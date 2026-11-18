package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Expert Talk, native (src/components/experttalk): the shared pieces — the look (etStyles.js),
 * India-time labels and the server call (etKit.jsx), and small views used by every screen.
 * Sizes are the web's CSS px (= dp), text is Calibri (Carlito) like the web.
 */
object Et {
    // ── colours (etStyles.js) ──
    val P = Ui.c("#5E17EB")
    val DEEP = Ui.c("#3A1490")          // .pm premium purple
    val INK = Ui.c("#20202A")
    val MUT = Ui.c("#6E6B78")
    val BODY = Ui.c("#45424E")
    val PTEXT = Ui.c("#4A16B8")
    val LINE = Ui.c("#ECE8F4")
    val PM_LINE = Ui.c("#E7E0F5")
    val SOFT_LINE = Ui.c("#F2EFF7")
    val FIELD_LINE = Ui.c("#E3DDF0")
    val PAGE = Ui.c("#F8F7FB")
    val PM_PAGE = Ui.c("#F6F3FC")
    val TINT = Ui.c("#F0EAFE")
    val PM_TINT = Ui.c("#EFE8FE")
    val GREEN = Ui.c("#1F9D55")
    val GOLD = Ui.c("#E8A317")
    val RED_TEXT = Ui.c("#B4232A")

    // ── topics (members see "Life Guidance" for the stored "Counselling") ──
    fun topicName(x: String) = if (x == "Counselling") "Life Guidance" else x
    fun topicNames(list: List<String>, sep: String = " · ") = list.joinToString(sep) { topicName(it) }

