package com.aeonos.portalha

import android.util.Log

/**
 * Tracks whether the revived Amazon Alexa (falcon) client is CONNECTED (its Speech
 * Interaction Manager is in "ReadyState") by tailing falcon's own state machine in logcat.
 *
 * Why: after a boot/reboot falcon restarts and takes a while (sometimes minutes) to reconnect
 * to Alexa on Android 10. Firing an Alexa handoff before then reaches a disconnected falcon →
 * "sorry, something went wrong." The handoff checks [isReady] and, when false, gives the user
 * feedback and skips the doomed LISTEN — then works automatically once falcon reconnects.
 *
 * Semantics: OPTIMISTIC — assume ready unless we've POSITIVELY seen falcon go to a
 * disconnected state (and not yet come back). This matters because falcon only logs
 * "ReadyState" on activity, so an idle-but-ready falcon logs nothing; defaulting to "not
 * ready" would wrongly block every request (and deadlock, since we'd block the very event
 * that would log ReadyState). The post-reboot case we care about DOES log DisconnectState
 * while reconnecting, so "block only on a seen disconnect" catches it correctly.
 * Requires READ_LOGS (already granted for presence). Only run while Alexa support is enabled.
 */
class FalconReadiness {
    companion object { private const val TAG = "PortalHA" }

    @Volatile private var running = false
    @Volatile private var connected = true   // optimistic until we SEE a disconnect
    fun isReady() = connected

    // When falcon last FAILED a turn, from its own state machine ("Got ErrorEvent in
    // ListenState" — the upload overrun that produces "sorry, something went wrong").
    // Timing alone can't tell a failed turn from a real one: measured aborts land
    // anywhere from 250 ms to 2.7 s after LISTEN, and a genuine answer starts speaking
    // in that same range. This is falcon telling us directly, so the retry can key off
    // the actual failure instead of a guess.
    @Volatile var lastErrorMs = 0L
        private set

    private var process: Process? = null
    private var reader: Thread? = null

    fun start() {
        if (running) return
        running = true
        connected = true
        reader = Thread(::readLoop, "portal-ha-falcon-ready").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "falcon readiness watch started")
    }

    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
    }

    private fun readLoop() {
        try {
            // -T 200: seed from recent history (so an already-connected falcon reads ready
            // even when idle) then follow new state transitions live.
            val p = ProcessBuilder("logcat", "-T", "200", "-s", "SPCH-SIM_SimStateMachine:I")
                .redirectErrorStream(true).start()
            process = p
            p.inputStream.bufferedReader().use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    when {
                        line.contains("ReadyState") ->
                            if (!connected) { connected = true; Log.i(TAG, "falcon: connected (ReadyState)") }
                        line.contains("DisconnectState") || line.contains("IgnoreWhileDisconnected") ->
                            if (connected) { connected = false; Log.i(TAG, "falcon: disconnected — gating handoff") }
                        // Checked last so a state word in the same line can't mask it.
                        line.contains("ErrorEvent") -> {
                            lastErrorMs = System.currentTimeMillis()
                            Log.i(TAG, "falcon: ErrorEvent — turn failed")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "falcon readiness reader stopped: ${e.message} (READ_LOGS granted?)")
        }
    }
}
