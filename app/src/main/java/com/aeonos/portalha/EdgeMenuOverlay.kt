package com.aeonos.portalha

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout

/**
 * The dashboard's menu, opened ON TOP of whatever app is in front by the left-edge swipe
 * (EdgeSwipeOverlay) — the app underneath stays exactly where it was. From here:
 *  - tap the dimmed backdrop → menu closes, you're back in that app;
 *  - Back to HA Bridge      → the dashboard;
 *  - Home / Calls / a tile  → that, as from the dashboard's own drawer (shared AppTiles).
 *
 * An overlay window rather than an Activity, so opening it never moves the app behind out of
 * the foreground. No Edit button: the picker is a dialog and needs the dashboard.
 */
class EdgeMenuOverlay(
    private val context: Context,
    private val onDashboard: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)
    @Volatile private var root: View? = null

    val isShowing: Boolean get() = root != null

    fun show() = main.post {
        if (root != null || !Settings.canDrawOverlays(context)) return@post
        runCatching {
            // Themed, so the buttons render as in the app (the service context has no theme).
            val themed = ContextThemeWrapper(context, R.style.Theme_PortalHA)
            val v = LayoutInflater.from(themed).inflate(R.layout.overlay_edge_menu, null)
            v.findViewById<View>(R.id.menu_scrim).setOnClickListener { hide() }
            v.findViewById<View>(R.id.btn_menu_dashboard).setOnClickListener { hide(); onDashboard() }
            v.findViewById<View>(R.id.btn_menu_home).setOnClickListener {
                hide(); HomeScreen.goHome(context)
            }
            AppTiles.fill(v.findViewById<GridLayout>(R.id.grid_menu_apps),
                beforeLaunch = { hide() }, onAdd = null)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, lp)
            root = v
            // Slide in from the left, like the drawer it stands in for.
            val panel = v.findViewById<View>(R.id.menu_panel)
            val scrim = v.findViewById<View>(R.id.menu_scrim)
            panel.translationX = -260 * context.resources.displayMetrics.density
            scrim.alpha = 0f
            panel.animate().translationX(0f).setDuration(200).start()
            scrim.animate().alpha(1f).setDuration(200).start()
        }
    }

    fun hide() = main.post {
        val v = root ?: return@post
        root = null
        runCatching { wm.removeView(v) }
        onClosed()
    }
}