    // ── India time (etKit.jsx) ──
    const val IST = 330 * 60000L
    val MON = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val MONF = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val WDN = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    // Calendar dates as plain day numbers (days since 1 Jan 1970): no shared date formatter,
    // so they are right on any thread (a SimpleDateFormat used from two threads mixes dates up).
    private val YMD = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
    private fun civil(days: Long): IntArray {
        val z = days + 719468L
        val era = Math.floorDiv(z, 146097L)
        val doe = (z - era * 146097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = (yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt()
        return intArrayOf(y, m, d)
    }
    private fun dayNo(date: String?): Long? {
        val g = YMD.matchEntire(date ?: "") ?: return null
        return daysFromCivil(g.groupValues[1].toInt(), g.groupValues[2].toInt(), g.groupValues[3].toInt())
    }
    /** UTC midnight of a YYYY-MM-DD date (ms); 0 when it is not a date */
    fun dayMs(date: String?): Long = (dayNo(date) ?: 0L) * 86_400_000L
    fun ymd(ms: Long): String { val c = civil(Math.floorDiv(ms, 86_400_000L)); return "${c[0]}-${c[1].toString().padStart(2, '0')}-${c[2].toString().padStart(2, '0')}" }
    fun todayIST(now: Long = System.currentTimeMillis()) = ymd(now + IST)
    fun addDays(date: String, n: Int): String = ymd(dayMs(date) + n * 86_400_000L)
    /** 0 = Sunday … 6 = Saturday (1 Jan 1970 was a Thursday) */
    fun wdOf(date: String): Int = Math.floorMod((dayNo(date) ?: 0L) + 4L, 7L).toInt()
    fun dayOfMonth(date: String) = civil(dayNo(date) ?: 0L)[2]
    fun monthOf(date: String) = civil(dayNo(date) ?: 0L)[1] - 1
    fun yearOf(date: String) = civil(dayNo(date) ?: 0L)[0]
    fun startMs(date: String, min: Int): Long = dayMs(date) + min * 60_000L - IST
    fun hm(min: Int): String { val h = min / 60; val m = min % 60; return "${((h + 11) % 12) + 1}:${m.toString().padStart(2, '0')} ${if (h >= 12) "PM" else "AM"}" }
    /** "3:00" (hm without AM / PM) */
    fun hmShort(min: Int) = hm(min).replace(Regex(" (AM|PM)$"), "")
    fun dateStr(date: String) = "${WDN[wdOf(date)]} ${dayOfMonth(date)} ${MON[monthOf(date)]}"
    fun dayLabel(date: String): String { val t = todayIST(); if (date == t) return "Today"; if (date == addDays(t, 1)) return "Tomorrow"; return dateStr(date) }
    fun whenText(date: String, min: Int) = "${dayLabel(date)}, ${hm(min)}"
    fun countdown(ms: Long): String {
        val s = max(0L, (ms / 1000.0).roundToLong())
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        if (d > 0) return "${d}d ${h}h"
        if (h > 0) return "${h}h ${m}m"
        if (m > 0) return "$m min"
        return "${s}s"
    }
    fun mmss(sec: Double): String { val s = max(0.0, sec); return "${floor(s / 60).toLong()}:${(floor(s).toLong() % 60).toString().padStart(2, '0')}" }
    fun daysText(days: List<Int>): String {
        val d = days.sorted()
        if (d.isEmpty()) return "No working days"
        val run = d.withIndex().all { (i, x) -> i == 0 || x == d[i - 1] + 1 }
        return if (run && d.size > 2) "${WDN[d.first()]}–${WDN[d.last()]}" else d.joinToString(", ") { WDN[it] }
    }

    // ── numbers like toLocaleString("en-IN") ──
    private val inF: NumberFormat get() = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 3 }
    fun num(v: Double): String = inF.format(v)
    fun num(v: Long): String = inF.format(v)

    /** a date-time from the server (with or without a zone), as the phone's own time */
    fun msOf(v: String?): Long = Tf.ms(v)
    private val enIN = Locale("en", "IN")
    /** toLocaleString("en-IN", { day, month: "short", hour, minute }) → 18 Nov, 3:30 pm */
    fun dayTime(ms: Long): String = SimpleDateFormat("d MMM, h:mm a", enIN).format(Date(ms)).replace("AM", "am").replace("PM", "pm")
    /** … with the weekday → Wed, 18 Nov, 3:30 pm */
    fun wdDayTime(ms: Long): String = SimpleDateFormat("EEE, d MMM, h:mm a", enIN).format(Date(ms)).replace("AM", "am").replace("PM", "pm")

    // ── the server (base44.functions.invoke("expertTalk", { action, ...payload })) ──
    class R(val ok: Boolean, val data: JSONObject, val error: String, val detail: String, val status: Int)

    fun call(action: String, payload: JSONObject = JSONObject(), done: (R) -> Unit) {
        val body = JSONObject().put("action", action)
        val it = payload.keys(); while (it.hasNext()) { val k = it.next(); body.put(k, payload.opt(k)) }
        Api.invoke("expertTalk", body, 30000) { r ->
            if (r.ok) { done(R(true, r.data, "", "", r.status)); return@invoke }
            val m = r.data.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: r.error ?: "Something went wrong. Please try again."
            val detail = r.data.optString("detail").takeIf { it != "null" }.orEmpty()
            done(R(false, r.data, if (m == "banned") "Your account is blocked." else m, detail, r.status))
        }
    }

    // ── data helpers ──
    fun s(o: JSONObject?, k: String): String { val v = o?.opt(k) ?: return ""; return if (v == JSONObject.NULL) "" else v.toString() }
    fun i(o: JSONObject?, k: String, def: Int = 0): Int { val v = o?.opt(k); return when (v) { is Number -> v.toInt(); is String -> v.toDoubleOrNull()?.toInt() ?: def; else -> def } }
    fun d(o: JSONObject?, k: String): Double { val v = o?.opt(k); return when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: 0.0; else -> 0.0 } }
    fun b(o: JSONObject?, k: String): Boolean { val v = o?.opt(k); return when (v) { is Boolean -> v; is Number -> v.toDouble() != 0.0; is String -> v.isNotEmpty() && v != "false"; else -> false } }
    fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toString() }
    fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it).let { v -> (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull() } }
    fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    /** one decimal like JS toFixed(1) */
    fun fixed1(v: Double): String = String.format(Locale.US, "%.1f", v)
    /** the session price list rules.price[d] (the server's), or the fixed one */
    fun price(rules: JSONObject?, dur: Int): Int = rules?.optJSONObject("price")?.optInt(dur.toString(), 0)?.takeIf { it > 0 } ?: when (dur) { 25 -> 250; 35 -> 350; 45 -> 450; else -> 250 }
    fun firstName(n: String) = n.split(" ").firstOrNull().orEmpty()
}

// ═══════════════════════════ small views ═══════════════════════════

