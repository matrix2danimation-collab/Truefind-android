package app.truefind.android.login

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.truefind.android.Ui
import org.json.JSONObject
import java.util.Locale

/**
 * The app's own two-step sign-in (MFA): the second sign-in step after an email sign-in, setting up a method,
 * and the two-factor settings (/mfa). The same Base44 routes as the web (lib/authMfaFlow.js mfaSession):
 * /api/apps/<id>/auth/mfa/… with the challenge, then the management grant, as the bearer.
 */
internal class MfaSession(private val layer: LoginLayer, challenge: JSONObject) {
    private var credential = challenge.optString("mfa_session_token")
    class Err(val message: String, val status: Int) { val mustSignInAgain get() = status == 401 }

    private fun call(suffix: String, body: JSONObject?, method: String = "POST", done: (JSONObject?, Err?) -> Unit) {
        LoginApi.auth("mfa/$suffix", body, credential, method, noBody = body == null) { r ->
            if (!r.ok) { done(null, Err(LoginApi.fetchErr(r, "Something went wrong. Try again."), r.status)); return@auth }
            val res = r.data
            res.optString("mfa_management_token").takeIf { it.isNotEmpty() }?.let { credential = it }
            res.optString("access_token").takeIf { it.isNotEmpty() }?.let { layer.setToken(it) }
            done(res, null)
        }
    }
    fun verify(code: String, method: String, remember: Boolean, done: (JSONObject?, Err?) -> Unit) = call("verify", JSONObject().put("code", code).put("method", method).put("remember_device", remember), done = done)
    fun sendSignInCode(done: (JSONObject?, Err?) -> Unit) = call("resend", null, done = done)
    fun setupAuthenticator(done: (JSONObject?, Err?) -> Unit) = call("totp/setup", JSONObject(), done = done)
    fun confirmAuthenticator(code: String, done: (JSONObject?, Err?) -> Unit) = call("totp/verify", JSONObject().put("code", code).put("remember_device", false), done = done)
    fun sendPhoneCode(phone: String, done: (JSONObject?, Err?) -> Unit) = call("sms/send-code", JSONObject().put("phone_number", phone), done = done)
    fun confirmPhone(code: String, done: (JSONObject?, Err?) -> Unit) = call("sms/verify", JSONObject().put("code", code).put("remember_device", false), done = done)
    fun status(done: (JSONObject?, Err?) -> Unit) = call("status", null, "GET", done)
    fun sendProofCode(done: (JSONObject?, Err?) -> Unit) = call("sms/send-code", JSONObject().put("phone_number", ""), done = done)
    fun removeMethod(method: String, code: String, proof: String, done: (JSONObject?, Err?) -> Unit) =
        call("disable", JSONObject().put("method", method).put("code", code).put("verification_method", proof), done = done)
}

/** pieces of the MFA card (MfaParts.jsx) */
internal object MfaKit {
    const val INVALID_PHONE = "Invalid phone number. Please check the number and try again."
    private const val CODES = "af93 ax358 al355 dz213 as1684 ad376 ao244 ai1264 aq672 ag1268 ar54 am374 aw297 au61 at43 az994 bs1242 bh973 bd880 bb1246 by375 be32 bz501 bj229 bm1441 bt975 bo591 ba387 bw267 bv47 br55 io246 vg1284 bn673 bg359 bf226 bi257 kh855 cm237 ca1 cv238 bq599 ky1345 cf236 td235 cl56 cn86 cx61 cc61 co57 km269 cd243 cg242 ck682 cr506 ci225 hr385 cu53 cw599 cy357 cz420 dk45 dj253 dm1767 do1 ec593 eg20 sv503 gq240 er291 ee372 et251 fk500 fo298 fj679 fi358 fr33 gf594 pf689 tf262 ga241 gm220 ge995 de49 gh233 gi350 gr30 gl299 gd1473 gp590 gu1671 gt502 gg44 gn224 gw245 gy592 ht509 hm672 hn504 hk852 hu36 is354 in91 id62 ir98 iq964 ie353 im44 il972 it39 jm1876 jp81 je44 jo962 kz7 ke254 ki686 xk383 kw965 kg996 la856 lv371 lb961 ls266 lr231 ly218 li423 lt370 lu352 mo853 mk389 mg261 mw265 my60 mv960 ml223 mt356 mh692 mq596 mr222 mu230 yt262 mx52 fm691 md373 mc377 mn976 me382 ms1664 ma212 mz258 mm95 na264 nr674 np977 nl31 nc687 nz64 ni505 ne227 ng234 nu683 nf672 kp850 mp1670 no47 om968 pk92 pw680 ps970 pa507 pg675 py595 pe51 ph63 pn64 pl48 pt351 pr1 qa974 re262 ro40 ru7 rw250 bl590 sh290 kn1869 lc1758 mf590 pm508 vc1784 ws685 sm378 st239 sa966 sn221 rs381 sc248 sl232 sg65 sx1721 sk421 si386 sb677 so252 za27 gs500 kr82 ss211 es34 lk94 sd249 sr597 sj47 sz268 se46 ch41 sy963 tw886 tj992 tz255 th66 tl670 tg228 tk690 to676 tt1868 tn216 tr90 tm993 tc1649 tv688 vi1340 ug256 ua380 ae971 gb44 us1 um1 uy598 uz998 vu678 va39 ve58 vn84 wf681 eh212 ye967 zm260 zw263"
    private val COUNTRY: Map<String, String> by lazy { CODES.split(" ").associate { it.substring(0, 2) to it.substring(2) } }
    val DIAL: List<String> by lazy { COUNTRY.values.toSet().sortedBy { it.toLong() } }
    /** the dial code of the phone's region (en-IN → +91), else +1 */
    fun defaultDialCode(): String = COUNTRY[Locale.getDefault().country.lowercase()]?.let { "+$it" } ?: "+1"
    fun isPlausiblePhone(full: String): Boolean { val d = WelcomePage.onlyDigits(full); return d.length in 7..15 }

