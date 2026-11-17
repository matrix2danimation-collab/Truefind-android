package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * EXPLORE · CONNECT — native version of src/pages/Explore.jsx.
 * Same data (getOnlineNow), same filters, same calls (startCall), same look.
 * It sits above the web app's bottom bar, which stays the web's own.
 *
 * [host]  full-screen layer for the sheet, dialogs and the calling screen
 * [go]    opens a web page of the app: go(path, stateJson)
 */
@SuppressLint("ViewConstructor")
class ExploreScreen(
    ctx: Context,
    private val host: FrameLayout,
    private val go: (String, JSONObject?) -> Unit,
) : FrameLayout(ctx) {

    companion object {
        const val POLL_MS = 15000L
        const val POLL_MIN = 12000L
        const val POLL_MAX = 60000L
        val CHIPS = listOf(
            "all" to "For You", "near" to "Nearby", "ver" to "Verified", "free" to "Free now",
            "t:Hindi" to "Hindi", "t:English" to "English", "t:Punjabi" to "Punjabi", "t:Haryanvi" to "Haryanvi",
            "t:Love" to "Love", "t:Relationship" to "Relationship", "t:Breakup" to "Breakup", "t:Friendship" to "Friendship",
            "t:Romantic Talk" to "Romantic Talk", "t:Just Talking" to "Just Talking", "t:Loneliness" to "Loneliness", "t:Stress" to "Stress",
            "t:Singing" to "Singing", "t:Movies" to "Movies", "t:Shayari" to "Shayari",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val INK = Ui.c("#3B0764")

    // ── data ──
    private var profiles: List<Person> = emptyList()
    private var me: JSONObject? = null
    private var gotList = false
    private var loading = false
    private var error = ""
    private var coins: Long? = null
    private var coinsAt = 0L
    private var rates = intArrayOf(10, 40) // voice, video (getAppConfig overrides)
    private var filter = "all"
    private var lastFilter: String? = null
    private var pollMs = POLL_MS
    private var oldFeed = false
    private var lastLoadAt = 0L
    private var shown = false
    private val following = HashMap<String, Boolean>()
    private var calling = false
    private var matchRun = 0
    private var lastMatch: String? = null

    // ── views ──
    private val feed = FeedView(ctx) { p -> tap(p) }
    private val header = LinearLayout(ctx)
    private val coinText: TextView
    private val chipViews = ArrayList<TextView>()
    private val actions = LinearLayout(ctx)
    private val voiceRate: TextView
    private val videoRate: TextView
    private val empty = LinearLayout(ctx)
    private val emptyText: TextView
    private val emptyBtn: TextView
    private val toast: TextView
    private val stage = CallStageView(ctx).apply { visibility = View.GONE }
    private var sheetLayer: View? = null
    private var dialogLayer: View? = null

    init {
        setBackgroundColor(Color.WHITE)
        clipChildren = false

        // ── the feed (between the header and the buttons, under both) ──
        addView(feed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── header: True Find · coins, then the filter chips ──
        header.orientation = LinearLayout.VERTICAL
        header.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.WHITE, Ui.c("#F8F3FF"))).apply {
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, Ui.dp(22f), Ui.dp(22f), Ui.dp(22f), Ui.dp(22f))
            setStroke(Ui.dpi(1f), Ui.c("#E6D9FA"))
        }
        header.elevation = Ui.dp(10f)
        header.outlineSpotShadowColorCompat(Ui.c("#18043C"))
        header.setPadding(Ui.dpi(14f), Ui.dpi(32f), Ui.dpi(14f), Ui.dpi(13f))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = SpannableString("True Find").apply {
            setSpan(ForegroundColorSpan(Ui.c("#6C04CB")), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        row.addView(Ui.text(ctx, brand, 18f, INK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val coin = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Ui.c("#F4ECFF"), 20f, Ui.c("#E3D3FB"))
            setPadding(Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(4f), Ui.dpi(3f))
            contentDescription = "Wallet balance, add coins"
            setOnClickListener { go("/wallet", null) }
        }
        coin.addView(ImageView(ctx).apply { setImageDrawable(CoinDrawable(20f)) }, LinearLayout.LayoutParams(Ui.dpi(20f), Ui.dpi(20f)))
        coinText = Ui.text(ctx, "–", 13f, INK).apply { setPadding(Ui.dpi(5f), 0, Ui.dpi(5f), 0) }
        coin.addView(coinText)
        coin.addView(Ui.text(ctx, "+", 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.c("#6C04CB")) }
        }, LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)).apply { leftMargin = Ui.dpi(2f) })
        row.addView(coin)
        header.addView(row)
        val chipsScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        CHIPS.forEachIndexed { i, (k, label) ->
            val tv = Ui.text(ctx, label, 12.5f, Ui.c("#5B3E8C")).apply {
                setPadding(Ui.dpi(13f), Ui.dpi(6f), Ui.dpi(13f), Ui.dpi(6f))
                setOnClickListener { setFilter(k) }
                tag = k
            }
            chipViews.add(tv)
            chips.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = Ui.dpi(7f) })
        }
        chipsScroll.addView(chips)
        header.addView(chipsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(10f) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        paintChips()

        // ── Voice / Video / Random ──
        actions.orientation = LinearLayout.HORIZONTAL
        actions.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#F8F3FF"), Color.WHITE, Color.WHITE)).apply {
            cornerRadii = floatArrayOf(Ui.dp(22f), Ui.dp(22f), Ui.dp(22f), Ui.dp(22f), 0f, 0f, 0f, 0f)
            setStroke(Ui.dpi(1f), Ui.c("#E6D9FA"))
        }
        actions.elevation = Ui.dp(10f)
        actions.setPadding(Ui.dpi(12f), Ui.dpi(13f), Ui.dpi(12f), Ui.dpi(11f))
        val (vb, vr) = actionButton(ctx, "Voice", PathIcon(PathIcon.PHONE, Ui.c("#16a34a"), 15f), INK, Ui.c("#674A96"), Ui.round(Color.WHITE, 14f, Ui.c("#E3D3FB"))) { startRandom("voice") }
        val (db, dr) = actionButton(ctx, "Video", PathIcon(PathIcon.VIDEO, Color.WHITE, 16f), Color.WHITE, Ui.c("#E9D5FF"),
            Ui.vgrad(Ui.c("#7A0AD7"), Ui.c("#5806A3"), 14f)) { startRandom("video") }
        val (rb, _) = actionButton(ctx, "Random", PathIcon(PathIcon.RANDOM, Ui.c("#6C04CB"), 15f, 2.4f), Ui.c("#4C1D95"), Ui.c("#674A96"),
            Ui.round(Ui.c("#F1E8FE"), 14f, Ui.c("#E3D3FB")), smallText = "match now", coin = false) { startRandom(if (Math.random() < 0.5) "voice" else "video") }
        voiceRate = vr; videoRate = dr
        listOf(vb, db, rb).forEachIndexed { i, b ->
            actions.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = Ui.dpi(8f) })
        }
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        showRates()

        // ── empty / error note ──
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER_HORIZONTAL
        emptyText = Ui.text(ctx, "", 13.5f, Ui.c("#EFE4FF"), false).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }
        empty.addView(emptyText)
        emptyBtn = Ui.text(ctx, "Retry", 13f, Color.WHITE).apply {
            background = Ui.round(Color.argb(36, 255, 255, 255), 12f, Color.argb(77, 255, 255, 255))
            setPadding(Ui.dpi(16f), Ui.dpi(7f), Ui.dpi(16f), Ui.dpi(7f))
        }
        empty.addView(emptyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(10f) })
        empty.visibility = View.GONE
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply { leftMargin = Ui.dpi(24f); rightMargin = Ui.dpi(24f) })

        toast = Ui.text(ctx, "", 12f, Color.WHITE).apply {
            background = Ui.round(Ui.c("#2E0A63"), 18f, Color.argb(56, 255, 255, 255))
            setPadding(Ui.dpi(14f), Ui.dpi(8f), Ui.dpi(14f), Ui.dpi(8f))
            visibility = View.GONE
            elevation = Ui.dp(12f)
        }
        addView(toast, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP))

        host.addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.elevation = Ui.dp(40f)

        // the feed sits under the header and the buttons by 20 (rounded edges overlap it)
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { placeFeed() } }
        actions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { placeFeed() } }
    }

    private fun placeFeed() {
        val lp = feed.layoutParams as LayoutParams
        val top = (header.height - Ui.dpi(20f)).coerceAtLeast(0)
        val bot = (actions.height - Ui.dpi(20f)).coerceAtLeast(0)
        if (lp.topMargin != top || lp.bottomMargin != bot) {
            lp.topMargin = top; lp.bottomMargin = bot
            feed.layoutParams = lp
        }
        (toast.layoutParams as LayoutParams).let { if (it.topMargin != top + Ui.dpi(30f)) { it.topMargin = top + Ui.dpi(30f); toast.layoutParams = it } }
    }

    private fun actionButton(
        ctx: Context, label: String, icon: PathIcon, color: Int, smallColor: Int, bg: GradientDrawable,
        smallText: String? = null, coin: Boolean = true, onClick: () -> Unit,
    ): Pair<View, TextView> {
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = bg
            setPadding(Ui.dpi(6f), Ui.dpi(8f), Ui.dpi(6f), Ui.dpi(8f))
            setOnClickListener { onClick() }
        }
        Ui.pressable(b)
        val top = Ui.text(ctx, label, 14f, color).apply {
            gravity = Gravity.CENTER
            compoundDrawablePadding = Ui.dpi(5f)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        }
        b.addView(top)
        val small = Ui.text(ctx, smallText ?: "", 10.5f, smallColor).apply {
            gravity = Gravity.CENTER
            compoundDrawablePadding = Ui.dpi(3f)
            if (coin) setCompoundDrawablesWithIntrinsicBounds(CoinDrawable(12f), null, null, null)
        }
        b.addView(small, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(2f) })
        return b to small
    }

    private fun showRates() {
        voiceRate.text = "${rates[0]} /min"
        videoRate.text = "${rates[1]} /min"
    }

    private fun paintChips() {
        for (tv in chipViews) {
            val on = tv.tag == filter
            tv.setTextColor(if (on) Color.WHITE else Ui.c("#5B3E8C"))
            tv.background = if (on) Ui.vgrad(Ui.c("#7A0AD7"), Ui.c("#5806A3"), 16f) else Ui.round(Ui.c("#F6F0FF"), 16f, Ui.c("#E8DDF8"))
        }
    }

    private fun setFilter(k: String) {
        if (filter == k) return
        filter = k
        paintChips()
        pushToFeed()
    }

    // ═════════════════ shown / hidden (follows the web address) ═════════════════
    fun setShown(on: Boolean) {
        if (on == shown) return
        shown = on
        if (on) {
            visibility = View.VISIBLE
            feed.reopened()
            if (!gotList || System.currentTimeMillis() - lastLoadAt > minOf(pollMs, 4000L)) load()
            loadWallet(false)
            loadRates()
            schedulePoll()
        } else {
            feed.running = false
            visibility = View.GONE
            closeSheet(); closeDialog()
            main.removeCallbacks(pollTick)
        }
    }

    /** The phone's back button while Explore is on screen: closes a pop-up first. */
    fun onBack(): Boolean {
        if (stage.visibility == View.VISIBLE) { if (searching) cancelMatch(); return true }
        if (dialogLayer != null) { closeDialog(); return true }
        if (sheetLayer != null) { closeSheet(); return true }
        return false
    }

    /** The app came back to the screen (or went away). */
    fun onAppVisible(visible: Boolean) {
        if (!shown) return
        feed.running = visible
        if (visible) { load(); schedulePoll() } else main.removeCallbacks(pollTick)
    }

    private val pollTick = Runnable {
        if (shown) load()
        schedulePoll()
    }

    private fun schedulePoll() {
        main.removeCallbacks(pollTick)
        if (shown) main.postDelayed(pollTick, pollMs)
    }

    // ═════════════════ data ═════════════════
    fun load() {
        if (loading) return
        if (!Api.ready) { main.postDelayed({ if (shown) load() }, 700); return }
        loading = true
        lastLoadAt = System.currentTimeMillis()
        val fn = if (oldFeed) "getDiscoverProfiles" else "getOnlineNow"
        Api.invoke(fn) { r ->
            loading = false
            if (!oldFeed && r.status == 404) { oldFeed = true; load(); return@invoke }
            if (!r.ok) {
                error = r.error ?: "Could not load who is online."
                refreshEmpty()
                return@invoke
            }
            val d = r.data
            val arr = d.optJSONArray("profiles") ?: d.optJSONArray("items")
            val fresh = ArrayList<Person>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = Person.from(o)
                if (p.id.isNotEmpty() && p.online) fresh.add(p)
            }
            if (!oldFeed) {
                val pm = d.optLong("poll_ms", 0L)
                pollMs = if (pm > 0) pm.coerceIn(POLL_MIN, POLL_MAX) else POLL_MS
            } else pollMs = 30000L
            d.optJSONObject("me")?.let { me = it }
            // photos of the first people the feed shows: fetched straight away
            fresh.take(24).forEach { if (it.photoUrls.isNotEmpty()) Photos.load(it.photoUrls, feed.faceSize) { } }
            profiles = fresh
            gotList = true
            error = ""
            pushToFeed()
        }
    }

    private fun myGender(): String = Person.genderOf(me?.optString("gender", "") ?: "")

    private fun everyone(): List<Person> = if (Person.SHOW_DEMO) profiles + Person.demoPeople(myGender()) else profiles

    private fun filtered(all: List<Person>): List<Person> {
        val myCity = me?.optString("city", "")?.trim()?.lowercase().orEmpty()
        val myState = me?.optString("state", "")?.trim()?.lowercase().orEmpty()
        return all.filter { p ->
            when {
                filter.startsWith("t:") -> p.tagsText.contains(filter.substring(2).lowercase())
                filter == "ver" -> p.verified
                filter == "free" -> !p.busy
                filter == "near" -> (myCity.isNotEmpty() && p.city.lowercase() == myCity) || (myState.isNotEmpty() && p.state.lowercase() == myState)
                else -> true
            }
        }
    }

    private fun pushToFeed() {
        // nobody goes on the feed until the online list has come once (my own
        // profile comes with it, so boys see girls and girls see boys from the start)
        if (!gotList) { refreshEmpty(); return }
        val all = everyone()
        val list = filtered(all)
        val reset = lastFilter != filter
        lastFilter = filter
        feed.setPool(list, all, reset)
        refreshEmpty()
    }

    private fun refreshEmpty() {
        if (loading && !gotList) { empty.visibility = View.GONE; return }
        val all = if (gotList) everyone() else emptyList()
        val list = if (gotList) filtered(all) else emptyList()
        if (gotList && list.isNotEmpty()) { empty.visibility = View.GONE; return }
        empty.visibility = View.VISIBLE
        emptyText.text = when {
            !gotList -> error.ifBlank { "Could not load who is online." }
            error.isNotBlank() && all.isEmpty() -> error
            all.isNotEmpty() -> "No one online matches this right now."
            else -> "No one is online right now. People show up here within seconds of opening True Find."
        }
        val showAll = gotList && all.isNotEmpty()
        emptyBtn.text = if (showAll) "Show everyone" else "Retry"
        emptyBtn.setOnClickListener { if (showAll) setFilter("all") else load() }
    }

    private fun loadWallet(force: Boolean) {
        if (!force && System.currentTimeMillis() - coinsAt < 30000 && coins != null) return
        if (!Api.ready) { main.postDelayed({ if (shown) loadWallet(force) }, 900); return }
        Api.invoke("getWallet", JSONObject().put("app", "truefind")) { r ->
            val w = r.data.optJSONObject("wallet")
            if (r.ok && w != null && w.has("coins")) {
                coins = w.optDouble("coins", 0.0).toLong()
                coinsAt = System.currentTimeMillis()
                coinText.text = rupees(coins!!)
            }
        }
    }

    private fun loadRates() {
        if (!Api.ready) return
        Api.invoke("getAppConfig") { r ->
            val rt = r.data.optJSONObject("config")?.optJSONObject("rates") ?: return@invoke
            if (r.data.optBoolean("fallback")) return@invoke
            rates = intArrayOf(rt.optInt("voice", rates[0]), rt.optInt("video", rates[1]))
            showRates()
        }
    }

    // ═════════════════ taps and calls ═════════════════
    private fun rateOf(type: String) = if (type == "video") rates[1] else rates[0]

    private fun bigFace(p: Person): Bitmap {
        val size = Ui.dpi(140f)
        for (u in p.photoUrls) Photos.get(u, size)?.let { return it }
        for (u in p.photoUrls) Photos.get(u, feed.faceSize)?.let { return it }
        return AvatarArt.forPerson(p, size)
    }

    /** The drawn face as a PNG data address, for the web demo call page. */
    private fun faceUrl(p: Person): String {
        if (p.photoUrls.isNotEmpty()) return p.photoUrls.last()
        val bmp = AvatarArt.forPerson(p, Ui.dpi(96f))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun peerJson(p: Person) = JSONObject()
        .put("name", p.firstName).put("photo", faceUrl(p)).put("age", p.age)
        .put("city", p.city).put("state", p.state).put("verified", p.verified)

    // tap a bubble: green phone → voice call, purple camera → video call, busy → her sheet
    private fun tap(p: Person) {
        val now = everyone().firstOrNull { it.id == p.id }
        if (now == null || !now.online) { showToast("Just went offline"); return }
        if (now.demo) {
            if (now.busy) { showToast("Demo profile · busy"); return }
            go("/demo-call", JSONObject().put("type", now.callType).put("peer", peerJson(now)))
            return
        }
        if (now.busy) { openSheet(now); return }
        callPerson(now, now.callType)
    }

    private fun showCoins(type: String, d: JSONObject) {
        val rate = d.optInt("rate", 0).takeIf { it > 0 } ?: rateOf(type)
        val bal = if (d.has("balance")) d.optDouble("balance", 0.0).toLong() else (coins ?: 0L)
        openDialog("Add coins to call",
            "${if (type == "video") "Video" else "Voice"} calls cost $rate coins per minute. Your balance is ${rupees(bal)} coins.",
            "Add coins") { go("/wallet", null) }
    }

    private fun callPerson(p: Person, type: String) {
        if (calling) return
        calling = true
        searching = false
        stage.onCancel = null
        stage.show("calling", type, rateOf(type), bigFace(p), p.firstName)
        feed.blocked = true
        Api.invoke("startCall", JSONObject().put("to_user_id", p.callId).put("type", type).put("app", "truefind")) { r ->
            calling = false
            val id = r.data.optJSONObject("call")?.optString("id").orEmpty()
            if (r.ok && id.isNotEmpty()) {
                closeSheet()
                coinsAt = 0L
                go("/call/$id", null)
                main.postDelayed({ hideStage() }, 400)
                return@invoke
            }
            hideStage()
            val err = r.data.optString("error")
            when {
                r.status == 402 || err == "insufficient_coins" -> showCoins(type, r.data)
                r.status == 409 || err == "busy" -> openDialog("${p.firstName.ifBlank { "She" }} is busy", "On another call right now. Please try again in a few minutes.")
                r.ok -> openDialog("Couldn't connect", "Could not start the call. Please try again.")
                else -> openDialog("Couldn't connect", r.error ?: "Something went wrong. Please try again.")
            }
        }
    }

    private var searching = false

    // Voice / Video / Random: someone online and free, of the other gender
    private fun startRandom(type: String) {
        if (stage.visibility == View.VISIBLE || calling) return
        val run = ++matchRun
        searching = true
        stage.onCancel = { cancelMatch() }
        stage.show("search", type, rateOf(type), null, "")
        feed.blocked = true
        val t0 = System.currentTimeMillis()
        Api.invoke("getOnlineNow") { r ->
            val live: List<Person>? = r.data.optJSONArray("profiles")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Person.from(o) } }
            }
            val wait = (1500 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)
            main.postDelayed({
                if (run != matchRun) return@postDelayed
                val myG = myGender()
                val cand = (live ?: profiles).filter { it.online && !it.busy && it.callId.isNotEmpty() && (myG.isEmpty() || it.gender.isEmpty() || it.gender != myG) }
                var pool = cand.shuffled()
                if (pool.size > 1 && pool[0].callId == lastMatch) pool = pool.drop(1) + pool[0]
                if (pool.isEmpty()) {
                    // nobody real is free: while demo profiles are on, try the call screen with one of them (nothing billed)
                    val demos = if (Person.SHOW_DEMO) Person.demoPeople(myG).filter { !it.busy } else emptyList()
                    if (demos.isNotEmpty()) {
                        val d = demos.random()
                        searching = false
                        stage.show("calling", type, rateOf(type), AvatarArt.forPerson(d, Ui.dpi(140f)), d.firstName)
                        main.postDelayed({
                            if (run != matchRun) return@postDelayed
                            go("/demo-call", JSONObject().put("type", type).put("skipRing", true).put("peer", peerJson(d)))
                            main.postDelayed({ hideStage() }, 400)
                        }, 1200)
                        return@postDelayed
                    }
                    hideStage()
                    openDialog("No one is free right now", "Everyone online is on another call. Please try again in a minute.")
                    return@postDelayed
                }
                tryNext(run, type, pool.take(4), 0, null)
            }, wait)
        }
    }

    private fun tryNext(run: Int, type: String, pool: List<Person>, i: Int, lastErr: String?) {
        if (run != matchRun) return
        if (i >= pool.size) {
            hideStage()
            if (lastErr != null) openDialog("Couldn't connect", lastErr)
            else openDialog("No one is free right now", "Everyone online is on another call. Please try again in a minute.")
            return
        }
        val p = pool[i]
        searching = false
        stage.show("calling", type, rateOf(type), bigFace(p), p.firstName)
        Api.invoke("startCall", JSONObject().put("to_user_id", p.callId).put("type", type).put("app", "truefind")) { r ->
            if (run != matchRun) return@invoke
            val id = r.data.optJSONObject("call")?.optString("id").orEmpty()
            if (r.ok && id.isNotEmpty()) {
                lastMatch = p.callId
                coinsAt = 0L
                go("/call/$id", null)
                main.postDelayed({ hideStage() }, 400)
                return@invoke
            }
            val err = r.data.optString("error")
            if (r.status == 402 || err == "insufficient_coins") { hideStage(); showCoins(type, r.data); return@invoke }
            if (r.status == 403 && (err == "banned" || err.contains("complete your profile", true))) {
                hideStage()
                openDialog("Couldn't connect", if (err == "banned") "Your account can't make calls right now." else err)
                return@invoke
            }
            val next = if (r.status == 409 || err == "busy") lastErr else (r.error ?: lastErr)
            tryNext(run, type, pool, i + 1, next)
        }
    }

    private fun cancelMatch() { matchRun += 1; searching = false; hideStage() }

    private fun hideStage() {
        stage.hide()
        feed.blocked = sheetLayer != null || dialogLayer != null
    }

    private fun toggleFollow(p: Person, btn: TextView) {
        val id = p.callId
        val on = following[id] != true
        following[id] = on
        paintFollow(btn, on)
        Api.invoke("follow", JSONObject().put("action", "set").put("user_id", id).put("on", on)) { r ->
            if (!r.ok) { following[id] = !on; paintFollow(btn, !on); showToast("Couldn't update. Try again.") }
        }
    }

    private fun paintFollow(btn: TextView, on: Boolean) {
        btn.text = if (on) "Following" else "+ Follow"
        btn.setTextColor(if (on) Ui.c("#111111") else Color.WHITE)
        btn.background = Ui.round(if (on) Ui.c("#f3f4f6") else Ui.c("#111111"), 14f)
    }

    // ═════════════════ pop-ups ═════════════════
    private val hideToast = Runnable { toast.visibility = View.GONE }
    private fun showToast(s: String) {
        toast.text = s
        toast.visibility = View.VISIBLE
        toast.alpha = 0f
        toast.animate().alpha(1f).setDuration(200).start()
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, 1900)
    }

    private fun dim(onTap: () -> Unit): View = View(context).apply {
        setBackgroundColor(Color.argb(140, 5, 0, 10))
        setOnClickListener { onTap() }
        alpha = 0f
        animate().alpha(1f).setDuration(250).start()
    }

    /** Busy person: her profile sheet (Follow; calls once she is free). */
    private fun openSheet(p: Person) {
        closeSheet()
        val ctx = context
        val layer = FrameLayout(ctx).apply { elevation = Ui.dp(30f) }
        layer.addView(dim { closeSheet() }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(Ui.dp(22f), Ui.dp(22f), Ui.dp(22f), Ui.dp(22f), 0f, 0f, 0f, 0f)
            }
            setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(20f))
            isClickable = true
        }
        sheet.addView(View(ctx).apply { background = Ui.round(Ui.c("#e5e7eb"), 4f) },
            LinearLayout.LayoutParams(Ui.dpi(38f), Ui.dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dpi(12f) })
        val gone = profiles.none { it.id == p.id } && !p.demo
        val busy = !gone && p.busy
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val av = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (gone) Ui.c("#9A8FB8") else if (busy) Ui.c("#ef4444") else Ui.c("#C4B5FD")) }
            setPadding(Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f))
        }
        av.addView(ImageView(ctx).apply {
            setImageBitmap(bigFace(p))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setPadding(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        row.addView(av, LinearLayout.LayoutParams(Ui.dpi(70f), Ui.dpi(70f)).apply { rightMargin = Ui.dpi(13f) })
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        info.addView(Ui.text(ctx, p.firstName.ifBlank { "Someone" } + (if (p.age > 0) ", ${p.age}" else ""), 18f, Ui.c("#111111")).apply {
            if (p.verified) {
                compoundDrawablePadding = Ui.dpi(5f)
                setCompoundDrawablesWithIntrinsicBounds(null, null, VerifiedMark(17f), null)
            }
        })
        val place = listOf(p.city, p.state).filter { it.isNotBlank() }.joinToString(", ")
        if (place.isNotEmpty()) info.addView(Ui.text(ctx, place, 12f, Ui.c("#6b7280"), false).apply { setPadding(0, Ui.dpi(1f), 0, 0) })
        info.addView(Ui.text(ctx, if (gone) "● Offline" else if (busy) "● Busy · on another call" else "● Online · Free — call now", 11.5f,
            if (gone) Ui.c("#6b7280") else if (busy) Ui.c("#dc2626") else Ui.c("#16a34a")).apply { setPadding(0, Ui.dpi(3f), 0, 0) })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fol = Ui.text(ctx, "", 12f, Color.WHITE).apply { setPadding(Ui.dpi(12f), Ui.dpi(6f), Ui.dpi(12f), Ui.dpi(6f)) }
        paintFollow(fol, following[p.callId] == true)
        fol.setOnClickListener { toggleFollow(p, fol) }
        row.addView(fol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })
        sheet.addView(row)
        if (p.bio.isNotBlank()) sheet.addView(Ui.text(ctx, p.bio, 12.5f, Ui.c("#4b5563"), false).apply { setLineSpacing(0f, 1.1f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(12f) })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val (vb, vr) = actionButton(ctx, "Voice Call", PathIcon(PathIcon.PHONE, Ui.c("#16a34a"), 15f), INK, Ui.c("#674A96"), Ui.round(Color.WHITE, 14f, Ui.c("#e5e7eb"))) { callPerson(p, "voice") }
        val (db, dr) = actionButton(ctx, "Video Call", PathIcon(PathIcon.VIDEO, Color.WHITE, 16f), Color.WHITE, Ui.c("#E9D5FF"), Ui.vgrad(Ui.c("#7A0AD7"), Ui.c("#5806A3"), 14f)) { callPerson(p, "video") }
        vr.text = "${rates[0]} /min"; dr.text = "${rates[1]} /min"
        val off = busy || gone
        listOf(vb, db).forEachIndexed { i, b ->
            b.setPadding(Ui.dpi(6f), Ui.dpi(10f), Ui.dpi(6f), Ui.dpi(10f))
            b.isEnabled = !off; b.alpha = if (off) 0.45f else 1f
            btns.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = Ui.dpi(10f) })
        }
        sheet.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(14f) })
        sheet.addView(Ui.text(ctx, if (busy) "Follow to know when she is free" else if (gone) "She shows up here again when she opens True Find" else "Billed per second · minimum 1 minute",
            10.5f, Ui.c("#9ca3af"), false).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(9f) })
        layer.addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        host.addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setStartDelay(70).setDuration(340)
                .setInterpolator(android.view.animation.PathInterpolator(0.22f, 0.61f, 0.36f, 1f)).start()
        }
        sheetLayer = layer
        feed.blocked = true
    }

    private fun closeSheet() {
        sheetLayer?.let { host.removeView(it) }
        sheetLayer = null
        feed.blocked = dialogLayer != null || stage.visibility == View.VISIBLE
    }

    private fun openDialog(title: String, text: String, goLabel: String? = null, onGo: (() -> Unit)? = null) {
        closeDialog()
        val ctx = context
        val layer = FrameLayout(ctx).apply { elevation = Ui.dp(50f) }
        layer.addView(dim { closeDialog() }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(Color.WHITE, 22f)
            setPadding(Ui.dpi(18f), Ui.dpi(20f), Ui.dpi(18f), Ui.dpi(16f))
            isClickable = true
        }
        card.addView(Ui.text(ctx, title, 17f, Ui.c("#160636")).apply { gravity = Gravity.CENTER })
        card.addView(Ui.text(ctx, text, 13.5f, Ui.c("#5C4C6E"), false).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(6f) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, bg: GradientDrawable, color: Int, click: () -> Unit) = Ui.text(ctx, label, 14f, color).apply {
            gravity = Gravity.CENTER; background = bg; setOnClickListener { click() }
        }
        row.addView(btn(if (goLabel != null) "Not now" else "OK", Ui.round(Ui.c("#F5EFFC"), 14f), Ui.c("#160636")) { closeDialog() },
            LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f))
        if (goLabel != null) row.addView(btn(goLabel, Ui.vgrad(Ui.c("#7A0AD7"), Ui.c("#5806A3"), 14f), Color.WHITE) { closeDialog(); onGo?.invoke() },
            LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f).apply { leftMargin = Ui.dpi(10f) })
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(16f) })
        layer.addView(card, LayoutParams(Ui.dpi(340f).coerceAtMost(resources.displayMetrics.widthPixels - Ui.dpi(48f)), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        host.addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dialogLayer = layer
        feed.blocked = true
    }

    private fun closeDialog() {
        dialogLayer?.let { host.removeView(it) }
        dialogLayer = null
        feed.blocked = sheetLayer != null || stage.visibility == View.VISIBLE
    }
}

/** The blue verified badge (same shape as the web's). */
class VerifiedMark(sizeDp: Float) : android.graphics.drawable.Drawable() {
    private val px = Ui.dpi(sizeDp)
    private val star = androidx.core.graphics.PathParser.createPathFromPathData("m12 1 2.6 2.1 3.3-.3.9 3.2 2.9 1.6-1 3.2 1 3.2-2.9 1.6-.9 3.2-3.3-.3L12 23l-2.6-2.1-3.3.3-.9-3.2-2.9-1.6 1-3.2-1-3.2 2.9-1.6.9-3.2 3.3.3z")
    private val tick = androidx.core.graphics.PathParser.createPathFromPathData("m8 12 3 3 5-6")
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#3b82f6") }
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.4f; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: android.graphics.Canvas) {
        c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat()); c.scale(bounds.width() / 24f, bounds.height() / 24f)
        c.drawPath(star, fill); c.drawPath(tick, line); c.restore()
    }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Coloured shadow where the phone supports it (Android 9+). */
fun View.outlineSpotShadowColorCompat(color: Int) {
    if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = color; outlineAmbientShadowColor = color }
}

@Suppress("unused")
private val keepEllipsize = TextUtils.TruncateAt.END
