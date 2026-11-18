package app.truefind.android.login

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.method.PasswordTransformationMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.truefind.android.Photos
import app.truefind.android.TfLoader
import app.truefind.android.Ui
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The dark sign-in pages of the web (AuthLayout, NeonAuthShell, the MFA card): the neon backdrop, the
 * shadcn inputs / buttons / OTP boxes with the app's theme (index.css), drawn natively. Sizes in dp = CSS px.
 */
internal object AK {
    val primary get() = LK.primary
    val ring get() = LK.primary
    val fg get() = LK.fg
    val mutedFg get() = LK.mutedFg
    val input get() = LK.border           // --input = --border
    val destructive get() = LK.destructive
    fun w(a: Float) = LK.a(Color.WHITE, a)
    fun p(a: Float) = LK.a(LK.primary, a)
    /** rounded-md = calc(var(--radius) - 2px) = 14px (--radius 1rem) */
    const val MD = 14f
    const val LOGO_URL = "https://media.base44.com/images/public/6ac9c4c6f75fbeb2364dff2b/63d63af70_generated_image.png"
    const val GOOGLE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/></svg>"""

    /** Label: text-sm font-medium leading-none (Calibri has no 500: regular) */
    fun label(ctx: Context, s: String, color: Int = Color.WHITE) = LK.t(ctx, s, 14f, color, false, 1f)
    /** a paragraph: text-sm (14 / 20) */
    fun small(ctx: Context, s: CharSequence, color: Int, center: Boolean = true, bold: Boolean = false) =
        LK.t(ctx, s, 14f, color, bold, 20f / 14f).apply { if (center) gravity = Gravity.CENTER_HORIZONTAL }
    fun error(ctx: Context) = small(ctx, "", destructive).apply { visibility = View.GONE }
    fun setText(v: TextView, s: String) { v.text = s; v.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE }

    /** text with links: (text, null) or (label, onTap) — font-semibold, the given colour, underlined */
    fun linked(ctx: Context, size: Float, lhPx: Float, color: Int, linkColor: Int, underline: Boolean, vararg parts: Pair<String, (() -> Unit)?>): LText =
        LK.t(ctx, "", size, color, false, lhPx / size).apply {
            val sb = SpannableStringBuilder()
            for ((txt, tap) in parts) {
                val st = sb.length; sb.append(txt)
                if (tap != null) sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) { tap() }
                    override fun updateDrawState(ds: TextPaint) { ds.color = linkColor; ds.isUnderlineText = underline; ds.typeface = Ui.bold }
                }, st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = sb; movementMethod = LinkMovementMethod.getInstance(); highlightColor = Color.TRANSPARENT
        }

    /** a text button: text-sm font-semibold text-primary underline (block mx-auto) */
    fun textBtn(ctx: Context, s: String, color: Int = primary, underline: Boolean = true, size: Float = 14f, bold: Boolean = true, onTap: () -> Unit): TextView =
        LK.t(ctx, s, size, color, bold, 20f / 14f).apply {
            if (underline) paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { if (isEnabled) onTap() }
            LK.press(this, 0.97f)
        }
}

// ═══════════════════════════════════ the neon backdrop (NeonBackdrop.jsx) ═══════════════════════════════════
/**
 * Calm dark base, five soft colour glows (one pulsing), two frosted glass sheets, four slow heartbeat hearts
 * and a readability scrim. The blurred glows are painted once, small, and stretched (they are pure blur).
 */
@SuppressLint("ViewConstructor")
internal class NeonBg(ctx: Context) : View(ctx) {
    private class Glow(val l: (Float, Float) -> Float, val t: (Float, Float) -> Float, val wf: Float, val hf: Float, val color: Int, val sigma: Float)
    private val d get() = Ui.density
    private val glows = listOf(
        Glow({ _, _ -> -96f * d }, { _, _ -> -128f * d }, 0.70f, 0.45f, LK.ca("#F43F5E", 0.25f), 90f),
        Glow({ w, _ -> w + 96f * d - 0.65f * w }, { _, h -> 0.35f * h }, 0.65f, 0.40f, LK.ca("#22D3EE", 0.20f), 90f),
        Glow({ w, _ -> 0.10f * w }, { _, h -> h + 0.10f * h - 0.35f * h }, 0.60f, 0.35f, LK.ca("#FBBF24", 0.15f), 100f),
        Glow({ _, _ -> -112f * d }, { _, h -> 0.55f * h }, 0.70f, 0.40f, LK.ca("#8B5CF6", 0.15f), 120f),
        Glow({ w, _ -> w + 96f * d - 0.60f * w }, { _, h -> h - 0.05f * h - 0.35f * h }, 0.60f, 0.35f, LK.ca("#FB7185", 0.15f), 120f),
    )
    private var pulseBmp: Bitmap? = null
    private var restBmp: Bitmap? = null
    private var made = 0L
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val sheetFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheetLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scrim = Paint()
    private val heart: Drawable = LK.lu(LK.I.Heart, LK.a(LK.primary, 0.4f), 24f, 2f, "currentColor")
    private var t0 = 0L
    private var now = 0L
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { now = android.os.SystemClock.uptimeMillis(); invalidate() }
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); t0 = android.os.SystemClock.uptimeMillis(); now = t0; anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        val k = 1f / 6f
        val bw = max(1, (w * k).roundToInt()); val bh = max(1, (h * k).roundToInt())
        fun paint(list: List<Glow>): Bitmap {
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.scale(k, k)
            for (g in list) {
                val sig = g.sigma * d * k
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = g.color; maskFilter = BlurMaskFilter(((sig - 0.5f) / 0.57735f).coerceAtLeast(1f) / k, BlurMaskFilter.Blur.NORMAL) }
                val l = g.l(w.toFloat(), h.toFloat()); val t = g.t(w.toFloat(), h.toFloat())
                c.drawOval(RectF(l, t, l + g.wf * w, t + g.hf * h), p)
            }
            return b
        }
        pulseBmp = paint(glows.take(1)); restBmp = paint(glows.drop(1))
        scrim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(LK.a(LK.bg, 0.6f), Color.TRANSPARENT, LK.a(LK.bg, 0.7f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        made = now
    }

    /** tf-heartbeat: 1 → 1.22 → 1 → 1.15 → 1 (in the first half), ease-in-out between the steps */
    companion object {
        private val KEYS = floatArrayOf(0f, 0.12f, 0.24f, 0.36f, 0.48f, 1f)
        private val VALS = floatArrayOf(1f, 1.22f, 1f, 1.15f, 1f, 1f)
        private val EASE = PathInterpolator(0.42f, 0f, 0.58f, 1f)
        fun beat(ms: Long, period: Long): Float {
            if (ms < 0) return 1f
            val f = (ms % period).toFloat() / period
            for (i in 0 until KEYS.size - 1) if (f <= KEYS[i + 1]) {
                val u = EASE.getInterpolation((f - KEYS[i]) / (KEYS[i + 1] - KEYS[i]))
                return VALS[i] + (VALS[i + 1] - VALS[i]) * u
            }
            return 1f
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(LK.bg)
        val ms = now - t0
        // tf-pulse-glow: opacity .35 ↔ .75 over 3 s (ease-in-out)
        val ph = (ms % 3000L) / 3000f
        val tri = if (ph < 0.5f) ph * 2f else (1f - ph) * 2f
        val op = 0.35f + 0.40f * EASE.getInterpolation(tri)
        pulseBmp?.let { bp.alpha = (op * 255).roundToInt(); c.drawBitmap(it, null, RectF(0f, 0f, w, h), bp) }
        restBmp?.let { bp.alpha = 255; c.drawBitmap(it, null, RectF(0f, 0f, w, h), bp) }
        // the two frosted glass sheets
        fun sheet(left: Float, top: Float, sw: Float, sh: Float, deg: Float, fill: Float, line: Float) {
            c.save(); c.rotate(deg, left + sw / 2f, top + sh / 2f)
            sheetFill.color = LK.a(Color.WHITE, fill); sheetLine.color = LK.a(Color.WHITE, line); sheetLine.strokeWidth = d
            val r = RectF(left, top, left + sw, top + sh); val rr = 48f * d
            c.drawRoundRect(r, rr, rr, sheetFill)
            r.inset(d / 2f, d / 2f); c.drawRoundRect(r, rr, rr, sheetLine)
            c.restore()
        }
        sheet(-64f * d, 0.12f * h, 1.30f * w, 0.26f * h, -6f, 0.04f, 0.07f)
        sheet(w + 80f * d - 1.40f * w, h - 0.14f * h - 0.30f * h, 1.40f * w, 0.30f * h, 3f, 0.03f, 0.06f)
        // the hearts (tf-heartbeat-slow, 2.4 s, started .6 s apart)
        fun heartAt(x: Float, y: Float, size: Float, delay: Long) {
            val s = beat(ms - delay, 2400L)
            val px = size * d
            c.save(); c.translate(x, y); c.scale(s, s, px / 2f, px / 2f)
            heart.setBounds(0, 0, px.roundToInt(), px.roundToInt()); heart.draw(c)
            c.restore()
        }
        heartAt(0.08f * w, 0.12f * h, 20f, 0)
        heartAt(w - 0.09f * w - 15f * d, 0.30f * h, 15f, 600)
        heartAt(0.06f * w, 0.62f * h, 24f, 1200)
        heartAt(w - 0.10f * w - 16f * d, 0.80f * h, 16f, 1800)
        c.drawRect(0f, 0f, w, h, scrim)
    }
}

/** the logo pill of AuthLayout (the TRUE FIND wordmark picture), with its heartbeat */
@SuppressLint("ViewConstructor")
internal class LogoPill(ctx: Context) : FrameLayout(ctx) {
    private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { val s = NeonBg.beat((it.animatedFraction * 1600).toLong(), 1600); scaleX = s; scaleY = s }
    }
    init {
        clipChildren = false
        // ring-1 ring-white/20 + shadow-[0_0_24px_primary/.4]
        background = CssBox(LK.card, 20f, shadows = listOf(Sh(0f, 0f, 24f, 0f, AK.p(0.4f)), Sh(0f, 0f, 0f, 1f, AK.w(0.2f))))
        val clip = FrameLayout(ctx).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, v.height / 2f) } }
            clipToOutline = true
            addView(img, LayoutParams(-1, -1))
        }
        addView(clip, LayoutParams(-1, -1))
        contentDescription = "TRUE FIND"
        Photos.loadCover(listOf(AK.LOGO_URL), Ui.dpi(160f) * 2, Ui.dpi(40f) * 2) { b -> if (b != null) { img.alpha = 0f; img.setImageBitmap(b); img.animate().alpha(1f).setDuration(200).start() } }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}

// ═══════════════════════════════════ page shells ═══════════════════════════════════
/** A dark page: the neon backdrop with a scrolling column on top, centred while it is shorter than the screen. */
internal open class DarkPage(ctx: Context, layer: LoginLayer, private val neon: Boolean = true) : LPage(ctx, layer) {
    protected val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true; clipChildren = false }
    /** the content box (fills the screen height at least) */
    protected val frame = FrameLayout(ctx).apply { clipChildren = false }
    init {
        setBackgroundColor(LK.bg)
        if (neon) addView(NeonBg(ctx), LayoutParams(-1, -1))
        scroll.addView(frame, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
    }
    /** shows [content] (max 448 wide), centred in the screen ([center]) or from the top */
    protected fun put(content: View, padX: Float, padY: Float, center: Boolean = true, maxW: Float = 448f) {
        frame.removeAllViews()
        frame.setPadding(Ui.dpi(padX), Ui.dpi(padY), Ui.dpi(padX), Ui.dpi(padY))
        val box = object : FrameLayout(context) {
            override fun onMeasure(w: Int, h: Int) {
                val room = MeasureSpec.getSize(w)
                super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(room, Ui.dpi(maxW)), MeasureSpec.EXACTLY), h)
            }
        }.apply { clipChildren = false; addView(content, LayoutParams(-1, -2)) }
        frame.addView(box, LayoutParams(-1, -2, if (center) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        content.alpha = 0f; content.translationY = Ui.dp(6f)
        content.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    /** AuthLayout: logo, title + line, the glowing card, and a footer line */
    protected fun authLayout(title: String, sub: String?, footer: View?, vararg children: View): LinearLayout {
        val ctx = context
        val col = LK.col(ctx, Gravity.CENTER_HORIZONTAL)
        col.addView(LogoPill(ctx), LK.lp(Ui.dpi(160f), Ui.dpi(40f), b = 24f))
        col.addView(LK.t(ctx, title, 30f, Color.WHITE, true, 36f / 30f, -0.75f).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2))
        if (!sub.isNullOrEmpty()) col.addView(LK.t(ctx, sub, 16f, AK.w(0.7f), false, 1.5f).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2, t = 8f))
        val card = LK.col(ctx).apply {
            background = CssBox(LK.a(LK.card, 0.8f), 16f, 1f, AK.p(0.6f), shadows = listOf(Sh(0f, 0f, 36f, 0f, AK.p(0.35f))))
            setPadding(Ui.dpi(33f), Ui.dpi(33f), Ui.dpi(33f), Ui.dpi(33f))
        }
        for (v in children) card.addView(v, (v.layoutParams as? LinearLayout.LayoutParams) ?: LK.lp(-1, -2))
        col.addView(card, LK.lp(-1, -2, t = 40f))
        if (footer != null) col.addView(footer, LK.lp(-1, -2, t = 24f))
        return col
    }

    /** the footer line of AuthLayout: "New here? Create account" */
    protected fun footer(text: String, link: String, onTap: () -> Unit): LText =
        AK.linked(context, 14f, 20f, AK.w(0.7f), AK.primary, true, text to null, link to onTap).apply { gravity = Gravity.CENTER }
}

/** a vertical stack with space-y-N between its children */
internal fun stack(ctx: Context, gapDp: Float, vararg views: View): LinearLayout = LK.col(ctx).apply {
    var first = true
    for (v in views) {
        val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LK.lp(-1, -2)
        if (!first) lp.topMargin += Ui.dpi(gapDp)
        addView(v, lp)
        first = false
    }
}

// ═══════════════════════════════════ shadcn parts ═══════════════════════════════════
/**
 * Input: h, rounded, border, background, a 16 px Lucide icon 12 px from the left (pl-10 then), text 16;
 * focused → a 1 px ring in the theme's ring colour. [light] = the white/15 border, white text and white/40 hint.
 */
@SuppressLint("ViewConstructor")
internal class AField(
    ctx: Context, hint: String, icon: String? = null, private val hDp: Float = 48f, private val radius: Float = AK.MD,
    private val fill: Int = LK.a(LK.bg, 0.6f), private val line: Int = AK.w(0.15f), textColor: Int = Color.WHITE, hintColor: Int = AK.w(0.4f),
    iconColor: Int = AK.mutedFg, padL: Float = if (icon != null) 40f else 12f, padR: Float = 12f, size: Float = 16f,
) : FrameLayout(ctx) {
    val edit = object : EditText(ctx) {
        override fun onFocusChanged(f: Boolean, d: Int, r: android.graphics.Rect?) { super.onFocusChanged(f, d, r); look() }
    }
    var bad = false; set(v) { field = v; look() }
    var onText: ((String) -> Unit)? = null
    private var setting = false
    init {
        clipChildren = false
        edit.apply {
            background = null
            setPadding(Ui.dpi(padL), 0, Ui.dpi(padR), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(size))
            typeface = Ui.regular; includeFontPadding = false
            setTextColor(textColor); setHintTextColor(hintColor)
            this.hint = hint
            isSingleLine = true; maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!setting) onText?.invoke(s?.toString().orEmpty()) }
            })
        }
        addView(edit, LayoutParams(-1, -1))
        if (icon != null) addView(ImageView(ctx).apply { setImageDrawable(LK.lu(icon, iconColor, 16f)) }, LK.flp(Ui.dpi(16f), Ui.dpi(16f), Gravity.CENTER_VERTICAL or Gravity.START, l = 12f))
        setOnClickListener { LK.showKeyboard(edit) }
        look()
    }
    private fun look() {
        val focused = edit.hasFocus()
        val l = if (bad) AK.destructive else line
        val sh = ArrayList<Sh>(); sh.add(Sh(0f, 1f, 2f, 0f, Color.argb(13, 0, 0, 0)))
        if (focused) sh.add(Sh(0f, 0f, 0f, 1f, if (bad) AK.destructive else AK.ring))
        background = CssBox(fill, radius, 1f, l, shadows = sh)
    }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(hDp), MeasureSpec.EXACTLY))
    override fun setEnabled(e: Boolean) { super.setEnabled(e); edit.isEnabled = e; alpha = if (e) 1f else 0.5f }
    val value: String get() = edit.text?.toString().orEmpty()
    fun put(v: String) { if (value == v) return; setting = true; edit.setText(v); edit.setSelection(v.length); setting = false }
    fun email(): AField { edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; edit.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS); return this }
    fun password(newOne: Boolean): AField {
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        edit.transformationMethod = PasswordTransformationMethod.getInstance()
        edit.typeface = Ui.regular
        edit.setAutofillHints(if (newOne) View.AUTOFILL_HINT_PASSWORD else View.AUTOFILL_HINT_PASSWORD)
        return this
    }
    fun phone(): AField { edit.inputType = InputType.TYPE_CLASS_PHONE; edit.setAutofillHints(View.AUTOFILL_HINT_PHONE); return this }
    fun number(): AField { edit.inputType = InputType.TYPE_CLASS_NUMBER; return this }
    fun onGo(f: () -> Unit): AField {
        edit.imeOptions = EditorInfo.IME_ACTION_GO
        edit.setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) { f(); true } else false }
        return this
    }
}

/**
 * Button (shadcn): default (primary), outline or ghost; [hDp] high, rounded, text 14 (or [size]); busy → the
 * white spinner before the text; disabled → .5; pressed → scale .97 (the app's button:active).
 */
@SuppressLint("ViewConstructor")
internal class ABtn(
    ctx: Context, label: String, private val kind: String = "primary", private val hDp: Float = 48f, radius: Float = AK.MD,
    size: Float = 14f, bold: Boolean = true, icon: Drawable? = null, private val iconGap: Float = 16f, glow: Sh? = null,
) : FrameLayout(ctx) {
    private val row = LK.row(ctx, Gravity.CENTER)
    private val spin = TfLoader(ctx, 16f, Color.WHITE).apply { visibility = View.GONE }
    private val iconV = icon?.let { ImageView(ctx).apply { setImageDrawable(it) } }
    private val text = LK.one(LK.t(ctx, label, size, if (kind == "primary") Color.WHITE else AK.fg, bold))
    var busy = false; set(v) { field = v; draw() }
    var off = false; set(v) { field = v; draw() }
    var busyLabel: String? = null
    private val label0 = label
    init {
        clipChildren = false
        val sh = ArrayList<Sh>()
        if (kind == "primary") { sh.add(Sh(0f, 1f, 3f, 0f, Color.argb(26, 0, 0, 0))); sh.add(Sh(0f, 1f, 2f, -1f, Color.argb(26, 0, 0, 0))) }
        if (kind == "outline") sh.add(Sh(0f, 1f, 2f, 0f, Color.argb(13, 0, 0, 0)))
        if (glow != null) sh.add(glow)
        background = when (kind) {
            "primary" -> CssBox(AK.primary, radius, shadows = sh)
            "outline" -> CssBox(Color.TRANSPARENT, radius, 1f, AK.input, shadows = sh)
            else -> CssBox(Color.TRANSPARENT, radius)
        }
        row.addView(spin, LK.lp(Ui.dpi(16f), Ui.dpi(16f), r = iconGap))
        iconV?.let { row.addView(it, LK.lp(Ui.dpi(16f), Ui.dpi(16f), r = iconGap)) }
        row.addView(text)
        addView(row, LayoutParams(-2, -2, Gravity.CENTER))
        LK.press(this, 0.97f)
        draw()
    }
    fun setBackgroundBox(b: Drawable) { background = b }
    fun textColor(c: Int) { text.setTextColor(c) }
    fun spinnerSize(dp: Float, gap: Float) {
        row.removeView(spin)
        val s2 = TfLoader(context, dp, Color.WHITE).apply { visibility = spin.visibility }
        row.addView(s2, 0, LK.lp(Ui.dpi(dp), Ui.dpi(dp), r = gap))
        spinRef = s2
    }
    private var spinRef: TfLoader? = null
    private fun draw() {
        val sp = spinRef ?: spin
        sp.visibility = if (busy) View.VISIBLE else View.GONE
        iconV?.visibility = if (busy) View.GONE else View.VISIBLE
        text.text = if (busy && busyLabel != null) busyLabel else label0
        isEnabled = !(busy || off)
        alpha = if (busy || off) 0.5f else 1f
    }
    fun setLabel(s: String) { text.text = s }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(hDp), MeasureSpec.EXACTLY))
}

/**
 * InputOTP (input-otp + shadcn): six 36 × 36 boxes joined in a row (border-y border-r, the first with the left
 * border and rounded left corners, the last rounded right), the active box ringed with the caret blinking.
 */
@SuppressLint("ViewConstructor")
internal class OtpRow(ctx: Context, private val n: Int = 6) : FrameLayout(ctx) {
    val edit: EditText = object : EditText(ctx) {
        override fun onFocusChanged(f: Boolean, d: Int, r: android.graphics.Rect?) { super.onFocusChanged(f, d, r); slots.invalidate() }
        override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); if (selStart != text?.length ?: 0) setSelection(text?.length ?: 0) }
    }
    var code = ""; private set
    var bad = false; set(v) { field = v; slots.invalidate() }
    var onChange: ((String) -> Unit)? = null
    private var setting = false
    private var caretOn = true
    private val main = Handler(Looper.getMainLooper())
    private val blink = object : Runnable { override fun run() { caretOn = !caretOn; slots.invalidate(); main.postDelayed(this, if (caretOn) 875 else 375) } }
    private val slots: View = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.regular; textSize = Ui.dp(14f); textAlign = Paint.Align.CENTER; color = AK.fg }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.dpi(36f * n), Ui.dpi(36f))
        override fun onDraw(c: Canvas) {
            val s = Ui.dp(36f); val r = Ui.dp(AK.MD); val lw = Ui.dp(1f)
            val focused = edit.hasFocus() && edit.isEnabled
            val active = if (!focused) -1 else if (code.length >= n) n - 1 else code.length
            val line = if (bad) AK.destructive else AK.input
            // shadow-sm under each box
            for (i in 0 until n) {
                val x = i * s
                val rad = floatArrayOf(if (i == 0) r else 0f, if (i == n - 1) r else 0f, if (i == n - 1) r else 0f, if (i == 0) r else 0f)
                val path = android.graphics.Path()
                path.addRoundRect(RectF(x, 0f, x + s, s), floatArrayOf(rad[0], rad[0], rad[1], rad[1], rad[2], rad[2], rad[3], rad[3]), android.graphics.Path.Direction.CW)
                p.style = Paint.Style.FILL; p.color = Color.argb(13, 0, 0, 0)
                c.save(); c.translate(0f, lw); c.drawPath(path, p); c.restore()
            }
            for (i in 0 until n) {
                val x = i * s
                p.style = Paint.Style.FILL; p.color = line
                // border-y + border-r (+ border-l on the first), drawn as filled strips inside the box
                val rl = if (i == 0) r else 0f; val rr = if (i == n - 1) r else 0f
                val outer = android.graphics.Path().apply { addRoundRect(RectF(x, 0f, x + s, s), floatArrayOf(rl, rl, rr, rr, rr, rr, rl, rl), android.graphics.Path.Direction.CW) }
                val innerL = x + (if (i == 0) lw else 0f)
                val inner = android.graphics.Path().apply { addRoundRect(RectF(innerL, lw, x + s - lw, s - lw), floatArrayOf((rl - lw).coerceAtLeast(0f), (rl - lw).coerceAtLeast(0f), (rr - lw).coerceAtLeast(0f), (rr - lw).coerceAtLeast(0f), (rr - lw).coerceAtLeast(0f), (rr - lw).coerceAtLeast(0f), (rl - lw).coerceAtLeast(0f), (rl - lw).coerceAtLeast(0f)), android.graphics.Path.Direction.CW) }
                outer.op(inner, android.graphics.Path.Op.DIFFERENCE)
                c.drawPath(outer, p)
                val ch = code.getOrNull(i)?.toString() ?: ""
                if (ch.isNotEmpty()) { val fm = tp.fontMetrics; c.drawText(ch, x + s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, tp) }
            }
            if (active >= 0) {
                val x = active * s
                val rl = if (active == 0) r else 0f; val rr = if (active == n - 1) r else 0f
                p.style = Paint.Style.STROKE; p.strokeWidth = lw; p.color = AK.ring
                val ringPath = android.graphics.Path().apply { addRoundRect(RectF(x - lw / 2f, -lw / 2f, x + s + lw / 2f, s + lw / 2f), floatArrayOf(rl + lw / 2f, rl + lw / 2f, rr + lw / 2f, rr + lw / 2f, rr + lw / 2f, rr + lw / 2f, rl + lw / 2f, rl + lw / 2f), android.graphics.Path.Direction.CW) }
                c.drawPath(ringPath, p)
                if (code.getOrNull(active) == null && caretOn) {
                    p.style = Paint.Style.FILL; p.color = AK.fg
                    c.drawRect(x + s / 2f - lw / 2f, s / 2f - Ui.dp(8f), x + s / 2f + lw / 2f, s / 2f + Ui.dp(8f), p)
                }
            }
        }
    }
    init {
        clipChildren = false
        edit.apply {
            background = null; setTextColor(Color.TRANSPARENT); isCursorVisible = false
            inputType = InputType.TYPE_CLASS_NUMBER
            setAutofillHints("smsOTPCode")
            alpha = 0.01f; isLongClickable = false
            filters = arrayOf(android.text.InputFilter.LengthFilter(n))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (setting) return
                    val d = WelcomePage.onlyDigits(s?.toString()).take(n)
                    if (d != s?.toString()) { put(d) }
                    if (d != code) { code = d; caretOn = true; slots.invalidate(); onChange?.invoke(d) }
                }
            })
        }
        addView(slots, LayoutParams(-2, -2, Gravity.CENTER))
        addView(edit, LayoutParams(-1, -1))
        edit.setOnFocusChangeListener { _, f -> main.removeCallbacks(blink); caretOn = true; if (f) main.postDelayed(blink, 875); slots.invalidate() }
        contentDescription = "6-digit code"
    }
    fun put(v: String) { setting = true; edit.setText(v); edit.setSelection(v.length); setting = false; code = v; slots.invalidate() }
    fun focus() = LK.showKeyboard(edit)
    override fun setEnabled(e: Boolean) { super.setEnabled(e); edit.isEnabled = e; alpha = if (e) 1f else 0.5f; slots.invalidate() }
    override fun onDetachedFromWindow() { main.removeCallbacks(blink); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(36f), MeasureSpec.EXACTLY))
}

/** a page's spinner (white on the dark pages) */
internal fun darkSpinner(ctx: Context, dp: Float): View = TfLoader(ctx, dp, Color.WHITE)

/**
 * MobileSelect: a box that opens a bottom drawer (vaul) with the options; the picked one is bold in the theme
 * colour with a check. [leading] = a 16 px icon before the text.
 */
@SuppressLint("ViewConstructor")
internal class ASelect(
    ctx: Context, private val layer: LoginLayer, private val title: String, private var placeholder: String,
    private val hDp: Float = 48f, private val radius: Float = 16f, leading: String? = null,
) : FrameLayout(ctx) {
    var options: List<Pair<String, String>> = emptyList()
    var value = ""; private set
    var onPick: ((String) -> Unit)? = null
    private val txt = LK.one(LK.t(ctx, "", 14f, Color.WHITE))
    init {
        background = CssBox(AK.w(0.10f), radius, 1f, AK.w(0.15f))
        val row = LK.row(ctx)
        row.setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
        if (leading != null) row.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(leading, AK.w(0.5f), 16f)) }, LK.lp(Ui.dpi(16f), Ui.dpi(16f), r = 8f))
        row.addView(txt, LK.lp(0, -2, weight = 1f))
        row.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.ChevronDown, AK.w(0.5f), 16f)) }, LK.lp(Ui.dpi(16f), Ui.dpi(16f), l = 8f))
        addView(row, LayoutParams(-1, -1))
        isClickable = true
        setOnClickListener { if (isEnabled) open() }
        LK.press(this, 0.97f)
        draw()
    }
    fun set(v: String) { value = v; draw() }
    fun setPlaceholder(p: String) { placeholder = p; draw() }
    override fun setEnabled(e: Boolean) { super.setEnabled(e); alpha = if (e) 1f else 0.5f }
    private fun draw() {
        val sel = options.firstOrNull { it.first == value }
        txt.text = sel?.second ?: placeholder
        txt.typeface = if (sel != null) Ui.regular else Ui.regular
        txt.setTextColor(if (sel != null) Color.WHITE else AK.w(0.4f))
    }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(hDp), MeasureSpec.EXACTLY))

    fun open() {
        val ctx = context
        LK.hideKeyboard(this)
        var close: () -> Unit = {}
        val panel = object : LinearLayout(ctx) {}.apply {
            orientation = LinearLayout.VERTICAL
            background = CssBox(LK.bg, corners = floatArrayOf(10f, 10f, 0f, 0f), strokeDp = 1f, stroke = AK.input)
        }
        panel.addView(View(ctx).apply { background = CssBox(LK.muted, 4f) }, LK.lp(Ui.dpi(100f), Ui.dpi(8f), t = 16f, g = Gravity.CENTER_HORIZONTAL))
        panel.addView(LK.t(ctx, title, 16f, AK.fg, true, 1f).apply { gravity = Gravity.CENTER; setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(4f)) }, LK.lp(-1, -2))
        val list = LK.col(ctx).apply { setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), Ui.dpi(20f)) }
        for ((v, label) in options) {
            val on = v == value
            val row = LK.row(ctx).apply { setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), Ui.dpi(14f)); background = CssBox(Color.TRANSPARENT, 12f) }
            row.addView(LK.t(ctx, label, 14f, if (on) AK.primary else AK.fg, on, 20f / 14f), LK.lp(0, -2, weight = 1f))
            if (on) row.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.Check, AK.primary, 16f)) }, LK.lp(Ui.dpi(16f), Ui.dpi(16f), l = 12f))
            row.isClickable = true
            row.setOnClickListener { value = v; draw(); onPick?.invoke(v); close() }
            row.setOnTouchListener { vv, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) vv.background = CssBox(LK.muted, 12f) else if (e.actionMasked != android.view.MotionEvent.ACTION_MOVE) vv.background = CssBox(Color.TRANSPARENT, 12f); false }
            list.addView(row, LK.lp(-1, -2))
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.45f).roundToInt()
        val sc = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list, LayoutParams(-1, -2)) }
        panel.addView(sc, LK.lp(-1, -2))
        close = layer.sheet(panel, Color.argb(204, 0, 0, 0), 1f, slideMs = 500, ease = PathInterpolator(0.32f, 0.72f, 0f, 1f))
    }
}

/** StepIndicator: progress dots (the current one long and glowing) */
internal fun stepDots(ctx: Context, current: Int, total: Int): View = LK.row(ctx, Gravity.CENTER).apply {
    for (i in 1..total) {
        val v = View(ctx)
        v.background = when {
            i == current -> CssBox(AK.primary, 3f, shadows = listOf(Sh(0f, 0f, 10f, 0f, AK.p(0.8f))))
            i < current -> CssBox(AK.p(0.6f), 3f)
            else -> CssBox(AK.w(0.2f), 3f)
        }
        addView(v, LK.lp(Ui.dpi(if (i == current) 32f else 16f), Ui.dpi(6f), l = if (i == 1) 0f else 8f))
    }
}

/** a checkbox like the browser's (accent = the theme colour) */
@SuppressLint("ViewConstructor")
internal class ACheck(ctx: Context, checked: Boolean) : View(ctx) {
    var checked = checked; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = LK.lu(LK.I.Check, Color.WHITE, 12f, 3f)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.dpi(16f), Ui.dpi(16f))
    override fun onDraw(c: Canvas) {
        val s = width.toFloat(); val r = Ui.dp(2f)
        if (checked) {
            p.style = Paint.Style.FILL; p.color = AK.primary; c.drawRoundRect(0f, 0f, s, s, r, r, p)
            val m = Ui.dpi(2f); tick.setBounds(m, m, width - m, height - m); tick.draw(c)
        } else {
            p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawRoundRect(0f, 0f, s, s, r, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = Ui.dp(1f); p.color = Color.parseColor("#767676")
            c.drawRoundRect(Ui.dp(0.5f), Ui.dp(0.5f), s - Ui.dp(0.5f), s - Ui.dp(0.5f), r, r, p)
        }
    }
}

/** the destructive toast of the web (top of the page, 5 s) */
internal fun redToast(layer: LoginLayer, text: String) {
    val ctx = layer.context
    val box = LK.row(ctx).apply {
        background = CssBox(AK.destructive, AK.MD, 1f, AK.destructive, shadows = listOf(Sh(0f, 10f, 15f, -3f, Color.argb(26, 0, 0, 0)), Sh(0f, 4f, 6f, -4f, Color.argb(26, 0, 0, 0))))
        setPadding(Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(32f), Ui.dpi(24f))
        elevation = Ui.dp(40f)
    }
    box.addView(LK.t(ctx, text, 14f, LK.a(LK.hsl(0f, 0f, 98f), 0.9f), false, 20f / 14f), LK.lp(0, -2, weight = 1f))
    val lp = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), 0) }
    layer.addView(box, lp)
    box.translationY = -Ui.dp(140f)
    box.animate().translationY(0f).setDuration(300).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    layer.postDelayed({ box.animate().alpha(0f).translationX(Ui.dp(400f)).setDuration(200).withEndAction { layer.removeView(box) }.start() }, 5000)
}

internal fun ViewGroup.clearChildren() { removeAllViews() }
