package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * The dashboard's left-edge swipe, available over other apps: a thin strip down the left edge
 * with a faint grab-handle. Swipe right from it and [onSwipe] brings Portal HA Bridge back with
 * its menu open — an easy way home from Reolink, Calls, a browser, anything.
 *
 * It is a touchable overlay, so it takes the left [STRIP_DP] of whatever app is underneath.
 * Only a rightward swipe does anything; a tap or a vertical drag on it is swallowed and ignored.
 * BridgeService shows it only while another app is in front and never during a call
 * (ringing included), where it could sit over the Answer button.
 */
class EdgeSwipeOverlay(private val context: Context, private val onSwipe: () -> Unit) {
    companion object {
        private const val STRIP_DP = 14
        private const val TRIGGER_DP = 36    // how far right a swipe must travel
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)
    private var view: StripView? = null

    fun show() = main.post {
        if (view != null || !Settings.canDrawOverlays(context)) return@post
        runCatching {
            val d = context.resources.displayMetrics.density
            val v = StripView(context)
            val lp = WindowManager.LayoutParams(
                (STRIP_DP * d).toInt(), WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.START or Gravity.TOP }
            wm.addView(v, lp)
            view = v
        }
    }

    fun hide() = main.post {
        val v = view ?: return@post
        view = null
        runCatching { wm.removeView(v) }
    }

    private inner class StripView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }   // ~35% white
        private val rect = RectF()
        private var downX = 0f
        private var fired = false

        override fun onDraw(canvas: Canvas) {
            // A short pill hugging the edge, vertically centred — just enough to be findable.
            val w = 4 * d; val h = 56 * d
            rect.set(3 * d, (height - h) / 2f, 3 * d + w, (height + h) / 2f)
            canvas.drawRoundRect(rect, w / 2f, w / 2f, handle)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; fired = false }
                MotionEvent.ACTION_MOVE ->
                    if (!fired && e.rawX - downX >= TRIGGER_DP * d) {
                        fired = true
                        onSwipe()
                    }
            }
            return true
        }
    }
}
