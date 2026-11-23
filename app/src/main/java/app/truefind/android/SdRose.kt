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
 * Secret Dating's look from the owner's picture (23 Nov): a blush page, maroon buttons, black words,
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

    /** "TrueFind" with its little heart — the logo at the top of Home, Chats and the map */
    fun logo(ctx: Context, sizeDp: Float = 25f): View {
        val box = FrameLayout(ctx)
        val word = TextView(ctx).apply {
            text = "TrueFind"; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(sizeDp)); setTextColor(LOGO)
            typeface = serifItalic; includeFontPadding = false; maxLines = 1
            setPadding(0, Ui.dpi(sizeDp * .22f), Ui.dpi(sizeDp * .62f), 0)
        }
        box.addView(word, FrameLayout.LayoutParams(-2, -2))
        box.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Heart, LOGO, sizeDp * .56f, 2.1f)); rotation = 14f },
            FrameLayout.LayoutParams(Ui.dpi(sizeDp * .56f), Ui.dpi(sizeDp * .56f), Gravity.END or Gravity.TOP))
        box.contentDescription = "TrueFind"
        return box
    }

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
