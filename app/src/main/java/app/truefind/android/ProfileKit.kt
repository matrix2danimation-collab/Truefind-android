package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colours of the Profile, Edit Profile, Settings and Followers screens (pages/Profile.jsx C, N, F, TILE). */
object PC {
    // C
    val wine = Ui.c("#4C1D95"); val plum = Ui.c("#3A1478"); val wineSoft = Ui.c("#F1EAFB"); val wineLine = Ui.c("#DCCFF5")
    val find = Ui.c("#8C1466"); val line = Ui.c("#E7E0F2"); val faint = Ui.c("#F6F3FB"); val ink = Ui.c("#1E1433"); val sub = Ui.c("#5F5673")
    val green = Ui.c("#16A34A"); val red = Ui.c("#B91C1C")
    // N
    val head = wine; val headBtn = Ui.c("#5E31AD"); val deep = plum; val onHead = wineLine; val ground = faint; val tint = wineSoft
    val share = Ui.c("#E6DBF8"); val dash = Ui.c("#B9A3E6"); val dashSoft = Ui.c("#CDBDEB"); val text = Ui.c("#3B3350"); val online = Ui.c("#4ADE80")
    val amberBg = Ui.c("#FFF1D6"); val amber = Ui.c("#7A4B00"); val greenBg = Ui.c("#E3F5EA"); val nGreen = Ui.c("#0F6B3F")
    // F (Followers / Following)
    val fHead = Ui.c("#17121F"); val fHeadBtn = Ui.c("#2B2438"); val fOnHead = Ui.c("#B9B0C9"); val fTabOn = Ui.c("#7C3AED"); val fTabOff = Ui.c("#D6CFE3")
    val fLine = Ui.c("#E9E6EE"); val fSub = Ui.c("#5E586B"); val fFace = Ui.c("#EFEAF7"); val fFaceLine = Ui.c("#DDD5EC"); val fBtnOn = Ui.c("#F1EEF5"); val fBadge = Ui.c("#6D28D9")
    // TILE (dark square tiles of the Settings list)
    val tNotifications = Ui.c("#B45309"); val tWallet = Ui.c("#047857"); val tRefer = Ui.c("#BE185D"); val tHost = Ui.c("#6D28D9")
    val tVerification = Ui.c("#1D4ED8"); val tMemberships = Ui.c("#A16207"); val tPrivacy = Ui.c("#0F766E"); val tBlocked = Ui.c("#B91C1C")
    val tSecurity = Ui.c("#4338CA"); val tHelp = Ui.c("#0E7490"); val tLogout = Ui.c("#334155"); val tDelete = Ui.c("#7F1D1D")
}

/** The rules of Profile.jsx (same as the server, the Control Center and the Wallet). */
object PR {
    const val MAX_PHOTOS = 5
    const val MAX_BIO = 200
    const val HOST_MIN_MINUTES = 200
    const val VIDEO_SECONDS = 7

    const val DANCE = "<circle cx=\"14.5\" cy=\"3.8\" r=\"2\"/><path d=\"M13.8 7.4 11.6 13.8\"/><path d=\"M13.3 9.2 16.6 8.6 19.4 5\"/><path d=\"M12.8 10.2 8.9 10.4 5.6 7.6\"/><path d=\"M11.6 13.8 8.6 17.2 5.2 18.8\"/><path d=\"M11.6 13.8 14.6 16.8 14.2 21.4\"/>"
    /** the 10 ready-made interests & hobbies */
    val INTERESTS = listOf("Singing" to Lu.Mic, "Movies" to Lu.Clapperboard, "Romantic Talk" to Lu.Heart, "Dancing" to DANCE, "Talking" to Lu.MessagesSquare,
        "Music" to Lu.Headphones, "Travel" to Lu.Plane, "Cooking" to Lu.ChefHat, "Shayari" to Lu.Feather, "Gaming" to Lu.Gamepad2)

    val STATES = listOf("Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
        "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
        "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
        "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal", "Andaman and Nicobar Islands", "Chandigarh",
        "Dadra and Nagar Haveli and Daman and Diu", "Delhi", "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry")

