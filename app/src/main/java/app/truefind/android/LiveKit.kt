package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * LIVE ROOMS (native) — shared pieces: the same catalog (gifts, seat fees, the 40 room levels),
 * colours, badges and small parts as the web page (src/pages/LiveRooms.jsx + LiveRoom.jsx).
 */
object LK {
    // ── catalog: the app and the server use the SAME list (liveRoom function) ──
    val COHOST_FEE = mapOf("video" to 90, "audio" to 10)
    val FEE_SHARE = mapOf("video" to 0.5, "audio" to 0.6)
    const val GIFT_SHARE = 0.7
    const val MAX_COHOSTS = 5
    /** owner rule (Nov 16): no normal chat in Live Rooms — every message is a 2-coin Super Chat */
    const val SUPER_CHAT_COINS = 2
    fun seatsOf(mode: String) = if (mode == "audio") MAX_COHOSTS else 1
    fun feeOf(mode: String) = COHOST_FEE[if (mode == "video") "video" else "audio"] ?: 10

    /** gifts: the same list as Live Dance, but the Heart costs 5 coins here (owner rule, Nov 16: the cheapest gift) */
    val GIFTS: List<DK.Gift> = DK.GIFTS.map { if (it.id == "heart") DK.Gift("heart", it.e, it.name, 5, it.g) else it }
    fun gift(id: String?): DK.Gift? = GIFTS.firstOrNull { it.id == id }
    val HEART: DK.Gift get() = gift("heart")!!
    val GIFT_COUNTS = listOf(1, 5, 10, 20)

    val CATEGORIES = listOf("Chat", "Music", "Friends", "Advice", "Fun")
    val LANGUAGES = listOf("Hindi", "English", "Hinglish")
    val REASONS = listOf("Abuse or bad language", "Nudity or adult content", "Harassment or threats", "Spam or promotion", "Asking for money / numbers", "Fake profile", "Other")

    // ── room levels 1–40: total XP, live days, live hours, unique visitors, key reward ──
    class Lv(val xp: Long, val days: Int, val hours: Int, val visitors: Int, val reward: String)
    val LEVELS = listOf(
        Lv(0, 0, 0, 0, "Basic Room"), Lv(500, 2, 2, 10, "Bronze Badge"), Lv(1500, 4, 5, 25, "Room Frame (Basic)"), Lv(3000, 6, 10, 50, "Name Color"),
        Lv(5000, 8, 15, 100, "Silver Badge"), Lv(8000, 10, 25, 200, "Room Background"), Lv(12000, 12, 35, 350, "Premium Host Tag"), Lv(18000, 15, 50, 500, "Golden Frame"),
        Lv(25000, 18, 70, 750, "Entry Effect"), Lv(35000, 20, 100, 1000, "Gold Badge"), Lv(50000, 25, 150, 1500, "Special Room Theme"), Lv(65000, 28, 180, 2000, "Elite Badge"),
        Lv(80000, 30, 220, 2500, "New Room Banner"), Lv(90000, 32, 260, 3000, "Chat Highlight"), Lv(100000, 35, 300, 3500, "Elite Frame"), Lv(120000, 38, 350, 4000, "Royal Badge (Bronze)"),
        Lv(140000, 40, 400, 5000, "Royal Frame"), Lv(160000, 45, 500, 6000, "Room Spotlight"), Lv(180000, 48, 600, 7000, "Host Crown (Basic)"), Lv(200000, 50, 700, 8000, "Royal Badge (Silver)"),
        Lv(250000, 55, 800, 9000, "Legendary Tag"), Lv(300000, 60, 900, 10000, "Custom Room Cover"), Lv(350000, 65, 1000, 12000, "VIP Room Skin"), Lv(400000, 70, 1200, 15000, "Animated Entry"),
        Lv(450000, 75, 1400, 18000, "Legendary Frame"), Lv(500000, 80, 1600, 20000, "Titanium Badge"), Lv(550000, 85, 1800, 22000, "Titanium Theme"), Lv(600000, 90, 2000, 25000, "Special Effects"),
        Lv(700000, 95, 2200, 28000, "Top Room Boost"), Lv(800000, 100, 2500, 30000, "Titanium Crown"), Lv(1000000, 110, 3000, 35000, "Mythic Badge"), Lv(1200000, 120, 3500, 40000, "Mythic Frame"),
        Lv(1400000, 130, 4000, 45000, "Room Promotion"), Lv(1600000, 140, 4500, 50000, "Exclusive Theme"), Lv(1800000, 150, 5000, 60000, "Mythic Crown"), Lv(2000000, 160, 5500, 70000, "Ultimate Badge"),
        Lv(2500000, 170, 6000, 80000, "Global Spotlight"), Lv(3000000, 180, 7000, 90000, "Exclusive Entry"), Lv(3500000, 190, 8000, 100000, "Ultimate Frame"), Lv(4000000, 200, 10000, 150000, "Ultimate Crown"),
    )
    // XP = live time + real visitors + active days + gifts (gifts are only a bonus)
    const val XP_PER_LIVE_MINUTE = 4
    const val XP_PER_VISITOR = 10
    const val XP_PER_ACTIVE_DAY = 100
    const val XP_PER_GIFT_COINS = 10

