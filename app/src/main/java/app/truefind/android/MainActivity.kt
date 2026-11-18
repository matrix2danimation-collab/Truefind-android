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
    private var path = "/"
    private var search = ""
    /** an address the web sent while a native tap was leading (applied when the lead ends) */
    private var heldRoute: String? = null
    private var preview = ""
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

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // camera + microphone for the native live selfie videos (Become a Host / Creator, verification)
    private var camDone: ((Boolean) -> Unit)? = null
    private val askCam = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val cb = camDone ?: return@registerForActivityResult
        camDone = null
        cb(has(Manifest.permission.CAMERA) && has(Manifest.permission.RECORD_AUDIO))
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                chatRoom?.let { r -> if (r.visibility == View.VISIBLE) { if (!r.back()) chatBack(); return } }
                if (pages.onBack()) return
                if (explore.visibility == View.VISIBLE && explore.onBack()) return
                if (secret.visibility == View.VISIBLE && secret.back()) return
                if (preview.isNotEmpty()) { finish(); return }
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
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

    private fun update() {
        if (preview.isNotEmpty()) return
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
    }

    /** Test mode: open a native screen with made-up data (Demo) and, after a moment, one of its pop-ups. */
    private fun startPreview() {
        if (preview.startsWith("home") || preview.startsWith("nav") || preview.startsWith("profile") || preview.startsWith("builder") || preview.startsWith("verify")) {
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

    override fun onResume() {
        super.onResume()
        explore.onAppVisible(true)
        secret.onAppVisible(true)
        pages.onAppVisible(true)
        chatRoom?.onAppVisible(true)
        if (preview.isEmpty()) updater.check() // test builds: "Update available" when GitHub has a newer APK
    }

    override fun onPause() {
        explore.onAppVisible(false)
        secret.onAppVisible(false)
        pages.onAppVisible(false)
        chatRoom?.onAppVisible(false)
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
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
