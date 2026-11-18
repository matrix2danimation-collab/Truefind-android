package app.truefind.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** The bottom sheets of the Live Room pages that are not inside a room (same texts and rules as the web sheets). */
object LiveSheets {

    fun sheet(screen: LiveRoomsScreen, title: String?, pad: Boolean = true, scroll: Boolean = true, maxFraction: Float = 0.88f): DanceSheet =
        DanceSheet(screen.context, screen.layer, title, scroll = scroll, pad = pad, maxFraction = maxFraction)

    /** a spinner where the answer will go; the returned function shows an error there instead */
    fun loading(parent: LinearLayout): Pair<FrameLayout, (String) -> Unit> {
        val ctx = parent.context
        val f = FrameLayout(ctx)
        f.addView(DotsSpinner(ctx), DK.flp(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER, t = 40f, b = 40f))
        parent.addView(f, DK.llp())
        return f to { err: String ->
            f.removeAllViews()
            f.addView(DK.t(ctx, err, 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.flp(DK.MATCH, DK.WRAP, Gravity.CENTER, t = 30f, b = 30f))
        }
    }

    fun label(s: LinearLayout, text: CharSequence, top: Float = 16f) =
        s.addView(DK.t(s.context, text, 14f, DK.INK, true, 0), DK.llp(t = top, b = 8f))

    fun input(ctx: android.content.Context, hint: String, max: Int, lines: Int = 1): EditText = EditText(ctx).apply {
        this.hint = hint; setHintTextColor(Ui.c("#9b93b3")); setTextColor(DK.INK); typeface = Ui.regular
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(if (lines > 1) 15.5f else 16f))
        filters = arrayOf(InputFilter.LengthFilter(max))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(1.5f), DK.LINE) }
        if (lines > 1) { minLines = lines; maxLines = lines + 2; gravity = Gravity.TOP or Gravity.START; setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f)) }
        else { isSingleLine = true; setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0) }
    }

    /** a sideways row of choice chips (on = purple, off = white with a line) */
    fun chipRow(s: LinearLayout, options: List<String>, selected: String, onPick: (String) -> Unit): HorizontalScrollView {
        val ctx = s.context
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: String) = views.forEach { v ->
            val on = v.tag == sel
            v.setTextColor(if (on) Color.WHITE else DK.INK)
            v.background = if (on) DK.grad(DK.GRAD, 19f) else DK.pill(Color.WHITE, DK.LINE, 1.5f)
            v.isSelected = on
        }
        options.forEach { o ->
            val v = Ui.text(ctx, o, 14.5f, DK.INK).apply {
                gravity = Gravity.CENTER; tag = o; contentDescription = o; isClickable = true
                setPadding(Ui.dpi(15f), 0, Ui.dpi(15f), 0)
                setOnClickListener { onPick(o); paint(o) }
            }
            views.add(v)
            row.addView(v, DK.llp(DK.WRAP, Ui.dpi(38f), r = 8f))
        }
        paint(selected)
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(row) }
        s.addView(hs, DK.llp())
        return hs
    }

    /** a button with a spinner in its place while busy */
    class BusyButton(val box: FrameLayout, val btn: TextView, private val spin: DotsSpinner) {
        private var label: CharSequence = btn.text
        private var icon: android.graphics.drawable.Drawable? = btn.compoundDrawablesRelative[0]
        var busy = false; private set
        fun set(b: Boolean) {
            if (b == busy) return
            busy = b
            if (b) { label = btn.text; icon = btn.compoundDrawablesRelative[0]; btn.text = ""; btn.setCompoundDrawablesRelative(null, null, null, null); spin.visibility = View.VISIBLE }
            else { btn.text = label; btn.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null); spin.visibility = View.GONE }
        }
    }
    fun busyButton(ctx: android.content.Context, label: CharSequence, icon: android.graphics.drawable.Drawable?, heightDp: Float = 50f, colors: IntArray = DK.GRAD, sizeDp: Float = 16.5f): BusyButton {
        val box = FrameLayout(ctx)
        val btn = DK.button(ctx, label, icon, heightDp, sizeDp, colors)
        val spin = DotsSpinner(ctx, Color.WHITE).apply { visibility = View.GONE; elevation = Ui.dp(8f) }
        box.addView(btn, DK.flp(DK.MATCH, Ui.dpi(heightDp)))
        box.addView(spin, DK.flp(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
        return BusyButton(box, btn, spin)
    }

    // ───────── Go Live (GoLiveSheet) — Audio only (owner rule, Nov 17) ─────────
    fun goLive(screen: LiveRoomsScreen) {
        val home = screen.home ?: return
        val ctx = screen.context
        val s = sheet(screen, "Go Live")
        if (!home.optBoolean("can_host")) {
            val female = home.optJSONObject("me")?.optString("gender") == "f"
            val who = if (female) "Creator" else "Host"
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(4f)) }
            box.addView(FrameLayout(ctx).apply {
                background = DK.oval(DK.SOFT)
                addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.RADIO, DK.PURPLE, 36f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
            }, DK.llp(Ui.dpi(74f), Ui.dpi(74f)))
            box.addView(DK.t(ctx, "Only approved ${who}s can go live", 20f, DK.INK, true, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 12f, b = 6f))
            box.addView(DK.t(ctx, "Become a verified $who to start your own audio room, get up to ${LK.MAX_COHOSTS} Co-Hosts and earn from gifts.", 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
            box.addView(DK.button(ctx, "Become a $who").apply {
                setOnClickListener { s.close(); screen.nav.go(if (female) "/register/verification" else "/profile?open=host") }
            }, DK.llp(DK.MATCH, Ui.dpi(50f), t = 18f))
            s.body.addView(box)
            screen.sheets.open(s)
            return
        }
        val mine = home.optJSONObject("my_room")
        val cats = home.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }?.ifEmpty { null } ?: LK.CATEGORIES
        val langs = home.optJSONArray("languages")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }?.ifEmpty { null } ?: LK.LANGUAGES
        var category = mine?.optString("category")?.takeIf { cats.contains(it) } ?: "Chat"
        var language = mine?.optString("language")?.takeIf { langs.contains(it) } ?: "Hindi"
        // the room kind: Audio Room only
        val kind = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(18f); setStroke(Ui.dpi(2f), DK.PURPLE) }
            setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
        }
        kind.addView(FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, DK.GRAD).apply { shape = GradientDrawable.OVAL }
            addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.MIC, Color.WHITE, 23f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
        }, DK.llp(Ui.dpi(46f), Ui.dpi(46f)))
        val kt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, 0, 0) }
        kt.addView(DK.t(ctx, "Audio Room", 15.5f, DK.INK))
        kt.addView(DK.t(ctx, "Voice only · up to ${LK.MAX_COHOSTS} Co-Hosts", 12.5f, DK.SUB, false))
        kind.addView(kt, DK.llp(0, DK.WRAP, 1f))
        s.body.addView(kind, DK.llp(t = 4f))
        label(s.body, "Room name")
        val name = input(ctx, "e.g. Chill Talk Room", 40).apply { setText(mine?.optString("name").orEmpty()); contentDescription = "Room name" }
        s.body.addView(name, DK.llp(DK.MATCH, Ui.dpi(48f)))
        label(s.body, "Category")
        chipRow(s.body, cats, category) { category = it }
        label(s.body, "Language")
        chipRow(s.body, langs, language) { language = it }
        val about = SpannableStringBuilder("About your room ")
        val a0 = about.length; about.append("(optional)")
        about.setSpan(android.text.style.ForegroundColorSpan(DK.SUB), a0, about.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label(s.body, about)
        val desc = input(ctx, "Fun talks, new friends, voice chat.", 180, 2).apply { setText(mine?.optString("description").orEmpty()); contentDescription = "About your room" }
        s.body.addView(desc, DK.llp(DK.MATCH, DK.WRAP))
        val note = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(14f) }
            setPadding(Ui.dpi(13f), Ui.dpi(11f), Ui.dpi(13f), Ui.dpi(11f))
        }
        note.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.USERS, DK.PURPLE, 19f)) }, DK.llp(Ui.dpi(19f), Ui.dpi(19f), t = 1f))
        val fee = home.optJSONObject("fee")?.optInt("audio")?.takeIf { it > 0 } ?: LK.feeOf("audio")
        note.addView(DK.t(ctx, "Up to ${LK.MAX_COHOSTS} people can join you as Co-Host: $fee coins each. You get ${(LK.GIFT_SHARE * 100).toInt()}% of every gift and ${((LK.FEE_SHARE["audio"] ?: 0.6) * 100).toInt()}% of every Co-Host fee.", 13.5f, Ui.c("#3b2a63"), false, 0).apply { setLineSpacing(0f, 1.2f) },
            DK.llp(0, DK.WRAP, 1f, l = 10f))
        s.body.addView(note, DK.llp(t = 14f))
        val err = DK.t(ctx, "", 14f, Ui.c("#dc2626"), true, 0).apply { visibility = View.GONE }
        s.body.addView(err, DK.llp(t = 12f))
        val start = busyButton(ctx, if (mine?.optBoolean("live") == true) "Restart Live" else "Start Live", DIcon(DIcon.RADIO, Color.WHITE, 20f))
        start.btn.contentDescription = "Start Live"
        s.body.addView(start.box, DK.llp(DK.MATCH, Ui.dpi(50f), t = 16f))
        s.body.addView(DK.t(ctx, "Keep it respectful. Numbers, links, IDs and adult content are not allowed.", 12.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(t = 10f))
        start.btn.setOnClickListener {
            if (start.busy) return@setOnClickListener
            val n = name.text.toString().trim()
            if (n.length < 3) { err.text = "Give your room a name (at least 3 letters)."; err.visibility = View.VISIBLE; return@setOnClickListener }
            start.set(true); err.visibility = View.GONE
            val body = JSONObject().put("mode", "audio").put("name", n).put("category", category).put("language", language).put("description", desc.text.toString().trim())
            LiveApi.call("go_live", body) { r ->
                start.set(false)
                val id = r.data.optJSONObject("room")?.optString("id").orEmpty()
                if (r.ok && id.isNotEmpty()) { s.hideKeyboard(); s.close(); screen.loadHome(); screen.nav.go("/live/$id") }
                else { err.text = r.text("Could not start the live. Try again."); err.visibility = View.VISIBLE }
            }
        }
        screen.sheets.open(s)
    }

    // ───────── Demo mode (admins only; opened from /live?demo=1) ─────────
    fun demo(screen: LiveRoomsScreen, onDone: () -> Unit) {
        val s = sheet(screen, "Demo mode")
        s.note("Only admins see this. Demo rooms show only on admin accounts: real members never see them.")
        class It(val action: String, val icon: String, val title: String, val sub: String, val ok: (JSONObject) -> String)
        val items = listOf(
            It("demo_seed", LK.SPARKLES, "Add demo live rooms", "Demo audio rooms with demo people sending Super Chats, gifts and join requests") { r -> if (r.has("already")) "${r.optInt("already")} demo rooms are already live" else "${r.optInt("created")} demo rooms are live now" },
            It("demo_coins", DIcon.COINS, "Give me 5,000 test coins", "Free coins in your own wallet to try gifts and Co-Host seats") { "5,000 test coins added" },
            It("demo_host", DIcon.CROWN, "Make me an approved Host", "Your account can Go Live without verification (for testing)") { "You are an approved Host now" },
            It("demo_clear", DIcon.TRASH, "Remove all demo rooms", "Deletes the fake rooms and their chats") { r -> "${r.optInt("removed")} demo rooms removed" },
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
                LiveApi.call(it.action) { r ->
                    busy = ""; ic.removeView(spin); icon.visibility = View.VISIBLE
                    if (r.ok) { screen.showToast(it.ok(r.data)); s.close(); onDone() } else screen.showToast(r.text())
                }
            }
            s.body.addView(row, DK.llp(b = 8f))
        }
        screen.sheets.open(s)
    }

    // ───────── How ranking works ─────────
    fun rankInfo(screen: LiveRoomsScreen) {
        val s = sheet(screen, "How ranking works")
        val ctx = s.context
        val first = SpannableStringBuilder("Hosts are ranked by the gifts (")
        val at = first.length; first.append(" coins) they receive in their Live Rooms.")
        first.insert(at, " ")
        first.setSpan(object : android.text.style.ReplacementSpan() {
            override fun getSize(p: android.graphics.Paint, t: CharSequence?, st: Int, e: Int, fm: android.graphics.Paint.FontMetricsInt?) = Ui.dpi(15f)
            override fun draw(c: android.graphics.Canvas, t: CharSequence?, st: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint) {
                val d = LK.diamond(14f); val sz = Ui.dpi(14f); val mid = (top + bottom) / 2
                d.setBounds(x.toInt(), mid - sz / 2, x.toInt() + sz, mid + sz / 2); d.draw(c)
            }
        }, at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        listOf<CharSequence>(first, "Weekly starts every Monday, Monthly on the 1st (Indian time). All Time never resets.",
            "Top 30 hosts are shown. Higher levels and ranks get more visibility in Live Rooms.").forEach { line ->
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(Ui.text(ctx, "•", 15f, Ui.c("#3b2a63")), DK.llp(Ui.dpi(16f), DK.WRAP))
            r.addView(DK.t(ctx, line, 15f, Ui.c("#3b2a63"), false, 0).apply { setLineSpacing(0f, 1.3f) }, DK.llp(0, DK.WRAP, 1f))
            s.body.addView(r, DK.llp(t = 4f, b = 2f))
        }
        screen.sheets.open(s)
    }

    // ───────── Room Host Profile (HostSheet) ─────────
    /**
     * [inRoomGift]: inside the room, "Send Gift" opens the room's gifts; null = from the list / Leaderboard
     * (opens the room and its gifts while the host is live).
     */
    fun hostProfile(screen: LiveRoomsScreen, roomId: String, inRoomGift: (() -> Unit)?, isHostHere: Boolean = false) {
        val s = sheet(screen, "Room Host Profile")
        val ctx = s.context
        val (box, fail) = loading(s.body)
        LiveApi.call("host_profile", JSONObject().put("room_id", roomId)) { r ->
            if (!s.isOpen) return@call
            if (!r.ok) { fail(r.text("Could not load the host profile.")); return@call }
            s.body.removeView(box)
            val d = r.data
            val room = d.optJSONObject("room") ?: JSONObject()
            val host = room.optJSONObject("host") ?: JSONObject()
            val stats = d.optJSONObject("stats") ?: JSONObject()
            var followed = d.optBoolean("followed")
            var followers = stats.optInt("followers")
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            // top: photo with a crown · name, Host, ID, live · level badge
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
            val av = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            av.addView(AvatarView(ctx, 92f, Ui.c("#f5c84b"), 4.6f).apply { set(host.optString("photo")) })
            av.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CROWN, Ui.c("#f5b301"), 26f, Ui.c("#ffd54a"))); rotation = 18f }, DK.flp(Ui.dpi(26f), Ui.dpi(26f), Gravity.TOP or Gravity.END, t = -12f, r = 2f))
            top.addView(av, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(14f), 0, Ui.dpi(6f), 0) }
            mid.addView(LK.nameBadge(ctx, host.optString("name"), 20f, DK.INK, 19f).apply { maxWidth = Ui.dpi(150f) }, DK.llp(DK.WRAP, DK.WRAP))
            mid.addView(Ui.text(ctx, "👑 Host", 12.5f, Color.WHITE).apply { background = DK.grad(DK.GRAD, 99f); setPadding(Ui.dpi(10f), Ui.dpi(3f), Ui.dpi(10f), Ui.dpi(3f)) }, DK.llp(DK.WRAP, DK.WRAP, t = 5f))
            mid.addView(Ui.text(ctx, "ID: ${room.optString("code")}", 13.5f, DK.SUB, false).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.COPY, DK.SUB, 15f), null); compoundDrawablePadding = Ui.dpi(6f)
                contentDescription = "Copy Room ID"; isClickable = true
                setOnClickListener { screen.showToast(if (liveCopy(ctx, room.optString("code"))) "Room ID copied" else "Could not copy") }
            }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            val live = room.optBoolean("live")
            val liveRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            liveRow.addView(if (live) BlinkDot(ctx, Ui.c("#22c55e")) else View(ctx).apply { background = DK.oval(Ui.c("#b9b2cc")) }, DK.llp(Ui.dpi(9f), Ui.dpi(9f), r = 6f))
            liveRow.addView(Ui.text(ctx, if (live) "Live now" else "Offline", 13.5f, if (live) Ui.c("#16a34a") else DK.SUB))
            mid.addView(liveRow, DK.llp(DK.WRAP, DK.WRAP, t = 4f))
            top.addView(mid, DK.llp(0, DK.WRAP, 1f))
            top.addView(LevelBadgeView(ctx, 78f, true).apply { level = d.optInt("level", 1) })
            wrap.addView(top)
            val bio = d.optString("bio")
            if (bio.isNotBlank()) {
                val sb = SpannableStringBuilder("“ ").append(bio)
                sb.setSpan(android.text.style.ForegroundColorSpan(DK.PURPLE), 0, 1, 0); sb.setSpan(android.text.style.RelativeSizeSpan(1.5f), 0, 1, 0)
                wrap.addView(DK.t(ctx, sb, 14.5f, Ui.c("#3b2a63"), false, 0).apply {
                    background = GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(14f) }
                    setPadding(Ui.dpi(14f), Ui.dpi(11f), Ui.dpi(14f), Ui.dpi(11f)); setLineSpacing(0f, 1.2f)
                }, DK.llp(t = 14f))
            }
            // four numbers
            val grid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val rating = if (stats.isNull("rating") || !stats.has("rating")) "New" else "${stats.optInt("rating")}%"
            listOf(Triple("👥", LK.n(stats.optLong("online")), "Online"), Triple("👁️", LK.k(stats.optLong("total_visits")), "Total Visits"),
                Triple("🎁", LK.k(stats.optLong("gifts")), "Gifts"), Triple("💗", rating, "Rating")).forEachIndexed { i, (e, v, l) ->
                val c = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(1f), DK.LINE) }
                    setPadding(Ui.dpi(4f), Ui.dpi(10f), Ui.dpi(4f), Ui.dpi(10f))
                }
                c.addView(Ui.text(ctx, e, 17f, DK.INK, false))
                c.addView(DK.t(ctx, v, 16.5f, DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 2f))
                c.addView(DK.t(ctx, l, 11.5f, DK.SUB, false).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 1f))
                grid.addView(c, DK.llp(0, DK.WRAP, 1f, l = if (i == 0) 0f else 8f))
            }
            wrap.addView(grid, DK.llp(t = 12f))
            // the rows
            val rows = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), DK.LINE) }
            }
            val cat = room.optString("category").let { if (it == "Chat" || it.isBlank()) "Live Talk & Meet" else it }
            listOf(Triple("Room Name", room.optString("name"), false), Triple("Room ID", room.optString("code"), true), Triple("Category", cat, false),
                Triple("Language", room.optString("language"), false), Triple("Hosting since", LK.dateLong(d.optString("hosting_since")), false)).forEachIndexed { i, (k, v, copy) ->
                if (i > 0) rows.addView(View(ctx).apply { setBackgroundColor(DK.LINE) }, DK.llp(DK.MATCH, Ui.dpi(1f)))
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(14f), Ui.dpi(11f), Ui.dpi(14f), Ui.dpi(11f)) }
                row.addView(DK.t(ctx, k, 14.5f, DK.SUB, true), DK.llp(DK.WRAP, DK.WRAP))
                row.addView(DK.t(ctx, v, 14.5f, DK.INK).apply { gravity = Gravity.END }, DK.llp(0, DK.WRAP, 1f, l = 10f))
                if (copy) row.addView(DK.iconButton(ctx, DIcon(DIcon.COPY, DK.SUB, 15f), 26f, null, "Copy $k").apply {
                    setOnClickListener { screen.showToast(if (liveCopy(ctx, v)) "Copied" else "Could not copy") }
                }, DK.llp(Ui.dpi(26f), Ui.dpi(26f), l = 6f))
                rows.addView(row)
            }
            wrap.addView(rows, DK.llp(t = 12f))
            // Follow / Send Gift
            val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val mine = d.optBoolean("mine")
            if (!mine) {
                val fb = DK.button(ctx, "", null)
                var busy = false
                fun paint() {
                    fb.text = if (followed) "Following" else "Follow"
                    fb.contentDescription = fb.text
                    fb.setTextColor(if (followed) DK.PURPLE else Color.WHITE)
                    fb.background = if (followed) DK.pill(Ui.c("#efe7fd")) else DK.grad(DK.GRAD, 25f)
                    fb.elevation = if (followed) 0f else Ui.dp(5f)
                    fb.setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(if (followed) DIcon.CHECK else DIcon.PLUS, if (followed) DK.PURPLE else Color.WHITE, 19f), null, null, null)
                }
                paint()
                fb.setOnClickListener {
                    if (busy) return@setOnClickListener
                    busy = true
                    LiveApi.call("follow", JSONObject().put("room_id", roomId).put("on", !followed)) { res ->
                        busy = false
                        if (res.ok) {
                            followed = res.data.optBoolean("followed"); followers += if (followed) 1 else -1
                            paint(); screen.showToast(if (followed) "You follow this room now" else "Unfollowed")
                        } else screen.showToast(res.text())
                    }
                }
                btns.addView(fb, DK.llp(0, Ui.dpi(50f), 1f))
            } else {
                btns.addView(DK.t(ctx, "This is your room · ${LK.n(followers)} followers", 14.5f, DK.SUB, true, 0).apply { gravity = Gravity.CENTER }, DK.llp(0, DK.WRAP, 1f, t = 14f, b = 14f))
            }
            if (!isHostHere && !mine) {
                btns.addView(DK.lineButton(ctx, "Send Gift", DIcon(DIcon.GIFT, DK.PURPLE, 19f)).apply {
                    setOnClickListener {
                        s.close()
                        if (inRoomGift != null) inRoomGift()
                        else if (live) { screen.giftOnJoin = room.optString("id"); screen.nav.go("/live/${room.optString("id")}") }
                        else screen.showToast("Gifts can be sent while the host is live.")
                    }
                }, DK.llp(0, Ui.dpi(50f), 1f, l = 10f))
            }
            wrap.addView(btns, DK.llp(t = 16f))
            s.body.addView(wrap)
            wrap.fadeUpIn()
        }
        screen.sheets.open(s)
    }
}

/** copies [text] to the phone's clipboard */
fun liveCopy(ctx: android.content.Context, text: String): Boolean = try {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("True Find", text)); true
} catch (e: Exception) { false }