    // ── level tiers (Bronze → Ultimate) ──
    class Tier(val name: String, val from: Int, val to: Int, val c: IntArray, val crown: Int, val deco: String, val wing: IntArray?, val gem: Int?, val hex: List<String>) {
        var level = 1
        val label: String get() = name + "+".repeat(level - from)
    }
    private fun tg(from: Int, to: Int, name: String, c: List<String>, crown: String, deco: String, wing: List<String>? = null, gem: String? = null) =
        arrayOf<Any?>(from, to, name, c, crown, deco, wing, gem)
    private val TIERS = listOf(
        tg(1, 4, "Bronze", listOf("#f0b27a", "#c26a2c", "#6e3510"), "#e39a5a", "laurel"),
        tg(5, 8, "Silver", listOf("#e3e8ef", "#9aa5b4", "#4f5a6b"), "#c7cfdb", "laurel"),
        tg(9, 12, "Gold", listOf("#ffe08a", "#f0a92b", "#9a5a05"), "#ffc93c", "laurel"),
        tg(13, 16, "Platinum", listOf("#c9a8f5", "#8a55d8", "#3f1a86"), "#a87ef0", "laurel"),
        tg(17, 20, "Crystal", listOf("#b9a3ff", "#7442f5", "#3412a8"), "#9c7dff", "laurel"),
        tg(21, 24, "Diamond", listOf("#9fd0ff", "#2f86ee", "#0b3c96"), "#6fb4ff", "wings", listOf("#cfe7ff", "#4a9cf5")),
        tg(25, 27, "Elite", listOf("#d4b0ff", "#9150f0", "#4b16a8"), "#b58af8", "wings", listOf("#ead9ff", "#9a62f2")),
        tg(28, 30, "Royal", listOf("#ffb3dc", "#e0429c", "#86124f"), "#ff7cc0", "wings", listOf("#ffd6ec", "#e65aa8")),
        tg(31, 32, "Legendary", listOf("#cfa8ff", "#8a3ff0", "#3d1295"), "#b07cff", "wings", listOf("#e2ccff", "#8d4cf0")),
        tg(33, 34, "Titanium", listOf("#c3d3ec", "#5d80b8", "#233f6e"), "#a9bfe0", "wings", listOf("#e0e9f7", "#6f8fc2")),
        tg(35, 37, "Mythic", listOf("#ffb8f4", "#d63fc6", "#6e0f6a"), "#ff86ea", "wings", listOf("#ffd9f8", "#d95ccb"), "#7c3aed"),
        tg(38, 39, "Master", listOf("#ff9a9a", "#d0263a", "#6e0712"), "#ffd257", "gold", listOf("#fff0b8", "#e8a21a"), "#2563eb"),
        tg(40, 40, "Ultimate", listOf("#ff8f8f", "#c8162c", "#5e0410"), "#ffd84f", "gold", listOf("#fff3c4", "#eaa416"), "#2563eb"),
    )
    @Suppress("UNCHECKED_CAST")
    fun tierOf(level: Int): Tier {
        val lv = level.coerceIn(1, 40)
        val g = TIERS.firstOrNull { lv >= it[0] as Int && lv <= it[1] as Int } ?: TIERS[0]
        val hex = g[3] as List<String>
        val wing = (g[6] as List<String>?)?.map { Ui.c(it) }?.toIntArray()
        return Tier(g[2] as String, g[0] as Int, g[1] as Int, hex.map { Ui.c(it) }.toIntArray(), Ui.c(g[4] as String), g[5] as String, wing, (g[7] as String?)?.let { Ui.c(it) },
            hex + listOf(g[4] as String) + ((g[6] as List<String>?) ?: emptyList()) + listOfNotNull(g[7] as String?)).also { it.level = lv }
    }

