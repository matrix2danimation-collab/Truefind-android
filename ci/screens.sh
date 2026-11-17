#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs
# the native screens in test mode (made-up data, no login). Output: $2/*.png
# Also writes frame timings (gfxinfo) per screen. The emulator draws with a
# software GPU, so its timings are much slower than a real phone's.
APK="$1"; OUT="$2"; SET="${3:-all}"   # which screens: home (Explore + Secret Dating Home) · chat (Online Map + Chats + one chat) · all
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000

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
    if [ "$l" -gt "$lastL" ] && [ -n "$n" ] && [ "$n" = "$lastN" ] && [ "${n##* }" -ge 2 ]; then same=$((same+1)); [ $same -ge 3 ] && return 0; else same=0; fi
    lastN="$n"; lastL=$l
  done
  echo "  (still drawing after $max s)" >> "$OUT/start.txt"
}
# phone animations on/off ("Remove animations"); the emulator starts with them off
anims() { for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k "$1"; done; }
shot() {
  local name="$1"; local mode="$2"; shift 2
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null   # no plan / settings left over from the last screen
  case "$mode" in anim*) anims 1 ;; esac   # a few screens are photographed with animations on, as on most phones
  sleep 1
  adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  adb shell am start -W -n $PKG/.MainActivity "$@" >> "$OUT/start.txt" 2>&1
  # a slow emulator can show "System UI isn't responding" on top: close such system pop-ups first
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  case "$mode" in
    open)  waitlog "preview open" 45; settle 60 ;;
    quick) waitlog "preview open" 45; sleep 1 ;;   # toasts and pop-ups go away after a few seconds
    story) waitlog "preview open" 45; sleep 3 ;;   # a story shows for 6 s
    anim)  sleep 14 ;;                             # moving screens never stop drawing: a fixed wait
    animopen) waitlog "preview open" 45; sleep 6 ;;
    *)     sleep 4; settle 60 ;;
  esac
  case "$mode" in quick|story) ;; *) adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 2 ;; esac
  # the app must be the screen on top; if it is not (it closed, or the phone showed something else), write down why and open it again
  if ! adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG"; then
    { echo "== $name: app not on top"; adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity";
      adb logcat -d -b main,system,crash | grep -iE "truefind|FATAL|Fatal signal|ANR in|lowmemorykiller|Killing|has died|backtrace" | tail -60; } > "$OUT/$name.why.txt"
    adb shell am start -n $PKG/.MainActivity "$@" > /dev/null 2>&1
    case "$mode" in open|quick|story|animopen) waitlog "preview open" 45 ;; esac
    case "$mode" in anim*) sleep 10 ;; quick|story) sleep 1 ;; *) settle 60 ;; esac
  fi
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
  { echo "== $name"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile|Number "; } >> "$OUT/frames.txt"
  case "$mode" in anim*) anims 0 ;; esac
}
if [ "$SET" = home ] || [ "$SET" = all ]; then
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
shot 12-story story --es tf_preview secret --ez tf_premium true --es tf_open story
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
if [ "$SET" = chat ] || [ "$SET" = all ]; then
# Online Map (/dating)
shot 30-map home --es tf_preview secret-map --ez tf_premium true
shot 31-map-free home --es tf_preview secret-map
shot 32-map-full open --es tf_preview secret-map --ez tf_premium true --es tf_open full
shot 33-map-everyone open --es tf_preview secret-map --ez tf_premium true --es tf_open all
shot 34-map-online open --es tf_preview secret-map --ez tf_premium true --es tf_open online
shot 35-map-end open --es tf_preview secret-map --ez tf_premium true --es tf_open scroll
shot 36-map-moving anim --es tf_preview secret-map --ez tf_premium true
shot 37-map-strong-phone anim --es tf_preview secret-map --ez tf_premium true --es tf_lite 0
# Chats (/secret-app?tab=chat)
shot 40-chats home --es tf_preview secret-chat --ez tf_premium true
shot 41-chats-free home --es tf_preview secret-chat
shot 42-chats-empty home --es tf_preview secret-chat --ez tf_premium true --ez tf_nochats true
shot 43-chat-profile open --es tf_preview secret-chat --ez tf_premium true --es tf_open chatprofile
# one chat (/chat/:id)
shot 50-room home --es tf_preview chatroom --ez tf_premium true
shot 51-room-gifs open --es tf_preview chatroom --ez tf_premium true --es tf_open gif
shot 52-room-gifs-premium open --es tf_preview chatroom --ez tf_premium true --es tf_open gifpremium
shot 53-room-gift open --es tf_preview chatroom --ez tf_premium true --es tf_open gift
shot 54-room-gift-low open --es tf_preview chatroom --ez tf_premium true --es tf_open giftlow
shot 55-room-menu open --es tf_preview chatroom --ez tf_premium true --es tf_open menu
shot 56-room-report open --es tf_preview chatroom --ez tf_premium true --es tf_open report
shot 57-room-held open --es tf_preview chatroom --ez tf_premium true --es tf_open held
shot 58-room-profile open --es tf_preview chatroom --ez tf_premium true --es tf_open profile
shot 59-room-block open --es tf_preview chatroom --ez tf_premium true --es tf_open block
shot 60-room-blocked open --es tf_preview chatroom --ez tf_premium true --es tf_open blocked
shot 61-room-typing open --es tf_preview chatroom --ez tf_premium true --es tf_open typing
shot 62-room-short home --es tf_preview chatroom --ez tf_premium true --es tf_chat m5
shot 63-room-gift-sent animopen --es tf_preview chatroom --ez tf_premium true --es tf_chat m2 --es tf_open sendgif
shot 64-room-strong-phone animopen --es tf_preview chatroom --ez tf_premium true --es tf_lite 0 --es tf_chat m2 --es tf_open gifpremium
fi
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
