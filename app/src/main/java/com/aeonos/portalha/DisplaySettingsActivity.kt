package com.aeonos.portalha

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DisplaySettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var swPresence: Switch
    private lateinit var swEnhancedPresence: Switch
    private lateinit var swCoexist: Switch
    private lateinit var swWake: Switch
    private lateinit var swVoiceAnnounce: Switch
    private lateinit var etWakePhrase: EditText
    private lateinit var swAlexa: Switch
    private lateinit var etAlexaPhrase: EditText
    private lateinit var seekPresenceSound: SeekBar
    private lateinit var tvPresenceSound: TextView
    private lateinit var swTimeout: Switch
    private lateinit var etMinutes: EditText
    private lateinit var tvPresenceStatus: TextView
    private lateinit var etTempOffset: EditText
    private lateinit var seekDashboardZoom: SeekBar
    private lateinit var tvDashboardZoom: TextView
    private var hasTempSensor = false

    // Live-sync the UI when the service changes prefs (HA commands).
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateUi() }

    // Live ambient-sound readout next to the threshold, for calibration.
    private var liveLevel = -1
    private val levelHandler = Handler(Looper.getMainLooper())
    private val levelPoll = object : Runnable {
        override fun run() {
            liveLevel = BridgeService.currentSoundLevel()
            tvPresenceSound.text = soundLabel(prefs.presenceSoundThreshold)
            levelHandler.postDelayed(this, 700)
        }
    }

    private fun soundLabel(threshold: Int) =
        "Sound threshold: $threshold" + if (liveLevel >= 0) "      (now: $liveLevel)" else ""

    private fun zoomLabel(percent: Int) = "Dashboard zoom: $percent%"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_display_settings)

        swPresence = findViewById(R.id.sw_presence)
        swEnhancedPresence = findViewById(R.id.sw_enhanced_presence)
        swCoexist = findViewById(R.id.sw_coexist)
        swWake = findViewById(R.id.sw_wake)
        swVoiceAnnounce = findViewById(R.id.sw_voice_announce)
        etWakePhrase = findViewById(R.id.et_wake_phrase)
        swAlexa = findViewById(R.id.sw_alexa)
        etAlexaPhrase = findViewById(R.id.et_alexa_phrase)
        seekPresenceSound = findViewById(R.id.seek_presence_sound)
        tvPresenceSound = findViewById(R.id.tv_presence_sound)
        swTimeout = findViewById(R.id.sw_screen_timeout)
        etMinutes = findViewById(R.id.et_timeout_minutes)
        tvPresenceStatus = findViewById(R.id.tv_presence_status)
        etTempOffset = findViewById(R.id.et_temp_offset)
        seekDashboardZoom = findViewById(R.id.seek_dashboard_zoom)
        tvDashboardZoom = findViewById(R.id.tv_dashboard_zoom)

        // The temperature section only makes sense on hardware that has the sensor.
        hasTempSensor = getSystemService(android.hardware.SensorManager::class.java)
            ?.getDefaultSensor(android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE) != null
        findViewById<View>(R.id.section_temp).visibility = if (hasTempSensor) View.VISIBLE else View.GONE
        etTempOffset.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveTempOffset() }

        findViewById<Button>(R.id.btn_back).setOnClickListener { saveMinutes(); saveTempOffset(); saveWakePhrase(); finish() }

        swPresence.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.presenceEnabled) return@setOnCheckedChangeListener
            prefs.presenceEnabled = checked
            // Presence needs READ_LOGS, which only adb can grant. If missing, tell
            // the user exactly how — same constraint as screen sleep.
            if (checked && !hasReadLogs()) showReadLogsDialog()
            BridgeService.applyDisplaySettings(this)
            updateUi()
        }

        swEnhancedPresence.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.enhancedPresenceEnabled) return@setOnCheckedChangeListener
            prefs.enhancedPresenceEnabled = checked
            BridgeService.applyDisplaySettings(this)
            updateUi()
        }

        swCoexist.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.coexistVoiceAssistant) return@setOnCheckedChangeListener
            prefs.coexistVoiceAssistant = checked
            if (checked && prefs.wakeWordEnabled) prefs.wakeWordEnabled = false   // mutually exclusive
            BridgeService.applyDisplaySettings(this)
            updateUi()
            Toast.makeText(this,
                if (checked) "Mic released for the voice assistant — sound sensor off"
                else "Mic reclaimed — sound sensor on",
                Toast.LENGTH_SHORT).show()
        }

        swWake.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.wakeWordEnabled) return@setOnCheckedChangeListener
            prefs.wakeWordEnabled = checked
            if (checked && prefs.coexistVoiceAssistant) prefs.coexistVoiceAssistant = false   // mutually exclusive
            BridgeService.applyDisplaySettings(this)
            updateUi()
            Toast.makeText(this,
                if (checked) "Wake word on — model downloads on first use, then say your phrase"
                else "Wake word off",
                Toast.LENGTH_SHORT).show()
        }
        etWakePhrase.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveWakePhrase() }

        swAlexa.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.alexaWakeEnabled) return@setOnCheckedChangeListener
            if (checked && !alexaProvisioned()) {
                // Enabling without falcon would arm a wake word that leads nowhere.
                showAlexaProvisionDialog()
                updateUi()   // snap the switch back off
                return@setOnCheckedChangeListener
            }
            prefs.alexaWakeEnabled = checked
            BridgeService.applyDisplaySettings(this)
            updateUi()
        }
        etAlexaPhrase.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveAlexaPhrase() }

        // Read at trigger time by the service — no live apply needed.
        swVoiceAnnounce.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.voiceAnnounceEnabled) { prefs.voiceAnnounceEnabled = checked; updateUi() }
        }

        seekPresenceSound.progress = prefs.presenceSoundThreshold
        tvPresenceSound.text = soundLabel(prefs.presenceSoundThreshold)
        seekPresenceSound.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                tvPresenceSound.text = soundLabel(p)
                if (fromUser) prefs.presenceSoundThreshold = p   // read live by the sound callback
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) { BridgeService.applyDisplaySettings(this@DisplaySettingsActivity) }
        })

        // Dashboard zoom: SeekBar carries the zoom as a percent (50–300%); the pref
        // stores it as a float (0.5–3.0). Applied by DashboardActivity on its next
        // onResume / page load — no service restart needed.
        seekDashboardZoom.progress = (prefs.dashboardZoom * 100).toInt()
        tvDashboardZoom.text = zoomLabel(seekDashboardZoom.progress)
        seekDashboardZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                tvDashboardZoom.text = zoomLabel(p)
                if (fromUser) prefs.dashboardZoom = p / 100f
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        swTimeout.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.screenTimeoutEnabled) return@setOnCheckedChangeListener
            prefs.screenTimeoutEnabled = checked
            BridgeService.applyDisplaySettings(this)
            updateUi()
            Toast.makeText(this,
                if (checked) "Screen will turn off when idle" else "Screen will stay on",
                Toast.LENGTH_SHORT).show()
        }

        etMinutes.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveMinutes() }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerListener(prefsListener)
        updateUi()
        levelHandler.post(levelPoll)            // live sound readout for calibration
    }

    override fun onPause() {
        super.onPause()
        saveMinutes()
        saveTempOffset()
        saveWakePhrase()
        prefs.unregisterListener(prefsListener)
        levelHandler.removeCallbacks(levelPoll)
    }

    private fun hasReadLogs() =
        checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    private fun saveMinutes() {
        val v = etMinutes.text.toString().toIntOrNull() ?: return
        val clamped = v.coerceIn(1, 240)
        if (clamped != prefs.screenTimeoutMinutes) {
            prefs.screenTimeoutMinutes = clamped
            BridgeService.applyDisplaySettings(this)
        }
    }

    private fun saveTempOffset() {
        if (!hasTempSensor) return
        val v = etTempOffset.text.toString().toFloatOrNull() ?: return
        val clamped = v.coerceIn(-20f, 20f)
        if (clamped != prefs.tempOffset) {
            prefs.tempOffset = clamped
            BridgeService.applyDisplaySettings(this)
        }
    }

    private fun saveWakePhrase() {
        val before = prefs.wakePhrase
        prefs.wakePhrase = etWakePhrase.text.toString()   // setter trims + enforces the "hey " prefix
        if (prefs.wakePhrase != before) BridgeService.applyDisplaySettings(this)
    }

    private fun saveAlexaPhrase() {
        val before = prefs.alexaWakePhrase
        prefs.alexaWakePhrase = etAlexaPhrase.text.toString()
        if (prefs.alexaWakePhrase != before && prefs.alexaWakeEnabled) BridgeService.applyDisplaySettings(this)
    }

    // Alexa support needs Amazon's Alexa client (falcon) on the device — installed and
    // permission-granted by the USB provisioner only (no app can grant permissions to
    // another package, so an over-the-air app update can never do this step).
    private fun alexaProvisioned(): Boolean = runCatching {
        packageManager.getPackageInfo("com.amazon.alexa.multimodal.falcon", 0)
    }.isSuccess

    private fun showAlexaProvisionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Alexa isn't provisioned yet")
            .setMessage("Alexa support needs Amazon's Alexa client on this Portal. App " +
                "updates can't install it — its permissions can only be granted over USB.\n\n" +
                "One-time setup: connect a USB cable to a computer and run\n\n" +
                "    provision.ps1 -Alexa    (Windows)\n" +
                "    ./provision.sh --alexa  (macOS/Linux)\n\n" +
                "then enter the code the Portal shows at amazon.com/code. Full steps are in " +
                "the README under “Alexa on your Portal”.\n\n" +
                "Once that's done, come back and turn this on.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateUi() {
        swPresence.isChecked = prefs.presenceEnabled
        swTimeout.isChecked = prefs.screenTimeoutEnabled
        if (etMinutes.text.toString() != prefs.screenTimeoutMinutes.toString())
            etMinutes.setText(prefs.screenTimeoutMinutes.toString())
        findViewById<View>(R.id.row_timeout_mins).alpha = if (prefs.screenTimeoutEnabled) 1f else 0.4f

        swCoexist.isChecked = prefs.coexistVoiceAssistant

        // Wake word & coexist are mutually exclusive (wake needs our mic; coexist gives
        // it to an external app). Grey out whichever the other one disables.
        swWake.isChecked = prefs.wakeWordEnabled
        if (!etWakePhrase.hasFocus() && etWakePhrase.text.toString() != prefs.wakePhrase)
            etWakePhrase.setText(prefs.wakePhrase)
        findViewById<View>(R.id.row_wake_phrase).alpha = if (prefs.wakeWordEnabled) 1f else 0.4f

        // Alexa support: a second, independent wake word. Its own phrase field greys when off.
        swAlexa.isChecked = prefs.alexaWakeEnabled
        if (!etAlexaPhrase.hasFocus() && etAlexaPhrase.text.toString() != prefs.alexaWakePhrase)
            etAlexaPhrase.setText(prefs.alexaWakePhrase)
        findViewById<View>(R.id.row_alexa_phrase).alpha = if (prefs.alexaWakeEnabled) 1f else 0.4f

        // Voice announce rides the wake detector — grey it out when wake is off.
        swVoiceAnnounce.isChecked = prefs.voiceAnnounceEnabled
        swVoiceAnnounce.isEnabled = prefs.wakeWordEnabled
        swVoiceAnnounce.alpha = if (prefs.wakeWordEnabled) 1f else 0.4f
        findViewById<View>(R.id.tv_voice_announce_note).alpha = if (prefs.wakeWordEnabled) 0.6f else 0.3f
        // Our wake words (Jarvis + Alexa) need the mic; coexist gives it away — mutually exclusive.
        val ourWake = prefs.wakeWordEnabled || prefs.alexaWakeEnabled
        swWake.isEnabled = !prefs.coexistVoiceAssistant
        swWake.alpha = if (prefs.coexistVoiceAssistant) 0.4f else 1f
        swAlexa.isEnabled = !prefs.coexistVoiceAssistant
        swAlexa.alpha = if (prefs.coexistVoiceAssistant) 0.4f else 1f
        swCoexist.isEnabled = !ourWake
        swCoexist.alpha = if (ourWake) 0.4f else 1f

        // Enhanced (sound) presence needs the mic — and only applies while presence
        // detection is on — so it's unavailable while coexisting with an assistant.
        val soundFeaturesAvailable = prefs.presenceEnabled && !prefs.coexistVoiceAssistant
        swEnhancedPresence.isChecked = prefs.enhancedPresenceEnabled
        swEnhancedPresence.isEnabled = soundFeaturesAvailable
        swEnhancedPresence.alpha = if (soundFeaturesAvailable) 1f else 0.4f
        if (seekPresenceSound.progress != prefs.presenceSoundThreshold)
            seekPresenceSound.progress = prefs.presenceSoundThreshold
        tvPresenceSound.text = soundLabel(prefs.presenceSoundThreshold)

        val zoomPct = (prefs.dashboardZoom * 100).toInt()
        if (seekDashboardZoom.progress != zoomPct) seekDashboardZoom.progress = zoomPct
        tvDashboardZoom.text = zoomLabel(zoomPct)
        findViewById<View>(R.id.row_presence_sound).alpha =
            if (soundFeaturesAvailable && prefs.enhancedPresenceEnabled) 1f else 0.4f

        if (hasTempSensor) {
            val s = "%.1f".format(prefs.tempOffset)
            if (etTempOffset.text.toString() != s) etTempOffset.setText(s)
        }

        tvPresenceStatus.text = when {
            !prefs.presenceEnabled -> "Presence detection off."
            hasReadLogs() -> "READ_LOGS granted ✓  — presence is being published to Home Assistant."
            else -> "⚠  READ_LOGS not granted — run this on a computer, then reopen:\n" +
                "adb shell pm grant\n$packageName\nandroid.permission.READ_LOGS"
        }
    }

    private fun showReadLogsDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val code = TextView(this).apply {
            text = "adb shell pm grant\n$packageName\nandroid.permission.READ_LOGS"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(0xFF_E0E0E0.toInt())
            setBackgroundColor(0xFF_101010.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        val message = TextView(this).apply {
            text = "Portal presence reads Meta's own person detection from the system log, " +
                "which needs the READ_LOGS permission. Android only allows granting it over adb.\n\n" +
                "On the computer you installed the app from (Portal connected by USB), run this — " +
                "one line, three space-separated parts — then reopen the app:"
            textSize = 14f
            setTextColor(0xFF_CCCCCC.toInt())
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(message)
            addView(code, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Presence needs one adb command")
            .setView(layout)
            .setPositiveButton("Got it", null)
            .show()
    }
}
