package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Expert Talk — the expert's panel (ExpertPanel.jsx), native. An expert account is Expert Talk only:
 * this dashboard, her bookings and her profile. She can pick up calls (never decline or end them),
 * report a member, ask a member to move a session, set her days and how many sessions she takes a
 * day, pause bookings, edit her profile, change her photo (True Find approves it), see earnings and
 * ask to leave Expert Talk. The dashboard asks the server again every 4 seconds, like the web.
 */
class EtPanel(val et: ExpertTalkScreen) {
    var d: JSONObject? = null; private set
    var err: Et.R? = null; private set
    private var lastKey = ""
    var callId: String? = null
    private var loading = false

    val UPCOMING = listOf("confirmed", "ringing", "live")

    /** the dashboard answer, again (every 4 s while the page shows) */
    fun load(done: (() -> Unit)? = null) {
        if (loading) { done?.invoke(); return }
        loading = true
        Et.call("dash") { r ->
            loading = false
            if (r.ok) {
                d = r.data; err = null
                val key = JSONObject(r.data.toString()).apply { remove("now") }.toString()
                val changed = key != lastKey
                lastKey = key
                if (changed) et.panelChanged()
                ringCheck()
            } else if (d == null) { err = r; et.panelChanged() }
            done?.invoke()
        }
    }

    /** an incoming call opens on its own (she can't decline it) */
    private var rangFor = ""
    private fun ringCheck() {
        val ringing = Et.objs(d?.optJSONArray("bookings")).firstOrNull { Et.s(it, "status") == "ringing" }
        if (ringing == null) { rangFor = ""; return }
        val id = Et.s(ringing, "id")
        if (callId == null && rangFor != id) {
            rangFor = id
            vibrate()
            et.openExpertCall(id)
        }
    }

    @SuppressLint("MissingPermission")
    private fun vibrate() {
        try {
            val v = et.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val pattern = longArrayOf(0, 400, 200, 400)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        } catch (e: Throwable) { }
    }

    fun booking(id: String): JSONObject? = Et.objs(d?.optJSONArray("bookings")).firstOrNull { Et.s(it, "id") == id }
}

