package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * The Settings drawer of Profile (?settings=1), sliding in from the left, and its screens
 * (?settings=notifications | apply | verification | memberships | privacy | blocked |
 * security | refer | help) sliding in from the right over the list.
 */
@SuppressLint("ViewConstructor")
class PSettings(ctx: Context, private val ps: ProfileScreen) : FrameLayout(ctx) {
    private class Item(val id: String, val title: String, val sub: String?, val icon: String, val color: Int, val go: String? = null, val action: (() -> Unit)? = null, val danger: Boolean = false)

    private val ease = PathInterpolator(.25f, .6f, .3f, 1f)
    private val dim = View(ctx).apply { setBackgroundColor(Color.argb(115, 20, 10, 30)) }
    private val aside = FrameLayout(ctx).apply { setBackgroundColor(PC.ground) }
    private val listScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
    private val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var panelView: View? = null
    private var panelBody: PPanel? = null
    var panel: String? = null; private set
    private var leaving = false

    init {
        isClickable = true
        elevation = Ui.dp(30f)
        addView(dim, LayoutParams(-1, -1))
        dim.setOnClickListener { ps.closeDrawer() }
        listScroll.addView(listCol, LayoutParams(-1, -2))
        aside.addView(listScroll, LayoutParams(-1, -1))
        addView(aside, LayoutParams(-1, -1))
        renderList()
    }

    fun slideIn() {
        dim.alpha = 0f; dim.animate().alpha(1f).setDuration(300).start()
        aside.translationX = -resources.displayMetrics.widthPixels.toFloat()
        aside.animate().translationX(0f).setDuration(300).setInterpolator(ease).start()
    }
    fun slideOut(done: () -> Unit) {
        leaving = true
        dim.animate().alpha(0f).setDuration(300).start()
        aside.animate().translationX(-width.toFloat().coerceAtLeast(1f)).setDuration(300).setInterpolator(ease).withEndAction { release(); done() }.start()
    }
    fun release() { panelBody?.release(); panelBody = null }
    fun onAppVisible(v: Boolean) { panelBody?.onAppVisible(v) }

    /** the data changed (profile, notifications, blocked, premium) */
    fun refresh() {
        if (leaving) return
        renderList()
        panelBody?.refresh()
        panelView?.findViewWithTag<TextView>("ptitle")?.text = titleOf(panel)
    }

