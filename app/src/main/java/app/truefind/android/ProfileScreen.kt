package app.truefind.android

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * True Find · Profile (pages/Profile.jsx), native. Everything of the page:
 * photo, name, Online, Dashboard, location, Followers / Following, Edit Profile, Share,
 * Bio, Photos (1 main + 4, in review, make main, remove), Interests, Joined, Verification,
 * and — kept in the address like the web (?edit=1, ?people=…, ?settings=…) — the Edit
 * Profile page, the Followers / Following page and the Settings drawer with its screens.
 */
@SuppressLint("ViewConstructor")
class ProfileScreen(ctx: Context, val pages: NativePages) : KeepPage(ctx) {
    class Ph(val ref: String, val pending: Boolean, val index: Int, val own: Boolean)

    private val main = Handler(Looper.getMainLooper())

    // ── data ──
    var me: JSONObject? = null; private set
    var profile: JSONObject? = null; private set
    var notifications: List<JSONObject> = emptyList()
    var blocked: List<JSONObject> = emptyList()
    /** Avatar / Templates shop: what is for sale (the server's list), my True Find coins */
    var shopItems: List<ShopItemV> = emptyList(); private set
    var shopCoins: Long? = null; private set
    private var shopSeq = 0
    private val shopBoxes = HashMap<String, LinearLayout>()
    private var shopPage: ShopAllPage? = null
    private var shopPageClose: (() -> Unit)? = null
    private var shopSheetClose: (() -> Unit)? = null
    var premium: JSONObject? = null
    /** followers, following (from the server) */
    var followCounts: LongArray? = null
    var uploading = false; private set
    var uploadingAvatar = false; private set
    private var loading = false
    // The last profile seen on this phone (for this sign-in): the page opens with it at once and is
    // refreshed underneath. Before, every first open showed a blank page with a spinner for ~1 s
    // (two server answers in a row) — seen frame by frame on the phone video.
    private val saved = ctx.getSharedPreferences("tfprofile", 0)
    private fun signKey() = Api.token.hashCode().toString()
    private fun restoreSaved() {
        if (profile != null || Api.token.isEmpty() || saved.getString("k", null) != signKey()) return
        try {
            me = JSONObject(saved.getString("me", null) ?: return)
            profile = JSONObject(saved.getString("p", null) ?: return)
        } catch (e: Exception) { me = null; profile = null }
    }
    /** loads the profile in the background (from Home), so the first open is instant */
    fun prefetch() { if (profile == null && !loading && pages.signedIn) { restoreSaved(); load() } }
    private var loadError = ""
    private var loadSeq = 0

    // ── the address ──
    var search = ""; private set
    var usr: JSONObject? = null; private set
    private var shown = false

