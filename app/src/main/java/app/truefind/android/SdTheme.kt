package app.truefind.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser

/**
 * Secret Dating's new look (owner, 20 Nov night, from his five reference photos): a dark top, a white sheet with a
 * little handle, the dark tab strip, the grey icon bar at the bottom, and the photos' font (Plus Jakarta Sans)
 * instead of Calibri on these pages. Colours: the owner's pick of 21 Nov night, "01 · Black + White + Yellow"
 * (it was maroon the night before): black tops, white sheets, yellow for what is chosen / mine / to tap.
 */
object SD {
    // owner's pick (21 Nov night, "01 · Black + White + Yellow"): black tops, white sheets, yellow highlights
    val HEAD = Ui.c("#0F0F12")       // the tops of the pages (black)
    val M = Ui.c("#FFC727")          // the highlight: chosen tab, my bubbles, like, counts, send
    val ON_M = Ui.c("#111114")       // words and icons on the yellow
    val M_TEXT = Ui.c("#9A7300")     // the yellow as words on white (darker, to be read)
    val M_DARK = Ui.c("#26262B")     // round buttons on the black top
    val M_LIGHT = Ui.c("#FFE088")    // the light send button (before there is something to send)
    val M_TINT = Ui.c("#FFF7E0")
    val STRIP = Ui.c("#1C1C21")      // the tabs strip
    val STRIP_OFF = Ui.c("#C9CDD6")
    val STRIP_CHIP = Ui.c("#2E2E34")
    val TXT = Ui.c("#1B1B1F")
    val SUB = Ui.c("#8E8E96")
    val TIME = Ui.c("#A2A2AA")
    val FIELD = Ui.c("#F7F7F7")
    val NAV_BG = Ui.c("#F3F3F3")
    val NAV_LINE = Ui.c("#E8E7EB")
    /** the open tab in the bottom bar: yellow (owner, 21 Nov night: "navigation bar click karne pr yellow ho") */
    val NAV_ON = Ui.c("#FFB800")
    /** the red dot on the heart (new likes) */
    val NAV_DOT = Ui.c("#E94057")
    val NAV_OFF = Ui.c("#ADAFBB")
    val GREEN = Ui.c("#3DBE7A")
    val ONLINE = Ui.c("#22C55E")
    val RED = Ui.c("#E5484D")
    val RED_TINT = Ui.c("#FFF0EF")
    val HANDLE = Ui.c("#D4D5D8")

    /** the phone's status bar: Secret Dating's pages run up under it (the Home photo, the maroon tops); MainActivity sets it */
    var topInset = 0

    /** where things sit on the maroon pages (dp below the status bar), the same on Chat, My Activity and Online Map */
    // (owner, 21 Nov morning: everything a bit lower under the status bar, the words smaller and lighter)
    const val TITLE_TOP = 78f
    const val STRIP_TOP = 136f
    const val SHEET_TOP = 200f

    fun sheetBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        val r = Ui.dp(26f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    fun stripBg(): GradientDrawable = GradientDrawable().apply {
        setColor(STRIP)
        val r = Ui.dp(26f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
    /** a see-through round button over a photo (the photo's "glass" look) */
    fun glass(dark: Boolean, radiusDp: Float = 999f): GradientDrawable = GradientDrawable().apply {
        setColor(if (dark) Color.argb(72, 44, 12, 28) else Color.argb(36, 255, 255, 255))
        cornerRadius = Ui.dp(radiusDp)
        setStroke(maxOf(1, Ui.dpi(1f)), Color.argb(if (dark) 62 else 70, 255, 255, 255))
    }
    /** "Good Morning," / "Good Afternoon," / "Good Evening," by the phone's clock */
    fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) { in 4..11 -> "Good Morning,"; in 12..16 -> "Good Afternoon,"; else -> "Good Evening," }
    }
    /** "08.15 PM" (the photo's way of writing the time) */
    fun clock(ms: Long): String {
        if (ms <= 0) return ""
        return java.text.SimpleDateFormat("hh.mm a", java.util.Locale.US).format(java.util.Date(ms)).uppercase()
    }
}

/** Plus Jakarta Sans (assets/sd-fonts), the photos' font; the phone's own font when a file is missing. */
object Jk {
    lateinit var regular: Typeface
    lateinit var medium: Typeface
    lateinit var semibold: Typeface
    lateinit var bold: Typeface
    lateinit var extrabold: Typeface
    private var ready = false
    fun init(ctx: Context) {
        if (ready) return
        ready = true
        fun load(n: String, fb: Typeface): Typeface = try { Typeface.createFromAsset(ctx.assets, "sd-fonts/PlusJakartaSans-$n.ttf") } catch (e: Exception) { fb }
        regular = load("Regular", Typeface.SANS_SERIF)
        medium = load("Medium", regular)
        semibold = load("SemiBold", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
        bold = load("Bold", semibold)
        extrabold = load("ExtraBold", bold)
    }
    fun of(weight: Int): Typeface {
        if (!ready) return if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // one step lighter for the heavy ones (owner, 21 Nov morning: the words looked too fat)
        return when { weight >= 800 -> bold; weight >= 600 -> semibold; weight >= 500 -> medium; else -> regular }
    }
}

/** a line of text in the photos' font */
fun jk(ctx: Context, s: CharSequence, sizeDp: Float, color: Int, weight: Int = 500): TextView = TextView(ctx).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(sizeDp))
    setTextColor(color)
    typeface = Jk.of(weight)
    includeFontPadding = false
}

