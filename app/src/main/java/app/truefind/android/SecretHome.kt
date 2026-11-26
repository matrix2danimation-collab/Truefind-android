package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * RECONNECT · Home (26 Nov: Reconnect is now "Reconnect"; the owner took out the Discover cards, the ✕ ♥ likes,
 * Liked you / Matches, Favorite and Stories — Reconnect keeps the map, the chats and the plans).
 * Home: the header (photo · logo · credits), Complete your profile, Notifications (who messaged you or viewed your
 * profile) and Private by Design. Data: getDiscoverProfiles (only whether Reconnect is joined), getMapProfiles,
 * getRequestCredits, getSecretActivity, UserProfile, PremiumAccess.
 * The Reconnect bottom bar (Home · Chat · Map) is the app's own too (SdNavBar): these pages end where it starts.
 * A member who has not joined Reconnect yet gets the Join card here (JoinCard).
 */
@SuppressLint("ViewConstructor")
class SecretHome(
    ctx: Context,
    private val host: FrameLayout,
    private val go: (String, JSONObject?) -> Unit,
) : KeepPage(ctx) {

    private val main = Handler(Looper.getMainLooper())
    val layer = SdLayer(host)

    // ── data (same names as the web) ──
    private var discover: List<JSONObject>? = null
    private var needSecret = false
    private var online: List<JSONObject>? = null
    private var mapPremium: Boolean? = null
    private var mapUntil = ""
    private var access: Pair<Boolean, String>? = null      // (active, until)
    private var credits: Int? = null
    private var member: JSONObject? = null
    private var intro: JSONObject? = null
    private var me: JSONObject? = null
    private var myId = ""
    private var myProfile: JSONObject? = null                // null = still loading
    private var mine = JSONObject()                           // photo, name, gender, city, state
    private var activity: JSONObject? = null
    private val freshIds = LinkedHashSet<String>()
    private var actKnown: Set<String>? = null
    private val inviteState = HashMap<String, String>()
    /**
     * My Reconnect chats, by the other person's profile id and user id (owner, 20 Nov: after a request was
     * sent, their profile asked for "Send Request" again — the request opens a chat at once, but the profile only
     * knew that when opened from the chat list). A profile with a chat shows Open Chat; Send Request on its card
     * opens the chat. Read as the member (their own matches), not as the app.
     */
    private val chatWith = HashMap<String, String>()
    private var chatsSeq = 0
    private fun loadMyChats(done: (() -> Unit)? = null) {
        val me = myId
        if (me.isEmpty()) { done?.invoke(); return }
        val my = ++chatsSeq
        val q = JSONObject().put("\$or", org.json.JSONArray()
            .put(JSONObject().put("user_a_id", me).put("app", "secret"))
            .put(JSONObject().put("user_b_id", me).put("app", "secret")))
        Api.filter("Match", q, "-created_date", 500) { r ->
            if (my == chatsSeq && r.ok) {
                val items = r.data.optJSONArray("items")
                if (items != null) {
                    chatWith.clear(); chatWith.putAll(SdChatIndex.build(items, me))
                    openChats.clear(); openChats.addAll(SdChatOpen.openIds(items, me))
                    render()
                }
            }
            done?.invoke()
        }
    }
    /** the chat I already have with this person ("" when none) */
    fun chatIdFor(p: JSONObject): String = SdChatIndex.find(chatWith, p)
    /**
     * Chats opened once (a request paid with a credit, or a message sent with a plan): they open without a running
     * plan, for good (owner, 20 Nov: "a chat once opened never locks again"). The same rule as the server's.
     */
    private val openChats = HashSet<String>()
    fun chatOpen(matchId: String): Boolean = matchId.isNotEmpty() &&
        (matchId in openChats || ChatCache.chat(matchId)?.optBoolean("open") == true || chatPage?.chats?.any { it.id == matchId && it.open } == true)
    /** this person's chat is open for me without a plan */
    fun chatOpenWith(p: JSONObject): Boolean = p.optBoolean("_chatOpen") || chatOpen(chatIdFor(p))
    private val viewed = HashSet<String>()
    private var notice = ""
    private var shown = false
    private var seenSent = false
    private val prefs = ctx.getSharedPreferences("sd", 0)

    // ── views ──
    private val scroll = ScrollView(ctx)
    private val col = vbox(ctx)
    /** everything under "Complete your profile": Notifications, Private by Design */
    private val body = vbox(ctx)
    /** not a member of Reconnect yet: the Join card takes the body's place (JoinAppCard.jsx) */
    private var joinCard: View? = null
    private val myPhoto = RoundPhoto(ctx, 43f)
    private val headLeft = FrameLayout(ctx)
    private val headTitle = FrameLayout(ctx)
    private val headRight = FrameLayout(ctx)
    private var headMember: Boolean? = null
    /** the header's parts on this phone: 1 on the test phone's width (411), down to .84 on a narrow one — so the whole logo has room */
    private val headU = (ctx.resources.displayMetrics.widthPixels / Ui.density / 411f).coerceIn(.84f, 1f)
    private val logo = Rs.logo(ctx, (ctx.resources.displayMetrics.widthPixels / Ui.density * .064f).coerceIn(21f, 26f))
    private val myBadge = FrameLayout(ctx)
    private val creditsPill = sdText(ctx, "", 14f, Rs.INK)
    private val pills = ArrayList<TextView>()                 // the same pill on the map and chat pages
    /** which Reconnect page: "home" (/secret-app) · "map" (/reconnect-map) · "chat" (/secret-app?tab=chat) */
    var page = "home"; private set
    private val backdrop = PinkBackdrop(ctx)
    private var mapPage: SdMapPage? = null
    private var chatPage: SdChatsPage? = null
    private val noticeBox = sdText(ctx, "", 15f, Rs.c("#166534"), bold = false)
    private val actBox = FrameLayout(ctx)
    private val completeBtn = LinearLayout(ctx)
    private val completeSub = sdText(ctx, "", 13.5f, Rs.c("#8E2A55"), bold = false)
    private val toast = LinearLayout(ctx)
    private val toastText = sdText(ctx, "", 15f, K.ink2, bold = false)
    private val toastBtn = sdText(ctx, "", 14f, Color.WHITE)

    init {
        SdRules.load(ctx)
        clipChildren = false
        // nothing of these pages may be drawn below their bottom edge: the Reconnect bar is there
        outlineProvider = android.view.ViewOutlineProvider.BOUNDS
        clipToOutline = true
        addView(backdrop, LayoutParams(-1, -1))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        addView(scroll, LayoutParams(-1, -1))
        col.setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(34f))
        col.clipChildren = false
        // the stories row and the card reach the screen's edges (their −16 margins): nothing of them is cut at the padding
        col.clipToPadding = false
        scroll.addView(col, LayoutParams(-1, -2))
        // (no soft white fade over the page's foot above the bottom bar any more: the owner wants it clean, 23 Nov night)

        buildHeader()
        noticeBox.background = rounded(Rs.c("#F0FDF4"), 14f, 1f, Rs.argb(89, 22, 163, 74))
        noticeBox.setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(11f))
        noticeBox.visibility = GONE
        noticeBox.setOnClickListener { notice = ""; noticeBox.visibility = GONE }
        col.addView(noticeBox, lp(MATCH, WRAP, t = 12f))
        buildComplete()
        body.clipChildren = false
        col.addView(body, lp(MATCH, WRAP))
        // Reconnect (26 Nov): Notifications (messages · profile views) at the top, then Private by Design
        body.addView(actBox, lp(MATCH, WRAP, t = 12f))
        // what is new counts as seen once the box has been on screen for a moment (also after scrolling to it)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (!seenSent && freshIds.isNotEmpty()) watchSeen() }
        buildPrivate()
        buildToast()
        render()
    }

    // ═════════════════ derived (same rules as the web) ═════════════════
    private val premium get() = mapPremium == true || access?.first == true
    private val premiumKnown get() = mapPremium != null || access != null
    private val premiumUntil get() = access?.second?.takeIf { it.isNotEmpty() } ?: mapUntil
    private val daysLeft: String? get() {
        val u = premiumUntil; if (u.isEmpty()) return null
        val ms = SdRules.ms(u) - System.currentTimeMillis()
        if (SdRules.ms(u) == 0L) return null
        if (ms <= 0) return "Renew"
        val d = ms / 86_400_000L
        return if (d >= 1) "${d}d left" else "${maxOf(1L, (ms + 3_599_999L) / 3_600_000L)}h left"
    }
    private val profileKnown get() = myProfile != null
    private val missing: List<String> get() = myProfile?.let { SdRules.missingOf(it) } ?: emptyList()
    private val profileComplete get() = !profileKnown || missing.isEmpty()
    private val myGender: String get() = SdRules.sexOf(mine).ifEmpty { SdRules.sexOf(me) }
    private val myCreator get() = SdRules.creatorStatusOf(myProfile)
    private val freeGirl get() = myGender == "f" && (member?.let { it.optBoolean("free") } ?: SdRules.girlApproved(myProfile))
    private val girlUnverified get() = myGender == "f" && profileKnown && !freeGirl
    private val canUse get() = (premium || freeGirl) && profileComplete
    private val canRequest get() = premium && profileComplete && (myGender != "f" || freeGirl)
    private val lockWhy get() = if (girlUnverified) "verify" else if (freeGirl) "plan" else "premium"
    private val creditsNow: Int? get() = if (premiumKnown && !premium) 0 else credits
    private val untilText get() = if (premium) SdRules.whenText(premiumUntil) else ""
    private fun sameGender(p: JSONObject) = myGender.isNotEmpty() && SdRules.sexOf(p) == myGender
    private fun forMe(list: List<JSONObject>): List<JSONObject> {
        val want = if (myGender == "m") "f" else if (myGender == "f") "m" else ""
        if (want.isEmpty()) return list
        if (list.none { SdRules.sexOf(it).isNotEmpty() }) return list
        return list.filter { SdRules.sexOf(it) == want }
    }
    private val liveDeck: List<JSONObject> get() {
        val seen = HashSet<String>(); val out = ArrayList<JSONObject>()
        val a = online?.let { forMe(it.filter { p -> SdRules.liveOf(p) }) } ?: emptyList()
        val b = discover?.let { forMe(it) }?.filter { SdRules.isOnline(it) }?.map { JSONObject(it.toString()).put("_online", true) } ?: emptyList()
        for (p in a + b) { val id = p.optString("id"); if (id.isNotEmpty() && seen.add(id)) out.add(p) }
        return out
    }
    private val onlineIds get() = liveDeck.map { it.optString("id") }.toHashSet()

    // ═════════════════ loading ═════════════════
    private var lastSearch: String? = null
    fun setShown(on: Boolean, view: String = "home", search: String = "") {
        if (on && search != lastSearch) { lastSearch = search; parseNotice(search) }
        if (!on) lastSearch = null
        if (on && view != page) {
            // another Reconnect page: its own content (pop-ups of the last one close, as on the web)
            layer.closeAll()
            switchTo(view)
            if (shown) { loadAll(); restartPoll() }
        }
        if (on == shown) return
        shown = on
        if (on) {
            visibility = VISIBLE
            switchTo(view)
            loadAll()
            restartPoll()
        } else {
            visibility = GONE
            layer.closeAll()
            main.removeCallbacks(poll)
            mapPage?.setActive(false); chatPage?.setActive(false)
            liveOff()
        }
    }
    private fun pageView(v: String): View? = when (v) { "home" -> scroll; "map" -> mapPage; "chat" -> chatPage; else -> null }
    private fun switchTo(view: String) {
        val from = page
        val before = pageView(from)?.takeIf { it.visibility == VISIBLE }
        switchNow(view)
        // the same smooth move as between the app's pages (Online Map | Home | Chat: left to right)
        val after = pageView(view)
        if (shown && isShown && before != null && after != null && before !== after) {
            // side by side like FRND's tabs; the pink bar at the bottom stays still
            val order = listOf("home", "chat", "map")
            PageFx.run(this, PageFx.Kind.TAB, before, after, dir = if (order.indexOf(view) > order.indexOf(from)) 1 else -1,
                onEnd = { pageView(page)?.let { cur -> listOf(scroll, mapPage, chatPage).forEach { v -> if (v != null && v !== cur) v.visibility = GONE } } })
        }
    }
    private fun switchNow(view: String) {
        page = view
        backdrop.dense = view == "chat"
        scroll.visibility = if (view == "home") VISIBLE else GONE
        if (view == "map" && mapPage == null) mapPage = SdMapPage(this).also { addView(it, indexOfChild(scroll) + 1, LayoutParams(-1, -1)) }
        if (view == "chat" && chatPage == null) chatPage = SdChatsPage(this).also { addView(it, indexOfChild(scroll) + 1, LayoutParams(-1, -1)) }
        mapPage?.visibility = if (view == "map") VISIBLE else GONE
        chatPage?.visibility = if (view == "chat") VISIBLE else GONE
        if (shown) {
            mapPage?.setActive(view == "map")
            chatPage?.setActive(view == "chat")
            if (view == "map") liveOff() else liveOn()
        }
        render()
    }
    private fun restartPoll() { main.removeCallbacks(poll); main.postDelayed(poll, 90_000) }
    fun onAppVisible(v: Boolean) {
        if (!shown) return
        if (v) { loadAll(); restartPoll(); if (page != "map") liveOn(); chatPage?.setActive(page == "chat") }
        else { main.removeCallbacks(poll); liveOff(); chatPage?.setActive(false) }
    }
    fun back(): Boolean {
        if (layer.back()) return true
        mapPage?.let { if (page == "map" && it.isFull) { it.setFull(false); return true } }
        return false
    }
    private val poll = object : Runnable {
        override fun run() {
            // every 90 s while the page is on screen (45 s before 23 Nov night: four lists every 45 s from every phone used up
            // the platform's shared allowance, and then chat messages were "busy") — not the people on the Chats page
            if (shown) { if (page != "chat") loadPeople(); loadActivity() }
            main.postDelayed(this, 90_000)
        }
    }
    // a new message arrives: Notifications updates at once (no need to wait for the next check)
    private var liveStop: (() -> Unit)? = null
    private val actSoon = Runnable { loadActivity() }
    private fun liveOn() {
        if (liveStop != null || !shown) return
        liveStop = Realtime.subscribe("Message") { ev ->
            val d = ev.data ?: return@subscribe
            if (ev.type == "create" && d.optString("app").ifEmpty { "secret" } == "secret" && d.optString("to_user_id").isNotEmpty() && d.optString("to_user_id") == myId) { main.removeCallbacks(actSoon); main.postDelayed(actSoon, 900) }
        }
    }
    private fun liveOff() { liveStop?.invoke(); liveStop = null; main.removeCallbacks(actSoon) }

    private fun parseNotice(search: String) {
        val q = android.net.Uri.parse("x://x$search")
        notice = when {
            q.getQueryParameter("premium") == "success" -> "Payment received — Reconnect Premium is active!"
            q.getQueryParameter("premium") == "cancelled" -> "Payment cancelled. You can unlock anytime."
            q.getQueryParameter("credits") == "success" -> "Payment received — request credits added!"
            q.getQueryParameter("credits") == "cancelled" -> "Payment cancelled. You can recharge anytime."
            else -> notice
        }
        // a tapped notification: Home, with Notifications at its top
        if (q.getQueryParameter("activity") != null) main.postDelayed({ if (shown && !needSecret) scroll.smoothScrollTo(0, 0) }, 300)
        renderNotice()
    }

    fun loadAll(done: (() -> Unit)? = null) {
        if (!Api.ready) { main.postDelayed({ if (shown) loadAll(done) else done?.invoke() }, 700); return }
        // a plan remembered on this phone counts until the server answers (it is only what's SHOWN)
        if (access == null) prefs.getString("sd_plan_until", "")?.let { u -> if (u.isNotEmpty() && SdRules.ms(u) > System.currentTimeMillis()) access = true to u }
        var left = 3
        val one: () -> Unit = { left -= 1; if (left == 0) done?.invoke() }
        loadPeople(one); loadActivity(one); loadMyChats(one)
    }

    // ── pull down to refresh (owner, 19 Nov evening): the home page and the chats, in Reconnect's own pink ──
    private val pull: PagePull = run {
        PagePull(this, PullSpinner.PINK, PullSpinner.PINK_RING, canPull = {
            shown && !layer.open && when (page) {
                "home" -> scroll.isShown && !scroll.canScrollVertically(-1)
                "chat" -> chatPage?.let { it.isShown && it.atTop() } == true
                else -> false   // the map moves under the finger
            }
        }) { done ->
            if (page == "chat") { var left = 2; val one = { left -= 1; if (left == 0) done() }; loadAll(one); chatPage?.load(done = one) ?: one() }
            else loadAll(done)
        }
    }
    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean = pull.intercept(e) || super.onInterceptTouchEvent(e)
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean = pull.touch(e) || super.onTouchEvent(e)

    private fun loadPeople(done: (() -> Unit)? = null) {
        var left = if (page != "map") 2 else 1
        val one: () -> Unit = { left -= 1; if (left == 0) done?.invoke() }
        if (page != "map") Api.invoke("getDiscoverProfiles", JSONObject().put("intent", "dating")) { r ->
            if (r.ok) {
                needSecret = r.data.optString("needs_membership") == "secret"
                discover = list(r.data.optJSONArray("profiles"))
            } else if (discover == null) discover = emptyList()
            render()
            one()
        }
        Api.invoke("getMapProfiles", JSONObject().put("include_offline", true)) { r ->
            if (r.ok) {
                val real = list(r.data.optJSONArray("profiles")).map { p -> p.put("_online", p.opt("online") == true || (!p.has("online") && SdRules.isOnline(p))) }
                // demo profiles (testing only, see SdDemo): after the real people, the gender this member sees
                online = if (SdDemo.SHOW) real + SdDemo.people(SdRules.sexOf(r.data.optJSONObject("me"))) else real
                if (SdDemo.SHOW) SdDemoChat.viewer = SdRules.sexOf(r.data.optJSONObject("me"))
                mapPremium = r.data.optBoolean("premium", false) && r.data.opt("premium") == true
                mapUntil = r.data.optString("premium_until", "")
                me = r.data.optJSONObject("me")
                if (mapPremium == true && mapUntil.isNotEmpty()) prefs.edit().putString("sd_plan_until", mapUntil).apply()
            } else if (online == null) online = emptyList()
            render()
            one()
        }
        Api.invoke("getRequestCredits") { r ->
            if (r.ok) {
                // the same answer the Reconnect wallet asks for: handed over, so it opens with fresh credits at once
                onCredits?.invoke(r.data)
                val c = r.data.optJSONObject("credits")
                credits = if (c != null && c.has("available_request_credits") && !c.isNull("available_request_credits")) c.optInt("available_request_credits") else null
                r.data.optJSONObject("member")?.let { member = it }
                r.data.optJSONObject("intro")?.let { intro = it }
                render()
            }
        }
        Api.me { u ->
            val uid = u.data.optString("id")
            if (!u.ok || uid.isEmpty()) { if (myProfile == null) myProfile = JSONObject(); render(); return@me }
            val first = myId.isEmpty()
            myId = uid
            if (first) loadMyChats()
            Api.filter("UserProfile", JSONObject().put("created_by_id", uid), limit = 1) { pr ->
                val p = pr.data.optJSONArray("items")?.optJSONObject(0)
                myProfile = p ?: JSONObject().put("full_name", u.data.optString("full_name"))
                mine = JSONObject().put("photo", SdRules.photoOf(p)).put("name", SdRules.s(p, "full_name", "name").ifEmpty { u.data.optString("full_name") })
                    .put("gender", SdRules.sexOf(p).ifEmpty { SdRules.sexOf(u.data) }).put("city", SdRules.s(p, "city")).put("state", SdRules.s(p, "state"))
                render()
            }
            Api.filter("PremiumAccess", JSONObject().put("user_id", uid).put("app", "secret"), "-until", 1) { pa ->
                // a failed check is never "no plan" (it showed every chat locked after reopening the app)
                if (!pa.ok) return@filter
                val a = pa.data.optJSONArray("items")?.optJSONObject(0)
                val until = a?.optString("until", "") ?: ""
                access = (until.isNotEmpty() && SdRules.ms(until) > System.currentTimeMillis()) to until
                if (access?.first == true) prefs.edit().putString("sd_plan_until", until).apply() else prefs.edit().remove("sd_plan_until").apply()
                render()
            }
        }
    }


    private fun loadActivity(done: (() -> Unit)? = null) {
        Api.invoke("getSecretActivity") { r ->
            done?.invoke()
            val items = r.data.optJSONArray("items")
            if (!r.ok || items == null) { if (activity == null) { activity = JSONObject().put("items", JSONArray()).put("counts", JSONObject()).put("can_see", false); renderActivity() }; return@invoke }
            // (likes are gone from Reconnect, 26 Nov: only messages and profile views are shown)
            val all = list(items).filter { it.optString("kind").ifEmpty { "like" } != "like" }
            all.filter { it.optBoolean("fresh") }.forEach { freshIds.add(it.optString("id")) }
            val known = actKnown
            if (known != null) {
                val added = all.filter { it.optBoolean("fresh") && it.optString("id") !in known }
                if (added.isNotEmpty() && shown && page == "home") showPop(added)
            }
            actKnown = all.map { it.optString("id") }.toSet()
            if ((r.data.optJSONObject("counts")?.optInt("total") ?: 0) > 0) seenSent = false
            activity = JSONObject(r.data.toString()).put("items", JSONArray().apply { all.forEach { put(it) } })
            renderActivity()
        }
    }
    private fun markActivitySeen() { Api.invoke("getSecretActivity", JSONObject().put("mark_seen", true)) { } }

    private fun list(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    // ═════════════════ header ═════════════════
    private fun buildHeader() {
        val row = LinearLayout(context)
        val u = headU
        // my photo: a fine white edge and a thin line around it (flat)
        val btn = FrameLayout(context)
        val ring = FrameLayout(context).apply { background = oval(Color.WHITE, 1.5f, Rs.c("#E3B9BC")); setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f)) }
        ring.addView(myPhoto, LayoutParams(-1, -1))
        btn.addView(ring, LayoutParams(dpi(48f * u), dpi(48f * u), Gravity.CENTER))
        btn.addView(myBadge, LayoutParams(-1, -1))
        btn.contentDescription = "My profile"
        btn.press().onTap { openMyProfile(false) }
        headLeft.addView(btn, LayoutParams(dpi(52f * u), dpi(52f * u), Gravity.START or Gravity.CENTER_VERTICAL))
        // the logo right after the photo (owner's picture, 23 Nov): the whole name on every phone — it takes the room that is
        // left between the photo and the credits, and draws itself smaller where that is less than it needs
        headTitle.addView(logo, LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dpi(8f * u) })
        // credits pill — opens the Reconnect wallet
        styleCreditsPill(creditsPill, u)
        val pillBox = FrameLayout(context).apply { addView(creditsPill, LayoutParams(-2, dpi(36f * u), Gravity.CENTER)) }
        pillBox.press().onTap { wallet() }
        // (no bell any more, 26 Nov: My Activity went with the likes — Notifications are on Home itself)
        val right = hbox(context, 2f)
        right.clipChildren = false
        right.addView(pillBox, lp(WRAP, dpi(44f)))
        headRight.addView(right, LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(headLeft); row.addView(headTitle); row.addView(headRight)
        layoutHeader(true)
        col.addView(row, lp(MATCH, WRAP))
    }
    /** the credits pill as in the picture: white, the coins, "9 Credits", a maroon + ([u]: the header's scale on a narrow phone) */
    private fun styleCreditsPill(t: TextView, u: Float = 1f) {
        t.background = rounded(Color.WHITE, 18f, 1f, Rs.LINE)
        t.gravity = Gravity.CENTER_VERTICAL
        t.maxLines = 1
        if (u != 1f) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(14f * u))
        t.setPadding(dpi(9f * u), 0, dpi(5f * u), 0)
        t.compoundDrawablePadding = dpi(5f * u)
        t.setCompoundDrawablesWithIntrinsicBounds(Coins(18f * u), null, PlusChip(Icon(Lucide.Plus, Color.WHITE, 13f * u, 3f), 24f * u), null)
    }
    /**
     * The words on the header's credits pill: "5 Credits" — only the number where the row would otherwise leave the logo
     * less than 18 (a very narrow phone, or a long number): the name must stay whole and readable.
     */
    private fun headerCredits(member: Boolean): String {
        val full = "${SdRules.fmtCredits(creditsNow)} Credits"
        // (not a member yet: the logo stands in the row's middle and makes room for the pill — see layoutHeader)
        if (!member) return full
        val u = headU
        val vw = resources.displayMetrics.widthPixels / Ui.density
        creditsPill.text = full
        creditsPill.measure(0, 0)
        val fixed = 32f + 52f * u + 8f * u + 2f + creditsPill.measuredWidth / Ui.density
        return if (vw - fixed >= LogoView.widthAt(18f) / Ui.density) full else SdRules.fmtCredits(creditsNow)
    }
    /**
     * a member: photo · TrueFind (right after the photo, in the room that is left) · credits;
     * not a member yet: TrueFind in the middle of the row — the photo's side made as wide as the credits pill's side, and
     * the logo fits itself into what is left between them (on a narrow phone a little smaller, never cut, and the pill
     * keeps its whole "0 Credits")
     */
    private var headSide = -1
    private fun layoutHeader(member: Boolean) {
        var side = 0
        if (!member) { creditsPill.measure(0, 0); side = maxOf(dpi(52f * headU), creditsPill.measuredWidth) }
        if (headMember == member && (member || side == headSide)) return
        headMember = member; headSide = side
        headLeft.layoutParams = lp(if (member) WRAP else side, WRAP)
        headTitle.layoutParams = lp(0, WRAP, 1f)
        headRight.layoutParams = lp(if (member) WRAP else side, WRAP)
        (logo.layoutParams as? LayoutParams)?.let {
            it.gravity = if (member) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            it.leftMargin = if (member) dpi(8f * headU) else 0
            logo.layoutParams = it
        }
    }

    /** the little white "+" circle at the end of the credits pill */
    private class PlusChip(val icon: Icon, val sizeDp: Float = 24f) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Rs.M }
        override fun getIntrinsicWidth() = Ui.dpi(sizeDp)
        override fun getIntrinsicHeight() = Ui.dpi(sizeDp)
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; c.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, p)
            val s = Ui.dpi(13f * sizeDp / 24f); icon.setBounds(b.centerX() - s / 2, b.centerY() - s / 2, b.centerX() + s / 2, b.centerY() + s / 2); icon.draw(c)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun buildComplete() {
        completeBtn.orientation = LinearLayout.HORIZONTAL
        completeBtn.gravity = Gravity.CENTER_VERTICAL
        completeBtn.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(18f); setStroke(dpi(1.5f), Rs.c("#F3A7C6")) }
        completeBtn.setPadding(dpi(10f), dpi(10f), dpi(8f), dpi(10f))
        completeBtn.elevation = Ui.dp(2f)
        completeBtn.contentDescription = "Complete your profile"
        val ic = ImageView(context).apply { setImageDrawable(Icon(Lucide.User, Color.WHITE, 22f, 2.2f)); scaleType = ImageView.ScaleType.CENTER; background = K.badge() }
        completeBtn.addView(ic, lp(dpi(42f), dpi(42f), r = 10f))
        val t = vbox(context)
        t.addView(sdText(context, "Complete your profile", 16f, Rs.c("#9A1246")))
        t.addView(completeSub, lp(WRAP, WRAP, t = 3f))
        completeBtn.addView(t, lp(0, WRAP, 1f))
        completeBtn.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, K.wine, 20f, 2.4f)) }, lp(dpi(20f), dpi(20f), l = 10f))
        completeBtn.press().onTap { openMyProfile(true) }
        completeBtn.visibility = GONE
        col.addView(completeBtn, lp(MATCH, WRAP, t = 12f))
    }



    private fun buildPrivate() {
        // owner's picture (23 Nov): four plain rows — an icon, a short title, one clear line
        body.addView(Rs.section(context, "Private by Design"), lp(MATCH, WRAP, t = 22f))
        body.addView(sdText(context, "Your privacy is our most important feature.", 14f, Rs.SUB, bold = false), lp(MATCH, WRAP, l = 13f, t = 3f, b = 10f))
        val points = listOf(
            Triple(Lucide.Lock, "Your number stays private", "Your phone number is never shown or shared."),
            Triple(Lucide.Link2Off, "No links or IDs", "Links, IDs and social handles are blocked in chat."),
            Triple(Lucide.ShieldCheck, "Verified members", "Every feature opens after full verification."),
            Triple(Lucide.MessageCircleMore, "Unlimited chat", "Once a chat is open, talk as much as you like."),
        )
        val rows = vbox(context, 7f)
        for ((ic, title, line) in points) {
            val row = hbox(context, 12f).apply { background = rounded(Color.WHITE, 14f, 1f, Rs.LINE); setPadding(dpi(11f), dpi(9f), dpi(12f), dpi(9f)) }
            row.addView(ImageView(context).apply { setImageDrawable(Icon(ic, Rs.ICON, 20f, 1.9f)); scaleType = ImageView.ScaleType.CENTER; background = rounded(Rs.BG_TOP, 11f) }, lp(dpi(38f), dpi(38f)))
            val t = vbox(context)
            t.addView(sdText(context, title, 15f, Rs.INK))
            t.addView(sdText(context, line, 13f, Rs.SUB, bold = false).lineHeight(1.15f), lp(MATCH, WRAP, t = 1f))
            row.addView(t, lp(0, WRAP, 1f))
            row.contentDescription = "$title. $line"
            rows.addView(row, lp(MATCH, WRAP))
        }
        body.addView(rows, lp(MATCH, WRAP))
    }

    private fun buildToast() {
        toast.orientation = LinearLayout.HORIZONTAL; toast.gravity = Gravity.CENTER_VERTICAL
        toast.background = rounded(Color.WHITE, 18f, 1f, K.line)
        toast.elevation = Ui.dp(90f)
        toast.setPadding(dpi(16f), dpi(12f), dpi(14f), dpi(12f))
        toast.addView(toastText, lp(0, WRAP, 1f))
        toastBtn.background = rounded(K.pill, 19f); toastBtn.gravity = Gravity.CENTER; toastBtn.setPadding(dpi(16f), 0, dpi(16f), 0)
        toast.addView(toastBtn, lp(WRAP, dpi(38f), l = 10f))
        toast.visibility = GONE
    }

    // ═════════════════ render ═════════════════
    // many answers arrive at once: draw once per frame, not once per answer
    private var renderQueued = false
    private fun render() {
        if (renderQueued) return
        renderQueued = true
        post { renderQueued = false; renderNow() }
    }
    private fun renderNow() {
        if (Demo.on) android.util.Log.i("TFDBG", "secret render premium=$premium access=$access map=$mapPremium profile=${myProfile != null} missing=$missing deck=${liveDeck.size} act=${activity != null}")
        // my photo + its badge
        val pid = mine.optString("photo")
        myPhoto.set(listOf(pid))
        myBadge.removeAllViews()
        when {
            profileKnown && missing.isNotEmpty() -> myBadge.addView(sdText(context, missing.size.toString(), 12f, Color.WHITE).apply { gravity = Gravity.CENTER; background = oval(Rs.c("#E3086C"), 2f, Color.WHITE) },
                LayoutParams(dpi(18f), dpi(18f), Gravity.END or Gravity.TOP).apply { topMargin = dpi(1f) })
            premium -> myBadge.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Crown, Color.WHITE, 11f, 2.2f, Color.WHITE)); scaleType = ImageView.ScaleType.CENTER; background = oval(K.hot, 2f, Color.WHITE) },
                LayoutParams(dpi(21f), dpi(21f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -dpi(1f); bottomMargin = dpi(1f) })
            else -> myBadge.addView(View(context).apply { background = oval(K.green, 2.5f, Color.WHITE) }, LayoutParams(dpi(14f), dpi(14f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(2f); bottomMargin = dpi(3f) })
        }
        val creditsFull = "${SdRules.fmtCredits(creditsNow)} Credits"
        creditsPill.text = headerCredits(!needSecret)
        creditsPill.contentDescription = "${creditsNow?.let { "$it credits" } ?: "Credits"}. Open Reconnect wallet"
        renderNotice()
        // complete-profile prompt
        val showComplete = profileKnown && missing.isNotEmpty() && !needSecret
        completeBtn.visibility = if (showComplete) VISIBLE else GONE
        completeSub.text = "Add ${missing.joinToString(", ") { SdRules.NEED_LABEL[it] ?: it }} to unlock Premium features."
        actBox.visibility = if (needSecret) GONE else VISIBLE
        layoutHeader(!needSecret)
        // not a member yet: the Join card instead of Notifications and Private by Design
        body.visibility = if (needSecret) GONE else VISIBLE
        if (needSecret && joinCard == null) {
            joinCard = JoinCard.build(context, "secret") { needSecret = false; render(); loadPeople() }
                .also { col.addView(it, col.indexOfChild(body), lp(MATCH, WRAP, t = 22f)) }
        } else if (!needSecret) joinCard?.let { col.removeView(it); joinCard = null }
        renderActivity()
        for (p in pills) p.text = creditsFull
        if (page == "map") mapPage?.render()
        if (page == "chat") chatPage?.render()
    }

    /** Home in the bottom bar gets a red dot while new messages / profile views wait (MainActivity sets this) */
    var onLikesDot: ((Boolean) -> Unit)? = null
    /** Reconnect Home has just read the credits (MainActivity hands them to the Wallet) */
    var onCredits: ((JSONObject) -> Unit)? = null
    private fun renderNotice() {
        noticeBox.text = notice
        noticeBox.visibility = if (notice.isNotEmpty()) VISIBLE else GONE
    }





    // ── Notifications (who messaged you, viewed your profile) ──
    private var actAll = false
    private fun renderActivity() {
        onLikesDot?.invoke((activity?.let { a -> list(a.optJSONArray("items")).count { it.optString("id") in freshIds } } ?: 0) > 0)
        actBox.removeAllViews()
        if (needSecret) return
        val data = activity
        val items = list(data?.optJSONArray("items"))
        val fresh = items.filter { it.optString("id") in freshIds }
        val older = items.filter { it.optString("id") !in freshIds }
        val all = fresh + older
        val canSee = data?.optBoolean("can_see") == true
        val box = vbox(context).apply { background = rounded(Color.WHITE, 24f, 1f, Rs.argb(41, 214, 40, 120)); setPadding(dpi(10f), dpi(12f), dpi(10f), dpi(10f)); elevation = Ui.dp(4f) }
        // header: bell (the red dot blinks while something is new) · Notifications · how many new
        val head = hbox(context, 10f).apply { setPadding(dpi(4f), 0, dpi(4f), 0) }
        val bell = FrameLayout(context).apply { background = K.badge(); elevation = Ui.dp(3f) }
        bell.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Bell, Color.WHITE, 21f, 2.2f, if (fresh.isNotEmpty()) Color.WHITE else null)); scaleType = ImageView.ScaleType.CENTER }, LayoutParams(-1, -1))
        if (fresh.isNotEmpty()) bell.addView(BlinkDot(context), LayoutParams(dpi(12f), dpi(12f), Gravity.END or Gravity.TOP))
        head.addView(bell, lp(dpi(42f), dpi(42f)))
        val t = vbox(context)
        t.addView(sdText(context, "Notifications", 21f, K.wine, serif = true))
        t.addView(sdText(context, when { data == null -> "Checking…"; fresh.isNotEmpty() -> "${fresh.size} new for you"; items.isNotEmpty() -> "You're all caught up"; else -> "Messages and profile views" },
            13.5f, if (fresh.isNotEmpty()) K.hot else K.sub, bold = fresh.isNotEmpty()), lp(WRAP, WRAP, t = 2f))
        head.addView(t, lp(0, WRAP, 1f))
        if (fresh.isNotEmpty()) head.addView(sdText(context, if (fresh.size > 99) "99+" else fresh.size.toString(), 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = rounded(Rs.c("#FF1F3D"), 14f); setPadding(dpi(9f), 0, dpi(9f), 0); minWidth = dpi(28f)
        }, lp(WRAP, dpi(28f)))
        box.addView(head, lp(MATCH, WRAP))
        when {
            data == null -> { for (i in 0..1) box.addView(View(context).apply { background = rounded(Rs.c("#FCE4F0"), 14f) }, lp(MATCH, dpi(52f), l = 4f, r = 4f, t = if (i == 0) 12f else 8f)) }
            all.isEmpty() -> box.addView(sdText(context, "When someone messages you or views your profile, you'll see it here.", 14f, K.sub, bold = false).apply {
                gravity = Gravity.CENTER; lineHeight(1.2f); background = rounded(K.tint2, 14f); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
            }, lp(MATCH, WRAP, l = 4f, r = 4f, t = 10f, b = 2f))
            else -> {
                val chips = hbox(context, 6f).apply { setPadding(dpi(2f), dpi(12f), dpi(2f), dpi(2f)) }
                fun count(kind: String) = items.filter { it.optString("kind") == kind }.sumOf { if (kind == "message") (it.optInt("unread").takeIf { u -> u > 0 } ?: it.optInt("count").takeIf { c -> c > 0 } ?: 1) else 1 }
                for ((k, l, ic) in listOf(Triple("message", "Message", Lucide.MessageCircleMore), Triple("view", "View", Lucide.Eye))) {
                    val n = count(k)
                    val col = if (n > 0) K.wine else Rs.c("#B8A3AE")
                    // icon + count side by side in the middle of the chip
                    val chip = FrameLayout(context).apply { background = rounded(if (n > 0) K.tint else Rs.c("#FAF5F8"), 15f) }
                    chip.addView(sdText(context, "$n ${if (n == 1) l else "${l}s"}", 13f, col).icon(Icon(ic, col, 14f, 2.4f), 5f).apply {
                        gravity = Gravity.CENTER; maxLines = 1
                    }, LayoutParams(-2, -1, Gravity.CENTER))
                    chips.addView(chip, lp(0, dpi(30f), 1f))
                }
                box.addView(chips, lp(MATCH, WRAP))
                val rows = vbox(context, 2f).apply { setPadding(0, dpi(6f), 0, 0) }
                for (x in if (actAll) all else all.take(3)) rows.addView(activityRow(x, canSee, x.optString("id") in freshIds), lp(MATCH, WRAP))
                box.addView(rows, lp(MATCH, WRAP))
                if (all.size > 3) box.addView(sdText(context, if (actAll) "Show less" else "See all ${all.size}", 15f, K.hot).apply { gravity = Gravity.CENTER }.press().onTap { actAll = !actAll; renderActivity() },
                    lp(MATCH, dpi(38f), t = 2f))
                if (!canSee) {
                    val b = sdText(context, if (girlUnverified) "Verify free to see who" else "See who it is", 16f, Color.WHITE).icon(
                        if (girlUnverified) Icon(Lucide.ShieldCheck, Color.WHITE, 19f, 2.3f) else Icon(Lucide.Crown, Color.WHITE, 18f, 2.2f, Color.WHITE), 8f).apply {
                        gravity = Gravity.CENTER; background = K.btn(24f); elevation = Ui.dp(4f)
                    }
                    box.addView(centerWrap(b).press().onTap { locked("activity") }, lp(MATCH, dpi(48f), t = 6f))
                }
            }
        }
        actBox.addView(box, LayoutParams(-1, -2))
        if (fresh.isNotEmpty()) watchSeen()
    }

    /** a button whose icon + label stay in the middle */
    private fun centerWrap(tv: TextView): View = FrameLayout(context).apply {
        background = tv.background; elevation = tv.elevation; tv.background = null; tv.elevation = 0f
        addView(tv, LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun activityRow(x: JSONObject, canSee: Boolean, isNew: Boolean): View {
        val kind = x.optString("kind").ifEmpty { "message" }
        val p = x.optJSONObject("person")
        val show = canSee && p != null
        val bg = if (kind == "view") K.wine else K.hot
        val ic = if (kind == "view") Lucide.Eye else Lucide.MessageCircleMore
        val text = if (kind == "view") { if (x.optInt("count") > 1) "viewed your profile ${x.optInt("count")} times" else "viewed your profile" }
            else { if (x.optInt("count") > 1) "sent you ${x.optInt("count")} messages" else "sent you a message" }
        val at = SdRules.timeAgo(x.optString("at")).let { if (it == "just now") "Just now" else it }
        val row = hbox(context, 11f).apply {
            setPadding(dpi(8f), dpi(9f), dpi(8f), dpi(9f))
            background = if (isNew) grad(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Rs.c("#FFF0F7"), Color.WHITE), 16f) else null
        }
        val face = FrameLayout(context).apply { clipChildren = false }
        if (show) {
            val ph = RoundPhoto(context, 48f)
            ph.set(SdRules.thumbs(p), Demo.round(p, dpi(48f)))
            val ringB = FrameLayout(context).apply { background = oval(Color.WHITE, 1.5f, Rs.c("#F6C2DA")); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
            ringB.addView(ph, LayoutParams(-1, -1))
            face.addView(ringB, LayoutParams(dpi(50f), dpi(50f), Gravity.CENTER))
        } else face.addView(BlurFace(context, x.optString("g")), LayoutParams(dpi(48f), dpi(48f), Gravity.CENTER))
        face.addView(ImageView(context).apply { setImageDrawable(Icon(ic, Color.WHITE, 11f, 2.6f)); scaleType = ImageView.ScaleType.CENTER; background = oval(bg, 2f, Color.WHITE) },
            LayoutParams(dpi(21f), dpi(21f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -dpi(3f); bottomMargin = -dpi(3f) })
        row.addView(face, lp(dpi(48f), dpi(48f)))
        val mid = vbox(context)
        val line = SpannableStringBuilder()
        if (show) {
            val name = SdRules.firstName(SdRules.s(p, "name")) + (SdRules.s(p, "age").let { if (it.isNotEmpty()) ", $it" else "" })
            line.append(name, ForegroundColorSpan(K.ink), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            line.setSpan(BoldSpan(), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            line.append(if (x.optString("g") == "f") "Someone pretty" else "Someone nice", ForegroundColorSpan(Rs.argb(110, Color.red(K.wine), Color.green(K.wine), Color.blue(K.wine))), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            line.setSpan(BlurSpan(), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        line.append(" $text")
        mid.addView(sdText(context, line, 15f, K.ink2, bold = false).lineHeight(1.1f))
        mid.addView(sdText(context, if (show) listOf(SdRules.s(p, "city"), at).filter { it.isNotEmpty() }.joinToString(" · ") else "Get Premium to see who · $at",
            13f, if (show) K.sub else K.hot, bold = !show).oneLine(), lp(WRAP, WRAP, t = 2f))
        row.addView(mid, lp(0, WRAP, 1f))
        val end = vbox(context, 5f, Gravity.END)
        val unread = x.optInt("unread")
        if (kind == "message" && unread > 0) end.addView(sdText(context, if (unread > 99) "99+" else unread.toString(), 12.5f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = rounded(K.pill, 11f); setPadding(dpi(7f), 0, dpi(7f), 0); minWidth = dpi(22f)
        }, lp(WRAP, dpi(22f)))
        else if (isNew) end.addView(sdText(context, "New", 11.5f, K.hot).apply { background = rounded(K.tint, 10f); setPadding(dpi(8f), dpi(2f), dpi(8f), dpi(2f)) })
        end.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, Rs.c("#D9A2BC"), 17f, 2.4f)) }, lp(dpi(17f), dpi(17f)))
        row.addView(end)
        row.contentDescription = "${if (show) SdRules.firstName(SdRules.s(p, "name")) else "Someone"} $text, $at${if (isNew) ", new" else ""}"
        row.press().onTap { openActivity(x) }
        return row
    }

    private class BoldSpan : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = Ui.bold }
        override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = Ui.bold }
    }
    /** "Someone pretty" written blurred (filter: blur(4.5px)) */
    private class BlurSpan : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = Ui.bold; tp.maskFilter = android.graphics.BlurMaskFilter(Ui.dp(4.5f), android.graphics.BlurMaskFilter.Blur.NORMAL) }
        override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = Ui.bold }
    }

    /** the red dot on the bell (blinks while something is new) */
    private class BlinkDot(ctx: Context) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val a = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 1100; repeatCount = -1; interpolator = android.view.animation.LinearInterpolator(); addUpdateListener { t = it.animatedValue as Float; invalidate() } }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); a.start() }
        override fun onDetachedFromWindow() { a.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(c: android.graphics.Canvas) {
            val r = width / 2f
            val ph = if (t < .6f) t / .6f else (1f - t) / .4f      // 0 → 1 (at 60 %) → 0
            p.color = Rs.argb(((1f - ph) * 0.7f * 255).toInt(), 255, 31, 61); c.drawCircle(r, r, r * (1f + 1.17f * ph), p)
            p.color = Color.WHITE; c.drawCircle(r, r, r, p)
            p.color = Rs.argb(((1f - .45f * ph) * 255).toInt(), 255, 31, 61); c.drawCircle(r, r, r - Ui.dp(2f), p)
        }
    }

    /** blurred face: soft pink / lilac circle with a blurred person shape and a lock */
    private class BlurFace(ctx: Context, val g: String) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val lock = Icon(Lucide.Lock, Color.WHITE, 14f, 2.6f)
        override fun onDraw(c: android.graphics.Canvas) {
            val s = width.toFloat()
            val tone = if (g == "f") intArrayOf(Rs.c("#FF9CC6"), Rs.c("#E3086C")) else intArrayOf(Rs.c("#C9A6F5"), Rs.c("#8E1243"))
            c.save(); c.clipPath(android.graphics.Path().apply { addCircle(s / 2, s / 2, s / 2, android.graphics.Path.Direction.CW) })
            p.shader = android.graphics.LinearGradient(0f, 0f, s, s, tone[0], tone[1], android.graphics.Shader.TileMode.CLAMP); c.drawRect(0f, 0f, s, s, p); p.shader = null
            p.maskFilter = android.graphics.BlurMaskFilter(s * 3.4f / 48f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            val k = s / 48f
            c.save(); c.scale(1.08f, 1.08f, s / 2, s / 2)
            p.color = Rs.argb(217, 255, 255, 255); c.drawCircle(24 * k, 19 * k, 9 * k, p)
            c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData("M7 48c1.5-10 8.6-15.5 17-15.5S39.5 38 41 48z").apply { transform(android.graphics.Matrix().apply { setScale(k, k) }) }, p.apply { color = Rs.argb(204, 255, 255, 255) })
            p.color = Rs.argb(89, 255, 255, 255); c.drawCircle(12 * k, 10 * k, 7 * k, p)
            c.restore(); p.maskFilter = null; c.restore()
            val ls = (s * 0.3f).toInt(); lock.setBounds((s / 2).toInt() - ls / 2, (s / 2).toInt() - ls / 2, (s / 2).toInt() + ls / 2, (s / 2).toInt() + ls / 2); lock.draw(c)
        }
    }

    /** the box was on screen (Home) for a moment: what is new counts as seen */
    private val seenCheck = Runnable {
        if (seenSent || !shown || page != "home") return@Runnable
        val r = Rect()
        if (actBox.isShown && actBox.getGlobalVisibleRect(r) && r.height() >= actBox.height * 0.4f) { seenSent = true; markActivitySeen() }
    }
    private fun watchSeen() {
        if (seenSent) return
        main.removeCallbacks(seenCheck)
        main.postDelayed(seenCheck, 1200)
    }

    /** a credits pill made for a page that has closed (its number no longer needs keeping up to date) */
    fun forgetPill(wrapper: View) { (wrapper as? android.view.ViewGroup)?.getChildAt(0)?.let { pills.remove(it) } }
    fun openChatId(id: String) { layer.closeAll(); go("/chat/$id", JSONObject().put("openChat", 1)) }



    // ═════════════════ actions (same rules as the web) ═════════════════
    fun wallet() = go("/wallet?app=secret", null)
    /** True Find's Settings (Profile's drawer, the very same screens); Back comes back to Reconnect */
    fun openSettings() = go("/profile?open=settings", null)

    /** Get Premium: the profile must be complete first (otherwise Premium couldn't be used) */
    fun unlock() {
        layer.closeAll()
        if (profileKnown && missing.isNotEmpty()) { SdSheets.need(this, missing) { openMyProfile(true) }; return }
        if (girlUnverified) { verifySheet(); return }
        wallet()
    }
    /** a Premium feature was tapped: complete the profile first, then Go Premium (girls: verify first — then it's free) */
    fun locked(what: String) {
        if (profileKnown && missing.isNotEmpty()) { SdSheets.need(this, missing) { layer.closeAll(); openMyProfile(true) }; return }
        if (girlUnverified) { verifySheet(); return }
        SdSheets.premium(this, what, freeGirl, intro) { unlock() }
    }
    private fun verifySheet() {
        val st = if (myCreator == "pending" || myCreator == "rejected") myCreator else "none"
        SdSheets.verify(this, st) { layer.closeAll(); go("/profile?open=creator", null) }
    }

    fun openProfile(p: JSONObject): (JSONObject) -> Unit {
        val withOnline = JSONObject(p.toString()).put("_online", p.optBoolean("_online") || p.optString("id") in onlineIds || SdRules.isOnline(p))
        chatIdFor(p).let { if (it.isNotEmpty()) withOnline.put("_chatId", it) }
        val id = withOnline.optString("id")
        if (id.isNotEmpty() && viewed.add(id) && !id.startsWith("demo")) Api.invoke("recordProfileView", JSONObject().put("profile_id", id)) { }
        return SdProfileView.open(this, withOnline)
    }
    // notifications row: with a plan it opens the profile / the chat; without, Go Premium (girls: verify)
    private fun openActivity(x: JSONObject) {
        val p = x.optJSONObject("person")
        if (activity?.optBoolean("can_see") != true || p == null) { locked("activity"); return }
        if (x.optString("kind") == "message" && x.optString("match_id").isNotEmpty() && !x.isNull("match_id")) { go("/chat/${x.optString("match_id")}", JSONObject().put("openChat", 1)); return }
        openProfile(p)
    }

    // ── for the map and chat pages ──
    val onlineNow: List<JSONObject>? get() = online
    val meNow: JSONObject? get() = me
    val mineNow: JSONObject get() = mine
    val noticeNow: String get() = notice
    val myIdNow: String get() = myId
    val verifyFirst: Boolean get() = girlUnverified
    val hostRoot: FrameLayout get() = host
    fun clearNotice() { notice = ""; renderNotice(); render() }
    fun goHome() = go("/secret-app", null)
    /** the same credits pill as the Home header (opens the Reconnect wallet) */
    fun newCreditsPill(): View {
        val t = sdText(context, "${SdRules.fmtCredits(creditsNow)} Credits", 14f, Rs.INK).apply { styleCreditsPill(this) }
        pills.add(t)
        return FrameLayout(context).apply { addView(t, LayoutParams(-2, dpi(36f), Gravity.CENTER)); contentDescription = creditsPill.contentDescription }.also { it.press().onTap { wallet() } }
    }
    /** the top of a tab's page (owner's picture, 23 Nov): the logo, [end] at the right (credits / search), then the page's name */
    fun pageHeader(title: String, end: View? = null): View {
        val box = vbox(context)
        val row = hbox(context)
        row.addView(Rs.logo(context, 23f), lp(WRAP, WRAP))
        row.addView(View(context), lp(0, 1, 1f))
        row.addView(end ?: newCreditsPill(), lp(WRAP, dpi(44f)))
        box.addView(row, lp(MATCH, WRAP))
        box.addView(Rs.title(context, title, 29f, Rs.M_INK), lp(WRAP, WRAP, t = 6f))
        return box
    }
    /** one quiet line under a page's name */
    fun pageSub(text: String): View = sdText(context, text, 14f, Rs.SUB, bold = false)
    /** "Premium · 2d left" (tap: wallet) — null without a running plan */
    fun planPill(): View? {
        val d = daysLeft
        if (!(premiumKnown && premium && d != null)) return null
        val renew = d == "Renew"
        return sdText(context, if (renew) "Premium ended · Renew" else "Premium · $d", 13f, if (renew) Rs.c("#B4183C") else K.wine)
            .icon(Icon(Lucide.Hourglass, if (renew) Rs.c("#B4183C") else K.wine, 13f), 5f).apply {
                gravity = Gravity.CENTER_VERTICAL; background = rounded(if (renew) Rs.c("#FFE4E6") else Color.WHITE, 14f); setPadding(dpi(10f), 0, dpi(10f), 0)
            }.also { it.press().onTap { wallet() } }
    }
    /** the chat was pinned / unpinned (ChatRoom's ⋮ menu) */
    fun chatPinned(id: String, on: Boolean) { chatPage?.setPinned(id, on) }
    /**
     * Chat list: a tap anywhere on a row — the photo too — opens the chat (a locked chat opens the plans straight away).
     * The owner, 23 Nov: "चैट पे दबाएंगे तो चैट ओपन होनी चाहिए" — the photo used to open the profile first; the profile is
     * one tap away inside the chat (its header).
     */
    fun openChatRow(c: SdChatsPage.Chat) {
        if (c.demo) { openDemoChat(c.id); return }
        if (premiumKnown && !canUse && !(c.open || chatOpen(c.id))) { unlock(); return }
        chatPage?.markRead(c.id)
        go("/chat/${c.id}", JSONObject().put("openChat", 1))
    }
    /** a demo person's chat (testing only, SdDemoChat): it opens at once — no plan, no credit, nothing sent anywhere */
    private fun openDemoChat(id: String) {
        if (id.isEmpty()) return
        SdDemoChat.viewer = myGender
        SdDemoChat.start(id)
        chatPage?.markRead(id)
        layer.closeAll()
        go("/chat/$id", JSONObject().put("openChat", 1))
    }

    // ── for the profile page and the sheets ──
    val isPremium get() = canUse
    val mayRequest get() = canRequest
    val whyLocked get() = lockWhy
    val creditsShown get() = creditsNow
    val until get() = untilText
    fun isSameGender(p: JSONObject) = sameGender(p)
    fun inviteOf(id: String) = inviteState[id] ?: ""
    val myProfileNow get() = myProfile ?: JSONObject()
    val gender get() = myGender
    val premiumInfo get() = Triple(premium, premiumKnown, premiumUntil)
    val days get() = daysLeft

    /** "Send Request" (card or profile) */
    fun askRequest(p: JSONObject) {
        // a demo profile (testing only): its chat opens at once (owner, 23 Nov: "दबाते ही सीधा chat खुले") — no request is
        // sent anywhere and no credit is used
        if (SdDemo.isDemo(p)) { if (!sameGender(p)) openDemoChat(SdDemoChat.idFor(p)); return }
        if (sameGender(p)) return
        val chat = chatIdFor(p)
        if (chat.isNotEmpty()) { if (canUse || chatOpen(chat)) { layer.closeAll(); go("/chat/$chat", JSONObject().put("openChat", 1)) } else locked("chat"); return }
        if (!canRequest) { locked("request"); return }
        val st = inviteState[p.optString("id")]
        if (st == "sent") { if (!layer.open) openProfile(p); return }
        if (st == "sending") return
        SdSheets.request(this, p)
    }

    fun sendRequest(p: JSONObject, sheetDone: () -> Unit, refresh: () -> Unit) {
        val id = p.optString("id")
        if (!canRequest) { sheetDone(); locked("request"); return }
        if (inviteState[id] == "sending") return
        inviteState[id] = "sending"; refresh()
        sendInvitationPatiently(JSONObject().put("to_profile_id", id), 0) { r ->
            sheetDone()
            val d = r.data
            if (r.ok) {
                if (d.has("credits_left") && !d.isNull("credits_left")) credits = d.optInt("credits_left")
                else if (d.optBoolean("ok") && !d.optBoolean("free")) credits = credits?.let { maxOf(0, it - 1) }
                val match = d.optString("match_id")
                if (match.isNotEmpty() && !d.isNull("match_id")) {
                    inviteState.remove(id)
                    chatWith[id] = match
                    SdRules.s(p, "user_id", "created_by_id").let { if (it.isNotEmpty()) chatWith[it] = match }
                    layer.closeAll(); render(); go("/chat/$match", JSONObject().put("openChat", 1)); return@sendInvitationPatiently
                }
                inviteState[id] = "sent"
                say("Request sent to ${SdRules.firstName(SdRules.s(p, "name")).ifEmpty { "them" }} ✓")
            } else {
                inviteState.remove(id)
                val err = d.optString("error")
                when {
                    err == "verify_first" -> verifySheet()
                    err == "opposite_gender_only" -> say(d.optString("message").ifEmpty { "Requests can only be sent to the opposite gender." }, "err")
                    err == "no_credits" -> { credits = 0; say(d.optString("message").ifEmpty { "No credits left. Credits come with a Reconnect plan." }, "credits") }
                    err == "busy" || r.status == 0 || r.status >= 500 -> say(if (r.status == 0) "No connection. Check your internet and try again." else "The server is busy for a moment. Please try again.", "err")
                    r.status == 402 || err == "premium_required" -> say(if (freeGirl) "Requests need credits — they come with a plan." else "Sending requests is for Premium members.", "premium")
                    r.status == 403 -> say(d.optString("message").ifEmpty { err.ifEmpty { "You can't send a request to this person." } }, "err")
                    else -> say(err.ifEmpty { r.error ?: "Could not send the request." }, "err")
                }
            }
            render(); refresh()
        }
    }

    /**
     * sendInvitation, asked again (up to twice, 1.5 s and 4 s later) when the answer was a passing failure — no answer,
     * the server busy for a moment, or failing for a moment. Safe: when the first try did go through, the next one finds
     * the chat it made (no second credit) and opens it.
     */
    private fun sendInvitationPatiently(body: JSONObject, attempt: Int, done: (Api.Result) -> Unit) {
        Api.invoke("sendInvitation", body) { r ->
            val passing = !r.ok && (r.status == 0 || r.status == 408 || r.status in 500..599 ||
                (r.status == 429 && (r.data.optString("error") == "busy" || r.data.optBoolean("server_busy"))))
            if (passing && attempt < 2 && shown) main.postDelayed({ sendInvitationPatiently(body, attempt + 1, done) }, if (attempt == 0) 1500L else 4000L)
            else done(r)
        }
    }

    fun moderate(p: JSONObject, action: String) {
        if (SdDemo.isDemo(p)) { say("This is a demo profile."); return }
        Api.invoke("moderateUser", JSONObject().put("profile_id", p.optString("id")).put("action", action)) { r ->
            if (r.ok) {
                val id = p.optString("id")
                online = online?.filter { it.optString("id") != id }; discover = discover?.filter { it.optString("id") != id }
                layer.closeAll()
                say(if (action == "block") "Blocked. You won't see each other anymore." else "Report sent. Our team will review it.")
                render()
            } else say(r.data.optString("error").ifEmpty { r.error ?: "Action failed." }, "err")
        }
    }


    fun openChat(p: JSONObject) { val id = p.optString("_chatId"); layer.closeAll(); go("/chat/$id", JSONObject().put("openChat", 1)) }


    fun openMyProfile(edit: Boolean) { SdMyProfile.open(this, edit) }
    fun profileSaved(next: JSONObject) {
        myProfile = next
        mine.put("name", SdRules.s(next, "full_name", "name").ifEmpty { mine.optString("name") }).put("city", SdRules.s(next, "city")).put("state", SdRules.s(next, "state"))
        SdRules.photoOf(next).let { if (it.isNotEmpty()) mine.put("photo", it) }
        render()
    }
    fun exit() { layer.closeAll(); go("/", null) }
    fun verify() { layer.closeAll(); go("/profile?open=creator", null) }
    fun changePhoto() { layer.closeAll(); go("/profile", null) }
    fun showWallet() { layer.closeAll(); wallet() }

    /** test mode only: open one pop-up so the emulator can photograph it */
    fun previewOpen(what: String, tries: Int = 0) {
        if (page != "home") { previewPage(what, tries); return }
        // wait until the made-up data is there (and drawn)
        if ((liveDeck.isEmpty() || myProfile == null || activity == null || renderQueued) && tries < 20) { postDelayed({ previewOpen(what, tries + 1) }, 300); return }
        // and laid out once more before opening anything on top
        if (tries >= 0) { postDelayed({ previewOpen(what, -1) }, 900); return }
        val first = liveDeck.firstOrNull() ?: online?.firstOrNull() ?: return
        if (Demo.on) android.util.Log.i("TFDBG", "preview open $what")
        when (what) {
            "profile" -> openProfile(first)
            "request" -> SdSheets.request(this, first)
            "premium" -> SdSheets.premium(this, "request", false, intro) { }
            "need" -> SdSheets.need(this, listOf("age", "city")) { }
            "verify" -> SdSheets.verify(this, "none") { }
            "my" -> openMyProfile(false)
            "myedit" -> openMyProfile(true)
            "toast" -> say("Request sent to ${SdRules.firstName(SdRules.s(first, "name"))} ✓")
            "pop" -> showPop(list(activity?.optJSONArray("items")).take(1))
            "scroll" -> scroll.scrollTo(0, scroll.getChildAt(0).height)
            "scrollmid" -> scroll.scrollTo(0, scroll.getChildAt(0).height / 3)
            "demo-profile" -> online?.firstOrNull { SdDemo.isDemo(it) && SdRules.liveOf(it) }?.let { openProfile(it) }
            "demo-profile-new" -> online?.filter { SdDemo.isDemo(it) }?.getOrNull(8)?.let { openProfile(it) }
            "demo-request" -> online?.firstOrNull { SdDemo.isDemo(it) }?.let { askRequest(it) }
            // (a demo person outside the chat list's five: Send Request starts a new, empty chat)
            "demo-request-new" -> online?.filter { SdDemo.isDemo(it) }?.getOrNull(8)?.let { askRequest(it) }
            "profileend" -> { openProfile(first); postDelayed({ lastScroll(layer.root)?.let { it.scrollTo(0, it.getChildAt(0).height) } }, 1200) }
        }
    }

    /** test shots on the map and chat pages */
    private fun previewPage(what: String, tries: Int) {
        val ready = if (page == "map") online != null && SdMapData.ready else chatPage?.chats != null && myProfile != null
        if (!ready && tries < 40) { postDelayed({ previewPage(what, tries + 1) }, 300); return }
        if (tries >= 0) { postDelayed({ previewPage(what, -1) }, 900); return }
        when {
            what == "profile" -> (online?.firstOrNull { SdRules.liveOf(it) } ?: online?.firstOrNull())?.let { openProfile(it) }
            // (a chat row opens the chat itself since 23 Nov — the first real one · the first demo one)
            what == "chatrow" -> chatPage?.chats?.firstOrNull()?.let { openChatRow(it) }
            what == "demochat" -> chatPage?.chats?.firstOrNull { it.demo }?.let { openChatRow(it) }
            what == "premium" -> locked("chat")
            page == "map" -> mapPage?.preview(what)
            else -> chatPage?.preview(what)
        }
        android.util.Log.i("TFDBG", "preview open $what")
    }

    private fun lastScroll(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is android.view.ViewGroup) for (k in v.childCount - 1 downTo 0) lastScroll(v.getChildAt(k))?.let { return it }
        return null
    }

    // ── toast (bottom) and the drop-in card for new notifications (top) ──
    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(200).withEndAction { toast.visibility = GONE }.start() }
    fun say(text: String, kind: String = "ok") {
        if (toast.parent == null) host.addView(toast, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dpi(16f), 0, dpi(16f), 0) })
        // 12 above the bottom of this page (the Reconnect bar sits right under it)
        val bottomGap = host.height - host.paddingBottom - (bottom - dpi(12f))
        (toast.layoutParams as FrameLayout.LayoutParams).bottomMargin = maxOf(dpi(12f), bottomGap)
        toastText.text = text
        toastText.setTextColor(if (kind == "err") Rs.c("#B4183C") else K.ink2)
        toastBtn.visibility = if (kind == "credits" || kind == "premium") VISIBLE else GONE
        toastBtn.text = if (kind == "credits" || freeGirl) "Get a Plan" else "Get Premium"
        toastBtn.setOnClickListener { main.removeCallbacks(hideToast); toast.visibility = GONE; unlock() }
        toast.visibility = VISIBLE; toast.alpha = 0f; toast.animate().alpha(1f).setDuration(250).start()
        main.removeCallbacks(hideToast); main.postDelayed(hideToast, 4200)
    }

    private fun showPop(items: List<JSONObject>) {
        val x = items[0]
        val canSee = activity?.optBoolean("can_see") == true
        val p = if (canSee) x.optJSONObject("person") else null
        val kind = x.optString("kind")
        val text = if (kind == "view") "viewed your profile" else if (x.optInt("count") > 1) "sent you ${x.optInt("count")} messages" else "sent you a message"
        val title = if (items.size > 1) "${items.size} new notifications" else "${p?.let { SdRules.firstName(SdRules.s(it, "name")) } ?: "Someone"} $text"
        val card = hbox(context, 11f).apply { background = rounded(Color.WHITE, 20f, 1f, Rs.argb(46, 214, 40, 120)); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f)); elevation = Ui.dp(95f) }
        val face: View = if (p != null) RoundPhoto(context, 44f).also { it.set(SdRules.thumbs(p)) } else BlurFace(context, x.optString("g"))
        card.addView(face, lp(dpi(44f), dpi(44f)))
        val t = vbox(context); t.addView(sdText(context, title, 15.5f, K.ink).oneLine()); t.addView(sdText(context, if (canSee) "Tap to see" else "Tap to see who", 13f, K.hot))
        card.addView(t, lp(0, WRAP, 1f))
        card.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Bell, K.hot, 20f, 2.3f)) }, lp(dpi(20f), dpi(20f)))
        host.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(12f), dpi(10f), dpi(12f), 0) })
        card.translationY = -dpi(120f).toFloat(); card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)).start()
        val close = Runnable { card.animate().alpha(0f).setDuration(200).withEndAction { host.removeView(card) }.start() }
        card.setOnClickListener { main.removeCallbacks(close); host.removeView(card); if (items.size == 1) openActivity(x) else scroll.smoothScrollTo(0, 0) }
        main.postDelayed(close, 4500)
    }
}

