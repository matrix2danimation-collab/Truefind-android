package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.PathParser
import org.json.JSONObject

/*
 * Shared pieces of the native Wallet (WalletScreen) — the same look as
 * src/pages/Wallet.jsx and src/components/TFCoinArt.jsx on the web.
 * Sizes are in dp (= the web's px), colours are the web's own.
 */

/** One wallet's colours: True Find = deep purple, Reconnect = rose (Wallet.jsx THEME). */
class WTheme(
    val hdr: Int, val btn: Int, val track: Int, val sub: Int, val off: Int,
    val tint: Int, val tintLine: Int, val line: Int, val ink: Int, val muted: Int, val accent: Int,
) {
    companion object {
        val TRUEFIND = WTheme(
            Ui.c("#4C1D95"), Ui.c("#5E31AD"), Ui.c("#3A1478"), Ui.c("#DCCFF5"), Ui.c("#E9DDFB"),
            Ui.c("#F5F0FC"), Ui.c("#DCCFF5"), Ui.c("#E7E0F2"), Ui.c("#1E1433"), Ui.c("#5F5673"), Ui.c("#4C1D95"),
        )
        val SECRET = WTheme(
            Ui.c("#A3154F"), Ui.c("#B8346A"), Ui.c("#7C0E3B"), Ui.c("#FAD4E2"), Ui.c("#FBE1EB"),
            Ui.c("#FDF0F5"), Ui.c("#F3C9DA"), Ui.c("#EEDFE6"), Ui.c("#2A1220"), Ui.c("#6B5560"), Ui.c("#A3154F"),
        )
    }
}

/** Wallet.jsx's C colours (earnings cards, sheets, results). */
object WC {
    val ink = Ui.c("#1E1433")
    val deep = Ui.c("#3A1478")
    val vivid = Ui.c("#4C1D95")
    val sub = Ui.c("#5F5673")
    val soft = Ui.c("#5F5673")
    val line = Ui.c("#E7E0F2")
    val green = Ui.c("#0F6B3F")
    val head = Ui.c("#4C1D95")   // HEAD / BTN
    val payGreen = Ui.c("#16B85C")
}

/** The lucide icons the web Wallet uses (lucide-react 0.475), as one path each (24 × 24, stroke 2). */
object WIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    const val CHEVRON_LEFT = "M15 18l-6-6 6-6"
    const val CHEVRON_DOWN = "M6 9l6 6 6-6"
    const val CHECK = "M20 6L9 17l-5-5"
    const val RECEIPT_LINES = "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1ZM8 8h8M8 12h8M8 16h5"
    const val TRENDING_UP = "M22 7L13.5 15.5L8.5 10.5L2 17M16 7L22 7L22 13"
    val CLOCK = circle(12f, 12f, 10f) + "M12 6L12 12L16 14"
    const val SHIELD_CHECK = "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1zM9 12l2 2 4-4"
    val CIRCLE_CHECK = circle(12f, 12f, 10f) + "M9 12l2 2 4-4"
    val CIRCLE_X = circle(12f, 12f, 10f) + "M15 9l-6 6M9 9l6 6"
    const val REFRESH = "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5"
    const val SEND = "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11zM21.854 2.147l-10.94 10.939"
    const val PHONE = "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"
    val GIFT = rect(3f, 8f, 18f, 4f, 1f) + "M12 8v13M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5"
    val HELP = circle(12f, 12f, 10f) + "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3M12 17h.01"
    const val X = "M18 6L6 18M6 6l12 12"
    const val ARROW_DOWN_LEFT = "M17 7L7 17M17 17H7V7"
    const val ARROW_UP_RIGHT = "M7 7h10v10M7 17L17 7"
    val LOCK = rect(3f, 11f, 18f, 11f, 2f) + "M7 11V7a5 5 0 0 1 10 0v4"
    const val RECEIPT = "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1ZM16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8M12 17.5v-11"
    const val MESSAGE = "M7.9 20A9 9 0 1 0 4 16.1L2 22Z"
    val CIRCLE_DOT = circle(12f, 12f, 10f) + circle(12f, 12f, 1f)

    /** An outline icon, like <Icon style={{ width: size, height: size, color }} strokeWidth={stroke} />. */
    fun view(ctx: Context, data: String, color: Int, sizeDp: Float, stroke: Float = 2f): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(PathIcon(data, color, sizeDp, stroke))
            layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
}

