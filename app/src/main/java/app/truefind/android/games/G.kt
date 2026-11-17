package app.truefind.android.games

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.truefind.android.Ui
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Small kit shared by the native games (sizes in dp = the web's CSS px). */
object G {
    fun dp(v: Float): Float = Ui.dp(v)
    fun dpi(v: Float): Int = Ui.dpi(v)
    fun c(hex: String): Int = Color.parseColor(hex)
    /** "#RRGGBB" with alpha 0..1 */
    fun ca(hex: String, a: Float): Int = alpha(c(hex), a)
    fun alpha(color: Int, a: Float): Int = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    /** CSS "#RRGGBBAA" too */
    fun hex8(h: String): Int {
        if (h.length == 9) {
            val rgb = c(h.substring(0, 7)); val a = h.substring(7, 9).toInt(16)
            return Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        }
        return c(h)
    }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── text ──
    fun text(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = true, italic: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(size))
            setTextColor(color)
            typeface = if (italic) (try { Ui.bold } catch (e: Throwable) { null }).let { android.graphics.Typeface.create(it, if (bold) android.graphics.Typeface.BOLD_ITALIC else android.graphics.Typeface.ITALIC) }
            else if (bold) Ui.bold else Ui.regular
            includeFontPadding = false
        }



    // ── boxes ──
    fun vbox(ctx: Context, gapDp: Float = 0f, gravity: Int = Gravity.NO_GRAVITY): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; this.gravity = gravity
        if (gapDp > 0f) gap(this, gapDp)
    }
    fun hbox(ctx: Context, gapDp: Float = 0f, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
        if (gapDp > 0f) gap(this, gapDp)
    }
    /** CSS gap between the children of a LinearLayout. */
    fun gap(l: LinearLayout, gapDp: Float) {
        val px = dpi(gapDp)
        l.dividerDrawable = GradientDrawable().apply { setSize(px, px); setColor(Color.TRANSPARENT) }
        l.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
    fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)
    fun lpw(weight: Float, h: Int = WRAP): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, h, weight)
    fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity)

    fun round(color: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radiusDp)
            if (stroke != null) setStroke(dpi(strokeDp).coerceAtLeast(1), stroke)
        }
    fun oval(color: Int, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        if (stroke != null) setStroke(dpi(strokeDp).coerceAtLeast(1), stroke)
    }

    /** Android shadow under a rounded shape (closest to CSS box-shadow). */
    fun shadow(v: View, elevationDp: Float, color: Int? = null, radiusDp: Float = -1f) {
        v.elevation = dp(elevationDp)
        if (radiusDp >= 0f) {
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, dp(radiusDp)) }
            }
        }
        if (color != null && Build.VERSION.SDK_INT >= 28) { v.outlineSpotShadowColor = color; v.outlineAmbientShadowColor = color }
    }

    // ── press (web :active { transform: scale(.96) }) ──
    fun press(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (view.isEnabled) view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }
    fun click(v: View, scale: Float = 0.96f, f: () -> Unit) {
        press(v, scale)
        v.isClickable = true
        v.setOnClickListener { if (v.isEnabled) f() }
    }

    // ── pictures ──
    fun icon(ctx: Context, name: String, color: Int, sizeDp: Float, stroke: Float = 2f, fill: Int? = null): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(SvgDrawable(Svg.icon(name, color, stroke, fill), sizeDp))
            layoutParams = ViewGroup.LayoutParams(dpi(sizeDp), dpi(sizeDp))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    fun iconLp(v: View, sizeDp: Float): View { v.layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp)); return v }

    private var coinBmp: Bitmap? = null
    /** The small True Find coin picture (same file as the web: tf-coin-sm.png). */
    fun coin(ctx: Context, sizeDp: Float): ImageView = ImageView(ctx).apply {
        if (coinBmp == null) coinBmp = try { ctx.assets.open("games/tf-coin.png").use { BitmapFactory.decodeStream(it) } } catch (e: Throwable) { null }
        coinBmp?.let { setImageBitmap(it) }
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ── animations (web keyframes) ──
    val EASE_OUT = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    val EASE_SHEET = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
    /** .xx-in: fade + rise (translateY [dy] → 0) */
    fun rise(v: View, delayMs: Long = 0, dyDp: Float = 10f, ms: Long = 320) {
        v.alpha = 0f; v.translationY = dp(dyDp)
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(ms).setInterpolator(EASE_OUT).start()
    }
    /** a heart beat (scale 1 → 1.09 → 1, 1.2 s, forever) */
    fun beat(v: View, peak: Float = 1.09f, periodMs: Long = 1200): ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = when {
                    t < 0.18f -> 1f + (peak - 1f) * ease(t / 0.18f)
                    t < 0.36f -> peak - (peak - 1f) * ease((t - 0.18f) / 0.18f)
                    else -> 1f
                }
                v.scaleX = s; v.scaleY = s
            }
            start()
        }
    fun ease(t: Float): Float = (1f - cos(Math.PI * t).toFloat()) / 2f

    /** Stops every animator stored on a view tree (so nothing keeps running off screen). */
    fun stopAll(list: MutableList<ValueAnimator>) { for (a in list) a.cancel(); list.clear() }

    // ── a confirm box (the web's window.confirm) ──
    fun confirm(ctx: Context, msg: String, ok: () -> Unit) {
        try {
            AlertDialog.Builder(ctx).setMessage(msg).setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
        } catch (e: Throwable) { ok() }
    }
}


