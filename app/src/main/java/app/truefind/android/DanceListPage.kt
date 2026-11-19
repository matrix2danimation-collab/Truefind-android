package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * The Live Dance list (DanceList in LiveDance.jsx): the 3 big mode boxes, or
 * the lives of one mode in two columns. Asks the server every 10 s while it
 * is on screen, like the web page.
 */
@SuppressLint("ViewConstructor")
class DanceListPage(ctx: Context, private val screen: LiveDanceScreen) : FrameLayout(ctx) {
    // pull down to refresh (owner, 19 Nov evening)
    private lateinit var pull: PagePull
    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean = (::pull.isInitialized && pull.intercept(e)) || super.onInterceptTouchEvent(e)
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean = (::pull.isInitialized && pull.touch(e)) || super.onTouchEvent(e)
    private val main = Handler(Looper.getMainLooper())
    private var home: JSONObject? = null
    private var err = ""
    private var mode = ""
    private var active = false
    private var loading = false
    private val rv = RecyclerView(ctx)
    private val adapter = Adapter()
    private val coinText: TextView
    private val flask: View
    private val goLive: TextView
    private val poll = Runnable { load(); schedule() }

    init {
        background = DK.pageBg()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // ── top bar (TopHeader) ──
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dpi(4f), 0, Ui.dpi(10f), 0)
            background = GradientDrawable().apply { setColor(Ui.ca("#ffffff", 0.9f)) }
        }
        top.addView(DK.iconButton(ctx, DIcon(DIcon.CHEVRON_LEFT, DK.INK, 28f), 44f, null, "Back").apply { setOnClickListener { back() } })
        top.addView(DK.logo(ctx), DK.llp(0, DK.WRAP, 1f, l = 0f))
        flask = DK.iconButton(ctx, DIcon(DIcon.FLASK, Color.WHITE, 15f), 30f, DK.pill(Ui.c("#0f766e")), "Demo mode").apply {
            visibility = View.GONE
            setOnClickListener { DanceSheets.demo(screen, inRoom = false) { load() } }
        }
        top.addView(flask, DK.llp(Ui.dpi(36f), Ui.dpi(30f), r = 6f))
        val (pill, coins) = DK.coinPill(ctx)
        coinText = coins
        pill.contentDescription = "Coins. Add coins"
        pill.setOnClickListener { screen.nav.go("/wallet") }
        top.addView(pill, DK.llp(DK.WRAP, Ui.dpi(34f)))
        col.addView(top, DK.llp(DK.MATCH, Ui.dpi(56f)))
        col.addView(View(ctx).apply { setBackgroundColor(DK.LINE) }, DK.llp(DK.MATCH, Ui.dpi(1f)))
        // ── the list ──
        val glm = GridLayoutManager(ctx, 2)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.items.getOrNull(position)?.type == T_ROOM) 1 else 2
        }
        rv.layoutManager = glm
        rv.adapter = adapter
        rv.clipToPadding = false
        rv.setPadding(Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(120f))
        rv.itemAnimator = null
        rv.setHasFixedSize(true)
        col.addView(rv, DK.llp(DK.MATCH, 0, 1f))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // ── Go Live Dance (approved hosts / creators) ──
        goLive = DK.button(ctx, "Go Live Dance", DIcon(DIcon.RADIO, Color.WHITE, 21f), 56f, 17f).apply {
            setPadding(Ui.dpi(30f), 0, Ui.dpi(30f), 0)
            background = LayerDrawableCompat.goldRing(DK.grad(DK.GRAD, 28f))
            visibility = View.GONE
            setOnClickListener { openGoLive() }
        }
        addView(goLive, DK.flp(DK.WRAP, Ui.dpi(56f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 18f))
        render()
        // (the circle comes down under the 56 dp top bar)
        pull = PagePull(this, topDp = 60f, canPull = { isShown && !rv.canScrollVertically(-1) }) { done -> load(done) }
    }

    fun setMode(m: String, animate: Boolean) {
        if (m == mode && adapter.itemCount > 0) return
        val was = mode
        mode = m
        render()
        rv.scrollToPosition(0)
        if (animate && was != m) {
            val w = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
            rv.translationX = if (m.isNotEmpty()) w * 0.35f else -w * 0.35f
            rv.alpha = 0.4f
            rv.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(DK.EASE).start()
        }
    }

    fun onShown() { if (home == null || System.currentTimeMillis() - loadedAt > 4000) load() }

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        main.removeCallbacks(poll)
        if (on) { load(); schedule() }
    }
    private fun schedule() { main.removeCallbacks(poll); if (active) main.postDelayed(poll, 10_000) }

    private var loadedAt = 0L
    /** pulled down to refresh: told when the list is back */
    private val waiting = ArrayList<() -> Unit>()
    fun load(done: (() -> Unit)? = null) {
        done?.let { waiting.add(it) }
        if (loading) return
        loading = true
        DanceApi.call("home") { r ->
            loading = false
            if (r.ok) { home = r.data; err = ""; loadedAt = System.currentTimeMillis() } else if (home == null) err = r.text("Could not load Live Dance.")
            render()
            val w = ArrayList(waiting); waiting.clear(); w.forEach { it() }
        }
    }

    private fun back() {
        screen.nav.back()
    }

    // ── what the list shows ──
    private fun rooms(): List<JSONObject> = DK.arr(home?.optJSONArray("rooms"))
    private fun mine(): JSONObject? = home?.optJSONObject("my_room")?.takeIf { it.optBoolean("live") }

    /** the "Go Live Dance" sheet; after starting, the host goes into the new live */
    private fun openGoLive() {
        val langs = ArrayList<String>()
        home?.optJSONArray("languages")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { langs.add(it) } }
        DanceSheets.goLive(screen, langs, mode.ifEmpty { "solo" }) { id -> screen.nav.go("/dance/$id") }
    }

    private fun render() {
        val h = home
        coinText.text = if (h == null) "…" else DK.n(h.optLong("balance"))
        flask.visibility = if (h?.optBoolean("is_admin") == true) View.VISIBLE else View.GONE
        goLive.visibility = if (h?.optBoolean("can_host") == true && mine() == null) View.VISIBLE else View.GONE
        val all = rooms()
        val list = ArrayList<Item>()
        list.add(Item(T_TITLE))
        mine()?.let { list.add(Item(T_MINE, it)) }
        if (mode.isEmpty()) {
            DK.MODES.forEach { m -> list.add(Item(T_BOX, mode = m, rooms = all.filter { it.optString("mode") == m })) }
        } else {
            list.add(Item(T_INTRO, mode = mode))
            if (h == null && err.isEmpty()) list.add(Item(T_LOADING))
        }
        if (err.isNotEmpty() && h == null) list.add(Item(T_ERROR))
        if (mode.isNotEmpty() && h != null) {
            val mine = all.filter { it.optString("mode") == mode }
            if (mine.isEmpty()) list.add(Item(T_EMPTY)) else mine.forEach { list.add(Item(T_ROOM, it)) }
        }
        adapter.set(list)
    }

    // ───────── list items ─────────
    private class Item(val type: Int, val room: JSONObject? = null, val mode: String = "", val rooms: List<JSONObject> = emptyList()) {
        fun key(): String = when (type) { T_ROOM -> "r" + room?.optString("id"); T_BOX -> "b$mode"; else -> "t$type" }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()
        fun set(list: List<Item>) {
            val old = items
            items = list
            if (old.size == list.size && old.indices.all { old[it].key() == list[it].key() }) notifyItemRangeChanged(0, list.size)
            else notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = items[position].type
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(when (type) {
            T_ROOM -> RoomCard(context)
            T_BOX -> ModeBox(context)
            else -> FrameLayout(context)
        }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val it = items[position]
            when (it.type) {
                T_ROOM -> (holder.itemView as RoomCard).bind(it.room!!) { id -> screen.nav.go("/dance/$id") }
                T_BOX -> (holder.itemView as ModeBox).bind(it.mode, it.rooms, home == null) { m -> screen.nav.go("/dance?m=$m") }
                else -> bindSimple(holder.itemView as FrameLayout, it)
            }
        }
    }

    private fun bindSimple(f: FrameLayout, it: Item) {
        f.removeAllViews()
        f.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        val ctx = context
        when (it.type) {
            T_TITLE -> {
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(10f), Ui.dpi(2f), Ui.dpi(10f), Ui.dpi(12f)) }
                col.addView(Ui.text(ctx, if (mode.isEmpty()) "Live Dance 💃" else DK.mode(mode).label, 25f, DK.INK).apply { letterSpacing = -0.01f })
                val n = rooms().count { r -> r.optString("mode") == mode }
                col.addView(DK.t(ctx, if (mode.isEmpty()) "Watch, vote and support your favourite dancers." else "$n live now · tap a live to join", 14f, DK.SUB, false).apply { setPadding(0, Ui.dpi(4f), 0, 0) })
                f.addView(col)
            }
            T_MINE -> {
                val r = it.room!!
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = DK.grad(DK.GRAD, 18f); elevation = Ui.dp(6f)
                    setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
                    contentDescription = "Go back to your live"
                    setOnClickListener { screen.nav.go("/dance/${r.optString("id")}") }
                    Ui.pressable(this, 0.97f)
                }
                val av = AvatarView(ctx, 44f, Color.WHITE, 2f).apply { set(r.optJSONObject("host")?.optString("photo")) }
                card.addView(av)
                val txt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), 0, Ui.dpi(8f), 0) }
                txt.addView(DK.t(ctx, "You are live · ${DK.mode(r.optString("mode")).label}", 16f, Color.WHITE))
                txt.addView(DK.t(ctx, "Tap to go back to your live", 13f, Ui.ca("#ffffff", 0.9f), false))
                card.addView(txt, DK.llp(0, DK.WRAP, 1f))
                card.addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CHEVRON_RIGHT, Color.WHITE, 22f)) })
                f.addView(card, DK.flp(DK.MATCH, DK.WRAP, l = 6f, t = 0f, r = 6f, b = 12f))
            }
            T_INTRO -> f.addView(modeIntro(ctx, mode), DK.flp(DK.MATCH, DK.WRAP, l = 6f, r = 6f, b = 6f))
            T_LOADING -> f.addView(DotsSpinner(ctx), DK.flp(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER, t = 40f, b = 40f))
            T_ERROR -> {
                val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; danceCard(); setPadding(Ui.dpi(20f), Ui.dpi(20f), Ui.dpi(20f), Ui.dpi(20f)) }
                card.addView(DK.t(ctx, err, 15f, DK.ERR, true, 0).apply { gravity = Gravity.CENTER })
                card.addView(DK.button(ctx, "Try again", null, 44f, 15f).apply { setOnClickListener { load() } }, DK.llp(DK.WRAP, Ui.dpi(44f), t = 12f))
                f.addView(card, DK.flp(DK.MATCH, DK.WRAP, l = 6f, t = 14f, r = 6f))
            }
            T_EMPTY -> {
                val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; danceCard(); setPadding(Ui.dpi(18f), Ui.dpi(26f), Ui.dpi(18f), Ui.dpi(26f)) }
                card.addView(Ui.text(ctx, "🪩", 46f, DK.INK, false))
                card.addView(DK.t(ctx, "No ${DK.mode(mode).label} live right now", 17f, DK.INK, true, 0).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(6f), 0, 0) })
                card.addView(DK.t(ctx, if (home?.optBoolean("can_host") == true) "Be the first one — start your dance live." else "Check again in a little while.", 14f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(6f), 0, 0) })
                f.addView(card, DK.flp(DK.MATCH, DK.WRAP, l = 6f, t = 14f, r = 6f))
            }
        }
    }

    companion object {
        const val T_TITLE = 1; const val T_MINE = 2; const val T_BOX = 3; const val T_INTRO = 4
        const val T_LOADING = 5; const val T_ERROR = 6; const val T_ROOM = 7; const val T_EMPTY = 8

        /** ModeIntro: the small card on top of a mode's list */
        fun modeIntro(ctx: Context, mode: String): View {
            val m = DK.mode(mode)
            val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; danceCard(); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(10f)) }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(13f) }
                addView(DK.modeIcon(ctx, mode, 20f, DK.PURPLE), DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER))
            }, DK.llp(Ui.dpi(40f), Ui.dpi(40f)))
            val txt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(10f), 0, Ui.dpi(8f), 0) }
            txt.addView(DK.t(ctx, m.label, 15.5f, DK.INK))
            txt.addView(DK.t(ctx, m.line, 13f, DK.SUB, false, 2))
            row.addView(txt, DK.llp(0, DK.WRAP, 1f))
            row.addView(Ui.text(ctx, "${m.fee}", 13f, Ui.c("#92400e")).apply {
                gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.TICKET, Ui.c("#92400e"), 15f), null, null, null)
                compoundDrawablePadding = Ui.dpi(4f)
                background = GradientDrawable().apply { setColor(Ui.c("#fff7e6")); cornerRadius = Ui.dp(12f); setStroke(Ui.dpi(1f), Ui.c("#f7d58a")) }
                setPadding(Ui.dpi(9f), Ui.dpi(6f), Ui.dpi(9f), Ui.dpi(6f))
            })
            card.addView(row)
            card.addView(DK.steps(ctx, if (mode == "solo") DK.soloSteps() else DK.battleSteps()), DK.llp(t = 10f))
            return card
        }
    }

    /** ModeBox: one big box per mode */
    private class ModeBox(ctx: Context) : LinearLayout(ctx) {
        private val head = FrameLayout(ctx)
        private val liveTv: TextView
        private val liveDot = BlinkDot(ctx, Color.WHITE)
        private val livePill = LinearLayout(ctx)
        private val watch: TextView
        private val fee: TextView
        private val icon = FrameLayout(ctx)
        private val title: TextView
        private val line: TextView
        private val faces = LinearLayout(ctx)
        private val stepsBox = FrameLayout(ctx)
        private val cta: TextView
        private var mode = ""

        init {
            orientation = VERTICAL
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dpi(6f), 0, Ui.dpi(6f), Ui.dpi(16f)) }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(24f); setStroke(Ui.dpi(1f), DK.LINE) }
            elevation = Ui.dp(6f)
            outlineSpotShadowColorCompat(Ui.c("#5a0fc2"))
            DK.roundClip(this, 24f)
            isClickable = true
            Ui.pressable(this, 0.98f)
            addView(head, DK.llp(DK.MATCH, Ui.dpi(178f)))
            head.addView(BeamView(ctx, Ui.ca("#ffffff", 0.22f), 0).apply { rotation = 18f; translationX = Ui.dp(52f); translationY = -Ui.dp(40f) },
                DK.flp(Ui.dpi(120f), Ui.dpi(260f), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            // top line: LIVE · watching · fee
            val topRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            livePill.orientation = HORIZONTAL; livePill.gravity = Gravity.CENTER_VERTICAL
            livePill.setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
            livePill.addView(liveDot, DK.llp(Ui.dpi(7f), Ui.dpi(7f), r = 5f))
            liveTv = Ui.text(ctx, "…", 12.5f, Color.WHITE).apply { letterSpacing = 0.03f }
            livePill.addView(liveTv)
            topRow.addView(livePill, DK.llp(DK.WRAP, Ui.dpi(24f)))
            watch = Ui.text(ctx, "", 12.5f, Color.WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = DK.pill(Ui.ca("#140528", 0.35f))
                setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.EYE, Color.WHITE, 14f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
            }
            topRow.addView(watch, DK.llp(DK.WRAP, Ui.dpi(24f), l = 6f))
            topRow.addView(View(ctx), DK.llp(0, 1, 1f))
            fee = Ui.text(ctx, "", 13f, Ui.c("#92400e")).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = DK.pill(Ui.c("#fff7e6"))
                setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(15f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
            }
            topRow.addView(fee, DK.llp(DK.WRAP, Ui.dpi(26f)))
            head.addView(topRow, DK.flp(DK.MATCH, DK.WRAP, Gravity.TOP, 14f, 14f, 14f))
            // icon circle bottom-right
            icon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ca("#ffffff", 0.14f)); setStroke(Ui.dpi(1f), Ui.ca("#ffffff", 0.25f)) }
            head.addView(icon, DK.flp(Ui.dpi(92f), Ui.dpi(92f), Gravity.BOTTOM or Gravity.END, r = 14f, b = 12f))
            // title, line, faces bottom-left
            val txt = LinearLayout(ctx).apply { orientation = VERTICAL }
            title = Ui.text(ctx, "", 26f, Color.WHITE).apply { letterSpacing = -0.01f }
            line = DK.t(ctx, "", 13.5f, Ui.ca("#ffffff", 0.92f), false, 2).apply { setPadding(0, Ui.dpi(4f), 0, 0) }
            faces.orientation = HORIZONTAL; faces.gravity = Gravity.CENTER_VERTICAL
            txt.addView(title); txt.addView(line); txt.addView(faces, DK.llp(DK.WRAP, DK.WRAP, t = 10f))
            head.addView(txt, DK.flp(DK.MATCH, DK.WRAP, Gravity.BOTTOM, 16f, 0f, 120f, 16f))
            addView(stepsBox, DK.llp(DK.MATCH, DK.WRAP, l = 12f, t = 12f, r = 12f, b = 10f))
            cta = DK.button(ctx, "", null, 46f, 15.5f).apply {
                background = DK.grad(DK.GRAD, 14f); elevation = 0f; isClickable = false
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.CHEVRON_RIGHT, Color.WHITE, 19f), null); compoundDrawablePadding = Ui.dpi(6f)
            }
            addView(cta, DK.llp(DK.MATCH, Ui.dpi(46f), l = 12f, r = 12f, b = 12f))
        }

        fun bind(m: String, rooms: List<JSONObject>, loading: Boolean, open: (String) -> Unit) {
            val info = DK.mode(m)
            if (mode != m) {
                mode = m
                head.background = DK.boxBg(m)
                icon.removeAllViews()
                val iv: View = if (m == "solo") Ui.text(context, "💃", 50f, Color.WHITE, false).apply { gravity = Gravity.CENTER }
                else ImageView(context).apply { setImageDrawable(DIcon(if (m == "battle") DIcon.SWORDS else DIcon.USERS, if (m == "battle") DK.GOLD_TEXT else Color.WHITE, 46f)); scaleType = ImageView.ScaleType.CENTER }
                icon.addView(iv, DK.flp(DK.MATCH, DK.MATCH))
                title.text = info.label; line.text = info.line; fee.text = "${info.fee}"
                stepsBox.removeAllViews()
                stepsBox.addView(DK.steps(context, if (m == "solo") DK.soloSteps() else DK.battleSteps()))
            }
            val n = rooms.size
            liveTv.text = if (loading) "…" else if (n > 0) "$n LIVE" else "NO LIVE"
            liveDot.visibility = if (n > 0) View.VISIBLE else View.GONE
            livePill.background = if (n > 0) DK.grad(intArrayOf(Ui.c("#ff3b5c"), Ui.c("#e11d48")), 99f) else DK.pill(Ui.ca("#ffffff", 0.22f))
            val watching = rooms.sumOf { it.optLong("viewers") }
            watch.text = DK.c(watching); watch.visibility = if (watching > 0) View.VISIBLE else View.GONE
            faces.removeAllViews()
            rooms.take(4).forEachIndexed { i, r ->
                val av = AvatarView(context, 30f, Color.WHITE, 2f).apply { set(r.optJSONObject("host")?.optString("photo")) }
                faces.addView(av, DK.llp(DK.WRAP, DK.WRAP, l = if (i == 0) 0f else -8f))
            }
            if (n > 4) faces.addView(Ui.text(context, "+${n - 4} more", 12.5f, Color.WHITE), DK.llp(DK.WRAP, DK.WRAP, l = 8f))
            faces.visibility = if (n > 0) View.VISIBLE else View.GONE
            cta.text = if (n > 0) "Watch $n live${if (n > 1) "s" else ""}" else "Open ${info.short}"
            contentDescription = "${info.label}: $n live"
            setOnClickListener { open(m) }
        }
    }

    /** RoomCard: one live in the 2-column list */
    private class RoomCard(ctx: Context) : FrameLayout(ctx) {
        private val photo = CoverView(ctx)
        private val left = SlantFrame(ctx, true)
        private val right = SlantFrame(ctx, false)
        private val pa = CoverView(ctx)
        private val pb = CoverView(ctx)
        private val vs: TextView
        private val viewers: TextView
        private val demo: TextView
        private val battleOn: TextView
        private val title: TextView
        private val host: TextView
        private val av = AvatarView(ctx, 22f, Ui.ca("#ffffff", 0.7f), 1.5f)
        private val fee: TextView

        init {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(6f)) }
            background = DK.stageBg()
            DK.roundClip(this, 20f)
            elevation = Ui.dp(5f)
            outlineSpotShadowColorCompat(Ui.c("#3c0a8c"))
            isClickable = true
            Ui.pressable(this, 0.97f)
            addView(photo, DK.flp())
            left.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#6d28d9"), Ui.c("#3b0f74")))
            right.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#db2777"), Ui.c("#5b0f74")))
            left.addView(pa, DK.flp()); right.addView(pb, DK.flp())
            addView(left, LayoutParams(0, 0)); addView(right, LayoutParams(0, 0))
            vs = Ui.text(ctx, "VS", 30f, DK.GOLD_TEXT).apply {
                typeface = DK.italic(); gravity = Gravity.CENTER
                setShadowLayer(Ui.dp(7f), 0f, Ui.dp(2f), Ui.c("#b45309"))
            }
            addView(vs, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            vs.vsWobble()
            addView(ShadeView(ctx, intArrayOf(Ui.ca("#140528", 0.35f), Color.TRANSPARENT, Color.TRANSPARENT, Ui.ca("#140528", 0.88f)), floatArrayOf(0f, 0.3f, 0.5f, 1f)), DK.flp())
            val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val (live, v) = DK.livePill(ctx, small = true)
            viewers = v
            topRow.addView(live)
            topRow.addView(View(ctx), DK.llp(0, 1, 1f))
            demo = DK.demoTag(ctx)
            topRow.addView(demo, DK.llp(DK.WRAP, Ui.dpi(20f)))
            addView(topRow, DK.flp(DK.MATCH, DK.WRAP, Gravity.TOP, 8f, 8f, 8f))
            val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            battleOn = Ui.text(ctx, "Battle on", 11f, Color.WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = DK.pill(Ui.ca("#ff3b5c", 0.9f))
                setPadding(Ui.dpi(8f), Ui.dpi(2f), Ui.dpi(8f), Ui.dpi(2f))
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.SWORDS, Color.WHITE, 12f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
            }
            bottom.addView(battleOn, DK.llp(DK.WRAP, DK.WRAP, b = 5f))
            title = DK.t(ctx, "", 15f, Color.WHITE)
            bottom.addView(title)
            val hostRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            hostRow.addView(av)
            host = DK.t(ctx, "", 12.5f, Ui.ca("#ffffff", 0.95f), false)
            hostRow.addView(host, DK.llp(0, DK.WRAP, 1f, l = 6f, r = 4f))
            fee = Ui.text(ctx, "", 12f, Ui.c("#ffe1a1")).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(14f), null, null, null); compoundDrawablePadding = Ui.dpi(3f)
            }
            hostRow.addView(fee)
            bottom.addView(hostRow, DK.llp(t = 5f))
            addView(bottom, DK.flp(DK.MATCH, DK.WRAP, Gravity.BOTTOM, 10f, 0f, 10f, 10f))
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val height = (width * 4.1f / 3f).toInt()
            // the two halves of a running battle: 50 % each (RoomCard in the web)
            (left.layoutParams as LayoutParams).apply { this.width = width / 2; this.height = height; leftMargin = 0 }
            (right.layoutParams as LayoutParams).apply { this.width = width - width / 2; this.height = height; leftMargin = width / 2 }
            (vs.layoutParams as LayoutParams).topMargin = (height * 0.4f).toInt()
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }

        fun bind(r: JSONObject, open: (String) -> Unit) {
            val hostO = r.optJSONObject("host")
            val b = r.optJSONObject("battle")
            val running = b?.optString("status") == "running"
            val split = r.optString("mode") != "solo" && running
            photo.visibility = if (split) View.GONE else View.VISIBLE
            left.visibility = if (split) View.VISIBLE else View.GONE
            right.visibility = left.visibility
            vs.visibility = left.visibility
            if (split) { pa.set(b?.optJSONObject("a")?.optString("photo")); pb.set(b?.optJSONObject("b")?.optString("photo")) }
            else photo.set(hostO?.optString("photo"))
            viewers.text = DK.c(r.optLong("viewers"))
            demo.visibility = if (r.optBoolean("demo")) View.VISIBLE else View.GONE
            battleOn.visibility = if (running) View.VISIBLE else View.GONE
            title.text = r.optString("title")
            host.text = hostO?.optString("name").orEmpty()
            av.set(hostO?.optString("photo"))
            fee.text = "${r.optInt("fee")}"
            contentDescription = "${r.optString("title")} by ${hostO?.optString("name").orEmpty()}"
            setOnClickListener { open(r.optString("id")) }
        }

    }
}

/** a gold ring around a button (the Go Live / Join buttons' box-shadow) */
object LayerDrawableCompat {
    fun goldRing(inner: GradientDrawable): android.graphics.drawable.Drawable {
        val ring = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = Ui.dp(999f); setStroke(Ui.dpi(2f), Ui.ca("#ffd666", 0.85f)) }
        return android.graphics.drawable.LayerDrawable(arrayOf(inner, ring))
    }
}
