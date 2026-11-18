package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Mood Changer look (mcStyles.js): white + violet, Calibri, flat 2D. */
object MC {
    val bg = Color.WHITE
    val wash = G.c("#F8F4FF"); val line = G.c("#ECE6F6"); val ink = G.c("#1B1530"); val inkSoft = G.c("#6B6482")
    val accent = G.c("#7F00FF"); val accentDeep = G.c("#5B00B8"); val accentTint = G.c("#F1E6FF")
    val danger = G.c("#E8364A"); val ok = G.c("#128F5C"); val plum = G.c("#3B0B72"); val berry = G.c("#A3164F")
    val call1 = G.c("#2E0868"); val call2 = G.c("#5A12CC"); val call3 = G.c("#7A43E4")
    const val XS = 11f; const val SM = 12.5f; const val MD = 14f; const val LG = 16f; const val XL = 19f; const val XXL = 23f
    /** CSS "ease" */
    val EASE = android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    /** the web's narrow-phone rule (@media (max-width: 380px)) */
    fun narrow(ctx: Context) = ctx.resources.displayMetrics.let { it.widthPixels / it.density <= 380f }
    fun sidePad(ctx: Context) = if (narrow(ctx)) 16f else 20f
    /** a gap of [dp] inside a text (CSS flex gap between two words) */
    fun gapSpan(sb: android.text.SpannableStringBuilder, dp: Float) {
        val a = sb.length; sb.append(" ")
        sb.setSpan(object : android.text.style.ReplacementSpan() {
            override fun getSize(p: Paint, t: CharSequence?, s: Int, e: Int, fm: Paint.FontMetricsInt?) = G.dpi(dp)
            override fun draw(c: Canvas, t: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {}
        }, a, sb.length, 0)
    }

    fun t(ctx: Context, s: CharSequence, size: Float = MD, color: Int = ink, bold: Boolean = false): TextView =
        G.text(ctx, s, size, color, bold).lineH(1.5f)

    /** .btn (primary / secondary / ghost / danger / white), 50 high (38 small) */
    @SuppressLint("ViewConstructor")
    class Btn(ctx: Context, private val variant: String, text: String, icon: String? = null, iconRight: String? = null, private val small: Boolean = false, f: () -> Unit) : LinearLayout(ctx) {
        private val tv: TextView
        private val icL: ImageView?
        private val icR: ImageView?
        var label: String
            get() = tv.text.toString()
            set(v) { if (tv.text.toString() != v) tv.text = v }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            G.gap(this, 8f)
            pad(if (small) 14f else 20f, 0f, if (small) 14f else 20f, 0f)
            val isz = if (small) 16f else 18f
            icL = if (icon != null) G.icon(ctx, icon, fg(), isz, 2.2f).also { addView(it) } else null
            tv = G.text(ctx, text, if (small) SM else 15f, fg(), true)
            addView(tv)
            icR = if (iconRight != null) G.icon(ctx, iconRight, fg(), isz, 2.2f).also { addView(it) } else null
            paint()
            G.click(this, 0.985f) { f() }
        }
        private fun fg(): Int = when (variant) { "primary", "danger" -> Color.WHITE; else -> accentDeep }
        private fun paint() {
            val r = if (small) 12f else 14f
            background = when (variant) {
                "primary" -> G.round(if (isEnabled) accent else G.c("#DCCBF6"), r)
                "secondary" -> G.round(Color.WHITE, r, G.c("#E0D3F5"), 1f)
                "danger" -> G.round(danger, r)
                "white" -> G.round(Color.WHITE, r)
                else -> G.round(Color.TRANSPARENT, r)
            }
            if (variant == "white") G.shadow(this, 8f, G.c("#14003c"), r)
        }
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            if (variant == "primary") paint() // web: only .btn-primary:disabled changes colour
        }
        fun setIcons(left: String?) {
            icL?.visibility = if (left == null) View.GONE else View.VISIBLE
            icR?.visibility = View.VISIBLE
        }
        fun hideIconRight(hide: Boolean) { icR?.visibility = if (hide) View.GONE else View.VISIBLE }
        fun hideIconLeft(hide: Boolean) { icL?.visibility = if (hide) View.GONE else View.VISIBLE }
    }

    /** A screen: top bar, scrolling middle, fixed foot (web .screen / .topbar / .scroll / .foot). */
    class Screen(val ctx: Context) {
        val root = G.vbox(ctx)
        val top = FrameLayout(ctx)
        val scroll = scrollPage(ctx)
        val body = G.vbox(ctx)
        val foot = G.vbox(ctx, 8f)
        init {
            root.setBackgroundColor(bg)
            top.pad(14f, 18f, 14f, 8f)
            root.addView(top, G.lp(G.MATCH, G.WRAP))
            val side = sidePad(ctx) // @media (max-width: 380px): 16 instead of 20
            body.pad(side, 4f, side, 22f)
            scroll.addView(body, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
            root.addView(scroll, G.lp(G.MATCH, 0).apply { weight = 1f })
            foot.pad(side, 12f, side, 16f)
            foot.background = topLine(Color.WHITE)
            foot.visibility = View.GONE
            root.addView(foot, G.lp(G.MATCH, G.WRAP))
            root.alpha = 0.35f; root.translationY = G.dp(6f)
            root.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(EASE).start() // mcEnter .26s ease
        }
        /** The same screen drawn again (a tap changed it): no enter animation, same scroll place. */
        fun same(scrollY: Int) {
            root.animate().cancel(); root.alpha = 1f; root.translationY = 0f
            if (scrollY <= 0) return
            body.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    scroll.scrollTo(0, scrollY)
                }
            })
        }
        /** back button (left), True Find wordmark (middle), [right] (right) */
        fun bar(onBack: (() -> Unit)?, right: View? = null, light: Boolean = false) {
            top.removeAllViews()
            if (onBack != null) {
                val b = FrameLayout(ctx).apply {
                    background = G.round(Color.WHITE, 12f, line, 1f); contentDescription = "Back"
                    addView(G.icon(ctx, "ChevronLeft", ink, 20f, 2.3f), FrameLayout.LayoutParams(G.dpi(20f), G.dpi(20f), Gravity.CENTER))
                }
                G.click(b) { onBack() }
                top.addView(b, FrameLayout.LayoutParams(G.dpi(38f), G.dpi(38f), Gravity.START or Gravity.CENTER_VERTICAL))
            }
            top.addView(wordmark(ctx, light, 15f), FrameLayout.LayoutParams(G.WRAP, G.WRAP, Gravity.CENTER))
            if (right != null) top.addView(right, FrameLayout.LayoutParams(G.WRAP, G.WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
            top.minimumHeight = G.dpi(38f)
        }
        fun addFoot(v: View, h: Int = G.WRAP) { foot.visibility = View.VISIBLE; foot.addView(v, G.lp(G.MATCH, h)) }
    }

    fun topLine(fill: Int): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val p = Paint()
        override fun draw(c: Canvas) {
            val b = bounds
            p.color = fill; c.drawRect(b, p)
            p.color = line; c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + G.dp(1f), p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /** True Find wordmark (heart with a T) */
    fun wordmark(ctx: Context, light: Boolean, size: Float): View {
        val row = G.hbox(ctx, 0f)
        val c = if (light) "#ffffff" else "#7F00FF"
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M12 20.3s-7.6-4.6-7.6-10.1A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.6 2.6c0 5.5-7.6 10.1-7.6 10.1z" fill="none" stroke="$c" stroke-width="2.1" stroke-linejoin="round"/><path d="M9 11.4h6M12 11.4v5" stroke="$c" stroke-width="2" stroke-linecap="round"/></svg>"""
        row.addView(ImageView(ctx).apply { setImageDrawable(SvgDrawable(svg, size + 3f)) }, LinearLayout.LayoutParams(G.dpi(size + 3f), G.dpi(size + 3f)).m(r = 5f))
        val txt = android.text.SpannableStringBuilder("True Find")
        txt.setSpan(android.text.style.ForegroundColorSpan(if (light) Color.WHITE else plum), 0, 4, 0)
        txt.setSpan(android.text.style.ForegroundColorSpan(if (light) Color.WHITE else berry), 5, 9, 0)
        row.addView(G.text(ctx, txt, size, Color.WHITE).apply { letterSpacing = -0.01f })
        row.contentDescription = "True Find"
        return row
    }

    fun face(ctx: Context, mood: String?, sizeDp: Float): ImageView = ImageView(ctx).apply {
        setImageDrawable(SvgDrawable(McData.face(mood), sizeDp))
        layoutParams = LinearLayout.LayoutParams(G.dpi(sizeDp), G.dpi(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun h1(ctx: Context, s: String, center: Boolean = false): TextView =
        G.text(ctx, s, XXL, ink, true).lineH(1.22f).apply { letterSpacing = -0.015f; if (center) gravity = Gravity.CENTER }
    fun lede(ctx: Context, s: CharSequence, center: Boolean = false): TextView =
        t(ctx, s, MD, inkSoft).apply { if (center) gravity = Gravity.CENTER }
    fun label(ctx: Context, s: String, hint: String? = null, hintAccent: Boolean = false): TextView {
        val sb = android.text.SpannableStringBuilder(s)
        if (hint != null) {
            gapSpan(sb, 8f)
            val a = sb.length; sb.append(hint)
            sb.setSpan(android.text.style.ForegroundColorSpan(if (hintAccent) accentDeep else inkSoft), a, sb.length, 0)
            if (!hintAccent) sb.setSpan(FontSpan(app.truefind.android.Ui.regular), a, sb.length, 0) // .opt-hint: weight 500 (.meter-now stays 600)
        }
        return G.text(ctx, sb, SM, ink, true)
    }
    fun small(ctx: Context, s: CharSequence, center: Boolean = false): TextView = t(ctx, s, SM, inkSoft).apply { if (center) gravity = Gravity.CENTER }

    /** StepTag "n/of" */
    fun stepTag(ctx: Context, n: Int, of: Int): TextView {
        val sb = android.text.SpannableStringBuilder("$n")
        val a = sb.length; sb.append("/$of")
        sb.setSpan(android.text.style.ForegroundColorSpan(inkSoft), a, sb.length, 0)
        return G.text(ctx, sb, SM, accentDeep, true)
    }

    fun iconBtn(ctx: Context, icon: String, label: String, sizeIcon: Float = 20f, f: () -> Unit): View {
        val b = FrameLayout(ctx).apply {
            background = G.round(Color.WHITE, 12f, line, 1f); contentDescription = label
            addView(G.icon(ctx, icon, ink, sizeIcon, 2.1f), FrameLayout.LayoutParams(G.dpi(sizeIcon), G.dpi(sizeIcon), Gravity.CENTER))
        }
        G.click(b) { f() }
        b.layoutParams = FrameLayout.LayoutParams(G.dpi(38f), G.dpi(38f))
        return b
    }

    /** numbered "how it works" rows */
    fun how(ctx: Context, items: List<Pair<String, String>>): View {
        val l = G.vbox(ctx, 14f)
        items.forEachIndexed { i, (b, s) ->
            val row = G.hbox(ctx, 12f, Gravity.TOP)
            row.addView(G.text(ctx, "${i + 1}", SM, accentDeep, true).apply { gravity = Gravity.CENTER; background = G.round(accentTint, 10f) }, LinearLayout.LayoutParams(G.dpi(30f), G.dpi(30f)))
            val c = G.vbox(ctx)
            c.addView(t(ctx, b, MD, ink, true))
            c.addView(t(ctx, s, SM, inkSoft))
            row.addView(c, G.lpw(1f))
            l.addView(row, G.lp(G.MATCH, G.WRAP))
        }
        return l
    }

    fun rulesList(ctx: Context): View {
        val l = G.vbox(ctx)
        McData.RULES.forEachIndexed { i, (ic, title, text) ->
            val row = G.hbox(ctx, 12f, Gravity.TOP)
            row.pad(0f, 12f, 0f, 12f)
            row.addView(FrameLayout(ctx).apply {
                background = G.round(wash, 12f)
                addView(G.icon(ctx, ic, accent, 18f), FrameLayout.LayoutParams(G.dpi(18f), G.dpi(18f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(G.dpi(36f), G.dpi(36f)))
            val c = G.vbox(ctx)
            c.addView(t(ctx, title, MD, ink, true))
            c.addView(t(ctx, text, SM, inkSoft))
            row.addView(c, G.lpw(1f))
            if (i < McData.RULES.size - 1) row.background = bottomLine()
            l.addView(row, G.lp(G.MATCH, G.WRAP))
        }
        return l
    }

    fun bottomLine(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val p = Paint().apply { color = line }
        override fun draw(c: Canvas) { val b = bounds; c.drawRect(b.left.toFloat(), b.bottom - G.dp(1f), b.right.toFloat(), b.bottom.toFloat(), p) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Strength bars (5 small bars) */
    fun strength(ctx: Context, n: Int): View {
        val row = G.hbox(ctx, 3f)
        for (i in 1..5) row.addView(View(ctx).apply { background = G.round(if (i <= n) accent else G.c("#E8E0F5"), 2f) }, LinearLayout.LayoutParams(G.dpi(10f), G.dpi(4f)))
        return row
    }

    /** a round info / tip box (.private / .note) */
    fun noteBox(ctx: Context, icon: String, text: CharSequence, kind: String = "private"): LinearLayout {
        val box = G.hbox(ctx, if (kind == "private") 12f else 10f, Gravity.TOP)
        box.pad(14f, if (kind == "private") 14f else 12f, 14f, if (kind == "private") 14f else 12f)
        box.background = when (kind) {
            "note" -> G.round(G.c("#FFF8EE"), 14f, G.c("#F5E2C6"), 1f)
            else -> G.round(wash, 16f)
        }
        box.addView(G.icon(ctx, icon, if (kind == "note") G.c("#B26A00") else accent, 17f), LinearLayout.LayoutParams(G.dpi(17f), G.dpi(17f)).m(t = 2f))
        box.addView(t(ctx, text, SM, if (kind == "note") ink else G.c("#463C61")), G.lpw(1f))
        return box
    }

    /** status rows (✓ done / spinner / empty) */
    class StatusList(val ctx: Context) {
        val root = G.vbox(ctx)
        init { root.background = G.round(Color.WHITE, 16f, line, 1f); root.pad(16f, 2f, 16f, 2f) }
        fun add(text: String, done: Boolean, active: Boolean, extra: String? = null) {
            val row = G.hbox(ctx, 12f)
            row.pad(0f, 11f, 0f, 11f)
            val st = FrameLayout(ctx).apply { background = G.oval(if (done) G.c("#E2F5EC") else G.c("#F3EEFA")) }
            if (done) st.addView(G.icon(ctx, "Check", ok, 13f, 3f), FrameLayout.LayoutParams(G.dpi(13f), G.dpi(13f), Gravity.CENTER))
            else if (active) st.addView(TfSpinner(ctx, 13f), FrameLayout.LayoutParams(G.dpi(13f), G.dpi(13f), Gravity.CENTER))
            row.addView(st, LinearLayout.LayoutParams(G.dpi(22f), G.dpi(22f)))
            row.addView(t(ctx, text, SM, if (done) ink else inkSoft), G.lpw(1f))
            if (extra != null) row.addView(t(ctx, extra, XS, inkSoft))
            if (root.childCount > 0) (root.getChildAt(root.childCount - 1)).background = bottomLine()
            root.addView(row, G.lp(G.MATCH, G.WRAP))
        }
    }

    /** Meter: 5 segments (strength) */
    class Meter(val ctx: Context, var value: Int, val onChange: (Int) -> Unit) {
        val root = G.hbox(ctx, 6f)
        private val segs = ArrayList<TextView>()
        init {
            for (i in 1..5) {
                val s = G.text(ctx, "$i", MD, inkSoft, true).apply { gravity = Gravity.CENTER; contentDescription = McData.INTENSITY[i - 1] }
                G.click(s, 0.97f) { set(i); onChange(i) }
                segs.add(s)
                root.addView(s, G.lpw(1f, G.dpi(42f)))
            }
            set(value)
        }
        fun set(v: Int) {
            value = v
            segs.forEachIndexed { i, s ->
                val n = i + 1
                when {
                    n == v -> { s.background = G.round(accent, 12f, accent, 1f); s.setTextColor(Color.WHITE) }
                    n < v -> { s.background = G.round(accentTint, 12f, G.c("#DCC7FA"), 1f); s.setTextColor(accentDeep) }
                    else -> { s.background = G.round(Color.WHITE, 12f, line, 1f); s.setTextColor(inkSoft) }
                }
            }
        }
    }

    fun meterEnds(ctx: Context): View {
        val r = G.hbox(ctx)
        r.addView(t(ctx, "A little", XS, inkSoft), G.lpw(1f))
        r.addView(t(ctx, "Very strong", XS, inkSoft))
        return r
    }

    /** chip (min 36 high, pill) */
    fun chip(ctx: Context, text: String, on: Boolean, icon: String? = null, tick: Boolean = true, minH: Float = 36f, f: () -> Unit): LinearLayout {
        val c = G.hbox(ctx, 6f, Gravity.CENTER)
        c.pad(14f, 6f, 14f, 6f)
        c.minimumHeight = G.dpi(minH)
        c.background = if (on) G.round(accentTint, 99f, G.c("#CDB2F7"), 1f) else G.round(Color.WHITE, 99f, line, 1f)
        if (on && tick) c.addView(G.icon(ctx, "Check", accentDeep, 14f, 2.6f))
        else if (icon != null) c.addView(G.icon(ctx, icon, ink, 14f))
        c.addView(t(ctx, text, SM, if (on) accentDeep else ink))
        G.click(c, 0.97f) { f() }
        return c
    }

    /** option row (.opt): icon box + text (+ small) + optional radio */
    fun opt(ctx: Context, text: String, icon: String? = null, small: String? = null, radio: Boolean? = null, danger: Boolean = false, hi: String? = null, f: () -> Unit): LinearLayout {
        val o = G.hbox(ctx, 12f)
        o.pad(14f, 12f, 14f, 12f)
        val on = radio == true
        o.background = if (on) G.round(G.c("#FBF8FF"), 14f, accent, 1f) else G.round(Color.WHITE, 14f, line, 1f)
        if (icon != null) o.addView(FrameLayout(ctx).apply {
            background = G.round(if (danger) G.c("#FFEDEF") else wash, 11f)
            addView(G.icon(ctx, icon, if (danger) MC.danger else accent, 17f), FrameLayout.LayoutParams(G.dpi(17f), G.dpi(17f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(G.dpi(34f), G.dpi(34f)))
        val col = G.vbox(ctx)
        col.addView(G.text(ctx, text, MD, if (danger) MC.danger else ink, false).lineH(1.3f))
        val sub = small ?: hi
        if (sub != null) col.addView(G.text(ctx, sub, XS, inkSoft, false).lineH(1.3f), G.lp(G.WRAP, G.WRAP).m(t = 2f))
        o.addView(col, G.lpw(1f))
        if (radio != null) {
            val r = FrameLayout(ctx).apply { background = G.oval(Color.WHITE, if (on) accent else G.c("#C6B5E4"), 1.5f) }
            if (on) r.addView(View(ctx).apply { background = G.oval(accent) }, FrameLayout.LayoutParams(G.dpi(10f), G.dpi(10f), Gravity.CENTER))
            o.addView(r, LinearLayout.LayoutParams(G.dpi(20f), G.dpi(20f)))
        }
        G.click(o, 0.985f) { f() }
        return o
    }

    /** white bottom sheet (grip, title, content) */
    fun sheet(host: FrameLayout, title: String?, onClosed: () -> Unit = {}): Sheet {
        val s = Sheet(host, CssBg(floatArrayOf(24f, 24f, 0f, 0f), emptyList(), fill = Color.WHITE), G.ca("#120828", 0.46f), 20f, 10f, 20f, 20f, 0f, onClosed = onClosed, maxHeightFrac = 0.88f)
        val ctx = host.context
        s.panel.addView(View(ctx).apply { background = G.round(G.c("#E2DAEE"), 2f) }, LinearLayout.LayoutParams(G.dpi(40f), G.dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = G.dpi(12f) })
        if (title != null) s.panel.addView(G.text(ctx, title, XL, ink, true).apply { letterSpacing = -0.01f }, G.lp(G.MATCH, G.WRAP).m(t = 4f, b = 6f))
        return s
    }
    fun sheetP(ctx: Context, s: String): TextView = t(ctx, s, SM, inkSoft)
}

/** A row of bars that dance while someone is talking (Waveform). */
class Waveform(ctx: Context, private val bars: Int, private val light: Boolean) : View(ctx) {
    var active = false
        set(v) { if (field != v) { field = v; if (v) anim.start() else { anim.cancel(); invalidate() } } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active) anim.start() }
    override fun onDraw(c: Canvas) {
        val gap = G.dp(3f)
        val bw = (width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val hPct = (30 + ((i * 37) % 60)) / 100f
            val delay = ((i * 97) % 900) / 1000f
            val k = if (active) {
                val ph = ((t - delay) % 1f + 1f) % 1f
                0.25f + 0.75f * ((1f - kotlin.math.cos(ph * 2 * Math.PI).toFloat()) / 2f)
            } else 0.14f
            val h = height * hPct * k
            val x = i * (bw + gap)
            rect.set(x, (height - h) / 2f, x + bw, (height + h) / 2f)
            p.color = if (light) Color.argb(230, 255, 255, 255) else MC.accent
            p.alpha = if (light) (if (active) 230 else 103) else if (active) 140 else 115
            c.drawRoundRect(rect, G.dp(2f), G.dp(2f), p)
        }
    }
}

/** Simple flow layout (CSS flex-wrap) with gaps. */
class Flow(ctx: Context, private val gapX: Float, private val gapY: Float, private val center: Boolean = false) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        val gx = G.dpi(gapX); val gy = G.dpi(gapY)
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == View.GONE) continue
            ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gy; rowH = 0 }
            x += ch.measuredWidth + gx
            rowH = max(rowH, ch.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val gx = G.dpi(gapX); val gy = G.dpi(gapY)
        // rows first (for centring)
        val rows = ArrayList<MutableList<View>>()
        var cur = ArrayList<View>(); var x = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == View.GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { rows.add(cur); cur = ArrayList(); x = 0 }
            cur.add(ch); x += ch.measuredWidth + gx
        }
        if (cur.isNotEmpty()) rows.add(cur)
        var y = paddingTop
        for (row in rows) {
            val rw = row.sumOf { it.measuredWidth } + gx * (row.size - 1)
            var cx = paddingLeft + if (center) (maxW - rw) / 2 else 0
            val rh = row.maxOf { it.measuredHeight }
            for (ch in row) {
                val cy = y + (rh - ch.measuredHeight) / 2
                ch.layout(cx, cy, cx + ch.measuredWidth, cy + ch.measuredHeight)
                cx += ch.measuredWidth + gx
            }
            y += rh + gy
        }
    }
}

/** Round photo with the web's double ring (white 3 px, then the mood colour 3 px), speaking ripple, muted badge. */
class McAvatar(ctx: Context, private val sizeDp: Float, ring: Int?) : FrameLayout(ctx) {
    private val photo = AvatarView(ctx, sizeDp, Color.TRANSPARENT, 0f, G.c("#EDE2FF"))
    private val ringView = View(ctx)
    private val ripple = View(ctx)
    private var rippleAnim: ValueAnimator? = null
    private val badge = FrameLayout(ctx)
    init {
        clipChildren = false
        val px = G.dpi(sizeDp)
        ripple.background = G.oval(Color.TRANSPARENT, G.ca("#ffffff", 0.75f), 2f)
        ripple.visibility = View.GONE
        setRing(ring)
        addView(ringView, LayoutParams(px + G.dpi(12f), px + G.dpi(12f), Gravity.CENTER))
        addView(photo, LayoutParams(px, px, Gravity.CENTER))
        addView(ripple, LayoutParams(px + G.dpi(18f), px + G.dpi(18f), Gravity.CENTER))
        badge.background = G.oval(MC.danger, Color.WHITE, 2f)
        badge.addView(G.icon(ctx, "MicOff", Color.WHITE, 12f, 2.4f), LayoutParams(G.dpi(12f), G.dpi(12f), Gravity.CENTER))
        badge.visibility = View.GONE
        addView(badge, LayoutParams(G.dpi(22f), G.dpi(22f), Gravity.BOTTOM or Gravity.END).apply { val m = G.dpi(3f); rightMargin = m; bottomMargin = m })
    }
    /** web: box-shadow 0 0 0 3px #fff, 0 0 0 6px var(--ring) (ring transparent → only the white one) */
    fun setRing(ring: Int?) {
        val rc: Int = ring ?: Color.TRANSPARENT
        ringView.background = object : android.graphics.drawable.Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(c: Canvas) {
                val b = bounds; val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = b.width() / 2f
                if (Color.alpha(rc) > 0) { p.color = rc; c.drawCircle(cx, cy, r, p) }
                p.color = Color.WHITE; c.drawCircle(cx, cy, r - G.dp(3f), p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }
    fun setPhoto(uri: String?) = photo.setPhoto(uri)
    fun setMuted(m: Boolean) { badge.visibility = if (m) View.VISIBLE else View.GONE }
    fun setSpeaking(s: Boolean) {
        if (s && rippleAnim == null) {
            ripple.visibility = View.VISIBLE
            rippleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1250; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.PathInterpolator(0f, 0f, 0.58f, 1f)
                addUpdateListener { val k = it.animatedValue as Float; val sc = 0.94f + 0.2f * k; ripple.scaleX = sc; ripple.scaleY = sc; ripple.alpha = 0.95f * (1f - k) }
                start()
            }
        } else if (!s && rippleAnim != null) { rippleAnim?.cancel(); rippleAnim = null; ripple.visibility = View.GONE }
    }
    override fun onDetachedFromWindow() { rippleAnim?.cancel(); rippleAnim = null; super.onDetachedFromWindow() }
    /** total box incl. rings */
    fun boxPx() = G.dpi(sizeDp) + G.dpi(18f)
}

/** Sets the True Find font face for a part of a text (bold <b> inside regular text, or the reverse). */
class FontSpan(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = tf }
}

/**
 * A row that is only as wide as its content (CSS inline items in a flex row with no flex:1):
 * the item at [shrink] gives way (wraps) when the row would be too wide, so the items after it stay
 * right behind it instead of being pushed out.
 */
fun packRow(ctx: Context, gapDp: Float, items: List<View>, shrink: Int, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout {
    val r = G.hbox(ctx, gapDp, gravity)
    items.forEachIndexed { i, v ->
        val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: v.layoutParams?.let { LinearLayout.LayoutParams(it.width, it.height) } ?: LinearLayout.LayoutParams(G.WRAP, G.WRAP)
        if (i == shrink) { lp.width = G.WRAP; lp.weight = 1f }
        r.addView(v, lp)
    }
    return r
}
