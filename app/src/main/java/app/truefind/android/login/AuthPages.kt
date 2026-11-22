package app.truefind.android.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.truefind.android.Compat
import app.truefind.android.Api
import app.truefind.android.Ui
import org.json.JSONObject

internal object AuthPages {
    private val STRIP = setOf("access_token", "clear_access_token", "app_id", "app_base_url", "functions_version", "from_url")

    /** lib/authReturnTo.js safeReturnTo: ?returnTo= as a safe same-site path, else "/" */
    fun safeReturnTo(search: String): String {
        val raw = try { Uri.parse("x://x/$search").getQueryParameter("returnTo") } catch (e: Exception) { null }
        if (raw.isNullOrEmpty()) return "/"
        return try {
            val origin = Uri.parse(Api.origin)
            val u = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> Uri.parse(raw)
                raw.startsWith("//") -> Uri.parse("${origin.scheme}:$raw")
                raw.startsWith("/") -> Uri.parse(Api.origin + raw)
                else -> Uri.parse(Api.origin + "/" + raw)
            }
            if (u.scheme != origin.scheme || u.authority != origin.authority || raw.contains("\\")) return "/"
            val q = if (u.queryParameterNames.any { it in STRIP }) {
                val b = Uri.Builder()
                for (n in u.queryParameterNames) if (n !in STRIP) for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
                b.build().encodedQuery
            } else u.encodedQuery
            val path = (u.encodedPath ?: "/").ifEmpty { "/" } + (if (q.isNullOrEmpty()) "" else "?$q")
            if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")) "/" else path
        } catch (e: Exception) { "/" }
    }

    fun shown(phone: String) = if (phone.length == 10) "+91 ${phone.substring(0, 5)} ${phone.substring(5)}" else "+91 $phone"

    /** a small grey line with the shield icon (text-xs text-white/60) */
    fun shieldLine(ctx: Context, s: String): View = LK.row(ctx, Gravity.CENTER).apply {
        addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.ShieldCheck, AK.w(0.6f), 14f)) }, LK.lp(Ui.dpi(14f), Ui.dpi(14f), r = 8f))
        addView(LK.t(ctx, s, 12f, AK.w(0.6f), false, 16f / 12f).apply { gravity = Gravity.CENTER }, LK.lp(0, -2, weight = 1f).apply { width = -2; weight = 0f })
    }

    /** Label + input (space-y-2) */
    fun labeled(ctx: Context, label: String, field: View, color: Int = AK.w(0.8f)): LinearLayout = stack(ctx, 8f, AK.label(ctx, label, color), field)
}

// ═══════════════════════════════════ /login ═══════════════════════════════════
/**
 * Login.jsx: email + password (the default), or the WhatsApp OTP: a number that already has an account is signed
 * in directly by the server (the one-time sign-in link) and never sees Google; a new number goes to Google once
 * (it makes the account), then gender → profile → Home on /welcome.
 */
internal class LoginPage(ctx: Context, layer: LoginLayer) : DarkPage(ctx, layer) {
    private val main = Handler(Looper.getMainLooper())
    private var returnTo = "/"
    private var method = "email"
    private var step = "phone"
    private var phone = ""
    private var error = ""
    private var busy = false
    private var sentAt = 0L
    private var ownerGoogle = false
    private var built = false
    private var update: () -> Unit = {}
    init { LoginStore.init(ctx) }

    override fun route(search: String) {
        if (built) return
        built = true
        returnTo = AuthPages.safeReturnTo(search)
        // Already signed in? Skip straight to the app. Not signed in right after the one-time link: say so (with a code).
        val cameFromLink = LoginStore.takeDirectLoginAttempt()
        val note = LoginStore.takeLoginNote()
        if (note.isNotEmpty()) error = note
        show()
        LoginApi.me { u, _ ->
            if (u != null) leaveTo(returnTo, true)
            else if (cameFromLink && note.isEmpty()) { error = WelcomePage.directLoginMessage("session_not_created"); update() }
        }
    }

