package app.truefind.android

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject

/** Members (MembersTab.jsx): search everyone, filters, and phones used by many accounts. */
class AdMembers(a: AdminScreen, initialQ: String) : AdTab(a) {
    private var q = initialQ
    private var kind = "all"
    private var limit = 50
    private var remoteQ = ""
    private var remote: List<JSONObject> = emptyList()
    private var remoteBusy = false
    private val main = Handler(Looper.getMainLooper())
    private val listBox = AU.col(ctx)
    private val filterBox = FrameLayout(ctx)
    private var search: AdInput? = null
    private var shared: AdLoad? = null
    private var searchSeq = 0

    private val errBox = FrameLayout(ctx)

    override fun build() {
        root.removeAllViews()
        root.addView(errBox, AU.lp(-1, -2))
        // the search box (stays while the list below changes)
        val box = FrameLayout(ctx)
        val input = AU.input(ctx, "Search name, phone, email, city or id", q)
        input.setPadding(Ui.dpi(36f), 0, Ui.dpi(12f), 0)
        input.onText = { v -> q = v; limit = 50; startSearch(); renderList() }
        search = input
        box.addView(input, FrameLayout.LayoutParams(-1, Ui.dpi(42f)))
        box.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(AdIc.Search, AK.faint, 16f)) }, FrameLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = Ui.dpi(12f) })
        root.addView(box, AU.lp(-1, -2, b = 10f))
        root.addView(filterBox, AU.lp(-1, -2))
        root.addView(listBox, AU.lp(-1, -2))
        renderFilters()
        if (q.trim().length >= 2) startSearch()
        renderList()
    }

    override fun onShared() { if (alive) renderList() }
    override fun destroy() { super.destroy(); main.removeCallbacksAndMessages(null) }

    private fun renderFilters() {
        filterBox.removeAllViews()
        filterBox.addView(AU.filters(ctx, listOf(Triple("all", "All", 0), Triple("female", "Female", 0), Triple("male", "Male", 0), Triple("banned", "Banned", 0), Triple("phones", "Same phone", 0)), kind) {
            kind = it; limit = 50; renderFilters(); renderList()
        })
    }

    // Server search over ALL members (the list below holds the newest 500).
    private fun startSearch() {
        main.removeCallbacksAndMessages(null)
        val s = q.trim()
        if (s.length < 2) { remoteQ = ""; remote = emptyList(); remoteBusy = false; return }
        remoteBusy = true
        val my = ++searchSeq
        main.postDelayed({
            src.fn("adminUsers", JSONObject().put("action", "search").put("q", s)) { r ->
                if (!alive || my != searchSeq) return@fn
                remoteQ = s; remote = if (r.ok) AF.list(r.data, "results") else emptyList(); remoteBusy = false
                renderList()
            }
        }, 400)
    }

    private fun renderList() {
        if (!alive) return
        errBox.removeAllViews()
        if (a.profError.isNotEmpty()) errBox.addView(a.profilesErrorLine())
        listBox.removeAllViews()
        if (a.profLoading && a.profiles.isEmpty()) { listBox.addView(AU.loading(ctx)); return }
        if (kind == "phones") { sharedPhones(); return }
        val s = q.trim().lowercase()
        val list = a.profiles.filter { p ->
            if (kind == "female" && AF.s(p, "gender") != "female") return@filter false
            if (kind == "male" && AF.s(p, "gender") != "male") return@filter false
            if (kind == "banned" && !AF.b(p, "banned")) return@filter false
            if (s.isEmpty()) return@filter true
            listOf("full_name", "email", "phone", "city", "created_by_id").any { k -> AF.s(p, k).lowercase().contains(s) }
        }
        val localIds = list.map { AF.s(it, "created_by_id") }.toSet()
        val extra = remote.filter { r -> !localIds.contains(AF.s(r, "user_id")) && (kind != "banned" || AF.b(r, "banned")) && (kind == "all" || kind == "banned" || AF.s(r, "gender") == kind) }
        listBox.addView(AU.t(ctx, "${list.size} member${if (list.size == 1) "" else "s"}${if (a.capped) " (newest 500 + everyone waiting for a decision)" else ""}", 12f, AK.faint), AU.lp(-1, -2, l = 2f, r = 2f, b = 8f))
        if (list.isEmpty() && extra.isEmpty() && !remoteBusy) { listBox.addView(AU.empty(ctx, AdIc.Users, "No members found")); return }
        if (list.isNotEmpty()) listBox.addView(AU.listCard(ctx, list.take(limit).map { p -> memberRow(p) { a.openMember(AF.s(p, "created_by_id")) } }))
        if (list.size > limit) listBox.addView(AU.t(ctx, "Show more", 13.5f, AK.deep, true).apply {
            gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 12f, 1.5f, AK.line)
            setOnClickListener { limit += 50; renderList() }
        }, AU.lp(-1, Ui.dpi(42f), t = 10f))
        if (extra.isNotEmpty() || remoteBusy) {
            listBox.addView(AU.t(ctx, "FROM ALL MEMBERS${if (remoteBusy) " · SEARCHING…" else ""}", 12f, AK.faint, true).apply { letterSpacing = 0.5f / 12f }, AU.lp(-1, -2, l = 2f, t = 16f, r = 2f, b = 8f))
            if (extra.isNotEmpty()) listBox.addView(AU.listCard(ctx, extra.map { p -> memberRow(JSONObject(p.toString()).put("full_name", AF.s(p, "name")).put("created_date", AF.s(p, "joined"))) { a.openMember(AF.s(p, "user_id")) } }))
        }
    }

    private fun memberRow(p: JSONObject, onOpen: () -> Unit): LinearLayout = memberRow(this, p, onOpen)

    private fun sharedPhones() {
        val l = shared ?: AdLoad(this, "adminUsers", { JSONObject().put("action", "shared_devices") }) { if (kind == "phones") renderList() }.also { shared = it; it.load() }
        if (l.data == null && l.error.isEmpty()) { listBox.addView(AU.loading(ctx)); return }
        listBox.addView(AU.errorLine(ctx, l.error) { l.load() })
        listBox.addView(AU.t(ctx, "One phone with many accounts can mean fake profiles (a boy running \"girl\" accounts) or repeated new-user offers. Check them before you act.", 12.5f, AK.sub), AU.lp(-1, -2, b = 10f))
        val list = AF.list(l.data, "devices")
        if (list.isEmpty()) { listBox.addView(AU.empty(ctx, AdIc.Smartphone, "No shared phones", "Every phone has one account so far.")); return }
        list.forEachIndexed { i, g ->
            val accounts = AF.list(g, "accounts")
            val c = AU.card(ctx, 0f).apply { clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
            val h = AU.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)) }
            h.addView(AU.icon(ctx, AdIc.Smartphone, AK.red, 16f))
            h.addView(AU.t(ctx, "${accounts.size} accounts on one phone", 13.5f, AK.ink, true), AU.lp(0, -2, 1f, l = 8f, r = 8f))
            h.addView(AU.t(ctx, AF.fmt(AF.s(g, "last_seen")), 11.5f, AK.faint))
            c.addView(h)
            for (acc in accounts) {
                c.addView(AU.divider(ctx))
                c.addView(memberRow(JSONObject(acc.toString()).put("full_name", AF.s(acc, "name")).put("created_date", AF.s(acc, "joined"))) { a.openMember(AF.s(acc, "user_id")) })
            }
            listBox.addView(c, AU.lp(-1, -2, t = if (i > 0) 10f else 0f))
        }
    }

    override fun previewOpen(action: String) {
        when (action) {
            "phones" -> { kind = "phones"; renderFilters(); renderList() }
            "search" -> { search?.setText("Pri") }
        }
    }

    companion object {
        /** MemberRow: face, name with Banned / Creator / Host, details, phone · joined */
        fun memberRow(t: AdTab, p: JSONObject, onOpen: () -> Unit): LinearLayout {
            val ctx = t.ctx
            val r = AU.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)); setOnClickListener { onOpen() } }
            val img = AdImg(ctx, t.src, true, 42f, 42f)
            img.set(AdVerify.photoOf(p).ifEmpty { AF.s(p, "photo") })
            r.addView(img, LinearLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(42f)))
            val c = AU.col(ctx)
            val creator = PR.creatorStatus(p) == "approved" || AF.b(p, "creator")
            val host = PR.hostStatus(p) == "approved" || AF.b(p, "host")
            val pills = AU.row(ctx)
            if (AF.b(p, "banned")) pills.addView(AU.pill(ctx, "banned"), AU.lp(-2, -2, r = 6f))
            if (creator) pills.addView(AU.pill(ctx, "x", "Creator"), AU.lp(-2, -2, r = 6f))
            if (host) pills.addView(AU.pill(ctx, "x", "Host"), AU.lp(-2, -2, r = 6f))
            val top = AdNameRow(ctx, AU.t(ctx, AF.s(p, "full_name").ifEmpty { AF.s(p, "name") }.ifEmpty { "—" }, 14f, AK.ink, true).ell(), pills)
            c.addView(top)
            c.addView(AU.t(ctx, AdVerify.meta(p), 12f, AK.sub).ell())
            c.addView(AU.t(ctx, "${AF.s(p, "phone").ifEmpty { AF.s(p, "email") }.ifEmpty { "—" }} · joined ${AF.fmt(AF.s(p, "created_date").ifEmpty { AF.s(p, "joined") })}", 11.5f, AK.faint).ell())
            r.addView(c, AU.lp(0, -2, 1f, l = 12f))
            return r
        }
    }
}

