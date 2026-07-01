package com.aeonos.portalha

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BridgeService : Service() {

    companion object {
        private const val TAG = "PortalHA"
        private const val CHANNEL = "portal_ha_bridge"
        private const val NOTIF_ID = 1
        private const val MOTION_CLEAR_MS = 5_000L
        // How long a loud sound keeps "enhanced presence" present after the noise
        // stops (people make only intermittent sound, so we bridge the gaps).
        private const val SOUND_PRESENCE_HOLD_MS = 60_000L
        @Volatile private var crashGuardInstalled = false

        private const val ACTION_SET_CAMERA = "com.aeonos.portalha.SET_CAMERA"
        private const val EXTRA_CAMERA_ON = "camera_on"
        private const val ACTION_SET_ROTATION = "com.aeonos.portalha.SET_ROTATION"
        private const val EXTRA_ROTATION = "rotation"
        private const val ACTION_ENSURE_CAMERA = "com.aeonos.portalha.ENSURE_CAMERA"
        private const val ACTION_APPLY_DISPLAY = "com.aeonos.portalha.APPLY_DISPLAY"
        private const val ACTION_APPLY_INTERCOM = "com.aeonos.portalha.APPLY_INTERCOM"

        // Live reference to the running service so the dashboard UI + the PTT
        // overlay can query peers and drive the intercom directly (low latency,
        // no intent round-trip). Cleared on destroy.
        @Volatile private var instance: BridgeService? = null

        fun intercomPeers(): List<Intercom.Peer> = instance?.intercom?.onlinePeers() ?: emptyList()
        fun intercomBusyName(): String? = instance?.intercom?.busySpeakerName()
        fun intercomTalking(): Boolean = instance?.intercom?.isTalking() == true
        // Whether this Portal can SEND announcements (false when Alexa holds the mic).
        fun intercomCanTransmit(): Boolean = instance?.intercom?.canTransmit() ?: true
        // Returns true if talking actually started (false = busy / no mic / not ready).
        fun intercomStartTalk(target: String?): Boolean = instance?.intercom?.startTalk(target) == true
        fun intercomStopTalk() { instance?.intercom?.stopTalk() }

        // Fire the assistant hand-off on demand (e.g. the HA dashboard voice button via
        // HaExternalBridge). Reuses the wake flow: brings the assistant up, yields the mic,
        // reclaims it when done. No-op if the service isn't running.
        fun requestAssist(@Suppress("UNUSED_PARAMETER") context: Context) { instance?.fireWakeHandoff() }

        // Re-evaluate the PTT overlays after a pref/config change.
        fun applyIntercomOverlay(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_APPLY_INTERCOM))

        // Repaint the floating buttons live (e.g. the transparency slider moved).
        fun intercomOverlayRefresh() { instance?.intercomOverlays?.forEach { it.refresh() } }

        // The dashboard drives overlay visibility — the floating buttons show only
        // while the Portal HA Bridge dashboard is in front, not over other apps.
        @Volatile private var dashboardForeground = false
        fun setDashboardForeground(fg: Boolean) {
            dashboardForeground = fg
            instance?.reconcileIntercomOverlays()
        }

        fun start(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, BridgeService::class.java))

        // In-app camera on/off button — same code path as the HA MQTT command.
        fun setCamera(context: Context, on: Boolean) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_SET_CAMERA).putExtra(EXTRA_CAMERA_ON, on))

        // Apply a new stream rotation to the live camera without a restart.
        fun setRotation(context: Context, degrees: Int) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_SET_ROTATION).putExtra(EXTRA_ROTATION, degrees))

        // Re-acquire the camera if it should be on but was evicted (e.g. another
        // app grabbed it while we were backgrounded). Called on activity resume.
        fun ensureCamera(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_ENSURE_CAMERA))

        // Re-read presence/screen-timeout prefs and resync (monitor + HA states)
        // without a full service restart. Called from the display settings page.
        fun applyDisplaySettings(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_APPLY_DISPLAY))

        // Latest 0–100 ambient sound level (or -1) — for calibrating the enhanced-
        // presence threshold live in settings.
        fun currentSoundLevel(): Int = instance?.lastSoundLevel ?: -1

        // Latest combined presence (face OR sound), or null if unknown / presence
        // detection is off. Read by the Jarvis tool-provider's get_presence tool.
        fun currentPresence(): Boolean? = instance?.lastPublishedPresence

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private val running = AtomicBoolean(false)
    // Paho's callback thread must never block: a synchronous publish() from inside
    // messageArrived deadlocks the client — QoS 0 token completion is dispatched by
    // that same callback thread. All inbound commands run on this executor instead.
    private val commandExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "portal-ha-cmd").also { it.isDaemon = true }
    }
    @Volatile private var mqtt: MqttClient? = null
    @Volatile private var prefs: Prefs? = null

    // Screen + audio
    private var screenReceiver: BroadcastReceiver? = null
    private var audioReceiver: BroadcastReceiver? = null
    private var sensorBridge: SensorBridge? = null
    private var soundMonitor: SoundMonitor? = null
    private var wakeDetector: WakeWordDetector? = null
    private var startedWakePhrase: String? = null   // phrase the live recognizer was built with

    // Portal-to-Portal intercom (audio-only push-to-announce) + optional overlays.
    private var intercom: Intercom? = null
    private val intercomOverlays = mutableListOf<IntercomOverlay>()
    private var deleteTarget: DeleteTargetOverlay? = null
    private var movingCount = 0                 // talk buttons currently in move mode
    private var shownOverlaySignature: String? = null   // config the live overlays were built from
    @Volatile private var lastVolumePercent = -1
    @Volatile private var lastVolumeMuted = false
    @Volatile private var lastBrightnessPercent = -1

    // Camera
    private var cameraStream: CameraStream? = null
    private var rtspStreamer: RtspStreamer? = null
    private val mediaKeepAlive = MediaKeepAlive()
    private var cameraOverlay: View? = null
    private val motionDetector = MotionDetector()
    @Volatile private var cameraActive = false
    @Volatile private var lastMotionMs = 0L
    @Volatile private var motionPublished = false

    // Recover the RTSP stream when a Portal CALL grabs Camera 0 and later frees it.
    // The call yanks the camera surface (stream goes dead, "Broken pipe") but our
    // isStreaming stays true. When OUR front camera becomes available again while
    // we still think we're streaming, that means we lost it → restart to recover.
    private var frontCameraId: String? = null
    @Volatile private var rtspNeedsRestart = false
    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            // Our front camera went free while we still think we're streaming → a
            // call took it. DON'T restart here: we're backgrounded (call just ended)
            // and Android blocks opening the camera from the background. Flag it and
            // recover on the next return to the app (ensureCamera → foreground).
            if (cameraId == frontCameraId && rtspStreamer?.isStreaming == true) {
                Log.i(TAG, "camera $cameraId freed while streaming (call?) — will recover on return to app")
                rtspNeedsRestart = true
            }
        }
    }

    // Accelerometer auto-rotate. The Portal locks its OS display rotation, but the
    // accelerometer still tracks gravity, so OrientationEventListener tells us
    // landscape vs portrait. Debounced (each change triggers one restart() — which
    // blips clients), and only acts while streaming.
    @Volatile private var lastDeviceOrientation = -1   // committed snapped angle
    @Volatile private var pendingDeviceOrientation = -1
    // Both Portal+ models have a FIXED camera that does NOT pivot with the screen, so
    // auto-rotate (accelerometer) is wrong for them — it kept changing rotation as the
    // screen turned. Disable it; use the persisted streamRotation (default 0 for aloha =
    // upright, 90 for cipher). The manual ROTATE button still adjusts it. Other models
    // (e.g. the 10" Portal) keep the accelerometer auto-rotate.
    private val isAloha = android.os.Build.DEVICE.equals("aloha", true)
    private val isCipher = android.os.Build.DEVICE.equals("cipher", true)
    private val orientationApply = Runnable { commitDeviceOrientation() }
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val snapped = when {
                    deg >= 315 || deg < 45 -> 0
                    deg < 135 -> 90
                    deg < 225 -> 180
                    else -> 270
                }
                onDeviceOrientation(snapped)
            }
        }
    }

    // Portal presence (logcat heartbeat) + on-device screen-off timer
    private var presenceMonitor: PresenceMonitor? = null
    // Enhanced presence: combine Meta's face detection (facePresent) with recent
    // ambient-sound activity (lastSoundActivityMs) so a person in a dark room still
    // registers. lastPublishedPresence dedupes the combined output (null = unsent).
    @Volatile private var facePresent = false
    @Volatile private var lastSoundActivityMs = 0L
    @Volatile private var lastPublishedPresence: Boolean? = null
    @Volatile private var lastSoundLevel = -1   // for the live readout in settings
    @Volatile private var screenOn = true
    @Volatile private var lastActivityMs = System.currentTimeMillis()
    private val timeoutThread = HandlerThread("portal-ha-timeout").also { it.start() }
    private val timeoutHandler = Handler(timeoutThread.looper)
    private val timeoutRunnable = object : Runnable {
        override fun run() { checkScreenTimeout(); timeoutHandler.postDelayed(this, 15_000L) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        installRtspCrashGuard()
        createChannel()
        startForeground(NOTIF_ID, notification("Starting…"))

        val p = Prefs(this).also { prefs = it }
        ScreenControl.enableAccessibility(this)
        sensorBridge = SensorBridge(this, ::publishRaw).also { it.start(p) }
        soundMonitor = SoundMonitor(this) { level ->
            lastSoundLevel = level
            prefs?.let { p ->
                publishRaw(HaDiscovery.soundStateTopic(p.deviceId), level.toString(), 0)
                // Enhanced presence: loud-enough sound counts as activity.
                if (p.enhancedPresenceEnabled && p.presenceEnabled && level >= p.presenceSoundThreshold)
                    lastSoundActivityMs = System.currentTimeMillis()
                recomputePresence(p)
            }
        }
        // Coexist with an external voice assistant: release the mic. Our own wake word
        // (wakeDetector) needs the mic, so the two are mutually exclusive — wake wins.
        val coexist = p.coexistVoiceAssistant && !p.wakeWordEnabled
        intercom = Intercom(this, p.deviceId, { prefs?.deviceName ?: "Portal" }, { localIp() }, ::publishBytes)
            .also {
                it.attachSoundMonitor(if (coexist) null else soundMonitor)
                it.setOnDemandCapture(coexist)
            }
        // On-device "hey jarvis": fed the warm mic, fires the assistant wake handoff.
        wakeDetector = WakeWordDetector(this) { fireWakeHandoff() }.also { it.phrase = p.wakePhrase }
        soundMonitor?.wakeSink = { buf, n -> wakeDetector?.feed(buf, n) }
        instance = this

        // Measure mic capability first (it owns the mic briefly), then start the
        // sound sensor + the PTT overlay once we know whether this Portal can send.
        intercom?.probeTransmitCapability()   // ~1.1s, owns the mic while measuring
        Handler(Looper.getMainLooper()).postDelayed({
            if (!coexist) soundMonitor?.start()   // coexist = leave the mic for the assistant
            if (p.wakeWordEnabled) { wakeDetector?.start(); startedWakePhrase = p.wakePhrase }
            reconcileIntercomOverlays()
        }, 1_500L)

        if (p.cameraServiceEnabled) {
            // Overlay keeps the process "visible" so Camera 0 opens from the
            // service. The actual owner (RTSP streamer or motion CameraStream)
            // is decided by applyCameraState on the camera-restore path.
            showCameraOverlay()
        }

        registerScreenReceiver()
        registerAudioReceiver()
        // Stops Portal's launcher from idle-kicking us to the home screen.
        mediaKeepAlive.start(this)

        screenOn = getSystemService(PowerManager::class.java).isInteractive
        lastActivityMs = System.currentTimeMillis()
        reconcilePresence(p)
        timeoutHandler.post(timeoutRunnable)

        if (p.cameraServiceEnabled && (isAloha || isCipher)) {
            Log.i(TAG, "orientation auto-rotate disabled (Portal+ camera is fixed; uses streamRotation)")
        } else if (p.cameraServiceEnabled && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
            Log.i(TAG, "orientation auto-rotate enabled (accelerometer)")
        } else if (p.cameraServiceEnabled) {
            Log.w(TAG, "orientation auto-rotate unavailable: no usable accelerometer")
        }

        if (p.cameraServiceEnabled) registerCameraAvailability()
    }

    private fun registerCameraAvailability() {
        runCatching {
            val cm = getSystemService(CameraManager::class.java)
            frontCameraId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }
            cm.registerAvailabilityCallback(cameraAvailabilityCallback, Handler(Looper.getMainLooper()))
            Log.i(TAG, "camera-availability watch on (front camera id=$frontCameraId)")
        }.onFailure { Log.w(TAG, "camera-availability register failed: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            Thread(::mqttLoop, "portal-ha-mqtt").also { it.isDaemon = true }.start()
        }
        if (intent?.action == ACTION_SET_CAMERA) {
            val on = intent.getBooleanExtra(EXTRA_CAMERA_ON, false)
            val p = prefs ?: Prefs(this).also { prefs = it }
            commandExecutor.submit {
                runCatching { handleCameraCommand(if (on) "ON" else "OFF", p) }
                    .onFailure { Log.w(TAG, "in-app camera toggle failed: ${it.message}") }
            }
        }
        if (intent?.action == ACTION_SET_ROTATION) {
            val deg = intent.getIntExtra(EXTRA_ROTATION, 0)
            commandExecutor.submit {
                cameraStream?.rotation = deg                    // motion path (live)
                rtspStreamer?.let { it.rotationOffset = deg; if (it.isStreaming) it.restart() }
                Log.i(TAG, "manual rotation offset set to $deg deg")
            }
        }
        if (intent?.action == ACTION_ENSURE_CAMERA) {
            val p = prefs ?: Prefs(this).also { prefs = it }
            commandExecutor.submit {
                runCatching {
                    Log.i(TAG, "ensureCamera: serviceEnabled=${p.cameraServiceEnabled} cameraOn=${p.cameraOn} rtsp=${rtspStreamer?.isStreaming} needsRestart=$rtspNeedsRestart motionCam=${cameraStream?.isActive}")
                    if (p.cameraServiceEnabled && p.cameraOn) {
                        val r = rtspStreamer
                        if (rtspNeedsRestart && r != null && r.isStreaming) {
                            // A call took the camera and freed it; we're foreground
                            // now so the camera can reopen — restart to recover.
                            rtspNeedsRestart = false
                            Log.i(TAG, "ensureCamera: recovering stream after call took the camera — restart")
                            r.restart()
                        } else {
                            applyCameraState(p)
                        }
                    }
                }.onFailure { Log.w(TAG, "ensureCamera failed: ${it.message}") }
            }
        }
        if (intent?.action == ACTION_APPLY_INTERCOM) {
            prefs ?: Prefs(this).also { prefs = it }
            hideIntercomOverlays()          // rebuild from the (possibly edited) config
            reconcileIntercomOverlays()
        }
        if (intent?.action == ACTION_APPLY_DISPLAY) {
            val p = prefs ?: Prefs(this).also { prefs = it }
            commandExecutor.submit {
                runCatching {
                    applyCoexist(p)
                    reconcileWake(p)
                    reconcilePresence(p)
                    publishDisplayDiscovery(p)
                    publishDisplayStates(p)
                    if (sensorBridge?.hasTemperature == true) {
                        publishRaw(HaDiscovery.tempOffsetStateTopic(p.deviceId), "%.1f".format(p.tempOffset), 1, retained = true)
                        sensorBridge?.republishTemperature()
                    }
                    lastActivityMs = System.currentTimeMillis()  // give the new timeout a fresh start
                }.onFailure { Log.w(TAG, "applyDisplaySettings failed: ${it.message}") }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        commandExecutor.shutdownNow()
        runCatching { mqtt?.disconnect(0) }
        screenReceiver?.let { unregisterReceiver(it) }
        audioReceiver?.let { unregisterReceiver(it) }
        sensorBridge?.stop()
        soundMonitor?.stop()
        wakeDetector?.stop()
        wakeHandler.removeCallbacks(reclaimPoll); micYieldedForWake = false
        intercom?.release()
        hideIntercomOverlays()
        instance = null
        cameraStream?.release()
        rtspStreamer?.stop()
        runCatching { orientationListener.disable() }
        runCatching { getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(cameraAvailabilityCallback) }
        mediaKeepAlive.stop()
        presenceMonitor?.release()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutThread.quitSafely()
        hideCameraOverlay()
        super.onDestroy()
    }

    // ── Camera construction ───────────────────────────────────────────────────

    // Motion-detection camera path (RTSP streaming uses its own RtspStreamer).
    private fun buildCameraStream(p: Prefs) = CameraStream(this).apply {
        rotation = p.streamRotation
        onFrame = { jpeg ->
            if (p.motionEnabled && motionDetector.detect(jpeg, p.motionSensitivity)) {
                lastMotionMs = System.currentTimeMillis()
                if (!motionPublished) {
                    motionPublished = true
                    publishRaw(HaDiscovery.motionStateTopic(p.deviceId), "ON", 0)
                }
            }
        }
        onStateChange = { active ->
            cameraActive = active
            publishRaw(HaDiscovery.cameraStateTopic(p.deviceId),
                if (active) "ON" else "OFF", 1, retained = true)
        }
    }

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        lastActivityMs = System.currentTimeMillis()  // restart the off-timer
                        publishState("ON"); reclaimForeground()
                    }
                    Intent.ACTION_SCREEN_OFF -> { screenOn = false; publishState("OFF") }
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    // While the screen is off, Portal's launcher (com.facebook.alohaapps.launcher)
    // asserts HOME behind the dark screen, so we wake to the launcher instead of
    // the dashboard. Bring our dashboard back to the front on screen-on. Our
    // SYSTEM_ALERT_WINDOW permission exempts this from background-start limits.
    // DashboardActivity is singleTask, so this reuses the existing instance.
    private fun reclaimForeground() {
        runCatching {
            startActivity(Intent(this, DashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }.onFailure { Log.w(TAG, "reclaimForeground failed: ${it.message}") }
    }

    private fun registerAudioReceiver() {
        audioReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val p = prefs ?: return
                when (intent.action) {
                    AudioManager.ACTION_MICROPHONE_MUTE_CHANGED -> publishMicState(p)
                    "android.media.VOLUME_CHANGED_ACTION" -> {
                        val vol = currentVolumePercent()
                        if (vol != lastVolumePercent) {
                            lastVolumePercent = vol
                            publishRaw(HaDiscovery.volumeStateTopic(p.deviceId), vol.toString(), 1)
                        }
                    }
                    "android.media.STREAM_MUTE_CHANGED_ACTION" -> publishVolumeMuteState(p)
                }
            }
        }
        registerReceiver(audioReceiver, IntentFilter().apply {
            addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        })
    }

    // ── MQTT loop ─────────────────────────────────────────────────────────────

    private fun mqttLoop() {
        var backoff = 5_000L
        while (running.get()) {
            try {
                connectAndRun()
                backoff = 5_000L
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "MQTT error, retry in ${backoff / 1000}s: ${e.message}")
            }
            if (running.get()) sleep(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun connectAndRun() {
        val p = prefs ?: Prefs(this).also { prefs = it }
        val client = MqttClient(p.brokerUri, "portalha-${p.deviceId.take(8)}", MemoryPersistence())
        // Safety net: cap how long any synchronous operation can block, so an
        // unforeseen blocking call degrades to a 30s hiccup instead of a permanent hang.
        client.timeToWait = 30_000L

        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) { Log.w(TAG, "Connection lost: ${cause?.message}"); mqtt = null }
            override fun messageArrived(topic: String, msg: MqttMessage) {
                // Intercom traffic is binary (PCM audio) and must NOT be string-
                // decoded or run on the command executor — route it straight to
                // the manager from the raw bytes. handleRawMessage is non-blocking.
                if (intercom?.handleRawMessage(topic, msg.payload) == true) return
                val payload = msg.toString().trim()
                Log.i(TAG, "messageArrived: topic=$topic payload=$payload")
                runCatching {
                    commandExecutor.submit {
                        runCatching { handleMessage(topic, payload, p) }
                            .onFailure { Log.w(TAG, "command handler failed: ${it.message}") }
                    }
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        client.connect(MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 15
            keepAliveInterval = 30
            maxInflight = 100
            if (p.username.isNotEmpty()) { userName = p.username; password = p.password.toCharArray() }
            setWill(HaDiscovery.stateTopic(p.deviceId), "OFF".toByteArray(), 1, true)
        })
        mqtt = client
        Log.i(TAG, "MQTT connected to ${p.brokerUri}")

        // Purge retained commands left by old builds BEFORE subscribing, so the
        // broker has nothing stale to replay at us (screen OFF, camera OFF, …).
        HaDiscovery.commandTopics(p.deviceId).forEach { client.publish(it, emptyRetained()) }

        // Subscriptions
        listOfNotNull(
            HaDiscovery.commandTopic(p.deviceId),
            HaDiscovery.sensitivityCommandTopic(p.deviceId),
            HaDiscovery.micMuteCommandTopic(p.deviceId),
            HaDiscovery.volumeCommandTopic(p.deviceId),
            HaDiscovery.volumeMuteCommandTopic(p.deviceId),
            HaDiscovery.soundCommandTopic(p.deviceId),
            HaDiscovery.brightnessCommandTopic(p.deviceId),
            if (p.cameraServiceEnabled) HaDiscovery.cameraCommandTopic(p.deviceId) else null,
            // motion can be enabled live by the camera-ON cascade, so subscribe
            // whenever the camera service is on
            if (p.cameraServiceEnabled) HaDiscovery.motionSensitivityCommandTopic(p.deviceId) else null,
            if (p.cameraServiceEnabled) HaDiscovery.motionEnableCommandTopic(p.deviceId) else null,
            if (p.cameraServiceEnabled) HaDiscovery.streamEnableCommandTopic(p.deviceId) else null,
            HaDiscovery.presenceEnableCommandTopic(p.deviceId),
            HaDiscovery.screenTimeoutCommandTopic(p.deviceId),
            HaDiscovery.screenTimeoutMinsCommandTopic(p.deviceId),
            if (sensorBridge?.hasTemperature == true) HaDiscovery.tempOffsetCommandTopic(p.deviceId) else null,
            HaDiscovery.haTokenCommandTopic(p.deviceId)
        ).forEach { client.subscribe(it, 1) }

        // Intercom: subscribe to presence/lock/audio and announce ourselves.
        intercom?.subscriptions()?.forEach { (topic, qos) -> client.subscribe(topic, qos) }
        intercom?.publishPresence()

        // Clear stale retained entities from old builds
        HaDiscovery.staleTopics(p.deviceId).forEach { topic -> client.publish(topic, emptyRetained()) }

        // Discovery
        publishDiscovery(client, p)

        // Initial states
        val pm = getSystemService(PowerManager::class.java)
        publishState(if (pm.isInteractive) "ON" else "OFF")
        publishSensitivityState(p)
        publishMicState(p)
        publishVolumeState(p)
        publishVolumeMuteState(p)
        publishBrightnessState(p)
        publishDisplayStates(p)
        publishRaw(HaDiscovery.ipStateTopic(p.deviceId), localIp() ?: "unknown", 1, retained = true)
        if (sensorBridge?.hasTemperature == true)
            publishRaw(HaDiscovery.tempOffsetStateTopic(p.deviceId), "%.1f".format(p.tempOffset), 1, retained = true)
        if (p.cameraServiceEnabled) {
            publishRaw(HaDiscovery.cameraStateTopic(p.deviceId), if (cameraActive) "ON" else "OFF", 1, retained = true)
            publishFeatureSwitchStates(p)
            if (p.motionEnabled) publishMotionSensitivityState(p)
            // Restore desired camera state after an app restart / reboot
            // (commands are no longer retained on the broker, so we do this ourselves).
            if (p.cameraOn) {
                Log.i(TAG, "restoring camera ON (persisted desired state)")
                applyCameraState(p)
            }
        }

        updateNotification("Connected · ${p.brokerHost}")

        try {
            while (running.get() && client.isConnected) {
                sleep(5_000)
                pollChangedStates(p)
            }
        } finally {
            runCatching { intercom?.clearPresence() }   // retract our retained presence
            mqtt = null
            runCatching { client.disconnect(0) }
        }
    }

    private fun publishDiscovery(client: MqttClient, p: Prefs) {
        fun pub(topic: String, payload: String) = client.publish(topic, retained(payload))

        pub(HaDiscovery.discoveryTopic(p.deviceId), HaDiscovery.configPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.ipDiscoveryTopic(p.deviceId), HaDiscovery.ipConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.lightDiscoveryTopic(p.deviceId), HaDiscovery.lightConfigPayload(p.deviceId, p.deviceName))
        for (axis in listOf("x", "y", "z"))
            pub(HaDiscovery.accelDiscoveryTopic(p.deviceId, axis), HaDiscovery.accelConfigPayload(p.deviceId, p.deviceName, axis))

        // RGB and temperature are hardware-dependent: Portal has the RGB sensor,
        // Portal+ has ambient temperature instead. Publish only what exists;
        // clear the other so HA doesn't show a dead entity.
        if (sensorBridge?.hasRgb == true) {
            for (ch in listOf("r", "g", "b"))
                pub(HaDiscovery.rgbDiscoveryTopic(p.deviceId, ch), HaDiscovery.rgbConfigPayload(p.deviceId, p.deviceName, ch))
        } else {
            for (ch in listOf("r", "g", "b"))
                client.publish(HaDiscovery.rgbDiscoveryTopic(p.deviceId, ch), emptyRetained())
        }
        if (sensorBridge?.hasTemperature == true) {
            pub(HaDiscovery.tempDiscoveryTopic(p.deviceId), HaDiscovery.tempConfigPayload(p.deviceId, p.deviceName))
            pub(HaDiscovery.tempOffsetDiscoveryTopic(p.deviceId), HaDiscovery.tempOffsetConfigPayload(p.deviceId, p.deviceName))
        } else {
            client.publish(HaDiscovery.tempDiscoveryTopic(p.deviceId), emptyRetained())
            client.publish(HaDiscovery.tempOffsetDiscoveryTopic(p.deviceId), emptyRetained())
        }

        pub(HaDiscovery.tapDiscoveryTopic(p.deviceId), HaDiscovery.tapConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.sensitivityDiscoveryTopic(p.deviceId), HaDiscovery.sensitivityConfigPayload(p.deviceId, p.deviceName))
        // The Sound Level sensor only exists when we hold the mic; in coexist mode the
        // mic is released, so remove the entity instead of publishing a stale value.
        if (p.coexistVoiceAssistant)
            client.publish(HaDiscovery.soundDiscoveryTopic(p.deviceId), emptyRetained())
        else
            pub(HaDiscovery.soundDiscoveryTopic(p.deviceId), HaDiscovery.soundConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.micMuteDiscoveryTopic(p.deviceId), HaDiscovery.micMuteConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.volumeDiscoveryTopic(p.deviceId), HaDiscovery.volumeConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.volumeMuteDiscoveryTopic(p.deviceId), HaDiscovery.volumeMuteConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.doorbellDiscoveryTopic(p.deviceId), HaDiscovery.doorbellConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.alertDiscoveryTopic(p.deviceId), HaDiscovery.alertConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.brightnessDiscoveryTopic(p.deviceId), HaDiscovery.brightnessConfigPayload(p.deviceId, p.deviceName))
        // HA long-lived token, settable from HA (for the Jarvis tool-provider's smart-home control).
        pub(HaDiscovery.haTokenDiscoveryTopic(p.deviceId), HaDiscovery.haTokenConfigPayload(p.deviceId, p.deviceName))

        // Camera, motion-enable and streaming-enable switches exist only while
        // the camera service is enabled; motion entities additionally require
        // motion detection. Disabled entities are cleared from HA so they can't
        // be used to control the device.
        if (p.cameraServiceEnabled) {
            pub(HaDiscovery.cameraDiscoveryTopic(p.deviceId), HaDiscovery.cameraConfigPayload(p.deviceId, p.deviceName))
            pub(HaDiscovery.motionEnableDiscoveryTopic(p.deviceId), HaDiscovery.motionEnableConfigPayload(p.deviceId, p.deviceName))
            pub(HaDiscovery.streamEnableDiscoveryTopic(p.deviceId), HaDiscovery.streamEnableConfigPayload(p.deviceId, p.deviceName))
        } else {
            client.publish(HaDiscovery.cameraDiscoveryTopic(p.deviceId), emptyRetained())
            client.publish(HaDiscovery.motionEnableDiscoveryTopic(p.deviceId), emptyRetained())
            client.publish(HaDiscovery.streamEnableDiscoveryTopic(p.deviceId), emptyRetained())
        }
        if (p.cameraServiceEnabled && p.motionEnabled) {
            pub(HaDiscovery.motionDiscoveryTopic(p.deviceId), HaDiscovery.motionConfigPayload(p.deviceId, p.deviceName))
            pub(HaDiscovery.motionSensitivityDiscoveryTopic(p.deviceId), HaDiscovery.motionSensitivityConfigPayload(p.deviceId, p.deviceName))
        } else {
            HaDiscovery.motionEntityTopics(p.deviceId).forEach { client.publish(it, emptyRetained()) }
        }

        // Screen-timeout controls always present; presence sensor only while enabled.
        pub(HaDiscovery.presenceEnableDiscoveryTopic(p.deviceId), HaDiscovery.presenceEnableConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.screenTimeoutDiscoveryTopic(p.deviceId), HaDiscovery.screenTimeoutConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.screenTimeoutMinsDiscoveryTopic(p.deviceId), HaDiscovery.screenTimeoutMinsConfigPayload(p.deviceId, p.deviceName))
        if (p.presenceEnabled) {
            pub(HaDiscovery.presenceDiscoveryTopic(p.deviceId), HaDiscovery.presenceConfigPayload(p.deviceId, p.deviceName))
        } else {
            client.publish(HaDiscovery.presenceDiscoveryTopic(p.deviceId), emptyRetained())
        }
    }

    // Presence sensor discovery toggled live (when presence is enabled/disabled
    // from the device UI without a reconnect).
    private fun publishDisplayDiscovery(p: Prefs) {
        if (p.presenceEnabled) {
            publishRaw(HaDiscovery.presenceDiscoveryTopic(p.deviceId),
                HaDiscovery.presenceConfigPayload(p.deviceId, p.deviceName), 1, retained = true)
        } else {
            publishRaw(HaDiscovery.presenceDiscoveryTopic(p.deviceId), "", 1, retained = true)
        }
    }

    private fun pollChangedStates(p: Prefs) {
        val vol = currentVolumePercent()
        if (vol != lastVolumePercent) { lastVolumePercent = vol; publishRaw(HaDiscovery.volumeStateTopic(p.deviceId), vol.toString(), 1) }

        val muted = getSystemService(AudioManager::class.java).isStreamMute(AudioManager.STREAM_MUSIC)
        if (muted != lastVolumeMuted) publishVolumeMuteState(p)

        val bright = currentBrightnessPercent()
        if (bright != lastBrightnessPercent) { lastBrightnessPercent = bright; publishRaw(HaDiscovery.brightnessStateTopic(p.deviceId), bright.toString(), 1) }

        if (motionPublished && System.currentTimeMillis() - lastMotionMs > MOTION_CLEAR_MS) {
            motionPublished = false
            publishRaw(HaDiscovery.motionStateTopic(p.deviceId), "OFF", 0)
        }

        // Clears enhanced-sound presence once the hold window lapses.
        recomputePresence(p)
    }

    // ── Command router ────────────────────────────────────────────────────────

    private fun handleMessage(topic: String, payload: String, p: Prefs) {
        when (topic) {
            HaDiscovery.commandTopic(p.deviceId)                  -> handleScreenCommand(payload)
            HaDiscovery.sensitivityCommandTopic(p.deviceId)       -> handleSensitivityCommand(payload, p)
            HaDiscovery.micMuteCommandTopic(p.deviceId)           -> handleMicMuteCommand(payload, p)
            HaDiscovery.volumeCommandTopic(p.deviceId)            -> handleVolumeCommand(payload, p)
            HaDiscovery.volumeMuteCommandTopic(p.deviceId)        -> handleVolumeMuteCommand(payload, p)
            HaDiscovery.soundCommandTopic(p.deviceId)             -> TonePlayer.play(payload)
            HaDiscovery.brightnessCommandTopic(p.deviceId)        -> handleBrightnessCommand(payload, p)
            HaDiscovery.cameraCommandTopic(p.deviceId)            -> handleCameraCommand(payload, p)
            HaDiscovery.motionSensitivityCommandTopic(p.deviceId) -> handleMotionSensitivityCommand(payload, p)
            HaDiscovery.motionEnableCommandTopic(p.deviceId)      -> handleMotionEnableCommand(payload, p)
            HaDiscovery.streamEnableCommandTopic(p.deviceId)      -> handleStreamEnableCommand(payload, p)
            HaDiscovery.presenceEnableCommandTopic(p.deviceId)    -> handlePresenceEnableCommand(payload, p)
            HaDiscovery.screenTimeoutCommandTopic(p.deviceId)     -> handleScreenTimeoutCommand(payload, p)
            HaDiscovery.screenTimeoutMinsCommandTopic(p.deviceId) -> handleScreenTimeoutMinsCommand(payload, p)
            HaDiscovery.tempOffsetCommandTopic(p.deviceId)        -> handleTempOffsetCommand(payload, p)
            HaDiscovery.haTokenCommandTopic(p.deviceId)           -> handleHaTokenCommand(payload, p)
        }
    }

    private fun handleScreenCommand(cmd: String) {
        when (cmd.uppercase()) {
            "ON" -> ScreenControl.wake(this)
            "OFF" -> ScreenControl.sleep()
        }
    }

    private fun handleSensitivityCommand(payload: String, p: Prefs) {
        p.tapThreshold = (payload.toFloatOrNull() ?: return).coerceIn(2f, 15f)
        publishSensitivityState(p)
    }

    private fun handleMicMuteCommand(payload: String, p: Prefs) {
        val muted = payload.uppercase() == "ON"
        getSystemService(AudioManager::class.java).setMicrophoneMute(muted)
        publishMicState(p)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, if (muted) "Microphone muted" else "Microphone unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleVolumeCommand(payload: String, p: Prefs) {
        val pct = (payload.toIntOrNull() ?: return).coerceIn(0, 100)
        val am = getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100, 0)
        publishVolumeState(p)
    }

    private fun handleVolumeMuteCommand(payload: String, p: Prefs) {
        val muted = payload.uppercase() == "ON"
        getSystemService(AudioManager::class.java).adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        publishVolumeMuteState(p)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, if (muted) "Volume muted" else "Volume unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleBrightnessCommand(payload: String, p: Prefs) {
        val pct = (payload.toIntOrNull() ?: return).coerceIn(0, 100)
        try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(0, 255))
            publishBrightnessState(p)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SETTINGS not granted — run: adb shell appops set $packageName WRITE_SETTINGS allow")
        }
    }

    private fun handleCameraCommand(cmd: String, p: Prefs) {
        if (!p.cameraServiceEnabled) { Log.w(TAG, "camera cmd '$cmd' ignored — camera service disabled"); return }
        Log.i(TAG, "camera cmd: $cmd  stream=${p.streamEnabled} motion=${p.motionEnabled} cameraActive=$cameraActive")
        when (cmd.uppercase()) {
            "ON" -> {
                p.cameraOn = true
                // Pick a mode if none is set — restore the last one (stream and
                // motion are mutually exclusive: RTSP owns the camera).
                if (!p.motionEnabled && !p.streamEnabled) {
                    if (p.lastMotionEnabled && !p.lastStreamEnabled) p.motionEnabled = true
                    else p.streamEnabled = true   // default to streaming
                }
                applyFeatureState(p)
                applyCameraState(p)
            }
            "OFF" -> {
                p.cameraOn = false
                if (p.motionEnabled || p.streamEnabled) {
                    p.lastMotionEnabled = p.motionEnabled
                    p.lastStreamEnabled = p.streamEnabled
                    p.motionEnabled = false
                    p.streamEnabled = false
                }
                motionDetector.reset()
                motionPublished = false
                publishRaw(HaDiscovery.motionStateTopic(p.deviceId), "OFF", 0)
                applyFeatureState(p)
                applyCameraState(p)
            }
        }
    }

    private fun handleMotionSensitivityCommand(payload: String, p: Prefs) {
        p.motionSensitivity = (payload.toIntOrNull() ?: return).coerceIn(1, 100)
        publishMotionSensitivityState(p)
    }

    // HA switches mirroring the in-app motion/streaming toggles. Motion and
    // streaming are mutually exclusive — each opens Camera 0 itself, so turning
    // one on turns the other off.
    private fun handleMotionEnableCommand(payload: String, p: Prefs) {
        if (!p.cameraServiceEnabled) { Log.w(TAG, "motion enable cmd ignored — camera service disabled"); return }
        when (payload.uppercase()) {
            "ON" -> {
                p.motionEnabled = true
                p.streamEnabled = false
                p.cameraOn = true
                applyFeatureState(p); applyCameraState(p)
            }
            "OFF" -> {
                p.motionEnabled = false
                if (p.cameraOn) { p.cameraOn = false; p.lastMotionEnabled = true; p.lastStreamEnabled = false }
                applyFeatureState(p); applyCameraState(p)
            }
        }
    }

    private fun handleStreamEnableCommand(payload: String, p: Prefs) {
        if (!p.cameraServiceEnabled) { Log.w(TAG, "stream enable cmd ignored — camera service disabled"); return }
        when (payload.uppercase()) {
            "ON" -> {
                p.streamEnabled = true
                p.motionEnabled = false
                p.cameraOn = true
                applyFeatureState(p); applyCameraState(p)
            }
            "OFF" -> {
                p.streamEnabled = false
                if (p.cameraOn) { p.cameraOn = false; p.lastStreamEnabled = true; p.lastMotionEnabled = false }
                applyFeatureState(p); applyCameraState(p)
            }
        }
    }

    // Single authority for Camera 0 ownership. RTSP streaming and motion are
    // mutually exclusive (each opens the camera directly). @Synchronized because
    // the MQTT-restore path and ensureCamera (commandExecutor) can call it
    // concurrently — without it, both start RTSP and the 2nd collides on port 8554.
    @Synchronized
    private fun applyCameraState(p: Prefs) {
        val on = p.cameraServiceEnabled && p.cameraOn
        when {
            on && p.streamEnabled -> {
                stopCameraStreamSilently()   // RTSP needs Camera 0
                val r = rtspStreamer ?: RtspStreamer(this).also { rtspStreamer = it }
                r.rotationOffset = p.streamRotation
                if (!r.isStreaming) {
                    // withAudio=false → NoAudioSource: the RTSP stream must NOT open
                    // the mic, or it starves/garbles Portal calls. (Audio is silent/
                    // useless anyway; a real mic-share is a future follow-up.)
                    val ok = r.start(1280, 720, 15, 2_000_000, withAudio = false)
                    cameraActive = ok
                    publishRaw(HaDiscovery.cameraStateTopic(p.deviceId), if (ok) "ON" else "OFF", 1, retained = true)
                    if (!ok) Log.w(TAG, "RTSP failed to start")
                }
            }
            on && p.motionEnabled -> {
                rtspStreamer?.stop()
                val cs = cameraStream ?: buildCameraStream(p).also { cameraStream = it }
                if (!cs.isActive) cs.start()   // onStateChange publishes camera ON
            }
            else -> {
                rtspStreamer?.stop()
                stopCameraStreamSilently()
                if (cameraActive) {
                    cameraActive = false
                    publishRaw(HaDiscovery.cameraStateTopic(p.deviceId), "OFF", 1, retained = true)
                }
            }
        }
    }

    // Stop the motion CameraStream without its onStateChange firing a stale OFF
    // (which would race an RTSP ON publish — the camera-state flicker bug).
    private fun stopCameraStreamSilently() {
        cameraStream?.let { it.onStateChange = null; it.stop() }
        cameraStream = null
    }

    // RTSP-Server 1.3.0 throws an UNCAUGHT InterruptedException from its accept
    // thread when a stream is stopped (which we must do to re-prepare the encoder
    // for a rotation change) — that would kill the whole app. Swallow ONLY that
    // specific library exception; let every other crash propagate normally.
    private fun installRtspCrashGuard() {
        if (crashGuardInstalled) return
        crashGuardInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (ex is InterruptedException &&
                ex.stackTrace.any { it.className.contains("rtspserver", ignoreCase = true) }) {
                Log.w(TAG, "swallowed RtspServer InterruptedException on ${thread.name}")
            } else {
                prev?.uncaughtException(thread, ex)
            }
        }
    }

    // ── Accelerometer auto-rotate ─────────────────────────────────────────────

    // OrientationEventListener fires continuously; debounce so a held new
    // orientation (1.2s) triggers exactly one restart, ignoring wobble near the
    // 45° boundaries.
    private fun onDeviceOrientation(snapped: Int) {
        if (snapped == lastDeviceOrientation) {           // settled back; cancel pending
            pendingDeviceOrientation = -1
            timeoutHandler.removeCallbacks(orientationApply)
            return
        }
        if (snapped != pendingDeviceOrientation) {
            pendingDeviceOrientation = snapped
            timeoutHandler.removeCallbacks(orientationApply)
            timeoutHandler.postDelayed(orientationApply, 1200)
        }
    }

    private fun commitDeviceOrientation() {
        val snapped = pendingDeviceOrientation
        pendingDeviceOrientation = -1
        if (snapped == -1 || snapped == lastDeviceOrientation) return
        lastDeviceOrientation = snapped
        val r = rtspStreamer ?: return
        // Stream rotation to keep the picture upright. On aloha (square FOV) the user
        // wants portrait->90, landscape->0; other models use the generic (device+90).
        // Only non-Portal+ models reach here (Portal+ auto-rotate is disabled — fixed cam).
        val auto = (snapped + 90) % 360
        Log.i(TAG, "orientation commit: snapped=$snapped -> auto=$auto (offset=${r.rotationOffset}, was=${r.autoRotation})")
        if (auto == r.autoRotation) return   // no actual change — leave the stream alone
        r.autoRotation = auto
        commandExecutor.submit {
            if (r.isStreaming) r.restart()
        }
    }

    // ── Presence + screen-off timer ───────────────────────────────────────────

    private fun handlePresenceEnableCommand(payload: String, p: Prefs) {
        p.presenceEnabled = payload.uppercase() == "ON"
        reconcilePresence(p)
        publishDisplayDiscovery(p)
        publishDisplayStates(p)
    }

    private fun handleScreenTimeoutCommand(payload: String, p: Prefs) {
        p.screenTimeoutEnabled = payload.uppercase() == "ON"
        lastActivityMs = System.currentTimeMillis()  // fresh countdown
        publishDisplayStates(p)
    }

    private fun handleScreenTimeoutMinsCommand(payload: String, p: Prefs) {
        p.screenTimeoutMinutes = payload.toIntOrNull() ?: return
        lastActivityMs = System.currentTimeMillis()
        publishDisplayStates(p)
    }

    private fun handleTempOffsetCommand(payload: String, p: Prefs) {
        p.tempOffset = payload.toFloatOrNull() ?: return
        sensorBridge?.republishTemperature()   // reflect immediately in HA
        publishRaw(HaDiscovery.tempOffsetStateTopic(p.deviceId), "%.1f".format(p.tempOffset), 1, retained = true)
    }

    // HA long-lived token set from Home Assistant (the "HA Token" text entity).
    // Stored for the Jarvis tool-provider's smart-home control. Log only the length,
    // never the secret. No state echo (the entity is optimistic / write-only).
    private fun handleHaTokenCommand(payload: String, p: Prefs) {
        val token = payload.trim()
        if (token.isEmpty()) return
        p.haToken = token
        Log.i(TAG, "ha token set from Home Assistant (len=${token.length})")
    }

    private fun hasReadLogs() =
        checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    // Start/stop the face-presence monitor to match prefs + permission, then
    // publish the combined (face + enhanced-sound) presence state.
    private fun reconcilePresence(p: Prefs) {
        if (p.presenceEnabled && hasReadLogs()) {
            if (presenceMonitor == null) {
                presenceMonitor = PresenceMonitor { present -> onPresenceChange(present) }.also { it.start() }
            }
        } else {
            presenceMonitor?.release()
            presenceMonitor = null
            facePresent = false
            // Without READ_LOGS face detection can't run; enhanced (sound) presence
            // still can, so only warn when there's no fallback configured.
            if (p.presenceEnabled && !hasReadLogs() && !p.enhancedPresenceEnabled)
                Log.w(TAG, "presence enabled but READ_LOGS not granted — run: adb shell pm grant $packageName android.permission.READ_LOGS")
        }
        if (p.presenceEnabled) {
            recomputePresence(p)
        } else {
            lastPublishedPresence = null
            publishRaw(HaDiscovery.presenceStateTopic(p.deviceId), "OFF", 1, retained = true)
        }
    }

    private fun onPresenceChange(present: Boolean) {
        facePresent = present
        prefs?.let { recomputePresence(it) }
    }

    // Apply the coexist-with-voice-assistant setting live (toggled from settings).
    // ON  → release the mic: stop SoundMonitor, drop the Sound Level sensor from HA,
    //        and put the intercom on on-demand capture. OFF → reclaim the mic + sensor.
    // Idempotent — the isRunning() guards make repeated apply calls a no-op.
    private fun applyCoexist(p: Prefs) {
        val coexist = p.coexistVoiceAssistant
        intercom?.attachSoundMonitor(if (coexist) null else soundMonitor)
        intercom?.setOnDemandCapture(coexist)
        if (coexist) {
            if (soundMonitor?.isRunning() == true) {
                soundMonitor?.stop()
                Log.i(TAG, "coexist: released mic for external voice assistant")
            }
            lastSoundLevel = -1
            // Can't update the Sound Level sensor without the mic — remove it from HA.
            publishRaw(HaDiscovery.soundDiscoveryTopic(p.deviceId), "", 1, retained = true)
        } else {
            if (soundMonitor?.isRunning() == false) {
                soundMonitor?.start()
                Log.i(TAG, "coexist: off — reclaimed mic for the sound sensor")
            }
            publishRaw(HaDiscovery.soundDiscoveryTopic(p.deviceId),
                HaDiscovery.soundConfigPayload(p.deviceId, p.deviceName), 1, retained = true)
        }
    }

    // Wake word matched — fire portal-wake's public handoff broadcast so the assistant
    // (Jarvis) wakes and takes the mic. We don't hand the mic back explicitly:
    // SoundMonitor yields on its own (its reads fail while the assistant records, then
    // it re-acquires when the assistant releases), and a cooldown blocks instant re-fire.
    private fun fireWakeHandoff() {
        val p = prefs ?: return
        val id = p.wakePhrase.trim().lowercase().substringAfterLast(' ').ifEmpty { "jarvis" }
        val pkg = p.wakeAssistantPackage
        fun broadcastAndYield() {
            runCatching {
                sendBroadcast(Intent("com.portal.wake.action.WAKE")
                    .setPackage(pkg).putExtra("com.portal.wake.extra.ID", id))
                Log.i(TAG, "wake: fired handoff -> $pkg (id=$id)")
            }.onFailure { Log.w(TAG, "wake: handoff failed: ${it.message}") }
            yieldMicForWake()
        }
        // Android 10+ denies the mic to a foreground service started while the app is in
        // the background, so the assistant hears silence when woken. Bring it to the
        // foreground first (our SYSTEM_ALERT_WINDOW allows the background-activity-start),
        // then start the conversation. Android 9 captures fine woken-in-background, so it
        // stays subtle (no takeover) there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wakeHandler.post {
                runCatching {
                    packageManager.getLaunchIntentForPackage(pkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it); Log.i(TAG, "wake: brought $pkg to foreground") }
                }.onFailure { Log.w(TAG, "wake: foreground launch failed: ${it.message}") }
            }
            wakeHandler.postDelayed({ broadcastAndYield() }, 600L)   // let the activity resume first
        } else {
            broadcastAndYield()
        }
    }

    // After the wake fires, the assistant needs the mic but our SoundMonitor is holding
    // it — so the assistant hears silence ("ignores you"). Release the mic now and
    // reclaim once the assistant stops recording. We poll activeRecordingConfigurations
    // (after a short settle so our own capture has stopped and the assistant has started):
    // once we've SEEN a recording and it drops to none, the conversation is over. A long
    // timeout always recovers in case the handoff never took.
    @Volatile private var micYieldedForWake = false
    @Volatile private var wakeConsumerSeen = false
    @Volatile private var wakeYieldStartMs = 0L
    private val wakeHandler = Handler(Looper.getMainLooper())
    private val reclaimPoll = object : Runnable {
        override fun run() {
            if (!micYieldedForWake) return
            val active = runCatching {
                getSystemService(AudioManager::class.java)?.activeRecordingConfigurations?.size ?: 0
            }.getOrDefault(0)
            if (active > 0) wakeConsumerSeen = true
            val elapsed = System.currentTimeMillis() - wakeYieldStartMs
            if ((wakeConsumerSeen && active == 0) || elapsed > 120_000L) {
                reclaimMicAfterWake(if (wakeConsumerSeen) "assistant done" else "timeout")
            } else {
                wakeHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private fun yieldMicForWake() {
        if (micYieldedForWake) return
        micYieldedForWake = true
        wakeConsumerSeen = false
        wakeYieldStartMs = System.currentTimeMillis()
        soundMonitor?.stop()        // free the mic; the wake detector idles on an empty queue
        Log.i(TAG, "wake: yielded mic to assistant")
        wakeHandler.postDelayed(reclaimPoll, 1_500L)   // settle, then watch for the assistant to finish
    }

    private fun reclaimMicAfterWake(reason: String) {
        if (!micYieldedForWake) return
        micYieldedForWake = false
        wakeHandler.removeCallbacks(reclaimPoll)
        // Restart the warm mic whenever we normally hold it (not just wake mode) — the sound
        // sensor / enhanced presence need it too, and an assist-button handoff also yields it.
        val coexist = prefs?.let { it.coexistVoiceAssistant && !it.wakeWordEnabled } ?: false
        if (!coexist && soundMonitor?.isRunning() == false) soundMonitor?.start()
        // The assistant may still be speaking its reply; ignore wake matches briefly so
        // its audio (echoed back through the mic) can't immediately re-trigger the handoff.
        wakeDetector?.pauseMatching(3_000L)
        // On Android 10+ we brought the assistant to the front to capture; the conversation
        // is over, so bring our dashboard back to the kiosk.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startActivity(Intent(this, DashboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        Log.i(TAG, "wake: reclaimed mic ($reason)")
    }

    // Start/stop the wake detector to match the pref (live, from the apply path). Wake
    // needs our mic, so it ensures SoundMonitor is running (coexist is off when wake is
    // on — they're mutually exclusive). Idempotent via the isRunning() guards.
    private fun reconcileWake(p: Prefs) {
        val phraseChanged = startedWakePhrase != null && startedWakePhrase != p.wakePhrase
        wakeDetector?.phrase = p.wakePhrase
        if (p.wakeWordEnabled) {
            if (soundMonitor?.isRunning() == false) soundMonitor?.start()
            if (wakeDetector?.isRunning() == false) {
                wakeDetector?.start(); startedWakePhrase = p.wakePhrase
            } else if (phraseChanged) {
                // The grammar is fixed when the recognizer is created, so a new phrase
                // needs a fresh recognizer. Stop now and restart after a short gap so the
                // old decode thread has exited (it polls on a 200 ms timeout) before the
                // new one starts — otherwise the two race on the running flag.
                wakeDetector?.stop()
                startedWakePhrase = p.wakePhrase
                wakeHandler.postDelayed({
                    if (prefs?.wakeWordEnabled == true) wakeDetector?.start()
                }, 400L)
            }
        } else if (wakeDetector?.isRunning() == true) {
            wakeDetector?.stop()
            startedWakePhrase = null
        }
    }

    // Combined presence = Meta face detection OR (when enhanced) recent ambient
    // sound. Publishes only on change. Called from the presence monitor, the sound
    // callback, the display-settings apply path, and the periodic poll.
    private fun recomputePresence(p: Prefs) {
        if (!p.presenceEnabled) return
        val soundActive = p.enhancedPresenceEnabled && lastSoundActivityMs > 0 &&
            System.currentTimeMillis() - lastSoundActivityMs < SOUND_PRESENCE_HOLD_MS
        val present = facePresent || soundActive
        if (present) lastActivityMs = System.currentTimeMillis()   // keeps the screen awake
        if (lastPublishedPresence != present) {
            lastPublishedPresence = present
            publishRaw(HaDiscovery.presenceStateTopic(p.deviceId), if (present) "ON" else "OFF", 1, retained = true)
            Log.i(TAG, "presence -> ${if (present) "DETECTED" else "CLEAR"} (face=$facePresent sound=$soundActive)")
        }
    }

    // Runs every 15s on its own thread (independent of MQTT). Sleeps the screen
    // once it has been idle — no presence and no wake — for the configured time.
    private fun checkScreenTimeout() {
        val p = prefs ?: return
        if (!p.screenTimeoutEnabled || !screenOn) return
        // Presence (face or enhanced-sound) holds the screen awake and resets the countdown.
        recomputePresence(p)
        if (lastPublishedPresence == true) { lastActivityMs = System.currentTimeMillis(); return }
        if (System.currentTimeMillis() - lastActivityMs >= p.screenTimeoutMinutes * 60_000L) {
            Log.i(TAG, "screen timeout: ${p.screenTimeoutMinutes}m idle — sleeping screen")
            ScreenControl.sleep()
        }
    }

    private fun publishDisplayStates(p: Prefs) {
        publishRaw(HaDiscovery.presenceEnableStateTopic(p.deviceId), if (p.presenceEnabled) "ON" else "OFF", 1, retained = true)
        publishRaw(HaDiscovery.screenTimeoutStateTopic(p.deviceId), if (p.screenTimeoutEnabled) "ON" else "OFF", 1, retained = true)
        publishRaw(HaDiscovery.screenTimeoutMinsStateTopic(p.deviceId), p.screenTimeoutMinutes.toString(), 1, retained = true)
    }

    // Bring the HA motion entities and switch states in line with the current
    // motion/stream prefs. Camera ownership (RTSP vs motion) is handled
    // separately by applyCameraState.
    private fun applyFeatureState(p: Prefs) {
        if (p.motionEnabled) {
            publishRaw(HaDiscovery.motionDiscoveryTopic(p.deviceId),
                HaDiscovery.motionConfigPayload(p.deviceId, p.deviceName), 1, retained = true)
            publishRaw(HaDiscovery.motionSensitivityDiscoveryTopic(p.deviceId),
                HaDiscovery.motionSensitivityConfigPayload(p.deviceId, p.deviceName), 1, retained = true)
            publishMotionSensitivityState(p)
        } else {
            motionDetector.reset()
            motionPublished = false
            HaDiscovery.motionEntityTopics(p.deviceId).forEach { publishRaw(it, "", 1, retained = true) }
        }

        publishFeatureSwitchStates(p)
    }

    private fun publishFeatureSwitchStates(p: Prefs) {
        publishRaw(HaDiscovery.motionEnableStateTopic(p.deviceId), if (p.motionEnabled) "ON" else "OFF", 1, retained = true)
        publishRaw(HaDiscovery.streamEnableStateTopic(p.deviceId), if (p.streamEnabled) "ON" else "OFF", 1, retained = true)
    }

    // ── State publishers ──────────────────────────────────────────────────────

    fun publishState(state: String) {
        val p = prefs ?: Prefs(this)
        publishRaw(HaDiscovery.stateTopic(p.deviceId), state, 1, retained = true)
    }

    private fun publishSensitivityState(p: Prefs) =
        publishRaw(HaDiscovery.sensitivityStateTopic(p.deviceId), "%.1f".format(p.tapThreshold), 1, retained = true)

    private fun publishMicState(p: Prefs) =
        publishRaw(HaDiscovery.micMuteStateTopic(p.deviceId),
            if (getSystemService(AudioManager::class.java).isMicrophoneMute) "ON" else "OFF", 1, retained = true)

    private fun publishVolumeState(p: Prefs) {
        lastVolumePercent = currentVolumePercent()
        publishRaw(HaDiscovery.volumeStateTopic(p.deviceId), lastVolumePercent.toString(), 1, retained = true)
    }

    private fun publishVolumeMuteState(p: Prefs) {
        val muted = getSystemService(AudioManager::class.java).isStreamMute(AudioManager.STREAM_MUSIC)
        lastVolumeMuted = muted
        publishRaw(HaDiscovery.volumeMuteStateTopic(p.deviceId), if (muted) "ON" else "OFF", 1, retained = true)
    }

    private fun publishBrightnessState(p: Prefs) {
        lastBrightnessPercent = currentBrightnessPercent()
        publishRaw(HaDiscovery.brightnessStateTopic(p.deviceId), lastBrightnessPercent.toString(), 1, retained = true)
    }

    private fun publishMotionSensitivityState(p: Prefs) =
        publishRaw(HaDiscovery.motionSensitivityStateTopic(p.deviceId), p.motionSensitivity.toString(), 1, retained = true)

    // ── Device state helpers ──────────────────────────────────────────────────

    private fun currentVolumePercent(): Int {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else 0
    }

    private fun currentBrightnessPercent(): Int =
        (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255).coerceIn(0, 100)

    // ── Camera overlay (keeps process in "visible" state for background camera) ─

    private fun showCameraOverlay() {
        if (cameraOverlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — run: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
            return
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val wm = getSystemService(WindowManager::class.java)
                val v = View(this)
                val params = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).also { it.alpha = 0f }
                wm.addView(v, params)
                cameraOverlay = v
                Log.i(TAG, "Camera overlay shown — process is now in visible state")
            }.onFailure { Log.w(TAG, "Could not show camera overlay: ${it.message}") }
        }
    }

    private fun hideCameraOverlay() {
        val v = cameraOverlay ?: return
        cameraOverlay = null
        Handler(Looper.getMainLooper()).post {
            runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        }
    }

    // ── MQTT helpers ──────────────────────────────────────────────────────────

    private fun publishRaw(topic: String, payload: String, qos: Int = 0, retained: Boolean = false) {
        runCatching {
            mqtt?.publish(topic, MqttMessage(payload.toByteArray()).also { it.qos = qos; it.isRetained = retained })
        }
    }

    // Binary publish for the intercom (raw PCM frames, presence, lock). Never
    // called from the Paho callback thread — only the capture + MQTT threads.
    private fun publishBytes(topic: String, payload: ByteArray, qos: Int, retained: Boolean) {
        runCatching {
            mqtt?.publish(topic, MqttMessage(payload).also { it.qos = qos; it.isRetained = retained })
        }
    }

    // ── Intercom PTT overlays (named floating buttons) ────────────────────────

    // Show the configured talk buttons only while: the feature is on, this Portal
    // can transmit (not receive-only), AND the dashboard is in front. Otherwise
    // hide them — they don't float over other apps / the home screen.
    private fun reconcileIntercomOverlays() {
        val p = prefs ?: return
        val show = p.intercomOverlayEnabled && intercom?.canTransmit() == true && dashboardForeground
        if (!show) { hideIntercomOverlays(); return }

        // Seed a default "Talk → Everyone" button only on first-ever use — NOT after the
        // user deliberately deletes them all (else a deleted last button keeps coming back).
        val buttons = p.getIntercomButtons().ifEmpty {
            if (p.intercomButtonsConfigured()) mutableListOf()
            else mutableListOf(IntercomButton("Talk", "all")).also { p.setIntercomButtons(it) }
        }
        // Converge to the current config: rebuild only when it actually changed. Replaces a
        // fragile "already up → return" that left removed buttons on screen as touch traps.
        val sig = buttons.joinToString("|") { "${it.name}/${it.target}/${it.x}/${it.y}" }
        if (sig == shownOverlaySignature && intercomOverlays.size == buttons.size) return
        hideIntercomOverlays()
        shownOverlaySignature = sig
        buttons.forEachIndexed { i, b ->
            IntercomOverlay(
                this, b.name, b.x, b.y, i,
                onDown = { intercom?.startTalk(b.target) == true },
                onUp = { intercom?.stopTalk() },
                onMoved = { x, y -> saveIntercomButtonPosition(i, x, y) },
                onMoveMode = { active -> onOverlayMoveMode(active) },
                overDeleteZone = { cx, cy -> hitTestDeleteZone(cx, cy) },
                onDelete = { deleteIntercomButton(i) }
            ).also { intercomOverlays.add(it); it.show() }
        }
    }

    private fun saveIntercomButtonPosition(index: Int, x: Int, y: Int) {
        val p = prefs ?: return
        val list = p.getIntercomButtons()
        if (index in list.indices) {
            list[index] = list[index].copy(x = x, y = y)
            p.setIntercomButtons(list)
            // Keep the signature in sync so a later reconcile doesn't needlessly rebuild.
            shownOverlaySignature = list.joinToString("|") { "${it.name}/${it.target}/${it.x}/${it.y}" }
        }
    }

    // Delete-target geometry (bottom-centre) — shared by the visual target and the hit test.
    private fun deleteZone(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        val r = (44 * dm.density).toInt()
        return Triple(dm.widthPixels / 2, dm.heightPixels - (64 * dm.density).toInt(), r)
    }

    // A talk button entered/left move mode — show the delete target while any is moving.
    private fun onOverlayMoveMode(active: Boolean) {
        movingCount = (movingCount + if (active) 1 else -1).coerceAtLeast(0)
        if (movingCount > 0) {
            if (deleteTarget == null) {
                val (cx, cy, r) = deleteZone()
                deleteTarget = DeleteTargetOverlay(this, cx, cy, r * 2).also { it.show() }
            }
        } else {
            deleteTarget?.hide(); deleteTarget = null
        }
    }

    // True if a dragged button's centre is over the delete target; highlights it too.
    private fun hitTestDeleteZone(cx: Int, cy: Int): Boolean {
        val (zx, zy, r) = deleteZone()
        val dx = (cx - zx).toDouble(); val dy = (cy - zy).toDouble()
        val hit = dx * dx + dy * dy <= (r * 1.5) * (r * 1.5)   // generous drop radius
        deleteTarget?.setActive(hit)
        return hit
    }

    private fun deleteIntercomButton(index: Int) {
        val p = prefs ?: return
        val list = p.getIntercomButtons()
        if (index in list.indices) { list.removeAt(index); p.setIntercomButtons(list) }
        hideIntercomOverlays()          // clears move state + delete target
        reconcileIntercomOverlays()     // rebuild from the trimmed config
    }

    private fun hideIntercomOverlays() {
        intercomOverlays.forEach { it.hide() }
        intercomOverlays.clear()
        movingCount = 0
        deleteTarget?.hide(); deleteTarget = null
        shownOverlaySignature = null
    }

    private fun retained(payload: String) =
        MqttMessage(payload.toByteArray()).also { it.qos = 1; it.isRetained = true }

    private fun emptyRetained() =
        MqttMessage(ByteArray(0)).also { it.qos = 1; it.isRetained = true }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Portal HA Bridge", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Portal HA Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))

    private fun sleep(ms: Long) =
        try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
}
