package app.truefind.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

/**
 * Reconnect's look from the owner's picture (23 Nov): a blush page, maroon buttons, black words,
 * flat ("2D") buttons — no gloss, no heavy shadows. One place for its colours, its heading font and
 * the change of the earlier pinks into this look.
 */
object Rs {
    /** maroon: buttons, the open tab, my own chat bubbles */
    val M = Ui.c("#931A39")
    val M_DARK = Ui.c("#7A1230")
    /** big titles ("Chats") */
    val M_INK = Ui.c("#521420")
    /** line icons and the words on white chips */
    val ICON = Ui.c("#74122A")
    val LOGO = Ui.c("#7D0F28")
    val INK = Ui.c("#161214")
    val SUB = Ui.c("#6A6061")
    val SOFT = Ui.c("#93888A")
    val TIME = Ui.c("#8A4350")
    /** the page: blush, a little lighter at the bottom */
    val BG_TOP = Ui.c("#FCE7E6")
    val BG_END = Ui.c("#FDF1EF")
    val LINE = Ui.c("#F1D8D7")
    val CHIP = Ui.c("#FFFAF8")
    val TINT = Ui.c("#FBDEDD")
    val DECO = Ui.c("#F1C5C7")
    /** the verified seal stays bright pink, as in the picture */
    val SEAL = Ui.c("#F0127A")
    val RED = Ui.c("#F03A4C")

    /** headings: the phone's own serif, bold (like the picture's "Stories", "Chats") */
    val serif: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    val serifItalic: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC) }

    // ── the earlier pinks → this look (same lightness order: dark pink → deep maroon, pale pink → blush) ──
    private val cache = HashMap<Int, Int>()
    private val LX = floatArrayOf(0f, .27f, .31f, .46f, .59f, .67f, .75f, .85f, .94f, 1f)
    private val LY = floatArrayOf(0f, .21f, .25f, .34f, .46f, .58f, .70f, .86f, .945f, 1f)
    private fun curve(l: Float): Float {
        for (i in 1 until LX.size) if (l <= LX[i]) { val t = (l - LX[i - 1]) / (LX[i] - LX[i - 1]); return LY[i - 1] + (LY[i] - LY[i - 1]) * t }
        return 1f
    }
    /** a pink (magenta … rose) becomes its maroon / blush; reds, greens, golds, greys and whites stay as they are */
    fun map(col: Int): Int {
        cache[col]?.let { return it }
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(col or (0xFF shl 24), hsl)
        val h = hsl[0]; val s = hsl[1]; val l = hsl[2]
        val out = if (h < 300f || h > 342f || s < 0.2f) col else {
            val nl = curve(l)
            val nh = if (nl <= 0.6f) 346f else 346f + (minOf(nl, 0.92f) - 0.6f) / 0.32f * 18f
            val ns = when {
                nl <= 0.4f -> 0.70f
                nl <= 0.62f -> 0.70f - (nl - 0.4f) / 0.22f * 0.22f
                nl <= 0.85f -> 0.48f + (nl - 0.62f) / 0.23f * 0.07f
                else -> 0.55f + (nl - 0.85f) / 0.15f * 0.20f
            } * minOf(1f, s / 0.6f)
            val m = ColorUtils.HSLToColor(floatArrayOf(nh % 360f, ns, nl))
            Color.argb(Color.alpha(col), Color.red(m), Color.green(m), Color.blue(m))
        }
        cache[col] = out
        return out
    }
    fun c(hex: String): Int = map(Color.parseColor(hex))
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = map(Color.argb(a, r, g, b))

    // ── small pieces the pages share ──
    fun flat(color: Int, radiusDp: Float) = GradientDrawable().apply { setColor(color); cornerRadius = Ui.dp(radiusDp) }

    /** a heading in the serif: "Stories", "Chats", "My Activity" */
    fun title(ctx: Context, s: CharSequence, sizeDp: Float, color: Int = INK): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(sizeDp))
        setTextColor(color)
        typeface = serif
        includeFontPadding = false
    }

    /** a section's heading as in the picture: a short maroon bar, then the title */
    fun section(ctx: Context, s: String, sizeDp: Float = 20f): LinearLayout = hbox(ctx, 9f).apply {
        addView(View(ctx).apply { background = flat(M, 2f) }, lp(dpi(3.5f), dpi(sizeDp * .86f)))
        addView(title(ctx, s, sizeDp))
    }

    /** "TrueFind" with its heart over the end of "Find" — the logo at the top of Home, Chats and the map (see [LogoView]) */
    fun logo(ctx: Context, sizeDp: Float = 25f): LogoView = LogoView(ctx, sizeDp)

    /** a chip of a row of choices (Discover · Nearby · Favorite, All · Online · Unread): maroon when chosen, white otherwise */
    fun paintChip(t: TextView, on: Boolean, icon: String?, fillOn: Boolean = true) {
        t.background = if (on) flat(M, 99f) else GradientDrawable().apply { setColor(CHIP); cornerRadius = Ui.dp(99f); setStroke(Ui.dpi(1f), LINE) }
        t.setTextColor(if (on) Color.WHITE else ICON)
        if (icon != null) {
            val col = if (on) Color.WHITE else ICON
            t.setCompoundDrawablesWithIntrinsicBounds(Icon(icon, col, 16f, 2.1f, if (on && fillOn) col else null), null, null, null)
            t.compoundDrawablePadding = Ui.dpi(6f)
        }
    }
}