/** Reconnect: is this chat open for me without a running plan? — the same rule as the server's shared/chatOpen.ts */
object SdChatOpen {
    /** chats made before this moment (1 Dec 2026, UTC): the one who sent the request counts as having opened them */
    const val RULE_FROM = 1796083200000L
    fun isOpen(m: JSONObject, me: String): Boolean {
        if (me.isEmpty()) return false
        if (m.optString("app").ifEmpty { "secret" } != "secret") return false
        val a = m.optString("user_a_id"); val b = m.optString("user_b_id")
        if (a != me && b != me) return false
        val of = m.optJSONArray("open_for")
        if (of != null && (0 until of.length()).any { of.optString(it) == me }) return true
        val made = SdRules.ms(m.optString("created_date"))
        return made in 1 until RULE_FROM && a == me && m.optString("call_free_for") != me
    }
    fun openIds(items: org.json.JSONArray, me: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until items.length()) { val m = items.optJSONObject(i) ?: continue; if (m.optString("id").isNotEmpty() && isOpen(m, me)) out.add(m.optString("id")) }
        return out
    }
}

/** My Reconnect chats by the other person's user id and profile id (newest first wins) — and the lookup for a profile. */
object SdChatIndex {
    fun build(items: org.json.JSONArray, me: String): HashMap<String, String> {
        val out = HashMap<String, String>()
        for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val id = m.optString("id"); if (id.isEmpty()) continue
            val a = m.optString("user_a_id"); val b = m.optString("user_b_id")
            if (a != me && b != me) continue                                   // not mine
            val hid = m.optJSONArray("hidden_for")
            if (hid != null && (0 until hid.length()).any { hid.optString(it) == me }) continue   // "deleted for me"
            val aMe = a == me
            val uid = if (aMe) b else a; val pid = m.optString(if (aMe) "profile_b_id" else "profile_a_id")
            if (uid.isNotEmpty()) out.putIfAbsent(uid, id)
            if (pid.isNotEmpty()) out.putIfAbsent(pid, id)
        }
        return out
    }
    fun find(index: Map<String, String>, p: JSONObject): String = p.optString("_chatId").ifEmpty {
        listOf("id", "user_id", "created_by_id").firstNotNullOfOrNull { k -> p.optString(k).takeIf { it.isNotEmpty() }?.let { index[it] } } ?: ""
    }
}