    // ── views ──
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
    private val pageCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(PC.ground) }
    private val center = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE); setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
    private var edit: PEditPage? = null
    private var people: PPeoplePage? = null
    var drawer: PSettings? = null; private set
    private var sheetClose: (() -> Unit)? = null
    private var viewerClose: (() -> Unit)? = null

    init {
        setBackgroundColor(PC.ground)
        isClickable = true
        scroll.addView(pageCol, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
        addView(center, LayoutParams(-1, -1))
        center.visibility = GONE
    }

    // ═════════════════ derived (same as the web) ═════════════════
    val gender: String get() = PR.s(profile, "gender").lowercase()
    val isFemale: Boolean get() = gender.startsWith("f")
    val name: String get() = PR.s(profile, "full_name").ifEmpty { PR.s(me, "full_name") }
    val locationText: String get() {
        val city = PR.s(profile, "city")
        return if (city.isNotEmpty()) "$city, ${PR.s(profile, "state").ifEmpty { "India" }}" else PR.s(profile, "state")
    }
    val interests: List<String> get() = Tf.strs(profile?.optJSONArray("hobbies")).filter { PR.truthy(it) }
    val joinedAt: String get() = PR.s(profile, "created_date").ifEmpty { PR.s(me, "created_date") }
    val vInfo: PR.VInfo get() = PR.verificationInfo(profile)
    /** women: Become a Creator first; an approved Creator can then apply to Host */
    val applyKind: String get() = if (isFemale && PR.creatorStatus(profile) != "approved") "creator" else "host"
    val applyStatus: String get() = if (applyKind == "host") PR.hostStatus(profile) else PR.creatorStatus(profile)
    val photoField: String get() {
        if ((profile?.optJSONArray("images")?.length() ?: 0) > 0) return "images"
        if ((profile?.optJSONArray("photos")?.length() ?: 0) > 0) return "photos"
        return "images"
    }
    private fun currentList(): JSONArray = profile?.optJSONArray(photoField) ?: JSONArray()
    val photos: List<Ph> get() {
        val p = profile ?: return emptyList()
        val arr = currentList()
        var mainList = (0 until arr.length()).map { it to arr.opt(it) }.filter { PR.srcOf(it.second).isNotEmpty() }
        val pic = PR.srcOf(p.opt("profile_picture"))
        if (pic.isNotEmpty()) mainList = mainList.sortedByDescending { PR.srcOf(it.second) == pic }
        val out = ArrayList(mainList.map { (i, raw) -> Ph(PR.srcOf(raw), PR.PENDING_WORDS.contains(PR.statusOfItem(raw)), i, true) })
        val queued = Tf.arr(p.optJSONArray("photo_queue")).filter { PR.str(it.opt("status")).ifEmpty { "pending" } == "pending" }
        val extra = ArrayList<Any?>()
        p.optJSONArray("pending_images")?.let { a -> for (i in 0 until a.length()) extra.add(a.opt(i)) }
        p.optJSONArray("pending_photos")?.let { a -> for (i in 0 until a.length()) extra.add(a.opt(i)) }
        extra.addAll(queued)
        for (raw in extra) {
            val ref = PR.srcOf(raw)
            if (ref.isNotEmpty() && out.none { it.ref == ref }) out.add(Ph(ref, true, -1, false))
        }
        return out.take(PR.MAX_PHOTOS)
    }
    /** the round photo: the main photo, else the first photo that is not in review */
    val avatarRaws: List<String> get() = listOfNotNull(PR.srcOf(profile?.opt("profile_picture")).ifEmpty { null }, photos.firstOrNull { !it.pending }?.ref)
    val unread: Int get() = notifications.count { !PR.truthy(it.opt("read")) && !PR.truthy(it.opt("is_read")) }
    val premiumActive: Boolean get() = premium?.let { p -> !PR.truthy(p.opt("until")) || Tf.ms(PR.s(p, "until")) > System.currentTimeMillis() } ?: false
    val coins: Double? get() = PR.firstNum(profile, listOf("coin_balance", "coins", "wallet_balance", "balance"))

    // ═════════════════ shown / the address ═════════════════
    fun setShown(on: Boolean, search: String, usr: JSONObject?, fresh: Boolean = false) {
        val was = shown
        shown = on
        visibility = if (on) VISIBLE else GONE
        if (!on) {
            // left the page (not just covered by a call screen): like the web, the page starts fresh next time
            if (was && !pages.onProfile) tearDown()
            if (!pages.signedIn) clearData()
            return
        }
        this.search = search; this.usr = usr
        // opened afresh (a tap — not Back from a page opened over it): from the top, not where it was left
        // (owner, 20 Nov: scrolled down, Back, opened again → it was still down there)
        if (!was && fresh) { scroll.stopNestedScroll(); scroll.fling(0); scroll.scrollTo(0, 0) }
        if (profile == null) { restoreSaved(); if (profile != null) renderPage() }
        if (!was && !loading) load()
        if (!was && profile != null) loadShop()
        applyAddress()
    }

    private fun tearDown() {
        sheetClose?.invoke(); sheetClose = null
        shopSheetClose?.invoke(); shopSheetClose = null
        shopPageClose?.invoke(); shopPageClose = null; shopPage = null
        viewerClose?.invoke(); viewerClose = null
        edit?.let { removeView(it) }; edit = null
        people?.let { removeView(it) }; people = null
        drawer?.let { it.release(); removeView(it) }; drawer = null
        scroll.visibility = VISIBLE
    }
    private fun clearData() {
        me = null; profile = null; notifications = emptyList(); blocked = emptyList(); premium = null; followCounts = null
        shopItems = emptyList(); shopCoins = null
        loadError = ""; loadSeq++; loading = false
        pageCol.removeAllViews()
        saved.edit().clear().apply()
    }

    private fun q(name: String): String? = try { Uri.parse("tf://x/" + (if (search.startsWith("?")) search else "?$search")).getQueryParameter(name) } catch (e: Exception) { null }
    val depth: Int get() = PR.firstNum(usr, listOf("tfDepth"))?.toInt() ?: 0
    val fromList: Boolean get() = PR.truthy(usr?.opt("fromList"))
    /** this settings screen was opened from another page (an ?open= link), not from Profile itself */
    val fromOutside: Boolean get() = PR.truthy(usr?.opt("fromOutside"))
    val panelId: String? get() { val s = q("settings"); return if (!s.isNullOrEmpty() && s != "1") s else null }

    private fun applyAddress() {
        if (!shown) return
        val p = profile
        val editOpen = q("edit") == "1"
        val kind = q("people")?.takeIf { it == "followers" || it == "following" }
        val settingsOpen = !q("settings").isNullOrEmpty()
        // old links like /profile?open=host still open the right place
        val open = q("open")
        if (p != null && !open.isNullOrEmpty()) {
            val target = when (open) {
                "host", "creator" -> "apply"; "settings" -> "1"
                "verification", "notifications", "privacy", "blocked", "memberships", "help", "security" -> open
                else -> null
            }
            // (opened from another page — Home's Verification bar, Reconnect, a Wallet button: its back arrow goes
            // back to that page, like the phone's Back; owner, 19 Nov evening)
            if (target != null) { replaceQuery("?settings=$target", JSONObject().put("fromOutside", true)); return }
        }
        if (p == null) { renderCenter(); return }
        center.visibility = GONE
        // Edit Profile page
        if (editOpen) {
            if (edit == null) { edit = PEditPage(context, this).also { addView(it, indexOfChild(center), LayoutParams(-1, -1)) } }
        } else edit?.let { it.closed(); removeView(it); edit = null }
        // Followers / Following page
        if (!editOpen && kind != null) {
            val pp = people ?: PPeoplePage(context, this).also { people = it; addView(it, indexOfChild(center), LayoutParams(-1, -1)) }
            pp.setKind(kind)
        } else people?.let { removeView(it); people = null }
        scroll.visibility = if (edit != null || people != null) INVISIBLE else VISIBLE
        // Settings drawer (slides in from the left)
        if (settingsOpen) {
            val d = drawer ?: PSettings(context, this).also { drawer = it; addView(it, LayoutParams(-1, -1)); it.slideIn() }
            d.setPanel(panelId)
        } else drawer?.let { d -> drawer = null; d.slideOut { removeView(d) } }
    }

    // ── moving around (like navigate() of the web page) ──
    private fun st(d: Int, extra: JSONObject?): JSONObject {
        val o = JSONObject().put("tfDepth", d)
        extra?.keys()?.forEach { k -> o.put(k, extra.get(k)) }
        return o
    }
    private val here: String get() = pages.path.ifEmpty { "/profile" }
    fun pushQuery(s: String, extra: JSONObject? = null) { if (pages.preview.isNotEmpty()) { previewGo(s); return }; pages.go(here + s, st(depth + 1, extra)) }
    fun replaceQuery(s: String, extra: JSONObject? = null) { if (pages.preview.isNotEmpty()) { previewGo(s); return }; pages.go(here + s, st(depth, extra), replace = true) }
    fun backToPage() { if (pages.preview.isNotEmpty()) { previewGo(""); return }; if (depth > 0) pages.backBy(depth) else replaceQuery("") }
    fun openEdit() = pushQuery("?edit=1")
    fun openPeople(kind: String) = pushQuery("?people=$kind")
    fun openDrawer(startPanel: String?) = pushQuery(if (startPanel != null) "?settings=$startPanel" else "?settings=1")
    fun closeDrawer() { if (fromOutside && pages.canGoBack && pages.preview.isEmpty()) pages.backBy(1) else backToPage() }
    fun openPanel(id: String) = pushQuery("?settings=$id", JSONObject().put("fromList", true))
    fun panelBack() { if (pages.preview.isNotEmpty()) { previewGo("?settings=1"); return }; if (fromList || (fromOutside && pages.canGoBack)) pages.backBy(1) else replaceQuery("?settings=1") }
    fun leaveTo(path: String) { backToPage(); main.postDelayed({ pages.go(path) }, 260) }
    fun go(path: String) = pages.go(path)
    /** test screens only: there is no web page, so the address changes here */
    private fun previewGo(s: String) { search = s; usr = null; applyAddress() }

    // ═════════════════ the phone's Back ═════════════════
    /** Pop-ups close first (they sit in the layer); the pages of the address go back through the web history. */
    fun onBack(): Boolean {
        if (pages.preview.isNotEmpty()) {
            if (drawer?.panel != null) { panelBack(); return true }
            if (drawer != null || edit != null || people != null) { previewGo(""); return true }
        }
        return false
    }
    fun onAppVisible(v: Boolean) { drawer?.onAppVisible(v) }

    // ═════════════════ data ═════════════════
    fun say(text: String, bad: Boolean = false) = pages.toast.show(text, bad, top = true, ms = 3000, style = "profile")

    fun load() {
        val seq = ++loadSeq
        loading = true; loadError = ""
        if (profile == null) renderCenter()
        Api.me { r ->
            if (seq != loadSeq) return@me
            val user = r.data
            val id = PR.s(user, "id")
            if (!r.ok || id.isEmpty()) { loadFailed(seq); return@me }
            if (me != null && PR.s(me, "id") != id) { profile = null; notifications = emptyList(); blocked = emptyList(); premium = null; followCounts = null }
            me = user
            val tries = arrayListOf(JSONObject().put("created_by_id", id), JSONObject().put("user_id", id))
            // (a member without an e-mail is not looked up by e-mail: an empty question would match anyone)
            PR.s(user, "email").takeIf { it.isNotEmpty() }?.let { tries.add(JSONObject().put("created_by", it)) }
            findProfile(tries, 0, 0, seq, id)
        }
    }
    private fun findProfile(tries: List<JSONObject>, i: Int, failed: Int, seq: Int, uid: String) {
        if (i >= tries.size) { gotProfile(null, failed, seq, uid); return }
        Api.filter("UserProfile", tries[i]) { r ->
            if (seq != loadSeq) return@filter
            if (!r.ok) { findProfile(tries, i + 1, failed + 1, seq, uid); return@filter }
            val p = PR.list(r).firstOrNull()
            if (p != null) gotProfile(p, failed, seq, uid) else findProfile(tries, i + 1, failed, seq, uid)
        }
    }
    /** the Blocked users list again (after a Block from the Followers list) */
    fun reloadBlocked() {
        val uid = PR.s(me, "id")
        if (uid.isEmpty()) return
        val seq = loadSeq
        Api.filter("Block", JSONObject().put("blocker_id", uid)) { r -> if (seq == loadSeq && r.ok) { blocked = PR.list(r); drawer?.refresh() } }
    }
    private fun gotProfile(p: JSONObject?, failed: Int, seq: Int, uid: String) {
        // no internet is not the same as "no profile yet": one failed lookup is enough to be unsure
        if (p == null && failed > 0) { loadFailed(seq); return }
        loading = false
        val firstTime = profile == null || PR.s(profile, "id") != PR.s(p, "id")
        profile = p
        if (p != null) saved.edit().putString("k", signKey()).putString("me", me?.toString()).putString("p", p.toString()).apply()
        if (p != null) {
            Api.filter("Block", JSONObject().put("blocker_id", uid)) { r -> if (seq == loadSeq) { blocked = if (r.ok) PR.list(r) else emptyList(); drawer?.refresh() } }
            if (firstTime) loadShop()
            Api.filter("Notification", JSONObject().put("user_id", uid)) { r -> if (seq == loadSeq) { notifications = if (r.ok) PR.sortNew(PR.list(r)).take(40) else emptyList(); drawer?.refresh() } }
            Api.filter("PremiumAccess", JSONObject().put("user_id", uid).put("app", "secret")) { r -> if (seq == loadSeq) { premium = if (r.ok) PR.sortNew(PR.list(r)).firstOrNull() else null; drawer?.refresh() } }
            if (firstTime || followCounts == null) loadCounts()
        }
        renderPage()
        applyAddress()
        drawer?.refresh()
    }
    private fun loadFailed(seq: Int) {
        if (seq != loadSeq) return
        loading = false
        loadError = "Could not load your profile. Please check your internet and try again."
        if (profile == null) renderCenter()
    }
    private fun loadCounts() {
        Api.invoke("follow", JSONObject().put("action", "counts")) { r ->
            if (!r.ok || PR.truthy(r.data.opt("error"))) return@invoke
            followCounts = longArrayOf(PR.firstNum(r.data, listOf("followers"))?.toLong() ?: 0, PR.firstNum(r.data, listOf("following"))?.toLong() ?: 0)
            renderPage()
        }
    }
    fun setCounts(followers: Long, following: Long) { followCounts = longArrayOf(followers, following); renderPage(); people?.renderTabs() }

    /** updateMyProfile { updates } — the saved profile comes back (or the error) */
    fun saveUpdates(updates: JSONObject, done: (JSONObject?, Api.Result?) -> Unit) {
        Api.invoke("updateMyProfile", JSONObject().put("updates", updates)) { r ->
            val d = r.data
            if (!r.ok || d.opt("ok") == false || (PR.truthy(d.opt("error")) && !PR.truthy(d.opt("profile")))) { done(null, r); return@invoke }
            val next = d.optJSONObject("profile")
            profile = next ?: JSONObject((profile ?: JSONObject()).toString()).also { m -> updates.keys().forEach { k -> m.put(k, updates.get(k)) } }
            renderPage(); drawer?.refresh(); edit?.profileChanged()
            done(next, null)
        }
    }
    /** Become a Host / Become a Creator: checked and saved on the server */
    fun applyFor(apply: JSONObject, done: (JSONObject?, Api.Result?) -> Unit) {
        Api.invoke("updateMyProfile", JSONObject().put("apply", apply), 60000) { r ->
            val p = r.data.optJSONObject("profile")
            if (!r.ok || p == null) { done(null, r); return@invoke }
            profile = p
            renderPage(); drawer?.refresh()
            done(p, null)
        }
    }

    // ═════════════════ actions ═════════════════
    private fun share() {
        val url = Api.origin + here
        try {
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "${name.ifEmpty { "My" }} profile on True Find"); putExtra(Intent.EXTRA_TEXT, url) }
            context.startActivity(Intent.createChooser(i, null))
        } catch (e: Exception) {
            copy(url); say("Profile link copied.")
        }
    }
    fun copy(text: String): Boolean = try {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("True Find", text)); true
    } catch (e: Exception) { false }

    private fun objectStyle(): Boolean { val a = currentList(); return (0 until a.length()).any { a.opt(it) is JSONObject } }
    private fun makeItem(url: String): Any = if (objectStyle()) JSONObject().put("url", url).put("status", "pending").put("uploaded_at", isoNow()) else url
    private fun isoNow() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
    private fun thumbsOf(url: String, thumb: String): JSONArray? = if (thumb.isEmpty()) null else JSONArray().put(JSONObject().put("uri", url).put("thumb", thumb))

    /** the phone's photo picker → (bytes, type) */
    fun pick(done: (ByteArray, String) -> Unit) {
        val p = SdSheets.pickImage ?: return
        p { bytes, type -> if (bytes != null) done(bytes, type) }
    }

    private fun addPhoto() {
        if (uploading) return
        pick { bytes, type ->
            val p = profile ?: return@pick
            if (!type.startsWith("image/")) { say("Please choose a photo.", true); return@pick }
            if (photos.size >= PR.MAX_PHOTOS) { say("You can add up to ${PR.MAX_PHOTOS} photos.", true); return@pick }
            if (bytes.size > 15 * 1024 * 1024) { say("This photo is too big. Please choose another one.", true); return@pick }
            uploading = true; renderPage()
            PUp.photoAndThumb(bytes, type) { up ->
                if (up.url.isEmpty()) { uploading = false; renderPage(); say(PR.errText(up.error?.data, "Could not add the photo. Please try again."), true); return@photoAndThumb }
                val list = currentList()
                val next = JSONArray(); for (i in 0 until list.length()) next.put(list.opt(i)); next.put(makeItem(up.url))
                val trimmed = JSONArray(); for (i in 0 until min(next.length(), PR.MAX_PHOTOS)) trimmed.put(next.opt(i))
                val updates = JSONObject().put(photoField, trimmed)
                thumbsOf(up.url, up.thumb)?.let { updates.put("photo_thumbs", it) }
                // a new main photo replaces the one already waiting for review, so only ask when none is waiting
                val mainWaiting = Tf.arr(p.optJSONArray("photo_queue")).any { PR.truthy(it.opt("main")) && PR.str(it.opt("status")).ifEmpty { "pending" } == "pending" }
                if (!PR.truthy(p.opt("profile_picture")) && !mainWaiting) updates.put("profile_picture", up.url)
                saveUpdates(updates) { _, err ->
                    uploading = false; renderPage()
                    if (err == null) say("Photo added. Our team will check it soon.") else say(PR.errText(err, "Could not add the photo. Please try again."), true)
                }
            }
        }
    }

    fun changeAvatar() {
        if (uploadingAvatar) return
        // an approved profile photo is replaced (it shows until the new one is approved, then it goes)
        mainPhoto()?.let { m -> if (m.own) { replacePhoto(m); return } }
        pick { bytes, type ->
            if (profile == null) return@pick
            if (!type.startsWith("image/")) { say("Please choose a photo.", true); return@pick }
            if (bytes.size > 15 * 1024 * 1024) { say("This photo is too big. Please choose another one.", true); return@pick }
            uploadingAvatar = true; renderPage(); edit?.profileChanged()
            PUp.photoAndThumb(bytes, type) { up ->
                if (up.url.isEmpty()) { uploadingAvatar = false; renderPage(); edit?.profileChanged(); say(PR.errText(up.error?.data, "Could not change your photo. Please try again."), true); return@photoAndThumb }
                val updates = JSONObject().put("profile_picture", up.url)
                thumbsOf(up.url, up.thumb)?.let { updates.put("photo_thumbs", it) }
                val cur = currentList()
                if (cur.length() < PR.MAX_PHOTOS) { val a = JSONArray().put(makeItem(up.url)); for (i in 0 until cur.length()) a.put(cur.opt(i)); updates.put(photoField, a) }
                saveUpdates(updates) { next, err ->
                    uploadingAvatar = false; renderPage(); edit?.profileChanged()
                    if (err != null) say(PR.errText(err, "Could not change your photo. Please try again."), true)
                    // a new photo is checked by the team first; it is not live yet
                    else say(if (next != null && PR.s(next, "profile_picture") != up.url) "Photo sent for review. It shows after our team approves it." else "Profile photo updated.")
                }
            }
        }
    }

    private fun makeMain(ph: Ph) {
        if (!ph.own || ph.index < 0 || ph.ref == PR.s(profile, "profile_picture")) return
        val cur = currentList()
        val a = JSONArray().put(cur.opt(ph.index))
        for (i in 0 until cur.length()) if (i != ph.index) a.put(cur.opt(i))
        saveUpdates(JSONObject().put(photoField, a).put("profile_picture", ph.ref)) { _, err ->
            if (err == null) { viewerClose?.invoke(); viewerClose = null; say("Main photo changed.") } else say(PR.errText(err, "Could not change the main photo."), true)
        }
    }

    /** the server's photo_action (updateMyProfile): delete / main / replace — on approved photos and on photos still in review */
    private fun photoAction(action: JSONObject, done: (JSONObject?, Api.Result?) -> Unit) {
        Api.invoke("updateMyProfile", JSONObject().put("photo_action", action)) { r ->
            val next = r.data.optJSONObject("profile")
            if (!r.ok || next == null) { done(null, r); return@invoke }
            profile = next
            renderPage(); drawer?.refresh(); edit?.profileChanged()
            done(next, null)
        }
    }
    /** a server from before 19 Nov does not know "replace" / deleting a photo in review */
    private fun oldServer(r: Api.Result?): Boolean = r != null && r.status == 400 && PR.s(r.data, "error") == "Invalid photo action."

    /**
     * Delete a photo (owner, 19 Nov): an approved one leaves the profile; one still in review leaves the review
     * (before, a photo in review could not be removed at all).
     */
    private fun removePhoto(ph: Ph) {
        val isMain = ph.ref == PR.s(profile, "profile_picture")
        PConfirm(pages.layer, "Delete this photo?",
            if (ph.pending) "It is still being checked. It will be removed and never shown." else if (isMain) "It will be removed from your profile. Your next photo becomes your profile photo." else "It will be removed from your profile.",
            "Delete", danger = true, onConfirm = { box ->
            photoAction(JSONObject().put("type", "delete").put("uri", ph.ref)) { _, err ->
                if (err == null) { box.close(); viewerClose?.invoke(); viewerClose = null; say("Photo deleted."); return@photoAction }
                if (ph.own && oldServer(err)) {
                    // (an older server: an approved photo is removed the way it was before)
                    val cur = currentList()
                    val a = JSONArray(); for (i in 0 until cur.length()) if (i != ph.index) a.put(cur.opt(i))
                    val updates = JSONObject().put(photoField, a)
                    if (isMain) updates.put("profile_picture", if (a.length() > 0) PR.srcOf(a.opt(0)) else "")
                    saveUpdates(updates) { _, e2 ->
                        box.close()
                        if (e2 == null) { viewerClose?.invoke(); viewerClose = null; say("Photo deleted.") } else say(PR.errText(e2, "Could not delete the photo."), true)
                    }
                    return@photoAction
                }
                box.close()
                say(PR.errText(err, "Could not delete the photo. Please try again."), true)
            }
        })
    }

    /**
     * Replace a photo with a new one from the phone (owner, 19 Nov). The new photo is checked by the team first,
     * like every new photo. The main photo keeps showing until its replacement is approved (then it goes); any other
     * photo makes room at once; a photo still in review is simply swapped.
     */
    private var replacing = ""
    fun replacePhoto(ph: Ph) {
        if (uploading || uploadingAvatar) return
        pick { bytes, type ->
            if (profile == null) return@pick
            if (!type.startsWith("image/")) { say("Please choose a photo.", true); return@pick }
            if (bytes.size > 15 * 1024 * 1024) { say("This photo is too big. Please choose another one.", true); return@pick }
            val isMain = ph.ref == PR.s(profile, "profile_picture")
            uploading = true; replacing = ph.ref; if (isMain) uploadingAvatar = true
            renderPage(); edit?.profileChanged()
            fun end() { uploading = false; uploadingAvatar = false; replacing = ""; renderPage(); edit?.profileChanged() }
            PUp.photoAndThumb(bytes, type) { up ->
                if (up.url.isEmpty()) { end(); say(PR.errText(up.error?.data, "Could not upload the photo. Please try again."), true); return@photoAndThumb }
                val act = JSONObject().put("type", "replace").put("uri", ph.ref).put("new_uri", up.url)
                if (up.thumb.isNotEmpty()) act.put("thumb", up.thumb)
                photoAction(act) { _, err ->
                    if (err == null) { end(); viewerClose?.invoke(); viewerClose = null; say("New photo sent for review. It shows after our team approves it."); return@photoAction }
                    if (ph.own && oldServer(err)) {
                        // (an older server: the new photo goes in for review in the old photo's place, the old way)
                        val cur = currentList()
                        val a = JSONArray()
                        if (isMain) { a.put(makeItem(up.url)); for (i in 0 until cur.length()) a.put(cur.opt(i)) }
                        else { for (i in 0 until cur.length()) if (i != ph.index) a.put(cur.opt(i)); a.put(makeItem(up.url)) }
                        val updates = JSONObject().put(photoField, a)
                        if (isMain) updates.put("profile_picture", up.url)
                        thumbsOf(up.url, up.thumb)?.let { updates.put("photo_thumbs", it) }
                        saveUpdates(updates) { _, e2 ->
                            end()
                            if (e2 == null) { viewerClose?.invoke(); viewerClose = null; say("New photo sent for review. It shows after our team approves it.") }
                            else say(PR.errText(e2, "Could not replace the photo."), true)
                        }
                        return@photoAction
                    }
                    end()
                    say(PR.errText(err, "Could not replace the photo. Please try again."), true)
                }
            }
        }
    }

    fun saveEdit(d: PEditPage.Draft, done: () -> Unit) {
        val p = profile
        val basic = JSONObject(); val extra = JSONObject()
        if (d.bio.trim() != PR.s(p, "bio")) basic.put("bio", d.bio.trim())
        if (d.fullName.trim().isNotEmpty() && d.fullName.trim() != PR.s(p, "full_name")) extra.put("full_name", d.fullName.trim())
        if (d.city.trim() != PR.s(p, "city")) extra.put("city", d.city.trim())
        if (d.state != PR.s(p, "state")) extra.put("state", d.state)
        if (d.locationVisible != (p?.opt("location_visible") != false)) extra.put("location_visible", d.locationVisible)
        if (basic.length() == 0 && extra.length() == 0) { done(); backToPage(); return }
        val all = JSONObject(basic.toString()); extra.keys().forEach { all.put(it, extra.get(it)) }
        saveUpdates(all) { _, err ->
            if (err == null) { done(); backToPage(); say("Profile saved."); return@saveUpdates }
            if (err.data.opt("error") != "privacy_blocked" && basic.length() > 0 && extra.length() > 0) {
                saveUpdates(basic) { _, e2 ->
                    done()
                    if (e2 == null) { backToPage(); say("Bio saved. Name or location could not be changed right now.", true) }
                    else say(PR.errText(e2, "Could not save your profile."), true)
                }
                return@saveUpdates
            }
            done()
            say(PR.errText(err, "Could not save your profile."), true)
        }
    }

    fun setLocationVisible(v: Boolean, done: () -> Unit = {}) {
        saveUpdates(JSONObject().put("location_visible", v)) { _, err ->
            done()
            if (err == null) say(if (v) "Location is visible on your profile." else "Location is hidden.") else say(PR.errText(err, "Could not change this setting."), true)
        }
    }

    fun unblock(b: JSONObject) {
        val id = PR.s(b, "id")
        blocked = blocked.filter { PR.s(it, "id") != id }
        drawer?.refresh()
        Api.async({ Api.request("DELETE", "/entities/Block/$id", null, null) }) { r -> if (r.ok || Demo.on) say("Unblocked.") else { say("Could not unblock. Please try again.", true); load() } }
    }

    fun markRead() {
        val rows = notifications.filter { !PR.truthy(it.opt("read")) && !PR.truthy(it.opt("is_read")) }
        if (rows.isEmpty()) return
        notifications = notifications.map { JSONObject(it.toString()).put("read", true) }
        drawer?.refresh()
        for (n in rows) {
            val id = PR.s(n, "id")
            if (!Demo.on) Api.async({ Api.request("PUT", "/entities/Notification/$id", JSONObject().put("read", true).toString().toByteArray(), "application/json") }) { }
        }
    }

    fun joinSecret(done: () -> Unit) {
        Api.invoke("joinApp", JSONObject().put("app", "secret")) { r ->
            if (!r.ok) { done(); say(PR.errText(r, "Could not join right now."), true); return@invoke }
            load()
            done()
            say("You joined Reconnect.")
        }
    }

    fun doLogout() {
        PConfirm(pages.layer, "Log out?", "You can log in again anytime with your mobile number.", "Log out", onConfirm = { _ ->
            // off Explore at once, and this phone stops getting this account's notifications; then the web logs out
            pages.logout()
        })
    }

    fun doDelete() {
        PConfirm(pages.layer, "Delete your account?", "Your profile, photos, chats and coins will be removed forever. This cannot be undone.", "Delete",
            danger = true, typeToConfirm = "DELETE", onConfirm = { box ->
                Api.invoke("deleteAccount", JSONObject()) { r ->
                    if (r.ok) pages.logout(presence = false)
                    else { box.close(); say(PR.errText(r, "Could not delete your account. Please try again."), true) }
                }
            })
    }

    // ═════════════════ sheets & the photo viewer ═════════════════
    /** the profile photo (the round one): View, Change (Edit) and Remove (owner, 19 Nov: the profile photo could not be removed) */
    private fun avatarSheet() {
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val hasPhoto = avatarRaws.isNotEmpty()
        val main0 = mainPhoto()
        if (hasPhoto) col.addView(PUi.btn(ctx, "View photo", "light", Lu.Eye, 17f) { sheetClose?.invoke(); main.postDelayed({ viewAvatar() }, 10) }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { bottomMargin = Ui.dpi(10f) })
        col.addView(PUi.btn(ctx, "Change photo", "primary", Lu.Camera, 17f) { sheetClose?.invoke(); changeAvatar() }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        if (main0 != null) col.addView(PUi.btn(ctx, "Remove photo", "danger", Lu.Trash2, 17f) { sheetClose?.invoke(); removePhoto(main0) }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(10f) })
        sheetClose = PUi.sheet(pages.layer, "Profile photo", null, col) { sheetClose = null }
    }
    /** the approved main photo as a grid photo (null: none yet) */
    private fun mainPhoto(): Ph? {
        val pic = PR.s(profile, "profile_picture")
        if (pic.isEmpty()) return null
        return photos.firstOrNull { it.ref == pic && !it.pending } ?: Ph(pic, false, -1, false)
    }

    /** Long press on the profile photo: a pencil shows on top of it; the pencil opens Change / Remove (owner, 19 Nov). */
    private fun showPencil(av: FrameLayout) {
        if (av.findViewWithTag<View>("pencil") != null) return
        val ctx = context
        val pen = FrameLayout(ctx).apply {
            tag = "pencil"; contentDescription = "Edit profile photo"
            background = Tf.circle(PC.head, 2f, Color.WHITE)
            elevation = Ui.dp(6f)
            addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Pencil, Color.WHITE, 15f, 2.2f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { hidePencil(av); avatarSheet() }
            Ui.pressable(this, .95f)
        }
        // inside the photo's own square, at its top: nothing around it can cut it (owner, 19 Nov: it showed half cut)
        av.addView(pen, FrameLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pen.scaleX = 0.4f; pen.scaleY = 0.4f; pen.alpha = 0f
        pen.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
        main.postDelayed({ hidePencil(av) }, 5000)
    }
    private fun hidePencil(av: FrameLayout) {
        val pen = av.findViewWithTag<View>("pencil") ?: return
        pen.tag = null
        pen.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150).withEndAction { av.removeView(pen) }.start()
    }
    private fun viewAvatar() {
        viewerClose = PViewer.open(pages.layer, avatarRaws, "Profile photo", emptyList()) { viewerClose = null }
    }
    /** a photo full screen with what can be done with it: every photo — main, other, still in review — can be replaced or deleted */
    private fun viewPhoto(ph: Ph) {
        val acts = ArrayList<PViewer.Action>()
        if (ph.own && !ph.pending && ph.ref != PR.s(profile, "profile_picture")) acts.add(PViewer.Action("Make main", Lu.Star, false) { makeMain(ph) })
        acts.add(PViewer.Action("Replace", Lu.RefreshCw, false) { replacePhoto(ph) })
        acts.add(PViewer.Action("Delete", Lu.Trash2, true) { removePhoto(ph) })
        viewerClose = PViewer.open(pages.layer, listOf(ph.ref), if (ph.pending) "Photo in review" else "Photo", acts) { viewerClose = null }
    }
    private fun bioSheet() {
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val input = TfInput(ctx, "Write a few words about yourself", multiLine = true, lines = 4).apply { setText(PR.s(profile, "bio")); maxLen(PR.MAX_BIO); contentDescription = "Bio" }
        col.addView(input, LinearLayout.LayoutParams(-1, -2))
        val count = Tf.t(ctx, "", 12f, PC.sub, false).apply { gravity = Gravity.END }
        col.addView(count, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(6f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
        val upd = { count.text = "${input.value.length}/${PR.MAX_BIO}" }
        upd(); input.onText = { upd() }
        lateinit var save: TfBtn
        save = PUi.btn(ctx, "Save") {
            save.busy = true
            saveUpdates(JSONObject().put("bio", input.value.trim())) { _, err ->
                save.busy = false
                if (err == null) { sheetClose?.invoke(); sheetClose = null; say("Bio saved.") } else say(PR.errText(err, "Could not save your bio."), true)
            }
        }
        col.addView(save, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(10f) })
        sheetClose = PUi.sheet(pages.layer, "About you", "Numbers, links and social media IDs are not allowed.", col) { sheetClose = null }
        input.requestFocus()
        input.postDelayed({ try { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0) } catch (e: Exception) { } }, 380)
    }
    private fun interestSheet() {
        val ctx = context
        val keys = PR.INTERESTS.map { it.first }
        val sel = ArrayList(interests.filter { keys.contains(it) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var save: TfBtn
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cells = ArrayList<() -> Unit>()
        fun label() = save.label("Save · ${sel.size} selected")
        for (rowStart in PR.INTERESTS.indices step 2) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in rowStart until min(rowStart + 2, PR.INTERESTS.size)) {
                val (key, inner) = PR.INTERESTS[j]
                val cell = FrameLayout(ctx)
                val inRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0) }
                val ic = FrameLayout(ctx)
                val icon = ImageView(ctx)
                ic.addView(icon, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                inRow.addView(ic, LinearLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f)).apply { rightMargin = Ui.dpi(10f) })
                inRow.addView(Tf.t(ctx, key, 13.5f, PC.ink).ell(), LinearLayout.LayoutParams(0, -2, 1f))
                cell.addView(inRow, FrameLayout.LayoutParams(-1, -1))
                val tick = FrameLayout(ctx).apply { background = Tf.circle(PC.wine) }
                tick.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Check, Color.WHITE, 11f, 3.2f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                cell.addView(tick, FrameLayout.LayoutParams(Ui.dpi(17f), Ui.dpi(17f), Gravity.END or Gravity.TOP).apply { topMargin = Ui.dpi(5f); rightMargin = Ui.dpi(5f) })
                val paint = {
                    val on = sel.contains(key)
                    cell.background = Tf.box(if (on) Ui.c("#F4EEFC") else Color.WHITE, 13f, 1.5f, if (on) PC.wine else PC.line)
                    ic.background = Tf.box(if (on) PC.wine else PC.faint, 9f)
                    icon.setImageDrawable(PUi.lu(inner, if (on) Color.WHITE else PC.plum, 16f, 2.1f))
                    tick.visibility = if (on) VISIBLE else GONE
                    cell.contentDescription = key + if (on) ", selected" else ""
                }
                paint(); cells.add(paint)
                cell.setOnClickListener { if (sel.contains(key)) sel.remove(key) else sel.add(key); paint(); label() }
                Ui.pressable(cell, .97f)
                row.addView(cell, LinearLayout.LayoutParams(0, Ui.dpi(48f), 1f).apply { if (j > rowStart) leftMargin = Ui.dpi(10f) })
            }
            if (row.childCount == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, Ui.dpi(48f), 1f).apply { leftMargin = Ui.dpi(10f) })
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rowStart > 0) topMargin = Ui.dpi(10f) })
        }
        col.addView(grid)
        save = PUi.btn(ctx, "") {
            save.busy = true
            saveUpdates(JSONObject().put("hobbies", JSONArray(sel))) { _, err ->
                save.busy = false
                if (err == null) { sheetClose?.invoke(); sheetClose = null; say("Interests saved.") } else say(PR.errText(err, "Could not save your interests."), true)
            }
        }
        label()
        col.addView(save, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(16f) })
        sheetClose = PUi.sheet(pages.layer, "Choose your interests", "Tap to select. Tap again to remove.", col) { sheetClose = null }
    }

    // ═════════════════ drawing the page ═════════════════
    private fun renderCenter() {
        if (profile != null || !shown) return
        center.removeAllViews()
        center.visibility = VISIBLE
        val ctx = context
        if (loading) { center.addView(TfLoader(ctx, 30f, PC.wine), LinearLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f))); return }
        center.addView(PUi.wordmark(ctx, 24f), LinearLayout.LayoutParams(-2, -2))
        center.addView(Tf.t(ctx, loadError.ifEmpty { "Your profile is not set up yet." }, 14f, PC.sub, false).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(14f) })
        val b = if (loadError.isNotEmpty()) PUi.btn(ctx, "Try again", "primary", Lu.RefreshCw, 17f) { load() } else PUi.btn(ctx, "Complete my profile") { pages.go("/welcome") }
        center.addView(b, LinearLayout.LayoutParams(min(Ui.dpi(280f), resources.displayMetrics.widthPixels - Ui.dpi(48f)), Ui.dpi(48f)).apply { topMargin = Ui.dpi(14f) })
    }

    private fun cardBox(radius: Float = 18f) = Tf.box(Color.WHITE, radius, 1f, PC.line)

    // ═════════════════ 28 Nov (owner): the page laid out like Blinkit's "Your account", in light purple ═════════════════
    private val TOP = Ui.c("#D9CCFA")
    private val PAGE = Ui.c("#F4F2FA")
    private val INK = Ui.c("#111111")
    private val GREY = Ui.c("#6B6880")
    private var photoSheetBox: LinearLayout? = null
    private var photoSheetClose: (() -> Unit)? = null
    /** the bar that stays at the top once the name has scrolled away (Blinkit: "← Profile") */
    private val stickBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Ui.c("#D9CCFA")); elevation = Ui.dp(3f); alpha = 0f; visibility = GONE
        setPadding(Ui.dpi(14f), Ui.dpi(8f), Ui.dpi(14f), Ui.dpi(8f)); isClickable = true
    }
    private var nameBottom = Ui.dpi(260f)

    private fun roundBack(): View = FrameLayout(context).apply {
        background = Tf.circle(Color.WHITE)
        elevation = Ui.dp(1.5f)
        contentDescription = "Back"
        addView(ImageView(context).apply { setImageDrawable(PUi.lu(Lu.ArrowLeft, INK, 20f, 2f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { pages.historyBack() }
        Ui.pressable(this, .94f)
    }

    private fun setupStick() {
        if (stickBar.parent != null) return
        stickBar.addView(roundBack(), LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f)))
        stickBar.addView(Tf.t(context, "Profile", 17f, INK), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dpi(14f) })
        addView(stickBar, LayoutParams(-1, -2, Gravity.TOP))
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val on = scroll.scrollY > nameBottom
            if (on && stickBar.visibility != VISIBLE) { stickBar.visibility = VISIBLE; stickBar.animate().alpha(1f).setDuration(160).start() }
            else if (!on && stickBar.visibility == VISIBLE && stickBar.alpha > 0f) stickBar.animate().alpha(0f).setDuration(140).withEndAction { if (scroll.scrollY <= nameBottom) stickBar.visibility = GONE }.start()
        }
    }

    /** one line of a list: icon, words, (value), arrow — it sinks a little under the finger (the owner's Blinkit video) */
    private fun listRow(icon: String, label: String, value: String = "", valueColor: Int = GREY, arrow: Boolean = true, onClick: (() -> Unit)? = null): LinearLayout {
        val ctx = context
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dpi(16f), 0, Ui.dpi(14f), 0); minimumHeight = Ui.dpi(52f)
            background = Tf.box(Color.WHITE, 12f)
            contentDescription = if (value.isNotEmpty()) "$label: $value" else label
        }
        r.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(icon, INK, 21f, 1.7f)) }, LinearLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f)).apply { rightMargin = Ui.dpi(14f) })
        r.addView(Tf.t(ctx, label, 15f, INK, false).lineH(20f).ell(), LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotEmpty()) r.addView(Tf.t(ctx, value, 13.5f, valueColor, valueColor != GREY).lineH(18f).ell(), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dpi(8f); rightMargin = Ui.dpi(if (arrow) 6f else 2f) })
        if (arrow) r.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.ChevronRight, Ui.c("#9C98A8"), 18f, 2f)) }, LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)))
        if (onClick != null) { r.setOnClickListener { onClick() }; Ui.pressable(r, .96f) }
        return r
    }

    /** a white box with a title and lines (thin lines between them) */
    private fun listBox(title: String, rows: List<View>): LinearLayout {
        val ctx = context
        val b = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 16f); clipChildren = false }
        if (title.isNotEmpty()) b.addView(Tf.t(ctx, title, 17f, INK).lineH(22f), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = Ui.dpi(16f); topMargin = Ui.dpi(16f); bottomMargin = Ui.dpi(10f) })
        rows.forEachIndexed { i, v ->
            if (i > 0 || title.isNotEmpty()) b.addView(View(ctx).apply { setBackgroundColor(Ui.c("#F0EDF6")) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
            b.addView(v, LinearLayout.LayoutParams(-1, Ui.dpi(52f)))
        }
        return b
    }

    fun renderPage() {
        val p = profile ?: return
        val ctx = context
        center.visibility = GONE
        setupStick()
        pageCol.removeAllViews()
        pageCol.setBackgroundColor(PAGE)

        // ── the top: light purple fading into the page, round back button, big photo, name, Online · place ──
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(TOP, Ui.c("#E9E1FC"), PAGE))
            setPadding(Ui.dpi(16f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(18f))
            clipChildren = false
        }
        val bar = FrameLayout(ctx)
        bar.addView(roundBack(), FrameLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(42f), Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(bar, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))

        val av = FrameLayout(ctx).apply { contentDescription = "Profile photo"; clipChildren = false }
        val ring = FrameLayout(ctx).apply { background = Tf.circle(Color.WHITE); setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)) }
        val face = PImg(ctx, round = true).apply { placeholder = Color.WHITE }
        face.set(avatarRaws)
        ring.addView(face, FrameLayout.LayoutParams(-1, -1))
        av.addView(ring, FrameLayout.LayoutParams(Ui.dpi(104f), Ui.dpi(104f)))
        val cam = FrameLayout(ctx).apply { background = Tf.circle(PC.head, 2f, Color.WHITE) }
        if (uploadingAvatar) cam.addView(TfLoader(ctx, 12f, Color.WHITE), FrameLayout.LayoutParams(Ui.dpi(12f), Ui.dpi(12f), Gravity.CENTER))
        else cam.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Camera, Color.WHITE, 14f, 2.1f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        av.addView(cam, FrameLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = Ui.dpi(2f); bottomMargin = Ui.dpi(4f) })
        av.setOnClickListener { avatarSheet() }
        val peekRaws = avatarRaws.ifEmpty { listOfNotNull(photos.firstOrNull { it.pending }?.ref?.takeIf { it.isNotBlank() }) }
        PhotoPeek.attach(av, peekRaws, { face.bitmap }, this@ProfileScreen, .97f, onEmpty = { showPencil(av) }, onClosed = { showPencil(av) })
        top.addView(av, LinearLayout.LayoutParams(Ui.dpi(104f), Ui.dpi(104f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -Ui.dpi(6f) })
        top.addView(Tf.t(ctx, name.ifEmpty { "Your name" }, 24f, INK).lineH(30f).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(10f) })
        val loc = locationText
        val sub = Tf.t(ctx, listOf("Online", loc).filter { it.isNotEmpty() }.joinToString(" · "), 13.5f, Ui.c("#5F5673"), false).lineH(18f).apply {
            gravity = Gravity.CENTER; compoundDrawablePadding = Ui.dpi(6f)
            setCompoundDrawablesWithIntrinsicBounds(DotDrawable(PC.online, 7f), null, null, null)
        }
        top.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dpi(4f) })
        pageCol.addView(top, LinearLayout.LayoutParams(-1, -2))
        top.post { nameBottom = (top.bottom - Ui.dpi(40f)).coerceAtLeast(Ui.dpi(120f)) }

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), Ui.dpi(20f)); clipChildren = false }

        // ── the light purple banner: the bio (Blinkit's "Add your birthday") ──
        val bioText = PR.s(p, "bio")
        val ban = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#F1EAFF"), Ui.c("#E4D7FC"))).apply { cornerRadius = Ui.dp(16f) }
            setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f))
            contentDescription = if (bioText.isEmpty()) "Add your bio" else "Your bio: $bioText. Edit"
            setOnClickListener { bioSheet() }; Ui.pressable(this, .97f)
        }
        val bc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bc.addView(Tf.t(ctx, if (bioText.isEmpty()) "Add your bio" else "Your bio", 15.5f, INK).lineH(20f))
        if (bioText.isNotEmpty()) bc.addView(Tf.t(ctx, bioText, 13.5f, Ui.c("#4A4560"), false).lineH(18f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(3f) })
        bc.addView(Tf.t(ctx, if (bioText.isEmpty()) "Write a few words ▸" else "Edit ▸", 13.5f, PC.head).lineH(18f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(5f) })
        ban.addView(bc, LinearLayout.LayoutParams(0, -2, 1f))
        ban.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Pencil, Ui.c("#8B5CF6"), 34f, 1.5f, "#EDE5FE")) }, LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f)).apply { leftMargin = Ui.dpi(10f) })
        body.addView(ban, LinearLayout.LayoutParams(-1, -2))

        // ── three tiles: Edit Profile, Share Profile, Dashboard ──
        val tiles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        listOf(Triple(Lu.Pencil, "Edit Profile") { openEdit() }, Triple(Lu.Share2, "Share Profile") { share() }, Triple(Lu.LayoutDashboard, "Dashboard") { pages.go("/dashboard") }).forEachIndexed { i, (ic, t, go) ->
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = Tf.box(Color.WHITE, 16f); setPadding(Ui.dpi(4f), Ui.dpi(14f), Ui.dpi(4f), Ui.dpi(12f))
                contentDescription = t; setOnClickListener { go() }; Ui.pressable(this, .95f)
            }
            tile.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(ic, INK, 30f, 1.5f, "#F3EFFC")) }, LinearLayout.LayoutParams(Ui.dpi(34f), Ui.dpi(34f)).apply { bottomMargin = Ui.dpi(8f) })
            tile.addView(Tf.t(ctx, t, 14f, INK, false).lineH(18f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            tiles.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = Ui.dpi(9f) })
        }
        body.addView(tiles, gap(12f))

        // ── Followers · Following, then Templates (outlined, NEW) ──
        val fol = followCounts?.get(0) ?: PR.countOf(p, listOf("followers_count", "followers"))
        val fwg = followCounts?.get(1) ?: PR.countOf(p, listOf("following_count", "following"))
        val fr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; background = Tf.box(Color.WHITE, 16f) }
        listOf(Triple("followers", "Followers", fol), Triple("following", "Following", fwg)).forEachIndexed { i, (id, text, n) ->
            if (i == 1) fr.addView(View(ctx).apply { setBackgroundColor(Ui.c("#F0EDF6")) }, LinearLayout.LayoutParams(Ui.dpi(1f), -1).apply { topMargin = Ui.dpi(12f); bottomMargin = Ui.dpi(12f) })
            val c = listRow(if (i == 0) Lu.Users else Lu.User, text, n.toString(), PC.head) { openPeople(id) }
            fr.addView(c, LinearLayout.LayoutParams(0, -1, 1f))
        }
        body.addView(fr, LinearLayout.LayoutParams(-1, Ui.dpi(52f)).apply { topMargin = Ui.dpi(12f) })
        val tpl = listRow(Lu.Crown, "Templates") { openShopAll("template") }
        tpl.background = Tf.box(Color.WHITE, 16f, 1.5f, Ui.c("#6C2BD9"))
        tpl.addView(Tf.t(ctx, "NEW", 11.5f, Color.WHITE).apply { gravity = Gravity.CENTER; background = Tf.box(PC.head, 6f); setPadding(Ui.dpi(8f), 0, Ui.dpi(8f), 0) },
            tpl.childCount - 1, LinearLayout.LayoutParams(-2, Ui.dpi(22f)).apply { rightMargin = Ui.dpi(8f) })
        body.addView(tpl, LinearLayout.LayoutParams(-1, Ui.dpi(52f)).apply { topMargin = Ui.dpi(12f) })

        // ── Your information ──
        val ph = photos
        val vi = vInfo
        val (vt, vfg) = if (vi.verified) "Verified" to PC.nGreen else if (vi.pending) "In review" to PC.amber else "Not verified" to GREY
        val mine = interests
        body.addView(listBox("Your information", listOf(
            listRow(Lu.Image, "Photos", "${ph.size} / ${PR.MAX_PHOTOS}") { photoSheet() },
            listRow(Lu.User, "Avatar") { openShopAll("avatar") },
            listRow(Lu.Heart, "Interests", if (mine.isEmpty()) "" else mine.size.toString()) { interestSheet() },
            listRow(Lu.MapPin, "Location", loc) { openEdit() },
            listRow(Lu.CalendarDays, "Joined", PR.fmtDate(joinedAt).ifEmpty { "—" }, arrow = false),
            listRow(Lu.ShieldCheck, "Verification", vt, vfg) { openDrawer("verification") },
            listRow(Lu.Settings, "Settings") { openDrawer(null) },
        )), gap(12f))

        // ── Other information (owner's second Blinkit picture) ──
        body.addView(listBox("Other information", listOf(
            listRow(Lu.Share2, "Share the app") { openPanel("refer") },
            listRow(Lu.Star, "Become a creator") { openPanel("apply") },
            listRow(Lu.Lock, "Account privacy") { openPanel("privacy") },
            listRow(Lu.Bell, "Notification preferences") { openPanel("notifications") },
            listRow(Lu.HelpCircle, "Help & support") { openPanel("help") },
            listRow(Lu.Info, "Terms & policies") { openPanel("legal") },
        )), gap(12f))
        body.addView(listBox("", listOf(listRow(Lu.LogOut, "Log out") { openPanel("logout") })), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(20f) })

        // ── True Find + the version, grey, at the very bottom ──
        val foot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        foot.addView(Tf.t(ctx, "True Find", 30f, Ui.c("#B9B4C6")).lineH(36f))
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        if (ver.isNotEmpty()) foot.addView(Tf.t(ctx, "v$ver", 14f, Ui.c("#B9B4C6")).lineH(18f))
        body.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(40f); bottomMargin = Ui.dpi(20f) })

        pageCol.addView(body, LinearLayout.LayoutParams(-1, -2))
        pageCol.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
        fillPhotoSheet()
    }

    private fun gap(dp: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(dp) }

    /** Photos (1 big + 4 small, the "only real photos" note) — now in a sheet from the "Photos" line */
    private fun photoSheet() {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        photoSheetBox = box
        fillPhotoSheet()
        photoSheetClose = PUi.sheet(pages.layer, "Photos", "${photos.size} / ${PR.MAX_PHOTOS} · the first one is your main photo", box) { photoSheetBox = null; photoSheetClose = null }
    }

    private fun fillPhotoSheet() {
        val box = photoSheetBox ?: return
        val ctx = context
        box.removeAllViews()
        val ph = photos
        val grid = PhotoGrid(ctx)
        for (i in 0 until PR.MAX_PHOTOS) grid.addView(photoCell(ph.getOrNull(i), i, ph.size))
        box.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val note = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dpi(12f), 0, 0) }
        note.addView(PUi.icon(ctx, PUi.lu(Lu.ShieldCheck, PC.nGreen, 16f), 16f), LinearLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f)).apply { rightMargin = Ui.dpi(10f); topMargin = Ui.dpi(1f) })
        note.addView(PUi.para(ctx, "Only real photos of you. Our team checks every new photo before others can see it. AI-made or fake photos are rejected.", 12.5f, PC.sub, 17f), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(note)
    }

    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f) }

    /** a view of [hDp] height with [v] centred in it (a 44 tap area around a smaller pill) */
    private fun wrapH(v: View, hDp: Float): FrameLayout = FrameLayout(context).apply {
        val lp = v.layoutParams
        addView(v, FrameLayout.LayoutParams(lp?.width ?: -2, lp?.height ?: -2, Gravity.CENTER_VERTICAL))
        minimumHeight = Ui.dpi(hDp)
        layoutParams = ViewGroup.LayoutParams(-2, Ui.dpi(hDp))
    }

    private fun flatBtn(label: String, inner: String, bg: Int, fg: Int, onClick: () -> Unit): View = PUi.iconText(context, label, inner, 15f, fg, 16f, 8f, Tf.box(bg, 12f)).apply {
        setOnClickListener { onClick() }; Ui.pressable(this, .97f)
    }

    private fun infoCard(inner: String, tileBg: Int, tileFg: Int, label: String, value: String, valueColor: Int, onClick: (() -> Unit)?): LinearLayout {
        val ctx = context
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = cardBox(16f); setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0) }
        val t = FrameLayout(ctx).apply { background = Tf.box(tileBg, 9f) }
        t.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(inner, tileFg, 16f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        c.addView(t, LinearLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f)).apply { rightMargin = Ui.dpi(10f) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Tf.t(ctx, label, 12.5f, PC.sub, false).lineH(16f))
        col.addView(Tf.t(ctx, value, 14.5f, valueColor).lineH(19f).ell())
        c.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (onClick != null) { c.setOnClickListener { onClick() }; Ui.pressable(c, .97f) }
        return c
    }

    // ═════════════════ Avatar and Templates (owner, 19 Nov evening) ═════════════════
    // Only "Avatar" on the box (no "Real photo / Avatar · soon", no "soon" anywhere); both rows scroll sideways,
    // "See all" opens a page with all of them, tapping one opens the Buy sheet (Wallet-like, "Buy for N coins").
    // What is for sale comes from the server (the shop function); with nothing for sale yet the empty places show.
    private fun avatarSpace(): View = shopSpace("avatar")
    private fun templateSpace(): View = shopSpace("template")
    private fun shopSpace(kind: String): View {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = cardBox(); setPadding(Ui.dpi(14f), Ui.dpi(8f), Ui.dpi(14f), Ui.dpi(14f)) }
        shopBoxes[kind] = c
        fillShopBox(kind, c)
        return c
    }
    private fun fillShopBox(kind: String, c: LinearLayout) {
        val ctx = context
        c.removeAllViews()
        val list = shopItems.filter { it.kind == kind }
        val avatar = kind == "avatar"
        c.addView(PUi.secHead(ctx, if (avatar) Lu.User else Lu.Crown, if (avatar) "Avatar" else "Templates", if (list.isNotEmpty()) ShopUi.seeAll(ctx) { openShopAll(kind) } else null))
        if (!avatar && list.isEmpty()) c.addView(PUi.para(ctx, "Premium frames for your profile photo will show here.", 12.5f, PC.sub, 17f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(2f) })
        c.addView(ShopUi.row(ctx, kind, list) { openShopBuy(it) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(if (list.isEmpty()) 10f else 4f) })
    }
    private fun refreshShop() {
        for ((k, c) in shopBoxes) if (c.isAttachedToWindow) fillShopBox(k, c)
        shopPage?.refresh()
    }
    /** what is for sale + my coins (on the first load and on every visit: coins may have been added in the Wallet) */
    fun loadShop() {
        val my = ++shopSeq
        Api.invoke("shop", JSONObject().put("action", "list")) { r ->
            if (my != shopSeq || !r.ok || !r.data.optBoolean("ok")) return@invoke
            shopItems = ShopData.items(r.data)
            shopCoins = ShopData.coins(r.data)
            refreshShop()
        }
    }
    private fun openShopAll(kind: String) {
        shopPageClose?.invoke()
        val page = ShopAllPage(context, kind, { shopItems }, { shopCoins }, { openShopBuy(it) }) { shopPageClose?.invoke() }
        shopPage = page
        shopPageClose = pages.layer.page(page, z = 12f) { shopPage = null; shopPageClose = null }
    }
    private fun openShopBuy(item: ShopItemV) {
        shopSheetClose?.invoke()
        shopSheetClose = ShopBuySheet.open(pages.layer, item, shopCoins,
            buy = { tap, done ->
                Api.invoke("shop", JSONObject().put("action", "buy").put("item_id", item.id).put("client_buy_id", tap)) { r ->
                    val res = ShopData.buy(r.status, r.data)
                    if (res is ShopData.Buy.Short) shopCoins = res.coins ?: shopCoins
                    done(res)
                }
            },
            addCoins = { pages.go("/wallet") },
            bought = { got, coins ->
                shopItems = shopItems.map { x -> if (x.id == got.id) ShopItemV(x.id, x.kind, x.name, x.art, x.price, true) else x }
                if (coins != null) shopCoins = coins
                refreshShop()
                say("Bought! It's yours now.")
            })
    }

    private fun photoCell(ph: Ph?, i: Int, count: Int): View {
        val ctx = context
        val big = i == 0
        val r = if (big) 14f else 12f
        if (ph == null) {
            val next = i == count
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = Tf.dashedBox(if (big) Ui.c("#F6F1FD") else Ui.c("#FBF9FE"), r, 1.5f, if (big) PC.dash else PC.dashSoft)
                contentDescription = if (big) "Add main photo" else "Add photo"
                setOnClickListener { if (!uploading) addPhoto() }
                Ui.pressable(this, .97f)
            }
            val busy = uploading && next && replacing.isEmpty()
            if (big) {
                val t = FrameLayout(ctx).apply { background = Tf.box(PC.head, 12f) }
                if (busy) t.addView(TfLoader(ctx, 18f, Color.WHITE), FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
                else t.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Plus, Color.WHITE, 20f, 2.2f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                cell.addView(t, LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f)).apply { bottomMargin = Ui.dpi(9f) })
                cell.addView(Tf.t(ctx, if (busy) "Uploading…" else "Add main photo", 14f, PC.head).lineH(18f).apply { gravity = Gravity.CENTER })
                cell.addView(Tf.t(ctx, "${PR.MAX_PHOTOS - count} left", 12.5f, PC.sub, false).lineH(16f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(3f) })
            } else {
                if (busy) cell.addView(TfLoader(ctx, 18f, PC.wine), LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)))
                else cell.addView(PUi.icon(ctx, PUi.lu(Lu.Plus, PC.headBtn, 18f, 2.2f), 18f))
            }
            return cell
        }
        val cell = FrameLayout(ctx).apply {
            background = Tf.box(PC.tint, r); clipToOutline = true
            contentDescription = if (big) "Main photo" else "Photo ${i + 1}"
            setOnClickListener { if (!uploading) viewPhoto(ph) }
            Ui.pressable(this, .97f)
        }
        val swapping = replacing.isNotEmpty() && replacing == ph.ref
        val img = PImg(ctx, r).apply { empty = "none"; alpha = if (ph.pending || swapping) 0.6f else 1f }
        val spin = TfLoader(ctx, 18f, PC.wine)
        cell.addView(img, FrameLayout.LayoutParams(-1, -1))
        cell.addView(spin, FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        img.onLoaded = { ok -> spin.visibility = if (ok && !swapping) GONE else spin.visibility }
        img.set(listOf(ph.ref))
        if (img.hasPhoto && !swapping) spin.visibility = GONE
        // hold: the photo big, like Instagram (owner, 21 Nov); a tap still opens it with Make main / Replace / Delete
        PhotoPeek.attach(cell, listOf(ph.ref), { img.bitmap }, this@ProfileScreen, .97f)
        if (ph.pending) {
            val badge = Tf.t(ctx, "In review", if (big) 10.5f else 10f, PC.amber).apply {
                gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 6f); maxLines = 1
                if (big) { setPadding(Ui.dpi(7f), 0, Ui.dpi(7f), 0); compoundDrawablePadding = Ui.dpi(3f); setCompoundDrawablesWithIntrinsicBounds(PUi.lu(Lu.Clock, PC.amber, 10f, 2.6f), null, null, null) }
            }
            cell.addView(badge, if (big) FrameLayout.LayoutParams(-2, Ui.dpi(20f), Gravity.END or Gravity.TOP).apply { topMargin = Ui.dpi(6f); rightMargin = Ui.dpi(6f) }
                else FrameLayout.LayoutParams(-1, Ui.dpi(20f), Gravity.TOP).apply { topMargin = Ui.dpi(4f); leftMargin = Ui.dpi(4f); rightMargin = Ui.dpi(4f) })
        }
        if (big) {
            val m = Tf.t(ctx, "Main", 11.5f, Color.WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL; background = Tf.box(PC.head, 7f); setPadding(Ui.dpi(7f), 0, Ui.dpi(9f), 0)
                compoundDrawablePadding = Ui.dpi(4f); setCompoundDrawablesWithIntrinsicBounds(PUi.lu(Lu.Star, Color.WHITE, 11f, 0f, "#fff"), null, null, null)
            }
            cell.addView(m, FrameLayout.LayoutParams(-2, Ui.dpi(22f), Gravity.START or Gravity.BOTTOM).apply { leftMargin = Ui.dpi(8f); bottomMargin = Ui.dpi(8f) })
        }
        return cell
    }

    /**
     * the 1 big + 4 small photo grid, two rows, gap 8. Every place is a square (owner, 19 Nov: the main photo's
     * circle was cut at its sides — the big place was taller than wide): the big one is as tall as the two rows.
     */
    private class PhotoGrid(ctx: Context) : ViewGroup(ctx) {
        private val g = Ui.dpi(8f)
        private fun cols(w: Int): IntArray { val a = w - 2 * g; val c = (a - g) / 4; val c0 = a - 2 * c; return intArrayOf(c0, c, a - c0 - c) }
        override fun onMeasure(ws: Int, hs: Int) {
            val w = MeasureSpec.getSize(ws)
            val h = if (MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY) MeasureSpec.getSize(hs) else cols(w)[0]
            val c = cols(w); val rh = (h - g) / 2
            for (i in 0 until childCount) {
                val cw = when (i) { 0 -> c[0]; 1, 3 -> c[1]; else -> c[2] }
                val ch = if (i == 0) h else rh
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l; val h = b - t; val c = cols(w); val rh = (h - g) / 2
            val x1 = c[0] + g; val x2 = x1 + c[1] + g
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                val (x, y) = when (i) { 0 -> 0 to 0; 1 -> x1 to 0; 2 -> x2 to 0; 3 -> x1 to rh + g; else -> x2 to rh + g }
                v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
            }
        }
    }

    // ═════════════════ test screens (emulator only) ═════════════════
    fun previewOpen(what: String) {
        when (what) {
            "avatar" -> avatarSheet()
            "bio" -> bioSheet()
            "interests" -> interestSheet()
            "viewer" -> photos.firstOrNull()?.let { viewPhoto(it) }
            "logout" -> doLogout()
            "delete" -> doDelete()
            "toast" -> say("Photo added. Our team will check it soon.")
            "scroll" -> scroll.scrollTo(0, pageCol.height)
            "mid" -> scroll.scrollTo(0, Ui.dpi(560f))
            "photos" -> photoSheet()
            // hold on the main photo / on the profile photo (the big photo stays: no finger is lifted)
            "peek", "peek-avatar" -> {
                val found = ArrayList<View>()
                pageCol.findViewsWithText(found, if (what == "peek") "Main photo" else "Profile photo", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                found.firstOrNull()?.performLongClick()
            }
            else -> drawer?.previewOpen(what) ?: edit?.previewOpen(what) ?: people?.previewOpen(what)
        }
    }

    @Suppress("unused") private fun keepTv(t: TextView) = t
}
