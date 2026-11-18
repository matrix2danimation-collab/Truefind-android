package app.truefind.android

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToLong

/** Reports (ReportsTab.jsx): members' reports about people and live rooms — read the chat, dismiss, warn, ban. */
class AdReports(a: AdminScreen) : AdTab(a) {
    private var status = "open"
    private var busy = ""
    private val load = AdLoad(this, "adminUsers", { JSONObject().put("action", "reports").put("status", status).put("limit", 100) }) { render() }

    override fun build() { render(); load.load() }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        root.addView(AU.filters(ctx, listOf(Triple("open", "Open", 0), Triple("reviewed", "Acted on", 0), Triple("dismissed", "Dismissed", 0), Triple("all", "All", 0)), status) { status = it; render(); load.load() })
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        val all = (AF.list(d, "reports") + AF.list(d, "room_reports")).sortedByDescending { AF.ms(AF.s(it, "created_date")) }
        if (all.isEmpty()) { root.addView(AU.empty(ctx, AdIc.Flag, "No reports here", if (status == "open") "Members' reports about people and live rooms show up here." else "")); return }
        all.forEachIndexed { i, r -> root.addView(card(r), AU.lp(-1, -2, t = if (i > 0) 12f else 0f)) }
    }

    private fun card(r: JSONObject): LinearLayout {
        val c = AU.card(ctx)
        val rep = AF.o(r, "reported"); val by = AF.o(r, "reporter")
        val pills = FlowRow(ctx, Ui.dpi(6f), Ui.dpi(6f))
        pills.addView(AU.pill(ctx, AF.s(r, "status")))
        if (AF.s(r, "kind") == "room") pills.addView(AU.pill(ctx, "x", "Live room"))
        if (AF.s(r, "call_id").isNotEmpty()) pills.addView(AU.pill(ctx, "x", "From a call", AK.tint, AK.deep))
        pills.addView(AU.pill(ctx, "x", AF.s(r, "reason").ifEmpty { "Other" }, AK.redBg, AK.red))
        val times = AF.n(r, "reports_against")
        if (times > 1) pills.addView(AU.pill(ctx, "x", "Reported ${AF.nf(times)} times", AK.black, Color.WHITE))
        val head = AU.row(ctx, Gravity.TOP)
        head.addView(pills, AU.lp(0, -2, 1f, r = 6f))
        head.addView(AU.t(ctx, AF.fmt(AF.s(r, "created_date")), 11.5f, AK.faint), AU.lp(-2, -2, t = 4f))
        c.addView(head, AU.lp(-1, -2, b = 10f))
        val w = AF.n(rep, "warnings").toInt()
        val sub = listOf(AF.s(rep, "gender"), AF.s(rep, "age").let { if (it.isNotEmpty()) "$it yrs" else "" }, AF.s(rep, "city"), if (AF.b(rep, "banned")) "BANNED" else "", if (w > 0) "$w warning${if (w > 1) "s" else ""}" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        c.addView(AU.person(ctx, src, rep, 46f, sub, { a.openMember(AF.s(rep, "user_id")) }))
        val det = AF.s(r, "details")
        if (det.isNotEmpty()) c.addView(AU.note(ctx, det, AK.soft, AK.ink, 13.5f).apply { setLineSpacing(0f, 1.2f) }, AU.lp(-1, -2, t = 10f))
        val ev = AF.strs(r, "evidence_uris")
        if (ev.isNotEmpty()) {
            val row = AU.row(ctx)
            ev.forEachIndexed { i, u -> row.addView(AdImg(ctx, src, false, 76f, 96f).also { it.set(u); it.onOpen = { x -> a.zoom(x) } }, LinearLayout.LayoutParams(Ui.dpi(76f), Ui.dpi(96f)).apply { if (i > 0) leftMargin = Ui.dpi(8f) }) }
            c.addView(row, AU.lp(-1, -2, t = 10f))
        }
        val byLine = AU.row(ctx)
        byLine.addView(AU.t(ctx, "Reported by ", 12.5f, AK.sub))
        byLine.addView(AU.t(ctx, AF.s(by, "name").ifEmpty { "a member" }, 12.5f, AK.deep, true).apply { setOnClickListener { a.openMember(AF.s(by, "user_id")) } })
        if (AF.s(r, "kind") == "room") byLine.addView(AU.t(ctx, " in a live room", 12.5f, AK.sub))
        c.addView(byLine, AU.lp(-1, -2, t = 10f))
        val st = AF.s(r, "status")
        val res = AF.s(r, "resolution"); val note = AF.s(r, "admin_note")
        if (st != "open" && (res.isNotEmpty() || note.isNotEmpty())) {
            val rb = AF.s(r, "reviewed_by")
            c.addView(AU.t(ctx, AU.boldLeadAfter("Decision: ", res.ifEmpty { st }, "${if (note.isNotEmpty()) " — $note" else ""}${if (rb.isNotEmpty()) " · $rb" else ""}", AK.sub), 12f, AK.sub), AU.lp(-1, -2, t = 6f))
        }
        val b = busy == AF.s(r, "id")
        val btns = ArrayList<View>()
        if (AF.s(r, "match_id").isNotEmpty()) btns.add(AU.btn(ctx, "Read chat", "tint", true, AdIc.MessageSquareText) { chat(AF.s(r, "id")) })
        if (st == "open") {
            btns.add(AU.btn(ctx, "Dismiss", "light", true, AdIc.XCircle) { resolve(r, "dismiss", "") }.also { it.busy = b })
            btns.add(AU.btn(ctx, "Warn", "dark", true, AdIc.AlertTriangle) { ask(r, "warn") }.also { it.off = b })
            btns.add(AU.btn(ctx, "Ban", "danger", true, AdIc.Ban) { ask(r, "ban") }.also { it.off = b || AF.b(rep, "banned") })
            if (AF.b(rep, "creator")) btns.add(AU.btn(ctx, "Remove creator", "dangerLight", true, AdIc.ShieldOff) { ask(r, "remove_creator") }.also { it.off = b })
        }
        if (btns.isNotEmpty()) c.addView(AU.wrap(ctx, 8f, *btns.toTypedArray()), AU.lp(-1, -2, t = 12f))
        return c
    }

    private fun resolve(r: JSONObject, decision: String, reason: String, done: ((Boolean) -> Unit)? = null) {
        busy = AF.s(r, "id"); render()
        src.fn("adminUsers", JSONObject().put("action", "resolve_report").put("kind", AF.s(r, "kind")).put("report_id", AF.s(r, "id")).put("decision", decision).put("reason", reason)) { x ->
            busy = ""
            if (x.ok) {
                val ce = AF.n(x.data, "calls_ended")
                say((mapOf("dismiss" to "Dismissed", "warn" to "Warning sent ✓", "ban" to "Banned ✓", "remove_creator" to "Creator removed")[decision] ?: "Done") + if (ce > 0) " · ${AF.nf(ce)} call ended" else "")
                done?.invoke(true)
                load.load(); a.changed()
            } else { say(AF.err(x), true); done?.invoke(false); render() }
        }
    }

    private fun ask(r: JSONObject, decision: String) {
        val name = AF.s(AF.o(r, "reported"), "name").ifEmpty { "this member" }
        AdAsk(a,
            mapOf("warn" to "Send a warning", "ban" to "Ban $name?", "remove_creator" to "Remove creator status")[decision]!!,
            mapOf("warn" to "They get this warning in Notifications. The reporter is thanked.", "ban" to "Blocked from the whole app at once; their running call and live are ended. Their other open reports close too.", "remove_creator" to "Her earnings stop; she is told the reason.")[decision],
            if (decision == "ban") listOf("Fake profile", "Harassment", "Asking for money / scam", "Nudity or sexual content", "Underage") else listOf("Rude or abusive language", "Asking for money", "Sharing contact details", "Fake photos"),
            if (decision == "ban") "Reason (saved with the ban)" else "Reason (they see this)",
            mapOf("warn" to "Send warning", "ban" to "Ban", "remove_creator" to "Remove creator")[decision]!!,
            decision != "warn") { reason, done -> resolve(r, decision, reason, done) }.open()
    }

    /** the chat between the two people of a report (the reporter's messages on the right) */
    private fun chat(reportId: String) {
        val sh = AdSheet(a.layer, "Chat in this report")
        sh.body.addView(AU.loading(ctx))
        fun fill(r: Api.Result) {
            sh.body.removeAllViews()
            if (!r.ok) { sh.body.addView(AU.errorLine(ctx, AF.err(r, "Could not load. Please try again.")) { sh.body.removeAllViews(); sh.body.addView(AU.loading(ctx)); src.fn("adminUsers", JSONObject().put("action", "report_chat").put("report_id", reportId)) { x -> if (!sh.closed) fill(x) } }); return }
            val d = r.data
            val msgs = AF.list(d, "messages")
            if (msgs.isEmpty()) { sh.body.addView(AU.t(ctx, AF.s(d, "note").ifEmpty { "No messages." }, 13f, AK.sub)); return }
            val reporterId = AF.s(d, "reporter_id")
            msgs.forEachIndexed { i, m ->
                val mine = AF.s(m, "from") == reporterId
                val bubble = AU.col(ctx).apply {
                    background = Tf.box(if (mine) AK.tint else AK.soft, 13f, 1f, if (mine) AK.tintLine else AK.line)
                    setPadding(Ui.dpi(11f), Ui.dpi(8f), Ui.dpi(11f), Ui.dpi(8f))
                    addView(AU.t(ctx, AF.s(m, "from_name").ifEmpty { if (mine) "Reporter" else "Reported" }, 11.5f, if (mine) AK.deep else AK.red, true))
                    val txt = AF.s(m, "text").ifEmpty { if (AF.s(m, "gif_url").isNotEmpty()) "[GIF]" else if (AF.s(m, "media_uri").isNotEmpty()) "[photo]" else "[${AF.s(m, "kind")}]" }
                    addView(AU.t(ctx, txt, 13.5f, AK.ink), AU.lp(-2, -2, t = 2f))
                    addView(AU.t(ctx, AF.fmt(AF.s(m, "at")), 10.5f, AK.faint), AU.lp(-2, -2, t = 2f))
                }
                val line = AU.bubbleLine(ctx, bubble, mine)
                sh.body.addView(line, AU.lp(-1, -2, t = if (i > 0) 8f else 0f))
            }
        }
        sh.show()
        src.fn("adminUsers", JSONObject().put("action", "report_chat").put("report_id", reportId)) { r -> if (!sh.closed) fill(r) }
    }

    override fun previewOpen(action: String) {
        val first = (AF.list(load.data, "reports") + AF.list(load.data, "room_reports")).firstOrNull() ?: return
        when (action) { "chat" -> chat(AF.s(first, "id")); "ban" -> ask(first, "ban") }
    }
}

