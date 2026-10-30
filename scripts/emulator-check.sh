#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# This script must never resize or test a user's physical phone/watch.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || { echo 'Only run on a single emulator' >&2; exit 1; }
trap 'adb pull /sdcard/Android/data/io.github.ray204.feishuwatch/files/screenshots app/build/watch-screenshots || true' EXIT
adb shell wm size 372x430
adb shell wm density 240
# Retain app data until the EXIT trap exports the screenshot. The emulator itself
# is disposable; AGP's default uninstall otherwise removes external app files.
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
