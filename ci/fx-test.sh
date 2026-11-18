#!/usr/bin/env bash
# The page moves test (PageFx): the app plays a list of taps by itself (Home ⇄ Explore, Profile, Wallet,
# Recent, Secret Dating and Back) with made-up data.
#   fx / fx2: each move stops 45 % of the way so it can be photographed ("fx-NN-mid.png"), and the page after
#             the move is photographed too ("fx-NN-end.png").
#   fx3:      the moves run freely ("live"): a picture of the page each one ends on ("fx-live-N.png"), and
#             the log says for each which pages are shown / kept ready and one TFFX line per move.
# fx-log.txt: everything the app wrote, saved as it happens (so it is there even if the test phone stops).
# The emulator draws with a software GPU (about 1 frame a second here), so this checks WHAT moves where;
# how smooth it is can only be seen on a real phone.
APK="$1"; OUT="$2"; PKG=app.truefind.android
# steps FROM..TO of the app's list ("fx": 1–6, "fx2": 7–11); LIVE: the free moves instead
FROM="${3:-1}"; TO="${4:-11}"; LIVE="${5:-}"
X="--es tf_preview fx --ei tf_fx_from $FROM --ei tf_fx_to $TO"
[ -n "$LIVE" ] && X="$X --ez tf_fx_live true"
mkdir -p "$OUT"
# every phone command has a time limit (a stopped test phone must never hold the test up)
ADB="$(command -v adb)"
adb() { timeout 120 "$ADB" "$@"; }
alive() { [ "$(timeout 10 "$ADB" get-state 2>/dev/null | tr -d '\r')" = device ]; }
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
# animations on, as on most phones (the emulator starts with them off)
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null
adb logcat -c
# what the app writes, as it happens
"$ADB" logcat -v time -s TFDBG:I TFFX:I AndroidRuntime:E > "$OUT/fx-stream.txt" 2>/dev/null &
STREAM=$!
# waits until the app has written [1] ([2] seconds at most)
waitlog() {
  local t=0
  while [ $t -lt $(( $2 * 2 )) ]; do
    grep -q "$1" "$OUT/fx-stream.txt" && return 0
    [ $(( t % 10 )) = 9 ] && ! alive && break
    sleep 0.5; t=$((t+1))
  done
  echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"; return 1
}
shot() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; echo "$(date +%T.%N | cut -c1-12) $1" >> "$OUT/shots.txt"; timeout 60 "$ADB" exec-out screencap -p > "$OUT/$1.png"; [ -s "$OUT/$1.png" ] || rm -f "$OUT/$1.png"; }
timeout 90 "$ADB" shell am start -W -n $PKG/.MainActivity $X > "$OUT/start.txt" 2>&1
# the slow emulator sometimes puts its home screen on top (and Android then pauses the app):
# every 4 s, if the app is not the screen on top, bring it back (the same app, the test goes on)
( while alive; do
    sleep 4
    if ! adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG"; then
      echo "$(date +%T) app not on top: brought back" >> "$OUT/start.txt"
      adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
      # (if Android closed the app — on this emulator it does when Google Play services stops — the test starts again)
      adb shell am start -n $PKG/.MainActivity $X > /dev/null 2>&1
    fi
  done ) &
KEEP=$!
trap 'kill $KEEP $STREAM 2>/dev/null' EXIT
waitlog "fx step start" 90
sleep 3; shot fx-00-start
if [ -n "$LIVE" ]; then
  for k in 1 2 3 4 5 6; do
    alive || { echo "the test phone stopped before free move $k" >> "$OUT/problems.txt"; break; }
    if waitlog "fx step live done $k:" 60; then sleep 2; shot fx-live-$k; fi
  done
else
  for n in $(seq $FROM $TO); do
    N=$(printf %02d $n)
    # the test phone stopped: the rest can't be photographed (no endless waiting)
    alive || { echo "the test phone stopped before step $n" >> "$OUT/problems.txt"; break; }
    if waitlog "fx step frozen $n\r\?\$" 90; then sleep 1; shot fx-$N-mid; fi
    if waitlog "fx step done $n\r\?\$" 90; then sleep 2; shot fx-$N-end; fi
  done
fi
sleep 1
kill $STREAM 2>/dev/null
grep -v "frames so far" "$OUT/fx-stream.txt" > "$OUT/fx-log.txt"; rm -f "$OUT/fx-stream.txt"
timeout 30 "$ADB" logcat -d -b crash > "$OUT/crash-log.txt" 2>/dev/null
grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt" "$OUT/fx-log.txt" 2>/dev/null && { grep -h -A30 "FATAL EXCEPTION" "$OUT/crash-log.txt" "$OUT/fx-log.txt" > "$OUT/fx.crash.txt"; }
ls -la "$OUT"