// ── extensions (top level so every games file can use them) ──
/** CSS line-height (× font size) */
fun TextView.lineH(lineHeight: Float): TextView {
    setLineSpacing(textSize * (lineHeight - 1f), 1f)
    return this
}
fun TextView.ellipsize1(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }
fun LinearLayout.LayoutParams.m(l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f): LinearLayout.LayoutParams {
    setMargins(G.dpi(l), G.dpi(t), G.dpi(r), G.dpi(b)); return this
}
fun FrameLayout.LayoutParams.m(l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f): FrameLayout.LayoutParams {
    setMargins(G.dpi(l), G.dpi(t), G.dpi(r), G.dpi(b)); return this
}
fun View.pad(l: Float, t: Float, r: Float, b: Float) { setPadding(G.dpi(l), G.dpi(t), G.dpi(r), G.dpi(b)) }
fun View.pad(all: Float) { val p = G.dpi(all); setPadding(p, p, p, p) }

/**
 * Background like the web's CSS: layers of linear / radial gradients, rounded corners,
 * an optional border (solid or dashed). Layers are drawn first to last (CSS lists the TOP layer first,
 * so pass them bottom-first).
 */
class CssBg(
    private val radiusDp: FloatArray = floatArrayOf(0f, 0f, 0f, 0f), // tl, tr, br, bl
    private val layers: List<Layer> = emptyList(),
    private val fill: Int = Color.TRANSPARENT,
    private val border: Int = Color.TRANSPARENT,
    private val borderDp: Float = 0f,
    private val dashed: Boolean = false,
    private val oval: Boolean = false,
) : Drawable() {
    sealed class Layer
    /** linear-gradient(angle, colors at stops) — angle as CSS (180 = top → bottom) */
    class Linear(val angle: Float, val colors: IntArray, val stops: FloatArray? = null) : Layer()
    /** radial-gradient(rx ry at cx cy, …) — all as fractions of the box (CSS percents / 100);
     *  [closestSide] = CSS "circle at x y" sized by farthest corner when rx<0 */
    class Radial(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val colors: IntArray, val stops: FloatArray? = null, val circle: Boolean = false) : Layer()

    constructor(radius: Float, layers: List<Layer>, fill: Int = Color.TRANSPARENT, border: Int = Color.TRANSPARENT, borderDp: Float = 0f, dashed: Boolean = false)
        : this(floatArrayOf(radius, radius, radius, radius), layers, fill, border, borderDp, dashed, false)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private var shaders: List<Shader> = emptyList()
    private var builtFor = 0L
    private var alphaV = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) { builtFor = 0L }

    private fun build() {
        val b = bounds
        val key = (b.width().toLong() shl 32) or b.height().toLong()
        if (key == builtFor) return
        builtFor = key
        val w = b.width().toFloat(); val h = b.height().toFloat()
        shaders = layers.map { l ->
            when (l) {
                is Linear -> {
                    val a = Math.toRadians(l.angle.toDouble())
                    val dx = sin(a).toFloat(); val dy = (-cos(a)).toFloat()
                    val len = abs(w * dx) + abs(h * dy)
                    val cx = b.left + w / 2f; val cy = b.top + h / 2f
                    LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f, l.colors, l.stops, Shader.TileMode.CLAMP)
                }
                is Radial -> {
                    val cx = b.left + w * l.cx; val cy = b.top + h * l.cy
                    var rx = w * l.rx; var ry = h * l.ry
                    if (l.circle) {
                        // CSS "circle at x y": radius to the farthest corner
                        val fx = maxOf(cx - b.left, b.right - cx); val fy = maxOf(cy - b.top, b.bottom - cy)
                        val r = Math.hypot(fx.toDouble(), fy.toDouble()).toFloat()
                        rx = r; ry = r
                    }
                    val g = RadialGradient(0f, 0f, 1f, l.colors, l.stops, Shader.TileMode.CLAMP)
                    val m = Matrix(); m.setScale(rx.coerceAtLeast(0.01f), ry.coerceAtLeast(0.01f)); m.postTranslate(cx, cy)
                    g.setLocalMatrix(m); g
                }
            }
        }
        path.reset()
        rect.set(b)
        if (oval) path.addOval(rect, Path.Direction.CW)
        else path.addRoundRect(rect, radii(), Path.Direction.CW)
        if (borderDp > 0f) {
            bp.strokeWidth = G.dp(borderDp)
            bp.color = border
            bp.pathEffect = if (dashed) DashPathEffect(floatArrayOf(G.dp(4f), G.dp(3f)), 0f) else null
        }
    }

    private fun radii(): FloatArray {
        val r = radiusDp.map { G.dp(it) }
        return floatArrayOf(r[0], r[0], r[1], r[1], r[2], r[2], r[3], r[3])
    }

    override fun draw(c: Canvas) {
        build()
        p.shader = null
        if (Color.alpha(fill) > 0) { p.color = fill; p.alpha = Color.alpha(fill) * alphaV / 255; c.drawPath(path, p) }
        for (s in shaders) { p.shader = s; p.color = Color.BLACK; p.alpha = alphaV; c.drawPath(path, p) }
        p.shader = null
        if (borderDp > 0f && Color.alpha(border) > 0) {
            val half = G.dp(borderDp) / 2f
            val r = RectF(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
            val bpPath = Path()
            if (oval) bpPath.addOval(r, Path.Direction.CW) else {
                val rr = radii().map { (it - half).coerceAtLeast(0f) }.toFloatArray()
                bpPath.addRoundRect(r, rr, Path.Direction.CW)
            }
            bp.alpha = Color.alpha(border) * alphaV / 255
            c.drawPath(bpPath, bp)
        }
    }
    override fun setAlpha(alpha: Int) { alphaV = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(outline: Outline) {
        val b = bounds
        if (oval) outline.setOval(b) else outline.setRoundRect(b, G.dp(radiusDp[0]))
    }

    companion object {
        fun ovalOf(layers: List<Layer>, fill: Int = Color.TRANSPARENT, border: Int = Color.TRANSPARENT, borderDp: Float = 0f) =
            CssBg(floatArrayOf(0f, 0f, 0f, 0f), layers, fill, border, borderDp, false, true)
    }
}

/** Vertical scroll page with the web's overscroll glow off (like the web page). */
fun scrollPage(ctx: Context): ScrollView = ScrollView(ctx).apply {
    isFillViewport = true
    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    isVerticalScrollBarEnabled = false
    clipToPadding = false
}

/** Rotating "Loader2" ring (web: animate-spin). */
class Spinner(ctx: Context, color: Int, sizeDp: Float) : FrameLayout(ctx) {
    private val anim: ValueAnimator
    init {
        val ic = G.icon(ctx, "Loader2", color, sizeDp)
        addView(ic, FrameLayout.LayoutParams(G.dpi(sizeDp), G.dpi(sizeDp), Gravity.CENTER))
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { ic.rotation = it.animatedValue as Float }
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}

/** Fade helper used by toasts and banners. */
fun View.fadeIn(ms: Long = 180) { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(ms).setInterpolator(DecelerateInterpolator()).start() }