    /** the shield badge of a level (LevelBadge) without its "Lv.N" text, as SVG (drawn by the app's own SVG painter) */
    private val badgeCache = HashMap<Int, Svg>()
    fun badgeSvg(level: Int): Svg = synchronized(badgeCache) {
        badgeCache.getOrPut(level.coerceIn(1, 40)) {
            val t = tierOf(level)
            val h = t.hex
            val c1 = h[0]; val c2 = h[1]; val c3 = h[2]; val crown = h[3]
            val sb = StringBuilder("<svg viewBox=\"0 0 120 110\"><defs>")
            sb.append("<linearGradient id=\"s\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"$c1\"/><stop offset=\".55\" stop-color=\"$c2\"/><stop offset=\"1\" stop-color=\"$c3\"/></linearGradient>")
            sb.append("<linearGradient id=\"l\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"$c1\"/><stop offset=\"1\" stop-color=\"$c2\"/></linearGradient>")
            sb.append("<linearGradient id=\"c\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#fff6d6\"/><stop offset=\".45\" stop-color=\"$crown\"/><stop offset=\"1\" stop-color=\"$c3\"/></linearGradient></defs>")
            fun f(v: Double) = String.format(Locale.US, "%.2f", v)
            if (t.deco == "laurel") {
                for (side in listOf(1, -1)) for (i in 0 until 5) {
                    val a = Math.toRadians(-35.0 + i * 26)
                    val x = 60 - side * (33 + cos(a) * 9) - side * i * 1.2
                    val y = 62 + sin(a) * 26
                    sb.append("<ellipse cx=\"${f(x)}\" cy=\"${f(y)}\" rx=\"7.5\" ry=\"3.6\" fill=\"url(#l)\" stroke=\"$c3\" stroke-width=\".7\" transform=\"rotate(${f(side * (-60.0 + i * 28))} ${f(x)} ${f(y)})\"/>")
                }
            } else {
                val w1 = h.getOrElse(4) { "#ffffff" }; val w2 = h.getOrElse(5) { "#cccccc" }
                val edge = if (t.deco == "gold") "#a06a05" else c3
                for (side in listOf(-1, 1)) {
                    fun X(x: Double) = f(60 + side * x)
                    for (i in 0 until 4) {
                        sb.append("<path d=\"M${X(26.0)} ${44 + i * 7} C ${X(44.0 + i * 3)} ${30 + i * 9}, ${X(56.0 - i * 2)} ${34 + i * 10}, ${X(58.0 - i * 3)} ${44 + i * 10} C ${X(48.0 - i * 2)} ${48 + i * 9}, ${X(38.0)} ${54 + i * 7}, ${X(28.0)} ${56 + i * 6} Z\" fill=\"${if (i % 2 == 1) w2 else w1}\" stroke=\"$edge\" stroke-width=\".8\" opacity=\"${f(1 - i * 0.08)}\"/>")
                    }
                }
            }
            sb.append("<path d=\"M60 24 L90 33 V58 C90 77 77 90 60 98 C43 90 30 77 30 58 V33 Z\" fill=\"url(#s)\" stroke=\"$c3\" stroke-width=\"2.2\"/>")
            sb.append("<path d=\"M60 30 L84 37.5 V58 C84 73.5 74 84 60 91 C46 84 36 73.5 36 58 V37.5 Z\" fill=\"none\" stroke=\"rgba(255,255,255,.45)\" stroke-width=\"1.6\"/>")
            sb.append("<path d=\"M36 40 C 48 34, 72 34, 84 40 L84 48 C 70 43, 50 43, 36 48 Z\" fill=\"rgba(255,255,255,.18)\"/>")
            sb.append("<path d=\"M44 26 L48 12 L55 20 L60 7 L65 20 L72 12 L76 26 Z\" fill=\"url(#c)\" stroke=\"$c3\" stroke-width=\"1.2\" stroke-linejoin=\"round\"/>")
            val gem = if (h.size > 6) h.last() else null
            listOf(48 to 12, 60 to 7, 72 to 12).forEachIndexed { i, (x, y) ->
                sb.append("<circle cx=\"$x\" cy=\"$y\" r=\"2.2\" fill=\"${if (gem != null && i == 1) gem else "#fff3c4"}\" stroke=\"$c3\" stroke-width=\".6\"/>")
            }
            sb.append("</svg>")
            Svg.of(sb.toString())
        }
    }

    // ── numbers & dates ──
    fun n(v: Number?): String = DK.n(v)
    /** short from 10,000 up: 12.5K, 1.2M, 1.5Cr (fmtK) */
    fun k(v: Number?): String {
        val d = v?.toDouble() ?: 0.0
        fun one(x: Double) = String.format(Locale.US, "%.1f", x).removeSuffix(".0")
        return when {
            d >= 1e7 -> one(d / 1e7) + "Cr"
            d >= 1e6 -> one(d / 1e6) + "M"
            d >= 1e4 -> one(d / 1e3) + "K"
            else -> DK.n(d)
        }
    }
    /** always short from 1,000 up: 1.2K, 24.5K, 1.3M (fmtC) */
    fun c(v: Number?): String = DK.c(v)
    /** 18 Nov 2026 */
    fun dateLong(iso: String?): String {
        val t = DK.ms(iso)
        if (t <= 0L) return ""
        return try { SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(java.util.Date(t)) } catch (e: Exception) { "" }
    }

    // ── colours (C in LiveRooms.jsx) ──
    val INK = DK.INK
    val SUB = DK.SUB
    val LINE = DK.LINE
    val SOFT = DK.SOFT
    val PURPLE = DK.PURPLE
    val PINK = DK.PINK
    val GRAD = DK.GRAD
    val RED_GRAD = intArrayOf(Ui.c("#f43f5e"), Ui.c("#dc2626"))
    val PINK_GRAD = intArrayOf(Ui.c("#ff4d8d"), Ui.c("#c026d3"))

    /** the dark "glass" chips on the room (rgba(22,8,44,.48) + a thin white edge) */
    fun glass(radiusDp: Float = 999f, a: Float = 0.48f): GradientDrawable = GradientDrawable().apply {
        setColor(Ui.ca("#16082c", a)); cornerRadius = Ui.dp(radiusDp); setStroke(Ui.dpi(1f), Ui.ca("#ffffff", 0.14f))
    }

    // ── Lucide icons the Live Room uses (the rest are in DIcon) ──
    const val HEADPHONES = "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"
    const val USER_PLUS = "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z M19 8L19 14 M22 11L16 11"
    const val MORE = "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z M18 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z M4 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"
    const val THUMBS_UP = "M7 10v12 M15 5.88L14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z"
    const val THUMBS_DOWN = "M17 14V2 M9 18.12L10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z"
    const val CLOCK = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z M12 6L12 12L16 14"
    const val TRENDING_UP = "M22 7L13.5 15.5L8.5 10.5L2 17 M16 7L22 7L22 13"
    const val STAR = "M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26z"
    const val MEGAPHONE = "M3 11L21 6V18L3 14V11z M11.6 16.8a3 3 0 1 1 -5.8 -1.6"
    const val LINK2 = "M9 17H7A5 5 0 0 1 7 7h2 M15 7h2a5 5 0 1 1 0 10h-2 M8 12L16 12"
    const val LOCK = "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z M7 11V7a5 5 0 0 1 10 0v4"
    const val IMAGE_PLUS = "M16 5h6 M19 2v6 M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5 M21 15L17.914 11.914a2 2 0 0 0 -2.828 0L6 21 M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z"
    const val CHEVRON_DOWN = "M6 9l6 6 6-6"
    const val SPARKLES = "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z M20 3v4 M22 5h-4 M4 17v2 M5 18H3"

    /** the blue diamond of the Leaderboard (DiamondIcon) */
    fun diamond(sizeDp: Float): Drawable = Tf.svg("<svg viewBox=\"0 0 24 24\"><path d=\"M6.5 3h11l4 6-9.5 12L2.5 9z\" fill=\"#60a5fa\"/>" +
        "<path d=\"M6.5 3l3 6h5l3-6M2.5 9h19M9.5 9L12 21l2.5-12\" fill=\"none\" stroke=\"#1d4ed8\" stroke-width=\"1.1\" stroke-linejoin=\"round\"/>" +
        "<path d=\"M7.5 4.5l2 4h-5z\" fill=\"#bfdbfe\"/></svg>", sizeDp)

    // ── small parts ──
    /** "Lv.15" pill in the tier's colours (LevelChip) */
    fun levelChip(ctx: Context, level: Int, small: Boolean = false): TextView {
        val t = tierOf(level)
        return Ui.text(ctx, "Lv.${t.level}", if (small) 10f else 11.5f, Color.WHITE).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.01f
            setShadowLayer(Ui.dp(1f), 0f, Ui.dp(1f), t.c[2])
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.c[0], t.c[1])).apply { cornerRadius = Ui.dp(99f); setStroke(Ui.dpi(1f), t.c[2]) }
            setPadding(Ui.dpi(if (small) 6f else 8f), 0, Ui.dpi(if (small) 6f else 8f), 0)
            minHeight = Ui.dpi(if (small) 17f else 20f)
            isSingleLine = true
        }
    }

    /** "👑 Host" / "👥 Co-Host" tag (HostTag) */
    fun hostTag(ctx: Context, co: Boolean, small: Boolean = false): TextView =
        Ui.text(ctx, if (co) "👥 Co-Host" else "👑 Host", if (small) 11.5f else 13f, if (co) Ui.c("#4c1d95") else Color.WHITE).apply {
            gravity = Gravity.CENTER; isSingleLine = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (co) intArrayOf(Ui.c("#e9d8ff"), Ui.c("#c9a8f7")) else intArrayOf(Ui.c("#a855f7"), Ui.c("#7a1fe4"))).apply { cornerRadius = Ui.dp(99f) }
            setPadding(Ui.dpi(if (small) 7f else 10f), 0, Ui.dpi(if (small) 7f else 10f), 0)
            minHeight = Ui.dpi(if (small) 20f else 24f)
        }

    /** a round icon button on the dark room (roundBtn): dark glass, or white with a purple icon */
    fun roundBtn(ctx: Context, icon: Drawable, sizeDp: Float, light: Boolean, desc: String, bg: Drawable? = null): FrameLayout =
        DK.iconButton(ctx, icon, sizeDp, bg ?: (if (light) DK.oval(Color.WHITE) else DK.oval(Ui.ca("#16082c", 0.5f))), desc).apply {
            if (light) elevation = Ui.dp(4f)
        }

    /** text with the purple Verified mark after it */
    fun nameBadge(ctx: Context, name: String, sizeDp: Float, color: Int, badgeDp: Float, bold: Boolean = true): TextView =
        DK.t(ctx, name, sizeDp, color, bold).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, VerifiedBadge(badgeDp), null)
            compoundDrawablePadding = Ui.dpi(5f)
        }

    /** a ⋯/icon text row of a menu sheet */
    fun ellipsize(tv: TextView): TextView { tv.isSingleLine = true; tv.ellipsize = TextUtils.TruncateAt.END; return tv }

    fun round(v: Double): Int = v.roundToInt()
    /** ms → "2026-11-18T07:15:00.000Z" (the server's own time format) */
    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
}