    // ── the list ──
    private fun items(): List<List<Item>> {
        val unread = ps.unread
        val coins = ps.coins
        val vi = ps.vInfo
        val st = ps.applyStatus
        val g1 = ArrayList<Item>()
        // only the owner (admin) sees this: the Control Center
        if (PR.s(ps.me, "role") == "admin") g1.add(Item("admin", "Control Center", "Only you can see this", Lu.LayoutDashboard, Ui.c("#17121F"), go = "/admin"))
        g1.add(Item("notifications", "Notifications", if (unread > 0) "$unread new" else "You're all caught up", Lu.Bell, PC.tNotifications))
        g1.add(Item("wallet", "Wallet", if (coins != null) "${jsNum(coins)} coins · top-ups and history" else "Balance, top-ups and history", Lu.Wallet, PC.tWallet, go = "/wallet"))
        g1.add(Item("refer", "Refer & Earn", "Invite friends · you both get free coins", Lu.Gift, PC.tRefer))
        g1.add(Item("apply", if (ps.applyKind == "creator") "Become a Creator" else "Become a Host",
            when (st) { "approved" -> "Approved"; "pending" -> "Under review"; "rejected" -> "Rejected · apply again"; else -> "Not applied" }, Lu.Radio, PC.tHost))
        g1.add(Item("verification", "Verification", if (vi.verified) "Verified" else if (vi.pending) "Under review" else "Identity and age status", Lu.ShieldCheck, PC.tVerification))
        g1.add(Item("memberships", "Memberships & premium", if (ps.premiumActive) "Secret Dating premium active" else "True Find, Secret Dating", Lu.Crown, PC.tMemberships))
        val g2 = listOf(
            Item("privacy", "Privacy", if (ps.profile?.opt("location_visible") == false) "Location hidden" else "Location visible", Lu.Eye, PC.tPrivacy),
            Item("blocked", "Blocked users", "${ps.blocked.size} blocked", Lu.Ban, PC.tBlocked),
            Item("security", "Security & guidelines", "Stay safe on the app", Lu.Lock, PC.tSecurity),
            Item("help", "Help & support", "Reports and account help", Lu.HelpCircle, PC.tHelp))
        val g3 = listOf(
            Item("logout", "Log out", null, Lu.LogOut, PC.tLogout, action = { ps.doLogout() }),
            Item("delete", "Delete account", "Permanently remove your account", Lu.Trash2, PC.tDelete, action = { ps.doDelete() }, danger = true))
        return listOf(g1, g2, g3)
    }
    /** a number as JS prints it (12, 12.5) */
    private fun jsNum(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    private fun titleOf(id: String?): String = items().flatten().firstOrNull { it.id == id }?.title ?: ""

    private fun renderList() {
        val ctx = context
        val y = listScroll.scrollY
        listCol.removeAllViews()
        val head = PUi.pageHead(ctx, "Settings", null, PUi.headBtn(ctx, "Close settings", PUi.lu(Lu.X, Color.WHITE, 20f, 2.2f)) { ps.closeDrawer() })
        listCol.addView(head, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = Tf.topRound(PC.ground, 28f)
            setPadding(Ui.dpi(16f), Ui.dpi(20f), Ui.dpi(16f), Ui.dpi(22f))
        }
        // back to my profile
        val me = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dpi(68f)
            setPadding(Ui.dpi(12f), 0, Ui.dpi(10f), 0); background = Tf.box(Color.WHITE, 16f, 1f, PC.line)
            contentDescription = "Back to my profile"; setOnClickListener { ps.closeDrawer() }; Ui.pressable(this, .97f)
        }
        val fw = FrameLayout(ctx).apply { background = Tf.circle(Ui.c("#E9DDFB"), 1f, PC.wineLine); setPadding(Ui.dpi(1f), Ui.dpi(1f), Ui.dpi(1f), Ui.dpi(1f)) }
        val face = PImg(ctx, round = true).apply { placeholder = Ui.c("#E9DDFB") }
        fw.addView(face, FrameLayout.LayoutParams(-1, -1))
        face.set(ps.avatarRaws)
        me.addView(fw, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)).apply { rightMargin = Ui.dpi(12f) })
        val mc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        mc.addView(Tf.t(ctx, ps.name.ifEmpty { "Your name" }, 15f, PC.ink).lineH(20f).ell())
        mc.addView(Tf.t(ctx, "Joined ${PR.fmtDate(ps.joinedAt).ifEmpty { "—" }}", 12.5f, PC.sub, false).lineH(16f))
        me.addView(mc, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(12f) })
        me.addView(PUi.icon(ctx, PUi.lu(Lu.ChevronRight, PC.sub, 16f, 2.2f), 16f))
        body.addView(me, LinearLayout.LayoutParams(-1, -2))
        val unread = ps.unread
        for (group in items()) {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 16f, 1f, PC.line); clipToOutline = true }
            group.forEachIndexed { i, it ->
                if (i > 0) box.addView(View(ctx).apply { setBackgroundColor(PC.line) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dpi(56f)
                    setPadding(Ui.dpi(12f), 0, Ui.dpi(10f), 0)
                    setOnClickListener { _ -> onItem(it) }; Ui.pressable(this, .97f)
                }
                row.addView(PUi.tile(ctx, it.color, it.icon, 34f), LinearLayout.LayoutParams(Ui.dpi(34f), Ui.dpi(34f)).apply { rightMargin = Ui.dpi(12f) })
                val tc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                tc.addView(Tf.t(ctx, it.title, 14.5f, if (it.danger) Ui.c("#B42318") else PC.ink).lineH(19f))
                if (!it.sub.isNullOrEmpty()) tc.addView(Tf.t(ctx, it.sub, 12.5f, PC.sub, false).lineH(16f).ell())
                row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(12f) })
                if (it.id == "notifications" && unread > 0) row.addView(Tf.t(ctx, unread.toString(), 11.5f, Color.WHITE).apply {
                    gravity = Gravity.CENTER; background = Tf.box(PC.head, 7f); minWidth = Ui.dpi(20f); setPadding(Ui.dpi(6f), 0, Ui.dpi(6f), 0)
                }, LinearLayout.LayoutParams(-2, Ui.dpi(20f)).apply { rightMargin = Ui.dpi(12f) })
                row.addView(PUi.icon(ctx, PUi.lu(Lu.ChevronRight, PC.sub, 16f, 2.2f), 16f))
                box.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            body.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(16f) })
        }
        val wm = FrameLayout(ctx).apply { setPadding(0, Ui.dpi(4f), 0, 0) }
        wm.addView(PUi.wordmark(ctx, 14f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        body.addView(wm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(16f) })
        listCol.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = -Ui.dpi(28f) })
        listScroll.post { listScroll.scrollTo(0, y) }
    }

    private fun onItem(it: Item) {
        if (it.go != null) { ps.leaveTo(it.go); return }
        if (it.action != null) { it.action.invoke(); return }
        ps.openPanel(it.id)
        if (it.id == "notifications") postDelayed({ ps.markRead() }, 1200)
    }

    // ── one screen over the list ──
    fun setPanel(id: String?) {
        if (id == panel) return
        val old = panelView
        panel = id
        panelBody?.release(); panelBody = null
        old?.let { aside.removeView(it) }
        panelView = null
        if (id == null) {
            listScroll.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(ease).start()
            return
        }
        listScroll.animate().translationX(-aside.width * 0.25f).alpha(0f).setDuration(260).setInterpolator(ease).start()
        val ctx = context
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(PC.head); setPadding(Ui.dpi(16f), Ui.dpi(52f), Ui.dpi(16f), Ui.dpi(14f)) }
        bar.addView(PUi.headBtn(ctx, "Back to settings", PUi.lu(Lu.ChevronLeft, Color.WHITE, 22f, 2.2f)) { ps.panelBack() }, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)).apply { rightMargin = Ui.dpi(12f) })
        bar.addView(Tf.t(ctx, titleOf(id), 17f, Color.WHITE).lineH(22f).ell().apply { tag = "ptitle" }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val sc = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dpi(12f), 0, Ui.dpi(24f)) }
        sc.addView(content, LayoutParams(-1, -2))
        page.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        val body: PPanel? = when (id) {
            "notifications" -> PNotifications(ctx, ps)
            "apply" -> PApply(ctx, ps)
            "verification" -> PVerification(ctx, ps)
            "memberships" -> PMemberships(ctx, ps)
            "privacy" -> PPrivacy(ctx, ps)
            "blocked" -> PBlocked(ctx, ps)
            "security" -> PInfo(ctx, ps, "security")
            "refer" -> PRefer(ctx, ps)
            "help" -> PInfo(ctx, ps, "help")
            else -> null
        }
        if (body != null) { content.addView(body.view, LinearLayout.LayoutParams(-1, -2)); body.scroll = sc }
        panelBody = body
        panelView = page
        aside.addView(page, LayoutParams(-1, -1))
        page.translationX = aside.width.toFloat().coerceAtLeast(resources.displayMetrics.widthPixels.toFloat())
        page.animate().translationX(0f).setDuration(260).setInterpolator(ease).start()
    }

    fun previewOpen(what: String) { panelBody?.previewOpen(what) }
}

