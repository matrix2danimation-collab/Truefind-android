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
