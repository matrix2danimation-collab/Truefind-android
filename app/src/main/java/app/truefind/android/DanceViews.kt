package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A round face (Avatar in LiveRooms.jsx): the photo, or the default picture.
 * [ringDp] draws the coloured ring around it (outside the face, inside the view).
 */
class AvatarView(ctx: Context, private val faceDp: Float, private val ring: Int? = null, ringDp: Float = 0f, private val online: Boolean = false) : View(ctx) {
    private val ringPx = if (ring != null) Ui.dp(if (ringDp > 0f) ringDp else max(2f, faceDp * 0.05f)) else 0f
    private val facePx = Ui.dpi(faceDp)
    private var src = ""
    private var bmp: Bitmap? = null
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring ?: Color.TRANSPARENT }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#22c55e") }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val clip = Path()
    var showOnline = false
        set(v) { field = v; invalidate() }

    /** total size of the view (face + ring on both sides) */
    val sizePx: Int get() = (facePx + 2 * ringPx).toInt()

    fun set(photo: String?) {
        val s = photo.orEmpty()
        if (s == src && (bmp != null || s.isEmpty())) return
        src = s
        bmp = DanceImages.cached(s, facePx, true)
        invalidate()
        if (bmp == null && s.isNotEmpty()) DanceImages.load(s, facePx, true) { b -> if (src == s) { bmp = b; invalidate() } }
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(sizePx, sizePx) }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = facePx / 2f
        if (ring != null) c.drawCircle(cx, cy, r + ringPx, ringPaint)
        val b = bmp
        if (b != null) {
            c.save(); c.translate(cx - r, cy - r); c.scale(facePx.toFloat() / b.width, facePx.toFloat() / b.height)
            c.drawBitmap(b, 0f, 0f, bmpPaint); c.restore()
        } else {
            clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW)
            c.save(); c.clipPath(clip); DefaultFace.draw(c, cx - r, cy - r, r * 2, r * 2); c.restore()
        }
        if (online || showOnline) {
            val d = max(Ui.dp(9f), facePx * 0.24f)
            val x = cx + r - d / 2f - facePx * 0.02f; val y = cy + r - d / 2f - facePx * 0.04f
            c.drawCircle(x, y, d / 2f + Ui.dp(2f), dotRing); c.drawCircle(x, y, d / 2f, dot)
        }
    }
}

/**
 * A wide photo that fills its box (object-fit: cover), or the default picture.
 * [blur] shows a soft, darker copy (camera off / pay screen preview).
 */
class CoverView(ctx: Context) : View(ctx) {
    private var src = ""
    private var bmp: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    var blur = 0f            // 0 none, otherwise a soft picture
        set(v) { field = v; reload() }
    var dim = 1f             // brightness 1 = normal
        set(v) {
            field = v
            p.colorFilter = if (v >= 0.999f) null else ColorMatrixColorFilter(ColorMatrix().apply { setScale(v, v, v, 1f) })
            invalidate()
        }
    var zoom = 1f
        set(v) { field = v; invalidate() }
    var fallbackFace = true  // draw the default picture when there is no photo

    fun set(photo: String?) {
        val s = photo.orEmpty()
        if (s == src && bmp != null) return
        src = s
        reload()
    }

    private fun wantPx(): Int = if (blur > 0f) Ui.dpi(36f) else max(Ui.dpi(120f), min(max(width, height), Ui.dpi(720f)))

    private fun reload() {
        val s = src
        if (s.isEmpty()) { bmp = null; invalidate(); return }
        if (width == 0 && height == 0) { post { if (src == s) reload() }; return }
        val px = wantPx()
        bmp = DanceImages.cached(s, px, false)
        invalidate()
        if (bmp == null) DanceImages.load(s, px, false) { b -> if (src == s) { bmp = b; invalidate() } }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); if (ow == 0 && oh == 0) reload() }

    override fun onDraw(c: Canvas) {
        val b = bmp
        if (b == null) {
            if (fallbackFace && width > 0) DefaultFace.draw(c, 0f, 0f, width.toFloat(), height.toFloat())
            return
        }
        val s = max(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
        m.reset(); m.setScale(s, s)
        m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        c.drawBitmap(b, m, p)
    }
}

/** A vertical see-through → dark fade drawn over pictures (the stage's gradients). */
class ShadeView(ctx: Context, private val stops: IntArray, private val pos: FloatArray) : View(ctx) {
    private val p = Paint()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), stops, pos, Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
}

