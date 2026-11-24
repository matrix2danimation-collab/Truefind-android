package app.truefind.android.login

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.truefind.android.Compat
import app.truefind.android.TfLoader
import app.truefind.android.Ui
import org.json.JSONObject
import kotlin.math.sqrt

/** a plain text box for the Welcome screens: no line of its own (the row around it draws the box) */
@SuppressLint("ViewConstructor")
internal class WInput(ctx: Context, size: Float, hint: String, color: Int = LK.ink, hintColor: Int = LK.ph) : EditText(ctx) {
    var onText: ((String) -> Unit)? = null
    private var setting = false
    init {
        background = null
        setPadding(0, 0, 0, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(size))
        typeface = Ui.regular
        setTextColor(color); setHintTextColor(hintColor)
        this.hint = hint
        includeFontPadding = false
        isSingleLine = true; maxLines = 1
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!setting) onText?.invoke(s?.toString().orEmpty()) }
        })
    }
    /** sets the text (keeping the cursor at the end) without telling [onText] */
    fun put(v: String) { if (text?.toString() == v) return; setting = true; setText(v); setSelection(v.length); setting = false }
    val value: String get() = text?.toString().orEmpty()
}

/** BootScreen: the soft white-to-lilac page with the big blue spinner. */
internal class BootStep(ctx: Context) : WelcomeStep(ctx) {
    init {
        background = CssBox(gradient = intArrayOf(Color.WHITE, LK.c("#FCF9FE"), LK.c("#F7EFFC")))
        addView(TfLoader(ctx, 34f), LayoutParams(Ui.dpi(34f), Ui.dpi(34f), Gravity.CENTER))
    }
}

// ═══════════════════════════════════ 2. LOGIN — mobile number only (OTP) ═══════════════════════════════════
@SuppressLint("ViewConstructor")
internal class PhoneStep(ctx: Context, private val page: WelcomePage) : WelcomeStep(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private var error = ""
    private var busy = false
    private val box = LK.row(ctx)
    private val input = WInput(ctx, 17f, "Mobile Number").apply {
        letterSpacing = 0.6f / 17f
        inputType = InputType.TYPE_CLASS_PHONE
        imeOptions = EditorInfo.IME_ACTION_GO
        Compat.autofill(this, "phone")
        Compat.autofillYes(this)
    }
    private val ok = FrameLayout(ctx).apply {
        background = CssBox(LK.green, 11f)
        addView(ImageView(ctx).apply { setImageDrawable(WK.ico(WK.CHECK, Color.WHITE, 13f)) }, LayoutParams(Ui.dpi(13f), Ui.dpi(13f), Gravity.CENTER))
    }
    private val errV = WK.err(ctx)
    private val noteV = WK.err(ctx)
    private val cta = Cta(ctx, "Login with OTP")

    init {
        val sheet = WK.sheet(ctx)
        sheet.addView(WK.label(ctx, "Mobile number"))
        box.setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
        box.addView(WK.artView(ctx, WK.FLAG, 24f), LK.lp(Ui.dpi(24f), Ui.dpi(24f), r = 10f))
        box.addView(LK.t(ctx, "+91", 16f, LK.ink, true), LK.lp(r = 10f))
        box.addView(View(ctx).apply { setBackgroundColor(LK.line) }, LK.lp(Ui.dpi(1f), Ui.dpi(22f), r = 10f))
        box.addView(input, LK.lp(0, -1, weight = 1f))
        box.addView(ok, LK.lp(Ui.dpi(22f), Ui.dpi(22f), l = 10f))
        sheet.addView(box, LK.lp(-1, Ui.dpi(56f), t = 8f))
        sheet.addView(errV, WK.errLp())
        sheet.addView(noteV, WK.errLp())
        sheet.addView(WK.iconLine(ctx, WK.INFO, "We'll send a 6-digit OTP to verify your number.", 12.5f, false), LK.lp(t = 10f))
        sheet.addView(cta, LK.lp(-1, -2, t = 22f))
        // trust lines
        val trust = LK.col(ctx).apply {
            background = CssBox(Color.WHITE).let { TopLine(LK.div) }
            setPadding(0, Ui.dpi(18f), 0, 0)
        }
        trust.addView(trustRow(ctx, WK.icoView(ctx, WK.LOCK, LK.purple, 15f), "Your number stays private", "It is never shown to other members"))
        trust.addView(trustRow(ctx, LK.t(ctx, "18+", 11f, LK.purple, true), "Only for adults", "True Find is only for people aged 18 and above"), LK.lp(-1, -2, t = 12f))
        sheet.addView(trust, LK.lp(-1, -2, t = 22f))
        sheet.addView(LK.grow(ctx, 16f))
        sheet.addView(WK.linked(ctx, 12f, LK.sub, LK.link, 1.45f,
            "By continuing I accept the " to null, "Community Guidelines" to { page.layer.openSite("/community-guidelines") }, ", " to null,
            "Terms of Use" to { page.layer.openSite("/terms") }, " & " to null, "Privacy Policy" to { page.layer.openSite("/privacy") }, "." to null))
        addView(WK.page(ctx, WK.head(ctx, 1, "Welcome Back", "Login with your mobile number", WK.ART_LOGIN, false) { page.goBack("login") }, sheet), LayoutParams(-1, -1))

        input.put(page.phone)
        input.onText = { v -> val c = WelcomePage.cleanPhone(v); if (c != v) input.put(c); page.phone = c; if (error.isNotEmpty()) error = ""; draw() }
        input.setOnFocusChangeListener { _, _ -> draw() }
        input.setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false }
        cta.setOnClickListener { submit() }
        box.setOnClickListener { LK.showKeyboard(input) }
        draw()
    }

    private fun trustRow(ctx: Context, icon: View, b: String, s: String): LinearLayout = LK.row(ctx).apply {
        val ic = FrameLayout(ctx).apply { background = CssBox(LK.tint, 16f); addView(icon, LayoutParams(-2, -2, Gravity.CENTER)) }
        addView(ic, LK.lp(Ui.dpi(32f), Ui.dpi(32f), r = 10f))
        val t = LK.col(ctx)
        t.addView(LK.t(ctx, b, 13f, LK.ink, true, 1.25f))
        t.addView(LK.t(ctx, s, 12f, LK.sub, false, 1.25f))
        addView(t, LK.lp(0, -2, weight = 1f))
    }

    private fun draw() {
        val valid = WelcomePage.validPhone(page.phone)
        ok.visibility = if (valid) View.VISIBLE else View.GONE
        val focused = input.hasFocus()
        box.background = CssBox(Color.WHITE, 16f, 1.5f, if (error.isNotEmpty()) LK.bad else if (focused) LK.purple else LK.line,
            shadows = if (focused) listOf(Sh(0f, 0f, 0f, 4f, LK.ring)) else emptyList())
        errV.text = error; errV.visibility = if (error.isNotEmpty()) View.VISIBLE else View.GONE
        val note = page.loginNote
        noteV.text = note; noteV.visibility = if (note.isNotEmpty() && error.isEmpty()) View.VISIBLE else View.GONE
        cta.busy = busy
    }

    private fun submit() {
        if (busy) return
        if (!WelcomePage.validPhone(page.phone)) { error = "Please enter a valid 10-digit mobile number."; draw(); return }
        error = ""
        page.loginNote = ""
        busy = true; draw()
        page.onSent { err -> busy = false; if (err.isNotEmpty()) error = err; draw() }
    }

    private val focusLater = Runnable { LK.autoFocus(input); input.setSelection(input.value.length) }
    override fun setLive(v: Boolean) { main.removeCallbacks(focusLater); if (v) main.postDelayed(focusLater, 480) else LK.hideKeyboard(input) }
    override fun refresh() = draw()
    override fun dispose() { main.removeCallbacksAndMessages(null) }
    override fun preview(variant: String, open: String) {
        when {
            variant.contains("error") -> { page.phone = "12345"; input.put("12345"); submit() }
            variant.contains("filled") -> { page.phone = "9876543210"; input.put("9876543210"); draw() }
            variant.contains("note") -> { page.loginNote = WelcomePage.directLoginMessage("session_not_created"); draw() }
        }
        if (open == "rules") InfoSheet.open(page.layer, "rules")
        if (open == "privacy") InfoSheet.open(page.layer, "privacy")
    }
}

