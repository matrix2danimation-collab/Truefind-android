package app.truefind.android

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * The native Home, Profile (with Settings and every screen inside it), Edit your
 * profile, Verification and the bottom bar — laid over the Base44 web app.
 *
 * They follow the web app's address exactly like the native Explore does: the
 * web app stays the one source of truth for where you are (so the phone's Back,
 * the web's own back rules (PhoneBack.jsx) and every link keep working), and a
 * native screen shows whenever its address is open:
 *   /, /home            → Home              /profile[?…]          → Profile
 *   /profile/edit       → Edit your profile  /register/verification → Verification
 *   /, /home, /explore, /chats → the bottom bar (the web's own bar is hidden there)
 * The web page under a native screen is hidden (not removed), and any web
 * pop-up that must be seen (incoming call, after-call sheet, a dialog on
 * Recent, maintenance …) puts the native screens aside until it closes.
 */
class NativePages(private val act: ComponentActivity, private val root: FrameLayout, private val web: WebView, private val onChange: () -> Unit) {

    companion object {
        const val NAME = "TFHome"
        val NAV_ON = setOf("/", "/home", "/explore", "/chats")

        /** Runs in the page as early as possible (and again after each load, harmless). */
        val JS = """
(function(){
  if (window.__tfHomeInit) return; window.__tfHomeInit = true;
  var H = window.$NAME; if (!H) return;
  // The Daily Free Coins pop-up is shown by the native Home (once a day); the web Home never opens its own.
  try {
    var gi = Storage.prototype.getItem;
    Storage.prototype.getItem = function(k){
      if (typeof k === 'string' && k.indexOf('tf_daily_pop:') === 0) return new Date(Date.now() + 19800000).toISOString().slice(0, 10);
      return gi.apply(this, arguments);
    };
  } catch(e){}
  var css = document.createElement('style');
  css.textContent = 'html.tfnn nav.tfnav{display:none!important}'
    + 'html.tfhn .tf-tab[data-tab="home"]{visibility:hidden!important}html.tfhn .tfdc{display:none!important}'
    + 'html.tfpn .tfp-root{visibility:hidden!important}html.tfbn .tfb{visibility:hidden!important}html.tfvn .tfv{visibility:hidden!important}'
    // In the app a tab switch is instant (as in other Android apps): the web's 0.3 s tab slide
    // stuttered on phones and showed half-moved pages; the page going out is simply hidden.
    + '.tf-pg-in,.tf-pg-out{animation:none!important}.tf-pg-out{visibility:hidden!important}';
  (document.head || document.documentElement).appendChild(css);
  // The app's own router (React Router's navigator), so a native tap opens a page
  // exactly like a tap in the web app (push / replace, with its state).
  var navCache = null;
  function findNav(){
    if (navCache) return navCache;
    try {
      var root = document.getElementById('root'); if (!root) return null;
      var key = null, ks = Object.keys(root);
      for (var i = 0; i < ks.length; i++) if (ks[i].indexOf('__reactContainer$') === 0) { key = ks[i]; break; }
      if (!key) return null;
      var stack = [root[key]], n = 0;
      while (stack.length && n < 20000) {
        var f = stack.pop(); n++;
        if (!f) continue;
        var v = f.memoizedProps && f.memoizedProps.value;
        if (v && v.navigator && typeof v.navigator.push === 'function' && typeof v.navigator.replace === 'function') { navCache = v.navigator; return navCache; }
        if (f.sibling) stack.push(f.sibling);
        if (f.child) stack.push(f.child);
      }
    } catch(e){}
    return null;
  }
  window.__tfNav = function(path, state, replace){
    var nav = findNav();
    if (nav) { try { if (replace) nav.replace(path, state || null); else nav.push(path, state || null); return 'nav'; } catch(e){} }
    if (replace) {
      var i0 = (history.state && history.state.idx) || 0;
      history.replaceState({ usr: state || null, key: Math.random().toString(36).slice(2, 10), idx: i0 }, '', path);
    } else {
      var i1 = ((history.state && history.state.idx) || 0) + 1;
      history.pushState({ usr: state || null, key: Math.random().toString(36).slice(2, 10), idx: i1 }, '', path);
    }
    dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    return 'pop';
  };
  // Bottom bar tapped on the tab already open: back to the top of that page.
  window.__tfTop = function(){
    try {
      var el = document.querySelector('.tf-tab:not(.off):not(.tf-pg-out)');
      if (el) el.scrollTo({ top: 0, behavior: 'smooth' }); else window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch(e){}
  };
  window.__tfReturnCall = function(){ var b = document.querySelector('button[aria-label="Return to your call"]'); if (b) b.click(); };
  function vis(el){
    if (!el || !el.getClientRects().length) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.right > 0 && r.bottom > 0 && r.left < innerWidth && r.top < innerHeight;
  }
  function hidden(el){
    var h = document.documentElement.classList;
    if (el.closest('.tfc') || el.closest('.tfdc')) return true;
    if (h.contains('tfsd') && el.closest('#root .sd')) return true;
    if (h.contains('tfhn') && el.closest('.tf-tab[data-tab="home"]')) return true;
    if (h.contains('tfpn') && el.closest('.tfp-root')) return true;
    if (h.contains('tfbn') && el.closest('.tfb')) return true;
    if (h.contains('tfvn') && el.closest('.tfv')) return true;
    return false;
  }
  function covered(){
    var l = document.querySelectorAll('.tfic,[role="alertdialog"],[role="dialog"],[aria-modal="true"]');
    for (var i = 0; i < l.length; i++) if (vis(l[i]) && !hidden(l[i])) return true;
    return false;
  }
  var last = '';
  function report(){
    try {
      var st = history.state || {};
      var badge = document.querySelector('nav.tfnav .tfnav-badge');
      var pill = document.querySelector('button[aria-label="Return to your call"]');
      var ht = '';
      if (document.documentElement.classList.contains('tfhn')) { var te = document.querySelector('.tf-tab[data-tab="home"] [role="status"]'); if (te) ht = String(te.textContent || '').trim(); }
      var o = { path: location.pathname || '/', search: location.search || '', usr: st.usr == null ? null : st.usr,
        idx: st.idx == null ? -1 : st.idx, cover: covered(), unread: badge ? String(badge.textContent || '') : '',
        pill: !!(pill && pill.getClientRects().length), htoast: ht, cls: String(document.documentElement.className || '') };
      var s = JSON.stringify(o);
      if (s !== last) { last = s; H.state(location.origin, s); }
    } catch(e){}
  }
  window.__tfReport = function(){ last = ''; report(); };
  ['pushState','replaceState'].forEach(function(k){
    var o = history[k];
    history[k] = function(){ var r = o.apply(this, arguments); setTimeout(report, 0); return r; };
  });
  addEventListener('popstate', function(){ setTimeout(report, 0); });
  var t = 0;
  function soon(){ if (!t) t = setTimeout(function(){ t = 0; report(); }, 120); }
  function watch(){
    if (!document.body) { setTimeout(watch, 50); return; }
    new MutationObserver(soon).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'aria-label'] });
  }
  watch();
  setInterval(report, 1000);
  report();
})();
""".trimIndent()
    }

    private val main = Handler(Looper.getMainLooper())

    // ── what the web page says ──
    var path = "/"; private set
    var search = ""; private set
    var usr: JSONObject? = null; private set
    var idx = -1; private set
    private var cover = false
    private var pill = false
    private var unread = 0
    private var gotState = false
    private var lastHomeToast = ""
    var preview = ""                 // test mode (emulator screenshots): which screen
    private var appVisible = true

    // ── the native screens ──
    val layer = TfLayer(root)
    val toast = TfToast(root)
    val nav = NavBar(act) { key, p -> tab(key, p) }
    val home = HomeScreen(act, this)
    val profile = ProfileScreen(act, this)
    val builder = BuilderScreen(act, this)
    val verify = VerifyScreen(act, this)
    private val callPill = CallPill(act) { web.evaluateJavascript("window.__tfReturnCall && window.__tfReturnCall()", null) }
    private val screens: List<View> get() = listOf(home, profile, builder, verify)
    /** the full pages this part shows (for the move between pages) */
    val pageViews: List<View> get() = screens
    /** the Daily Free Coins pop-up while it is open */
    var dailySheet: DailyClaimSheet? = null
    fun rootView(): FrameLayout = root
    /** the activity (the camera needs it) */
    val activity: ComponentActivity get() = act
    /** signed in at the last update */
    var signedIn = false; private set
    /** camera + microphone for the live selfie videos (MainActivity asks the phone) */
    var askCamera: ((Boolean) -> Unit) -> Unit = { it(false) }

    init {
        for (s in screens) { s.visibility = View.GONE; root.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        nav.visibility = View.GONE
        nav.elevation = Ui.dp(16f); nav.outlineProvider = null
        root.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        callPill.visibility = View.GONE
        callPill.elevation = Ui.dp(18f)
        root.addView(callPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        layer.onChange = { onChange() }
    }

    /** Before the page loads: the bridge and the script. */
    fun attach(scriptAtStart: Boolean) {
        web.addJavascriptInterface(Bridge(), NAME)
        if (scriptAtStart) androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, JS, MainActivity.ORIGINS)
    }

    inner class Bridge {
        @JavascriptInterface
        fun state(origin: String?, json: String?) {
            if (origin == null || !MainActivity.ORIGINS.contains(origin) || json == null) return
            main.post { onState(json) }
        }
    }

    private fun onState(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val rp = o.optString("path", "/").ifEmpty { "/" }
        // a native tap has already switched the screen: an older address still on its way is held back
        if (leading(rp)) { heldJson = json; return }
        if (lead != null) { lead = null; heldJson = null; main.removeCallbacks(leadEnd) }
        path = rp
        search = o.optString("search", "")
        usr = o.optJSONObject("usr")
        idx = o.optInt("idx", -1)
        cover = o.optBoolean("cover")
        pill = o.optBoolean("pill")
        val u = o.optString("unread").trim()
        unread = if (u.endsWith("+")) 100 else u.toIntOrNull() ?: 0
        gotState = true
        // a new page (reload) has none of the classes that hide the web screens: set them again
        val cls = o.optString("cls", "").split(' ').filter { it.isNotEmpty() }.toSet()
        if (classes.any { (c, on) -> on != cls.contains(c) }) classes.clear()
        // a message of the (hidden) web Home, e.g. "Invite from … added": shown on the native Home
        val ht = o.optString("htoast", "")
        if (ht.isNotEmpty() && ht != lastHomeToast && onHome) home.say(ht)
        lastHomeToast = ht
        onChange()
    }

    // ═════════════════ a native tap leads ═════════════════
    // The web reports a new address only after it has done its own work (on a slow phone that took
    // half a second, and in between the old native screen had gone and the new one wasn't there yet:
    // a white flash and the web's half-moved page). Now a tap on a native screen's page switches the
    // native screens at once; the web follows underneath. If the web doesn't confirm within 1.5 s,
    // what it says wins again.
    /** Settings → Check for update (MainActivity asks GitHub) */
    var checkUpdate: () -> Unit = {}
    /** MainActivity follows the same address (Explore, Secret Dating) */
    var onLead: (String, String) -> Unit = { _, _ -> }
    var onLeadEnd: () -> Unit = {}
    private var lead: String? = null
    private var leadUntil = 0L
    private var heldJson: String? = null
    private val leadEnd = Runnable {
        lead = null
        val j = heldJson; heldJson = null
        // the web never confirmed: go by what it says now (it may not have sent anything new)
        if (j != null) onState(j) else web.evaluateJavascript("window.__tfReport && window.__tfReport()", null)
        onLeadEnd()
    }
    private fun norm(p: String): String = p.substringBefore('?').trimEnd('/').ifEmpty { "/" }.let { if (it == "/home") "/" else it }
    /** true while a native tap leads and [p] is another (older) address */
    fun leading(p: String): Boolean {
        val l = lead ?: return false
        return android.os.SystemClock.uptimeMillis() < leadUntil && norm(p) != l
    }
    private fun shownNatively(p: String, q: String): Boolean =
        p == "/" || p == "/profile" || p == "/profile/edit" || p == "/register/verification" || p == "/explore" ||
            p == "/secret-app" || p == "/dating" || p.startsWith("/chat/") || p == "/wallet" ||
            p == "/dance" || p.startsWith("/dance/") || p == "/live" || p.startsWith("/live/")
    /** Switches the native screens to [target] now (only when a native screen shows it). */
    fun lead(target: String, state: JSONObject?) {
        if (preview.isNotEmpty() || !gotState || !signedIn || cover) return
        val q = target.indexOf('?')
        val tp = norm(target)
        val ts = if (q >= 0) target.substring(q) else ""
        if (!shownNatively(tp, ts) || (norm(path) == tp && search == ts)) return
        lead = tp; leadUntil = android.os.SystemClock.uptimeMillis() + 1500
        main.removeCallbacks(leadEnd); main.postDelayed(leadEnd, 1500)
        path = target.substringBefore('?').ifEmpty { "/" }; search = ts; usr = state
        onLead(path, search)
        onChange()
    }

    // ═════════════════ which screen shows ═════════════════
    private fun p(): String = path.trimEnd('/').ifEmpty { "/" }
    val onHome: Boolean get() = p() == "/" || p() == "/home"
    val onProfile: Boolean get() = p() == "/profile"
    val onBuilder: Boolean get() = p() == "/profile/edit"
    val onVerify: Boolean get() = p() == "/register/verification"

    /** the bottom bar is on screen */
    var navShown = false; private set

    /**
     * Called by MainActivity whenever the page or the login changes.
     * Returns the bottom of the page area for the native Explore (px, or -1 = leave as it is).
     */
    fun update(signedIn: Boolean): Int {
        if (preview.isNotEmpty()) return -1
        this.signedIn = signedIn
        val live = signedIn && gotState
        val showNav = live && NAV_ON.contains(p()) && !cover
        val showHome = live && onHome && !cover
        val showProfile = live && onProfile && !cover
        val showBuilder = live && onBuilder && !cover
        val showVerify = live && onVerify && !cover
        // the web's own pages under the native ones stay hidden (not removed)
        setClass("tfnn", live && NAV_ON.contains(p()))
        setClass("tfhn", live && onHome)
        setClass("tfpn", live && onProfile)
        setClass("tfbn", live && onBuilder)
        setClass("tfvn", live && onVerify)
        navShown = showNav
        nav.visibility = if (showNav) View.VISIBLE else View.GONE
        nav.current = when (p()) { "/", "/home" -> "home"; "/explore" -> "explore"; "/chats" -> "recent"; else -> "" }
        nav.unread = unread
        val innerH = root.height - root.paddingTop - root.paddingBottom
        val pageH = if (showNav && innerH > 0) innerH - nav.barPx else ViewGroup.LayoutParams.MATCH_PARENT
        sizeTo(home, pageH)
        home.setShown(showHome)
        profile.setShown(showProfile, search, usr)
        builder.setShown(showBuilder)
        verify.setShown(showVerify)
        if (!(showHome || showProfile || showBuilder || showVerify)) layer.closeAll()
        // "Call in progress · Return" (the web's pill is under the native screens)
        val pillOn = live && pill && (showHome || showNav || showProfile)
        callPill.visibility = if (pillOn) View.VISIBLE else View.GONE
        if (pillOn) (callPill.layoutParams as FrameLayout.LayoutParams).let { lp ->
            val b = (if (showNav) nav.barPx else 0) + Ui.dpi(if (showNav) 12f else 24f)
            if (lp.bottomMargin != b) { lp.bottomMargin = b; callPill.layoutParams = lp }
        }
        return if (showNav && innerH > 0) innerH - nav.barPx else -1
    }

    private fun sizeTo(v: View, h: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.height != h) { lp.height = h; v.layoutParams = lp }
    }

    private val classes = HashMap<String, Boolean>()
    private fun setClass(c: String, on: Boolean) {
        if (classes[c] == on) return
        classes[c] = on
        web.evaluateJavascript("document.documentElement.classList.${if (on) "add" else "remove"}('$c')", null)
    }

    // ═════════════════ moving around (same as a tap in the web app) ═════════════════
    /** navigate(path, { state, replace }) of the web app */
    fun go(path: String, state: JSONObject? = null, replace: Boolean = false) {
        if (preview.isNotEmpty()) { toast.show("→ $path"); return }
        lead(path, state)
        web.evaluateJavascript("window.__tfNav && window.__tfNav(${JSONObject.quote(path)}, ${state?.toString() ?: "null"}, ${if (replace) "true" else "false"});", null)
    }
    /** navigate(-1), or Home when there is nothing before (the ‹ button of Profile) */
    fun historyBack() {
        if (preview.isNotEmpty()) { act.finish(); return }
        web.evaluateJavascript("history.length > 1 ? history.back() : (window.__tfNav && window.__tfNav('/'))", null)
    }

    /** runs [code] in the page; [done] gets the text it returns (null when none) */
    fun js(code: String, done: (String?) -> Unit) {
        if (preview.isNotEmpty()) { done(null); return }
        web.evaluateJavascript(code) { v -> done(try { if (v == null || v == "null") null else org.json.JSONTokener(v).nextValue() as? String } catch (e: Exception) { null }) }
    }
    /** this phone's id that the web app keeps (deviceId() of presence.js), made the same way when missing */
    fun deviceId(done: (String?) -> Unit) = js("(function(){try{var id=localStorage.getItem('tf_device_id');if(!id||!/^[A-Za-z0-9_-]{8,64}${'$'}/.test(id)){id=((window.crypto&&crypto.randomUUID&&crypto.randomUUID())||(Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,12))).replace(/[^A-Za-z0-9_-]/g,'');localStorage.setItem('tf_device_id',id)}return id}catch(e){return ''}})()", done)

    /**
     * Log out like the web app: off Explore at once (presence offline) and this phone's
     * notifications off (1.5 s at most), then the server's log-out page → /welcome.
     */
    fun logout(presence: Boolean = true) {
        if (preview.isNotEmpty()) { toast.show("→ log out"); return }
        val app = JSONObject.quote(Api.appId); val tok = JSONObject.quote(Api.token)
        val out = "(function(push){var o=location.origin,done=false;function out(){if(done)return;done=true;try{localStorage.removeItem('base44_access_token');localStorage.removeItem('token')}catch(e){}" +
            "location.href=o+'/api/apps/auth/logout?from_url='+encodeURIComponent(o+'/welcome')}" +
            "if(!push){out();return}try{localStorage.removeItem('tf_push_sent')}catch(e){}var job=Promise.resolve();" +
            "try{if('serviceWorker' in navigator&&'PushManager' in window){job=navigator.serviceWorker.getRegistration().then(function(r){return r&&r.pushManager?r.pushManager.getSubscription():null}).then(function(s){if(!s)return;" +
            "return fetch('/api/apps/'+$app+'/functions/pushSubscribe',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',Accept:'application/json','X-App-Id':$app,Authorization:'Bearer '+$tok},body:JSON.stringify({action:'remove',endpoint:s.endpoint})})})}}catch(e){}" +
            "Promise.race([job.catch(function(){}),new Promise(function(r){setTimeout(r,1500)})]).then(out,out)})(${if (presence) "true" else "false"})"
        if (!presence) { web.evaluateJavascript(out, null); return }
        var fired = false
        val fire = { if (!fired) { fired = true; web.evaluateJavascript(out, null) } }
        main.postDelayed({ fire() }, 1500)
        Api.invoke("updatePresence", JSONObject().put("state", "offline"), 1500) { fire() }
    }

    /** navigate(-n) */
    fun backBy(n: Int) {
        if (preview.isNotEmpty()) return
        web.evaluateJavascript("history.go(${-maxOf(1, n)})", null)
    }
    /** the phone's Back on a page without pop-ups: the web history (PhoneBack.jsx fixes where it lands) */
    fun webBack() {
        if (preview.isNotEmpty()) { act.finish(); return }
        if (web.canGoBack()) web.goBack() else go("/home", null, true)
    }

    private fun tab(key: String, path: String) {
        val here = nav.current
        if (key == here) {
            // the tab you are on: back to its top
            when (key) { "home" -> home.scrollTop(); "explore" -> {}; else -> web.evaluateJavascript("window.__tfTop && window.__tfTop()", null) }
            return
        }
        go(path)
    }

    // ═════════════════ phone Back / app on screen ═════════════════
    /** Back: a native pop-up closes first. */
    fun onBack(): Boolean {
        dailySheet?.let { if (it.back()) return true }
        if (layer.back()) return true
        if (home.visibility == View.VISIBLE && home.onBack()) return true
        if (profile.visibility == View.VISIBLE && profile.onBack()) return true
        if (verify.visibility == View.VISIBLE && verify.onBack()) return true
        if (builder.visibility == View.VISIBLE && builder.onBack()) return true
        return false
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        home.onAppVisible(v)
        profile.onAppVisible(v)
        verify.onAppVisible(v)
    }

    // ═════════════════ test mode (emulator screenshots on GitHub only) ═════════════════
    fun startPreview(what: String, open0: String) {
        preview = what
        // "settings=apply,form": an address (?settings=apply) and then a test action on that screen ("form")
        val open = if (!open0.startsWith("?") && open0.substringBefore(",").contains("=")) "?" + open0.replaceFirst(",", "|") else open0
        Demo.on = true
        DemoHome.on = true
        val h = root.resources.displayMetrics.heightPixels
        val navTop = h - Ui.dpi(90f) - nav.barPx
        when {
            what.startsWith("home") -> {
                path = "/"; nav.visibility = View.VISIBLE; nav.current = "home"; nav.unread = 3
                sizeTo(home, navTop); home.setShown(true)
            }
            what.startsWith("nav") -> {
                path = "/explore"; nav.visibility = View.VISIBLE; nav.current = what.removePrefix("nav-").ifEmpty { "explore" }; nav.unread = 12
            }
            what.startsWith("profile") -> {
                path = "/profile"
                profile.setShown(true, if (open.startsWith("?")) open.substringBefore("|") else "", null)
            }
            what.startsWith("builder") -> { path = "/profile/edit"; builder.setShown(true) }
            what.startsWith("verify") -> { path = "/register/verification"; verify.setShown(true) }
        }
        // "?settings=apply|form": the address, then a test action on that screen
        val action = if (open.startsWith("?")) open.substringAfter("|", "") else open
        main.postDelayed({
            android.util.Log.i("TFDBG", "preview open $action")
            if (action.isNotEmpty()) when {
                what.startsWith("home") -> home.previewOpen(action)
                what.startsWith("profile") -> profile.previewOpen(action)
                what.startsWith("verify") -> verify.previewOpen(action)
                what.startsWith("builder") -> builder.previewOpen(action)
            }
        }, 3500)
    }
}

/** "Call in progress · Return" — same pill as the web's ActiveCallBanner. */
class CallPill(ctx: android.content.Context, onTap: () -> Unit) : android.widget.LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        background = Tf.box(Ui.c("#15101E"), 999f)
        setPadding(Ui.dpi(14f), 0, Ui.dpi(6f), 0)
        minimumHeight = Ui.dpi(42f)
        val dot = LiveBlinkDot(ctx)
        addView(dot, Tf.ll(Ui.dpi(8f), Ui.dpi(8f), r = 9f))
        addView(Tf.t(ctx, "Call in progress", 13.5f, android.graphics.Color.WHITE), Tf.ll(r = 9f))
        val ret = Tf.t(ctx, "Return", 12.5f, android.graphics.Color.WHITE).apply {
            gravity = android.view.Gravity.CENTER
            background = Tf.box(Ui.c("#7B2FF7"), 999f)
            setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            compoundDrawablePadding = Ui.dpi(5f)
            setCompoundDrawablesWithIntrinsicBounds(Tf.svg("<svg viewBox=\"0 0 24 24\"><path fill=\"#fff\" d=\"M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z\"/></svg>", 13f), null, null, null)
        }
        addView(ret, Tf.ll(h = Ui.dpi(30f)))
        contentDescription = "Return to your call"
        setOnClickListener { onTap() }
        Ui.pressable(this, 0.97f)
    }
}

/** a small dot blinking 1 → .35 → 1 every 1.2 s */
class LiveBlinkDot(ctx: android.content.Context, private val color: Int = Ui.c("#22C55E"), private val ms: Long = 1200, private val low: Float = .35f) : View(ctx) {
    private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var a = 1f
    private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ms; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { val f = it.animatedValue as Float; val k = if (f < .5f) f / .5f else (1f - f) / .5f; a = 1f - (1f - low) * smooth(k); invalidate() }
    }
    private fun smooth(x: Float) = x * x * (3 - 2 * x)
    // blinks only while it can be seen
    private fun sync() { if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) { if (!anim.isStarted) anim.start() else if (anim.isPaused) anim.resume() } else if (anim.isRunning) anim.pause() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { sync() } }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
    override fun onDraw(c: android.graphics.Canvas) { pt.color = color; pt.alpha = (a * 255).toInt(); c.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, pt) }
}
