#!/usr/bin/env bash
# Build debug APK, install on the connected emulator/device, launch the app.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh

PACKAGE="com.example.signalberry"
ACTIVITY=".ServerConnect"

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n "$PACKAGE/$ACTIVITY"
echo "Launched $PACKAGE"
