#!/usr/bin/env bash
# The app (branch supabase-app) against the REAL Supabase server, on the test phone (emulator). It signs in with
# a test number (Supabase → Authentication → Phone → test numbers: a fixed OTP, no WhatsApp message is sent):
#   919000000001 = "Test Ravi" (member, OTP 111111) · 919000000002 = "Test Priya" (creator, OTP 222222)
#  1. fresh install → Get Started                                                → x01-get-started.png
#  2. Get Started → number 9000000002 → Login with OTP → OTP screen              → x02-number.png, x03-otp.png
#  3. OTP 222222 → Home (Supabase Auth session, checkPhoneLogin "ok")            → x04-home.png
#  4. Explore, Wallet, Profile: read from Supabase                               → x05-explore.png, x06-wallet.png, x07-profile.png
#  5. Ravi (this script, through Supabase's API) calls Priya → the incoming-call screen shows on the phone
#     (it comes live: Supabase Realtime, or the Firebase notification)          → x08-incoming.png
#  6. Ravi hangs up → the call screen goes                                       → x09-after-call.png
#  7. the app closed and opened again → still signed in, straight to Home       → x10-reopened.png
#  8. Settings → Log out → Get Started again                                     → x11-logged-out.png
# results.txt: what passed / failed (each line "OK …" or "FAIL …"); app-log.txt: what the app wrote.
APK="$1"; OUT="$2"; PKG=app.truefind.android
SB=https://ltffzgmnhjeqoguquovf.supabase.co
KEY=sb_publishable_fQ6jdqIDFe9HrU5fbRWJqA_a9rQAPrn
mkdir -p "$OUT"
ADB="$(command -v adb)"
adb() { timeout 120 "$ADB" "$@"; }
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null
for p in POST_NOTIFICATIONS RECORD_AUDIO CAMERA; do adb shell pm grant $PKG android.permission.$p > /dev/null 2>&1; done
adb shell input keyevent KEYCODE_HOME; sleep 15

ok() { echo "OK   $*" | tee -a "$OUT/results.txt"; }
bad() { echo "FAIL $*" | tee -a "$OUT/results.txt"; }
shot() { dump; timeout 40 "$ADB" exec-out screencap -p > "$OUT/$1.png"; [ -s "$OUT/$1.png" ] || rm -f "$OUT/$1.png"; }
said() { adb logcat -d -s TFDBG:I | grep -q "$1"; }
# waits until the app has written [1] ([2] seconds at most)
waitlog() { local t=0; while [ $t -lt "$2" ]; do said "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
dump1() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1; }
# the test phone's own "Pixel Launcher isn't responding" box (its launcher is slow on the emulator) covers
# everything: "Wait" closes it, then the screen is read again
dump() {
  local i xy
  for i in 1 2 3 4; do
    dump1
    grep -q "isn&apos;t responding\|isn't responding" "$OUT/ui.xml" 2>/dev/null || return 0
    xy=$(python3 ci/ui-find.py "$OUT/ui.xml" "Wait" 2>/dev/null) && adb shell input tap $xy
    echo "(closed the phone's 'isn't responding' box)" >> "$OUT/start.txt"
    sleep 2
  done
}
# taps the element whose text / description is [1] (waits [2] seconds at most for it to show)
tap() {
  local t=0 xy
  while [ $t -lt "${2:-20}" ]; do
    dump; xy=$(python3 ci/ui-find.py "$OUT/ui.xml" "$1" 2>/dev/null)
    if [ -n "$xy" ] && [ "${xy%% *}" != EXPAND ]; then adb shell input tap $xy; return 0; fi
    sleep 2; t=$((t+2))
  done
  return 1
}
shows() { dump; python3 ci/ui-find.py "$OUT/ui.xml" "$1" > /dev/null 2>&1; }
link() { adb shell am start -n $PKG/.MainActivity -a android.intent.action.VIEW -d "https://truefindapp.com$1" > /dev/null 2>&1; }
# a Supabase Auth session for a test number (prints the access token, or "")
session() {
  curl -s -m 20 -X POST "$SB/auth/v1/otp" -H "apikey: $KEY" -H "Content-Type: application/json" -d "{\"phone\":\"+$1\"}" > /dev/null
  curl -s -m 20 -X POST "$SB/auth/v1/verify" -H "apikey: $KEY" -H "Content-Type: application/json" \
    -d "{\"type\":\"sms\",\"phone\":\"+$1\",\"token\":\"$2\"}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('access_token',''), d.get('user',{}).get('id',''))"
}
fn() {  # fn <token> <name> <json>
  curl -s -m 30 -X POST "$SB/functions/v1/api/apps/truefind/functions/$2" -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "$3"
}
keep() {
  adb logcat -d -s TFDBG:I TFAuth:W TFLive:W TFLogin:W TFApi:W | grep -vE "frames so far" > "$OUT/app-log.txt"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/crash.txt"
  [ -s "$OUT/crash.txt" ] || rm -f "$OUT/crash.txt"
}