// ═══════════════════════════ dashboard ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtXDash(et: ExpertTalkScreen, private val panel: EtPanel) : EtPage(et), EtPreviewable {
    override fun pullRefresh(done: () -> Unit) = panel.load(done)
    override fun preview(what: String) {
        val b = Et.objs(panel.d?.optJSONArray("bookings"))
        when (what) {
            "pause" -> pauseSheet(); "leave" -> leaveSheet()
            "resched" -> b.firstOrNull { Et.s(it, "status") == "confirmed" }?.let { reschedSheet(it) }
            "explain" -> b.firstOrNull { Et.s(it, "status") == "expert_no_show" }?.let { explainSheet(it) }
            "scroll" -> scroll.post { scroll.scrollTo(0, Ui.dpi(900f)) }
            "bottom" -> scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    override val rank = 0
    private var busy = false
    private val tickers = ArrayList<() -> Unit>()

    override fun back(): Boolean = false
    override fun tick() { for (t in tickers) t() }

    private fun act(f: (() -> Unit) -> Unit) { busy = true; f { busy = false; panel.load() } }

    override fun render() {
        val ui = et.ui
        val d = panel.d ?: return
        val ex = d.optJSONObject("expert") ?: JSONObject()
        val rules = d.optJSONObject("rules")
        val now = System.currentTimeMillis()
        val bookings = Et.objs(d.optJSONArray("bookings"))
        val up = bookings.filter { panel.UPCOMING.contains(Et.s(it, "status")) }.sortedBy { Et.msOf(Et.s(it, "start_at")) }
        val missed = bookings.filter { Et.s(it, "status") == "expert_no_show" && Et.s(it, "explain").isEmpty() }
        val past = bookings.filter { listOf("completed", "user_no_show", "cut_by_report", "expert_no_show").contains(Et.s(it, "status")) }.take(8)
        val warnMax = rules?.optInt("warn_max", 4) ?: 4
        tickers.clear()
        rebuild { b ->
            val h = etRow(ctx, 10f).apply { setPadding(Ui.dpi(16f), Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(4f)) }
            h.put(etCol(ctx).apply {
                put(etT(ctx, "Expert Talk", 21f, Et.INK, true, 1f).apply { letterSpacing = -.01f })
                put(etT(ctx, "by True Find", 10.5f, Et.MUT, true, 1f).apply { letterSpacing = .04f }, t = 2f)
            }, 0f, -2f, 1f)
            h.put(etPill(ctx, "Professional", "solid", EtIcon.ShieldCheck))
            b.put(h, -1f, -2f)
            val st = ui.stack(14f)
            b.put(st, -1f, -2f)
            // hello card → my profile
            st.put(ui.card(12f, shadow = true, row = true, pad = floatArrayOf(14f, 14f, 14f, 14f)).apply {
                put(EtPhoto(ctx, 54f).apply { demoKey = Et.s(ex, "id"); set(Et.s(ex, "photo")) }, 54f, 54f)
                put(etCol(ctx).apply {
                    put(etT(ctx, "Expert Dashboard", 11.5f, Et.MUT))
                    put(etRow(ctx, 5f).apply { put(etT(ctx, "Hi, ${Et.firstName(Et.s(ex, "name"))}", 17f, Et.INK, true)); put(etVerified(ctx, 15f), 15f, 15f) })
                    put(etT(ctx, Et.s(ex, "title"), 11.5f, Et.MUT))
                }, 0f, -2f, 1f)
                put(etIcon(ctx, EtIcon.ChevronRight, 18f, Ui.c("#A29EAD")), 18f, 18f)
                setOnClickListener { et.xgo("profile") }
            }, -1f, -2f)
            if (Et.b(ex, "admin_paused")) st.put(alert(EtSpan().add("Bookings switched off by True Find.", bold = true).add(" ${Et.s(ex, "paused_reason")}. Please contact True Find support.").build(), true), -1f, -2f)
            val warnings = Et.i(ex, "warnings")
            if (!Et.b(ex, "admin_paused") && warnings > 0) st.put(alert(EtSpan().add("$warnings of $warnMax warnings.", bold = true).add(" Explain every missed session within 24 hours. At $warnMax warnings your bookings switch off.").build(), false), -1f, -2f)
            st.put(acceptingCard(ex, up), -1f, -2f)
            st.put(earnCard(d, rules), -1f, -2f)
            if (missed.isNotEmpty()) {
                val sec = etCol(ctx, 10f)
                sec.put(ui.st(etT(ctx, "Explain missed sessions", 16.5f, Et.INK, true), etPill(ctx, "${missed.size}", "bad")))
                for (bk in missed) {
                    val c = etCol(ctx, 8f).apply { background = Tf.box(Color.WHITE, 18f, 1.5f, Ui.c("#F3C4C6")); setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) }
                    c.put(etRow(ctx, 8f).apply { put(etIcon(ctx, EtIcon.CircleAlert, 17f, Et.RED_TEXT), 17f, 17f); put(etT(ctx, "${Et.s(bk, "user_alias")} · ${Et.whenText(Et.s(bk, "date"), Et.i(bk, "start_min"))}", 12.5f, Et.INK, true), 0f, -2f, 1f) })
                    val due = Et.msOf(Et.s(bk, "explain_due"))
                    val note = etT(ctx, "", 11.5f, Et.MUT)
                    val upd = {
                        val n = System.currentTimeMillis()
                        val w = Et.i(bk, "warns")
                        note.text = "You didn't pick up, so the member got a full refund. " + if (due > n) "Explain within ${Et.countdown(due - n)}." else "Overdue · $w warning${if (w == 1) "" else "s"} so far."
                    }
                    upd(); tickers.add(upd)
                    c.put(note)
                    c.put(EtBtn(ctx, "Write explanation", "s2", sm = true).apply { setOnClickListener { explainSheet(bk) } }, -1f, -2f)
                    sec.put(c, -1f, -2f)
                }
                st.put(sec, -1f, -2f)
            }
            // upcoming sessions
            val sec = etCol(ctx, 10f)
            sec.put(ui.st(etT(ctx, "Upcoming sessions", 16.5f, Et.INK, true), etT(ctx, "${up.size}", 12.5f, Et.MUT)))
            if (up.isEmpty()) sec.put(ui.empty("No upcoming sessions."), -1f, -2f)
            for (bk in up) sec.put(upcomingCard(bk, rules, now), -1f, -2f)
            st.put(sec, -1f, -2f)
            if (past.isNotEmpty()) {
                val ps = etCol(ctx, 10f)
                ps.put(etT(ctx, "Recent", 16.5f, Et.INK, true))
                val card = ui.card()
                past.forEachIndexed { i, bk ->
                    val stt = Et.s(bk, "status")
                    val r = etRow(ctx, 12f).apply { setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f)); if (i < past.size - 1) background = BottomLine(Et.SOFT_LINE) }
                    r.put(etCol(ctx).apply { put(etT(ctx, "${Et.s(bk, "user_alias")} · ${Et.i(bk, "dur")} min", 12.5f, Et.INK, true).etEll()); put(etT(ctx, Et.whenText(Et.s(bk, "date"), Et.i(bk, "start_min")), 11.5f, Et.MUT)) }, 0f, -2f, 1f)
                    val stars = Et.i(bk, "stars")
                    val label = when (stt) { "completed" -> if (stars > 0) "$stars★" else "Done"; "user_no_show" -> "Member didn't call"; "cut_by_report" -> if (Et.s(bk, "earn_state") == "held") "Under review" else "Reported"; else -> "Missed" }
                    r.put(etPill(ctx, label, if (stt == "completed") "ok" else if (stt == "expert_no_show") "bad" else "grey"))
                    card.put(r, -1f, -2f)
                }
                ps.put(card, -1f, -2f)
                st.put(ps, -1f, -2f)
            }
            // menu
            val menu = ui.card()
            val leaving = Et.s(ex, "leave_status") == "pending"
            val items = listOf(Triple(EtIcon.CalendarDays, "Availability & sessions per day") { et.xgo("avail") }, Triple(EtIcon.UserRound, "My profile") { et.xgo("profile") },
                Triple(EtIcon.UserX, if (leaving) "Leave request sent" else "Leave Expert Talk") { leaveSheet() }, Triple(EtIcon.LogOut, "Log out") { et.pages.logout() })
            items.forEachIndexed { i, (ic, t, f) ->
                menu.put(etRow(ctx, 12f).apply {
                    setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f)); if (i < items.size - 1) background = BottomLine(Et.SOFT_LINE)
                    put(etSq(ctx, ic, 16f, Et.P, Et.TINT, 32f, 10f), 32f, 32f)
                    put(etT(ctx, t, 12.5f, Et.INK, true), 0f, -2f, 1f)
                    put(etIcon(ctx, EtIcon.ChevronRight, 17f, Ui.c("#A29EAD")), 17f, 17f)
                    setOnClickListener { f() }
                }, -1f, -2f)
            }
            st.put(menu, -1f, -2f)
            val rulesC = etCol(ctx, 8f).apply { background = Tf.box(Et.INK, 22f); setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f)) }
            rulesC.put(etRow(ctx, 8f).apply { put(etIcon(ctx, EtIcon.ShieldCheck, 18f, Ui.c("#C9B6F5")), 18f, 18f); put(etT(ctx, "Expert rules", 15f, Color.WHITE, true)) })
            for (t in listOf("Never ask for a member's phone number, address or social IDs.", "Pick up every booked call. You can't decline or end a call.",
                    "Rude member? Report once to warn them; a second report ends the call.", "Missed a session? Explain within 24 hours or you get warnings."))
                rulesC.put(etT(ctx, "• $t", 12.5f, Ui.c("#CFC8DD")))
            st.put(rulesC, -1f, -2f)
            st.put(etRow(ctx, 6f, Gravity.CENTER).apply { put(etIcon(ctx, EtIcon.Lock, 12f, Et.MUT), 12f, 12f); put(etT(ctx, "Members see only your profile, never your earnings or phone.", 11.5f, Et.MUT)) }, -1f, -2f)
        }
    }

    private fun alert(text: CharSequence, red: Boolean): View = etAlert(ctx, text, red)

    private fun acceptingCard(ex: JSONObject, up: List<JSONObject>): View {
        val ui = et.ui
        val on = Et.b(ex, "accepting")
        val c = ui.card(8f, pad = floatArrayOf(14f, 16f, 14f, 16f))
        val row = etRow(ctx, 10f)
        row.put(etCol(ctx).apply {
            put(etRow(ctx, 8f).apply { put(View(ctx).apply { background = Tf.circle(if (on) Et.GREEN else Ui.c("#A29EAD")) }, 8f, 8f); put(etT(ctx, "Accepting bookings", 15f, Et.INK, true)) })
            put(etT(ctx, if (on) "Members can find your profile and book you" else "Your profile is hidden from members", 12.5f, Et.MUT))
        }, 0f, -2f, 1f)
        val disabled = busy || Et.b(ex, "admin_paused") || Et.s(ex, "leave_status") == "pending"
        row.put(EtSwitch(ctx, on).apply {
            contentDescription = "Accepting bookings"
            alpha = if (disabled) .5f else 1f
            setOnClickListener {
                if (disabled) return@setOnClickListener
                if (on) pauseSheet() else act { fin -> Et.call("accepting", JSONObject().put("on", true)) { r -> if (r.ok) toast("Bookings are on") else toast(r.error); fin() } }
            }
        }, 52f, 30f)
        c.put(row, -1f, -2f)
        val d = panel.d
        val perDay = Et.i(ex, "per_day"); val todayN = Et.i(d, "today_count")
        if (on) c.put(etT(ctx, "Today: $todayN of $perDay sessions booked${if (todayN >= perDay) " · today is full" else ""}", 11.5f, Et.MUT))
        val resume = Et.msOf(Et.s(ex, "resume_at"))
        if (!on && resume > 0) c.put(etT(ctx, "Back on by itself: ${Et.wdDayTime(resume)}", 11.5f, Et.MUT))
        if (!on && up.isNotEmpty()) c.put(etT(ctx, "You still have ${up.size} booked session${if (up.size == 1) "" else "s"}. Please take them.", 11.5f, Ui.c("#7A4A00")))
        return c
    }

    private fun earnCard(d: JSONObject, rules: JSONObject?): View {
        val earn = d.optJSONObject("earnings")
        val c = etCol(ctx).apply {
            background = EtDotsBg(); clipToOutline = true
            setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f))
        }
        c.put(etT(ctx, "Available to withdraw", 11.5f, Color.argb(217, 255, 255, 255)))
        c.put(etT(ctx, "₹${Et.num(Et.d(earn, "available"))}", 30f, Color.WHITE, true), t = 4f, b = 10f)
        c.put(etRow(ctx, 18f).apply {
            put(etCol(ctx).apply { put(etT(ctx, "Pending", 11.5f, Color.argb(204, 255, 255, 255))); put(etT(ctx, "₹${Et.num(Et.d(earn, "pending"))}", 15f, Color.WHITE, true)) })
            put(etCol(ctx).apply { put(etT(ctx, "Your share", 11.5f, Color.argb(204, 255, 255, 255))); put(etT(ctx, "${rules?.optInt("share_pct", 50) ?: 50}%", 15f, Color.WHITE, true)) })
        })
        c.put(EtBtn(ctx, "Earnings & withdraw", "wht", EtIcon.Wallet, 17f).apply { setOnClickListener { et.pages.go("/wallet") } }, -1f, -2f, t = 14f)
        return c
    }

    private fun upcomingCard(bk: JSONObject, rules: JSONObject?, now0: Long): View {
        val ui = et.ui
        val stt = Et.s(bk, "status")
        val date = Et.s(bk, "date"); val sm = Et.i(bk, "start_min")
        val start = Et.startMs(date, sm); val live = stt == "live" || stt == "ringing"
        val openBefore = rules?.optLong("open_before_ms", 120_000L) ?: 120_000L
        val resched = bk.optJSONObject("resched")?.takeIf { Et.s(it, "date").isNotEmpty() }
        val canAsk = stt == "confirmed" && resched == null && now0 < start - openBefore
        val c = ui.card(10f, pad = floatArrayOf(14f, 14f, 14f, 14f))
        val row = etRow(ctx, 12f)
        row.put(etCol(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply {
            put(etT(ctx, Et.hmShort(sm), 15f, Et.INK, true))
            put(etT(ctx, "${Et.hm(sm).takeLast(2)} · ${Et.i(bk, "dur")}m", 11.5f, Et.MUT))
        }, 54f, -2f)
        row.put(etSq(ctx, EtIcon.UserRound, 16f, Et.P, Et.TINT, 32f, 10f), 32f, 32f)
        row.put(etCol(ctx).apply {
            put(etT(ctx, Et.s(bk, "user_alias"), 15f, Et.INK, true).etEll())
            put(etT(ctx, "${Et.dayLabel(date)} · ${Et.topicNames(Et.strs(bk.optJSONArray("concerns")))}", 11.5f, Et.MUT).etEll())
        }, 0f, -2f, 1f)
        val pill = etPill(ctx, "", if (live) "ok" else "pur")
        val upd = { val n = System.currentTimeMillis(); pill.text = if (live) "On call" else if (n < start) "in ${Et.countdown(start - n)}" else "Now" }
        upd(); tickers.add(upd)
        row.put(pill)
        c.put(row, -1f, -2f)
        bk.optJSONObject("moved_from")?.let { mf -> if (Et.s(mf, "date").isNotEmpty()) c.put(etRow(ctx, 6f).apply { put(etIcon(ctx, EtIcon.RefreshCw, 13f, Et.P), 13f, 13f); put(etT(ctx, "The member moved this from ${Et.whenText(Et.s(mf, "date"), Et.i(mf, "start_min"))}", 11.5f, Ui.c("#57546A")), 0f, -2f, 1f) }, -1f, -2f) }
        if (resched != null) c.put(etPill(ctx, "Asked to move to ${Et.whenText(Et.s(resched, "date"), Et.i(resched, "start_min"))} · waiting for the member", "warn"), -2f, -2f)
        if (live) c.put(EtBtn(ctx, if (stt == "ringing") "Pick up" else "Return to call", "green", EtIcon.Phone, 16f, sm = true).apply { setOnClickListener { et.openExpertCall(Et.s(bk, "id")) } }, -1f, -2f)
        if (canAsk) c.put(EtBtn(ctx, "Request a new time", "s2", sm = true).apply { setOnClickListener { reschedSheet(bk) } }, -1f, -2f)
        c.put(etT(ctx, "You earn ₹${Et.num(Et.d(bk, "share"))} for this session.", 11.5f, Et.MUT))
        return c
    }

    // ── pop-ups ──
    private fun pauseSheet() {
        val ui = et.ui
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.Pause, "Turn off bookings?", "Your profile is hidden from members until you turn bookings back on. Booked sessions stay.") { close() }, -1f, -2f)
            val now = System.currentTimeMillis()
            val restOfToday = maxOf(1, Math.ceil((Et.dayMs(Et.todayIST()) + (23 * 60 + 59) * 60_000L - Et.IST - now) / 3_600_000.0).toInt())
            for ((l, h) in listOf("For 2 hours" to 2, "For the rest of today" to restOfToday, "For 1 day" to 24, "For 3 days" to 72, "Until I turn them on" to 0)) {
                p.put(etRow(ctx, 10f).apply {
                    background = Tf.box(Color.WHITE, 14f, 1.5f, Et.LINE); setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f)); minimumHeight = Ui.dpi(44f)
                    put(etIcon(ctx, EtIcon.Clock, 17f, Et.P), 17f, 17f)
                    put(etT(ctx, l, 12.5f, Et.INK, true), 0f, -2f, 1f)
                    put(etIcon(ctx, EtIcon.ChevronRight, 16f, Ui.c("#A29EAD")), 16f, 16f)
                    setOnClickListener { if (!busy) act { fin -> Et.call("accepting", JSONObject().put("on", false).put("resume_hours", h)) { r -> if (r.ok) { toast("Bookings are off"); close() } else toast(r.error); fin() } } }
                }, -1f, -2f)
            }
        }
    }

    private fun reschedSheet(bk: JSONObject) {
        val ui = et.ui
        var text = ""
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.CalendarDays, "Ask the member to move", "${Et.s(bk, "user_alias")} · ${Et.whenText(Et.s(bk, "date"), Et.i(bk, "start_min"))}. True Find suggests your next free time; the member can accept, keep the time or pick another.") { close() }, -1f, -2f)
            p.put(etTextArea(ctx, "Reason (e.g. Scheduling conflict)", 120, 2) { text = it }, -1f, -2f)
            p.put(EtBtn(ctx, "Send request").apply { setOnClickListener { if (!busy) { off = true; act { fin -> Et.call("resched_request", JSONObject().put("booking_id", Et.s(bk, "id")).put("reason", text)) { r -> if (r.ok) { toast("Request sent to the member"); close() } else { toast(r.error); off = false }; fin() } } } } }, -1f, -2f)
            p.put(etT(ctx, "If the member keeps the original time and you can't make it, they get a full refund and it counts as a missed session.", 11.5f, Et.MUT))
        }
    }

    private fun explainSheet(bk: JSONObject) {
        val ui = et.ui
        var text = ""
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.CircleAlert, "Why did you miss it?", "${Et.s(bk, "user_alias")} · ${Et.whenText(Et.s(bk, "date"), Et.i(bk, "start_min"))}") { close() }, -1f, -2f)
            val send = EtBtn(ctx, "Send explanation").apply { off = true }
            p.put(etTextArea(ctx, "What happened?", 600) { text = it; send.off = busy || it.trim().length < 10 }, -1f, -2f)
            send.setOnClickListener { if (!busy && text.trim().length >= 10) { send.off = true; act { fin -> Et.call("explain", JSONObject().put("booking_id", Et.s(bk, "id")).put("text", text)) { r -> if (r.ok) { toast("Explanation sent"); close() } else { toast(r.error); send.off = false }; fin() } } } }
            p.put(send, -1f, -2f)
        }
    }

    private fun leaveSheet() {
        val ui = et.ui
        val ex = panel.d?.optJSONObject("expert") ?: return
        var text = ""
        sheet { p, close ->
            if (Et.s(ex, "leave_status") == "pending") {
                p.put(ui.sheetHead(EtIcon.LogOut, "Your request is with True Find", "Reason: ${Et.s(ex, "leave_reason")}") { close() }, -1f, -2f)
                p.put(EtBtn(ctx, "Cancel my request", "s2").apply { setOnClickListener { if (!busy) { off = true; act { fin -> Et.call("leave_cancel") { r -> if (r.ok) { toast("Request cancelled"); close() } else { toast(r.error); off = false }; fin() } } } } }, -1f, -2f)
            } else {
                p.put(ui.sheetHead(EtIcon.LogOut, "Leave Expert Talk?", "When True Find accepts, your account becomes a normal member account (not a creator). Your booked sessions are refunded to the members.") { close() }, -1f, -2f)
                if (Et.s(ex, "leave_status") == "declined") p.put(etT(ctx, "Your last request was declined. You can ask again.", 12.5f, Et.RED_TEXT))
                p.put(etT(ctx, EtSpan().add("Withdraw your earnings first.", bold = true).add(" After you leave, withdrawals as an expert stop.").build(), 12.5f, Ui.c("#6B4400")).apply {
                    background = Tf.box(Ui.c("#FFF6E8"), 16f, 1f, Ui.c("#F6DDB0")); setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
                }, -1f, -2f)
                val send = EtBtn(ctx, "Send request", "red").apply { off = true }
                p.put(etTextArea(ctx, "Why do you want to leave?", 300, 3) { text = it; send.off = busy || it.trim().length < 3 }, -1f, -2f)
                send.setOnClickListener { if (!busy && text.trim().length >= 3) { send.off = true; act { fin -> Et.call("leave", JSONObject().put("reason", text)) { r -> if (r.ok) { toast("Request sent to True Find"); close() } else { toast(r.error); send.off = false }; fin() } } } }
                p.put(send, -1f, -2f)
            }
        }
    }
}

