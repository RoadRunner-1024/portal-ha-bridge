# Portal HA Bridge

Turn a **Meta Portal** into a fully-fledged **Home Assistant** device — screen control, camera streaming, motion & presence detection, ambient sensors, sound level, and more — all exposed automatically over **MQTT auto-discovery**.

It runs as a persistent background service plus an optional full-screen HA dashboard (kiosk). Nothing is sent anywhere except your own MQTT broker and Home Assistant.

> Unofficial, third-party project. Not affiliated with or endorsed by Meta.

---

## What you get in Home Assistant

Everything below appears automatically as one HA **device** (named whatever you set in the app), via MQTT discovery — no YAML required for the entities themselves.

| Entity | Type | Notes |
|---|---|---|
| **Screen** | switch | Sleep / wake the display |
| **Screen Timeout** + **Screen Timeout Minutes** | switch + number | On-device idle screen-off timer (works without HA) |
| **Camera** | switch | Master camera power on/off |
| **Camera Streaming** | switch | RTSP H.264 stream on `:8554` (see below) |
| **Motion Detection** | switch | adds **Motion** (binary_sensor) + **Motion Sensitivity** (number) |
| **Portal Presence** | binary_sensor | Occupancy from Meta's *own* face detection (see below) |
| **Presence Detection** | switch | Enable/disable the presence sensor |
| **Ambient Light** | sensor | lux (`tcs34x0`) |
| **Light R / G / B** | sensors | colour channels (hardware-dependent) |
| **Temperature** + **Temperature Offset** | sensor + number | only on models with an ambient-temp sensor |
| **Sound Level** | sensor | 0–100 ambient loudness (amplitude only, audio never stored) |
| **Tap** / **Tilt** | sensor | knock/tilt gesture direction (`left/right/up/down/front/back`) |
| **Tap/Tilt Sensitivity** | number | threshold slider |
| **Accel X / Y / Z** | sensors | raw accelerometer |
| **Brightness** | number | screen brightness 0–100 |
| **Volume** + **Volume Mute** | number + switch | media volume |
| **Mic Mute** | switch | microphone mute |
| **Doorbell** / **Alert** | buttons | play a tone on the Portal |

Camera, Motion, and Camera Streaming are mutually managed: Motion and Streaming each open Camera 0 and are **mutually exclusive**.

---

## Supported devices

