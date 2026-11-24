#!/usr/bin/env bash
# The app (branch supabase-app) against the REAL Supabase server, on the test phone (emulator). It signs in with
# a test number (Supabase → Authentication → Phone → test numbers: a fixed OTP, no WhatsApp message is sent):
#   919000000001 = "Test Ravi" (member, OTP 111111) · 919000000002 = "Test Priya" (creator, OTP 222222)
#  1. fresh install → Get Started                                                → x01-get-started.png
#  2. Get Started → number 9000000002 → (Enter) → OTP sent by Supabase Auth      → x02-number.png, x03-otp.png
#  3. OTP 222222 → signed in, Home, live updates connected                       → x04-home.png
#  4. Explore, Wallet, Profile: read from Supabase                               → x05-explore.png, x06-wallet.png, x07-profile.png
#  5. Ravi (this script, through Supabase's API) calls Priya → the call arrives live (Supabase Realtime) and
#     the incoming-call screen shows                                             → x08-incoming.png
#  6. Ravi hangs up → the call screen goes                                       → x09-after-call.png
#  7. the app closed and opened again → still signed in, straight to Home       → x10-reopened.png
#  8. Settings → Log out → Get Started again                                     → x11-logged-out.png
# Checks read the app's own log lines (TFDBG), not the screen: the sign-in pages move all the time, and the
# phone's screen reader (uiautomator) waits for a still screen. results.txt: "OK …" / "FAIL …" per check.
APK="$1"; OUT="$2"; WHO="${3:-priya}"; PKG=app.truefind.android
# who signs in on the test phone: Priya (creator: Ravi's call rings for her) or Ravi (member: no call part)
if [ "$WHO" = ravi ]; then NUM=9000000001; OTP=111111; else NUM=9000000002; OTP=222222; fi
SB=https://ltffzgmnhjeqoguquovf.supabase.co
KEY=sb_publishable_fQ6jdqIDFe9HrU5fbRWJqA_a9rQAPrn
mkdir -p "$OUT"
ADB="$(command -v adb)"
adb() { timeout 60 "$ADB" "$@"; }
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null
for p in POST_NOTIFICATIONS RECORD_AUDIO CAMERA; do adb shell pm grant $PKG android.permission.$p > /dev/null 2>&1; done
adb shell input keyevent KEYCODE_HOME; sleep 15
# the screen's size (the Get Started button sits at the same place of the page on every phone of this size)
read -r W H <<< "$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"; W=${W:-1080}; H=${H:-2400}

