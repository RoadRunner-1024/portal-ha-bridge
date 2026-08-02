# Changelog

All notable changes to Portal HA Bridge. Versions are the app `versionName`;
the in-app updater (Settings → System & Updates) and the provisioner both pull
the latest GitHub release.

## v1.18.0 — Listen-in audio, self-updating fleet, intercom polish

**Added**
- **Camera stream audio (experimental, off by default).** "Stream audio" in
  Camera Settings adds the room's sound to the RTSP stream — listen-in from
  Home Assistant (the settings page shows the right card config for audio).
  It taps the wake-word microphone rather than opening its own, so calls,
  Alexa and "hey jarvis" are unaffected; the track just goes quiet for a few
  seconds while they hold the mic. Turning it on makes the camera a live room
  microphone — it's per-device and off unless you choose it.
- **Daily update checks (opt-in).** "Check for updates daily" in the main
  settings: once a day, at a random time per Portal, the app checks GitHub.
  When a new version appears you get a prompt with the release notes —
  **Update now**, **Skip this version** (never asks again for that one), or
  **Later** (asks again tomorrow).
- **2-way reply timeout slider.** How long the hands-free reply channel stays
  open in silence is now adjustable (2–60 s) in Intercom Settings. It was
  hardcoded to ~2 s — and the old settings text claiming "a minute" now tells
  the truth.
- **Talk button shows the Portal orb (2-way).** Holding a talk button brings
  up the orb in orange — channel ready, you're live — and on release of a
  broadcast announce it cools to blue as the reply window opens. Same orb,
  one continuous gesture.

**Fixed**
- **Ghost talk buttons.** Rapid rebuilds (settings changes, deleting a button,
  dashboard transitions) could leak untracked button windows that drag-to-❌
  could never remove — only an app restart cleared them. The overlay teardown
  is now ordering-proof; ghosts can't be minted.
- **Orb stuck blue on push-to-talk.** The one-shot "transmitting" state could
  arrive before the orb's window existed and was lost; orb state is now sticky
  across window creation.
- **Stranded-dashboard auto-return hardened.** The return check no longer
  gives up when the screen is dark (a screen-wake resumes the launcher, not
  the app, so nothing else would have re-checked), and every screen-on now
  verifies the camera actually runs — recovering evictions whose events were
  lost while the screen was off.
- **RTSP log flood silenced.** The streaming library logged every packet
  (~150 lines/s with a connected client), drowning the device log and getting
  the app's own diagnostics pruned.

## v1.17.2 — Camera stream self-heal + call awareness

**Fixed**
- **The camera stream now recovers by itself instead of dying silently.** Two
  long-standing wedge states left the app *believing* it was streaming while
  Home Assistant showed a dead camera until the app was manually restarted:
  - Camera turned ON while the app was in the background (e.g. the Portal was
    left on the launcher): Android 10 refuses the camera but the stream
    reported success. The app now detects that the camera never opened and
    restarts the stream — automatically when the dashboard is visible, or the
    moment it next comes to the front (the HA **Show Dashboard** button is
    enough to heal it remotely).
  - A restart race at startup could lose the RTSP port (`EADDRINUSE`) and
    leave a zombie server. The app now detects the dead server and retries
    with backoff, and the restart itself waits longer for the port to free.
  - Camera-freed events from the app's *own* stream restarts no longer
    masquerade as "a call took the camera" (this caused the cascade of
    restarts that triggered the port race).
  - **Stranded-dashboard auto-return.** The Portal launcher can take the
    foreground by itself (observed in the wild); on Android 10 that evicts the
    camera, and on a Portal whose presence keeps the screen awake, nothing
    ever brought the dashboard back — the room saw the launcher and HA saw a
    dead camera for hours. If the stream is dead while the app is not in
    front, the dashboard now returns on its own after ~90 s (never during a
    call, a cast, an Alexa turn/playback, or while the screen is off).

**Added** *(committed earlier, first shipped in this release)*
- **In Call sensor + Show Dashboard button in Home Assistant.** The bridge
  publishes whether the Portal is in a Messenger/WhatsApp call, and a button
  that wakes the screen and brings the dashboard to the front from HA.
- In-call guards across the app: wake handoffs, announcements, incoming
  intercom audio, cast launches and the Alexa warm-up all stand down while a
  call is active.

## v1.17.1 — Alexa provisioning on macOS/Linux

**Added**
- **`./provision.sh --alexa`** — the macOS/Linux provisioner now does the full
  one-command Alexa setup (download + SHA-verify + install + grants +
  amazon.com/code sign-in + kick-until-connected), matching the Windows
  script's `-Alexa`. It verifies the falcon install and fails loudly rather
  than pretending.

**Fixed**
- In-app provisioning messages show both the Windows and macOS/Linux commands.
- Provisioner (`provision.sh`): a failed platform-tools bootstrap now stops the
  script instead of continuing with a broken adb path.
- Otherwise identical to v1.17.0; the bump also ensures devices running
  pre-release 1.17.0 builds are offered the final update.

## v1.17.0 — Alexa on your Portal + YouTube casting

