# Portal HA Bridge

Turn a **Meta Portal** into a fully-fledged **Home Assistant** device — screen control, camera streaming, motion & presence detection, ambient sensors, sound level, and more — all exposed automatically over **MQTT auto-discovery**.

It runs as a persistent background service plus an optional full-screen HA dashboard (kiosk). Nothing is sent anywhere except your own MQTT broker and Home Assistant.

> Unofficial, third-party project. Not affiliated with or endorsed by Meta.

---

<a href="https://www.buymeacoffee.com/roadrunner1024" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me a Coffee" style="height: 60px !important;width: 217px !important;" ></a>


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

### 1. Install + provision

**Windows:**

```powershell
iwr https://raw.githubusercontent.com/RoadRunner-1024/portal-ha-bridge/main/provision.ps1 -OutFile provision.ps1
Unblock-File .\provision.ps1
.\provision.ps1
```

**macOS / Linux:**

```bash
curl -fsSL https://raw.githubusercontent.com/RoadRunner-1024/portal-ha-bridge/main/provision.sh -o provision.sh
chmod +x provision.sh
./provision.sh
```

Both do the same thing — **nothing needs to be pre-installed**. The script downloads Google's platform-tools if `adb` isn't on your PATH, downloads and installs the latest release APK if the app isn't already on the device, grants every permission/app-op (all require ADB — they can't be granted from the Portal UI), auto-enables the screen-control accessibility service, and prints a green verification checklist.

| Flag (Windows / macOS+Linux) | Effect |
|---|---|
| *(none)* | install the app if it's missing, then grant everything |
| `-Install` / `--install` | force a reinstall / update to the latest APK |
| `-Apk <path>` / `--apk <path>` | install a specific APK instead of downloading |
| `-SetLauncher` / `--set-launcher` | also set the immortal launcher as the kiosk home |
| `-Serial <id>` / `--serial <id>` | target a specific device when several are attached |

> Prefer to build it yourself? See [Building from source](#building-from-source) — the provisioner automatically uses your build output if it finds one.
>
> No computer at all? You can install the APK and grant **most** things via the app's own permission prompts — but **screen sleep and Portal presence each need a one-time ADB grant** (`WRITE_SECURE_SETTINGS` and `READ_LOGS`), because Portal blocks them from its UI. See [SETUP.md](SETUP.md).

### 2. Configure
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

### Portal+ camera aspect ratio

The Portal+ front camera ("Smart Camera") only exposes a virtual sensor that scales its field of view into whatever size is requested, so a normal 16:9 request comes out badly stretched. The app corrects this per model:

- **Portal+ 1st gen (`aloha`)** — its usable FOV is ~square, so the stream is encoded **480×480 (1:1)**. It displays correctly in every player with **no extra config**.
- **Portal+ 2nd gen (`cipher`)** — its FOV is 4:3 but the camera is portrait-mounted, so making it upright forces a **480×640** portrait buffer (a 4:3 scene squashed into 3:4). The encoder library can't stamp a pixel-aspect flag, so the 4:3 is applied **viewer-side**. For the HA WebRTC card, add a SAR via go2rtc's ffmpeg — it's a no-re-encode bitstream filter:

  ```yaml
  type: custom:webrtc-camera
  url: 'ffmpeg:rtsp://<cipher-ip>:8554/#video=copy#raw=-bsf:v h264_metadata=sample_aspect_ratio=16/9'
  ```

  In VLC direct, set **Video → Aspect Ratio → 4:3**. (A future encoder-pipeline rewrite could bake the aspect into the stream itself.)

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
- **Camera aspect ratio** differs by Portal+ generation — `aloha` streams square (1:1, no config), `cipher` streams 480×640 and needs the one-line 4:3 SAR filter in the HA card. See [Portal+ camera aspect ratio](#portal-camera-aspect-ratio).
- **Camera orientation**: both Portal+ models have a **fixed camera** (it doesn't pivot with the screen), so accelerometer auto-rotate is disabled for them and the stream uses a fixed rotation — upright out of the box (`aloha` rot 0, `cipher` rot 90), adjustable with the in-app **Rotate** button. Auto-rotate still applies to non-Portal+ models.
- **Portal+ 2nd gen (`cipher`)**: its accelerometer is mounted on the **moving screen arm**, which heavily dampens taps — so the tap threshold is auto-scaled, the gesture is relabelled **"Tilt"**, and its dominant (Z) axis reports **up/down** instead of front/back. All automatic — no config.

---

## Permissions

All ADB-granted (they can't be granted from the Portal UI). The provisioner (`provision.ps1` on Windows, `provision.sh` on macOS/Linux) does these for you:

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
provision.ps1           One-shot device setup for Windows (install + permissions + launcher)
provision.sh            One-shot device setup for macOS / Linux (same steps)
SETUP.md                Detailed setup & MQTT topics
```

---

## Troubleshooting

- **Camera "1 frame then freezes" in HA** → use the `ffmpeg:…#video=copy` URL (drops the AAC track); ensure the app is recent (Constrained Baseline profile).
- **No screen sleep** → `WRITE_SECURE_SETTINGS` not granted; run the provisioner (`provision.ps1` / `provision.sh`), or use the single grant in SETUP.md.
- **Camera "can't open from background"** → re-open the dashboard app (it re-acquires the camera in the foreground).
- **Provision says "no device"** → check `adb devices`, re-plug, accept the USB-debugging prompt.

---

## License

**PolyForm Noncommercial License 1.0.0** — see [LICENSE](LICENSE).

You're free to use, modify, and share this for **noncommercial** purposes (personal, hobby, education, non-profit). **All commercial use is reserved** to the copyright holder — © 2026 RoadRunner-1024. For a commercial license, contact the author.
