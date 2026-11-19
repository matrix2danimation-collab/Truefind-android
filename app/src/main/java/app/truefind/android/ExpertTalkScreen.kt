package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject

/** Where the member is inside Expert Talk and what she picked (the web's `nav` object). */
class EtNav {
    var screen = "home"
    var expertId: String? = null
    var stub: JSONObject? = null
    var from: String? = null
    var expert: JSONObject? = null
    var concerns: MutableList<String> = ArrayList()
    var dur = 25
    var date: String? = null
    var t: Int? = null
    var move: JSONObject? = null
    var reply = false
    var booked: JSONObject? = null
    var after: JSONObject? = null
    var callId: String? = null
    var callName: String? = null
    var callPhoto: String? = null
    var cat = ""
    var chip = "All"
    var q = ""
    var focus = false
}

/**
 * EXPERT TALK — native version of src/pages/ExpertTalk.jsx, full screen, whenever the web app
 * is on /expert-talk. It asks the server who you are ("home") and shows the right side:
 * the member's Expert Talk (EtMember.kt) or the expert's own panel (EtExpert.kt).
 *
 * Opening: the first answer is kept for 5 minutes, so most opens show the full page at once;
 * otherwise a page-shaped outline shows and the real page replaces it in place (no second
 * slide on top of the app's own page move). Moving between Expert Talk's own screens slides
 * like the rest of the app (forward from the right, back from the left), and the phone's Back
 * goes to the screen before (as the header's back arrow does).
 */
@SuppressLint("ViewConstructor")
class ExpertTalkScreen(ctx: Context, val pages: NativePages) : FrameLayout(ctx) {
    companion object { const val FRESH_MS = 5 * 60 * 1000L }

    val ui = EtUi(ctx)
    val toast = EtToast(pages.rootView())
    var nav = EtNav(); private set

    // ── "home": the first answer (kept for a while) ──
    var home: JSONObject? = null; private set
    private var homeAt = 0L
    private var homeErr: Et.R? = null
    private var homeLoading = false
    private val homeWaiters = ArrayList<() -> Unit>()

