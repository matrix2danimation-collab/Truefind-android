package app.truefind.android.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.truefind.android.Tf
import app.truefind.android.Ui
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared look of the native sign-in pages: the colours of the web pages (Welcome.jsx, the AuthLayout
 * pages and the shadcn theme of index.css), text with CSS line-height, boxes with CSS box-shadows,
 * and the Lucide icons the web uses (lucide-react 0.475.0). Sizes are dp = the web's CSS px.
 */
internal object LK {
    fun c(hex: String) = Color.parseColor(hex)
    fun ca(hex: String, a: Float) = Ui.ca(hex, a)
    fun a(color: Int, a: Float) = Color.argb((a * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** CSS hsl(h s% l% / a) */
    fun hsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Int {
        val sf = s / 100f; val lf = l / 100f
        val cc = (1f - abs(2f * lf - 1f)) * sf
        val x = cc * (1f - abs((h / 60f) % 2f - 1f))
        val m = lf - cc / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(cc, x, 0f); h < 120f -> Triple(x, cc, 0f); h < 180f -> Triple(0f, cc, x)
            h < 240f -> Triple(0f, x, cc); h < 300f -> Triple(x, 0f, cc); else -> Triple(cc, 0f, x)
        }
        return Color.argb((alpha * 255).roundToInt(), ((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    // ── Welcome.jsx (Get Started → Login → OTP → Gender → Profile) ──
    val head = c("#4A1D96"); val ink = c("#1F1235"); val sub = c("#6B6478"); val line = c("#E6E0F0"); val purple = c("#6C2BD9")
    val ring = c("#F1EBFD"); val bad = c("#E0527A"); val err = c("#C81E4F"); val link = c("#5B21B6"); val tint = c("#F4EFFC")
    val ph = c("#8F87A6"); val div = c("#EEE9F5"); val filledLine = c("#D9CFF0"); val filledBg = c("#FAF8FD")
    val sheetTitle = c("#3B0764"); val sheetSub = c("#7C7594"); val optInk = c("#2A1645"); val purpleDeep = c("#6C04CB")
    val green = c("#22C55E")

    // ── the dark theme of the AuthLayout / NeonAuthShell / MFA pages (index.css :root) ──
    val bg = hsl(217f, 33f, 13f); val fg = hsl(210f, 40f, 98f); val card = hsl(217f, 33f, 17f); val primary = hsl(358f, 100f, 68f)
    val muted = hsl(217f, 33f, 20f); val mutedFg = hsl(215f, 20f, 65f); val destructive = hsl(0f, 70f, 50f); val border = hsl(217f, 33f, 25f)
    val accent = hsl(217f, 33f, 22f)

    // ── Lucide icons (lucide-react 0.475.0, the version the web uses) ──
    object I {
        const val Phone = """<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>"""
        const val Smartphone = """<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><path d="M12 18h.01"/>"""
        const val MessageSquare = """<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>"""
        const val ChevronsUpDown = """<path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/>"""
        const val HeartHandshake = """<path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/><path d="M12 5 9.04 7.96a2.17 2.17 0 0 0 0 3.08c.82.82 2.13.85 3 .07l2.07-1.9a2.82 2.82 0 0 1 3.79 0l2.96 2.66"/><path d="m18 15-2-2"/><path d="m15 18-2-2"/>"""
        const val Flag = """<path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><line x1="4" x2="4" y1="22" y2="15"/>"""
        const val Calendar = """<path d="M8 2v4"/><path d="M16 2v4"/><rect width="18" height="18" x="3" y="4" rx="2"/><path d="M3 10h18"/>"""
        const val BadgeCheck = """<path d="M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z"/><path d="m9 12 2 2 4-4"/>"""
        const val Eye = """<path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/>"""
        const val Ban = """<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>"""
        const val Wallet = """<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>"""
        const val Users = """<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>"""
        const val TriangleAlert = """<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>"""
        const val Lock = """<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>"""
        const val Mail = """<rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>"""
        const val CircleCheck = """<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>"""
        const val ShieldCheck = """<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>"""
        const val MessageCircle = """<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>"""
        const val User = """<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>"""
        const val MapPin = """<path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/>"""
        const val Camera = """<path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/>"""
        const val Trash2 = """<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/>"""
        const val Copy = """<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>"""
        const val Check = """<path d="M20 6 9 17l-5-5"/>"""
        const val ChevronDown = """<path d="m6 9 6 6 6-6"/>"""
        const val Heart = """<path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/>"""
        const val X = """<path d="M18 6 6 18"/><path d="m6 6 12 12"/>"""
    }

    // ── text ──
    fun t(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false, lh: Float = 0f, ls: Float = 0f): LText =
        LText(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(size))
            setTextColor(color)
            typeface = if (bold) Ui.bold else Ui.regular
            includeFontPadding = false
            if (ls != 0f) letterSpacing = ls / size
            if (lh > 0f) cssLine(Ui.dp(lh * size))
        }

    fun one(v: TextView): TextView { v.maxLines = 1; v.isSingleLine = true; v.ellipsize = TextUtils.TruncateAt.END; return v }

    // ── icons ──
    /** a Lucide icon (24 box) drawn by the app's own SVG painter */
    fun lu(inner: String, color: Int, sizeDp: Float, stroke: Float = 2f, fill: String = "none"): Drawable = Tf.lu(inner, color, sizeDp, stroke, fill)
    fun icon(ctx: Context, d: Drawable, wDp: Float, hDp: Float = wDp): ImageView = ImageView(ctx).apply {
        setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(wDp), Ui.dpi(hDp))
    }
    /** SVG art with clip paths etc. (AndroidSVG, the games' renderer) */
    fun art(markup: String, sizeDp: Float): Drawable = app.truefind.android.games.SvgDrawable(markup, sizeDp)

    // ── layout ──
    fun col(ctx: Context, gravity: Int = Gravity.START) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; this.gravity = gravity; noClip(this) }
    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity; noClip(this) }
    fun lp(w: Int = -2, h: Int = -2, t: Float = 0f, b: Float = 0f, l: Float = 0f, r: Float = 0f, weight: Float = 0f, g: Int = -1) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)); if (g >= 0) gravity = g }
    fun flp(w: Int = -2, h: Int = -2, g: Int = Gravity.NO_GRAVITY, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) =
        FrameLayout.LayoutParams(w, h, g).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)) }
    fun noClip(v: ViewGroup) { v.clipChildren = false; v.clipToPadding = false }
    fun gap(ctx: Context, hDp: Float) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, Ui.dpi(hDp)) }
    /** flex: 1 1 0 with a min height (the web's .tfo-grow) */
    fun grow(ctx: Context, minDp: Float) = View(ctx).apply { minimumHeight = Ui.dpi(minDp); layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }

    /** :active { transform: scale(s) } (and the web's button press) */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View, scale: Float = 0.985f) {
        v.setOnTouchListener { view, e ->
            if (!view.isEnabled) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }

    fun click(v: View, scale: Float = 0.97f, f: () -> Unit) { v.isClickable = true; v.setOnClickListener { f() }; press(v, scale) }

    /** false in the test pictures (the keyboard would cover half of the page) */
    var autoKeyboard = true
    /** a page puts the cursor in its first box: the keyboard comes up with it, like the web's focus() after a tap */
    fun autoFocus(v: View) { if (autoKeyboard) showKeyboard(v) else v.requestFocus() }

    fun showKeyboard(v: View) {
        v.requestFocus()
        try { (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) } catch (e: Exception) { }
    }
    fun hideKeyboard(v: View) {
        try { (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) } catch (e: Exception) { }
        v.clearFocus()
    }
}

