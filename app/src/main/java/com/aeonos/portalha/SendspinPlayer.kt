package com.aeonos.portalha

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.sendspin.protocol.AudioFormat
import com.sendspin.protocol.ArtworkChannel
import com.sendspin.protocol.ClientPreferences
import com.sendspin.protocol.DiscoveryService
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    /** Called on the main thread with the current track, or null when nothing is playing. */
    var onTrack: ((title: String, artist: String, album: String, artwork: ByteArray?) -> Unit)? = null

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
            audioPlayerFactory = { buffer, clock -> SendspinAudioPlayer(buffer, clock) },
        )
        client = c

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
        } }
    }

    fun stop() {
        connectJob?.cancel(); connectJob = null
        runCatching { client?.disconnect("shutting_down") }
        client = null
        scope?.cancel(); scope = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
