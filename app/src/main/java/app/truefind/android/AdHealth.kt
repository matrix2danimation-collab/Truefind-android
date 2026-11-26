package app.truefind.android

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * App Health (25 Nov): the Control Center's Health, Pages and Issues sections and their pop-ups, from what
 * members' phones report (AppHealth.kt) and the app's own records — server function adminHealth.
 * Never a message's words: only counts and problem details.
 */
object AH {
    val RANGES = listOf(Triple("today", "Today", 0), Triple("yesterday", "Yesterday", 0), Triple("week", "7 days", 0), Triple("30d", "30 days", 0))
    val TITLE = mapOf(
        "call_failed" to "Call could not connect", "call_start_failed" to "Call could not start (server)", "call_dropped" to "Call dropped mid-call",
        "call_no_audio" to "No sound on a call", "call_no_video" to "No picture on a video call", "call_billing_failed" to "Call bill not settled",
        "msg_failed" to "Message did not send", "msg_slow" to "Message slow to send",
        "payment_stuck" to "Payment stuck", "payment_not_added" to "Paid, but not added", "payment_failed" to "Payment failed", "payment_start_failed" to "Payment could not start",
        "crash" to "App crashed", "crash_native" to "App crashed (phone code)", "crash_memory" to "Closed for low memory",
        "anr" to "App froze and was closed", "hang" to "App froze", "page_slow" to "Page very slow to open",
        "server_error" to "Server error", "server_down" to "Server could not be reached", "server_timeout" to "Server timed out", "server_slow" to "Server slow to answer",
        "no_internet" to "No internet",
    )
    fun title(kind: String): String = TITLE[kind] ?: kind.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** (icon, background, colour) of a problem area */
    fun look(area: String): Triple<String, Int, Int> = when (area) {
        "call" -> Triple(AdIc.PhoneOff, AK.redBg, AK.red)
        "message" -> Triple(AdIc.MessageSquare, AK.amberBg, AK.amber)
        "payment" -> Triple(AdIc.CreditCard, AK.redBg, AK.red)
        "crash" -> Triple(AdIc.Bug, AK.redBg, AK.red)
        "hang" -> Triple(AdIc.Timer, AK.amberBg, AK.amber)
        "page" -> Triple(AdIc.Timer, AK.amberBg, AK.amber)
        "server" -> Triple(AdIc.Server, AK.blueBg, AK.blue)
        "network" -> Triple(AdIc.WifiOff, AK.grey, AK.sub)
        else -> Triple(AdIc.AlertTriangle, AK.grey, AK.sub)
    }
    /** the Issues section's area filters: key → areas */
    val AREAS = listOf(
        Triple("all", "All", emptyList()), Triple("call", "Calls", listOf("call")), Triple("message", "Messages", listOf("message")),
        Triple("payment", "Payments", listOf("payment")), Triple("crash", "Crashes & freezes", listOf("crash", "hang")),
        Triple("page", "Slow pages", listOf("page")), Triple("server", "Server & internet", listOf("server", "network")),
    )

