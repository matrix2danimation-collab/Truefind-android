package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/*
 * Wallet building blocks: text and boxes, the bottom sheet (SheetShell),
 * the "Processing / Payment successful / Payment failed" screens and the
 * small pieces of the payment flow — all as in src/pages/Wallet.jsx.
 */

object WV {
    val EASE_OUT = PathInterpolator(0.22f, 0.61f, 0.36f, 1f) // cubic-bezier(.22,.61,.36,1)

    fun text(ctx: Context, s: CharSequence, sizeDp: Float, color: Int, bold: Boolean = false, lineHeightDp: Float = 0f): TextView =
        Ui.text(ctx, s, sizeDp, color, bold).also { if (lineHeightDp > 0f) lineHeight(it, lineHeightDp) }

    /** CSS line-height for text that can wrap. */
    fun lineHeight(tv: TextView, dp: Float) {
        try { TextViewCompat.setLineHeight(tv, Ui.dpi(dp)) } catch (e: Exception) { }
    }

    fun box(ctx: Context, vertical: Boolean, gravity: Int = Gravity.NO_GRAVITY): LinearLayout = LinearLayout(ctx).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (gravity != Gravity.NO_GRAVITY) this.gravity = gravity
        clipChildren = false; clipToPadding = false
    }

    fun lp(w: Int, h: Int, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)) }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun round(color: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = Ui.dp(radiusDp)
            if (stroke != null) setStroke(Math.max(1, Math.round(strokeDp * Ui.density)), stroke)
        }

    fun topRound(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val r = Ui.dp(radiusDp)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun oval(color: Int, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        if (stroke != null) setStroke(Math.max(1, Math.round(strokeDp * Ui.density)), stroke)
    }

    /** .tfw-press: shrinks a little while pressed. */
    fun press(v: View, scale: Float = 0.97f) = Ui.pressable(v, scale)

    /** The white card: border 1px #E7E0F2, radius 20. */
    fun card(ctx: Context, padL: Float, padT: Float, padR: Float = padL, padB: Float = padT): LinearLayout = box(ctx, true).apply {
        background = round(Color.WHITE, 20f, WC.line)
        setPadding(Ui.dpi(padL), Ui.dpi(padT), Ui.dpi(padR), Ui.dpi(padB))
    }

    /** A full-width button with an optional icon/spinner before the label (primaryBtn / cta). */
    fun button(
        ctx: Context, label: CharSequence, bg: Int, fg: Int, heightDp: Float, radiusDp: Float, sizeDp: Float,
        lead: View? = null, gap: Float = 8f,
    ): LinearLayout = box(ctx, false, Gravity.CENTER).apply {
        background = round(bg, radiusDp)
        minimumHeight = Ui.dpi(heightDp)
        isClickable = true; isFocusable = true
        if (lead != null) addView(lead, (lead.layoutParams as? LinearLayout.LayoutParams ?: lp(WRAP, WRAP)).apply { rightMargin = Ui.dpi(gap) })
        addView(text(ctx, label, sizeDp, fg, true).apply { gravity = Gravity.CENTER })
        layoutParams = lp(MATCH, Ui.dpi(heightDp))
        press(this)
    }

    /** primaryBtn(): height 50, radius 16, purple, white 15.5 bold. */
    fun primary(ctx: Context, label: CharSequence, lead: View? = null, bg: Int = WC.head, fg: Int = Color.WHITE): LinearLayout =
        button(ctx, label, bg, fg, 50f, 16f, 15.5f, lead)

    /** h2: 18px bold, line height 24. */
    fun h2(ctx: Context, s: String, color: Int): TextView = text(ctx, s, 18f, color, true, 24f)

    /** <Row label value strong /> with its bottom line (none on the last row). */
    fun row(ctx: Context, label: String, value: String, strong: Boolean = false, last: Boolean = false, valueColor: Int = WC.ink): View {
        val r = box(ctx, false, Gravity.CENTER_VERTICAL)
        r.setPadding(0, Ui.dpi(11f), 0, Ui.dpi(11f))
        r.addView(text(ctx, label, 14f, WC.sub), lp(0, WRAP, weight = 1f))
        r.addView(text(ctx, value, 14f, valueColor, true).apply {
            gravity = Gravity.END
            if (strong) typeface = Ui.bold
        }, lp(WRAP, WRAP, l = 12f))
        if (!last) return withBottomLine(ctx, r, WC.line)
        return r
    }

    /** A view with a 1px line under it (borderBottom). */
    fun withBottomLine(ctx: Context, v: View, color: Int): View {
        val wrap = box(ctx, true)
        wrap.addView(v, lp(MATCH, WRAP))
        wrap.addView(View(ctx).apply { setBackgroundColor(color) }, lp(MATCH, Math.max(1, Ui.dpi(1f))))
        return wrap
    }

    /** "Select payment method" logos (PAY_APPS). */
    fun payLogo(ctx: Context, key: String): View {
        val box = FrameLayout(ctx).apply { background = round(Color.WHITE, 12f, WC.line) }
        val inner: View = when (key) {
            "gpay" -> text(ctx, "G", 18f, Ui.c("#4285F4"), true)
            "phonepe" -> text(ctx, "पे", 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = oval(Ui.c("#5F259F"))
                layoutParams = FrameLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER)
            }
            "paytm" -> text(ctx, SpannableString("paytm").apply {
                setSpan(ForegroundColorSpan(Ui.c("#00BAF2")), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Ui.c("#20336B")), 3, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 12.5f, Ui.c("#20336B"), true)
            else -> text(ctx, SpannableString("UPI▸").apply {
                setSpan(ForegroundColorSpan(Ui.c("#F37021")), 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 13f, Ui.c("#5B5B5B"), true).apply {
                typeface = Typeface.create(Ui.bold, Typeface.BOLD_ITALIC)
                letterSpacing = -0.03f
            }
        }
        box.addView(inner, (inner.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return box
    }

    /** TxIcon: 42 × 42 rounded box, green for coins in, purple for coins out. */
    fun txIcon(ctx: Context, kind: String, plus: Boolean): View {
        val icon = when {
            kind == "call" || kind == "video" -> WIcons.PHONE
            kind == "gift" -> WIcons.GIFT
            plus -> WIcons.ARROW_DOWN_LEFT
            else -> WIcons.ARROW_UP_RIGHT
        }
        val f = FrameLayout(ctx).apply { background = round(if (plus) Ui.c("#EEFAF2") else Ui.c("#F4EEFD"), 14f) }
        f.addView(WIcons.view(ctx, icon, if (plus) WC.green else WC.vivid, 19f, 2.3f).apply {
            layoutParams = FrameLayout.LayoutParams(Ui.dpi(19f), Ui.dpi(19f), Gravity.CENTER)
        })
        f.layoutParams = LinearLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(42f))
        return f
    }

    /** "₹10" with a crossed-out old number: "instead of ~~12~~". */
    fun insteadOf(ctx: Context, was: Long, color: Int): TextView {
        val sb = SpannableStringBuilder("instead of ")
        val start = sb.length
        sb.append(wRupees(was))
        sb.setSpan(android.text.style.StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text(ctx, sb, 13f, color, false, 16f)
    }
}

/** A ScrollView that never grows past [maxFraction] of the screen (maxHeight: 92vh). */
@SuppressLint("ViewConstructor")
class WMaxScroll(ctx: Context, private val maxFraction: Float) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * maxFraction).toInt()
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (size > 0) Math.min(size, max) else max
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}

/**
 * Content no wider than 480px, centred (maxWidth: 480, margin: 0 auto).
 * [paddingInside]: the 480 already counts the padding (box-sizing: border-box).
 */
class WMax(ctx: Context, private val maxDp: Float = 480f, private val paddingInside: Boolean = false) : LinearLayout(ctx) {
    init { orientation = VERTICAL; clipChildren = false; clipToPadding = false }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = Ui.dpi(maxDp) + if (paddingInside) 0 else paddingLeft + paddingRight
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (w > max) View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/**
 * SheetShell: dims the page and slides a white sheet up from the bottom with
 * a purple header (close ✕ or back ‹ and the title). Tap outside to close.
 */
class WSheet(private val host: FrameLayout, title: String, private val onClose: () -> Unit, onBack: (() -> Unit)? = null) {
    private val ctx = host.context
    val root = FrameLayout(ctx)
    private val panel = WMax(ctx)
    private val sheetView: View
    /** where the cards go (margin: -26px 14px 0, over the header) */
    val content: LinearLayout = WV.box(ctx, true)

    init {
        root.setBackgroundColor(Color.argb(128, 28, 6, 58))
        root.elevation = Ui.dp(25f) // above the pull-down circle
        root.isClickable = true
        root.setOnClickListener { onClose() }
        root.contentDescription = title

        val scroll = WMaxScroll(ctx, 0.92f).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = WV.topRound(Color.WHITE, 26f)
            clipToOutline = true
            isClickable = true // taps on the sheet stay on the sheet
        }
        panel.setPadding(0, 0, 0, Ui.dpi(18f))

        val head = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
            background = WV.topRound(WC.head, 26f)
            setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(40f))
        }
        val btn = FrameLayout(ctx).apply {
            background = WV.oval(Color.argb(41, 255, 255, 255))
            contentDescription = if (onBack != null) "Back" else "Close"
            isClickable = true
            setOnClickListener { (onBack ?: onClose)() }
        }
        val icon = if (onBack != null) WIcons.view(ctx, WIcons.CHEVRON_LEFT, Color.WHITE, 20f) else WIcons.view(ctx, WIcons.X, Color.WHITE, 18f)
        btn.addView(icon, FrameLayout.LayoutParams(icon.layoutParams.width, icon.layoutParams.height, Gravity.CENTER))
        head.addView(btn, WV.lp(Ui.dpi(36f), Ui.dpi(36f)))
        head.addView(WV.text(ctx, title, 18f, Color.WHITE, true), WV.lp(WV.WRAP, WV.WRAP, l = 10f))
        panel.addView(head, WV.lp(WV.MATCH, WV.WRAP))
        panel.addView(content, WV.lp(WV.MATCH, WV.WRAP, l = 14f, t = -26f, r = 14f))

        scroll.addView(panel, FrameLayout.LayoutParams(WV.MATCH, WV.WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(WV.MATCH, WV.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            // maxWidth 480
            width = Math.min(host.resources.displayMetrics.widthPixels, Ui.dpi(480f))
        })
        sheetView = scroll
    }

    fun show() {
        host.addView(root, FrameLayout.LayoutParams(WV.MATCH, WV.MATCH))
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(200).start()
        // slides up from below (after 70ms), like .tfw-sheet
        sheetView.translationY = host.resources.displayMetrics.heightPixels.toFloat()
        sheetView.post {
            sheetView.translationY = sheetView.height.toFloat()
            sheetView.animate().translationY(0f).setStartDelay(70).setDuration(340).setInterpolator(WV.EASE_OUT).start()
        }
    }

    fun dismiss() {
        sheetView.animate().cancel()
        host.removeView(root)
    }
}

/** The ✓ drawn in (the payment-successful circle): stroke 3, white. */
class WCheckView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }
    private val part = Path()
    private val measure = PathMeasure(path, false)
    private var k = 0f

    fun start() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 250; duration = 450
            interpolator = PathInterpolator(0f, 0f, 0.58f, 1f) // ease-out
            addUpdateListener { k = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(c: Canvas) {
        part.reset()
        measure.getSegment(0f, measure.length * k, part, true)
        c.save()
        c.scale(width / 24f, height / 24f)
        c.drawPath(part, p)
        c.restore()
    }
}

/** .tfw-pop: grows in, a little too big, then settles. */
fun popIn(v: View) {
    v.scaleX = 0.4f; v.scaleY = 0.4f; v.alpha = 0f
    v.animate().scaleX(1.08f).scaleY(1.08f).alpha(1f).setDuration(300)
        .setInterpolator(PathInterpolator(0.2f, 0.9f, 0.3f, 1.3f))
        .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.3f, 1.3f)).start() }
        .start()
}
