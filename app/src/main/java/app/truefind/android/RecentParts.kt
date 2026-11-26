package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pieces of the native Recent tab (pages/Chats.jsx) and of another member's profile opened from it
 * (components/PersonProfile.jsx): colours, icons, the page background, a row's box, the round photo,
 * the round call buttons, the loading blocks and the web's box shadows.
 */
object RC {
    // pages/Chats.jsx C (the True Find Home colours)
    // 26 Nov (owner's templates): the same colours as Profile / Wallet — dark ink, the template's button purple,
    // light lavender chips and the thin lavender card line
    val ink = Ui.c("#1E1433"); val purple = Ui.c("#6C2BD9"); val sub = Ui.c("#5F5673"); val gray = Ui.c("#7C7594")
    val card = Ui.c("#FFFFFF"); val line = Ui.c("#E6DCFB"); val soft = Ui.c("#F1EAFF"); val softInk = Ui.c("#4C1D95")
    val burgundy = Ui.c("#A3154F"); val green = Ui.c("#16A34A"); val red = Ui.c("#DC2626")
    val softLine = Ui.c("#E3D3FB")

    /** the filled purple button: one flat purple, as in the templates (26 Nov; was a #7A0AD7 → #5806A3 gradient) */
    fun btn(radiusDp: Float): GradientDrawable =
        GradientDrawable().apply { setColor(Ui.c("#6C2BD9")); cornerRadius = Ui.dp(radiusDp) }

    // Lucide icons (lucide-react 0.475.0, the web's version) that the rest of the app did not need yet
    const val PHONE = "<path d=\"M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z\"/>"
    const val PHONE_MISSED = "<line x1=\"22\" x2=\"16\" y1=\"2\" y2=\"8\"/><line x1=\"16\" x2=\"22\" y1=\"2\" y2=\"8\"/>" + PHONE
    const val ARROW_UP_RIGHT = "<path d=\"M7 7h10v10\"/><path d=\"M7 17 17 7\"/>"
    const val ARROW_DOWN_LEFT = "<path d=\"M17 7 7 17\"/><path d=\"M17 17H7V7\"/>"

    /** the faint background pattern: tiny hearts, rings and dots in light purple (a 120 × 120 tile) */
    const val PATTERN = "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='120' viewBox='0 0 120 120' fill='none' stroke='#C9B0F2' stroke-width='1.3' stroke-linecap='round' stroke-linejoin='round'>" +
        "<path d='M22 26c-2.4-2.6-6.6-.4-5.6 3 .8 2.6 5.6 6 5.6 6s4.8-3.4 5.6-6c1-3.4-3.2-5.6-5.6-3z'/>" +
        "<circle cx='86' cy='22' r='3.2'/>" +
        "<path d='M80 84c-2-2.2-5.6-.3-4.7 2.5.7 2.2 4.7 5 4.7 5s4-2.8 4.7-5c.9-2.8-2.7-4.7-4.7-2.5z'/>" +
        "<circle cx='30' cy='88' r='1.6' fill='#C9B0F2' stroke='none'/><circle cx='60' cy='56' r='1.4' fill='#D8C6F6' stroke='none'/>" +
        "<circle cx='104' cy='60' r='1.6' fill='#D8C6F6' stroke='none'/><path d='M50 108l3 0M51.5 106.5l0 3'/>" +
        "</svg>"

