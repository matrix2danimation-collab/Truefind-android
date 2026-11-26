package app.truefind.android

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Support (SupportTab.jsx): members' help chats and website deletion requests, with replies. */
class AdSupport(a: AdminScreen) : AdTab(a) {
    companion object {
        val LABEL = mapOf("payment" to "Coins & recharge", "calls" to "Calls", "payout" to "Withdrawal", "verification" to "Verification", "report" to "Report a person",
            "account" to "Account", "bug" to "App problem", "deletion" to "Account deletion", "other" to "Other")
        val QUICK = listOf(
            "Thanks for writing to us. We are checking this and will reply soon.",
            "We have added the missing coins to your wallet. Sorry for the trouble!",
            "Your withdrawal was sent today. It can take up to 24 hours to show in your bank.",
            "Please update the app and try again. If it still happens, send us a screenshot.",
        )
    }
    private var status = "open"
    private val load = AdLoad(this, "support", { JSONObject().put("action", "admin_list").put("status", status) }) { render() }

    override fun build() { render(); load.load() }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        val counts = AF.o(load.data, "counts")
        root.addView(AU.filters(ctx, listOf(Triple("open", "Waiting", AF.n(counts, "open").toInt()), Triple("answered", "Replied", 0), Triple("closed", "Closed", 0), Triple("all", "All", 0)), status) { status = it; render(); load.load() })
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        val list = AF.list(d, "tickets")
        if (list.isEmpty()) { root.addView(AU.empty(ctx, AdIc.LifeBuoy, "No conversations here", "Messages from Settings → Help & support and website deletion requests show up here.")); return }
        root.addView(AU.listCard(ctx, list.map { t -> row(t) }))
    }

    private fun row(t: JSONObject): LinearLayout {
        val msgs = AF.list(t, "messages")
        val last = msgs.lastOrNull()
        val r = AU.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(11f), Ui.dpi(12f), Ui.dpi(11f)); setOnClickListener { thread(AF.s(t, "id")) } }
        r.addView(View(ctx).apply { background = Tf.circle(if (AF.b(t, "admin_unread")) AK.purple else Color.TRANSPARENT) }, LinearLayout.LayoutParams(Ui.dpi(8f), Ui.dpi(8f)))
        val mid = AU.col(ctx)
        val cat = AF.s(t, "category")
        val name = AU.t(ctx, AF.s(t, "name").ifEmpty { AF.s(t, "contact") }.ifEmpty { "Website visitor" }, 13.5f, AK.ink, true).ell()
        mid.addView(AdNameRow(ctx, name, AU.t(ctx, LABEL[cat] ?: cat, 11.5f, if (cat == "deletion") AK.red else AK.faint, true).apply { maxLines = 1 }))
        mid.addView(AU.t(ctx, "${if (AF.s(last, "from") == "admin") "You: " else ""}${AF.s(last, "text").ifEmpty { AF.s(t, "subject") }}", 12.5f, AK.sub).ell())
        r.addView(mid, AU.lp(0, -2, 1f, l = 10f, r = 10f))
        val right = AU.col(ctx).apply { gravity = Gravity.END }
        right.addView(AU.pill(ctx, AF.s(t, "status")), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        right.addView(AU.t(ctx, AF.ago(AF.s(last, "at").ifEmpty { AF.s(t, "created_date") }), 11f, AK.faint).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = Ui.dpi(3f) })
        r.addView(right)
        return r
    }

    /** one conversation (Thread): messages, quick answers, reply, close / open again */
    private fun thread(id: String) {
        var t: JSONObject? = null
        var err = ""
        val sh = AdSheet(a.layer, "Conversation", onClose = { load.load() })
        val msgsBox = AU.col(ctx)
        val area = AU.area(ctx, "Write your reply…", 3).also { it.filter { s -> s.take(2000) } }
        val send = AU.btn(ctx, "Send reply", "dark", icon = AdIc.Send, iconSize = 15f).also { it.off = true }
        lateinit var draw: () -> Unit
        fun loadThread() {
            err = ""
            src.fn("support", JSONObject().put("action", "admin_get").put("ticket_id", id)) { r ->
                if (sh.closed) return@fn
                if (r.ok) { t = AF.o(r.data, "ticket"); a.changed() } else err = AF.err(r)
                draw()
            }
        }
        fun setStatus(action: String) {
            src.fn("support", JSONObject().put("action", action).put("ticket_id", id)) { r ->
                if (r.ok) { loadThread(); say(if (action == "admin_close") "Closed" else "Opened again") } else say(AF.err(r), true)
            }
        }
        draw = {
            val b = sh.body
            b.removeAllViews()
            b.addView(AU.errorLine(ctx, err) { loadThread() })
            val tk = t
            if (tk == null) { if (err.isEmpty()) b.addView(AU.loading(ctx)) }
            else {
                val cat = AF.s(tk, "category")
                sh.title(AF.s(tk, "subject").ifEmpty { LABEL[cat] ?: "" }.ifEmpty { "Conversation" })
                val head = FlowRow(ctx, Ui.dpi(6f), Ui.dpi(6f))
                head.addView(AU.pill(ctx, AF.s(tk, "status")))
                head.addView(AU.pill(ctx, "x", LABEL[cat] ?: cat))
                val uid = AF.s(tk, "user_id")
                head.addView(if (uid.isNotEmpty()) AU.t(ctx, AF.s(tk, "name").ifEmpty { "Open member" }, 12.5f, AK.deep, true).apply { setOnClickListener { a.openMember(uid) } }
                    else AU.t(ctx, "From the website · ${AF.s(tk, "contact")}", 12f, AK.sub))
                b.addView(head, AU.lp(-1, -2, b = 10f))
                if (cat == "deletion") {
                    val c = AU.card(ctx).apply { background = Tf.box(AK.tint, 16f, 1f, AK.tintLine) }
                    val contact = AF.s(tk, "contact").ifEmpty { "the member" }
                    val txt = android.text.SpannableStringBuilder("Account deletion request. First confirm it is really their account (call or WhatsApp ")
                    var st = txt.length; txt.append(contact); txt.setSpan(TypefaceSpanCompat(Ui.bold), st, txt.length, 0)
                    txt.append("), then open the member and tap ")
                    st = txt.length; txt.append("Delete account"); txt.setSpan(TypefaceSpanCompat(Ui.bold), st, txt.length, 0)
                    txt.append(".")
                    c.addView(AU.t(ctx, txt, 13f, AK.ink, false, 19f))
                    if (uid.isEmpty() && AF.s(tk, "contact").isNotEmpty()) c.addView(AU.btn(ctx, "Find this member", "dark", true, AdIc.Search, 14f) { a.findMember(AF.s(tk, "contact"), id) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(8f) })
                    if (uid.isNotEmpty()) c.addView(AU.btn(ctx, "Open member to delete", "dangerLight", true, AdIc.Trash2, 14f) { a.openMember(uid, id) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(8f) })
                    b.addView(c, AU.lp(-1, -2, b = 10f))
                }
                msgsBox.removeAllViews()
                AF.list(tk, "messages").forEachIndexed { i, m ->
                    val team = AF.s(m, "from") == "admin"
                    val bubble = AU.col(ctx).apply {
                        background = if (team) Tf.box(AK.black, 13f) else Tf.box(AK.soft, 13f, 1f, AK.line)
                        setPadding(Ui.dpi(11f), Ui.dpi(8f), Ui.dpi(11f), Ui.dpi(8f))
                        addView(AU.t(ctx, if (team) "You (True Find team)" else AF.s(tk, "name").ifEmpty { "Member" }, 11.5f, if (team) AK.onBlack else AK.deep, true))
                        addView(AU.t(ctx, AF.s(m, "text"), 13.5f, if (team) Color.WHITE else AK.ink, false, 19f), AU.lp(-2, -2, t = 2f))
                        val att = AF.s(m, "attachment")
                        if (att.isNotEmpty()) addView(AdImg(ctx, src, false, 120f, 150f).also { it.set(att); it.onOpen = { u -> a.zoom(u) } }, LinearLayout.LayoutParams(Ui.dpi(120f), Ui.dpi(150f)).apply { topMargin = Ui.dpi(6f) })
                        addView(AU.t(ctx, AF.fmt(AF.s(m, "at")), 10.5f, if (team) Color.WHITE else AK.ink).apply { alpha = 0.7f }, AU.lp(-2, -2, t = 3f))
                    }
                    val line = AU.bubbleLine(ctx, bubble, team)
                    msgsBox.addView(line, AU.lp(-1, -2, t = if (i > 0) 8f else 0f))
                }
                b.addView(msgsBox, AU.lp(-1, -2))
                val quick = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
                val qr = AU.row(ctx)
                QUICK.forEachIndexed { i, q ->
                    qr.addView(AU.t(ctx, q, 12f, AK.sub).apply {
                        gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = Ui.dpi(220f)
                        background = Tf.box(Color.WHITE, 999f, 1.5f, AK.line); setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
                        setOnClickListener { area.setText(q); area.setSelection(area.value.length) }
                    }, LinearLayout.LayoutParams(-2, Ui.dpi(30f)).apply { if (i > 0) leftMargin = Ui.dpi(6f) })
                }
                quick.addView(qr)
                b.addView(quick, AU.lp(-1, -2, t = 12f, b = 8f))
                (area.parent as? android.view.ViewGroup)?.removeView(area)
                b.addView(area, AU.lp(-1, -2))
                (send.parent as? android.view.ViewGroup)?.removeView(send)
                val closed = AF.s(tk, "status") == "closed"
                b.addView(AU.btnRow(ctx, 8f, false, send, AU.btn(ctx, if (closed) "Open again" else "Close", "light") { setStatus(if (closed) "admin_reopen" else "admin_close") }).also {
                    (send.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
                }, AU.lp(-1, -2, t = 10f))
                // the newest message in view
                sh.body.post { (sh.body.parent as? ScrollView)?.let { sc -> sc.scrollTo(0, msgsBox.bottom - sc.height / 2) } }
            }
        }
        area.onText = { send.off = it.trim().length < 2 }
        send.setOnClickListener {
            if (send.busy || send.off) return@setOnClickListener
            send.busy = true
            src.fn("support", JSONObject().put("action", "admin_reply").put("ticket_id", id).put("message", area.value.trim())) { r ->
                send.busy = false
                if (r.ok) {
                    val nt = AF.o(r.data, "ticket")
                    t = JSONObject((t ?: JSONObject()).toString()).also { o -> nt?.keys()?.forEach { k -> o.put(k, nt.opt(k)) } }
                    area.setText("")
                    say("Reply sent — the member gets a notification ✓")
                    a.changed(); draw()
                } else say(AF.err(r), true)
            }
        }
        draw()
        sh.show()
        loadThread()
    }

    override fun previewOpen(action: String) {
        if (action == "thread") AF.list(load.data, "tickets").firstOrNull()?.let { thread(AF.s(it, "id")) }
        if (action == "deletion") AF.list(load.data, "tickets").firstOrNull { AF.s(it, "category") == "deletion" }?.let { thread(AF.s(it, "id")) }
    }
}