    /** "Mobile data · Jio", "Wi-Fi", or "" */
    fun net(network: String, operator: String): String = when (network) {
        "wifi" -> if (operator.isNotEmpty()) "Wi-Fi ($operator SIM)" else "Wi-Fi"
        "mobile" -> if (operator.isNotEmpty()) "$operator mobile data" else "Mobile data"
        "none" -> "No network"
        "" -> operator
        else -> listOf(network, operator).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    /** seconds → "2h 05m", "12m", "45s" */
    fun time(sec: Double): String {
        val s = Math.max(0L, Math.round(sec))
        val h = s / 3600; val m = (s % 3600) / 60
        return when { h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"; m > 0 -> "${m}m"; else -> "${s}s" }
    }
    /** ms → "0.4 s" */
    fun secs(ms: Double): String = if (ms <= 0) "—" else String.format(Locale.US, "%.1f s", ms / 1000.0)
    fun rupees(v: Double): String = "₹" + AF.loc(v, 2)
    /** "2026-11-25" → "Wed 25 Nov" */
    fun day(d: String): String = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }
        java.text.SimpleDateFormat("EEE d MMM", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }.format(f.parse(d)!!)
    } catch (e: Exception) { d }
    /** the phone line of a problem / member: "Xiaomi Redmi Note 12 · Android 13 · app 0.1.250 · Jio mobile data" */
    fun phone(o: JSONObject?): String = listOf(
        AF.s(o, "device"), AF.s(o, "android").let { if (it.isNotEmpty()) "Android ${it.substringBefore(" (")}" else "" },
        AF.s(o, "app_version").let { if (it.isNotEmpty()) "app $it" else "" }, net(AF.s(o, "network"), AF.s(o, "operator")),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    /** a number row (label · value), the value red when it is a problem above 0 */
    fun line(ctx: android.content.Context, label: String, value: Double, bad: Boolean = false, text: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val r = AU.row(ctx)
        r.setPadding(0, Ui.dpi(5f), 0, Ui.dpi(5f))
        r.addView(AU.t(ctx, label, 13.5f, AK.sub), AU.lp(0, -2, 1f, r = 10f))
        val col = if (bad && value > 0) AK.red else AK.ink
        r.addView(AU.t(ctx, text ?: AF.nf(value), 14f, col, true))
        if (onClick != null) {
            r.addView(AU.icon(ctx, AdIc.ChevronRight, AK.faint, 14f), AU.lp(Ui.dpi(14f), Ui.dpi(14f), l = 6f))
            r.setOnClickListener { onClick() }
        }
        return r
    }

    /** one problem row (Issues list, member sheet, page sheet) */
    fun issueRow(ctx: android.content.Context, a: AdminScreen, i: JSONObject, person: JSONObject?, showPerson: Boolean, onOpen: () -> Unit): LinearLayout {
        val (ic, bg, fg) = look(AF.s(i, "area"))
        val r = AU.row(ctx, Gravity.TOP)
        r.setPadding(Ui.dpi(12f), Ui.dpi(11f), Ui.dpi(12f), Ui.dpi(11f))
        r.addView(AU.tile(ctx, ic, bg, fg, 34f, 10f, 17f))
        val c = AU.col(ctx)
        val n = AF.n(i, "count")
        val top = AU.row(ctx)
        top.addView(AU.t(ctx, title(AF.s(i, "kind")), 13.5f, AK.ink, true).ell(), AU.lp(0, -2, 1f))
        if (n > 1) top.addView(AU.badge(ctx, "×${AF.nf(n)}", AK.redBg, AK.red), AU.lp(-2, Ui.dpi(18f), l = 6f))
        c.addView(top)
        val who = if (showPerson) {
            val nm = AF.s(person, "name")
            if (AF.s(i, "source") == "server" && AF.s(i, "user_id").isEmpty()) "Server" else nm.ifEmpty { if (AF.s(i, "user_id").isEmpty()) "Unknown member" else "Member" }
        } else ""
        val detail = AF.s(i, "detail")
        if (detail.isNotEmpty()) c.addView(AU.t(ctx, detail, 12.5f, AK.ink).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, AU.lp(-1, -2, t = 2f))
        val sub = listOf(who, AF.s(i, "page").let { if (it.isNotEmpty()) "on $it" else "" }, phone(i), AF.ago(AF.s(i, "last_at"))).filter { it.isNotEmpty() }.joinToString(" · ")
        c.addView(AU.t(ctx, sub, 11.5f, AK.faint).apply { maxLines = 2 }, AU.lp(-1, -2, t = 2f))
        r.addView(c, AU.lp(0, -2, 1f, l = 10f, r = 6f))
        if (AF.s(i, "status") == "resolved") r.addView(AU.pill(ctx, "resolved", "Fixed"))
        r.setOnClickListener { onOpen() }
        return r
    }
}

/** Health: today / yesterday / 7 / 30 days at a glance — members, money, calls, messages, crashes, hangs, server — biggest problems first. */
class AdHealth(a: AdminScreen) : AdTab(a) {
    private var range = "today"
    private val load = AdLoad(this, "adminHealth", { JSONObject().put("action", "health").put("range", range) }) { render() }

    override fun build() { render(); load.load() }
    override fun onVisible(v: Boolean) { if (v && alive) load.load() }

    private fun kpi(icon: String, label: String, value: String, note: String? = null): LinearLayout {
        val c = AU.card(ctx, 12f)
        val h = AU.row(ctx)
        h.addView(AU.icon(ctx, icon, AK.purple, 14f))
        h.addView(AU.t(ctx, label, 12f, AK.sub, true), AU.lp(-2, -2, l = 6f))
        c.addView(h)
        c.addView(AU.t(ctx, value, 22f, AK.ink, true, 26f), AU.lp(-1, -2, t = 6f))
        if (!note.isNullOrEmpty()) c.addView(AU.t(ctx, note, 11.5f, AK.faint), AU.lp(-1, -2, t = 2f))
        return c
    }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        root.addView(AU.filters(ctx, AH.RANGES, range) { range = it; render(); load.load() })
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        if (d == null) return
        val when_ = AF.s(AF.o(d, "range"), "when").ifEmpty { "today" }
        val u = AF.o(d, "users"); val m = AF.o(d, "money"); val c = AF.o(d, "calls"); val msg = AF.o(d, "messages")
        val pr = AF.o(d, "problems") ?: JSONObject(); val pg = AF.o(d, "pages")
        fun p(k: String) = AF.n(pr, k)

        // the biggest problems, in plain words
        root.addView(AU.section(ctx, "Biggest problems $when_"))
        val top = AF.list(d, "top")
        if (top.isEmpty()) {
            val cc = AU.card(ctx)
            val r = AU.row(ctx)
            r.addView(AU.icon(ctx, AdIc.CheckCircle2, AK.green, 20f))
            r.addView(AU.t(ctx, "No problems reported $when_ — all clear.", 14f, AK.ink, true), AU.lp(0, -2, 1f, l = 10f))
            cc.addView(r)
            root.addView(cc)
        } else {
            root.addView(AU.listCard(ctx, top.take(8).map { g ->
                val (ic, bg, fg) = AH.look(AF.s(g, "area"))
                AU.row(ctx, Gravity.TOP).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
                    addView(AU.tile(ctx, ic, bg, fg))
                    val tx = AU.col(ctx)
                    tx.addView(AU.t(ctx, AF.s(g, "line").replaceFirstChar { it.uppercase() }, 14f, AK.ink, true, 19f))
                    val h = AF.s(g, "hint")
                    if (h.isNotEmpty()) tx.addView(AU.t(ctx, h, 12f, AK.sub, false, 16f), AU.lp(-1, -2, t = 2f))
                    addView(tx, AU.lp(0, -2, 1f, l = 10f, r = 8f))
                    addView(AU.icon(ctx, AdIc.ChevronRight, AK.faint, 16f), AU.lp(Ui.dpi(16f), Ui.dpi(16f), t = 9f))
                    setOnClickListener { a.openIssues(kind = AF.s(g, "kind"), range = range) }
                }
            }))
        }

        // members and money
        root.addView(AU.section(ctx, "Members & money"))
        val g = AdGrid(ctx, 150f, 10f)
        val withUsage = AF.n(u, "with_usage")
        g.addView(kpi(AdIc.Users, "Active members", AF.nf(AF.n(u, "active")), "${AF.nf(AF.n(u, "opens"))} app opens · ${AF.nf(AF.n(u, "total"))} members in all"))
        g.addView(kpi(AdIc.UserPlus, "New members", AF.nf(AF.n(u, "new")), when_))
        g.addView(kpi(AdIc.IndianRupee, "Money paid", AH.rupees(AF.n(m, "revenue_inr")), "${AF.nf(AF.n(m, "paid"))} payments · ${AF.nf(AF.n(m, "buyers"))} buyers"))
        g.addView(kpi(AdIc.Clock, "Time in the app", AH.time(AF.n(u, "seconds")), if (withUsage > 0) "about ${AH.time(AF.n(u, "seconds") / withUsage)} per member" else "no app-use numbers yet"))
        root.addView(g, AU.lp(-1, -2))

        // calls
        root.addView(AU.section(ctx, "Calls"))
        val total = AF.n(c, "total"); val conn = AF.n(c, "connected")
        val callProblems = p("call_failed") + p("call_start_failed") + p("call_dropped") + p("call_no_audio") + p("call_no_video")
        val cc = AU.card(ctx)
        cc.addView(bar(conn, callProblems, total))
        cc.addView(AU.rows(ctx, 0f,
            AH.line(ctx, "Calls made (voice ${AF.nf(AF.n(c, "voice"))} · video ${AF.nf(AF.n(c, "video"))})", total),
            AH.line(ctx, "Connected", conn, text = "${AF.nf(conn)} · ${AF.loc(AF.n(c, "minutes"), 1)} min"),
            AH.line(ctx, "Not answered", AF.n(c, "not_answered")),
            AH.line(ctx, "Declined", AF.n(c, "declined")),
            AH.line(ctx, "Could not connect", p("call_failed") + p("call_start_failed"), true) { a.openIssues(areas = "call", range = range) },
            AH.line(ctx, "Dropped mid-call", p("call_dropped"), true) { a.openIssues(kind = "call_dropped", range = range) },
            AH.line(ctx, "No sound", p("call_no_audio"), true) { a.openIssues(kind = "call_no_audio", range = range) },
            AH.line(ctx, "No picture (video)", p("call_no_video"), true) { a.openIssues(kind = "call_no_video", range = range) },
            AH.line(ctx, "Bill not settled", Math.max(p("call_billing_failed"), AF.n(c, "billing_problems")), true) { a.setTab("live") },
        ), AU.lp(-1, -2, t = 8f))
        root.addView(cc)

        // messages
        root.addView(AU.section(ctx, "Messages"))
        root.addView(AU.card(ctx).apply {
            addView(AU.rows(ctx, 0f,
                AH.line(ctx, "Sent (by ${AF.nf(AF.n(msg, "senders"))} members)", AF.n(msg, "sent")),
                AH.line(ctx, "Did not send", p("msg_failed"), true) { a.openIssues(kind = "msg_failed", range = range) },
                AH.line(ctx, "Slow to send (over 5 s)", p("msg_slow"), true) { a.openIssues(kind = "msg_slow", range = range) },
            ))
            addView(AU.t(ctx, "Only counted — the words are never read or kept.", 11.5f, AK.faint), AU.lp(-1, -2, t = 4f))
        })

        // payments
        root.addView(AU.section(ctx, "Payments (Google Play)"))
        root.addView(AU.card(ctx).apply {
            val rows = mutableListOf(
                AH.line(ctx, "Paid", AF.n(m, "paid"), text = "${AF.nf(AF.n(m, "paid"))} · ${AH.rupees(AF.n(m, "revenue_inr"))}"),
                AH.line(ctx, "Waiting (pending)", AF.n(m, "pending")),
                AH.line(ctx, "Stuck over 15 min", p("payment_stuck").coerceAtLeast(AF.n(m, "stuck")), true) { a.openIssues(kind = "payment_stuck", range = range) },
                AH.line(ctx, "Paid but not added", p("payment_not_added"), true) { a.openIssues(kind = "payment_not_added", range = range) },
                AH.line(ctx, "Failed", AF.n(m, "failed").coerceAtLeast(p("payment_failed")), true) { a.openIssues(areas = "payment", range = range) },
                AH.line(ctx, "Refunded", AF.n(m, "refunded"), text = "${AF.nf(AF.n(m, "refunded"))} · ${AH.rupees(AF.n(m, "refunded_inr"))}"),
            )
            // Google Play licence testers' buys: no money, so kept out of "Paid"
            if (AF.n(m, "test_paid") > 0) rows.add(1, AH.line(ctx, "Test purchases (no money)", AF.n(m, "test_paid")))
            addView(AU.rows(ctx, 0f, *rows.toTypedArray()))
        })

        // the app itself
        root.addView(AU.section(ctx, "App: crashes, freezes, slow pages"))
        root.addView(AU.card(ctx).apply {
            addView(AU.rows(ctx, 0f,
                AH.line(ctx, "Crashes", p("crash") + p("crash_native") + p("crash_memory"), true) { a.openIssues(areas = "crash", range = range) },
                AH.line(ctx, "Froze and closed by Android", p("anr"), true) { a.openIssues(kind = "anr", range = range) },
                AH.line(ctx, "Froze for a few seconds", p("hang"), true) { a.openIssues(kind = "hang", range = range) },
                AH.line(ctx, "Slow page opens (over 1.2 s)", AF.n(pg, "slow_opens"), true) { a.setTab("pages") },
                AH.line(ctx, "Very slow pages (over 3 s)", p("page_slow"), true) { a.openIssues(kind = "page_slow", range = range) },
            ))
            val slow = AF.list(d, "slow_pages")
            if (slow.isNotEmpty()) addView(AU.t(ctx, "Slowest: " + slow.take(3).joinToString(" · ") { "${AF.s(it, "page")} (${AF.nf(AF.n(it, "slow"))} slow${if (AF.n(it, "hangs") > 0) ", ${AF.nf(AF.n(it, "hangs"))} froze" else ""})" }, 12f, AK.sub), AU.lp(-1, -2, t = 6f))
        })

        // server and internet
        root.addView(AU.section(ctx, "Server & internet"))
        root.addView(AU.card(ctx).apply {
            addView(AU.rows(ctx, 0f,
                AH.line(ctx, "Server errors", p("server_error"), true) { a.openIssues(kind = "server_error", range = range) },
                AH.line(ctx, "Server could not be reached", p("server_down"), true) { a.openIssues(kind = "server_down", range = range) },
                AH.line(ctx, "Timed out", p("server_timeout"), true) { a.openIssues(kind = "server_timeout", range = range) },
                AH.line(ctx, "Slow answers (over 10 s)", p("server_slow"), true) { a.openIssues(kind = "server_slow", range = range) },
                AH.line(ctx, "No internet on members' phones", p("no_internet")) { a.openIssues(kind = "no_internet", range = range) },
            ))
        })

        // day by day
        val days = AF.list(d, "days")
        if (days.size > 1) {
            root.addView(AU.section(ctx, "Day by day"))
            val head = AU.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f)) }
            for ((t, w) in listOf("Day" to 1.5f, "Active" to 1f, "New" to 0.8f, "Paid" to 1.2f, "Calls" to 1.3f, "Issues" to 1f)) head.addView(AU.t(ctx, t, 11.5f, AK.faint, true), AU.lp(0, -2, w))
            val rows = ArrayList<View>()
            rows.add(head)
            for (x in days.reversed()) {
                val r = AU.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f)) }
                val probs = AF.n(x, "call_problems") + AF.n(x, "crashes")
                for ((t, w, col) in listOf(
                    Triple(AH.day(AF.s(x, "day")), 1.5f, AK.ink), Triple(AF.nf(AF.n(x, "active")), 1f, AK.ink), Triple(AF.nf(AF.n(x, "new")), 0.8f, AK.ink),
                    Triple(AH.rupees(AF.n(x, "revenue_inr")), 1.2f, AK.ink), Triple("${AF.nf(AF.n(x, "connected"))}/${AF.nf(AF.n(x, "calls"))}", 1.3f, AK.ink),
                    Triple(AF.nf(probs), 1f, if (probs > 0) AK.red else AK.ink))) r.addView(AU.t(ctx, t, 12.5f, col, true).ell(), AU.lp(0, -2, w))
                rows.add(r)
            }
            root.addView(AU.listCard(ctx, rows))
            root.addView(AU.t(ctx, "Calls: connected / made · Issues: call problems + crashes", 11.5f, AK.faint), AU.lp(-1, -2, l = 2f, t = 6f))
        }

        // alerts to the owner's phone
        root.addView(AU.section(ctx, "Alerts to your phone"))
        val al = AF.o(d, "alerts")
        val ac = AU.card(ctx) { AdAlertsSheet(a).open() }
        val ar = AU.row(ctx)
        ar.addView(AU.tile(ctx, AdIc.Bell, if (AF.b(al, "enabled")) AK.tint else AK.grey, if (AF.b(al, "enabled")) AK.deep else AK.sub))
        val at = AU.col(ctx)
        at.addView(AU.t(ctx, if (AF.b(al, "enabled")) "On — your phone rings when many things fail at once" else "Off — no alerts are sent", 14f, AK.ink, true))
        val last = AF.list(al, "last").firstOrNull()
        at.addView(AU.t(ctx, if (last != null) "Last: ${AF.s(last, "title")} · ${AF.ago(AF.s(last, "created_date"))}" else "No alert sent yet", 12f, AK.sub).ell(), AU.lp(-1, -2, t = 2f))
        ar.addView(at, AU.lp(0, -2, 1f, l = 10f, r = 10f))
        ar.addView(AU.icon(ctx, AdIc.ChevronRight, AK.faint, 16f))
        ac.addView(ar)
        if (al != null && !AF.b(al, "push_ready")) ac.addView(AU.warn(ctx, "Notifications are not set up on the server — alerts can't reach a phone yet.", "amber"), AU.lp(-1, -2, t = 10f))
        root.addView(ac)
        root.addView(AU.t(ctx, "Numbers come from members' phones (app use, pages, problems) and from the app's own records (calls, payments, messages counted). Phones on older app versions don't send app-use numbers.", 11.5f, AK.faint, false, 16f), AU.lp(-1, -2, l = 2f, t = 12f, r = 2f))
    }

    /** a thin bar: connected (green) · problems (red) · the rest (grey) */
    private fun bar(ok: Double, bad: Double, total: Double): View {
        val w = AU.col(ctx)
        val r = AU.row(ctx)
        val t = Math.max(total, ok + bad).coerceAtLeast(1.0)
        val parts = listOf(ok to AK.green, bad to AK.red, (t - ok - bad).coerceAtLeast(0.0) to AK.line)
        for ((v, col) in parts) if (v > 0) r.addView(View(ctx).apply { background = Tf.box(col, 3f) }, LinearLayout.LayoutParams(0, Ui.dpi(8f), v.toFloat()).apply { rightMargin = Ui.dpi(2f) })
        if (total <= 0) r.addView(View(ctx).apply { background = Tf.box(AK.line, 3f) }, LinearLayout.LayoutParams(0, Ui.dpi(8f), 1f))
        w.addView(r, AU.lp(-1, -2))
        val pct = if (total > 0) Math.round(ok * 100 / total) else 0
        w.addView(AU.t(ctx, if (total > 0) "$pct% of calls connected · ${AF.nf(bad)} with a problem" else "No calls yet", 12f, AK.sub), AU.lp(-1, -2, t = 5f))
        return w
    }

    override fun previewOpen(action: String) {
        when (action) {
            "week" -> { range = "week"; render(); load.load() }
            "alerts" -> AdAlertsSheet(a).open()
        }
    }
}

