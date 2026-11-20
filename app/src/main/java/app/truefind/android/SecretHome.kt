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
 * SECRET DATING · Home — native version of the "home" view of src/pages/SecretApp.jsx.
 * Same data (getDiscoverProfiles, getMapProfiles, getRequestCredits, getStories,
 * getSecretActivity, UserProfile, PremiumAccess), same rules, same look.
 * The Secret Dating bottom bar (Online Map · Home · Chat) is the app's own too (SdNavBar): these pages
 * end where it starts. A member who has not joined Secret Dating yet gets the Join card here (JoinCard).
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
    private var storiesState = "loading"
    private var storiesMine: JSONObject? = null
    private var storiesAll: List<JSONObject> = emptyList()
    private var activity: JSONObject? = null
    private val freshIds = LinkedHashSet<String>()
    private var actKnown: Set<String>? = null
    private val inviteState = HashMap<String, String>()
    /**
     * My Secret Dating chats, by the other person's profile id and user id (owner, 20 Nov: after a request was
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
    /** everything under "Complete your profile": Online Now, the cards, Stories, Private by Design */
    private val body = vbox(ctx)
    /** not a member of Secret Dating yet: the Join card takes the body's place (JoinAppCard.jsx) */
    private var joinCard: View? = null
    private val myPhoto = RoundPhoto(ctx, 43f)
    private val myBadge = FrameLayout(ctx)
    private val creditsPill = sdText(ctx, "", 13f, Color.WHITE)
    private val pills = ArrayList<TextView>()                 // the same pill on the map and chat pages
    /** which Secret Dating page: "home" (/secret-app) · "map" (/dating) · "chat" (/secret-app?tab=chat) */
    var page = "home"; private set
    private var mapPage: SdMapPage? = null
    private var chatPage: SdChatsPage? = null
    private val noticeBox = sdText(ctx, "", 15f, Ui.c("#166534"), bold = false)
    private val actBox = FrameLayout(ctx)
    private val completeBtn = LinearLayout(ctx)
    private val completeSub = sdText(ctx, "", 13.5f, Ui.c("#8E2A55"), bold = false)
    private val onlineRow = hbox(ctx, 8f, Gravity.CENTER)
    private val deckBox = FrameLayout(ctx)
    private val deck = SdDeck(ctx, onOpen = { openProfile(it) }, onChat = { askRequest(it) })
    private val storyRow = LinearLayout(ctx)
    private val storyLock = LinearLayout(ctx)
    private val storyLockTitle = sdText(ctx, "", 17f, Ui.c("#9A1246"))
    private val storyLockSub = sdText(ctx, "", 14f, Ui.c("#8E2A55"), bold = false)
    private val storyLockIcon = ImageView(ctx)
    private val addLine = sdText(ctx, "You can post a story every day to find your true friend.", 15.5f, K.deep)
    private val toast = LinearLayout(ctx)
    private val toastText = sdText(ctx, "", 15f, K.ink2, bold = false)
    private val toastBtn = sdText(ctx, "", 14f, Color.WHITE)

    init {
        SdRules.load(ctx)
        clipChildren = false
        // nothing of these pages may be drawn below their bottom edge: the Secret Dating bar is there
        outlineProvider = android.view.ViewOutlineProvider.BOUNDS
        clipToOutline = true
        addView(PinkBackdrop(ctx), LayoutParams(-1, -1))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        addView(scroll, LayoutParams(-1, -1))
        col.setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(40f))
        col.clipChildren = false
        scroll.addView(col, LayoutParams(-1, -2))
        // the page fades softly into the bottom bar instead of being cut off under it
        addView(object : View(ctx) {
            val p = android.graphics.Paint()
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.argb(0, 255, 255, 255), Color.WHITE), null, android.graphics.Shader.TileMode.CLAMP)
            }
            override fun onDraw(c: android.graphics.Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
        }, LayoutParams(-1, dpi(30f), Gravity.BOTTOM))

        buildHeader()
        buildTitle()
        noticeBox.background = rounded(Ui.c("#F0FDF4"), 14f, 1f, Color.argb(89, 22, 163, 74))
        noticeBox.setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(11f))
        noticeBox.visibility = GONE
        noticeBox.setOnClickListener { notice = ""; noticeBox.visibility = GONE }
        col.addView(noticeBox, lp(MATCH, WRAP, t = 12f))
        col.addView(actBox, lp(MATCH, WRAP, t = 14f))
        buildComplete()
        body.clipChildren = false
        col.addView(body, lp(MATCH, WRAP))
        body.addView(onlineRow, lp(MATCH, WRAP, t = 12f))
        deckBox.clipChildren = false
        body.addView(deckBox, lp(MATCH, WRAP, l = -16f, r = -16f, t = 10f))
        buildStories()
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
    private val storyList: List<JSONObject> get() {
        val want = if (myGender == "m") "f" else if (myGender == "f") "m" else ""
        val known = HashMap<String, JSONObject>()
        for (p in (online ?: emptyList()) + (discover ?: emptyList())) known[p.optString("id")] = p
        val list = if (want.isEmpty()) storiesAll else storiesAll.filter { x -> val g = SdRules.sexOf(x).ifEmpty { SdRules.sexOf(known[x.optString("profile_id")]) }; g.isEmpty() || g == want }
        val myCity = SdRules.s(me, "city").ifEmpty { SdRules.s(mine, "city") }.trim().lowercase()
        val here = if (me != null) SdRules.geoOf(SdRules.s(me, "city"), SdRules.s(me, "state")) else SdRules.geoOf(SdRules.s(mine, "city"), SdRules.s(mine, "state"))
        val withKm = list.map { x ->
            val who = known[x.optString("profile_id")]
            val city = SdRules.s(x, "city").ifEmpty { SdRules.s(who, "city") }; val state = SdRules.s(x, "state").ifEmpty { SdRules.s(who, "state") }
            val km = SdRules.kmBetween(here, SdRules.geoOf(city, state))
            val sameCity = myCity.isNotEmpty() && city.trim().lowercase() == myCity
            JSONObject(x.toString()).put("city", city).put("_km", if (sameCity) 0.0 else km)
        }.filter { it.optDouble("_km", 0.0) <= 500.0 }
        return withKm.sortedWith(compareBy<JSONObject> { if (it.optBoolean("seen")) 1 else 0 }.thenBy { it.optDouble("_km", 1e9) })
    }
    private fun storyOf(id: String): JSONObject? = storyList.firstOrNull { it.optString("profile_id") == id }

    // ═════════════════ loading ═════════════════
    private var lastSearch: String? = null
    fun setShown(on: Boolean, view: String = "home", search: String = "") {
        if (on && search != lastSearch) { lastSearch = search; parseNotice(search) }
        if (!on) lastSearch = null
        if (on && view != page) {
            // another Secret Dating page: its own content (pop-ups of the last one close, as on the web)
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
            val order = listOf("map", "home", "chat")
            PageFx.run(this, PageFx.Kind.TAB, before, after, dir = if (order.indexOf(view) > order.indexOf(from)) 1 else -1,
                onEnd = { pageView(page)?.let { cur -> listOf(scroll, mapPage, chatPage).forEach { v -> if (v != null && v !== cur) v.visibility = GONE } } })
        }
    }
    private fun switchNow(view: String) {
        page = view
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
    private fun restartPoll() { main.removeCallbacks(poll); main.postDelayed(poll, 45_000) }
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
            // the web: every 45 s while the page is on screen — not the people on the Chats page
            if (shown) { if (page != "chat") loadPeople(); loadStories(); loadActivity() }
            main.postDelayed(this, 45_000)
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
            q.getQueryParameter("premium") == "success" -> "Payment received — Secret Dating Premium is active!"
            q.getQueryParameter("premium") == "cancelled" -> "Payment cancelled. You can unlock anytime."
            q.getQueryParameter("credits") == "success" -> "Payment received — request credits added!"
            q.getQueryParameter("credits") == "cancelled" -> "Payment cancelled. You can recharge anytime."
            else -> notice
        }
        if (q.getQueryParameter("activity") != null) main.postDelayed({ scroll.smoothScrollTo(0, maxOf(0, actBox.top - dpi(80f))) }, 500)
        renderNotice()
    }

    fun loadAll(done: (() -> Unit)? = null) {
        if (!Api.ready) { main.postDelayed({ if (shown) loadAll(done) else done?.invoke() }, 700); return }
        // a plan remembered on this phone counts until the server answers (it is only what's SHOWN)
        if (access == null) prefs.getString("sd_plan_until", "")?.let { u -> if (u.isNotEmpty() && SdRules.ms(u) > System.currentTimeMillis()) access = true to u }
        var left = 4
        val one: () -> Unit = { left -= 1; if (left == 0) done?.invoke() }
        loadPeople(one); loadStories(one); loadActivity(one); loadMyChats(one)
    }

    // ── pull down to refresh (owner, 19 Nov evening): the home page and the chats, in Secret Dating's own pink ──
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
                online = list(r.data.optJSONArray("profiles")).map { p -> p.put("_online", p.opt("online") == true || (!p.has("online") && SdRules.isOnline(p))) }
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

    private fun loadStories(done: (() -> Unit)? = null) {
        Api.invoke("getStories") { r ->
            done?.invoke()
            if (r.ok) {
                storiesState = "ready"
                storiesMine = r.data.optJSONObject("mine")
                storiesAll = list(r.data.optJSONArray("stories"))
            } else {
                storiesState = if (r.status == 404 || Regex("not.?found", RegexOption.IGNORE_CASE).containsMatchIn(r.error ?: "")) "missing" else if (storiesState == "ready") "ready" else "error"
            }
            renderStories()
        }
    }

    private fun loadActivity(done: (() -> Unit)? = null) {
        Api.invoke("getSecretActivity") { r ->
            done?.invoke()
            val items = r.data.optJSONArray("items")
            if (!r.ok || items == null) { if (activity == null) { activity = JSONObject().put("items", JSONArray()).put("counts", JSONObject()).put("can_see", false); renderActivity() }; return@invoke }
            val all = list(items)
            all.filter { it.optBoolean("fresh") }.forEach { freshIds.add(it.optString("id")) }
            val known = actKnown
            if (known != null) {
                val added = all.filter { it.optBoolean("fresh") && it.optString("id") !in known }
                if (added.isNotEmpty() && shown && page == "home") showPop(added)
            }
            actKnown = all.map { it.optString("id") }.toSet()
            if ((r.data.optJSONObject("counts")?.optInt("total") ?: 0) > 0) seenSent = false
            activity = r.data
            renderActivity()
        }
    }
    private fun markActivitySeen() { Api.invoke("getSecretActivity", JSONObject().put("mark_seen", true)) { } }

    private fun list(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    // ═════════════════ header ═════════════════
    private fun buildHeader() {
        val row = FrameLayout(context)
        // my photo: flat two-tone ring, a fine white gap, then the photo
        val btn = FrameLayout(context)
        val ring = FrameLayout(context).apply {
            background = RingDrawable(intArrayOf(Ui.c("#FF7DBB"), Ui.c("#E3086C"), Ui.c("#8E1243"), Ui.c("#FF9CCB"), Ui.c("#FF7DBB")), floatArrayOf(0f, .35f, .65f, .85f, 1f))
            setPadding(dpi(2.5f), dpi(2.5f), dpi(2.5f), dpi(2.5f))
        }
        val gapW = FrameLayout(context).apply { background = oval(Color.WHITE); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
        gapW.addView(myPhoto, LayoutParams(-1, -1))
        ring.addView(gapW, LayoutParams(-1, -1))
        btn.addView(ring, LayoutParams(dpi(52f), dpi(52f), Gravity.CENTER))
        btn.addView(myBadge, LayoutParams(-1, -1))
        btn.contentDescription = "My profile"
        btn.press().onTap { openMyProfile(false) }
        // (inside the page's edge: a margin past it cut the ring's left side — owner, 20 Nov)
        row.addView(btn, LayoutParams(dpi(56f), dpi(56f), Gravity.START or Gravity.CENTER_VERTICAL))
        val vw = resources.displayMetrics.widthPixels / Ui.density
        row.addView(sdText(context, "TrueFind", (vw * .074f).coerceIn(26f, 31f), Color.WHITE, italic = true).apply { setPadding(dpi(4f), 0, dpi(4f), 0) },
            LayoutParams(-2, -2, Gravity.CENTER))
        // credits pill — opens the Secret Dating wallet
        creditsPill.background = rounded(K.wine2, 17f)
        creditsPill.elevation = Ui.dp(3f)
        creditsPill.gravity = Gravity.CENTER_VERTICAL
        creditsPill.setPadding(dpi(7f), 0, dpi(5f), 0)
        creditsPill.compoundDrawablePadding = dpi(4f)
        val plus = Icon(Lucide.Plus, K.hot, 13f, 3.2f)
        creditsPill.setCompoundDrawablesWithIntrinsicBounds(Coins(18f), null, PlusChip(plus), null)
        val pillBox = FrameLayout(context).apply { addView(creditsPill, LayoutParams(-2, dpi(34f), Gravity.CENTER)) }
        pillBox.press().onTap { wallet() }
        // (inside the page's edge too: the "+" circle was cut on the right — owner, 20 Nov)
        row.addView(pillBox, LayoutParams(-2, dpi(44f), Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(row, lp(MATCH, WRAP))
    }

    /** the little white "+" circle at the end of the credits pill */
    private class PlusChip(val icon: Icon) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun getIntrinsicWidth() = Ui.dpi(22f)
        override fun getIntrinsicHeight() = Ui.dpi(22f)
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; c.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, p)
            val s = Ui.dpi(13f); icon.setBounds(b.centerX() - s / 2, b.centerY() - s / 2, b.centerX() + s / 2, b.centerY() + s / 2); icon.draw(c)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun buildTitle() {
        val box = vbox(context, 9f, Gravity.CENTER_HORIZONTAL)
        val vw = resources.displayMetrics.widthPixels / Ui.density
        box.addView(sdText(context, "SECRET DATING", (vw * .082f).coerceIn(28f, 34f), Color.WHITE).apply { letterSpacing = .06f })
        val pill = sdText(context, "Serious Singles Only", 16.5f, Ui.c("#961846")).icon(Icon(Lucide.Gem, K.hot, 15f, 2.2f), 7f).apply {
            letterSpacing = .01f; gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 999f); setPadding(dpi(12f), 0, dpi(16f), 0); elevation = Ui.dp(3f)
        }
        box.addView(pill, lp(WRAP, dpi(34f)))
        col.addView(box, lp(MATCH, WRAP, t = 10f))
    }

    private fun buildComplete() {
        completeBtn.orientation = LinearLayout.HORIZONTAL
        completeBtn.gravity = Gravity.CENTER_VERTICAL
        completeBtn.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(18f); setStroke(dpi(1.5f), Ui.c("#F3A7C6")) }
        completeBtn.setPadding(dpi(10f), dpi(10f), dpi(8f), dpi(10f))
        completeBtn.elevation = Ui.dp(2f)
        completeBtn.contentDescription = "Complete your profile"
        val ic = ImageView(context).apply { setImageDrawable(Icon(Lucide.User, Color.WHITE, 22f, 2.2f)); scaleType = ImageView.ScaleType.CENTER; background = K.badge() }
        completeBtn.addView(ic, lp(dpi(42f), dpi(42f), r = 10f))
        val t = vbox(context)
        t.addView(sdText(context, "Complete your profile", 16f, Ui.c("#9A1246")))
        t.addView(completeSub, lp(WRAP, WRAP, t = 3f))
        completeBtn.addView(t, lp(0, WRAP, 1f))
        completeBtn.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, K.wine, 20f, 2.4f)) }, lp(dpi(20f), dpi(20f), l = 10f))
        completeBtn.press().onTap { openMyProfile(true) }
        completeBtn.visibility = GONE
        col.addView(completeBtn, lp(MATCH, WRAP, t = 12f))
    }

    private fun sectionTitle(title: String, sub: String? = null): View {
        val box = vbox(context)
        val row = hbox(context, 12f)
        fun lineView(toRight: Boolean) = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                if (toRight) intArrayOf(Color.argb(0, 214, 40, 120), Ui.c("#D93A86")) else intArrayOf(Ui.c("#D93A86"), Color.argb(0, 214, 40, 120)))
        }
        fun diamond() = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#FF7DBB"), Ui.c("#C80962"))).apply { cornerRadius = Ui.dp(1.5f) }
            rotation = 45f
        }
        row.addView(lineView(true), lp(0, dpi(1.5f), 1f))
        row.addView(diamond(), lp(dpi(7f), dpi(7f)))
        row.addView(sdText(context, title, 26f, Ui.c("#941544"), italic = true).apply { letterSpacing = .005f })
        row.addView(diamond(), lp(dpi(7f), dpi(7f)))
        row.addView(lineView(false), lp(0, dpi(1.5f), 1f))
        box.addView(row, lp(MATCH, WRAP))
        if (sub != null) box.addView(sdText(context, sub, 15.5f, Ui.c("#A31C55")).apply { gravity = Gravity.CENTER; lineHeight(1.1f) }, lp(MATCH, WRAP, l = 6f, r = 6f, t = 6f))
        return box
    }

    private fun buildStories() {
        body.addView(sectionTitle("Stories"), lp(MATCH, WRAP, t = 24f, b = 14f))
        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        storyRow.orientation = LinearLayout.HORIZONTAL
        storyRow.setPadding(dpi(12f), dpi(2f), dpi(12f), dpi(4f))
        hs.addView(storyRow)
        body.addView(hs, lp(MATCH, WRAP, l = -16f, r = -16f))
        // "Stories are Premium Only" / "Verify to use Stories free"
        storyLock.orientation = LinearLayout.HORIZONTAL; storyLock.gravity = Gravity.CENTER_VERTICAL
        storyLock.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#FCDDEC"), Ui.c("#FDE8F2"))).apply { cornerRadius = Ui.dp(18f); setStroke(dpi(1f), Color.argb(71, 232, 120, 170)) }
        storyLock.setPadding(dpi(10f), dpi(10f), dpi(8f), dpi(10f))
        storyLockIcon.scaleType = ImageView.ScaleType.CENTER; storyLockIcon.background = K.badge()
        storyLock.addView(storyLockIcon, lp(dpi(44f), dpi(44f), r = 10f))
        val t = vbox(context); t.addView(storyLockTitle); t.addView(storyLockSub, lp(WRAP, WRAP, t = 3f))
        storyLock.addView(t, lp(0, WRAP, 1f))
        storyLock.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, K.wine, 20f, 2.4f)) }, lp(dpi(20f), dpi(20f), l = 10f))
        storyLock.press().onTap { locked("watch") }
        body.addView(storyLock, lp(MATCH, WRAP, t = 14f))
        addLine.gravity = Gravity.CENTER; addLine.lineHeight(1.15f)
        addLine.inlineIcon(Icon(Lucide.Pencil, K.deep, 17f, 2.2f), 7f)
        addLine.setPadding(dpi(6f), dpi(8f), dpi(6f), dpi(8f)); addLine.minHeight = dpi(44f)
        addLine.press().onTap { addStory() }
        body.addView(addLine, lp(MATCH, WRAP, t = 8f))
    }

    private fun buildPrivate() {
        // owner, 20 Nov: the four boxes were written unprofessionally — each point now has a short title and one clear line
        body.addView(sectionTitle("Private by Design", "Your privacy is our most important feature."), lp(MATCH, WRAP, t = 24f, b = 14f))
        val points = listOf(
            Triple(Lucide.Lock, "Your number stays private", "Your phone number is never shown or shared."),
            Triple(Lucide.Link2Off, "No links or IDs", "Links, IDs and social handles are blocked in chat."),
            Triple(Lucide.ShieldCheck, "Verified members", "Every feature opens after full verification."),
            Triple(Lucide.MessageCircleMore, "Unlimited chat", "Once a chat is open, talk as much as you like."),
        )
        val grid = vbox(context, 10f)
        for (r in 0 until 2) {
            val row = hbox(context, 10f, Gravity.TOP).apply { isBaselineAligned = false }
            for (c in 0 until 2) {
                val (ic, title, line) = points[r * 2 + c]
                val cell = vbox(context, 0f).apply {
                    background = rounded(Color.WHITE, 18f, 1f, Color.argb(40, 214, 40, 120))
                    setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(13f)); elevation = Ui.dp(2f)
                }
                cell.addView(ImageView(context).apply { setImageDrawable(Icon(ic, Color.WHITE, 18f, 2.1f)); scaleType = ImageView.ScaleType.CENTER; background = oval(K.hot) }, lp(dpi(36f), dpi(36f)))
                cell.addView(sdText(context, title, 15f, Ui.c("#7A1240")).lineHeight(1.08f), lp(MATCH, WRAP, t = 10f))
                cell.addView(sdText(context, line, 13f, K.sub, bold = false).lineHeight(1.18f), lp(MATCH, WRAP, t = 4f))
                cell.contentDescription = "$title. $line"
                row.addView(cell, lp(0, MATCH, 1f))
            }
            grid.addView(row, lp(MATCH, WRAP))
        }
        body.addView(grid, lp(MATCH, WRAP))
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
            profileKnown && missing.isNotEmpty() -> myBadge.addView(sdText(context, missing.size.toString(), 12f, Color.WHITE).apply { gravity = Gravity.CENTER; background = oval(Ui.c("#E3086C"), 2f, Color.WHITE) },
                LayoutParams(dpi(18f), dpi(18f), Gravity.END or Gravity.TOP).apply { topMargin = dpi(1f) })
            premium -> myBadge.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Crown, Color.WHITE, 11f, 2.2f, Color.WHITE)); scaleType = ImageView.ScaleType.CENTER; background = oval(K.hot, 2f, Color.WHITE) },
                LayoutParams(dpi(21f), dpi(21f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -dpi(1f); bottomMargin = dpi(1f) })
            else -> myBadge.addView(View(context).apply { background = oval(K.green, 2.5f, Color.WHITE) }, LayoutParams(dpi(14f), dpi(14f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(2f); bottomMargin = dpi(3f) })
        }
        creditsPill.text = "${SdRules.fmtCredits(creditsNow)} Credits"
        creditsPill.contentDescription = "${creditsNow?.let { "$it credits" } ?: "Credits"}. Open Secret Dating wallet"
        renderNotice()
        // complete-profile prompt
        val showComplete = profileKnown && missing.isNotEmpty() && !needSecret
        completeBtn.visibility = if (showComplete) VISIBLE else GONE
        completeSub.text = "Add ${missing.joinToString(", ") { SdRules.NEED_LABEL[it] ?: it }} to unlock Premium features."
        actBox.visibility = if (needSecret) GONE else VISIBLE
        // not a member yet: the Join card instead of Online Now, the cards, Stories and Private by Design
        body.visibility = if (needSecret) GONE else VISIBLE
        if (needSecret && joinCard == null) {
            joinCard = JoinCard.build(context, "secret") { needSecret = false; render(); loadPeople() }
                .also { col.addView(it, col.indexOfChild(body), lp(MATCH, WRAP, t = 22f)) }
        } else if (!needSecret) joinCard?.let { col.removeView(it); joinCard = null }
        renderOnline()
        renderDeck()
        renderActivity()
        renderStories()
        for (p in pills) p.text = creditsPill.text
        if (page == "map") mapPage?.render()
        if (page == "chat") chatPage?.render()
    }

    private fun renderNotice() {
        noticeBox.text = notice
        noticeBox.visibility = if (notice.isNotEmpty()) VISIBLE else GONE
    }

    private fun renderOnline() {
        onlineRow.removeAllViews()
        val n = liveDeck.size
        val pill = hbox(context, 9f, Gravity.CENTER).apply { background = rounded(Color.WHITE, 999f, 1f, Color.argb(36, 214, 40, 120)); setPadding(dpi(13f), 0, dpi(16f), 0) }
        if (n > 0) {
            pill.addView(LiveDot(context, 10f, 3f, Color.argb(56, 61, 214, 42)))
            pill.addView(sdText(context, "${SdRules.inr(n)} Online Now", 17f, Ui.c("#141215")))
        } else {
            pill.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Lock, K.rose, 15f, 2.2f)) }, lp(dpi(15f), dpi(15f)))
            pill.addView(sdText(context, "Private · Members only", 17f, K.sub))
        }
        onlineRow.orientation = LinearLayout.HORIZONTAL
        onlineRow.addView(pill, lp(WRAP, dpi(36f)))
        val d = daysLeft
        if (premiumKnown && premium && d != null) {
            val renew = d == "Renew"
            val plan = sdText(context, if (renew) "Premium ended · Renew" else "Premium · $d", 13f, if (renew) Ui.c("#B4183C") else K.wine)
                .icon(Icon(Lucide.Hourglass, if (renew) Ui.c("#B4183C") else K.wine, 13f), 5f).apply {
                    gravity = Gravity.CENTER_VERTICAL; background = rounded(if (renew) Ui.c("#FFE4E6") else Color.WHITE, 14f); setPadding(dpi(10f), 0, dpi(10f), 0)
                    // always one whole line (owner, 20 Nov: it was squeezed into two lines and cut)
                    isSingleLine = true; maxLines = 1
                }
            plan.press().onTap { wallet() }
            // both side by side only when both fit whole; otherwise the plan goes under, centred
            val free = resources.displayMetrics.widthPixels - dpi(32f)
            val un = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            pill.measure(un, un); plan.measure(un, un)
            if (pill.measuredWidth + dpi(8f) + plan.measuredWidth > free) onlineRow.orientation = LinearLayout.VERTICAL
            onlineRow.addView(plan, lp(WRAP, dpi(28f)))
        }
    }

    private var deckState = ""
    private fun renderDeck() {
        val state = if (online == null && discover == null) "loading" else if (liveDeck.isNotEmpty()) "deck" else "empty"
        if (state != deckState) {
            deckState = state
            deckBox.removeAllViews()
            when (state) {
                "loading" -> {
                    val w = minOf(resources.displayMetrics.widthPixels - dpi(74f), dpi(360f))
                    deckBox.addView(View(context).apply { background = rounded(Ui.c("#FCE4F0"), 28f) },
                        LayoutParams(w, (w * 1.48f).toInt(), Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(8f) })
                }
                "deck" -> deckBox.addView(deck, LayoutParams(-1, -2))
                else -> {
                    val card = vbox(context, 0f, Gravity.CENTER_HORIZONTAL).apply {
                        background = rounded(Color.WHITE, 28f, 1f, K.line); setPadding(dpi(22f), dpi(44f), dpi(22f), dpi(44f)); elevation = Ui.dp(6f)
                    }
                    card.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Sparkles, K.rose, 30f, 1.6f)) }, lp(dpi(30f), dpi(30f)))
                    card.addView(sdText(context, "No one online right now", 22f, K.wine, italic = true).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, t = 12f))
                    card.addView(sdText(context, "Members appear here the moment they come online.", 15f, K.sub, bold = false).apply { gravity = Gravity.CENTER; lineHeight(1.2f) }, lp(WRAP, WRAP, t = 6f))
                    deckBox.addView(card, LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(8f) })
                }
            }
        }
        if (state == "deck") deck.set(liveDeck, canRequest, inviteState)
    }

    // ── Notifications (who liked you, messaged you, viewed your profile) ──
    private var actAll = false
    private fun renderActivity() {
        actBox.removeAllViews()
        if (needSecret) return
        val data = activity
        val items = list(data?.optJSONArray("items"))
        val fresh = items.filter { it.optString("id") in freshIds }
        val older = items.filter { it.optString("id") !in freshIds }
        val all = fresh + older
        val canSee = data?.optBoolean("can_see") == true
        val box = vbox(context).apply { background = rounded(Color.WHITE, 24f, 1f, Color.argb(41, 214, 40, 120)); setPadding(dpi(10f), dpi(12f), dpi(10f), dpi(10f)); elevation = Ui.dp(4f) }
        // header: bell (the red dot blinks while something is new) · Notifications · how many new
        val head = hbox(context, 10f).apply { setPadding(dpi(4f), 0, dpi(4f), 0) }
        val bell = FrameLayout(context).apply { background = K.badge(); elevation = Ui.dp(3f) }
        bell.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Bell, Color.WHITE, 21f, 2.2f, if (fresh.isNotEmpty()) Color.WHITE else null)); scaleType = ImageView.ScaleType.CENTER }, LayoutParams(-1, -1))
        if (fresh.isNotEmpty()) bell.addView(BlinkDot(context), LayoutParams(dpi(12f), dpi(12f), Gravity.END or Gravity.TOP))
        head.addView(bell, lp(dpi(42f), dpi(42f)))
        val t = vbox(context)
        t.addView(sdText(context, "Notifications", 21f, K.wine, italic = true))
        t.addView(sdText(context, when { data == null -> "Checking…"; fresh.isNotEmpty() -> "${fresh.size} new for you"; items.isNotEmpty() -> "You're all caught up"; else -> "Likes, messages and profile views" },
            13.5f, if (fresh.isNotEmpty()) K.hot else K.sub, bold = fresh.isNotEmpty()), lp(WRAP, WRAP, t = 2f))
        head.addView(t, lp(0, WRAP, 1f))
        if (fresh.isNotEmpty()) head.addView(sdText(context, if (fresh.size > 99) "99+" else fresh.size.toString(), 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = rounded(Ui.c("#FF1F3D"), 14f); setPadding(dpi(9f), 0, dpi(9f), 0); minWidth = dpi(28f)
        }, lp(WRAP, dpi(28f)))
        box.addView(head, lp(MATCH, WRAP))
        when {
            data == null -> { for (i in 0..1) box.addView(View(context).apply { background = rounded(Ui.c("#FCE4F0"), 14f) }, lp(MATCH, dpi(52f), l = 4f, r = 4f, t = if (i == 0) 12f else 8f)) }
            all.isEmpty() -> box.addView(sdText(context, "When someone likes you, messages you or views your profile, you'll see it here.", 14f, K.sub, bold = false).apply {
                gravity = Gravity.CENTER; lineHeight(1.2f); background = rounded(K.tint2, 14f); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
            }, lp(MATCH, WRAP, l = 4f, r = 4f, t = 10f, b = 2f))
            else -> {
                val chips = hbox(context, 6f).apply { setPadding(dpi(2f), dpi(12f), dpi(2f), dpi(2f)) }
                fun count(kind: String) = items.filter { it.optString("kind") == kind }.sumOf { if (kind == "message") (it.optInt("unread").takeIf { u -> u > 0 } ?: it.optInt("count").takeIf { c -> c > 0 } ?: 1) else 1 }
                for ((k, l, ic) in listOf(Triple("like", "Like", Lucide.Heart), Triple("message", "Message", Lucide.MessageCircleMore), Triple("view", "View", Lucide.Eye))) {
                    val n = count(k)
                    val col = if (n > 0) K.wine else Ui.c("#B8A3AE")
                    // icon + count side by side in the middle of the chip
                    val chip = FrameLayout(context).apply { background = rounded(if (n > 0) K.tint else Ui.c("#FAF5F8"), 15f) }
                    chip.addView(sdText(context, "$n ${if (n == 1) l else "${l}s"}", 13f, col).icon(Icon(ic, col, 14f, 2.4f, if (k == "like" && n > 0) col else null), 5f).apply {
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
        val kind = x.optString("kind").ifEmpty { "like" }
        val p = x.optJSONObject("person")
        val show = canSee && p != null
        val bg = when (kind) { "message" -> K.hot; "view" -> K.wine; else -> Ui.c("#F0386B") }
        val ic = when (kind) { "message" -> Lucide.MessageCircleMore; "view" -> Lucide.Eye; else -> Lucide.Heart }
        val text = when (kind) {
            "message" -> if (x.optInt("count") > 1) "sent you ${x.optInt("count")} messages" else "sent you a message"
            "view" -> if (x.optInt("count") > 1) "viewed your profile ${x.optInt("count")} times" else "viewed your profile"
            else -> if (x.optBoolean("mutual")) "likes you — you liked them too" else "liked your profile"
        }
        val at = SdRules.timeAgo(x.optString("at")).let { if (it == "just now") "Just now" else it }
        val row = hbox(context, 11f).apply {
            setPadding(dpi(8f), dpi(9f), dpi(8f), dpi(9f))
            background = if (isNew) grad(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#FFF0F7"), Color.WHITE), 16f) else null
        }
        val face = FrameLayout(context).apply { clipChildren = false }
        if (show) {
            val ph = RoundPhoto(context, 48f)
            ph.set(SdRules.thumbs(p), Demo.round(p, dpi(48f)))
            val ringB = FrameLayout(context).apply { background = oval(Color.WHITE, 1.5f, Ui.c("#F6C2DA")); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
            ringB.addView(ph, LayoutParams(-1, -1))
            face.addView(ringB, LayoutParams(dpi(50f), dpi(50f), Gravity.CENTER))
        } else face.addView(BlurFace(context, x.optString("g")), LayoutParams(dpi(48f), dpi(48f), Gravity.CENTER))
        face.addView(ImageView(context).apply { setImageDrawable(Icon(ic, Color.WHITE, 11f, 2.6f, if (kind == "like") Color.WHITE else null)); scaleType = ImageView.ScaleType.CENTER; background = oval(bg, 2f, Color.WHITE) },
            LayoutParams(dpi(21f), dpi(21f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -dpi(3f); bottomMargin = -dpi(3f) })
        row.addView(face, lp(dpi(48f), dpi(48f)))
        val mid = vbox(context)
        val line = SpannableStringBuilder()
        if (show) {
            val name = SdRules.firstName(SdRules.s(p, "name")) + (SdRules.s(p, "age").let { if (it.isNotEmpty()) ", $it" else "" })
            line.append(name, ForegroundColorSpan(K.ink), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            line.setSpan(BoldSpan(), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            line.append(if (x.optString("g") == "f") "Someone pretty" else "Someone nice", ForegroundColorSpan(Color.argb(110, Color.red(K.wine), Color.green(K.wine), Color.blue(K.wine))), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        end.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, Ui.c("#D9A2BC"), 17f, 2.4f)) }, lp(dpi(17f), dpi(17f)))
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
            p.color = Color.argb(((1f - ph) * 0.7f * 255).toInt(), 255, 31, 61); c.drawCircle(r, r, r * (1f + 1.17f * ph), p)
            p.color = Color.WHITE; c.drawCircle(r, r, r, p)
            p.color = Color.argb(((1f - .45f * ph) * 255).toInt(), 255, 31, 61); c.drawCircle(r, r, r - Ui.dp(2f), p)
        }
    }

    /** blurred face: soft pink / lilac circle with a blurred person shape and a lock */
    private class BlurFace(ctx: Context, val g: String) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val lock = Icon(Lucide.Lock, Color.WHITE, 14f, 2.6f)
        override fun onDraw(c: android.graphics.Canvas) {
            val s = width.toFloat()
            val tone = if (g == "f") intArrayOf(Ui.c("#FF9CC6"), Ui.c("#E3086C")) else intArrayOf(Ui.c("#C9A6F5"), Ui.c("#8E1243"))
            c.save(); c.clipPath(android.graphics.Path().apply { addCircle(s / 2, s / 2, s / 2, android.graphics.Path.Direction.CW) })
            p.shader = android.graphics.LinearGradient(0f, 0f, s, s, tone[0], tone[1], android.graphics.Shader.TileMode.CLAMP); c.drawRect(0f, 0f, s, s, p); p.shader = null
            p.maskFilter = android.graphics.BlurMaskFilter(s * 3.4f / 48f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            val k = s / 48f
            c.save(); c.scale(1.08f, 1.08f, s / 2, s / 2)
            p.color = Color.argb(217, 255, 255, 255); c.drawCircle(24 * k, 19 * k, 9 * k, p)
            c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData("M7 48c1.5-10 8.6-15.5 17-15.5S39.5 38 41 48z").apply { transform(android.graphics.Matrix().apply { setScale(k, k) }) }, p.apply { color = Color.argb(204, 255, 255, 255) })
            p.color = Color.argb(89, 255, 255, 255); c.drawCircle(12 * k, 10 * k, 7 * k, p)
            c.restore(); p.maskFilter = null; c.restore()
            val ls = (s * 0.3f).toInt(); lock.setBounds((s / 2).toInt() - ls / 2, (s / 2).toInt() - ls / 2, (s / 2).toInt() + ls / 2, (s / 2).toInt() + ls / 2); lock.draw(c)
        }
    }

    /** the box was on screen for a moment: what is new counts as seen */
    private var seenCheck: Runnable? = null
    private fun watchSeen() {
        if (seenSent) return
        val check = Runnable {
            if (seenSent || !shown) return@Runnable
            val r = Rect()
            if (actBox.getGlobalVisibleRect(r) && r.height() >= actBox.height * 0.4f) { seenSent = true; markActivitySeen() }
        }
        seenCheck?.let { main.removeCallbacks(it) }
        seenCheck = check
        main.postDelayed(check, 1200)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (!seenSent) { main.removeCallbacks(check); main.postDelayed(check, 1200) } }
    }

    // ── stories ──
    private fun renderStories() {
        storyRow.removeAllViews()
        val list = storyList
        val sm = storiesMine
        if (sm != null) storyRow.addView(storyRing("mine", listOf(mine.optString("photo")), "Your Story", eyeSub(sm.optInt("views")), false, null) {
            StoryViewer.open(this, listOf(JSONObject(sm.toString()).put("photo", mine.optString("photo"))), 0, true)
        }.apply { contentDescription = "Your story, ${sm.optInt("views")} views" })
        else storyRow.addView(storyRing("add", emptyList(), "Your Story", null, false, null) { if (canUse) addStory() else locked("story") }.apply {
            contentDescription = if (canUse) "Add to your story" else "Add to your story, Premium only"
        })
        list.forEachIndexed { i, s ->
            val km = s.optDouble("_km", Double.NaN)
            val where = if (!km.isNaN()) (if (km < 5) "Your city" else "${Math.round(km)} km") else SdRules.s(s, "city")
            storyRow.addView(storyRing(if (s.optBoolean("seen")) "seen" else "new", listOf(SdRules.s(s, "photo_thumb"), SdRules.s(s, "photo")),
                SdRules.firstName(SdRules.s(s, "name")), if (where.isNotEmpty()) pinSub(where) else null, !canUse, s) { if (canUse) openStories(i) else locked("watch") })
        }
        if (list.isEmpty()) {
            val empty = when (storiesState) { "missing" -> "Stories are switching on soon."; "loading" -> ""; else -> "No stories within 500 km yet." }
            if (empty.isNotEmpty()) storyRow.addView(sdText(context, empty, 14f, K.sub, bold = false).apply { lineHeight(1.15f); maxWidth = dpi(230f); setPadding(dpi(8f), 0, dpi(8f), dpi(18f)) },
                LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        val locked = premiumKnown && !canUse
        storyLock.visibility = if (locked) VISIBLE else GONE
        if (locked) {
            storyLockTitle.text = if (girlUnverified) "Verify to use Stories free" else "Stories are Premium Only"
            storyLockSub.text = if (girlUnverified) "Verified girls post and watch stories free." else "Only Premium Members can watch or post stories."
            storyLockIcon.setImageDrawable(if (girlUnverified) Icon(Lucide.ShieldCheck, Color.WHITE, 24f, 2f) else Icon(Lucide.Crown, Color.WHITE, 23f, 2f, Color.WHITE))
        }
    }
    private fun eyeSub(views: Int) = sdText(context, views.toString(), 12f, K.sub, bold = false).icon(Icon(Lucide.Eye, K.sub, 12f), 2f)
    private fun pinSub(t: String) = sdText(context, t, 12f, K.sub, bold = false).icon(Icon(Lucide.MapPin, K.sub, 12f, 2.2f), 2f).oneLine()

    private fun storyRing(kind: String, photos: List<String>, label: String, sub: TextView?, crown: Boolean, story: JSONObject?, onClick: () -> Unit): View {
        val size = 54f
        val col = vbox(context, 4f, Gravity.CENTER_HORIZONTAL)
        val ringBox = FrameLayout(context).apply { clipChildren = false }
        if (kind == "add") {
            ringBox.addView(ImageView(context).apply {
                setImageDrawable(Icon(Lucide.Plus, K.hot, size * 0.46f, 2.6f)); scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#FFF3F9"), Ui.c("#FDDDEC"))).apply { shape = GradientDrawable.OVAL; setStroke(dpi(2f), Ui.c("#EC4E98")) }
                elevation = Ui.dp(2f)
            }, FrameLayout.LayoutParams(dpi(size), dpi(size)))
        } else {
            val ring = FrameLayout(context).apply {
                background = when (kind) {
                    "seen" -> oval(Ui.c("#EBC9D9"))
                    "mine" -> RingDrawable(intArrayOf(Ui.c("#F43D95"), Ui.c("#8E1243"), Ui.c("#FF7DBB"), Ui.c("#F43D95")), floatArrayOf(0f, .55f, .85f, 1f))
                    else -> RingDrawable(intArrayOf(Ui.c("#FF7DBB"), Ui.c("#E3086C"), Ui.c("#8E1243"), Ui.c("#FF9CCB"), Ui.c("#FF7DBB")), floatArrayOf(0f, .4f, .7f, .9f, 1f))
                }
                setPadding(dpi(2.5f), dpi(2.5f), dpi(2.5f), dpi(2.5f))
            }
            val wb = FrameLayout(context).apply { background = oval(Color.WHITE); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
            val ph = RoundPhoto(context, size)
            ph.set(photos, story?.let { Demo.round(JSONObject().put("id", it.optString("profile_id")).put("gender", it.optString("gender")), dpi(54f)) })
            wb.addView(ph, FrameLayout.LayoutParams(-1, -1)); ring.addView(wb, FrameLayout.LayoutParams(-1, -1))
            ringBox.addView(ring, FrameLayout.LayoutParams(dpi(size), dpi(size)))
        }
        if (crown) ringBox.addView(ImageView(context).apply {
            setImageDrawable(Icon(Lucide.Crown, Color.WHITE, 12f, 2.2f, Color.WHITE)); scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#F23D93"), Ui.c("#C20A5E"))).apply { shape = GradientDrawable.OVAL; setStroke(dpi(2f), Color.WHITE) }
        }, FrameLayout.LayoutParams(dpi(22f), dpi(22f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -dpi(4f); bottomMargin = -dpi(2f) })
        col.addView(ringBox, lp(dpi(size), dpi(size)))
        col.addView(sdText(context, label, 13f, if (kind == "seen") K.soft else K.ink).oneLine().apply { maxWidth = dpi(size + 8f) })
        if (sub != null) col.addView(sub.apply { maxWidth = dpi(size + 8f) }, lp(WRAP, WRAP, t = -2f))
        col.press().onTap(onClick)
        return FrameLayout(context).apply { addView(col, FrameLayout.LayoutParams(dpi(size + 8f), -2)); setPadding(dpi(1f), 0, dpi(1f), 0) }
    }

    // ═════════════════ actions (same rules as the web) ═════════════════
    fun wallet() = go("/wallet?app=secret", null)

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
    /** the same credits pill as the Home header (opens the Secret Dating wallet) */
    fun newCreditsPill(): View {
        val t = sdText(context, creditsPill.text, 13f, Color.WHITE).apply {
            background = rounded(K.wine2, 17f); elevation = Ui.dp(3f); gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(7f), 0, dpi(5f), 0); compoundDrawablePadding = dpi(4f)
            setCompoundDrawablesWithIntrinsicBounds(Coins(18f), null, PlusChip(Icon(Lucide.Plus, K.hot, 13f, 3.2f)), null)
        }
        pills.add(t)
        return FrameLayout(context).apply { addView(t, LayoutParams(-2, dpi(34f), Gravity.CENTER)); contentDescription = creditsPill.contentDescription }.also { it.press().onTap { wallet() } }
    }
    /** header of the map and chat pages: back · title · credits */
    fun pageHeader(title: String): View {
        val row = hbox(context, 2f)
        row.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ArrowLeft, Color.WHITE, 26f, 2.3f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back to Secret Dating" }
            .press().onTap { goHome() }, lp(dpi(44f), dpi(44f)))
        val vw = resources.displayMetrics.widthPixels / Ui.density
        row.addView(sdText(context, title, (vw * .076f).coerceIn(25f, 32f), Color.WHITE, italic = true).oneLine(), lp(0, WRAP, 1f, l = 4f))
        row.addView(newCreditsPill(), lp(WRAP, dpi(44f)))
        return FrameLayout(context).apply { addView(row, LayoutParams(-1, -2).apply { setMargins(-dpi(12f), 0, -dpi(8f), 0) }); clipChildren = false }
    }
    /** "— Verified Boys & Girls Across India —" */
    fun pageSub(text: String): View {
        val row = hbox(context, 8f, Gravity.CENTER)
        fun line(toRight: Boolean) = View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (toRight) intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(230, 255, 255, 255)) else intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(0, 255, 255, 255))) }
        val vw = resources.displayMetrics.widthPixels / Ui.density
        row.addView(line(true), lp(dpi(26f), dpi(1.5f)))
        row.addView(sdText(context, text, (vw * .039f).coerceIn(13.5f, 15f), Color.WHITE))
        row.addView(line(false), lp(dpi(26f), dpi(1.5f)))
        return row
    }
    /** "Premium · 2d left" (tap: wallet) — null without a running plan */
    fun planPill(): View? {
        val d = daysLeft
        if (!(premiumKnown && premium && d != null)) return null
        val renew = d == "Renew"
        return sdText(context, if (renew) "Premium ended · Renew" else "Premium · $d", 13f, if (renew) Ui.c("#B4183C") else K.wine)
            .icon(Icon(Lucide.Hourglass, if (renew) Ui.c("#B4183C") else K.wine, 13f), 5f).apply {
                gravity = Gravity.CENTER_VERTICAL; background = rounded(if (renew) Ui.c("#FFE4E6") else Color.WHITE, 14f); setPadding(dpi(10f), 0, dpi(10f), 0)
            }.also { it.press().onTap { wallet() } }
    }
    /** a story from someone you're chatting with (shown on their profile) */
    fun chatStoryFor(uid: String, pid: String, otherId: String): JSONObject? = storyList.firstOrNull { x ->
        val xu = x.optString("user_id"); val xp = x.optString("profile_id")
        (xu.isNotEmpty() && (xu == uid || xu == otherId)) || (xp.isNotEmpty() && (xp == pid || xp == otherId))
    }
    /** Chat list: tap a photo → that person's profile (story + Open Chat) — at once, the rest of the profile fills in when it arrives */
    fun openFromChat(c: SdChatsPage.Chat) {
        val open = c.open || chatOpen(c.id)
        if (!canUse && !open) { locked("chat"); return }
        val known = ((online ?: emptyList()) + (discover ?: emptyList())).firstOrNull { p ->
            (c.pid.isNotEmpty() && p.optString("id") == c.pid) || (c.uid.isNotEmpty() && (p.optString("user_id") == c.uid || p.optString("created_by_id") == c.uid))
        }
        val base = known?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", c.pid.ifEmpty { c.id }).put("user_id", c.uid).put("name", c.name).put("photo", c.photo).put("age", c.age).put("verified", c.verified)
        base.put("_online", c.online || base.optString("id") in onlineIds || SdRules.isOnline(base)).put("_chatId", c.id)
        if (open) base.put("_chatOpen", true)
        chatStoryFor(c.uid, c.pid, "")?.let { base.put("_story", it) }
        val refresh = openProfile(base)
        if (known != null) return
        // the web: { ...profile, ...what we had, name/photo/city/state from the profile when we had none }
        fun merge(p: JSONObject) {
            val m = JSONObject(p.toString())
            base.keys().forEach { k -> m.put(k, base.get(k)) }
            m.put("name", SdRules.s(base, "name").ifEmpty { SdRules.s(p, "full_name", "name") })
            m.put("photo", SdRules.s(base, "photo").ifEmpty { SdRules.s(p, "photo", "profile_picture") })
            m.put("city", SdRules.s(base, "city").ifEmpty { SdRules.s(p, "city") }).put("state", SdRules.s(base, "state").ifEmpty { SdRules.s(p, "state") })
            refresh(m)
        }
        fun byUser() { if (c.uid.isNotEmpty()) Api.filter("UserProfile", JSONObject().put("created_by_id", c.uid), null, 1) { r -> r.data.optJSONArray("items")?.optJSONObject(0)?.let { merge(it) } } }
        if (c.pid.isNotEmpty()) Api.filter("UserProfile", JSONObject().put("id", c.pid), null, 1) { r -> r.data.optJSONArray("items")?.optJSONObject(0)?.let { merge(it) } ?: byUser() }
        else byUser()
    }
    /** Chat list: tap the name / message → the chat (a locked chat opens the plans straight away) */
    fun openChatRow(c: SdChatsPage.Chat) {
        if (premiumKnown && !canUse && !(c.open || chatOpen(c.id))) { unlock(); return }
        chatPage?.markRead(c.id)
        go("/chat/${c.id}", JSONObject().put("openChat", 1))
    }

    // ── for the profile page and the sheets ──
    val isPremium get() = canUse
    val mayRequest get() = canRequest
    val whyLocked get() = lockWhy
    val creditsShown get() = creditsNow
    val until get() = untilText
    fun isSameGender(p: JSONObject) = sameGender(p)
    fun storyFor(p: JSONObject): JSONObject? = storyOf(p.optString("id")) ?: p.optJSONObject("_story")
    fun inviteOf(id: String) = inviteState[id] ?: ""
    val myProfileNow get() = myProfile ?: JSONObject()
    val gender get() = myGender
    val premiumInfo get() = Triple(premium, premiumKnown, premiumUntil)
    val days get() = daysLeft

    /** "Send Request" (card or profile) */
    fun askRequest(p: JSONObject) {
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
        Api.invoke("sendInvitation", JSONObject().put("to_profile_id", id)) { r ->
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
                    layer.closeAll(); render(); go("/chat/$match", JSONObject().put("openChat", 1)); return@invoke
                }
                inviteState[id] = "sent"
                say("Request sent to ${SdRules.firstName(SdRules.s(p, "name")).ifEmpty { "them" }} ✓")
            } else {
                inviteState.remove(id)
                val err = d.optString("error")
                when {
                    err == "verify_first" -> verifySheet()
                    err == "opposite_gender_only" -> say(d.optString("message").ifEmpty { "Requests can only be sent to the opposite gender." }, "err")
                    err == "no_credits" -> { credits = 0; say(d.optString("message").ifEmpty { "No credits left. Credits come with a Secret Dating plan." }, "credits") }
                    r.status == 402 || err == "premium_required" -> say(if (freeGirl) "Requests need credits — they come with a plan." else "Sending requests is for Premium members.", "premium")
                    r.status == 403 -> say(d.optString("message").ifEmpty { err.ifEmpty { "You can't send a request to this person." } }, "err")
                    else -> say(err.ifEmpty { r.error ?: "Could not send the request." }, "err")
                }
            }
            render(); refresh()
        }
    }

    fun moderate(p: JSONObject, action: String) {
        Api.invoke("moderateUser", JSONObject().put("profile_id", p.optString("id")).put("action", action)) { r ->
            if (r.ok) {
                val id = p.optString("id")
                online = online?.filter { it.optString("id") != id }; discover = discover?.filter { it.optString("id") != id }
                storiesAll = storiesAll.filter { it.optString("profile_id") != id }
                layer.closeAll()
                say(if (action == "block") "Blocked. You won't see each other anymore." else "Report sent. Our team will review it.")
                render()
            } else say(r.data.optString("error").ifEmpty { r.error ?: "Action failed." }, "err")
        }
    }

    fun liked(id: String, v: Boolean) { online = online?.map { if (it.optString("id") == id) it.put("liked_by_me", v) else it } }

    fun openChat(p: JSONObject) { val id = p.optString("_chatId"); layer.closeAll(); go("/chat/$id", JSONObject().put("openChat", 1)) }

    private fun openStories(i: Int) {
        if (!canUse) { locked("watch"); return }
        val l = storyList
        if (i !in l.indices) return
        StoryViewer.open(this, l, i, false)
    }
    fun watchStory(s: JSONObject) { StoryViewer.open(this, listOf(s), 0, false) }
    fun seeStory(s: JSONObject) {
        if (s.optBoolean("seen")) return
        val id = s.optString("id")
        storiesAll = storiesAll.map { if (it.optString("id") == id) it.put("seen", true) else it }
        Api.invoke("viewStory", JSONObject().put("story_id", id)) { }
    }
    fun deleteStory(done: () -> Unit) {
        Api.invoke("deleteStory", JSONObject().put("story_id", storiesMine?.optString("id"))) { r ->
            if (r.ok) { done(); storiesMine = null; say("Story deleted."); renderStories() } else say(r.data.optString("error").ifEmpty { "Could not delete the story." }, "err")
        }
    }
    fun viewersOfMine(done: (List<JSONObject>) -> Unit) {
        Api.invoke("getStories") { r -> done(list(r.data.optJSONObject("mine")?.optJSONArray("viewers"))) }
    }
    private fun addStory() {
        if (!canUse) { locked("story"); return }
        if (storiesState != "ready") { say("Stories are switching on soon."); return }
        SdSheets.addStory(this) { d ->
            d.optJSONObject("mine")?.let { storiesMine = it }
            say("Your story is live for 24 hours ✨")
            loadStories()
        }
    }

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
            "story" -> StoryViewer.open(this, storyList, 0, false)
            "my" -> openMyProfile(false)
            "myedit" -> openMyProfile(true)
            "addstory" -> SdSheets.addStory(this) { }
            "toast" -> say("Request sent to ${SdRules.firstName(SdRules.s(first, "name"))} ✓")
            "pop" -> showPop(list(activity?.optJSONArray("items")).take(1))
            "scroll" -> scroll.scrollTo(0, scroll.getChildAt(0).height)
            "scrollmid" -> scroll.scrollTo(0, deckBox.top + deckBox.height / 2)
            "stories" -> scroll.scrollTo(0, (storyRow.parent as View).top - dpi(260f))
            "seeall" -> { actAll = true; renderActivity() }
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
            what == "chatprofile" -> chatPage?.chats?.firstOrNull()?.let { openFromChat(it) }
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
        // 12 above the bottom of this page (the Secret Dating bar sits right under it)
        val bottomGap = host.height - host.paddingBottom - (bottom - dpi(12f))
        (toast.layoutParams as FrameLayout.LayoutParams).bottomMargin = maxOf(dpi(12f), bottomGap)
        toastText.text = text
        toastText.setTextColor(if (kind == "err") Ui.c("#B4183C") else K.ink2)
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
        val text = when (kind) { "message" -> if (x.optInt("count") > 1) "sent you ${x.optInt("count")} messages" else "sent you a message"; "view" -> "viewed your profile"; else -> if (x.optBoolean("mutual")) "likes you — you liked them too" else "liked your profile" }
        val title = if (items.size > 1) "${items.size} new notifications" else "${p?.let { SdRules.firstName(SdRules.s(it, "name")) } ?: "Someone"} $text"
        val card = hbox(context, 11f).apply { background = rounded(Color.WHITE, 20f, 1f, Color.argb(46, 214, 40, 120)); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f)); elevation = Ui.dp(95f) }
        val face: View = if (p != null) RoundPhoto(context, 44f).also { it.set(SdRules.thumbs(p)) } else BlurFace(context, x.optString("g"))
        card.addView(face, lp(dpi(44f), dpi(44f)))
        val t = vbox(context); t.addView(sdText(context, title, 15.5f, K.ink).oneLine()); t.addView(sdText(context, if (canSee) "Tap to see" else "Tap to see who", 13f, K.hot))
        card.addView(t, lp(0, WRAP, 1f))
        card.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Bell, K.hot, 20f, 2.3f)) }, lp(dpi(20f), dpi(20f)))
        host.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(12f), dpi(10f), dpi(12f), 0) })
        card.translationY = -dpi(120f).toFloat(); card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)).start()
        val close = Runnable { card.animate().alpha(0f).setDuration(200).withEndAction { host.removeView(card) }.start() }
        card.setOnClickListener { main.removeCallbacks(close); host.removeView(card); scroll.smoothScrollTo(0, maxOf(0, actBox.top - dpi(80f))) }
        main.postDelayed(close, 4500)
    }
}

/** My Secret Dating chats by the other person's user id and profile id (newest first wins) — and the lookup for a profile. */
/** Secret Dating: is this chat open for me without a running plan? — the same rule as the server's shared/chatOpen.ts */
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
