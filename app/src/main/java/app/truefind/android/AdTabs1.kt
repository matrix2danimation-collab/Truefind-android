package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max

/** Overview (OverviewTab.jsx): today at a glance and everything waiting for a decision. */
class AdOverview(a: AdminScreen) : AdTab(a) {
    override fun build() = render()
    override fun onShared() = render()

    private fun kpi(icon: String, label: String, value: Double, note: String? = null): LinearLayout {
        val c = AU.card(ctx, 12f)
        val h = AU.row(ctx)
        h.addView(AU.icon(ctx, icon, AK.purple, 14f))
        h.addView(AU.t(ctx, label, 12f, AK.sub, true), AU.lp(-2, -2, l = 6f))
        c.addView(h)
        c.addView(AU.t(ctx, if (value < 0) "—" else AF.nf(value), 22f, AK.ink, true, 26f), AU.lp(-1, -2, t = 6f))
        if (!note.isNullOrEmpty()) c.addView(AU.t(ctx, note, 11.5f, AK.faint), AU.lp(-1, -2, t = 2f))
        return c
    }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        val d = a.overview
        if (a.ovLoading && d == null) { root.addView(AU.loading(ctx)); return }
        root.addView(AU.errorLine(ctx, a.ovError) { a.loadOverview() })
        val u = AF.o(d, "users"); val live = AF.o(d, "live"); val m = AF.o(d, "money_today"); val t = AF.o(d, "todo")
        val cfg = AF.o(d, "config"); val jobs = AF.o(d, "jobs")

        val off = listOf("calls_enabled" to "Calls", "games_enabled" to "Games", "live_enabled" to "Live rooms", "signups_enabled" to "New sign-ups")
            .filter { (k, _) -> cfg != null && cfg.has(k) && !cfg.isNull(k) && !PR.truthy(cfg.opt(k)) }.map { it.second }
        val maint = AF.b(cfg, "maintenance_mode")
        if (maint || off.isNotEmpty()) {
            val c = AU.card(ctx).apply { background = Tf.box(AK.black, 16f) }
            val r = AU.row(ctx)
            r.addView(AU.icon(ctx, AdIc.Wrench, Color.WHITE, 20f))
            val tx = AU.col(ctx)
            tx.addView(AU.t(ctx, if (maint) "Maintenance is ON — members can't use the app" else "Switched off: ${off.joinToString(", ")}", 14.5f, Color.WHITE, true))
            if (maint && off.isNotEmpty()) tx.addView(AU.t(ctx, "Also off: ${off.joinToString(", ")}", 12.5f, AK.onBlack), AU.lp(-1, -2, t = 2f))
            r.addView(tx, AU.lp(0, -2, 1f, l = 10f, r = 10f))
            r.addView(AU.t(ctx, "Settings", 12.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = Tf.box(AK.purple, 10f); setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                setOnClickListener { a.setTab("settings") }
            }, LinearLayout.LayoutParams(-2, Ui.dpi(32f)))
            c.addView(r)
            root.addView(c, AU.lp(-1, -2, b = 12f))
        }

        val g = AdGrid(ctx, 150f, 10f)
        g.addView(kpi(AdIc.Users, "Members", AF.n(u, "total")))
        g.addView(kpi(AdIc.UserPlus, "New today", AF.n(u, "new_today")))
        g.addView(kpi(AdIc.Wifi, "Online now", AF.n(u, "online")))
        g.addView(kpi(AdIc.PhoneCall, "On a call now", AF.n(u, "in_call"), "${AF.nf(AF.n(live, "calls"))} calls · ${AF.nf(AF.n(live, "ringing"))} ringing"))
        root.addView(g, AU.lp(-1, -2))

