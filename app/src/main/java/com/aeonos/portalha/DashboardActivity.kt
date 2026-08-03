package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class DashboardActivity : AppCompatActivity() {

    companion object {
        @Volatile private var instance: DashboardActivity? = null

        const val ACTION_CLEAR_WEB_CACHE = "com.aeonos.portalha.DEBUG_CLEAR_WEB_CACHE"

        // Per-process latch for the launch-time cache clear in loadDashboard().
        @Volatile private var webCacheCleared = false

        // Snapshot the live dashboard as a bitmap, so the wake handoff can freeze it
        // on-screen (an overlay) while the assistant is invisibly brought forward to
        // grab the mic — the switch to the assistant is never seen. Uses PixelCopy:
        // it reads back the composited GPU frame, so the copy is pixel-identical to
        // the screen. (View.draw() software rendering distorted CSS-transformed
        // WebView elements — e.g. the Immich kiosk clock came out squashed.)
        // Async; calls back on the main thread with null if capture isn't possible.
        fun snapshot(cb: (android.graphics.Bitmap?) -> Unit) {
            val act = instance
            if (act == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                cb(null); return
            }
            runCatching {
                val v = act.findViewById<View>(android.R.id.content)
                if (v == null || v.width <= 0 || v.height <= 0) { cb(null); return }
                val bmp = android.graphics.Bitmap.createBitmap(
                    v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(act.window, bmp, { result ->
                    cb(if (result == android.view.PixelCopy.SUCCESS) bmp else null)
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }.onFailure { cb(null) }
        }
    }

    private lateinit var webView: WebView
    private lateinit var drawer: DrawerLayout
    private lateinit var prefs: Prefs

    // Intercom drawer controls. peerIds is kept aligned with the spinner rows;
    // index 0 is "Everyone" (broadcast → null target), the rest are peer ids.
    private lateinit var spinnerTarget: Spinner
    private lateinit var tvIntercomStatus: TextView
    private lateinit var btnAnnounce: Button
    private var peerIds: List<String?> = listOf(null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        prefs = Prefs(this)
        BridgeService.start(this)

        // Hold the screen awake while the dashboard is up. Portal's display
        // timeout is what starts the idle cascade (screen off + launcher
        // asserting HOME over us). HA's Screen switch can still sleep it —
        // this only blocks the timeout path, like a playing video does.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersive()   // kiosk: hide the system nav/status bars

        drawer = findViewById(R.id.drawer_layout)
        webView = findViewById(R.id.web_view)

        // Kiosk: never draw scrollbars — they flash down the right edge whenever the
        // WebView is re-laid-out (e.g. returning from the assistant handoff).
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Speak HA's frontend "external app" protocol so the dashboard treats us as a native
        // wrapper (native settings entry + working voice button + no-logout auth). CAUTION: once
        // window.externalApp exists, the frontend routes AUTH through us (getExternalAuth) — so we
        // only inject the bridge when a long-lived token is configured to answer it. Without a token
        // there's nothing to authenticate with, and the dashboard would hang on the loading screen.
        if (prefs.haToken.isNotBlank())
            webView.addJavascriptInterface(HaExternalBridge(this, webView, prefs), "externalApp")

        // Diagnostics for the dashboard page. The devtools socket is a local abstract
        // socket reachable only over adb (never the network), so this is safe to leave on
        // and it's the only way to inspect what the page — or an iframe inside it — is
        // doing on a device with no visible browser UI.
        //   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant media permissions so HA calls work inside the WebView
                request.grant(request.resources)
            }
            // WebView drops console output on the floor by default, which meant a page
            // (or iframe) failing inside the kiosk was completely invisible in logcat.
            // Forward it — errors from cross-origin iframes surface here too.
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i("PortalHA", "webview console [${m.messageLevel()}] " +
                    "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }

        // A third-party page embedded in the kiosk can wedge permanently on a stale cache:
        // if its cached shell predates a server-side update, a SPA that reloads itself on a
        // version mismatch (ImmichFrame does exactly this) strobes forever, and nothing in
        // the page can recover it — a "fresh" cached entry is re-served on every reload
        // instead of being revalidated. There is no browser UI on this device, so this is
        // the only way to evict it.
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_CLEAR_WEB_CACHE
        val cacheFilter = android.content.IntentFilter(ACTION_CLEAR_WEB_CACHE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            registerReceiver(clearCacheReceiver, cacheFilter, android.content.Context.RECEIVER_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(clearCacheReceiver, cacheFilter)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(alwaysVisibleJs(), null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(alwaysVisibleJs(), null)
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed() // Accept self-signed certs for local HA
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showPlaceholder(
                    "Failed to load.<br><br>Swipe in from the <b>left edge</b> to open the menu, " +
                    "then tap <b>Settings</b> to check your Home Assistant URL.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The WebView renderer died (usually OOM on a long-running
                // dashboard). Rebuild the activity instead of crashing the app.
                android.util.Log.w("PortalHA", "WebView renderer gone (crash=${detail.didCrash()}) — recreating dashboard")
                recreate()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                // intent:// and other app schemes — WebView drops these silently,
                // so hand them to Android (lets HA cards launch Portal apps).
                runCatching {
                    val intent =
                        if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        else Intent(Intent.ACTION_VIEW, request.url)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure {
                    android.util.Log.w("PortalHA", "Could not launch $url: ${it.message}")
                }
                return true
            }
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            drawer.closeDrawers()
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            drawer.closeDrawers()
            loadDashboard()
        }

        setupIntercom()

        instance = this
        loadDashboard()

        // First run (nothing configured yet): drop straight into Settings rather
        // than showing the empty dashboard placeholder. Only on a genuine fresh
        // create — savedInstanceState guards against config-change recreation,
        // and onCreate (not onResume) means backing out of Settings won't loop.
        if (savedInstanceState == null && prefs.haUrl.isBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    // Hide the status/navigation bars for a full-screen kiosk view. STICKY so a
    // swipe only reveals them briefly, then they auto-hide. (Deprecated flags, but
    // these are the working API on the Portal's Android 9/10.)
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

    // Immersive-sticky drops after focus changes (dialogs, the drawer, app
    // switches) — re-assert it whenever we regain focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    override fun onPause() {
        super.onPause()
        // Hide the floating talk buttons when the dashboard isn't in front.
        BridgeService.setDashboardForeground(false)
    }

    override fun onResume() {
        super.onResume()
        instance = this
        enableImmersive()
        // Floating talk buttons are shown only while the dashboard is in front.
        BridgeService.setDashboardForeground(true)
        // Re-acquire the camera if another app (e.g. the Portal launcher) took
        // it while we were in the background.
        BridgeService.ensureCamera(this)
        // Reload if URL changed in settings
        val url = prefs.haUrl
        val current = webView.url ?: ""
        if (url.isNotEmpty() && !current.startsWith(normalise(url).trimEnd('/'))) {
            loadDashboard()
        }
    }

    // ── Intercom (push-to-announce) ───────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupIntercom() {
        spinnerTarget = findViewById(R.id.spinner_target)
        tvIntercomStatus = findViewById(R.id.tv_intercom_status)
        btnAnnounce = findViewById(R.id.btn_announce)
        val btn = btnAnnounce

        refreshIntercom()

        // Hold to talk: press streams the mic, release stops.
        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val target = peerIds.getOrNull(spinnerTarget.selectedItemPosition)
                    if (BridgeService.intercomStartTalk(target)) {
                        (v as Button).text = "● Broadcasting…"
                        v.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
                    } else {
                        val busy = BridgeService.intercomBusyName()
                        Toast.makeText(this,
                            busy?.let { "Busy — $it is speaking" } ?: "Can't announce (mic unavailable)",
                            Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    BridgeService.intercomStopTalk()
                    (v as Button).text = "Hold to Announce"
                    v.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF3949AB.toInt())
                    true
                }
                else -> false
            }
        }

        // Refresh the online-Portal list each time the drawer is opened.
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { refreshIntercom() }
        })
    }

    private fun refreshIntercom() {
        val canTx = BridgeService.intercomCanTransmit()
        val peers = BridgeService.intercomPeers()
        val labels = ArrayList<String>().apply {
            add("Everyone"); peers.forEach { add(it.name) }
        }
        peerIds = ArrayList<String?>().apply { add(null); peers.forEach { add(it.id) } }

        val prev = spinnerTarget.selectedItemPosition
        spinnerTarget.adapter = ArrayAdapter(this, R.layout.spinner_item_light, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (prev in labels.indices) spinnerTarget.setSelection(prev)

        // Receive-only Portals (system holds the mic) can't send — disable the
        // controls and explain, but still show who's online (they can hear).
        btnAnnounce.isEnabled = canTx
        btnAnnounce.alpha = if (canTx) 1f else 0.5f
        btnAnnounce.text = if (canTx) "Hold to Announce" else "Receive-only"
        spinnerTarget.isEnabled = canTx
        spinnerTarget.alpha = if (canTx) 1f else 0.5f

        val busy = BridgeService.intercomBusyName()
        tvIntercomStatus.text = when {
            !canTx -> "Receive-only on this Portal — the microphone is reserved by the system. " +
                "You'll still hear announcements from other Portals."
            busy != null -> "$busy is speaking…"
            peers.isEmpty() -> "No other Portals online yet."
            else -> "${peers.size} Portal${if (peers.size == 1) "" else "s"} online."
        }
    }

    private fun loadDashboard() {
        val url = prefs.haUrl.trim()
        if (url.isEmpty()) {
            showPlaceholder(
                "Swipe in from the <b>left edge</b> to open the menu, " +
                "then tap <b>Settings</b> to enter your Home Assistant URL.")
        } else {
            // Start each app launch on a clean HTTP cache. An embedded third-party page can
            // wedge permanently on a stale cached shell — ImmichFrame reload-loops when its
            // cached bundle disagrees with a current _app/version.json, and because it sends
            // no Cache-Control the stale copy is re-served rather than revalidated, so the
            // loop never ends by itself. Clearing here makes a Portal reboot the cure for
            // the whole class of problem, instead of needing adb.
            // Once per process, not per activity: recreate() (renderer death) and settings
            // changes both re-enter this, and re-clearing there would just add latency.
            // Cost is one re-download of the HA frontend per launch, over the LAN, and this
            // app is long-running — launches are rare.
            if (!webCacheCleared) {
                webCacheCleared = true
                android.util.Log.i("PortalHA", "webview: cleared HTTP cache for a fresh start")
                webView.clearCache(true)
            }
            webView.loadUrl(normalise(url))
        }
    }

    private fun normalise(url: String) = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "http://$url"
    }

    /**
     * Kiosk lie: make the page believe it is ALWAYS visible. When the assistant
     * takes the foreground during a wake handoff, Android tells the page it's
     * hidden and HA's frontend tears down camera streams (then visibly reloads
     * them on return). Spoofing document.hidden/visibilityState and swallowing
     * the visibility events (capture phase, registered before HA's bundle loads)
     * keeps the streams connected across the handoff — no reload.
     */
    private fun alwaysVisibleJs(): String = """
        (function () {
          if (window.__phaAlwaysVisible) return; window.__phaAlwaysVisible = true;
          try {
            Object.defineProperty(Document.prototype, 'hidden',
              { get: function () { return false; }, configurable: true });
            Object.defineProperty(Document.prototype, 'visibilityState',
              { get: function () { return 'visible'; }, configurable: true });
          } catch (e) {}
          ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze']
            .forEach(function (t) {
              var swallow = function (e) { e.stopImmediatePropagation(); };
              window.addEventListener(t, swallow, true);
              document.addEventListener(t, swallow, true);
            });
        })();
    """.trimIndent()

    private fun showPlaceholder(message: String) {
        // Must use loadDataWithBaseURL, not loadData: loadData treats the payload
        // like a URL and chokes on the '#' in hex colors, rendering a blank page.
        webView.loadDataWithBaseURL(
            null,
            """<html><body style="background:#1c1c1c;color:#ccc;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;
               height:100vh;margin:0;text-align:center;padding:40px;box-sizing:border-box;">
               <div><h2 style="color:#fff">Portal HA Bridge</h2><p>$message</p></div>
               </body></html>""",
            "text/html", "UTF-8", null
        )
    }

    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    private val clearCacheReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            android.util.Log.i("PortalHA", "webview: clearing HTTP cache + reloading")
            webView.clearCache(true)
            webView.reload()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(clearCacheReceiver) }
        if (instance === this) instance = null
        super.onDestroy()
    }
}
