package app.truefind.android.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.truefind.android.Api
import app.truefind.android.Svg
import app.truefind.android.SvgDrawable
import app.truefind.android.TfLoader
import app.truefind.android.Ui

/** Pieces of the Welcome screens (Welcome.jsx: OnHead, Button, Ico, the sheets), drawn natively. */
internal object WK {
    // ── the web's own small icons (Ico of Welcome.jsx) ──
    private fun svg(vb: String, inner: String) = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$vb">$inner</svg>"""
    val PERSON = svg("0 0 24 26", """<circle cx="12" cy="7.6" r="5.4" fill="none" stroke="currentColor" stroke-width="2"/><path d="M2.5 23.6v-1.4c0-3.6 2.9-6.4 6.4-6.4h6.2c3.5 0 6.4 2.8 6.4 6.4v1.4z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>""")
    val CALENDAR = svg("0 0 24 26", """<rect x="2.2" y="4.4" width="19.6" height="19.4" rx="3.2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M7.2 1.8v4.8M16.8 1.8v4.8M2.4 10h19.2" stroke="currentColor" stroke-width="2" stroke-linecap="round" fill="none"/><circle cx="8" cy="16.8" r="1.25" fill="currentColor"/><circle cx="16" cy="16.8" r="1.25" fill="currentColor"/>""")
    val PIN = svg("0 0 22 28", """<path d="M11 26.2s-8.6-8.4-8.6-14.6a8.6 8.6 0 1 1 17.2 0c0 6.2-8.6 14.6-8.6 14.6z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><circle cx="11" cy="11.4" r="3.3" fill="none" stroke="currentColor" stroke-width="2"/>""")
    val CHEVRON = svg("0 0 16 9", """<path d="M1.4 1.2 8 7.6l6.6-6.4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>""")
    val CHECK = svg("0 0 24 24", """<path d="M6.2 12.6l3.9 3.8 7.9-8.4" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/>""")
    val INFO = svg("0 0 24 24", """<circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 11v6M12 7.4v.2" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" fill="none"/>""")
    val CLOCK = svg("0 0 24 24", """<circle cx="12" cy="12" r="8.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 7.5V12l3 2" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>""")
    val LOCK = svg("0 0 24 24", """<rect x="4.5" y="10.5" width="15" height="10.5" rx="2.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" fill="none" stroke="currentColor" stroke-width="2"/>""")
    val SEARCH = svg("0 0 24 24", """<circle cx="11" cy="11" r="6.8" fill="none" stroke="currentColor" stroke-width="2"/><path d="M16.2 16.2 21 21" stroke="currentColor" stroke-width="2" stroke-linecap="round" fill="none"/>""")
    val CLOSE = svg("0 0 24 24", """<path d="M6 6l12 12M18 6 6 18" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" fill="none"/>""")
    val ARROW = svg("0 0 24 24", """<path d="M5 12h14M13 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>""")
    val BACK = svg("0 0 24 24", """<path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>""")