        root.addView(AU.section(ctx, "Needs you"))
        val supUnread = AF.n(t, "support_unread")
        val needs = listOf(
            Triple(AF.n(t, "creators"), "Creators to verify" to AdIc.ShieldCheck, "verify"),
            Triple(AF.n(t, "hosts"), "Hosts to verify" to AdIc.Radio, "verify"),
            Triple(AF.n(t, "photos"), "Photos to check" to AdIc.Image, "verify"),
            Triple(AF.n(t, "payouts"), "Withdrawals waiting" to AdIc.IndianRupee, "payouts"),
            Triple(AF.n(t, "reports"), "Reports to review" to AdIc.Flag, "reports"),
            Triple(if (supUnread != 0.0) supUnread else AF.n(t, "support"), (if (supUnread != 0.0) "New support messages" else "Open support chats") to AdIc.LifeBuoy, "support"),
            Triple(AF.n(t, "billing"), "Call bills to check" to AdIc.ReceiptText, "live"),
            Triple(AF.n(live, "stuck_calls"), "Calls gone quiet (jobs will end them)" to AdIc.PhoneOff, "live"),
            Triple(AF.n(t, "errors"), "App errors (last 24 h)" to AdIc.Bug, "errors"),
        ).filter { it.first > 0 }
        if (needs.isEmpty()) {
            val c = AU.card(ctx)
            val r = AU.row(ctx)
            r.addView(AU.icon(ctx, AdIc.CheckCircle2, AK.green, 20f))
            r.addView(AU.t(ctx, "All clear — nothing is waiting for you.", 14f, AK.ink, true), AU.lp(0, -2, 1f, l = 10f))
            c.addView(r)
            root.addView(c)
        } else {
            root.addView(AU.listCard(ctx, needs.map { (n, lt, go) ->
                AU.row(ctx).apply {
                    setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
                    addView(AU.tile(ctx, lt.second, AK.tint, AK.deep))
                    addView(AU.t(ctx, lt.first, 14f, AK.ink, true), AU.lp(0, -2, 1f, l = 12f, r = 12f))
                    addView(AU.t(ctx, AF.nf(n), 12.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = Tf.box(AK.black, 12f); minWidth = Ui.dpi(26f); setPadding(Ui.dpi(8f), 0, Ui.dpi(8f), 0) }, LinearLayout.LayoutParams(-2, Ui.dpi(24f)))
                    addView(AU.icon(ctx, AdIc.ChevronRight, AK.faint, 16f), AU.lp(Ui.dpi(16f), Ui.dpi(16f), l = 12f))
                    setOnClickListener { a.setTab(go) }
                }
            }))
        }

        root.addView(AU.section(ctx, "Today (since 12 am)"))
        val mc = AU.card(ctx)
        if (m != null) {
            val g3 = AdGrid(ctx, 0f, 8f, 3)
            for ((l, v, n) in listOf(Triple("Coins bought", AF.n(m, "topup_coins"), "${AF.nf(AF.n(m, "topups"))} recharges"), Triple("Coins spent", AF.n(m, "coins_spent"), "calls, gifts, games"), Triple("Creators earned", AF.n(m, "creator_earned"), "TF currency"))) {
                val c = AU.col(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
                c.addView(AU.t(ctx, AF.coins(v), 19f, AK.ink, true).apply { gravity = Gravity.CENTER })
                c.addView(AU.t(ctx, l, 12f, AK.sub, true).apply { gravity = Gravity.CENTER }, AU.lp(-2, -2, t = 1f))
                c.addView(AU.t(ctx, n, 11f, AK.faint).apply { gravity = Gravity.CENTER })
                g3.addView(c)
            }
            mc.addView(g3)
        } else mc.addView(AU.t(ctx, "Money totals could not be read right now.", 13f, AK.sub))
        root.addView(mc)

        root.addView(AU.section(ctx, "Background jobs"))
        val jc = AU.card(ctx) { a.setTab("system") }
        val jr = AU.row(ctx)
        jr.addView(AU.icon(ctx, AdIc.Cpu, AK.purple, 18f))
        val jt = AU.col(ctx)
        jt.addView(AU.t(ctx, if (AF.b(jobs, "never_ran")) "Not run yet — they start with the next member online" else "Last run ${AF.ago(AF.s(jobs, "last_started_at"))}", 14f, AK.ink, true))
        val le = AF.s(jobs, "last_error")
        jt.addView(AU.t(ctx, if (le.isNotEmpty()) "Last problem: $le" else "Ends quiet calls, frees busy people, cleans old data · ${AF.nf(AF.n(jobs, "runs"))} runs", 12f, if (le.isNotEmpty()) AK.red else AK.sub).ell(), AU.lp(-1, -2, t = 2f))
        jr.addView(jt, AU.lp(0, -2, 1f, l = 10f, r = 10f))
        jr.addView(AU.icon(ctx, AdIc.ChevronRight, AK.faint, 16f))
        jc.addView(jr)
        root.addView(jc)
    }
}

