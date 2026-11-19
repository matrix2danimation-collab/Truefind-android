package app.truefind.android

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * The native Home, Profile (with Settings and every screen inside it), Edit your
 * profile, Verification, Recent, Expert Talk, My Dashboard, Secret Dating Earning
 * and the bottom bar.
 *
 * A screen shows whenever its address is open (the app's own Router keeps the address):
 *   /, /home            → Home              /profile[?…]          → Profile
 *   /profile/edit       → Edit your profile  /register/verification → Verification
 *   /chats              → Recent (RecentScreen)
 *   /, /home, /explore, /chats → the bottom bar
 *   /expert-talk        → Expert Talk (member side or the expert's panel)
 *   /dashboard          → My Dashboard        /secret-earnings      → Secret Dating Earning
 * Before, these followed the address of the web page hidden underneath; now MainActivity tells
 * them the address ([setRoute]) and their taps go back to it ([Host]).
 */
class NativePages(private val act: ComponentActivity, private val root: FrameLayout, private val onChange: () -> Unit) {

    /** moving around and signing out (MainActivity) */
    interface Host {
        /** navigate(path, { state, replace }) */
        fun go(path: String, state: JSONObject?, replace: Boolean)
        /** navigate(-n); false when there is no page behind */
        fun back(n: Int): Boolean
        val canGoBack: Boolean
        /** log out: [presence] says "offline" to Explore first */
        fun logout(presence: Boolean)
    }

    companion object {
        val NAV_ON = setOf("/", "/home", "/explore", "/chats")
    }

    private val main = Handler(Looper.getMainLooper())
    lateinit var host: Host

    // ── where the app is ──
    var path = "/"; private set
    var search = ""; private set
    var usr: JSONObject? = null; private set
    var idx = -1; private set
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
    val expert = ExpertTalkScreen(act, this)
    val dash = DashboardScreen(act, this)
    val earn = SecretEarningsScreen(act, this)
    val recent = RecentScreen(act, this)
    /** the native calls */
    var calls: CallManager? = null
    private val callPill = CallPill(act) { calls?.returnToCall() }
    private val screens: List<View> get() = listOf(home, profile, builder, verify, expert, dash, earn, recent)
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

    /** MainActivity: the address now (path, ?query), what was handed along with it, and its place in the history. */
    fun setRoute(path: String, search: String, state: JSONObject?, idx: Int) {
        this.path = path.ifEmpty { "/" }
        this.search = search
        this.usr = state
        this.idx = idx
    }

    /** Settings → Check for update (MainActivity asks GitHub) */
    var checkUpdate: () -> Unit = {}
    /** Settings → Page speed report (test builds) */
    var speedReport: () -> Unit = {}
    /** test builds: Settings → "Page move speed" (how long page moves take, to try on the phone) */
    var moveSpeed: () -> Unit = {}
    private var profilePrefetched = false

    // ═════════════════ which screen shows ═════════════════
    private fun p(): String = path.trimEnd('/').ifEmpty { "/" }
    val onHome: Boolean get() = p() == "/" || p() == "/home"
    val onProfile: Boolean get() = p() == "/profile"
    val onBuilder: Boolean get() = p() == "/profile/edit"
    val onVerify: Boolean get() = p() == "/register/verification"
    val onExpert: Boolean get() = p() == "/expert-talk"
    val onDash: Boolean get() = p() == "/dashboard"
    val onEarn: Boolean get() = p() == "/secret-earnings"
    val onRecent: Boolean get() = p() == "/chats"

    /** the bottom bar is on screen */
    var navShown = false; private set

    /**
     * Called by MainActivity whenever the page or the login changes ([signedIn]: signed in and inside the app).
     * Returns the bottom of the page area for the native Explore (px, or -1 = leave as it is).
     */
    fun update(signedIn: Boolean): Int {
        if (preview.isNotEmpty()) return -1
        this.signedIn = signedIn
        val live = signedIn
        val showNav = live && NAV_ON.contains(p())
        val showHome = live && onHome
        val showProfile = live && onProfile
        val showBuilder = live && onBuilder
        val showVerify = live && onVerify
        val showExpert = live && onExpert
        val showDash = live && onDash
        val showEarn = live && onEarn
        val showRecent = live && onRecent
        navShown = showNav
        nav.visibility = if (showNav) View.VISIBLE else View.GONE
        nav.current = when (p()) { "/", "/home" -> "home"; "/explore" -> "explore"; "/chats" -> "recent"; else -> "" }
        val innerH = root.height - root.paddingTop - root.paddingBottom
        val pageH = if (showNav && innerH > 0) innerH - nav.barPx else ViewGroup.LayoutParams.MATCH_PARENT
        sizeTo(home, pageH)
        home.setShown(showHome)
        // Profile gets its data ready while Home shows, so opening it is instant (like FRND)
        if (showHome && !profilePrefetched) { profilePrefetched = true; main.postDelayed({ profile.prefetch() }, 1500) }
        if (!live) profilePrefetched = false
        profile.setShown(showProfile, search, usr)
        builder.setShown(showBuilder)
        verify.setShown(showVerify)
        expert.setShown(showExpert)
        dash.setShown(showDash)
        earn.setShown(showEarn, search)
        recent.setShown(showRecent)
        // the Recent list is loaded once in the background after sign-in (the first visit shows it at once)
        if (live) recent.warm()
        if (!(showHome || showProfile || showBuilder || showVerify || showExpert || showDash || showEarn || showRecent)) layer.closeAll()
        // "Call in progress · Return": on every page but games, rooms and calls
        val cm = calls
        val pillOn = live && cm != null && cm.enabled && cm.pillVisible(path + search)
        callPill.visibility = if (pillOn) View.VISIBLE else View.GONE
        if (pillOn) (callPill.layoutParams as FrameLayout.LayoutParams).let { lp ->
            // above the bottom bar; on Profile 24 dp from the bottom (as before); on other pages where the web's pill sat (84 px)
            val b = if (showNav) nav.barPx + Ui.dpi(12f) else if (showHome || showProfile || showBuilder || showVerify) Ui.dpi(24f) else Ui.dpi(84f)
            if (lp.bottomMargin != b) { lp.bottomMargin = b; callPill.layoutParams = lp }
        }
        return if (showNav && innerH > 0) innerH - nav.barPx else -1
    }

    private fun sizeTo(v: View, h: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.height != h) { lp.height = h; v.layoutParams = lp }
    }

    // ═════════════════ moving around ═════════════════
    /** Home read the True Find wallet (MainActivity hands it to the Wallet page, which asks for the same) */
    var onWallet: (JSONObject) -> Unit = {}
    /** navigate(path, { state, replace }) */
    fun go(path: String, state: JSONObject? = null, replace: Boolean = false) {
        if (preview.isNotEmpty()) { toast.show("→ $path"); return }
        host.go(path, state, replace)
    }
    /** navigate(-1), or Home when there is nothing before (the ‹ button of Profile) */
    fun historyBack() {
        if (preview.isNotEmpty()) { act.finish(); return }
        if (!host.back(1)) host.go("/", null, false)
    }
    /** a page was opened from another page of the app (there is one to go back to) */
    val canGoBack: Boolean get() = preview.isEmpty() && host.canGoBack

    /** this phone's own id (the web's deviceId() of presence.js) */
    fun deviceId(done: (String?) -> Unit) = done(Session.deviceId(act))

    /**
     * Log out: off Explore at once (presence offline) and this phone's notifications off
     * (1.5 s at most), then the server's log-out → the sign-in pages.
     */
    fun logout(presence: Boolean = true) {
        if (preview.isNotEmpty()) { toast.show("→ log out"); return }
        host.logout(presence)
    }

    /** navigate(-n) */
    fun backBy(n: Int) {
        if (preview.isNotEmpty()) return
        host.back(maxOf(1, n))
    }

    private fun tab(key: String, path: String) {
        val here = nav.current
        if (key == here) {
            // the tab you are on: back to its top
            when (key) { "home" -> home.scrollTop(); "recent" -> recent.scrollTop(); else -> {} }
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
        if (expert.visibility == View.VISIBLE && expert.onBack()) return true
        if (recent.visibility == View.VISIBLE && recent.onBack()) return true
        return false
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        home.onAppVisible(v)
        profile.onAppVisible(v)
        verify.onAppVisible(v)
        expert.onAppVisible(v)
        dash.onAppVisible(v)
        recent.onAppVisible(v)
    }

    // ═════════════════ test mode (emulator screenshots on GitHub only) ═════════════════
    /** test pictures: the "Call in progress · Return" pill over the native Home */
    fun previewPill() {
        callPill.visibility = View.VISIBLE
        (callPill.layoutParams as FrameLayout.LayoutParams).let { lp -> lp.bottomMargin = nav.barPx + Ui.dpi(12f); callPill.layoutParams = lp }
    }

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
            what.startsWith("et") -> {
                path = "/expert-talk"; signedIn = true
                app.truefind.android.games.GameApi.demo = true
                EtDemo.on = true; EtDemo.expert = what == "et-expert"
                expert.setShown(true)
            }
            what.startsWith("dash") -> { path = "/dashboard"; signedIn = true; EtDemo.on = true; EtDemo.creator = what == "dash-creator"; dash.setShown(true) }
            what.startsWith("earn") -> { path = "/secret-earnings"; signedIn = true; EtDemo.on = true; earn.setShown(true, if (what == "earn-admin") "?creator=demo-c1" else "") }
            what.startsWith("recent") -> {
                DemoHome.recentScene = act.intent?.getStringExtra("tf_scene").orEmpty()
                path = "/chats"; nav.visibility = View.VISIBLE; nav.current = "recent"; nav.unread = 3
                recent.setShown(true)
            }
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
                what.startsWith("et") -> expert.previewOpen(action)
                what.startsWith("dash") -> dash.previewOpen(action)
                what.startsWith("earn") -> earn.previewOpen(action)
                what.startsWith("recent") -> recent.previewOpen(action)
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
