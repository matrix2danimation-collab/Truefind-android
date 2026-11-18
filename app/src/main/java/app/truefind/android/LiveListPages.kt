package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/** the rooms of the list: Audio rooms only (owner rule, Nov 17: video lives are in Live Dance) */
internal fun liveAudioRooms(home: JSONObject?): List<JSONObject> = DK.arr(home?.optJSONArray("rooms")).filter { it.optString("mode", "audio") == "audio" }
internal fun liveMine(home: JSONObject?): JSONObject? = home?.optJSONObject("my_room")?.takeIf { it.optBoolean("live") }

/** "You are live: … · tap to go back" (the purple banner of the list) */
internal fun liveMineBanner(ctx: Context, r: JSONObject, open: () -> Unit): View {
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = DK.grad(DK.GRAD, 18f); elevation = Ui.dp(6f)
        outlineSpotShadowColorCompat(Ui.c("#7a1fe4"))
        setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
        contentDescription = "You are live: ${r.optString("name")}. Tap to go back"
        isClickable = true
        setOnClickListener { open() }
        Ui.pressable(this, 0.97f)
    }
    card.addView(BlinkDot(ctx, Ui.c("#ff8fb1")), DK.llp(Ui.dpi(10f), Ui.dpi(10f)))
    val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, Ui.dpi(8f), 0) }
    tx.addView(DK.t(ctx, "You are live: ${r.optString("name")}", 15.5f, Color.WHITE))
    tx.addView(DK.t(ctx, "${LK.k(r.optLong("viewers"))} in your room · tap to go back", 13f, Ui.ca("#ffffff", 0.9f), false))
    card.addView(tx, DK.llp(0, DK.WRAP, 1f))
    card.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CHEVRON_RIGHT, Color.WHITE, 20f)) })
    return card
}

/** the floating "+ Go Live" / "Open My Live" button at the bottom of the list pages */
internal fun liveGoLiveButton(ctx: Context): TextView = DK.button(ctx, "Go Live", null, 54f, 17f).apply {
    setPadding(Ui.dpi(14f), 0, Ui.dpi(26f), 0)
    background = DK.grad(DK.GRAD, 27f)
    elevation = Ui.dp(10f)
    compoundDrawablePadding = Ui.dpi(8f)
    val plus = object : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ca("#ffffff", 0.22f) }
        private val icon = DIcon(DIcon.PLUS, Color.WHITE, 20f, stroke = 2.8f)
        override fun getIntrinsicWidth() = Ui.dpi(28f)
        override fun getIntrinsicHeight() = Ui.dpi(28f)
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, p)
            val s = Ui.dpi(20f); val l = b.left + (b.width() - s) / 2; val t = b.top + (b.height() - s) / 2
            icon.setBounds(l, t, l + s, t + s); icon.draw(c)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    setCompoundDrawablesRelativeWithIntrinsicBounds(plus, null, null, null)
    visibility = View.GONE
}

// ═════════════════════════════ /live — the Live Room page ═════════════════════════════
/** The Live Room page (LiveRooms.jsx without ?m): the big Audio Room box, "You are live", Go Live. */
@SuppressLint("ViewConstructor")
class LiveHomePage(ctx: Context, private val screen: LiveRoomsScreen) : FrameLayout(ctx) {
    private val box = AudioBox(ctx)
    private val mineBox = FrameLayout(ctx)
    private val errBox = FrameLayout(ctx)
    private val goLive = liveGoLiveButton(ctx)
    private var mineSig = ""

