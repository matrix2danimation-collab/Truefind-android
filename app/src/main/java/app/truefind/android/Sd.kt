package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Reconnect's colours: the names every page uses (the web's K). */
interface SdPal {
    val wine: Int; val wine2: Int; val rose: Int; val hot: Int; val deep: Int
    val ink: Int; val ink2: Int; val sub: Int; val soft: Int
    val tint: Int; val tint2: Int; val line: Int; val line2: Int
    val green: Int; val pill: Int
    fun btn(radiusDp: Float): GradientDrawable
    fun sheet(): GradientDrawable
    fun badge(): GradientDrawable
}

/** The earlier pink (the web's K). True Find's own chats still wear it (ChatRoom); Reconnect moved to [K]. */
object KP : SdPal {
    override val wine = Ui.c("#8E1243"); override val wine2 = Ui.c("#9E1447"); override val rose = Ui.c("#E3086C"); override val hot = Ui.c("#D80F6E"); override val deep = Ui.c("#8A0E3C")
    override val ink = Ui.c("#1B1A1D"); override val ink2 = Ui.c("#2A1F25"); override val sub = Ui.c("#6E5562"); override val soft = Ui.c("#8C878B")
    override val tint = Ui.c("#FDE1EF"); override val tint2 = Ui.c("#FFF0F7"); override val line = Ui.c("#F4BFD7"); override val line2 = Ui.c("#F6DAE7")
    override val green = Ui.c("#3DD62A"); override val pill = Ui.c("#D80F6E")
    /** every pink page: light pink at the top, white at the bottom */
    val PAGE = intArrayOf(Ui.c("#FB5C94"), Ui.c("#FD6FA3"), Ui.c("#FD82B0"), Ui.c("#FE94BC"), Ui.c("#FEA6C8"), Ui.c("#FDB9D3"), Ui.c("#FED8E6"), Ui.c("#FFFFFF"))
    val PAGE_AT = floatArrayOf(0f, .18f, .34f, .48f, .60f, .69f, .80f, .90f)
    /** K.btn: linear-gradient(90deg,#F23D93,#D80F6E) */
    override fun btn(radiusDp: Float) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#F23D93"), Ui.c("#D80F6E"))).apply { cornerRadius = Ui.dp(radiusDp) }
    /** K.sheet: linear-gradient(180deg,#FFD6E6 0%,#FFFFFF 50%) */
    override fun sheet() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#FFD6E6"), Color.WHITE, Color.WHITE)).apply {
        cornerRadii = floatArrayOf(Ui.dp(28f), Ui.dp(28f), Ui.dp(28f), Ui.dp(28f), 0f, 0f, 0f, 0f)
    }
    /** the round pink icon box: linear-gradient(180deg,#F43D95,#C80962) */
    override fun badge() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#F43D95"), Ui.c("#C80962"))).apply { shape = GradientDrawable.OVAL }
}

/** Reconnect now (owner's picture, 23 Nov): maroon on blush, flat buttons. The same names as the pink had. */
object K : SdPal {
    override val wine = Ui.c("#6D1328"); override val wine2 = Ui.c("#77152C"); override val rose = Ui.c("#931A39"); override val hot = Ui.c("#931A39"); override val deep = Ui.c("#671226")
    override val ink = Ui.c("#161214"); override val ink2 = Ui.c("#2A1F22"); override val sub = Ui.c("#6A6061"); override val soft = Ui.c("#8C878B")
    override val tint = Ui.c("#FAE8E6"); override val tint2 = Ui.c("#FDF4F3"); override val line = Ui.c("#F0C9C8"); override val line2 = Ui.c("#F7DEDC")
    override val green = Ui.c("#3DD62A"); override val pill = Ui.c("#931A39")
    /** the main button: flat maroon */
    override fun btn(radiusDp: Float) = GradientDrawable().apply { setColor(Rs.M); cornerRadius = Ui.dp(radiusDp) }
    /** a bottom sheet: a breath of blush at its top, then white */
    override fun sheet() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#FBE3E1"), Color.WHITE, Color.WHITE)).apply {
        cornerRadii = floatArrayOf(Ui.dp(28f), Ui.dp(28f), Ui.dp(28f), Ui.dp(28f), 0f, 0f, 0f, 0f)
    }
    /** the round icon box: flat maroon */
    override fun badge() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Rs.M) }
}

