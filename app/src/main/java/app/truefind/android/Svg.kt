package app.truefind.android

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A small SVG painter for the web app's own drawings (icons, badges, the
 * Home pictures): the same <svg> markup the web uses, drawn natively with
 * Canvas, so every shape, colour and line is exactly the web's.
 * Handles: svg (viewBox), g, path, circle, ellipse, rect, line, polyline,
 * polygon, linear / radial gradients; fill, stroke, opacities, line caps,
 * joins, dashes, fill-rule and transforms (translate, scale, rotate, matrix).
 * "currentColor" takes the colour given when drawing (tint).
 * Parsed once per markup and kept.
 */
class Svg private constructor(val vbX: Float, val vbY: Float, val vbW: Float, val vbH: Float, private val ops: List<Op>) {

    private class Op(val path: Path, val fill: Paint?, val fillCurrent: Boolean, val stroke: Paint?, val strokeCurrent: Boolean,
                     val fillAlpha: Float, val strokeAlpha: Float)

    /** Draws the picture into the box (l, t, w, h): fitted and centred (meet) or stretched (fit = false). */
    fun draw(c: Canvas, l: Float, t: Float, w: Float, h: Float, tint: Int = Color.BLACK, alpha: Float = 1f, meet: Boolean = true) {
        if (vbW <= 0f || vbH <= 0f || w <= 0f || h <= 0f) return
        c.save()
        if (meet) {
            val s = minOf(w / vbW, h / vbH)
            c.translate(l + (w - vbW * s) / 2f, t + (h - vbH * s) / 2f)
            c.scale(s, s)
        } else {
            c.translate(l, t)
            c.scale(w / vbW, h / vbH)
        }
        c.translate(-vbX, -vbY)
        for (o in ops) {
            o.fill?.let { p ->
                if (o.fillCurrent) p.color = tint
                p.alpha = (255 * o.fillAlpha * alpha * (if (o.fillCurrent) Color.alpha(tint) / 255f else 1f)).toInt().coerceIn(0, 255)
                c.drawPath(o.path, p)
            }
            o.stroke?.let { p ->
                if (o.strokeCurrent) p.color = tint
                p.alpha = (255 * o.strokeAlpha * alpha * (if (o.strokeCurrent) Color.alpha(tint) / 255f else 1f)).toInt().coerceIn(0, 255)
                c.drawPath(o.path, p)
            }
        }
        c.restore()
    }