/** the soft lighter planes across the dark top (the photo's "edge" look) */
class MaroonDeco(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val d = Ui.density
        p.color = Color.argb(18, 255, 255, 255)
        c.save(); c.translate(120 * d + 260 * d, -260 * d + 310 * d); c.rotate(38f)
        r.set(-260 * d, -310 * d, 260 * d, 310 * d); c.drawRoundRect(r, 80 * d, 80 * d, p); c.restore()
        p.color = Color.argb(13, 255, 255, 255)
        c.save(); c.translate(250 * d + 210 * d, -200 * d + 280 * d); c.rotate(38f)
        r.set(-210 * d, -280 * d, 210 * d, 280 * d); c.drawRoundRect(r, 80 * d, 80 * d, p); c.restore()
    }
}

/**
 * A layer that keeps its content clear of the phone's status bar: SD.topInset (known only once the window has its
 * insets — it is read again at every measure). [fixedDp] ≥ 0: its own height is the status bar + that much.
 */
open class InsetFrame(ctx: Context, private val fixedDp: Float = -1f) : FrameLayout(ctx) {
    init { clipToPadding = false }
    override fun onMeasure(w: Int, h: Int) {
        if (paddingTop != SD.topInset) setPadding(paddingLeft, SD.topInset, paddingRight, paddingBottom)
        if (fixedDp >= 0f) super.onMeasure(w, MeasureSpec.makeMeasureSpec(SD.topInset + Ui.dpi(fixedDp), MeasureSpec.EXACTLY))
        else super.onMeasure(w, h)
    }
}

/** a strip as tall as the phone's status bar, in one colour (over a page that scrolls under the bar) */
class StatusStrip(ctx: Context, color: Int) : View(ctx) {
    init { setBackgroundColor(color); isClickable = false }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), SD.topInset)
}

/** the little grey handle at the top of a white sheet */
fun sdHandle(ctx: Context, wDp: Float = 80f): View = View(ctx).apply {
    background = GradientDrawable().apply { setColor(SD.HANDLE); cornerRadius = Ui.dp(3f) }
    layoutParams = FrameLayout.LayoutParams(Ui.dpi(wDp), Ui.dpi(6f), Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(10f) }
}