    val PENDING_WORDS = setOf("pending", "in_review", "review", "under_review", "submitted")

    /** JS truthiness of a JSON value */
    fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }
    /** String(v || "") */
    fun str(v: Any?): String = if (!truthy(v)) "" else v.toString()
    /** a text field ("" for missing / null) */
    fun s(o: JSONObject?, k: String): String = str(o?.opt(k))

    /** srcOf: a photo item is a plain address or { url | file_url | uri | file_uri | path } */
    fun srcOf(x: Any?): String = when (x) {
        is String -> x
        is JSONObject -> listOf("url", "file_url", "uri", "file_uri", "path").map { s(x, it) }.firstOrNull { it.isNotEmpty() } ?: ""
        else -> ""
    }
    fun statusOfItem(x: Any?): String =
        if (x is JSONObject) (s(x, "status").ifEmpty { if (x.opt("approved") == false) "pending" else "" }).lowercase() else ""

    fun norm(v: Any?): String {
        val s = str(v).lowercase()
        return when (s) {
            "approved", "verified", "active", "accepted", "done" -> "approved"
            "pending", "submitted", "in_review", "review", "under_review", "applied" -> "pending"
            "rejected", "declined", "failed" -> "rejected"
            else -> "none"
        }
    }

    /** An application counts as approved / rejected only when the admin reviewed THIS application. */
    private fun reviewed(appliedAt: Any?, reviewedFor: Any?, status: Any?): String {
        if (!truthy(appliedAt)) return "none"
        if (truthy(reviewedFor) && reviewedFor == appliedAt && (status == "approved" || status == "rejected")) return status as String
        return "pending"
    }
    fun hostStatus(p: JSONObject?): String = reviewed(p?.opt("host_applied_at"), p?.opt("host_reviewed_for"), p?.opt("host_status"))
    fun creatorStatus(p: JSONObject?): String = reviewed(p?.opt("creator_submitted_at"), p?.opt("creator_reviewed_for"), p?.opt("creator_status"))

    class VInfo(val verified: Boolean, val pending: Boolean, val at: String)
    private val VERIFIED_AT = listOf("identity_verified_at", "verified_at", "verification_completed_at", "age_verified_at", "creator_verified_at", "creator_approved_at", "host_approved_at", "host_verified_at")
    fun verificationInfo(p: JSONObject?): VInfo {
        val st = listOf(p?.opt("identity_verification_status"), p?.opt("age_verification_status"))
        val ok = st.any { norm(it) == "approved" } || creatorStatus(p) == "approved" || hostStatus(p) == "approved" || p?.opt("verified") == true || p?.opt("is_verified") == true
        val pending = !ok && st.any { norm(it) == "pending" }
        val at = VERIFIED_AT.map { s(p, it) }.firstOrNull { it.isNotEmpty() && Tf.ms(it) > 0L } ?: ""
        return VInfo(ok, pending, at)
    }

    /** Number(v) of JS for the values the server sends; null = NaN / missing */
    private fun num(v: Any?): Double? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is Boolean -> if (v) 1.0 else 0.0
        is String -> if (v.isEmpty()) null else if (v.isBlank()) 0.0 else v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        is JSONArray -> if (v.length() == 0) 0.0 else if (v.length() == 1) num(v.opt(0)) else null
        else -> null
    }
    fun countOf(p: JSONObject?, keys: List<String>): Long {
        for (k in keys) {
            val v = p?.opt(k) ?: continue
            if (v is JSONArray) return v.length().toLong()
            val n = num(v) ?: continue
            return n.toLong()
        }
        return 0
    }
    fun firstNum(p: JSONObject?, keys: List<String>): Double? {
        for (k in keys) { val n = num(p?.opt(k)) ?: continue; return n }
        return null
    }

    /** errText of Profile.jsx: the server's reason / message / short error, else [fallback] */
    fun errText(d: JSONObject?, fallback: String): String {
        if (d == null) return fallback
        if (d.opt("error") == "privacy_blocked") return s(d, "reason").ifEmpty { "For your safety, numbers, links and social media IDs are not allowed here." }
        val e = d.opt("error")
        val msg = s(d, "reason").ifEmpty { s(d, "message") }.ifEmpty { if (e is String && e.length < 140) e else "" }
        return msg.ifEmpty { fallback }
    }
    fun errText(r: Api.Result, fallback: String) = errText(r.data, fallback)

    /** "17 Nov 2026" (toLocaleDateString en-IN, day numeric, month short, year numeric) */
    fun fmtDate(v: String?): String = Tf.date(v)

    fun list(r: Api.Result): List<JSONObject> = Tf.list(r)
    fun sortNew(l: List<JSONObject>): List<JSONObject> = l.sortedByDescending { Tf.ms(s(it, "created_date")) }
}