/** Errors (ErrorsTab.jsx): crashes on members' phones, grouped, with details and "Mark fixed". */
class AdErrors(a: AdminScreen) : AdTab(a) {
    private val SOURCE = mapOf("react" to "Screen crashed", "window" to "Script error", "promise" to "Unhandled error", "chunk" to "Old app files after update", "manual" to "Reported")
    private var status = "open"
    private var openId = ""
    private val load = AdLoad(this, "adminOps", { JSONObject().put("action", "errors").put("status", status) }) { render() }

    override fun build() { render(); load.load() }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        root.addView(AU.filters(ctx, listOf(Triple("open", "Open", 0), Triple("resolved", "Fixed", 0), Triple("all", "All", 0)), status) { status = it; render(); load.load() })
        root.addView(AU.t(ctx, "Crashes on members' phones, grouped. Send the top ones to your developer (or to me) with the screen name. When fixed, mark them fixed — if it happens again it comes back here by itself.", 12.5f, AK.sub), AU.lp(-1, -2, l = 2f, r = 2f, b = 10f))
        root.addView(AU.errorLine(ctx, load.error) { load.load() })
        val d = load.data
        if (load.loading && d == null) { root.addView(AU.loading(ctx)); return }
        val list = AF.list(d, "errors")
        if (list.isEmpty()) { root.addView(AU.empty(ctx, AdIc.Bug, if (status == "open") "No open errors" else "Nothing here", if (status == "open") "Great — the app is running without reported crashes." else "")); return }
        list.forEachIndexed { i, e -> root.addView(card(e), AU.lp(-1, -2, t = if (i > 0) 10f else 0f)) }
        if (status == "open" && list.size > 1) root.addView(AU.btn(ctx, "Mark all fixed", "light") {
            AdConfirm(a, "Mark every open error as fixed?", "Any that happen again will come back here by themselves.", "Mark all fixed") { done ->
                src.fn("adminOps", JSONObject().put("action", "errors_resolve_all")) { r ->
                    if (r.ok) { say("${AF.nf(AF.n(r.data, "resolved"))} marked fixed"); done(true); load.load(); a.changed() } else { say(AF.err(r), true); done(false) }
                }
            }.open()
        }, AU.lp(-1, -2, t = 12f))
    }

    private fun card(e: JSONObject): LinearLayout {
        val id = AF.s(e, "id"); val open = openId == id
        val c = AU.card(ctx)
        val top = AU.row(ctx, Gravity.TOP)
        val n = AF.n(e, "count")
        val count = AU.col(ctx).apply {
            gravity = Gravity.CENTER; background = Tf.box(AK.redBg, 11f); minimumWidth = Ui.dpi(40f); minimumHeight = Ui.dpi(40f)
            setPadding(Ui.dpi(3f), 0, Ui.dpi(3f), 0)
            addView(AU.t(ctx, if (n > 999) "999+" else AF.nf(n), 14f, AK.red, true, 15f).apply { gravity = Gravity.CENTER })
            addView(AU.t(ctx, "times", 9.5f, AK.red, true).apply { gravity = Gravity.CENTER })
        }
        top.addView(count, LinearLayout.LayoutParams(-2, Ui.dpi(40f)))
        val mid = AU.col(ctx)
        mid.addView(AU.t(ctx, AF.s(e, "message"), 13.5f, AK.ink, true))
        val people = AF.n(e, "people").toInt()
        mid.addView(AU.t(ctx, "${SOURCE[AF.s(e, "source")] ?: AF.s(e, "source")} · screen ${AF.s(e, "page").ifEmpty { "—" }} · $people ${if (people == 1) "person" else "people"}", 12f, AK.sub), AU.lp(-1, -2, t = 3f))
        mid.addView(AU.t(ctx, "Last ${AF.ago(AF.s(e, "last_seen"))} · first ${AF.fmt(AF.s(e, "first_seen"))}", 11.5f, AK.faint), AU.lp(-1, -2, t = 1f))
        top.addView(mid, AU.lp(0, -2, 1f, l = 10f, r = 10f))
        if (AF.s(e, "status") == "resolved") top.addView(AU.pill(ctx, "resolved", "Fixed"))
        c.addView(top)
        if (open) {
            val pre = AU.t(ctx, "${AF.s(e, "stack").ifEmpty { "No technical details." }}\n\nVersion: ${AF.s(e, "app_version").ifEmpty { "—" }}\nPhone: ${AF.s(e, "user_agent").ifEmpty { "—" }}", 11f, AK.ink, false, 15f).apply {
                typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
            }
            val sc = object : ScrollView(ctx) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(260f), MeasureSpec.AT_MOST)) }.apply {
                background = Tf.box(AK.soft, 10f, 1f, AK.line); setPadding(Ui.dpi(10f), Ui.dpi(10f), Ui.dpi(10f), Ui.dpi(10f)); isVerticalScrollBarEnabled = true
            }
            sc.addView(pre)
            c.addView(sc, AU.lp(-1, -2, t = 10f))
        }
        val btns = AU.row(ctx)
        btns.addView(AU.btn(ctx, "Details", "light", true, if (open) AdIc.ChevronUp else AdIc.ChevronDown) { openId = if (open) "" else id; render() })
        if (AF.s(e, "status") != "resolved") btns.addView(AU.btn(ctx, "Mark fixed", "dark", true, AdIc.CheckCircle2) {
            src.fn("adminOps", JSONObject().put("action", "error_resolve").put("id", id)) { r -> if (r.ok) { say("Marked fixed ✓"); load.load(); a.changed() } else say(AF.err(r), true) }
        }, AU.lp(-2, -2, l = 8f))
        c.addView(btns, AU.lp(-1, -2, t = 10f))
        return c
    }

    override fun previewOpen(action: String) {
        if (action == "details") { openId = AF.s(AF.list(load.data, "errors").firstOrNull(), "id"); render() }
    }
}

