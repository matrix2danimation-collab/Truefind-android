package app.truefind.android

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * The Wallet's lists and rules — copied from src/pages/Wallet.jsx so the native
 * Wallet sells the same packs, shows the same offers and does the same sums.
 * No Android code in this file (the numbers are checked on a computer).
 */

/** A coin pack: price in ₹ → coins. art = which coin picture (grows with the pack). */
data class WPack(
    val id: String, val price: Long, val coins: Long, val art: Int,
    val tag: String? = null, val was: Long = 0, val offer: Boolean = false,
)

/** A Reconnect plan (Premium + request credits; credits end with the plan). */
data class WPlan(val key: String, val name: String, val price: Int, val credits: Int, val days: String, val tag: String? = null)

/** Withdraw sums (Wallet.jsx payoutBreakdown). */
data class WBreakdown(
    val gross: Long, val fee: Long, val share: Long, val tds: Long, val net: Long,
    val fy: String, val paidThisYear: Double, val tdsApplies: Boolean,
)

/** A withdraw request's state: "none" | "pending" | "paid" | "rejected". */
data class WPayout(val paidTotal: Double, val amount: Double, val at: String, val state: String)

object WData {
    // Coin packs (price in ₹ → coins), owner's price list (27 Nov): 15 coins = 1 minute of calling,
    // regular ≈ ₹10 a minute, premium ≈ ₹8 a minute. Shown biggest first (owner: big packs on top, small ones
    // further down). Keep in sync with the server's shared/playProducts.ts and Play Console (coins_<id>).
    val PACKS = listOf(
        WPack("c49999", 49999, 93750, 8),
        WPack("c25999", 25999, 48750, 7),
        WPack("c12999", 12999, 24375, 7),
        WPack("c4999", 4999, 9375, 6, "Best deal"),
        WPack("c2599", 2599, 4875, 5),
        WPack("c1499", 1499, 2820, 5),
        WPack("c799", 799, 1500, 4),
        WPack("c399", 399, 750, 4, "Most popular"),
        WPack("c199", 199, 375, 3),
        WPack("r149", 149, 225, 3),
        WPack("r60", 60, 90, 2),
        WPack("r15", 15, 22, 1),
    )

    // First time offer (once only; the server checks it too): more coins than the regular rate.
    val OFFERS = listOf(
        WPack("n9", 9, 17, 1, null, 0, true),
        WPack("n49", 49, 94, 1, null, 0, true),
        WPack("n79", 79, 152, 2, null, 0, true),
    )

    const val DEFAULT_PACK = "c399"
    const val REGULAR_COINS_PER_RUPEE = 1.5 // ₹10 = 15 coins = 1 minute

    /** What these coins would cost at the regular rate (₹10 = 15 coins), rounded down. */
    fun regularPrice(p: WPack): Long = Math.floor(p.coins / REGULAR_COINS_PER_RUPEE).toLong()

    /** The struck-out price under a pack: First time offer and premium packs only (regular packs are the regular rate). */
    fun wasPrice(p: WPack): Long = if (p.offer || p.id.startsWith("c")) regularPrice(p) else 0L

    /** "25% MORE" on a First time offer: extra coins over the regular rate, rounded down (never more than true). */
    fun morePct(p: WPack): Int = Math.floor((p.coins / (p.price * REGULAR_COINS_PER_RUPEE) - 1) * 100).toInt()

    /** "18% OFF": how much cheaper than the struck-out price, rounded down (never more than true); 0 = no discount. */
    fun offPct(p: WPack): Int {
        val was = wasPrice(p)
        if (was <= p.price || was <= 0) return 0
        return ((was - p.price) * 100 / was).toInt()
    }

    fun findPack(id: String?): WPack? = OFFERS.find { it.id == id } ?: PACKS.find { it.id == id }

    // Reconnect plans (the server's shared/secretPlans.ts has the same list).
    val PLANS = listOf(
        WPlan("daily", "Daily", 12, 3, "1 day"),
        WPlan("starter", "Starter", 39, 5, "7 days", "Most popular"),
        WPlan("pro", "Pro", 79, 8, "7 days"),
        WPlan("elite", "Elite", 169, 10, "30 days"),
    )

    val FAQ = listOf(
        "Paid, but coins not added?" to "Coins usually reach your wallet within a few seconds. Open the wallet again to refresh. If they are still not there after 30 minutes, contact support with the time of payment.",
        "Payment failed, but money debited?" to "Your bank sends it back to your account automatically, normally within 3–5 working days.",
    )

    // Payment apps shown on the "Select payment method" screen.
    val PAY_APPS = listOf("upi" to "UPI", "gpay" to "Google Pay", "phonepe" to "PhonePe", "paytm" to "Paytm")
    fun methodName(k: String?): String = if (k == "gplay") "Google Play" else (PAY_APPS.find { it.first == k } ?: PAY_APPS[0]).second

    // ───────── Creator earnings / withdraw (change the numbers here if your CA advises) ─────────
    const val MIN_WITHDRAW = 100
    const val APP_FEE_PCT = 33          // True Find's share, GST included
    const val TDS_PCT = 10              // income-tax TDS on the creator's share
    const val TDS_YEAR_LIMIT = 50000    // TDS applies once a creator's yearly payouts cross this

    /** Indian financial year, e.g. "2026-27" (April to March), counted in India time. */
    fun currentFY(now: Long = System.currentTimeMillis()): String {
        val ist = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        ist.timeInMillis = now + (5.5 * 3600 * 1000).toLong()
        val y = ist.get(Calendar.YEAR)
        val start = if (ist.get(Calendar.MONTH) >= 3) y else y - 1
        return "$start-" + ((start + 1) % 100).toString().padStart(2, '0')
    }

