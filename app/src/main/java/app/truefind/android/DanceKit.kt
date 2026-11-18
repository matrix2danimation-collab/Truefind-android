package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LIVE DANCE (native) — shared pieces: the same colours, gifts, texts and
 * small parts as the web page (src/pages/LiveDance.jsx + LiveRooms.jsx).
 */
object DK {
    // ── colours (C in LiveRooms.jsx) ──
    val INK = Ui.c("#1b1131")
    val SUB = Ui.c("#6e6689")
    val LINE = Ui.c("#ebe3fa")
    val SOFT = Ui.c("#f3ecff")
    val PURPLE = Ui.c("#7a1fe4")
    val DEEP = Ui.c("#5a0fc2")
    val PINK = Ui.c("#ff2d6f")
    val ROSE = Ui.c("#be123c")
    val STEP_TEXT = Ui.c("#4b4466")
    val GRAD = intArrayOf(Ui.c("#9a3cf3"), Ui.c("#7a1fe4"), Ui.c("#5a0fc2"))
    val BAR_A = intArrayOf(Ui.c("#6d28d9"), Ui.c("#9b3cf3"))
    val BAR_B = intArrayOf(Ui.c("#f43f8e"), Ui.c("#ff6aa8"))
    val GOLD_TEXT = Ui.c("#ffd166")
    val ERR = Ui.c("#be123c")

    // ── modes ──
    class ModeInfo(val key: String, val label: String, val short: String, val fee: Int, val line: String, val banner: String)
    val MODES = listOf("solo", "battle", "duo")
    val MODE = mapOf(
        "solo" to ModeInfo("solo", "Solo Dance", "Solo", 50, "Watch the host's exclusive live dance", "Solo Dance Mode"),
        "battle" to ModeInfo("battle", "1 vs 1 Battle", "1 vs 1", 50, "Host vs challenger · you vote the winner", "1 vs 1 Dance Battle"),
        "duo" to ModeInfo("duo", "2 Dancer Battle", "2 Dancer", 25, "Two dancers battle · host & audience judge", "2 Dancer Battle"),
    )
    fun mode(k: String?): ModeInfo = MODE[k] ?: MODE.getValue("solo")

    val QUICK = listOf("rose", "teddy", "cake", "car", "castle")
    val GENRES = listOf("Bhojpuri", "Haryanvi", "Punjabi", "Telugu", "Hindi", "Bollywood", "Hollywood")
    val STYLES = listOf("Freestyle", "Bollywood Thumka", "Hip-Hop", "Bhangra", "Classical", "Folk", "Contemporary")
    val EMOJIS = listOf("🔥", "😍", "💜", "👏", "💃", "🕺", "✨", "😂", "🙌", "❤️", "🥰", "👑")
    val REASONS = listOf("Abuse or bad language", "Nudity or adult content", "Harassment or threats", "Spam or promotion", "Asking for money / numbers", "Fake profile", "Other")
    val GENRE_ICON = mapOf("Bhojpuri" to "🪘", "Haryanvi" to "🌾", "Punjabi" to "🥁", "Telugu" to "🎬", "Hindi" to "🎶", "Bollywood" to "💃", "Hollywood" to "🎤")
    val STYLE_ICON = mapOf("Freestyle" to "🌀", "Bollywood Thumka" to "💃", "Hip-Hop" to "🧢", "Bhangra" to "🥁", "Classical" to "🪷", "Folk" to "🪔", "Contemporary" to "🩰")
    val TAGS = listOf("⭐", "💜", "🔥", "🌸", "💖", "✨", "🌙", "🦋")
    fun tagOf(id: String): String {
        var h = 0L
        for (ch in id) h = (h * 31 + ch.code) and 0xffffffffL
        return TAGS[(h % TAGS.size).toInt()]
    }