/**
 * The page background, drawn once and kept still while the page scrolls (sticky in the web).
 * Reconnect: blush with fine hearts and leaves ([rose], 23 Nov). True Find's own chats: the earlier pink.
 */
class PinkBackdrop(ctx: Context) : View(ctx) {
    var rose = true
        set(v) { if (field != v) { field = v; shade(); invalidate() } }
    /** the chat pages (the list and the chat itself): more doodles all over (owner, 23 Nov night: "थोड़े और डूडल्स") */
    var dense = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint()
    private val deco by lazy { RoseDeco() }
    private fun shade() {
        if (height <= 0) return
        p.shader = if (rose) LinearGradient(0f, 0f, 0f, height.toFloat(), Rs.BG_TOP, Rs.BG_END, Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, 0f, height.toFloat(), KP.PAGE, KP.PAGE_AT, Shader.TileMode.CLAMP)
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { shade() }
    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        if (rose) { if (dense) deco.drawDense(c, width.toFloat(), height.toFloat()); deco.draw(c, width.toFloat(), height.toFloat()) }
    }
}

/** A Lucide icon: stroke (round caps), optional fill — like <Heart strokeWidth fill/> in the web. */
class Icon(svg: String, private var color: Int, sizeDp: Float, private val stroke: Float = 2f, private var fill: Int? = null) : Drawable() {
    private val path: Path = PathParser.createPathFromPathData(svg)
    private val px = Ui.dpi(sizeDp)
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    fun tint(c: Int, f: Int? = fill): Icon { color = c; fill = f; invalidateSelf(); return this }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f)
        fill?.let { pf.color = it; c.drawPath(path, pf) }
        ps.color = color; ps.strokeWidth = stroke
        c.drawPath(path, ps)
        c.restore()
    }
    override fun setAlpha(a: Int) { ps.alpha = a; pf.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** pink scalloped "verified" badge (Seal) */
class Seal(sizeDp: Float, private val fill: Int = Rs.SEAL, private val tick: Int = Color.WHITE) : Drawable() {
    private val px = Ui.dpi(sizeDp)
    private val shape = PathParser.createPathFromPathData("M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z")
    private val check = PathParser.createPathFromPathData("m8.6 12.2 2.3 2.3 4.6-4.8")
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f)
        pf.color = fill; c.drawPath(shape, pf)
        ps.color = tick; c.drawPath(check, ps)
        c.restore()
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** three stacked gold coins (Coins) */
class Coins(sizeDp: Float) : Drawable() {
    private val px = Ui.dpi(sizeDp)
    private val parts = listOf(
        "M4.5 16v2.6c0 1.6 3.4 2.9 7.5 2.9s7.5-1.3 7.5-2.9V16" to "#F2A93B",
        "M4.5 16a7.5 2.9 0 1 0 15 0a7.5 2.9 0 1 0 -15 0z" to "#FFD36B",
        "M4.5 11v2.6c0 1.6 3.4 2.9 7.5 2.9s7.5-1.3 7.5-2.9V11" to "#F2A93B",
        "M4.5 11a7.5 2.9 0 1 0 15 0a7.5 2.9 0 1 0 -15 0z" to "#FFD36B",
        "M4.5 6v2.6c0 1.6 3.4 2.9 7.5 2.9s7.5-1.3 7.5-2.9V6" to "#F2A93B",
        "M4.5 6a7.5 2.9 0 1 0 15 0a7.5 2.9 0 1 0 -15 0z" to "#FFE39A",
    ).map { PathParser.createPathFromPathData(it.first) to Ui.c(it.second) }
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f; color = Ui.c("#B26F0B") }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f)
        for ((p, col) in parts) { pf.color = col; c.drawPath(p, pf); c.drawPath(p, ps) }
        c.restore()
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Green "online" dot with its soft pulse (the web's Dot). */
class LiveDot(ctx: Context, sizeDp: Float, private val ringDp: Float = 0f, private val ringColor: Int = Color.WHITE) : View(ctx) {
    private val s = Ui.dp(sizeDp)
    private val k = (sizeDp + 12f) / sizeDp
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    override fun onMeasure(w: Int, h: Int) { val d = (s + 2 * Ui.dp(ringDp)).toInt() + 1; setMeasuredDimension(d, d) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = s / 2f
        // ring: scale 1 → k and fade out by 70 %, ease-out
        val f = (t / 0.7f).coerceAtMost(1f); val e = 1f - (1f - f) * (1f - f)
        p.color = Color.argb(((1f - e) * 0.6f * 255).toInt(), 52, 211, 94)
        c.drawCircle(cx, cy, r * (1f + (k - 1f) * e), p)
        if (ringDp > 0) { p.color = ringColor; c.drawCircle(cx, cy, r + Ui.dp(ringDp), p) }
        p.color = K.green; c.drawCircle(cx, cy, r, p)
    }
}

/**
 * A round photo with the web's fallback (DefaultAvatar: lavender silhouette).
 * raws = small copy first, then the full photo; private files are signed first.
 */
class RoundPhoto(ctx: Context, private val sizeDp: Float) : View(ctx) {
    private var bmp: Bitmap? = null
    private var want: List<String> = emptyList()
    private val dst = RectF()
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var dim = 0f        // 0..1: a blur-like veil (blurred story for Free members)
    fun set(raws: List<String>, demoFace: Bitmap? = null) {
        val list = raws.filter { it.isNotBlank() }
        if (list == want && bmp != null) return
        want = list
        bmp = demoFace ?: Photos.cached(list, size())
        invalidate()
        if (bmp == null && list.isNotEmpty()) Photos.loadRaw(list, size()) { b -> if (want == list && b != null) { bmp = b; invalidate() } }
    }
    private fun size() = Ui.dpi(sizeDp).coerceAtLeast(48)
    override fun onDraw(c: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val b = bmp
        if (b != null) c.drawBitmap(b, null, dst, pb) else DefaultAvatar.draw(c, width.toFloat())
        if (dim > 0f) { val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = Color.argb((dim * 255).toInt(), 253, 236, 245); c.drawCircle(width / 2f, height / 2f, width / 2f, p) }
    }
}

/** The app's default avatar (src/assets/default-avatar.svg), drawn round. */
object DefaultAvatar {
    private val body = PathParser.createPathFromPathData("M20 130C22 94 42 78 62 78S102 94 104 130Z")
    fun draw(c: Canvas, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        val clip = Path().apply { addCircle(size / 2, size / 2, size / 2, Path.Direction.CW) }
        c.clipPath(clip)
        c.scale(size / 124f, size / 124f)
        p.shader = RadialGradient(43.4f, 43.4f, 93f, Ui.c("#F5F3FF"), Ui.c("#C4B5FD"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 124f, 124f, p)
        p.shader = null; p.color = Color.WHITE; p.alpha = (0.92f * 255).toInt()
        c.drawCircle(62f, 48f, 21f, p)
        c.drawPath(body, p)
        c.restore()
    }
}

/** A conic ring around a photo (conic-gradient(from 200deg, …)). */
class RingDrawable(private val colors: IntArray, private val stops: FloatArray?) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onBoundsChange(b: android.graphics.Rect) {
        p.shader = SweepGradient(b.exactCenterX(), b.exactCenterY(), colors, stops).apply {
            setLocalMatrix(Matrix().apply { setRotate(110f, b.exactCenterX(), b.exactCenterY()) })
        }
    }
    override fun draw(c: Canvas) { c.drawOval(RectF(bounds), p) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ───────── small view helpers ─────────
/** [serif]: a heading in Reconnect's serif (a little smaller: its letters are wider than the app font's) */
fun sdText(ctx: Context, s: CharSequence, sizeDp: Float, color: Int, bold: Boolean = true, italic: Boolean = false, serif: Boolean = false): TextView =
    TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(if (serif) sizeDp * .9f else sizeDp))
        setTextColor(color)
        typeface = if (serif) Rs.serif else if (italic) Ui.boldItalic else if (bold) Ui.bold else Ui.regular
        includeFontPadding = false
    }

fun TextView.oneLine(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }
fun TextView.lineHeight(mult: Float): TextView { setLineSpacing(0f, mult); return this }
fun TextView.shadow(radiusDp: Float, dyDp: Float, color: Int): TextView { setShadowLayer(Ui.dp(radiusDp), 0f, Ui.dp(dyDp), color); return this }
fun TextView.icon(d: Drawable?, gapDp: Float, end: Boolean = false): TextView {
    compoundDrawablePadding = Ui.dpi(gapDp)
    if (end) setCompoundDrawablesWithIntrinsicBounds(null, null, d, null) else setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
    return this
}

/** an icon inside the text itself (like an inline <svg> in the web page): it sits right next to the words, also in centred text */
class IconSpan(private val d: Drawable, private val gapPx: Int, private val atEnd: Boolean) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) { val p = paint.fontMetricsInt; fm.ascent = p.ascent; fm.descent = p.descent; fm.top = p.top; fm.bottom = p.bottom }
        return d.bounds.width() + gapPx
    }
    override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = paint.fontMetrics
        val mid = y + (p.ascent + p.descent) / 2f // the middle of the letters
        c.save(); c.translate(x + if (atEnd) gapPx.toFloat() else 0f, mid - d.bounds.height() / 2f); d.draw(c); c.restore()
    }
}
fun TextView.inlineIcon(d: Drawable, gapDp: Float, atEnd: Boolean = false): TextView {
    d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
    val t = text.toString()
    val s = android.text.SpannableString(if (atEnd) "$t\u2060\uFFFC" else "\uFFFC$t")
    val at = if (atEnd) s.length - 1 else 0
    s.setSpan(IconSpan(d, Ui.dpi(gapDp), atEnd), at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text = s
    return this
}

fun hbox(ctx: Context, gapDp: Float = 0f, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    if (gapDp > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = gap(gapDp) }
}
fun vbox(ctx: Context, gapDp: Float = 0f, gravity: Int = Gravity.START): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL; this.gravity = gravity
    if (gapDp > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = gap(gapDp) }
}
private fun gap(dp: Float) = GradientDrawable().apply { setSize(Ui.dpi(dp), Ui.dpi(dp)); setColor(Color.TRANSPARENT) }

