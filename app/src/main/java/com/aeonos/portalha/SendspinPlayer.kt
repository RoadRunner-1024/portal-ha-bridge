package com.aeonos.portalha

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log
import com.sendspin.protocol.AudioFormat
import com.sendspin.protocol.ArtworkChannel
import com.sendspin.protocol.ClientPreferences
import com.sendspin.protocol.DiscoveryService
import com.sendspin.protocol.GroupPlaybackState
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.JsonOptionalAdapterFactory
import com.sendspin.protocol.OptionalRole
import com.sendspin.protocol.SendSpinClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * server/state carries only what CHANGED: an absent field means "unchanged", while a present
 * null means "cleared". Treating absent as empty blanks the title/artist on any partial update,
 * which churns the track identity and throws away the lyrics — so merge against what we had.
 */
private fun JsonOptional<String>.merge(previous: String): String = when (this) {
    is JsonOptional.Present -> value.orEmpty()
    else -> previous
}

/**
 * Sendspin player — the Portal as a synchronised multi-room speaker.
 *
 * Where DLNA makes each Portal an island, Sendspin (Open Home Foundation) is a push-based PCM
 * protocol built for group playback: Music Assistant keeps every member in step via a shared
 * clock. It also pushes track metadata and album artwork down the same connection, so the
 * now-playing overlay no longer needs to poll Home Assistant to find out what's playing.
 *
 * We take the client-initiated path: browse mDNS for a Sendspin server and connect to it.
 */