    /** The picture as a bitmap of w × h pixels (for pattern tiles and still pictures). */
    fun bitmap(w: Int, h: Int, tint: Int = Color.BLACK): Bitmap {
        val b = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(b), 0f, 0f, w.toFloat(), h.toFloat(), tint, meet = false)
        return b
    }

    companion object {
        private val cache = HashMap<String, Svg>()

        fun of(xml: String): Svg = synchronized(cache) { cache.getOrPut(xml) { parse(xml) } }

        /** A Lucide icon (24 box, stroke = currentColor). */
        fun lucide(inner: String, stroke: Float = 2f, fill: String = "none"): Svg =
            of("<svg viewBox=\"0 0 24 24\" fill=\"$fill\" stroke=\"currentColor\" stroke-width=\"$stroke\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$inner</svg>")

        // ───────── parsing ─────────
        private class Node(val tag: String, val a: Map<String, String>, val kids: MutableList<Node> = ArrayList())

        private fun dom(xml: String): Node? {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml))
            val stack = ArrayList<Node>()
            var root: Node? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val m = HashMap<String, String>()
                        for (i in 0 until p.attributeCount) m[p.getAttributeName(i)] = p.getAttributeValue(i)
                        // style="a:b;c:d" works like attributes
                        m["style"]?.split(';')?.forEach { kv -> val i = kv.indexOf(':'); if (i > 0) m[kv.substring(0, i).trim()] = kv.substring(i + 1).trim() }
                        val n = Node(p.name, m)
                        if (stack.isEmpty()) root = n else stack.last().kids.add(n)
                        stack.add(n)
                    }
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                ev = p.next()
            }
            return root
        }

        private class Grad(val linear: Boolean, val a: Map<String, String>, val stops: List<Pair<Float, Int>>)

        private data class St(
            val fill: String = "black", val fillOp: Float = 1f, val stroke: String = "none", val strokeOp: Float = 1f,
            val sw: Float = 1f, val cap: Paint.Cap = Paint.Cap.BUTT, val join: Paint.Join = Paint.Join.MITER,
            val dash: FloatArray? = null, val op: Float = 1f, val evenOdd: Boolean = false, val m: Matrix = Matrix(),
        )

        private fun parse(xml: String): Svg {
            val root = dom(xml) ?: return Svg(0f, 0f, 0f, 0f, emptyList())
            val vb = root.a["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
            val w = num(root.a["width"]) ?: vb?.getOrNull(2) ?: 24f
            val h = num(root.a["height"]) ?: vb?.getOrNull(3) ?: 24f
            val box = if (vb != null && vb.size == 4) vb else listOf(0f, 0f, w, h)
            val grads = HashMap<String, Grad>()
            fun collect(n: Node) {
                if (n.tag == "linearGradient" || n.tag == "radialGradient") {
                    val stops = n.kids.filter { it.tag == "stop" }.map { s ->
                        val off = s.a["offset"]?.let { o -> if (o.endsWith("%")) (o.dropLast(1).toFloatOrNull() ?: 0f) / 100f else o.toFloatOrNull() ?: 0f } ?: 0f
                        val col = color(s.a["stop-color"] ?: "black") ?: Color.BLACK
                        val so = (s.a["stop-opacity"]?.toFloatOrNull() ?: 1f) * (Color.alpha(col) / 255f)
                        off to Color.argb((so * 255).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))
                    }
                    n.a["id"]?.let { grads[it] = Grad(n.tag == "linearGradient", n.a, stops) }
                }
                n.kids.forEach { collect(it) }
            }
            collect(root)
            val ops = ArrayList<Op>()
            fun walk(n: Node, parent: St) {
                if (n.tag == "defs" || n.tag == "linearGradient" || n.tag == "radialGradient" || n.tag == "pattern" ||
                    n.tag == "clipPath" || n.tag == "mask" || n.tag == "title" || n.tag == "image" || n.tag == "text") return
                val st = style(n.a, parent)
                val path: Path? = when (n.tag) {
                    "path" -> n.a["d"]?.let { d -> try { PathParser.createPathFromPathData(d) } catch (e: Throwable) { null } }
                    "circle" -> Path().apply { addCircle(f(n.a["cx"]), f(n.a["cy"]), f(n.a["r"]), Path.Direction.CW) }
                    "ellipse" -> Path().apply { val cx = f(n.a["cx"]); val cy = f(n.a["cy"]); val rx = f(n.a["rx"]); val ry = f(n.a["ry"]); addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }
                    "rect" -> Path().apply {
                        val x = f(n.a["x"]); val y = f(n.a["y"]); val rw = f(n.a["width"]); val rh = f(n.a["height"])
                        var rx = num(n.a["rx"]) ?: num(n.a["ry"]) ?: 0f; var ry = num(n.a["ry"]) ?: num(n.a["rx"]) ?: 0f
                        rx = rx.coerceAtMost(rw / 2f); ry = ry.coerceAtMost(rh / 2f)
                        if (rx > 0f || ry > 0f) addRoundRect(RectF(x, y, x + rw, y + rh), rx, ry, Path.Direction.CW) else addRect(RectF(x, y, x + rw, y + rh), Path.Direction.CW)
                    }
                    "line" -> Path().apply { moveTo(f(n.a["x1"]), f(n.a["y1"])); lineTo(f(n.a["x2"]), f(n.a["y2"])) }
                    "polyline", "polygon" -> Path().apply {
                        val pts = (n.a["points"] ?: "").trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                        for (i in 0 until pts.size / 2) if (i == 0) moveTo(pts[0], pts[1]) else lineTo(pts[i * 2], pts[i * 2 + 1])
                        if (n.tag == "polygon") close()
                    }
                    else -> null
                }
                if (path != null) {
                    path.transform(st.m)
                    if (st.evenOdd) path.fillType = Path.FillType.EVEN_ODD
                    val k = scaleOf(st.m)
                    val bounds = RectF().also { path.computeBounds(it, true) }
                    var fillPaint: Paint? = null; var fillCur = false; var fillA = st.fillOp * st.op
                    if (st.fill != "none" && n.tag != "line" && n.tag != "polyline") {
                        val pf = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                        var ok = true
                        if (st.fill == "currentColor") fillCur = true
                        else if (st.fill.startsWith("url(")) { val g = grads[urlId(st.fill)]; if (g != null) pf.shader = shader(g, bounds, st.m) else ok = false }
                        else { val col = color(st.fill); if (col != null) { pf.color = col; fillA *= Color.alpha(col) / 255f } else ok = false }
                        if (ok) fillPaint = pf
                    }
                    var strokePaint: Paint? = null; var strokeCur = false; var strokeA = st.strokeOp * st.op
                    if (st.stroke != "none" && st.sw > 0f) {
                        val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = st.sw * k; strokeCap = st.cap; strokeJoin = st.join; strokeMiter = 4f
                        }
                        val d = st.dash
                        if (d != null && d.isNotEmpty() && d.any { it > 0f }) {
                            val dd = if (d.size % 2 == 1) d + d else d
                            ps.pathEffect = DashPathEffect(FloatArray(dd.size) { dd[it] * k }, 0f)
                        }
                        var ok = true
                        if (st.stroke == "currentColor") strokeCur = true
                        else if (st.stroke.startsWith("url(")) { val g = grads[urlId(st.stroke)]; if (g != null) ps.shader = shader(g, bounds, st.m) else ok = false }
                        else { val col = color(st.stroke); if (col != null) { ps.color = col; strokeA *= Color.alpha(col) / 255f } else ok = false }
                        if (ok) strokePaint = ps
                    }
                    if (fillPaint != null || strokePaint != null) ops.add(Op(path, fillPaint, fillCur, strokePaint, strokeCur, fillA, strokeA))
                }
                for (kid in n.kids) walk(kid, st)
            }
            val base = style(root.a, St())
            for (kid in root.kids) walk(kid, base)
            return Svg(box[0], box[1], box[2], box[3], ops)
        }

        private fun urlId(v: String) = v.substringAfter("#").substringBefore(")").trim()

        private fun scaleOf(m: Matrix): Float {
            val v = FloatArray(9); m.getValues(v)
            return sqrt(abs(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y]))
        }

        private fun shader(g: Grad, b: RectF, m: Matrix): Shader {
            val user = g.a["gradientUnits"] == "userSpaceOnUse"
            val colors = if (g.stops.isEmpty()) intArrayOf(Color.BLACK, Color.BLACK) else g.stops.map { it.second }.toIntArray()
            val pos = if (g.stops.isEmpty()) floatArrayOf(0f, 1f) else g.stops.map { it.first.coerceIn(0f, 1f) }.toFloatArray()
            val cs = if (colors.size == 1) intArrayOf(colors[0], colors[0]) else colors
            val ps = if (pos.size == 1) floatArrayOf(0f, 1f) else pos
            fun frac(key: String, def: Float): Float { val v = g.a[key] ?: return def; return if (v.endsWith("%")) (v.dropLast(1).toFloatOrNull() ?: 0f) / 100f else v.toFloatOrNull() ?: def }
            val sh: Shader = if (g.linear) {
                val x1 = frac("x1", 0f); val y1 = frac("y1", 0f); val x2 = frac("x2", 1f); val y2 = frac("y2", 0f)
                LinearGradient(x1, y1, x2, y2, cs, ps, Shader.TileMode.CLAMP)
            } else {
                val cx = frac("cx", .5f); val cy = frac("cy", .5f); val r = frac("r", .5f)
                RadialGradient(cx, cy, r.coerceAtLeast(0.0001f), cs, ps, Shader.TileMode.CLAMP)
            }
            val lm = Matrix()
            if (user) lm.set(m) else { lm.setScale(b.width().coerceAtLeast(0.0001f), b.height().coerceAtLeast(0.0001f)); lm.postTranslate(b.left, b.top) }
            sh.setLocalMatrix(lm)
            return sh
        }

        private fun style(a: Map<String, String>, p: St): St {
            val m = Matrix(p.m)
            a["transform"]?.let { m.preConcat(transform(it)) }
            val cap = when (a["stroke-linecap"]) { "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; "butt" -> Paint.Cap.BUTT; else -> p.cap }
            val join = when (a["stroke-linejoin"]) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; "miter" -> Paint.Join.MITER; else -> p.join }
            val dash = a["stroke-dasharray"]?.let { d -> if (d == "none") null else d.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }.toFloatArray() } ?: p.dash
            return St(
                fill = a["fill"] ?: p.fill,
                fillOp = a["fill-opacity"]?.toFloatOrNull() ?: p.fillOp,
                stroke = a["stroke"] ?: p.stroke,
                strokeOp = a["stroke-opacity"]?.toFloatOrNull() ?: p.strokeOp,
                sw = num(a["stroke-width"]) ?: p.sw,
                cap = cap, join = join, dash = dash,
                op = p.op * (a["opacity"]?.toFloatOrNull() ?: 1f),
                evenOdd = a["fill-rule"]?.let { it == "evenodd" } ?: p.evenOdd,
                m = m,
            )
        }

        private fun transform(s: String): Matrix {
            val m = Matrix()
            Regex("(\\w+)\\s*\\(([^)]*)\\)").findAll(s).forEach { r ->
                val v = r.groupValues[2].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                val t = Matrix()
                when (r.groupValues[1]) {
                    "translate" -> t.setTranslate(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                    "scale" -> t.setScale(v.getOrElse(0) { 1f }, v.getOrElse(1) { v.getOrElse(0) { 1f } })
                    "rotate" -> if (v.size >= 3) t.setRotate(v[0], v[1], v[2]) else t.setRotate(v.getOrElse(0) { 0f })
                    "matrix" -> if (v.size == 6) t.setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f))
                    "skewX" -> t.setSkew(Math.tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                    "skewY" -> t.setSkew(0f, Math.tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat())
                }
                m.preConcat(t)
            }
            return m
        }

        private fun num(v: String?): Float? = v?.trim()?.removeSuffix("px")?.toFloatOrNull()
        private fun f(v: String?): Float = num(v) ?: 0f

        private val NAMED = mapOf("white" to Color.WHITE, "black" to Color.BLACK, "transparent" to Color.TRANSPARENT, "red" to Color.RED)

        /** #rgb, #rrggbb, #rrggbbaa, rgb(), rgba(), a few names. null = none / unknown. */
        fun color(v: String): Int? {
            val s = v.trim()
            if (s.isEmpty() || s == "none") return null
            NAMED[s.lowercase()]?.let { return it }
            if (s.startsWith("#")) {
                val h = s.substring(1)
                return try {
                    when (h.length) {
                        3 -> Color.rgb(Integer.parseInt("${h[0]}${h[0]}", 16), Integer.parseInt("${h[1]}${h[1]}", 16), Integer.parseInt("${h[2]}${h[2]}", 16))
                        6 -> Color.parseColor(s)
                        8 -> { val rgb = Color.parseColor("#" + h.substring(0, 6)); Color.argb(Integer.parseInt(h.substring(6, 8), 16), Color.red(rgb), Color.green(rgb), Color.blue(rgb)) }
                        else -> null
                    }
                } catch (e: Exception) { null }
            }
            if (s.startsWith("rgb")) {
                val n = s.substringAfter("(").substringBefore(")").split(",").map { it.trim() }
                if (n.size >= 3) {
                    val r = n[0].toFloatOrNull() ?: return null; val g = n[1].toFloatOrNull() ?: return null; val b = n[2].toFloatOrNull() ?: return null
                    val a = n.getOrNull(3)?.toFloatOrNull() ?: 1f
                    return Color.argb((a * 255).toInt().coerceIn(0, 255), r.toInt(), g.toInt(), b.toInt())
                }
            }
            return null
        }
    }
}