    // the pictures in the purple tops (104 × 104) and the flag: drawn by AndroidSVG (clip paths)
    val ART_LOGIN = """<svg xmlns="http://www.w3.org/2000/svg" width="104" height="104" viewBox="0 0 104 104"><circle cx="52" cy="52" r="50" fill="#FFFFFF" fill-opacity="0.07"/><rect x="30" y="14" width="44" height="78" rx="10" fill="#1C1430"/><rect x="34" y="18" width="36" height="70" rx="7" fill="#2E1F5E"/><rect x="45" y="22" width="14" height="3" rx="1.5" fill="#1C1430"/><rect x="16" y="40" width="72" height="26" rx="13" fill="#FFFFFF"/>""" +
        (0..5).joinToString("") { """<circle cx="${30 + it * 8.8}" cy="53" r="2.6" fill="#6C2BD9"/>""" } +
        """<circle cx="78" cy="74" r="11" fill="#22C55E" stroke="#4A1D96" stroke-width="3"/><path d="M73 74.5l3.5 3.5 6.5-7" fill="none" stroke="#FFFFFF" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>"""
    val ART_VERIFY = """<svg xmlns="http://www.w3.org/2000/svg" width="104" height="104" viewBox="0 0 104 104"><circle cx="52" cy="52" r="50" fill="#FFFFFF" fill-opacity="0.07"/><path d="M52 12 L84 24 V52 C84 72 71 86 52 94 C33 86 20 72 20 52 V24 Z" fill="#C4B5FD"/><path d="M52 20 L77 29.5 V52 C77 68 67 79 52 86 C37 79 27 68 27 52 V29.5 Z" fill="#7C3AED"/><path d="M52 20 L27 29.5 V52 C27 68 37 79 52 86 Z" fill="#8B5CF6"/><path d="M40 52.5l8.5 8.5L65 43" fill="none" stroke="#FFFFFF" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/></svg>"""
    val ART_GENDER = """<svg xmlns="http://www.w3.org/2000/svg" width="104" height="104" viewBox="0 0 104 104"><defs><clipPath id="g1"><circle cx="38" cy="52" r="26"/></clipPath><clipPath id="g2"><circle cx="68" cy="52" r="26"/></clipPath></defs><circle cx="52" cy="52" r="50" fill="#FFFFFF" fill-opacity="0.07"/><circle cx="38" cy="52" r="26" fill="#DBEAFE"/><g clip-path="url(#g1)"><circle cx="38" cy="46" r="9" fill="#2563EB"/><ellipse cx="38" cy="76" rx="18" ry="13" fill="#2563EB"/></g><circle cx="68" cy="52" r="28.5" fill="#4A1D96"/><circle cx="68" cy="52" r="26" fill="#FCE7F3"/><g clip-path="url(#g2)"><circle cx="68" cy="46" r="9" fill="#DB2777"/><ellipse cx="68" cy="76" rx="18" ry="13" fill="#DB2777"/></g></svg>"""
    val FLAG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 30"><defs><clipPath id="c"><circle cx="15" cy="15" r="14"/></clipPath></defs><g clip-path="url(#c)"><rect width="30" height="10.3" fill="#F7931E"/><rect y="10.3" width="30" height="9.4" fill="#FFFFFF"/><rect y="19.7" width="30" height="10.3" fill="#139A0E"/><circle cx="15" cy="15" r="3.6" fill="none" stroke="#1E2A8A" stroke-width="1.1"/><circle cx="15" cy="15" r="0.9" fill="#1E2A8A"/></g><circle cx="15" cy="15" r="14" fill="none" stroke="#000000" stroke-opacity="0.08"/></svg>"""

    fun ico(markup: String, color: Int, wDp: Float, hDp: Float = wDp): Drawable = SvgDrawable(Svg.of(markup), wDp, hDp, color)
    fun icoView(ctx: Context, markup: String, color: Int, wDp: Float, hDp: Float = wDp): ImageView = ImageView(ctx).apply {
        setImageDrawable(ico(markup, color, wDp, hDp)); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(wDp), Ui.dpi(hDp))
    }
    fun artView(ctx: Context, markup: String, wDp: Float, hDp: Float = wDp): View =
        app.truefind.android.games.SvgView(ctx, markup, wDp, hDp).apply { layoutParams = LinearLayout.LayoutParams(Ui.dpi(wDp), Ui.dpi(hDp)) }