    private fun leaveTo(path: String, replace: Boolean) {
        if (layer.preview.isNotEmpty()) { layer.toast("→ $path"); return }
        layer.handOff("home") { }
        layer.host.js("location.${if (replace) "replace" else "assign"}(${JSONObject.quote(path)})")
    }

    private val registerPath get() = "/register" + if (returnTo != "/") "?returnTo=" + Uri.encode(returnTo) else ""

    private fun show() {
        when {
            method == "email" -> emailView()
            step == "otp" -> otpView()
            else -> phoneView()
        }
    }

    // ── email and password ──
    private fun emailView() {
        val ctx = context
        val top = AK.small(ctx, "", AK.destructive)
        val form = EmailForm(ctx, layer, returnTo) { challenge -> mfa(challenge) }
        val use = AK.textBtn(ctx, "Use WhatsApp OTP instead") { method = "phone"; error = ""; show() }
        val col = authLayout("Welcome Back", "Sign in with your email and password", footer("New here? ", "Create account") { layer.go(registerPath) },
            top.apply { layoutParams = LK.lp(-1, -2, b = 16f) }, form, use.apply { layoutParams = LK.lp(-2, -2, t = 20f, g = Gravity.CENTER_HORIZONTAL) })
        update = { AK.setText(top, error) }
        update()
        setNeon(true)
        put(col, 16f, 40f)
    }

    private fun mfa(challenge: JSONObject) {
        setNeon(false)
        put(mfaCard(context, MfaFlowView(context, layer, challenge, returnTo)), 16f, 32f)
    }

