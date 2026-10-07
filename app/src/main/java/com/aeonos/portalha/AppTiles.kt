package com.aeonos.portalha

import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The menu's app tiles — Calls first (unless hidden), then the pinned apps, each with its real
 * icon and name, two to a row. Shared by the dashboard's drawer and the over-other-apps menu
 * (EdgeMenuOverlay) so the two always look and behave the same.
 */
object AppTiles {

    /**
     * Rebuild [grid]. [beforeLaunch] runs just before an app opens (close the menu). [onAdd], if
     * given, puts a "+ Add apps" tile in an otherwise empty grid; the overlay passes null, since
     * the picker is a dialog and needs the dashboard.
     */
    fun fill(grid: GridLayout, beforeLaunch: () -> Unit, onAdd: (() -> Unit)?) {
        val ctx = grid.context
        grid.removeAllViews()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        // 260dp menu - 2x28dp padding = 204dp, so two 102dp tiles fill a row exactly.
        fun tile(label: String, icon: Drawable?, tint: Int?, onTap: () -> Unit) =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = GridLayout.LayoutParams().apply { width = dp(102) }
                val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0); ta.recycle()
                addView(ImageView(ctx).apply {
                    if (icon != null) setImageDrawable(icon)
                    else setImageResource(android.R.drawable.ic_input_add)
                    if (icon == null || tint != null) setColorFilter(tint ?: 0xFF_AAB0FF.toInt())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(68), dp(68)))
                addView(TextView(ctx).apply {
                    text = label
                    setTextColor(0xFF_DDDDEE.toInt())
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { onTap() }
            }

        // Calls leads (unless unticked in Edit), whenever Portal calling exists on this device.
        val showCalls = HomeScreen.hasCalls(ctx) && Prefs(ctx).showCallsTile
        if (showCalls) {
            grid.addView(tile("Calls", ctx.getDrawable(android.R.drawable.sym_action_call),
                0xFF_4CAF50.toInt()) { beforeLaunch(); HomeScreen.openCalls(ctx) })
        }

        val apps = HomeScreen.shortcuts(ctx)
        if (apps.isEmpty()) {
            // Only the "+" when the grid would otherwise be empty; with Calls showing, Edit is
            // right there in the header.
            if (!showCalls && onAdd != null) grid.addView(tile("Add apps", null, null, onAdd))
            return
        }
        val pm = ctx.packageManager
        for (app in apps) {
            grid.addView(tile(app.label,
                runCatching { app.icon(pm) }.getOrElse { pm.defaultActivityIcon }, null) {
                beforeLaunch(); HomeScreen.open(ctx, app)
            })
        }
    }
}
