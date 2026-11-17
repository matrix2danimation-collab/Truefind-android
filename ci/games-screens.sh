#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs the native games in
# test mode (made-up answers, no login). Output: $2/*.png (+ crash logs if any)
APK="$1"; OUT="$2"
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell pm grant app.truefind.android android.permission.RECORD_AUDIO || true
shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop app.truefind.android
  adb logcat -c
  adb shell am start -W -n app.truefind.android/.MainActivity "$@" > /dev/null
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
}
shot 01-hub 7 --es tf_games hub
shot 02-hub-clue-sheet 8 --es tf_games hub --es tf_open clue-sheet
shot 03-hc-intro-boy 7 --es tf_games hc-intro
shot 04-hc-intro-girl 7 --es tf_games hc-intro --ez tf_girl true
shot 05-hc-secret 7 --es tf_games hc-secret
shot 06-hc-play 7 --es tf_games hc-play
shot 07-hc-over 7 --es tf_games hc-over
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
