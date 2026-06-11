#!/usr/bin/env bash
# Fast verification: compile debug APK + run JVM unit tests. No emulator needed.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh

./gradlew :app:assembleDebug :app:testDebugUnitTest
