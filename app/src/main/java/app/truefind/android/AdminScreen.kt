package app.truefind.android

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Control Center (pages/AdminPanel.jsx), drawn by the phone: admin only, shown whenever
 * the web app's address is /admin (Profile → Settings → Control Center, or the "Maintenance
 * is ON" pill). Black top with the section tabs (Overview · Health · Pages · Growth · Verify · Members ·
 * Reports · Experts · Live · Payouts · Support · Issues · Settings · System), white page,
 * the member sheet and every pop-up of the web version, with the same server calls.
 * Every server action checks the admin role again on its own.
 */
class AdminScreen(private val act: ComponentActivity, private val nav: Nav) : FrameLayout(act) {
    interface Nav {
        /** navigate(path, { replace }) of the web app */
        fun go(path: String, replace: Boolean)
        /** the ‹ button: navigate(-1), or Home when there is nothing before */
        fun back()
    }

    companion object {
        const val FETCH_LIMIT = 500
        val TABS = listOf(
            Triple("overview", "Overview", AdIc.LayoutDashboard),
            // App Health (25 Nov): Health, Pages; Errors became Issues (every problem members met; the old grouped crash list inside)
            Triple("health", "Health", AdIc.Activity), Triple("pages", "Pages", AdIc.LayoutList),
            Triple("growth", "Growth", AdIc.TrendingUp),
            Triple("verify", "Verify", AdIc.ShieldCheck), Triple("members", "Members", AdIc.Users),
            Triple("reports", "Reports", AdIc.Flag), Triple("experts", "Experts", AdIc.Headphones),
            Triple("live", "Live", AdIc.Radio), Triple("payouts", "Payouts", AdIc.IndianRupee),
            Triple("support", "Support", AdIc.LifeBuoy), Triple("errors", "Issues", AdIc.Bug),
            Triple("settings", "Settings", AdIc.Settings), Triple("system", "System", AdIc.Cpu),
        )
    }

    var src: AdSrc = AdNet
    private val main = Handler(Looper.getMainLooper())
    /** pop-ups over the Control Center (sheets, the photo viewer) */
    val layer = TfLayer(this)
    private val toast = AdToast(this)

    // ── who is looking (role admin only) ──
    var me: JSONObject? = null; private set
    private var allowed = false
    private var gateToken = ""

    // ── the data every section shares ──
    var overview: JSONObject? = null; private set
    var ovLoading = true; private set
    var ovError = ""; private set
    var profiles: List<JSONObject> = emptyList(); private set
    var capped = false; private set
    var profLoading = true; private set
    var profError = ""; private set
    var memberQ = ""; private set

