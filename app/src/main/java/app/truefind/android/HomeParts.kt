package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Home page look, built natively from components/home/HomeView.jsx
 * (design "13 · Home"): drawn at the web's 390 px design width and zoomed to the
 * phone width (max 480), exactly like the web. [z] = that zoom.
 */
class Zm(val z: Float) {
    fun px(v: Float): Float = Ui.dp(v * z)
    fun pi(v: Float): Int = Math.round(px(v))
}

/** A view running one looping animation, only while it is on screen (saves battery, keeps scrolling smooth). */
abstract class LoopView(ctx: Context, private val periodMs: Long) : View(ctx) {
    protected var t = 0f   // 0..1 within the loop
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = periodMs; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    // runs only while it can be seen (not when hidden, scrolled into a GONE parent, or the app is in the background)
    private fun sync() { if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) { if (!anim.isStarted) anim.start() else if (anim.isPaused) anim.resume() } else if (anim.isRunning) anim.pause() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { sync() } }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
    protected fun phase(delayMs: Long): Float = ((t - delayMs.toFloat() / periodMs) % 1f + 1f) % 1f
}

private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)
private val EASE_IN_OUT = PathInterpolator(0.42f, 0f, 0.58f, 1f)
private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

/**
 * A dot that sends out a fading ring (tfPing / tfLive), optionally blinking itself (tfDot).
 * [grow]: the ring's end scale; [fadeAt]: when the ring is gone (0..1).
 */
class PingDot(ctx: Context, private val color: Int, private val dotPx: Float, period: Long, private val grow: Float, private val startOp: Float,
              private val fadeAt: Float, private val blink: Boolean = false, private val ringOnly: Boolean = false) : LoopView(ctx, period) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = dotPx / 2f
        val f = (t / fadeAt).coerceAtMost(1f); val e = EASE_OUT.getInterpolation(f)
        p.color = color; p.alpha = (255 * startOp * (1f - e)).toInt()
        if (t < fadeAt) c.drawCircle(cx, cy, r * (1f + (grow - 1f) * e), p)
        if (!ringOnly) {
            val a = if (blink) { val k = if (t < .5f) t / .5f else (1f - t) / .5f; 1f - .55f * EASE_IN_OUT.getInterpolation(k) } else 1f
            p.alpha = (255 * a).toInt(); c.drawCircle(cx, cy, r, p)
        }
    }
}

/** Round photo with a fallback (the default lavender avatar) — any size, drawn round. */
class FacePic(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private var want: List<String> = emptyList()
    var bg: Int = Color.TRANSPARENT
    /** raws: small copy first, then the full photo (private photos are signed first) */
    fun setPhoto(raws: List<String>) {
        val list = raws.filter { it.isNotBlank() }.distinct()
        if (list == want && bmp != null) return
        want = list
        val size = max(width, Ui.dpi(48f))
        bmp = Photos.cached(list, size)
        invalidate()
        if (bmp == null && list.isNotEmpty()) Photos.loadRaw(list, size) { b -> if (want == list && b != null) { bmp = b; invalidate() } }
    }
    fun setBitmap(b: Bitmap?) { want = emptyList(); bmp = b; invalidate() }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        r.set(0f, 0f, s, s)
        if (bg != Color.TRANSPARENT) { p.color = bg; c.drawOval(r, p) }
        val b = bmp
        if (b != null) {
            p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val k = max(s / b.width, s / b.height)
                setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate((s - b.width * k) / 2f, (s - b.height * k) / 2f) })
            }
            c.drawOval(r, p); p.shader = null
        } else DefaultAvatar.draw(c, s)
    }
}

/** A picture from the app's assets, cut to fill (object-fit: cover), with rounded corners [radiusPx] (or round). */
class CoverPic(ctx: Context, private val asset: String, private val radiusPx: Float = 0f, private val round: Boolean = false) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    override fun onDraw(c: Canvas) {
        val b = bitmap ?: Tf.pic(context, asset) ?: return
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            val k = max(w / b.width, h / b.height)
            setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate((w - b.width * k) / 2f, (h - b.height * k) / 2f) })
        }
        r.set(0f, 0f, w, h)
        if (round) c.drawOval(r, p) else c.drawRoundRect(r, radiusPx, radiusPx, p)
        p.shader = null
    }
}

