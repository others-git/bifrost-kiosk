# bifrost-kiosk — companion Android app

Status: **v0.1 builds.** Kiosk + voice-satellite scaffold implemented; debug &
release APKs build green (see [Build & install](#build--install)). Seeded
2026-06-14, first build 2026-06-15.

## What this is

A single native Android app for the wall-mounted Bifrost tablet that does **two
jobs in one device-owner app**:

1. **Hard-locked kiosk display** — full-screen WebView pointed at the Bifrost
   dashboard, pinned via `startLockTask()` so the notification shade, recents,
   and home-escape are gone (a true single-use device).
2. **Always-on voice satellite** — wake-word listening ("active listening for a
   trigger word, **not** push-to-talk"), captures the utterance, sends it to
   Bifrost's voice API, and plays back the TTS reply.

This **consolidates** what we currently run as three separate pieces on the
tablet (see `DEVICE-STATE.md`):
- WallPanel (display) → replaced by this app's WebView kiosk
- TestDPC (device owner) → replaced by this app's own DeviceAdminReceiver
- (planned) standalone voice satellite → folded in here

Why one app: lock-task (the unescapable kiosk) requires the *foreground app
itself* to call `startLockTask`, and WallPanel can't. The voice satellite needs
its own foreground service anyway. One device-owner app that is both the
lock-task host and the voice service is the clean end-state.

Related: device setup runbook lives at `../bifrost-skills/tablet-kiosk/`
(SKILL.md). Architecture decision recorded in Claude memory
`tablet-voice-satellite-arch`.

## Target device

Galaxy Tab A9+ 5G — **SM-X218U**, Android 15 (One UI), Snapdragon. Locked
bootloader (`ro.boot.flash.locked=1`, US carrier variant) → no custom ROM, no
root. Everything must work via standard APIs + device-owner (adb-set).

## Components

### A. Kiosk display
- Full-screen immersive `WebView`, start URL `https://bifrost.theundead.live`
  (resolves to a LAN IP via local DNS; allow user-config + cleartext/SSL flags).
- `DeviceAdminReceiver` + set as **device owner** (`adb shell dpm
  set-device-owner <pkg>/.AdminReceiver`; device must have no accounts).
- On resume: `setLockTaskPackages(self)`, `startLockTask()`,
  `setStatusBarDisabled(true)`, immersive sticky, keep-screen-on.
- Declare `category.HOME` + `RECEIVE_BOOT_COMPLETED` so it is the launcher and
  auto-starts on boot.
- Admin-exit hatch (PIN-gated) to drop lock-task for maintenance.

### B. Voice satellite
- Foreground `Service`, `foregroundServiceType="microphone"`, started on boot,
  runs alongside the locked WebView.
- **Wake-word engine** (pick one — see Open questions):
  - openWakeWord (FOSS, ONNX/TFLite, custom words) — preferred for no-license.
  - Picovoice Porcupine (commercial SDK, free tier needs an access key).
  - Vosk (FOSS ASR; keyword spotting, heavier).
- Pipeline: wake-word → VAD-bounded capture → POST to Bifrost voice API → play
  TTS response. Use `AcousticEchoCanceler`/`NoiseSuppressor`; duck/handle the
  mic while TTS plays (barge-in optional later).
- Permissions: `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `RECEIVE_BOOT_COMPLETED`. Device-owner can
  auto-grant RECORD_AUDIO.

### C. Bifrost integration (define the client contract)
- Bifrost already has a **voice API** (api::voice — native deterministic grammar
  first, HA-Assist fallback) as of release 0.14.0. TODO: pin down the exact
  endpoint(s), audio/transcript format, and auth (Bearer key like /api/v1) the
  satellite should call. Decide on-device STT vs send-audio-to-server.
- Reuse the same Bearer-key auth as `/api/v1` if posting audio/text.

## Cutover (when this app is ready)
1. Build + sideload this app.
2. **Clear the current device owner first** — TestDPC holds it now; only one
   device owner allowed. Remove via TestDPC UI ("Remove device owner") or
   `dpm remove-active-admin com.afwsamples.testdpc/.DeviceAdminReceiver`
   (else factory reset). Device must have **no accounts** to re-`set-device-owner`.
3. `dpm set-device-owner <thisapp>/.AdminReceiver`, set as Home, reboot, verify
   pinned kiosk + voice service.
4. Uninstall WallPanel + TestDPC.

## Decisions (were open questions)
- **Wake-word + STT: Vosk** (FOSS, CPU-only, fully offline) — one small model
  serves both wake detection and command transcription. Wake word: `bifrost`
  (configurable). The `SpeechEngine` interface keeps openWakeWord/Porcupine
  swappable later.
- **STT on-device** (not streamed), because Bifrost's audio endpoint
  `/api/voice/listen` isn't built yet — the tablet transcribes and POSTs **text**
  to the shipped `/api/voice/command`.
- **TTS on-device** (Android `TextToSpeech`) reads back the `said` reply — no
  server audio contract needed.
- **Kotlin + Views**, single `:app` module, minSdk 26 / target 35.
- **Half-duplex**: the mic is paused while TTS speaks (resumes after), so the
  recognizer never hears itself — no AEC tuning needed for v1.

### Still open on the **Bifrost** side
`/api/voice/command` is **session-gated** today; the satellite sends a `bfr_`
Bearer token (forward-compatible). Exposing the voice seam under Bearer auth
(like `/api/v1`) is the one server change needed for headless auth. See CLAUDE.md.

## Tech stack
Kotlin, Android Views, single Gradle module. No Google Play dependency (tablet is
being de-Googled). Sideload via adb (`verifier_verify_adb_installs 0` already set
on the test tablet).

## Build & install
```bash
source scripts/env.sh                 # JDK 17 + Android SDK env
./scripts/fetch-vosk-model.sh         # optional: enable on-device voice (~40MB)
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.33:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```
The APK builds and installs **without** device-owner (soft kiosk) and **without**
the Vosk model (voice idle). To make it the hard kiosk, complete the cutover below
then `adb shell dpm set-device-owner live.theundead.bifrost.kiosk/.AdminReceiver`.
Reach setup any time by long-pressing the screen's **top-right corner** → PIN
(default `0000`).