/** Pages: every page — opens, members, time, slow opens, hangs — with the phones they were slow on. */
class AdPages(a: AdminScreen) : AdTab(a) {
    private var range = "today"
    private var sort = "opens"
    private val load = AdLoad(this, "adminHealth", { JSONObject().put("action", "pages").put("range", range) }) { render() }

    override fun build() { render(); load.load() }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        root.addView(AU.filters(ctx, AH.RANGES, range) { range = it; render(); load.load() })
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        val list = AF.list(d, "pages")
        if (list.isEmpty()) {
            root.addView(AU.empty(ctx, AdIc.LayoutList, "No page numbers yet", "They come from members' phones on the new app version, about every 5 minutes."))
            return
        }
        fun visitSecs(p: JSONObject) = if (AF.n(p, "opens") > 0) AF.n(p, "seconds") / AF.n(p, "opens") else 0.0
        val most = list.maxByOrNull { AF.n(it, "opens") }!!
        val least = list.minByOrNull { AF.n(it, "opens") }!!
        val longest = list.maxByOrNull { visitSecs(it) }!!
        val slowest = list.filter { AF.n(it, "avg_open_ms") > 0 }.maxByOrNull { AF.n(it, "avg_open_ms") }

        root.addView(AU.t(ctx, "${AF.nf(AF.n(d, "members"))} members · ${list.size} pages", 12.5f, AK.sub), AU.lp(-1, -2, l = 2f, b = 8f))
        val g = AdGrid(ctx, 150f, 10f)
        fun stat(icon: String, label: String, page: JSONObject?, value: String) = AU.card(ctx, 12f) { if (page != null) AdPageSheet(a, AF.s(page, "page"), range).open() }.apply {
            val h = AU.row(ctx); h.addView(AU.icon(ctx, icon, AK.purple, 14f)); h.addView(AU.t(ctx, label, 12f, AK.sub, true), AU.lp(-2, -2, l = 6f)); addView(h)
            addView(AU.t(ctx, AF.s(page, "page").ifEmpty { "—" }, 16f, AK.ink, true).ell(), AU.lp(-1, -2, t = 6f))
            addView(AU.t(ctx, value, 11.5f, AK.faint), AU.lp(-1, -2, t = 2f))
        }
        g.addView(stat(AdIc.Eye, "Most opened", most, "${AF.nf(AF.n(most, "opens"))} opens"))
        g.addView(stat(AdIc.Eye, "Least opened", least, "${AF.nf(AF.n(least, "opens"))} opens"))
        g.addView(stat(AdIc.Clock, "Longest stay", longest, "about ${AH.time(visitSecs(longest))} a visit"))
        g.addView(stat(AdIc.Timer, "Slowest to appear", slowest, if (slowest != null) "${AH.secs(AF.n(slowest, "avg_open_ms"))} on average" else "—"))
        root.addView(g, AU.lp(-1, -2, b = 6f))