/** a 1 px line along the top (border-top) */
internal class TopLine(private val color: Int) : android.graphics.drawable.Drawable() {
    private val p = Paint().apply { this.color = this@TopLine.color }
    override fun draw(c: Canvas) { val b = bounds; c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + Ui.dp(1f), p) }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

// ═══════════════════════════════════ 3. VERIFY OTP ═══════════════════════════════════
@SuppressLint("ViewConstructor")
internal class OtpStep(ctx: Context, private val page: WelcomePage) : WelcomeStep(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private var code = ""
    private var error = ""
    private var busy = false
    private var passed = false
    private var live = false
    private val boxes = OtpBoxes(ctx, WelcomePage.OTP_LENGTH, 56f, 14f, 9f, 22f)
    private val hidden = WInput(ctx, 16f, "").apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setTextColor(Color.TRANSPARENT); setHintTextColor(Color.TRANSPARENT)
        isCursorVisible = false
        Compat.autofill(this, "smsOTPCode")
        alpha = 0.01f
        contentDescription = "6-digit OTP"
    }
    private val errV = WK.err(ctx)
    private val ownerV = LK.col(ctx)
    private val cta = Cta(ctx, "Verify OTP")
    private val resend = LK.row(ctx, Gravity.CENTER)
    private val demoBox = LK.row(ctx)
    private val sheet = WK.sheet(ctx)

    init {
        sheet.addView(LK.t(ctx, "Enter the ${WelcomePage.OTP_LENGTH}-digit OTP sent on WhatsApp to", 13.5f, LK.sub))
        val p = page.phone
        val shown = if (p.length == 10) "+91 ${p.substring(0, 5)} ${p.substring(5)}" else "+91 $p"
        val num = LK.row(ctx, Gravity.BOTTOM)
        num.addView(LK.t(ctx, shown, 17f, LK.ink, true))
        num.addView(WK.link(ctx, "Change") { page.goBack("verify") }.apply { setPadding(0, 0, 0, 0) }, LK.lp(l = 10f))
        sheet.addView(num, LK.lp(t = 4f))
        val otp = FrameLayout(ctx).apply { clipChildren = false }
        otp.addView(boxes, LayoutParams(-1, Ui.dpi(56f)))
        otp.addView(hidden, LayoutParams(-1, -1))
        sheet.addView(otp, LK.lp(-1, Ui.dpi(56f), t = 18f))
        sheet.addView(errV, WK.errLp())
        sheet.addView(ownerV, LK.lp(-1, -2, t = 7f, l = 2f, r = 2f))
        sheet.addView(cta, LK.lp(-1, -2, t = 22f))
        resend.minimumHeight = Ui.dpi(26f)
        sheet.addView(resend, LK.lp(-1, -2, t = 16f))
        sheet.addView(demoBox, LK.lp(-1, Ui.dpi(48f), t = 20f))
        sheet.addView(LK.grow(ctx, 16f))
        sheet.addView(WK.iconLine(ctx, WK.LOCK, "We never share your number with anyone.", 12f, true), LK.lp(-1, -2))
        addView(WK.page(ctx, WK.head(ctx, 2, "Verify Your Number", "One quick check to keep every profile real", WK.ART_VERIFY, false) { page.verifyNote = ""; page.goBack("verify") }, sheet), LayoutParams(-1, -1))

        hidden.onText = { v -> onChange(v) }
        hidden.setOnFocusChangeListener { _, _ -> draw() }
        otp.setOnClickListener { LK.showKeyboard(hidden) }
        boxes.setOnClickListener { LK.showKeyboard(hidden) }
        cta.setOnClickListener { verify(code) }
        draw()
    }

    private fun left(): Int = maxOf(0, WelcomePage.RESEND_SECONDS - ((SystemClock.elapsedRealtime() - page.sentAt) / 1000L).toInt())

    private val tick = object : Runnable { override fun run() { drawResend(); if (live && left() > 0) main.postDelayed(this, 1000) } }

    private fun onChange(v: String) {
        val digits = WelcomePage.onlyDigits(v).take(WelcomePage.OTP_LENGTH)
        if (digits != v) hidden.put(digits)
        code = digits
        if (error.isNotEmpty()) error = ""
        draw()
        if (digits.length == WelcomePage.OTP_LENGTH) verify(digits)
    }

    private fun verify(value: String) {
        if (busy) return
        if (value.length != WelcomePage.OTP_LENGTH) { error = "Please enter the ${WelcomePage.OTP_LENGTH}-digit OTP."; draw(); return }
        busy = true; error = ""; draw()
        val after = {
            passed = true
            // (the right code made the sign-in; the next step could not open — no internet …: said here, Verify tries again)
            page.afterVerify { busy = false; if (page.verifyNote.isNotEmpty()) { error = page.verifyNote; page.verifyNote = "" }; draw() }
        }
        if (passed) { after(); return }
        page.checkOtp(value) { err ->
            when {
                err == null -> { /* a sign-in link is opening in the web page: stays busy */ }
                err.isNotEmpty() -> {
                    busy = false; error = err; code = ""; hidden.put(""); draw()
                    main.postDelayed({ LK.showKeyboard(hidden) }, 30)
                }
                else -> after()
            }
        }
    }

    private fun doResend() {
        if (left() > 0 || busy) return
        busy = true; code = ""; hidden.put(""); error = ""; draw()
        page.sendOtp { err ->
            passed = false; busy = false
            if (err.isNotEmpty()) error = err
            draw(); main.removeCallbacks(tick); main.post(tick)
            LK.showKeyboard(hidden)
        }
    }

    private fun drawResend() {
        resend.removeAllViews()
        resend.addView(WK.icoView(context, WK.CLOCK, LK.sub, 13f), LK.lp(Ui.dpi(13f), Ui.dpi(13f), r = 6f))
        val l = left()
        if (l > 0) resend.addView(WK.linked(context, 13f, LK.sub, LK.ink, 0f, "Didn't get the code? Resend in " to null, "00:${WelcomePage.pad2(l)}" to {}).apply { movementMethod = null })
        else {
            resend.addView(LK.t(context, "Didn't get the code? ", 13f, LK.sub))
            resend.addView(WK.link(context, "Resend OTP", 13f) { doResend() }.apply { setPadding(0, Ui.dpi(4f), 0, Ui.dpi(4f)) })
        }
    }

    private fun draw() {
        boxes.set(code, hidden.hasFocus() && !busy, error.isNotEmpty())
        hidden.isEnabled = !busy
        errV.text = error; errV.visibility = if (error.isNotEmpty()) View.VISIBLE else View.GONE
        ownerV.visibility = View.GONE
        cta.busy = busy
        drawResend()
        // DEMO box: the code shown on screen when the server is in demo mode
        demoBox.removeAllViews()
        val demo = page.demoOtp
        demoBox.visibility = if (demo.isNotEmpty()) View.VISIBLE else View.GONE
        if (demo.isNotEmpty()) {
            demoBox.background = CssBox(LK.c("#FFFBEB"), 14f, 1f, LK.c("#D9B84A"), dashed = true)
            demoBox.setPadding(Ui.dpi(10f), 0, Ui.dpi(8f), 0)
            demoBox.addView(LK.t(context, "DEMO", 10.5f, LK.c("#5B4300"), true, ls = 0.8f).apply { background = CssBox(LK.c("#F8D66B"), 6f); setPadding(Ui.dpi(7f), Ui.dpi(3f), Ui.dpi(7f), Ui.dpi(3f)) })
            demoBox.addView(LK.t(context, "OTP", 13f, LK.c("#6B5A1E")), LK.lp(l = 10f))
            demoBox.addView(LK.t(context, demo, 15f, LK.ink, true, ls = 2f), LK.lp(l = 10f))
            demoBox.addView(View(context), LK.lp(0, 1, weight = 1f))
            val fill = LK.t(context, "Auto-fill", 12.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = CssBox(LK.ink, 10f); setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0) }
            LK.click(fill, 0.97f) { code = demo; hidden.put(demo); error = ""; draw(); verify(demo) }
            demoBox.addView(fill, LK.lp(-2, Ui.dpi(32f)))
        }
    }

    private val focusLater = Runnable { LK.autoFocus(hidden) }
    override fun setLive(v: Boolean) {
        live = v
        main.removeCallbacks(focusLater); main.removeCallbacks(tick)
        if (v) { main.postDelayed(focusLater, 480); main.post(tick) } else LK.hideKeyboard(hidden)
    }
    override fun refresh() = draw()
    override fun dispose() { live = false; main.removeCallbacksAndMessages(null); boxes.stop() }
    override fun preview(variant: String, open: String) {
        when {
            variant.contains("error") -> { code = ""; error = "Invalid or expired OTP."; draw() }
            variant.contains("typing") -> { code = "482"; hidden.put("482"); draw() }
            variant.contains("resend") -> { page.sentAt = SystemClock.elapsedRealtime() - (WelcomePage.RESEND_SECONDS - 20) * 1000L; draw() }
        }
    }
}

