package app.truefind.android

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max

private object HC {
    val ink = Ui.c("#1E1433"); val sub = Ui.c("#5F5673"); val soft = Ui.c("#8A829B"); val line = Ui.c("#E7E0F2"); val faint = Ui.c("#F6F3FB")
    val purple = Ui.c("#4C1D95"); val purpleSoft = Ui.c("#F1EAFB"); val black = Ui.c("#17121F"); val red = Ui.c("#B91C1C")
    val green = Ui.c("#0F6B3F"); val greenBg = Ui.c("#E3F5EA"); val amber = Ui.c("#7A4B00"); val amberBg = Ui.c("#FFF1D6")
}

private fun pill(ctx: Context, label: String, bg: Int, fg: Int): TextView = Tf.t(ctx, label, 11.5f, fg).apply {
    gravity = Gravity.CENTER; background = Tf.box(bg, 999f); setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0); maxLines = 1
    layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(22f))
}
private fun hCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 16f, 1f, HC.line); setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) }

/**
 * Refer & Earn (components/ReferPanel.jsx): my invite code (copy / share), the numbers, a
 * friend's code in the first 7 days, how it works and the friends who joined. Coins are paid
 * to both after the friend's first recharge.
 */
class PRefer(ctx: Context, ps: ProfileScreen) : PPanel(ctx, ps) {
    override val view = col()
    private var data: JSONObject? = null
    private var err = ""
    private var code = ""
    private var busy = false
    private var copied = false
    private var alive = true
    private val codeRe = Regex("^TF[A-HJ-NP-Z2-9]{5}$")

    init {
        // a code from an invite link waits on this phone (the web keeps it): it fills the box
        ps.pages.js("(function(){try{return localStorage.getItem('tf_ref')||''}catch(e){return ''}})()") { v -> if (alive && v != null && codeRe.matches(v) && code.isEmpty()) { code = v; if (data != null) render() } }
        render()
        load()
    }
    override fun release() { alive = false }

    private fun errOf(r: Api.Result, d: String) = PR.s(r.data, "error").ifEmpty { d }

    private fun load() {
        Api.invoke("referral", JSONObject().put("action", "mine")) { r ->
            if (!alive) return@invoke
            if (r.ok) { data = r.data; err = "" } else err = errOf(r, "Could not load Refer & Earn. Please try again.")
            render()
        }
    }

