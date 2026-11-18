#!/usr/bin/env bash
# Signed out ⇄ signed in, on the test phone against the real server (no account is used):
#  1. a fresh install opens: no sign-in on the phone → the sign-in web page is made (hidden) and the app's
#     own "Get Started" page shows over it                                          → s01-get-started.png
#  2. the app is closed and a sign-in the server does not know is put on the phone (as if someone was
#     signed in): it opens straight into its own pages, without any web page        → s02-own-pages.png
#     the server answers "not signed in" → the app signs out by itself: the sign-in
#     web page is made, the server's log-out runs, "Get Started" shows              → s03-signed-out.png
#  3. opened once more: nothing is kept → "Get Started" again                       → s04-opened-again.png
# session-log.txt: what the app wrote about each change ("mode: …"); problems.txt: what did not happen.
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"
ADB="$(command -v adb)"
adb() { timeout 120 "$ADB" "$@"; }
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null

# waits until the app has written [1] ([2] seconds at most)
waitlog() {
  local t=0
  while [ $t -lt "$2" ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done
  echo "part $PART: no '$1' after $2 s" >> "$OUT/problems.txt"; return 1
}
shot() { timeout 40 "$ADB" exec-out screencap -p > "$OUT/$1.png"; [ -s "$OUT/$1.png" ] || rm -f "$OUT/$1.png"; }
keep() {
  { echo "== part $PART"; adb logcat -d -s TFDBG:I | grep -E "mode:" ; } >> "$OUT/session-log.txt"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" >> "$OUT/crash.txt"
  [ -s "$OUT/crash.txt" ] || rm -f "$OUT/crash.txt"
}
open() {
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity >> "$OUT/start.txt" 2>&1
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
}

# ── 1. a fresh install ──
PART=1
open
waitlog "mode: start: no sign-in on this phone" 60
waitlog "mode: web page made" 30
waitlog "mode: sign-in page shown" 150 && sleep 6
shot s01-get-started
keep

# ── 2. a sign-in the server does not know ──
PART=2
adb shell am force-stop $PKG
sleep 1
adb shell run-as $PKG cat shared_prefs/tf_session.xml > "$OUT/kept-on-phone.txt" 2>&1
if grep -q 'name="app_id"' "$OUT/kept-on-phone.txt"; then
  sed 's#</map>#    <string name="token">eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJjaS10ZXN0LW5vdC1hLXJlYWwtc2lnbi1pbiJ9.Y2ktdGVzdA</string>\n</map>#' "$OUT/kept-on-phone.txt" > "$OUT/put-on-phone.xml"
  timeout 60 "$ADB" shell "run-as $PKG sh -c 'cat > shared_prefs/tf_session.xml'" < "$OUT/put-on-phone.xml"
  if ! adb shell run-as $PKG cat shared_prefs/tf_session.xml | grep -q 'name="token"'; then
    # (older ways of this phone) the same through a file
    adb push "$OUT/put-on-phone.xml" /data/local/tmp/tf_session.xml > /dev/null 2>&1
    adb shell run-as $PKG cp /data/local/tmp/tf_session.xml shared_prefs/tf_session.xml
  fi
  rm -f "$OUT/put-on-phone.xml"
  if adb shell run-as $PKG cat shared_prefs/tf_session.xml | grep -q 'name="token"'; then
    open
    if waitlog "mode: start: sign-in kept on this phone" 60; then
      waitlog "mode: native" 20 && sleep 2
      shot s02-own-pages
      waitlog "mode: the server no longer accepts this sign-in" 90
      waitlog "mode: signing out" 30
      waitlog "mode: web page made" 30
      waitlog "mode: the sign-in pages lead" 120
      waitlog "mode: sign-in page shown" 60 && sleep 6
      shot s03-signed-out
    fi
    keep
    # nothing of the sign-in may be left on the phone
    adb shell run-as $PKG cat shared_prefs/tf_session.xml > "$OUT/kept-after-sign-out.txt" 2>&1
    grep -q 'name="token"' "$OUT/kept-after-sign-out.txt" && echo "part 2: the sign-in is still kept on the phone after signing out" >> "$OUT/problems.txt"

    # ── 3. opened once more ──
    PART=3
    adb shell am force-stop $PKG
    sleep 1
    open
    waitlog "mode: start: no sign-in on this phone" 60
    waitlog "mode: sign-in page shown" 150 && sleep 6
    shot s04-opened-again
    keep
  else
    echo "part 2: the test sign-in could not be put on the phone" >> "$OUT/problems.txt"
  fi
else
  echo "part 2: the app did not keep the app's id from the sign-in page (kept-on-phone.txt)" >> "$OUT/problems.txt"
fi
timeout 30 "$ADB" logcat -d -b crash > "$OUT/crash-log.txt" 2>/dev/null
[ -s "$OUT/crash-log.txt" ] || rm -f "$OUT/crash-log.txt"
[ -f "$OUT/problems.txt" ] || echo "all parts happened as expected" > "$OUT/ok.txt"
exit 0