/** An SVG drawn to fill the view (meet), optional tint for currentColor. */
class SvgView(ctx: Context, var svg: Svg, var tint: Int = Color.BLACK, private val meet: Boolean = true) : View(ctx) {
    override fun onDraw(c: Canvas) = svg.draw(c, 0f, 0f, width.toFloat(), height.toFloat(), tint, meet = meet)
}

/** The web's flex rows with exact children sizes: a FrameLayout where each child sits at (x, y) in design px. */
class AbsBox(ctx: Context) : FrameLayout(ctx) {
    init { clipChildren = false; clipToPadding = false }
    fun put(v: View, x: Float, y: Float, w: Int, h: Int): View {
        addView(v, LayoutParams(w, h).apply { leftMargin = x.roundToInt(); topMargin = y.roundToInt() })
        return v
    }
}

// ═════════════════ animated pieces ═════════════════

/** Random Match: two rings growing and fading (2.2 s, second one 1.1 s later), the dashed ring turning (9 s), the swap arrows moving. */
class RmCircle(ctx: Context, private val m: Zm) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = Svg.of(HomeArt.RM_DASHED)
    private val swap = Svg.of(HomeArt.RM_SWAP)
    private var tRing = 0f; private var tSpin = 0f; private var tSwap = 0f
    private val anims = listOf(
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 2200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { tRing = it.animatedValue as Float } },
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 9000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { tSpin = it.animatedValue as Float } },
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { tSwap = it.animatedValue as Float; invalidate() } },
    )
    private fun sync() { val on = isAttachedToWindow && isShown && windowVisibility == VISIBLE; for (a in anims) if (on) { if (!a.isStarted) a.start() else if (a.isPaused) a.resume() } else if (a.isRunning) a.pause() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { sync() } }
    override fun onDetachedFromWindow() { anims.forEach { it.cancel() }; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = m.px(28f)
        // rings (scale 1 → 1.75, opacity .35 → 0, ease-out)
        for (d in floatArrayOf(0f, .5f)) {
            val f = (tRing + d) % 1f; val e = EASE_OUT.getInterpolation(f)
            p.color = Ui.c("#6C2BD9"); p.alpha = (255 * .35f * (1f - e)).toInt()
            c.drawCircle(cx, cy, r * (1f + .75f * e), p)
        }
        // dashed ring with the yellow dot, turning (76 box around the 64 box)
        c.save(); c.rotate(360f * tSpin, cx, cy)
        val s = m.px(76f); dashed.draw(c, cx - s / 2f, cy - s / 2f, s, s); c.restore()
        // purple circle with a white 3 px border
        p.alpha = 255; p.color = Color.WHITE; c.drawCircle(cx, cy, r, p)
        p.color = Ui.c("#6C2BD9"); c.drawCircle(cx, cy, r - m.px(3f), p)
        // swap arrows: translateX -2 → 2 → -2 (ease-in-out)
        val k = if (tSwap < .5f) tSwap / .5f else (1f - tSwap) / .5f
        val dx = m.px(-2f + 4f * EASE_IN_OUT.getInterpolation(k))
        val a = m.px(26f); swap.draw(c, cx - a / 2f + dx, cy - a / 2f, a, a)
    }
}

/** Live Room tile icon: the red radio waves with the blinking centre dot (1.2 s, 1 → .15 → 1). */
class LiveRoomIcon(ctx: Context, private val m: Zm) : LoopView(ctx, 1200) {
    private val waves = Svg.of(HomeArt.LIVE_ROOM_WAVES)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#DC2626") }
    override fun onDraw(c: Canvas) {
        waves.draw(c, 0f, 0f, width.toFloat(), height.toFloat())
        val k = if (t < .5f) t / .5f else (1f - t) / .5f
        p.alpha = (255 * (1f - .85f * EASE_IN_OUT.getInterpolation(k))).toInt()
        c.drawCircle(m.px(11.5f) + m.px(3.5f), m.px(11.5f) + m.px(3.5f), m.px(3.5f), p)
    }
}