/** The owner's TF coin pictures (from the web app's src/assets), loaded once. */
object WCoins {
    private var sm: Bitmap? = null
    private var d3: Bitmap? = null

    fun small(ctx: Context): Bitmap? = sm ?: load(ctx, "wallet/tf-coin-sm.png").also { sm = it }
    fun big3d(ctx: Context): Bitmap? = d3 ?: load(ctx, "wallet/tf-coin-3d.png").also { d3 = it }

    private val sized = HashMap<String, Bitmap>()

    /**
     * The coin picture shrunk to exactly [px] × [px]-ish screen pixels, halving step by
     * step with smoothing. Drawing the 144 px picture straight down to a small size
     * makes its rim grainy and jagged; a picture already at screen size stays clean.
     * Same picture, same size on screen — only sharper.
     */
    fun sharp(src: Bitmap, pxW: Int, pxH: Int = pxW): Bitmap {
        if (pxW <= 0 || pxH <= 0 || (pxW >= src.width && pxH >= src.height)) return src
        val key = "${System.identityHashCode(src)}:$pxW:$pxH"
        sized[key]?.let { return it }
        var b = src
        while (b.width / 2 >= pxW && b.height / 2 >= pxH) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        if (b.width != pxW || b.height != pxH) b = Bitmap.createScaledBitmap(b, pxW, pxH, true)
        sized[key] = b
        return b
    }

