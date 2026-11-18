#!/usr/bin/env bash
# Signed out ⇄ signed in, on the test phone against the real server. No account is used: the "sign-in" is a
# made-up one the server does not know (the app is told not to ask the server about it, except in part 5).
#  1. a fresh install opens: no sign-in on the phone → the sign-in web page is made (hidden) and the app's
#     own "Get Started" page shows over it                                               → s01-get-started.png
#  2. signing in ends: the web page gets a sign-in and opens Home (what a finished sign-in does, and what a
#     phone signed in before this version looks like when it opens): the app takes the sign-in over, shows
#     its own pages and the web page goes                                                → s02-taken-over.png
#  3. opened again: the sign-in is kept by the app → straight into its own pages, no web page → s03-own-pages.png
#  4. a sign-in page is opened from inside the app (as "Complete my profile" does): the web page is made
#     with the app's sign-in and the native sign-in page shows                           → s04-sign-in-page.png
#  5. opened normally: the server answers "not signed in" → the app signs out by itself (the web page is
#     made, the server's log-out runs, "Get Started" shows)                              → s05-signed-out.png
#  6. opened once more: nothing is kept anywhere → "Get Started" again                   → s06-opened-again.png
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
# what the app keeps on the phone (its private file); the copies for reading leave the sign-in itself out
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
# one line of JavaScript in the app's web page (debug builds let the test phone's tools reach it)
in_page() {
  local pid; pid=$(adb shell pidof $PKG | tr -d '\r ')
  [ -n "$pid" ] || { echo "(the app is not running)"; return 1; }
  adb forward tcp:9222 "localabstract:webview_devtools_remote_$pid" > /dev/null 2>&1 || { echo "(no way into the web page)"; return 1; }
  timeout 60 python3 "$(dirname "$0")/devtools.py" 9222 "$1" 2>&1
  local r=$?
  adb forward --remove tcp:9222 > /dev/null 2>&1
  return $r
}

# ── 1. a fresh install ──
PART=1
open
waitlog "mode: start: no sign-in on this phone" 60
waitlog "mode: web page made" 30
waitlog "mode: sign-in page shown" 150 && sleep 6
shot s01-get-started
keep

# ── 2. a sign-in ends in the web page: the app takes it over ──
PART=2
adb shell am force-stop $PKG
sleep 1
copy_kept kept-1-after-first-open.txt
kept | grep -q 'name="app_id"' || echo "part 2: the app did not keep the app's id from the sign-in page (kept-1-after-first-open.txt)" >> "$OUT/problems.txt"
open $NOCHECK
waitlog "mode: sign-in page shown" 150 && sleep 3
{ echo "== where the web page is"; in_page "location.pathname + ' · sign-in kept: ' + !!localStorage.getItem('base44_access_token')"; } >> "$OUT/web-page.txt" 2>&1
{ echo "== the sign-in is given to the web page, which opens Home"
  in_page "(function(){try{localStorage.setItem('base44_access_token','$TOKEN');localStorage.setItem('token','$TOKEN')}catch(e){return 'storage: '+e}setTimeout(function(){location.replace('/home')},50);return 'given'})()"; } >> "$OUT/web-page.txt" 2>&1
TOOK=no
if waitlog "mode: signed in on the web page" 90; then
  TOOK=yes
  waitlog "mode: native" 20
  waitlog "mode: web page gone" 20 && sleep 4
fi
shot s02-taken-over
keep
adb shell am force-stop $PKG
sleep 1
copy_kept kept-2-after-take-over.txt
if [ $TOOK = yes ]; then
  kept | grep -q 'name="token"' || echo "part 2: the app did not keep the sign-in it took over from the web page" >> "$OUT/problems.txt"
fi
# (the next parts need a sign-in on the phone either way)
if ! kept | grep -q 'name="token"'; then
  kept | sed "s#</map>#    <string name=\"token\">$TOKEN</string>\n</map>#" | put
fi
if ! kept | grep -q 'name="token"'; then echo "part 3: the test sign-in could not be put on the phone" >> "$OUT/problems.txt"; exit 0; fi

# ── 3. opened again: the app's own sign-in, no web page ──
PART=3
open $NOCHECK
waitlog "mode: start: sign-in kept on this phone" 60
waitlog "mode: native" 20 && sleep 4
said "mode: web page made" && echo "part 3: a web page was made although the phone had a sign-in" >> "$OUT/problems.txt"
shot s03-own-pages
keep

# ── 4. a sign-in page opened from inside the app ──
PART=4
adb logcat -c
adb shell am start -n $PKG/.MainActivity -a android.intent.action.VIEW -d "https://true-find.base44.app/welcome" >> "$OUT/start.txt" 2>&1
waitlog "mode: leaving for the sign-in page /welcome" 30
waitlog "mode: web page made" 30
waitlog "mode: the sign-in pages lead" 150 && sleep 5
shot s04-sign-in-page
keep

# ── 5. opened normally: the server does not know this sign-in → the app signs out by itself ──
PART=5
adb shell am force-stop $PKG
sleep 1
# (the web page has thrown the made-up sign-in away by now, and the app with it: it is put back for this part)
if ! kept | grep -q 'name="token"'; then
  kept | sed "s#</map>#    <string name=\"token\">$TOKEN</string>\n</map>#" | put
fi
open
waitlog "mode: start: sign-in kept on this phone" 60
waitlog "mode: the server no longer accepts this sign-in" 90
waitlog "mode: signing out" 30
waitlog "mode: web page made" 30
waitlog "mode: the sign-in pages lead" 150
waitlog "mode: sign-in page shown" 60 && sleep 6
shot s05-signed-out
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
shot s06-opened-again
keep

timeout 30 "$ADB" logcat -d -b crash > "$OUT/crash-log.txt" 2>/dev/null
[ -s "$OUT/crash-log.txt" ] || rm -f "$OUT/crash-log.txt"
[ -f "$OUT/problems.txt" ] || echo "all 6 parts happened as expected" > "$OUT/ok.txt"
exit 0
