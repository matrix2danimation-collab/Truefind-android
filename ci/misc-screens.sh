#!/usr/bin/env bash
# Runs inside the Android emulator job (branch misc-admin-native): photographs the native
# Control Center and the status screens in test mode (made-up data, no login). Output: $2/*.png
#   set "misc":  maintenance, announcement, blocked account, error screens, "Never miss a call"
#   set "admin": every Control Center section and its pop-ups
#   set "health": App Health only (Health, Pages, Issues, the member sheet's new parts)
APK="$1"; OUT="$2"; SET="${3:-misc}"
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1

# every phone command has a time limit, and a phone (emulator) that stopped is started again (25 Nov: the test
# phone sometimes drops off for good — "waiting for device" — and the job then waited until its time ran out)
T() { timeout "$@"; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
RESTARTS=0
alive() { [ "$(T 10 adb get-state 2>/dev/null | tr -d '\r')" = device ]; }
restart() {
  RESTARTS=$((RESTARTS+1))
  { echo "== $1: the phone stopped ($(date +%T)) — starting it again (#$RESTARTS)"; free -m | head -2
    sudo dmesg 2>/dev/null | grep -iE "oom|killed process|out of memory|qemu|segfault|general protection|emulator" | tail -15; } >> "$OUT/problems.txt"
  [ $RESTARTS -gt 4 ] && { echo "too many stops: the rest is skipped" >> "$OUT/problems.txt"; return 1; }
  pkill -9 -f qemu-system 2>/dev/null; pkill -9 -f "emulator/emulator" 2>/dev/null; sleep 3
  T 20 adb kill-server > /dev/null 2>&1; T 20 adb start-server > /dev/null 2>&1
  nohup "$SDK/emulator/emulator" -port 5554 -avd test -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none -no-snapshot > "$OUT/emulator-$RESTARTS.log" 2>&1 &
  T 240 adb wait-for-device || return 1
  local t=0
  while [ $t -lt 300 ]; do [ "$(T 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; t=$((t+3)); done
  sleep 5
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do T 15 adb shell settings put global $k 0; done
  T 15 adb shell input keyevent 82
  T 15 adb shell settings put system screen_off_timeout 600000
  T 15 adb shell settings put global hide_error_dialogs 1
  T 240 adb install -r "$APK" > /dev/null || return 1
  echo "   phone back at $(date +%T)" >> "$OUT/problems.txt"
}

waitlog() {
  local t=0
  while [ $t -lt "$2" ]; do T 10 adb logcat -d -s TFDBG:I 2>/dev/null | grep -q "$1" && return 0; alive || return 1; sleep 1; t=$((t+1)); done
  echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"
}
# open the app on one test scene (and, if given, one of its pop-ups)
launch() {
  local scene="$1" open="$2"
  if [ -n "$open" ]; then
    T 90 adb shell am start -W -n $PKG/.MainActivity --es tf_misc "$scene" --es tf_open "$open" >> "$OUT/start.txt" 2>&1
  else
    T 90 adb shell am start -W -n $PKG/.MainActivity --es tf_misc "$scene" >> "$OUT/start.txt" 2>&1
  fi
}
ontop() { T 20 adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG"; }
# shot NAME WAIT SCENE [OPEN] — taken once more if the phone stopped while it was open
shot() {
  [ $RESTARTS -gt 4 ] && return 0
  alive || restart "$1" || return 0
  shot1 "$@"
  if ! alive; then
    echo "$1: the phone stopped while this screen was open" >> "$OUT/problems.txt"
    rm -f "$OUT/$1.png"
    restart "$1" && shot1 "$@"
  fi
  return 0
}
shot1() {
  local name="$1" wait="$2" scene="$3" open="$4"
  T 30 adb shell am force-stop $PKG
  T 30 adb shell pm clear $PKG > /dev/null
  sleep 1
  T 15 adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  launch "$scene" "$open"
  waitlog "preview open" 60 || return 0
  sleep "$wait"
  T 15 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  # the app must be the screen on top; if it is not (it closed, or the phone showed something else), write down why and open it once more
  if ! ontop; then
    { echo "== $name: app not on top"; T 20 adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity";
      T 20 adb logcat -d -b main,system,crash | grep -iE "truefind|FATAL|Fatal signal|ANR in|lowmemorykiller|Killing|has died|backtrace|TFDBG" | tail -80; } > "$OUT/$name.why.txt"
    T 30 adb shell am force-stop $PKG
    sleep 1
    T 15 adb logcat -c
    echo "  (opened again)" >> "$OUT/start.txt"
    launch "$scene" "$open"
    waitlog "preview open" 60 || return 0
    sleep "$wait"
    T 15 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  fi
  T 60 adb exec-out screencap -p > "$OUT/$name.png"
  [ -s "$OUT/$name.png" ] || rm -f "$OUT/$name.png"
  T 20 adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; T 20 adb logcat -d | grep -E "TFApi|TFDBG|AndroidRuntime" | tail -20; } >> "$OUT/debug.txt"
  { echo "== $name"; T 20 adb logcat -d -s TFDBG:I | grep tabbar | tail -12; } >> "$OUT/tabbar.txt"
  return 0
}

# warm-up: the very first start after installing is much slower on the test phone (its web view and
# the app's code are prepared then); one start that is not photographed, so every picture gets a normal start
T 15 adb logcat -c
echo "== warm-up" >> "$OUT/start.txt"
launch maint ""
waitlog "preview open" 90
{ echo "== warm-up"; ontop && echo "on top" || echo "NOT on top"; T 20 adb logcat -d -b main,system,crash | grep -iE "FATAL|Fatal signal|ANR in|lowmemorykiller|has died|TFDBG" | tail -40; } >> "$OUT/warmup.txt"
alive || restart warm-up

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
  shot m12-notify-bg-battery 3 notify-bg-battery
  shot m12b-notify-bg-battery-restricted 3 notify-bg-battery-restricted
  shot m13-notify-bg-autostart 3 notify-bg-autostart
  shot m13b-notify-bg-autostart-samsung 3 notify-bg-autostart-samsung
  shot m13c-notify-bg-autostart-appinfo 3 notify-bg-autostart-appinfo
  shot m13d-notify-bg-miui 3 notify-bg-miui
  shot m13e-notify-bg-channel 3 notify-bg-channel
  shot m14-notify-bg-fullscreen 3 notify-bg-fullscreen
  shot m15-notify-bg-done 3 notify-bg-done
  shot m12-rules-reminder 4 rules
fi

# Control Center → System only (26 Nov: the Jobs and Backup texts after the move off Base44)
if [ "$SET" = cc ]; then
  shot a34-system 3 admin-system
  shot a35-system-jobs 3 admin-system details
  shot a36-system-end 3 admin-system scrollend
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
  # App Health (25 Nov): Health, Pages, Issues and the member sheet's new parts
  shot a37-health 4 admin-health
  shot a38-health-more 3 admin-health scroll
  shot a39-health-end 3 admin-health scrollend
  shot a40-health-week 4 admin-health week
  shot a41-health-alerts 4 admin-health alerts
  shot a42-pages 4 admin-pages
  shot a43-pages-more 3 admin-pages scroll
  shot a44-page-detail 4 admin-pages page
  shot a45-issues-calls 4 admin-errors calls
  shot a46-issue-details 4 admin-errors details
  shot a47-crash-groups 4 admin-errors crashes
  shot a48-member-use 5 admin-members member:use
  shot a49-member-payments 5 admin-members member:payments
  shot a50-member-calls 5 admin-members member:calls
  shot a51-member-problems 5 admin-members member:problems
fi

# App Health only (its work branch): the new sections, the old Errors (now Issues) and Overview
if [ "$SET" = health ]; then
  shot a01-overview 3 admin-overview
  # App Health (25 Nov): Health, Pages, Issues and the member sheet's new parts
  shot a37-health 4 admin-health
  shot a38-health-more 3 admin-health scroll
  shot a39-health-end 3 admin-health scrollend
  shot a40-health-week 4 admin-health week
  shot a41-health-alerts 4 admin-health alerts
  shot a42-pages 4 admin-pages
  shot a43-pages-more 3 admin-pages scroll
  shot a44-page-detail 4 admin-pages page
  shot a45-issues-calls 4 admin-errors calls
  shot a46-issue-details 4 admin-errors details
  shot a47-crash-groups 4 admin-errors crashes
  shot a48-member-use 5 admin-members member:use
  shot a49-member-payments 5 admin-members member:payments
  shot a50-member-calls 5 admin-members member:calls
  shot a51-member-problems 5 admin-members member:problems
  shot a29-issues 3 admin-errors
fi

T 60 adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