/** The OTP boxes (.tfo-box): filled, active (purple ring + blinking caret) and bad (red) states. */
@SuppressLint("ViewConstructor")
internal class OtpBoxes(ctx: Context, private val n: Int, private val hDp: Float, private val rDp: Float, private val gapDp: Float, private val sizeDp: Float) : View(ctx) {
    private var code = ""
    private var focused = false
    private var bad = false
    private var caretOn = true
    private val main = Handler(Looper.getMainLooper())
    private val blink = object : Runnable { override fun run() { caretOn = !caretOn; invalidate(); main.postDelayed(this, 500) } }
    private val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = Ui.dp(sizeDp); color = LK.ink; textAlign = Paint.Align.CENTER }
    private val fills = HashMap<String, CssBox>()
    init { isClickable = true }
    fun set(code: String, focused: Boolean, bad: Boolean) {
        this.code = code; this.focused = focused; this.bad = bad
        main.removeCallbacks(blink)
        if (focused) { caretOn = true; main.postDelayed(blink, 500) }
        invalidate()
    }
    fun stop() { main.removeCallbacks(blink) }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    private fun box(kind: String): CssBox = fills.getOrPut(kind) {
        when (kind) {
            "active" -> CssBox(Color.WHITE, rDp, 1.5f, if (bad) LK.bad else LK.purple, shadows = listOf(Sh(0f, 0f, 0f, 4f, LK.ring)))
            "filled" -> CssBox(LK.filledBg, rDp, 1.5f, LK.filledLine)
            "activeBad" -> CssBox(Color.WHITE, rDp, 1.5f, LK.bad, shadows = listOf(Sh(0f, 0f, 0f, 4f, LK.ring)))
            "filledBad" -> CssBox(LK.filledBg, rDp, 1.5f, LK.bad)
            "bad" -> CssBox(Color.WHITE, rDp, 1.5f, LK.bad)
            else -> CssBox(Color.WHITE, rDp, 1.5f, LK.line)
        }
    }
    override fun onDraw(c: Canvas) {
        val gap = Ui.dp(gapDp)
        val w = (width - gap * (n - 1)) / n
        val h = height.toFloat()
        for (i in 0 until n) {
            val l = i * (w + gap)
            val ch = code.getOrNull(i)?.toString() ?: ""
            val active = focused && (i == code.length || (i == n - 1 && code.length == n))
            val kind = when {
                active -> if (bad) "activeBad" else "active"
                ch.isNotEmpty() -> if (bad) "filledBad" else "filled"
                bad -> "bad"
                else -> "idle"
            }
            val d = box(kind)
            d.setBounds(l.toInt(), 0, (l + w).toInt(), h.toInt())
            d.draw(c)
            if (ch.isNotEmpty()) {
                val fm = tp.fontMetrics
                c.drawText(ch, l + w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, tp)
            } else if (active && caretOn) {
                val p = Paint().apply { color = LK.purple; isAntiAlias = true }
                c.drawRoundRect(l + w / 2f - Ui.dp(1f), h / 2f - Ui.dp(11f), l + w / 2f + Ui.dp(1f), h / 2f + Ui.dp(11f), Ui.dp(2f), Ui.dp(2f), p)
            }
        }
    }
}