/** One Settings screen. */
abstract class PPanel(val ctx: Context, val ps: ProfileScreen) {
    abstract val view: View
    var scroll: ScrollView? = null
    open fun refresh() {}
    open fun release() {}
    open fun onAppVisible(v: Boolean) {}
    open fun previewOpen(what: String) {}

    fun col(l: Float = 16f, t: Float = 4f, r: Float = 16f, b: Float = 24f) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)) }
    fun lp(top: Float = 0f) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(top) }
    fun line(): View = View(ctx).apply { setBackgroundColor(PC.line) }
    /** title + text rows with a line under each (Security, Help) */
    fun infoList(into: LinearLayout, rows: List<Pair<String, String>>) {
        for ((t, s) in rows) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dpi(13f), 0, Ui.dpi(13f)) }
            r.addView(Tf.t(ctx, t, 14f, PC.ink))
            r.addView(PUi.para(ctx, s, 13f, PC.sub, 19f), lp(3f))
            into.addView(r, lp())
            into.addView(line(), LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
        }
    }
    fun card(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 18f, 1f, PC.line); setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f)) }
}

class PNotifications(ctx: Context, ps: ProfileScreen) : PPanel(ctx, ps) {
    override val view = col()
    init { refresh() }
    override fun refresh() {
        view.removeAllViews()
        val list = ps.notifications
        if (list.isEmpty()) {
            val e = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(10f), Ui.dpi(40f), Ui.dpi(10f), Ui.dpi(40f)) }
            e.addView(PUi.tile(ctx, PC.tNotifications, Lu.Bell, 52f))
            e.addView(Tf.t(ctx, "You're all caught up", 15f, PC.ink).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(14f); bottomMargin = Ui.dpi(4f) })
            e.addView(Tf.t(ctx, "New likes, messages and updates will show here.", 13f, PC.sub, false).apply { gravity = Gravity.CENTER })
            view.addView(e, lp())
            return
        }
        for (n in list) {
            val read = PR.truthy(n.opt("read")) || PR.truthy(n.opt("is_read"))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dpi(12f), 0, Ui.dpi(12f)) }
            row.addView(View(ctx).apply { background = Tf.circle(if (read) Color.TRANSPARENT else PC.wine) }, LinearLayout.LayoutParams(Ui.dpi(8f), Ui.dpi(8f)).apply { topMargin = Ui.dpi(6f); rightMargin = Ui.dpi(12f) })
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val title = PR.s(n, "title"); val msg = PR.s(n, "message").ifEmpty { PR.s(n, "body") }.ifEmpty { PR.s(n, "text") }
            c.addView(Tf.t(ctx, title.ifEmpty { msg }.ifEmpty { "Notification" }, 14f, PC.ink))
            if (title.isNotEmpty() && msg.isNotEmpty()) c.addView(Tf.t(ctx, msg, 13f, PC.sub, false), lp(2f))
            c.addView(Tf.t(ctx, PR.fmtDate(PR.s(n, "created_date")), 11.5f, PC.sub, false), lp(4f))
            row.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
            view.addView(row, lp())
            view.addView(line(), LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
        }
    }
}

