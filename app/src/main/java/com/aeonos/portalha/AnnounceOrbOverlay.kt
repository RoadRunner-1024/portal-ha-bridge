package com.aeonos.portalha

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// A Valve-Portal-style oval aperture, full-screen overlay, shown while a voice announce is
// active. ORANGE on the transmitting Portal (interactive: tap anywhere to stop); BLUE on every
// receiving Portal (non-touchable) while it plays an announcement. A glowing layered rim, a
// swirling interior vortex, and orbiting sparks; setLevel drives the throb from the live audio.
// Look tuned on-device: size 100, opacity 84, rim 201, energy 0.
class AnnounceOrbOverlay(
    private val context: Context,
    private val blue: Boolean = false,
    private val interactive: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)
    @Volatile private var view: OrbView? = null
    @Volatile var onTap: (() -> Unit)? = null

    // Sticky state: show() attaches the view in a POSTED runnable, so a setter
    // called right after show() used to land on view==null and vanish — a PTT
    // press's single setTransmitting(true) was lost and the orb stayed blue.
    // (The hands-free path masked this by re-sending state every mic frame.)
    @Volatile private var lastLive = false
    @Volatile private var lastLevel = 0
    @Volatile private var lastTransmitting = false

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            if (view != null) return@post
            runCatching {
                val v = OrbView(context, blue)
                var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                if (!interactive) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags, PixelFormat.TRANSLUCENT)
                if (interactive) v.setOnClickListener { onTap?.invoke() }
                // Apply any state set between show() and this post running.
                v.live = lastLive; v.level = lastLevel; v.transmitting = lastTransmitting
                view = v
                wm.addView(v, lp)
            }
        }
    }

    fun setLive(live: Boolean) { lastLive = live; view?.live = live }
    fun setLevel(level: Int) { lastLevel = level; view?.level = level }
    fun setTransmitting(t: Boolean) { lastTransmitting = t; view?.transmitting = t }

    fun hide() {
        main.post {
            val v = view ?: return@post
            view = null
            v.animate().alpha(0f).setDuration(200).withEndAction { runCatching { wm.removeView(v) } }.start()
        }
    }

    private class OrbView(context: Context, private val blue: Boolean) : View(context) {
        @Volatile var level = 0
        @Volatile var live = false
        @Volatile var transmitting = false   // this Portal holds the floor → warm toward orange
        private var smoothV = 0f
        private var warmth = 0f
        private val oval = RectF()
        private val arc = RectF()
        private val clip = Path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t0 = System.nanoTime()
        private val parts = Array(55) { Spark() }
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { postInvalidateOnAnimation() }
        }
        private class Spark {
            var a = Random.nextFloat() * 6.283f
            val rf = 0.86f + Random.nextFloat() * 0.2f
            val sp = (0.4f + Random.nextFloat() * 0.9f) * (if (Random.nextBoolean()) 1f else -1f)
            val sz = 0.5f + Random.nextFloat() * 1.6f
            val ph = Random.nextFloat() * 6f
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        // Orange-base orbs are always orange; blue-base orbs warm toward orange (by [warmth])
        // when this Portal is transmitting. a = alpha fraction 0..1.
        private fun col(a: Float, o1: Int, o2: Int, o3: Int, b1: Int, b2: Int, b3: Int): Int {
            val al = (a * 255f).coerceIn(0f, 255f).toInt()
            val idle = if (blue) Color.argb(al, b1, b2, b3) else Color.argb(al, o1, o2, o3)
            if (!blue || warmth <= 0.01f) return idle
            return lerpColor(idle, Color.argb(al, o1, o2, o3), warmth)
        }
        private fun lerpColor(c1: Int, c2: Int, tt: Float): Int = Color.argb(
            (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * tt).toInt(),
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * tt).toInt(),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * tt).toInt(),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * tt).toInt())

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w < 2f || h < 2f) return
            val cx = w / 2f; val cy = h / 2f
            val t = (System.nanoTime() - t0) / 1e9f
            val maxR = min(w, h) / 2f * 0.99f                 // SIZE = 100
            val OPAC = 0.84f; val RIM = 2.01f                 // opacity 84, rim 201
            val tv = if (live) level / 100f else 0f
            smoothV += (tv - smoothV) * 0.25f
            warmth += ((if (transmitting) 1f else 0f) - warmth) * 0.22f   // quick graceful blue<->orange
            val breathe = 1f + 0.015f * sin(t * 2.1f) + smoothV * 0.05f
            val rx = maxR * 0.60f * breathe; val ry = maxR * 0.95f * breathe
            val bright = if (live) 1f else 0.85f
            val swirl = t * 0.5f * (if (live) 1.5f else 1f)   // energy 0 -> slow steady spin
            val rimA = min(1f, OPAC + 0.25f)
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry)

            // ---- interior vortex, clipped to the oval ----
            canvas.save()
            clip.reset(); clip.addOval(oval, Path.Direction.CW); canvas.clipPath(clip)
            fill.shader = RadialGradient(cx, cy, ry,
                intArrayOf(
                    col(0.35f * OPAC, 80, 20, 0, 0, 20, 60),
                    col(0.50f * OPAC * bright, 255, 120, 20, 30, 120, 220),
                    col(0.80f * OPAC * bright, 255, 190, 90, 120, 190, 255),
                    col(0.95f * OPAC * bright, 255, 240, 210, 220, 245, 255)),
                floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, fill)
            fill.shader = null
            stroke.strokeWidth = maxOf(1f, maxR * 0.012f * RIM)
            for (i in 0 until 14) {
                val f = 0.30f + i * 0.05f
                val a0 = swirl * (0.6f + f) + i * 0.9f
                val span = 1.1f + (i % 3) * 0.5f
                val al = (0.10f + 0.10f * abs(sin(t * 3f + i))) * OPAC
                stroke.color = if (i % 2 == 0) col(al, 255, 245, 220, 225, 245, 255)
                               else col(al, 255, 150, 40, 80, 170, 255)
                arc.set(cx - rx * f, cy - ry * f, cx + rx * f, cy + ry * f)
                canvas.drawArc(arc, a0 * 57.2958f, span * 57.2958f, false, stroke)
            }
            canvas.restore()

            // ---- glowing layered rim ----
            drawRim(canvas, maxR * 0.05f * RIM * 1.9f, col(0.28f * bright * rimA, 255, 110, 20, 20, 110, 230))
            drawRim(canvas, maxR * 0.05f * RIM * 1.25f, col(0.50f * bright * rimA, 255, 150, 45, 45, 150, 255))
            drawRim(canvas, maxR * 0.05f * RIM * 0.7f, col(0.85f * bright * rimA, 255, 200, 110, 120, 200, 255))
            drawRim(canvas, maxR * 0.05f * RIM * 0.32f, col(1.0f * bright * rimA, 255, 248, 225, 225, 248, 255))

            // ---- orbiting rim sparks ----
            val spd = 0.004f * (if (live) 1.6f else 1f)       // energy 0 base
            for (p in parts) {
                p.a += p.sp * spd
                val wob = 1f + 0.05f * sin(t * 3f + p.ph)
                val px = cx + cos(p.a) * rx * p.rf * wob
                val py = cy + sin(p.a) * ry * p.rf * wob
                val al = min(1f, OPAC + 0.3f) * (0.5f + 0.5f * sin(t * 6f + p.ph)) * bright
                dot.color = if (p.rf > 1.0f) col(al, 255, 240, 210, 220, 245, 255)
                            else col(al, 255, 170, 70, 90, 180, 255)
                canvas.drawCircle(px, py, p.sz * (if (live) 1.5f else 1f) * (0.6f + RIM * 0.4f), dot)
            }
        }

        private fun drawRim(canvas: Canvas, wdt: Float, c: Int) {
            stroke.strokeWidth = maxOf(1f, wdt); stroke.color = c
            canvas.drawOval(oval, stroke)
        }
    }
}