/** MemberSheet.jsx: one member — actions, details, wallets, history, calls, reports, payouts, phones, admin actions. */
class AdMemberSheet(private val a: AdminScreen, private val userId: String, private val ticketId: String) {
    private val ctx = a.context
    private val src get() = a.src
    private var d: JSONObject? = null
    private var err = ""
    private lateinit var sheet: AdSheet

    companion object {
        val TX_LABEL = mapOf(
            "topup" to "Recharge", "call_spend" to "Call", "call_earn" to "Call earning", "chat_spend" to "Chat", "chat_earn" to "Chat earning", "chat_media" to "Chat photo",
            "chat_gift" to "Chat gift", "live_gift" to "Live gift", "live_cohost" to "Co-host", "live_earning" to "Live earning", "live_refund" to "Live refund",
            "dress_buy" to "Dress Code", "dress_earn" to "Dress Code earning", "game_entry" to "Game entry", "game_win" to "Game win", "game_earn" to "Game earning",
            "game_gift" to "Game gift", "game_gift_earn" to "Game gift earning", "admin_adjust" to "By admin", "admin_earn_adjust" to "Earnings by admin",
        )
        val SD_PLANS = listOf(Triple("daily", "Daily", "1 day · 3 credits"), Triple("starter", "Starter", "7 days · 5 credits"), Triple("pro", "Pro", "7 days · 8 credits"), Triple("elite", "Elite", "30 days · 10 credits"))
    }

