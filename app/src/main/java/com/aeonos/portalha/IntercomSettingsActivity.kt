package com.aeonos.portalha

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// All intercom settings in one place: show-buttons toggle, button configuration,
// announcement volume, and the talk-button appearance (colour / opacity /
// transparent background) with a LIVE PREVIEW so changes are visible here without
// needing the real floating buttons on the dashboard.
class IntercomSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_intercom_settings)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        preview = findViewById(R.id.preview_button)

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
                Toast.makeText(this, "Grant 'Display over other apps' first (Settings → main screen)", Toast.LENGTH_LONG).show()
                swShow.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.intercomOverlayEnabled = checked
            BridgeService.applyIntercomOverlay(this)
        }

        findViewById<Button>(R.id.btn_configure_buttons).setOnClickListener {
            startActivity(Intent(this, IntercomButtonsActivity::class.java))
        }

        // Announcement volume.
        val seekVol = findViewById<SeekBar>(R.id.seek_intercom_volume)
        val tvVol = findViewById<TextView>(R.id.tv_intercom_volume)
        seekVol.progress = prefs.intercomVolume
        tvVol.text = "Announcement volume: ${prefs.intercomVolume}%"
        seekVol.setOnSeekBarChangeListener(simpleSeek { p ->
            tvVol.text = "Announcement volume: $p%"
            prefs.intercomVolume = p
        })

        // Background colour (hue) — the track itself shows the full hue spectrum.
        val seekColor = findViewById<SeekBar>(R.id.seek_intercom_color)
        styleHueSlider(seekColor)
        seekColor.progress = prefs.intercomButtonHue
        seekColor.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.intercomButtonHue = p; updatePreview(); BridgeService.intercomOverlayRefresh()
        })

        // Background saturation (grey ↔ colour) and brightness (dark ↔ light).
        val seekSat = findViewById<SeekBar>(R.id.seek_bg_sat)
        seekSat.progress = prefs.intercomButtonSat
        seekSat.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.intercomButtonSat = p; updatePreview(); BridgeService.intercomOverlayRefresh()
        })
        val seekVal = findViewById<SeekBar>(R.id.seek_bg_val)
        seekVal.progress = prefs.intercomButtonVal
        seekVal.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.intercomButtonVal = p; updatePreview(); BridgeService.intercomOverlayRefresh()
        })

        // Text colour (hue) + shade (black ↔ colour ↔ white).
        val seekTextHue = findViewById<SeekBar>(R.id.seek_text_hue)
        styleHueSlider(seekTextHue)
        seekTextHue.progress = prefs.intercomTextHue
        seekTextHue.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.intercomTextHue = p; updatePreview(); BridgeService.intercomOverlayRefresh()
        })
        val seekTextShade = findViewById<SeekBar>(R.id.seek_text_shade)
        seekTextShade.progress = prefs.intercomTextShade
        seekTextShade.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.intercomTextShade = p; updatePreview(); BridgeService.intercomOverlayRefresh()
        })

        // Opacity.
        val seekOpacity = findViewById<SeekBar>(R.id.seek_intercom_opacity)
        val tvOpacity = findViewById<TextView>(R.id.tv_intercom_opacity)
        seekOpacity.progress = prefs.intercomOverlayOpacity
        tvOpacity.text = "Opacity: ${prefs.intercomOverlayOpacity}%"
        seekOpacity.setOnSeekBarChangeListener(simpleSeek { p ->
            val v = p.coerceIn(10, 100)
            tvOpacity.text = "Opacity: $v%"
            prefs.intercomOverlayOpacity = v; updatePreview(); BridgeService.intercomOverlayRefresh()
        })

        // Transparent background.
        val swTransparent = findViewById<Switch>(R.id.sw_transparent_bg)
        swTransparent.isChecked = prefs.intercomTransparentBg
        swTransparent.setOnCheckedChangeListener { _, checked ->
            prefs.intercomTransparentBg = checked; updatePreview(); BridgeService.intercomOverlayRefresh()
        }

        updatePreview()
    }

    // Mirror the overlay's idle look on the preview swatch.
    private fun updatePreview() {
        val density = resources.displayMetrics.density
        val color = if (prefs.intercomTransparentBg) Color.TRANSPARENT else IntercomOverlay.bgColor(prefs)
        preview.alpha = (prefs.intercomOverlayOpacity / 100f).coerceIn(0.1f, 1f)
        preview.setTextColor(IntercomOverlay.textColor(prefs))
        preview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40 * density
            setColor(color)
            setStroke((2 * density).toInt(), Color.parseColor("#80FFFFFF"))
        }
    }

    // Paint the colour SeekBar's track as a full rainbow hue gradient with a round
    // white thumb, so it reads as a colour picker rather than a plain slider.
    private fun styleHueSlider(seek: SeekBar) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val hues = IntArray(37) { Color.HSVToColor(floatArrayOf(it * 10f, 1f, 1f)) }   // 0..360
        val rainbow = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, hues).apply {
            cornerRadius = dp(5).toFloat()
            setSize(dp(280), dp(10))                     // intrinsic height = track height
        }
        seek.progressDrawable = LayerDrawable(arrayOf<Drawable>(rainbow)).apply {
            setLayerInset(0, 0, dp(10), 0, dp(10))       // vertical padding around the bar
        }
        seek.thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(2), Color.parseColor("#555555"))
            setSize(dp(22), dp(22))
        }
        seek.splitTrack = false
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }
}
