package com.aeonos.portalha

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// Calibration for the Portal's own sensors: tap sensitivity (was loose at the bottom
// of the main screen) and the temperature offset (was filed under Display & Presence,
// which it never had anything to do with).
class SensorSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etTempOffset: EditText
    private lateinit var tvTempNow: TextView
    private lateinit var tvSensitivity: TextView
    private lateinit var seekSensitivity: SeekBar
    private var hasTempSensor = false

    // Home Assistant can write tapThreshold and tempOffset over MQTT at any time. Without
    // this, an open Sensors screen would keep showing the old offset and then write it back
    // on exit — silently reverting the change made from HA.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateUi() }

    // Live raw reading next to the offset field, so it can be set against a real
    // thermometer. Mirrors the live sound readout on the Display screen.
    private val tempHandler = Handler(Looper.getMainLooper())
    private val tempPoll = object : Runnable {
        override fun run() {
            showTempNow()
            tempHandler.postDelayed(this, 2_000)
        }
    }

    private fun thresholdToProgress(t: Float) = ((t - 2.0f) / 0.5f).toInt().coerceIn(0, 26)
    private fun progressToThreshold(p: Int) = 2.0f + p * 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_sensor_settings)

        etTempOffset = findViewById(R.id.et_temp_offset)
        tvTempNow = findViewById(R.id.tv_temp_now)
        tvSensitivity = findViewById(R.id.tv_sensitivity_value)
        seekSensitivity = findViewById(R.id.seek_sensitivity)

        findViewById<Button>(R.id.btn_back).setOnClickListener { saveTempOffset(); finish() }

        // Sensitivity slider: 0–26 → 2.0–15.0 m/s² in 0.5 steps
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

        // The temperature section only makes sense on hardware that has the sensor.
        hasTempSensor = getSystemService(android.hardware.SensorManager::class.java)
            ?.getDefaultSensor(android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE) != null
        findViewById<View>(R.id.section_temp).visibility = if (hasTempSensor) View.VISIBLE else View.GONE
        etTempOffset.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveTempOffset() }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerListener(prefsListener)
        updateUi()
        if (hasTempSensor) tempHandler.post(tempPoll)
    }

    override fun onPause() {
        super.onPause()
        saveTempOffset()                     // save BEFORE unregistering, as Display does
        prefs.unregisterListener(prefsListener)
        tempHandler.removeCallbacks(tempPoll)
    }

    private fun updateSensitivityLabel(progress: Int) {
        tvSensitivity.text = "%.1f m/s²  (lower = more sensitive)".format(progressToThreshold(progress))
    }

    private fun updateUi() {
        if (!seekSensitivity.isPressed) {
            val p = thresholdToProgress(prefs.tapThreshold)
            if (seekSensitivity.progress != p) seekSensitivity.progress = p
            updateSensitivityLabel(p)
        }
        if (hasTempSensor && !etTempOffset.hasFocus()) {
            val s = "%.1f".format(prefs.tempOffset)
            if (etTempOffset.text.toString() != s) etTempOffset.setText(s)
        }
        showTempNow()
    }

    private fun showTempNow() {
        if (!hasTempSensor) return
        val raw = BridgeService.currentRawTemp()
        tvTempNow.text = if (raw == null) "Waiting for a reading…"
            else "Sensor reads %.1f°C → Home Assistant sees %.1f°C".format(raw, raw + prefs.tempOffset)
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
}