/** the purple earnings card: dots over a purple slope (.earn) */
class EtDotsBg : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(51, 255, 255, 255) }
    private val path = android.graphics.Path()
    override fun onBoundsChange(b: android.graphics.Rect) {
        // linear-gradient(160deg, #6A27F3 0%, #5E17EB 55%, #5612E2 100%)
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val a = Math.toRadians(160.0 - 90.0)
        val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
        val half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        p.shader = android.graphics.LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            intArrayOf(Ui.c("#6A27F3"), Ui.c("#5E17EB"), Ui.c("#5612E2")), floatArrayOf(0f, .55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        path.reset(); val r = Ui.dp(22f)
        path.addRoundRect(android.graphics.RectF(b), r, r, android.graphics.Path.Direction.CW)
    }
    override fun draw(c: android.graphics.Canvas) {
        c.save(); c.clipPath(path)
        c.drawPath(path, p)
        // radial dots (.9 px) every 9 px, starting 4 px in
        val step = Ui.dp(9f); val r = Ui.dp(.9f)
        var y = bounds.top + Ui.dp(4f) + step / 2f
        while (y < bounds.bottom) { var x = bounds.left + Ui.dp(4f) + step / 2f; while (x < bounds.right) { c.drawCircle(x, y, r, dot); x += step }; y += step }
        c.restore()
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, Ui.dp(22f)) }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** the on / off switch (.tgl: 52 × 30, green when on) */
class EtSwitch(ctx: Context, private val on: Boolean) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; contentDescription = "switch" }
    override fun onDraw(c: android.graphics.Canvas) {
        p.color = if (on) Ui.c("#157F44") else Ui.c("#D6D0E2")
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, p)
        p.color = Color.WHITE
        val r = Ui.dp(12f)
        c.drawCircle(if (on) width - Ui.dp(3f) - r else Ui.dp(3f) + r, height / 2f, r, p)
    }
}