/**
 * The room level shield (LevelBadge): crown, laurels (levels 1–20) or wings (21–40), "Lv.N",
 * and the tier name under it when [withName].
 */
class LevelBadgeView(ctx: Context, private val sizeDp: Float, private val withName: Boolean = false) : View(ctx) {
    var level = 1
        set(v) {
            val x = v.coerceIn(1, 40)
            if (x != field || svg == null) { field = x; tier = LK.tierOf(x); svg = LK.badgeSvg(x); contentDescription = "Level ${tier.level} ${tier.label}"; requestLayout(); invalidate() }
        }
    private var tier = LK.tierOf(1)
    private var svg: Svg? = null
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    init { level = 1 }

    private fun nameDp() = max(10f, sizeDp * 0.17f)
    override fun onMeasure(w: Int, h: Int) {
        val ww = Ui.dpi(sizeDp)
        val hh = (Ui.dp(sizeDp) * 0.92f + if (withName) Ui.dp(2f + nameDp() * 1.25f) else 0f).toInt()
        setMeasuredDimension(ww, hh)
    }

    override fun onDraw(c: Canvas) {
        val s = svg ?: return
        val w = Ui.dp(sizeDp); val bh = w * 0.92f
        s.draw(c, 0f, 0f, w, bh)
        val k = min(w / 120f, bh / 110f)
        val ox = (w - 120f * k) / 2f; val oy = (bh - 110f * k) / 2f
        text.typeface = Ui.bold; edge.typeface = Ui.bold
        text.textSize = 21f * k; edge.textSize = 21f * k
        text.letterSpacing = -0.5f / 21f; edge.letterSpacing = text.letterSpacing
        edge.strokeWidth = 3f * k; edge.color = tier.c[2]
        val label = "Lv.${tier.level}"
        c.drawText(label, ox + 60f * k, oy + 68f * k, edge)
        c.drawText(label, ox + 60f * k, oy + 68f * k, text)
        if (withName) {
            name.typeface = Ui.bold; name.textSize = Ui.dp(nameDp())
            name.color = if (tier.hex[2] == "#4f5a6b") Ui.c("#46505f") else tier.c[2]
            c.drawText(tier.label, w / 2f, bh + Ui.dp(2f) + name.textSize * 0.95f, name)
        }
    }
}

