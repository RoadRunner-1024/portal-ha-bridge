# Changelog

All notable changes to Portal HA Bridge. Versions are the app `versionName`;
the in-app updater (Settings → System & Updates) and the provisioner both pull
the latest GitHub release.

## v1.21.1 — Music and calls get along

**Fixed**
- **The music comes back after a call.** If a call rings and isn't answered, the music is only
  quietened for the ring and returns — along with the now-playing screen — once it stops. It used
  to stay silent while Music Assistant carried on playing, with the screen gone until the next
  song.
- **Answering a call now stops the music**, on the Portal and on that speaker in Music Assistant,
  rather than leaving it half-suspended. Taking a call means you're done listening.

## v1.21.0 — A now-playing screen with sing-along lyrics, and a second way to be a speaker

**Added**
- **A now-playing screen while music is playing.** Album art, title/album/artist, and transport
  controls (previous / play-pause / next / stop) with a volume slider, over your screensaver.
  Drag the progress bar to skip through a track.
- **Sing-along lyrics.** Tap the album art for a full lyrics view in the style of the Music
  Assistant player: the current line stays fixed in the middle of the screen while the song
  scrolls up past it and fades away, over a backdrop in the album's own colours. Lyrics come
  from LRCLIB (free, no account needed); tracks without lyrics simply say so, and instrumental
  stretches show a row of notes.
- **A Music settings screen**, holding both speaker options and the now-playing screen, plus a
  new option to stop the music when you close that screen (off by default — normally closing it
  just puts it away).
- **Sendspin (experimental): the Portal as a synchronised speaker.** A second way to be a
  speaker for Music Assistant, alongside DLNA, using the Open Home Foundation's Sendspin
  protocol. Unlike DLNA it's built for playing in step with other speakers, and it sends the
  track details and artwork straight to the Portal. **Off by default** — turn on "Synced
  speaker" in Settings → Music, or the matching Home Assistant switch. Needs Music Assistant
  2.8 or newer. Multi-room grouping itself is untested so far; that's the next thing to look at.
- **Home Assistant switches per Portal** for each of the above, so every Portal can be set up
  independently.

**Fixed**
- **Incoming calls could ring but not be answered.** The app only recognised a call once it was
  *connected*, so while it was ringing the screen stayed covered and the Answer button couldn't
  be reached — the call then timed out as missed. Calls also arrived as a small
  picture-in-picture tile instead of full screen. Both fixed.
- **The screen now follows the queue.** Music Assistant's "flow mode" sends a whole queue as one
  continuous stream, so a DLNA speaker is never told when the track changes — the screen used
  to stay stuck on whichever track was playing when the stream began.
- **Music gets out of the way properly.** It now goes quiet for an Alexa turn, an intercom
  announcement or a call, and comes back afterwards. Intercom announcements used to make music
  *louder* rather than quieter.
- **Things stay in the right order on screen** — Alexa's listening bar and the intercom talk
  buttons stay on top of the now-playing screen, which in turn stays above the screensaver. The
  screensaver no longer disappears for an Alexa turn and then reappears over the music.
- Album art now refreshes on every track change.
- Event notifications to controllers (GENA) were never actually sent, so controllers were never
  told when playback state changed.
- Gapless queue playback (`SetNextAVTransportURI`) is now supported.

## v1.20.5 — The Portal is now a speaker (Music Assistant / DLNA)

**Added**
- **Play music to the Portal from Music Assistant.** Each Portal now advertises itself as a
  DLNA/UPnP renderer, so Music Assistant discovers it automatically (under its DLNA provider /
  Universal Player) and it shows up as a speaker you can play to — no Home Assistant config,
  no add-ons. Any other DLNA controller on the network can use it too. Play, pause, stop,
  seek, volume and mute all work, and the now-playing position/state is reported back.
- **It gets out of the way for the important things.** Music automatically pauses for a call
  or an Alexa turn and resumes afterwards (it yields the speaker via audio focus), and drops
  out for the wake word so the Portal can still hear you.
- On by default. (A per-Portal on/off toggle and now-playing details in Home Assistant are
  coming in a follow-up.)

**Note**
- This is standalone playback — the Portal plays on its own. Tight multi-room sync with other
  speakers (grouped playback in perfect step) isn't part of this; that would need a different
  protocol.

## v1.20.4 — The Calls button skips the screensaver and comes back on its own

**Added**
- **Tapping Calls goes straight to the calling screen.** Opening the Portal's own Calls
  app lands on its idle photo/clock screen, which you normally have to tap to get past.
  The Portal now clears that for you, so you arrive on your favourites and Contacts ready
  to call. Works whether the launcher was already warm or cold from a reboot.
- **It brings the dashboard back after a call.** Once you're done — or if you open Calls
  and wander off — the Home Assistant dashboard returns on its own after a minute, as
  long as no call is in progress. Leaving the Portal for anything else (a browser, a
  video) is left alone; only the calling screen is followed. The minute is adjustable.