// ═══════════════════════════ availability ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtXAvail(et: ExpertTalkScreen, private val panel: EtPanel) : EtPage(et) {
    override val rank = 1
    private val ex = panel.d?.optJSONObject("expert") ?: JSONObject()
    private val days = Et.ints(ex.optJSONArray("days")).toMutableList()
    private val durs = Et.ints(ex.optJSONArray("durs")).toMutableList()
    private var per = Et.i(ex, "per_day", 6)
    private var busy = false
    private val save = EtBtn(ctx, "Save")

    override fun back(): Boolean { et.xgo("dash"); return true }

    private fun changed(): Boolean = days.sorted() != Et.ints(ex.optJSONArray("days")).sorted() || durs.sorted() != Et.ints(ex.optJSONArray("durs")).sorted() || per != Et.i(ex, "per_day", 6)

    override fun render() {
        val ui = et.ui
        val d = panel.d
        val rules = d?.optJSONObject("rules")
        rebuild { b ->
            b.put(ui.hdr("Availability", false, { et.xgo("dash") }), -1f, -2f)
            val st = ui.stack(14f)
            b.put(st, -1f, -2f)
            st.put(ui.card(10f, pad = floatArrayOf(14f, 16f, 14f, 16f)).apply {
                put(etT(ctx, "Working days", 15f, Et.INK, true))
                put(ui.chips(listOf(1, 2, 3, 4, 5, 6, 0).map { i -> ui.chip(Et.WDN[i], days.contains(i)) { if (days.contains(i)) days.remove(i) else days.add(i); render() } }), -1f, -2f)
                put(etT(ctx, "10:00 AM – 6:00 PM · lunch 1–2 PM · set by True Find", 12.5f, Et.MUT))
            }, -1f, -2f)
            st.put(ui.card(12f, pad = floatArrayOf(14f, 16f, 14f, 16f)).apply {
                put(ui.st(etT(ctx, "Sessions per day", 15f, Et.INK, true), etT(ctx, "Today: ${Et.i(d, "today_count")} booked", 12.5f, Et.MUT)), -1f, -2f)
                put(etRow(ctx, 0f).apply {
                    put(etT(ctx, "The most bookings you take in one day", 12.5f, Et.BODY), 0f, -2f, 1f)
                    put(etRow(ctx, 12f).apply {
                        put(step("−", "Fewer sessions a day") { per = (per - 1).coerceAtLeast(1); render() }, 36f, 36f)
                        put(etT(ctx, "$per", 18f, Et.INK, true).apply { gravity = Gravity.CENTER; minWidth = Ui.dpi(28f) })
                        put(step("+", "More sessions a day") { per = (per + 1).coerceAtMost(14); render() }, 36f, 36f)
                    })
                }, -1f, -2f)
                put(ui.chips(listOf(3, 5, 7, 10).map { n -> ui.chip("$n a day", per == n) { per = n; render() } }), -1f, -2f)
                put(etT(ctx, "When a day reaches this number, your other slots that day show as full. Sessions already booked are not affected.", 11.5f, Et.MUT))
            }, -1f, -2f)
            st.put(ui.card(10f, pad = floatArrayOf(14f, 16f, 14f, 16f)).apply {
                put(etT(ctx, "Session lengths", 15f, Et.INK, true))
                put(ui.chips(listOf(25, 35, 45).map { m -> ui.chip("$m min · ${Et.price(rules, m)} coins", durs.contains(m)) { if (durs.contains(m)) durs.remove(m) else durs.add(m); render() } }), -1f, -2f)
            }, -1f, -2f)
            st.put(etT(ctx, "Members can book you any working day up to 30 days ahead.", 12.5f, Et.MUT))
        }
        save.off = !changed() || busy || days.isEmpty() || durs.isEmpty()
        save.setOnClickListener {
            if (save.off) return@setOnClickListener
            busy = true; save.off = true
            Et.call("availability", JSONObject().put("days", JSONArray(days)).put("durs", JSONArray(durs)).put("per_day", per)) { r ->
                busy = false
                if (disposed) return@call
                if (r.ok) { toast("Saved"); panel.load { et.xgo("dash") } } else { toast(r.error); render() }
            }
        }
        if (save.parent == null) setSticky(save)
    }

    private fun step(s: String, label: String, onClick: () -> Unit): View = etT(ctx, s, 18f, Et.INK, true, 1f).apply {
        gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 11f, 1.5f, Et.FIELD_LINE); contentDescription = label
        setOnClickListener { onClick() }
    }
}

