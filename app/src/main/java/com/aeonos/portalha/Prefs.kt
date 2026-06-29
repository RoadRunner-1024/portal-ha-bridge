package com.aeonos.portalha

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

// One floating push-to-talk button: a name and the target it announces to
// ("all" = everyone, otherwise a peer device id), plus its saved screen position
// (-1 = use a default slot).
data class IntercomButton(
    val name: String,
    val target: String,
    val x: Int = -1,
    val y: Int = -1
)

class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("portal_ha", Context.MODE_PRIVATE)

    // The service updates prefs in response to HA commands (camera on/off,
    // feature cascades); UI screens register here to stay in sync live.
    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    var brokerHost: String
        get() = sp.getString("broker_host", "homeassistant.local") ?: "homeassistant.local"
        set(v) = sp.edit().putString("broker_host", v).apply()

    var brokerPort: Int
        get() = sp.getInt("broker_port", 1883)
        set(v) = sp.edit().putInt("broker_port", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    var deviceName: String
        get() = sp.getString("device_name", "Portal") ?: "Portal"
        set(v) = sp.edit().putString("device_name", v).apply()

    val deviceId: String
        get() {
            val existing = sp.getString("device_id", null)
            if (existing != null) return existing
            val new = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString("device_id", new).apply()
            return new
        }

    var tapThreshold: Float
        get() = sp.getFloat("tap_threshold", 4.0f)
        set(v) = sp.edit().putFloat("tap_threshold", v).apply()

    // Camera feature toggles. The legacy "camera_enabled" key seeds the defaults
    // so existing installs keep their behavior after upgrading.
    var motionEnabled: Boolean
        get() = sp.getBoolean("motion_enabled", sp.getBoolean("camera_enabled", false))
        set(v) = sp.edit().putBoolean("motion_enabled", v).apply()

    var streamEnabled: Boolean
        get() = sp.getBoolean("stream_enabled", sp.getBoolean("camera_enabled", false))
        set(v) = sp.edit().putBoolean("stream_enabled", v).apply()

    // Master switch for the entire camera service (app-only, not exposed to HA).
    // When off: no camera infrastructure, no HA camera/motion entities, and
    // camera commands from HA are ignored.
    var cameraServiceEnabled: Boolean
        get() = sp.getBoolean("camera_service_enabled", motionEnabled || streamEnabled)
        set(v) = sp.edit().putBoolean("camera_service_enabled", v).apply()

    // Consumers active when the camera was last turned off — turning the camera
    // back on restores them (camera off switches motion/streaming off; camera on
    // brings back what was running before).
    var lastMotionEnabled: Boolean
        get() = sp.getBoolean("last_motion_enabled", true)
        set(v) = sp.edit().putBoolean("last_motion_enabled", v).apply()

    var lastStreamEnabled: Boolean
        get() = sp.getBoolean("last_stream_enabled", true)
        set(v) = sp.edit().putBoolean("last_stream_enabled", v).apply()

    // Desired camera on/off state — survives app restarts and reboots so the
    // camera comes back without relying on retained MQTT commands.
    var cameraOn: Boolean
        get() = sp.getBoolean("camera_on", false)
        set(v) = sp.edit().putBoolean("camera_on", v).apply()

    var motionSensitivity: Int
        get() = sp.getInt("motion_sensitivity", 20)
        set(v) = sp.edit().putInt("motion_sensitivity", v).apply()

    // Calibration offset (deg C) added to the ambient-temperature reading before
    // publishing. The Portal+ sensor is an accelerometer die-temp sensor with a
    // per-chip bias, so this lets the user dial it to a real thermometer.
    var tempOffset: Float
        get() = sp.getFloat("temp_offset", 0f)
        set(v) = sp.edit().putFloat("temp_offset", v.coerceIn(-20f, 20f)).apply()

    // Manual stream rotation in degrees (0/90/180/270), cycled from the app.
    var streamRotation: Int
        // cipher (2nd-gen Portal+) is fixed-orientation and needs +90 to be upright;
        // default it there so it's correct out of the box (still adjustable).
        get() = sp.getInt("stream_rotation", if (android.os.Build.DEVICE.equals("cipher", true)) 90 else 0)
        set(v) = sp.edit().putInt("stream_rotation", v).apply()

    // Portal presence — reads Meta's own face-presence detection by tailing
    // logcat (needs READ_LOGS via adb). Published to HA as a binary_sensor.
    var presenceEnabled: Boolean
        get() = sp.getBoolean("presence_enabled", false)
        set(v) = sp.edit().putBoolean("presence_enabled", v).apply()

    // Enhanced presence: also count loud-enough ambient sound as "present" — helps
    // in low light where Meta's camera face-detection gets unreliable. The threshold
    // is 0–100, same scale as the Sound Level sensor (higher = needs louder sound).
    var enhancedPresenceEnabled: Boolean
        get() = sp.getBoolean("enhanced_presence", false)
        set(v) = sp.edit().putBoolean("enhanced_presence", v).apply()

    var presenceSoundThreshold: Int
        get() = sp.getInt("presence_sound_threshold", 8)
        set(v) = sp.edit().putInt("presence_sound_threshold", v.coerceIn(0, 100)).apply()

    // On-device screen-off timer (independent of HA). When enabled, the screen
    // sleeps after this many minutes with no presence / no wake. Disabled = the
    // screen stays on indefinitely.
    var screenTimeoutEnabled: Boolean
        get() = sp.getBoolean("screen_timeout_enabled", false)
        set(v) = sp.edit().putBoolean("screen_timeout_enabled", v).apply()

    var screenTimeoutMinutes: Int
        get() = sp.getInt("screen_timeout_minutes", 5)
        set(v) = sp.edit().putInt("screen_timeout_minutes", v.coerceIn(1, 240)).apply()

    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(v) = sp.edit().putString("ha_url", v).apply()

    // Portal-to-Portal intercom: show a floating push-to-talk button over the
    // dashboard (optional — the drawer always has a hold-to-announce button).
    var intercomOverlayEnabled: Boolean
        get() = sp.getBoolean("intercom_overlay_enabled", false)
        set(v) = sp.edit().putBoolean("intercom_overlay_enabled", v).apply()

    // The configured floating talk buttons. Empty list + overlay enabled → a single
    // default "Talk → Everyone" button is shown (and seeded on first move/config).
    fun getIntercomButtons(): MutableList<IntercomButton> {
        val raw = sp.getString("intercom_buttons", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                IntercomButton(
                    o.optString("name", "Talk"),
                    o.optString("target", "all"),
                    o.optInt("x", -1),
                    o.optInt("y", -1)
                )
            }
        }.getOrDefault(mutableListOf())
    }

    fun setIntercomButtons(list: List<IntercomButton>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject()
                .put("name", b.name).put("target", b.target).put("x", b.x).put("y", b.y))
        }
        sp.edit().putString("intercom_buttons", arr.toString()).apply()
    }

    // Playback level (0–100) the speaker is set to while an announcement plays.
    var intercomVolume: Int
        get() = sp.getInt("intercom_volume", 55)
        set(v) = sp.edit().putInt("intercom_volume", v.coerceIn(0, 100)).apply()

    // Idle opacity of the floating talk buttons (10–100 %); solid while moving/live.
    var intercomOverlayOpacity: Int
        get() = sp.getInt("intercom_overlay_opacity", 45)
        set(v) = sp.edit().putInt("intercom_overlay_opacity", v.coerceIn(10, 100)).apply()

    // Talk-button background colour as HSV: hue (0–360), saturation + value (0–100).
    // Drop saturation to 0 for grey; value is the light↔dark control. Defaults match
    // the old fixed blue (hue 230, sat 65, val 82).
    var intercomButtonHue: Int
        get() = sp.getInt("intercom_btn_hue", 230)
        set(v) = sp.edit().putInt("intercom_btn_hue", v.coerceIn(0, 360)).apply()

    var intercomButtonSat: Int
        get() = sp.getInt("intercom_btn_sat", 65)
        set(v) = sp.edit().putInt("intercom_btn_sat", v.coerceIn(0, 100)).apply()

    var intercomButtonVal: Int
        get() = sp.getInt("intercom_btn_val", 82)
        set(v) = sp.edit().putInt("intercom_btn_val", v.coerceIn(0, 100)).apply()

    // Talk-button text colour: hue (0–360) + a single "shade" (0 = black, 50 = full
    // colour, 100 = white). Default 100 = white text.
    var intercomTextHue: Int
        get() = sp.getInt("intercom_text_hue", 0)
        set(v) = sp.edit().putInt("intercom_text_hue", v.coerceIn(0, 360)).apply()

    var intercomTextShade: Int
        get() = sp.getInt("intercom_text_shade", 100)
        set(v) = sp.edit().putInt("intercom_text_shade", v.coerceIn(0, 100)).apply()

    // Draw the talk button with no filled background (just the label) when idle.
    var intercomTransparentBg: Boolean
        get() = sp.getBoolean("intercom_transparent_bg", false)
        set(v) = sp.edit().putBoolean("intercom_transparent_bg", v).apply()

    val brokerUri: String get() = "tcp://$brokerHost:$brokerPort"
}