/** An SVG as a Drawable of a fixed size (dp), drawn in [tint] where it says currentColor. */
class SvgDrawable(private val svg: Svg, private val wDp: Float, private val hDp: Float = wDp, private var tint: Int = Color.BLACK) : Drawable() {
    private var a = 1f
    fun tint(c: Int): SvgDrawable { tint = c; invalidateSelf(); return this }
    override fun getIntrinsicWidth() = Ui.dpi(wDp)
    override fun getIntrinsicHeight() = Ui.dpi(hDp)
    override fun draw(c: Canvas) { val b = bounds; svg.draw(c, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat(), tint, a) }
    override fun setAlpha(alpha: Int) { a = alpha / 255f; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A repeating pattern made from an SVG tile (the web's background-image tiles and <pattern>s). */
object SvgPattern {
    private val cache = HashMap<String, Bitmap>()
    /** A paint that fills with [tile] repeated every tileDp × tileDp, turned by [rotate] degrees. */
    fun paint(tile: Svg, tileDp: Float, rotate: Float = 0f, key: String): Paint {
        val px = Ui.dpi(tileDp).coerceAtLeast(2)
        val bmp = synchronized(cache) { cache.getOrPut("$key|$px") { tile.bitmap(px, px) } }
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                if (rotate != 0f) setLocalMatrix(Matrix().apply { setRotate(rotate) })
            }
        }
    }
}