    /** mix of two colours (t = 0 → a, 1 → b), alpha included */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * k).roundToInt()
        return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /**
     * Where a one-line text sits like a CSS line box of [lhDp]: the text keeps its own height and these
     * margins (px, may be below 0) put it where the web puts it — the glyphs centred in the line.
     */
    fun half(tv: TextView, lhDp: Float): Int {
        val fm = tv.paint.fontMetricsInt
        return ((Ui.dp(lhDp) - (fm.descent - fm.ascent)) / 2f).roundToInt()
    }
    /** LinearLayout params of a one-line text in a CSS line box of [lhDp], with the web's margins around it */
    fun line(tv: TextView, lhDp: Float, w: Int = LinearLayout.LayoutParams.WRAP_CONTENT, topDp: Float = 0f, bottomDp: Float = 0f): LinearLayout.LayoutParams {
        val h = half(tv, lhDp)
        return LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(topDp) + h; bottomMargin = Ui.dpi(bottomDp) + h }
    }

    // ── the web's box-shadow, drawn where it shows: under a box ──
    private fun phi(x: Double): Double {
        // standard normal CDF (Abramowitz–Stegun 7.1.26), enough for a soft shadow
        val t = 1.0 / (1.0 + 0.3275911 * kotlin.math.abs(x) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x / 2.0)
        return if (x >= 0) (1.0 + y) / 2.0 else (1.0 - y) / 2.0
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * box-shadow: 0 [offYDp] [blurDp] [spreadDp] [color] (with its alpha × [k]) of the box [b] (px, corners [radius]):
     * only the part below the box is drawn (the shadow never shows through the box itself).
     */
    fun shadowBelow(c: Canvas, b: RectF, offYDp: Float, blurDp: Float, spreadDp: Float, color: Int, k: Float = 1f) {
        if (k <= 0f) return
        val sigma = Ui.dp(blurDp) / 2f
        val spread = Ui.dp(spreadDp)
        val sBottom = b.bottom + spread + Ui.dp(offYDp)
        val sLeft = b.left - spread; val sRight = b.right + spread
        val end = sBottom + 2.6f * sigma
        if (end <= b.bottom || sRight - sLeft <= 0f) return
        val a0 = Color.alpha(color) / 255f * k
        val n = 7
        val cols = IntArray(n); val pos = FloatArray(n)
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val y = b.bottom + (end - b.bottom) * f
            val a = (a0 * phi(((sBottom - y) / sigma).toDouble())).toFloat()
            cols[i] = Color.argb((a * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
            pos[i] = f
        }
        shadowPaint.shader = LinearGradient(0f, b.bottom, 0f, end, cols, pos, Shader.TileMode.CLAMP)
        c.save()
        c.clipRect(b.left - sigma * 3f, b.bottom, b.right + sigma * 3f, end)
        // the shadow's own width (a little narrower than the box), its ends rounded off
        val r = RectF(sLeft - sigma * 0.6f, b.bottom - sigma * 4f, sRight + sigma * 0.6f, end)
        val rr = min(sigma * 2.2f, r.width() / 2f)
        c.drawRoundRect(r, rr, rr, shadowPaint)
        c.restore()
        shadowPaint.shader = null
    }
}

/**
 * The Recent page background (fixed: the list scrolls over it): a lavender wash from the top, a soft
 * purple light at the corners and a faint pattern of tiny hearts and dots, fading out towards the bottom.
 */
class RecentBackdrop(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pat = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wash: Shader? = null
    private val glows = ArrayList<Shader>()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        val fw = w.toFloat(); val fh = h.toFloat()
        wash = LinearGradient(0f, 0f, 0f, fh, intArrayOf(Ui.c("#F1E7FF"), Ui.c("#F8F3FF"), Ui.c("#FCFAFF"), Color.WHITE), floatArrayOf(0f, .34f, .62f, 1f), Shader.TileMode.CLAMP)
        glows.clear()
        // radial-gradient(70% 40% at 100% 0%, rgba(167,112,245,.28) 0%, transparent 70%) and the other two
        glows.add(ellipse(fw, 0f, .7f * fw, .4f * fh, Color.argb(71, 167, 112, 245), .70f))
        glows.add(ellipse(0f, .06f * fh, .6f * fw, .34f * fh, Color.argb(77, 236, 190, 255), .72f))
        glows.add(ellipse(.5f * fw, fh, .8f * fw, .3f * fh, Color.argb(56, 214, 190, 250), .70f))
        // the pattern at 55 %, masked: fully there at the top, 45 % at 45 % of the height, 15 % at the bottom
        val tile = SvgPattern.paint(Svg.of(RC.PATTERN), 120f, 0f, "recent-bg").shader
        val mask = LinearGradient(0f, 0f, 0f, fh, intArrayOf(Color.BLACK, Color.argb(115, 0, 0, 0), Color.argb(38, 0, 0, 0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        pat.shader = if (tile != null) ComposeShader(tile, mask, PorterDuff.Mode.DST_IN) else null
        pat.alpha = (0.55f * 255).roundToInt()
        cache?.recycle(); cache = null
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Int, stop: Float): Shader =
        RadialGradient(0f, 0f, 1f, intArrayOf(color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))), floatArrayOf(0f, stop), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(cx, cy) })
        }

    // The background is painted once into a picture of its own (same look) and that picture is what the screen
    // draws: page moves and pop-ups then don't paint the gradients and the masked pattern again on every frame
    // (the test phone's graphics stopped on exactly that, and it is lighter on real phones too).
    private var cache: android.graphics.Bitmap? = null

    private fun paint(c: Canvas, w: Float, h: Float) {
        p.shader = wash; c.drawRect(0f, 0f, w, h, p)
        for (g in glows) { p.shader = g; c.drawRect(0f, 0f, w, h, p) }
        p.shader = null
        if (pat.shader != null) c.drawRect(0f, 0f, w, h, pat)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle(); cache = null
    }

    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        var b = cache
        if (b == null || b.width != w || b.height != h) {
            b?.recycle()
            b = try { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) } catch (e: Throwable) { null }
            if (b == null) { paint(c, w.toFloat(), h.toFloat()); return }
            paint(Canvas(b), w.toFloat(), h.toFloat())
            cache = b
        }
        c.drawBitmap(b, 0f, 0f, null)
    }
}