/** Photos: shrink before upload (faster on mobile data) and the small list copy, like photoUpload.js. */
object PUp {
    const val UPLOAD_ERROR = "Upload failed. Please choose a clear photo (JPG or PNG)."
    const val THUMB_PX = 360

    class Pic(val bmp: Bitmap, val w: Int, val h: Int)

    /** Reads a photo once, turned the right way up; big photos are read smaller (never under [minSide]). */
    fun decode(bytes: ByteArray, minSide: Int): Pic? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            var s = 1
            while (max(o.outWidth, o.outHeight) / (s * 2) >= minSide) s *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            if (raw == null) null else {
                val or = try { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
                val m = Matrix()
                when (or) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                }
                val turned = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
                val sideways = or == ExifInterface.ORIENTATION_ROTATE_90 || or == ExifInterface.ORIENTATION_ROTATE_270 || or == ExifInterface.ORIENTATION_TRANSPOSE || or == ExifInterface.ORIENTATION_TRANSVERSE
                Pic(turned, if (sideways) o.outHeight else o.outWidth, if (sideways) o.outWidth else o.outHeight)
            }
        }
    } catch (e: Throwable) { null }

    /** drawJpeg: at most [maxDim] px wide / high (of the real photo), white behind see-through parts */
    fun jpeg(p: Pic, maxDim: Int, quality: Float): ByteArray? = try {
        val scale = min(1f, maxDim.toFloat() / max(p.w, p.h))
        val w = max(1, (p.w * scale).roundToInt()); val h = max(1, (p.h * scale).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(p.bmp, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val bo = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), bo)
        out.recycle()
        bo.toByteArray()
    } catch (e: Throwable) { null }

    /** POST a file to Core/UploadFile (public) or Core/UploadPrivateFile — runs on the caller's (background) thread. */
    fun send(private: Boolean, bytes: ByteArray, name: String, mime: String): Api.Result {
        if (Demo.on) return Api.Result(200, JSONObject().put("file_url", "demo://up/$name").put("file_uri", "demo://up/$name"), null)
        val boundary = "----TrueFind" + System.nanoTime()
        val out = ByteArrayOutputStream()
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return Api.request("POST", "/integration-endpoints/Core/${if (private) "UploadPrivateFile" else "UploadFile"}", out.toByteArray(), "multipart/form-data; boundary=$boundary", 90000)
    }

    class Up(val url: String, val thumb: String, val error: Api.Result?)

    /**
     * Profile.jsx uploadPhotoAndThumb: the photo (shrunk to 1600 px / 0.86 when big) and its
     * 360 px list copy, uploaded side by side, both public. The small copy is optional.
     */
    fun photoAndThumb(bytes: ByteArray, mime: String, done: (Up) -> Unit) {
        Api.async({
            val pic = decode(bytes, 1600)
            // shrinkImage: only jpeg / png / webp are made smaller; a small one is sent as it is
            val canShrink = Regex("^image/(jpeg|jpg|png|webp)$", RegexOption.IGNORE_CASE).matches(mime)
            var full = bytes; var fullMime = mime.ifEmpty { "image/jpeg" }
            if (canShrink && pic != null) {
                val scale = min(1f, 1600f / max(pic.w, pic.h))
                if (!(scale == 1f && bytes.size < 1.5 * 1024 * 1024)) jpeg(pic, 1600, 0.86f)?.let { full = it; fullMime = "image/jpeg" }
            }
            val small = if (pic != null) jpeg(pic, THUMB_PX, 0.74f) else null
            pic?.bmp?.recycle()
            var thumbUrl = ""
            val t = if (small != null) Thread { thumbUrl = urlOf(send(false, small, "thumb.jpg", "image/jpeg")) }.apply { start() } else null
            val ext = if (fullMime == "image/jpeg") "jpg" else fullMime.substringAfter('/').ifEmpty { "jpg" }
            val r = send(false, full, "photo.$ext", fullMime)
            try { t?.join(60000) } catch (e: InterruptedException) { }
            val url = urlOf(r)
            if (url.isEmpty()) Up("", "", r) else Up(url, thumbUrl, null)
        }, done)
    }
    private fun urlOf(r: Api.Result): String = if (!r.ok) "" else PR.s(r.data, "file_url").ifEmpty { PR.s(r.data, "url") }.ifEmpty { PR.s(r.data.optJSONObject("data"), "file_url") }

    /** photoUpload.js uploadProfilePhoto (screenshots for support): 1440 px / 0.82, private. Returns the file uri ("" = failed). */
    fun privatePhoto(bytes: ByteArray, mime: String, done: (String) -> Unit) {
        if (bytes.size > 15 * 1024 * 1024 || !mime.startsWith("image/")) { done(""); return }
        Api.async({
            val pic = decode(bytes, 1440)
            val full = if (pic != null) jpeg(pic, 1440, 0.82f) else null
            pic?.bmp?.recycle()
            if (full == null) "" else { val r = send(true, full, "photo.jpg", "image/jpeg"); if (r.ok) PR.s(r.data, "file_uri") else "" }
        }, done)
    }
}