        root.addView(AU.filters(ctx, listOf(Triple("opens", "Most opened", 0), Triple("time", "Most time", 0), Triple("slow", "Slowest", 0), Triple("problems", "Problems", 0)), sort) { sort = it; render() })
        val sorted = when (sort) {
            "time" -> list.sortedByDescending { AF.n(it, "seconds") }
            "slow" -> list.sortedByDescending { AF.n(it, "avg_open_ms") }
            "problems" -> list.sortedByDescending { AF.n(it, "slow") + AF.n(it, "hangs") * 3 }
            else -> list
        }
        root.addView(AU.listCard(ctx, sorted.map { p -> row(p, visitSecs(p)) }))
        root.addView(AU.t(ctx, "Slow = took over 1.2 s to appear after the tap · Froze = the app stopped for 2.5 s or more on that page.", 11.5f, AK.faint, false, 16f), AU.lp(-1, -2, l = 2f, t = 8f, r = 2f))
    }

    private fun row(p: JSONObject, visit: Double): LinearLayout = AU.row(ctx).apply {
        setPadding(Ui.dpi(12f), Ui.dpi(11f), Ui.dpi(12f), Ui.dpi(11f))
        val c = AU.col(ctx)
        c.addView(AU.t(ctx, AF.s(p, "page"), 14f, AK.ink, true).ell())
        c.addView(AU.t(ctx, "${AF.nf(AF.n(p, "opens"))} opens · ${AF.nf(AF.n(p, "members"))} members · ${AH.time(AF.n(p, "seconds"))} in all · ~${AH.time(visit)} a visit", 12f, AK.sub), AU.lp(-1, -2, t = 2f))
        val bad = AF.list(p, "bad_devices")
        if (bad.isNotEmpty()) c.addView(AU.t(ctx, "Slow / froze on: " + bad.joinToString(", ") { AF.s(it, "device") }, 11.5f, AK.amber).ell(), AU.lp(-1, -2, t = 2f))
        addView(c, AU.lp(0, -2, 1f, r = 8f))
        val right = AU.col(ctx).apply { gravity = Gravity.END }
        right.addView(AU.t(ctx, AH.secs(AF.n(p, "avg_open_ms")), 12.5f, if (AF.n(p, "avg_open_ms") >= AppHealth.SLOW_OPEN_MS) AK.red else AK.ink, true).apply { gravity = Gravity.END })
        val chips = AU.row(ctx)
        if (AF.n(p, "slow") > 0) chips.addView(AU.badge(ctx, "${AF.nf(AF.n(p, "slow"))} slow", AK.amberBg, AK.amber), AU.lp(-2, Ui.dpi(18f), t = 3f))
        if (AF.n(p, "hangs") > 0) chips.addView(AU.badge(ctx, "${AF.nf(AF.n(p, "hangs"))} froze", AK.redBg, AK.red), AU.lp(-2, Ui.dpi(18f), l = 4f, t = 3f))
        right.addView(chips)
        addView(right)
        setOnClickListener { AdPageSheet(a, AF.s(p, "page"), range).open() }
    }

    override fun previewOpen(action: String) {
        if (action == "page") AF.list(load.data, "pages").firstOrNull()?.let { AdPageSheet(a, AF.s(it, "page"), range).open() }
        if (action == "week") { range = "week"; render(); load.load() }
    }
}

/** One page: by phone, the members who use it most, day by day, and its problems. */
class AdPageSheet(private val a: AdminScreen, private val page: String, private val range: String) {
    private val ctx = a.context
    private lateinit var sheet: AdSheet
    private var d: JSONObject? = null
    private var err = ""

    fun open() {
        sheet = AdSheet(a.layer, page)
        render(); sheet.show(); load()
    }
    private fun load() {
        a.src.fn("adminHealth", JSONObject().put("action", "page_detail").put("page", page).put("range", range)) { r ->
            if (sheet.closed) return@fn
            if (r.ok) { d = r.data; err = "" } else err = AF.err(r, "Could not load this page's numbers.")
            render()
        }
    }
    private fun render() {
        val b = sheet.body
        b.removeAllViews()
        b.addView(AU.errorLine(ctx, err) { load() })
        val d = d ?: run { if (err.isEmpty()) b.addView(AU.loading(ctx)); return }
        b.addView(AU.t(ctx, AH.RANGES.firstOrNull { it.first == range }?.second ?: "", 12.5f, AK.sub))
        val people = AF.o(d, "people")

        b.addView(AU.section(ctx, "By phone"))
        val devs = AF.list(d, "devices")
        if (devs.isEmpty()) b.addView(AU.t(ctx, "No numbers yet.", 13f, AK.sub))
        else b.addView(AU.listCard(ctx, devs.map { x ->
            AU.row(ctx).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                addView(AU.icon(ctx, AdIc.Smartphone, AK.purple, 16f))
                val c = AU.col(ctx)
                c.addView(AU.t(ctx, AF.s(x, "device"), 13.5f, AK.ink, true).ell())
                c.addView(AU.t(ctx, "${AF.nf(AF.n(x, "opens"))} opens · ${AF.nf(AF.n(x, "members"))} members · appears in ${AH.secs(AF.n(x, "avg_open_ms"))}", 12f, AK.sub))
                addView(c, AU.lp(0, -2, 1f, l = 10f, r = 8f))
                val slow = AF.n(x, "slow"); val hangs = AF.n(x, "hangs")
                addView(AU.t(ctx, if (slow + hangs > 0) "${AF.nf(slow)} slow · ${AF.nf(hangs)} froze" else "OK", 12f, if (slow + hangs > 0) AK.red else AK.green, true))
            }
        }))

