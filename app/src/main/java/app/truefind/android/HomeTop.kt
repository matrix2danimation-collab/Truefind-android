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
            // 4 … 30: one clean card per creator (owner, 20 Nov: "the lines below, made nicely")
            col.addView(Tf.t(ctx, "Top ${res.rest.first().rank}–${res.rest.last().rank}", 13f, SUB).apply { letterSpacing = 0.02f }, Tf.ll(l = 4f, t = 18f, b = 2f))
            res.rest.forEach { row -> col.addView(rowView(ctx, row), Tf.ll(Tf.M, Tf.W, t = 8f)) }
        }
        if (!res.fromServer) col.addView(Tf.t(ctx, "Couldn't load this week's ranking. Pull down to try again.", 12.5f, SUB, false).apply { gravity = Gravity.CENTER },
            Tf.ll(Tf.M, Tf.W, t = 14f))
    }

    // ── 2 · 1 · 3 ──
    private fun podium(ctx: Context, top: List<TopRank.Row>): View {
        val card = FrameLayout(ctx).apply {
            // the winners' card (owner, 22 Nov: design "02 · Champagne Gold", without the white glow behind the 1st):
            // a champagne-gold gradient with bubbles, sparkles, stars and confetti
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#FFE7A3"), Ui.c("#F6C453"), Ui.c("#D99A1E"))).apply { cornerRadius = Ui.dp(24f) },
                ChampagneDoodles(24f)))
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
        // the 1st's pattern for all three (owner, 20 Nov): a crown over each photo — gold, silver, bronze
        if (first) c.addView(View(ctx).apply { background = SvgDrawable(Svg.of(HomeSheets.CROWN), 28f, 22f, GOLD) }, Tf.ll(Ui.dpi(28f), Ui.dpi(22f), b = 2f))
        else c.addView(View(ctx).apply { background = SvgDrawable(Svg.of(HomeSheets.CROWN), 22f, 17f, ring) }, Tf.ll(Ui.dpi(22f), Ui.dpi(17f), b = 2f))
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
        c.addView(Tf.t(ctx, "${HomeRules.firstName(r.p).ifEmpty { "Creator" }}${if (age != null) ", $age" else ""}", if (first) 15f else 13.5f, Ui.c("#4A2C00")).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }, Tf.ll(Tf.M, Tf.W, t = 6f))
        c.addView(Tf.t(ctx, TopRank.timeText(r.minutes), 12f, Ui.c("#6B4300"), false).apply { gravity = Gravity.CENTER }, Tf.ll(Tf.M, Tf.W, t = 1f))
        // the step it stands on: the middle one is the tallest
        val step = TextView(ctx).apply {
            text = "${r.rank}"; textSize = if (first) 30f else 24f; setTextColor(Ui.c("#C88A12")); typeface = Ui.bold; gravity = Gravity.CENTER
            // cream steps with a white top edge (champagne-gold card)
            background = GradientDrawable().apply {
                setColor(Ui.c(when (k) { 0 -> "#FFFBEA"; 1 -> "#FFF4D2"; else -> "#FFEFC2" }))
                setStroke(Ui.dpi(1.5f), Color.WHITE)
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
        val row = Tf.row(ctx).apply {
            background = Tf.box(Color.WHITE, 18f, 1f, Ui.c("#ECE4FA"))
            elevation = Ui.dp(1.5f)
            setPadding(Ui.dpi(10f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
            setOnClickListener { sheets.person(r.p) }
        }
        // the place: a small purple-tinted circle
        row.addView(Tf.t(ctx, "${r.rank}", 14f, DEEP).apply { gravity = Gravity.CENTER; background = Tf.circle(Ui.c("#F1EAFE"), 1f, Ui.c("#DCCDFB")) },
            Tf.ll(Ui.dpi(30f), Ui.dpi(30f), r = 10f))
        // the photo with a thin purple ring
        val full = HomeRules.photoOf(r.p)
        val ph = FrameLayout(ctx).apply { background = Tf.circle(Ui.c("#B79CF2")); setPadding(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f)) }
        val inner = FrameLayout(ctx).apply { background = Tf.circle(Color.WHITE); setPadding(Ui.dpi(1.5f), Ui.dpi(1.5f), Ui.dpi(1.5f), Ui.dpi(1.5f)) }
        inner.addView(RectPhoto(ctx, Ui.dp(999f)).apply { set(listOf(HomeRules.listPhoto(r.p, full), full)) }, LayoutParams(-1, -1))
        ph.addView(inner, LayoutParams(-1, -1))
        val phWrap = FrameLayout(ctx).apply { clipChildren = false }
        phWrap.addView(ph, LayoutParams(Ui.dpi(48f), Ui.dpi(48f)))
        if (HomeRules.isFree(r.p)) phWrap.addView(View(ctx).apply { background = Tf.circle(Ui.c("#1A9A50"), 2f, Color.WHITE) },
            LayoutParams(Ui.dpi(12f), Ui.dpi(12f), Gravity.END or Gravity.BOTTOM))
        row.addView(phWrap, Tf.ll(Ui.dpi(48f), Ui.dpi(48f), r = 12f))
        // name, age · city
        val t = Tf.col(ctx)
        val age = HomeRules.ageOf(r.p)
        t.addView(Tf.t(ctx, "${HomeRules.firstName(r.p).ifEmpty { "Creator" }}${if (age != null) ", $age" else ""}", 15f, INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val city = Tf.t(ctx, HomeRules.cityOf(r.p).ifEmpty { "India" }, 12.5f, SUB, false).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = Ui.dpi(3f)
            setCompoundDrawablesWithIntrinsicBounds(Tf.lu(Lu.MapPin, Ui.c("#9B8AB8"), 12f, 2.2f), null, null, null)
        }
        t.addView(city, Tf.ll(t = 2f))
        row.addView(t, Tf.ll(0, Tf.W, 1f))
        // minutes this week: a purple pill with a clock
        row.addView(Tf.t(ctx, TopRank.timeText(r.minutes), 12.5f, PURPLE).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Tf.box(Ui.c("#F5F0FF"), 14f, 1f, Ui.c("#E2D5FB"))
            setPadding(Ui.dpi(9f), Ui.dpi(5f), Ui.dpi(10f), Ui.dpi(5f))
            compoundDrawablePadding = Ui.dpi(4f)
            setCompoundDrawablesWithIntrinsicBounds(Tf.lu(Lu.Clock, PURPLE, 13f, 2.2f), null, null, null)
            maxLines = 1
        }, Tf.ll(l = 8f))
        row.contentDescription = "Number ${r.rank}, ${HomeRules.firstName(r.p)}, ${TopRank.timeText(r.minutes)} this week"
        Ui.pressable(row, 0.98f)
        return row
    }
}

/**
 * The Weekly Top card's doodles (owner, 20 Nov: crowns, hearts and rings behind the first three, a clean premium
 * look): a quiet, even pattern — crowns (every fourth one gold), hearts, rings and four-point sparkles — in
 * see-through white over the purple, each a little turned. Drawn inside the card's rounded corners.
 */
class CrownDoodles(private val radiusDp: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val clip = android.graphics.Path()
    private val shape = android.graphics.Path()
    private val box = android.graphics.RectF()
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds; if (b.isEmpty) return
        c.save()
        box.set(b); clip.reset(); clip.addRoundRect(box, Ui.dp(radiusDp), Ui.dp(radiusDp), android.graphics.Path.Direction.CW)
        c.clipPath(clip)
        val cell = Ui.dp(44f)
        val cols = (b.width() / cell).toInt() + 2; val rows = (b.height() / cell).toInt() + 2
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = (i * 7 + j * 13 + i * j * 3) and 0x7fffffff
            // offset every other row, a little jitter inside the cell
            val x = b.left + (i + if (j % 2 == 1) 0.5f else 0f) * cell + ((k % 9) - 4) * Ui.dp(1.2f)
            val y = b.top + (j + 0.5f) * cell + (((k / 9) % 9) - 4) * Ui.dp(1.2f)
            val turn = (((k / 81) % 7) - 3) * 6f
            val size = Ui.dp(11f + (k % 4) * 1.5f)
            c.save(); c.translate(x, y); c.rotate(turn)
            when (k % 5) {
                0, 3 -> crown(c, size, if ((i + j) % 4 == 0) android.graphics.Color.argb(78, 245, 197, 66) else android.graphics.Color.argb(40, 255, 255, 255))
                1 -> heart(c, size * 0.9f, android.graphics.Color.argb(36, 255, 182, 222))
                2 -> ring(c, size * 0.45f, android.graphics.Color.argb(34, 255, 255, 255))
                else -> sparkle(c, size * 0.55f, android.graphics.Color.argb(46, 255, 255, 255))
            }
            c.restore()
        }
        c.restore()
    }
    private fun crown(c: android.graphics.Canvas, s: Float, col: Int) {
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = Ui.dp(1.4f); p.color = col
        val w = s; val h = s * 0.72f
        shape.reset()
        shape.moveTo(-w / 2f, h / 2f); shape.lineTo(-w / 2f, -h / 4f); shape.lineTo(-w / 4f, h / 8f); shape.lineTo(0f, -h / 2f)
        shape.lineTo(w / 4f, h / 8f); shape.lineTo(w / 2f, -h / 4f); shape.lineTo(w / 2f, h / 2f); shape.close()
        c.drawPath(shape, p)
        p.style = android.graphics.Paint.Style.FILL
        c.drawCircle(0f, -h / 2f - Ui.dp(1.5f), Ui.dp(1.4f), p)
        c.drawCircle(-w / 2f, -h / 4f - Ui.dp(1.5f), Ui.dp(1.2f), p)
        c.drawCircle(w / 2f, -h / 4f - Ui.dp(1.5f), Ui.dp(1.2f), p)
    }
    private fun heart(c: android.graphics.Canvas, s: Float, col: Int) {
        p.style = android.graphics.Paint.Style.FILL; p.color = col
        val h = s
        shape.reset()
        shape.moveTo(0f, h * 0.35f)
        shape.cubicTo(-h * 0.55f, -h * 0.05f, -h * 0.35f, -h * 0.55f, 0f, -h * 0.25f)
        shape.cubicTo(h * 0.35f, -h * 0.55f, h * 0.55f, -h * 0.05f, 0f, h * 0.35f)
        shape.close()
        c.drawPath(shape, p)
    }
    private fun ring(c: android.graphics.Canvas, r: Float, col: Int) {
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = Ui.dp(1.4f); p.color = col
        c.drawCircle(0f, 0f, r, p)
    }
    private fun sparkle(c: android.graphics.Canvas, r: Float, col: Int) {
        p.style = android.graphics.Paint.Style.FILL; p.color = col
        val q = r * 0.28f
        shape.reset()
        shape.moveTo(0f, -r); shape.quadTo(q, -q, r, 0f); shape.quadTo(q, q, 0f, r); shape.quadTo(-q, q, -r, 0f); shape.quadTo(-q, -q, 0f, -r); shape.close()
        c.drawPath(shape, p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * The Weekly Top card's doodles, champagne-gold version (owner, 22 Nov, design "02 · Champagne Gold"): an even,
 * quiet pattern of bubbles (rings), four-point sparkles, stars and small confetti strips — white, cream and a
 * deep gold — each a little turned. Drawn inside the card's rounded corners. No glow circle behind the 1st.
 */
class ChampagneDoodles(private val radiusDp: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val clip = android.graphics.Path()
    private val shape = android.graphics.Path()
    private val box = android.graphics.RectF()
    private val white = android.graphics.Color.argb(150, 255, 255, 255)
    private val cream = android.graphics.Color.argb(140, 255, 246, 216)
    private val gold = android.graphics.Color.argb(95, 184, 115, 10)
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds; if (b.isEmpty) return
        c.save()
        box.set(b); clip.reset(); clip.addRoundRect(box, Ui.dp(radiusDp), Ui.dp(radiusDp), android.graphics.Path.Direction.CW)
        c.clipPath(clip)
        val cell = Ui.dp(46f)
        val cols = (b.width() / cell).toInt() + 2; val rows = (b.height() / cell).toInt() + 2
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = (i * 7 + j * 13 + i * j * 3) and 0x7fffffff
            val x = b.left + (i + if (j % 2 == 1) 0.5f else 0f) * cell + ((k % 9) - 4) * Ui.dp(1.4f)
            val y = b.top + (j + 0.5f) * cell + (((k / 9) % 9) - 4) * Ui.dp(1.4f)
            val turn = (((k / 81) % 7) - 3) * 8f
            val size = Ui.dp(10f + (k % 4) * 1.6f)
            val col = when ((i + 2 * j) % 3) { 0 -> white; 1 -> cream; else -> gold }
            c.save(); c.translate(x, y); c.rotate(turn)
            when (k % 5) {
                0, 3 -> bubble(c, size * 0.4f, white)
                1 -> sparkle(c, size * 0.6f, col)
                2 -> star(c, size * 0.55f, col)
                else -> confetti(c, size, col)
            }
            c.restore()
        }
        c.restore()
    }
    private fun bubble(c: android.graphics.Canvas, r: Float, col: Int) {
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = Ui.dp(1.4f); p.color = col
        c.drawCircle(0f, 0f, r, p)
    }
    private fun sparkle(c: android.graphics.Canvas, r: Float, col: Int) {
        p.style = android.graphics.Paint.Style.FILL; p.color = col
        val q = r * 0.28f
        shape.reset()
        shape.moveTo(0f, -r); shape.quadTo(q, -q, r, 0f); shape.quadTo(q, q, 0f, r); shape.quadTo(-q, q, -r, 0f); shape.quadTo(-q, -q, 0f, -r); shape.close()
        c.drawPath(shape, p)
    }
    private fun star(c: android.graphics.Canvas, r: Float, col: Int) {
        p.style = android.graphics.Paint.Style.FILL; p.color = col
        shape.reset()
        for (n in 0 until 10) {
            val a = Math.toRadians(-90.0 + n * 36.0)
            val rr = if (n % 2 == 0) r else r * 0.45f
            val px = (rr * Math.cos(a)).toFloat(); val py = (rr * Math.sin(a)).toFloat()
            if (n == 0) shape.moveTo(px, py) else shape.lineTo(px, py)
        }
        shape.close()
        c.drawPath(shape, p)
    }
    private fun confetti(c: android.graphics.Canvas, s: Float, col: Int) {
        p.style = android.graphics.Paint.Style.FILL; p.color = col
        val w = s * 0.32f; val h = s * 0.85f
        c.drawRoundRect(-w / 2f, -h / 2f, w / 2f, h / 2f, Ui.dp(1.5f), Ui.dp(1.5f), p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