    /** MfaHeader: the shield tile, the title (waits for the app's name) and the line under it */
    fun header(ctx: Context, title: String?, description: String?): LinearLayout = LK.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
        val tile = FrameLayout(ctx).apply { background = CssBox(AK.primary, 16f) }
        tile.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.ShieldCheck, Color.WHITE, 28f)) }, FrameLayout.LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
        addView(tile, LK.lp(Ui.dpi(56f), Ui.dpi(56f), b = 16f))
        addView(LK.t(ctx, title ?: "", 24f, AK.fg, true, 32f / 24f, -0.6f).apply { gravity = Gravity.CENTER; minHeight = Ui.dpi(32f) }, LK.lp(-1, -2))
        if (!description.isNullOrEmpty()) addView(LK.t(ctx, description, 16f, AK.mutedFg, false, 1.5f).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2, t = 8f))
        layoutParams = LK.lp(-1, -2, b = 24f)
    }

    /** MfaNotice: the error box; an expired sign-in links back to sign-in */
    fun notice(ctx: Context, layer: LoginLayer, err: MfaSession.Err?, signInPath: String = "/login", returnTo: String = "/"): View {
        if (err == null) return View(ctx).apply { visibility = View.GONE; layoutParams = LK.lp(0, 0) }
        val box = FrameLayout(ctx).apply { background = CssBox(LK.a(AK.destructive, 0.1f), 16f); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)) }
        val t: TextView = if (err.mustSignInAgain) {
            val href = signInPath + (if (returnTo != "/") "?returnTo=" + android.net.Uri.encode(returnTo) else "")
            AK.linked(ctx, 14f, 20f, AK.destructive, AK.destructive, true, "Your sign-in expired. " to null, "Sign in again" to { layer.host.js("location.href=${JSONObject.quote(href)}") })
        } else AK.small(ctx, err.message.ifEmpty { "Verification failed. Please try again." }, AK.destructive)
        t.gravity = Gravity.CENTER
        box.addView(t, FrameLayout.LayoutParams(-1, -2))
        box.layoutParams = LK.lp(-1, -2, b = 16f)
        return box
    }

    /** CodeField: the label and the six boxes; six digits (or Enter) → [onComplete] */
    fun codeField(ctx: Context, label: String = "Enter the 6-digit code", onComplete: (String) -> Unit): Pair<LinearLayout, OtpRow> {
        val col = LK.col(ctx, Gravity.CENTER_HORIZONTAL)
        col.addView(AK.label(ctx, label, AK.mutedFg).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2))
        val otp = OtpRow(ctx)
        otp.edit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        otp.edit.setOnEditorActionListener { _, _, _ -> onComplete(otp.code); true }
        col.addView(otp, LK.lp(-1, -2, t = 8f))
        return col to otp
    }

    /** RememberDevice */
    fun remember(ctx: Context, checked: Boolean, onChange: (Boolean) -> Unit): View = LK.row(ctx, Gravity.CENTER).apply {
        val box = ACheck(ctx, checked)
        addView(box)
        addView(LK.t(ctx, "Remember this device for 30 days", 14f, AK.mutedFg, false, 20f / 14f), LK.lp(l = 8f))
        isClickable = true
        setOnClickListener { box.checked = !box.checked; onChange(box.checked) }
    }

    /** two buttons side by side (grid-cols-2 gap-3 pt-2): outline + primary */
    fun actions(ctx: Context, secondary: String, primary: String, onSecondary: () -> Unit, onPrimary: () -> Unit): Pair<LinearLayout, Pair<ABtn, ABtn>> {
        val row = LK.row(ctx).apply { setPadding(0, Ui.dpi(8f), 0, 0) }
        val a = ABtn(ctx, secondary, "outline", 44f, bold = false).apply { setOnClickListener { onSecondary() } }
        val b = ABtn(ctx, primary, "primary", 44f, bold = false, iconGap = 16f).apply { setOnClickListener { onPrimary() } }
        row.addView(a, LK.lp(0, -2, weight = 1f, r = 6f)); row.addView(b, LK.lp(0, -2, weight = 1f, l = 6f))
        return row to (a to b)
    }

    fun h2(ctx: Context, s: String) = LK.t(ctx, s, 18f, AK.fg, true, 28f / 18f).apply { gravity = Gravity.CENTER }
}

