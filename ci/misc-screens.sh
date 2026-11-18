#!/usr/bin/env bash
# Runs inside the Android emulator job (branch misc-admin-native): photographs the native
# Control Center and the status screens in test mode (made-up data, no login). Output: $2/*.png
#   set "misc":  maintenance, announcement, blocked account, error screens, "Never miss a call"
#   set "admin": every Control Center section and its pop-ups
APK="$1"; OUT="$2"; SET="${3:-misc}"
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1

waitlog() {
  local t=0
  while [ $t -lt "$2" ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done
  echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"
}
# shot NAME WAIT SCENE [OPEN]
shot() {
  local name="$1" wait="$2" scene="$3" open="$4"
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null
  sleep 1
  adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  if [ -n "$open" ]; then
    adb shell am start -W -n $PKG/.MainActivity --es tf_misc "$scene" --es tf_open "$open" >> "$OUT/start.txt" 2>&1
  else
    adb shell am start -W -n $PKG/.MainActivity --es tf_misc "$scene" >> "$OUT/start.txt" 2>&1
  fi
  waitlog "preview open" 60
  sleep "$wait"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d | grep -E "TFApi|TFDBG|AndroidRuntime" | tail -20; } >> "$OUT/debug.txt"
}

if [ "$SET" = misc ]; then
  shot m01-maintenance 3 maint
  shot m02-maintenance-admin-pill 3 maint-admin
  shot m03-announcement 3 announcement
  shot m04-banned 3 banned
  shot m05-error-crash 3 crash
  shot m06-error-offline-web 3 offline
  shot m07-error-cant-load 3 loaderr
  shot m08-error-server 3 servererr
  shot m09-notify 4 notify
  shot m10-notify-on 3 notify-on
  shot m11-notify-blocked 3 notify-blocked
fi

if [ "$SET" = admin ]; then
  shot a01-overview 3 admin-overview
  shot a02-overview-end 3 admin-overview scrollend
  shot a03-growth 4 admin-growth
  shot a04-growth-more 3 admin-growth scroll
  shot a05-verify-creators 4 admin-verify
  shot a06-verify-creator-more 3 admin-verify scroll
  shot a07-verify-reject 3 admin-verify reject
  shot a08-verify-hosts 4 admin-verify hosts
  shot a09-verify-photos 4 admin-verify photos
  shot a10-members 3 admin-members
  shot a11-members-search 4 admin-members search
  shot a12-members-phones 4 admin-members phones
  shot a13-member-sheet 4 admin-members member
  shot a14-reports 3 admin-reports
  shot a15-reports-chat 3 admin-reports chat
  shot a16-reports-ban 3 admin-reports ban
  shot a17-experts 3 admin-experts
  shot a18-experts-cases 3 admin-experts cases
  shot a19-experts-all 3 admin-experts experts
  shot a20-live 3 admin-live
  shot a21-live-end 3 admin-live end
  shot a22-payouts 3 admin-payouts
  shot a23-payouts-reject 3 admin-payouts reject
  shot a24-payouts-paid 3 admin-payouts paid
  shot a25-payouts-export 3 admin-payouts export
  shot a26-support 3 admin-support
  shot a27-support-thread 4 admin-support thread
  shot a28-support-deletion 4 admin-support deletion
  shot a29-errors 3 admin-errors
  shot a30-errors-details 3 admin-errors details
  shot a31-settings 3 admin-settings
  shot a32-settings-changed 3 admin-settings change
  shot a33-settings-confirm 3 admin-settings confirm
  shot a34-system 3 admin-system
  shot a35-system-jobs 3 admin-system details
  shot a36-system-end 3 admin-system scrollend
fi

adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
