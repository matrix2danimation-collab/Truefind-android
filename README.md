# True Find — Android app

The Android app of True Find.

- **Whole app = the Base44 web app** (`https://true-find.base44.app`) inside a WebView: login, Home, Secret Dating, Wallet, Recent, calls, games — all unchanged, all still edited and published from Base44.
- **Explore is native** (drawn by the phone itself, not a web page) for smooth scrolling. It uses the same Base44 backend functions as the web Explore: `getOnlineNow`, `getWallet`, `getAppConfig`, `startCall`, `follow`. Login is shared with the web page (same token), so the user signs in once.
- The web's own bottom bar stays visible under native Explore.

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
| `CallStageView.kt` | "Finding someone…" / "Calling …" screen |
| `AvatarArt.kt` | drawn faces for profiles without a photo (same as web) |
| `Photos.kt`, `Api.kt`, `Person.kt`, `Ui.kt`, `Widgets.kt` | helpers |
