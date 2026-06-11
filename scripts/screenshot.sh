#!/usr/bin/env bash
# Capture the emulator screen to artifacts/screenshots/<name>.png (default: current.png)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh

NAME="${1:-current}"
mkdir -p artifacts/screenshots
# exec-out needs API 21+; fall back to shell screencap + pull for old images (bb-q10 / API 18)
if ! adb exec-out screencap -p > "artifacts/screenshots/$NAME.png" 2>/dev/null \
   || [ ! -s "artifacts/screenshots/$NAME.png" ]; then
    adb shell screencap -p /data/local/tmp/_screen.png
    adb pull /data/local/tmp/_screen.png "artifacts/screenshots/$NAME.png" >/dev/null
    adb shell rm /data/local/tmp/_screen.png
fi
echo "Saved artifacts/screenshots/$NAME.png"
