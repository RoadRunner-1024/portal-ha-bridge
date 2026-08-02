package com.aeonos.portalha

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class SensorBridge(
    private val context: android.content.Context,
    private val onPublish: (topic: String, payload: String, qos: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "PortalHA"
        private const val RGB_TYPE = 65537
        private const val TAP_COOLDOWN_MS = 800L
        private const val TAP_RESET_MS = 1500L
        private const val LIGHT_THROTTLE_MS = 2_000L
        private const val LIGHT_MIN_DELTA = 1.5f
        private const val ACCEL_THROTTLE_MS = 5_000L
        private const val GRAVITY_ALPHA = 0.85f
        private const val TEMP_THROTTLE_MS = 30_000L
        private const val TEMP_MIN_DELTA = 0.2f
    }

    // Which optional sensors this hardware actually has — drives whether the
    // matching HA entities are published. Portal has RGB (65537); Portal+ has
    // ambient temperature instead. Detected at start() from the sensor list.
    var hasRgb = false
        private set
    var hasTemperature = false
        private set

    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("portal-ha-sensors").also { it.start() }
    private val handler = Handler(thread.looper)

    // The Portal+ 2nd gen ("cipher") has its accelerometer on the moving screen
    // arm, which heavily dampens body taps — measured de-gravitied force was only
    // ~0.4–1.5 (vs the still-floor <0.4). Scale the threshold down hard so firm
    // taps land just above that floor. This model only.
    private val isCipher = android.os.Build.DEVICE.equals("cipher", true)
    private val tapScale = if (isCipher) 0.25f else 1f

    @Volatile private var gravX = 0f
    @Volatile private var gravY = 0f
    @Volatile private var gravZ = 0f
    private var gravInit = false

    private var lastTapMs = 0L
    private var lastLightMs = 0L
    private var lastLux = Float.MIN_VALUE
    private var lastAccelMs = 0L
    private var lastRgbMs = 0L
    private var lastTempMs = 0L
    private var lastTemp = Float.MIN_VALUE

    @Volatile private var prefs: Prefs? = null

    fun start(prefs: Prefs) {
        this.prefs = prefs
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == RGB_TYPE }?.let {
            hasRgb = true
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            hasTemperature = true
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        Log.i(TAG, "sensors: rgb=$hasRgb temperature=$hasTemperature")
    }

    fun stop() {
        sm.unregisterListener(this)
        thread.quitSafely()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val p = prefs ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> handleLight(event, p)
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event, p)
            Sensor.TYPE_AMBIENT_TEMPERATURE -> handleTemp(event, p)
            RGB_TYPE -> handleRgb(event, p)
        }
    }

    private fun handleTemp(event: SensorEvent, p: Prefs) {
        val c = event.values[0]
        val now = System.currentTimeMillis()
        if (now - lastTempMs < TEMP_THROTTLE_MS && abs(c - lastTemp) < TEMP_MIN_DELTA) return
        lastTempMs = now
        lastTemp = c   // raw reading; offset applied at publish time
        onPublish(HaDiscovery.tempStateTopic(p.deviceId), "%.1f".format(c + p.tempOffset), 0)
    }

    // Last RAW temperature reading (no offset), or null before the first one.
    // Shown live in Sensor settings so the offset can be calibrated against a
    // real thermometer without guessing what the sensor currently reads.
    fun rawTemperature(): Float? = if (lastTemp == Float.MIN_VALUE) null else lastTemp

    // Re-emit the last temperature with the current offset — called when the
    // offset changes so HA updates immediately instead of waiting for a reading.
    fun republishTemperature() {
        val p = prefs ?: return
        if (lastTemp == Float.MIN_VALUE) return
        onPublish(HaDiscovery.tempStateTopic(p.deviceId), "%.1f".format(lastTemp + p.tempOffset), 0)
    }

    private fun handleLight(event: SensorEvent, p: Prefs) {
        val lux = event.values[0]
        val now = System.currentTimeMillis()
        if (now - lastLightMs < LIGHT_THROTTLE_MS && abs(lux - lastLux) < LIGHT_MIN_DELTA) return
        lastLightMs = now
        lastLux = lux
        onPublish(HaDiscovery.lightStateTopic(p.deviceId), "%.1f".format(lux), 0)
    }

    private fun handleRgb(event: SensorEvent, p: Prefs) {
        val now = System.currentTimeMillis()
        if (now - lastRgbMs < LIGHT_THROTTLE_MS) return
        lastRgbMs = now
        val r = event.values.getOrElse(0) { 0f }
        val g = event.values.getOrElse(1) { 0f }
        val b = event.values.getOrElse(2) { 0f }
        onPublish(
            HaDiscovery.rgbStateTopic(p.deviceId),
            """{"r":${"%.1f".format(r)},"g":${"%.1f".format(g)},"b":${"%.1f".format(b)}}""",
            0
        )
    }

    private fun handleAccel(event: SensorEvent, p: Prefs) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val alpha = if (gravInit) GRAVITY_ALPHA else 0f
        gravX = alpha * gravX + (1 - alpha) * x
        gravY = alpha * gravY + (1 - alpha) * y
        gravZ = alpha * gravZ + (1 - alpha) * z
        gravInit = true

        val lx = x - gravX
        val ly = y - gravY
        val lz = z - gravZ
        val force = sqrt((lx * lx + ly * ly + lz * lz).toDouble()).toFloat()

        val now = System.currentTimeMillis()
        if (now - lastAccelMs >= ACCEL_THROTTLE_MS) {
            lastAccelMs = now
            onPublish(
                HaDiscovery.accelStateTopic(p.deviceId),
                """{"x":${"%.2f".format(x)},"y":${"%.2f".format(y)},"z":${"%.2f".format(z)}}""",
                0
            )
        }

        // Threshold is read live from prefs so HA slider and app slider take effect
        // immediately; tapScale lowers it on the less-sensitive Portal+ 2nd gen.
        val threshold = p.tapThreshold * tapScale
        if (force > threshold && now - lastTapMs > TAP_COOLDOWN_MS) {
            lastTapMs = now
            val dir = when {
                abs(lx) >= abs(ly) && abs(lx) >= abs(lz) -> if (lx > 0) "right" else "left"
                abs(ly) >= abs(lx) && abs(ly) >= abs(lz) -> if (ly > 0) "down" else "up"
                // On the cipher Portal+ the gesture reads as a screen tilt, so the
                // Z axis is more accurately up/down than front/back.
                else -> if (isCipher) { if (lz > 0) "up" else "down" } else { if (lz > 0) "front" else "back" }
            }
            Log.i(TAG, "tap: $dir  force=%.1f  threshold=%.1f (scale=%.2f)".format(force, threshold, tapScale))
            onPublish(HaDiscovery.tapStateTopic(p.deviceId), dir, 1)
            handler.removeCallbacksAndMessages("tap_reset")
            handler.postAtTime({
                onPublish(HaDiscovery.tapStateTopic(p.deviceId), "none", 0)
            }, "tap_reset", SystemClock.uptimeMillis() + TAP_RESET_MS)
        }
    }
}