/** Expert Talk tile: the headphones picture; a white patch fades in and out over its sound waves (1.8 s). */
class ExpertIcon(ctx: Context) : LoopView(ctx, 1800) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val b = Tf.pic(context, "home/expert_icon.jpg") ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val k = min(w / b.width, h / b.height)
        val dw = b.width * k; val dh = b.height * k
        r.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        c.drawBitmap(b, null, r, p)
        val f = if (t < .5f) t / .5f else (1f - t) / .5f
        p.color = Color.WHITE; p.alpha = (255 * .82f * EASE_IN_OUT.getInterpolation(f)).toInt()
        c.drawRect(w * .328f, h * .526f, w * (.328f + .343f), h * (.526f + .34f), p)
        p.alpha = 255
    }
}

/** Dashboard tile: purple bars; the yellow line draws itself left to right and rises (3.4 s), its arrow head appears. */
class DashIcon(ctx: Context) : LoopView(ctx, 3400) {
    private val bars = Svg.of(HomeArt.DASH_BARS)
    private val line = Svg.of(HomeArt.DASH_LINE)
    private val head = Svg.of(HomeArt.DASH_HEAD)
    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        bars.draw(c, 0f, 0f, s, s)
        // tfUpH: translate(-.625, 1.5) → (1.125, -2.75), opacity 0 → 1 (14 %) → 1 (76 %) → 0
        val k = s / 30f
        val dx = (-.625f + 1.75f * t) * k; val dy = (1.5f - 4.25f * t) * k
        val op = when { t < .14f -> t / .14f; t < .76f -> 1f; else -> 1f - (t - .76f) / .24f }
        // tfGr: the visible window's left edge (in % of 30) — the line shows from x = 0 to window + 30
        val gi = (t * 100f).coerceAtMost(64f)
        val i0 = gi.toInt().coerceIn(0, HomeMotion.GR.size - 1); val i1 = min(i0 + 1, HomeMotion.GR.size - 1)
        val g = HomeMotion.GR[i0] + (HomeMotion.GR[i1] - HomeMotion.GR[i0]) * (gi - i0)
        val right = (g / 100f) * s + s
        c.save(); c.translate(dx, dy)
        c.save(); c.clipRect(-s, -s, right, s * 2)
        line.draw(c, 0f, 0f, s, s, alpha = op)
        c.restore()
        // tfHead: 0 until 50 %, 1 from 68 %
        val ho = when { t < .5f -> 0f; t < .68f -> (t - .5f) / .18f; else -> 1f }
        head.draw(c, 0f, 0f, s, s, alpha = ho * op)
        c.restore()
    }
}

/**
 * Earn tile: light runs round its gold border (two white lines from the bottom-left
 * corner, one up the left side and along the top, one along the bottom and up the
 * right side, meeting top-right) and a gold star flashes at that corner (4.4 s).
 * Drawn over the tile, 14 px bigger on every side for the star.
 */
