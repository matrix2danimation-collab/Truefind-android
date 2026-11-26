package app.truefind.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * Buying through Google Play (25 Nov): coin packs and Reconnect plans. Google's own payment screen takes the
 * money (UPI — Google Pay, PhonePe, Paytm — card, net banking …); the server then checks the purchase with Google
 * and gives what was bought (verifyPlayPurchase), exactly once. Nothing is given by the phone itself.
 *
 *  1. checkPlayPurchase: may this member buy it now (payments set up, offer not used, …) → the member's id;
 *  2. Google's payment screen, with that id and the wallet ("truefind" | "secret") attached to the purchase;
 *  3. verifyPlayPurchase: the server gives it and marks it used at Google.
 * A purchase whose last step did not finish (no internet, app closed, a UPI payment completed later) is still
 * listed by Google as not used: recover() sends it to the server again (Wallet opened, app back in front).
 *
 * Product ids (Play Console, one-time, consumable): coins_<pack id> (coins_p259, coins_w49 …), sd_<plan> (sd_starter …).
 */
object PlayBilling {
    sealed class Outcome {
        /** the server's answer: { granted, kind: coins|plan, added, wallet, plan, until, … } */
        class Done(val data: JSONObject) : Outcome()
        /** paid later (e.g. UPI still waiting): the coins come when Google confirms it */
        object Pending : Outcome()
        object Cancelled : Outcome()
        /** buying is not open yet (server not set up, product not in Play Console, no Google Play on the phone) */
        class NotReady(val message: String) : Outcome()
        class Failed(val message: String, val code: String = "") : Outcome()
    }

    private const val TAG = "TFPAY"
    private val main = Handler(Looper.getMainLooper())
    private var client: BillingClient? = null
    private var connecting = false
    private val waiting = ArrayList<(Boolean) -> Unit>()
    /** the purchase the buy screen is waiting for: its product, "paid at Google" (the server check starts), its answer */
    private class Wait(val product: String, val paid: () -> Unit, val done: (Outcome) -> Unit)
    private var current: Wait? = null
    /** purchases being checked by the server right now (by purchase token): a second ask for the same one waits for
     *  that answer instead of asking again; shown = the buy screens waiting for it (none: a background check) */
    private class Check { val shown = ArrayList<(Outcome) -> Unit>() }
    private val checking = HashMap<String, Check>()
    /** a purchase that finished outside the buy screen was given now (the Wallet reloads) */
    var onRecovered: ((JSONObject) -> Unit)? = null

    fun productFor(packId: String) = "coins_$packId"
    fun productForPlan(planKey: String) = "sd_$planKey"

