package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Control Center look (components/admin/ui.jsx): white page, black + premium purple,
 * Calibri, small clean text, flat (no gradients, no glow). Every native Control Center
 * screen is built from these, with the web's exact sizes (dp = the web's CSS px).
 */
object AK {
    val black = Ui.c("#17121F"); val black2 = Ui.c("#2B2438"); val onBlack = Ui.c("#B9B0C9")
    val purple = Ui.c("#6D28D9"); val deep = Ui.c("#4C1D95"); val tint = Ui.c("#F3EEFB"); val tintLine = Ui.c("#E2D6F7")
    val soft = Ui.c("#FAF9FC"); val line = Ui.c("#E9E6EE"); val ink = Ui.c("#17121F"); val sub = Ui.c("#5E586B"); val faint = Ui.c("#8A829B")
    val green = Ui.c("#0F6B3F"); val greenBg = Ui.c("#E3F5EA"); val amber = Ui.c("#7A4B00"); val amberBg = Ui.c("#FFF1D6")
    val red = Ui.c("#B91C1C"); val redBg = Ui.c("#FDECEC"); val blue = Ui.c("#1D4ED8"); val blueBg = Ui.c("#E8EEFD")
    val grey = Ui.c("#EEEAF4")
}

/** Numbers, dates and JSON reading, the same rules as ui.jsx (num, fmt, ago, dur, coinsTxt, rupees). */
object AF {
    /** Number(v) of JS, NaN → 0 */
    fun num(v: Any?): Double = when (v) {
        null, JSONObject.NULL -> 0.0
        is Number -> v.toDouble().takeIf { it.isFinite() } ?: 0.0
        is Boolean -> if (v) 1.0 else 0.0
        is String -> if (v.isBlank()) 0.0 else v.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        else -> 0.0
    }
    fun n(o: JSONObject?, k: String): Double = num(o?.opt(k))
    /** String(v || "") */
    fun s(o: JSONObject?, k: String): String = PR.s(o, k)
    fun b(o: JSONObject?, k: String): Boolean = PR.truthy(o?.opt(k))
    fun o(o: JSONObject?, k: String): JSONObject? = o?.optJSONObject(k)
    fun list(o: JSONObject?, k: String): List<JSONObject> = Tf.arr(o?.optJSONArray(k))
    fun strs(o: JSONObject?, k: String): List<String> = Tf.strs(o?.optJSONArray(k)).filter { it.isNotEmpty() }

    /** toDate of ui.jsx: a time without a zone is UTC */
    fun ms(s: String?): Long = Tf.ms(s, plainUtc = true)

