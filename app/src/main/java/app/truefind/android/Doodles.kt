package app.truefind.android

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/**
 * The call screens' light doodle pattern (web: Doodles / PanelDoodles in
 * src/components/DateCallScreen.jsx) and the plain default avatar
 * (src/assets/default-avatar.svg). Sizes are the web's px = dp here.
 */
object Doodles {
    private const val VIOLET = "#7B2FF7"
    private const val INK = "#15101E"

    private class Mark(val d: String? = null, val cx: Float = 0f, val cy: Float = 0f, val r: Float = 0f)

    // white page: 140 × 140 tile, rotated −8°, stroke 2.4
    private val PAGE_VIOLET = listOf(
        Mark("M22 34c-6-9-20-3-11 9l11 11 11-11c9-12-5-18-11-9z"),
        Mark("M98 52l4 9 10 1-8 6 3 10-9-6-9 6 3-10-8-6 10-1z"),
        Mark("M50 118v-14l10-3v14"), Mark(cx = 47f, cy = 119f, r = 3f), Mark(cx = 57f, cy = 116f, r = 3f),
        Mark("M118 112l-6 10h8l-6 10"),
    )
    private val PAGE_INK = listOf(
        Mark("M66 18q8-10 16 0t16 0t16 0"), Mark(cx = 28f, cy = 88f, r = 7f),
        Mark("M66 74a6 6 0 1 1 6 6a10 10 0 1 1-10-10"), Mark("M92 104v14M85 111h14"),
        Mark("M10 128c6-4 12 4 18 0"), Mark("M124 22l6 6M130 22l-6 6"),
    )
    // purple panel: 120 × 120 tile, rotated −8°, white at 13 %, stroke 2
    private val PANEL_WHITE = listOf(
        Mark("M20 30c-5-8-17-3-9 8l9 9 9-9c8-11-4-16-9-8z"),
        Mark("M84 44l3 8 9 1-7 5 3 9-8-5-8 5 3-9-7-5 9-1z"),
        Mark("M56 16q7-9 14 0t14 0"), Mark(cx = 26f, cy = 78f, r = 6f),
        Mark("M44 102v-12l9-3v12"), Mark(cx = 41f, cy = 103f, r = 3f), Mark(cx = 50f, cy = 100f, r = 3f),
        Mark("M82 92v12M76 98h12"), Mark("M104 100l-5 9h7l-5 9"),
    )

    private fun tile(size: Float, stroke: Float, groups: List<Pair<Int, List<Mark>>>): Bitmap {
        val d = Ui.density
        val px = (size * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / size, px / size)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for ((color, marks) in groups) {
            p.color = color
            for (m in marks) {
                if (m.d != null) c.drawPath(PathParser.createPathFromPathData(m.d), p)
                else c.drawCircle(m.cx, m.cy, m.r, p)
            }
        }
        return bmp
    }

    private fun shader(bmp: Bitmap): BitmapShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        setLocalMatrix(Matrix().apply { setRotate(-8f) }) // patternTransform="rotate(-8)" around the top-left
    }

    private var pageTile: Bitmap? = null
    private var panelTile: Bitmap? = null

    /** White page with the violet / ink doodles (web Doodles). */
    class Page : Drawable() {
        private val bg = Paint().apply { color = Color.WHITE }
        private val pat = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        init {
            val t = pageTile ?: tile(140f, 2.4f, listOf(
                Ui.ca(VIOLET, 0.28f) to PAGE_VIOLET,
                Ui.ca(INK, 0.16f) to PAGE_INK,
            )).also { pageTile = it }
            pat.shader = shader(t)
        }
        override fun draw(c: Canvas) {
            c.drawRect(bounds, bg)
            c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat())
            c.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), pat)
            c.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** The purple bottom panel (rounded top) with the light white doodles (web PanelDoodles). */
    class Panel(color: Int, private val radiusPx: Float) : Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val pat = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shape = Path()
        init {
            val t = panelTile ?: tile(120f, 2f, listOf(Ui.ca("#FFFFFF", 0.13f) to PANEL_WHITE)).also { panelTile = it }
            pat.shader = shader(t)
        }
        override fun onBoundsChange(b: android.graphics.Rect) {
            shape.reset()
            val r = radiusPx
            shape.addRoundRect(RectF(0f, 0f, b.width().toFloat(), b.height().toFloat()), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        }
        override fun draw(c: Canvas) {
            c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat())
            c.drawPath(shape, bg)
            c.drawPath(shape, pat)
            c.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private var avatarCache: Pair<Int, Bitmap>? = null

    /** default-avatar.svg in a circle: lavender glow, white head and shoulders. */
    fun defaultAvatar(size: Int): Bitmap {
        avatarCache?.let { if (it.first == size) return it.second }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = size / 124f
        val clip = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
            // radialGradient cx=.35 cy=.35 r=.75: #F5F3FF → #C4B5FD
            shader = RadialGradient(size * 0.35f, size * 0.35f, size * 0.75f,
                intArrayOf(Ui.c("#F5F3FF"), Ui.c("#C4B5FD")), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ca("#FFFFFF", 0.92f) }
        c.save(); c.scale(k, k)
        c.drawCircle(62f, 48f, 21f, white)
        c.drawPath(PathParser.createPathFromPathData("M20 130C22 94 42 78 62 78S102 94 104 130Z"), white)
        c.restore()
        avatarCache = size to bmp
        return bmp
    }
}
