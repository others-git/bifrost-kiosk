#!/usr/bin/env bash
# Download a small English Vosk model into the app's assets so the voice
# satellite can do on-device wake-word + STT. The model is intentionally NOT
# committed (tens of MB); run this before building a voice-enabled APK.
#
# Without a model the app still builds and runs — the voice service just stays
# idle (see VoskSpeechEngine).
set -euo pipefail

MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
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
# The zip contains a single top-level dir; flatten it into assets/model-en-us.
inner="$(find "$tmp" -maxdepth 1 -type d -name 'vosk-model-*' | head -1)"
cp -r "$inner"/* "$DEST/"
rm -rf "$tmp"
echo "Installed Vosk model into $DEST"
echo "(~40MB; the APK will grow accordingly.)"