    // ── the light doodle wallpaper (white lines; WebP 864 × 1512), read once, after the first draw ──
    private val doodles = HashMap<String, android.graphics.Bitmap>()
    fun doodle(ctx: Context, name: String, done: (android.graphics.Bitmap?) -> Unit) {
        doodles[name]?.let { done(it); return }
        Api.async({ try { ctx.assets.open("login/$name.webp").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null } }) { b ->
            if (b != null) doodles[name] = b
            done(b)
        }
    }
    /** the wallpaper as a picture at the top of a box, full width, height by its shape (fades in once read) */
    fun doodleView(ctx: Context, name: String): ImageView = object : ImageView(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, (width * 1512f / 864f).toInt())
        }
    }.apply {
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        doodle(ctx, name) { b -> if (b != null) { setImageBitmap(b); animate().alpha(1f).setDuration(250).start() } }
    }

    // ── text pieces ──
    fun label(ctx: Context, s: String) = LK.t(ctx, s, 12f, LK.sub, true)
    fun err(ctx: Context) = LK.t(ctx, "", 12.5f, LK.err, false, 1.35f).apply { visibility = View.GONE }
    fun errLp() = LK.lp(-1, -2, t = 7f, l = 2f, r = 2f)
    /** an icon + text line (.tfo-help / .tfo-foot) */
    fun iconLine(ctx: Context, icon: String, text: CharSequence, size: Float, center: Boolean): LinearLayout = LK.row(ctx, if (center) Gravity.CENTER else Gravity.CENTER_VERTICAL).apply {
        addView(icoView(ctx, icon, LK.sub, 13f), LK.lp(Ui.dpi(13f), Ui.dpi(13f), r = 6f))
        addView(LK.t(ctx, text, size, LK.sub))
    }
    /** .tfo-link: bold purple, 4 px above and below */
    fun link(ctx: Context, s: String, size: Float = 13.5f, onTap: () -> Unit): TextView = LK.t(ctx, s, size, LK.link, true).apply {
        setPadding(0, Ui.dpi(4f), 0, Ui.dpi(4f))
        isClickable = true
        setOnClickListener { onTap() }
    }

    /** text with bold purple links (the legal lines): parts = text, or "[label]" → link with that label */
    fun linked(ctx: Context, size: Float, color: Int, linkColor: Int, lh: Float, vararg parts: Pair<String, (() -> Unit)?>): LText = LK.t(ctx, "", size, color, false, lh).apply {
        val sb = SpannableStringBuilder()
        for ((txt, tap) in parts) {
            val start = sb.length
            sb.append(txt)
            if (tap != null) sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { tap() }
                override fun updateDrawState(ds: TextPaint) { ds.color = linkColor; ds.isUnderlineText = false; ds.typeface = Ui.bold }
            }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = sb
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
    }

    // ── the purple top of Login, Verify OTP, Gender and Profile (OnHead) ──
    fun head(ctx: Context, step: Int, title: String, sub: String, art: String?, short: Boolean, onBack: () -> Unit): FrameLayout {
        val box = object : FrameLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, h)
                val min = Ui.dpi(if (short) 250f else 300f)
                if (measuredHeight < min) setMeasuredDimension(measuredWidth, min)
            }
        }
        box.setBackgroundColor(LK.head)
        // the wallpaper sits in its own clipped layer behind everything
        val bg = FrameLayout(ctx)
        bg.addView(doodleView(ctx, "calls"), FrameLayout.LayoutParams(-1, -2))
        box.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val narrow = ctx.resources.displayMetrics.widthPixels / Ui.density < 360f
        // the column (step, bars, title, line); its width leaves room for the picture
        val colW = object : LinearLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                val room = MeasureSpec.getSize(w)
                val want = if (narrow) room else minOf(room - Ui.dpi(if (short) 104f else 118f), Ui.dpi(250f))
                super.onMeasure(MeasureSpec.makeMeasureSpec(want.coerceAtLeast(0), MeasureSpec.EXACTLY), h)
            }
        }.apply { orientation = LinearLayout.VERTICAL }
        colW.addView(LK.t(ctx, "STEP $step OF 4", 11f, LK.a(Color.WHITE, 0.8f), true, ls = 1.4f))
        val segs = LK.row(ctx)
        for (i in 1..4) segs.addView(View(ctx).apply { background = CssBox(if (i <= step) Color.WHITE else LK.a(Color.WHITE, 0.3f), 2f) }, LK.lp(Ui.dpi(26f), Ui.dpi(4f), r = if (i < 4) 4f else 0f))
        colW.addView(segs, LK.lp(t = 8f))
        colW.addView(LK.t(ctx, title, 26f, Color.WHITE, true, 1.12f, -0.3f), LK.lp(-1, -2, t = 18f))
        colW.addView(LK.t(ctx, sub, 14.5f, LK.a(Color.WHITE, 0.82f), false, 1.4f), LK.lp(-1, -2, t = 8f))
        val inner = FrameLayout(ctx).apply { setPadding(Ui.dpi(24f), Ui.dpi(118f), Ui.dpi(24f), Ui.dpi(34f)) }
        inner.addView(colW, FrameLayout.LayoutParams(-1, -2))
        box.addView(inner, FrameLayout.LayoutParams(-1, -2))
        if (art != null && !narrow) box.addView(artView(ctx, art, 104f), LK.flp(Ui.dpi(104f), Ui.dpi(104f), Gravity.TOP or Gravity.END, t = 132f, r = 22f))
        // back button and the name
        val back = FrameLayout(ctx).apply {
            background = CssBox(LK.a(Color.WHITE, 0.12f), 22f, 1f, LK.a(Color.WHITE, 0.28f))
            addView(ImageView(ctx).apply { setImageDrawable(ico(BACK, Color.WHITE, 20f)) }, FrameLayout.LayoutParams(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER))
            contentDescription = "Go back"
        }
        LK.click(back, 0.94f) { onBack() }
        box.addView(back, LK.flp(Ui.dpi(44f), Ui.dpi(44f), Gravity.TOP or Gravity.START, l = 20f, t = 48f))
        box.addView(LK.t(ctx, "TrueFind", 20f, Color.WHITE, true, 1f, -0.2f), LK.flp(-2, -2, Gravity.TOP or Gravity.END, t = 59f, r = 24f))
        return box
    }

    /** the white sheet under the purple top (.tfo-sheet): round 28 on top, its shadow rising over the purple */
    fun sheet(ctx: Context, padTop: Float = 26f): LinearLayout = LK.col(ctx).apply {
        background = CssBox(Color.WHITE, corners = floatArrayOf(28f, 28f, 0f, 0f), shadows = listOf(Sh(0f, -10f, 30f, 0f, Color.argb(41, 0, 0, 0))))
        setPadding(Ui.dpi(24f), Ui.dpi(padTop), Ui.dpi(24f), Ui.dpi(20f))
    }

    /** a Welcome screen: purple top + white sheet filling the rest, scrolling as one */
    fun page(ctx: Context, head: View, sheet: View): ScrollView {
        // .tfo: purple behind everything (it shows at the sheet's round top corners)
        val col = LK.col(ctx).apply { setBackgroundColor(LK.head) }
        col.addView(head, LK.lp(-1, -2))
        col.addView(sheet, LK.lp(-1, 0, weight = 1f))
        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true
            clipChildren = false
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
    }
}

