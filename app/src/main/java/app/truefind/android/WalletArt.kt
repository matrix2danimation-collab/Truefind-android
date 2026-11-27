package app.truefind.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.PathParser

/**
 * The Wallet top's coin picture (owner's design 1, 27 Nov): the 3D TF coin standing up in front of two TF coins
 * lying flat, a soft light behind them and four white sparkles. Drawn from the owner's own coin pictures.
 */
class WalletHeaderCoins(ctx: Context) : View(ctx) {
    private val coin3d: Bitmap? = WCoins.big3d(ctx)
    private val flat: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.wallet_coin) } catch (e: Exception) { null }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val star = Path()

    init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // soft light behind the coins
        // (kept inside the picture's box so no square edge shows)
        val gr = Math.min(w * 0.44f, h * 0.5f)
        p.shader = RadialGradient(w * 0.56f, h * 0.5f, gr, Color.argb(60, 220, 200, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.56f, h * 0.5f, gr, p)
        p.shader = null
        // shadow on the ground
        p.shader = RadialGradient(w * 0.55f, h * 0.9f, w * 0.44f, Color.argb(80, 30, 5, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.save(); c.scale(1f, 0.2f, w * 0.55f, h * 0.9f); c.drawCircle(w * 0.55f, h * 0.9f, w * 0.44f, p); c.restore()
        p.shader = null
        // two coins lying flat (back right, then front left)
        lying(c, w * 0.75f, h * 0.66f, w * 0.44f, h * 0.24f, h * 0.10f)
        lying(c, w * 0.30f, h * 0.71f, w * 0.50f, h * 0.27f, h * 0.11f)
        // the 3D coin standing in front, leaning a little
        coin3d?.let { b ->
            val ch = h * 0.76f
            val cw = ch * b.width / b.height
            val cx = w * 0.56f; val cy = h * 0.45f
            c.save()
            c.rotate(-8f, cx, cy)
            val dst = RectF(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
            c.drawBitmap(WCoins.sharp(b, dst.width().toInt().coerceAtLeast(1), dst.height().toInt().coerceAtLeast(1)), null, dst, p)
            c.restore()
        }
        // sparkles
        sparkle(c, w * 0.20f, h * 0.17f, h * 0.085f)
        sparkle(c, w * 0.10f, h * 0.36f, h * 0.07f)
        sparkle(c, w * 0.31f, h * 0.05f, h * 0.06f)
        sparkle(c, w * 0.93f, h * 0.78f, h * 0.065f)
    }

    /** A coin lying flat: its gold side, then the coin's own face squashed into an oval on top. */
    private fun lying(c: Canvas, cx: Float, cy: Float, cw: Float, faceH: Float, thick: Float) {
        val face = RectF(cx - cw / 2f, cy - faceH / 2f, cx + cw / 2f, cy + faceH / 2f)
        // the side (edge): an oval under the face, filled between them
        p.shader = android.graphics.LinearGradient(face.left, 0f, face.right, 0f,
            intArrayOf(Color.parseColor("#9A5B05"), Color.parseColor("#D89A1E"), Color.parseColor("#A86A08")), null, Shader.TileMode.CLAMP)
        val side = Path().apply {
            addOval(RectF(face.left, face.top + thick, face.right, face.bottom + thick), Path.Direction.CW)
            addRect(RectF(face.left, cy, face.right, cy + thick), Path.Direction.CW)
        }
        c.drawPath(side, p)
        p.shader = null
        val b = flat
        if (b != null) {
            c.drawBitmap(b, null, face, p)
        } else {
            p.color = Color.parseColor("#F2B734"); c.drawOval(face, p)
        }
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, r: Float) {
        val k = r * 0.22f
        star.reset()
        star.moveTo(x, y - r)
        star.quadTo(x + k, y - k, x + r, y)
        star.quadTo(x + k, y + k, x, y + r)
        star.quadTo(x - k, y + k, x - r, y)
        star.quadTo(x - k, y - k, x, y - r)
        star.close()
        p.color = Color.WHITE
        p.setShadowLayer(r * 0.5f, 0f, 0f, Color.argb(150, 255, 255, 255))
        c.drawPath(star, p)
        p.clearShadowLayer()
    }
}

/** A filled icon (SVG path on a [box] × [box] grid), e.g. the phone and camera on the Wallet top's minute chips. */
class WFillIcon(ctx: Context, data: String, private val color: Int, private val box: Float = 24f) : View(ctx) {
    private val path: Path = try { PathParser.createPathFromPathData(data) } catch (e: Exception) { Path() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@WFillIcon.color; style = Paint.Style.FILL }
    init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height) / box
        c.save(); c.translate((width - box * s) / 2f, (height - box * s) / 2f); c.scale(s, s); c.drawPath(path, p); c.restore()
    }

    companion object {
        const val PHONE = "M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z"
        /** a filled video camera */
        const val VIDEO = "M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM16 10l5-3.2v10.4L16 14z"
    }
}

/**
 * Holds the Wallet's Pay bar. A finger that starts on the bar and moves up or down scrolls the page (owner, 27 Nov:
 * with the bar showing, a swipe that began on it did not scroll); a plain tap still presses the bar / Pay button.
 */
class WPayDock(ctx: Context, private val target: () -> View?) : android.widget.FrameLayout(ctx) {
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = Math.abs(e.rawY - downY); val dx = Math.abs(e.rawX - downX)
                if (!dragging && dy > slop && dy > dx) {
                    dragging = true
                    // the page gets a finger-down where the finger started, then this move: it scrolls from here on
                    send(e, android.view.MotionEvent.ACTION_DOWN, downX, downY)
                    send(e, android.view.MotionEvent.ACTION_MOVE, e.rawX, e.rawY)
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!dragging) return false
        send(e, e.actionMasked.let { if (it == android.view.MotionEvent.ACTION_POINTER_UP || it == android.view.MotionEvent.ACTION_POINTER_DOWN) android.view.MotionEvent.ACTION_MOVE else it }, e.rawX, e.rawY)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) dragging = false
        return true
    }

    private fun send(src: android.view.MotionEvent, action: Int, rawX: Float, rawY: Float) {
        val t = target() ?: return
        val loc = IntArray(2); t.getLocationOnScreen(loc)
        val ev = android.view.MotionEvent.obtain(src.downTime, src.eventTime, action, rawX - loc[0], rawY - loc[1], 0)
        t.dispatchTouchEvent(ev)
        ev.recycle()
    }
}

/** A bright band of light that runs once across the Pay bar when it appears (like payment apps' buttons). */
class WShine(private val radiusPx: Float) : android.graphics.drawable.Drawable() {
    var progress = -1f
        set(v) { field = v; invalidateSelf() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    override fun draw(c: Canvas) {
        if (progress < 0f || progress > 1f) return
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val band = w * 0.28f
        val x = b.left - band + (w + band * 2f) * progress
        clip.reset(); clip.addRoundRect(RectF(b), radiusPx, radiusPx, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        p.shader = android.graphics.LinearGradient(x - band / 2f, 0f, x + band / 2f, h * 0.35f,
            intArrayOf(Color.TRANSPARENT, Color.argb(95, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(RectF(b), p)
        c.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