/** line-height in dp (CSS line-height) */
fun TextView.lineH(dp: Float): TextView { val fm = paint.fontMetrics; setLineSpacing(Ui.dp(dp) - (fm.descent - fm.ascent), 1f); return this }
/** one line, "…" at the end */
fun TextView.ell(): TextView { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; return this }
fun TextView.maxLen(n: Int): TextView { filters = arrayOf(InputFilter.LengthFilter(n)); return this }
/** text size in dp (not scaled by the phone's font size, like the rest of the app) */
fun TextView.sizeDp(v: Float): TextView { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(v)); return this }

/** Small UI pieces of Profile.jsx */
object PUi {
    fun lu(inner: String, color: Int, size: Float, stroke: Float = 2f, fill: String = "none"): Drawable = Tf.lu(inner, color, size, stroke, fill)

    fun icon(ctx: Context, d: Drawable, sizeDp: Float): ImageView = ImageView(ctx).apply {
        setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
    }

    /** Square button on the purple top bar (back, settings, close): 44 × 44, round 12 */
    fun headBtn(ctx: Context, label: String, d: Drawable, bg: Int = PC.headBtn, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = Tf.box(bg, 12f)
        contentDescription = label
        addView(ImageView(ctx).apply { setImageDrawable(d) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { onClick() }
        Ui.pressable(this, .97f)
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f))
    }