    private val IN = Locale("en", "IN")
    private val IST = TimeZone.getTimeZone("Asia/Kolkata")
    /** toLocaleString("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) → 18 Nov, 12:35 pm */
    fun fmt(s: String?): String {
        val t = ms(s); if (t <= 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", IN).apply { timeZone = IST }.format(Date(t)).replace("AM", "am").replace("PM", "pm")
    }
    fun ago(s: String?): String {
        val t = ms(s); if (t <= 0L) return "—"
        return agoMs(t)
    }
    fun agoMs(t: Long): String {
        val sec = max(0L, ((System.currentTimeMillis() - t) / 1000.0).roundToLong())
        return when {
            sec < 60 -> "${sec}s ago"
            sec < 3600 -> "${sec / 60} min ago"
            sec < 86400 -> "${sec / 3600} h ago"
            else -> "${sec / 86400} d ago"
        }
    }
    fun dur(v: Any?): String {
        val s = max(0L, num(v).roundToLong())
        val m = s / 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "$m:${(s % 60).toString().padStart(2, '0')}"
    }
    /** toLocaleString("en-IN"): Indian grouping, at most [maxFrac] decimals */
    fun loc(v: Double, maxFrac: Int = 3): String {
        if (!v.isFinite()) return "0"
        val bd = BigDecimal.valueOf(v).setScale(maxFrac, RoundingMode.HALF_UP)
        val neg = bd.signum() < 0
        val abs = bd.abs()
        val whole = abs.toBigInteger()
        val frac = abs.subtract(BigDecimal(whole)).stripTrailingZeros()
        val fs = if (frac.signum() == 0) "" else frac.toPlainString().removePrefix("0")
        val s = rupees(whole.toLong()) + fs
        return if (neg && s != "0") "-$s" else s
    }
    fun nf(v: Any?): String = loc(num(v))
    fun coins(v: Any?): String = loc(floor(num(v) * 100 + 0.5) / 100)
    /** ₹ with exactly 2 decimals (toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })) */
    fun money2(v: Any?): String {
        val bd = BigDecimal.valueOf(num(v)).setScale(2, RoundingMode.HALF_UP)
        val neg = bd.signum() < 0
        val abs = bd.abs()
        val whole = abs.toBigInteger()
        val frac = abs.subtract(BigDecimal(whole)).setScale(2, RoundingMode.HALF_UP).toPlainString().removePrefix("0")
        return "₹${if (neg) "-" else ""}${rupees(whole.toLong())}$frac"
    }
    fun rup(v: Any?): String = loc(num(v))

    /** errText of ui.jsx: the server's error, else the request's own message, else [d] */
    fun err(r: Api.Result, d: String = "Something went wrong."): String =
        r.data.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: r.error ?: d
}

/** The building blocks of ui.jsx (Card, Btn, Pill, Row, Warn, Empty, Loading, ErrorLine, SectionTitle, Filters, Input, TextArea, Switch, Person). */
object AU {
    fun t(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false, lh: Float = 0f): TextView =
        Tf.t(ctx, s, size, color, bold).also { if (lh > 0f) it.lineH(lh) }
    fun col(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }
    fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)) }
    fun icon(ctx: Context, inner: String, color: Int, size: Float, stroke: Float = 2f): ImageView = ImageView(ctx).apply {
        setImageDrawable(Tf.lu(inner, color, size, stroke)); layoutParams = LinearLayout.LayoutParams(Ui.dpi(size), Ui.dpi(size))
    }
    /** a square with an icon in the middle (the 34 × 34 tinted tiles) */
    fun tile(ctx: Context, inner: String, bg: Int, fg: Int, size: Float = 34f, radius: Float = 10f, iconSize: Float = 17f): FrameLayout = FrameLayout(ctx).apply {
        background = Tf.box(bg, radius)
        addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(inner, fg, iconSize)) }, FrameLayout.LayoutParams(Ui.dpi(iconSize), Ui.dpi(iconSize), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(size), Ui.dpi(size))
    }

    /** Card: white, 1 px line, round 16, padding [pad] */
    fun card(ctx: Context, pad: Float = 14f, onClick: (() -> Unit)? = null): LinearLayout = col(ctx).apply {
        background = Tf.box(Color.WHITE, 16f, 1f, AK.line)
        val p = Ui.dpi(pad); setPadding(p, p, p, p)
        if (onClick != null) { setOnClickListener { onClick() }; Ui.pressable(this, 0.99f) }
    }

    fun btn(ctx: Context, label: CharSequence, kind: String = "dark", small: Boolean = false, icon: String? = null, iconSize: Float = 0f,
            onClick: (() -> Unit)? = null): AdBtn = AdBtn(ctx, label, kind, small, icon, iconSize).also { b -> if (onClick != null) b.setOnClickListener { if (!b.busy && !b.off) onClick() } }

    private val PILLS: Map<String, List<Any?>> by lazy {
        mapOf(
            "pending" to listOf(AK.amberBg, AK.amber, "Pending", AdIc.Clock),
            "open" to listOf(AK.amberBg, AK.amber, "Open", AdIc.Clock),
            "approved" to listOf(AK.greenBg, AK.green, "Approved", AdIc.CheckCircle2),
            "paid" to listOf(AK.greenBg, AK.green, "Paid", AdIc.CheckCircle2),
            "answered" to listOf(AK.greenBg, AK.green, "Replied", AdIc.CheckCircle2),
            "resolved" to listOf(AK.greenBg, AK.green, "Resolved", AdIc.CheckCircle2),
            "reviewed" to listOf(AK.greenBg, AK.green, "Reviewed", AdIc.CheckCircle2),
            "rejected" to listOf(AK.redBg, AK.red, "Rejected", AdIc.XCircle),
            "dismissed" to listOf(AK.grey, AK.sub, "Dismissed", AdIc.XCircle),
            "closed" to listOf(AK.grey, AK.sub, "Closed", AdIc.XCircle),
            "none" to listOf(AK.grey, AK.sub, "Not verified", null),
            "banned" to listOf(AK.redBg, AK.red, "Banned", null),
            "live" to listOf(AK.redBg, AK.red, "LIVE", null),
            "ringing" to listOf(AK.blueBg, AK.blue, "Ringing", null),
            "active" to listOf(AK.greenBg, AK.green, "On call", null),
        )
    }
    /** Pill: a status word on its colour (pending, approved, banned …), or [label] on [bg]/[fg] */
    fun pill(ctx: Context, status: String, label: String? = null, bg: Int? = null, fg: Int? = null): TextView {
        val p = PILLS[status]
        val back = bg ?: (p?.get(0) as? Int) ?: AK.tint
        val fore = fg ?: (p?.get(1) as? Int) ?: AK.deep
        val text = label ?: (p?.get(2) as? String) ?: status
        val ic = p?.get(3) as? String
        return Tf.t(ctx, text, 11.5f, fore, true).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1
            background = Tf.box(back, 999f)
            setPadding(Ui.dpi(8f), 0, Ui.dpi(8f), 0)
            minHeight = Ui.dpi(22f); height = Ui.dpi(22f)
            if (ic != null) { compoundDrawablePadding = Ui.dpi(4f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(ic, fore, 12f), null, null, null) }
        }
    }

    /** Row: label on the left (grey), value on the right ("—" when empty) */
    fun kv(ctx: Context, label: String, value: CharSequence?, strong: Boolean = false, valueColor: Int = AK.ink): LinearLayout {
        val r = row(ctx, Gravity.TOP)
        r.addView(t(ctx, label, 13f, AK.sub, false, 20f), lp(-2, -2, 0f, r = 12f))
        val v = if (value == null || value.isEmpty()) "—" else value
        r.addView(t(ctx, v, 13f, valueColor, true, 20f).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, lp(0, -2, 1f))
        return r
    }
    fun kvView(ctx: Context, label: String, value: View): LinearLayout {
        val r = row(ctx, Gravity.CENTER_VERTICAL)
        r.addView(t(ctx, label, 13f, AK.sub, false, 20f), lp(0, -2, 1f, r = 12f))
        r.addView(value, lp(-2, -2))
        return r
    }
    /** a stack of rows with [gap] between them */
    fun rows(ctx: Context, gap: Float = 3f, vararg views: View?): LinearLayout = col(ctx).apply {
        var first = true
        for (v in views) { if (v == null) continue; addView(v, lp(-1, -2, t = if (first) 0f else gap)); first = false }
    }

    /** Warn: a small red (or amber) line with a warning sign */
    fun warn(ctx: Context, text: String, tone: String = "red"): TextView {
        val (bg, fg) = if (tone == "amber") AK.amberBg to AK.amber else AK.redBg to AK.red
        return t(ctx, text, 12.5f, fg, true).apply {
            background = Tf.box(bg, 10f)
            setPadding(Ui.dpi(10f), Ui.dpi(6f), Ui.dpi(10f), Ui.dpi(6f))
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = Ui.dpi(7f)
            setCompoundDrawablesWithIntrinsicBounds(Tf.lu(AdIc.AlertTriangle, fg, 14f), null, null, null)
        }
    }

    fun empty(ctx: Context, icon: String?, title: String, text: String? = null): LinearLayout {
        val c = card(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(18f), Ui.dpi(34f), Ui.dpi(18f), Ui.dpi(34f)) }
        if (icon != null) c.addView(tile(ctx, icon, AK.tint, AK.deep, 44f, 13f, 21f), LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)).apply { bottomMargin = Ui.dpi(10f) })
        c.addView(t(ctx, title, 15f, AK.ink, true).apply { gravity = Gravity.CENTER })
        if (!text.isNullOrEmpty()) c.addView(t(ctx, text, 13f, AK.sub).apply { gravity = Gravity.CENTER }, lp(-2, -2, t = 4f))
        return c
    }

    fun loading(ctx: Context, pad: Float = 40f): FrameLayout = FrameLayout(ctx).apply {
        val p = Ui.dpi(pad); setPadding(p, p, p, p)
        addView(TfLoader(ctx, 24f, AK.purple), FrameLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.CENTER))
    }

    /** ErrorLine: the red line with "Retry" (nothing when [text] is empty) */
    fun errorLine(ctx: Context, text: String, onRetry: (() -> Unit)?): View {
        if (text.isEmpty()) return View(ctx).apply { visibility = View.GONE }
        val r = row(ctx).apply { background = Tf.box(AK.redBg, 12f); setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)) }
        r.addView(t(ctx, text, 13f, AK.red, true), lp(0, -2, 1f, r = 10f))
        if (onRetry != null) r.addView(t(ctx, "Retry", 13f, AK.red, true).apply { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; setOnClickListener { onRetry() } })
        r.layoutParams = lp(-1, -2, b = 12f)
        return r
    }

    /** SectionTitle: small grey capitals, with something on the right */
    fun section(ctx: Context, title: String, right: View? = null): LinearLayout {
        val r = row(ctx)
        r.addView(t(ctx, title.uppercase(Locale.ROOT), 12f, AK.faint, true).apply { letterSpacing = 0.5f / 12f }, lp(0, -2, 1f, r = 10f))
        if (right != null) r.addView(right, lp(-2, -2))
        r.layoutParams = lp(-1, -2, l = 2f, t = 18f, r = 2f, b = 8f)
        return r
    }

    /** a text link (the web's small purple text buttons) */
    fun link(ctx: Context, text: String, size: Float = 12.5f, color: Int = AK.deep, onClick: () -> Unit): TextView =
        t(ctx, text, size, color, true).apply { setOnClickListener { onClick() }; setPadding(0, Ui.dpi(4f), 0, Ui.dpi(4f)) }

    /** Filters: the small tabs (Pending / Approved / …), each with an optional count */
    fun filters(ctx: Context, items: List<Triple<String, String, Int>>, value: String, onChange: (String) -> Unit): HorizontalScrollView {
        val hs = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true
            background = Tf.box(AK.soft, 12f, 1f, AK.line)
            val p = Ui.dpi(4f); setPadding(p, p, p, p); clipToPadding = false
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        val r = row(ctx)
        items.forEachIndexed { i, (k, label, n) ->
            val on = value == k
            val b = row(ctx, Gravity.CENTER).apply {
                background = if (on) Tf.box(AK.black, 9f) else null
                setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                setOnClickListener { if (!on) onChange(k) }
            }
            b.addView(t(ctx, label, 13f, if (on) Color.WHITE else AK.sub, true).apply { maxLines = 1 })
            if (n > 0) b.addView(badge(ctx, n.toString(), if (on) AK.purple else AK.tint, if (on) Color.WHITE else AK.deep), lp(-2, Ui.dpi(18f), l = 6f))
            r.addView(b, LinearLayout.LayoutParams(-2, Ui.dpi(32f), 1f).apply { if (i > 0) leftMargin = Ui.dpi(6f) })
        }
        hs.addView(r, FrameLayout.LayoutParams(-1, -2))
        hs.layoutParams = lp(-1, -2, b = 12f)
        return hs
    }
    fun badge(ctx: Context, text: String, bg: Int, fg: Int): TextView = t(ctx, text, 11f, fg, true).apply {
        gravity = Gravity.CENTER; background = Tf.box(bg, 9f); minWidth = Ui.dpi(18f); setPadding(Ui.dpi(5f), 0, Ui.dpi(5f), 0)
    }

    fun input(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, onChange: ((String) -> Unit)? = null): AdInput =
        AdInput(ctx, hint, false, 1, type).also { e -> if (value.isNotEmpty()) e.setText(value); e.onText = onChange }
    fun area(ctx: Context, hint: String, rows: Int = 3, value: String = "", onChange: ((String) -> Unit)? = null): AdInput =
        AdInput(ctx, hint, true, rows, 0).also { e -> if (value.isNotEmpty()) e.setText(value); e.onText = onChange }
    fun label(ctx: Context, text: String, top: Float = 0f, bottom: Float = 6f): TextView =
        t(ctx, text, 12.5f, AK.ink, true).apply { layoutParams = lp(-1, -2, t = top, b = bottom) }

    /** Person: a face, the name (tap opens the member) and a small line under it */
    fun person(ctx: Context, src: AdSrc, p: JSONObject?, size: Float = 40f, sub: String? = null, onOpen: (() -> Unit)? = null, right: View? = null): LinearLayout {
        val name = AF.s(p, "name").ifEmpty { AF.s(p, "full_name") }.ifEmpty { "Unknown" }
        val r = row(ctx)
        val img = AdImg(ctx, src, round = true, wDp = size, hDp = size)
        img.set(AF.s(p, "photo").ifEmpty { AF.s(p, "profile_picture") })
        r.addView(img, LinearLayout.LayoutParams(Ui.dpi(size), Ui.dpi(size)))
        val c = col(ctx)
        c.addView(t(ctx, name, 14f, AK.ink, true).ell().apply { if (onOpen != null) setOnClickListener { onOpen() } })
        val line = sub ?: listOf(AF.s(p, "gender"), AF.s(p, "age").let { if (it.isNotEmpty()) "$it yrs" else "" }, AF.s(p, "city")).filter { it.isNotEmpty() }.joinToString(" · ")
        c.addView(t(ctx, line, 12f, AK.sub).ell())
        r.addView(c, lp(0, -2, 1f, l = 10f))
        if (right != null) r.addView(right, lp(-2, -2, l = 10f))
        return r
    }

    /** a line of buttons with [gap] between them, each taking an equal part when [fill] */
    fun btnRow(ctx: Context, gap: Float = 10f, fill: Boolean = true, vararg b: View?): LinearLayout {
        val r = row(ctx)
        var first = true
        for (v in b) { if (v == null) continue; r.addView(v, if (fill) LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) leftMargin = Ui.dpi(gap) } else LinearLayout.LayoutParams(-2, -2).apply { if (!first) leftMargin = Ui.dpi(gap) }); first = false }
        return r
    }
    /** buttons that wrap onto more lines (flex-wrap: wrap; gap) */
    fun wrap(ctx: Context, gap: Float = 8f, vararg b: View?): FlowRow = FlowRow(ctx, Ui.dpi(gap), Ui.dpi(gap)).apply { for (v in b) if (v != null) addView(v) }

    /** a tinted note box (Pose asked …, Line to say …) */
    fun note(ctx: Context, text: CharSequence, bg: Int = AK.tint, color: Int = AK.ink, size: Float = 13f): TextView =
        t(ctx, text, size, color).apply { background = Tf.box(bg, 12f); setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f)); setLineSpacing(0f, 1.15f) }

    /** "label: " in bold [labelColor] followed by the text */
    fun boldLead(lead: String, text: String, labelColor: Int): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        sb.append(lead)
        sb.setSpan(android.text.style.ForegroundColorSpan(labelColor), 0, lead.length, 0)
        sb.setSpan(TypefaceSpanCompat(Ui.bold), 0, lead.length, 0)
        sb.append(text)
        return sb
    }

    /** "lead " + bold middle + the rest (the web's "Decision: <b>…</b> — note") */
    fun boldLeadAfter(lead: String, bold: String, rest: String, @Suppress("UNUSED_PARAMETER") color: Int): CharSequence {
        val sb = android.text.SpannableStringBuilder(lead)
        val st = sb.length; sb.append(bold)
        sb.setSpan(TypefaceSpanCompat(Ui.bold), st, sb.length, 0)
        sb.append(rest)
        return sb
    }

    /** a chat bubble on its side of the line, at most [frac] of the width (maxWidth: 84%) */
    fun bubbleLine(ctx: Context, bubble: View, end: Boolean, frac: Float = 0.84f): FrameLayout {
        val line = object : FrameLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                val mx = (MeasureSpec.getSize(w) * frac).toInt()
                bubble.measure(MeasureSpec.makeMeasureSpec(mx, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                setMeasuredDimension(MeasureSpec.getSize(w), bubble.measuredHeight)
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val x = if (end) (r - l) - bubble.measuredWidth else 0
                bubble.layout(x, 0, x + bubble.measuredWidth, bubble.measuredHeight)
            }
        }
        line.addView(bubble)
        return line
    }

    /** a thin line between list rows (borderTop: 1px line) */
    fun divider(ctx: Context): View = View(ctx).apply { setBackgroundColor(AK.line); layoutParams = lp(-1, 1) }

    /** a card whose rows are separated by lines (Card pad 0 + borderTop on each row) */
    fun listCard(ctx: Context, rows: List<View>): LinearLayout {
        val c = card(ctx, 0f)
        c.clipToOutline = true; c.outlineProvider = ViewOutlineProvider.BACKGROUND
        rows.forEachIndexed { i, v -> if (i > 0) c.addView(divider(ctx)); c.addView(v, lp(-1, -2)) }
        return c
    }

    fun copy(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("True Find", text))
        } catch (e: Exception) { }
    }
}