    // ── mobile number ──
    private fun phoneView() {
        val ctx = context
        val field = AField(ctx, "10-digit mobile number", LK.I.Phone).phone()
        field.edit.letterSpacing = 0.05f
        Compat.autofill(field.edit, "phone")
        val ok = FrameLayout(ctx).apply { background = CssBox(LK.green, 10f); visibility = View.GONE }
        ok.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.ShieldCheck, Color.WHITE, 12f)) }, FrameLayout.LayoutParams(Ui.dpi(12f), Ui.dpi(12f), Gravity.CENTER))
        field.addView(ok, LK.flp(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER_VERTICAL or Gravity.END, r = 12f))
        field.put(phone)
        val err = AK.error(ctx)
        val btn = ABtn(ctx, "Send WhatsApp OTP", icon = LK.lu(LK.I.MessageCircle, Color.WHITE, 16f))
        val form = stack(ctx, 16f, AuthPages.labeled(ctx, "Mobile number", field), err, btn, AuthPages.shieldLine(ctx, "We'll send a 6-digit code on WhatsApp to verify it's you."))
        val use = AK.textBtn(ctx, "Use email and password instead") { method = "email"; error = ""; show() }
        val col = authLayout("Welcome Back", "Sign in with your mobile number", footer("New here? ", "Create account") { layer.go(registerPath) },
            form, use.apply { layoutParams = LK.lp(-2, -2, t = 20f, g = Gravity.CENTER_HORIZONTAL) })
        update = {
            val valid = WelcomePage.validPhone(phone)
            ok.visibility = if (valid) View.VISIBLE else View.GONE
            AK.setText(err, error)
            btn.busy = busy; btn.off = !valid
        }
        field.onText = { v -> val c = WelcomePage.cleanPhone(v); if (c != v) field.put(c); phone = c; if (error.isNotEmpty()) error = ""; update() }
        field.onGo { sendOtp() }
        btn.setOnClickListener { sendOtp() }
        update()
        setNeon(true)
        put(col, 16f, 40f)
        main.postDelayed({ LK.autoFocus(field.edit) }, 120)
    }

    private fun sendOtp() {
        if (busy) return
        error = ""
        if (!WelcomePage.validPhone(phone)) { error = "Please enter a valid 10-digit mobile number."; update(); return }
        busy = true; update()
        LoginApi.fn("sendPhoneOtp", JSONObject().put("phone", phone).put("country_code", "91")) { r ->
            busy = false
            val d = r.data
            if (!r.ok) { error = LoginApi.fnErr(r, "Could not send the OTP. Check your internet and try again."); update(); return@fn }
            if (!d.optBoolean("success")) { error = d.optString("error").ifEmpty { "Could not send the OTP. Please try again." }; update(); return@fn }
            sentAt = SystemClock.elapsedRealtime()
            step = "otp"; show()
        }
    }

    // ── the WhatsApp code ──
    private var otp: OtpRow? = null
    private var otpTick: Runnable? = null
    private fun otpView() {
        val ctx = context
        val o = OtpRow(ctx); otp = o
        val err = AK.error(ctx)
        val owner = AK.textBtn(ctx, "App owner: sign in with Google") { ownerSignIn() }
        val btn = ABtn(ctx, "Verify & Continue")
        val resend = LK.row(ctx, Gravity.CENTER)
        val col = authLayout("Verify Your Number", "Enter the 6-digit code sent on WhatsApp to ${AuthPages.shown(phone)}",
            footer("Wrong number? ", "Change") { step = "phone"; error = ""; ownerGoogle = false; show() },
            stack(ctx, 20f, o, err, owner.apply { layoutParams = LK.lp(-2, -2, g = Gravity.CENTER_HORIZONTAL) }, btn, resend, AuthPages.shieldLine(ctx, "We never share your number with anyone.")))
        fun left() = maxOf(0, 30 - ((SystemClock.elapsedRealtime() - sentAt) / 1000L).toInt())
        val tick = object : Runnable { override fun run() { update(); if (left() > 0 && step == "otp") main.postDelayed(this, 1000) } }
        otpTick = tick
        update = {
            o.bad = error.isNotEmpty()
            AK.setText(err, error)
            owner.visibility = if (ownerGoogle) View.VISIBLE else View.GONE
            btn.busy = busy; btn.off = o.code.length < 6
            resend.removeAllViews()
            val l = left()
            if (l > 0) resend.addView(AK.linked(ctx, 14f, 20f, AK.w(0.7f), Color.WHITE, false, "Didn't get the code? Resend in " to null, "00:${WelcomePage.pad2(l)}" to {}).apply { movementMethod = null })
            else {
                resend.addView(LK.t(ctx, "Didn't get the code? ", 14f, AK.w(0.7f), false, 20f / 14f))
                resend.addView(AK.textBtn(ctx, "Resend OTP", underline = false) { resendOtp() }.apply { isEnabled = !busy; alpha = if (busy) 0.5f else 1f })
            }
        }
        o.onChange = { v -> error = ""; update(); if (v.length == 6) verifyOtp(v) }
        btn.setOnClickListener { verifyOtp(o.code) }
        update()
        setNeon(true)
        put(col, 16f, 40f)
        main.removeCallbacksAndMessages(null)
        main.postDelayed(tick, 1000)
        main.postDelayed({ LK.autoFocus(o.edit) }, 120)
    }

    private fun verifyOtp(value: String) {
        if (busy || value.length != 6) return
        error = ""; busy = true; update()
        LoginApi.fn("verifyPhoneOtp", JSONObject().put("phone", phone).put("country_code", "91").put("otp", value)) { r ->
            val d = r.data
            if (!r.ok) { error = LoginApi.fnErr(r, "Could not verify the OTP. Try again."); otp?.put(""); busy = false; update(); return@fn }
            if (!d.optBoolean("success")) { error = d.optString("error").ifEmpty { "Wrong OTP. Please try again." }; otp?.put(""); busy = false; update(); return@fn }
            // this number already has an account: the server signs that account in directly; Google is never opened here
            val link = d.optString("embed_url").takeIf { it.isNotEmpty() && d.opt("embed_url") != JSONObject.NULL }
            if (link != null) {
                LoginStore.clearPhoneProof()
                LoginStore.markDirectLogin()
                if (layer.preview.isNotEmpty()) { layer.toast("→ sign-in link"); return@fn }
                layer.handOff("link") { why -> if (why == "timeout") { busy = false; error = WelcomePage.directLoginMessage("session_timeout"); update() } }
                layer.host.loadWeb(link)
                return@fn
            }
            if (WelcomePage.accountExists(d)) {
                // (owner only: the proof is kept so the server can check, after Google, that the account and the number belong together)
                if (d.optString("direct_login") == "privileged_user") LoginStore.savePhoneProof(phone, d.optString("verification_token")) else LoginStore.clearPhoneProof()
                ownerGoogle = d.optString("direct_login") == "privileged_user"
                error = WelcomePage.directLoginMessage(d.optString("direct_login"))
                otp?.put(""); busy = false; update()
                return@fn
            }
            // a new number: keep the proof for the profile, then Google makes the account; onboarding continues at /welcome
            LoginStore.savePhoneProof(phone, d.optString("verification_token"))
            val next = "/welcome?verified_phone=" + Uri.encode("91$phone")
            GoogleSignIn.start(layer, phone, next) { res ->
                if (res == "fail") leaveTo(next, false) // Google could not start: onboarding still continues at /welcome
                if (res == "back") { busy = false; update() }
            }
        }
    }

    private fun ownerSignIn() {
        GoogleSignIn.start(layer, phone, "/welcome") { r -> if (r == "fail") { error = "Could not start Google sign-in. Please try again."; update() } }
    }

    private fun resendOtp() {
        if (SystemClock.elapsedRealtime() - sentAt < 30_000 || busy) return
        error = ""; busy = true; update()
        LoginApi.fn("sendPhoneOtp", JSONObject().put("phone", phone).put("country_code", "91")) { r ->
            busy = false
            val d = r.data
            if (!r.ok) { error = LoginApi.fnErr(r, "Could not resend the OTP. Try again."); update(); return@fn }
            if (!d.optBoolean("success")) { error = d.optString("error").ifEmpty { "Could not resend the OTP." }; update(); return@fn }
            sentAt = SystemClock.elapsedRealtime(); otp?.put("")
            update()
            otpTick?.let { main.removeCallbacks(it); main.postDelayed(it, 1000) }
            otp?.let { LK.showKeyboard(it.edit) }
        }
    }

    private fun setNeon(on: Boolean) { getChildAt(0)?.visibility = if (on) View.VISIBLE else View.GONE }
    override fun leave() { main.removeCallbacksAndMessages(null) }
    override fun preview(variant: String, open: String) {
        when {
            variant.startsWith("phone") -> { method = "phone"; step = "phone"; if (variant.contains("filled")) phone = "9876543210"; show() }
            variant.startsWith("otp") -> { method = "phone"; step = "otp"; phone = "9876543210"; sentAt = SystemClock.elapsedRealtime(); show(); if (variant.contains("owner")) { ownerGoogle = true; error = WelcomePage.directLoginMessage("privileged_user"); update() } }
            variant.startsWith("mfa") -> mfa(JSONObject().put("mfa_session_token", "demo").put("mfa_methods", org.json.JSONArray().put("totp")).put("mfa_required", true))
            variant.startsWith("error") -> { error = WelcomePage.directLoginMessage("session_not_created"); show() }
        }
    }
}