class EarnShine(ctx: Context, private val m: Zm) : LoopView(ctx, 4400) {
    private val a = Svg.of(HomeArt.EARN_BORDER_A)
    private val b = Svg.of(HomeArt.EARN_BORDER_B)
    private val ga = Svg.of(HomeArt.EARN_BORDER_A.replace("#FFFFFF", "#FBBF24").replace("stroke-width=\"2.400\"", "stroke-width=\"5\""))
    private val gb = Svg.of(HomeArt.EARN_BORDER_B.replace("#FFFFFF", "#FBBF24").replace("stroke-width=\"2.400\"", "stroke-width=\"5\""))
    private val star = Svg.of(HomeArt.EARN_STAR)
    private val clip = Path()
    private fun at(xs: FloatArray, ys: FloatArray, f: Float): Pair<Float, Float> {
        val x = (f / 0.005f).coerceIn(0f, (xs.size - 1).toFloat())
        val i0 = x.toInt(); val i1 = min(i0 + 1, xs.size - 1); val k = x - i0
        return (xs[i0] + (xs[i1] - xs[i0]) * k) to (ys[i0] + (ys[i1] - ys[i0]) * k)
    }
    override fun onDraw(c: Canvas) {
        val pad = m.px(14f)
        // the border box: the tile + 1.5 px each side (calc(100% + 3px) at -1.5px)
        val bx = pad - m.px(1.5f); val by = pad - m.px(1.5f)
        val bw = width - 2 * pad + m.px(3f); val bh = height - 2 * pad + m.px(3f)
        val op = when { t < .06f -> EASE_IN_OUT.getInterpolation(t / .06f); t < .48f -> 1f; t < .56f -> 1f - EASE_IN_OUT.getInterpolation((t - .48f) / .08f); else -> 0f }
        if (op > 0f) {
            val f = min(t, .56f)
            for ((pair, pts) in listOf((a to ga) to (HomeMotion.SH_A_X to HomeMotion.SH_A_Y), (b to gb) to (HomeMotion.SH_B_X to HomeMotion.SH_B_Y))) {
                val (px, py) = at(pts.first, pts.second, f)
                val cx = bx + bw * px / 100f; val cy = by + bh * py / 100f
                c.save()
                clip.reset(); clip.addCircle(cx, cy, m.px(15f), Path.Direction.CW); c.clipPath(clip)
                // drop-shadow(0 0 2px #FBBF24): a soft gold line under the white one
                pair.second.draw(c, bx, by, bw, bh, alpha = op * .45f, meet = false)
                pair.first.draw(c, bx, by, bw, bh, alpha = op, meet = false)
                c.restore()
            }
        }
        // tfStar: hidden to 48 %, pops (58 %: 1.15), wobbles and turns, gone by 92 %
        if (t in .48f..0.92f) {
            val (sc, rot, o) = when {
                t < .58f -> { val k = (t - .48f) / .10f; Triple(.2f + (1.15f - .2f) * k, 0f, k) }
                t < .68f -> { val k = (t - .58f) / .10f; Triple(1.15f + (.88f - 1.15f) * k, 12f * k, 1f) }
                t < .78f -> { val k = (t - .68f) / .10f; Triple(.88f + (1.05f - .88f) * k, 12f + 12f * k, 1f) }
                else -> { val k = (t - .78f) / .14f; Triple(1.05f + (.3f - 1.05f) * k, 24f + 21f * k, 1f - k) }
            }
            val s = m.px(39f)
            val cx = width - s / 2f; val cy = s / 2f
            c.save(); c.rotate(rot, cx, cy); c.scale(sc, sc, cx, cy)
            star.draw(c, cx - s / 2f, cy - s / 2f, s, s, alpha = o.coerceIn(0f, 1f))
            c.restore()
        }
    }
}

/** The Earn tile's coin stack (SVG) with the flat TF coin standing in front (the web's <image>). */
class EarnCoins(ctx: Context) : View(ctx) {
    private val svg = Svg.of(HomeArt.EARN_COINS)
    private val r = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        svg.draw(c, 0f, 0f, s, s)
        val coin = Tf.pic(context, "home/tf_coin_sm.png") ?: return
        // <image x=10.3 y=9.6 width=12 height=12.93 preserveAspectRatio="xMidYMin meet"> in the 24 box
        val k = s / 24f
        val bw = 12f * k; val bh = 12.93f * k
        val sc = min(bw / coin.width, bh / coin.height)
        val dw = coin.width * sc; val dh = coin.height * sc
        val x = 10.3f * k + (bw - dw) / 2f; val y = 9.6f * k
        r.set(x, y, x + dw, y + dh); c.drawBitmap(coin, null, r, p)
    }
}

/** The four little corner marks of the Live Rooms tiles (6 × 6, 1.5 px #C9B6F5, round 3). */
class CornerMarks(ctx: Context, private val m: Zm) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.c("#C9B6F5") }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        p.strokeWidth = m.px(1.5f)
        val off = m.px(7f) + m.px(.75f); val s = m.px(6f) - m.px(.75f); val r = m.px(3f)
        val bl = height - off
        // bottom-left: └
        path.reset(); path.moveTo(off, bl - s); path.lineTo(off, bl - r); path.quadTo(off, bl, off + r, bl); path.lineTo(off + s, bl); c.drawPath(path, p)
        // bottom-right: ┘
        val rr = width - off
        path.reset(); path.moveTo(rr, bl - s); path.lineTo(rr, bl - r); path.quadTo(rr, bl, rr - r, bl); path.lineTo(rr - s, bl); c.drawPath(path, p)
    }
}

// ═════════════════ the Verification bar and Daily Free Coins ═════════════════