/** mic on / off badge (MicBadge): purple with a mic, or red with a crossed mic, with a white edge */
class MicBadgeView(ctx: Context, private val sizeDp: Float) : View(ctx) {
    var off = false
        set(v) { if (v != field) { field = v; contentDescription = if (v) "Mic off" else "Mic on"; invalidate() } }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var on: DIcon? = null
    private var offIcon: DIcon? = null
    init { contentDescription = "Mic on" }
    override fun onMeasure(w: Int, h: Int) { val s = Ui.dpi(sizeDp); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        c.drawCircle(r, r, r, ring)
        val inner = r - Ui.dp(2f)
        fill.shader = if (off) null else LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Ui.c("#a855f7"), Ui.c("#7c1fe4"), Shader.TileMode.CLAMP)
        fill.color = if (off) Ui.c("#ef4444") else Color.WHITE
        c.drawCircle(r, r, inner, fill)
        val iconPx = (width * 0.55f).toInt()
        val d = if (off) (offIcon ?: DIcon(DIcon.MIC_OFF, Color.WHITE, sizeDp * 0.55f).also { offIcon = it }) else (on ?: DIcon(DIcon.MIC, Color.WHITE, sizeDp * 0.55f).also { on = it })
        val l = (width - iconPx) / 2
        d.setBounds(l, l, l + iconPx, l + iconPx)
        d.draw(c)
    }
}

