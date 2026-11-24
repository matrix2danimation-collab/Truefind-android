package app.truefind.android.login

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import app.truefind.android.Ui
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * True Find · Welcome (/welcome), native — pages/Welcome.jsx:
 * Get Started (3 screens) → mobile number → WhatsApp OTP → gender → profile (name, date of birth,
 * state, city) → Home. Since the move to Supabase (24 Nov 2026) the sign-in is Supabase Auth itself:
 *  - the OTP goes out through Supabase Auth (its WhatsApp sender, Edge Function "send-sms"); the right OTP
 *    makes the session at once ([Auth]) — there is no Google step and no e-mail sign-in any more;
 *  - one mobile number = one account: the number IS the account (Supabase Auth), so a number that already has
 *    a finished profile simply opens it (checkPhoneLogin "ok"); a new number fills in gender → profile
 *    (createMyProfile), then Home;
 *  - a profile from before that belongs to another account (checkPhoneLogin "taken") signs out with the reason.
 */
internal class WelcomePage(ctx: Context, layer: LoginLayer) : LPage(ctx, layer) {

    companion object {
        const val OTP_LENGTH = 6
        /** Supabase Auth sends a new code to the same number once a minute at most: the Resend timer waits as long */
        const val RESEND_SECONDS = 60
        val STEPS = listOf("intro", "login", "verify", "gender", "profile")
        val BACK_TO = mapOf("login" to "intro", "verify" to "login", "gender" to "login", "profile" to "gender")

        fun onlyDigits(v: String?) = (v ?: "").replace(Regex("\\D"), "")
        fun cleanPhone(raw: String): String {
            var d = onlyDigits(raw)
            if (d.length > 10 && d.startsWith("91")) d = d.substring(2)
            if (d.length > 10 && d.startsWith("0")) d = d.substring(1)
            return d.take(10)
        }
        fun validPhone(p: String) = Regex("^[6-9]\\d{9}$").matches(p)
        fun pad2(n: Int) = n.toString().padStart(2, '0')

        /** today in India (y, m, d) */
        fun todayInIndia(): Triple<Int, Int, Int> {
            val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
            return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }
        fun maskDob(raw: String): String {
            val d = onlyDigits(raw).take(8)
            return when {
                d.length <= 2 -> d
                d.length <= 4 -> "${d.substring(0, 2)}/${d.substring(2)}"
                else -> "${d.substring(0, 2)}/${d.substring(2, 4)}/${d.substring(4)}"
            }
        }
        class Dob(val ok: Boolean, val error: String = "", val iso: String = "", val age: Int = 0)
        fun checkDob(text: String): Dob {
            if (text.isEmpty()) return Dob(false, "Please enter your date of birth.")
            val m = Regex("^(\\d{2})/(\\d{2})/(\\d{4})$").find(text) ?: return Dob(false, "Enter date as DD/MM/YYYY.")
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); isLenient = true; set(y, mo - 1, d) }
            // (JavaScript's Date.UTC reads the years 0–99 as 1900–1999: such a date never matches what was typed)
            if (y < 100 || c.get(Calendar.YEAR) != y || c.get(Calendar.MONTH) != mo - 1 || c.get(Calendar.DAY_OF_MONTH) != d) return Dob(false, "Please enter a real date.")
            val (ty, tm, td) = todayInIndia()
            val future = y > ty || (y == ty && (mo > tm || (mo == tm && d > td)))
            var age = ty - y
            if (tm < mo || (tm == mo && td < d)) age -= 1
            if (future || y < 1900 || age > 100) return Dob(false, "Please enter a valid date of birth.")
            if (age < 18) return Dob(false, "You must be 18 or older to join True Find.")
            return Dob(true, iso = "$y-${pad2(mo)}-${pad2(d)}", age = age)
        }
        private val NAME_RE = Regex("^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*$")
        fun checkName(name: String): String {
            val n = name.trim().replace(Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+"), " ")
            if (n.isEmpty()) return "Please enter your name."
            if (n.length < 2) return "Name is too short."
            if (n.length > 40) return "Name is too long (max 40 letters)."
            if (!NAME_RE.matches(n)) return "Please use letters only."
            return ""
        }

        /** a message of the sign-in that could not finish (shown on the number screen) */
        fun directLoginMessage(code: String?): String {
            val c = (code ?: "unknown").replace(Regex("[^a-z0-9_]", RegexOption.IGNORE_CASE), "").take(60).ifEmpty { "unknown" }
            if (c == "banned") return "This account has been blocked. Please contact True Find support."
            return "We could not sign you in right now. Please try again in a minute. (Code: $c)"
        }

        /** Supabase Auth's answer to "send the OTP", in the words the person reads */
        fun sendError(r: app.truefind.android.Api.Result): String {
            if (r.status == 0) return "Could not send the OTP. Please check your internet and try again."
            val code = r.data.optString("error_code").ifBlank { r.data.optString("code") }
            val msg = app.truefind.android.Supa.authMessage(r.data)
            val wait = Regex("after (\\d+) seconds?").find(msg)?.groupValues?.get(1)
            return when {
                wait != null -> "Please wait $wait seconds before asking for a new OTP."
                r.status == 429 || code.contains("rate_limit") -> "Too many OTP requests. Please wait a few minutes and try again."
                code == "phone_provider_disabled" || code == "sms_send_failed" || code.startsWith("hook") || r.status >= 500 ->
                    "Could not send the OTP on WhatsApp right now. Please try again in a minute."
                code == "validation_failed" || msg.contains("phone", true) && msg.contains("invalid", true) -> "Please enter a valid 10-digit mobile number."
                else -> "Could not send the OTP. Please try again."
            }
        }

        /** Supabase Auth's answer to the OTP check, in the words the person reads */
        fun verifyError(r: app.truefind.android.Api.Result): String {
            if (r.status == 0) return "Could not check the OTP. Please check your internet and try again."
            val code = r.data.optString("error_code").ifBlank { r.data.optString("code") }
            return when {
                r.status == 429 || code.contains("rate_limit") -> "Too many attempts. Please wait a minute and try again."
                code == "user_banned" -> directLoginMessage("banned")
                code == "otp_expired" || r.status == 403 || r.status == 401 -> "Wrong or expired OTP. Please check the code or tap Resend."
                r.status >= 500 -> "Server is busy right now. Please try again in a moment."
                else -> "Wrong OTP. Please check and try again."
            }
        }
    }

    // ── kept on the phone: a message for the number screen after a sign-out (LoginStore) ──
    init { LoginStore.init(ctx) }

    // ── state (Onboarding of Welcome.jsx) ──
    var demo = false; private set
    var phone = ""
    var loginNote = ""
    var demoOtp = ""
    var otpPhone = ""
    var sentAt = 0L
    var gender = ""
    var fName = ""; var fDob = ""; var fState = ""; var fCity = ""
    var profileBusy = false
    var profileMsg = ""
    var profileMsgType = ""
    private var otpSent = false
    private var seq = 0
    private var booted = ""
    private val net = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ── the screens: layers, the old one slides away while the new one slides in ──
    var step = "intro"; private set
    private var depth = 0
    private val history = ArrayList<String>()
    private var top: WelcomeStep? = null
    private val main = Handler(Looper.getMainLooper())

    init { setBackgroundColor(android.graphics.Color.WHITE) }

    override fun route(search: String) {
        val key = search
        if (booted == key && top != null) return
        booted = key
        boot(search)
    }

    // ═════════════════ first load ═════════════════
    private fun boot(search: String) {
        val my = ++seq
        demo = query(search, "demo") == "1"
        history.clear(); depth = 0
        // (a profile filled in before Google existed in the old sign-in: nothing of it is needed any more)
        LoginStore.clearPending()
        // signed out a moment ago with a reason (the number belongs to another account …): the number screen says it
        val note = LoginStore.takeLoginNote()
        if (note.isNotEmpty() && app.truefind.android.Api.token.isEmpty()) {
            loginNote = note
            if (top == null) show("login", "fade") else go("login", 0, false)
            return
        }
        // signed in already (a sign-in page opened from the app — Finish profile —, or the app closed during sign-up)
        if (app.truefind.android.Api.token.isNotEmpty()) {
            if (top == null) show("boot", "fade")
            phone = app.truefind.android.Auth.phone.let { onlyDigits(it).replace(Regex("^91(?=\\d{10}$)"), "").takeLast(10) }.ifEmpty { phone }
            whereNext(my) { err -> if (my == seq && err.isNotEmpty()) { loginNote = err; go("login", 0, false) } }
            return
        }
        if (top == null) show("intro", "fade")
    }

    /**
     * Signed in now (the right OTP): where to go. A finished profile → Home; none yet → gender → profile (what an
     * unfinished one already has is filled in); the number's profile belongs to another account → signed out with
     * the reason. [done] gets "" when it went on, else the message to show (the person can try again).
     */
    private fun whereNext(my: Int, done: (String) -> Unit) {
        LoginApi.fn("checkPhoneLogin", JSONObject()) { r ->
            if (my != seq) return@fn
            val st = r.data.optString("status")
            when {
                r.ok && st == "ok" -> {
                    if (r.data.optBoolean("banned")) { done(directLoginMessage("banned")); return@fn }
                    LoginStore.clearPending(); openHome(); done("")
                }
                r.ok && st == "taken" -> { rejectLogin(r.data.optString("error")); done("") }
                r.ok && st == "no_profile" -> {
                    findMyProfile { existing ->
                        if (my != seq) return@findMyProfile
                        if (existing != null) {
                            // keep what was already filled in, so the person only completes the rest
                            if (existing.optString("gender").isNotEmpty() && existing.opt("gender") != JSONObject.NULL) gender = existing.optString("gender").lowercase()
                            if (fName.isEmpty()) fName = existing.optString("full_name").takeIf { existing.opt("full_name") != JSONObject.NULL }.orEmpty()
                            if (fState.isEmpty()) fState = existing.optString("state").takeIf { existing.opt("state") != JSONObject.NULL }.orEmpty()
                            if (fCity.isEmpty()) fCity = existing.optString("city").takeIf { existing.opt("city") != JSONObject.NULL }.orEmpty()
                        }
                        go("gender", 1, if (step == "verify") "replace" else false)
                        done("")
                    }
                }
                // the sign-in is not accepted (it ended meanwhile): back to the number
                r.status == 401 -> done("Please verify your number again.")
                else -> done(LoginApi.friendly(r, "Could not sign you in right now. Please try again."))
            }
        }
    }

    /** this account's profile, if it has one already (unfinished) */
    private fun findMyProfile(done: (JSONObject?) -> Unit) {
        val uid = app.truefind.android.Auth.userId
        if (uid.isEmpty()) { done(null); return }
        LoginApi.profiles(JSONObject().put("created_by_id", uid), 1) { r -> done(if (r.ok) r.list.firstOrNull() else null) }
    }
    private fun markProfileComplete(done: (Boolean) -> Unit) {
        LoginApi.fn("updateMyProfile", JSONObject().put("updates", JSONObject().put("profile_complete", true))) { r -> done(r.ok) }
    }

    /** createMyProfile (+ profile_complete). null = saved, else the message to show */
    private fun saveProfile(profile: JSONObject, done: (String?) -> Unit) {
        LoginApi.fn("createMyProfile", JSONObject().put("profile", profile)) { r ->
            val err = r.data.opt("error")
            if (!r.ok || (err != null && err != JSONObject.NULL && err.toString().isNotEmpty())) {
                done(LoginApi.friendly(if (r.ok) LoginApi.R(400, r.data, r.text) else r, "Could not save your profile. Please try again."))
                return@fn
            }
            // a brand-new profile is already marked complete by createMyProfile; one made before is marked now
            if (!r.data.optBoolean("existing")) { done(null); return@fn }
            markProfileComplete { ok -> done(if (ok) null else "Could not save your profile. Please try again.") }
        }
    }

    /** the app's own pages take over, on Home */
    private fun openHome() { layer.signedIn("/") }

    /** The number and the signed-in account do not belong together: sign out, back to the number screen with the reason. */
    private fun rejectLogin(message: String) {
        LoginStore.saveLoginNote(message.ifEmpty { "This mobile number is already linked to another True Find account." })
        booted = ""
        layer.serverLogout("/welcome")
    }

    // ═════════════════ OTP ═════════════════
    /** Supabase Auth sends the OTP (WhatsApp); [done] gets "" when sent, else the error to show */
    fun sendOtp(done: (String) -> Unit) {
        val p = phone
        LoginApi.demo?.let { d -> main.postDelayed({ val r = d("fn", "sendPhoneOtp", JSONObject().put("phone", p)); otpSent = true; otpPhone = p; sentAt = SystemClock.elapsedRealtime(); demoOtp = r.data.optString("demo_code"); done("") }, 350); return }
        net.execute {
            val r = app.truefind.android.Supa.auth("POST", "otp", JSONObject().put("phone", "+91$p"))
            main.post {
                if (!r.ok) { done(sendError(r)); return@post }
                otpSent = true
                demoOtp = ""
                otpPhone = p
                sentAt = SystemClock.elapsedRealtime()
                done("")
            }
        }
    }

    /** the Login screen's button: a code sent less than a minute ago to the same number is reused */
    fun onSent(done: (String) -> Unit) {
        if (otpPhone != phone || sentAt == 0L || SystemClock.elapsedRealtime() - sentAt > RESEND_SECONDS * 1000L) {
            sendOtp { err -> if (err.isNotEmpty()) done(err) else { go("verify"); done("") } }
        } else { go("verify"); done("") }
    }

    /** Supabase Auth checks the OTP and, when right, makes the session; [done] gets "" when right, else the error */
    fun checkOtp(code: String, done: (String?) -> Unit) {
        LoginApi.demo?.let { d -> main.postDelayed({ val r = d("fn", "verifyPhoneOtp", JSONObject().put("otp", code)); done(if (r.data.optBoolean("success")) "" else r.data.optString("error").ifEmpty { "Wrong OTP. Please check and try again." }) }, 350); return }
        val p = phone
        net.execute {
            val r = app.truefind.android.Supa.auth("POST", "verify", JSONObject().put("type", "sms").put("phone", "+91$p").put("token", code))
            val signed = r.ok && app.truefind.android.Auth.take(context, r.data)
            main.post {
                if (!r.ok) { done(verifyError(r)); return@post }
                if (!signed) { done("Could not sign you in right now. Please try again."); return@post }
                done("")
            }
        }
    }

    /** after a right OTP (signed in now): Home for a finished profile, else gender → profile */
    fun afterVerify(done: () -> Unit) {
        if (LoginApi.demo != null) { go("gender", 1, "replace"); done(); return }
        val my = seq
        whereNext(my) { err -> verifyNote = err; done() }
    }
    /** a message for the OTP screen after the right code (the next step could not be opened) */
    var verifyNote = ""

    // ═════════════════ profile ═════════════════
    /** Save & Continue */
    fun saveFromForm(payload: JSONObject) {
        profileBusy = true; refreshTop()
        if (LoginApi.demo != null) { main.postDelayed({ profileBusy = false; refreshTop(); openHome() }, 400); return }
        if (app.truefind.android.Api.token.isEmpty()) {
            // (the sign-in ended meanwhile) the number is verified again first
            profileBusy = false; profileMsgType = "error"; profileMsg = "Please verify your number again."; refreshTop()
            main.postDelayed({ loginNote = "Please verify your number again."; go("login", -1, false) }, 900)
            return
        }
        saveProfile(payload) { err ->
            if (err == null) { LoginStore.clearPending(); openHome() }
            else { profileBusy = false; profileMsgType = "error"; profileMsg = err; refreshTop() }
        }
    }

    // ═════════════════ moving between the screens ═════════════════
    /** go(next, dir, push): dir 1 = forward slide, -1 = back, 0 = fade; push true / false / "replace" */
    fun go(next: String, dir: Int = 1, push: Any = true) {
        if (step == next && top != null) return
        // "replace": this screen takes the place of the one before it in the back list (the list stays)
        if (push == true) { depth += 1; history.add(step) }
        show(next, when { dir > 0 -> "fwd"; dir < 0 -> "back"; else -> "fade" })
    }

    fun goBack(from: String) {
        if (depth > 0 && history.isNotEmpty()) {
            depth -= 1
            var prev = history.removeAt(history.size - 1)
            if (prev == "verify" && !otpSent) prev = "login"
            show(prev, "back")
        } else go(BACK_TO[from] ?: "intro", -1, false)
    }

    private fun make(s: String): WelcomeStep = when (s) {
        "boot" -> BootStep(context)
        "intro" -> IntroStep(context, this)
        "login" -> PhoneStep(context, this)
        "verify" -> OtpStep(context, this)
        "gender" -> GenderStep(context, this)
        else -> ProfileStep(context, this)
    }

    private fun show(next: String, anim: String) {
        val from = step
        step = next
        val old = top
        val v = make(next)
        top = v
        addView(v, LayoutParams(-1, -1))
        if (next == "gender" || next == "intro") LK.hideKeyboard(this)
        val w = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        val off = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (old == null || from == "boot" || off) {
            old?.let { it.dispose(); removeView(it) }
            if (old != null && !off) { v.alpha = 0f; v.animate().alpha(1f).setDuration(320).start() }
            v.setLive(true)
            return
        }
        old.setLive(false)
        old.isEnabled = false
        val ms = 360L
        when (anim) {
            "fwd" -> {
                v.translationX = w
                v.animate().translationX(0f).setDuration(ms).setInterpolator(LoginLayer.EASE).start()
                old.animate().translationX(-0.25f * w).alpha(0.75f).setDuration(ms).setInterpolator(LoginLayer.EASE).start()
            }
            "back" -> {
                bringChildToFront(old)
                v.translationX = -0.25f * w; v.alpha = 0.75f
                v.animate().translationX(0f).alpha(1f).setDuration(ms).setInterpolator(LoginLayer.EASE).start()
                old.animate().translationX(w).setDuration(ms).setInterpolator(LoginLayer.EASE).start()
            }
            else -> {
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(320).start()
                old.animate().alpha(0f).setDuration(240).start()
            }
        }
        main.postDelayed({ old.dispose(); removeView(old) }, 420)
        v.setLive(true)
    }

    /** redraws the screen on top with the current state (busy, messages) */
    fun refreshTop() { top?.refresh() }

    override fun onBack(): Boolean {
        val s = step
        if (s == "intro" || s == "boot") {
            if (top?.onBack() == true) return true
            // nothing before Get Started: Back leaves the app (as the browser would)
            // (opened from a page of the app — "Complete my profile": back to that page)
            if (layer.preview.isEmpty()) { if (!layer.host.backToApp()) layer.host.finishApp(); return true }
            return false
        }
        if (top?.onBack() == true) return true
        if (s == "verify") verifyNote = ""
        goBack(s)
        return true
    }

    override fun leave() { seq++; top?.dispose(); main.removeCallbacksAndMessages(null); net.shutdown() }
    override fun visible(v: Boolean) { top?.appVisible(v) }

    // ═════════════════ test mode ═════════════════
    override fun preview(variant: String, open: String) {
        seq++
        LoginDemo.welcome(this, variant)
        val s = when {
            variant.startsWith("intro") || variant.isEmpty() -> "intro"
            variant.startsWith("phone") -> "login"
            variant.startsWith("verify") -> "verify"
            variant.startsWith("gender") -> "gender"
            variant.startsWith("profile") -> "profile"
            variant.startsWith("boot") -> "boot"
            else -> "intro"
        }
        if (s != step) { history.clear(); depth = 0; show(s, "fade") }
        main.postDelayed({ top?.preview(variant, open) }, 700)
    }
}

/** One screen of the Welcome flow. */
internal abstract class WelcomeStep(ctx: Context) : FrameLayout(ctx) {
    init { clipChildren = false; setBackgroundColor(android.graphics.Color.WHITE) }
    /** on top and taking taps (focus the field after the slide) */
    open fun setLive(v: Boolean) {}
    open fun refresh() {}
    open fun dispose() {}
    open fun onBack(): Boolean = false
    open fun appVisible(v: Boolean) {}
    open fun preview(variant: String, open: String) {}
    protected val ui = Ui
    @Suppress("unused") private val keep: View? = null
}
