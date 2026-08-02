package com.aeonos.portalha

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// Everything that listens for or answers your voice: the two wake words and their
// phrases, the neural false-wake check, voice announce, and handing the mic to an
// external assistant. Split out of Display & Presence, which had grown into a
// voice screen with a display setting or two attached.
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var swCoexist: Switch
    private lateinit var swWake: Switch
    private lateinit var etWakePhrase: EditText
    private lateinit var swAlexa: Switch
    private lateinit var etAlexaPhrase: EditText
    private lateinit var swWakeVerify: Switch
    private lateinit var seekWakeVerify: SeekBar
    private lateinit var tvWakeVerify: TextView
    private lateinit var tvWakeVerifyCoverage: TextView
    private lateinit var swVoiceAnnounce: Switch

    // Live-sync when the service changes prefs underneath us (HA commands, cascades).
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_voice_settings)

        swCoexist = findViewById(R.id.sw_coexist)
        swWake = findViewById(R.id.sw_wake)
        etWakePhrase = findViewById(R.id.et_wake_phrase)
        swAlexa = findViewById(R.id.sw_alexa)
        etAlexaPhrase = findViewById(R.id.et_alexa_phrase)
        swWakeVerify = findViewById(R.id.sw_wake_verify)
        seekWakeVerify = findViewById(R.id.seek_wake_verify_threshold)
        tvWakeVerify = findViewById(R.id.tv_wake_verify_threshold)
        tvWakeVerifyCoverage = findViewById(R.id.tv_wake_verify_coverage)
        swVoiceAnnounce = findViewById(R.id.sw_voice_announce)

        findViewById<Button>(R.id.btn_back).setOnClickListener { saveWakePhrase(); saveAlexaPhrase(); finish() }

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

        // Neural wake verification (openWakeWord). The switch rebuilds the detector
        // (verifiers are created with the recognizer); the threshold applies live.
        swWakeVerify.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.wakeVerifyEnabled) return@setOnCheckedChangeListener
            prefs.wakeVerifyEnabled = checked
            BridgeService.applyDisplaySettings(this)
            updateUi()
        }
        seekWakeVerify.progress = (prefs.wakeVerifyThreshold - 2).coerceIn(0, 93)
        tvWakeVerify.text = "Strictness: ${prefs.wakeVerifyThreshold}"
        seekWakeVerify.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                val v = (p + 2).coerceIn(2, 95)
                tvWakeVerify.text = "Strictness: $v"
                if (fromUser) prefs.wakeVerifyThreshold = v
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) { BridgeService.applyDisplaySettings(this@VoiceSettingsActivity) }
        })

        // Read at trigger time by the service — no live apply needed.
        swVoiceAnnounce.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.voiceAnnounceEnabled) { prefs.voiceAnnounceEnabled = checked; updateUi() }
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
    }

    override fun onResume() {
        super.onResume()
        prefs.registerListener(prefsListener)
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        saveWakePhrase()
        saveAlexaPhrase()
        prefs.unregisterListener(prefsListener)
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

        // Neural verification only matters while one of our wake words is on.
        val anyWake = prefs.wakeWordEnabled || prefs.alexaWakeEnabled
        swWakeVerify.isChecked = prefs.wakeVerifyEnabled
        swWakeVerify.isEnabled = anyWake
        swWakeVerify.alpha = if (anyWake) 1f else 0.4f
        val verifyActive = anyWake && prefs.wakeVerifyEnabled
        seekWakeVerify.isEnabled = verifyActive
        seekWakeVerify.alpha = if (verifyActive) 1f else 0.4f
        tvWakeVerify.alpha = if (verifyActive) 1f else 0.4f
        if (!seekWakeVerify.isPressed) {
            seekWakeVerify.progress = (prefs.wakeVerifyThreshold - 2).coerceIn(0, 93)
            tvWakeVerify.text = "Strictness: ${prefs.wakeVerifyThreshold}"
        }
        // Say plainly which of THIS device's phrases the check can actually cover — a
        // custom phrase has no trained model, so it silently stays Vosk-only and keeps
        // whatever false wakes it had. Without this the setting looks like it applies
        // to everything.
        val phrases = buildList {
            if (prefs.wakeWordEnabled) add(prefs.wakePhrase)
            if (prefs.alexaWakeEnabled) add(prefs.alexaWakePhrase)
        }.filter { it.isNotBlank() }
        tvWakeVerifyCoverage.text = phrases.joinToString("\n") { p ->
            if (OwwVerifier.modelFor(p) != null) "✓  “$p” is double-checked"
            else "✗  “$p” has no model — not checked (only “alexa” and “hey jarvis” are)"
        }
        tvWakeVerifyCoverage.alpha = if (verifyActive) 0.75f else 0.35f

        // Voice announce rides the wake detector — grey it out when wake is off.
        swVoiceAnnounce.isChecked = prefs.voiceAnnounceEnabled
        swVoiceAnnounce.isEnabled = prefs.wakeWordEnabled
        swVoiceAnnounce.alpha = if (prefs.wakeWordEnabled) 1f else 0.4f
        findViewById<View>(R.id.tv_voice_announce_note).alpha = if (prefs.wakeWordEnabled) 0.6f else 0.3f

        // Our wake words (Jarvis + Alexa) need the mic; coexist gives it away.
        swWake.isEnabled = !prefs.coexistVoiceAssistant
        swWake.alpha = if (prefs.coexistVoiceAssistant) 0.4f else 1f
        swAlexa.isEnabled = !prefs.coexistVoiceAssistant
        swAlexa.alpha = if (prefs.coexistVoiceAssistant) 0.4f else 1f
        swCoexist.isChecked = prefs.coexistVoiceAssistant
        swCoexist.isEnabled = !anyWake
        swCoexist.alpha = if (anyWake) 0.4f else 1f
    }
}
