package com.aeonos.portalha

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

// The settings hub: what this Portal is and how it reaches Home Assistant, plus the
// way in to each settings section. Permissions, updates and the sleep/wake tests
// moved to System & Updates; tap sensitivity moved to Sensors — they were crowding
// out the settings people actually come here for.
class MainActivity : AppCompatActivity() {

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

        // ── Settings sections ────────────────────────────────────────────────
        findViewById<Button>(R.id.btn_camera_settings).setOnClickListener {
            startActivity(Intent(this, CameraSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_display_settings).setOnClickListener {
            startActivity(Intent(this, DisplaySettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_voice_settings).setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_intercom_settings).setOnClickListener {
            startActivity(Intent(this, IntercomSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_sensor_settings).setOnClickListener {
            startActivity(Intent(this, SensorSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_system_settings).setOnClickListener {
            startActivity(Intent(this, SystemSettingsActivity::class.java))
        }
        // Only appears when something is actually missing — takes you straight to
        // the screen that can fix it.
        findViewById<Button>(R.id.btn_fix_permissions).setOnClickListener {
            startActivity(Intent(this, SystemSettingsActivity::class.java))
        }

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
    }

    override fun onResume() {
        super.onResume()
        ScreenControl.enableAccessibility(this)
        BridgeService.start(this)
        BridgeService.ensureCamera(this)
        updateStatus()
        // Safety net: if a self-update killed us before the install receiver could
        // restore high-contrast text, undo it now (no-op when nothing is pending).
        Updater.restoreInstallerContrast(this)
        maybeShowAlexaProvisionNotice()
    }

    // One-time heads-up after landing on an Alexa-capable build: the OTA update delivers
    // the Alexa features, but Amazon's Alexa client itself can only be provisioned over
    // USB (permissions to another package aren't app-grantable). Shown once, and only on
    // Portals that don't already have it.
    private fun maybeShowAlexaProvisionNotice() {
        if (prefs.alexaProvisionNoticeShown) return
        prefs.alexaProvisionNoticeShown = true
        val falconInstalled = runCatching {
            packageManager.getPackageInfo("com.amazon.alexa.multimodal.falcon", 0)
        }.isSuccess
        if (falconInstalled) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New: Alexa on your Portal")
            .setMessage("This version can put real Amazon Alexa on this Portal — wake word, " +
                "stories, music, “alexa stop”.\n\n" +
                "It needs a ONE-TIME setup over USB (app updates can't do it): connect a " +
                "cable to a computer and run\n\n" +
                "    provision.ps1 -Alexa    (Windows)\n" +
                "    ./provision.sh --alexa  (macOS/Linux)\n\n" +
                "then enter the code shown at amazon.com/code, and enable Alexa support in " +
                "Settings → Voice & Assistants. Full steps: README, “Alexa on your " +
                "Portal”. Until then the Alexa features stay off — everything else works " +
                "as before.")
            .setPositiveButton("OK", null)
            .show()
    }

    // Identity + reachability at a glance. The permission checklist itself lives in
    // System & Updates; here we only raise a flag when something needs attention.
    private fun updateStatus() {
        findViewById<TextView>(R.id.tv_status).text = buildString {
            appendLine("Version:   ${BuildConfig.VERSION_NAME}")
            appendLine("Device ID: ${prefs.deviceId}")
            appendLine("IP:        ${BridgeService.localIp() ?: "(no network)"}")
            append("Broker:    ${prefs.brokerUri}")
        }

        val ready = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            Settings.System.canWrite(this) &&
            Settings.canDrawOverlays(this) &&
            ScreenControl.isAccessibilityEnabled(this)
        findViewById<Button>(R.id.btn_fix_permissions).visibility = if (ready) View.GONE else View.VISIBLE
    }
}