/** EmailLoginForm.jsx: email + password → the app's password sign-in (with the MFA step when it is needed) */
@SuppressLint("ViewConstructor")
internal class EmailForm(ctx: Context, private val layer: LoginLayer, private val returnTo: String, private val onMfa: (JSONObject) -> Unit) : LinearLayout(ctx) {
    private val email = AField(ctx, "you@example.com", LK.I.Mail, fill = LK.a(LK.bg, 0.6f), line = AK.input, textColor = AK.fg, hintColor = AK.mutedFg).email()
    private val pass = AField(ctx, "Enter your password", LK.I.Lock, fill = LK.a(LK.bg, 0.6f), line = AK.input, textColor = AK.fg, hintColor = AK.mutedFg).password(false)
    private val err = AK.error(ctx)
    private val btn = ABtn(ctx, "Sign in")
    private var busy = false
    init {
        orientation = VERTICAL
        val forgot = AK.textBtn(ctx, "Forgot password?") { layer.go("/forgot-password") }.apply { gravity = Gravity.END }
        val s = stack(ctx, 16f, AuthPages.labeled(ctx, "Email", email, AK.fg), AuthPages.labeled(ctx, "Password", pass, AK.fg), forgot, err, btn)
        addView(s, LK.lp(-1, -2))
        email.onText = { AK.setText(err, ""); draw() }
        pass.onText = { AK.setText(err, ""); draw() }
        pass.onGo { submit() }
        btn.setOnClickListener { submit() }
        draw()
        postDelayed({ LK.autoFocus(email.edit) }, 150)
    }
    private fun draw() {
        btn.busy = busy; btn.off = email.value.trim().isEmpty() || pass.value.isEmpty()
        email.isEnabled = !busy; pass.isEnabled = !busy
    }
    private fun submit() {
        if (busy || email.value.trim().isEmpty() || pass.value.isEmpty()) return
        busy = true; AK.setText(err, ""); draw()
        LoginApi.auth("login", JSONObject().put("email", email.value.trim()).put("password", pass.value), null) { r ->
            if (!r.ok) { busy = false; draw(); AK.setText(err, LoginApi.fetchErr(r, "Unable to sign in")); return@auth }
            val tok = r.data.optString("access_token")
            if (tok.isNotEmpty()) layer.setToken(tok)
            if (r.data.optBoolean("mfa_required")) { onMfa(r.data); return@auth }
            if (tok.isEmpty()) { busy = false; draw(); AK.setText(err, "Could not sign in. Please try again."); return@auth }
            if (layer.preview.isNotEmpty()) { layer.toast("→ $returnTo"); busy = false; draw(); return@auth }
            layer.signInWith(tok, returnTo)
        }
    }
}

