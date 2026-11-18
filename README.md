# True Find — Android app

The Android app of True Find.

- **Every screen is drawn by the phone itself** (native). The backend stays on Base44: the same functions and data as the website (`https://true-find.base44.app`), called directly by the app.
- **Signed in, there is no web page at all.** The app keeps where you are and where Back goes (`Router.kt`), keeps the sign-in (`Session.kt`) and does what the web page used to do in the background: online / offline for Explore, the red number on Find, "who am I", expert accounts, the small photo copy (`SessionWork`, `ThumbBackfill`). Before, the whole web app ran hidden under the native screens and every page change went through it — two apps at once, which made page moves hang.
- **Only signing in and signing out still use a web page for a moment**, hidden under the native sign-in pages (`LoginLayer`): Base44's one-time sign-in link, Google's page and the server's log-out only work in a browser. It is made when it is needed and thrown away as soon as you are in.
- Notifications while the app is closed (calls ring, messages, missed calls) come through Firebase (`Push.kt`) when the build has `GOOGLE_SERVICES_JSON` (GitHub → Settings → Secrets → Actions).

## Build

Every push to `main` builds a test APK with GitHub Actions:

- **Releases → "Test build" → `TrueFind-test.apk`** (open on the phone and install), or
- **Actions → latest run → Artifacts → `TrueFind-test-apk`**.

## Files

| File | What it is |
|---|---|
| `MainActivity.kt` | puts the screens together: which page shows, the moves between pages, signed in ⇄ sign-in pages, permissions, the phone's Back |
| `Router.kt` | the app's own history: where you are, where Back goes (the fixed steps Wallet → Recent → Explore → Home, Profile → Home) |
| `Session.kt` | the sign-in kept on the phone; `SessionWork`: online / offline, the number on Find, "who am I", expert accounts; `ThumbBackfill` |
| `SdNavBar.kt` | Secret Dating's bottom bar (Online Map · Home · Chat) |
| `Push.kt` | notifications while the app is closed (Firebase): ringing calls with Answer / Decline, messages, missed calls |
| `WebBridge.kt` | link with the sign-in web page (only while signing in / out): its address, the login token, the app id |
| `ExploreScreen.kt` | native Explore: header, chips, Voice/Video/Random, busy sheet, dialogs, calls |
| `FeedView.kt` | the moving bubbles feed and its still background |
| `CallStageView.kt` | "Finding someone…" / "Calling …" screen |
| `AvatarArt.kt` | drawn faces for profiles without a photo (same as web) |
| `LiveRoomsScreen.kt`, `LiveListPages.kt`, `LiveRoomPage.kt`, `LiveRoomSheets.kt`, `LiveSheets.kt`, `LiveKit.kt`, `LiveVoice.kt`, `LiveApi.kt` | native Live Rooms (`/live`, `/live/…`, audio only): list, Leaderboard, Go Live, the room (voice, seats, Super Chat, Hearts, gifts, Join list, Follow, Block / Report, End Live); same `liveRoom` / `liveRoomToken` functions as the web page |
| `Photos.kt`, `Api.kt`, `Person.kt`, `Ui.kt`, `Widgets.kt` | helpers |
| `AdminScreen.kt` | native Control Center (`/admin`, admin only): black top with the section tabs, members and overview data, verification decisions |
| `AdTabs1.kt`, `AdVerify.kt`, `AdMembers.kt`, `AdTabs2.kt`, `AdTabs3.kt` | the Control Center sections: Overview, Growth · Verify · Members + member sheet · Reports, Experts, Live, Payouts · Support, Errors, Settings, System |
| `AdKit.kt`, `AdSheets.kt`, `AdMedia.kt`, `AdIcons.kt`, `AdSource.kt`, `AdDemo.kt` | the Control Center's look (cards, buttons, pills, sheets, photo / video viewers), its server calls, and made-up data for the test pictures |
| `AppStatus.kt` | maintenance screen (+ the admin's "Maintenance is ON" pill), announcement card, "account is blocked", error screens ("Something went wrong", "No internet connection"), crash notes for Control Center → Errors |
| `NotifyPrompt.kt` | "Never miss a call" card on Home: asks Android's notifications question once (again after 3 days on "Not now") |

