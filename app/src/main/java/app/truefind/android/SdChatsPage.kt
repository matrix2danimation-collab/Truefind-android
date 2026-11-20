package app.truefind.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * SECRET DATING · Chat (/secret-app?tab=chat) — your private chats, newest first, live — in the look of the owner's
 * photos (20 Nov night): the maroon top ("Good Evening, name 👋", the bell, "Chat" with how many unread), the black
 * strip (All · Unread · Online), a white sheet with the rows — no lines between them; bold name, light message;
 * one grey tick = sent, two green ticks = seen; a green dot = online. Tap a row → the chat, tap a photo → their
 * profile. Long-press a row → Pin chat · Delete chat · Block; swipe a row left → the red bin (Delete).
 * No credits here any more (owner, 20 Nov night). Search by name. The plan lock stays as before.
 */
@SuppressLint("ViewConstructor")
class SdChatsPage(private val h: SecretHome) : FrameLayout(h.context) {
    class Chat(val id: String, val name: String, val photo: String, val photoThumb: String, val age: String, val pid: String, val uid: String,
               val online: Boolean, val verified: Boolean, val text: String, val from: String, val time: String, var unread: Int,
               /** opened once (a paid request, or a message with a plan): opens without a plan, for good (owner, 20 Nov) */
               val open: Boolean = false,
               /** the last message was mine (null = not known) · and the other side has seen it */
               val mine: Boolean? = null, val read: Boolean = false)

    private val top = SdTop(context, h, "Chat", bell = true, extra = TextView(context))
    private val unreadCircle = top.extra as TextView
    private val tabs = SdTabs(context, listOf("All", "Unread", "Online"), 106f) { i -> filter = i; closeOpen(false); renderList() }
    private var filter = 0
    private val scroll = ScrollView(context)
    private val col = vbox(context)
    private val lockBox = LinearLayout(context)
    private val lockTitle = jk(context, "", 15f, SD.M, 700)
    private val lockSub = jk(context, "", 12.5f, SD.SUB, 500)
    private val lockIcon = ImageView(context)
    private val searchBox = LinearLayout(context)
    private val search = EditText(context)
    private val clear = ImageView(context)
    private val listBox = vbox(context)
    private var q = ""
    private val prefs = context.getSharedPreferences("sd", 0)
    /** chats pinned to the top (this phone only) */
    private val pins: MutableSet<String> = HashSet(prefs.getStringSet("sd_pins", emptySet()) ?: emptySet())

    var chats: List<Chat>? = null; private set
    /** test shots: scroll to the end, or search, swipe a row, the long-press menu */
    fun preview(what: String) {
        when (what) {
            "scroll" -> scroll.scrollTo(0, scroll.getChildAt(0).height)
            "search" -> search.setText("si")
            "swipe" -> (listBox.getChildAt(0) as? SwipeRow)?.open(true)
            "menu" -> chats?.firstOrNull()?.let { options(it) }
            "unread" -> { tabs.pick(1); filter = 1; renderList() }
        }
    }
    var failed = false; private set

    init {
        setBackgroundColor(SD.M)
        addView(MaroonDeco(context), LayoutParams(-1, -1))
        unreadCircle.apply {
            typeface = Jk.of(600); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(12.5f)); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = oval(SD.STRIP); includeFontPadding = false; visibility = GONE
            layoutParams = LinearLayout.LayoutParams(dpi(32f), dpi(32f)).apply { leftMargin = dpi(14f) }
        }
        val inset = InsetFrame(context)
        addView(inset, LayoutParams(-1, -1))
        inset.addView(top, LayoutParams(-1, -2))
        val strip = FrameLayout(context).apply { background = SD.stripBg() }
        strip.addView(tabs, LayoutParams(-1, -2))
        inset.addView(strip, LayoutParams(-1, -1).apply { topMargin = dpi(SD.STRIP_TOP) })
        val sheet = FrameLayout(context).apply { background = SD.sheetBg(); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isFillViewport = true
        sheet.addView(scroll, LayoutParams(-1, -1))
        sheet.addView(sdHandle(context))
        col.setPadding(0, dpi(28f), 0, dpi(24f))
        scroll.addView(col, LayoutParams(-1, -2))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> closeOpen(true) }
        inset.addView(sheet, LayoutParams(-1, -1).apply { topMargin = dpi(SD.SHEET_TOP) })

