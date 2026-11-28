package app.truefind.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.truefind.android.games.GameApi
import app.truefind.android.games.GameVoice
import app.truefind.android.games.GamesDemo
import app.truefind.android.games.GamesLayer
import app.truefind.android.login.LoginLayer
import org.json.JSONObject

/** Ask the phone for its quickest screen mode (see MainActivity.askFastScreen). On since 19 Nov (the owner's choice); false turns it off again. */
private const val FAST_SCREEN = true

/**
 * True Find for Android — every screen is drawn by the phone itself; the backend stays on Base44
 * (the same functions and data as the website).
 *
 *  - Signed in, the app leads itself: [Router] keeps where you are and where Back goes, [Session] keeps
 *    the sign-in, [SessionWork] does what the web page did in the background (online / offline, the red
 *    number on Find, who am I). There is NO web page while you are signed in. (Before, the whole web app
 *    ran hidden under the native screens and every page change went through it — two apps at once, which
 *    made page moves hang.)
 *  - Only signing in and signing out still use a web page for a moment, hidden under the native sign-in
 *    pages (LoginLayer): Base44's one-time sign-in link, Google's page and the server's log-out only work
 *    in a browser. It is made when it is needed and thrown away as soon as you are in.
 *
 * The screens: Home, Profile, Recent, Expert Talk, Dashboard (NativePages) · Explore · Reconnect
 * (SecretHome + its own bar SdNavBar, one chat in ChatRoom) · Wallet · Games · Live Dance · Live Rooms ·
 * Control Center · calls (CallManager) · status screens (AppStatus) · sign-in pages (LoginLayer).
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val START_URL = "https://truefindapp.com/"
        /** the old Base44 address: the same app and server, only no longer shown (a sign-in saved with it moves to START_URL) */
        const val OLD_ORIGIN = "https://true-find.base44.app"
        // only addresses True Find owns: the app's sign-in and bridges are given to these pages alone
        // (truefindapp.in was removed: it is not ours, so a page there must never be treated as the app)
        val ORIGINS = setOf("https://truefindapp.com", "https://www.truefindapp.com", OLD_ORIGIN)
    }

    private lateinit var root: FrameLayout
    private lateinit var edgeGlow: EdgeGlow
    // every touch passes the glow first (it only looks; the screen gets the touch as before)
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::edgeGlow.isInitialized) try { edgeGlow.feed(ev) } catch (e: Exception) { }
        return super.dispatchTouchEvent(ev)
    }
    /** the sign-in web page: only there while signing in or out (never while the app's own pages lead) */
    private var web: WebView? = null
    private lateinit var explore: ExploreScreen
    private lateinit var secret: SecretHome
    private lateinit var pages: NativePages
    private var chatRoom: ChatRoom? = null
    private lateinit var wallet: WalletScreen
    private var walletPreviewing = false
    private lateinit var games: GamesLayer
    /** calls drawn by the phone: the incoming screen, the call screen, the Return pill (CallManager) */
    private lateinit var calls: CallManager
    private var callPreview = ""
    private var path = "/"
    private var search = ""
    private var preview = ""

    // ── where the app is ──
    /** the app's own history: where you are, where Back goes (signed in) */
    private val router = Router()
    /** signed in and inside the app: the app's own pages lead; there is no web page */
    private var native = false
    /** the app's pages are being left for a sign-in page (log out, "Finish profile" …): they stay until it shows */
    private var leaving = false
    private var signingOut = false
    /** the address the sign-in web page last told ("" = nothing since it was opened) */
    private var webRoute = ""
    private var webSynced = false
    /** on the way to a sign-in page: the web page has said it holds the app's sign-in (none when logging out) */
    private var webAgrees = false
    /** the app's history as it was when a tap inside the app opened a sign-in page: Back there returns to it */
    private var returnTo: String? = null
    private lateinit var work: SessionWork
    private lateinit var sdNav: SdNavBar
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var dance: LiveDanceScreen
    private lateinit var login: LoginLayer
    /** Google sign-in came back while the app was closed (truefind://auth): handed over once the page is up */
    private var pendingAuth: Uri? = null
    private var danceOn = false
    private lateinit var live: LiveRoomsScreen
    private var liveOn = false
    private var cover = false
    private var navTopCss = -1f
    /** this phone's WebView can run the app's script before a page's own code (asked only when a web page is made) */
    private val scriptAtStart: Boolean by lazy { try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (e: Throwable) { false } }
    private val updater by lazy { Updater(this) }
    // native Control Center (/admin), status screens (maintenance, announcement, blocked, errors) and the notifications card
    private lateinit var admin: AdminScreen
    private lateinit var status: AppStatus
    private lateinit var notify: NotifyPrompt
    private lateinit var rules: RulesReminder

    // ── permissions asked by the web page (mic / camera for calls, location for the map) ──
    private var pendingWebPermission: PermissionRequest? = null
    private val askMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val req = pendingWebPermission ?: return@registerForActivityResult
        pendingWebPermission = null
        answer(req)
    }

    private fun answer(req: PermissionRequest) {
        val ok = ArrayList<String>()
        for (r in req.resources) {
            if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && has(Manifest.permission.RECORD_AUDIO)) ok.add(r)
            if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) ok.add(r)
            if (r == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) ok.add(r)
        }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val (origin, cb) = pendingGeo ?: return@registerForActivityResult
        pendingGeo = null
        cb.invoke(origin, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false)
    }
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    // Reconnect "Add to your story": the phone's photo picker
    private var pickDone: ((ByteArray?, String) -> Unit)? = null
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = pickDone ?: return@registerForActivityResult
        pickDone = null
        if (uri == null) { cb(null, ""); return@registerForActivityResult }
        val type = contentResolver.getType(uri) ?: "image/jpeg"
        Api.async({ try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null } }) { bytes -> cb(bytes, type) }
    }
    // the microphone for the games' voice (native Agora)
    private var micDone: ((Boolean) -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cb = micDone
        micDone = null
        cb?.invoke(ok)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // microphone (+ camera for video) for the native calls: asked before a call is answered or while it rings
    private var callPermDone: ((Boolean) -> Unit)? = null
    private var callPermVideo = false
    private val askCallAv = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val cb = callPermDone ?: return@registerForActivityResult
        callPermDone = null
        cb(has(Manifest.permission.RECORD_AUDIO) && (!callPermVideo || has(Manifest.permission.CAMERA)))
    }
    private fun askCallPerms(video: Boolean, done: (Boolean) -> Unit) {
        val need = ArrayList<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (video && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (need.isEmpty()) { done(true); return }
        callPermDone?.invoke(false)
        callPermDone = done; callPermVideo = video
        try { askCallAv.launch(need.toTypedArray()) } catch (e: Exception) { callPermDone = null; done(false) }
    }

    // camera + microphone for the native live selfie videos (Become a Host / Creator, verification)
    private var camDone: ((Boolean) -> Unit)? = null
    private val askCam = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val cb = camDone ?: return@registerForActivityResult
        camDone = null
        cb(has(Manifest.permission.CAMERA) && has(Manifest.permission.RECORD_AUDIO))
    }
    // camera + mic for dancing live (native Live Dance)
    private var avCb: ((Boolean) -> Unit)? = null
    private val askAv = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val cb = avCb; avCb = null
        cb?.invoke(has(Manifest.permission.CAMERA) && has(Manifest.permission.RECORD_AUDIO))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Photos.assets = applicationContext.assets
        Lite.init(this)
        // failed server answers are kept on the phone for the page speed report (20 Nov)
        Api.keepFailuresIn(this)
        // call ends that never reached the server (a lost answer, the app closed) are sent again (CallEnder, 23 Nov)
        CallEnder.init(this)
        // notifications while the app is closed (Firebase, when this build has it): this phone's push token
        Push.onToken = { _ -> runOnUiThread { web?.evaluateJavascript("try{window.dispatchEvent(new Event('tf-native-token'))}catch(e){}", null); if (Api.token.isNotEmpty()) Push.register(this, Api.token.takeLast(24)) } }
        // a message, like or note arrived while the app is open: the red number on Find is asked again
        Push.onMessage = { t -> runOnUiThread { if (::work.isInitialized && (t == "msg" || t == "like" || t == "info")) work.unreadSoon(300) } }
        Push.onCall = { id -> runOnUiThread { if (::calls.isInitialized) calls.pushWhileOpen(id) } }
        Push.init(this)
        // the sign-in (Supabase Auth) renews itself every hour: live updates take the new one; one the server ended signs out here
        Auth.onRenewed = { _ -> if (!isDestroyed) tokenChanged() }
        Auth.onEnded = { if (!isDestroyed && Api.token.isNotEmpty()) sessionRejected() }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // no grey veil of the phone's own over the strips at the top and bottom: they take the page's colours (owner, 21 Nov)
        if (android.os.Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        askFastScreen()

        // (PageHost: pages that were shown once stay ready while hidden, so coming back to them is only a move)
        root = PageHost(this).apply { clipChildren = false }
        // white as before; the strip under the phone's status bar turns Explore's purple while Explore is open
        appBg = AppBackground({ root.paddingTop }, { if (::explore.isInitialized) explore.headerHeight else 0 })
        appBg.bottomStrip = { root.paddingBottom }
        root.background = appBg
        setContentView(root)
        // the Reconnect page ends exactly where its bar starts — checked again whenever the screen's edges change
        // (the phone's insets can arrive after the page was sized: a white band showed between the page and the bar)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { sizeSecretPage() } }
        // keep everything clear of the status bar, the phone's own buttons and the keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            // Reconnect's bar (the page's blush) runs on down over the phone's own button strip
            if (::sdNav.isInitialized && sdNav.below != bars.bottom) {
                sdNav.below = bars.bottom
                (sdNav.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = -bars.bottom; sdNav.layoutParams = it }
            }
            WindowInsetsCompat.CONSUMED
        }

        // the app's own history: every change shows the page it now points at
        router.onChange = { move -> onRouterChange(move) }

        explore = ExploreScreen(this, root) { p, state -> navigate(p, state) }
        explore.visibility = View.GONE
        root.addView(explore, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        secret = SecretHome(this, root) { p, state -> navigate(p, state) }
        secret.visibility = View.GONE
        root.addView(secret, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Reconnect's own bottom bar (Home · Chat · Map; 26 Nov: Discover and Likes went with the like feature)
        sdNav = SdNavBar(this) { to -> navigate(to) }
        secret.onLikesDot = { on -> sdNav.homeDot = on }
        sdNav.visibility = View.GONE
        root.addView(sdNav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        SdSheets.pickImage = { cb -> pickDone = cb; try { pickPhoto.launch("image/*") } catch (e: Exception) { pickDone = null; cb(null, "") } }
        // native Home, Profile, Recent … and the bottom bar
        pages = NativePages(this, root) { update() }
        pages.host = object : NativePages.Host {
            override fun go(path: String, state: JSONObject?, replace: Boolean) = navigate(path, state, replace)
            override fun back(n: Int): Boolean = goBack(n)
            override val canGoBack: Boolean get() = native && router.canGoBack
            override fun logout(presence: Boolean) = signOut(presence)
        }
        pages.checkUpdate = { updater.checkNow() }
        // test builds: the "Update available" box never covers a ringing / running call (seen in the push test, 23 Nov)
        updater.holdOff = { overLock || (::calls.isInitialized && (calls.busyWithCall || calls.callWaiting)) }
        // test builds: how smoothly the app draws on this phone (Settings → Page speed report)
        pages.speedReport = { Perf.show(this) }
        pages.moveSpeed = { askMoveSpeed() }
        if (BuildConfig.SELF_UPDATE) PageFx.slow = try { getSharedPreferences("tf_test", MODE_PRIVATE).getFloat("fx_slow", 1f).coerceIn(1f, 3f) } catch (e: Exception) { 1f }
        if (BuildConfig.SELF_UPDATE) Perf.start(this)
        pages.onWallet = { d -> if (::wallet.isInitialized && !walletPreviewing) wallet.offer(d) }
        secret.onCredits = { d -> if (::wallet.isInitialized && !walletPreviewing) wallet.offerCredits(d) }
        pages.askCamera = { cb ->
            if (has(Manifest.permission.CAMERA) && has(Manifest.permission.RECORD_AUDIO)) cb(true)
            else { camDone = cb; try { askCam.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) } catch (e: Exception) { camDone = null; cb(false) } }
        }
        // calls are drawn by the phone (incoming screen, call screen, the Return pill)
        calls = CallManager(this, root, object : CallManager.Host {
            override fun path() = this@MainActivity.path + this@MainActivity.search
            override fun go(path: String) { if (callPreview.isEmpty()) navigate(path) }
            override fun changed() { if (overLock && !calls.busyWithCall) showOverLock(false); update() }
            override fun askPerms(video: Boolean, done: (Boolean) -> Unit) = askCallPerms(video, done)
        })
        pages.calls = calls
        // Random Match asks for the microphone (+ camera) before it starts searching
        RandomSearch.perms = { v, done -> askCallPerms(v, done) }
        var lastH = 0
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> val h = bottom - top; if (h != lastH) { lastH = h; root.post { update() } } }

        wallet = WalletScreen(this, { p, state -> navigate(p, state) }, { walletBack() }, { url -> openCheckout(url) }, { app -> walletAddress(app) })
        wallet.visibility = View.GONE
        root.addView(wallet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        games = GamesLayer(this, { p, state -> navigate(p, state) }, { fallback -> if (!goBack()) navigate(fallback) })
        root.addView(games, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        GameVoice.micAsker = { done ->
            if (has(Manifest.permission.RECORD_AUDIO)) done(true)
            else {
                micDone?.invoke(false)
                micDone = done
                try { askMic.launch(Manifest.permission.RECORD_AUDIO) } catch (e: Exception) { micDone = null; done(false) }
            }
        }

        dance = LiveDanceScreen(this, object : LiveDanceScreen.Nav {
            override fun go(path: String) = navigate(path)
            override fun replace(path: String) = navigate(path, null, true)
            override fun back() {
                if (goBack()) return
                replace(if (pathOnly(this@MainActivity.path) != "/dance") "/dance" else "/")
            }
            override fun askCameraMic(done: (Boolean) -> Unit) {
                if (has(Manifest.permission.CAMERA) && has(Manifest.permission.RECORD_AUDIO)) { done(true); return }
                avCb?.invoke(false)
                avCb = done
                askAv.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
            override fun share(title: String, text: String) {
                try {
                    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }
                    startActivity(Intent.createChooser(i, title))
                } catch (e: Exception) { }
            }
        })
        dance.visibility = View.GONE
        root.addView(dance, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // native Control Center (/admin), status screens and the "Never miss a call" card
        admin = AdminScreen(this, object : AdminScreen.Nav {
            override fun go(path: String, replace: Boolean) = navigate(path, null, replace)
            override fun back() { if (!goBack()) navigate("/home", null, true) }
        })
        admin.visibility = View.GONE
        root.addView(admin, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        notify = NotifyPrompt(this, root)
        // the rules reminder every 3 months (its links open the website's policy pages in a browser tab)
        rules = RulesReminder(this, root) { p -> openBrowserTab(Api.origin.trimEnd('/') + p) }
        status = AppStatus(this, root, object : AppStatus.Host {
            override fun webJs(code: String) { web?.evaluateJavascript(code, null) }
            // (there is no web page any more: "Go to Home" opens the app's Home when signed in)
            override fun webLoad(url: String) { if (native && !leaving) navigate("/", null, true) }
            override fun retrySession() { if (::work.isInitialized) work.retry() }
        }, { p -> navigate(p) }, { signOut(true) })
        notify.navPx = { if (pages.nav.visibility == View.VISIBLE) pages.nav.barPx else 0 }
        notify.busyElsewhere = { fxTest || pages.layer.open || pages.dailySheet != null || (cover && !native) || status.blocking || rules.open }
        rules.busyElsewhere = { fxTest || pages.layer.open || pages.dailySheet != null || (cover && !native) || status.blocking }
        CrashNote.install(this)
        // App Health (25 Nov): app use, pages and problems for the Control Center (AppHealth.kt)
        AppHealth.init(this)
        // native Live Rooms (/live …)
        live = LiveRoomsScreen(this, object : LiveRoomsScreen.Nav {
            override fun go(path: String) = navigate(path)
            override fun replace(path: String) = navigate(path, null, true)
            override fun back() {
                if (goBack()) return
                // opened straight on this page: a room / the list / the Leaderboard → Live Room page → Home
                val atTop = pathOnly(this@MainActivity.path) == "/live" && !search.contains("m=")
                replace(if (atTop) "/" else "/live")
            }
            override fun askMic(done: (Boolean) -> Unit) {
                if (has(Manifest.permission.RECORD_AUDIO)) { done(true); return }
                micDone?.invoke(false)
                micDone = done
                try { askMic.launch(Manifest.permission.RECORD_AUDIO) } catch (e: Exception) { micDone = null; done(false) }
            }
            override fun share(title: String, text: String) {
                try {
                    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }
                    startActivity(Intent.createChooser(i, title))
                } catch (e: Exception) { }
            }
            override fun pickImage(done: (ByteArray?, String) -> Unit) {
                pickDone?.invoke(null, "")
                pickDone = done
                try { pickPhoto.launch("image/*") } catch (e: Exception) { pickDone = null; done(null, "") }
            }
        })
        live.visibility = View.GONE
        root.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // native sign-in pages (over everything else while signed out, or while a sign-in page opened from the app shows)
        login = LoginLayer(this, object : LoginLayer.Host {
            override fun signedIn(next: String) = this@MainActivity.signedIn(next)
            override fun signOut(then: String) = this@MainActivity.signOutFromLogin(then)
            override fun finishApp() { finish() }
            override fun pickPhoto(done: (ByteArray?, String) -> Unit) {
                pickDone?.invoke(null, "")
                pickDone = done
                try { pickPhoto.launch("image/*") } catch (e: Exception) { pickDone = null; done(null, "") }
            }
            override fun openTab(url: String): Boolean = openBrowserTab(url)
            override fun backToApp(): Boolean = this@MainActivity.backToApp()
        })
        // the sign-in layer shows or goes: the pages move
        var loginSeen = false
        login.onChange = {
            if (login.shown != loginSeen) { loginSeen = login.shown; note("sign-in page " + if (loginSeen) "shown" else "hidden") }
            update()
        }
        root.addView(login, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // the soft glow at a list's top / bottom end (owner, 19 Nov evening): True Find's blue, Reconnect's pink.
        // It sits over the pages (under the call screen) and never takes a touch.
        edgeGlow = EdgeGlow(this) {
            val p = pathOnly(path)
            if (p == "/secret-app" || p == "/reconnect-map" || Regex("^/chat/[^/]+$").matches(p)) K.rose else PullSpinner.BLUE
        }
        edgeGlow.elevation = Ui.dp(99f)
        root.addView(edgeGlow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // what the web page did in the background for a signed-in member (online / offline, the number on Find …)
        work = SessionWork(this, object : SessionWork.Host {
            override fun unread(n: Int) { pages.nav.unread = n }
            override fun rejected() { sessionRejected() }
            override fun offline(on: Boolean) { note(if (on) "the sign-in could not be checked (no answer from the server)" else "the server answers again"); status.setSessionOffline(on) }
            override fun expert(on: Boolean) { if (on && native && !leaving && !expertAllowed(pathOnly(path))) router.go("/expert-talk", null, true) }
            override fun barShown(): Boolean = pages.navShown
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backAt = uptime()
                if (::login.isInitialized && login.shown && login.onBack()) return
                if (status.onBack()) return
                // a call on screen (or ringing) answers Back first
                if (calls.onBack()) return
                if (dance.visibility == View.VISIBLE) {
                    if (!dance.onBack()) dance.nav.back()
                    return
                }
                if (live.visibility == View.VISIBLE) {
                    if (!live.onBack()) live.nav.back()
                    return
                }
                chatRoom?.let { r -> if (r.visibility == View.VISIBLE) { if (!r.back()) chatBack(); return } }
                if (wallet.visibility == View.VISIBLE && wallet.onBack()) return
                if (admin.visibility == View.VISIBLE && admin.onBack()) return
                if (pages.onBack()) return
                if (games.visibility == View.VISIBLE && games.onBack()) return
                if (explore.visibility == View.VISIBLE && explore.onBack()) return
                if (secret.visibility == View.VISIBLE && secret.back()) return
                if (preview.isNotEmpty() || walletPreviewing || callPreview.isNotEmpty()) { finish(); return }
                if (native) {
                    if (leaving) return
                    // the page before; nothing behind: the app's own steps (Wallet → Recent → Explore → Home,
                    // Profile → Home); the app is left only from Home
                    // True Find Home: "Tap again to exit" (black, white words); a second Back within 2 s leaves the app
                    // (owner, 21 Nov morning — the history behind Home is not walked any more)
                    if (pathOnly(path).let { it == "/" || it == "/home" }) {
                        val t = uptime()
                        if (t - exitAskAt < 2000) { finish(); return }
                        exitAskAt = t; exitTip(); return
                    }
                    if (router.back()) return
                    if (router.up()) return
                    finish()
                    return
                }
                // a sign-in web page that is not one of the app's own (Google's): its own history
                val w = web
                if (w != null && w.canGoBack()) { w.goBack(); return }
                finish()
            }
        })

        // test mode (emulator pictures on GitHub only): the native call screens with made-up data
        val callScene = intent?.getStringExtra("tf_call").orEmpty()
        if (callScene.isNotEmpty() && BuildConfig.SELF_UPDATE) { startCallPreview(callScene); return }
        // test mode (emulator pictures on GitHub only): a native games screen with made-up data
        val gamesPreview = intent?.getStringExtra("tf_games").orEmpty()
        if (gamesPreview.isNotEmpty() && BuildConfig.SELF_UPDATE) { startGamesPreview(gamesPreview); return }
        // test mode (emulator pictures on GitHub only): the native Wallet with made-up data
        val walletScene = intent?.getStringExtra("tf_wallet").orEmpty()
        if (walletScene.isNotEmpty() && BuildConfig.SELF_UPDATE) { startWalletPreview(walletScene); return }
        // test mode (emulator pictures on GitHub only): the Control Center / status screens / notifications card, made-up data
        val miscScene = intent?.getStringExtra("tf_misc").orEmpty()
        if (miscScene.isNotEmpty() && BuildConfig.SELF_UPDATE) { startMiscPreview(miscScene); return }
        // test mode (emulator screenshots on GitHub only): a native screen with made-up data, no web page
        preview = intent?.getStringExtra("tf_preview").orEmpty()
        if (preview.isNotEmpty() && BuildConfig.SELF_UPDATE) { startPreview(); return }
        // test mode: a native sign-in page with made-up answers
        val loginScene = intent?.getStringExtra("tf_login").orEmpty()
        if (loginScene.isNotEmpty() && BuildConfig.SELF_UPDATE) {
            preview = "login"
            watchFrames()
            login.startPreview(loginScene, intent?.getStringExtra("tf_open").orEmpty())
            return
        }
        // test runs only (ci/session-test.sh): a made-up sign-in stays, to try the change between the two modes
        SessionWork.skipCheck = BuildConfig.SELF_UPDATE && intent?.getBooleanExtra("tf_test_nocheck", false) == true
        // test runs only (ci/push-test.sh): what the phone still needs so calls ring while closed (BgSetup)
        if (BuildConfig.SELF_UPDATE && intent?.getBooleanExtra("tf_bgcheck", false) == true) android.util.Log.i("TFDBG", "bgcheck ${BgSetup.report(this)}")
        val pushPath = intent?.let { pushOpen(it, true) }
        val linkPath = intent?.data?.takeIf { isOurs(it) }?.let { u -> (u.encodedPath ?: "/").ifEmpty { "/" } + (u.encodedQuery?.let { "?$it" } ?: "") }
        startSession(pushPath ?: linkPath, savedInstanceState)
    }

    /** one line in the phone's log whenever the app changes between its own pages and the sign-in pages (no personal data) */
    private fun note(what: String) { android.util.Log.i("TFDBG", "mode: $what") }

    // ═════════════════ signed in → the app's own pages · signed out → the sign-in pages ═════════════════
    /**
     * The app opens: a sign-in kept on this phone goes straight to the app's own pages; without one the native
     * sign-in pages open (WhatsApp OTP, Supabase Auth). There is no web page any more.
     */
    private fun startSession(first: String?, saved: Bundle?) {
        if (Session.load(this)) {
            note("start: sign-in kept on this phone")
            val restored = saved?.getString("tf_router")?.let { router.restore(it) } == true
            enterNative(first ?: "/", restored && first == null)
            return
        }
        note("start: no sign-in on this phone, the sign-in pages open")
        returnTo = null
        showLogin(first?.takeIf { LoginLayer.isLoginPath(pathOnly(it)) } ?: "/welcome")
    }

    /** The sign-in pages lead from [target] (/welcome …): signed out, or a sign-in page opened from the app. */
    private fun showLogin(target: String) {
        AppHealth.page(pathOnly(target))
        native = false; leaving = false; signingOut = false
        main.removeCallbacks(leaveTimeout); showBusy(false)
        // (signed in on a sign-in page — Finish profile —: calls still reach this phone)
        calls.enabled = Api.token.isNotEmpty()
        router.clear()
        setPath(target)
        login.open(target)
        update()
    }

    /** The sign-in pages finished (the right OTP, a finished profile): the app's own pages from [next]. */
    private fun signedIn(next: String) {
        if (native || Api.token.isEmpty()) return
        note("signed in: the app's own pages take over")
        returnTo = null
        val t = pathOnly(next).let { if (LoginLayer.isLoginPath(it)) "/" else next }
        enterNative(t, false)
    }

    private fun enterNative(target: String, restored: Boolean, move: Router.Move = Router.Move.RESET) {
        native = true; leaving = false; signingOut = false
        main.removeCallbacks(leaveTimeout); showBusy(false)
        Session.save(this)
        calls.enabled = true
        tokenChanged()
        calls.signedIn(Api.token)
        login.close()
        work.start()
        // (the first page of the app just shows: no move from the sign-in page)
        fxPath = ""
        note("native" + if (restored && router.started) " (history kept)" else " at ${pathOnly(target)}")
        if (restored && router.started) onRouterChange(move)
        else {
            var t = guard(target)
            // (Home takes nothing from the address: whatever the sign-in page left there is dropped)
            if (Router.same(pathOnly(t), "/")) t = pathOnly(t)
            // (a page opened from a notification or a link sits on Home: Back goes there)
            router.reset(t, null, if (Router.same(t, "/")) null else "/")
        }
    }

    /**
     * Leaves the app's own pages for a sign-in page ([target], e.g. /welcome to finish a profile). Back on its
     * first page comes back to the page it was opened from.
     */
    private fun enterLogin(target: String, replace: Boolean = false) {
        if (!native || leaving) return
        // Back on that page comes back here (a page that sent you there in its own place: to the page before it)
        returnTo = if (replace) router.saveBelow() else router.save()
        note("leaving for the sign-in page ${pathOnly(target)}")
        showLogin(target)
    }

    /**
     * Log out (the web's logout()): this phone stops getting this account's notifications, Explore is told
     * "offline" ([presence]; 1.5 s at most), the sign-in is forgotten on this phone and on the server, then
     * Get Started.
     */
    private fun signOut(presence: Boolean) {
        if (preview.isNotEmpty() || fxTest) return
        if (!native) { signOutFromLogin("/welcome"); return }
        if (leaving) return
        leaving = true; signingOut = true
        returnTo = null
        // (a quiet "one moment" over the page until the sign-in page is up)
        showBusy(true)
        note("signing out")
        Push.unregister(this)
        work.stop(presence) { finishSignOut("/welcome") }
        main.removeCallbacks(leaveTimeout); main.postDelayed(leaveTimeout, 6_000)
    }
    /** a sign-in page signs out (the number belongs to another account …): then [then] */
    private fun signOutFromLogin(then: String) {
        if (native) { signOut(false); return }
        returnTo = null
        if (Api.token.isEmpty()) { showLogin(then); return }
        note("signing out from a sign-in page")
        Push.unregister(this)
        work.stop(false) { finishSignOut(then) }
    }
    private fun finishSignOut(then: String) {
        if (!leaving && native) return
        main.removeCallbacks(leaveTimeout)
        val old = Api.token
        Session.clear(this)
        Api.token = ""
        // the server forgets this phone's sign-in too
        Auth.logout(old)
        tokenChanged()
        calls.signedIn("")
        if (isDestroyed) return
        showLogin(then)
    }
    /** "Who am I" was answered with "not signed in", or the server ended the sign-in: it is over on this phone. */
    private fun sessionRejected() {
        note("the server no longer accepts this sign-in")
        when {
            // on the sign-in pages: forgotten here, Get Started again
            !native -> { work.stop(false); finishSignOut("/welcome") }
            !leaving -> signOut(false)
        }
    }

    /** Back on the first sign-in page, opened from a page of the app (Complete my profile …): that page again. */
    private fun backToApp(): Boolean {
        val saved = returnTo ?: return false
        returnTo = null
        if (native || Api.token.isEmpty() || !Api.ready || !router.restore(saved)) return false
        note("Back on the sign-in page: the app's page again")
        enterNative(router.current.url, true, Router.Move.POP)
        return true
    }
    /** a log-out that never finished (the "offline" message hung): it finishes now */
    private val leaveTimeout = Runnable { if (leaving && signingOut) { note("log-out finished after waiting"); finishSignOut("/welcome") } }

    /** a quiet "one moment" over the page while the sign-in page is on its way (shown after a third of a second) */
    private var busyView: View? = null
    private val busyShow = Runnable { busyView?.let { v -> v.visibility = View.VISIBLE; v.alpha = 0f; v.animate().alpha(1f).setDuration(160).start() } }
    private fun showBusy(on: Boolean) {
        main.removeCallbacks(busyShow)
        if (!on) { busyView?.let { root.removeView(it) }; busyView = null; return }
        if (busyView != null) return
        val box = FrameLayout(this).apply { isClickable = true; visibility = View.INVISIBLE; elevation = Ui.dp(400f); outlineProvider = null; setBackgroundColor(Color.argb(40, 23, 18, 31)) }
        val card = FrameLayout(this).apply { background = Tf.box(Color.WHITE, 18f); elevation = Ui.dp(6f) }
        card.addView(TfLoader(this, 26f), FrameLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(26f), android.view.Gravity.CENTER))
        box.addView(card, FrameLayout.LayoutParams(Ui.dpi(84f), Ui.dpi(64f), android.view.Gravity.CENTER))
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
        busyView = box
        main.postDelayed(busyShow, 350)
    }

    /** the things that follow the sign-in (live updates, notifications, status screens): once per change */
    private var signalled: String? = null
    private fun tokenChanged() {
        val t = Api.token
        Push.keepAuth(this, Api.origin, Api.appId, t)
        // (the app's id may still be on its way from the sign-in page: its next message tries again)
        if (t.isNotEmpty() && !Api.ready) return
        if (t == signalled) return
        signalled = t
        Realtime.tokenChanged()
        if (t.isNotEmpty()) Push.register(this, t.takeLast(24))
        if (::status.isInitialized) status.tokenChanged(t.isNotEmpty())
        if (t.isNotEmpty()) CrashNote.sendPending(this)
    }

    // ═════════════════ (the sign-in web page is gone: Supabase's WhatsApp OTP sign-in is native) ═════════════════
    // ═════════════════ opened from a notification (Push) ═════════════════
    private var overLock = false
    /** an incoming call may show over the lock screen (and wake the screen) while it rings / its screen is open */
    private fun showOverLock(on: Boolean) {
        overLock = on
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(on); setTurnScreenOn(on) }
        else @Suppress("DEPRECATION") {
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(f) else window.clearFlags(f)
        }
        if (on) main.postDelayed(wakeForCall, 800) else releaseCallWake()
    }

    /**
     * Android 9 and 10 (seen in the push test, 23 Nov) can open the call screen over the lock screen
     * and still leave the screen dark: if it is still dark a moment later, the screen is switched on
     * for the ringing (at most 45 s, let go as soon as the call screen goes).
     */
    private var callWake: android.os.PowerManager.WakeLock? = null
    private val wakeForCall = Runnable {
        if (!overLock || Build.VERSION.SDK_INT >= 30) return@Runnable
        try {
            val pm = getSystemService(android.os.PowerManager::class.java) ?: return@Runnable
            if (pm.isInteractive) return@Runnable
            releaseCallWake()
            @Suppress("DEPRECATION")
            callWake = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "truefind:incoming-call").apply { setReferenceCounted(false); acquire(45_000L) }
            android.util.Log.i("TFDBG", "call: screen woken")
        } catch (e: Throwable) { }
    }
    private fun releaseCallWake() {
        main.removeCallbacks(wakeForCall)
        try { callWake?.let { if (it.isHeld) it.release() } } catch (e: Throwable) { }
        callWake = null
    }
    /** a tapped notification: a call rings / is answered here, anything else opens its page. [cold]: the app was
     *  closed, the page's address is returned (the app opens on it) instead. */
    private fun pushOpen(i: Intent, cold: Boolean): String? {
        val call = i.getStringExtra(Push.EXTRA_CALL).orEmpty()
        val url = i.getStringExtra(Push.EXTRA_URL)
        if (call.isEmpty() && url == null) return null
        i.removeExtra(Push.EXTRA_CALL); i.removeExtra(Push.EXTRA_URL)
        if (call.isNotEmpty()) {
            android.util.Log.i("TFDBG", "push open call=$call act=${i.getStringExtra(Push.EXTRA_ACT).orEmpty()} cold=$cold")
            showOverLock(true)
            calls.fromNotification(call, i.getStringExtra(Push.EXTRA_ACT).orEmpty())
            return null
        }
        val u = url.orEmpty().takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
        if (cold) return u
        navigate(u)
        return null
    }

    private fun isOurs(u: Uri): Boolean = u.scheme == "https" && ORIGINS.contains("https://${u.host}")

    // ═════════════════ moving around ═════════════════
    /** pages only an expert account may open (ExpertGate.jsx): everything else takes her to her Expert Talk panel */
    private fun expertAllowed(p: String): Boolean = listOf("/expert-talk", "/wallet", "/admin", "/delete-account").any { p == it || p.startsWith("$it/") }
    /** a page the app has (the web showed "Page not found" for anything else) */
    private fun known(p: String): Boolean =
        p == "/" || p == "/home" || p == "/explore" || p == "/chats" || p == "/wallet" || p == "/profile" || p == "/profile/edit" ||
            p == "/register/verification" || p == "/secret-app" || p == "/reconnect-map" || Regex("^/chat/[^/]+$").matches(p) ||
            p == "/games" || p.startsWith("/games/") || p == "/dance" || p.startsWith("/dance/") || p == "/live" || p.startsWith("/live/") ||
            p == "/expert-talk" || p == "/dashboard" || p == "/secret-earnings" || p == "/admin"
    /** where [target] really leads: an unknown address is Home; an expert account stays in Expert Talk */
    private fun guard(target: String): String {
        val p = pathOnly(target)
        var t = if (known(p)) target else "/"
        if (::work.isInitialized && work.isExpert && !expertAllowed(pathOnly(t))) t = "/expert-talk"
        return t
    }
    /** /call/<id> and /demo-call are the call screen (never a page of the history) */
    private fun openCallPage(full: String, state: JSONObject?): Boolean {
        val p = pathOnly(full)
        val q = full.indexOf('?').let { if (it >= 0) full.substring(it) else "" }
        if (p == "/demo-call") { calls.openPractice(state ?: JSONObject()); return true }
        val id = Regex("^/call/([^/]+)$").find(p)?.groupValues?.get(1) ?: return false
        calls.openCall(Uri.decode(id), state, q)
        return true
    }

    /**
     * Opens a page (the web's navigate(path, { state, replace })). Signed in, the app's own history takes it;
     * on the sign-in pages the sign-in web page's router does.
     */
    private fun navigate(full: String, state: JSONObject? = null, replace: Boolean = false) {
        // a test preview has no pages behind it; a chat still opens over it as in the app, so the taps that lead to a chat
        // (a row of the chat list, Send Request on a demo profile) can be photographed
        if (Demo.on && preview.isNotEmpty()) Regex("^/chat/([^/?]+)$").find(pathOnly(full))?.groupValues?.get(1)?.let { id ->
            val r = room(); r.visibility = View.VISIBLE; r.open(id); roomShown = true; roomStrips(); sdNav.visibility = View.GONE
            return
        }
        // (and True Find's Settings, opened from Reconnect's My Profile: the Profile page with its Settings drawer)
        if (Demo.on && preview.startsWith("secret") && pathOnly(full) == "/profile" && full.contains("open=settings")) {
            secret.layer.closeAll(); secret.visibility = View.GONE; sdNav.visibility = View.GONE
            pages.startPreview("profile", "settings=1")
            return
        }
        // on the sign-in pages: only another sign-in page opens (the app's pages need the sign-in first)
        if (!native) { if (LoginLayer.isLoginPath(pathOnly(full))) { AppHealth.page(pathOnly(full)); login.go(full, replace) }; return }
        if (leaving) return
        if (openCallPage(full, state)) { AppHealth.page(pathOnly(full)); return }
        // a sign-in page (finish the profile, sign in again …): the sign-in layer takes over
        if (LoginLayer.isLoginPath(pathOnly(full))) { enterLogin(full, replace); return }
        val t = guard(full)
        nativeNavAt = uptime()
        router.go(t, if (t == full) state else null, replace)
    }
    /** [n] pages back; false when there is no page behind */
    private var exitAskAt = 0L
    private fun exitTip() {
        val v = TextView(this).apply {
            text = "Tap again to exit"; setTextColor(Color.WHITE); textSize = 14f; typeface = Ui.bold; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#111114")); cornerRadius = Ui.dp(22f) }
            setPadding(Ui.dpi(20f), Ui.dpi(11f), Ui.dpi(20f), Ui.dpi(11f)); elevation = Ui.dp(30f); alpha = 0f
        }
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dpi(96f) })
        v.animate().alpha(1f).setDuration(150).start()
        v.postDelayed({ v.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(v) }.start() }, 2000)
    }

    private fun goBack(n: Int = 1): Boolean {
        if (!native) {
            val w = web ?: return false
            if (!w.canGoBackOrForward(-n)) return false
            backAt = uptime()
            w.goBackOrForward(-n)
            return true
        }
        if (leaving) return true
        return router.back(n)
    }
    /** a chat's Back: the page you came from, or the Reconnect chats (the web's goBack in ChatRoom.jsx) */
    private fun chatBack() { if (!goBack()) navigate("/secret-app?tab=chat", null, true) }
    private fun room(): ChatRoom = chatRoom ?: ChatRoom(this, root, go = { p, st -> navigate(p, st) }, goBack = { chatBack() }, replace = { navigate(it, null, true) }).also {
        it.visibility = View.GONE
        // a Reconnect chat has a maroon top and a blush page: the phone's strips follow (True Find's own chats: white as before)
        it.onStrip = { if (roomShown) roomStrips() }
        // pinned / unpinned in the chat: the Reconnect chat list moves it at once
        it.onPinned = { id, on -> if (::secret.isInitialized) secret.chatPinned(id, on) }
        root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chatRoom = it
    }

    /** The app's own history changed: the pages follow (and move the way that change asks for). */
    private fun onRouterChange(move: Router.Move) {
        if (!native) return
        val e = router.current
        // an expert account is Expert Talk only (ExpertGate.jsx): any other page is her panel instead
        if (::work.isInitialized && work.isExpert && !expertAllowed(pathOnly(e.path))) { router.go("/expert-talk", null, true); return }
        path = e.path; search = e.search
        routeIdx = router.idx
        Perf.page(e.path, e.search)
        AppHealth.page(e.path, e.search)
        pages.setRoute(e.path, e.search, e.state, router.idx)
        moveHint = move
        lastMove = move
        if (move == Router.Move.POP) backAt = uptime()
        update()
        calls.routeChanged()
    }

    /** the address in two parts, as the rest of the app keeps it: path and ?query */
    private fun setPath(full: String) {
        val q = full.indexOf('?')
        path = if (q >= 0) full.substring(0, q) else full
        search = if (q >= 0) full.substring(q) else ""
    }
    private fun pathOnly(p: String) = p.substringBefore('?').trimEnd('/').ifEmpty { "/" }
    private fun isDance(p: String): Boolean { val o = pathOnly(p); return o == "/dance" || o.startsWith("/dance/") }
    private fun isLive(p: String): Boolean { val o = pathOnly(p); return o == "/live" || o.startsWith("/live/") }

    // ── the move between pages (PageFx: FRND's tab slide, push and back) ──
    private var backAt = 0L        // phone Back, or a page's own back arrow
    private var nativeNavAt = 0L   // a tap in a native screen opened another page
    private var fxPath = ""        // the address at the last move check ("" until the first page shows)
    private var fxIdx = -1         // the history position at the last move check
    private var routeIdx = -1      // the history position now
    private var moveHint: Router.Move? = null   // what the app's own history just did (read once by the next move check)
    private var lastMove: Router.Move? = null   // the same, kept: a page opened afresh starts at its top, a page gone back to stays where it was
    private fun pageViews(): List<View> = buildList {
        add(explore); add(secret); add(wallet); add(games); add(dance); add(live)
        if (::login.isInitialized) add(login)
        chatRoom?.let { add(it) }
        if (::admin.isInitialized) add(admin)
        addAll(pages.pageViews)
    }
    /** the full native page on screen (null: the sign-in web page shows) — pages still sliding away don't count */
    private fun shownPage(): View? = pageViews().filter { it.visibility == View.VISIBLE && it.parent === root && !PageFx.isHeld(it) }
        .maxByOrNull { root.indexOfChild(it) }
    /** pages of the bottom bar that slide side by side (Find and Wallet open over it, with their own back arrow) */
    private fun tabIndex(p: String): Int = when (pathOnly(p).let { if (it == "/home") "/" else it }) {
        "/" -> 0; "/explore" -> 1; "/chats" -> 3; else -> -1
    }
    private fun uptime() = android.os.SystemClock.uptimeMillis()

    /** Shows the native screens that fit the page now, and moves to it the way FRND does when the page changes. */
    private fun update() {
        // while the screens are still being made (onCreate), just show what fits
        if (!::dance.isInitialized || !::live.isInitialized || !::wallet.isInitialized || !::games.isInitialized || !::login.isInitialized || !::work.isInitialized) { updateNow(); return }
        val hint = moveHint; moveHint = null
        val before = shownPage()
        val navBefore = pages.nav.visibility == View.VISIBLE && !PageFx.isHeld(pages.nav)
        val fromPath = fxPath
        val fromIdx = fxIdx
        PageFx.release()
        updateNow()
        PageFx.reassert()
        val after = shownPage()
        val navAfter = pages.nav.visibility == View.VISIBLE && !PageFx.isHeld(pages.nav)
        // the bottom bar came back (e.g. after reading a chat): the red number on Find is asked again
        if (navAfter && !navBefore) work.barBack()
        val toPath = path
        if (Api.token.isEmpty() || preview.isNotEmpty() || walletPreviewing) { fxPath = ""; return }
        readyTabs()
        fxPath = toPath; fxIdx = routeIdx
        if (fromPath.isEmpty()) return                       // the app just opened: no move
        val now = uptime()
        val webToWeb = before == null && after == null
        if (webToWeb) return                                 // the sign-in web page moves its own pages
        if (before === after) return                         // the same native page
        if (pathOnly(fromPath) == pathOnly(toPath)) return   // same address (the app opening, a sheet)
        val tA = tabIndex(fromPath); val tB = tabIndex(toPath)
        // the app's own history says which way it went; on the sign-in pages: a Back a moment ago, or an earlier place in the history
        val goingBack = if (native && hint != null) hint == Router.Move.POP else (now - backAt < 1500 || (routeIdx >= 0 && fromIdx >= 0 && routeIdx < fromIdx))
        val kind = when {
            // Back goes the Claude app's way everywhere, between the bar's pages too (owner, 19 Nov evening: Wallet →
            // Recent dissolved, but Recent → Explore → Home still slid sideways like a tab tap). A tab tap still slides.
            native && hint == Router.Move.POP -> PageFx.Kind.POP
            tA >= 0 && tB >= 0 && tA != tB -> PageFx.Kind.TAB
            goingBack -> PageFx.Kind.POP
            tA < 0 && tB >= 0 -> PageFx.Kind.POP
            else -> PageFx.Kind.PUSH
        }
        val nativeLed = now - nativeNavAt < 1500
        // (the sign-in web page counts as a page only while it leads)
        val fromV: View? = before ?: if (native) null else web
        val toV: View? = after ?: if (native) null else web
        // (test builds: the frames of this move are counted under this name — Settings → Page speed report)
        Perf.nextLabel = Perf.pageName(pathOnly(fromPath)) + " → " + Perf.pageName(pathOnly(toPath), search)
        when (kind) {
            PageFx.Kind.TAB -> PageFx.run(root, kind, fromV, toV, dir = if (tB > tA) 1 else -1,
                // Home ↔ Recent: Explore lies between them and passes by (as the tab between does in FRND)
                between = if (minOf(tA, tB) == 0 && maxOf(tA, tB) == 3 && explore.parked) listOf(explore) else emptyList(),
                onEnd = { settle() })
            PageFx.Kind.PUSH -> PageFx.run(root, kind, fromV, toV,
                lowerExtras = if (navBefore && !navAfter) listOf(pages.nav) else emptyList(),
                // Reconnect's pink bar comes in with its page
                upperExtras = if (after === secret && before != null) listOf(sdNav) else emptyList(),
                moveLower = before != null || nativeLed, onEnd = { settle() })
            PageFx.Kind.POP -> {
                backAt = 0L
                PageFx.run(root, kind, before, toV,
                    lowerExtras = if (navAfter && !navBefore) listOf(pages.nav) else emptyList(),
                    // … and leaves with it
                    upperExtras = if (before === secret && after != null) listOf(sdNav) else emptyList(),
                    onEnd = { settle() })
            }
        }
    }
    /** [work] as soon as the phone has nothing else to do (1.2 s later at most), and never during a page move */
    private fun whenQuiet(delay: Long, work: () -> Unit) {
        root.postDelayed({
            var done = false
            fun go() {
                if (done) return
                if (PageFx.running) { root.postDelayed({ go() }, 300); return }
                done = true
                work()
            }
            android.os.Looper.myQueue().addIdleHandler { go(); false }
            root.postDelayed({ go() }, 1200)
        }, delay)
    }
    /**
     * Explore and Recent get ready in the background a moment after Home first shows: laid out and drawn off
     * the screen, and Explore's people read once (as Recent's calls already are), so their first visit moves
     * as smoothly as the later ones — FRND's tabs are all there from the start too.
     */
    private var tabsReadied = false
    private fun readyTabs() {
        if (tabsReadied || Api.token.isEmpty() || !(pages.onHome && pages.home.visibility == View.VISIBLE)) return
        tabsReadied = true
        whenQuiet(1500) {
            explore.keepReady()
            // (its bubbles are drawn once off the screen when the people have come)
            if (explore.parked) explore.load { if (explore.parked) root.invalidate() }
        }
        whenQuiet(2400) { pages.recent.keepReady() }
    }
    /** the pages that stay ready while hidden (KeepPage) */
    private fun keptPages(): List<KeepPage> = listOf(pages.home, explore, pages.recent, wallet, secret, pages.profile)
    override fun onStop() {
        // (App Health: the screen time ends first, so the going-away heartbeat carries it)
        AppHealth.foreground(false)
        // off the screen: Explore is told "offline" at once (as the web page did when it was hidden)
        if (::work.isInitialized) work.onVisible(false)
        // Android drops every drawing while the app is away: hidden pages are drawn again one by one after
        // coming back (onStart), not all of them in the first frame
        if (::wallet.isInitialized) for (p in keptPages()) p.appStopped()
        super.onStop()
    }
    override fun onStart() {
        super.onStart()
        AppHealth.foreground(true)
        if (::work.isInitialized) work.onVisible(true)
        if (!::wallet.isInitialized) return
        var i = 0
        for (p in keptPages()) if (p.asleep) whenQuiet(900L + 450L * i++) { p.wake() }
    }
    /** after a move: the pages that should be hidden now are hidden (no new move from here) */
    private fun settle() {
        if (!::dance.isInitialized) return
        updateNow()
        fxPath = path; fxIdx = routeIdx
    }

    private lateinit var appBg: AppBackground

    /** Explore open: the phone's status bar sits on Explore's purple (white clock and icons); elsewhere white as before. */
    private fun exploreStatusBar(on: Boolean) {
        if (appBg.purple == on) return
        appBg.purple = on
        stripNow = -1   // (the page's own strip colour is set again after this)
        // the phone paints its status bar in the theme's white; on Explore it is see-through, so the purple strip shows
        @Suppress("DEPRECATION")
        window.statusBarColor = if (on) Color.TRANSPARENT else Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !on
    }

    /** Reconnect's bar is showing: the page's blush runs on under the phone's own button strip — the strip is
     *  painted the same blush (older phones paint it themselves) and the bar may draw over the padding (owner, 21 Nov) */
    private var sdBottomOn: Boolean? = null

    private val SD_TOP = Rs.BG_TOP                  // Reconnect's blush page, at its top (23 Nov; was the pink #FB5C94)
    private var roomShown = false
    /** one Reconnect chat is open: maroon behind the clock (white clock), blush over the phone's buttons */
    private fun roomStrips() {
        val r = chatRoom
        pageStrip(r?.stripTop)
        appBg.bottom = r?.stripBottom
        navStrip(r?.stripBottom ?: Color.WHITE)
    }
    private var navNow: Int? = null
    /** the phone's own button strip in [col] (phones that paint it themselves) */
    private fun navStrip(col: Int) {
        if (navNow == col) return
        navNow = col
        @Suppress("DEPRECATION")
        window.navigationBarColor = col
    }
    private val WALLET_TOP = Color.rgb(91, 33, 182) // the Wallet's purple top (#5B21B6, owner 27 Nov): no white strip above it
    private val PROFILE_TOP = Color.rgb(217, 204, 250) // Profile's light purple top, Blinkit-style (#D9CCFA, owner 28 Nov)
    private val CALLBACK_TOP = Color.rgb(247, 247, 251) // Call Back's off-white page, on up under the clock (owner, 27 Nov)
    private val HOME_TOP = Color.rgb(138, 76, 236) // True Find Home's purple header top (#8A4CEC, 23 Nov; was the light lilac #FAF8FD)
    private var stripNow: Int? = -1
    /** the status bar's strip in [col] (null: white as before); white clock and icons on a chat's maroon, dark elsewhere.
     *  Explore keeps its own purple strip (exploreStatusBar). */
    private fun pageStrip(col: Int?) {
        if (appBg.purple) return
        if (stripNow == col) return
        stripNow = col
        appBg.top = col
        @Suppress("DEPRECATION")
        window.statusBarColor = col ?: Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = col != Rs.M && col != HOME_TOP && col != WALLET_TOP   // white clock and icons on a chat's maroon and on Home's purple
    }
    private fun sdBottom(on: Boolean) {
        root.clipToPadding = !on
        sdBottomOn = on
    }

    /** Reconnect's pages end where its own bar starts (the room inside the phone's edges, less the bar) */
    private fun sizeSecretPage() {
        if (!::secret.isInitialized || !::sdNav.isInitialized) return
        val innerH = root.height - root.paddingTop - root.paddingBottom
        sizeTo(secret, if (innerH > 0) innerH - sdNav.barPx else ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private fun sizeTo(v: View, h: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.height != h) { lp.height = h; v.layoutParams = lp }
    }

    private fun updateNow() {
        if (walletPreviewing) return
        if (preview.isNotEmpty()) return
        // signed in and inside the app: its own pages show. Otherwise only the sign-in pages do.
        val inApp = native
        val covered = cover && !native
        // ── Live Dance ──
        val wantDance = inApp && isDance(path + search)
        if (wantDance) {
            danceOn = true
            dance.route(path + search)
            dance.setShown(true)
        } else if (danceOn) {
            danceOn = false
            dance.leave()
        }
        // ── Live Rooms ──
        if (::live.isInitialized) {
            val wantLive = inApp && isLive(path + search)
            if (wantLive) {
                liveOn = true
                live.route(path + search)
                live.setShown(true)
            } else if (liveOn) {
                liveOn = false
                live.leave()
            }
        }

        pages.freshOpen = lastMove != Router.Move.POP
        val navTop = pages.update(inApp)
        val p = path.trimEnd('/')
        val onExplore = p == "/explore"
        // Reconnect: which page (the web's SecretApp view)
        val tab = try { Uri.parse("x://x$search").getQueryParameter("tab") } catch (e: Exception) { null }
        val secretView = when (p) { "/secret-app" -> if (tab == "chat") "chat" else "home"; "/reconnect-map" -> "map"; else -> "" }
        val chatId = Regex("^/chat/([^/]+)$").find(p)?.groupValues?.get(1).orEmpty()
        val show = onExplore && inApp
        val showSecret = secretView.isNotEmpty() && inApp
        // one chat: over the whole screen (no bar on that page)
        val showRoom = chatId.isNotEmpty() && inApp
        if (showRoom) room().open(chatId) else chatRoom?.close()
        chatRoom?.let { r -> if (showRoom != (r.visibility == View.VISIBLE)) r.visibility = if (showRoom) View.VISIBLE else View.GONE }
        // Explore ends where the bottom bar starts; the Reconnect pages end where their own bar starts
        sizeTo(explore, if (pages.navShown && navTop > 0) navTop else ViewGroup.LayoutParams.MATCH_PARENT)
        sizeSecretPage()
        // (its own "open" state counts too: a page move may hold its view visible or hidden for a moment, and the
        // two must never be left disagreeing — that left Explore on screen with its bubbles stopped. While Explore
        // is open, every screen update also makes sure its bubbles are moving: setShown(true) again only checks that.)
        if (show || explore.visibility == View.VISIBLE || explore.isOn) explore.setShown(show)
        exploreStatusBar(show)
        secret.setShown(showSecret, secretView.ifEmpty { secret.page }, search)
        if (secretView.isNotEmpty()) sdNav.current = secretView
        sdNav.visibility = if (showSecret) View.VISIBLE else View.GONE
        // the bar's grey runs on down under the phone's own button strip (it may draw over the padding there; owner, 21 Nov)
        sdBottom(showSecret)
        // the strip under the phone's status bar in the page's own top colour: no separate white strip (owner, 21 Nov)
        roomShown = showRoom
        if (showRoom) roomStrips() else {
            appBg.bottom = null
            navStrip(if (showSecret) Rs.BG_END else Color.WHITE)   // the bar's blush (23 Nov)
            pageStrip(if (show) null else if (showSecret) SD_TOP else if (inApp && (p.isEmpty() || p == "/home")) HOME_TOP else if (inApp && p == "/wallet") WALLET_TOP else if (inApp && p == "/chats") CALLBACK_TOP else if (inApp && p == "/profile" && search.isEmpty()) PROFILE_TOP else null)
        }
        // native Wallet: full screen
        val showWallet = p == "/wallet" && inApp
        // opened afresh (a tap, not Back): the Wallet starts at its top on the tab its address asks for (owner, 19 Nov)
        wallet.setShown(showWallet, search.removePrefix("?"), fresh = lastMove != Router.Move.POP)
        if (!previewing) games.setRoute(path, inApp, false)
        // the Control Center (/admin, admins only), maintenance / announcement / blocked / error screens, "Never miss a call"
        if (::admin.isInitialized) {
            admin.setShown(p == "/admin" && inApp, search)
            status.update(path + search, Api.token.isNotEmpty(), covered)
            notify.update(pages.onHome && pages.home.visibility == View.VISIBLE, inApp)
            rules.update(pages.onHome && pages.home.visibility == View.VISIBLE, inApp)
        }
    }

    /** Test mode: the Control Center, a status screen or the notifications card with made-up data (no web page, no login). */
    private fun startMiscPreview(scene: String) {
        preview = "misc"
        watchFrames()
        val open = intent.getStringExtra("tf_open").orEmpty()
        val ready = Runnable { android.util.Log.i("TFDBG", "preview open $open") }
        if (scene.startsWith("admin")) {
            admin.startPreview(scene.removePrefix("admin").removePrefix("-"))
            root.postDelayed({ if (open.isNotEmpty()) admin.previewOpen(open); root.postDelayed(ready, if (open.isNotEmpty()) 1500L else 500L) }, 2500)
            return
        }
        // the status screens and the card over the native Home (made-up data), as members see them
        Demo.girl = intent.getBooleanExtra("tf_girl", false)
        root.post { pages.startPreview("home", "") }
        root.postDelayed({
            if (scene.startsWith("notify")) notify.startPreview(scene) else if (scene == "rules") rules.startPreview() else status.startPreview(scene)
            root.postDelayed(ready, 1200)
        }, 3000)
    }

    /** The Wallet's back arrow: the page before, or Home when the Wallet was the first page. */
    private fun walletBack() { if (!goBack()) navigate("/") }

    /** Keeps ?app=secret in the address when the Wallet switches (as the web page does). */
    private fun walletAddress(app: String) {
        if (!native || leaving || pathOnly(router.path) != "/wallet") return
        val u = try { Uri.parse("x://x/${router.search}") } catch (e: Exception) { return }
        val b = StringBuilder()
        fun add(k: String, v: String) { b.append(if (b.isEmpty()) "?" else "&").append(Uri.encode(k)).append("=").append(Uri.encode(v)) }
        var done = false
        for (k in u.queryParameterNames) {
            if (k == "app") { if (app == "secret") { add("app", "secret"); done = true } }
            else for (v in u.getQueryParameters(k)) add(k, v)
        }
        if (app == "secret" && !done) add("app", "secret")
        router.replaceSearch(b.toString())
    }

    /**
     * A Reconnect plan's payment page (only when online payments are switched on on the server): it opens in
     * the phone's browser, on top of the app; back in the app the Wallet reads the plan again.
     */
    private var checkoutOpen = false
    private fun openCheckout(url: String) {
        checkoutOpen = true
        if (!openBrowserTab(url)) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { checkoutOpen = false; wallet.checkoutClosed() } }
    }

    /** Test mode: the native Wallet with made-up data (WalletDemo), no web page, no login. */
    private fun startWalletPreview(scene: String) {
        walletPreviewing = true
        wallet.source = WalletDemo(scene)
        wallet.setShown(true, if (scene.startsWith("secret")) "app=secret" else "")
        val open = intent.getStringExtra("tf_open").orEmpty()
        if (open.isNotEmpty()) root.postDelayed({ wallet.previewOpen(open) }, 2500)
    }

    /**
     * Test runs only: the page moves (PageFx) with made-up data — the same code as on the phone: every step
     * is a tap's call into the app's own history (Router), nothing else. The CI records the screen.
     */
    private var fxTest = false
    private fun startFxTest() {
        fxTest = true
        Demo.on = true; DemoHome.on = true; DemoHome.dailyTaken = true
        Api.origin = "https://true-find.base44.app"; Api.appId = "demo"; Api.token = "demo"
        watchFrames()
        preview = ""
        fun say(what: String) = android.util.Log.i("TFDBG", "fx step $what")
        native = true
        calls.enabled = true
        // each move stops part of the way (45 %) for a picture ("fx frozen N"), then ends 9 s later (steps 24 s apart:
        // the test phone takes several seconds per picture)
        // test sets "fx" (steps 1–6) and "fx2" (7–11) each on their own test phone: the emulator tends to stop
        // after ~4 minutes, so each half is short. Every step starts from Home.
        val from = intent.getIntExtra("tf_fx_from", 1)
        val to = intent.getIntExtra("tf_fx_to", 11)
        fun frozenStep(k: Int, what: String, act: () -> Unit) {
            say("$k $what")
            PageFx.freezeAt = 0.45f
            act()
            root.postDelayed({ say("frozen $k"); }, 3000)
            root.postDelayed({ PageFx.finish(); say("now $k: ${router.path}${router.search} (page ${router.idx})"); say("done $k") }, 12000)
        }
        val list: List<Pair<String, () -> Unit>> = listOf(
            "Home -> Explore (tab)" to { navigate("/explore") },
            "Explore -> Home (tab back)" to { navigate("/") },
            "Home -> Profile (open)" to { navigate("/profile") },
            "Profile -> Home (back)" to { goBack() },
            "Home -> Wallet (open)" to { navigate("/wallet", JSONObject().put(Router.TAB, true)) },
            "Wallet -> Recent (back: the fixed step above the Wallet)" to { goBack() },
            "Recent -> Home (tab)" to { navigate("/") },
            "Home -> Recent (tab)" to { navigate("/chats") },
            "Recent -> Reconnect (open)" to { navigate("/secret-app") },
            "Reconnect Home -> Map (its own tab)" to { navigate("/reconnect-map") },
            "Reconnect -> Recent (back, two pages)" to { goBack(2) },
        )
        root.postDelayed({
            say("start"); router.reset("/")
            // the second half starts where the first half ends (on Recent), without a move
            if (from >= 7 && !intent.getBooleanExtra("tf_fx_live", false)) { navigate("/chats"); PageFx.finish() }
        }, 1500)
        // test set "fx3": the moves running freely — each must end on the right page with the pages it left
        // kept ready ("parked"); the log gets one TFFX line per move and where the app's history stands
        if (intent.getBooleanExtra("tf_fx_live", false)) {
            fun state(v: View) = if (v.visibility == View.VISIBLE) "shown" else if (v is KeepPage && v.parked) (if (v.asleep) "asleep" else "parked") else "gone"
            val live: List<Pair<String, () -> Unit>> = listOf(
                "Home -> Explore" to { navigate("/explore") },
                "Explore -> Home" to { navigate("/") },
                "Home -> Wallet (open)" to { navigate("/wallet", JSONObject().put(Router.TAB, true)) },
                "Wallet -> Recent (back)" to { goBack() },
                "Recent -> Home (back: Explore is the step above Recent, then Home)" to { goBack(); goBack() },
                "Home -> Recent (Explore passes by)" to { navigate("/chats") },
            )
            var t = 8000L
            live.forEachIndexed { i, (what, act) ->
                root.postDelayed({ say("live ${i + 1} $what"); act() }, t)
                root.postDelayed({
                    say("live done ${i + 1}: at=${router.path}${router.search} page=${router.idx} moving=${PageFx.running} home=${state(pages.home)} explore=${state(explore)} recent=${state(pages.recent)} wallet=${state(wallet)} " +
                        "x=${pages.home.translationX.toInt()},${explore.translationX.toInt()},${pages.recent.translationX.toInt()},${wallet.translationX.toInt()}")
                    say("perf| ${Perf.lastMove()}")
                }, t + 4500)
                t += 8000L
            }
            root.postDelayed({ Perf.report(this).lines().forEach { say("perf| $it") }; say("end") }, t)
            return
        }
        var at = 6000L
        for (k in from..to.coerceAtMost(list.size)) {
            val (what, act) = list[k - 1]
            root.postDelayed({ frozenStep(k, what, act) }, at)
            at += 24000L
        }
        root.postDelayed({ Perf.report(this).lines().forEach { say("perf| $it") }; say("end") }, at)
    }

    private fun startPreview() {
        if (preview == "fx") { startFxTest(); return }
        if (preview.startsWith("home") || preview.startsWith("nav") || preview.startsWith("profile") || preview.startsWith("builder") || preview.startsWith("verify") ||
            preview.startsWith("et") || preview.startsWith("dash") || preview.startsWith("earn") || preview.startsWith("recent")) {
            Demo.girl = intent.getBooleanExtra("tf_girl", false)
            watchFrames()
            root.post { pages.startPreview(preview, intent.getStringExtra("tf_open").orEmpty()); if (preview.startsWith("home")) pageStrip(HOME_TOP) }
            return
        }
        Demo.on = true
        Demo.premium = intent.getBooleanExtra("tf_premium", false)
        Demo.girl = intent.getBooleanExtra("tf_girl", false)
        Demo.noBlur = intent.getBooleanExtra("tf_noblur", false)
        Demo.noChats = intent.getBooleanExtra("tf_nochats", false)
        Demo.notJoined = intent.getBooleanExtra("tf_notjoined", false)
        Demo.tfChat = intent.getBooleanExtra("tf_tfchat", false)
        intent.getStringExtra("tf_lite")?.let { Lite.forceForTests(it == "1") }
        intent.getStringExtra("tf_mapoff")?.let { GlobeMap.testOff = it.split(",").filter { s -> s.isNotBlank() }.toSet() }
        if (intent.getBooleanExtra("tf_mapcheck", false)) GlobeMap.testCheck = true
        watchFrames()
        val open = intent.getStringExtra("tf_open").orEmpty()
        // one chat (/chat/:id): the whole screen
        if (preview == "chatroom") {
            val r = room()
            r.visibility = View.VISIBLE
            r.open(intent.getStringExtra("tf_chat") ?: "m1")
            roomShown = true; roomStrips()
            if (open.isNotEmpty()) root.postDelayed({ r.preview(open) }, 1500)
            return
        }
        val screen = if (preview.startsWith("secret")) secret else explore
        val lp = screen.layoutParams as FrameLayout.LayoutParams
        lp.height = (resources.displayMetrics.heightPixels - 90 * resources.displayMetrics.density).toInt()
        screen.layoutParams = lp
        val view = when (preview) { "secret-map" -> "map"; "secret-chat" -> "chat"; else -> "home" }
        if (screen === explore) {
            explore.setShown(true)
            exploreStatusBar(true)
            // the bottom bar under Explore too, as in the app (its Explore tab joins the Voice / Video / Random panel)
            pages.nav.visibility = View.VISIBLE; pages.nav.current = "explore"; pages.nav.seamless = true
            root.post { val innerH = root.height - root.paddingTop - root.paddingBottom; if (innerH > 0) sizeTo(explore, innerH - pages.nav.barPx) }
        } else {
            secret.setShown(true, view, "")
            // Reconnect's own bar under its pages, as in the app
            sdNav.current = view; sdNav.visibility = View.VISIBLE
            sdBottom(true)
            navStrip(Rs.BG_END)
            pageStrip(SD_TOP)
            root.post { sizeSecretPage() }
        }
        if (open.isNotEmpty()) root.postDelayed({ secret.previewOpen(open) }, 3500)
    }

    /** Test mode: a call screen (or the incoming call / the Return pill) with made-up data, no web page, no login. */
    private fun startCallPreview(scene: String) {
        callPreview = scene
        Demo.on = true
        Demo.girl = intent.getBooleanExtra("tf_girl", false)
        watchFrames()
        val open = intent.getStringExtra("tf_open").orEmpty()
        root.post {
            if (scene == "pill") {
                // "Call in progress · Return" over the native Home
                pages.startPreview("home", "")
                calls.preview("pill", "", Demo.girl)
                pages.previewPill()
            } else calls.preview(scene, open, Demo.girl)
            android.util.Log.i("TFDBG", "preview open call $scene $open")
        }
    }

    /** test runs only: write slow frames (and a count every 2 s) to the log, to see where time goes */
    private fun watchFrames() {
        if (android.os.Build.VERSION.SDK_INT < 24) return
        val t = android.os.HandlerThread("tf-frames").apply { start() }
        var frames = 0; var slow = 0; var worst = 0L
        val ms = { m: android.view.FrameMetrics, k: Int -> m.getMetric(k) / 1_000_000 }
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            frames++
            val total = ms(m, android.view.FrameMetrics.TOTAL_DURATION)
            if (total > worst) worst = total
            if (total > 100) {
                slow++
                val gpu = if (android.os.Build.VERSION.SDK_INT >= 31) ms(m, android.view.FrameMetrics.GPU_DURATION) else -1
                android.util.Log.i("TFDBG", "slow frame #$frames total=${total}ms anim=${ms(m, android.view.FrameMetrics.ANIMATION_DURATION)} layout=${ms(m, android.view.FrameMetrics.LAYOUT_MEASURE_DURATION)} draw=${ms(m, android.view.FrameMetrics.DRAW_DURATION)} sync=${ms(m, android.view.FrameMetrics.SYNC_DURATION)} issue=${ms(m, android.view.FrameMetrics.COMMAND_ISSUE_DURATION)} swap=${ms(m, android.view.FrameMetrics.SWAP_BUFFERS_DURATION)} gpu=$gpu")
            }
        }, android.os.Handler(t.looper))
        val tick = object : Runnable { override fun run() { android.util.Log.i("TFDBG", "frames so far $frames (slow >100ms: $slow, worst ${worst}ms)"); root.postDelayed(this, 2000) } }
        root.postDelayed(tick, 2000)
    }

    private var previewing = false

    /** Test mode: the games with made-up answers (GamesDemo), no web page, no login. */
    private fun startGamesPreview(scene: String) {
        previewing = true
        GameApi.demo = true
        GamesDemo.scene = scene
        GamesDemo.girl = intent.getBooleanExtra("tf_girl", false)
        val path = when {
            scene.startsWith("hc") -> "/games/heart-chase"
            scene.startsWith("mc") -> "/games/mood-changer"
            scene.startsWith("cm") -> "/games/clue-me"
            else -> "/games"
        }
        games.setRoute(path, true, false)
        val open = intent.getStringExtra("tf_open").orEmpty()
        if (open.isNotEmpty()) root.postDelayed({ games.open(open) }, 2500)
        // the test phone loads the games' voice engine once (no call), see GameVoice.selfCheck
        if (scene == "voice-check") root.postDelayed({ android.util.Log.i("TFVOICE", app.truefind.android.games.GameVoice.selfCheck(this)) }, 1500)
    }

    /**
     * Asks the phone for the quickest way its screen can show this size (90 or 120 pictures a second instead
     * of 60). Many phones keep an app at 60 unless it asks: the owner's phone can do 90 and showed this app at
     * 60 (page speed report, 19 Nov). The phone may still say no (battery saver, heat, its own setting) —
     * then nothing changes. Page moves and the app's animations go by the clock, so they take just as long
     * and only show more pictures on the way.
     *
     * Switched by [FAST_SCREEN] (on since 19 Nov, the owner's choice): at 90 a second the phone has 11 ms for each picture instead of
     * 17. A page whose pictures take 12–16 ms keeps a steady 60 on a 60 screen, but on a 90 screen it would
     * miss every other tick and look worse than before. The page speed report's "typical frame" per page
     * (build 97 on) shows how it goes on the owner's phone; if pages stutter, set it back to false.
     */
    private fun askFastScreen() {
        if (!FAST_SCREEN) return
        try {
            @Suppress("DEPRECATION") val d = windowManager.defaultDisplay ?: return
            val now = d.mode ?: return
            val best = d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (best.refreshRate < 61f) return                 // a 60-only screen: nothing to ask for
            val lp = window.attributes
            if (lp.preferredDisplayModeId == best.modeId && lp.preferredRefreshRate == best.refreshRate) return
            // (both ways of asking: some phones listen to only one of them)
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
            note("asked the screen for ${Math.round(best.refreshRate)} pictures a second (it shows ${Math.round(now.refreshRate)} now)")
        } catch (e: Throwable) { }
    }

    /**
     * Test builds, Settings → "Page move speed": every page move takes 1 / 1.25 / 1.5 / 2 times as long, kept on
     * this phone. For the owner to find, beside FRND and Claude on his own phone, the speed that looks right to
     * him: measured frame by frame the moves already take as long as theirs, yet they look quicker to him.
     */
    private fun askMoveSpeed() {
        val steps = floatArrayOf(1f, 1.25f, 1.5f, 2f)
        val names = arrayOf("As measured from FRND and Claude (now the app's own)", "A little slower · 1.25×", "Slower · 1.5×", "Much slower · 2×")
        val at = steps.indexOfFirst { Math.abs(it - PageFx.slow) < 0.01f }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Page move speed")
            .setSingleChoiceItems(names, at) { d, i ->
                PageFx.slow = steps[i]
                try { getSharedPreferences("tf_test", MODE_PRIVATE).edit().putFloat("fx_slow", steps[i]).apply() } catch (e: Exception) { }
                pages.profile.drawer?.refresh()
                d.dismiss()
                android.widget.Toast.makeText(this, if (i == 0) "Page moves: as measured." else "Page moves: ${steps[i]}× as long.", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        askFastScreen()
        Push.visible = true
        Push.clearCalls(this) // the app rings it itself now
        Push.tidy(this)
        calls.onAppVisible(true)
        // a call's "call in progress" service the phone refused while True Find was away starts now (CallService)
        CallService.refresh(this)
        // a Google Play payment that finished while True Find was away (UPI confirmed later): the server adds it now
        if (!Demo.on && Auth.has(this)) PlayBilling.recover(this)
        explore.onAppVisible(true)
        secret.onAppVisible(true)
        pages.onAppVisible(true)
        chatRoom?.onAppVisible(true)
        wallet.onAppVisible(true)
        // back from a plan's payment page in the phone's browser: the Wallet reads the plan again
        if (checkoutOpen) { checkoutOpen = false; wallet.checkoutClosed() }
        admin.onAppVisible(true); status.onAppVisible(true); notify.onAppVisible(true)
        if (preview.isEmpty() && !walletPreviewing && !previewing && !fxTest && callPreview.isEmpty()) updater.check() // test builds: "Update available" when GitHub has a newer APK
        dance.onAppVisible(true)
        live.onAppVisible(true)
        if (::login.isInitialized) login.onAppVisible(true)
    }

    override fun onPause() {
        Push.visible = false
        calls.onAppVisible(false)
        explore.onAppVisible(false)
        secret.onAppVisible(false)
        pages.onAppVisible(false)
        chatRoom?.onAppVisible(false)
        wallet.onAppVisible(false)
        admin.onAppVisible(false); status.onAppVisible(false); notify.onAppVisible(false)
        dance.onAppVisible(false)
        live.onAppVisible(false)
        if (::login.isInitialized) login.onAppVisible(false)
        if (web != null) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // signed in: where the app was (its own history); on the sign-in pages: the web page's
        if (native && !leaving && router.started && !fxTest) outState.putString("tf_router", router.save())
        else web?.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data
        if (pushOpen(intent, false) != null) return
        data?.takeIf { isOurs(it) }?.let { u ->
            if (native) navigate((u.encodedPath ?: "/").ifEmpty { "/" } + (u.encodedQuery?.let { "?$it" } ?: ""))
        }
    }

    /** Opens [url] in a Chrome Custom Tab (the phone's browser in the app's own window); false when there is none. */
    private fun openBrowserTab(url: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // a Custom Tab without the browser library: the session extra (empty) is what asks for it
        i.putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", Color.WHITE)
        i.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        startActivity(i)
        true
    } catch (e: Exception) { false }

    override fun onDestroy() {
        if (::work.isInitialized) work.stop(false)
        calls.shutdown()
        games.shutdown()
        dance.leave()
        live.leave()
        DanceVideo.destroyAll()
        main.removeCallbacksAndMessages(null)
        web?.let { w -> web = null; root.removeView(w); try { w.destroy() } catch (e: Throwable) { } }
        super.onDestroy()
    }
}
