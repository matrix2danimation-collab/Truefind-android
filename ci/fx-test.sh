#!/usr/bin/env bash
# The page moves test (PageFx): the app plays a list of taps by itself (Home ⇄ Explore, Profile, Wallet,
# Recent, Secret Dating and Back) with made-up data, while the emulator records the screen.
# Output: $2/fx.mp4 (the recording), $2/fx-log.txt (each step and each move: how long, how many frames),
# and a picture every 3 s. The emulator draws with a software GPU: it is much slower than a phone, so the
# recording shows WHAT moves and where, not how smooth it is on a real phone.
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
# animations on, as on most phones (the emulator starts with them off)
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null
adb logcat -c
adb shell rm -f /sdcard/fx.mp4
# the recording (max 3 min on Android; this test takes ~40 s)
adb shell screenrecord --size 540x1200 --bit-rate 6000000 --time-limit 44 /sdcard/fx.mp4 &
REC=$!
sleep 1
adb shell am start -W -n $PKG/.MainActivity --es tf_preview fx > "$OUT/start.txt" 2>&1
for i in 3 6 9 12 15 18 21 24 27 30 33 36 39; do
  sleep 3
  adb exec-out screencap -p > "$OUT/fx-$(printf %02d $i)s.png" 2>/dev/null
done
wait $REC
sleep 2
adb pull /sdcard/fx.mp4 "$OUT/fx.mp4" > /dev/null 2>&1 || echo "no recording" >> "$OUT/start.txt"
adb logcat -d -s TFDBG:I TFFX:I AndroidRuntime:E > "$OUT/fx-log.txt"
adb logcat -d -b crash > "$OUT/crash-log.txt"
grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt" && cp "$OUT/crash-log.txt" "$OUT/fx.crash.txt"
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