class PVerification(ctx: Context, ps: ProfileScreen) : PPanel(ctx, ps) {
    override val view = col()
    init { refresh() }
    override fun refresh() {
        view.removeAllViews()
        val p = ps.profile
        val vi = ps.vInfo
        val st = ps.applyStatus
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)); background = Tf.box(if (vi.verified) Ui.c("#ECFDF3") else PC.faint, 18f) }
        top.addView(PUi.icon(ctx, PUi.lu(Lu.BadgeCheck, if (vi.verified) PC.green else PC.sub, 30f), 30f), LinearLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f)).apply { rightMargin = Ui.dpi(12f) })
        val tc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tc.addView(Tf.t(ctx, if (vi.verified) "Your profile is verified" else if (vi.pending) "Verification under review" else "Not verified yet", 15f, PC.ink))
        tc.addView(Tf.t(ctx, if (vi.verified) (if (vi.at.isNotEmpty()) "Completed on ${PR.fmtDate(vi.at)}" else "Verified by the True Find team") else "Verified profiles get more trust and more calls.", 12.5f, PC.sub, false), lp(2f))
        top.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(top, lp())
        val rows = ArrayList<Pair<String, Any?>>()
        rows.add("Identity" to p?.opt("identity_verification_status"))
        rows.add("Age (18+)" to p?.opt("age_verification_status"))
        if (PR.truthy(p?.opt("voice_verification_status"))) rows.add("Voice" to p?.opt("voice_verification_status"))
        if (PR.truthy(p?.opt("pan_verification_status"))) rows.add("PAN card" to p?.opt("pan_verification_status"))
        rows.add((if (ps.isFemale) "Creator" else "Host") to (if (st == "none") "" else st))
        for ((label, v) in rows) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(2f), Ui.dpi(13f), Ui.dpi(2f), Ui.dpi(13f)) }
            r.addView(Tf.t(ctx, label, 14f, PC.ink), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(PUi.statusPill(ctx, PR.norm(v)))
            view.addView(r, lp())
            view.addView(line(), LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
        }
        if (st != "approved") view.addView(PUi.btn(ctx, if (ps.applyKind == "creator") "Become a Creator" else "Become a Host", "light") { ps.openPanel("apply") },
            LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(16f) })
    }
}

class PMemberships(ctx: Context, ps: ProfileScreen) : PPanel(ctx, ps) {
    override val view = col()
    init { refresh() }
    override fun refresh() {
        view.removeAllViews()
        val p = ps.profile
        val joined = Tf.strs(p?.optJSONArray("apps")).contains("secret")
        val c1 = card()
        val r1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r1.addView(PUi.tile(ctx, PC.tHost, Lu.Heart), LinearLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(42f)).apply { rightMargin = Ui.dpi(12f) })
        val t1 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t1.addView(Tf.t(ctx, "True Find", 15f, PC.ink))
        t1.addView(Tf.t(ctx, "Member since ${PR.fmtDate(ps.joinedAt).ifEmpty { "—" }}", 12.5f, PC.sub, false), lp(2f))
        r1.addView(t1, LinearLayout.LayoutParams(0, -2, 1f))
        c1.addView(r1)
        view.addView(c1, lp())
        val c2 = card()
        val r2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r2.addView(PUi.tile(ctx, PC.tMemberships, Lu.Crown), LinearLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(42f)).apply { rightMargin = Ui.dpi(12f) })
        val t2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t2.addView(Tf.t(ctx, "Secret Dating", 15f, PC.ink))
        val until = PR.s(ps.premium, "until")
        t2.addView(Tf.t(ctx, if (ps.premiumActive) "Premium active${if (until.isNotEmpty()) " until ${PR.fmtDate(until)}" else ""}" else if (joined) "Joined · free plan" else "Not joined yet", 12.5f, PC.sub, false), lp(2f))
        r2.addView(t2, LinearLayout.LayoutParams(0, -2, 1f))
        c2.addView(r2)
        if (!joined && !ps.premiumActive) c2.addView(PUi.btn(ctx, "Join Secret Dating", "light") { ps.joinSecret { } }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(14f) })
        c2.addView(PUi.btn(ctx, "Open Secret Dating") { ps.leaveTo("/secret-app") }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(10f) })
        view.addView(c2, lp(12f))
    }
}