    private val holder = FrameLayout(ctx)
    private var page: EtPage? = null
    var panel: EtPanel? = null; private set
    private var mode = ""            // "" until we know · "member" · "expert"
    private var shown = false
    private var active = false       // the address is /expert-talk (shown or covered for a moment)
    var appVisible = true; private set
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { if (shown && appVisible) page?.tick(); main.postDelayed(this, 1000) } }
    private val panelPoll = object : Runnable { override fun run() { if (shown && appVisible && mode == "expert") panel?.load(); main.postDelayed(this, 4000) } }

    init {
        setBackgroundColor(Et.PAGE)
        isClickable = true
        addView(holder, LayoutParams(-1, -1))
    }

    // ═════════════════ shown / hidden (NativePages) ═════════════════
    fun setShown(on: Boolean) {
        val was = shown
        shown = on
        visibility = if (on) VISIBLE else GONE
        if (!on) {
            if (was) { main.removeCallbacks(ticker); main.removeCallbacks(panelPoll); toast.clear() }
            // left the page (not just covered by a call screen): like the web, Expert Talk starts fresh next time
            if (active && !pages.onExpert) tearDown()
            if (!pages.signedIn) { home = null; homeAt = 0L; homeErr = null }
            return
        }
        if (!active) begin()
        if (!was) {
            main.removeCallbacks(ticker); main.postDelayed(ticker, 1000)
            main.removeCallbacks(panelPoll); main.postDelayed(panelPoll, 4000)
            page?.shown(true)
        }
    }

    private fun begin() {
        active = true
        nav = EtNav()
        mode = ""
        val fresh = home != null && System.currentTimeMillis() - homeAt < FRESH_MS
        if (!fresh) { home = null; homeErr = null }
        if (home != null) applyHome() else showPage(EtPlaceholder(this), anim = false)
        loadHome()
    }

    private fun tearDown() {
        active = false
        scrollMemo.clear()
        page?.let { it.dispose(); holder.removeView(it) }
        page = null
        panel = null
        mode = ""
        toast.clear()
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        // back in the app: the expert's bookings (an incoming call) at once
        if (v && shown && mode == "expert") panel?.load()
    }

    /** the phone's Back (after the app's pop-ups) */
    fun onBack(): Boolean {
        val p = page ?: return false
        if (p.back()) return true
        // an expert's account is Expert Talk only: Back on her dashboard leaves the app like Home does
        if (mode == "expert" && p is EtXDash) { pages.activity.moveTaskToBack(true); return true }
        return false
    }

    // ═════════════════ the first answer ═════════════════
    /** asks "home" again (one quiet retry first: a cold server can fail the very first call) */
    fun loadHome(done: (() -> Unit)? = null) {
        if (done != null) homeWaiters.add(done)
        if (homeLoading) return
        homeLoading = true
        fun finish(r: Et.R) {
            homeLoading = false
            if (r.ok) { home = r.data; homeAt = System.currentTimeMillis(); homeErr = null }
            else if (home == null) homeErr = r
            val w = ArrayList(homeWaiters); homeWaiters.clear()
            if (active) applyHome()
            w.forEach { it() }
        }
        Et.call("home") { r ->
            if (r.ok) finish(r)
            else main.postDelayed({ Et.call("home") { r2 -> finish(r2) } }, 900)
        }
    }

    private fun applyHome() {
        val h = home
        if (h == null) {
            val e = homeErr ?: return
            if (mode == "") showPage(EtErrorPage(this, e.error, e.detail) { homeErr = null; showPage(EtPlaceholder(this), anim = false); loadHome() }, anim = false)
            return
        }
        if (Et.b(h.optJSONObject("me"), "is_expert")) { if (mode != "expert") startPanel(); return }
        if (mode != "member") {
            mode = "member"
            nav.screen = "home"
            showPage(EtHome(this), anim = false)
        } else page?.homeChanged()
    }

    // ═════════════════ the member's screens ═════════════════
    /**
     * Where each screen was scrolled when it was left for a screen further in (owner, 19 Nov: back from a topic's
     * experts the Expert Talk page opened at its top instead of where he had tapped). Going back to a screen puts
     * it at that same place; opening a screen afresh starts it at its top.
     */
    private val scrollMemo = HashMap<String, Int>()

    /** setNav({ ...n, ...extra, screen }) of MemberApp.jsx (going "home" asks the server again) */
    fun go(screen: String, back: Boolean? = null, set: EtNav.() -> Unit = {}) {
        if (screen == "home") loadHome()
        val from = nav.screen
        val old = page
        nav.set()
        nav.screen = screen
        val p: EtPage = when (screen) {
            "experts" -> EtExperts(this)
            "profile" -> EtProfile(this)
            "book" -> EtBook(this)
            "slot" -> EtSlot(this)
            "confirm" -> EtConfirm(this)
            "booked" -> EtBooked(this)
            "sessions" -> EtSessions(this)
            "apply" -> EtApply(this)
            "call" -> EtCallPage(this, nav.callId.orEmpty(), "member", nav.callName.orEmpty(), nav.callPhoto.orEmpty(), null) { st -> go("sessions") { after = st; callId = null } }
            else -> EtHome(this)
        }
        val isBack = back ?: (old != null && p.rank < old.rank)
        if (old != null && from != screen) {
            if (isBack) scrollMemo.remove(from)            // left backwards: that screen starts fresh next time
            else scrollMemo[from] = old.scroll.scrollY     // left for a screen further in: remember the place
        }
        val keepY = if (isBack) scrollMemo.remove(screen) ?: 0 else { scrollMemo.remove(screen); 0 }
        showPage(p, back, keepY = keepY)
    }

    // ═════════════════ the expert's panel ═════════════════
    private fun startPanel() {
        mode = "expert"
        val p = EtPanel(this)
        panel = p
        showPage(EtSpinnerPage(this), anim = false)
        p.load()
    }

    /** the dashboard answer changed (or failed the first time) */
    fun panelChanged() {
        val p = panel ?: return
        val cur = page
        if (p.d == null) {
            val e = p.err ?: return
            showPage(EtErrorPage(this, e.error, e.detail) { showPage(EtSpinnerPage(this), anim = false); p.load() }, anim = false)
            return
        }
        when (cur) {
            is EtSpinnerPage, is EtErrorPage -> showPage(EtXDash(this, p), anim = false)
            is EtXDash, is EtXProfile -> cur.render()
            else -> {}
        }
    }

    fun xgo(screen: String, back: Boolean? = null) {
        val p = panel ?: return
        val pg: EtPage = when (screen) {
            "avail" -> EtXAvail(this, p)
            "profile" -> EtXProfile(this, p)
            "edit" -> EtXEdit(this, p)
            else -> EtXDash(this, p)
        }
        showPage(pg, back)
    }

    /** the expert's call screen (opens by itself when a member calls) */
    fun openExpertCall(id: String) {
        val p = panel ?: return
        p.callId = id
        val b = p.booking(id)
        showPage(EtCallPage(this, id, "expert", Et.s(b, "user_alias").ifEmpty { "Member" }, "", b) { _ ->
            p.callId = null
            xgo("dash", back = true)
            p.load()
        }, back = false)
    }

    // ═════════════════ test mode (emulator pictures on GitHub only) ═════════════════
    /** opens one screen / pop-up with the made-up data (EtDemo), e.g. "book", "slot,calendar", "sessions,rate" */
    fun previewOpen(action: String) {
        android.util.Log.i("TFDBG", "et preview $action")
        val parts = action.split(",")
        val ex = Et.objs(home?.optJSONArray("experts")).firstOrNull() ?: return
        val full = JSONObject(ex.toString())
        fun toSlot() = go("slot", back = false) { expertId = Et.s(ex, "id"); expert = full; concerns = arrayListOf("Breakup", "Loneliness"); dur = 25; date = null; t = null; move = null }
        when (parts[0]) {
            "scroll1" -> page?.scroll?.let { s -> s.post { s.scrollTo(0, Ui.dpi(560f)) } }
            "scroll2" -> page?.scroll?.let { s -> s.post { s.scrollTo(0, Ui.dpi(1180f)) } }
            "scroll3" -> page?.scroll?.let { s -> s.post { s.fullScroll(View.FOCUS_DOWN) } }
            "experts" -> go("experts", back = false) { cat = ""; chip = "All" }
            "today" -> go("experts", back = false) { cat = ""; chip = "Available today" }
            "topic" -> go("experts", back = false) { cat = "breakup"; chip = "All" }
            "profile" -> go("profile", back = false) { expertId = Et.s(ex, "id"); from = "home"; stub = ex }
            "book" -> go("book", back = false) { expertId = Et.s(ex, "id"); expert = full; concerns = arrayListOf("Breakup"); dur = 25; date = null; t = null; move = null }
            "slot" -> toSlot()
            "confirm" -> go("confirm", back = false) { expertId = Et.s(ex, "id"); expert = full; concerns = arrayListOf("Breakup", "Loneliness"); dur = 35; date = Et.addDays(Et.todayIST(), 1); t = 990; move = null }
            "booked" -> go("booked", back = false) { booked = JSONObject().put("expert", full).put("date", Et.addDays(Et.todayIST(), 1)).put("start_min", 990).put("dur", 35).put("coins", 350) }
            "sessions" -> go("sessions", back = false)
            "apply" -> go("apply", back = false)
            "call" -> go("call", back = false) { callId = "bk1"; callName = Et.s(ex, "name"); callPhoto = "" }
            "avail" -> xgo("avail", back = false)
            "xprofile" -> xgo("profile", back = false)
            "edit" -> xgo("edit", back = false)
            "xcall" -> openExpertCall("x1")
            "move" -> {
                val b = EtDemo.sessions().getJSONObject(2)
                go("slot", back = false) { move = b; reply = false; expert = JSONObject().put("id", "demo-g3").put("name", Et.s(b.optJSONObject("expert"), "name")).put("photo", "").put("title", Et.s(b.optJSONObject("expert"), "title")); date = null; t = null }
            }
        }
        if (parts.size > 1) main.postDelayed({
            val w = parts[1]
            if (w.startsWith("y")) page?.scroll?.scrollTo(0, Ui.dpi(w.drop(1).toFloatOrNull() ?: 0f))
            else (page as? EtPreviewable)?.preview(w)
        }, 1800)
    }

    // ═════════════════ showing a screen ═════════════════
    private fun showPage(p: EtPage, back: Boolean? = null, anim: Boolean = true, keepY: Int = 0) {
        val old = page
        page = p
        holder.addView(p, LayoutParams(-1, -1))
        p.render()
        // back at a screen that was scrolled: it is laid out now and put at that place before it moves in, so it
        // slides in already where it was (not at its top first)
        if (keepY > 0) p.restoreScroll(keepY, holder.width, holder.height)
        if (old != null) {
            val isBack = back ?: (p.rank < old.rank)
            // the page you leave stays until the move ends (no move while the app's own page move is still running)
            val gone = { old.dispose(); holder.removeView(old) }
            if (anim && shown && isAttachedToWindow && width > 0 && !PageFx.running)
                PageFx.run(holder, if (isBack) PageFx.Kind.POP else PageFx.Kind.PUSH, old, p, onEnd = gone)
            else gone()
        }
        p.shown(true)
    }
}

