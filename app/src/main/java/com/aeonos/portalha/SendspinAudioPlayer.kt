package com.aeonos.portalha

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.util.Log
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.StreamFormat
import kotlin.concurrent.thread

/**
 * Sendspin audio sink for the Portal.
 *
 * The protocol library does the hard parts — Kalman clock sync and a timestamp-ordered buffer —
 * so playback is just: ask the buffer when the next chunk is due, sleep until then, and write it
 * to an [AudioTrack]. We advertise PCM only, so chunks are raw frames and need no decoding
 * (anything else would need a decoder; that case is logged loudly rather than played as noise).
 */
class SendspinAudioPlayer(
    private val buffer: AudioBuffer,
    private val clock: ClockSync,
) : AudioPlayer {

    private companion object { const val TAG = "PortalHA" }

    private var track: AudioTrack? = null
    private var format: StreamFormat? = null
    private var feeder: Thread? = null

    @Volatile private var running = false
    @Volatile private var dropped = 0L
    @Volatile private var gain = 1f

    override val isPlaying: Boolean get() = running
    override val droppedDecodeFrames: Long get() = dropped

    override fun configure(format: StreamFormat) {
        releaseTrack()
        this.format = format
        if (!format.codec.equals("pcm", ignoreCase = true)) {
            Log.w(TAG, "sendspin: server chose codec '${format.codec}' but only PCM is supported")
            return
        }
        val channelMask = if (format.channels >= 2)
            AndroidAudioFormat.CHANNEL_OUT_STEREO else AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = when (format.bitDepth) {
            16 -> AndroidAudioFormat.ENCODING_PCM_16BIT
            8 -> AndroidAudioFormat.ENCODING_PCM_8BIT
            32 -> AndroidAudioFormat.ENCODING_PCM_FLOAT
            else -> {
                Log.w(TAG, "sendspin: unsupported bit depth ${format.bitDepth}, assuming 16")
                AndroidAudioFormat.ENCODING_PCM_16BIT
            }
        }
        val minBuf = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
            .coerceAtLeast(8192)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build())
            // A couple of buffers' headroom: enough to ride out scheduling jitter without
            // adding latency the group sync would have to compensate for.
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(gain) }
        Log.i(TAG, "sendspin: audio configured ${format.sampleRate}Hz " +
            "${format.channels}ch ${format.bitDepth}bit buf=${minBuf * 2}B")
    }

    override fun start() {
        val t = track ?: run { Log.w(TAG, "sendspin: start with no track"); return }
        if (running) return
        running = true
        runCatching { t.play() }.onFailure { Log.w(TAG, "sendspin: play failed: ${it.message}") }
        feeder = thread(isDaemon = true, name = "sendspin-audio") { feedLoop() }
    }

    override fun flush() {
        buffer.flush()
        runCatching { track?.pause(); track?.flush(); if (running) track?.play() }
    }

    override fun stop() {
        running = false
        feeder?.let { runCatching { it.join(500) } }
        feeder = null
        releaseTrack()
    }

    override fun transition(format: StreamFormat) {
        val wasRunning = running
        stop()
        configure(format)
        if (wasRunning) start()
    }

    override fun setVolume(gain: Float) {
        this.gain = gain.coerceIn(0f, 1f)
        runCatching { track?.setVolume(this.gain) }
    }

    private fun feedLoop() {
        while (running) {
            val t = track ?: break
            val waitMicros = buffer.nextChunkDelayMicros()
            if (waitMicros == null) {                 // nothing queued yet
                Thread.sleep(5)
                continue
            }
            if (waitMicros > 1_000) {                 // not due yet — nap, but stay responsive
                Thread.sleep((waitMicros / 1000).coerceAtMost(20L))
                continue
            }
            val chunk = buffer.poll() ?: continue
            // Blocking write: AudioTrack paces us to real time from here on.
            val n = runCatching { t.write(chunk.data, 0, chunk.data.size) }.getOrDefault(0)
            if (n < 0) {
                Log.w(TAG, "sendspin: AudioTrack.write error $n")
                dropped++
            }
        }
    }

    private fun releaseTrack() {
        runCatching { track?.pause(); track?.flush(); track?.stop() }
        runCatching { track?.release() }
        track = null
    }
}
