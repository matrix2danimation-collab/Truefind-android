package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * One screen of Expert Talk (the web keeps them as `nav.screen` inside one page): a scrolling
 * column (header + content, like .scr) and, when the screen has one, the white bar at the bottom
 * (.sticky). Pop-ups open on the app's pop-up layer.
 */
@SuppressLint("ViewConstructor")
abstract class EtPage(val et: ExpertTalkScreen, bg: Int = Et.PAGE) : FrameLayout(et.context) {
    val ctx: Context get() = context
    protected val main = Handler(Looper.getMainLooper())
    val scroll = ScrollView(et.context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; isFillViewport = true }
    val body: LinearLayout = etCol(et.context)
    private val frame = etCol(et.context)
    private var stickyBar: View? = null
    private val timers = ArrayList<Runnable>()
    private var sheetClose: (() -> Unit)? = null
    var disposed = false; private set

    init {
        setBackgroundColor(bg)
        isClickable = true
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        frame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(frame, LayoutParams(-1, -1))
    }

    /** where the screen sits in the page order (a lower one slides in from the left) */
    open val rank: Int = 0
    /** builds the screen (again) from the current data */
    abstract fun render()
    /** every second while it shows */
    open fun tick() {}
    /** the phone's Back (after pop-ups): true when the screen handled it */
    open fun back(): Boolean = false
    /** Expert Talk's first answer ("home") changed */
    open fun homeChanged() {}
    open fun shown(on: Boolean) {}

    fun dispose() {
        disposed = true
        for (t in timers) main.removeCallbacks(t)
        timers.clear()
        closeSheet()
        onDispose()
    }
    open fun onDispose() {}

    /** runs [f] every [ms] (first after [ms]) until the screen is gone */
    fun every(ms: Long, f: () -> Unit) {
        val r = object : Runnable { override fun run() { if (disposed) return; f(); main.postDelayed(this, ms) } }
        timers.add(r)
        main.postDelayed(r, ms)
    }
    fun later(ms: Long, f: () -> Unit) {
        val r = Runnable { if (!disposed) f() }
        timers.add(r); main.postDelayed(r, ms)
    }

    /** rebuild the column, keeping where it was scrolled */
    fun rebuild(fill: (LinearLayout) -> Unit) {
        val y = scroll.scrollY
        body.removeAllViews()
        fill(body)
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    /** the white bar at the bottom (.sticky): padding 12 16 16, gap 8, line on top */
    fun setSticky(v: View?) {
        stickyBar?.let { frame.removeView(it) }
        stickyBar = null
        if (v == null) return
        val bar = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            elevation = Ui.dp(6f); outlineSpotShadowColorCompat(Color.argb(40, 48, 22, 120))
            addView(View(ctx).apply { setBackgroundColor(Et.LINE) }, LayoutParams(-1, Ui.dpi(1f), Gravity.TOP))
            addView(v, LayoutParams(-1, -2).apply { setMargins(Ui.dpi(16f), Ui.dpi(12f), Ui.dpi(16f), Ui.dpi(16f)) })
        }
        frame.addView(bar, LinearLayout.LayoutParams(-1, -2))
        stickyBar = bar
    }

    // ── pop-ups ──
    /** a bottom sheet (.sheet): white, round 26 on top, handle, padding 10 18 20, gap 12 */
    fun sheet(build: (LinearLayout, () -> Unit) -> Unit) {
        closeSheet()
        val panel = etCol(ctx, 12f).apply {
            background = Tf.topRound(Color.WHITE, 26f)
            setPadding(Ui.dpi(18f), Ui.dpi(10f), Ui.dpi(18f), Ui.dpi(20f))
            isClickable = true
        }
        panel.put(View(ctx).apply { background = Tf.box(Ui.c("#DDD6EA"), 3f) }, 42f, 5f, g = Gravity.CENTER_HORIZONTAL)
        var close: () -> Unit = {}
        val doClose: () -> Unit = { close() }
        build(panel, doClose)
        close = et.pages.layer.sheet(panel, dim = Color.argb(128, 17, 12, 30), maxFrac = 0.94f, onClose = { if (sheetClose != null) sheetClose = null })
        sheetClose = close
    }
    fun closeSheet() { val c = sheetClose; sheetClose = null; c?.invoke() }
    val sheetOpen: Boolean get() = sheetClose != null

