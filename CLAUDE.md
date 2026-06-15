# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

A single **native Android device-owner app** for a wall-mounted Galaxy Tab A9+ 5G
(SM-X218U, Android 15 / API 35, locked bootloader → no root, no custom ROM —
everything via standard APIs + device owner). It does two jobs in one process:

1. **Hard-locked kiosk** — full-screen immersive `WebView` on the Bifrost
   dashboard, pinned with `startLockTask()` so the shade/recents/home-escape are
   gone.
2. **Always-on voice satellite** — a `microphone` foreground service doing
   on-device wake-word + STT (Vosk), POSTing the command text to Bifrost's voice
   API, and speaking the reply via on-device TTS.

It must be **one app** because lock-task can only be entered by the foreground app
itself — WallPanel/the OS launcher can't, which is the whole reason this replaces
the WallPanel + TestDPC + planned-satellite stack. Companion to **Bifrost**, the
home media hub in `../bifrost` (its dashboard is the WebView target and it hosts
the voice API).

## Build & test

The toolchain (JDK 17 + Android SDK) is installed locally; **always `source
scripts/env.sh` first** — it sets `JAVA_HOME`, `ANDROID_HOME`, and points
`GRADLE_USER_HOME` off the slow `/mnt/d` DrvFs mount.

```bash
source scripts/env.sh
./gradlew testDebugUnitTest          # JVM unit tests (wake-word logic)
./gradlew testDebugUnitTest --tests '*WakeWordTest'   # a single test class
./gradlew lintDebug                  # lint (CI fails on lint errors)
./gradlew assembleDebug              # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease            # -> app/build/outputs/apk/release/app-release-unsigned.apk
```

`local.properties` (`sdk.dir=…`, gitignored) is written by `env.sh` users / CI's
SDK action. First build downloads the Gradle 8.9 distribution + dependencies.

## Architecture (the parts that span files)

**Device-owner is the foundation.** `AdminReceiver` (a `DeviceAdminReceiver`) is
set as device owner via `adb shell dpm set-device-owner
live.theundead.bifrost.kiosk/.AdminReceiver` (device must have **no accounts**;
only one device owner allowed — clear TestDPC first, see README cutover).
**`LockTask.kt` is the single choke point** for every device-owner policy call
(`setLockTaskPackages`, `startLockTask`, `setStatusBarDisabled`,
`addPersistentPreferredActivity`, self-granting `RECORD_AUDIO`). Every call there
**degrades gracefully when not device owner** — so the APK installs and runs as a
"soft kiosk" on an unprovisioned device for debugging; hard-pinning only kicks in
once device-owner is set. Touch policy code only through `LockTask`.

**Kiosk lifecycle (`MainActivity`).** On resume it re-pins + re-applies immersive
every time it regains focus (defends against anything surfacing over the kiosk).
Back is swallowed via `OnBackPressedDispatcher` (navigates WebView history, never
exits). The only escape is a **long-press on the invisible top-right corner**
(`maintenanceHandle`) → `PinGate` → `SettingsActivity`. It's also declared
`category.HOME` + auto-started by `BootReceiver`.

**Config is all in `Prefs`** (SharedPreferences) so one APK drops onto any tablet
and is pointed at any hub from the maintenance screen: dashboard URL, server base,
Bearer key, voice endpoint path, wake word, room context, exit PIN, voice on/off.

**Voice pipeline (`voice/` package), half-duplex:**
`VoiceService` (mic FGS, boot-started) → `VoicePipeline` orchestrates
`SpeechEngine` (interface; `VoskSpeechEngine` impl) → `WakeWord` (pure, gates on
the wake word) → `BifrostVoiceClient` (POST) → `TtsPlayer` (speak reply). The mic
is **paused while TTS speaks** and resumes only when playback finishes, so the
recognizer never hears itself — cheap echo handling without AEC tuning.
`SpeechEngine` is the swap seam (Vosk today; openWakeWord + separate STT later).

**`WakeWord` is deliberately Android-free and unit-tested** (`WakeWordTest`) — it
holds the homophone tolerance ("bifrost" ≈ "by frost"/"be frost", since small CPU
Vosk models mishear it), whole-word matching, and politeness stripping. Put voice
logic worth testing here, not in the engine.

## Bifrost integration — the contract

`BifrostVoiceClient` POSTs `{ text, context?: { room } }` to
`serverBase + voiceEndpoint` (default `/api/voice/command`) and reads
`{ ok, said }` (the server also returns `clauses[]`; we ignore it). This matches
Bifrost's **shipped** M23-P1 text→action seam.

Two deliberate decisions:
- **On-device STT** (Vosk). Bifrost M23 P2 *has now shipped* the server-side
  audio endpoint `POST /api/voice/listen` (multipart audio → command result), but
  we stay on-device for now: half-duplex, no upload, no round-trip latency. That
  endpoint is the swap seam for server-side STT later.
- **On-device TTS** (Android `TextToSpeech`) reads back `said` — no server audio
  contract needed.

**Auth (resolved).** The voice seam now accepts **either** a browser session
**or** a `bfr_` Bearer key — `voice_authed()` in `../bifrost` `src/api/voice.rs`
ORs `require_session` with `require_api_key` (covered by the
`voice_command_accepts_bearer_api_key` test). So the headless satellite
authenticates with its minted key, exactly as the client already sends. (As of
2026-06-15 this lives in an **uncommitted** change in `../bifrost`; confirm it has
landed before relying on headless auth in the field.)

## The Vosk model is not in the repo

The acoustic model (~40MB) is **not committed** and **not required to build/run** —
without it `VoskSpeechEngine` logs and stays idle (the kiosk is unaffected). To
enable voice, run `scripts/fetch-vosk-model.sh` (downloads vosk-model-small-en-us
into `app/src/main/assets/model-en-us/`, gitignored) before building. The APK
grows accordingly. `androidResources.noCompress` keeps the model uncompressed so
Vosk can mmap it.

## Decisions locked (were README "Open questions")

Kotlin + **Views** (not Compose); single `:app` module; **minSdk 26 / target 35**;
**no Google Play dependency**; wake-word + STT via **Vosk** (FOSS, CPU, offline);
**half-duplex** (pause mic during TTS) over barge-in; package
`live.theundead.bifrost.kiosk`.

## CI / release

`.github/workflows/ci.yml` — tests + lint + `assembleDebug` on push/PR, uploads
the debug APK + reports. `.github/workflows/release.yml` — on a `v*` tag, builds
`assembleRelease`, signs it (release keystore from `SIGNING_KEYSTORE_BASE64` &
friends if set, else an ephemeral key so the sideload APK is always installable),
and publishes a GitHub Release with the APK attached.

## Working with the target device (from DEVICE-STATE.md)

adb: USB via usbipd, or wifi at `192.168.1.33:5555` (wifi adb does **not** survive
reboot — re-enable over USB; prefer wifi for installs, usbip bulk transfers are
flaky). **Landscape screencap is 1920x1200 while `wm size` reports 1200x1920**, so
scripted taps miss — set `user_rotation 0` during GUI automation, restore `1`.
`stay_on_while_plugged_in=7` keeps the screen on when powered (OS-level). Don't
remove `com.google.android.overlay.gmsconfig.*` (idmap SIGABRT).

## Related locations

- `../bifrost` — the Bifrost hub. Voice API spec in its `PLAN.md` (M23/M24) +
  `API.md`; the dashboard is the WebView target.
- `../bifrost-skills/tablet-kiosk/` (SKILL.md) — device setup runbook.
- Claude memory `tablet-voice-satellite-arch` — the architecture decision record.