# ── 1. fresh install ──
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity >> "$OUT/start.txt" 2>&1
sleep 6
shot x01-get-started
if said "start: no sign-in on this phone" && shows "Get Started"; then ok "1 fresh install opens Get Started (no web page)"; else bad "1 Get Started did not show"; fi

# ── 2. number → OTP screen ──
tap "Get Started" 20 || bad "2 no Get Started button"
sleep 2
adb shell input text 9000000002
sleep 1
shot x02-number
tap "Login with OTP" 10 || bad "2 no Login with OTP button"
sleep 5
shot x03-otp
if shows "Verify OTP"; then ok "2 OTP sent by Supabase Auth, OTP screen shows"; else bad "2 the OTP screen did not show"; fi

# ── 3. OTP → Home ──
adb shell input text 222222
if waitlog "signed in: the app's own pages take over" 40; then ok "3 OTP accepted: signed in"; else bad "3 not signed in after the OTP"; fi
sleep 10
shot x04-home
if said "native at /"; then ok "3 Home opened"; else bad "3 Home did not open"; fi

# ── 4. pages filled from Supabase ──
link /explore; sleep 9; shot x05-explore
link /wallet; sleep 8; shot x06-wallet
link /profile; sleep 8; shot x07-profile
link /; sleep 4

# ── 5. Ravi calls Priya: the call comes to the phone live ──
read -r RAVI RAVI_ID <<< "$(session 919000000001 111111)"
read -r PRIYA PRIYA_ID <<< "$(session 919000000002 222222)"
[ -n "$RAVI" ] && [ -n "$PRIYA_ID" ] && ok "5 test sessions made from the script" || bad "5 could not make the test sessions"
CALL=$(fn "$RAVI" startCall "{\"to_user_id\":\"$PRIYA_ID\",\"type\":\"voice\",\"app\":\"truefind\",\"client_key\":\"ci-$(date +%s)-call\"}")
echo "startCall → $CALL" | head -c 600 >> "$OUT/api.txt"; echo >> "$OUT/api.txt"
CALL_ID=$(echo "$CALL" | python3 -c "import sys,json; print((json.load(sys.stdin).get('call') or {}).get('id',''))" 2>/dev/null)
[ -n "$CALL_ID" ] && ok "5 Ravi's call started on the server ($CALL_ID)" || bad "5 startCall failed"
sleep 8
shot x08-incoming
if shows "Accept" || shows "Decline" || said "incoming"; then ok "5 the incoming call showed on the phone"; else bad "5 no incoming-call screen"; fi

# ── 6. Ravi hangs up ──
[ -n "$CALL_ID" ] && echo "endCall → $(fn "$RAVI" endCall "{\"call_id\":\"$CALL_ID\"}")" | head -c 400 >> "$OUT/api.txt"; echo >> "$OUT/api.txt"
sleep 8
shot x09-after-call
if shows "Accept"; then bad "6 the call screen is still there"; else ok "6 the call screen went"; fi

# ── 7. closed and opened again ──
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity >> "$OUT/start.txt" 2>&1
sleep 9
shot x10-reopened
if said "start: sign-in kept on this phone"; then ok "7 reopened: still signed in"; else bad "7 the sign-in was not kept"; fi

# ── 8. log out ──
link "/profile?settings=1"; sleep 5
if tap "Log out" 15; then
  sleep 2
  tap "Log out" 10   # the "Log out?" box
  sleep 8
  shot x11-logged-out
  if shows "Get Started"; then ok "8 logged out: Get Started"; else bad "8 Get Started did not come after Log out"; fi
else bad "8 no Log out in Settings"; shot x11-logged-out; fi

keep
echo "== results"; cat "$OUT/results.txt"
ls -la "$OUT"