fun lp(w: Int = LinearLayout.LayoutParams.WRAP_CONTENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
       l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f, gravity: Int = -1) =
    LinearLayout.LayoutParams(w, h, weight).apply {
        setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)); if (gravity >= 0) this.gravity = gravity
    }
val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
fun dpi(v: Float) = Ui.dpi(v)

fun oval(color: Int, strokeDp: Float = 0f, strokeColor: Int = Color.TRANSPARENT) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(color); if (strokeDp > 0) setStroke(Ui.dpi(strokeDp), strokeColor)
}
fun rounded(color: Int, radiusDp: Float, strokeDp: Float = 0f, strokeColor: Int = Color.TRANSPARENT) = GradientDrawable().apply {
    setColor(color); cornerRadius = Ui.dp(radiusDp); if (strokeDp > 0) setStroke(Ui.dpi(strokeDp), strokeColor)
}
fun grad(orientation: GradientDrawable.Orientation, colors: IntArray, radiusDp: Float = 0f, strokeDp: Float = 0f, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable(orientation, colors).apply { cornerRadius = Ui.dp(radiusDp); if (strokeDp > 0) setStroke(Ui.dpi(strokeDp), strokeColor) }

/** The same press feel as .sd-press (scale .96) */
fun View.press(scale: Float = 0.96f): View { Ui.pressable(this, scale); return this }
fun View.onTap(f: () -> Unit): View { setOnClickListener { f() }; return this }

/** "Someone, 24 · Delhi" style pieces — same rules as SecretApp.jsx */
object SdRules {
    fun s(o: JSONObject?, vararg keys: String): String {
        if (o == null) return ""
        for (k in keys) { val v = o.opt(k); if (v != null && v != JSONObject.NULL) { val t = v.toString(); if (t.isNotEmpty() && t != "null") return t } }
        return ""
    }
    fun firstName(n: String?): String = (n?.trim().takeUnless { it.isNullOrEmpty() } ?: "Member").split(Regex("\\s+"))[0]
    fun sexOf(p: JSONObject?): String {
        val v = s(p, "gender", "sex", "i_am", "owner_gender").trim().lowercase()
        if (v.isEmpty()) return ""
        if (Regex("^(f|female|woman|women|girl|lady|महिला|स्त्री|लड़की)$").matches(v)) return "f"
        if (Regex("^(m|male|man|men|boy|guy|पुरुष|लड़का)$").matches(v)) return "m"
        return ""
    }
    private val ISO = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")
    private fun iso() = ISO.map { SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    /** a date as milliseconds (0 = not a date) */
    fun ms(v: String?): Long {
        if (v.isNullOrBlank()) return 0L
        var t = v.trim()
        if (t.endsWith("Z")) t = t.dropLast(1) + "+00:00"
        // 6-digit fractions (Python style) → 3
        t = t.replace(Regex("(\\.\\d{3})\\d+"), "$1")
        for (f in iso()) { try { return f.parse(t)?.time ?: 0L } catch (e: Exception) { } }
        return 0L
    }
    fun isOnline(p: JSONObject?): Boolean {
        if (p == null) return false
        if (p.opt("online") == true || p.opt("is_online") == true) return true
        if (p.opt("online") == false || p.opt("is_online") == false) return false
        val t = ms(s(p, "last_active", "last_seen"))
        return t > 0 && System.currentTimeMillis() - t < 150_000
    }
    fun liveOf(p: JSONObject?): Boolean = if (p != null && p.has("_online")) p.optBoolean("_online") else isOnline(p)
    fun timeAgo(t: String?): String {
        val m0 = ms(t); if (m0 <= 0) return ""
        val diff = System.currentTimeMillis() - m0
        if (diff < 0) return ""
        val m = diff / 60000
        if (m < 1) return "just now"
        if (m < 60) return "${m}m ago"
        val h = m / 60
        return if (h < 24) "${h}h ago" else "${h / 24}d ago"
    }
    fun seenText(p: JSONObject): String = if (liveOf(p)) "Online now" else timeAgo(s(p, "last_active")).let { if (it.isNotEmpty()) "Last seen $it" else "Offline" }
    fun cityOf(p: JSONObject?): String = s(p, "city", "location", "state")
    fun photo(p: JSONObject?): String = s(p, "photo")
    fun thumbs(p: JSONObject?): List<String> = listOf(s(p, "photo_thumb"), s(p, "photo", "profile_picture"))
    private val ITEM_PENDING = Regex("pending|review|submitted", RegexOption.IGNORE_CASE)
    /** my own photo (approved ones only) */
    fun photoOf(p: JSONObject?): String {
        if (p == null) return ""
        val direct = s(p, "photo", "profile_picture")
        if (direct.isNotEmpty()) return direct
        for (key in listOf("images", "photos")) {
            val a = p.optJSONArray(key) ?: continue
            for (i in 0 until a.length()) {
                val x = a.opt(i)
                if (x is String && x.isNotBlank()) return x
                if (x is JSONObject) {
                    val st = x.optString("status").ifEmpty { if (x.has("approved") && !x.optBoolean("approved")) "pending" else "" }
                    if (ITEM_PENDING.containsMatchIn(st)) continue
                    val u = s(x, "url", "file_url", "uri", "file_uri", "path"); if (u.isNotEmpty()) return u
                }
            }
        }
        return ""
    }
    /** age from the date of birth (Indian date); falls back to the saved age */
    fun ageOf(p: JSONObject?): Int? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(s(p, "date_of_birth"))
        if (m != null) {
            // "now" in India (UTC + 5:30)
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = System.currentTimeMillis() + 19_800_000L
            var a = cal.get(java.util.Calendar.YEAR) - m.groupValues[1].toInt()
            val mo = cal.get(java.util.Calendar.MONTH) + 1; val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            if (mo < m.groupValues[2].toInt() || (mo == m.groupValues[2].toInt() && d < m.groupValues[3].toInt())) a -= 1
            return if (a in 1..119) a else null
        }
        val n = s(p, "age").toDoubleOrNull() ?: return null
        return if (n > 0 && n < 120) n.toInt() else null
    }
    val NEED_LABEL = mapOf("name" to "Name", "age" to "Age", "state" to "State", "city" to "City")
    fun missingOf(p: JSONObject?): List<String> {
        val out = ArrayList<String>()
        if (s(p, "full_name", "name").isBlank()) out.add("name")
        val a = ageOf(p); if (a == null || a < 18) out.add("age")
        if (s(p, "state").isBlank()) out.add("state")
        if (s(p, "city").isBlank()) out.add("city")
        return out
    }
    private fun stNorm(v: String): String {
        val x = v.lowercase()
        return when (x) {
            "approved", "verified", "active", "accepted", "done" -> "approved"
            "pending", "submitted", "in_review", "review", "under_review", "applied" -> "pending"
            "rejected", "declined", "failed" -> "rejected"
            else -> "none"
        }
    }
    fun creatorStatusOf(p: JSONObject?): String =
        if (s(p, "account_type").lowercase() == "creator") "approved" else stNorm(s(p, "creator_verification_status", "creator_status", "creator_application_status"))
    fun girlApproved(p: JSONObject?): Boolean {
        if (p == null) return false
        val at = s(p, "creator_submitted_at")
        if (at.isNotEmpty() && s(p, "creator_reviewed_for") == at) return s(p, "creator_status") == "approved"
        return s(p, "account_type").lowercase() == "creator"
    }
    /** "17 Nov, 6:30 pm" (en-IN) */
    fun whenText(t: String?): String {
        val m = ms(t); if (m <= 0) return ""
        return SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN")).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(java.util.Date(m)).replace("AM", "am").replace("PM", "pm")
    }
    fun fmtCredits(n: Int?): String = when { n == null -> "–"; n >= 10000 -> "${n / 1000}k"; n >= 1000 -> String.format(Locale.US, "%.1f", n / 1000f).removeSuffix(".0") + "k"; else -> n.toString() }
    fun inr(n: Int): String = rupees(n.toLong())

    // ── no numbers, links or IDs (stories, About Me) — same checks as the web ──
    private val NUM_WORDS = mapOf("zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "shunya" to 0, "shoonya" to 0, "sunya" to 0, "ek" to 1, "do" to 2, "teen" to 3, "tin" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "pach" to 5,
        "chhe" to 6, "chhah" to 6, "che" to 6, "cheh" to 6, "saat" to 7, "sat" to 7, "aath" to 8, "ath" to 8, "aat" to 8, "nau" to 9, "no" to 9)
    private val HI_WORDS = listOf("शून्य" to "0", "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4", "पांच" to "5", "पाँच" to "5", "छह" to "6", "छः" to "6", "छे" to "6", "सात" to "7", "आठ" to "8", "नौ" to "9")
    fun findContact(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var t = java.text.Normalizer.normalize(input.lowercase(), java.text.Normalizer.Form.NFKC).replace(Regex("[️⃣]"), "")
        t = t.map { ch -> if (ch in '०'..'९') ('0' + (ch - '०')) else ch }.joinToString("")
        HI_WORDS.forEach { (w, d) -> t = t.split(w).joinToString(" $d ") }
        var sq = t
        repeat(6) { sq = sq.replace(Regex("(\\d)[\\s.\\-_/\\\\,()*#+|:;'\"~`^=<>\\[\\]{}]+(\\d)"), "$1$2") }
        if (Regex("\\d{7,}").containsMatchIn(sq)) return "Phone numbers aren't allowed."
        var run = ""; var words = 0
        for (tok in t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }) {
            when {
                tok.all { it.isDigit() } -> run += tok
                NUM_WORDS.containsKey(tok) -> { run += NUM_WORDS[tok].toString(); words += 1 }
                else -> { run = ""; words = 0; continue }
            }
            if (run.length >= 7 && (words >= 3 || tok.all { it.isDigit() })) return "Phone numbers aren't allowed, even in words."
        }
        if (Regex("(https?://|www\\.|\\b[a-z0-9-]{2,}\\.(com|in|net|org|me|io|co|app|xyz|link|ly|gg|tv|info|biz|site|online|store|live|club|page)\\b|\\b[a-z0-9-]{2,}\\s*(\\(dot\\)|\\[dot\\]|\\s+dot\\s+)\\s*(com|in|net|org|me|io|co|app)\\b)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "Links aren't allowed."
        if (Regex("@[a-z0-9_.]{3,}", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "IDs and @handles aren't allowed."
        if (Regex("\\b(insta(gram)?|ig\\s*id|telegram|t\\.me|tele\\s*gram|whats\\s*app|whatsapp|watsapp|wa\\.me|snap\\s*chat|snapchat|snap\\s*id|facebook|fb\\s*id|twitter|discord|signal\\s*app|wechat|skype|gmail|e-?mail|my\\s*id|mera\\s*id|dm\\s*me)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
            Regex("(इंस्टा|इन्स्टा|टेलीग्राम|व्हाट्स|वॉट्स|स्नैप|फेसबुक)").containsMatchIn(input)) return "Social media IDs aren't allowed."
        if (Regex("\\b(mera|my|apna|tera|your|tumhara|call)\\s*(no\\.?|num|number|nmbr|numbr|contact)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) || Regex("(मेरा|अपना)\\s*(नंबर|नम्बर)").containsMatchIn(input)) return "Sharing numbers isn't allowed."
        return ""
    }

    // ── places: districts per state, city coordinates (same lists as the web) ──
    private var data: JSONObject? = null
    fun load(ctx: Context) {
        if (data != null) return
        data = try { JSONObject(ctx.assets.open("sd-data.json").bufferedReader().use { it.readText() }) } catch (e: Exception) { JSONObject() }
    }
    val states: List<String> get() = data?.optJSONObject("districts")?.keys()?.asSequence()?.toList() ?: emptyList()
    fun districts(state: String): List<String> {
        val d = data?.optJSONObject("districts") ?: return emptyList()
        val a: JSONArray = d.optJSONArray(state) ?: d.optJSONArray(state.replace(" & ", " and ")) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }
    /** [lng, lat] of a city (state middle, else India middle) — coordsFor */
    fun geoOf(city: String?, state: String?): DoubleArray {
        val c = data?.optJSONObject("cities")?.optJSONArray(city ?: "") ?: data?.optJSONObject("states")?.optJSONArray(state ?: "")
        return if (c != null) doubleArrayOf(c.optDouble(1), c.optDouble(0)) else doubleArrayOf(78.6569, 22.9734)
    }
    fun kmBetween(a: DoubleArray, b: DoubleArray): Double {
        val r = PI / 180; val dLat = (b[1] - a[1]) * r; val dLng = (b[0] - a[0]) * r
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a[1] * r) * cos(b[1] * r) * sin(dLng / 2) * sin(dLng / 2)
        return 12742 * asin(min(1.0, sqrt(h)))
    }
}
