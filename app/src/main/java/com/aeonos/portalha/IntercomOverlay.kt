package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

// One floating push-to-talk button, labelled with its name and bound to a target.
//
// - Press and HOLD to announce to this button's target, release to stop.
// - DOUBLE-TAP to toggle "move mode" (button goes solid); drag it anywhere; double-
//   tap again to lock. Position is saved per button via onMoved.
// - In move mode a delete target appears; dragging the button onto it removes the button.
// - Idle opacity comes from settings; it's solid while moving or live.
class IntercomOverlay(
    private val context: Context,
    private val label: String,
    private val initialX: Int,
    private val initialY: Int,
    private val defaultIndex: Int,
    private val onDown: () -> Boolean,
    private val onUp: () -> Unit,
    private val onMoved: (x: Int, y: Int) -> Unit,
    // Move mode entered/exited — the service shows/hides the delete target.
    private val onMoveMode: (active: Boolean) -> Unit = {},
    // True if the button's centre (cx,cy) is over the delete target; the service also
    // highlights the target as a side effect. Queried on drag and on release.
    private val overDeleteZone: (cx: Int, cy: Int) -> Boolean = { _, _ -> false },
    // Dropped on the delete target — the service removes this button and rebuilds.
    private val onDelete: () -> Unit = {}
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val TALK_DELAY_MS = 280L   // hold = talk; quick tap = double-tap candidate

        // Background colour from hue/saturation/value (shared with the settings preview).
        fun bgColor(prefs: Prefs): Int = Color.HSVToColor(floatArrayOf(
            prefs.intercomButtonHue.toFloat(),
            prefs.intercomButtonSat / 100f,
            prefs.intercomButtonVal / 100f))

        // Text colour from hue + a single shade: 0 = black, 50 = full hue colour,
        // 100 = white (so plain white/black text and coloured text are all reachable).
        fun textColor(prefs: Prefs): Int {
            val t = (prefs.intercomTextShade / 100f).coerceIn(0f, 1f)
            val h = prefs.intercomTextHue.toFloat()
            return if (t <= 0.5f) Color.HSVToColor(floatArrayOf(h, 1f, (t * 2f).coerceIn(0f, 1f)))
                   else Color.HSVToColor(floatArrayOf(h, (1f - (t - 0.5f) * 2f).coerceIn(0f, 1f), 1f))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private val wm get() = context.getSystemService(WindowManager::class.java)

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    // Staged-refloat state (see prepareRefloat/completeRefloat): the outgoing window
    // pending removal, whether the incoming one should stay hidden until revealed, and
    // whether a default-slot button has been right-aligned yet.
    private var pendingOld: TextView? = null
    @Volatile private var stageHidden = false
    @Volatile private var defaultPlaced = false

    // Latched by hide(): a hidden overlay is dead and must never add a window again.
    // show() adds its window in a POSTED runnable, so without this latch a
    // hide()-before-the-post-ran was a no-op (view still null) and the post then
    // added a window NOBODY tracked — a ghost button that no drag-to-delete or
    // rebuild could ever remove (only an app restart). Rebuild churn (settings
    // apply, delete-and-rebuild, dashboard resume cycles) minted one ghost per
    // interrupted show.
    @Volatile private var destroyed = false

    private val liveColor = Color.parseColor("#E53935")

    @Volatile private var moveMode = false
    private var talking = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    private val startTalkRunnable = Runnable {
        if (onDown()) { talking = true; applyVisual() }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (destroyed || view != null) return
        if (!Settings.canDrawOverlays(context)) { Log.w(TAG, "intercom overlay: no overlay permission"); return }
        main.post {
            if (destroyed || view != null) return@post
            runCatching {
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()

                val btn = TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    maxLines = 1
                }

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = if (initialX >= 0) initialX else dp(16)
                    y = if (initialY >= 0) initialY else dp(48) + defaultIndex * dp(64)
                }

                val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        main.removeCallbacks(startTalkRunnable)
                        if (talking) { onUp(); talking = false }
                        moveMode = !moveMode
                        applyVisual()
                        onMoveMode(moveMode)   // service shows/hides the delete target
                        return true
                    }
                })

                btn.setOnTouchListener { _, ev ->
                    gesture.onTouchEvent(ev)
                    val p = params ?: return@setOnTouchListener true
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (moveMode) {
                                downRawX = ev.rawX; downRawY = ev.rawY; startX = p.x; startY = p.y
                            } else {
                                main.postDelayed(startTalkRunnable, TALK_DELAY_MS)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> if (moveMode) {
                            p.x = startX + (ev.rawX - downRawX).toInt()
                            p.y = startY + (ev.rawY - downRawY).toInt()
                            runCatching { wm.updateViewLayout(view, p) }
                            overDeleteZone(centerX(p), centerY(p))   // live-highlight the delete target
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (moveMode) {
                                val moved = kotlin.math.abs(p.x - startX) + kotlin.math.abs(p.y - startY) > dp(6)
                                if (moved && overDeleteZone(centerX(p), centerY(p))) {
                                    // Dropped on the delete target → remove this button.
                                    moveMode = false
                                    onMoveMode(false)
                                    onDelete()
                                    return@setOnTouchListener true
                                }
                                onMoved(p.x, p.y)
                                // If this was an actual drag (not the double-tap that
                                // entered move mode), lock it back to normal and repaint
                                // with the user's colour/opacity settings.
                                if (moved) { moveMode = false; applyVisual(); onMoveMode(false) }
                            } else {
                                main.removeCallbacks(startTalkRunnable)
                                if (talking) { onUp(); talking = false; applyVisual() }
                            }
                        }
                    }
                    true
                }

                view = btn
                params = lp
                applyVisual()
                defaultPlaced = initialX >= 0
                // Default slot: the real x needs the measured width, so the button is
                // added at a placeholder position — keep it INVISIBLE until it's been
                // right-aligned, or it flashes at the top-left for a frame each show.
                // A staged refloat (stageHidden) keeps it hidden until completeRefloat.
                if (initialX < 0 || stageHidden) btn.visibility = android.view.View.INVISIBLE
                wm.addView(btn, lp)

                // Default slot: right-align once we know the measured width.
                if (initialX < 0) btn.post {
                    val p = params ?: return@post
                    p.x = (context.resources.displayMetrics.widthPixels - btn.width - dp(16)).coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(btn, p) }
                    defaultPlaced = true
                    if (!stageHidden) btn.visibility = android.view.View.VISIBLE
                }
                Log.i(TAG, "intercom overlay '$label' shown")
            }.onFailure { Log.w(TAG, "intercom overlay show failed: ${it.message}") }
        }
    }

    private fun applyVisual() {
        val v = view ?: return
        val density = context.resources.displayMetrics.density
        val idleAlpha = (prefs.intercomOverlayOpacity / 100f).coerceIn(0.1f, 1f)
        val chosen = bgColor(prefs)
        val transparentIdle = prefs.intercomTransparentBg
        val (bg, alpha) = when {
            moveMode -> chosen to 1f                                  // solid while moving
            talking  -> liveColor to 1f                              // red while live
            else     -> (if (transparentIdle) Color.TRANSPARENT else chosen) to idleAlpha
        }
        v.text = if (talking) "● $label" else label
        v.setTextColor(textColor(prefs))
        v.alpha = alpha
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40 * density
            setColor(bg)
            // Keep a faint outline so a transparent button is still findable.
            setStroke((2 * density).toInt(), Color.parseColor("#80FFFFFF"))
        }
    }

    // Button centre on screen (params origin is top-left; add half the measured size).
    private fun centerX(p: WindowManager.LayoutParams) = p.x + (view?.width ?: 0) / 2
    private fun centerY(p: WindowManager.LayoutParams) = p.y + (view?.height ?: 0) / 2

    // Re-read prefs (opacity) and repaint — called when the settings slider moves.
    fun refresh() = main.post { applyVisual() }

    fun hide() {
        // Latch FIRST: any queued show() post sees this and skips its addView.
        destroyed = true
        // Teardown on the main thread, AFTER any already-queued show() post — so
        // whichever windows actually got added (current, staged, outgoing) are
        // all removed regardless of interleaving.
        main.post {
            main.removeCallbacks(startTalkRunnable)
            stageHidden = false
            pendingOld?.let { o -> pendingOld = null; runCatching { wm.removeView(o) } }
            view?.let { v -> view = null; params = null; runCatching { wm.removeView(v) } }
        }
    }

    /**
     * Staged refloat, step 1: build a REPLACEMENT window above any newer overlay window
     * (the wake-handoff cover — later-added windows draw on top), kept INVISIBLE at its
     * final position; [onReady] fires once it's attached, measured, and (for default-slot
     * buttons) right-aligned. The old window stays visible until completeRefloat(), so
     * callers can swap cover + button in a single frame — no blink, no alpha stacking.
     */
    fun prepareRefloat(onReady: Runnable) {
        val old = view
        if (destroyed || old == null || pendingOld != null) { onReady.run(); return }
        pendingOld = old
        view = null; params = null
        stageHidden = true
        main.removeCallbacks(startTalkRunnable)
        show()
        var tries = 0
        lateinit var waitReady: Runnable
        waitReady = Runnable {
            val nv = view
            when {
                nv != null && nv.width > 0 && defaultPlaced -> onReady.run()
                ++tries > 30 -> onReady.run()   // ~0.5s cap — never stall the handoff
                else -> main.postDelayed(waitReady, 16)
            }
        }
        main.post(waitReady)
    }

    /** Staged refloat, step 2: reveal the replacement and drop the outgoing window. */
    fun completeRefloat() {
        stageHidden = false
        view?.visibility = android.view.View.VISIBLE
        pendingOld?.let { o -> pendingOld = null; main.post { runCatching { wm.removeView(o) } } }
    }
}
