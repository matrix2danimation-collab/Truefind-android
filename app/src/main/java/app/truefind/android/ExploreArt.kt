package app.truefind.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * The small doodles inside Explore's Voice / Video / Random buttons: each button has its own set (20 Nov).
 * Lines from Tabler Icons (tabler.io/icons, MIT License, Copyright (c) 2020-2026 Paweł Kuna) plus our own
 * heart, dot, ring and sparkle. Generated.
 */
object ExploreArt {
    const val VOICE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 107 74" fill="none" stroke="#16A34A" stroke-linecap="round" stroke-linejoin="round"><g transform="translate(11 12) rotate(-14) scale(0.792)" stroke-width="1.705"><path d="M-9.33-10.67h5.33l2.67 6.67-3.34 2a14.67 14.67 0 0 0 6.67 6.67l2-3.34 6.67 2.67v5.33a2.67 2.67 0 0 1-2.67 2.67 21.33 21.33 0 0 1-20-20 2.67 2.67 0 0 1 2.67-2.67"/><path d="M4-6.67a2.67 2.67 0 0 1 2.67 2.67"/><path d="M4-12a8 8 0 0 1 8 8"/></g><g transform="translate(96 13) rotate(16) scale(0.708)" stroke-width="1.906"><path d="M-4.14-8.97a4.14 4.14 0 0 1 4.14-4.13 4.14 4.14 0 0 1 4.14 4.13v6.9a4.14 4.14 0 0 1-4.14 4.14 4.14 4.14 0 0 1-4.14-4.14l0-6.9"/><path d="M-9.66-2.07a9.66 9.66 0 0 0 19.32 0"/><path d="M-5.52 13.1l11.04 0"/><path d="M0 7.59l0 5.51"/></g><g transform="translate(9 63) rotate(10) scale(0.667)" stroke-width="2.025"><path d="M-12 7.5a4.5 4.5 0 1 0 9 0 4.5 4.5 0 0 0-9 0"/><path d="M3 7.5a4.5 4.5 0 1 0 9 0 4.5 4.5 0 0 0-9 0"/><path d="M-3 7.5v-19.5h15v19.5"/><path d="M-3-6h15"/></g><g transform="translate(93 63) rotate(-8) scale(0.833)" stroke-width="1.62"><path d="M12.45 0h-2.77c-1.23 0-2.3-1.18-2.43-2.76-0.41-4.77-1.03-8.29-3.8-8.29s-3.45 4.95-3.45 11.05-0.69 11.05-3.45 11.05-3.39-3.52-3.8-8.29c-0.14-1.58-1.2-2.76-2.43-2.76h-2.77"/></g><g transform="translate(53 6) rotate(8) scale(0.333)" stroke-width="4.05"><path d="M0 10.94c-8.12-6.12-12.5-10.37-12.5-15.38a6.5 6.5 0 0 1 12.5-2.5 6.5 6.5 0 0 1 12.5 2.5c0 5-4.37 9.25-12.5 15.38z"/></g><g transform="translate(30 68) scale(0.125)" stroke-width="10.8"><path d="M-12 0a12 12 0 1 0 24 0 12 12 0 1 0-24 0z" fill="#16A34A" stroke="none"/></g><g transform="translate(76 5) scale(0.25)" stroke-width="5.4"><path d="M0-12c1.2 8.4 3.6 10.8 12 12-8.4 1.2-10.8 3.6-12 12-1.2-8.4-3.6-10.8-12-12 8.4-1.2 10.8-3.6 12-12z" fill="#16A34A" stroke="none"/></g><g transform="translate(60 70) scale(0.188)" stroke-width="7.2"><path d="M-12 0a12 12 0 1 0 24 0 12 12 0 1 0-24 0z"/></g></svg>"""
    const val VIDEO = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 107 74" fill="none" stroke="#FFFFFF" stroke-linecap="round" stroke-linejoin="round"><g transform="translate(11 12) rotate(-12) scale(0.792)" stroke-width="1.705"><path d="M4.48-2.99l6.79-3.39a1.49 1.49 0 0 1 2.16 1.33v10.1a1.49 1.49 0 0 1-2.16 1.33l-6.79-3.39v-5.98"/><path d="M-13.43-5.97a2.99 2.99 0 0 1 2.98-2.99h11.94a2.99 2.99 0 0 1 2.99 2.99v11.94a2.99 2.99 0 0 1-2.99 2.99h-11.94a2.99 2.99 0 0 1-2.98-2.99l0-11.94"/></g><g transform="translate(96 13) rotate(14) scale(0.708)" stroke-width="1.906"><path d="M-12-9a3 3 0 0 1 3-3h18a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3h-18a3 3 0 0 1-3-3l0-18"/><path d="M-6-12l0 24"/><path d="M6-12l0 24"/><path d="M-12-6l6 0"/><path d="M-12 6l6 0"/><path d="M-12 0l24 0"/><path d="M6-6l6 0"/><path d="M6 6l6 0"/></g><g transform="translate(96 62) scale(0.625)" stroke-width="2.16"><path d="M-10.37-12.77v25.54l20.74-12.77-20.74-12.77"/></g><g transform="translate(10 63) rotate(12) scale(0.708)" stroke-width="1.906"><path d="M-9.68-6.91h1.39a2.76 2.76 0 0 0 2.76-2.77 1.38 1.38 0 0 1 1.38-1.38h8.3a1.38 1.38 0 0 1 1.38 1.38 2.76 2.76 0 0 0 2.76 2.77h1.39a2.76 2.76 0 0 1 2.76 2.76v12.44a2.76 2.76 0 0 1-2.76 2.77h-19.36a2.76 2.76 0 0 1-2.76-2.77v-12.44a2.76 2.76 0 0 1 2.76-2.76"/><path d="M-3.46 4.15a4.84 4.84 0 0 0 6.92 0"/><path d="M4.15-1.38l0.01 0"/><path d="M-4.15-1.38l0.02 0"/></g><g transform="translate(54 6) rotate(-8) scale(0.333)" stroke-width="4.05"><path d="M0 10.94c-8.12-6.12-12.5-10.37-12.5-15.38a6.5 6.5 0 0 1 12.5-2.5 6.5 6.5 0 0 1 12.5 2.5c0 5-4.37 9.25-12.5 15.38z"/></g><g transform="translate(76 68) scale(0.25)" stroke-width="5.4"><path d="M0-12c1.2 8.4 3.6 10.8 12 12-8.4 1.2-10.8 3.6-12 12-1.2-8.4-3.6-10.8-12-12 8.4-1.2 10.8-3.6 12-12z" fill="#FFFFFF" stroke="none"/></g><g transform="translate(30 6) scale(0.125)" stroke-width="10.8"><path d="M-12 0a12 12 0 1 0 24 0 12 12 0 1 0-24 0z" fill="#FFFFFF" stroke="none"/></g><g transform="translate(34 70) scale(0.188)" stroke-width="7.2"><path d="M-12 0a12 12 0 1 0 24 0 12 12 0 1 0-24 0z"/></g></svg>"""
    const val RANDOM = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 107 74" fill="none" stroke="#6C04CB" stroke-linecap="round" stroke-linejoin="round"><g transform="translate(11 12) rotate(-14) scale(0.75)" stroke-width="1.8"><path d="M-12-9.33a2.67 2.67 0 0 1 2.67-2.67h18.66a2.67 2.67 0 0 1 2.67 2.67v18.66a2.67 2.67 0 0 1-2.67 2.67h-18.66a2.67 2.67 0 0 1-2.67-2.67v-18.66"/><path d="M-5.33-4.67a0.67 0.67 0 1 0 1.33 0 0.67 0.67 0 1 0-1.33 0"/><path d="M4-4.67a0.67 0.67 0 1 0 1.33 0 0.67 0.67 0 1 0-1.33 0"/><path d="M4 4.67a0.67 0.67 0 1 0 1.33 0 0.67 0.67 0 1 0-1.33 0"/><path d="M-5.33 4.67a0.67 0.67 0 1 0 1.33 0 0.67 0.67 0 1 0-1.33 0"/><path d="M-0.67 0a0.67 0.67 0 1 0 1.34 0 0.67 0.67 0 1 0-1.34 0"/></g><g transform="translate(96 13) rotate(10) scale(0.708)" stroke-width="1.906"><path d="M6.47 8.63a2.88 2.88 0 0 1 2.88 2.88 2.88 2.88 0 0 1 2.88-2.88 2.88 2.88 0 0 1-2.88-2.87 2.88 2.88 0 0 1-2.88 2.87m0-17.26a2.88 2.88 0 0 1 2.88 2.87 2.88 2.88 0 0 1 2.88-2.87 2.88 2.88 0 0 1-2.88-2.88 2.88 2.88 0 0 1-2.88 2.88m-10.07 17.26a8.63 8.63 0 0 1 8.64-8.63 8.63 8.63 0 0 1-8.64-8.63 8.63 8.63 0 0 1-8.63 8.63 8.63 8.63 0 0 1 8.63 8.63"/></g><g transform="translate(96 62) scale(0.708)" stroke-width="1.906"><path d="M8.29-11.06l4.15 4.15-4.15 4.15"/><path d="M8.29 11.06l4.15-4.15-4.15-4.15"/><path d="M-12.44-6.91h4.15a6.91 6.91 0 0 1 6.91 6.91 6.91 6.91 0 0 0 6.91 6.91h6.91"/><path d="M12.44-6.91h-6.91a6.88 6.88 0 0 0-4.15 1.38m-5.53 11.06a6.89 6.89 0 0 1-4.14 1.38h-4.15"/></g><g transform="translate(10 63) rotate(10) scale(0.708)" stroke-width="1.906"><path d="M2.8 8.3l-2.79 2.76-10.36-10.26a6.91 6.91 0 1 1 10.36-9.07 6.91 6.91 0 0 1 11.26 7.99"/><path d="M5.52 11.06l5.8-5.83a3.88 3.88 0 0 0 0-5.46 3.8 3.8 0 0 0-5.4-0.01l-0.39 0.39-0.38-0.39a3.8 3.8 0 0 0-5.4-0.01 3.88 3.88 0 0 0-0.01 5.46l5.77 5.85 0.01 0"/></g><g transform="translate(54 6) scale(0.25)" stroke-width="5.4"><path d="M0-12v24m-12-12h24"/></g><g transform="translate(76 69) scale(0.125)" stroke-width="10.8"><path d="M-12 0a12 12 0 1 0 24 0 12 12 0 1 0-24 0z" fill="#6C04CB" stroke="none"/></g><g transform="translate(30 6) scale(0.188)" stroke-width="7.2"><path d="M-12 0a12 12 0 1 0 24 0 12 12 0 1 0-24 0z"/></g><g transform="translate(36 69) scale(0.25)" stroke-width="5.4"><path d="M0-12c1.2 8.4 3.6 10.8 12 12-8.4 1.2-10.8 3.6-12 12-1.2-8.4-3.6-10.8-12-12 8.4-1.2 10.8-3.6 12-12z" fill="#6C04CB" stroke="none"/></g></svg>"""
}