/** Growth (GrowthTab.jsx): where new members drop off, call ratings, Refer & Earn. */
class AdGrowth(a: AdminScreen) : AdTab(a) {
    private var days = "7"
    private val load = AdLoad(this, "adminOps", { JSONObject().put("action", "growth").put("days", days.toInt()) }) { render() }

    override fun build() { render(); load.load() }

    private fun shortDay(d: String): String {
        val p = d.split("-")
        val mi = p.getOrNull(1)?.toIntOrNull() ?: 0
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "${p.getOrNull(2)?.toIntOrNull() ?: ""} ${months.getOrElse(mi - 1) { "" }}"
    }

    private fun stat(icon: String, label: String, value: String, note: String? = null): LinearLayout {
        val c = AU.card(ctx, 12f)
        val h = AU.row(ctx)
        h.addView(AU.icon(ctx, icon, AK.purple, 14f))
        h.addView(AU.t(ctx, label, 12f, AK.sub, true), AU.lp(0, -2, 1f, l = 6f))
        c.addView(h)
        c.addView(AU.t(ctx, value, 22f, AK.ink, true), AU.lp(-1, -2, t = 6f))
        if (!note.isNullOrEmpty()) c.addView(AU.t(ctx, note, 11.5f, AK.faint), AU.lp(-1, -2, t = 2f))
        return c
    }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        root.addView(AU.filters(ctx, listOf(Triple("7", "7 days", 0), Triple("14", "14 days", 0), Triple("30", "30 days", 0), Triple("90", "90 days", 0)), days) { days = it; render(); load.load() })
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        if (d == null) return
        val signups = AF.n(d, "signups")
        val ratings = AF.o(d, "ratings")
        if (signups == 0.0 && AF.n(ratings, "count") == 0.0) {
            root.addView(AU.empty(ctx, AdIc.TrendingUp, "No new members in these days", "When people sign up, you'll see here where they stop: free coins → first call → recharge."))
            return
        }
        root.addView(AU.section(ctx, "New members · where they stop"))
        val fc = AU.card(ctx)
        val funnel = AF.list(d, "funnel")
        val top = AF.n(funnel.firstOrNull(), "count")
        for (s in funnel) fc.addView(funnelRow(s, top))
        fc.addView(AU.t(ctx, "Out of ${AF.nf(signups)} who finished sign-up in the last ${AF.s(d, "days").ifEmpty { days }} days${if (AF.b(d, "capped")) " (newest 5,000)" else ""}. A big drop between two rows is the place to improve.", 12f, AK.sub, false, 17f), AU.lp(-1, -2, t = 8f))
        root.addView(fc)

        val cb = AF.o(d, "came_back")
        val g = AdGrid(ctx, 150f, 10f)
        g.addView(stat(AdIc.Repeat, "Came back after day 1", "${AF.s(cb, "pct").ifEmpty { "0" }}%", "${AF.nf(AF.n(cb, "count"))} of ${AF.nf(signups)}"))
        g.addView(stat(AdIc.ShieldAlert, "Reports from calls", AF.nf(AF.n(d, "call_reports")), "In-call or after-call reports"))
        root.addView(g, AU.lp(-1, -2, t = 10f))