    // ── gifts (LR_GIFTS; Live Dance keeps its own Heart price: 30) ──
    class Gift(val id: String, val e: String, val name: String, val price: Int, val g: String)
    val GIFT_GROUPS = listOf(Triple("popular", "Popular", "🔥"), Triple("love", "Love", "❤️"), Triple("luxury", "Luxury", "👑"), Triple("festive", "Festive", "✨"))
    val GIFT_COUNTS = listOf(1, 5, 10, 20)
    val GIFTS = listOf(
        Gift("rose", "🌹", "Rose", 10, "popular"), Gift("heart", "💖", "Heart", 30, "popular"), Gift("teddy", "🧸", "Teddy", 50, "popular"),
        Gift("cake", "🎂", "Cake", 100, "popular"), Gift("crown", "👑", "Crown", 200, "popular"), Gift("ring", "💍", "Ring", 300, "popular"),
        Gift("car", "🚗", "Car", 500, "popular"), Gift("castle", "🏰", "Castle", 1000, "popular"),
        Gift("kiss", "💋", "Kiss", 20, "love"), Gift("letter", "💌", "Love Letter", 40, "love"), Gift("chocolates", "🍫", "Chocolates", 60, "love"),
        Gift("bouquet", "💐", "Bouquet", 150, "love"), Gift("cupid", "💘", "Cupid", 250, "love"), Gift("doves", "🕊️", "Love Doves", 400, "love"),
        Gift("couple", "💑", "Couple", 600, "love"), Gift("forever", "💞", "Forever", 800, "love"),
        Gift("champagne", "🍾", "Champagne", 350, "luxury"), Gift("watch", "⌚", "Watch", 450, "luxury"), Gift("diamond", "💎", "Diamond", 700, "luxury"),
        Gift("supercar", "🏎️", "Supercar", 900, "luxury"), Gift("yacht", "🛥️", "Yacht", 1500, "luxury"), Gift("jet", "🛩️", "Private Jet", 2000, "luxury"),
        Gift("rocket", "🚀", "Rocket", 3000, "luxury"), Gift("island", "🏝️", "Island", 5000, "luxury"),
        Gift("diya", "🪔", "Diya", 15, "festive"), Gift("balloons", "🎈", "Balloons", 20, "festive"), Gift("sparkler", "🎇", "Sparkler", 25, "festive"),
        Gift("star", "🌟", "Star", 50, "festive"), Gift("party", "🎉", "Party", 60, "festive"), Gift("giftbox", "🎁", "Gift Box", 80, "festive"),
        Gift("fireworks", "🎆", "Fireworks", 120, "festive"), Gift("lantern", "🏮", "Lantern", 150, "festive"),
    )
    fun gift(id: String?): Gift? = GIFTS.firstOrNull { it.id == id }