    /**
     * The flat purple top of Profile, Edit Profile and Settings: "TrueFind" and the page name between
     * two buttons, then [children]. A soft circle at the top right (and a ring at the bottom left).
     */
    fun pageHead(ctx: Context, sub: String, left: View?, right: View?, children: List<View> = emptyList(), pad: Float = 52f, gap: Float = 20f,
                 ring: Boolean = false, bg: Int = PC.head, subColor: Int = PC.onHead): FrameLayout {
        val box = FrameLayout(ctx).apply { setBackgroundColor(bg); clipChildren = true }
        box.addView(HeadDeco(ctx, ring), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(20f), Ui.dpi(52f), Ui.dpi(20f), Ui.dpi(pad)) }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dpi(44f) }
        if (left != null) row.addView(left, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)).apply { rightMargin = Ui.dpi(12f) })
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Tf.t(ctx, "TrueFind", 17f, Color.WHITE).lineH(22f))
        t.addView(Tf.t(ctx, sub, 13f, subColor, false).lineH(18f))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) {
            val w = right.layoutParams?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT
            row.addView(right, LinearLayout.LayoutParams(w, Ui.dpi(44f)).apply { leftMargin = Ui.dpi(12f) })
        }
        col.addView(row)
        for (v in children) col.addView(v, (v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply { topMargin = Ui.dpi(gap) })
        box.addView(col, FrameLayout.LayoutParams(-1, -2))
        return box
    }

    private class HeadDeco(ctx: Context, private val ring: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.style = Paint.Style.FILL; p.color = Color.argb(15, 255, 255, 255)
            // 240 circle at top −90, right −70
            c.drawCircle(w + Ui.dp(70f) - Ui.dp(120f), -Ui.dp(90f) + Ui.dp(120f), Ui.dp(120f), p)
            if (ring) {
                // 170 ring (1.5 line) at bottom −70, left −60
                p.style = Paint.Style.STROKE; p.strokeWidth = Ui.dp(1.5f); p.color = Color.argb(31, 255, 255, 255)
                c.drawCircle(-Ui.dp(60f) + Ui.dp(85f), h + Ui.dp(70f) - Ui.dp(85f), Ui.dp(85f) - Ui.dp(0.75f), p)
            }
        }
    }

    /** Title row of a card: small square icon, title and one thing on the right */
    fun secHead(ctx: Context, iconInner: String, title: String, right: View?): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dpi(44f) }
        val tile = FrameLayout(ctx).apply { background = Tf.box(PC.tint, 8f) }
        tile.addView(ImageView(ctx).apply { setImageDrawable(lu(iconInner, PC.head, 15f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        row.addView(tile, LinearLayout.LayoutParams(Ui.dpi(28f), Ui.dpi(28f)).apply { rightMargin = Ui.dpi(10f) })
        row.addView(Tf.t(ctx, title, 15f, PC.ink).lineH(20f), LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) row.addView(right)
        return row
    }

    /** "True Find" in plum and wine */
    fun wordmark(ctx: Context, size: Float): TextView {
        val s = android.text.SpannableString("True Find")
        s.setSpan(android.text.style.ForegroundColorSpan(PC.plum), 0, 4, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(PC.find), 5, 9, 0)
        return Tf.t(ctx, s, size, PC.plum).apply { letterSpacing = -0.4f / size; includeFontPadding = false }
    }

    fun statusPill(ctx: Context, status: String): TextView {
        val (label, bg, fg) = when (status) {
            "approved" -> Triple("Approved", Ui.c("#E8F7EE"), PC.green)
            "pending" -> Triple("Under review", Ui.c("#FFF4E0"), Ui.c("#B45309"))
            "rejected" -> Triple("Rejected", Ui.c("#FDECEC"), PC.red)
            else -> Triple("Not applied", PC.faint, PC.sub)
        }
        return Tf.t(ctx, label, 12f, fg).apply {
            gravity = Gravity.CENTER; background = Tf.box(bg, 999f); setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
            minHeight = Ui.dpi(24f); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(24f))
        }
    }

    /** dark square tile with a white line icon */
    fun tile(ctx: Context, color: Int, iconInner: String, size: Float = 42f): FrameLayout = FrameLayout(ctx).apply {
        background = Tf.box(color, (size * 0.27f).roundToInt().toFloat())
        addView(ImageView(ctx).apply { setImageDrawable(lu(iconInner, Color.WHITE, (size * 0.48f).roundToInt().toFloat(), 2.1f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(Ui.dpi(size), Ui.dpi(size))
    }

    /** Btn: 48 high, round 14, bold 15 — primary / light / danger */
    fun btn(ctx: Context, label: String, kind: String = "primary", iconInner: String? = null, iconSize: Float = 18f, height: Float = 48f, radius: Float = 14f, onClick: () -> Unit): TfBtn {
        val fg = when (kind) { "light" -> PC.plum; else -> Color.WHITE }
        return TfBtn(ctx, label, kind, iconInner?.let { lu(it, fg, iconSize) }, height, radius, 15f).apply { setOnClickListener { onClick() } }
    }

    /** a paragraph (regular weight) with its line height */
    fun para(ctx: Context, s: CharSequence, size: Float, color: Int, lh: Float, bold: Boolean = false): TextView = Tf.t(ctx, s, size, color, bold).lineH(lh)

    /** The bottom sheet of Profile.jsx: grabber, title (+ small line), round ✕, then [content]. Returns close(). */
    fun sheet(layer: TfLayer, title: String, sub: String?, content: View, onClose: () -> Unit = {}): () -> Unit {
        val ctx = layer.ctx
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Tf.topRound(Color.WHITE, 24f)
            setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(18f))
            isClickable = true
        }
        panel.addView(View(ctx).apply { background = Tf.box(Ui.c("#E5DCEE"), 2f) }, LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dpi(14f) })
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(Tf.t(ctx, title, 17f, PC.ink).lineH(22f))
        if (!sub.isNullOrEmpty()) tcol.addView(para(ctx, sub, 12.5f, PC.sub, 17f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(3f) })
        head.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(12f) })
        var close: () -> Unit = {}
        val x = FrameLayout(ctx).apply {
            background = Tf.circle(PC.faint); contentDescription = "Close"
            addView(ImageView(ctx).apply { setImageDrawable(lu(Lu.X, PC.ink, 17f, 2.4f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { close() }
            Ui.pressable(this, .97f)
        }
        head.addView(x, LinearLayout.LayoutParams(Ui.dpi(34f), Ui.dpi(34f)))
        panel.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(14f) })
        panel.addView(content, LinearLayout.LayoutParams(-1, -2))
        close = layer.sheet(panel, Color.argb(115, 20, 10, 30), 0.92f, 20f, { false }, onClose)
        return close
    }
}

/**
 * Confirm of Profile.jsx: centred box with Cancel / OK. [typeToConfirm] asks the member to
 * type a word first (DELETE). [dark]: black box with white text (Followers / Following).
 * [onConfirm] gets this box: call [done] when the work ended, or [close].
 */
class PConfirm(private val layer: TfLayer, title: String, text: String, confirmLabel: String = "OK", danger: Boolean = false, dark: Boolean = false,
               typeToConfirm: String? = null, private val onConfirm: (PConfirm) -> Unit, onClosed: () -> Unit = {}) {
    private val ctx = layer.ctx
    private var busy = false
    private val ok: TfBtn
    private val cancel: TfBtn
    private var closer: () -> Unit = {}
    private var closed = false

    init {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Tf.box(if (dark) PC.fHead else Color.WHITE, 22f)
            setPadding(Ui.dpi(18f), Ui.dpi(20f), Ui.dpi(18f), Ui.dpi(16f))
        }
        card.addView(Tf.t(ctx, title, 17f, if (dark) Color.WHITE else PC.ink).apply { gravity = Gravity.CENTER })
        card.addView(PUi.para(ctx, text, 13.5f, if (dark) PC.fOnHead else PC.sub, 19f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f); bottomMargin = Ui.dpi(14f) })
        var input: TfInput? = null
        if (!typeToConfirm.isNullOrEmpty()) {
            input = TfInput(ctx, "Type $typeToConfirm to confirm").apply { gravity = Gravity.CENTER; contentDescription = "Type $typeToConfirm to confirm" }
            card.addView(input, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { bottomMargin = Ui.dpi(12f) })
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        cancel = TfBtn(ctx, "Cancel", "light", null, 44f, 14f, 15f).apply {
            if (dark) { background = Tf.box(PC.fHeadBtn, 14f); this.text.setTextColor(Color.WHITE) }
            setOnClickListener { if (!busy) close() }
        }
        ok = TfBtn(ctx, confirmLabel, if (danger) "danger" else "primary", null, 44f, 14f, 15f).apply {
            setOnClickListener { if (!busy && !off) { busy = true; refresh(); onConfirm(this@PConfirm) } }
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f).apply { rightMargin = Ui.dpi(10f) })
        row.addView(ok, LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f))
        card.addView(row)
        if (input != null) {
            ok.off = true
            input.onText = { t -> ok.off = t.trim().uppercase() != typeToConfirm }
        }
        closer = layer.dialog(card, Color.argb(128, 20, 10, 30), 340f, 40f, { busy }) { closed = true; onClosed() }
    }
    private fun refresh() { ok.busy = busy; cancel.off = busy }
    /** the work ended (the box stays open) */
    fun done() { if (closed) return; busy = false; refresh() }
    fun close() { busy = false; closer() }
}