/** A name that shrinks (…) so that what follows it (pills) stays right next to it — flex: name, then pills. */
@SuppressLint("ViewConstructor")
class AdNameRow(ctx: Context, private val name: TextView, private val after: View, private val gapDp: Float = 6f) : ViewGroup(ctx) {
    init { addView(name); addView(after) }
    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        after.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val aw = if (after.visibility == GONE || after.measuredWidth == 0) 0 else after.measuredWidth + Ui.dpi(gapDp)
        name.measure(MeasureSpec.makeMeasureSpec(max(0, w - aw), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) name.measuredWidth + aw else w, max(name.measuredHeight, after.measuredHeight))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        name.layout(0, (h - name.measuredHeight) / 2, name.measuredWidth, (h + name.measuredHeight) / 2)
        val x = name.measuredWidth + Ui.dpi(gapDp)
        after.layout(x, (h - after.measuredHeight) / 2, x + after.measuredWidth, (h + after.measuredHeight) / 2)
    }
}

/** Btn of ui.jsx: dark · purple · light · tint · danger · dangerLight · green; 42 high (small 34); busy → the loader. */
@SuppressLint("ViewConstructor")
class AdBtn(ctx: Context, label: CharSequence, val kind: String = "dark", private val small: Boolean = false, private val iconInner: String? = null, iconSize: Float = 0f) : FrameLayout(ctx) {
    val text: TextView
    private val loader: TfLoader
    private val fg: Int
    private val iconPx = if (iconSize > 0f) iconSize else if (small) 15f else 16f
    var busy = false
        set(v) { field = v; text.visibility = if (v) INVISIBLE else VISIBLE; loader.visibility = if (v) VISIBLE else GONE; refresh() }
    var off = false
        set(v) { field = v; refresh() }