Meta Portal family on **Android 9 (API 28)** and **Android 10 (API 29)** — Portal (10"), Portal Mini, Portal+ (1st & 2nd gen). One APK covers them all (`minSdk 28`). Sensor availability and a couple of behaviours vary by model — see [Per-model notes](#per-model-notes).

---

## Quick start

### 1. Build or grab the APK
- **Download:** grab `portal-ha-bridge.apk` from the [latest release](https://github.com/RoadRunner-1024/portal-ha-bridge/releases/latest).
- **Build from source:** see [Building](#building-from-source) — the signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

### 2. Install + provision (Windows, device on ADB)
With the Portal connected via ADB, from the project root:

```powershell
.\provision.ps1 -Install -SetLauncher
```

This installs the APK, grants every permission/app-op (all require ADB — they can't be granted from the Portal UI), auto-enables the screen-control accessibility service, optionally sets the [immortal launcher](https://github.com/…) as the kiosk home, restarts the app, and prints a green verification checklist.

| Flag | Effect |
|---|---|
| *(none)* | grant permissions on the connected device |
| `-Install` | install the latest APK first |
| `-SetLauncher` | also set immortal as the default launcher |
| `-Serial <id>` | target a specific device when several are attached |

> No computer? You can install the APK and grant **most** things via the app's permission prompts — but **screen sleep needs one ADB grant** (`WRITE_SECURE_SETTINGS`), because Meta hides accessibility services from Portal's Settings. See [SETUP.md](SETUP.md).

### 3. Configure
Open **Portal HA Bridge** on the device and enter your **MQTT broker** host/port/credentials and a **device name** (this becomes the HA device + entity prefix). Save & restart.

The HA device appears automatically.

---

## Camera streaming (RTSP → Home Assistant)

When **Camera Streaming** is on, the app serves H.264 (Constrained Baseline) at:

```
rtsp://<portal-ip>:8554/
```

It plays directly in VLC. For Home Assistant, the cleanest path is the **WebRTC Camera** custom card. Because the stream carries a mandatory (silent) AAC track that WebRTC can't use, ingest it through go2rtc's ffmpeg with `#video=copy` to drop the audio:

```yaml
type: custom:webrtc-camera
url: 'ffmpeg:rtsp://<portal-ip>:8554/#video=copy'
```

The app's **Camera Settings** screen shows this exact line with the device's own IP filled in.

Notes:
- The H.264 profile is forced to **Constrained Baseline** so browser WebRTC accepts it.
- Audio is intentionally dropped — the Portal mic is reserved for calls and the sound sensor.
- If a **Portal call** grabs the camera, the stream is auto-recovered when you return to the dashboard app.
- Orientation: a manual **Rotate** button is provided; on fixed-orientation Portals you set it once.

### Show camera feeds only when on (HA dashboard view)

```yaml
type: conditional
conditions:
  - entity: switch.<device>_camera
    state: "on"
card:
  type: custom:webrtc-camera
  url: 'ffmpeg:rtsp://<portal-ip>:8554/#video=copy'
```

---

## Presence detection

The **Portal Presence** sensor doesn't run our own detection — it piggybacks on Meta's. `PresenceManager` (Aloha) runs face-presence detection on the Portal's *other* camera and logs a heartbeat (~every 30 s) **only while a person is present**, going silent when the room empties. The app tails logcat for that heartbeat: fresh beats = present, no beat for ~50 s = absent.

- Requires the `READ_LOGS` permission (ADB-grantable only).
- Independent of the app's own camera — works with the camera feature off.

## Screen control

| Action | Mechanism |
|---|---|
| **Wake** | `PowerManager` wake lock (`WAKE_LOCK` only) |
| **Sleep** | `AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN` (no device admin) |

Plus an on-device idle timer (**Screen Timeout** / **…Minutes**) that sleeps the screen independently of HA.

---

## Per-model notes

- **Temperature / RGB / sensors** are hardware-dependent and auto-detected — entities only appear if the sensor exists.
- **Portal+ 2nd gen (`cipher`)**: its accelerometer is mounted on the **moving screen arm**, which heavily dampens taps. On this model the tap threshold is auto-scaled for sensitivity, the gesture is relabelled **"Tilt"**, and its dominant (Z) axis reports **up/down** instead of front/back. This is automatic — no config.

---

## Permissions

All ADB-granted (they can't be granted from the Portal UI). `provision.ps1` does these for you:

| Permission / app-op | Used for |
|---|---|
| `WRITE_SECURE_SETTINGS` | auto-enable the screen-sleep accessibility service |
| `CAMERA` | streaming / motion |
| `RECORD_AUDIO` | ambient sound-level sensor |
| `READ_LOGS` | Portal presence sensor |
| `WRITE_SETTINGS` (app-op) | read/set brightness |
| `SYSTEM_ALERT_WINDOW` (app-op) | overlay → background camera access |

---

## Building from source

- **Android Studio** (or Gradle CLI) — JDK 17.
- Kotlin 2.0.20, AGP 8.3.2, Gradle 8.6, `compileSdk 35`, `minSdk 28`.
- RTSP camera uses `com.github.pedroSG94:RTSP-Server:1.3.0` + `com.github.pedroSG94.RootEncoder:library:2.4.6` (this exact pair) via JitPack.

```bash
./gradlew assembleRelease
```

**Signing:** release builds read `keystore.properties` (in the project root) for the signing key. That file and the `.keystore` are **git-ignored** — keep your own; losing them means you can't sign updates that install over an existing install.

---

## Project structure

```
app/src/main/java/com/aeonos/portalha/
  BridgeService.kt      Foreground service: MQTT, camera authority, orchestration
  HaDiscovery.kt        MQTT discovery payloads / topics for every entity
  RtspStreamer.kt       Headless RTSP H.264 server (RootEncoder)
  CameraStream.kt       Camera capture for motion detection
  MotionDetector.kt     Frame-diff motion detection
  SensorBridge.kt       Light / RGB / temp / accelerometer / tap-tilt
  SoundMonitor.kt       Ambient sound level (yields the mic during calls)
  PresenceMonitor.kt    Tails Meta's PresenceManager heartbeat
  ScreenControl.kt / ScreenAccessibility.kt   Sleep/wake
  MediaKeepAlive.kt     Stops the launcher idle-kicking the app
  TonePlayer.kt         Doorbell / alert tones
  DashboardActivity.kt  Full-screen HA WebView (kiosk)
  MainActivity.kt       Settings / configuration UI
  Prefs.kt              SharedPreferences wrapper
provision.ps1           One-shot device setup (install + permissions + launcher)
SETUP.md                Detailed setup & MQTT topics
```

---

## Troubleshooting

- **Camera "1 frame then freezes" in HA** → use the `ffmpeg:…#video=copy` URL (drops the AAC track); ensure the app is recent (Constrained Baseline profile).
- **No screen sleep** → `WRITE_SECURE_SETTINGS` not granted; run `provision.ps1` (or the single grant in SETUP.md).
- **Camera "can't open from background"** → re-open the dashboard app (it re-acquires the camera in the foreground).
- **Provision says "no device"** → check `adb devices`, re-plug, accept the USB-debugging prompt.

---

## License

**PolyForm Noncommercial License 1.0.0** — see [LICENSE](LICENSE).

You're free to use, modify, and share this for **noncommercial** purposes (personal, hobby, education, non-profit). **All commercial use is reserved** to the copyright holder — © 2026 RoadRunner-1024. For a commercial license, contact the author.
