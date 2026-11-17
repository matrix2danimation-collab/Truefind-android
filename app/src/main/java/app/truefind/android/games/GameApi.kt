package app.truefind.android.games

import android.os.Handler
import android.os.Looper
import app.truefind.android.Api
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The games call the SAME Base44 backend functions as the web games (heartChase, moodChanger,
 * nameGuess, dressCodeAction, sendInvitation …) with the same payloads, so every rule and every
 * coin stays on the server. Test mode (emulator pictures on GitHub) answers from [GamesDemo].
 */
object GameApi {
    @Volatile var demo = false
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)

    fun call(fn: String, body: JSONObject, done: (Api.Result) -> Unit) {
        if (demo) { main.postDelayed({ done(GamesDemo.answer(fn, body)) }, 120); return }
        Api.invoke(fn, body, 30000, done)
    }

    /** Like [call], but a short server or network hiccup is tried again quietly (up to 3 times). */
    fun callRetry(fn: String, body: JSONObject, done: (Api.Result) -> Unit) {
        fun attempt(i: Int) {
            call(fn, body) { r ->
                val hiccup = r.status == 0 || r.status >= 500 || r.status == 408 || r.status == 429
                if (!r.ok && hiccup && i < 2) main.postDelayed({ attempt(i + 1) }, 700L * (i + 1))
                else done(r)
            }
        }
        attempt(0)
    }

    /** The web's errMsg(e): the server's "error" text, else [fallback]. */
    fun err(r: Api.Result, fallback: String = "Something went wrong. Please try again."): String {
        val d = r.data
        val e = d.optString("error").takeIf { it.isNotBlank() && it != "null" }
            ?: d.optString("message").takeIf { it.isNotBlank() && it != "null" }
        return when {
            e != null -> e
            r.status == 0 -> "Please check your internet and try again."
            else -> fallback
        }
    }

    // ── private profile photos need a signed link (web: resolveAvatar) ──
    private val signed = HashMap<String, Pair<String, Long>>()
    fun photo(uri: String?, done: (String) -> Unit) {
        val u = uri.orEmpty()
        if (u.isEmpty()) { done(""); return }
        if (u.startsWith("http://") || u.startsWith("https://")) { done(u); return }
        if (demo) { done(""); return }
        signed[u]?.let { (url, at) -> if (System.currentTimeMillis() - at < 30 * 60_000L) { done(url); return } }
        pool.execute {
            val r = post("/integration-endpoints/Core/CreateFileSignedUrl", JSONObject().put("file_uri", u))
            val url = r?.optString("signed_url").orEmpty()
            main.post {
                if (url.isNotEmpty()) signed[u] = url to System.currentTimeMillis()
                done(url)
            }
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        if (!Api.ready) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${Api.origin}/api/apps/${Api.appId}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Id", Api.appId)
                setRequestProperty("Authorization", "Bearer ${Api.token}")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Throwable) { null } finally { conn?.disconnect() }
    }
}

// ── small JSON helpers (the web reads optional fields everywhere) ──
fun JSONObject?.obj(k: String): JSONObject? = this?.optJSONObject(k)
fun JSONObject?.arr(k: String): JSONArray? = this?.optJSONArray(k)
fun JSONObject?.str(k: String, def: String = ""): String {
    if (this == null || !has(k) || isNull(k)) return def
    return optString(k, def)
}
fun JSONObject?.num(k: String, def: Double = 0.0): Double {
    if (this == null || !has(k) || isNull(k)) return def
    val v = opt(k)
    return when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: def; else -> def }
}
fun JSONObject?.int(k: String, def: Int = 0): Int = num(k, def.toDouble()).toInt()
fun JSONObject?.long(k: String, def: Long = 0L): Long = num(k, def.toDouble()).toLong()
fun JSONObject?.bool(k: String): Boolean = this != null && optBoolean(k, false)
fun JSONObject?.hasNum(k: String): Boolean = this != null && has(k) && !isNull(k) && opt(k) is Number
fun JSONArray?.ints(): List<Int> {
    if (this == null) return emptyList()
    val out = ArrayList<Int>(length())
    for (i in 0 until length()) { val v = opt(i); if (v is Number) out.add(v.toInt()) }
    return out
}
fun JSONArray?.objs(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}
fun JSONArray?.strs(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) { val v = opt(i); if (v != null && v != JSONObject.NULL) out.add(v.toString()) }
    return out
}
