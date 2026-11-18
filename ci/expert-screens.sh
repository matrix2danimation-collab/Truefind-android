#!/usr/bin/env bash
# Emulator pictures of the native Expert Talk, My Dashboard and Secret Dating Earning
# (test mode: made-up data from EtDemo.kt, no login, nothing sent anywhere).
# usage: expert-screens.sh <apk> <out dir> <set: et | etx>
APK="$1"; OUT="$2"; SET="${3:-et}"
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
P="--es tf_preview"
shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null
  sleep 1
  adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  adb shell am start -W -n $PKG/.MainActivity "$@" >> "$OUT/start.txt" 2>&1
  case "$*" in
    *tf_open*) waitlog "preview open" 60; sleep "$wait" ;;
    *) waitlog "frames so far" 60; sleep "$wait" ;;
  esac
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
  { echo "== $name"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile|Number "; } >> "$OUT/frames.txt"
}

if [ "$SET" = et ]; then
shot et01-home 8 $P et
shot et02-home-scroll1 5 $P et --es tf_open scroll1
shot et03-home-scroll2 5 $P et --es tf_open scroll2
shot et04-home-bottom 5 $P et --es tf_open scroll3
shot et05-experts 5 $P et --es tf_open experts
shot et06-experts-today 5 $P et --es tf_open today
shot et07-experts-topic 5 $P et --es tf_open topic
shot et08-profile 6 $P et --es tf_open profile
shot et09-profile-scroll 7 $P et --es tf_open profile,y620
shot et10-profile-bottom 7 $P et --es tf_open profile,y1400
shot et11-book 5 $P et --es tf_open book
shot et12-slot 6 $P et --es tf_open slot
shot et13-slot-pick 7 $P et --es tf_open slot,pick
shot et14-calendar 7 $P et --es tf_open slot,calendar
shot et15-confirm 6 $P et --es tf_open confirm
shot et16-booked 5 $P et --es tf_open booked
shot et17-sessions 6 $P et --es tf_open sessions
shot et18-sessions-scroll 7 $P et --es tf_open sessions,y760
shot et19-rules 7 $P et --es tf_open sessions,rules
shot et20-rate 7 $P et --es tf_open sessions,rate
shot et21-report 7 $P et --es tf_open sessions,report
shot et22-done 7 $P et --es tf_open sessions,done
shot et23-cancelled 7 $P et --es tf_open sessions,cancel
shot et24-move 6 $P et --es tf_open move
shot et25-apply 5 $P et --es tf_open apply
shot et26-apply-scroll 6 $P et --es tf_open apply,y700
shot et27-call 6 $P et --es tf_open call
shot et28-call-end 7 $P et --es tf_open call,end
fi

if [ "$SET" = etx ]; then
shot ex01-dash 8 $P et-expert
shot ex02-dash-scroll 7 $P et-expert --es tf_open x,scroll
shot ex03-dash-bottom 7 $P et-expert --es tf_open x,bottom
shot ex04-pause 7 $P et-expert --es tf_open x,pause
shot ex05-leave 7 $P et-expert --es tf_open x,leave
shot ex06-resched 7 $P et-expert --es tf_open x,resched
shot ex07-explain 7 $P et-expert --es tf_open x,explain
shot ex08-avail 5 $P et-expert --es tf_open avail
shot ex09-myprofile 6 $P et-expert --es tf_open xprofile
shot ex10-edit 5 $P et-expert --es tf_open edit
shot ex11-call 6 $P et-expert --es tf_open xcall
shot ex12-call-report 7 $P et-expert --es tf_open xcall,report
shot d01-dash-member 7 $P dash
shot d02-dash-creator 7 $P dash-creator
shot d03-dash-month 6 $P dash-creator --es tf_open month
shot d04-dash-all 6 $P dash-creator --es tf_open all
shot d05-earn 7 $P earn
shot d06-earn-bottom 6 $P earn --es tf_open bottom
shot d07-earn-admin 7 $P earn-admin
fi
