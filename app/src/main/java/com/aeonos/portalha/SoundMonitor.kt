package com.aeonos.portalha

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

// Publishes a 0–100 ambient sound level. CRUCIAL: it must yield the mic during
// Portal voice/video calls — holding the AudioRecord open continuously starves
// the call (callers can't hear you). So the capture loop releases the mic
// whenever AudioManager.mode signals a call/ring, and re-acquires it after.
class SoundMonitor(
    private val context: Context,
    private val onLevel: (level: Int) -> Unit
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val SAMPLE_RATE = 16000
        private const val PUBLISH_MS = 2_000L
    }

    private val running = AtomicBoolean(false)

    // When set (the intercom is announcing), every captured PCM chunk is handed
    // here too, so the announce streams this SAME continuously-warm mic — no
    // release/reacquire handoff, no cold-start warmup, no ~1s latency. The callback
    // runs on the capture thread and must return promptly (pack + publish a frame).
    @Volatile var frameSink: ((buf: ShortArray, length: Int) -> Unit)? = null

    fun isRunning() = running.get()

    fun start() {
        // Idempotent + safe to re-call after stop() (coexist mode toggles us on/off
        // live). The CAS guards against two callers double-starting the thread.
        if (!running.compareAndSet(false, true)) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — sound level disabled. " +
                "Run: adb shell pm grant ${context.packageName} android.permission.RECORD_AUDIO")
            running.set(false)
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { Log.w(TAG, "AudioRecord not supported"); running.set(false); return }
        val bufSize = minBuf * 4

        Thread({
            val am = context.getSystemService(AudioManager::class.java)
            // Read in SMALL chunks (~40 ms) so the loop revisits the paused flag
            // frequently and can hand the mic to the intercom within ~40 ms. A big
            // read blocks the loop for its whole duration, which on a contended
            // Portal mic delayed the handoff by up to 2 s — long enough to swallow
            // an entire announce. RMS accumulates across reads, so chunk size only
            // affects responsiveness, not the published level.
            val buf = ShortArray(SAMPLE_RATE / 25)   // 640 samples = 40 ms
            var rec: AudioRecord? = null
            var sumSq = 0.0
            var count = 0
            var lastPublish = System.currentTimeMillis()

            fun release() {
                rec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                rec = null; sumSq = 0.0; count = 0
            }

            while (running.get()) {
                // A Portal call (VoIP = IN_COMMUNICATION, telephony = IN_CALL, or
                // an incoming RINGTONE) needs the mic — let go of it immediately.
                val mode = am?.mode ?: AudioManager.MODE_NORMAL
                val inCall = mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_RINGTONE
                if (inCall) {
                    if (rec != null) { Log.i(TAG, "sound: releasing mic for call (mode=$mode)"); release() }
                    Thread.sleep(500)
                    continue
                }

                if (rec == null) {
                    val r = runCatching {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
                        )
                    }.getOrNull()
                    if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { r?.release() }
                        Thread.sleep(1000)
                        continue
                    }
                    runCatching { r.startRecording() }
                    rec = r
                    lastPublish = System.currentTimeMillis()
                    Log.i(TAG, "sound: mic acquired")
                }

                val n = rec?.read(buf, 0, buf.size) ?: -1
                if (n > 0) {
                    frameSink?.invoke(buf, n)   // forward to the intercom if announcing
                    for (i in 0 until n) sumSq += buf[i].toLong() * buf[i]
                    count += n
                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= PUBLISH_MS && count > 0) {
                        lastPublish = now
                        val rms = sqrt(sumSq / count)
                        // Map RMS 0–32768 to 0–100 via dBFS: floor at -60 dBFS
                        val dbfs = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -90.0
                        val level = ((dbfs + 60.0) / 60.0 * 100.0).coerceIn(0.0, 100.0).toInt()
                        onLevel(level)
                        sumSq = 0.0
                        count = 0
                    }
                } else {
                    // Read failed (mic grabbed elsewhere, e.g. a call) — drop it and
                    // back off a good while so we don't fight whoever has it.
                    Log.i(TAG, "sound: read failed, mic busy — backing off")
                    release()
                    Thread.sleep(2500)
                }
            }
            release()
        }, "portal-ha-sound").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
    }
}
