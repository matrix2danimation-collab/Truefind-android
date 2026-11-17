#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs
# the native screens in test mode (made-up data, no login). Output: $2/*.png
APK="$1"; OUT="$2"
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop app.truefind.android
  adb logcat -c
  adb shell am start -W -n app.truefind.android/.MainActivity "$@" > /dev/null
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  adb logcat -d -s TFDBG:I >> "$OUT/debug.txt"
}
shot 01-explore 8 --es tf_preview explore
shot 02-secret-free 8 --es tf_preview secret
shot 03-secret-premium 8 --es tf_preview secret --ez tf_premium true
shot 04-secret-scroll-mid 9 --es tf_preview secret --ez tf_premium true --es tf_open scrollmid
shot 05-secret-scroll-end 9 --es tf_preview secret --es tf_open scroll
shot 06-profile 9 --es tf_preview secret --ez tf_premium true --es tf_open profile
shot 07-profile-free 9 --es tf_preview secret --es tf_open profile
shot 08-request 9 --es tf_preview secret --ez tf_premium true --es tf_open request
shot 09-premium 9 --es tf_preview secret --es tf_open premium
shot 10-need 9 --es tf_preview secret --es tf_open need
shot 11-verify 9 --es tf_preview secret --ez tf_girl true --es tf_open verify
shot 12-story 10 --es tf_preview secret --ez tf_premium true --es tf_open story
shot 13-my-profile 9 --es tf_preview secret --ez tf_premium true --es tf_open my
shot 14-my-edit 9 --es tf_preview secret --es tf_open myedit
shot 15-add-story 9 --es tf_preview secret --ez tf_premium true --es tf_open addstory
shot 16-toast 6 --es tf_preview secret --ez tf_premium true --es tf_open toast
shot 17-girl-home 8 --es tf_preview secret --ez tf_girl true
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