    fun toast(s: String) = et.toast.show(s)
}

/** The pieces of etKit.jsx and the CSS classes, as small builders. */
class EtUi(val ctx: Context) {

    /** .ib — the 40 square button with an icon (pm: lighter line + soft shadow) */
    fun ib(inner: String, iconSize: Float = 20f, pm: Boolean = false, dark: Boolean = false, label: String = "", onClick: () -> Unit): FrameLayout {
        val s = if (dark) (if (pm) 52f else 48f) else 40f
        return FrameLayout(ctx).apply {
            background = when {
                dark && pm -> Tf.box(Et.DEEP, 16f)
                dark -> Tf.box(Et.INK, 14f)
                else -> Tf.box(Color.WHITE, 12f, 1f, if (pm) Et.PM_LINE else Et.LINE)
            }
            if (pm) etShadow(2f)
            addView(etIcon(ctx, inner, iconSize, if (dark) Color.WHITE else Et.INK), FrameLayout.LayoutParams(Ui.dpi(iconSize), Ui.dpi(iconSize), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(Ui.dpi(s), Ui.dpi(s))
            contentDescription = label
            setOnClickListener { onClick() }
            Ui.pressable(this, .96f)
        }
    }

    /** Hdr of etKit: back arrow, title, something on the right */
    fun hdr(title: String, pm: Boolean = false, onBack: (() -> Unit)?, right: View? = null): LinearLayout {
        val r = etRow(ctx, 10f)
        if (pm) r.setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(10f)) else r.setPadding(Ui.dpi(16f), Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(8f))
        if (onBack != null) r.put(ib(EtIcon.ArrowLeft, 19f, pm, label = "Back") { onBack() }, 40f, 40f)
        r.put(etT(ctx, title, if (pm) 21f else 19f, Et.INK, true).etEll(), 0f, -2f, 1f)
        if (right != null) r.put(right)
        return r
    }

    /** small purple "Step 1 of 3" */
    fun stepText(s: String): TextView = etT(ctx, s, 12.5f, Et.PTEXT, true)

    /** .stack: padding 6 16 22 with a gap */
    fun stack(gap: Float = 18f): LinearLayout = etCol(ctx, gap).apply { setPadding(Ui.dpi(16f), Ui.dpi(6f), Ui.dpi(16f), Ui.dpi(22f)) }

    /** .card (white, line, round) */
    fun card(gap: Float = 0f, pm: Boolean = false, shadow: Boolean = false, pad: FloatArray? = null, row: Boolean = false): LinearLayout =
        (if (row) etRow(ctx, gap) else etCol(ctx, gap)).apply {
            background = etCardBg(pm)
            if (shadow) etShadow(if (pm) 3f else 2f)
            if (pad != null) setPadding(Ui.dpi(pad[3]), Ui.dpi(pad[0]), Ui.dpi(pad[1]), Ui.dpi(pad[2]))
        }

    /** .card.empty: centred small grey text (pm: dashed line, lighter page) */
    fun empty(text: String, pm: Boolean = false): View = etT(ctx, text, 12.5f, Et.MUT).apply {
        gravity = Gravity.CENTER
        background = if (pm) Tf.dashedBox(Ui.c("#FBFAFE"), 20f, 1f, Et.PM_LINE, 4f, 3f) else etCardBg()
        setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f))
    }

    /** h3 (pm: 18 px with the purple bar in front) */
    fun h3(text: String, pm: Boolean = false, after: View? = null): LinearLayout {
        val r = etRow(ctx, 8f)
        if (pm) r.put(View(ctx).apply { background = Tf.box(Et.P, 2f) }, 4f, 18f)
        r.put(etT(ctx, text, if (pm) 18f else 16.5f, Et.INK, true))
        if (after != null) r.put(after)
        return r
    }

    /** .st: a heading with something at its right (baseline) */
    fun st(left: View, right: View?): LinearLayout {
        val r = etRow(ctx, 8f, Gravity.CENTER_VERTICAL)
        r.put(left, 0f, -2f, 1f)
        if (right != null) r.put(right)
        return r
    }

    /** .lnk (pm: a light purple pill) */
    fun lnk(text: String, pm: Boolean = false, red: Boolean = false, onClick: () -> Unit): TextView = etT(ctx, text, 13.5f, if (red) Et.RED_TEXT else Et.PTEXT, true, 1f).apply {
        gravity = Gravity.CENTER
        if (pm) { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(12.5f)); background = Tf.box(Et.PM_TINT, 14f); setPadding(Ui.dpi(11f), 0, Ui.dpi(11f), 0); height = Ui.dpi(28f) }
        else setPadding(0, Ui.dpi(4f), 0, Ui.dpi(4f))
        setOnClickListener { onClick() }
    }

    /** .chip (on: purple line + light purple; dark: deep purple filled; solid: purple filled) */
    fun chip(text: String, on: Boolean, kind: String = "", pm: Boolean = false, lead: View? = null, trail: String? = null, onClick: () -> Unit): LinearLayout {
        val h = if (pm) 36f else 34f
        val (bg, fg, line) = when {
            on && kind == "dark" -> Triple(if (pm) Et.DEEP else Et.INK, Color.WHITE, if (pm) Et.DEEP else Et.INK)
            on && kind == "solid" -> Triple(Et.P, Color.WHITE, Et.P)
            on -> Triple(Et.TINT, Et.PTEXT, Et.P)
            else -> Triple(Color.WHITE, Et.INK, if (pm) Et.PM_LINE else Et.FIELD_LINE)
        }
        return etRow(ctx, 6f, Gravity.CENTER).apply {
            background = Tf.box(bg, h / 2f, 1.5f, line)
            setPadding(Ui.dpi(if (pm) 14f else 13f), 0, Ui.dpi(if (pm) 14f else 13f), 0)
            if (lead != null) put(lead)
            put(etT(ctx, text, 13.5f, fg, true, 1f))
            if (trail != null) put(etIcon(ctx, trail, 12f, fg), 12f, 12f)
            minimumHeight = Ui.dpi(h)
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(h))
            isSelected = on
            contentDescription = text
            setOnClickListener { onClick() }
            Ui.pressable(this, .97f)
        }
    }

    /** chips that wrap onto more lines (.chips: gap 8) */
    fun chips(views: List<View>, gap: Float = 8f): EtFlow = EtFlow(ctx, Ui.dpi(gap), Ui.dpi(gap)).apply { views.forEach { addView(it) } }

    /** .sq.okc check circle */
    fun okc(): FrameLayout = FrameLayout(ctx).apply {
        background = Tf.circle(Ui.c("#E6F5EC"))
        addView(etIcon(ctx, EtIcon.Check, 13f, Ui.c("#157F44")), FrameLayout.LayoutParams(Ui.dpi(13f), Ui.dpi(13f), Gravity.CENTER))
    }

    /** SheetHead: purple square icon, title 19, small grey line, ✕ */
    fun sheetHead(icon: String?, title: String, sub: String?, onClose: (() -> Unit)?): LinearLayout {
        val r = etRow(ctx, 10f)
        if (icon != null) r.put(etSq(ctx, icon, 20f, Color.WHITE, Et.P), 40f, 40f)
        val c = etCol(ctx)
        c.put(etT(ctx, title, 19f, Et.INK, true))
        if (!sub.isNullOrEmpty()) c.put(etT(ctx, sub, 12.5f, Et.MUT))
        r.put(c, 0f, -2f, 1f)
        if (onClose != null) r.put(ib(EtIcon.X, 16f, label = "Close") { onClose() }, 40f, 40f)
        return r
    }

    /** .kv2: label left, value right, a line under */
    fun kv2(k: View, v: View, last: Boolean = false): LinearLayout = etRow(ctx, 10f).apply {
        setPadding(0, Ui.dpi(10f), 0, Ui.dpi(10f))
        put(k, 0f, -2f, 1f)
        put(v)
        if (!last) background = BottomLine(Et.SOFT_LINE)
    }
    fun kv2(k: String, v: String, last: Boolean = false) = kv2(etT(ctx, k, 12.5f, Et.MUT), etT(ctx, v, 12.5f, Et.INK, true), last)

    /** a label with a small icon in front (row gap 6) */
    fun iconLabel(inner: String, text: String, size: Float = 12.5f, color: Int = Et.MUT, iconSize: Float = 14f, iconColor: Int = color, bold: Boolean = false, gap: Float = 6f): LinearLayout =
        etRow(ctx, gap).apply { put(etIcon(ctx, inner, iconSize, iconColor), iconSize, iconSize); put(etT(ctx, text, size, color, bold)) }

    /** the loader in the middle (Spinner of etKit) */
    fun spinner(): View = FrameLayout(ctx).apply {
        setPadding(Ui.dpi(40f), Ui.dpi(40f), Ui.dpi(40f), Ui.dpi(40f))
        addView(TfLoader(ctx, 30f), FrameLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER))
    }

    /** ErrorBox of etKit */
    fun errorBox(text: String, detail: String, onRetry: (() -> Unit)?): View {
        val c = etCol(ctx, 10f, Gravity.CENTER_HORIZONTAL).apply { background = etCardBg(); setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f)) }
        c.put(etT(ctx, text, 12.5f).apply { gravity = Gravity.CENTER })
        if (detail.isNotEmpty()) c.put(etT(ctx, detail, 11.5f, Et.MUT).apply { gravity = Gravity.CENTER })
        if (onRetry != null) c.put(EtBtn(ctx, "Try again", "s2", sm = true).apply { setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0); setOnClickListener { onRetry() } }, -2f, -2f)
        return FrameLayout(ctx).apply { addView(c, FrameLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f)) }) }
    }

    /** a row of text items with icons (the checks of "How it works" and the sheets) */
    fun bullet(inner: String, text: CharSequence, color: Int = Et.P, textColor: Int = Et.BODY, iconSize: Float = 15f, top: Boolean = true): LinearLayout =
        etRow(ctx, 10f, if (top) Gravity.TOP else Gravity.CENTER_VERTICAL).apply {
            put(etIcon(ctx, inner, iconSize, color), iconSize, iconSize, t = if (top) 1f else 0f)
            put(etT(ctx, text, 12.5f, textColor), 0f, -2f, 1f)
        }

    /** horizontal scroller (.hs: margin 0 -16, padding 2 16 6, gap 12) */
    fun hs(gap: Float = 12f): Pair<HorizontalScrollView, LinearLayout> {
        val inner = etRow(ctx, gap, Gravity.TOP).apply { setPadding(Ui.dpi(16f), Ui.dpi(2f), Ui.dpi(16f), Ui.dpi(6f)) }
        val sv = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false; addView(inner, FrameLayout.LayoutParams(-2, -2)) }
        return sv to inner
    }
}