/** fine line hearts and two leafy twigs on the blush page (the picture's backdrop), drawn very lightly */
internal class RoseDeco {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Rs.DECO }
    private val heart: Path = PathParser.createPathFromPathData(Lucide.Heart)
    // x, y as parts of the page; size in dp; turn in degrees
    private val hearts = arrayOf(
        floatArrayOf(.88f, .215f, 26f, 12f), floatArrayOf(.05f, .345f, 13f, -12f), floatArrayOf(.80f, .455f, 17f, -8f), floatArrayOf(.09f, .585f, 20f, 10f),
        floatArrayOf(.92f, .665f, 13f, 16f), floatArrayOf(.52f, .80f, 16f, -6f), floatArrayOf(.16f, .87f, 12f, 8f), floatArrayOf(.70f, .925f, 22f, 6f),
    )
    private val twig = Path()
    init {
        // a twig, 100 high: a bent stem with five pairs of leaves (drawn once, placed twice)
        twig.moveTo(0f, 0f); twig.quadTo(14f, -52f, 2f, -100f)
        for (i in 0 until 5) {
            val t = .16f + i * .17f
            val x = 2f * (1 - t) * t * 14f + t * t * 2f; val y = 2f * (1 - t) * t * -52f + t * t * -100f
            for (side in intArrayOf(-1, 1)) {
                val len = 22f - i * 2.2f
                val tx = x + side * len; val ty = y - len * .62f
                twig.moveTo(x, y); twig.quadTo(x + side * len * .25f, y - len * .78f, tx, ty); twig.quadTo(x + side * len * .78f, y - len * .05f, x, y)
            }
        }
    }
    // the chat pages' extra doodles, in the same fine line: hearts, stars, sparkles, speech bubbles, little flowers,
    // envelopes and dots, scattered in a loose grid (each cell 92 dp, its doodle moved and turned a little)
    private val star: Path = PathParser.createPathFromPathData(Lucide.Star)
    private val bubble: Path = PathParser.createPathFromPathData("M21 11.5a8.4 8.4 0 0 1 -9 8.4 9 9 0 0 1 -3.8 -.8L3 21l1.9 -5.1A8.4 8.4 0 0 1 3 11.5 8.5 8.5 0 0 1 12 3a8.5 8.5 0 0 1 9 8.5z M12 15.2c-2.6 -1.8 -3.9 -3.1 -3.9 -4.5a1.9 1.9 0 0 1 3.9 -.4 1.9 1.9 0 0 1 3.9 .4c0 1.4 -1.3 2.7 -3.9 4.5z")
    private val sparkle: Path = PathParser.createPathFromPathData("M12 3c.6 4.2 2.8 6.4 7 7 -4.2 .6 -6.4 2.8 -7 7 -.6 -4.2 -2.8 -6.4 -7 -7 4.2 -.6 6.4 -2.8 7 -7z")
    private val flower: Path = PathParser.createPathFromPathData("M12 9.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 1 0 0 -4.8z M12 9.6c-1.8 -2.6 -1.2 -5.6 0 -6.6 1.2 1 1.8 4 0 6.6z M14.3 11.3c1.4 -2.9 4.3 -3.7 5.8 -3.1 -.3 1.6 -2.6 3.6 -5.8 3.1z M13.4 14.1c3.1 .7 4.4 3.4 4.1 5 -1.6 .2 -4 -1.5 -4.1 -5z M10.6 14.1c-.1 3.5 -2.5 5.2 -4.1 5 -.3 -1.6 1 -4.3 4.1 -5z M9.7 11.3c-3.2 .5 -5.5 -1.5 -5.8 -3.1 1.5 -.6 4.4 .2 5.8 3.1z")
    private val envelope: Path = PathParser.createPathFromPathData("M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V7a1 1 0 0 1 1 -1z M3.5 7l8.5 6 8.5 -6")
    private val ring: Path = Path().apply { addCircle(12f, 12f, 3.2f, Path.Direction.CW) }
    fun drawDense(c: Canvas, w: Float, h: Float) {
        val shapes = arrayOf(heart, star, bubble, sparkle, flower, heart, envelope, ring, sparkle)
        val cell = Ui.dp(92f)
        val cols = (w / cell).toInt() + 2; val rows = (h / cell).toInt() + 2
        p.alpha = 190
        for (r in 0 until rows) for (k in 0 until cols) {
            val n = r * 7 + k * 3 + r * k
            val sh = shapes[((n % shapes.size) + shapes.size) % shapes.size]
            // a little off the grid, every other row half a cell along
            val x = (k + (if (r % 2 == 1) .5f else 0f)) * cell + ((n * 37) % 29 - 14) * Ui.density
            val y = r * cell + ((n * 53) % 31 - 15) * Ui.density + cell * .35f
            val size = (12f + (n * 11) % 9) * (if (sh === ring) .7f else 1f)
            val s = Ui.dp(size) / 24f
            p.strokeWidth = Ui.dp(1.1f) / s
            c.save(); c.translate(x, y); c.rotate(((n * 47) % 50 - 25).toFloat()); c.scale(s, s); c.translate(-12f, -12f); c.drawPath(sh, p); c.restore()
        }
        p.alpha = 255
    }
    fun draw(c: Canvas, w: Float, h: Float) {
        p.alpha = 255
        for (hh in hearts) {
            val s = Ui.dp(hh[2]) / 24f
            p.strokeWidth = Ui.dp(1.2f) / s
            c.save(); c.translate(w * hh[0], h * hh[1]); c.rotate(hh[3]); c.scale(s, s); c.translate(-12f, -12f); c.drawPath(heart, p); c.restore()
        }
        val k = Ui.dp(1.25f)
        p.strokeWidth = Ui.dp(1.2f) / k
        c.save(); c.translate(w - Ui.dp(26f), Ui.dp(150f)); c.rotate(28f); c.scale(k, k); c.drawPath(twig, p); c.restore()
        c.save(); c.translate(Ui.dp(14f), h * .78f); c.rotate(-24f); c.scale(k, k); c.drawPath(twig, p); c.restore()
    }
}

