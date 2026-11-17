package app.truefind.android

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Calls the SAME Base44 backend functions the web app uses
 * (getOnlineNow, getWallet, startCall, follow, getAppConfig …).
 * Login is shared: the token and app id come from the Base44 page
 * running in the WebView (see WebBridge), so the user signs in once.
 */
object Api {
    @Volatile var origin: String = MainActivity.START_URL.trimEnd('/')
    @Volatile var appId: String = ""
    @Volatile var token: String = ""

    class Result(val status: Int, val data: JSONObject, val error: String?) {
        val ok get() = status in 200..299
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    val ready: Boolean get() = Demo.on || (appId.isNotEmpty() && token.isNotEmpty())

    fun invoke(name: String, payload: JSONObject = JSONObject(), timeoutMs: Int = 30000, done: (Result) -> Unit) {
        pool.execute {
            val r = call(name, payload, timeoutMs)
            main.post { done(r) }
        }
    }

    private fun call(name: String, payload: JSONObject, timeoutMs: Int): Result {
        if (Demo.on) return Demo.function(name, payload)
        return request("POST", "/functions/$name", payload.toString().toByteArray(Charsets.UTF_8), "application/json", timeoutMs)
    }

    /**
     * Any Base44 request of this app (same as the web SDK): path after /api/apps/{appId}.
     * The answer is wrapped: an array answer comes back as { "items": [...] }.
     */
    fun request(method: String, path: String, body: ByteArray?, contentType: String?, timeoutMs: Int = 30000): Result {
        if (!ready) return Result(0, JSONObject(), "Not signed in yet")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$origin/api/apps/$appId$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Id", appId)
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/json")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                val t = text.trim()
                if (t.startsWith("[")) JSONObject().put("items", org.json.JSONArray(t)) else JSONObject(t)
            } catch (e: Exception) { JSONObject() }
            val err = if (code in 200..299) null else
                (json.optString("error").takeIf { it.isNotBlank() } ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: "Something went wrong. Please try again.")
            Result(code, json, err)
        } catch (e: Exception) {
            Result(0, JSONObject(), "Please check your internet and try again.")
        } finally {
            conn?.disconnect()
        }
    }

    /** Runs [work] off the main thread and hands its result back on the main thread. */
    fun <T> async(work: () -> T, done: (T) -> Unit) {
        pool.execute { val r = work(); main.post { done(r) } }
    }

    /** entities.X.filter(query, sort, limit) — the records as a list. */
    fun filter(entity: String, query: JSONObject, sort: String? = null, limit: Int = 0, done: (Result) -> Unit) {
        if (Demo.on) { main.post { done(Demo.filter(entity, query)) }; return }
        val q = StringBuilder("?q=").append(java.net.URLEncoder.encode(query.toString(), "UTF-8"))
        if (sort != null) q.append("&sort=").append(java.net.URLEncoder.encode(sort, "UTF-8"))
        if (limit > 0) q.append("&limit=").append(limit)
        async({ request("GET", "/entities/$entity$q", null, null) }, done)
    }

    /** auth.me() — the signed-in user. */
    fun me(done: (Result) -> Unit) {
        if (Demo.on) { main.post { done(Demo.me()) }; return }
        async({ request("GET", "/entities/User/me", null, null) }, done)
    }

    /** integrations.Core.X(json) */
    fun core(endpoint: String, payload: JSONObject, done: (Result) -> Unit) {
        if (Demo.on) { main.post { done(Demo.core(endpoint, payload)) }; return }
        async({ request("POST", "/integration-endpoints/Core/$endpoint", payload.toString().toByteArray(Charsets.UTF_8), "application/json") }, done)
    }

    /** integrations.Core.UploadPrivateFile({ file }) — a photo from the phone. */
    fun uploadPrivate(bytes: ByteArray, fileName: String, mime: String, done: (Result) -> Unit) {
        if (Demo.on) { main.post { done(Result(200, JSONObject().put("file_uri", "demo://story"), null)) }; return }
        val boundary = "----TrueFind" + System.currentTimeMillis()
        val out = java.io.ByteArrayOutputStream()
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        async({ request("POST", "/integration-endpoints/Core/UploadPrivateFile", out.toByteArray(), "multipart/form-data; boundary=$boundary", 60000) }, done)
    }
}
