package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The True Find "long train" loader (TfSpinner): five dots, big to small, running round. */
class TfLoader(ctx: Context, private val sizeDp: Float = 24f, var color: Int = Tf.BLUE) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val ease = PathInterpolator(0.5f, 0f, 0.5f, 1f)
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    override fun onMeasure(w: Int, h: Int) { val s = Ui.dpi(sizeDp); setMeasuredDimension(resolveSize(s, w), resolveSize(s, h)) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (visibility == VISIBLE) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, v: Int) {
        super.onVisibilityChanged(changedView, v)
        if (!isAttachedToWindow) return
        if (v == VISIBLE && isShown) { if (!anim.isStarted) anim.start() } else anim.cancel()
    }
    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val sizes = floatArrayOf(0.22f, 0.192f, 0.164f, 0.136f, 0.108f)
        val op = floatArrayOf(1f, 0.8f, 0.62f, 0.45f, 0.3f)
        p.color = color
        for (i in 0 until 5) {
            val f = ((t + 0.08f * i / 1.3f) % 1f + 1f) % 1f
            c.save(); c.translate(cx, cy); c.rotate(360f * ease.getInterpolation(f))
            p.alpha = (255 * op[i] * Color.alpha(color) / 255f).toInt()
            c.drawCircle(0f, -size / 2f + size * 0.12f, size * sizes[i] / 2f, p)
            c.restore()
        }
    }
}

/** The on / off switch of Profile (46 × 28). */
class TfToggle(ctx: Context, private val onColor: Int = Ui.c("#4C1D95")) : View(ctx) {
    var on = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var k = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()
    init { isClickable = true; setOnClickListener { val v = !on; onChange?.invoke(v) }; Ui.pressable(this, 0.97f) }
    fun set(v: Boolean, animate: Boolean = true) {
        if (v == on && (k == 1f) == v) return
        on = v
        if (!animate) { k = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(k, if (v) 1f else 0f).apply { duration = 200; addUpdateListener { k = it.animatedValue as Float; invalidate() }; start() }
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.dpi(46f), Ui.dpi(28f))
    override fun onDraw(c: Canvas) {
        p.color = blend(Ui.c("#D9D2E1"), onColor, k)
        r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, height / 2f, height / 2f, p)
        p.color = Color.WHITE
        val x = Ui.dp(3f) + (Ui.dp(21f) - Ui.dp(3f)) * k
        c.drawCircle(x + Ui.dp(11f), height / 2f, Ui.dp(11f), p)
    }
    private fun blend(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
}

/**
 * Profile's button (Btn): primary purple, light white with a line, or danger red;
 * 48 high, round 14, bold 15. busy → the loader instead of the text.
 */
class TfBtn(ctx: Context, label: CharSequence, val kind: String = "primary", icon: Drawable? = null,
            private val heightDp: Float = 48f, radiusDp: Float = 14f, sizeDp: Float = 15f) : FrameLayout(ctx) {
    val text: TextView
    private val loader: TfLoader
    var busy = false
        set(v) { field = v; text.visibility = if (v) INVISIBLE else VISIBLE; loader.visibility = if (v) VISIBLE else GONE; refresh() }
    var off = false
        set(v) { field = v; refresh() }

    init {
        val (bg, fg) = when (kind) {
            "light" -> Tf.box(Color.WHITE, radiusDp, 1.5f, Ui.c("#E7E0F2")) to Ui.c("#3A1478")
            "danger" -> Tf.box(Ui.c("#B91C1C"), radiusDp) to Color.WHITE
            "dark" -> Tf.box(Ui.c("#17121F"), radiusDp) to Color.WHITE
            else -> Tf.box(Ui.c("#4C1D95"), radiusDp) to Color.WHITE
        }
        background = bg
        text = Tf.t(ctx, label, sizeDp, fg).apply {
            gravity = Gravity.CENTER
            if (icon != null) { compoundDrawablePadding = Ui.dpi(8f); setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null) }
        }
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        loader = TfLoader(ctx, 18f, if (kind == "light") Tf.BLUE else Color.WHITE).apply { visibility = GONE }
        addView(loader, LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        Ui.pressable(this, 0.97f)
    }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(heightDp), MeasureSpec.EXACTLY))
    private fun refresh() { isEnabled = !off && !busy; alpha = if (off) 0.5f else 1f }
    fun label(s: CharSequence) { text.text = s }
}

/**
 * Pop-ups over the whole screen (the web's portal layer): bottom sheets, full
 * pages from the right, the Settings drawer from the left, centred dialogs and
 * fades. The phone's Back closes the top one first.
 */
class TfLayer(private val host: FrameLayout) {
    private class Item(val view: View, val back: () -> Boolean, val close: () -> Unit)
    private val stack = ArrayList<Item>()
    val open: Boolean get() = stack.isNotEmpty()
    var onChange: (() -> Unit)? = null
    val ctx: Context get() = host.context

