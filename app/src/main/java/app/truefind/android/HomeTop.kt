package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What the Weekly Top Creators page shows, from the server's answer (weeklyTop) — or, when that can't be had
 * (an older server, no connection), from Home's own list in its order. Kept apart from the drawing so it can
 * be checked on its own.
 */
object TopRank {
    class Row(val p: JSONObject, val rank: Int, val minutes: Int)
    class Result(val top3: List<Row>, val rest: List<Row>, val myRank: Int?, val myMinutes: Int, val among: String, val fromServer: Boolean)

    const val MAX = 30

    fun build(server: JSONObject?, local: List<JSONObject>): Result {
        val list = server?.optJSONArray("top")
        if (server != null && server.optBoolean("ok") && list != null) {
            val rows = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.take(MAX)
                .mapIndexed { i, p -> Row(p, p.optInt("week_rank", i + 1).takeIf { it > 0 } ?: (i + 1), p.optInt("week_talk_minutes", 0)) }
                .sortedBy { it.rank }
            val me = server.optJSONObject("me")
            val rank = me?.let { if (it.has("rank") && !it.isNull("rank")) it.optInt("rank").takeIf { r -> r > 0 } else null }
            return Result(rows.take(3), rows.drop(3), rank, me?.optInt("minutes", 0) ?: 0, me?.optString("among").orEmpty(), true)
        }
        // no answer from the server: Home's list as it is (its own order), no rank of yours
        val rows = local.take(MAX).mapIndexed { i, p -> Row(p, i + 1, HomeRules.weekMins(p).roundToInt()) }
        return Result(rows.take(3), rows.drop(3), null, 0, "", false)
    }

    /** "2 h 10 min" · "45 min" · "0 min" */
    fun timeText(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val h = m / 60; val r = m % 60
        return when { h > 0 && r > 0 -> "$h h $r min"; h > 0 -> "$h h"; else -> "$r min" }
    }
    fun amongText(among: String): String = when (among) { "creators" -> "among the creators"; "boys" -> "among the boys"; else -> "" }
}

/**
 * Home → Weekly Top Creators → See all (owner, 19 Nov evening): a page of its own.
 * The first three on top — 1 in the middle, raised, 2 and 3 on its sides — then "Your rank" (whatever it is,
 * 100 or 200), then 4 to 30 in a list. Ranked by minutes talked in True Find calls over the last 7 days.
 * Tap anyone → their Voice / Video sheet. Pull down to refresh.
 */
