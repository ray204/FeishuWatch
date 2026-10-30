#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# This script must never resize or test a user's physical phone/watch.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || { echo 'Only run on a single emulator' >&2; exit 1; }
adb shell wm size 372x430
adb shell wm density 240
# Retain app data until screenshot export. run-as reads the debug app's private
# file without relying on Android 11's restricted external-storage shell access.
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
mkdir -p app/build/watch-screenshots
adb exec-out run-as io.github.ray204.feishuwatch cat files/screenshots/dashboard.png > app/build/watch-screenshots/dashboard.png
test -s app/build/watch-screenshots/dashboard.png