class PPrivacy(ctx: Context, ps: ProfileScreen) : PPanel(ctx, ps) {
    override val view = col()
    private val tg = TfToggle(ctx, PC.wine).apply { contentDescription = "Show my location" }
    init {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Tf.box(Color.WHITE, 16f, 1f, PC.line); setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) }
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Tf.t(ctx, "Show my location", 14f, PC.ink))
        t.addView(Tf.t(ctx, "Only your city and state are shown.", 12.5f, PC.sub, false), lp(2f))
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(12f) })
        r.addView(tg)
        view.addView(r, lp())
        view.addView(PUi.para(ctx, "Your mobile number is never shown to anyone. Numbers, links and social media IDs are blocked in chats and bios to keep you safe.", 12.5f, PC.sub, 18f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f); leftMargin = Ui.dpi(4f); rightMargin = Ui.dpi(4f) })
        tg.set(ps.profile?.opt("location_visible") != false, false)
        tg.onChange = { v -> ps.setLocationVisible(v) }
    }
    override fun refresh() { tg.set(ps.profile?.opt("location_visible") != false) }
}

class PBlocked(ctx: Context, ps: ProfileScreen) : PPanel(ctx, ps) {
    override val view = col()
    init { refresh() }
    override fun refresh() {
        view.removeAllViews()
        if (ps.blocked.isEmpty()) {
            view.addView(Tf.t(ctx, "You haven't blocked anyone.", 13.5f, PC.sub, false).apply { gravity = Gravity.CENTER; setPadding(Ui.dpi(10f), Ui.dpi(40f), Ui.dpi(10f), Ui.dpi(40f)) }, lp())
            return
        }
        for (b in ps.blocked) {
            val name = PR.s(b, "blocked_name")
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dpi(12f), 0, Ui.dpi(12f)) }
            r.addView(Tf.t(ctx, name.ifEmpty { "U" }.take(1).uppercase(), 16f, PC.plum).apply { gravity = Gravity.CENTER; background = Tf.circle(PC.faint) },
                LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f)).apply { rightMargin = Ui.dpi(12f) })
            r.addView(Tf.t(ctx, name.ifEmpty { "User" }, 14f, PC.ink).ell(), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(12f) })
            r.addView(Tf.t(ctx, "Unblock", 13f, PC.wine).apply {
                gravity = Gravity.CENTER; background = Tf.box(Color.TRANSPARENT, 10f, 1.5f, PC.line); setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
                setOnClickListener { ps.unblock(b) }; Ui.pressable(this, .97f)
            }, LinearLayout.LayoutParams(-2, Ui.dpi(34f)))
            view.addView(r, lp())
            view.addView(line(), LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
        }
    }
}

/** Security & guidelines, or Help & support (FAQs + Contact support) */
class PInfo(ctx: Context, ps: ProfileScreen, private val kind: String) : PPanel(ctx, ps) {
    override val view = col(t = 0f)
    private var support: PSupport? = null
    init {
        if (kind == "security") infoList(view, listOf(
            "Never share your OTP" to "True Find will never ask for your OTP, password or bank details.",
            "No money requests" to "If anyone asks you to send money, report and block them.",
            "Keep chats in the app" to "Numbers, links and social media IDs are blocked to keep you safe.",
            "Meet safely" to "If you meet someone, meet in a public place and tell a friend.",
            "18+ only" to "True Find is only for adults. Report anyone who looks under 18.",
            "Respect everyone" to "Abuse, nudity and harassment lead to a permanent ban."))
        else {
            val f = ps.isFemale
            infoList(view, listOf(
                "How do I add coins?" to "Open Settings → Wallet and choose a top-up.",
                "How do I report someone?" to "Open their profile or chat, tap the menu and choose Report.",
                "Why is my photo “In review”?" to "Every new photo is checked by our team. It shows to others after approval.",
                (if (f) "How do I become a Creator?" else "How do I become a Host?") to "Open Settings → " + (if (f) "Become a Creator" else "Become a Host") + " and follow the steps.",
                "How do I delete my account?" to "Open Settings → Delete account. This removes your data forever."))
            val s = PSupport(ctx, ps)
            support = s
            view.addView(s.view, lp(18f))
        }
    }
    override fun release() { support?.release() }
    override fun previewOpen(what: String) { support?.previewOpen(what) }
}

