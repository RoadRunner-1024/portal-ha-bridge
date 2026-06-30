package com.aeonos.portalha

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private val RUNTIME_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        val etHaUrl = findViewById<EditText>(R.id.et_ha_url)
        etHaUrl.setText(prefs.haUrl)

        val etHaToken = findViewById<EditText>(R.id.et_ha_token)
        etHaToken.setText(prefs.haToken)

        // Back to the dashboard (MainActivity is always opened from it)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        // Guided permission setup — each tap resolves the next missing item,
        // so no adb is needed (except the optional WRITE_SECURE_SETTINGS).
        findViewById<Button>(R.id.btn_grant).setOnClickListener { grantNextMissing() }

        findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkForUpdate(it as Button) }

        val etHost = findViewById<EditText>(R.id.et_host)
        val etPort = findViewById<EditText>(R.id.et_port)
        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val etName = findViewById<EditText>(R.id.et_name)

        etHost.setText(prefs.brokerHost)
        etPort.setText(prefs.brokerPort.toString())
        etUser.setText(prefs.username)
        etPass.setText(prefs.password)
        etName.setText(prefs.deviceName)

        // Sensitivity slider: 0–26 → 2.0–15.0 m/s² in 0.5 steps
        val tvSensitivity = findViewById<TextView>(R.id.tv_sensitivity_value)
        val seekSensitivity = findViewById<SeekBar>(R.id.seek_sensitivity)

        fun thresholdToProgress(t: Float) = ((t - 2.0f) / 0.5f).toInt().coerceIn(0, 26)
        fun progressToThreshold(p: Int) = 2.0f + p * 0.5f
        fun updateSensitivityLabel(progress: Int) {
            tvSensitivity.text = "%.1f m/s²  (lower = more sensitive)".format(progressToThreshold(progress))
        }

        seekSensitivity.max = 26
        seekSensitivity.progress = thresholdToProgress(prefs.tapThreshold)
        updateSensitivityLabel(seekSensitivity.progress)
        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateSensitivityLabel(progress)
                if (fromUser) prefs.tapThreshold = progressToThreshold(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        // Camera settings live on their own page
        findViewById<Button>(R.id.btn_camera_settings).setOnClickListener {
            startActivity(Intent(this, CameraSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_display_settings).setOnClickListener {
            startActivity(Intent(this, DisplaySettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_intercom_settings).setOnClickListener {
            startActivity(Intent(this, IntercomSettingsActivity::class.java))
        }

        // Other buttons
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            prefs.brokerHost = etHost.text.toString().trim().ifEmpty { "homeassistant.local" }
            prefs.brokerPort = etPort.text.toString().toIntOrNull() ?: 1883
            prefs.username = etUser.text.toString().trim()
            prefs.password = etPass.text.toString()
            prefs.deviceName = etName.text.toString().trim().ifEmpty { "Portal" }
            prefs.haUrl = etHaUrl.text.toString().trim()
            prefs.haToken = etHaToken.text.toString().trim()
            BridgeService.stop(this)
            BridgeService.start(this)
            updateStatus()
            Toast.makeText(this, "Saved — service restarting", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_sleep).setOnClickListener { ScreenControl.sleep() }
        findViewById<Button>(R.id.btn_wake).setOnClickListener { ScreenControl.wake(this) }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            if (!ScreenControl.enableAccessibility(this)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                Toast.makeText(this, "Accessibility service enabled", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }
    }

    // ── In-app updater ────────────────────────────────────────────────────────

    private fun checkForUpdate(btn: Button) {
        val orig = btn.text
        btn.isEnabled = false; btn.text = "Checking…"
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            runOnUiThread {
                btn.isEnabled = true; btn.text = orig
                result.onSuccess { rel ->
                    if (Updater.isNewer(rel.version, BuildConfig.VERSION_NAME)) promptUpdate(btn, rel)
                    else Toast.makeText(this,
                        "You're on the latest version (v${BuildConfig.VERSION_NAME}).", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Couldn't check for updates: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun promptUpdate(btn: Button, rel: Updater.Release) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("A newer version is available:\n\nv${BuildConfig.VERSION_NAME}  →  v${rel.version}\n\n" +
                "Download and install it now? Your settings are kept.")
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(btn, rel) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(btn: Button, rel: Updater.Release) {
        if (!Updater.canInstall(this)) { showInstallPermDialog(); return }
        val dest = java.io.File(cacheDir, "update.apk")
        btn.isEnabled = false; btn.text = "Downloading… 0%"
        Thread {
            val r = runCatching {
                Updater.downloadApk(rel.apkUrl, dest) { pct -> runOnUiThread { btn.text = "Downloading… $pct%" } }
            }
            runOnUiThread {
                btn.isEnabled = true; btn.text = "Check for Updates"
                r.onSuccess {
                    runCatching { Updater.install(this, dest) }.onFailure {
                        Toast.makeText(this, "Install failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }.onFailure {
                    Toast.makeText(this, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    // "Install unknown apps" — flaky to toggle on Portal, so offer the settings
    // page and the exact adb fallback (the provisioner grants this automatically).
    private fun showInstallPermDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Allow installing updates")
            .setMessage("To install updates in-app, this app needs the \"install unknown apps\" " +
                "permission.\n\nTry Open settings below. If the toggle doesn't take on your Portal, run " +
                "this on a computer with the Portal connected, then try again:\n\n" +
                "adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow\n\n" +
                "(The provisioner does this for you.)")
            .setPositiveButton("Open settings") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Toast.makeText(this, "Settings page unavailable — use the adb command.", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        ScreenControl.enableAccessibility(this)
        BridgeService.start(this)
        BridgeService.ensureCamera(this)
        updateStatus()
    }


    private fun updateStatus() {
        val accessible = ScreenControl.isAccessibilityEnabled(this)
        val hasRecord = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val hasCamera = checkSelfPermission(android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val hasWriteSettings = Settings.System.canWrite(this)
        val hasOverlay = Settings.canDrawOverlays(this)

        findViewById<TextView>(R.id.tv_status).text = buildString {
            appendLine("Version:   ${BuildConfig.VERSION_NAME}")
            appendLine("Device ID: ${prefs.deviceId}")
            appendLine("IP:        ${BridgeService.localIp() ?: "(no network)"}")
            appendLine("Broker:    ${prefs.brokerUri}")
            appendLine()
            appendLine("CAMERA:                ${if (hasCamera) "✓" else "✗  tap Grant below"}")
            appendLine("RECORD_AUDIO (sound):  ${if (hasRecord) "✓" else "✗  tap Grant below"}")
            appendLine("WRITE_SETTINGS (brightness): ${if (hasWriteSettings) "✓" else "✗  tap Grant below"}")
            appendLine("SYSTEM_ALERT_WINDOW:   ${if (hasOverlay) "✓" else "✗  tap Grant below"}")
            appendLine("Accessibility (sleep): ${if (accessible) "✓" else "✗  tap Grant below (needs adb)"}")
        }

        findViewById<Button>(R.id.btn_grant).visibility =
            if (hasCamera && hasRecord && hasWriteSettings && hasOverlay && accessible) View.GONE
            else View.VISIBLE
    }

    // Walks through whatever is still missing, one step per tap: runtime
    // permission dialogs first, then the special-access settings screens.
    private fun grantNextMissing() {
        val missingRuntime = RUNTIME_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        when {
            missingRuntime.isNotEmpty() ->
                requestPermissions(missingRuntime.toTypedArray(), 1)

            !Settings.System.canWrite(this) -> openSpecialAccess(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")),
                "Modify system settings", "lets the brightness slider work")

            !Settings.canDrawOverlays(this) -> openSpecialAccess(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                "Display over other apps", "keeps the camera available in the background")

            !ScreenControl.isAccessibilityEnabled(this) -> {
                when {
                    // Best case: WRITE_SECURE_SETTINGS granted → enable silently.
                    ScreenControl.enableAccessibility(this) ->
                        Toast.makeText(this, "Accessibility service enabled", Toast.LENGTH_SHORT).show()
                    // Portal hides third-party accessibility services from its
                    // Settings UI, so the only route is the one adb command.
                    isPortal() -> showAccessibilityAdbDialog()
                    // Normal Android: the Settings toggle works.
                    else -> openAccessibility()
                }
            }

            else -> Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    private fun isPortal() =
        android.os.Build.MANUFACTURER.equals("Facebook", ignoreCase = true) ||
        android.os.Build.MODEL.contains("Portal", ignoreCase = true)

    // Sleep is the one feature Portal can't grant from its UI (Meta removed the
    // accessibility-service list). Show the exact adb command to type on the
    // computer that's connected to the Portal (the only place it can run).
    private fun showAccessibilityAdbDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Each argument on its own line so the space separating the package from
        // the permission can't disappear into a line wrap. Three lines = three
        // space-separated parts of one command.
        val code = TextView(this).apply {
            text = "adb shell pm grant\n$packageName\nandroid.permission.WRITE_SECURE_SETTINGS"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(0xFF_E0E0E0.toInt())
            setBackgroundColor(0xFF_101010.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        val message = TextView(this).apply {
            text = "Meta hides accessibility services on Portal, so screen sleep can't be " +
                "enabled on the device itself.\n\nOn the computer you used to install the app " +
                "(with the Portal connected by USB), run this one command, then reopen the app. " +
                "It's a single line — the three parts below are separated by spaces:"
            textSize = 14f
            setTextColor(0xFF_CCCCCC.toInt())
        }
        val footer = TextView(this).apply {
            text = "Everything else — camera, motion, streaming, sound, brightness, volume — " +
                "works without this step."
            textSize = 13f
            setTextColor(0xFF_999999.toInt())
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(message)
            addView(code, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(14) })
            addView(footer)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Screen sleep needs one adb command")
            .setView(layout)
            .setPositiveButton("Got it", null)
            .show()
    }

    // Portal's curated Accessibility list hides third-party services, so the
    // plain ACTION_ACCESSIBILITY_SETTINGS is a dead end. Try the per-service
    // detail page (API 29+) which deep-links straight to our toggle, bypassing
    // the list; fall back to the list screen if that isn't available either.
    private fun openAccessibility() {
        // These framework constants are @hide, so use their literal values.
        val component = "$packageName/${ScreenAccessibility::class.java.name}"
        val args = android.os.Bundle().apply {
            putString("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME", component)
        }
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME", component)
            putExtra(":settings:show_fragment_args", args)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (detail.resolveActivity(packageManager) != null) {
            Toast.makeText(this, "Turn on 'Portal HA Bridge' for screen sleep", Toast.LENGTH_LONG).show()
            runCatching { startActivity(detail) }.onFailure { openAccessibilityList() }
        } else {
            openAccessibilityList()
        }
    }

    // Non-Portal fallback: the standard accessibility list (has its own back button).
    private fun openAccessibilityList() {
        Toast.makeText(this, "Enable 'Portal HA Bridge' for screen sleep", Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    // Portal's system settings pages have no back button, so warn the user what
    // to expect before launching them: toggle ON, then use the Back gesture.
    private fun openSpecialAccess(intent: Intent, toggleName: String, purpose: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enable '$toggleName'")
            .setMessage(
                "The next screen is a system settings page — it $purpose.\n\n" +
                "1. Turn ON '$toggleName'\n" +
                "2. Swipe up (or use the Back gesture) to return here\n\n" +
                "The page has no Back button of its own — that's normal on Portal.")
            .setPositiveButton("Open settings") { _, _ ->
                runCatching { startActivity(intent) }.onFailure {
                    Toast.makeText(this, "Settings screen unavailable on this device", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            // Restart so the camera/sound subsystems pick up the new permissions
            BridgeService.stop(this)
            BridgeService.start(this)
        }
    }
}
