package app.truefind.android

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Calls the app's backend functions (getOnlineNow, getWallet, startCall, follow, getAppConfig …) and tables.
 * Since 24 Nov 2026 they run on Supabase ([Supa]): the "api" Edge Function answers the same addresses Base44
 * did, so the paths below are unchanged. The sign-in is Supabase Auth ([Auth]): [token] is renewed by itself
 * before it runs out, and a request the server answered with "session_expired" is asked once more with a new one.
 */
object Api {
    /** the website (truefindapp.com): web pages only — policies, invite and share links. The server is [base]. */
    @Volatile var origin: String = MainActivity.START_URL.trimEnd('/')
    @Volatile var appId: String = ""
    @Volatile var token: String = ""
    /** where every function and table of the app is asked: …/api/apps/<id> on Supabase's "api" function */
    val base: String get() = "${Supa.API}/api/apps/${appId.ifEmpty { Supa.APP_ID }}"

    class Result(val status: Int, val data: JSONObject, val error: String?) {
        val ok get() = status in 200..299
    }

    private val pool = Executors.newFixedThreadPool(3)
    /**
     * Its own lane for what the member is waiting for on screen right now (the Wallet, 21 Nov): the 3 lanes
     * above are shared by every page's reads and the background work (online pings, the number on Find …).
     * When the server answers slowly they can all be taken for up to 30 s each, and the Wallet's own asks
     * then waited in the queue behind them — "loading" for a long time with nothing wrong on the Wallet.
     */
    private val frontPool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    /**
     * What was read from the server (a page's people, coins, lists, pictures) goes back to the screen that
     * asked — but not in the middle of a page move: filling a page while it slides made the move stop half
     * way. It then waits the few tenths of a second until the move ends. Anything you do (a call, a message,
     * a payment, saving) is never held back.
     */
    private fun deliver(block: () -> Unit) { main.post { PageFx.whenStill(block) } }
    /** an action's answer: at once */
    private fun <T> act(work: () -> T, done: (T) -> Unit) { pool.execute { val r = work(); main.post { done(r) } } }

    val ready: Boolean get() = Demo.on || (appId.isNotEmpty() && token.isNotEmpty())

    fun invoke(name: String, payload: JSONObject = JSONObject(), timeoutMs: Int = 30000, done: (Result) -> Unit) =
        invokeOn(pool, name, payload, timeoutMs, done)
    /** the same as [invoke], on the front lane: for the page the member is looking at and waiting for */
    fun invokeFront(name: String, payload: JSONObject = JSONObject(), timeoutMs: Int = 30000, done: (Result) -> Unit) =
        invokeOn(frontPool, name, payload, timeoutMs, done)
    private fun invokeOn(ex: java.util.concurrent.ExecutorService, name: String, payload: JSONObject, timeoutMs: Int, done0: (Result) -> Unit) {
        // (App Health: the final answer of the Google Play steps tells whether a payment got stuck — AppHealth.answer)
        val done: (Result) -> Unit = { r -> AppHealth.answer(name, r); done0(r) }
        // reading something (getWallet, getOnlineNow, getChats …) is safe to ask again
        if (name.startsWith("get")) { readWithRetry({ call(name, payload, timeoutMs) }, done, 0, ex); return }
        if (name in NOT_AGAIN) { ex.execute { val r = call(name, payload, timeoutMs); main.post { done(r) } }; return }
        actWithBusyRetry({ call(name, payload, timeoutMs) }, done, 0, PATIENT[name] ?: 2, ex)
    }
    /**
     * Actions that wait out a busy server longer by themselves (more tries, each wait at most 5 s): a call must
     * ring if it possibly can (owner, 20 Nov: "Couldn't connect · The server is busy" again and again on Call Back).
     * Only when nothing was done yet (retry_safe) — the call is never started twice.
     */
    private val PATIENT = mapOf("startCall" to 4)
    /** actions that reach outside services first (an SMS, a payment order, an email): never asked again by themselves */
    private val NOT_AGAIN = setOf("sendPhoneOtp", "verifyPhoneOtp", "checkPhoneLogin", "whatsappCheck", "createPremiumCheckout",
        "createCreditsCheckout", "topUpWallet", "support", "deleteAccount", "adminPayouts")
    /** asked once: the screen asks again itself (a chat message with its own key) */
    fun invokeOnce(name: String, payload: JSONObject, timeoutMs: Int = 30000, done: (Result) -> Unit) {
        pool.execute { val r = call(name, payload, timeoutMs); main.post { done(r) } }
    }