ok() { echo "OK   $*" | tee -a "$OUT/results.txt"; }
bad() { echo "FAIL $*" | tee -a "$OUT/results.txt"; }
said() { adb logcat -d -s TFDBG:I | grep -q "$1"; }
# waits until the app has written [1] ([2] seconds at most)
waitlog() { local end=$(( $(date +%s) + $2 )); while [ "$(date +%s)" -lt $end ]; do said "$1" && return 0; sleep 1; done; return 1; }
# the screen as text (20 s at most: a moving screen is never "still" for the phone's reader)
dump() { rm -f "$OUT/ui.xml"; timeout 20 "$ADB" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && timeout 20 "$ADB" pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1; [ -s "$OUT/ui.xml" ]; }
# the test phone's own "… isn't responding" box (its launcher is slow on the emulator): "Wait" closes it
unblock() {
  local i xy
  for i in 1 2 3; do
    dump || return 0
    grep -q "isn't responding\|isn&apos;t responding" "$OUT/ui.xml" || return 0
    xy=$(python3 ci/ui-find.py "$OUT/ui.xml" "Wait" 2>/dev/null) && adb shell input tap $xy
    echo "(closed the phone's 'isn't responding' box)" >> "$OUT/start.txt"; sleep 2
  done
}
shot() { timeout 40 "$ADB" exec-out screencap -p > "$OUT/$1.png"; [ -s "$OUT/$1.png" ] || rm -f "$OUT/$1.png"; }
# taps the element whose text / description is [1] ([2] seconds at most)
tap() {
  local end=$(( $(date +%s) + ${2:-20} )) xy
  while [ "$(date +%s)" -lt $end ]; do
    if dump; then xy=$(python3 ci/ui-find.py "$OUT/ui.xml" "$1" 2>/dev/null); if [ -n "$xy" ] && [ "${xy%% *}" != EXPAND ]; then adb shell input tap $xy; return 0; fi; fi
    sleep 2
  done
  return 1
}
shows() { dump && python3 ci/ui-find.py "$OUT/ui.xml" "$1" > /dev/null 2>&1; }
# taps the LAST element whose text is [1] (a box over the page comes last: its button, not the page's line under it)
tap_top() {
  local end=$(( $(date +%s) + ${2:-20} )) xy
  while [ "$(date +%s)" -lt $end ]; do
    if dump; then
      xy=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[2].lower(); hit = None
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if want in ((n.get("text") or "").strip().lower(), (n.get("content-desc") or "").strip().lower()) and n.get("bounds"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); hit = f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
if hit: print(hit)
PY
)
      if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi
    fi
    sleep 2
  done
  return 1
}
link() { adb shell am start -n $PKG/.MainActivity -a android.intent.action.VIEW -d "https://truefindapp.com$1" > /dev/null 2>&1; }
# a Supabase Auth session for a test number (prints "<access token> <user id>")
session() {
  curl -s -m 20 -X POST "$SB/auth/v1/otp" -H "apikey: $KEY" -H "Content-Type: application/json" -d "{\"phone\":\"+$1\"}" > /dev/null
  curl -s -m 20 -X POST "$SB/auth/v1/verify" -H "apikey: $KEY" -H "Content-Type: application/json" \
    -d "{\"type\":\"sms\",\"phone\":\"+$1\",\"token\":\"$2\"}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('access_token',''), d.get('user',{}).get('id',''))"
}
fn() {  # fn <token> <name> <json>
  curl -s -m 30 -X POST "$SB/functions/v1/api/apps/truefind/functions/$2" -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "$3"
}
keep() {
  adb logcat -d -s TFDBG:I TFAuth:W TFLive:W TFLogin:W TFApi:W | grep -vE "frames so far" >> "$OUT/app-log.txt"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" >> "$OUT/crash.txt"
  [ -s "$OUT/crash.txt" ] || rm -f "$OUT/crash.txt"
}

# the app's own lines and every error, written down as they come (kept even when the test phone goes away)
adb logcat -c
( timeout 1800 "$ADB" logcat -v time -s TFDBG:I TFAuth:W TFLive:W TFLogin:W TFApi:W AndroidRuntime:E ActivityManager:I lowmemorykiller:I > "$OUT/live-log.txt" 2>&1 & )
( timeout 1800 "$ADB" logcat -v time "*:E" > "$OUT/errors.txt" 2>&1 & )
# the app's memory and the phone's busiest processes every 3 s (what led up to the phone going away, if it does)
( end=$(( $(date +%s) + 1500 )); while [ "$(date +%s)" -lt $end ]; do
    echo "== $(date +%H:%M:%S)"; timeout 8 "$ADB" shell dumpsys meminfo -s $PKG 2>/dev/null | grep -E "TOTAL|Java Heap|Native Heap|Graphics" | head -5
    timeout 8 "$ADB" shell top -b -n 1 -m 6 -o PID,RES,%CPU,ARGS 2>/dev/null | tail -7; sleep 3
  done > "$OUT/memory.txt" 2>&1 & )
phone_ok() { [ "$(timeout 10 "$ADB" get-state 2>/dev/null)" = device ]; }
gone() { phone_ok && return 1; bad "the test phone (emulator) went away: the rest is not checked"; cp "$OUT/live-log.txt" "$OUT/app-log.txt" 2>/dev/null; ls -la "$OUT"; exit 0; }

# ── 1. fresh install ──
adb shell am start -W -n $PKG/.MainActivity >> "$OUT/start.txt" 2>&1
sleep 6; unblock
shot x01-get-started
if said "start: no sign-in on this phone" && said "sign-in page shown"; then ok "1 fresh install opens the sign-in pages (no web page)"; else bad "1 the sign-in pages did not open"; fi

# ── 2. number → OTP sent ──
# Get Started: the big button at the bottom of the page (its place on the 1080 × 2400 phone: 540, 2088)
if ! tap "Get Started" 15; then adb shell input tap $((W / 2)) $((H * 2088 / 2400)); fi
sleep 3
adb shell input text $NUM
sleep 1
shot x02-number
adb shell input keyevent KEYCODE_ENTER     # the number field's "Go" = Login with OTP
if waitlog "login: otp sent 200" 25; then ok "2 Supabase Auth sent the OTP (test number)"; else bad "2 the OTP was not sent ($(adb logcat -d -s TFDBG:I | grep 'login: otp sent' | tail -1))"; fi
sleep 3
shot x03-otp