/** "Didn't receive a code? Resend", counting down while a resend isn't allowed yet (ResendLine + useCooldown) */
@SuppressLint("ViewConstructor")
internal class ResendLine(ctx: Context, private val onResend: () -> Unit) : LinearLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private var left = 0
    var disabled = false; set(v) { field = v; draw() }
    private val line = LK.row(ctx, Gravity.CENTER)
    private val err = AK.error(ctx)
    private val tick = object : Runnable { override fun run() { if (left > 0) { left -= 1; draw(); if (left > 0) main.postDelayed(this, 1000) } } }
    init {
        orientation = VERTICAL
        addView(line, LK.lp(-1, -2)); addView(err, LK.lp(-1, -2, t = 4f))
        draw()
    }
    fun cool(seconds: Int) { left = seconds; main.removeCallbacks(tick); draw(); if (left > 0) main.postDelayed(tick, 1000) }
    fun error(s: String?) = AK.setText(err, s ?: "")
    private fun draw() {
        line.removeAllViews()
        line.addView(LK.t(context, "Didn't receive a code? ", 14f, AK.mutedFg, false, 20f / 14f))
        if (left > 0) line.addView(LK.t(context, "Resend in ${left}s", 14f, AK.mutedFg, false, 20f / 14f))
        else line.addView(AK.textBtn(context, "Resend", AK.fg, false) { if (!disabled) onResend() }.apply { alpha = if (disabled) 0.5f else 1f })
    }
    override fun onDetachedFromWindow() { main.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}

/** MfaFlow: the second sign-in step (Verify), or setting one up when the app requires it (Enroll) */
@SuppressLint("ViewConstructor")
internal class MfaFlowView(ctx: Context, private val layer: LoginLayer, private val challenge: JSONObject, private val returnTo: String,
                           private val onManage: ((MfaSession) -> Unit)? = null) : FrameLayout(ctx) {
    private val session = MfaSession(layer, challenge)
    private val main = Handler(Looper.getMainLooper())
    init { if (challenge.optString("next_step") == "enroll") enroll(null) else verify() }

    private fun finish(result: JSONObject) {
        if (result.optString("mfa_management_token").isNotEmpty() && onManage != null) { onManage.invoke(session); return }
        val tok = result.optString("access_token")
        if (layer.preview.isNotEmpty()) { layer.toast("→ $returnTo"); return }
        layer.signInWith(tok.ifEmpty { app.truefind.android.Api.token }, returnTo)
    }

    private fun show(v: View) { removeAllViews(); addView(v, LayoutParams(-1, -2)) }

    // ── Enroll ──
    private fun enroll(method: String?) {
        val ctx = context
        val col = LK.col(ctx)
        val head = MfaKit.header(ctx, null, if (method != null) null else "Add a second sign-in step to keep your account secure. Choose how you want to get your codes.")
        col.addView(head)
        LoginApi.loginInfo { r -> ((head.getChildAt(1)) as TextView).text = "${r.data.optString("name").ifEmpty { "This app" }} requires multi-factor authentication" }
        if (method != null) col.addView(MfaMethodSetup(ctx, layer, session, method, null, { finish(it) }, { enroll(null) }), LK.lp(-1, -2))
        else {
            val a = ABtn(ctx, "Set up your authenticator app", "primary", 48f, bold = false).apply { setOnClickListener { enroll("totp") } }
            val b = ABtn(ctx, "Enter your phone number", "outline", 48f, bold = false).apply { setOnClickListener { enroll("sms") } }
            col.addView(stack(ctx, 12f, a, b))
        }
        show(col)
    }

    // ── Verify ──
    private fun verify() {
        val ctx = context
        val methods = (0 until (challenge.optJSONArray("mfa_methods")?.length() ?: 0)).map { challenge.optJSONArray("mfa_methods")!!.optString(it) }
        var method = if (methods.contains("totp")) "totp" else "sms"
        var remember = true
        var busy = false
        var sending = false
        var sent = false
        var err: MfaSession.Err? = null
        val phone = challenge.optString("phone_hint").ifEmpty { "your phone" }
        val col = LK.col(ctx)
        col.addView(MfaKit.header(ctx, "Verify your identity", null))
        val body = LK.col(ctx)
        col.addView(body)
        show(col)
        lateinit var draw: () -> Unit
        lateinit var resend: ResendLine
        var cooldown = 0
        val sendCode: () -> Unit = {
            err = null; sending = true; draw()
            session.sendSignInCode { _, e ->
                sending = false
                if (e != null) err = e else { cooldown = 60; resend.cool(60) }
                draw()
            }
        }
        resend = ResendLine(ctx) { sendCode() }
        val (codeBox, otp) = MfaKit.codeField(ctx) { v -> doVerify(v, method, remember, { busy = it; draw() }, { e -> err = e; draw() }) }
        otp.onChange = { if (it.length == 6) doVerify(it, method, remember, { b -> busy = b; draw() }, { e -> err = e; otp.put(""); draw() }) }
        val btn = ABtn(ctx, "Verify", "primary", 48f, bold = false).apply { busyLabel = "Verifying..." }
        btn.setOnClickListener { doVerify(otp.code, method, remember, { busy = it; draw() }, { e -> err = e; otp.put(""); draw() }) }
        val rememberV = MfaKit.remember(ctx, true) { remember = it }
        draw = {
            body.removeAllViews()
            val items = ArrayList<View>()
            items.add(MfaKit.notice(ctx, layer, err, "/login", returnTo))
            items.add(AK.small(ctx, if (method == "totp") "Enter the code from your authenticator app" else if (sending && cooldown == 0) "Sending code to $phone…" else "Enter the code sent to $phone", AK.mutedFg))
            (codeBox.parent as? ViewGroup)?.removeView(codeBox); items.add(codeBox)
            (rememberV.parent as? ViewGroup)?.removeView(rememberV); items.add(rememberV)
            btn.busy = busy; btn.off = otp.code.length < 6
            (btn.parent as? ViewGroup)?.removeView(btn); items.add(btn)
            if (method == "sms") { resend.disabled = sending; (resend.parent as? ViewGroup)?.removeView(resend); items.add(resend) }
            val other = methods.firstOrNull { it != method }
            if (other != null) items.add(ABtn(ctx, if (other == "sms") "Verify via Text Message" else "Verify via Authenticator App", "outline", 36f, bold = false).apply {
                setOnClickListener { method = other; otp.put(""); err = null; if (method == "sms" && !sent) { sent = true; sendCode() }; draw() }
            })
            val back = if (returnTo.isNotEmpty() && returnTo != "/") "/login?returnTo=" + android.net.Uri.encode(returnTo) else "/login"
            items.add(AK.textBtn(ctx, "Back to Login", AK.mutedFg, false, bold = false) { layer.host.js("location.href=${JSONObject.quote(back)}") })
            var first = true
            for (v in items) {
                if (v.visibility == View.GONE) continue
                body.addView(v, LK.lp(-1, -2, t = if (first) 0f else 16f)); first = false
            }
        }
        otp.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { btn.off = otp.code.length < 6 || busy }
        })
        draw()
        // text the code once when SMS is the method in use; resending is the person's call
        if (method == "sms" && !sent) { sent = true; sendCode() }
        main.postDelayed({ LK.autoFocus(otp.edit) }, 250)
    }

    private var verifying = false
    private fun doVerify(code: String, method: String, remember: Boolean, setBusy: (Boolean) -> Unit, fail: (MfaSession.Err) -> Unit) {
        if (verifying || code.length < 6) return
        verifying = true; setBusy(true)
        session.verify(code, method, remember) { res, e ->
            verifying = false
            if (e != null || res == null) { setBusy(false); fail(e ?: MfaSession.Err("", 0)); return@verify }
            finish(res)
        }
    }

    override fun onDetachedFromWindow() { main.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}

