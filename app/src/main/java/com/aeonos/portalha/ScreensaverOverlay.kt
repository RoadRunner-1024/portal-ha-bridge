package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Photo-frame screensaver: an ImmichFrame / Immich Kiosk page rendered full-screen over the
 * dashboard.
 *
 * ★It is an OVERLAY, never an Activity or a DreamService. Backgrounding DashboardActivity is
 * what evicts Camera 0 on Android 10, which kills the RTSP stream (see the stream-wedge notes) —
 * so the photos are drawn in a TYPE_APPLICATION_OVERLAY window and the dashboard stays the
 * foreground activity underneath, still streaming. This is the same trick the wake cover, the
 * talk buttons and the announce orb already use.
 *
 * The page is a JavaScript app, so we render it rather than scraping it — there is no image in
 * its HTML to scrape. The WebView is built on [show] and destroyed on [hide] rather than kept
 * around: a Portal has ~2.8 GB of RAM and the dashboard's own renderer is already the largest
 * process on the device.
 *
 * ## Touch
 * A transparent catcher sits above the WebView and takes every touch, so the page never sees
 * one. That is deliberate: it lets us keep the frame's own left/right navigation *and* still
 * have a way out, which the page alone could not give us.
 *
 *  - left third   → previous photo
 *  - right third  → next photo
 *  - centre third → exit the screensaver (full height)
 *
 * Navigation is delivered as a synthetic `keydown` on `window`, because both frames listen for
 * one and only read `event.key`:
 *   - ImmichFrame: `{ArrowRight: next, ArrowLeft: back, " ": pause, i: showInfo}`
 *   - Immich Kiosk: the same arrows, plus space for play/pause
 * Injecting keys is steadier than synthesising clicks on their invisible edge buttons, which
 * would break the moment either project moves its markup around.
 */
class ScreensaverOverlay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)

    @Volatile private var root: FrameLayout? = null
    private var web: WebView? = null
    private var onExit: (() -> Unit)? = null
    @Volatile private var visible = false

    /** Photos are on screen right now. */
    val isShowing: Boolean get() = root != null && visible

    /** Loaded and running, but not on screen — see [prestage]. */
    val isStaged: Boolean get() = root != null

    /**
     * Put [url] on screen. [exit] is called on a centre tap — the caller owns what "exit" means
     * (it also has to restart its idle timer, so we don't self-hide here).
     *
     * If the page was already [prestage]d this only reveals it, which is the whole point: the
     * frame appears at once instead of showing the bare white SvelteKit shell for a second while
     * it boots.
     */
    fun show(url: String, exit: () -> Unit) {
        if (isStaged) { setVisible(true); return }
        create(url, exit, showNow = true)
    }

    /**
     * Load the page but keep it off screen, ready to appear instantly.
     *
     * Used while the panel sleeps: ImmichFrame is a JavaScript app and takes a moment to boot,
     * during which it paints white — unacceptable as the first thing you see when you walk up to
     * a Portal. Staging it behind a dark screen moves that cost somewhere nobody is looking.
     *
     * Costs a live WebView for as long as it is staged, which is why it is opt-in: these panels
     * have ~2.8 GB of RAM and the dashboard's own renderer is already the biggest process on the
     * device.
     */
    fun prestage(url: String, exit: () -> Unit) {
        if (isStaged) { setVisible(false); return }   // already loaded — just conceal it
        create(url, exit, showNow = false)
    }

    private fun setVisible(want: Boolean) {
        main.post {
            val v = root ?: return@post
            if (visible == want) return@post
            visible = want
            // No fade when concealing: that only happens behind a dark screen, where an
            // animation would just be work nobody can see.
            if (want) v.animate().alpha(1f).setDuration(FADE_MS).start() else v.alpha = 0f
            android.util.Log.i(TAG, "screensaver: ${if (want) "revealed (prestaged)" else "concealed"}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(url: String, exit: () -> Unit, showNow: Boolean) {
        if (url.isBlank()) return
        if (!Settings.canDrawOverlays(context)) {
            android.util.Log.w(TAG, "screensaver: no overlay permission — not showing")
            return
        }
        main.post {
            if (root != null) return@post
            runCatching {
                onExit = exit
                val container = FrameLayout(context)
                container.setBackgroundColor(Color.BLACK)

                val wv = WebView(context)
                wv.setBackgroundColor(Color.BLACK)
                wv.isVerticalScrollBarEnabled = false
                wv.isHorizontalScrollBarEnabled = false
                wv.overScrollMode = View.OVER_SCROLL_NEVER
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                // Keep any navigation the page does inside this WebView.
                wv.webViewClient = WebViewClient()
                container.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))

                // Transparent catcher ABOVE the page: added last, so it wins every touch.
                val catcher = View(context)
                catcher.setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_UP) onTap(e.x, v.width)
                    true
                }
                container.addView(catcher, FrameLayout.LayoutParams(MATCH, MATCH))

                val lp = WindowManager.LayoutParams(
                    MATCH, MATCH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Touchable (no FLAG_NOT_TOUCHABLE) but not focusable: we want taps without
                    // taking key focus off the dashboard. KEEP_SCREEN_ON so the panel doesn't
                    // blank mid-slideshow — the caller decides when photos end.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                )
                lp.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED

                root = container
                web = wv
                visible = showNow
                container.alpha = 0f
                wm.addView(container, lp)
                if (showNow) container.animate().alpha(1f).setDuration(FADE_MS).start()
                wv.loadUrl(url)
                android.util.Log.i(TAG,
                    if (showNow) "screensaver: shown ($url)" else "screensaver: prestaged ($url)")
            }.onFailure {
                android.util.Log.w(TAG, "screensaver: show failed: ${it.message}")
                root = null
                web = null
                visible = false
            }
        }
    }

    fun hide() {
        main.post {
            val v = root ?: return@post
            root = null
            onExit = null
            visible = false
            val wv = web
            web = null
            v.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                runCatching { wm.removeView(v) }
                // Tear the page down properly or it keeps running (and holding memory) invisibly.
                runCatching {
                    wv?.stopLoading()
                    wv?.loadUrl("about:blank")
                    (wv?.parent as? ViewGroup)?.removeView(wv)
                    wv?.destroy()
                }
            }.start()
            android.util.Log.i(TAG, "screensaver: hidden")
        }
    }

    private fun onTap(x: Float, width: Int) {
        if (width <= 0) return
        when {
            x < width / 3f -> sendKey("ArrowLeft", "previous")
            x > width * 2f / 3f -> sendKey("ArrowRight", "next")
            else -> {
                android.util.Log.i(TAG, "screensaver: centre tap -> exit")
                onExit?.invoke()
            }
        }
    }

    /** Both frames bind their navigation to a `window` keydown and read only `event.key`. */
    private fun sendKey(key: String, what: String) {
        val wv = web ?: return
        android.util.Log.i(TAG, "screensaver: $what")
        runCatching {
            wv.evaluateJavascript(
                "window.dispatchEvent(new KeyboardEvent('keydown'," +
                    "{key:'$key',bubbles:true,cancelable:true}));",
                null
            )
        }
    }

    private companion object {
        const val TAG = "PortalHA"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val FADE_MS = 400L
    }
}