/** A TextView laid out like CSS line-height (the spare room split above and below each line). */
class EtText(ctx: Context) : TextView(ctx) {
    var halfLeading = 0
    var fontHeight = 0
    private var used = 0
    override fun onMeasure(w: Int, h: Int) {
        val room = View.MeasureSpec.getSize(h) - super.getCompoundPaddingTop() - super.getCompoundPaddingBottom()
        used = when (View.MeasureSpec.getMode(h)) {
            View.MeasureSpec.EXACTLY -> 0
            View.MeasureSpec.AT_MOST -> halfLeading.coerceAtMost(((room - fontHeight) / 2).coerceAtLeast(0))
            else -> halfLeading
        }
        super.onMeasure(w, h)
    }
    override fun getCompoundPaddingTop(): Int = super.getCompoundPaddingTop() + used
    override fun getCompoundPaddingBottom(): Int = super.getCompoundPaddingBottom() + used
    /** CSS line-height × font size */
    fun lh(mult: Float): EtText {
        val fm = paint.fontMetricsInt
        val font = fm.descent - fm.ascent
        val extra = textSize * mult - font
        setLineSpacing(extra, 1f)
        fontHeight = font; halfLeading = if (extra > 0f) (extra / 2f).roundToInt() else 0
        requestLayout()
        return this
    }
}

/** text in dp size, Calibri, CSS line-height 1.35 (the Expert Talk page's) */
fun etT(ctx: Context, s: CharSequence, size: Float = 15f, color: Int = Et.INK, bold: Boolean = false, lh: Float = 1.35f, italic: Boolean = false): EtText =
    EtText(ctx).apply {
        text = s
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(size))
        setTextColor(color)
        typeface = if (italic) Ui.boldItalic else if (bold) Ui.bold else Ui.regular
        lh(lh)
    }

fun <T : TextView> T.etEll(): T { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; return this }

/** CSS gap between children (an invisible divider) */
class EtGap(private val px: Int) : Drawable() {
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {}
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSPARENT
}

fun etCol(ctx: Context, gap: Float = 0f, gravity: Int = Gravity.START): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL; this.gravity = gravity
    if (gap > 0f) { dividerDrawable = EtGap(Ui.dpi(gap)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
}
fun etRow(ctx: Context, gap: Float = 0f, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    if (gap > 0f) { dividerDrawable = EtGap(Ui.dpi(gap)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
}
/** takes a view out of the layout it is in (to put it somewhere else) */
fun <T : View> T.detach(): T { (parent as? ViewGroup)?.removeView(this); return this }

/** adds [v] (dp sizes; -1 match, -2 wrap) */
fun LinearLayout.put(v: View, w: Float = -2f, h: Float = -2f, weight: Float = 0f, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f, g: Int = -1): View {
    // a view that already has its own size keeps it when no size is asked for
    val pre = v.layoutParams
    val ww = if (w == -2f && h == -2f && pre != null) pre.width else dim(w)
    val hh = if (w == -2f && h == -2f && pre != null) pre.height else dim(h)
    val lp = LinearLayout.LayoutParams(ww, hh, weight).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)); if (g >= 0) gravity = g }
    v.detach()
    addView(v, lp)
    return v
}
fun FrameLayout.put(v: View, w: Float = -1f, h: Float = -1f, g: Int = Gravity.TOP or Gravity.START, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f): View {
    v.detach()
    addView(v, FrameLayout.LayoutParams(dim(w), dim(h), g).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)) })
    return v
}
private fun dim(v: Float): Int = when { v == -1f -> ViewGroup.LayoutParams.MATCH_PARENT; v == -2f -> ViewGroup.LayoutParams.WRAP_CONTENT; else -> Ui.dpi(v) }

/** a Lucide icon (24 box) as a view */
fun etIcon(ctx: Context, inner: String, sizeDp: Float, color: Int, stroke: Float = 2f, fill: String = "none"): ImageView = ImageView(ctx).apply {
    setImageDrawable(Tf.lu(inner, color, sizeDp, stroke, fill)); scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
}
fun etIconD(inner: String, sizeDp: Float, color: Int, stroke: Float = 2f, fill: String = "none"): Drawable = Tf.lu(inner, color, sizeDp, stroke, fill)

/** the verified tick (BadgeCheck, white on purple) */
fun etVerified(ctx: Context, size: Float = 15f): ImageView = etIcon(ctx, EtIcon.BadgeCheck, size, Color.WHITE, 2f, "#5E17EB").apply { contentDescription = "Verified" }

/** the TF coin picture */
fun etCoin(ctx: Context, size: Float = 16f): ImageView = ImageView(ctx).apply {
    setImageBitmap(Tf.pic(ctx, "home/tf_coin_sm.png")); scaleType = ImageView.ScaleType.FIT_CENTER
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(Ui.dpi(size), Ui.dpi(size))
}