    init {
        val r = if (small) 10f else 12f
        val (bg, f) = when (kind) {
            "purple" -> Tf.box(AK.purple, r) to Color.WHITE
            "light" -> Tf.box(Color.WHITE, r, 1.5f, AK.line) to AK.deep
            "tint" -> Tf.box(AK.tint, r) to AK.deep
            "danger" -> Tf.box(AK.red, r) to Color.WHITE
            "dangerLight" -> Tf.box(Color.WHITE, r, 1.5f, Ui.c("#F2C9C9")) to AK.red
            "green" -> Tf.box(AK.green, r) to Color.WHITE
            else -> Tf.box(AK.black, r) to Color.WHITE
        }
        fg = f
        background = bg
        val p = Ui.dpi(if (small) 12f else 16f)
        setPadding(p, 0, p, 0)
        text = Tf.t(ctx, label, if (small) 13f else 14f, fg, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            if (iconInner != null) { compoundDrawablePadding = Ui.dpi(6f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(iconInner, fg, iconPx), null, null, null) }
        }
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        loader = TfLoader(ctx, 16f, fg).apply { visibility = GONE }
        addView(loader, LayoutParams(Ui.dpi(16f), Ui.dpi(16f), Gravity.CENTER))
        Ui.pressable(this, 0.97f)
    }
    fun label(s: CharSequence) { text.text = s }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(if (small) 34f else 42f), MeasureSpec.EXACTLY))
    private fun refresh() { isEnabled = !off && !busy; alpha = if (off) 0.45f else 1f }
}