**Note**
- This uses the Portal's accessibility permission (already granted for screen sleep). After
  updating to this version a Portal needs **one reboot** for the new behaviour to switch on —
  it does not take effect until the accessibility service reloads. After that first reboot it
  is automatic.

**For setup**
- The provisioner now enables the stock Portal launcher (the only app Meta trusts to open
  Contacts/calling) and pins Immortal as the default Home, so the Calls button works on a
  freshly provisioned Portal. Both are default now — the old `-SetLauncher` / `--set-launcher`
  flag is no longer needed (still accepted, does nothing).

## v1.20.3 — An Alexa announcement no longer takes the Portal away from you

**Fixed**
- **The Portal comes back straight after an Alexa announcement.** An announcement — from
  the Alexa app, or from another Echo — put the Alexa client on screen, usually as a
  black screen, and left it there for about **35 seconds**. Worse than the look of it:
  while another app is in front, Android stops our microphone hearing anything at all,
  so for that whole time the Portal could not respond to "alexa" — there was no way to
  reply to the announcement, ask a follow-up, or do anything but wait it out or press
  Home. The dashboard now returns as soon as Alexa has finished speaking, about a
  second and a half after her last word, which is also when the microphone comes back.
  The announcement itself is never cut short.
- **Anything that puts itself in front of the dashboard is now undone, not just the
  camera case.** Previously the only automatic recovery ran when the camera stream had
  died, so on a Portal with the camera off nothing ever brought the dashboard back. It
  now recovers whatever pushed in — an announcement, or the launcher deciding to show
  its home screen — once that thing has finished.
- **Leaving the dashboard on purpose still works exactly as before.** If you tap through
  to another app, or press Home, the Portal stays where you put it and will not drag the
  dashboard back over the top. The difference between "you left" and "something barged
  in" is now decided by whether anyone actually touched the screen.

## v1.20.2 — Immich Kiosk navigation, and the blank screensaver actually sticks

**Fixed**
- **Tapping left and right now works on Immich Kiosk.** The middle-tap exit worked
  because that is ours, but the photo never changed: Kiosk listens for a **key-up** on
  the page body, while ImmichFrame listens for a **key-down** on the window, and we
  were only sending the latter. Both are now satisfied by one event, so navigation
  behaves the same whichever frame you point it at.
- **"Blank screen while asleep" no longer gets quietly undone.** The launcher rewrites
  the system screensaver setting every time its home screen appears — which is every
  restart and every time something sends the Portal home — so our blank screensaver was
  being replaced within minutes of being set, and the flash on wake came back. The
  setting is now watched and reclaimed immediately. If you had this turned on and were
  still seeing a flash of the launcher's screensaver, this is why.

**For setup**
- A Portal can now have its device name, broker, port and Home Assistant address filled
  in over USB during provisioning, instead of being typed on the panel. Passwords and
  tokens are deliberately excluded — those are still entered on the device or, for the
  Home Assistant token, sent from Home Assistant itself.

## v1.20.1 — Comes back after a power cut, and its own settings screen

**Added**
- **Show dashboard after a restart** (on by default). After a reboot or a power cut the
  Portal puts the dashboard back on screen instead of sitting on the launcher. It
  keeps asserting itself for the first minute, because launchers tend to grab the
  screen back while the system settles — so this works whichever launcher you use,
  and needs nothing configured in the launcher itself.
- **Shorten the Portal's own screen timeout** (off by default). Sets Portal OS's
  ambient display timeout to 1 minute, where it ships at 5. It has no effect while
  the dashboard is on screen; it only shortens how long something else can sit there
  after a restart. Turning it off restores your original value. New Portals get this
  set during provisioning.
- **The screensaver has its own settings screen** — Display & Presence had grown too
  long to find anything in. Everything for the photo frame now lives under
  **Display & Presence → Photo Screensaver settings**.

**Fixed**
- **"Check for Updates" now shows the release notes.** The daily update prompt always
  did; checking manually gave you a version number and nothing else, so you had no
  idea what you were updating to. This is that screen.
- **Turning the screensaver on without an address** no longer leaves you with a
  feature that silently does nothing. The address is now required, and highlighted
  until you fill it in.

## v1.20.0 — A photo screensaver, and a Portal that stays out of your way

**Added**
- **Photo screensaver.** Point it at your **ImmichFrame** or **Immich Kiosk** page and the
  Portal shows your photos when nothing has been touched for a while. Which photos
  appear stays configured where it already is — there is nothing to set up here
  beyond the address. Tap the **left or right third** to move between photos and the
  **middle** to go back to the dashboard.
  - **The camera keeps streaming behind the photos.** The frame is drawn over the
    dashboard rather than replacing it, so a Portal being used as a camera in Home
    Assistant carries on working while it shows photos.
  - **Wake to photos** — show the photos rather than the dashboard when the screen
    wakes, so the Portal greets you as a photo frame.
  - **Keep photos ready while asleep** — loads the page behind the dark screen so it
    appears instantly instead of showing a blank page while it starts up. Costs some
    memory for as long as the Portal sleeps, so it is yours to choose.
  - **Only when someone's there** — photos while presence is detected, screen off when
    the room empties.
