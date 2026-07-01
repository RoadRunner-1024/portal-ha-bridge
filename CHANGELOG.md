# Changelog

All notable changes to Portal HA Bridge. Versions are the app `versionName`;
the in-app updater (Settings → *Check for Updates*) and the provisioner both pull
the latest GitHub release.

## v1.15.0 — Native Home Assistant frontend integration + talk-button drag-to-delete

**Added**
- **Native HA frontend integration.** The dashboard now speaks Home Assistant's
  "external app" protocol (the same one the official companion app uses), so it's
  treated as a native wrapper — no need to switch to the HA app, and the camera and
  everything else stay:
  - **App Configuration** entry in HA's sidebar → opens this app's settings.
  - The HA **voice/Assist button works** and routes to your voice assistant
    (Jarvis) — captured natively, so it works even on a plain-HTTP HA where the
    browser mic is normally blocked.
  - **No-login / no-logout auth** — the dashboard authenticates with your saved
    long-lived token, so no sign-in and it won't drop the session.
  - Only activates when a long-lived token is set (Settings → HA token); otherwise
    the dashboard uses the normal web login.
- **Drag-to-delete talk buttons.** Double-tap a talk button to enter move mode; a
  circular ✕ target appears at the bottom of the screen. Drag the button onto it
  (it highlights red) and release to delete that button.

**Fixed**
- **Removed talk buttons now actually disappear.** Editing the talk buttons in
  settings reliably reconciles the floating overlays (previously a removed button
  could linger — and a transparent/low-opacity one became an invisible touch trap
  that blocked closing Home Assistant popups). Deleting the *last* button no longer
  re-seeds the default "Talk" button.

## v1.14.1 — Wake-word accuracy: confidence gating + contamination reject

**Fixed**
- **Far fewer wake-word false positives.** Ported the accuracy gates from
  rudysev/portal-wake's on-device-tuned matcher: the detector now acts only on
  **finalized** decodes (never unstable partials), uses **per-word confidence**
  (`setWords`), and rejects any decode that is **contaminated** — i.e. contains
  Vosk's `[unk]` token. A genuine close-mic "hey jarvis" decodes as a bare
  `hey jarvis` with no `[unk]` and both words near 100% confidence; background
  audio (TV, a nearby phone call) that assembles a wake shows up as
  `[unk] hey jarvis` or with a weak "hey" — now rejected. Also requires the "hey"
  lead in front of the keyword (≥80% confidence) and the keyword itself ≥60%, and
  logs near-misses (`wake: near-miss […] (rejected)`) for tuning.

## v1.14.0 — Wake-word false-trigger fix + readable updater dialog on Gen-1 Portal+

**Fixed**
- **Wake word no longer re-triggers itself after the assistant replies.** The
  detector now requires the **whole phrase** ("hey jarvis"), not just the last word
  — a one-word grammar mapped almost any speech onto the keyword, so the assistant's
  own spoken reply kept re-firing the handoff. It also ignores matches for a few
  seconds right after a handoff (so the reply echoed through the mic can't re-fire),
  and editing the wake phrase now rebuilds the recognizer live instead of needing a
  service restart. The phrase is always prefaced with **"Hey"** ("jarvis" and
  "hey jarvis" both become "hey jarvis") — a bare keyword is what false-triggered.
- **Self-update on Gen-1 Portal+ (Android 9) no longer shows a blank installer.**
  Meta's RRO theme overlay renders the system "Update?" dialog white-on-white, so
  the Install/Cancel buttons were invisible. The overlay can't be durably disabled
  (it re-enables on every reboot), so instead the updater briefly turns on the
  system **high-contrast text** setting just for the install — making the dialog
  legible — and restores your previous setting once it finishes. No effect on
  Android 10 Portals, which don't have the issue.

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