/** Input / TextArea of ui.jsx: 42 high (or [rows] lines), round 12, 1.5 line, 14 text. */
@SuppressLint("ViewConstructor")
class AdInput(ctx: Context, hint: String, multi: Boolean, rows: Int, type: Int) : EditText(ctx) {
    var onText: ((String) -> Unit)? = null
    init {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(14f))
        typeface = Ui.regular
        setTextColor(AK.ink)
        setHintTextColor(Ui.c("#757575"))
        this.hint = hint
        includeFontPadding = false
        background = Tf.box(Color.WHITE, 12f, 1.5f, AK.line)
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = rows; gravity = Gravity.TOP or Gravity.START
            setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
            setLineSpacing(0f, 1.15f)
            isVerticalScrollBarEnabled = true
        } else {
            inputType = if (type != 0) type else InputType.TYPE_CLASS_TEXT
            maxLines = 1; isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            minHeight = Ui.dpi(42f); height = Ui.dpi(42f)
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onText?.invoke(s?.toString().orEmpty()) }
        })
    }
    val value: String get() = text?.toString().orEmpty()
    /** keeps only what [clean] allows (the web's onChange(v.replace(...))) */
    fun filter(clean: (String) -> String) {
        addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                val now = s.toString(); val ok = clean(now)
                if (ok != now) { busy = true; s.replace(0, s.length, ok); busy = false }
            }
        })
    }
}

