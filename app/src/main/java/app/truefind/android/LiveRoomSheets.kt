package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** The sheets and pop-ups inside a Live Room (same texts and rules as LiveRoom.jsx). */
object LiveRoomSheets {
    private fun ctxOf(page: LiveRoomPage) = page.screen.context
    private fun divider(ctx: Context) = View(ctx).apply { setBackgroundColor(DK.LINE) }
    private fun screenH(ctx: Context) = ctx.resources.displayMetrics.heightPixels

    /** a menu row: icon + label (red for leaving / ending) */
    private fun menuRow(ctx: Context, icon: String, label: String, red: Boolean, click: () -> Unit): View =
        Ui.text(ctx, label, 16f, if (red) Ui.c("#dc2626") else DK.INK).apply {
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(icon, if (red) Ui.c("#dc2626") else DK.PURPLE, 22f), null, null, null)
            compoundDrawablePadding = Ui.dpi(14f)
            setPadding(Ui.dpi(6f), 0, Ui.dpi(6f), 0)
            minHeight = Ui.dpi(52f)
            isClickable = true; contentDescription = label
            setOnClickListener { click() }
        }

    // ═════════════════════════════ ⋯ menu ═════════════════════════════
    fun menu(page: LiveRoomPage) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, null)
        val host = page.isHost()
        fun add(icon: String, label: String, red: Boolean = false, f: () -> Unit) {
            s.body.addView(menuRow(ctx, icon, label, red) { s.close(); f() }, DK.llp(DK.MATCH, DK.WRAP))
            s.body.addView(divider(ctx), DK.llp(DK.MATCH, Ui.dpi(1f)))
        }
        add(DIcon.INFO, "Room info") { panel(page, "info") }
        add(DIcon.BAR_CHART, "Room stats") { panel(page, "info", sub = "stats") }
        add(DIcon.TROPHY, "Leaderboard") { screen.nav.go("/live/leaderboard") }
        add(DIcon.SHARE, "Share room") { page.share() }
        if (!host) add(LK.THUMBS_UP, "Rate this room") { rate(page) }
        if (page.isCo()) add(DIcon.MIC_OFF, "Leave Co-Host seat") { page.act("cohost_leave", ok = "You left the seat") }
        add(DIcon.FLAG, "Report someone") { pickPerson(page, "report") }
        add(DIcon.BAN, "Block someone") { pickPerson(page, "block") }
        add(DIcon.LOG_OUT, if (host) "End live" else "Leave room", true) { page.leave() }
        screen.sheets.open(s)
    }

    // ═════════════════════════════ one person ═════════════════════════════
    fun person(page: LiveRoomPage, u: JSONObject) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, null)
        val self = u.optBoolean("self") || u.optString("user_id") == page.myId()
        val hostId = page.host()?.optString("user_id")
        val role = when {
            u.optString("user_id") == hostId -> "host"
            page.cohosts().any { it.optString("user_id") == u.optString("user_id") } -> "cohost"
            else -> u.optString("role").ifEmpty { "viewer" }
        }
        val co = role == "cohost"; val hostP = role == "host"
        val live = page.cohosts().firstOrNull { it.optString("user_id") == u.optString("user_id") } ?: u
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, Ui.dpi(10f)) }
        head.addView(AvatarView(ctx, 58f).apply { set(u.optString("photo")) })
        val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, 0, 0) }
        tx.addView(DK.t(ctx, u.optString("name") + if (self) " (you)" else "", 18f, DK.INK))
        tx.addView(if (hostP) LK.hostTag(ctx, false, true) else if (co) LK.hostTag(ctx, true, true) else DK.t(ctx, "In the room", 13.5f, DK.SUB, false), DK.llp(DK.WRAP, DK.WRAP, t = 4f))
        head.addView(tx, DK.llp(0, DK.WRAP, 1f))
        s.body.addView(head)
        fun add(icon: String, label: String, red: Boolean = false, f: () -> Unit) {
            s.body.addView(divider(ctx), DK.llp(DK.MATCH, Ui.dpi(1f)))
            s.body.addView(menuRow(ctx, icon, label, red) { f() }, DK.llp(DK.MATCH, DK.WRAP))
        }
        val isHost = page.isHost()
        if (self && co) add(DIcon.MIC_OFF, "Leave Co-Host seat") { s.close(); page.act("cohost_leave", ok = "You left the seat") }
        if (!self && (co || hostP)) add(DIcon.GIFT, "Send a gift to ${DK.first(u.optString("name"))}") { s.close(); panel(page, "gifts", giftTo = u.optString("user_id")) }
        if (!self && isHost && co) {
            val hm = live.optBoolean("host_muted")
            add(if (hm) DIcon.MIC else DIcon.MIC_OFF, if (hm) "Allow to speak" else "Mute") { s.close(); page.act("mute", JSONObject().put("user_id", u.optString("user_id")).put("muted", !hm), if (hm) "They can speak again" else "Muted") }
            add(DIcon.X, "Remove from seat") { s.close(); page.act("cohost_remove", JSONObject().put("user_id", u.optString("user_id")), "Removed from the seat") }
        }
        if (!self && isHost) add(DIcon.LOG_OUT, "Remove from room", true) { s.close(); page.act("kick", JSONObject().put("user_id", u.optString("user_id")), "Removed from the room") }
        if (!self) {
            add(DIcon.FLAG, "Report") { s.close(); report(page, u) }
            add(DIcon.BAN, "Block", true) { s.close(); block(page, u) }
        }
        screen.sheets.open(s)
    }

    // ═════════════════════════════ who to report / block ═════════════════════════════
    fun pickPerson(page: LiveRoomPage, mode: String) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, if (mode == "block") "Who do you want to block?" else "Who do you want to report?")
        val (box, fail) = LiveSheets.loading(s.body)
        LiveApi.call("users", JSONObject().put("room_id", page.roomId)) { r ->
            if (!s.isOpen) return@call
            s.body.removeView(box)
            val people = DK.arr(r.data.optJSONArray("users")).filter { it.optString("user_id") != page.myId() }
            if (people.isEmpty()) { s.body.addView(DK.t(ctx, "No one else is in the room.", 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 10f, b = 10f)); return@call }
            people.forEach { u ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(1f), DK.LINE) }
                    setPadding(Ui.dpi(10f), Ui.dpi(9f), Ui.dpi(10f), Ui.dpi(9f))
                    isClickable = true; contentDescription = u.optString("name")
                    setOnClickListener { s.close(); if (mode == "block") block(page, u) else report(page, u) }
                    Ui.pressable(this, 0.98f)
                }
                row.addView(AvatarView(ctx, 44f).apply { set(u.optString("photo")) })
                row.addView(DK.t(ctx, u.optString("name"), 15.5f, DK.INK), DK.llp(0, DK.WRAP, 1f, l = 12f))
                if (u.optString("role") != "viewer") row.addView(LK.hostTag(ctx, u.optString("role") == "cohost", true), DK.llp(DK.WRAP, Ui.dpi(20f)))
                s.body.addView(row, DK.llp(b = 8f))
            }
            if (!r.ok && people.isEmpty()) fail(r.text())
        }
        screen.sheets.open(s)
    }

    // ═════════════════════════════ Report ═════════════════════════════
    fun report(page: LiveRoomPage, u: JSONObject) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, "Report ${u.optString("name")}".trim())
        var reason = ""
        val rows = ArrayList<LinearLayout>()
        fun paint() = rows.forEach { r ->
            val on = r.tag == reason
            r.background = GradientDrawable().apply { setColor(if (on) DK.SOFT else Color.WHITE); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(if (on) 2f else 1f), if (on) DK.PURPLE else DK.LINE) }
            (r.getChildAt(0) as RadioDot).on = on
            r.isSelected = on
        }
        LK.REASONS.forEach { rs ->
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = rs
                setPadding(Ui.dpi(12f), Ui.dpi(11f), Ui.dpi(12f), Ui.dpi(11f))
                isClickable = true; contentDescription = rs
                setOnClickListener { reason = rs; paint() }
            }
            r.addView(RadioDot(ctx), DK.llp(Ui.dpi(20f), Ui.dpi(20f), r = 10f))
            r.addView(DK.t(ctx, rs, 15f, DK.INK), DK.llp(0, DK.WRAP, 1f))
            rows.add(r)
            s.body.addView(r, DK.llp(b = 6f))
        }
        paint()
        val details = LiveSheets.input(ctx, "What happened? (required)", 500, 3).apply { contentDescription = "What happened"; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f)) }
        s.body.addView(details, DK.llp(t = 6f))
        // screenshots (up to 3)
        val shots = ArrayList<Pair<ByteArray, String>>()
        val shotRow = DanceFlowRow(ctx)
        s.body.addView(shotRow, DK.llp(t = 10f))
        lateinit var paintShots: () -> Unit
        paintShots = {
            shotRow.removeAllViews()
            shots.forEachIndexed { i, _ ->
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(10f) }
                    setPadding(Ui.dpi(10f), 0, Ui.dpi(6f), 0)
                }
                chip.addView(Ui.text(ctx, "📷 Screenshot ${i + 1}", 12.5f, DK.INK))
                chip.addView(DK.iconButton(ctx, DIcon(DIcon.X, DK.INK, 14f), 24f, null, "Remove screenshot").apply { setOnClickListener { shots.removeAt(i); paintShots() } }, DK.llp(Ui.dpi(24f), Ui.dpi(24f), l = 4f))
                shotRow.addView(chip, ViewGroup.MarginLayoutParams(DK.WRAP, Ui.dpi(32f)))
            }
            if (shots.size < 3) shotRow.addView(Ui.text(ctx, "Add screenshot", 13.5f, DK.PURPLE).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(18f); setStroke(Ui.dpi(1.5f), DK.PURPLE, Ui.dp(4f), Ui.dp(3f)) }
                setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(LK.IMAGE_PLUS, DK.PURPLE, 17f), null, null, null); compoundDrawablePadding = Ui.dpi(6f)
                isClickable = true
                setOnClickListener { screen.nav.pickImage { bytes, mime -> if (bytes != null && bytes.isNotEmpty() && shots.size < 3) { shots.add(bytes to mime); paintShots() } } }
            }, ViewGroup.MarginLayoutParams(DK.WRAP, Ui.dpi(36f)))
        }
        paintShots()
        val err = DK.t(ctx, "", 14f, Ui.c("#dc2626"), true, 0).apply { visibility = View.GONE }
        s.body.addView(err, DK.llp(t = 10f))
        val send = LiveSheets.busyButton(ctx, "Send Report", DIcon(DIcon.FLAG, Color.WHITE, 19f))
        s.body.addView(send.box, DK.llp(DK.MATCH, Ui.dpi(50f), t = 14f))
        s.body.addView(DK.t(ctx, "They will also be blocked for you. The True Find team can ban them permanently.", 12.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 8f))
        fun fail(m: String) { send.set(false); err.text = m; err.visibility = View.VISIBLE }
        send.btn.setOnClickListener {
            if (send.busy) return@setOnClickListener
            val d = details.text.toString().trim()
            if (reason.isEmpty()) { err.text = "Choose a reason."; err.visibility = View.VISIBLE; return@setOnClickListener }
            if (d.length < 10) { err.text = "Please write what happened (at least 10 letters)."; err.visibility = View.VISIBLE; return@setOnClickListener }
            send.set(true); err.visibility = View.GONE
            val uris = ArrayList<String>()
            fun finish() {
                LiveApi.call("report", JSONObject().put("room_id", page.roomId).put("user_id", u.optString("user_id")).put("reason", reason).put("details", d)) { r ->
                    if (!r.ok) { fail(r.text("Could not send the report.")); return@call }
                    val pid = u.optString("profile_id")
                    if (pid.isNotEmpty()) LiveApi.other("moderateUser", JSONObject().put("profile_id", pid).put("action", "report").put("reason", reason).put("details", d.take(1000))
                        .put("evidence_uris", org.json.JSONArray(uris)).put("app", "truefind").put("room_id", page.roomId)) { }
                    screen.showToast("Report sent. Our team will check it.")
                    page.onBlocked(u)
                    s.close()
                }
            }
            fun upload(i: Int) {
                if (!s.isOpen) return
                if (i >= shots.size) { finish(); return }
                LiveApi.upload(shots[i].first, shots[i].second) { r ->
                    if (!r.ok) { fail(r.text("Could not send the report.")); return@upload }
                    r.data.optString("file_uri").takeIf { it.isNotEmpty() }?.let { uris.add(it) }
                    upload(i + 1)
                }
            }
            upload(0)
        }
        screen.sheets.open(s)
    }

    // ═════════════════════════════ Block ═════════════════════════════
    fun block(page: LiveRoomPage, u: JSONObject) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, "Block ${u.optString("name")}?")
        val isHostP = u.optString("user_id") == page.host()?.optString("user_id")
        s.body.addView(DK.t(ctx, "You won't see their messages in rooms and they can't contact you.${if (isHostP) " You will leave this room." else ""}", 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, DK.llp(b = 16f))
        val err = DK.t(ctx, "", 14f, Ui.c("#dc2626"), true, 0).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        s.body.addView(err, DK.llp(b = 8f))
        val b = LiveSheets.busyButton(ctx, "Block", DIcon(DIcon.BAN, Color.WHITE, 19f), colors = LK.RED_GRAD)
        s.body.addView(b.box, DK.llp(DK.MATCH, Ui.dpi(50f)))
        s.body.addView(DK.lineButton(ctx, "Cancel").apply { setOnClickListener { s.close() } }, DK.llp(DK.MATCH, Ui.dpi(50f), t = 10f))
        b.btn.setOnClickListener {
            if (b.busy) return@setOnClickListener
            b.set(true); err.visibility = View.GONE
            fun done() { page.onBlocked(u); screen.showToast("${u.optString("name")} is blocked"); s.close() }
            val pid = u.optString("profile_id")
            if (pid.isEmpty()) { done(); return@setOnClickListener }
            LiveApi.other("moderateUser", JSONObject().put("profile_id", pid).put("action", "block")) { r ->
                b.set(false)
                if (r.ok) done() else { err.text = r.text("Could not block. Try again."); err.visibility = View.VISIBLE }
            }
        }
        screen.sheets.open(s)
    }

    // ═════════════════════════════ Join list (seat wishlist) ═════════════════════════════
    fun wish(page: LiveRoomPage) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, "Join list")
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.body.addView(box)
        fun render() {
            box.removeAllViews()
            val me = page.me() ?: return
            val isHost = page.isHost()
            val left = page.seatsLeft()
            val fee = page.fee()
            val lead = if (isHost) "Requests from people who want to join you on a seat. Invite one and they can Pay & Join."
                else "Want to join the host on a seat? Send a request. When the host invites you, pay $fee coins to join."
            val seatsTxt = if (left > 0) "$left seat${if (left > 1) "s" else ""} free now." else "All seats are taken right now."
            box.addView(DK.t(ctx, "$lead $seatsTxt", 14f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, DK.llp(b = 12f))
            if (!isHost && !page.onSeat()) {
                if (me.optBoolean("invited")) box.addView(DK.button(ctx, "Host invited you · Pay & Join", DIcon(DIcon.CHECK, Color.WHITE, 20f), colors = intArrayOf(Ui.c("#22c55e"), Ui.c("#15803d"))).apply {
                    setOnClickListener { s.close(); joinDialog(page) }
                }, DK.llp(DK.MATCH, Ui.dpi(50f), b = 12f))
                else if (me.optBoolean("wish")) box.addView(DK.lineButton(ctx, "Cancel my request", DIcon(DIcon.HAND, DK.PURPLE, 20f)).apply {
                    setOnClickListener { page.setWish(false); postDelayed({ if (s.isOpen) render() }, 50) }
                }, DK.llp(DK.MATCH, Ui.dpi(50f), b = 12f))
                else box.addView(DK.button(ctx, "Request to join", DIcon(DIcon.HAND, Color.WHITE, 20f)).apply {
                    setOnClickListener { page.setWish(true); postDelayed({ if (s.isOpen) render() }, 50) }
                }, DK.llp(DK.MATCH, Ui.dpi(50f), b = 12f))
            }
            val list = page.wishlist()
            if (list.isEmpty()) { box.addView(DK.t(ctx, "Nobody is waiting yet", 14.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 20f, b = 20f)); return }
            list.forEachIndexed { i, u ->
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(4f), Ui.dpi(10f), Ui.dpi(4f), Ui.dpi(10f)) }
                row.addView(Ui.text(ctx, "${i + 1}", 14f, DK.SUB).apply { gravity = Gravity.CENTER }, DK.llp(Ui.dpi(22f), DK.WRAP))
                row.addView(AvatarView(ctx, 42f).apply { set(u.optString("photo")) }, DK.llp(DK.WRAP, DK.WRAP, l = 12f))
                val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, Ui.dpi(8f), 0) }
                tx.addView(DK.t(ctx, if (u.optString("user_id") == page.myId()) "You" else u.optString("name"), 15.5f, DK.INK))
                val inv = u.optBoolean("invited")
                tx.addView(DK.t(ctx, if (inv) "Invited to a seat" else "Wants to join", 12.5f, if (inv) Ui.c("#16a34a") else DK.SUB, inv))
                row.addView(tx, DK.llp(0, DK.WRAP, 1f))
                if (isHost && !inv) {
                    val inviteBtn = DK.button(ctx, "Invite", null, 38f, 14f).apply { setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0); contentDescription = "Invite ${u.optString("name")}" }
                    inviteBtn.setOnClickListener {
                        inviteBtn.isEnabled = false; inviteBtn.alpha = 0.6f
                        page.act("wish_invite", JSONObject().put("user_id", u.optString("user_id")), "${u.optString("name")} is invited") { _ -> if (s.isOpen) postRender(s) { render() } }
                    }
                    row.addView(inviteBtn, DK.llp(DK.WRAP, Ui.dpi(38f)))
                }
                box.addView(row)
                box.addView(divider(ctx), DK.llp(DK.MATCH, Ui.dpi(1f)))
            }
        }
        render()
        page.wishRender = { if (s.isOpen) render() else page.wishRender = null }
        screen.sheets.open(s)
    }
    private fun postRender(s: DanceSheet, f: () -> Unit) { s.postDelayed({ if (s.isOpen) f() }, 900) }

    // ═════════════════════════════ Pay & Join (Co-Host) ═════════════════════════════
    fun joinDialog(page: LiveRoomPage) {
        val screen = page.screen
        val ctx = ctxOf(page)
        if (screen.dialog?.isOpen == true) return
        val d = LiveDialog(ctx, screen)
        val c = d.card
        val audio = page.mode() == "audio"
        c.addView(FrameLayout(ctx).apply {
            background = DK.oval(DK.SOFT)
            addView(ImageView(ctx).apply { setImageDrawable(DIcon(if (audio) DIcon.MIC else LK.USER_PLUS, DK.PURPLE, 32f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
        }, DK.llp(Ui.dpi(64f), Ui.dpi(64f)))
        c.addView(DK.t(ctx, "Join as Co-Host?", 22f, DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 12f, b = 6f))
        c.addView(DK.t(ctx, "Host is requesting coins to join the ${if (audio) "audio" else "live"} as Co-Host${if (audio) ". Your mic will be on." else ". Your camera and mic will be on."}", 15f, Ui.c("#4b4466"), false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
        val fee = page.fee()
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#f5edff"), Ui.c("#efe3ff"))).apply { cornerRadius = Ui.dp(18f) }
            setPadding(Ui.dpi(26f), Ui.dpi(12f), Ui.dpi(26f), Ui.dpi(12f))
        }
        val coins2 = FrameLayout(ctx)
        coins2.addView(ImageView(ctx).apply { setImageDrawable(CoinIcon(30f)) }, DK.flp(Ui.dpi(30f), Ui.dpi(30f)))
        coins2.addView(ImageView(ctx).apply { setImageDrawable(CoinIcon(30f)) }, DK.flp(Ui.dpi(30f), Ui.dpi(30f), l = 16f))
        pill.addView(coins2, DK.llp(Ui.dpi(46f), Ui.dpi(30f)))
        pill.addView(Ui.text(ctx, "$fee Coins", 26f, DK.INK), DK.llp(DK.WRAP, DK.WRAP, l = 10f))
        c.addView(pill, DK.llp(DK.WRAP, DK.WRAP, t = 16f))
        val mine = DK.t(ctx, "", 13f, DK.SUB, false).apply { visibility = View.GONE }
        c.addView(mine, DK.llp(DK.WRAP, DK.WRAP, t = 8f))
        LiveApi.other("getWallet", JSONObject().put("app", "truefind")) { r ->
            if (r.ok) { mine.text = "Your coins: ${LK.n(r.data.optJSONObject("wallet")?.optLong("coins") ?: 0)}"; mine.visibility = View.VISIBLE }
        }
        val err = DK.t(ctx, "", 14f, Ui.c("#be123c"), true, 0).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        c.addView(err, DK.llp(DK.WRAP, DK.WRAP, t = 10f))
        val pay = LiveSheets.busyButton(ctx, "Pay & Join", null, 54f, sizeDp = 18f)
        pay.btn.contentDescription = "Pay & Join"
        c.addView(pay.box, DK.llp(DK.MATCH, Ui.dpi(54f), t = 16f))
        c.addView(DK.t(ctx, "Coins are not returned if you leave or the host removes you.", 12f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 10f))
        pay.btn.setOnClickListener {
            if (pay.busy) return@setOnClickListener
            pay.set(true); err.visibility = View.GONE
            page.payAndJoin { r ->
                pay.set(false)
                if (r == null) { d.close(); return@payAndJoin }
                if (r.noCoins) {
                    val sb = SpannableStringBuilder("You need ${LK.n(r.data.optLong("need"))} coins. You have ${LK.n(r.data.optLong("balance"))}.  Recharge")
                    sb.setSpan(android.text.style.ForegroundColorSpan(DK.PURPLE), sb.length - 8, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    err.text = sb
                    err.setOnClickListener { d.close(); screen.nav.go("/wallet") }
                } else { err.text = r.text(); err.setOnClickListener(null) }
                err.visibility = View.VISIBLE
            }
        }
        d.open()
    }

    // ═════════════════════════════ End your live? ═════════════════════════════
    fun endConfirm(page: LiveRoomPage) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, "End your live?")
        s.body.addView(DK.t(ctx, "Everyone in the room will leave. Your gifts and Co-Host coins are added to your earnings.", 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, DK.llp(b = 16f))
        s.body.addView(DK.button(ctx, "End Live", colors = LK.RED_GRAD).apply { contentDescription = "End Live"; setOnClickListener { s.close(); page.endLive() } }, DK.llp(DK.MATCH, Ui.dpi(50f)))
        s.body.addView(DK.lineButton(ctx, "Keep Live").apply { setOnClickListener { s.close() } }, DK.llp(DK.MATCH, Ui.dpi(50f), t = 10f))
        screen.sheets.open(s)
    }

    // ═════════════════════════════ Enjoying this room? ═════════════════════════════
    fun rate(page: LiveRoomPage) {
        val screen = page.screen
        val ctx = ctxOf(page)
        val s = LiveSheets.sheet(screen, "Enjoying this room?")
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple(true, LK.THUMBS_UP, "Yes, love it"), Triple(false, LK.THUMBS_DOWN, "Not really")).forEachIndexed { i, (up, icon, label) ->
            val b = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(if (up) DK.SOFT else Color.WHITE); cornerRadius = Ui.dp(18f); setStroke(Ui.dpi(1.5f), DK.LINE) }
                setPadding(Ui.dpi(8f), Ui.dpi(18f), Ui.dpi(8f), Ui.dpi(18f))
                isClickable = true; contentDescription = label
                setOnClickListener { s.close(); page.act("rate", JSONObject().put("up", up), "Thanks for rating!") }
                Ui.pressable(this, 0.97f)
            }
            b.addView(ImageView(ctx).apply { setImageDrawable(DIcon(icon, if (up) DK.PURPLE else Ui.c("#94a3b8"), 30f)) })
            b.addView(Ui.text(ctx, label, 15f, DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 8f))
            row.addView(b, DK.llp(0, DK.WRAP, 1f, l = if (i == 0) 0f else 12f))
        }
        s.body.addView(row, DK.llp(t = 4f))
        screen.sheets.open(s)
    }

    // ═════════════════════════════ the panel: Chat · Users · Gifts · Info ═════════════════════════════
    fun panel(page: LiveRoomPage, tab: String, sub: String? = null, giftTo: String? = null) {
        if (page.room() == null) return
        page.panel?.dismiss()
        val p = Panel(page, tab, sub, giftTo)
        page.panel = p
        p.open()
    }

    @SuppressLint("NotifyDataSetChanged")
    class Panel(val page: LiveRoomPage, private var tab: String, private var sub: String?, private var giftTo: String?) {
        private val screen = page.screen
        private val ctx = page.screen.context
        val s = DanceSheet(ctx, screen.layer, null, scroll = false, pad = false, maxFraction = 0.82f, onClosed = { if (page.panel === this) page.panel = null; usersTimer?.let { t -> page.removeCallbacks(t) } })
        private val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(16f)) }
        private val tabsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; background = GradientDrawable().apply { setColor(Ui.c("#f1ebfb")); cornerRadius = Ui.dp(26f) }; setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)) }
        private val content = FrameLayout(ctx)
        private var chatAdapter: ChatAdapter? = null
        private var chatRv: RecyclerView? = null
        private var usersTimer: Runnable? = null
        private var usersData: JSONObject? = null
        private var usersFilter = "all"
        private var usersRender: (() -> Unit)? = null
        private var infoRender: (() -> Unit)? = null

        init {
            s.body.addView(wrap, DK.llp(DK.MATCH, DK.WRAP))
            wrap.addView(tabsRow, DK.llp(b = 12f))
            wrap.addView(content, DK.llp(DK.MATCH, DK.WRAP, 1f))
        }

        fun open() { show(tab, sub); screen.sheets.open(s) }
        fun dismiss() { if (s.isOpen) s.close() }

        private fun show(t: String, sb: String?) {
            tab = t; sub = sb
            usersTimer?.let { page.removeCallbacks(it) }; usersTimer = null
            usersRender = null; infoRender = null; chatAdapter = null; chatRv = null
            s.scrollTarget = null
            paintTabs()
            content.removeAllViews()
            when {
                sb == "stats" -> statsPage()
                sb == "rules" -> rulesPage()
                sb == "level" -> levelPage()
                sb == "about" -> aboutPage()
                t == "chat" -> chatTab()
                t == "users" -> usersTab()
                t == "gifts" -> giftsTab()
                else -> infoTab()
            }
        }

        private fun paintTabs() {
            tabsRow.visibility = if (sub == null) View.VISIBLE else View.GONE
            tabsRow.removeAllViews()
            val viewers = page.room()?.optLong("viewers") ?: 0
            listOf("chat" to "Chat", "users" to "Users (${LK.c(viewers)})", "gifts" to "Gifts", "info" to "Info").forEach { (k, l) ->
                val on = k == tab
                tabsRow.addView(Ui.text(ctx, l, 14.5f, if (on) Color.WHITE else DK.INK).apply {
                    gravity = Gravity.CENTER; isSingleLine = true
                    background = if (on) DK.grad(DK.GRAD, 22f) else null
                    contentDescription = l; isClickable = true; isSelected = on
                    setOnClickListener { if (tab != k || sub != null) show(k, null) }
                }, DK.llp(0, Ui.dpi(40f), if (k == "users") 1.35f else 1f))
            }
        }

        fun roomChanged() {
            if (sub == null) paintTabs()
            infoRender?.invoke()
        }

        fun chatChanged() {
            val a = chatAdapter ?: return
            a.items = page.visibleMsgs()
            a.notifyDataSetChanged()
            chatRv?.scrollToPosition(a.items.size - 1)
            (content.getChildAt(0) as? LinearLayout)?.findViewWithTag<View>("empty")?.visibility = if (a.items.isEmpty()) View.VISIBLE else View.GONE
        }

        // ───────── Chat ─────────
        private fun chatTab() {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val frame = FrameLayout(ctx)
            val rv = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx).apply { stackFromEnd = true }
                itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(8f)); clipToPadding = false
            }
            val a = ChatAdapter(page)
            a.items = page.visibleMsgs()
            rv.adapter = a
            frame.addView(rv, DK.flp())
            frame.addView(DK.t(ctx, "Super Chats, gifts and join requests show here", 14.5f, DK.SUB, false, 0).apply {
                gravity = Gravity.CENTER; tag = "empty"; visibility = if (a.items.isEmpty()) View.VISIBLE else View.GONE
            }, DK.flp(DK.MATCH, DK.WRAP, Gravity.CENTER))
            col.addView(frame, DK.llp(DK.MATCH, (screenH(ctx) * 0.5f).toInt()))
            col.addView(DK.t(ctx, "Every message here is a Super Chat · ${LK.SUPER_CHAT_COINS} coins to the host", 12.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 6f))
            content.addView(col)
            chatAdapter = a; chatRv = rv
            s.scrollTarget = rv
            if (a.items.isNotEmpty()) rv.scrollToPosition(a.items.size - 1)
        }

        // ───────── Users ─────────
        private fun usersTab() {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            content.addView(col)
            col.addView(FrameLayout(ctx).apply { addView(DotsSpinner(ctx), DK.flp(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER)) }, DK.llp(DK.MATCH, Ui.dpi(120f)))
            val rv = MaxRecycler(ctx, (screenH(ctx) * 0.52f).toInt()).apply { layoutManager = LinearLayoutManager(ctx); itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER }
            val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val adapter = UsersAdapter(page)
            rv.adapter = adapter
            fun render() {
                val d = usersData ?: return
                if (col.childCount == 1 && col.getChildAt(0) is FrameLayout) {
                    col.removeAllViews()
                    col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(chips) }, DK.llp(b = 10f))
                    col.addView(rv, DK.llp(DK.MATCH, DK.WRAP))
                    if (page.isHost()) col.addView(DK.t(ctx, "Tap a person to mute a Co-Host, remove them from the seat or from the room.", 12.5f, DK.SUB, false, 0), DK.llp(l = 2f, t = 10f))
                    s.scrollTarget = rv
                }
                chips.removeAllViews()
                listOf("all" to "All (${LK.c(d.optLong("total"))})", "online" to "Online (${LK.c(d.optLong("online"))})", "hosts" to "Hosts (${d.optInt("hosts")})").forEach { (k, l) ->
                    val on = usersFilter == k
                    chips.addView(Ui.text(ctx, l, 14f, if (on) Color.WHITE else DK.INK).apply {
                        gravity = Gravity.CENTER; contentDescription = l; isClickable = true; isSelected = on
                        background = if (on) DK.grad(DK.GRAD, 19f) else DK.pill(Color.WHITE, DK.LINE, 1.5f)
                        setPadding(Ui.dpi(15f), 0, Ui.dpi(15f), 0)
                        if (k == "online") { setCompoundDrawablesRelativeWithIntrinsicBounds(object : android.graphics.drawable.Drawable() {
                            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#22c55e") }
                            override fun getIntrinsicWidth() = Ui.dpi(8f); override fun getIntrinsicHeight() = Ui.dpi(8f)
                            override fun draw(c: Canvas) { c.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), bounds.width() / 2f, p) }
                            override fun setAlpha(alpha: Int) {}; override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                            @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                        }, null, null, null); compoundDrawablePadding = Ui.dpi(6f) }
                        setOnClickListener { usersFilter = k; render() }
                    }, DK.llp(DK.WRAP, Ui.dpi(38f), r = 8f))
                }
                val list = DK.arr(d.optJSONArray("users")).filter { u ->
                    when (usersFilter) { "online" -> u.optBoolean("online"); "hosts" -> u.optString("role") != "viewer"; else -> true }
                }
                adapter.items = list
                adapter.notifyDataSetChanged()
            }
            usersRender = { render() }
            fun load() {
                LiveApi.call("users", JSONObject().put("room_id", page.roomId)) { r ->
                    if (!s.isOpen || tab != "users" || sub != null) return@call
                    if (r.ok) { usersData = r.data; render() }
                }
            }
            load()
            val t = object : Runnable { override fun run() { if (!s.isOpen || tab != "users" || sub != null) return; load(); page.postDelayed(this, 6000) } }
            usersTimer = t
            page.postDelayed(t, 6000)
        }

        // ───────── Gifts ─────────
        private fun giftsTab() {
            // scrolls on short phones (the Send button must always be reachable)
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            sv.addView(col)
            content.addView(sv)
            s.scrollTarget = sv
            val host = page.host() ?: JSONObject()
            val people = (listOf(host) + page.cohosts()).filter { it.optString("user_id") != page.myId() && it.optString("user_id").isNotEmpty() }
            if (people.isEmpty()) {
                col.addView(DK.t(ctx, "Gifts go to the host and Co-Hosts. You are the host here, so viewers send gifts to you.", 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, DK.llp(l = 10f, t = 30f, r = 10f, b = 30f))
                return
            }
            var to = people.firstOrNull { it.optString("user_id") == giftTo }?.optString("user_id") ?: people.first().optString("user_id")
            var cat = "popular"
            var sel: DK.Gift? = null
            var count = 1
            var coins: Long? = null
            var busy = false
            // my coins + Recharge
            val coinRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Ui.c("#faf6ff")); cornerRadius = Ui.dp(18f); setStroke(Ui.dpi(1f), DK.LINE) }
                setPadding(Ui.dpi(14f), Ui.dpi(8f), Ui.dpi(8f), Ui.dpi(8f))
            }
            coinRow.addView(ImageView(ctx).apply { setImageDrawable(CoinIcon(30f)) }, DK.llp(Ui.dpi(30f), Ui.dpi(30f)))
            val coinTv = DK.t(ctx, "My Coins: …", 16f, DK.INK)
            coinRow.addView(coinTv, DK.llp(0, DK.WRAP, 1f, l = 10f, r = 8f))
            coinRow.addView(DK.button(ctx, "Recharge", null, 38f, 14f).apply {
                setPadding(Ui.dpi(13f), 0, Ui.dpi(13f), 0); elevation = 0f
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.PLUS, Color.WHITE, 16f), null); compoundDrawablePadding = Ui.dpi(4f)
                setOnClickListener { s.close(); screen.nav.go("/wallet") }
            }, DK.llp(DK.WRAP, Ui.dpi(38f)))
            col.addView(coinRow)
            LiveApi.other("getWallet", JSONObject().put("app", "truefind")) { r ->
                if (r.ok) { coins = r.data.optJSONObject("wallet")?.optLong("coins") ?: 0; coinTv.text = "My Coins: ${LK.n(coins)}" }
            }
            // who gets it
            val toRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            if (people.size > 1) col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(toRow) }, DK.llp(t = 10f))
            // groups
            val grpRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(grpRow) }, DK.llp(t = 12f, b = 10f))
            // the grid (swipe sideways = next group)
            val grid = object : RecyclerView(ctx) {
                private var dx0 = 0f; private var dy0 = 0f
                override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) { dx0 = e.x; dy0 = e.y }
                    return super.onInterceptTouchEvent(e)
                }
                override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) { dx0 = e.x; dy0 = e.y }
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = e.x - dx0; val dy = e.y - dy0
                        if (abs(dx) > Ui.dp(50f) && abs(dx) > abs(dy) * 1.4f) { swipe?.invoke(dx); return true }
                    }
                    return super.dispatchTouchEvent(e)
                }
                var swipe: ((Float) -> Unit)? = null
            }
            grid.layoutManager = GridLayoutManager(ctx, 4)
            grid.itemAnimator = null; grid.overScrollMode = View.OVER_SCROLL_NEVER; grid.isNestedScrollingEnabled = false
            col.addView(grid, DK.llp(DK.MATCH, DK.WRAP))
            // x1 x5 x10 x20
            val countRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            countRow.addView(Ui.text(ctx, "Send", 13.5f, DK.SUB), DK.llp(DK.WRAP, DK.WRAP, r = 8f))
            val countViews = ArrayList<TextView>()
            LK.GIFT_COUNTS.forEach { n ->
                val v = Ui.text(ctx, "x$n", 14f, DK.INK).apply { gravity = Gravity.CENTER; contentDescription = "x$n"; isClickable = true; setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0); minWidth = Ui.dpi(46f) }
                countViews.add(v)
                countRow.addView(v, DK.llp(DK.WRAP, Ui.dpi(34f), r = 8f))
            }
            col.addView(countRow, DK.llp(t = 10f))
            val err = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Ui.c("#fff1f2")); cornerRadius = Ui.dp(12f) }
                setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)); visibility = View.GONE
            }
            val errTv = DK.t(ctx, "", 14f, Ui.c("#be123c"), true, 0)
            err.addView(errTv, DK.llp(0, DK.WRAP, 1f))
            err.addView(Ui.text(ctx, "Recharge", 14f, DK.PURPLE).apply { isClickable = true; setPadding(Ui.dpi(8f), Ui.dpi(4f), 0, Ui.dpi(4f)); setOnClickListener { s.close(); screen.nav.go("/wallet") } })
            col.addView(err, DK.llp(t = 10f))
            val send = LiveSheets.busyButton(ctx, "Choose a gift", DIcon(DIcon.GIFT, Color.WHITE, 21f), 54f, sizeDp = 17.5f)
            col.addView(send.box, DK.llp(DK.MATCH, Ui.dpi(54f), t = 12f))

            lateinit var paint: () -> Unit
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                var items = LK.GIFTS.filter { it.g == cat }
                override fun getItemCount() = items.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                    val cell = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f))
                        isClickable = true
                        Ui.pressable(this, 0.97f)
                    }
                    cell.addView(Ui.text(ctx, "", 38f, DK.INK, false).apply { tag = "e"; gravity = Gravity.CENTER })
                    cell.addView(DK.t(ctx, "", 11.5f, DK.SUB).apply { tag = "n"; gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 4f))
                    cell.addView(Ui.text(ctx, "", 13.5f, DK.INK).apply { tag = "p"; setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(15f), null, null, null); compoundDrawablePadding = Ui.dpi(3f) }, DK.llp(DK.WRAP, DK.WRAP, t = 4f))
                    cell.layoutParams = RecyclerView.LayoutParams(DK.MATCH, Ui.dpi(112f)).apply { setMargins(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)) }
                    return object : RecyclerView.ViewHolder(cell) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val g = items[pos]
                    val v = h.itemView as LinearLayout
                    v.findViewWithTag<TextView>("e").text = g.e
                    v.findViewWithTag<TextView>("n").text = g.name
                    v.findViewWithTag<TextView>("p").text = LK.n(g.price)
                    val on = sel?.id == g.id
                    v.background = if (on) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#f6eeff"), Color.WHITE)).apply { cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(2f), DK.PURPLE) }
                        else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
                    v.elevation = if (on) Ui.dp(3f) else 0f
                    v.contentDescription = "${g.name}, ${g.price} coins"
                    v.isSelected = on
                    v.setOnClickListener { sel = g; err.visibility = View.GONE; paint() }
                }
            }
            grid.adapter = adapter
            fun setCat(k: String) { cat = k; adapter.items = LK.GIFTS.filter { it.g == cat }; adapter.notifyDataSetChanged(); paint() }
            grid.swipe = { dx ->
                val i = DK.GIFT_GROUPS.indexOfFirst { it.first == cat }
                val n = DK.GIFT_GROUPS[(i + if (dx < 0) 1 else -1).coerceIn(0, DK.GIFT_GROUPS.size - 1)]
                if (n.first != cat) setCat(n.first)
            }
            paint = {
                toRow.removeAllViews()
                if (people.size > 1) people.forEach { p ->
                    val on = p.optString("user_id") == to
                    val chip = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply { setColor(if (on) DK.SOFT else Color.WHITE); cornerRadius = Ui.dp(20f); setStroke(Ui.dpi(if (on) 2f else 1.5f), if (on) DK.PURPLE else DK.LINE) }
                        setPadding(Ui.dpi(4f), 0, Ui.dpi(12f), 0)
                        isClickable = true; isSelected = on; contentDescription = "Gift to ${p.optString("name")}"
                        setOnClickListener { to = p.optString("user_id"); paint() }
                    }
                    chip.addView(AvatarView(ctx, 30f).apply { set(p.optString("photo")) })
                    chip.addView(Ui.text(ctx, DK.first(p.optString("name")) + if (p.optString("user_id") == host.optString("user_id")) " 👑" else "", 13.5f, DK.INK), DK.llp(DK.WRAP, DK.WRAP, l = 7f))
                    toRow.addView(chip, DK.llp(DK.WRAP, Ui.dpi(40f), r = 8f))
                }
                grpRow.removeAllViews()
                DK.GIFT_GROUPS.forEach { (k, label, icon) ->
                    val on = k == cat
                    grpRow.addView(Ui.text(ctx, "$icon $label", 14f, if (on) Color.WHITE else DK.INK).apply {
                        gravity = Gravity.CENTER; isClickable = true; isSelected = on; contentDescription = label
                        background = if (on) DK.grad(DK.GRAD, 19f) else DK.pill(Color.WHITE, DK.LINE, 1.5f)
                        setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
                        setOnClickListener { if (cat != k) setCat(k) }
                    }, DK.llp(DK.WRAP, Ui.dpi(38f), r = 8f))
                }
                countViews.forEachIndexed { i, v ->
                    val on = LK.GIFT_COUNTS[i] == count
                    v.setTextColor(if (on) Color.WHITE else DK.INK)
                    v.background = if (on) DK.grad(DK.GRAD, 17f) else DK.pill(Color.WHITE, DK.LINE, 1.5f)
                    v.isSelected = on
                    v.setOnClickListener { count = LK.GIFT_COUNTS[i]; paint() }
                }
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                val g = sel
                if (!send.busy) {
                    send.btn.text = if (g != null) "Send Gift · ${LK.n(g.price.toLong() * count)} coins" else "Choose a gift"
                }
                send.btn.alpha = if (g == null) 0.55f else 1f
                send.btn.contentDescription = if (g != null) "Send Gift" else "Choose a gift"
            }
            send.btn.setOnClickListener {
                val g = sel ?: return@setOnClickListener
                if (busy || to.isEmpty()) return@setOnClickListener
                busy = true; send.set(true); err.visibility = View.GONE
                val total = g.price.toLong() * count
                LiveApi.call("gift", JSONObject().put("room_id", page.roomId).put("to_user_id", to).put("gift_id", g.id).put("count", count).put("client_id", DK.newId())) { r ->
                    busy = false; send.set(false)
                    if (r.ok) {
                        if (r.data.has("balance")) { coins = r.data.optLong("balance"); coinTv.text = "My Coins: ${LK.n(coins)}" }
                        val toName = people.firstOrNull { it.optString("user_id") == to }?.optString("name").orEmpty()
                        s.close()
                        page.giftSent(JSONObject().put("id", "local-${System.currentTimeMillis()}").put("gift_id", g.id).put("count", count).put("name", "You").put("to_name", toName))
                        screen.showToast("${g.name}${if (count > 1) " x$count" else ""} sent 💜")
                    } else {
                        if (r.data.has("balance")) { coins = r.data.optLong("balance"); coinTv.text = "My Coins: ${LK.n(coins)}" }
                        errTv.text = if (r.noCoins) "You need ${LK.n(r.data.optLong("need", total))} coins. You have ${LK.n(r.data.optLong("balance"))}." else r.text("Gift not sent.")
                        err.visibility = View.VISIBLE
                        paint()
                    }
                }
            }
            paint()
        }

        // ───────── Info ─────────
        private fun infoTab() {
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            sv.addView(col)
            content.addView(sv)
            s.scrollTarget = sv
            fun render() {
                col.removeAllViews()
                val room = page.room() ?: return
                val host = room.optJSONObject("host") ?: JSONObject()
                val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, Ui.dpi(12f)) }
                val pic = FrameLayout(ctx); DK.roundClip(pic, 16f)
                pic.addView(CoverView(ctx).apply { set(host.optString("photo")) }, DK.flp())
                head.addView(pic, DK.llp(Ui.dpi(74f), Ui.dpi(74f)))
                val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, Ui.dpi(8f), 0) }
                tx.addView(DK.t(ctx, room.optString("name"), 18f, DK.INK))
                tx.addView(Ui.text(ctx, "Room ID: ${room.optString("code")}", 14f, DK.SUB, false).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.COPY, DK.SUB, 15f), null); compoundDrawablePadding = Ui.dpi(6f)
                    contentDescription = "Copy Room ID"; isClickable = true; setOnClickListener { page.copyId() }
                }, DK.llp(DK.WRAP, DK.WRAP, t = 4f))
                head.addView(tx, DK.llp(0, DK.WRAP, 1f))
                if (!page.isHost()) {
                    val f = page.followed
                    head.addView(DK.button(ctx, if (f) "Following" else "Follow", DIcon(if (f) DIcon.CHECK else LK.STAR, if (f) DK.PURPLE else Color.WHITE, 16f), 40f, 14.5f).apply {
                        setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0); elevation = 0f
                        if (f) { background = DK.pill(Ui.c("#efe7fd")); setTextColor(DK.PURPLE) }
                        setOnClickListener { page.toggleFollow(false) }
                    }, DK.llp(DK.WRAP, Ui.dpi(40f)))
                }
                col.addView(head)
                val hostRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(Ui.c("#fbf9ff")); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
                    setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
                    isClickable = true; contentDescription = "Host ${host.optString("name")}"
                    setOnClickListener { openHostSheet(page) }
                }
                hostRow.addView(AvatarView(ctx, 44f).apply { set(host.optString("photo")) })
                val hs = SpannableStringBuilder("Host ")
                val a0 = hs.length; hs.append("· ${host.optString("name")}")
                hs.setSpan(android.text.style.ForegroundColorSpan(DK.SUB), a0, hs.length, 0)
                hostRow.addView(DK.t(ctx, hs, 16f, DK.INK), DK.llp(0, DK.WRAP, 1f, l = 12f))
                hostRow.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CHEVRON_RIGHT, DK.SUB, 20f)) })
                col.addView(hostRow, DK.llp(b = 6f))
                fun row(icon: String, title: String, subT: CharSequence?, extra: View? = null, click: () -> Unit) {
                    col.addView(divider(ctx), DK.llp(DK.MATCH, Ui.dpi(1f)))
                    val r = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(Ui.dpi(4f), Ui.dpi(12f), Ui.dpi(4f), Ui.dpi(12f))
                        isClickable = true; contentDescription = title
                        setOnClickListener { click() }
                    }
                    r.addView(FrameLayout(ctx).apply {
                        background = DK.oval(DK.SOFT)
                        addView(ImageView(ctx).apply { setImageDrawable(DIcon(icon, DK.PURPLE, 22f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
                    }, DK.llp(Ui.dpi(44f), Ui.dpi(44f)))
                    val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, Ui.dpi(8f), 0) }
                    t.addView(DK.t(ctx, title, 16f, DK.INK))
                    if (subT != null) t.addView(DK.t(ctx, subT, 13.5f, Ui.c("#4b4466"), false, 3).apply { setLineSpacing(0f, 1.15f) }, DK.llp(t = 2f))
                    if (extra != null) t.addView(extra, DK.llp(t = 4f))
                    r.addView(t, DK.llp(0, DK.WRAP, 1f))
                    r.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CHEVRON_RIGHT, DK.SUB, 20f)) })
                    col.addView(r)
                }
                row(DIcon.BAR_CHART, "Room Stats", "${LK.c(room.optLong("viewers"))} Online  |  ${LK.k(room.optLong("total_visits"))} Total Visits") { show("info", "stats") }
                row(DIcon.FILE_TEXT, "Room Rules", "Respect, no adult content, no numbers or links") { show("info", "rules") }
                val lvl = room.optInt("level", 1)
                val next = LK.LEVELS.getOrNull(lvl)
                val lrow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                lrow.addView(LK.levelChip(ctx, lvl), DK.llp(DK.WRAP, DK.WRAP))
                lrow.addView(DK.t(ctx, "${LK.tierOf(lvl).label}${if (next != null) " · next: ${next.reward}" else " · max level"}", 13f, DK.SUB, false), DK.llp(0, DK.WRAP, 1f, l = 8f))
                row(LK.STAR, "Room Level", null, lrow) { show("info", "level") }
                row(DIcon.INFO, "About Room", room.optString("description").ifBlank { "Fun talks, new friends, video & audio chat. Be respectful and enjoy! 💜" }) { show("info", "about") }
            }
            infoRender = { render() }
            render()
        }

        private fun subHead(col: LinearLayout, title: String) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(DK.iconButton(ctx, DIcon(DIcon.CHEVRON_LEFT, DK.INK, 26f), 40f, null, "Back").apply { setOnClickListener { show("info", null) } })
            row.addView(DK.t(ctx, title, 19f, DK.INK).apply { gravity = Gravity.CENTER; setPadding(0, 0, Ui.dpi(40f), 0) }, DK.llp(0, DK.WRAP, 1f))
            col.addView(row, DK.llp(l = -6f, b = 8f))
        }
        private fun subPage(): LinearLayout {
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            sv.addView(col)
            content.addView(sv)
            s.scrollTarget = sv
            return col
        }

        // ───────── Room Stats ─────────
        private fun statsPage() {
            val col = subPage()
            subHead(col, "Room Stats")
            var period = "day"
            val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; background = GradientDrawable().apply { setColor(Ui.c("#f1ebfb")); cornerRadius = Ui.dp(24f) }; setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)) }
            col.addView(tabs)
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(body)
            var seq = 0
            lateinit var load: () -> Unit
            fun paintTabs() {
                tabs.removeAllViews()
                listOf("day" to "Daily", "week" to "Weekly").forEach { (k, l) ->
                    val on = k == period
                    tabs.addView(Ui.text(ctx, l, 15f, if (on) Color.WHITE else DK.INK).apply {
                        gravity = Gravity.CENTER; background = if (on) DK.grad(DK.GRAD, 20f) else null; isClickable = true; isSelected = on; contentDescription = l
                        setOnClickListener { if (period != k) { period = k; paintTabs(); load() } }
                    }, DK.llp(0, Ui.dpi(40f), 1f))
                }
            }
            load = {
                val my = ++seq
                body.removeAllViews()
                val (_, fail) = LiveSheets.loading(body)
                LiveApi.call("stats", JSONObject().put("room_id", page.roomId).put("period", period)) { r ->
                    if (my != seq || !s.isOpen) return@call
                    if (!r.ok) { fail(r.text()); return@call }
                    body.removeAllViews()
                    val d = r.data
                    val sec = d.optInt("avg_session_sec")
                    val mmss = String.format(java.util.Locale.US, "%02dm %02ds", sec / 60, sec % 60)
                    val tiles = listOf(Triple(DIcon.USERS, LK.n(d.optLong("online")), "Currently Online"), Triple(DIcon.EYE, LK.n(d.optLong("visits")), if (period == "day") "Visits today" else "Visits this week"),
                        Triple(LK.TRENDING_UP, LK.n(d.optLong("peak")), "Peak Concurrent"), Triple(LK.CLOCK, mmss, "Avg. Session"))
                    for (rowI in 0 until 2) {
                        val rr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        for (ci in 0 until 2) {
                            val (ic, v, l) = tiles[rowI * 2 + ci]
                            val t = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                background = GradientDrawable().apply { setColor(Ui.c("#fbf9ff")); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
                                setPadding(Ui.dpi(12f), Ui.dpi(13f), Ui.dpi(12f), Ui.dpi(13f))
                            }
                            t.addView(ImageView(ctx).apply { setImageDrawable(DIcon(ic, DK.PURPLE, 26f)) })
                            val tc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(10f), 0, 0, 0) }
                            tc.addView(DK.t(ctx, v, 19f, DK.INK))
                            tc.addView(DK.t(ctx, l, 12.5f, DK.SUB, false).apply { if (rowI == 0 && ci == 0) { setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.c("#22c55e")); setSize(Ui.dpi(8f), Ui.dpi(8f)) }, null, null, null); compoundDrawablePadding = Ui.dpi(5f) } })
                            t.addView(tc, DK.llp(0, DK.WRAP, 1f))
                            rr.addView(t, DK.llp(0, DK.WRAP, 1f, l = if (ci == 0) 0f else 10f))
                        }
                        body.addView(rr, DK.llp(t = if (rowI == 0) 12f else 10f))
                    }
                    val gift = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#f3e9ff"), Ui.c("#faf6ff"))).apply { cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
                        setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), Ui.dpi(14f))
                    }
                    gift.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.GIFT, DK.PURPLE, 34f)) })
                    val gc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(14f), 0, 0, 0) }
                    gc.addView(DK.t(ctx, LK.n(d.optLong("gifts")), 22f, DK.INK))
                    gc.addView(DK.t(ctx, "Gifts Received · ${LK.n(d.optLong("gift_coins"))} coins", 13f, DK.SUB, false))
                    gift.addView(gc)
                    body.addView(gift, DK.llp(t = 10f))
                    body.addView(DK.t(ctx, "Room Activity", 16f, DK.INK), DK.llp(t = 16f, b = 6f))
                    body.addView(DK.t(ctx, "Most people in the room ${if (period == "day") "each hour today" else "each day this week"}", 12.5f, DK.SUB, false, 0), DK.llp(b = 4f))
                    body.addView(ActivityChart(ctx, DK.arr(d.optJSONArray("chart")), period == "day"), DK.llp(DK.MATCH, DK.WRAP))
                    body.addView(DK.t(ctx, "All-time visits: ${LK.n(d.optLong("total_visits"))} · Live today: ${d.optInt("live_minutes")} min", 12.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 6f))
                }
            }
            paintTabs()
            load()
        }

        // ───────── Room Rules ─────────
        private fun rulesPage() {
            val col = subPage()
            subHead(col, "Room Rules")
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#efe3ff"), Ui.c("#f8f3ff"))).apply { cornerRadius = Ui.dp(18f) }
                setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f))
            }
            top.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.SHIELD_CHECK, DK.PURPLE, 46f)) })
            val tc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(14f), 0, 0, 0) }
            tc.addView(DK.t(ctx, "Room Rules", 18.5f, DK.INK))
            tc.addView(DK.t(ctx, "Follow these rules to keep the room safe, respectful and fun for everyone.", 13.5f, Ui.c("#4b4466"), false, 0).apply { setLineSpacing(0f, 1.15f) })
            top.addView(tc, DK.llp(0, DK.WRAP, 1f))
            col.addView(top)
            listOf(Triple(DIcon.SHIELD_CHECK, "Be Respectful", "Treat everyone with kindness and respect. No bad language."),
                Triple(DIcon.VIDEO, "No Nude or Adult Content", "No explicit, vulgar or illegal content on camera, in chat or in your photo."),
                Triple(LK.MEGAPHONE, "No Harassment", "No abuse, threats, hate speech or bullying of anyone."),
                Triple(LK.LINK2, "No Spam or Promotion", "No links, outside ads or selling. No asking for money."),
                Triple(LK.LOCK, "Keep Contacts Private", "No phone numbers, social media IDs or links in any form. They are blocked."),
                Triple(DIcon.USERS, "Keep it Real", "Use your real photo and be yourself. Only 18+ can join.")).forEach { (ic, t, sub) ->
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
                    setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
                }
                r.addView(FrameLayout(ctx).apply { background = DK.oval(DK.SOFT); addView(ImageView(ctx).apply { setImageDrawable(DIcon(ic, DK.PURPLE, 21f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp()) }, DK.llp(Ui.dpi(40f), Ui.dpi(40f)))
                val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, 0, 0) }
                c.addView(DK.t(ctx, t, 15.5f, DK.INK))
                c.addView(DK.t(ctx, sub, 13f, DK.SUB, false, 0).apply { setLineSpacing(0f, 1.15f) })
                r.addView(c, DK.llp(0, DK.WRAP, 1f))
                col.addView(r, DK.llp(t = 8f))
            }
            col.addView(DK.t(ctx, "Breaking rules can get you removed from the room. Reports go to the True Find team, who can ban accounts permanently.", 13f, DK.SUB, false, 0).apply { setLineSpacing(0f, 1.2f) }, DK.llp(l = 2f, t = 12f))
            val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(DK.lineButton(ctx, "Report User", DIcon(DIcon.FLAG, DK.PURPLE, 19f)).apply { setOnClickListener { pickPerson(page, "report") } }, DK.llp(0, Ui.dpi(50f), 1f))
            btns.addView(DK.button(ctx, "Block User", DIcon(DIcon.BAN, Color.WHITE, 19f)).apply { setOnClickListener { pickPerson(page, "block") } }, DK.llp(0, Ui.dpi(50f), 1f, l = 10f))
            col.addView(btns, DK.llp(t = 14f))
        }

        // ───────── Room Level ─────────
        private fun levelPage() {
            val col = subPage()
            subHead(col, "Room Level")
            val (box, fail) = LiveSheets.loading(col)
            LiveApi.call("level", JSONObject().put("room_id", page.roomId)) { r ->
                if (!s.isOpen) return@call
                if (!r.ok) { fail(r.text()); return@call }
                col.removeView(box)
                val d = r.data
                val lv = d.optInt("level", 1)
                val t = LK.tierOf(lv)
                val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(LevelBadgeView(ctx, 112f, true).apply { level = lv })
                val tc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(14f), 0, 0, 0) }
                tc.addView(DK.t(ctx, "Level $lv", 26f, DK.INK))
                val max = d.optBoolean("max")
                val xp = d.optLong("xp"); val lxp = d.optLong("level_xp"); val nxp = d.optLong("next_xp")
                val pct = if (max) 1f else ((xp - lxp).toFloat() / max(1L, nxp - lxp)).coerceIn(0f, 1f)
                tc.addView(FracBar(ctx, Ui.c("#ece5f8"), DK.GRAD, 6f).apply { frac = pct }, DK.llp(DK.MATCH, Ui.dpi(12f), t = 8f))
                tc.addView(DK.t(ctx, if (max) "${LK.n(xp)} XP · Max level" else if (xp >= nxp) "${LK.n(xp)} XP · XP goal done ✅" else "${LK.n(xp)} / ${LK.n(nxp)} XP", 14.5f, Ui.c("#3b2a63"), true, 0), DK.llp(t = 6f))
                tc.addView(DK.t(ctx, "${t.label} · ${d.optString("reward")}", 12.5f, DK.SUB, false, 0), DK.llp(t = 2f))
                top.addView(tc, DK.llp(0, DK.WRAP, 1f))
                col.addView(top)
                if (!max) {
                    val nb = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#f3e9ff"), Ui.c("#fbf8ff"))).apply { cornerRadius = Ui.dp(16f) }
                        setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
                    }
                    nb.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.GIFT, Ui.c("#f59e0b"), 30f)) })
                    val c2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, 0, 0) }
                    c2.addView(DK.t(ctx, "Next Level Reward (Level ${lv + 1})", 15f, DK.INK))
                    c2.addView(DK.t(ctx, "${d.optString("next_reward")} · higher visibility & more features", 13f, DK.SUB, false, 0))
                    nb.addView(c2, DK.llp(0, DK.WRAP, 1f))
                    col.addView(nb, DK.llp(t = 14f))
                }
                val need = d.optJSONObject("need")
                if (need != null) {
                    col.addView(DK.t(ctx, "Level ${lv + 1} needs", 17f, DK.INK), DK.llp(t = 16f, b = 4f))
                    fun crit(icon: String, title: String, have: Long, needN: Long, unit: String, note: String?) {
                        col.addView(divider(ctx), DK.llp(DK.MATCH, Ui.dpi(1f)))
                        val rr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(2f), Ui.dpi(11f), Ui.dpi(2f), Ui.dpi(11f)) }
                        rr.addView(FrameLayout(ctx).apply { background = DK.oval(DK.SOFT); addView(ImageView(ctx).apply { setImageDrawable(DIcon(icon, DK.PURPLE, 20f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp()) }, DK.llp(Ui.dpi(38f), Ui.dpi(38f)))
                        val c3 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, 0, 0) }
                        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        line.addView(DK.t(ctx, title, 14.5f, DK.INK), DK.llp(0, DK.WRAP, 1f))
                        line.addView(DK.t(ctx, note ?: "${LK.n(have)} / ${LK.n(needN)}${if (unit.isNotEmpty()) " $unit" else ""}", 14.5f, Ui.c("#3b2a63"), true), DK.llp(DK.WRAP, DK.WRAP, l = 8f))
                        c3.addView(line)
                        if (note == null) c3.addView(FracBar(ctx, Ui.c("#ece5f8"), DK.GRAD, 4f).apply { frac = if (needN > 0) have.toFloat() / needN else 1f }, DK.llp(DK.MATCH, Ui.dpi(8f), t = 6f))
                        rr.addView(c3, DK.llp(0, DK.WRAP, 1f))
                        col.addView(rr)
                    }
                    crit(LK.CLOCK, "Active Room Minutes", d.optLong("live_minutes"), need.optLong("minutes"), "min", null)
                    crit(DIcon.USERS, "Unique Visitors", d.optLong("unique_visitors"), need.optLong("visitors"), "", null)
                    crit(LK.STAR, "Active Days", d.optLong("active_days"), need.optLong("days"), "days", null)
                    crit(LK.TRENDING_UP, "Total XP", xp, need.optLong("xp"), "", null)
                    crit(DIcon.SHIELD_CHECK, "Respectful Behavior", 0, 0, "", if (d.optBoolean("respectful", true)) "Good ✅" else "Under review ⚠️")
                    crit(DIcon.BAR_CHART, "Weekly Activity", d.optLong("week_days"), 7, "days", null)
                    crit(DIcon.GIFT, "Gifts Received", 0, 0, "", "${LK.n(d.optLong("gift_total"))} coins · bonus")
                }
                val rules = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { setColor(Ui.c("#faf6ff")); cornerRadius = Ui.dp(18f); setStroke(Ui.dpi(1f), DK.LINE) }
                    setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), Ui.dpi(14f))
                }
                rules.addView(DK.t(ctx, "XP & Level Rules", 15.5f, DK.PURPLE), DK.llp(b = 6f))
                listOf("XP = Live Time (${LK.XP_PER_LIVE_MINUTE} XP/min) + Real Visitors (${LK.XP_PER_VISITOR} XP each) + Active Days (${LK.XP_PER_ACTIVE_DAY} XP) + Gifts (1 XP per ${LK.XP_PER_GIFT_COINS} coins).",
                    "Live minutes count only while real people are in your room. A visitor counts after 1 minute. Fake / bot activity doesn't count.",
                    "A day is active after 30 live minutes. Gifts are a bonus, never required.",
                    "Every level needs its XP AND its live days, live hours and visitors.",
                    "Higher level = higher place in Live Rooms, more trust and more rewards. Maximum level: 40.").forEach { line ->
                    val rr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    rr.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CHECK, DK.PURPLE, 17f)) }, DK.llp(Ui.dpi(17f), Ui.dpi(17f), t = 1f))
                    rr.addView(DK.t(ctx, line, 13.5f, Ui.c("#3b2a63"), false, 0).apply { setLineSpacing(0f, 1.2f) }, DK.llp(0, DK.WRAP, 1f, l = 8f))
                    rules.addView(rr, DK.llp(t = 6f))
                }
                col.addView(rules, DK.llp(t = 14f))
                val table = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                val toggle = DK.lineButton(ctx, "See all 40 levels", heightDp = 44f, sizeDp = 15f)
                toggle.setOnClickListener {
                    val showing = table.visibility == View.VISIBLE
                    if (!showing && table.childCount == 0) buildLevelTable(table, lv)
                    table.visibility = if (showing) View.GONE else View.VISIBLE
                    toggle.text = if (showing) "See all 40 levels" else "Hide all levels"
                }
                col.addView(toggle, DK.llp(DK.MATCH, Ui.dpi(44f), t = 12f))
                col.addView(table, DK.llp(t = 10f))
                top.fadeUpIn()
            }
        }

        private fun buildLevelTable(t: LinearLayout, current: Int) {
            t.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(1f), DK.LINE) }
            DK.roundClip(t, 14f)
            val weights = floatArrayOf(44f, 70f, 46f, 54f, 120f)
            fun cellRow(cells: List<View>, bg: Int?, bold: Boolean): LinearLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (bg != null) setBackgroundColor(bg)
                setPadding(Ui.dpi(8f), Ui.dpi(7f), Ui.dpi(8f), Ui.dpi(7f))
                cells.forEachIndexed { i, v -> addView(v, DK.llp(0, DK.WRAP, weights[i], l = if (i == 0) 0f else 4f)) }
                if (bold) cells.forEach { (it as? TextView)?.typeface = Ui.bold }
            }
            t.addView(cellRow(listOf("Lv.", "Total XP", "Days", "Hours", "Visitors · Reward").map { Ui.text(ctx, it, 12.5f, Color.WHITE) }, null, true).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, DK.GRAD) })
            LK.LEVELS.forEachIndexed { i, lv ->
                val here = i + 1 == current
                val chip = FrameLayout(ctx).apply { addView(LK.levelChip(ctx, i + 1, true), FrameLayout.LayoutParams(DK.WRAP, DK.WRAP, Gravity.START or Gravity.CENTER_VERTICAL)) }
                val cells = listOf(chip, DK.t(ctx, LK.n(lv.xp), 12.5f, DK.INK, here), DK.t(ctx, "${lv.days}", 12.5f, DK.INK, here), DK.t(ctx, LK.n(lv.hours), 12.5f, DK.INK, here),
                    DK.t(ctx, "${LK.n(lv.visitors)} · ${lv.reward}", 12.5f, DK.INK, here, 0).apply { setLineSpacing(0f, 1.1f) })
                t.addView(cellRow(cells, if (here) Ui.c("#efe4ff") else if (i % 2 == 1) Color.WHITE else Ui.c("#faf7ff"), false))
            }
        }

        // ───────── About Room ─────────
        private fun aboutPage() {
            val col = subPage()
            subHead(col, "About Room")
            val room = page.room() ?: return
            val host = room.optJSONObject("host") ?: JSONObject()
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val pic = FrameLayout(ctx); DK.roundClip(pic, 16f)
            pic.addView(CoverView(ctx).apply { set(host.optString("photo")) }, DK.flp())
            top.addView(pic, DK.llp(Ui.dpi(112f), Ui.dpi(84f)))
            val tc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(14f), 0, 0, 0) }
            tc.addView(LK.nameBadge(ctx, room.optString("name"), 19f, DK.INK, 19f).apply { maxWidth = Ui.dpi(170f) }, DK.llp(DK.WRAP, DK.WRAP))
            tc.addView(Ui.text(ctx, "Room ID: ${room.optString("code")}", 14f, DK.SUB, false).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.COPY, DK.SUB, 15f), null); compoundDrawablePadding = Ui.dpi(6f)
                contentDescription = "Copy Room ID"; isClickable = true; setOnClickListener { page.copyId() }
            }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            top.addView(tc, DK.llp(0, DK.WRAP, 1f))
            col.addView(top)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
            }
            fun line(icon: String, k: String, v: View, click: (() -> Unit)? = null) {
                if (box.childCount > 0) box.addView(divider(ctx), DK.llp(DK.MATCH, Ui.dpi(1f)))
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
                    if (click != null) { isClickable = true; contentDescription = k; setOnClickListener { click() } }
                }
                r.addView(ImageView(ctx).apply { setImageDrawable(DIcon(icon, DK.PURPLE, 21f)) }, DK.llp(Ui.dpi(21f), Ui.dpi(21f)))
                r.addView(DK.t(ctx, k, 14.5f, DK.SUB, true), DK.llp(Ui.dpi(92f), DK.WRAP, l = 12f))
                r.addView(v, DK.llp(0, DK.WRAP, 1f))
                box.addView(r)
            }
            val cat = room.optString("category").let { if (it == "Chat" || it.isBlank()) "Live Talk & Meet" else it }
            line(LK.STAR, "Category", DK.t(ctx, cat, 14.5f, DK.INK, true, 0))
            line(DIcon.FILE_TEXT, "Description", DK.t(ctx, room.optString("description").ifBlank { "Fun talks, new friends, video & audio chat. 💜" }, 14.5f, DK.INK, true, 0).apply { setLineSpacing(0f, 1.2f) })
            line(LK.MEGAPHONE, "Language", DK.t(ctx, room.optString("language"), 14.5f, DK.INK, true, 0))
            val hv = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            hv.addView(AvatarView(ctx, 34f).apply { set(host.optString("photo")) })
            hv.addView(LK.nameBadge(ctx, host.optString("name"), 15f, DK.INK, 17f).apply { maxWidth = Ui.dpi(120f) }, DK.llp(0, DK.WRAP, 1f, l = 8f))
            hv.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CHEVRON_RIGHT, DK.SUB, 18f)) })
            line(DIcon.USERS, "Host", hv) { openHostSheet(page) }
            line(LK.CLOCK, "Created On", DK.t(ctx, LK.dateLong(room.optString("created_date")), 14.5f, DK.INK, true))
            col.addView(box, DK.llp(t = 14f))
        }
    }

    /** the host's profile from inside the room (Send Gift opens the room's gifts) */
    fun openHostSheet(page: LiveRoomPage) {
        val h = page.host()
        LiveSheets.hostProfile(page.screen, page.roomId, if (page.isHost()) null else ({ panel(page, "gifts", giftTo = h?.optString("user_id")) }), page.isHost())
    }

    // ───────── list adapters ─────────
    class ChatAdapter(private val page: LiveRoomPage) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<JSONObject> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(FrameLayout(p.context).apply { layoutParams = RecyclerView.LayoutParams(DK.MATCH, DK.WRAP) }) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val f = h.itemView as FrameLayout
            f.removeAllViews()
            f.addView(LiveChat.line(f.context, items[pos], false, page.host()?.optString("name").orEmpty()) { u -> page.panel?.dismiss(); page.openUser(u) })
        }
    }

    class UsersAdapter(private val page: LiveRoomPage) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<JSONObject> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(FrameLayout(p.context).apply { layoutParams = RecyclerView.LayoutParams(DK.MATCH, DK.WRAP).apply { bottomMargin = Ui.dpi(8f) } }) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val f = h.itemView as FrameLayout
            val ctx = f.context
            val u = items[pos]
            val me = u.optString("user_id") == page.myId()
            f.removeAllViews()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Ui.c("#fbf9ff")); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
                setPadding(Ui.dpi(10f), Ui.dpi(9f), Ui.dpi(10f), Ui.dpi(9f))
                isClickable = true; contentDescription = u.optString("name")
                setOnClickListener { page.panel?.dismiss(); page.openUser(JSONObject(u.toString()).put("self", me)) }
            }
            row.addView(AvatarView(ctx, 50f).apply { set(u.optString("photo")) })
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, Ui.dpi(8f), 0) }
            val nm = SpannableStringBuilder(u.optString("name"))
            if (me) { val a = nm.length; nm.append(" (you)"); nm.setSpan(android.text.style.ForegroundColorSpan(DK.SUB), a, nm.length, 0); nm.setSpan(android.text.style.RelativeSizeSpan(0.82f), a, nm.length, 0) }
            else nm.append(" ").append(LiveRoomPage.tagOf(u.optString("user_id")))
            c.addView(DK.t(ctx, nm, 16f, DK.INK))
            val onMic = u.optBoolean("on_mic"); val online = u.optBoolean("online")
            c.addView(Ui.text(ctx, if (onMic) "On mic" else if (online) "Online" else "Away", 13.5f, Ui.c("#3b2a63"), false).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (onMic) Ui.c("#ec4899") else if (online) Ui.c("#22c55e") else Ui.c("#c4bcd6")); setSize(Ui.dpi(8f), Ui.dpi(8f)) }, null, null, null)
                compoundDrawablePadding = Ui.dpi(5f)
            }, DK.llp(DK.WRAP, DK.WRAP, t = 3f))
            row.addView(c, DK.llp(0, DK.WRAP, 1f))
            if (u.optString("role") != "viewer") row.addView(LK.hostTag(ctx, u.optString("role") == "cohost", true), DK.llp(DK.WRAP, Ui.dpi(20f), r = 8f))
            row.addView(FrameLayout(ctx).apply {
                background = DK.oval(Color.WHITE, DK.LINE, 1.5f)
                addView(ImageView(ctx).apply { setImageDrawable(DIcon(LK.MORE, DK.PURPLE, 19f, DK.PURPLE)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
            }, DK.llp(Ui.dpi(38f), Ui.dpi(38f)))
            f.addView(row)
        }
    }
}