// ═══════════════════════════════════ /register ═══════════════════════════════════
/** Register.jsx: email + password sign-up; a 6-digit code by email makes the account usable */
internal class RegisterPage(ctx: Context, layer: LoginLayer) : DarkPage(ctx, layer) {
    private val main = Handler(Looper.getMainLooper())
    private var returnTo = "/welcome"
    private var email = ""; private var password = ""; private var confirm = ""
    private var error = ""
    private var busy = false
    private var built = false
    private var update: () -> Unit = {}

    override fun route(search: String) {
        if (built) return
        built = true
        returnTo = AuthPages.safeReturnTo(search).let { if (it == "/") "/welcome" else it }
        form()
    }

    private fun validEmail() = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim())
    private fun matches() = password.isNotEmpty() && password == confirm

    private fun form() {
        val ctx = context
        val e = AField(ctx, "you@example.com", LK.I.Mail).email()
        val p = AField(ctx, "At least 8 characters", LK.I.Lock).password(true)
        val c = AField(ctx, "Re-enter your password", LK.I.Lock).password(true)
        e.put(email); p.put(password); c.put(confirm)
        val err = AK.error(ctx)
        val btn = ABtn(ctx, "Create Account")
        val col = authLayout("Create Your Account", "Join True Find and meet real people near you", footer("Already have an account? ", "Sign in") { layer.go("/login") },
            stack(ctx, 16f, AuthPages.labeled(ctx, "Email", e), AuthPages.labeled(ctx, "Password", p), AuthPages.labeled(ctx, "Confirm password", c), err, btn))
        update = { AK.setText(err, error); btn.busy = busy; btn.off = !(validEmail() && password.length >= 8 && matches()) }
        e.onText = { email = it; update() }; p.onText = { password = it; update() }; c.onText = { confirm = it; update() }
        c.onGo { submit() }
        btn.setOnClickListener { submit() }
        update()
        setNeon(true)
        put(col, 16f, 40f)
        main.postDelayed({ LK.autoFocus(e.edit) }, 150)
    }

    private fun submit() {
        if (busy) return
        error = ""
        when {
            !validEmail() -> error = "Please enter a valid email address."
            password.length < 8 -> error = "Password must be at least 8 characters."
            !matches() -> error = "Passwords do not match."
        }
        if (error.isNotEmpty()) { update(); return }
        busy = true; update()
        LoginApi.auth("register", JSONObject().put("email", email.trim()).put("password", password), Api.token.ifEmpty { "" }) { r ->
            busy = false
            if (!r.ok) { error = LoginApi.sdkErr(r).ifEmpty { "Could not create your account. Please try again." }; update(); return@auth }
            otp()
        }
    }

    private var code: OtpRow? = null
    private fun otp() {
        val ctx = context
        val o = OtpRow(ctx); code = o
        val err = AK.error(ctx)
        val btn = ABtn(ctx, "Verify & Continue")
        val resend = LK.row(ctx, Gravity.CENTER)
        resend.addView(LK.t(ctx, "Didn't get the code? ", 14f, AK.w(0.7f), false, 20f / 14f))
        val again = AK.textBtn(ctx, "Resend", underline = false) { resend() }
        resend.addView(again)
        val col = authLayout("Verify Your Email", "Enter the 6-digit code sent to ${email.trim()}",
            footer("Wrong email? ", "Start over") { if (!busy) { o.put(""); error = ""; form() } },
            stack(ctx, 20f, o, err, btn, resend))
        update = { o.bad = error.isNotEmpty(); AK.setText(err, error); btn.busy = busy; btn.off = o.code.length != 6; again.isEnabled = !busy; again.alpha = if (busy) 0.5f else 1f }
        o.onChange = { v -> update(); if (v.length == 6) verify(v) }
        btn.setOnClickListener { verify(o.code) }
        update()
        setNeon(true)
        put(col, 16f, 40f)
        main.postDelayed({ LK.autoFocus(o.edit) }, 150)
    }

    private fun verify(value: String) {
        if (busy || value.length != 6) return
        error = ""; busy = true; update()
        LoginApi.auth("verify-otp", JSONObject().put("email", email.trim()).put("otp_code", value), Api.token) { r ->
            if (!r.ok) { error = LoginApi.sdkErr(r).ifEmpty { "Wrong code. Please check and try again." }; code?.put(""); busy = false; update(); return@auth }
            if (r.data.optBoolean("mfa_required")) { busy = false; mfa(r.data); return@auth }
            val tok = r.data.optString("access_token")
            if (tok.isEmpty()) { error = "Could not complete verification. Please try again."; code?.put(""); busy = false; update(); return@auth }
            if (layer.preview.isNotEmpty()) { layer.toast("→ $returnTo"); return@auth }
            // the default destination resumes the phone / profile sign-up
            layer.signInWith(tok, returnTo)
        }
    }

    private fun resend() {
        if (busy) return
        busy = true; error = ""; update()
        LoginApi.auth("resend-otp", JSONObject().put("email", email.trim()), Api.token) { r ->
            busy = false
            if (!r.ok) error = LoginApi.sdkErr(r).ifEmpty { "Could not resend the code. Please try again." }
            update()
        }
    }

    private fun mfa(challenge: JSONObject) { setNeon(false); put(mfaCard(context, MfaFlowView(context, layer, challenge, returnTo)), 16f, 32f) }
    private fun setNeon(on: Boolean) { getChildAt(0)?.visibility = if (on) View.VISIBLE else View.GONE }
    override fun leave() { main.removeCallbacksAndMessages(null) }
    override fun preview(variant: String, open: String) {
        if (variant.contains("otp")) { email = "aarav@example.com"; otp() }
        if (variant.contains("error")) { email = "aarav@example"; password = "short"; confirm = "short"; form(); submit() }
    }
}