    // ── a busy platform (20 Nov) ──
    // Base44 answers 429 "Rate limit exceeded … after N s" when the app's server calls together go over their
    // shared allowance. The functions now wait a little themselves and then answer { error: "busy",
    // retry_after, retry_safe }. An action is asked again by itself only when the server says nothing was
    // saved yet (retry_safe) — after the wait it named (1–10 s), at most twice; otherwise the member reads
    // "The server is busy. Please try again in a moment." (never "Something went wrong").
    private const val BUSY_TEXT = "The server is busy. Please try again in a moment."
    /** a server answer that means "too many requests right now" (not "the person is busy on a call": that is 409) */
    fun isServerBusy(code: Int, json: JSONObject, text: String = ""): Boolean = code == 429 &&
        (json.optString("error") == "busy" || json.optBoolean("rate_limited") || json.optBoolean("server_busy") ||
            Regex("rate.?limit|too many requests", RegexOption.IGNORE_CASE).containsMatchIn(json.optString("error") + " " + json.optString("message") + " " + text.take(300)))
    /** seconds the server asked to wait (1–10; 4 when it didn't say) */
    fun busyWaitMs(r: Result): Long = (r.data.optDouble("retry_after", 4.0).takeIf { !it.isNaN() } ?: 4.0).let { (it * 1000).toLong().coerceIn(1000L, 10_000L) }
    /** the same wait, spread (24 Nov): every phone told "busy, 6 s" asked again at the very same moment and met
     *  the busy server together — now each waits 100–150 % of it, a moment of its own */
    private fun spreadWaitMs(r: Result): Long = busyWaitMs(r).let { it + (Math.random() * it * 0.5).toLong() }
    private fun actWithBusyRetry(work: () -> Result, done: (Result) -> Unit, tries: Int = 0, most: Int = 2, ex: java.util.concurrent.ExecutorService = pool) {
        ex.execute {
            val r = work()
            main.post {
                val again = tries < most && ready && !Demo.on && r.status == 429 && r.data.optBoolean("server_busy") && r.data.optBoolean("retry_safe")
                if (again) main.postDelayed({ actWithBusyRetry(work, done, tries + 1, most, ex) }, if (most > 2) minOf(spreadWaitMs(r), 6500L) else spreadWaitMs(r))
                else done(r)
            }
        }
    }
    /** the answer of a busy platform, made readable on every screen: its words in "error", the old code kept */
    private fun markBusy(code: Int, json: JSONObject, text: String): JSONObject {
        if (!isServerBusy(code, json, text)) return json
        // the platform itself turned the request away (its own "Rate limit exceeded", not one of our functions'
        // answers): nothing ran on the server, so asking again later is always safe (20 Nov)
        val platform = !json.has("rate_limited") && json.optString("error") != "busy" && !json.optBoolean("server_busy")
        val said = json.optString("message").takeIf { it.isNotBlank() && it != "busy" && !platform }
        json.put("error_code", json.optString("error").ifBlank { "busy" })
        if (platform) json.put("platform_said", (json.optString("message").ifBlank { json.optString("detail") }.ifBlank { text }).take(160))
        json.put("error", said ?: BUSY_TEXT)
        json.put("server_busy", true)
        if (platform) json.put("retry_safe", true)
        if (!json.has("retry_after")) {
            val m = Regex("after\\s+(\\d+(?:\\.\\d+)?)\\s*s", RegexOption.IGNORE_CASE).find(json.optString("platform_said") + " " + text.take(300))
            json.put("retry_after", m?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.ceil(it).toInt() } ?: 4)
        }
        return json
    }

    // ── a read that failed for a moment is asked again ──
    // On the owner's phone the server sometimes answered a read with 500 (or not at all) and the next
    // try worked: the page showed "Try again" / an empty list for nothing. A read (never a payment, a call
    // or a message) is now asked again up to 2 times, 0.7 s and then 1.8 s later, before it counts as failed.
    private fun passing(r: Result) = r.status == 0 || r.status == 408 || r.status == 429 || r.status in 500..599
    private fun readWithRetry(work: () -> Result, done: (Result) -> Unit, tries: Int = 0, ex: java.util.concurrent.ExecutorService = pool) {
        ex.execute {
            val r = work()
            main.post {
                if (tries < 2 && ready && !Demo.on && passing(r)) main.postDelayed({ readWithRetry(work, done, tries + 1, ex) }, if (r.status == 429) spreadWaitMs(r) else if (tries == 0) 700L else 1800L)
                else PageFx.whenStill { done(r) }
            }
        }
    }

    private fun call(name: String, payload: JSONObject, timeoutMs: Int): Result {
        // a demo person's chat (testing only, SdDemoChat) is answered on the phone — nothing about it goes to the server
        SdDemoChat.function(name, payload)?.let { return it }
        if (Demo.on) return Demo.function(name, payload)
        return request("POST", "/functions/$name", payload.toString().toByteArray(Charsets.UTF_8), "application/json", timeoutMs)
    }

    /**
     * Any request of this app to the server (same as the web SDK): path after /api/apps/{appId}.
     * The answer is wrapped: an array answer comes back as { "items": [...] }.
     */
    fun request(method: String, path: String, body: ByteArray?, contentType: String?, timeoutMs: Int = 30000): Result {
        if (!ready) return Result(0, JSONObject(), "Not signed in yet")
        val first = requestOnce(method, path, body, contentType, timeoutMs)
        // the sign-in's hour was up (the phone slept past its renewal): a new one, then the same request once more
        if (expired(first) && Auth.renew(true)) return requestOnce(method, path, body, contentType, timeoutMs)
        return first
    }

    /** the server said the sign-in's time is up (401 "session_expired"): [Auth] makes a new one */
    private fun expired(r: Result) = r.status == 401 && r.data.optString("code") == "session_expired"

    private fun requestOnce(method: String, path: String, body: ByteArray?, contentType: String?, timeoutMs: Int): Result {
        count(method, path)
        val t0 = android.os.SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        return try {
            val bearer = Auth.fresh()
            conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Id", appId)
                setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/json")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            // a public function answered without the sign-in (its hour was up): the next request goes with a new one
            if (conn.getHeaderField("X-Session-Expired") == "1") pool.execute { Auth.renew(true) }
            val json = markBusy(code, try {
                val t = text.trim()
                if (t.startsWith("[")) JSONObject().put("items", org.json.JSONArray(t)) else JSONObject(t)
            } catch (e: Exception) { JSONObject() }, text)
            val err = if (code in 200..299) null else
                (json.optString("error").takeIf { it.isNotBlank() } ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: ("Something went wrong. Please try again." + tag(code, path)))
            if (code !in 200..299) { note(method, path, code, text); signal(code, json) }
            AppHealth.http(path, code, android.os.SystemClock.elapsedRealtime() - t0, null, json)
            Result(code, json, err)
        } catch (e: Exception) {
            AppHealth.http(path, 0, android.os.SystemClock.elapsedRealtime() - t0, e, null)
            Result(0, JSONObject(), "Please check your internet and try again.")
        } finally {
            conn?.disconnect()
        }
    }

    // ── what went wrong, for finding problems on a real phone ──
    // A server answer without its own message used to say only "Something went wrong": the
    // reason was lost. Now the code and the call's name go with it, e.g. "(429 · getWallet)":
    // 401 = sign-in not accepted, 429 = too many requests, 5xx = the server failed.
    private fun tag(code: Int, path: String) = " ($code · ${path.substringBefore('?').substringAfterLast('/')})"
    private val log = ArrayDeque<String>()
    /** the last failed server answers (newest last), each "time METHOD path → code: start of the answer" */
    val failures: List<String> get() = synchronized(log) { log.toList() }
    // kept on the phone (20 Nov): the list was empty after the app was closed, so a report taken later showed nothing
    @Volatile private var store: android.content.SharedPreferences? = null
    /** MainActivity: where the failed answers are kept between app starts */
    fun keepFailuresIn(ctx: android.content.Context) {
        if (store != null) return
        val sp = ctx.getSharedPreferences("tf_api_log", android.content.Context.MODE_PRIVATE)
        store = sp
        try {
            val a = org.json.JSONArray(sp.getString("failed", "[]"))
            synchronized(log) { log.clear(); for (i in 0 until a.length()) log.addLast(a.optString(i)); while (log.size > 40) log.removeFirst() }
        } catch (e: Exception) { }
    }
    private fun note(method: String, path: String, code: Int, text: String) {
        val t = java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "$t $method ${path.substringBefore('?')} → $code: ${text.replace(Regex("\\s+"), " ").take(160)}"
        android.util.Log.w("TFApi", line)
        val snap = synchronized(log) { log.addLast(line); while (log.size > 40) log.removeFirst(); log.toList() }
        try { store?.edit()?.putString("failed", org.json.JSONArray(snap).toString())?.apply() } catch (e: Exception) { }
    }

    // ── how many requests this phone sends (page speed report, 20 Nov): every request counts against the member's
    // own allowance on the platform, and every function's reads against the app's shared one ──
    private val sent = ArrayDeque<Pair<Long, String>>()
    private fun count(method: String, path: String) {
        val name = path.substringBefore('?').let { p -> if (p.startsWith("/functions/")) p.removePrefix("/functions/") else "$method ${p.substringAfter("/entities/").substringBefore('/')}" }
        val now = System.currentTimeMillis()
        synchronized(sent) { sent.addLast(now to name); while (sent.isNotEmpty() && now - sent.first().first > 10 * 60_000L) sent.removeFirst(); while (sent.size > 5000) sent.removeFirst() }
    }
    /** requests sent in the last [ms]: (total, by name, most first) */
    fun sentIn(ms: Long): Pair<Int, List<Pair<String, Int>>> {
        val now = System.currentTimeMillis()
        val l = synchronized(sent) { sent.filter { now - it.first <= ms } }
        return l.size to l.groupingBy { it.second }.eachCount().toList().sortedByDescending { it.second }
    }

    /** the server said this account is blocked (403 "banned") or the app is in maintenance (503): the status screens show it */
    private fun signal(code: Int, json: JSONObject) {
        val e = json.optString("error")
        if (code == 403 && e == "banned") AppStatusHooks.server("banned")
        else if (code == 503 && e == "maintenance") AppStatusHooks.server("maintenance")
    }

    /** Runs [work] off the main thread and hands its result back on the main thread. */
    fun <T> async(work: () -> T, done: (T) -> Unit) {
        pool.execute { val r = work(); deliver { done(r) } }
    }

    /** entities.X.filter(query, sort, limit) — the records as a list. */
    fun filter(entity: String, query: JSONObject, sort: String? = null, limit: Int = 0, done: (Result) -> Unit) {
        SdDemoChat.filter(entity, query)?.let { r -> deliver { done(r) }; return }
        if (Demo.on) { deliver { done(Demo.filter(entity, query)) }; return }
        val q = StringBuilder("?q=").append(java.net.URLEncoder.encode(query.toString(), "UTF-8"))
        if (sort != null) q.append("&sort=").append(java.net.URLEncoder.encode(sort, "UTF-8"))
        if (limit > 0) q.append("&limit=").append(limit)
        readWithRetry({ request("GET", "/entities/$entity$q", null, null) }, done)
    }

    /** entities.X.get(id) */
    fun get(entity: String, id: String, done: (Result) -> Unit) {
        SdDemoChat.filter(entity, JSONObject().put("id", id))?.let { r -> deliver { done(Result(r.status, r.data.optJSONArray("items")?.optJSONObject(0) ?: JSONObject(), r.error)) }; return }
        if (Demo.on) { deliver { done(Demo.filter(entity, JSONObject().put("id", id)).let { r -> Result(r.status, r.data.optJSONArray("items")?.optJSONObject(0) ?: JSONObject(), r.error) }) }; return }
        readWithRetry({ request("GET", "/entities/$entity/${java.net.URLEncoder.encode(id, "UTF-8")}", null, null) }, done)
    }

    /** entities.X.delete(id) */
    fun delete(entity: String, id: String, done: (Result) -> Unit) {
        if (Demo.on) { main.post { done(Result(200, JSONObject(), null)) }; return }
        act({ request("DELETE", "/entities/$entity/${java.net.URLEncoder.encode(id, "UTF-8")}", null, null) }, done)
    }

    /** entities.X.create(data) — a row of the member's own (the Typing table, 24 Nov) */
    fun create(entity: String, data: JSONObject, done: (Result) -> Unit = {}) {
        if (Demo.on) { main.post { done(Result(200, JSONObject(data.toString()).put("id", "demo-row"), null)) }; return }
        act({ requestOk("POST", "/entities/$entity", data) }, done)
    }

    /** entities.X.update(id, data) — a row of the member's own */
    fun update(entity: String, id: String, data: JSONObject, done: (Result) -> Unit = {}) {
        if (Demo.on) { main.post { done(Result(200, JSONObject(data.toString()).put("id", id), null)) }; return }
        act({ requestOk("PUT", "/entities/$entity/${java.net.URLEncoder.encode(id, "UTF-8")}", data) }, done)
    }

    /** entities.X.updateMany(query, data) — PATCH …/update-many (HttpURLConnection can't send PATCH: OkHttp does) */
    fun updateMany(entity: String, query: JSONObject, data: JSONObject, done: (Result) -> Unit = {}) {
        if (Demo.on || SdDemoChat.ownsQuery(query)) { main.post { done(Result(200, JSONObject(), null)) }; return }
        act({ requestOk("PATCH", "/entities/$entity/update-many", JSONObject().put("query", query).put("data", data)) }, done)
    }

    val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private fun requestOk(method: String, path: String, body: JSONObject?): Result {
        if (!ready) return Result(0, JSONObject(), "Not signed in yet")
        val first = requestOkOnce(method, path, body)
        if (expired(first) && Auth.renew(true)) return requestOkOnce(method, path, body)
        return first
    }

    private fun requestOkOnce(method: String, path: String, body: JSONObject?): Result {
        count(method, path)
        return try {
            val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
            val req = okhttp3.Request.Builder().url("$base$path").method(method, rb)
                .header("Accept", "application/json").header("X-App-Id", appId).header("Authorization", "Bearer ${Auth.fresh()}").build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                val json = markBusy(res.code, try { val t = text.trim(); if (t.startsWith("[")) JSONObject().put("items", org.json.JSONArray(t)) else JSONObject(t) } catch (e: Exception) { JSONObject() }, text)
                val err = if (res.isSuccessful) null else (json.optString("error").ifBlank { json.optString("message") }.ifBlank { "Something went wrong. Please try again." + tag(res.code, path) })
                if (!res.isSuccessful) { note(method, path, res.code, text); signal(res.code, json) }
                Result(res.code, json, err)
            }
        } catch (e: Exception) { Result(0, JSONObject(), "Please check your internet and try again.") }
    }

    /** auth.me() — the signed-in user. */
    fun me(done: (Result) -> Unit) {
        if (Demo.on) { deliver { done(Demo.me()) }; return }
        async({ request("GET", "/entities/User/me", null, null) }, done)
    }

    /** integrations.Core.X(json) */
    fun core(endpoint: String, payload: JSONObject, done: (Result) -> Unit) {
        if (Demo.on) { main.post { done(Demo.core(endpoint, payload)) }; return }
        act({ request("POST", "/integration-endpoints/Core/$endpoint", payload.toString().toByteArray(Charsets.UTF_8), "application/json") }, done)
    }

    /** integrations.Core.UploadPrivateFile({ file }) — a photo from the phone. */
    fun uploadPrivate(bytes: ByteArray, fileName: String, mime: String, done: (Result) -> Unit) {
        if (Demo.on) { main.post { done(Result(200, JSONObject().put("file_uri", "demo://story"), null)) }; return }
        val boundary = "----TrueFind" + System.currentTimeMillis()
        val out = java.io.ByteArrayOutputStream()
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        act({ request("POST", "/integration-endpoints/Core/UploadPrivateFile", out.toByteArray(), "multipart/form-data; boundary=$boundary", 60000) }, done)
    }
}
