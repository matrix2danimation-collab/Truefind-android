package app.truefind.android.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ScrollView
import app.truefind.android.Api
import app.truefind.android.MainActivity
import app.truefind.android.Ui
import org.json.JSONObject

/**
 * NATIVE SIGN-IN. Whenever the web app is on one of its sign-in pages, the phone draws that page itself
 * (this layer, over the web page, which stays underneath, hidden):
 *   /welcome (and "/" while signed out)  → Get Started, mobile number, WhatsApp OTP, gender, profile
 *   /login /register /register/phone /forgot-password /reset-password /mfa → email sign-in pages
 *   /register/basic-profile /register/complete-profile → the old profile pages (signed in)
 *   /delete-account → how to delete an account
 * Every page makes the SAME Base44 calls as the web page (LoginApi): WhatsApp OTP, the one-time sign-in
 * link of returning members, Google for new members, Base44's email/password routes. A sign-in is handed
 * to the web page (its token, or the sign-in link opened in it), so the rest of the app is signed in too.
 * The web page stays the one place that knows where you are: page changes go through its router.
 */
@SuppressLint("ViewConstructor")
class LoginLayer(ctx: Context, internal val host: Host) : FrameLayout(ctx) {

    interface Host {
        /** the web app's navigate(path, { replace }) */
        fun go(path: String, replace: Boolean)
        /** a full page load in the web view (sign-in links, Home after sign-up) */
        fun loadWeb(url: String)
        fun js(code: String)
        fun canWebBack(): Boolean
        fun webBack()
        fun finishApp()
        fun pickPhoto(done: (ByteArray?, String) -> Unit)
        /** opens [url] in a Chrome Custom Tab (Google's own sign-in page); false when there is no browser */
        fun openTab(url: String): Boolean
    }

    companion object {
        const val NAME = "TFLogin"
        private val PAGES = mapOf(
            "/welcome" to "welcome", "/register/phone" to "welcome", "/login" to "login", "/register" to "register",
            "/forgot-password" to "forgot", "/reset-password" to "reset", "/mfa" to "mfa", "/delete-account" to "delete",
            "/register/basic-profile" to "basic", "/register/complete-profile" to "complete",
        )
        fun norm(p: String): String = p.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        val EASE = PathInterpolator(0.25f, 0.8f, 0.25f, 1f)

        /** Runs in the page as early as possible (and again after each load, harmless). */
        val JS = """
(function(){
  if (window.__tfLoginInit) return; window.__tfLoginInit = true;
  var L = window.$NAME; if (!L) return;
  // The app draws the sign-in pages itself: the web's own ones stay hidden underneath.
  var st = document.createElement('style');
  st.textContent = '.tfw-root{visibility:hidden!important}html.tfln #root .tf-stage{visibility:hidden!important}';
  (document.head || document.documentElement).appendChild(st);
  var PUB = /^\/(welcome|login|register|register\/phone|forgot-password|reset-password|mfa|delete-account)${'$'}/;
  var PROT = /^\/register\/(basic-profile|complete-profile)${'$'}/;
  function tok(){ try { var t = localStorage.getItem('base44_access_token') || localStorage.getItem('token') || ''; if (t && t.charAt(0) === '"') { try { t = JSON.parse(t); } catch(e){} } return String(t || ''); } catch(e){ return ''; } }
  function ours(){
    var p = (location.pathname || '/').replace(/\/+${'$'}/, '') || '/', s = location.search || '';
    if (p === '/mfa' && (/[?&]mfa_code=/.test(s) || window.__tfMfaWeb)) { window.__tfMfaWeb = true; return false; }
    if (PUB.test(p)) return true;
    if (PROT.test(p)) return !!tok();
    return false;
  }
  var last = '';
  function report(){
    try {
      var h = document.documentElement.classList, on = ours();
      if (on !== h.contains('tfln')) { if (on) h.add('tfln'); else h.remove('tfln'); }
      var o = { path: location.pathname || '/', search: location.search || '', welcome: !!document.querySelector('.tfw-root'), signed: !!tok(), mfaWeb: !!window.__tfMfaWeb };
      var s = JSON.stringify(o);
      if (s !== last) { last = s; L.state(location.origin, s); }
    } catch(e){}
  }
  window.__tfLoginReport = function(){ last = ''; report(); };
  ['pushState','replaceState'].forEach(function(k){ var o = history[k]; history[k] = function(){ var r = o.apply(this, arguments); report(); return r; }; });
  addEventListener('popstate', function(){ report(); });
  var t = 0;
  function soon(){ if (!t) t = setTimeout(function(){ t = 0; report(); }, 60); }
  function watch(){ if (!document.body) { setTimeout(watch, 30); return; } new MutationObserver(soon).observe(document.body, { childList: true }); }
  watch();
  setInterval(report, 700);
  report();
})();
""".trimIndent()
    }

