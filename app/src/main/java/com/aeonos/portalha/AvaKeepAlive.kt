package com.aeonos.portalha

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * Keeps an external voice assistant (fleet: Ava, com.example.ava) able to HEAR on Android 10
 * Portals (Portal 10" "omni", SDK 29) without ever covering the dashboard.
 *
 * ## Why
 * Meta's AudioPolicyService carries a "ProcessPolicy" that silences a recorder unless its
 * process has a VISIBLE activity. Measured (logcat -s AudioPolicyService):
 *     ProcessPolicy: onForegroundActivitiesChanged() <uid>/<pid> -> 0
 *     ProcessPolicy: onUidForeground() silencing for <uid> -> 0
 *     Setting App state for uid = <uid>, state = 0        <- the recorder now gets zeros
 * A voice assistant that runs from a foreground service (Ava) therefore records silence while
 * our dashboard is in front, i.e. always. Android 9 (Portal+) has no such policy.
 *
 * ## How
 * With freeform windowing enabled (Settings.Global enable_freeform_support=1 and
 * force_resizable_activities=1, read by the system at BOOT only) we "park" the assistant: a
 * transparent activity of ours ([AvaKeepAliveActivity]) is started in its own task, in a freeform
 * window whose launch bounds lie off the bottom-right corner, and it starts the assistant's launcher
 * activity INSIDE that task (no NEW_TASK), so the assistant's activity lives in a freeform task we
 * own. WindowManager keeps 48x32 dp of any freeform window on screen; Android draws only that
 * sliver, the dashboard stays visible and resumed (multi-resume) so the camera keeps streaming,
 * and the assistant's process owns a visible activity so it is not silenced.
 * Owning the task matters: an existing task keeps its windowing mode whatever the launch options
 * say, so starting the assistant's OWN task could bring it up full screen (someone opened it from
 * the launcher, a setup script drove its UI) - covering the dashboard and killing the stream. Our
 * task is always created by us, in freeform, and if freeform turns out not to be active on this
 * boot our activity sees it (isInMultiWindowMode false), removes its task and never starts the
 * assistant - our own translucent activity on top is camera-safe.
 * The sliver is hidden under a tiny overlay of ours showing the dashboard pixels behind it
 * (PixelCopy of the dashboard window), plus a transparent touch guard over the sliver itself so a
 * finger in the corner can't drag the parked window on screen by its caption.
 *
 * Every activity start on the display (ours: a wake, a navigate, Show Dashboard, the steal return;
 * or anyone else's) moves the fullscreen stack above the parked window, and the policy silences the
 * assistant again seconds-to-minutes later. So we re-park (bring our task back to the front):
 *  - shortly after each of our own dashboard starts/resumes ([request] from BridgeService),
 *  - on screen on/off,
 *  - when our logcat reader sees the assistant's uid lose its visible activity or get silenced,
 *  - on a periodic safety tick, and once at start (boot).
 * Never while a call, a cast, one of our own settings screens or another app has the screen, never
 * in a tight loop (minimum gap + rate limit with exponential backoff), and every park is logged with
 * its reason ("keepalive: park #n (<reason>) ...").
 *
 * The corner cover is re-copied every 3 s, plus a burst at +0.4/+1.2/+2.5 s after each park, page
 * change, wake and cover show, so a page that was still drawing when it was first copied doesn't
 * stay frozen in the corner. Copies run on a thread of their own (PixelCopy blocks its caller).
 *
 * Two ways to turn it off: the user's switch ([setEnabled], HA "Ava Keep-Alive"), which stays off,
 * and a timed pause for setup scripts ([pauseUntil], at most [MAX_PAUSE_MINUTES]) that ends BY
 * ITSELF, so a script interrupted while it drives the assistant's own UI can't leave it deaf.
 *
 * A no-op below SDK 29, when the assistant isn't installed, when the switch is off or paused.
 */
class AvaKeepAlive(private val ctx: Context, private val host: Host) {

    interface Host {
        /** Why a park must wait right now (call, cast, one of our screens, another app), or null. */
        fun parkBlocker(): String?
        val screenIsOn: Boolean
        /** The dashboard activity is resumed (in front, possibly next to the parked window). */
        val dashboardInFront: Boolean
        /** One of our full-screen overlays (photos, sleep cover, now playing) is up. */
        val fullScreenOverlayUp: Boolean
        /** elapsedRealtime of the last touch on one of our windows (0 = never). */
        val lastTouchElapsed: Long
    }

    companion object {
        private const val TAG = "PortalHA"
        const val DEFAULT_PACKAGE = "com.example.ava"
        private const val WINDOWING_MODE_FREEFORM = 5
        // ActivityOptions' (hidden) bundle key for the launch windowing mode. Passing it in the
        // options bundle is exactly what `am start --windowingMode 5` does, with no reflection.
        private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
        // WindowManager keeps this much of a freeform window on screen (WindowState.MINIMUM_VISIBLE_*).
        private const val MIN_VISIBLE_W_DP = 48
        private const val MIN_VISIBLE_H_DP = 32
        // Default minimal size of a resizeable task.
        private const val MIN_TASK_DP = 220
        // The freeform window's drop shadow spills this far left of / above its visible sliver.
        private const val SHADOW_LEFT_DP = 36
        private const val SHADOW_TOP_DP = 28

        private const val START_DELAY_MS = 6_000L
        private const val TICK_MS = 60_000L
        private const val COVER_TICK_MS = 1_000L
        // Steady refresh of the corner cover. Not 1 s: every copy is real render-thread work
        // (~7 ms on the Portal 10", shared with every window of the app), for a corner that
        // rarely changes between page changes - which get the burst below.
        private const val COVER_REFRESH_MS = 3_000L
        // Extra copies after a park, a page change or the cover coming up: the page drawing
        // underneath settles within a couple of seconds.
        private val COVER_BURST_MS = longArrayOf(400L, 1_200L, 2_500L)
        /** Longest timed pause a setup script can ask for (minutes); longer requests are clamped. */
        const val MAX_PAUSE_MINUTES = 30
        private const val MAX_PAUSE_MS = MAX_PAUSE_MINUTES * 60_000L
        private const val MIN_GAP_MS = 5_000L
        private const val RATE_WINDOW_MS = 10 * 60_000L
        private const val RATE_MAX = 12
        private const val BACKOFF_MIN_MS = 60_000L
        private const val BACKOFF_MAX_MS = 15 * 60_000L
        // A park moves input focus to the parked window, which would close the keyboard under
        // someone typing on the dashboard. Wait for the screen to be left alone first.
        private const val TOUCH_IDLE_MS = 8_000L
        private const val TOUCH_IDLE_SILENCED_MS = 3_000L
        // Our activity re-starting the assistant inside our task (it finished or was removed).
        private const val INNER_MIN_GAP_MS = 5_000L
        private const val INNER_MAX_PER_10MIN = 8

        @Volatile private var instance: AvaKeepAlive? = null

        /** AvaKeepAliveActivity came to the top of its task (created, or the assistant above it went away). */
        fun onHostResumed(act: Activity) {
            val ka = instance
            if (ka == null) { act.finishAndRemoveTask(); return }
            ka.hostResumed(act)
        }

        /** The dashboard showed or finished loading a page: re-copy the corner cover as it settles. */
        fun onDashboardContentChanged() { instance?.refreshCoverSoon() }

        /**
         * A stored pause end (wall clock, ms) made safe to use: 0 when it has passed (or none), and
         * never more than [MAX_PAUSE_MINUTES] ahead (a clock that jumped back can't stretch it).
         */
        fun clampPauseUntil(untilMs: Long, nowMs: Long = System.currentTimeMillis()): Long = when {
            untilMs <= nowMs -> 0L
            untilMs > nowMs + MAX_PAUSE_MS -> nowMs + MAX_PAUSE_MS
            else -> untilMs
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm get() = ctx.getSystemService(WindowManager::class.java)

    /** In effect: the user's switch is on and no timed pause is running. */
    @Volatile var enabled = false
        private set
    // The user's switch (HA "Ava Keep-Alive"): only the user turns it back on.
    @Volatile private var userEnabled = false
    // Timed setup pause: wall-clock end (ms), 0 = none. Ends by itself (pauseEnd, tick, screen on).
    @Volatile private var pausedUntil = 0L
    @Volatile private var pkg = DEFAULT_PACKAGE
    @Volatile private var component: ComponentName? = null
    @Volatile private var avaUid = -1
    @Volatile private var started = false

    // Freeform: null = not seen yet, true = our task came up freeform, false = not active this boot.
    @Volatile private var freeformOk: Boolean? = null
    // A park has been issued by this process (the first one clears a task left from an earlier one).
    @Volatile private var parkedThisRun = false

    // What AudioPolicyService last said about the assistant's uid (-1 = nothing seen yet).
    @Volatile private var avaFg = -1
    @Volatile private var avaAppState = -1

    // Park bookkeeping (elapsedRealtime).
    @Volatile private var lastParkMs = 0L
    @Volatile private var parkCount = 0
    private val parkTimes = ArrayDeque<Long>()
    private val innerStarts = ArrayDeque<Long>()
    @Volatile private var innerCount = 0
    @Volatile private var backoffMs = 0L
    @Volatile private var backoffUntil = 0L
    @Volatile private var pendingReason: String? = null
    @Volatile private var pendingDueMs = 0L
    @Volatile private var pendingForced = false
    @Volatile private var lastSkipLog = ""
    @Volatile private var lastSkipLogMs = 0L

    // Logcat reader.
    @Volatile private var logProc: Process? = null
    @Volatile private var logThread: Thread? = null

    // Corner cover + touch guard (created once, early, so they sit BELOW every later overlay).
    private var coverView: ImageView? = null
    private var coverLp: WindowManager.LayoutParams? = null
    private var guardView: View? = null
    private var guardLp: WindowManager.LayoutParams? = null
    @Volatile private var coverShown = false
    @Volatile private var lastCoverCopyMs = 0L
    private var sliver = Rect()
    private var coverRect = Rect()
    private val burstToken = Any()
    // PixelCopy cost (on the copy thread), for the status line.
    @Volatile private var copyCount = 0
    @Volatile private var copyTotalUs = 0L
    @Volatile private var copyMaxUs = 0L

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Seconds left of a timed pause (0 = not paused). */
    private fun pauseLeftSec(): Long {
        val u = pausedUntil
        return if (u == 0L) 0L else ((u - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
    }

    /** One line for logs / setup scripts: what the keep-alive is doing right now. */
    fun status(): String {
        val now = SystemClock.elapsedRealtime()
        val ff = when (freeformOk) { true -> "ok"; false -> "inactive"; null -> "unknown" }
        val n = copyCount
        val avgMs = if (n == 0) 0.0 else copyTotalUs / n / 1000.0
        return "keepalive: status enabled=$enabled sdk=${Build.VERSION.SDK_INT} pkg=$pkg uid=$avaUid " +
            "installed=${component != null} freeform=$ff fg=$avaFg state=$avaAppState parks=$parkCount " +
            "inner=$innerCount lastPark=${if (lastParkMs == 0L) -1 else (now - lastParkMs) / 1000}s " +
            "backoff=${if (backoffUntil > now) (backoffUntil - now) / 1000 else 0}s cover=$coverShown " +
            "switch=${if (userEnabled) "on" else "off"} paused=${pauseLeftSec()}s " +
            "copies=$n copyAvg=${String.format(java.util.Locale.US, "%.1f", avgMs)}ms " +
            "copyMax=${copyMaxUs / 1000}ms"
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    /** [pausedUntilMs]: a timed pause still running from before a restart (wall clock, 0 = none). */
    fun start(on: Boolean, packageName: String, pausedUntilMs: Long = 0L) {
        instance = this
        pkg = packageName.ifBlank { DEFAULT_PACKAGE }
        userEnabled = on
        pausedUntil = clampPauseUntil(pausedUntilMs)
        enabled = on && pausedUntil == 0L
        if (!supported) { Log.i(TAG, "keepalive: not needed on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"); return }
        if (started) return
        started = true
        main.post { ensureCoverWindows() }
        main.postDelayed(tick, START_DELAY_MS)
        main.postDelayed(coverTick, START_DELAY_MS)
        schedulePauseEnd()
        Log.i(TAG, "keepalive: service up (enabled=$enabled, switch=${if (on) "on" else "off"}, " +
            "paused=${pauseLeftSec()}s, package=$pkg)")
    }

    fun stop() {
        started = false
        main.removeCallbacks(tick); main.removeCallbacks(coverTick); main.removeCallbacks(attemptRunnable)
        main.removeCallbacks(pauseEnd); main.removeCallbacksAndMessages(burstToken)
        stopLogReader()
        main.post {
            removeCoverWindows()
            copyThread?.quitSafely(); copyThread = null; copyHandler = null
        }
        if (instance === this) instance = null
    }

    /** The user's switch. Off stays off; on takes effect unless a timed pause is running. */
    fun setEnabled(on: Boolean) {
        userEnabled = on
        applyEnabled(if (on) "switch on" else "switch off")
    }

    /**
     * Timed pause for setup scripts: off now, back on BY ITSELF at [untilMs] (wall clock, clamped to
     * [MAX_PAUSE_MINUTES] ahead); 0 or a past time ends a pause now. The user's switch is untouched,
     * so a switched-off keep-alive stays off when the pause ends.
     */
    fun pauseUntil(untilMs: Long) {
        pausedUntil = clampPauseUntil(untilMs)
        main.removeCallbacks(pauseEnd)
        if (pausedUntil != 0L) {
            Log.i(TAG, "keepalive: paused for ${pauseLeftSec()}s (setup) - back on by itself when it runs out")
            schedulePauseEnd()
            applyEnabled("paused")
        } else {
            applyEnabled("pause ended")
        }
    }

    /** Recompute [enabled] from the switch and the pause, and act on a change. */
    @Synchronized private fun applyEnabled(why: String) {
        val on = userEnabled && pausedUntil == 0L
        if (on == enabled) return
        enabled = on
        Log.i(TAG, "keepalive: ${if (on) "enabled" else "disabled"} ($why)")
        if (!supported) return
        if (on) {
            main.post { ensureCoverWindows() }
            request(why, 1_000L, force = true)
        } else {
            main.removeCallbacks(attemptRunnable); pendingReason = null; pendingForced = false
            stopLogReader()
            main.post { setCoverVisible(false) }
            // Take the parked window away entirely: our task, and the assistant's activity in it.
            removeOurTask(why)
        }
    }

    private val pauseEnd = Runnable { checkPauseEnd() }

    private fun schedulePauseEnd() {
        if (pausedUntil == 0L || !started) return
        main.removeCallbacks(pauseEnd)
        val left = (pausedUntil - System.currentTimeMillis()).coerceIn(0L, MAX_PAUSE_MS)
        main.postDelayed(pauseEnd, left + 250L)
    }

    /** End a timed pause that has run out (called by its timer, and by the tick and screen on as backups). */
    private fun checkPauseEnd() {
        val u = pausedUntil
        if (u == 0L) return
        val c = clampPauseUntil(u)
        if (c != 0L) { pausedUntil = c; schedulePauseEnd(); return }
        pausedUntil = 0L
        Log.i(TAG, "keepalive: setup pause ran out")
        applyEnabled("setup pause ran out")
    }

    fun setPackage(p: String) {
        val v = p.ifBlank { DEFAULT_PACKAGE }
        if (v == pkg) return
        pkg = v; component = null; avaUid = -1; avaFg = -1; avaAppState = -1
        stopLogReader()
        removeOurTask("package changed")
        Log.i(TAG, "keepalive: package now $pkg")
        request("package changed", 1_000L, force = true)
    }

    // ── Triggers ─────────────────────────────────────────────────────────────────

    /**
     * Ask for a park [delayMs] from now. A burst of triggers becomes one attempt at the EARLIEST
     * requested time (a later, lazier trigger never delays an urgent one). Unless [force], the
     * attempt is dropped when the log shows the assistant visible and not silenced by then (the
     * trigger did not actually cover it). Our own dashboard starts are processed by the system
     * before startActivity returns, so a short delay is enough for them.
     */
    fun request(reason: String, delayMs: Long = 1_500L, force: Boolean = false) {
        if (!supported || !enabled || !started) return
        main.post {
            val due = SystemClock.elapsedRealtime() + delayMs
            val pr = pendingReason
            if (pr != null) {
                pendingReason = "${pr.substringBefore(" +")} +$reason"
                pendingForced = pendingForced || force
                if (due >= pendingDueMs) return@post
                main.removeCallbacks(attemptRunnable)
            } else {
                pendingReason = reason; pendingForced = force
            }
            pendingDueMs = due
            main.postDelayed(attemptRunnable, delayMs)
        }
    }

    /** Our own dashboard start (bringDashboardToFront / reclaimForeground) just covered the parked window. */
    fun onDashboardStarted(reason: String) = request(reason, 600L)

    fun onScreen(on: Boolean) {
        if (on) main.post { checkPauseEnd() }
        if (on) request("screen on", 1_000L) else request("screen off", 1_200L)
        main.post { updateCover() }
        if (on) refreshCoverSoon()
    }

    fun onDashboardResumed() {
        request("dashboard resumed", 500L)
        main.post { updateCover() }
        refreshCoverSoon()
    }

    fun onDashboardPaused() {
        main.post { updateCover() }
    }

    // ── Park ─────────────────────────────────────────────────────────────────────

    private val attemptRunnable = Runnable {
        val reason = pendingReason ?: "?"
        val forced = pendingForced
        pendingReason = null; pendingForced = false
        attempt(reason, forced)
    }

    private fun attempt(reason: String, forced: Boolean = false) {
        if (!supported || !enabled || !started) return
        if (!resolve()) { skip("assistant $pkg not installed"); return }
        ensureLogReader()
        if (freeformOk == false) { skip("freeform windowing is not active on this boot (reboot once after enabling it)"); return }
        if (!freeformGlobalsOn()) { enableFreeformGlobals(); return }
        host.parkBlocker()?.let { skip(it); return }
        // Still visible and hearing (per AudioPolicyService) after the trigger: nothing to do.
        if (!forced && lastParkMs != 0L && avaFg == 1 && avaAppState != 0 && logThread?.isAlive == true) return
        val now = SystemClock.elapsedRealtime()
        // Leave a finger alone: a park moves input focus (the keyboard would close mid-typing).
        val idleNeed = if (avaAppState == 0) TOUCH_IDLE_SILENCED_MS else TOUCH_IDLE_MS
        val touchAge = if (host.lastTouchElapsed == 0L) Long.MAX_VALUE else now - host.lastTouchElapsed
        if (host.screenIsOn && touchAge < idleNeed) { retryIn(reason, forced, idleNeed - touchAge + 200L); return }
        if (now < backoffUntil) { retryIn(reason, forced, backoffUntil - now); return }
        val gap = now - lastParkMs
        if (lastParkMs != 0L && gap < MIN_GAP_MS) { retryIn(reason, forced, MIN_GAP_MS - gap); return }
        while (parkTimes.isNotEmpty() && now - parkTimes.first() > RATE_WINDOW_MS) parkTimes.removeFirst()
        if (parkTimes.size >= RATE_MAX) {
            backoffMs = if (backoffMs == 0L) BACKOFF_MIN_MS else (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            backoffUntil = now + backoffMs
            Log.w(TAG, "keepalive: ${parkTimes.size} parks in ${RATE_WINDOW_MS / 60000} min - backing off ${backoffMs / 1000}s ($reason)")
            retryIn(reason, forced, backoffMs); return
        }
        if (backoffMs != 0L && parkTimes.size <= RATE_MAX / 3) backoffMs = 0L
        park(reason)
    }

    /** Try again in [ms]; a forced attempt stays forced (it must not be dropped as "still visible"). */
    private fun retryIn(reason: String, forced: Boolean, ms: Long) {
        if (pendingReason == null) pendingReason = reason
        pendingForced = pendingForced || forced
        val d = ms.coerceIn(200L, BACKOFF_MAX_MS)
        pendingDueMs = SystemClock.elapsedRealtime() + d
        main.removeCallbacks(attemptRunnable)
        main.postDelayed(attemptRunnable, d)
    }

    private fun skip(why: String) {
        val now = SystemClock.elapsedRealtime()
        // The same reason is logged again at most every 10 minutes (e.g. no assistant installed).
        if (why != lastSkipLog || now - lastSkipLogMs > 10 * 60_000L) {
            lastSkipLog = why; lastSkipLogMs = now
            Log.i(TAG, "keepalive: not parking - $why")
        }
    }

    /** Create our freeform task (first time) or bring it back to the front (every later time). */
    private fun park(reason: String) {
        val bounds = launchBounds()
        // Same intent every time: with NEW_TASK an existing task whose root has this exact intent is
        // brought to the front as it is (the assistant on top, resumed) - no new instance.
        // Except the FIRST park of this process: our task outlives a previous process of ours (an
        // update, a crash) with only the assistant left in it, and bringing that back would never
        // resume our activity - no freeform check, so no corner cover over a visible sliver. Clear
        // it and start over inside it (the task keeps its freeform bounds).
        val fresh = !parkedThisRun
        val intent = Intent(ctx, AvaKeepAliveActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                (if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0))
        if (freeformOk != false) coverBeforePark()
        val ok = runCatching { ctx.startActivity(intent, freeformOptions(bounds)) }
            .onFailure { Log.w(TAG, "keepalive: park failed: ${it.message}") }.isSuccess
        if (!ok) return
        parkedThisRun = true
        val now = SystemClock.elapsedRealtime()
        lastParkMs = now; parkTimes.addLast(now); parkCount++
        Log.i(TAG, "keepalive: park #$parkCount ($reason${if (fresh) ", fresh task" else ""}) fg=$avaFg state=$avaAppState " +
            "screen=${if (host.screenIsOn) "on" else "off"}")
        refreshCoverSoon()
    }

    /** Our activity is on top of our task: check freeform, then put the assistant above it. */
    private fun hostResumed(act: Activity) {
        val freeform = act.isInMultiWindowMode
        if (freeformOk != freeform) {
            val loc = IntArray(2); runCatching { act.window.decorView.getLocationOnScreen(loc) }
            if (freeform) Log.i(TAG, "keepalive: freeform windowing active (parked window at ${loc[0]},${loc[1]})")
            else Log.e(TAG, "keepalive: freeform windowing NOT active (our window came up full screen) - " +
                "reboot the Portal once so enable_freeform_support takes effect; the assistant was not started")
        }
        freeformOk = freeform
        if (!freeform || !enabled || !started) { act.finishAndRemoveTask(); @Suppress("DEPRECATION") act.overridePendingTransition(0, 0); return }
        val cmp = component ?: run { resolve(); component }
        if (cmp == null) { act.finishAndRemoveTask(); return }
        val now = SystemClock.elapsedRealtime()
        while (innerStarts.isNotEmpty() && now - innerStarts.first() > RATE_WINDOW_MS) innerStarts.removeFirst()
        val last = innerStarts.lastOrNull() ?: 0L
        if (innerStarts.size >= INNER_MAX_PER_10MIN || (last != 0L && now - last < INNER_MIN_GAP_MS)) {
            skip("the assistant keeps leaving our window (${innerStarts.size} starts in 10 min) - waiting")
            main.postDelayed({ request("assistant restart retry", 0L, force = true) }, 60_000L)
            return
        }
        innerStarts.addLast(now); innerCount++
        // No NEW_TASK: the assistant's activity joins OUR freeform task (it's standard/singleTop).
        runCatching {
            act.startActivity(Intent().setComponent(cmp).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
            @Suppress("DEPRECATION") act.overridePendingTransition(0, 0)
            Log.i(TAG, "keepalive: started ${cmp.flattenToShortString()} in the parked window (#$innerCount)")
        }.onFailure { Log.w(TAG, "keepalive: could not start ${cmp.flattenToShortString()}: ${it.message}") }
    }

    private fun removeOurTask(why: String) {
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            for (t in am.appTasks) {
                val base = runCatching { t.taskInfo.baseIntent.component?.className }.getOrNull()
                if (base == AvaKeepAliveActivity::class.java.name) {
                    t.finishAndRemoveTask()
                    Log.i(TAG, "keepalive: parked window removed ($why)")
                }
            }
        }.onFailure { Log.w(TAG, "keepalive: could not remove the parked window: ${it.message}") }
    }

    /** Cover the corner before the sliver appears (the park itself is a few frames away). */
    private fun coverBeforePark() {
        if (host.screenIsOn && host.dashboardInFront && !host.fullScreenOverlayUp && !coverShown) setCoverVisible(true)
    }

    private fun freeformOptions(bounds: Rect): android.os.Bundle {
        val opts = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        return opts.toBundle().apply { putInt(KEY_WINDOWING_MODE, WINDOWING_MODE_FREEFORM) }
    }

    private fun screenSize(): Point {
        val p = Point()
        @Suppress("DEPRECATION") runCatching { wm.defaultDisplay.getRealSize(p) }
        if (p.x <= 0 || p.y <= 0) { val dm = ctx.resources.displayMetrics; p.set(dm.widthPixels, dm.heightPixels) }
        return p
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** Launch bounds: the window's top-left 48x32 dp on screen in the bottom-right corner, the rest off it. */
    private fun launchBounds(): Rect {
        val s = screenSize()
        val l = s.x - dp(MIN_VISIBLE_W_DP); val t = s.y - dp(MIN_VISIBLE_H_DP)
        sliver = Rect(l, t, s.x, s.y)
        coverRect = Rect(l - dp(SHADOW_LEFT_DP), t - dp(SHADOW_TOP_DP), s.x, s.y)
        return Rect(l, t, l + dp(MIN_TASK_DP), t + dp(MIN_TASK_DP))
    }

    private fun resolve(): Boolean {
        if (component != null && avaUid >= 0) return true
        return runCatching {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            val launch = pm.getLaunchIntentForPackage(pkg)?.component ?: ComponentName(pkg, "$pkg.MainActivity")
            avaUid = ai.uid; component = launch
            Log.i(TAG, "keepalive: assistant ${launch.flattenToShortString()} uid=$avaUid")
            true
        }.getOrDefault(false)
    }

    // ── Freeform settings ────────────────────────────────────────────────────────

    private fun freeformGlobalsOn(): Boolean {
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, "enable_freeform_support", 0) == 1 &&
            Settings.Global.getInt(cr, "force_resizable_activities", 0) == 1
    }

    /** WRITE_SECURE_SETTINGS (granted at provisioning) lets us switch freeform on; the system only
     *  reads it at boot, so parking waits for the next reboot. */
    private fun enableFreeformGlobals() {
        val set = runCatching {
            Settings.Global.putInt(ctx.contentResolver, "enable_freeform_support", 1)
            Settings.Global.putInt(ctx.contentResolver, "force_resizable_activities", 1)
        }.isSuccess
        freeformOk = false
        Log.w(TAG, "keepalive: freeform windowing was off - " +
            if (set) "switched on; takes effect after the next reboot" else "could not switch it on (WRITE_SECURE_SETTINGS?)")
    }

    // ── Periodic safety check ────────────────────────────────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            runCatching { checkPauseEnd() }   // backup for the pause timer
            runCatching { safetyCheck() }.onFailure { Log.w(TAG, "keepalive: check failed: ${it.message}") }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun safetyCheck() {
        if (!enabled || !resolve()) return
        ensureLogReader()
        val why = when {
            lastParkMs == 0L -> "start"
            avaAppState == 0 -> "silenced (check)"
            host.screenIsOn && avaFg == 0 -> "not visible (check)"
            else -> null
        } ?: return
        request(why, 0L, force = lastParkMs == 0L)
    }

    // ── Logcat: AudioPolicyService's view of the assistant's uid ─────────────────

    private val fgRe = Regex("""onForegroundActivitiesChanged\(\)\s+(\d+)/(\d+)\s+->\s+(\d)""")
    private val stateRe = Regex("""Setting App state for uid = (\d+), state = (\d)""")

    private fun ensureLogReader() {
        if (logThread?.isAlive == true || avaUid < 0 || !enabled) return
        logThread = Thread({ readLoop() }, "portal-ha-keepalive-log").also { it.isDaemon = true; it.start() }
    }

    private fun stopLogReader() {
        logThread = null
        runCatching { logProc?.destroy() }
        logProc = null
    }

    private fun readLoop() {
        var failures = 0
        val me = Thread.currentThread()
        while (started && enabled && me === logThread) {
            try {
                // -T 1: start at the newest line (no backlog). READ_LOGS is granted at provisioning.
                val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "AudioPolicyService")
                    .redirectErrorStream(true).start()
                logProc = p
                Log.i(TAG, "keepalive: watching AudioPolicyService for uid $avaUid")
                p.inputStream.bufferedReader().use { r ->
                    while (started && enabled && me === logThread) {
                        val line = r.readLine() ?: break
                        onLogLine(line)
                    }
                }
                failures = 0
            } catch (e: Exception) {
                if (me === logThread) { failures++; Log.w(TAG, "keepalive: log reader: ${e.message}") }
            } finally {
                runCatching { logProc?.destroy() }
            }
            if (!started || !enabled || me !== logThread) break
            runCatching { Thread.sleep((2_000L shl failures.coerceAtMost(5)).coerceAtMost(60_000L)) }
        }
    }

    private fun onLogLine(line: String) {
        val uid = avaUid
        if (uid < 0) return
        fgRe.find(line)?.let { m ->
            if (m.groupValues[1].toIntOrNull() != uid) return
            val v = m.groupValues[3].toInt()
            avaFg = v
            // Lost its visible activity. With the screen on that is an activity start covering it
            // (the silencing follows); with the screen off the lock screen stopped it, which on its
            // own doesn't silence - a real "state = 0" (below) or the screen-on park handles that.
            if (v == 0 && host.screenIsOn) request("lost visibility", 1_500L)
            return
        }
        stateRe.find(line)?.let { m ->
            if (m.groupValues[1].toIntOrNull() != uid) return
            val v = m.groupValues[2].toInt()
            val was = avaAppState
            avaAppState = v
            if (v == 0 && was != 0) {
                Log.i(TAG, "keepalive: assistant uid $uid silenced by the audio policy")
                request("silenced", 800L)
            }
        }
    }

    // ── Corner cover ─────────────────────────────────────────────────────────────

    private val coverTick = object : Runnable {
        override fun run() {
            runCatching { updateCover() }
            main.postDelayed(this, COVER_TICK_MS)
        }
    }

    private fun overlayLp(r: Rect, touchable: Boolean) = WindowManager.LayoutParams(
        r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left; y = r.top
        title = "PortalHA keep-alive cover"
    }

    /** Both windows are added once, hidden, as early as possible: overlays stack in the order they
     *  were added, so these stay BELOW every photo/sleep/now-playing overlay added later. */
    private fun ensureCoverWindows() {
        if (!supported || coverView != null || !Settings.canDrawOverlays(ctx)) return
        launchBounds()
        runCatching {
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.rgb(0xFA, 0xFA, 0xFA))
                visibility = View.GONE
            }
            val lp = overlayLp(coverRect, touchable = false)
            wm.addView(iv, lp)
            coverView = iv; coverLp = lp
            val g = View(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // Swallow touches on the sliver: a drag there would pull the parked window on
                // screen by its caption.
                @Suppress("ClickableViewAccessibility") setOnTouchListener { _, _ -> true }
            }
            val glp = overlayLp(sliver, touchable = false)
            wm.addView(g, glp)
            guardView = g; guardLp = glp
            Log.i(TAG, "keepalive: corner cover ready at ${coverRect.toShortString()} (sliver ${sliver.toShortString()})")
        }.onFailure { Log.w(TAG, "keepalive: cover window failed: ${it.message}"); removeCoverWindows() }
    }

    private fun removeCoverWindows() {
        coverView?.let { v -> runCatching { wm.removeView(v) } }
        guardView?.let { v -> runCatching { wm.removeView(v) } }
        coverView = null; guardView = null; coverLp = null; guardLp = null; coverShown = false
    }

    // Freeform not confirmed yet counts: a parked sliver may already be on screen (harmless if not -
    // the cover shows the very pixels behind it).
    private fun wantCover(): Boolean =
        enabled && freeformOk != false && lastParkMs != 0L &&
            host.screenIsOn && host.dashboardInFront && !host.fullScreenOverlayUp

    private fun updateCover(forceCopy: Boolean = false) {
        val want = wantCover()
        if (want != coverShown) { setCoverVisible(want); return }
        if (!want) return
        val now = SystemClock.elapsedRealtime()
        // -50 ms: the refresh must not slip a whole 1 s tick on jitter.
        if (forceCopy || now - lastCoverCopyMs >= COVER_REFRESH_MS - 50L) copyCover()
    }

    /**
     * Copy the dashboard pixels behind the corner into the cover. PixelCopy.request is synchronous
     * up to Android 13 and, measured on the Portal 10", takes ~7 ms per copy on a settled page and
     * up to ~100 ms while a page loads (it waits for the render thread) - so it runs on a thread of
     * its own; only setting the bitmap happens on the main thread.
     */
    private fun copyCover() {
        lastCoverCopyMs = SystemClock.elapsedRealtime()
        val r = Rect(coverRect)
        val h = copier() ?: return
        h.post {
            val t0 = SystemClock.elapsedRealtimeNanos()
            DashboardActivity.copyRegion(r) { bmp: Bitmap? ->
                if (bmp != null && coverShown) coverView?.setImageBitmap(bmp)
            }
            val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000L
            if (copyCount == Int.MAX_VALUE) { copyCount = 0; copyTotalUs = 0L }
            copyCount++; copyTotalUs += us
            if (us > copyMaxUs) copyMaxUs = us
        }
    }

    private var copyThread: HandlerThread? = null
    private var copyHandler: Handler? = null

    private fun copier(): Handler? {
        copyHandler?.let { return it }
        if (!started) return null
        return runCatching {
            val t = HandlerThread("portal-ha-keepalive-copy").also { it.start() }
            copyThread = t
            Handler(t.looper).also { copyHandler = it }
        }.getOrNull()
    }

    /**
     * Re-copy the corner at +0.4, +1.2 and +2.5 s: after a park, a page change or the cover coming
     * up, the page underneath may still be drawing, and the first copy would freeze it half-drawn
     * until the next refresh. A new burst replaces one still running.
     */
    fun refreshCoverSoon() {
        if (!supported || !started) return
        main.post {
            main.removeCallbacksAndMessages(burstToken)
            val t0 = SystemClock.uptimeMillis()
            for (d in COVER_BURST_MS) main.postAtTime({ updateCover(forceCopy = true) }, burstToken, t0 + d)
        }
    }

    private fun setCoverVisible(show: Boolean) {
        val v = coverView ?: return
        coverShown = show
        launchBounds()   // keep the geometry current
        coverLp?.let { lp ->
            if (lp.x != coverRect.left || lp.y != coverRect.top || lp.width != coverRect.width() || lp.height != coverRect.height()) {
                lp.x = coverRect.left; lp.y = coverRect.top; lp.width = coverRect.width(); lp.height = coverRect.height()
                runCatching { wm.updateViewLayout(v, lp) }
            }
        }
        v.visibility = if (show) View.VISIBLE else View.GONE
        val g = guardView
        guardLp?.let { lp ->
            if (g != null) {
                lp.x = sliver.left; lp.y = sliver.top; lp.width = sliver.width(); lp.height = sliver.height()
                lp.flags = if (show) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                           else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(g, lp) }
            }
        }
        if (show) {
            copyCover()
            refreshCoverSoon()
        }
    }
}