/**
 * A Recent row's box: white (.92), 1 px line, round 18, a very soft shadow under it. The last tapped
 * row ([picked]) gets a 2 px purple line, a light purple ring around it and a deeper shadow; while a
 * finger is on the row the line turns light purple (.tfr-row:active). Both changes ease over 150 ms.
 */
class RowBox : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var t = 0f          // 0 = normal … 1 = picked
    private var press = 0f      // 0 … 1 = finger on it
    private var tAnim: ValueAnimator? = null
    private var pAnim: ValueAnimator? = null
    var view: View? = null

    fun setPicked(on: Boolean, animate: Boolean) {
        val to = if (on) 1f else 0f
        tAnim?.cancel()
        if (!animate || t == to) { t = to; invalidateSelf(); return }
        tAnim = ValueAnimator.ofFloat(t, to).apply { duration = 150; addUpdateListener { t = it.animatedValue as Float; invalidateSelf() }; start() }
    }
    fun setPressed(on: Boolean) {
        val to = if (on) 1f else 0f
        if (press == to) return
        pAnim?.cancel()
        pAnim = ValueAnimator.ofFloat(press, to).apply { duration = 150; addUpdateListener { press = it.animatedValue as Float; invalidateSelf() }; start() }
    }

    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        val rad = Ui.dp(18f)
        // 0 6px 16px -14px rgba(76,29,149,.45)  →  picked: 0 10px 22px -16px rgba(76,29,149,.55)
        RC.shadowBelow(c, b, 6f, 16f, -14f, Color.argb(115, 76, 29, 149), 1f - t)
        RC.shadowBelow(c, b, 10f, 22f, -16f, Color.argb(140, 76, 29, 149), t)
        // picked: 0 0 0 4px rgba(108,4,203,.10) around the box
        if (t > 0f) {
            val ring = Ui.dp(4f)
            p.style = Paint.Style.STROKE; p.strokeWidth = ring
            p.color = Color.argb((0.10f * t * 255).roundToInt(), 108, 4, 203)
            r.set(b.left - ring / 2f, b.top - ring / 2f, b.right + ring / 2f, b.bottom + ring / 2f)
            c.drawRoundRect(r, rad + ring / 2f, rad + ring / 2f, p)
        }
        // the box (its colour runs under the line, like CSS)
        p.style = Paint.Style.FILL
        p.color = RC.mix(Color.argb(235, 255, 255, 255), Color.WHITE, t)
        c.drawRoundRect(b, rad, rad, p)
        // the line: 1 px #E8E1F1 → 2 px #6C04CB; a finger on it: #C9B2F2
        val bw = Ui.dp(1f + t)
        p.style = Paint.Style.STROKE; p.strokeWidth = bw
        p.color = RC.mix(RC.mix(RC.line, RC.purple, t), Ui.c("#C9B2F2"), press)
        r.set(b.left + bw / 2f, b.top + bw / 2f, b.right - bw / 2f, b.bottom - bw / 2f)
        c.drawRoundRect(r, rad - bw / 2f, rad - bw / 2f, p)
        p.style = Paint.Style.FILL
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The round photo of a Recent row: a 1.5 px line, lavender behind, the photo (its small copy first,
 * then the full photo) or the default avatar, and the Online / Busy / Offline dot at the bottom right
 * (26 % of the photo, white ring of 2.5).
 */
