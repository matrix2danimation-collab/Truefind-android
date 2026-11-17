#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs
# the native Random Match screens in test mode (made-up data, no login).
# Output: $2/*.png (+ *.crash.txt when the app crashed on that screen)
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
}
R="--es tf_preview random --es tf_open"
shot r01-sheet-voice 9 $R sheet
shot r02-sheet-video 8 $R sheet_video
shot r03-sheet-low-balance 8 $R sheet_low
shot r04-search 8 $R search
shot r05-search-own-photo 8 $R search_face
shot r06-search-video 8 $R search_video
shot r07-calling-no-photo 8 $R calling
shot r08-calling-own-photo 8 $R calling_face
shot r09-no-one-free 8 $R nofree
shot r10-couldnt-connect 8 $R couldnt
shot r11-add-coins 8 $R coins
# tap Find someone on the sheet: search → calling → "No one is free" (test mode, nobody is called)
adb shell am force-stop app.truefind.android
adb shell am start -W -n app.truefind.android/.MainActivity $R sheet > /dev/null
sleep 8
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/r12-ui.xml" > /dev/null 2>&1
B=$(grep -o 'text="Find someone"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/r12-ui.xml" | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
if [ -n "$B" ]; then
  X=$(echo "$B" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \3/' | awk '{print int(($1+$2)/2)}')
  Y=$(echo "$B" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2 \4/' | awk '{print int(($1+$2)/2)}')
  adb shell input tap "$X" "$Y"; sleep 1
  adb exec-out screencap -p > "$OUT/r12-tap-find-search.png"; sleep 2
  adb exec-out screencap -p > "$OUT/r13-tap-find-calling.png"; sleep 2.5
  adb exec-out screencap -p > "$OUT/r14-tap-find-no-one-free.png"
  adb shell input keyevent 4; sleep 1
  adb exec-out screencap -p > "$OUT/r15-back-closes-dialog.png"
else
  echo "Find someone button not found" > "$OUT/r12-NOT-FOUND.txt"
fi
adb logcat -d -s AndroidRuntime:E > "$OUT/r12-crash.txt"; [ -s "$OUT/r12-crash.txt" ] || rm -f "$OUT/r12-crash.txt"
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