/** The ring of steps round the shield (one segment per step; purple = True Find, pink = Secret Dating, amber = in review). */
class StepRing(ctx: Context) : View(ctx) {
    var tf: List<String> = listOf("todo", "todo", "todo")
    var sd: List<String> = listOf("todo")
    var glyph: Svg? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val k = width / 42f
        val marks = tf.map { it to Ui.c("#6C2BD9") } + sd.map { it to Ui.c("#DB2777") }
        val n = max(1, marks.size); val gap = 10f; val seg = (360f - n * gap) / n
        p.strokeWidth = 3.5f * k
        r.set((21f - 18.5f) * k, (21f - 18.5f) * k, (21f + 18.5f) * k, (21f + 18.5f) * k)
        marks.forEachIndexed { i, (mk, col) ->
            p.color = when (mk) { "done" -> col; "wait" -> Ui.c("#F59E0B"); else -> Ui.c("#E9E3F5") }
            c.drawArc(r, -90f + gap / 2f + i * (seg + gap), seg, false, p)
        }
        glyph?.let { g -> val s = 18f * k; g.draw(c, (width - s) / 2f, (height - s) / 2f, s, s) }
    }
}

object HomeGlyph {
    fun shield(c: String, filled: Boolean): Svg = Svg.of("<svg viewBox=\"0 0 24 24\"><path d=\"M12 2.7l7.4 2.8v5.6c0 4.6-3.1 8.5-7.4 9.8-4.3-1.3-7.4-5.2-7.4-9.8V5.5z\" fill=\"${if (filled) c else "none"}\" stroke=\"$c\" stroke-width=\"${if (filled) "0" else "2"}\" stroke-linejoin=\"round\"/><path d=\"M8.6 12l2.4 2.4 4.5-4.7\" fill=\"none\" stroke=\"${if (filled) "#FFFFFF" else c}\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>")
    fun tick(c: String = "#16A34A"): Svg = Svg.of("<svg viewBox=\"0 0 24 24\"><path d=\"M4.5 12.5l4.8 4.8L19.5 7\" fill=\"none\" stroke=\"$c\" stroke-width=\"3.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>")
}

/**
 * Daily Free Coins: the slim gold box (claim once every 24 hours): amount, day of the
 * 7-day streak with its bars, Claim / "Next reward in …" countdown / Claimed.
 */
@SuppressLint("ViewConstructor")
class DailyBox(ctx: Context, private val m: Zm, private val onClaim: () -> Unit) : FrameLayout(ctx) {
    private val coinsBox = AbsBox(ctx)
    private val doneBadge = FrameLayout(ctx)
    private val amount: TextView
    private val dayText: TextView
    private val bars = LinearLayout(ctx)
    private val bonus: TextView
    private val claimBtn: TextView
    private val waitBox = LinearLayout(ctx)
    private val waitText: TextView
    private val claimedText: TextView
    private var d: HomeScreen.Daily? = null
    private val tick = Runnable { refreshTimer() }