    private fun load(ctx: Context, path: String): Bitmap? = try {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /** <TFCoin size={…} />: the flat TF coin picture (falls back to the drawn coin). */
    fun flat(ctx: Context, sizeDp: Float): ImageView = ImageView(ctx).apply {
        val b = small(ctx)
        if (b != null) setImageBitmap(sharp(b, Ui.dpi(sizeDp))) else setImageDrawable(CoinDrawable(sizeDp))
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** <TFCoin3D size={…} />: the tilted 3D coin, 228 × 240. */
    fun tilted(ctx: Context, sizeDp: Float): ImageView = ImageView(ctx).apply {
        val b = big3d(ctx)
        val pw = Math.round(sizeDp * 228f / 240f * Ui.density)
        if (b != null) setImageBitmap(sharp(b, pw, Ui.dpi(sizeDp))) else setImageDrawable(CoinDrawable(sizeDp))
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(pw, Ui.dpi(sizeDp))
        contentDescription = "TF coin"
    }
}

/**
 * The coin-pack pictures (TFCoinArt.jsx): 2 coins → piles → box → bags → chest,
 * drawn in the same 76 × 46 box, scaled to [widthDp].
 */
class CoinArtDrawable(private val ctx: Context, private val tier: Int, widthDp: Float = 76f) : Drawable() {
    private val w = Ui.dpi(widthDp)
    private val h = Math.round(widthDp * 46f / 76f * Ui.density)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val coin = WCoins.small(ctx)
    private val r = RectF()

    companion object {
        private val RIM = Ui.c("#F9CF58"); private val RIM_D = Ui.c("#B86E00"); private val FACE = Ui.c("#F5A623")
        private val FACE_D = Ui.c("#D57800"); private val INK = Ui.c("#7A4300"); private val CREAM = Ui.c("#FFF3B2")
        private val BOX = Ui.c("#5E31AD"); private val BOX_D = Ui.c("#4C1D95"); private val BOX_L = Ui.c("#7A4FCF")
        private val LID = Ui.c("#3A1478"); private val GROUND = Ui.c("#1F1235")
        private val TF_T: Path by lazy { PathParser.createPathFromPathData("M-.62-.36h.6v.2h-.19v.54h-.22v-.54h-.19z") }
        private val TF_F: Path by lazy { PathParser.createPathFromPathData("M.08-.36h.56v.2h-.34v.11h.27v.19h-.27v.24h-.22z") }
        private val CROWN: Path by lazy { PathParser.createPathFromPathData("M28.5 10.5L28.5 2.5L33.5 6.5L38 0.8L42.5 6.5L47.5 2.5L47.5 10.5Z") }
        private val SKEW = Math.tan(Math.toRadians(-10.0)).toFloat()
    }

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h

    override fun draw(c: Canvas) {
        val b = bounds
        canvas = c
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        unit = b.width() / 76f
        c.scale(b.width() / 76f, b.height() / 46f)
        when (tier) {
            2 -> { ground(6f, 64f); big(19f, 31f, 12f); big(57f, 31f, 12f); big(38f, 28f, 16f) }
            3 -> { ground(8f, 62f); pile(9f, 4, 22f); big(52f, 28f, 16f) }
            4 -> { ground(2f, 72f); pile(2f, 3, 19f); pile(23f, 6, 19f); big(59f, 29f, 15f) }
            5 -> { ground(6f, 68f); heap(7.4f, 17f to floatArrayOf(26f, 41f), 25.5f to floatArrayOf(18.5f, 33.5f, 48.5f)); tray(9f, 29f, 49f); big(61f, 31f, 13f) }
            6 -> { ground(3f, 72f); heap(7.2f, 9.5f to floatArrayOf(33.5f), 17.5f to floatArrayOf(26f, 41f), 25.5f to floatArrayOf(18.5f, 33.5f, 48.5f)); tray(7f, 29f, 53f); big(62f, 31f, 13f) }
            7 -> { ground(8f, 64f); sack(25f, 1.08f); big(56f, 29f, 15f) }
            8 -> { ground(3f, 72f); sack(15f, 0.86f); sack(38f, 1.14f); big(62f, 31f, 13f) }
            9 -> { ground(5f, 70f); chest(7f, 48f, 7.2f, true, 16f to floatArrayOf(23.5f, 38.5f), 24f to floatArrayOf(16f, 31f, 46f)); big(62f, 31.5f, 12.5f) }
            10 -> { ground(0f, 76f); pile(0f, 4, 15f); chest(18f, 42f, 6.8f, true, 16f to floatArrayOf(31.5f, 46.5f), 24f to floatArrayOf(26f, 39f, 52f)); big(65f, 33f, 11f) }
            11 -> { ground(0f, 76f); sack(9f, 0.7f); pile(61f, 6, 15f); chest(19f, 40f, 6.8f, true, 9f to floatArrayOf(39f), 16f to floatArrayOf(31.5f, 46.5f), 24f to floatArrayOf(26f, 39f, 52f)); big(56f, 34f, 10f) }
            12 -> {
                ground(0f, 76f); pile(0f, 4, 14f); pile(62f, 6, 14f)
                chest(17f, 42f, 6.8f, false, 17f to floatArrayOf(30.5f, 45.5f), 24f to floatArrayOf(25f, 38f, 51f))
                fill.color = RIM; c.drawPath(CROWN, fill)
                line.color = RIM_D; line.strokeWidth = 0.9f; c.drawPath(CROWN, line)
                big(38f, 35.5f, 9.5f)
            }
            else -> { ground(15f, 48f); big(28f, 30f, 13f); big(47f, 29f, 15.5f) }
        }
        c.restore()
        canvas = null
    }

    private fun Canvas.rr(x: Float, y: Float, w: Float, h: Float, rx: Float, color: Int, stroke: Int? = null, sw: Float = 0f) {
        r.set(x, y, x + w, y + h)
        fill.color = color
        drawRoundRect(r, rx, rx, fill)
        if (stroke != null) { line.color = stroke; line.strokeWidth = sw; drawRoundRect(r, rx, rx, line) }
    }

    // the drawing helpers below draw on the canvas given to draw()
    private var canvas: Canvas? = null
    private var unit = 1f
    private fun cnv(): Canvas = canvas!!

    private fun big(x: Float, y: Float, rad: Float) {
        val c = cnv()
        r.set(x - rad, y - rad, x + rad, y + rad)
        if (coin != null) c.drawBitmap(WCoins.sharp(coin, Math.round(rad * 2f * unit)), null, r, bmp) else mini(x, y, rad)
    }

    private fun mini(x: Float, y: Float, rad: Float) {
        val c = cnv()
        // the rings as filled circles (same size and colours as before): crisper than thin strokes when small
        fill.color = RIM_D; c.drawCircle(x, y, rad + 0.35f, fill)
        fill.color = RIM; c.drawCircle(x, y, rad - 0.35f, fill)
        fill.color = FACE_D; c.drawCircle(x, y, rad * 0.72f + 0.25f, fill)
        fill.color = FACE; c.drawCircle(x, y, rad * 0.72f - 0.25f, fill)
        c.save()
        c.translate(x, y)
        c.scale(rad * 0.86f, rad * 0.86f)
        c.skew(SKEW, 0f)
        // the dark edge of "TF" as a small solid shadow (a thin outline smudges the tiny letters), then the cream letters
        fill.color = INK
        c.save(); c.translate(0.05f, 0.07f); c.drawPath(TF_T, fill); c.drawPath(TF_F, fill); c.restore()
        fill.color = CREAM
        c.drawPath(TF_T, fill); c.drawPath(TF_F, fill)
        c.restore()
    }

    private fun pile(x: Float, k: Int, w: Float, base: Float = 44f) {
        val c = cnv()
        for (i in 0 until k) {
            val y = base - 5.4f - i * 4.6f
            c.rr(x, y, w, 5.4f, 2.7f, FACE, RIM_D, 0.8f)
            c.rr(x + 2.2f, y + 1.1f, w - 4.4f, 1.3f, 0.65f, RIM)
        }
    }

    private fun heap(rad: Float, vararg rows: Pair<Float, FloatArray>) {
        for ((y, xs) in rows) for (x in xs) mini(x, y, rad)
    }

    private fun ground(x: Float, w: Float) {
        val c = cnv()
        fill.color = GROUND; fill.alpha = 26
        r.set(x, 44.6f - 1.7f, x + w, 44.6f + 1.7f)
        c.drawOval(r, fill)
        fill.alpha = 255
    }

    private fun tray(x: Float, y: Float, w: Float) {
        val c = cnv()
        c.rr(x, y, w, 44f - y, 3f, BOX)
        c.rr(x - 2f, y - 1.5f, w + 4f, 4.5f, 2.2f, BOX_D)
        fill.alpha = 140
        c.rr(x + 3f, y + 6f, w - 6f, 1.4f, 0.7f, BOX_L)
        fill.alpha = 255
    }

    private fun sack(cx: Float, s: Float) {
        val c = cnv()
        val b = 44f
        val p = Path()
        p.moveTo(cx - 5 * s, b - 22 * s); p.lineTo(cx - 8.5f * s, b - 29 * s); p.lineTo(cx, b - 25.5f * s)
        p.lineTo(cx + 8.5f * s, b - 29 * s); p.lineTo(cx + 5 * s, b - 22 * s); p.close()
        fill.color = BOX_L; c.drawPath(p, fill)
        p.reset()
        p.moveTo(cx - 10.5f * s, b); p.quadTo(cx - 16 * s, b - 12 * s, cx - 5 * s, b - 21 * s)
        p.lineTo(cx + 5 * s, b - 21 * s); p.quadTo(cx + 16 * s, b - 12 * s, cx + 10.5f * s, b); p.close()
        c.drawPath(p, fill)
        c.rr(cx - 5.8f * s, b - 23.2f * s, 11.6f * s, 3.2f * s, 1.6f * s, BOX_D)
        mini(cx, b - 9.5f * s, 5.6f * s)
    }

    private fun chest(x: Float, w: Float, rad: Float, lock: Boolean, vararg rows: Pair<Float, FloatArray>) {
        val c = cnv()
        val t = 28f
        val m = x + w / 2f
        val p = Path()
        p.moveTo(x, t); p.lineTo(x, t - 9); p.quadTo(m, t - 24, x + w, t - 9); p.lineTo(x + w, t); p.close()
        fill.color = LID; c.drawPath(p, fill)
        heap(rad, *rows)
        c.rr(x, t, w, 16f, 2f, BOX_D)
        c.rr(x - 1.2f, t - 1.4f, w + 2.4f, 3.6f, 1.8f, BOX)
        c.rr(x + 5f, t + 2.2f, 3f, 13.8f, 0f, RIM)
        c.rr(x + w - 8f, t + 2.2f, 3f, 13.8f, 0f, RIM)
        if (lock) c.rr(m - 3.2f, t + 5f, 6.4f, 6.4f, 1.2f, RIM, RIM_D, 0.9f)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; line.colorFilter = cf; bmp.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** <CoinArt tier={…} width={…} /> as a view. */
@SuppressLint("ViewConstructor")
class CoinArtView(ctx: Context, tier: Int, widthDp: Float = 76f) : View(ctx) {
    private val art = CoinArtDrawable(ctx, tier, widthDp)
    init {
        layoutParams = LinearLayout.LayoutParams(art.intrinsicWidth, art.intrinsicHeight)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onDraw(c: Canvas) {
        art.setBounds(0, 0, width, height)
        art.draw(c)
    }
}

/** Reconnect request credit: a round heart token (HeartToken in Wallet.jsx). */
class HeartTokenDrawable(private val sizeDp: Float, private val bg: Int, private val fg: Int) : Drawable() {
    private val px = Ui.dpi(sizeDp)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heart = PathParser.createPathFromPathData("M12 20.5s-8-5-8-10.6A4.4 4.4 0 0 1 12 7.4a4.4 4.4 0 0 1 8 2.5c0 5.6-8 10.6-8 10.6z")
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        val s = b.width().toFloat()
        p.color = bg
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), s / 2f, p)
        val hs = s * 0.56f
        c.save()
        c.translate(b.exactCenterX() - hs / 2f, b.exactCenterY() - hs / 2f)
        c.scale(hs / 24f, hs / 24f)
        p.color = fg
        c.drawPath(heart, p)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun heartToken(ctx: Context, sizeDp: Float, bg: Int, fg: Int = android.graphics.Color.WHITE): ImageView =
    ImageView(ctx).apply {
        setImageDrawable(HeartTokenDrawable(sizeDp, bg, fg))
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

/**
 * The green "% OFF" ribbon hanging from a coin pack (.tfw-rb): 22 × 36,
 * narrow at the top, wider lower down, closing in an arrow point, white text.
 */
class RibbonView(ctx: Context, private val off: Int) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#0A8F40") }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Ui.bold; textAlign = Paint.Align.CENTER }
    private val path = Path()
    init {
        contentDescription = "$off% off"
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        path.reset()
        path.moveTo(w * 0.22f, 0f); path.lineTo(w * 0.78f, 0f); path.lineTo(w, h * 0.40f); path.lineTo(w, h * 0.76f)
        path.lineTo(w * 0.5f, h); path.lineTo(0f, h * 0.76f); path.lineTo(0f, h * 0.40f); path.close()
        c.drawPath(path, fill)
        // "150%" then "OFF", from 12px down, 1px apart (font-size 9px, 7.5px from 100%; "OFF" 6.5px)
        val big = Ui.dp(if (off >= 100) 7.5f else 9f)
        val small = Ui.dp(6.5f)
        txt.textSize = big
        txt.letterSpacing = -0.2f / (if (off >= 100) 7.5f else 9f)
        var y = Ui.dp(12f) + big * 0.80f
        c.drawText("$off%", w / 2f, y, txt)
        txt.textSize = small
        txt.letterSpacing = 0.2f / 6.5f
        y += big * 0.20f + Ui.dp(1f) + small * 0.80f
        c.drawText("OFF", w / 2f, y, txt)
    }
}

/**
 * First time offer band (owner, 27 Nov): a gold ribbon hanging from the top edge of a pack, a small dark fold at its
 * top corners and a V point at the bottom; "25%" big and "MORE" small in white, so it reads clearly.
 */
class MoreBandView(
    ctx: Context, private val pct: Int, private val word: String = "MORE",
    private val top: Int = Ui.c("#F5B82A"), private val bottom: Int = Ui.c("#D98A06"), foldColor: Int = Ui.c("#9A5A00"),
) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foldColor }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Ui.bold; textAlign = Paint.Align.CENTER }
    private val path = Path()
    init { contentDescription = if (word == "MORE") "$pct% more coins" else "$pct% off" }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val inset = w * 3f / 38f
        // the fold: a thin darker strip a little wider than the band
        path.reset()
        path.moveTo(0f, h * 3.2f / 42f); path.lineTo(inset, 0f); path.lineTo(w - inset, 0f); path.lineTo(w, h * 3.2f / 42f); path.close()
        c.drawPath(path, fold)
        fill.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, top, bottom, android.graphics.Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(inset, 0f); path.lineTo(w - inset, 0f); path.lineTo(w - inset, h * 30f / 42f)
        path.lineTo(w / 2f, h); path.lineTo(inset, h * 30f / 42f); path.close()
        c.drawPath(path, fill)
        if (word == "MORE") {
            txt.textSize = h * 12.3f / 42f
            txt.letterSpacing = -0.03f
            c.drawText("$pct%", w / 2f, h * 16f / 42f, txt)
            txt.textSize = h * 7.4f / 42f
            txt.letterSpacing = 0.02f
            c.drawText(word, w / 2f, h * 26.5f / 42f, txt)
        } else {
            // "25%" over "OFF", both small (owner's design 1, 27 Nov)
            txt.textSize = h * 9.6f / 42f
            txt.letterSpacing = 0f
            c.drawText("$pct%", w / 2f, h * 14.5f / 42f, txt)
            txt.textSize = h * 9.2f / 42f
            c.drawText(word, w / 2f, h * 25.5f / 42f, txt)
        }
    }
}