/** a line along the bottom of a row (border-bottom: 1px) */
class BottomLine(private val color: Int, private val px: Int = Ui.dpi(1f).coerceAtLeast(1)) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint().apply { this.color = this@BottomLine.color }
    override fun draw(c: android.graphics.Canvas) { val b = bounds; c.drawRect(b.left.toFloat(), (b.bottom - px).toFloat(), b.right.toFloat(), b.bottom.toFloat(), p) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** a line along the top of a row (border-top: 1px) */
class TopLine(private val color: Int, private val px: Int = Ui.dpi(1f).coerceAtLeast(1)) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint().apply { this.color = this@TopLine.color }
    override fun draw(c: android.graphics.Canvas) { val b = bounds; c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), (b.top + px).toFloat(), p) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** flex-wrap: wrap — children in rows, [hGap] between them, [vGap] between rows */
class EtFlow(ctx: Context, private val hGap: Int, private val vGap: Int, private val center: Boolean = false) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0; var used = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams
            c.measure(childSpec(lp.width, maxW), childSpec(lp.height, Int.MAX_VALUE / 2))
            if (x > 0 && x + c.measuredWidth > maxW) { y += rowH + vGap; x = 0; rowH = 0 }
            x += c.measuredWidth + hGap
            used = maxOf(used, x - hGap)
            rowH = maxOf(rowH, c.measuredHeight)
        }
        val h = y + rowH + paddingTop + paddingBottom
        val w = if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY || MeasureSpec.getMode(wSpec) == MeasureSpec.AT_MOST) MeasureSpec.getSize(wSpec) else used + paddingLeft + paddingRight
        setMeasuredDimension(w, resolveSize(h, hSpec))
    }
    private fun childSpec(size: Int, max: Int): Int = when {
        size >= 0 -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        size == LayoutParams.MATCH_PARENT -> MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY)
        else -> MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = paddingTop; var rowH = 0
        val row = ArrayList<View>()
        fun flush() {
            val w = row.sumOf { it.measuredWidth } + hGap * (row.size - 1).coerceAtLeast(0)
            var cx = paddingLeft + if (center) (maxW - w) / 2 else 0
            for (c in row) { val cy = y + (rowH - c.measuredHeight) / 2; c.layout(cx, cy, cx + c.measuredWidth, cy + c.measuredHeight); cx += c.measuredWidth + hGap }
            row.clear()
        }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { flush(); y += rowH + vGap; x = 0; rowH = 0 }
            row.add(c); x += c.measuredWidth + hGap; rowH = maxOf(rowH, c.measuredHeight)
        }
        flush()
    }
    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