    // ── the page ──
    private val head = AU.col(act)
    private val emailLine = AU.t(act, "True Find · only you can see this", 12f, AK.onBlack).ell()
    private val refreshIcon = ImageView(act).apply { setImageDrawable(Tf.lu(AdIc.RefreshCw, Color.WHITE, 17f)) }
    private var spin: ObjectAnimator? = null
    /** the section tabs; the open one is kept in the middle each time the bar is laid out
     *  (also when the counts arrive and the tabs get wider), until the bar is touched */
    private val tabScroll = object : HorizontalScrollView(act) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (centerKey != null) applyCenter()
        }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) centerKey = null
            return super.dispatchTouchEvent(ev)
        }
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            // test pictures only: who moved the bar back to the start
            if (preview && l == 0 && oldl > 0) android.util.Log.i("TFDBG", "tabbar back to start from $oldl: " +
                Throwable().stackTrace.drop(1).take(14).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
        }
    }
    private var centerKey: String? = null
    private var centerSmooth = false
    private var centerTarget = -1
    private var centerAt = 0L
    private val tabRow = AU.row(act)
    private val scroll = ScrollView(act)
    private val body = AU.col(act)
    /** the Settings section's save bar (fixed at the bottom) */
    val bottomSlot = FrameLayout(act)
    private val gateView = FrameLayout(act)
    var tab = "overview"; private set
    private var cur: AdTab? = null
    private var shown = false
    private var appVisible = true
    private var lastSearch: String? = null
    var preview = false

    // ── downloads (Payouts CSV, backups): the phone's "Save to" screen ──
    private var pendingFile: ByteArray? = null
    private var fileDone: ((Boolean) -> Unit)? = null
    private val saveCsv = act.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u -> writeFile(u) }
    private val saveJson = act.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> writeFile(u) }

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        val page = AU.col(act)
        // black top: back, title, refresh, and the section tabs
        head.setBackgroundColor(AK.black)
        head.setPadding(Ui.dpi(16f), Ui.dpi(32f), Ui.dpi(16f), Ui.dpi(10f))
        val top = AU.row(act)
        top.addView(square(AdIc.ArrowLeft, 19f, "Back") { nav.back() }, LinearLayout.LayoutParams(Ui.dpi(38f), Ui.dpi(38f)))
        val titles = AU.col(act)
        titles.addView(AU.t(act, "Control Center", 19f, Color.WHITE, true, 22f))
        titles.addView(emailLine)
        top.addView(titles, AU.lp(0, -2, 1f, l = 10f, r = 10f))
        val refresh = FrameLayout(act).apply {
            background = Tf.box(AK.black2, 11f)
            addView(refreshIcon, LayoutParams(Ui.dpi(17f), Ui.dpi(17f), Gravity.CENTER))
            contentDescription = "Refresh"
            setOnClickListener { refreshAll() }
            Ui.pressable(this, 0.94f)
        }
        top.addView(refresh, LinearLayout.LayoutParams(Ui.dpi(38f), Ui.dpi(38f)))
        head.addView(top)
        tabScroll.apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            background = Tf.box(AK.black2, 13f)
            val p = Ui.dpi(4f); setPadding(p, p, p, p); clipToPadding = false
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        tabScroll.addView(tabRow, FrameLayout.LayoutParams(-2, -2))
        head.addView(tabScroll, AU.lp(-1, -2, t = 12f))
        page.addView(head, AU.lp(-1, -2))
        // the white page
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        body.setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), Ui.dpi(40f))
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, AU.lp(-1, 0, 1f))
        addView(page, LayoutParams(-1, -1))
        addView(bottomSlot, LayoutParams(-1, -2, Gravity.BOTTOM))
        // until the role is checked: the white page with the loader (the web's gate)
        gateView.setBackgroundColor(Color.WHITE)
        gateView.isClickable = true
        gateView.addView(TfLoader(act, 28f, AK.purple), LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
        addView(gateView, LayoutParams(-1, -1))
        renderTabs()
    }

    private fun square(inner: String, size: Float, label: String, onClick: () -> Unit) = FrameLayout(act).apply {
        background = Tf.box(AK.black2, 11f)
        addView(ImageView(act).apply { setImageDrawable(Tf.lu(inner, Color.WHITE, size)) }, LayoutParams(Ui.dpi(size), Ui.dpi(size), Gravity.CENTER))
        contentDescription = label
        setOnClickListener { onClick() }
        Ui.pressable(this, 0.94f)
    }

    // ═════════════════ shown / hidden (follows the web address) ═════════════════
    /** [search] = the address's ?query (…?tab=settings opens Settings) */
    fun setShown(on: Boolean, search: String) {
        if (!on) {
            if (!shown) return
            shown = false
            visibility = View.GONE
            stopPolls()
            layer.closeAll()
            toast.clear()
            cur?.destroy(); cur = null
            bottomSlot.removeAllViews()
            return
        }
        val want = tabFrom(search)
        if (!shown) {
            shown = true
            visibility = View.VISIBLE
            lastSearch = search
            tab = want
            if (allowed && gateToken == Api.token) {
                // back on the Control Center: the same page at once, fresh numbers underneath
                openTab(want, true)
                loadOverview(); loadProfiles()
            } else gate()
            startPolls()
            return
        }
        if (search != lastSearch) {
            lastSearch = search
            if (allowed && want != tab) setTab(want)
        }
    }

    private fun tabFrom(search: String): String {
        val t = try { Uri.parse("x://x/$search").getQueryParameter("tab") } catch (e: Exception) { null }
        return if (TABS.any { it.first == t }) t!! else "overview"
    }

    /** Gate: only role "admin" may stay (the web's auth.me() check). */
    private fun gate() {
        allowed = false
        gateView.visibility = View.VISIBLE
        gateView.alpha = 1f
        val token = Api.token
        src.me { r ->
            if (!shown) return@me
            if (r.ok && r.data.optString("role") == "admin") {
                me = r.data; allowed = true; gateToken = token
                emailLine.text = "True Find · only you can see this · ${r.data.optString("email").takeIf { it != "null" }.orEmpty()}"
                gateView.animate().alpha(0f).setDuration(160).withEndAction { gateView.visibility = View.GONE }.start()
                overview = null; profiles = emptyList()
                loadOverview(); loadProfiles()
                openTab(tab, true)
            } else nav.go("/home", true)
        }
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        if (!shown) return
        if (v) startPolls() else stopPolls()
        cur?.onVisible(v)
    }

    /** the phone's Back: a pop-up closes first */
    fun onBack(): Boolean {
        if (visibility != View.VISIBLE) return false
        if (layer.back()) return true
        return false
    }

    // ═════════════════ overview + members (shared by several sections) ═════════════════
    private val poll = object : Runnable {
        override fun run() { if (shown && appVisible && allowed) loadOverview(); main.postDelayed(this, 60_000) }
    }
    private fun startPolls() { main.removeCallbacks(poll); if (shown && appVisible) main.postDelayed(poll, 60_000) }
    private fun stopPolls() { main.removeCallbacks(poll) }

    private var ovSeq = 0
    fun loadOverview() {
        val my = ++ovSeq
        ovLoading = true; ovError = ""
        busyIcon()
        src.fn("adminOps", JSONObject().put("action", "overview")) { r ->
            if (my != ovSeq) return@fn
            ovLoading = false
            if (r.ok) { overview = r.data; ovError = "" } else ovError = AF.err(r, "Could not load the overview.")
            busyIcon(); renderTabs(); cur?.onShared()
        }
    }

    private var profSeq = 0
    /** Members for Verify / Members: the newest 500 + everyone still waiting for a decision. */
    fun loadProfiles() {
        val my = ++profSeq
        profLoading = true; profError = ""
        busyIcon()
        if (tab == "verify" || tab == "members") cur?.onShared()
        src.page("UserProfile", JSONObject(), "-created_date", FETCH_LIMIT) { r ->
            if (my != profSeq) return@page
            if (!r.ok) { profLoading = false; profError = AF.err(r, "Could not load members."); busyIcon(); cur?.onShared(); return@page }
            val raw = ArrayList(Tf.list(r))
            capped = raw.size >= FETCH_LIMIT
            val finish = {
                val seen = HashSet<String>()
                val list = raw.filter { !AF.b(it, "demo") && seen.add(it.optString("id")) }
                profiles = list.sortedByDescending { AF.ms(AF.s(it, "created_date")) }
                profLoading = false; profError = ""
                busyIcon(); renderTabs(); cur?.onShared()
            }
            if (!capped) { finish(); return@page }
            val qs = listOf(
                JSONObject().put("creator_status", "pending"), JSONObject().put("host_status", "pending"),
                JSONObject().put("photo_queue.status", "pending"), JSONObject().put("payout_request_amount", JSONObject().put("\$gt", 0)),
                JSONObject().put("creator_submitted_at", JSONObject().put("\$nin", JSONArray().put(JSONObject.NULL).put(""))),
                JSONObject().put("host_applied_at", JSONObject().put("\$nin", JSONArray().put(JSONObject.NULL).put(""))),
            )
            var left = qs.size
            for (q in qs) src.page("UserProfile", q, null, FETCH_LIMIT) { x ->
                if (my != profSeq) return@page
                if (x.ok) raw.addAll(Tf.list(x))
                if (--left == 0) finish()
            }
        }
    }

    private fun refreshAll() { loadOverview(); loadProfiles() }

    private fun busyIcon() {
        val on = ovLoading || profLoading
        if (on && spin == null) {
            spin = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f).apply { duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start() }
        } else if (!on) { spin?.cancel(); spin = null; refreshIcon.rotation = 0f }
    }

    // ── counts for the tabs ──
    fun subs(): List<JSONObject> = profiles.filter { AF.s(it, "creator_submitted_at").isNotEmpty() }.sortedByDescending { AF.ms(AF.s(it, "creator_submitted_at")) }
    class Stats(val pending: Int, val hostPending: Int, val photoPending: Int)
    fun stats(): Stats {
        val s = subs()
        return Stats(
            s.count { PR.creatorStatus(it) == "pending" },
            profiles.count { PR.hostStatus(it) == "pending" },
            profiles.sumOf { p -> Tf.arr(p.optJSONArray("photo_queue")).count { AF.s(it, "status") != "rejected" } },
        )
    }
    private fun badges(): Map<String, Int> {
        val t = AF.o(overview, "todo")
        val st = stats()
        return mapOf(
            "verify" to st.pending + st.hostPending + st.photoPending,
            "reports" to AF.n(t, "reports").toInt(),
            "live" to (AF.n(t, "billing") + AF.n(AF.o(overview, "live"), "stuck_calls")).toInt(),
            "payouts" to AF.n(t, "payouts").toInt(),
            "support" to AF.n(t, "support_unread").toInt(),
            "errors" to AF.n(t, "errors").toInt(),
        )
    }

    private fun renderTabs() {
        val b = badges()
        tabRow.removeAllViews()
        TABS.forEachIndexed { i, (key, label, ic) ->
            val on = tab == key
            val n = b[key] ?: 0
            val r = AU.row(act, Gravity.CENTER).apply {
                background = if (on) Tf.box(AK.purple, 10f) else null
                setPadding(Ui.dpi(11f), 0, Ui.dpi(11f), 0)
                tag = key
                setOnClickListener { if (tab != key) setTab(key) }
            }
            val fg = if (on) Color.WHITE else Ui.c("#D6CFE3")
            r.addView(AU.icon(act, ic, fg, 14f))
            r.addView(AU.t(act, label, 13f, fg, true), AU.lp(-2, -2, l = 6f))
            if (n > 0) r.addView(AU.badge(act, if (n > 99) "99+" else n.toString(), if (on) Color.WHITE else AK.purple, if (on) AK.purple else Color.WHITE), AU.lp(-2, Ui.dpi(18f), l = 6f))
            tabRow.addView(r, LinearLayout.LayoutParams(-2, Ui.dpi(34f)).apply { if (i > 0) leftMargin = Ui.dpi(6f) })
        }
    }

    /** the open section's tab in the middle of the bar (done when the bar is laid out: the new tabs then have their places) */
    private fun centerTab(smooth: Boolean) {
        centerKey = tab
        centerSmooth = smooth
        centerTarget = -1
        if (preview) android.util.Log.i("TFDBG", "tabbar center $tab smooth=$smooth laidOut=${tabScroll.isLaidOut}")
        tabScroll.requestLayout()
    }

    private fun applyCenter() {
        val key = centerKey ?: return
        val v = (0 until tabRow.childCount).map { tabRow.getChildAt(it) }.firstOrNull { it.tag == key } ?: return
        val inner = tabScroll.width - tabScroll.paddingLeft - tabScroll.paddingRight
        if (inner <= 0 || v.width <= 0 || tabRow.width <= 0) return
        val x = (v.left + v.width / 2 - inner / 2).coerceIn(0, maxOf(0, tabRow.width - inner))
        if (preview) android.util.Log.i("TFDBG", "tabbar place $key left=${v.left} w=${v.width} row=${tabRow.width} bar=${tabScroll.width} x=$x now=${tabScroll.scrollX}")
        if (x == tabScroll.scrollX) { centerSmooth = true; return }
        val now = android.os.SystemClock.uptimeMillis()
        // already gliding there: let it finish (a new call within the glide would jump)
        if (x == centerTarget && now - centerAt < 400) return
        centerTarget = x; centerAt = now
        if (centerSmooth && tabScroll.isShown) tabScroll.smoothScrollTo(x, 0) else tabScroll.scrollTo(x, 0)
        // later moves (counts arriving, wider tabs) glide
        centerSmooth = true
    }

    // ═════════════════ sections ═════════════════
    /** setTab of the web (the address keeps /admin; the section is the phone's own) */
    fun setTab(t: String) {
        if (!TABS.any { it.first == t }) return
        openTab(t, false)
    }

    private fun openTab(t: String, first: Boolean) {
        tab = t
        renderTabs()
        centerTab(!first)
        cur?.destroy()
        bottomSlot.removeAllViews()
        val tb: AdTab = when (t) {
            "growth" -> AdGrowth(this)
            "verify" -> AdVerify(this)
            "members" -> AdMembers(this, memberQ).also { memberQ = "" }
            "reports" -> AdReports(this)
            "experts" -> AdExperts(this)
            "live" -> AdLive(this)
            "payouts" -> AdPayouts(this)
            "support" -> AdSupport(this)
            "errors" -> AdIssues(this)
            "health" -> AdHealth(this)
            "pages" -> AdPages(this)
            "settings" -> AdSettings(this)
            "system" -> AdSystem(this)
            else -> AdOverview(this)
        }
        cur = tb
        body.removeAllViews()
        body.addView(tb.root, AU.lp(-1, -2))
        tb.build()
        if (!first) tb.root.alpha = 0f
        tb.root.animate().alpha(1f).setDuration(160).start()
        scroll.scrollTo(0, 0)
    }

    /** Issues opened from Health, a page or the member sheet: only one kind / area / member, for a time range */
    private var issuesPreset: JSONObject? = null
    fun openIssues(kind: String = "", areas: String = "", range: String = "today", userId: String = "", name: String = "") {
        issuesPreset = JSONObject().put("kind", kind).put("range", range).put("user_id", userId).put("name", name).apply { if (areas.isNotEmpty()) put("areas", areas) }
        layer.closeAll()
        if (tab == "errors") openTab("errors", false) else setTab("errors")
    }
    fun takeIssuesPreset(): JSONObject? = issuesPreset.also { issuesPreset = null }

    /** the red "could not load members" line of Verify / Members, with Retry */
    fun profilesErrorLine(): View = AU.errorLine(act, profError) { loadProfiles() }

    fun openMember(userId: String, ticketId: String = "") {
        if (userId.isEmpty()) return
        AdMemberSheet(this, userId, ticketId).open()
    }

    /** the Support "Find this member": one match opens them, else Members with the search filled in */
    fun findMember(contact: String, ticketId: String) {
        var q = contact.replace(Regex("[^\\d@.a-zA-Z]"), "")
        Regex("^(\\+?91)(\\d{10})$").find(q)?.let { q = it.groupValues[2] }
        val ask = if (q.length > 10 && q.all { it.isDigit() }) q.takeLast(10) else q
        src.fn("adminUsers", JSONObject().put("action", "search").put("q", ask)) { r ->
            if (r.ok) {
                val list = AF.list(r.data, "results")
                if (list.size == 1) { openMember(AF.s(list[0], "user_id"), ticketId); return@fn }
                say(if (list.isNotEmpty()) "${list.size} members match — pick the right one" else "No member with this number or email", list.isEmpty())
            } else say(AF.err(r), true)
            memberQ = q
            layer.closeAll()
            setTab("members")
        }
    }

    fun zoom(raw: String) = AdZoom.open(layer, src, raw)
    fun say(text: String, bad: Boolean = false) = toast.show(text, bad)

    // ═════════════════ verification decisions (same rules as the web) ═════════════════
    private fun nowIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
    private fun reviewer(): String = AF.s(me, "email").ifEmpty { AF.s(me, "id") }.ifEmpty { "admin" }

    private fun patchLocal(id: String, patch: JSONObject) {
        profiles = profiles.map { p -> if (p.optString("id") == id) JSONObject(p.toString()).also { c -> patch.keys().forEach { k -> c.put(k, patch.opt(k)) } } else p }
    }

    fun decideCreator(p: JSONObject, status: String, reason: String, done: (String?) -> Unit) {
        val approved = status == "approved"
        val patch = JSONObject()
            .put("creator_status", status)
            .put("creator_reject_reason", if (status == "rejected") reason else "")
            .put("creator_reviewed_at", nowIso())
            .put("creator_reviewed_by", reviewer())
            .put("creator_reviewed_for", p.opt("creator_submitted_at") ?: JSONObject.NULL)
            .put("creator_approved_upi_id", if (approved) AF.s(p, "creator_upi_id") else "")
            .put("identity_verification_status", if (approved) "verified" else "rejected")
        src.update("UserProfile", p.optString("id"), patch) { r ->
            if (!r.ok) { done(AF.err(r, "Action failed.")); return@update }
            patchLocal(p.optString("id"), patch)
            say(if (approved) "Approved ✓" else "Rejected")
            renderTabs(); loadOverview(); done(null)
        }
    }

    fun decideHost(p: JSONObject, status: String, reason: String, done: (String?) -> Unit) {
        val approved = status == "approved"
        val patch = JSONObject()
            .put("host_status", status)
            .put("host_reject_reason", if (status == "rejected") reason else "")
            .put("host_reviewed_at", nowIso())
            .put("host_reviewed_by", reviewer())
            .put("host_reviewed_for", p.opt("host_applied_at") ?: JSONObject.NULL)
            .put("host_approved_types", if (approved) (p.optJSONArray("host_types") ?: JSONArray()) else JSONArray())
        // Boys get paid to the UPI they gave in the host application.
        if (AF.s(p, "gender") != "female") patch.put("host_approved_upi_id", if (approved) AF.s(p, "host_upi_id") else "")
        src.update("UserProfile", p.optString("id"), patch) { r ->
            if (!r.ok) { done(AF.err(r, "Action failed.")); return@update }
            patchLocal(p.optString("id"), patch)
            say(if (approved) "Host approved ✓" else "Host application rejected")
            renderTabs(); loadOverview(); done(null)
        }
    }

    /** Photo review: approve moves the photo into the member's public photos (max 5, main first). */
    fun decidePhoto(p: JSONObject, uri: String, status: String, reason: String, done: (String?) -> Unit) {
        val id = p.optString("id")
        // Work on a fresh copy, so photos added or removed meanwhile are not lost.
        src.get("UserProfile", id) { g ->
            val fresh = if (g.ok && g.data.optString("id") == id) g.data else p
            val queue = Tf.arr(fresh.optJSONArray("photo_queue"))
            val entry = queue.firstOrNull { AF.s(it, "uri") == uri }
            if (entry == null || AF.s(entry, "status") == "rejected") {
                profiles = profiles.map { x -> if (x.optString("id") == id) JSONObject(x.toString()).also { c -> fresh.keys().forEach { k -> c.put(k, fresh.opt(k)) } } else x }
                say("This photo was already reviewed or removed.")
                sharedChanged(); done(null); return@get
            }
            val patch = JSONObject()
            if (status == "approved") {
                fun srcOf(x: Any?): String = when (x) { is String -> x; is JSONObject -> AF.s(x, "url").ifEmpty { AF.s(x, "uri") }.ifEmpty { AF.s(x, "src") }; else -> "" }
                val main0 = AF.s(fresh, "profile_picture")
                // a photo sent to replace another one (the member's "Replace"): the old one goes now
                val gone = AF.s(entry, "replaces")
                val pic = if (main0.isNotEmpty() && main0 != uri && main0 != gone) main0 else ""
                val imgs = fresh.optJSONArray("images")
                val others = (0 until (imgs?.length() ?: 0)).map { srcOf(imgs!!.opt(it)) }.filter { it.isNotEmpty() && it != uri && it != pic && it != gone }
                val makeMain = AF.b(entry, "main") || pic.isEmpty()
                val list = ArrayList<String>()
                if (makeMain) { list.add(uri); if (pic.isNotEmpty()) list.add(pic); list.addAll(others) } else { list.add(pic); list.addAll(others); list.add(uri) }
                while (list.size > 5) list.removeAt(1) // never more than 5 photos
                patch.put("images", JSONArray(list)).put("profile_picture", list[0])
                    .put("photo_queue", JSONArray(queue.filter { AF.s(it, "uri") != uri }))
            } else {
                patch.put("photo_queue", JSONArray(queue.map { q -> if (AF.s(q, "uri") == uri) JSONObject(q.toString()).put("status", "rejected").put("reason", reason) else q }))
            }
            src.update("UserProfile", id, patch) { r ->
                if (!r.ok) { done(AF.err(r, "Action failed.")); return@update }
                profiles = profiles.map { x -> if (x.optString("id") == id) JSONObject(x.toString()).also { c -> fresh.keys().forEach { k -> c.put(k, fresh.opt(k)) }; patch.keys().forEach { k -> c.put(k, patch.opt(k)) } } else x }
                say(if (status == "approved") "Photo approved ✓" else "Photo rejected")
                renderTabs(); loadOverview(); done(null)
            }
        }
    }
    private fun sharedChanged() { renderTabs(); cur?.onShared() }

    /** after a change in a sheet (member sheet etc.): the overview again (and members when asked) */
    fun changed(members: Boolean = false) { loadOverview(); if (members) loadProfiles() }

    // ═════════════════ phone actions ═════════════════
    fun dial(phone: String) { try { act.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) } catch (e: Exception) { say("No phone app to call with.", true) } }
    fun openUpi(link: String) {
        try { act.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(link)), "Pay with")) } catch (e: Exception) { say("No UPI app found on this phone.", true) }
    }

    /** downloadText of ui.jsx: the phone asks where to save the file */
    fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Boolean) -> Unit = {}) {
        if (preview) { say("Saved $name"); done(true); return }
        pendingFile = bytes; fileDone = done
        try { if (mime.contains("csv")) saveCsv.launch(name) else saveJson.launch(name) } catch (e: Exception) { pendingFile = null; fileDone = null; say("This phone can't save files here.", true); done(false) }
    }
    private fun writeFile(u: Uri?) {
        val bytes = pendingFile; val cb = fileDone
        pendingFile = null; fileDone = null
        if (u == null || bytes == null) { cb?.invoke(false); return }
        Api.async({ try { act.contentResolver.openOutputStream(u)?.use { it.write(bytes) }; true } catch (e: Exception) { false } }) { ok ->
            if (!ok) say("Could not save the file.", true)
            cb?.invoke(ok)
        }
    }

    // ═════════════════ test pictures (made-up data, no login) ═════════════════
    fun startPreview(what: String) {
        preview = true
        src = AdDemo(act)
        val t = what.substringBefore('-').substringBefore(':')
        shown = true; visibility = View.VISIBLE
        tab = if (TABS.any { it.first == t }) t else "overview"
        me = JSONObject().put("id", "admin-1").put("email", "owner@truefindapp.com").put("role", "admin")
        allowed = true; gateToken = Api.token
        emailLine.text = "True Find · only you can see this · owner@truefindapp.com"
        gateView.visibility = View.GONE
        loadOverview(); loadProfiles()
        openTab(tab, true)
    }
    /** a test action on the open section ("member", "sheet", "scroll" …) */
    fun previewOpen(action: String) {
        when {
            action == "scroll" -> scroll.smoothScrollTo(0, body.height / 2)
            action == "scrollend" -> scroll.smoothScrollTo(0, body.height)
            action.startsWith("member:") -> AdMemberSheet(this, "u-g1", "").also { it.focus = action.substringAfter(':') }.open()
            action.startsWith("member") -> openMember("u-g1")
            else -> cur?.previewOpen(action)
        }
    }
    /** brings a part of the page to the top (test pictures) */
    fun scrollToView(v: View) {
        var y = 0; var c: View? = v
        while (c != null && c !== body) { y += c.top; c = c.parent as? View }
        scroll.smoothScrollTo(0, maxOf(0, y - Ui.dpi(8f)))
    }
}