/** MfaMethodSetup: sets up "totp" (authenticator app) or "sms" (text messages) */
@SuppressLint("ViewConstructor")
internal class MfaMethodSetup(
    ctx: Context, private val layer: LoginLayer, private val session: MfaSession, private val method: String, private val signInPath: String?,
    private val onDone: (JSONObject) -> Unit, private val onBack: () -> Unit,
) : FrameLayout(ctx) {
    private var setup: JSONObject? = null
    private var step = "start"
    private var country = MfaKit.defaultDialCode()
    private var phone = ""
    private var fieldError: String? = null
    private var err: MfaSession.Err? = null
    private var busy = false
    private val main = Handler(Looper.getMainLooper())
    private var code = ""
    private val resend = ResendLine(ctx) { doResend() }
    private var touched = false
    private var errV: TextView? = null
    private var markPhone: (String?) -> Unit = {}
    private var sendBtn: ABtn? = null

    init {
        if (method == "totp") { busy = true; session.setupAuthenticator { r, e -> busy = false; if (e != null) err = e else setup = r; draw() } }
        draw()
    }

    private fun sp(): String = signInPath ?: "/login"

    private fun draw() {
        removeAllViews()
        val ctx = context
        val items = ArrayList<View>()
        if (method == "totp" && step == "start") {
            items.add(MfaKit.h2(ctx, "Set up your authenticator app"))
            items.add(MfaKit.notice(ctx, layer, err, sp()))
            if (setup == null && err == null) items.add(FrameLayout(ctx).apply { addView(darkSpinner(ctx, 24f), LayoutParams(-2, -2, Gravity.CENTER)) })
            setup?.let { s ->
                val key = s.optString("secret_formatted")
                val box = LK.col(ctx)
                val steps = LK.col(ctx)
                for ((i, t) in listOf("Copy the key below.", "In your authenticator app, add an account and paste the key.", "Come back here and enter the code the app shows.").withIndex())
                    steps.addView(LK.t(ctx, "${i + 1}.  $t", 14f, AK.mutedFg, false, 20f / 14f), LK.lp(-1, -2, t = if (i == 0) 0f else 4f, l = 4f))
                box.addView(steps)
                box.addView(LK.t(ctx, key, 14f, AK.fg, false, 20f / 14f, 0.7f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.CENTER
                    background = CssBox(Color.TRANSPARENT, 16f, 1f, AK.input); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
                }, LK.lp(-1, -2, t = 12f))
                val copy = ABtn(ctx, "Copy key", "outline", 44f, bold = false)
                copy.setOnClickListener {
                    try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("key", key.replace(Regex("\\s"), ""))) } catch (e: Exception) { }
                    copy.setLabel("Copied"); main.postDelayed({ copy.setLabel("Copy key") }, 2000)
                }
                box.addView(copy, LK.lp(-1, -2, t = 12f))
                items.add(box)
            }
            val (row, btns) = MfaKit.actions(ctx, "Cancel", "Continue", onBack) { step = "code"; draw() }
            btns.first.off = busy; btns.second.busy = busy; btns.second.off = setup == null
            items.add(row)
        } else if (method == "sms" && step == "start") {
            items.add(MfaKit.h2(ctx, "Enter your phone number"))
            items.add(AK.small(ctx, "A verification code will be sent to this number to confirm it's yours.", AK.mutedFg))
            val wrap = LK.col(ctx)
            wrap.addView(AK.label(ctx, "Phone number", AK.fg))
            val phoneError = fieldError ?: (if (err != null && err?.mustSignInAgain != true) err?.message else null)
            wrap.addView(phoneField(ctx, phoneError), LK.lp(-1, -2, t = 6f))
            items.add(wrap)
            if (err?.mustSignInAgain == true) items.add(MfaKit.notice(ctx, layer, err, sp()))
            val (row, btns) = MfaKit.actions(ctx, "Cancel", "Send Code", onBack) { sendCode() }
            btns.first.off = busy; btns.second.busy = busy; btns.second.off = phone.length < 4
            sendBtn = btns.second
            items.add(row)
        } else {
            items.add(MfaKit.h2(ctx, if (method == "totp") "Enter a code from your authenticator app" else "Verify your phone number"))
            if (method == "sms") items.add(AK.small(ctx, "A 6-digit code sent to $country $phone", AK.mutedFg))
            val mid = LK.col(ctx, Gravity.CENTER_HORIZONTAL)
            val (cf, otp) = MfaKit.codeField(ctx) { v -> confirm(v) }
            otp.put(code); otp.bad = fieldError != null
            otp.onChange = { v -> code = v; if (fieldError != null) { fieldError = null; otp.bad = false; errV?.visibility = View.GONE }; if (v.length == 6) confirm(v) }
            mid.addView(cf, LK.lp(-1, -2))
            val fe = AK.error(ctx); AK.setText(fe, fieldError ?: ""); errV = fe
            mid.addView(fe, LK.lp(-2, -2, t = 8f))
            if (method == "totp") mid.addView(LK.t(ctx, "Codes refresh every 30 seconds.", 12f, AK.mutedFg, false, 16f / 12f), LK.lp(t = 8f))
            else { (resend.parent as? ViewGroup)?.removeView(resend); mid.addView(resend, LK.lp(-1, -2, t = 8f)) }
            items.add(mid)
            items.add(MfaKit.notice(ctx, layer, err, sp()))
            val (row, btns) = MfaKit.actions(ctx, "Back", "Verify", { step = "start"; code = ""; fieldError = null; err = null; resend.error(null); draw() }) { confirm(code) }
            btns.first.off = busy; btns.second.busy = busy
            items.add(row)
            main.postDelayed({ LK.autoFocus(otp.edit) }, 200)
        }
        addView(stack(ctx, 16f, *items.filter { it.visibility != View.GONE }.toTypedArray()), LayoutParams(-1, -2))
    }

    private fun phoneField(ctx: Context, error: String?): View {
        val col = LK.col(ctx)
        val row = LK.row(ctx)
        val pick = ABtn(ctx, country, "outline", 40f, bold = false)
        pick.setOnClickListener { dialPicker(pick) }
        row.addView(pick, LK.lp(Ui.dpi(100f), -2))
        val f = AField(ctx, "5551234567", null, 40f, AK.MD, Color.TRANSPARENT, AK.input, AK.fg, AK.mutedFg).phone()
        f.edit.filters = arrayOf(android.text.InputFilter.LengthFilter(15))
        f.put(phone)
        val line = AK.small(ctx, "", AK.mutedFg, center = false)
        // once 4 digits are in, a number that can't be real is flagged as you type; a send error takes its place
        fun mark(err: String?) {
            val invalid = touched && phone.length >= 4 && !MfaKit.isPlausiblePhone("$country$phone")
            val msg = err ?: (if (invalid) MfaKit.INVALID_PHONE else null)
            f.bad = msg != null
            line.text = msg ?: "Standard message and data rates may apply."
            line.setTextColor(if (msg != null) AK.destructive else AK.mutedFg)
            sendBtn?.off = phone.length < 4
        }
        f.onText = { v -> val d = WelcomePage.onlyDigits(v); if (d != v) f.put(d); phone = d; fieldError = null; err = null; touched = true; mark(null) }
        f.onGo { if (!busy && phone.length >= 4) sendCode() }
        row.addView(f, LK.lp(0, -2, weight = 1f, l = 8f))
        col.addView(row, LK.lp(-1, -2))
        col.addView(line, LK.lp(-1, -2, t = 6f))
        markPhone = { mark(it) }
        main.post { mark(error) }
        main.postDelayed({ LK.autoFocus(f.edit) }, 200)
        return col
    }

    /** the dial code list (a small searchable list under the button) */
    private fun dialPicker(anchor: View) {
        val ctx = context
        val w = Ui.dpi(140f)
        val box = LK.col(ctx).apply { background = CssBox(LK.card, AK.MD, 1f, AK.input, shadows = listOf(Sh(0f, 4f, 6f, -1f, Color.argb(26, 0, 0, 0)))) }
        val searchRow = LK.row(ctx).apply { setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0); background = BottomLine(AK.input) }
        searchRow.addView(ImageView(ctx).apply { setImageDrawable(LK.lu("""<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>""", LK.a(AK.fg, 0.5f), 16f)) }, LK.lp(Ui.dpi(16f), Ui.dpi(16f), r = 8f))
        val q = WInput(ctx, 14f, "Search...", AK.fg, AK.mutedFg).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        searchRow.addView(q, LK.lp(0, Ui.dpi(36f), weight = 1f))
        box.addView(searchRow, LK.lp(-1, -2))
        val list = LK.col(ctx).apply { setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)) }
        val sc = object : ScrollView(ctx) { override fun onMeasure(a: Int, b: Int) = super.onMeasure(a, MeasureSpec.makeMeasureSpec(Ui.dpi(300f), MeasureSpec.AT_MOST)) }
            .apply { isVerticalScrollBarEnabled = false; addView(list, LayoutParams(-1, -2)) }
        box.addView(sc, LK.lp(-1, -2))
        val pop = PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { elevation = Ui.dp(8f); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = true }
        var search = ""
        fun fill() {
            list.removeAllViews()
            val n = search.replace("+", "")
            val codes = MfaKit.DIAL.filter { n.isEmpty() || it.startsWith(n) }
            if (codes.isEmpty()) list.addView(LK.t(ctx, "No match", 14f, AK.fg).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(24f), 0, Ui.dpi(24f)) }, LK.lp(-1, -2))
            for ((i, c) in codes.withIndex()) {
                val row = LK.row(ctx).apply { setPadding(Ui.dpi(8f), Ui.dpi(6f), Ui.dpi(8f), Ui.dpi(6f)); background = CssBox(if (i == 0) LK.accent else Color.TRANSPARENT, 12f) }
                row.addView(LK.t(ctx, "+$c", 14f, AK.fg, false, 20f / 14f), LK.lp(0, -2, weight = 1f))
                row.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.Check, AK.fg, 14f)); alpha = if (country == "+$c") 1f else 0f }, LK.lp(Ui.dpi(14f), Ui.dpi(14f)))
                row.isClickable = true
                row.setOnClickListener { country = "+$c"; search = ""; pop.dismiss() }
                list.addView(row, LK.lp(-1, -2))
            }
        }
        q.onText = { search = it; fill() }
        fill()
        pop.setOnDismissListener {
            // closing it with one match left picks that one
            if (search.isNotEmpty()) { val m = MfaKit.DIAL.filter { it.startsWith(search.replace("+", "")) }; if (m.size == 1) country = "+${m[0]}" }
            draw()
        }
        pop.showAsDropDown(anchor, 0, Ui.dpi(4f))
    }

    private fun run(work: (done: (JSONObject?, MfaSession.Err?) -> Unit) -> Unit, onError: (MfaSession.Err) -> Unit, ok: (JSONObject) -> Unit) {
        busy = true; draw()
        work { r, e -> busy = false; if (e != null || r == null) { onError(e ?: MfaSession.Err("", 0)); draw() } else { draw(); ok(r) } }
    }

    private fun sendCode() {
        fieldError = null; err = null; resend.error(null)
        val full = "$country$phone"
        if (!MfaKit.isPlausiblePhone(full)) { fieldError = MfaKit.INVALID_PHONE; draw(); return }
        run({ d -> session.sendPhoneCode(full, d) }, { e -> if (e.message.lowercase().contains("phone")) fieldError = MfaKit.INVALID_PHONE else err = e }) {
            step = "code"; code = ""; draw(); resend.cool(30)
        }
    }
    private fun doResend() {
        resend.error(null)
        session.sendPhoneCode("$country$phone") { _, e -> if (e != null) resend.error(e.message.ifEmpty { "Failed to resend" }) else resend.cool(30) }
    }
    private fun confirm(v: String) {
        if (busy) return
        if (v.length != 6) { fieldError = "Enter the full 6-digit code"; draw(); return }
        fieldError = null; err = null
        run({ d -> if (method == "totp") session.confirmAuthenticator(v, d) else session.confirmPhone(v, d) },
            { e -> if (e.mustSignInAgain) err = e else fieldError = e.message.ifEmpty { "Invalid code" } }) { onDone(it) }
    }
    override fun onDetachedFromWindow() { main.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}