/** five stars (Stars of etKit) */
fun etStars(ctx: Context, value: Double, size: Float = 13f): LinearLayout = etRow(ctx, 1f).apply {
    val v = Math.round(value).toInt()
    for (i in 1..5) put(etIcon(ctx, EtIcon.Star, size, Et.GOLD, 2f, if (i <= v) "#E8A317" else "none"), size, size)
    contentDescription = "$value out of 5"
}

/** a coloured pill (.pill p-ok / p-bad / p-warn / p-pur / p-grey / p-solid) */
fun etPill(ctx: Context, s: CharSequence, kind: String, icon: String? = null, iconSize: Float = 12f, h: Float = 22f): TextView {
    val (bg, fg) = when (kind) {
        "ok" -> Ui.c("#E6F5EC") to Ui.c("#157F44")
        "bad" -> Ui.c("#FDECEC") to Et.RED_TEXT
        "warn" -> Ui.c("#FFF3DC") to Ui.c("#7A4A00")
        "pur" -> Et.TINT to Et.PTEXT
        "dark" -> Et.INK to Color.WHITE
        "solid" -> Et.P to Color.WHITE
        else -> Ui.c("#F1EFF5") to Et.BODY
    }
    return etT(ctx, s, 11.5f, fg, true, 1f).apply {
        gravity = Gravity.CENTER; maxLines = 1
        background = Tf.box(bg, 11f)
        setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0)
        minHeight = Ui.dpi(h); height = Ui.dpi(h)
        if (icon != null) { compoundDrawablePadding = Ui.dpi(5f); setCompoundDrawablesWithIntrinsicBounds(etIconD(icon, iconSize, fg), null, null, null) }
    }
}

/** a round photo with the app's default avatar (Photo of etKit) */
@SuppressLint("ViewConstructor")
class EtPhoto(ctx: Context, private val sizeDp: Float) : View(ctx) {
    private var bmp: Bitmap? = null
    private var raw = ""
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#E8DFFD") }
    var demoKey = ""
    fun set(src: String?): EtPhoto {
        val s = src.orEmpty()
        if (s == raw && (bmp != null || s.isEmpty())) return this
        raw = s
        bmp = null
        if (demoKey.isNotEmpty() && Demo.on) { bmp = AvatarArt.forKey(demoKey, !demoKey.contains("-b"), px(), round = true); invalidate(); return this }
        if (s.isEmpty()) { invalidate(); return this }
        bmp = Photos.cached(listOf(s), px())
        invalidate()
        if (bmp == null) Photos.loadRaw(listOf(s), px()) { b -> if (raw == s && b != null) { bmp = b; invalidate() } }
        return this
    }
    private fun px() = Ui.dpi(sizeDp).coerceAtLeast(48)
    override fun onMeasure(w: Int, h: Int) { val s = Ui.dpi(sizeDp); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val b = bmp
        if (b != null) { c.drawCircle(width / 2f, height / 2f, width / 2f, bg); c.drawBitmap(b, null, dst, pb) } else DefaultAvatar.draw(c, width.toFloat())
    }
}

/** shimmering grey block (the loading outline, .etsk) */
class EtSkel(ctx: Context, private val radiusDp: Float, private val base: Int = Ui.c("#EFEBF6"), private val hi: Int = Ui.c("#F7F4FC")) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private fun sync() { if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) { if (!anim.isStarted) anim.start() } else anim.cancel() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { sync() } }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        // a light band moving right to left across the block, like the web's 400 % gradient sliding by
        val band = w * 2f
        val x0 = w - t * band * 2f
        p.shader = LinearGradient(x0, 0f, x0 + band, 0f, intArrayOf(base, hi, base), floatArrayOf(0f, .5f, 1f), Shader.TileMode.REPEAT)
        r.set(0f, 0f, w, h)
        val rr = Ui.dp(radiusDp)
        c.drawRoundRect(r, rr, rr, p)
    }
}

/** EditText styled like .field (46 high, round 13, 1.5 line, purple when focused) */
@SuppressLint("ViewConstructor")
class EtField(ctx: Context, hint: String, multi: Boolean = false, rows: Int = 4, maxLen: Int = 600) : EditText(ctx) {
    var onText: ((String) -> Unit)? = null
    init {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f))
        typeface = Ui.regular
        setTextColor(Et.INK)
        setHintTextColor(Ui.c("#9A97A3"))
        this.hint = hint
        includeFontPadding = false
        filters = arrayOf(InputFilter.LengthFilter(maxLen))
        background = Tf.box(Color.WHITE, 13f, 1.5f, Et.FIELD_LINE)
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = rows; gravity = Gravity.TOP or Gravity.START
            setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
            setLineSpacing(0f, 1.25f)
            minHeight = Ui.dpi(92f)
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true; maxLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            minHeight = Ui.dpi(46f)
        }
        setOnFocusChangeListener { _, f -> background = Tf.box(Color.WHITE, 13f, 1.5f, if (f) Et.P else Et.FIELD_LINE) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onText?.invoke(s?.toString().orEmpty()) }
        })
    }
    val value: String get() = text?.toString().orEmpty()
}