/** Experts (ExpertsTab.jsx): Expert Talk applications, reports, photos, leave requests and all experts. */
class AdExperts(a: AdminScreen) : AdTab(a) {
    private var view = "apps"
    private var busy = ""
    private val load = AdLoad(this, "expertTalk", { JSONObject().put("action", "admin_list") }) { render() }
    private val DECISIONS = mapOf(
        "expert_report" to listOf(Triple("block_user", "Block the member", "danger"), Triple("warn_user", "Warn the member", "dark"), Triple("unfair", "Report was unfair · refund member", "light")),
        "user_report" to listOf(Triple("warn_expert", "Warn the expert", "dark"), Triple("refund_user", "Refund the member", "danger"), Triple("dismiss", "Dismiss", "light")),
    )

    override fun build() { render(); load.load() }

    private fun run(key: String, payload: JSONObject, ok: String, after: (() -> Unit)? = null) {
        busy = key; render()
        src.fn("expertTalk", payload) { r ->
            busy = ""
            if (r.ok) { say(ok); load.load(); a.changed() } else { say(AF.err(r), true); render() }
            after?.invoke()
        }
    }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val apps = AF.list(d, "applications"); val cases = AF.list(d, "cases"); val experts = AF.list(d, "experts")
        val photos = experts.filter { AF.s(it, "pending_photo").isNotEmpty() }; val leaves = experts.filter { AF.s(it, "leave_status") == "pending" }
        root.addView(AU.filters(ctx, listOf(Triple("apps", "Applications", apps.size), Triple("cases", "Reports", cases.size), Triple("photos", "Photos", photos.size), Triple("leave", "Leaving", leaves.size), Triple("experts", "Experts", 0)), view) { view = it; render() })
        val list = AU.col(ctx)
        fun add(v: View) { list.addView(v, AU.lp(-1, -2, t = if (list.childCount > 0) 10f else 0f)) }
        when (view) {
            "apps" -> if (apps.isEmpty()) add(AU.empty(ctx, AdIc.Headphones, "No applications", "Verified creators who apply to become experts show here.")) else apps.forEach { add(appCard(it)) }
            "cases" -> if (cases.isEmpty()) add(AU.empty(ctx, AdIc.ShieldAlert, "No open reports", "Expert Talk reports from experts and members show here.")) else cases.forEach { add(caseCard(it)) }
            "photos" -> if (photos.isEmpty()) add(AU.empty(ctx, AdIc.Image, "No photos waiting")) else photos.forEach { add(photoCard(it)) }
            "leave" -> if (leaves.isEmpty()) add(AU.empty(ctx, AdIc.LogOut, "No leave requests")) else leaves.forEach { add(leaveCard(it)) }
            else -> if (experts.isEmpty()) add(AU.empty(ctx, AdIc.Users, "No experts yet")) else experts.forEach { add(expertCard(it)) }
        }
        root.addView(list, AU.lp(-1, -2))
    }

    private fun titleRow(title: String, right: View?): LinearLayout = AU.row(ctx).apply {
        addView(AU.t(ctx, title, 15f, AK.ink, true), AU.lp(0, -2, 1f, r = 8f))
        if (right != null) addView(right)
    }

    private fun appCard(x: JSONObject): LinearLayout {
        val id = AF.s(x, "id"); val zoomSet = AF.s(x, "zoom_at").isNotEmpty()
        val c = AU.card(ctx)
        c.addView(titleRow(AF.s(x, "name"), AU.pill(ctx, if (AF.s(x, "status") == "zoom_set") "approved" else "pending", if (AF.s(x, "status") == "zoom_set") "Interview set" else "New")))
        c.addView(AU.t(ctx, "${AF.s(x, "title")} · ${AF.s(x, "years")} yr · ${AF.strs(x, "langs").joinToString(", ")}", 13f, AK.sub), AU.lp(-1, -2, t = 2f, b = 8f))
        val phone = AF.s(x, "phone")
        c.addView(AU.rows(ctx, 2f,
            AU.kvView(ctx, "Phone (only you see it)", AU.t(ctx, phone.ifEmpty { "—" }, 13f, AK.purple, true).apply { setOnClickListener { if (phone.isNotEmpty()) a.dial(phone) } }),
            AU.kv(ctx, "Helps with", AF.strs(x, "specs").joinToString(", ")),
            AU.kv(ctx, "Qualification", AF.s(x, "qual")),
            AU.kv(ctx, "Sessions", AF.strs(x, "durs").joinToString(", ") { "$it min" }),
            AU.kv(ctx, "Applied", AF.fmt(AF.s(x, "created_date"))),
            if (zoomSet) AU.kv(ctx, "Zoom interview", AF.fmt(AF.s(x, "zoom_at")), true) else null))
        val about = AF.s(x, "about")
        if (about.isNotEmpty()) c.addView(AU.t(ctx, about, 13f, AK.ink), AU.lp(-1, -2, t = 8f))
        c.addView(AU.wrap(ctx, 8f,
            AU.btn(ctx, "Call", "light", true, AdIc.Phone, 14f) { a.dial(phone) },
            AU.btn(ctx, if (zoomSet) "Change Zoom time" else "Set Zoom interview", "tint", true, AdIc.Video, 14f) { zoomSheet(x) },
            AU.btn(ctx, "Approve", "green", true, AdIc.CheckCircle2, 14f) {
                note("Approve ${AF.s(x, "name")} as an expert?", "Her account becomes Expert Talk only (no other calls, video calls or games).", "Approve", false) { n -> run("ok$id", JSONObject().put("action", "admin_app").put("id", id).put("decision", "approve").put("note", n), "Approved — she is now an expert") }
            }.also { it.busy = busy == "ok$id"; it.off = !zoomSet },
            AU.btn(ctx, "Reject", "dangerLight", true, AdIc.XCircle, 14f) {
                note("Reject ${AF.s(x, "name")}?", "She sees your note and can apply again later.", "Reject", true) { n -> run("no$id", JSONObject().put("action", "admin_app").put("id", id).put("decision", "reject").put("note", n), "Rejected") }
            }.also { it.busy = busy == "no$id" },
        ), AU.lp(-1, -2, t = 12f))
        return c
    }

    private fun caseCard(x: JSONObject): LinearLayout {
        val id = AF.s(x, "id"); val kind = AF.s(x, "kind"); val bk = AF.o(x, "booking")
        val c = AU.card(ctx)
        c.addView(titleRow(if (kind == "expert_report") "Expert reported a member (call ended)" else "Member reported an expert", AU.pill(ctx, "open")))
        c.addView(AU.rows(ctx, 2f,
            AU.kv(ctx, "Expert", AF.s(bk, "expert_name")),
            AU.kv(ctx, "Member", "${AF.s(bk, "user_alias")} (${AF.s(x, "user_id")})"),
            AU.kv(ctx, "Session", "${AF.s(bk, "when")} · ${AF.s(bk, "dur")} min · ${AF.s(bk, "coins")} coins"),
            AU.kv(ctx, "Talked", AF.dur(AF.n(bk, "talk_seconds")))), AU.lp(-1, -2, t = 6f))
        if (kind == "expert_report") {
            val reports = AU.col(ctx).apply { background = Tf.box(AK.redBg, 10f); setPadding(Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(8f)) }
            reports.addView(AU.t(ctx, "Expert's reports", 11.5f, AK.red, true))
            AF.strs(x, "items").forEachIndexed { i, t -> reports.addView(AU.t(ctx, "${i + 1}. $t", 13f, AK.ink), AU.lp(-1, -2, t = 3f)) }
            c.addView(reports, AU.lp(-1, -2, t = 10f))
            val side = AU.col(ctx).apply { background = Tf.box(AK.soft, 10f, 1f, AK.line); setPadding(Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(8f)) }
            side.addView(AU.t(ctx, "Member's side", 11.5f, AK.deep, true))
            side.addView(AU.t(ctx, AF.s(x, "user_text").ifEmpty { if (AF.b(x, "user_side_skipped")) "The member chose not to write anything." else "Not written yet." }, 13f, AK.ink), AU.lp(-1, -2, t = 3f))
            c.addView(side, AU.lp(-1, -2, t = 8f))
            c.addView(AU.warn(ctx, "The expert's share is on hold until you decide.", "amber"), AU.lp(-1, -2, t = 8f))
        } else {
            val side = AU.col(ctx).apply { background = Tf.box(AK.soft, 10f, 1f, AK.line); setPadding(Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(8f)) }
            side.addView(AU.t(ctx, "Member's report", 11.5f, AK.deep, true))
            side.addView(AU.t(ctx, AF.s(x, "user_text"), 13f, AK.ink), AU.lp(-1, -2, t = 3f))
            c.addView(side, AU.lp(-1, -2, t = 10f))
        }
        val btns = (DECISIONS[kind] ?: emptyList()).map { (d, l, k) ->
            AU.btn(ctx, l, k, true, if (d == "block_user") AdIc.Ban else null, 14f) {
                note(l, "Add a short note (optional).", "Confirm", k == "danger") { n -> run("$id$d", JSONObject().put("action", "admin_case").put("id", id).put("decision", d).put("note", n), "Decision saved") }
            }.also { it.busy = busy == "$id$d" }
        }
        c.addView(AU.wrap(ctx, 8f, *btns.toTypedArray()), AU.lp(-1, -2, t = 12f))
        return c
    }

    private fun face(raw: String, size: Float, ring: Boolean) = FrameLayout(ctx).apply {
        val img = AdImg(ctx, src, true, size, size)
        img.set(raw)
        if (ring) { background = Tf.circle(Color.TRANSPARENT, 2f, AK.purple); val p = Ui.dpi(2f); setPadding(p, p, p, p) }
        addView(img, FrameLayout.LayoutParams(-1, -1))
    }

    private fun photoCard(e: JSONObject): LinearLayout {
        val id = AF.s(e, "id")
        val c = AU.card(ctx)
        c.addView(AU.t(ctx, AF.s(e, "name"), 15f, AK.ink, true))
        val r = AU.row(ctx)
        r.addView(AU.col(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(face(AF.s(e, "photo"), 84f, false), LinearLayout.LayoutParams(Ui.dpi(84f), Ui.dpi(84f))); addView(AU.t(ctx, "Now", 12f, AK.sub), AU.lp(-2, -2, t = 4f)) })
        r.addView(AU.t(ctx, "→", 14f, AK.faint), AU.lp(-2, -2, l = 14f, r = 14f))
        r.addView(AU.col(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(face(AF.s(e, "pending_photo"), 84f, true), LinearLayout.LayoutParams(Ui.dpi(84f), Ui.dpi(84f))); addView(AU.t(ctx, "New", 12f, AK.deep, true), AU.lp(-2, -2, t = 4f)) })
        c.addView(r, AU.lp(-1, -2, t = 10f))
        c.addView(AU.btnRow(ctx, 8f, false,
            AU.btn(ctx, "Approve", "green", true, AdIc.CheckCircle2, 14f) { run("pa$id", JSONObject().put("action", "admin_photo").put("id", id).put("approve", true), "Photo approved") }.also { it.busy = busy == "pa$id" },
            AU.btn(ctx, "Reject", "dangerLight", true, AdIc.XCircle, 14f) { run("pr$id", JSONObject().put("action", "admin_photo").put("id", id).put("approve", false), "Photo rejected") }.also { it.busy = busy == "pr$id" }), AU.lp(-1, -2, t = 12f))
        return c
    }

    private fun leaveCard(e: JSONObject): LinearLayout {
        val id = AF.s(e, "id"); val name = AF.s(e, "name"); val left = AF.n(e, "earned_available")
        val c = AU.card(ctx)
        c.addView(AU.t(ctx, "$name wants to leave Expert Talk", 15f, AK.ink, true))
        c.addView(AU.rows(ctx, 2f,
            AU.kv(ctx, "Reason", AF.s(e, "leave_reason")),
            AU.kv(ctx, "Asked", AF.fmt(AF.s(e, "leave_at"))),
            AU.kv(ctx, "Earnings not withdrawn", "₹${AF.nf(left)}", true)), AU.lp(-1, -2, t = 6f))
        if (left > 0) c.addView(AU.warn(ctx, "She still has earnings. Pay her out before accepting: after leaving she is no longer a creator.", "amber"), AU.lp(-1, -2, t = 8f))
        c.addView(AU.btnRow(ctx, 8f, false,
            AU.btn(ctx, "Accept", "dark", true) { note("Accept $name's request?", "Her booked sessions are refunded to the members, and her account becomes a normal member account (not a creator).", "Accept", true) { _ -> run("la$id", JSONObject().put("action", "admin_leave").put("id", id).put("accept", true), "Accepted — she is a normal member now") } }.also { it.busy = busy == "la$id" },
            AU.btn(ctx, "Decline", "light", true) { note("Decline the request?", "Tell her why (optional).", "Decline", false) { n -> run("ld$id", JSONObject().put("action", "admin_leave").put("id", id).put("accept", false).put("note", n), "Declined") } }.also { it.busy = busy == "ld$id" }), AU.lp(-1, -2, t = 12f))
        return c
    }

    private fun expertCard(e: JSONObject): LinearLayout {
        val id = AF.s(e, "id"); val paused = AF.b(e, "admin_paused")
        val c = AU.card(ctx)
        val top = AU.row(ctx)
        if (AF.s(e, "photo").isNotEmpty()) top.addView(face(AF.s(e, "photo"), 46f, false), AU.lp(Ui.dpi(46f), Ui.dpi(46f), r = 12f))
        val tc = AU.col(ctx)
        tc.addView(AU.t(ctx, AF.s(e, "name"), 15f, AK.ink, true))
        val reviews = AF.n(e, "reviews")
        tc.addView(AU.t(ctx, "${AF.s(e, "title")} · ${AF.s(e, "sessions").ifEmpty { "0" }} sessions · ${AF.s(e, "hours").ifEmpty { "0" }} h · ${if (reviews > 0) "${AF.s(e, "rating")}★ (${AF.nf(reviews)})" else "no reviews"}", 12.5f, AK.sub))
        top.addView(tc, AU.lp(0, -2, 1f, r = 8f))
        top.addView(AU.pill(ctx, if (paused) "rejected" else if (AF.b(e, "accepting")) "approved" else "dismissed", if (paused) "Off by True Find" else if (AF.b(e, "accepting")) "Taking bookings" else "Paused"))
        c.addView(top)
        c.addView(AU.rows(ctx, 2f,
            AU.kv(ctx, "On time / first ring / missed", "${AF.s(e, "on_time").ifEmpty { "0" }}% / ${AF.s(e, "first_ring").ifEmpty { "0" }}% / ${AF.s(e, "missed").ifEmpty { "0" }}"),
            AU.kv(ctx, "Missed-session warnings", "${AF.s(e, "warnings").ifEmpty { "0" }} of 4"),
            AU.kv(ctx, "Warnings from member reports", AF.s(e, "user_warnings").ifEmpty { "0" }),
            AU.kv(ctx, "Earnings not withdrawn", "₹${AF.nf(AF.n(e, "earned_available"))}"),
            if (paused) AU.kv(ctx, "Why off", AF.s(e, "paused_reason")) else null), AU.lp(-1, -2, t = 8f))
        val btn = if (paused) AU.btn(ctx, "Allow bookings again", "green", true) { run("up$id", JSONObject().put("action", "admin_pause").put("id", id).put("paused", false), "Bookings allowed again") }.also { it.busy = busy == "up$id" }
        else AU.btn(ctx, "Switch bookings off", "dangerLight", true) { note("Switch ${AF.s(e, "name")}'s bookings off?", "Her profile is hidden until you allow bookings again.", "Switch off", true) { n -> run("dp$id", JSONObject().put("action", "admin_pause").put("id", id).put("paused", true).put("reason", n), "Bookings switched off") } }.also { it.busy = busy == "dp$id" }
        c.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(10f) })
        return c
    }

    /** the web's small "ask" sheet of this tab: text, an optional note, the action and Cancel */
    private fun note(title: String, text: String, label: String, danger: Boolean, go: (String) -> Unit) {
        val sh = AdSheet(a.layer, title)
        sh.body.addView(AU.t(ctx, text, 13f, AK.sub), AU.lp(-1, -2, b = 10f))
        val area = AU.area(ctx, "Note (optional)", 2)
        sh.body.addView(area, AU.lp(-1, -2))
        sh.body.addView(AU.btnRow(ctx, 8f, false,
            AU.btn(ctx, label, if (danger) "danger" else "dark") { val n = area.value; sh.close(); go(n) },
            AU.btn(ctx, "Cancel", "light") { sh.close() }), AU.lp(-1, -2, t = 12f))
        sh.show()
    }

    /** Zoom video interview: the date and time picked on the phone, saved for her */
    private fun zoomSheet(x: JSONObject) {
        var at: Calendar? = null
        val sh = AdSheet(a.layer, "Zoom video interview")
        sh.body.addView(AU.t(ctx, "Call ${AF.s(x, "name")} on ${AF.s(x, "phone")}, agree a time, then save it here. She is told the time in the app; send her the Zoom link on her phone.", 13f, AK.sub), AU.lp(-1, -2, b = 10f))
        val field = AU.t(ctx, "dd/mm/yyyy, --:--", 14f, AK.faint).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Tf.box(Color.WHITE, 12f, 1.5f, AK.line); setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            compoundDrawablePadding = Ui.dpi(8f); setCompoundDrawablesWithIntrinsicBounds(null, null, Tf.lu(Lu.CalendarDays, AK.ink, 16f), null)
        }
        val save = AU.btn(ctx, "Save", "purple").also { it.off = true }
        field.setOnClickListener {
            val now = at ?: Calendar.getInstance()
            DatePickerDialog(a.context, { _, y, m, d ->
                TimePickerDialog(a.context, { _, h, min ->
                    at = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }
                    field.text = java.text.SimpleDateFormat("dd/MM/yyyy, hh:mm a", java.util.Locale("en", "IN")).format(at!!.time).replace("AM", "am").replace("PM", "pm")
                    field.setTextColor(AK.ink)
                    save.off = false
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }
        sh.body.addView(field, AU.lp(-1, Ui.dpi(42f)))
        save.setOnClickListener {
            val t = at ?: return@setOnClickListener
            if (save.busy) return@setOnClickListener
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(t.time)
            save.busy = true
            run("zoom", JSONObject().put("action", "admin_zoom").put("id", AF.s(x, "id")).put("at", iso), "Interview time saved") { sh.close() }
        }
        sh.body.addView(AU.btnRow(ctx, 8f, false, save, AU.btn(ctx, "Cancel", "light") { sh.close() }), AU.lp(-1, -2, t = 12f))
        sh.show()
    }

    override fun previewOpen(action: String) {
        when (action) { "cases" -> { view = "cases"; render() }; "photos" -> { view = "photos"; render() }; "leave" -> { view = "leave"; render() }; "experts" -> { view = "experts"; render() } }
    }
}

/** Live (LiveTab.jsx): calls and lives right now (every 10 s), end one, and call bills that need a check. */
class AdLive(a: AdminScreen) : AdTab(a) {
    private val main = Handler(Looper.getMainLooper())
    private val load = AdLoad(this, "adminOps", { JSONObject().put("action", "live") }) { render() }
    private var reviewed = false
    private val bills = AdLoad(this, "adminOps", { JSONObject().put("action", "billing_issues").put("reviewed", reviewed) }) { renderBills() }
    private val callsBox = AU.col(ctx)
    private val billBox = AU.col(ctx)
    private var visible = true
    private val poll = object : Runnable { override fun run() { if (alive && visible) load.load(); main.postDelayed(this, 10_000) } }
    private val tick = object : Runnable { override fun run() { if (alive) { updateClocks(); main.postDelayed(this, 1000) } } }
    private val clocks = ArrayList<Pair<android.widget.TextView, JSONObject>>()

    override fun build() {
        root.addView(callsBox, AU.lp(-1, -2))
        root.addView(billBox, AU.lp(-1, -2))
        render(); load.load(); bills.load()
        main.postDelayed(poll, 10_000)
        main.post(tick)
    }
    override fun onVisible(v: Boolean) { visible = v }
    override fun destroy() { super.destroy(); main.removeCallbacksAndMessages(null) }

    private fun render() {
        if (!alive) return
        callsBox.removeAllViews(); clocks.clear()
        callsBox.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        val calls = AF.list(d, "calls")
        val rooms = AF.list(d, "rooms") + AF.list(d, "dance")
        callsBox.addView(AU.section(ctx, "Calls now · ${calls.size}", AU.t(ctx, "updates every 10 s", 11.5f, AK.faint)).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 })
        if (load.loading && d == null) { callsBox.addView(AU.loading(ctx)); return }
        if (calls.isEmpty()) callsBox.addView(AU.empty(ctx, AdIc.Phone, "No calls right now"))
        else callsBox.addView(AU.listCard(ctx, calls.map { callRow(it) }))
        callsBox.addView(AU.section(ctx, "Lives now · ${rooms.size}"))
        if (d != null) {
            if (rooms.isEmpty()) callsBox.addView(AU.empty(ctx, AdIc.Radio, "No live rooms right now"))
            else callsBox.addView(AU.listCard(ctx, rooms.map { roomRow(it) }))
        }
        updateClocks()
    }

    private fun callRow(c: JSONObject): LinearLayout {
        val stuck = AF.b(c, "stuck")
        val r = AU.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(11f), Ui.dpi(12f), Ui.dpi(11f)) }
        r.addView(AU.tile(ctx, if (AF.s(c, "type") == "video") AdIc.Video else AdIc.Phone, if (stuck) AK.amberBg else AK.tint, if (stuck) AK.amber else AK.deep, 34f, 10f, 16f))
        val col = AU.col(ctx)
        val names = AU.row(ctx)
        val caller = AF.o(c, "caller"); val callee = AF.o(c, "callee")
        names.addView(AU.t(ctx, AF.s(caller, "name").ifEmpty { "Member" }, 13.5f, AK.ink, true).ell().apply { setOnClickListener { a.openMember(AF.s(caller, "id")) } }, AU.lp(-2, -2))
        names.addView(AU.t(ctx, " → ", 13.5f, AK.faint))
        names.addView(AU.t(ctx, AF.s(callee, "name").ifEmpty { "Member" }, 13.5f, AK.ink, true).ell().apply { setOnClickListener { a.openMember(AF.s(callee, "id")) } }, AU.lp(-2, -2))
        col.addView(names)
        val line = AU.t(ctx, "", 12f, if (stuck) AK.amber else AK.sub)
        clocks.add(line to c)
        col.addView(line, AU.lp(-1, -2, t = 1f))
        r.addView(col, AU.lp(0, -2, 1f, l = 10f, r = 10f))
        r.addView(AU.pill(ctx, if (AF.s(c, "status") == "ringing") "ringing" else "active"), AU.lp(-2, -2, r = 10f))
        r.addView(AU.btn(ctx, "End", "dangerLight", true, AdIc.PhoneOff, 14f) { end("call", c) })
        return r
    }

    private fun updateClocks() {
        for ((v, c) in clocks) {
            val started = AF.ms(AF.s(c, "started_at"))
            val secs = if (started > 0) max(0L, ((System.currentTimeMillis() - started) / 1000.0).roundToLong()) else 0L
            val stuck = AF.b(c, "stuck")
            v.text = (if (AF.s(c, "status") == "ringing") "Ringing · ${AF.ago(AF.s(c, "created_date"))}" else "${AF.dur(secs)} · ${AF.nf(AF.n(c, "rate"))} coins/min") +
                (if (stuck) " · phones quiet ${Math.round(AF.n(c, "quiet_seconds") / 60)} min (jobs will end it)" else "")
        }
    }

    private fun roomRow(r: JSONObject): LinearLayout {
        val row = AU.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(11f), Ui.dpi(12f), Ui.dpi(11f)) }
        row.addView(AdImg(ctx, src, true, 36f, 36f).also { it.set(AF.s(r, "host_photo")) }, LinearLayout.LayoutParams(Ui.dpi(36f), Ui.dpi(36f)))
        val col = AU.col(ctx)
        val dance = AF.s(r, "kind") == "dance"
        col.addView(AU.t(ctx, AF.s(r, "name").ifEmpty { if (dance) "Dance live" else "Live room" }, 13.5f, AK.ink, true).ell())
        val viewers = AF.n(r, "viewers")
        col.addView(AU.t(ctx, "${AF.s(r, "host_name").ifEmpty { "Host" }} · live ${AF.ago(AF.s(r, "since"))}${if (viewers > 0) " · ${AF.nf(viewers)} watching" else ""}${if (AF.b(r, "demo")) " · demo" else ""}", 12f, AK.sub).apply {
            compoundDrawablePadding = Ui.dpi(4f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(if (dance) AdIc.Music2 else AdIc.Radio, AK.sub, 11f), null, null, null)
        }.ell())
        row.addView(col, AU.lp(0, -2, 1f, l = 10f, r = 10f))
        row.addView(AU.pill(ctx, "live"), AU.lp(-2, -2, r = 10f))
        row.addView(AU.btn(ctx, "End live", "dangerLight", true) { end("room", r) })
        return row
    }

    private fun end(kind: String, item: JSONObject) {
        val call = kind == "call"
        AdConfirm(a, if (call) "End this call now?" else "End this live now?",
            if (call) "The call stops for both people. It is billed for the time already talked, like a normal hang-up." else "The live closes for the host and everyone watching — exactly as if the host ended it.",
            if (call) "End call" else "End live", true) { done ->
            val body = if (call) JSONObject().put("action", "call_end").put("call_id", AF.s(item, "id")) else JSONObject().put("action", "room_end").put("kind", AF.s(item, "kind")).put("room_id", AF.s(item, "id"))
            src.fn("adminOps", body) { r ->
                if (r.ok) {
                    if (call) { val ch = AF.n(r.data, "charged"); say(if (AF.b(r.data, "billing_problem")) "Call ended — billing needs a check (see below)" else "Call ended ✓${if (ch > 0) " · ${AF.coins(ch)} coins billed" else ""}") }
                    else say("Live ended ✓")
                    done(true); load.load(); bills.load(); a.changed()
                } else { say(AF.err(r), true); done(false) }
            }
        }.open()
    }

    // ── call bills to check ──
    private fun renderBills() {
        if (!alive) return
        billBox.removeAllViews()
        billBox.addView(AU.section(ctx, "Call bills to check", AU.link(ctx, if (reviewed) "Hide checked" else "Show checked too") { reviewed = !reviewed; renderBills(); bills.load() }))
        billBox.addView(AU.errorLine(ctx, bills.error) { bills.load() })
        val d = bills.data
        if (bills.loading && d == null) { billBox.addView(AU.loading(ctx, 20f)); return }
        val list = AF.list(d, "calls")
        if (list.isEmpty()) {
            val c = AU.card(ctx)
            val r = AU.row(ctx)
            r.addView(AU.icon(ctx, AdIc.CheckCircle2, AK.green, 18f))
            r.addView(AU.t(ctx, "No billing problems.", 13.5f, AK.ink, true), AU.lp(0, -2, 1f, l = 10f))
            c.addView(r); billBox.addView(c); return
        }
        list.forEachIndexed { i, c -> billBox.addView(billCard(c), AU.lp(-1, -2, t = if (i > 0) 10f else 0f)) }
    }

    private fun billCard(c: JSONObject): LinearLayout {
        val card = AU.card(ctx)
        val h = AU.row(ctx)
        h.addView(AU.icon(ctx, AdIc.ReceiptText, AK.amber, 16f))
        h.addView(AU.t(ctx, "${if (AF.s(c, "type") == "video") "Video" else "Voice"} call · ${AF.dur(AF.n(c, "duration_seconds"))}", 13.5f, AK.ink, true), AU.lp(0, -2, 1f, l = 8f, r = 8f))
        h.addView(AU.t(ctx, AF.fmt(AF.s(c, "ended_at").ifEmpty { AF.s(c, "created_date") }), 11.5f, AK.faint))
        card.addView(h, AU.lp(-1, -2, b = 8f))
        val who = AU.row(ctx)
        val caller = AF.o(c, "caller"); val callee = AF.o(c, "callee")
        who.addView(AU.t(ctx, AF.s(caller, "name").ifEmpty { "Caller" }, 13f, AK.deep, true).apply { setOnClickListener { a.openMember(AF.s(caller, "id")) } })
        who.addView(AU.t(ctx, " → ", 13f, AK.sub))
        who.addView(AU.t(ctx, AF.s(callee, "name").ifEmpty { "Creator" }, 13f, AK.deep, true).apply { setOnClickListener { a.openMember(AF.s(callee, "id")) } })
        card.addView(who, AU.lp(-1, -2, b = 6f))
        val g = AdGrid(ctx, 0f, 6f, 3)
        for ((l, v) in listOf("Taken from caller" to AF.n(c, "charged_coins"), "Creator earned" to AF.n(c, "creator_earned"), "Not collected" to AF.n(c, "uncollected_coins"))) {
            g.addView(AU.col(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL; background = Tf.box(AK.soft, 10f); setPadding(Ui.dpi(4f), Ui.dpi(6f), Ui.dpi(4f), Ui.dpi(6f))
                addView(AU.t(ctx, AF.coins(v), 15f, AK.ink, true).apply { gravity = Gravity.CENTER })
                addView(AU.t(ctx, l, 11f, AK.sub).apply { gravity = Gravity.CENTER })
            })
        }
        card.addView(g, AU.lp(-1, -2, b = 8f))
        val note = AF.s(c, "admin_note")
        if (note.isNotEmpty()) card.addView(AU.t(ctx, "${if (AF.s(c, "reviewed_at").isNotEmpty()) "Checked by ${AF.s(c, "reviewed_by")}: " else "Note: "}$note", 12.5f, AK.sub), AU.lp(-1, -2, b = 8f))
        if (AF.s(c, "reviewed_at").isEmpty()) {
            card.addView(AU.t(ctx, "Open the caller and creator, compare their wallet history, fix with \"Coins\" if needed, then mark this checked.", 12f, AK.faint), AU.lp(-1, -2, b = 8f))
            card.addView(AU.btn(ctx, "Mark checked", "dark", true) {
                AdAsk(a, "Mark this call bill as checked", null, emptyList(), "What did you check or fix?", "Save", false, 3, "Refunded 20 coins to the caller") { note2, done ->
                    src.fn("adminOps", JSONObject().put("action", "billing_review").put("call_id", AF.s(c, "id")).put("note", note2)) { r ->
                        if (r.ok) { say("Saved ✓"); done(true); bills.load(); a.changed() } else { say(AF.err(r), true); done(false) }
                    }
                }.open()
            }, LinearLayout.LayoutParams(-2, -2))
        }
        return card
    }

    override fun previewOpen(action: String) {
        if (action == "end") AF.list(load.data, "calls").firstOrNull()?.let { end("call", it) }
    }
}