// ═══════════════════════════════════ /forgot-password ═══════════════════════════════════
/** ForgotPassword.jsx: always the same "check your email" answer (it never tells whether an account exists) */
internal class ForgotPage(ctx: Context, layer: LoginLayer) : DarkPage(ctx, layer) {
    private var email = ""
    private var built = false
    override fun route(search: String) { if (!built) { built = true; form() } }

    private fun form() {
        val ctx = context
        val e = AField(ctx, "you@example.com", LK.I.Mail).email()
        e.put(email)
        val btn = ABtn(ctx, "Send Reset Link")
        val submit = {
            if (!btn.busy && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim())) {
                btn.busy = true
                LoginApi.auth("reset-password-request", JSONObject().put("email", email.trim()), Api.token) { _ -> btn.busy = false; sent() }
            } else if (!btn.busy) { e.bad = true; LK.showKeyboard(e.edit) }
        }
        e.onText = { email = it; e.bad = false }
        e.onGo { submit() }
        btn.setOnClickListener { submit() }
        put(authLayout("Forgot Password", "We'll email you a link to reset your password", footer("Remembered it? ", "Back to sign in") { layer.go("/login") },
            stack(ctx, 16f, AuthPages.labeled(ctx, "Email", e), btn)), 16f, 40f)
        postDelayed({ LK.autoFocus(e.edit) }, 150)
    }

    private fun sent() {
        val ctx = context
        val col = LK.col(ctx, Gravity.CENTER_HORIZONTAL)
        col.addView(ImageView(ctx).apply { setImageDrawable(LK.lu("""<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>""", AK.primary, 48f)) }, LK.lp(Ui.dpi(48f), Ui.dpi(48f)))
        col.addView(LK.t(ctx, "Check your email", 16f, AK.w(0.9f), false, 1.5f).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2, t = 12f))
        col.addView(AK.small(ctx, "If an account exists for ${email.trim()}, we've sent a password reset link.", AK.w(0.7f)), LK.lp(-1, -2, t = 12f))
        val again = ABtn(ctx, "Try another email", "outline", 48f, bold = false).apply {
            setBackgroundBox(CssBox(LK.a(LK.bg, 0.4f), AK.MD, 1f, AK.w(0.2f), shadows = listOf(Sh(0f, 1f, 2f, 0f, Color.argb(13, 0, 0, 0)))))
            textColor(Color.WHITE)
            setOnClickListener { form() }
        }
        col.addView(again, LK.lp(-1, -2, t = 20f))
        put(authLayout("Forgot Password", "We'll email you a link to reset your password", footer("Remembered it? ", "Back to sign in") { layer.go("/login") }, col), 16f, 40f)
    }
    override fun preview(variant: String, open: String) { if (variant.contains("sent")) { email = "aarav@example.com"; sent() } }
}

