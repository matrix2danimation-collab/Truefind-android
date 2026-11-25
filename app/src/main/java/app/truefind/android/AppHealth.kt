package app.truefind.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.Choreographer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * App Health (25 Nov): what this phone tells the Control Center → Health / Pages / Issues — quietly, with
 * very little data and no extra server request:
 *  - how often True Find was opened and how long it was on the screen (per day);
 *  - every page: opens, time on it, how fast it appeared (over [SLOW_OPEN_MS] counts as a slow open) and
 *    hangs (the app froze for [HANG_MS] or more while on it);
 *  - every problem met: a call that did not connect, dropped, or had no sound / picture; a message that did
 *    not send or took long; a payment the server could not finish; a crash; a freeze Android closed ("not
 *    responding"); a very slow page; a server that failed, timed out or could not be reached; no internet —
 *    each with the phone, Android version, app version and the network at that moment (Wi-Fi, or mobile data
 *    and the operator, e.g. Jio).
 * Never kept: anything a member writes or says — no message words, no photos, no call sound.
 *
 * The numbers wait in a small file and ride along with the presence heartbeat (SessionWork → updatePresence)
 * about every 5 minutes and when the app goes away ([takeBatch] / [ack]). A batch the server did not confirm is
 * sent again with the same id (the server counts it once). Everything here is cheap and never throws.
 */
object AppHealth {
    /** a page that appears later than this after the tap counts as a slow open */
    const val SLOW_OPEN_MS = 1200L
    /** a page this slow is also listed as a problem */
    const val VERY_SLOW_OPEN_MS = 3000L
    /** the app frozen this long is a hang */
    const val HANG_MS = 2500L
    /** a message that took this long to send is "slow" */
    const val MSG_SLOW_MS = 5000L
    /** a server answer this slow is listed */
    const val SERVER_SLOW_MS = 10_000L
    /** away from the screen this long, then back: a new "open" of the app */
    private const val NEW_OPEN_AFTER_MS = 30_000L
    /** numbers go with the heartbeat at most this often (problems that matter go with the next one) */
    private const val SEND_EVERY_MS = 5 * 60_000L
    private const val MAX_ISSUES = 120
    private const val PREFS = "tf_health"

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tf-health").apply { priority = Thread.MIN_PRIORITY } }
    private var prefs: SharedPreferences? = null
    private var app: Context? = null
    @Volatile var on = false; private set

    // ── what waits to be sent ──
    private class Day(var opens: Int = 0, var ms: Long = 0, var first: Long = 0, var last: Long = 0)
    private class Page(var opens: Int = 0, var ms: Long = 0, var slow: Int = 0, var hangs: Int = 0, var openSum: Long = 0, var openN: Int = 0, var openMax: Long = 0)
    private val days = HashMap<String, Day>()
    private val pages = HashMap<String, Page>()          // "day|page"
    private val issues = ArrayList<JSONObject>()
    /** the batch on its way (sent again, the same, until the server confirms it) */
    private var inflight: JSONObject? = null
    private var inflightAt = 0L
    private var lastSentAt = 0L
    private var urgent = false

    // ── where the app is now ──
    private var fg = false
    private var fgSince = 0L          // elapsedRealtime when the screen time last counted from
    private var lastBgAt = 0L         // wall time the app last went away
    private var openedOnce = false
    @Volatile var page = ""; private set
    private var pageSince = 0L        // elapsedRealtime

    // ═════════════ start ═════════════
    /** MainActivity.onCreate (once): the waiting numbers, the phone, and what Android says ended the app last time */
    fun init(ctx: Context) {
        if (on) return
        try {
            app = ctx.applicationContext
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            restore(p.getString("pending", null))
            page = p.getString("page", "").orEmpty()
            on = true
            io.execute { readExits(); device() }
        } catch (t: Throwable) { on = false }
    }

    // ═════════════ the app on / off the screen ═════════════
    /** MainActivity.onStart (true) / onStop (false) */
    fun foreground(v: Boolean) {
        if (!on) return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        synchronized(lock) {
            if (v == fg) return
            fg = v
            if (v) {
                if (!openedOnce || wall - lastBgAt > NEW_OPEN_AFTER_MS) {
                    day(wall).let { it.opens++; if (it.first == 0L) it.first = wall; it.last = wall }
                    openedOnce = true
                }
                fgSince = now
                if (page.isNotEmpty()) pageSince = now
            } else {
                countTime(now, wall)
                lastBgAt = wall
            }
        }
        if (v) watch(true) else { watch(false); save() }
    }

    /** the screen time since the last count goes to today, and to the page on screen */
    private fun countTime(now: Long, wall: Long) {
        if (fgSince > 0L && now > fgSince) {
            val d = day(wall)
            d.ms += minOf(now - fgSince, 6 * 3600_000L)
            d.last = wall
            if (d.first == 0L) d.first = wall
        }
        fgSince = now
        if (page.isNotEmpty() && pageSince > 0L && now > pageSince) pageAcc(wall, page).ms += minOf(now - pageSince, 6 * 3600_000L)
        pageSince = now
    }

    // ═════════════ pages ═════════════
    /** MainActivity: the page on screen changed (the app's own history). [path] e.g. "/explore", [search] "?tab=…" */
    fun page(path: String, search: String = "") {
        if (!on) return
        val name = nameOf(path, search)
        if (name.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        synchronized(lock) {
            if (name == page && now - pageSince < 1000) return
            if (fg && page.isNotEmpty() && pageSince > 0L && now > pageSince) pageAcc(wall, page).ms += minOf(now - pageSince, 6 * 3600_000L)
            page = name
            pageSince = now
            pageAcc(wall, name).opens++
        }
        try { prefs?.edit()?.putString("page", name)?.apply() } catch (e: Throwable) { }
        // how fast it appeared: the second frame after the move has been drawn
        val t0 = SystemClock.uptimeMillis()
        try {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { opened(name, SystemClock.uptimeMillis() - t0) }
            }
        } catch (e: Throwable) { }
    }

    private fun opened(name: String, ms: Long) {
        val wall = System.currentTimeMillis()
        synchronized(lock) {
            val p = pageAcc(wall, name)
            p.openSum += ms; p.openN++; if (ms > p.openMax) p.openMax = ms
            if (ms >= SLOW_OPEN_MS) p.slow++
        }
        if (ms >= VERY_SLOW_OPEN_MS) issue("page", "page_slow", code = "OPEN_SLOW", detail = "Took ${String.format(Locale.US, "%.1f", ms / 1000.0)} s to appear", pageName = name)
    }

    /** a readable page name: "Home", "Explore", "Chat room" … (ids left out) */
    fun nameOf(path: String, search: String = ""): String {
        val p = path.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        val q = if (search.isEmpty() && path.contains('?')) path.substringAfter('?') else search
        val known = Perf.pageName(p, q)
        // (the Recent tab is "Call Back" since 20 Nov; a call is one screen whatever its id)
        if (known == "Recent") return "Call Back"
        if (p.startsWith("/call/") || p == "/demo-call") return "Call screen"
        if (known != p) return known
        // other pages: the address without ids (long letters-and-digits parts)
        return p.split('/').filter { it.isNotEmpty() }.joinToString("/", prefix = "/") { s ->
            if (s.length >= 12 && s.any { it.isDigit() }) ":id" else s
        }.take(60)
    }

    // ═════════════ hangs: the app frozen ═════════════
    @Volatile private var watching = false
    @Volatile private var pongAt = 0L
    private val pong = Runnable { pongAt = SystemClock.uptimeMillis() }
    private var watcher: Thread? = null

    private fun watch(v: Boolean) {
        if (!v) { watching = false; return }
        if (watching) return
        watching = true
        pongAt = SystemClock.uptimeMillis()
        val t = Thread({
            var frozenSince = 0L
            while (watching) {
                main.post(pong)
                try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                val now = SystemClock.uptimeMillis()
                val since = now - pongAt
                if (since >= HANG_MS) {
                    if (frozenSince == 0L) frozenSince = pongAt
                } else if (frozenSince > 0L) {
                    // it moves again: how long it was frozen (from the last sign of life to this one)
                    hung(pongAt - frozenSince)
                    frozenSince = 0L
                }
            }
        }, "tf-health-watch")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        watcher = t
        t.start()
    }

    private fun hung(ms: Long) {
        if (ms < HANG_MS) return
        val name = page
        synchronized(lock) { if (name.isNotEmpty()) pageAcc(System.currentTimeMillis(), name).hangs++ }
        issue("hang", "hang", code = "FROZE", detail = "The app froze for ${String.format(Locale.US, "%.1f", ms / 1000.0)} s", pageName = name)
    }

    // ═════════════ problems ═════════════
    /**
     * One problem. [area]: call | message | payment | crash | hang | page | server | network.
     * The same problem again (kind, code, page, ref) before it is sent is counted on it.
     */
    fun issue(area: String, kind: String, code: String = "", detail: String = "", ref: String = "", pageName: String? = null,
              at: Long = System.currentTimeMillis(), n: Int = 1, now: Boolean = false) {
        if (!on) return
        try {
            val pg = (pageName ?: page).take(60)
            val net = netNow()
            synchronized(lock) {
                val same = issues.firstOrNull {
                    it.optString("kind") == kind && it.optString("code") == code && it.optString("page") == pg && it.optString("ref") == ref &&
                        it.optString("net") == net.first && at - it.optLong("at") < 30 * 60_000L
                }
                if (same != null) {
                    same.put("n", same.optInt("n", 1) + n)
                    if (detail.isNotEmpty()) same.put("detail", clean(detail, 300))
                } else {
                    if (issues.size >= MAX_ISSUES) issues.removeAt(0)
                    issues.add(JSONObject().put("area", area).put("kind", kind).put("code", clean(code, 80)).put("detail", clean(detail, 300))
                        .put("ref", ref.take(80)).put("page", pg).put("at", at).put("n", n)
                        .put("net", net.first).put("op", net.second).put("app", appVersion()))
                }
                if (now) urgent = true
            }
            if (now) save()
        } catch (e: Throwable) { }
    }

    /** a call problem (CallMedia): kind call_failed | call_dropped | call_no_audio | call_no_video */
    fun call(kind: String, callId: String, video: Boolean, code: String, detail: String = "") =
        issue("call", kind, code = code, detail = (if (video) "Video call" else "Voice call") + (if (detail.isNotEmpty()) " · $detail" else ""), ref = callId, now = true)

    /** ChatRoom: a message sent (or not): [ms] from Send to the server's answer */
    fun message(r: Api.Result, ms: Long, kind: String) {
        if (!on) return
        val what = when (kind) { "photo" -> "Photo"; "gif" -> "GIF"; "voice" -> "Voice note"; else -> "Message" }
        if (!r.ok) {
            // the server refused on purpose (no coins, blocked, a phone number in the text …): not a problem of the app
            if (r.status in 400..499 && r.status != 408 && r.status != 429) return
            val code = if (r.status == 0) "NO_CONNECTION" else if (r.status == 429) "BUSY" else "HTTP_${r.status}"
            issue("message", "msg_failed", code = code, detail = "$what did not send" + (if (r.status == 0) " (no connection)" else " (${r.status})"), now = true)
        } else if (ms >= MSG_SLOW_MS) {
            issue("message", "msg_slow", code = "SLOW", detail = "$what took ${String.format(Locale.US, "%.1f", ms / 1000.0)} s to send")
        }
    }

    // payments: the answers of the server's Google Play steps (the payment code itself is not touched: Api tells)
    private val PAY_FUNCTIONS = setOf("checkPlayPurchase", "verifyPlayPurchase")

    /** Api: the final answer of a server function (after its own retries) */
    fun answer(name: String, r: Api.Result) {
        if (!on || name !in PAY_FUNCTIONS || r.ok) return
        // "may I buy" refused for a reason (offer used, payments not open yet): only real failures count
        if (name == "checkPlayPurchase" && r.status in 400..499 && r.status != 408 && r.status != 429) return
        // the server's short reason (payments_not_ready, google_busy, cancelled …), never its sentence
        val err = r.data.optString("code").ifEmpty { r.data.optString("error_code") }
            .ifEmpty { r.data.optString("error").takeIf { Regex("^[a-z][a-z_]{2,39}$").matches(it) }.orEmpty() }.take(40)
        val code = (if (r.status == 0) "NO_CONNECTION" else "HTTP_${r.status}") + (if (err.isNotEmpty()) " $err" else "")
        if (name == "verifyPlayPurchase" && r.status == 409)
            issue("payment", "payment_failed", code = code, detail = "Google says this payment was cancelled: ${r.error.orEmpty().take(160)}", now = true)
        else if (name == "verifyPlayPurchase")
            issue("payment", "payment_not_added", code = code, detail = "Paid at Google, but the server could not add it yet: ${r.error.orEmpty().take(160)}", now = true)
        else
            issue("payment", "payment_start_failed", code = code, detail = "The payment could not start: ${r.error.orEmpty().take(160)}", now = true)
    }

    /** Api: one request's outcome (each try): no internet, a server that could not be reached / timed out / was very slow */
    fun http(path: String, code: Int, ms: Long, err: Throwable?, body: JSONObject?) {
        if (!on) return
        val fn = path.substringBefore('?').let { if (it.startsWith("/functions/")) it.removePrefix("/functions/") else it.substringAfter("/entities/").substringBefore('/').ifEmpty { it } }.take(40)
        when {
            code == 0 && err != null -> {
                val online = netNow().first.let { it != "none" && it.isNotEmpty() }
                when {
                    !online -> issue("network", "no_internet", code = fn, detail = "No internet on the phone")
                    err is java.net.SocketTimeoutException -> issue("server", "server_timeout", code = fn, detail = "No answer in ${ms / 1000} s")
                    err is java.net.UnknownHostException -> issue("network", "no_internet", code = fn, detail = "The server's name could not be found (network / DNS)")
                    else -> issue("server", "server_down", code = fn, detail = "${err.javaClass.simpleName}: ${err.message.orEmpty().take(120)}")
                }
            }
            // the server itself writes down its own errors (500); what it can't: the front door failing
            code == 502 || code == 504 || code >= 520 -> issue("server", "server_down", code = "$fn $code", detail = "The server could not be reached ($code)")
            code == 503 && body?.optString("error") != "maintenance" && body?.optString("code") != "maintenance" ->
                issue("server", "server_down", code = "$fn 503", detail = "The server was not available (503)")
            code in 200..299 && ms >= SERVER_SLOW_MS -> issue("server", "server_slow", code = fn, detail = "Answered after ${ms / 1000} s")
        }
    }

    /** CrashNote: the crash written down before the app closed last time (sent once signed in) */
    fun crash(o: JSONObject) {
        val msg = o.optString("message")
        val cls = Regex("Android: ([\\w.$]+)").find(msg)?.groupValues?.get(1)?.substringAfterLast('.')?.take(60).orEmpty()
        val stack = o.optString("stack")
        // where in our code: the first line of True Find's own code in the stack
        val where = stack.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("at app.truefind") }?.removePrefix("at app.truefind.android.")?.take(120).orEmpty()
        issue("crash", "crash", code = cls.ifEmpty { "CRASH" }, detail = (msg.removePrefix("Android: ").take(200) + if (where.isNotEmpty()) " · at $where" else ""),
            pageName = nameOf(o.optString("page").ifEmpty { "/" }), at = o.optLong("at", System.currentTimeMillis()), now = true)
    }

    // what Android says ended the app last time (Android 11+): frozen and closed ("not responding"), or a crash in
    // the phone's own code. The first time ever, only remembered (an old history is not reported).
    private fun readExits() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val ctx = app ?: return
            val p = prefs ?: return
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            val seen = p.getLong("exit_seen", -1L)
            var newest = maxOf(seen, 0L)
            for (e in list) {
                if (e.timestamp > newest) newest = e.timestamp
                if (seen < 0L || e.timestamp <= seen) continue
                val lastPage = p.getString("page", "").orEmpty()
                when (e.reason) {
                    ApplicationExitInfo.REASON_ANR -> issue("hang", "anr", code = "ANR", detail = "The app froze and Android closed it" + (e.description?.let { " · ${it.take(160)}" } ?: ""), pageName = lastPage, at = e.timestamp, now = true)
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> issue("crash", "crash_native", code = "NATIVE", detail = "Crash in the phone's own code" + (e.description?.let { " · ${it.take(160)}" } ?: ""), pageName = lastPage, at = e.timestamp, now = true)
                    ApplicationExitInfo.REASON_LOW_MEMORY -> if (e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                        issue("crash", "crash_memory", code = "LOW_MEMORY", detail = "Android closed the app on screen for lack of memory", pageName = lastPage, at = e.timestamp)
                }
            }
            p.edit().putLong("exit_seen", newest).apply()
        } catch (t: Throwable) { }
    }

    // ═════════════ the batch for the heartbeat ═════════════
    /**
     * SessionWork: the numbers to send with this heartbeat, or null (nothing yet / sent a moment ago).
     * [leaving]: the app is going away — everything goes now.
     */
    fun takeBatch(leaving: Boolean): JSONObject? {
        if (!on || Demo.on) return null
        return try {
            val now = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            synchronized(lock) {
                inflight?.let { b ->
                    // the last batch was not confirmed: the same one again (not more often than every 50 s)
                    return if (leaving || wall - inflightAt > 50_000L) { inflightAt = wall; b } else null
                }
                if (!leaving && !urgent && wall - lastSentAt < SEND_EVERY_MS) return null
                if (fg) countTime(now, wall)
                if (days.isEmpty() && pages.isEmpty() && issues.isEmpty()) return null
                val b = JSONObject().put("bid", java.util.UUID.randomUUID().toString().replace("-", "").take(24))
                b.put("dev", JSONObject().put("model", model()).put("android", android()).put("app", appVersion()).put("net", netNow().first).put("op", netNow().second))
                val da = JSONArray()
                for ((k, d) in days) if (d.opens > 0 || d.ms >= 1000) da.put(JSONObject().put("day", k).put("opens", d.opens).put("secs", d.ms / 1000).put("first", d.first).put("last", d.last))
                val pa = JSONArray()
                for ((k, p) in pages) {
                    if (p.opens == 0 && p.ms < 1000 && p.slow == 0 && p.hangs == 0) continue
                    pa.put(JSONObject().put("day", k.substringBefore('|')).put("page", k.substringAfter('|')).put("opens", p.opens).put("secs", p.ms / 1000)
                        .put("slow", p.slow).put("hangs", p.hangs).put("ms_sum", p.openSum).put("ms_n", p.openN).put("ms_max", p.openMax))
                }
                val ia = JSONArray(); for (i in issues) ia.put(i)
                b.put("days", da).put("pages", pa).put("issues", ia)
                // the parts that did not fill a whole second stay for the next batch
                days.values.forEach { it.opens = 0; it.ms %= 1000 }
                days.entries.removeAll { it.value.ms == 0L }
                pages.clear(); issues.clear(); urgent = false
                inflight = b; inflightAt = wall
                b
            }.also { save() }
        } catch (t: Throwable) { null }
    }

    /** SessionWork: the server's answer for [batch] */
    fun ack(batch: JSONObject, ok: Boolean) {
        synchronized(lock) {
            if (inflight !== batch) return
            if (ok) { inflight = null; lastSentAt = System.currentTimeMillis() }
        }
        if (ok) save()
    }

    // ═════════════ kept on the phone between app starts ═════════════
    private fun save() {
        val snap = try { synchronized(lock) { snapshot() } } catch (t: Throwable) { return }
        io.execute { try { prefs?.edit()?.putString("pending", snap)?.apply() } catch (t: Throwable) { } }
    }
    /** CrashNote: the app is about to close (written at once) */
    fun saveNow() {
        try { val s = synchronized(lock) { if (fg) countTime(SystemClock.elapsedRealtime(), System.currentTimeMillis()); snapshot() }; prefs?.edit()?.putString("pending", s)?.commit() } catch (t: Throwable) { }
    }
    private fun snapshot(): String {
        val o = JSONObject()
        val da = JSONObject(); for ((k, d) in days) da.put(k, JSONArray().put(d.opens).put(d.ms).put(d.first).put(d.last))
        val pa = JSONObject(); for ((k, p) in pages) pa.put(k, JSONArray().put(p.opens).put(p.ms).put(p.slow).put(p.hangs).put(p.openSum).put(p.openN).put(p.openMax))
        val ia = JSONArray(); for (i in issues) ia.put(i)
        o.put("days", da).put("pages", pa).put("issues", ia).put("sent", lastSentAt)
        inflight?.let { o.put("inflight", it) }
        return o.toString()
    }
    private fun restore(s: String?) {
        if (s.isNullOrEmpty()) return
        try {
            val o = JSONObject(s)
            synchronized(lock) {
                o.optJSONObject("days")?.let { d -> d.keys().forEach { k -> d.optJSONArray(k)?.let { a -> days[k] = Day(a.optInt(0), a.optLong(1), a.optLong(2), a.optLong(3)) } } }
                o.optJSONObject("pages")?.let { d -> d.keys().forEach { k -> d.optJSONArray(k)?.let { a -> pages[k] = Page(a.optInt(0), a.optLong(1), a.optInt(2), a.optInt(3), a.optLong(4), a.optInt(5), a.optLong(6)) } } }
                o.optJSONArray("issues")?.let { a -> for (i in 0 until minOf(a.length(), MAX_ISSUES)) a.optJSONObject(i)?.let { issues.add(it) } }
                lastSentAt = o.optLong("sent")
                inflight = o.optJSONObject("inflight")
                if (inflight != null || issues.isNotEmpty()) urgent = true
            }
        } catch (t: Throwable) { }
    }

    // ═════════════ the phone and its network ═════════════
    private fun day(wall: Long): Day = days.getOrPut(dayOf(wall)) { Day() }
    private fun pageAcc(wall: Long, name: String): Page = pages.getOrPut(dayOf(wall) + "|" + name) { Page() }
    // (one formatter, used under its own lock: ThreadLocal.withInitial needs Android 8, the app runs from Android 7)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
    private fun dayOf(wall: Long): String = synchronized(dayFmt) { dayFmt.format(Date(wall)) }

    @Volatile private var modelName = ""
    private fun device() { model() }
    fun model(): String {
        if (modelName.isNotEmpty()) return modelName
        val make = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
        val m = Build.MODEL.orEmpty().trim()
        modelName = (if (m.lowercase().startsWith(make.lowercase())) m else "$make $m").trim().take(80)
        return modelName
    }
    fun android(): String = "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})"
    fun appVersion(): String = BuildConfig.VERSION_NAME + if (BuildConfig.PREVIEW) "-preview" else ""

    /** (network, operator) now: ("wifi" | "mobile" | "ethernet" | "none", "Jio" | "Airtel" | "Vi" | …) */
    fun netNow(): Pair<String, String> {
        val ctx = app ?: return "" to ""
        val net = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (t: Throwable) { "" }
        val op = try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            operator(tm.networkOperatorName.orEmpty().ifEmpty { tm.simOperatorName.orEmpty() })
        } catch (t: Throwable) { "" }
        return net to op
    }
    /** India's operators by one name ("JIO 4G", "Jio True5G" → "Jio"; "Vodafone IN", "Vi India", "IDEA" → "Vi" …) */
    fun operator(raw: String): String {
        val s = raw.trim().lowercase()
        return when {
            s.isEmpty() -> ""
            s.contains("jio") -> "Jio"
            s.contains("airtel") -> "Airtel"
            s == "vi" || s.startsWith("vi ") || s.contains("vodafone") || s.contains("idea") || s.contains("vi india") -> "Vi"
            s.contains("bsnl") || s.contains("cellone") -> "BSNL"
            s.contains("mtnl") || s.contains("dolphin") -> "MTNL"
            else -> raw.trim().take(30)
        }
    }

    private fun clean(s: String, n: Int): String = s.replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(n)
}
