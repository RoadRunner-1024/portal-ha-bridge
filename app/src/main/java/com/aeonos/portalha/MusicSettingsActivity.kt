package com.aeonos.portalha

import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

/**
 * Music: which speaker roles this Portal offers, and whether the now-playing screen appears.
 * These switches mirror the Home Assistant ones, and both take effect immediately — the service
 * starts or stops the renderer rather than waiting for a restart.
 */
class MusicSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    // HA can flip any of these over MQTT while the screen is open; without this the switches
    // would show stale state and write it back on the next toggle.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_settings)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<Switch>(R.id.sw_dlna).setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.dlnaEnabled) return@setOnCheckedChangeListener
            prefs.dlnaEnabled = checked
            BridgeService.applyMediaSettings(this)
        }
        findViewById<Switch>(R.id.sw_sendspin).setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.sendspinEnabled) return@setOnCheckedChangeListener
            prefs.sendspinEnabled = checked
            BridgeService.applyMediaSettings(this)
        }
        findViewById<Switch>(R.id.sw_np_overlay).setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.nowPlayingOverlayEnabled) return@setOnCheckedChangeListener
            prefs.nowPlayingOverlayEnabled = checked
            BridgeService.applyMediaSettings(this)
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerListener(prefsListener)
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterListener(prefsListener)
    }

    private fun updateUi() {
        findViewById<Switch>(R.id.sw_dlna)?.isChecked = prefs.dlnaEnabled
        findViewById<Switch>(R.id.sw_sendspin)?.isChecked = prefs.sendspinEnabled
        findViewById<Switch>(R.id.sw_np_overlay)?.isChecked = prefs.nowPlayingOverlayEnabled
    }
}