        b.addView(AU.section(ctx, "Members who open it most"))
        val mem = AF.list(d, "members")
        if (mem.isEmpty()) b.addView(AU.t(ctx, "No one yet.", 13f, AK.sub))
        else b.addView(AU.listCard(ctx, mem.take(25).map { x ->
            val uid = AF.s(x, "user_id")
            val pp = people?.optJSONObject(uid)
            AU.person(ctx, a.src, pp ?: JSONObject().put("name", "Member"), 36f,
                "${AF.nf(AF.n(x, "opens"))} opens · ${AH.time(AF.n(x, "seconds"))}" + (if (AF.n(x, "slow") + AF.n(x, "hangs") > 0) " · ${AF.nf(AF.n(x, "slow"))} slow, ${AF.nf(AF.n(x, "hangs"))} froze" else ""),
                onOpen = { a.openMember(uid) }).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f)); setOnClickListener { a.openMember(uid) }
            }
        }))

        val days = AF.list(d, "days")
        if (days.size > 1) {
            b.addView(AU.section(ctx, "Day by day"))
            b.addView(AU.listCard(ctx, days.reversed().map { x ->
                AU.row(ctx).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f))
                    addView(AU.t(ctx, AH.day(AF.s(x, "day")), 13f, AK.ink, true), AU.lp(0, -2, 1f))
                    addView(AU.t(ctx, "${AF.nf(AF.n(x, "opens"))} opens · ${AF.nf(AF.n(x, "members"))} members" + (if (AF.n(x, "slow") + AF.n(x, "hangs") > 0) " · ${AF.nf(AF.n(x, "slow"))} slow · ${AF.nf(AF.n(x, "hangs"))} froze" else ""), 12f, AK.sub))
                }
            }))
        }

        val iss = AF.list(d, "issues")
        if (iss.isNotEmpty()) {
            b.addView(AU.section(ctx, "Problems on this page"))
            b.addView(AU.listCard(ctx, iss.map { i -> AH.issueRow(ctx, a, i, people?.optJSONObject(AF.s(i, "user_id")), true) { AdIssueSheet(a, i, people?.optJSONObject(AF.s(i, "user_id"))) { load() }.open() } }))
        }
    }
}

/** Issues: every problem members met (and the server saw) — newest first, by area, with the phone and network. */
class AdIssues(a: AdminScreen) : AdTab(a) {
    private var view = "all"            // all | crashes (the old grouped crash reports)
    private var range = "today"
    private var area = "all"
    private var kind = ""
    private var member = ""
    private var memberName = ""
    private var status = "open"
    private val extra = ArrayList<JSONObject>()
    private var more = false
    private var old: AdErrors? = null
    private val load = AdLoad(this, "adminHealth", { body("") }) { extra.clear(); render() }

    init {
        a.takeIssuesPreset()?.let { p ->
            range = p.optString("range", range).ifEmpty { range }
            kind = p.optString("kind")
            member = p.optString("user_id"); memberName = p.optString("name")
            p.optString("areas").takeIf { it.isNotEmpty() }?.let { area = it }
            status = if (member.isNotEmpty() || kind.isNotEmpty() || p.has("areas")) "all" else status
        }
    }

    private fun body(before: String): JSONObject {
        val areas = JSONArray(); AH.AREAS.firstOrNull { it.first == area }?.third?.forEach { areas.put(it) }
        return JSONObject().put("action", "issues").put("range", range).put("areas", areas).put("status", status)
            .put("kind", kind).put("user_id", member).put("before", before).put("limit", 60)
    }

    override fun build() { render(); load.load() }
    override fun destroy() { super.destroy(); old?.destroy() }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        root.addView(AU.filters(ctx, listOf(Triple("all", "All problems", 0), Triple("crashes", "Crash reports (grouped)", 0)), view) { view = it; render(); if (it == "all") load.load() })
        if (view == "crashes") {
            // the earlier Errors list: crashes grouped by kind, with "Mark fixed"
            val e = old ?: AdErrors(a).also { old = it; it.build() }
            (e.root.parent as? android.view.ViewGroup)?.removeView(e.root)
            root.addView(e.root, AU.lp(-1, -2))
            // a crash on purpose: shows that crash reports arrive (here, and in Firebase Crashlytics, which needs a first crash)
            root.addView(AU.btn(ctx, "Send a test crash", "light", small = true, icon = AdIc.Zap) {
                AdConfirm(a, "Close the app with a test crash?",
                    "Only to check that crash reports work. True Find closes now — open it again: the test crash shows here in a few minutes (and in Firebase → Crashlytics).",
                    "Crash now", danger = true) { done ->
                    done(true)
                    root.postDelayed({ throw RuntimeException("Test crash from the Control Center (not a real problem)") }, 400)
                }.open()
            }, AU.lp(-2, -2, t = 14f))
            return
        }
        root.addView(AU.filters(ctx, AH.RANGES, range) { range = it; render(); load.load() })
        val counts = AF.o(load.data, "counts")
        root.addView(AU.filters(ctx, AH.AREAS.map { (k, l, areas) ->
            Triple(k, l, if (k == "all") 0 else areas.sumOf { AF.n(counts, it) }.toInt())
        }, area) { area = it; kind = ""; render(); load.load() })
        root.addView(AU.filters(ctx, listOf(Triple("open", "Open", 0), Triple("resolved", "Fixed", 0), Triple("all", "All", 0)), status) { status = it; render(); load.load() })
        if (kind.isNotEmpty() || member.isNotEmpty()) {
            val chip = AU.row(ctx).apply { background = Tf.box(AK.tint, 10f); setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(8f)) }
            chip.addView(AU.t(ctx, listOf(if (kind.isNotEmpty()) "Only: ${AH.title(kind)}" else "", if (member.isNotEmpty()) "Member: ${memberName.ifEmpty { "one member" }}" else "").filter { it.isNotEmpty() }.joinToString(" · "), 13f, AK.deep, true), AU.lp(0, -2, 1f))
            chip.addView(AU.t(ctx, "Show all", 12.5f, AK.purple, true).apply { setOnClickListener { kind = ""; member = ""; memberName = ""; render(); load.load() } })
            root.addView(chip, AU.lp(-1, -2, b = 10f))
        }
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        val list = AF.list(d, "issues") + extra
        val people = AF.o(d, "people") ?: JSONObject()
        if (list.isEmpty()) {
            root.addView(AU.empty(ctx, AdIc.CheckCircle2, if (status == "open") "No open problems" else "Nothing here", if (status == "open") "Nothing went wrong for members in this time. New problems come in about every 5 minutes." else ""))
            return
        }
        val total = list.sumOf { AF.n(it, "count") }
        root.addView(AU.t(ctx, "${list.size} problem${if (list.size == 1) "" else "s"} · happened ${AF.nf(total)} time${if (total == 1.0) "" else "s"} · newest first", 12.5f, AK.sub), AU.lp(-1, -2, l = 2f, b = 8f))
        root.addView(AU.listCard(ctx, list.map { i ->
            val pp = people.optJSONObject(AF.s(i, "user_id"))
            AH.issueRow(ctx, a, i, pp, true) { AdIssueSheet(a, i, pp) { load.load() }.open() }
        }))
        if (AF.list(d, "issues").size + extra.size >= 60 && (extra.isEmpty() || more)) {
            root.addView(AU.btn(ctx, "Show older", "light") { loadMore(list.last()) }, AU.lp(-1, -2, t = 10f))
        }
        if (status == "open" && list.size > 1) root.addView(AU.btn(ctx, "Mark all shown fixed", "light", icon = AdIc.CheckCircle2) {
            AdConfirm(a, "Mark every open problem shown here as fixed?", "Any that happen again come back here by themselves.", "Mark all fixed") { done ->
                val areas = JSONArray(); AH.AREAS.firstOrNull { it.first == area }?.third?.forEach { areas.put(it) }
                src.fn("adminHealth", JSONObject().put("action", "issue_resolve").put("all", true).put("areas", areas).put("range", range)) { r ->
                    if (r.ok) { say("${AF.nf(AF.n(r.data, "resolved"))} marked fixed"); done(true); load.load() } else { say(AF.err(r), true); done(false) }
                }
            }.open()
        }, AU.lp(-1, -2, t = 10f))
    }

    private fun loadMore(lastRow: JSONObject) {
        src.fn("adminHealth", body(AF.s(lastRow, "last_at"))) { r ->
            if (!alive) return@fn
            if (!r.ok) { say(AF.err(r), true); return@fn }
            val more2 = AF.list(r.data, "issues")
            more = more2.size >= 60
            extra.addAll(more2)
            AF.o(r.data, "people")?.let { p -> val mine = AF.o(load.data, "people"); if (mine != null) p.keys().forEach { k -> mine.put(k, p.get(k)) } }
            render()
        }
    }

    override fun previewOpen(action: String) {
        when (action) {
            "details" -> AF.list(load.data, "issues").firstOrNull()?.let { i -> AdIssueSheet(a, i, AF.o(load.data, "people")?.optJSONObject(AF.s(i, "user_id"))) { }.open() }
            "calls" -> { area = "call"; render(); load.load() }
            "crashes" -> { view = "crashes"; render() }
        }
    }
}