/** test mode: a screen that can open one of its pop-ups by name */
interface EtPreviewable { fun preview(what: String) }

/** The page-shaped outline while Expert Talk opens (Placeholder of ExpertTalk.jsx). */
@SuppressLint("ViewConstructor")
class EtPlaceholder(et: ExpertTalkScreen) : EtPage(et) {
    override fun render() {
        val ui = et.ui
        rebuild { b ->
            val h = etRow(ctx, 10f).apply { setPadding(Ui.dpi(16f), Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(8f)) }
            h.put(ui.ib(EtIcon.ChevronLeft, 20f, label = "Back to True Find") { et.pages.go("/home") }, 40f, 40f)
            h.put(etCol(ctx).apply {
                put(etT(ctx, "Expert Talk", 22f, Et.INK, true, 1.05f))
                put(etT(ctx, EtSpan().add("by ").add("True", Ui.c("#4A12B8")).add(" ").add("Find", Ui.c("#8E1F4F")).build(), 11.5f, Et.MUT, true), t = 3f)
            }, 0f, -2f, 1f)
            h.put(EtSkel(ctx, 17f), 74f, 34f)
            h.put(EtSkel(ctx, 12f), 40f, 40f)
            b.put(h, -1f, -2f)
            val st = ui.stack(18f)
            st.put(EtSkel(ctx, 22f), -1f, 180f)
            st.put(etRow(ctx, 0f).apply { put(EtSkel(ctx, 8f), 0f, 20f, .55f); put(View(ctx), 0f, 20f, .45f) }, -1f, 20f)
            val g = EtGrid(ctx, 3, Ui.dpi(10f))
            repeat(6) { g.addView(EtSkel(ctx, 16f), android.view.ViewGroup.LayoutParams(-1, Ui.dpi(76f))) }
            st.put(g, -1f, -2f)
            repeat(2) { st.put(EtSkel(ctx, 18f), -1f, 84f) }
            b.put(st, -1f, -2f)
        }
    }
}

/** The loader alone (the expert's panel before its first answer). */
@SuppressLint("ViewConstructor")
class EtSpinnerPage(et: ExpertTalkScreen) : EtPage(et) {
    override fun render() { rebuild { b -> b.put(et.ui.spinner(), -1f, -2f, t = 40f) } }
}

/** "Could not load" with Try again (ErrorBox). */
@SuppressLint("ViewConstructor")
class EtErrorPage(et: ExpertTalkScreen, private val text: String, private val detail: String, private val retry: () -> Unit) : EtPage(et) {
    override fun render() { rebuild { b -> b.put(et.ui.errorBox(text, detail) { retry() }, -1f, -2f) } }
}

@Suppress("unused") private fun keepEts(v: View, l: LinearLayout, g: Int = Gravity.CENTER) = v to l
