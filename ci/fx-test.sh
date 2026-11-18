#!/usr/bin/env bash
# The page moves test (PageFx): the app plays a list of taps by itself (Home ⇄ Explore, Profile, Wallet,
# Recent, Secret Dating and Back) with made-up data. Each move stops part of the way (45 %) so it can be
# photographed ("fx-NN-mid.png"), and the page after the move is photographed too ("fx-NN-end.png").
# fx-log.txt: each step. The emulator draws with a software GPU (about 1 frame a second here), so this
# checks WHAT moves where; how smooth it is can only be seen on a real phone.
APK="$1"; OUT="$2"; PKG=app.truefind.android
# steps FROM..TO of the app's list ("fx": 1–6, "fx2": 7–11)
FROM="${3:-1}"; TO="${4:-11}"; X="--es tf_preview fx --ei tf_fx_from $FROM --ei tf_fx_to $TO"
mkdir -p "$OUT"
# every phone command has a time limit (a stopped test phone must never hold the test up)
ADB="$(command -v adb)"
adb() { timeout 120 "$ADB" "$@"; }
timeout 240 adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
# animations on, as on most phones (the emulator starts with them off)
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null
adb logcat -c
waitlog() { local t=0; while [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] && [ $t -lt "$2" ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done; echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"; return 1; }
timeout 90 adb shell am start -W -n $PKG/.MainActivity $X > "$OUT/start.txt" 2>&1
# the slow emulator sometimes puts its home screen on top (and Android then pauses the app):
# every 4 s, if the app is not the screen on top, bring it back (the same app, the test goes on)
( while [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ]; do
    sleep 4
    if ! adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG"; then
      echo "$(date +%T) app not on top: brought back" >> "$OUT/start.txt"
      adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
      # (if Android closed the app — on this emulator it does when Google Play services stops — the test starts again)
      adb shell am start -n $PKG/.MainActivity $X > /dev/null 2>&1
    fi
  done ) &
KEEP=$!
trap 'kill $KEEP 2>/dev/null' EXIT
waitlog "fx step start" 90
sleep 3; timeout 60 adb exec-out screencap -p > "$OUT/fx-00-start.png"
for n in $(seq $FROM $TO); do
  # the test phone stopped: the rest can't be photographed (no endless waiting)
  [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] || { echo "the test phone stopped before step $n" >> "$OUT/problems.txt"; break; }
  N=$(printf %02d $n)
  if waitlog "fx step frozen $n\$" 90; then sleep 1; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; echo "$(date +%T.%N | cut -c1-12) fx-$N-mid" >> "$OUT/shots.txt"; timeout 60 adb exec-out screencap -p > "$OUT/fx-$N-mid.png"; fi
  if waitlog "fx step done $n\$" 90; then sleep 2; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; echo "$(date +%T.%N | cut -c1-12) fx-$N-end" >> "$OUT/shots.txt"; timeout 60 adb exec-out screencap -p > "$OUT/fx-$N-end.png"; fi
done
# second half: the same moves running freely; a picture of the page each one ends on
if [ "$TO" -ge 11 ]; then
  for k in 1 2 3 4 5 6; do
    [ "$(timeout 10 "$ADB" get-state 2>/dev/null | tr -d '\r')" = device ] || { echo "the test phone stopped before free move $k" >> "$OUT/problems.txt"; break; }
    if waitlog "fx step live done $k:" 60; then sleep 1; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; timeout 60 "$ADB" exec-out screencap -p > "$OUT/fx-live-$k.png"; fi
  done
fi
timeout 30 adb logcat -d -s TFDBG:I TFFX:I AndroidRuntime:E | grep -v "frames so far" > "$OUT/fx-log.txt"
timeout 30 adb logcat -d -b crash > "$OUT/crash-log.txt"
grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt" && cp "$OUT/crash-log.txt" "$OUT/fx.crash.txt"
timeout 30 adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
