package com.aeonos.portalha

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The photo frame's own screen. Split out of Display & Presence, which had grown to fifteen
 * controls and buried the ones people were actually looking for.
 */
class ScreensaverSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var swEnabled: Switch
    private lateinit var etUrl: EditText
    private lateinit var tvUrlHelp: TextView
    private lateinit var etIdle: EditText
    private lateinit var swOnWake: Switch
    private lateinit var swPrestage: Switch
    private lateinit var swPresence: Switch

    // Live-sync when the service changes prefs (the HA switch does).
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_screensaver_settings)

        swEnabled = findViewById(R.id.sw_screensaver)
        etUrl = findViewById(R.id.et_screensaver_url)
        tvUrlHelp = findViewById(R.id.tv_url_help)
        etIdle = findViewById(R.id.et_screensaver_idle)
        swOnWake = findViewById(R.id.sw_screensaver_on_wake)
        swPrestage = findViewById(R.id.sw_screensaver_prestage)
        swPresence = findViewById(R.id.sw_screensaver_presence)

        findViewById<Button>(R.id.btn_back).setOnClickListener { leave() }
        findViewById<Button>(R.id.btn_back_bottom).setOnClickListener { leave() }

        swEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.screensaverEnabled) return@setOnCheckedChangeListener
            prefs.screensaverEnabled = checked
            updateUi()
            if (checked && prefs.screensaverUrl.isBlank()) flagMissingUrl()
        }

        swOnWake.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.screensaverOnWake) return@setOnCheckedChangeListener
            prefs.screensaverOnWake = checked
            updateUi()
            if (checked && !prefs.screensaverPrestage)
                Toast.makeText(this,
                    "Turn on \"Keep photos ready while asleep\" so they appear instantly",
                    Toast.LENGTH_LONG).show()
        }

        swPrestage.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.screensaverPrestage) return@setOnCheckedChangeListener
            prefs.screensaverPrestage = checked
            updateUi()
        }

        swPresence.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.screensaverPresenceOnly) return@setOnCheckedChangeListener
            prefs.screensaverPresenceOnly = checked
            updateUi()
        }

        etUrl.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
        etIdle.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
    }

    /**
     * A screensaver switched on with no address does nothing at all, silently — the service
     * checks for a blank URL and gives up, so the user is left thinking the feature is broken.
     * Refuse to leave the screen until there is something to load, and say why.
     */
    private fun leave() {
        save()
        if (prefs.screensaverEnabled && prefs.screensaverUrl.isBlank()) {
            flagMissingUrl()
            return
        }
        finish()
    }

    private fun flagMissingUrl() {
        etUrl.error = "Enter your ImmichFrame or Kiosk address"
        etUrl.requestFocus()
        tvUrlHelp.setTextColor(0xFFE53935.toInt())
        tvUrlHelp.text = "The screensaver needs an address before it can show anything. " +
            "Enter one, or turn the screensaver off."
        Toast.makeText(this, "Enter an address, or turn the screensaver off",
            Toast.LENGTH_LONG).show()
    }

    private fun clearUrlFlag() {
        etUrl.error = null
        tvUrlHelp.setTextColor(getColor(R.color.hint))
        tvUrlHelp.text = "Address of your ImmichFrame or Immich Kiosk page. " +
            "Which photos appear is configured there, not here."
    }

    // The hardware/gesture back must not be a way around the check either.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = leave()

    private fun save() {
        val url = etUrl.text.toString().trim()
        if (url != prefs.screensaverUrl) {
            prefs.screensaverUrl = url
            if (url.isNotBlank()) clearUrlFlag()
        }
        etIdle.text.toString().toIntOrNull()?.let {
            val clamped = it.coerceIn(15, 3600)
            if (clamped != prefs.screensaverIdleSecs) prefs.screensaverIdleSecs = clamped
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerListener(prefsListener)
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        save()
        prefs.unregisterListener(prefsListener)
    }

    private fun updateUi() {
        swEnabled.isChecked = prefs.screensaverEnabled
        swOnWake.isChecked = prefs.screensaverOnWake
        swPrestage.isChecked = prefs.screensaverPrestage
        swPresence.isChecked = prefs.screensaverPresenceOnly

        // Only overwrite the fields when they differ, or a live pref change would yank the
        // cursor out from under someone mid-edit.
        if (etUrl.text.toString() != prefs.screensaverUrl) etUrl.setText(prefs.screensaverUrl)
        if (etIdle.text.toString() != prefs.screensaverIdleSecs.toString())
            etIdle.setText(prefs.screensaverIdleSecs.toString())

        for (v in listOf(swOnWake, swPrestage)) {
            v.isEnabled = prefs.screensaverEnabled
            v.alpha = if (prefs.screensaverEnabled) 1f else 0.4f
        }
        etUrl.alpha = if (prefs.screensaverEnabled) 1f else 0.4f
        etIdle.alpha = if (prefs.screensaverEnabled) 1f else 0.4f
        // Presence-gating can't do anything without presence detection itself.
        swPresence.isEnabled = prefs.screensaverEnabled && prefs.presenceEnabled
        swPresence.alpha =
            if (prefs.screensaverEnabled && prefs.presenceEnabled) 1f else 0.4f
    }
}