/** Switch of ui.jsx: 46 × 28, purple when on, #D9D3E3 when off. */
class AdSwitch(ctx: Context) : View(ctx) {
    var on = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    var disabledLook = false
        set(v) { field = v; alpha = if (v) 0.5f else 1f; isEnabled = !v }
    private var k = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()
    init { isClickable = true; setOnClickListener { onChange?.invoke(!on) } }
    fun set(v: Boolean, animate: Boolean = true) {
        if (v == on && (k == 1f) == v) return
        on = v
        if (!animate) { k = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(k, if (v) 1f else 0f).apply { duration = 150; addUpdateListener { k = it.animatedValue as Float; invalidate() }; start() }
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.dpi(46f), Ui.dpi(28f))
    override fun onDraw(c: Canvas) {
        p.color = blend(Ui.c("#D9D3E3"), AK.purple, k)
        r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, height / 2f, height / 2f, p)
        p.color = Color.WHITE
        c.drawCircle(Ui.dp(3f) + Ui.dp(11f) + Ui.dp(18f) * k, height / 2f, Ui.dp(11f), p)
    }
    private fun blend(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
}

/**
 * Grid of ui.jsx's "repeat(auto-fit, minmax(MIN, 1fr))" (or a fixed number of columns):
 * as many columns as fit, all the same width, the items of a row the same height.
 */
class AdGrid(ctx: Context, private val minDp: Float, private val gapDp: Float, private val fixedCols: Int = 0) : ViewGroup(ctx) {
    private fun cols(w: Int): Int {
        if (fixedCols > 0) return fixedCols
        val gap = Ui.dp(gapDp); val min = Ui.dp(minDp)
        return max(1, floor((w + gap) / (min + gap)).toInt())
    }
    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val kids = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val c = cols(w)
        val gap = Ui.dpi(gapDp)
        val cw = (w - gap * (c - 1)) / c
        var total = 0
        kids.chunked(c).forEachIndexed { ri, line ->
            var h = 0
            for (k in line) { k.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)); h = max(h, k.measuredHeight) }
            for (k in line) if (k.measuredHeight != h) k.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            total += h + if (ri > 0) gap else 0
        }
        setMeasuredDimension(w, total)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val c = cols(w)
        val gap = Ui.dpi(gapDp)
        val cw = (w - gap * (c - 1)) / c
        var y = 0
        (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }.chunked(c).forEach { line ->
            var x = 0; var h = 0
            for (k in line) { k.layout(x, y, x + cw, y + k.measuredHeight); x += cw + gap; h = max(h, k.measuredHeight) }
            y += h + gap
        }
    }
}

