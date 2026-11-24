#!/usr/bin/env bash
# What each layer of the Secret Dating map costs to draw while a finger drags it: the same drags with one group of
# layers left out at a time (test switch tf_mapoff; the app itself always draws them all). Emulator numbers: only
# for comparing the rows with each other. Output: mapcost.txt.
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"
ADB="$(command -v adb)"
adb() { timeout 90 "$ADB" "$@"; }
HERE="$(cd "$(dirname "$0")" && pwd)"
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
for L in com.google.android.apps.nexuslauncher com.android.launcher3; do adb shell pm disable-user --user 0 $L > /dev/null 2>&1; done
read -r W H <<< "$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"; W=${W:-1080}; H=${H:-2400}
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
waitlog() { local end=$(( $(date +%s) + $2 )); while [ "$(date +%s)" -lt $end ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; done; return 1; }

# run <scene> <tf_open> <layers off (comma list, "-" for none)>
run() {
  local name="$1-${3//,/+}"
  adb shell am force-stop $PKG; sleep 1; adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --es tf_preview secret-map --ez tf_premium true --es tf_open "$2" --es tf_lite 0 --es tf_mapoff "${3#-}" > /dev/null 2>&1
  waitlog "preview open" 45
  sleep 9
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 1
  adb shell dumpsys gfxinfo $PKG reset > /dev/null
  : > "$OUT/$name.txt"
  for i in 1 2 3; do
    adb shell input swipe $((W * 25 / 100)) $((H * 55 / 100)) $((W * 70 / 100)) $((H * 50 / 100)) 650
    adb shell dumpsys gfxinfo $PKG framestats >> "$OUT/$name.txt"
    adb shell input swipe $((W * 70 / 100)) $((H * 45 / 100)) $((W * 30 / 100)) $((H * 52 / 100)) 650
    adb shell dumpsys gfxinfo $PKG framestats >> "$OUT/$name.txt"
  done
  python3 "$HERE/fstats.py" "$name" "$OUT/$name.txt" | grep finger >> "$OUT/mapcost.txt"
}

# the names' sizes worked out without their pictures must be exactly the pictures' sizes (test switch tf_mapcheck)
for sc in full-up full-india full-close; do
  adb shell am force-stop $PKG; sleep 1; adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --es tf_preview secret-map --ez tf_premium true --es tf_open $sc --es tf_lite 0 --ez tf_mapcheck true > /dev/null 2>&1
  waitlog "preview open" 45; sleep 8
  for i in 1 2; do adb shell input swipe $((W * 25 / 100)) $((H * 55 / 100)) $((W * 70 / 100)) $((H * 50 / 100)) 650; adb shell input swipe $((W * 70 / 100)) $((H * 45 / 100)) $((W * 30 / 100)) $((H * 52 / 100)) 650; done
  sleep 2
  echo "$sc: $(adb logcat -d -s TFDBG:I | grep -c 'namebox differs') names differ; $(adb logcat -d -s TFDBG:I | grep 'namebox checked' | tail -1 | sed -E 's/.*namebox //')" >> "$OUT/namecheck.txt"
  adb logcat -d -s TFDBG:I | grep 'namebox differs' | head -5 >> "$OUT/namecheck.txt"
done

echo "layers left out (emulator ms per frame while dragging; compare the rows)" > "$OUT/mapcost.txt"
for sc in "india full-india" "up full-up"; do
  set -- $sc
  for off in - india world districts,states lines graticule,mesh,lights graticule,world,india,lights,lines,districts,states,mesh; do run "$1" "$2" "$off"; done
done
for L in com.google.android.apps.nexuslauncher com.android.launcher3; do adb shell pm enable --user 0 $L > /dev/null 2>&1; done
cat "$OUT/namecheck.txt" "$OUT/mapcost.txt"