/** Payouts (PayoutsTab.jsx): withdrawals waiting (pay in a UPI app, mark paid / reject), history, and the CSV for accounts. */
class AdPayouts(a: AdminScreen) : AdTab(a) {
    private var view = "waiting"
    private val box = AU.col(ctx)
    private var waiting: AdLoad? = null
    private var history: AdLoad? = null
    private val utr = HashMap<String, String>()

    private val filterBox = android.widget.FrameLayout(ctx)
    override fun build() { render() }
    override fun onShared() { if (alive) drawFilters() }
    private fun drawFilters() {
        filterBox.removeAllViews()
        filterBox.addView(AU.filters(ctx, listOf(Triple("waiting", "Waiting", AF.n(AF.o(a.overview, "todo"), "payouts").toInt()), Triple("paid", "Paid", 0), Triple("rejected", "Not paid", 0), Triple("export", "Download", 0)), view) { view = it; history = null; render() })
    }

    private fun fyNow(): String {
        val ist = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = System.currentTimeMillis() + 19_800_000L }
        val y = ist.get(Calendar.YEAR)
        val s = if (ist.get(Calendar.MONTH) >= 3) y else y - 1
        return "$s-${((s + 1) % 100).toString().padStart(2, '0')}"
    }
    private fun fyBefore(fy: String): String { val s = fy.take(4).toInt() - 1; return "$s-${((s + 1) % 100).toString().padStart(2, '0')}" }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        root.addView(filterBox, AU.lp(-1, -2))
        drawFilters()
        root.addView(box, AU.lp(-1, -2))
        box.removeAllViews()
        when (view) {
            "waiting" -> { if (waiting == null) waiting = AdLoad(this, "adminPayouts", { JSONObject().put("action", "list") }) { if (view == "waiting") drawWaiting() }.also { it.load() }; drawWaiting() }
            "export" -> drawExport()
            else -> { if (history == null) history = AdLoad(this, "adminPayouts", { JSONObject().put("action", "history").put("status", view) }) { if (view == "paid" || view == "rejected") drawHistory() }.also { it.load() }; drawHistory() }
        }
    }

    private fun drawWaiting() {
        val l = waiting ?: return
        box.removeAllViews()
        if (l.loading && l.data == null) { box.addView(AU.loading(ctx)); return }
        box.addView(AU.errorLine(ctx, l.error) { l.load() })
        val list = AF.list(l.data, "pending")
        if (list.isEmpty()) box.addView(AU.empty(ctx, AdIc.IndianRupee, "No withdrawals waiting", "Creators' and hosts' withdrawal requests show up here."))
        list.forEachIndexed { i, p -> box.addView(payCard(p), AU.lp(-1, -2, t = if (i > 0) 12f else 0f)) }
        box.addView(AU.t(ctx, "\"Pay in UPI app\" opens GPay / PhonePe / Paytm with the amount filled in. After the money is sent, add the reference and tap Mark paid — the creator is told at once.", 12f, AK.faint), AU.lp(-1, -2, l = 2f, t = 12f))
    }

    private fun payCard(p: JSONObject): LinearLayout {
        val b = AF.o(p, "breakdown")
        val pid = AF.s(p, "profile_id")
        val upi = AF.s(p, "upi")
        val c = AU.card(ctx)
        c.addView(AU.person(ctx, src, JSONObject().put("name", AF.s(p, "name")).put("photo", AF.s(p, "photo")), 40f, "${AF.s(p, "phone")} · asked ${AF.fmt(AF.s(p, "requested_at"))}", { a.openMember(AF.s(p, "user_id")) }, AU.pill(ctx, "pending", "Waiting")))
        val pan = AF.s(p, "pan")
        c.addView(AU.rows(ctx, 3f,
            AU.kv(ctx, "Pay to UPI", upi, true),
            AU.kv(ctx, "UPI name", AF.s(p, "upi_name")),
            AU.kv(ctx, "Total earned", "₹${AF.rup(AF.n(p, "earned"))}"),
            AU.kv(ctx, "Already withdrawn", "₹${AF.rup(AF.n(p, "paid_total"))}"),
            AU.kv(ctx, "Available", "₹${AF.rup(AF.n(p, "available"))}"),
            AU.kv(ctx, "PAN", if (pan == "verified") "Verified" else if (pan == "uploaded" || pan == "pending") "Uploaded" else "Not given")), AU.lp(-1, -2, t = 12f, b = 10f))
        val tds = AF.b(b, "tdsApplies")
        val sum = AU.col(ctx).apply { background = Tf.box(AK.soft, 12f, 1f, AK.line); setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)) }
        sum.addView(AU.rows(ctx, 3f,
            AU.kv(ctx, "Requested", "₹${AF.rup(AF.n(b, "gross"))}"),
            AU.kv(ctx, "App charge 33% (GST incl.)", "− ₹${AF.rup(AF.n(b, "fee"))}"),
            AU.kv(ctx, "TDS ${if (tds) "10%" else "0%"}${if (tds) " (deposit to govt.)" else ""}", "− ₹${AF.rup(AF.n(b, "tds"))}")))
        sum.addView(View(ctx).apply { setBackgroundColor(AK.line) }, AU.lp(-1, 1, t = 4f, b = 4f))
        val send = AU.row(ctx)
        send.addView(AU.t(ctx, "Send", 15f, AK.ink, true), AU.lp(0, -2, 1f))
        send.addView(AU.t(ctx, "₹${AF.rup(AF.n(b, "net"))}", 15f, AK.green, true))
        sum.addView(send)
        c.addView(sum)
        val tooMuch = AF.b(p, "too_much"); val verified = AF.b(p, "verified"); val banned = AF.b(p, "banned")
        if (tooMuch || !verified || upi.isEmpty() || banned) c.addView(AU.rows(ctx, 5f,
            if (tooMuch) AU.warn(ctx, "Asks more than the balance (₹${AF.rup(AF.n(p, "available"))}) — reject it") else null,
            if (!verified) AU.warn(ctx, "Not a verified Creator or Host") else null,
            if (upi.isEmpty()) AU.warn(ctx, "No approved UPI") else null,
            if (banned) AU.warn(ctx, "This member is banned", "amber") else null), AU.lp(-1, -2, t = 10f))
        if (upi.isNotEmpty()) {
            val link = "upi://pay?pa=${Uri(upi)}&pn=${Uri(AF.s(p, "upi_name").ifEmpty { AF.s(p, "name") }.ifEmpty { "Creator" })}&am=${AF.n(b, "net").toLong()}.00&cu=INR&tn=${Uri("True Find payout")}"
            c.addView(AU.t(ctx, "Pay ₹${AF.rup(AF.n(b, "net"))} in UPI app", 14f, AK.deep, true).apply {
                gravity = Gravity.CENTER; background = Tf.box(AK.tint, 12f, 1.5f, AK.tintLine)
                compoundDrawablePadding = Ui.dpi(6f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(AdIc.Smartphone, AK.deep, 16f), null, null, null)
                setOnClickListener { a.openUpi(link) }
            }, AU.lp(-1, Ui.dpi(42f), t = 12f))
        }
        c.addView(AU.label(ctx, "UPI reference / UTR (after you pay)", top = 12f))
        val ref = AU.input(ctx, "e.g. 431512345678", utr[pid] ?: "")
        ref.filter { it.take(40) }
        ref.onText = { utr[pid] = it }
        c.addView(ref, AU.lp(-1, -2))
        c.addView(AU.btnRow(ctx, 10f, true,
            AU.btn(ctx, "Mark paid", "dark", icon = AdIc.CheckCircle2) { markPaid(p) }.also { it.off = upi.isEmpty() || !verified || tooMuch },
            AU.btn(ctx, "Reject", "dangerLight", icon = AdIc.XCircle) { reject(p) }), AU.lp(-1, -2, t = 10f))
        return c
    }
    private fun Uri(s: String) = android.net.Uri.encode(s)

    private fun markPaid(p: JSONObject) {
        val net = AF.n(AF.o(p, "breakdown"), "net")
        AdConfirm(a, "Mark as paid?", "Did you send ₹${AF.rup(net)} to ${AF.s(p, "upi")}? This is saved for her and in the payout history.", "Yes, paid") { done ->
            src.fn("adminPayouts", JSONObject().put("action", "mark_paid").put("profile_id", AF.s(p, "profile_id")).put("requested_at", AF.s(p, "requested_at")).put("utr", utr[AF.s(p, "profile_id")] ?: "")) { r ->
                if (r.ok) { say("Marked paid: ₹${AF.rup(net)} to ${AF.s(p, "name").ifEmpty { "creator" }} ✓"); done(true); waiting?.load(); a.changed() }
                else { say(AF.err(r), true); done(false) }
            }
        }.open()
    }
    private fun reject(p: JSONObject) {
        AdAsk(a, "Don't pay this request?", "The creator sees the reason and can ask again.", listOf("UPI name does not match", "Earnings under review", "Suspicious activity"), "Reason (she sees this)", "Reject", true) { reason, done ->
            src.fn("adminPayouts", JSONObject().put("action", "reject").put("profile_id", AF.s(p, "profile_id")).put("requested_at", AF.s(p, "requested_at")).put("reason", reason)) { r ->
                if (r.ok) { say("Request rejected"); done(true); waiting?.load(); a.changed() } else { say(AF.err(r), true); done(false) }
            }
        }.open()
    }

    private fun drawHistory() {
        val l = history ?: return
        box.removeAllViews()
        if (l.loading && l.data == null) { box.addView(AU.loading(ctx)); return }
        box.addView(AU.errorLine(ctx, l.error) { l.load() })
        val list = AF.list(l.data, "records")
        if (list.isEmpty()) { box.addView(AU.empty(ctx, AdIc.IndianRupee, "Nothing yet")); return }
        box.addView(AU.listCard(ctx, list.map { r ->
            AU.col(ctx).apply {
                setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
                val paid = AF.s(r, "status") == "paid"
                val h = AU.row(ctx)
                h.addView(AU.t(ctx, AF.s(r, "name").ifEmpty { "Creator" }, 13.5f, AK.ink, true).ell(), AU.lp(0, -2, 1f, r = 8f))
                h.addView(AU.t(ctx, "₹${AF.rup(AF.n(r, if (paid) "net" else "gross"))}", 14f, if (paid) AK.green else AK.red, true))
                addView(h)
                val ref = AF.s(r, "utr"); val tds = AF.n(r, "tds")
                addView(AU.t(ctx, if (paid) "Sent to ${AF.s(r, "upi_id")}${if (ref.isNotEmpty()) " · ref $ref" else ""}${if (tds != 0.0) " · TDS ₹${AF.rup(tds)}" else ""}" else "Not paid: ${AF.s(r, "reason")}", 12f, AK.sub), AU.lp(-1, -2, t = 2f))
                addView(AU.t(ctx, "${AF.fmt(AF.s(r, "decided_at"))} · ${AF.s(r, "decided_by")}", 11.5f, AK.faint), AU.lp(-1, -2, t = 1f))
            }
        }))
    }

    private var fy = ""
    private fun drawExport() {
        val now = fyNow()
        if (fy.isEmpty()) fy = now
        val c = AU.card(ctx)
        c.addView(AU.t(ctx, "Download payouts for accounts / TDS", 14.5f, AK.ink, true), AU.lp(-1, -2, b = 4f))
        c.addView(AU.t(ctx, "A CSV file (opens in Excel / Google Sheets) with every paid withdrawal of the financial year: name, UPI, amount, app charge, TDS and reference.", 12.5f, AK.sub), AU.lp(-1, -2, b = 12f))
        c.addView(AU.filters(ctx, listOf(Triple(now, "FY $now", 0), Triple(fyBefore(now), "FY ${fyBefore(now)}", 0)), fy) { fy = it; box.removeAllViews(); drawExport() })
        val last = AU.t(ctx, "", 12.5f, AK.sub).apply { visibility = View.GONE }
        val go = AU.btn(ctx, "Download CSV", "dark", icon = AdIc.Download)
        go.setOnClickListener {
            if (go.busy) return@setOnClickListener
            go.busy = true
            src.fn("adminPayouts", JSONObject().put("action", "export").put("fy", fy)) { r ->
                go.busy = false
                if (!r.ok) { say(AF.err(r), true); return@fn }
                val d = r.data
                a.saveFile("truefind-payouts-${AF.s(d, "fy").ifEmpty { fy }}.csv", "text/csv", ("﻿" + AF.s(d, "csv")).toByteArray(Charsets.UTF_8))
                val t = AF.o(d, "totals")
                last.text = "${AF.nf(AF.n(d, "count"))} payouts · sent ₹${AF.rup(AF.n(t, "net"))} · TDS ₹${AF.rup(AF.n(t, "tds"))}"
                last.visibility = View.VISIBLE
            }
        }
        c.addView(go, AU.lp(-1, -2))
        c.addView(last, AU.lp(-1, -2, t = 10f))
        box.addView(c)
    }

    override fun previewOpen(action: String) {
        when (action) { "paid" -> { view = "paid"; render() }; "export" -> { view = "export"; render() }; "reject" -> AF.list(waiting?.data, "pending").firstOrNull()?.let { reject(it) } }
    }
}
