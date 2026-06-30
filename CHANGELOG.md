# Changelog

All notable changes to Portal HA Bridge. Versions are the app `versionName`;
the in-app updater (Settings → *Check for Updates*) and the provisioner both pull
the latest GitHub release.

## v1.13.0 — On-device "hey jarvis" wake word (incl. Android 10)

**Added**
- **Hands-free wake word, on-device.** This app now detects the wake phrase itself
  (an offline **Vosk** recognizer on the mic it already holds) and triggers Jarvis
  via portal-wake's public handoff — **no separate wake app, and it works on
  Android 10 Portals**, which portal-assistant marks "Gen-1 only". Enable
  **On-device wake word** in Settings → Display & Presence (downloads a ~40 MB
  model once; the APK stays small).
- **Editable wake phrase** (default "hey jarvis"). Vosk is grammar-based, so any
  phrase works with no new model or retraining.
- On **Android 10**, a background-woken assistant is denied the mic, so the app
  briefly brings the assistant to the foreground to capture, then returns to the
  dashboard (a short per-wake takeover). On **Android 9** it stays subtle. The
  wake word is mutually exclusive with **Coexist** (both want the single mic).

## v1.12.0 — Set the HA token from Home Assistant

**Added**
- **"HA Token" entity** — an MQTT `text` entity (config category, password mode)
  under the Portal device. Paste your Home Assistant long-lived token into it in
  HA's own UI and it syncs to the Portal — no typing on the device, and you can
  set it for every Portal in the fleet from HA. The token is write-only (no state
  topic, so it's never echoed back or retained on the broker).

## v1.11.x — Voice assistant integration (Jarvis)

**Added**
- **Tool-provider plugin for [portal-assistant](https://github.com/rudysev/portal-assistant) ("Jarvis").**
  Control this Portal *and your whole Home Assistant* by voice — "Hey Jarvis,
  turn off the screen", "turn on Thea's light", "is anyone home?". Implemented as
  an exported `ContentProvider` per the assistant's public `ToolContract`; only
  the assistant package may invoke it. Tools: `set_screen`, `set_camera`,
  `get_presence`, `home_assistant` (natural language via HA Assist),
  `home_assistant_list` (discover any entity), `home_assistant_service` (control
  any entity). The list+service tools work for **every** HA device — no need to
  expose entities to HA Assist.
- **HA long-lived token field** in Settings (next to the HA URL) for the smart-home tools.

**Fixed**
- **Provider invisible in Jarvis** — the tool declarations are now inline literal
  manifest values, not `@string/` references. The assistant reads them with
  `getString()`, which returns `null` for a resource reference, so the provider
  was silently skipped (v1.11.1).
- **"Can't see my devices"** — added `home_assistant_list` so the assistant can
  discover entities directly over the REST API, instead of relying on HA Assist
  exposure (v1.11.2).

## v1.10.0 — Two-way Portal+ mic & voice-assistant coexistence

**Added**
- **Coexist with voice assistant** toggle (Settings → Display & Presence). The
  Portal has one mic, so this **releases it** for an always-on wake-word app
  (e.g. portal-wake "Hey Jarvis"): the Sound Level sensor and sound-based presence
  turn off, and the intercom captures on-demand only while announcing.
- **Two-way intercom on 1st-gen Portal+** — the provisioner gains `--free-mic`
  (`-FreeAlohaMic`), which disables Meta's "Hey Alexa" wake detector
  (`com.millennium`) to free the throttled microphone, making the Portal+ able to
  *send* on the intercom (not just receive). Reversible with `--restore-mic`.
  Meta face-presence and Smart-Camera framing are left untouched.

## Provisioning — Gen-1 Portal+ installer fix

**Fixed**
- On **1st-gen Portal+ (Android 9 / API < 29)**, a Meta display overlay
  (`com.facebook.aloha.rro.niu.android`) rendered the system package-installer
  dialog white-on-white, so the **in-app updater** and sideloads appeared to do
  nothing. The provisioner now disables that overlay (applied immediately, no
  reboot, doesn't disturb Shizuku) and reports it in the verification checklist.

## v1.9.0 and earlier

Enhanced presence (camera + ambient sound), in-app updater, Portal-to-Portal
intercom, RTSP H.264 camera streaming, presence detection, screen control,
ambient sensors, and MQTT auto-discovery. See the
[releases](https://github.com/RoadRunner-1024/portal-ha-bridge/releases) page.
