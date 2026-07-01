package com.aeonos.portalha

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

// A circular "✕" drop target shown at the bottom-centre of the screen while a talk
// button is in move mode. Dragging a button onto it deletes that button (the hit-test
// + deletion live in BridgeService; this is purely the visual target). It never takes
// touches itself (FLAG_NOT_TOUCHABLE) — the dragged button reports its position.
class DeleteTargetOverlay(
    private val context: Context,
    private val centerX: Int,
    private val centerY: Int,
    private val sizePx: Int,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        main.post {
            runCatching {
                val tv = TextView(context).apply {
                    text = "✕"   // ✕
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    gravity = Gravity.CENTER
                }
                val lp = WindowManager.LayoutParams(
                    sizePx, sizePx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = centerX - sizePx / 2
                    y = centerY - sizePx / 2
                }
                paint(tv, false)
                view = tv
                wm.addView(tv, lp)
            }
        }
    }

    // Highlight red when a button is hovering over it (about to delete), grey otherwise.
    fun setActive(active: Boolean) = main.post { view?.let { paint(it, active) } }

    private fun paint(tv: TextView, active: Boolean) {
        val density = context.resources.displayMetrics.density
        tv.alpha = if (active) 1f else 0.9f
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.parseColor("#E53935") else Color.parseColor("#CC424242"))
            setStroke((2 * density).toInt(), Color.WHITE)
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        main.post { runCatching { wm.removeView(v) } }
    }
}