/** One section of the Control Center: made fresh each time it is opened (like the web's tab component). */
abstract class AdTab(val a: AdminScreen) {
    val ctx: android.content.Context get() = a.context
    val src: AdSrc get() = a.src
    val root: LinearLayout = AU.col(a.context)
    var alive = true; private set
    /** first draw (and start loading) */
    abstract fun build()
    /** the shared data (overview / members) changed */
    open fun onShared() {}
    open fun onVisible(v: Boolean) {}
    open fun destroy() { alive = false }
    open fun previewOpen(action: String) {}
    fun say(t: String, bad: Boolean = false) = a.say(t, bad)
}

/** useApi of ui.jsx: one server call loaded when the section opens, again with [load]. */
class AdLoad(private val tab: AdTab, private val fn: String, private val body: () -> JSONObject, private val changed: () -> Unit) {
    var data: JSONObject? = null; private set
    var loading = true; private set
    var error = ""; private set
    private var seq = 0
    fun load(after: (() -> Unit)? = null) {
        val my = ++seq
        val hadError = error.isNotEmpty()
        loading = true; error = ""
        if (data == null || hadError) changed()
        tab.src.fn(fn, body()) { r ->
            if (!tab.alive || my != seq) return@fn
            loading = false
            if (r.ok) { data = r.data; error = "" } else error = AF.err(r, "Could not load. Please try again.")
            changed()
            after?.invoke()
        }
    }
}
