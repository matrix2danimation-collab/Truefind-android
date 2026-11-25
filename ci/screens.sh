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
# the native Control Center and status screens (their own list, from the misc-admin-native work)
if [ "$SET" = misc ] || [ "$SET" = admin ] || [ "$SET" = health ]; then bash ci/misc-screens.sh "$APK" "$OUT" "$SET"; exit 0; fi
# ── native Recent (/chats): the calls, a person's card and full profile, the messages ──
if [ "$SET" = recent ]; then
R="--es tf_preview recent"
T() { timeout "$@"; }   # every phone command has a time limit: one stuck command can't hold up the rest
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
RESTARTS=0
alive() { [ "$(T 10 adb get-state 2>/dev/null | tr -d '\r')" = device ]; }
host() { { echo "== $1 $(date +%T)"; free -m | head -2; ps -eo pid,rss,vsz,pcpu,comm --sort=-rss | head -4; } >> "$OUT/host.txt"; }
# the phone (emulator) stopped: write down what the computer saw, start the phone again and put the app back on it
restart() {
  RESTARTS=$((RESTARTS+1))
  { echo "== $1: the phone stopped ($(date +%T)) — starting it again (#$RESTARTS)"; free -m | head -2
    sudo dmesg 2>/dev/null | grep -iE "oom|killed process|out of memory|qemu|segfault|general protection|emulator" | tail -15; } >> "$OUT/problems.txt"
  [ $RESTARTS -gt 6 ] && { echo "too many stops: the rest is skipped" >> "$OUT/problems.txt"; return 1; }
  pkill -9 -f qemu-system 2>/dev/null; pkill -9 -f "emulator/emulator" 2>/dev/null; sleep 3
  T 20 adb kill-server > /dev/null 2>&1; T 20 adb start-server > /dev/null 2>&1
  nohup "$SDK/emulator/emulator" -port 5554 -avd test -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none -no-snapshot > "$OUT/emulator-$RESTARTS.log" 2>&1 &
  T 240 adb wait-for-device || return 1
  local t=0
  while [ $t -lt 300 ]; do [ "$(T 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; t=$((t+3)); done
  sleep 5
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do T 15 adb shell settings put global $k 0; done
  T 15 adb shell input keyevent 82
  T 15 adb shell settings put system screen_off_timeout 600000
  T 15 adb shell settings put global hide_error_dialogs 1
  T 240 adb install -r "$APK" > /dev/null || return 1
  echo "   phone back at $(date +%T)" >> "$OUT/problems.txt"
}
rwait() { local t=0; while [ $t -lt "$2" ]; do T 10 adb logcat -d -s TFDBG:I 2>/dev/null | grep -q "$1" && return 0; alive || return 1; sleep 1; t=$((t+1)); done; echo "  (no '$1' after $2 s)" >> "$OUT/start.txt"; }
rshot() {
  local name="$1"; local wait="$2"; shift 2
  [ $RESTARTS -gt 6 ] && return 0
  alive || restart "$name" || return 0
  host "$name"
  T 30 adb shell am force-stop $PKG
  T 30 adb shell pm clear $PKG > /dev/null
  sleep 1
  T 15 adb logcat -c
  # everything the phone writes while this screen opens (kept only if something goes wrong)
  T 300 adb logcat -v threadtime -b main,system,crash > "$OUT/$name.live.txt" 2>&1 &
  local lc=$!
  echo "== $name" >> "$OUT/start.txt"
  T 90 adb shell am start -W -n $PKG/.MainActivity $R "$@" >> "$OUT/start.txt" 2>&1
  case "$*" in
    *tf_open*) rwait "preview open" 60; sleep "$wait" ;;
    *) rwait "frames so far" 60; sleep "$wait" ;;
  esac
  # BACKS=n: the phone's Back n times first (each closes the top pop-up)
  for _ in $(seq 1 "${BACKS:-0}"); do T 15 adb shell input keyevent 4; sleep 1.5; done
  local bad=0
  if alive; then
    T 15 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
    T 60 adb exec-out screencap -p > "$OUT/$name.png" || { echo "$name: no picture" >> "$OUT/problems.txt"; bad=1; }
    # the app must still be open (Back must never close it while a pop-up is open)
    T 20 adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG" || { echo "$name: app not on top" >> "$OUT/problems.txt"; bad=1; }
    T 20 adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
    [ -s "$OUT/$name.crash.txt" ] && bad=1 || rm -f "$OUT/$name.crash.txt"
    { echo "== $name"; T 20 adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
    { echo "== $name"; T 20 adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile|Number "; } >> "$OUT/frames.txt"
  else
    echo "$name: the phone stopped while this screen was open" >> "$OUT/problems.txt"; bad=1
  fi
  sleep 1; kill $lc 2>/dev/null; wait $lc 2>/dev/null
  [ $bad = 1 ] || rm -f "$OUT/$name.live.txt"
  [ -s "$OUT/$name.png" ] || rm -f "$OUT/$name.png"
}
rshot r01-recent 8
# the list alone (no card): scrolled, empty, couldn't load, loading
rshot r12-recent-end 3 --es tf_open scroll
rshot r13-recent-empty 8 --es tf_scene empty
rshot r14-recent-error 8 --es tf_scene error
rshot r15-recent-loading 6 --es tf_scene skeleton
# which part of the small card the test phone can't draw (each one alone first)
rshot d01-picked-row 3 --es tf_open picked
rshot d02-sheet-only 3 --es tf_open sheetonly
rshot d03-card-plain 3 --es tf_open cardplain
rshot d04-card-ready 3 --es tf_open cardready
rshot r02-recent-card 3 --es tf_open card
rshot r03-recent-card-busy 3 --es tf_open cardbusy
rshot r16-recent-card-error 3 --es tf_open card --es tf_scene proferror
rshot r04-recent-full 4 --es tf_open full
rshot r05-recent-full-end 5 --es tf_open fullscroll
rshot r06-recent-photo 5 --es tf_open viewer
rshot r07-recent-coins 3 --es tf_open coins
rshot r08-recent-busy 3 --es tf_open busy --es tf_scene busy
rshot r09-recent-unfollow 4 --es tf_open unfollow
rshot r10-recent-follow-toast 2 --es tf_open follow
# the phone's Back: full profile → card → list; a message closes first
BACKS=1 rshot r17-recent-back-full 4 --es tf_open full
BACKS=2 rshot r18-recent-back-twice 4 --es tf_open full
BACKS=1 rshot r19-recent-back-message 3 --es tf_open coins
BACKS=1 rshot r20-recent-back-photo 5 --es tf_open viewer
alive && T 30 adb logcat -d > "$OUT/logcat-last.txt"
exit 0
fi
# the native Wallet (its own list of screens, from the Wallet work)
if [ "$SET" = wallet ]; then bash ci/wallet-screens.sh "$APK" "$OUT"; exit 0; fi
# only the Google Play payment pictures of the Wallet (25 Nov)
if [ "$SET" = walletplay ]; then ONLY=g bash ci/wallet-screens.sh "$APK" "$OUT"; exit 0; fi
# the native calls (their own list of screens, from the calls work)
if [ "$SET" = calls ]; then bash ci/calls-screens.sh "$APK" "$OUT"; exit 0; fi
# only the microphone-off pictures of the calls (owner, 25 Nov)
if [ "$SET" = callmute ]; then ONLY=c22 bash ci/calls-screens.sh "$APK" "$OUT"; exit 0; fi
# the native Expert Talk, My Dashboard and Secret Dating Earning (their own list, from the expert-dash work)
if [ "$SET" = et ] || [ "$SET" = etx ]; then bash ci/expert-screens.sh "$APK" "$OUT" "$SET"; exit 0; fi
# the native games (their own list of screens, from the Games work)
if [ "$SET" = games ]; then bash ci/games-screens.sh "$APK" "$OUT"; exit 0; fi
if [ "$SET" = fx ]; then bash ci/fx-test.sh "$APK" "$OUT" 1 6; exit 0; fi
if [ "$SET" = fx2 ]; then bash ci/fx-test.sh "$APK" "$OUT" 7 11; exit 0; fi
if [ "$SET" = fx3 ]; then bash ci/fx-test.sh "$APK" "$OUT" 1 0 live; exit 0; fi
# signed out ⇄ signed in against the real server (no account): the sign-in page, the app's own pages, signing out
if [ "$SET" = session ]; then bash ci/session-test.sh "$APK" "$OUT"; exit 0; fi
# the native sign-in pages (their own list of screens, from the Login work)
if [ "$SET" = login ]; then bash ci/login-screens.sh "$APK" "$OUT"; exit 0; fi
# the app against the real Supabase server: sign in with a test number, pages, a live call, log out (ci/supa-test.sh)
if [ "$SET" = supa ]; then bash ci/supa-test.sh "$APK" "$OUT" priya; exit 0; fi
if [ "$SET" = supa2 ]; then bash ci/supa-test.sh "$APK" "$OUT" ravi; exit 0; fi
# Secret Dating map smoothness: frame times while dragging at four zooms (ci/mapperf.sh)
if [ "$SET" = mapperf ]; then bash ci/mapperf.sh "$APK" "$OUT"; exit 0; fi
# what each map layer costs to draw (ci/mapcost.sh)
if [ "$SET" = mapcost ]; then bash ci/mapcost.sh "$APK" "$OUT"; exit 0; fi
if [ "$SET" = home ]; then
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
nshot p09b-settings-search 10 $P profile --es tf_open settings=1,find:coins
nshot p09c-settings-search-none 10 $P profile --es tf_open settings=1,find:zzz
# Settings search with the phone's own keyboard: tap the box, type a word, then tap the setting it found
search_typed() {
  local name=p09d-settings-search-typed box sx sy sb t
  adb shell am force-stop $PKG; adb shell pm clear $PKG > /dev/null; sleep 1; adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  adb shell am start -W -n $PKG/.MainActivity $P profile --es tf_open settings=1 >> "$OUT/start.txt" 2>&1
  waitlog "preview open" 60; sleep 9
  # where the box is: from the screen's own description; if that cannot be read, where it is on this test phone
  box=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' | grep 'class="android.widget.EditText"' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | grep -o '[0-9]*' | tr '\n' ' ')
  set -- $box
  if [ $# -eq 4 ]; then sx=$(( ($1 + $3) / 2 )); sy=$(( ($2 + $4) / 2 )); sb=$4; echo "  search box at $box" >> "$OUT/start.txt"
  else sx=540; sy=559; sb=622; echo "  search box: not read from the screen ($box), using $sx,$sy" >> "$OUT/start.txt"; fi
  kbd() { adb shell dumpsys input_method | grep -o "mInputShown=[a-z]*" | head -1; }
  adb shell input tap $sx $sy
  t=0; while [ $t -lt 30 ] && [ "$(kbd)" != "mInputShown=true" ]; do sleep 1; t=$((t+1)); done
  echo "  after the tap on the box: $(kbd) (waited $t s)" >> "$OUT/start.txt"
  sleep 2
  adb shell input text block; sleep 3
  adb exec-out screencap -p > "$OUT/$name.png"
  # the setting it found is the first row under the box (16 dp gap + half of a 56 dp row = 115 px here)
  adb shell input tap $sx $(( sb + 115 )); sleep 5
  echo "  after the tap on the found row: $(kbd)" >> "$OUT/start.txt"
  adb exec-out screencap -p > "$OUT/p09e-settings-search-open.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
}
search_typed
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
# hold a photo: it shows big over the page (owner, 21 Nov) — the main photo, then the profile photo at the top
nshot p30-photo-hold 4 $P profile --es tf_open peek
nshot p31-photo-hold-top 4 $P profile --es tf_open peek-avatar
nshot b01-builder 9 $P builder
nshot b02-builder-error 10 $P builder --es tf_open error
nshot b03-builder-uploading 10 $P builder --es tf_open uploading
nshot v01-verify-girl 9 $P verify --ez tf_girl true
nshot v02-verify-boy 9 $P verify
nshot v03-verify-pending 10 $P verify --ez tf_girl true --es tf_open pending
fi
# like "shot … home", then taps and swipes on the screen ("x y" = a tap, "x1 y1 x2 y2" = a swipe; ';' between them), then the photo:
# checks that the buttons really answer a finger (the 1080 × 2400 test phone's own points)
tshot() {
  local name="$1" acts="$2" a; shift 2
  adb shell am force-stop $PKG
  adb shell pm clear $PKG > /dev/null
  sleep 1
  adb logcat -c
  echo "== $name" >> "$OUT/start.txt"
  adb shell am start -W -n $PKG/.MainActivity "$@" >> "$OUT/start.txt" 2>&1
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  sleep 4; settle 60
  while IFS= read -r -d ';' a; do
    a=$(echo $a)
    [ -z "$a" ] && continue
    case "$(echo "$a" | wc -w)" in
      1) sleep "${a#w}" ;;                          # "w3": wait 3 s more
      2) adb shell input tap $a < /dev/null ;;      # (adb must not read the list of taps this loop is reading)
      4) adb shell input swipe $a 250 < /dev/null ;;
    esac
    sleep 2
  done <<< "$acts;"
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  { echo "== $name"; adb logcat -d -s TFDBG:I | grep TFDBG; } >> "$OUT/debug.txt"
}
# the Home card's two sizes (owner's second picture, 23 Nov): exactly the picture's (the page scrolls; the one in the app) · as
# tall as the first screen allows
if [ "$SET" = card ]; then
shot c01-exact-first home --es tf_preview secret --ez tf_premium true --ez tf_cardfit false
shot c02-exact-scrolled open --es tf_preview secret --ez tf_premium true --ez tf_cardfit false --es tf_open card
shot c03-fit-first home --es tf_preview secret --ez tf_premium true --ez tf_cardfit true
shot c04-exact-free home --es tf_preview secret --ez tf_cardfit false
shot c05-fit-free home --es tf_preview secret --ez tf_cardfit true
shot c06-exact-favorite open --es tf_preview secret --ez tf_premium true --ez tf_cardfit false --es tf_open favorite
fi
# narrower phones than the test phone (411 wide): 360 and 393 wide — the header's whole "TrueFind", the chips, the card (owner, 23 Nov:
# the name was cut on his phone)
if [ "$SET" = narrow ]; then
adb shell wm density 480 < /dev/null; sleep 4
shot n01-360-home home --es tf_preview secret --ez tf_premium true
shot n02-360-home-free home --es tf_preview secret
shot n03-360-card open --es tf_preview secret --ez tf_premium true --es tf_open card
shot n04-360-chats home --es tf_preview secret-chat --ez tf_premium true
shot n05-360-room home --es tf_preview chatroom --ez tf_premium true
shot n06-360-map home --es tf_preview secret-map --ez tf_premium true
shot n07-360-activity open --es tf_preview secret --ez tf_premium true --es tf_open activity
shot n08-360-profile open --es tf_preview secret --ez tf_premium true --es tf_open profile
shot n09-360-join home --es tf_preview secret --ez tf_notjoined true
adb shell wm density 440 < /dev/null; sleep 4
shot n11-393-home home --es tf_preview secret --ez tf_premium true
shot n12-393-home-free home --es tf_preview secret
shot n13-393-chats home --es tf_preview secret-chat --ez tf_premium true
shot n14-393-card open --es tf_preview secret --ez tf_premium true --es tf_open card
adb shell wm density 540 < /dev/null; sleep 4
shot n21-320-home home --es tf_preview secret --ez tf_premium true
adb shell wm density reset < /dev/null; sleep 3
fi
# My Profile's Settings → True Find's Settings (23 Nov night)
if [ "$SET" = mysettings ]; then
tshot s01-my-profile "w1" --es tf_preview secret --ez tf_premium true --es tf_open my
tshot s02-my-settings "886 246; w4" --es tf_preview secret --ez tf_premium true --es tf_open my
fi
if [ "$SET" = rosetap ]; then
# (the Home card is a little longer than the second picture's since 23 Nov evening — 1.8 × its width: its ✕ ♥ come up with a small
#  scroll, so those taps start from "tf_open card", the page scrolled until the card's top shows)
tshot t01-tap-heart "672 1720" --es tf_preview secret --ez tf_premium true --es tf_open card
tshot t02-tap-skip "409 1720" --es tf_preview secret --ez tf_premium true --es tf_open card
tshot t03-swipe-left "850 1270 250 1270" --es tf_preview secret --ez tf_premium true
tshot t04-swipe-right "850 1270 250 1270; 250 1270 850 1270" --es tf_preview secret --ez tf_premium true
tshot t05-side-card-next "1012 1500" --es tf_preview secret --ez tf_premium true
tshot t06-tap-card "540 1150" --es tf_preview secret --ez tf_premium true
tshot t07-chip-nearby "540 746" --es tf_preview secret --ez tf_premium true
tshot t08-like-then-favorite "672 1720; 540 700 540 2000; 880 746" --es tf_preview secret --ez tf_premium true --es tf_open card
tshot t09-chats-unread-tab "600 457" --es tf_preview secret-chat --ez tf_premium true
tshot t10-chats-search-btn "982 212" --es tf_preview secret-chat --ez tf_premium true
tshot t11-room-emoji "823 2236; 102 2085; 322 2085" --es tf_preview chatroom --ez tf_premium true
tshot t12-room-gift "100 2236" --es tf_preview chatroom --ez tf_premium true
tshot t13-scroll-page "540 1900 540 900" --es tf_preview secret --ez tf_premium true
tshot t14-bar-over-card "701 2224" --es tf_preview secret --ez tf_premium true
tshot t15-free-heart "672 1720" --es tf_preview secret --es tf_open card
tshot t16-free-skip "409 1720" --es tf_preview secret --es tf_open card
tshot t17-small-scroll "540 1700 540 1450" --es tf_preview secret --ez tf_premium true
# 23 Nov evening: a chat row opens the chat wherever it is tapped (photo · name), a demo row a demo chat; "Your Story" adds a story; the bell
tshot t18-chat-photo-opens-chat "148 650" --es tf_preview secret-chat --ez tf_premium true
tshot t19-chat-name-opens-chat "580 880" --es tf_preview secret-chat --ez tf_premium true
tshot t20-demo-chat-photo "148 1804" --es tf_preview secret-chat --ez tf_premium true
tshot t21-demo-chat-name "480 2036" --es tf_preview secret-chat --ez tf_premium true
tshot t22-demo-chat-free "148 1998" --es tf_preview secret-chat
tshot t23-your-story-adds "137 499" --es tf_preview secret --ez tf_premium true
tshot t24-bell "670 216" --es tf_preview secret --ez tf_premium true
tshot t25-story-opens "325 499" --es tf_preview secret --ez tf_premium true
# stories as on Instagram (23 Nov evening): tap on → the next person, swipe left → the next person, swipe down → closed, the logo's heart
tshot t26-story-tap-next "325 499; 900 1300" --es tf_preview secret --ez tf_premium true
tshot t27-story-tap-back "325 499; 900 1300; 120 1300" --es tf_preview secret --ez tf_premium true
tshot t28-story-swipe-next "325 499; 950 1300 150 1300" --es tf_preview secret --ez tf_premium true
tshot t29-story-swipe-down "325 499; 540 800 540 1900" --es tf_preview secret --ez tf_premium true
tshot t30-side-card-back "68 1500" --es tf_preview secret --ez tf_premium true
# 23 Nov night: Settings from My Profile (True Find's own), the map page scrolled (no fade over its foot), the chat list scrolled
tshot t31-my-settings "886 246; w4" --es tf_preview secret --ez tf_premium true --es tf_open my
tshot t32-map-scrolled "540 1900 540 700" --es tf_preview secret-map --ez tf_premium true
tshot t33-chats-scrolled "540 1900 540 900" --es tf_preview secret-chat --ez tf_premium true
tshot t34-home-scrolled "540 1900 540 1300" --es tf_preview secret --ez tf_premium true
fi
# 24 Nov: all of India on the map — capitals (stars), every state, ~7,100 towns, district lines — at each zoom
if [ "$SET" = india ]; then
shot m01-page-india open --es tf_preview secret-map --ez tf_premium true --es tf_open india
shot m02-page-city open --es tf_preview secret-map --ez tf_premium true --es tf_open india-city
shot m03-full-india open --es tf_preview secret-map --ez tf_premium true --es tf_open full-india
shot m04-full-north open --es tf_preview secret-map --ez tf_premium true --es tf_open full-north
shot m05-full-up open --es tf_preview secret-map --ez tf_premium true --es tf_open full-up
shot m06-full-close open --es tf_preview secret-map --ez tf_premium true --es tf_open full-close
shot m07-full-ne open --es tf_preview secret-map --ez tf_premium true --es tf_open full-ne
shot m08-full-south open --es tf_preview secret-map --ez tf_premium true --es tf_open full-south
shot m10-map-default home --es tf_preview secret-map --ez tf_premium true
fi
if [ "$SET" = rose ]; then
shot r01-home home --es tf_preview secret --ez tf_premium true
shot r02-home-free home --es tf_preview secret
shot r03-home-end open --es tf_preview secret --ez tf_premium true --es tf_open scroll
shot r04-nearby open --es tf_preview secret --ez tf_premium true --es tf_open nearby
shot r05-favorite open --es tf_preview secret --ez tf_premium true --es tf_open favorite
shot r06-like quick --es tf_preview secret --ez tf_premium true --es tf_open like
shot r07-profile open --es tf_preview secret --ez tf_premium true --es tf_open profile
shot r08-my open --es tf_preview secret --ez tf_premium true --es tf_open my
shot r09-activity open --es tf_preview secret --ez tf_premium true --es tf_open heart
shot r10-request open --es tf_preview secret --ez tf_premium true --es tf_open request
shot r11-premium open --es tf_preview secret --es tf_open premium
shot r12-chats home --es tf_preview secret-chat --ez tf_premium true
shot r13-chats-unread open --es tf_preview secret-chat --ez tf_premium true --es tf_open unread
shot r14-room home --es tf_preview chatroom --ez tf_premium true
shot r15-room-gifs open --es tf_preview chatroom --ez tf_premium true --es tf_open gif
shot r16-room-emoji open --es tf_preview chatroom --ez tf_premium true --es tf_open emoji
shot r17-room-menu open --es tf_preview chatroom --ez tf_premium true --es tf_open menu
shot r18-room-profile open --es tf_preview chatroom --ez tf_premium true --es tf_open profile
shot r19-map home --es tf_preview secret-map --ez tf_premium true
shot r20-join home --es tf_preview secret --ez tf_notjoined true
shot r21-room-truefind home --es tf_preview chatroom --ez tf_premium true --ez tf_tfchat true
shot r22-chats-search open --es tf_preview secret-chat --ez tf_premium true --es tf_open search
shot r23-chats-free home --es tf_preview secret-chat
# 23 Nov evening: demo people's stories and chats (testing only)
shot r24-demo-profile open --es tf_preview secret --ez tf_premium true --es tf_open demo-profile
shot r24b-demo-profile-new open --es tf_preview secret --ez tf_premium true --es tf_open demo-profile-new
shot r25-demo-request-chat open --es tf_preview secret --ez tf_premium true --es tf_open demo-request
shot r26-demo-request-new open --es tf_preview secret --ez tf_premium true --es tf_open demo-request-new
shot r27-demo-request-free open --es tf_preview secret --es tf_open demo-request-new
shot r28-demo-chat-said open --es tf_preview chatroom --ez tf_premium true --es tf_chat demo-chat-1 --es tf_open say
shot r29-demo-chat-gift quick --es tf_preview chatroom --ez tf_premium true --es tf_chat demo-chat-1 --es tf_open saygif
shot r30-demo-chat-profile open --es tf_preview chatroom --ez tf_premium true --es tf_chat demo-chat-2 --es tf_open profile
shot r31-demo-story story --es tf_preview secret --ez tf_premium true --es tf_open demostory
shot r32-stories-end open --es tf_preview secret --ez tf_premium true --es tf_open storyrow-end
shot r33-demo-chat-list open --es tf_preview secret-chat --ez tf_premium true --es tf_open scroll
fi
if [ "$SET" = sd ]; then
shot 01-explore home --es tf_preview explore
shot 02-secret-free home --es tf_preview secret
shot 03-secret-premium home --es tf_preview secret --ez tf_premium true
shot 03b-secret-join home --es tf_preview secret --ez tf_notjoined true   # not a member yet: the Join card
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
# My Activity (the bell beside Credits): Notifications at its top, then Liked you · You liked · Matches · Requests;
# and a demo profile (3D face; a request to it is stopped) — their own set, the "sd" set is already long
if [ "$SET" = act ]; then
shot 22-activity open --es tf_preview secret --ez tf_premium true --es tf_open activity
shot 22b-activity-heart open --es tf_preview secret --ez tf_premium true --es tf_open heart
shot 23-activity-free open --es tf_preview secret --es tf_open activity
shot 24-activity-you open --es tf_preview secret --ez tf_premium true --es tf_open activity-you
shot 25-activity-match open --es tf_preview secret --ez tf_premium true --es tf_open activity-match
shot 26-activity-req open --es tf_preview secret --ez tf_premium true --es tf_open activity-req
shot 27-activity-girl open --es tf_preview secret --ez tf_girl true --es tf_open activity
shot 28-activity-match-free open --es tf_preview secret --es tf_open activity-match
shot 29-demo-profile open --es tf_preview secret --ez tf_premium true --es tf_open demo-profile
shot 29b-demo-request quick --es tf_preview secret --ez tf_premium true --es tf_open demo-request
fi
if [ "$SET" = chat ] || [ "$SET" = all ]; then
# Online Map (/dating)
shot 30-map home --es tf_preview secret-map --ez tf_premium true
shot 31-map-free home --es tf_preview secret-map
shot 32-map-full open --es tf_preview secret-map --ez tf_premium true --es tf_open full
shot 33-map-everyone open --es tf_preview secret-map --ez tf_premium true --es tf_open all
shot 34-map-online open --es tf_preview secret-map --ez tf_premium true --es tf_open online
shot 35-map-end open --es tf_preview secret-map --ez tf_premium true --es tf_open scroll
shot 38-map-near open --es tf_preview secret-map --ez tf_premium true --es tf_open near
# closer in (names never on top of each other or of a person; pins split up as you zoom)
shot 39a-map-zoom-north open --es tf_preview secret-map --ez tf_premium true --es tf_open zoom-north
shot 39b-map-zoom-haryana open --es tf_preview secret-map --ez tf_premium true --es tf_open zoom-haryana
shot 39c-map-zoom-delhi open --es tf_preview secret-map --ez tf_premium true --es tf_open zoom-delhi
shot 39d-map-zoom-south open --es tf_preview secret-map --ez tf_premium true --es tf_open zoom-south
shot 36-map-moving anim --es tf_preview secret-map --ez tf_premium true
shot 37-map-strong-phone anim --es tf_preview secret-map --ez tf_premium true --es tf_lite 0
# Chats (/secret-app?tab=chat)
shot 40-chats home --es tf_preview secret-chat --ez tf_premium true
shot 41-chats-free home --es tf_preview secret-chat
shot 42-chats-empty home --es tf_preview secret-chat --ez tf_premium true --ez tf_nochats true
shot 43-chat-row-opens-chat open --es tf_preview secret-chat --ez tf_premium true --es tf_open chatrow
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
shot 65-room-toast quick --es tf_preview chatroom --ez tf_premium true --es tf_open toast
shot 66-room-story story --es tf_preview chatroom --ez tf_premium true --es tf_open story
shot 67-room-not-found open --es tf_preview chatroom --ez tf_premium true --es tf_chat zz --es tf_open error
fi
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