/**
 * Become a Host / Become a Creator: status, what is needed (Host: call minutes), then the
 * form — the live selfie video, UPI name and UPI ID, the name-match check — sent to the server.
 */
class PApply(ctx: Context, ps: ProfileScreen) : PPanel(ctx, ps) {
    override val view = col()
    private val kind = ps.applyKind
    private val femaleHost = kind == "host" && ps.gender == "female"
    private val needMinutes = if (femaleHost) 300 else PR.HOST_MIN_MINUTES
    /** checked: the count is known (or could not be read: callMins stays null) */
    private var callChecked = false
    private var callMins: Int? = null
    private var step = "info"
    private var video: File? = null
    private var upiName = ""
    private var upiId = ""
    private var agree = false
    private var busy = false
    private var formErr = ""
    private var recorder: LiveRecorder? = null
    private var alive = true
    // form views (kept while the form is open)
    private var errView: TextView? = null
    private var submitBtn: TfBtn? = null
    private var backBtn: TfBtn? = null

    init {
        countMinutes()
        render()
    }

    private fun countMinutes() {
        val uid = PR.s(ps.profile, "created_by_id")
        if (kind != "host" || uid.isEmpty()) { callChecked = true; callMins = null; return }
        // the profile has no minutes field: count them from the member's own calls (same count the admin sees)
        val only = if (femaleHost) "video" else null
        val seen = HashSet<String>(); var secs = 0.0; var left = 2; var failed = false
        fun one(field: String) {
            val q = JSONObject().put(field, uid); if (only != null) q.put("type", only)
            val path = "/entities/Call/v2/list?q=" + java.net.URLEncoder.encode(q.toString(), "UTF-8") + "&limit=1000&fields=" + java.net.URLEncoder.encode("id,duration_seconds,started_at,ended_at", "UTF-8")
            Api.async({ if (Demo.on) Demo.filter("Call", q) else Api.request("GET", path, null, null) }) { r ->
                if (!r.ok) failed = true
                else for (c in PR.list(r)) {
                    val id = PR.s(c, "id"); if (id.isEmpty() || !seen.add(id)) continue
                    var x = PR.firstNum(c, listOf("duration_seconds")) ?: 0.0
                    if (x == 0.0 && PR.truthy(c.opt("started_at")) && PR.truthy(c.opt("ended_at"))) {
                        val d = (Tf.ms(PR.s(c, "ended_at")) - Tf.ms(PR.s(c, "started_at"))) / 1000.0
                        if (d > 0) x = d
                    }
                    secs += x
                }
                left -= 1
                if (left == 0 && alive) { callChecked = true; callMins = if (failed) null else Math.floor(secs / 60).toInt(); if (step == "info") render() }
            }
        }
        one("caller_id"); one("callee_id")
    }

    private val minutes: Int? get() {
        val cm = if (callChecked) callMins else null
        if (femaleHost) return cm
        PR.firstNum(ps.profile, listOf("total_call_minutes", "call_minutes_total", "call_minutes", "talk_minutes"))?.let { return it.toInt() }
        PR.firstNum(ps.profile, listOf("total_call_seconds", "call_seconds"))?.let { return Math.floor(it / 60).toInt() }
        return cm
    }
    /** while the count loads the Apply button waits; if it can't be read, the team checks it */
    private val minutesOk: Boolean get() = kind != "host" || (minutes?.let { it >= needMinutes } ?: (callChecked && callMins == null))

    override fun refresh() { if (step == "info") render() }
    override fun release() { alive = false; recorder?.release(); video?.delete() }

    private val title get() = if (kind == "host") "Become a Host" else "Become a Creator"

