# Portal HA Bridge — Setup

Detailed install & MQTT reference. For the full feature list (camera, motion, presence, sensors, audio, tones) see the [README](README.md).

Runs as a persistent foreground service that starts on boot and exposes the Portal to Home Assistant over MQTT auto-discovery — including a screen switch (`OFF` sleeps, `ON` wakes).

## Requirements

- Meta Portal with ADB enabled (USB or network)
- Home Assistant with an MQTT broker (Mosquitto add-on recommended)

## Installation

### Recommended: one command (Windows)

The provisioner needs nothing pre-installed. With the Portal connected via ADB:

```powershell
iwr https://raw.githubusercontent.com/RoadRunner-1024/portal-ha-bridge/main/provision.ps1 -OutFile provision.ps1
Unblock-File .\provision.ps1
.\provision.ps1                 # install the app if missing + grant everything
.\provision.ps1 -SetLauncher    # + set the immortal kiosk launcher
```

It downloads `adb` (platform-tools) if it isn't on your PATH, downloads + installs the latest release APK if the app isn't already present, grants every permission/app-op, and enables the screen-control accessibility service. Use `-Install` later to force an update to a newer APK.

### Manual install (any OS with adb)

Prefer to do it by hand? Install the APK and grant the six ADB-only items yourself — these **cannot** be granted from the Portal UI:

```bash
adb install -r portal-ha-bridge.apk

adb shell pm grant com.aeonos.portalha android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.aeonos.portalha android.permission.RECORD_AUDIO
adb shell pm grant com.aeonos.portalha android.permission.CAMERA
adb shell pm grant com.aeonos.portalha android.permission.READ_LOGS
adb shell appops set com.aeonos.portalha WRITE_SETTINGS allow
adb shell appops set com.aeonos.portalha SYSTEM_ALERT_WINDOW allow

# restart so the app enables its own accessibility service
adb shell am force-stop com.aeonos.portalha
adb shell am start -n com.aeonos.portalha/.DashboardActivity
```

| Permission / app-op | Enables |
|---|---|
| `WRITE_SECURE_SETTINGS` | auto-enabling the screen-sleep accessibility service |
| `RECORD_AUDIO` | ambient sound-level sensor |
| `CAMERA` | camera streaming / motion |
| `READ_LOGS` | Portal presence sensor (logcat tail) |
| `WRITE_SETTINGS` | read/set screen brightness |
| `SYSTEM_ALERT_WINDOW` | overlay → background camera access |

### No computer at all?

Open the app and tap **Grant Missing Permissions**, then keep tapping until the status box is all ✓ — the app's own dialogs cover camera, microphone, brightness, and overlay. **Two features still need a one-time ADB grant**, because Portal blocks them from the UI:

- **Screen sleep** — `WRITE_SECURE_SETTINGS` (Meta hides accessibility services from Settings)
- **Portal presence** — `READ_LOGS`

The app shows the exact `adb` command (with a Copy button) when either is needed. Everything else works without adb — skip those two grants and you simply go without HA-controlled screen sleep and the presence sensor.

### Configure via the app

Open **Portal HA Bridge** on the Portal and fill in:

| Field | Value |
|-------|-------|
| Host | IP or hostname of your MQTT broker |
| Port | 1883 (default) |
| Username / Password | Your MQTT credentials |
| Device name | Name shown in Home Assistant (e.g. "Living Room Portal") |

Tap **Save & Restart Service**.

### Home Assistant

The app publishes MQTT auto-discovery — the entity appears automatically in HA under the device name you set.

- **Turn off** → screen sleeps (tap anywhere to wake manually)
- **Turn on** → screen wakes

Topics (for manual use or automations):

| Purpose | Topic |
|---------|-------|
| Command | `portal/<device_id>/screen/command` |
| State | `portal/<device_id>/screen/state` |
| Discovery | `homeassistant/switch/<device_id>_screen/config` |

The device ID is shown in the app's status area.

## Immortal Store catalog entry

```json
{
  "name": "Portal HA Bridge",
  "packageName": "com.aeonos.portalha",
  "source": "url",
  "apkUrl": "https://github.com/RoadRunner-1024/portal-ha-bridge/releases/latest/download/portal-ha-bridge.apk",
  "minSdk": 28,
  "description": "Home Assistant MQTT bridge for Meta Portal — screen, camera, sensors, presence",
  "author": "RoadRunner-1024",
  "homepage": "https://github.com/RoadRunner-1024/portal-ha-bridge"
}
```

## How sleep/wake work

| Action | Mechanism |
|--------|-----------|
| **Wake** | `PowerManager.FULL_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP` — standard `WAKE_LOCK` permission only |
| **Sleep** | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` — no device admin |

The Portal has no keyguard, so lock = screen blank, same behaviour as Immortal's `lockNow()` without needing device admin.