/** The big purple button (.tfo-cta): 52 high, round 26, white bold 16 with the arrow; busy → white spinner. */
@SuppressLint("ViewConstructor")
internal class Cta(ctx: Context, label: String, arrow: Boolean = true, private val heightDp: Float = 52f) : FrameLayout(ctx) {
    private val content = LK.row(ctx, Gravity.CENTER)
    private val text = LK.t(ctx, label, 16f, Color.WHITE, true)
    private val loader = TfLoader(ctx, 20f, Color.WHITE).apply { visibility = View.GONE }
    var busy = false
        set(v) { field = v; content.visibility = if (v) View.INVISIBLE else View.VISIBLE; loader.visibility = if (v) View.VISIBLE else View.GONE; look() }
    var off = false
        set(v) { field = v; look() }
    private val on = CssBox(LK.purple, 26f, shadows = listOf(Sh(0f, 8f, 20f, 0f, LK.ca("#6C2BD9", 0.30f))))
    private val dim = CssBox(LK.purple, 26f)
    init {
        clipChildren = false
        content.addView(text)
        if (arrow) content.addView(WK.icoView(ctx, WK.ARROW, Color.WHITE, 16f), LK.lp(Ui.dpi(16f), Ui.dpi(16f), l = 8f))
        addView(content, LayoutParams(-2, -2, Gravity.CENTER))
        addView(loader, LayoutParams(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER))
        LK.press(this, 0.985f)
        look()
    }
    private fun look() {
        val disabled = off || busy
        isEnabled = !disabled
        // :disabled → .45 and no shadow (busy buttons are disabled too)
        background = if (disabled) dim else on
        alpha = if (disabled) 0.45f else 1f
    }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(heightDp), MeasureSpec.EXACTLY))
    fun setLabel(s: String) { text.text = s }
}

