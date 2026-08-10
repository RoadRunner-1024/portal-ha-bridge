package com.aeonos.portalha

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Hides what the screen wakes onto.
 *
 * While a Portal sleeps, something else takes HOME behind the dark screen — Meta's stock
 * launcher fires idle HOME intents, and whatever is set as launcher (Immortal) is what they
 * resolve to. So the display lights up showing that, and only then does our screen-on handler
 * pull the dashboard back: a visible flash of somebody else's screen on every single wake.
 *
 * Rather than racing it, the wake is covered. The cover goes up when the screen goes OFF, so it
 * is already composited before the panel lights, and comes down a moment after the dashboard is
 * back in front. Overlay windows draw above every activity, so it does not matter which app won
 * HOME — nothing under it is ever seen. It also does not matter *why* the flash happens, which
 * is the point: the daydream and the launcher both produced it, and this covers any future
 * variant too.
 *
 * The screen is off the whole time it is being put up, so the cover itself is never seen
 * appearing.
 */
class SleepCover(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)

    @Volatile private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            if (view != null) return@post
            runCatching {
                val v = View(context)
                v.setBackgroundColor(Color.BLACK)
                val lp = WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Not touchable: the tap that wakes the Portal must reach whatever is
                    // underneath rather than being swallowed here, and a cover that ate input
                    // would strand the panel if removal ever failed.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                )
                view = v
                wm.addView(v, lp)
                android.util.Log.i("PortalHA", "sleep cover: up (wake will be hidden)")
                // Safety net: never let a missed screen-on leave the panel black. Removal is
                // idempotent, so this racing a normal hide is harmless.
                main.postDelayed({ hide() }, MAX_COVER_MS)
            }.onFailure { view = null }
        }
    }

    /** Fade out — by now the dashboard is behind it, so this reveals our own screen. */
    fun hide() {
        main.post {
            val v = view ?: return@post
            view = null
            android.util.Log.i("PortalHA", "sleep cover: revealing dashboard")
            v.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { runCatching { wm.removeView(v) } }.start()
        }
    }

    private companion object {
        const val FADE_MS = 220L
        const val MAX_COVER_MS = 15_000L
    }
}
