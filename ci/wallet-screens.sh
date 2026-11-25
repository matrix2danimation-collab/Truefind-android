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
  # ONLY=<start of a name>: just those pictures (a quick set)
  [ -n "$ONLY" ] && [ "${name#$ONLY}" = "$name" ] && return 0
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity "$@" > /dev/null
  sleep "$wait"
  # the emulator's own "Pixel Launcher isn't responding" box covered the middle of the pictures: closed first
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 1
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AndroidRuntime:E | grep -A40 "Process: $PKG" > "$OUT/$name.crash.txt"
  [ -s "$OUT/$name.crash.txt" ] || rm -f "$OUT/$name.crash.txt"
  # which server functions the Wallet asked for (made-up data): no needless asks
  adb logcat -d -s TFWALLET:I | grep "asked" > "$OUT/$name.asked.txt"
  [ -s "$OUT/$name.asked.txt" ] || rm -f "$OUT/$name.asked.txt"
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
shot 15b-secret-plan-pay 10 --es tf_wallet secret-plan --es tf_open planpay
shot 16-secret-girl-verify 8 --es tf_wallet secret-girl
shot 17-earnings 9 --es tf_wallet tf-earn --es tf_open earnings
shot 18-earnings-pending 9 --es tf_wallet tf-earn-pending --es tf_open earnings
shot 19-earnings-amount 10 --es tf_wallet tf-earn --es tf_open draft
shot 20-coins-tab-creator 8 --es tf_wallet tf-earn
# the owner's endless Secret Dating loading (21 Nov): plans show at once; with no answer the page stops after 12 s
shot 21-secret-wallet-hang-4s 4 --es tf_wallet secret-wallethang
shot 22-secret-wallet-hang-15s 15 --es tf_wallet secret-wallethang
shot 23-secret-credits-hang-4s 4 --es tf_wallet secret-credhang
shot 24-secret-credits-hang-15s 15 --es tf_wallet secret-credhang
shot 25-secret-fail 6 --es tf_wallet secret-fail
shot 26-secret-both-hang-15s 15 --es tf_wallet secret-wallethang-credhang
# Google Play payments (25 Nov): the Pay button while Google's screen opens, then the app's own screens after it
# (Google's payment screen itself can't show on the test phone)
shot g01-pay-opening 9 --es tf_wallet tf --es tf_open gopening
shot g02-processing 8 --es tf_wallet tf --es tf_open gprocessing
shot g03-success 9 --es tf_wallet tf --es tf_open gdone
shot g04-pending 8 --es tf_wallet tf --es tf_open gpending
shot g05-failed 8 --es tf_wallet tf --es tf_open gfailed
shot g06-coming-soon 8 --es tf_wallet tf --es tf_open soon
shot g07-coming-soon-admin 8 --es tf_wallet tf-admin --es tf_open gsoon-admin
shot g08-plan-done 9 --es tf_wallet secret-plan --es tf_open gplan-done
shot g09-plan-pending 9 --es tf_wallet secret-plan --es tf_open gplan-pending
adb logcat -d > "$OUT/logcat-last.txt"
ls -la "$OUT"
