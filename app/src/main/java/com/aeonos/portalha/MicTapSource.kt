package com.aeonos.portalha

import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.library.util.sources.audio.AudioSource

// Push-model audio source for the RTSP stream that TAPS the mic instead of
// opening it. SoundMonitor already owns the Portal's one usable capture slot
// (sound level, wake word and intercom announce all share its AudioRecord),
// and a second concurrent capture is refused anyway — so the stream gets a
// copy of the same 16 kHz mono PCM via feed() from SoundMonitor's capture
// thread (streamSink). Whenever the tap goes quiet — mic yielded to Alexa or
// a call, wake word disabled, busy-mic backoff — a watchdog thread pushes
// silence at the same 40 ms cadence so the AAC track never starves: HA keeps
// a continuous timeline and the audio simply goes mute for the gap.
class MicTapSource : AudioSource() {

    companion object {
        // MUST match SoundMonitor: 16 kHz mono 16-bit in 640-sample (40 ms) chunks,
        // and prepareAudio() in RtspStreamer must be called with the same rate.
        private const val CHUNK_SAMPLES = 640
        private const val CHUNK_MS = 40L
        private const val SILENCE_AFTER_MS = 250L   // tap quiet this long → synthesize
    }

    @Volatile private var callback: GetMicrophoneData? = null
    @Volatile private var running = false
    @Volatile private var lastFeedMs = 0L
    private var maxInputSize = 0
    private val silence = ByteArray(CHUNK_SAMPLES * 2)

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean = true

    override fun start(getMicrophoneData: GetMicrophoneData) {
        callback = getMicrophoneData
        lastFeedMs = System.currentTimeMillis()
        running = true
        Thread({
            while (running) {
                val idle = System.currentTimeMillis() - lastFeedMs
                if (idle >= SILENCE_AFTER_MS) {
                    callback?.inputPCMData(Frame(silence, 0, silence.size, System.nanoTime() / 1000))
                    runCatching { Thread.sleep(CHUNK_MS) }
                } else {
                    runCatching { Thread.sleep(SILENCE_AFTER_MS - idle) }
                }
            }
        }, "portal-ha-mictap").also { it.isDaemon = true }.start()
    }

    override fun stop() {
        running = false
        callback = null
    }

    override fun isRunning(): Boolean = running

    override fun release() = stop()

    override fun getMaxInputSize(): Int = maxInputSize
    override fun setMaxInputSize(size: Int) { maxInputSize = size }

    // Called from SoundMonitor's capture thread. Convert to little-endian PCM16
    // bytes and hand to the encoder; must return promptly (it shares the loop
    // with the wake detector and intercom sinks).
    fun feed(buf: ShortArray, length: Int) {
        if (!running) return
        val cb = callback ?: return
        lastFeedMs = System.currentTimeMillis()
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = buf[i].toInt()
            bytes[2 * i] = (s and 0xFF).toByte()
            bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        cb.inputPCMData(Frame(bytes, 0, bytes.size, System.nanoTime() / 1000))
    }
}
