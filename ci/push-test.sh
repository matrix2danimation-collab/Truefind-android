#!/usr/bin/env bash
# Runs inside the Android emulator job ("Push test"): does a call ring and a message show while
# True Find is CLOSED — on this Android version?
#
# The message is handed to the app exactly the way Google Play services hands over a Firebase
# message (the FirebaseInstanceIdReceiver broadcast; the emulator is rooted, so it may send it).
# Google's own delivery to the phone is not part of this test (that is tested on real phones);
# everything after it is: the app starting from nothing, the ringing notification, full screen on
# the lock screen, Answer / Decline, missed call, message, Doze, app standby, restrictions.
#
# Output: $2/result.txt (one PASS / FAIL / INFO line per check) and $2/*.png
APK="$1"; OUT="$2"
PKG=app.truefind.android
mkdir -p "$OUT"; R="$OUT/result.txt"; : > "$R"
log() { echo "$*" | tee -a "$R"; }
pass() { log "PASS $*"; }
fail() { log "FAIL $*"; }
info() { log "INFO $*"; }

adb root > /dev/null 2>&1; sleep 4; adb wait-for-device
# every crash of the whole run is kept (the log is cleared between the checks)
adb logcat -c; adb logcat -v epoch AndroidRuntime:E '*:S' > "$OUT/crash.txt" 2>&1 &
CRASHLOG=$!
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
info "Android API $API ($(adb shell getprop ro.build.version.release | tr -d '\r'))"
adb install -r "$APK" > "$OUT/install.txt" 2>&1 || { fail "install"; cat "$OUT/install.txt"; exit 0; }
[ "$API" -ge 33 ] && adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell settings put system screen_off_timeout 1800000
adb shell settings put global heads_up_notifications_enabled 1
adb shell svc power stayon false
adb shell wm dismiss-keyguard > /dev/null 2>&1

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2> /dev/null; }
tfdbg() { adb logcat -d -v epoch -s TFDBG:I; }
pid() { adb shell pidof $PKG 2> /dev/null | tr -d '\r'; }
# an active notification of True Find with this tag (only showing ones: the dismissed archive is printed differently)
active() { adb shell dumpsys notification --noredact 2> /dev/null | grep -E "NotificationRecord\(.*pkg=$PKG.*tag=$1( |\))" ; }
record() { adb shell dumpsys notification --noredact 2> /dev/null | awk -v t="tag=$1" 'index($0,"NotificationRecord(") && index($0,t) {p=1} p {print; n++} n>60 {exit}'; }
wait_active() { local i=0; while [ $i -lt "${2:-20}" ]; do [ -n "$(active "$1")" ] && return 0; sleep 0.5; i=$((i+1)); done; return 1; }
wait_gone() { local i=0; while [ $i -lt "${2:-20}" ]; do [ -z "$(active "$1")" ] && return 0; sleep 0.5; i=$((i+1)); done; return 1; }
wait_log() { local i=0; while [ $i -lt "${2:-20}" ]; do tfdbg | grep -q "$1" && return 0; sleep 0.5; i=$((i+1)); done; return 1; }

# one Firebase message, the way Google Play services delivers it ('key=value' …; no ' in values)
send() {
  local a="" kv
  for kv in "$@"; do a="$a --es ${kv%%=*} '${kv#*=}'"; done
  adb shell "am broadcast -n $PKG/com.google.firebase.iid.FirebaseInstanceIdReceiver -a com.google.android.c2dm.intent.RECEIVE -c $PKG --es from 923989420150 --es google.message_id m$RANDOM$RANDOM --es google.delivered_priority high --es google.original_priority high $a" >> "$OUT/broadcasts.txt" 2>&1
}
call() { send t=call "title=Riya is calling you" "body=Incoming ${2:-video} call · True Find" "call_id=$1" "kind=${2:-video}" "tag=call-$1" "url=/?call=$1" name=Riya ${3:+"icon=$3"}; }