/**
 * A TextView laid out like CSS line-height: every line is exactly [cssLine] px tall and the spare room
 * (or the overlap, when the line-height is smaller than the font) is split above and below the letters.
 */
open class LText(ctx: Context) : TextView(ctx) {
    private var lhPx = 0f
    fun cssLine(px: Float): LText {
        lhPx = px
        val fm = paint.fontMetricsInt
        setLineSpacing(px - (fm.descent - fm.ascent), 1f)
        requestLayout(); invalidate()
        return this
    }
    private fun half(): Float { val fm = paint.fontMetricsInt; return (lhPx - (fm.descent - fm.ascent)) / 2f }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (lhPx <= 0f || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) return
        val lines = (layout?.lineCount ?: 1).coerceAtLeast(1).let { if (maxLines in 1 until it) maxLines else it }
        var h = (lines * lhPx).roundToInt() + compoundPaddingTop + compoundPaddingBottom
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) h = h.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(measuredWidth, h)
    }
    override fun onDraw(canvas: Canvas) {
        if (lhPx <= 0f || (layoutParams?.height ?: -2) > 0) { super.onDraw(canvas); return }
        canvas.save(); canvas.translate(0f, half()); super.onDraw(canvas); canvas.restore()
    }
}

/** One CSS box-shadow: x, y, blur, spread (px = dp) and colour. */
internal class Sh(val dx: Float, val dy: Float, val blur: Float, val spread: Float = 0f, val color: Int)

/**
 * A box like the web's CSS: background colour (or a top→bottom gradient), rounded corners (one radius or
 * tl/tr/br/bl), a border, and box-shadows drawn outside the box (the parents let it spill: clipChildren off).
 * Blurred shadows are painted once into a small picture and reused.
 */
