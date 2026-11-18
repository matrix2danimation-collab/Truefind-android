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
| `AdminScreen.kt` | native Control Center (`/admin`, admin only): black top with the section tabs, members and overview data, verification decisions |
| `AdTabs1.kt`, `AdVerify.kt`, `AdMembers.kt`, `AdTabs2.kt`, `AdTabs3.kt` | the Control Center sections: Overview, Growth · Verify · Members + member sheet · Reports, Experts, Live, Payouts · Support, Errors, Settings, System |
| `AdKit.kt`, `AdSheets.kt`, `AdMedia.kt`, `AdIcons.kt`, `AdSource.kt`, `AdDemo.kt` | the Control Center's look (cards, buttons, pills, sheets, photo / video viewers), its server calls, and made-up data for the test pictures |
| `AppStatus.kt` | maintenance screen (+ the admin's "Maintenance is ON" pill), announcement card, "account is blocked", error screens ("Something went wrong", "No internet connection"), crash notes for Control Center → Errors |
| `NotifyPrompt.kt` | "Never miss a call" card on Home: asks Android's notifications question once (again after 3 days on "Not now") |