    init {
        background = LiveBg.page()
        isClickable = true
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; clipToPadding = false }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Ui.dpi(110f)) }
        sv.addView(col)
        // header: back · (nothing in the middle: no "True Find", owner rule Nov 17) · Leaderboard
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(6f), 0) }
        head.addView(DK.iconButton(ctx, DIcon(DIcon.CHEVRON_LEFT, DK.INK, 28f), 44f, null, "Back").apply { setOnClickListener { screen.nav.back() } })
        head.addView(View(ctx), DK.llp(0, 1, 1f))
        head.addView(DK.iconButton(ctx, DIcon(DIcon.TROPHY, DK.INK, 25f), 44f, null, "Leaderboard").apply { setOnClickListener { screen.nav.go("/live/leaderboard") } })
        col.addView(head)
        col.addView(Ui.text(ctx, "Live Room", 30f, DK.INK).apply { gravity = Gravity.CENTER; letterSpacing = -0.01f }, DK.llp(DK.MATCH, DK.WRAP, t = 8f))
        col.addView(box, DK.llp(DK.MATCH, DK.WRAP, l = 16f, t = 16f, r = 16f))
        col.addView(mineBox, DK.llp(DK.MATCH, DK.WRAP))
        col.addView(errBox, DK.llp(DK.MATCH, DK.WRAP))
        addView(sv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        goLive.setOnClickListener { val m = liveMine(screen.home); if (m != null) openRoom(m) else LiveSheets.goLive(screen) }
        addView(goLive, DK.flp(DK.WRAP, Ui.dpi(54f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 18f))
        box.setOnClickListener { screen.nav.go("/live?m=audio") }
        render()
        box.fadeUpIn()
    }

    private fun openRoom(r: JSONObject) = screen.nav.go("/live/${r.optString("id")}")

    /** (admins) the demo tools: /live?demo=1 — there is no Demo button on the page */
    fun openDemoIfAdmin() {
        val h = screen.home
        if (h == null) { postDelayed({ if (screen.home != null) openDemoIfAdmin() }, 800); return }
        if (h.optBoolean("is_admin")) LiveSheets.demo(screen) { screen.loadHome() }
    }

    fun render() {
        val h = screen.home
        val rooms = liveAudioRooms(h)
        box.bind(rooms, h == null)
        val mine = liveMine(h)
        goLive.visibility = if (h != null) View.VISIBLE else View.GONE
        goLive.text = if (mine != null) "Open My Live" else "Go Live"
        goLive.contentDescription = goLive.text
        val sig = mine?.let { "${it.optString("id")}|${it.optString("name")}|${it.optLong("viewers")}" } ?: ""
        if (sig != mineSig) {
            mineSig = sig
            mineBox.removeAllViews()
            if (mine != null) mineBox.addView(liveMineBanner(context, mine) { openRoom(mine) }, DK.flp(DK.MATCH, DK.WRAP, l = 16f, t = 12f, r = 16f))
        }
        errBox.removeAllViews()
        if (h == null && screen.homeErr.isNotEmpty()) {
            val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(10f), Ui.dpi(24f), Ui.dpi(10f), Ui.dpi(10f)) }
            c.addView(DK.t(context, screen.homeErr, 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER })
            c.addView(DK.lineButton(context, "Try again", heightDp = 44f).apply { setPadding(Ui.dpi(26f), 0, Ui.dpi(26f), 0); setOnClickListener { screen.loadHome() } }, DK.llp(DK.WRAP, Ui.dpi(44f), t = 14f))
            errBox.addView(c)
        }
    }

    /** RoomModeBox (audio): the big box in the same pattern as the Live Dance mode boxes */
    private class AudioBox(ctx: Context) : LinearLayout(ctx) {
        private val head = FrameLayout(ctx)
        private val liveTv: TextView
        private val liveDot = BlinkDot(ctx, Color.WHITE)
        private val livePill = LinearLayout(ctx)
        private val listen: TextView
        private val faces = LinearLayout(ctx)
        private val cta: TextView

        init {
            orientation = VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(24f); setStroke(Ui.dpi(1f), DK.LINE) }
            elevation = Ui.dp(6f)
            outlineSpotShadowColorCompat(Ui.c("#5a0fc2"))
            DK.roundClip(this, 24f)
            isClickable = true
            Ui.pressable(this, 0.98f)
            head.background = LiveBg.audioBox()
            addView(head, DK.llp(DK.MATCH, Ui.dpi(178f)))
            head.addView(BeamView(ctx, Ui.ca("#ffffff", 0.22f), 0).apply { rotation = 18f; translationX = Ui.dp(52f); translationY = -Ui.dp(40f) },
                DK.flp(Ui.dpi(120f), Ui.dpi(260f), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            // top line: LIVE · listening · seat fee
            val topRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            livePill.orientation = HORIZONTAL; livePill.gravity = Gravity.CENTER_VERTICAL
            livePill.setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
            livePill.addView(liveDot, DK.llp(Ui.dpi(7f), Ui.dpi(7f), r = 5f))
            liveTv = Ui.text(ctx, "…", 12.5f, Color.WHITE).apply { letterSpacing = 0.03f }
            livePill.addView(liveTv)
            topRow.addView(livePill, DK.llp(DK.WRAP, Ui.dpi(24f)))
            listen = Ui.text(ctx, "", 12.5f, Color.WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = DK.pill(Ui.ca("#140528", 0.35f))
                setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(LK.HEADPHONES, Color.WHITE, 14f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
            }
            topRow.addView(listen, DK.llp(DK.WRAP, Ui.dpi(24f), l = 6f))
            topRow.addView(View(ctx), DK.llp(0, 1, 1f))
            topRow.addView(Ui.text(ctx, "${LK.feeOf("audio")} seat", 13f, Ui.c("#92400e")).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = DK.pill(Ui.c("#fff7e6"))
                setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(15f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
                contentDescription = "Join a seat for ${LK.feeOf("audio")} coins"
            }, DK.llp(DK.WRAP, Ui.dpi(26f)))
            head.addView(topRow, DK.flp(DK.MATCH, DK.WRAP, Gravity.TOP, 14f, 14f, 14f))
            // the mic in a circle, bottom-right
            val icon = FrameLayout(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ca("#ffffff", 0.14f)); setStroke(Ui.dpi(1f), Ui.ca("#ffffff", 0.25f)) } }
            icon.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.MIC, Color.WHITE, 44f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
            head.addView(icon, DK.flp(Ui.dpi(92f), Ui.dpi(92f), Gravity.BOTTOM or Gravity.END, r = 14f, b = 12f))
            // title, line, faces bottom-left
            val txt = LinearLayout(ctx).apply { orientation = VERTICAL }
            txt.addView(Ui.text(ctx, "Audio Room", 26f, Color.WHITE).apply { letterSpacing = -0.01f })
            txt.addView(DK.t(ctx, "Listen live and talk on a seat", 13.5f, Ui.ca("#ffffff", 0.92f), false, 2).apply { setPadding(0, Ui.dpi(4f), 0, 0) })
            faces.orientation = HORIZONTAL; faces.gravity = Gravity.CENTER_VERTICAL
            txt.addView(faces, DK.llp(DK.WRAP, DK.WRAP, t = 10f))
            head.addView(txt, DK.flp(DK.MATCH, DK.WRAP, Gravity.BOTTOM, 16f, 0f, 120f, 16f))
            addView(DK.steps(ctx, listOf("🎧" to "Listen live", "💬" to "Super Chat", "🎁" to "Send gifts", "🙋" to "Join list")), DK.llp(DK.MATCH, DK.WRAP, l = 12f, t = 12f, r = 12f, b = 10f))
            cta = DK.button(ctx, "", null, 46f, 15.5f).apply {
                background = DK.grad(DK.GRAD, 14f); elevation = 0f; isClickable = false
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.CHEVRON_RIGHT, Color.WHITE, 19f), null); compoundDrawablePadding = Ui.dpi(6f)
            }
            addView(cta, DK.llp(DK.MATCH, Ui.dpi(46f), l = 12f, r = 12f, b = 12f))
        }

        fun bind(rooms: List<JSONObject>, loading: Boolean) {
            val n = rooms.size
            liveTv.text = if (loading) "…" else if (n > 0) "$n LIVE" else "NO LIVE"
            liveDot.visibility = if (n > 0) View.VISIBLE else View.GONE
            livePill.background = if (n > 0) DK.grad(intArrayOf(Ui.c("#ff3b5c"), Ui.c("#e11d48")), 99f) else DK.pill(Ui.ca("#ffffff", 0.22f))
            val listening = rooms.sumOf { it.optLong("viewers") }
            listen.text = LK.c(listening); listen.visibility = if (listening > 0) View.VISIBLE else View.GONE
            faces.removeAllViews()
            rooms.take(4).forEachIndexed { i, r ->
                faces.addView(AvatarView(context, 30f, Color.WHITE, 2f).apply { set(r.optJSONObject("host")?.optString("photo")) }, DK.llp(DK.WRAP, DK.WRAP, l = if (i == 0) 0f else -8f))
            }
            if (n > 4) faces.addView(Ui.text(context, "+${n - 4} more", 12.5f, Color.WHITE), DK.llp(DK.WRAP, DK.WRAP, l = 8f))
            faces.visibility = if (n > 0) View.VISIBLE else View.GONE
            cta.text = if (n > 0) "Listen to $n live${if (n > 1) "s" else ""}" else "Open Audio Room"
            contentDescription = "Audio Room: $n live"
        }
    }
}

