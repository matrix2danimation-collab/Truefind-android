package app.truefind.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Downloads profile photos once, decodes them already shrunk to the size
 * they are shown at (square, centre-cropped), and keeps them in memory.
 * At most 6 download at the same time (same rule as the web Explore).
 */
object Photos {
    private val pool = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private fun key(url: String, size: Int) = "$size|$url"

    fun get(url: String, size: Int): Bitmap? = cache.get(key(url, size))
    fun hasFailed(url: String) = synchronized(failed) { failed.contains(url) }

    /** Loads [urls] in order (small copy first, full photo if that fails). */
    fun load(urls: List<String>, size: Int, done: (Bitmap?) -> Unit) {
        val list = urls.filter { it.startsWith("http") && !hasFailed(it) }
        if (list.isEmpty()) { done(null); return }
        val first = list[0]
        val hit = get(first, size)
        if (hit != null) { done(hit); return }
        val k = key(first, size)
        val q = waiting[k]
        if (q != null) { q.add(done); return }
        waiting[k] = mutableListOf(done)
        pool.execute {
            val bmp = fetch(first, size)
            main.post {
                val cbs = waiting.remove(k) ?: mutableListOf()
                if (bmp != null) {
                    cache.put(k, bmp)
                    cbs.forEach { it(bmp) }
                } else {
                    synchronized(failed) { failed.add(first) }
                    val rest = list.drop(1)
                    cbs.forEach { cb -> if (rest.isEmpty()) cb(null) else load(rest, size, cb) }
                }
            }
        }
    }

    // private photos (file uri) → a signed address, asked once and remembered
    private val signed = HashMap<String, String>()
    private val signing = HashMap<String, MutableList<(String) -> Unit>>()

    /** The address to download [raw] from ("" = none). Private files are signed first (like resolveAvatar). */
    fun url(raw: String, done: (String) -> Unit) {
        if (raw.isBlank()) { done(""); return }
        if (raw.startsWith("http") || raw.startsWith("demo:")) { done(raw); return }
        signed[raw]?.let { done(it); return }
        signing[raw]?.let { it.add(done); return }
        signing[raw] = mutableListOf(done)
        Api.core("CreateFileSignedUrl", org.json.JSONObject().put("file_uri", raw).put("expires_in", 3600)) { r ->
            val u = r.data.optString("signed_url").ifBlank { r.data.optString("url") }
            if (u.isNotBlank()) signed[raw] = u
            signing.remove(raw)?.forEach { it(u) }
        }
    }

    /** Like [load], for raw photo values (small copy first); private files are signed first. */
    fun loadRaw(raws: List<String>, size: Int, done: (Bitmap?) -> Unit) {
        val list = raws.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) { done(null); return }
        url(list[0]) { u ->
            if (u.isNotBlank() && !u.startsWith("demo:")) {
                load(listOf(u), size) { b -> if (b != null || list.size == 1) done(b) else loadRaw(list.drop(1), size, done) }
            } else if (list.size > 1) loadRaw(list.drop(1), size, done) else done(null)
        }
    }

    /** A photo cut to fill a w × h box (object-fit: cover), not round — Secret Dating cards. */
    fun loadCover(raws: List<String>, w: Int, h: Int, done: (Bitmap?) -> Unit) {
        val list = raws.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) { done(null); return }
        url(list[0]) { u ->
            if (u.isBlank() || u.startsWith("demo:") || hasFailed(u)) { if (list.size > 1) loadCover(list.drop(1), w, h, done) else done(null); return@url }
            val k = "c${w}x$h|$u"
            cache.get(k)?.let { done(it); return@url }
            waiting[k]?.let { it.add(done); return@url }
            waiting[k] = mutableListOf(done)
            pool.execute {
                val b = fetchCover(u, w, h)
                main.post {
                    val cbs = waiting.remove(k) ?: mutableListOf()
                    if (b != null) { cache.put(k, b); cbs.forEach { it(b) } }
                    else {
                        synchronized(failed) { failed.add(u) }
                        cbs.forEach { cb -> if (list.size > 1) loadCover(list.drop(1), w, h, cb) else cb(null) }
                    }
                }
            }
        }
    }

    fun cachedCover(raws: List<String>, w: Int, h: Int): Bitmap? {
        for (r in raws) {
            if (r.isBlank()) continue
            val u = if (r.startsWith("http")) r else signed[r] ?: continue
            cache.get("c${w}x$h|$u")?.let { return it }
        }
        return null
    }

    private fun fetchCover(url: String, w: Int, h: Int): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000 }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val scaleNeeded = maxOf(w.toFloat() / o.outWidth, h.toFloat() / o.outHeight)
            var s = 1
            while (scaleNeeded * s * 2 <= 1f) s *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val k = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val cw = (w / k).toInt().coerceAtMost(src.width); val ch = (h / k).toInt().coerceAtMost(src.height)
            val sx = (src.width - cw) / 2; val sy = (src.height - ch) / 2
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            src.recycle()
            out
        } catch (e: Throwable) { null } finally { conn?.disconnect() }
    }

    /** Already downloaded (any of [raws])? */
    fun cached(raws: List<String>, size: Int): Bitmap? {
        for (r in raws) {
            if (r.isBlank()) continue
            val u = if (r.startsWith("http")) r else signed[r] ?: continue
            get(u, size)?.let { return it }
        }
        return null
    }

    /** A photo as it is (not cropped, not round), for the story viewer. */
    fun loadFull(raw: String, maxSide: Int, done: (Bitmap?) -> Unit) {
        url(raw) { u ->
            if (u.isBlank() || u.startsWith("demo:")) { done(null); return@url }
            pool.execute {
                val b = try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val bytes = c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1
                    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                } catch (e: Throwable) { null }
                main.post { done(b) }
            }
        }
    }

    /** Round copy of a square bitmap (all faces in True Find are round). */
    fun circle(sq: Bitmap): Bitmap {
        val n = minOf(sq.width, sq.height)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = android.graphics.BitmapShader(sq, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        Canvas(out).drawCircle(n / 2f, n / 2f, n / 2f, p)
        if (out != sq) sq.recycle()
        return out
    }

    private fun fetch(url: String, size: Int): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val minSide = minOf(opts.outWidth, opts.outHeight)
            if (minSide <= 0) return null
            var sample = 1
            while (minSide / (sample * 2) >= size) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            // centre crop to a square of [size] (object-fit: cover)
            val side = minOf(src.width, src.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val sx = (src.width - side) / 2
            val sy = (src.height - side) / 2
            Canvas(out).drawBitmap(src, Rect(sx, sy, sx + side, sy + side), Rect(0, 0, size, size),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            if (src != out) src.recycle()
            circle(out)
        } catch (e: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