# True Find closed the way a swipe from Recents closes it: no process left
close_app() {
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am kill $PKG; sleep 1
  local p; p=$(pid); [ -n "$p" ] && { adb shell kill -9 $p; sleep 1; }
  [ -z "$(pid)" ]
}
screen_on() { adb shell dumpsys power | grep -qE "mWakefulness=Awake|Display Power: state=ON"; }
unlock() { adb shell input keyevent KEYCODE_WAKEUP; sleep 1; adb shell wm dismiss-keyguard > /dev/null 2>&1; adb shell input keyevent KEYCODE_HOME; sleep 1; }
bgcheck() { close_app > /dev/null; adb logcat -c; adb shell am start -W -n $PKG/.MainActivity --ez tf_bgcheck true >> "$OUT/bgcheck-start.txt" 2>&1; wait_log "bgcheck" 60; tfdbg | grep -o "bgcheck.*" | tail -1; }
# tap the notification button with this text (the shade is opened first)
# the ringing notifications go (the app takes the ringing over when it comes on screen: Push.clearCalls)
clear_calls() { adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 4; close_app > /dev/null; }
dump_ui() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2> /dev/null; }
tap_button() {
  local at
  dump_ui; at=$(python3 ci/ui-find.py "$OUT/ui.xml" "$1" "Riya is calling you")   # the pop-up at the top first
  if [ -z "$at" ] || echo "$at" | grep -q EXPAND; then
    adb shell cmd statusbar expand-notifications > /dev/null 2>&1 || adb shell service call statusbar 1 > /dev/null 2>&1; sleep 2
    dump_ui; at=$(python3 ci/ui-find.py "$OUT/ui.xml" "$1" "Riya is calling you")
  fi
  if echo "$at" | grep -q EXPAND; then   # shown folded (Android 16): unfold it, look again
    set -- "$1" $at; adb shell input tap $3 $4; sleep 2
    dump_ui; at=$(python3 ci/ui-find.py "$OUT/ui.xml" "$1")
  fi
  [ -z "$at" ] && return 1
  adb shell input tap $at
}

# ── 0. first start after install (Firebase starts, the notification kinds are made), then closed ──
adb logcat -c
info "$(bgcheck)"
sleep 6
if close_app; then pass "0  app closed: no process left"; else fail "0  app could not be closed"; fi

# ── 1. a video call rings while True Find is closed (screen on, unlocked) ──
adb logcat -c
call T1 video
if wait_active call-T1 30; then
  pass "1  call rang with the app closed (the app started from nothing: pid $(pid))"
  rec=$(record call-T1)
  echo "$rec" > "$OUT/record-call.txt"
  echo "$rec" | grep -q "tf_calls" && pass "1a on the 'Incoming calls' kind (tf_calls)" || fail "1a not on tf_calls"
  echo "$rec" | grep -qi "category=call" && pass "1b category: call" || fail "1b category is not call"
  echo "$rec" | grep -qiE "fullscreenIntent=PendingIntent|fullScreenIntent=PendingIntent" && pass "1c full-screen screen attached" || fail "1c no full-screen screen"
  fl=$(echo "$rec" | grep -oE "flags=[A-Za-z0-9_|]+" | head -1 | cut -d= -f2)
  if echo "$fl" | grep -q "INSISTENT" || { echo "$fl" | grep -qE "^0x" && [ $(( fl & 0x4 )) -ne 0 ]; }; then pass "1d ringtone repeats until answered (INSISTENT, flags=$fl)"; else fail "1d not INSISTENT (flags=$fl)"; fi
  adb shell dumpsys notification 2> /dev/null | grep -E "mSoundNotificationKey=.*call-T1" > /dev/null && pass "1f the ringtone is playing for this call" || info "1f ringtone owner: $(adb shell dumpsys notification 2> /dev/null | grep -o 'mSoundNotificationKey=[^ ]*' | head -1)"
  echo "$rec" | grep -qiE "actions=2|Decline" && pass "1e Answer / Decline buttons" || info "1e buttons not seen in the record"
  sleep 1; shot 01-call-app-closed
else
  fail "1  call did NOT ring with the app closed"; tfdbg | tail -20 >> "$R"
fi

# ── 2. the caller hangs up: the ringing stops, "Missed call" shows ──
send t=missed "title=Missed call" "body=Riya tried to video call you on True Find" call_id=T1 tag=missed-T1 url=/chats
if wait_gone call-T1 20; then pass "2  caller hung up: ringing stopped"; else fail "2  still ringing after the caller hung up"; fi
if wait_active missed-T1 20; then pass "2a 'Missed call' shown"; sleep 1; shot 02-missed-call; else fail "2a no 'Missed call'"; fi

# ── 3. a message while True Find is closed ──
close_app
adb logcat -c
send t=msg "title=New message" "body=Someone sent you a message" tag=m-T3 "url=/secret-app"
if wait_active m-T3 30; then pass "3  message shown with the app closed"; sleep 1; shot 03-message-app-closed; else fail "3  message NOT shown with the app closed"; fi