    fun open() {
        sheet = AdSheet(a.layer, "Member")
        render()
        sheet.show()
        load()
    }

    private fun load() {
        err = ""
        src.fn("adminUsers", JSONObject().put("action", "user_detail").put("user_id", userId)) { r ->
            if (sheet.closed) return@fn
            if (r.ok) { d = r.data; err = "" } else err = AF.err(r, "Could not load this member.")
            render()
        }
    }

    /** after an action: this member again, and the Control Center's numbers */
    private fun changed() { load(); a.changed(members = true) }

    private fun render() {
        val b = sheet.body
        b.removeAllViews()
        b.addView(AU.errorLine(ctx, err) { load() })
        val d = d
        if (d == null) { if (err.isEmpty()) b.addView(AU.loading(ctx)); return }
        val c = AF.o(d, "card") ?: JSONObject()
        val user = AF.o(d, "user")
        val isAdmin = AF.s(user, "role") == "admin"
        val wallets = AF.list(d, "wallets")
        val tf = wallets.firstOrNull { AF.s(it, "app") == "truefind" }
        val sd = wallets.firstOrNull { AF.s(it, "app") == "secret" }

        // face, name, badges
        val top = AU.row(ctx)
        top.addView(AdImg(ctx, src, true, 64f, 64f).also { it.set(AF.s(c, "photo")); it.onOpen = { u -> a.zoom(u) } }, LinearLayout.LayoutParams(Ui.dpi(64f), Ui.dpi(64f)))
        val tc = AU.col(ctx)
        tc.addView(AU.t(ctx, AF.s(c, "name").ifEmpty { "—" }, 18f, AK.ink, true))
        tc.addView(AU.t(ctx, listOf(AF.s(c, "gender"), AF.s(c, "age").let { if (it.isNotEmpty()) "$it yrs" else "" }, AF.s(c, "city"), AF.s(c, "state")).filter { it.isNotEmpty() }.joinToString(" · "), 12.5f, AK.sub), AU.lp(-1, -2, t = 1f))
        val pills = FlowRow(ctx, Ui.dpi(5f), Ui.dpi(5f))
        if (AF.b(c, "banned")) pills.addView(AU.pill(ctx, "banned"))
        if (AF.b(c, "creator")) pills.addView(AU.pill(ctx, "x", "Creator"))
        if (AF.b(c, "host")) pills.addView(AU.pill(ctx, "x", "Host"))
        val w = AF.n(c, "warnings").toInt()
        if (w > 0) pills.addView(AU.pill(ctx, "x", "$w warning${if (w > 1) "s" else ""}", AK.amberBg, AK.amber))
        if (AF.b(c, "online")) pills.addView(AU.pill(ctx, "active", "Online")) else pills.addView(AU.pill(ctx, "x", "Seen ${AF.ago(AF.s(c, "last_active"))}", AK.grey, AK.sub))
        if (isAdmin) pills.addView(AU.pill(ctx, "x", "Admin", AK.black, Color.WHITE))
        tc.addView(pills, AU.lp(-1, -2, t = 5f))
        top.addView(tc, AU.lp(0, -2, 1f, l = 12f))
        b.addView(top)
        if (AF.b(c, "banned") && AF.s(c, "banned_reason").isNotEmpty()) b.addView(AU.warn(ctx, "Banned: ${AF.s(c, "banned_reason")}"), AU.lp(-1, -2, t = 10f))

        // what the admin can do
        val g = AdGrid(ctx, 130f, 8f)
        g.addView(AU.btn(ctx, "Coins", "dark", true, AdIc.Coins) { adjust(c) })
        g.addView(AU.btn(ctx, "Secret plan", "dark", true, AdIc.Crown) { secretPlan(c) })
        g.addView(AU.btn(ctx, "Message", "light", true, AdIc.MessageSquare) { message(c) })
        g.addView(AU.btn(ctx, "Warn", "light", true, AdIc.AlertTriangle) { warn(c) })
        if (AF.b(c, "banned")) g.addView(AU.btn(ctx, "Unban", "green", true, AdIc.RotateCcw) { unban(c) })
        else g.addView(AU.btn(ctx, "Ban", "danger", true, AdIc.Ban) { ban(c) }.also { it.off = isAdmin })
        if (AF.b(c, "creator")) g.addView(AU.btn(ctx, "Remove creator", "dangerLight", true, AdIc.ShieldOff) { uncreator(c) })
        if (!isAdmin) g.addView(AU.btn(ctx, "Delete account", "dangerLight", true, AdIc.Trash2) { delete(c) })
        b.addView(g, AU.lp(-1, -2, t = 14f))

        // details
        b.addView(AU.section(ctx, "Details"))
        val rt = AF.o(c, "rating")
        val rating = if (rt != null) "★ ${String.format(java.util.Locale.US, "%.1f", AF.n(rt, "avg"))} from ${AF.nf(AF.n(rt, "count"))} call${if (AF.n(rt, "count") > 1) "s" else ""}${if (AF.n(rt, "low") > 0) " · ${AF.nf(AF.n(rt, "low"))} low" else ""} · score ${AF.nf(AF.n(rt, "score"))}/100" else "No ratings yet"
        val counts = AF.o(d, "counts")
        val idRow = AU.row(ctx)
        idRow.addView(AU.t(ctx, "Member id", 13f, AK.sub), AU.lp(0, -2, 1f, r = 10f))
        idRow.addView(AU.t(ctx, AF.s(user, "id"), 12.5f, AK.deep, true).apply {
            compoundDrawablePadding = Ui.dpi(4f); setCompoundDrawablesWithIntrinsicBounds(null, null, Tf.lu(AdIc.Copy, AK.deep, 12f), null)
            setOnClickListener { AU.copy(ctx, AF.s(user, "id")); a.say("Copied") }
        })
        b.addView(AU.card(ctx).apply {
            addView(AU.rows(ctx, 3f,
                AU.kv(ctx, "Phone", AF.s(c, "phone")),
                AU.kv(ctx, "Email", AF.s(user, "email").ifEmpty { AF.s(c, "email") }),
                AU.kv(ctx, "Joined", AF.fmt(AF.s(c, "joined"))),
                AU.kv(ctx, "Last active", AF.fmt(AF.s(c, "last_active"))),
                AU.kv(ctx, "Call rating", rating),
                AU.kv(ctx, "Reports against", AF.nf(AF.n(counts, "reports_against"))),
                AU.kv(ctx, "Reports made", AF.nf(AF.n(counts, "reports_made"))),
                AU.kv(ctx, "Blocked by", "${AF.nf(AF.n(counts, "blocked_by"))} people"),
                idRow))
        })

        // wallets
        b.addView(AU.section(ctx, "Wallets"))
        val wg = AdGrid(ctx, 0f, 8f, 3)
        for ((l, v) in listOf("True Find coins" to AF.n(tf, "coins"), "Earnings (TF)" to AF.n(tf, "earned_coins"), "Secret Dating coins" to AF.n(sd, "coins"))) {
            wg.addView(AU.card(ctx, 10f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(AU.t(ctx, AF.coins(v), 18f, AK.ink, true).apply { gravity = Gravity.CENTER })
                addView(AU.t(ctx, l, 11.5f, AK.sub).apply { gravity = Gravity.CENTER })
            })
        }
        b.addView(wg)

        // same phone
        val same = AF.list(d, "same_device_accounts")
        if (same.isNotEmpty()) {
            b.addView(AU.section(ctx, "Same phone as ${same.size} other account${if (same.size > 1) "s" else ""}"))
            b.addView(AU.listCard(ctx, same.map { o ->
                AU.row(ctx).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
                    addView(AU.icon(ctx, AdIc.Smartphone, AK.red, 16f))
                    addView(AU.t(ctx, AF.s(o, "name").ifEmpty { AF.s(o, "user_id") }, 13.5f, AK.ink, true).ell(), AU.lp(0, -2, 1f, l = 10f, r = 10f))
                    if (AF.b(o, "banned")) addView(AU.pill(ctx, "banned"), AU.lp(-2, -2, r = 10f))
                    addView(AU.t(ctx, listOf(AF.s(o, "gender"), AF.s(o, "city")).filter { it.isNotEmpty() }.joinToString(" · "), 12f, AK.faint))
                    setOnClickListener { a.openMember(AF.s(o, "user_id")) }
                }
            }))
        }

