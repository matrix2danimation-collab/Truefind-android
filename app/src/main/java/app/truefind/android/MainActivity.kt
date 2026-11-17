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
    private lateinit var games: GamesLayer
    private var path = "/"
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

    // the microphone for the games' voice (native Agora)
    private var micDone: ((Boolean) -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cb = micDone
        micDone = null
        cb?.invoke(ok)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (games.visibility == View.VISIBLE && games.onBack()) return
                if (explore.visibility == View.VISIBLE && explore.onBack()) return
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        // test mode (emulator pictures on GitHub only): a native games screen with made-up data
        val gamesPreview = intent?.getStringExtra("tf_games").orEmpty()
        if (gamesPreview.isNotEmpty() && BuildConfig.SELF_UPDATE) { startGamesPreview(gamesPreview); return }
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
                if (!scriptAtStart && isOurs(Uri.parse(url))) view.evaluateJavascript(WebBridge.JS, null)
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
        val js = "window.__tfGo && window.__tfGo(${JSONObject.quote(p)}, ${state?.toString() ?: "null"});"
        web.evaluateJavascript(js, null)
    }

    // ── from the page ──
    override fun onRoute(path: String) {
        this.path = path
        update()
    }

    override fun onInfo(origin: String, token: String, appId: String, navTopCss: Float, cover: Boolean) {
        Api.origin = origin
        if (appId.isNotEmpty()) Api.appId = appId
        Api.token = token
        this.navTopCss = navTopCss
        this.cover = cover
        update()
    }

    private fun update() {
        val onExplore = path.trimEnd('/') == "/explore"
        val show = onExplore && !cover && Api.token.isNotEmpty()
        // native Explore ends where the web's bottom bar starts
        val lp = explore.layoutParams as FrameLayout.LayoutParams
        val h = if (navTopCss > 0) (navTopCss * resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        if (lp.height != h) { lp.height = h; explore.layoutParams = lp }
        if (show != (explore.visibility == View.VISIBLE)) {
            web.evaluateJavascript("document.documentElement.classList.${if (show) "add" else "remove"}('tfnx')", null)
            explore.setShown(show)
        }
        if (previewing) return
        val gamesWas = games.active
        games.setRoute(path, Api.token.isNotEmpty(), cover)
        if (games.active != gamesWas) web.evaluateJavascript("document.documentElement.classList.${if (games.active) "add" else "remove"}('tfgm')", null)
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
    }

    override fun onResume() {
        super.onResume()
        explore.onAppVisible(true)
        if (!previewing) updater.check() // test builds: "Update available" when GitHub has a newer APK
    }

    override fun onPause() {
        explore.onAppVisible(false)
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
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