/** icons drawn from SVG paths (24 × 24): filled, or stroked with round ends */
class SdIcon(private val parts: List<Pair<String, Int>>, private val sizeDp: Float, private val stroke: Float = 0f) : android.graphics.drawable.Drawable() {
    private val paths = parts.map { PathParser.createPathFromPathData(it.first) to it.second }
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = Ui.dpi(sizeDp)
    override fun getIntrinsicHeight() = Ui.dpi(sizeDp)
    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f)
        for ((path, col) in paths) {
            if (stroke > 0f) { ps.color = col; ps.strokeWidth = stroke; c.drawPath(path, ps) }
            else { pf.color = col; c.drawPath(path, pf) }
        }
        c.restore()
    }
    override fun setAlpha(a: Int) { pf.alpha = a; ps.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** the new pages' icon shapes (24 × 24), the same as the preview */
object SdPaths {
    const val CARD_BACK = "M12.2 6.4h6.6a1.8 1.8 0 0 1 1.8 1.8v11a1.8 1.8 0 0 1-1.8 1.8h-6.6a1.8 1.8 0 0 1-1.8-1.8v-11a1.8 1.8 0 0 1 1.8-1.8z"
    const val HEART_FILL = "M12 20.6c-.3 0-.6-.1-.8-.3C7.4 17.2 2.6 13.4 2.6 8.9 2.6 6 4.8 3.8 7.5 3.8c1.8 0 3.4.9 4.5 2.4 1.1-1.5 2.7-2.4 4.5-2.4 2.7 0 4.9 2.2 4.9 5.1 0 4.5-4.8 8.3-8.6 11.4-.2.2-.5.3-.8.3Z"
    const val CHAT_FILL = "M12.2 2.8a9.2 9.2 0 1 1 0 18.4H4.4a1.6 1.6 0 0 1-1.6-1.6V12a9.2 9.2 0 0 1 9.4-9.2Z"
    const val CHAT_LINES = "M8.1 9.6h8.2M8.1 12.9h8.2M8.1 16.2h4.7"
    const val PIN_FILL = "M12 2.3c-4.3 0-7.7 3.3-7.7 7.6 0 5.4 6.3 11.4 7.1 12 .35.3.85.3 1.2 0 .8-.6 7.1-6.6 7.1-12 0-4.3-3.4-7.6-7.7-7.6Z"
    const val BELL = "M6 8.6a6 6 0 0 1 12 0c0 6.4 2.6 8.3 2.6 8.3H3.4S6 15 6 8.6M10.2 20.4a2 2 0 0 0 3.6 0"
    const val BELL_FILL = "M12 2.6a6.4 6.4 0 0 0-6.4 6.4c0 6-2.6 7.8-2.6 7.8-.5.4-.3 1.2.4 1.2h17.2c.7 0 .9-.8.4-1.2 0 0-2.6-1.8-2.6-7.8A6.4 6.4 0 0 0 12 2.6Z"
    const val BELL_CLAPPER = "M9.7 20.2a2.6 2.6 0 0 0 4.6 0"
    const val REWIND = "M20.2 9.8A8.4 8.4 0 0 0 5.5 6.6L4 8.2M4 3.9v4.3h4.3M3.8 14.2a8.4 8.4 0 0 0 14.7 3.2l1.5-1.6M20 20.1v-4.3h-4.3"
    const val CHATS = "M17.3 10.1c0-4-3.3-7.1-7.4-7.1S2.5 6.1 2.5 10.1c0 1.6.5 3 1.4 4.2l-.9 3.3 3.4-.9a7.6 7.6 0 0 0 3.5.9M13.1 15.4a4.7 4.7 0 1 1 8.4 2.9l.6 2.6-2.6-.7a4.7 4.7 0 0 1-6.4-4.8Z"
    const val XSQ = "M8.2 4h7.6A4.2 4.2 0 0 1 20 8.2v7.6a4.2 4.2 0 0 1-4.2 4.2H8.2A4.2 4.2 0 0 1 4 15.8V8.2A4.2 4.2 0 0 1 8.2 4zM9.6 9.6l4.8 4.8M14.4 9.6l-4.8 4.8"
    const val HEART = "M12 20.2C8.3 17.3 3.4 13.6 3.4 9.1 3.4 6.4 5.5 4.3 8 4.3c1.6 0 3 .8 4 2.2 1-1.4 2.4-2.2 4-2.2 2.5 0 4.6 2.1 4.6 4.8 0 4.5-4.9 8.2-8.6 11.1Z"
    const val MAP_PIN = "M19.2 10.1c0 5.1-5.5 10-6.7 11a.8.8 0 0 1-1 0c-1.2-1-6.7-5.9-6.7-11a7.2 7.2 0 0 1 14.4 0ZM14.6 10.1a2.6 2.6 0 1 1-5.2 0 2.6 2.6 0 0 1 5.2 0Z"
    const val MORE = "M12 3.5a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8zm0 6.6a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8zm0 6.6a1.9 1.9 0 1 1 0 3.8 1.9 1.9 0 0 1 0-3.8z"
    const val GIFT_BASE = "M5.8 10.2h12.4a2.2 2.2 0 0 1 2.2 2.2v6.2a2.2 2.2 0 0 1-2.2 2.2H5.8a2.2 2.2 0 0 1-2.2-2.2v-6.2a2.2 2.2 0 0 1 2.2-2.2z"
    const val GIFT_LID = "M4.2 7h15.6a1.6 1.6 0 0 1 1.6 1.6V10a1.6 1.6 0 0 1-1.6 1.6H4.2A1.6 1.6 0 0 1 2.6 10V8.6A1.6 1.6 0 0 1 4.2 7z"
    const val GIFT_RIBBON = "M10.9 7h2.2v13.8h-2.2z"
    const val GIFT_BOW = "M12 7C10.8 3.6 6.6 3.2 6.6 5.4 6.6 6.8 8.6 7 12 7Zm0 0c1.2-3.4 5.4-3.8 5.4-1.6 0 1.4-2 1.6-5.4 1.6Z"
    const val SEND_FILL = "M20.4 11.1 5.2 3.8c-1-.5-2 .5-1.7 1.5L6 12l-2.5 6.7c-.3 1 .7 2 1.7 1.5l15.2-7.3c.8-.4.8-1.4 0-1.8Z"
    const val SEND_LINE = "M6.4 12h6"
    const val SEARCH = "M17.4 10.8a6.6 6.6 0 1 1-13.2 0 6.6 6.6 0 0 1 13.2 0zM20 20l-4.4-4.4"
    const val PUSHPIN = "M15.2 2.9a1.2 1.2 0 0 1 1.7 0l4.2 4.2a1.2 1.2 0 0 1 0 1.7l-.9.9a1.2 1.2 0 0 1-1.3.3l-2.5 2.5.4 3.6a1.2 1.2 0 0 1-.4 1l-1.1 1a1 1 0 0 1-1.4 0l-3.3-3.3-4.5 4.6a1 1 0 0 1-1.4-1.4l4.6-4.5-3.3-3.3a1 1 0 0 1 0-1.4l1-1.1a1.2 1.2 0 0 1 1-.4l3.6.4 2.5-2.5a1.2 1.2 0 0 1 .3-1.3l.9-.9Z"
    const val CHECK = "M5 12.6 9.6 17 19 7.4"
    const val CHECK2 = "M2.4 12.8 6.6 17l9.2-9.6M12.6 16l1 1 9-9.6"
    const val TRASH_BODY = "M5.6 7.6h12.8l-.9 11.6a2 2 0 0 1-2 1.8H8.5a2 2 0 0 1-2-1.8L5.6 7.6Z"
    const val TRASH_TOP = "M3.6 5.8h16.8M9.2 5.6V4.4c0-.7.6-1.2 1.2-1.2h3.2c.7 0 1.2.5 1.2 1.2v1.2"
    const val TRASH_LINES = "M10.2 11.2v5.6M13.8 11.2v5.6"
    const val TRASH_LINE = "M3.6 6.2h16.8M8.8 6V4.6c0-.8.6-1.4 1.4-1.4h3.6c.8 0 1.4.6 1.4 1.4V6M18.2 6.2l-.8 12.6a2 2 0 0 1-2 1.9H8.6a2 2 0 0 1-2-1.9L5.8 6.2M10.2 10.8v5.6M13.8 10.8v5.6"
    const val BAN = "M20.8 12a8.8 8.8 0 1 1-17.6 0 8.8 8.8 0 0 1 17.6 0zM5.8 5.8l12.4 12.4"
    const val PIN_LINE = "M12 16.8v4.6M9 10.6a2 2 0 0 1-1.1 1.8l-1.8.9A2 2 0 0 0 5 15.1v.8a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.8a2 2 0 0 0-1.1-1.8l-1.8-.9a2 2 0 0 1-1.1-1.8V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"
    const val SHIELD = "M20 13c0 5-3.5 7.5-7.7 9a1 1 0 0 1-.7 0C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.2-2.7a1.2 1.2 0 0 1 1.6 0C14.5 3.8 17 5 19 5a1 1 0 0 1 1 1zM9 12l2 2 4-4"
    const val LOCK = "M7.1 10.5h9.8a2.6 2.6 0 0 1 2.6 2.6v5.3a2.6 2.6 0 0 1-2.6 2.6H7.1a2.6 2.6 0 0 1-2.6-2.6v-5.3a2.6 2.6 0 0 1 2.6-2.6zM8 10.5V7.6a4 4 0 0 1 8 0v2.9"
    const val TWO_HEARTS_BACK = "M8.4 17.8c-.2 0-.4-.1-.5-.2C5 15.3 1.8 12.7 1.8 9.6c0-2 1.5-3.5 3.4-3.5 1.3 0 2.4.6 3.2 1.7.8-1.1 1.9-1.7 3.2-1.7 1.9 0 3.4 1.5 3.4 3.5 0 3.1-3.2 5.7-6.1 8-.1.1-.3.2-.5.2Z"
    const val TWO_HEARTS_FRONT = "M15.6 20.6c-.2 0-.4-.1-.5-.2-2.6-2.1-5.5-4.4-5.5-7.3 0-1.8 1.4-3.2 3.1-3.2 1.2 0 2.2.6 2.9 1.6.7-1 1.7-1.6 2.9-1.6 1.7 0 3.1 1.4 3.1 3.2 0 2.9-2.9 5.2-5.5 7.3-.1.1-.3.2-.5.2Z"
    const val CROWN = "M3.2 8.6 7.6 12l4.4-6.2 4.4 6.2 4.4-3.4-1.8 9.6H5zM6 19h12a1 1 0 0 1 0 2H6a1 1 0 0 1 0-2z"
    const val HOURGLASS = "M6 2.8h12M6 21.2h12M7.2 2.8c0 5 9.6 5.6 9.6 9.2S7.2 16.2 7.2 21.2M16.8 2.8c0 5-9.6 5.6-9.6 9.2s9.6 4.2 9.6 9.2"
    const val PLUS = "M12 5v14M5 12h14"
    const val X = "M18 6 6 18M6 6l12 12"
    const val RULER = "M9.7 2.8h4.6a2.4 2.4 0 0 1 2.4 2.4v13.6a2.4 2.4 0 0 1-2.4 2.4H9.7a2.4 2.4 0 0 1-2.4-2.4V5.2a2.4 2.4 0 0 1 2.4-2.4zM7.6 7.4h3M7.6 10.5h4.6M7.6 13.6h3M7.6 16.7h4.6"
    const val BRIEFCASE = "M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16M4 6h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"
    const val HEADPHONES = "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"
    const val PLANE = "M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z"
    const val CLAPPER = "M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3ZM6.2 5.3l3.1 3.9M12.4 3.4l3.1 4M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"
    const val SPARKLE = "M9.9 15.5a2 2 0 0 0-1.4-1.4l-6.1-1.6a.5.5 0 0 1 0-1l6.1-1.6a2 2 0 0 0 1.4-1.4l1.6-6.1a.5.5 0 0 1 1 0l1.6 6.1a2 2 0 0 0 1.4 1.4l6.1 1.6a.5.5 0 0 1 0 1l-6.1 1.6a2 2 0 0 0-1.4 1.4l-1.6 6.1a.5.5 0 0 1-1 0z"
    const val COIN = "M14 8a6 6 0 1 1-12 0 6 6 0 0 1 12 0zM18.1 10.4A6 6 0 1 1 10.3 18M7 6h1v4M16.7 13.9l.7.7-2.8 2.8"
}

fun sdIcon(d: String, color: Int, sizeDp: Float, stroke: Float = 0f) = SdIcon(listOf(d to color), sizeDp, stroke)

/** an icon in a view of its own */
fun sdIconView(ctx: Context, d: android.graphics.drawable.Drawable): ImageView = ImageView(ctx).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.CENTER_INSIDE }

