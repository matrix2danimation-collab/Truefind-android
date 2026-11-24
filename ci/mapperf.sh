#!/usr/bin/env bash
# Secret Dating · Online Map smoothness on the test phone (made-up people, no server, no sign-in): how long each
# frame takes while a finger drags the map, at four zooms (all of India · the north · Uttar Pradesh · a city), and
# while it sits still. The emulator draws with a software GPU, so its times are far slower than a phone's: they
# are for comparing two versions of the app on the same kind of machine, not for promising a phone's numbers.
# Output: mapperf.txt (the numbers), <scene>.png (what the map looked like), <scene>-*.txt (raw frame data).
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"
ADB="$(command -v adb)"
adb() { timeout 90 "$ADB" "$@"; }
HERE="$(cd "$(dirname "$0")" && pwd)"
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
# the emulator's own home screen app keeps freezing ("Pixel Launcher isn't responding") and its box then takes the
# finger: it is switched off for this test (the test never goes to the home screen)
for L in com.google.android.apps.nexuslauncher com.android.launcher3; do adb shell pm disable-user --user 0 $L > /dev/null 2>&1; done
read -r W H <<< "$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"; W=${W:-1080}; H=${H:-2400}
# like a normal phone: animations on (the emulator starts with them off, which the map reads as "reduce motion") and
# the full look (tf_lite 0), not the light one for phones with little memory / few cores
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
LOOK="${MAPPERF_LOOK:-0}"
waitlog() { local end=$(( $(date +%s) + $2 )); while [ "$(date +%s)" -lt $end ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; done; return 1; }
front() { adb shell dumpsys window | grep -E "mCurrentFocus" | head -1 | sed -E 's/^ +//'; }

# scene <name> <tf_open>: the full-screen map at a zoom, 10 drags across it, then 6 s without touching it
scene() {
  adb shell am force-stop $PKG; sleep 1; adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --es tf_preview secret-map --ez tf_premium true --es tf_open "$2" --es tf_lite "$LOOK" >> "$OUT/start.txt" 2>&1
  waitlog "preview open" 45 || echo "($1: no 'preview open')" >> "$OUT/start.txt"
  sleep 9   # the first flight to the place ends
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 1
  echo "$1 before: $(front)" >> "$OUT/focus.txt"
  timeout 40 "$ADB" exec-out screencap -p > "$OUT/$1-start.png"   # the map at rest before any finger (to compare two versions' look)
  adb shell dumpsys gfxinfo $PKG reset > /dev/null
  : > "$OUT/$1-drag.txt"
  for i in 1 2 3 4 5; do
    adb shell input swipe $((W * 25 / 100)) $((H * 55 / 100)) $((W * 70 / 100)) $((H * 50 / 100)) 650
    adb shell dumpsys gfxinfo $PKG framestats >> "$OUT/$1-drag.txt"      # (it keeps the last 120 frames: read after every drag)
    adb shell input swipe $((W * 70 / 100)) $((H * 45 / 100)) $((W * 30 / 100)) $((H * 52 / 100)) 650
    adb shell dumpsys gfxinfo $PKG framestats >> "$OUT/$1-drag.txt"
  done
  echo "$1 after: $(front)" >> "$OUT/focus.txt"
  timeout 40 "$ADB" exec-out screencap -p > "$OUT/$1.png"
  sleep 2
  adb shell dumpsys gfxinfo $PKG reset > /dev/null
  sleep 6
  adb shell dumpsys gfxinfo $PKG framestats > "$OUT/$1-still.txt"
  python3 "$HERE/fstats.py" "$1-drag" "$OUT/$1-drag.txt" "$1-still" "$OUT/$1-still.txt" >> "$OUT/mapperf.txt"
}

# the sky with its stars still shows while the Earth is small: all of India, then the zoom-out button three times
adb shell am force-stop $PKG; sleep 1; adb logcat -c
adb shell am start -n $PKG/.MainActivity --es tf_preview secret-map --ez tf_premium true --es tf_open full-india --es tf_lite "$LOOK" > /dev/null 2>&1
waitlog "preview open" 45; sleep 9
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 1
for i in 1 2 3; do adb shell input tap $((W * 915 / 1000)) $((H * 550 / 1000)); sleep 2; done
sleep 3
timeout 40 "$ADB" exec-out screencap -p > "$OUT/sky.png"

echo "scene          (times per frame on the emulator, ms; lower is better · finger = frames while a finger drags the map)" > "$OUT/mapperf.txt"
scene india full-india
scene north full-north
scene up full-up
scene city full-close
adb logcat -d -s AndroidRuntime:E | grep -A30 "Process: $PKG" > "$OUT/crash.txt"; [ -s "$OUT/crash.txt" ] || rm -f "$OUT/crash.txt"
for L in com.google.android.apps.nexuslauncher com.android.launcher3; do adb shell pm enable --user 0 $L > /dev/null 2>&1; done
cat "$OUT/mapperf.txt" "$OUT/focus.txt"
ls -la "$OUT"
