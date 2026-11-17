package app.truefind.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** The bottom sheets of Live Dance (same texts and rules as the web sheets). */
object DanceSheets {

    private fun sheet(screen: LiveDanceScreen, title: String?, pad: Boolean = true): DanceSheet =
        DanceSheet(screen.context, screen.layer, title, pad = pad)

    private fun loadingRow(s: DanceSheet): Pair<FrameLayout, (String) -> Unit> {
        val f = FrameLayout(s.context)
        f.addView(DotsSpinner(s.context), DK.flp(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER, t = 30f, b = 30f))
        s.body.addView(f, DK.llp())
        return f to { err: String ->
            f.removeAllViews()
            f.addView(DK.t(s.context, err, 15f, DK.ERR, true, 0).apply { gravity = Gravity.CENTER }, DK.flp(DK.MATCH, DK.WRAP, Gravity.CENTER, t = 30f, b = 30f))
        }
    }

    private fun divider(s: DanceSheet) = View(s.context).apply { setBackgroundColor(DK.LINE) }

    // ───────── Demo mode (admins) ─────────
    fun demo(screen: LiveDanceScreen, inRoom: Boolean, onDone: () -> Unit) {
        val s = sheet(screen, "Demo mode")
        s.note("Only admins see this. Demo lives show only to admins — real users never see them.")
        class It(val action: String, val icon: String, val title: String, val sub: String, val ok: (JSONObject) -> String)
        val items = if (inRoom) listOf(
            It("demo_fill", DIcon.USERS, "Add fake people to my live", "6 fake viewers + dance requests (battles) or song requests (solo), fake chat & votes") { "Fake people added" },
            It("demo_coins", DIcon.COINS, "Give me 5,000 test coins", "Free coins to test gifts") { "5,000 test coins added" },
        ) else listOf(
            It("demo_seed", DIcon.RADIO, "Add demo dance lives", "30 fake lives: 10 Solo, 10 1 vs 1, 10 2 Dancer Battle — with fake chat, gifts and votes") { r -> if (r.has("already")) "Demo lives are already there" else "${r.optInt("created")} demo lives added" },
            It("demo_coins", DIcon.COINS, "Give me 5,000 test coins", "Free coins to test entry fees, gifts and votes") { "5,000 test coins added" },
            It("demo_host", DIcon.CROWN, "Make me an approved Host", "Your account can Go Live without verification (for testing)") { "You are an approved Host now" },
            It("demo_clear", DIcon.TRASH, "Remove all demo data", "Deletes the fake rooms and fake people") { r -> "${r.optInt("removed")} demo rooms removed" },
        )
        var busy = ""
        items.forEach { it ->
            val danger = it.action == "demo_clear"
            val row = LinearLayout(s.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(if (danger) Ui.c("#fff5f5") else Ui.c("#fbf9ff")); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
                setPadding(Ui.dpi(12f), Ui.dpi(13f), Ui.dpi(12f), Ui.dpi(13f))
                isClickable = true; contentDescription = it.title
                Ui.pressable(this, 0.98f)
            }
            val ic = FrameLayout(s.context).apply { background = DK.oval(if (danger) Ui.c("#fee2e2") else DK.SOFT) }
            val icon = ImageView(s.context).apply { setImageDrawable(DIcon(it.icon, if (danger) Ui.c("#dc2626") else DK.PURPLE, 21f)); scaleType = ImageView.ScaleType.CENTER }
            ic.addView(icon, DK.flp())
            row.addView(ic, DK.llp(Ui.dpi(42f), Ui.dpi(42f)))
            val tx = LinearLayout(s.context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, 0, 0) }
            tx.addView(DK.t(s.context, it.title, 15.5f, DK.INK))
            tx.addView(DK.t(s.context, it.sub, 13f, DK.SUB, false, 0))
            row.addView(tx, DK.llp(0, DK.WRAP, 1f))
            row.setOnClickListener { _ ->
                if (busy.isNotEmpty()) return@setOnClickListener
                busy = it.action
                val spin = DotsSpinner(s.context)
                icon.visibility = View.GONE; ic.addView(spin, DK.flp(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER))
                DanceApi.call(it.action) { r ->
                    busy = ""; ic.removeView(spin); icon.visibility = View.VISIBLE
                    if (r.ok) { screen.showToast(it.ok(r.data)); onDone() } else screen.showToast(r.text())
                }
            }
            s.body.addView(row, DK.llp(b = 8f))
        }
        screen.sheets.open(s)
    }

    // ───────── Room Info ─────────
    fun info(screen: LiveDanceScreen, page: DanceRoomPage) {
        val room = page.roomJson() ?: return
        val host = room.optJSONObject("host") ?: JSONObject()
        val s = sheet(screen, "Room Info")
        val ctx = s.context
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(AvatarView(ctx, 60f, Ui.c("#e9d5ff")).apply { set(host.optString("photo")) })
        val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, Ui.dpi(8f), 0) }
        tx.addView(DK.t(ctx, host.optString("name"), 18f, DK.INK).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, VerifiedBadge(16f), null); compoundDrawablePadding = Ui.dpi(4f)
        }, DK.llp(DK.WRAP, DK.WRAP))
        tx.addView(DK.t(ctx, "Host · ${room.optString("language")}", 13.5f, DK.SUB, false))
        top.addView(tx, DK.llp(0, DK.WRAP, 1f))
        if (!page.hostMode()) {
            val fb = TextView(ctx)
            fun paint() {
                val on = page.isFollowed()
                fb.text = if (on) "Following" else "Follow"
                fb.setTextColor(if (on) DK.PURPLE else Color.WHITE)
                fb.background = if (on) DK.pill(Color.WHITE, DK.PURPLE, 1.5f) else DK.grad(DK.GRAD, 19f)
            }
            fb.typeface = Ui.bold; fb.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(14f)); fb.gravity = Gravity.CENTER
            fb.setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
            fb.contentDescription = "Follow"
            paint()
            fb.setOnClickListener { page.follow(); fb.postDelayed({ paint() }, 700) }
            Ui.pressable(fb, 0.96f)
            top.addView(fb, DK.llp(DK.WRAP, Ui.dpi(38f)))
        }
        s.body.addView(top, DK.llp(b = 12f))
        val rows = listOf("Title" to room.optString("title"), "Mode" to DK.mode(room.optString("mode")).label,
            "Entry fee" to "${page.feeOf()} coins (once per live)", "Watching now" to DK.n(room.optLong("viewers")))
        rows.forEach { (k, v) ->
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(2f), Ui.dpi(10f), Ui.dpi(2f), Ui.dpi(10f)) }
            r.addView(DK.t(ctx, k, 14.5f, DK.SUB, false), DK.llp(DK.WRAP, DK.WRAP))
            r.addView(DK.t(ctx, v, 14.5f, DK.INK, true, 2).apply { gravity = Gravity.END }, DK.llp(0, DK.WRAP, 1f, l = 10f))
            s.body.addView(r); s.body.addView(divider(s), DK.llp(DK.MATCH, Ui.dpi(1f)))
        }
        val idRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(2f), Ui.dpi(8f), Ui.dpi(2f), Ui.dpi(8f)) }
        idRow.addView(DK.t(ctx, "Room ID", 14.5f, DK.SUB, false), DK.llp(0, DK.WRAP, 1f))
        idRow.addView(Ui.text(ctx, room.optString("code"), 14f, DK.PURPLE).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(10f) }
            setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.COPY, DK.PURPLE, 15f), null); compoundDrawablePadding = Ui.dpi(6f)
            contentDescription = "Copy room ID"
            isClickable = true; setOnClickListener { page.copyId() }
        }, DK.llp(DK.WRAP, Ui.dpi(32f)))
        s.body.addView(idRow); s.body.addView(divider(s), DK.llp(DK.MATCH, Ui.dpi(1f)))
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(DK.lineButton(ctx, "Share", DIcon(DIcon.SHARE, DK.PURPLE, 17f), 46f).apply {
            setOnClickListener { s.close(); page.shareLive() }
        }, DK.llp(0, Ui.dpi(46f), 1f))
        if (!page.hostMode()) btns.addView(DK.lineButton(ctx, "Report", DIcon(DIcon.FLAG, DK.ERR, 17f), 46f, DK.ERR, Ui.c("#fecdd3")).apply {
            setOnClickListener { report(screen, page.roomId, JSONObject().put("user_id", host.optString("user_id")).put("name", host.optString("name"))) }
        }, DK.llp(0, Ui.dpi(46f), 1f, l = 10f))
        s.body.addView(btns, DK.llp(t = 14f))
        screen.sheets.open(s)
    }
    // ───────── Rules ─────────
    fun rules(screen: LiveDanceScreen, mode: String, fee: Int) {
        val s = sheet(screen, "Rules")
        val list = ArrayList<Triple<String, String, String>>()
        list.add(Triple("🎟️", "Entry fee", "Viewers pay $fee coins once per live. Chat is free."))
        if (mode == "solo") list.add(Triple("🎵", "Song requests", "Ask for a song type and dance style. The host sees how many people want each one."))
        else {
            list.add(Triple("✅", "How voting works", "Pick your favourite dancer and tap Vote. 1 free vote per battle. The dancer with more votes wins."))
            list.add(Triple("⏱️", "Battle timer", "The host chooses 3, 5, 10 or 15 minutes. Votes close when the time ends."))
            list.add(Triple("💃", "Join as a dancer", "Tap “Request to dance” on an empty seat. The host can accept or say no. No fee for dancers."))
        }
        list.add(Triple("🎁", "Gifts", "Gifts go to the person you choose. They get 70% of the gift value, the app keeps 30%."))
        list.add(Triple("💜", "Be respectful", "No bad language, no phone numbers or links. Dress and dance decently."))
        list.add(Triple("🚫", "Host control", "The host can mute, remove or block anyone who breaks the rules. Removed people don't get the entry back."))
        list.add(Triple("🔞", "18+ only", "Only adults can join Live Dance. Report anything wrong — our team checks every report."))
        list.forEach { (e, t, sub) ->
            val r = LinearLayout(s.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dpi(2f), Ui.dpi(10f), Ui.dpi(2f), Ui.dpi(10f)) }
            r.addView(Ui.text(s.context, e, 19f, DK.INK, false).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(12f) } }, DK.llp(Ui.dpi(38f), Ui.dpi(38f)))
            val tx = LinearLayout(s.context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, 0, 0) }
            tx.addView(DK.t(s.context, t, 15f, DK.INK))
            tx.addView(DK.t(s.context, sub, 13.5f, DK.SUB, false, 0).apply { setLineSpacing(0f, 1.2f) })
            r.addView(tx, DK.llp(0, DK.WRAP, 1f))
            s.body.addView(r); s.body.addView(divider(s), DK.llp(DK.MATCH, Ui.dpi(1f)))
        }
        screen.sheets.open(s)
    }

    // ───────── Top Supporters ─────────
    fun supporters(screen: LiveDanceScreen, roomId: String) {
        val s = sheet(screen, "Top Supporters")
        s.note("Who sent the most gifts in this live")
        val (box, fail) = loadingRow(s)
        DanceApi.call("supporters", JSONObject().put("room_id", roomId)) { r ->
            if (!r.ok) { fail(r.text()); return@call }
            s.body.removeView(box)
            val top = DK.arr(r.data.optJSONArray("top"))
            if (top.isEmpty()) { s.body.addView(DK.t(s.context, "No gifts yet. Be the first supporter 👑", 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(20f), 0, Ui.dpi(20f)) }); return@call }
            top.forEach { t ->
                val rank = t.optInt("rank")
                val row = LinearLayout(s.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(4f), Ui.dpi(9f), Ui.dpi(4f), Ui.dpi(9f)) }
                row.addView(Ui.text(s.context, if (rank <= 3) listOf("🥇", "🥈", "🥉")[rank - 1] else "$rank", if (rank <= 3) 20f else 15f, DK.SUB).apply { gravity = Gravity.CENTER }, DK.llp(Ui.dpi(28f), DK.WRAP))
                row.addView(AvatarView(s.context, 42f, if (rank == 1) Ui.c("#f5b301") else null).apply { set(t.optString("photo")) }, DK.llp(DK.WRAP, DK.WRAP, l = 10f))
                row.addView(DK.t(s.context, t.optString("name"), 15f, DK.INK), DK.llp(0, DK.WRAP, 1f, l = 10f))
                row.addView(Ui.text(s.context, DK.n(t.optLong("coins")), 15f, Ui.c("#92400e")).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(16f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
                })
                s.body.addView(row); s.body.addView(divider(s), DK.llp(DK.MATCH, Ui.dpi(1f)))
            }
        }
        screen.sheets.open(s)
    }

    // ───────── Battle Rank ─────────
    fun rank(screen: LiveDanceScreen, roomId: String, live: JSONObject?) {
        val s = sheet(screen, "Battle Rank")
        s.note("Winner is decided only by votes. Gifts show support.")
        val (box, fail) = loadingRow(s)
        DanceApi.call("battles", JSONObject().put("room_id", roomId)) { r ->
            if (!r.ok) { fail(r.text()); return@call }
            s.body.removeView(box)
            val cur = if (live?.optString("status") == "running") live else null
            val history = DK.arr(r.data.optJSONArray("history"))
            if (cur != null) s.body.addView(rankCard(s, cur, true), DK.llp(b = 10f))
            if (history.isEmpty() && cur == null) s.body.addView(DK.t(s.context, "No battles yet in this live.", 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(20f), 0, Ui.dpi(20f)) })
            history.forEach { s.body.addView(rankCard(s, it, false), DK.llp(b = 10f)) }
        }
        screen.sheets.open(s)
    }

    private fun rankCard(s: DanceSheet, b: JSONObject, cur: Boolean): View {
        val ctx = s.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(18f); setStroke(Ui.dpi(if (cur) 1.5f else 1f), if (cur) DK.PURPLE else DK.LINE) }
            setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(ctx, "Battle ${b.optInt("no")} · ${b.optInt("minutes")} min", 12.5f, DK.SUB).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.SWORDS, DK.PURPLE, 14f), null, null, null); compoundDrawablePadding = Ui.dpi(6f)
        }, DK.llp(0, DK.WRAP, 1f))
        val st = b.optString("status")
        head.addView(Ui.text(ctx, when { st == "running" -> "● Live now"; st == "cancelled" -> "Stopped"; b.optString("winner") == "tie" -> "Tie"; else -> "Finished" }, 12.5f,
            when (st) { "running" -> Ui.c("#e11d48"); "cancelled" -> DK.SUB; else -> Ui.c("#15803d") }))
        card.addView(head, DK.llp(b = 8f))
        val pa = DK.pct(b.optInt("votes_a"), b.optInt("votes_b"))
        listOf(0, 1).forEach { i ->
            val p = b.optJSONObject(if (i == 0) "a" else "b")
            val votes = b.optInt(if (i == 0) "votes_a" else "votes_b")
            val gifts = b.optLong(if (i == 0) "gifts_a" else "gifts_b")
            val win = b.optString("winner").isNotEmpty() && b.optString("winner") == p?.optString("user_id")
            val pc = if (i == 0) pa else 100 - pa
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
            val av = FrameLayout(ctx).apply { clipChildren = false }
            av.addView(AvatarView(ctx, 36f).apply { set(p?.optString("photo")) }, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER, t = 6f))
            if (win) av.addView(Ui.text(ctx, "👑", 14f, Color.WHITE, false), DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = -4f))
            row.addView(av)
            val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(8f), 0, Ui.dpi(8f), 0) }
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(DK.t(ctx, p?.optString("name").orEmpty(), 14f, DK.INK), DK.llp(DK.WRAP, DK.WRAP, 0f, r = 6f).apply { width = DK.WRAP })
            val sb = SpannableStringBuilder("${DK.n(votes)} ${if (votes == 1) "vote" else "votes"} ·  ")
            val at = sb.length; sb.append(" ${DK.c(gifts)}")
            sb.setSpan(CoinSpan(), at - 1, at, 0)
            line.addView(DK.t(ctx, sb, 12f, DK.SUB, false))
            mid.addView(line)
            val bar = FrameLayout(ctx).apply { background = GradientDrawable().apply { setColor(Ui.c("#f1ebfb")); cornerRadius = Ui.dp(4f) } }
            val fill = View(ctx).apply { background = DK.grad(if (i == 0) DK.BAR_A else DK.BAR_B, 4f, GradientDrawable.Orientation.LEFT_RIGHT) }
            bar.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
            bar.addOnLayoutChangeListener { v, l, _, r2, _, _, _, _, _ ->
                val w = ((r2 - l) * pc / 100f).toInt()
                if (fill.layoutParams.width != w) { fill.layoutParams.width = w; v.post { fill.requestLayout() } }
            }
            mid.addView(bar, DK.llp(DK.MATCH, Ui.dpi(8f), t = 4f))
            row.addView(mid, DK.llp(0, DK.WRAP, 1f))
            row.addView(Ui.text(ctx, "$pc%", 14f, DK.INK).apply { gravity = Gravity.END }, DK.llp(Ui.dpi(40f), DK.WRAP))
            card.addView(row, DK.llp(t = if (i == 0) 0f else 8f))
        }
        return card
    }

    // ───────── People in the room ─────────
    fun people(screen: LiveDanceScreen, page: DanceRoomPage) {
        val s = sheet(screen, "People in the room")
        val (box, fail) = loadingRow(s)
        DanceApi.call("users", JSONObject().put("room_id", page.roomId)) { r ->
            if (!r.ok) { fail(r.text()); return@call }
            s.body.removeView(box)
            s.note("${DK.n(r.data.optLong("online"))} watching now · ${DK.n(r.data.optLong("total"))} joined")
            DK.arr(r.data.optJSONArray("users")).forEach { u ->
                val row = LinearLayout(s.context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dpi(2f), Ui.dpi(9f), Ui.dpi(2f), Ui.dpi(9f))
                    isClickable = true; contentDescription = u.optString("name")
                    setOnClickListener { page.openPerson(u) }
                }
                row.addView(AvatarView(s.context, 42f, online = u.optBoolean("online")).apply { set(u.optString("photo")) })
                row.addView(DK.t(s.context, u.optString("name"), 15f, DK.INK), DK.llp(0, DK.WRAP, 1f, l = 10f))
                val role = u.optString("role")
                val tag = when (role) { "host" -> "👑 Host"; "dancer" -> "💃 Dancer${if (u.optInt("seat") > 0) " ${u.optInt("seat")}" else ""}"; else -> "" }
                if (tag.isNotEmpty()) row.addView(Ui.text(s.context, tag, 12f, if (role == "host") Ui.c("#92400e") else DK.PURPLE).apply {
                    background = DK.pill(if (role == "host") Ui.c("#fff3d6") else DK.SOFT); setPadding(Ui.dpi(9f), Ui.dpi(3f), Ui.dpi(9f), Ui.dpi(3f))
                })
                if (u.optBoolean("muted") && role != "viewer") row.addView(ImageView(s.context).apply { setImageDrawable(DIcon(DIcon.MIC_OFF, Ui.c("#e11d48"), 16f)) }, DK.llp(DK.WRAP, DK.WRAP, l = 6f))
                s.body.addView(row); s.body.addView(divider(s), DK.llp(DK.MATCH, Ui.dpi(1f)))
            }
        }
        screen.sheets.open(s)
    }

    // ───────── one person ─────────
    fun person(screen: LiveDanceScreen, page: DanceRoomPage, u: JSONObject) {
        val s = sheet(screen, null)
        val ctx = s.context
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, Ui.dpi(4f), 0, Ui.dpi(6f)) }
        head.addView(AvatarView(ctx, 76f, Ui.c("#e9d5ff")).apply { set(u.optString("photo")) })
        head.addView(DK.t(ctx, u.optString("name"), 19f, DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 8f))
        val onStage = page.targets().firstOrNull { it.optString("user_id") == u.optString("user_id") }
        if (onStage != null) head.addView(Ui.text(ctx, onStage.optString("label"), 13f, DK.PURPLE))
        s.body.addView(head)
        fun row(icon: String, label: String, danger: Boolean, click: () -> Unit) {
            s.body.addView(Ui.text(ctx, label, 15f, if (danger) DK.ERR else DK.INK).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(if (danger) Ui.c("#fff5f5") else Ui.c("#fbf9ff")); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), if (danger) Ui.c("#fecdd3") else DK.LINE) }
                setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(icon, if (danger) DK.ERR else DK.PURPLE, 20f), null, null, null); compoundDrawablePadding = Ui.dpi(12f)
                isClickable = true; contentDescription = label
                setOnClickListener { click() }
                Ui.pressable(this, 0.98f)
            }, DK.llp(DK.MATCH, Ui.dpi(52f), t = 8f))
        }
        val isHost = page.hostMode()
        val isDancer = onStage != null && onStage.optString("label") != "Host"
        if (onStage != null && page.meJson()?.optString("user_id") != u.optString("user_id") && !isHost)
            row(DIcon.GIFT, "Send a gift to ${DK.first(u.optString("name"))}", false) { page.pickTarget(u.optString("user_id")); gifts(screen, page) }
        if (isHost && isDancer) {
            row(if (u.optBoolean("host_muted")) DIcon.MIC else DIcon.MIC_OFF, if (u.optBoolean("host_muted")) "Unmute dancer" else "Mute dancer", false) { s.close(); page.personAct("mute", u, if (u.optBoolean("host_muted")) "Dancer unmuted" else "Dancer muted") }
            row(DIcon.USER_X, "Move off the stage", false) { s.close(); page.personAct("seat_remove", u, "Moved off the stage") }
        }
        if (isHost) {
            row(DIcon.LOG_OUT, "Remove from this live", true) { s.close(); page.personAct("kick", u, "${u.optString("name")} removed") }
            row(DIcon.BAN, "Block (remove & can't join again)", true) { s.close(); page.personAct("block", u, "${u.optString("name")} blocked") }
        } else row(DIcon.FLAG, "Report", true) { report(screen, page.roomId, u) }
        screen.sheets.open(s)
    }

    // ───────── Report ─────────
    fun report(screen: LiveDanceScreen, roomId: String, u: JSONObject) {
        val s = sheet(screen, "Report ${u.optString("name")}".trim())
        val ctx = s.context
        var reason = ""
        val chips = FlowRow(ctx)
        val views = ArrayList<TextView>()
        DK.REASONS.forEach { rs ->
            val c = DK.chip(ctx, rs, false, 36f, 13f)
            c.tag = rs
            c.setOnClickListener { reason = rs; views.forEach { v -> DK.paintChip(v, v.tag == rs) } }
            views.add(c)
            chips.addView(c, android.view.ViewGroup.MarginLayoutParams(DK.WRAP, Ui.dpi(36f)))
        }
        s.body.addView(chips, DK.llp())
        val details = EditText(ctx).apply {
            hint = "What happened?"; setHintTextColor(Ui.c("#9b93b3")); setTextColor(DK.INK); typeface = Ui.regular
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f)); gravity = Gravity.TOP or Gravity.START
            minLines = 4; maxLines = 6; filters = arrayOf(InputFilter.LengthFilter(500))
            background = GradientDrawable().apply { setColor(Ui.c("#fbf9ff")); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(1f), DK.LINE) }
            setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
            contentDescription = "What happened"
        }
        s.body.addView(details, DK.llp(t = 12f))
        val err = DK.t(ctx, "", 14f, DK.ERR, true, 0).apply { visibility = View.GONE }
        s.body.addView(err, DK.llp(t = 8f))
        var busy = false
        val btn = DK.button(ctx, "Send report", DIcon(DIcon.FLAG, Color.WHITE, 17f), colors = intArrayOf(Ui.c("#f43f5e"), Ui.c("#be123c")))
        btn.setOnClickListener {
            if (busy) return@setOnClickListener
            val d = details.text.toString().trim()
            if (reason.isEmpty()) { err.text = "Choose a reason."; err.visibility = View.VISIBLE; return@setOnClickListener }
            if (d.length < 10) { err.text = "Please write what happened (at least 10 letters)."; err.visibility = View.VISIBLE; return@setOnClickListener }
            busy = true; err.visibility = View.GONE
            DanceApi.call("report", JSONObject().put("room_id", roomId).put("user_id", u.optString("user_id")).put("reason", reason).put("details", d)) { r ->
                busy = false
                if (r.ok) { screen.showToast("Thanks — our team will check this."); s.close() } else { err.text = r.text(); err.visibility = View.VISIBLE }
            }
        }
        s.body.addView(btn, DK.llp(DK.MATCH, Ui.dpi(50f), t = 12f))
        screen.sheets.open(s)
    }

    // ───────── Request a Song ─────────
    fun song(screen: LiveDanceScreen, page: DanceRoomPage) {
        val s = sheet(screen, "Request a Song")
        val ctx = s.context
        val my = page.myRequest()
        if (my?.optString("kind") == "song" && my.optString("status") == "pending") {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, Ui.dpi(10f), 0, Ui.dpi(6f)) }
            box.addView(Ui.text(ctx, "⏳", 42f, DK.INK, false))
            box.addView(DK.t(ctx, "Your request is waiting", 17f, DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            box.addView(DK.t(ctx, "${my.optString("genre")}${if (my.optString("style").isNotBlank()) " · ${my.optString("style")}" else ""}. The host sees it.", 14f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            s.body.addView(box)
            screen.sheets.open(s)
            return
        }
        var genre = ""; var style = ""
        lateinit var send: TextView
        fun group(title: String, opt: Boolean, list: List<String>, icons: Map<String, String>, fallback: String, pick: (String) -> Unit) {
            val t = SpannableStringBuilder(title)
            if (opt) { val a = t.length; t.append(" (optional)"); t.setSpan(android.text.style.ForegroundColorSpan(DK.SUB), a, t.length, 0); t.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), a, t.length, 0) }
            s.body.addView(DK.t(ctx, t, 14.5f, DK.INK), DK.llp(t = if (s.body.childCount == 0) 2f else 14f, b = 8f))
            val flow = FlowRow(ctx)
            val chips = ArrayList<TextView>()
            list.forEach { v ->
                val c = DK.chip(ctx, "${icons[v] ?: fallback} $v", false, 38f, 13.5f)
                c.tag = v
                c.setOnClickListener {
                    val on = !(it.tag == (if (opt) style else genre))
                    pick(if (on) v else "")
                    chips.forEach { x -> DK.paintChip(x, on && x.tag == v) }
                    send.alpha = if (genre.isNotEmpty()) 1f else 0.5f
                }
                chips.add(c)
                flow.addView(c, android.view.ViewGroup.MarginLayoutParams(DK.WRAP, Ui.dpi(38f)))
            }
            s.body.addView(flow)
        }
        group("Song type", false, page.genres, DK.GENRE_ICON, "🎵") { genre = it }
        group("Dance style", true, page.styles, DK.STYLE_ICON, "💃") { style = it }
        val lab = SpannableStringBuilder("Song name")
        val a = lab.length; lab.append(" (optional)"); lab.setSpan(android.text.style.ForegroundColorSpan(DK.SUB), a, lab.length, 0)
        s.body.addView(DK.t(ctx, lab, 14.5f, DK.INK), DK.llp(t = 14f, b = 6f))
        val name = EditText(ctx).apply {
            hint = "e.g. Laung Laachi"; setHintTextColor(Ui.c("#9b93b3")); setTextColor(DK.INK); typeface = Ui.regular
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(15.5f)); isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(60))
            background = GradientDrawable().apply { setColor(Ui.c("#fbf9ff")); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(1f), DK.LINE) }
            setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
            contentDescription = "Song name"
        }
        s.body.addView(name, DK.llp(DK.MATCH, Ui.dpi(46f)))
        var busy = false
        send = DK.button(ctx, "Send request (free)", DIcon(DIcon.MUSIC, Color.WHITE, 18f)).apply {
            alpha = 0.5f
            setOnClickListener {
                if (genre.isEmpty() || busy) return@setOnClickListener
                busy = true
                page.requestSong(genre, style, name.text.toString().trim()) { ok -> busy = false; if (ok) s.close() }
            }
        }
        s.body.addView(send, DK.llp(DK.MATCH, Ui.dpi(50f), t = 16f))
        screen.sheets.open(s)
    }

    // ───────── Not enough coins ─────────
    fun coins(screen: LiveDanceScreen, need: Long, balance: Long) {
        val s = sheet(screen, null)
        val ctx = s.context
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, Ui.dpi(6f), 0, Ui.dpi(4f)) }
        box.addView(ImageView(ctx).apply { setImageDrawable(CoinIcon(54f)) }, DK.llp(Ui.dpi(54f), Ui.dpi(54f)))
        box.addView(DK.t(ctx, "Not enough coins", 20f, DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 10f, b = 6f))
        val sb = SpannableStringBuilder("This needs ")
        var a = sb.length; sb.append(DK.n(need)); sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, 0)
        sb.append(" coins. You have "); a = sb.length; sb.append(DK.n(balance)); sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, 0); sb.append(".")
        box.addView(DK.t(ctx, sb, 14.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, b = 16f))
        box.addView(DK.button(ctx, "Add coins", DIcon(DIcon.PLUS, Color.WHITE, 18f)).apply { setOnClickListener { s.close(); screen.nav.go("/wallet") } }, DK.llp(DK.MATCH, Ui.dpi(50f)))
        s.body.addView(box)
        screen.sheets.open(s)
    }

    // ───────── Yes / Cancel ─────────
    fun confirm(screen: LiveDanceScreen, title: String, text: String, yes: String, onYes: () -> Unit) {
        val s = sheet(screen, null)
        val ctx = s.context
        s.body.addView(DK.t(ctx, title, 20f, DK.INK, true, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 6f, b = 6f))
        s.body.addView(DK.t(ctx, text, 14.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, DK.llp(b = 16f))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(DK.lineButton(ctx, "Cancel").apply { setOnClickListener { s.close() } }, DK.llp(0, Ui.dpi(50f), 1f, r = 5f))
        row.addView(DK.button(ctx, yes, colors = intArrayOf(Ui.c("#f43f5e"), Ui.c("#be123c"))).apply { setOnClickListener { s.close(); onYes() } }, DK.llp(0, Ui.dpi(50f), 1f, l = 5f))
        s.body.addView(row)
        screen.sheets.open(s)
    }

    // ───────── Send a gift ─────────
    fun gifts(screen: LiveDanceScreen, page: DanceRoomPage) {
        val s = DanceSheet(screen.context, screen.layer, "Send a gift", scroll = false, pad = false)
        val ctx = s.context
        val list = page.targets().filter { it.optString("user_id") != page.meJson()?.optString("user_id") }
        var to = page.target()?.optString("user_id") ?: list.firstOrNull()?.optString("user_id").orEmpty()
        var grp = "popular"; var sel = "rose"; var count = 1; var busy = false
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(16f), Ui.dpi(4f), Ui.dpi(16f), 0) }
        s.body.addView(top)
        // who gets it
        val toRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (list.size > 1) {
            val hs = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            hs.addView(toRow); top.addView(hs, DK.llp(b = 8f))
        }
        // groups
        val grpRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val ghs = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(grpRow) }
        top.addView(ghs)
        // the grid
        val grid = androidx.recyclerview.widget.RecyclerView(ctx).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 4)
            setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f)); clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val gh = (screen.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels) * 0.36f
        s.body.addView(grid, DK.llp(DK.MATCH, gh.toInt()))
        // footer
        val foot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(16f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(16f)) }
        s.body.addView(View(ctx).apply { setBackgroundColor(DK.LINE) }, DK.llp(DK.MATCH, Ui.dpi(1f)))
        s.body.addView(foot)
        val fr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bal = Ui.text(ctx, page.balanceOf()?.let { DK.n(it) } ?: "…", 14f, DK.INK).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(18f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
        }
        fr.addView(bal)
        fr.addView(Ui.text(ctx, "Add coins", 13.5f, DK.PURPLE).apply { setPadding(Ui.dpi(8f), Ui.dpi(6f), Ui.dpi(8f), Ui.dpi(6f)); isClickable = true; setOnClickListener { s.close(); page.openWallet() } })
        fr.addView(View(ctx), DK.llp(0, 1, 1f))
        val counts = ArrayList<TextView>()
        DK.GIFT_COUNTS.forEach { n ->
            val c = Ui.text(ctx, "x$n", 13f, DK.INK).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = "x$n" }
            counts.add(c)
            fr.addView(c, DK.llp(Ui.dpi(40f), Ui.dpi(32f), l = 6f))
        }
        foot.addView(fr, DK.llp(b = 10f))
        val sendBox = FrameLayout(ctx)
        val send = DK.button(ctx, "")
        val spin = DotsSpinner(ctx, Color.WHITE).apply { visibility = View.GONE; elevation = Ui.dp(8f) }
        sendBox.addView(send, DK.flp(DK.MATCH, Ui.dpi(50f)))
        sendBox.addView(spin, DK.flp(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
        foot.addView(sendBox, DK.llp(DK.MATCH, Ui.dpi(50f)))

        lateinit var paint: () -> Unit
        class Adapter : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            var items = DK.GIFTS.filter { it.g == grp }
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(Ui.dpi(2f), Ui.dpi(9f), Ui.dpi(2f), Ui.dpi(7f))
                    isClickable = true
                    Ui.pressable(this, 0.95f)
                }
                cell.addView(Ui.text(ctx, "", 30f, DK.INK, false).apply { tag = "e"; gravity = Gravity.CENTER })
                cell.addView(DK.t(ctx, "", 11.5f, DK.INK).apply { tag = "n"; gravity = Gravity.CENTER })
                cell.addView(Ui.text(ctx, "", 11.5f, Ui.c("#92400e")).apply { tag = "p"; setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(12f), null, null, null); compoundDrawablePadding = Ui.dpi(2f) })
                cell.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(DK.MATCH, DK.WRAP).apply { setMargins(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)) }
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(cell) {}
            }
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, pos: Int) {
                val g = items[pos]
                val v = h.itemView as LinearLayout
                v.findViewWithTag<TextView>("e").text = g.e
                v.findViewWithTag<TextView>("n").text = g.name
                v.findViewWithTag<TextView>("p").text = DK.n(g.price)
                val on = g.id == sel
                v.background = GradientDrawable().apply { setColor(if (on) Ui.c("#f6efff") else Color.WHITE); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(if (on) 2f else 1f), if (on) DK.PURPLE else DK.LINE) }
                v.contentDescription = "${g.name}, ${g.price} coins"
                v.setOnClickListener { sel = g.id; paint() }
            }
        }
        val adapter = Adapter()
        grid.adapter = adapter
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun repaint() {
            // who
            toRow.removeAllViews()
            if (list.size > 1) list.forEach { t ->
                val on = t.optString("user_id") == to
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(if (on) Ui.c("#f6efff") else Color.WHITE); cornerRadius = Ui.dp(99f); setStroke(Ui.dpi(if (on) 2f else 1f), if (on) DK.PURPLE else DK.LINE) }
                    setPadding(Ui.dpi(4f), 0, Ui.dpi(12f), 0)
                    isClickable = true; contentDescription = "Gift to ${t.optString("name")}"
                    setOnClickListener { to = t.optString("user_id"); paint() }
                }
                chip.addView(AvatarView(ctx, 30f).apply { set(t.optString("photo")) })
                chip.addView(Ui.text(ctx, DK.first(t.optString("name")), 13f, DK.INK), DK.llp(DK.WRAP, DK.WRAP, l = 6f))
                chip.addView(Ui.text(ctx, t.optString("label"), 11f, DK.SUB, false), DK.llp(DK.WRAP, DK.WRAP, l = 4f))
                toRow.addView(chip, DK.llp(DK.WRAP, Ui.dpi(40f), r = 8f))
            }
            // groups
            grpRow.removeAllViews()
            DK.GIFT_GROUPS.forEach { (k, label, icon) ->
                val c = DK.chip(ctx, "$icon $label", k == grp, 34f, 13.5f)
                c.setOnClickListener { grp = k; adapter.items = DK.GIFTS.filter { it.g == grp }; adapter.notifyDataSetChanged(); paint() }
                grpRow.addView(c, DK.llp(DK.WRAP, Ui.dpi(34f), r = 6f))
            }
            counts.forEachIndexed { i, c ->
                val on = DK.GIFT_COUNTS[i] == count
                c.setTextColor(if (on) Color.WHITE else DK.INK)
                c.background = if (on) DK.grad(DK.GRAD, 10f) else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(10f); setStroke(Ui.dpi(1f), DK.LINE) }
                c.setOnClickListener { count = DK.GIFT_COUNTS[i]; paint() }
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            val g = DK.gift(sel)
            val who = list.firstOrNull { it.optString("user_id") == to } ?: list.firstOrNull()
            val sb = SpannableStringBuilder("Send ${g?.e ?: ""} ${g?.name ?: ""}${if (count > 1) " x$count" else ""} ·  ")
            val at = sb.length
            sb.append(" ${DK.n((g?.price ?: 0) * count)}")
            sb.setSpan(CoinSpan(), at - 1, at, 0)
            if (who != null && list.size > 1) sb.append(" → ${DK.first(who.optString("name"))}")
            if (!busy) send.text = sb
            send.alpha = if (who == null) 0.5f else 1f
            bal.text = page.balanceOf()?.let { DK.n(it) } ?: "…"
        }
        paint = { repaint() }
        send.setOnClickListener {
            val who = list.firstOrNull { it.optString("user_id") == to } ?: list.firstOrNull()
            if (busy || who == null) return@setOnClickListener
            busy = true; send.text = ""; spin.visibility = View.VISIBLE
            page.sendGift(sel, count, who.optString("user_id")) { ok ->
                busy = false; spin.visibility = View.GONE
                if (ok) s.close() else repaint()
            }
        }
        repaint()
        screen.sheets.open(s)
    }
}