// ═══════════════════════════════════ 4. SELECT GENDER ═══════════════════════════════════
@SuppressLint("ViewConstructor")
internal class GenderStep(ctx: Context, private val page: WelcomePage) : WelcomeStep(ctx) {
    private val cards = HashMap<String, GenderCard>()
    private val cta = Cta(ctx, "Continue")
    init {
        val sheet = WK.sheet(ctx)
        sheet.addView(WK.label(ctx, "I am"))
        val row = LK.row(ctx)
        for ((v, label) in listOf("male" to "Male", "female" to "Female")) {
            val card = GenderCard(ctx, v, label)
            cards[v] = card
            LK.click(card, 0.98f) { page.gender = if (page.gender == v) "" else v; draw(true) }
            row.addView(card, LK.lp(0, Ui.dpi(186f), weight = 1f, r = if (v == "male") 12f else 0f))
        }
        sheet.addView(row, LK.lp(-1, -2, t = 10f))
        sheet.addView(WK.iconLine(ctx, WK.INFO, "You can't change your gender later.", 12.5f, false), LK.lp(t = 10f))
        cta.setOnClickListener { page.go("profile") }
        sheet.addView(cta, LK.lp(-1, -2, t = 22f))
        val why = LK.col(ctx).apply { background = CssBox(LK.tint, 14f); setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f)) }
        why.addView(LK.t(ctx, "Why we ask", 12.5f, LK.ink, true, 1.4f))
        why.addView(LK.t(ctx, "We use it to show you the right people for calls and matches.", 12.5f, LK.sub, false, 1.4f), LK.lp(t = 2f))
        sheet.addView(why, LK.lp(-1, -2, t = 18f))
        sheet.addView(LK.grow(ctx, 16f))
        sheet.addView(WK.iconLine(ctx, WK.LOCK, "Your details are private and safe with us.", 12f, true), LK.lp(-1, -2))
        addView(WK.page(ctx, WK.head(ctx, 3, "Select Your Gender", "This helps us personalize your experience", WK.ART_GENDER, false) { page.goBack("gender") }, sheet), LayoutParams(-1, -1))
        draw(false)
    }
    private fun draw(animate: Boolean) {
        for ((v, c) in cards) c.setOn(page.gender == v, animate)
        cta.off = page.gender.isEmpty()
    }
    override fun preview(variant: String, open: String) {
        if (variant.contains("female")) { page.gender = "female"; draw(true) } else if (variant.contains("male")) { page.gender = "male"; draw(true) }
    }
}

