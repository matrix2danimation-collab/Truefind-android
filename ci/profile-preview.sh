#!/usr/bin/env bash
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"; adb install -r "$APK" || exit 1
shot() { local n="$1"; local w="$2"; shift 2
  adb shell am force-stop $PKG; adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --es tf_preview profile "$@" >/dev/null; sleep "$w"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; sleep 1
  adb exec-out screencap -p > "$OUT/$n.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$n.crash.txt"; [ -s "$OUT/$n.crash.txt" ] || rm -f "$OUT/$n.crash.txt"; }
shot p01-top 12
shot p02-mid 12 --es tf_open mid
shot p03-bottom 12 --es tf_open scroll
shot p04-photos 12 --es tf_open photos
ls -la "$OUT"
