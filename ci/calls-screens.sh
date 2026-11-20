#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the test APK and photographs the native call screens in
# test mode (made-up people and numbers, no login, nobody is called). Output: $2/*.png (+ crash logs if any)
APK="$1"; OUT="${2:?}"
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb shell pm grant $PKG android.permission.CAMERA || true
# the slow CI emulator's own "System UI isn't responding" box would cover the pictures: don't show it
adb shell settings put global hide_error_dialogs 1 || true
on_top() { adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "$PKG"; }
shot() {
  local name="${1:?}"; local wait="$2"; shift 2
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity "$@" > /dev/null
  sleep "$wait"
  # if the app isn't in front (emulator hiccup), start it once more
  if ! on_top; then adb shell am start -W -n $PKG/.MainActivity "$@" > /dev/null; sleep "$wait"; fi
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "${OUT:?}/${name:?}.png"
  # a crash log only when the app crashed
  local crash; crash=$(adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG")
  [ -n "$crash" ] && printf '%s\n' "$crash" > "${OUT:?}/${name:?}.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I TFCALL:I | tail -5; } >> "${OUT:?}/debug.txt"
}
C="--es tf_call"
# Agora's call engine has to load on a phone: made and set up once like a video call, nobody is called
adb shell am force-stop $PKG; adb logcat -c
adb shell am start -W -n $PKG/.MainActivity $C call-check > /dev/null
sleep 12
{ adb logcat -d -s TFCALL:I; adb logcat -d -s AndroidRuntime:E; } > "${OUT:?}/call-check.txt"
cat "${OUT:?}/call-check.txt"
# incoming call (Accept / Decline, the phone's ringtone)
shot c01-incoming-voice 11 $C incoming-voice
shot c02-incoming-video 11 $C incoming-video
shot c03-incoming-connecting 11 $C incoming-busy
shot c04-incoming-allow-mic 11 $C incoming-note
shot c05-incoming-girl 11 $C incoming-voice --ez tf_girl true
# before pick-up / not connected (the stage)
shot c10-ringing 11 $C ringing
shot c11-connecting 11 $C connecting
shot c12-declined 11 $C declined
shot c13-no-answer 11 $C missed
shot c14-connect-error 11 $C stage-error
# the voice call
shot c20-voice-she-talks 12 $C voice-talkp
shot c21-voice-both-talk 12 $C voice-talkmp
shot c22-voice-muted-speaker 12 $C voice-talkp-muted-speaker
shot c23-voice-heart-sent 13 $C voice-liked-talkp --es tf_open toast
shot c24-voice-she-liked-you 13 $C voice-theyliked-talkm --es tf_open liketoast
shot c25-voice-low-balance 12 $C voice-low-talkp
shot c26-voice-reconnecting 12 $C voice-reconnect
shot c27-voice-ended 12 $C voice-ended-charged
shot c28-voice-ended-out-of-coins 12 $C voice-ended-ranout-charged
# voice → video: Video tapped on a voice call, she says yes (the app used to close here: owner, 21 Nov)
shot c29-voice-to-video 16 $C voice-talkp --es tf_open switch
# after the call: the stars
shot c30-rating 13 $C voice-ended --es tf_open rating
shot c31-rating-report 13 $C voice-ended --es tf_open rating2
shot c32-rating-thanks 13 $C voice-ended --es tf_open thanks
# during the call: Report, Add coins, Go to Chat
shot c35-report 13 $C voice-talkp --es tf_open report
shot c36-add-coins 13 $C voice-talkp --es tf_open addcoins
shot c37-go-to-chat 13 $C voice-liked --es tf_open peer
shot c38-chat 13 $C voice-liked --es tf_open peerchat
shot c39-chat-free-used 13 $C voice-liked --es tf_open peerlocked
shot c40-go-premium 13 $C voice-liked --es tf_open premium
# the video call
shot c50-video 12 $C video-talkp
shot c51-video-her-camera-off 12 $C video-peeroff-talkp
shot c52-video-my-camera-off 12 $C video-camoff-talkm
shot c53-video-buttons-hidden 13 $C video-talkp --es tf_open hidden
shot c54-video-heart-sent 13 $C video-liked --es tf_open toast
shot c55-video-ended 12 $C video-ended-charged
# "Call in progress · Return" on Home, and a call that can't be opened
shot c60-return-pill 12 $C pill
shot c61-call-not-found 11 $C error
adb logcat -d > "${OUT:?}/logcat-last.txt"
ls -la "$OUT"
