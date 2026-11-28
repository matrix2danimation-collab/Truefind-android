package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Where the Wallet gets its data: the real backend, or made-up data for the emulator pictures. */
interface WalletSource {
    /** base44.functions.invoke(name, body) */
    fun fn(name: String, body: JSONObject, done: (Api.Result) -> Unit)
    /** base44.auth.me() and the user's own UserProfile. ok = false when it could not be read. */
    fun me(done: (user: JSONObject?, profile: JSONObject?, ok: Boolean) -> Unit)
}

/** The real backend: the same Base44 functions and records the web Wallet reads. */
object WalletNet : WalletSource {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    // (the front lane: the Wallet's asks never wait behind other pages' reads — Api.invokeFront)
    override fun fn(name: String, body: JSONObject, done: (Api.Result) -> Unit) = Api.invokeFront(name, body, 30000, done)

    override fun me(done: (user: JSONObject?, profile: JSONObject?, ok: Boolean) -> Unit) {
        pool.execute {
            val u = get("/entities/User/me")
            var prof: JSONObject? = null
            var ok = u != null
            val id = u?.optString("id").orEmpty()
            if (id.isNotEmpty()) {
                val q = URLEncoder.encode(JSONObject().put("created_by_id", id).toString(), "UTF-8")
                val r = get("/entities/UserProfile?q=$q&limit=1")
                if (r == null) ok = false else prof = r.optJSONArray("items")?.optJSONObject(0)
            }
            main.post { PageFx.whenStill { done(u, prof, ok) } }
        }
    }

    /** GET /api/apps/{appId}{path} with the shared login; an array answer comes back as { items: [...] }. */
    private fun get(path: String): JSONObject? {
        if (!Api.ready) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${Api.base}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Id", Api.appId)
                setRequestProperty("Authorization", "Bearer ${Auth.fresh()}")
            }
            if (conn.responseCode !in 200..299) return null
            val t = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (t.startsWith("[")) JSONObject().put("items", JSONArray(t)) else JSONObject(t)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * WALLET — native version of src/pages/Wallet.jsx: the True Find coin wallet and
 * the Reconnect wallet (credits and plans), the creators' Earnings tab, the
 * coin packs with the green Pay bar, the payment steps, history and help.
 * Same backend functions, same rules, same look. Full screen (no bottom bar).
 *
 * [go]           opens a page of the app: go(path, stateJson)
 * [webBack]      the header's back arrow (the page before, or Home)
 * [openCheckout] opens the plan payment page in the phone's browser
 * [setAddress]   keeps ?app=secret in the address, as the web does
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class WalletScreen(
    ctx: Context,
    private val go: (String, JSONObject?) -> Unit,
    private val webBack: () -> Unit,
    private val openCheckout: (String) -> Unit,
    private val setAddress: (String) -> Unit,
) : KeepPage(ctx) {

    /** Made-up data for the emulator pictures replaces this (WalletDemo). */
    var source: WalletSource = WalletNet

    private class Cached(
        val wallet: JSONObject?, val transactions: List<JSONObject>, val accountType: String,
        val profileId: String?, val rate: Double, val firstOffer: Boolean,
    )

    /** The buy-coins steps: "pack" | "method" | "opening" | "processing" | "done" | "pending" | "failed" | "soon".
     *  coins: what the server added (-1 = the pack's own number). */
    private class Flow(var step: String, var pack: WPack, var method: String = "upi", var at: Long = 0L, var ref: String = "", var error: String = "", var coins: Long = -1L)

    private val main = Handler(Looper.getMainLooper())
    private val PAY_GREEN = WC.payGreen

    // ── state (the same as the web page's) ──
    private var app = "truefind"
    private val cache = HashMap<String, Cached>()
    private var wallet: JSONObject? = null
    private var transactions: List<JSONObject> = emptyList()
    private var accountType = "user"
    private var profileId: String? = null
    private var rate = 0.0
    private var rateDraft = "0"
    private var firstOffer = false
    /** when the new user offer ends (the member's first 7 days; from the server), 0 = not known */
    private var offerUntilMs = 0L
    private var offerClock: TextView? = null
    private val offerTick = object : Runnable {
        override fun run() {
            val tv = offerClock ?: return
            val left = offerUntilMs - System.currentTimeMillis()
            if (left <= 0) { offerClock = null; firstOffer = false; renderBody(); renderPayBar(); return }
            tv.text = offerLeft(left)
            main.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L + 5L)
        }
    }
    private var loading = true
    /** the first load took too long: the page stops waiting and says so (a late answer still fills it) */
    private var loadSlow = false
    /** the credits could not be read (no answer in 12 s, or an error): the header says so instead of an endless "–" */
    private var creditsFailed = false
    private var credReq = 0
    /** the credits Reconnect Home read a moment ago (used only while fresh: at most 2 minutes old) */
    private var offeredCredits: JSONObject? = null
    private var offeredAt = 0L
    private var savingRate = false
    // (any new note is not a "could not load" one until [loadFailedNote] is set again)
    private var note = ""
        set(v) { field = v; loadFailedNote = false }
    private var showAllTx = false
    private var txFilter = "all"
    private var txOpen: JSONObject? = null
    private var sel: String? = null
    private var planSel: String? = null
    private var flow: Flow? = null
    private var credits: JSONObject? = null
    private var creditsPurchases: List<JSONObject> = emptyList()
    private var buyingCredits = false
    private var sdPlan: JSONObject? = null
    private var sdIntro: JSONObject? = null
    private var sdMember: JSONObject? = null
    private var tfCoins: Double? = null
    private var claimingIntro = false
    private var me: JSONObject? = null
    // What the page was last drawn from (per wallet, and the own profile). Coming back to the Wallet asks the
    // server again; when the answer is the very same, nothing is built again. (Before, the whole page was built
    // twice on every visit — the owner's phone, 19 Nov: 14 of the Wallet's 51 frames late, the longest 262 ms.)
    private val drawnFrom = HashMap<String, String>()
    private var drawnMe: String? = null
    private var isAdmin = false
    private var tab = "coins"
    private var reqId = 0
    private var topUpBusy = false
    private val faqOpen = HashSet<Int>()
    // earnings panel
    private var earnSending = false
    private var earnMsg = ""

    // ── shown / hidden ──
    private var shown = false
    private var everShown = false
    private var hiddenAt = 0L
    private var appAwayAt = 0L

    // ── views ──
    private val T get() = if (app == "secret") WTheme.SECRET else WTheme.TRUEFIND
    private val scroll = WBounceScroll(ctx)
    private val page = WV.box(ctx, true)
    private val headerStrip = FrameLayout(ctx)
    private val header = WMax(ctx)
    private val backBtn = FrameLayout(ctx)
    private val helpBtn = FrameLayout(ctx)
    private val titleSub: TextView
    private val switchBox = WV.box(ctx, false)
    private val switchTabs = ArrayList<TextView>()
    private val balanceBox = FrameLayout(ctx)
    private val headTop = FrameLayout(ctx)
    private val headLeft = WV.box(ctx, true)
    private val headCoins = WalletHeaderCoins(ctx)
    private val minuteChips = WV.box(ctx, false, Gravity.CENTER_VERTICAL)
    private val sheet = WV.box(ctx, true, Gravity.CENTER_HORIZONTAL)
    private val body = WMax(ctx, 480f, paddingInside = true)
    private val payHost: FrameLayout = WPayDock(ctx) { scroll }
    private var payBtn: View? = null
    private var payBar: View? = null
    private var payKey = ""
    private val spinner = PullSpinner(ctx)
    private var sheetUi: WSheet? = null
    private var resultLayer: View? = null
    private var helpSection: View? = null
    private val packTiles = HashMap<String, PackTile>()
    private val planRows = HashMap<String, PlanRow>()
    private var txSection: LinearLayout? = null
    private var draftBox: LinearLayout? = null
    private val amountEdit: EditText by lazy { input("Min ₹${WData.MIN_WITHDRAW}") }
    private val rateEdit: EditText by lazy { input("") }

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true // nothing reaches the web page underneath
        clipChildren = false