- **Control it from Home Assistant.** Every Portal gets a **Photo Screensaver** switch, a
  **Dismiss Screensaver** button, and a **Screensaver Dismiss Hold** number, plus a single
  **Dismiss Screensaver (All Portals)** button that clears the photos on every Portal at
  once — for pop-up cameras on motion, without listing each panel in the automation.
  Dismissing holds the photos off for a set time rather than letting them cover your
  cameras again a moment later; publish a number of seconds to
  `portal/screensaver/dismiss` to set that per automation.
- **Blank screen while asleep** (on by default). The Portal starts its own screensaver
  whenever the screen times out, which is why you saw a flash of the launcher's
  screensaver every time it woke. This replaces it with a blank screen. Turn it off and
  whatever was set before is put back.
- **Sleep even if someone's there.** Presence normally keeps the screen awake; with this
  on the screen-off timer runs regardless, so the Portal goes dark on schedule.

**Fixed**
- **The dashboard no longer barges in over another app.** If you left the Portal on
  Netflix or a browser, the dashboard could haul itself back to the front a minute or so
  later, and again on every screen wake. It now tells the difference between you
  choosing another app and the app being pushed aside by something else, and only
  recovers in the second case.
- **No flash of the launcher's screensaver when the screen wakes** (see above).

## v1.19.1 — A restart now fixes a stuck dashboard

**Fixed**
- **Stale web content could jam the dashboard indefinitely.** A Portal runs
  the same page for weeks and has no browser UI, so once it cached an old
  copy of something, nothing you could reach would shift it — even a reload
  re-served the stale copy rather than re-checking with the server. On one
  Portal this showed up as an embedded photo-frame page reloading itself
  about thirteen times a second, strobing the screen white; on the same
  device it had also quietly pinned Browser Mod to a months-old version.
  Each launch now starts with a clean web cache, so **restarting the Portal
  is the cure** for anything of this kind.

**Note**
- Because of the above, the first launch after this update may pull your
  Portal onto newer versions of custom cards and frontend resources it had
  been holding stale. If a card then reports a version mismatch, that
  mismatch was already there and simply hidden — it usually means Home
  Assistant needs restarting so its side matches the files it is serving.

**Added (diagnostics)**
- Page and iframe console output now goes to the device log, so a page
  failing inside the kiosk is visible instead of silent.
- Chrome DevTools can attach over adb for inspecting the dashboard. The
  debugging socket is reachable only over adb, never from the network.

## v1.19.0 — Fewer false wakes, settings you can find, Alexa that answers first time

**Added**
- **Neural false-wake check.** The wake word now gets a second opinion: a small
  neural network re-listens to the two seconds around the match and has to
  agree before anything wakes up. This is what stops the TV, a passing
  conversation or a word that merely rhymes from setting the Portal off.
  Voice & Assistants has an on/off switch and a strictness slider — the
  default is deliberately forgiving, because a slow or quiet "alexa" scores
  much lower than a brisk one and must still get through.
- **Settings reorganised into six sections.** Camera, Display & Presence,
  Voice & Assistants, Intercom, Sensors, and System & Updates. The main screen
  keeps what you actually come back for — connection details and live status —
  and a **⚠ Fix Missing Permissions** shortcut now appears there whenever
  something needs granting, instead of hiding at the bottom of a list.
- **Keep Alexa warm (opt-in, off by default).** Alexa's connection goes stale
  after a minute or so of quiet, which is why the first thing you asked after
  a while used to fail. With this on, the app quietly nudges her every 45
  seconds so your first request lands. She can't hear the room while it
  happens — her microphone is deliberately kept shut — and her "listening"
  indicator is hidden behind the dashboard. The cost: the dashboard is a still
  image for a few seconds each cycle, which only matters if you have live
  camera feeds on it. Off by default so the choice is yours.
- **Motion sensitivity slider** in Camera Settings. It was adjustable only
  from Home Assistant — the one camera setting with no control on the device.
- **Live temperature readout** in Sensors: "Sensor reads X°C → HA sees Y°C",
  so the offset can be set by eye rather than by arithmetic.

**Fixed**
- **Alexa's "sorry, something went wrong" on the first request.** When her
  session had gone stale the request was lost and you had to repeat yourself.
  The app now detects the aborted turn and retries within a fraction of a
  second, cutting her apology short. Turn on Keep Alexa Warm to stop it
  happening in the first place.
- **Sensor settings could quietly undo Home Assistant.** With the Sensors
  screen open, a temperature offset changed from HA wasn't picked up, and
  leaving the screen wrote the stale value back — reverting HA without a word.
- **"Enhanced presence" greyed out with no explanation.** The setting that
  disables it moved to another screen in this release; the helper text now
  says which one.
- **Documentation pointed at screens that no longer exist.** The README and
  both provisioners named the old settings layout — including the provisioner
  message printed at the exact moment you go looking for the Alexa switch.

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