    fun back(): Boolean { val top = stack.lastOrNull() ?: return false; if (!top.back()) top.close(); return true }
    fun closeAll() { for (i in stack.toList()) remove(i.view, false) }
    fun has(v: View) = stack.any { it.view === v }

    private fun add(v: View, back: () -> Boolean, close: () -> Unit, z: Float) {
        v.elevation = Ui.dp(60f) + z
        host.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stack.add(Item(v, back, close))
        onChange?.invoke()
    }

    fun remove(v: View, animate: Boolean = true) {
        val i = stack.indexOfFirst { it.view === v }
        if (i < 0) return
        stack.removeAt(i)
        hideKeyboard(v)
        if (animate) v.animate().alpha(0f).setDuration(180).withEndAction { host.removeView(v) }.start() else host.removeView(v)
        onChange?.invoke()
    }

    /**
     * Bottom sheet: dim + panel (already filled), sliding up (.34 s, 70 ms late).
     * The panel scrolls when taller than [maxFrac] of the screen. Returns close().
     */
    fun sheet(panel: View, dim: Int = Color.argb(115, 24, 8, 52), maxFrac: Float = 0.88f, z: Float = 20f,
              locked: () -> Boolean = { false }, onClose: () -> Unit = {}): () -> Unit {
        val box = FrameLayout(ctx)
        val dimV = View(ctx).apply { setBackgroundColor(dim); alpha = 0f }
        box.addView(dimV, FrameLayout.LayoutParams(-1, -1))
        val hostH = (host.height - host.paddingTop - host.paddingBottom).takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        val maxH = (hostH * maxFrac).toInt()
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)) }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        scroll.addView(panel)
        box.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        var closed = false
        val close: () -> Unit = {
            if (!closed) {
                closed = true
                dimV.animate().alpha(0f).setDuration(200).start()
                scroll.animate().translationY(scroll.height.toFloat()).setDuration(240).setInterpolator(SdLayer.EASE_UP).withEndAction { remove(box, false) }.start()
                hideKeyboard(box)
                onClose()
            }
        }
        dimV.setOnClickListener { if (!locked()) close() }
        box.isClickable = true
        add(box, { locked() }, close, z)
        dimV.animate().alpha(1f).setDuration(200).start()
        scroll.translationY = 4000f
        scroll.post {
            scroll.translationY = scroll.height.toFloat()
            scroll.animate().translationY(0f).setStartDelay(70).setDuration(340).setInterpolator(SdLayer.EASE_UP).start()
        }
        return close
    }

    /** A full page sliding in from the right. */
    fun page(view: View, z: Float = 10f, back: () -> Boolean = { false }, onClose: () -> Unit = {}): () -> Unit {
        var closed = false
        val close: () -> Unit = {
            if (!closed) {
                closed = true
                hideKeyboard(view)
                view.animate().translationX(view.width.toFloat()).setDuration(260).setInterpolator(SdLayer.EASE_IN).withEndAction { remove(view, false) }.start()
                onClose()
            }
        }
        view.isClickable = true
        add(view, back, close, z)
        view.translationX = host.width.toFloat().coerceAtLeast(1000f)
        view.animate().translationX(0f).setDuration(300).setInterpolator(SdLayer.EASE_IN).start()
        return close
    }

    /** A centred dialog: dim + card popping in (scale .94 → 1). Tap on the dim closes unless locked. */
    fun dialog(card: View, dim: Int = Color.argb(128, 24, 8, 52), widthDp: Float = 340f, z: Float = 40f,
               locked: () -> Boolean = { false }, onClose: () -> Unit = {}): () -> Unit {
        val box = FrameLayout(ctx)
        val dimV = View(ctx).apply { setBackgroundColor(dim) }
        box.addView(dimV, FrameLayout.LayoutParams(-1, -1))
        val maxW = (host.width - Ui.dpi(48f)).takeIf { it > 0 } ?: (host.resources.displayMetrics.widthPixels - Ui.dpi(48f))
        box.addView(card, FrameLayout.LayoutParams(minOf(Ui.dpi(widthDp), maxW), -2, Gravity.CENTER))
        card.isClickable = true
        var closed = false
        val close: () -> Unit = { if (!closed) { closed = true; hideKeyboard(box); remove(box); onClose() } }
        dimV.setOnClickListener { if (!locked()) close() }
        box.isClickable = true
        add(box, { locked() }, close, z)
        box.alpha = 0f; box.animate().alpha(1f).setDuration(180).start()
        card.scaleX = 0.94f; card.scaleY = 0.94f
        card.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        return close
    }

    /** A pop-up that just fades in (photo viewer). */
    fun fade(view: View, z: Float = 30f, back: () -> Boolean = { false }, onClose: () -> Unit = {}): () -> Unit {
        var closed = false
        val close: () -> Unit = { if (!closed) { closed = true; remove(view); onClose() } }
        view.isClickable = true
        add(view, back, close, z)
        view.alpha = 0f; view.animate().alpha(1f).setDuration(180).start()
        return close
    }

    private fun hideKeyboard(v: View) {
        try { (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) } catch (e: Exception) { }
    }
}