// ═══════════════════════════ my profile ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtXProfile(et: ExpertTalkScreen, private val panel: EtPanel) : EtPage(et) {
    override val rank = 1
    private var busy = false
    override fun back(): Boolean { et.xgo("dash"); return true }

    override fun render() {
        val ex = panel.d?.optJSONObject("expert") ?: return
        val pending = Et.b(ex, "pending_photo")
        val pill = etRow(ctx, 6f).apply {
            background = Tf.box(Color.WHITE, 17f, 1f, Et.LINE)
            setPadding(Ui.dpi(7f), 0, Ui.dpi(12f), 0)
            put(etIcon(ctx, EtIcon.Camera, 15f, Et.P), 15f, 15f)
            put(etT(ctx, if (pending) "Photo in review" else "Change photo", 14f, Et.INK, false, 1f))
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(34f))
            alpha = if (busy || pending) .55f else 1f
            setOnClickListener { if (!busy && !pending) pick() }
        }
        EtProfileBody.fill(this, ex, "expert", onBack = { et.xgo("dash") }, onEdit = { et.xgo("edit") }, headerRight = pill)
    }

    private fun pick() {
        val p = SdSheets.pickImage ?: return
        p { bytes, type ->
            if (bytes == null || disposed) return@p
            busy = true; render()
            PUp.privatePhoto(bytes, type) { uri ->
                if (disposed) return@privatePhoto
                if (uri.isEmpty()) { busy = false; render(); toast(PUp.UPLOAD_ERROR); return@privatePhoto }
                Et.call("photo", JSONObject().put("file", uri)) { r ->
                    busy = false
                    if (disposed) return@call
                    if (r.ok) { toast("New photo sent to True Find for approval"); panel.load { if (!disposed) render() } } else { toast(r.error); render() }
                }
            }
        }
    }
}