        // "Chat is Premium" / "Verify to chat free" (the same lock as before, in the new colours)
        lockBox.orientation = LinearLayout.HORIZONTAL; lockBox.gravity = Gravity.CENTER_VERTICAL
        lockBox.background = rounded(SD.M_TINT, 18f)
        lockBox.setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
        lockIcon.scaleType = ImageView.ScaleType.CENTER; lockIcon.background = oval(SD.M)
        lockBox.addView(lockIcon, lp(dpi(42f), dpi(42f), r = 12f))
        val lt = vbox(context); lt.addView(lockTitle); lt.addView(lockSub, lp(WRAP, WRAP, t = 2f))
        lockBox.addView(lt, lp(0, WRAP, 1f))
        lockBox.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, SD.M, 20f, 2.4f)) }, lp(dpi(20f), dpi(20f), l = 8f))
        lockBox.press().onTap { h.locked("chat") }
        col.addView(lockBox, lp(MATCH, WRAP, l = 16f, r = 16f, b = 12f))

        // search by name
        searchBox.orientation = LinearLayout.HORIZONTAL; searchBox.gravity = Gravity.CENTER_VERTICAL
        searchBox.background = rounded(SD.FIELD, 22f)
        searchBox.setPadding(dpi(16f), 0, dpi(6f), 0)
        searchBox.addView(sdIconView(context, SdIcon(listOf(SdPaths.SEARCH to Ui.c("#A5A5AD")), 18f, 1.9f)), lp(dpi(18f), dpi(18f), r = 10f))
        search.hint = "Search by name"; search.setHintTextColor(Ui.c("#A5A5AD")); search.setTextColor(SD.TXT); search.typeface = Jk.of(500)
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(14f)); search.background = null; search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH; search.setPadding(0, 0, 0, 0)
        search.contentDescription = "Search chats"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { q = s.toString(); clear.visibility = if (q.isEmpty()) GONE else VISIBLE; renderList() }
        })
        searchBox.addView(search, lp(0, dpi(44f), 1f))
        clear.setImageDrawable(SdIcon(listOf(SdPaths.X to SD.SUB), 16f, 2f)); clear.scaleType = ImageView.ScaleType.CENTER; clear.visibility = GONE
        clear.contentDescription = "Clear search"
        clear.onTap { search.setText("") }
        searchBox.addView(clear, lp(dpi(32f), dpi(32f)))
        col.addView(searchBox, lp(MATCH, dpi(44f), l = 16f, r = 16f, b = 8f))

        col.addView(listBox, lp(MATCH, WRAP))
        val foot = jk(context, "Numbers, links and IDs are never shared in chat.", 12f, SD.SUB, 500).apply {
            gravity = Gravity.CENTER_VERTICAL; icon(SdIcon(listOf(SdPaths.LOCK to SD.M), 13f, 2f), 6f)
        }
        col.addView(foot, lp(WRAP, WRAP, t = 14f, gravity = Gravity.CENTER_HORIZONTAL))
    }

    // ═════════════ data: getChats (all), live ═════════════
    private var loadingNow = false
    /** pulled down to refresh: told when the list is back */
    private val waiting = ArrayList<() -> Unit>()
    fun load(retried: Boolean = false, done: (() -> Unit)? = null) {
        done?.let { waiting.add(it) }
        if (loadingNow && !retried) return
        loadingNow = true
        Api.invoke("getChats", JSONObject()) { r ->
            val arr = r.data.optJSONArray("chats") ?: r.data.optJSONArray("items")
            if (!r.ok || arr == null) {
                // one quiet retry first (right after opening the app the first call can fail), then show the error
                if (!retried) { postDelayed({ load(true) }, 2500); return@invoke }
                loadingNow = false
                failed = true; if (chats == null) chats = emptyList(); render(); told(); return@invoke
            }
            loadingNow = false
            // each chat opens at once from here (ChatCache)
            ChatCache.putChats(arr)
            chats = list(arr).map { norm(it) }.filter { it.id.isNotEmpty() }.sortedByDescending { SdRules.ms(it.time) }
            failed = false
            render()
            told()
        }
    }
    private fun told() { val w = ArrayList(waiting); waiting.clear(); w.forEach { it() } }
    /** the list is at its top (pull down to refresh) */
    fun atTop(): Boolean = !scroll.canScrollVertically(-1)
    private var unsub: (() -> Unit)? = null
    private val soon = Runnable { load() }
    private val poll = object : Runnable { override fun run() { load(); postDelayed(this, 30_000) } }
    fun setActive(on: Boolean) {
        removeCallbacks(poll); removeCallbacks(soon); unsub?.invoke(); unsub = null
        if (!on) return
        load()
        unsub = Realtime.subscribe("Message") { ev -> if (ev.type == "create" || ev.type == "update") { removeCallbacks(soon); postDelayed(soon, 800) } }
        postDelayed(poll, 30_000)
    }
    /** opened: its unread count goes to 0 at once */
    fun markRead(id: String) { chats?.firstOrNull { it.id == id }?.unread = 0; renderList() }

    private fun list(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun pick(vararg v: Any?): String { for (x in v) { if (x == null || x == JSONObject.NULL) continue; val s = x.toString(); if (s.isNotEmpty()) return s }; return "" }
    private fun norm(c: JSONObject): Chat {
        val o = listOf("other", "other_user", "other_profile", "partner", "profile").firstNotNullOfOrNull { c.optJSONObject(it) } ?: JSONObject()
        val lmAny = c.opt("last_message")
        val m = lmAny as? JSONObject
        val kind = pick(m?.opt("kind"), c.opt("last_message_kind")).lowercase()
        val raw = pick(if (lmAny is String) lmAny else m?.opt("text"), c.opt("last_message_text"), c.opt("last_text"), c.opt("preview"))
        val text = if (kind == "gif" || (m != null && pick(m.opt("gif_id")).isNotEmpty())) "🎁 GIF" else if (kind == "photo") "📷 Photo" else if (kind == "voice") "🎤 Voice message" else raw
        val from = pick(m?.opt("from_user_id"), c.opt("last_message_from"), c.opt("last_sender_id"))
        return Chat(
            id = pick(c.opt("match_id"), c.opt("id"), c.opt("conversation_id")),
            name = pick(o.opt("name"), o.opt("full_name"), c.opt("other_name")).ifEmpty { "Member" },
            photo = pick(o.opt("photo"), o.opt("profile_picture"), c.opt("other_photo")),
            photoThumb = (o.opt("photo_thumb") as? String).orEmpty(),
            age = pick(o.opt("age"), c.opt("other_age")),
            pid = pick(o.opt("profile_id"), o.opt("id")), uid = pick(o.opt("user_id"), o.opt("created_by_id")),
            online = SdRules.isOnline(o), verified = o.optBoolean("verified") || o.optBoolean("is_verified"),
            text = text, from = from,
            time = pick(c.opt("last_message_at"), m?.opt("created_date"), c.opt("updated_date"), c.opt("created_date")),
            unread = pick(c.opt("unread_count"), c.opt("unread"), c.opt("unreadCount")).toDoubleOrNull()?.toInt() ?: 0,
            open = c.optBoolean("open", false),
            mine = (c.opt("last") as? JSONObject)?.let { l -> if (l.has("mine")) l.optBoolean("mine") else null }
                ?: from.takeIf { it.isNotEmpty() && h.myIdNow.isNotEmpty() }?.let { it == h.myIdNow },
            read = (c.opt("last") as? JSONObject)?.optBoolean("read") == true || m?.optBoolean("read") == true,
        )
    }

    // ═════════════ drawing ═════════════
    fun render() {
        top.update()
        val locked = h.premiumInfo.second && !h.isPremium
        lockBox.visibility = if (locked) VISIBLE else GONE
        val verify = h.verifyFirst
        lockIcon.setImageDrawable(if (verify) Icon(Lucide.ShieldCheck, Color.WHITE, 22f, 2f) else SdIcon(listOf(SdPaths.CROWN to Color.WHITE), 21f))
        lockTitle.text = if (verify) "Verify to chat free" else "Chat is Premium"
        lockSub.text = if (verify) "Chat is free for verified girls — no plan needed." else "Get Premium to open your chats."
        lockBox.contentDescription = if (verify) "Verify your profile to chat free" else "Chat is for Premium members. Get Premium"
        searchBox.visibility = if (!chats.isNullOrEmpty()) VISIBLE else GONE
        val unread = chats?.sumOf { it.unread } ?: 0
        unreadCircle.visibility = if (unread > 0) VISIBLE else GONE
        unreadCircle.text = if (unread > 99) "99+" else unread.toString()
        unreadCircle.contentDescription = "$unread unread messages"
        renderList()
    }

    private fun note(text: String): View = jk(context, text, 14.5f, SD.SUB, 500).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f); setPadding(dpi(24f), dpi(30f), dpi(24f), dpi(10f)) }
    private fun renderList() {
        closeOpen(false)
        listBox.removeAllViews()
        val all = chats
        val query = q.trim().lowercase()
        when {
            all == null -> repeat(5) { listBox.addView(rowSkeleton(), lp(MATCH, dpi(84f))) }
            failed && all.isEmpty() -> {
                val b = vbox(context, 0f, Gravity.CENTER_HORIZONTAL).apply { setPadding(dpi(18f), dpi(26f), dpi(18f), dpi(10f)) }
                b.addView(jk(context, "Couldn't load your chats", 17f, SD.TXT, 700), lp(WRAP, WRAP))
                b.addView(jk(context, "Check your internet and try again.", 14f, SD.SUB, 500), lp(WRAP, WRAP, t = 6f, b = 14f))
                b.addView(jk(context, "Try again", 15f, Color.WHITE, 700).apply { gravity = Gravity.CENTER; background = rounded(SD.M, 22f); setPadding(dpi(22f), 0, dpi(22f), 0) }
                    .press().onTap { chats = null; failed = false; render(); load() }, lp(WRAP, dpi(44f)))
                listBox.addView(b, lp(MATCH, WRAP))
            }
            all.isEmpty() -> {
                val b = vbox(context, 0f, Gravity.CENTER_HORIZONTAL).apply { setPadding(dpi(24f), dpi(30f), dpi(24f), dpi(10f)) }
                b.addView(sdIconView(context, SdIcon(listOf(SdPaths.CHAT_FILL to SD.M), 34f)).apply { background = oval(SD.M_TINT); setPadding(dpi(20f), dpi(20f), dpi(20f), dpi(20f)) }, lp(dpi(74f), dpi(74f)))
                b.addView(jk(context, "No chats yet", 20f, SD.TXT, 700), lp(WRAP, WRAP, t = 14f))
                b.addView(jk(context, "Send a request to someone you like. When they accept, your private chat opens here.", 14.5f, SD.SUB, 500).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP, t = 6f, b = 18f))
                b.addView(jk(context, "Find people online", 15.5f, Color.WHITE, 700).apply { gravity = Gravity.CENTER; background = rounded(SD.M, 24f); setPadding(dpi(24f), 0, dpi(24f), 0) }
                    .press().onTap { h.goHome() }, lp(WRAP, dpi(48f)))
                listBox.addView(b, lp(MATCH, WRAP))
            }
            else -> {
                var shown = if (query.isEmpty()) all else all.filter { it.name.lowercase().contains(query) }
                shown = when (filter) { 1 -> shown.filter { it.unread > 0 }; 2 -> shown.filter { it.online }; else -> shown }
                // pinned chats first (newest first among them), then the rest as before
                shown = shown.filter { it.id in pins } + shown.filter { it.id !in pins }
                if (shown.isEmpty()) listBox.addView(note(when {
                    query.isNotEmpty() -> "No chat with “${q.trim()}”."
                    filter == 1 -> "No unread messages."
                    filter == 2 -> "No one from your chats is online right now."
                    else -> "No chats yet."
                }), lp(MATCH, WRAP))
                for (c in shown) listBox.addView(SwipeRow(c), lp(MATCH, dpi(84f)))
            }
        }
    }

    private fun rowSkeleton(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(20f), 0, dpi(20f), 0)
        addView(View(context).apply { background = oval(Ui.c("#F4F4F7")) }, lp(dpi(56f), dpi(56f), r = 14f))
        val t = vbox(context, 8f)
        t.addView(View(context).apply { background = rounded(Ui.c("#F4F4F7"), 6f) }, lp(dpi(130f), dpi(13f)))
        t.addView(View(context).apply { background = rounded(Ui.c("#F7F7F9"), 6f) }, lp(dpi(190f), dpi(11f)))
        addView(t, lp(0, WRAP, 1f))
    }

    // ── one row: a white card that can be pushed left to show the red bin ──
    private var openRow: SwipeRow? = null
    private fun closeOpen(animate: Boolean) { openRow?.open(false, animate); openRow = null }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class SwipeRow(val c: Chat) : FrameLayout(this@SdChatsPage.context) {
        private val bin = FrameLayout(context)
        private val front = LinearLayout(context)
        private val reveal = Ui.dp(74f)
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startT = 0f
        private var dragging = false
        var isOpen = false; private set

        init {
            clipChildren = false
            bin.background = rounded(SD.RED_TINT, 20f)
            bin.addView(sdIconView(context, SdIcon(listOf(SdPaths.TRASH_BODY to SD.RED), 24f)).also { v ->
                v.setImageDrawable(SdIcon(listOf(SdPaths.TRASH_BODY to SD.RED, SdPaths.TRASH_LINES to Color.WHITE, SdPaths.TRASH_TOP to SD.RED), 24f))
            }, LayoutParams(dpi(24f), dpi(24f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(24f) })
            bin.contentDescription = "Delete chat with ${c.name}"
            bin.visibility = INVISIBLE
            bin.press().onTap { confirm("delete", c) }
            addView(bin, LayoutParams(dpi(110f), dpi(72f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(14f) })
            front.orientation = LinearLayout.HORIZONTAL; front.gravity = Gravity.CENTER_VERTICAL
            front.setPadding(dpi(20f), 0, dpi(20f), 0)
            front.background = rounded(Color.WHITE, 22f)
            front.isLongClickable = true
            buildFront()
            front.setOnClickListener { if (isOpen) open(false) else h.openChatRow(c) }
            front.setOnLongClickListener { closeOpen(true); options(c); true }
            front.contentDescription = "Chat with ${c.name}${if (c.unread > 0) ", ${c.unread} new" else ""}"
            addView(front, LayoutParams(-1, -1))
        }

        private fun buildFront() {
            val st = h.chatStoryFor(c.uid, c.pid, "")
            val locked = h.premiumInfo.second && !h.isPremium
            // the photo (a thin ring while they have a story) → their profile
            val ph = FrameLayout(context).apply { clipChildren = false }
            val ring = FrameLayout(context)
            if (st != null) { ring.background = oval(Color.WHITE, 2f, if (st.optBoolean("seen")) Ui.c("#E2E2E8") else SD.M); ring.setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f)) }
            val photo = RoundPhoto(context, 56f)
            photo.set(listOf(c.photoThumb, c.photo), Demo.round(JSONObject().put("id", c.pid), Ui.dpi(56f)))
            ring.addView(photo, LayoutParams(-1, -1))
            ph.addView(ring, LayoutParams(-1, -1))
            if (c.online) ph.addView(View(context).apply { background = oval(SD.GREEN, 2f, Color.WHITE); contentDescription = "Online" },
                LayoutParams(dpi(12f), dpi(12f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(1f); bottomMargin = dpi(1f) })
            ph.contentDescription = "${c.name}'s profile"
            ph.press().onTap { if (isOpen) open(false) else h.openFromChat(c) }
            front.addView(ph, lp(dpi(56f), dpi(56f), r = 14f))
            // name · time / message · pin, badge or ticks
            val t = vbox(context, 6f)
            val l1 = hbox(context, 10f)
            l1.addView(jk(context, c.name + if (c.age.isNotEmpty()) ", ${c.age}" else "", 15.5f, SD.TXT, 700).oneLine(), lp(0, WRAP, 1f))
            l1.addView(jk(context, chatTime(c.time), 12f, Ui.c("#9A9AA2"), 500), lp(WRAP, WRAP))
            t.addView(l1, lp(MATCH, WRAP))
            val l2 = hbox(context, 10f)
            l2.addView(jk(context, c.text.ifEmpty { "Say hi 👋" }, 13.5f, SD.SUB, 500).oneLine(), lp(0, WRAP, 1f))
            val end = hbox(context, 8f)
            if (c.id in pins) end.addView(sdIconView(context, SdIcon(listOf(SdPaths.PUSHPIN to Ui.c("#A7A7AF")), 16f)).apply { contentDescription = "Pinned" }, lp(dpi(16f), dpi(16f)))
            when {
                c.unread > 0 -> end.addView(jk(context, if (c.unread > 99) "99+" else c.unread.toString(), 11f, Color.WHITE, 700).apply {
                    gravity = Gravity.CENTER; background = rounded(SD.M, 10f); setPadding(dpi(6f), 0, dpi(6f), 0); minWidth = dpi(20f); contentDescription = "${c.unread} new"
                }, lp(WRAP, dpi(20f)))
                locked && !c.open -> end.addView(sdIconView(context, SdIcon(listOf(SdPaths.LOCK to Ui.c("#B9B9C0")), 15f, 2f)).apply { contentDescription = "Locked" }, lp(dpi(15f), dpi(15f)))
                c.mine == true && c.text.isNotEmpty() -> end.addView(sdIconView(context, if (c.read) SdIcon(listOf(SdPaths.CHECK2 to SD.GREEN), 17f, 1.9f) else SdIcon(listOf(SdPaths.CHECK to Ui.c("#B9B9C0")), 16f, 2f))
                    .apply { contentDescription = if (c.read) "Seen" else "Sent" }, lp(dpi(17f), dpi(17f)))
            }
            if (end.childCount > 0) l2.addView(end, lp(WRAP, WRAP))
            t.addView(l2, lp(MATCH, WRAP))
            front.addView(t, lp(0, WRAP, 1f))
        }

        /** pushed left (the bin shows) or back */
        fun open(on: Boolean, animate: Boolean = true) {
            isOpen = on
            if (on) { if (openRow !== this) { openRow?.open(false); openRow = this } } else if (openRow === this) openRow = null
            bin.visibility = VISIBLE
            front.background = rounded(Color.WHITE, 22f)
            front.elevation = if (on) Ui.dp(8f) else 0f
            val to = if (on) -reveal else 0f
            if (animate && !PageFx.off(context)) front.animate().translationX(to).setDuration(220).setInterpolator(android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f))
                .withEndAction { if (!isOpen) { bin.visibility = INVISIBLE; front.elevation = 0f } }.start()
            else { front.translationX = to; if (!on) bin.visibility = INVISIBLE }
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; startT = front.translationX; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f && (dx < 0 || isOpen)) {
                        dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                        if (openRow !== this) closeOpen(true)
                        bin.visibility = VISIBLE; front.elevation = Ui.dp(8f)
                        return true
                    }
                }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_MOVE -> if (dragging) front.translationX = (startT + e.x - downX).coerceIn(-reveal * 1.3f, 0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; open(front.translationX < -reveal * .45f) }
            }
            return true
        }
    }

    // ── long-press: Pin chat · Delete chat · Block ──
    private fun options(c: Chat) {
        val p = vbox(context).apply { background = SD.sheetBg(); setPadding(dpi(20f), dpi(26f), dpi(20f), dpi(24f)) }
        p.addView(View(context).apply { background = rounded(SD.HANDLE, 3f) }, lp(dpi(44f), dpi(6f), t = -14f, b = 12f, gravity = Gravity.CENTER_HORIZONTAL))
        val head = hbox(context, 12f).apply { setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(12f)) }
        head.addView(RoundPhoto(context, 46f).also { it.set(listOf(c.photoThumb, c.photo), Demo.round(JSONObject().put("id", c.pid), Ui.dpi(46f))) }, lp(dpi(46f), dpi(46f)))
        val ht = vbox(context, 2f)
        ht.addView(jk(context, c.name, 17f, SD.TXT, 700).oneLine())
        ht.addView(jk(context, if (c.online) "Online now" else "Chat", 12.5f, if (c.online) SD.GREEN else SD.SUB, 500))
        head.addView(ht, lp(0, WRAP, 1f))
        p.addView(head, lp(MATCH, WRAP))
        p.addView(View(context).apply { setBackgroundColor(Ui.c("#F1F1F4")) }, lp(MATCH, dpi(1f), b = 6f))
        var close: () -> Unit = {}
        val pinned = c.id in pins
        p.addView(optRow(SdPaths.PIN_LINE, Ui.c("#3A3A44"), Ui.c("#F4F4F7"), if (pinned) "Unpin chat" else "Pin chat", if (pinned) "Puts it back in time order" else "Keeps this chat at the top", SD.TXT) {
            close(); if (pinned) pins.remove(c.id) else pins.add(c.id); prefs.edit().putStringSet("sd_pins", HashSet(pins)).apply(); renderList()
        }, lp(MATCH, WRAP))
        p.addView(optRow(SdPaths.TRASH_LINE, SD.RED, SD.RED_TINT, "Delete chat", "Removes this conversation for you", SD.RED) { close(); confirm("delete", c) }, lp(MATCH, WRAP))
        p.addView(optRow(SdPaths.BAN, SD.RED, SD.RED_TINT, "Block", "They won't be able to message you", SD.RED) { close(); confirm("block", c) }, lp(MATCH, WRAP))
        p.contentDescription = "Chat options for ${c.name}"
        close = h.layer.sheet(p, z = 80f)
    }
    private fun optRow(icon: String, ic: Int, bg: Int, label: String, sub: String, color: Int, onClick: () -> Unit): View {
        val r = hbox(context, 14f).apply { setPadding(dpi(4f), dpi(10f), dpi(4f), dpi(10f)) }
        r.addView(sdIconView(context, SdIcon(listOf(icon to ic), 20f, 1.9f)).apply { background = oval(bg); setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f)) }, lp(dpi(44f), dpi(44f)))
        val t = vbox(context, 2f)
        t.addView(jk(context, label, 15.5f, color, 700))
        t.addView(jk(context, sub, 12.5f, SD.SUB, 500))
        r.addView(t, lp(0, WRAP, 1f))
        r.contentDescription = label
        return r.press(0.98f).onTap(onClick)
    }

    /** "Delete this chat?" / "Block Simran?" — then the same server calls as inside the chat */
    private fun confirm(kind: String, c: Chat) {
        val first = SdRules.firstName(c.name)
        val box = FrameLayout(context).apply { setBackgroundColor(Color.argb(115, 16, 8, 14)); setPadding(dpi(24f), dpi(24f), dpi(24f), dpi(24f)) }
        val card = vbox(context, 0f, Gravity.CENTER_HORIZONTAL).apply { background = rounded(Color.WHITE, 24f); setPadding(dpi(18f), dpi(22f), dpi(18f), dpi(16f)); elevation = Ui.dp(16f); isClickable = true }
        card.addView(jk(context, if (kind == "delete") "Delete this chat?" else "Block $first?", 20f, SD.TXT, 700).apply { gravity = Gravity.CENTER })
        card.addView(jk(context, if (kind == "delete") "All messages in this chat will be removed for you. This can't be undone." else "They won't be able to message you. You can unblock anytime from this chat or Settings.",
            14.5f, SD.SUB, 500).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP, t = 8f, b = 18f))
        var close: () -> Unit = {}
        val two = hbox(context, 10f)
        two.addView(jk(context, "Cancel", 15.5f, SD.TXT, 700).apply { gravity = Gravity.CENTER; background = rounded(Ui.c("#F4F4F7"), 24f) }.press().onTap { close() }, lp(0, dpi(48f), 1f))
        two.addView(jk(context, if (kind == "delete") "Delete" else "Block", 15.5f, Color.WHITE, 700).apply { gravity = Gravity.CENTER; background = rounded(SD.RED, 24f) }
            .press().onTap { close(); if (kind == "delete") deleteChat(c) else block(c) }, lp(0, dpi(48f), 1f))
        card.addView(two, lp(MATCH, WRAP))
        box.addView(card, LayoutParams(dpi(340f).coerceAtMost(resources.displayMetrics.widthPixels - dpi(48f)), -2, Gravity.CENTER))
        box.setOnClickListener { close() }
        close = h.layer.fade(box, z = 85f)
    }
    private fun drop(c: Chat) {
        chats = chats?.filter { it.id != c.id }
        if (pins.remove(c.id)) prefs.edit().putStringSet("sd_pins", HashSet(pins)).apply()
        render()
    }
    private fun deleteChat(c: Chat) {
        Api.invoke("deleteChat", JSONObject().put("match_id", c.id)) { r ->
            if (!r.ok) { h.say(if (r.status == 404) "Deleting chats needs a one-time server update." else r.data.optString("error").ifEmpty { "Couldn't delete. Try again." }, "err"); return@invoke }
            drop(c); h.say("Chat deleted.")
        }
    }
    private fun block(c: Chat) {
        if (c.pid.isEmpty()) { h.say("Couldn't block. Try again.", "err"); return }
        Api.invoke("moderateUser", JSONObject().put("profile_id", c.pid).put("action", "block")) { r ->
            if (!r.ok) { h.say(r.data.optString("error").ifEmpty { "Couldn't block. Try again." }, "err"); return@invoke }
            drop(c); h.say("${SdRules.firstName(c.name).ifEmpty { "They" }} can't message you now.")
        }
    }

    companion object {
        /** "09.12 PM" today (the photo's way) · "Yesterday" · "7 Nov" */
        fun chatTime(t: String): String {
            val ms = SdRules.ms(t); if (ms == 0L) return ""
            val d = Calendar.getInstance().apply { timeInMillis = ms }
            val now = Calendar.getInstance()
            fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (same(d, now)) return SD.clock(ms)
            val y = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (same(d, y)) return "Yesterday"
            return java.text.SimpleDateFormat("d MMM", Locale.US).format(d.time)
        }
    }
}

/** the web's sd-sk loading block: pink with a light sweep */
class Skeleton(ctx: android.content.Context, private val radiusDp: Float) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val t0 = System.currentTimeMillis()
    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val hh = height.toFloat(); val r = Ui.dp(radiusDp)
        p.shader = null; p.color = Ui.c("#FCE4F0"); c.drawRoundRect(0f, 0f, w, hh, r, r, p)
        if (GlobeMap.REDUCED) return
        val f = ((System.currentTimeMillis() - t0) % 750) / 750f
        val x = -w + f * 2 * w
        p.shader = android.graphics.LinearGradient(x, 0f, x + w, 0f, intArrayOf(Ui.c("#FCE4F0"), Ui.c("#FFF3F9"), Ui.c("#FCE4F0")), floatArrayOf(.25f, .5f, .75f), android.graphics.Shader.TileMode.CLAMP)
        c.save(); val path = android.graphics.Path().apply { addRoundRect(0f, 0f, w, hh, r, r, android.graphics.Path.Direction.CW) }; c.clipPath(path)
        c.drawRect(0f, 0f, w, hh, p); c.restore()
        if (isShown) postInvalidateOnAnimation()
    }
}
