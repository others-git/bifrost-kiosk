<h1 align="center">Bifrost Kiosk</h1>

<p align="center">
  The wall-tablet companion app for <a href="https://github.com/others-git/bifrost">Bifrost</a> — a locked, always-on home-control fixture with hands-free voice.
</p>

---

Bifrost Kiosk turns a spare Android tablet into a dedicated, wall-mounted control surface for your [Bifrost](https://github.com/others-git/bifrost) smart-home hub. It does two jobs in one app: a hard-locked full-screen dashboard, and an always-listening offline voice assistant.

## What it does

- **Locked single-purpose display.** Boots straight into a full-screen Bifrost dashboard and stays there — no status bar, no recents, no home button, no way to wander off into other apps. It relaunches itself on power-up, so a tablet on the wall is always showing your home.
- **Hands-free voice.** Say the wake word and speak a command; the tablet recognizes speech **on-device and fully offline**, sends the command to Bifrost, and speaks the reply back. No cloud microphone, no streaming your audio anywhere. A live overlay shows when it's listening and what it heard.
- **Pair by scanning a QR code.** Point the tablet's camera at the pairing code shown in Bifrost — it enrolls itself and is authorized in seconds. No keys to type on a touchscreen keyboard.
- **Remotely managed.** Each paired tablet checks in with Bifrost, so you can see it, put it to sleep, lock it, or revoke its access from the hub — handy for a fixture you don't physically touch.
- **Resilient.** If the hub is briefly unreachable it shows a clean branded screen and reconnects on its own, rather than a broken page.

## Getting it

Grab the latest APK from the [Releases](../../releases) page and install it on the tablet. Open it, then scan the pairing QR code from your Bifrost dashboard to connect. On-device voice works out of the box.

That's all most setups need. Turning the tablet into a fully **locked** kiosk (so it can't be exited) is an optional extra step for a permanent wall installation.

## How it fits with Bifrost

The tablet is just another Bifrost client: it shows the same dashboard you'd see in a browser and drives the same voice commands. Everything it can do is backed by Bifrost's APIs — see the [main project](https://github.com/others-git/bifrost) for the hub, providers, rooms, scenes, and the voice pipeline.

## License

MIT — same as [Bifrost](https://github.com/others-git/bifrost).