/**
 * The maroon top of Chat, My Activity and Online Map (the chat list photo): my photo, "Good Evening," and my
 * name with a 👋, the bell on the right (opens My Activity), then the page's big title (+ a badge or pill).
 * (Placed inside an InsetFrame: its spots are measured from under the status bar.)
 */
class SdTop(ctx: Context, private val h: SecretHome, title: String, bell: Boolean, val extra: View? = null, subtitle: String? = null) : FrameLayout(ctx) {
    private val photo = RoundPhoto(ctx, 46f)
    private val hello = jk(ctx, SD.greeting(), 11.5f, Color.argb(200, 255, 255, 255), 500)
    private val name = jk(ctx, "", 15.5f, Color.WHITE, 700).oneLine()
    private val bellBox = FrameLayout(ctx)
    private val bellDot = View(ctx)
    val titleView = jk(ctx, title, 27f, Color.WHITE, 600).oneLine()
    private val titleRow = LinearLayout(ctx)

    init {
        clipChildren = false
        val me = FrameLayout(ctx).apply { contentDescription = "My profile" }
        me.addView(photo, LayoutParams(-1, -1))
        me.press().onTap { h.openMyProfile(false) }
        addView(me, LayoutParams(dpi(42f), dpi(42f)).apply { leftMargin = dpi(16f); topMargin = dpi(14f) })
        val texts = vbox(ctx)
        texts.addView(hello); texts.addView(name, lp(WRAP, WRAP, t = 3f))
        addView(texts, LayoutParams(-1, -2).apply { leftMargin = dpi(68f); rightMargin = dpi(if (bell) 66f else 16f); topMargin = dpi(16f) })
        if (bell) {
            bellBox.background = oval(SD.M_DARK)
            bellBox.addView(sdIconView(ctx, SdIcon(listOf(SdPaths.BELL_FILL to Color.WHITE), 21f)), LayoutParams(dpi(21f), dpi(21f), Gravity.CENTER))
            bellBox.addView(sdIconView(ctx, SdIcon(listOf(SdPaths.BELL_CLAPPER to Color.WHITE), 21f, 1.9f)), LayoutParams(dpi(21f), dpi(21f), Gravity.CENTER))
            bellDot.background = oval(Ui.c("#FF6A3D"))
            bellDot.visibility = GONE
            bellBox.addView(bellDot, LayoutParams(dpi(9f), dpi(9f)).apply { leftMargin = dpi(13f); topMargin = dpi(11f) })
            bellBox.contentDescription = "My Activity"
            bellBox.press().onTap { h.goLikes() }
            addView(bellBox, LayoutParams(dpi(40f), dpi(40f), Gravity.END).apply { rightMargin = dpi(16f); topMargin = dpi(15f) })
        }
        titleRow.orientation = LinearLayout.HORIZONTAL; titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(titleView)
        if (extra != null) titleRow.addView(extra, lp(WRAP, WRAP, l = 14f))
        addView(titleRow, LayoutParams(-1, dpi(40f)).apply { leftMargin = dpi(16f); rightMargin = dpi(16f); topMargin = dpi(SD.TITLE_TOP) })
        if (subtitle != null) addView(jk(ctx, subtitle, 12f, Color.argb(190, 255, 255, 255), 500),
            LayoutParams(-2, -2).apply { leftMargin = dpi(16f); topMargin = dpi(SD.TITLE_TOP + 38f) })
        update()
    }