/** Settings (SettingsTab.jsx): maintenance, announcement, feature switches, call prices, rewards, support contact. */
class AdSettings(a: AdminScreen) : AdTab(a) {
    companion object {
        val FIELDS = listOf("maintenance_mode", "maintenance_message", "announcement", "signups_enabled", "calls_enabled", "games_enabled", "live_enabled",
            "voice_rate", "video_rate", "voice_creator_rate", "video_creator_rate", "support_email", "support_whatsapp", "daily_coins", "streak_bonus", "referral_reward",
            "grievance_name", "grievance_email", "business_name", "business_address")
        val RATES = listOf("voice_rate", "video_rate", "voice_creator_rate", "video_creator_rate")
        val REWARDS = listOf("daily_coins", "streak_bonus", "referral_reward")
        val NUMBERS = RATES + REWARDS
        val SWITCH_OFF = listOf("calls_enabled", "games_enabled", "live_enabled", "signups_enabled")
    }
    private val load = AdLoad(this, "adminOps", { JSONObject().put("action", "config_get") }) { onLoaded() }
    private var cfg: JSONObject? = null
    private val draft = HashMap<String, Any?>()
    private val bar = AU.row(ctx)
    private val barText = AU.t(ctx, "No changes", 12.5f, AK.faint, true)
    private val undo = AU.btn(ctx, "Undo", "light", true)
    private val save = AU.btn(ctx, "Save", "dark", icon = AdIc.Save, iconSize = 15f)
    private var pctLine: TextView? = null
    private var rewardLine: TextView? = null
    private var annCount: TextView? = null
    private var annNote: TextView? = null
    private var annRemove: View? = null
    private var annArea: AdInput? = null

