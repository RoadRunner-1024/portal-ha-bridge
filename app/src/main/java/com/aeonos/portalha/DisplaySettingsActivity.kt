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
    private lateinit var seekPresenceSound: SeekBar
    private lateinit var tvPresenceSound: TextView
    private lateinit var swTimeout: Switch
    private lateinit var etMinutes: EditText
    private lateinit var tvPresenceStatus: TextView
    private lateinit var etTempOffset: EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_display_settings)

        swPresence = findViewById(R.id.sw_presence)
        swEnhancedPresence = findViewById(R.id.sw_enhanced_presence)
        seekPresenceSound = findViewById(R.id.seek_presence_sound)
        tvPresenceSound = findViewById(R.id.tv_presence_sound)
        swTimeout = findViewById(R.id.sw_screen_timeout)
        etMinutes = findViewById(R.id.et_timeout_minutes)
        tvPresenceStatus = findViewById(R.id.tv_presence_status)
        etTempOffset = findViewById(R.id.et_temp_offset)

        // The temperature section only makes sense on hardware that has the sensor.
        hasTempSensor = getSystemService(android.hardware.SensorManager::class.java)
            ?.getDefaultSensor(android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE) != null
        findViewById<View>(R.id.section_temp).visibility = if (hasTempSensor) View.VISIBLE else View.GONE
        etTempOffset.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveTempOffset() }

        findViewById<Button>(R.id.btn_back).setOnClickListener { saveMinutes(); saveTempOffset(); finish() }

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

    private fun updateUi() {
        swPresence.isChecked = prefs.presenceEnabled
        swTimeout.isChecked = prefs.screenTimeoutEnabled
        if (etMinutes.text.toString() != prefs.screenTimeoutMinutes.toString())
            etMinutes.setText(prefs.screenTimeoutMinutes.toString())
        findViewById<View>(R.id.row_timeout_mins).alpha = if (prefs.screenTimeoutEnabled) 1f else 0.4f

        // Enhanced presence only applies while presence detection is on.
        swEnhancedPresence.isChecked = prefs.enhancedPresenceEnabled
        swEnhancedPresence.isEnabled = prefs.presenceEnabled
        swEnhancedPresence.alpha = if (prefs.presenceEnabled) 1f else 0.4f
        if (seekPresenceSound.progress != prefs.presenceSoundThreshold)
            seekPresenceSound.progress = prefs.presenceSoundThreshold
        tvPresenceSound.text = soundLabel(prefs.presenceSoundThreshold)
        findViewById<View>(R.id.row_presence_sound).alpha =
            if (prefs.presenceEnabled && prefs.enhancedPresenceEnabled) 1f else 0.4f

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
