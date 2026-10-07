package com.aeonos.portalha

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

// Optional "use the dashboard as the Portal's home screen", for Portals with no other usable
// launcher (e.g. the stock one locked behind a Facebook/WhatsApp login, and Immortal not wanted),
// plus the drawer's Home button and Apps shortcuts. The HOME entry point is the
// disabled-by-default HomeLauncher alias in the manifest; its enabled state IS the setting, so
// there's no pref to drift out of sync with the system.
object HomeScreen {

    private fun alias(ctx: Context) = ComponentName(ctx, "${ctx.packageName}.HomeLauncher")

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun isEnabled(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(alias(ctx)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** True when pressing Home actually lands on us (we're the resolved default). */
    fun isDefault(ctx: Context): Boolean {
        val ri = ctx.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(alias(ctx),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    /**
     * Ask Android to make us the default. Enabling a new HOME app invalidates any saved choice
     * (a saved preference only holds while the set of home apps is unchanged), so firing the
     * HOME intent brings up the system chooser; the user picks Portal HA Bridge → Always.
     */
    fun requestDefault(activity: Activity) {
        runCatching { activity.startActivity(homeIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The drawer's Home button: whatever launcher is set as home. */
    fun goHome(ctx: Context) = launch(ctx, homeIntent(), "the home screen")

    // ★Every launch from our menus goes through here. Taps on a dialog (or a drawer button
    // whose departure lands a beat later) don't reliably count as the dashboard being touched,
    // so without this the steal watchdog read "you opened Reolink" as "Reolink pushed in" and
    // pulled the dashboard back over it 1.8 s later — measured.
    private fun launch(ctx: Context, intent: Intent, label: String) {
        BridgeService.noteUserInput()
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, "Couldn't open $label", Toast.LENGTH_SHORT).show() }
    }

    class App(val label: String, val component: String, private val ri: android.content.pm.ResolveInfo) {
        /** Loaded on demand — only the handful of pinned apps ever need one. */
        fun icon(pm: PackageManager): android.graphics.drawable.Drawable = ri.loadIcon(pm)
    }

    // Meta's stock launcher is the only way into Portal calling (Contacts/favourites). It gets
    // its own fixed Calls tile, so it's kept out of the pickable list to avoid a duplicate.
    private const val META_LAUNCHER_PKG = "com.facebook.alohaapps.launcher"

    /** Whether the Calls tile can work: the stock launcher is installed and enabled. */
    fun hasCalls(ctx: Context): Boolean =
        ctx.packageManager.getLaunchIntentForPackage(META_LAUNCHER_PKG) != null

    /**
     * The Calls tile — the same route as Home Assistant's Calls button. Opening the stock
     * launcher is all it takes: BridgeService sees it come to the front, taps past its photo
     * screen, and returns to the dashboard once the calling screen sits idle.
     */
    fun openCalls(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(META_LAUNCHER_PKG) ?: run {
            Toast.makeText(ctx, "Portal calling isn't available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        launch(ctx, intent, "Calls")
    }

    private fun allApps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName &&
                it.activityInfo.packageName != META_LAUNCHER_PKG }
            .map { App(it.loadLabel(pm).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}", it) }
            .sortedBy { it.label.lowercase() }
    }

    /** The user's pinned apps, alphabetical. Apps uninstalled since are silently dropped. */
    fun shortcuts(ctx: Context): List<App> {
        val pinned = Prefs(ctx).appShortcuts
        if (pinned.isEmpty()) return emptyList()
        return allApps(ctx).filter { it.component in pinned }
    }

    fun open(ctx: Context, app: App) {
        val cn = ComponentName.unflattenFromString(app.component) ?: return
        launch(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), app.label)
    }

    /** Tick the apps (and the Calls tile) to pin in the drawer; [onSaved] redraws the tiles. */
    fun editShortcuts(activity: Activity, onSaved: () -> Unit) {
        val prefs = Prefs(activity)
        val apps = allApps(activity)
        // Calls leads the list as its own row when Portal calling exists; app rows follow.
        val calls = if (hasCalls(activity)) 1 else 0
        if (apps.isEmpty() && calls == 0) {
            Toast.makeText(activity, "No other apps found", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = (if (calls == 1) listOf("Calls (Portal calling)") else emptyList()) + apps.map { it.label }
        val picked = BooleanArray(labels.size) { i ->
            if (i < calls) prefs.showCallsTile else apps[i - calls].component in prefs.appShortcuts
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Choose apps for the menu")
            .setMultiChoiceItems(labels.toTypedArray(), picked) { _, i, on -> picked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                if (calls == 1) prefs.showCallsTile = picked[0]
                prefs.appShortcuts = apps.filterIndexed { i, _ -> picked[i + calls] }.map { it.component }.toSet()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