// ═══════════════════════════ edit profile ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtXEdit(et: ExpertTalkScreen, private val panel: EtPanel) : EtPage(et) {
    override val rank = 2
    private val ex = panel.d?.optJSONObject("expert") ?: JSONObject()
    private var title = Et.s(ex, "title")
    private val specs = Et.strs(ex.optJSONArray("specs")).toMutableList()
    private val langs = Et.strs(ex.optJSONArray("langs")).toMutableList()
    private var years = Et.i(ex, "years")
    private var qual = Et.s(ex, "qual")
    private var about = Et.s(ex, "about")
    private var busy = false
    private val specBox = FrameLayout(ctx); private val langBox = FrameLayout(ctx)
    private val yearsT = etT(ctx, "$years", 18f, Et.INK, true).apply { gravity = Gravity.CENTER; minWidth = Ui.dpi(24f) }
    private val qualWarn = etT(ctx, "Changing it removes “Checked by True Find” until True Find checks it again.", 11.5f, Ui.c("#7A4A00"))
    private var built = false

    override fun back(): Boolean { et.xgo("profile"); return true }

    override fun render() {
        if (built) return
        built = true
        val ui = et.ui
        rebuild { b ->
            b.put(ui.hdr("Edit profile", false, { et.xgo("profile") }), -1f, -2f)
            val st = ui.stack(16f)
            b.put(st, -1f, -2f)
            st.put(ui.card(10f, row = true, pad = floatArrayOf(12f, 12f, 12f, 12f)).apply {
                put(etIcon(ctx, EtIcon.Briefcase, 17f, Et.P), 17f, 17f)
                put(etT(ctx, EtSpan().add(Et.s(ex, "name"), bold = true).add(" · your verified name can't be changed").build(), 12.5f, Et.INK), 0f, -2f, 1f)
            }, -1f, -2f)
            st.put(lab("Profession", EtField(ctx, "", maxLen = 60).apply { setText(title); onText = { title = it } }), -1f, -2f)
            st.put(lab("Areas you help with (up to 5)", specBox), -1f, -2f)
            st.put(lab("Languages", langBox), -1f, -2f)
            st.put(etRow(ctx, 0f).apply {
                put(etT(ctx, "Years of experience", 13f, Et.BODY, true), 0f, -2f, 1f)
                put(etRow(ctx, 12f).apply {
                    put(step("−") { years = (years - 1).coerceAtLeast(0); yearsT.text = "$years" }, 36f, 36f)
                    put(yearsT)
                    put(step("+") { years = (years + 1).coerceAtMost(50); yearsT.text = "$years" }, 36f, 36f)
                })
            }, -1f, -2f)
            val qf = EtField(ctx, "", maxLen = 120).apply { setText(qual); onText = { qual = it; qualWarn.visibility = if (it != Et.s(ex, "qual") && Et.b(ex, "qual_checked")) View.VISIBLE else View.GONE } }
            qualWarn.visibility = View.GONE
            st.put(etCol(ctx).apply { put(etT(ctx, "Your background", 13f, Et.BODY, true), b = 6f); put(qf, -1f, -2f); put(qualWarn) }, -1f, -2f)
            st.put(lab("About you", etTextArea(ctx, "", 600, 4, about) { about = it }), -1f, -2f)
            fill()
        }
        val save = EtBtn(ctx, "Save profile", icon = EtIcon.Check, iconSize = 17f)
        save.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true; save.off = true
            Et.call("edit_profile", JSONObject().put("title", title).put("specs", JSONArray(specs)).put("langs", JSONArray(langs)).put("years", years).put("qual", qual).put("about", about)) { r ->
                busy = false
                if (disposed) return@call
                save.off = false
                if (r.ok) { toast("Profile saved"); panel.load { et.xgo("profile") } } else toast(r.error)
            }
        }
        setSticky(save)
    }

    private fun lab(label: String, v: View): View = etCol(ctx).apply { put(etT(ctx, label, 13f, Et.BODY, true), b = 6f); put(v, -1f, -2f) }
    private fun step(s: String, onClick: () -> Unit): View = etT(ctx, s, 18f, Et.INK, true, 1f).apply { gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 11f, 1.5f, Et.FIELD_LINE); setOnClickListener { onClick() } }

    private fun fill() {
        val ui = et.ui
        val r = panel.d?.optJSONObject("rules")
        fun tog(l: MutableList<String>, v: String, max: Int) { if (l.contains(v)) l.remove(v) else if (l.size < max) l.add(v) }
        specBox.removeAllViews(); langBox.removeAllViews()
        specBox.addView(ui.chips(Et.strs(r?.optJSONArray("specs")).map { s -> ui.chip(Et.topicName(s), specs.contains(s)) { tog(specs, s, 5); fill() } }))
        langBox.addView(ui.chips(Et.strs(r?.optJSONArray("langs")).map { s -> ui.chip(s, langs.contains(s)) { tog(langs, s, 6); fill() } }))
    }
}

@Suppress("unused") private fun keepX(g: GradientDrawable) = g
