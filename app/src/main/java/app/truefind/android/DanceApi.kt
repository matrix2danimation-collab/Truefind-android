package app.truefind.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Live Dance talks to the SAME backend function as the web page: "liveDance"
 * (and "liveDanceToken" for the video pass). Login is the web page's (Api).
 * Tests can put a fake server in [fake].
 */
object DanceApi {
    @Volatile var fake: ((String, JSONObject) -> Api.Result)? = null
    private val fakePool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class Res(val r: Api.Result) {
        val data: JSONObject get() = r.data
        val status: Int get() = r.status
        /** ok = 2xx and no { error } inside */
        val ok: Boolean get() = r.ok && !(r.data.has("error") && r.data.optString("error").isNotBlank())
        val code: String get() = r.data.optString("error", "")
        val noCoins: Boolean get() = code == "insufficient_coins"
        fun text(fallback: String = "Something went wrong. Try again."): String {
            val d = r.data
            return when {
                code == "need_entry" -> "Pay the entry fee to join first."
                code == "banned" -> "Your account can't use Live Dance."
                code == "insufficient_coins" -> "You don't have enough coins."
                d.optString("reason").isNotBlank() -> d.optString("reason")
                code.isNotBlank() -> code
                r.status == 0 -> r.error ?: "Please check your internet and try again."
                else -> r.error ?: fallback
            }
        }
    }

    fun call(action: String, body: JSONObject = JSONObject(), done: (Res) -> Unit) {
        val payload = JSONObject(body.toString()).put("action", action)
        fn("liveDance", payload) { done(Res(it)) }
    }

    fun token(roomId: String, done: (Res) -> Unit) = fn("liveDanceToken", JSONObject().put("room_id", roomId)) { done(Res(it)) }

    private fun fn(name: String, payload: JSONObject, done: (Api.Result) -> Unit) {
        val f = fake
        if (f != null) {
            fakePool.execute {
                val r = try { f(name, payload) } catch (e: Exception) { Api.Result(500, JSONObject().put("error", "fake: ${e.message}"), "fake failed") }
                main.post { done(r) }
            }
            return
        }
        Api.invoke(name, payload, done = done)
    }

    // ── private photos: a stored file → a short-lived signed address (resolveAvatar in the web) ──
    private val signed = HashMap<String, Pair<String, Long>>()
    private val signPool = Executors.newFixedThreadPool(2)
    /** null → no picture */
    fun photoUrl(src: String, done: (String?) -> Unit) {
        if (src.isBlank()) { done(null); return }
        if (src.startsWith("http://") || src.startsWith("https://")) { done(src); return }
        synchronized(signed) { signed[src]?.let { if (System.currentTimeMillis() - it.second < 4 * 60_000L) { done(it.first); return } } }
        if (fake != null || !Api.ready) { done(null); return }
        signPool.execute {
            var url: String? = null
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("${Api.origin}/api/apps/${Api.appId}/integration-endpoints/Core/CreateFileSignedUrl").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-App-Id", Api.appId)
                    setRequestProperty("Authorization", "Bearer ${Api.token}")
                }
                conn.outputStream.use { it.write(JSONObject().put("file_uri", src).toString().toByteArray()) }
                if (conn.responseCode in 200..299) {
                    val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    url = j.optString("signed_url").takeIf { it.startsWith("http") }
                }
            } catch (e: Exception) { } finally { conn?.disconnect() }
            if (url != null) synchronized(signed) { signed[src] = url!! to System.currentTimeMillis() }
            main.post { done(url) }
        }
    }
}

/**
 * Photos for Live Dance: round faces and wide "cover" pictures (stage, cards),
 * decoded already shrunk to the size they are shown at, kept in memory.
 */
object DanceImages {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** [px] = longest side wanted; [round] = square centre crop, made round */
    fun load(src: String, px: Int, round: Boolean, done: (Bitmap?) -> Unit) {
        if (src.isBlank() || px <= 0) { done(null); return }
        val key = "${if (round) "r" else "c"}$px|$src"
        cache.get(key)?.let { done(it); return }
        if (synchronized(failed) { failed.contains(src) }) { done(null); return }
        waiting[key]?.let { it.add(done); return }
        waiting[key] = mutableListOf(done)
        DanceApi.photoUrl(src) { url ->
            if (url == null) { finish(key, src, null); return@photoUrl }
            pool.execute {
                val bmp = fetch(url, px, round)
                main.post { finish(key, src, bmp) }
            }
        }
    }

    fun cached(src: String, px: Int, round: Boolean): Bitmap? = if (src.isBlank()) null else cache.get("${if (round) "r" else "c"}$px|$src")

    private fun finish(key: String, src: String, bmp: Bitmap?) {
        val cbs = waiting.remove(key) ?: mutableListOf()
        if (bmp != null) cache.put(key, bmp) else synchronized(failed) { failed.add(src) }
        cbs.forEach { it(bmp) }
    }

    private fun fetch(url: String, px: Int, round: Boolean): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val minSide = minOf(o.outWidth, o.outHeight)
            var sample = 1
            while (minSide / (sample * 2) >= px) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (!round) {
                // keep the shape; at most [px] on the short side
                val scale = px.toFloat() / minOf(src.width, src.height)
                if (scale >= 1f) return src
                val out = Bitmap.createScaledBitmap(src, maxOf(1, (src.width * scale).toInt()), maxOf(1, (src.height * scale).toInt()), true)
                if (out != src) src.recycle()
                return out
            }
            val side = minOf(src.width, src.height)
            val sq = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val sx = (src.width - side) / 2; val sy = (src.height - side) / 2 // centre crop (object-fit: cover)
            Canvas(sq).drawBitmap(src, Rect(sx, sy, sx + side, sy + side), Rect(0, 0, px, px), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            src.recycle()
            Photos.circle(sq)
        } catch (e: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
