package app.truefind.android

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** An icon drawn from SVG path data in a 24 × 24 box (fill or stroke). */
class PathIcon(
    data: String,
    private val color: Int,
    sizeDp: Float,
    private val strokeDp: Float = 0f,   // > 0 → outline icon (stroke width in the 24 box)
) : Drawable() {
    private val path: Path = PathParser.createPathFromPathData(data)
    private val px = Ui.dpi(sizeDp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@PathIcon.color
        if (strokeDp > 0f) {
            style = Paint.Style.STROKE; strokeWidth = strokeDp
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
    }

    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        c.drawPath(path, paint)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val PHONE = "M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2c.3-.3.7-.4 1-.2 1.1.4 2.3.6 3.6.6.6 0 1 .4 1 1V20c0 .6-.4 1-1 1A17 17 0 0 1 3 4c0-.6.4-1 1-1h3.5c.6 0 1 .4 1 1 0 1.3.2 2.5.6 3.6.1.3 0 .7-.2 1z"
        const val VIDEO = "M5 6h8a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3zM16 10l6-4v12l-6-4z"
        const val RANDOM = "M3 7h4l10 10h4M3 17h4l3-3M14 10l3-3h4M18 4l3 3-3 3M18 14l3 3-3 3"
        const val SHIELD = "M12 2l8 3v6c0 5-3.4 9.4-8 11-4.6-1.6-8-6-8-11V5z"
    }
}

/** The small gold "TF" coin (same as the web's .tfc-tf). */
class CoinDrawable(private val sizeDp: Float) : Drawable() {
    private val px = Ui.dpi(sizeDp)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.c("#e0a21c") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.c("#7a4b00"); typeface = Ui.bold; textAlign = Paint.Align.CENTER
    }

    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        val r = b.width() / 2f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        fill.shader = RadialGradient(b.left + b.width() * 0.35f, b.top + b.height() * 0.30f, r * 1.4f,
            intArrayOf(Ui.c("#fff1b8"), Ui.c("#f5bd36"), Ui.c("#c98a10")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        ring.strokeWidth = r * 0.15f
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        text.textSize = r * 0.75f
        c.drawText("TF", cx, cy + text.textSize * 0.36f, text)
    }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Indian digit grouping: 1,23,456 (same as toLocaleString("en-IN")). */
fun rupees(n: Long): String {
    val s = n.coerceAtLeast(0).toString()
    if (s.length <= 3) return s
    val last3 = s.takeLast(3)
    var rest = s.dropLast(3)
    val parts = ArrayList<String>()
    while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
    if (rest.isNotEmpty()) parts.add(0, rest)
    return parts.joinToString(",") + "," + last3
}

@Suppress("unused")
private val keepColor = Color.WHITE