/** the voice waves next to the host (Wave): 10 bars that move while the host talks */
class WaveView(ctx: Context, private val flip: Boolean) : View(ctx) {
    private val bars = floatArrayOf(10f, 22f, 34f, 18f, 40f, 26f, 14f, 30f, 20f, 12f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var start = 0L
    var on = false
        set(v) { if (v != field) { field = v; start = SystemClock.uptimeMillis(); invalidate() } }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(Ui.dpi(bars.size * 3.5f + (bars.size - 1) * 3f), Ui.dpi(44f)) }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Ui.c("#f0abfc"), Ui.c("#a855f7"), Shader.TileMode.CLAMP) }
    override fun onDraw(c: Canvas) {
        val moving = on && !Lite.reducedMotion
        p.alpha = if (on) 255 else (0.35f * 255).toInt()
        val now = SystemClock.uptimeMillis()
        val cy = height / 2f
        for (i in bars.indices) {
            val idx = if (flip) bars.size - 1 - i else i
            var hh = Ui.dp(bars[idx])
            if (moving) {
                // lrWave: 0.9 s, scaleY .35 → 1 → .35, each bar a little later
                val t = (((now - start) / 900f) - (idx % 5) * 0.12f / 0.9f).let { it - kotlin.math.floor(it) }
                val k = 0.35f + 0.65f * ((1f - cos(t * 2f * Math.PI.toFloat())) / 2f)
                hh *= k
            }
            val x = Ui.dp(i * 6.5f)
            r.set(x, cy - hh / 2f, x + Ui.dp(3.5f), cy + hh / 2f)
            c.drawRoundRect(r, Ui.dp(3f), Ui.dp(3f), p)
        }
        if (moving && isShown) postInvalidateOnAnimation()
    }
}