/**
 * A photo of the member cut to fill its box (object-fit: cover), round corners or round.
 * Private photos are signed first. "asset:" photos (test screens only) come from the app.
 */
@SuppressLint("ViewConstructor")
class PImg(ctx: Context, private val radiusDp: Float = 0f, private val round: Boolean = false) : View(ctx) {
    private var bmp: Bitmap? = null
    private var want: List<String> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    var placeholder: Int = Color.TRANSPARENT
    /** what shows while there is no photo: "avatar" (the default avatar), "spin" (nothing — a loader sits on top) or "none" */
    var empty = "avatar"
    var onLoaded: ((Boolean) -> Unit)? = null

    fun set(raws: List<String>) {
        val list = raws.filter { it.isNotBlank() }.distinct()
        if (list == want && bmp != null) return
        want = list
        if (list.isEmpty()) { bmp = null; invalidate(); onLoaded?.invoke(false); return }
        // the photo is cut for the real size of the box: wait for it when not laid out yet
        if (width == 0 || height == 0) { waiting = true; return }
        fetch(list)
    }
    private var waiting = false
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0 && waiting && want.isNotEmpty()) { waiting = false; fetch(want) }
    }
    private fun fetch(list: List<String>) {
        val w = max(width, Ui.dpi(40f)); val h = max(height, Ui.dpi(40f))
        val a = list.firstOrNull { it.startsWith("asset:") }
        if (a != null) { bmp = Tf.pic(context, a.removePrefix("asset:")); invalidate(); onLoaded?.invoke(bmp != null); return }
        val hit = Photos.cachedCover(list, w, h)
        if (hit != null) { bmp = hit; invalidate(); onLoaded?.invoke(true); return }
        bmp = null; invalidate()
        Photos.loadCover(list, w, h) { b -> if (want == list) { bmp = b; invalidate(); onLoaded?.invoke(b != null) } }
    }
    val hasPhoto: Boolean get() = bmp != null
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        val rad = if (round) min(w, h) / 2f else Ui.dp(radiusDp)
        if (placeholder != Color.TRANSPARENT) { p.color = placeholder; c.drawRoundRect(r, rad, rad, p) }
        val b = bmp
        if (b != null) {
            p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val k = max(w / b.width, h / b.height)
                setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate((w - b.width * k) / 2f, (h - b.height * k) / 2f) })
            }
            c.drawRoundRect(r, rad, rad, p); p.shader = null
        } else if (empty == "avatar") {
            val s = min(w, h)
            c.save(); c.translate((w - s) / 2f, (h - s) / 2f); DefaultAvatar.draw(c, s); c.restore()
        }
    }
}