/**
 * The faint doodles behind a button's words: the picture covers the whole button (scaled the same both ways,
 * centred, the overflow cut off), is cut at the button's rounded corners and shows at [strength] (0..1).
 * Drawn once per size into a small picture, so showing the button costs one bitmap.
 */
class ButtonDoodles(private val svg: Svg, private val strength: Float, private val radiusDp: Float) : Drawable() {
    private var pic: Bitmap? = null
    private var own = 1f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onBoundsChange(bounds: Rect) { pic = null }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width(); val h = b.height()
        if (w <= 0 || h <= 0 || svg.vbW <= 0f || svg.vbH <= 0f) return
        val p = pic ?: make(w, h).also { pic = it }
        paint.alpha = (255 * strength * own).roundToInt().coerceIn(0, 255)
        c.drawBitmap(p, b.left.toFloat(), b.top.toFloat(), paint)
    }

    private fun make(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = Ui.dp(radiusDp)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW) })
        val s = maxOf(w / svg.vbW, h / svg.vbH)
        svg.draw(c, (w - svg.vbW * s) / 2f, (h - svg.vbH * s) / 2f, svg.vbW * s, svg.vbH * s, meet = false)
        return out
    }

    override fun setAlpha(alpha: Int) { own = alpha / 255f; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A soft patch of one colour that fades out towards its edges (an oval as wide and as tall as the view).
 * Explore puts it behind its "no one online" note, so the words read cleanly over the wallpaper's doodles.
 * [strength] is how solid the middle is (0..1); it stays that solid up to [solid] of the way out.
 */
class SoftPatch(color: Int, strength: Float, private val solid: Float) : Drawable() {
    private val clear = color and 0x00FFFFFF
    private val full = clear or ((255 * strength).roundToInt().coerceIn(0, 255) shl 24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.width() <= 0 || bounds.height() <= 0) { paint.shader = null; return }
        paint.shader = RadialGradient(0f, 0f, 1f, intArrayOf(full, full, clear), floatArrayOf(0f, solid, 1f), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(bounds.width() / 2f, bounds.height() / 2f); postTranslate(bounds.exactCenterX(), bounds.exactCenterY()) })
        }
    }

    override fun draw(c: Canvas) { if (paint.shader != null) c.drawRect(bounds, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
