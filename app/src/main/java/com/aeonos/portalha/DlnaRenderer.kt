package com.aeonos.portalha

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * UPnP/DLNA MediaRenderer, so the Portal shows up as a speaker in Music Assistant
 * (its DLNA provider auto-discovers it and the Universal Player adopts it) and any
 * other DLNA controller on the LAN.
 *
 * Deliberately modelled on [DialServer]: the same SSDP multicast responder, the same
 * hand-rolled HTTP server, and the same WifiManager.MulticastLock (Portals silently drop
 * multicast without it, so M-SEARCHes never arrive). No UPnP library, no new playback
 * dependency — Android's MediaPlayer plays the stream URL the controller hands us, and
 * AudioManager carries the volume.
 *
 * The renderer implements the three standard MediaRenderer services, trimmed to the actions
 * a controller actually drives: AVTransport (SetAVTransportURI/Play/Pause/Stop/Seek + the
 * Get* state reads), RenderingControl (volume/mute), and ConnectionManager (protocol info).
 * GENA eventing is implemented for the two LastChange variables so the controller reflects
 * play/pause/volume without polling.
 *
 * Audio arbitration is by audio focus: playback requests AUDIOFOCUS_GAIN with USAGE_MEDIA,
 * so an Alexa turn or a call (which grab focus) pauses us, and we resume on focus return.
 * BridgeService also calls [pauseForSystem]/[resumeAfterSystem] for the intercom, which
 * doesn't take focus.
 */
