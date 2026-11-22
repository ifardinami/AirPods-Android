#!/usr/bin/env sh
set -eu
gradle --no-daemon :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk AirPods.apk
printf '\nAPK: AirPods.apk\n'