/** a grid of [cols] equal columns (.g2 / .g3), [gap] between cells */
class EtGrid(ctx: Context, private val cols: Int, private val gap: Int) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val cw = ((w - paddingLeft - paddingRight - gap * (cols - 1)) / cols).coerceAtLeast(0)
        var h = 0; var rowH = 0
        val vis = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        vis.forEachIndexed { i, c ->
            val lp = c.layoutParams
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), if (lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            rowH = maxOf(rowH, c.measuredHeight)
            if (i % cols == cols - 1 || i == vis.size - 1) { h += rowH + (if (h > 0) gap else 0); rowH = 0 }
        }
        // rows of the same height: stretch every cell to its row's tallest
        var start = 0
        while (start < vis.size) {
            val end = minOf(start + cols, vis.size)
            val tall = (start until end).maxOf { vis[it].measuredHeight }
            for (k in start until end) if (vis[k].measuredHeight != tall) vis[k].measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tall, MeasureSpec.EXACTLY))
            start = end
        }
        setMeasuredDimension(w, h + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = ((r - l - paddingLeft - paddingRight - gap * (cols - 1)) / cols).coerceAtLeast(0)
        var y = paddingTop; var rowH = 0
        val vis = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        vis.forEachIndexed { i, c ->
            val col = i % cols
            val x = paddingLeft + col * (cw + gap)
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            rowH = maxOf(rowH, c.measuredHeight)
            if (col == cols - 1) { y += rowH + gap; rowH = 0 }
        }
    }
    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}

@Suppress("unused") private fun keepUi(o: JSONObject) = o