class DlnaRenderer(
    private val context: Context,
    private val friendlyName: () -> String,
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        // Distinct from DIAL 8060, MJPEG 8080, RTSP 8554.
        const val HTTP_PORT = 8061
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val SVC_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SVC_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val SVC_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
        // How long a GENA subscription lasts before the controller must renew.
        private const val EVENT_TIMEOUT_S = 1800
    }

    // Stable UDN for this Portal (survives restarts so the controller keeps the same player).
    private val uuid = UUID.nameUUIDFromBytes(
        "portal-ha-dlna-${Prefs(context).deviceId}".toByteArray()).toString()
    private val udn = "uuid:$uuid"
    // Bumped each start so controllers know we rebooted and drop stale subscriptions.
    private val bootId = (System.currentTimeMillis() / 1000L).toInt()

    @Volatile private var running = false
    private var ssdpSocket: MulticastSocket? = null
    private var httpSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // All player state + MediaPlayer access is confined to this one thread.
    private val playThread = HandlerThread("dlna-play").also { it.start() }
    private val play = Handler(playThread.looper)

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    // ── Transport state (guarded by the play thread) ────────────────────────────
    @Volatile private var transportState = "NO_MEDIA_PRESENT"   // UPnP AVTransportState
    @Volatile private var currentUri = ""
    @Volatile private var currentMeta = ""     // the DIDL-Lite the controller sent
    // Gapless queue: Music Assistant preloads the upcoming track via SetNextAVTransportURI and
    // expects us to auto-advance to it when the current one ends. Without this, MA's queue never
    // moves on by itself (it isn't notified to push the next track).
    @Volatile private var nextUri = ""
    @Volatile private var nextMeta = ""
    @Volatile private var durationMs = 0
    // In MA "flow" mode the whole queue is one continuous stream, so the MediaPlayer position
    // keeps climbing across tracks. Record the stream position at each track boundary so we can
    // report a track-relative position (needed to sync LRCLIB lyrics, which are 0-based per track).
    @Volatile private var trackBaseMs = 0
    @Volatile private var prepared = false
    @Volatile private var pausedBySystem = false   // paused for a call/Alexa/intercom

    /** What's playing, parsed from the controller's DIDL-Lite — for the now-playing overlay. */
    data class NowPlaying(
        val title: String, val artist: String, val album: String,
        val artUri: String, val durationSec: Int,
    )
    @Volatile var nowPlaying: NowPlaying? = null
        private set

    /** BridgeService listens so the overlay can react to play/pause/track/volume changes. */
    interface Listener {
        fun onRendererStateChanged(state: String, np: NowPlaying?)
        fun onRendererVolumeChanged(volumePct: Int)
    }
    @Volatile var listener: Listener? = null

    // Public read state + controls for the overlay (all thread-safe / posted to the play thread).
    fun currentState(): String = transportState
    fun volumePct(): Int = getVolume()
    fun positionMs(): Int = positionRead()
    fun trackPositionMs(): Int = (positionRead() - trackBaseMs).coerceAtLeast(0)
    fun trackDurationMs(): Int = durationMs
    fun playPauseToggle() = play.post { if (transportState == "PLAYING") doPause(false) else doPlay() }
    fun stopFromUi() = play.post { doStop() }
    fun setVolumeFromUi(pct: Int) = setVolume(pct)
    fun nudgeVolume(delta: Int) = setVolume((getVolume() + delta).coerceIn(0, 100))

    fun start() {
        if (running) return
        running = true
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("portal-ha-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread(name = "dlna-ssdp", isDaemon = true) { ssdpLoop() }
        thread(name = "dlna-http", isDaemon = true) { httpLoop() }
        thread(name = "dlna-alive", isDaemon = true) { runCatching { announce("ssdp:alive") } }
        Log.i(TAG, "dlna: MediaRenderer started (port $HTTP_PORT, udn $udn)")
    }

    fun stop() {
        running = false
        runCatching { announce("ssdp:byebye") }
        runCatching { ssdpSocket?.close() }
        runCatching { httpSocket?.close() }
        runCatching { multicastLock?.release() }
        play.post { releasePlayer() }
    }

    /** True while music is actually playing — used by the screensaver/idle checks. */
    fun isPlaying(): Boolean = transportState == "PLAYING"

    private fun localIp(): String {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            ni.inetAddresses.toList().forEach { a ->
                if (!a.isLoopbackAddress && a.address.size == 4 && a.isSiteLocalAddress)
                    return a.hostAddress ?: "127.0.0.1"
            }
        }
        return "127.0.0.1"
    }

    private fun descUrl() = "http://${localIp()}:$HTTP_PORT/desc.xml"

    // ── SSDP discovery ──────────────────────────────────────────────────────────

    // The (ST, USN-suffix) pairs we answer for. rootdevice + the bare UDN + the device
    // type + each service, which is what a controller's M-SEARCH asks for.
    private fun targets(): List<Pair<String, String>> = listOf(
        "upnp:rootdevice" to "$udn::upnp:rootdevice",
        udn to udn,
        DEVICE_TYPE to "$udn::$DEVICE_TYPE",
        SVC_AVT to "$udn::$SVC_AVT",
        SVC_RC to "$udn::$SVC_RC",
        SVC_CM to "$udn::$SVC_CM",
    )

    private fun ssdpLoop() {
        try {
            // Unbound + reuse-address so we coexist with DialServer's socket on port 1900.
            val sock = MulticastSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(SSDP_PORT))
            ssdpSocket = sock
            sock.joinGroup(InetAddress.getByName(SSDP_ADDR))
            val buf = ByteArray(4096)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val msg = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                if (!msg.startsWith("M-SEARCH")) continue
                val st = Regex("(?im)^ST:\\s*(.+?)\\s*$").find(msg)?.groupValues?.get(1) ?: continue
                val matches = when (st) {
                    "ssdp:all" -> targets()
                    else -> targets().filter { it.first == st }
                }
                for ((stName, usn) in matches) {
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "CACHE-CONTROL: max-age=1800\r\n" +
                        "EXT:\r\n" +
                        "LOCATION: ${descUrl()}\r\n" +
                        "SERVER: Android UPnP/1.1 PortalHA/1.0\r\n" +
                        "ST: $stName\r\n" +
                        "USN: $usn\r\n" +
                        "BOOTID.UPNP.ORG: $bootId\r\n" +
                        "CONFIGID.UPNP.ORG: 1\r\n\r\n"
                    runCatching {
                        sock.send(DatagramPacket(resp.toByteArray(), resp.length, pkt.address, pkt.port))
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "dlna: ssdp loop died: ${e.message}")
        }
    }

    // Proactive multicast NOTIFY on start (alive) and stop (byebye) so controllers see the
    // player promptly rather than only on their next search.
    private fun announce(nts: String) {
        val sock = MulticastSocket()
        val group = InetAddress.getByName(SSDP_ADDR)
        try {
            for ((stName, usn) in targets()) {
                val extra = if (nts == "ssdp:alive")
                    "CACHE-CONTROL: max-age=1800\r\nLOCATION: ${descUrl()}\r\nSERVER: Android UPnP/1.1 PortalHA/1.0\r\n"
                else ""
                val msg = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "NTS: $nts\r\n" +
                    "NT: $stName\r\n" +
                    "USN: $usn\r\n" +
                    extra +
                    "BOOTID.UPNP.ORG: $bootId\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n"
                sock.send(DatagramPacket(msg.toByteArray(), msg.length, group, SSDP_PORT))
            }
        } finally {
            runCatching { sock.close() }
        }
    }

    // ── HTTP server (descriptions + SOAP control + GENA eventing) ───────────────

    private fun httpLoop() {
        try {
            val server = ServerSocket(HTTP_PORT)
            httpSocket = server
            while (running) {
                val client = server.accept()
                thread(isDaemon = true) {
                    runCatching { handle(client) }
                        .onFailure { Log.w(TAG, "dlna: http request failed: ${it.message}") }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "dlna: http loop died: ${e.message}")
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 5000
            val input = BufferedInputStream(s.getInputStream())
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: return
            val method = requestLine.getOrElse(0) { "" }
            val path = requestLine.getOrElse(1) { "/" }
            val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)").find(head)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = readBody(input, contentLength)
            val out = s.getOutputStream()

            when {
                method == "GET" && path.startsWith("/desc.xml") ->
                    respond(out, "200 OK", "text/xml; charset=utf-8", deviceDescription())
                method == "GET" && path.startsWith("/scpd/AVTransport") ->
                    respond(out, "200 OK", "text/xml; charset=utf-8", SCPD_AVT)
                method == "GET" && path.startsWith("/scpd/RenderingControl") ->
                    respond(out, "200 OK", "text/xml; charset=utf-8", SCPD_RC)
                method == "GET" && path.startsWith("/scpd/ConnectionManager") ->
                    respond(out, "200 OK", "text/xml; charset=utf-8", SCPD_CM)
                method == "POST" && path.startsWith("/control/") -> {
                    val soapAction = Regex("(?im)^SOAPACTION:\\s*\"?([^\"\\r\\n]+)\"?")
                        .find(head)?.groupValues?.get(1) ?: ""
                    handleSoap(out, soapAction, body)
                }
                method.equals("SUBSCRIBE", true) -> handleSubscribe(out, head, path)
                method.equals("UNSUBSCRIBE", true) -> {
                    val sid = Regex("(?im)^SID:\\s*(.+?)\\s*$").find(head)?.groupValues?.get(1)
                    if (sid != null) subscribers.remove(sid.trim())
                    respond(out, "200 OK", "text/plain", "")
                }
                else -> respond(out, "404 Not Found", "text/plain", "")
            }
            out.flush()
        }
    }

    // ── SOAP action dispatch ────────────────────────────────────────────────────

    private fun handleSoap(out: OutputStream, soapAction: String, body: String) {
        val action = soapAction.substringAfterLast('#')
        val resp: String? = when (action) {
            // AVTransport
            "SetAVTransportURI" -> {
                val uri = soapArg(body, "CurrentURI")
                val meta = soapArg(body, "CurrentURIMetaData")
                play.post { setUri(uri, meta) }
                soapResponse(SVC_AVT, action, "")
            }
            "SetNextAVTransportURI" -> {
                val nUri = soapArg(body, "NextURI")
                val nMeta = soapArg(body, "NextURIMetaData")
                play.post {
                    nextUri = nUri; nextMeta = nMeta
                    Log.i(TAG, "dlna: setNextUri title=${parseDidl(nMeta)?.title} blank=${nUri.isBlank()}")
                }
                soapResponse(SVC_AVT, action, "")
            }
            "Play" -> { play.post { doPlay() }; soapResponse(SVC_AVT, action, "") }
            "Pause" -> { play.post { doPause(false) }; soapResponse(SVC_AVT, action, "") }
            "Stop" -> { play.post { doStop() }; soapResponse(SVC_AVT, action, "") }
            "Seek" -> {
                val target = soapArg(body, "Target")
                play.post { doSeek(target) }
                soapResponse(SVC_AVT, action, "")
            }
            "GetTransportInfo" -> soapResponse(SVC_AVT, action,
                "<CurrentTransportState>$transportState</CurrentTransportState>" +
                "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                "<CurrentSpeed>1</CurrentSpeed>")
            "GetPositionInfo" -> {
                val pos = positionRead()
                soapResponse(SVC_AVT, action,
                    "<Track>1</Track>" +
                    "<TrackDuration>${hms(durationMs)}</TrackDuration>" +
                    "<TrackMetaData>${xmlEscape(currentMeta)}</TrackMetaData>" +
                    "<TrackURI>${xmlEscape(currentUri)}</TrackURI>" +
                    "<RelTime>${hms(pos)}</RelTime>" +
                    "<AbsTime>${hms(pos)}</AbsTime>" +
                    "<RelCount>2147483647</RelCount>" +
                    "<AbsCount>2147483647</AbsCount>")
            }
            "GetMediaInfo" -> soapResponse(SVC_AVT, action,
                "<NrTracks>${if (currentUri.isEmpty()) 0 else 1}</NrTracks>" +
                "<MediaDuration>${hms(durationMs)}</MediaDuration>" +
                "<CurrentURI>${xmlEscape(currentUri)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEscape(currentMeta)}</CurrentURIMetaData>" +
                "<NextURI>${xmlEscape(nextUri)}</NextURI>" +
                "<NextURIMetaData>${xmlEscape(nextMeta)}</NextURIMetaData>" +
                "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>")
            "GetTransportSettings" -> soapResponse(SVC_AVT, action,
                "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>")
            "GetDeviceCapabilities" -> soapResponse(SVC_AVT, action,
                "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia>" +
                "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>")
            "SetPlayMode" -> soapResponse(SVC_AVT, action, "")
            // RenderingControl
            "GetVolume" -> soapResponse(SVC_RC, action, "<CurrentVolume>${getVolume()}</CurrentVolume>")
            "SetVolume" -> {
                soapArg(body, "DesiredVolume").toIntOrNull()?.let { setVolume(it) }
                soapResponse(SVC_RC, action, "")
            }
            "GetMute" -> soapResponse(SVC_RC, action, "<CurrentMute>${if (isMuted()) 1 else 0}</CurrentMute>")
            "SetMute" -> {
                val on = soapArg(body, "DesiredMute").let { it == "1" || it.equals("true", true) }
                setMute(on)
                soapResponse(SVC_RC, action, "")
            }
            "ListPresets" -> soapResponse(SVC_RC, action, "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>")
            "SelectPreset" -> soapResponse(SVC_RC, action, "")
            // ConnectionManager
            "GetProtocolInfo" -> soapResponse(SVC_CM, action,
                "<Source></Source><Sink>${xmlEscape(SINK_PROTOCOLS)}</Sink>")
            "GetCurrentConnectionIDs" -> soapResponse(SVC_CM, action, "<ConnectionIDs>0</ConnectionIDs>")
            "GetCurrentConnectionInfo" -> soapResponse(SVC_CM, action,
                "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>" +
                "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>" +
                "<PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction>" +
                "<Status>OK</Status>")
            else -> null
        }
        if (resp == null) {
            respond(out, "501 Not Implemented", "text/xml; charset=utf-8", soapFault())
        } else {
            respond(out, "200 OK", "text/xml; charset=utf-8", resp)
        }
    }

    private fun soapArg(body: String, name: String): String {
        val m = Regex("(?is)<$name[^>]*>(.*?)</$name>").find(body) ?: return ""
        return xmlUnescape(m.groupValues[1].trim())
    }

    // ── Playback (all on the play thread) ───────────────────────────────────────

    private fun setUri(uri: String, meta: String) {
        // MA "flow" mode streams the whole queue as one continuous URL and re-sends
        // SetAVTransportURI with fresh metadata when the track changes — same URL. Update the
        // now-playing info WITHOUT tearing down and restarting the stream (which would hiccup).
        val sameStream = uri.isNotBlank() && uri == currentUri && player != null
        val newNp = parseDidl(meta)
        val trackChanged = newNp != null &&
            (newNp.title != nowPlaying?.title || newNp.artist != nowPlaying?.artist)
        currentUri = uri
        currentMeta = meta
        nowPlaying = newNp
        Log.i(TAG, "dlna: setUri sameStream=$sameStream trackChanged=$trackChanged " +
            "title=${newNp?.title} dur=${newNp?.durationSec}s uri=$uri")
        if (sameStream) {
            // Continuous stream, new track metadata → rebase the position so lyrics line up.
            if (trackChanged) trackBaseMs = positionRead()
            listener?.onRendererStateChanged(transportState, nowPlaying)
            return
        }
        trackBaseMs = 0
        durationMs = 0
        prepared = false
        // An explicit new track invalidates any previously preloaded "next" (MA will resend one).
        if (!advancing) { nextUri = ""; nextMeta = "" }
        releasePlayer()
        if (uri.isBlank()) { setTransport("NO_MEDIA_PRESENT"); return }
        setTransport("TRANSITIONING")
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
            setOnPreparedListener {
                prepared = true
                durationMs = runCatching { duration }.getOrDefault(0)
                // A controller that sent SetAVTransportURI then Play may have set wantPlay.
                if (wantPlay) { wantPlay = false; startPlayback() } else setTransport("STOPPED")
            }
            setOnCompletionListener {
                Log.i(TAG, "dlna: track completed, preloaded next=${nextUri.isNotBlank()}")
                // Gapless: if MA preloaded a next track, roll straight into it; else stop.
                if (!advanceToNext()) { setTransport("STOPPED"); abandonFocus() }
            }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "dlna: MediaPlayer error what=$what extra=$extra uri=$uri")
                setTransport("STOPPED")
                true
            }
        }
        player = mp
        runCatching {
            mp.setDataSource(uri)
            mp.prepareAsync()
        }.onFailure {
            Log.w(TAG, "dlna: setDataSource failed: ${it.message}")
            setTransport("STOPPED")
        }
    }

    // True only while auto-advancing to a preloaded next track, so setUri keeps the queued URI.
    @Volatile private var advancing = false

    /** On track completion, roll into the MA-preloaded next track (gapless). Play thread only. */
    private fun advanceToNext(): Boolean {
        if (nextUri.isBlank()) return false
        val u = nextUri; val m = nextMeta
        nextUri = ""; nextMeta = ""
        Log.i(TAG, "dlna: auto-advance -> ${parseDidl(m)?.title}")
        wantPlay = true
        advancing = true
        try { setUri(u, m) } finally { advancing = false }
        return true
    }

    @Volatile private var wantPlay = false

    private fun doPlay() {
        val mp = player
        if (mp == null) { return }
        if (prepared) startPlayback() else wantPlay = true   // start once prepared
    }

    private fun startPlayback() {
        if (!requestFocus()) { Log.w(TAG, "dlna: audio focus denied"); return }
        runCatching { player?.start(); setTransport("PLAYING") }
            .onFailure { Log.w(TAG, "dlna: start failed: ${it.message}") }
    }

    private fun doPause(bySystem: Boolean) {
        runCatching {
            if (player?.isPlaying == true) {
                player?.pause()
                pausedBySystem = bySystem
                setTransport("PAUSED_PLAYBACK")
            }
        }
    }

    private fun doStop() {
        wantPlay = false
        runCatching { if (player?.isPlaying == true) player?.stop() }
        prepared = false
        releasePlayer()
        setTransport("STOPPED")
        abandonFocus()
    }

    private fun doSeek(target: String) {
        val ms = parseHms(target)
        if (ms >= 0) runCatching { player?.seekTo(ms) }
    }

    private fun positionRead(): Int =
        runCatching { if (player != null && prepared) player!!.currentPosition else 0 }.getOrDefault(0)

    private fun releasePlayer() {
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
    }

    // ── Audio focus + arbitration ───────────────────────────────────────────────

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> play.post { doStop() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> play.post { doPause(true) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                play.post { runCatching { player?.setVolume(0.2f, 0.2f) } }
            AudioManager.AUDIOFOCUS_GAIN -> play.post {
                runCatching { player?.setVolume(1f, 1f) }
                if (pausedBySystem && transportState == "PAUSED_PLAYBACK") {
                    pausedBySystem = false
                    startPlayback()
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        @Suppress("DEPRECATION")
        return audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(focusListener)
        }
    }

    /** BridgeService: something that doesn't take audio focus (intercom) needs the speaker. */
    fun pauseForSystem() = play.post { if (transportState == "PLAYING") doPause(true) }

    /** BridgeService: that system audio is done — resume if WE had paused for it. */
    fun resumeAfterSystem() = play.post {
        if (pausedBySystem && transportState == "PAUSED_PLAYBACK") {
            pausedBySystem = false
            startPlayback()
        }
    }

    // ── Volume (maps the controller's 0..100 onto the device media stream) ───────

    private fun maxVol() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private fun getVolume(): Int =
        Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / maxVol()).toInt()
    private fun setVolume(pct: Int) {
        val v = Math.round(pct.coerceIn(0, 100) / 100.0 * maxVol()).toInt()
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        notifyEvent(SVC_RC)
        listener?.onRendererVolumeChanged(getVolume())
    }
    private fun isMuted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            audio.isStreamMute(AudioManager.STREAM_MUSIC) else false
    private fun setMute(on: Boolean) {
        runCatching {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        }
        notifyEvent(SVC_RC)
    }

    // ── Transport-state change → GENA notify ────────────────────────────────────

    private fun setTransport(state: String) {
        if (transportState == state) return
        Log.i(TAG, "dlna: transport $transportState -> $state (subs=${subscribers.size})")
        transportState = state
        notifyEvent(SVC_AVT)
        listener?.onRendererStateChanged(state, nowPlaying)
    }

    // Pull title/artist/album/art/duration out of the DIDL-Lite the controller sent.
    private fun parseDidl(meta: String): NowPlaying? {
        if (meta.isBlank()) return null
        fun tag(name: String): String {
            val m = Regex("(?is)<$name[^>]*>(.*?)</$name>").find(meta) ?: return ""
            return xmlUnescape(m.groupValues[1].trim())
        }
        val title = tag("dc:title")
        val artist = tag("upnp:artist").ifBlank { tag("dc:creator") }
        val album = tag("upnp:album")
        val art = Regex("(?is)<upnp:albumArtURI[^>]*>(.*?)</upnp:albumArtURI>")
            .find(meta)?.groupValues?.get(1)?.trim()?.let { xmlUnescape(it) } ?: ""
        val durAttr = Regex("(?i)duration=\"([0-9:.]+)\"").find(meta)?.groupValues?.get(1) ?: ""
        val durSec = parseHms(durAttr).let { if (it > 0) it / 1000 else 0 }
        if (title.isBlank() && artist.isBlank()) return null
        return NowPlaying(title, artist, album, art, durSec)
    }

    // ── GENA eventing ───────────────────────────────────────────────────────────

    private data class Sub(val callback: String, var seq: Int = 0)
    private val subscribers = ConcurrentHashMap<String, Sub>()

    private fun handleSubscribe(out: OutputStream, head: String, path: String) {
        val service = when {
            path.contains("AVTransport") -> SVC_AVT
            path.contains("RenderingControl") -> SVC_RC
            else -> SVC_CM
        }
        val sid = Regex("(?im)^SID:\\s*(.+?)\\s*$").find(head)?.groupValues?.get(1)?.trim()
        if (sid != null) {
            // Renewal — just extend.
            respond(out, "200 OK", "text/plain", "",
                "SID: $sid\r\nTIMEOUT: Second-$EVENT_TIMEOUT_S\r\n")
            return
        }
        val callback = Regex("(?im)^CALLBACK:\\s*<([^>]+)>").find(head)?.groupValues?.get(1) ?: ""
        val newSid = "uuid:${UUID.randomUUID()}"
        if (callback.isNotEmpty()) subscribers[newSid] = Sub(callback)
        Log.i(TAG, "dlna: GENA subscribe ${service.substringAfterLast(':')} cb=$callback")
        respond(out, "200 OK", "text/plain", "",
            "SID: $newSid\r\nTIMEOUT: Second-$EVENT_TIMEOUT_S\r\n")
        // Initial event so the controller has our current state immediately.
        thread(isDaemon = true) { runCatching { sendEvent(newSid, subscribers[newSid], service) } }
    }

    // Push the LastChange for [service] to every subscriber (best-effort, off the play thread).
    private fun notifyEvent(service: String) {
        if (subscribers.isEmpty()) return
        val snapshot = subscribers.entries.toList()
        thread(isDaemon = true) {
            for ((sid, sub) in snapshot) runCatching { sendEvent(sid, sub, service) }
        }
    }

    private fun sendEvent(sid: String, sub: Sub?, service: String) {
        sub ?: return
        val lastChange = when (service) {
            SVC_AVT -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">" +
                "<InstanceID val=\"0\">" +
                "<TransportState val=\"$transportState\"/>" +
                "<CurrentTrackURI val=\"${xmlEscape(currentUri)}\"/>" +
                "<CurrentTrackDuration val=\"${hms(durationMs)}\"/>" +
                "<CurrentTrackMetaData val=\"${xmlEscape(currentMeta)}\"/>" +
                "</InstanceID></Event>"
            else -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">" +
                "<InstanceID val=\"0\">" +
                "<Volume channel=\"Master\" val=\"${getVolume()}\"/>" +
                "<Mute channel=\"Master\" val=\"${if (isMuted()) 1 else 0}\"/>" +
                "</InstanceID></Event>"
        }
        val propset = "<?xml version=\"1.0\"?>\n" +
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            "<e:property><LastChange>${xmlEscape(lastChange)}</LastChange></e:property>" +
            "</e:propertyset>"
        val bytes = propset.toByteArray(StandardCharsets.UTF_8)
        // NOTE: HttpURLConnection CANNOT do this — setRequestMethod("NOTIFY") throws
        // ProtocolException (Java only permits a fixed method set), which silently killed every
        // GENA event and left controllers blind to track changes. Hand-roll the request instead.
        val url = URL(sub.callback)
        val port = if (url.port > 0) url.port else 80
        val path = (if (url.path.isNullOrEmpty()) "/" else url.path) +
            (if (url.query.isNullOrEmpty()) "" else "?" + url.query)
        val head = "NOTIFY $path HTTP/1.1\r\n" +
            "HOST: ${url.host}:$port\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
            "CONTENT-LENGTH: ${bytes.size}\r\n" +
            "NT: upnp:event\r\n" +
            "NTS: upnp:propchange\r\n" +
            "SID: $sid\r\n" +
            "SEQ: ${sub.seq}\r\n" +
            "CONNECTION: close\r\n\r\n"
        sub.seq++
        runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(url.host, port), 3000)
                sock.soTimeout = 3000
                sock.getOutputStream().apply {
                    write(head.toByteArray(StandardCharsets.UTF_8))
                    write(bytes)
                    flush()
                }
                readHead(BufferedInputStream(sock.getInputStream()))   // best-effort ack
            }
        }.onFailure {
            Log.w(TAG, "dlna: GENA notify failed (${sub.callback}): ${it.message}")
            // Controller gone — drop the subscription so we stop hammering it.
            subscribers.remove(sid)
        }
    }

    // ── HTTP helpers (same shape as DialServer) ─────────────────────────────────

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(c.toChar())
            if (c == '\n'.code && prev == '\n'.code) break
            if (c != '\r'.code) prev = c
            if (sb.length > 65536) return null
        }
        return sb.toString()
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val b = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(b, off, length - off)
            if (n < 0) break
            off += n
        }
        return String(b, 0, off, StandardCharsets.UTF_8)
    }

    private fun respond(
        out: OutputStream, status: String, contentType: String, body: String,
        extraHeaders: String = ""
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.write(("HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
    }

    // ── XML: device description + SCPDs + SOAP envelopes ────────────────────────

    private fun deviceDescription(): String = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" configId="1">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>$DEVICE_TYPE</deviceType>
    <friendlyName>${xmlEscape(friendlyName())}</friendlyName>
    <manufacturer>Meta</manufacturer>
    <manufacturerURL>https://www.home-assistant.io</manufacturerURL>
    <modelName>Portal HA Bridge</modelName>
    <modelNumber>1</modelNumber>
    <UDN>$udn</UDN>
    <serviceList>
      <service>
        <serviceType>$SVC_AVT</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/scpd/AVTransport.xml</SCPDURL>
        <controlURL>/control/AVTransport</controlURL>
        <eventSubURL>/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>$SVC_RC</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/scpd/RenderingControl.xml</SCPDURL>
        <controlURL>/control/RenderingControl</controlURL>
        <eventSubURL>/event/RenderingControl</eventSubURL>
      </service>
      <service>
        <serviceType>$SVC_CM</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/scpd/ConnectionManager.xml</SCPDURL>
        <controlURL>/control/ConnectionManager</controlURL>
        <eventSubURL>/event/ConnectionManager</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""

    private fun soapResponse(service: String, action: String, args: String): String =
        """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:${action}Response xmlns:u="$service">$args</u:${action}Response></s:Body></s:Envelope>"""

    private fun soapFault(): String =
        """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription></UPnPError></detail>
</s:Fault></s:Body></s:Envelope>"""

    // Time helpers: UPnP wants H:MM:SS.
    private fun hms(ms: Int): String {
        if (ms <= 0) return "0:00:00"
        val s = ms / 1000; return "${s / 3600}:${"%02d".format((s % 3600) / 60)}:${"%02d".format(s % 60)}"
    }
    private fun parseHms(v: String): Int {
        val parts = v.trim().split(":")
        return runCatching {
            when (parts.size) {
                3 -> ((parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble()).toInt()) * 1000
                2 -> ((parts[0].toInt() * 60 + parts[1].toDouble()).toInt()) * 1000
                else -> -1
            }
        }.getOrDefault(-1)
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
    private fun xmlUnescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}

// The sink protocols we accept. http-get with * lets the controller pick the format; the
// common MA/DLNA codecs (mp3, flac, wav, aac) are listed so it doesn't rule us out.
private const val SINK_PROTOCOLS =
    "http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*," +
    "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/L16:*," +
    "http-get:*:audio/aac:*,http-get:*:audio/mp4:*,http-get:*:audio/ogg:*,http-get:*:*:*"

// Trimmed but valid SCPDs — only the actions the renderer implements, with the state
// variables they reference. async_upnp_client (Music Assistant) parses these to learn
// which actions exist, so an omitted action simply won't be offered by the controller.
private const val SCPD_AVT = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
<action><name>SetAVTransportURI</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
</argumentList></action>
<action><name>SetNextAVTransportURI</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>NextURI</name><direction>in</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
<argument><name>NextURIMetaData</name><direction>in</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
</argumentList></action>
<action><name>Play</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
</argumentList></action>
<action><name>Pause</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
<action><name>Stop</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
<action><name>Seek</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetTransportInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetPositionInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
<argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
<argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
<argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
<argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
<argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
<argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
<argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetMediaInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
<argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
<argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
<argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
<argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
<argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
<argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>
<argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>
<argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetTransportSettings</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument>
<argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetDeviceCapabilities</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>PlayMedia</name><direction>out</direction><relatedStateVariable>PossiblePlaybackStorageMedia</relatedStateVariable></argument>
<argument><name>RecMedia</name><direction>out</direction><relatedStateVariable>PossibleRecordStorageMedia</relatedStateVariable></argument>
<argument><name>RecQualityModes</name><direction>out</direction><relatedStateVariable>PossibleRecordQualityModes</relatedStateVariable></argument>
</argumentList></action>
</actionList>
<serviceStateTable>
<stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType>
<allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>TRANSITIONING</allowedValue><allowedValue>NO_MEDIA_PRESENT</allowedValue></allowedValueList></stateVariable>
<stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>PossiblePlaybackStorageMedia</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>PossibleRecordStorageMedia</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentPlayMode</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentRecordQualityMode</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>PossibleRecordQualityModes</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType><allowedValueList><allowedValue>REL_TIME</allowedValue><allowedValue>TRACK_NR</allowedValue></allowedValueList></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
</serviceStateTable></scpd>"""

private const val SCPD_RC = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
<action><name>GetVolume</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
</argumentList></action>
<action><name>SetVolume</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetMute</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
</argumentList></action>
<action><name>SetMute</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
</argumentList></action>
<action><name>ListPresets</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>CurrentPresetNameList</name><direction>out</direction><relatedStateVariable>PresetNameList</relatedStateVariable></argument>
</argumentList></action>
<action><name>SelectPreset</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>PresetName</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_PresetName</relatedStateVariable></argument>
</argumentList></action>
</actionList>
<serviceStateTable>
<stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
<stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
<stateVariable sendEvents="no"><name>PresetNameList</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_PresetName</name><dataType>string</dataType><allowedValueList><allowedValue>FactoryDefaults</allowedValue></allowedValueList></stateVariable>
</serviceStateTable></scpd>"""

private const val SCPD_CM = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
<action><name>GetProtocolInfo</name><argumentList>
<argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
<argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetCurrentConnectionIDs</name><argumentList>
<argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetCurrentConnectionInfo</name><argumentList>
<argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
<argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>
<argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>
<argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>
<argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>
<argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
<argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>
<argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>
</argumentList></action>
</actionList>
<serviceStateTable>
<stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>
</serviceStateTable></scpd>"""