/** A box cut on a slant (the two halves of a battle stage, clip-path in the web). */
class SlantFrame(ctx: Context, private val left: Boolean) : FrameLayout(ctx) {
    private val path = Path()
    init { setWillNotDraw(false) }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        path.reset()
        if (left) { path.moveTo(0f, 0f); path.lineTo(w.toFloat(), 0f); path.lineTo(w * 0.88f, h.toFloat()); path.lineTo(0f, h.toFloat()) }
        else { path.moveTo(w * 0.12f, 0f); path.lineTo(w.toFloat(), 0f); path.lineTo(w.toFloat(), h.toFloat()); path.lineTo(0f, h.toFloat()) }
        path.close()
    }
    override fun dispatchDraw(c: Canvas) {
        c.save(); c.clipPath(path); super.dispatchDraw(c); c.restore()
    }
    override fun draw(c: Canvas) {
        c.save(); c.clipPath(path); super.draw(c); c.restore()
    }
}

/** A soft light beam on the stage that slowly fades in and out (ld-beam). */
class BeamView(ctx: Context, private val color: Int, delay: Long) : View(ctx) {
    private val p = Paint()
    private val anim = ValueAnimator.ofFloat(0.35f, 0.75f).apply {
        duration = 1300; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; startDelay = delay
        addUpdateListener { alpha = it.animatedValue as Float }
    }
    init { alpha = 0.35f }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), color, Color.TRANSPARENT, Shader.TileMode.CLAMP) }
    override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}

/**
 * Floating hearts (likes): each one rises, drifts and fades, like ld-heart.
 * One view draws them all; it only runs while hearts are in the air.
 */
class HeartsView(ctx: Context) : View(ctx) {
    private class H(val x: Float, val dx: Float, val delay: Long, val size: Float, val e: String, val born: Long)
    private val list = ArrayList<H>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ease = PathInterpolator(0.3f, 0.7f, 0.4f, 1f)
    private val emojis = listOf("💗", "💖", "💜", "❤️", "💕")
    private val rnd = java.util.Random()

    fun add(n: Int) {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until min(8, n)) list.add(H(rnd.nextFloat() * 30f, -40f + rnd.nextFloat() * 50f, i * 140L, 22f + rnd.nextFloat() * 12f, emojis[rnd.nextInt(emojis.size)], now))
        while (list.size > 24) list.removeAt(0)
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val it = list.iterator()
        while (it.hasNext()) {
            val h = it.next()
            val t = (now - h.born - h.delay) / 2300f
            if (t >= 1f) { it.remove(); continue }
            if (t < 0f) continue
            val k = ease.getInterpolation(t)
            val alpha = when { t < 0.12f -> t / 0.12f; else -> 1f - (t - 0.12f) / 0.88f }
            val scale = if (t < 0.12f) 0.6f + 0.4f * (t / 0.12f) else 1f + 0.15f * ((t - 0.12f) / 0.88f)
            val y = height - Ui.dp(240f) * k - (if (t < 0.12f) Ui.dp(20f) * (t / 0.12f) else 0f)
            val x = Ui.dp(h.x) + Ui.dp(h.dx) * k + Ui.dp(10f)
            p.textSize = Ui.dp(h.size) * scale
            p.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
            c.drawText(h.e, x, y, p)
        }
        if (list.isNotEmpty()) postInvalidateOnAnimation()
    }
}

/** The heart-tap burst (heartBurst.js): blue/pink lines and yellow/teal dots fly out. */
class BurstView(ctx: Context) : View(ctx) {
    private var start = 0L
    private var cx = 0f; private var cy = 0f; private var s = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val lines = listOf(Triple(Ui.c("#1EB4F0"), 0f, -1f), Triple(Ui.c("#1EB4F0"), 0f, 1f), Triple(Ui.c("#F0105A"), -1f, 0f), Triple(Ui.c("#F0105A"), 1f, 0f))
    private val dots = listOf(Triple(Ui.c("#FFC72C"), -0.75f, -0.75f), Triple(Ui.c("#1EE6C0"), 0.75f, -0.75f), Triple(Ui.c("#1EE6C0"), -0.75f, 0.75f), Triple(Ui.c("#FFC72C"), 0.75f, 0.75f))