/** The full-screen photo viewer (dark, ✕ at the top, the photo, buttons at the bottom). */
object PViewer {
    class Action(val label: String, val iconInner: String, val danger: Boolean, val onClick: () -> Unit)

    fun open(layer: TfLayer, raws: List<String>, title: String, actions: List<Action>, onClosed: () -> Unit = {}): () -> Unit {
        val ctx = layer.ctx
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(240, 10, 5, 16)); contentDescription = title }
        var close: () -> Unit = {}
        val top = FrameLayout(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(52f), Ui.dpi(12f), 0) }
        val x = FrameLayout(ctx).apply {
            background = Tf.circle(Color.argb(36, 255, 255, 255)); contentDescription = "Close photo"
            addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.X, Color.WHITE, 22f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { close() }
            Ui.pressable(this, .97f)
        }
        top.addView(x, FrameLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f), Gravity.END))
        box.addView(top)
        val mid = FrameLayout(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)) }
        val img = FitPic(ctx)
        val spin = TfLoader(ctx, 18f, Color.WHITE)
        mid.addView(img, FrameLayout.LayoutParams(-1, -1))
        mid.addView(spin, FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        box.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))
        if (actions.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dpi(16f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(16f)); isClickable = true }
            actions.forEachIndexed { i, a ->
                val fg = if (a.danger) Ui.c("#FCA5A5") else Color.WHITE
                val b = Tf.t(ctx, a.label, 14f, fg).apply {
                    gravity = Gravity.CENTER
                    background = Tf.box(if (a.danger) Color.argb(46, 220, 38, 38) else Color.argb(31, 255, 255, 255), 14f)
                    compoundDrawablePadding = Ui.dpi(7f)
                    setCompoundDrawablesWithIntrinsicBounds(PUi.lu(a.iconInner, fg, 17f), null, null, null)
                    setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                    setOnClickListener { a.onClick() }
                    Ui.pressable(this, .97f)
                }
                row.addView(b, LinearLayout.LayoutParams(0, Ui.dpi(46f), 1f).apply { if (i > 0) leftMargin = Ui.dpi(10f) })
            }
            box.addView(row)
        }
        box.setOnClickListener { close() }
        img.onPhotoTap = { /* the photo itself does not close the viewer */ }
        val list = raws.filter { it.isNotBlank() }
        val a = list.firstOrNull { it.startsWith("asset:") }
        if (a != null) { img.bitmap = Tf.pic(ctx, a.removePrefix("asset:")); spin.visibility = View.GONE }
        else if (list.isNotEmpty()) {
            val side = max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            loadFirst(list, side) { b -> img.bitmap = b; if (b != null) spin.visibility = View.GONE }
        }
        close = layer.fade(box, 30f, { false }, onClosed)
        return close
    }
    private fun loadFirst(list: List<String>, side: Int, done: (Bitmap?) -> Unit) {
        if (list.isEmpty()) { done(null); return }
        Photos.loadFull(list[0], side) { b -> if (b != null || list.size == 1) done(b) else loadFirst(list.drop(1), side, done) }
    }

    /** the photo as it is (object-fit: contain) with round 12 corners */
    private class FitPic(ctx: Context) : View(ctx) {
        var bitmap: Bitmap? = null
            set(v) { field = v; invalidate() }
        var onPhotoTap: (() -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val r = RectF()
        private var shown = RectF()
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            // a tap on the photo stays; around it closes (the box under it gets it)
            return shown.contains(e.x, e.y) && bitmap != null
        }
        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            val k = min(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * k; val h = b.height * k
            val l = (width - w) / 2f; val t = (height - h) / 2f
            shown.set(l, t, l + w, t + h)
            r.set(shown)
            p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate(l, t) }) }
            c.drawRoundRect(r, Ui.dp(12f), Ui.dp(12f), p); p.shader = null
        }
    }
}