/**
 * The logo: "TrueFind" in the serif italic, and the dot of its "i" is a little heart that beats and glows in changing
 * colours (owner, 23 Nov evening: "आई के डॉट पे हार्ट आना चाहिए और वो हार्ट ब्लिंक होना चाहिए … सिर्फ उस हार्ट में") —
 * only the heart moves, never the word. It always shows whole: where its row has less room than the word needs at
 * [maxDp] (a narrow phone, a long credits pill beside it), the whole logo is drawn smaller, never cut.
 */
@android.annotation.SuppressLint("ViewConstructor")
class LogoView(ctx: Context, private val maxDp: Float) : View(ctx) {
    private val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Rs.LOGO; typeface = Rs.serifItalic }
    private val fillP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val glowP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val edgeP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; color = Rs.LOGO; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val heart: android.graphics.Path = androidx.core.graphics.PathParser.createPathFromPathData(Lucide.Heart)
    private var px = Ui.dp(maxDp)
    private var g: Glyphs? = null
    private var t = 0f
    private val beat = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private var seen = false
    /** the text size it is drawn at now (dp): [maxDp], or less where the row is too narrow for that */
    val sizeDp: Float get() = px / Ui.density

    init { contentDescription = "TrueFind" }

    /**
     * The word at text size [size], drawn as paths: "TrueF", the "i" without its dot, "nd" — and where the dot was (its
     * middle, and the heart's size), all from the baseline at x 0.
     */
    private class Glyphs(val size: Float, val word: android.graphics.Path, val inkRight: Float, val cap: Float, val below: Float,
                         val hx: Float, val hBottom: Float, val hs: Float)
    private fun glyphs(size: Float): Glyphs {
        g?.let { if (it.size == size) return it }
        tp.textSize = size
        val b = android.graphics.Rect()
        tp.getTextBounds(WORD, 0, WORD.length, b)
        val cap = -b.top.toFloat()
        val xb = android.graphics.Rect(); tp.getTextBounds("x", 0, 1, xb)
        val xTop = xb.top.toFloat()
        val x1 = tp.measureText("TrueF"); val x2 = tp.measureText("TrueFi")
        // the "i": its dot is the part above the x-height
        val iPath = android.graphics.Path(); tp.getTextPath("i", 0, 1, x1, 0f, iPath)
        val above = android.graphics.Path().apply { addRect(x1 - size, -size * 2, x1 + size, xTop - cap * .04f, android.graphics.Path.Direction.CW) }
        val dot = android.graphics.Path(); dot.op(iPath, above, android.graphics.Path.Op.INTERSECT)
        val stem = android.graphics.Path(); stem.op(iPath, above, android.graphics.Path.Op.DIFFERENCE)
        val db = android.graphics.RectF(); dot.computeBounds(db, true)
        val word = android.graphics.Path()
        tp.getTextPath("TrueF", 0, 5, 0f, 0f, word)
        word.addPath(stem)
        val nd = android.graphics.Path(); tp.getTextPath("nd", 0, 2, x2, 0f, nd); word.addPath(nd)
        val hs = cap * HEART
        val hx = if (db.isEmpty) x1 + tp.measureText("i") * .62f else db.centerX() + hs * .06f
        val hBottom = if (db.isEmpty) xTop - cap * .1f else db.bottom + hs * .02f
        return Glyphs(size, word, maxOf(b.right.toFloat(), tp.measureText(WORD)), cap, maxOf(0f, b.bottom.toFloat()), hx, hBottom, hs).also { g = it }
    }
    /** how far the heart (with its glow, at its biggest) reaches over the tall letters' top */
    private fun over(k: Glyphs) = maxOf(0f, -(k.hBottom - k.hs * 1.18f) - k.cap)
    private fun width(k: Glyphs) = maxOf(k.inkRight, k.hx + k.hs * .7f) + Ui.dp(1f)
    private fun height(k: Glyphs) = k.cap + 2 * over(k) + k.below + Ui.dp(1f)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val max = Ui.dp(maxDp)
        px = max
        var k = glyphs(max)
        val room = MeasureSpec.getSize(wSpec)
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED && room > 0 && width(k) > room) { px = max * room / width(k) * .995f; k = glyphs(px) }
        setMeasuredDimension(kotlin.math.ceil(width(k)).toInt(), kotlin.math.ceil(height(k)).toInt())
    }

    override fun onDraw(c: Canvas) {
        val k = glyphs(px)
        val base = over(k) + k.cap
        c.save(); c.translate(0f, base)
        tp.style = android.graphics.Paint.Style.FILL
        c.drawPath(k.word, tp)
        // the heart: beats twice (lub-dub) every 1.7 s, its colour running red → pink → wine, a soft glow on each beat
        val still = !beat.isRunning
        val p1 = bump(t, 0f, .16f); val p2 = bump(t, .22f, .40f)
        val s = if (still) 1f else 1f + .17f * p1 + .10f * p2
        val col = if (still) HUE[0] else hue(t)
        c.save()
        c.translate(k.hx, k.hBottom - k.hs / 2f)
        c.rotate(-8f)
        c.scale(k.hs / 20f * s, k.hs / 20f * s)          // (the heart's line fills 20 of its 24 units)
        c.translate(-12f, -12.2f)
        if (!still) {
            glowP.color = col; glowP.alpha = (95 * maxOf(p1, p2 * .8f)).toInt()
            c.save(); c.scale(1.32f, 1.32f, 12f, 12.2f); c.drawPath(heart, glowP); c.restore()
        }
        fillP.color = col
        c.drawPath(heart, fillP)
        edgeP.strokeWidth = 1.6f
        c.drawPath(heart, edgeP)
        c.restore()
        c.restore()
    }

    private fun sync() {
        val on = seen && isAttachedToWindow && !GlobeMap.REDUCED
        if (on && !beat.isStarted) beat.start() else if (!on && beat.isStarted) { beat.cancel(); t = 0f; invalidate() }
    }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); seen = isVisible; sync() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { beat.cancel(); super.onDetachedFromWindow() }

    companion object {
        const val WORD = "TrueFind"
        /** the heart's size: half the tall letters' height */
        private const val HEART = .5f
        private val HUE = intArrayOf(Ui.c("#E11D48"), Ui.c("#FF4F9A"), Ui.c("#B3124A"), Ui.c("#E11D48"))
        private fun hue(t: Float): Int {
            val f = t * (HUE.size - 1); val i = f.toInt().coerceAtMost(HUE.size - 2)
            return androidx.core.graphics.ColorUtils.blendARGB(HUE[i], HUE[i + 1], f - i)
        }
        /** 0 → 1 → 0 between [a] and [b] (smooth), 0 elsewhere */
        private fun bump(t: Float, a: Float, b: Float): Float {
            if (t <= a || t >= b) return 0f
            val x = (t - a) / (b - a)
            return kotlin.math.sin(x * Math.PI).toFloat()
        }
        /** the width the whole logo needs at text size [sizeDp] (px) — to tell beforehand whether a row has room for it */
        fun widthAt(sizeDp: Float): Float {
            val p = android.graphics.Paint().apply { typeface = Rs.serifItalic; textSize = Ui.dp(sizeDp) }
            val b = android.graphics.Rect(); p.getTextBounds(WORD, 0, WORD.length, b)
            return maxOf(b.right.toFloat(), p.measureText(WORD)) + Ui.dp(1f)
        }
    }
}