class RecentFace(ctx: Context, private val sizeDp: Float) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bmp: Bitmap? = null
    private var want: List<String> = emptyList()
    var dot: Int = Color.TRANSPARENT
        set(v) { if (field != v) { field = v; invalidate() } }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun innerPx() = Ui.dpi(sizeDp - 3f).coerceAtLeast(24)

    /** raws: the small copy first, then the full photo ("asset:…" = a picture inside the app, test screens only) */
    fun setPhoto(raws: List<String>) {
        val list = raws.filter { it.isNotBlank() }.distinct()
        if (list == want && bmp != null) return
        want = list
        val asset = list.firstOrNull { it.startsWith("asset:") }
        if (asset != null) { bmp = Tf.pic(context, asset.removePrefix("asset:")); invalidate(); return }
        val size = innerPx()
        bmp = Photos.cached(list, size)
        invalidate()
        if (bmp == null && list.isNotEmpty()) Photos.loadRaw(list, size) { b -> if (want == list && b != null) { bmp = b; invalidate() } }
    }

    override fun onMeasure(w: Int, h: Int) { val s = Ui.dpi(sizeDp); setMeasuredDimension(s, s) }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val bw = Ui.dp(1.5f)
        p.shader = null
        p.color = RC.line; c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = RC.soft; c.drawCircle(s / 2f, s / 2f, s / 2f - bw, p)
        val inner = s - 2f * bw
        val b = bmp
        if (b != null && !b.isRecycled) {
            p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val k = max(inner / b.width, inner / b.height)
                setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate(bw + (inner - b.width * k) / 2f, bw + (inner - b.height * k) / 2f) })
            }
            c.drawCircle(s / 2f, s / 2f, inner / 2f, p)
            p.shader = null
        } else {
            c.save(); c.translate(bw, bw); DefaultAvatar.draw(c, inner); c.restore()
        }
        if (dot != Color.TRANSPARENT) {
            val d = Math.round(sizeDp * 0.26f).toFloat()
            val dr = Ui.dp(d) / 2f
            val cx = s - dr; val cy = s - Ui.dp(1f) - dr
            p.color = Color.WHITE; c.drawCircle(cx, cy, dr, p)
            p.color = dot; c.drawCircle(cx, cy, dr - Ui.dp(2.5f), p)
        }
    }
}

/**
 * A round call button of a Recent row (42): Voice is light purple with a purple phone, Video is the
 * purple gradient with a white camera and a soft purple glow under it. While its call starts it shows
 * the True Find loader; the other buttons fade to 60 % and wait.
 */
@SuppressLint("ViewConstructor")
class RecentCallBtn(ctx: Context, val video: Boolean) : FrameLayout(ctx) {
    private val icon = ImageView(ctx)
    private val loader = TfLoader(ctx, 19f, if (video) Color.WHITE else Tf.BLUE)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fill: Shader? = null

