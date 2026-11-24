#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs the native sign-in pages in
# test mode (made-up answers, no real number / email, nobody is signed in). Output: $2/*.png (+ crash logs)
APK="$1"; OUT="$2"
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1 || true
on_top() { adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "$PKG"; }
waitlog() { local t=0; while [ $t -lt "$1" ]; do adb logcat -d -s TFDBG:I | grep -q "preview open" && return 0; sleep 1; t=$((t+1)); done; echo "  (no 'preview open' after $1 s)" >> "$OUT/start.txt"; }
# shot <name> <seconds after the page is ready> <scene> [open]
shot() {
  local name="$1" wait="$2" scene="$3" open="${4:-}"
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null
  sleep 1
  adb logcat -c
  echo "== $name ($scene $open)" >> "$OUT/start.txt"
  if [ -n "$open" ]; then adb shell am start -W -n $PKG/.MainActivity --es tf_login "$scene" --es tf_open "$open" >> "$OUT/start.txt" 2>&1
  else adb shell am start -W -n $PKG/.MainActivity --es tf_login "$scene" >> "$OUT/start.txt" 2>&1; fi
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  waitlog 30
  sleep "$wait"
  if ! on_top; then
    { echo "== $name: app not on top"; adb logcat -d -b main,system,crash | grep -iE "truefind|FATAL|ANR in|has died" | tail -40; } > "$OUT/$name.why.txt"
    adb shell am start -W -n $PKG/.MainActivity --es tf_login "$scene" --es tf_open "$open" > /dev/null 2>&1; waitlog 30; sleep "$wait"
  fi
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I TFLogin:W | grep -E "TFDBG|TFLogin"; } >> "$OUT/debug.txt"
  { echo "== $name"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile"; } >> "$OUT/frames.txt"
}
# ── Get Started → mobile number → WhatsApp OTP → gender → profile (/welcome) ──
shot w01-intro 4 welcome-intro-still
shot w02-intro-2 4 welcome-intro-2-still
shot w03-intro-3 4 welcome-intro-3-still
shot w04-intro-rules 4 welcome-intro-still rules
shot w05-phone 4 welcome-phone
shot w05b-phone-keyboard 5 welcome-phone kb
shot w06-phone-filled 4 welcome-phone-filled
shot w07-phone-error 4 welcome-phone-error
shot w08-phone-note 4 welcome-phone-note
shot w09-verify 4 welcome-verify
shot w10-verify-typing 4 welcome-verify-typing
shot w11-verify-error 4 welcome-verify-error
shot w13-verify-demo 4 welcome-verify-demo
shot w14-verify-resend 4 welcome-verify-resend
shot w15-gender 4 welcome-gender
shot w16-gender-female 4 welcome-gender-female
shot w17-profile 4 welcome-profile
shot w18-profile-filled 4 welcome-profile-filled
shot w19-profile-errors 4 welcome-profile-errors
shot w20-profile-state 4 welcome-profile state
shot w21-profile-city 4 welcome-profile city
shot w22-profile-fail 4 welcome-profile-fail
shot w23-boot 3 welcome-boot
# ── /delete-account (e-mail sign-in, MFA, Google and the old profile pages were removed: WhatsApp OTP only, Supabase) ──
shot d01-delete 4 delete
shot d02-delete-sent 4 delete-sent
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
