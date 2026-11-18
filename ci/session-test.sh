#!/usr/bin/env bash
# Signed out ⇄ signed in, on the test phone against the real server. No account is used: the "sign-in" is a
# made-up one the server does not know (the app is told not to ask the server about it, except in part 5).
#  1. a fresh install opens: no sign-in on the phone → the sign-in web page is made (hidden) and the app's
#     own "Get Started" page shows over it                                             → s01-get-started.png
#  2. a sign-in is put on the phone: the app opens straight into its own pages, no web page → s02-own-pages.png
#  3. a sign-in page is opened from inside the app (as "Complete my profile" does): the web page is made and
#     gets the app's sign-in, the native sign-in page shows                            → s03-sign-in-page.png
#     Back: the app's page again, the web page goes                                    → s04-back-in-the-app.png
#  4. the sign-in is kept in the web page's storage only (as on every phone signed in before this version):
#     the app opens, takes it over from the web page, shows its own pages, the web page goes → s05-taken-over.png
#  5. opened normally: the server answers "not signed in" → the app signs out by itself (the web page is
#     made, the server's log-out runs, "Get Started" shows)                            → s06-signed-out.png
#  6. opened once more: nothing is kept anywhere → "Get Started" again                 → s07-opened-again.png
# session-log.txt: what the app wrote about each change ("mode: …"); problems.txt: what did not happen.
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"
ADB="$(command -v adb)"
adb() { timeout 120 "$ADB" "$@"; }
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null
NOCHECK="--ez tf_test_nocheck true"
TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJjaS10ZXN0LW5vdC1hLXJlYWwtc2lnbi1pbiJ9.Y2ktdGVzdA"