/** A gender card: picked → purple border and lilac, and the chat doodles bloom out from the circle. */
@SuppressLint("ViewConstructor")
internal class GenderCard(ctx: Context, kind: String, label: String) : FrameLayout(ctx) {
    private var on = false
    private val dood = Bloom(ctx, kind)
    private val mark = FrameLayout(ctx)
    private val tick = ImageView(ctx).apply { setImageDrawable(WK.ico(WK.CHECK, Color.WHITE, 13f)) }
    init {
        clipChildren = true
        addView(dood, LayoutParams(-1, -1))
        val col = LK.col(ctx, Gravity.CENTER)
        val sil = if (kind == "male") SilDrawable(LK.c("#DBEAFE"), LK.c("#2563EB")) else SilDrawable(LK.c("#FCE7F3"), LK.c("#DB2777"))
        col.addView(ImageView(ctx).apply { setImageDrawable(sil) }, LK.lp(Ui.dpi(84f), Ui.dpi(84f)))
        col.addView(LK.t(ctx, label, 16f, LK.ink, true), LK.lp(t = 14f))
        addView(col, LayoutParams(-1, -1))
        mark.addView(tick, LayoutParams(Ui.dpi(13f), Ui.dpi(13f), Gravity.CENTER))
        addView(mark, LK.flp(Ui.dpi(22f), Ui.dpi(22f), Gravity.TOP or Gravity.END, t = 12f, r = 12f))
        isClickable = true
        setOn(false, false)
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, Ui.dp(20f)) } }
        clipToOutline = true
    }
    fun setOn(v: Boolean, animate: Boolean) {
        on = v
        background = if (v) CssBox(LK.tint, 20f, 2f, LK.purple) else CssBox(Color.WHITE, 20f, 1.5f, LK.line)
        mark.background = if (v) CssBox(LK.purple, 11f, 1.5f, LK.purple) else CssBox(Color.TRANSPARENT, 11f, 1.5f, LK.c("#CFC5E2"))
        tick.visibility = if (v) View.VISIBLE else View.INVISIBLE
        dood.set(v, animate)
    }
}

/** The doodles of a picked gender card: a circle at 50 % / 41 % that grows from 18 % to 80 % (clip-path), fading to .62. */
@SuppressLint("ViewConstructor")
internal class Bloom(ctx: Context, private val kind: String) : View(ctx) {
    private var bmp: Bitmap? = null
    private var k = 0f        // 0 = folded, 1 = open
    private var fade = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    init { WK.doodle(ctx, kind) { b -> bmp = b; invalidate() } }
    fun set(on: Boolean, animate: Boolean) {
        anim?.cancel()
        val target = if (on) 1f else 0f
        if (!animate) { k = target; fade = target; invalidate(); return }
        val from = k; val fromF = fade
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (on) 380 else 220
            interpolator = if (on) PathInterpolator(0.2f, 0.8f, 0.2f, 1f) else PathInterpolator(0.42f, 0f, 1f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                k = from + (target - from) * t
                val ft = minOf(1f, t * (if (on) 380f / 200f else 220f / 200f))
                fade = fromF + (target - fromF) * ft
                invalidate()
            }
            start()
        }
    }
    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        if (fade <= 0.001f) return
        val w = width.toFloat(); val h = height.toFloat()
        // object-fit: cover, centred
        val s = maxOf(w / b.width, h / b.height)
        val dw = b.width * s; val dh = b.height * s
        val scale = 0.92f + 0.08f * k
        val ref = sqrt(w * w + h * h) / sqrt(2f)
        val r = ref * (0.18f + (0.80f - 0.18f) * k)
        c.save()
        clip.reset(); clip.addCircle(w * 0.5f, h * 0.41f, r, Path.Direction.CW); c.clipPath(clip)
        c.scale(scale, scale, w / 2f, h / 2f)
        p.alpha = (0.62f * fade * 255).toInt().coerceIn(0, 255)
        c.drawBitmap(b, null, android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p)
        c.restore()
    }
}