        // ───── the page: purple header with the balance, then the white sheet ─────
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipChildren = false
        page.clipChildren = false
        scroll.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.onShift = { px -> for (i in 0 until body.childCount) body.getChildAt(i).translationY = px }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Wallet top (owner, 27 Nov — the approved design): a flat purple card with round bottom corners, "Wallet",
        // the balance; no back / help buttons and no True Find / Reconnect switch (the phone's back still works;
        // the Reconnect wallet opens from Reconnect). Two soft glass layers peek out under it.
        // Design 1 (owner, 27 Nov): the 3D TF coin picture on the right of "Wallet" and the balance, and two minute
        // chips (≈ voice / ≈ video minutes) under the balance.
        header.setPadding(Ui.dpi(20f), Ui.dpi(24f), Ui.dpi(20f), Ui.dpi(26f))
        titleSub = WV.text(ctx, "Wallet", 30f, Color.WHITE, true, 36f)
        header.addView(headTop, WV.lp(WV.MATCH, WV.WRAP))
        headLeft.addView(titleSub, WV.lp(WV.MATCH, WV.WRAP))
        headLeft.setPadding(0, 0, Ui.dpi(100f), 0)
        headTop.addView(headLeft, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        headTop.addView(headCoins, LayoutParams(Ui.dpi(112f), Ui.dpi(84f), Gravity.TOP or Gravity.END).apply { topMargin = Ui.dpi(-8f); rightMargin = Ui.dpi(-6f) })

        // two separate wallets
        switchBox.setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f))
        for ((k, label) in listOf("truefind" to "True Find", "secret" to "Reconnect")) {
            val t = WV.text(ctx, label, 15f, T.off, true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { switchApp(k) }
                tag = k
            }
            switchTabs.add(t)
            switchBox.addView(t, WV.lp(0, WV.MATCH, l = if (switchTabs.size > 1) 4f else 0f, weight = 1f))
        }
        headLeft.addView(balanceBox, WV.lp(WV.MATCH, WV.WRAP, t = 22f))
        header.addView(minuteChips, WV.lp(WV.MATCH, WV.WRAP, t = 16f))
        headerStrip.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#5B21B6"), Ui.c("#4C1D95"), Ui.c("#43188A")),
        ).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, Ui.dp(26f), Ui.dp(26f), Ui.dp(26f), Ui.dp(26f)) }
        headerStrip.elevation = Ui.dp(8f)
        headerStrip.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        val headWrap = FrameLayout(ctx).apply { clipChildren = false }
        fun glass(fill: Int, radius: Float, side: Float, below: Float) = View(ctx).also {
            it.background = WV.round(fill, radius, Color.argb(153, 255, 255, 255))
            headWrap.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dpi(60f), Gravity.BOTTOM).apply {
                leftMargin = Ui.dpi(side); rightMargin = Ui.dpi(side); bottomMargin = Ui.dpi(20f - below)
            })
        }
        // one soft lavender layer peeks out under the purple top (design 1)
        glass(Color.argb(150, 196, 173, 240), 16f, 18f, 12f)
        headWrap.addView(headerStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dpi(20f) })
        page.addView(headWrap, WV.lp(WV.MATCH, WV.WRAP))

        // the white page under it
        sheet.setBackgroundColor(Color.WHITE)
        body.setPadding(Ui.dpi(12f), Ui.dpi(18f), Ui.dpi(12f), Ui.dpi(170f))
        sheet.addView(body, WV.lp(WV.MATCH, WV.WRAP))
        page.addView(sheet, WV.lp(WV.MATCH, 0, weight = 1f))

        // ───── the Pay bar (slides up when a pack or plan is tapped) ─────
        payHost.clipChildren = false
        payHost.clipToPadding = false // the bar's shadow was cut at the holder's padding into a grey box (owner, 27 Nov)
        payHost.setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
        addView(payHost, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dpi(92f + WData.PAY_SHADOW_ROOM), Gravity.BOTTOM))

        // ───── pull down to refresh ─────
        addView(spinner, LayoutParams(Ui.dpi(42f), Ui.dpi(42f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(4f) })
        spinner.elevation = Ui.dp(20f)

        renderAll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val lift = Ui.dpi(WData.payLiftDp(w / Ui.density))
        (payHost.layoutParams as LayoutParams).let { if (it.bottomMargin != lift) { it.bottomMargin = lift; payHost.layoutParams = it } }
    }

    private fun headButton(f: FrameLayout, icon: String, label: String, onTap: () -> Unit) {
        f.contentDescription = label
        f.isClickable = true
        f.setOnClickListener { onTap() }
        f.addView(WIcons.view(context, icon, Color.WHITE, 22f).apply {
            layoutParams = LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER)
        })
    }

    // ═════════════════ shown / hidden (follows the web address) ═════════════════

    /**
     * [query] = the address's ?… part: app=secret, tab=earnings|coins, premium=success|cancelled.
     * [fresh]: opened afresh (a tap), not come back to with Back. A fresh Wallet starts at its top, on the tab its
     * address asks for — Coins unless it says tab=earnings (owner, 19 Nov: it opened where it was left last time,
     * and a call's "+" opened the Earnings tab left over from an earlier visit instead of the coin packs).
     * Coming back with Back keeps the place and the tab.
     */
    fun setShown(on: Boolean, query: String, fresh: Boolean = true) {
        if (on == shown) return
        shown = on
        if (!on) {
            visibility = View.GONE
            hiddenAt = System.currentTimeMillis()
            hideKeyboard()
            return
        }
        visibility = View.VISIBLE
        val q = parseQuery(query)
        val first = !everShown
        everShown = true
        if (fresh && !first) {
            val want = if (q["tab"] == "earnings") "earnings" else "coins"
            if (tab != want) { tab = want; renderBody(); renderPayBar() }
            scroll.scrollTo(0, 0)
            // (and a glide still running from the last visit is replaced by a still one from the top: a ScrollView
            // otherwise finishes an old glide on its first frame back and puts the page where that glide ended)
            scroll.fling(0)
        }
        // back from the plan payment page: that tap is over
        if (buyingCredits) { buyingCredits = false; renderPayBar() }
        // ?app=secret opens the Reconnect wallet, a plain /wallet the True Find wallet
        val target = if (q["app"] == "secret") "secret" else "truefind"
        if (first) {
            app = target
            startLoad()
        } else if (target != app) {
            switchApp(target)
        } else {
            refreshQuietly() // back on the Wallet after another page: new balance and history
        }
        if (q["tab"] == "earnings") { tab = "earnings"; renderBody(); renderPayBar() }
        // back from paying for a plan: say so, and check the plan again shortly
        val st = q["premium"].orEmpty()
        if (q["app"] == "secret" && st.isNotEmpty()) {
            if (st == "success") note = "Payment received — your Reconnect plan is active!"
            else if (st == "cancelled") note = "Payment cancelled. You can choose a plan anytime."
            renderBody()
            main.postDelayed({ loadCredits() }, 3500)
            main.postDelayed({ loadCredits() }, 9000)
        }
    }

    /** The app came back to the screen (or went away). */
    fun onAppVisible(visible: Boolean) {
        if (!visible) { appAwayAt = System.currentTimeMillis(); return }
        if (shown && appAwayAt > 0 && System.currentTimeMillis() - appAwayAt > 60_000) refreshQuietly()
    }

    /** back from a plan's payment page (the phone's browser): that tap is over, the plan is read again */
    fun checkoutClosed() {
        if (buyingCredits) { buyingCredits = false; renderPayBar() }
        if (shown) refreshQuietly()
    }

    /** The phone's back button: closes a pop-up first. */
    fun onBack(): Boolean {
        val f = flow
        if (resultLayer != null) {
            if (f?.step == "processing") return true // wait for the answer
            closeFlow(); return true
        }
        if (sheetUi != null) {
            if (txOpen != null) closeTx() else closeFlow()
            return true
        }
        if (txPage != null) { closeHistory(); return true }
        return false
    }

    private fun parseQuery(q: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (part in q.removePrefix("?").split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = if (i >= 0) part.substring(0, i) else part
            val v = if (i >= 0) part.substring(i + 1) else ""
            try { out[java.net.URLDecoder.decode(k, "UTF-8")] = java.net.URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { }
        }
        return out
    }

    // ═════════════════ data ═════════════════

    private fun startLoad() {
        if (app == "secret") takeOfferedCredits()
        val c = cache[app]
        applyCached(c)
        loading = c?.wallet == null
        renderAll()
        load(initial = true)
        loadProfile()
        if (app == "secret") loadCredits()
    }

    private fun applyCached(c: Cached?) {
        wallet = c?.wallet
        transactions = c?.transactions ?: emptyList()
        firstOffer = c?.firstOffer == true
        if (c != null) {
            accountType = c.accountType; profileId = c.profileId; rate = c.rate; rateDraft = jsNum(c.rate)
        }
    }

    /**
     * Home has just read the True Find wallet (the very answer this page asks for): it is kept, so the
     * first visit shows the balance and the coin packs at once instead of an empty page while it loads.
     */
    fun offer(d: JSONObject) {
        if (shown && app == "truefind") return   // on screen: its own answer is on its way
        val w = d.optJSONObject("wallet") ?: return
        cache["truefind"] = Cached(w, list(d.optJSONArray("transactions")), d.str("account_type").ifEmpty { "user" },
            d.str("my_profile_id").ifEmpty { null }, d.num("chat_rate_coins"), d.opt("first_offer") == true)
    }

    /**
     * Reconnect Home has just read the credits (the very answer this page asks for): kept, so the Reconnect
     * wallet opens with them at once instead of "–". The server is still asked; its answer replaces them.
     * Never older than 2 minutes, so a number on screen is never an old one.
     */
    fun offerCredits(d: JSONObject) {
        if (d.optJSONObject("credits") == null) return
        offeredCredits = d; offeredAt = System.currentTimeMillis()
    }
    private fun takeOfferedCredits(): Boolean {
        val d = offeredCredits ?: return false
        offeredCredits = null
        if (System.currentTimeMillis() - offeredAt > 120_000) return false
        applyCredits(d)
        return true
    }
    private fun applyCredits(d: JSONObject) {
        credits = d.optJSONObject("credits")
        creditsPurchases = list(d.optJSONArray("purchases"))
        sdPlan = d.optJSONObject("plan")
        sdIntro = d.optJSONObject("intro")
        sdMember = d.optJSONObject("member")
        creditsFailed = credits == null
    }

    /** getWallet for the wallet on screen. A slow answer for the other wallet is dropped. */
    private fun load(initial: Boolean = false, quiet: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val id = ++reqId
        val forApp = app
        // (21 Nov, owner: the Reconnect wallet kept loading forever. A first load that has no answer in 12 s
        // stops the spinner and says so; pull down tries again, and a late answer still fills the page.)
        if (loading) main.postDelayed({
            if (id == reqId && forApp == app && loading) {
                loading = false; loadSlow = true
                note = "Could not load your wallet. Pull down to try again."
                loadFailedNote = true
                renderAll()
            }
        }, 12_000)
        source.fn("getWallet", JSONObject().put("app", forApp)) { r ->
            if (id != reqId || forApp != app) { done?.invoke(false); return@fn }
            // an answer at last: the "could not load" note (12 s without an answer, or an error before) goes
            if (r.ok) { loadSlow = false; if (loadFailedNote) note = "" }
            if (!r.ok) {
                if (initial) { note = axiosMessage(r).ifEmpty { "Could not load wallet." }; loadFailedNote = true }
                if (loading) { loading = false }
                drawnFrom.remove(forApp)
                renderAll()
                done?.invoke(false)
                return@fn
            }
            val d = r.data
            val sig = try { d.toString() } catch (e: Exception) { "" }
            // (only the quiet look after coming back: every other load draws the page as it always did)
            val same = quiet && !loading && sig.isNotEmpty() && drawnFrom[forApp] == sig
            wallet = d.optJSONObject("wallet")
            transactions = list(d.optJSONArray("transactions"))
            accountType = d.str("account_type").ifEmpty { "user" }
            profileId = d.str("my_profile_id").ifEmpty { null }
            rate = d.num("chat_rate_coins")
            rateDraft = jsNum(rate)
            if (rateEditReady) rateEdit.setText(rateDraft)
            firstOffer = forApp == "truefind" && d.opt("first_offer") == true
            if (forApp == "truefind") offerUntilMs = wParseDate(d.str("offer_until")) ?: 0L
            cache[forApp] = Cached(wallet, transactions, accountType, profileId, rate, firstOffer)
            loading = false
            if (sig.isNotEmpty()) drawnFrom[forApp] = sig else drawnFrom.remove(forApp)
            if (!same) renderAll()
            done?.invoke(true)
        }
    }

    /**
     * The own profile as the Wallet reads it: without what changes by itself every few seconds
     * (online / last seen, written by the app's "I am online" call) and is shown nowhere on this page.
     */
    private fun meSig(p: JSONObject?): String = try {
        if (p == null) "" else JSONObject(p.toString()).apply { remove("last_active"); remove("online_status"); remove("updated_date") }.toString()
    } catch (e: Exception) { "" }

    /** Own profile: creator / host verification and the withdraw request fields. */
    private fun loadProfile(quiet: Boolean = false, done: (() -> Unit)? = null) {
        source.me { user, prof, ok ->
            val wasAdmin = isAdmin
            if (user != null) isAdmin = user.str("role") == "admin"
            if (ok) {
                val sig = meSig(prof)
                val same = quiet && sig.isNotEmpty() && sig == drawnMe && wasAdmin == isAdmin
                me = prof
                drawnMe = sig.ifEmpty { null }
                if (!same) { renderBody(); renderPayBar() }
            }
            done?.invoke()
        }
    }

    /** Reconnect: credits, the running plan, the first-time offer and the True Find coins. */
    private fun loadCredits(done: (() -> Unit)? = null) {
        val id = ++credReq
        // True Find coins (only for the "Unlock for 1 coin" offer): the True Find wallet already read by Home or
        // this page is used, so the server is not asked for it again. Asked only when not known yet, or 0 (then
        // a fresh number matters: the button is off at 0).
        val known = cache["truefind"]?.wallet?.num("coins")
        val askTf = known == null || known <= 0.0
        if (known != null) tfCoins = known
        var left = if (askTf) 2 else 1
        var res: Api.Result? = null
        var tf: Api.Result? = null
        var over = false
        // (21 Nov, owner: "Request credits –" stayed for ever. No answer in 12 s: the header says so, pull down tries
        // again, and a late answer still fills it.)
        main.postDelayed({
            if (id == credReq && !over && credits == null) {
                creditsFailed = true
                if (app == "secret") renderHeader()
            }
        }, 12_000)
        val finish = finish@{
            left -= 1
            if (left > 0) return@finish
            over = true
            // (an older ask that answers after a newer one changes nothing)
            if (id != credReq) { done?.invoke(); return@finish }
            val r = res
            if (r != null && r.ok) {
                applyCredits(r.data)
            } else {
                // the last numbers read stay on screen; with none, the header says they could not be read
                creditsFailed = credits == null
            }
            val w = tf?.takeIf { it.ok }?.data?.optJSONObject("wallet")
            if (w != null) tfCoins = w.num("coins")
            if (app == "secret") { renderHeader(); renderBody(); renderPayBar() }
            done?.invoke()
            Unit
        }
        source.fn("getRequestCredits", JSONObject()) { res = it; finish() }
        if (askTf) source.fn("getWallet", JSONObject().put("app", "truefind")) { r ->
            tf = r
            // (kept for the True Find tab too: it opens with this number at once)
            r.data.optJSONObject("wallet")?.takeIf { r.ok }?.let { w ->
                val c = cache["truefind"]
                cache["truefind"] = Cached(w, c?.transactions ?: list(r.data.optJSONArray("transactions")), c?.accountType ?: r.data.str("account_type").ifEmpty { "user" },
                    c?.profileId ?: r.data.str("my_profile_id").ifEmpty { null }, c?.rate ?: r.data.num("chat_rate_coins"), c?.firstOffer ?: (r.data.opt("first_offer") == true))
            }
            finish()
        }
    }

    private fun refreshQuietly() {
        load(quiet = true)
        loadProfile(quiet = true)
        if (app == "secret") loadCredits()
    }

    private fun list(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    /** What the web shows for a failed request (axios: "Request failed with status code 500"). */
    private fun axiosMessage(r: Api.Result): String = if (r.status == 0) "Network Error" else "Request failed with status code ${r.status}"

    /** A number the way JavaScript writes it: 5, 5.5 */
    private fun jsNum(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    // ═════════════════ switching wallets / tabs ═════════════════

    /** Shows that wallet's cached numbers straight away (never the other wallet's). */
    private fun switchApp(next: String) {
        if (next == app) return
        val c = cache[next]
        app = next
        wallet = c?.wallet
        transactions = c?.transactions ?: emptyList()
        firstOffer = c?.firstOffer == true
        loading = c?.wallet == null
        note = ""
        showAllTx = false
        sel = null
        planSel = null
        credits = null
        creditsFailed = false
        creditsPurchases = emptyList()
        if (next == "secret") takeOfferedCredits()
        setAddress(next)
        renderAll()
        load(initial = true)
        if (next == "secret") loadCredits()
    }

    private fun setTab(k: String) {
        if (k == tab) return
        if (tab == "earnings") { earnMsg = ""; earnSending = false; if (amountEditReady) amountEdit.setText("") }
        tab = k
        renderBody()
        renderPayBar()
    }

    // ═════════════════ derived values (as in the web page) ═════════════════

    private val secret get() = app == "secret"
    private val isCreator get() = accountType == "creator"
    private val coins get() = wallet.num("coins")
    private val earned get() = wallet.num("earned_coins")
    /** Earnings tab: True Find wallet only, and only for people who applied to be a creator / host. */
    // the Coins / Earnings switch is gone (owner, 27 Nov): the Wallet shows coins only
    private val showEarnings get() = false
    private val coinsView get() = !showEarnings || tab == "coins"
    private val sdGirl get() = sdMember.str("gender") == "female"
    private val sdMustVerify get() = sdGirl && sdMember?.opt("free") != true
    private val offerOn get() = !secret && firstOffer
    private val sdOffer get() = sdIntro != null && !truthy(sdIntro?.opt("used")) && !truthy(sdIntro?.opt("had_plan"))

    /** Which pack the Pay button is for. New users start on the ₹49 offer, others on ₹259. */
    private val selPack: WPack
        get() {
            val p = WData.findPack(sel)
            if (p != null && (!p.offer || offerOn)) return p
            return if (offerOn) WData.OFFERS[1] else WData.findPack(WData.DEFAULT_PACK)!!
        }
    /** The Pay bar appears only after a pack is tapped. */
    private val picked: WPack? get() { val s = sel ?: return null; return if (WData.findPack(s) != null && selPack.id == s) selPack else null }
    private val planPicked: WPlan? get() = WData.PLANS.find { it.key == planSel }

    private fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0 && !v.toDouble().isNaN()
        is String -> v.isNotEmpty()
        else -> true
    }

    // ═════════════════ drawing ═════════════════

    private fun renderAll() {
        renderHeader()
        renderBody()
        renderPayBar()
    }

    private fun renderHeader() {
        val t = T
        setBackgroundColor(Color.WHITE)
        scroll.setBackgroundColor(Color.WHITE)
        titleSub.text = if (secret) "Reconnect" else "Wallet"
        backBtn.background = WV.round(t.btn, 12f)
        helpBtn.background = WV.round(t.btn, 12f)
        titleSub.setTextColor(Color.WHITE)
        switchBox.background = WV.round(t.track, 12f)
        for (tv in switchTabs) {
            val on = tv.tag == app
            tv.setTextColor(if (on) t.hdr else t.off)
            tv.background = if (on) WV.round(Color.WHITE, 9f) else null
            tv.isSelected = on
        }
        balanceBox.removeAllViews()
        val col = WV.box(context, true)
        if (secret) {
            col.addView(WV.text(context, "Request credits", 14f, t.sub, false, 20f))
            val row = WV.box(context, false, Gravity.CENTER_VERTICAL)
            row.addView(heartToken(context, 34f, Color.WHITE, t.hdr))
            val c = credits
            row.addView(bigNumber(if (c != null) wRupees(c.num("available_request_credits")) else "–"), WV.lp(WV.WRAP, WV.WRAP, l = 10f))
            row.addView(WV.text(context, "credits", 16f, t.sub), WV.lp(WV.WRAP, WV.WRAP, l = 10f).apply { gravity = Gravity.BOTTOM; bottomMargin = Ui.dpi(8f) })
            col.addView(row, WV.lp(WV.WRAP, Ui.dpi(48f), t = 6f))
            val until = if (sdPlan?.opt("active") == true && sdPlan.str("until").isNotEmpty()) wDate(sdPlan.str("until")) else ""
            val status = when {
                c == null && creditsFailed -> "Couldn't load credits · pull down to try again"
                sdPlan?.opt("active") == true -> "${sdPlan.str("name").ifEmpty { "Premium" }} · ends $until"
                sdGirl && !sdMustVerify -> "Chat is free for you · a plan adds credits"
                else -> "No active plan"
            }
            col.addView(WV.text(context, status, 13f, t.off, false, 18f).apply {
                background = WV.round(t.track, 8f)
                setPadding(Ui.dpi(10f), Ui.dpi(3f), Ui.dpi(10f), Ui.dpi(3f))
                // (could not be read: a tap tries again too, not only pulling down)
                if (c == null && creditsFailed) { isClickable = true; setOnClickListener { if (!spinner.spinning) refreshNow() } }
            }, WV.lp(WV.WRAP, WV.WRAP, t = 8f))
        } else {
            col.addView(WV.text(context, "Your balance", 15f, Color.argb(235, 255, 255, 255), true, 20f))
            val row = WV.box(context, false, Gravity.BOTTOM)
            row.addView(WV.text(context, wRupees(Math.floor(coins)), 38f, Color.WHITE, true, 44f).apply { includeFontPadding = false })
            row.addView(WV.text(context, "coins", 15f, Color.argb(235, 255, 255, 255), true, 20f).apply { includeFontPadding = false },
                WV.lp(WV.WRAP, WV.WRAP, l = 8f, b = 5f))
            col.addView(row, WV.lp(WV.WRAP, WV.WRAP, t = 3f))
        }
        balanceBox.addView(col)
        // design 1: the coin picture and the minute chips — True Find wallet only
        headCoins.visibility = if (secret) View.GONE else View.VISIBLE
        minuteChips.removeAllViews()
        minuteChips.visibility = if (secret) View.GONE else View.VISIBLE
        if (!secret) {
            // minutes from the app's real call prices (the ones Home / Explore show), whole minutes only, never more than true
            val hp = context.getSharedPreferences("tfhome", 0)
            val voice = hp.getInt("rate_voice", 10).coerceAtLeast(1)
            val video = hp.getInt("rate_video", 40).coerceAtLeast(1)
            val have = Math.max(0.0, Math.floor(coins))
            minuteChips.addView(minuteChip(WFillIcon.PHONE, "≈ ${wRupees(Math.floor(have / voice))} min voice"))
            minuteChips.addView(minuteChip(WFillIcon.VIDEO, "≈ ${wRupees(Math.floor(have / video))} min video"), WV.lp(WV.WRAP, WV.WRAP, l = 14f))
        }
    }

    /** "📞 ≈ 315 min voice" — a see-through pill with a thin light border (design 1). */
    private fun minuteChip(icon: String, label: String): View {
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL).apply {
            background = WV.round(Color.argb(26, 255, 255, 255), 16f, Color.argb(120, 255, 255, 255), 1.2f)
            setPadding(Ui.dpi(12f), Ui.dpi(6f), Ui.dpi(14f), Ui.dpi(6f))
            contentDescription = label.replace("≈", "about")
        }
        r.addView(WFillIcon(context, icon, Color.WHITE), WV.lp(Ui.dpi(16f), Ui.dpi(16f)))
        r.addView(WV.text(context, label, 13.5f, Color.WHITE, true, 18f).apply { maxLines = 1 }, WV.lp(WV.WRAP, WV.WRAP, l = 7f))
        return r
    }

    private fun bigNumber(s: String): TextView = WV.text(context, s, 44f, Color.WHITE, true).apply {
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        minHeight = Ui.dpi(48f)
    }

    /** The white sheet's content: sections 28px apart. A redraw keeps the cursor in a text box. */
    private fun renderBody() {
        val typing: EditText? = when {
            amountEditReady && amountEdit.hasFocus() -> amountEdit
            rateEditReady && rateEdit.hasFocus() -> rateEdit
            else -> null
        }
        drawBody()
        if (typing != null && typing.parent != null) typing.requestFocus()
    }

    private fun drawBody() {
        val t = T
        val ctx = context
        body.removeAllViews()
        packTiles.clear(); planRows.clear(); if (!historyOpen) txSection = null; helpSection = null; draftBox = null
        if (loading && !secret) {
            val f = FrameLayout(ctx)
            f.addView(WSpinner(ctx, 30f, t.accent).apply { layoutParams = LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER) })
            body.addView(f, WV.lp(WV.MATCH, Ui.dpi(150f)))
            return
        }
        // Coins / Earnings (creators and applicants only)
        if (showEarnings) {
            val seg = WV.box(ctx, false).apply {
                background = WV.round(Color.WHITE, 12f, t.line)
                setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f))
            }
            for ((k, label) in listOf("coins" to "Coins", "earnings" to "Earnings")) {
                val on = tab == k
                seg.addView(WV.text(ctx, label, 15f, if (on) Color.WHITE else t.muted, true).apply {
                    gravity = Gravity.CENTER
                    background = if (on) WV.round(t.accent, 9f) else null
                    isClickable = true
                    setOnClickListener { setTab(k) }
                }, WV.lp(0, Ui.dpi(38f), l = if (seg.childCount > 0) 4f else 0f, weight = 1f))
            }
            section(seg)
        }
        if (showEarnings && tab == "earnings") section(earningsPanel())

        if (coinsView && !secret) {
            // design 1 (owner, 27 Nov): the New user offer box first, then "Coin packages ...... Transaction history ▤",
            // then the packs
            // 28 Nov (owner): "Transaction history" sits above the New user offer box; the whole content (the offer box
            // too) moves with the springy scroll
            if (offerOn) {
                body.addView(WV.box(ctx, false, Gravity.END).apply { addView(historyLink()) }, WV.lp(WV.MATCH, WV.WRAP, r = 4f))
                body.addView(offerCard(), WV.lp(WV.MATCH, WV.WRAP, l = 2f, r = 2f, t = 14f))
            }
            body.addView(packagesHead(!offerOn), WV.lp(WV.MATCH, WV.WRAP, l = 4f, t = if (offerOn) 30f else 0f))
            body.addView(packsSection(), WV.lp(WV.MATCH, WV.WRAP, t = 22f))
            body.addView(termsLine(), WV.lp(WV.MATCH, WV.WRAP, t = 22f))
            noteBox()?.let { section(it) }
        }
        if (coinsView && secret) secretSections()

        // Creator earnings (Reconnect wallet; True Find uses the Earnings tab)
        if (isCreator && secret) section(WV.box(context, true).apply { setPadding(0, 0, 0, Ui.dpi(14f)); addView(rateCard(earned), WV.lp(WV.MATCH, WV.WRAP)) })

        if (coinsView) {
            if (loading) {
                // (Reconnect: only the coin history still waits for its answer)
                val f = FrameLayout(ctx)
                f.addView(WSpinner(ctx, 30f, t.accent).apply { layoutParams = LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER) })
                section(f.also { it.minimumHeight = Ui.dpi(120f) })
            }
            section(helpBlock())
        }
    }

    private fun section(v: View) {
        body.addView(v, WV.lp(WV.MATCH, WV.WRAP, t = if (body.childCount > 0) 28f else 0f))
    }

    private fun noteBox(): View? {
        if (note.isEmpty()) return null
        val t = T
        val text = WV.text(context, note, 14f, t.ink).apply {
            gravity = Gravity.CENTER
            background = WV.round(t.tint, 14f, t.tintLine)
            setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // the wallet could not be read: a "Try again" button under the note (pulling down still works too)
        if (!loadFailedNote) return text
        val box = WV.box(context, true, Gravity.CENTER_HORIZONTAL)
        box.addView(text, WV.lp(WV.MATCH, WV.WRAP))
        box.addView(WV.button(context, "Try again", t.accent, Color.WHITE, 40f, 12f, 14f).apply {
            setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0)
            setOnClickListener { if (!spinner.spinning) refreshNow() }
        }, WV.lp(WV.WRAP, Ui.dpi(40f), t = 10f))
        return box
    }
    /** the note says the wallet could not be read (12 s without an answer, or a server error) */
    private var loadFailedNote = false

    /** "Title ........ small grey line" */
    private fun titleRow(title: String, right: View?): LinearLayout {
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL)
        r.addView(WV.h2(context, title, T.ink), WV.lp(0, WV.WRAP, weight = 1f))
        if (right != null) r.addView(right, WV.lp(WV.WRAP, WV.WRAP, l = 8f))
        return r
    }

    private fun muted(s: String, size: Float = 13f): TextView = WV.text(context, s, size, T.muted)

    /** NEW USER / FIRST TIME card: tinted box, badge, "One time only". */
    private fun offerBox(badge: String, title: String, line: String): LinearLayout {
        val t = T
        val box = WV.box(context, true).apply {
            background = WV.round(t.tint, 20f, t.tintLine)
            setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f))
        }
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL)
        r.addView(WV.text(context, badge, 11f, Color.WHITE, true, 14f).apply {
            letterSpacing = 0.6f / 11f
            background = WV.round(t.accent, 6f)
            setPadding(Ui.dpi(8f), Ui.dpi(4f), Ui.dpi(8f), Ui.dpi(4f))
        })
        r.addView(Space(context), WV.lp(0, 1, weight = 1f))
        r.addView(muted("One time only"))
        box.addView(r, WV.lp(WV.MATCH, WV.WRAP))
        box.addView(WV.text(context, title, 20f, t.ink, true, 26f), WV.lp(WV.MATCH, WV.WRAP, t = 10f))
        box.addView(WV.text(context, line, 14f, t.muted, false, 20f), WV.lp(WV.MATCH, WV.WRAP, t = 4f))
        return box
    }

    /** "Coin packages" (big, black) on the left, "Transaction history ▤" on the right (design 1). */
    private fun packagesHead(withHistory: Boolean = true): View {
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL)
        r.addView(WV.text(context, "Coin packages", 21f, Ui.c("#111111"), true, 26f).apply { maxLines = 1 }, WV.lp(0, WV.WRAP, weight = 1f))
        if (withHistory) r.addView(historyLink(), WV.lp(WV.WRAP, WV.WRAP, l = 8f))
        return r
    }

    /** "Transaction history ▤": opens the history page. */
    private fun historyLink(): View {
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            isClickable = true
            contentDescription = "Transaction history"
            setOnClickListener { openHistory() }
        }
        r.setPadding(0, Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(6f))
        r.addView(WV.text(context, "Transaction history", 14f, Ui.c("#111111"), true))
        r.addView(WIcons.view(context, WIcons.RECEIPT_LINES, Ui.c("#111111"), 21f), WV.lp(Ui.dpi(21f), Ui.dpi(21f), l = 8f))
        return r
    }

    /** "Transaction history" (owner, 27 Nov): the transactions open in a sheet of their own, never on the page. */
    private var historyOpen = false
    private var txPage: FrameLayout? = null
    private var txNewestFirst = true

    /** Transaction history (owner, 27 Nov): a whole page that pops in (not a sheet from the bottom), like his
     *  reference — back arrow, "Transactions", All / Credits / Debits, rows grouped by day. */
    private fun openHistory() {
        if (txPage != null) return
        historyOpen = true
        txFilter = "all"
        val ctx = context
        val pg = FrameLayout(ctx).apply {
            setBackgroundColor(Ui.c("#F7F7FB"))
            isClickable = true
            elevation = Ui.dp(30f)
        }
        val col = WV.box(ctx, true)
        // top: ← Transactions ......... filter
        val top = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply { setPadding(Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(6f)) }
        top.addView(FrameLayout(ctx).apply {
            isClickable = true; contentDescription = "Back"
            setOnClickListener { closeHistory() }
            addView(WIcons.view(ctx, "M19 12H5M12 19l-7-7 7-7", Ui.c("#111111"), 22f), LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
        }, WV.lp(Ui.dpi(44f), Ui.dpi(44f)))
        top.addView(Space(ctx), WV.lp(0, 1, weight = 1f))
        top.addView(FrameLayout(ctx).apply {
            background = WV.round(Ui.c("#6C2BD9"), 10f)
            isClickable = true; contentDescription = "Newest or oldest first"
            setOnClickListener { txNewestFirst = !txNewestFirst; fillTxPage() }
            addView(WIcons.view(ctx, "M4 6h16M7 12h10M10 18h4", Color.WHITE, 18f), LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        }, WV.lp(Ui.dpi(36f), Ui.dpi(36f)))
        col.addView(top, WV.lp(WV.MATCH, WV.WRAP))
        col.addView(WV.text(ctx, "Transactions", 24f, Ui.c("#111111"), true, 30f), WV.lp(WV.MATCH, WV.WRAP, l = 20f, r = 20f, t = 2f))
        // All | Credits | Debits — a grey track, the chosen one a white pill
        val seg = WV.box(ctx, false).apply {
            background = WV.round(Ui.c("#ECECF2"), 10f)
            setPadding(Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f))
        }
        col.addView(seg, WV.lp(WV.MATCH, Ui.dpi(40f), l = 20f, r = 20f, t = 14f))
        val list = WV.box(ctx, true).apply { setPadding(Ui.dpi(20f), Ui.dpi(6f), Ui.dpi(20f), Ui.dpi(30f)) }
        val sc = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(list) }
        col.addView(sc, WV.lp(WV.MATCH, 0, t = 8f, weight = 1f))
        pg.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        txSeg = seg; txList = list
        fillTxPage()
        addView(pg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        txPage = pg
        pg.alpha = 0f; pg.translationY = Ui.dp(28f); pg.scaleX = 0.985f; pg.scaleY = 0.985f
        pg.post {
            pg.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).withLayer().start()
        }
        renderPayBar()
    }
    private var txSeg: LinearLayout? = null
    private var txList: LinearLayout? = null

    private fun fillTxPage() {
        val ctx = context
        val seg = txSeg ?: return
        val list = txList ?: return
        seg.removeAllViews()
        for ((k, label) in listOf("all" to "All", "credit" to "Credits", "debit" to "Debits")) {
            val on = txFilter == k
            seg.addView(WV.text(ctx, label, 14f, if (on) Ui.c("#111111") else Ui.c("#6B6880"), on).apply {
                gravity = Gravity.CENTER
                background = if (on) WV.round(Color.WHITE, 8f) else null
                if (on) outlineSpotShadowColorCompat(Color.argb(70, 160, 140, 230))
                elevation = if (on) Ui.dp(2f) else 0f
                isClickable = true; isSelected = on
                setOnClickListener { txFilter = k; fillTxPage() }
            }, WV.lp(0, WV.MATCH, weight = 1f))
        }
        list.removeAllViews()
        val items = filteredTx.sortedBy { wParseDate(it.str("created_date")) ?: 0L }.let { if (txNewestFirst) it.reversed() else it }
        if (items.isEmpty()) {
            list.addView(WV.text(ctx, if (txFilter == "all") "No transactions yet" else "Nothing here yet", 15f, Ui.c("#6B6880")).apply { gravity = Gravity.CENTER },
                WV.lp(WV.MATCH, WV.WRAP, t = 60f))
            return
        }
        val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val today = dayKey.format(java.util.Date())
        val yest = dayKey.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
        val rowDate = java.text.SimpleDateFormat("EEE MMM d, yyyy · h:mma", java.util.Locale.US)
        var lastDay = ""
        for (tx in items) {
            val t = wParseDate(tx.str("created_date")) ?: 0L
            val k = if (t > 0) dayKey.format(java.util.Date(t)) else ""
            if (k != lastDay) {
                lastDay = k
                val label = when (k) { today -> "Today"; yest -> "Yesterday"; "" -> "Earlier"; else -> java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US).format(java.util.Date(t)) }
                list.addView(WV.text(ctx, label, 13f, Ui.c("#6B6880"), true, 18f), WV.lp(WV.MATCH, WV.WRAP, t = if (list.childCount > 0) 16f else 10f, b = 8f))
            }
            val plus = tx.num("amount") >= 0
            val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
                // a glowing white card on the off-white page (owner, 27 Nov): pure white, a very light edge and a
                // soft lavender-white glow around it; the text and the page colour stay as they were
                background = WV.round(Color.WHITE, 12f, Ui.c("#F3F1F8"))
                elevation = Ui.dp(3f)
                outlineSpotShadowColorCompat(Color.argb(70, 160, 140, 230))
                setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
                isClickable = true
                setOnClickListener { openTx(tx) }
                WV.press(this)
            }
            r.addView(txBadge(tx, plus), WV.lp(Ui.dpi(36f), Ui.dpi(36f)))
            val c = WV.box(ctx, true)
            c.addView(WV.text(ctx, tx.str("description").ifEmpty { tx.str("type") }.ifEmpty { if (plus) "Coins added" else "Coins spent" }, 14f, Ui.c("#111111"), true, 19f).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (t > 0) c.addView(WV.text(ctx, rowDate.format(java.util.Date(t)).replace("AM", "am").replace("PM", "pm"), 11.5f, Ui.c("#8E8BA0"), false, 16f), WV.lp(WV.WRAP, WV.WRAP, t = 2f))
            r.addView(c, WV.lp(0, WV.WRAP, l = 12f, weight = 1f))
            r.addView(WV.text(ctx, (if (plus) "+" else "−") + wRupees(Math.abs(tx.num("amount"))), 14.5f, if (plus) Ui.c("#16A34A") else Ui.c("#DC2626"), true),
                WV.lp(WV.WRAP, WV.WRAP, l = 10f))
            list.addView(r, WV.lp(WV.MATCH, WV.WRAP, b = 10f))
        }
    }

    /**
     * The round badge of a history row (owner, 27 Nov): a call made (coins paid) — green ↗; a call picked up (earned)
     * — black ↙; a missed call — red ↙; a gift — a gift box; daily / free coins — the TF coin; coins added — blue ↙;
     * other coins spent — orange ↗.
     */
    private fun txBadge(tx: JSONObject, plus: Boolean): View {
        val ctx = context
        val d = (tx.str("description") + " " + tx.str("type")).lowercase(java.util.Locale.ROOT)
        val f = FrameLayout(ctx)
        fun arrow(path: String, color: Int, bg: String) {
            f.background = WV.round(Ui.c(bg), 18f)
            f.addView(WIcons.view(ctx, path, color, 16f), LayoutParams(Ui.dpi(16f), Ui.dpi(16f), Gravity.CENTER))
        }
        when {
            d.contains("gift") -> {
                f.background = WV.round(Ui.c("#FCE7F3"), 10f, Ui.c("#F9A8D4"))
                f.addView(WIcons.view(ctx, WIcons.GIFT, Ui.c("#DB2777"), 18f), LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
            }
            d.contains("daily") || d.contains("free coin") || d.contains("streak") || d.contains("reward") || d.contains("welcome") || d.contains("bonus") -> {
                f.background = WV.round(Ui.c("#FEF3C7"), 18f)
                f.addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.wallet_coin); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.CENTER))
            }
            // calls: only the arrow's colour changes, the round background stays as it was (owner, 27 Nov)
            d.contains("missed") -> arrow(WIcons.ARROW_DOWN_LEFT, Ui.c("#DC2626"), if (plus) "#E3F0FD" else "#FDECE3")
            (d.contains("call") || d.contains("video") || d.contains("voice")) && plus -> arrow(WIcons.ARROW_DOWN_LEFT, Ui.c("#111111"), "#E3F0FD")
            d.contains("call") || d.contains("video") || d.contains("voice") -> arrow(WIcons.ARROW_UP_RIGHT, Ui.c("#16A34A"), "#FDECE3")
            plus -> arrow(WIcons.ARROW_DOWN_LEFT, Ui.c("#3B82F6"), "#E3F0FD")
            else -> arrow(WIcons.ARROW_UP_RIGHT, Ui.c("#F07A3A"), "#FDECE3")
        }
        return f
    }

    private fun closeHistory() {
        val pg = txPage ?: return
        historyOpen = false
        txPage = null; txSeg = null; txList = null
        pg.animate().alpha(0f).translationY(Ui.dp(20f)).setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withLayer().withEndAction { removeView(pg) }.start()
        renderPayBar()
    }

    /** New user offer (design 1, owner 27 Nov): a light lavender box with a soft glow, "New user offer" in deep
     *  purple, a solid purple countdown pill, the three offer packs in white cards. */
    private fun offerCard(): View {
        val box = WV.box(context, true).apply {
            background = WV.round(Ui.c("#EFE4FD"), 24f, Ui.c("#E6D8FB"))
            elevation = Ui.dp(5f)
            outlineSpotShadowColorCompat(Color.argb(90, 150, 110, 230))
            setPadding(Ui.dpi(12f), Ui.dpi(16f), Ui.dpi(12f), Ui.dpi(14f))
        }
        val head = WV.box(context, false, Gravity.CENTER_VERTICAL)
        head.addView(WV.text(context, "New user offer", 21f, Ui.c("#550AA9"), true, 27f).apply { maxLines = 1 },
            WV.lp(0, WV.WRAP, l = 6f, weight = 1f))
        main.removeCallbacks(offerTick)
        offerClock = null
        val left = offerUntilMs - System.currentTimeMillis()
        if (offerUntilMs > 0 && left > 0) {
            val clock = WV.text(context, offerLeft(left), 12f, Color.WHITE, true, 16f).apply {
                maxLines = 1
                background = WV.round(Ui.c("#9845EB"), 14f)
                setPadding(Ui.dpi(11f), Ui.dpi(5f), Ui.dpi(11f), Ui.dpi(5f))
                contentDescription = "Offer time left"
            }
            head.addView(clock, WV.lp(WV.WRAP, WV.WRAP, l = 8f, r = 2f))
            offerClock = clock
            main.post(offerTick)
        }
        box.addView(head, WV.lp(WV.MATCH, WV.WRAP))
        box.addView(packGrid(WData.OFFERS, true, 8f), WV.lp(WV.MATCH, WV.WRAP, t = 16f))
        return box
    }

    /** "⏱ 6d 23h 59m 59s left" */
    private fun offerLeft(ms: Long): String {
        val s = ms / 1000
        val d = s / 86400; val h = s % 86400 / 3600; val m = s % 3600 / 60; val sec = s % 60
        return "⏱ ${d}d ${h}h ${m}m ${sec}s left"
    }

    /** All packs, biggest on top (owner, 27 Nov), with a little more room under the offer box. */
    private fun packsSection(): View {
        val s = WV.box(context, true)
        s.addView(packGrid(WData.PACKS, false, 24f), WV.lp(WV.MATCH, WV.WRAP, t = 10f))
        return s
    }

    /** "By continuing you agree to the Terms of Service and Privacy Policy" — the two names in dark purple. */
    private fun termsLine(): View {
        val sb = android.text.SpannableStringBuilder("By continuing you agree to the ")
        fun bold(w: String) {
            val a = sb.length; sb.append(w)
            sb.setSpan(android.text.style.ForegroundColorSpan(Ui.c("#4C1D95")), a, sb.length, 0)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD_ITALIC), a, sb.length, 0)
        }
        bold("Terms of Service"); sb.append("\nand "); bold("Privacy Policy")
        return WV.text(context, sb, 12f, Ui.c("#111111"), false, 18f).apply { gravity = Gravity.CENTER }
    }

    private fun secureLine(): View {
        val r = WV.box(context, false, Gravity.CENTER)
        r.addView(WIcons.view(context, WIcons.LOCK, T.muted, 14f), WV.lp(Ui.dpi(14f), Ui.dpi(14f)))
        r.addView(muted("Secure UPI payment · GPay, PhonePe, Paytm"), WV.lp(WV.WRAP, WV.WRAP, l = 6f))
        return r
    }

    /** grid of 3 columns, 8px apart */
    private fun packGrid(packs: List<WPack>, offer: Boolean, rowGap: Float): View {
        val grid = WV.box(context, true)
        packs.chunked(3).forEachIndexed { i, chunk ->
            val r = WV.box(context, false)
            for (j in 0 until 3) {
                val p = chunk.getOrNull(j)
                val v: View = if (p != null) PackTile(p, offer).also { it.on = picked?.id == p.id; packTiles[p.id] = it } else Space(context)
                r.addView(v, WV.lp(0, Ui.dpi(if (offer) 176f else 189f), l = if (j > 0) if (offer) 8f else 10f else 0f, weight = 1f))
            }
            grid.addView(r, WV.lp(WV.MATCH, WV.WRAP, t = if (i > 0) rowGap else 0f))
        }
        return grid
    }

    private fun pick(id: String) {
        // 28 Nov (owner): tap a pack → it turns green and the Pay bar comes; tap the same pack again → both go away
        sel = if (picked?.id == id) null else id
        val on = picked?.id
        for ((k, tile) in packTiles) tile.on = k == on
        renderPayBar()
    }

    /**
     * One coin-pack tile (owner, 27 Nov): white card, coins in the middle with "coins" under them, the price in a
     * lavender strip at the bottom (the regular-rate price struck out under it for offers and premium packs).
     * Design 1 (owner, 27 Nov): offer cards wear a gold "% OFF" band; a chosen regular pack a purple one;
     * "Most popular" / "Best deal" sit on the top edge. Selected = green border, light green card, green strip, green tick.
     */
    private val PILES = intArrayOf(R.drawable.wallet_pile1, R.drawable.wallet_pile2, R.drawable.wallet_pile3, R.drawable.wallet_pile4,
        R.drawable.wallet_pile5, R.drawable.wallet_pile6, R.drawable.wallet_pile7, R.drawable.wallet_pile8)

    private inner class PackTile(val p: WPack, val offer: Boolean) : FrameLayout(context) {
        private val strip: LinearLayout
        private val tick: View
        private var priceTv: TextView? = null
        private var wasTv: TextView? = null
        private var offBand: View? = null
        var on = false
            set(v) { if (field != v) { field = v; paint() } }

        init {
            clipChildren = false; clipToPadding = false
            elevation = Ui.dp(3f)
            isClickable = true
            setOnClickListener { pick(p.id) }
            WV.press(this)
            val was = WData.wasPrice(p)
            contentDescription = "${wRupees(p.coins)} coins for ₹${wRupees(p.price)}" +
                (if (WData.offPct(p) > 0) ", ${WData.offPct(p)}% off" else "") + (p.tag?.let { ", $it" } ?: "")
            val col = WV.box(context, true, Gravity.CENTER_HORIZONTAL)
            // 28 Nov (owner): room at the top for the % OFF band (and the tag pill above it) so nothing covers the coins
            col.setPadding(0, Ui.dpi(if (offer) 38f else 47f), 0, 0)
            col.addView(android.widget.ImageView(context).apply {
                setImageResource(PILES[(p.art - 1).coerceIn(0, PILES.size - 1)])
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, WV.lp(Ui.dpi(82f), Ui.dpi(50f)))
            col.addView(WV.text(context, wRupees(p.coins), 18f, Ui.c("#4C1D95"), true, 22f).apply { gravity = Gravity.CENTER },
                WV.lp(WV.WRAP, WV.WRAP, t = 6f))
            col.addView(WV.text(context, "coins", 11f, Ui.c("#8E87A3"), true, 14f).apply { gravity = Gravity.CENTER })
            col.addView(Space(context), WV.lp(1, 0, weight = 1f))
            strip = WV.box(context, true, Gravity.CENTER).apply { setPadding(0, Ui.dpi(7f), 0, Ui.dpi(7f)) }
            strip.addView(WV.text(context, "₹${wRupees(p.price)}", 15.5f, Ui.c("#5F5673"), true, 19f).apply { gravity = Gravity.CENTER })
            priceTv = strip.getChildAt(0) as TextView
            if (was > 0) strip.addView(WV.text(context, "₹${wRupees(was)}", 10.5f, Ui.c("#9A93AE"), true, 13f).apply {
                gravity = Gravity.CENTER
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                wasTv = this
            })
            col.addView(strip, WV.lp(WV.MATCH, Ui.dpi(if (was > 0) 48f else 40f)))
            addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            // design 1: a gold "18% / OFF" band hangs from the top edge of each offer card (the true discount from
            // the struck-out price, never more)
            val off = WData.offPct(p)
            // 28 Nov (owner): smaller band, below the tag pill, never over the coins
            val bandTop = if (p.tag != null) 11f else -1f
            if (offer && off > 0) addView(MoreBandView(context, off, "OFF"), LayoutParams(Ui.dpi(30f), Ui.dpi(34f), Gravity.TOP or Gravity.START).apply {
                leftMargin = Ui.dpi(6f); topMargin = Ui.dpi(bandTop)
            })
            // a regular pack: a purple "20% / OFF" band in the same place — shown from the start, not only when tapped (owner, 28 Nov)
            if (!offer && off > 0) {
                offBand = MoreBandView(context, off, "OFF", Ui.c("#B65CF0"), Ui.c("#8B2FE0"), Ui.c("#5B1A9E"))
                addView(offBand, LayoutParams(Ui.dpi(30f), Ui.dpi(34f), Gravity.TOP or Gravity.START).apply {
                    leftMargin = Ui.dpi(6f); topMargin = Ui.dpi(bandTop)
                })
            }
            // the tag sits on the top edge, in the middle: a purple pill, white words, a gold star on "Best deal"
            p.tag?.let { tag ->
                val words: CharSequence = if (tag == "Best deal") android.text.SpannableStringBuilder("★ Best deal").apply {
                    setSpan(android.text.style.ForegroundColorSpan(Ui.c("#F9BF2E")), 0, 1, 0)
                } else tag
                addView(WV.text(context, words, 10.5f, Color.WHITE, true, 14f).apply {
                    maxLines = 1
                    background = WV.round(Ui.c("#6C2BD9"), 11f)
                    setPadding(Ui.dpi(10f), Ui.dpi(3f), Ui.dpi(10f), Ui.dpi(3f))
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(-10f) })
            }
            // chosen: a green round tick with a white ring at the top right (design 1)
            tick = FrameLayout(context).apply {
                background = WV.oval(Ui.c("#16A34A"), Color.WHITE, 2f)
                addView(WIcons.view(context, WIcons.CHECK, Color.WHITE, 12f, 3f), LayoutParams(Ui.dpi(12f), Ui.dpi(12f), Gravity.CENTER))
            }
            addView(tick, LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.TOP or Gravity.END).apply { topMargin = Ui.dpi(7f); rightMargin = Ui.dpi(7f) })
            paint()
        }

        /** Selected (owner, 27 Nov — like his reference): white card, a bold green border, the price strip filled green. */
        private fun paint() {
            val green = Ui.c("#16A34A")
            val r = Ui.dp(14f)
            background = if (on) WV.round(Ui.c("#EEF8F1"), 14f, green, 3f) else WV.round(Ui.c("#FCFAFF"), 14f)
            strip.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (on) green else Ui.c("#F1EAFF"))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
            priceTv?.setTextColor(if (on) Color.WHITE else Ui.c("#5F5673"))
            wasTv?.setTextColor(if (on) Color.argb(210, 255, 255, 255) else Ui.c("#9A93AE"))
            tick.visibility = if (on) View.VISIBLE else View.GONE
            isSelected = on
        }
    }

    // ───────── Reconnect wallet: request credits + plans ─────────

    private fun secretSections() {
        val t = T
        val ctx = context
        noteBox()?.let { section(it) }

        // girls: Creator verification first — then Reconnect is free
        if (sdMustVerify) {
            val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
                background = WV.round(t.tint, 16f, t.tintLine)
                setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
            }
            r.addView(WIcons.view(ctx, WIcons.SHIELD_CHECK, t.accent, 24f))
            r.addView(WV.text(ctx, "Verify your profile first (Creator verification). Then Reconnect is free for you, and a plan only adds request credits.", 14f, t.ink, false, 19f),
                WV.lp(0, WV.WRAP, l = 10f, weight = 1f))
            r.addView(WV.button(ctx, "Verify now", t.accent, Color.WHITE, 40f, 12f, 14f).apply {
                setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
                setOnClickListener { go("/profile?open=creator", null) }
            }, WV.lp(WV.WRAP, Ui.dpi(40f), l = 10f))
            section(r)
        }

        // first-time offer: 1 True Find coin → 1 day + 3 credits (once, after verification)
        if (sdOffer && !sdMustVerify) {
            val box = offerBox("FIRST TIME", "Try Reconnect for 1 coin", "1 day${if (sdGirl) "" else " of unlimited chat"} + 3 request credits.")
            if (sdIntro?.opt("available") == true) {
                val off = claimingIntro || tfCoins == 0.0
                val lead: View? = if (claimingIntro) WSpinner(ctx, 18f, Color.WHITE) else null
                val b = WV.button(ctx, "Unlock for 1", t.accent, Color.WHITE, 48f, 12f, 16f, lead)
                // "Unlock for 1 [coin] coin" (8px apart, like the web button's gap)
                b.addView(WCoins.flat(ctx, 22f), WV.lp(Ui.dpi(22f), Ui.dpi(22f), l = 8f))
                b.addView(WV.text(ctx, "coin", 16f, Color.WHITE, true), WV.lp(WV.WRAP, WV.WRAP, l = 8f))
                b.alpha = if (off) 0.6f else 1f
                b.isEnabled = !off
                b.setOnClickListener { if (!off) claimIntro() }
                box.addView(b, WV.lp(WV.MATCH, Ui.dpi(48f), t = 14f))
            } else {
                box.addView(WV.text(ctx, if (sdGirl) "Complete your Creator verification to unlock this offer." else "Add your photo, name, age, state and city in your profile to unlock this offer.",
                    14f, t.accent, true), WV.lp(WV.MATCH, WV.WRAP, t = 12f))
            }
            val tc = tfCoins
            box.addView(muted("Your True Find coins: ${if (tc == null) "–" else wRupees(tc)}${if (tc == 0.0) " · add coins in the True Find wallet first" else ""}"),
                WV.lp(WV.MATCH, WV.WRAP, t = 10f))
            section(box)
        }

        // plans
        val plans = WV.box(ctx, true)
        plans.addView(titleRow("Choose a plan", muted("Tap a plan · credits end with it")), WV.lp(WV.MATCH, WV.WRAP))
        val listBox = WV.box(ctx, true)
        WData.PLANS.forEachIndexed { i, p ->
            val row = PlanRow(p)
            planRows[p.key] = row
            listBox.addView(row, WV.lp(WV.MATCH, WV.WRAP, t = if (i > 0) 10f else 0f))
        }
        plans.addView(listBox, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        plans.addView(secureLine(), WV.lp(WV.MATCH, WV.WRAP, t = 10f))
        plans.addView(WV.text(ctx, "A plan bought while one is running adds its days and its credits.", 13f, t.muted, false, 19f).apply { gravity = Gravity.CENTER },
            WV.lp(WV.MATCH, WV.WRAP, t = 10f))
        section(plans)

        // every plan includes
        val inc = WV.box(ctx, true)
        inc.addView(WV.h2(ctx, "Every plan includes", t.ink))
        val incBox = WV.box(ctx, true).apply {
            background = WV.round(Color.WHITE, 16f, t.line)
            setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
        }
        val items = listOf(
            WIcons.MESSAGE to (if (sdGirl) "Chat is free for verified girls" else "Unlimited chat with your matches"),
            WIcons.SEND to "Send requests — 1 credit each",
            WIcons.CIRCLE_DOT to "Post and watch nearby Stories",
        )
        items.forEachIndexed { i, (icon, text) ->
            val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply { minimumHeight = Ui.dpi(44f) }
            r.addView(WIcons.view(ctx, icon, t.accent, 20f))
            r.addView(WV.text(ctx, text, 15f, t.ink), WV.lp(0, WV.WRAP, l = 12f, weight = 1f))
            incBox.addView(if (i < items.size - 1) WV.withBottomLine(ctx, r, t.line) else r, WV.lp(WV.MATCH, WV.WRAP))
        }
        inc.addView(incBox, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        inc.addView(muted("Requests sent so far: ${wRupees(credits.num("total_requests_sent"))}"), WV.lp(WV.MATCH, WV.WRAP, t = 10f))
        section(inc)

        // plan history
        val hist = WV.box(ctx, true)
        hist.addView(WV.h2(ctx, "Plan history", t.ink))
        if (creditsPurchases.isEmpty()) {
            hist.addView(emptyBox("No plans yet", "Your plan history will show here."), WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        } else {
            val lb = WV.box(ctx, true).apply { background = WV.round(Color.WHITE, 16f, t.line) }
            creditsPurchases.forEachIndexed { i, p ->
                val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
                    minimumHeight = Ui.dpi(60f)
                    setPadding(Ui.dpi(16f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(8f))
                }
                val c = WV.box(ctx, true)
                c.addView(WV.text(ctx, "${p.str("plan_name").ifEmpty { "Plan" }} · ${p.str("status")}", 15f, t.ink, true))
                c.addView(WV.text(ctx, wDate(p.str("created_date")), 13f, t.muted))
                r.addView(c, WV.lp(0, WV.WRAP, weight = 1f))
                val added = p.num("credits_added").takeIf { it != 0.0 } ?: p.num("credits_purchased")
                r.addView(WV.text(ctx, "+${jsNum(added)} credits", 15f, t.accent, true).apply { maxLines = 1 }, WV.lp(WV.WRAP, WV.WRAP, l = 10f))
                lb.addView(if (i < creditsPurchases.size - 1) WV.withBottomLine(ctx, r, t.line) else r, WV.lp(WV.MATCH, WV.WRAP))
            }
            hist.addView(lb, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        }
        section(hist)

        // Reconnect has its own coins too (gifts and paid chat there)
        val sc = WV.box(ctx, true)
        sc.addView(WV.h2(ctx, "Reconnect coins", t.ink))
        val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
            background = WV.round(Color.WHITE, 16f, t.line)
            setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
            minimumHeight = Ui.dpi(64f)
        }
        r.addView(WCoins.flat(ctx, 26f))
        val amt = SpannableStringBuilder(if (wallet == null) "–" else wRupees(coins)).append(" ")
        val s0 = amt.length
        amt.append("coins")
        amt.setSpan(AbsoluteSizeSpan(Ui.dpi(14f)), s0, amt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        amt.setSpan(ForegroundColorSpan(t.muted), s0, amt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        amt.setSpan(TypefaceSpanCompat(Ui.regular), s0, amt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        r.addView(WV.text(ctx, amt, 20f, t.ink, true), WV.lp(0, WV.WRAP, l = 10f, weight = 1f))
        r.addView(WV.button(ctx, "Add coins", Color.WHITE, t.accent, 40f, 12f, 14f).apply {
            background = WV.round(Color.WHITE, 12f, t.tintLine)
            setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
            setOnClickListener { openBuy(null) }
        }, WV.lp(WV.WRAP, Ui.dpi(40f), l = 10f))
        sc.addView(r, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        section(sc)
    }

    /** One Reconnect plan: radio, name + tag, days, credits, price. */
    private inner class PlanRow(val p: WPlan) : LinearLayout(context) {
        private val dot = View(context)
        private val ringV = FrameLayout(context)
        var on = false
            set(v) { field = v; paint() }

        init {
            val t = T
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dpi(80f)
            setPadding(Ui.dpi(14f), Ui.dpi(10f), Ui.dpi(14f), Ui.dpi(10f))
            isClickable = true
            setOnClickListener { pickPlan(p.key) }
            WV.press(this)
            contentDescription = "${p.name}, ${p.days}, ${p.credits} request credits, ₹${p.price}"
            ringV.addView(dot, FrameLayout.LayoutParams(Ui.dpi(10f), Ui.dpi(10f), Gravity.CENTER))
            addView(ringV, WV.lp(Ui.dpi(20f), Ui.dpi(20f)))
            val c = WV.box(context, true)
            val nameRow = WV.box(context, false, Gravity.CENTER_VERTICAL)
            nameRow.addView(WV.text(context, p.name, 16f, t.ink, true, 20f))
            p.tag?.let {
                nameRow.addView(WV.text(context, it, 11f, Color.WHITE, true, 16f).apply {
                    maxLines = 1
                    background = WV.round(t.accent, 8f)
                    setPadding(Ui.dpi(8f), Ui.dpi(1f), Ui.dpi(8f), Ui.dpi(1f))
                }, WV.lp(WV.WRAP, WV.WRAP, l = 8f))
            }
            c.addView(nameRow)
            c.addView(WV.text(context, "${p.days}${if (sdGirl) "" else " · Unlimited chat"}", 13f, t.muted, false, 18f), WV.lp(WV.WRAP, WV.WRAP, t = 2f))
            val cr = WV.box(context, false, Gravity.CENTER_VERTICAL)
            cr.addView(heartToken(context, 16f, t.accent))
            cr.addView(WV.text(context, "${p.credits} request credits", 13f, t.accent, true, 18f), WV.lp(WV.WRAP, WV.WRAP, l = 5f))
            c.addView(cr, WV.lp(WV.WRAP, WV.WRAP, t = 2f))
            addView(c, WV.lp(0, WV.WRAP, l = 12f, weight = 1f))
            addView(WV.text(context, "₹${p.price}", 20f, t.ink, true), WV.lp(WV.WRAP, WV.WRAP, l = 12f))
            on = planPicked?.key == p.key
        }

        private fun paint() {
            val t = T
            background = WV.round(if (on) t.tint else Color.WHITE, 16f, if (on) t.accent else t.line, 2f)
            ringV.background = WV.oval(Color.TRANSPARENT, if (on) t.accent else Ui.c("#8F7A85"), 2f)
            dot.background = WV.oval(if (on) t.accent else Color.TRANSPARENT)
            isSelected = on
        }
    }

    private fun pickPlan(key: String) {
        planSel = key
        for ((k, row) in planRows) row.on = k == key
        renderPayBar()
    }

    private fun emptyBox(title: String, line: String): View {
        val t = T
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL).apply {
            background = WV.round(Color.WHITE, 16f, t.line)
            setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
            minimumHeight = Ui.dpi(76f)
        }
        val ic = FrameLayout(context).apply { background = WV.round(t.tint, 12f) }
        ic.addView(WIcons.view(context, WIcons.RECEIPT, t.accent, 20f).apply { layoutParams = LayoutParams(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER) })
        r.addView(ic, WV.lp(Ui.dpi(40f), Ui.dpi(40f)))
        val c = WV.box(context, true)
        c.addView(WV.text(context, title, 15f, t.ink, true, 20f))
        c.addView(WV.text(context, line, 13f, t.muted, false, 18f))
        r.addView(c, WV.lp(0, WV.WRAP, l = 12f, weight = 1f))
        return r
    }

    // ───────── Transactions (coins of this wallet) ─────────

    private val filteredTx: List<JSONObject>
        get() = transactions.filter { tx ->
            when (txFilter) {
                "credit" -> tx.num("amount") >= 0
                "debit" -> tx.num("amount") < 0
                else -> true
            }
        }

    private fun transactionsSection(): View {
        val s = WV.box(context, true)
        txSection = s
        fillTx(s)
        return s
    }

    private fun fillTx(s: LinearLayout) {
        val t = T
        val ctx = context
        s.removeAllViews()
        val list = filteredTx
        val more: View? = if (list.size > 5) WV.text(ctx, if (showAllTx) "Show less" else "View all", 14f, t.accent, true).apply {
            gravity = Gravity.CENTER
            minHeight = Ui.dpi(44f)
            setPadding(Ui.dpi(4f), 0, Ui.dpi(4f), 0)
            isClickable = true
            setOnClickListener { showAllTx = !showAllTx; txSection?.let { fillTx(it) } }
        } else null
        s.addView(titleRow(if (secret) "Coin history" else "Transactions", more), WV.lp(WV.MATCH, WV.WRAP))
        val chips = WV.box(ctx, false)
        for ((k, label) in listOf("all" to "All", "credit" to "Added", "debit" to "Spent")) {
            val on = txFilter == k
            chips.addView(WV.text(ctx, label, 14f, if (on) Color.WHITE else t.ink, on).apply {
                gravity = Gravity.CENTER
                background = WV.round(if (on) t.accent else Color.WHITE, 18f, if (on) t.accent else t.tintLine)
                setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
                isClickable = true
                isSelected = on
                setOnClickListener { txFilter = k; showAllTx = false; txSection?.let { fillTx(it) } }
            }, WV.lp(WV.WRAP, Ui.dpi(36f), l = if (chips.childCount > 0) 8f else 0f))
        }
        s.addView(chips, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        if (list.isEmpty()) {
            s.addView(emptyBox("No transactions yet", "Your coin history will show here."), WV.lp(WV.MATCH, WV.WRAP, t = 12f))
            return
        }
        val shownTx = if (showAllTx) list else list.take(5)
        val box = WV.box(ctx, true).apply { background = WV.round(Color.WHITE, 16f, t.line); clipToOutline = true }
        shownTx.forEachIndexed { i, tx ->
            val plus = tx.num("amount") >= 0
            val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
                minimumHeight = Ui.dpi(60f)
                setPadding(Ui.dpi(16f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(8f))
                isClickable = true
                setOnClickListener { openTx(tx) }
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.ca("#4C1D95", 0.08f)), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            }
            val c = WV.box(ctx, true)
            c.addView(WV.text(ctx, tx.str("description").ifEmpty { tx.str("type") }.ifEmpty { if (plus) "Coins added" else "Spent" }, 15f, t.ink, true, 20f).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            c.addView(WV.text(ctx, wDate(tx.str("created_date")), 13f, t.muted, false, 18f))
            r.addView(c, WV.lp(0, WV.WRAP, weight = 1f))
            r.addView(WV.text(ctx, (if (plus) "+" else "−") + wRupees(Math.abs(tx.num("amount"))), 16f, if (plus) WC.green else t.ink, true),
                WV.lp(WV.WRAP, WV.WRAP, l = 12f))
            box.addView(if (i < shownTx.size - 1) WV.withBottomLine(ctx, r, t.line) else r, WV.lp(WV.MATCH, WV.WRAP))
        }
        s.addView(box, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
    }

    // ───────── Payment help ─────────

    private fun helpBlock(): View {
        val t = T
        val ctx = context
        val s = WV.box(ctx, true)
        helpSection = s
        s.addView(WV.h2(ctx, "Payment help", t.ink))
        val box = WV.box(ctx, true).apply { background = WV.round(Color.WHITE, 16f, t.line); clipToOutline = true }
        val parts = ArrayList<Pair<View, TextView>>() // chevron, answer
        fun paintFaq(animate: Boolean) {
            parts.forEachIndexed { k, (chev, ans) ->
                val o = faqOpen.contains(k)
                if (animate) chev.animate().rotation(if (o) 180f else 0f).setDuration(200).start() else chev.rotation = if (o) 180f else 0f
                if (o && ans.visibility != View.VISIBLE) {
                    ans.visibility = View.VISIBLE
                    if (animate) { ans.alpha = 0f; ans.animate().alpha(1f).setDuration(200).start() }
                }
                if (!o) ans.visibility = View.GONE
            }
        }
        WData.FAQ.forEachIndexed { i, (q, a) ->
            val item = WV.box(ctx, true)
            val head = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
                minimumHeight = Ui.dpi(52f)
                setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
                isClickable = true
                contentDescription = q
            }
            head.addView(WV.text(ctx, q, 15f, t.ink), WV.lp(0, WV.WRAP, weight = 1f))
            val chev = WIcons.view(ctx, WIcons.CHEVRON_DOWN, t.muted, 16f)
            head.addView(chev, WV.lp(Ui.dpi(16f), Ui.dpi(16f), l = 10f))
            val ans = WV.text(ctx, a, 14f, t.muted, false, 21f).apply { setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), Ui.dpi(14f)) }
            parts.add(chev to ans)
            head.setOnClickListener {
                // one answer open at a time
                val now = !faqOpen.contains(i)
                faqOpen.clear()
                if (now) faqOpen.add(i)
                paintFaq(true)
            }
            item.addView(head, WV.lp(WV.MATCH, WV.WRAP))
            item.addView(ans, WV.lp(WV.MATCH, WV.WRAP))
            box.addView(if (i < WData.FAQ.size - 1) WV.withBottomLine(ctx, item, t.line) else item, WV.lp(WV.MATCH, WV.WRAP))
        }
        paintFaq(false)
        s.addView(box, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        return s
    }

    /** The ? button: scroll smoothly to "Payment help" (16px above it). */
    private fun goToHelp() {
        main.postDelayed({
            val target = helpSection ?: return@postDelayed
            var y = 0
            var v: View? = target
            while (v != null && v !== page) { y += v.top; v = v.parent as? View }
            scroll.smoothScrollTo(0, (y - Ui.dpi(16f)).coerceAtLeast(0))
        }, 60)
    }

    // ───────── Earnings (creators and hosts) ─────────

    private var amountEditReady = false
    private var rateEditReady = false

    private fun input(hint: String): EditText = EditText(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f))
        typeface = Ui.regular
        setTextColor(WC.ink)
        setHintTextColor(Ui.c("#9A90AD"))
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = EditorInfo.IME_ACTION_DONE
        maxLines = 1
        isSingleLine = true
        background = WV.round(Ui.c("#FBF9FF"), 14f, Ui.c("#E6DCF7"), 1.5f)
        setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun detach(v: View) { (v.parent as? ViewGroup)?.removeView(v) }

    private val STATUS_UI = mapOf(
        "none" to arrayOf("Not verified", "#6B5A8A", "#F3EEFC", WIcons.SHIELD_CHECK),
        "pending" to arrayOf("Under review", "#B45309", "#FFF7E6", WIcons.CLOCK),
        "approved" to arrayOf("Verified", "#15803D", "#E9F9EF", WIcons.CIRCLE_CHECK),
        "rejected" to arrayOf("Rejected", "#B91C1C", "#FDECEC", WIcons.CIRCLE_X),
    )

    private fun earningsPanel(): View {
        val ctx = context
        val p = me
        val panel = WV.box(ctx, true)
        // Girls are verified as Creators; boys are verified as Hosts.
        val isMaleHost = p.str("gender") != "female"
        val status = if (isMaleHost) WData.reviewStatus(p.str("host_applied_at"), p.str("host_reviewed_for"), p.str("host_status"))
            else WData.reviewStatus(p.str("creator_submitted_at"), p.str("creator_reviewed_for"), p.str("creator_status"))
        val S = STATUS_UI.getValue(status)
        val payout = WData.payout(p.num("payout_paid_total"), p.num("payout_request_amount"), p.str("payout_requested_at"), p.str("payout_paid_for"), p.str("payout_rejected_for"))
        val pendingAmount = if (payout.state == "pending") payout.amount else 0.0
        val available = Math.max(0.0, earned - payout.paidTotal - pendingAmount)
        val payoutUpi = if (isMaleHost) p.str("host_approved_upi_id") else p.str("creator_approved_upi_id")
        val canWithdraw = status == "approved" && payoutUpi.isNotEmpty() && payout.state != "pending"

        // ── verification status ──
        val c1 = WV.card(ctx, 18f, 18f)
        val head = WV.box(ctx, false, Gravity.CENTER_VERTICAL)
        val ib = FrameLayout(ctx).apply { background = WV.round(Ui.c(S[2]), 14f) }
        ib.addView(WIcons.view(ctx, S[3], Ui.c(S[1]), 24f).apply { layoutParams = LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.CENTER) })
        head.addView(ib, WV.lp(Ui.dpi(44f), Ui.dpi(44f)))
        val hc = WV.box(ctx, true)
        hc.addView(WV.text(ctx, if (isMaleHost) "Host verification" else "Creator verification", 16f, WC.ink, true))
        hc.addView(WV.text(ctx, S[0], 13f, Ui.c(S[1]), true))
        head.addView(hc, WV.lp(0, WV.WRAP, l = 12f, weight = 1f))
        c1.addView(head)
        val line = when (status) {
            "approved" -> "You are verified. Earnings and withdrawals are unlocked."
            "pending" -> "Our team is checking your video. You can withdraw once you are verified."
            "rejected" -> if (isMaleHost) "Your host application was not approved. Please apply again." else "Your verification was not approved. Please submit a new live video."
            else -> if (isMaleHost) "Apply as a Host with a selfie video and your UPI to start earning." else "Verify with a live video and your UPI to start earning."
        }
        c1.addView(WV.text(ctx, line, 13.5f, WC.sub, false, 19f), WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        val reason = if (isMaleHost) p.str("host_reject_reason") else p.str("creator_reject_reason")
        if (status == "rejected" && reason.isNotEmpty()) c1.addView(alertBox("Reason: $reason", "#FDECEC", "#F6C3C3", "#991B1B"), WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        if (status == "rejected" || status == "none") {
            val label = if (isMaleHost) (if (status == "rejected") "Apply again" else "Become a Host") else (if (status == "rejected") "Verify again" else "Verify now")
            c1.addView(WV.button(ctx, label, WC.head, Color.WHITE, 46f, 16f, 15.5f, WIcons.view(ctx, WIcons.REFRESH, Color.WHITE, 16f)).apply {
                setOnClickListener { go(if (isMaleHost) "/profile?open=host" else "/register/verification", null) }
            }, WV.lp(WV.MATCH, Ui.dpi(46f), t = 14f))
        }
        panel.addView(c1, WV.lp(WV.MATCH, WV.WRAP, b = 14f))

        // ── earnings summary ──
        val c2 = WV.card(ctx, 18f, 18f)
        c2.addView(iconLine(WIcons.TRENDING_UP, "Available to withdraw"), WV.lp(WV.MATCH, WV.WRAP, b = 4f))
        c2.addView(WV.text(ctx, "₹${wRupees(available)}", 34f, WC.ink, true))
        val grid = WV.box(ctx, false)
        for ((label, value) in listOf("Total earned" to earned, "Withdrawn" to payout.paidTotal, "In process" to pendingAmount)) {
            val b = WV.box(ctx, true, Gravity.CENTER).apply {
                background = WV.round(Ui.c("#FAF7FF"), 14f, WC.line)
                setPadding(Ui.dpi(4f), Ui.dpi(10f), Ui.dpi(4f), Ui.dpi(10f))
            }
            b.addView(WV.text(ctx, "₹${wRupees(value)}", 16f, WC.ink, true).apply { gravity = Gravity.CENTER })
            b.addView(WV.text(ctx, label, 11f, WC.soft).apply { gravity = Gravity.CENTER }, WV.lp(WV.WRAP, WV.WRAP, t = 2f))
            grid.addView(b, WV.lp(0, WV.WRAP, l = if (grid.childCount > 0) 8f else 0f, weight = 1f))
        }
        c2.addView(grid, WV.lp(WV.MATCH, WV.WRAP, t = 14f))
        panel.addView(c2, WV.lp(WV.MATCH, WV.WRAP, b = 14f))

        // ── withdraw ──
        val c3 = WV.card(ctx, 18f, 18f)
        c3.addView(WV.text(ctx, "Withdraw", 16f, WC.ink, true), WV.lp(WV.MATCH, WV.WRAP, b = 4f))
        when {
            status != "approved" -> c3.addView(WV.text(ctx, "Withdrawals unlock after your verification is approved.", 13.5f, WC.sub, false, 19f))
            payoutUpi.isEmpty() -> c3.addView(WV.text(ctx, "Your payout UPI is being set up by our team. Please check back soon.", 13.5f, WC.sub, false, 19f))
            else -> {
                val upiLine = SpannableStringBuilder("Money is sent only to your verified UPI: ")
                val s0 = upiLine.length
                upiLine.append(payoutUpi)
                upiLine.setSpan(StyleSpan(android.graphics.Typeface.BOLD), s0, upiLine.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                upiLine.setSpan(ForegroundColorSpan(WC.ink), s0, upiLine.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                c3.addView(WV.text(ctx, upiLine, 13.5f, WC.sub, false, 19f), WV.lp(WV.MATCH, WV.WRAP, b = 12f))
                if (payout.state == "pending") {
                    c3.addView(alertBox("Requested on ${wDate(payout.at)}. Usually paid within 2 working days.", "#FFF7E6", "#FDE3A7", "#92400E", WIcons.CLOCK), WV.lp(WV.MATCH, WV.WRAP, b = 12f))
                    c3.addView(breakdownBox(WData.breakdown(payout.amount, p.str("payout_fy"), p.num("payout_fy_share_paid"))))
                } else {
                    if (payout.state == "paid") {
                        val amt = p.num("payout_last_paid_amount").takeIf { truthy(p?.opt("payout_last_paid_amount")) } ?: payout.amount
                        val on = if (p.str("payout_last_paid_at").isNotEmpty()) " on ${wDate(p.str("payout_last_paid_at"))}" else ""
                        c3.addView(alertBox("₹${wRupees(amt)} was sent to your UPI$on.", "#E9F9EF", "#BFE9CF", "#166534", WIcons.CIRCLE_CHECK), WV.lp(WV.MATCH, WV.WRAP, b = 12f))
                    }
                    if (payout.state == "rejected") {
                        val rr = p.str("payout_reject_reason")
                        c3.addView(alertBox("Last request was not paid${if (rr.isNotEmpty()) ": $rr" else "."}", "#FDECEC", "#F6C3C3", "#991B1B"), WV.lp(WV.MATCH, WV.WRAP, b = 12f))
                    }
                    val inRow = WV.box(ctx, false, Gravity.CENTER_VERTICAL)
                    detach(amountEdit)
                    if (!amountEditReady) {
                        amountEditReady = true
                        amountEdit.addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) { fillDraft() }
                        })
                    }
                    inRow.addView(amountEdit, WV.lp(0, Ui.dpi(46f), weight = 1f))
                    inRow.addView(WV.text(ctx, "Max", 13.5f, WC.vivid, true).apply {
                        gravity = Gravity.CENTER
                        background = WV.round(Color.WHITE, 14f, Ui.c("#E6DCF7"), 1.5f)
                        setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
                        isClickable = true
                        setOnClickListener { amountEdit.setText(Math.floor(available).toLong().toString()); amountEdit.setSelection(amountEdit.text.length) }
                    }, WV.lp(WV.WRAP, Ui.dpi(46f), l = 8f))
                    c3.addView(inRow, WV.lp(WV.MATCH, WV.WRAP))
                    val db = WV.box(ctx, true)
                    draftBox = db
                    c3.addView(db, WV.lp(WV.MATCH, WV.WRAP))
                    fillDraft()
                    val off = !canWithdraw || earnSending || available < WData.MIN_WITHDRAW
                    val lead: View = if (earnSending) WSpinner(ctx, 16f, Color.WHITE) else WIcons.view(ctx, WIcons.SEND, Color.WHITE, 16f)
                    c3.addView(WV.primary(ctx, "Request withdrawal", lead).apply {
                        alpha = if (off) 0.5f else 1f
                        isEnabled = !off
                        setOnClickListener { if (!off) requestWithdraw(available, payoutUpi) }
                    }, WV.lp(WV.MATCH, Ui.dpi(50f), t = 12f))
                    if (available < WData.MIN_WITHDRAW) c3.addView(WV.text(ctx, "You need at least ₹${WData.MIN_WITHDRAW} available to withdraw.", 12f, WC.soft), WV.lp(WV.MATCH, WV.WRAP, t = 8f))
                }
            }
        }
        if (earnMsg.isNotEmpty()) c3.addView(WV.text(ctx, earnMsg, 13.5f, WC.vivid, false, 19f), WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        // how charges work
        val how = WV.box(ctx, true)
        how.setPadding(0, Ui.dpi(12f), 0, 0)
        how.addView(WV.text(ctx, "How charges work", 13f, WC.sub, true))
        val fy = WData.currentFY()
        val lines = arrayListOf(
            "• True Find keeps ${WData.APP_FEE_PCT}% of every withdrawal. GST is already included in this.",
            "• TDS is deducted only as per government rules: when your total payouts in a financial year (April–March) go above ₹${wRupees(WData.TDS_YEAR_LIMIT.toDouble())}, ${WData.TDS_PCT}% TDS is cut from your share.",
            "• TDS is deposited with the government against your PAN, and you can claim it in your income tax return.",
        )
        if (status == "approved") lines.add("• Paid so far this year ($fy): ₹${wRupees(if (p.str("payout_fy") == fy) p.num("payout_fy_share_paid") else 0.0)}")
        for (l in lines) how.addView(WV.text(ctx, l, 12f, WC.soft, false, 17f), WV.lp(WV.MATCH, WV.WRAP, t = 4f))
        c3.addView(View(ctx).apply { setBackgroundColor(WC.line) }, WV.lp(WV.MATCH, Math.max(1, Ui.dpi(1f)), t = 14f))
        c3.addView(how, WV.lp(WV.MATCH, WV.WRAP))
        panel.addView(c3, WV.lp(WV.MATCH, WV.WRAP, b = 14f))

        if (isCreator) panel.addView(rateCard(null), WV.lp(WV.MATCH, WV.WRAP, b = 14f))
        return panel
    }

    /** The draft amount's breakdown, under the amount box (only when an amount is typed). */
    private fun fillDraft() {
        val db = draftBox ?: return
        db.removeAllViews()
        val n = amountEdit.text.toString().trim().toDoubleOrNull() ?: 0.0
        val draft = Math.floor(n)
        if (draft > 0) db.addView(breakdownBox(WData.breakdown(draft, me.str("payout_fy"), me.num("payout_fy_share_paid"))), WV.lp(WV.MATCH, WV.WRAP, t = 12f))
    }

    private fun iconLine(icon: String, text: String): View {
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL)
        r.addView(WIcons.view(context, icon, WC.vivid, 16f), WV.lp(Ui.dpi(16f), Ui.dpi(16f)))
        r.addView(WV.text(context, text, 13.5f, WC.sub), WV.lp(WV.WRAP, WV.WRAP, l = 8f))
        return r
    }

    private fun alertBox(text: String, bg: String, border: String, color: String, icon: String? = null): View {
        val r = WV.box(context, false, Gravity.CENTER_VERTICAL).apply {
            background = WV.round(Ui.c(bg), 14f, Ui.c(border))
            setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
        }
        if (icon != null) r.addView(WIcons.view(context, icon, Ui.c(color), 16f), WV.lp(Ui.dpi(16f), Ui.dpi(16f), r = 8f))
        r.addView(WV.text(context, text, 13.5f, Ui.c(color), false, 19f), WV.lp(0, WV.WRAP, weight = 1f))
        return r
    }

    private fun breakdownBox(b: WBreakdown): View {
        val box = WV.box(context, true).apply {
            background = WV.round(Ui.c("#FAF7FF"), 16f, WC.line)
            setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
        }
        val red = Ui.c("#B91C1C")
        fun row(label: String, value: String, strong: Boolean = false, color: Int? = null, first: Boolean = false) {
            val r = WV.box(context, false, Gravity.CENTER_VERTICAL)
            r.addView(WV.text(context, label, 13.5f, color ?: WC.sub), WV.lp(0, WV.WRAP, weight = 1f))
            r.addView(WV.text(context, value, 13.5f, color ?: WC.ink, true), WV.lp(WV.WRAP, WV.WRAP, l = 12f))
            box.addView(r, WV.lp(WV.MATCH, WV.WRAP, t = if (first) 0f else 6f))
        }
        row("Withdraw amount", "₹${wRupees(b.gross)}", first = true)
        row("True Find charge (${WData.APP_FEE_PCT}%, GST included)", "− ₹${wRupees(b.fee)}", color = red)
        row("Govt. TDS (${if (b.tdsApplies) WData.TDS_PCT else 0}%)", "− ₹${wRupees(b.tds)}", color = red)
        box.addView(View(context).apply { setBackgroundColor(WC.line) }, WV.lp(WV.MATCH, Math.max(1, Ui.dpi(1f)), t = 8f, b = 2f))
        row("You receive", "₹${wRupees(b.net)}", strong = true)
        return box
    }

    private fun requestWithdraw(available: Double, upi: String) {
        val raw = amountEdit.text.toString().trim()
        val amt = Math.floor(if (raw.isEmpty()) 0.0 else raw.toDoubleOrNull() ?: Double.NaN)
        earnMsg = ""
        when {
            amt.isNaN() || amt < WData.MIN_WITHDRAW -> { earnMsg = "Minimum withdrawal is ₹${WData.MIN_WITHDRAW}."; renderBody(); return }
            amt > available -> { earnMsg = "You can withdraw up to ₹${wRupees(available)}."; renderBody(); return }
        }
        if (me.str("id").isEmpty() || earnSending) return
        earnSending = true
        hideKeyboard()
        renderBody()
        source.fn("updateMyProfile", JSONObject().put("withdraw", JSONObject().put("amount", amt.toLong()))) { r ->
            earnSending = false
            val d = r.data
            if (r.ok && d.opt("ok") == true) {
                val m = JSONObject(me.toString())
                m.put("payout_request_amount", d.opt("payout_request_amount"))
                m.put("payout_requested_at", d.opt("payout_requested_at"))
                me = m
                drawnMe = null
                amountEdit.setText("")
                earnMsg = "Withdrawal requested. ₹${wRupees(WData.breakdown(amt, m.str("payout_fy"), m.num("payout_fy_share_paid")).net)} will be sent to $upi."
            } else {
                earnMsg = d.str("error").ifEmpty { if (r.ok) "Could not send the request. Please try again." else axiosMessage(r) }
            }
            renderBody()
        }
    }

    /** RateCard: the creator's premium chat rate (₹ per message), with earnings on the Reconnect wallet. */
    private fun rateCard(earnedShown: Double?): View {
        val ctx = context
        val c = WV.card(ctx, 18f, 18f)
        if (earnedShown != null) {
            c.addView(iconLine(WIcons.TRENDING_UP, "Creator earnings"), WV.lp(WV.MATCH, WV.WRAP, b = 2f))
            val sb = SpannableStringBuilder("₹${wRupees(earnedShown)} ")
            val s0 = sb.length
            sb.append("earned")
            sb.setSpan(AbsoluteSizeSpan(Ui.dpi(13f)), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(WC.soft), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(TypefaceSpanCompat(Ui.regular), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            c.addView(WV.text(ctx, sb, 24f, WC.ink, true), WV.lp(WV.MATCH, WV.WRAP, b = 12f))
        }
        c.addView(WV.text(ctx, "Your premium chat rate (₹ per message)", 13.5f, WC.sub), WV.lp(WV.MATCH, WV.WRAP, b = 8f))
        val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL)
        detach(rateEdit)
        if (!rateEditReady) {
            rateEditReady = true
            rateEdit.setText(rateDraft)
            rateEdit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { rateDraft = s?.toString().orEmpty() }
            })
        }
        r.addView(rateEdit, WV.lp(0, Ui.dpi(46f), weight = 1f))
        val lead: View? = if (savingRate) WSpinner(ctx, 16f, Color.WHITE) else null
        r.addView(WV.button(ctx, if (savingRate) "" else "Save", WC.head, Color.WHITE, 46f, 14f, 15f, lead, 0f).apply {
            setPadding(Ui.dpi(20f), 0, Ui.dpi(20f), 0)
            alpha = if (savingRate) 0.6f else 1f
            setOnClickListener { saveRate() }
        }, WV.lp(WV.WRAP, Ui.dpi(46f), l = 8f))
        c.addView(r, WV.lp(WV.MATCH, WV.WRAP))
        c.addView(WV.text(ctx, if (rate > 0) "Fans pay ₹${jsNum(rate)} per message to chat with you." else "Set a rate above 0 to charge for chat.", 13f, WC.soft),
            WV.lp(WV.MATCH, WV.WRAP, t = 8f))
        return c
    }

    private fun saveRate() {
        val value = Math.max(0, Math.min(100, rateDraft.trim().let { s -> Regex("^\\s*[+-]?\\d+").find(s)?.value?.trim()?.toIntOrNull() } ?: 0))
        if (profileId == null || savingRate) return
        savingRate = true
        note = ""
        hideKeyboard()
        renderBody()
        source.fn("updateMyProfile", JSONObject().put("updates", JSONObject().put("chat_rate_coins", value))) { r ->
            savingRate = false
            if (r.ok) {
                rate = value.toDouble()
                rateDraft = value.toString()
                rateEdit.setText(rateDraft)
                note = if (value > 0) "Premium chat set to $value coins per message." else "Chat is now free."
            } else {
                note = axiosMessage(r)
            }
            renderBody()
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
        if (amountEditReady) amountEdit.clearFocus()
        if (rateEditReady) rateEdit.clearFocus()
    }

    // ═════════════════ the Pay bar ═════════════════

    private fun renderPayBar() {
        val t = T
        val opening = flow?.step == "opening"
        val show = (!loading || secret) && coinsView && (flow == null || opening) && txOpen == null && !historyOpen && (if (secret) planPicked != null else picked != null)
        if (!show) {
            payBar?.let { payHost.removeView(it) }
            payBar = null; payKey = ""
            return
        }
        // like the web's key={…}: a new pack / plan slides in again; other changes just redraw it
        val identity = if (secret) "plan:${planPicked!!.key}" else "pack:${picked!!.id}"
        val key = "$identity:${buyingCredits || opening}:$sdMustVerify:${sdPlan?.opt("active") == true}"
        if (key == payKey && payBar != null) return
        val animate = payBar == null || payKey.substringBeforeLast(':').substringBeforeLast(':').substringBeforeLast(':') != identity
        payBar?.let { payHost.removeView(it) }
        payKey = key
        val ctx = context
        val bar: LinearLayout
        if (secret) {
            val p = planPicked!!
            val off = buyingCredits || sdMustVerify
            val lead: View? = if (buyingCredits) WSpinner(ctx, 18f, Color.WHITE) else null
            // never see-through (owner, 19 Nov: the plans showed through it): a bar that can't be tapped yet is a
            // solid lighter shade of the same colour instead of a faded one
            val fill = if (off) androidx.core.graphics.ColorUtils.blendARGB(t.accent, Color.WHITE, 0.3f) else t.accent
            bar = WV.button(ctx, "${if (sdPlan?.opt("active") == true) "Add" else "Get"} ${p.name} · ₹${p.price} · ${p.credits} credits", fill, Color.WHITE, 56f, 16f, 17f, lead)
            bar.alpha = 1f
            bar.setOnClickListener { if (!off) buyPlan(p.key) }
        } else {
            val p = picked!!
            bar = coinPayBar(p, opening)
        }
        val w = Math.min(resources.displayMetrics.widthPixels - Ui.dpi(32f), Ui.dpi(448f))
        // (room under the bar: its shadow fades out there instead of being cut off — owner, 27 Nov: a grey box showed
        // at the bar's lower corners)
        payHost.addView(bar, LayoutParams(w, Ui.dpi(if (secret) 56f else 72f), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = Ui.dpi(WData.PAY_SHADOW_ROOM)
        })
        val wasShown = payBar != null
        payBar = bar
        if (animate) payMotion(bar, firstShow = !wasShown)
    }

    /**
     * How the Pay bar arrives (owner, 27 Nov: the old slide up from the bottom looked like a sheet of paper, not a
     * payment button). First time: it pops up from a little lower and smaller with a small springy overshoot, a band
     * of light runs across it once, and the Pay button gives one short pulse. Another pack tapped while it shows:
     * a quick springy bump and the light again, no slide.
     */
    private fun payMotion(bar: View, firstShow: Boolean) {
        val shine = WShine(Ui.dp(24f))
        bar.foreground = shine
        val spring = android.view.animation.OvershootInterpolator(if (firstShow) 1.7f else 2.4f)
        if (firstShow) {
            bar.alpha = 0f; bar.scaleX = 0.86f; bar.scaleY = 0.86f; bar.translationY = Ui.dp(28f)
            bar.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(40).setDuration(430).setInterpolator(spring).start()
        } else {
            bar.scaleX = 0.95f; bar.scaleY = 0.95f
            bar.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(spring).start()
        }
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = if (firstShow) 300L else 120L
            duration = 620L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { shine.progress = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { shine.progress = -1f }
            })
            start()
        }
        val btn = payBtn ?: return
        if (btn.parent !== bar) return
        btn.animate().scaleX(1.07f).scaleY(1.07f).setStartDelay(if (firstShow) 420L else 200L).setDuration(140)
            .setInterpolator(WV.EASE_OUT).withEndAction {
                btn.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }.start()
    }

    /** The coin pack's Pay bar (design 1, owner 27 Nov): green, the TF coin, "24,375 coins · 20% OFF" and the
     *  new balance under it, a white Pay button. The % is the true discount; packs without one show just the coins. */
    private fun coinPayBar(p: WPack, opening: Boolean): LinearLayout {
        val ctx = context
        val bar = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#15803D"), Ui.c("#166534")),
            ).apply { cornerRadius = Ui.dp(24f) }
            elevation = Ui.dp(10f)
            setPadding(Ui.dpi(14f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
            isClickable = true
        }
        bar.addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.wallet_coin); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            WV.lp(Ui.dpi(38f), Ui.dpi(38f)))
        val col = WV.box(ctx, true)
        val off = WData.offPct(p)
        col.addView(WV.text(ctx, "${wRupees(p.coins)} coins" + (if (off > 0) " · $off% OFF" else ""), 15f, Color.WHITE, true, 20f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(WV.text(ctx, "New balance: ${wRupees(Math.max(0.0, Math.floor(coins)) + p.coins)}", 12.5f, Color.argb(225, 255, 255, 255), true, 17f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, WV.lp(WV.WRAP, WV.WRAP, t = 3f))
        bar.addView(col, WV.lp(0, WV.WRAP, l = 12f, weight = 1f))
        val btn = WV.box(ctx, false, Gravity.CENTER).apply {
            background = WV.round(Color.WHITE, 16f)
            elevation = Ui.dp(2f)
            setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
            isClickable = true
            setOnClickListener { if (!opening) openBuy(p) }
            WV.press(this)
        }
        payBtn = btn
        if (opening) btn.addView(WSpinner(ctx, 16f, Ui.c("#111111")), WV.lp(Ui.dpi(16f), Ui.dpi(16f), r = 8f))
        btn.addView(WV.text(ctx, "Pay ₹${wRupees(p.price)}", 16f, Ui.c("#111111"), true).apply { maxLines = 1 })
        bar.addView(btn, WV.lp(WV.WRAP, Ui.dpi(48f), l = 8f))
        bar.setOnClickListener { if (!opening) openBuy(p) }
        return bar
    }

    // ═════════════════ buying ═════════════════

    /** Buying coins (25 Nov): the Pay button opens Google Play's own payment screen straight away (owner: no list of
     *  payment apps of ours — Google's screen has UPI with Google Pay, PhonePe, Paytm, and cards). "Add coins" first
     *  lets the member choose a pack. While Google Play payments are not set up yet: "Coming soon", as before (the
     *  admin can still add demo coins from there). The emulator pictures (made-up data) keep the old steps. */
    private fun openBuy(pack: WPack?) {
        val live = source === WalletNet
        if (pack == null) { flow = if (live || isAdmin) Flow("pack", WData.findPack(WData.DEFAULT_PACK)!!) else Flow("soon", WData.findPack(WData.DEFAULT_PACK)!!); showFlow(); return }
        if (!live) { flow = if (isAdmin) Flow("method", pack, "upi") else Flow("soon", pack); showFlow(); return }
        buyWithPlay(pack)
    }

    /** Google Play: check with the server → Google's payment screen → the server gives the coins → Payment successful. */
    private fun buyWithPlay(pack: WPack) {
        if (topUpBusy) return
        topUpBusy = true
        val forApp = app
        val startedAt = System.currentTimeMillis()
        flow = Flow("opening", pack, "gplay")
        note = ""
        renderPayBar()
        PlayBilling.buy(context, PlayBilling.productFor(pack.id), forApp, paid = {
            // Google's screen closed, paid: the server is adding the coins
            if (app == forApp) flow?.let { it.step = "processing"; showFlow() }
        }) { o ->
            topUpBusy = false
            if (app != forApp) return@buy
            val f = flow ?: Flow("opening", pack, "gplay").also { flow = it }
            when (o) {
                is PlayBilling.Outcome.Done -> {
                    val d = o.data
                    d.optJSONObject("wallet")?.let { wallet = it; drawnFrom.remove(forApp) }
                    sel = null // after a first purchase the offer card goes away (load() checks with the server)
                    load { _ ->
                        val wait = Math.max(0L, 900L - (System.currentTimeMillis() - startedAt))
                        main.postDelayed({
                            val cur = flow ?: return@postDelayed
                            cur.step = "done"; cur.at = System.currentTimeMillis(); cur.ref = d.str("order"); cur.method = "gplay"
                            cur.coins = if (d.has("added") && d.optLong("added") > 0) d.optLong("added") else -1L
                            showFlow()
                        }, wait)
                    }
                }
                PlayBilling.Outcome.Pending -> { f.step = "pending"; showFlow() }
                PlayBilling.Outcome.Cancelled -> closeFlow()
                is PlayBilling.Outcome.NotReady -> { f.step = "soon"; f.error = o.message; showFlow() }
                is PlayBilling.Outcome.Failed -> {
                    if (o.code == "offer_used") { sel = null; load() }
                    f.step = "failed"; f.error = o.message; showFlow()
                }
            }
        }
    }

    /** A payment that finished while the Wallet was away (UPI confirmed later, a lost connection): it is in now. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (source !== WalletNet) return
        PlayBilling.onRecovered = { d ->
            note = if (d.str("kind") == "plan") "Your earlier payment went through — your Reconnect plan is active!" else "Your earlier payment went through — the coins are in your wallet."
            load()
            if (app == "secret") loadCredits { }
        }
        PlayBilling.recover(context)
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacks(offerTick)
        PlayBilling.onRecovered = null
    }

    private fun closeFlow() {
        flow = null
        sheetUi?.dismiss(); sheetUi = null
        resultLayer?.let { removeView(it) }; resultLayer = null
        renderPayBar()
    }

    private fun showFlow() {
        val f = flow ?: return closeFlow()
        sheetUi?.dismiss(); sheetUi = null
        resultLayer?.let { removeView(it) }; resultLayer = null
        renderPayBar()
        val ctx = context
        when (f.step) {
            "soon" -> {
                val s = WSheet(this, "Buy coins", { closeFlow() })
                val c = WV.card(ctx, 18f, 18f).apply { gravity = Gravity.CENTER_HORIZONTAL }
                c.addView(WCoins.flat(ctx, 44f))
                c.addView(WV.text(ctx, "Coming soon", 18f, WC.ink, true).apply { gravity = Gravity.CENTER }, WV.lp(WV.WRAP, WV.WRAP, t = 10f))
                c.addView(WV.text(ctx, "Buying coins is not available yet. We are connecting UPI payments, and you will be able to buy coins here very soon. No money is taken from you now.",
                    14f, WC.sub, false, 21f).apply { gravity = Gravity.CENTER }, WV.lp(WV.MATCH, WV.WRAP, t = 6f))
                s.content.addView(c, WV.lp(WV.MATCH, WV.WRAP))
                s.content.addView(WV.primary(ctx, "OK").apply { setOnClickListener { closeFlow() } }, WV.lp(WV.MATCH, Ui.dpi(50f), t = 14f))
                // the admin can still add demo coins (no money) while Google Play payments are being set up
                if (isAdmin) s.content.addView(WV.text(ctx, "Admin: demo top-up (no money)", 14f, WC.vivid, true).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { f.step = "method"; f.method = "upi"; showFlow() }
                }, WV.lp(WV.MATCH, Ui.dpi(44f), t = 6f))
                sheetUi = s; s.show()
            }
            "pack" -> {
                val s = WSheet(this, "Buy coins", { closeFlow() })
                val c = WV.card(ctx, 14f, 6f)
                c.addView(WV.text(ctx, "Choose a pack", 16f, WC.ink, true), WV.lp(WV.MATCH, WV.WRAP, t = 12f, b = 6f))
                WData.PACKS.forEachIndexed { i, p ->
                    val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
                        setPadding(0, Ui.dpi(8f), 0, Ui.dpi(8f))
                        isClickable = true
                        setOnClickListener { if (source === WalletNet) { closeFlow(); buyWithPlay(p) } else { f.pack = p; f.step = "method"; showFlow() } }
                        WV.press(this)
                        contentDescription = "${wRupees(p.coins)} coins for ₹${wRupees(p.price)}"
                    }
                    r.addView(CoinArtView(ctx, p.art, 64f))
                    r.addView(WV.text(ctx, "${wRupees(p.coins)} coins", 16f, WC.ink, true), WV.lp(0, WV.WRAP, l = 10f, weight = 1f))
                    r.addView(WV.text(ctx, "₹${wRupees(p.price)}", 15f, WC.vivid, true).apply {
                        background = WV.round(Ui.c("#F5F0FC"), 10f)
                        setPadding(Ui.dpi(14f), Ui.dpi(6f), Ui.dpi(14f), Ui.dpi(6f))
                    }, WV.lp(WV.WRAP, WV.WRAP, l = 10f))
                    c.addView(if (i < WData.PACKS.size - 1) WV.withBottomLine(ctx, r, WC.line) else r, WV.lp(WV.MATCH, WV.WRAP))
                }
                s.content.addView(c, WV.lp(WV.MATCH, WV.WRAP))
                sheetUi = s; s.show()
            }
            "method" -> {
                val s = WSheet(this, "Payment", { closeFlow() })
                val c = WV.card(ctx, 18f, 18f).apply { gravity = Gravity.CENTER_HORIZONTAL }
                c.addView(WV.text(ctx, "You pay", 13f, WC.sub).apply { gravity = Gravity.CENTER })
                c.addView(WV.text(ctx, "₹${wRupees(f.pack.price)}", 40f, WC.ink, true).apply { letterSpacing = -0.02f; gravity = Gravity.CENTER })
                val cr = WV.box(ctx, false, Gravity.CENTER)
                cr.addView(WCoins.flat(ctx, 20f))
                cr.addView(WV.text(ctx, "${wRupees(f.pack.coins)} coins", 15f, WC.ink, true), WV.lp(WV.WRAP, WV.WRAP, l = 6f))
                c.addView(cr, WV.lp(WV.WRAP, WV.WRAP, t = 2f))
                c.addView(WV.text(ctx, "to your ${if (secret) "Reconnect" else "True Find"} wallet${if (f.pack.offer) " · first recharge offer" else ""}", 13f, WC.sub).apply { gravity = Gravity.CENTER },
                    WV.lp(WV.WRAP, WV.WRAP, t = 2f))
                s.content.addView(c, WV.lp(WV.MATCH, WV.WRAP))
                val m = WV.card(ctx, 14f, 8f)
                m.addView(WV.text(ctx, "Select payment method", 15f, WC.ink, true), WV.lp(WV.MATCH, WV.WRAP, t = 10f, b = 4f))
                val radios = ArrayList<Pair<String, View>>()
                WData.PAY_APPS.forEachIndexed { i, (k, name) ->
                    val r = WV.box(ctx, false, Gravity.CENTER_VERTICAL).apply {
                        setPadding(0, Ui.dpi(13f), 0, Ui.dpi(13f))
                        isClickable = true
                        contentDescription = name
                    }
                    r.addView(WV.payLogo(ctx, k), WV.lp(Ui.dpi(42f), Ui.dpi(42f)))
                    val nc = WV.box(ctx, true)
                    nc.addView(WV.text(ctx, name, 15f, WC.ink, true))
                    if (k == "upi") nc.addView(WV.text(ctx, "Pay with any UPI app", 12f, WC.soft))
                    r.addView(nc, WV.lp(0, WV.WRAP, l = 12f, weight = 1f))
                    val radio = View(ctx)
                    r.addView(radio, WV.lp(Ui.dpi(22f), Ui.dpi(22f), l = 12f))
                    radios.add(k to radio)
                    r.setOnClickListener {
                        f.method = k
                        for ((kk, v) in radios) v.background = if (kk == k) WV.oval(Color.WHITE, WC.vivid, 6f) else WV.oval(Color.WHITE, Ui.c("#CDBFE6"), 2f)
                    }
                    m.addView(if (i < WData.PAY_APPS.size - 1) WV.withBottomLine(ctx, r, WC.line) else r, WV.lp(WV.MATCH, WV.WRAP))
                }
                for ((kk, v) in radios) v.background = if (kk == f.method) WV.oval(Color.WHITE, WC.vivid, 6f) else WV.oval(Color.WHITE, Ui.c("#CDBFE6"), 2f)
                s.content.addView(m, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
                s.content.addView(WV.primary(ctx, "Pay ₹${wRupees(f.pack.price)}", WIcons.view(ctx, WIcons.LOCK, Color.WHITE, 16f)).apply { setOnClickListener { pay() } },
                    WV.lp(WV.MATCH, Ui.dpi(50f), t = 14f))
                s.content.addView(WV.text(ctx, "Secure payment · Coins are added to your wallet instantly", 12f, WC.soft).apply { gravity = Gravity.CENTER },
                    WV.lp(WV.MATCH, WV.WRAP, t = 10f))
                sheetUi = s; s.show()
            }
            "processing", "done", "pending", "failed" -> showResult(f)
            "opening" -> {} // Google Play's payment screen is opening on top
        }
    }

    /** Processing → Payment successful / Payment failed (full screen). */
    private fun showResult(f: Flow) {
        val ctx = context
        val done = f.step == "done"
        val coinsAdded = if (f.coins >= 0) f.coins else f.pack.coins.toLong()
        val layer = FrameLayout(ctx).apply {
            setBackgroundColor(if (done) Color.WHITE else WC.head)
            isClickable = true
            alpha = 0f
        }
        val col = WMax(ctx, 400f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val fg = if (done) WC.ink else Color.WHITE
        when (f.step) {
            "processing" -> {
                col.addView(WSpinner(ctx, 80f, Color.WHITE).apply { contentDescription = "Processing" }, WV.lp(Ui.dpi(80f), Ui.dpi(80f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                col.addView(WV.text(ctx, "Processing payment…", 24f, fg, true).apply { gravity = Gravity.CENTER }, WV.lp(WV.MATCH, WV.WRAP, t = 26f))
                col.addView(WV.text(ctx, "₹${wRupees(f.pack.price)} via ${WData.methodName(f.method)}", 14f, fg).apply { gravity = Gravity.CENTER; alpha = 0.85f }, WV.lp(WV.MATCH, WV.WRAP, t = 8f))
                col.addView(WV.text(ctx, "Please do not close the app", 13f, fg).apply { gravity = Gravity.CENTER; alpha = 0.7f }, WV.lp(WV.MATCH, WV.WRAP, t = 4f))
            }
            "done" -> {
                val circle = FrameLayout(ctx).apply { background = WV.oval(WC.head) }
                val check = WCheckView(ctx)
                circle.addView(check, LayoutParams(Ui.dpi(46f), Ui.dpi(46f), Gravity.CENTER))
                col.addView(circle, WV.lp(Ui.dpi(92f), Ui.dpi(92f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                popIn(circle); check.start()
                col.addView(WV.text(ctx, "Payment successful", 24f, WC.deep, true).apply { gravity = Gravity.CENTER }, WV.lp(WV.MATCH, WV.WRAP, t = 20f))
                val r = WV.box(ctx, false, Gravity.CENTER)
                r.addView(WCoins.flat(ctx, 34f))
                r.addView(WV.text(ctx, wRupees(coinsAdded), 40f, WC.ink, true), WV.lp(WV.WRAP, WV.WRAP, l = 8f))
                col.addView(r, WV.lp(WV.MATCH, WV.WRAP, t = 6f))
                col.addView(WV.text(ctx, "coins added to your ${if (secret) "Reconnect" else "True Find"} wallet", 14f, WC.sub).apply { gravity = Gravity.CENTER }, WV.lp(WV.MATCH, WV.WRAP, t = 2f))
                val card = WV.card(ctx, 16f, 4f)
                card.addView(WV.row(ctx, "Amount paid", "₹${wRupees(f.pack.price)}"))
                card.addView(WV.row(ctx, "Payment mode", WData.methodName(f.method)))
                card.addView(WV.row(ctx, "Date", wDate(f.at)))
                // Google Play: its whole order number (GPA.…), the one Play Console and Google's receipt email show
                if (f.ref.isNotEmpty()) card.addView(WV.row(ctx, "Reference", if (f.ref.startsWith("GPA.")) f.ref else f.ref.takeLast(12).uppercase()))
                card.addView(WV.row(ctx, "New balance", "${wRupees(coins)} coins", last = true, valueColor = WC.green))
                col.addView(card, WV.lp(WV.MATCH, WV.WRAP, t = 18f))
                col.addView(WV.primary(ctx, "Done").apply { setOnClickListener { closeFlow() } }, WV.lp(WV.MATCH, Ui.dpi(50f), t = 18f))
            }
            "pending" -> {
                // paid at Google, not confirmed yet (a UPI payment can take a few minutes): the coins come by themselves
                val circle = FrameLayout(ctx).apply { background = WV.oval(Color.argb(41, 255, 255, 255)) }
                circle.addView(WIcons.view(ctx, WIcons.CLOCK, Color.WHITE, 50f).apply { layoutParams = LayoutParams(Ui.dpi(50f), Ui.dpi(50f), Gravity.CENTER) })
                col.addView(circle, WV.lp(Ui.dpi(88f), Ui.dpi(88f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                popIn(circle)
                col.addView(WV.text(ctx, "Payment pending", 24f, fg, true).apply { gravity = Gravity.CENTER }, WV.lp(WV.MATCH, WV.WRAP, t = 20f))
                col.addView(WV.text(ctx, "Your ${wRupees(f.pack.coins)} coins will be added as soon as Google confirms the payment. UPI can take a few minutes — you can close this screen.",
                    14f, fg, false, 20f).apply { gravity = Gravity.CENTER; alpha = 0.88f }, WV.lp(WV.MATCH, WV.WRAP, t = 8f))
                col.addView(WV.primary(ctx, "Back to wallet", bg = Color.WHITE, fg = WC.deep).apply { setOnClickListener { closeFlow() } }, WV.lp(WV.MATCH, Ui.dpi(50f), t = 22f))
            }
            else -> {
                val circle = FrameLayout(ctx).apply { background = WV.oval(Color.argb(41, 255, 255, 255)) }
                circle.addView(WIcons.view(ctx, WIcons.CIRCLE_X, Ui.c("#FECACA"), 52f).apply { layoutParams = LayoutParams(Ui.dpi(52f), Ui.dpi(52f), Gravity.CENTER) })
                col.addView(circle, WV.lp(Ui.dpi(88f), Ui.dpi(88f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                popIn(circle)
                col.addView(WV.text(ctx, "Payment failed", 24f, fg, true).apply { gravity = Gravity.CENTER }, WV.lp(WV.MATCH, WV.WRAP, t = 20f))
                col.addView(WV.text(ctx, f.error, 14f, fg, false, 20f).apply { gravity = Gravity.CENTER; alpha = 0.88f }, WV.lp(WV.MATCH, WV.WRAP, t = 8f))
                col.addView(WV.text(ctx, if (f.method == "gplay") "If money was taken, your coins are added by themselves — open the Wallet again in a little while."
                    else "If money was debited, your bank returns it in 3–5 working days.", 12.5f, fg, false, 18f).apply { gravity = Gravity.CENTER; alpha = 0.7f },
                    WV.lp(WV.MATCH, WV.WRAP, t = 6f))
                col.addView(WV.primary(ctx, "Back to wallet", bg = Color.WHITE, fg = WC.deep).apply { setOnClickListener { closeFlow() } }, WV.lp(WV.MATCH, Ui.dpi(50f), t = 22f))
                col.addView(WV.text(ctx, "Get help", 14f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { closeFlow(); goToHelp() }
                }, WV.lp(WV.MATCH, Ui.dpi(44f), t = 10f))
            }
        }
        val sc = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val center = FrameLayout(ctx).apply { setPadding(Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f)) }
        center.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        sc.addView(center, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layer.addView(sc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layer.elevation = Ui.dp(30f)
        layer.animate().alpha(1f).setDuration(200).start()
        resultLayer = layer
    }

    /** Runs the (admin demo) top-up and drives the Processing → Success / Failed screens. */
    private fun pay() {
        val f = flow ?: return
        if (topUpBusy) return
        topUpBusy = true
        val pack = f.pack
        val forApp = app
        val startedAt = System.currentTimeMillis()
        f.step = "processing"
        note = ""
        showFlow()
        source.fn("topUpWallet", JSONObject().put("pack", pack.id).put("app", forApp)) { r ->
            if (app != forApp) { topUpBusy = false; return@fn }
            val d = r.data
            if (r.ok) {
                d.optJSONObject("wallet")?.let { wallet = it; drawnFrom.remove(forApp) }
                // after a first purchase the offer card goes away (load() checks with the server)
                sel = null
                load { _ ->
                    topUpBusy = false
                    val ref = d.str("transaction_id").ifEmpty { d.optJSONObject("transaction").str("id") }.ifEmpty { d.str("txn_id") }
                        .ifEmpty { d.str("reference") }.ifEmpty { d.str("id") }
                    // keep the processing ring on screen for a moment so it reads as a real step
                    val wait = Math.max(0L, 900L - (System.currentTimeMillis() - startedAt))
                    main.postDelayed({
                        val cur = flow ?: return@postDelayed
                        cur.step = "done"; cur.at = startedAt; cur.ref = ref
                        showFlow()
                    }, wait)
                }
            } else {
                topUpBusy = false
                if (d.str("error") == "offer_used") { sel = null; load() }
                val cur = flow ?: return@fn
                cur.step = "failed"
                cur.error = d.str("message").ifEmpty { d.str("error") }.ifEmpty { axiosMessage(r) }.ifEmpty { "Payment could not be completed." }
                showFlow()
            }
        }
    }

    /** Reconnect plan (Daily ₹12 · Starter ₹39 · Pro ₹79 · Elite ₹169): Google Play's payment screen (25 Nov),
     *  then the server gives the plan's days and credits. (The emulator pictures keep the old checkout call.) */
    private fun buyPlan(planKey: String) {
        if (buyingCredits) return
        buyingCredits = true
        note = ""
        renderBody(); renderPayBar()
        if (source === WalletNet) {
            PlayBilling.buy(context, PlayBilling.productForPlan(planKey), "secret", paid = {
                note = "Payment received — adding your plan…"; renderBody()
            }) { o ->
                buyingCredits = false
                note = when (o) {
                    is PlayBilling.Outcome.Done -> "Payment received — your Reconnect plan is active!"
                    PlayBilling.Outcome.Pending -> "Payment pending — your plan starts as soon as Google confirms the payment."
                    PlayBilling.Outcome.Cancelled -> ""
                    is PlayBilling.Outcome.NotReady -> "Online payments are coming soon."
                    is PlayBilling.Outcome.Failed ->
                        if (o.code == "verify_first") "Complete your Creator verification first — then Reconnect is free for you." else o.message
                }
                if (o is PlayBilling.Outcome.Done) { load(); loadCredits { renderBody() } }
                renderBody(); renderPayBar()
            }
            return
        }
        source.fn("createPremiumCheckout", JSONObject().put("app", "secret").put("plan", planKey)) { r ->
            val url = if (r.ok) r.data.str("url") else ""
            if (url.isNotEmpty()) {
                openCheckout(url) // the phone's browser opens the payment page; back in the app the plan is read again
                return@fn
            }
            val d = r.data
            note = when {
                r.status == 503 -> "Online payments are coming soon."
                d.str("error") == "verify_first" -> "Complete your Creator verification first — then Reconnect is free for you."
                r.ok -> d.str("error").ifEmpty { "Could not start checkout." }
                else -> d.str("message").ifEmpty { d.str("error") }.ifEmpty { axiosMessage(r) }.ifEmpty { "Could not start checkout." }
            }
            buyingCredits = false
            renderBody(); renderPayBar()
        }
    }

    /** First-time offer: 1 True Find coin → Premium for 1 day + 3 request credits (once). */
    private fun claimIntro() {
        if (claimingIntro) return
        claimingIntro = true
        note = ""
        renderBody()
        source.fn("claimIntroOffer", JSONObject()) { r ->
            val d = r.data
            if (r.ok) {
                if (d.opt("ok") == true) {
                    d.optJSONObject("wallet")?.let { tfCoins = it.num("coins") }
                    note = "Done — Reconnect Premium is on for 1 day with 3 request credits."
                    loadCredits { claimingIntro = false; renderBody() }
                    return@fn
                }
            } else {
                note = when {
                    d.str("error") == "insufficient_coins" -> "You need 1 True Find coin. Add coins to your True Find wallet first."
                    d.str("error") == "verify_first" -> "Complete your verification first to get this offer."
                    truthy(d.opt("needs_membership")) -> "Join Reconnect first, then come back for the offer."
                    else -> d.str("message").ifEmpty { d.str("error") }.ifEmpty { "Could not start the offer. Please try again." }
                }
                if (d.str("error") == "offer_used") { loadCredits { claimingIntro = false; renderBody() }; return@fn }
            }
            claimingIntro = false
            renderBody()
        }
    }

    // ═════════════════ transaction detail ═════════════════

    private fun openTx(tx: JSONObject) {
        txOpen = tx
        renderPayBar()
        val ctx = context
        val plus = tx.num("amount") >= 0
        val s = WSheet(this, "Transaction detail", { closeTx() })
        val c = WV.card(ctx, 18f, 18f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(WV.txIcon(ctx, WData.txKind(tx.str("description"), tx.str("type"), tx.num("amount")), plus))
        c.addView(WV.text(ctx, tx.str("description").ifEmpty { tx.str("type") }.ifEmpty { "Transaction" }, 17f, WC.ink, true).apply { gravity = Gravity.CENTER },
            WV.lp(WV.WRAP, WV.WRAP, t = 8f))
        c.addView(WV.text(ctx, "${if (plus) "+" else "−"}${wRupees(Math.abs(tx.num("amount")))} coins", 34f, if (plus) WC.green else WC.ink, true).apply { gravity = Gravity.CENTER },
            WV.lp(WV.WRAP, WV.WRAP, t = 4f))
        s.content.addView(c, WV.lp(WV.MATCH, WV.WRAP))
        val d = WV.card(ctx, 16f, 4f)
        d.addView(WV.row(ctx, "Type", if (plus) "Added" else "Spent"))
        d.addView(WV.row(ctx, "Date", wDate(tx.str("created_date")).ifEmpty { "—" }))
        if (truthy(tx.opt("status"))) d.addView(WV.row(ctx, "Status", tx.opt("status")?.toString().orEmpty()))
        d.addView(WV.row(ctx, "ID", tx.str("id").ifEmpty { "—" }.takeLast(12).uppercase(), last = true))
        s.content.addView(d, WV.lp(WV.MATCH, WV.WRAP, t = 12f))
        s.content.addView(WV.primary(ctx, "Close").apply { setOnClickListener { closeTx() } }, WV.lp(WV.MATCH, Ui.dpi(50f), t = 14f))
        sheetUi?.dismiss()
        sheetUi = s
        s.show()
    }

    private fun closeTx() {
        txOpen = null
        sheetUi?.dismiss(); sheetUi = null
        renderPayBar()
    }

    // ═════════════════ pull down to refresh ═════════════════
    private val SLOP = Ui.dp(10f)
    private var ptrTrack = false
    private var ptrPull = false
    private var ptrX = 0f
    private var ptrY = 0f
    private var ptrD = 0f

    // finger travel → spinner travel (easy at first, harder near the end), in dp
    private fun travel(dyPx: Float): Float {
        val d = ((dyPx - SLOP) / Ui.density).coerceAtLeast(0f) * 0.55f
        return if (d <= 60f) d else minOf(112f, 60f + (d - 60f) * 0.4f)
    }

    private fun onInput(x: Float, y: Float): Boolean {
        for (e in listOfNotNull(if (amountEditReady) amountEdit else null, if (rateEditReady) rateEdit else null)) {
            if (!e.isShown) continue
            val loc = IntArray(2); e.getLocationOnScreen(loc)
            val me2 = IntArray(2); getLocationOnScreen(me2)
            val ex = loc[0] - me2[0]; val ey = loc[1] - me2[1]
            if (x >= ex && x <= ex + e.width && y >= ey && y <= ey + e.height) return true
        }
        return false
    }

    private fun detect(e: MotionEvent): Boolean {
        if (!ptrTrack || ptrPull) return ptrPull
        val dx = e.x - ptrX; val dy = e.y - ptrY
        if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return false
        if (dy > 0 && dy > Math.abs(dx) * 1.4f && scroll.scrollY <= 0) ptrPull = true else ptrTrack = false
        return ptrPull
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ptrPull = false
                ptrTrack = !spinner.spinning && !loading && sheetUi == null && resultLayer == null && txPage == null && scroll.scrollY <= 0 && !onInput(e.x, e.y)
                ptrX = e.x; ptrY = e.y; ptrD = 0f
            }
            MotionEvent.ACTION_MOVE -> return detect(e)
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ptrPull) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> return ptrTrack
                MotionEvent.ACTION_MOVE -> if (!detect(e)) return ptrTrack
                else -> return false
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> { ptrD = travel(e.y - ptrY); spinner.pull(ptrD, 64f); scroll.follow(Ui.dp(ptrD) * 0.8f) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ptrPull = false; ptrTrack = false
                scroll.settle()
                if (ptrD >= 64f && e.actionMasked == MotionEvent.ACTION_UP) refreshNow() else spinner.hide()
                ptrD = 0f
            }
        }
        return true
    }

    /** Pull down at the top: balance, packs and history load again. */
    private fun refreshNow() {
        spinner.spin(22f)
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        val t0 = System.currentTimeMillis()
        var left = if (app == "secret") 3 else 2
        val doneOne = {
            left -= 1
            if (left == 0) {
                val wait = (650 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)
                main.postDelayed({ spinner.hide() }, wait)
            }
        }
        main.postDelayed({ if (spinner.spinning) spinner.hide() }, 8000) // at most 8 s, like the web
        load { doneOne() }
        loadProfile { doneOne() }
        if (app == "secret") loadCredits { doneOne() }
    }

    // ═════════════════ test pictures (emulator only) ═════════════════

    /** Opens a pop-up / state for the emulator pictures (WalletDemo scenes). */
    fun previewOpen(what: String) {
        when (what) {
            "pick" -> pick(if (offerOn) "n49" else "c399")
            "pickbig" -> pick("c12999")
            "unpick" -> { pick("c399"); main.postDelayed({ pick("c399") }, 1500) }
            "plan" -> pickPlan("starter")
            "planpay" -> { pickPlan("starter"); main.postDelayed({ buyPlan("starter") }, 600) }   // the plan's button with no payment gateway yet
            "soon" -> { isAdmin = false; openBuy(WData.findPack("c399")) }
            "method" -> { isAdmin = true; openBuy(WData.findPack("c399")) }
            "pack" -> { isAdmin = true; openBuy(null) }
            "processing" -> { flow = Flow("processing", WData.findPack("c399")!!, "gpay"); showFlow() }
            "done" -> { flow = Flow("done", WData.findPack("c399")!!, "phonepe", System.currentTimeMillis(), "tx_demo_9f8e7d6c5b4a"); showFlow() }
            "failed" -> { flow = Flow("failed", WData.findPack("c399")!!, "upi", error = "Top-up is not available yet."); showFlow() }
            // Google Play (25 Nov)
            "gopening" -> { pick("c399"); main.postDelayed({ flow = Flow("opening", WData.findPack("c399")!!, "gplay"); renderPayBar() }, 700) }
            "gprocessing" -> { flow = Flow("processing", WData.findPack("c399")!!, "gplay"); showFlow() }
            "gdone" -> { flow = Flow("done", WData.findPack("c399")!!, "gplay", System.currentTimeMillis(), "GPA.3384-1290-5521-77142"); showFlow() }
            "gpending" -> { flow = Flow("pending", WData.findPack("c399")!!, "gplay"); showFlow() }
            "gfailed" -> { flow = Flow("failed", WData.findPack("c399")!!, "gplay", error = "Google Play is not reachable right now. Check your internet and try again."); showFlow() }
            "gsoon-admin" -> { isAdmin = true; flow = Flow("soon", WData.findPack("c399")!!); showFlow() }
            "gplan-done" -> { pickPlan("starter"); note = "Payment received — your Reconnect plan is active!"; renderBody(); renderPayBar() }
            "gplan-pending" -> { pickPlan("starter"); note = "Payment pending — your plan starts as soon as Google confirms the payment."; renderBody(); renderPayBar() }
            "tx" -> transactions.firstOrNull()?.let { openTx(it) }
            "help" -> { faqOpen.clear(); faqOpen.add(0); renderBody(); goToHelp() }
            "earnings" -> setTab("earnings")
            "alltx" -> { showAllTx = true; openHistory() }
            "draft" -> { setTab("earnings"); main.postDelayed({ if (amountEditReady) amountEdit.setText("1500") }, 300) }
        }
    }
}

/** A typeface for part of a text (TypefaceSpan(Typeface) needs Android 9). */
class TypefaceSpanCompat(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = tf }
}
