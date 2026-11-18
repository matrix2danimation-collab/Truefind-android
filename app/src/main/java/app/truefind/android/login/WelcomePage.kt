package app.truefind.android.login

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import app.truefind.android.Api
import app.truefind.android.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * True Find · Welcome (/welcome, and "/" while signed out), native — pages/Welcome.jsx:
 * Get Started (3 screens) → mobile number → WhatsApp OTP → gender → profile (name, date of birth,
 * state, city) → Home. The same rules as the web page:
 *  - a number that already has a finished account opens it directly (the server's one-time sign-in link,
 *    opened in the web page); Google is never shown to it;
 *  - a new number goes to Google once (Base44 makes the account), then gender → profile → Home;
 *  - one mobile number = one account (checkPhoneLogin after Google);
 *  - the OTP proof (15 min) and a profile filled in before Google (30 days) are kept on the phone.
 */
internal class WelcomePage(ctx: Context, layer: LoginLayer) : LPage(ctx, layer) {

    companion object {
        const val OTP_LENGTH = 6
        const val RESEND_SECONDS = 30
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

        /** True when this number already has a finished account (lib/directLogin.js accountExists) */
        fun accountExists(d: JSONObject) = d.optBoolean("account_exists") || (d.optBoolean("has_profile") && d.optBoolean("profile_complete"))
        fun directLoginMessage(code: String?): String {
            val c = (code ?: "unknown").replace(Regex("[^a-z0-9_]", RegexOption.IGNORE_CASE), "").take(60).ifEmpty { "unknown" }
            if (c == "banned") return "This account has been blocked. Please contact True Find support."
            if (c == "privileged_user") return "This number belongs to the app owner's account. Base44 does not allow a direct OTP sign-in for the owner account."
            return "We could not sign you in right now. Please try again in a minute. (Code: $c)"
        }
    }

    // ── kept on the phone (the web keeps the same in the browser's storage): LoginStore ──
    init { LoginStore.init(ctx) }
    private fun readPhoneProof(phone: String?) = LoginStore.readPhoneProof(phone)
    private fun clearPhoneProof() = LoginStore.clearPhoneProof()
    private fun proofPhone() = LoginStore.proofPhone()
    private fun readPending() = LoginStore.readPending()
    private fun writePending(o: JSONObject) = LoginStore.writePending(o)
    private fun clearPending() = LoginStore.clearPending()
    private fun saveLoginNote(text: String) = LoginStore.saveLoginNote(text)
    private fun takeLoginNote() = LoginStore.takeLoginNote()
    private fun markDirectLogin() = LoginStore.markDirectLogin()
    private fun takeDirectLoginAttempt() = LoginStore.takeDirectLoginAttempt()