    /** burst around [target] (a view inside this view's parent space) */
    fun burst(target: View) {
        val a = IntArray(2); val b = IntArray(2)
        target.getLocationInWindow(a); getLocationInWindow(b)
        cx = a[0] - b[0] + target.width / 2f; cy = a[1] - b[1] + target.height / 2f
        s = max(Ui.dp(26f), min(target.width, target.height) * 0.75f)
        start = SystemClock.uptimeMillis()
        target.animate().cancel()
        target.animate().scaleX(0.8f).scaleY(0.8f).setDuration(150).withEndAction {
            target.animate().scaleX(1.2f).scaleY(1.2f).setDuration(190).withEndAction { target.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }.start()
        }.start()
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        if (start == 0L) return
        val el = SystemClock.uptimeMillis() - start - 280
        if (el > 820) { start = 0L; return }
        val reach = s * 1.05f
        if (el >= 0) {
            val tl = (el / 750f).coerceIn(0f, 1f)
            for ((col, dx, dy) in lines) {
                val k = if (tl < 0.25f) 0.25f + (0.6f - 0.25f) * (tl / 0.25f) else 0.6f + 0.4f * ((tl - 0.25f) / 0.75f)
                val sx = if (tl < 0.25f) 0.3f + 0.7f * (tl / 0.25f) else 1f - 0.8f * ((tl - 0.25f) / 0.75f)
                val a = if (tl < 0.25f) 1f else 1f - (tl - 0.25f) / 0.75f
                p.color = col; p.alpha = (255 * a).toInt(); p.strokeWidth = max(Ui.dp(3f), s * 0.085f)
                val len = s * 0.42f * sx / 2f
                val x = cx + dx * reach * k; val y = cy + dy * reach * k
                if (dx == 0f) c.drawLine(x, y - len, x, y + len, p) else c.drawLine(x - len, y, x + len, y, p)
            }
            val td = (el / 800f).coerceIn(0f, 1f)
            p.style = Paint.Style.FILL
            for ((col, dx, dy) in dots) {
                val k = if (td < 0.2f) 0.6f * (td / 0.2f) else 0.6f + 0.4f * ((td - 0.2f) / 0.8f)
                val sc = if (td < 0.2f) 0.3f + 0.7f * (td / 0.2f) else 1f - 0.5f * ((td - 0.2f) / 0.8f)
                val a = if (td < 0.2f) td / 0.2f else 1f - (td - 0.2f) / 0.8f
                p.color = col; p.alpha = (255 * a.coerceIn(0f, 1f)).toInt()
                c.drawCircle(cx + dx * reach * k, cy + dy * reach * k, max(Ui.dp(4f), s * 0.13f) / 2f * sc, p)
            }
            p.style = Paint.Style.STROKE
        }
        postInvalidateOnAnimation()
    }
}

/** Confetti for the winner (ld-conf): 22 pieces falling and turning. */
class ConfettiView(ctx: Context) : View(ctx) {
    private val colors = intArrayOf(Ui.c("#ffd166"), Ui.c("#c084fc"), Ui.c("#f472b6"), Ui.c("#60a5fa"), Ui.c("#34d399"))
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var start = 0L
    private val r = RectF()
    fun play() { start = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }
    override fun onDraw(c: Canvas) {
        if (start == 0L) return
        val el = SystemClock.uptimeMillis() - start
        var alive = false
        for (i in 0 until 22) {
            val t = (el - (i % 7) * 120L) / 2600f
            if (t < 0f) { alive = true; continue }
            if (t > 1f) continue
            alive = true
            val x = width * ((i * 37) % 100) / 100f
            val y = -Ui.dp(20f) + Ui.dp(440f) * (t * t)
            p.color = colors[i % colors.size]; p.alpha = (255 * (1f - t)).toInt()
            c.save(); c.translate(x, y); c.rotate(540f * t)
            r.set(-Ui.dp(4f), -Ui.dp(7f), Ui.dp(4f), Ui.dp(7f))
            c.drawRoundRect(r, Ui.dp(2f), Ui.dp(2f), p)
            c.restore()
        }
        if (alive) postInvalidateOnAnimation() else start = 0L
    }
}