    // ── numbers & time ──
    /** 1,23,456 (toLocaleString("en-IN")) */
    fun n(v: Number?): String = rupees((v?.toDouble() ?: 0.0).roundToInt().toLong())
    /** short from 1,000 up: 1.2K, 24.5K, 1.3M */
    fun c(v: Number?): String {
        val d = v?.toDouble() ?: 0.0
        fun one(x: Double) = String.format(java.util.Locale.US, "%.1f", x).removeSuffix(".0")
        return when {
            d >= 1e6 -> one(d / 1e6) + "M"
            d >= 1e3 -> one(d / 1e3) + "K"
            else -> d.roundToInt().toString()
        }
    }
    fun mmss(sec: Double): String {
        val s = max(0, sec.toInt())
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }
    fun pct(a: Number?, b: Number?): Int {
        val x = a?.toDouble() ?: 0.0; val y = b?.toDouble() ?: 0.0
        val t = x + y
        return if (t > 0) (x / t * 100).roundToInt() else 50
    }
    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?""")
    /** ISO time → ms (0 when missing / bad). A time without a zone counts as UTC (Base44 dates). */
    fun ms(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val m = ISO.find(s) ?: return 0L
        val g = m.groupValues
        return try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            var t = cal.timeInMillis + (g[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0)
            val z = g[8]
            if (z.isNotEmpty() && z != "Z") {
                val sign = if (z[0] == '-') -1 else 1
                val digits = z.substring(1).replace(":", "")
                val off = (digits.take(2).toInt() * 60 + digits.drop(2).toInt()) * 60_000L
                t -= sign * off
            }
            t
        } catch (e: Exception) { 0L }
    }
    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
    fun first(name: String?): String = name.orEmpty().trim().split(Regex("\\s+")).firstOrNull().orEmpty()

    // ── JSON ──
    fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    fun str(o: JSONObject?, k: String): String = if (o == null || o.isNull(k)) "" else o.optString(k, "")

    // ── drawables ──
    fun grad(colors: IntArray, radiusDp: Float = 0f, o: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR): GradientDrawable =
        GradientDrawable(o, colors).apply { cornerRadius = Ui.dp(radiusDp) }
    fun pill(color: Int, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = Ui.dp(999f); if (stroke != null) setStroke(Ui.dpi(strokeDp), stroke) }
    fun oval(color: Int, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); if (stroke != null) setStroke(Ui.dpi(strokeDp), stroke) }
    /** the light "glass" on the dark stage (web: rgba(24,8,48,.45) + white border) */
    fun glass(radiusDp: Float = 999f, a: Float = 0.45f): GradientDrawable = GradientDrawable().apply {
        setColor(Ui.ca("#180830", a)); cornerRadius = Ui.dp(radiusDp); setStroke(Ui.dpi(1f), Ui.ca("#ffffff", 0.18f))
    }
    fun glassOval(a: Float = 0.45f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Ui.ca("#180830", a)); setStroke(Ui.dpi(1f), Ui.ca("#ffffff", 0.18f))
    }
    fun card(radiusDp: Float = 20f, bg: Int = Ui.ca("#ffffff", 0.94f), stroke: Int = LINE): GradientDrawable =
        GradientDrawable().apply { setColor(bg); cornerRadius = Ui.dp(radiusDp); setStroke(Ui.dpi(1f), stroke) }

    /** PAGE_BG: linear-gradient(180deg,#f6efff 0%,#fdfbff 38%,#f4ecff 100%) */
    fun pageBg(): Drawable = object : Drawable() {
        private val p = Paint()
        override fun draw(c: Canvas) {
            val b = bounds
            p.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), intArrayOf(Ui.c("#f6efff"), Ui.c("#fdfbff"), Ui.c("#f4ecff")), floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(b, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** STAGE_BG: dark purple with a purple glow top-left and a pink glow top-right */
    fun stageBg(): Drawable = object : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            p.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), intArrayOf(Ui.c("#3b0f74"), Ui.c("#250a4d"), Ui.c("#1a0738")), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = RadialGradient(b.left + w * 0.2f, b.top.toFloat(), max(1f, w * 0.6f), intArrayOf(Ui.ca("#ba68ff", 0.55f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = RadialGradient(b.left + w * 0.85f, b.top.toFloat(), max(1f, w * 0.6f), intArrayOf(Ui.ca("#ff50aa", 0.45f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = null
            if (h <= 0f) return
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** the big mode boxes' backgrounds (BOX_BG) */
    fun boxBg(mode: String): Drawable = object : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            val (cols, glow) = when (mode) {
                "battle" -> intArrayOf(Ui.c("#6d28d9"), Ui.c("#9d174d"), Ui.c("#4c0f5c")) to Ui.ca("#ff5a78", 0.55f)
                "duo" -> intArrayOf(Ui.c("#4f46e5"), Ui.c("#6d28d9"), Ui.c("#3b0f74")) to Ui.ca("#78aaff", 0.5f)
                else -> intArrayOf(Ui.c("#7c2ae8"), Ui.c("#5a0fc2"), Ui.c("#3b0f74")) to Ui.ca("#ff78c8", 0.55f)
            }
            val pos = if (mode == "battle") floatArrayOf(0f, 0.6f, 1f) else floatArrayOf(0f, 0.55f, 1f)
            p.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), cols, pos, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = RadialGradient(b.left + w * 0.85f, b.top + h * 0.1f, max(1f, w * 0.7f), intArrayOf(glow, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = null
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }

    // ── text ──
    fun t(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = true, lines: Int = 1): TextView = Ui.text(ctx, s, size, color, bold).apply {
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END; if (lines == 1) isSingleLine = true }
    }
    fun italic(): Typeface = Typeface.create(Ui.bold, Typeface.BOLD_ITALIC)

    /** "True Find" in the web's gradient italic, with the heart */
    fun logo(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(ctx).apply { setImageDrawable(LogoHeart()) }, LinearLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(24f)))
        val tv = object : TextView(ctx) {
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                super.onSizeChanged(w, h, ow, oh)
                paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Ui.c("#b44bff"), Ui.c("#6a0fd8"), Shader.TileMode.CLAMP)
            }
        }
        tv.text = "True Find"; tv.typeface = italic(); tv.includeFontPadding = false
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(23f)); tv.setTextColor(Ui.c("#8a2ef0")); tv.isSingleLine = true
        addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dpi(6f) })
    }

    // ── buttons ──
    /** btn(): purple pill, white bold text, optional icon */
    fun button(ctx: Context, label: CharSequence, icon: Drawable? = null, heightDp: Float = 50f, sizeDp: Float = 16.5f, colors: IntArray = GRAD): TextView =
        DButton(ctx, label, sizeDp, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = grad(colors, heightDp / 2f)
            if (icon != null) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null); compoundDrawablePadding = Ui.dpi(8f) }
            setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0)
            minHeight = Ui.dpi(heightDp)
            elevation = Ui.dp(5f)
            outlineSpotShadowColorCompat(Ui.c("#7a1fe4"))
            isClickable = true; isFocusable = true
            Ui.pressable(this, 0.97f)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dpi(heightDp))
        }
    /** btnLine(): white pill with a purple line */
    fun lineButton(ctx: Context, label: CharSequence, icon: Drawable? = null, heightDp: Float = 50f, color: Int = PURPLE, stroke: Int = PURPLE, sizeDp: Float = 16f): TextView =
        DButton(ctx, label, sizeDp, color).apply {
            gravity = Gravity.CENTER
            background = pill(Color.WHITE, stroke, 1.5f)
            if (icon != null) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null); compoundDrawablePadding = Ui.dpi(8f) }
            setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0)
            isClickable = true; isFocusable = true
            Ui.pressable(this, 0.97f)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dpi(heightDp))
        }
    /** a chip that can be on (purple) or off (white) */
    fun chip(ctx: Context, label: CharSequence, on: Boolean, heightDp: Float = 34f, sizeDp: Float = 13.5f): TextView =
        Ui.text(ctx, label, sizeDp, if (on) Color.WHITE else INK).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dpi(13f), 0, Ui.dpi(13f), 0)
            background = if (on) grad(GRAD, 99f) else pill(Color.WHITE, LINE)
            minHeight = Ui.dpi(heightDp)
            isClickable = true
            Ui.pressable(this, 0.97f)
        }
    fun paintChip(tv: TextView, on: Boolean) {
        tv.setTextColor(if (on) Color.WHITE else INK)
        tv.background = if (on) grad(GRAD, 99f) else pill(Color.WHITE, LINE)
    }

    /** a round icon button */
    fun iconButton(ctx: Context, icon: Drawable, sizeDp: Float, bg: Drawable?, desc: String): FrameLayout = FrameLayout(ctx).apply {
        background = bg
        contentDescription = desc
        isClickable = true; isFocusable = true
        addView(ImageView(ctx).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.CENTER }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layoutParams = ViewGroup.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
        Ui.pressable(this, 0.94f)
    }
    fun emojiButton(ctx: Context, emoji: String, sizeDp: Float, emojiDp: Float, bg: Drawable?, desc: String): TextView = Ui.text(ctx, emoji, emojiDp, Color.WHITE, false).apply {
        gravity = Gravity.CENTER
        background = bg
        contentDescription = desc
        isClickable = true; isFocusable = true
        layoutParams = ViewGroup.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
        Ui.pressable(this, 0.94f)
    }

    /** LIVE pill (+ viewers) */
    fun livePill(ctx: Context, small: Boolean = false): Pair<LinearLayout, TextView> {
        val h = if (small) 22f else 26f
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val live = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = grad(intArrayOf(Ui.c("#ff3b5c"), Ui.c("#e11d48")), 99f)
            setPadding(Ui.dpi(if (small) 8f else 10f), 0, Ui.dpi(if (small) 8f else 10f), 0)
        }
        live.addView(BlinkDot(ctx, Color.WHITE), LinearLayout.LayoutParams(Ui.dpi(7f), Ui.dpi(7f)).apply { rightMargin = Ui.dpi(5f) })
        live.addView(Ui.text(ctx, "LIVE", if (small) 11.5f else 13f, Color.WHITE).apply { letterSpacing = 0.04f })
        row.addView(live, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dpi(h)))
        val viewers = Ui.text(ctx, "0", if (small) 11.5f else 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(Ui.dpi(if (small) 8f else 10f), 0, Ui.dpi(if (small) 8f else 10f), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.EYE, Color.WHITE, if (small) 13f else 15f), null, null, null)
            compoundDrawablePadding = Ui.dpi(4f)
        }
        row.addView(viewers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dpi(h)).apply { leftMargin = Ui.dpi(6f) })
        return row to viewers
    }

    /** Tag on the stage ("👑 Host", "Dancer 1"…) */
    fun tag(ctx: Context, s: String, gold: Boolean): TextView = Ui.text(ctx, s, 12.5f, if (gold) Ui.c("#fff3cf") else Color.WHITE).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
        background = if (gold) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.ca("#501e0a", 0.6f), Ui.ca("#280a3c", 0.6f))).apply {
            cornerRadius = Ui.dp(99f); setStroke(Ui.dpi(1f), Ui.ca("#ffd26e", 0.75f))
        } else GradientDrawable().apply { setColor(Ui.ca("#180830", 0.5f)); cornerRadius = Ui.dp(99f); setStroke(Ui.dpi(1f), Ui.ca("#ffffff", 0.25f)) }
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dpi(24f))
    }
    fun demoTag(ctx: Context): TextView = Ui.text(ctx, "DEMO", 10.5f, Color.WHITE).apply {
        gravity = Gravity.CENTER; letterSpacing = 0.06f
        setPadding(Ui.dpi(7f), 0, Ui.dpi(7f), 0)
        background = pill(Ui.c("#0f766e"))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dpi(20f))
    }

    /** the coin pill in the top bar (coins + "+"), opens the Wallet */
    fun coinPill(ctx: Context): Pair<LinearLayout, TextView> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = grad(intArrayOf(Ui.c("#2b1052"), Ui.c("#4a1a8a")), 99f)
            setPadding(Ui.dpi(7f), 0, Ui.dpi(6f), 0)
            isClickable = true
            Ui.pressable(this, 0.96f)
        }
        row.addView(ImageView(ctx).apply { setImageDrawable(CoinIcon(20f)) }, LinearLayout.LayoutParams(Ui.dpi(20f), Ui.dpi(20f)))
        val tv = Ui.text(ctx, "…", 14f, Color.WHITE)
        row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dpi(5f); rightMargin = Ui.dpi(5f) })
        row.addView(FrameLayout(ctx).apply {
            background = oval(Ui.ca("#ffffff", 0.18f))
            addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.PLUS, Color.WHITE, 14f)); scaleType = ImageView.ScaleType.CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f)))
        return row to tv
    }

    /** web's ModeIcon: 💃 / swords / users */
    fun modeIcon(ctx: Context, mode: String, sizeDp: Float, color: Int): View = if (mode == "solo")
        Ui.text(ctx, "💃", sizeDp, color, false).apply { gravity = Gravity.CENTER; includeFontPadding = false }
    else ImageView(ctx).apply { setImageDrawable(DIcon(if (mode == "battle") DIcon.SWORDS else DIcon.USERS, color, sizeDp)); scaleType = ImageView.ScaleType.CENTER }

    /** the steps row ("Pay entry > Watch live > …") */
    fun steps(ctx: Context, list: List<Pair<String, String>>, textDp: Float = 11.5f, emojiDp: Float = 19f): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
        list.forEachIndexed { i, (e, label) ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(Ui.text(ctx, e, emojiDp, INK, false).apply { gravity = Gravity.CENTER })
            col.addView(Ui.text(ctx, label, textDp, STEP_TEXT).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(3f), 0, 0); setLineSpacing(0f, 1.1f) })
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i < list.size - 1) addView(ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.CHEVRON_RIGHT, Ui.c("#c4b5fd"), 14f)) },
                LinearLayout.LayoutParams(Ui.dpi(14f), Ui.dpi(14f)).apply { topMargin = Ui.dpi(6f) })
        }
    }
    fun soloSteps() = listOf("🎟️" to "Pay entry", "👀" to "Watch live", "🎵" to "Request song", "🎁" to "Send gifts")
    fun battleSteps() = listOf("🎟️" to "Pay entry", "⚔️" to "Watch battle", "✅" to "Vote free", "🏆" to "Winner by votes")

    fun lp(w: Int, h: Int) = ViewGroup.MarginLayoutParams(w, h)
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    fun llp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f, gravity: Int = -1) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)); if (gravity != -1) this.gravity = gravity
        }
    fun flp(w: Int = MATCH, h: Int = MATCH, gravity: Int = Gravity.NO_GRAVITY, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) =
        FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(Ui.dpi(l), Ui.dpi(t), Ui.dpi(r), Ui.dpi(b)) }

    /** clip a view to a rounded rectangle (works for video too) */
    fun roundClip(v: View, radiusDp: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(radiusDp)) }
        }
        v.clipToOutline = true
    }
    fun ovalClip(v: View) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
        }
        v.clipToOutline = true
    }

    val EASE = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
    val EASE_OUT = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
}

/** Lucide icons (same as the web), drawn from their path data in a 24 × 24 box. */
class DIcon(data: String, private val color: Int, sizeDp: Float, private val fill: Int? = null, private val stroke: Float = 2f) : Drawable() {
    private val path: Path = PathParser.createPathFromPathData(data)
    private val px = Ui.dpi(sizeDp)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = this@DIcon.color
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill ?: Color.TRANSPARENT }

    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        if (fill != null) c.drawPath(path, inner)
        if (stroke > 0f) c.drawPath(path, line)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { line.alpha = alpha; inner.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { line.colorFilter = cf; inner.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val CHEVRON_LEFT = "M15 18l-6-6 6-6"
        const val CHEVRON_RIGHT = "M9 18l6-6-6-6"
        const val X = "M18 6 6 18 M6 6l12 12"
        const val CHECK = "M20 6 9 17l-5-5"
        const val PLUS = "M5 12h14 M12 5v14"
        const val EYE = "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0 M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z"
        const val COPY = "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"
        const val INFO = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z M12 16v-4 M12 8h.01"
        const val FILE_TEXT = "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z M14 2v4a2 2 0 0 0 2 2h4 M10 9H8 M16 13H8 M16 17H8"
        const val BAR_CHART = "M3 3v16a2 2 0 0 0 2 2h16 M18 17V9 M13 17V5 M8 17v-3"
        const val USERS = "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z M22 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75"
        const val GIFT = "M4 8h16a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z M12 8v13 M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7 M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5"
        const val HEART = "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"
        const val SHARE = "M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z M3 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z M8.59 13.51L15.42 17.49 M15.41 6.51L8.59 10.49"
        const val SEND = "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z M21.854 2.147l-10.94 10.939"
        const val SMILE = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z M8 14s1.5 2 4 2 4-2 4-2 M9 9L9.01 9 M15 9L15.01 9"
        const val MIC = "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z M19 10v2a7 7 0 0 1-14 0v-2 M12 19L12 22"
        const val MIC_OFF = "M2 2L22 22 M18.89 13.23A7.12 7.12 0 0 0 19 12v-2 M5 10v2a7 7 0 0 0 12 5 M15 9.34V5a3 3 0 0 0-5.68-1.33 M9 9v3a3 3 0 0 0 5.12 2.12 M12 19L12 22"
        const val VIDEO = "M16 13l5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5 M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z"
        const val VIDEO_OFF = "M10.66 6H14a2 2 0 0 1 2 2v2.5l5.248-3.062A.5.5 0 0 1 22 7.87v8.196 M16 16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2 M2 2l20 20"
        const val SWORDS = "M14.5 17.5L3 6L3 3L6 3L17.5 14.5 M13 19L19 13 M16 16L20 20 M19 21L21 19 M14.5 6.5L18 3L21 3L21 6L17.5 9.5 M5 14L9 18 M7 17L4 20 M3 19L5 21"
        const val MUSIC = "M4 18a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z M12 18V2l7 4"
        const val TICKET = "M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z M13 5v2 M13 17v2 M13 11v2"
        const val TIMER = "M10 2L14 2 M12 14L15 11 M4 14a8 8 0 1 0 16 0a8 8 0 1 0 -16 0z"
        const val TROPHY = "M6 9H4.5a2.5 2.5 0 0 1 0-5H6 M18 9h1.5a2.5 2.5 0 0 0 0-5H18 M4 22h16 M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22 M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22 M18 2H6v7a6 6 0 0 0 12 0V2Z"
        const val VOLUME = "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z M16 9a5 5 0 0 1 0 6 M19.364 18.364a9 9 0 0 0 0-12.728"
        const val VOLUME_X = "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z M22 9L16 15 M16 9L22 15"
        const val LOG_OUT = "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17L21 12L16 7 M21 12L9 12"
        const val SQUARE = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z"
        const val PLAY = "M6 3L20 12L6 21L6 3z"
        const val FLAG = "M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z M4 22L4 15"
        const val BAN = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z M4.9 4.9l14.2 14.2"
        const val USER_X = "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z M17 8L22 13 M22 8L17 13"
        const val COINS = "M2 8a6 6 0 1 0 12 0a6 6 0 1 0 -12 0z M18.09 10.37A6 6 0 1 1 10.34 18 M7 6h1v4 M16.71 13.88l.7.71-2.82 2.82"
        const val FLASK = "M14 2v6a2 2 0 0 0 .245.96l5.51 10.08A2 2 0 0 1 18 22H6a2 2 0 0 1-1.755-2.96l5.51-10.08A2 2 0 0 0 10 8V2 M6.453 15h11.094 M8.5 2h7"
        const val RADIO = "M4.9 19.1C1 15.2 1 8.8 4.9 4.9 M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5 M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5 M19.1 4.9C23 8.8 23 15.1 19.1 19"
        const val TRASH = "M3 6h18 M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6 M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2 M10 11L10 17 M14 11L14 17"
        const val SHIELD_CHECK = "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z M9 12l2 2 4-4"
        const val HAND = "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2 M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2 M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8 M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"
        const val CROWN = "M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4.276 3.664a1 1 0 0 0 1.516-.294z M5 21h14"
    }
}

/** The web's CoinIcon (gold coin with the money mark). */
class CoinIcon(sizeDp: Float) : Drawable() {
    private val px = Ui.dpi(sizeDp)
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#f5a400") }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#ffc83d") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Ui.c("#e08e00") }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; color = Ui.c("#a65f00") }
    private val s = PathParser.createPathFromPathData("M14.6 9.2c-.5-.9-1.5-1.4-2.6-1.4-1.6 0-2.7.9-2.7 2.1 0 2.8 5.6 1.6 5.6 4.3 0 1.3-1.2 2.2-2.9 2.2-1.2 0-2.3-.6-2.8-1.6M12 6.4v11.2")
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f)
        c.drawCircle(12f, 12f, 10.5f, outer); c.drawCircle(12f, 12f, 8.3f, inner); c.drawCircle(12f, 12f, 8.3f, ring); c.drawPath(s, mark)
        c.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The web's Verified mark (purple badge, white tick). */
class VerifiedBadge(sizeDp: Float) : Drawable() {
    private val px = Ui.dpi(sizeDp)
    private val star = PathParser.createPathFromPathData("M12 1.8l2.4 1.8 3-.2.9 2.9 2.5 1.7-.9 2.9.9 2.9-2.5 1.7-.9 2.9-3-.2L12 22.2l-2.4-1.8-3 .2-.9-2.9-2.5-1.7.9-2.9-.9-2.9 2.5-1.7.9-2.9 3 .2z")
    private val tick = PathParser.createPathFromPathData("M7.6 12.2l3 3 5.8-6")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#8b3cf0") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat()); c.scale(bounds.width() / 24f, bounds.height() / 24f)
        c.drawPath(star, fill); c.drawPath(tick, line); c.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The heart of the "True Find" logo (DanceLogo). */
class LogoHeart : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heart = PathParser.createPathFromPathData("M13 21.5C6 16.6 2 12.9 2 8.4 2 5.3 4.4 3 7.4 3c2.2 0 4.1 1.2 5.6 3.1C14.5 4.2 16.4 3 18.6 3 21.6 3 24 5.3 24 8.4c0 4.5-4 8.2-11 13.1z")
    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 26f, b.height() / 24f)
        p.shader = LinearGradient(0f, 0f, 26f, 24f, Ui.c("#c45cff"), Ui.c("#6a0fd8"), Shader.TileMode.CLAMP)
        c.drawPath(heart, p); c.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The picture shown for anyone without a photo (default-avatar.svg). */
object DefaultFace {
    private val body = PathParser.createPathFromPathData("M20 130C22 94 42 78 62 78S102 94 104 130Z")
    fun draw(c: Canvas, l: Float, t: Float, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val side = max(w, h)
        c.save()
        c.clipRect(l, t, l + w, t + h)
        c.translate(l + (w - side) / 2f, t + (h - side) / 2f)
        c.scale(side / 124f, side / 124f)
        p.shader = RadialGradient(124f * 0.35f, 124f * 0.35f, 124f * 0.75f, Ui.c("#F5F3FF"), Ui.c("#C4B5FD"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 124f, 124f, p)
        p.shader = null; p.color = Color.WHITE; p.alpha = (0.92f * 255).toInt()
        c.drawCircle(62f, 48f, 21f, p)
        c.drawPath(body, p)
        c.restore()
    }
}

/** A blinking white dot (the "lr-dot"). */
class BlinkDot(ctx: Context, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BlinkDot.color }
    private val anim = ValueAnimator.ofFloat(1f, 0.35f).apply {
        duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { alpha = it.animatedValue as Float }
    }
    init { ShownRunner(this, anim) } // blinks only while it can be seen
    override fun onDraw(c: Canvas) { c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, p) }
}

/** The five-dot "long train" loader (TfSpinner), blue or white. */
class DotsSpinner(ctx: Context, private val color: Int = Ui.c("#5664EF")) : View(ctx) {
    private companion object {
        val SIZES = floatArrayOf(0.22f, 0.192f, 0.164f, 0.136f, 0.108f)
        val OP = floatArrayOf(1f, 0.8f, 0.62f, 0.45f, 0.3f)
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@DotsSpinner.color }
    private val ease = PathInterpolator(0.5f, 0f, 0.5f, 1f)
    private var start = 0L
    override fun onAttachedToWindow() { super.onAttachedToWindow(); start = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }
    override fun onDraw(c: Canvas) {
        if (!isShown) return
        val size = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val t = ((SystemClock.uptimeMillis() - start) % 1300L) / 1300f
        for (i in 0 until 5) {
            val f = ((t + 0.08f * i / 1.3f) % 1f + 1f) % 1f
            val ang = 360f * ease.getInterpolation(f)
            c.save(); c.translate(cx, cy); c.rotate(ang)
            p.alpha = (255 * OP[i]).toInt()
            c.drawCircle(0f, -size / 2f + size * 0.12f, size * SIZES[i] / 2f, p)
            c.restore()
        }
        postInvalidateOnAnimation()
    }
}

/** Small toast at the bottom (useToast). */
class DanceToast(ctx: Context) : TextView(ctx) {
    private val hideRun = Runnable { animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start() }
    init {
        setTextColor(Color.WHITE); typeface = Ui.bold; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(14f))
        gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f)
        background = GradientDrawable().apply { setColor(Ui.ca("#180c2c", 0.92f)); cornerRadius = Ui.dp(14f) }
        setPadding(Ui.dpi(16f), Ui.dpi(11f), Ui.dpi(16f), Ui.dpi(11f))
        elevation = Ui.dp(12f)
        visibility = GONE
    }
    fun show(msg: String, ms: Long = 2600) {
        if (msg.isBlank()) return
        text = msg
        removeCallbacks(hideRun)
        animate().cancel()
        if (visibility != VISIBLE) { alpha = 0f; visibility = VISIBLE }
        animate().alpha(1f).setDuration(200).start()
        postDelayed(hideRun, ms)
    }
}

/**
 * Bottom sheet (Sheet in LiveRooms.jsx): dims the screen, slides up, can be
 * pulled down to close. [content] goes in a scroll area unless [scroll] is false.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DanceSheet(
    ctx: Context,
    private val host: FrameLayout,
    title: String?,
    private val scroll: Boolean = true,
    pad: Boolean = true,
    private val maxFraction: Float = 0.88f,
    val onClosed: (() -> Unit)? = null,
) : FrameLayout(ctx) {
    private val dim = View(ctx).apply { setBackgroundColor(Ui.ca("#0e061e", 0.5f)); alpha = 0f }
    val panel = object : LinearLayout(ctx) {
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val cap = (host.height * maxFraction).toInt()
            val h = if (cap > 0) MeasureSpec.makeMeasureSpec(min(MeasureSpec.getSize(hSpec), cap), MeasureSpec.AT_MOST) else hSpec
            super.onMeasure(wSpec, h)
        }
        private var downY = 0f
        private var dragging = false
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    val atTop = (scrollView ?: scrollTarget)?.canScrollVertically(-1) != true
                    if (dy > Ui.dp(10f) && atTop) { dragging = true; return true }
                }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; return true }
                MotionEvent.ACTION_MOVE -> { translationY = max(0f, e.rawY - downY); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (translationY > height * 0.22f) close() else animate().translationY(0f).setDuration(200).setInterpolator(DK.EASE).start()
                    dragging = false
                    return true
                }
            }
            return dragging
        }
    }
    val body: LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var scrollView: android.widget.ScrollView? = null
    /** a sheet without its own scroll area (scroll = false): the list inside it, so pulling it down scrolls the list first */
    var scrollTarget: View? = null
    private var closing = false
    var isOpen = false
        private set

    init {
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dim.setOnClickListener { close() }
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(Ui.dp(24f), Ui.dp(24f), Ui.dp(24f), Ui.dp(24f), 0f, 0f, 0f, 0f)
        }
        panel.elevation = Ui.dp(16f)
        panel.isClickable = true
        panel.addView(View(ctx).apply { background = DK.pill(Ui.c("#ddd3ef")) },
            LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(4.5f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dpi(9f) })
        if (title != null) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(20f), Ui.dpi(8f), Ui.dpi(14f), Ui.dpi(4f)) }
            row.addView(Ui.text(ctx, title, 19f, DK.INK).apply { gravity = Gravity.CENTER; setPadding(Ui.dpi(34f), 0, 0, 0) }, LinearLayout.LayoutParams(0, DK.WRAP, 1f))
            row.addView(DK.iconButton(ctx, DIcon(DIcon.X, DK.INK, 22f), 36f, null, "Close").apply { setOnClickListener { close() } })
            panel.addView(row)
        }
        val p = if (pad) intArrayOf(Ui.dpi(18f), Ui.dpi(8f), Ui.dpi(18f), Ui.dpi(18f)) else intArrayOf(0, 0, 0, 0)
        body.setPadding(p[0], p[1], p[2], p[3])
        if (scroll) {
            val sv = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; isFillViewport = false }
            sv.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            scrollView = sv
            panel.addView(sv, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f))
        } else panel.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun open(): DanceSheet {
        if (isOpen) return this
        isOpen = true
        host.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panel.visibility = View.INVISIBLE
        panel.post {
            panel.translationY = panel.height.toFloat().coerceAtLeast(Ui.dp(300f))
            panel.visibility = View.VISIBLE
            panel.animate().translationY(0f).setStartDelay(30).setDuration(340).setInterpolator(DK.EASE).start()
        }
        dim.animate().alpha(1f).setDuration(200).start()
        return this
    }

    fun close() {
        if (closing || !isOpen) return
        closing = true
        panel.animate().translationY(panel.height.toFloat()).setStartDelay(0).setDuration(240).setInterpolator(DK.EASE).start()
        dim.animate().alpha(0f).setDuration(240).withEndAction {
            host.removeView(this); isOpen = false; closing = false; onClosed?.invoke()
        }.start()
        hideKeyboard()
    }

    fun hideKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (e: Exception) { }
    }

    /** small centred line under the title */
    fun note(s: String) = body.addView(DK.t(context, s, 13.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
        DK.llp(b = 10f))
}

/**
 * A button whose icon sits right before its text, the two centred together (like the web
 * buttons), also when the button is wider than its content. No text (busy) = no icon.
 */
class DButton(ctx: Context, label: CharSequence, sizeDp: Float, color: Int) : TextView(ctx) {
    init {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(sizeDp))
        setTextColor(color)
        typeface = Ui.bold
        includeFontPadding = false
    }
    override fun onDraw(c: Canvas) {
        val d = compoundDrawablesRelative[0]
        if (d != null) {
            val dW = d.intrinsicWidth; val dH = d.intrinsicHeight
            var textW = 0f
            val l = layout
            if (l != null && !text.isNullOrEmpty()) for (i in 0 until l.lineCount) textW = max(textW, l.getLineWidth(i))
            if (textW <= 0f) d.setBounds(0, 0, 0, 0)
            else {
                val dx = ((width - (dW + compoundDrawablePadding + textW)) / 2f - paddingLeft).toInt().coerceAtLeast(0)
                d.setBounds(dx, 0, dx + dW, dH)
            }
        }
        super.onDraw(c)
    }
}

/** helper to keep one sheet at a time on the screen */
class SheetSlot {
    var current: DanceSheet? = null
        private set
    fun open(s: DanceSheet) {
        current?.let { if (it.isOpen) it.close() }
        current = s
        s.open()
    }
    fun close(): Boolean {
        val c = current ?: return false
        current = null
        if (!c.isOpen) return false
        c.close(); return true
    }
}

/** linear layout background helper: rounded white "card" with soft shadow */
fun View.danceCard(radiusDp: Float = 20f) {
    background = DK.card(radiusDp)
    elevation = Ui.dp(3f)
    outlineSpotShadowColorCompat(Ui.c("#5a0fc2"))
}

/** animate a View's alpha 0→1 in (lr-in) */
fun View.fadeUpIn(delay: Long = 0) {
    alpha = 0f; translationY = Ui.dp(8f)
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(320).setInterpolator(DK.EASE_OUT).start()
}

@Suppress("unused")
private val keepDanceKit = arrayOf<Any>(LayerDrawable::class.java, LinearInterpolator::class.java)
