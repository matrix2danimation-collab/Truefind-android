#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs the native games in
# test mode (made-up answers, no login). Output: $2/*.png (+ crash logs if any)
APK="$1"; OUT="$2"
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell pm grant app.truefind.android android.permission.RECORD_AUDIO || true
# the slow CI emulator's own "System UI isn't responding" box would cover the pictures: don't show it
adb shell settings put global hide_error_dialogs 1 || true
on_top() { adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "app.truefind.android"; }
shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop app.truefind.android
  adb logcat -c
  adb shell am start -W -n app.truefind.android/.MainActivity "$@" > /dev/null
  sleep "$wait"
  # if the app isn't in front (emulator hiccup), start it once more
  if ! on_top; then adb shell am start -W -n app.truefind.android/.MainActivity "$@" > /dev/null; sleep "$wait"; fi
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
}
# (the emulator draws slowly — first frames can take 5–9 s — so each picture waits 10 s)
shot 01-hub 10 --es tf_games hub
shot 02-hub-clue-sheet 11 --es tf_games hub --es tf_open clue-sheet
shot 03-hc-intro-boy 10 --es tf_games hc-intro
shot 04-hc-intro-girl 10 --es tf_games hc-intro --ez tf_girl true
shot 05-hc-secret 10 --es tf_games hc-secret
shot 06-hc-play 10 --es tf_games hc-play
shot 07-hc-over 12 --es tf_games hc-over
shot 08-cm-local 11 --es tf_games hub --es tf_open clue-local
shot 09-cm-online 11 --es tf_games hub --es tf_open clue-online
shot 10-cm-page 10 --es tf_games cm
shot 11-mc-intro 10 --es tf_games mc-intro
shot 12-mc-wait-girl 10 --es tf_games mc-wait --ez tf_girl true
shot 13-mc-matched-girl 10 --es tf_games mc-matched --ez tf_girl true
shot 14-mc-detail 10 --es tf_games mc-detail
shot 15-mc-reveal 7 --es tf_games mc-reveal
shot 16-mc-call 11 --es tf_games mc-call
shot 17-mc-call-girl 11 --es tf_games mc-call-girl --ez tf_girl true
shot 18-mc-final 10 --es tf_games mc-final
shot 19-mc-summary 10 --es tf_games mc-summary
shot 20-dc-home 11 --es tf_games dc-home --es tf_open dress-code
shot 21-dc-practice 12 --es tf_games dc-practice --es tf_open dress-code
shot 22-dc-wardrobe 13 --es tf_games dc-wardrobe --es tf_open dress-code
shot 23-dc-sheet 12 --es tf_games dc-sheet --es tf_open dress-code
shot 24-dc-online-boy 12 --es tf_games dc-online --es tf_open dress-code
shot 25-dc-online-girl 12 --es tf_games dc-online --ez tf_girl true --es tf_open dress-code
shot 26-dc-reveal 12 --es tf_games dc-reveal --es tf_open dress-code
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