    /** my photo, my name, the clock's greeting and the bell's dot */
    fun update() {
        photo.set(listOf(h.myPhotoRaw))
        name.text = h.myFullName.ifEmpty { "Member" } + " 👋"
        hello.text = SD.greeting()
        bellDot.visibility = if (h.hasNewActivity) VISIBLE else GONE
        bellBox.contentDescription = if (h.hasNewActivity) "My Activity, new" else "My Activity"
    }
}

/** the black strip's pill tabs ("All · Unread · Online", "Liked you · Matches") */
class SdTabs(ctx: Context, private val labels: List<String>, private val pillW: Float, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {
    var picked = 0; private set
    private val cells = ArrayList<Pair<LinearLayout, Pair<TextView, TextView>>>()
    init {
        orientation = HORIZONTAL
        setPadding(dpi(16f), dpi(12f), dpi(16f), 0)
        for ((i, l) in labels.withIndex()) {
            val cell = FrameLayout(ctx)
            val pill = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            val t = jk(ctx, l, 13.5f, SD.STRIP_OFF, 500)
            val n = jk(ctx, "", 11.5f, Color.WHITE, 700).apply { gravity = Gravity.CENTER; minWidth = dpi(22f); setPadding(dpi(6f), 0, dpi(6f), 0); visibility = GONE }
            pill.addView(t); pill.addView(n, lp(WRAP, dpi(22f), l = 8f))
            cell.addView(pill, FrameLayout.LayoutParams(dpi(pillW), dpi(40f), Gravity.CENTER))
            cell.contentDescription = l
            cell.press().onTap { if (picked != i) { pick(i); onPick(i) } }
            addView(cell, lp(0, dpi(40f), 1f))
            cells.add(pill to (t to n))
        }
        pick(0)
    }
    fun pick(i: Int) {
        picked = i
        for ((k, c) in cells.withIndex()) {
            val on = k == i
            c.first.background = if (on) rounded(SD.M, 20f) else null
            c.second.first.setTextColor(if (on) SD.ON_M else SD.STRIP_OFF)
            // the counts in green (owner, 21 Nov morning)
            c.second.second.setTextColor(Color.WHITE)
            c.second.second.background = rounded(SD.GREEN, 11f)
        }
    }
    /** a count beside a tab's name (null = none) */
    fun count(i: Int, n: Int?) {
        val v = cells.getOrNull(i)?.second?.second ?: return
        v.visibility = if (n == null) GONE else VISIBLE
        v.text = n?.toString() ?: ""
        cells[i].first.parent.let { (it as? View)?.contentDescription = labels[i] + if (n != null) ", $n" else "" }
    }
}

/** a pink of the old look turned into the black-white-yellow pick (by how light it is): dark → black, mid → yellow, light → cream */
object SdTone {
    fun toTheme(c: Int): Int {
        val hsl = FloatArray(3); androidx.core.graphics.ColorUtils.colorToHSL(c, hsl)
        val l = hsl[2]
        val out = when { l < .33f -> 0x1B1B1F; l < .58f -> 0xE0A800; l < .80f -> 0xF0D58C; l < .93f -> 0xFFF0C2; else -> 0xFFF9E8 }
        return Color.argb(Color.alpha(c), (out shr 16) and 255, (out shr 8) and 255, out and 255)
    }
}

/**
 * The colours of the other Secret Dating pages (a profile, My profile, the sheets, stories) in the owner's pick of
 * 21 Nov night — black, white and yellow — with the same names as the old pink set (K), so those pages read KY.
 * Buttons with white words are black now (white on yellow can't be read); yellow marks, black tops.
 */
object KY : SdPal {
    override val wine = Ui.c("#1B1B1F"); override val wine2 = Ui.c("#26262B"); override val rose = Ui.c("#E0A800"); override val hot = Ui.c("#E0A800"); override val deep = Ui.c("#1B1B1F")
    override val ink = Ui.c("#1B1A1D"); override val ink2 = Ui.c("#24242A"); override val sub = Ui.c("#6E6E76"); override val soft = Ui.c("#8C8C94")
    override val tint = Ui.c("#FFF7E0"); override val tint2 = Ui.c("#FFFBEF"); override val line = Ui.c("#EFE3BF"); override val line2 = Ui.c("#F2EDE0")
    override val green = Ui.c("#3DD62A"); override val pill = Ui.c("#111114")
    /** a page: black at the top (where the white name sits), white below */
    override val PAGE = intArrayOf(Ui.c("#0F0F12"), Ui.c("#0F0F12"), Ui.c("#1C1C21"), Ui.c("#F4F4F6"), Ui.c("#FFFFFF"))
    override val PAGE_AT = floatArrayOf(0f, .34f, .46f, .58f, .70f)
    override fun btn(radiusDp: Float) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#2A2A30"), Ui.c("#0F0F12"))).apply { cornerRadius = Ui.dp(radiusDp) }
    override fun sheet() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#FFF7E0"), Color.WHITE, Color.WHITE)).apply {
        cornerRadii = floatArrayOf(Ui.dp(28f), Ui.dp(28f), Ui.dp(28f), Ui.dp(28f), 0f, 0f, 0f, 0f)
    }
    override fun badge() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#2A2A30"), Ui.c("#0F0F12"))).apply { shape = GradientDrawable.OVAL }
}

/** the page background of those pages (black top, white below) */
class SdBackdrop(ctx: Context) : View(ctx) {
    private val p = Paint()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), KY.PAGE, KY.PAGE_AT, android.graphics.Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
}
