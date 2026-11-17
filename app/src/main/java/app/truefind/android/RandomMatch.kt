package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * RANDOM MATCH — native version of Home's Random Match (src/pages/Home.jsx):
 *  1. Voice Call / Video Call on Home → the price sheet ("Random Voice Call":
 *     Price, Your balance, Add coins, Find someone) — same look as the web Sheet.
 *  2. Find someone → "Finding someone for you…" → "Calling Riya…" (CallStageView,
 *     the web's CallStageScreen look, with the member's own photo).
 *  3. Same matching as the web: getOnlineNow → free people of the other gender
 *     (RandomPick), up to 4 tried one by one with startCall, then the web call
 *     page /call/{id} (the call itself stays the web's, like native Explore).
 *  4. Same pop-ups: No one is free right now · Couldn't connect · Add coins to call.
 *
 * Opened from the web Home's buttons (WebBridge → MainActivity) or with open(type)
 * (a native Home can call MainActivity.openRandomMatch).
 */
@SuppressLint("ViewConstructor")
class RandomMatchLayer(
    ctx: Context,
    private val go: (String, JSONObject?) -> Unit,
) : FrameLayout(ctx) {

    // Home.jsx colours: C.ink, C.sub, C.soft and the button gradient BTN
    private val INK = Ui.c("#1f0a3d"); private val SUB = Ui.c("#6a6280"); private val SOFT = Ui.c("#8e86a3")
    private val BTN_TOP = Ui.c("#7a0ad7"); private val BTN_BOTTOM = Ui.c("#5806a3")
    private val EASE = PathInterpolator(0.22f, 0.61f, 0.36f, 1f) // the web sheet's slide (tfhUp)

    private val main = Handler(Looper.getMainLooper())
    // the call prices are kept on the phone like the web keeps them (tf_app_config_v1), so the
    // sheet shows the right price straight away; getAppConfig refreshes them
    private val prefs = ctx.getSharedPreferences("tf_random_match", Context.MODE_PRIVATE)

    // ── data (kept while the app runs) ──
    private var coins: Long? = null
    private var rates = intArrayOf(10, 40)          // voice, video — getAppConfig overrides (web: useCallRates)
    private var ratesAt = 0L
    private var me: JSONObject? = null              // my own card from getOnlineNow (photo, gender, ids)
    private var meAt = 0L
    private var meLoading = false
    private var account = ""                        // the login the data above belongs to
    private var myFace: Bitmap? = null
    private var lastMatch: String? = null
    private var matchRun = 0
    private var searching = false
    private var covered = false
    private var preview = false

    // ── views ──
    private val stage = CallStageView(ctx, webLook = true).apply { visibility = View.GONE }
    private var stageOn = false
    private var sheetLayer: View? = null
    private var sheetType = ""
    private var balanceText: TextView? = null
    private var priceText: TextView? = null
    private var addCoins: View? = null
    private var dialogLayer: View? = null

    init {
        rates = intArrayOf(prefs.getInt("voice", rates[0]), prefs.getInt("video", rates[1]))
        clipChildren = false
        visibility = View.GONE
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ═════════════════ from the app ═════════════════

    /** Voice Call / Video Call tapped in Random Match: the price sheet first (like the web). */
    fun open(type: String) {
        if (stageOn) return
        val t = if (type == "video") "video" else "voice"
        closeDialog()
        if (!preview) {
            checkAccount()
            loadWallet()
            loadRates()
            loadMe()
        }
        showSheet(t)
    }

    /** The phone's back button: closes a pop-up first; nothing while a call is being placed. */
    fun onBack(): Boolean {
        if (stageOn) { if (searching) cancelMatch(); return true }
        if (dialogLayer != null) { closeDialog(); return true }
        if (sheetLayer != null) { closeSheet(); return true }
        return false
    }

    /** The web page changed address. Leaving Home closes the pop-ups and stops a search (web: onHome effect). */
    fun onPath(path: String) {
        if (preview) return
        val p = path.trimEnd('/').ifEmpty { "/" }
        if (p == "/" || p == "/home") return
        closeSheet()
        closeDialog()
        if (stageOn && searching) cancelMatch()
    }

    /** A web pop-up (incoming call …) is on top: step aside until it is gone. */
    fun setCovered(c: Boolean) {
        if (preview || covered == c) return
        covered = c
        refreshShown()
    }

    // ═════════════════ data ═════════════════

    private fun checkAccount() {
        val tok = Api.token
        if (tok == account) return
        // another login on this phone: forget the old member's photo, coins and last match
        account = tok
        me = null; meAt = 0L; myFace = null; coins = null; lastMatch = null
    }

    private fun loadWallet() {
        if (!Api.ready) return
        Api.invoke("getWallet", JSONObject().put("app", "truefind")) { r ->
            val w = r.data.optJSONObject("wallet")
            if (r.ok && w != null && w.has("coins")) {
                coins = w.optDouble("coins", 0.0).toLong()
                paintBalance()
            }
        }
    }

    private fun loadRates() {
        if (!Api.ready || System.currentTimeMillis() - ratesAt < 5 * 60_000L) return
        Api.invoke("getAppConfig") { r ->
            if (r.data.optBoolean("fallback")) return@invoke
            val rt = r.data.optJSONObject("config")?.optJSONObject("rates") ?: return@invoke
            rates = intArrayOf(rt.optInt("voice", rates[0]), rt.optInt("video", rates[1]))
            ratesAt = System.currentTimeMillis()
            prefs.edit().putInt("voice", rates[0]).putInt("video", rates[1]).apply()
            paintBalance()
        }
    }

    /** My own card (photo for the search screen, gender, ids): once per app run, then from every search. */
    private fun loadMe() {
        if (!Api.ready || meLoading || (me != null && System.currentTimeMillis() - meAt < 10 * 60_000L)) return
        meLoading = true
        Api.invoke("getOnlineNow") { r ->
            meLoading = false
            r.data.optJSONObject("me")?.let { setMe(it) }
        }
    }

    private fun setMe(o: JSONObject) {
        val old = me?.let { photoUrls(it) }.orEmpty()
        me = o
        meAt = System.currentTimeMillis()
        val urls = photoUrls(o)
        if (urls != old) myFace = null
        if (urls.isNotEmpty() && myFace == null) Photos.load(urls, Ui.dpi(140f)) { b ->
            if (b != null && me?.let { photoUrls(it) } == urls) {
                myFace = b
                if (stageOn) stage.setFace(b)
            }
        }
    }

    private fun photoUrls(o: JSONObject): List<String> =
        listOf(o.optString("photo_thumb", ""), o.optString("photo", ""), o.optString("profile_picture", ""))
            .filter { it.startsWith("http") }.distinct()

    private fun myGender(): String = me?.let { J(it) }?.let { RandomPick.genderOf(it.str("gender").ifEmpty { it.str("sex") }) }.orEmpty()

    private fun myIds(): Set<String> {
        val o = me ?: return emptySet()
        val j = J(o)
        return setOf(j.str("id"), j.str("user_id"), j.str("created_by_id")).filter { it.isNotEmpty() }.toSet()
    }

    private fun rateOf(type: String) = if (type == "video") rates[1] else rates[0]

    // ═════════════════ matching (web: startRandom) ═════════════════

    private fun startRandom(type: String) {
        if (stageOn) return
        val run = ++matchRun
        closeSheet()
        searching = true
        showStage("search", type, "")
        val t0 = System.currentTimeMillis()
        // Who can be matched: the people who have True Find open right now (same live
        // list as Explore). If that list can't be read, the Home creators list is used.
        Api.invoke("getOnlineNow") { r ->
            if (run != matchRun) return@invoke
            r.data.optJSONObject("me")?.let { setMe(it) }
            val live = if (r.ok) r.data.optJSONArray("profiles") else null
            if (live != null) {
                matchFrom(run, type, t0, (0 until live.length()).mapNotNull { live.optJSONObject(it) })
            } else {
                Api.invoke("getDiscoverProfiles") { r2 ->
                    if (run != matchRun) return@invoke
                    val a = if (r2.ok) r2.data.optJSONArray("profiles") else null
                    matchFrom(run, type, t0, if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) })
                }
            }
        }
    }

    private fun matchFrom(run: Int, type: String, t0: Long, list: List<JSONObject>) {
        val wait = (1700 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)
        main.postDelayed({
            if (run != matchRun) return@postDelayed // cancelled
            val now = System.currentTimeMillis()
            val pool = RandomPick.order(list.map { RandomPick.cand(J(it), now) }, myIds(), myGender(), lastMatch)
            if (pool.isEmpty()) {
                hideStage()
                openDialog("No one is free right now", "Everyone online is on another call. Please try again in a minute.")
                return@postDelayed
            }
            tryNext(run, type, pool.take(4), 0, null)
        }, wait)
    }

    private fun tryNext(run: Int, type: String, pool: List<RandomPick.Cand>, i: Int, lastErr: String?) {
        if (run != matchRun) return
        if (i >= pool.size) {
            hideStage()
            if (lastErr != null) openDialog("Couldn't connect", lastErr)
            else openDialog("No one is free right now", "Everyone online is on another call. Please try again in a minute.")
            return
        }
        val p = pool[i]
        searching = false
        showStage("calling", type, p.name)
        Api.invoke("startCall", JSONObject().put("to_user_id", p.callId).put("type", type).put("app", "truefind")) { r ->
            if (run != matchRun) return@invoke
            val id = r.data.optJSONObject("call")?.optString("id").orEmpty()
            if (r.ok && id.isNotEmpty()) {
                lastMatch = p.callId
                coins = null // the balance changes with the call; asked again next time
                go("/call/$id", null)
                main.postDelayed({ if (run == matchRun) hideStage() }, 400)
                return@invoke
            }
            if (r.ok) { tryNext(run, type, pool, i + 1, lastErr); return@invoke } // no call came back: next person
            val err = r.data.optString("error")
            if (r.status == 402 || err == "insufficient_coins") { hideStage(); showCoins(type, r.data); return@invoke }
            // a problem with MY account (sign-up not finished, banned) won't change with another person
            if (r.status == 403 && (err == "banned" || err.contains("complete your profile", ignoreCase = true))) {
                hideStage()
                openDialog("Couldn't connect", if (err == "banned") "Your account can't make calls right now." else err)
                return@invoke
            }
            val next = if (r.status == 409 || err == "busy") lastErr else errText(r)
            tryNext(run, type, pool, i + 1, next)
        }
    }

    /** web errText(): the server's own short message, else the fallback. */
    private fun errText(r: Api.Result): String {
        val d = r.data
        val m = when {
            d.opt("error") is String -> d.optString("error")
            d.opt("message") is String -> d.optString("message")
            else -> ""
        }
        return if (m.isNotEmpty() && m.length < 140 && !Regex("^[a-z_]+$").matches(m)) m else "Something went wrong. Please try again."
    }

    private fun cancelMatch() {
        matchRun += 1
        searching = false
        hideStage()
    }

    private fun showCoins(type: String, d: JSONObject) {
        val rate = d.optInt("rate", 0).takeIf { it > 0 } ?: rateOf(type)
        val bal = if (d.has("balance") && !d.isNull("balance")) d.optDouble("balance", 0.0).toLong() else (coins ?: 0L)
        openDialog("Add coins to call",
            "${if (type == "video") "Video" else "Voice"} calls cost $rate coins per minute. Your balance is ${rupees(bal)} coins.",
            "Add coins") { go("/wallet", null) }
    }

    // ═════════════════ the search / calling screen ═════════════════

    private fun showStage(st: String, type: String, name: String) {
        stageOn = true
        stage.onCancel = if (st == "search") ({ cancelMatch() }) else null
        stage.show(st, type, rateOf(type), myFace, name, title = if (st == "search") "Finding someone for you…" else null)
        refreshShown()
    }

    private fun hideStage() {
        stageOn = false
        searching = false
        stage.hide()
        refreshShown()
    }

    // ═════════════════ the price sheet (web: Sheet + Random Match confirm) ═════════════════

    private fun showSheet(type: String) {
        closeSheet()
        val ctx = context
        sheetType = type
        val layer = FrameLayout(ctx)
        layer.addView(dim(0.45f) { closeSheet() }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val sheet = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(Ui.dp(24f), Ui.dp(24f), Ui.dp(24f), Ui.dp(24f), 0f, 0f, 0f, 0f)
            }
            elevation = Ui.dp(16f)
            outlineSpotShadowColorCompat(Ui.c("#1E083C"))
            isClickable = true // taps on the sheet don't close it
            contentDescription = "Random ${if (type == "video") "Video" else "Voice"} Call"
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dpi(18f), Ui.dpi(10f), Ui.dpi(18f), Ui.dpi(22f))
        }
        // grab handle
        col.addView(View(ctx).apply { background = Ui.round(Ui.c("#ddd2ee"), 2f) },
            LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dpi(12f) })

        // icon + title + line
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(if (type == "video") LucideIcon(LucideIcon.VIDEO, Color.WHITE, 26f, 1.6f, fill = true)
                             else LucideIcon(LucideIcon.MIC, Color.WHITE, 26f, 2.3f))
            background = Ui.vgrad(BTN_TOP, BTN_BOTTOM, 16f)
            elevation = Ui.dp(6f)
            outlineSpotShadowColorCompat(Ui.c("#5A06AA"))
        }
        head.addView(icon, LinearLayout.LayoutParams(Ui.dpi(54f), Ui.dpi(54f)).apply { rightMargin = Ui.dpi(14f) })
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Ui.text(ctx, "Random ${if (type == "video") "Video" else "Voice"} Call", 20f, INK))
        words.addView(Ui.text(ctx, "We'll connect you with someone who is online and free right now.", 14f, SUB, false).apply {
            setLineSpacing(0f, 1.18f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(3f) })
        head.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)

        // Price · Your balance
        val price = infoRow(ctx, "Price", "")
        priceText = price.getChildAt(1) as TextView
        col.addView(price, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(16f) })
        val bal = infoRow(ctx, "Your balance", "")
        balanceText = bal.getChildAt(1) as TextView
        col.addView(bal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(8f) })
        col.addView(Ui.text(ctx, "Billed per minute only while the call is connected (minimum 1 minute). You can end the call anytime.", 12.5f, SOFT, false).apply {
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dpi(10f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f)
        })

        // Add coins (only when the balance is below one minute) · Find someone
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val add = Ui.text(ctx, "Add coins", 15.5f, Ui.c("#5b12b8")).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Color.WHITE, 16f, Ui.c("#d9c6f7"), 1.5f)
            setOnClickListener { closeSheet(); go("/wallet", null) }
        }
        Ui.pressable(add, 0.97f)
        btns.addView(add, LinearLayout.LayoutParams(0, Ui.dpi(50f), 1f).apply { rightMargin = Ui.dpi(10f) })
        addCoins = add
        val find = Ui.text(ctx, "Find someone", 15.5f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Ui.vgrad(BTN_TOP, BTN_BOTTOM, 16f)
            elevation = Ui.dp(6f)
            outlineSpotShadowColorCompat(Ui.c("#5A06AA"))
            setOnClickListener { if (preview) previewSearch(type) else startRandom(type) }
        }
        Ui.pressable(find, 0.97f)
        btns.addView(find, LinearLayout.LayoutParams(0, Ui.dpi(50f), 1.4f))
        col.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(16f) })
        sheet.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ✕ (top right)
        val close = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(LucideIcon(LucideIcon.X, Ui.c("#5b4a7a"), 18f, 2.2f))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.c("#f4effc")) }
            contentDescription = "Close"
            setOnClickListener { closeSheet() }
        }
        sheet.addView(close, LayoutParams(Ui.dpi(34f), Ui.dpi(34f), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.dpi(10f); rightMargin = Ui.dpi(12f)
        })

        val w = minOf(resources.displayMetrics.widthPixels, Ui.dpi(480f))
        layer.addView(sheet, LayoutParams(w, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sheetLayer = layer
        paintBalance()
        refreshShown()

        // slide up like the web (tfhUp .34s, starts 70 ms after it is on screen)
        sheet.visibility = View.INVISIBLE
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.VISIBLE
            sheet.animate().translationY(0f).setStartDelay(70).setDuration(340).setInterpolator(EASE).start()
        }
    }

    private fun infoRow(ctx: Context, label: String, value: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Ui.c("#f8f4ff"), 16f, Ui.c("#ece4fa"))
            setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
            addView(Ui.text(ctx, label, 14.5f, SUB, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(ctx, value, 16f, INK).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dpi(10f) })
        }

    /** Price, "982 coins" (or "…" until the wallet answers) and the Add coins button when below one minute. */
    private fun paintBalance() {
        priceText?.text = "${rateOf(sheetType)} coins per minute"
        val c = coins
        balanceText?.text = if (c == null) "…" else "${rupees(c)} coins"
        addCoins?.visibility = if (c != null && c < rateOf(sheetType)) View.VISIBLE else View.GONE
    }

    private fun closeSheet() {
        sheetLayer?.let { removeView(it) }
        sheetLayer = null
        balanceText = null
        priceText = null
        addCoins = null
        refreshShown()
    }

    // ═════════════════ pop-ups (web: Home's Dialog) ═════════════════

    private fun openDialog(title: String, text: String, goLabel: String? = null, onGo: (() -> Unit)? = null) {
        closeDialog()
        val ctx = context
        val layer = FrameLayout(ctx)
        layer.addView(dim(0.5f) { closeDialog() }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(Color.WHITE, 22f)
            elevation = Ui.dp(20f)
            outlineSpotShadowColorCompat(Ui.c("#1E083C"))
            setPadding(Ui.dpi(20f), Ui.dpi(22f), Ui.dpi(20f), Ui.dpi(18f))
            isClickable = true
        }
        card.addView(Ui.text(ctx, title, 19f, INK).apply { gravity = Gravity.CENTER })
        card.addView(Ui.text(ctx, text, 14.5f, SUB, false).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(8f) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (goLabel != null) {
            val g = Ui.text(ctx, goLabel, 15f, Color.WHITE).apply {
                gravity = Gravity.CENTER; background = Ui.vgrad(BTN_TOP, BTN_BOTTOM, 14f)
                setOnClickListener { closeDialog(); onGo?.invoke() }
            }
            Ui.pressable(g, 0.97f)
            row.addView(g, LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f).apply { rightMargin = Ui.dpi(10f) })
        }
        val ok = Ui.text(ctx, if (goLabel != null) "Not now" else "OK", 15f, Ui.c("#4a1a8c")).apply {
            gravity = Gravity.CENTER; background = Ui.round(Ui.c("#f3eefc"), 14f)
            setOnClickListener { closeDialog() }
        }
        Ui.pressable(ok, 0.97f)
        row.addView(ok, LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f))
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(18f) })
        val w = minOf(Ui.dpi(340f), resources.displayMetrics.widthPixels - Ui.dpi(48f))
        layer.addView(card, LayoutParams(w, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dialogLayer = layer
        refreshShown()
        // pop in (tfhPop .2s)
        card.alpha = 0f; card.scaleX = 0.94f; card.scaleY = 0.94f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun closeDialog() {
        dialogLayer?.let { removeView(it) }
        dialogLayer = null
        refreshShown()
    }

    /** The dark see-through layer behind a pop-up (fades in .18 s like tfhFade). */
    private fun dim(alpha: Float, onTap: () -> Unit): View = View(context).apply {
        setBackgroundColor(Color.argb((alpha * 255).toInt(), 24, 8, 52))
        setOnClickListener { onTap() }
        this.alpha = 0f
        animate().alpha(1f).setDuration(180).start()
    }

    private fun refreshShown() {
        val any = stageOn || sheetLayer != null || dialogLayer != null
        visibility = if (any && !covered) View.VISIBLE else View.GONE
    }

    // ═════════════════ test pictures (CI emulator, made-up data, no login) ═════════════════

    /** what: sheet · sheet_video · sheet_low · search · search_face · search_video · calling · calling_face · nofree · couldnt · coins */
    fun preview(what: String) {
        preview = true
        rates = intArrayOf(10, 40)
        coins = if (what == "sheet_low") 6L else 982L
        val demo = Person.demoPeople("male").first { !it.busy }
        when (what) {
            "sheet", "sheet_low" -> open("voice")
            "sheet_video" -> open("video")
            "search", "search_video" -> { myFace = null; showStage("search", if (what == "search_video") "video" else "voice", "") }
            "search_face" -> { myFace = AvatarArt.forPerson(Person.demoPeople("female").first(), Ui.dpi(140f)); showStage("search", "voice", "") }
            "calling" -> { myFace = null; showStage("calling", "voice", demo.name) }
            "calling_face" -> { myFace = AvatarArt.forPerson(Person.demoPeople("female").first(), Ui.dpi(140f)); showStage("calling", "video", demo.name) }
            "nofree" -> openDialog("No one is free right now", "Everyone online is on another call. Please try again in a minute.")
            "couldnt" -> openDialog("Couldn't connect", "Something went wrong. Please try again.")
            "coins" -> showCoins("video", JSONObject().put("rate", 40).put("balance", 6))
            else -> open("voice")
        }
    }

    /** Test pictures only: Find someone walks through the screens without calling anyone. */
    private fun previewSearch(type: String) {
        val run = ++matchRun
        closeSheet()
        searching = true
        showStage("search", type, "")
        main.postDelayed({
            if (run != matchRun) return@postDelayed
            searching = false
            showStage("calling", type, "Riya")
            main.postDelayed({ if (run == matchRun) { hideStage(); openDialog("No one is free right now", "Everyone online is on another call. Please try again in a minute.") } }, 1500)
        }, 1700)
    }

    /** A profile card as RandomPick reads it. */
    private class J(private val o: JSONObject) : RandomPick.Obj {
        override fun str(key: String): String = when (val v = o.opt(key)) {
            null, JSONObject.NULL -> ""
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> ""
        }
        override fun isTrue(key: String): Boolean = o.opt(key) == true
        override fun truthy(key: String): Boolean = when (val v = o.opt(key)) {
            null, JSONObject.NULL -> false
            is Boolean -> v
            is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
            is String -> v.isNotEmpty()
            else -> true
        }
    }
}

/** Lucide icons (the web's icon set) drawn from their path data in a 24 × 24 box: stroke, or fill + stroke. */
class LucideIcon(
    data: String,
    private val color: Int,
    sizeDp: Float,
    private val stroke: Float,
    private val fill: Boolean = false,
) : android.graphics.drawable.Drawable() {
    private val path = androidx.core.graphics.PathParser.createPathFromPathData(data)
    private val px = Ui.dpi(sizeDp)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = this@LucideIcon.color
        style = if (fill) android.graphics.Paint.Style.FILL_AND_STROKE else android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        c.drawPath(path, paint)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        // lucide-react 0.475: mic, video, x (the web sheet's icons)
        const val MIC = "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z M19 10v2a7 7 0 0 1-14 0v-2 M12 19L12 22"
        const val VIDEO = "m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5 M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"
        const val X = "M18 6 6 18 M6 6l12 12"
    }
}