class SendspinPlayer(
    private val context: Context,
    private val deviceName: () -> String,
) {
    private companion object { const val TAG = "PortalHA" }

    private var scope: CoroutineScope? = null
    private var client: SendSpinClient? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectJob: Job? = null
    private var idleJob: Job? = null
    @Volatile private var audioPlayer: SendspinAudioPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Silence playback while a call / Alexa turn / the intercom needs the speaker, without
     * dropping our place in the group stream. See SendspinAudioPlayer.muteForSystem.
     */
    fun muteForSystem(muted: Boolean) { audioPlayer?.muteForSystem(muted) }

    // Anything that grabs focus properly (a phone call, another media app) mutes us too — the
    // BridgeService hooks only cover the things that DON'T take focus, like the intercom.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> muteForSystem(false)
            else -> muteForSystem(true)
        }
    }

    // Running view of the track, since server/state only sends what changed.
    @Volatile private var curTitle = ""
    @Volatile private var curArtist = ""
    @Volatile private var curAlbum = ""
    @Volatile private var curPosMs = 0
    @Volatile private var curDurMs = 0
    @Volatile private var curCommands: List<String> = emptyList()
    @Volatile private var progressSeq = 0

    /** What Sendspin says is playing. Pushed to us — no polling, no separate artwork fetch. */
    data class Track(
        val title: String, val artist: String, val album: String,
        val playing: Boolean, val positionMs: Int, val durationMs: Int,
        /**
         * Bumped only when the server actually sent a new progress snapshot. Most state messages
         * carry none, and re-anchoring the position clock to a stale [positionMs] on those drags
         * playback backwards — which is what put the lyrics behind the music.
         */
        val progressSeq: Int,
    )

    /** Fired whenever the track details change. Null means nothing is playing. */
    var onTrack: ((Track?) -> Unit)? = null

    /** Fired when new album artwork arrives, as raw image bytes. */
    var onArtwork: ((ByteArray?) -> Unit)? = null

    /** Ask the server to change this player's volume (0–100). */
    fun setVolume(pct: Int) {
        runCatching { client?.sendControllerCommand("volume", volume = pct.coerceIn(0, 100)) }
    }

    // Transport goes over the same controller channel as volume — Music Assistant owns the queue
    // either way, but routing it here keeps it aimed at THIS player rather than having to resolve
    // the right media_player entity through Home Assistant.
    fun next() = sendCommand("next", "next_track", "skip_next")
    fun previous() = sendCommand("previous", "previous_track", "skip_previous")
    fun stopPlayback() = sendCommand("stop", "pause")

    /** Jump to [positionMs] within the current track (server advertises "seek"). */
    fun seekTo(positionMs: Int) {
        Log.i(TAG, "sendspin: seek to ${positionMs}ms")
        runCatching { client?.sendSeek(positionMs.toLong()) }
            .onFailure { Log.w(TAG, "sendspin: seek failed: ${it.message}") }
    }
    fun playPause(currentlyPlaying: Boolean) =
        if (currentlyPlaying) sendCommand("pause", "play_pause") else sendCommand("play", "play_pause")

    private fun emitTrack(playing: Boolean) = onTrack?.invoke(Track(
        title = curTitle, artist = curArtist, album = curAlbum,
        playing = playing, positionMs = curPosMs, durationMs = curDurMs, progressSeq = progressSeq))

    /** Send the first candidate the server says it supports (falling back to the first). */
    private fun sendCommand(vararg candidates: String) {
        val supported = curCommands
        val cmd = candidates.firstOrNull { supported.isEmpty() || it in supported } ?: candidates.first()
        Log.i(TAG, "sendspin: controller command '$cmd' (server supports $supported)")
        runCatching { client?.sendControllerCommand(cmd) }
            .onFailure { Log.w(TAG, "sendspin: command '$cmd' failed: ${it.message}") }
    }

    val isConnected: Boolean get() = client != null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        // mDNS needs multicast to survive Wi-Fi power saving, same as the DLNA/DIAL servers.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("portalha-sendspin").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        val okHttp = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // long-lived streaming socket
            .build()
        // The protocol's optional fields are a sealed JsonOptional (absent vs present-null), which
        // needs its own factory — registered ahead of the reflective Kotlin adapter, per the
        // library's documented setup. A bare Moshi.Builder() fails on the first server/state.
        val moshi = Moshi.Builder()
            .add(JsonOptionalAdapterFactory())
            .addLast(KotlinJsonAdapterFactory())
            .build()

        // PCM only: the Portal has no spare headroom for decoding, and on a LAN the bandwidth
        // is irrelevant. Artwork is requested at a size that suits the now-playing overlay.
        val prefs = ClientPreferences(
            supportedFormats = listOf(
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 44100, bitDepth = 16),
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 48000, bitDepth = 16),
            ),
            artworkChannels = listOf(ArtworkChannel(source = "album", format = "jpeg")),
            supportedOptionalRoles = setOf(
                OptionalRole.PLAYER, OptionalRole.METADATA, OptionalRole.ARTWORK, OptionalRole.CONTROLLER),
        )

        val c = SendSpinClient(
            okHttpClient = okHttp,
            moshi = moshi,
            preferences = prefs,
            clientName = deviceName(),
            manufacturer = "Meta",
            productName = "Portal HA Bridge",
            softwareVersion = BuildConfig.VERSION_NAME,
            audioPlayerFactory = { buffer, clock ->
                SendspinAudioPlayer(buffer, clock).also { audioPlayer = it }
            },
        )
        client = c

        // Announce ourselves as media playback so the system ducks/notifies us appropriately.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { runCatching { audio.requestAudioFocus(it) } }

        // Follow the first server we find and keep following whichever is current.
        connectJob = s.launch {
            DiscoveryService(AndroidNsdBrowser(context)).discover().collectLatest { servers ->
                val server = servers.firstOrNull() ?: return@collectLatest
                Log.i(TAG, "sendspin: connecting to $server")
                runCatching { c.connect(server.wsUrl) }
                    .onFailure { Log.w(TAG, "sendspin: connect failed: ${it.message}") }
            }
        }

        s.launch { c.state.collect { Log.i(TAG, "sendspin: state $it") } }
        s.launch { c.albumArtwork.collect { art ->
            Log.i(TAG, "sendspin: artwork ${art?.size ?: 0} bytes")
            if (art != null && art.isNotEmpty()) onArtwork?.invoke(art)
        } }

        // Track details ride the same connection as the audio, so there's nothing to poll.
        s.launch {
            c.serverState.collect { st ->
                st.controller?.supportedCommands?.let {
                    if (it != curCommands) { curCommands = it; Log.i(TAG, "sendspin: controller supports $it") }
                }
                val md = st.metadata ?: return@collect
                curTitle = md.title.merge(curTitle)
                curArtist = md.artist.merge(curArtist)
                curAlbum = md.album.merge(curAlbum)
                // progress is a plain nullable: null here also means "unchanged". Only a genuinely
                // new snapshot may re-anchor the position clock.
                md.progress?.let {
                    curPosMs = it.trackProgress.toInt()
                    curDurMs = it.trackDuration.toInt()
                    progressSeq++
                }
                if (curTitle.isBlank() && curArtist.isBlank()) { onTrack?.invoke(null); return@collect }
                emitTrack(playing = c.groupPlaybackState.value == GroupPlaybackState.PLAYING)
            }
        }
        s.launch {
            c.groupPlaybackState.collect { gs ->
                Log.i(TAG, "sendspin: playback $gs")
                idleJob?.cancel()
                if (curTitle.isBlank() && curArtist.isBlank()) return@collect
                // Re-emit the same track with the new playing state. Music Assistant reports
                // STOPPED when it ends the stream on a pause, so treating that as "nothing is
                // playing" tore the whole now-playing screen down the moment you hit pause.
                emitTrack(playing = gs == GroupPlaybackState.PLAYING)
                if (gs == GroupPlaybackState.STOPPED) {
                    // Genuinely finished rather than paused? Only then clear the screen.
                    idleJob = s.launch {
                        delay(90_000)
                        curTitle = ""; curArtist = ""; curAlbum = ""; curPosMs = 0; curDurMs = 0
                        onTrack?.invoke(null)
                    }
                }
            }
        }
    }

    fun stop() {
        connectJob?.cancel(); connectJob = null
        idleJob?.cancel(); idleJob = null
        focusRequest?.let { req ->
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { audio.abandonAudioFocusRequest(req) }
        }
        focusRequest = null
        audioPlayer = null
        runCatching { client?.disconnect("shutting_down") }
        client = null
        scope?.cancel(); scope = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