    /** Same formula as the admin side uses when paying. */
    fun breakdown(gross: Double, payoutFy: String, fySharePaid: Double, now: Long = System.currentTimeMillis()): WBreakdown {
        val g = Math.max(0.0, Math.floor(if (gross.isFinite()) gross else 0.0)).toLong()
        val fee = jsRound(g * APP_FEE_PCT / 100.0)
        val share = g - fee
        val fy = currentFY(now)
        val paid = if (payoutFy == fy) fySharePaid else 0.0
        val applies = share > 0 && paid + share > TDS_YEAR_LIMIT
        val tds = if (applies) jsRound(share * TDS_PCT / 100.0) else 0L
        return WBreakdown(g, fee, share, tds, share - tds, fy, paid, applies)
    }

    /**
     * "none" | "pending" | "approved" | "rejected" — the admin's decision counts
     * only for the exact submission it was made on (creators and hosts alike).
     */
    fun reviewStatus(submittedAt: String, reviewedFor: String, status: String): String {
        if (submittedAt.isEmpty()) return "none"
        if (reviewedFor.isNotEmpty() && reviewedFor == submittedAt && (status == "approved" || status == "rejected")) return status
        return "pending"
    }

    /** Withdraw request state, from fields on the user's own profile. */
    fun payout(paidTotal: Double, amount: Double, at: String, paidFor: String, rejectedFor: String): WPayout {
        var state = "none"
        if (at.isNotEmpty() && amount > 0) {
            state = when (at) {
                paidFor -> "paid"
                rejectedFor -> "rejected"
                else -> "pending"
            }
        }
        return WPayout(paidTotal, amount, at, state)
    }

    /** What a history line is about (picks its icon). */
    fun txKind(description: String, type: String, amount: Double): String {
        val d = description.ifEmpty { type }.lowercase(Locale.ROOT)
        return when {
            d.contains("video") -> "video"
            d.contains("call") || d.contains("voice") -> "call"
            d.contains("gift") -> "gift"
            d.contains("withdraw") || d.contains("payout") -> "withdraw"
            amount >= 0 -> "add"
            else -> "spend"
        }
    }

    /** Room under the Pay bar inside its holder, so the bar's shadow fades out instead of being cut into a grey box. */
    const val PAY_SHADOW_ROOM = 18f

    /** Where the Pay bar's holder sits above the screen's bottom. The bar's own bottom edge = this + PAY_SHADOW_ROOM:
     *  4px above the True Find bottom bar's height (owner, 27 Nov night: "a little lower", out from under the thumb —
     *  28px lower than before). */
    fun payLiftDp(screenWidthDp: Float): Float = Math.max(60f, Math.min(screenWidthDp, 480f) / 853f * 139f) + 4f - PAY_SHADOW_ROOM

    /** JavaScript's Math.round (halves go up). */
    fun jsRound(v: Double): Long = Math.floor(v + 0.5).toLong()
}

// ───────── numbers and dates, the same as the web ─────────

/** toLocaleString("en-IN"): 1,23,456 — and up to 3 decimals when there are any (9,999.6). */
fun wRupees(v: Double): String {
    if (!v.isFinite()) return if (v.isNaN()) "NaN" else if (v > 0) "∞" else "-∞"
    val d = java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    val neg = d.signum() < 0
    val plain = d.abs().toPlainString()
    val dot = plain.indexOf('.')
    val s = if (dot >= 0) plain.substring(0, dot) else plain
    val frac = if (dot >= 0) plain.substring(dot) else ""
    val whole = if (s.length <= 3) s else {
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        parts.joinToString(",") + "," + last3
    }
    return (if (neg) "-" else "") + whole + frac
}

fun wRupees(v: Long): String = wRupees(v.toDouble())

/**
 * new Date(v) as JavaScript reads it: a date alone is UTC, a date and time
 * without a zone is the phone's local time, "Z" / "+05:30" as written.
 */
fun wParseDate(v: String?): Long? {
    val s = v?.trim().orEmpty()
    if (s.isEmpty()) return null
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$""").find(s) ?: return null
    val g = m.groupValues
    val hasTime = g[4].isNotEmpty()
    val zone = g[8]
    val tz = when {
        zone == "Z" -> TimeZone.getTimeZone("UTC")
        zone.isNotEmpty() -> TimeZone.getTimeZone("GMT" + (if (zone.contains(':')) zone else zone.substring(0, 3) + ":" + zone.substring(3)))
        !hasTime -> TimeZone.getTimeZone("UTC")
        else -> TimeZone.getDefault()
    }
    val month = g[2].toInt(); val day = g[3].toInt()
    val hour = if (hasTime) g[4].toInt() else 0
    val min = if (hasTime) g[5].toInt() else 0
    val sec = g[6].toIntOrNull() ?: 0
    if (month !in 1..12 || day !in 1..31 || hour > 24 || min > 59 || sec > 59) return null
    val cal = Calendar.getInstance(tz)
    cal.clear()
    cal.isLenient = false
    cal.set(g[1].toInt(), month - 1, day, hour, min, sec)
    cal.set(Calendar.MILLISECOND, g[7].let { if (it.isEmpty()) 0 else (it + "00").substring(0, 3).toInt() })
    return try { cal.timeInMillis } catch (e: Exception) { null }
}

/** fmtDate(): "17 Nov, 8:26 PM" (date-fns "d MMM, h:mm a"); "" for a bad date. */
fun wDate(v: String?): String = wParseDate(v)?.let { wDate(it) } ?: ""

fun wDate(t: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.US).format(java.util.Date(t))
