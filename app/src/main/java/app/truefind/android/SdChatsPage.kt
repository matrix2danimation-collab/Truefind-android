package app.truefind.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * SECRET DATING · Chat (/secret-app?tab=chat) — the web's ChatList, native:
 * your private chats, newest first, live. Tap a photo → that person's profile (story · Open Chat);
 * tap the name / message → the chat itself. Search by name. The bottom bar stays the web's own.
 */
@SuppressLint("ViewConstructor")
class SdChatsPage(private val h: SecretHome) : FrameLayout(h.context) {
    class Chat(val id: String, val name: String, val photo: String, val photoThumb: String, val age: String, val pid: String, val uid: String,
               val online: Boolean, val verified: Boolean, val text: String, val from: String, val time: String, var unread: Int)

    private val scroll = ScrollView(context)
    private val col = vbox(context)
    private val lockBox = LinearLayout(context)
    private val lockTitle = sdText(context, "", 17f, Ui.c("#9A1246"))
    private val lockSub = sdText(context, "", 14f, Ui.c("#8E2A55"), bold = false)
    private val lockIcon = ImageView(context)
    private val searchBox = LinearLayout(context)
    private val search = EditText(context)
    private val clear = ImageView(context)
    private val listBox = vbox(context, 8f)
    private var q = ""

    var chats: List<Chat>? = null; private set
    /** test shots: scroll to the end, or search */
    fun preview(what: String) { when (what) { "scroll" -> scroll.scrollTo(0, scroll.getChildAt(0).height); "search" -> search.setText("si") } }
    var failed = false; private set

    init {
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isFillViewport = true
        addView(scroll, LayoutParams(-1, -1))
        col.setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(40f))
        scroll.addView(col, LayoutParams(-1, -2))
        col.addView(h.pageHeader("Chats"), lp(MATCH, WRAP))
        col.addView(h.pageSub("Choose who you want to chat with"), lp(MATCH, WRAP, t = 8f))