/**
 * A row whose first child (a text) gets what is left after the others (icons), like a flex
 * row where the text may shrink and the badge never does. Children are centred vertically.
 */
class ShrinkRow(ctx: Context, private val gapPx: Int) : android.view.ViewGroup(ctx) {
    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2 else MeasureSpec.getSize(ws)
        var fixed = 0; var h = 0; var n = 0
        for (i in 1 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            val lp = c.layoutParams
            c.measure(spec(lp.width), spec(lp.height))
            fixed += c.measuredWidth; n++; h = max(h, c.measuredHeight)
        }
        var w = fixed + n * gapPx
        if (childCount > 0) {
            val t = getChildAt(0)
            if (t.visibility != GONE) {
                t.measure(MeasureSpec.makeMeasureSpec(max(0, maxW - w), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                w += t.measuredWidth; h = max(h, t.measuredHeight)
            } else w -= if (n > 0) gapPx else 0
        }
        val lpH = layoutParams?.height ?: -2
        val outH = if (lpH > 0) lpH else h
        setMeasuredDimension(if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) MeasureSpec.getSize(ws) else min(w, maxW), outH)
    }
    private fun spec(v: Int) = if (v > 0) MeasureSpec.makeMeasureSpec(v, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0; val h = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            val y = (h - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gapPx
        }
    }
}