    init {
        setWillNotDraw(false)
        icon.setImageDrawable(if (video) Tf.lu(Lu.Video, Color.WHITE, 19f, 2.1f) else Tf.lu(RC.PHONE, RC.softInk, 18f, 2.1f))
        addView(icon, LayoutParams(Ui.dpi(if (video) 19f else 18f), Ui.dpi(if (video) 19f else 18f), Gravity.CENTER))
        loader.visibility = GONE
        addView(loader, LayoutParams(Ui.dpi(19f), Ui.dpi(19f), Gravity.CENTER))
        isClickable = true
        setOnTouchListener { v, e ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.95f).scaleY(.95f).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    /** busy: this button's call is starting; dim: another one is */
    fun state(busy: Boolean, dim: Boolean) {
        icon.visibility = if (busy) GONE else VISIBLE
        loader.visibility = if (busy) VISIBLE else GONE
        alpha = if (dim) .6f else 1f
        isEnabled = !busy && !dim
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fill = if (video) LinearGradient(0f, 0f, 0f, h.toFloat(), Ui.c("#6C2BD9"), Ui.c("#6C2BD9"), Shader.TileMode.CLAMP) else null
        if (video) {
            // box-shadow: 0 8px 16px -10px rgba(88,6,163,.8): a circle of radius 11, 8 lower, blurred (σ 8)
            val cols = IntArray(GLOW.size) { i -> Color.argb((GLOW[i] * 255).roundToInt().coerceIn(0, 255), 88, 6, 163) }
            val pos = FloatArray(GLOW.size) { i -> i / (GLOW.size - 1f) }
            glowPaint.shader = RadialGradient(w / 2f, h / 2f + Ui.dp(8f), Ui.dp(GLOW_REACH), cols, pos, Shader.TileMode.CLAMP)
        }
        hole.reset(); hole.addCircle(w / 2f, h / 2f, w / 2f, android.graphics.Path.Direction.CW)
    }
    private val hole = android.graphics.Path()

    companion object {
        private const val GLOW_R = 11f; private const val GLOW_SIGMA = 8f
        const val GLOW_REACH = GLOW_R + 2.6f * GLOW_SIGMA
        /** how strong the blurred circle is at 0 … GLOW_REACH from its centre (a 2-D blur of a disc, worked out once) */
        val GLOW: FloatArray by lazy {
            val n = 14
            FloatArray(n) { k ->
                val rho = GLOW_REACH * k / (n - 1)
                var sum = 0.0
                val nr = 40; val nt = 72
                for (i in 0 until nr) {
                    val r = (i + 0.5) * GLOW_R / nr
                    for (j in 0 until nt) {
                        val th = (j + 0.5) * 2 * Math.PI / nt
                        val d2 = r * r + rho * rho - 2 * r * rho * Math.cos(th)
                        sum += exp(-d2 / (2.0 * GLOW_SIGMA * GLOW_SIGMA)) * r
                    }
                }
                val area = (GLOW_R / nr) * (2 * Math.PI / nt)
                (0.8 * sum * area / (2 * Math.PI * GLOW_SIGMA * GLOW_SIGMA)).toFloat()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (video) {
            // flat, no glow under it (26 Nov, the owner's templates)
            p.shader = fill; c.drawCircle(w / 2f, h / 2f, w / 2f, p); p.shader = null
        } else {
            p.color = RC.line; c.drawCircle(w / 2f, h / 2f, w / 2f, p)
            p.color = RC.soft; c.drawCircle(w / 2f, h / 2f, w / 2f - Ui.dp(1f), p)
        }
    }
}

/**
 * A loading block (the web's .tfr-sk / .tfpp-sk): a soft colour with a light band sweeping right to left
 * every 0.75 s — still when the phone's animations are off. (The web's band: twice the block wide,
 * base · light in the middle · base, moving one tile to the left per loop; only one band is ever in view.)
 */
class RecentShimmer(ctx: Context, private val radiusDp: Float, private val base: Int, private val light: Int) : LoopView(ctx, 750) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        r.set(0f, 0f, w, h)
        val rad = min(Ui.dp(radiusDp), h / 2f)
        val f = if (Lite.reducedMotion) 0f else t
        // the band's middle: from the right edge, one tile (2 × the width) to the left per loop
        var mid = w - 2f * w * f
        if (mid < -0.5f * w) mid += 2f * w
        p.shader = LinearGradient(mid - 0.5f * w, 0f, mid + 0.5f * w, 0f, intArrayOf(base, light, base), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
    }
}

/**
 * A row of the web's flexbox, one line high: children side by side (each with its gap before it),
 * centred in [heightPx]; one child may shrink (and end with "…"), one may sit at the right edge
 * (margin-left: auto).
 */
class RecentLine(ctx: Context, private val heightPx: Int) : ViewGroup(ctx) {
    private val gaps = ArrayList<Int>()
    private var shrink: View? = null
    private var end: View? = null

    fun add(v: View, gapDp: Float = 0f, wPx: Int = LayoutParams.WRAP_CONTENT, hPx: Int = LayoutParams.WRAP_CONTENT, shrinks: Boolean = false, atEnd: Boolean = false): RecentLine {
        addView(v, LayoutParams(wPx, hPx))
        gaps.add(Ui.dpi(gapDp))
        if (shrinks) shrink = v
        if (atEnd) end = v
        return this
    }

    private fun spec(v: Int) = if (v >= 0) MeasureSpec.makeMeasureSpec(v, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else MeasureSpec.getSize(ws)
        var used = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            used += gaps[i]
            if (c === shrink) continue
            c.measure(spec(c.layoutParams.width), spec(c.layoutParams.height))
            used += c.measuredWidth
        }
        shrink?.let { s ->
            if (s.visibility != GONE) {
                s.measure(MeasureSpec.makeMeasureSpec(max(0, maxW - used), MeasureSpec.AT_MOST), spec(s.layoutParams.height))
                used += s.measuredWidth
            }
        }
        val w = if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) MeasureSpec.getSize(ws) else min(used, maxW)
        setMeasuredDimension(w, heightPx)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            x += gaps[i]
            if (c === end) x = max(x, w - c.measuredWidth)
            val y = (h - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth
        }
    }
}

