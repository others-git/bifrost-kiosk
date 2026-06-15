#!/usr/bin/env bash
# Download a small English Vosk model into the app's assets so the voice
# satellite can do on-device wake-word + STT. The model is intentionally NOT
# committed (tens of MB); run this before building a voice-enabled APK.
#
# Without a model the app still builds and runs — the voice service just stays
# idle (see VoskSpeechEngine).
set -euo pipefail

# Override the model by exporting VOSK_MODEL_URL (any zip from
# https://alphacephei.com/vosk/models, or your own). See README "Using a
# different / custom Vosk model".
MODEL_URL="${VOSK_MODEL_URL:-https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/model-en-us"

if [ -d "$DEST" ] && [ -n "$(ls -A "$DEST" 2>/dev/null)" ]; then
    echo "Model already present at $DEST"
    exit 0
fi

tmp="$(mktemp -d)"
echo "Downloading $MODEL_URL ..."
wget -q --show-progress "$MODEL_URL" -O "$tmp/model.zip"
unzip -q "$tmp/model.zip" -d "$tmp"
mkdir -p "$DEST"
# Vosk zips contain a single top-level dir; flatten it into assets/model-en-us
# (works for custom VOSK_MODEL_URL zips too — we take the first extracted dir).
inner="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
cp -r "$inner"/* "$DEST/"
rm -rf "$tmp"

# Vosk's StorageService.unpack (used for the bundled-asset path) reads a `uuid`
# file in the model dir as a version marker to copy the model out of assets into
# internal storage; raw alphacephei models don't ship one, so without it unpack
# throws FileNotFoundException and voice stays dead. Generate a stable id per
# fetch (a different model → new id → the device re-syncs). The external "BYO"
# path loads via Model(path) directly and doesn't need this.
if [ ! -f "$DEST/uuid" ]; then
    (cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N) > "$DEST/uuid"
fi

echo "Installed Vosk model into $DEST"
echo "(the APK will grow by the model size — small en-us is ~40MB.)"
