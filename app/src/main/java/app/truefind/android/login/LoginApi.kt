package app.truefind.android.login

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import app.truefind.android.Api
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * The SAME Base44 calls the web sign-in pages make, from the phone:
 *  - backend functions (sendPhoneOtp, verifyPhoneOtp, checkPhoneLogin, createMyProfile, updateMyProfile, support)
 *    work before sign-in too, exactly like the web's base44.functions.invoke;
 *  - Base44's own auth routes (/auth/login, /register, /verify-otp, /resend-otp, /reset-password-request,
 *    /reset-password, /mfa/…), who-am-I (/entities/User/me) and the app's sign-in settings (login-info).
 * The web page's login (its token, and its cookies) goes with every call, so both stay one sign-in.
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
    /** the address of the page the person is on (the SDK sends it as X-Origin-URL) */
    @Volatile var here = ""

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** the token to send ("" = none): an explicit one (MFA steps) or the shared sign-in */
    private fun bearer(explicit: String?) = explicit ?: Api.token

    private fun waitAppId(): String {
        var n = 0
        while (Api.appId.isEmpty() && n < 40) { Thread.sleep(250); n++ }
        return Api.appId
    }

    private fun send(method: String, url: String, body: String?, token: String, extra: Map<String, String> = emptyMap(), timeoutMs: Int = 30000, json: Boolean = false): R {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                if (Api.appId.isNotEmpty()) setRequestProperty("X-App-Id", Api.appId)
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Origin-URL", here.ifEmpty { Api.origin + "/welcome" })
                try { CookieManager.getInstance().getCookie(Api.origin)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) } } catch (e: Exception) { }
                for ((k, v) in extra) setRequestProperty(k, v)
                if (body != null || json) setRequestProperty("Content-Type", "application/json")
                if (body != null || method == "POST") doOutput = true
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            else if (method == "POST") conn.outputStream.close() // an empty POST (Content-Length: 0)
            val code = conn.responseCode
            // cookies the server sets (the web's fetch keeps them: credentials "include") go to the web page too
            try {
                val cm = CookieManager.getInstance()
                conn.headerFields?.entries?.filter { it.key != null && it.key.equals("Set-Cookie", true) }?.flatMap { it.value }?.forEach { cm.setCookie(Api.origin, it) }
            } catch (e: Exception) { }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                val t = text.trim()
                if (t.startsWith("[")) JSONObject().put("items", JSONArray(t)) else if (t.startsWith("{")) JSONObject(t) else JSONObject()
            } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) android.util.Log.w("TFLogin", "$method ${url.substringBefore('?').substringAfter("/api/")} → $code ${text.take(160)}")
            R(code, json, text)
        } catch (e: Exception) {
            R(0, JSONObject(), "")
        } finally { conn?.disconnect() }
    }

    private fun run(work: () -> R, done: (R) -> Unit) { pool.execute { val r = work(); main.post { done(r) } } }

    /** base44.functions.invoke(name, payload) */
    fun fn(name: String, payload: JSONObject, token: String? = null, done: (R) -> Unit) {
        demo?.let { d -> main.postDelayed({ done(d("fn", name, payload)) }, 350); return }
        run({
            val id = waitAppId()
            if (id.isEmpty()) R(0, JSONObject(), "") else send("POST", "${Api.origin}/api/apps/$id/functions/$name", payload.toString(), bearer(token))
        }, done)
    }

    /** Base44's auth routes: POST /api/apps/{id}/auth/{path} (or GET), with an optional MFA token */
    fun auth(path: String, body: JSONObject?, token: String? = "", method: String = "POST", extra: Map<String, String> = emptyMap(), noBody: Boolean = false, done: (R) -> Unit) {
        demo?.let { d -> main.postDelayed({ done(d("auth", path, body)) }, 350); return }
        run({
            val id = waitAppId()
            if (id.isEmpty()) R(0, JSONObject(), "") else send(method, "${Api.origin}/api/apps/$id/auth/$path", body?.toString() ?: (if (method == "POST" && !noBody) "{}" else null), token ?: "", extra, json = noBody && method == "POST")
        }, done)
    }

    /** auth.me(): the signed-in user, or null (status 0 = no internet) */
    fun me(done: (JSONObject?, Int) -> Unit) {
        demo?.let { d -> main.postDelayed({ val r = d("me", "", null); done(if (r.ok) r.data else null, r.status) }, 250); return }
        run({
            val id = waitAppId()
            if (id.isEmpty()) R(0, JSONObject(), "") else send("GET", "${Api.origin}/api/apps/$id/entities/User/me", null, bearer(null), timeoutMs = 12000)
        }) { r ->
            val u = r.data
            done(if (r.ok && (u.optString("id").isNotEmpty() || u.optString("email").isNotEmpty())) u else null, r.status)
        }
    }

    /** auth.updateMe(data) */
    fun updateMe(data: JSONObject, done: (R) -> Unit) {
        demo?.let { d -> main.post { done(d("updateMe", "", data)) }; return }
        run({ send("PUT", "${Api.origin}/api/apps/${waitAppId()}/entities/User/me", data.toString(), bearer(null)) }, done)
    }

    /** entities.UserProfile.filter(query) */
    fun profiles(query: JSONObject, limit: Int = 0, done: (R) -> Unit) {
        demo?.let { d -> main.postDelayed({ done(d("profiles", "", query)) }, 250); return }
        val q = "?q=" + URLEncoder.encode(query.toString(), "UTF-8") + (if (limit > 0) "&limit=$limit" else "")
        run({ send("GET", "${Api.origin}/api/apps/${waitAppId()}/entities/UserProfile$q", null, bearer(null)) }, done)
    }

    /** the app's sign-in settings (name, auth_config with mfa_policy and the sign-in methods) */
    fun loginInfo(done: (R) -> Unit) {
        demo?.let { d -> main.post { done(d("loginInfo", "", null)) }; return }
        run({ send("GET", "${Api.origin}/api/apps/public/login-info/by-id/${waitAppId()}", null, "") }, done)
    }

    /** a plain GET of a page of the app (text) */
    fun page(path: String, done: (R) -> Unit) {
        demo?.let { d -> main.post { done(d("page", path, null)) }; return }
        run({ send("GET", Api.origin + path, null, "", mapOf("Accept" to "text/html")) }, done)
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