/** Two-colour vote bars with the % (VoteBars), animated when the votes move. */
class VoteBarsView(ctx: Context) : View(ctx) {
    private var target = 50f
    private var cur = 50f
    private var show = false
    private var anim: ValueAnimator? = null
    private val pa = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(1f); color = Ui.ca("#ffffff", 0.35f) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Ui.bold; textSize = Ui.dp(15f) }
    private val ra = RectF(); private val rb = RectF()
    private val path = Path()

    fun set(a: Int, visible: Boolean) {
        show = visible
        val t = if (visible) a.toFloat() else 50f
        if (t != target) {
            target = t
            anim?.cancel()
            anim = ValueAnimator.ofFloat(cur, t).apply { duration = 600; addUpdateListener { cur = it.animatedValue as Float; invalidate() }; start() }
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val gap = Ui.dp(4f)
        val frac = cur.coerceIn(14f, 86f) / 100f
        val wa = (w - gap) * frac
        ra.set(0f, 0f, wa, h); rb.set(wa + gap, 0f, w, h)
        pa.shader = LinearGradient(0f, 0f, wa, 0f, DK.BAR_A[0], DK.BAR_A[1], Shader.TileMode.CLAMP)
        pb.shader = LinearGradient(rb.left, 0f, w, 0f, DK.BAR_B[0], DK.BAR_B[1], Shader.TileMode.CLAMP)
        val big = h / 2f; val small = Ui.dp(4f)
        path.reset(); path.addRoundRect(ra, floatArrayOf(big, big, small, small, small, small, big, big), Path.Direction.CW); c.drawPath(path, pa); c.drawPath(path, edge)
        path.reset(); path.addRoundRect(rb, floatArrayOf(small, small, big, big, big, big, small, small), Path.Direction.CW); c.drawPath(path, pb); c.drawPath(path, edge)
        val la = if (show) "${cur.toInt()}%" else "—"
        val lb = if (show) "${100 - cur.toInt()}%" else "—"
        val ty = h / 2f + txt.textSize * 0.36f
        txt.textAlign = Paint.Align.LEFT; c.drawText(la, Ui.dp(10f), ty, txt)
        txt.textAlign = Paint.Align.RIGHT; c.drawText(lb, w - Ui.dp(10f), ty, txt)
    }
}

/** the soft purple/gold glow ring around the big gift button (ld-glow), pulsing */
class GlowRing(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var start = 0L
    override fun onAttachedToWindow() { super.onAttachedToWindow(); start = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }
    override fun onDraw(c: Canvas) {
        val t = ((SystemClock.uptimeMillis() - start) % 2000L) / 2000f
        val k = (1f - kotlin.math.cos(t * 2 * Math.PI).toFloat()) / 2f // 0 → 1 → 0
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - Ui.dp(10f)
        p.style = Paint.Style.FILL
        p.shader = android.graphics.RadialGradient(cx, cy, r + Ui.dp(10f + 6f * k), intArrayOf(Ui.ca("#aa46ff", 0.55f + 0.25f * k), Color.TRANSPARENT), floatArrayOf(r / (r + Ui.dp(10f + 6f * k)), 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r + Ui.dp(10f + 6f * k), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = Ui.dp(3f + 2f * k); p.color = Ui.ca("#ffd666", 0.55f + 0.25f * k)
        c.drawCircle(cx, cy, r + p.strokeWidth / 2f, p)
        if (isShown) postInvalidateOnAnimation()
    }
}

/** runs [a] while the view is on screen (stops when it leaves, starts again when it comes back) */
private fun View.whileAttached(a: ValueAnimator): ValueAnimator {
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { if (!a.isStarted) a.start() }
        override fun onViewDetachedFromWindow(v: View) { a.cancel() }
    })
    if (isAttachedToWindow) a.start()
    return a
}

/** wobble of the VS letters (ld-vs) */
@SuppressLint("ObjectAnimatorBinding")
fun View.vsWobble(): ValueAnimator = whileAttached(ValueAnimator.ofFloat(0f, 1f).apply {
    duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
    addUpdateListener {
        if (!isShown) return@addUpdateListener
        val t = it.animatedValue as Float
        val k = (sin(t * 2 * Math.PI - Math.PI / 2).toFloat() + 1f) / 2f
        rotation = -4f + 7f * k; scaleX = 1f + 0.08f * k; scaleY = scaleX
    }
})

/** float up and down (ld-crown) */
fun View.bob(): ValueAnimator = whileAttached(ValueAnimator.ofFloat(0f, -Ui.dp(4f)).apply {
    duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
    addUpdateListener { translationY = it.animatedValue as Float }
})