// ═════════════════════════════ /live?m=audio — every Audio Room live now ═════════════════════════════
/**
 * The Audio Room list in a native app look (owner rule, Nov 17): a white app bar that stays on top,
 * small filter chips, and one big flat card per live (photo, name, room, listeners, Join).
 */
@SuppressLint("ViewConstructor")
class LiveListPage(ctx: Context, private val screen: LiveRoomsScreen) : FrameLayout(ctx) {
    private var chip = "All"
    private val chipViews = ArrayList<TextView>()
    private val sub: TextView
    private val rv = RecyclerView(ctx)
    private val adapter = Adapter()
    private val goLive = liveGoLiveButton(ctx)

    init {
        setBackgroundColor(Ui.c("#f6f5f9"))
        isClickable = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // app bar
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(Ui.dpi(4f), Ui.dpi(6f), Ui.dpi(4f), Ui.dpi(6f))
        }
        bar.addView(DK.iconButton(ctx, DIcon(DIcon.CHEVRON_LEFT, DK.INK, 26f), 44f, null, "Back").apply { setOnClickListener { screen.nav.back() } })
        val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(4f), 0, 0, 0) }
        tcol.addView(DK.t(ctx, "Audio Room", 20f, DK.INK))
        sub = DK.t(ctx, " ", 13f, DK.SUB, false)
        tcol.addView(sub)
        bar.addView(tcol, DK.llp(0, DK.WRAP, 1f))
        bar.addView(DK.iconButton(ctx, DIcon(DIcon.TROPHY, DK.INK, 23f), 44f, null, "Leaderboard").apply { setOnClickListener { screen.nav.go("/live/leaderboard") } })
        col.addView(bar, DK.llp(DK.MATCH, DK.WRAP))
        col.addView(View(ctx).apply { setBackgroundColor(Ui.c("#ece8f3")) }, DK.llp(DK.MATCH, Ui.dpi(1f)))
        // chips
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dpi(16f), Ui.dpi(12f), Ui.dpi(16f), Ui.dpi(4f)) }
        listOf("All", "Chat", "Music", "Friends", "Hindi").forEach { c ->
            val tv = Ui.text(ctx, c, 14f, DK.INK).apply {
                gravity = Gravity.CENTER; tag = c; contentDescription = c
                isClickable = true
                setOnClickListener { if (chip != c) { chip = c; paintChips(); render(); rv.scrollToPosition(0) } }
            }
            chipViews.add(tv)
            chips.addView(tv, DK.llp(DK.WRAP, Ui.dpi(34f), r = 8f))
        }
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(chips) }
        // the list (chips scroll with it)
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, 0, 0, Ui.dpi(110f))
        adapter.chips = hs
        col.addView(rv, DK.llp(DK.MATCH, 0, 1f))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        goLive.setOnClickListener { val m = liveMine(screen.home); if (m != null) open(m) else LiveSheets.goLive(screen) }
        addView(goLive, DK.flp(DK.WRAP, Ui.dpi(54f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 18f))
        paintChips()
        render()
    }

    private fun paintChips() {
        chipViews.forEach { tv ->
            val on = tv.tag == chip
            tv.setTextColor(if (on) Ui.c("#4c1d95") else Ui.c("#3f3a4d"))
            tv.typeface = Ui.bold
            tv.background = GradientDrawable().apply { setColor(if (on) Ui.c("#efe6fd") else Color.WHITE); cornerRadius = Ui.dp(8f); setStroke(Ui.dpi(1f), if (on) Ui.c("#cdb6f7") else Ui.c("#dcd6e6")) }
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(if (on) DIcon(DIcon.CHECK, Ui.c("#4c1d95"), 16f, stroke = 2.6f) else null, null, null, null)
            tv.compoundDrawablePadding = Ui.dpi(5f)
            tv.setPadding(Ui.dpi(if (on) 10f else 14f), 0, Ui.dpi(14f), 0)
            tv.isSelected = on
        }
    }

    private fun open(r: JSONObject) = screen.nav.go("/live/${r.optString("id")}")

    /** the lives, filtered by the chip, the ones you follow first, then by level, then by listeners */
    private fun rooms(): List<JSONObject> = liveAudioRooms(screen.home)
        .filter { r -> chip == "All" || (if (chip == "Hindi") Regex("hindi|hinglish", RegexOption.IGNORE_CASE).containsMatchIn(r.optString("language")) else r.optString("category") == chip) }
        .sortedWith(compareByDescending<JSONObject> { if (it.optBoolean("followed")) 1 else 0 }.thenByDescending { it.optInt("level") }.thenByDescending { it.optLong("viewers") })

    fun render() {
        val h = screen.home
        sub.text = if (h != null) "${liveAudioRooms(h).size} live now" else " "
        val mine = liveMine(h)
        goLive.visibility = if (h != null) View.VISIBLE else View.GONE
        goLive.text = if (mine != null) "Open My Live" else "Go Live"
        goLive.contentDescription = goLive.text
        val items = ArrayList<Item>()
        items.add(Item(T_CHIPS))
        if (mine != null) items.add(Item(T_MINE, mine))
        when {
            h == null && screen.homeErr.isEmpty() -> repeat(4) { items.add(Item(T_SKELETON, key = "s$it")) }
            h == null -> items.add(Item(T_ERROR))
            else -> { val list = rooms(); if (list.isEmpty()) items.add(Item(T_EMPTY)) else list.forEach { items.add(Item(T_ROOM, it)) } }
        }
        adapter.set(items)
    }

    // ───────── list items ─────────
    private class Item(val type: Int, val room: JSONObject? = null, val key: String = "") {
        fun id(): String = when (type) { T_ROOM -> "r" + room?.optString("id"); T_SKELETON -> key; else -> "t$type" }
    }
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()
        var chips: View? = null
        @SuppressLint("NotifyDataSetChanged")
        fun set(list: List<Item>) {
            val old = items
            items = list
            if (old.size == list.size && old.indices.all { old[it].id() == list[it].id() }) notifyItemRangeChanged(0, list.size)
            else notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = items[position].type
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(when (type) {
            T_ROOM -> RoomCard(context)
            T_CHIPS -> FrameLayout(context).apply { layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT) }
            else -> FrameLayout(context)
        }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val it = items[position]
            when (it.type) {
                T_ROOM -> (holder.itemView as RoomCard).bind(it.room!!) { r -> open(r) }
                T_CHIPS -> {
                    val f = holder.itemView as FrameLayout
                    val c = chips ?: return
                    if (c.parent !== f) { (c.parent as? ViewGroup)?.removeView(c); f.removeAllViews(); f.addView(c) }
                }
                else -> bindSimple(holder.itemView as FrameLayout, it)
            }
        }
    }

    private fun bindSimple(f: FrameLayout, it: Item) {
        f.removeAllViews()
        f.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        val ctx = context
        when (it.type) {
            T_MINE -> f.addView(liveMineBanner(ctx, it.room!!) { open(it.room) }, DK.flp(DK.MATCH, DK.WRAP, l = 16f, t = 8f, r = 16f, b = 2f))
            T_SKELETON -> f.addView(View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#efebf5"), Ui.c("#f7f5fa"), Ui.c("#efebf5"))).apply { cornerRadius = Ui.dp(16f) }
            }, DK.flp(DK.MATCH, Ui.dpi(122f), l = 16f, t = 10f, r = 16f))
            T_ERROR -> {
                val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(10f), Ui.dpi(40f), Ui.dpi(10f), Ui.dpi(40f)) }
                c.addView(DK.t(ctx, screen.homeErr, 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER })
                c.addView(DK.lineButton(ctx, "Try again", heightDp = 44f).apply { setPadding(Ui.dpi(26f), 0, Ui.dpi(26f), 0); setOnClickListener { screen.loadHome() } }, DK.llp(DK.WRAP, Ui.dpi(44f), t = 14f))
                f.addView(c, DK.flp(DK.MATCH, DK.WRAP, l = 16f, r = 16f))
            }
            T_EMPTY -> {
                val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(18f), Ui.dpi(36f), Ui.dpi(18f), Ui.dpi(36f)) }
                c.addView(FrameLayout(ctx).apply {
                    background = DK.oval(Ui.c("#f0e7fe"))
                    addView(ImageView(ctx).apply { setImageDrawable(DIcon(LK.HEADPHONES, DK.PURPLE, 36f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
                }, DK.llp(Ui.dpi(78f), Ui.dpi(78f)))
                c.addView(DK.t(ctx, "No audio rooms live ${if (chip != "All") "in $chip " else ""}right now", 17.5f, DK.INK, true, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 14f, b = 4f))
                c.addView(DK.t(ctx, if (screen.home?.optBoolean("can_host") == true) "Start your own room and invite people in." else "Check back soon. New rooms go live all the time.", 14.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
                f.addView(c, DK.flp(DK.MATCH, DK.WRAP))
                c.fadeUpIn()
            }
        }
    }

    companion object {
        const val T_CHIPS = 1; const val T_MINE = 2; const val T_SKELETON = 3; const val T_ERROR = 4; const val T_EMPTY = 5; const val T_ROOM = 6
    }

    /** one live: a big flat white card (photo with LIVE, name ✔, room, "Lv · category · language", listeners, Join) */
    private class RoomCard(ctx: Context) : LinearLayout(ctx) {
        private val photo = CoverView(ctx)
        private val name: TextView
        private val room: TextView
        private val meta: TextView
        private val listen: TextView

        init {
            orientation = HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), 0) }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), Ui.c("#ece8f3")) }
            elevation = Ui.dp(1f)
            setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
            isClickable = true
            Ui.pressable(this, 0.98f)
            val pic = FrameLayout(ctx).apply { setBackgroundColor(Ui.c("#f1ecfa")) }
            DK.roundClip(pic, 14f)
            pic.addView(photo, DK.flp())
            val live = LinearLayout(ctx).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Ui.c("#e11d48")); cornerRadius = Ui.dp(6f) }
                setPadding(Ui.dpi(7f), 0, Ui.dpi(7f), 0)
            }
            live.addView(BlinkDot(ctx, Color.WHITE), DK.llp(Ui.dpi(6f), Ui.dpi(6f), r = 4f))
            live.addView(Ui.text(ctx, "LIVE", 10.5f, Color.WHITE).apply { letterSpacing = 0.04f })
            pic.addView(live, DK.flp(DK.WRAP, Ui.dpi(20f), Gravity.BOTTOM or Gravity.START, l = 6f, b = 6f))
            addView(pic, DK.llp(Ui.dpi(96f), Ui.dpi(96f)))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(Ui.dpi(14f), Ui.dpi(2f), 0, 0) }
            name = LK.nameBadge(ctx, "", 16.5f, Ui.c("#1c1726"), 16f)
            col.addView(name, DK.llp(DK.WRAP, DK.WRAP))
            room = DK.t(ctx, "", 14.5f, Ui.c("#3f3a4d"), false)
            col.addView(room, DK.llp(t = 2f))
            meta = DK.t(ctx, "", 12.5f, Ui.c("#7a7488"), false)
            col.addView(meta, DK.llp(t = 2f))
            col.addView(View(ctx), DK.llp(DK.MATCH, 0, 1f))
            val bottom = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            listen = DK.t(ctx, "", 14f, Ui.c("#3f3a4d"), true).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(LK.HEADPHONES, Ui.c("#3f3a4d"), 16f), null, null, null); compoundDrawablePadding = Ui.dpi(5f)
            }
            bottom.addView(listen, DK.llp(0, DK.WRAP, 1f, r = 8f))
            bottom.addView(Ui.text(ctx, "Join", 14f, Ui.c("#4c1d95")).apply {
                gravity = Gravity.CENTER; background = DK.pill(Ui.c("#efe6fd")); setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0)
            }, DK.llp(DK.WRAP, Ui.dpi(34f)))
            col.addView(bottom, DK.llp(t = 6f))
            addView(col, DK.llp(0, Ui.dpi(96f), 1f))
        }

        fun bind(r: JSONObject, open: (JSONObject) -> Unit) {
            val host = r.optJSONObject("host") ?: JSONObject()
            photo.set(host.optString("photo"))
            // the name is as wide as it can be next to the photo; the ✔ follows it
            name.maxWidth = (resources.displayMetrics.widthPixels - Ui.dpi(32f + 24f + 96f + 14f + 22f)).coerceAtLeast(Ui.dpi(80f))
            name.text = host.optString("name")
            room.text = r.optString("name")
            meta.text = listOf(if (r.optInt("level") > 1) "Lv ${r.optInt("level")}" else "", r.optString("category"), r.optString("language")).filter { it.isNotBlank() }.joinToString(" · ")
            listen.text = "${LK.k(r.optLong("viewers"))} listening"
            contentDescription = "${host.optString("name")} is live: ${r.optString("name")}, ${r.optLong("viewers")} listening"
            setOnClickListener { open(r) }
        }
    }
}