/** a RecyclerView that grows with its rows up to [maxPx] tall, then scrolls (max-height in the web) */
class MaxRecycler(ctx: Context, private val maxPx: Int) : RecyclerView(ctx) {
    override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)) }
}

/** the round choice dot of the Report reasons */
class RadioDot(ctx: Context) : View(ctx) {
    var on = false
        set(v) { if (v != field) { field = v; invalidate() } }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(2f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DK.PURPLE }
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        ring.color = if (on) DK.PURPLE else Ui.c("#c9bfe0")
        c.drawCircle(r, r, r - ring.strokeWidth / 2f, ring)
        if (on) c.drawCircle(r, r, Ui.dp(5f), dot)
    }
}

/** The area chart of Room Stats (ActivityChart): the most people each hour today, or each day this week. */
class ActivityChart(ctx: Context, private val data: List<JSONObject>, private val day: Boolean) : View(ctx) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#eee7fa"); strokeWidth = Ui.dp(1f) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#7a7290"); textSize = Ui.dp(10f); typeface = Ui.regular }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#8b3cf0"); style = Paint.Style.STROKE; strokeWidth = Ui.dp(2.4f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#8b3cf0"); style = Paint.Style.STROKE; strokeWidth = Ui.dp(2f) }
    init { contentDescription = "Room activity chart" }
    override fun onMeasure(w: Int, h: Int) { val ww = MeasureSpec.getSize(w); setMeasuredDimension(ww, (ww * 150f / 320f).toInt()) }
    override fun onDraw(c: Canvas) {
        txt.typeface = Ui.regular
        val k = width / 320f
        c.save(); c.scale(k, k)
        val W = 320f; val H = 150f; val L = 34f; val B = 22f; val T = 10f
        val values = data.map { it.optDouble("value", 0.0).toFloat() }
        val mx = max(4f, values.maxOrNull() ?: 0f)
        val p10 = Math.pow(10.0, Math.floor(Math.log10(mx.toDouble()))).toFloat()
        val m = mx / p10
        val nice = (if (m <= 1) 1f else if (m <= 2) 2f else if (m <= 5) 5f else 10f) * p10
        fun x(i: Int) = L + (i * (W - L - 6)) / max(1, data.size - 1)
        fun y(v: Float) = T + (H - T - B) * (1 - v / nice)
        txt.textSize = 10f; txt.textAlign = Paint.Align.RIGHT
        grid.strokeWidth = 1f
        listOf(0f, nice / 3, 2 * nice / 3, nice).map { Math.round(it).toFloat() }.forEach { t ->
            c.drawLine(L, y(t), W - 4, y(t), grid)
            c.drawText(LK.c(t), L - 6, y(t) + 4, txt)
        }
        if (data.isNotEmpty()) {
            val path = Path(); val fill = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            fill.addPath(path); fill.lineTo(x(data.size - 1), H - B); fill.lineTo(L, H - B); fill.close()
            area.shader = LinearGradient(0f, T, 0f, H - B, Ui.ca("#a855f7", 0.38f), Ui.ca("#a855f7", 0.03f), Shader.TileMode.CLAMP)
            c.drawPath(fill, area)
            line.strokeWidth = 2.4f
            c.drawPath(path, line)
            dotLine.strokeWidth = 2f
            values.forEachIndexed { i, v -> if (data.size <= 8 || i % 3 == 0) { c.drawCircle(x(i), y(v), 3.2f, dotFill); c.drawCircle(x(i), y(v), 3.2f, dotLine) } }
            txt.textAlign = Paint.Align.CENTER
            data.forEachIndexed { i, d ->
                val lab = if (day) { if (i % 4 == 0) { val h = d.optInt("label"); if (h == 0) "12 AM" else if (h < 12) "$h AM" else if (h == 12) "12 PM" else "${h - 12} PM" } else "" } else d.optString("label")
                if (lab.isNotEmpty()) c.drawText(lab, x(i), H - 6, txt)
            }
        }
        c.restore()
    }
}