# ── 3. OTP → signed in → Home ──
adb shell input text $OTP
if waitlog "login: otp checked 200 signed in" 30; then ok "3 the OTP made the sign-in (Supabase Auth)"; else bad "3 the OTP did not sign in ($(adb logcat -d -s TFDBG:I | grep 'login: otp checked' | tail -1))"; fi
if waitlog "login: next 200 ok" 30; then ok "3 checkPhoneLogin: a finished profile"; else bad "3 checkPhoneLogin did not say ok ($(adb logcat -d -s TFDBG:I | grep 'login: next' | tail -1))"; fi
if waitlog "native at /" 20; then ok "3 Home opened"; else bad "3 Home did not open"; fi
sleep 4; shot x04-home
if waitlog "live: joined Call" 30; then ok "3 live updates connected (Supabase Realtime: Call)"; else gone; bad "3 live updates did not connect"; fi
sleep 6; unblock
shot x04b-home
# (a new member's Daily Free Coins pop-up covers every page: closed with its ×, the coins are not claimed)
tap "Close" 15 && sleep 2

# ── 4. pages filled from Supabase ──
gone
link /explore; sleep 10; shot x05-explore
link /wallet; sleep 9; shot x06-wallet
link /profile; sleep 9; shot x07-profile
link /; sleep 5

gone
if [ "$WHO" = priya ]; then
# ── 5. Ravi calls Priya: the call comes to the phone live ──
read -r RAVI RAVI_ID <<< "$(session 919000000001 111111)"
read -r PRIYA PRIYA_ID <<< "$(session 919000000002 222222)"
if [ -n "$RAVI" ] && [ -n "$PRIYA_ID" ]; then ok "5 test sessions made from the script"; else bad "5 could not make the test sessions"; fi
CALL=$(fn "$RAVI" startCall "{\"to_user_id\":\"$PRIYA_ID\",\"type\":\"voice\",\"app\":\"truefind\",\"client_key\":\"ci-$(date +%s)-call\"}")
echo "startCall → ${CALL:0:600}" >> "$OUT/api.txt"
CALL_ID=$(echo "$CALL" | python3 -c "import sys,json; print((json.load(sys.stdin).get('call') or {}).get('id',''))" 2>/dev/null)
if [ -n "$CALL_ID" ]; then ok "5 Ravi's call started on the server"; else bad "5 startCall failed"; fi
if waitlog "live: Call create" 15; then ok "5 the new call arrived live (Supabase Realtime)"; else bad "5 no live update for the new call"; fi
sleep 5
shot x08-incoming
if shows "Accept"; then ok "5 the incoming-call screen showed"; else echo "(5: the incoming-call screen could not be read as text — see x08-incoming.png)" >> "$OUT/results.txt"; fi

# ── 6. Ravi hangs up ──
[ -n "$CALL_ID" ] && echo "endCall → $(fn "$RAVI" endCall "{\"call_id\":\"$CALL_ID\"}" | head -c 400)" >> "$OUT/api.txt"
if waitlog "live: Call update" 15; then ok "6 the hang-up arrived live"; else bad "6 no live update for the hang-up"; fi
sleep 6
shot x09-after-call
keep
fi

gone
# ── 7. closed and opened again ──
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity >> "$OUT/start.txt" 2>&1
if waitlog "start: sign-in kept on this phone" 20; then ok "7 reopened: still signed in"; else bad "7 the sign-in was not kept"; fi
sleep 9; unblock
shot x10-reopened

gone
# ── 8. log out ──
link "/profile?settings=1"; sleep 6
for i in 1 2 3; do adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 300; sleep 1; done
if tap "Log out" 30; then
  sleep 2
  tap_top "Log out" 20   # the "Log out?" box's own button
  if waitlog "signing out" 15 && waitlog "sign-in page shown" 20; then ok "8 logged out: the sign-in pages again"; else bad "8 Log out did not finish"; fi
else bad "8 no Log out in Settings"; fi
sleep 6
shot x11-logged-out

keep
echo "== results"; cat "$OUT/results.txt"
ls -la "$OUT"