// ═════════════════════════════ /live/leaderboard ═════════════════════════════
/** The hosts' Leaderboard (Leaderboard in LiveRooms.jsx): Weekly / Monthly / All Time, by gifts received. */
@SuppressLint("ViewConstructor")
class LiveBoardPage(ctx: Context, private val screen: LiveRoomsScreen) : FrameLayout(ctx) {
    private var period = "week"
    private var data: JSONObject? = null
    private var err = ""
    private var all = false
    private var seq = 0
    private val tabs = ArrayList<TextView>()
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val sv = ScrollView(ctx)

    init {
        background = LiveBg.page()
        isClickable = true
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Ui.dpi(40f)) }
        sv.addView(col)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(6f), 0) }
        head.addView(DK.iconButton(ctx, DIcon(DIcon.CHEVRON_LEFT, DK.INK, 28f), 44f, null, "Back").apply { setOnClickListener { screen.nav.back() } })
        head.addView(Ui.text(ctx, "Leaderboard", 24f, DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(0, DK.WRAP, 1f))
        head.addView(DK.iconButton(ctx, DIcon(DIcon.INFO, DK.PURPLE, 24f), 44f, null, "How the ranking works").apply { setOnClickListener { LiveSheets.rankInfo(screen) } })
        col.addView(head)
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("week" to "Weekly", "month" to "Monthly", "all" to "All Time").forEach { (k, l) ->
            val t = Ui.text(ctx, l, 15f, DK.INK).apply {
                gravity = Gravity.CENTER; tag = k; contentDescription = l; isClickable = true
                setOnClickListener { if (period != k) { period = k; paintTabs(); load() } }
            }
            tabs.add(t)
            tabRow.addView(t, DK.llp(0, Ui.dpi(42f), 1f, l = if (tabs.size == 1) 0f else 8f))
        }
        col.addView(tabRow, DK.llp(DK.MATCH, DK.WRAP, l = 16f, t = 12f, r = 16f))
        col.addView(body, DK.llp())
        addView(sv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        paintTabs()
    }

    fun onShown() { if (data == null || err.isNotEmpty()) load() }

    private fun paintTabs() {
        tabs.forEach { t ->
            val on = t.tag == period
            t.setTextColor(if (on) Color.WHITE else DK.INK)
            t.background = if (on) DK.grad(DK.GRAD, 21f) else DK.pill(Color.WHITE, DK.LINE, 1.5f)
            t.isSelected = on
        }
    }

    private fun load() {
        val my = ++seq
        data = null; err = ""; all = false
        render()
        LiveApi.call("leaderboard", JSONObject().put("period", period)) { r ->
            if (my != seq) return@call
            if (r.ok) data = r.data else err = r.text("Could not load the leaderboard.")
            render()
        }
    }

    private fun render() {
        body.removeAllViews()
        val ctx = context
        val d = data
        if (d == null && err.isEmpty()) { body.addView(FrameLayout(ctx).apply { addView(DotsSpinner(ctx), DK.flp(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER)) }, DK.llp(DK.MATCH, Ui.dpi(150f))); return }
        if (d == null) { body.addView(DK.t(ctx, err, 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(l = 20f, t = 40f, r = 20f)); return }
        val top = DK.arr(d.optJSONArray("top"))
        if (top.isEmpty()) {
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(28f), Ui.dpi(50f), Ui.dpi(28f), Ui.dpi(20f)) }
            c.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.TROPHY, Ui.c("#c9b3f2"), 46f)) })
            c.addView(DK.t(ctx, "No gifts yet ${when (period) { "week" -> "this week"; "month" -> "this month"; else -> "" }}".trim(), 16f, DK.INK, true, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 10f))
            c.addView(DK.t(ctx, "Hosts appear here when they receive gifts in their live rooms.", 14f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            body.addView(c)
            return
        }
        // the podium: 2 · 1 · 3
        val podium = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            background = object : android.graphics.drawable.Drawable() {
                private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                private val r = android.graphics.RectF()
                override fun draw(c: android.graphics.Canvas) {
                    val b = bounds
                    val rad = Math.max(1f, b.width() * 0.9f)
                    val sh = android.graphics.RadialGradient(0f, 0f, 1f, intArrayOf(Ui.c("#efe2ff"), Ui.c("#faf6ff")), floatArrayOf(0f, 0.7f), android.graphics.Shader.TileMode.CLAMP)
                    sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(rad, Math.max(1f, b.height() * 0.8f)); postTranslate(b.exactCenterX(), b.top + b.height() * 0.1f) })
                    p.shader = sh
                    r.set(b); c.drawRoundRect(r, Ui.dp(26f), Ui.dp(26f), p)
                }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            setPadding(Ui.dpi(6f), Ui.dpi(14f), Ui.dpi(6f), Ui.dpi(18f))
            clipChildren = false; clipToPadding = false
        }
        podium.addView(podiumCol(top.getOrNull(1), 2), DK.llp(0, DK.WRAP, 1f))
        podium.addView(podiumCol(top.getOrNull(0), 1), DK.llp(0, DK.WRAP, 1f, l = 6f, r = 6f))
        podium.addView(podiumCol(top.getOrNull(2), 3), DK.llp(0, DK.WRAP, 1f))
        body.addView(podium, DK.llp(DK.MATCH, DK.WRAP, l = 14f, t = 34f, r = 14f))
        podium.fadeUpIn()
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val me = d.optJSONObject("me")
        if (me != null && me.optInt("rank") > 3) rows.addView(row(me, true), DK.llp(b = 8f))
        top.drop(3).take(if (all) 27 else 7).forEach { rows.addView(row(it, false), DK.llp(b = 4f)) }
        body.addView(rows, DK.llp(DK.MATCH, DK.WRAP, l = 14f, t = 16f, r = 14f))
        if (top.size > 10) {
            body.addView(DK.lineButton(ctx, if (all) "Show Top 10" else "View Top 30 Ranking", stroke = Ui.c("#d9c6f7")).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(LK.CHEVRON_DOWN, DK.PURPLE, 19f).let { if (all) RotatedDrawable(it) else it }, null)
                compoundDrawablePadding = Ui.dpi(8f)
                setOnClickListener { all = !all; render() }
            }, DK.llp(DK.MATCH, Ui.dpi(50f), l = 14f, t = 14f, r = 14f))
        }
    }

    private fun openHost(p: JSONObject) { val id = p.optString("room_id"); if (id.isNotEmpty()) LiveSheets.hostProfile(screen, id, null) }

    private fun podiumCol(p: JSONObject?, place: Int): View {
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
        if (p == null) return col
        val first = place == 1
        col.setPadding(0, if (first) 0 else Ui.dpi(26f), 0, 0)
        col.contentDescription = "Rank $place: ${p.optString("name")}, ${LK.n(p.optLong("value"))} diamonds"
        col.isClickable = true
        col.setOnClickListener { openHost(p) }
        Ui.pressable(col, 0.97f)
        val ring = when (place) { 1 -> Ui.c("#f5b301"); 2 -> Ui.c("#c3c9d6"); else -> Ui.c("#f0a37a") }
        val face = if (first) 96f else 74f
        val box = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        box.addView(AvatarView(ctx, face, ring, if (first) 4f else 3f).apply { set(p.optString("photo")) }, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        if (first) box.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CROWN, Ui.c("#e59a00"), 30f, Ui.c("#ffd54a"))) }, DK.flp(Ui.dpi(30f), Ui.dpi(30f), Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = -24f))
        val badge = Ui.text(ctx, "$place", 14f, if (first) Ui.c("#6b3d00") else Ui.c("#3a3f4a")).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, when (place) {
                1 -> intArrayOf(Ui.c("#ffd75e"), Ui.c("#f0a500")); 2 -> intArrayOf(Ui.c("#eef1f6"), Ui.c("#aeb6c4")); else -> intArrayOf(Ui.c("#ffc3a0"), Ui.c("#e07b45"))
            }).apply { shape = GradientDrawable.OVAL; setStroke(Ui.dpi(2f), Color.WHITE) }
        }
        val faceTotal = Ui.dp(face + (if (first) 8f else 6f))
        box.addView(badge, DK.flp(Ui.dpi(26f), Ui.dpi(26f), Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = (faceTotal - Ui.dp(16f)) / Ui.density))
        col.addView(box, DK.llp(DK.WRAP, DK.WRAP))
        col.addView(LK.nameBadge(ctx, p.optString("name"), if (first) 16f else 14.5f, DK.INK, 15f).apply { maxWidth = Ui.dpi(if (first) 120f else 100f) }, DK.llp(DK.WRAP, DK.WRAP, t = 15f))
        col.addView(LK.levelChip(ctx, p.optInt("level", 1)), DK.llp(DK.WRAP, DK.WRAP, t = 5f))
        col.addView(Ui.text(ctx, LK.n(p.optLong("value")), if (first) 17f else 15f, DK.INK).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(LK.diamond(if (first) 18f else 16f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
        }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
        return col
    }

    private fun row(p: JSONObject, you: Boolean): View {
        val ctx = context
        val f = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = if (you) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#f6efff"), Ui.c("#fbf8ff"))).apply { cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1.5f), Ui.c("#c9a7f7")) }
                else GradientDrawable().apply { setColor(if (p.optInt("rank") % 2 == 1) Color.TRANSPARENT else Ui.c("#f8f4fe")); cornerRadius = Ui.dp(16f) }
            setPadding(Ui.dpi(if (you) 14f else 12f), Ui.dpi(if (you) 14f else 9f), Ui.dpi(if (you) 14f else 12f), Ui.dpi(if (you) 14f else 9f))
            isClickable = true
            contentDescription = "${if (you) "You, " else ""}rank ${p.optInt("rank")}: ${p.optString("name")}"
            setOnClickListener { openHost(p) }
            Ui.pressable(this, 0.98f)
        }
        r.addView(Ui.text(ctx, if (you) "#${p.optInt("rank")}" else "${p.optInt("rank")}", if (you) 19f else 15.5f, if (you) DK.PURPLE else DK.INK).apply { gravity = Gravity.CENTER }, DK.llp(Ui.dpi(if (you) 34f else 26f), DK.WRAP))
        r.addView(AvatarView(ctx, if (you) 46f else 40f).apply { set(p.optString("photo")) }, DK.llp(DK.WRAP, DK.WRAP, l = 11f))
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(11f), 0, Ui.dpi(6f), 0) }
        mid.addView(LK.nameBadge(ctx, p.optString("name"), 15f, DK.INK, 15f).apply { maxWidth = Ui.dpi(150f) }, DK.llp(DK.WRAP, DK.WRAP))
        if (you) mid.addView(LK.levelChip(ctx, p.optInt("level", 1), true), DK.llp(DK.WRAP, DK.WRAP, t = 3f))
        r.addView(mid, DK.llp(0, DK.WRAP, 1f))
        if (!you) r.addView(LK.levelChip(ctx, p.optInt("level", 1), true), DK.llp(DK.WRAP, DK.WRAP, r = 6f))
        r.addView(Ui.text(ctx, LK.n(p.optLong("value")), 15f, DK.INK).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(LK.diamond(16f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
            minWidth = Ui.dpi(92f)
        }, DK.llp(DK.WRAP, DK.WRAP))
        f.addView(r, DK.flp(DK.MATCH, DK.WRAP, t = if (you) 11f else 0f))
        if (you) f.addView(Ui.text(ctx, "You", 11.5f, Color.WHITE).apply { background = DK.grad(DK.GRAD, 99f); setPadding(Ui.dpi(9f), Ui.dpi(2f), Ui.dpi(9f), Ui.dpi(2f)) },
            DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.START, l = 56f))
        return f
    }
}

/** a drawable turned upside down (the chevron of "Show Top 10") */
class RotatedDrawable(private val d: android.graphics.drawable.Drawable) : android.graphics.drawable.Drawable() {
    override fun getIntrinsicWidth() = d.intrinsicWidth
    override fun getIntrinsicHeight() = d.intrinsicHeight
    override fun draw(c: android.graphics.Canvas) {
        d.bounds = bounds
        c.save(); c.rotate(180f, bounds.exactCenterX(), bounds.exactCenterY()); d.draw(c); c.restore()
    }
    override fun setAlpha(alpha: Int) { d.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { d.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
