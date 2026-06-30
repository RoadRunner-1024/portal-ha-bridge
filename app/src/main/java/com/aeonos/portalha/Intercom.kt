package com.aeonos.portalha

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Audio-only Portal-to-Portal intercom over the shared MQTT broker.
//
// Half-duplex "push to announce": hold the button, your mic streams live to the
// other Portals running this app, they play it out loud; release to stop. No
// video, no echo-cancellation needed (one-way at a time).
//
// Everything rides the broker we're already connected to:
//   - presence  portal/intercom/presence/<id>   retained "name|ip|ts"
//   - lock      portal/intercom/lock            retained "speakerId|name|ts"
//   - audio     portal/intercom/audio/<id>/<tgt> QoS0  raw PCM16 LE frames
// where <tgt> is "all" (broadcast) or a specific device id (one-to-one).
//
// The speaking lock is first-come and carries a timestamp so a crashed talker
// can't wedge it — peers treat a lock older than LOCK_TTL_MS as free, and the
// talker refreshes it every LOCK_REFRESH_MS while holding.
class Intercom(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: () -> String,
    private val localIp: () -> String?,
    // Publish via the service's MQTT client. MUST NOT be invoked from the Paho
    // callback thread (see BridgeService.messageArrived) — we only call it from
    // the capture thread and the MQTT loop thread.
    private val publish: (topic: String, payload: ByteArray, qos: Int, retained: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "PortalHA"
        const val PRESENCE_PREFIX = "portal/intercom/presence/"
        const val LOCK_TOPIC = "portal/intercom/lock"
        const val AUDIO_PREFIX = "portal/intercom/audio/"     // + "<sender>/<target>"

        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = SAMPLE_RATE / 50    // 20 ms = 320 samples
        private const val LOCK_TTL_MS = 8_000L                // older lock = stale/free
        private const val LOCK_REFRESH_MS = 3_000L            // talker re-asserts while held
        private const val PLAY_IDLE_MS = 1_200L               // restore volume after silence

        // A Portal can transmit only if its mic delivers ~real-time audio. On the
        // Portal+ the far-field mic-array is held by Meta's own system service (and
        // on any Portal, a wake-word app like Alexa's), throttling capture to ~20%
        // — unusable for live voice. We measure this at startup rather than guess
        // by model/package. Anything under this fraction of real-time = receive-only.
        private const val CAPTURE_OK_FRACTION = 0.6
        // Below this the probe read essentially nothing — treat as inconclusive
        // (foreground race), not throttled. A throttled Portal+ reads a few-thousand.
        private const val THROTTLE_FLOOR = 1_000L
        // Receiver jitter buffer + queue cap, kept small so playback stays close to
        // real-time (releasing the talk button shouldn't lop a long tail off the far
        // end). ~0.2 s of slack absorbs LAN jitter without noticeable lag.
        private const val PLAY_BUFFER_BYTES = 6_400          // 0.2 s @ 16 kHz mono PCM16
        private const val MAX_QUEUED_FRAMES = 16             // drop oldest beyond this
    }

    data class Peer(val id: String, val name: String, val ip: String, val ts: Long)

    private val peers = ConcurrentHashMap<String, Peer>()

    // Peers other than ourselves, sorted for a stable UI ordering.
    fun onlinePeers(): List<Peer> =
        peers.values.filter { it.id != deviceId }.sortedBy { it.name.lowercase() }

    // ── Network-wide speaking lock ────────────────────────────────────────────
    @Volatile private var lockSpeaker: String? = null
    @Volatile private var lockName: String = ""
    @Volatile private var lockTs: Long = 0L

    // Name of someone ELSE currently speaking (null if free or it's us / stale).
    fun busySpeakerName(): String? {
        val s = lockSpeaker ?: return null
        if (s == deviceId) return null
        return if (System.currentTimeMillis() - lockTs < LOCK_TTL_MS) lockName else null
    }

    fun isTalking() = txRunning.get()

    // Whether this Portal can SEND. Determined once at startup by measuring the
    // real capture rate (see probeTransmitCapability). Optimistic until probed so
    // the very first interaction isn't wrongly blocked. Receiving always works.
    @Volatile private var transmitCapable = true
    @Volatile private var capabilityProbed = false
    fun canTransmit() = transmitCapable

    // One-shot mic-capability check. On the Portal+ the far-field mic-array is held
    // by Meta's own system service (and on any Portal, a wake-word app), throttling
    // a third-party AudioRecord to ~20% of real-time — unusable for live voice.
    // We measure it rather than guess by model/package, so any throttled device is
    // correctly marked receive-only and any clean one is full two-way. Must run
    // BEFORE SoundMonitor grabs the mic (call it before soundMonitor.start()).
    fun probeTransmitCapability() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            transmitCapable = false; capabilityProbed = true; return
        }
        Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, FRAME_SAMPLES * 2 * 4))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec?.release() }
                transmitCapable = false; capabilityProbed = true
                Log.w(TAG, "intercom: capability probe could not open mic → receive-only")
                return@Thread
            }
            val buf = ShortArray(FRAME_SAMPLES)
            runCatching { rec.startRecording() }
            // Let the mic warm up — the first reads can return 0 for a moment
            // (especially right after a restart) without meaning it's throttled.
            Thread.sleep(250)
            val t0 = System.currentTimeMillis()
            var total = 0L
            // Measure throughput across the whole window; a 0 read means "no data
            // yet", NOT "throttled" — keep going rather than bailing.
            while (System.currentTimeMillis() - t0 < 800) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) total += n else Thread.sleep(5)
            }
            val dur = System.currentTimeMillis() - t0
            runCatching { rec.stop(); rec.release() }
            val sps = if (dur > 0) total * 1000 / dur else 0
            // Three outcomes:
            //  - near real-time  → capable (e.g. the 10" Portal ~16–19k)
            //  - clear PARTIAL   → genuinely throttled, receive-only (Portal+ ~3–5k)
            //  - ~nothing (<1k)  → inconclusive: the mic was likely just silenced
            //    because we weren't foreground yet (Android 10). Don't condemn the
            //    device — assume capable; it works once the dashboard is foreground.
            val throttled = sps in THROTTLE_FLOOR until (SAMPLE_RATE * CAPTURE_OK_FRACTION).toLong()
            transmitCapable = !throttled
            capabilityProbed = true
            Log.i(TAG, "intercom: mic capability ~$sps sps → transmit " +
                if (transmitCapable) "ENABLED" else "DISABLED (receive-only)")
        }, "portal-ha-intercom-probe").also { it.isDaemon = true }.start()
    }

    private var soundMonitor: SoundMonitor? = null
    fun attachSoundMonitor(sm: SoundMonitor?) { soundMonitor = sm }

    // Coexist mode: SoundMonitor isn't holding the mic (it's released so an external
    // voice assistant can hear its wake word), so an announce opens its OWN short-
    // lived recorder for the push-to-talk and frees it again the moment you let go.
    @Volatile private var onDemandCapture = false
    fun setOnDemandCapture(b: Boolean) { onDemandCapture = b }
    private var txCaptureThread: Thread? = null

    // Topics the service should subscribe to, with the QoS to use for each.
    fun subscriptions(): List<Pair<String, Int>> = listOf(
        "${PRESENCE_PREFIX}+" to 1,
        LOCK_TOPIC to 1,
        "${AUDIO_PREFIX}+/+" to 0
    )

    private fun ownPresenceTopic() = "$PRESENCE_PREFIX$deviceId"

    // Announce ourselves (call once connected). Retained so late joiners see us.
    fun publishPresence() {
        val payload = "${deviceName()}|${localIp() ?: ""}|${System.currentTimeMillis()}"
        publish(ownPresenceTopic(), payload.toByteArray(), 1, true)
        Log.i(TAG, "intercom: presence published as '${deviceName()}' (${ownPresenceTopic()})")
    }

    // Clear our retained presence on a graceful disconnect.
    fun clearPresence() = publish(ownPresenceTopic(), ByteArray(0), 1, true)

    // ── Inbound routing (raw bytes, from the Paho callback thread) ────────────
    // Returns true if the topic belonged to the intercom (and was consumed).
    // Must stay non-blocking: presence/lock are tiny string parses, audio is a
    // queue offer handed to the playback thread.
    fun handleRawMessage(topic: String, payload: ByteArray): Boolean {
        when {
            topic.startsWith(PRESENCE_PREFIX) -> handlePresence(topic, payload)
            topic == LOCK_TOPIC -> handleLock(payload)
            topic.startsWith(AUDIO_PREFIX) -> handleAudio(topic, payload)
            else -> return false
        }
        return true
    }

    private fun handlePresence(topic: String, payload: ByteArray) {
        val id = topic.removePrefix(PRESENCE_PREFIX)
        if (id.isEmpty() || id == deviceId) return   // ignore our own retained presence
        if (payload.isEmpty()) { peers.remove(id); return }
        val parts = String(payload).split("|")
        val known = peers.containsKey(id)
        peers[id] = Peer(
            id = id,
            name = parts.getOrNull(0)?.ifEmpty { id } ?: id,
            ip = parts.getOrNull(1) ?: "",
            ts = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis()
        )
        if (!known) Log.i(TAG, "intercom: peer online '${peers[id]?.name}' ($id)")
    }

    private fun handleLock(payload: ByteArray) {
        if (payload.isEmpty()) { lockSpeaker = null; lockName = ""; lockTs = 0L; return }
        val parts = String(payload).split("|")
        lockSpeaker = parts.getOrNull(0)
        lockName = parts.getOrNull(1) ?: ""
        lockTs = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis()
    }

    private fun handleAudio(topic: String, payload: ByteArray) {
        // topic tail = "<sender>/<target>"
        val rest = topic.removePrefix(AUDIO_PREFIX)
        val slash = rest.indexOf('/')
        if (slash < 0) return
        val sender = rest.substring(0, slash)
        val target = rest.substring(slash + 1)
        if (sender == deviceId) return                       // ignore our own frames
        if (target != "all" && target != deviceId) return    // not addressed to us
        if (payload.isEmpty()) return
        ensurePlaybackThread()
        // Keep latency low: if frames back up (a burst, or playback briefly behind),
        // drop the oldest so we don't drift further and further behind the speaker.
        while (playQueue.size >= MAX_QUEUED_FRAMES) playQueue.poll()
        playQueue.offer(payload)
    }

    // ── Playback (receive) ────────────────────────────────────────────────────
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val playRunning = AtomicBoolean(false)
    private var playThread: Thread? = null

    @Synchronized
    private fun ensurePlaybackThread() {
        if (playRunning.get()) return
        playRunning.set(true)
        playThread = Thread({ playbackLoop() }, "portal-ha-intercom-rx")
            .also { it.isDaemon = true; it.start() }
    }

    private fun playbackLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, PLAY_BUFFER_BYTES))
                .build()
        }.getOrNull()
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            playRunning.set(false)
            Log.w(TAG, "intercom: playback AudioTrack unavailable")
            return
        }

        var boosted = false
        var savedVol = -1
        runCatching { track.play() }
        Log.i(TAG, "intercom: playback started")
        try {
            while (playRunning.get()) {
                val frame = try {
                    playQueue.poll(PLAY_IDLE_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) { break }

                if (frame == null) {
                    // Gone quiet — drop the forced volume and idle the track.
                    if (boosted) { restoreVolume(savedVol); boosted = false; savedVol = -1 }
                    runCatching { track.pause(); track.flush() }
                    continue
                }
                if (!boosted) {
                    savedVol = boostVolume(); boosted = true; runCatching { track.play() }
                    Log.i(TAG, "intercom: receiving — playing announcement")
                }
                var off = 0
                while (off < frame.size && playRunning.get()) {
                    val w = track.write(frame, off, frame.size - off)
                    if (w <= 0) break
                    off += w
                }
            }
        } finally {
            runCatching { track.stop(); track.release() }
            if (boosted) restoreVolume(savedVol)
            Log.i(TAG, "intercom: playback stopped")
        }
    }

    // Set STREAM_MUSIC to the user's chosen intercom level for the announcement;
    // return the prior volume so we can restore it afterwards.
    private fun boostVolume(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (max * Prefs(context).intercomVolume / 100).coerceIn(1, max)
        if (target != cur) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        return cur
    }

    private fun restoreVolume(saved: Int) {
        if (saved < 0) return
        val am = context.getSystemService(AudioManager::class.java)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
    }

    // ── Transmit (announce) ───────────────────────────────────────────────────
    // Normal mode: we DON'T open our own recorder — SoundMonitor already holds the
    // mic warm, so while announcing we just attach a frame sink to it and its PCM
    // chunks fan out to the broker (no handoff, no warmup, near-zero start latency).
    // Coexist mode: the mic is released for an external voice assistant, so we open
    // a short-lived recorder for the announce (startOnDemandCapture) and close it on
    // release — the assistant's own arbiter yields for those seconds and reclaims.
    private val txRunning = AtomicBoolean(false)
    @Volatile private var txTopic = ""
    @Volatile private var txFrames = 0
    @Volatile private var txBeeped = false
    @Volatile private var lastLockRefresh = 0L

    // Start announcing. target = null/"all" → broadcast; else a peer device id.
    // Returns false if blocked (someone else speaking, no mic permission, busy).
    fun startTalk(target: String?): Boolean {
        if (txRunning.get()) return true
        if (!canTransmit()) { Log.i(TAG, "intercom: transmit disabled — receive-only Portal"); return false }
        busySpeakerName()?.let { Log.i(TAG, "intercom: busy — $it is speaking"); return false }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "intercom: RECORD_AUDIO not granted"); return false
        }
        if (!onDemandCapture && soundMonitor == null) {
            Log.w(TAG, "intercom: mic not ready yet"); return false
        }

        txRunning.set(true)
        acquireLock()
        txTopic = "$AUDIO_PREFIX$deviceId/${if (target.isNullOrEmpty()) "all" else target}"
        txFrames = 0
        txBeeped = false
        lastLockRefresh = System.currentTimeMillis()
        if (onDemandCapture) startOnDemandCapture()                        // open our own mic now
        else soundMonitor?.frameSink = { buf, n -> onMicFrame(buf, n) }    // ride the warm mic
        Log.i(TAG, "intercom: announcing → $txTopic")
        return true
    }

    fun stopTalk() {
        if (!txRunning.compareAndSet(true, false)) return
        soundMonitor?.frameSink = null
        txCaptureThread = null   // the on-demand loop sees txRunning=false and exits, freeing its recorder
        releaseLock()
        Log.i(TAG, "intercom: stopped talking — sent $txFrames frames")
    }

    // Coexist-mode capture: a recorder that lives only for one announce. Mirrors
    // SoundMonitor's config (VOICE_RECOGNITION 16k mono PCM16) and feeds the same
    // onMicFrame packer; runs while txRunning, then releases the mic so the external
    // voice assistant can reclaim it.
    private fun startOnDemandCapture() {
        txCaptureThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, FRAME_SAMPLES * 2 * 4))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec?.release() }
                Log.w(TAG, "intercom: on-demand mic unavailable")
                return@Thread
            }
            runCatching { rec.startRecording() }
            val buf = ShortArray(FRAME_SAMPLES)
            try {
                while (txRunning.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onMicFrame(buf, n)
                }
            } finally {
                runCatching { rec.stop(); rec.release() }
            }
        }, "portal-ha-intercom-tx").also { it.isDaemon = true; it.start() }
    }

    // Called on SoundMonitor's capture thread for each warm-mic chunk while we're
    // announcing. Packs it to little-endian PCM16 and publishes it.
    private fun onMicFrame(buf: ShortArray, n: Int) {
        if (!txRunning.get() || n <= 0) return
        if (!txBeeped) { txBeeped = true; playReadyBeep() }   // mic is live → "talk now"
        val out = ByteArray(n * 2)
        var bi = 0
        for (i in 0 until n) {
            val s = buf[i].toInt()
            out[bi++] = (s and 0xff).toByte()
            out[bi++] = ((s shr 8) and 0xff).toByte()
        }
        publish(txTopic, out, 0, false)
        txFrames++
        val now = System.currentTimeMillis()
        if (now - lastLockRefresh >= LOCK_REFRESH_MS) { acquireLock(); lastLockRefresh = now }
    }

    // Soft, short beep on the sender so the user knows capture is live ("talk now").
    private fun playReadyBeep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 40)   // 40/100 = soft
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Thread({ Thread.sleep(300); runCatching { tg.release() } }, "portal-ha-beep")
                .also { it.isDaemon = true }.start()
        }
    }

    private fun acquireLock() {
        lockSpeaker = deviceId
        lockName = deviceName()
        lockTs = System.currentTimeMillis()
        publish(LOCK_TOPIC, "$deviceId|$lockName|$lockTs".toByteArray(), 0, true)
    }

    private fun releaseLock() {
        if (lockSpeaker == deviceId) { lockSpeaker = null; lockName = ""; lockTs = 0L }
        publish(LOCK_TOPIC, ByteArray(0), 1, true)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    fun release() {
        stopTalk()
        playRunning.set(false)
        playThread?.interrupt()
        playThread = null
    }
}
