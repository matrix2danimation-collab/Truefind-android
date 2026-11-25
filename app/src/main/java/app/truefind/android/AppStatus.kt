package app.truefind.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONObject

/** Small links between the app's parts and the native status screens. */
object AppStatusHooks {
    private val main = Handler(Looper.getMainLooper())
    /** the Control Center saved new settings: read them again now */
    var onConfigChanged: () -> Unit = {}
    fun configChanged() = main.post { onConfigChanged() }
    /** a server answer said "banned" (403) or "maintenance" (503) */
    var onServer: (String) -> Unit = {}
    fun server(what: String) { main.post { onServer(what) } }
}

/**
 * The web app's status screens, drawn by the phone (AppStatusGate.jsx, BannedNotice.jsx,
 * AppErrorBoundary.jsx and the "No internet connection" screen of App.jsx):
 *  - Maintenance (Control Center → Settings): a calm "We'll be back soon" page over the whole
 *    app with Try again; the admin keeps using the app and sees a small black pill instead.
 *  - Announcement: shown once per message as a card at the bottom (never during a call).
 *  - Blocked account: "Your account is blocked" with Sign out.
 *  - Errors: "Something went wrong" (a web screen crashed: Try again / Go to Home), "No internet
 *    connection" (the web's own screen, or True Find could not load at all: Retry, and again by
 *    itself when the internet is back).
 * Settings come from getAppConfig (every 2 minutes and when the app comes back to the screen),
 * the web page tells what its own screens show (the script below), and server answers "banned" /
 * "maintenance" are noticed at once. The web's own copies are hidden under these.
 */