// ═══════════════════════════════════ 5. COMPLETE PROFILE ═══════════════════════════════════
@SuppressLint("ViewConstructor")
internal class ProfileStep(ctx: Context, private val page: WelcomePage) : WelcomeStep(ctx) {
    private val touched = HashSet<String>()
    private val nameIn = WInput(ctx, 16f, "Your name").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        filters = arrayOf(InputFilter.LengthFilter(40))
        Compat.autofill(this, "name")
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }
    private val dobIn = WInput(ctx, 16f, "DD/MM/YYYY").apply { inputType = InputType.TYPE_CLASS_NUMBER; imeOptions = EditorInfo.IME_ACTION_DONE }
    private val nameRow = field(ctx, WK.PERSON, 16f, 17f)
    private val dobRow = field(ctx, WK.CALENDAR, 16f, 17f)
    private val stateRow = field(ctx, WK.PIN, 16f, 17f)
    private val cityRow = field(ctx, WK.PIN, 16f, 17f)
    private val ageV = LK.t(ctx, "", 11.5f, LK.purple, true).apply { background = CssBox(LK.tint, 11f); setPadding(Ui.dpi(9f), Ui.dpi(4f), Ui.dpi(9f), Ui.dpi(4f)) }
    private val stateV = LK.one(LK.t(ctx, "", 16f, LK.ink))
    private val cityV = LK.one(LK.t(ctx, "", 16f, LK.ink))
    private val stateChev = WK.icoView(ctx, WK.CHEVRON, LK.sub, 13f, 8f)
    private val cityChev = WK.icoView(ctx, WK.CHEVRON, LK.sub, 13f, 8f)
    private val nameErr = WK.err(ctx); private val dobErr = WK.err(ctx); private val stateErr = WK.err(ctx); private val cityErr = WK.err(ctx)
    private val alert = LK.row(ctx, Gravity.TOP)
    private val cta = Cta(ctx, "Save & Continue")
    private val avatar = ImageView(ctx)

    private fun field(ctx: Context, icon: String, iw: Float, ih: Float): LinearLayout = LK.row(ctx).apply {
        setPadding(Ui.dpi(12f), 0, Ui.dpi(14f), 0)
        val ic = FrameLayout(ctx).apply { background = CssBox(LK.tint, 17f); addView(ImageView(ctx).apply { setImageDrawable(WK.ico(icon, LK.purple, iw, ih)) }, LayoutParams(Ui.dpi(iw), Ui.dpi(ih), Gravity.CENTER)) }
        addView(ic, LK.lp(Ui.dpi(34f), Ui.dpi(34f), r = 12f))
    }
    private fun body(ctx: Context, label: String, v: View): LinearLayout = LK.col(ctx).apply {
        addView(LK.t(ctx, label, 11f, LK.sub))
        addView(v, LK.lp(-1, -2, t = 1f))
    }

    init {
        val sheet = WK.sheet(ctx, 22f)
        sheet.addView(WK.label(ctx, "Your details"))
        // name
        nameRow.addView(body(ctx, "Full name", nameIn), LK.lp(0, -2, weight = 1f))
        // date of birth
        dobRow.addView(body(ctx, "Date of birth", dobIn), LK.lp(0, -2, weight = 1f))
        dobRow.addView(ageV, LK.lp(l = 12f))
        // state / city (pickers)
        stateRow.addView(body(ctx, "State", stateV), LK.lp(0, -2, weight = 1f))
        stateRow.addView(stateChev, LK.lp(Ui.dpi(13f), Ui.dpi(8f), l = 12f))
        cityRow.addView(body(ctx, "City", cityV), LK.lp(0, -2, weight = 1f))
        cityRow.addView(cityChev, LK.lp(Ui.dpi(13f), Ui.dpi(8f), l = 12f))
        val fields = LK.col(ctx)
        fields.addView(nameRow, LK.lp(-1, Ui.dpi(58f))); fields.addView(nameErr, WK.errLp())
        fields.addView(dobRow, LK.lp(-1, Ui.dpi(58f), t = 10f)); fields.addView(dobErr, WK.errLp())
        fields.addView(stateRow, LK.lp(-1, Ui.dpi(58f), t = 10f)); fields.addView(stateErr, WK.errLp())
        fields.addView(cityRow, LK.lp(-1, Ui.dpi(58f), t = 10f)); fields.addView(cityErr, WK.errLp())
        sheet.addView(fields, LK.lp(-1, -2, t = 12f))
        alert.setPadding(Ui.dpi(14f), Ui.dpi(11f), Ui.dpi(14f), Ui.dpi(11f))
        sheet.addView(alert, LK.lp(-1, -2, t = 12f))
        sheet.addView(cta, LK.lp(-1, -2, t = 14f))
        sheet.addView(WK.iconLine(ctx, WK.LOCK, "Your details are private and safe with us.", 12f, true), LK.lp(-1, -2, t = 12f))
        // the round photo spot overlapping the purple top (right 24, 46 up)
        val holder = FrameLayout(ctx).apply { clipChildren = false }
        holder.addView(sheet, LayoutParams(-1, -1))
        val av = FrameLayout(ctx).apply { background = CssBox(Color.WHITE, 46f, shadows = listOf(Sh(0f, 6f, 16f, 0f, LK.ca("#1F1235", 0.18f)))) }
        av.addView(avatar, LayoutParams(Ui.dpi(82f), Ui.dpi(82f), Gravity.CENTER))
        holder.addView(av, LK.flp(Ui.dpi(92f), Ui.dpi(92f), Gravity.TOP or Gravity.END, r = 24f).apply { topMargin = -Ui.dpi(46f) })
        addView(WK.page(ctx, WK.head(ctx, 4, "Complete Your Profile", "Add details to get better matches", null, true) { page.goBack("profile") }, holder), LayoutParams(-1, -1))

        nameIn.put(page.fName); dobIn.put(page.fDob)
        nameIn.onText = { v -> page.fName = v; draw() }
        nameIn.setOnFocusChangeListener { _, f -> if (!f) { val t = page.fName.replace(Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+"), " ").trim(); page.fName = t; nameIn.put(t); touched.add("name") }; draw() }
        dobIn.onText = { v -> val m = WelcomePage.maskDob(v); if (m != v) dobIn.put(m); page.fDob = m; draw() }
        dobIn.setOnFocusChangeListener { _, f -> if (!f) touched.add("dob"); draw() }
        dobIn.setOnEditorActionListener { _, _, _ -> LK.hideKeyboard(dobIn); false }
        nameRow.setOnClickListener { LK.showKeyboard(nameIn) }
        dobRow.setOnClickListener { LK.showKeyboard(dobIn) }
        LK.click(stateRow, 0.99f) { pickState() }
        LK.click(cityRow, 0.99f) { openCity() }
        cta.setOnClickListener { save() }
        draw()
    }

    private val cities: List<String> get() = Places.WELCOME[page.fState] ?: emptyList()

    private fun pickState() {
        LK.hideKeyboard(this)
        PickerSheet.open(page.layer, "Select State", "${Places.WELCOME.size} states & union territories", "Search state", Places.WELCOME.keys.toList(), page.fState) { s ->
            if (s != page.fState) page.fCity = ""
            page.fState = s; touched.add("state"); draw()
        }
    }
    private fun openCity() {
        LK.hideKeyboard(this)
        if (page.fState.isEmpty()) { touched.add("city"); draw(); pickState(); return }
        PickerSheet.open(page.layer, "Select City", "${cities.size} districts in ${page.fState}", "Search city / district", cities, page.fCity) { c ->
            page.fCity = c; touched.add("city"); draw()
        }
    }

    private fun draw() {
        val nameErrT = WelcomePage.checkName(page.fName)
        val dob = WelcomePage.checkDob(page.fDob)
        val showName = if (touched.contains("name")) nameErrT else ""
        val showDob = if ((touched.contains("dob") || page.fDob.length == 10) && !dob.ok) dob.error else ""
        val showState = if (touched.contains("state") && page.fState.isEmpty()) "Please select your state." else ""
        val showCity = if (touched.contains("city") && page.fCity.isEmpty()) (if (page.fState.isNotEmpty()) "Please select your city." else "Select your state first.") else ""
        fun look(row: View, bad: Boolean, focused: Boolean) {
            row.background = CssBox(Color.WHITE, 16f, 1.5f, if (bad) LK.bad else if (focused) LK.purple else LK.line, shadows = if (focused) listOf(Sh(0f, 0f, 0f, 4f, LK.ring)) else emptyList())
        }
        look(nameRow, showName.isNotEmpty(), nameIn.hasFocus()); look(dobRow, showDob.isNotEmpty(), dobIn.hasFocus())
        look(stateRow, showState.isNotEmpty(), false); look(cityRow, showCity.isNotEmpty(), false)
        for ((v, t) in listOf(nameErr to showName, dobErr to showDob, stateErr to showState, cityErr to showCity)) { v.text = t; v.visibility = if (t.isNotEmpty()) View.VISIBLE else View.GONE }
        ageV.text = "${dob.age} yrs"; ageV.visibility = if (dob.ok) View.VISIBLE else View.GONE
        stateV.text = page.fState.ifEmpty { "Select state" }; stateV.setTextColor(if (page.fState.isEmpty()) LK.ph else LK.ink)
        cityV.text = page.fCity.ifEmpty { "Select city" }; cityV.setTextColor(if (page.fCity.isEmpty()) LK.ph else LK.ink)
        cityChev.setImageDrawable(WK.ico(WK.CHEVRON, if (page.fState.isEmpty()) LK.c("#C7BED6") else LK.sub, 13f, 8f))
        val sil = if (page.gender == "female") SilDrawable(LK.c("#FCE7F3"), LK.c("#DB2777")) else SilDrawable(LK.c("#DBEAFE"), LK.c("#2563EB"))
        avatar.setImageDrawable(sil)
        // the message box
        alert.removeAllViews()
        if (page.profileMsg.isNotEmpty()) {
            val bad = page.profileMsgType == "error"
            alert.visibility = View.VISIBLE
            alert.background = CssBox(if (bad) LK.c("#FFF0F3") else LK.tint, 14f)
            alert.addView(WK.icoView(context, WK.INFO, if (bad) LK.err else LK.purple, 15f), LK.lp(Ui.dpi(15f), Ui.dpi(15f), t = 1.5f, r = 9f))
            alert.addView(LK.t(context, page.profileMsg, 13f, if (bad) LK.c("#A11A3D") else LK.ink, false, 1.4f), LK.lp(0, -2, weight = 1f))
        } else alert.visibility = View.GONE
        cta.busy = page.profileBusy
    }

    private fun save() {
        touched.addAll(listOf("name", "dob", "state", "city"))
        page.profileMsg = ""; page.profileMsgType = ""
        val dob = WelcomePage.checkDob(page.fDob)
        val ready = WelcomePage.checkName(page.fName).isEmpty() && dob.ok && page.fState.isNotEmpty() && page.fCity.isNotEmpty()
        draw()
        if (!ready || page.profileBusy) return
        if (page.gender != "male" && page.gender != "female") { page.go("gender", -1); return }
        LK.hideKeyboard(this)
        val payload = JSONObject().put("full_name", page.fName.trim().replace(Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+"), " ")).put("date_of_birth", dob.iso)
            .put("gender", page.gender).put("state", page.fState).put("city", page.fCity).put("phone", page.phone)
        page.saveFromForm(payload)
    }

    override fun refresh() = draw()
    override fun preview(variant: String, open: String) {
        when {
            variant.contains("filled") -> { page.fName = "Aarav Sharma"; page.fDob = "14/08/1999"; page.fState = "Haryana"; page.fCity = "Rohtak"; nameIn.put(page.fName); dobIn.put(page.fDob); draw() }
            variant.contains("errors") -> { page.fName = "A"; page.fDob = "31/02/2010"; nameIn.put("A"); dobIn.put(page.fDob); save() }
            variant.contains("fail") -> { page.fName = "Aarav Sharma"; page.fDob = "14/08/1999"; page.fState = "Haryana"; page.fCity = "Rohtak"; nameIn.put(page.fName); dobIn.put(page.fDob); page.profileMsgType = "error"; page.profileMsg = "Could not save your profile. Please try again."; draw() }
        }
        if (open == "state") pickState()
        if (open == "city") { page.fState = "Haryana"; draw(); openCity() }
    }
}

// ═══════════════════════════════════ the pickers and the rules sheet ═══════════════════════════════════
internal object PickerSheet {
    /** PickerSheet of Welcome.jsx: title, count, search box, the list (picked one ticked) */
    fun open(layer: LoginLayer, title: String, subtitle: String, placeholder: String, options: List<String>, value: String, onPick: (String) -> Unit) {
        val ctx = layer.context
        var close: () -> Unit = {}
        val panel = SheetPanel(ctx, title, subtitle) { close() }
        val search = LK.row(ctx).apply {
            background = CssBox(LK.c("#F7F3FB"), 999f, 1f, LK.c("#EDE6F5"))
            setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
        }
        search.addView(WK.icoView(ctx, WK.SEARCH, LK.ph, 17f), LK.lp(Ui.dpi(17f), Ui.dpi(17f), r = 10f))
        val q = WInput(ctx, 14f, placeholder, LK.optInk, LK.ph).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; contentDescription = placeholder }
        search.addView(q, LK.lp(0, -1, weight = 1f))
        panel.top.addView(search, LK.lp(-1, Ui.dpi(42f), t = 12f, b = 6f))
        fun fill(text: String) {
            panel.list.removeAllViews()
            val s = text.trim().lowercase()
            val list = if (s.isEmpty()) options else options.filter { it.lowercase().contains(s) }
            for (o in list) {
                val on = o == value
                val row = LK.row(ctx).apply {
                    setPadding(Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f))
                    background = if (on) CssBox(LK.c("#F3EAFE")) else BottomLine(LK.c("#F3EEF8"))
                }
                row.addView(LK.t(ctx, o, 13.5f, if (on) LK.sheetTitle else LK.optInk, on), LK.lp(0, -2, weight = 1f))
                if (on) row.addView(WK.icoView(ctx, WK.CHECK, LK.purpleDeep, 17f), LK.lp(Ui.dpi(17f), Ui.dpi(17f), l = 10f))
                row.isClickable = true
                row.setOnClickListener { onPick(o); close() }
                panel.list.addView(row, LK.lp(-1, -2, l = 8f, r = 8f))
            }
            if (list.isEmpty()) panel.list.addView(LK.t(ctx, "No match found", 12.5f, LK.ph).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(26f), 0, Ui.dpi(26f)) }, LK.lp(-1, -2))
            panel.list.setPadding(0, 0, 0, Ui.dpi(6f))
        }
        fill("")
        q.onText = { fill(it) }
        close = layer.sheet(panel)
    }
}

