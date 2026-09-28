package com.aeonos.portalha

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Receiver-only YouTube screen: the TV web client (youtube.com/tv) in a
 * WebView, launched by a DIAL cast from a phone. All control (browse, play,
 * pause, seek, queue) happens on the phone — the Portal just renders.
 *
 * The Leanback client gates on user agent, so we present a Tizen smart-TV UA
 * (verified fine on the Portal's Chromium-131 WebView: 1080p MSE, smooth).
 *
 * Exit paths back to the HA dashboard:
 *  - the phone disconnects ("Stop casting" doesn't send a DIAL DELETE — we
 *    watch the lounge bind channel's loungeStatus events for the connected
 *    remote count dropping to zero with nothing playing),
 *  - nothing has played for IDLE_EXIT_MS (phone walked away / crashed),
 *  - a long-press anywhere on the screen,
 *  - an explicit DIAL DELETE /run (rare, but part of the spec).
 */
class TvAppActivity : Activity() {

    companion object {
        private const val TAG = "PortalHA"
        private const val EXTRA_QUERY = "launch_query"
        private const val TV_URL = "https://www.youtube.com/tv"
        // A UA youtube.com/tv accepts; without it the page bounces to youtube.com.
        private const val TV_UA =
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36"

        private const val POLL_MS = 5_000L          // playback/exit re-check cadence
        private const val DISCONNECT_GRACE_MS = 10_000L  // remotes==0 + not playing this long → exit
        private const val IDLE_EXIT_MS = 5 * 60_000L     // nothing played this long → exit

        // Live instance so BridgeService can close us when the phone disconnects
        // (same pattern as BridgeService.instance). Cleared in onDestroy.
        @Volatile private var instance: TvAppActivity? = null

        /** True while the YouTube screen is up (DIAL app state for the phone). */
        fun isShowing(): Boolean = instance != null

        /** Bring up the YouTube screen with the DIAL launch params (pairingCode=…). */
        fun launch(ctx: Context, query: String) {
            ctx.startActivity(Intent(ctx, TvAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_QUERY, query))
        }

        /** Close the YouTube screen (explicit DIAL stop). Safe from any thread. */
        fun close() {
            instance?.let { it.runOnUiThread { it.exitToDashboard("dial stop") } }
        }
    }

    private lateinit var webView: WebView
    private lateinit var gestures: GestureDetector
    private val handler = Handler(Looper.getMainLooper())

    // Connected-remote tracking, fed by the lounge bind channel via CastBridge.
    // Starts at 1: we exist because a phone just cast to us.
    @Volatile private var remotes = 1
    @Volatile private var lastRemoteSeenMs = 0L   // last time remotes was > 0
    @Volatile private var lastPlayingMs = 0L      // last time a video was actually playing
    private var exiting = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersive()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = TV_UA
        }
        webView.addJavascriptInterface(CastBridge(), "PortalCast")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false   // keep everything in the TV client

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(loungeShimJs(), null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(loungeShimJs(), null)
            }
        }

        // Escape hatch: receiver-only by design, but a long-press anywhere exits
        // back to the dashboard immediately.
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) { exitToDashboard("long-press") }
        })

        loadFromIntent(intent)
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadFromIntent(intent)   // a new cast while already showing
    }

    private fun loadFromIntent(intent: Intent?) {
        val query = intent?.getStringExtra(EXTRA_QUERY).orEmpty()
        val url = if (query.isBlank()) TV_URL else "$TV_URL?$query"
        val now = System.currentTimeMillis()
        remotes = 1; lastRemoteSeenMs = now; lastPlayingMs = now   // fresh grace period
        Log.i(TAG, "cast: loading $url")
        webView.loadUrl(url)
    }

    // ── Exit policy ───────────────────────────────────────────────────────────

    private val pollRunnable = object : Runnable {
        override fun run() {
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video');" +
                    "return (v&&!v.paused&&!v.ended&&v.currentTime>0)?1:0;})()"
            ) { result ->
                val now = System.currentTimeMillis()
                if (result?.trim() == "1") lastPlayingMs = now
                val remoteGone = remotes == 0 &&
                    now - lastRemoteSeenMs > DISCONNECT_GRACE_MS &&
                    now - lastPlayingMs > DISCONNECT_GRACE_MS
                when {
                    remoteGone -> exitToDashboard("phone disconnected")
                    now - lastPlayingMs > IDLE_EXIT_MS -> exitToDashboard("idle")
                    else -> handler.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    /** All exits land here: bring the HA dashboard back, then finish. */
    private fun exitToDashboard(reason: String) {
        if (exiting) return
        exiting = true
        Log.i(TAG, "cast: exit ($reason) → dashboard")
        runCatching {
            startActivity(Intent(this, DashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        finish()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        BridgeService.noteTouch()   // activity for the screen-off timer
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // Fed by the JS shim below with the number of connected remotes each time
    // the lounge bind channel reports a loungeStatus.
    inner class CastBridge {
        @JavascriptInterface
        fun remotes(n: Int) {
            remotes = n
            if (n > 0) lastRemoteSeenMs = System.currentTimeMillis()
        }
    }

    /**
     * Two jobs, injected before the client boots:
     *
     * 1. NAME: the lounge screen name is what the phone's cast menu shows, but
     *    the web client hardcodes "YouTube on TV" on its bind request (it
     *    ignores the name stored in localStorage — verified on-device).
     *    Rewrite the bind URL's name param to this Portal's device name.
     *
     * 2. DISCONNECT: the bind long-poll's streamed response carries lounge
     *    events; each loungeStatus lists the connected devices. Count the
     *    REMOTE_CONTROL entries in every new chunk and report to PortalCast —
     *    zero remotes = the phone disconnected (there is no DIAL DELETE for it).
     */
    private fun loungeShimJs(): String {
        val name = Prefs(this).deviceName
            .replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function () {
              if (window.__phaShim) return; window.__phaShim = true;
              function fix(u) {
                try {
                  if (typeof u === 'string' && u.indexOf('/api/lounge/') > -1 && /[?&]name=/.test(u)) {
                    return u.replace(/([?&]name=)[^&]*/, '$1' + encodeURIComponent('$name'));
                  }
                } catch (e) {}
                return u;
              }
              function scan(xhr) {
                try {
                  var t = xhr.responseText || '';
                  var chunk = t.substring(xhr.__phaSeen || 0);
                  xhr.__phaSeen = t.length;
                  if (chunk.indexOf('loungeStatus') > -1 && window.PortalCast) {
                    var n = (chunk.match(/REMOTE_CONTROL/g) || []).length;
                    PortalCast.remotes(n);
                  }
                } catch (e) {}
              }
              var xo = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function (m, u) {
                arguments[1] = fix(u);
                if (typeof arguments[1] === 'string' &&
                    arguments[1].indexOf('/api/lounge/bc/bind') > -1) {
                  var xhr = this;
                  xhr.addEventListener('progress', function () { scan(xhr); });
                  xhr.addEventListener('load', function () { scan(xhr); });
                }
                return xo.apply(this, arguments);
              };
              if (window.fetch) {
                var of = window.fetch;
                window.fetch = function (u, o) {
                  if (typeof u === 'string') u = fix(u); return of.call(this, u, o);
                };
              }
            })();
        """.trimIndent()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersive() {
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(pollRunnable)
        BridgeService.castScreenClosed()
        webView.destroy()
        super.onDestroy()
    }
}
