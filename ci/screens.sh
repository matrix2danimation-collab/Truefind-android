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
# 720 x 1600 screen at 280 dpi = 411 dp wide, the same layout as the Pixel 6 (1080 x 2400, 420 dpi), with fewer pixels to draw
adb shell wm size 720x1600
adb shell wm density 280
sleep 3

# wait until a line with $1 is in the app's test log (max $2 s)
waitlog() {
  local t=0
  while [ $t -lt "$2" ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done
  echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"
}
# wait until the app has drawn everything it is going to: its frame count stays the same for 6 s (max $1 s)
settle() {
  local max=$1 t=0 same=0 lastN="" lastL=0 n l
  while [ $t -lt "$max" ]; do
    sleep 2; t=$((t+2))
    l=$(adb logcat -d -s TFDBG:I | grep -c "frames so far")
    n=$(adb logcat -d -s TFDBG:I | grep -o "frames so far [0-9]*" | tail -1)
    if [ "$l" -gt "$lastL" ] && [ -n "$n" ] && [ "$n" = "$lastN" ]; then same=$((same+1)); [ $same -ge 3 ] && return 0; else same=0; fi
    lastN="$n"; lastL=$l
  done
  echo "  (still drawing after $max s)" >> "$OUT/start.txt"
}
shot() {
  local name="$1"; local mode="$2"; shift 2
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null   # no plan / settings left over from the last screen
  sleep 1
  adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  adb shell am start -W -n $PKG/.MainActivity "$@" >> "$OUT/start.txt" 2>&1
  case "$mode" in
    open)  waitlog "preview open" 45; settle 60 ;;
    quick) waitlog "preview open" 45; sleep 2 ;;
    *)     sleep 4; settle 60 ;;
  esac
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
  { echo "== $name"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile|Number "; } >> "$OUT/frames.txt"
}
shot 01-explore home --es tf_preview explore
shot 02-secret-free home --es tf_preview secret
shot 03-secret-premium home --es tf_preview secret --ez tf_premium true
shot 04-secret-scroll-mid open --es tf_preview secret --ez tf_premium true --es tf_open scrollmid
shot 05-secret-scroll-end open --es tf_preview secret --es tf_open scroll
shot 06-profile open --es tf_preview secret --ez tf_premium true --es tf_open profile
shot 07-profile-free open --es tf_preview secret --es tf_open profile
shot 08-request open --es tf_preview secret --ez tf_premium true --es tf_open request
shot 09-premium open --es tf_preview secret --es tf_open premium
shot 10-need open --es tf_preview secret --es tf_open need
shot 11-verify open --es tf_preview secret --ez tf_girl true --es tf_open verify
shot 12-story quick --es tf_preview secret --ez tf_premium true --es tf_open story
shot 13-my-profile open --es tf_preview secret --ez tf_premium true --es tf_open my
shot 14-my-edit open --es tf_preview secret --es tf_open myedit
shot 15-add-story open --es tf_preview secret --ez tf_premium true --es tf_open addstory
shot 16-toast quick --es tf_preview secret --ez tf_premium true --es tf_open toast
shot 17-girl-home home --es tf_preview secret --ez tf_girl true
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