/** MfaManage: enable, replace or turn off a method (with a fresh code from that method) */
@SuppressLint("ViewConstructor")
internal class MfaManageView(ctx: Context, private val layer: LoginLayer, private val session: MfaSession, private val returnTo: String) : FrameLayout(ctx) {
    private var status: JSONObject? = null
    private var err: MfaSession.Err? = null
    private val body = FrameLayout(ctx)
    init {
        val col = LK.col(ctx)
        col.addView(MfaKit.header(ctx, "Two-factor authentication", "Add an extra layer of protection when signing in. You'll be asked for a code each time you sign in."))
        col.addView(body, LK.lp(-1, -2))
        addView(col, LayoutParams(-1, -2))
        load()
    }
    private fun load() { err = null; status = null; list(); session.status { r, e -> if (e != null) err = e else status = r; list() } }
    private fun list() {
        val ctx = context
        body.removeAllViews()
        val items = ArrayList<View>()
        items.add(MfaKit.notice(ctx, layer, err, "/mfa"))
        if (status == null && err == null) items.add(FrameLayout(ctx).apply { addView(darkSpinner(ctx, 24f), LayoutParams(-2, -2, Gravity.CENTER)) })
        status?.let { st ->
            val methods = st.optJSONArray("methods")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            for ((id, name, ico, desc) in listOf(
                arrayOf("totp", "Authenticator app", LK.I.Smartphone, "Use an app like Google Authenticator or Duo to generate secure sign-in codes."),
                arrayOf("sms", "Text messages", LK.I.MessageSquare, "Get sign-in codes sent to your phone by text message."))) {
                val enrolled = methods.contains(id)
                val icon = ImageView(ctx).apply { setImageDrawable(LK.lu(ico, AK.mutedFg, 20f)) }
                val t = LK.col(ctx)
                t.addView(LK.t(ctx, name, 16f, AK.fg, false, 1.5f))
                t.addView(LK.t(ctx, if (enrolled) (if (id == "sms" && st.optString("phone_hint").isNotEmpty()) "Enabled · ${st.optString("phone_hint")}" else "Enabled") else desc, 14f, AK.mutedFg, false, 20f / 14f))
                val btns = LK.row(ctx)
                btns.addView(ABtn(ctx, if (enrolled) "Replace" else "Enable", "outline", 32f, size = 12f, bold = false).apply { setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0); setOnClickListener { setupView(id) } })
                if (enrolled) btns.addView(ABtn(ctx, "Disable", "ghost", 32f, size = 12f, bold = false).apply { setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0); setOnClickListener { disableView(id) } }, LK.lp(l = 8f))
                val row = MethodRow(ctx, icon, t, btns).apply { background = CssBox(Color.TRANSPARENT, 16f, 1f, AK.input); setPadding(Ui.dpi(17f), Ui.dpi(17f), Ui.dpi(17f), Ui.dpi(17f)) }
                items.add(row)
            }
        }
        items.add(ABtn(ctx, "Done", "primary", 48f, bold = false).apply { setOnClickListener { if (layer.preview.isNotEmpty()) layer.toast("→ $returnTo") else layer.host.js("location.href=${JSONObject.quote(returnTo)}") } })
        body.addView(stack(ctx, 16f, *items.filter { it.visibility != View.GONE }.toTypedArray()), LayoutParams(-1, -2))
    }
    private fun setupView(method: String) {
        body.removeAllViews()
        body.addView(MfaMethodSetup(context, layer, session, method, "/mfa", { load() }, { list() }), LayoutParams(-1, -2))
    }
    private fun disableView(method: String) {
        val ctx = context
        var codeSent = method == "totp"
        var e: MfaSession.Err? = null
        var busy = false
        var code = ""
        lateinit var draw: () -> Unit
        val resend = ResendLine(ctx) { if (!busy) { busy = true; draw(); session.sendProofCode { r, er -> busy = false; e = er; draw() } } }
        draw = {
            body.removeAllViews()
            val items = ArrayList<View>()
            items.add(MfaKit.h2(ctx, if (method == "totp") "Enter a code from your authenticator app" else "Verify with a code sent to your phone"))
            items.add(MfaKit.notice(ctx, layer, e, "/mfa"))
            val confirm: (String) -> Unit = { v ->
                if (!busy && v.length >= 6) {
                    busy = true; e = null; draw()
                    session.removeMethod(method, v, method) { r, er -> busy = false; if (er == null && r != null) load() else { e = er; code = ""; draw() } }
                }
            }
            if (codeSent) {
                val (cf, otp) = MfaKit.codeField(ctx) { confirm(it) }
                otp.put(code); otp.onChange = { v -> code = v; if (v.length == 6) confirm(v) }
                items.add(cf)
                if (method == "sms") { resend.disabled = busy; (resend.parent as? ViewGroup)?.removeView(resend); items.add(resend) }
            } else {
                items.add(ABtn(ctx, "Send Code", "primary", 44f, bold = false).apply {
                    this.busy = busy
                    setOnClickListener { busy = true; e = null; draw(); session.sendProofCode { r, er -> busy = false; if (er == null && r != null) { codeSent = true; resend.cool(30) } else e = er; draw() } }
                })
            }
            val (row, b) = MfaKit.actions(ctx, "Cancel", "Verify & Disable", { list() }) { confirm(code) }
            b.first.off = busy; b.second.busy = busy; b.second.off = !codeSent || code.length < 6
            items.add(row)
            body.addView(stack(ctx, 16f, *items.filter { it.visibility != View.GONE }.toTypedArray()), LayoutParams(-1, -2))
        }
        draw()
    }
}