    private fun render() {
        view.removeAllViews()
        recorder = null; errView = null; submitBtn = null; backBtn = null
        if (step == "form") { renderForm(); return }
        val p = ps.profile
        val status = if (kind == "host") PR.hostStatus(p) else PR.creatorStatus(p)
        val reason = PR.s(p, if (kind == "host") "host_reject_reason" else "creator_reject_reason")
        val c = card()
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(Tf.t(ctx, "Your status", 15f, PC.ink), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(10f) })
        r.addView(PUi.statusPill(ctx, status))
        c.addView(r)
        val text = when (status) {
            "approved" -> if (kind == "host") "You are an approved Host. You can go live and earn." else "You are an approved Creator. You can earn on True Find."
            "pending" -> "Our team is checking your video. This usually takes up to 24 hours."
            "rejected" -> "Your application was not approved. You can apply again."
            else -> "Earn money on True Find. Complete the steps below and apply."
        }
        c.addView(PUi.para(ctx, text, 13.5f, PC.sub, 19f), lp(10f))
        if (status == "rejected" && reason.isNotEmpty()) c.addView(Tf.t(ctx, "Reason: $reason", 13f, Ui.c("#991B1B"), false).apply { background = Tf.box(Ui.c("#FDECEC"), 12f); setPadding(Ui.dpi(10f), Ui.dpi(10f), Ui.dpi(10f), Ui.dpi(10f)) }, lp(10f))
        view.addView(c, lp())
        view.addView(Tf.t(ctx, "What you need", 13f, PC.ink), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(18f); bottomMargin = Ui.dpi(8f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
        val m = minutes
        val needs = ArrayList<String>()
        if (kind == "host") needs.add(if (femaleHost) "At least 5 hours of video calls on True Find${if (m != null) " (you have ${m / 60} h ${m % 60} min)" else ""}"
            else "At least ${PR.HOST_MIN_MINUTES} minutes of calls on True Find${if (m != null) " (you have $m)" else ""}")
        needs.add("A ${PR.VIDEO_SECONDS}-second live selfie video (camera only)")
        needs.add("Your UPI name and UPI ID for payments")
        needs.add("Your UPI name must match your profile name, otherwise it will be rejected")
        for (t in needs) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dpi(2f), Ui.dpi(9f), Ui.dpi(2f), Ui.dpi(9f)) }
            val tick = FrameLayout(ctx).apply { background = Tf.box(PC.wineSoft, 7f) }
            tick.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Check, PC.wine, 13f, 3f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            row.addView(tick, LinearLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f)).apply { rightMargin = Ui.dpi(10f) })
            row.addView(PUi.para(ctx, t, 13.5f, PC.ink, 19f), LinearLayout.LayoutParams(0, -2, 1f))
            view.addView(row, lp())
        }
        if (kind == "host" && m != null && !minutesOk) {
            val bar = FrameLayout(ctx).apply { background = Tf.box(PC.faint, 999f); clipToOutline = true }
            val fill = View(ctx).apply { background = Tf.box(PC.wine, 0f) }
            bar.addView(fill, FrameLayout.LayoutParams(0, -1))
            bar.post { (fill.layoutParams as FrameLayout.LayoutParams).width = (bar.width * minOf(1.0, m.toDouble() / needMinutes)).toInt(); fill.requestLayout() }
            view.addView(bar, LinearLayout.LayoutParams(-1, Ui.dpi(8f)).apply { topMargin = Ui.dpi(8f) })
            view.addView(Tf.t(ctx, "${needMinutes - m} more minutes of ${if (femaleHost) "video calls" else "calls"} needed.", 12.5f, PC.sub, false),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(6f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
        }
        if (status == "none" || status == "rejected") {
            val b = PUi.btn(ctx, if (status == "rejected") "Apply again" else "Apply to ${title.replace("Become a ", "become a ")}") {
                if (kind == "creator") ps.go("/register/verification") else { step = "form"; render(); scroll?.scrollTo(0, 0) }
            }
            b.off = !minutesOk
            view.addView(b, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(16f) })
        }
    }

    private fun renderForm() {
        val name = ps.name
        val first = name.trim().split(Regex("\\s+")).firstOrNull()?.ifEmpty { null } ?: "your name"
        val line = if (kind == "host") "Hi, I am $first. I want to become a Host on True Find." else "Hi, I am $first. I want to become a Creator on True Find."
        val rec = LiveRecorder(ctx, ps.pages.activity, PR.VIDEO_SECONDS, line, { cb -> ps.pages.askCamera(cb) }) { f -> video?.takeIf { it != f }?.delete(); video = f }
        recorder = rec
        view.addView(rec, lp())
        view.addView(Tf.t(ctx, "UPI name", 13f, PC.ink), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(16f); bottomMargin = Ui.dpi(6f) })
        val nameIn = TfInput(ctx, "Name on your UPI account").apply { setText(upiName); contentDescription = "UPI name"; onText = { upiName = it } }
        view.addView(nameIn, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        view.addView(Tf.t(ctx, "UPI ID", 13f, PC.ink), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(12f); bottomMargin = Ui.dpi(6f) })
        val idIn = TfInput(ctx, "name@okaxis").apply {
            setText(upiId); contentDescription = "UPI ID"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        idIn.onText = { t -> val c = t.replace(Regex("\\s"), ""); if (c != t) { idIn.setText(c); idIn.setSelection(c.length) }; upiId = c }
        view.addView(idIn, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        val warn = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; background = Tf.box(Ui.c("#FFF7ED"), 14f, 1f, Ui.c("#FED7AA")); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)) }
        warn.addView(PUi.icon(ctx, PUi.lu(Lu.AlertTriangle, Ui.c("#B45309"), 18f), 18f), LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)).apply { topMargin = Ui.dpi(1f); rightMargin = Ui.dpi(10f) })
        warn.addView(PUi.para(ctx, "Your UPI name must match your profile name (${name.ifEmpty { "your name" }}). If it does not match, your application will be rejected.", 12.5f, Ui.c("#7C2D12"), 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(warn, lp(12f))
        val ag = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val box = FrameLayout(ctx)
        val tick = ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Check, Color.WHITE, 14f, 3f)) }
        box.addView(tick, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val paintAgree = { box.background = Tf.box(if (agree) PC.wine else Color.WHITE, 7f, 2f, if (agree) PC.wine else PC.wineLine); tick.visibility = if (agree) View.VISIBLE else View.GONE; ag.contentDescription = "My UPI name matches my profile name" + if (agree) ", checked" else "" }
        paintAgree()
        ag.addView(box, LinearLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f)).apply { rightMargin = Ui.dpi(10f) })
        ag.addView(Tf.t(ctx, "My UPI name matches my profile name", 13f, PC.ink), LinearLayout.LayoutParams(0, -2, 1f))
        ag.setOnClickListener { agree = !agree; paintAgree() }
        Ui.pressable(ag, .97f)
        view.addView(ag, LinearLayout.LayoutParams(-1, Ui.dpi(36f)).apply { topMargin = Ui.dpi(8f) })
        val err = Tf.t(ctx, "", 13f, PC.red).apply { visibility = View.GONE }
        errView = err
        view.addView(err, lp(12f))
        val sub = PUi.btn(ctx, "Submit application") { submit() }
        submitBtn = sub
        view.addView(sub, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(14f) })
        val back = PUi.btn(ctx, "Back", "light") { if (!busy) { step = "info"; formErr = ""; video?.delete(); video = null; render() } }
        backBtn = back
        view.addView(back, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(10f) })
        showErr()
    }

    private fun showErr() {
        errView?.let { it.text = formErr; it.visibility = if (formErr.isEmpty()) View.GONE else View.VISIBLE }
        submitBtn?.busy = busy
        backBtn?.off = busy
    }

    private fun submit() {
        if (busy) return
        formErr = ""
        val v = video
        formErr = when {
            v == null -> "Please record your ${PR.VIDEO_SECONDS}-second video."
            upiName.trim().length < 2 -> "Please enter your UPI name."
            !Regex("^[\\w.-]{2,}@[a-zA-Z][a-zA-Z0-9.]{1,}$").matches(upiId.trim()) -> "Please enter a valid UPI ID, like name@okaxis."
            !agree -> "Please confirm that your UPI name matches your profile name."
            else -> ""
        }
        if (formErr.isNotEmpty() || v == null) { showErr(); return }
        busy = true; showErr()
        val name = "$kind-video-${System.currentTimeMillis()}.mp4"
        Api.async({
            val bytes = try { v.readBytes() } catch (e: Throwable) { null }
            if (bytes == null) "" else {
                // private first; a normal upload when that is not possible
                val a = PUp.send(true, bytes, name, "video/mp4")
                val uri = if (a.ok) PR.s(a.data, "file_uri").ifEmpty { PR.s(a.data.optJSONObject("data"), "file_uri") } else ""
                uri.ifEmpty {
                    val b = PUp.send(false, bytes, name, "video/mp4")
                    if (b.ok) PR.s(b.data, "file_url").ifEmpty { PR.s(b.data, "url") }.ifEmpty { PR.s(b.data.optJSONObject("data"), "file_url") } else ""
                }
            }
        }) { uri ->
            if (!alive) return@async
            if (uri.isEmpty()) { busy = false; formErr = "Could not send your application. Please try again."; showErr(); return@async }
            // sent as an application; only the server sets pending / approved
            ps.applyFor(JSONObject().put("kind", kind).put("video_uri", uri).put("upi_name", upiName.trim()).put("upi_id", upiId.trim())) { next, err ->
                if (!alive) return@applyFor
                busy = false
                if (err != null) { formErr = PR.errText(err, "Could not send your application. Please try again."); showErr(); return@applyFor }
                val after = if (kind == "host") PR.hostStatus(next) else PR.creatorStatus(next)
                if (next != null && after != "pending") { formErr = "The application could not be saved yet. Please try again later."; showErr(); return@applyFor }
                ps.say("Application sent. Our team will check it soon.")
                video?.delete(); video = null
                step = "info"; render()
            }
        }
    }

    override fun previewOpen(what: String) { if (what == "form") { step = "form"; render() } }
}

@Suppress("unused") private fun keepVG(v: ViewGroup) = v