        root.addView(AU.section(ctx, "Sign-ups by day"))
        root.addView(AU.card(ctx).apply { addView(dayChart(AF.list(d, "by_day"))) })

        root.addView(AU.section(ctx, "Call ratings"))
        val rc = AU.card(ctx)
        rc.addView(stars(ratings))
        val wr = AF.n(ratings, "with_report")
        if (wr > 0) rc.addView(AU.t(ctx, "${AF.nf(wr)} low rating${if (wr > 1) "s" else ""} came with a report — see the Reports tab.", 12.5f, AK.red), AU.lp(-1, -2, t = 10f))
        root.addView(rc)
        root.addView(AU.section(ctx, "Lowest rated (5+ ratings, under 3 stars)"))
        root.addView(AU.card(ctx).apply { addView(rated(AF.list(ratings, "low"), true)) })
        root.addView(AU.section(ctx, "Best rated"))
        root.addView(AU.card(ctx).apply { addView(rated(AF.list(ratings, "top"), false)) })

        root.addView(AU.section(ctx, "Refer & Earn"))
        val rf = AF.o(d, "referrals")
        val g2 = AdGrid(ctx, 130f, 10f)
        g2.addView(stat(AdIc.Gift, "Joined with a code", AF.nf(AF.n(rf, "joined"))))
        g2.addView(stat(AdIc.Gift, "Waiting for recharge", AF.nf(AF.n(rf, "waiting"))))
        g2.addView(stat(AdIc.Gift, "Rewarded", AF.nf(AF.n(rf, "rewarded")), "${AF.nf(AF.n(rf, "coins_paid"))} coins paid in all"))
        g2.addView(stat(AdIc.ShieldAlert, "Stopped (fraud checks)", AF.nf(AF.n(rf, "blocked")), "Same phone, ban or 30/month limit"))
        root.addView(g2)
    }

    private fun funnelRow(s: JSONObject, top: Double): LinearLayout {
        val c = AU.col(ctx).apply { setPadding(0, Ui.dpi(7f), 0, Ui.dpi(7f)) }
        val h = AU.row(ctx, Gravity.BOTTOM)
        h.addView(AU.t(ctx, AF.s(s, "label"), 13.5f, AK.ink, true), AU.lp(0, -2, 1f, r = 8f))
        h.addView(AU.t(ctx, AF.nf(AF.n(s, "count")), 14f, AK.ink, true))
        h.addView(AU.t(ctx, "${AF.s(s, "pct").ifEmpty { "0" }}%", 12f, AK.sub).apply { gravity = Gravity.END }, AU.lp(Ui.dpi(46f), -2, l = 8f))
        c.addView(h, AU.lp(-1, -2, b = 5f))
        val n = AF.n(s, "count")
        val w = if (top > 0) max(if (n > 0) 2.0 else 0.0, n / top * 100) else 0.0
        c.addView(Bar(ctx, (w / 100).toFloat()), AU.lp(-1, Ui.dpi(10f)))
        return c
    }

    /** the funnel bar: a soft track with a 1 px line, purple fill */
    private class Bar(ctx: Context, private val f: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.style = Paint.Style.FILL; p.color = AK.soft; r.set(0f, 0f, w, h); c.drawRoundRect(r, Ui.dp(5f), Ui.dp(5f), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = Ui.dp(1f); p.color = AK.line; r.inset(Ui.dp(0.5f), Ui.dp(0.5f)); c.drawRoundRect(r, Ui.dp(5f), Ui.dp(5f), p)
            if (f > 0f) { p.style = Paint.Style.FILL; p.color = AK.purple; r.set(Ui.dp(1f), Ui.dp(1f), Ui.dp(1f) + (w - Ui.dp(2f)) * f.coerceAtMost(1f), h - Ui.dp(1f)); c.drawRoundRect(r, Ui.dp(4f), Ui.dp(4f), p) }
        }
    }

    // ── sign-ups per day (bars), with how many of them talked (the darker part) ──
    private fun dayChart(rows: List<JSONObject>): LinearLayout {
        val c = AU.col(ctx)
        val head = AU.t(ctx, "Tap a bar for that day", 12.5f, AK.sub).apply { minHeight = Ui.dpi(18f) }
        c.addView(head, AU.lp(-1, -2, b = 8f))
        val bars = DayBars(ctx, rows) { i ->
            val h = rows.getOrNull(i)
            head.text = if (h == null) "Tap a bar for that day" else android.text.SpannableStringBuilder().apply {
                val day = shortDay(AF.s(h, "day"))
                append(day); setSpan(android.text.style.ForegroundColorSpan(AK.ink), 0, day.length, 0); setSpan(TypefaceSpanCompat(Ui.bold), 0, day.length, 0)
                append(" · ${AF.nf(AF.n(h, "signups"))} sign-ups · ${AF.nf(AF.n(h, "talked"))} talked · ${AF.nf(AF.n(h, "recharged"))} recharged")
            }
        }
        c.addView(bars, AU.lp(-1, Ui.dpi(121f)))
        val axis = AU.row(ctx)
        val many = rows.size > 14
        axis.addView(AU.t(ctx, rows.firstOrNull()?.let { shortDay(AF.s(it, "day")) } ?: "", 11f, AK.faint), AU.lp(0, -2, 1f))
        if (!many && rows.size > 2) axis.addView(AU.t(ctx, shortDay(AF.s(rows[rows.size / 2], "day")), 11f, AK.faint).apply { gravity = Gravity.CENTER }, AU.lp(0, -2, 1f))
        axis.addView(AU.t(ctx, rows.lastOrNull()?.let { shortDay(AF.s(it, "day")) } ?: "", 11f, AK.faint).apply { gravity = Gravity.END }, AU.lp(0, -2, 1f))
        c.addView(axis, AU.lp(-1, -2, t = 5f))
        val legend = AU.row(ctx)
        fun key(color: Int, text: String) = AU.t(ctx, text, 12f, AK.sub).apply {
            compoundDrawablePadding = Ui.dpi(5f)
            setCompoundDrawablesWithIntrinsicBounds(Tf.box(color, 3f).apply { setSize(Ui.dpi(10f), Ui.dpi(10f)) }, null, null, null)
        }
        legend.addView(key(Ui.c("#C4B0EE"), "Sign-ups"))
        legend.addView(key(AK.purple, "Of them, talked on a call"), AU.lp(-2, -2, l = 14f))
        c.addView(legend, AU.lp(-1, -2, t = 8f))
        return c
    }

    @SuppressLint("ViewConstructor")
    private class DayBars(ctx: Context, private val rows: List<JSONObject>, private val onPick: (Int) -> Unit) : View(ctx) {
        private var sel = -1
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val max = max(1.0, rows.maxOfOrNull { AF.n(it, "signups") } ?: 1.0)
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (rows.isEmpty()) return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_UP) {
                val i = ((e.x / width) * rows.size).toInt().coerceIn(0, rows.size - 1)
                if (i != sel) { sel = i; invalidate(); onPick(i) }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            return super.onTouchEvent(e)
        }
        override fun onDraw(c: Canvas) {
            val n = rows.size
            val h = height - Ui.dp(1f)
            if (n > 0) {
                val gap = Ui.dp(2f)
                val bw = (width - gap * (n - 1)) / n
                rows.forEachIndexed { i, row ->
                    val s = AF.n(row, "signups"); val t = AF.n(row, "talked")
                    if (s <= 0) return@forEachIndexed
                    val bh = maxOf(Ui.dp(3f), (s / max * h).toFloat())
                    val x = i * (bw + gap)
                    p.color = if (sel == i) AK.deep else Ui.c("#C4B0EE")
                    r.set(x, h - bh, x + bw, h)
                    c.save(); c.clipRect(r)
                    val rad = Ui.dp(4f)
                    c.drawRoundRect(RectF(r.left, r.top, r.right, r.bottom + rad), rad, rad, p)
                    p.color = AK.purple
                    val th = (bh * (t / s)).toFloat()
                    c.drawRect(r.left, h - th, r.right, h, p)
                    c.restore()
                }
            }
            p.color = AK.line
            c.drawRect(0f, h, width.toFloat(), height.toFloat(), p)
        }
    }

    private fun stars(rt: JSONObject?): LinearLayout {
        val dist = (0 until 5).map { AF.num(rt?.optJSONArray("dist")?.opt(it)) }
        val count = AF.n(rt, "count"); val avg = AF.n(rt, "avg")
        val mx = max(1.0, dist.maxOrNull() ?: 1.0)
        val r = AU.row(ctx)
        val left = AU.col(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
        left.addView(AU.t(ctx, if (count > 0) String.format(java.util.Locale.US, "%.1f", avg) else "—", 28f, AK.ink, true).apply { gravity = Gravity.CENTER })
        left.addView(AU.t(ctx, "${AF.nf(count)} rating${if (count == 1.0) "" else "s"}", 11.5f, AK.sub).apply { gravity = Gravity.CENTER })
        r.addView(left, LinearLayout.LayoutParams(Ui.dpi(74f), -2))
        val rows = AU.col(ctx)
        for (s in listOf(5, 4, 3, 2, 1)) {
            val line = AU.row(ctx)
            line.addView(AU.t(ctx, "$s", 12f, AK.sub).apply { compoundDrawablePadding = Ui.dpi(2f); setCompoundDrawablesWithIntrinsicBounds(null, null, Tf.lu(AdIc.Star, AK.sub, 10f), null) }, LinearLayout.LayoutParams(Ui.dpi(22f), -2))
            val v = dist[s - 1]
            line.addView(object : View(ctx) {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val rr = RectF()
                override fun onDraw(c: Canvas) {
                    p.color = AK.soft; rr.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(rr, Ui.dp(4f), Ui.dp(4f), p)
                    val f = (v / mx).toFloat(); if (f > 0f) { p.color = if (s <= 2) AK.red else AK.purple; rr.set(0f, 0f, width * f, height.toFloat()); c.drawRoundRect(rr, Ui.dp(4f), Ui.dp(4f), p) }
                }
            }, AU.lp(0, Ui.dpi(8f), 1f, l = 7f, r = 7f))
            line.addView(AU.t(ctx, AF.nf(v), 12f, AK.ink, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(Ui.dpi(30f), -2))
            rows.addView(line, AU.lp(-1, -2, b = 4f))
        }
        r.addView(rows, AU.lp(0, -2, 1f, l = 16f))
        return r
    }

    private fun rated(list: List<JSONObject>, low: Boolean): View {
        if (list.isEmpty()) return AU.t(ctx, if (low) "Nobody with 5+ ratings is below 3 stars. 👍" else "Nobody has 5 ratings yet.", 13f, AK.sub)
        val c = AU.col(ctx)
        list.forEachIndexed { i, p ->
            val sub = "${if (AF.b(p, "creator")) "Creator · " else ""}${AF.nf(AF.n(p, "count"))} ratings${if (AF.n(p, "low") > 0) " · ${AF.nf(AF.n(p, "low"))} low" else ""}"
            val color = if (low) AK.red else Ui.c("#F5A400")
            val right = AU.t(ctx, String.format(java.util.Locale.US, "%.1f", AF.n(p, "avg")), 14f, if (low) AK.red else AK.ink, true).apply {
                compoundDrawablePadding = Ui.dpi(3f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(AdIc.Star, color, 13f, 2f, colorHex(color)), null, null, null)
            }
            val person = AU.person(ctx, src, JSONObject(p.toString()).put("profile_picture", AF.s(p, "photo")).put("full_name", AF.s(p, "name")), 38f, sub, { a.openMember(AF.s(p, "user_id")) }, right)
            c.addView(person, AU.lp(-1, -2, t = if (i > 0) 10f else 0f))
        }
        return c
    }

    private fun colorHex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
}
