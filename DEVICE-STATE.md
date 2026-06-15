# Tablet current state (as of 2026-06-14)

Snapshot of the reference tablet after this session, so the companion-app build
knows its starting point. Full setup history: `../bifrost-skills/tablet-kiosk/`.

## Device
- Galaxy Tab A9+ 5G, **SM-X218U**, Android 15 (One UI), serial `R9TXA02P2DK`.
- Bootloader locked (no root / no custom ROM). CSC `XAU`.
- adb serial (R9TXA02P2DK) over USB via usbipd; also adb-over-wifi at
  `192.168.1.33:5555` (does NOT survive reboot — re-enable via USB).
- `verifier_verify_adb_installs=0`, `package_verifier_enable=0` (sideloads are
  non-interactive — user-authorized).
- `stay_on_while_plugged_in=7` (AC|USB|Wireless) → screen stays on whenever
  powered; the 30-min `screen_off_timeout` only applies on battery. The companion
  app can also enforce `FLAG_KEEP_SCREEN_ON`, but this OS setting is app-agnostic.

## What's installed / configured
- **WallPanel** (`xyz.wallpanel.app`) — display kiosk. Dashboard URL
  `https://bifrost.theundead.live`, Fullscreen ON, Start-on-Boot ON. Set as the
  default **Home launcher** (`.ui.activities.BrowserActivityNative`). = soft kiosk.
  Camera motion-wake ON: front camera (id 1), Motion Detection + Wakes Screen,
  Dim Screen Saver after 30s idle. Granted CAMERA + WRITE_SETTINGS appop.
  Settings button hidden via **Settings Transparent** (invisible but still
  clickable in bottom-right). Reopen settings: tap bottom-right corner, or
  `adb shell am start -n xyz.wallpanel.app/.ui.activities.SettingsActivity`.
- **TestDPC** (`com.afwsamples.testdpc`) — currently the **device owner**
  (`.DeviceAdminReceiver`). No restriction policies applied yet. Must be cleared
  before the companion app can become device owner (see README cutover).
- Fully Kiosk: **removed**.
- Debloated 371 → ~307 pkgs (Samsung + Google junk, Play Store, DeX, Chrome,
  Gallery, AR apps, T-Mobile ironSource Aura adware). Reversible
  (`pm install-existing`). Keyboard/overlay fix: do NOT remove
  `com.google.android.overlay.gmsconfig.*` (idmap SIGABRT — already learned).
- Notifications muted: DND on (`zen_mode=1`), heads-up/LED/lockscreen-notif/UI-
  sounds off, STREAM_NOTIFICATION+RING=0. STREAM_MUSIC left ON for future TTS.

## Known constraints learned this session
- WallPanel has **no device-admin** → can't be `dpm set-device-owner`'d and never
  calls `startLockTask` → can't be hard-pinned. This is why the companion app
  must host the WebView itself.
- Orientation locked **landscape** (`user_rotation 1`, `accelerometer_rotation
  0`) for the wall mount. NOTE for future adb GUI automation: in landscape the
  screencap is 1920x1200 while `wm size` reports 1200x1920, so taps land wrong —
  temporarily `user_rotation 0` (portrait) during automation, then restore 1.
- Bulk adb transfers over usbip are flaky → install/push over wifi adb.