/** One problem in full: what, who, the phone, Android, app version, network, when, how often — Mark fixed. */
class AdIssueSheet(private val a: AdminScreen, private val i: JSONObject, private val person: JSONObject?, private val changed: () -> Unit) {
    private val ctx = a.context
    fun open() {
        val sh = AdSheet(a.layer, AH.title(AF.s(i, "kind")))
        val b = sh.body
        val (ic, bg, fg) = AH.look(AF.s(i, "area"))
        val top = AU.row(ctx)
        top.addView(AU.tile(ctx, ic, bg, fg, 44f, 13f, 21f), LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)))
        val tc = AU.col(ctx)
        tc.addView(AU.t(ctx, AF.s(i, "detail").ifEmpty { AH.title(AF.s(i, "kind")) }, 14.5f, AK.ink, true, 19f))
        val n = AF.n(i, "count")
        tc.addView(AU.t(ctx, if (n > 1) "Happened ${AF.nf(n)} times" else "Happened once", 12.5f, AK.sub), AU.lp(-1, -2, t = 2f))
        top.addView(tc, AU.lp(0, -2, 1f, l = 12f))
        b.addView(top)
        val uid = AF.s(i, "user_id")
        if (uid.isNotEmpty()) {
            b.addView(AU.section(ctx, "Member"))
            b.addView(AU.card(ctx, 12f) { a.openMember(uid) }.apply {
                addView(AU.person(ctx, a.src, person ?: JSONObject().put("name", "Member"), 40f, null, { a.openMember(uid) }, AU.icon(ctx, AdIc.ChevronRight, AK.faint, 16f)))
            })
        }
        b.addView(AU.section(ctx, "Details"))
        b.addView(AU.card(ctx).apply {
            addView(AU.rows(ctx, 3f,
                AU.kv(ctx, "What", AH.title(AF.s(i, "kind"))),
                AU.kv(ctx, "Code", AF.s(i, "code")),
                AU.kv(ctx, "Page", AF.s(i, "page")),
                AU.kv(ctx, "Phone", AF.s(i, "device")),
                AU.kv(ctx, "Android", AF.s(i, "android")),
                AU.kv(ctx, "App version", AF.s(i, "app_version")),
                AU.kv(ctx, "Network", AH.net(AF.s(i, "network"), AF.s(i, "operator"))),
                AU.kv(ctx, "First", AF.fmt(AF.s(i, "first_at"))),
                AU.kv(ctx, "Last", AF.fmt(AF.s(i, "last_at"))),
                AU.kv(ctx, "Reported by", if (AF.s(i, "source") == "server") "The server" else "The member's phone"),
                if (AF.s(i, "ref_id").isNotEmpty()) AU.kv(ctx, if (AF.s(i, "area") == "payment") "Payment id" else if (AF.s(i, "area") == "call") "Call id" else "Id", AF.s(i, "ref_id")) else null,
                if (AF.s(i, "status") == "resolved") AU.kv(ctx, "Fixed", "${AF.fmt(AF.s(i, "resolved_at"))} · ${AF.s(i, "resolved_by")}", valueColor = AK.green) else null,
            ))
        })
        if (AF.s(i, "status") != "resolved") {
            b.addView(AU.btn(ctx, "Mark fixed", "dark", icon = AdIc.CheckCircle2) {
                a.src.fn("adminHealth", JSONObject().put("action", "issue_resolve").put("id", AF.s(i, "id"))) { r ->
                    if (r.ok) { a.say("Marked fixed ✓"); sh.close(); changed() } else a.say(AF.err(r), true)
                }
            }, AU.lp(-1, -2, t = 14f))
        }
        b.addView(AU.btn(ctx, "Copy details", "light", icon = AdIc.Copy) {
            AU.copy(ctx, listOf(AH.title(AF.s(i, "kind")), AF.s(i, "detail"), "Code: ${AF.s(i, "code")}", "Page: ${AF.s(i, "page")}", AH.phone(i), "Android ${AF.s(i, "android")}",
                "Times: ${AF.nf(n)} · first ${AF.fmt(AF.s(i, "first_at"))} · last ${AF.fmt(AF.s(i, "last_at"))}", "Member: ${AF.s(person, "name")} ($uid)", "Id: ${AF.s(i, "ref_id")}").joinToString("\n"))
            a.say("Copied")
        }, AU.lp(-1, -2, t = 10f))
        sh.show()
    }
}

/** Alerts to the owner's phone: on / off, the limits, a test, the last alerts. */
class AdAlertsSheet(private val a: AdminScreen) {
    private val ctx = a.context
    private lateinit var sheet: AdSheet
    private var d: JSONObject? = null
    private var err = ""
    private var enabled = true
    private val rules = LinkedHashMap<String, Triple<Boolean, String, String>>()
    private val ABOUT = listOf(
        Triple("calls", "Calls failing", "Calls that did not connect, dropped, or had no sound / picture"),
        Triple("payments", "Payments", "Stuck, failed, or paid without coins added"),
        Triple("crashes", "App crashes", "Crashes and \"app not responding\""),
        Triple("server", "Server errors", "Server errors and time-outs"),
        Triple("messages", "Messages not sending", "Messages that did not send"),
    )