internal class CssBox(
    var fill: Int = Color.TRANSPARENT,
    radiusDp: Float = 0f,
    var strokeDp: Float = 0f,
    var stroke: Int = Color.TRANSPARENT,
    var shadows: List<Sh> = emptyList(),
    var gradient: IntArray? = null,
    var corners: FloatArray? = null,
    var dashed: Boolean = false,
) : Drawable() {
    var radiusDp: Float = radiusDp
        set(v) { field = v; invalidateSelf() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val path = Path()
    private class Cache(val w: Int, val h: Int, val bmp: Bitmap, val scale: Float, val pad: Float)
    private val cache = HashMap<Sh, Cache>()

    private fun radii(w: Float, h: Float): FloatArray {
        val cr = corners
        val max = minOf(w, h) / 2f
        return if (cr != null) floatArrayOf(Ui.dp(cr[0]).coerceAtMost(max), Ui.dp(cr[1]).coerceAtMost(max), Ui.dp(cr[2]).coerceAtMost(max), Ui.dp(cr[3]).coerceAtMost(max))
        else { val v = Ui.dp(radiusDp).coerceAtMost(max); floatArrayOf(v, v, v, v) }
    }
    private fun shape(out: Path, rect: RectF, rad: FloatArray, grow: Float) {
        out.reset()
        val g = rect.let { RectF(it.left - grow, it.top - grow, it.right + grow, it.bottom + grow) }
        val rr = rad.map { (it + grow).coerceAtLeast(0f) }
        out.addRoundRect(g, floatArrayOf(rr[0], rr[0], rr[1], rr[1], rr[2], rr[2], rr[3], rr[3]), Path.Direction.CW)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        r.set(b)
        val rad = radii(r.width(), r.height())
        if (shadows.isNotEmpty()) {
            c.save()
            shape(path, r, rad, 0f)
            if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutPath(path) else @Suppress("DEPRECATION") c.clipPath(path, android.graphics.Region.Op.DIFFERENCE)
        }
        for (s in shadows) {
            if (s.blur <= 0.01f) {
                shape(path, r, rad, Ui.dp(s.spread))
                p.style = Paint.Style.FILL; p.shader = null; p.color = s.color
                c.save(); c.translate(Ui.dp(s.dx), Ui.dp(s.dy)); c.drawPath(path, p); c.restore()
                continue
            }
            val cached = cache[s]?.takeIf { it.w == b.width() && it.h == b.height() } ?: shadowBitmap(s, b.width(), b.height(), rad).also { cache[s] = it }
            sp.alpha = 255
            c.save()
            c.translate(b.left + Ui.dp(s.dx) - cached.pad, b.top + Ui.dp(s.dy) - cached.pad)
            c.scale(1f / cached.scale, 1f / cached.scale)
            c.drawBitmap(cached.bmp, 0f, 0f, sp)
            c.restore()
        }
        if (shadows.isNotEmpty()) c.restore()
        shape(path, r, rad, 0f)
        val g = gradient
        p.style = Paint.Style.FILL
        if (g != null) {
            p.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, g, null, android.graphics.Shader.TileMode.CLAMP)
            c.drawPath(path, p); p.shader = null
        } else if (Color.alpha(fill) > 0) { p.color = fill; c.drawPath(path, p) }
        if (strokeDp > 0f && Color.alpha(stroke) > 0) {
            val sw = Ui.dp(strokeDp)
            val inner = RectF(r.left + sw / 2f, r.top + sw / 2f, r.right - sw / 2f, r.bottom - sw / 2f)
            shape(path, inner, rad.map { (it - sw / 2f).coerceAtLeast(0f) }.toFloatArray(), 0f)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = stroke
            p.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(Ui.dp(4f), Ui.dp(3f)), 0f) else null
            c.drawPath(path, p)
            p.pathEffect = null; p.style = Paint.Style.FILL
        }
    }

    private fun shadowBitmap(s: Sh, w: Int, h: Int, rad: FloatArray): Cache {
        // CSS blur B = a gaussian of sigma B/2; drawn at half size when large (it is blurry anyway)
        val sigma = Ui.dp(s.blur) / 2f
        val scale = if (sigma > Ui.dp(6f)) 0.5f else 1f
        val spread = Ui.dp(s.spread)
        val pad = sigma * 3f + max(0f, spread) + 2f
        val bw = ((w + pad * 2) * scale).roundToInt().coerceAtLeast(1)
        val bh = ((h + pad * 2) * scale).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(scale, scale)
        val rect = RectF(pad, pad, pad + w, pad + h)
        val pp = Path(); shape(pp, rect, rad, spread)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color
            val radius = ((sigma * scale - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        cv.drawPath(pp, paint)
        return Cache(w, h, bmp, scale, pad)
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A round "silhouette" avatar: a soft circle with a simple head and shoulders (Welcome.jsx Sil). */
internal class SilDrawable(private val tint: Int, private val fg: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    override fun draw(c: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()) / 64f
        val cx = b.exactCenterX(); val cy = b.exactCenterY(); val rr = minOf(b.width(), b.height()) / 2f
        c.save()
        clip.reset(); clip.addCircle(cx, cy, rr, Path.Direction.CW); c.clipPath(clip)
        p.color = tint; c.drawCircle(cx, cy, rr, p)
        val l = cx - 32f * s; val t = cy - 32f * s
        p.color = fg
        c.drawCircle(l + 32f * s, t + 25f * s, 10.5f * s, p)
        c.drawOval(RectF(l + 11f * s, t + 45f * s, l + 53f * s, t + 75f * s), p)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