        // wallet history
        b.addView(AU.section(ctx, "Wallet history"))
        val txs = AF.list(d, "transactions")
        if (txs.isEmpty()) b.addView(AU.card(ctx, 0f).apply { addView(AU.t(ctx, "No coin movements yet.", 13f, AK.sub).apply { setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) }) })
        else b.addView(AU.listCard(ctx, txs.take(50).map { t ->
            AU.row(ctx).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                val l = AU.col(ctx)
                l.addView(AU.t(ctx, "${TX_LABEL[AF.s(t, "type")] ?: AF.s(t, "type")}${if (AF.s(t, "app") == "secret") " · Secret" else ""}", 13f, AK.ink, true))
                l.addView(AU.t(ctx, AF.s(t, "description"), 11.5f, AK.sub).ell())
                addView(l, AU.lp(0, -2, 1f, r = 10f))
                val amt = AF.n(t, "amount")
                val rr = AU.col(ctx).apply { gravity = Gravity.END }
                rr.addView(AU.t(ctx, "${if (amt > 0) "+" else ""}${AF.coins(amt)}", 13.5f, if (amt < 0) AK.red else AK.green, true).apply { gravity = Gravity.END })
                rr.addView(AU.t(ctx, AF.fmt(AF.s(t, "created_date")), 11f, AK.faint).apply { gravity = Gravity.END })
                addView(rr)
            }
        }))

        // calls
        b.addView(AU.section(ctx, "Calls"))
        val calls = AF.list(d, "calls")
        if (calls.isEmpty()) b.addView(AU.card(ctx, 0f).apply { addView(AU.t(ctx, "No calls yet.", 13f, AK.sub).apply { setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) }) })
        else b.addView(AU.listCard(ctx, calls.map { cl ->
            AU.row(ctx).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                val caller = AF.s(cl, "role") == "caller"
                val l = AU.col(ctx)
                l.addView(AU.t(ctx, "${if (AF.s(cl, "type") == "video") "Video" else "Voice"} ${if (caller) "to" else "from"} ${AF.s(AF.o(cl, "other"), "name").ifEmpty { "someone" }}", 13f, AK.ink, true))
                val by = AF.s(cl, "ended_by")
                l.addView(AU.t(ctx, "${AF.fmt(AF.s(cl, "created_date"))} · ${AF.s(cl, "status")}${if (by.isNotEmpty()) " · ended by $by" else ""}", 11.5f, AK.sub))
                addView(l, AU.lp(0, -2, 1f, r = 10f))
                val rr = AU.col(ctx).apply { gravity = Gravity.END }
                rr.addView(AU.t(ctx, AF.dur(AF.n(cl, "duration_seconds")), 12f, AK.ink, true).apply { gravity = Gravity.END })
                rr.addView(AU.t(ctx, if (caller) "${AF.coins(AF.n(cl, "charged_coins"))} coins" else "+${AF.coins(AF.n(cl, "creator_earned"))} TF", 12f, AK.sub).apply { gravity = Gravity.END })
                addView(rr)
            }
        }))

        // reports against
        val reps = AF.list(d, "reports_against")
        if (reps.isNotEmpty()) {
            b.addView(AU.section(ctx, "Reports against"))
            b.addView(AU.listCard(ctx, reps.map { r ->
                AU.col(ctx).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                    val h = AU.row(ctx)
                    h.addView(AU.t(ctx, AF.s(r, "reason"), 13f, AK.ink, true), AU.lp(0, -2, 1f, r = 8f))
                    h.addView(AU.pill(ctx, AF.s(r, "status")))
                    addView(h)
                    addView(AU.t(ctx, AF.s(r, "details"), 12f, AK.sub), AU.lp(-1, -2, t = 2f))
                    val res = AF.s(r, "resolution")
                    addView(AU.t(ctx, "${AF.fmt(AF.s(r, "created_date"))}${if (res.isNotEmpty()) " · $res" else ""}", 11f, AK.faint), AU.lp(-1, -2, t = 2f))
                }
            }))
        }

        // withdrawals
        val pays = AF.list(d, "payouts")
        if (pays.isNotEmpty()) {
            b.addView(AU.section(ctx, "Withdrawals"))
            b.addView(AU.listCard(ctx, pays.map { p ->
                AU.row(ctx).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                    addView(AU.t(ctx, "₹${AF.nf(AF.n(p, "net"))} ${if (AF.s(p, "status") == "paid") "sent" else "not paid"}", 13f, AK.ink, true), AU.lp(0, -2, 1f, r = 8f))
                    addView(AU.t(ctx, AF.fmt(AF.s(p, "decided_at")), 13f, AK.sub))
                }
            }))
        }

        // phones used
        val devs = AF.list(d, "devices")
        if (devs.isNotEmpty()) {
            b.addView(AU.section(ctx, "Phones used"))
            b.addView(AU.listCard(ctx, devs.map { dv ->
                AU.col(ctx).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                    addView(AU.t(ctx, AF.s(dv, "user_agent").ifEmpty { "Unknown phone" }, 12.5f, AK.ink, true).ell())
                    val ip = AF.s(dv, "ip")
                    addView(AU.t(ctx, "Last seen ${AF.fmt(AF.s(dv, "last_seen"))}${if (ip.isNotEmpty()) " · $ip" else ""}", 11.5f, AK.sub))
                }
            }))
        }

        // admin actions
        val acts = AF.list(d, "admin_actions")
        if (acts.isNotEmpty()) {
            b.addView(AU.section(ctx, "Admin actions"))
            b.addView(AU.listCard(ctx, acts.map { x ->
                AU.col(ctx).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                    val amt = x.opt("amount")
                    val amount = if (amt != null && amt != JSONObject.NULL && (amt is Number || (amt is String && amt.toDoubleOrNull() != null))) " · ${amt}" else ""
                    addView(AU.t(ctx, "${AF.s(x, "action").replace("_", " ")}$amount", 13f, AK.ink, true))
                    addView(AU.t(ctx, AF.s(x, "details"), 11.5f, AK.sub))
                    addView(AU.t(ctx, "${AF.fmt(AF.s(x, "created_date"))} · ${AF.s(x, "admin_email")}", 11f, AK.faint))
                }
            }))
        }
    }

    private fun name(c: JSONObject) = AF.s(c, "name")
    private fun uid(c: JSONObject) = AF.s(c, "user_id").ifEmpty { userId }
    private fun seg(items: List<Pair<String, String>>, value: () -> String, set: (String) -> Unit): LinearLayout {
        val r = AU.row(ctx)
        fun paint() {
            for (i in 0 until r.childCount) {
                val v = r.getChildAt(i) as android.widget.TextView
                val on = items[i].first == value()
                v.background = Tf.box(if (on) AK.black else Color.WHITE, 10f, 1.5f, if (on) AK.black else AK.line)
                v.setTextColor(if (on) Color.WHITE else AK.sub)
            }
        }
        items.forEachIndexed { i, (k, l) ->
            r.addView(AU.t(ctx, l, 13f, AK.sub, true).apply { gravity = Gravity.CENTER; maxLines = 1; setOnClickListener { set(k); paint() } }, LinearLayout.LayoutParams(0, Ui.dpi(36f), 1f).apply { if (i > 0) leftMargin = Ui.dpi(6f) })
        }
        paint()
        r.layoutParams = AU.lp(-1, -2, b = 10f)
        return r
    }
    private fun switchRow(text: String, on: Boolean, set: (Boolean) -> Unit): LinearLayout {
        val r = AU.row(ctx)
        r.addView(AU.t(ctx, text, 13f, AK.sub), AU.lp(0, -2, 1f))
        r.addView(AdSwitch(ctx).also { s -> s.set(on, false); s.onChange = { v -> s.set(v); set(v) } })
        r.layoutParams = AU.lp(-1, -2, t = 12f, b = 12f)
        return r
    }

    // ───────── Coins (wallet_adjust) ─────────
    private fun adjust(c: JSONObject) {
        var app = "truefind"; var kind = "coins"; var sign = "1"; var notify = true
        val sh = AdSheet(a.layer, "Coins for ${name(c).ifEmpty { "member" }}")
        val b = sh.body
        val amount = AU.input(ctx, "50", type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        amount.filter { it.replace(Regex("[^\\d.]"), "").take(9) }
        val reason = AU.area(ctx, "Refund for a call that dropped", 2)
        val go = AU.btn(ctx, "Add coins", "dark")
        fun val0(): Double? = amount.value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        fun refresh() {
            val v = val0()
            val word = if (kind == "earned") "earnings" else "coins"
            val num = if (v != null) (if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()) else ""
            go.label(listOf(if (sign == "1") "Add" else "Take", num, word).filter { it.isNotEmpty() }.joinToString(" "))
            go.background = Tf.box(if (sign == "1") AK.black else AK.red, 12f)
            go.off = v == null || reason.value.trim().length < 5
        }
        b.addView(seg(listOf("truefind" to "True Find wallet", "secret" to "Secret Dating wallet"), { app }) { app = it; refresh() })
        b.addView(seg(listOf("coins" to "Coins", "earned" to "Earnings (TF currency)"), { kind }) { kind = it; refresh() })
        b.addView(seg(listOf("1" to "Add", "-1" to "Take"), { sign }) { sign = it; refresh() })
        b.addView(AU.label(ctx, "Amount", top = 4f))
        b.addView(amount, AU.lp(-1, -2))
        b.addView(AU.label(ctx, "Reason (saved in the history)", top = 12f))
        b.addView(reason, AU.lp(-1, -2))
        b.addView(switchRow("Tell the member (notification)", notify) { notify = it })
        amount.onText = { refresh() }; reason.onText = { refresh() }
        go.setOnClickListener {
            val v = val0() ?: return@setOnClickListener
            if (go.busy || go.off) return@setOnClickListener
            go.busy = true
            val amt = (if (sign == "1") 1 else -1) * Math.round(v * 100) / 100.0
            src.fn("adminUsers", JSONObject().put("action", "wallet_adjust").put("user_id", uid(c)).put("app", app).put("kind", kind).put("amount", amt).put("reason", reason.value.trim()).put("notify", notify)) { r ->
                go.busy = false
                if (r.ok) {
                    a.say("${if (sign == "1") "Added" else "Taken"} ${if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()} ${if (kind == "earned") "earnings" else "coins"} ✓")
                    sh.close(); changed()
                } else a.say(AF.err(r, "Could not change the wallet."), true)
            }
        }
        b.addView(go, AU.lp(-1, -2))
        b.addView(AU.t(ctx, "A wallet never goes below zero. Every change is saved in the audit log.", 12f, AK.faint), AU.lp(-1, -2, t = 8f))
        refresh()
        sh.show()
    }
    // ───────── free Secret Dating plan ─────────
    private fun secretPlan(c: JSONObject) {
        var plan = "elite"; var notify = true
        val sh = AdSheet(a.layer, "Secret Dating plan for ${name(c).ifEmpty { "member" }}")
        val b = sh.body
        val grid = AdGrid(ctx, 0f, 8f, 2)
        val cells = ArrayList<LinearLayout>()
        fun paint() {
            SD_PLANS.forEachIndexed { i, (k, _, _) ->
                val on = plan == k
                val cell = cells[i]
                cell.background = Tf.box(if (on) AK.black else Color.WHITE, 12f, 1.5f, if (on) AK.black else AK.line)
                (cell.getChildAt(0) as android.widget.TextView).setTextColor(if (on) Color.WHITE else AK.ink)
                (cell.getChildAt(1) as android.widget.TextView).setTextColor(if (on) Color.WHITE else AK.ink)
            }
        }
        for ((k, n, s) in SD_PLANS) {
            val cell = AU.col(ctx).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
                addView(AU.t(ctx, n, 14f, AK.ink, true))
                addView(AU.t(ctx, s, 12f, AK.ink).apply { alpha = 0.8f }, AU.lp(-1, -2, t = 2f))
                setOnClickListener { plan = k; paint() }
            }
            cells.add(cell); grid.addView(cell)
        }
        paint()
        b.addView(grid, AU.lp(-1, -2, b = 12f))
        b.addView(AU.label(ctx, "Reason (saved in the history)", top = 4f))
        val reason = AU.area(ctx, "Testing", 2, "Testing")
        b.addView(reason, AU.lp(-1, -2))
        b.addView(switchRow("Tell the member (notification)", notify) { notify = it })
        val go = AU.btn(ctx, "Give plan free", "dark", icon = AdIc.Crown, iconSize = 15f)
        reason.onText = { go.off = it.trim().length < 5 }
        go.setOnClickListener {
            if (go.busy || go.off) return@setOnClickListener
            go.busy = true
            src.fn("adminUsers", JSONObject().put("action", "grant_secret_plan").put("user_id", uid(c)).put("plan", plan).put("reason", reason.value.trim()).put("notify", notify)) { r ->
                go.busy = false
                if (r.ok) { val until = AF.s(r.data, "until"); a.say("Secret Dating plan added ✓${if (until.isNotEmpty()) " · till ${AF.fmt(until)}" else ""}"); sh.close(); changed() }
                else a.say(AF.err(r, "Could not give the plan."), true)
            }
        }
        b.addView(go, AU.lp(-1, -2))
        b.addView(AU.t(ctx, "Works like a bought plan (Premium + request credits; adds to a running plan). No money is recorded. After this the member no longer gets the ₹1 first-time offer.", 12f, AK.faint), AU.lp(-1, -2, t = 8f))
        sh.show()
    }

    // ───────── message (notification from True Find) ─────────
    private fun message(c: JSONObject) {
        val sh = AdSheet(a.layer, "Message to ${name(c).ifEmpty { "member" }}")
        val b = sh.body
        b.addView(AU.label(ctx, "Title"))
        val title = AU.input(ctx, "Please update your profile photo", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).also { it.filter { s -> s.take(120) } }
        b.addView(title, AU.lp(-1, -2))
        b.addView(AU.label(ctx, "Message", top = 12f))
        val text = AU.area(ctx, "", 3).also { it.filter { s -> s.take(500) } }
        b.addView(text, AU.lp(-1, -2))
        val go = AU.btn(ctx, "Send message", "dark").also { it.off = true }
        title.onText = { go.off = it.trim().length < 2 }
        go.setOnClickListener {
            if (go.busy || go.off) return@setOnClickListener
            go.busy = true
            src.fn("adminUsers", JSONObject().put("action", "notify").put("user_id", uid(c)).put("title", title.value.trim()).put("body", text.value.trim())) { r ->
                go.busy = false
                if (r.ok) { a.say("Message sent ✓"); sh.close() } else a.say(AF.err(r), true)
            }
        }
        b.addView(go, AU.lp(-1, -2, t = 12f))
        b.addView(AU.t(ctx, "It appears in the member's Notifications from \"True Find\".", 12f, AK.faint), AU.lp(-1, -2, t = 8f))
        sh.show()
    }

    private fun warn(c: JSONObject) {
        AdAsk(a, "Warn ${name(c).ifEmpty { "member" }}", "They get a warning in Notifications. Warnings are counted on their profile.",
            listOf("Rude or abusive language", "Asking for money", "Sharing contact details", "Fake photos", "Misbehaving on calls"), "Reason (they see this)", "Send warning") { r, done ->
            src.fn("adminUsers", JSONObject().put("action", "warn").put("user_id", uid(c)).put("reason", r)) { x ->
                if (x.ok) { a.say("Warning sent ✓"); done(true); changed() } else { a.say(AF.err(x), true); done(false) }
            }
        }.open()
    }
    private fun ban(c: JSONObject) {
        AdAsk(a, "Ban ${name(c).ifEmpty { "member" }}?", "They are blocked from the whole app at once. Their running call and live are ended and billed normally.",
            listOf("Fake profile", "Harassment", "Asking for money / scam", "Nudity or sexual content", "Underage"), "Reason (saved with the ban)", "Ban", true) { r, done ->
            src.fn("adminUsers", JSONObject().put("action", "ban").put("user_id", uid(c)).put("reason", r)) { x ->
                if (x.ok) {
                    val ce = AF.n(x.data, "calls_ended"); val re = AF.n(x.data, "rooms_ended")
                    a.say("Banned ✓${if (ce > 0) " · ${AF.nf(ce)} call ended" else ""}${if (re > 0) " · ${AF.nf(re)} live ended" else ""}")
                    done(true); changed()
                } else { a.say(AF.err(x), true); done(false) }
            }
        }.open()
    }
    private fun unban(c: JSONObject) {
        AdAsk(a, "Unban ${name(c).ifEmpty { "member" }}?", "They can use the app again and get a 'welcome back' message.", emptyList(), "Note (optional, for the audit log)", "Unban", false, 0) { r, done ->
            src.fn("adminUsers", JSONObject().put("action", "unban").put("user_id", uid(c)).put("reason", r)) { x ->
                if (x.ok) { a.say("Unbanned ✓"); done(true); changed() } else { a.say(AF.err(x), true); done(false) }
            }
        }.open()
    }
    private fun uncreator(c: JSONObject) {
        AdAsk(a, "Remove creator status?", "Her earnings and withdrawals stop. She is told the reason and can apply again.", emptyList(), "Reason (she sees this)", "Remove creator", true) { r, done ->
            src.fn("adminUsers", JSONObject().put("action", "remove_creator").put("user_id", uid(c)).put("reason", r)) { x ->
                if (x.ok) { a.say("Creator status removed"); done(true); changed() } else { a.say(AF.err(x), true); done(false) }
            }
        }.open()
    }

    // ───────── delete the account (on request) ─────────
    private fun delete(c: JSONObject) {
        val sh = AdSheet(a.layer, "Delete ${name(c).ifEmpty { "this" }} account?")
        val b = sh.body
        b.addView(AU.t(ctx, "Only do this after confirming it is really their account (for example, call or WhatsApp the number on the request). Their profile, photos, chats, coins and follows are removed for good. Payments, withdrawals, call records (without the name) and reports stay, as the law and safety need. A banned member keeps the ban.", 13.5f, AK.sub, false, 19f), AU.lp(-1, -2, b = 10f))
        b.addView(AU.label(ctx, "Type DELETE to confirm"))
        val typed = AU.input(ctx, "DELETE", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        typed.filter { it.uppercase().take(6) }
        b.addView(typed, AU.lp(-1, -2))
        val go = AU.btn(ctx, "Delete account", "danger", icon = AdIc.Trash2, iconSize = 15f).also { it.off = true }
        typed.onText = { go.off = it != "DELETE" }
        go.setOnClickListener {
            if (go.busy || go.off) return@setOnClickListener
            go.busy = true
            val body = JSONObject().put("action", "delete_account").put("user_id", uid(c)).put("confirm", "DELETE")
            if (ticketId.isNotEmpty()) body.put("ticket_id", ticketId)
            src.fn("adminUsers", body) { r ->
                go.busy = false
                if (r.ok) { a.say("Account deleted ✓"); sh.close(); a.changed(members = true); sheet.close() } else a.say(AF.err(r), true)
            }
        }
        b.addView(go, AU.lp(-1, -2, t = 12f))
        sh.show()
    }

}
