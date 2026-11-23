package app.truefind.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Helpers shared by the native Home, Profile and bottom bar
 * (sizes in dp = the web's CSS px; text in Calibri / Carlito like the web).
 */
object Tf {
    /** now, as the server writes times (UTC, ISO 8601 with milliseconds) */
    fun isoNow(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    /** The True Find blue of the "long train" loader */
    val BLUE = Ui.c("#5664EF")

    // ───────── text ─────────
    fun t(ctx: Context, s: CharSequence, sizeDp: Float, color: Int, bold: Boolean = true): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(sizeDp))
        setTextColor(color)
        typeface = if (bold) Ui.bold else Ui.regular
        includeFontPadding = false
    }

    /** text size in px (already scaled) */
    fun tpx(ctx: Context, s: CharSequence, px: Float, color: Int, bold: Boolean = true): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        setTextColor(color)
        typeface = if (bold) Ui.bold else Ui.regular
        includeFontPadding = false
    }

    fun TextView.one(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }

    /** line-height in dp (CSS line-height: 20px) */
    fun TextView.lh(dp: Float): TextView {
        val fm = paint.fontMetrics
        val natural = fm.descent - fm.ascent
        setLineSpacing(Ui.dp(dp) - natural, 1f)
        return this
    }

    // ───────── shapes ─────────
    fun box(color: Int, radiusDp: Float, strokeDp: Float = 0f, strokeColor: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = Ui.dp(radiusDp); if (strokeDp > 0f) setStroke(Ui.dpi(strokeDp).coerceAtLeast(1), strokeColor)
    }
    fun boxPx(color: Int, radiusPx: Float, strokePx: Float = 0f, strokeColor: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx; if (strokePx > 0f) setStroke(Math.round(strokePx).coerceAtLeast(1), strokeColor)
    }
    fun dashedBox(color: Int, radiusDp: Float, strokeDp: Float, strokeColor: Int, dashDp: Float = 5f, gapDp: Float = 4f): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = Ui.dp(radiusDp); setStroke(Ui.dpi(strokeDp).coerceAtLeast(1), strokeColor, Ui.dp(dashDp), Ui.dp(gapDp))
    }
    fun circle(color: Int, strokeDp: Float = 0f, strokeColor: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); if (strokeDp > 0f) setStroke(Ui.dpi(strokeDp).coerceAtLeast(1), strokeColor)
    }
    /** top corners only (sheets) */
    fun topRound(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color); val r = Ui.dp(radiusDp); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    // ───────── layout params ─────────
    const val M = LinearLayout.LayoutParams.MATCH_PARENT
    const val W = LinearLayout.LayoutParams.WRAP_CONTENT
    fun ll(w: Int = W, h: Int = W, weight: Float = 0f, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f, g: Int = -1) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)); if (g >= 0) gravity = g }

    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }
    fun col(ctx: Context, gravity: Int = Gravity.START) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; this.gravity = gravity }
    fun space(ctx: Context) = View(ctx)

    // ───────── icons ─────────
    /** a Lucide icon as a drawable */
    fun lu(inner: String, color: Int, sizeDp: Float, stroke: Float = 2f, fill: String = "none"): Drawable =
        SvgDrawable(Svg.lucide(inner, stroke, fill), sizeDp, sizeDp, color)

    fun svg(markup: String, wDp: Float, hDp: Float = wDp, tint: Int = Color.BLACK): Drawable = SvgDrawable(Svg.of(markup), wDp, hDp, tint)

    // ───────── pictures kept in the app (assets/home) ─────────
    private val pics = object : LruCache<String, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    fun pic(ctx: Context, path: String): Bitmap? {
        pics.get(path)?.let { return it }
        return try {
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }?.also { pics.put(path, it) }
        } catch (e: Exception) { null }
    }

    // ───────── data helpers (same rules as the web) ─────────
    fun s(o: JSONObject?, vararg keys: String): String {
        if (o == null) return ""
        for (k in keys) { val v = o.opt(k); if (v != null && v != JSONObject.NULL) { val t = v.toString(); if (t.isNotBlank()) return t } }
        return ""
    }
    fun n(o: JSONObject?, key: String): Double { val v = o?.opt(key) ?: return 0.0; return when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: 0.0; else -> 0.0 } }
    fun has(o: JSONObject?, key: String): Boolean { val v = o?.opt(key) ?: return false; return v != JSONObject.NULL && v.toString().isNotEmpty() }
    fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toString() }
    /** a list answer (array, { items }, { data }) */
    fun list(r: Api.Result): List<JSONObject> = arr(r.data.optJSONArray("items") ?: r.data.optJSONArray("data"))

    /** srcOf: a string, or { url | file_url | uri | file_uri | path } */
    fun srcOf(v: Any?): String = when (v) {
        is String -> v
        is JSONObject -> listOf("url", "file_url", "uri", "file_uri", "path").map { v.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
        else -> ""
    }

    private val ZONED = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
    private val PLAIN = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm")
    /**
     * Milliseconds of a date text; 0 = none. Like the web's new Date(v): a date
     * with a time but no zone is the phone's local time ([plainUtc] = treat it as
     * UTC, like the Support panel does); a bare date (2026-11-17) is UTC.
     */
    fun ms(v: String?, plainUtc: Boolean = false): Long {
        val s0 = v?.trim().orEmpty()
        if (s0.isEmpty()) return 0L
        val s = s0.replace(Regex("(\\.\\d{3})\\d+"), "$1").replace(Regex("\\.(\\d)(?!\\d)"), ".$100").replace(Regex("\\.(\\d\\d)(?!\\d)"), ".$10")
        fun tryParse(f: String, zone: TimeZone?): Long {
            return try {
                val p = SimpleDateFormat(f, Locale.US).apply { isLenient = false; if (zone != null) timeZone = zone }
                val pos = java.text.ParsePosition(0)
                val d = p.parse(s, pos)
                if (d != null && pos.index == s.length) d.time else 0L
            } catch (e: Exception) { 0L }
        }
        for (f in ZONED) { val t = tryParse(f, null); if (t > 0L) return t }
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return tryParse("yyyy-MM-dd", TimeZone.getTimeZone("UTC"))
        for (f in PLAIN) { val t = tryParse(f, if (plainUtc) TimeZone.getTimeZone("UTC") else null); if (t > 0L) return t }
        return 0L
    }

    private val IN = Locale("en", "IN")
    /** toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" }) → 17 Nov 2026 */
    fun date(v: String?): String {
        val t = ms(v); if (t <= 0L) return ""
        return SimpleDateFormat("d MMM yyyy", IN).format(Date(t))
    }
    /** toLocaleString("en-IN", { day, month: "short", hour, minute }) → 17 Nov, 3:05 pm */
    fun dateTime(v: String?, plainUtc: Boolean = false): String {
        val t = ms(v, plainUtc); if (t <= 0L) return ""
        return SimpleDateFormat("d MMM, h:mm a", IN).format(Date(t)).replace("AM", "am").replace("PM", "pm")
    }

    /** today in India (YYYY-MM-DD), like new Date(Date.now() + 5.5 h).toISOString().slice(0, 10) */
    fun todayIst(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(System.currentTimeMillis() + 19_800_000L))

    /** the server's error text when it is a readable sentence, else [fallback] (errText of Home.jsx) */
    fun err(r: Api.Result, fallback: String): String {
        val d = r.data
        val m = d.optString("error").takeIf { it.isNotBlank() } ?: d.optString("message")
        return if (m.isNotBlank() && m.length < 140 && !Regex("^[a-z_]+$").matches(m)) m else fallback
    }

    fun firstName(n: String?): String = (n ?: "").trim().split(Regex("\\s+")).firstOrNull().orEmpty()

    /** Indian grouping like toLocaleString("en-IN") */
    fun inr(v: Double): String = rupees(Math.floor(v).toLong())
    fun inr(v: Long): String = rupees(v)

    /** Paint for a canvas */
    fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    @Suppress("unused") private fun keep(c: Canvas) = c
}