class AppStatus(
    private val act: ComponentActivity, private val root: FrameLayout, private val host: Host,
    private val go: (String) -> Unit, private val logout: () -> Unit,
) {
    /** the sign-in web page (only there while signing in or out) and the app's own sign-in check */
    interface Host {
        /** runs [code] in the sign-in web page, when there is one */
        fun webJs(code: String)
        /** loads [url] in the sign-in web page */
        fun webLoad(url: String)
        /** "No internet connection" → Retry: the app asks the server again who is signed in */
        fun retrySession()
    }
    companion object {
        const val NAME = "TFStatus"
        private val QUIET = Regex("^/(call|admin|welcome|login|register|delete-account|games/(mood-changer|heart-chase|clue-me)|live/|dance/)")

        /** Runs in the page as early as possible (and again after each load, harmless). */
        val JS = """
(function(){
  if (window.__tfStInit) return; window.__tfStInit = true;
  var S = window.$NAME; if (!S) return;
  var css = document.createElement('style');
  css.textContent = 'html.tfst [role="alertdialog"][aria-label="True Find is updating"],html.tfst [role="alertdialog"][aria-labelledby="tf-banned-title"],'
    + 'html.tfst [role="dialog"][aria-label="Announcement"],html.tfst .tf-st-pill{display:none!important}';
  (document.head || document.documentElement).appendChild(css);
  document.documentElement.classList.add('tfst');
  function txt(el){ return el ? String(el.textContent || '') : ''; }
  function crash(){ var l = document.querySelectorAll('[role="alert"]'); for (var i = 0; i < l.length; i++) { var t = txt(l[i]); if (t.indexOf('Something went wrong') >= 0 && t.indexOf('Go to Home') >= 0) return l[i]; } return null; }
  function offline(){ var l = document.querySelectorAll('div.fixed.inset-0'); for (var i = 0; i < l.length; i++) { var t = txt(l[i]); if (t.indexOf('No internet connection') >= 0 && l[i].querySelector('button')) return l[i]; } return null; }
  function btn(box, word){ if (!box) return null; var b = box.querySelectorAll('button'); for (var i = 0; i < b.length; i++) if (txt(b[i]).indexOf(word) >= 0) return b[i]; return null; }
  var last = '';
  function scan(){
    try {
      var m = document.querySelector('[role="alertdialog"][aria-label="True Find is updating"]');
      var maint = null; if (m) { var ps = m.querySelectorAll('p'); maint = ps.length >= 3 ? txt(ps[2]) : ''; }
      var banned = !!document.querySelector('[role="alertdialog"][aria-labelledby="tf-banned-title"]');
      var an = document.querySelector('[role="dialog"][aria-label="Announcement"]');
      var ann = null; if (an) { var ap = an.querySelectorAll('p'); ann = ap.length >= 2 ? txt(ap[1]) : ''; }
      var pill = false, bs = document.querySelectorAll('button');
      for (var i = 0; i < bs.length; i++) if (txt(bs[i]).indexOf('Maintenance is ON') === 0) { bs[i].classList.add('tf-st-pill'); pill = true; }
      var err = crash() ? 'crash' : (offline() ? 'offline' : '');
      var o = JSON.stringify({ maint: maint, banned: banned, ann: ann, pill: pill, err: err });
      if (o !== last) { last = o; S.state(location.origin, o); }
    } catch(e){}
  }
  // the native screens' buttons press the web's own (hidden) ones, so the web app stays in step
  window.__tfStatusAct = function(what){
    try {
      if (what === 'maint') { var m = document.querySelector('[role="alertdialog"][aria-label="True Find is updating"]'); var b = btn(m, 'Try again'); if (b) b.click(); }
      else if (what === 'ann') { var a = document.querySelector('[role="dialog"][aria-label="Announcement"]'); var c = btn(a, 'OK'); if (c) c.click(); }
      else if (what === 'crash') { var d = btn(crash(), 'Try again'); if (d) d.click(); }
      else if (what === 'offline') { var e = btn(offline(), 'Retry'); if (e) e.click(); }
    } catch(e){}
    last = ''; setTimeout(scan, 50);
  };
  var t = 0;
  function soon(){ if (!t) t = setTimeout(function(){ t = 0; scan(); }, 150); }
  function watch(){ if (!document.body) { setTimeout(watch, 50); return; } new MutationObserver(soon).observe(document.body, { childList: true, subtree: true }); }
  watch();
  setInterval(scan, 1500);
  scan();
})();
""".trimIndent()
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("tf_status", Context.MODE_PRIVATE)

    // ── the app's live settings (getAppConfig) ──
    private var cfg = JSONObject()
    private var isAdmin = false
    private var fetchedAt = 0L
    private var inflight = false
    private var startedAt = android.os.SystemClock.uptimeMillis()

    // ── what the web page's own screens show ──
    private var webMaint: String? = null
    private var webBanned = false
    private var webAnn: String? = null
    private var webErr = ""

    // ── what the app noticed itself ──
    private var banned = false
    /** True Find's page could not load: "offline" (no connection) or "server" (the server failed) */
    private var loadError = ""
    private var failedUrl = ""
    private var pageError = false
    /** signed in, but the server could not be reached when the app opened (SessionWork) */
    private var sessionOffline = false

    // ── where the app is ──
    private var path = "/"
    private var signedIn = false
    private var cover = false
    private var appVisible = true
    var preview = ""

    // ── the screens ──
    private var maintView: FrameLayout? = null
    private var maintMsg: TextView? = null
    private var maintBtn: TfBtnLite? = null
    private var pillView: TextView? = null
    private var annView: FrameLayout? = null
    private var annShownFor = ""
    private var bannedView: FrameLayout? = null
    private var errView: FrameLayout? = null
    private var errKind = ""

    /** true while a status screen covers the app (the notifications card waits) */
    val blocking: Boolean get() = listOfNotNull(maintView, bannedView, errView, annView).any { it.visibility == View.VISIBLE }

    init {
        AppStatusHooks.onConfigChanged = { refresh(true) }
        AppStatusHooks.onServer = { what ->
            when (what) {
                "banned" -> if (!banned) { banned = true; render() }
                "maintenance" -> refresh(true)
            }
        }
        main.post { watchNetwork(); main.postDelayed(tick, 120_000) }
        main.postDelayed({ render() }, 1600) // the first screen settles first (announcement)
    }

    // ═════════════════ the web page tells what its own screens show ═════════════════
    inner class Bridge {
        @JavascriptInterface
        fun state(origin: String?, json: String?) {
            if (origin == null || !MainActivity.ORIGINS.contains(origin) || json == null) return
            main.post {
                val o = try { JSONObject(json) } catch (e: Exception) { return@post }
                webMaint = if (o.isNull("maint")) null else o.optString("maint")
                webBanned = o.optBoolean("banned")
                webAnn = if (o.isNull("ann")) null else o.optString("ann")
                webErr = o.optString("err")
                render()
            }
        }
    }

    // ═════════════════ settings from the server ═════════════════
    private val tick = object : Runnable {
        override fun run() { if (appVisible) refresh(false); main.postDelayed(this, 120_000) }
    }

    /** getAppConfig again (at most every 30 s unless [force]) */
    fun refresh(force: Boolean, after: (() -> Unit)? = null) {
        if (preview.isNotEmpty() || !Api.ready || Demo.on) { after?.invoke(); return }
        if (!force && fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < 30_000) { after?.invoke(); return }
        if (inflight) { after?.invoke(); return }
        inflight = true
        Api.invoke("getAppConfig", JSONObject(), 20000) { r ->
            inflight = false
            if (r.ok) {
                val c = r.data.optJSONObject("config")
                isAdmin = r.data.optBoolean("is_admin")
                if (c != null && !r.data.optBoolean("fallback")) cfg = c
                fetchedAt = System.currentTimeMillis()
            }
            render()
            after?.invoke()
        }
    }

    fun tokenChanged(signedInNow: Boolean) {
        banned = false
        fetchedAt = 0L; isAdmin = false; cfg = JSONObject()
        if (signedInNow) refresh(true)
        render()
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        if (v) { refresh(false); if (loadError.isNotEmpty()) retryLoad(false) }
    }

    /** MainActivity: where the app is now */
    fun update(path: String, signedIn: Boolean, cover: Boolean) {
        val wasIn = this.signedIn
        this.path = path.ifEmpty { "/" }; this.signedIn = signedIn; this.cover = cover
        lastPath = this.path
        if (signedIn && !wasIn) refresh(false)
        render()
    }

    /** the app's own sign-in check has no internet (still signed in) / has it again */
    fun setSessionOffline(on: Boolean) { if (sessionOffline != on) { sessionOffline = on; render() } }

    /** the sign-in web page is gone (signed in, the app's own screens show): nothing of it counts any more */
    fun webGone() {
        webMaint = null; webBanned = false; webAnn = null; webErr = ""
        loadError = ""; failedUrl = ""; pageError = false
        render()
    }

    // ═════════════════ the page itself could not load ═════════════════
    fun onPageStarted(url: String) { pageError = false }
    fun onLoadError(url: String, offline: Boolean) {
        pageError = true
        failedUrl = url
        loadError = if (offline) "offline" else "server"
        render()
    }
    fun onPageFinished(url: String) {
        if (!pageError && loadError.isNotEmpty() && !url.startsWith("about:") && !url.startsWith("data:")) { loadError = ""; render() }
    }
    private fun retryLoad(byHand: Boolean) {
        if (loadError.isEmpty()) return
        val u = failedUrl.ifEmpty { MainActivity.START_URL }
        if (byHand || appVisible) host.webLoad(u)
    }
    private val again = object : Runnable { override fun run() { if (loadError.isNotEmpty() && appVisible) retryLoad(false); main.postDelayed(this, 15_000) } }
    private fun watchNetwork() {
        main.postDelayed(again, 15_000)
        if (Build.VERSION.SDK_INT < 24) return
        try {
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post { if (loadError.isNotEmpty()) retryLoad(false) } }
            })
        } catch (e: Exception) { }
    }

    // ═════════════════ which screen shows ═════════════════
    private fun p(): String = path.substringBefore('?').ifEmpty { "/" }

    private fun render() {
        if (preview.isNotEmpty()) return
        val p = p()
        // errors first: they cover everything
        val err = when {
            loadError == "offline" -> "load-offline"
            loadError == "server" -> "load-server"
            webErr == "crash" -> "crash"
            webErr == "offline" || sessionOffline -> "offline"
            else -> ""
        }
        showError(err)

        val maintOn = signedIn && ((fetchedAt > 0 && cfg.optBoolean("maintenance_mode")) || webMaint != null) && !p.startsWith("/delete-account")
        val admin = isAdmin
        showMaintenance(maintOn && !admin, cfg.optString("maintenance_message").takeIf { it.isNotBlank() && it != "null" && fetchedAt > 0 } ?: webMaint.orEmpty())
        showPill(maintOn && admin && !p.startsWith("/admin"))

        showBanned(signedIn && (banned || webBanned))

        // announcement: once per message, never in quiet places or during a call
        val id = cfg.optString("announcement_id").takeIf { it != "null" }.orEmpty()
        val text = cfg.optString("announcement").takeIf { it != "null" }.orEmpty()
        val ready = android.os.SystemClock.uptimeMillis() - startedAt > 1500
        val annNative = ready && fetchedAt > 0 && text.isNotEmpty() && id.isNotEmpty() && seen() != id
        val annWeb = webAnn != null && fetchedAt == 0L
        val showAnn = signedIn && !cover && (annNative || annWeb) && !QUIET.containsMatchIn(p) && !(maintOn && !admin)
        showAnnouncement(showAnn, if (annNative) text else webAnn.orEmpty(), if (annNative) id else "web")
    }

    private fun seen(): String = prefs.getString("seen_announcement", "") ?: ""

    /** the phone's Back: the announcement closes like OK; the blocking screens send the app to the background */
    fun onBack(): Boolean {
        errView?.let { if (it.visibility == View.VISIBLE) { act.moveTaskToBack(true); return true } }
        maintView?.let { if (it.visibility == View.VISIBLE) { act.moveTaskToBack(true); return true } }
        bannedView?.let { if (it.visibility == View.VISIBLE) { act.moveTaskToBack(true); return true } }
        annView?.let { if (it.visibility == View.VISIBLE) { annOk(); return true } }
        return false
    }

    // ═════════════════ the screens ═════════════════
    private fun layer(v: View, elevationDp: Float) {
        v.elevation = Ui.dp(elevationDp); v.outlineProvider = null
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
    }
    private fun fadeIn(v: View) { v.visibility = View.VISIBLE; v.alpha = 0f; v.animate().alpha(1f).setDuration(180).start() }
    private fun fadeOut(v: View) { v.animate().alpha(0f).setDuration(160).withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start() }
    private fun setVisible(v: View, on: Boolean) {
        val now = v.visibility == View.VISIBLE
        if (on && !now) fadeIn(v) else if (!on && now) fadeOut(v)
    }

    /** the centred column of the maintenance and error pages (max 340 wide) */
    private fun column(ctx: Context): Pair<FrameLayout, LinearLayout> {
        val page = FrameLayout(ctx).apply { setBackgroundColor(Color.WHITE); isClickable = true }
        val sc = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val holder = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        holder.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { val m = Ui.dpi(24f); setMargins(m, m, m, m) })
        sc.addView(holder, FrameLayout.LayoutParams(-1, -1))
        page.addView(sc, FrameLayout.LayoutParams(-1, -1))
        // at most 340 wide, in the middle
        holder.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val max = Ui.dpi(340f); val w = r - l - Ui.dpi(48f)
            val lp = col.layoutParams as FrameLayout.LayoutParams
            val want = if (w > max) max else -1
            if (lp.width != want) { lp.width = want; col.layoutParams = lp }
        }
        return page to col
    }
    private fun iconBox(ctx: Context, inner: String, color: Int): FrameLayout = FrameLayout(ctx).apply {
        background = Tf.box(Ui.c("#F3EEFB"), 16f)
        addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(inner, color, 26f)) }, FrameLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER))
    }

    // ── Maintenance ──
    private fun showMaintenance(on: Boolean, message: String) {
        if (!on) { maintView?.let { setVisible(it, false) }; return }
        val v = maintView ?: run {
            val (page, col) = column(act)
            col.addView(Tf.t(act, "True Find", 22f, Ui.c("#4C1D95")), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(26f) })
            col.addView(iconBox(act, AdIc.MaintWrench, Ui.c("#4C1D95")), LinearLayout.LayoutParams(Ui.dpi(56f), Ui.dpi(56f)).apply { bottomMargin = Ui.dpi(16f) })
            col.addView(Tf.t(act, "We'll be back soon", 19f, Ui.c("#17121F")).apply { gravity = Gravity.CENTER })
            val msg = Tf.t(act, "", 14f, Ui.c("#5E586B"), false).apply { gravity = Gravity.CENTER; lineH(20f) }
            maintMsg = msg
            col.addView(msg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f); bottomMargin = Ui.dpi(24f) })
            val b = TfBtnLite(act, "Try again", Ui.c("#17121F"), Color.WHITE, 46f, 12f, 15f)
            b.setOnClickListener {
                if (b.busy) return@setOnClickListener
                b.busy = true; b.label("Checking…")
                val t0 = android.os.SystemClock.uptimeMillis()
                host.webJs("window.__tfStatusAct && window.__tfStatusAct('maint')")
                refresh(true) {
                    main.postDelayed({ b.busy = false; b.label("Try again") }, maxOf(0L, 400 - (android.os.SystemClock.uptimeMillis() - t0)))
                }
            }
            maintBtn = b
            col.addView(b, LinearLayout.LayoutParams(-1, Ui.dpi(46f)))
            page.contentDescription = "True Find is updating"
            layer(page, 500f)
            page.visibility = View.GONE
            maintView = page
            page
        }
        maintMsg?.text = message
        setVisible(v, true)
    }

    // ── the admin's pill: "Maintenance is ON · members can't use the app" ──
    private fun showPill(on: Boolean) {
        if (!on) { pillView?.let { setVisible(it, false) }; return }
        val v = pillView ?: Tf.t(act, "Maintenance is ON · members can't use the app", 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER; maxLines = 1
            background = Tf.box(Ui.c("#17121F"), 999f)
            setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            setOnClickListener { go("/admin?tab=settings") }
            elevation = Ui.dp(350f); outlineProvider = null
            root.addView(this, FrameLayout.LayoutParams(-2, Ui.dpi(28f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(30f) })
            visibility = View.GONE
            pillView = this
        }
        setVisible(v, true)
    }

    // ── Announcement ──
    private fun showAnnouncement(on: Boolean, text: String, id: String) {
        if (!on) { annView?.let { setVisible(it, false) }; return }
        val v = annView ?: run {
            val box = FrameLayout(act).apply { setBackgroundColor(Color.argb(107, 23, 18, 31)); isClickable = true }
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = Tf.box(Color.WHITE, 18f, 1f, Ui.c("#E9E6EE"))
                setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(16f))
                isClickable = true
            }
            card.addView(Tf.t(act, "FROM TRUE FIND", 12f, Ui.c("#4C1D95")).apply { letterSpacing = 0.4f / 12f }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(2f) })
            card.addView(Tf.t(act, "", 15f, Ui.c("#17121F"), false).apply { lineH(21f); tag = "text" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(4f); bottomMargin = Ui.dpi(16f) })
            val ok = TfBtnLite(act, "OK", Ui.c("#17121F"), Color.WHITE, 44f, 12f, 15f)
            ok.setOnClickListener { annOk() }
            card.addView(ok, LinearLayout.LayoutParams(-1, Ui.dpi(44f)))
            box.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { val m = Ui.dpi(16f); setMargins(m, m, m, m) })
            box.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val max = Ui.dpi(420f); val w = r - l - Ui.dpi(32f)
                val lp = card.layoutParams as FrameLayout.LayoutParams
                val want = if (w > max) max else -1
                if (lp.width != want) { lp.width = want; card.layoutParams = lp }
            }
            box.setOnClickListener { annOk() }
            layer(box, 300f)
            box.visibility = View.GONE
            annView = box
            box
        }
        annShownFor = id
        (v.findViewWithTag<TextView>("text"))?.text = text
        if (v.visibility != View.VISIBLE) {
            val card = (v as FrameLayout).getChildAt(0)
            fadeIn(v)
            card.translationY = Ui.dp(30f)
            card.animate().translationY(0f).setDuration(300).setInterpolator(SdLayer.EASE_UP).start()
        }
    }
    private fun annOk() {
        if (annShownFor.isNotEmpty() && annShownFor != "web") prefs.edit().putString("seen_announcement", annShownFor).apply()
        host.webJs("window.__tfStatusAct && window.__tfStatusAct('ann')")
        // the web app keeps the same "seen" mark (its own check, if it ever draws it itself)
        if (annShownFor.isNotEmpty() && annShownFor != "web") host.webJs("try{localStorage.setItem('tf_seen_announcement',${JSONObject.quote(annShownFor)})}catch(e){}")
        webAnn = null
        annView?.let { setVisible(it, false) }
        render()
    }

    // ── Blocked account ──
    private fun showBanned(on: Boolean) {
        if (!on) { bannedView?.let { setVisible(it, false) }; return }
        val v = bannedView ?: run {
            val box = FrameLayout(act).apply { setBackgroundColor(Color.argb(235, 10, 4, 24)); isClickable = true }
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = Tf.box(Color.WHITE, 24f)
                val p = Ui.dpi(24f); setPadding(p, p, p, p)
                elevation = Ui.dp(12f)
            }
            card.addView(FrameLayout(act).apply {
                background = Tf.circle(Ui.c("#FEE2E2"))
                addView(ImageView(act).apply { setImageDrawable(Tf.lu(AdIc.ShieldAlert, Ui.c("#DC2626"), 32f)) }, FrameLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(Ui.dpi(64f), Ui.dpi(64f)))
            card.addView(Tf.t(act, "Your account is blocked", 21f, Ui.c("#1F1235")).apply { gravity = Gravity.CENTER; setTypeface(Ui.bold) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(16f) })
            card.addView(Tf.t(act, "True Find has blocked this account for breaking the community rules. You can't call, chat or go live with it.", 14.5f, Ui.c("#5B5370"), false).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.42f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f) })
            val b = TfBtnLite(act, "Sign out", 0, Color.WHITE, 48f, 16f, 15f).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#7A1FE4"), Ui.c("#5A0FC2"))).apply { cornerRadius = Ui.dp(16f) }
            }
            b.setOnClickListener {
                if (b.busy) return@setOnClickListener
                b.busy = true; b.label("Signing out…")
                logout()
            }
            card.addView(b, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(20f) })
            box.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { val m = Ui.dpi(24f); setMargins(m, m, m, m) })
            box.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val max = Ui.dpi(384f); val w = r - l - Ui.dpi(48f)
                val lp = card.layoutParams as FrameLayout.LayoutParams
                val want = if (w > max) max else -1
                if (lp.width != want) { lp.width = want; card.layoutParams = lp }
            }
            layer(box, 480f)
            box.visibility = View.GONE
            bannedView = box
            box
        }
        setVisible(v, true)
    }

    // ── Errors: "Something went wrong" and "No internet connection" ──
    private fun showError(kind: String) {
        if (kind.isEmpty()) { errView?.let { setVisible(it, false) }; errKind = ""; return }
        if (kind != errKind) { errView?.let { root.removeView(it) }; errView = null }
        errKind = kind
        val v = errView ?: buildError(kind).also { layer(it, 520f); it.visibility = View.GONE; errView = it }
        setVisible(v, true)
    }

    private fun buildError(kind: String): FrameLayout {
        if (kind == "crash" || kind == "load-server") {
            val (page, col) = column(act)
            col.addView(Tf.t(act, "True Find", 22f, Ui.c("#4C1D95")).apply { letterSpacing = 0.2f / 22f }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(26f) })
            col.addView(iconBox(act, AdIc.ErrorMark, Ui.c("#4C1D95")), LinearLayout.LayoutParams(Ui.dpi(56f), Ui.dpi(56f)).apply { bottomMargin = Ui.dpi(16f) })
            col.addView(Tf.t(act, "Something went wrong", 18f, Ui.c("#17121F")).apply { gravity = Gravity.CENTER })
            col.addView(Tf.t(act, if (kind == "crash") "Sorry, this screen ran into a problem. Our team has been told. Please try again." else "True Find could not open right now. Please try again.", 13.5f, Ui.c("#5E586B"), false).apply { gravity = Gravity.CENTER; lineH(19f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(6f); bottomMargin = Ui.dpi(22f) })
            val again = TfBtnLite(act, "Try again", Ui.c("#17121F"), Color.WHITE, 46f, 12f, 15f)
            again.setOnClickListener {
                if (kind == "crash") host.webJs("window.__tfStatusAct && window.__tfStatusAct('crash')")
                else retryLoad(true)
            }
            col.addView(again, LinearLayout.LayoutParams(-1, Ui.dpi(46f)))
            val home = TfBtnLite(act, "Go to Home", Color.WHITE, Ui.c("#4C1D95"), 46f, 12f, 15f).apply { background = Tf.box(Color.WHITE, 12f, 1.5f, Ui.c("#E9E6EE")) }
            home.setOnClickListener {
                loadError = ""; webErr = ""
                host.webLoad(Api.origin.trimEnd('/') + "/home")
                render()
            }
            col.addView(home, LinearLayout.LayoutParams(-1, Ui.dpi(46f)).apply { topMargin = Ui.dpi(10f) })
            return page
        }
        // "No internet connection" (the web's OfflineScreen, or True Find could not load)
        val page = FrameLayout(act).apply { setBackgroundColor(Color.WHITE); isClickable = true }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(Tf.t(act, "No internet connection", 17f, Ui.c("#1F1235")).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(6f) })
        col.addView(Tf.t(act, if (kind == "offline") "You are still logged in. Check your internet and try again." else "Check your internet and try again.", 13f, Ui.c("#6B6280"), false).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(18f) })
        val retry = TfBtnLite(act, "Retry", Ui.c("#6C2BD9"), Color.WHITE, 0f, 10f, 14f).apply { setPadding(Ui.dpi(26f), Ui.dpi(10f), Ui.dpi(26f), Ui.dpi(10f)) }
        retry.setOnClickListener {
            if (kind == "offline") { if (sessionOffline) host.retrySession() else host.webJs("window.__tfStatusAct && window.__tfStatusAct('offline')") } else retryLoad(true)
        }
        col.addView(retry, LinearLayout.LayoutParams(-2, -2))
        page.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { val m = Ui.dpi(24f); setMargins(m, m, m, m) })
        return page
    }

    // ═════════════════ test pictures (GitHub emulator only) ═════════════════
    fun startPreview(scene: String) {
        preview = scene
        when {
            scene.startsWith("maint") -> { maintenancePreview(scene == "maint-admin") }
            scene == "announcement" -> {
                preview = ""; signedIn = true; path = "/"; fetchedAt = System.currentTimeMillis(); startedAt = 0
                cfg = JSONObject().put("announcement", "Diwali offer: 2x coins on every recharge today! 🎉\nOffer ends at midnight.").put("announcement_id", "preview-${System.currentTimeMillis()}")
                render(); preview = scene
            }
            scene == "banned" -> { preview = ""; signedIn = true; banned = true; render(); preview = scene }
            scene == "crash" -> { preview = ""; webErr = "crash"; render(); preview = scene }
            scene == "offline" -> { preview = ""; webErr = "offline"; render(); preview = scene }
            scene == "loaderr" -> { preview = ""; loadError = "offline"; render(); preview = scene }
            scene == "servererr" -> { preview = ""; loadError = "server"; render(); preview = scene }
        }
    }
    private fun maintenancePreview(admin: Boolean) {
        preview = ""; signedIn = true; path = "/"; fetchedAt = System.currentTimeMillis(); isAdmin = admin
        cfg = JSONObject().put("maintenance_mode", true).put("maintenance_message", "True Find is getting an update. We'll be back in a few minutes. Thank you for waiting!")
        render(); preview = if (admin) "maint-admin" else "maint"
    }
}