    private val main = Handler(Looper.getMainLooper())

    // ── what the web page says ──
    internal var path = "/"; private set
    internal var search = ""; private set
    private var webWelcome = false
    private var gotState = false
    private var cover = false
    private var token = ""
    /** the web view is on someone else's page (Google): this layer steps aside */
    private var foreign = false
    private var pageLoading = false
    private var pageDoneAt = 0L

    // ── pages ──
    private var page: LPage? = null
    private var pageKey = ""
    internal var preview = ""
    /** called when this layer appears or goes (MainActivity moves the pages) */
    var onChange: () -> Unit = {}
    /** the time of the last Back that this layer started (the next page move slides back) */
    private var backAt = 0L

    init {
        visibility = View.GONE
        isClickable = true // nothing falls through to the hidden web page
        isFocusable = true
        setBackgroundColor(Color.WHITE)
        clipChildren = false
    }

    // ═════════════════ the web page link ═════════════════
    fun attach(web: WebView, scriptAtStart: Boolean) {
        web.addJavascriptInterface(Bridge(), NAME)
        if (scriptAtStart) androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, JS, MainActivity.ORIGINS)
    }

    inner class Bridge {
        @JavascriptInterface
        fun state(origin: String?, json: String?) {
            if (origin == null || !MainActivity.ORIGINS.contains(origin) || json == null) return
            main.post { onState(origin, json) }
        }
    }

    private var mfaWeb = false

    private fun onState(origin: String, json: String) {
        if (preview.isNotEmpty()) return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        path = o.optString("path", "/").ifEmpty { "/" }
        search = o.optString("search", "")
        webWelcome = o.optBoolean("welcome")
        mfaWeb = o.optBoolean("mfaWeb")
        LoginApi.here = origin + path + search
        gotState = true
        foreign = false
        refresh()
    }

    /** From the app: the login token the page holds and whether a web pop-up covers the page. */
    fun setRoute(token: String, cover: Boolean) {
        if (preview.isNotEmpty()) return
        val signedNow = token.isNotEmpty() && this.token.isEmpty()
        this.token = token
        this.cover = cover
        refresh()
        if (signedNow) page?.signedIn()
    }

    /** The web view starts / finishes a page (sign-in hand-offs wait for it). */
    fun onWebPage(url: String, finished: Boolean) {
        if (preview.isNotEmpty()) return
        val u = try { Uri.parse(url) } catch (e: Exception) { null }
        val ours = u != null && u.scheme == "https" && MainActivity.ORIGINS.contains("https://${u.host}")
        if (!finished) { pageLoading = true; foreign = !ours && url.startsWith("http"); gotState = false; mfaWeb = false }
        else { pageLoading = false; pageDoneAt = SystemClock.uptimeMillis() }
        refresh()
    }

    // ═════════════════ which page shows ═════════════════
    private fun wantedKey(): String {
        if (cover || foreign || !gotState) return ""
        val p = norm(path)
        val key = PAGES[p]
        if (key != null) {
            // a Google / SSO sign-in finishing its second step (/mfa?mfa_code=…) is the web page's own
            if (key == "mfa" && (mfaWeb || Regex("[?&]mfa_code=").containsMatchIn(search))) return ""
            if ((key == "basic" || key == "complete") && token.isEmpty()) return ""
            return key
        }
        // the web shows its Welcome ("/" while signed out): drawn here instead
        if (webWelcome) return "welcome"
        return ""
    }

    val shown: Boolean get() = visibility == View.VISIBLE

    private fun refresh() {
        if (preview.isNotEmpty()) return
        val h = hand
        if (h != null) { checkHand(h); if (hand != null) { if (visibility != View.VISIBLE && !foreign) { visibility = View.VISIBLE; onChange() }; return } }
        val key = wantedKey()
        if (key.isEmpty()) {
            if (visibility == View.VISIBLE) {
                visibility = View.GONE
                closeSheets()
                page?.let { it.leave(); removeView(it) }
                page = null; pageKey = ""
                onChange()
            }
            return
        }
        val appearing = visibility != View.VISIBLE
        if (key != pageKey || page == null) open(key, !appearing) else page?.route(search)
        if (appearing) { visibility = View.VISIBLE; onChange() }
    }

    private fun make(key: String): LPage = when (key) {
        "welcome" -> WelcomePage(context, this)
        "login" -> LoginPage(context, this)
        "register" -> RegisterPage(context, this)
        "forgot" -> ForgotPage(context, this)
        "reset" -> ResetPage(context, this)
        "mfa" -> MfaPage(context, this)
        "delete" -> DeletePage(context, this)
        "basic" -> BasicProfilePage(context, this)
        else -> CompleteProfilePage(context, this)
    }

    /** shows page [key]; with a move (slide + fade, like the app's other pages) when one was already showing */
    private fun open(key: String, animate: Boolean) {
        closeSheets()
        val old = page
        val p = make(key)
        page = p; pageKey = key
        addView(p, 0, LayoutParams(-1, -1))
        p.route(search)
        if (old != null) {
            old.leave()
            if (animate && android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) {
                val back = SystemClock.uptimeMillis() - backAt < 900
                val dir = if (back) -1f else 1f
                bringChildToFront(old)
                p.translationX = dir * Ui.dp(30f)
                p.animate().translationX(0f).setDuration(280).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
                old.animate().translationX(-dir * Ui.dp(30f)).alpha(0f).setDuration(280).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction { removeView(old) }.start()
            } else removeView(old)
        }
    }

    // ═════════════════ moving around (through the web app's router) ═════════════════
    /** navigate(path) of the web app */
    fun go(path: String, replace: Boolean = false) {
        if (preview.isNotEmpty()) { toast("→ $path"); return }
        host.go(path, replace)
    }
    /** the phone's Back / a page's back arrow: the page before (web history) */
    fun back() {
        backAt = SystemClock.uptimeMillis()
        if (preview.isNotEmpty()) { host.finishApp(); return }
        host.webBack()
    }

    /**
     * Back: a pop-up closes first, then the page's own steps, then the page before it (web history, like the
     * browser). Nothing before it: Get Started leaves the app; another sign-in page goes to Get Started (in its
     * place, so Back can never go round in a circle); the signed-in profile pages go Home.
     */
    fun onBack(): Boolean {
        if (sheets.isNotEmpty()) { sheets.last().second(); return true }
        val p = page ?: return false
        if (p.onBack()) return true
        backAt = SystemClock.uptimeMillis()
        if (preview.isNotEmpty()) { host.finishApp(); return true }
        if (host.canWebBack()) { host.webBack(); return true }
        when (pageKey) {
            "welcome" -> host.finishApp()
            "basic", "complete" -> host.go("/", true)
            else -> host.go("/welcome", true)
        }
        return true
    }

    fun onAppVisible(v: Boolean) { page?.visible(v); GoogleSignIn.appVisible(v) }

    // ═════════════════ signing in through the web page ═════════════════
    private class Hand(val kind: String, val at: Long, val fail: (String) -> Unit)
    private var hand: Hand? = null
    private val handTick = object : Runnable { override fun run() { hand?.let { checkHand(it); if (hand != null) main.postDelayed(this, 500) else refresh() } } }

    /**
     * The web page takes over a sign-in (a one-time sign-in link, a new token, the server's log-out, Home):
     * this layer keeps the page on screen until the web page is signed in somewhere else, or says it is not.
     * [fail] gets "session" (still signed out), or "timeout".
     */
    internal fun handOff(kind: String, fail: (String) -> Unit = {}) {
        hand = Hand(kind, SystemClock.uptimeMillis(), fail)
        main.removeCallbacks(handTick); main.postDelayed(handTick, 600)
    }
    internal fun handActive() = hand != null
    internal fun dropHand() { hand = null; main.removeCallbacks(handTick) }

    private fun checkHand(h: Hand) {
        val now = SystemClock.uptimeMillis()
        val age = now - h.at
        if (age < 700) return
        val loginPage = PAGES.containsKey(norm(path)) || ((norm(path) == "/" || norm(path) == "/home") && webWelcome)
        when (h.kind) {
            // signed in and out of the sign-in pages: done (Home shows)
            "link", "token", "home" -> {
                if (token.isNotEmpty() && !loginPage && !pageLoading) { dropHand(); return }
                // the page came back signed out (the link made no session)
                if (h.kind == "link" && token.isEmpty() && !pageLoading && webWelcome && pageDoneAt > h.at && now - pageDoneAt > 1500) { dropHand(); h.fail("session"); return }
                // a token handed over and the web page is on a sign-in page with it: done (the page carries on)
                if (h.kind == "token" && token.isNotEmpty() && !pageLoading && pageDoneAt > h.at) { dropHand(); return }
                if (age > (if (h.kind == "home") 15000 else 25000)) { dropHand(); h.fail("timeout") }
            }
            // the server's log-out: done once a fresh page is up
            "logout" -> if (!pageLoading && pageDoneAt > h.at && token.isEmpty()) dropHand() else if (age > 15000) { dropHand(); h.fail("timeout") }
            else -> dropHand()
        }
    }

    /**
     * Hands a new login token to the web page (base44.auth.setToken: both keys) and loads [next] there: a full
     * page load, like the web's location.href ([replace] = location.replace, no step back to the sign-in page).
     */
    internal fun signInWith(tok: String, next: String, replace: Boolean = false) {
        Api.token = tok
        handOff("token") { }
        host.js("(function(){try{localStorage.setItem('base44_access_token',${JSONObject.quote(tok)});localStorage.setItem('token',${JSONObject.quote(tok)});}catch(e){}location.${if (replace) "replace" else "assign"}(${JSONObject.quote(next)});})()")
    }
    /** base44.auth.setToken only (the page stays): MFA steps that return a new sign-in before going on */
    internal fun setToken(tok: String) {
        if (tok.isEmpty()) return
        Api.token = tok
        host.js("(function(){try{localStorage.setItem('base44_access_token',${JSONObject.quote(tok)});localStorage.setItem('token',${JSONObject.quote(tok)});}catch(e){}})()")
    }

    /** The server's log-out (tokens and cookies cleared), back on /welcome. */
    internal fun serverLogout(then: String = "/welcome") {
        Api.token = ""
        handOff("logout") { }
        host.js("(function(){try{localStorage.removeItem('base44_access_token');localStorage.removeItem('token')}catch(e){}var o=location.origin;location.href=o+'/api/apps/auth/logout?from_url='+encodeURIComponent(o+${JSONObject.quote(then)})})()")
    }

    /** Google's sign-in returned to the app (truefind://auth?…) */
    fun onAuthReturn(uri: Uri) { GoogleSignIn.onReturn(this, uri) }

    // ═════════════════ pop-ups (bottom sheets) and short messages ═════════════════
    private val sheets = ArrayList<Pair<View, () -> Unit>>()
    private fun closeSheets() { for (s in sheets.toList()) s.second(); sheets.clear() }

    /**
     * A bottom sheet over the page (the web's .tfw-sheet-wrap): dim fades in, the panel slides up (.34 s, 70 ms late).
     * [maxFrac] of the screen at most (the panel scrolls inside). Returns close().
     */
    internal fun sheet(panel: View, dim: Int = Color.argb(107, 30, 8, 48), maxFrac: Float = 0.8f, onClose: () -> Unit = {},
                       slideMs: Long = 340, ease: android.view.animation.Interpolator = PathInterpolator(0.22f, 0.61f, 0.36f, 1f), delayMs: Long = 70): () -> Unit {
        val box = FrameLayout(context).apply { isClickable = true }
        val dimV = View(context).apply { setBackgroundColor(dim); alpha = 0f }
        box.addView(dimV, LayoutParams(-1, -1))
        val maxH = ((height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * maxFrac).toInt()
        val holder = object : FrameLayout(context) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)) }
        }
        holder.addView(panel, LayoutParams(-1, -2))
        box.addView(holder, LayoutParams(-1, -2, Gravity.BOTTOM))
        var closed = false
        lateinit var close: () -> Unit
        close = {
            if (!closed) {
                closed = true
                sheets.removeAll { it.first === box }
                LK.hideKeyboard(box)
                dimV.animate().alpha(0f).setDuration(200).start()
                holder.animate().translationY(holder.height.toFloat()).setDuration(240).setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                    .withEndAction { removeView(box) }.start()
                onClose()
            }
        }
        dimV.setOnClickListener { close() }
        addView(box, LayoutParams(-1, -1))
        box.elevation = Ui.dp(30f)
        sheets.add(box to close)
        dimV.animate().alpha(1f).setDuration(200).start()
        holder.translationY = 4000f
        holder.post {
            holder.translationY = holder.height.toFloat()
            holder.animate().translationY(0f).setStartDelay(delayMs).setDuration(slideMs).setInterpolator(ease).start()
        }
        return close
    }

    private var toastV: View? = null
    private val toastHide = Runnable { toastV?.let { v -> v.animate().alpha(0f).setDuration(180).withEndAction { removeView(v) }.start() }; toastV = null }
    /** a short message at the bottom (test mode: where a tap would go) */
    internal fun toast(text: String) {
        main.removeCallbacks(toastHide)
        toastV?.let { removeView(it) }
        val v = LK.t(context, text, 13.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = CssBox(Color.argb(235, 30, 10, 60), 999f)
            setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(10f))
            elevation = Ui.dp(60f)
        }
        addView(v, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dpi(36f) })
        toastV = v
        main.postDelayed(toastHide, 2600)
    }

    // ═════════════════ test mode (emulator pictures on GitHub only) ═════════════════
    /**
     * [scene] = page (+ a variant after "-"), e.g. "welcome", "welcome-verify", "login-phone"; [open] = an action on it.
     * Made-up answers (LoginDemo), no web page, no login.
     */
    fun startPreview(scene: String, open: String) {
        preview = scene
        LoginApi.demo = LoginDemo::answer
        LoginDemo.scene = scene
        gotState = true
        path = when (scene.substringBefore('-')) {
            "login" -> "/login"; "register" -> "/register"; "forgot" -> "/forgot-password"; "reset" -> "/reset-password"
            "mfa" -> "/mfa"; "delete" -> "/delete-account"; "basic" -> "/register/basic-profile"; "complete" -> "/register/complete-profile"
            else -> "/welcome"
        }
        search = if (scene.startsWith("reset")) "?token=demo" else ""
        val key = PAGES[path] ?: "welcome"
        visibility = View.VISIBLE
        val p = make(key)
        page = p; pageKey = key
        addView(p, 0, LayoutParams(-1, -1))
        p.route(search)
        main.postDelayed({
            android.util.Log.i("TFDBG", "preview open $open")
            p.preview(scene.substringAfter('-', ""), open)
        }, 1800)
    }
}

/** One native sign-in page (a full page of this layer). */
internal abstract class LPage(ctx: Context, val layer: LoginLayer) : FrameLayout(ctx) {
    init { clipChildren = false }
    /** shown, or its address (?query) changed */
    open fun route(search: String) {}
    /** the phone's Back: true when the page handled it (a step back inside the page) */
    open fun onBack(): Boolean = false
    /** leaving for good: stop timers */
    open fun leave() {}
    /** a login token just arrived (a sign-in finished) */
    open fun signedIn() {}
    open fun visible(v: Boolean) {}
    /** test mode: [variant] of the page and an [open] action */
    open fun preview(variant: String, open: String) {}

    protected fun query(search: String, key: String): String = try { Uri.parse("x://x/$search").getQueryParameter(key).orEmpty() } catch (e: Exception) { "" }

    /** a page that scrolls (fills the screen when shorter) */
    protected fun scroller(content: View): ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true
        clipChildren = false
        addView(content, LayoutParams(-1, -2))
    }
}
