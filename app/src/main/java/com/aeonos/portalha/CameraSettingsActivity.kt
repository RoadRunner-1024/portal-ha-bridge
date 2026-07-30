package com.aeonos.portalha

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CameraSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var swService: Switch
    private lateinit var swMotion: Switch
    private lateinit var swStream: Switch
    private lateinit var swStreamAudio: Switch
    private lateinit var btnCameraPower: Button
    private lateinit var btnRotate: Button
    private lateinit var tvCameraUrl: TextView

    // Live-sync the UI when the service changes prefs (HA commands, cascades).
    // Android dispatches these on the main thread; held as a field because
    // SharedPreferences only keeps a weak reference to listeners.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_camera_settings)

        swService = findViewById(R.id.sw_camera_service)
        swMotion = findViewById(R.id.sw_motion)
        swStream = findViewById(R.id.sw_stream)
        swStreamAudio = findViewById(R.id.sw_stream_audio)
        btnCameraPower = findViewById(R.id.btn_camera_power)
        btnRotate = findViewById(R.id.btn_rotate)
        tvCameraUrl = findViewById(R.id.tv_camera_url)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        btnRotate.setOnClickListener {
            prefs.streamRotation = (prefs.streamRotation + 90) % 360
            BridgeService.setRotation(this, prefs.streamRotation)
            updateUi()
        }

        swService.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.cameraServiceEnabled) return@setOnCheckedChangeListener
            prefs.cameraServiceEnabled = checked
            updateUi()
            restartService(if (checked) "Camera service enabled" else "Camera service disabled")
        }

        // Motion and streaming are mutually exclusive (each opens the camera).
        swMotion.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.motionEnabled) return@setOnCheckedChangeListener
            prefs.motionEnabled = checked
            if (checked) {
                prefs.streamEnabled = false   // mutually exclusive
                prefs.cameraOn = true
            } else if (prefs.cameraOn) {
                prefs.lastMotionEnabled = true; prefs.lastStreamEnabled = false
                prefs.cameraOn = false
            }
            updateUi()
            restartService(if (checked) "Motion detection enabled" else "Motion detection disabled")
        }

        swStream.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.streamEnabled) return@setOnCheckedChangeListener
            prefs.streamEnabled = checked
            if (checked) {
                prefs.motionEnabled = false   // mutually exclusive
                prefs.cameraOn = true
            } else if (prefs.cameraOn) {
                prefs.lastStreamEnabled = true; prefs.lastMotionEnabled = false
                prefs.cameraOn = false
            }
            updateUi()
            restartService(if (checked) "RTSP streaming enabled" else "RTSP streaming disabled")
        }

        // Experimental: room audio on the RTSP stream (mic tee — shares the
        // wake-word capture, mutes during Alexa turns/calls). The service owns
        // the pref write and the stream restart; this just sends the apply
        // broadcast (same channel the adb debug toggle uses).
        swStreamAudio.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.streamAudioEnabled) return@setOnCheckedChangeListener
            sendBroadcast(android.content.Intent("com.aeonos.portalha.DEBUG_STREAM_AUDIO")
                .setPackage(packageName).putExtra("on", checked))
            Toast.makeText(this,
                if (checked) "Audio added to the stream — restarting it"
                else "Stream is video-only again — restarting it", Toast.LENGTH_SHORT).show()
        }

        btnCameraPower.setOnClickListener {
            val on = !prefs.cameraOn
            prefs.cameraOn = on
            BridgeService.setCamera(this, on)
            updateUi()
        }
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

    private fun restartService(message: String) {
        BridgeService.stop(this)
        BridgeService.start(this)
        Toast.makeText(this, "$message — service restarting", Toast.LENGTH_SHORT).show()
    }

    private fun updateUi() {
        val serviceOn = prefs.cameraServiceEnabled
        val green = android.content.res.ColorStateList.valueOf(0xFF_1B5E20.toInt())
        val red = android.content.res.ColorStateList.valueOf(0xFF_B71C1C.toInt())

        swService.isChecked = serviceOn
        swMotion.isChecked = prefs.motionEnabled
        swStream.isChecked = prefs.streamEnabled
        swStreamAudio.isChecked = prefs.streamAudioEnabled

        // Sub-controls only make sense while the service is enabled
        swMotion.isEnabled = serviceOn
        swStream.isEnabled = serviceOn
        swStreamAudio.isEnabled = serviceOn && prefs.streamEnabled

        btnCameraPower.visibility = if (serviceOn) View.VISIBLE else View.GONE
        btnCameraPower.text = if (prefs.cameraOn) "Turn Camera Off" else "Turn Camera On"
        btnCameraPower.backgroundTintList = if (prefs.cameraOn) red else green

        btnRotate.visibility = if (serviceOn) View.VISIBLE else View.GONE
        btnRotate.text = "Rotate Stream (currently ${prefs.streamRotation}°)"

        if (serviceOn && prefs.streamEnabled) {
            val ip = BridgeService.localIp() ?: "<device-ip>"
            tvCameraUrl.text = if (prefs.streamAudioEnabled)
                "Home Assistant — use the WebRTC Camera\n" +
                "card (custom:webrtc-camera). Add a card:\n\n" +
                "type: custom:webrtc-camera\n" +
                "url: 'ffmpeg:rtsp://$ip:8554/#video=copy#audio=opus'\n" +
                "media: video,audio\n\n" +
                "(#audio=opus transcodes the AAC track for\n" +
                "WebRTC; or use mode: mse to play it as-is.)\n\n" +
                "Raw RTSP (VLC etc.): rtsp://$ip:8554/"
            else
                "Home Assistant — use the WebRTC Camera\n" +
                "card (custom:webrtc-camera). Add a card:\n\n" +
                "type: custom:webrtc-camera\n" +
                "url: 'ffmpeg:rtsp://$ip:8554/#video=copy'\n\n" +
                "#video=copy drops the empty audio track\n" +
                "WebRTC can't decode (prevents the 1-frame\n" +
                "freeze).\n\n" +
                "Raw RTSP (VLC etc.): rtsp://$ip:8554/"
            tvCameraUrl.visibility = View.VISIBLE
        } else {
            tvCameraUrl.visibility = View.GONE
        }
    }
}