/**
 * PrivateImg of ui.jsx: a member's photo (private files get a signed address first);
 * round, or a box with round 12 corners. Nothing yet → the tinted box; no photo / can't
 * load → a face outline (round) or a short word. [onOpen] gets the photo's stored value.
 */
@SuppressLint("ViewConstructor")
class AdImg(ctx: Context, private val src: AdSrc, private val round: Boolean, private val wDp: Float = 0f, private val hDp: Float = 0f,
            private val aspect: Float = 0f, private val radius: Float = 12f) : FrameLayout(ctx) {
    private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private var uri = ""
    private var seq = 0
    var onOpen: ((String) -> Unit)? = null

    init {
        background = if (round) Tf.circle(AK.tint) else Tf.box(AK.tint, radius)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                if (round) o.setOval(0, 0, v.width, v.height) else o.setRoundRect(0, 0, v.width, v.height, Ui.dp(radius))
            }
        }
        clipToOutline = true
        addView(img, LayoutParams(-1, -1))
        setOnClickListener { if (img.drawable != null && uri.isNotEmpty()) onOpen?.invoke(uri) }
    }

    override fun onMeasure(w: Int, h: Int) {
        if (aspect > 0f) {
            val ww = MeasureSpec.getSize(w)
            super.onMeasure(MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((ww * aspect).roundToInt(), MeasureSpec.EXACTLY))
        } else super.onMeasure(w, h)
    }

    fun set(raw: String) {
        uri = raw
        ++seq
        img.setImageDrawable(null)
        removeNote()
        if (raw.isBlank()) { showMissing("No photo"); return }
        if (width > 0 && height > 0) load() else loadAfterLayout = true
    }

    /** the photo is read at the size it is shown (sharp, and no bigger than needed) */
    private var loadAfterLayout = false
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0 && (loadAfterLayout || (img.drawable == null && noteView == null && uri.isNotBlank()))) { loadAfterLayout = false; load() }
    }
    private fun load() {
        val my = seq
        val raw = uri
        val pw = width.coerceIn(Ui.dpi(24f), 900); val ph = height.coerceIn(Ui.dpi(24f), 1200)
        src.image(raw, pw, ph) { bmp ->
            if (my != seq) return@image
            if (bmp != null) img.setImageBitmap(bmp) else showMissing("Can't load")
        }
    }

    private var noteView: View? = null
    private fun removeNote() { noteView?.let { removeView(it) }; noteView = null }
    private fun showMissing(word: String) {
        removeNote()
        val v: View = if (round) {
            val s = max(14f, wDp * 0.5f)
            ImageView(context).apply { setImageDrawable(Tf.lu(AdIc.UserRound, Ui.c("#9B84CF"), s)) }.also { addView(it, LayoutParams(Ui.dpi(s), Ui.dpi(s), Gravity.CENTER)) }
        } else {
            AU.t(context, word, 10f, AK.faint).apply { gravity = Gravity.CENTER }.also { addView(it, LayoutParams(-2, -2, Gravity.CENTER)) }
        }
        noteView = v
    }
}

/** Bitmaps for AdImg and the photo viewer: assets (made-up data for test pictures) or the network (Photos). */
object AdPics {
    fun asset(ctx: Context, path: String, w: Int, h: Int): Bitmap? = try {
        val src = ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        if (src == null) null else cover(src, w, h)
    } catch (e: Throwable) { null }
    fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val k = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / k).toInt().coerceIn(1, src.width); val ch = (h / k).toInt().coerceIn(1, src.height)
        val sx = (src.width - cw) / 2; val sy = (src.height - ch) / 2
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}

/**
 * Sheet of ui.jsx: the bottom sheet (dim, white panel round 20 at the top, title and ✕ that
 * stay on top while the rest scrolls, at most 92% of the screen). Slides up; Back or a tap
 * on the dim closes it.
 */
class AdSheet(private val layer: TfLayer, title: String, private val onClose: () -> Unit = {}) {
    private val ctx: Context = layer.ctx
    val box = FrameLayout(ctx)
    private val dim = View(ctx).apply { setBackgroundColor(Color.argb(115, 23, 18, 31)); alpha = 0f }
    private val panel = AU.col(ctx)
    val body: LinearLayout = AU.col(ctx)
    private val scroll: ScrollView
    private val titleView: TextView
    var closed = false; private set
    /** the sheet can't be closed while true (a save is running) */
    var locked: () -> Boolean = { false }