/**
 * The True Find loader ("long train", TfSpinner.jsx): five dots, big to
 * small, running round a circle, fast then slow. [color] as the web gives it.
 */
class WSpinner(ctx: Context, private val sizeDp: Float, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@WSpinner.color }
    private var t = 0f
    private val ease = PathInterpolator(0.5f, 0f, 0.5f, 1f)
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    init {
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
        contentDescription = "Loading"
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (visibility == VISIBLE) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) { if (!anim.isStarted) anim.start() } else anim.cancel()
    }
    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val sizes = floatArrayOf(0.22f, 0.192f, 0.164f, 0.136f, 0.108f)
        val op = floatArrayOf(1f, 0.8f, 0.62f, 0.45f, 0.3f)
        val base = android.graphics.Color.alpha(color) / 255f
        for (i in 0 until 5) {
            val f = ((t - (-0.08f * i) / 1.3f) % 1f + 1f) % 1f
            c.save()
            c.translate(cx, cy)
            c.rotate(360f * ease.getInterpolation(f))
            p.alpha = (255 * op[i] * base).toInt()
            c.drawCircle(0f, -size / 2f + size * 0.12f, size * sizes[i] / 2f, p)
            c.restore()
        }
    }
}

/**
 * The green Pay bar: linear-gradient(180deg, #4BEB92 0%, #22D46B 55%, #1AC460 100%)
 * with the thin white light along its top edge (inset 0 1.5px 0 rgba(255,255,255,.7)).
 */
