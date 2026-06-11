#!/usr/bin/env bash
# Boot the SignalBerry AVD and wait until Android has fully started.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh

AVD="${1:-signalberry}"

if adb get-state >/dev/null 2>&1; then
    echo "Emulator/device already connected."
else
    nohup emulator "@$AVD" -gpu auto -no-snapshot-save >/tmp/emulator.log 2>&1 &
    echo "Booting @$AVD ..."
fi

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done
echo "Boot completed."
