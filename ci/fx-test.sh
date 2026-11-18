#!/usr/bin/env bash
# The page moves test (PageFx): the app plays a list of taps by itself (Home ⇄ Explore, Profile, Wallet,
# Recent, Secret Dating and Back) with made-up data. Each move stops part of the way (45 %) so it can be
# photographed ("fx-NN-mid.png"), and the page after the move is photographed too ("fx-NN-end.png").
# fx-log.txt: each step. The emulator draws with a software GPU (about 1 frame a second here), so this
# checks WHAT moves where; how smooth it is can only be seen on a real phone.
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
# animations on, as on most phones (the emulator starts with them off)
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null
adb logcat -c
waitlog() { local t=0; while [ $t -lt "$2" ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done; echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"; return 1; }
adb shell am start -W -n $PKG/.MainActivity --es tf_preview fx > "$OUT/start.txt" 2>&1
waitlog "fx step start" 90
sleep 3; adb exec-out screencap -p > "$OUT/fx-00-start.png"
for n in 1 2 3 4 5 6 7 8 9 10 11; do
  N=$(printf %02d $n)
  if waitlog "fx step frozen $n\$" 120; then sleep 2; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; adb exec-out screencap -p > "$OUT/fx-$N-mid.png"; fi
  if waitlog "fx step done $n\$" 120; then sleep 3; adb exec-out screencap -p > "$OUT/fx-$N-end.png"; fi
done
adb logcat -d -s TFDBG:I TFFX:I AndroidRuntime:E | grep -v "frames so far" > "$OUT/fx-log.txt"
adb logcat -d -b crash > "$OUT/crash-log.txt"
grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt" && cp "$OUT/crash-log.txt" "$OUT/fx.crash.txt"
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