**Added**
- **Alexa on your Portal — including Android 10.** With the stock Alexa client
  provisioned (one-time `provision.ps1 -Alexa` / `./provision.sh --alexa` over
  USB — see the README), the
  app's own on-device wake word hands the mic to **real Amazon Alexa**. This
  works on **Android 10 Portals**, where the stock "Hey Alexa" wake app is deaf
  (Android silences background mic capture; the bridge brings Alexa forward
  invisibly behind a frozen-frame cover for the turn). Enable **Alexa support**
  in Settings → Display & Presence — the phrase is editable (default "alexa")
  and it runs alongside the Jarvis wake word: two assistants, one mic.
  - **Long answers play to the end.** The mic hand-back is playback-aware:
    "alexa, tell me a story" holds the conversation open while she's audibly
    speaking and gives the mic back a few seconds after she stops — so stories
    aren't cut off and interactive skills can keep asking you questions.
  - **Barge-in.** Say the wake word while Alexa is talking to interrupt her —
    "alexa … stop" works mid-story, just like a real Echo.
  - **Multi-turn dialogs** ("alexa, set a reminder" → "what's the reminder?")
    keep the mic with Alexa across follow-ups.
  - **Cyan listening bar** as the "speak now" cue: say the wake word, wait for
    the bar/beep, then give the command.
  - **"alexa stop", handled properly.** Said in one breath, the words are gone
    before Alexa's mic can open — so the bridge recognizes the phrase itself and
    acts locally: playing music is paused instantly (no cloud round-trip, no
    "something went wrong"), and mid-story it cuts her off on the spot.
  - **Cold-start auto-retry.** The first request after an app restart used to
    fail with "something went wrong" (Alexa's first mic grab loses a race while
    its UI cold-starts) — the bridge now detects the instant abort and silently
    retries; worst case you hear a beep and repeat the command once.
  - **Portal+ 1st-gen (Android 9):** Alexa shows her own story/music card there
    (no screen takeover needed), and it stays up while audio plays. When the
    interaction ends, the Portal now returns straight to the Home Assistant
    dashboard instead of stranding on the Meta home screen.
  - **One-time USB provisioning required** — an app update can't install
    Amazon's Alexa client (see the README, *Alexa on your Portal*). The app
    shows a one-time notice after updating, and the Alexa toggle explains the
    step on unprovisioned Portals. Until provisioned, everything else works as
    before.
- **Seamless assistant handoff.** The Android-10 wake takeover (Jarvis and
  Alexa) is now invisible: the screen is covered with a pixel-perfect frozen
  frame of the dashboard before the assistant comes forward, the dashboard
  returns the moment the assistant has the mic, conversations are no longer cut
  short between turns, and **camera feeds no longer reload after a wake** (the
  dashboard WebView stays "visible" to Home Assistant throughout).
- **Provisioner: one-command Alexa setup + auto-update.** `provision.ps1 -Alexa`
  (Windows) or `./provision.sh --alexa` (macOS/Linux) downloads and verifies the
  Alexa client, installs and grants it, opens the code sign-in (enter it at
  amazon.com/code — UK accounts work), and relaunches it until connected. The
  Windows script also now **auto-updates the app** whenever your local build is
  newer than what's installed.
- **YouTube casting.** The Portal now shows up in the **cast menu of the YouTube
  app** on any phone on your Wi-Fi (Android and iPhone) under its device name —
  exactly like a smart TV. Tap it and the Portal switches from the dashboard to
  a full-screen YouTube player; **everything is controlled from the phone**
  (browse, play/pause, seek, queue — the Portal is just the screen). A sleeping
  Portal **wakes when you cast**. Disconnecting on the phone (or a long-press on
  the Portal's screen) returns to the Home Assistant dashboard.
  No pairing codes, no cloud linking, no configuration: discovery is DIAL over
  the LAN (the pre-Chromecast smart-TV mechanism the YouTube app still speaks),
  playback is YouTube's own TV web client. DRM apps (Netflix & co.) can't work
  this way — they require certified receivers.

**Fixed**
- **Alexa was inaudible with the bridge running.** The keep-alive's continuous
  silent audio track occupied the Portal's audio output path and starved Alexa's
  speech (playback "succeeded" but nothing was heard). The silent track is gone;
  the media-session keep-alive remains.

## v1.16.0 — Voice announce, glowing orb, and experimental hands-free 2-way

**Added**
- **Voice announce.** Say your wake phrase + **"announce"** in one breath ("hey
  jarvis announce"), wait for the double-chirp, then speak — your live voice
  broadcasts to every Portal over the intercom. ~2s of silence ends it (end tone),
  30s max. No assistant round-trip, no synthesized speech — it's your own voice.
  Deliberately hard to mis-fire: the phrase must decode **exactly** with **every
  word ≥90% confidence**, and it only transmits if real speech follows the chirp.
  Toggle in Settings → Display & Presence.
- **Glowing Portal-style orb.** A big animated aperture (glowing rim, swirling
  vortex, orbiting sparks) shows while a voice announce or 2-way channel is live —
  **orange** on the Portal transmitting, **blue** on those receiving, throbbing with
  the audio. Tap it to stop/hang up.
- **Screen wakes on an announcement.** A sleeping Portal now wakes and shows the orb
  when an announcement arrives, instead of only playing audio to a dark screen.
- **Experimental hands-free 2-way.** Settings → Intercom → **Enable 2-way**. When on,
  finishing any Everyone-announce opens a live **reply channel**: every Portal
  auto-arms and you just **talk back hands-free** — voice-activated, one at a time
  (first-come lock, so no garble), with the speaker's orb glowing orange so everyone
  sees who has the floor. Tap a Portal to hang up; ~2s of silence drops it. Uses the
  `VOICE_COMMUNICATION` mic path for echo cancellation. Off by default.

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