    private fun share(message: String) {
        try {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "True Find"); putExtra(Intent.EXTRA_TEXT, message) }, null))
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(message)))) } catch (e2: Exception) { }
        }
    }

    private fun apply() {
        if (busy || code.trim().isEmpty()) return
        busy = true; render()
        ps.pages.deviceId { dev ->
            val body = JSONObject().put("action", "apply").put("code", code.trim())
            if (!dev.isNullOrEmpty()) body.put("device_id", dev)
            Api.invoke("referral", body) { r ->
                if (!alive) return@invoke
                busy = false
                if (r.ok) {
                    ps.pages.js("(function(){try{localStorage.removeItem('tf_ref')}catch(e){}return ''})()") { }
                    val d = r.data
                    ps.say("Invite from ${PR.s(d, "inviter").ifEmpty { "your friend" }} added. Recharge ₹${PR.s(d, "min_recharge").ifEmpty { "49" }}+ and you both get ${PR.s(d, "reward").ifEmpty { PR.s(data, "reward") }} coins.")
                    code = ""
                    load()
                } else {
                    ps.say(errOf(r, "Could not use this code."), true)
                    if (r.status == 409 || r.status == 403) ps.pages.js("(function(){try{localStorage.removeItem('tf_ref')}catch(e){}return ''})()") { }
                }
                render()
            }
        }
    }

    private fun render() {
        view.removeAllViews()
        val d = data
        if (d == null) {
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, Ui.dpi(24f), 0, Ui.dpi(24f)) }
            if (err.isNotEmpty()) {
                c.addView(Tf.t(ctx, err, 14f, HC.red, false).apply { gravity = Gravity.CENTER })
                c.addView(Tf.t(ctx, "Try again", 14f, HC.purple).apply {
                    gravity = Gravity.CENTER; background = Tf.box(HC.purpleSoft, 12f); setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0)
                    setOnClickListener { err = ""; render(); load() }; Ui.pressable(this, .97f)
                }, LinearLayout.LayoutParams(-2, Ui.dpi(40f)).apply { topMargin = Ui.dpi(10f) })
            } else c.addView(TfLoader(ctx, 24f, HC.purple), LinearLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f)))
            view.addView(c, lp())
            return
        }
        if (d.opt("enabled") != true && !PR.truthy(d.opt("enabled"))) {
            val c = hCard(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
            c.addView(Tf.t(ctx, "Refer & Earn is paused right now", 15f, HC.ink).apply { gravity = Gravity.CENTER })
            c.addView(Tf.t(ctx, "Please check again later.", 13f, HC.sub, false).apply { gravity = Gravity.CENTER }, lp(4f))
            view.addView(c, lp())
            return
        }
        val reward = PR.s(d, "reward")
        val myCode = PR.s(d, "code")
        val minR = PR.s(d, "min_recharge")
        val message = "Join me on True Find — talk to real people on voice and video calls. Use my invite code $myCode and we both get $reward free coins after your first recharge: ${Api.origin}/?ref=${Uri.encode(myCode)}"
        // the offer + my code
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(HC.black, 16f); setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) }
        val r1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gift = FrameLayout(ctx).apply { background = Tf.box(Color.argb(31, 255, 255, 255), 12f) }
        gift.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Gift, Color.WHITE, 21f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        r1.addView(gift, LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f)).apply { rightMargin = Ui.dpi(10f) })
        val t1 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t1.addView(Tf.t(ctx, "Invite friends, both get $reward coins", 17f, Color.WHITE))
        t1.addView(Tf.t(ctx, "After your friend's first recharge of ₹$minR or more", 12.5f, Ui.c("#C9C0DA"), false), lp(1f))
        r1.addView(t1, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(r1)
        top.addView(Tf.t(ctx, "YOUR INVITE CODE", 11.5f, Ui.c("#C9C0DA")).apply { letterSpacing = 1f / 11.5f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f); bottomMargin = Ui.dpi(6f) })
        val r2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(Tf.t(ctx, myCode, 22f, HC.black).apply { gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 13f); letterSpacing = 3f / 22f; contentDescription = "Your invite code" },
            LinearLayout.LayoutParams(0, Ui.dpi(48f), 1f).apply { rightMargin = Ui.dpi(8f) })
        val cp = FrameLayout(ctx).apply {
            background = Tf.box(Color.argb(36, 255, 255, 255), 13f); contentDescription = "Copy code"
            addView(ImageView(ctx).apply { setImageDrawable(if (copied) PUi.lu(Lu.Check, Color.WHITE, 20f) else PUi.lu(Lu.Copy, Color.WHITE, 19f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener {
                if (ps.copy(myCode)) { copied = true; render(); postDelayedSafe(1600) { copied = false; render() } } else ps.say("Could not copy. Please copy the code by hand.", true)
            }
            Ui.pressable(this, .97f)
        }
        r2.addView(cp, LinearLayout.LayoutParams(Ui.dpi(48f), Ui.dpi(48f)))
        top.addView(r2)
        top.addView(PUi.iconText(ctx, "Share invite", Lu.Share2, 15f, Color.WHITE, 18f, 8f, Tf.box(Ui.c("#7C3AED"), 13f)).apply {
            setOnClickListener { share(message) }; Ui.pressable(this, .97f)
        }, LinearLayout.LayoutParams(-1, Ui.dpi(46f)).apply { topMargin = Ui.dpi(10f) })
        view.addView(top, lp())
        // numbers
        val s = d.optJSONObject("stats")
        val nums = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Joined" to "joined", "Rewarded" to "rewarded", "Coins earned" to "coins").forEachIndexed { i, (l, k) ->
            val c = hCard(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f)) }
            c.addView(Tf.t(ctx, Tf.inr((PR.firstNum(s, listOf(k)) ?: 0.0)), 20f, HC.ink).apply { gravity = Gravity.CENTER })
            c.addView(Tf.t(ctx, l, 11.5f, HC.sub).apply { gravity = Gravity.CENTER }, lp(1f))
            nums.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = Ui.dpi(8f) })
        }
        view.addView(nums, lp(12f))
        // a friend's code (first 7 days only)
        if (PR.truthy(d.opt("can_enter_code"))) {
            val c = hCard(ctx)
            c.addView(Tf.t(ctx, "Have a friend's code?", 14.5f, HC.ink))
            val days = PR.firstNum(d, listOf("enter_days_left"))?.toLong() ?: 0
            c.addView(Tf.t(ctx, "You can add it in your first 7 days · ${PR.s(d, "enter_days_left")} day${if (days > 1) "s" else ""} left", 12.5f, HC.sub, false),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(2f); bottomMargin = Ui.dpi(10f) })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val input = TfInput(ctx, "Friend's code", lineColor = HC.line).apply {
                setText(code); sizeDp(16f); typeface = Ui.bold; letterSpacing = 2f / 16f; contentDescription = "Friend's invite code"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = Tf.box(Color.WHITE, 12f, 1f, HC.line); setOnFocusChangeListener { _, _ -> }
                setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            }
            row.addView(input, LinearLayout.LayoutParams(0, Ui.dpi(46f), 1f).apply { rightMargin = Ui.dpi(8f) })
            val ab = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = Tf.box(HC.purple, 12f); setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0) }
            if (busy) ab.addView(TfLoader(ctx, 16f, Color.WHITE), LinearLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f)).apply { rightMargin = Ui.dpi(6f) })
            ab.addView(Tf.t(ctx, "Apply", 14.5f, Color.WHITE))
            val paintBtn = { val on = !busy && code.length >= 7; ab.alpha = if (on) 1f else 0.5f; ab.isEnabled = on }
            input.onText = { t ->
                val c2 = t.uppercase().replace(Regex("[^A-Z0-9]"), "").take(7)
                if (c2 != t) { input.setText(c2); input.setSelection(c2.length) }
                code = c2; paintBtn()
            }
            ab.setOnClickListener { apply() }
            Ui.pressable(ab, .97f)
            paintBtn()
            row.addView(ab, LinearLayout.LayoutParams(-2, Ui.dpi(46f)))
            c.addView(row)
            view.addView(c, lp(12f))
        }
        d.optJSONObject("my_invite")?.let { mi ->
            val c = hCard(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val st = PR.s(mi, "status")
            val text = when (st) {
                "rewarded" -> "You got ${PR.s(mi, "coins")} welcome coins from your invite."
                "pending" -> "Your invite is added. Make a recharge of ₹$minR+ to get $reward coins."
                else -> "Your invite code can't give coins on this account."
            }
            c.addView(Tf.t(ctx, text, 13.5f, HC.ink, false), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(10f) })
            c.addView(statusPill(st))
            view.addView(c, lp(12f))
        }
        // how it works
        val how = hCard(ctx)
        how.addView(Tf.t(ctx, "How it works", 14.5f, HC.ink), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(8f) })
        listOf("1" to "Share your code or link with a friend.", "2" to "They sign up and enter your code in their first 7 days (the link fills it in).",
            "3" to "When they recharge ₹$minR or more for the first time, you both get $reward coins.").forEach { (n, t) ->
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dpi(5f), 0, Ui.dpi(5f)) }
            r.addView(Tf.t(ctx, n, 12f, HC.purple).apply { gravity = Gravity.CENTER; background = Tf.circle(HC.purpleSoft) }, LinearLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f)).apply { rightMargin = Ui.dpi(10f) })
            r.addView(PUi.para(ctx, t, 13.5f, HC.ink, 19f), LinearLayout.LayoutParams(0, -2, 1f))
            how.addView(r)
        }
        how.addView(PUi.para(ctx, "One reward per new person and phone. Codes used on the same phone, fake accounts or misuse get no coins and can lead to a ban.", 11.5f, HC.soft, 16f), lp(8f))
        view.addView(how, lp(12f))
        // my friends
        val friends = Tf.arr(d.optJSONArray("friends"))
        if (friends.isNotEmpty()) {
            val c = hCard(ctx)
            c.addView(Tf.t(ctx, "Friends who joined", 14.5f, HC.ink), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(4f) })
            friends.forEachIndexed { i, f ->
                if (i > 0) c.addView(View(ctx).apply { setBackgroundColor(HC.line) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dpi(9f), 0, Ui.dpi(9f)) }
                val nm = PR.s(f, "name")
                r.addView(Tf.t(ctx, nm.ifEmpty { "F" }.take(1), 14f, HC.purple).apply { gravity = Gravity.CENTER; background = Tf.circle(HC.purpleSoft) }, LinearLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f)).apply { rightMargin = Ui.dpi(10f) })
                val sp = android.text.SpannableStringBuilder(nm)
                val coins = PR.s(f, "coins")
                if (PR.truthy(f.opt("coins"))) { val a = sp.length; sp.append(" · +$coins"); sp.setSpan(android.text.style.ForegroundColorSpan(HC.green), a, sp.length, 0) }
                r.addView(Tf.t(ctx, sp, 14f, HC.ink), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(10f) })
                r.addView(statusPill(PR.s(f, "status")))
                c.addView(r)
            }
            view.addView(c, lp(12f))
        }
    }

    private fun statusPill(st: String): TextView = when (st) {
        "rewarded" -> pill(ctx, "Coins earned", HC.greenBg, HC.green)
        "not_eligible" -> pill(ctx, "Not eligible", Ui.c("#EEEAF4"), HC.sub)
        else -> pill(ctx, "Waiting for recharge", HC.amberBg, HC.amber)
    }

    private fun postDelayedSafe(ms: Long, f: () -> Unit) { view.postDelayed({ if (alive) f() }, ms) }

    override fun previewOpen(what: String) {}
}

