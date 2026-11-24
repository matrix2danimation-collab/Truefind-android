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
import android.widget.FrameLayout
import android.widget.ScrollView
import app.truefind.android.Api
import app.truefind.android.Ui
import org.json.JSONObject

/**
 * NATIVE SIGN-IN (Supabase, 24 Nov 2026). The phone draws every sign-in page itself; there is no web page
 * under them any more (Base44's sign-in link, Google's page and the server's log-out needed one — WhatsApp OTP
 * with Supabase Auth does not):
 *   /welcome (and the old sign-in addresses /login, /register …) → Get Started, mobile number, WhatsApp OTP,
 *                                                                   gender, profile
 *   /delete-account → how to delete an account
 * Signing in is WhatsApp OTP only (Google and e-mail sign-in were removed): WelcomePage makes the session
 * (Supabase Auth), and [Host.signedIn] opens the app. This layer keeps its own short history for Back.
 */
@SuppressLint("ViewConstructor")
class LoginLayer(ctx: Context, internal val host: Host) : FrameLayout(ctx) {

    interface Host {
        /** signed in (a finished profile): the app's own pages from [next] ("/" = Home) */
        fun signedIn(next: String)
        /** log out (the number belongs to another account …): the sign-in is forgotten, then this layer opens [then] */
        fun signOut(then: String)
        fun finishApp()
        fun pickPhoto(done: (ByteArray?, String) -> Unit)
        /** opens [url] in a Chrome Custom Tab; false when there is no browser */
        fun openTab(url: String): Boolean
        /** Back on the first sign-in page, opened from a page of the app: that page again (true when it was) */
        fun backToApp(): Boolean = false
    }

    companion object {
        private val PAGES = mapOf(
            "/welcome" to "welcome", "/register/phone" to "welcome", "/delete-account" to "delete",
            // e-mail sign-in, Google and the old profile pages are gone: their addresses lead to the WhatsApp OTP sign-in
            "/login" to "welcome", "/register" to "welcome", "/forgot-password" to "welcome", "/reset-password" to "welcome",
            "/mfa" to "welcome", "/register/basic-profile" to "welcome", "/register/complete-profile" to "welcome",
        )
        fun norm(p: String): String = p.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        /** one of the sign-in pages this layer draws (/welcome, /delete-account and the old sign-in addresses) */
        fun isLoginPath(p: String): Boolean = PAGES.containsKey(norm(p))
        val EASE = PathInterpolator(0.25f, 0.8f, 0.25f, 1f)
    }

    private val main = Handler(Looper.getMainLooper())

    // ── where this layer is ──
    internal var path = "/welcome"; private set
    internal var search = ""; private set
    /** the sign-in pages before this one (Back) */
    private val history = ArrayList<String>()

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
        isClickable = true // nothing falls through to the pages under it
        isFocusable = true
        setBackgroundColor(Color.WHITE)
        clipChildren = false
    }

    // ═════════════════ shown / gone ═════════════════
    val shown: Boolean get() = visibility == View.VISIBLE

    /** The sign-in pages take the screen at [target] (/welcome …): signed out, or a sign-in page opened from the app. */
    fun open(target: String) {
        if (preview.isNotEmpty()) return
        history.clear()
        setAddress(target)
        refresh(false)
    }

    /** The app's own pages took over (signed in): this layer goes and forgets its pages. */
    fun close() {
        if (preview.isNotEmpty()) return
        history.clear()
        if (visibility == View.VISIBLE) {
            visibility = View.GONE
            closeSheets()
            page?.let { it.leave(); removeView(it) }
            page = null; pageKey = ""
            onChange()
        }
    }

    private fun setAddress(target: String) {
        val q = target.indexOf('?')
        path = if (q >= 0) target.substring(0, q) else target
        search = if (q >= 0) target.substring(q) else ""
    }

    private fun refresh(animate: Boolean) {
        val key = PAGES[norm(path)] ?: "welcome"
        val appearing = visibility != View.VISIBLE
        if (key != pageKey || page == null) open(key, animate && !appearing) else page?.route(search)
        if (appearing) { visibility = View.VISIBLE; onChange() }
    }

    private fun make(key: String): LPage = when (key) {
        "delete" -> DeletePage(context, this)
        else -> WelcomePage(context, this)
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

    // ═════════════════ moving around ═════════════════
    /**
     * Another page: a sign-in page here (its own history, for Back), anything else is a page of the app — only
     * when signed in (a finished profile opens Home; signed out, it stays on the sign-in pages).
     */
    fun go(path: String, replace: Boolean = false) {
        if (preview.isNotEmpty()) { toast("→ $path"); return }
        if (!isLoginPath(path)) { if (Api.token.isNotEmpty()) host.signedIn(path); return }
        if (!replace) history.add(this.path + search)
        setAddress(path)
        refresh(true)
    }
    /** the phone's Back / a page's back arrow: the sign-in page before, else the app's page it was opened from, else out */
    fun back() {
        backAt = SystemClock.uptimeMillis()
        if (preview.isNotEmpty()) { host.finishApp(); return }
        if (history.isNotEmpty()) { setAddress(history.removeAt(history.size - 1)); refresh(true); return }
        if (host.backToApp()) return
        host.finishApp()
    }

    /**
     * Back: a pop-up closes first, then the page's own steps, then the sign-in page before it. Nothing before it:
     * the page of the app it was opened from (Finish profile …), else Get Started leaves the app and another
     * sign-in page goes to Get Started (in its place, so Back can never go round in a circle).
     */
    fun onBack(): Boolean {
        if (sheets.isNotEmpty()) { sheets.last().second(); return true }
        val p = page ?: return false
        if (p.onBack()) return true
        backAt = SystemClock.uptimeMillis()
        if (preview.isNotEmpty()) { host.finishApp(); return true }
        if (history.isNotEmpty()) { setAddress(history.removeAt(history.size - 1)); refresh(true); return true }
        if (host.backToApp()) return true
        if (pageKey == "welcome") host.finishApp() else go("/welcome", true)
        return true
    }

    fun onAppVisible(v: Boolean) { page?.visible(v) }

    // ═════════════════ signed in / out ═════════════════
    /** The sign-in is made and the profile finished: the app opens on [next]. */
    internal fun signedIn(next: String = "/") {
        if (preview.isNotEmpty()) { toast("→ $next"); return }
        host.signedIn(next)
    }

    /** Log out (the sign-in is forgotten here and on the server), then the sign-in page [then]. */
    internal fun serverLogout(then: String = "/welcome") {
        if (preview.isNotEmpty()) { toast("→ $then"); return }
        host.signOut(then)
    }

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
    /** A public page of the website (truefindapp.com/terms, /privacy, …), full screen in the phone's browser tab. */
    internal fun openSite(path: String) {
        if (preview.isNotEmpty()) { toast("→ $path"); return }
        val url = Api.origin.trimEnd('/') + path
        if (host.openTab(url)) return
        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
    }

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
     * [scene] = page (+ a variant after "-"), e.g. "welcome", "welcome-verify", "delete"; [open] = an action on it.
     * Made-up answers (LoginDemo), no server, no login.
     */
    fun startPreview(scene: String, open: String) {
        preview = scene
        LK.autoKeyboard = open == "kb"
        LoginApi.demo = LoginDemo::answer
        LoginDemo.scene = scene
        path = if (scene.substringBefore('-') == "delete") "/delete-account" else "/welcome"
        search = ""
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