/** A plain button for the status screens: [bg] (0 = the background is set by the caller), text [fg]. */
class TfBtnLite(ctx: Context, label: String, bg: Int, fg: Int, heightDp: Float, radiusDp: Float, sizeDp: Float) : FrameLayout(ctx) {
    private val text = Tf.t(ctx, label, sizeDp, fg).apply { gravity = Gravity.CENTER; maxLines = 1 }
    var busy = false
        set(v) { field = v; alpha = if (v) 0.6f else 1f }
    private val h = heightDp
    init {
        if (bg != 0) background = Tf.box(bg, radiusDp)
        addView(text, LayoutParams(-2, -2, Gravity.CENTER))
        Ui.pressable(this, 0.97f)
    }
    fun label(s: String) { text.text = s }
    override fun onMeasure(w: Int, hs: Int) = if (h > 0f) super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(h), MeasureSpec.EXACTLY)) else super.onMeasure(w, hs)
}

/** where the app was (for a crash note) */
@Volatile var lastPath: String = "/"

/**
 * A native crash is written down on the phone (the app still closes as Android does), and sent
 * to the Control Center → Errors the next time True Find opens signed in — like the web app's
 * crash reports, so problems in the phone's own screens are found too.
 */
object CrashNote {
    private const val FILE = "tf-crash.json"
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                val o = JSONObject().put("message", "Android: ${e.javaClass.name}: ${e.message ?: ""}".take(480)).put("stack", sw.toString().take(2000))
                    .put("page", lastPath).put("thread", t.name).put("at", System.currentTimeMillis())
                java.io.File(app.filesDir, FILE).writeText(o.toString())
            } catch (x: Throwable) { }
            // (App Health: the numbers not yet saved are written before the app closes)
            try { AppHealth.saveNow() } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
    /** sends the last crash once the app is signed in (then forgets it) */
    fun sendPending(ctx: Context) {
        val f = java.io.File(ctx.filesDir, FILE)
        if (!f.exists() || !Api.ready || Demo.on) return
        val o = try { JSONObject(f.readText()) } catch (e: Exception) { f.delete(); return }
        f.delete()
        // App Health: the crash with the phone, Android, app version and network (Control Center → Issues)
        AppHealth.crash(o)
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        Api.invoke("logClientError", JSONObject().put("message", o.optString("message")).put("stack", o.optString("stack")).put("source", "manual")
            .put("page", o.optString("page")).put("version", "android-$v")) { }
    }
}
