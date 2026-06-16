#!/usr/bin/env bash
#
# Fetch the whisper.cpp upstream source the native build compiles against.
#
# The source (~hundreds of files) is NOT vendored into git — only our
# app/src/main/cpp/CMakeLists.txt and whisper_jni.c are. This pins the upstream
# checkout so the build is reproducible (CI runs this before assembling).
# Mirrors scripts/fetch-vosk-model.sh: idempotent, skips an existing checkout.
set -euo pipefail

TAG="${WHISPER_CPP_TAG:-v1.7.4}"
DEST="app/src/main/cpp/whisper.cpp"

cd "$(dirname "$0")/.."

if [ -f "$DEST/include/whisper.h" ]; then
    echo "whisper.cpp already present at $DEST (skipping). Remove it to re-fetch."
    exit 0
fi

echo "Cloning whisper.cpp $TAG into $DEST ..."
rm -rf "$DEST"
git clone --depth 1 -b "$TAG" https://github.com/ggerganov/whisper.cpp "$DEST"
# Drop the nested .git so it isn't an embedded repo / accidental submodule.
rm -rf "$DEST/.git"
echo "Done. ($DEST is gitignored; the build compiles its src/ and ggml/ directly.)"