    init {
        clipChildren = true
        background = GradientDrawable().apply { setColor(Ui.c("#FFF6DE")); cornerRadius = m.px(18f); setStroke(max(1, m.pi(1f)), Ui.c("#F2D58A")) }
        clipToOutline = true
        // background circles (120 × 64, right -26)
        addView(SvgView(ctx, Svg.of(HomeArt.DAILY_BG)), LayoutParams(m.pi(120f), m.pi(64f), Gravity.END).apply { rightMargin = -m.pi(26f) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(m.pi(11f), 0, m.pi(12f), 0) }
        addView(row, LayoutParams(-1, -1))
        // two coins + the green tick when today's are taken
        coinsBox.put(CoverPicFit(ctx, "home/tf_coin_sm.png"), m.px(15f), 0f, m.pi(26f), m.pi(26f))
        coinsBox.put(CoverPicFit(ctx, "home/tf_coin_sm.png"), 0f, m.px(11f), m.pi(31f), m.pi(31f))
        doneBadge.background = Tf.circle(Ui.c("#16A34A")).apply { setStroke(m.pi(2f), Ui.c("#FFF6DE")) }
        doneBadge.addView(SvgView(ctx, Svg.of(HomeArt.CHECK_WHITE8)), LayoutParams(m.pi(8f), m.pi(8f), Gravity.CENTER))
        coinsBox.put(doneBadge, m.px(22f), m.px(27f), m.pi(16f), m.pi(16f))
        doneBadge.visibility = GONE
        row.addView(coinsBox, LinearLayout.LayoutParams(m.pi(42f), m.pi(42f)).apply { rightMargin = m.pi(11f) })
        // middle
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cap = Tf.tpx(ctx, "DAILY FREE COINS", m.px(10.5f), Ui.c("#8A5A00")).apply {
            letterSpacing = 0.9f / 10.5f
            compoundDrawablePadding = m.pi(4f)
            setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.STAR_GOLD10), 10f * m.z), null, null, null)
        }
        mid.addView(cap)
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        amount = Tf.tpx(ctx, "Checking…", m.px(17f), Ui.c("#3D2A00"))
        dayText = Tf.tpx(ctx, "", m.px(12f), Ui.c("#8A5A00")).apply { setPadding(m.pi(6f), 0, 0, m.pi(1f)) }
        line.addView(amount); line.addView(dayText)
        mid.addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(2f) })
        bars.orientation = LinearLayout.HORIZONTAL; bars.gravity = Gravity.CENTER_VERTICAL
        for (i in 1..7) bars.addView(View(ctx), LinearLayout.LayoutParams(m.pi(if (i == 7) 22f else 14f), m.pi(5f)).apply { if (i > 1) leftMargin = m.pi(3f) })
        bonus = Tf.tpx(ctx, "", m.px(10.5f), Ui.c("#8A5A00")).apply { setPadding(m.pi(6f), 0, 0, 0) }
        bars.addView(bonus)
        bars.visibility = GONE
        mid.addView(bars, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(4f) })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = m.pi(11f) })
        // right
        claimBtn = Tf.tpx(ctx, "Claim", m.px(14.5f), Color.WHITE).apply {
            gravity = Gravity.CENTER; background = Tf.boxPx(Ui.c("#1F1235"), m.px(18f)); setPadding(m.pi(18f), 0, m.pi(18f), 0)
            setOnClickListener { onClaim() }
        }
        Ui.pressable(claimBtn, 0.97f)
        row.addView(claimBtn, LinearLayout.LayoutParams(-2, m.pi(36f)))
        waitBox.orientation = LinearLayout.VERTICAL; waitBox.gravity = Gravity.END
        waitBox.addView(Tf.tpx(ctx, "Next reward in", m.px(10.5f), Ui.c("#8A6A1F")))
        waitText = Tf.tpx(ctx, "", m.px(14f), Ui.c("#3D2A00")).apply { fontFeatureSettings = "tnum" }
        waitBox.addView(waitText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(1f) })
        row.addView(waitBox)
        claimedText = Tf.tpx(ctx, "Claimed", m.px(12.5f), Ui.c("#15803D"))
        row.addView(claimedText)
        set(null)
    }

    fun set(daily: HomeScreen.Daily?) {
        d = daily
        refreshTimer()
    }

    private fun refreshTimer() {
        removeCallbacks(tick)
        val d = d
        val nextAt = d?.nextAt ?: 0L
        val waiting = d != null && !d.ready && nextAt > 0L
        val left = if (waiting) nextAt - System.currentTimeMillis() else 0L
        val ready = d != null && (d.ready || (waiting && left <= 0))
        val s = d?.streak
        val done = if (s != null) s.done.coerceIn(0, 7) else 0
        val next = s?.day ?: 1
        val shown = if (s != null) (if (done == 7 && next == 1 && !ready) 7 else if (next == 1) 0 else done) else 0
        amount.text = if (d != null) "${d.amount} coins" else "Checking…"
        val dt = if (d == null) "" else if (s != null) (if (done == 7 && !ready) "Streak done!" else "Day $next of 7") else if (ready) "Ready" else "Claimed today"
        dayText.text = dt
        dayText.visibility = if (dt.isEmpty()) GONE else VISIBLE
        dayText.setTextColor(if (s == null && !ready) Ui.c("#15803D") else Ui.c("#8A5A00"))
        doneBadge.visibility = if (!ready && d != null) VISIBLE else GONE
        if (s != null) {
            bars.visibility = VISIBLE
            for (i in 1..7) {
                val filled = i <= shown; val isNext = !filled && i == next
                bars.getChildAt(i - 1).background = Tf.boxPx(when { filled -> if (i == 7) Ui.c("#1F1235") else Ui.c("#D99A06"); isNext -> Ui.c("#F5C84C"); else -> Ui.c("#F2E3B6") }, m.px(3f))
            }
            bonus.text = "★ ${s.bonus}"
        } else bars.visibility = GONE
        claimBtn.visibility = if (ready) VISIBLE else GONE
        claimBtn.text = if (d?.busy == true) "Claiming…" else "Claim"
        claimBtn.alpha = if (d?.busy == true) 0.6f else 1f
        claimBtn.isEnabled = d?.busy != true
        waitBox.visibility = if (!ready && waiting && left > 0) VISIBLE else GONE
        claimedText.visibility = if (!ready && !(waiting && left > 0) && d != null) VISIBLE else GONE
        if (waiting && left > 0) {
            val t = (left / 1000).coerceAtLeast(0)
            waitText.text = "${t / 3600}h ${two(t % 3600 / 60)}m ${two(t % 60)}s"
            postDelayed(tick, 1000 - (System.currentTimeMillis() % 1000))
        }
    }
    private fun two(n: Long) = n.toString().padStart(2, '0')
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshTimer() }
}