// ═══════════════════════════════════ /reset-password ═══════════════════════════════════
/** ResetPassword.jsx: a new password with the token from the email link (?token=…), then back to /login */
internal class ResetPage(ctx: Context, layer: LoginLayer) : DarkPage(ctx, layer) {
    private var token = ""
    private var built = false
    override fun route(search: String) {
        if (built) return
        built = true
        token = query(search, "token")
        val ctx = context
        val p = AField(ctx, "At least 8 characters", LK.I.Lock).password(true)
        val c = AField(ctx, "Re-enter your password", LK.I.Lock).password(true)
        val err = AK.error(ctx)
        val btn = ABtn(ctx, "Reset Password")
        val submit = submit@{
            if (btn.busy) return@submit
            AK.setText(err, "")
            val e = when {
                token.isEmpty() -> "This reset link is missing its token. Please use the link from your email."
                p.value.length < 8 -> "Password must be at least 8 characters."
                p.value != c.value -> "Passwords do not match."
                else -> ""
            }
            if (e.isNotEmpty()) { AK.setText(err, e); return@submit }
            btn.busy = true
            LoginApi.auth("reset-password", JSONObject().put("reset_token", token).put("new_password", p.value), Api.token) { r ->
                if (!r.ok) { btn.busy = false; AK.setText(err, LoginApi.sdkErr(r).ifEmpty { "Could not reset your password. The link may have expired." }); return@auth }
                if (layer.preview.isNotEmpty()) { layer.toast("→ /login"); btn.busy = false; return@auth }
                layer.handOff("home") { }
                layer.host.js("location.href='/login'")
            }
        }
        c.onGo { submit() }
        btn.setOnClickListener { submit() }
        put(authLayout("Reset Password", "Choose a new password for your account", footer("Remembered it? ", "Back to sign in") { layer.go("/login") },
            stack(ctx, 16f, AuthPages.labeled(ctx, "New password", p), AuthPages.labeled(ctx, "Confirm new password", c), err, btn)), 16f, 40f)
    }
}
