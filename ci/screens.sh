#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs
# the native screens in test mode (made-up data, no login). Output: $2/*.png
# Also writes frame timings (gfxinfo) per screen. The emulator draws with a
# software GPU, so its timings are much slower than a real phone's.
APK="$1"; OUT="$2"
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
# the test phone shows no "… isn't responding" boxes over the pictures (they only cover the screen)
adb shell settings put global hide_error_dialogs 1

# wait until a line with $1 is in the app's test log (max $2 s)
waitlog() {
  local t=0
  while [ $t -lt "$2" ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done
  echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"
}
# wait until the app has drawn everything it is going to: its frame count stays the same for 6 s (max $1 s)
settle() {
  local max=$1 t=0 same=0 lastN="" lastL=0 n l
  while [ $t -lt "$max" ]; do
    sleep 2; t=$((t+2))
    l=$(adb logcat -d -s TFDBG:I | grep -c "frames so far")
    n=$(adb logcat -d -s TFDBG:I | grep -o "frames so far [0-9]*" | tail -1)
    if [ "$l" -gt "$lastL" ] && [ -n "$n" ] && [ "$n" = "$lastN" ]; then same=$((same+1)); [ $same -ge 3 ] && return 0; else same=0; fi
    lastN="$n"; lastL=$l
  done
  echo "  (still drawing after $max s)" >> "$OUT/start.txt"
}
shot() {
  local name="$1"; local mode="$2"; shift 2
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null   # no plan / settings left over from the last screen
  sleep 1
  adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  adb shell am start -W -n $PKG/.MainActivity "$@" >> "$OUT/start.txt" 2>&1
  # a slow emulator can show "System UI isn't responding" on top: close such system pop-ups first
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  case "$mode" in
    open)  waitlog "preview open" 45; settle 60 ;;
    quick) waitlog "preview open" 45; sleep 1 ;;   # toasts and pop-ups go away after a few seconds
    *)     sleep 4; settle 60 ;;
  esac
  [ "$mode" = quick ] || { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 2; }
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
  { echo "== $name"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile|Number "; } >> "$OUT/frames.txt"
}
if [ "${GITHUB_REF_NAME:-}" = "home-native" ]; then
# ── native Home, bottom bar, Profile (+ its pages and Settings screens), Edit your profile, Verification ──
P="--es tf_preview"
nshot() {
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
  { echo "== $name"; adb logcat -d | grep -E "ActivityManager.*(Kill|kill|died|ANR|crash)|Fatal signal|lowmemorykiller|am_kill|DEBUG   :" | tail -15; } >> "$OUT/why.txt"
  { echo "== $name"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile|Number "; } >> "$OUT/frames.txt"
}
nshot h01-home 9 $P home
nshot h02-home-girl 9 $P home --ez tf_girl true
nshot h03-home-scroll1 10 $P home --es tf_open scroll1
nshot h04-home-scroll2 10 $P home --es tf_open scroll2
nshot h05-home-scroll3 10 $P home --es tf_open scroll3
nshot h06-home-daily 11 $P home --es tf_open daily
nshot h07-home-confirm 10 $P home --es tf_open confirm
nshot h08-home-person 10 $P home --es tf_open person
nshot h09-home-all 10 $P home --es tf_open all
nshot h10-home-match 10 $P home --es tf_open match
nshot h11-home-dialog 10 $P home --es tf_open dialog
nshot h12-home-toast 2 $P home --es tf_open toast
nshot n01-nav-explore 7 $P nav-explore
nshot n02-nav-recent 7 $P nav-recent
nshot p01-profile 9 $P profile
nshot p02-profile-girl 9 $P profile --ez tf_girl true
nshot p03-profile-scroll 10 $P profile --es tf_open scroll
nshot p04-edit 9 $P profile --es tf_open edit=1
nshot p05-edit-state 10 $P profile --es tf_open edit=1,state
nshot p06-followers 9 $P profile --es tf_open people=followers
nshot p07-following 9 $P profile --es tf_open people=following
nshot p08-unfollow 10 $P profile --es tf_open people=following,unfollow
nshot p09-settings 9 $P profile --es tf_open settings=1
nshot p10-notifications 9 $P profile --es tf_open settings=notifications
nshot p11-apply-host 9 $P profile --es tf_open settings=apply
nshot p12-apply-form 10 $P profile --es tf_open settings=apply,form
nshot p13-apply-creator 9 $P profile --ez tf_girl true --es tf_open settings=apply
nshot p14-verification 9 $P profile --es tf_open settings=verification
nshot p15-memberships 9 $P profile --es tf_open settings=memberships
nshot p16-privacy 9 $P profile --es tf_open settings=privacy
nshot p17-blocked 9 $P profile --es tf_open settings=blocked
nshot p18-security 9 $P profile --es tf_open settings=security
nshot p19-refer 9 $P profile --es tf_open settings=refer
nshot p20-help 9 $P profile --es tf_open settings=help
nshot p21-help-new 10 $P profile --es tf_open settings=help,new
nshot p22-help-thread 10 $P profile --es tf_open settings=help,thread
nshot p23-avatar 10 $P profile --es tf_open avatar
nshot p24-bio 10 $P profile --es tf_open bio
nshot p25-interests 10 $P profile --es tf_open interests
nshot p26-viewer 10 $P profile --es tf_open viewer
nshot p27-logout 10 $P profile --es tf_open logout
nshot p28-delete 10 $P profile --es tf_open delete
nshot p29-toast 2 $P profile --es tf_open toast
nshot b01-builder 9 $P builder
nshot b02-builder-error 10 $P builder --es tf_open error
nshot b03-builder-uploading 10 $P builder --es tf_open uploading
nshot v01-verify-girl 9 $P verify --ez tf_girl true
nshot v02-verify-boy 9 $P verify
nshot v03-verify-pending 10 $P verify --ez tf_girl true --es tf_open pending
# the screens of the other work still open as before
shot s01-explore home --es tf_preview explore
shot s02-secret home --es tf_preview secret --ez tf_premium true
else
shot 01-explore home --es tf_preview explore
shot 02-secret-free home --es tf_preview secret
shot 03-secret-premium home --es tf_preview secret --ez tf_premium true
shot 04-secret-scroll-mid open --es tf_preview secret --ez tf_premium true --es tf_open scrollmid
shot 05-secret-scroll-end open --es tf_preview secret --es tf_open scroll
shot 06-profile open --es tf_preview secret --ez tf_premium true --es tf_open profile
shot 07-profile-free open --es tf_preview secret --es tf_open profile
shot 08-request open --es tf_preview secret --ez tf_premium true --es tf_open request
shot 09-premium open --es tf_preview secret --es tf_open premium
shot 10-need open --es tf_preview secret --es tf_open need
shot 11-verify open --es tf_preview secret --ez tf_girl true --es tf_open verify
shot 12-story quick --es tf_preview secret --ez tf_premium true --es tf_open story
shot 13-my-profile open --es tf_preview secret --ez tf_premium true --es tf_open my
shot 14-my-edit open --es tf_preview secret --es tf_open myedit
shot 15-add-story open --es tf_preview secret --ez tf_premium true --es tf_open addstory
shot 16-toast quick --es tf_preview secret --ez tf_premium true --es tf_open toast
shot 17-girl-home home --es tf_preview secret --ez tf_girl true
shot 18-pop quick --es tf_preview secret --ez tf_premium true --es tf_open pop
shot 19-free-stories open --es tf_preview secret --es tf_open stories
shot 20-see-all open --es tf_preview secret --ez tf_premium true --es tf_open seeall
shot 21-profile-end open --es tf_preview secret --ez tf_premium true --es tf_open profileend
fi
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
