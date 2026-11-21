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
 * The Secret Dating bottom bar (Home · My Activity · Chat · Online Map) is the app's own too (SdNavBar): these pages
 * end where it starts. A member who has not joined Secret Dating yet gets the Join card here (JoinCard).
 * The look (owner, 20 Nov night, from his photos): Home = one photo card up under the status bar (SdHomeView);
 * My Activity is the heart tab (SdLikesPage: Liked you · Matches, no notifications); Chat and Online Map get the same
 * maroon top; Stories moved under the map. The same data and the same rules as before.
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
    /** which Secret Dating page: "home" (/secret-app) · "likes" (?tab=likes) · "chat" (?tab=chat) · "map" (/dating) */
    var page = "home"; private set
    private var homeView: SdHomeView? = null
    private var likesPage: SdLikesPage? = null
    private var mapPage: SdMapPage? = null
    private var chatPage: SdChatsPage? = null
    /** the Stories block (shown under the map on Online Map) */
    private val storiesBox = vbox(ctx)
    private val statusCover = StatusStrip(ctx, KY.PAGE[0])
    private val storyRow = LinearLayout(ctx)
    private val storyLock = LinearLayout(ctx)
    private val storyLockTitle = jk(ctx, "", 15f, SD.TXT, 700)
    private val storyLockSub = jk(ctx, "", 12.5f, SD.SUB, 500)
    private val storyLockIcon = ImageView(ctx)
    private val addLine = jk(ctx, "You can post a story every day to find your true friend.", 14f, SD.M_TEXT, 600)
    private val toast = LinearLayout(ctx)
    private val toastText = sdText(ctx, "", 15f, KY.ink2, bold = false)
    private val toastBtn = sdText(ctx, "", 14f, Color.WHITE)

    init {
        SdRules.load(ctx)
        clipChildren = false
        // nothing of these pages may be drawn below their bottom edge: the Secret Dating bar is there
        outlineProvider = android.view.ViewOutlineProvider.BOUNDS
        clipToOutline = true
        setBackgroundColor(SD.NAV_BG)
        // a full page open on top (a profile, My profile): the strip under the status bar takes its pink
        statusCover.visibility = GONE
        statusCover.elevation = Ui.dp(50f)
        addView(statusCover, LayoutParams(-1, -2))
        layer.onPages = { statusCover.visibility = if (layer.hasPage) VISIBLE else GONE }
        layer.dimColor = Color.argb(115, 12, 12, 16)
        buildStories()
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
            mapPage?.setActive(false); chatPage?.setActive(false); likesPage?.setActive(false)
            liveOff()
        }
    }
    private fun pageView(v: String): View? = when (v) { "home" -> homeView; "likes" -> likesPage; "map" -> mapPage; "chat" -> chatPage; else -> null }
    private val pageOrder = listOf("home", "likes", "chat", "map")
    private fun switchTo(view: String) {
        val from = page
        val before = pageView(from)?.takeIf { it.visibility == VISIBLE }
        switchNow(view)
        // the same smooth move as between the app's pages, in the order of the bottom bar (Home · My Activity · Chat · Online Map)
        val after = pageView(view)
        if (shown && isShown && before != null && after != null && before !== after) {
            PageFx.run(this, PageFx.Kind.TAB, before, after, dir = if (pageOrder.indexOf(view) > pageOrder.indexOf(from)) 1 else -1,
                onEnd = { pageView(page)?.let { cur -> listOf(homeView, likesPage, mapPage, chatPage).forEach { v -> if (v != null && v !== cur) v.visibility = GONE } } })
        }
    }
    private fun switchNow(view: String) {
        page = view
        // each page is made the first time it is opened
        if (view == "home" && homeView == null) homeView = SdHomeView(this).also { addView(it, 0, LayoutParams(-1, -1)) }
        if (view == "likes" && likesPage == null) likesPage = SdLikesPage(this).also { addView(it, LayoutParams(-1, -1)); likesStart?.let { s -> it.pick(s) }; likesStart = null }
        if (view == "map" && mapPage == null) mapPage = SdMapPage(this).also { addView(it, LayoutParams(-1, -1)) }
        if (view == "chat" && chatPage == null) chatPage = SdChatsPage(this).also { addView(it, LayoutParams(-1, -1)) }
        // every page ends with the Home card's round bottom corners over the grey bar (owner, 21 Nov morning)
        for (v in listOf(likesPage, mapPage, chatPage)) if (v != null && !v.clipToOutline) {
            v.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) { val r = Ui.dp(30f); o.setRoundRect(0, -r.toInt(), view.width, view.height, r) }
            }
            v.clipToOutline = true
        }
        homeView?.visibility = if (view == "home") VISIBLE else GONE
        likesPage?.visibility = if (view == "likes") VISIBLE else GONE
        mapPage?.visibility = if (view == "map") VISIBLE else GONE
        chatPage?.visibility = if (view == "chat") VISIBLE else GONE
        if (shown) {
            mapPage?.setActive(view == "map")
            chatPage?.setActive(view == "chat")
            likesPage?.setActive(view == "likes")
            if (view == "map") liveOff() else liveOn()
        }
        render()
    }
    private fun restartPoll() { main.removeCallbacks(poll); main.postDelayed(poll, 45_000) }
    fun onAppVisible(v: Boolean) {
        if (!shown) return
        if (v) { loadAll(); restartPoll(); if (page != "map") liveOn(); chatPage?.setActive(page == "chat"); if (page == "likes") likesPage?.setActive(true) }
        else { main.removeCallbacks(poll); liveOff(); chatPage?.setActive(false); likesPage?.setActive(false) }
    }
    fun back(): Boolean {
        if (layer.back()) return true
        mapPage?.let { if (page == "map" && it.isFull) { it.setFull(false); return true } }
        // Back on My Activity / Chat / Online Map → Home; Back on Home → "Tap again to exit" (black, white words) at the
        // bottom; a second Back within 2 seconds → out of Secret Dating, to True Find (owner, 21 Nov morning)
        if (!shown) return false
        if (page != "home") { go("/secret-app", null); return true }
        val t = android.os.SystemClock.uptimeMillis()
        if (t - exitAskAt < 2000) { exitAskAt = 0L; exitTip?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; go("/", null); return true }
        exitAskAt = t
        exitTip()
        return true
    }
    private var exitAskAt = 0L
    private var exitTip: View? = null
    private fun exitTip() {
        exitTip?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val v = jk(context, "Tap again to exit", 14f, Color.WHITE, 500).apply {
            gravity = Gravity.CENTER; background = rounded(Ui.c("#111114"), 22f); setPadding(dpi(20f), dpi(12f), dpi(20f), dpi(12f))
            elevation = Ui.dp(12f); alpha = 0f
        }
        exitTip = v
        val bottomGap = (host.height - host.paddingBottom) - bottom + dpi(16f)
        host.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = maxOf(dpi(16f), bottomGap) })
        v.animate().alpha(1f).setDuration(150).start()
        v.postDelayed({ v.animate().alpha(0f).setDuration(200).withEndAction { (v.parent as? android.view.ViewGroup)?.removeView(v); if (exitTip === v) exitTip = null }.start() }, 2000)
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
        // a tapped notification: open My Activity (Notifications are at its top)
        if (q.getQueryParameter("activity") != null) main.postDelayed({ if (shown && !needSecret) openMyActivity("liked") }, 300)
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

    // ── pull down to refresh (owner, 19 Nov evening): Home, My Activity and the chats, in Secret Dating's own yellow ──
    private val pull: PagePull = run {
        PagePull(this, SD.M_TEXT, SD.M_TINT, canPull = {
            shown && !layer.open && when (page) {
                "home" -> homeView?.let { it.isShown && !it.dragging } == true
                "likes" -> likesPage?.let { it.isShown && it.atTop() } == true
                "chat" -> chatPage?.let { it.isShown && it.atTop() } == true
                else -> false   // the map moves under the finger
            }
        }) { done ->
            when (page) {
                "chat" -> { var left = 2; val one = { left -= 1; if (left == 0) done() }; loadAll(one); chatPage?.load(done = one) ?: one() }
                "likes" -> { var left = 2; val one = { left -= 1; if (left == 0) done() }; loadAll(one); likesPage?.load(one) ?: one() }
                else -> loadAll(done)
            }
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
                // the same answer the Secret Dating wallet asks for: handed over, so it opens with fresh credits at once
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
            if (!r.ok || items == null) { if (activity == null) { activity = JSONObject().put("items", JSONArray()).put("counts", JSONObject()).put("can_see", false); render() }; return@invoke }
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
            render()
        }
    }
    private fun markActivitySeen() { Api.invoke("getSecretActivity", JSONObject().put("mark_seen", true)) { } }

    private fun list(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    // ═════════════════ Stories (under the map on Online Map now — owner, 20 Nov night) ═════════════════
    /** the Stories block for the Online Map page (one block: it moves with the page that shows it) */
    fun storiesView(): View { (storiesBox.parent as? android.view.ViewGroup)?.removeView(storiesBox); renderStories(); return storiesBox }
    private fun buildStories() {
        val head = hbox(context, 8f)
        head.addView(jk(context, "Stories", 20f, SD.TXT, 700), lp(0, WRAP, 1f))
        head.addView(jk(context, "24 hours", 12f, SD.SUB, 600).apply { background = rounded(SD.FIELD, 12f); setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f)) })
        storiesBox.addView(head, lp(MATCH, WRAP, b = 12f))
        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        storyRow.orientation = LinearLayout.HORIZONTAL
        storyRow.setPadding(dpi(12f), dpi(2f), dpi(12f), dpi(4f))
        hs.addView(storyRow)
        storiesBox.addView(hs, lp(MATCH, WRAP, l = -16f, r = -16f))
        // "Stories are Premium Only" / "Verify to use Stories free" (the same lock as before, in the new colours)
        storyLock.orientation = LinearLayout.HORIZONTAL; storyLock.gravity = Gravity.CENTER_VERTICAL
        storyLock.background = rounded(SD.M_TINT, 18f)
        storyLock.setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
        storyLockIcon.scaleType = ImageView.ScaleType.CENTER; storyLockIcon.background = oval(SD.M)
        storyLock.addView(storyLockIcon, lp(dpi(42f), dpi(42f), r = 12f))
        val t = vbox(context); t.addView(storyLockTitle); t.addView(storyLockSub, lp(WRAP, WRAP, t = 2f))
        storyLock.addView(t, lp(0, WRAP, 1f))
        storyLock.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, SD.M_TEXT, 20f, 2.4f)) }, lp(dpi(20f), dpi(20f), l = 8f))
        storyLock.press().onTap { locked("watch") }
        storiesBox.addView(storyLock, lp(MATCH, WRAP, t = 12f))
        addLine.gravity = Gravity.CENTER; addLine.setLineSpacing(0f, 1.15f)
        addLine.inlineIcon(Icon(Lucide.Pencil, SD.M_TEXT, 16f, 2.2f), 7f)
        addLine.setPadding(dpi(6f), dpi(8f), dpi(6f), dpi(8f)); addLine.minHeight = dpi(44f)
        addLine.press().onTap { addStory() }
        storiesBox.addView(addLine, lp(MATCH, WRAP, t = 6f))
    }

    private fun buildToast() {
        toast.orientation = LinearLayout.HORIZONTAL; toast.gravity = Gravity.CENTER_VERTICAL
        toast.background = rounded(Color.WHITE, 18f, 1f, KY.line)
        toast.elevation = Ui.dp(90f)
        toast.setPadding(dpi(16f), dpi(12f), dpi(14f), dpi(12f))
        toast.addView(toastText, lp(0, WRAP, 1f))
        toastBtn.background = rounded(KY.pill, 19f); toastBtn.gravity = Gravity.CENTER; toastBtn.setPadding(dpi(16f), 0, dpi(16f), 0)
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
        homeView?.render()
        renderStories()
        onLikesDot?.invoke(hasNewActivity)
        if (page == "map") mapPage?.render()
        if (page == "chat") chatPage?.render()
        if (page == "likes") likesPage?.render()
    }
    private fun renderNotice() = render()
    /** the heart in the bottom bar gets a red dot while new likes wait (MainActivity sets this) */
    var onLikesDot: ((Boolean) -> Unit)? = null
    /** Secret Dating Home has just read the credits (MainActivity hands them to the Wallet) */
    var onCredits: ((JSONObject) -> Unit)? = null

    /** blurred face: soft pink / lilac circle with a blurred person shape and a lock */
    private class BlurFace(ctx: Context, val g: String) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val lock = Icon(Lucide.Lock, Color.WHITE, 14f, 2.6f)
        override fun onDraw(c: android.graphics.Canvas) {
            val s = width.toFloat()
            val tone = if (g == "f") intArrayOf(Ui.c("#FFF0C2"), Ui.c("#E0A800")) else intArrayOf(Ui.c("#C9A6F5"), Ui.c("#1B1B1F"))
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

    // ── My Activity: the heart tab now (Liked you · Matches) — the bell, a notification and a new-like pop-up open it ──
    private var likesStart: String? = null
    fun goLikes(start: String = "liked") {
        if (needSecret) return
        val which = if (start == "match") "match" else "liked"
        likesPage?.pick(which) ?: run { likesStart = which }
        if (page == "likes") return
        layer.closeAll()
        go("/secret-app?tab=likes", null)
    }
    @Suppress("UNUSED_PARAMETER")
    fun openMyActivity(start: String = "liked", all: Boolean = false) = goLikes(start)
    /** the likes were on screen for a moment: what was new counts as seen, and the red dots go */
    fun likesSeen() {
        if (seenSent || !hasNewActivity) return
        seenSent = true
        markActivitySeen()
        freshIds.clear()
        activity?.optJSONObject("counts")?.let { it.put("likes", 0); it.put("total", 0) }
        render()
    }
    fun openChatId(id: String) { layer.closeAll(); go("/chat/$id", JSONObject().put("openChat", 1)) }

    // ── for the Home card (SdHomeView) and the maroon tops (SdTop) ──
    val deckNow: List<JSONObject> get() = liveDeck
    val deckLoading: Boolean get() = online == null && discover == null
    val joinNeeded: Boolean get() = needSecret
    fun joined() { needSecret = false; render(); loadPeople() }
    val missingNow: List<String> get() = if (profileKnown && !needSecret) missing else emptyList()
    val myPhotoRaw: String get() = mine.optString("photo")
    val myFullName: String get() = SdRules.s(myProfile, "full_name", "name").ifEmpty { mine.optString("name") }
    /** new likes not seen yet (the bell's dot, the heart's dot) */
    val hasNewActivity: Boolean get() = (activity?.optJSONObject("counts")?.optInt("likes") ?: 0) > 0

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
            if (empty.isNotEmpty()) storyRow.addView(sdText(context, empty, 14f, KY.sub, bold = false).apply { lineHeight(1.15f); maxWidth = dpi(230f); setPadding(dpi(8f), 0, dpi(8f), dpi(18f)) },
                LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        val locked = premiumKnown && !canUse
        storyLock.visibility = if (locked) VISIBLE else GONE
        if (locked) {
            storyLockTitle.text = if (girlUnverified) "Verify to use Stories free" else "Stories are Premium Only"
            storyLockSub.text = if (girlUnverified) "Verified girls post and watch stories free." else "Only Premium Members can watch or post stories."
            storyLockIcon.setImageDrawable(if (girlUnverified) Icon(Lucide.ShieldCheck, SD.ON_M, 24f, 2f) else Icon(Lucide.Crown, SD.ON_M, 23f, 2f, SD.ON_M))
        }
    }
    private fun eyeSub(views: Int) = sdText(context, views.toString(), 12f, KY.sub, bold = false).icon(Icon(Lucide.Eye, KY.sub, 12f), 2f)
    private fun pinSub(t: String) = sdText(context, t, 12f, KY.sub, bold = false).icon(Icon(Lucide.MapPin, KY.sub, 12f, 2.2f), 2f).oneLine()

    private fun storyRing(kind: String, photos: List<String>, label: String, sub: TextView?, crown: Boolean, story: JSONObject?, onClick: () -> Unit): View {
        val size = 54f
        val col = vbox(context, 4f, Gravity.CENTER_HORIZONTAL)
        val ringBox = FrameLayout(context).apply { clipChildren = false }
        if (kind == "add") {
            ringBox.addView(ImageView(context).apply {
                setImageDrawable(Icon(Lucide.Plus, Ui.c("#E0A800"), size * 0.46f, 2.6f)); scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SD.M_TINT, Ui.c("#FFEFC2"))).apply { shape = GradientDrawable.OVAL; setStroke(dpi(2f), SD.M) }
                elevation = Ui.dp(2f)
            }, FrameLayout.LayoutParams(dpi(size), dpi(size)))
        } else {
            val ring = FrameLayout(context).apply {
                background = when (kind) {
                    "seen" -> oval(Ui.c("#E2E2E8"))
                    "mine" -> RingDrawable(intArrayOf(SD.M, SD.HEAD, Ui.c("#FFE088"), SD.M), floatArrayOf(0f, .55f, .85f, 1f))
                    else -> RingDrawable(intArrayOf(Ui.c("#FFE088"), SD.M, Ui.c("#E0A800"), Ui.c("#FFD54F"), Ui.c("#FFE088")), floatArrayOf(0f, .4f, .7f, .9f, 1f))
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
            setImageDrawable(Icon(Lucide.Crown, SD.ON_M, 12f, 2.2f, SD.ON_M)); scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#FFD54F"), SD.M)).apply { shape = GradientDrawable.OVAL; setStroke(dpi(2f), Color.WHITE) }
        }, FrameLayout.LayoutParams(dpi(22f), dpi(22f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -dpi(4f); bottomMargin = -dpi(2f) })
        col.addView(ringBox, lp(dpi(size), dpi(size)))
        col.addView(sdText(context, label, 13f, if (kind == "seen") KY.soft else KY.ink).oneLine().apply { maxWidth = dpi(size + 8f) })
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
    /** "Premium · 2d left" (tap: wallet) — null without a running plan */
    fun planPill(): View? {
        val d = daysLeft
        if (!(premiumKnown && premium && d != null)) return null
        val renew = d == "Renew"
        // the yellow pill on the black top of Online Map (owner, 21 Nov night); an ended plan stays red
        val col = if (renew) Ui.c("#B4183C") else SD.ON_M
        return jk(context, if (renew) "Premium ended · Renew" else "Premium · $d", 12.5f, col, 700)
            .icon(SdIcon(listOf(SdPaths.HOURGLASS to col), 13f, 2.2f), 6f).apply {
                gravity = Gravity.CENTER_VERTICAL; background = rounded(if (renew) Ui.c("#FFE4E6") else SD.M, 15f); setPadding(dpi(12f), 0, dpi(12f), 0)
                isSingleLine = true; maxLines = 1
                contentDescription = "${text}. Open Secret Dating wallet"
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
        if (SdDemo.isDemo(p)) { say("This is a demo profile. Requests go only to real members."); return }
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
            // My Activity shows the request's new status
            if (page == "likes") likesPage?.load()
        }
    }

    fun moderate(p: JSONObject, action: String) {
        if (SdDemo.isDemo(p)) { say("This is a demo profile."); return }
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
            "next" -> homeView?.preview("next")
            "drag" -> homeView?.preview("drag")
            "dragleft" -> homeView?.preview("dragleft")
            "leave" -> back()
            "activity" -> goLikes("liked")
            "activity-match" -> goLikes("match")
            "demo-profile" -> online?.firstOrNull { SdDemo.isDemo(it) && SdRules.liveOf(it) }?.let { openProfile(it) }
            "demo-request" -> online?.firstOrNull { SdDemo.isDemo(it) }?.let { askRequest(it) }
            "profileend" -> { openProfile(first); postDelayed({ lastScroll(layer.root)?.let { it.scrollTo(0, it.getChildAt(0).height) } }, 1200) }
        }
    }

    /** test shots on the map and chat pages */
    private fun previewPage(what: String, tries: Int) {
        val ready = when (page) {
            "map" -> online != null && SdMapData.ready
            "likes" -> myProfile != null && activity != null
            else -> chatPage?.chats != null && myProfile != null
        }
        if (!ready && tries < 40) { postDelayed({ previewPage(what, tries + 1) }, 300); return }
        if (tries >= 0) { postDelayed({ previewPage(what, -1) }, 900); return }
        when {
            what == "profile" -> (online?.firstOrNull { SdRules.liveOf(it) } ?: online?.firstOrNull())?.let { openProfile(it) }
            what == "chatprofile" -> chatPage?.chats?.firstOrNull()?.let { openFromChat(it) }
            what == "premium" -> locked("chat")
            page == "map" -> mapPage?.preview(what)
            page == "likes" -> likesPage?.preview(what)
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
        toastText.setTextColor(if (kind == "err") Ui.c("#B4183C") else KY.ink2)
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
        val card = hbox(context, 11f).apply { background = rounded(Color.WHITE, 20f, 1f, Color.argb(46, 224, 168, 0)); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f)); elevation = Ui.dp(95f) }
        val face: View = if (p != null) RoundPhoto(context, 44f).also { it.set(SdRules.thumbs(p)) } else BlurFace(context, x.optString("g"))
        card.addView(face, lp(dpi(44f), dpi(44f)))
        val t = vbox(context); t.addView(sdText(context, title, 15.5f, KY.ink).oneLine()); t.addView(sdText(context, if (canSee) "Tap to see" else "Tap to see who", 13f, KY.hot))
        card.addView(t, lp(0, WRAP, 1f))
        card.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Bell, KY.hot, 20f, 2.3f)) }, lp(dpi(20f), dpi(20f)))
        host.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(12f), dpi(10f), dpi(12f), 0) })
        card.translationY = -dpi(120f).toFloat(); card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)).start()
        val close = Runnable { card.animate().alpha(0f).setDuration(200).withEndAction { host.removeView(card) }.start() }
        card.setOnClickListener { main.removeCallbacks(close); host.removeView(card); openMyActivity("liked") }
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
