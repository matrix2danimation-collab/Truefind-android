#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs
# the native screens in test mode (made-up data, no login). Output: $2/*.png
# Also writes frame timings (gfxinfo) per screen. The emulator draws with a
# software GPU, so its timings are much slower than a real phone's.
APK="$1"; OUT="$2"
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null   # no plan / settings left over from the last screen
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity "$@" > /dev/null
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
  { echo "== $name"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile|Number "; } >> "$OUT/frames.txt"
}
shot 01-explore 10 --es tf_preview explore
shot 02-secret-free 12 --es tf_preview secret
shot 03-secret-premium 12 --es tf_preview secret --ez tf_premium true
shot 03b-premium-noblur 12 --es tf_preview secret --ez tf_premium true --ez tf_noblur true
shot 03c-premium-noweb 12 --es tf_preview secret --ez tf_premium true --ez tf_noweb true
shot 04-secret-scroll-mid 12 --es tf_preview secret --ez tf_premium true --es tf_open scrollmid
shot 05-secret-scroll-end 12 --es tf_preview secret --es tf_open scroll
shot 06-profile 12 --es tf_preview secret --ez tf_premium true --es tf_open profile
shot 07-profile-free 12 --es tf_preview secret --es tf_open profile
shot 08-request 12 --es tf_preview secret --ez tf_premium true --es tf_open request
shot 09-premium 12 --es tf_preview secret --es tf_open premium
shot 10-need 12 --es tf_preview secret --es tf_open need
shot 11-verify 12 --es tf_preview secret --ez tf_girl true --es tf_open verify
shot 12-story 12 --es tf_preview secret --ez tf_premium true --es tf_open story
shot 13-my-profile 12 --es tf_preview secret --ez tf_premium true --es tf_open my
shot 14-my-edit 12 --es tf_preview secret --es tf_open myedit
shot 15-add-story 12 --es tf_preview secret --ez tf_premium true --es tf_open addstory
shot 16-toast 9 --es tf_preview secret --ez tf_premium true --es tf_open toast
shot 17-girl-home 12 --es tf_preview secret --ez tf_girl true
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
