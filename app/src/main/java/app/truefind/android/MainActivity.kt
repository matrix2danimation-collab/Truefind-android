package app.truefind.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import org.json.JSONObject

/**
 * True Find for Android.
 *  - The whole app is the Base44 web app (true-find.base44.app) in a WebView,
 *    exactly as it is today: login, Home, Secret Dating, Wallet, Recent,
 *    calls, games …
 *  - Explore is drawn natively (ExploreScreen) on top of the web page whenever
 *    the address is /explore. The web's own bottom bar stays visible under it.
 *  - Secret Dating is drawn natively too: Home (/secret-app), Online Map (/dating)
 *    and Chats (/secret-app?tab=chat) in SecretHome, above the web's own Secret
 *    Dating bar; one chat (/chat/:id) in ChatRoom, over the whole screen.
 *  - The Wallet is native too (WalletScreen), full screen, whenever the address
 *    is /wallet (True Find and Secret Dating wallets, Earnings, payments).
 *  - Games are native too (GamesLayer): the Games page, Heart Chase Boxes, Mood Changer,
 *    ClueMe and Dress Code, whenever the address is /games or a game's address.
 */
class MainActivity : ComponentActivity(), WebBridge.Listener {

    companion object {
        const val START_URL = "https://true-find.base44.app/"
        val ORIGINS = setOf("https://true-find.base44.app", "https://truefindapp.in", "https://www.truefindapp.in")
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var explore: ExploreScreen
    private lateinit var secret: SecretHome
    private lateinit var pages: NativePages
    private var chatRoom: ChatRoom? = null
    private lateinit var wallet: WalletScreen
    private var walletPreviewing = false
    private lateinit var games: GamesLayer
    private var path = "/"
    private var search = ""
    /** an address the web sent while a native tap was leading (applied when the lead ends) */
    private var heldRoute: String? = null
    private var preview = ""
    private lateinit var dance: LiveDanceScreen
    private var danceGate = false   // the published web page leaves Live Dance to this app
    private var danceOn = false
    private var cover = false
    private var navTopCss = -1f
    private var scriptAtStart = false
    private val updater by lazy { Updater(this) }

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

    // Secret Dating "Add to your story": the phone's photo picker
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Lite.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE); clipChildren = false }
        setContentView(root)
        // keep everything clear of the status bar, the phone's own buttons and the keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setupWeb()

        explore = ExploreScreen(this, root) { p, state -> openWeb(p, state) }
        explore.visibility = View.GONE
        root.addView(explore, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        secret = SecretHome(this, root) { p, state -> openWeb(p, state) }
        secret.visibility = View.GONE
        secret.onWantsWeb = { update() }
        root.addView(secret, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        SdSheets.pickImage = { cb -> pickDone = cb; try { pickPhoto.launch("image/*") } catch (e: Exception) { pickDone = null; cb(null, "") } }
        // native Home, Profile and the bottom bar (they follow the web address like Explore)
        pages = NativePages(this, root, web) { update() }
        pages.onLead = { p, s -> path = p; search = s; heldRoute = null }
        pages.checkUpdate = { updater.checkNow() }
        pages.onLeadEnd = {
            val h = heldRoute; heldRoute = null
            if (h != null) onRoute(h)
            else web.evaluateJavascript("location.pathname + location.search") { v ->
                val now = try { org.json.JSONArray("[$v]").optString(0) } catch (e: Exception) { "" }
                if (now.startsWith("/")) onRoute(now)
            }
        }
        pages.attach(scriptAtStart)
        pages.askCamera = { cb ->
            if (has(Manifest.permission.CAMERA) && has(Manifest.permission.RECORD_AUDIO)) cb(true)
            else { camDone = cb; try { askCam.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) } catch (e: Exception) { camDone = null; cb(false) } }
        }
        var lastH = 0
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> val h = bottom - top; if (h != lastH) { lastH = h; root.post { update() } } }

        wallet = WalletScreen(this, { p, state -> openWeb(p, state) }, { walletBack() }, { url -> openWalletCheckout(url) }, { app -> walletAddress(app) })
        wallet.visibility = View.GONE
        root.addView(wallet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        games = GamesLayer(this, { p, state -> openWeb(p, state) }, { fallback -> if (web.canGoBack()) web.goBack() else openWeb(fallback, null) })
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
            override fun go(path: String) {
                // show it at once; the web address follows (a moment later, through onRoute)
                if (isDance(path)) { setPath(path); update() }
                openWeb(path, null)
            }
            override fun replace(path: String) {
                if (isDance(path)) { setPath(path); update() }
                web.evaluateJavascript("window.__tfReplace && window.__tfReplace(${JSONObject.quote(path)});", null)
            }
            override fun back() {
                if (web.canGoBack()) web.goBack()
                else replace(if (pathOnly(this@MainActivity.path) != "/dance") "/dance" else "/")
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backAt = android.os.SystemClock.uptimeMillis()
                if (dance.visibility == View.VISIBLE) {
                    if (!dance.onBack()) {
                        if (web.canGoBack()) web.goBack() else dance.nav.back()
                    }
                    return
                }
                chatRoom?.let { r -> if (r.visibility == View.VISIBLE) { if (!r.back()) chatBack(); return } }
                if (wallet.visibility == View.VISIBLE && wallet.onBack()) return
                if (pages.onBack()) return
                if (games.visibility == View.VISIBLE && games.onBack()) return
                if (explore.visibility == View.VISIBLE && explore.onBack()) return
                if (secret.visibility == View.VISIBLE && secret.back()) return
                if (preview.isNotEmpty() || walletPreviewing) { finish(); return }
                if (web.canGoBack()) { web.goBack(); return }
                // Nothing behind in the web's history (a tab opened in place, or the app opened on this page):
                // Back used to close the whole app here. Now it takes the app's own steps
                // (Wallet → Recent → Explore → Home, Profile → Home) and leaves the app only from Home.
                val up = stepAbove(path, search)
                if (up != null) { pages.go(up, null, true); return }
                if (pathOnly(path) != "/" && pathOnly(path) != "/home") { pages.go("/", null, true); return }
                finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        // test mode (emulator pictures on GitHub only): a native games screen with made-up data
        val gamesPreview = intent?.getStringExtra("tf_games").orEmpty()
        if (gamesPreview.isNotEmpty() && BuildConfig.SELF_UPDATE) { startGamesPreview(gamesPreview); return }
        // test mode (emulator pictures on GitHub only): the native Wallet with made-up data
        val walletScene = intent?.getStringExtra("tf_wallet").orEmpty()
        if (walletScene.isNotEmpty() && BuildConfig.SELF_UPDATE) { startWalletPreview(walletScene); return }
        // test mode (emulator screenshots on GitHub only): a native screen with made-up data, no web page
        preview = intent?.getStringExtra("tf_preview").orEmpty()
        if (preview.isNotEmpty() && BuildConfig.SELF_UPDATE) { startPreview(); return }
        if (web.url == null) web.loadUrl(intent?.data?.takeIf { isOurs(it) }?.toString() ?: START_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.setBackgroundColor(Color.WHITE)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString TrueFindApp/1"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(WebBridge(this), WebBridge.NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, WebBridge.JS, ORIGINS)
            scriptAtStart = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    "intent" -> {
                        // UPI / payment apps: intent://pay?...#Intent;scheme=upi;package=…;end
                        try {
                            val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                            i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
                            try { startActivity(i) } catch (e: ActivityNotFoundException) {
                                i.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                            }
                        } catch (e: Exception) { }
                        true
                    }
                    else -> {
                        // upi:, tel:, mailto:, whatsapp: … open the right app
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { }
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!scriptAtStart && isOurs(Uri.parse(url))) { view.evaluateJavascript(WebBridge.JS, null); view.evaluateJavascript(NativePages.JS, null) }
                CookieManager.getInstance().flush()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // the page crashed (low memory): start the app screen again instead of closing
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val need = ArrayList<String>()
                    for (r in request.resources) {
                        if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
                        if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
                    }
                    if (need.isEmpty()) {
                        answer(request)
                    } else {
                        pendingWebPermission?.deny()
                        pendingWebPermission = request
                        askMedia.launch(need.toTypedArray())
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false); return
                }
                pendingGeo = origin to callback
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }

            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    pickFile.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
    }

    private fun isOurs(u: Uri): Boolean = u.scheme == "https" && ORIGINS.contains("https://${u.host}")

    /** Opens a page of the web app (same as navigate(path, { state }) inside it). */
    private fun openWeb(p: String, state: JSONObject?) {
        pages.lead(p, state)
        val js = "window.__tfGo && window.__tfGo(${JSONObject.quote(p)}, ${state?.toString() ?: "null"});"
        web.evaluateJavascript(js, null)
    }
    /** the same, in place of this page (navigate(path, { replace: true })) */
    private fun replaceWeb(p: String) {
        web.evaluateJavascript("window.__tfGo && window.__tfGo(${JSONObject.quote(p)}, null, true);", null)
    }
    /** a chat's Back: the page you came from, or the Secret Dating chats (the web's goBack in ChatRoom.jsx) */
    private fun chatBack() {
        web.evaluateJavascript("window.__tfBack && window.__tfBack('/secret-app?tab=chat');", null)
    }
    private fun room(): ChatRoom = chatRoom ?: ChatRoom(this, root, go = { p, st -> openWeb(p, st) }, goBack = { chatBack() }, replace = { replaceWeb(it) }).also {
        it.visibility = View.GONE
        root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chatRoom = it
    }

    // ── from the page ──
    override fun onRoute(path: String) {
        // a native tap has already switched the screen: an older address still on its way waits
        if (pages.leading(path)) { heldRoute = path; return }
        heldRoute = null
        val q = path.indexOf('?')
        this.path = if (q >= 0) path.substring(0, q) else path
        this.search = if (q >= 0) path.substring(q) else ""
        wallet.wantsWeb = false // back in the app after the plan payment page
        update()
    }

    override fun onInfo(origin: String, token: String, appId: String, navTopCss: Float, cover: Boolean) {
        Api.origin = origin
        if (appId.isNotEmpty()) Api.appId = appId
        val newLogin = token != Api.token
        Api.token = token
        if (newLogin) Realtime.tokenChanged()
        this.navTopCss = navTopCss
        this.cover = cover
        update()
    }

    override fun onCaps(dance: Boolean) {
        // only when our script ran before the page's own code (else the web page draws Live Dance itself)
        val on = dance && scriptAtStart
        if (on == danceGate) return
        danceGate = on
        update()
    }

    /** The step above a page for the phone's Back (the same steps as the web's PhoneBack). */
    private fun stepAbove(p: String, q: String): String? = when (pathOnly(p)) {
        "/explore" -> "/"
        "/chats" -> "/explore"
        "/wallet" -> "/chats"
        "/profile/edit" -> "/profile"
        "/profile" -> if (q.isNotEmpty() && q != "?") "/profile" else "/"
        else -> null
    }

    /** the address in two parts, as the rest of the app keeps it: path and ?query */
    private fun setPath(full: String) {
        val q = full.indexOf('?')
        path = if (q >= 0) full.substring(0, q) else full
        search = if (q >= 0) full.substring(q) else ""
    }
    private fun pathOnly(p: String) = p.substringBefore('?').trimEnd('/').ifEmpty { "/" }
    private fun isDance(p: String): Boolean { val o = pathOnly(p); return o == "/dance" || o.startsWith("/dance/") }
    private fun webMode(p: String) = try { Uri.parse("https://x$p").getQueryParameter("web") == "1" } catch (e: Exception) { false }

    // ── the move between pages (PageFx) ──
    private var backAt = 0L
    private var fxPath = ""
    private fun pageViews(): List<View> = buildList {
        add(explore); add(secret); add(wallet); add(games); add(dance)
        chatRoom?.let { add(it) }
        addAll(pages.pageViews)
    }
    private fun shownPage(): View? = pageViews().filter { it.visibility == View.VISIBLE && it.parent === root }
        .maxByOrNull { root.indexOfChild(it) }
    private val tabs = listOf("/", "/explore", "/secret-app", "/dating", "/chats", "/wallet")
    private fun tabIndex(p: String) = tabs.indexOf(pathOnly(p).let { if (it == "/home") "/" else it })

    /** Shows the native screens that fit the page now, with a smooth move when the full page changes. */
    private fun update() {
        // while the screens are still being made (onCreate), just show what fits
        if (!::dance.isInitialized || !::wallet.isInitialized || !::games.isInitialized) { updateNow(); return }
        val before = shownPage()
        val fromPath = fxPath
        updateNow()
        val after = shownPage()
        fxPath = path
        if (before === after) return
        if (preview.isNotEmpty() && !intent.getBooleanExtra("tf_fx", false)) return
        val a = tabIndex(fromPath); val b = tabIndex(path)
        val back = android.os.SystemClock.uptimeMillis() - backAt < 800 ||
            (a >= 0 && b >= 0 && b < a) || (a < 0 && b >= 0 && fromPath.isNotEmpty())
        PageFx.run(root, before, after, back)
    }

    private fun updateNow() {
        if (walletPreviewing) return
        if (preview.isNotEmpty()) return
        // ── Live Dance: native when the web page hands it over (not for ?web=1 pages) ──
        val wantDance = isDance(path + search) && !webMode(path + search) && danceGate && Api.token.isNotEmpty()
        if (wantDance) {
            danceOn = true
            dance.route(path + search)
            dance.setShown(!cover)
        } else if (danceOn) {
            danceOn = false
            dance.leave()
        }

        val navTop = pages.update(Api.token.isNotEmpty())
        val p = path.trimEnd('/')
        val onExplore = p == "/explore"
        // Secret Dating: which page (the web's SecretApp view)
        val tab = try { Uri.parse("x://x$search").getQueryParameter("tab") } catch (e: Exception) { null }
        val secretView = when (p) { "/secret-app" -> if (tab == "chat") "chat" else "home"; "/dating" -> "map"; else -> "" }
        val chatId = Regex("^/chat/([^/]+)$").find(p)?.groupValues?.get(1).orEmpty()
        val signedIn = Api.token.isNotEmpty()
        val show = onExplore && !cover && signedIn
        val showSecret = secretView.isNotEmpty() && !cover && signedIn && !secret.wantsWeb
        // one chat: over the whole screen (no bar on that page); a call on top (cover) hides it for a while, typing kept
        val showRoom = chatId.isNotEmpty() && !cover && signedIn
        if (chatId.isNotEmpty() && signedIn) room().open(chatId) else chatRoom?.close()
        chatRoom?.let { r ->
            if (showRoom != (r.visibility == View.VISIBLE)) {
                web.evaluateJavascript("document.documentElement.classList.${if (showRoom) "add" else "remove"}('tfcr')", null)
                r.visibility = if (showRoom) View.VISIBLE else View.GONE
            }
        }
        // the native page ends where the web's bottom bar starts
        val h = if (navTopCss > 0) (navTopCss * resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        for (v in listOf(explore, secret)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            // the main bottom bar is native now: Explore ends where it starts
            val want = if (v === explore && pages.navShown && navTop > 0) navTop else h
            if (lp.height != want) { lp.height = want; v.layoutParams = lp }
        }
        if (show != (explore.visibility == View.VISIBLE)) {
            web.evaluateJavascript("document.documentElement.classList.${if (show) "add" else "remove"}('tfnx')", null)
            explore.setShown(show)
        }
        if (showSecret != (secret.visibility == View.VISIBLE)) web.evaluateJavascript("document.documentElement.classList.${if (showSecret) "add" else "remove"}('tfsd')", null)
        secret.setShown(showSecret, secretView.ifEmpty { secret.page }, search)
        // native Wallet: full screen (the web hides its bottom bar on /wallet)
        val showWallet = path.trimEnd('/') == "/wallet" && !cover && Api.token.isNotEmpty() && !wallet.wantsWeb
        if (showWallet != (wallet.visibility == View.VISIBLE)) web.evaluateJavascript(walletCss(showWallet), null)
        wallet.setShown(showWallet, search.removePrefix("?"))
        if (!previewing) {
            val gamesWas = games.active
            games.setRoute(path, Api.token.isNotEmpty(), cover)
            if (games.active != gamesWas) web.evaluateJavascript("document.documentElement.classList.${if (games.active) "add" else "remove"}('tfgm')", null)
        }
    }

    /** While the native Wallet is on screen, the web page's own Wallet (.tfw) is not drawn. */
    private fun walletCss(on: Boolean) = "(function(){var d=document;if(!d.getElementById('tfwl-css')){var s=d.createElement('style');s.id='tfwl-css';" +
        "s.textContent='html.tfwl .tfw{display:none!important}';(d.head||d.documentElement).appendChild(s);}" +
        "d.documentElement.classList.toggle('tfwl',$on);})();"

    /** The Wallet's back arrow: the web's navigate(-1), or Home when the Wallet was the first page. */
    private fun walletBack() {
        web.evaluateJavascript("(function(){var s=history.state||{};if(s.key&&s.key!=='default'){history.back();}else if(window.__tfGo){window.__tfGo('/',null);}})();", null)
    }

    /** Keeps ?app=secret in the address when the Wallet switches (as the web page does). */
    private fun walletAddress(app: String) {
        web.evaluateJavascript("(function(){try{if(location.pathname.replace(/\\/+$/,'')!=='/wallet')return;var u=new URL(location.href);" +
            "if(${JSONObject.quote(app)}==='secret')u.searchParams.set('app','secret');else u.searchParams.delete('app');" +
            "history.replaceState(history.state,'',u.pathname+u.search+u.hash);}catch(e){}})();", null)
    }

    /** Secret Dating plan payment: the payment page opens in the web view; back in the app it returns to /wallet. */
    private fun openWalletCheckout(url: String) {
        wallet.wantsWeb = true
        update()
        web.loadUrl(url)
    }

    /** Test mode: the native Wallet with made-up data (WalletDemo), no web page, no login. */
    private fun startWalletPreview(scene: String) {
        walletPreviewing = true
        web.loadUrl("about:blank")
        wallet.source = WalletDemo(scene)
        wallet.setShown(true, if (scene.startsWith("secret")) "app=secret" else "")
        val open = intent.getStringExtra("tf_open").orEmpty()
        if (open.isNotEmpty()) root.postDelayed({ wallet.previewOpen(open) }, 2500)
    }

    /** Test mode: open a native screen with made-up data (Demo) and, after a moment, one of its pop-ups. */
    private fun startPreview() {
        if (preview.startsWith("home") || preview.startsWith("nav") || preview.startsWith("profile") || preview.startsWith("builder") || preview.startsWith("verify") ||
            preview.startsWith("et") || preview.startsWith("dash") || preview.startsWith("earn")) {
            Demo.girl = intent.getBooleanExtra("tf_girl", false)
            web.loadUrl("about:blank")
            watchFrames()
            root.post { pages.startPreview(preview, intent.getStringExtra("tf_open").orEmpty()) }
            return
        }
        Demo.on = true
        Demo.premium = intent.getBooleanExtra("tf_premium", false)
        Demo.girl = intent.getBooleanExtra("tf_girl", false)
        Demo.noBlur = intent.getBooleanExtra("tf_noblur", false)
        Demo.noChats = intent.getBooleanExtra("tf_nochats", false)
        intent.getStringExtra("tf_lite")?.let { Lite.forceForTests(it == "1") }
        web.loadUrl("about:blank")
        if (intent.getBooleanExtra("tf_noweb", false)) web.visibility = View.GONE
        watchFrames()
        val open = intent.getStringExtra("tf_open").orEmpty()
        // one chat (/chat/:id): the whole screen
        if (preview == "chatroom") {
            val r = room()
            r.visibility = View.VISIBLE
            r.open(intent.getStringExtra("tf_chat") ?: "m1")
            if (open.isNotEmpty()) root.postDelayed({ r.preview(open) }, 1500)
            return
        }
        val screen = if (preview.startsWith("secret")) secret else explore
        val lp = screen.layoutParams as FrameLayout.LayoutParams
        lp.height = (resources.displayMetrics.heightPixels - 90 * resources.displayMetrics.density).toInt()
        screen.layoutParams = lp
        val view = when (preview) { "secret-map" -> "map"; "secret-chat" -> "chat"; else -> "home" }
        if (screen === explore) explore.setShown(true) else secret.setShown(true, view, "")
        if (open.isNotEmpty()) root.postDelayed({ secret.previewOpen(open) }, 3500)
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
        web.loadUrl("about:blank")
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

    override fun onResume() {
        super.onResume()
        explore.onAppVisible(true)
        secret.onAppVisible(true)
        pages.onAppVisible(true)
        chatRoom?.onAppVisible(true)
        wallet.onAppVisible(true)
        if (preview.isEmpty() && !walletPreviewing && !previewing) updater.check() // test builds: "Update available" when GitHub has a newer APK
        dance.onAppVisible(true)
    }

    override fun onPause() {
        explore.onAppVisible(false)
        secret.onAppVisible(false)
        pages.onAppVisible(false)
        chatRoom?.onAppVisible(false)
        wallet.onAppVisible(false)
        dance.onAppVisible(false)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.takeIf { isOurs(it) }?.let { web.loadUrl(it.toString()) }
    }

    override fun onDestroy() {
        games.shutdown()
        dance.leave()
        DanceVideo.destroyAll()
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