/**
 * Help & support → Contact support (components/SupportPanel.jsx): write to the True Find team
 * (topic, message, a screenshot) and read the team's replies in the conversations.
 */
class PSupport(val ctx: Context, private val ps: ProfileScreen) {
    private val CATS = listOf("payment" to "Coins & recharge", "calls" to "Calls", "payout" to "Withdrawal", "verification" to "Verification",
        "report" to "Report a person", "account" to "My account", "bug" to "App problem", "other" to "Other")
    private fun label(k: String) = CATS.firstOrNull { it.first == k }?.second

    val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 16f, 1f, HC.line); setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f)) }
    private var tickets: List<JSONObject>? = null
    private var email = ""; private var whatsapp = ""
    private var openId = ""
    private var writing = false
    private var failed = false
    private var alive = true
    // new message
    private var category = ""; private var text = ""; private var shot = ""; private var uploading = false; private var busy = false
    // reply
    private var reply = ""; private var replyBusy = false

    init {
        view.addView(Tf.t(ctx, "CONTACT SUPPORT", 12f, HC.soft).apply { letterSpacing = 0.4f / 12f }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(10f) })
        view.addView(box, LinearLayout.LayoutParams(-1, -2))
        render()
        load()
    }
    fun release() { alive = false }

    private fun errOf(r: Api.Result, d: String) = PR.s(r.data, "error").ifEmpty { if (r.status > 0) "Request failed with status code ${r.status}" else "Network Error" }.ifEmpty { d }

    private fun load(then: (() -> Unit)? = null) {
        Api.invoke("support", JSONObject().put("action", "mine")) { r ->
            if (!alive) return@invoke
            if (r.ok) {
                tickets = Tf.arr(r.data.optJSONArray("tickets"))
                val c = r.data.optJSONObject("contact")
                email = PR.s(c, "email"); whatsapp = PR.s(c, "whatsapp")
                failed = false
            } else { failed = true; if (tickets == null) tickets = emptyList() }
            render(); then?.invoke()
        }
    }

    private fun render() {
        box.removeAllViews()
        val open = tickets?.firstOrNull { PR.s(it, "id") == openId }
        when {
            open != null -> thread(open)
            writing -> newMessage()
            else -> home()
        }
    }

    private fun home() {
        box.addView(PUi.para(ctx, "Didn't find your answer? Write to us — our team replies here and you get a notification.", 13.5f, HC.sub, 19f))
        box.addView(PUi.iconText(ctx, "New message", Lu.Plus, 15f, Color.WHITE, 17f, 8f, Tf.box(HC.black, 13f)).apply {
            setOnClickListener { writing = true; render() }; Ui.pressable(this, .97f)
        }, LinearLayout.LayoutParams(-1, Ui.dpi(46f)).apply { topMargin = Ui.dpi(12f) })
        if (email.isNotEmpty() || whatsapp.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun link(t: String, inner: String, go: () -> Unit) = PUi.iconText(ctx, t, inner, 13f, HC.purple, 15f, 6f, Tf.box(Color.WHITE, 12f, 1.5f, HC.line)).apply {
                setOnClickListener { go() }; Ui.pressable(this, .97f)
            }
            if (email.isNotEmpty()) row.addView(link("Email us", Lu.Mail) { open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))) }, LinearLayout.LayoutParams(0, Ui.dpi(40f), 1f))
            if (whatsapp.isNotEmpty()) row.addView(link("WhatsApp", Lu.MessageCircle) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + whatsapp.replace(Regex("\\D"), "")))) },
                LinearLayout.LayoutParams(0, Ui.dpi(40f), 1f).apply { if (row.childCount > 0) leftMargin = Ui.dpi(8f) })
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(10f) })
        }
        val t = tickets
        if (t == null) {
            val f = FrameLayout(ctx).apply { setPadding(0, Ui.dpi(18f), 0, Ui.dpi(4f)) }
            f.addView(TfLoader(ctx, 20f, HC.purple), FrameLayout.LayoutParams(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER_HORIZONTAL))
            box.addView(f, LinearLayout.LayoutParams(-1, -2))
        } else if (t.isNotEmpty()) {
            box.addView(Tf.t(ctx, "Your conversations", 12.5f, HC.ink), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f); bottomMargin = Ui.dpi(4f) })
            for (tk in t) {
                box.addView(View(ctx).apply { setBackgroundColor(HC.line) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
                val msgs = Tf.arr(tk.optJSONArray("messages"))
                val last = msgs.lastOrNull()
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dpi(11f), 0, Ui.dpi(11f))
                    setOnClickListener { openId = PR.s(tk, "id"); render(); seen(tk) }; Ui.pressable(this, .97f)
                }
                r.addView(View(ctx).apply { background = Tf.circle(if (PR.truthy(tk.opt("user_unread"))) HC.purple else Color.TRANSPARENT) }, LinearLayout.LayoutParams(Ui.dpi(8f), Ui.dpi(8f)).apply { rightMargin = Ui.dpi(10f) })
                val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                c.addView(Tf.t(ctx, PR.s(tk, "subject").ifEmpty { label(PR.s(tk, "category")) ?: "" }, 13.5f, HC.ink).ell())
                c.addView(Tf.t(ctx, (if (PR.s(last, "from") == "admin") "Team: " else "You: ") + PR.s(last, "text"), 12f, HC.soft, false).ell())
                r.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(10f) })
                r.addView(statusPill(PR.s(tk, "status")))
                box.addView(r, LinearLayout.LayoutParams(-1, -2))
            }
        } else if (failed) {
            val sp = android.text.SpannableStringBuilder("Could not load your messages. ")
            val a = sp.length; sp.append("Try again")
            sp.setSpan(android.text.style.ForegroundColorSpan(HC.purple), a, sp.length, 0)
            sp.setSpan(TfTypefaceSpan(Ui.bold), a, sp.length, 0)
            box.addView(Tf.t(ctx, sp, 12.5f, HC.red, false).apply { setOnClickListener { load() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(12f) })
        }
    }

    private fun statusPill(st: String): TextView = when (st) {
        "answered" -> pill(ctx, "Replied", HC.greenBg, HC.green)
        "closed" -> pill(ctx, "Closed", Ui.c("#EEEAF4"), HC.sub)
        else -> pill(ctx, "Waiting for reply", HC.amberBg, HC.amber)
    }

    private fun open(i: Intent) { try { ctx.startActivity(i) } catch (e: Exception) { } }

    private fun seen(tk: JSONObject) {
        if (!PR.truthy(tk.opt("user_unread"))) return
        Api.invoke("support", JSONObject().put("action", "seen").put("ticket_id", PR.s(tk, "id"))) { r -> if (r.ok && alive) load() }
    }

    private fun thread(tk: JSONObject) {
        box.addView(Tf.t(ctx, "All conversations", 13.5f, HC.purple).apply {
            gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = Ui.dpi(4f)
            setCompoundDrawablesWithIntrinsicBounds(PUi.lu(Lu.ChevronLeft, HC.purple, 18f), null, null, null)
            setOnClickListener { openId = ""; reply = ""; render() }; Ui.pressable(this, .97f)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(2f); bottomMargin = Ui.dpi(12f) })
        val hd = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cat = PR.s(tk, "category")
        hc.addView(Tf.t(ctx, PR.s(tk, "subject").ifEmpty { label(cat) ?: "Conversation" }, 15f, HC.ink).ell())
        hc.addView(Tf.t(ctx, "${label(cat) ?: "Other"} · started ${Tf.dateTime(PR.s(tk, "created_date"), true)}", 12f, HC.soft, false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(2f) })
        hd.addView(hc, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(10f) })
        hd.addView(statusPill(PR.s(tk, "status")))
        box.addView(hd, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(12f) })
        val msgs = Tf.arr(tk.optJSONArray("messages"))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        msgs.forEachIndexed { i, m ->
            val mine = PR.s(m, "from") == "user"
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (mine) Gravity.END else Gravity.START }
            val b = object : LinearLayout(ctx) {
                override fun onMeasure(w: Int, h: Int) {
                    val max = (MeasureSpec.getSize(w) * 0.84f).toInt()
                    super.onMeasure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), h)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
                val r = Ui.dp(14f); val s = Ui.dp(4f)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (mine) HC.purple else HC.faint)
                    cornerRadii = if (mine) floatArrayOf(r, r, r, r, s, s, r, r) else floatArrayOf(r, r, r, r, r, r, s, s)
                    if (!mine) setStroke(Ui.dpi(1f), HC.line)
                }
            }
            val fg = if (mine) Color.WHITE else HC.ink
            if (!mine) b.addView(Tf.t(ctx, "True Find team", 11.5f, HC.purple), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(2f) })
            b.addView(PUi.para(ctx, PR.s(m, "text"), 13.5f, fg, 19f))
            if (PR.truthy(m.opt("attachment"))) b.addView(Tf.t(ctx, "Screenshot attached", 11.5f, fg, false).apply { alpha = 0.8f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(4f) })
            b.addView(Tf.t(ctx, Tf.dateTime(PR.s(m, "at"), true), 10.5f, fg, false).apply { alpha = 0.7f; gravity = Gravity.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(4f) })
            wrap.addView(b, LinearLayout.LayoutParams(-2, -2))
            list.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = Ui.dpi(8f) })
        }
        val end = View(ctx)
        list.addView(end, LinearLayout.LayoutParams(-1, 0))
        box.addView(list, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(14f) })
        if (PR.s(tk, "status") != "closed") {
            val input = TfInput(ctx, "Write a reply…", multiLine = true, lines = 3, lineColor = HC.line).apply {
                setText(reply); filters = arrayOf(InputFilter.LengthFilter(2000)); sizeDp(14f); background = Tf.box(Color.WHITE, 13f, 1.5f, HC.line)
                setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
            }
            box.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(10f) })
            val send = sendBtn("Send", replyBusy)
            val paint = { val ok = reply.trim().length >= 2; send.alpha = if (ok) 1f else 0.5f; send.isEnabled = ok && !replyBusy }
            input.onText = { reply = it; paint() }
            paint()
            send.setOnClickListener {
                if (reply.trim().length < 2 || replyBusy) return@setOnClickListener
                replyBusy = true; render()
                Api.invoke("support", JSONObject().put("action", "reply").put("ticket_id", PR.s(tk, "id")).put("message", reply.trim())) { r ->
                    if (!alive) return@invoke
                    if (r.ok) { reply = ""; load { replyBusy = false; render() } }
                    else { replyBusy = false; render(); ps.say(errOf(r, "Could not send. Please try again."), true) }
                }
            }
            box.addView(send, LinearLayout.LayoutParams(-1, Ui.dpi(46f)))
            box.addView(Tf.t(ctx, "My problem is solved · close this", 13f, HC.sub).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    Api.invoke("support", JSONObject().put("action", "close").put("ticket_id", PR.s(tk, "id"))) { r ->
                        if (!alive) return@invoke
                        if (r.ok) load { ps.say("Conversation closed.") } else ps.say(errOf(r, "Could not close it. Please try again."), true)
                    }
                }
                Ui.pressable(this, .97f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(12f) })
        } else {
            box.addView(Tf.t(ctx, "This conversation is closed. Start a new message if you need more help.", 13f, HC.sub, false).apply { gravity = Gravity.CENTER })
        }
        // like scrollIntoView({ block: "end" }) on the last message
        view.post {
            val sc = findScroll(view) ?: return@post
            val r = Rect(); end.getDrawingRect(r)
            try { sc.offsetDescendantRectToMyCoords(end, r); sc.scrollTo(0, max(0, r.bottom - sc.height + sc.paddingBottom)) } catch (e: Exception) { }
        }
    }

    private fun findScroll(v: View): android.widget.ScrollView? {
        var p = v.parent
        while (p != null) { if (p is android.widget.ScrollView) return p; p = p.parent }
        return null
    }

    private fun sendBtn(label: String, busyNow: Boolean): FrameLayout = FrameLayout(ctx).apply {
        background = Tf.box(HC.purple, 13f)
        if (busyNow) addView(TfLoader(ctx, 18f, Color.WHITE), FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        else addView(Tf.t(ctx, label, 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = Ui.dpi(8f)
            setCompoundDrawablesWithIntrinsicBounds(PUi.lu(Lu.Send, Color.WHITE, 16f), null, null, null)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        Ui.pressable(this, .97f)
    }

    private fun newMessage() {
        val hd = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hd.addView(Tf.t(ctx, "New message", 15f, HC.ink), LinearLayout.LayoutParams(0, -2, 1f))
        hd.addView(FrameLayout(ctx).apply {
            background = Tf.box(HC.faint, 10f); contentDescription = "Cancel"
            addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.X, HC.sub, 16f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { writing = false; render() }; Ui.pressable(this, .97f)
        }, LinearLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f)))
        box.addView(hd, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(10f) })
        box.addView(Tf.t(ctx, "What is it about?", 12.5f, HC.sub, false), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(8f) })
        val chips = FlowRow(ctx, Ui.dpi(7f), Ui.dpi(7f))
        val paints = ArrayList<() -> Unit>()
        for ((k, l) in CATS) {
            val c = Tf.t(ctx, l, 13f, HC.sub).apply { gravity = Gravity.CENTER; setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0); layoutParams = android.view.ViewGroup.LayoutParams(-2, Ui.dpi(32f)) }
            val paint = { val on = category == k; c.background = Tf.box(if (on) HC.purpleSoft else Color.WHITE, 999f, 1.5f, if (on) HC.purple else HC.line); c.setTextColor(if (on) HC.purple else HC.sub) }
            paint(); paints.add(paint)
            c.setOnClickListener { category = k; paints.forEach { it() } }
            Ui.pressable(c, .97f)
            chips.addView(c)
        }
        box.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(12f) })
        val draft = text
        val input = TfInput(ctx, "Tell us what happened. Add details like the date, the amount or the person's name.", multiLine = true, lines = 5, lineColor = HC.line).apply {
            setText(draft); filters = arrayOf(InputFilter.LengthFilter(2000)); sizeDp(14f); background = Tf.box(Color.WHITE, 13f, 1.5f, HC.line)
            setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
        }
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val attach = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dpi(32f) }
        if (uploading) attach.addView(TfLoader(ctx, 15f, HC.purple), LinearLayout.LayoutParams(Ui.dpi(15f), Ui.dpi(15f)).apply { rightMargin = Ui.dpi(6f) })
        else attach.addView(PUi.icon(ctx, PUi.lu(Lu.ImagePlus, HC.purple, 15f), 15f), LinearLayout.LayoutParams(Ui.dpi(15f), Ui.dpi(15f)).apply { rightMargin = Ui.dpi(6f) })
        attach.addView(Tf.t(ctx, if (shot.isNotEmpty()) "Screenshot added ✓" else "Add a screenshot", 13f, HC.purple))
        attach.setOnClickListener { if (!uploading) attachShot() }
        Ui.pressable(attach, .97f)
        row.addView(attach, LinearLayout.LayoutParams(0, -2, 1f))
        val counter = Tf.t(ctx, "${text.length}/2000", 11.5f, HC.soft, false)
        row.addView(counter)
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f); bottomMargin = Ui.dpi(12f) })
        val send = sendBtn("Send to support", busy)
        val hint = Tf.t(ctx, "Please write at least 10 letters.", 12f, HC.soft, false).apply { gravity = Gravity.CENTER }
        val paint = {
            val n = text.trim().length
            val ok = n >= 10 && !uploading
            send.alpha = if (ok) 1f else 0.5f; send.isEnabled = ok && !busy
            hint.visibility = if (n in 1..9) View.VISIBLE else View.GONE
            counter.text = "${text.length}/2000"
        }
        input.onText = { text = it; paint() }
        send.setOnClickListener {
            if (busy || text.trim().length < 10 || uploading) return@setOnClickListener
            busy = true; render()
            val body = JSONObject().put("action", "create").put("category", category.ifEmpty { "other" }).put("message", text.trim())
            if (shot.isNotEmpty()) body.put("attachment_uri", shot)
            Api.invoke("support", body) { r ->
                if (!alive) return@invoke
                busy = false
                if (r.ok) { ps.say("Message sent. We'll reply here soon."); writing = false; category = ""; text = ""; shot = ""; render(); load() }
                else { render(); ps.say(errOf(r, "Could not send. Please try again."), true) }
            }
        }
        box.addView(send, LinearLayout.LayoutParams(-1, Ui.dpi(46f)))
        box.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f) })
        paint()
    }

    private fun attachShot() {
        ps.pick { bytes, type ->
            if (!alive) return@pick
            uploading = true; render()
            PUp.privatePhoto(bytes, type) { uri ->
                if (!alive) return@privatePhoto
                uploading = false
                if (uri.isEmpty()) ps.say(PUp.UPLOAD_ERROR, true) else shot = uri
                render()
            }
        }
    }

    fun previewOpen(what: String) { when (what) { "new" -> { writing = true; render() }; "thread" -> tickets?.firstOrNull()?.let { openId = PR.s(it, "id"); render() } } }
}