    private fun client(ctx: Context): BillingClient = client ?: BillingClient.newBuilder(ctx.applicationContext)
        .setListener { r, list -> main.post { updated(r, list) } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build().also { client = it }

    /** connected to Google Play (once; later calls wait for the same connection) */
    private fun connected(ctx: Context, then: (Boolean) -> Unit) {
        val c = try { client(ctx) } catch (t: Throwable) { Log.w(TAG, "no billing client", t); then(false); return }
        if (c.isReady) { then(true); return }
        waiting.add(then)
        if (connecting) return
        connecting = true
        val giveUp = Runnable { finish(false) }
        main.postDelayed(giveUp, 15000)
        try {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    main.post { main.removeCallbacks(giveUp); Log.i(TAG, "setup ${r.responseCode} ${r.debugMessage}"); finish(r.responseCode == BillingResponseCode.OK) }
                }
                override fun onBillingServiceDisconnected() { /* the client reconnects by itself (enableAutoServiceReconnection) */ }
            })
        } catch (t: Throwable) { main.removeCallbacks(giveUp); Log.w(TAG, "startConnection", t); finish(false) }
    }
    private fun finish(ok: Boolean) {
        if (!connecting) return
        connecting = false
        val w = ArrayList(waiting); waiting.clear()
        w.forEach { it(ok) }
    }

    private fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    /** Buys one product; done gets exactly one Outcome, on the main thread. wallet: "truefind" | "secret".
     *  paid: Google's screen closed with the money paid — the server check (a second or two) starts now. */
    fun buy(ctx: Context, productId: String, wallet: String, paid: () -> Unit = {}, done: (Outcome) -> Unit) {
        if (current != null) { done(Outcome.Failed("A payment is already open.")); return }
        val activity = activityOf(ctx) ?: run { done(Outcome.Failed("Please try again.")); return }
        // 1) the server: may I? (and nobody pays for something the server could not give)
        Api.invokeFront("checkPlayPurchase", JSONObject().put("product_id", productId).put("app", wallet), 20000) { r ->
            val d = r.data
            if (!r.ok) {
                val err = d.optString("error")
                if (r.status == 503 || r.status == 404 || err == "payments_not_ready") done(Outcome.NotReady(d.optString("message").ifEmpty { "Buying is opening soon." }))
                else done(Outcome.Failed(d.optString("message").ifEmpty { d.optString("error") }.ifEmpty { "Could not start the payment. Please try again." }, err))
                return@invokeFront
            }
            val account = d.optString("account_id")
            if (account.isEmpty()) { done(Outcome.Failed("Could not start the payment. Please try again.")); return@invokeFront }
            // 2) Google's payment screen
            connected(activity) { ok ->
                val c = client
                if (!ok || c == null) { done(Outcome.NotReady("Google Play payments are not available on this phone.")); return@connected }
                val q = QueryProductDetailsParams.newBuilder().setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.INAPP).build())).build()
                c.queryProductDetailsAsync(q) { br, res ->
                    main.post {
                        val pd = if (br.responseCode == BillingResponseCode.OK) res.productDetailsList.firstOrNull() else null
                        if (pd == null) {
                            Log.w(TAG, "no product $productId: ${br.responseCode} ${br.debugMessage}")
                            done(Outcome.NotReady("This pack is not on Google Play yet.")); return@post
                        }
                        val offer = pd.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
                        val pdp = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd)
                            .apply { if (!offer.isNullOrEmpty()) setOfferToken(offer) }.build()
                        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(pdp))
                            .setObfuscatedAccountId(account).setObfuscatedProfileId(wallet).build()
                        current = Wait(productId, paid, done)
                        val lr = c.launchBillingFlow(activity, params)
                        if (lr.responseCode != BillingResponseCode.OK) {
                            current = null
                            Log.w(TAG, "launch ${lr.responseCode} ${lr.debugMessage}")
                            done(Outcome.Failed(messageOf(lr)))
                        }
                    }
                }
            }
        }
    }

    private fun updated(r: BillingResult, list: List<Purchase>?) {
        val cb = current
        when (r.responseCode) {
            BillingResponseCode.OK -> {
                val mine = list.orEmpty().firstOrNull { cb != null && it.products.contains(cb.product) }
                if (cb != null && mine == null) { current = null; cb.done(Outcome.Failed("Payment could not be completed.")) }
                list.orEmpty().forEach { p ->
                    if (p === mine && cb != null) {
                        current = null
                        when (p.purchaseState) {
                            Purchase.PurchaseState.PURCHASED -> { cb.paid(); verify(p, cb.done) }
                            Purchase.PurchaseState.PENDING -> cb.done(Outcome.Pending)
                            else -> cb.done(Outcome.Failed("Payment could not be completed."))
                        }
                    } else if (p.purchaseState == Purchase.PurchaseState.PURCHASED) verify(p, null) // e.g. a UPI payment confirmed later
                }
            }
            BillingResponseCode.USER_CANCELED -> { current = null; cb?.done?.invoke(Outcome.Cancelled) }
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // an earlier purchase of this pack is not used yet: give it now, then it can be bought again
                current = null
                cb?.done?.invoke(Outcome.Failed("Your last payment for this pack is still being added. Please wait a moment and try again."))
                client?.let { recoverWith(it) }
            }
            else -> { current = null; Log.w(TAG, "purchase ${r.responseCode} ${r.debugMessage}"); cb?.done?.invoke(Outcome.Failed(messageOf(r))) }
        }
    }

    /** 3) the server checks it with Google and gives it (safe to ask again: it is given once).
     *  done == null: found in the background — the Wallet hears about it (onRecovered) unless a buy screen shows it. */
    private fun verify(p: Purchase, done: ((Outcome) -> Unit)?) {
        val token = p.purchaseToken
        checking[token]?.let { running -> if (done != null) running.shown.add(done); return }
        val product = p.products.firstOrNull() ?: run { done?.invoke(Outcome.Failed("Payment could not be completed.")); return }
        val check = Check().also { if (done != null) it.shown.add(done) }
        checking[token] = check
        val body = JSONObject().put("product_id", product).put("purchase_token", token).put("package", BuildConfig.APPLICATION_ID)
        Api.invokeFront("verifyPlayPurchase", body, 30000) { r ->
            checking.remove(token)
            val d = r.data
            val o = when {
                r.ok && d.optBoolean("pending") -> Outcome.Pending
                r.ok -> Outcome.Done(d)
                else -> Outcome.Failed(d.optString("message").ifEmpty { d.optString("error") }
                    .ifEmpty { "Your payment is safe — it will be added shortly. Open the Wallet again in a moment." }, d.optString("error"))
            }
            Log.i(TAG, "verify $product → ${r.status} ${if (o is Outcome.Done) "granted=${d.optBoolean("granted")}" else o.javaClass.simpleName}")
            if (check.shown.isEmpty()) { if (o is Outcome.Done && d.optBoolean("granted")) onRecovered?.invoke(d) }
            else check.shown.forEach { it(o) }
        }
    }

    /** Purchases Google lists as not used yet go to the server again (paid while the app was away, a lost connection). */
    fun recover(ctx: Context) {
        if (!Auth.has(ctx)) return
        connected(ctx) { ok -> val c = client; if (ok && c != null) recoverWith(c) }
    }
    private fun recoverWith(c: BillingClient) {
        try {
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { br, list ->
                main.post {
                    // a buy screen still open: its own purchase is on the way; anything else waits for the next check
                    if (br.responseCode != BillingResponseCode.OK || current != null) return@post
                    list.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { p ->
                        Log.i(TAG, "unfinished purchase ${p.products} → server")
                        verify(p, null)
                    }
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "recover", t) }
    }

    private fun messageOf(r: BillingResult): String = when (r.responseCode) {
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED ->
            "Google Play is not reachable right now. Check your internet and try again."
        BillingResponseCode.ITEM_UNAVAILABLE -> "This pack is not available right now."
        BillingResponseCode.NETWORK_ERROR -> "No internet. Please try again."
        else -> "Payment could not be completed. Please try again."
    }
}