/** An asset picture fitted (object-fit: contain), e.g. the TF coin. */
class CoverPicFit(ctx: Context, private val asset: String) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val b = Tf.pic(context, asset) ?: return
        val k = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * k; val h = b.height * k
        r.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        c.drawBitmap(b, null, r, p)
    }
}

// ═════════════════ rows that scroll sideways ═════════════════

/**
 * A row of cards scrolled left / right. snap = "proximity" (Online now: settles on a
 * card when close to one) or "glide" (Weekly Top Creators: a flick runs on up to 8
 * cards, slows down and settles on a card; a gentle swipe moves card by card).
 */
@SuppressLint("ViewConstructor")
class CardRow(ctx: Context, private val stepPx: () -> Int, private val glide: Boolean) : HorizontalScrollView(ctx) {
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = false; clipToPadding = false
        isFillViewport = false
        addView(row, LayoutParams(-2, -2))
    }
    private fun maxX() = max(0, row.width - (width - paddingLeft - paddingRight))
    fun runTo(target: Int, dur: Long = 0) {
        anim?.cancel()
        val from = scrollX; val to = target.coerceIn(0, maxX())
        if (abs(to - from) < 1) return
        val time = if (dur > 0) dur else min(1100L, max(280L, (abs(to - from) / Ui.density * 0.9f).toLong()))
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = time
            interpolator = android.view.animation.Interpolator { k -> 1f - (1f - k).let { it * it * it * it } }
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) anim?.cancel()
        return super.onInterceptTouchEvent(e)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { anim?.cancel(); parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1) // px per ms
                val v = vt?.xVelocity ?: 0f
                vt?.recycle(); vt = null
                super.onTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
                settle(v)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
    override fun fling(velocityX: Int) { /* settle() decides where it stops */ }
    private fun settle(vPxPerMs: Float) {
        val step = stepPx().coerceAtLeast(1)
        if (glide) {
            val v = vPxPerMs / Ui.density
            val throwPx = if (abs(v) < 0.5f) 0f else max(-8f * step, min(8f * step, v * 320f * Ui.density))
            runTo(((scrollX - throwPx) / step).roundToInt() * step)
        } else {
            // proximity: a flick glides on like a normal list, then settles on the nearest card if close
            val v = vPxPerMs
            val throwPx = if (abs(v / Ui.density) < 0.3f) 0f else v * 260f
            val target = (scrollX - throwPx).roundToInt().coerceIn(0, maxX())
            val near = (target.toFloat() / step).roundToInt() * step
            runTo(if (abs(near - target) < step * 0.35f) near else target)
        }
    }
}

@Suppress("unused") private val keepTU = TextUtils.TruncateAt.END
@Suppress("unused") private fun keepLG(): Shader = LinearGradient(0f, 0f, 1f, 1f, 0, 0, Shader.TileMode.CLAMP)
@Suppress("unused") private val keepPM = PathMeasure()
@Suppress("unused") private val keepIV: Class<*> = ImageView::class.java
@Suppress("unused") private val keepVG: Class<*> = ViewGroup::class.java
