package com.aeonos.portalha

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// How the intercom behaves: whether the floating talk buttons show, what they're
// bound to, the hands-free 2-way reply channel and its timeout, and announcement
// volume. Purely cosmetic settings moved to IntercomAppearanceActivity.
class IntercomSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_intercom_settings)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        val canTransmit = BridgeService.intercomCanTransmit()

        // Show-buttons toggle (needs overlay permission + ability to transmit).
        val swShow = findViewById<Switch>(R.id.sw_show_buttons)
        val tvNote = findViewById<TextView>(R.id.tv_intercom_note)
        swShow.isChecked = prefs.intercomOverlayEnabled && canTransmit
        swShow.isEnabled = canTransmit
        tvNote.text = if (canTransmit) {
            "Two-way intercom enabled — you can send and receive announcements."
        } else {
            "Receive-only on this Portal.\n\nThe microphone here is reserved by the system " +
                "(the Portal+'s always-on far-field mic / voice assistant), so this Portal can " +
                "only HEAR announcements, not send them."
        }
        swShow.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.intercomOverlayEnabled) return@setOnCheckedChangeListener
            if (checked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Display over other apps' first (Settings → System & Updates)", Toast.LENGTH_LONG).show()
                swShow.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.intercomOverlayEnabled = checked
            BridgeService.applyIntercomOverlay(this)
        }

        findViewById<Button>(R.id.btn_configure_buttons).setOnClickListener {
            startActivity(Intent(this, IntercomButtonsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_button_appearance).setOnClickListener {
            startActivity(Intent(this, IntercomAppearanceActivity::class.java))
        }

        // Experimental: enable 2-way mode. When on, an announce opens a hands-free reply
        // channel so recipients can talk back. This is a persisted enable, not a live channel.
        val swTwoWay = findViewById<Switch>(R.id.sw_two_way_test)
        swTwoWay.isChecked = prefs.twoWayExperimental
        swTwoWay.setOnCheckedChangeListener { _, checked ->
            prefs.twoWayExperimental = checked
            BridgeService.setTwoWayEnabled(this, checked)
        }

        // Reply timeout: how many seconds of silence keep the reply channel open.
        // Read live by the service's idle check, so it applies immediately —
        // even to a channel that's already open.
        val seekReply = findViewById<SeekBar>(R.id.seek_two_way_timeout)
        val tvReply = findViewById<TextView>(R.id.tv_two_way_timeout)
        fun replyLabel(s: Int) { tvReply.text = "Reply timeout: closes after ${s}s of silence" }
        seekReply.progress = (prefs.twoWayIdleSecs - 2).coerceIn(0, 58)
        replyLabel(prefs.twoWayIdleSecs)
        seekReply.setOnSeekBarChangeListener(simpleSeek { p ->
            val secs = p + 2   // slider 0..58 → 2..60 s
            prefs.twoWayIdleSecs = secs
            replyLabel(secs)
        })

        // Announcement volume.
        val seekVol = findViewById<SeekBar>(R.id.seek_intercom_volume)
        val tvVol = findViewById<TextView>(R.id.tv_intercom_volume)
        seekVol.progress = prefs.intercomVolume
        tvVol.text = "Announcement volume: ${prefs.intercomVolume}%"
        seekVol.setOnSeekBarChangeListener(simpleSeek { p ->
            tvVol.text = "Announcement volume: $p%"
            prefs.intercomVolume = p
        })
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }
}