/**
 * A bottom sheet panel: grip, title + small line, close; then [header] views and a list that scrolls in the
 * rest of the room (.tfw-sheet: white, round 20 on top, max 80 % of the screen).
 */
@SuppressLint("ViewConstructor")
internal class SheetPanel(ctx: Context, title: String, sub: String, onClose: () -> Unit) : ViewGroup(ctx) {
    val top = LK.col(ctx)
    val list = LK.col(ctx)
    val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list, FrameLayout.LayoutParams(-1, -2)) }
    init {
        background = CssBox(Color.WHITE, corners = floatArrayOf(20f, 20f, 0f, 0f), shadows = listOf(Sh(0f, -20f, 40f, -20f, LK.ca("#3C0A46", 0.45f))))
        clipChildren = false
        setPadding(Ui.dpi(18f), Ui.dpi(10f), Ui.dpi(18f), Ui.dpi(10f))
        top.addView(View(ctx).apply { background = CssBox(LK.c("#E3DCEC"), 9f) }, LK.lp(Ui.dpi(38f), Ui.dpi(4f), b = 12f, g = Gravity.CENTER_HORIZONTAL))
        val head = LK.row(ctx)
        val t = LK.col(ctx)
        t.addView(LK.t(ctx, title, 17f, LK.sheetTitle, true))
        t.addView(LK.t(ctx, sub, 11f, LK.sheetSub, false), LK.lp(t = 2f))
        head.addView(t, LK.lp(0, -2, weight = 1f))
        val x = FrameLayout(ctx).apply {
            background = CssBox(LK.c("#F5F0FA"), 17f)
            addView(ImageView(ctx).apply { setImageDrawable(WK.ico(WK.CLOSE, LK.sheetTitle, 17f)) }, FrameLayout.LayoutParams(Ui.dpi(17f), Ui.dpi(17f), Gravity.CENTER))
            contentDescription = "Close"
        }
        LK.click(x, 0.94f) { onClose() }
        head.addView(x, LK.lp(Ui.dpi(34f), Ui.dpi(34f), l = 12f))
        top.addView(head, LK.lp(-1, -2))
        addView(top)
        addView(scroll)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = w - paddingLeft - paddingRight
        val maxH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2 else MeasureSpec.getSize(heightMeasureSpec)
        top.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val room = (maxH - paddingTop - paddingBottom - top.measuredHeight).coerceAtLeast(0)
        scroll.measure(MeasureSpec.makeMeasureSpec(inner + Ui.dpi(16f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST))
        setMeasuredDimension(w, paddingTop + top.measuredHeight + scroll.measuredHeight + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        top.layout(paddingLeft, paddingTop, paddingLeft + top.measuredWidth, paddingTop + top.measuredHeight)
        // the list reaches 8 px past the sides (.tfw-list margin 0 -8px; padding 0 8px)
        val y = paddingTop + top.measuredHeight
        scroll.layout(paddingLeft - Ui.dpi(8f), y, paddingLeft - Ui.dpi(8f) + scroll.measuredWidth, y + scroll.measuredHeight)
    }
}