    fun open() {
        sheet = AdSheet(a.layer, "Alerts to your phone")
        render(); sheet.show(); load()
    }
    private fun load() {
        a.src.fn("adminHealth", JSONObject().put("action", "alerts_get")) { r ->
            if (sheet.closed) return@fn
            if (r.ok) {
                d = r.data; err = ""
                enabled = AF.b(r.data, "enabled")
                val rr = AF.o(r.data, "rules")
                rules.clear()
                for ((k, _, _) in ABOUT) { val x = rr?.optJSONObject(k); rules[k] = Triple(AF.b(x, "on"), AF.nf(AF.n(x, "n")), AF.nf(AF.n(x, "min"))) }
            } else err = AF.err(r, "Could not load the alert settings.")
            render()
        }
    }
    private fun render() {
        val b = sheet.body
        b.removeAllViews()
        b.addView(AU.errorLine(ctx, err) { load() })
        val d = d ?: run { if (err.isEmpty()) b.addView(AU.loading(ctx)); return }
        b.addView(AU.t(ctx, "When many things go wrong in a few minutes, your phone gets a loud notification. The same alert comes at most once every 30 minutes.", 13f, AK.sub, false, 18f))
        if (!AF.b(d, "push_ready")) b.addView(AU.warn(ctx, "Notifications are not set up on the server (Firebase key) — alerts can't reach a phone.", "amber"), AU.lp(-1, -2, t = 10f))
        else b.addView(AU.t(ctx, "Your phones signed in as admin: ${AF.nf(AF.n(d, "admin_phones"))}", 12.5f, if (AF.n(d, "admin_phones") > 0) AK.green else AK.red, true), AU.lp(-1, -2, t = 8f))

        val master = AU.card(ctx)
        val sw = AdSwitch(ctx).apply { set(enabled, false); onChange = { v -> enabled = v; set(v) } }
        master.addView(AU.kvView(ctx, "Alerts on", sw))
        b.addView(master, AU.lp(-1, -2, t = 12f))

        for ((k, title, about) in ABOUT) {
            val cur = rules[k] ?: Triple(true, "", "")
            val c = AU.card(ctx)
            val h = AU.row(ctx)
            val tc = AU.col(ctx)
            tc.addView(AU.t(ctx, title, 14f, AK.ink, true))
            tc.addView(AU.t(ctx, about, 12f, AK.sub), AU.lp(-1, -2, t = 1f))
            h.addView(tc, AU.lp(0, -2, 1f, r = 10f))
            h.addView(AdSwitch(ctx).apply { set(cur.first, false); onChange = { v -> rules[k] = rules[k]!!.copy(first = v); set(v) } })
            c.addView(h)
            val inRow = AU.row(ctx)
            inRow.addView(AU.input(ctx, "5", cur.second, InputType.TYPE_CLASS_NUMBER) { v -> rules[k] = rules[k]!!.copy(second = v) }, AU.lp(0, -2, 1f))
            inRow.addView(AU.t(ctx, if (k == "payments") "or more in" else "or more in", 12.5f, AK.sub), AU.lp(-2, -2, l = 8f, r = 8f))
            inRow.addView(AU.input(ctx, "10", cur.third, InputType.TYPE_CLASS_NUMBER) { v -> rules[k] = rules[k]!!.copy(third = v) }, AU.lp(0, -2, 1f))
            inRow.addView(AU.t(ctx, "min", 12.5f, AK.sub), AU.lp(-2, -2, l = 8f))
            c.addView(inRow, AU.lp(-1, -2, t = 10f))
            b.addView(c, AU.lp(-1, -2, t = 10f))
        }

        val save = AU.btn(ctx, "Save", "dark", icon = AdIc.Save)
        save.setOnClickListener {
            if (save.busy) return@setOnClickListener
            save.busy = true
            val rr = JSONObject()
            for ((k, v) in rules) rr.put(k, JSONObject().put("on", v.first).put("n", v.second.toIntOrNull() ?: 0).put("min", v.third.toIntOrNull() ?: 0))
            a.src.fn("adminHealth", JSONObject().put("action", "alerts_save").put("enabled", enabled).put("rules", rr)) { r ->
                save.busy = false
                if (r.ok) { a.say("Alerts saved ✓"); load() } else a.say(AF.err(r), true)
            }
        }
        b.addView(save, AU.lp(-1, -2, t = 14f))
        val test = AU.btn(ctx, "Send a test alert to my phone", "light", icon = AdIc.Bell)
        test.setOnClickListener {
            if (test.busy) return@setOnClickListener
            test.busy = true
            a.src.fn("adminHealth", JSONObject().put("action", "alert_test")) { r ->
                test.busy = false
                if (r.ok) a.say("Sent to ${AF.nf(AF.n(r.data, "phones"))} phone${if (AF.n(r.data, "phones") == 1.0) "" else "s"} ✓") else a.say(AF.err(r), true)
            }
        }
        b.addView(test, AU.lp(-1, -2, t = 10f))

        val last = AF.list(d, "last")
        b.addView(AU.section(ctx, "Last alerts"))
        if (last.isEmpty()) b.addView(AU.t(ctx, "None sent yet.", 13f, AK.sub))
        else b.addView(AU.listCard(ctx, last.map { x ->
            AU.col(ctx).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
                addView(AU.t(ctx, AF.s(x, "title"), 13.5f, AK.ink, true))
                addView(AU.t(ctx, "${AF.fmt(AF.s(x, "created_date"))} · reached ${AF.nf(AF.n(x, "sent_to"))} phone${if (AF.n(x, "sent_to") == 1.0) "" else "s"}", 12f, AK.sub), AU.lp(-1, -2, t = 2f))
                if (AF.s(x, "body").isNotEmpty()) addView(AU.t(ctx, AF.s(x, "body"), 12f, AK.faint).apply { maxLines = 3 }, AU.lp(-1, -2, t = 2f))
            }
        }))
    }
}