/**
 * The host's round photo on the audio stage: a soft purple glow ring that pulses while the host
 * talks (lr-glow), the photo, and the mic badge.
 */
class HostFace(ctx: Context, private val faceDp: Float) : FrameLayout(ctx) {
    val face = AvatarView(ctx, faceDp)
    val mic = MicBadgeView(ctx, 34f)
    private val glow = object : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shaderR = -1f
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = Ui.dp(faceDp) / 2f
            val k = if (speaking && !Lite.reducedMotion) ((1f - cos(((SystemClock.uptimeMillis() % 2000L) / 2000f) * 2f * Math.PI.toFloat())) / 2f) else 0f
            val big = r + Ui.dp(40f)
            if (shaderR != big) {
                halo.shader = RadialGradient(cx, cy, big, intArrayOf(Ui.ca("#a03cff", 0.55f), Ui.ca("#a03cff", 0.18f), Color.TRANSPARENT), floatArrayOf(r / big, (r + Ui.dp(16f)) / big, 1f), Shader.TileMode.CLAMP)
                shaderR = big
            }
            halo.alpha = (255 * (0.75f + 0.25f * k)).toInt()
            c.drawCircle(cx, cy, big, halo)
            ring.strokeWidth = Ui.dp(4f + 2f * k)
            ring.color = if (k > 0f) Ui.ca("#ce8cff", 0.55f + 0.2f * k) else Ui.ca("#ba68ff", 0.55f)
            c.drawCircle(cx, cy, r + ring.strokeWidth / 2f, ring)
            if (speaking && !Lite.reducedMotion && isShown) postInvalidateOnAnimation()
        }
    }
    var speaking = false
        set(v) { if (v != field) { field = v; glow.invalidate() } }

    init {
        clipChildren = false; clipToPadding = false
        val pad = Ui.dpi(44f)
        addView(glow, LayoutParams(Ui.dpi(faceDp) + pad * 2, Ui.dpi(faceDp) + pad * 2, Gravity.CENTER))
        addView(face, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // web: right 0, bottom 4 on the photo
        addView(mic, LayoutParams(Ui.dpi(34f), Ui.dpi(34f), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = Ui.dpi(4f) })
    }
    override fun onMeasure(w: Int, h: Int) {
        val s = Ui.dpi(faceDp)
        super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
    }
}

/** one Co-Host seat on the audio stage: photo with a ring (bright while talking) and the mic badge */
class SeatFace(ctx: Context, private val faceDp: Float) : FrameLayout(ctx) {
    val face = AvatarView(ctx, faceDp)
    val mic = MicBadgeView(ctx, 22f)
    private val ring = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = Ui.dp(faceDp) / 2f
            if (speaking) {
                val big = r + Ui.dp(16f)
                glowP.shader = RadialGradient(cx, cy, big, intArrayOf(Ui.ca("#c084fc", 0.7f), Color.TRANSPARENT), floatArrayOf(r / big, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, big, glowP)
                p.color = Ui.c("#c084fc"); p.strokeWidth = Ui.dp(3f)
            } else { p.color = Ui.ca("#ffffff", 0.55f); p.strokeWidth = Ui.dp(2f) }
            c.drawCircle(cx, cy, r + p.strokeWidth / 2f, p)
        }
    }
    var speaking = false
        set(v) { if (v != field) { field = v; ring.invalidate() } }
    init {
        clipChildren = false; clipToPadding = false
        addView(ring, LayoutParams(Ui.dpi(faceDp + 36f), Ui.dpi(faceDp + 36f), Gravity.CENTER))
        addView(face, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(mic, LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.BOTTOM or Gravity.END).apply { rightMargin = -Ui.dpi(3f); bottomMargin = -Ui.dpi(2f) })
    }
    override fun onMeasure(w: Int, h: Int) {
        val s = Ui.dpi(faceDp)
        super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
    }
}

