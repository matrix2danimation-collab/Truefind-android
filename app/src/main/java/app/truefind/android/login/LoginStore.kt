package app.truefind.android.login

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the sign-in pages keep on the phone between steps (the web keeps the same in the browser's storage:
 * lib/phoneProof.js, lib/directLogin.js and Welcome.jsx). The native pages keep their own copy, so the
 * web's hidden pages never act on it twice.
 *  - the OTP proof of a number (15 minutes), sent to createMyProfile / checkPhoneLogin;
 *  - a profile filled in before Google (30 days), saved when Google comes back;
 *  - a message for the login screen, and the "left for the one-time sign-in link" mark (3 minutes);
 *  - the quick trips to Home (2 in 15 s at most, so it can never loop).
 */
internal object LoginStore {
    private const val PROOF_MAX_MS = 15 * 60 * 1000L
    private const val PENDING_MAX_MS = 30L * 24 * 3600 * 1000
    private const val ATTEMPT_MAX_MS = 3 * 60 * 1000L
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("tf_login", Context.MODE_PRIVATE) }
    private val p: SharedPreferences get() = prefs ?: throw IllegalStateException("LoginStore.init first")

    private fun tenDigits(v: String) = WelcomePage.onlyDigits(v).replace(Regex("^91(?=\\d{10}$)"), "").takeLast(10)

    // ── the OTP proof (phoneProof.js) ──
    fun savePhoneProof(phone: String, token: String) {
        if (token.isEmpty()) return
        p.edit().putString("proof", JSONObject().put("phone", tenDigits(phone)).put("token", token).put("at", System.currentTimeMillis()).toString()).apply()
    }
    private fun proofObj(): JSONObject? = try { JSONObject(p.getString("proof", "") ?: "") } catch (e: Exception) { null }
    /** the token for this number, or "" if there is none / it is too old */
    fun readPhoneProof(phone: String?): String {
        val o = proofObj() ?: return ""
        val t = o.optString("token"); if (t.isEmpty()) return ""
        if (System.currentTimeMillis() - o.optLong("at") > PROOF_MAX_MS) return ""
        if (!phone.isNullOrEmpty() && o.optString("phone") != tenDigits(phone)) return ""
        return t
    }
    fun clearPhoneProof() { p.edit().remove("proof").apply() }
    /** the 10-digit number of a still-valid proof, or "" */
    fun proofPhone(): String {
        val o = proofObj() ?: return ""
        if (o.optString("token").isEmpty() || System.currentTimeMillis() - o.optLong("at") > PROOF_MAX_MS) return ""
        return o.optString("phone")
    }

    // ── a profile filled in before Google (Welcome.jsx readPending / writePending) ──
    fun readPending(): JSONObject? = try {
        val o = JSONObject(p.getString("pending", "") ?: "")
        if (o.optString("full_name").isEmpty() || System.currentTimeMillis() - o.optLong("savedAt") > PENDING_MAX_MS) null else o.apply { remove("savedAt") }
    } catch (e: Exception) { null }
    fun writePending(o: JSONObject) { p.edit().putString("pending", JSONObject(o.toString()).put("savedAt", System.currentTimeMillis()).toString()).apply() }
    fun clearPending() { p.edit().remove("pending").apply() }

    // ── directLogin.js ──
    fun saveLoginNote(text: String) { p.edit().putString("note", text).apply() }
    fun takeLoginNote(): String { val t = p.getString("note", "") ?: ""; p.edit().remove("note").apply(); return t }
    fun markDirectLogin() { p.edit().putLong("direct", System.currentTimeMillis()).apply() }
    /** came back from the sign-in link moments ago? (read once) */
    fun takeDirectLoginAttempt(): Boolean {
        val at = p.getLong("direct", 0L); p.edit().remove("direct").apply()
        return at > 0 && System.currentTimeMillis() - at < ATTEMPT_MAX_MS
    }

    // ── openHome: 2 quick tries at most ──
    /** true when a trip to Home may start now (fewer than 2 in the last 15 s) */
    fun homeHop(): Boolean {
        val now = System.currentTimeMillis()
        val hops = try { JSONArray(p.getString("hops", "[]")) } catch (e: Exception) { JSONArray() }
        val recent = (0 until hops.length()).map { hops.optLong(it) }.filter { now - it < 15000 }
        if (recent.size >= 2) { p.edit().remove("hops").apply(); return false }
        p.edit().putString("hops", JSONArray(recent + now).toString()).apply()
        return true
    }

    // ── Google in the phone's browser (GoogleSignIn): the one sign-in this app started ──
    fun putGoogle(nonce: String, next: String) { p.edit().putString("g_nonce", nonce).putString("g_next", next).putLong("g_at", System.currentTimeMillis()).apply() }
    /** the next page of the started Google sign-in with this [nonce] (once; "" when none / old / another one) */
    fun takeGoogle(nonce: String): String? {
        val n = p.getString("g_nonce", "") ?: ""; val next = p.getString("g_next", "") ?: ""; val at = p.getLong("g_at", 0L)
        if (n.isEmpty() || n != nonce || System.currentTimeMillis() - at > 30 * 60 * 1000L) return null
        p.edit().remove("g_nonce").remove("g_next").remove("g_at").apply()
        return next
    }
    fun googlePending(): Boolean = (p.getString("g_nonce", "") ?: "").isNotEmpty() && System.currentTimeMillis() - p.getLong("g_at", 0L) < 30 * 60 * 1000L
    fun dropGoogle() { p.edit().remove("g_nonce").remove("g_next").remove("g_at").apply() }
}