    init {
        box.addView(dim, FrameLayout.LayoutParams(-1, -1))
        dim.setOnClickListener { if (!locked()) close() }
        panel.background = Tf.topRound(Color.WHITE, 20f)
        panel.isClickable = true
        panel.setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), 0)
        val head = AU.row(ctx)
        titleView = AU.t(ctx, title, 16f, AK.ink, true)
        head.addView(titleView, AU.lp(0, -2, 1f, r = 10f))
        val x = FrameLayout(ctx).apply {
            background = Tf.box(AK.soft, 10f)
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(AdIc.X, AK.sub, 17f)) }, FrameLayout.LayoutParams(Ui.dpi(17f), Ui.dpi(17f), Gravity.CENTER))
            contentDescription = "Close"
            setOnClickListener { if (!locked()) close() }
        }
        head.addView(x, LinearLayout.LayoutParams(Ui.dpi(34f), Ui.dpi(34f)))
        head.setPadding(0, Ui.dpi(4f), 0, 0)
        panel.addView(head, AU.lp(-1, -2, b = 12f))
        val hostH = { (layer.ctx.resources.displayMetrics.heightPixels) }
        scroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                val host = (box.parent as? View)?.height?.takeIf { it > 0 } ?: hostH()
                val maxH = (host * 0.92f).toInt() - Ui.dpi(14f + 4f + 34f + 12f)
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH.coerceAtLeast(Ui.dpi(120f)), MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        body.setPadding(0, 0, 0, Ui.dpi(18f))
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        panel.addView(scroll, AU.lp(-1, -2))
        box.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    fun title(s: String) { titleView.text = s }
    /** brings a part of the body to the top of the sheet (test pictures) */
    fun scrollToChild(v: View) {
        scroll.postDelayed({
            var y = 0; var c: View? = v
            while (c != null && c !== body) { y += c.top; c = c.parent as? View }
            scroll.smoothScrollTo(0, maxOf(0, y - Ui.dpi(4f)))
        }, 300)
    }

    /** puts the sheet on screen (after the body is filled) */
    fun show(): AdSheet {
        layer.fade(box, 40f, back = { if (!locked()) close(); true })
        box.alpha = 1f
        box.animate().cancel()
        dim.animate().alpha(1f).setDuration(200).start()
        panel.translationY = 4000f
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setStartDelay(40).setDuration(320).setInterpolator(SdLayer.EASE_UP).start()
        }
        return this
    }

    fun close() {
        if (closed) return
        closed = true
        try { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(box.windowToken, 0) } catch (e: Exception) { }
        dim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationY(panel.height.toFloat().coerceAtLeast(1f)).setStartDelay(0).setDuration(240).setInterpolator(SdLayer.EASE_UP)
            .withEndAction { layer.remove(box, false) }.start()
        onClose()
    }
    /** closes at once (no movement) */
    fun drop() { if (closed) return; closed = true; layer.remove(box, false); onClose() }
}

/** The web toast (components/ui/toast.jsx in this app's colours): a dark card at the top, red for problems, 5 s. */
class AdToast(private val host: FrameLayout) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var view: View? = null
    private val hide = Runnable { view?.let { v -> v.animate().alpha(0f).translationX(v.width * 0.3f).setDuration(200).withEndAction { host.removeView(v); if (view === v) view = null }.start() } }
    fun show(text: String, bad: Boolean = false) {
        main.removeCallbacks(hide)
        view?.let { host.removeView(it) }
        val ctx = host.context
        val bg = if (bad) Ui.c("#D92626") else Ui.c("#161F2C")
        val border = if (bad) Ui.c("#D92626") else Ui.c("#2B3B55")
        val fg = if (bad) Ui.c("#FAFAFA") else Ui.c("#F8FAFC")
        val card = FrameLayout(ctx).apply {
            background = Tf.box(bg, 14f, 1f, border)
            setPadding(Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(32f), Ui.dpi(24f))
            elevation = Ui.dp(200f)
            addView(AU.t(ctx, text, 14f, fg).apply { alpha = 0.9f; setLineSpacing(0f, 1.25f) }, FrameLayout.LayoutParams(-1, -2))
            setOnClickListener { main.removeCallbacks(hide); hide.run() }
        }
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { val m = Ui.dpi(16f); setMargins(m, m, m, 0) }
        host.addView(card, lp)
        card.alpha = 0f; card.translationY = -Ui.dp(60f)
        card.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(SdLayer.EASE_UP).start()
        view = card
        main.postDelayed(hide, 5000)
    }
    fun clear() { main.removeCallbacks(hide); view?.let { host.removeView(it) }; view = null }
}