/** a 1 px line along the bottom (border-bottom) */
internal class BottomLine(private val color: Int) : android.graphics.drawable.Drawable() {
    private val p = Paint().apply { this.color = this@BottomLine.color }
    override fun draw(c: Canvas) { val b = bounds; c.drawRect(b.left.toFloat(), b.bottom - Ui.dp(1f), b.right.toFloat(), b.bottom.toFloat(), p) }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Community Guidelines / Terms of Use / Privacy Policy → the rules every member agrees to (lib/safetyRules.js) */
internal object InfoSheet {
    private val SAFETY = listOf(
        LK.I.Smartphone to "Phone verified by OTP when you sign up",
        LK.I.BadgeCheck to "Identity, photo and voice verification badges on profiles",
        LK.I.Ban to "Block anyone instantly from any profile or chat",
        LK.I.Flag to "Report a profile and moderators review it",
        LK.I.Eye to "You control whether your city shows on the map",
        LK.I.Lock to "Your phone number, email and documents are never shown to others",
        LK.I.Wallet to "All payments stay inside the app's secure wallet",
    )
    private val RULES = listOf(
        LK.I.Users to "18+ only. One real account per person",
        LK.I.HeartHandshake to "Be respectful — harassment, abuse or hate speech gets banned",
        LK.I.TriangleAlert to "No nudity or explicit content in photos or calls",
        LK.I.Wallet to "Never ask another member for money, OTPs or bank details",
        LK.I.Ban to "No fake profiles, impersonation or spam",
        LK.I.Flag to "Break the rules and your account can be suspended",
    )