    // ── state (Onboarding of Welcome.jsx) ──
    var demo = false; private set
    var phone = ""
    var loginNote = ""
    var ownerGoogle = false
    var demoOtp = ""
    var otpPhone = ""
    var sentAt = 0L
    var gender = ""
    var fName = ""; var fDob = ""; var fState = ""; var fCity = ""
    var profileBusy = false
    var profileMsg = ""
    var profileMsgType = ""
    private var user: JSONObject? = null
    private var userChecked = false
    private var hasProfile = false
    private var otpSent = false
    private var seq = 0
    private var booted = ""

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
        val verifiedPhone = query(search, "verified_phone")
        if (query(search, "reset") == "1") clearPending()
        val startPending = readPending()
        history.clear(); depth = 0
        if (startPending != null) {
            phone = startPending.optString("phone", phone); gender = startPending.optString("gender", gender)
            fName = startPending.optString("full_name"); fState = startPending.optString("state"); fCity = startPending.optString("city")
            val parts = startPending.optString("date_of_birth").split("-")
            if (parts.size == 3) fDob = "${parts[2]}/${parts[1]}/${parts[0]}"
        }
        if (top == null) show(if (startPending != null) "boot" else "intro", "fade")
        LoginApi.me { u, _ ->
            if (my != seq) return@me
            user = u; userChecked = true
            if (verifiedPhone.isNotEmpty()) { val d = onlyDigits(verifiedPhone).replace(Regex("^91"), "").takeLast(10); if (d.isNotEmpty()) phone = d }
            val cameFromLink = takeDirectLoginAttempt()
            val note = takeLoginNote().ifEmpty { if (u == null && cameFromLink) directLoginMessage("session_not_created") else "" }
            if (u == null && note.isNotEmpty()) { loginNote = note; go("login", 0, false); return@me }
            // ONE NUMBER = ONE ACCOUNT: signed in with a number just proven by OTP — do they belong together?
            val proven = if (u != null && !demo) proofPhone() else ""
            if (proven.isNotEmpty()) {
                LoginApi.fn("checkPhoneLogin", JSONObject().put("phone", proven).put("phone_token", readPhoneProof(proven))) { r ->
                    if (my != seq) return@fn
                    val st = r.data.optString("status")
                    if (r.ok && (st == "taken" || st == "mismatch")) { clearPending(); rejectLogin(r.data.optString("error")); return@fn }
                    bootProfile(my, u, startPending, verifiedPhone)
                }
            } else bootProfile(my, u, startPending, verifiedPhone)
        }
    }

    private fun bootProfile(my: Int, u: JSONObject?, startPending: JSONObject?, verifiedPhone: String) {
        if (u == null) { bootRest(my, null, null, startPending, verifiedPhone); return }
        findMyProfile(u) { existing ->
            if (my != seq) return@findMyProfile
            val done = existing != null && (hasAllDetails(existing) || existing.optBoolean("profile_complete"))
            hasProfile = done
            if (done) clearPending()
            val next = {
                if (done && !demo) { if (!openHome()) bootRest(my, u, existing, startPending, verifiedPhone) }
                else bootRest(my, u, existing, startPending, verifiedPhone)
            }
            // profiles saved before this was set stay hidden from every list: fixed on the way in
            if (done && !demo && existing?.optBoolean("profile_complete") != true) markProfileComplete { if (my == seq) next() } else next()
        }
    }

    private fun bootRest(my: Int, u: JSONObject?, existing: JSONObject?, startPending: JSONObject?, verifiedPhone: String) {
        if (my != seq) return
        if (existing != null && !hasProfile) {
            // keep what was already filled in, so the person only completes the rest
            if (existing.optString("gender").isNotEmpty()) gender = existing.optString("gender").lowercase()
            if (fName.isEmpty()) fName = existing.optString("full_name")
            if (fState.isEmpty()) fState = existing.optString("state")
            if (fCity.isEmpty()) fCity = existing.optString("city")
        }
        if (startPending != null && u != null && !hasProfile) {
            go("profile", 0, false)
            profileBusy = true; refreshTop()
            saveProfile(startPending) { err ->
                if (my != seq) return@saveProfile
                if (err == null) {
                    clearPending()
                    if (openHome()) return@saveProfile
                    profileBusy = false; profileMsgType = "error"; profileMsg = "Could not open Home. Please try again."; refreshTop()
                } else { profileBusy = false; profileMsgType = "error"; profileMsg = err; refreshTop() }
            }
            return
        }
        // signed in but sign-up never finished → gender + profile, only with a number proven by WhatsApp OTP
        if (u != null && !demo && !hasProfile) {
            val proven = proofPhone()
            if (proven.isNotEmpty()) { phone = proven; go("gender", 0, false) } else go("login", 0, false)
            return
        }
        // back from Google with a verified number but no session: straight to gender
        if (u == null && verifiedPhone.isNotEmpty()) { go("gender", 0, false); return }
        if (step == "boot") go("intro", 0, false)
    }

    private fun findMyProfile(u: JSONObject, done: (JSONObject?) -> Unit) {
        val queries = ArrayList<JSONObject>()
        if (u.optString("id").isNotEmpty()) queries.add(JSONObject().put("created_by_id", u.optString("id")))
        if (u.optString("email").isNotEmpty()) queries.add(JSONObject().put("created_by", u.optString("email")))
        fun next(i: Int) {
            if (i >= queries.size) { done(null); return }
            LoginApi.profiles(queries[i]) { r -> val first = if (r.ok) r.list.firstOrNull() else null; if (first != null) done(first) else next(i + 1) }
        }
        next(0)
    }
    private fun hasAllDetails(p: JSONObject) = listOf("full_name", "gender", "date_of_birth", "state", "city").all { p.optString(it).isNotEmpty() && p.opt(it) != JSONObject.NULL }
    private fun markProfileComplete(done: (Boolean) -> Unit) {
        LoginApi.fn("updateMyProfile", JSONObject().put("updates", JSONObject().put("profile_complete", true))) { r -> done(r.ok) }
    }

    /** createMyProfile (+ profile_complete, + the account's name). null = saved, else the message to show */
    private fun saveProfile(profile: JSONObject, done: (String?) -> Unit) {
        val token = readPhoneProof(profile.optString("phone"))
        LoginApi.fn("createMyProfile", JSONObject().put("profile", profile).put("phone_token", token)) { r ->
            val err = r.data.opt("error")
            if (!r.ok || (err != null && err != JSONObject.NULL && err.toString().isNotEmpty())) {
                done(if (r.status == 404) "Profile service not found. Please check the createMyProfile function in Base44." else LoginApi.friendly(if (r.ok) LoginApi.R(400, r.data, r.text) else r, "Could not save your profile. Please try again."))
                return@fn
            }
            clearPhoneProof() // used once
            val existing = r.data.optBoolean("existing")
            markProfileComplete { ok ->
                // a brand-new profile is already marked complete by createMyProfile
                if (existing && !ok) { done("Could not save your profile. Please try again."); return@markProfileComplete }
                LoginApi.updateMe(JSONObject().put("full_name", profile.optString("full_name"))) { done(null) }
            }
        }
    }

    /** A full page load into Home, so the app's own login check sees the session (2 quick tries at most). */
    private fun openHome(): Boolean {
        if (!LoginStore.homeHop()) return false
        if (layer.preview.isNotEmpty()) { layer.toast("→ /home"); return true }
        layer.handOff("home") { profileBusy = false; profileMsgType = "error"; profileMsg = "Could not open Home. Please try again."; refreshTop() }
        layer.host.js("location.replace('/home')")
        return true
    }

    /** The number and the signed-in account do not belong together: sign out, back to the login screen with the reason. */
    private fun rejectLogin(message: String) {
        clearPhoneProof()
        saveLoginNote(message.ifEmpty { "This mobile number is already linked to another True Find account." })
        booted = ""
        layer.serverLogout("/welcome")
    }

    // ═════════════════ OTP ═════════════════
    /** sendPhoneOtp; [done] gets "" when sent, else the error to show */
    fun sendOtp(done: (String) -> Unit) {
        val p = phone
        LoginApi.fn("sendPhoneOtp", JSONObject().put("phone", p).put("country_code", "91")) { r ->
            val d = r.data
            if (!r.ok) { done(LoginApi.fnErr(r, "Could not send the OTP. Please check your internet and try again.")); return@fn }
            if (!d.optBoolean("success")) { done(d.optString("error").ifEmpty { "Could not send the OTP. Please try again." }); return@fn }
            otpSent = true
            demoOtp = if (d.optBoolean("demo") && d.optString("demo_code").isNotEmpty()) d.optString("demo_code") else ""
            otpPhone = p
            sentAt = SystemClock.elapsedRealtime()
            done("")
        }
    }

    /** the Login screen's button: a code sent less than 30 s ago to the same number is reused */
    fun onSent(done: (String) -> Unit) {
        if (otpPhone != phone || sentAt == 0L || SystemClock.elapsedRealtime() - sentAt > RESEND_SECONDS * 1000L) {
            sendOtp { err -> if (err.isNotEmpty()) done(err) else { go("verify"); done("") } }
        } else { go("verify"); done("") }
    }

    /** verifyPhoneOtp; [done] gets "" when right (then [afterVerify]), null while a sign-in link is opening, else the error */
    fun checkOtp(code: String, done: (String?) -> Unit) {
        LoginApi.fn("verifyPhoneOtp", JSONObject().put("phone", phone).put("country_code", "91").put("otp", code)) { r ->
            val d = r.data
            if (!r.ok) { done(LoginApi.fnErr(r, "Could not check the OTP. Please check your internet and try again.")); return@fn }
            if (!d.optBoolean("success")) { done(d.optString("error").ifEmpty { "Wrong OTP. Please check and try again." }); return@fn }
            LoginStore.savePhoneProof(phone, d.optString("verification_token"))
            // this number already has an account: the server signs that account in directly; Google is never opened for it
            if (accountExists(d) && !demo) {
                if (user != null) { clearPhoneProof(); done("This mobile number is already linked to another True Find account."); return@fn }
                val link = d.optString("embed_url").takeIf { it.startsWith("https://") && d.opt("embed_url") != JSONObject.NULL }
                if (link != null) {
                    clearPhoneProof()
                    markDirectLogin()
                    done(null)
                    if (layer.preview.isNotEmpty()) { layer.toast("→ sign-in link"); return@fn }
                    layer.handOff("link") { why ->
                        // the link made no session: say so on the number screen (Google is never opened)
                        takeDirectLoginAttempt()
                        loginNote = directLoginMessage(if (why == "timeout") "session_timeout" else "session_not_created")
                        go("login", -1, false)
                    }
                    layer.host.loadWeb(link)
                    return@fn
                }
                // (owner only: the proof stays, so the server can check after Google that the account and number belong together)
                if (d.optString("direct_login") != "privileged_user") clearPhoneProof()
                ownerGoogle = d.optString("direct_login") == "privileged_user"
                done(directLoginMessage(d.optString("direct_login")))
                return@fn
            }
            done("")
        }
    }

    /** after a right OTP: signed in already (only the phone was missing) → gender; else Google once (makes the account) */
    fun afterVerify(done: () -> Unit) {
        if (user != null) { go("gender", 1, "replace"); done(); return }
        // the page leaves for Google ("started": stays busy until it has gone); Google could not start → gender
        GoogleSignIn.start(layer, phone, null) { r -> when (r) { "fail" -> { go("gender", 1, "replace"); done() }; "back" -> done() } }
    }

    fun ownerGoogleSignIn() { GoogleSignIn.start(layer, phone, "/welcome") { } }

    // ═════════════════ profile ═════════════════
    /** Save & Continue */
    fun saveFromForm(payload: JSONObject) {
        profileBusy = true; refreshTop()
        LoginApi.me { u, _ ->
            user = u
            if (u == null) {
                // no session yet: keep the details and make the session through Google; they are saved on return
                writePending(payload)
                GoogleSignIn.start(layer, phone, null) { r ->
                    if (r == "fail") { clearPending(); profileBusy = false; profileMsgType = "error"; profileMsg = "Could not start Google sign-in. Please try again."; refreshTop() }
                    if (r == "back") { profileBusy = false; refreshTop() }
                }
                return@me
            }
            val finish = {
                saveProfile(payload) { err ->
                    if (err == null) { clearPending(); if (!openHome()) { profileBusy = false; profileMsgType = "error"; profileMsg = "Could not open Home. Please try again."; refreshTop() } }
                    else { profileBusy = false; profileMsgType = "error"; profileMsg = err; refreshTop() }
                }
            }
            if (demo) findMyProfile(u) { p -> if (p != null) { clearPending(); if (!openHome()) { profileBusy = false; refreshTop() } } else finish() }
            else finish()
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
            if (!layer.host.canWebBack() && layer.preview.isEmpty()) { layer.host.finishApp(); return true }
            return false
        }
        if (top?.onBack() == true) return true
        if (s == "verify") ownerGoogle = false
        goBack(s)
        return true
    }

    override fun signedIn() {
        // a sign-in arrived (Google came back): carry on from the start, signed in now
        if (!layer.handActive()) { booted = ""; route(layer.search) }
    }

    override fun leave() { seq++; top?.dispose(); main.removeCallbacksAndMessages(null) }
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