/** TextArea of etKit: a box that grows with a counter "12/600" under it */
fun etTextArea(ctx: Context, hint: String, max: Int = 600, rows: Int = 4, initial: String = "", onChange: (String) -> Unit): LinearLayout {
    val box = etCol(ctx, 4f)
    val f = EtField(ctx, hint, true, rows, max)
    f.setText(initial)
    val n = etT(ctx, "${initial.length}/$max", 11.5f, Et.MUT)
    f.onText = { s -> n.text = "${s.length}/$max"; onChange(s) }
    box.put(f, -1f, -2f)
    box.put(n, -2f, -2f, g = Gravity.END)
    return box
}

/**
 * The .btn of Expert Talk: primary purple, s2 (white with a line), red, green, wht (white);
 * [sm] = the 40-high small one, [pm] = the round premium one (.pm .btn.sm).
 */
@SuppressLint("ViewConstructor")
class EtBtn(ctx: Context, label: CharSequence, private val kind: String = "p", icon: String? = null, iconSize: Float = 18f,
            val sm: Boolean = false, private val pm: Boolean = false, private val stroke: Float = 2f, private val leadView: View? = null) : LinearLayout(ctx) {
    private val tv: EtText = etT(ctx, label, if (sm) 14f else 16f, Color.WHITE, true, 1f)
    private val iv: ImageView? = icon?.let { ImageView(ctx) }
    private val iconInner = icon
    private val iconPx = iconSize
    var heightDp: Float = if (sm) (if (pm) 42f else 40f) else 50f
    var radiusDp: Float = if (sm) (if (pm) 21f else 12f) else 15f
    var off = false
        set(v) { field = v; paint() }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
        dividerDrawable = EtGap(Ui.dpi(8f)); showDividers = SHOW_DIVIDER_MIDDLE
        if (leadView != null) addView(leadView)
        if (iv != null) addView(iv, LayoutParams(Ui.dpi(iconSize), Ui.dpi(iconSize)))
        addView(tv, LayoutParams(-2, -2))
        tv.maxLines = 1
        paint()
        Ui.pressable(this, .97f)
    }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(heightDp), MeasureSpec.EXACTLY))
    fun label(s: CharSequence) { tv.text = s }
    fun textSize(sp: Float) { tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(sp)) }
    fun paint() {
        val (bg, fg, line) = when {
            off && (kind == "s2" || kind == "redo") -> Triple(Ui.c("#F6F4F9"), Ui.c("#B5B0C2"), Et.LINE)
            off -> Triple(Ui.c("#C9BDF0"), Color.WHITE, 0)
            kind == "s2" -> Triple(Color.WHITE, Et.INK, Et.FIELD_LINE)
            kind == "red" -> Triple(Ui.c("#C92F35"), Color.WHITE, 0)
            kind == "redo" -> Triple(Color.WHITE, Et.RED_TEXT, Ui.c("#F3C4C6"))
            kind == "green" -> Triple(Ui.c("#157F44"), Color.WHITE, 0)
            kind == "wht" -> Triple(Color.WHITE, Et.INK, 0)
            kind == "whtp" -> Triple(Color.WHITE, Et.DEEP, 0)
            pm && sm -> Triple(Et.DEEP, Color.WHITE, 0)
            else -> Triple(Et.P, Color.WHITE, 0)
        }
        background = if (line != 0) Tf.box(bg, radiusDp, 1.5f, line) else Tf.box(bg, radiusDp)
        tv.setTextColor(fg)
        if (iv != null && iconInner != null) iv.setImageDrawable(Tf.lu(iconInner, fg, iconPx, stroke))
        isEnabled = !off
    }
    fun refreshShape() { paint(); requestLayout() }
}