    fun open(layer: LoginLayer, first: String, onClose: () -> Unit = {}) {
        val ctx = layer.context
        var close: () -> Unit = {}
        val panel = SheetPanel(ctx, "Rules & Privacy", "Every member agrees to these when joining True Find") { close() }
        val parts = if (first == "privacy") listOf("Your privacy & safety" to SAFETY, "Community guidelines" to RULES) else listOf("Community guidelines" to RULES, "Your privacy & safety" to SAFETY)
        for ((title, items) in parts) {
            panel.list.addView(LK.t(ctx, title.uppercase(), 11.5f, LK.purpleDeep, true, ls = 0.69f), LK.lp(-1, -2, t = 14f, b = 8f, l = 10f, r = 10f))
            for ((i, item) in items.withIndex()) {
                val row = LK.row(ctx, Gravity.TOP)
                row.addView(LK.icon(ctx, LK.lu(item.first, LK.purpleDeep, 16f), 16f), LK.lp(Ui.dpi(16f), Ui.dpi(16f), t = 1f, r = 10f))
                row.addView(LK.t(ctx, item.second, 13f, LK.optInk, false, 1.4f), LK.lp(0, -2, weight = 1f))
                panel.list.addView(row, LK.lp(-1, -2, t = if (i == 0) 0f else 10f, l = 10f, r = 10f))
            }
        }
        // the full documents: the public pages on the website (web pages/Legal.jsx), in the phone's browser
        val full = LK.t(ctx, "Read the full Terms of Use, Privacy Policy, Refund Policy and Child Safety Standards", 12.5f, LK.purpleDeep, true, 1.4f)
        full.setOnClickListener {
            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(app.truefind.android.Api.origin.trimEnd('/') + "/legal"))) } catch (e: Exception) { }
        }
        panel.list.addView(full, LK.lp(-1, -2, t = 16f, l = 10f, r = 10f))
        panel.list.setPadding(0, 0, 0, Ui.dpi(6f))
        close = layer.sheet(panel, onClose = onClose)
    }
}