/** The member sheet's App Health part: app use, payments, calls & coins, messages, pages, problems. */
object AdMemberHealth {
    fun addTo(a: AdminScreen, b: LinearLayout, d: JSONObject, userId: String, name: String): Map<String, View> {
        val ctx = a.context
        val h = AF.o(d, "health") ?: return emptyMap()
        val u = AF.o(h, "usage"); val ph = AF.o(h, "phone")
        val at = HashMap<String, View>()
        fun sec(key: String, title: String, right: View? = null) = AU.section(ctx, title, right).also { at[key] = it; b.addView(it) }

        // app use
        sec("use", "App use")
        b.addView(AU.card(ctx).apply {
            if (AF.n(u, "days_used") <= 0) addView(AU.t(ctx, "No app-use numbers yet (they come from the new app version, about every 5 minutes).", 12.5f, AK.sub, false, 17f), AU.lp(-1, -2, b = 6f))
            addView(AU.rows(ctx, 3f,
                AU.kv(ctx, "Opened the app", "${AF.nf(AF.n(u, "opens"))} times · ${AF.nf(AF.n(u, "opens_7d"))} in the last 7 days"),
                AU.kv(ctx, "Time in the app", "${AH.time(AF.n(u, "seconds"))} · ${AH.time(AF.n(u, "seconds_7d"))} in the last 7 days"),
                AU.kv(ctx, "Days used", "${AF.nf(AF.n(u, "days_used"))} · ${AF.nf(AF.n(u, "days_used_30d"))} in the last 30 days"),
                AU.kv(ctx, "First seen in the app", AF.fmt(AF.s(u, "first_at"))),
                AU.kv(ctx, "Last seen in the app", AF.fmt(AF.s(u, "last_at"))),
                AU.kv(ctx, "Phone", AF.s(ph, "device")),
                AU.kv(ctx, "Android", AF.s(ph, "android")),
                AU.kv(ctx, "App version", AF.s(ph, "app_version")),
                AU.kv(ctx, "Network (last)", AH.net(AF.s(ph, "network"), AF.s(ph, "operator"))),
            ))
        })

        // payments
        val pt = AF.o(h, "purchase_totals")
        sec("payments", "Payments")
        val buys = AF.list(h, "purchases")
        b.addView(AU.card(ctx).apply {
            val rows = mutableListOf(
                AU.kv(ctx, "Paid", "${AF.nf(AF.n(pt, "paid"))} · ${AH.rupees(AF.n(pt, "paid_inr"))}", true),
                AU.kv(ctx, "Pending", AF.nf(AF.n(pt, "pending")), valueColor = if (AF.n(pt, "pending") > 0) AK.amber else AK.ink),
                AU.kv(ctx, "Failed", AF.nf(AF.n(pt, "failed")), valueColor = if (AF.n(pt, "failed") > 0) AK.red else AK.ink),
                AU.kv(ctx, "Refunded", "${AF.nf(AF.n(pt, "refunded"))} · ${AH.rupees(AF.n(pt, "refunded_inr"))}"),
            )
            if (AF.n(pt, "test_paid") > 0) rows.add(1, AU.kv(ctx, "Test purchases", "${AF.nf(AF.n(pt, "test_paid"))} (no money)"))
            addView(AU.rows(ctx, 3f, *rows.toTypedArray()))
        })
        if (buys.isNotEmpty()) b.addView(AU.listCard(ctx, buys.take(30).map { x ->
            AU.row(ctx).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                val c = AU.col(ctx)
                c.addView(AU.t(ctx, "${AF.s(x, "plan_name").ifEmpty { "Purchase" }}${if (AF.s(x, "app") == "secret") " · Reconnect" else ""}", 13f, AK.ink, true).ell())
                c.addView(AU.t(ctx, "${AF.fmt(AF.s(x, "created_date"))}${AF.s(x, "provider_ref").let { if (it.isNotEmpty()) " · $it" else "" }}", 11.5f, AK.sub).ell())
                addView(c, AU.lp(0, -2, 1f, r = 8f))
                val rr = AU.col(ctx).apply { gravity = Gravity.END }
                rr.addView(AU.t(ctx, AH.rupees(AF.n(x, "amount_inr")), 13f, AK.ink, true).apply { gravity = Gravity.END })
                val st = AF.s(x, "status")
                rr.addView(when {
                    st == "succeeded" && AF.b(x, "test") -> AU.pill(ctx, "x", "Test · no money", AK.amberBg, AK.amber)
                    else -> when (st) {
                    "succeeded" -> AU.pill(ctx, "paid")
                    "pending" -> AU.pill(ctx, "pending")
                    "failed" -> AU.pill(ctx, "x", "Failed", AK.redBg, AK.red)
                    "refunded" -> AU.pill(ctx, "x", "Refunded", AK.blueBg, AK.blue)
                    else -> AU.pill(ctx, "x", st)
                    }
                }, AU.lp(-2, -2, t = 3f))
                addView(rr)
            }
        }).apply { layoutParams = AU.lp(-1, -2, t = 8f) })

        // calls & coins
        val c = AF.o(h, "calls"); val co = AF.o(h, "coins")
        sec("calls", "Calls & coins")
        val g = AdGrid(ctx, 0f, 8f, 2)
        fun tile(v: String, l: String, n: String) = AU.card(ctx, 10f).apply {
            addView(AU.t(ctx, v, 17f, AK.ink, true))
            addView(AU.t(ctx, l, 11.5f, AK.sub, true))
            if (n.isNotEmpty()) addView(AU.t(ctx, n, 11f, AK.faint))
        }
        g.addView(tile(AF.nf(AF.n(c, "voice")), "Voice calls", "${AF.loc(AF.n(c, "voice_minutes"), 1)} min"))
        g.addView(tile(AF.nf(AF.n(c, "video")), "Video calls", "${AF.loc(AF.n(c, "video_minutes"), 1)} min"))
        g.addView(tile(AF.coins(AF.n(co, "spent")), "Coins spent", "calls ${AF.coins(AF.n(co, "spent_calls"))} · chat ${AF.coins(AF.n(co, "spent_chat"))}"))
        g.addView(tile(AF.coins(AF.n(co, "bought")), "Coins bought", "free coins ${AF.coins(AF.n(co, "free"))}"))
        b.addView(g)
        b.addView(AU.card(ctx).apply {
            addView(AU.rows(ctx, 3f,
                AU.kv(ctx, "Calls made / received", "${AF.nf(AF.n(c, "made"))} / ${AF.nf(AF.n(c, "received"))}"),
                AU.kv(ctx, "Connected", AF.nf(AF.n(c, "connected"))),
                AU.kv(ctx, "Not answered · declined", "${AF.nf(AF.n(c, "not_answered"))} · ${AF.nf(AF.n(c, "declined"))}"),
                AU.kv(ctx, "Could not start", AF.nf(AF.n(c, "not_started")), valueColor = if (AF.n(c, "not_started") > 0) AK.red else AK.ink),
                AU.kv(ctx, "Coins on gifts & games", AF.coins(AF.n(co, "spent_gifts_games"))),
                AU.kv(ctx, "Earned on calls (TF)", AF.coins(AF.n(c, "creator_earned"))),
            ))
        }, AU.lp(-1, -2, t = 8f))

        // messages
        val ms = AF.o(h, "messages")
        sec("messages", "Messages")
        b.addView(AU.card(ctx).apply {
            addView(AU.rows(ctx, 3f,
                AU.kv(ctx, "Sent", "${AF.nf(AF.n(ms, "sent"))} · ${AF.nf(AF.n(ms, "sent_7d"))} in the last 7 days"),
                AU.kv(ctx, "Received", AF.nf(AF.n(ms, "received"))),
            ))
            addView(AU.t(ctx, "Only counted — the words are never shown here.", 11.5f, AK.faint), AU.lp(-1, -2, t = 4f))
        })

        // pages
        val pages = AF.list(h, "pages")
        if (pages.isNotEmpty()) {
            sec("pages", "Pages used (30 days)")
            b.addView(AU.listCard(ctx, pages.map { p ->
                AU.row(ctx).apply {
                    setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f))
                    addView(AU.t(ctx, AF.s(p, "page"), 13f, AK.ink, true).ell(), AU.lp(0, -2, 1f, r = 8f))
                    val bad = AF.n(p, "slow") + AF.n(p, "hangs")
                    addView(AU.t(ctx, "${AF.nf(AF.n(p, "opens"))}× · ${AH.time(AF.n(p, "seconds"))}" + (if (bad > 0) " · ${AF.nf(AF.n(p, "slow"))} slow · ${AF.nf(AF.n(p, "hangs"))} froze" else ""), 12f, if (bad > 0) AK.red else AK.sub))
                }
            }))
        }

        // problems
        val iss = AF.list(h, "issues")
        val counts = AF.o(h, "issue_counts")
        val total = AH.AREAS.drop(1).sumOf { (_, _, ar) -> ar.sumOf { AF.n(counts, it) } }
        sec("problems", "Problems this member met", if (iss.isNotEmpty()) AU.link(ctx, "See all") { a.openIssues(userId = userId, name = name, range = "30d") } else null)
        if (iss.isEmpty()) b.addView(AU.card(ctx).apply { addView(AU.t(ctx, "No problems reported.", 13f, AK.sub)) })
        else {
            b.addView(AU.t(ctx, "${AF.nf(total)} in the last 30 days: " + AH.AREAS.drop(1).mapNotNull { (_, l, ar) -> ar.sumOf { AF.n(counts, it) }.takeIf { it > 0 }?.let { "$l ${AF.nf(it)}" } }.joinToString(" · "), 12f, AK.sub), AU.lp(-1, -2, l = 2f, b = 8f))
            b.addView(AU.listCard(ctx, iss.take(15).map { i -> AH.issueRow(ctx, a, i, null, false) { AdIssueSheet(a, i, JSONObject().put("name", name)) { }.open() } }))
        }
        return at
    }
}
