#!/usr/bin/env bash
# TEST ONLY — runs inside the Android emulator job (wallet-screens.yml): installs
# the test APK and photographs the native Wallet with made-up data (WalletDemo),
# no login, no web page. Output: $2/*.png (+ crash logs if any).
APK="$1"; OUT="$2"
PKG=app.truefind.android
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity "$@" > /dev/null
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
}
shot 01-truefind 7 --es tf_wallet tf
shot 02-first-offer 7 --es tf_wallet tf-new
shot 03-pack-tapped 8 --es tf_wallet tf --es tf_open pick
shot 04-offer-tapped 8 --es tf_wallet tf-new --es tf_open pick
shot 05-coming-soon 8 --es tf_wallet tf --es tf_open soon
shot 06-payment-method 8 --es tf_wallet tf-admin --es tf_open method
shot 07-choose-pack 8 --es tf_wallet tf-admin --es tf_open pack
shot 08-processing 8 --es tf_wallet tf --es tf_open processing
shot 09-success 9 --es tf_wallet tf --es tf_open done
shot 10-failed 8 --es tf_wallet tf --es tf_open failed
shot 11-transaction 8 --es tf_wallet tf --es tf_open tx
shot 12-all-transactions 9 --es tf_wallet tf --es tf_open alltx
shot 13-help 9 --es tf_wallet tf --es tf_open help
shot 14-secret 8 --es tf_wallet secret
shot 15-secret-plan 9 --es tf_wallet secret-plan --es tf_open plan
shot 16-secret-girl-verify 8 --es tf_wallet secret-girl
shot 17-earnings 9 --es tf_wallet tf-earn --es tf_open earnings
shot 18-earnings-pending 9 --es tf_wallet tf-earn-pending --es tf_open earnings
shot 19-earnings-amount 10 --es tf_wallet tf-earn --es tf_open draft
shot 20-coins-tab-creator 8 --es tf_wallet tf-earn
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