# waits until the app has written [1] ([2] seconds at most); [3] = "quiet": not a problem when it does not come
waitlog() {
  local t=0
  while [ $t -lt "$2" ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done
  [ "$3" = quiet ] || echo "part $PART: no '$1' after $2 s" >> "$OUT/problems.txt"; return 1
}
said() { adb logcat -d -s TFDBG:I | grep -q "$1"; }
shot() { timeout 40 "$ADB" exec-out screencap -p > "$OUT/$1.png"; [ -s "$OUT/$1.png" ] || rm -f "$OUT/$1.png"; }
keep() {
  { echo "== part $PART"; adb logcat -d -s TFDBG:I | grep -E "mode:" ; } >> "$OUT/session-log.txt"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" >> "$OUT/crash.txt"
  [ -s "$OUT/crash.txt" ] || rm -f "$OUT/crash.txt"
}
open() {
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity "$@" >> "$OUT/start.txt" 2>&1
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
}
# what the app keeps on the phone (its private file), with the sign-in itself left out of the copy
kept() { adb shell run-as $PKG cat shared_prefs/tf_session.xml 2>&1 | tr -d '\r'; }
copy_kept() { kept | sed 's#\(name="token">\)[^<]*#\1(a sign-in)#' > "$OUT/$1"; }
put() {   # stdin: the file's new text
  cat > "$OUT/.put.xml"
  timeout 60 "$ADB" shell "run-as $PKG sh -c 'cat > shared_prefs/tf_session.xml'" < "$OUT/.put.xml"
  if [ "$(kept | md5sum)" != "$(tr -d '\r' < "$OUT/.put.xml" | md5sum)" ]; then
    adb push "$OUT/.put.xml" /data/local/tmp/tf_session.xml > /dev/null 2>&1
    adb shell run-as $PKG cp /data/local/tmp/tf_session.xml shared_prefs/tf_session.xml
  fi
  rm -f "$OUT/.put.xml"
}

# ── 1. a fresh install ──
PART=1
open
waitlog "mode: start: no sign-in on this phone" 60
waitlog "mode: web page made" 30
waitlog "mode: sign-in page shown" 150 && sleep 6
shot s01-get-started
keep

# ── 2. a sign-in on the phone: straight into the app's own pages ──
PART=2
adb shell am force-stop $PKG
sleep 1
copy_kept kept-1-after-first-open.txt
if ! kept | grep -q 'name="app_id"'; then
  echo "part 2: the app did not keep the app's id from the sign-in page (kept-1-after-first-open.txt)" >> "$OUT/problems.txt"
  exit 0
fi
kept | sed "s#</map>#    <string name=\"token\">$TOKEN</string>\n</map>#" | put
if ! kept | grep -q 'name="token"'; then echo "part 2: the test sign-in could not be put on the phone" >> "$OUT/problems.txt"; exit 0; fi
open $NOCHECK
waitlog "mode: start: sign-in kept on this phone" 60
waitlog "mode: native" 20 && sleep 4
said "mode: web page made" && echo "part 2: a web page was made although the phone had a sign-in" >> "$OUT/problems.txt"
shot s02-own-pages
keep

# ── 3. a sign-in page opened from inside the app, and Back ──
PART=3
adb logcat -c
adb shell am start -n $PKG/.MainActivity -a android.intent.action.VIEW -d "https://true-find.base44.app/welcome" >> "$OUT/start.txt" 2>&1
waitlog "mode: leaving for the sign-in page /welcome" 30
waitlog "mode: web page made" 30
waitlog "mode: the web page gets the app's sign-in" 90
waitlog "mode: the sign-in pages lead" 150 && sleep 6
shot s03-sign-in-page
for i in 1 2 3; do
  adb shell input keyevent 4
  waitlog "mode: Back on the sign-in page" 8 quiet && break
done
waitlog "mode: Back on the sign-in page: the app's page again" 3
waitlog "mode: web page gone" 20 && sleep 4
shot s04-back-in-the-app
keep

# ── 4. the sign-in only in the web page's storage: the app takes it over ──
PART=4
sleep 12   # (the web page's storage is written to the phone a few seconds after it changes)
adb shell am force-stop $PKG
sleep 1
kept | grep -v 'name="token"' | put
if kept | grep -q 'name="token"'; then echo "part 4: the app's own copy of the sign-in could not be taken away" >> "$OUT/problems.txt"; fi
open $NOCHECK
waitlog "mode: start: no sign-in on this phone" 60
waitlog "mode: web page made" 30
waitlog "mode: signed in on the web page" 150
waitlog "mode: native" 20
waitlog "mode: web page gone" 20 && sleep 4
shot s05-taken-over
keep
adb shell am force-stop $PKG
sleep 1
copy_kept kept-4-after-take-over.txt
kept | grep -q 'name="token"' || echo "part 4: the app did not keep the sign-in it took over from the web page" >> "$OUT/problems.txt"

# ── 5. opened normally: the server does not know this sign-in → the app signs out by itself ──
PART=5
open
waitlog "mode: start: sign-in kept on this phone" 60
waitlog "mode: the server no longer accepts this sign-in" 90
waitlog "mode: signing out" 30
waitlog "mode: web page made" 30
waitlog "mode: the sign-in pages lead" 150
waitlog "mode: sign-in page shown" 60 && sleep 6
shot s06-signed-out
keep
adb shell am force-stop $PKG
sleep 1
copy_kept kept-5-after-sign-out.txt
kept | grep -q 'name="token"' && echo "part 5: the sign-in is still kept on the phone after signing out" >> "$OUT/problems.txt"

# ── 6. opened once more: nothing kept anywhere ──
PART=6
open
waitlog "mode: start: no sign-in on this phone" 60
waitlog "mode: sign-in page shown" 150 && sleep 6
said "mode: signed in on the web page" && echo "part 6: the web page still held the sign-in after signing out" >> "$OUT/problems.txt"
shot s07-opened-again
keep

timeout 30 "$ADB" logcat -d -b crash > "$OUT/crash-log.txt" 2>/dev/null
[ -s "$OUT/crash-log.txt" ] || rm -f "$OUT/crash-log.txt"
[ -f "$OUT/problems.txt" ] || echo "all 6 parts happened as expected" > "$OUT/ok.txt"
exit 0
