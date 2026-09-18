package com.aeonos.portalha

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
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
import kotlin.concurrent.thread

class BridgeService : Service() {

    companion object {
        private const val TAG = "PortalHA"
        private const val CHANNEL = "portal_ha_bridge"
        private const val NOTIF_ID = 1
        private const val MOTION_CLEAR_MS = 5_000L
        // How long a loud sound keeps "enhanced presence" present after the noise
        // stops (people make only intermittent sound, so we bridge the gaps).
        private const val SOUND_PRESENCE_HOLD_MS = 60_000L
        // Android 10+ denies the mic to a background-started assistant, so on wake we
        // bring it to the foreground and wait this long before broadcasting, giving its
        // activity time to resume so its mic-open succeeds. The screen is handed back
        // the instant the assistant grabs the mic, so this is the main lever on how long
        // the assistant is visible — trim with care (too low = assistant hears silence).
        // Measured on the omni: mic-grab lands ~220ms after the broadcast, activity
        // resume ~300-500ms after launch; 300ms keeps the grab just past the resume.
        // If the assistant ever comes up deaf, raise this first (600 was rock solid).
        private const val WAKE_FOREGROUND_MS = 300L
        // Alexa/falcon on A10: our mic must be RELEASED and falcon fully foreground before
        // we fire LISTEN, or falcon captures nothing ("sorry, something went wrong"). Give
        // its activity a longer, safe window to resume + the mic slot to actually free.
        private const val ALEXA_FOREGROUND_MS = 800L
        // Barge-in LISTEN needs only the mic slot to free (falcon is already foreground and
        // resumed), so a much shorter settle than ALEXA_FOREGROUND_MS. Raise if barge-in
        // ever yields "sorry, something went wrong".
        private const val ALEXA_BARGE_LISTEN_MS = 300L
        // Falcon warm-up kick after our app (re)starts: wait for the dashboard to settle,
        // then hold falcon foreground behind the cover just long enough for its voice
        // session to re-establish (its activity launch IS the provisioner's "kick").
        private const val FALCON_WARMUP_DELAY_MS = 12_000L
        private const val FALCON_WARMUP_HOLD_MS = 2_500L
        // How long to keep falcon's output muted around the warm-up turn: long enough to
        // cover the abort, our retry, and any "sorry…" it speaks in between.
        private const val FALCON_WARMUP_MUTE_MS = 9_000L
        // ★KEEP-WARM. Measured 2026-08-02/03: falcon's upload session goes stale after
        // roughly a minute idle — a turn 25 s after the last one is clean, one 100 s later
        // aborts. The abort costs the user their WORDS (the retry recovers falcon's
        // session, but by the time a fresh listen window opens the command has been
        // spoken and thrown away), so it has to be PREVENTED, not recovered from.
        // A deaf, muted LISTEN on this interval keeps the session alive so the real turn
        // captures first time. Gated on presence so an empty room costs nothing.
        private const val ALEXA_KEEPWARM_MS = 45_000L
        private const val ALEXA_KEEPWARM_MUTE_MS = 6_000L
        private const val ALEXA_KEEPWARM_SKIP_MS = 20_000L
        // How long the dashboard snapshot stays up over a keep-warm — long enough to
        // outlast Alexa's listening bar, short enough that a live dashboard barely blinks.
        private const val ALEXA_KEEPWARM_COVER_MS = 7_000L
        // Cold-abort detection: when falcon's SIMActivity has to be COLD-CREATED (first turn
        // after its activity died, e.g. after our app restarts), its first capture opens
        // while the uid is still policy-silenced — the server gets dead air and kills the
        // turn ~200-400ms after LISTEN ("something went wrong"). A TURN_DONE this soon after
        // our LISTEN can't be a real answer, so treat it as the race and re-fire LISTEN once:
        // the activity is warm by then, and the retry also cuts the error speech short.
        private const val ALEXA_COLD_ABORT_WINDOW_MS = 1_500L
        // ★MEASURED ROOT CAUSE (2026-08-01, falcon's own logs during a cold abort):
        //   SPCH-AP_AudioRecorder: Start reading from the audio stream
        //   SPCH-SIM_StreamWriterRunnable: amazon.speech.audio.AudioStreamReader$OverrunException
        //   SPCH-SIM_SimStateMachine: Got ErrorEvent in ListenState -> errorCode GENERIC
        // The mic opens FINE (no silencing race — that earlier theory was wrong, and a
        // 2.5 s pre-LISTEN settle did NOT prevent it). What fails is falcon's UPLOAD of
        // the first utterance: its shm audio buffer overruns while the recognize stream
        // is still being established, so it kills the turn ~200-400 ms in.
        // Nothing on our side can pre-warm that, so we absorb it with a retry LISTEN.
        // ★DO NOT SHORTEN THIS. Measured on-device 2026-08-01: at 80 ms the retry cuts
        // her error speech to "sorr—" but falcon is still tearing the aborted turn down
        // and DROPS the LISTEN — the turn then dies silently, which is far worse than a
        // brief error sound. At 900 ms the retry is accepted and the turn recovers
        // (verified: capture opens, full listen window, clean end). The audible cost is
        // ~290 ms of "sorry, s—" and that is the right trade.
        private const val ALEXA_COLD_RETRY_MS = 900L
        private const val ALEXA_COLD_RETRY2_MS = 1_400L
        private const val ALEXA_COLD_MAX_RETRIES = 2
        // Keep the mute a little past the retry LISTEN so the tail of the apology can't
        // leak through; her real answer arrives seconds later, well clear of this.
        private const val ALEXA_ABORT_MUTE_TAIL_MS = 500L
        // Grace for our logcat reader to surface falcon's ErrorEvent after TURN_DONE.
        private const val ALEXA_ERROR_CHECK_MS = 400L
        // The assistant must sit at baseline (no recording) continuously for this long
        // before we call the conversation done — rides over inter-turn mic releases.
        private const val WAKE_RECLAIM_DEBOUNCE_MS = 2_500L
        // After Alexa finishes a turn AND stops speaking, hold this long before reclaiming —
        // long enough for a multi-turn follow-up (ExpectSpeech) to reopen the mic. The hold
        // is playback-aware: while her voice is audible (see assistantSpeaking()) the
        // countdown doesn't run at all, so a long answer ("tell me a story") can't strand
        // her mid-conversation; the grace starts when the speaker actually goes quiet.
        private const val ALEXA_DIALOG_GRACE_MS = 4_000L
        @Volatile private var crashGuardInstalled = false

        // RTSP self-heal: how long after a (re)start to verify the camera actually
        // opened, how long to ignore our own torn-down camera's freed event, and
        // the foreground auto-retry cap per failure chain (backoff 1s→30s; a
        // return to the app always arms one more try via ensureCamera).
        private const val RTSP_HEALTH_CHECK_MS = 4_000L
        private const val RTSP_OWN_STOP_IGNORE_MS = 3_000L
        private const val RTSP_RECOVER_MAX_ATTEMPTS = 6
        private const val DASHBOARD_RETURN_MS = 90_000L

        // ── Telling "the user left" apart from "an app barged in" ─────────────────────
        // Android reports the two IDENTICALLY. Measured with an Alexa announcement
        // (2026-08-28): falcon starts its AriaActivity without FLAG_ACTIVITY_NO_USER_ACTION,
        // so the framework logs `am_pause_activity … userLeaving=true` and calls
        // onUserLeaveHint on us exactly as a real Home press would. Taking that at face value
        // switched auto-return off for the whole announcement.
        // The discriminator is a touch: a person going elsewhere has just tapped or pressed
        // something on OUR panel; an app putting itself in front has not. Backed up by a real
        // Home press, which also broadcasts CLOSE_SYSTEM_DIALOGS reason=homekey.
        private const val USER_LEAVE_TOUCH_MS = 5_000L
        // First check after an untouched steal. Deliberately short: the cost of being in the
        // background is not cosmetic (see noteForegroundStolen).
        private const val STOLEN_RETURN_GRACE_MS = 600L
        // Poll fast. This runs ONLY while something has the front off us, and the interval is
        // pure added latency — at 2 s it was contributing more delay than the hold below.
        private const val STOLEN_RETURN_POLL_MS = 400L
        // How long the intruder must stay quiet before we take the screen back. The thing this
        // has to clear is falcon re-asserting AriaActivity (a second START with
        // REORDER_TO_FRONT) shortly AFTER its announcement audio ends — measured across four
        // real announcements at 455 / 580 / 449 / 419 ms. 1.2 s is a little over twice the
        // worst of those, and lands the return ~1.5 s after she stops talking.
        private const val STOLEN_QUIET_MS = 1_200L
        // ★Ping-pong insurance. Once in testing, falcon took the front back ~0.5 s after we
        // returned — the hold above cannot prevent that, because the assert came after a
        // legitimately quiet gap. So each steal that arrives hard on the heels of our own
        // return lengthens the next hold instead of trading flips with it. Bounded, and reset
        // by any steal that isn't part of a flap.
        private const val STOLEN_REFLAP_MS = 3_000L
        private const val STOLEN_QUIET_STEP_MS = 1_000L
        private const val STOLEN_FLAP_MAX = 3
        // Something that genuinely owns the screen this long isn't a stray announcement.
        // Stop chasing it; a screen-on reclaim or the user will sort it out.
        private const val STOLEN_RETURN_MAX_MS = 120_000L

        // What "shorten the OS screen timeout" sets it to. One minute is what the field report
        // used; long enough not to fight anything, short enough that the OS stops waiting.
        private const val OS_TIMEOUT_SHORT_MS = 60_000

        // Settings.Secure screensaver keys — @hide, so referenced by name.
        private const val DREAM_COMPONENTS = "screensaver_components"
        private const val DREAM_DEFAULT = "screensaver_default_component"

        // Never rewrite the screensaver slot more often than this — bounds a two-app tug-of-war
        // to a slow alternation rather than a hot loop on a Settings key.
        private const val DREAM_RECLAIM_MIN_MS = 5_000L

        // How long after screen-on to keep the wake covered. Long enough for the dashboard to be
        // resumed and drawn, short enough not to feel like a slow wake.
        private const val SLEEP_COVER_REVEAL_MS = 700L

        private const val ACTION_SET_CAMERA = "com.aeonos.portalha.SET_CAMERA"
        private const val EXTRA_CAMERA_ON = "camera_on"
        private const val ACTION_SET_ROTATION = "com.aeonos.portalha.SET_ROTATION"
        private const val EXTRA_ROTATION = "rotation"
        private const val ACTION_ENSURE_CAMERA = "com.aeonos.portalha.ENSURE_CAMERA"
        private const val ACTION_BOOTED = "com.aeonos.portalha.BOOTED"
        private const val ACTION_APPLY_DISPLAY = "com.aeonos.portalha.APPLY_DISPLAY"
        private const val ACTION_APPLY_MEDIA = "com.aeonos.portalha.APPLY_MEDIA"

        // When to put the dashboard up after a boot, and it has to be several tries over a good
        // minute. Measured on a real reboot: the system starts HOME at +0s, we win the front at
        // +28s, the launcher launches us at +31s and then puts ITSELF back at +35s. Whoever
        // asserts last wins, so we simply have to still be asserting after the launcher has
        // finished. This is also what makes the behaviour independent of which launcher is
        // installed — we never rely on its "launch an app at boot" setting, which on Immortal
        // is self-defeating anyway.
        private val BOOT_FRONT_DELAYS_MS = longArrayOf(4_000L, 12_000L, 25_000L, 45_000L, 70_000L)
        private const val ACTION_APPLY_INTERCOM = "com.aeonos.portalha.APPLY_INTERCOM"
        private const val ACTION_TWOWAY_TEST = "com.aeonos.portalha.TWOWAY_TEST"
        private const val EXTRA_TWOWAY_ON = "two_way_on"
        // Live-switch the wake handoff cover style for A/B testing, e.g.:
        //   adb shell am startservice -n com.aeonos.portalha/.BridgeService \
        //     -a com.aeonos.portalha.SET_COVER --es cover snapshot
        private const val ACTION_SET_COVER = "com.aeonos.portalha.SET_COVER"
        private const val EXTRA_COVER = "cover"
        // Reply-channel silence timeout now lives in Prefs.twoWayIdleSecs (slider);
        // the idle check reads it live so slider moves apply to an open channel.

        // Opt-in daily update check: evaluated on an hourly tick (cheap), fires
        // when the persisted due time passes and the screen is on.
        private const val UPDATE_TICK_MS = 3_600_000L

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

        // Experimental hands-free 2-way test engine — driven by the temporary toggle in
        // Intercom settings while we verify whether echo behaves on the hardware.
        fun setTwoWayEnabled(context: Context, on: Boolean) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_TWOWAY_TEST).putExtra(EXTRA_TWOWAY_ON, on))

        // Fire the assistant hand-off on demand (e.g. the HA dashboard voice button via
        // HaExternalBridge). Reuses the wake flow: brings the assistant up, yields the mic,
        // reclaims it when done. No-op if the service isn't running.
        fun requestAssist(@Suppress("UNUSED_PARAMETER") context: Context) { instance?.fireWakeHandoff() }

        // The YouTube cast screen closed (user long-pressed out, or it died) —
        // sync the DIAL app state so the phone offers a fresh launch next time.
        fun castScreenClosed() { instance?.dialServer?.appRunning = false }

        // Re-evaluate the PTT overlays after a pref/config change.
        fun applyIntercomOverlay(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_APPLY_INTERCOM))

        // Repaint the floating buttons live (e.g. the transparency slider moved).
        fun intercomOverlayRefresh() { instance?.intercomOverlays?.forEach { it.refresh() } }

        // The dashboard drives overlay visibility — the floating buttons show only
        // while the Portal HA Bridge dashboard is in front, not over other apps.
        @Volatile private var dashboardForeground = false

        // True while the user is deliberately using another app (see
        // DashboardActivity.onUserLeaveHint). Every path that pulls the dashboard to the front
        // must check it, or a Portal being used as a tablet — Netflix, a browser — has the
        // dashboard thrown over the top mid-programme. Cleared the moment the dashboard is
        // genuinely front again, so an actual launcher steal after that still self-heals.
        @Volatile private var userLeftDashboard = false

        // Called from DashboardActivity.onUserLeaveHint — which fires both when someone
        // deliberately walks away from the panel AND when an app launches itself over us.
        // See USER_LEAVE_TOUCH_MS for why those are indistinguishable and how we separate them.
        fun noteUserLeftDashboard() {
            val svc = instance
            val now = System.currentTimeMillis()
            val touchAge = if (svc == null || svc.lastInputMs == 0L) -1L else now - svc.lastInputMs
            val homeAge = if (svc == null || svc.lastHomeKeyMs == 0L) -1L else now - svc.lastHomeKeyMs
            // With no service there is nothing to return the dashboard anyway, so fall back to
            // the cautious reading: assume the person meant it.
            val deliberate = svc == null ||
                touchAge in 0..USER_LEAVE_TOUCH_MS ||
                homeAge in 0..USER_LEAVE_TOUCH_MS
            // Not deliberate: leave userLeftDashboard alone and say nothing more. The pause that
            // follows this hint is what arms the watchdog (see setDashboardForeground), so that
            // one path covers both a steal that bothers to send a hint and one that doesn't.
            if (!deliberate) {
                Log.i(TAG, "dashboard: leave hint with no touch behind it (touch ${touchAge}ms ago) — treating as a steal, not a choice")
                return
            }
            if (!userLeftDashboard) Log.i(TAG, "dashboard: user switched to another app (touch ${touchAge}ms ago, home key ${homeAge}ms ago) — auto-return disabled until they come back")
            userLeftDashboard = true
        }

        fun setDashboardForeground(fg: Boolean) {
            dashboardForeground = fg
            if (fg) { userLeftDashboard = false; instance?.clearForegroundSteal() }
            // We just lost the front and nothing marked it a deliberate departure. That covers
            // both shapes of steal: an app that sends a leave hint (an Alexa announcement — see
            // noteUserLeftDashboard) and one that sends none at all (a launcher asserting HOME
            // from the background, which pauses us with no hint whatsoever — measured).
            else if (!userLeftDashboard) instance?.noteForegroundStolen()
            instance?.reconcileIntercomOverlays()
        }

        // A touch or key reached the dashboard — restart the photo-frame countdown.
        fun noteUserInteraction() { instance?.lastInteractionMs = System.currentTimeMillis() }

        // Real input only — see DashboardActivity.dispatchTouchEvent for why this can't just
        // read lastInteractionMs. Starts at 0 so a freshly started service correctly believes
        // nobody has touched anything yet.
        fun noteUserInput() { instance?.lastInputMs = System.currentTimeMillis() }

        // The foreground app changed (reported by ScreenAccessibility). Used to auto-return the
        // dashboard after the Meta Calls flow — see checkCallReturn.
        fun noteForegroundPackage(pkg: String) { instance?.onForegroundPackage(pkg) }

        // The Meta calling flow: its launcher (reached by the HA Calls button), the contacts /
        // dialer UI, and Messenger (the in-call screen). Leaving the dashboard for any of THESE
        // arms the timed return; leaving for anything else is left strictly alone.
        private val CALL_RETURN_PKGS = setOf(
            "com.facebook.alohaapps.launcher",
            "com.facebook.alohaapps.contacts",
            "com.facebook.aloha.app.messenger",
        )
        private const val META_LAUNCHER_PKG = "com.facebook.alohaapps.launcher"
        // How long after the launcher appears to inject the dismiss tap — long enough for its
        // photo home to be drawn and accept the touch, short enough not to be seen as a pause.

        fun start(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java))

        fun startFromBoot(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_BOOTED))

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

        // Start/stop the speaker roles (DLNA, Sendspin) to match the prefs, without a service
        // restart. Called from the system settings page's MUSIC switches.
        fun applyMediaSettings(context: Context) =
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .setAction(ACTION_APPLY_MEDIA))

        // Latest 0–100 ambient sound level (or -1) — for calibrating the enhanced-
        // presence threshold live in settings.
        fun currentSoundLevel(): Int = instance?.lastSoundLevel ?: -1

        // Latest RAW temperature reading (offset not applied), or null — lets the
        // Sensors screen show what the sensor sees while you set the offset.
        fun currentRawTemp(): Float? = instance?.sensorBridge?.rawTemperature()

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
    private var alexaTurnDoneReceiver: BroadcastReceiver? = null
    private var debugWakeReceiver: BroadcastReceiver? = null
    private var debugCallReceiver: BroadcastReceiver? = null
    private var debugAudioReceiver: BroadcastReceiver? = null
    private var debugUpdateReceiver: BroadcastReceiver? = null
    private var debugOwwReceiver: BroadcastReceiver? = null
    private var debugScreenReceiver: BroadcastReceiver? = null
    private var debugScreensaverReceiver: BroadcastReceiver? = null
    private var debugConfigReceiver: BroadcastReceiver? = null
    private var debugCallReturnReceiver: BroadcastReceiver? = null
    private var debugTapReceiver: BroadcastReceiver? = null
    private var sensorBridge: SensorBridge? = null
    private var soundMonitor: SoundMonitor? = null
    private var dialServer: DialServer? = null
    private var dlnaRenderer: DlnaRenderer? = null
    private var nowPlayingOverlay: NowPlayingOverlay? = null
    private var sendspinPlayer: SendspinPlayer? = null
    // Music-Assistant-truth poller (see MaControl.poll): owns the overlay whenever HA can tell us
    // what's really playing, because the DLNA renderer goes blind under MA's flow mode.
    @Volatile private var maPollRunning = false
    @Volatile private var maDriving = false
    @Volatile private var maTrackKey = ""
    @Volatile private var maPlaying = false
    @Volatile private var maPosBaseMs = 0
    @Volatile private var maPosBaseAt = 0L
    @Volatile private var maPosStamp = ""
    @Volatile private var maDurationMs = 0
    @Volatile private var maIdlePolls = 0
    // Sendspin now-playing: pushed to us, so no polling and no separate artwork fetch.
    @Volatile private var sendspinDriving = false
    @Volatile private var ssTrackKey = ""
    @Volatile private var ssPlaying = false
    @Volatile private var ssPosBaseMs = 0
    @Volatile private var ssPosBaseAt = 0L
    @Volatile private var ssDurationMs = 0
    @Volatile private var ssProgressSeq = -1
    @Volatile private var dlnaTrackKey = ""     // "title|artist" of the track the overlay shows
    private var twoWay: TwoWayEngine? = null
    private var twoWayOrb: AnnounceOrbOverlay? = null
    @Volatile private var twoWayChannelOpen = false
    @Volatile private var lastTwoWayActivityMs = 0L
    private var wakeDetector: WakeWordDetector? = null
    private var startedWakePhrase: String? = null   // phrase the live recognizer was built with
    private var voiceAnnounce: VoiceAnnounce? = null
    private var receiveOrb: AnnounceOrbOverlay? = null

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
    // Whether ANYONE currently holds Camera 0 (availability callback state). While
    // RTSP streams it should be us; "streaming" with the camera free = dead stream.
    @Volatile private var frontCamInUse = false
    @Volatile private var lastRtspStartMs = 0L
    @Volatile private var lastRtspDeadMs = 0L
    @Volatile private var rtspRecoverAttempts = 0
    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (cameraId != frontCameraId) return
            frontCamInUse = false
            // Our front camera went free while we still think we're streaming → a
            // call took it. DON'T restart here: we're backgrounded (call just ended)
            // and Android blocks opening the camera from the background. Flag it and
            // recover on the next return to the app (ensureCamera → foreground).
            // Freed events within a few seconds of our own (re)start are the close
            // of the stream we just tore down arriving late — those must NOT set the
            // flag (they faked a "call took the camera" and cascaded restarts).
            if (rtspStreamer?.isStreaming == true &&
                System.currentTimeMillis() - lastRtspStartMs > RTSP_OWN_STOP_IGNORE_MS) {
                Log.i(TAG, "camera $cameraId freed while streaming — stream dead")
                onRtspStreamDead("camera taken")
            }
        }
        override fun onCameraUnavailable(cameraId: String) {
            if (cameraId == frontCameraId) frontCamInUse = true
        }
    }

    // Stranded-dashboard auto-return. A launcher self-promotion (observed on the
    // omni: com.immortal.launcher takes the front on its own) backgrounds the app
    // and A10 evicts our camera. On a Portal whose presence keeps the screen on,
    // no activity resume ever comes — the stream stayed dead for hours until a
    // human or HA intervened. When the stream is flagged dead while we're not in
    // front, bring the dashboard back after a grace period, unless the Portal is
    // genuinely mid-something (call, cast, Alexa turn/playback — then re-check).
    // Screen off is left alone: the next screen-on resume heals by itself.
    private val dashboardReturn = Runnable {
        val p = prefs
        when {
            !rtspNeedsRestart || dashboardForeground -> {}   // healed or already back
            p == null || !p.cameraServiceEnabled || !p.cameraOn || !p.streamEnabled -> {}
            // Screen dark: don't act (never wake the room), but KEEP polling — a
            // screen-on resumes the LAUNCHER when it stole the front, not us, so
            // dropping here left the stream dead until a human intervened.
            !screenOn -> scheduleDashboardReturn()
            // ★The user is in another app on purpose. Recovery is for a stranded panel, not for
            // overriding someone watching Netflix — and the camera can't open from the
            // background anyway, so returning would not even heal the stream. Keep polling so
            // this resumes working the moment they come back and something really does steal it.
            userLeftDashboard -> scheduleDashboardReturn()
            inCall || micYieldedForWake || TvAppActivity.isShowing() || falconPlaying() ->
                scheduleDashboardReturn()                    // busy — check again later
            else -> {
                Log.i(TAG, "stream dead in background — returning dashboard to front")
                bringDashboardToFront()                      // resume → ensureCamera recovers
            }
        }
    }
    private fun scheduleDashboardReturn() {
        wakeHandler.removeCallbacks(dashboardReturn)
        wakeHandler.postDelayed(dashboardReturn, DASHBOARD_RETURN_MS)
    }

    // ── An app took the front and nobody asked it to ───────────────────────────────
    // Set by noteUserLeftDashboard when the departure had no touch behind it. The known
    // culprits are an Alexa announcement (falcon's AriaActivity, rendered black when the
    // announcement has no visual) and a launcher firing its own HOME intent.
    //
    // ★Why this is worth chasing rather than tolerating: on Android 10 a uid that is not
    // foreground has its recorder SILENCED — measured during an announcement as
    // "AudioPolicyService … onUidForeground() silencing for 10130 -> 0" the instant falcon's
    // card appeared, and back to 1 only when it closed itself 34.7 s later. For that whole
    // window our AudioRecord was fed digital silence, so the wake word could not fire and the
    // Portal could not hear "alexa" at all. Nobody can opt out either: even falcon fails the
    // check for Meta's RECORD_AUDIO_PRIVILEGED. Being foreground IS the microphone, so
    // returning the dashboard is what makes the Portal able to listen again — not decoration.
    //
    // We still wait for the intruder to finish, because interrupting an announcement to fix
    // an announcement would be silly. See STOLEN_QUIET_MS for why "finished" needs a hold.
    @Volatile private var foregroundStolenMs = 0L
    @Volatile private var stolenQuietSinceMs = 0L
    @Volatile private var stolenLogged = false
    // When we last took the screen back, and how many steals in a row have landed right on top
    // of one of those returns — see STOLEN_REFLAP_MS.
    @Volatile private var stolenReturnedMs = 0L
    @Volatile private var stolenFlaps = 0
    // Set by the CLOSE_SYSTEM_DIALOGS receiver; only a genuine Home press produces it, which
    // covers the one deliberate departure that reaches us without touching our window first.
    @Volatile private var lastHomeKeyMs = 0L

    // Last REAL touch or key on the dashboard (dispatchTouchEvent/dispatchKeyEvent), as opposed
    // to lastInteractionMs, which Android also stamps on the way out of the activity.
    @Volatile private var lastInputMs = 0L

    // ── Auto-return from the Meta Calls flow ───────────────────────────────────────
    // The current foreground package (from ScreenAccessibility) and, while we are sitting in a
    // calling app with no call, when that idle stretch began. bringDashboardToFront fires once
    // it has lasted callReturnMinutes. A call in progress, or moving to any non-calling app,
    // resets the arm — so a real call is never interrupted and an app the user actually chose
    // (a browser, Netflix) is never overridden.
    @Volatile private var foregroundPkg: String? = null
    @Volatile private var callReturnArmedMs = 0L
    // Test hook: DEBUG_CALL_RETURN --ei secs N overrides the minutes pref with N seconds so the
    // return can be exercised without waiting three minutes. 0 = use the pref.
    @Volatile private var callReturnDebugSecs = 0

    private fun onForegroundPackage(pkg: String) {
        if (pkg == foregroundPkg) return
        val prev = foregroundPkg
        foregroundPkg = pkg
        // Re-evaluate immediately so leaving a calling app (or a call starting) resets the arm
        // without waiting for the next 15 s tick. Routed through timeoutHandler so every
        // checkCallReturn runs on the one thread — no race on callReturnArmedMs with the tick.
        timeoutHandler.post { runCatching { checkCallReturn() } }
        // Meta's launcher just came to the front (we only ever reach it via the Calls button —
        // Home is Immortal). It opens on its photo home, which takes one tap to reveal the Calls
        // tiles; inject that tap so the user lands straight on the tiles. Only on the transition
        // INTO the launcher, so we never tap twice or interfere once they're on the tiles.
        if (pkg == META_LAUNCHER_PKG && prev != META_LAUNCHER_PKG &&
            prefs?.autoDismissCallScreensaver == true) {
            scheduleCallScreensaverTap()
        }
    }

    // One tap, a beat after the launcher home is up, to clear its idle "dream" face (clock on a
    // dark/photo background) and land on the Calls tiles. ★The spot must be SCREEN CENTRE:
    // verified on-device that a top-of-screen tap does NOT dismiss the cold dream face (it just
    // sat on the clock), while a centre tap does. Centre is also safe if the home ever comes up
    // already past the dream — it falls in the empty gap between the favourites row and the
    // app-shortcuts card, so it launches nothing.
    // Dismiss the launcher's idle "dream" face (clock on a photo/abstract background) with a
    // centre tap so we land on the Calls tiles. ★It has to be a RETRY BURST, not one tap:
    // verified on-device that a COLD launcher only accepts the dismiss once it has finished
    // loading — an early tap is swallowed (the face just sits there), while the same tap lands
    // cleanly several seconds later. A warm launcher dismisses on the first tap. So we tap
    // centre repeatedly over a window; once the tiles are up every further centre tap falls in
    // the empty gap between the favourites row and the app-shortcuts card and launches nothing,
    // and the foregroundPkg guard stops the burst the moment the launcher is no longer in front.
    private val callTapDelaysMs = longArrayOf(900, 2000, 3500, 5500, 8000, 11000)

    private fun scheduleCallScreensaverTap() {
        callTapDelaysMs.forEachIndexed { i, d -> tapLauncherCentre(d, "${i + 1}/${callTapDelaysMs.size}") }
    }

    private fun tapLauncherCentre(delayMs: Long, which: String) {
        wakeHandler.postDelayed({
            if (foregroundPkg != META_LAUNCHER_PKG) return@postDelayed   // left the launcher — stop
            val svc = ScreenAccessibility.instance ?: run {
                Log.w(TAG, "calls: no accessibility service to dismiss the launcher screensaver")
                return@postDelayed
            }
            Log.i(TAG, "calls: dismissing launcher dream face — centre tap $which")
            svc.tapFraction(0.5f, 0.5f)
        }, delayMs)
    }

    // A voice/video call is live. Reuses the same signal HA sees (USAGE_VOICE_COMMUNICATION
    // playback from another uid — falcon/Messenger), plus the audio mode for the ring/connect
    // window before any audio is flowing. Either one means "do not pull the screen away".
    private fun callInProgress(): Boolean {
        if (inCall) return true
        val am = getSystemService(AudioManager::class.java) ?: return false
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL
    }

    // Come back to the dashboard a set time after the user went to make a call and then left the
    // calling app idle. Runs on the 15 s tick and whenever the foreground app changes.
    private fun checkCallReturn() {
        val p = prefs ?: return
        val windowMs = if (callReturnDebugSecs > 0) callReturnDebugSecs * 1000L
            else p.callReturnMinutes * 60_000L
        if (windowMs <= 0L) { callReturnArmedMs = 0L; return }        // feature off
        if (dashboardForeground) { callReturnArmedMs = 0L; return }   // already home
        val fg = foregroundPkg
        // Not in the calling flow — the user is on the home screen or in some other app they
        // chose. Leave it entirely alone (this is the "don't yank Netflix" rule).
        if (fg == null || fg !in CALL_RETURN_PKGS) { callReturnArmedMs = 0L; return }
        // Mid-call (or ringing/connecting): hold, and restart the idle countdown so the return
        // only ever happens well after the call is over.
        if (callInProgress()) { callReturnArmedMs = 0L; return }
        val now = System.currentTimeMillis()
        if (callReturnArmedMs == 0L) {
            callReturnArmedMs = now
            Log.i(TAG, "calls: in ${fg} with no call — will return to dashboard in ${windowMs / 1000}s if it stays idle")
            return
        }
        if (now - callReturnArmedMs < windowMs) return
        Log.i(TAG, "calls: calling app idle ${(now - callReturnArmedMs) / 1000}s — returning to dashboard")
        callReturnArmedMs = 0L
        bringDashboardToFront()
    }

    // How many of OUR activities are resumed. Process-wide via the Application callbacks, so it
    // covers every settings screen without each one having to report in.
    @Volatile private var ourActivitiesResumed = 0
    private val ourActivityWatch = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: android.app.Activity) { ourActivitiesResumed++ }
        override fun onActivityPaused(a: android.app.Activity) {
            if (ourActivitiesResumed > 0) ourActivitiesResumed--
        }
        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
        override fun onActivityStarted(a: android.app.Activity) {}
        override fun onActivityStopped(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: android.app.Activity) {}
    }

    private fun noteForegroundStolen() {
        if (foregroundStolenMs != 0L) return          // already watching this one
        // The screen going to sleep pauses the dashboard too, and that is not a steal — without
        // this every single sleep would arm a watch and log about it. Nothing is lost: a steal
        // that happens in the dark is picked up by reclaimForeground() on the next screen-on.
        if (!screenOn) return
        val now = System.currentTimeMillis()
        // Straight back on top of our own return: whatever this is wants the screen more than
        // the quiet hold can tell. Give it longer each time rather than trading flips.
        stolenFlaps = if (stolenReturnedMs != 0L && now - stolenReturnedMs <= STOLEN_REFLAP_MS)
            minOf(stolenFlaps + 1, STOLEN_FLAP_MAX) else 0
        if (stolenFlaps > 0) {
            Log.i(TAG, "dashboard: taken again ${now - stolenReturnedMs}ms after we came back — waiting ${stolenQuietHoldMs()}ms for quiet this time")
        }
        foregroundStolenMs = now
        stolenQuietSinceMs = 0L
        // Deliberately silent. The screen going off pauses us BEFORE ACTION_SCREEN_OFF arrives
        // (measured — the guard above misses it), so arming is not yet evidence of anything;
        // the first poll says whether this is a real steal, and logs there.
        stolenLogged = false
        wakeHandler.removeCallbacks(stolenReturn)
        wakeHandler.postDelayed(stolenReturn, STOLEN_RETURN_GRACE_MS)
    }

    /** Quiet the intruder must hold, stretched while it keeps grabbing the front straight back. */
    private fun stolenQuietHoldMs(): Long =
        STOLEN_QUIET_MS + stolenFlaps * STOLEN_QUIET_STEP_MS

    private fun clearForegroundSteal() {
        if (foregroundStolenMs == 0L) return
        foregroundStolenMs = 0L
        stolenQuietSinceMs = 0L
        wakeHandler.removeCallbacks(stolenReturn)
    }

    private val stolenReturn = object : Runnable {
        override fun run() {
            val started = foregroundStolenMs
            if (started == 0L) return
            // Back on our own — whatever it was let go, or a screen-on reclaim beat us to it.
            // Must CLEAR, not just return: leaving the timestamp set would make every later
            // steal a no-op at the "already watching this one" guard.
            if (dashboardForeground) { clearForegroundSteal(); return }
            val now = System.currentTimeMillis()
            if (now - started >= STOLEN_RETURN_MAX_MS) {
                Log.i(TAG, "dashboard: whatever took the front is still busy after ${STOLEN_RETURN_MAX_MS / 1000}s — leaving it be")
                clearForegroundSteal()
                return
            }
            // One of our OWN screens is up — settings, the cast receiver. Nothing was stolen,
            // and dragging the dashboard over a settings page the user is reading would be its
            // own bug. Checked here rather than at arming time because the outgoing pause
            // arrives before the incoming resume, so the count is briefly zero in between.
            if (ourActivitiesResumed > 0) {
                Log.i(TAG, "dashboard: another of our own screens is up ($ourActivitiesResumed) — nothing was stolen")
                clearForegroundSteal(); return
            }
            // Screen dark: there is no black screen to fix and no reason to light the room.
            // The screen-on reclaimForeground() covers this now that a steal no longer marks
            // the departure deliberate, so hand it over rather than poll in the dark.
            if (!screenOn) { clearForegroundSteal(); return }
            // Past the cancels, so this really is something sitting on top of us uninvited.
            if (!stolenLogged) {
                stolenLogged = true
                Log.i(TAG, "dashboard: lost the front with nobody asking — returning once it goes quiet")
            }
            // Otherwise the usual "is the Portal mid-something real" list, plus Alexa's own
            // voice and mic so an announcement is never cut off half-spoken.
            val busy = inCall || TvAppActivity.isShowing() || micYieldedForWake ||
                assistantSpeaking() || assistantRecording() || falconPlaying()
            if (busy) {
                stolenQuietSinceMs = 0L
                wakeHandler.postDelayed(this, STOLEN_RETURN_POLL_MS)
                return
            }
            if (stolenQuietSinceMs == 0L) stolenQuietSinceMs = now
            if (now - stolenQuietSinceMs < stolenQuietHoldMs()) {
                wakeHandler.postDelayed(this, STOLEN_RETURN_POLL_MS)
                return
            }
            Log.i(TAG, "dashboard: front was taken ${now - started}ms ago and has been quiet ${now - stolenQuietSinceMs}ms — coming back")
            stolenReturnedMs = now
            clearForegroundSteal()
            bringDashboardToFront()
        }
    }

    // start()/startStream() report success even when Android refused the camera
    // (A10 blocks opening from the background) — the encoder then never produces
    // video and clients get "video info is null" forever. Verify a few seconds
    // after every (re)start that Camera 0 is actually held; if not, the stream is
    // dead and must be flagged for recovery.
    private val rtspHealthCheck = Runnable {
        if (rtspStreamer?.isStreaming == true && !frontCamInUse) {
            Log.w(TAG, "rtsp health check: streaming but camera 0 never opened")
            onRtspStreamDead("camera not open")
        }
    }

    // Call after every RTSP (re)start attempt.
    private fun noteRtspStarted() {
        lastRtspStartMs = System.currentTimeMillis()
        wakeHandler.removeCallbacks(rtspHealthCheck)
        wakeHandler.postDelayed(rtspHealthCheck, RTSP_HEALTH_CHECK_MS)
    }

    // A dead stream never healed itself before: isStreaming stayed true so both
    // ensureCamera and applyCameraState treated it as running. Central sink for
    // all dead-stream signals (camera never opened, EADDRINUSE server bind loss,
    // clients starving on "video info is null"): flag for the foreground-return
    // recovery, and when the dashboard is already front retry here with backoff.
    private fun onRtspStreamDead(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRtspDeadMs > 60_000L) rtspRecoverAttempts = 0   // new failure chain
        lastRtspDeadMs = now
        rtspNeedsRestart = true
        // A lost server socket (EADDRINUSE) needs no foreground to rebind — always
        // retry those. Camera-open failures DO need the app in front, so don't
        // spin on them in the background; ensureCamera recovers on the next
        // return to the app (the HA "Show Dashboard" button is enough).
        val bindFailure = reason.contains("Server creation failed")
        if (!bindFailure && !dashboardForeground) {
            Log.w(TAG, "rtsp stream dead ($reason) while backgrounded — will recover on return to app")
            scheduleDashboardReturn()
            return
        }
        if (rtspRecoverAttempts >= RTSP_RECOVER_MAX_ATTEMPTS) {
            Log.w(TAG, "rtsp stream dead ($reason) — retry cap hit, waiting for return to app")
            return
        }
        val backoff = (1000L shl rtspRecoverAttempts).coerceAtMost(30_000L)
        rtspRecoverAttempts++
        Log.w(TAG, "rtsp stream dead ($reason) — auto-restart #$rtspRecoverAttempts in ${backoff}ms")
        wakeHandler.postDelayed({
            commandExecutor.submit {
                val p = prefs ?: return@submit
                val r = rtspStreamer ?: return@submit
                if (p.cameraServiceEnabled && p.cameraOn && rtspNeedsRestart && r.isStreaming) {
                    rtspNeedsRestart = false
                    r.restart()
                    noteRtspStarted()
                }
            }
        }, backoff)
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
        override fun run() {
            checkScreenTimeout()
            runCatching { checkScreensaver() }.onFailure { Log.w(TAG, "screensaver check: ${it.message}") }
            runCatching { checkCallReturn() }.onFailure { Log.w(TAG, "call-return check: ${it.message}") }
            timeoutHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        installRtspCrashGuard()
        createChannel()
        startForeground(NOTIF_ID, notification("Starting…"))
        runCatching { application.registerActivityLifecycleCallbacks(ourActivityWatch) }

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
        val coexist = p.coexistVoiceAssistant && !p.wakeWordEnabled && !p.alexaWakeEnabled
        intercom = Intercom(this, p.deviceId, { prefs?.deviceName ?: "Portal" }, { localIp() }, ::publishBytes)
            .also {
                it.attachSoundMonitor(if (coexist) null else soundMonitor)
                it.setOnDemandCapture(coexist)
                val ro = AnnounceOrbOverlay(this, blue = true, interactive = false)
                receiveOrb = ro
                it.onReceiveStart = {
                    ScreenControl.wake(this); lastActivityMs = System.currentTimeMillis()
                    if (twoWayChannelOpen) { twoWayOrb?.setLive(true); lastTwoWayActivityMs = System.currentTimeMillis() }
                    else { ro.show(); ro.setLive(true) }
                }
                it.onReceiveLevel = { lvl ->
                    if (twoWayChannelOpen) { twoWayOrb?.setLevel(lvl); lastTwoWayActivityMs = System.currentTimeMillis() }
                    else ro.setLevel(lvl)
                }
                it.onReceiveEnd = { if (!twoWayChannelOpen) ro.hide() }
                it.onTwoWayChannel = { open, _ -> onTwoWayChannelChanged(open) }
                it.suppressPlayback = { inCall }   // never talk over a live Meta call
            }
        // On-device "hey jarvis": fed the warm mic, fires the assistant wake handoff.
        // "<phrase> announce" instead triggers a hands-free intercom broadcast.
        voiceAnnounce = VoiceAnnounce({ soundMonitor }, { intercom }, orb = AnnounceOrbOverlay(this, blue = false, interactive = true), onDone = {
            wakeDetector?.pauseMatching(3_000L)   // end tone / room echo can't re-trigger
        })
        wakeDetector = WakeWordDetector(this,
            onWake = { fireWakeHandoff() },
            onAnnounce = { startVoiceAnnounce() },
            onAlexaWake = { fireAlexaHandoff() },
            onAlexaStop = { fireAlexaStop() },
        ).also {
            it.phrase = if (p.wakeWordEnabled) p.wakePhrase else ""
            it.alexaPhrase = if (p.alexaWakeEnabled) p.alexaWakePhrase else ""
            it.verifyEnabled = p.wakeVerifyEnabled
            it.verifyThreshold = p.wakeVerifyThreshold / 100f
        }
        soundMonitor?.wakeSink = { buf, n -> wakeDetector?.feed(buf, n) }
        // RTSP audio tap: resolved through the streamer on every chunk, so stream
        // restarts re-tap automatically. No-op (null micTap) when audio is off.
        soundMonitor?.streamSink = { buf, n -> rtspStreamer?.micTap?.feed(buf, n) }
        twoWay = TwoWayEngine(this, { intercom }, { talking ->
            lastTwoWayActivityMs = System.currentTimeMillis()
            twoWayOrb?.setTransmitting(talking)   // my orb warms blue→orange while I hold the floor
            Log.i(TAG, "2way: talking=$talking")
        })
        intercom?.twoWayEnabled = p.twoWayExperimental
        instance = this

        // Measure mic capability first (it owns the mic briefly), then start the
        // sound sensor + the PTT overlay once we know whether this Portal can send.
        intercom?.probeTransmitCapability()   // ~1.1s, owns the mic while measuring
        Handler(Looper.getMainLooper()).postDelayed({
            if (!coexist) soundMonitor?.start()   // coexist = leave the mic for the assistant
            if (p.wakeWordEnabled || p.alexaWakeEnabled) { wakeDetector?.start(); startedWakePhrase = wakeSig(p) }
            // Start watching falcon's connection state on boot so the Alexa handoff can gate on
            // it (falcon takes a while to reconnect after a reboot — see FalconReadiness).
            if (p.alexaWakeEnabled && falconReadiness == null) falconReadiness = FalconReadiness().also { it.start() }
            // Warm falcon up so the FIRST "alexa" after our restart doesn't land on a stale
            // session ("something went wrong"). NOTE this onCreate path — not reconcileWake —
            // is what runs at boot; reconcileWake only fires on a settings APPLY.
            if (p.alexaWakeEnabled) { scheduleFalconWarmup(); startAlexaKeepWarm() }
            reconcileIntercomOverlays()
            // Daily auto update check (opt-in): first evaluation 2 min after boot,
            // then hourly — maybeAutoUpdateCheck gates on the persisted due time.
            wakeHandler.postDelayed(autoUpdateTick, 120_000L)
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

        // YouTube cast receiver: DIAL discovery makes this Portal show up in the
        // cast menu of any YouTube app on the LAN; a cast launches TvAppActivity.
        dialServer = DialServer(
            this,
            friendlyName = { prefs?.deviceName ?: "Portal" },
            onLaunch = { query ->
                Handler(Looper.getMainLooper()).post {
                    if (inCall) {
                        // Don't punt a live call into PiP for a YouTube cast.
                        Log.i(TAG, "cast: launch refused — Portal is on a call")
                        return@post
                    }
                    ScreenControl.wake(this)                       // cast-to-wake
                    lastActivityMs = System.currentTimeMillis()
                    runCatching { TvAppActivity.launch(this, query) }
                        .onFailure { Log.w(TAG, "cast: launch failed: ${it.message}") }
                }
            },
            onStopApp = { TvAppActivity.close() }
        ).also { it.start() }

        // DLNA MediaRenderer: makes the Portal a speaker in Music Assistant (and any DLNA
        // controller). Modelled on DialServer; playback yields to calls/Alexa via audio focus.
        if (p.dlnaEnabled) startDlna()

        // Sendspin: joins Music Assistant as a synchronised multi-room player. Experimental.
        if (p.sendspinEnabled) startSendspin()

        startCallWatch()

        screenOn = getSystemService(PowerManager::class.java).isInteractive
        lastActivityMs = System.currentTimeMillis()
        reconcilePresence(p)
        reconcileDreamSlot(p)
        startDreamWatch()          // and take it back whenever the launcher grabs it
        reconcileOsTimeout(p)
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
                rtspStreamer?.let { it.rotationOffset = deg; if (it.isStreaming) { it.restart(); noteRtspStarted() } }
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
                            Log.i(TAG, "ensureCamera: recovering dead stream — restart")
                            r.restart()
                            noteRtspStarted()
                        } else {
                            applyCameraState(p)
                        }
                    }
                }.onFailure { Log.w(TAG, "ensureCamera failed: ${it.message}") }
            }
        }
        if (intent?.action == ACTION_SET_COVER) {
            val style = intent.getStringExtra(EXTRA_COVER) ?: "whoosh"
            (prefs ?: Prefs(this).also { prefs = it }).wakeCoverStyle = style
            Log.i(TAG, "wake: cover style set to '$style'")
        }
        if (intent?.action == ACTION_BOOTED) {
            val p = prefs ?: Prefs(this).also { prefs = it }
            if (p.startOnBoot) {
                Log.i(TAG, "boot: bringing the dashboard to the front")
                BOOT_FRONT_DELAYS_MS.forEach { d ->
                    wakeHandler.postDelayed({
                        // ★Clear the "user went elsewhere" flag first. A launcher starting its
                        // own home screen fires onUserLeaveHint on us exactly as a real Home
                        // press does (measured: Immortal does this ~35s into a boot), which
                        // would otherwise switch the guard on and abandon the boot recovery
                        // half-finished. Nobody has deliberately chosen another app seconds
                        // after a power cut, so within this window the flag means nothing.
                        userLeftDashboard = false
                        // Still yield to a call or a cast — those are real.
                        if (!inCall && !TvAppActivity.isShowing()) bringDashboardToFront()
                    }, d)
                }
            }
        }

        if (intent?.action == ACTION_APPLY_INTERCOM) {
            prefs ?: Prefs(this).also { prefs = it }
            hideIntercomOverlays()          // rebuild from the (possibly edited) config
            reconcileIntercomOverlays()
        }
        if (intent?.action == ACTION_TWOWAY_TEST) {
            // Enable/disable 2-way mode. A broadcast announce then auto-opens the reply
            // channel (see Intercom.stopTalk); disabling also closes any open channel.
            val on = intent.getBooleanExtra(EXTRA_TWOWAY_ON, false)
            intercom?.twoWayEnabled = on
            if (!on) intercom?.closeTwoWayChannel()
            Log.i(TAG, "2way: enabled=$on")
        }
        if (intent?.action == ACTION_APPLY_MEDIA) {
            val p = prefs ?: Prefs(this).also { prefs = it }
            commandExecutor.submit {
                runCatching {
                    if (p.dlnaEnabled) startDlna() else stopDlna()
                    if (p.sendspinEnabled) startSendspin() else stopSendspin()
                    publishDlnaState(p)
                }.onFailure { Log.w(TAG, "apply media settings failed: ${it.message}") }
            }
            return START_STICKY
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
                    reconcileDreamSlot(p)
        reconcileOsTimeout(p)
                    lastActivityMs = System.currentTimeMillis()  // give the new timeout a fresh start
                }.onFailure { Log.w(TAG, "applyDisplaySettings failed: ${it.message}") }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { screensaver.hide() }
        runCatching { sleepCover.hide() }   // never outlive the service holding the screen black
        dreamObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        dreamObserver = null
        commandExecutor.shutdownNow()
        runCatching { mqtt?.disconnect(0) }
        screenReceiver?.let { unregisterReceiver(it) }
        audioReceiver?.let { unregisterReceiver(it) }
        alexaTurnDoneReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugWakeReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugCallReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugAudioReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugUpdateReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugOwwReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugScreenReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugCallReturnReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugTapReceiver?.let { runCatching { unregisterReceiver(it) } }
        sensorBridge?.stop()
        soundMonitor?.stop()
        wakeDetector?.stop()
        falconReadiness?.stop(); falconReadiness = null
        twoWay?.stop(); twoWayOrb?.hide()
        dialServer?.stop(); dialServer = null
        stopDlna()
        stopSendspin()
        wakeHandler.removeCallbacks(reclaimTimeout); wakeHandler.removeCallbacks(reclaimDebounce)
        wakeHandler.removeCallbacks(stolenReturn); foregroundStolenMs = 0L
        runCatching { application.unregisterActivityLifecycleCallbacks(ourActivityWatch) }
        micYieldedForWake = false
        wakeCoverView?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) }; wakeCoverView = null }
        wakeRecordingCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(cb) }
            wakeRecordingCallback = null
        }
        wakePlaybackCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioPlaybackCallback(cb) }
            wakePlaybackCallback = null
        }
        callWatchCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioPlaybackCallback(cb) }
            callWatchCallback = null
        }
        wakeHandler.removeCallbacks(autoUpdateTick)
        wakeHandler.removeCallbacks(alexaKeepWarm)
        unmuteAlexaOutput()   // never leave the Portal muted if we stop mid-warm-up
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
                        // Wake straight to the photos when asked. Revealed BEFORE the cover
                        // drops, so the hand-off is one composited step and the dashboard is
                        // never glimpsed on the way past.
                        prefs?.let { pp ->
                            if (pp.screensaverEnabled && pp.screensaverOnWake &&
                                pp.screensaverUrl.isNotBlank() && !userLeftDashboard) {
                                screensaver.show(pp.screensaverUrl) { exitScreensaver() }
                            }
                        }
                        // Reveal only once the dashboard has had time to be resumed and drawn;
                        // dropping the cover on the ACTION_SCREEN_ON tick would show the very
                        // frame it exists to hide. Removal is idempotent and self-limiting.
                        wakeHandler.postDelayed({ sleepCover.hide() }, SLEEP_COVER_REVEAL_MS)
                        // The camera can die silently while the screen is dark (launcher
                        // steal + eviction, events lost to log pruning) — with the flag
                        // never set, ensureCamera would no-op on a dead stream. Verify.
                        if (rtspStreamer?.isStreaming == true) {
                            wakeHandler.removeCallbacks(rtspHealthCheck)
                            wakeHandler.postDelayed(rtspHealthCheck, RTSP_HEALTH_CHECK_MS)
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false; publishState("OFF")
                        // Put the cover up NOW, while the panel is dark, so it is already
                        // composited before the screen lights again. Skipped when the user is
                        // deliberately in another app — there is no flash to hide then, and
                        // blacking out their app for a moment would be its own annoyance.
                        if (!userLeftDashboard) sleepCover.show()
                        // Either park the photos out of sight but LOADED, so a wake can show
                        // them instantly, or tear them down entirely. Prestaging is what makes
                        // "wake to photos" usable — otherwise the first thing you see walking up
                        // to the Portal is ImmichFrame's blank shell booting.
                        val p = prefs
                        if (p != null && p.screensaverEnabled && p.screensaverPrestage &&
                            p.screensaverUrl.isNotBlank() && !userLeftDashboard) {
                            screensaver.prestage(p.screensaverUrl) { exitScreensaver() }
                        } else screensaver.hide()
                    }
                    // A real Home press, and nothing else, broadcasts this with reason=homekey
                    // (recents counts too — also a deliberate departure). It is the only way
                    // that particular choice reaches us, because the system consumes the
                    // gesture itself and no touch is ever dispatched to our window.
                    Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                        val reason = intent.getStringExtra("reason")
                        if (reason == "homekey" || reason == "recentapps") {
                            lastHomeKeyMs = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        })
    }

    // While the screen is off, Portal's launcher (com.facebook.alohaapps.launcher)
    // asserts HOME behind the dark screen, so we wake to the launcher instead of
    // the dashboard. Bring our dashboard back to the front on screen-on. Our
    // ── DLNA / Music Assistant speaker ──────────────────────────────────────────

    /**
     * One now-playing overlay, shared by both speaker roles. Whichever transport is actually
     * playing supplies the details; the controls always go out to Music Assistant, which owns
     * the queue either way.
     */
    private fun ensureNowPlayingOverlay(): NowPlayingOverlay {
        nowPlayingOverlay?.let { return it }
        return NowPlayingOverlay(
            this,
            // Sendspin carries transport on its own controller channel, aimed at this player;
            // the DLNA path has to go through HA because a DLNA renderer doesn't own the queue.
            onPrev = { if (sendspinDriving) sendspinPlayer?.previous() else MaControl.previous(this) },
            onNext = { if (sendspinDriving) sendspinPlayer?.next() else MaControl.next(this) },
            onPlayPause = {
                when {
                    sendspinDriving -> sendspinPlayer?.playPause(ssPlaying)
                    maDriving -> MaControl.playPause(this)
                    else -> dlnaRenderer?.playPauseToggle()
                }
            },
            onStop = { if (sendspinDriving) sendspinPlayer?.stopPlayback() else dlnaRenderer?.stopFromUi() },
            onSetVolume = { pct ->
                if (sendspinDriving) sendspinPlayer?.setVolume(pct) else dlnaRenderer?.setVolumeFromUi(pct)
            },
            // Scrubbing the progress bar. Re-anchor the position clock straight away so the lyric
            // highlight lands on the new spot instead of drifting back until the server catches up.
            onSeek = { ms ->
                when {
                    sendspinDriving -> {
                        sendspinPlayer?.seekTo(ms)
                        ssPosBaseMs = ms; ssPosBaseAt = SystemClock.elapsedRealtime()
                    }
                    maDriving -> {
                        MaControl.seek(this, ms)
                        maPosBaseMs = ms; maPosBaseAt = SystemClock.elapsedRealtime()
                    }
                }
            },
            onClose = { nowPlayingOverlay?.hide() },
            positionProvider = {
                when {
                    // Sendspin hands us a per-track progress snapshot; count on from it locally.
                    sendspinDriving ->
                        if (ssPlaying) (ssPosBaseMs + (SystemClock.elapsedRealtime() - ssPosBaseAt)).toInt()
                        else ssPosBaseMs
                    maDriving ->
                        if (maPlaying) (maPosBaseMs + (SystemClock.elapsedRealtime() - maPosBaseAt)).toInt()
                        else maPosBaseMs
                    else -> dlnaRenderer?.trackPositionMs() ?: 0
                }
            },
            durationProvider = {
                when {
                    sendspinDriving -> ssDurationMs
                    maDriving -> maDurationMs
                    else -> dlnaRenderer?.trackDurationMs() ?: 0
                }
            },
        ).also { nowPlayingOverlay = it }
    }

    private fun startDlna() {
        if (dlnaRenderer != null) return
        ensureNowPlayingOverlay()
        startMaPoll()
        dlnaRenderer = DlnaRenderer(this, friendlyName = { prefs?.deviceName ?: "Portal" }).apply {
            listener = object : DlnaRenderer.Listener {
                override fun onRendererStateChanged(state: String, np: DlnaRenderer.NowPlaying?) =
                    onDlnaState(state, np)
                override fun onRendererVolumeChanged(volumePct: Int) {
                    nowPlayingOverlay?.setVolume(volumePct)
                }
            }
            start()
        }
    }

    // ── Sendspin (synchronised multi-room) ──────────────────────────────────────

    private fun startSendspin() {
        if (sendspinPlayer != null) return
        Log.i(TAG, "sendspin: starting synced player as '${prefs?.deviceName}'")
        ensureNowPlayingOverlay()
        sendspinPlayer = SendspinPlayer(this, deviceName = { prefs?.deviceName ?: "Portal" }).apply {
            onTrack = { t -> onSendspinTrack(t) }
            onArtwork = { bytes -> nowPlayingOverlay?.setArtwork(bytes) }
            start()
        }
    }

    private fun stopSendspin() {
        sendspinPlayer?.stop(); sendspinPlayer = null
        sendspinDriving = false; ssTrackKey = ""
        nowPlayingOverlay?.hide()
    }

    /** Sendspin pushed new track details — drive the overlay straight off them. */
    private fun onSendspinTrack(t: SendspinPlayer.Track?) {
        val overlayOn = prefs?.nowPlayingOverlayEnabled == true
        if (t == null || !overlayOn || inCall) {
            if (ssTrackKey.isNotEmpty()) { nowPlayingOverlay?.hide(); ssTrackKey = "" }
            sendspinDriving = t != null
            return
        }
        // Sendspin is authoritative while it's playing, so the DLNA/HA path stands down.
        sendspinDriving = true
        ssPlaying = t.playing
        ssDurationMs = t.durationMs
        // Most state messages carry no progress; re-anchoring on those would keep dragging the
        // clock back to a stale snapshot and leave the lyrics trailing the music.
        if (t.progressSeq != ssProgressSeq) {
            ssProgressSeq = t.progressSeq
            ssPosBaseMs = t.positionMs
            ssPosBaseAt = SystemClock.elapsedRealtime()
        }

        val key = "${t.title}|${t.artist}"
        if (key != ssTrackKey) {
            ssTrackKey = key
            Log.i(TAG, "sendspin: now playing '${t.title}' by '${t.artist}' " +
                "pos=${t.positionMs}ms dur=${t.durationMs}ms")
            // artUri is blank: the artwork arrives as bytes on its own channel.
            nowPlayingOverlay?.show(t.title, t.artist, t.album, "", t.playing, 50)
            nowPlayingOverlay?.setLyrics(null)
            thread(isDaemon = true, name = "sendspin-lyrics") {
                val res = Lyrics.fetch(t.artist, t.title, t.album, t.durationMs / 1000)
                val lines = res?.synced
                Log.i(TAG, "sendspin: lyrics for '${t.title}' — " +
                    if (lines.isNullOrEmpty()) "none synced (plain=${res?.plain != null})"
                    else "${lines.size} lines, last at ${lines.last().atMs}ms of ${t.durationMs}ms")
                if (ssTrackKey == key) nowPlayingOverlay?.setLyrics(res)
            }
        } else {
            nowPlayingOverlay?.update(t.title, t.artist, t.album, "", t.playing)
        }
    }

    private fun stopDlna() {
        maPollRunning = false; maDriving = false; maTrackKey = ""
        dlnaRenderer?.stop(); dlnaRenderer = null
        nowPlayingOverlay?.hide(); nowPlayingOverlay = null
        dlnaTrackKey = ""
    }

    // ── Music Assistant state poller ────────────────────────────────────────────
    // Asks HA what this Portal's MA player is actually playing. Under flow mode the renderer
    // only ever hears about the first track of the queue, so this is the only reliable source
    // of the current title/artist/art/position.

    private fun startMaPoll() {
        if (maPollRunning) return
        maPollRunning = true
        thread(isDaemon = true, name = "ma-poll") {
            while (maPollRunning) {
                runCatching { pollMaOnce() }
                    .onFailure { Log.i("PortalHA", "dlna: MA poll failed: ${it.message}") }
                Thread.sleep(3000)
            }
        }
    }

    private fun pollMaOnce() {
        // Sendspin pushes the real thing — don't let the poller fight it for the overlay.
        if (sendspinDriving) return
        val s = MaControl.poll(this)
        if (s == null) {
            // HA not configured / entity not resolvable → leave the overlay to the DLNA path.
            if (maDriving) { maDriving = false; maTrackKey = "" }
            return
        }
        if (!maDriving) Log.i("PortalHA", "dlna: MA state polling is driving the overlay")
        maDriving = true
        maPlaying = s.playing
        maDurationMs = s.durationMs
        // Re-anchor the clock only when HA actually re-reports the position (track change, seek,
        // pause/resume) — otherwise keep counting on the local monotonic clock. Rebasing on every
        // poll would make the progress twitch with network latency, and it keeps us independent
        // of any clock skew between the Portal and HA.
        if (s.positionStamp != maPosStamp || !s.playing) {
            maPosStamp = s.positionStamp
            maPosBaseMs = s.positionMs
            maPosBaseAt = SystemClock.elapsedRealtime()
        }

        val overlayOn = prefs?.nowPlayingOverlayEnabled == true
        if (!overlayOn || inCall) {                 // deliberate: switch off / call → hide at once
            if (maTrackKey.isNotEmpty()) {
                Log.i("PortalHA", "dlna: overlay hide (overlayOn=$overlayOn inCall=$inCall)")
                nowPlayingOverlay?.hide(); maTrackKey = ""
            }
            maIdlePolls = 0
            return
        }
        if (!s.active) {
            // MA dips to a blank/idle state for a moment between tracks; tearing the overlay down
            // on a single such poll is what made the lyrics view vanish mid-song. Need it to be
            // genuinely idle for a few polls running before we believe it.
            maIdlePolls++
            if (maIdlePolls >= 3 && maTrackKey.isNotEmpty()) {
                Log.i("PortalHA", "dlna: overlay hide (idle, state=${s.state})")
                nowPlayingOverlay?.hide(); maTrackKey = ""
            }
            return
        }
        maIdlePolls = 0
        val key = "${s.title}|${s.artist}"
        if (key != maTrackKey) {
            maTrackKey = key
            maPosStamp = s.positionStamp
            maPosBaseMs = s.positionMs
            maPosBaseAt = SystemClock.elapsedRealtime()
            Log.i("PortalHA", "dlna: MA now playing '${s.title}' by '${s.artist}' " +
                "pos=${s.positionMs}ms dur=${s.durationMs}ms")
            nowPlayingOverlay?.show(s.title, s.artist, s.album, s.artUrl, s.playing,
                dlnaRenderer?.volumePct() ?: 50)
            nowPlayingOverlay?.setLyrics(null)          // clear stale lyrics while fetching
            thread(isDaemon = true, name = "ma-lyrics") {
                val res = Lyrics.fetch(s.artist, s.title, s.album, s.durationMs / 1000)
                if (maTrackKey == key) nowPlayingOverlay?.setLyrics(res)
            }
        } else {
            nowPlayingOverlay?.update(s.title, s.artist, s.album, s.artUrl, s.playing)
        }
    }

    // Renderer transport/track changes → drive the now-playing overlay + lyrics. Fires on the
    // renderer's play thread; all overlay methods post to the main thread themselves.
    private fun onDlnaState(state: String, np: DlnaRenderer.NowPlaying?) {
        if (maDriving) return        // HA/MA polling owns the overlay — its track info is correct
        val overlayOn = prefs?.nowPlayingOverlayEnabled == true
        val active = (state == "PLAYING" || state == "PAUSED_PLAYBACK" || state == "TRANSITIONING") && np != null
        if (!overlayOn || !active || inCall) {
            if (state == "STOPPED" || state == "NO_MEDIA_PRESENT" || !overlayOn || inCall)
                nowPlayingOverlay?.hide()
            if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") dlnaTrackKey = ""
            return
        }
        np!!
        val playing = state == "PLAYING"
        val key = "${np.title}|${np.artist}"
        Log.i("PortalHA", "dlna: onState $state title=${np.title} keyChanged=${key != dlnaTrackKey}")
        if (key != dlnaTrackKey) {
            dlnaTrackKey = key
            nowPlayingOverlay?.show(np.title, np.artist, np.album, np.artUri, playing, dlnaRenderer?.volumePct() ?: 50)
            nowPlayingOverlay?.setLyrics(null)              // clear stale lyrics while fetching
            thread(isDaemon = true, name = "dlna-lyrics") {
                val res = Lyrics.fetch(np.artist, np.title, np.album, np.durationSec)
                if (dlnaTrackKey == key) nowPlayingOverlay?.setLyrics(res)   // still the same track
            }
        } else {
            nowPlayingOverlay?.update(np.title, np.artist, np.album, np.artUri, playing)
        }
    }

    // SYSTEM_ALERT_WINDOW permission exempts this from background-start limits.
    // DashboardActivity is singleTask, so this reuses the existing instance.
    private fun reclaimForeground() {
        if (inCall) return   // never shove the dashboard over a live call (it would PiP it)
        // Nor over an app the user deliberately opened: waking the screen is not a request to
        // abandon whatever they were watching.
        if (userLeftDashboard) return
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

        // falcon fires TURN_DONE at the end of EACH turn. But a turn can be one step of a
        // MULTI-TURN dialog — Alexa asks a follow-up ("what's the reminder?") and reopens the
        // mic herself, no wake word. If we reclaim (and return the screen) on the FIRST
        // TURN_DONE, falcon backgrounds → its reopened capture is silenced on A10 → she never
        // hears the answer. So on TURN_DONE we DON'T reclaim immediately: we start a grace
        // window, keeping falcon foreground (behind the cover) + our mic yielded. A follow-up
        // fires another TURN_DONE which resets the timer; we reclaim only once she's truly done.
        alexaTurnDoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (wakeIsAlexa && micYieldedForWake) {
                    wakeHandler.removeCallbacks(reclaimDebounce)
                    val sinceListen = System.currentTimeMillis() - lastListenAtMs
                    if (sinceListen in 1..ALEXA_COLD_ABORT_WINDOW_MS &&
                            alexaColdRetries < ALEXA_COLD_MAX_RETRIES) {
                        retryFailedAlexaTurn("TURN_DONE ${sinceListen}ms after LISTEN")
                        return   // hold the yield; the retried turn drives the state from here
                    }
                    // Slower failures look identical to a real turn from timing alone
                    // (measured: an abort 2.7 s in, error speech starting where a genuine
                    // answer would). falcon logs its own ErrorEvent, so ask it instead —
                    // after a beat, because our logcat reader can lag TURN_DONE slightly.
                    if (alexaColdRetries < ALEXA_COLD_MAX_RETRIES) {
                        val listenAt = lastListenAtMs
                        wakeHandler.postDelayed({
                            val errored = (falconReadiness?.lastErrorMs ?: 0L) > listenAt
                            if (errored && micYieldedForWake && wakeIsAlexa &&
                                    lastListenAtMs == listenAt &&           // no newer turn started
                                    alexaColdRetries < ALEXA_COLD_MAX_RETRIES) {
                                retryFailedAlexaTurn("falcon ErrorEvent")
                            }
                        }, ALEXA_ERROR_CHECK_MS)
                    }
                    if (assistantSpeaking()) {
                        // TURN_DONE can arrive while the response audio is still playing
                        // (long answers, stories) — hold; the playback callback starts the
                        // grace once she actually stops talking.
                        wakeSpeakingSeen = true
                        Log.i(TAG, "wake: falcon TURN_DONE but Alexa still speaking -> holding")
                    } else {
                        Log.i(TAG, "wake: falcon TURN_DONE -> grace (${ALEXA_DIALOG_GRACE_MS}ms) for a possible follow-up")
                        wakeHandler.postDelayed(reclaimDebounce, ALEXA_DIALOG_GRACE_MS)
                    }
                }
            }
        }
        runCatching {
            registerReceiver(alexaTurnDoneReceiver, IntentFilter().apply {
                addAction("com.amazon.alexa.multimodal.falcon.TURN_DONE")
            })
        }

        // Debug: fire the Alexa handoff from adb (no voice needed) — reproduces/verifies
        // cold-start turn failures from the desk. Falcon just listens to the room and ends
        // the turn if nothing is said.
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_ALEXA_WAKE
        debugWakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                Log.i(TAG, "wake: DEBUG_ALEXA_WAKE -> fireAlexaHandoff")
                fireAlexaHandoff()
            }
        }
        runCatching {
            registerReceiver(debugWakeReceiver, IntentFilter("com.aeonos.portalha.DEBUG_ALEXA_WAKE"))
        }

        // Debug: toggle experimental RTSP audio from adb (restarts the stream):
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_STREAM_AUDIO --ez on true
        debugAudioReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val on = intent.getBooleanExtra("on", true)
                val p = prefs ?: return
                p.streamAudioEnabled = on
                Log.i(TAG, "camera: DEBUG_STREAM_AUDIO -> $on (restarting stream)")
                commandExecutor.submit {
                    if (rtspStreamer?.isStreaming == true) {
                        rtspStreamer?.stop()
                        runCatching { Thread.sleep(700) }   // port release, as in restart()
                    }
                    applyCameraState(p)
                }
            }
        }
        runCatching {
            registerReceiver(debugAudioReceiver, IntentFilter("com.aeonos.portalha.DEBUG_STREAM_AUDIO"))
        }

        // Debug: exercise the auto-update prompt from adb. With extras, show the
        // dialog with that content (pure UI smoke test); without extras, run a REAL
        // check right now, bypassing the toggle and schedule (prompts only if GitHub
        // actually has a newer, un-skipped version):
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_UPDATE_PROMPT [--es version 9.9 --es notes "…"]
        debugUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val v = intent.getStringExtra("version")
                if (v == null) {
                    Log.i(TAG, "update: DEBUG_UPDATE_PROMPT -> forcing real check now")
                    maybeAutoUpdateCheck(force = true)
                    return
                }
                startActivity(Intent(this@BridgeService, UpdatePromptActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(UpdatePromptActivity.EXTRA_VERSION, v)
                    .putExtra(UpdatePromptActivity.EXTRA_NOTES, intent.getStringExtra("notes") ?: "")
                    .putExtra(UpdatePromptActivity.EXTRA_APK_URL, intent.getStringExtra("apkUrl") ?: ""))
            }
        }
        runCatching {
            registerReceiver(debugUpdateReceiver, IntentFilter("com.aeonos.portalha.DEBUG_UPDATE_PROMPT"))
        }

        // Debug: score the last ~2.4s of mic audio with the openWakeWord verifiers, so a
        // room's noise floor and a real utterance can be compared when picking a threshold:
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_OWW_SCORE
        // With --es file <path>, scores that WAV instead of the mic buffer (offline harness).
        debugOwwReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val file = intent.getStringExtra("file")
                Thread({
                    if (file != null) wakeDetector?.debugScoreFile(file) else wakeDetector?.debugScore()
                }, "portal-ha-oww-debug").also { it.isDaemon = true }.start()
            }
        }
        runCatching {
            registerReceiver(debugOwwReceiver, IntentFilter("com.aeonos.portalha.DEBUG_OWW_SCORE"))
        }

        // Debug: configure and drive the photo screensaver without typing a URL on the panel.
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_SCREENSAVER --es url http://host:8355
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_SCREENSAVER --es action show|hide
        // "show" bypasses the idle and presence gates so a cold check doesn't mean standing in
        // front of the Portal for two minutes; everything else about the overlay is unchanged.
        debugScreensaverReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val p = prefs ?: return
                intent.getStringExtra("url")?.let {
                    p.screensaverUrl = it
                    p.screensaverEnabled = true
                    Log.i(TAG, "screensaver: url set to '$it' (enabled)")
                }
                when (intent.getStringExtra("action")) {
                    "show" -> screensaver.show(p.screensaverUrl) { exitScreensaver() }
                    "hide" -> exitScreensaver()
                }
            }
        }
        runCatching {
            registerReceiver(debugScreensaverReceiver, IntentFilter("com.aeonos.portalha.DEBUG_SCREENSAVER"))
        }

        // Debug: fill in the connection settings from adb, so a freshly provisioned Portal
        // doesn't have to be typed into by hand on a touchscreen:
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_CONFIG \
        //     --es name Portal-Go --es broker 192.168.0.39 --ei port 1883 \
        //     --es user mqttuser --es haUrl http://192.168.0.39:8123
        // ★Deliberately NO password: it would sit in shell history and the device log. That one
        // stays a typed-in-person field.
        debugConfigReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val p = prefs ?: return
                var changed = false
                intent.getStringExtra("name")?.let { p.deviceName = it; changed = true }
                intent.getStringExtra("broker")?.let { p.brokerHost = it; changed = true }
                intent.getStringExtra("user")?.let { p.username = it; changed = true }
                intent.getStringExtra("haUrl")?.let { p.haUrl = it; changed = true }
                if (intent.hasExtra("port")) {
                    p.brokerPort = intent.getIntExtra("port", 1883); changed = true
                }
                if (!changed) return
                Log.i(TAG, "config: name='${p.deviceName}' broker='${p.brokerHost}:${p.brokerPort}' " +
                    "user='${p.username}' haUrl='${p.haUrl}' (password unchanged)")
                // Reconnect on the new details rather than waiting for a restart.
                restartMqtt()
            }
        }
        runCatching {
            registerReceiver(debugConfigReceiver, IntentFilter("com.aeonos.portalha.DEBUG_CONFIG"))
        }

        // Debug: open a settings screen from adb. The settings activities are
        // exported=false (nothing else should be able to launch them), so `am start`
        // is refused — this is the smoke-test route after UI changes:
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_OPEN_SCREEN --es screen VoiceSettingsActivity
        debugScreenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val name = intent.getStringExtra("screen") ?: return
                runCatching {
                    startActivity(Intent().setClassName(packageName, "$packageName.$name")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Log.i(TAG, "debug: opened $name")
                }.onFailure { Log.w(TAG, "debug: could not open '$name': ${it.message}") }
            }
        }
        runCatching {
            registerReceiver(debugScreenReceiver, IntentFilter("com.aeonos.portalha.DEBUG_OPEN_SCREEN"))
        }

        // Debug: place an outbound Meta call from adb, e.g.:
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_PLACE_CALL --es self <selfFbid> --es to <calleeFbid> [--ez video true]
        debugCallReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val self = intent.getStringExtra("self") ?: ""
                val to = intent.getStringExtra("to") ?: ""
                val video = intent.getBooleanExtra("video", false)
                Log.i(TAG, "call: DEBUG_PLACE_CALL self=$self to=$to video=$video")
                PortalCaller.placeCall(this@BridgeService, self, to, video)
            }
        }
        runCatching {
            registerReceiver(debugCallReceiver, IntentFilter("com.aeonos.portalha.DEBUG_PLACE_CALL"))
        }

        // Debug: exercise the Calls auto-return without waiting the full minutes. The seconds
        // value overrides callReturnMinutes until the next restart; 0 restores the pref.
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_CALL_RETURN --ei secs 10
        debugCallReturnReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                callReturnDebugSecs = intent.getIntExtra("secs", 0)
                Log.i(TAG, "calls: DEBUG_CALL_RETURN override = ${callReturnDebugSecs}s (0 = use pref)")
                timeoutHandler.post { runCatching { checkCallReturn() } }
            }
        }
        runCatching {
            registerReceiver(debugCallReturnReceiver, IntentFilter("com.aeonos.portalha.DEBUG_CALL_RETURN"))
        }

        // Debug: inject a tap at a screen fraction to verify gesture dispatch / find the spot
        // that dismisses the launcher photo home.
        //   adb shell am broadcast -a com.aeonos.portalha.DEBUG_TAP --ef fx 0.5 --ef fy 0.06
        debugTapReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val fx = intent.getFloatExtra("fx", 0.5f)
                val fy = intent.getFloatExtra("fy", 0.5f)
                Log.i(TAG, "calls: DEBUG_TAP ($fx,$fy)")
                ScreenAccessibility.instance?.tapFraction(fx, fy)
                    ?: Log.w(TAG, "calls: DEBUG_TAP — no accessibility service")
            }
        }
        runCatching {
            registerReceiver(debugTapReceiver, IntentFilter("com.aeonos.portalha.DEBUG_TAP"))
        }
    }

    // ── MQTT loop ─────────────────────────────────────────────────────────────

    /**
     * Drop the current broker connection so [mqttLoop] rebuilds it from the current prefs.
     * Used after the connection settings are changed from outside the UI; the loop's own retry
     * is what actually reconnects, so this only has to make the existing session end.
     */
    private fun restartMqtt() {
        runCatching { mqtt?.disconnect(0) }
        mqtt = null
        Log.i(TAG, "config: dropped the broker connection — reconnecting with the new settings")
    }

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
            HaDiscovery.showDashboardCommandTopic(p.deviceId),
            HaDiscovery.screensaverCommandTopic(p.deviceId),
            HaDiscovery.screensaverDismissCommandTopic(p.deviceId),
            HaDiscovery.screensaverHoldCommandTopic(p.deviceId),
            // Shared across the fleet, so one HA action clears the photos everywhere.
            HaDiscovery.SCREENSAVER_FLEET_DISMISS_TOPIC,
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
            HaDiscovery.haTokenCommandTopic(p.deviceId),
            HaDiscovery.dlnaCommandTopic(p.deviceId),
            HaDiscovery.sendspinCommandTopic(p.deviceId),
            HaDiscovery.npOverlayCommandTopic(p.deviceId)
        ).forEach { client.subscribe(it, 1) }

        // Intercom: subscribe to presence/lock/audio and announce ourselves.
        intercom?.subscriptions()?.forEach { (topic, qos) -> client.subscribe(topic, qos) }
        intercom?.publishPresence()

        // Clear stale retained entities from old builds
        HaDiscovery.staleTopics(p.deviceId).forEach { topic -> client.publish(topic, emptyRetained()) }

        // Discovery
        publishDiscovery(client, p)

        // In-call state is event-driven (audio playback callback); publish the
        // current value so HA has it from the first connect.
        publishRaw(HaDiscovery.inCallStateTopic(p.deviceId), if (inCall) "ON" else "OFF", 1, retained = true)

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
            // MUST run on the commandExecutor: every other stream start/stop/restart
            // is serialized on that single thread, and restart() sleeps OUTSIDE the
            // applyCameraState lock — calling from the MQTT thread raced the boot
            // orientation restart mid-sleep (two servers fought, one leaked the port).
            if (p.cameraOn) {
                commandExecutor.submit {
                    Log.i(TAG, "restoring camera ON (persisted desired state)")
                    applyCameraState(p)
                }
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
        pub(HaDiscovery.inCallDiscoveryTopic(p.deviceId), HaDiscovery.inCallConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.showDashboardDiscoveryTopic(p.deviceId), HaDiscovery.showDashboardConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.screensaverDiscoveryTopic(p.deviceId), HaDiscovery.screensaverConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.screensaverDismissDiscoveryTopic(p.deviceId), HaDiscovery.screensaverDismissConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.screensaverHoldDiscoveryTopic(p.deviceId), HaDiscovery.screensaverHoldConfigPayload(p.deviceId, p.deviceName))
        // Fleet button: identical payload from every Portal, so HA keeps exactly one entity.
        pub(HaDiscovery.fleetScreensaverDismissDiscoveryTopic(), HaDiscovery.fleetScreensaverDismissConfigPayload())
        pub(HaDiscovery.brightnessDiscoveryTopic(p.deviceId), HaDiscovery.brightnessConfigPayload(p.deviceId, p.deviceName))
        // HA long-lived token, settable from HA (for the Jarvis tool-provider's smart-home control).
        pub(HaDiscovery.haTokenDiscoveryTopic(p.deviceId), HaDiscovery.haTokenConfigPayload(p.deviceId, p.deviceName))
        // Music-speaker (DLNA) on/off, and the now-playing overlay on/off.
        pub(HaDiscovery.dlnaDiscoveryTopic(p.deviceId), HaDiscovery.dlnaConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.sendspinDiscoveryTopic(p.deviceId), HaDiscovery.sendspinConfigPayload(p.deviceId, p.deviceName))
        pub(HaDiscovery.npOverlayDiscoveryTopic(p.deviceId), HaDiscovery.npOverlayConfigPayload(p.deviceId, p.deviceName))
        publishDlnaState(p)

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
            HaDiscovery.showDashboardCommandTopic(p.deviceId)     -> if (payload == "show") showDashboard()
            HaDiscovery.screensaverCommandTopic(p.deviceId)       -> handleScreensaverCommand(payload, p)
            HaDiscovery.screensaverDismissCommandTopic(p.deviceId),
            HaDiscovery.SCREENSAVER_FLEET_DISMISS_TOPIC           -> dismissScreensaverFromHa(payload, p)
            HaDiscovery.screensaverHoldCommandTopic(p.deviceId)   -> handleScreensaverHoldCommand(payload, p)
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
            HaDiscovery.dlnaCommandTopic(p.deviceId)              -> handleDlnaCommand(payload, p)
            HaDiscovery.sendspinCommandTopic(p.deviceId)          -> handleSendspinCommand(payload, p)
            HaDiscovery.npOverlayCommandTopic(p.deviceId)         -> handleNpOverlayCommand(payload, p)
        }
    }

    private fun handleDlnaCommand(payload: String, p: Prefs) {
        val on = payload.equals("ON", ignoreCase = true)
        if (on != p.dlnaEnabled) p.dlnaEnabled = on
        if (on) startDlna() else stopDlna()
        publishDlnaState(p)
        Log.i(TAG, "dlna: HA set speaker enabled=$on")
    }

    private fun handleNpOverlayCommand(payload: String, p: Prefs) {
        val on = payload.equals("ON", ignoreCase = true)
        if (on != p.nowPlayingOverlayEnabled) p.nowPlayingOverlayEnabled = on
        if (!on) nowPlayingOverlay?.hide()   // takes effect for the next track if turned back on
        publishDlnaState(p)
        Log.i(TAG, "dlna: HA set now-playing overlay enabled=$on")
    }

    private fun handleSendspinCommand(payload: String, p: Prefs) {
        val on = payload.equals("ON", ignoreCase = true)
        if (on != p.sendspinEnabled) p.sendspinEnabled = on
        if (on) startSendspin() else stopSendspin()
        publishDlnaState(p)
        Log.i(TAG, "sendspin: HA set synced speaker enabled=$on")
    }

    private fun publishDlnaState(p: Prefs) {
        publishRaw(HaDiscovery.sendspinStateTopic(p.deviceId), if (p.sendspinEnabled) "ON" else "OFF", 1, retained = true)
        publishRaw(HaDiscovery.dlnaStateTopic(p.deviceId), if (p.dlnaEnabled) "ON" else "OFF", 1, retained = true)
        publishRaw(HaDiscovery.npOverlayStateTopic(p.deviceId), if (p.nowPlayingOverlayEnabled) "ON" else "OFF", 1, retained = true)
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
    // mutually exclusive (each opens the camera directly). Every caller must be
    // on the single-threaded commandExecutor — restart()'s stop/sleep/start runs
    // OUTSIDE this lock, so only executor serialization prevents two servers
    // fighting over port 8554. @Synchronized kept as a belt.
    @Synchronized
    private fun applyCameraState(p: Prefs) {
        val on = p.cameraServiceEnabled && p.cameraOn
        when {
            on && p.streamEnabled -> {
                stopCameraStreamSilently()   // RTSP needs Camera 0
                val r = rtspStreamer ?: RtspStreamer(this).also {
                    rtspStreamer = it
                    it.onStreamDead = { reason -> onRtspStreamDead(reason) }
                }
                r.rotationOffset = p.streamRotation
                if (!r.isStreaming) {
                    // withAudio taps SoundMonitor's capture (MicTapSource) — the
                    // stream itself never opens the mic, so calls/Alexa/wake word
                    // are unaffected; their yields just mute the track briefly.
                    val ok = r.start(1280, 720, 15, 2_000_000, withAudio = p.streamAudioEnabled)
                    cameraActive = ok
                    publishRaw(HaDiscovery.cameraStateTopic(p.deviceId), if (ok) "ON" else "OFF", 1, retained = true)
                    if (ok) noteRtspStarted() else Log.w(TAG, "RTSP failed to start")
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
            if (r.isStreaming) { r.restart(); noteRtspStarted() }
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
        // Our own wake word (Jarvis or Alexa) needs the mic, so it overrides coexist.
        val coexist = p.coexistVoiceAssistant && !p.wakeWordEnabled && !p.alexaWakeEnabled
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
    // Hands-free 2-way channel opened/closed (from the shared signal, on ANY Portal — that's
    // the auto-arm). While open, hand the mic to the AEC VOICE_COMMUNICATION engine (VOX +
    // first-come floor lock); when closed, restore the warm mic (sound sensor + wake word).
    // A blue orb marks the live channel.
    private fun onTwoWayChannelChanged(open: Boolean) {
        wakeHandler.post {
            if (open == twoWayChannelOpen) return@post
            twoWayChannelOpen = open
            if (open) {
                if (inCall) {
                    // NEVER arm the 2-way mic during a live Meta call — VOX would pick up
                    // the call conversation and broadcast it to every other Portal.
                    Log.i(TAG, "2way: channel opened while in a call — not arming on this Portal")
                    twoWayChannelOpen = false
                    return@post
                }
                lastTwoWayActivityMs = System.currentTimeMillis()
                receiveOrb?.hide()          // the announce orb hands off to the 2-way orb
                soundMonitor?.stop()
                wakeHandler.postDelayed({ if (twoWayChannelOpen) twoWay?.start() }, 350L)
                if (twoWayOrb == null) twoWayOrb = AnnounceOrbOverlay(this, blue = true, interactive = true)
                    .also { it.onTap = { intercom?.closeTwoWayChannel() } }   // tap the Portal to hang up
                twoWayOrb?.show(); twoWayOrb?.setLive(true)
                wakeHandler.removeCallbacks(twoWayIdleCheck)
                wakeHandler.postDelayed(twoWayIdleCheck, 500L)
                Log.i(TAG, "2way channel: OPEN — engine armed")
            } else {
                wakeHandler.removeCallbacks(twoWayIdleCheck)
                twoWay?.stop()
                val coexist = prefs?.let { it.coexistVoiceAssistant && !it.wakeWordEnabled && !it.alexaWakeEnabled } ?: false
                if (!coexist && soundMonitor?.isRunning() == false) soundMonitor?.start()
                twoWayOrb?.hide(); twoWayOrb = null
                Log.i(TAG, "2way channel: closed — mic restored")
            }
        }
    }

    // ── Daily auto update check (opt-in) ─────────────────────────────────────

    private val autoUpdateTick = object : Runnable {
        override fun run() {
            maybeAutoUpdateCheck()
            wakeHandler.postDelayed(this, UPDATE_TICK_MS)
        }
    }

    // Once a day at a drifting random hour: ask GitHub for the latest release and,
    // if it's newer and not the version the user skipped, pop the update prompt
    // (changelog + Update now / Skip this version / Later). The prompt only lands
    // while the screen is on and no call is up — otherwise the due check just
    // waits for the next hourly tick.
    private fun maybeAutoUpdateCheck(force: Boolean = false) {
        val p = prefs ?: return
        if (!force) {
            if (!p.autoUpdateCheck) return
            val now = System.currentTimeMillis()
            if (p.nextUpdateCheckMs == 0L) {
                // First arming: land at a random point in the next 24 h — staggers the
                // fleet so the Portals don't all hit GitHub at the same minute.
                p.nextUpdateCheckMs = now + (Math.random() * 24 * 3_600_000L).toLong()
                Log.i(TAG, "update: auto-check armed, first check in ${(p.nextUpdateCheckMs - now) / 60_000L} min")
                return
            }
            if (now < p.nextUpdateCheckMs) return
            if (!screenOn || inCall) return
        }
        val now = System.currentTimeMillis()
        Thread({
            val rel = runCatching { Updater.fetchLatest() }.getOrNull()
            wakeHandler.post {
                if (rel == null) {
                    p.nextUpdateCheckMs = now + 2 * 3_600_000L   // network hiccup — retry in 2 h
                    return@post
                }
                // Next check 20–28 h out: keeps roughly daily cadence while the
                // hour drifts randomly across days.
                p.nextUpdateCheckMs = now + 20 * 3_600_000L + (Math.random() * 8 * 3_600_000L).toLong()
                if (!Updater.isNewer(rel.version, BuildConfig.VERSION_NAME)) return@post
                if (rel.version == p.skippedUpdateVersion) {
                    Log.i(TAG, "update: v${rel.version} available but skipped by user")
                    return@post
                }
                Log.i(TAG, "update: v${rel.version} available (auto check) — prompting")
                runCatching {
                    startActivity(Intent(this@BridgeService, UpdatePromptActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(UpdatePromptActivity.EXTRA_VERSION, rel.version)
                        .putExtra(UpdatePromptActivity.EXTRA_NOTES, rel.notes)
                        .putExtra(UpdatePromptActivity.EXTRA_APK_URL, rel.apkUrl))
                }
            }
        }, "portal-ha-update-check").also { it.isDaemon = true }.start()
    }

    // While the channel is open, drop it on a short burst of true silence — nobody talking
    // locally (engine has no floor) AND nothing coming in. Keeps the timer fresh while I talk.
    private val twoWayIdleCheck = object : Runnable {
        override fun run() {
            if (!twoWayChannelOpen) return
            if (twoWay?.talking == true) lastTwoWayActivityMs = System.currentTimeMillis()
            val idleMs = (prefs?.twoWayIdleSecs ?: 2).coerceIn(2, 60) * 1_000L
            if (System.currentTimeMillis() - lastTwoWayActivityMs > idleMs) {
                Log.i(TAG, "2way channel: silent — closing")
                intercom?.closeTwoWayChannel()
            } else wakeHandler.postDelayed(this, 400L)
        }
    }

    // "<wake phrase> announce" matched — hands-free intercom broadcast (no assistant).
    // Wake matching pauses for the whole possible window (armed + live) so our own
    // announcement audio can't trigger anything; VoiceAnnounce.onDone re-arms sooner.
    private fun startVoiceAnnounce() {
        if (micYieldedForWake) {
            Log.i(TAG, "announce: wake during a yielded turn — ignored")
            return
        }
        if (inCall) {
            Log.i(TAG, "announce: wake during a live call — ignored")
            return
        }
        val p = prefs ?: return
        if (!p.voiceAnnounceEnabled) { Log.i(TAG, "announce: disabled in settings"); return }
        wakeDetector?.pauseMatching(36_000L)
        if (voiceAnnounce?.start() != true) wakeDetector?.pauseMatching(2_000L)
    }

    private fun fireWakeHandoff() {
        // The barge-in mic runs while Alexa speaks, so the OTHER wake routes can match on
        // her audio mid-turn — only the Alexa barge-in is valid then; don't stack a Jarvis
        // handoff (or the assist button) on top of a live Alexa conversation.
        if (micYieldedForWake) {
            Log.i(TAG, "wake: Jarvis/assist wake during a yielded turn — ignored")
            return
        }
        if (inCall) {
            Log.i(TAG, "wake: Jarvis/assist wake during a live call — ignored")
            return
        }
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
                // Cover the screen FIRST and wait until it is verifiably on-screen, only
                // then bring the assistant up behind it — it grabs the mic completely
                // unseen. The cover comes down in bringDashboardToFront once it has the mic.
                showWakeCover(Runnable {
                    runCatching {
                        packageManager.getLaunchIntentForPackage(pkg)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            ?.let { startActivity(it); Log.i(TAG, "wake: brought $pkg to foreground") }
                    }.onFailure { Log.w(TAG, "wake: foreground launch failed: ${it.message}") }
                    wakeHandler.postDelayed({ broadcastAndYield() }, WAKE_FOREGROUND_MS)   // let the activity resume first
                })
            }
        } else {
            broadcastAndYield()
        }
    }

    // Alexa handoff: our Vosk detector heard "alexa" → poke the revived falcon client with
    // its own LISTEN broadcast (reverse-engineered from millennium; the SAME thing millennium
    // fires). Then release our warm mic so falcon can capture the follow-up command, and
    // reclaim it when falcon is done (the shared yield/reclaim path detects the assistant's
    // recording by session id and restores our mic + wake word). No screen cover / foreground
    // dance needed: falcon runs headless and, on the A9 Portals this is supported on, captures
    // fine in the background. falcon must already be linked + connected (ReadyState) — it stays
    // connected once the initial kick has established it (see Immortal provisioning).
    // True while the current handoff is to Alexa/falcon (vs Jarvis). Changes the reclaim
    // behaviour: unlike Jarvis (screen returns the instant it grabs the mic), falcon must
    // STAY foreground for the whole turn on A10 — backgrounding it re-silences its capture
    // and hides its response UI. So we hold the screen on falcon until the turn is done.
    @Volatile private var wakeIsAlexa = false
    private var alexaBar: AlexaBarOverlay? = null
    private var falconReadiness: FalconReadiness? = null

    private val screensaver by lazy { ScreensaverOverlay(this) }
    private val sleepCover by lazy { SleepCover(this) }
    private var dreamObserver: android.database.ContentObserver? = null
    @Volatile private var lastDreamClaimMs = 0L
    @Volatile private var dreamFightLogged = false
    // Time of the last REAL interaction (a touch or key on the dashboard). Deliberately not
    // lastActivityMs: presence resets that one to hold the screen awake, so a photo frame keyed
    // off it could never appear while somebody was standing in front of the Portal — which is
    // precisely when you want it.
    @Volatile private var lastInteractionMs = System.currentTimeMillis()
    // Set by an HA dismiss: no photos until this passes, however quiet the Portal goes.
    @Volatile private var screensaverHoldUntilMs = 0L

    @Volatile private var lastListenAtMs = 0L
    @Volatile private var alexaColdRetries = 0

    // Re-fire LISTEN after a turn falcon failed. Shared by both detection routes (the
    // fast TURN_DONE window and falcon's own ErrorEvent) so the retry timing — which is
    // the fragile part, see ALEXA_COLD_RETRY_MS — lives in exactly one place.
    private fun retryFailedAlexaTurn(reason: String) {
        alexaColdRetries++
        val delay = if (alexaColdRetries == 1) ALEXA_COLD_RETRY_MS else ALEXA_COLD_RETRY2_MS
        // ★This is what actually silences "sorry, something went wrong". We learn the turn
        // failed BEFORE she starts apologising — measured 2026-08-02: falcon's ErrorEvent
        // at LISTEN+165 ms, our detection at +206 ms, her speech at +241 ms — so muting
        // here beats the audio out by ~35 ms. The retry still recovers the turn; the user
        // gets a second of silence and then the real answer instead of half an apology.
        // Restored after the retry has taken hold (her real answer is seconds later).
        muteAlexaOutput(delay + ALEXA_ABORT_MUTE_TAIL_MS, "cold abort")
        Log.i(TAG, "wake: failed Alexa turn ($reason) -> retry #$alexaColdRetries in ${delay}ms")
        wakeHandler.postDelayed({
            if (micYieldedForWake && wakeIsAlexa) broadcastAlexaListen("cold-retry")
        }, delay)
    }

    private fun broadcastAlexaListen(tag: String) {
        runCatching {
            sendBroadcast(Intent("com.amazon.alexa.multimodal.falcon.LISTEN")
                .setPackage("com.amazon.alexa.multimodal.falcon")
                .addFlags(0x10000020))
            lastListenAtMs = System.currentTimeMillis()
            Log.i(TAG, "wake: fired Alexa LISTEN -> falcon ($tag)")
        }.onFailure { Log.w(TAG, "wake: alexa LISTEN failed: ${it.message}") }
    }

    // "alexa stop" spoken in one breath. A LISTEN can't act on words already spoken —
    // falcon would just listen to the room for ~8s, error out ("something went wrong"),
    // and kill the music session on its way down. So act locally instead.
    private fun fireAlexaStop() {
        if (micYieldedForWake) {
            if (wakeIsAlexa) {
                // Mid-turn (story speech): the barge-in LISTEN itself cuts her speech —
                // that IS the stop. She'll briefly listen and end the turn quietly.
                Log.i(TAG, "wake: 'alexa stop' mid-turn -> barge cuts her speech")
                fireAlexaHandoff()
            } else Log.i(TAG, "wake: 'alexa stop' during a non-Alexa turn — ignored")
            return
        }
        if (falconPlaying()) {
            // Music / long-form playback with the turn long over: pause her player
            // directly — instant, offline, and it doesn't tear down the session.
            Log.i(TAG, "wake: 'alexa stop' -> pausing falcon playback (media key)")
            dispatchMediaPause()
            wakeHandler.postDelayed({
                if (inCall) return@postDelayed   // a call connected meanwhile — leave it be
                if (falconPlaying()) {
                    // The key didn't take (e.g. a timer alarm, or key routing lost) —
                    // fall back to a normal listen so the user can repeat the command.
                    Log.i(TAG, "wake: media pause didn't take -> normal handoff")
                    fireAlexaHandoff()
                } else {
                    Log.i(TAG, "wake: falcon playback stopped")
                    // On A9 falcon's card is still on screen with nothing playing —
                    // restore the dashboard (no-op when we're already front).
                    bringDashboardToFront()
                }
            }, 800L)
            return
        }
        // Nothing of hers is playing — behave like a plain wake so she hears the intent.
        fireAlexaHandoff()
    }

    private fun dispatchMediaPause() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        }.onFailure { Log.w(TAG, "wake: media key dispatch failed: ${it.message}") }
    }

    private fun fireAlexaHandoff() {
        // A live Meta call owns the screen and the mic: foregrounding falcon would punt
        // the call into picture-in-picture and the capture would fail anyway.
        if (inCall) {
            Log.i(TAG, "wake: 'alexa' during a live call — ignored")
            wakeHandler.post {
                runCatching { Toast.makeText(this, "In a call — Alexa is unavailable", Toast.LENGTH_SHORT).show() }
            }
            return
        }
        // Barge-in: the wake word matched while a held Alexa turn is mid-speech (our mic
        // runs during her speaking phase — see startBargeListen). Falcon is already
        // foreground behind the cover, so no yield/cover dance: free the mic slot, then
        // re-fire LISTEN — falcon cuts its own speech and listens ("alexa, stop" mid-story).
        if (micYieldedForWake) {
            if (!wakeIsAlexa) { Log.i(TAG, "wake: 'alexa' during a non-Alexa turn — ignored"); return }
            Log.i(TAG, "wake: barge-in — interrupting Alexa")
            wakeHandler.removeCallbacks(reclaimDebounce)
            stopBargeListen("barge-in")
            wakeHandler.postDelayed({ broadcastAlexaListen("barge-in") }, ALEXA_BARGE_LISTEN_MS)
            return
        }
        // After a boot, falcon can take a while to reconnect on A10. Firing LISTEN at a
        // disconnected falcon just yields "sorry, something went wrong" — so if it isn't
        // ReadyState yet, tell the user it's starting up and skip; it'll work once connected.
        if (falconReadiness?.isReady() == false) {
            Log.i(TAG, "wake: heard 'alexa' but falcon not connected yet — skipping (still starting up)")
            wakeHandler.post {
                runCatching {
                    Toast.makeText(this, "Alexa is still starting up — try again in a moment",
                        Toast.LENGTH_SHORT).show()
                }
            }
            wakeDetector?.pauseMatching(2_000L)   // don't machine-gun retries
            return
        }
        wakeIsAlexa = true
        // ORDER MATTERS (this was the "sorry, something went wrong" bug): free OUR mic FIRST
        // so the slot is actually available when falcon captures, THEN bring falcon foreground,
        // THEN — after it has resumed and the slot has freed — fire LISTEN.
        yieldMicForWake()   // stops SoundMonitor + arms the reclaim watch
        fun fireListen() {
            broadcastAlexaListen("handoff")
            // Echo-style listening bar as the "speak now" cue (falcon's own UI is hidden by
            // the cover). Shows above the cover; hidden at reclaim (turn done).
            (alexaBar ?: AlexaBarOverlay(this).also { alexaBar = it }).show()
        }
        // Android 10 SILENCES a recorder that isn't the foreground app (verified: millennium
        // gets `silenced:true` in the background). Bring falcon's own activity to the front so
        // its capture is un-silenced; it renders its Alexa response (weather card etc.) itself.
        // Held foreground for the whole turn (wakeIsAlexa) — see onWakeRecordingChanged. On A9
        // falcon captures fine headless, so just fire.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Seamless, exactly like Jarvis: cover the screen FIRST, then bring falcon up
            // BEHIND the cover. The cover is an overlay, so falcon is still the top ACTIVITY
            // (its mic is un-silenced on A10) but stays hidden; its audio response plays
            // through. Unlike Jarvis we keep the cover up for the WHOLE turn (falcon must stay
            // foreground on A10) — reclaim (falcon's TURN_DONE) drops it back to the dashboard.
            wakeHandler.post {
                showWakeCover(Runnable {
                    runCatching {
                        startActivity(Intent()
                            .setClassName("com.amazon.alexa.multimodal.falcon",
                                "com.amazon.alexa.multimodal.falcon.SIMActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                        Log.i(TAG, "wake: brought falcon foreground behind cover (A10 un-silence)")
                    }.onFailure { Log.w(TAG, "wake: falcon foreground failed: ${it.message}") }
                    wakeHandler.postDelayed({ fireListen() }, ALEXA_FOREGROUND_MS)
                })
            }
        } else {
            fireListen()
        }
    }

    // After the wake fires, the assistant needs the mic but our SoundMonitor is holding
    // it — so the assistant hears silence ("ignores you"). We split the handoff into two
    // independent stages so the SCREEN comes back as fast as physically possible:
    //
    //   1. Screen: the assistant only needs the FOREGROUND to *acquire* the mic (an
    //      in-progress capture keeps running once it's backgrounded, Android 10+). So the
    //      instant it grabs the mic we hand the screen straight back to our dashboard —
    //      typically within ~1s of the wake, not when the whole conversation ends.
    //   2. Mic: our SoundMonitor stays yielded until the assistant actually STOPS
    //      recording (end of the conversation), then we reclaim the warm mic.
    //
    // Both transitions are detected event-driven via an AudioRecordingCallback (fires the
    // millisecond the recording set changes) rather than polling, so there's no fixed
    // settle/poll latency. We tell the assistant's recording apart from our own by audio
    // session id (SoundMonitor.audioSessionId) — no dependence on release/acquire timing,
    // so a fast assistant grab can't be mistaken for "nothing there".
    @Volatile private var micYieldedForWake = false
    @Volatile private var wakeConsumerSeen = false
    @Volatile private var wakeFocusReturned = false
    @Volatile private var wakeYieldStartMs = 0L
    private var wakeRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var wakePlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    @Volatile private var wakeSpeakingSeen = false
    private val wakeHandler = Handler(Looper.getMainLooper())
    private val reclaimTimeout = object : Runnable {
        override fun run() {
            // The cap is a safety net for turns that end weirdly — not a limit on a healthy
            // long interaction. A story alternates speaking and listening for minutes; if
            // Alexa is audibly mid-answer OR holding the mic, push the cap back instead of
            // cutting her off; going idle re-enters the normal grace path. A pending grace
            // also defers it (it reclaims within seconds anyway) — the cap once beat a
            // fresh grace by 200ms and cut a possible follow-up short.
            val gracePending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                wakeHandler.hasCallbacks(reclaimDebounce)
            if (wakeIsAlexa && micYieldedForWake &&
                (assistantSpeaking() || assistantRecording() || gracePending)) {
                Log.i(TAG, "wake: turn cap reached but Alexa still active -> extending")
                wakeHandler.postDelayed(this, 20_000L)
                return
            }
            reclaimMicAfterWake("timeout")
        }
    }

    // Any active recorder that isn't our own SoundMonitor = the assistant capturing.
    private fun assistantRecording(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        val ours = soundMonitor?.audioSessionId ?: -1
        val configs = runCatching { am.activeRecordingConfigurations }.getOrDefault(emptyList())
        return configs.any { it.clientAudioSessionId != ours }
    }

    // Falcon's uid + the hidden AudioPlaybackConfiguration.getClientUid() — used to tell
    // "falcon is playing SOMETHING" (voice OR music) apart from our own dashboard WebView's
    // permanently-active media player, which usage tags alone cannot do. Reflection works on
    // the fleet because provisioning sets hidden_api_policy=1; when it doesn't, callers fall
    // back to the voice-only fingerprint below.
    private val falconUid: Int by lazy {
        runCatching {
            packageManager.getApplicationInfo("com.amazon.alexa.multimodal.falcon", 0).uid
        }.getOrDefault(-1)
    }
    private val playbackClientUidMethod: java.lang.reflect.Method? by lazy {
        runCatching { AudioPlaybackConfiguration::class.java.getMethod("getClientUid") }.getOrNull()
    }

    // Any active player owned by falcon — its voice, a story soundtrack, or music.
    private fun falconPlaying(): Boolean {
        if (falconUid < 0) return false
        val m = playbackClientUidMethod ?: return assistantSpeaking()
        val am = getSystemService(AudioManager::class.java) ?: return false
        val configs = runCatching { am.activePlaybackConfigurations }.getOrDefault(emptyList())
        return configs.any { cfg -> runCatching { m.invoke(cfg) as? Int }.getOrNull() == falconUid }
    }

    // Alexa's voice, as it actually appears on the Portal (measured via dumpsys audio,
    // 2026-07-07): an AudioTrack from falcon tagged USAGE_ASSISTANCE_SONIFICATION +
    // CONTENT_TYPE_SPEECH. She does NOT use USAGE_ASSISTANT. That exact pair is the
    // narrowest "Alexa is talking" signature: the listening beeps are SONIFICATION content
    // (systemui), and our own dashboard WebView keeps a permanent MEDIA player alive —
    // matching either of those would hold the turn open forever. Falcon MUSIC playback is
    // deliberately not matched: it survives backgrounding, so reclaiming under it is fine.
    private fun assistantSpeaking(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        val configs = runCatching { am.activePlaybackConfigurations }.getOrDefault(emptyList())
        return configs.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION &&
                it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH
        }
    }

    // ── Barge-in ──────────────────────────────────────────────────────────────
    // While Alexa SPEAKS her mic is closed, so the Portal's capture slot is free: run our
    // warm mic + wake detector for exactly that window, letting "alexa" interrupt a long
    // response ("alexa, stop" mid-story). The slot MUST be handed straight back the moment
    // she wants it (her speech ends / her capture appears) — one of our recorders squatting
    // on the slot while falcon captures is the original "sorry, something went wrong" bug.
    private fun startBargeListen() {
        if (!micYieldedForWake || !wakeIsAlexa) return
        if (prefs?.alexaWakeEnabled != true) return
        if (soundMonitor?.isRunning() != false) return
        soundMonitor?.start()
        Log.i(TAG, "wake: barge-in armed — wake word can interrupt her")
    }

    private fun stopBargeListen(reason: String) {
        if (!micYieldedForWake) return
        if (soundMonitor?.isRunning() != true) return
        soundMonitor?.stop()
        Log.i(TAG, "wake: barge-in mic released ($reason)")
    }

    private fun yieldMicForWake() {
        if (micYieldedForWake) return
        micYieldedForWake = true
        wakeConsumerSeen = false
        wakeFocusReturned = false
        wakeSpeakingSeen = false
        alexaColdRetries = 0
        wakeYieldStartMs = System.currentTimeMillis()
        soundMonitor?.stop()        // free the mic; the wake detector idles on an empty queue
        // Pause any DLNA music so the assistant is heard. Audio focus usually does this on its
        // own once the assistant grabs it, but not every wake path takes focus — so be explicit.
        dlnaRenderer?.pauseForSystem()
        Log.i(TAG, "wake: yielded mic to assistant")

        val am = getSystemService(AudioManager::class.java)
        if (am != null) {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    onWakeRecordingChanged()
                }
            }
            wakeRecordingCallback = cb
            am.registerAudioRecordingCallback(cb, wakeHandler)
            onWakeRecordingChanged()   // in case the assistant already grabbed the mic

            // Alexa only: also watch the OUTPUT side. A long response (story) keeps playing
            // after the mic is released and even after TURN_DONE — and reclaiming then
            // backgrounds falcon, which SILENCES ITS RECORDER on A10 (the audio finishes,
            // but she can never hear a follow-up again — the interactive story dies). Track
            // her voice player so the grace only starts once she has actually stopped.
            if (wakeIsAlexa) {
                val pcb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        onWakePlaybackChanged()
                    }
                }
                wakePlaybackCallback = pcb
                am.registerAudioPlaybackCallback(pcb, wakeHandler)
            }
        }

        // Absolute safety net if the assistant never records / never stops cleanly. Alexa
        // turns are short and we also reclaim on falcon's TURN_DONE, so a much tighter cap
        // keeps the wake word from going deaf for long if a turn ends weirdly.
        wakeHandler.postDelayed(reclaimTimeout, if (wakeIsAlexa) 20_000L else 120_000L)
    }

    // A voice assistant releases the mic BETWEEN turns (it stops recording to speak its
    // reply, then grabs it again for your follow-up). So a momentary "not recording" isn't
    // necessarily "done" — only reclaim if it STAYS quiet this long.
    private val reclaimDebounce = Runnable { reclaimMicAfterWake("assistant done") }

    private fun onWakeRecordingChanged() {
        if (!micYieldedForWake) return
        val elapsed = System.currentTimeMillis() - wakeYieldStartMs
        if (assistantRecording()) {
            // The assistant holds the mic. Cancel any pending reclaim (it was just an
            // inter-turn pause) and, the first time, hand the screen back immediately.
            wakeHandler.removeCallbacks(reclaimDebounce)
            // Safety net: if her capture opened while our barge-in mic was still up
            // (speech-end stop lost the race), free the slot right now.
            if (wakeIsAlexa) stopBargeListen("her capture opened")
            wakeConsumerSeen = true
            // Jarvis: return the screen the instant it grabs the mic (capture continues
            // backgrounded). Alexa/falcon: do NOT — it must stay foreground for the whole
            // turn on A10 (backgrounding re-silences it), so we return only at reclaim.
            if (!wakeFocusReturned && !wakeIsAlexa && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wakeFocusReturned = true
                bringDashboardToFront()
                Log.i(TAG, "wake: assistant has mic (${elapsed}ms) -> dashboard back to front")
            }
        } else if (wakeConsumerSeen) {
            // Not recording — maybe done, maybe just speaking a reply (or about to ask a
            // multi-turn follow-up). Wait for it to stay quiet; if it re-grabs the mic the
            // branch above cancels this. Alexa gets the dialog grace so an ExpectSpeech
            // follow-up has room to reopen the mic — but if she's already audibly speaking,
            // don't count down at all; the playback callback starts the grace when she stops.
            wakeHandler.removeCallbacks(reclaimDebounce)
            if (wakeIsAlexa && assistantSpeaking()) {
                wakeSpeakingSeen = true
                startBargeListen()   // her mic just closed and she's talking — arm barge-in
                return
            }
            wakeHandler.postDelayed(reclaimDebounce,
                if (wakeIsAlexa) ALEXA_DIALOG_GRACE_MS else WAKE_RECLAIM_DEBOUNCE_MS)
        }
    }

    // Output-side twin of onWakeRecordingChanged, Alexa turns only. While her response is
    // audible we hold the yield open (no grace countdown, cover + falcon stay up); the
    // moment the speaker goes quiet we start the post-speech grace — so "tell me a story"
    // plays to the end and the mic still comes back a few seconds after she finishes.
    private fun onWakePlaybackChanged() {
        if (!micYieldedForWake || !wakeIsAlexa) return
        val am = getSystemService(AudioManager::class.java) ?: return
        // Log every change during a turn as usage/content pairs — if falcon's voice ever
        // shows up tagged differently than 13/1 (see assistantSpeaking), this reveals it.
        val pairs = runCatching { am.activePlaybackConfigurations }.getOrDefault(emptyList())
            .map { "${it.audioAttributes.usage}/${it.audioAttributes.contentType}" }
        Log.i(TAG, "wake: playback changed while yielded, usage/content=$pairs")
        if (assistantSpeaking()) {
            wakeHandler.removeCallbacks(reclaimDebounce)
            if (!wakeSpeakingSeen) {
                wakeSpeakingSeen = true
                Log.i(TAG, "wake: Alexa speaking -> holding turn until she stops")
            }
            if (!assistantRecording()) startBargeListen()
        } else if (wakeSpeakingSeen) {
            wakeSpeakingSeen = false
            // Give the slot back FIRST — a follow-up listen opens her capture ~250ms after
            // her voice stops, and our recorder must not be squatting on it.
            stopBargeListen("she stopped speaking")
            // Ignore the transition if she's already recording again (barge-less follow-up
            // opened the mic) — the recording branch owns the reclaim from there.
            if (!assistantRecording()) {
                Log.i(TAG, "wake: Alexa stopped speaking -> grace (${ALEXA_DIALOG_GRACE_MS}ms)")
                wakeHandler.removeCallbacks(reclaimDebounce)
                wakeHandler.postDelayed(reclaimDebounce, ALEXA_DIALOG_GRACE_MS)
            }
        }
    }

    // ── In-call awareness ─────────────────────────────────────────────────────
    // A live Meta call (Messenger/WhatsApp) plays its far-end audio tagged
    // USAGE_VOICE_COMMUNICATION — the one reliable signal on Portals (the system
    // audio mode stays NORMAL throughout a call, so it can't be used). Our own
    // audio never carries that usage (intercom playback is MEDIA), but exclude our
    // uid anyway via the same reflection used by falconPlaying(). Published to HA
    // as the "In Call" binary_sensor and consulted by the wake/announce/cast/
    // warm-up guards: a foreground grab during a call floats the call into
    // picture-in-picture (harmless but rude), and the call owns the mic anyway.
    @Volatile private var inCall = false
    private var callWatchCallback: AudioManager.AudioPlaybackCallback? = null

    private fun computeInCall(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        val configs = runCatching { am.activePlaybackConfigurations }.getOrDefault(emptyList())
        val myUid = android.os.Process.myUid()
        return configs.any { cfg ->
            cfg.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION &&
                (playbackClientUidMethod?.let { m ->
                    runCatching { m.invoke(cfg) as? Int }.getOrNull()
                } ?: -1) != myUid
        }
    }

    private fun startCallWatch() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                onCallStateMaybeChanged()
            }
        }
        callWatchCallback = cb
        am.registerAudioPlaybackCallback(cb, wakeHandler)
        onCallStateMaybeChanged()
    }

    private fun onCallStateMaybeChanged() {
        val now = computeInCall()
        if (now == inCall) return
        inCall = now
        Log.i(TAG, "call: ${if (now) "IN CALL" else "call ended"}")
        // This runs on the main looper — publish from the command executor (Paho's
        // sync QoS-1 publish blocks on the PUBACK, up to timeToWait; never on the UI thread).
        prefs?.let { p ->
            commandExecutor.submit {
                publishRaw(HaDiscovery.inCallStateTopic(p.deviceId), if (now) "ON" else "OFF", 1, retained = true)
            }
        }
        if (!now) {
            // A call may have blocked a reclaim's mic restart — recover the warm mic now.
            val p = prefs
            val coexist = p?.let { it.coexistVoiceAssistant && !it.wakeWordEnabled && !it.alexaWakeEnabled } ?: false
            if (!coexist && !micYieldedForWake && soundMonitor?.isRunning() == false) {
                soundMonitor?.start()
                Log.i(TAG, "call: restarted warm mic after call end")
            }
        }
    }

    // HA "Show Dashboard" button: wake the screen and bring the dashboard forward.
    // During a call the call auto-floats into picture-in-picture and keeps running.
    private fun showDashboard() {
        Log.i(TAG, "show dashboard requested (inCall=$inCall)")
        wakeHandler.post {
            ScreenControl.wake(this)
            lastActivityMs = System.currentTimeMillis()
            bringDashboardToFront()
        }
    }

    private fun bringDashboardToFront() {
        // Works on A9 too: falcon self-foregrounds a story/music card there, and without an
        // explicit return the end of the turn strands the user on the Meta launcher (plus a
        // long black transition) instead of the dashboard. A no-op when we're already front.
        runCatching {
            startActivity(Intent(this, DashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        // The dashboard is now on top (behind the cover) — give it a moment to draw,
        // then fade the frozen snapshot out to reveal the identical live dashboard.
        wakeHandler.postDelayed({ hideWakeCover() }, 300L)
    }

    // A full-screen overlay laid ON TOP of everything (TYPE_APPLICATION_OVERLAY sits above
    // all activities) to mask the ~400ms wake handoff. We put it up before bringing the
    // assistant forward, so the assistant grabs the mic completely unseen; the assistant is
    // still the top ACTIVITY underneath, so it still counts as foreground and gets the mic.
    // Two styles (Prefs.wakeCoverStyle):
    //   "whoosh"   — an orange gradient curtain slides down to cover, then slides off.
    //   "snapshot" — a frozen dashboard image crossfades in and back out.
    private var wakeCoverView: View? = null
    private var wakeCoverStyle: String = "whoosh"

    /**
     * Put the cover up and invoke [onCovered] only once the screen is ACTUALLY covered
     * (frame-commit for the snapshot, end-of-sweep for the whoosh) — launching the
     * assistant any earlier lets a frame of it slip through a not-yet-drawn cover.
     */
    private fun showWakeCover(onCovered: Runnable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || wakeCoverView != null) { onCovered.run(); return }
        wakeCoverStyle = prefs?.wakeCoverStyle ?: "whoosh"
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val fire = Runnable { if (once.compareAndSet(false, true)) onCovered.run() }
        if (wakeCoverStyle == "snapshot") {
            // PixelCopy reads back the composited GPU frame asynchronously (a few ms),
            // then we cover with the pixel-perfect copy. The swap is made ATOMIC: the
            // cover AND the replacement talk-button windows are staged INVISIBLE, then
            // revealed in one main-thread pass → composited in the SAME frame. The screen
            // goes {live dashboard + old buttons} → {frozen frame + new buttons} with no
            // intermediate frame, so nothing can blink and no alpha ever stacks.
            DashboardActivity.snapshot { snap ->
                val shown = runCatching {
                    if (wakeCoverView != null) { fire.run(); return@snapshot }
                    val cover = android.widget.ImageView(this).apply {
                        if (snap != null) {
                            setImageBitmap(snap)
                            scaleType = android.widget.ImageView.ScaleType.FIT_XY
                        } else setBackgroundColor(0xFF1C1C1C.toInt())
                        visibility = View.INVISIBLE   // revealed with the staged buttons
                    }
                    addCoverWindow(cover)
                    val overlays = intercomOverlays.toList()
                    val revealed = java.util.concurrent.atomic.AtomicBoolean(false)
                    var remaining = overlays.size
                    val reveal = Runnable {
                        if (revealed.compareAndSet(false, true)) {
                            cover.visibility = View.VISIBLE
                            overlays.forEach { runCatching { it.completeRefloat() } }
                            // Assistant may launch only once the covering frame is on
                            // screen (frame-commit is API 29+; this path is Q-only).
                            runCatching { cover.viewTreeObserver.registerFrameCommitCallback(fire) }
                                .onFailure { wakeHandler.post(fire) }
                            wakeHandler.postDelayed(fire, 150L)
                        }
                    }
                    if (overlays.isEmpty()) reveal.run()
                    else overlays.forEach { ov ->
                        runCatching { ov.prepareRefloat(Runnable { if (--remaining <= 0) reveal.run() }) }
                            .onFailure { if (--remaining <= 0) reveal.run() }
                    }
                    wakeHandler.postDelayed(reveal, 300L)   // cap: reveal even if staging stalls
                }
                if (shown.isFailure) {
                    Log.w(TAG, "wake: cover failed: ${shown.exceptionOrNull()?.message}")
                    fire.run()   // never block the handoff on cosmetics
                }
            }
        } else {
            val shown = runCatching {
                val h = resources.displayMetrics.heightPixels.toFloat()
                val cover = View(this).apply {
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0xFFFF8A2B.toInt(), 0xFFFF5A00.toInt(), 0xFFCC3300.toInt()))
                    translationY = -h   // starts off the top, sweeps down
                }
                addCoverWindow(cover)
                // Stage replacement buttons above the curtain; flip them live once it
                // fully covers (the old ones disappear behind it at the same moment).
                intercomOverlays.forEach { runCatching { it.prepareRefloat(Runnable {}) } }
                val landed = Runnable {
                    intercomOverlays.forEach { runCatching { it.completeRefloat() } }
                    fire.run()
                }
                cover.animate().translationY(0f).setDuration(170L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction(landed).start()
                wakeHandler.postDelayed(landed, 400L)   // fallback if the animator stalls
            }
            if (shown.isFailure) {
                Log.w(TAG, "wake: cover failed: ${shown.exceptionOrNull()?.message}")
                fire.run()
            }
        }
    }

    // Attach a cover view as the top overlay window, holding the display rotation while
    // it's up (the assistant may request a different orientation — the display rotating
    // under the cover would re-lay it out mid-handoff).
    private fun addCoverWindow(cover: View) {
        // TRANSLUCENT pixel format: the whoosh reveals the live dashboard around the
        // curtain while it moves; the snapshot view itself is opaque edge to edge.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or   // extend under the system bars
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        // Immersive: keep the status/nav bars hidden while the cover is up, so the assistant's
        // (non-immersive) activity coming up behind it doesn't flash a system bar at the edge.
        @Suppress("DEPRECATION")
        cover.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        getSystemService(WindowManager::class.java).addView(cover, lp)
        wakeCoverView = cover
        Log.i(TAG, "wake: cover shown (style=$wakeCoverStyle)")
    }

    private fun hideWakeCover() {
        val cover = wakeCoverView ?: return
        wakeCoverView = null
        val remove = Runnable { runCatching { getSystemService(WindowManager::class.java).removeView(cover) } }
        if (wakeCoverStyle == "snapshot") {
            // Slightly slower fade back to live — softens the position-jump when the
            // content underneath (e.g. an animated screensaver) moved during the freeze.
            cover.animate().alpha(0f).setDuration(350L).withEndAction(remove).start()
        } else {
            // Continue the downward motion — the curtain slides off the bottom, revealing
            // the (live) dashboard from the top down.
            val h = resources.displayMetrics.heightPixels.toFloat()
            cover.animate().translationY(h).setDuration(220L)
                .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction(remove).start()
        }
    }

    private fun reclaimMicAfterWake(reason: String) {
        if (!micYieldedForWake) return
        micYieldedForWake = false
        wakeHandler.removeCallbacks(reclaimTimeout)
        wakeHandler.removeCallbacks(reclaimDebounce)
        // The assistant turn is over — resume DLNA music if we paused it for the turn.
        dlnaRenderer?.resumeAfterSystem()
        wakeRecordingCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(cb) }
            wakeRecordingCallback = null
        }
        wakePlaybackCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioPlaybackCallback(cb) }
            wakePlaybackCallback = null
        }
        wakeSpeakingSeen = false
        // Restart the warm mic whenever we normally hold it (not just wake mode) — the sound
        // sensor / enhanced presence need it too, and an assist-button handoff also yields it.
        // Not during a live call, though: the call owns the mic; onCallStateMaybeChanged
        // restarts us when it ends.
        val coexist = prefs?.let { it.coexistVoiceAssistant && !it.wakeWordEnabled && !it.alexaWakeEnabled } ?: false
        if (!coexist && !inCall && soundMonitor?.isRunning() == false) soundMonitor?.start()
        // The assistant may still be speaking its reply; ignore wake matches briefly so
        // its audio (echoed back through the mic) can't immediately re-trigger the handoff.
        wakeDetector?.pauseMatching(3_000L)
        // Fallback: if we never handed focus back early (handoff never took, or timed out),
        // make sure the dashboard is in front now.
        // Return to the dashboard. bringDashboardToFront brings it on top BEHIND the cover and
        // fades the cover out once it's drawn (no flash of the assistant). Alexa always lands
        // here (held foreground till now). If we already returned early (Jarvis), just make
        // sure no cover lingers.
        alexaBar?.hide()   // drop the listening bar when the turn ends
        // A9: falcon shows its own story/music card (no cover there) and may still be
        // PLAYING when the turn machinery goes idle (music isn't held open). Leave the
        // card up while its audio runs — "alexa, play music" keeps the nice display —
        // but once falcon is silent, restore our dashboard explicitly, or the ended turn
        // strands the user on the Meta launcher + a long black gap.
        val leaveFalconUp = wakeIsAlexa && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            falconPlaying()
        if (leaveFalconUp) Log.i(TAG, "wake: falcon still playing (A9) -> leaving its screen up")
        // A call answered mid-turn owns the screen now — just drop the cover, don't PiP it.
        if (!wakeFocusReturned && !leaveFalconUp && !inCall) bringDashboardToFront() else hideWakeCover()
        wakeFocusReturned = false
        wakeIsAlexa = false
        Log.i(TAG, "wake: reclaimed mic ($reason, ${System.currentTimeMillis() - wakeYieldStartMs}ms total)")
    }

    // The recognizer grammar is fixed at creation, so any change to the ENABLED phrases needs
    // a fresh recognizer. This signature captures both routes (empty = that route disabled).
    private fun wakeSig(p: Prefs) =
        "${if (p.wakeWordEnabled) p.wakePhrase else ""}|${if (p.alexaWakeEnabled) p.alexaWakePhrase else ""}"

    // Start/stop the wake detector to match the prefs (live, from the apply path). Runs when
    // EITHER the Jarvis wake word OR Alexa support is on; each route is fed its own phrase
    // ("" = disabled). Wake needs our warm mic, so it ensures SoundMonitor is running.
    private fun reconcileWake(p: Prefs) {
        val want = p.wakeWordEnabled || p.alexaWakeEnabled
        val sig = wakeSig(p)
        val sigChanged = startedWakePhrase != null && startedWakePhrase != sig
        wakeDetector?.phrase = if (p.wakeWordEnabled) p.wakePhrase else ""
        wakeDetector?.alexaPhrase = if (p.alexaWakeEnabled) p.alexaWakePhrase else ""
        // Threshold applies live; the enable flag only takes effect on a (re)start
        // since the verifiers are built with the recognizer.
        wakeDetector?.verifyThreshold = p.wakeVerifyThreshold / 100f
        val verifyChanged = wakeDetector?.verifyEnabled != p.wakeVerifyEnabled
        wakeDetector?.verifyEnabled = p.wakeVerifyEnabled
        if (want) {
            if (soundMonitor?.isRunning() == false) soundMonitor?.start()
            if (wakeDetector?.isRunning() == false) {
                wakeDetector?.start(); startedWakePhrase = sig
            } else if (sigChanged || verifyChanged) {
                // New enabled-phrase set → rebuild the recognizer. Stop now and restart after a
                // short gap so the old decode thread exits (200 ms poll) before the new starts.
                wakeDetector?.stop()
                startedWakePhrase = sig
                wakeHandler.postDelayed({
                    if (prefs?.let { it.wakeWordEnabled || it.alexaWakeEnabled } == true) wakeDetector?.start()
                }, 400L)
            }
        } else if (wakeDetector?.isRunning() == true) {
            wakeDetector?.stop()
            startedWakePhrase = null
        }

        // Watch falcon's connection state only while Alexa support is on — so the handoff can
        // gate on it (skip + inform the user while falcon is still reconnecting after a boot).
        if (p.alexaWakeEnabled) {
            if (falconReadiness == null) falconReadiness = FalconReadiness().also { it.start() }
            scheduleFalconWarmup()
            startAlexaKeepWarm()
        } else {
            falconReadiness?.stop(); falconReadiness = null
            wakeHandler.removeCallbacks(alexaKeepWarm)
        }
    }

    // FALCON WARM-UP: the first LISTEN after our app restarts lands on a falcon whose voice
    // session has gone stale — it aborts within ~300ms and speaks "something went wrong",
    // then takes a few kicked turns to fully recover (measured 2026-07-07: ReadyState only
    // ~45s after the first attempt; a 19-min idle WITHOUT our restart was fine, so it's our
    // restart that staleness follows). The provisioner revives a stale falcon by simply
    // LAUNCHING its activity, so do one silent kick shortly after start: falcon foreground
    // behind the cover for a moment — no LISTEN, so no beep and no mic involvement — then
    // back to the dashboard. The user's first real "alexa" then lands on a warm falcon.
    @Volatile private var falconWarmupDone = false

    private fun scheduleFalconWarmup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (falconWarmupDone) return
        falconWarmupDone = true
        Log.i(TAG, "wake: falcon warm-up scheduled in ${FALCON_WARMUP_DELAY_MS}ms")
        val posted = wakeHandler.postDelayed({ runFalconWarmup() }, FALCON_WARMUP_DELAY_MS)
        if (!posted) Log.w(TAG, "wake: falcon warm-up post FAILED")
    }

    // Streams falcon speaks on (her TTS is USAGE_ASSISTANCE_SONIFICATION, which maps to
    // STREAM_SYSTEM; MUSIC is muted too because the exact mapping isn't guaranteed on
    // Meta's build and a wrong guess would leave the warm-up audible).
    private val alexaOutputStreams = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)
    @Volatile private var alexaMuted = false
    private val alexaUnmute = Runnable { unmuteAlexaOutput() }

    // Silence falcon for [ms], then restore no matter what. Used around the warm-up turn,
    // which deliberately provokes her cold failure — the user must not hear it.
    private fun muteAlexaOutput(ms: Long, why: String) {
        val am = getSystemService(AudioManager::class.java) ?: return
        // Always (re)arm the restore, even if already muted — a second abort must
        // extend the window rather than let the first timer unmute mid-retry.
        wakeHandler.removeCallbacks(alexaUnmute)
        wakeHandler.postDelayed(alexaUnmute, ms)
        if (alexaMuted) return
        alexaMuted = true
        runCatching {
            alexaOutputStreams.forEach { am.adjustStreamVolume(it, AudioManager.ADJUST_MUTE, 0) }
            Log.i(TAG, "wake: falcon output muted ${ms}ms ($why)")
        }.onFailure { Log.w(TAG, "wake: mute failed: ${it.message}"); alexaMuted = false }
    }

    private fun unmuteAlexaOutput() {
        wakeHandler.removeCallbacks(alexaUnmute)
        if (!alexaMuted) return
        alexaMuted = false
        val am = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            alexaOutputStreams.forEach { am.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0) }
            Log.i(TAG, "wake: falcon output restored")
        }.onFailure { Log.w(TAG, "wake: unmute failed: ${it.message}") }
    }

    // Periodic deaf+muted LISTEN that keeps falcon's upload session from going stale, so
    // the user's first "alexa" captures their command instead of losing it to a cold
    // abort. Same safety as the startup warm-up: falcon is NOT foregrounded, so A10
    // silences its capture and it cannot act on anything said in the room, and its output
    // is muted so the "didn't understand" it inevitably produces is never heard.
    private val alexaKeepWarm = object : Runnable {
        override fun run() {
            val p = prefs
            if (p?.alexaWakeEnabled != true) return              // feature off — stop the loop
            wakeHandler.postDelayed(this, ALEXA_KEEPWARM_MS)     // always keep the loop alive
            if (!p.alexaKeepWarmEnabled) return                  // opt-in: see Prefs for why
            when {
                micYieldedForWake -> return          // a real turn is in flight (and warms it)
                inCall -> return                     // never inject audio work into a call
                falconPlaying() -> return            // don't mute music/stories to keep warm
                !alexaLikelyNeeded() -> return       // nobody around to talk to it
            }
            // Skip only if a turn warmed it VERY recently. Comparing against the full
            // interval here was a bug: the tick is every 45 s and lastListenAtMs is
            // always ~45 s old, so every other tick was skipped and the real cadence
            // became ~90 s — past the ~60-100 s staleness point this exists to beat.
            if (System.currentTimeMillis() - lastListenAtMs < ALEXA_KEEPWARM_SKIP_MS) return
            muteAlexaOutput(ALEXA_KEEPWARM_MUTE_MS, "keep-warm")
            // ★Hide Alexa's own blue listening bar. A LISTEN makes falcon draw
            // com.amazon.aria.AriaActivity — verified a plain BASE_APPLICATION window,
            // which our TYPE_APPLICATION_OVERLAY cover sits above (overlay windows z-order
            // above every activity). The cover is a pixel-perfect snapshot of the
            // dashboard, so nothing visibly changes; the dashboard is just frozen for the
            // few seconds the warm-up takes. Without this the panel appears to listen to
            // the room every 45 s, which is what made keep-warm unacceptable as a default.
            showWakeCover(Runnable {
                broadcastAlexaListen("keep-warm")
                wakeHandler.postDelayed({
                    // A real wake may have started mid-warm-up; its flow owns the cover then.
                    if (!micYieldedForWake) hideWakeCover()
                }, ALEXA_KEEPWARM_COVER_MS)
            })
        }
    }

    // Only bother keeping falcon warm when someone could plausibly speak: presence, or
    // recent sound if presence isn't in use. With presence off entirely we keep it warm
    // unconditionally — that Portal has no better signal.
    private fun alexaLikelyNeeded(): Boolean {
        val p = prefs ?: return false
        if (!p.presenceEnabled) return true
        if (lastPublishedPresence == true) return true
        return lastSoundActivityMs > 0 &&
            System.currentTimeMillis() - lastSoundActivityMs < SOUND_PRESENCE_HOLD_MS
    }

    private fun startAlexaKeepWarm() {
        wakeHandler.removeCallbacks(alexaKeepWarm)
        wakeHandler.postDelayed(alexaKeepWarm, ALEXA_KEEPWARM_MS)
        Log.i(TAG, "wake: falcon keep-warm loop armed (enabled=${prefs?.alexaKeepWarmEnabled}, " +
            "every ${ALEXA_KEEPWARM_MS / 1000}s when on, presence-gated)")
    }

    private fun runFalconWarmup() {
        Log.i(TAG, "wake: falcon warm-up fired (alexa=${prefs?.alexaWakeEnabled} yielded=$micYieldedForWake inCall=$inCall)")
        if (prefs?.alexaWakeEnabled != true) return
        if (micYieldedForWake) return   // a real turn is in flight — it warms falcon itself
        if (inCall) return              // never PiP a live call for a warm-up
        // ★The kick alone does NOT fix the first turn — measured repeatedly. Launching
        // SIMActivity revives a stale ACTIVITY, but what actually fails on the first real
        // "alexa" is falcon's first audio UPLOAD to Amazon (its shm buffer overruns while
        // the recognize stream is still being set up). Only an actual LISTEN exercises
        // that path, so the warm-up now fires one and SPENDS the cold failure here, where
        // nobody is waiting on it.
        //
        // Two things make that safe:
        //  - We do NOT foreground falcon for this. Backgrounded on A10 its capture is
        //    policy-silenced, so it hears dead air and CANNOT act on anything said in the
        //    room. (A foregrounded warm-up turn would be a live, unattended microphone.)
        //  - Its output is muted for the whole window, so the "sorry, something went
        //    wrong" this deliberately provokes is never audible.
        // The user's first real "alexa" then lands on a falcon whose upload path is warm.
        // NOTE: deliberately does NOT launch falcon's activity. An earlier version kicked
        // SIMActivity here (left over from the theory that a stale ACTIVITY was the
        // problem) — with no cover over it, that made falcon visibly take the screen for
        // ~2.5 s on every start, which is exactly the flicker this app exists to avoid.
        // The LISTEN above is what warms the upload session, and it needs no UI at all.
        Log.i(TAG, "wake: falcon warm-up — muted LISTEN to spend the cold failure")
        muteAlexaOutput(FALCON_WARMUP_MUTE_MS, "warm-up")
        broadcastAlexaListen("warm-up")
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
        // Presence (face or enhanced-sound) holds the screen awake and resets the countdown —
        // unless the user has asked for the screen to sleep on schedule regardless of whether
        // anyone is there. Presence is still computed and published either way.
        recomputePresence(p)
        if (lastPublishedPresence == true && !p.screenTimeoutIgnorePresence) {
            lastActivityMs = System.currentTimeMillis(); return
        }
        if (System.currentTimeMillis() - lastActivityMs >= p.screenTimeoutMinutes * 60_000L) {
            Log.i(TAG, "screen timeout: ${p.screenTimeoutMinutes}m idle — sleeping screen")
            ScreenControl.sleep()
        }
    }

    /**
     * Show or hide the photo frame. Runs on the same 15 s tick as the screen timeout, so idle
     * detection is accurate to about that — fine for a countdown measured in minutes.
     *
     * Ordering with screen-off is deliberate and needs no extra setting: presence already resets
     * [lastActivityMs], so while somebody is in the room the screen never sleeps and the photos
     * simply stay up; once the room empties, the existing timeout sleeps the screen and the
     * !screenOn branch below takes the frame down. Photos while you're there, dark when you're not.
     */
    private fun checkScreensaver() {
        val p = prefs ?: return
        if (!p.screensaverEnabled || p.screensaverUrl.isBlank()) {
            // isStaged, not isShowing: a prestaged page is invisible but still a live WebView,
            // so switching the feature off must tear it down rather than leak it.
            if (screensaver.isStaged) screensaver.hide()
            return
        }
        // Anything that owns the screen or the mic outranks a slideshow. Same guard list as the
        // other overlays: a call, a cast, or an assistant turn must never be covered by photos.
        // ★dashboardForeground is essential, not tidiness: the overlay swallows every touch, so
        // appearing over a settings screen makes the UI unusable — fields can't be focused and
        // the keyboard never opens. Only the dashboard feeds lastInteractionMs (via
        // onUserInteraction), so any other screen would look idle and summon photos over itself.
        val busy = inCall || dialServer?.appRunning == true || micYieldedForWake || falconPlaying()
        if (!screenOn || busy || !dashboardForeground) {
            if (screensaver.isShowing) screensaver.hide()
            return
        }
        if (screensaver.isShowing) return
        // Held off by a recent dismiss — someone is looking at the dashboard on purpose.
        if (System.currentTimeMillis() < screensaverHoldUntilMs) return
        // With presence off there's no better signal, so the idle timer alone drives it.
        if (p.screensaverPresenceOnly && p.presenceEnabled && lastPublishedPresence != true) return
        if (System.currentTimeMillis() - lastInteractionMs < p.screensaverIdleSecs * 1000L) return
        screensaver.show(p.screensaverUrl) { exitScreensaver() }
    }

    /** Centre tap: drop the photos and restart both countdowns so it doesn't reappear at once. */
    private fun exitScreensaver() {
        lastInteractionMs = System.currentTimeMillis()
        // The photo frame is its own window, so its taps never reach the dashboard's
        // dispatchTouchEvent — but a centre tap is unambiguously a person, and it should count
        // as one if they go somewhere else next.
        lastInputMs = System.currentTimeMillis()
        lastActivityMs = System.currentTimeMillis()
        screensaver.hide()
    }

    /** HA switch: turn the whole feature on or off. Off takes any showing photos down at once. */
    private fun handleScreensaverCommand(payload: String, p: Prefs) {
        val on = payload.equals("ON", ignoreCase = true)
        if (on != p.screensaverEnabled) p.screensaverEnabled = on
        if (!on) screensaver.hide()
        publishScreensaverState(p)
        Log.i(TAG, "screensaver: HA set enabled=$on")
    }

    /**
     * Dismiss now, from HA — per-device or fleet-wide. This is a "get out of the way" for a
     * motion-triggered camera pop-up, not a way to switch the feature off (that's the switch).
     * Harmless when nothing is showing, which matters for the fleet topic: every Portal receives
     * it, and most of them won't have photos up.
     *
     * The photos are then held off for a window, not merely re-counted-down: restarting the idle
     * timer alone would cover the cameras again the moment it elapsed. [payload] may carry a
     * number of seconds to override the configured default for one press (e.g. publish "300" to
     * hold five minutes); anything else uses [Prefs.screensaverDismissHoldSecs].
     */
    private fun dismissScreensaverFromHa(payload: String, p: Prefs) {
        val hold = payload.trim().toIntOrNull()?.coerceIn(0, 3600) ?: p.screensaverDismissHoldSecs
        val wasShowing = screensaver.isShowing
        screensaverHoldUntilMs = System.currentTimeMillis() + hold * 1000L
        exitScreensaver()
        Log.i(TAG, "screensaver: dismissed by HA (showing=$wasShowing, held ${hold}s)")
    }

    /**
     * Take (or hand back) the system screensaver slot.
     *
     * Meta's power policy starts a dream at the screen timeout no matter what
     * `screensaver_enabled`, `screensaver_activate_on_sleep` or `screensaver_activate_on_dock`
     * say — all three were measured being ignored — and a TYPE_DREAM window sits above every
     * overlay we can draw, so it cannot be hidden either. The only way to stop the launcher's
     * screensaver appearing on every wake is for OUR dream to be the one that runs.
     *
     * Both keys are set: `screensaver_components` is what runs, and `screensaver_default_component`
     * is what the framework falls back to. The previous pair is remembered so switching this off
     * restores exactly what was there.
     */
    /**
     * Watch the screensaver slot and take it back if anything else claims it.
     *
     * Claiming once at startup is not enough on a real Portal: the launcher owns this setting too,
     * and Immortal rewrites it to its own PhotoDreamService every time its home screen runs —
     * which is every boot and every HOME kick. Reconciling only at service start meant we lost
     * the slot within minutes and never noticed, so the wake-flash fix quietly stopped working.
     *
     * A ContentObserver makes us the last writer without polling. Our own write fires this too,
     * but the reconcile no-ops when the value already reads as ours, so it settles immediately
     * rather than echoing.
     */
    private fun startDreamWatch() {
        if (dreamObserver != null) return
        val obs = object : android.database.ContentObserver(wakeHandler) {
            override fun onChange(selfChange: Boolean) {
                val p = prefs ?: return
                if (p.claimDreamSlot) reconcileDreamSlot(p)
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(DREAM_COMPONENTS), false, obs)
            dreamObserver = obs
            Log.i(TAG, "dream: watching the screensaver slot")
        }.onFailure { Log.w(TAG, "dream: could not watch the slot: ${it.message}") }
    }

    private fun reconcileDreamSlot(p: Prefs) {
        runCatching {
            val ours = "$packageName/.BlankDreamService"
            val cr = contentResolver
            val cur = Settings.Secure.getString(cr, DREAM_COMPONENTS) ?: ""
            if (p.claimDreamSlot) {
                if (cur == ours) return
                // Anti-thrash. Claiming is normally a one-off, but the slot is shared with
                // whatever launcher is installed and Immortal rewrites it every time its home
                // screen runs. If something ever wrote back instantly and forever, two apps
                // would spin on this key; refusing to write twice in quick succession bounds
                // that to a slow alternation instead of a hot loop.
                val now = System.currentTimeMillis()
                if (now - lastDreamClaimMs < DREAM_RECLAIM_MIN_MS) {
                    if (!dreamFightLogged) {
                        dreamFightLogged = true
                        Log.w(TAG, "dream: something keeps taking the screensaver slot back ('$cur') — backing off")
                    }
                    return
                }
                lastDreamClaimMs = now
                // Only record the first time, or a second pass would save our own value as the
                // thing to restore and the original would be lost forever.
                if (p.dreamRestoreComponents.isBlank()) {
                    p.dreamRestoreComponents = cur
                    p.dreamRestoreDefault = Settings.Secure.getString(cr, DREAM_DEFAULT) ?: ""
                }
                Settings.Secure.putString(cr, DREAM_COMPONENTS, ours)
                Settings.Secure.putString(cr, DREAM_DEFAULT, ours)
                Log.i(TAG, "dream: claimed the screensaver slot (was '$cur')")
            } else if (p.dreamRestoreComponents.isNotBlank()) {
                Settings.Secure.putString(cr, DREAM_COMPONENTS, p.dreamRestoreComponents)
                Settings.Secure.putString(cr, DREAM_DEFAULT, p.dreamRestoreDefault)
                Log.i(TAG, "dream: released the slot back to '${p.dreamRestoreComponents}'")
                p.dreamRestoreComponents = ""
                p.dreamRestoreDefault = ""
            }
        }.onFailure { Log.w(TAG, "dream slot reconcile failed: ${it.message}") }
    }

    /**
     * Shorten (or restore) Portal OS's own screen timeout — its "ambient display" setting, which
     * is plain `system screen_off_timeout` and ships at five minutes.
     *
     * It does nothing while our dashboard is in front, because FLAG_KEEP_SCREEN_ON blocks the
     * timeout path outright; it only applies in the windows where something else owns the screen,
     * after a boot or a foreground steal. Reported from the field: dropping it to a minute was
     * what let the app be "left alone on top of the launcher", because the OS reaches its
     * ambient/sleep decision sooner and the launcher stops sitting there.
     *
     * Off by default and fully reversible — the previous value is remembered and put back — since
     * this is a system-wide setting the owner may have chosen deliberately. Needs WRITE_SETTINGS,
     * which the app already holds for the brightness slider.
     */
    private fun reconcileOsTimeout(p: Prefs) {
        runCatching {
            val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            if (p.shortenOsTimeout) {
                if (cur == OS_TIMEOUT_SHORT_MS) return
                // Record once, or a second pass would save our own value as the thing to restore.
                if (p.osTimeoutRestore < 0 && cur > 0) p.osTimeoutRestore = cur
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, OS_TIMEOUT_SHORT_MS)
                Log.i(TAG, "os timeout: shortened to ${OS_TIMEOUT_SHORT_MS}ms (was ${cur}ms)")
            } else if (p.osTimeoutRestore >= 0) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, p.osTimeoutRestore)
                Log.i(TAG, "os timeout: restored to ${p.osTimeoutRestore}ms")
                p.osTimeoutRestore = -1
            }
        }.onFailure { Log.w(TAG, "os timeout reconcile failed: ${it.message}") }
    }

    private fun handleScreensaverHoldCommand(payload: String, p: Prefs) {
        val secs = payload.trim().toFloatOrNull()?.toInt() ?: return
        p.screensaverDismissHoldSecs = secs
        publishScreensaverState(p)
        Log.i(TAG, "screensaver: dismiss hold set to ${p.screensaverDismissHoldSecs}s")
    }

    private fun publishScreensaverState(p: Prefs) {
        publishRaw(HaDiscovery.screensaverStateTopic(p.deviceId),
            if (p.screensaverEnabled) "ON" else "OFF", 1, retained = true)
        publishRaw(HaDiscovery.screensaverHoldStateTopic(p.deviceId),
            p.screensaverDismissHoldSecs.toString(), 1, retained = true)
    }

    private fun publishDisplayStates(p: Prefs) {
        publishScreensaverState(p)
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

    // PTT press with 2-way on: show the orb ORANGE for the announce itself — the
    // "channel ready, you're live" cue, in the same visual language as holding the
    // floor during the hands-free reply phase. Without 2-way the old minimal look
    // (red button only) is kept. Never over a live call.
    private fun onPttTalkStarted() {
        if (intercom?.twoWayEnabled != true || inCall) return
        wakeHandler.post {
            if (twoWayOrb == null) twoWayOrb = AnnounceOrbOverlay(this, blue = true, interactive = true)
                .also { it.onTap = { intercom?.closeTwoWayChannel() } }   // tap the Portal to hang up
            twoWayOrb?.show()
            twoWayOrb?.setLive(true)
            twoWayOrb?.setTransmitting(true)   // blue base warms to orange ≈ instantly
        }
    }

    // PTT release: broadcast announces hand the SAME orb to the reply channel that
    // stopTalk() just opened (it cools orange→blue = "listening for replies");
    // direct/peer announces have no reply channel, so their orb goes away.
    private fun onPttTalkStopped(target: String?) {
        val handsOffToReply = intercom?.twoWayEnabled == true &&
            (target.isNullOrEmpty() || target == "all")
        wakeHandler.post {
            twoWayOrb?.setTransmitting(false)
            // Keep the orb if an (earlier) reply channel still owns it.
            if (!handsOffToReply && !twoWayChannelOpen) { twoWayOrb?.hide(); twoWayOrb = null }
        }
    }

    // Show the configured talk buttons only while: the feature is on, this Portal
    // can transmit (not receive-only), AND the dashboard is in front. Otherwise
    // hide them — they don't float over other apps / the home screen.
    private fun reconcileIntercomOverlays() {
        val p = prefs ?: return
        // The wake-handoff cover counts as "dashboard in front": the buttons float above
        // the cover for the whole handoff, so hiding them here would blink them out.
        val show = p.intercomOverlayEnabled && intercom?.canTransmit() == true &&
            (dashboardForeground || wakeCoverView != null)
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
                onDown = {
                    val ok = intercom?.startTalk(b.target) == true
                    if (ok) onPttTalkStarted()
                    ok
                },
                onUp = { intercom?.stopTalk(); onPttTalkStopped(b.target) },
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