/** the MFA card on a plain dark page (MfaScreen) */
internal fun mfaCard(ctx: Context, content: View): View = FrameLayout(ctx).apply {
    background = CssBox(LK.card, 16f, 1f, LK.border, shadows = listOf(Sh(0f, 1f, 2f, 0f, Color.argb(13, 0, 0, 0))))
    setPadding(Ui.dpi(33f), Ui.dpi(33f), Ui.dpi(33f), Ui.dpi(33f))
    addView(content, FrameLayout.LayoutParams(-1, -2))
}

/**
 * /mfa opened directly: the two-factor authentication settings. Changing them starts with a fresh sign-in
 * (ConfirmIdentity), then a code from an enrolled method (MfaFlow), then MfaManage.
 */
internal class MfaPage(ctx: Context, layer: LoginLayer) : DarkPage(ctx, layer, neon = false) {
    private var returnTo = "/"
    private var built = false
    override fun route(search: String) {
        if (built) return
        built = true
        returnTo = AuthPages.safeReturnTo(search)
        confirmIdentity(null)
    }
    private fun card(v: View) = put(mfaCard(context, v), 16f, 32f)

    private fun confirmIdentity(initial: MfaSession.Err?) {
        val ctx = context
        val col = LK.col(ctx)
        val head = MfaKit.header(ctx, null, "Sign in to continue")
        col.addView(head)
        val noticeHolder = FrameLayout(ctx)
        col.addView(noticeHolder, LK.lp(-1, -2))
        var err = initial
        fun showErr() { noticeHolder.removeAllViews(); val n = MfaKit.notice(ctx, layer, err); if (n.visibility != View.GONE) noticeHolder.addView(n) }
        showErr()
        val spin = FrameLayout(ctx).apply { addView(darkSpinner(ctx, 24f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }
        col.addView(spin, LK.lp(-1, -2))
        card(col)
        LoginApi.loginInfo { r ->
            val info = if (r.ok) r.data else JSONObject()
            (head.getChildAt(1) as TextView).text = "Welcome to ${info.optString("name").ifEmpty { "the app" }}"
            col.removeView(spin)
            val o = info.optJSONObject("auth_config") ?: JSONObject()
            val items = ArrayList<View>()
            for ((id, flag, name) in listOf(Triple("google", "enable_google_login", "Google"), Triple("microsoft", "enable_microsoft_login", "Microsoft"), Triple("facebook", "enable_facebook_login", "Facebook"), Triple("apple", "enable_apple_login", "Apple"))) {
                val on = if (o.has(flag) && !o.isNull(flag)) o.optBoolean(flag) else id == "google"
                if (!on) continue
                items.add(ABtn(ctx, "Continue with $name", "outline", 48f, bold = false, icon = if (id == "google") LK.art(AK.GOOGLE, 16f) else null, iconGap = 16f).apply {
                    setOnClickListener { GoogleSignIn.provider(layer, id, returnTo, true) { res -> if (res == "fail") { err = MfaSession.Err("Unable to load sign-in options", 0); showErr() } } }
                })
            }
            if (o.optBoolean("enable_sso_login") || o.optBoolean("use_workspace_sso"))
                items.add(ABtn(ctx, "Continue with SSO", "outline", 48f, bold = false).apply { setOnClickListener { GoogleSignIn.provider(layer, "sso", returnTo, true) { } } })
            if (o.optBoolean("enable_username_password")) {
                val email = AField(ctx, "you@example.com", LK.I.Mail, 48f, AK.MD, Color.TRANSPARENT, AK.input, AK.fg, AK.mutedFg).email()
                val pass = AField(ctx, "••••••••", LK.I.Lock, 48f, AK.MD, Color.TRANSPARENT, AK.input, AK.fg, AK.mutedFg).password(false)
                val go = ABtn(ctx, "Continue", "primary", 48f, bold = false)
                val submit = {
                    if (email.value.isNotEmpty() && pass.value.isNotEmpty() && !go.busy) {
                        err = null; showErr(); go.busy = true
                        LoginApi.auth("login", JSONObject().put("email", email.value).put("password", pass.value), null, extra = mapOf("X-App-Mfa-Action" to "manage")) { res ->
                            if (!res.ok) { err = MfaSession.Err(LoginApi.fetchErr(res, "Something went wrong. Try again."), res.status); showErr(); go.busy = false; return@auth }
                            flow(res.data)
                        }
                    }
                }
                go.setOnClickListener { submit() }
                pass.onGo { submit() }
                val form = stack(ctx, 16f,
                    stack(ctx, 8f, AK.label(ctx, "Email", AK.fg), email),
                    stack(ctx, 8f, AK.label(ctx, "Password", AK.fg), pass), go)
                items.add(form)
            }
            col.addView(stack(ctx, 16f, *items.toTypedArray()), LK.lp(-1, -2))
        }
    }

    private fun flow(challenge: JSONObject) { card(MfaFlowView(context, layer, challenge, returnTo) { s -> manage(s) }) }
    private fun manage(s: MfaSession) { card(MfaManageView(context, layer, s, returnTo)) }

    override fun preview(variant: String, open: String) {
        when {
            variant.contains("verify") -> flow(JSONObject().put("mfa_session_token", "demo").put("mfa_methods", org.json.JSONArray().put("totp").put("sms")).put("phone_hint", "•••• 3210"))
            variant.contains("enroll") -> flow(JSONObject().put("mfa_session_token", "demo").put("next_step", "enroll"))
            variant.contains("manage") -> manage(MfaSession(layer, JSONObject().put("mfa_session_token", "demo")))
        }
    }
}

/**
 * One method in the two-factor settings (flex flex-wrap items-center gap-3): icon, the words (at least 160 wide)
 * and the buttons; on a narrow screen the buttons drop below, to the right, instead of squeezing the words.
 */
@SuppressLint("ViewConstructor")
internal class MethodRow(ctx: Context, private val icon: View, private val text: View, private val actions: View) : ViewGroup(ctx) {
    private var wrapped = false
    init { addView(icon); addView(text); addView(actions) }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val inner = w - paddingLeft - paddingRight
        val gap = Ui.dpi(12f)
        val un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        icon.measure(MeasureSpec.makeMeasureSpec(Ui.dpi(20f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ui.dpi(20f), MeasureSpec.EXACTLY))
        actions.measure(un, un)
        val rest = inner - icon.measuredWidth - gap - gap - actions.measuredWidth
        wrapped = rest < Ui.dpi(160f)
        val tw = if (wrapped) inner - icon.measuredWidth - gap else rest
        text.measure(MeasureSpec.makeMeasureSpec(tw.coerceAtLeast(0), MeasureSpec.EXACTLY), un)
        val line1 = maxOf(icon.measuredHeight, text.measuredHeight, if (wrapped) 0 else actions.measuredHeight)
        val h = line1 + (if (wrapped) gap + actions.measuredHeight else 0)
        setMeasuredDimension(w, h + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = Ui.dpi(12f)
        val line1 = maxOf(icon.measuredHeight, text.measuredHeight, if (wrapped) 0 else actions.measuredHeight)
        var x = paddingLeft
        val y = paddingTop
        fun put(v: View, vx: Int, top: Int, lineH: Int) { val vy = top + (lineH - v.measuredHeight) / 2; v.layout(vx, vy, vx + v.measuredWidth, vy + v.measuredHeight) }
        put(icon, x, y, line1); x += icon.measuredWidth + gap
        put(text, x, y, line1)
        val ax = (r - l) - paddingRight - actions.measuredWidth
        if (wrapped) put(actions, ax, y + line1 + gap, actions.measuredHeight) else put(actions, ax, y, line1)
    }
}