@SuppressLint("ViewConstructor")
class TopCreatorsPage(ctx: Context, private val home: HomeScreen, private val sheets: HomeSheets, private val close: () -> Unit) : FrameLayout(ctx) {
    private val PURPLE = Ui.c("#5B21B6"); private val DEEP = Ui.c("#3B0F86"); private val INK = Ui.c("#1F1235"); private val SUB = Ui.c("#6B6478")
    private val GOLD = Ui.c("#F5B301"); private val SILVER = Ui.c("#A7AFBD"); private val BRONZE = Ui.c("#C2763A")
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
    private val col = Tf.col(ctx)
    private var data: JSONObject? = null
    private var failed = false
    private var seq = 0
    private val pull: PagePull

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#F4EEFF"), Color.WHITE, Color.WHITE))
        isClickable = true
        val page = Tf.col(ctx)
        // header: back · title
        val head = Tf.row(ctx).apply { setPadding(Ui.dpi(8f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(6f)) }
        head.addView(ImageView(ctx).apply {
            setImageDrawable(Tf.lu(Lu.ChevronLeft, INK, 26f, 2.4f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back"
            background = Tf.circle(Color.WHITE, 1f, Ui.c("#E9DFF8"))
            setOnClickListener { close() }
        }.also { Ui.pressable(it, 0.95f) }, Tf.ll(Ui.dpi(42f), Ui.dpi(42f), r = 10f))
        val tcol = Tf.col(ctx)
        tcol.addView(Tf.t(ctx, "Weekly Top Creators", 19f, INK))
        tcol.addView(Tf.t(ctx, "Most talk time on calls · last 7 days", 12.5f, SUB, false), Tf.ll(t = 1f))
        head.addView(tcol, Tf.ll(0, Tf.W, 1f))
        page.addView(head, Tf.ll(Tf.M, Tf.W))
        col.setPadding(Ui.dpi(16f), Ui.dpi(6f), Ui.dpi(16f), Ui.dpi(28f))
        scroll.addView(col, LayoutParams(-1, -2))
        page.addView(scroll, Tf.ll(Tf.M, 0, 1f))
        addView(page, LayoutParams(-1, -1))
        pull = PagePull(this, topDp = 58f, canPull = { isShown && !scroll.canScrollVertically(-1) }) { done -> load(done) }
        render()
        load(null)
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = pull.intercept(e) || super.onInterceptTouchEvent(e)
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = pull.touch(e) || super.onTouchEvent(e)

    private fun load(done: (() -> Unit)?) {
        val my = ++seq
        Api.invoke("weeklyTop", JSONObject()) { r ->
            done?.invoke()
            if (my != seq) return@invoke
            if (r.ok && r.data.optBoolean("ok")) { data = r.data; failed = false } else if (data == null) failed = true
            render()
        }
    }

    private fun render() {
        val ctx = context
        col.removeAllViews()
        if (data == null && !failed) {
            col.addView(TfLoader(ctx, 28f, PURPLE), Tf.ll(Ui.dpi(28f), Ui.dpi(28f), t = 80f, g = Gravity.CENTER_HORIZONTAL))
            return
        }
        val res = TopRank.build(data, home.creators())
        if (res.top3.isEmpty()) {
            col.addView(Tf.t(ctx, "No one has talked on calls this week yet. The list fills up as calls happen.", 14f, SUB, false).apply { gravity = Gravity.CENTER },
                Tf.ll(Tf.M, Tf.W, t = 60f))
            return
        }
        col.addView(podium(ctx, res.top3), Tf.ll(Tf.M, Tf.W))
        // "Your rank" sits right above number 4
        if (res.fromServer) col.addView(yourRank(ctx, res), Tf.ll(Tf.M, Tf.W, t = 16f))
        if (res.rest.isNotEmpty()) {
            val list = Tf.col(ctx).apply { background = Tf.box(Color.WHITE, 18f, 1f, Ui.c("#EEE9F5")); setPadding(0, Ui.dpi(4f), 0, Ui.dpi(4f)) }
            res.rest.forEachIndexed { i, row ->
                if (i > 0) list.addView(View(ctx).apply { setBackgroundColor(Ui.c("#F1ECF8")) }, Tf.ll(Tf.M, Ui.dpi(1f), l = 64f))
                list.addView(rowView(ctx, row), Tf.ll(Tf.M, Tf.W))
            }
            col.addView(list, Tf.ll(Tf.M, Tf.W, t = 12f))
        }
        if (!res.fromServer) col.addView(Tf.t(ctx, "Couldn't load this week's ranking. Pull down to try again.", 12.5f, SUB, false).apply { gravity = Gravity.CENTER },
            Tf.ll(Tf.M, Tf.W, t = 14f))
    }

    // ── 2 · 1 · 3 ──
    private fun podium(ctx: Context, top: List<TopRank.Row>): View {
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#6C2BD9"), DEEP)).apply { cornerRadius = Ui.dp(24f) }
            elevation = Ui.dp(6f)
        }
        val row = Tf.row(ctx, Gravity.BOTTOM).apply { setPadding(Ui.dpi(10f), Ui.dpi(18f), Ui.dpi(10f), 0) }
        val order = listOf(1, 0, 2)          // 2nd on the left, 1st in the middle, 3rd on the right
        for (k in order) {
            val r = top.getOrNull(k)
            // an empty place (fewer than 3 this week) takes no height: a plain View sized "wrap" would take ALL the
            // height there is and stretch the podium down the whole page (owner, 20 Nov photo)
            row.addView(if (r == null) View(ctx) else place(ctx, r, k), Tf.ll(0, if (r == null) 0 else Tf.W, if (k == 0) 1.25f else 1f))
        }
        card.addView(row, LayoutParams(-1, -2))
        return card
    }
    private fun place(ctx: Context, r: TopRank.Row, k: Int): View {
        val first = k == 0
        val ring = when (k) { 0 -> GOLD; 1 -> SILVER; else -> BRONZE }
        val size = if (first) 86f else 66f
        val c = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply { setOnClickListener { sheets.person(r.p) }; contentDescription = "Number ${r.rank}, ${HomeRules.firstName(r.p)}" }
        if (first) c.addView(View(ctx).apply { background = SvgDrawable(Svg.of(HomeSheets.CROWN), 28f, 22f, GOLD) }, Tf.ll(Ui.dpi(28f), Ui.dpi(22f), b = 2f))
        val ph = FrameLayout(ctx).apply { background = Tf.circle(ring); setPadding(Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f)) }
        val inner = FrameLayout(ctx).apply { background = Tf.circle(Color.WHITE); setPadding(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f)) }
        val full = HomeRules.photoOf(r.p)
        inner.addView(RectPhoto(ctx, Ui.dp(999f)).apply { set(listOf(HomeRules.listPhoto(r.p, full), full)) }, LayoutParams(-1, -1))
        ph.addView(inner, LayoutParams(-1, -1))
        val holder = FrameLayout(ctx).apply { clipChildren = false }
        holder.addView(ph, LayoutParams(Ui.dpi(size), Ui.dpi(size), Gravity.CENTER_HORIZONTAL))
        holder.addView(Tf.t(ctx, "${r.rank}", 12f, if (k == 0) Ui.c("#3B2500") else Color.WHITE).apply {
            gravity = Gravity.CENTER; background = Tf.circle(ring, 2f, Color.WHITE)
        }, LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(size - 12f) })
        c.addView(holder, Tf.ll(Tf.W, Ui.dpi(size + 12f)))
        val age = HomeRules.ageOf(r.p)
        c.addView(Tf.t(ctx, "${HomeRules.firstName(r.p).ifEmpty { "Creator" }}${if (age != null) ", $age" else ""}", if (first) 15f else 13.5f, Color.WHITE).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }, Tf.ll(Tf.M, Tf.W, t = 6f))
        c.addView(Tf.t(ctx, TopRank.timeText(r.minutes), 12f, Ui.c("#E4D9F7"), false).apply { gravity = Gravity.CENTER }, Tf.ll(Tf.M, Tf.W, t = 1f))
        // the step it stands on: the middle one is the tallest
        val step = TextView(ctx).apply {
            text = "${r.rank}"; textSize = if (first) 30f else 24f; setTextColor(Color.argb(150, 255, 255, 255)); typeface = Ui.bold; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(if (first) 70 else 48, 255, 255, 255), Color.argb(10, 255, 255, 255))).apply {
                cornerRadii = floatArrayOf(Ui.dp(14f), Ui.dp(14f), Ui.dp(14f), Ui.dp(14f), 0f, 0f, 0f, 0f)
            }
        }
        c.addView(step, Tf.ll(Tf.M, Ui.dpi(when (k) { 0 -> 74f; 1 -> 54f; else -> 42f }), l = 6f, t = 10f, r = 6f))
        Ui.pressable(c, 0.97f)
        return c
    }

    // ── Your rank ──
    private fun yourRank(ctx: Context, res: TopRank.Result): View {
        val box = Tf.row(ctx).apply {
            background = Tf.box(Ui.c("#F5F0FF"), 18f, 1.5f, Ui.c("#C9B3F5"))
            setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
        }
        val raws = home.myPhotoRaws()
        val ph = FrameLayout(ctx).apply { background = Tf.circle(PURPLE); setPadding(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f)) }
        ph.addView(RectPhoto(ctx, Ui.dp(999f)).apply { set(raws) }, LayoutParams(-1, -1))
        box.addView(ph, Tf.ll(Ui.dpi(46f), Ui.dpi(46f), r = 12f))
        val t = Tf.col(ctx)
        t.addView(Tf.t(ctx, "Your rank", 13f, SUB, false))
        t.addView(Tf.t(ctx, TopRank.timeText(res.myMinutes) + " this week" + TopRank.amongText(res.among).let { if (it.isEmpty()) "" else " · $it" }, 12.5f, PURPLE, false).apply { maxLines = 2 }, Tf.ll(t = 2f))
        box.addView(t, Tf.ll(0, Tf.W, 1f))
        box.addView(Tf.t(ctx, res.myRank?.let { "#$it" } ?: "—", 26f, DEEP), Tf.ll(l = 8f))
        box.contentDescription = "Your rank " + (res.myRank?.toString() ?: "not known")
        return box
    }

    // ── 4 … 30 ──
    private fun rowView(ctx: Context, r: TopRank.Row): View {
        val row = Tf.row(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)); setOnClickListener { sheets.person(r.p) } }
        row.addView(Tf.t(ctx, "${r.rank}", 14f, PURPLE).apply { gravity = Gravity.CENTER }, Tf.ll(Ui.dpi(26f), Tf.W, r = 8f))
        val full = HomeRules.photoOf(r.p)
        row.addView(RectPhoto(ctx, Ui.dp(999f)).apply { set(listOf(HomeRules.listPhoto(r.p, full), full)) }, Tf.ll(Ui.dpi(44f), Ui.dpi(44f), r = 12f))
        val t = Tf.col(ctx)
        val age = HomeRules.ageOf(r.p)
        t.addView(Tf.t(ctx, "${HomeRules.firstName(r.p).ifEmpty { "Creator" }}${if (age != null) ", $age" else ""}", 14.5f, INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        t.addView(Tf.t(ctx, HomeRules.cityOf(r.p).ifEmpty { "India" }, 12f, SUB, false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, Tf.ll(t = 1f))
        row.addView(t, Tf.ll(0, Tf.W, 1f))
        if (HomeRules.isFree(r.p)) row.addView(View(ctx).apply { background = Tf.circle(Ui.c("#1A9A50"), 2f, Color.WHITE) }, Tf.ll(Ui.dpi(10f), Ui.dpi(10f), r = 8f))
        row.addView(Tf.t(ctx, TopRank.timeText(r.minutes), 13f, PURPLE).apply { gravity = Gravity.END }, Tf.ll(l = 6f))
        row.contentDescription = "Number ${r.rank}, ${HomeRules.firstName(r.p)}, ${TopRank.timeText(r.minutes)} this week"
        Ui.pressable(row, 0.98f)
        return row
    }
}