# ── 4. a voice call on the LOCKED phone, screen off: the screen wakes and the call screen opens ──
close_app
adb shell input keyevent KEYCODE_SLEEP; sleep 3
adb logcat -c
call T4 voice
if wait_log "push open call=T4" 30; then pass "4  locked + screen off: the call screen opened by itself"; else fail "4  locked + screen off: the call screen did not open"; fi
sleep 3
screen_on && pass "4a the screen woke up" || fail "4a the screen stayed off"
[ -n "$(active call-T4)$(tfdbg | grep 'push shown t=call id=T4')" ] && pass "4b the call rang" || fail "4b the call did not ring"
shot 04-call-locked-phone
record call-T4 | grep -iE "NotificationRecord|group|fullscreen|importance" > "$OUT/record-call-locked.txt"
adb logcat -d -b all 2> /dev/null | grep -iE "full.?screen|fullscreenintent|FSI|NotifInterrupt|VisualInterruption|HeadsUpCoordinator" | tail -60 > "$OUT/fsi-decision.txt"
info "4  group of the locked-phone call: $(grep -oE "(overrideGroupKey|groupKey|group)=[^ ,)]*" "$OUT/record-call-locked.txt" | sort -u | tr '\n' ' ')"
unlock

# ── 4x. the same, with no other True Find notification in the shade ──
clear_calls
adb shell cmd statusbar expand-notifications > /dev/null 2>&1; sleep 2
dump_ui; at=$(python3 ci/ui-find.py "$OUT/ui.xml" "Clear all"); [ -n "$at" ] && adb shell input tap $at; sleep 2
adb shell cmd statusbar collapse > /dev/null 2>&1
close_app
adb shell input keyevent KEYCODE_SLEEP; sleep 3
adb logcat -c
call T4x voice
if wait_log "push open call=T4x" 30; then pass "4x locked + screen off, no other notifications: the call screen opened"; else fail "4x locked + screen off, no other notifications: the call screen did not open"; fi
sleep 3; screen_on && pass "4x the screen woke up" || fail "4x the screen stayed off"
shot 04x-call-locked-clean-shade
adb logcat -d -b all 2> /dev/null | grep -iE "full.?screen|fullscreenintent|FSI|NotifInterrupt|VisualInterruption|HeadsUpCoordinator" | tail -60 > "$OUT/fsi-decision-clean.txt"
unlock

# ── 4y. locked, screen ON (the lock screen showing) ──
clear_calls
adb shell input keyevent KEYCODE_SLEEP; sleep 2; adb shell input keyevent KEYCODE_WAKEUP; sleep 2
adb logcat -c
call T4y video
if wait_log "push open call=T4y" 30; then pass "4y locked, screen on: the call screen opened over the lock screen"; else fail "4y locked, screen on: the call screen did not open"; fi
sleep 2; shot 04y-call-lock-screen-on
unlock

# ── 5. Doze (phone idle, unplugged, screen off for long) ──
close_app
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP; sleep 2
adb shell dumpsys deviceidle force-idle > "$OUT/doze.txt" 2>&1
info "doze: $(adb shell dumpsys deviceidle get deep | tr -d '\r')"
adb logcat -c
call T5 video
if wait_log "push shown t=call id=T5" 30; then pass "5  Doze: the call rang"; else fail "5  Doze: the call did not ring"; fi
adb shell dumpsys deviceidle unforce > /dev/null; adb shell dumpsys battery reset
unlock; clear_calls

# ── 6. app standby: "rare" and (Android 12+) "restricted" buckets ──
if [ "$API" -ge 28 ]; then
  for b in rare restricted; do
    [ "$b" = restricted ] && [ "$API" -lt 31 ] && continue
    close_app
    adb shell am set-standby-bucket $PKG $b
    adb logcat -c
    call T6$b video
    if wait_log "push shown t=call id=T6$b" 30; then pass "6  standby bucket '$b': the call rang"; else fail "6  standby bucket '$b': the call did not ring"; fi
    clear_calls
  done
  adb shell am set-standby-bucket $PKG active
fi

# ── 7. Answer from the ringing notification (app closed) ──
close_app
adb logcat -c
call T7 video
if wait_active call-T7 30 && tap_button "Answer"; then
  if wait_log "push open call=T7 act=answer" 30; then pass "7  Answer opened True Find on the call"; else fail "7  Answer did not open the call"; fi
else
  fail "7  could not find Answer on the ringing notification"; adb shell cat /sdcard/ui.xml > "$OUT/ui-answer.xml" 2>/dev/null
