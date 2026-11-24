package app.truefind.android.login

import android.os.Handler
import android.os.Looper
import app.truefind.android.Api
import app.truefind.android.Auth
import app.truefind.android.Supa
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * The server calls of the sign-in pages (Supabase since 24 Nov 2026: the same "api" function as the rest of the
 * app, [Api.base]): functions (checkPhoneLogin, createMyProfile, updateMyProfile, support — some work before
 * sign-in too) and the member's own profile. The OTP itself goes to Supabase Auth (WelcomePage, [Supa.auth]).
 */
internal object LoginApi {
    class R(val status: Int, val data: JSONObject, val text: String) {
        val ok get() = status in 200..299
        /** a list answer ([..] or { items }) */
        val list: List<JSONObject> get() {
            val a = data.optJSONArray("items") ?: data.optJSONArray("data") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
    }

    /** test mode (emulator pictures): made-up answers, no server */
    var demo: ((kind: String, name: String, body: JSONObject?) -> R)? = null

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private fun send(method: String, url: String, body: String?, timeoutMs: Int = 30000): R {
        var conn: HttpURLConnection? = null
        return try {
            // the sign-in, renewed first when its hour is nearly up ("" before sign-in: public functions only)
            val token = Auth.fresh()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Id", Supa.APP_ID)
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) { setRequestProperty("Content-Type", "application/json"); doOutput = true }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                val t = text.trim()
                if (t.startsWith("[")) JSONObject().put("items", JSONArray(t)) else if (t.startsWith("{")) JSONObject(t) else JSONObject()
            } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) android.util.Log.w("TFLogin", "$method ${url.substringBefore('?').substringAfter("/api/apps/")} → $code ${text.take(160)}")
            R(code, json, text)
        } catch (e: Exception) {
            R(0, JSONObject(), "")
        } finally { conn?.disconnect() }
    }

    /** the request once more with a new sign-in when the server said its hour was up */
    private fun sendRenewing(method: String, url: String, body: String?, timeoutMs: Int = 30000): R {
        val r = send(method, url, body, timeoutMs)
        if (r.status == 401 && r.data.optString("code") == "session_expired" && Auth.renew(true)) return send(method, url, body, timeoutMs)
        return r
    }

    private fun run(work: () -> R, done: (R) -> Unit) { pool.execute { val r = work(); main.post { done(r) } } }

    /** base44.functions.invoke(name, payload) */
    fun fn(name: String, payload: JSONObject, done: (R) -> Unit) {
        demo?.let { d -> main.postDelayed({ done(d("fn", name, payload)) }, 350); return }
        run({ sendRenewing("POST", "${Api.base}/functions/$name", payload.toString()) }, done)
    }

    /** entities.UserProfile.filter(query): the member's own profile (signed in) */
    fun profiles(query: JSONObject, limit: Int = 0, done: (R) -> Unit) {
        demo?.let { d -> main.postDelayed({ done(d("profiles", "", query)) }, 250); return }
        val q = "?q=" + URLEncoder.encode(query.toString(), "UTF-8") + (if (limit > 0) "&limit=$limit" else "")
        run({ sendRenewing("GET", "${Api.base}/entities/UserProfile$q", null) }, done)
    }

    // ── what to show when a call fails (the same texts the web pages show) ──
    /** a function's error (errText of Welcome.jsx: the server's "error", else [fallback]) */
    fun fnErr(r: R, fallback: String): String {
        if (r.status == 0) return fallback
        val e = r.data.opt("error")
        return if (e is String && e.isNotBlank()) e else fallback
    }
    /** friendlyError of Welcome.jsx (createMyProfile and the profile save) */
    fun friendly(r: R, fallback: String): String {
        if (r.status == 429) return "Too many attempts. Please wait a minute and try again."
        if (r.status == 0) return "No internet connection. Please check and try again."
        if (r.status >= 500) return "Server is busy right now. Please try again in a moment."
        val body = r.data
        val m = (body.opt("error") as? String) ?: body.optJSONObject("error")?.optString("message") ?: body.optString("message").ifBlank { body.optString("detail") }
        val msg = (m ?: "").trim()
        if (msg.isNotEmpty() && msg.length <= 140 && !Regex("status code|undefined|null|\\[object", RegexOption.IGNORE_CASE).containsMatchIn(msg)) return msg
        return fallback
    }
    /** axios' own message (err.message of a failed functions call, no "error" in the answer) */
    fun axiosMsg(r: R): String = if (r.status == 0) "Network Error" else "Request failed with status code ${r.status}"
    /** err?.response?.data?.error || err.message (BasicProfile, CompleteProfile) */
    fun fnMsg(r: R): String { val e = r.data.opt("error"); return if (e is String && e.isNotEmpty()) e else axiosMsg(r) }

    /** a Base44 SDK auth error message (err.message of base44.auth.*) */
    fun sdkErr(r: R): String {
        if (r.status == 0) return "Network Error"
        val d = r.data
        return d.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: d.optString("message").takeIf { it.isNotBlank() }
            ?: d.optString("detail").takeIf { it.isNotBlank() }
            ?: "Request failed with status code ${r.status}"
    }
    /** the MFA / password sign-in fetch errors (authMfa.js, authMfaFlow.js); no answer = fetch's own "Failed to fetch" */
    fun fetchErr(r: R, fallback: String): String {
        if (r.status == 0) return "Failed to fetch"
        val d = r.data
        return d.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: d.optString("detail").takeIf { it.isNotBlank() }
            ?: fallback
    }
}