/**
 * Backgrounds of the Live Room pages, drawn like the web's CSS gradients:
 *  - [room]: the dark purple audio stage (radial 100% 55% at 50% 28%)
 *  - [page]: the light page of the list (radial 120% 40% at 50% 0%)
 *  - [audioBox]: the big Audio Room box (glow top-right + 135° purple)
 */
object LiveBg {
    private fun ellipse(b: android.graphics.Rect, cxF: Float, cyF: Float, rxF: Float, ryF: Float, colors: IntArray, pos: FloatArray): Shader {
        val sh = RadialGradient(0f, 0f, 1f, colors, pos, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(max(1f, b.width() * rxF), max(1f, b.height() * ryF))
        m.postTranslate(b.left + b.width() * cxF, b.top + b.height() * cyF)
        sh.setLocalMatrix(m)
        return sh
    }
    private abstract class Bg : Drawable() {
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }

    fun room(): Drawable = object : Bg() {
        private val p = Paint()
        private var shaderFor: android.graphics.Rect? = null
        override fun draw(c: Canvas) {
            val b = bounds
            if (shaderFor != b) { p.shader = ellipse(b, 0.5f, 0.28f, 1f, 0.55f, intArrayOf(Ui.c("#6d28d9"), Ui.c("#3b0d7a"), Ui.c("#1a0636")), floatArrayOf(0f, 0.45f, 0.8f)); shaderFor = android.graphics.Rect(b) }
            c.drawRect(b, p)
        }
    }

    fun page(): Drawable = object : Bg() {
        private val p = Paint()
        private var shaderFor: android.graphics.Rect? = null
        override fun draw(c: Canvas) {
            val b = bounds
            if (shaderFor != b) { p.shader = ellipse(b, 0.5f, 0f, 1.2f, 0.4f, intArrayOf(Ui.c("#f1e6ff"), Ui.c("#faf7ff")), floatArrayOf(0f, 0.6f)); shaderFor = android.graphics.Rect(b) }
            c.drawRect(b, p)
        }
    }

    fun audioBox(): Drawable = object : Bg() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            p.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), intArrayOf(Ui.c("#4f46e5"), Ui.c("#6d28d9"), Ui.c("#2a0f5c")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = ellipse(b, 0.85f, 0.1f, 0.7f, 0.8f, intArrayOf(Ui.ca("#78aaff", 0.5f), Color.TRANSPARENT), floatArrayOf(0f, 0.7f))
            c.drawRect(b, p)
            p.shader = null
        }
    }

    /** the dark fade under the room's top bar */
    fun topFade(): Drawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.ca("#0a0316", 0.62f), Ui.ca("#0a0316", 0f)))
}

/** an endless value 0 → 1 → 0 that only runs while [v] can be seen (for small glows and pulses) */
fun View.livePulse(ms: Long, onValue: (Float) -> Unit): ValueAnimator {
    val a = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ms; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { val t = it.animatedValue as Float; onValue((1f - cos(t * 2f * Math.PI.toFloat())) / 2f) }
    }
    if (!Lite.reducedMotion) ShownRunner(this, a)
    return a
}

/** a gift box that gently rocks (lr-box) */
fun View.giftRock() {
    if (Lite.reducedMotion) return
    livePulse(2400) { k -> rotation = -3f + 6f * k; translationY = -Ui.dp(5f) * k }
}

@Suppress("unused")
private val keepLiveKit = arrayOf<Any>(ImageView::class.java, TypedValue::class.java)
