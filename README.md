# True Find — Android app

The Android app of True Find.

- **Whole app = the Base44 web app** (`https://true-find.base44.app`) inside a WebView: login, Home, Secret Dating, Wallet, Recent, calls, games — all unchanged, all still edited and published from Base44.
- **Explore is native** (drawn by the phone itself, not a web page) for smooth scrolling. It uses the same Base44 backend functions as the web Explore: `getOnlineNow`, `getWallet`, `getAppConfig`, `startCall`, `follow`. Login is shared with the web page (same token), so the user signs in once.
- The web's own bottom bar stays visible under native Explore.
- **Random Match is native** too: Home → Random Match → Voice Call / Video Call opens the app's own price sheet (Price, Your balance, Add coins, Find someone), the "Finding someone for you…" / "Calling …" screen and the same pop-ups as the web (No one is free right now, Couldn't connect, Add coins to call). It picks people with the web's exact rules (getOnlineNow → free, other gender, shuffled, last match last, up to 4 tried with `startCall`), then opens the web call page `/call/{id}`. A native Home can open it with `MainActivity.openRandomMatch(type)`.

## Test pictures

Branch `random-match-native`: every push builds the APK (not put on Releases) and photographs the Random Match screens on an emulator (`ci/screens-random.sh`, test mode `--es tf_preview random --es tf_open sheet|search|calling|…`, made-up data, no login). The pictures are kept on the branch `screens-random`.

## Build

Every push to `main` builds a test APK with GitHub Actions:

- **Releases → "Test build" → `TrueFind-test.apk`** (open on the phone and install), or
- **Actions → latest run → Artifacts → `TrueFind-test-apk`**.

## Files

| File | What it is |
|---|---|
| `MainActivity.kt` | WebView, mic/camera/location/file-upload permissions, UPI payment links, back button |
| `WebBridge.kt` | link with the web page: address, login token, bottom bar position, opening web pages |
| `ExploreScreen.kt` | native Explore: header, chips, Voice/Video/Random, busy sheet, dialogs, calls |
| `FeedView.kt` | the moving bubbles feed and its still background |
| `CallStageView.kt` | "Finding someone…" / "Calling …" screen (Random Match adds the web's doodles, panel slide and blinking dot) |
| `RandomMatch.kt` | native Random Match: price sheet, search / calling, pop-ups, matching |
| `RandomPick.kt` | who Random Match rings, in what order (the web's rules; plain Kotlin, checked on a computer) |
| `Doodles.kt` | the call screens' doodle pattern and the plain default avatar |
| `AvatarArt.kt` | drawn faces for profiles without a photo (same as web) |
| `Photos.kt`, `Api.kt`, `Person.kt`, `Ui.kt`, `Widgets.kt` | helpers |