fi
sleep 2; shot 07-after-answer
adb shell cmd statusbar collapse > /dev/null 2>&1

# ── 8. Decline from the ringing notification (app closed) ──
close_app
adb logcat -c
call T8 voice
if wait_active call-T8 30 && tap_button "Decline"; then
  wait_log "push decline id=T8" 20 && pass "8  Decline worked without opening the app" || fail "8  Decline did not reach the app"
  wait_gone call-T8 20 && pass "8a the ringing stopped" || fail "8a still ringing after Decline"
else
  fail "8  could not find Decline on the ringing notification"; adb shell cat /sdcard/ui.xml > "$OUT/ui-decline.xml" 2>/dev/null
fi
adb shell cmd statusbar collapse > /dev/null 2>&1

# ── 9. a slow caller photo never holds up the ringing (it rings without the photo after at most 1 s) ──
close_app
adb logcat -c
call T9 video "https://httpbin.org/delay/8"
if wait_log "push shown t=call id=T9" 30; then
  g=$(tfdbg | grep "push got t=call" | tail -1 | awk '{print $1}'); s=$(tfdbg | grep "push shown t=call id=T9" | tail -1 | awk '{print $1}')
  ms=$(python3 -c "print(int((float('$s')-float('$g'))*1000))" 2>/dev/null || echo "?")
  if [ "$ms" != "?" ] && [ "$ms" -le 2000 ]; then pass "9  slow photo: rang ${ms} ms after the message came"; else fail "9  slow photo held the ringing up (${ms} ms)"; fi
else fail "9  slow photo: the call did not ring"; fi
clear_calls

# ── 10. True Find open on screen: no notification (the app rings itself), and it hears of the call ──
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 6
call T10 video
if wait_log "push in app t=call" 20; then pass "10 app on screen: the call went to the app itself"; else fail "10 app on screen: the call was not handed to the app"; fi
[ -z "$(active call-T10)" ] && pass "10a no second ringing notification over the open app" || fail "10a a notification showed over the open app"

# ── 11. what the phone still needs (BgSetup) is read correctly ──
close_app
l=$(bgcheck); info "11 fresh: $l"
echo "$l" | grep -q "battery=false" && echo "$l" | grep -q "next=battery" && pass "11a battery saver on → the card asks for it first" || fail "11a battery not asked for ($l)"
adb shell dumpsys deviceidle whitelist +$PKG > /dev/null
l=$(bgcheck); echo "$l" | grep -q "battery=true" && pass "11b battery allowed → seen" || fail "11b battery allowed not seen ($l)"
if [ "$API" -ge 28 ]; then
  adb shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND ignore
  l=$(bgcheck); echo "$l" | grep -q "restricted=true" && echo "$l" | grep -q "next=battery" && pass "11c Battery → Restricted → seen and asked first" || fail "11c Restricted not seen ($l)"
  adb shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow
fi
if [ "$API" -ge 34 ]; then
  adb shell cmd appops set $PKG USE_FULL_SCREEN_INTENT deny 2>> "$R"
  l=$(bgcheck); echo "$l" | grep -q "fullscreen=false" && echo "$l" | grep -q "next=fullscreen" && pass "11d full-screen calls off → seen" || fail "11d full-screen off not seen ($l)"
  # and a call still rings (as a pop-up instead of the full screen)
  close_app; adb logcat -c; call T11 video
  wait_active call-T11 30 && pass "11e full-screen off: the call still rings" || fail "11e full-screen off: the call did not ring"
  adb shell cmd appops set $PKG USE_FULL_SCREEN_INTENT allow 2>> "$R"
  clear_calls
fi
adb shell dumpsys deviceidle whitelist -$PKG > /dev/null
l=$(bgcheck); info "11 Google Play services on this phone: $(echo "$l" | grep -o 'play=[a-z]*')"

# ── 12. notifications switched off by the member: nothing can show (no crash; the card asks again) ──
if [ "$API" -ge 33 ]; then
  adb shell pm revoke $PKG android.permission.POST_NOTIFICATIONS
  close_app; adb logcat -c; call T12 video
  wait_log "push blocked t=call" 30 && pass "12 notifications off: handled without a crash (nothing can show)" || fail "12 notifications off: not handled"
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
fi

kill $CRASHLOG 2> /dev/null
if grep -A3 "FATAL EXCEPTION" "$OUT/crash.txt" | grep -q "$PKG"; then fail "True Find crashed during the run (crash.txt)"; else pass "no crash of True Find in the whole run"; fi
echo "----"; cat "$R"
exit 0