/** A short message over everything (Home: above the bottom bar; Profile: under the status bar). */
class TfToast(private val host: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hide = Runnable { view?.let { v -> v.animate().alpha(0f).setDuration(180).withEndAction { host.removeView(v); if (view === v) view = null }.start() } }

    /** top = true: under the status bar (Profile); else bottom above [bottomPx] (Home). */
    fun show(text: String, bad: Boolean = false, top: Boolean = false, bottomPx: Int = 0, ms: Long = 2600, style: String = "home") {
        main.removeCallbacks(hide)
        view?.let { host.removeView(it) }
        val ctx = host.context
        val v = Tf.t(ctx, text, if (style == "home") 14.5f else 13.5f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            if (style == "home") {
                background = Tf.box(Color.argb(235, 30, 10, 60), 999f)
                setPadding(Ui.dpi(18f), Ui.dpi(11f), Ui.dpi(18f), Ui.dpi(11f))
            } else {
                background = Tf.box(if (bad) Ui.c("#7F1D1D") else Ui.c("#1E1433"), 14f)
                setPadding(Ui.dpi(16f), Ui.dpi(11f), Ui.dpi(16f), Ui.dpi(11f))
            }
            maxWidth = (host.width * 0.88f).toInt().coerceAtLeast(Ui.dpi(200f))
            elevation = Ui.dp(200f)
            isClickable = false
        }
        val lp = FrameLayout.LayoutParams(-2, -2, (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL)
        if (top) lp.topMargin = Ui.dpi(36f) else lp.bottomMargin = bottomPx
        host.addView(v, lp)
        v.alpha = 0f; v.scaleX = if (style == "home") 1f else 0.94f; v.scaleY = v.scaleX
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        view = v
        main.postDelayed(hide, ms)
    }
    fun clear() { main.removeCallbacks(hide); view?.let { host.removeView(it) }; view = null }
}

/** One-line text input styled like .tfp-input (48 high, round 12, 1.5 line; purple line when focused). */
@SuppressLint("ViewConstructor")
class TfInput(ctx: Context, hint: String, multiLine: Boolean = false, lines: Int = 1, private val lineColor: Int = Ui.c("#E7E0F2"),
              private val focusColor: Int = Ui.c("#4C1D95")) : EditText(ctx) {
    var onText: ((String) -> Unit)? = null
    init {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(16f))
        typeface = Ui.regular
        setTextColor(Ui.c("#1E1433"))
        setHintTextColor(Ui.c("#9A91AD"))
        this.hint = hint
        includeFontPadding = false
        background = Tf.box(Color.WHITE, 12f, 1.5f, lineColor)
        if (multiLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLines(lines); gravity = Gravity.TOP or Gravity.START
            setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
            setLineSpacing(0f, 1.2f)
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1; isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
            minHeight = Ui.dpi(48f)
        }
        setOnFocusChangeListener { _, f -> background = Tf.box(Color.WHITE, 12f, 1.5f, if (f) focusColor else lineColor) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onText?.invoke(s?.toString().orEmpty()) }
        })
    }
    val value: String get() = text?.toString().orEmpty()
}

/** Small helpers to build rows of icon + text */
object TfRow {
    fun icon(ctx: Context, d: Drawable, sizeDp: Float): ImageView = ImageView(ctx).apply {
        setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
    }
    /** a square tile with a centred icon (Profile's Tile / SecHead icon) */
    fun tile(ctx: Context, bg: Int, d: Drawable, sizeDp: Float, radiusDp: Float, iconDp: Float): FrameLayout = FrameLayout(ctx).apply {
        background = Tf.box(bg, radiusDp)
        addView(ImageView(ctx).apply { setImageDrawable(d) }, FrameLayout.LayoutParams(Ui.dpi(iconDp), Ui.dpi(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
    }
}

/** A drawable that is just a coloured round dot (the web's status dots). */
class DotDrawable(private val color: Int, private val sizeDp: Float, private val ringColor: Int = Color.TRANSPARENT, private val ringDp: Float = 0f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth() = Ui.dpi(sizeDp)
    override fun getIntrinsicHeight() = Ui.dpi(sizeDp)
    override fun draw(c: Canvas) {
        val b = bounds; val r = minOf(b.width(), b.height()) / 2f
        if (ringDp > 0f) { p.color = ringColor; c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, p) }
        p.color = color; c.drawCircle(b.exactCenterX(), b.exactCenterY(), r - Ui.dp(ringDp), p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

@Suppress("unused") private fun keepMotion(e: MotionEvent) = e
@Suppress("unused") private val keepGd = GradientDrawable()