/** the Expert Talk toast: a dark bar at the top of the page for 3.2 s */
class EtToast(private val host: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var v: TextView? = null
    private val hide = Runnable { v?.let { t -> t.animate().alpha(0f).setDuration(160).withEndAction { host.removeView(t); if (v === t) v = null }.start() } }
    fun show(msg: String) {
        if (msg.isBlank()) return
        main.removeCallbacks(hide)
        v?.let { host.removeView(it) }
        val t = etT(host.context, msg, 13.5f, Color.WHITE, false, 1.35f).apply {
            background = Tf.box(Et.INK, 14f)
            setPadding(Ui.dpi(14f), Ui.dpi(11f), Ui.dpi(14f), Ui.dpi(11f))
            elevation = Ui.dp(200f)   // above the pop-ups too (the web's toast sits over its sheets)
            outlineSpotShadowColorCompat(Color.argb(90, 0, 0, 0))
        }
        host.addView(t, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), 0) })
        t.alpha = 0f; t.translationY = -Ui.dp(8f)
        t.animate().alpha(1f).translationY(0f).setDuration(180).start()
        v = t
        main.postDelayed(hide, 3200)
    }
    fun clear() { main.removeCallbacks(hide); v?.let { host.removeView(it) }; v = null }
}

/** .altbox — the amber note box; [red]: the red one with the shield (icon at the top left) */
fun etAlert(ctx: Context, text: CharSequence, red: Boolean): LinearLayout = etRow(ctx, 10f, Gravity.TOP).apply {
    background = if (red) Tf.box(Ui.c("#FDECEC"), 16f, 1f, Ui.c("#F3C4C6")) else Tf.box(Ui.c("#FFF6E8"), 16f, 1f, Ui.c("#F6DDB0"))
    setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
    val fg = if (red) Ui.c("#8C1D22") else Ui.c("#6B4400")
    if (red) put(etIcon(ctx, EtIcon.ShieldAlert, 18f, fg), 18f, 18f)
    put(etT(ctx, text, 13.5f, fg), 0f, -2f, 1f)
}

/** a span helper: parts of a text in bold / colour */
class EtSpan {
    private val sb = SpannableStringBuilder()
    fun add(s: CharSequence, color: Int? = null, bold: Boolean = false): EtSpan {
        val a = sb.length; sb.append(s); val b = sb.length
        if (color != null) sb.setSpan(ForegroundColorSpan(color), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(EtBoldSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }
    fun build(): CharSequence = sb
}
/** Calibri bold inside a regular text (StyleSpan would fake it) */
class EtBoldSpan : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = Ui.bold }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = Ui.bold }
}

/** a coloured dot (gdot: 8 with a soft ring) */
class EtDot(ctx: Context, private val color: Int, private val sizeDp: Float = 8f, private val ringDp: Float = 3f, private val ringColor: Int = Color.argb(46, 31, 157, 85)) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) { val s = Ui.dpi(sizeDp + ringDp * 2); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (ringDp > 0f) { p.color = ringColor; c.drawCircle(cx, cy, Ui.dp(sizeDp / 2 + ringDp), p) }
        p.color = color; c.drawCircle(cx, cy, Ui.dp(sizeDp / 2), p)
    }
}

/** square icon tile (.sq / .sq.sm / .sq.p / .sq.pl) */
fun etSq(ctx: Context, inner: String, iconSize: Float, iconColor: Int, bg: Int, sizeDp: Float = 40f, radius: Float = 13f, round: Boolean = false, stroke: Float = 2f): FrameLayout =
    FrameLayout(ctx).apply {
        background = if (round) Tf.circle(bg) else Tf.box(bg, radius)
        addView(etIcon(ctx, inner, iconSize, iconColor, stroke), FrameLayout.LayoutParams(Ui.dpi(iconSize), Ui.dpi(iconSize), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
    }

/** a white card (.card): white, 1 px line, round 18 (premium pages: 20, lighter line) */
fun etCardBg(pm: Boolean = false, color: Int = Color.WHITE, line: Int = if (pm) Et.PM_LINE else Et.LINE, radius: Float = if (pm) 20f else 18f): GradientDrawable =
    Tf.box(color, radius, 1f, line)

/** a card's soft purple shadow (.shadow) */
fun View.etShadow(dp: Float = 3f) { elevation = Ui.dp(dp); outlineSpotShadowColorCompat(Color.argb(150, 48, 22, 120)) }

/** the 1 px line between rows */
fun etLine(ctx: Context, color: Int = Et.SOFT_LINE): View = View(ctx).apply { setBackgroundColor(color) }

/** typeface for the big quote mark (Georgia → the phone's serif) */
val etSerif: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.NORMAL) }

@Suppress("unused") private fun keepEt(a: StyleSpan) = a