    override fun build() {
        root.setPadding(0, 0, 0, Ui.dpi(70f))
        // the save bar fixed at the bottom of the page
        bar.setBackgroundColor(Color.WHITE)
        bar.setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(10f))
        val wrap = AU.col(ctx)
        wrap.addView(View(ctx).apply { setBackgroundColor(AK.line) }, AU.lp(-1, 1))
        bar.addView(barText, AU.lp(0, -2, 1f, r = 10f))
        bar.addView(undo, AU.lp(-2, -2, r = 10f))
        bar.addView(save, AU.lp(-2, -2))
        wrap.addView(bar, AU.lp(-1, -2))
        a.bottomSlot.removeAllViews()
        a.bottomSlot.addView(wrap, FrameLayout.LayoutParams(-1, -2))
        a.bottomSlot.visibility = View.GONE
        undo.setOnClickListener { resetDraft(); draw() }
        save.setOnClickListener { if (!save.busy && !save.off) { if (bigChange()) confirm() else doSave(null) } }
        render(); load.load()
    }
    override fun destroy() { super.destroy(); a.bottomSlot.removeAllViews() }

    private fun onLoaded() {
        val c = AF.o(load.data, "config")
        if (c != null && (cfg == null || c.toString() != cfg.toString())) { cfg = c; resetDraft(); draw() } else render()
    }
    private fun resetDraft() {
        val c = cfg ?: return
        draft.clear()
        for (k in FIELDS) draft[k] = if (NUMBERS.contains(k)) jsNum(c.opt(k)) else c.opt(k).let { if (it == JSONObject.NULL) null else it }
    }
    /** String(cfg[k]) of a number the way JS writes it (10, 2.25) */
    private fun jsNum(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "undefined"
        is Number -> v.toDouble().let { d -> if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
        else -> v.toString()
    }

    private fun render() {
        if (!alive) return
        if (cfg != null) return
        root.removeAllViews()
        if (load.loading) { root.addView(AU.loading(ctx)); return }
        root.addView(AU.errorLine(ctx, load.error.ifEmpty { "Could not load the settings." }) { load.load() })
    }

    private fun patch(): JSONObject {
        val c = cfg ?: return JSONObject()
        val out = JSONObject()
        for (k in FIELDS) {
            val raw = draft[k]
            if (NUMBERS.contains(k)) {
                val s = raw?.toString().orEmpty()
                val v = s.toDoubleOrNull()
                if (s.isEmpty() || v == null || !v.isFinite()) { out.put(k, s); continue }
                val cur = c.opt(k)
                if (cur !is Number || cur.toDouble() != v) out.put(k, if (v % 1.0 == 0.0) v.toLong() else v)
            } else {
                val cur = c.opt(k).let { if (it == JSONObject.NULL) null else it }
                if (raw != cur) out.put(k, raw ?: JSONObject.NULL)
            }
        }
        return out
    }
    private fun bigChange(): Boolean {
        val p = patch()
        return (p.opt("maintenance_mode") == true) || NUMBERS.any { p.has(it) } || SWITCH_OFF.any { p.opt(it) == false }
    }

    private fun refreshBar() {
        val n = patch().length()
        a.bottomSlot.visibility = if (cfg != null) View.VISIBLE else View.GONE
        barText.text = if (n > 0) "$n change${if (n > 1) "s" else ""} not saved" else "No changes"
        barText.setTextColor(if (n > 0) AK.ink else AK.faint)
        undo.visibility = if (n > 0) View.VISIBLE else View.GONE
        save.off = n == 0
        val num = { k: String -> AF.num(draft[k]?.toString()) }
        val pct = { share: Double, rate: Double -> if (rate > 0) Math.round(share / rate * 100) else 0L }
        pctLine?.text = "Creator gets ${pct(num("voice_creator_rate"), num("voice_rate"))}% of voice and ${pct(num("video_creator_rate"), num("video_rate"))}% of video (before the 33% at withdrawal). Home and Explore show the new prices to members automatically."
        val ref = num("referral_reward")
        rewardLine?.text = "Day 7 of a streak gives ${AF.nf(num("daily_coins") + num("streak_bonus"))} coins. ${if (ref > 0) "Inviter and friend get ${AF.nf(ref)} coins each." else "Refer & Earn is OFF (0)."}"
        val ann = draft["announcement"]?.toString().orEmpty()
        annCount?.text = "${ann.length}/300"
        annNote?.text = if (ann.isNotEmpty()) "Members will see this" else "No announcement"
        annRemove?.visibility = if (ann.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun head(icon: String, title: String, text: String?): LinearLayout {
        val r = AU.row(ctx, Gravity.TOP)
        r.addView(AU.tile(ctx, icon, AK.tint, AK.deep))
        val c = AU.col(ctx)
        c.addView(AU.t(ctx, title, 14.5f, AK.ink, true))
        if (!text.isNullOrEmpty()) c.addView(AU.t(ctx, text, 12.5f, AK.sub, false, 17f), AU.lp(-1, -2, t = 1f))
        r.addView(c, AU.lp(0, -2, 1f, l = 10f))
        r.layoutParams = AU.lp(-1, -2, b = 10f)
        return r
    }
    /** warnOn: for Maintenance, ON is the unusual state (red); for features, OFF is */
    private fun switchRow(key: String, label: String, text: String, onText: String = "", first: Boolean = false, warnOn: Boolean = false): LinearLayout {
        val c = AU.col(ctx)
        if (!first) c.addView(AU.divider(ctx))
        val r = AU.row(ctx).apply { setPadding(0, Ui.dpi(10f), 0, Ui.dpi(10f)) }
        val tc = AU.col(ctx)
        tc.addView(AU.t(ctx, label, 13.5f, AK.ink, true))
        val line = AU.t(ctx, "", 12f, AK.sub)
        tc.addView(line)
        r.addView(tc, AU.lp(0, -2, 1f, r = 12f))
        val sw = AdSwitch(ctx)
        fun paint() {
            val on = draft[key] == true
            val warn = if (warnOn) on else !on
            line.text = if (warnOn) (if (on) onText else text) else (if (on) text else "Switched off — members see a 'paused' message")
            line.setTextColor(if (warn) AK.red else AK.sub)
        }
        sw.set(draft[key] == true, false)
        sw.onChange = { v -> draft[key] = v; sw.set(v); paint(); refreshBar() }
        paint()
        r.addView(sw)
        c.addView(r)
        return c
    }
    private fun numField(key: String, label: String, clean: (String) -> String, decimal: Boolean): LinearLayout {
        val c = AU.col(ctx)
        c.addView(AU.label(ctx, label, top = 10f))
        val e = AU.input(ctx, "", draft[key]?.toString().orEmpty(), if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER)
        e.filter(clean)
        e.onText = { v -> draft[key] = v; refreshBar() }
        c.addView(e, AU.lp(-1, -2))
        return c
    }
    private fun textField(key: String, label: String?, hint: String, rows: Int, max: Int, type: Int = 0): LinearLayout {
        val c = AU.col(ctx)
        if (label != null) c.addView(AU.label(ctx, label, top = 10f))
        val e = if (rows > 0) AU.area(ctx, hint, rows, draft[key]?.toString().orEmpty()) else AU.input(ctx, hint, draft[key]?.toString().orEmpty(), if (type != 0) type else InputType.TYPE_CLASS_TEXT)
        if (max > 0) e.filter { it.take(max) }
        e.onText = { v -> draft[key] = v; refreshBar() }
        if (key == "announcement") annArea = e
        c.addView(e, AU.lp(-1, -2))
        return c
    }

    private fun draw() {
        if (!alive) return
        val c = cfg ?: return
        root.removeAllViews()
        root.addView(AU.errorLine(ctx, load.error) { load.load() })

        root.addView(AU.section(ctx, "App status").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 })
        root.addView(AU.card(ctx).apply {
            addView(head(AdIc.Wrench, "Maintenance mode", "Everyone except you sees a calm “We'll be back soon” screen. Use it while fixing something big."))
            addView(switchRow("maintenance_mode", "Maintenance mode", "Off — the app is open for everyone", "ON — members see the maintenance screen", true, true))
            addView(textField("maintenance_message", "Message on the maintenance screen", "True Find is getting an update. We'll be back in a few minutes. Thank you for waiting!", 2, 300))
        })

        root.addView(AU.section(ctx, "Announcement"))
        root.addView(AU.card(ctx).apply {
            addView(head(AdIc.Megaphone, "Message for every member", "Shown once to each member as a small card (never during a call). Change the text to show a new one."))
            addView(textField("announcement", null, "Diwali offer: 2x coins on every recharge today!", 3, 300))
            val r = AU.row(ctx)
            annNote = AU.t(ctx, "", 11.5f, AK.faint)
            annCount = AU.t(ctx, "", 11.5f, AK.faint)
            r.addView(annNote, AU.lp(0, -2, 1f)); r.addView(annCount)
            addView(r, AU.lp(-1, -2, t = 6f))
            val rm = AU.btn(ctx, "Remove announcement", "light", true) { annArea?.setText("") }
            annRemove = rm
            addView(rm, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(8f) })
        })

        root.addView(AU.section(ctx, "Switches"))
        root.addView(AU.card(ctx).apply {
            addView(head(AdIc.ToggleRight, "Turn big features on or off", "If something breaks, switch it off here at once — no update needed. You (admin) can still use it to test."))
            addView(switchRow("calls_enabled", "Calls", "Voice / video calls and Random Match", first = true))
            addView(switchRow("games_enabled", "Games", "Heart Chase, Mood Changer, ClueMe, Dress Code"))
            addView(switchRow("live_enabled", "Live rooms", "Going live and joining lives"))
            addView(switchRow("signups_enabled", "New sign-ups", "New people can create a profile"))
        })

        root.addView(AU.section(ctx, "Call prices"))
        root.addView(AU.card(ctx).apply {
            addView(head(AdIc.IndianRupee, "What callers pay and creators earn (per minute)", "Applies to calls that start after you save. Calls already running keep their price."))
            val g = AdGrid(ctx, 0f, 10f, 2)
            g.addView(numField("voice_rate", "Voice price (coins)", { it.replace(Regex("\\D"), "").take(4) }, false))
            g.addView(numField("video_rate", "Video price (coins)", { it.replace(Regex("\\D"), "").take(4) }, false))
            g.addView(numField("voice_creator_rate", "Creator earns · voice", { it.replace(Regex("[^\\d.]"), "").take(6) }, true))
            g.addView(numField("video_creator_rate", "Creator earns · video", { it.replace(Regex("[^\\d.]"), "").take(6) }, true))
            addView(g)
            pctLine = AU.t(ctx, "", 12.5f, AK.sub)
            addView(pctLine, AU.lp(-1, -2, t = 10f))
        })

        root.addView(AU.section(ctx, "Rewards"))
        root.addView(AU.card(ctx).apply {
            addView(head(AdIc.Gift, "Free coins you give away", "Daily Free Coins on Home, the day-7 streak bonus, and Refer & Earn (paid to both people after the friend's first recharge of ₹49+)."))
            val g = AdGrid(ctx, 0f, 10f, 2)
            g.addView(numField("daily_coins", "Daily free coins", { it.replace(Regex("\\D"), "").take(3) }, false))
            g.addView(numField("streak_bonus", "Day-7 streak bonus", { it.replace(Regex("\\D"), "").take(4) }, false))
            g.addView(numField("referral_reward", "Refer & Earn (each)", { it.replace(Regex("\\D"), "").take(4) }, false))
            addView(g)
            rewardLine = AU.t(ctx, "", 12.5f, AK.sub)
            addView(rewardLine, AU.lp(-1, -2, t = 10f))
        })

        root.addView(AU.section(ctx, "Support contact"))
        root.addView(AU.card(ctx).apply {
            addView(head(AdIc.LifeBuoy, "Shown in Settings → Help & support", "Members can also write to you in the app (Support tab)."))
            addView(textField("support_email", "Support email", "support@truefindapp.com", 0, 0, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
            addView(textField("support_whatsapp", "WhatsApp number", "+91 98765 43210", 0, 0, InputType.TYPE_CLASS_PHONE))
        })

        // same as the web Control Center (SettingsTab.jsx): shown on truefindapp.com/contact and the policy pages
        root.addView(AU.section(ctx, "Legal & business details"))
        root.addView(AU.card(ctx).apply {
            addView(head(AdIc.LifeBuoy, "Shown on the website's Contact and policy pages", "Indian IT Rules ask for a Grievance Officer; Razorpay and Google Play ask for the business name and address."))
            addView(textField("grievance_name", "Grievance Officer name", "Full name", 0, 80))
            addView(textField("grievance_email", "Grievance Officer email", "grievance@truefindapp.com", 0, 120, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
            addView(textField("business_name", "Business name", "Your registered business name (as on MSME / bank)", 0, 120))
            addView(textField("business_address", "Business address", "Full postal address with city, state and PIN code", 3, 300))
        })

        val at = AF.s(c, "updated_at")
        root.addView(AU.t(ctx, if (at.isNotEmpty()) "Last saved ${AF.fmt(at)} by ${AF.s(c, "updated_by")}" else "Never changed — the app is using the standard settings.", 11.5f, AK.faint), AU.lp(-1, -2, l = 2f, t = 12f, r = 2f))
        refreshBar()
    }

    private fun confirm() {
        val p = patch()
        val text = listOfNotNull(
            if (p.opt("maintenance_mode") == true) "Maintenance ON: members can't use the app until you switch it off." else null,
            if (RATES.any { p.has(it) }) "New call prices apply to every call that starts from now." else null,
            if (REWARDS.any { p.has(it) }) "New free-coin amounts apply from the next claim / recharge." else null,
            if (SWITCH_OFF.any { p.opt(it) == false }) "Members can't start what you switched off." else null,
        ).joinToString(" ")
        AdConfirm(a, "Save these changes?", text, "Save") { done -> doSave(done) }.open()
    }

    private fun doSave(done: ((Boolean) -> Unit)?) {
        val c = cfg ?: return
        save.busy = true
        src.fn("adminOps", JSONObject().put("action", "config_save").put("version", c.opt("version") ?: JSONObject.NULL).put("patch", patch())) { r ->
            save.busy = false
            if (r.ok) {
                say("Settings saved ✓ — members get them within 2 minutes")
                done?.invoke(true)
                cfg = null
                load.load(); a.changed()
                AppStatusHooks.configChanged()
            } else {
                say(AF.err(r, "Could not save."), true)
                done?.invoke(false)
                if (r.status == 409) { cfg = null; load.load() }
            }
        }
    }

    override fun previewOpen(action: String) {
        when (action) {
            "change" -> { draft["maintenance_mode"] = true; draft["voice_rate"] = "12"; draw() }
            "confirm" -> { draft["maintenance_mode"] = true; draft["voice_rate"] = "12"; draw(); confirm() }
        }
    }
}

/** System (SystemTab.jsx): WhatsApp OTP, offer notifications, background jobs, backup, audit log, testing tools. */
class AdSystem(a: AdminScreen) : AdTab(a) {
    companion object {
        val TABLES = listOf("UserProfile" to "Members", "Wallet" to "Wallets", "WalletTransaction" to "Wallet history", "Call" to "Calls",
            "PayoutRecord" to "Payouts", "ModerationReport" to "Reports", "SupportTicket" to "Support", "AdminAction" to "Audit log", "AppConfig" to "Settings", "CallRating" to "Call ratings", "Referral" to "Refer & Earn")
        val KIND_LABEL = mapOf("send" to "Login OTP", "verify" to "Code check", "limited" to "Blocked", "test" to "Test send")
        val OFFER_PAGES = listOf("/wallet" to "Wallet", "/" to "Home", "/secret-app" to "Reconnect", "/games" to "Games", "/live" to "Live Rooms")
    }
    private val main = Handler(Looper.getMainLooper())
    private val otpBox = AU.col(ctx); private val offerBox = AU.col(ctx); private val jobsBox = AU.col(ctx); private val backupBox = AU.col(ctx); private val auditBox = AU.col(ctx); private val testBox = AU.col(ctx)
    private val wa = AdLoad(this, "whatsappCheck", { JSONObject().put("action", "status") }) { drawOtp() }
    private val offers = AdLoad(this, "adminOps", { JSONObject().put("action", "offers") }) { drawOffers() }
    private val jobs = AdLoad(this, "adminOps", { JSONObject().put("action", "jobs") }) { drawJobs() }
    private val audit = AdLoad(this, "adminOps", { JSONObject().put("action", "audit_log").put("limit", 60) }) { drawAudit() }

    override fun build() {
        root.addView(AU.section(ctx, "WhatsApp OTP").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 }); root.addView(otpBox, AU.lp(-1, -2))
        root.addView(AU.section(ctx, "Notifications")); root.addView(offerBox, AU.lp(-1, -2))
        root.addView(AU.section(ctx, "Jobs")); root.addView(jobsBox, AU.lp(-1, -2))
        root.addView(AU.section(ctx, "Backup")); root.addView(backupBox, AU.lp(-1, -2))
        root.addView(AU.section(ctx, "History")); root.addView(auditBox, AU.lp(-1, -2))
        root.addView(AU.section(ctx, "Testing")); root.addView(testBox, AU.lp(-1, -2))
        drawOtp(); drawOffers(); drawJobs(); drawBackup(); drawAudit(); drawTest()
        wa.load(); offers.load(); jobs.load(); audit.load()
    }
    override fun destroy() { super.destroy(); main.removeCallbacksAndMessages(null); pumping = false }

    private fun headRow(icon: String, title: String, text: String?, bg: Int = AK.tint, fg: Int = AK.deep): LinearLayout {
        val r = AU.row(ctx, Gravity.TOP)
        r.addView(AU.tile(ctx, icon, bg, fg))
        val c = AU.col(ctx)
        c.addView(AU.t(ctx, title, 14.5f, AK.ink, true))
        if (!text.isNullOrEmpty()) c.addView(AU.t(ctx, text, 12.5f, AK.sub, false, 17f), AU.lp(-1, -2, t = 1f))
        r.addView(c, AU.lp(0, -2, 1f, l = 10f))
        return r
    }
    private fun plainRow(label: String, value: CharSequence, valueColor: Int = AK.ink): LinearLayout = AU.row(ctx, Gravity.TOP).apply {
        addView(AU.t(ctx, label, 13f, AK.sub), AU.lp(0, -2, 1f, r = 10f))
        addView(AU.t(ctx, value, 13f, valueColor, true).apply { gravity = Gravity.END })
    }
    private fun yes(v: Boolean): CharSequence = android.text.SpannableString(if (v) "Yes" else "No").apply { setSpan(android.text.style.ForegroundColorSpan(if (v) AK.green else AK.red), 0, length, 0) }

    // ───────── WhatsApp OTP: the count, the balance at Meta, the setup check and a test send ─────────
    private var topupBusy = false
    private var testBusy = false
    private var testRes: JSONObject? = null
    private var testPhone = ""
    private fun drawOtp() {
        if (!alive) return
        otpBox.removeAllViews()
        val d = wa.data
        val sm = AF.o(d, "summary")
        if (sm != null) otpBox.addView(otpAccount(sm), AU.lp(-1, -2, b = 10f))
        else if (AF.s(d, "summary_error").isNotEmpty()) otpBox.addView(AU.card(ctx).apply { addView(AU.t(ctx, "OTP history isn't ready yet (${AF.s(d, "summary_error")}). Publish once more, then refresh.", 13f, AK.red)) }, AU.lp(-1, -2, b = 10f))
        val c = AU.card(ctx)
        c.addView(headRow(AdIc.MessageCircle, "WhatsApp OTP", "Checks the login OTP setup. The token itself is never shown."), AU.lp(-1, -2, b = 10f))
        c.addView(AU.errorLine(ctx, wa.error) { wa.load() })
        if (wa.loading && d == null) c.addView(AU.loading(ctx, 16f))
        else if (d != null) {
            val s = AF.o(d, "secrets"); val n = AF.o(d, "number"); val q = AF.o(d, "requests")
            val rows = AU.col(ctx)
            fun add(v: View) = rows.addView(v, AU.lp(-1, -2, t = if (rows.childCount > 0) 4f else 0f))
            add(plainRow("WHATSAPP_TOKEN set", android.text.SpannableStringBuilder(yes(AF.b(s, "token_set"))).append(if (AF.b(s, "token_set")) " · ${AF.s(s, "token_length")} letters" else "")))
            if (AF.b(s, "token_has_spaces")) add(plainRow("Token problem", "has a space or new line", AK.red))
            add(plainRow("WHATSAPP_PHONE_ID set", android.text.SpannableStringBuilder(yes(AF.b(s, "phone_id_set") && AF.b(s, "phone_id_ok"))).append(if (AF.b(s, "phone_id_set")) " · ends ${AF.s(s, "phone_id_end")}" else "")))
            add(plainRow("Template", "${AF.s(s, "template")} (${AF.s(s, "language")})"))
            if (AF.b(n, "ok")) {
                add(plainRow("Number at Meta", "${AF.s(n, "display_phone_number").ifEmpty { "—" }} · ${AF.s(n, "verified_name").ifEmpty { "—" }}"))
                add(plainRow("Display name", AF.s(n, "name_status").ifEmpty { "—" }))
                add(plainRow("Number status", "${AF.s(n, "status").ifEmpty { "—" }}${if (AF.s(n, "account_mode").isNotEmpty()) " · ${AF.s(n, "account_mode")}" else ""}"))
                add(plainRow("Quality · limit", "${AF.s(n, "quality_rating").ifEmpty { "—" }} · ${AF.s(n, "messaging_limit_tier").ifEmpty { "—" }}"))
            } else add(plainRow("Meta says", AF.s(n, "error").ifEmpty { "—" }, AK.red))
            add(plainRow("OTP requests reached server", "${AF.nf(AF.n(q, "last_hour"))} last hour · ${AF.nf(AF.n(q, "last_day"))} last 24 h"))
            c.addView(rows, AU.lp(-1, -2, b = 12f))
        }
        val tr = AU.row(ctx)
        val phone = AU.input(ctx, "10-digit number to test", testPhone, InputType.TYPE_CLASS_PHONE)
        phone.textSize = 16f
        phone.background = Tf.box(Color.WHITE, 10f, 1.5f, AK.line)
        phone.filter { it.replace(Regex("\\D"), "").take(10) }
        phone.height = Ui.dpi(40f); phone.minHeight = Ui.dpi(40f)
        val send = AU.btn(ctx, "Send test", "dark", icon = AdIc.Send, iconSize = 14f).also { it.off = testPhone.length != 10; it.busy = testBusy }
        phone.onText = { testPhone = it; send.off = it.length != 10 }
        send.setOnClickListener {
            if (send.busy || send.off) return@setOnClickListener
            testBusy = true; send.busy = true; testRes = null
            src.fn("whatsappCheck", JSONObject().put("action", "test").put("phone", phone.value)) { r ->
                testBusy = false
                testRes = if (r.ok) (AF.o(r.data, "result") ?: JSONObject()) else JSONObject().put("ok", false).put("error", AF.err(r))
                wa.load()
            }
        }
        tr.addView(phone, AU.lp(0, Ui.dpi(40f), 1f, r = 6f))
        tr.addView(send)
        c.addView(tr)
        testRes?.let { res ->
            val ok = AF.b(res, "ok")
            val code = AF.s(res, "meta_code"); val subc = AF.s(res, "meta_subcode"); val det = AF.s(res, "details")
            c.addView(AU.t(ctx, if (ok) "Meta accepted it ✓ (message id ${AF.s(res, "message_id").ifEmpty { "—" }}). If it still doesn't arrive, the number may not have WhatsApp."
                else "Meta said: ${AF.s(res, "error")}${if (code.isNotEmpty()) " (code $code${if (subc.isNotEmpty()) "/$subc" else ""})" else ""}${if (det.isNotEmpty()) " — $det" else ""}", 12.5f, if (ok) AK.green else AK.red, false, 18f), AU.lp(-1, -2, t = 10f))
        }
        otpBox.addView(c)
    }

    private fun otpAccount(sm: JSONObject): LinearLayout {
        val c = AU.card(ctx)
        c.addView(AU.t(ctx, "OTP count", 14.5f, AK.ink, true), AU.lp(-1, -2, b = 10f))
        val today = AF.o(sm, "today"); val month = AF.o(sm, "month")
        fun line(l: String, a1: String, a2: String, head: Boolean = false) = AU.row(ctx).apply {
            addView(AU.t(ctx, l, 13f, AK.sub), AU.lp(0, -2, 1f))
            addView(AU.t(ctx, a1, if (head) 12f else 13f, if (head) AK.sub else AK.ink, true).apply { gravity = Gravity.END }, AU.lp(Ui.dpi(70f), -2, l = 14f))
            addView(AU.t(ctx, a2, if (head) 12f else 13f, if (head) AK.sub else AK.ink, true).apply { gravity = Gravity.END }, AU.lp(Ui.dpi(80f), -2, l = 14f))
        }
        val grid = AU.col(ctx)
        grid.addView(line("", "Today", "This month", true))
        for ((l, k) in listOf("OTPs sent" to "sent", "Logged in (right code)" to "verified", "Send failed" to "failed", "Wrong codes typed" to "wrong", "Blocked by limits" to "limited", "Test sends" to "tests"))
            grid.addView(line(l, AF.nf(AF.n(today, k)), AF.nf(AF.n(month, k))), AU.lp(-1, -2, t = 4f))
        grid.addView(line("Cost (about)", AF.money2(AF.n(today, "cost")), AF.money2(AF.n(month, "cost"))), AU.lp(-1, -2, t = 4f))
        c.addView(grid)
        val added = AF.n(sm, "added"); val bal = AF.n(sm, "balance")
        val low = added > 0 && bal < 10
        val box = AU.col(ctx).apply { background = Tf.box(if (low) Ui.c("#FDECEC") else AK.tint, 12f); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)) }
        box.addView(plainRow("Money added at Meta", AF.money2(added)))
        box.addView(plainRow("Spent on OTPs (about)", AF.money2(AF.n(sm, "spent_all"))), AU.lp(-1, -2, t = 3f))
        box.addView(AU.row(ctx).apply {
            addView(AU.t(ctx, "Left (about)", 14f, AK.ink, true), AU.lp(0, -2, 1f))
            addView(AU.t(ctx, if (added > 0) AF.money2(bal) else "Add your top-up below", 14f, if (low) AK.red else AK.green, true))
        }, AU.lp(-1, -2, t = 6f))
        if (low) box.addView(AU.t(ctx, "Running low — add money in WhatsApp Manager → Billing, or OTPs will stop.", 12.5f, AK.red), AU.lp(-1, -2, t = 6f))
        val r = AU.row(ctx)
        val amt = AU.input(ctx, "₹ you added at Meta", type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        amt.textSize = 16f; amt.background = Tf.box(Color.WHITE, 10f, 1.5f, AK.line); amt.height = Ui.dpi(38f); amt.minHeight = Ui.dpi(38f); amt.setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
        amt.filter { it.replace(Regex("[^\\d.]"), "").take(8) }
        val sv = AU.btn(ctx, "Save", "dark", true).also { it.off = true; it.busy = topupBusy }
        amt.onText = { sv.off = !((it.toDoubleOrNull() ?: 0.0) > 0) }
        sv.setOnClickListener {
            if (sv.busy || sv.off) return@setOnClickListener
            topupBusy = true; sv.busy = true
            src.fn("whatsappCheck", JSONObject().put("action", "topup").put("amount", amt.value.toDoubleOrNull() ?: 0.0)) { x ->
                topupBusy = false; sv.busy = false
                if (x.ok) { say("Saved ✓"); wa.load() } else say(AF.err(x), true)
            }
        }
        r.addView(amt, AU.lp(0, Ui.dpi(38f), 1f, r = 6f)); r.addView(sv)
        box.addView(r, AU.lp(-1, -2, t = 10f))
        box.addView(AU.t(ctx, "Estimate at ${AF.money2(AF.n(sm, "rate"))} per OTP (Meta's price + GST), counted from when this history started. Meta charges only delivered messages, so the exact figure is in WhatsApp Manager → Billing Hub.", 11.5f, AK.sub, false, 16f), AU.lp(-1, -2, t = 8f))
        c.addView(box, AU.lp(-1, -2, t = 14f))
        c.addView(AU.t(ctx, "LATEST", 12f, AK.sub, true).apply { letterSpacing = 0.4f / 12f }, AU.lp(-1, -2, t = 14f, b = 6f))
        val recent = AF.list(sm, "recent")
        if (recent.isEmpty()) c.addView(AU.t(ctx, "Nothing yet — the next OTP will show here.", 12.5f, AK.sub))
        recent.forEachIndexed { i, x ->
            val ok = AF.b(x, "ok"); val kind = AF.s(x, "kind")
            val row = AU.row(ctx)
            row.addView(View(ctx).apply { background = Tf.circle(if (ok) AK.green else if (kind == "limited") Ui.c("#B45309") else AK.red) }, LinearLayout.LayoutParams(Ui.dpi(8f), Ui.dpi(8f)))
            row.addView(AU.t(ctx, KIND_LABEL[kind] ?: kind, 12.5f, AK.ink, true).apply { maxLines = 1 }, AU.lp(-2, -2, l = 8f))
            val end = AF.s(x, "phone_end")
            row.addView(AU.t(ctx, if (end.isNotEmpty()) "…$end" else "", 12.5f, AK.sub).apply { maxLines = 1 }, AU.lp(-2, -2, l = 8f))
            row.addView(AU.t(ctx, if (ok) (if (kind == "verify") "Right code" else "Sent") else AF.s(x, "error").ifEmpty { "Failed" }, 12.5f, if (ok) AK.sub else AK.red).ell(), AU.lp(0, -2, 1f, l = 8f))
            val at = x.opt("at")
            val agoTxt = when (at) { is Number -> AF.agoMs(at.toLong()); is String -> at.toLongOrNull()?.let { AF.agoMs(it) } ?: AF.ago(at); else -> "—" }
            row.addView(AU.t(ctx, agoTxt, 12.5f, AK.sub).apply { maxLines = 1 }, AU.lp(-2, -2, l = 8f))
            c.addView(row, AU.lp(-1, -2, t = if (i > 0) 6f else 0f))
        }
        return c
    }

    // ───────── Offer notification to every phone ─────────
    private var offerUrl = "/wallet"
    private var offerTitle = ""
    private var offerBody = ""
    private var offerBusy = false
    private var pumping = false
    private var pumpFor = ""
    private fun drawOffers() {
        if (!alive) return
        offerBox.removeAllViews()
        val d = offers.data
        val c = AU.card(ctx)
        val phones = AF.n(d, "phones")
        c.addView(headRow(AdIc.Megaphone, "Offer notification", "Goes to every phone that allowed notifications${if (d != null) " (${if (phones < 0) "—" else AF.nf(phones)} phones)" else ""}, even when True Find is closed. Calls, messages, likes and Daily Free Coins are sent by themselves."))
        c.addView(AU.errorLine(ctx, offers.error) { offers.load() }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.dpi(10f) })
        val list = AF.list(d, "offers")
        val going = list.firstOrNull { AF.s(it, "status") == "sending" }
        val form = AU.col(ctx)
        val title = AU.input(ctx, "Title, e.g. 50% extra coins today", offerTitle, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val body = AU.area(ctx, "Offer text, e.g. Buy any pack before midnight and get 50% extra coins.", 2, offerBody)
        val send = AU.btn(ctx, "Send to everyone", "dark", icon = AdIc.Send, iconSize = 14f).also { it.busy = offerBusy }
        fun can() { send.off = offerTitle.trim().isEmpty() || offerBody.trim().isEmpty() || going != null }
        title.onText = { offerTitle = it; can() }; body.onText = { offerBody = it; can() }
        form.addView(title, AU.lp(-1, -2))
        form.addView(body, AU.lp(-1, -2, t = 8f))
        val chips = FlowRow(ctx, Ui.dpi(6f), Ui.dpi(6f))
        for ((u, l) in OFFER_PAGES) {
            val on = offerUrl == u
            chips.addView(AU.t(ctx, "Opens $l", 12.5f, if (on) AK.deep else AK.sub, true).apply {
                background = Tf.box(if (on) AK.tint else Color.WHITE, 999f, 1f, if (on) AK.deep else AK.line)
                setPadding(Ui.dpi(10f), Ui.dpi(6f), Ui.dpi(10f), Ui.dpi(6f))
                setOnClickListener { offerUrl = u; drawOffers() }
            })
        }
        form.addView(chips, AU.lp(-1, -2, t = 8f))
        send.setOnClickListener {
            if (send.busy || send.off) return@setOnClickListener
            offerBusy = true; send.busy = true
            src.fn("adminOps", JSONObject().put("action", "offer_create").put("title", offerTitle).put("body", offerBody).put("url", offerUrl)) { r ->
                offerBusy = false
                if (r.ok) { say("Offer is going out ✓"); offerTitle = ""; offerBody = ""; offers.load() } else { say(AF.err(r), true); drawOffers() }
            }
        }
        form.addView(send, AU.lp(-1, -2, t = 8f))
        can()
        c.addView(form, AU.lp(-1, -2, t = 12f))
        if (offers.loading && d == null) c.addView(AU.loading(ctx, 14f))
        else if (list.isNotEmpty()) {
            val l = AU.col(ctx)
            list.take(6).forEachIndexed { i, o ->
                val box = AU.col(ctx).apply { background = Tf.box(AK.soft, 12f, 1f, AK.line); setPadding(Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(8f)) }
                val st = AF.s(o, "status")
                box.addView(AU.row(ctx, Gravity.TOP).apply {
                    addView(AU.t(ctx, AF.s(o, "title"), 13f, AK.ink, true), AU.lp(0, -2, 1f, r = 8f))
                    addView(AU.t(ctx, mapOf("sending" to "Sending…", "done" to "Sent", "cancelled" to "Stopped")[st] ?: st, 12f, if (st == "sending") AK.deep else AK.sub, true).apply { maxLines = 1 })
                })
                box.addView(AU.t(ctx, AF.s(o, "body"), 12.5f, AK.sub), AU.lp(-1, -2, t = 2f, b = 4f))
                val tot = AF.n(o, "total")
                box.addView(AU.row(ctx).apply {
                    addView(AU.t(ctx, "${AF.nf(AF.n(o, "sent"))} phones got it${if (tot > 0) " of ${AF.nf(tot)}" else ""} · ${AF.ago(AF.s(o, "created_date"))}", 12f, AK.sub), AU.lp(0, -2, 1f, r = 8f))
                    if (st == "sending") addView(AU.btn(ctx, "Stop", "light", true, AdIc.Square, 12f) {
                        src.fn("adminOps", JSONObject().put("action", "offer_cancel").put("id", AF.s(o, "id"))) { r -> if (r.ok) { say("Stopped"); offers.load() } else say(AF.err(r), true) }
                    })
                })
                l.addView(box, AU.lp(-1, -2, t = if (i > 0) 8f else 0f))
            }
            c.addView(l, AU.lp(-1, -2, t = 12f))
        }
        offerBox.addView(c)
        // keep sending, a batch every call, while an offer is going out and this screen is open
        if (going != null && !pumping) pump(AF.s(going, "id"))
    }
    private fun pump(id: String) {
        pumping = true; pumpFor = id
        fun step() {
            if (!alive || !pumping) { pumping = false; return }
            src.fn("adminOps", JSONObject().put("action", "offer_step")) { r ->
                if (!alive) { pumping = false; return@fn }
                if (!r.ok) { main.postDelayed({ step() }, 8000); return@fn }
                offers.load {
                    if (AF.b(r.data, "done")) { pumping = false; return@load }
                    if (AF.n(r.data, "sent") == 0.0) main.postDelayed({ step() }, 5000) else step()
                }
            }
        }
        step()
    }

    // ───────── background jobs ─────────
    private var jobsRunning = false
    private var lastRun: JSONObject? = null
    private var details = false
    private fun drawJobs() {
        if (!alive) return
        jobsBox.removeAllViews()
        val d = jobs.data
        val s = AF.o(d, "status")
        val r = lastRun ?: AF.o(s, "last_result")
        val c = AU.card(ctx)
        c.addView(headRow(AdIc.Cpu, "Background jobs", "Run by themselves every 2 minutes while anyone has the app open: end calls where both phones went quiet (billed fairly), close calls that rang with no answer, flag interrupted bills, free people stuck as “busy”, and clean old codes and error reports."))
        c.addView(AU.errorLine(ctx, jobs.error) { jobs.load() }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.dpi(10f) })
        if (jobs.loading && d == null) c.addView(AU.loading(ctx, 16f))
        else {
            val rows = AU.col(ctx)
            val ms = AF.n(s, "last_duration_ms")
            for ((l, v) in listOf(
                "Last run" to (if (AF.b(s, "never_ran")) "Not yet" else "${AF.ago(AF.s(s, "last_started_at"))} (${AF.s(s, "last_trigger").ifEmpty { "—" }})"),
                "Took" to (if (ms > 0) String.format(java.util.Locale.US, "%.1f s", ms / 1000) else "—"),
                "Total runs" to AF.nf(AF.n(s, "runs")),
                "Last clean-up" to (if (AF.s(s, "last_cleanup_at").isNotEmpty()) AF.ago(AF.s(s, "last_cleanup_at")) else "—"),
            )) rows.addView(AU.row(ctx).apply { addView(AU.t(ctx, l, 13f, AK.sub), AU.lp(0, -2, 1f)); addView(AU.t(ctx, v, 13f, AK.ink, true)) }, AU.lp(-1, -2, t = if (rows.childCount > 0) 3f else 0f))
            if (r != null) rows.addView(AU.t(ctx, "Last run: ${AF.nf(AF.n(r, "calls_ended"))} calls ended · ${AF.nf(AF.n(r, "rings_closed"))} rings closed · ${AF.nf(AF.n(r, "settlements_flagged"))} bills flagged · ${AF.nf(AF.n(r, "people_freed"))} people freed${if (AF.b(r, "cleanup_ran")) " · ${AF.nf(AF.n(r, "otp_rows_deleted") + AF.n(r, "errors_deleted"))} old rows cleaned" else ""}", 12.5f, AK.sub), AU.lp(-1, -2, t = 6f))
            val le = AF.s(s, "last_error")
            if (le.isNotEmpty()) rows.addView(AU.t(ctx, "Problem: $le", 12.5f, AK.red), AU.lp(-1, -2, t = 4f))
            c.addView(rows, AU.lp(-1, -2, t = 12f, b = 12f))
        }
        val run = AU.btn(ctx, "Run now", "dark", icon = AdIc.Play, iconSize = 15f).also { it.busy = jobsRunning }
        run.setOnClickListener {
            if (run.busy) return@setOnClickListener
            jobsRunning = true; run.busy = true
            src.fn("adminOps", JSONObject().put("action", "jobs_run")) { x ->
                jobsRunning = false
                if (x.ok) { if (AF.b(x.data, "busy")) say("Jobs are running right now — try again in a minute.", true) else { lastRun = AF.o(x.data, "summary"); say("Jobs finished ✓") }; jobs.load() }
                else { say(AF.err(x), true); drawJobs() }
            }
        }
        c.addView(run, AU.lp(-1, -2))
        c.addView(AU.t(ctx, "${if (details) "▾" else "▸"} Also run them when nobody is online (optional)", 12.5f, AK.deep, true).apply { setOnClickListener { details = !details; drawJobs() }; setPadding(0, Ui.dpi(4f), 0, Ui.dpi(4f)) }, AU.lp(-1, -2, t = 6f))
        if (details) {
            val sb = android.text.SpannableStringBuilder("1. In Base44 → Secrets, add ")
            var st = sb.length; sb.append("JOBS_KEY"); sb.setSpan(TypefaceSpanCompat(Ui.bold), st, sb.length, 0)
            sb.append(" with any long secret word. 2. On cron-job.org (free), make a job every 5 minutes that sends a POST to the address below with the body ")
            st = sb.length; sb.append("{\"key\":\"your secret word\"}"); sb.setSpan(android.text.style.TypefaceSpan("monospace"), st, sb.length, 0)
            sb.append(".")
            c.addView(AU.t(ctx, sb, 12.5f, AK.sub, false, 18f), AU.lp(-1, -2, t = 4f, b = 6f))
            val url = "${src.origin}/api/apps/${src.appId.ifEmpty { "<app id>" }}/functions/runJobs"
            c.addView(AU.t(ctx, url, 11.5f, AK.ink).apply {
                background = Tf.box(AK.soft, 10f, 1f, AK.line); setPadding(Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(8f))
                compoundDrawablePadding = Ui.dpi(6f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(AdIc.Copy, AK.ink, 13f), null, null, null)
                setOnClickListener { AU.copy(ctx, url); say("Copied") }
            }, AU.lp(-1, -2))
        }
        jobsBox.addView(c)
    }

    // ───────── backup (your own copy of the main tables) ─────────
    private var backupBusy = ""
    private var progress = ""
    private fun drawBackup() {
        if (!alive) return
        backupBox.removeAllViews()
        val c = AU.card(ctx)
        c.addView(headRow(AdIc.DatabaseBackup, "Backup", "Base44 keeps your data safe. This gives you your own copy too — keep it somewhere private (it has phone numbers)."), AU.lp(-1, -2, b = 10f))
        c.addView(AU.btn(ctx, "Download full backup", "dark", icon = AdIc.Download, iconSize = 15f) { all() }.also { it.busy = backupBusy == "all"; it.off = backupBusy.isNotEmpty() && backupBusy != "all" }, AU.lp(-1, -2))
        if (progress.isNotEmpty()) c.addView(AU.t(ctx, progress, 12f, AK.sub), AU.lp(-1, -2, t = 8f))
        c.addView(AU.wrap(ctx, 6f, *TABLES.map { (e, l) -> AU.btn(ctx, l, "light", true) { one(e, l) }.also { it.busy = backupBusy == e; it.off = backupBusy.isNotEmpty() && backupBusy != e } }.toTypedArray()), AU.lp(-1, -2, t = 10f))
        backupBox.addView(c)
    }
    private fun pull(entity: String, label: String, done: (JSONArray?, String?) -> Unit) {
        val rows = JSONArray()
        fun page(cursor: String?, n: Int) {
            val body = JSONObject().put("action", "export").put("entity", entity)
            if (cursor != null) body.put("cursor", cursor)
            src.fn("adminOps", body) { r ->
                if (!alive) return@fn
                if (!r.ok) { done(null, AF.err(r)); return@fn }
                val items = r.data.optJSONArray("items")
                if (items != null) for (i in 0 until items.length()) rows.put(items.opt(i))
                progress = "$label: ${AF.nf(rows.length())} rows…"; drawBackup()
                val next = AF.s(r.data, "next_cursor")
                if (next.isEmpty() || n >= 399) done(rows, null) else page(next, n + 1)
            }
        }
        page(null, 0)
    }
    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
    private fun one(entity: String, label: String) {
        if (backupBusy.isNotEmpty()) return
        backupBusy = entity; drawBackup()
        pull(entity, label) { rows, err ->
            backupBusy = ""; progress = ""
            if (rows == null) { say(err ?: "Something went wrong.", true); drawBackup(); return@pull }
            a.saveFile("truefind-$entity-${today()}.json", "application/json", rows.toString(1).toByteArray(Charsets.UTF_8)) { ok -> if (ok) say("$label: ${rows.length()} rows downloaded") }
            drawBackup()
        }
    }
    private fun all() {
        if (backupBusy.isNotEmpty()) return
        backupBusy = "all"; drawBackup()
        val out = JSONObject().put("exported_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
        fun next(i: Int) {
            if (i >= TABLES.size) {
                backupBusy = ""; progress = ""
                a.saveFile("truefind-backup-${today()}.json", "application/json", out.toString().toByteArray(Charsets.UTF_8)) { ok -> if (ok) say("Backup downloaded ✓") }
                drawBackup(); return
            }
            val (e, l) = TABLES[i]
            pull(e, l) { rows, err ->
                if (rows == null) { backupBusy = ""; progress = ""; say(err ?: "Something went wrong.", true); drawBackup(); return@pull }
                out.put(e, rows); next(i + 1)
            }
        }
        next(0)
    }

    // ───────── audit log ─────────
    private fun drawAudit() {
        if (!alive) return
        auditBox.removeAllViews()
        val c = AU.card(ctx, 0f).apply { clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        val h = AU.row(ctx).apply { setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f)) }
        h.addView(AU.icon(ctx, AdIc.ScrollText, AK.deep, 16f))
        h.addView(AU.t(ctx, "Audit log", 14f, AK.ink, true), AU.lp(-2, -2, l = 8f))
        h.addView(AU.t(ctx, "— every ban, coin change, payout and setting", 12f, AK.faint).ell(), AU.lp(0, -2, 1f, l = 8f))
        c.addView(h)
        c.addView(AU.divider(ctx))
        val err = AU.errorLine(ctx, audit.error) { audit.load() }
        if (audit.error.isNotEmpty()) c.addView(FrameLayout(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), 0); addView(err) })
        val list = AF.list(audit.data, "actions")
        if (audit.loading && audit.data == null) c.addView(AU.loading(ctx, 16f))
        else if (list.isEmpty()) c.addView(AU.t(ctx, "Nothing yet.", 13f, AK.sub).apply { setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) })
        else list.forEachIndexed { i, x ->
            if (i > 0) c.addView(AU.divider(ctx))
            c.addView(AU.col(ctx).apply {
                setPadding(Ui.dpi(14f), Ui.dpi(9f), Ui.dpi(14f), Ui.dpi(9f))
                val amt = x.opt("amount")
                addView(AU.t(ctx, "${AF.s(x, "action").replace("_", " ")}${if (amt != null && amt != JSONObject.NULL) " · $amt" else ""}", 13f, AK.ink, true))
                val det = AF.s(x, "details")
                if (det.isNotEmpty()) addView(AU.t(ctx, det, 12f, AK.sub), AU.lp(-1, -2, t = 1f))
                addView(AU.t(ctx, "${AF.fmt(AF.s(x, "created_date"))} · ${AF.s(x, "admin_email")}", 11f, AK.faint), AU.lp(-1, -2, t = 1f))
            })
        }
        auditBox.addView(c)
    }

    // ───────── testing tools (admin only) ─────────
    private var testKey = ""
    private fun drawTest() {
        if (!alive) return
        testBox.removeAllViews()
        val c = AU.card(ctx)
        c.addView(headRow(AdIc.Sparkles, "Testing", "Only you see this. Demo accounts are switched off: everyone, you too, sees real members only.", AK.amberBg, AK.amber), AU.lp(-1, -2, b = 10f))
        fun b(key: String, icon: String, label: String, calls: List<Pair<String, JSONObject>>, msg: String, kind: String = "light") =
            AU.btn(ctx, label, kind, true, icon, 14f) { runTest(key, calls, msg) }.also { it.busy = testKey == key; it.off = testKey.isNotEmpty() && testKey != key }
        c.addView(AU.wrap(ctx, 6f,
            b("coins", AdIc.Coins, "Give me 5,000 test coins", listOf("demoData" to JSONObject().put("action", "demo_coins")), "5,000 test coins added ✓"),
            b("host", AdIc.Radio, "Make me an approved Host", listOf("demoData" to JSONObject().put("action", "demo_host")), "You're an approved Host now ✓"),
            b("clear", AdIc.Trash2, "Remove all demo data", listOf("liveRoom" to JSONObject().put("action", "demo_clear"), "liveDance" to JSONObject().put("action", "demo_clear"), "demoData" to JSONObject().put("action", "clear")), "All demo data removed ✓", "dangerLight")))
        testBox.addView(c)
    }
    private fun runTest(key: String, calls: List<Pair<String, JSONObject>>, msg: String) {
        if (testKey.isNotEmpty()) return
        testKey = key; drawTest()
        fun next(i: Int) {
            if (i >= calls.size) { testKey = ""; say(msg); drawTest(); return }
            src.fn(calls[i].first, calls[i].second) { r ->
                if (!r.ok) { testKey = ""; say(AF.err(r, "Action failed."), true); drawTest(); return@fn }
                next(i + 1)
            }
        }
        next(0)
    }

    override fun previewOpen(action: String) {
        when (action) { "details" -> { details = true; drawJobs(); jobsBox.postDelayed({ a.scrollToView(jobsBox) }, 400) } }
    }
}