/** A box no wider than [maxDp] (the web's max-width), centred by its parent. */
class RecentMaxWidth(ctx: Context, private val maxDp: Float) : LinearLayout(ctx) {
    init { orientation = VERTICAL }
    override fun onMeasure(ws: Int, hs: Int) {
        val size = MeasureSpec.getSize(ws)
        val w = if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) Ui.dpi(maxDp) else min(size, Ui.dpi(maxDp))
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), hs)
    }
}

/** A plain coloured box with round corners that also gives the web's soft shadow under it. */
class ShadowBox(private val color: Int, private val radiusDp: Float, private val offYDp: Float, private val blurDp: Float,
                private val spreadDp: Float, private val shadow: Int, private val strokeDp: Float = 0f, private val stroke: Int = Color.TRANSPARENT) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        RC.shadowBelow(c, b, offYDp, blurDp, spreadDp, shadow)
        p.style = Paint.Style.FILL; p.color = color
        c.drawRoundRect(b, Ui.dp(radiusDp), Ui.dp(radiusDp), p)
        if (strokeDp > 0f) {
            val sw = Ui.dp(strokeDp)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = stroke
            c.drawRoundRect(RectF(b.left + sw / 2f, b.top + sw / 2f, b.right - sw / 2f, b.bottom - sw / 2f), Ui.dp(radiusDp) - sw / 2f, Ui.dp(radiusDp) - sw / 2f, p)
            p.style = Paint.Style.FILL
        }
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, Ui.dp(radiusDp)) }
}

@Suppress("unused") private val keepRect = Rect()