        // "Chat is Premium" / "Verify to chat free"
        lockBox.orientation = LinearLayout.HORIZONTAL; lockBox.gravity = Gravity.CENTER_VERTICAL
        lockBox.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#FCDDEC"), Ui.c("#FDE8F2"))).apply { cornerRadius = Ui.dp(18f); setStroke(dpi(1f), Color.argb(71, 232, 120, 170)) }
        lockBox.setPadding(dpi(10f), dpi(10f), dpi(8f), dpi(10f))
        lockIcon.scaleType = ImageView.ScaleType.CENTER; lockIcon.background = K.badge()
        lockBox.addView(lockIcon, lp(dpi(44f), dpi(44f), r = 10f))
        val lt = vbox(context); lt.addView(lockTitle); lt.addView(lockSub, lp(WRAP, WRAP, t = 3f))
        lockBox.addView(lt, lp(0, WRAP, 1f))
        lockBox.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, K.wine, 20f, 2.4f)) }, lp(dpi(20f), dpi(20f), l = 10f))
        lockBox.press().onTap { h.locked("chat") }
        col.addView(lockBox, lp(MATCH, WRAP, t = 14f))

        // search by name
        searchBox.orientation = LinearLayout.HORIZONTAL; searchBox.gravity = Gravity.CENTER_VERTICAL
        searchBox.background = rounded(Color.WHITE, 24f, 1f, K.line)
        searchBox.setPadding(dpi(14f), 0, dpi(8f), 0)
        searchBox.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Search, K.wine, 19f)) }, lp(dpi(19f), dpi(19f), r = 8f))
        search.hint = "Search by name"; search.setHintTextColor(Ui.c("#A8909C")); search.setTextColor(K.ink); search.typeface = Ui.regular
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(16f)); search.background = null; search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH; search.setPadding(0, 0, 0, 0)
        search.contentDescription = "Search chats"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { q = s.toString(); clear.visibility = if (q.isEmpty()) GONE else VISIBLE; renderList() }
        })
        searchBox.addView(search, lp(0, dpi(44f), 1f))
        clear.setImageDrawable(Icon(Lucide.X, K.wine, 16f)); clear.scaleType = ImageView.ScaleType.CENTER; clear.background = oval(K.tint); clear.visibility = GONE
        clear.contentDescription = "Clear search"
        clear.onTap { search.setText("") }
        searchBox.addView(clear, lp(dpi(32f), dpi(32f)))
        col.addView(searchBox, lp(MATCH, dpi(48f), t = 14f))

        col.addView(listBox, lp(MATCH, WRAP, t = 14f))
        val foot = sdText(context, "Numbers, links and IDs are never shared in chat.", 13.5f, K.sub, bold = false).icon(Icon(Lucide.Lock, K.rose, 14f), 7f).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(Color.WHITE, 99f); setPadding(dpi(13f), dpi(7f), dpi(13f), dpi(7f)); lineHeight(1.2f)
        }
        col.addView(foot, lp(WRAP, WRAP, t = 16f, gravity = Gravity.CENTER_HORIZONTAL))
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
        )
    }

    // ═════════════ drawing ═════════════
    fun render() {
        val locked = h.premiumInfo.second && !h.isPremium
        lockBox.visibility = if (locked) VISIBLE else GONE
        val verify = h.verifyFirst
        lockIcon.setImageDrawable(if (verify) Icon(Lucide.ShieldCheck, Color.WHITE, 24f, 2f) else Icon(Lucide.Crown, Color.WHITE, 23f, 2f, Color.WHITE))
        lockTitle.text = if (verify) "Verify to chat free" else "Chat is Premium"
        lockSub.text = if (verify) "Chat is free for verified girls — no plan needed." else "Get Premium to open your chats."
        lockBox.contentDescription = if (verify) "Verify your profile to chat free" else "Chat is for Premium members. Get Premium"
        searchBox.visibility = if (!chats.isNullOrEmpty()) VISIBLE else GONE
        renderList()
    }

    private fun card() = rounded(Color.WHITE, 22f, 1f, K.line)
    private fun renderList() {
        listBox.removeAllViews()
        val all = chats
        val query = q.trim().lowercase()
        when {
            all == null -> repeat(4) { listBox.addView(Skeleton(context, 20f), lp(MATCH, dpi(80f))) }
            failed && all.isEmpty() -> {
                val b = vbox(context, 0f, Gravity.CENTER_HORIZONTAL).apply { background = card(); setPadding(dpi(18f), dpi(26f), dpi(18f), dpi(26f)); elevation = Ui.dp(3f) }
                b.addView(sdText(context, "Couldn't load your chats", 18f, K.wine), lp(WRAP, WRAP))
                b.addView(sdText(context, "Check your internet and try again.", 14.5f, K.sub, bold = false), lp(WRAP, WRAP, t = 6f, b = 14f))
                b.addView(sdText(context, "Try again", 15.5f, Color.WHITE).apply { gravity = Gravity.CENTER; background = rounded(K.pill, 22f); setPadding(dpi(22f), 0, dpi(22f), 0) }
                    .press().onTap { chats = null; failed = false; render(); load() }, lp(WRAP, dpi(44f)))
                listBox.addView(b, lp(MATCH, WRAP))
            }
            all.isEmpty() -> {
                val b = vbox(context, 0f, Gravity.CENTER_HORIZONTAL).apply { background = card(); setPadding(dpi(20f), dpi(30f), dpi(20f), dpi(30f)); elevation = Ui.dp(3f) }
                b.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.MessageCircleMore, K.rose, 34f, 1.7f)) }, lp(dpi(34f), dpi(34f)))
                b.addView(sdText(context, "No chats yet", 22f, K.wine, italic = true), lp(WRAP, WRAP, t = 10f))
                b.addView(sdText(context, "Send a request to someone you like. When they accept, your private chat opens here.", 15f, K.sub, bold = false).apply { gravity = Gravity.CENTER; lineHeight(1.2f) }, lp(MATCH, WRAP, t = 6f, b = 16f))
                b.addView(sdText(context, "Find people online", 16.5f, Color.WHITE).apply { gravity = Gravity.CENTER; background = K.btn(24f); setPadding(dpi(24f), 0, dpi(24f), 0); elevation = Ui.dp(4f) }
                    .press().onTap { h.goHome() }, lp(WRAP, dpi(48f)))
                listBox.addView(b, lp(MATCH, WRAP))
            }
            else -> {
                val shown = if (query.isEmpty()) all else all.filter { it.name.lowercase().contains(query) }
                if (shown.isEmpty()) listBox.addView(sdText(context, "No chat with “${q.trim()}”.", 15f, Color.WHITE).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, t = 18f, b = 18f))
                for (c in shown) listBox.addView(row(c), lp(MATCH, WRAP))
            }
        }
    }

    private fun row(c: Chat): View {
        val st = h.chatStoryFor(c.uid, c.pid, "")
        val locked = h.premiumInfo.second && !h.isPremium
        val r = hbox(context, 12f).apply { background = rounded(Color.WHITE, 20f, 1f, if (c.unread > 0) Ui.c("#F3A7C6") else K.line2); setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f)); elevation = Ui.dp(2f) }
        // the photo (a story ring when they have one) → their profile
        val ph = FrameLayout(context)
        val ring = FrameLayout(context).apply {
            if (st != null) {
                background = if (st.optBoolean("seen")) oval(Ui.c("#EBC9D9")) else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#E3086C"), Ui.c("#FF84BD"))).apply { shape = GradientDrawable.OVAL }
                setPadding(dpi(2.5f), dpi(2.5f), dpi(2.5f), dpi(2.5f))
            }
        }
        val inner = FrameLayout(context).apply { background = oval(Ui.c("#F6D6E6"), if (st != null) 2f else 1f, if (st != null) Color.WHITE else K.line2); setPadding(dpi(if (st != null) 2f else 1f), dpi(if (st != null) 2f else 1f), dpi(if (st != null) 2f else 1f), dpi(if (st != null) 2f else 1f)) }
        val photo = RoundPhoto(context, 56f)
        photo.set(listOf(c.photoThumb, c.photo), Demo.round(JSONObject().put("id", c.pid), Ui.dpi(56f)))
        inner.addView(photo, LayoutParams(-1, -1)); ring.addView(inner, LayoutParams(-1, -1))
        ph.addView(ring, LayoutParams(dpi(60f), dpi(60f)))
        if (c.online) ph.addView(LiveDot(context, 13f, 2.5f, Color.WHITE), LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(1f); bottomMargin = dpi(3f) })
        ph.contentDescription = "${c.name}'s profile"
        ph.press().onTap { h.openFromChat(c) }
        r.addView(ph, lp(dpi(60f), dpi(60f)))
        // name + last message → the chat
        val mid = hbox(context, 10f)
        val t = vbox(context)
        // the name (cut with … when long) and the verified seal right after it
        val nm = sdText(context, c.name + if (c.age.isNotEmpty()) ", ${c.age}" else "", 17f, K.ink2).oneLine()
        if (c.verified) nm.icon(Seal(16f), 6f, end = true)
        t.addView(nm, lp(WRAP, WRAP))
        t.addView(sdText(context, if (c.text.isNotEmpty()) "${if (c.from.isNotEmpty() && c.from == h.myIdNow) "You: " else ""}${c.text}" else "Say hi 👋", 14.5f, if (c.unread > 0) K.ink2 else K.sub, bold = c.unread > 0).oneLine(), lp(MATCH, WRAP, t = 3f))
        mid.addView(t, lp(0, WRAP, 1f))
        val end = vbox(context, 6f, Gravity.END)
        end.addView(sdText(context, chatTime(c.time), 12.5f, if (c.unread > 0) K.hot else K.soft, bold = c.unread > 0), lp(WRAP, WRAP))
        when {
            c.unread > 0 -> end.addView(sdText(context, if (c.unread > 99) "99+" else c.unread.toString(), 12.5f, Color.WHITE).apply { gravity = Gravity.CENTER; background = rounded(K.pill, 11f); setPadding(dpi(7f), 0, dpi(7f), 0); minWidth = dpi(22f) }, lp(WRAP, dpi(22f)))
            locked -> end.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Lock, K.soft, 15f)) }, lp(dpi(15f), dpi(15f)))
            else -> end.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, Ui.c("#E3A6C2"), 18f)) }, lp(dpi(18f), dpi(18f)))
        }
        mid.addView(end, lp(WRAP, WRAP))
        mid.setPadding(0, dpi(4f), 0, dpi(4f))
        mid.contentDescription = "Chat with ${c.name}${if (c.unread > 0) ", ${c.unread} new" else ""}"
        mid.onTap { h.openChatRow(c) }
        r.addView(mid, lp(0, WRAP, 1f))
        return r
    }

    companion object {
        /** "4:05 PM" today · "Yesterday" · "7 Nov" */
        fun chatTime(t: String): String {
            val ms = SdRules.ms(t); if (ms == 0L) return ""
            val d = Calendar.getInstance().apply { timeInMillis = ms }
            val now = Calendar.getInstance()
            fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (same(d, now)) return java.text.SimpleDateFormat("h:mm a", Locale.US).format(d.time).uppercase()
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