class PayGreenDrawable(private val radiusDp: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(178, 255, 255, 255) }
    private val r = RectF()
    private val clip = Path()
    override fun draw(c: Canvas) {
        val b = bounds
        r.set(b)
        val rad = Ui.dp(radiusDp)
        p.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(Ui.c("#4BEB92"), Ui.c("#22D46B"), Ui.c("#1AC460")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        // inner top light, 1.5px, clipped to the rounded shape
        c.save()
        clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(clip)
        c.drawRect(r.left, r.top, r.right, r.top + Ui.dp(1.5f), hi)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ───────── numbers and dates, the same as the web ─────────

/** Number(v) if it is a finite number, else 0 (Wallet.jsx num()). */
fun wNum(v: Any?): Double = when (v) {
    null, JSONObject.NULL -> 0.0
    is Number -> v.toDouble().takeIf { it.isFinite() } ?: 0.0
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.trim().let { s -> if (s.isEmpty()) 0.0 else s.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0 }
    else -> 0.0
}

fun JSONObject?.num(key: String): Double = if (this == null || !has(key)) 0.0 else wNum(opt(key))
fun JSONObject?.str(key: String): String = if (this == null || !has(key) || isNull(key)) "" else optString(key, "")


/**
 * The Wallet's scroll (owner, 27 Nov): butter-smooth springs. At the bottom, dragging further lifts the content; at
 * the top (the pull-to-refresh) it comes down; a fling that reaches either end gives a small bounce. Only what
 * [onShift] moves goes with it (the offers and packs) — the purple top and "Transaction history" stay still.
 */
class WBounceScroll(ctx: Context) : android.widget.ScrollView(ctx) {
    /** moves the bouncing content by [px] (negative = up) */
    var onShift: ((Float) -> Unit)? = null
    private var shift = 0f
    private var lastY = 0f
    private var dragging = false
    private var edgeBounced = false
    private val maxOver = Ui.dp(110f)
    private val holder = androidx.dynamicanimation.animation.FloatValueHolder()
    private val spring = androidx.dynamicanimation.animation.SpringAnimation(holder).apply {
        spring = androidx.dynamicanimation.animation.SpringForce(0f).setStiffness(260f).setDampingRatio(0.78f)
        addUpdateListener { _, v, _ -> applyShift(v) }
    }

    private fun applyShift(v: Float) { shift = v; onShift?.invoke(v) }

    /** set by the pull-to-refresh at the top (content follows the finger) */
    fun follow(px: Float) { spring.cancel(); applyShift(px) }

    /** let go: spring back to rest */
    fun settle(velocity: Float = 0f) {
        if (shift == 0f && velocity == 0f) return
        holder.value = shift
        spring.setStartValue(shift)
        spring.setStartVelocity(velocity)
        spring.start()
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { lastY = ev.y; dragging = true; edgeBounced = false; if (spring.isRunning) { spring.cancel() } }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastY
                lastY = ev.y
                val atEnd = !canScrollVertically(1)
                if ((atEnd && dy < 0) || shift < 0f) {
                    val over = -shift
                    // resistance grows as it lifts, so it feels soft
                    val next = (over - dy * (0.55f - 0.4f * (over / maxOver))).coerceIn(0f, maxOver)
                    applyShift(-next)
                    if (next > 0f) return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (shift < 0f) { settle(); super.onTouchEvent(ev); return true }
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t > 0 && canScrollVertically(1)) edgeBounced = false
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        // a fling that runs into the end or the top: one soft bounce of the content
        if (clampedY && !dragging && !edgeBounced && shift == 0f) {
            edgeBounced = true
            holder.value = 0f
            spring.setStartValue(0f)
            spring.setStartVelocity(if (scrollY > 0) -Ui.dp(900f) else Ui.dp(900f))
            spring.start()
        }
    }
}
