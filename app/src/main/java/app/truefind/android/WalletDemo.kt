package app.truefind.android

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * TEST BUILDS ONLY — made-up Wallet data for the emulator pictures on GitHub
 * (MainActivity "tf_wallet" test mode). Never used in the real app.
 *
 * Scene words: "new" (first-recharge offer), "earn" (creator with earnings),
 * "pending" (a withdrawal in process), "girl" (Reconnect girl not verified
 * yet), "plan" (a Reconnect plan running), "admin", "empty" (no history),
 * "wallethang" (the Reconnect coin wallet never answers), "credhang" (the
 * credits never answer), "fail" (both answer 500) — the owner's endless loading, 21 Nov.
 */
class WalletDemo(private val scene: String) : WalletSource {
    private val main = Handler(Looper.getMainLooper())
    private val isNew = scene.contains("new")
    private val creator = scene.contains("earn")
    private val pending = scene.contains("pending")
    private val girl = scene.contains("girl")
    private val plan = scene.contains("plan")
    private val admin = scene.contains("admin")
    private val empty = scene.contains("empty") || isNew
    private var coins = if (isNew) 0L else 1240L
    private val walletHang = scene.contains("wallethang")
    private val credHang = scene.contains("credhang")
    private val fail = scene.contains("fail")
    /** how many times each function was asked (the test log shows it: no needless asks) */
    private val asked = HashMap<String, Int>()

    private fun iso(hoursAgo: Double): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(System.currentTimeMillis() - (hoursAgo * 3600_000).toLong()))

    private fun later(ms: Long = 450, f: () -> Unit) { main.postDelayed(f, ms) }

    private fun ok(d: JSONObject) = Api.Result(200, d, null)

    override fun fn(name: String, body: JSONObject, done: (Api.Result) -> Unit) {
        val app = body.optString("app", "truefind")
        val key = "$name:$app"
        asked[key] = (asked[key] ?: 0) + 1
        android.util.Log.i("TFWALLET", "asked $key x${asked[key]}")
        if (walletHang && name == "getWallet" && app == "secret") return   // never answers
        if (credHang && name == "getRequestCredits") return               // never answers
        if (fail && (name == "getRequestCredits" || (name == "getWallet" && app == "secret"))) {
            later { done(Api.Result(500, JSONObject().put("error", "Something went wrong. Please try again."), "Something went wrong. Please try again.")) }
            return
        }
        later {
            done(when (name) {
                "getWallet" -> ok(JSONObject()
                    .put("wallet", JSONObject().put("coins", if (app == "secret") 80 else coins).put("earned_coins", if (creator) 8650 else 0))
                    .put("transactions", if (empty) JSONArray() else txs(app))
                    .put("first_offer", isNew)
                    .put("my_profile_id", "demo-profile")
                    .put("account_type", if (creator) "creator" else "user")
                    .put("chat_rate_coins", if (creator) 5 else 0))
                "getRequestCredits" -> ok(JSONObject()
                    .put("credits", JSONObject().put("available_request_credits", if (plan) 5 else 0).put("total_requests_sent", 7))
                    .put("plan", JSONObject().put("active", plan).put("until", if (plan) iso(-120.0) else "").put("name", if (plan) "Starter" else ""))
                    .put("member", JSONObject().put("verified", !girl).put("free", girl && false).put("gender", if (girl) "female" else "male"))
                    .put("intro", JSONObject().put("available", !girl).put("verified", !girl).put("used", plan).put("had_plan", plan))
                    .put("purchases", if (plan) JSONArray()
                        .put(JSONObject().put("id", "p2").put("plan_name", "Starter").put("status", "succeeded").put("credits_purchased", 5).put("created_date", iso(20.0)))
                        .put(JSONObject().put("id", "p1").put("plan_name", "Daily").put("status", "succeeded").put("credits_purchased", 3).put("created_date", iso(200.0)))
                        else JSONArray()))
                "topUpWallet" -> {
                    coins += 500
                    ok(JSONObject().put("wallet", JSONObject().put("coins", coins)).put("transaction_id", "tx_demo_77a1b2c3d4e5"))
                }
                "claimIntroOffer" -> ok(JSONObject().put("ok", true).put("wallet", JSONObject().put("coins", coins - 1)))
                "createPremiumCheckout" -> Api.Result(503, JSONObject().put("error", "Payments are not configured yet."), "Payments are not configured yet.")
                "updateMyProfile" -> ok(JSONObject().put("ok", true).put("payout_request_amount", body.optJSONObject("withdraw")?.optInt("amount") ?: 0).put("payout_requested_at", iso(0.0)))
                else -> Api.Result(404, JSONObject().put("error", "Not found"), "Not found")
            })
        }
    }

    override fun me(done: (user: JSONObject?, profile: JSONObject?, ok: Boolean) -> Unit) {
        later(300) {
            val user = JSONObject().put("id", "demo-user").put("role", if (admin) "admin" else "user")
            val at = iso(300.0)
            val prof = JSONObject().put("id", "demo-profile").put("gender", if (creator) "female" else "male")
            if (creator) {
                prof.put("creator_submitted_at", at).put("creator_reviewed_for", at).put("creator_status", "approved")
                    .put("creator_approved_upi_id", "riya.sharma@okaxis")
                    .put("payout_paid_total", 2000).put("payout_fy", WData.currentFY()).put("payout_fy_share_paid", 1340)
                if (pending) prof.put("payout_request_amount", 1500).put("payout_requested_at", iso(5.0))
            }
            done(user, prof, true)
        }
    }

    private fun txs(app: String): JSONArray {
        val a = JSONArray()
        val rows = if (app == "secret") listOf(
            Triple("Gift sent · Chocolates", -2, 3.0), Triple("Coins added", 100, 30.0), Triple("Gift sent · Good night", -1, 50.0),
        ) else listOf(
            Triple("Video call with Simran", -90, 1.5), Triple("Voice call with Riya", -40, 6.0), Triple("Daily free coins", 10, 20.0),
            Triple("Gift sent · Rose", -10, 26.0), Triple("DEMO top-up (no real money): 500 coins · ₹259 pack", 500, 48.0),
            Triple("Heart Chase Boxes entry", -15, 52.0), Triple("Voice call with Anjali", -30, 75.0), Triple("Daily free coins", 10, 96.0),
        )
        rows.forEachIndexed { i, (d, amt, h) ->
            a.put(JSONObject().put("id", "wtx_demo_${1000 + i}abcdef").put("description", d).put("amount", amt).put("created_date", iso(h))
                .put("type", if (amt >= 0) "topup" else "spend"))
        }
        return a
    }
}
