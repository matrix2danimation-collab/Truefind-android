package app.truefind.android.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.View
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG

/**
 * The web games draw their pictures (game art, the Dress Code doll, Lucide icons) as SVG.
 * Here the phone draws the SAME SVG itself (AndroidSVG, no web page): rendered once at the
 * size it is shown, then kept as a picture, so scrolling and animating never redraw it.
 */
object Svg {
    private val parsed = object : LruCache<String, SVG>(160) {}
    private val parsedBg = object : LruCache<String, SVG>(60) {}
    private val bitmaps = object : LruCache<String, Bitmap>(36 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // big pictures are drawn off the main thread (one at a time), so opening a screen never stutters
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun parse(src: String): SVG? {
        parsed.get(src)?.let { return it }
        return try { SVG.getFromString(src).also { parsed.put(src, it) } } catch (e: Throwable) { null }
    }
    private fun parseBg(src: String): SVG? {
        parsedBg.get(src)?.let { return it }
        return try { SVG.getFromString(src).also { parsedBg.put(src, it) } } catch (e: Throwable) { null }
    }

    private fun key(src: String, w: Int, h: Int, shadows: List<Shadow>) = "$w×$h|${src.hashCode()}|${src.length}|${shadows.size}"

    fun cached(src: String, w: Int, h: Int, shadows: List<Shadow> = emptyList()): Bitmap? = bitmaps.get(key(src, w, h, shadows))

    /** Draws the picture on the background thread; [done] runs on the main thread. */
    fun bitmapAsync(src: String, w: Int, h: Int, vbW: Float, shadows: List<Shadow>, done: (Bitmap?) -> Unit) {
        if (w <= 0 || h <= 0) { done(null); return }
        val k = key(src, w, h, shadows)
        bitmaps.get(k)?.let { done(it); return }
        val q = waiting[k]
        if (q != null) { q.add(done); return }
        waiting[k] = mutableListOf(done)
        worker.execute {
            val bmp = render(src, w, h, vbW, shadows, ::parseBg)
            main.post {
                if (bmp != null) bitmaps.put(k, bmp)
                waiting.remove(k)?.forEach { it(bmp) }
            }
        }
    }

    /** One drop shadow like the web's feDropShadow (on the parts in [shadowSrc]). */
    class Shadow(val shadowSrc: String, val dx: Float, val dy: Float, val std: Float, val color: Int, val opacity: Float)

    /**
     * [src] drawn into a w × h picture. [vb] = the viewBox width of [src] (to scale shadows).
     * Cached: the same picture at the same size is drawn only once.
     */
    fun bitmap(src: String, w: Int, h: Int, vbW: Float = 0f, shadows: List<Shadow> = emptyList()): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val k = key(src, w, h, shadows)
        bitmaps.get(k)?.let { return it }
        val out = render(src, w, h, vbW, shadows, ::parse) ?: return null
        bitmaps.put(k, out)
        return out
    }

    private fun render(src: String, w: Int, h: Int, vbW: Float, shadows: List<Shadow>, parser: (String) -> SVG?): Bitmap? {
        val svg = parser(src) ?: return null
        val out = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { return null }
        val c = Canvas(out)
        val scale = if (vbW > 0f) w / vbW else 1f
        for (s in shadows) drawShadow(c, s, w, h, scale, parser)
        try { svg.renderToCanvas(c, RenderOptions.create().viewPort(0f, 0f, w.toFloat(), h.toFloat())) } catch (e: Throwable) { }
        return out
    }

    private fun drawShadow(c: Canvas, s: Shadow, w: Int, h: Int, scale: Float, parser: (String) -> SVG?) {
        val svg = parser(s.shadowSrc) ?: return
        val src = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { return }
        try { svg.renderToCanvas(Canvas(src), RenderOptions.create().viewPort(0f, 0f, w.toFloat(), h.toFloat())) } catch (e: Throwable) { }
        val radius = (s.std * scale * 1.7f).coerceAtLeast(0.5f)
        val blur = Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
        val off = IntArray(2)
        val alpha = src.extractAlpha(blur, off)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color
            this.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
        }
        c.drawBitmap(alpha, off[0] + s.dx * scale, off[1] + s.dy * scale, p)
        alpha.recycle(); src.recycle()
    }

    // ── Lucide icons ──
    /** A Lucide icon as SVG text (stroke icons; [fill] fills the shapes too, like fill="currentColor"). */
    fun icon(name: String, color: Int, stroke: Float = 2f, fill: Int? = null): String {
        val inner = LucideData.icons[name] ?: ""
        val f = if (fill != null) hex(fill) else "none"
        val fo = if (fill != null) Color.alpha(fill) / 255f else 1f
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="$f" fill-opacity="$fo" stroke="${hex(color)}" stroke-opacity="${Color.alpha(color) / 255f}" stroke-width="$stroke" stroke-linecap="round" stroke-linejoin="round">$inner</svg>"""
    }

    fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
}

/** A picture drawn from SVG text, sized by its bounds (sharp at every size). */
class SvgDrawable(private var src: String, private val sizeDp: Float = 24f, private val vbW: Float = 0f) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shadows: List<Svg.Shadow> = emptyList()
    fun withShadows(list: List<Svg.Shadow>): SvgDrawable { shadows = list; return this }
    fun set(newSrc: String) { if (newSrc != src) { src = newSrc; invalidateSelf() } }
    override fun getIntrinsicWidth() = G.dpi(sizeDp)
    override fun getIntrinsicHeight() = G.dpi(sizeDp)
    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width(); val h = b.height()
        val bmp = if (w * h <= 160 * 160 && shadows.isEmpty()) Svg.bitmap(src, w, h, vbW, shadows)
        else Svg.cached(src, w, h, shadows) ?: run { Svg.bitmapAsync(src, w, h, vbW, shadows) { invalidateSelf() }; null }
        if (bmp != null) c.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A view that shows SVG art. [overflow] (in viewBox units: left, top, right, bottom) lets the art
 * spill out of its box like the web's overflow: visible (sparkles outside the picture).
 */
class SvgView(ctx: Context, private var src: String, private val vbW: Float, private val vbH: Float) : View(ctx) {
    var shadows: List<Svg.Shadow> = emptyList()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    fun set(newSrc: String) { if (newSrc != src) { src = newSrc; invalidate() } }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val mode = MeasureSpec.getMode(hSpec)
        val h = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(hSpec) else (w * vbH / vbW).toInt()
        setMeasuredDimension(w, h)
    }
    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        val bmp = if (w * h <= 160 * 160 && shadows.isEmpty()) Svg.bitmap(src, w, h, vbW, shadows)
        else Svg.cached(src, w, h, shadows) ?: run {
            Svg.bitmapAsync(src, w, h, vbW, shadows) { if (it != null) { alpha = 0f; invalidate(); animate().alpha(1f).setDuration(140).start() } }
            null
        }
        if (bmp != null) c.drawBitmap(bmp, 0f, 0f, paint)
    }
}
