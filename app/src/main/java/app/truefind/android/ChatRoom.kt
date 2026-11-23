package app.truefind.android

import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Secret Dating chat room for one match (/chat/:matchId) — the web's src/pages/ChatRoom.jsx, native.
 * Messages stream in live. A message with a phone number, ID, email, link or abusive words is NOT sent:
 * it stays only on your side with a red ✕ "Not sent". Text and GIFs only (voice notes and photos sent
 * earlier still show). Tap the photo or the name at the top to open their full profile.
 * Back always returns to the Secret Dating chat list.
 */
@SuppressLint("ViewConstructor")
class ChatRoom(
    ctx: Context,
    private val host: FrameLayout,
    private val go: (String, JSONObject?) -> Unit,
    private val goBack: () -> Unit,
    private val replace: (String) -> Unit,
) : FrameLayout(ctx) {

    private val main = Handler(Looper.getMainLooper())
    private val layer = SdLayer(host)
    private val prefs = ctx.getSharedPreferences("sd", 0)

    // ── state (same names as the web) ──
    var matchId = ""; private set
    /** pinned to the top of my chat list (pinChat, only for me) */
    private var pinned = false
    private var pinBusy = false
    private var other: JSONObject? = null
    private var otherPhotoRaw = ""
    private val messages = ArrayList<JSONObject>()
    private class Pending(val id: String, val gif: Gif)
    private val pending = ArrayList<Pending>()
    private var held = ArrayList<JSONObject>()
    private var loading = true
    private var sending = false
    private var error = ""
    private var sendFailed = false
    private var balance: Int? = null
    private var lowCoins = false
    private var chatApp = "secret"
    private var otherTyping = false
    private var blockRow: JSONObject? = null
    private var story: JSONObject? = null
    private var storyPremium = false
    private var gifOpen = false
    private var gifTab = "sweet"
    private var meId = ""
    private var gen = 0

    // ── the look: a Secret Dating chat wears the maroon and blush of the owner's picture (23 Nov);
    //    True Find's own chats keep the pink they had. Every colour below asks which one it is. ──
    private val sdLook get() = chatApp != "truefind"
    private val pk: SdPal get() = if (sdLook) K else KP
    private fun pc(hex: String): Int = if (sdLook) Rs.c(hex) else Ui.c(hex)
    private fun pa(a: Int, r: Int, g: Int, b: Int): Int = if (sdLook) Rs.argb(a, r, g, b) else Color.argb(a, r, g, b)
    /** words and icons drawn straight on the page (not on a card): black on the blush, white on the pink */
    private val onBg get() = if (sdLook) Rs.INK else Color.WHITE
    private fun ckBtn(radiusDp: Float, ring: Int? = null): GradientDrawable = if (sdLook) K.btn(radiusDp).apply { if (ring != null) setStroke(Ui.dpi(2f), ring) } else CK.btn(radiusDp, ring)
    private fun ckOval(): GradientDrawable = if (sdLook) K.badge() else CK.btnOval()
    private fun ckPill(radiusDp: Float): GradientDrawable = if (sdLook) K.btn(radiusDp) else CK.pill(radiusDp)
    /** the phone's strips around this chat: maroon behind the clock (blush while a page is open over the chat),
     *  blush over the phone's buttons; True Find's own chats: white as before (null) */
    val stripTop: Int? get() = if (!sdLook) null else if (layer.pageOpen) Rs.BG_TOP else Rs.M
    val stripBottom: Int? get() = if (sdLook) Rs.BG_END else null
    var onStrip: (() -> Unit)? = null

    // ── views ──
    private val headPhoto = RoundPhoto(ctx, 43f)
    private val headRing = FrameLayout(ctx)
    private val headDot = View(ctx)
    private val headName = sdText(ctx, "", 20f, Color.WHITE).oneLine()
    private val headStatus = LinearLayout(ctx)
    private val menuBtn = ImageView(ctx)
    private val errorBox = vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
    private val scroll = ScrollView(ctx)
    private val listWrap = vbox(ctx, 6f)
    private val infoBox = vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
    private val emptyBox = vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
    private val listCol = vbox(ctx, 6f)
    private val tailCol = vbox(ctx, 6f)
    private val spinner = ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(pk.rose) }
    private val failBtn = sdText(ctx, "Message didn't send. Check your internet and tap to try again.", 14.5f, CK.red)
    private val tray = FrameLayout(ctx)
    private val composer = FrameLayout(ctx)
    private val input = EditText(ctx)
    private val counter = sdText(ctx, "", 12f, pk.soft, bold = false)
    private val gifBtn = sdText(ctx, "GIF", 14f, pk.hot)
    // Secret Dating's look (23 Nov): the gifts on their own round button, a smiley for a row of quick emojis
    private val backdrop = PinkBackdrop(ctx)
    private val headBand = FrameLayout(ctx)
    private val headWhite = FrameLayout(ctx)
    private val giftBtn = ImageView(ctx)
    private val emojiBtn = ImageView(ctx)
    private val emojiBar = android.widget.HorizontalScrollView(ctx)
    private var emojiOpen = false
    private var lookNow: Boolean? = null
    private val unblockBtn = sdText(ctx, "Unblock", 15f, Color.WHITE)
    private val sendBtn = FrameLayout(ctx)
    private val sendIcon = ImageView(ctx)
    private val sendSpin = ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
    private val inputBox = LinearLayout(ctx)
    private val blockedBar = LinearLayout(ctx)
    private val blockedText = sdText(ctx, "", 15.5f, pk.ink2, bold = false)
    private var fx: GiftFxView? = null
    // "Connecting…" / "No internet" under the header, only while the live connection is down (ConnWatch)
    private val conn = ConnWatch()
    private val connBar = sdText(ctx, "", 13.5f, pk.ink2).apply {
        gravity = Gravity.CENTER; setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f))
        background = rounded(pc("#FFF7E0"), 14f, 1f, pc("#F5D98B")); visibility = GONE
        contentDescription = "Connection status"
    }
    private var unsubConn: (() -> Unit)? = null
    private val connCheck = Runnable { renderConn() }
    private fun phoneOffline(): Boolean = try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION") val ni = cm.activeNetworkInfo
        ni == null || !ni.isConnected
    } catch (e: Exception) { false }
    private fun renderConn() {
        main.removeCallbacks(connCheck)
        val now = android.os.SystemClock.elapsedRealtime()
        val t = if (shownNow && !Demo.on) conn.text(now, phoneOffline()) else ""
        if (t.isEmpty()) connBar.visibility = GONE else { connBar.text = t; connBar.visibility = VISIBLE }
        val w = conn.waitMs(now)
        if (shownNow && w >= 0) main.postDelayed(connCheck, if (t.isEmpty()) w + 50L else 3000L)
    }

    init {
        isClickable = true
        addView(backdrop, LayoutParams(-1, -1))
        val colv = vbox(ctx)
        addView(colv, LayoutParams(-1, -1))
        headBand.addView(buildHeader(), LayoutParams(-1, -2))
        colv.addView(headBand, lp(MATCH, WRAP))
        colv.addView(connBar, lp(WRAP, WRAP, t = 8f, gravity = Gravity.CENTER_HORIZONTAL))
        errorBox.background = rounded(pc("#FFF1F3"), 16f, 1f, pc("#F8B4C2"))
        errorBox.setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f)); errorBox.visibility = GONE
        colv.addView(errorBox, lp(MATCH, WRAP, l = 14f, r = 14f, t = 12f))
        scroll.isVerticalScrollBarEnabled = false; scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; scroll.isFillViewport = true
        listWrap.setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(8f))
        listWrap.addView(infoBox, lp(MATCH, WRAP))
        listWrap.addView(spinner, lp(dpi(30f), dpi(30f), t = 64f, b = 64f, gravity = Gravity.CENTER_HORIZONTAL))
        listWrap.addView(emptyBox, lp(MATCH, WRAP))
        listWrap.addView(listCol, lp(MATCH, WRAP))
        listWrap.addView(tailCol, lp(MATCH, WRAP))
        scroll.addView(listWrap, LayoutParams(-1, -2))
        // a tap on the messages (not a scroll) closes the GIFs — the web's onClick on the list
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f
        scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> if (gifOpen && abs(e.x - downX) < slop && abs(e.y - downY) < slop) { gifOpen = false; renderTray() }
            }
            false
        }
        colv.addView(scroll, lp(MATCH, 0, 1f))
        failBtn.gravity = Gravity.CENTER; failBtn.background = rounded(pc("#FFF1F3"), 16f, 1f, pc("#F8B4C2")); failBtn.setPadding(dpi(11f), dpi(11f), dpi(11f), dpi(11f))
        failBtn.visibility = GONE; failBtn.onTap { send() }
        colv.addView(failBtn, lp(MATCH, WRAP, l = 12f, r = 12f, t = 4f))
        tray.visibility = GONE
        colv.addView(tray, lp(MATCH, WRAP, l = 10f, r = 10f))
        buildEmojiBar()
        colv.addView(emojiBar, lp(MATCH, dpi(46f), l = 12f, r = 12f, t = 4f))
        colv.addView(buildComposer(), lp(MATCH, WRAP))
        // a page over the chat (their profile) has the blush behind the clock; back in the chat, the maroon again
        layer.onChange = { onStrip?.invoke() }
        applyLook()
    }

    /** the parts built once — the page behind, the top band, the message box — in this chat's look (see sdLook) */
    private fun applyLook() {
        val rose = sdLook
        if (lookNow == rose) return
        // bubbles already drawn in the other look are drawn again (the next renderList builds them afresh)
        if (lookNow != null) { listCol.removeAllViews(); tailCol.removeAllViews(); ticks.clear(); shownKeys = emptyList() }
        lookNow = rose
        backdrop.rose = rose
        backdrop.dense = rose
        // the top: a flat maroon band with round lower corners (the picture's chat); the pink look has none
        headBand.background = if (rose) GradientDrawable().apply { setColor(Rs.M); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, Ui.dp(24f), Ui.dp(24f), Ui.dp(24f), Ui.dp(24f)) } else null
        headBand.setPadding(0, 0, 0, dpi(if (rose) 6f else 0f))
        headWhite.background = oval(pk.tint, 2f, Color.WHITE)
        spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(pk.rose)
        giftBtn.visibility = if (rose) VISIBLE else GONE
        emojiBtn.visibility = if (rose) VISIBLE else GONE
        gifBtn.visibility = if (rose) GONE else VISIBLE
        inputBox.background = rounded(Color.WHITE, 26f, if (rose) 1f else 1.5f, pk.line)
        inputBox.elevation = if (rose) 0f else Ui.dp(3f)
        inputBox.setPadding(dpi(if (rose) 10f else 6f), 0, dpi(6f), 0)
        input.setTextColor(pk.ink); input.setHintTextColor(pc("#A8909C"))
        counter.setTextColor(pk.soft)
        sendBtn.background = ckOval()
        blockedBar.background = rounded(Color.WHITE, 20f, 1f, pk.line); blockedBar.elevation = if (rose) 0f else Ui.dp(3f)
        blockedText.setTextColor(pk.ink2)
        unblockBtn.background = ckPill(19f)
        if (!rose) emojiOpen = false
        renderEmoji()
        renderTray()
        onStrip?.invoke()
    }

    // ── a row of quick emojis over the message box (the smiley in it) ──
    private fun buildEmojiBar() {
        emojiBar.isHorizontalScrollBarEnabled = false; emojiBar.overScrollMode = View.OVER_SCROLL_NEVER
        emojiBar.background = rounded(Color.WHITE, 23f, 1f, Rs.LINE)
        val row = hbox(context).apply { setPadding(dpi(6f), 0, dpi(6f), 0) }
        for (e in EMOJIS) row.addView(TextView(context).apply {
            text = e; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(23f)); setTextColor(Color.BLACK); gravity = Gravity.CENTER; includeFontPadding = false; contentDescription = "Add $e"
        }.onTap { addEmoji(e) }, lp(dpi(42f), dpi(46f)))
        emojiBar.addView(row)
        emojiBar.visibility = GONE
    }
    private fun addEmoji(e: String) {
        if (input.text.length + e.length > 500) return
        val at = input.selectionStart.let { if (it < 0) input.text.length else it }
        input.text.insert(at, e)
    }
    private fun renderEmoji() {
        val on = emojiOpen && sdLook && blockRow == null
        emojiBar.visibility = if (on) VISIBLE else GONE
        emojiBtn.setImageDrawable(Icon(Lucide.Smile, if (on) Rs.M else Rs.SOFT, 23f, 2f))
    }

    // ═════════════ open / close (the route /chat/:id) ═════════════
    fun open(id: String) {
        if (id == matchId && shownNow) return
        close()
        matchId = id
        gen++
        val g = gen
        other = null; otherPhotoRaw = ""; messages.clear(); pending.clear(); held = readHeld(id)
        loading = true; error = ""; sendFailed = false; balance = null; lowCoins = false; chatApp = "secret"; otherTyping = false
        failedKey = ""; failedText = ""
        blockRow = null; story = null; storyPremium = false; gifOpen = false; gifTab = "sweet"; input.setText(""); shownKeys = emptyList()
        listCol.removeAllViews(); tailCol.removeAllViews()
        // what was seen of this chat before (the chat list, the last visit) shows at once; the server's answer
        // follows quietly (owner, 20 Nov: the chat took half a minute to show anything)
        pinned = false; pinBusy = false
        ChatCache.chat(id)?.let { c ->
            pinned = c.optBoolean("pinned", false)
            other = JSONObject((c.optJSONObject("other") ?: JSONObject()).toString())
            chatApp = if (c.optString("app") == "truefind") "truefind" else "secret"
            otherPhotoRaw = SdRules.s(other, "photo")
        }
        // a chat not seen in a list yet (opened from a notification): the look it had last time, so a True Find chat
        // does not flash Secret Dating's maroon first
        if (ChatCache.chat(id) == null && prefs.getString("chat_app_$id", "") == "truefind") chatApp = "truefind"
        ChatCache.messages(id)?.let { list -> messages.addAll(list); loading = false }
        ChatCache.me().takeIf { it.isNotEmpty() }?.let { meId = it }
        shownNow = true
        emojiOpen = false
        applyLook()
        renderAll()
        if (!loading) scrollToEnd(false)
        load(g)
        // a demo person's chat (testing only): said once each time the app is opened
        if (SdDemoChat.owns(id) && !demoTold) { demoTold = true; main.postDelayed({ if (shownNow && matchId == id) say("Demo chat, for testing: messages stay on this phone.") }, 700) }
        unsubMsg = Realtime.subscribe("Message") { ev -> onMessageEvent(ev) }
        unsubMatch = Realtime.subscribe("Match") { ev -> onMatchEvent(ev) }
        unsubTyping = Realtime.subscribe("Typing") { ev -> onTypingEvent(ev) }
        conn.state(Realtime.connected, android.os.SystemClock.elapsedRealtime())
        unsubConn = Realtime.onState { up -> conn.state(up, android.os.SystemClock.elapsedRealtime()); renderConn() }
        renderConn()
        main.removeCallbacks(presence); main.postDelayed(presence, 60_000)
        main.removeCallbacks(fallback); main.postDelayed(fallback, 4000)
    }
    private var shownNow = false
    private var demoTold = false
    // if the live connection is down, look for new messages every few seconds instead
    // (every 8 s, not 4: each look is a read from the platform's shared allowance, and too many of them made sending "busy")
    private val fallback = object : Runnable { override fun run() { main.postDelayed(this, 8000); if (shownNow && appVisible && !loading && error.isEmpty() && !Realtime.connected && !Demo.on) refreshLatest() } }
    private fun refreshLatest() {
        val g = gen
        Api.filter("Message", JSONObject().put("match_id", matchId), "-created_date", 30) { m ->
            if (g != gen || !m.ok) return@filter
            val items = m.data.optJSONArray("items") ?: return@filter
            var added = false; var changed = false
            for (i in items.length() - 1 downTo 0) {
                val x = items.optJSONObject(i) ?: continue
                val ix = messages.indexOfFirst { it.optString("id") == x.optString("id") }
                if (ix < 0) { messages.add(x); added = true } else if (messages[ix].optBoolean("read") != x.optBoolean("read")) { messages[ix].put("read", x.optBoolean("read")); changed = true }
            }
            if (added) { messages.sortBy { SdRules.ms(it.optString("created_date")) }; renderAll(); scrollToEnd(true); markRead() } else if (changed) renderList()
        }
    }
    private var unsubMsg: (() -> Unit)? = null
    private var unsubMatch: (() -> Unit)? = null
    private var unsubTyping: (() -> Unit)? = null

    fun close() {
        if (!shownNow) return
        shownNow = false
        // what is on screen now is what shows first next time
        if (matchId.isNotEmpty() && !loading && error.isEmpty()) ChatCache.putMessages(matchId, messages)
        gen++
        unsubMsg?.invoke(); unsubMsg = null; unsubMatch?.invoke(); unsubMatch = null; unsubTyping?.invoke(); unsubTyping = null
        unsubConn?.invoke(); unsubConn = null; main.removeCallbacks(connCheck); connBar.visibility = GONE
        main.removeCallbacks(presence); main.removeCallbacks(fallback); main.removeCallbacks(typingOff); main.removeCallbacks(typingHide); main.removeCallbacks(fxEnd)
        layer.closeAll()
        fx?.let { (it.parent as? ViewGroup)?.removeView(it) }; fx = null
        hideKeyboard()
        Voice.stop()
        if (input.text.isNotEmpty() || otherTyping) setTyping(false)
    }

    fun back(): Boolean = layer.back()

    /** the app went to the background / came back: like the web's document.hidden, the minute checks wait */
    private var appVisible = true
    fun onAppVisible(v: Boolean) {
        appVisible = v
        if (!v) { main.removeCallbacks(typingOff); if (input.text.isNotEmpty() && shownNow) setTyping(false) }
    }

    /** test shots only (made-up data): open one part of the chat room, then say so in the test log */
    fun preview(what: String, tries: Int = 0) {
        if ((loading || (other == null && error.isEmpty())) && tries < 40) { postDelayed({ preview(what, tries + 1) }, 300); return }
        if (tries >= 0) { postDelayed({ preview(what, -1) }, 900); return }
        when (what) {
            "gif" -> { gifOpen = true; renderTray() }
            "emoji" -> { emojiOpen = true; renderEmoji() }
            "gifpremium" -> { gifOpen = true; gifTab = "premium"; renderTray() }
            "menu" -> menu()
            "report" -> report()
            "gift" -> gifOf("bouquet")?.let { giftSheet(it) }
            "giftlow" -> { balance = 3; gifOf("crown")?.let { giftSheet(it) } }
            "held" -> { input.setText("Mera number 98765 43210 hai"); send() }
            "say" -> { input.setText("Hello! Kaise ho?"); send() }
            "saygif" -> gifOf("rose")?.let { sendGifNow(it) }
            "profile" -> profilePage()
            "typing" -> { otherTyping = true; renderHeader(); renderTail(); scrollToEnd(false) }
            "block" -> confirm("block")
            "blocked" -> { blockRow = JSONObject(); renderComposer(); renderList() }
            "sendgif" -> gifOf("bouquet")?.let { sendGifNow(it) }
            "toast" -> say("Watching stories is for Premium members.", "premium")
            "story" -> { storyPremium = true; openStory() }
            "error" -> { }
        }
        android.util.Log.i("TFDBG", "preview open $what")
    }

    private fun load(g: Int) {
        // a busy server (right after a request many calls go out at once) is waited out — up to 6 tries, each after
        // the wait the server named; while the chat is already on screen (from before) nothing else changes
        fun again(r: Api.Result, tries: Int) = !r.ok && tries < (if (r.data.optBoolean("server_busy")) 6 else 2)
        fun waitFor(r: Api.Result) = if (r.data.optBoolean("server_busy")) Api.busyWaitMs(r) else 2500L
        var meReady = meId.isNotEmpty()
        var items: JSONArray? = null
        // the messages show once both they and "who am I" are known (mine on the right, theirs on the left)
        fun showMessages() {
            val a = items ?: return
            if (!meReady || g != gen) return
            items = null
            val fresh = ArrayList<JSONObject>()
            for (i in a.length() - 1 downTo 0) a.optJSONObject(i)?.let { fresh.add(it) }
            val same = !loading && fresh.size == messages.size && fresh.indices.all { fresh[it].optString("id") == messages[it].optString("id") && fresh[it].optBoolean("read") == messages[it].optBoolean("read") }
            ChatCache.putMessages(matchId, fresh)
            if (!same) {
                val wasLoading = loading
                messages.clear(); messages.addAll(fresh)
                loading = false
                renderAll(); if (wasLoading) scrollToEnd(false) else scrollToEnd(true)
            } else if (loading) { loading = false; renderAll() }
            markRead()
        }
        // the NEWEST 100 messages (oldest first on screen) — asked at once, beside the chat's details
        fun page(tries: Int) {
            Api.filter("Message", JSONObject().put("match_id", matchId), "-created_date", 100) { m ->
                if (g != gen) return@filter
                if (again(m, tries + 1)) { main.postDelayed({ if (g == gen) page(tries + 1) }, waitFor(m)); return@filter }
                if (!m.ok) { if (loading) { error = m.error ?: "Could not load chat."; loading = false; renderAll() }; return@filter }
                items = m.data.optJSONArray("items") ?: JSONArray()
                showMessages()
            }
        }
        fun chats(tries: Int) {
            Api.invoke("getChats", JSONObject().put("match_id", matchId).put("only", true)) { r ->
                if (g != gen) return@invoke
                if (again(r, tries + 1)) { main.postDelayed({ if (g == gen) chats(tries + 1) }, waitFor(r)); return@invoke }
                // (known from before: a failed look stays quiet — the chat is on screen and works)
                if (!r.ok) { if (other == null) { error = r.error ?: "Could not load chat."; loading = false; renderAll() }; return@invoke }
                val list = r.data.optJSONArray("chats")
                val chat = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }.firstOrNull { it.optString("match_id") == matchId }
                if (chat == null) { error = "Chat not found."; loading = false; renderAll(); return@invoke }
                ChatCache.putChat(chat)
                other = chat.optJSONObject("other") ?: JSONObject()
                chatApp = if (chat.optString("app") == "truefind") "truefind" else "secret"
                if (chatApp == "truefind" && prefs.getString("chat_app_$matchId", "") != "truefind") prefs.edit().putString("chat_app_$matchId", "truefind").apply()
                applyLook()
                otherPhotoRaw = SdRules.s(other, "photo")
                Api.invoke("getWallet", JSONObject().put("app", chatApp)) { w -> if (g == gen && w.ok) { balance = w.data.optJSONObject("wallet")?.optInt("coins") ?: 0; renderTray() } }
                // did I block this person?
                if (meId.isNotEmpty()) Api.filter("Block", JSONObject().put("blocker_id", meId), null, 200) { b -> if (g == gen && b.ok) { blockRow = findBlock(b.data.optJSONArray("items")); renderComposer() } }
                // their story (Secret Dating chats only)
                if (chatApp != "truefind") Api.invoke("getStories", JSONObject()) { s ->
                    if (g != gen || !s.ok) return@invoke
                    val arr = s.data.optJSONArray("stories")
                    val o = other ?: JSONObject()
                    story = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.firstOrNull {
                        (SdRules.s(o, "profile_id").isNotEmpty() && it.optString("profile_id") == SdRules.s(o, "profile_id")) || (SdRules.s(o, "user_id").isNotEmpty() && it.optString("user_id") == SdRules.s(o, "user_id"))
                    }
                    storyPremium = s.data.optBoolean("premium")
                    renderHeader()
                }
                renderAll()
            }
        }
        page(0)
        if (meReady) chats(0)
        else Api.me { u ->
            if (g != gen) return@me
            meId = u.data.optString("id")
            if (meId.isNotEmpty()) ChatCache.setMe(meId)
            meReady = true
            showMessages()
            chats(0)
        }
    }
    private fun markRead() {
        if (meId.isEmpty()) return
        Api.updateMany("Message", JSONObject().put("match_id", matchId).put("to_user_id", meId).put("read", false), JSONObject().put("\$set", JSONObject().put("read", true)))
    }
    private fun findBlock(arr: JSONArray?): JSONObject? {
        val o = other ?: return null
        val pid = SdRules.s(o, "profile_id"); val uid = SdRules.s(o, "user_id", "created_by_id")
        for (i in 0 until (arr?.length() ?: 0)) {
            val b = arr!!.optJSONObject(i) ?: continue
            if (listOf("blocked_profile_id", "blocked_id", "blocked_user_id", "profile_id").any { k -> val x = b.optString(k); x.isNotEmpty() && (x == pid || x == uid) }) return b
        }
        return null
    }
    private val otherUserId: String get() {
        val o = other
        SdRules.s(o, "user_id", "created_by_id").let { if (it.isNotEmpty()) return it }
        messages.firstOrNull { it.optString("from_user_id").let { f -> f.isNotEmpty() && f != meId && f != SdDemoChat.SELF } }?.let { return it.optString("from_user_id") }
        messages.firstOrNull { it.optString("to_user_id").let { f -> f.isNotEmpty() && f != meId && f != SdDemoChat.SELF } }?.let { return it.optString("to_user_id") }
        return ""
    }
    /** a message of mine (in a demo chat, testing only, my lines carry SdDemoChat.SELF instead of my id) */
    private fun isMine(m: JSONObject): Boolean = m.optString("from_user_id").let { it == meId || it == SdDemoChat.SELF }

    // live: new / changed / deleted messages, and "typing…"
    private fun onMessageEvent(ev: Realtime.Event) {
        val d = ev.data
        if (ev.type == "create" && d?.optString("match_id") == matchId) {
            if (messages.none { it.optString("id") == d.optString("id") }) messages.add(d)
            if (d.optString("to_user_id") == meId) markRead()
            renderAll(); scrollToEnd(true)
        }
        if (ev.type == "update" && d?.optString("match_id") == matchId) {
            val i = messages.indexOfFirst { it.optString("id") == d.optString("id") }
            if (i >= 0) { val m = messages[i]; d.keys().forEach { k -> m.put(k, d.get(k)) }; renderList() }
        }
        if (ev.type == "delete") {
            val id = ev.id.ifEmpty { d?.optString("id").orEmpty() }
            if (messages.removeAll { it.optString("id") == id }) renderAll()
        }
    }
    private val typingHide = Runnable { otherTyping = false; renderHeader(); renderTail() }
    private fun onMatchEvent(ev: Realtime.Event) {
        val d = ev.data ?: return
        if (ev.type != "update" || d.optString("id") != matchId) return
        val who = d.optString("typing_user_id"); val at = SdRules.ms(d.optString("typing_at"))
        val fresh = meId.isNotEmpty() && who.isNotEmpty() && who != meId && at > 0 && System.currentTimeMillis() - at < 5000
        otherTyping = fresh
        main.removeCallbacks(typingHide)
        if (fresh) main.postDelayed(typingHide, 5000)
        renderHeader(); renderTail()
        // when they start typing, show the dots (if you're reading the latest messages)
        if (fresh && scroll.getChildAt(0).height - scroll.scrollY - scroll.height < dpi(220f)) main.postDelayed({ scrollToEnd(true) }, 30)
    }
    /** the other phone's own "… is typing" row (Typing table, 24 Nov): the same dots as from the chat's row */
    private fun onTypingEvent(ev: Realtime.Event) {
        val d = ev.data ?: return
        if (ev.type == "delete" || d.optString("match_id") != matchId || meId.isEmpty()) return
        val otherUid = other?.optString("user_id").orEmpty()
        val from = d.optString("created_by_id")
        // only the other person's row, written to me; nothing from someone I blocked (a row without its writer's
        // id is taken when it is written to me for this very chat)
        if (from == meId || (from.isNotEmpty() && otherUid.isNotEmpty() && from != otherUid) || d.optString("to_user_id") != meId || blockRow != null) return
        val at = SdRules.ms(d.optString("updated_date").ifEmpty { d.optString("typing_at") })
        val fresh = d.optBoolean("typing", false) && at > 0 && System.currentTimeMillis() - at < 5000
        otherTyping = fresh
        main.removeCallbacks(typingHide)
        if (fresh) main.postDelayed(typingHide, 5000)
        renderHeader(); renderTail()
        if (fresh && scroll.getChildAt(0).height - scroll.scrollY - scroll.height < dpi(220f)) main.postDelayed({ scrollToEnd(true) }, 30)
    }
    // keep "Online now" / "Last seen …" fresh while the chat is open
    private val presence = object : Runnable {
        override fun run() {
            main.postDelayed(this, 60_000)
            if (!shownNow || !appVisible) return
            val g = gen
            Api.invoke("getChats", JSONObject().put("match_id", matchId).put("only", true)) { r ->
                if (g != gen || !r.ok) return@invoke
                val list = r.data.optJSONArray("chats")
                val c = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }.firstOrNull { it.optString("match_id") == matchId }
                val o2 = c?.optJSONObject("other") ?: return@invoke
                other?.let { o -> o.put("online", o2.opt("online")); if (o2.optString("last_active").isNotEmpty()) o.put("last_active", o2.optString("last_active")) }
                renderHeader()
            }
        }
    }

    // ═════════════ typing ping ═════════════
    private var typingPing = 0L
    private val typingOff = Runnable { setTyping(false) }
    // "… is typing" is written by this phone itself in its own row (Typing table, 24 Nov): out of this member's
    // own allowance, not the one the whole app shares (the setTyping function wrote the chat for every ping).
    // If the table is not there (the server not updated yet) or a write is refused, setTyping as before.
    private var typingRow = ""            // this phone's row for this chat
    private var typingRowFor = ""         // … and which chat it belongs to
    private var typingDirect = true
    private var typingLooking = false
    private var typingFails = 0
    private fun setTyping(flag: Boolean) {
        if (matchId.isEmpty()) return
        val id = matchId
        val to = other?.optString("user_id").orEmpty()
        if (!typingDirect || Demo.on || SdDemoChat.owns(id) || to.isEmpty() || meId.isEmpty()) { typingByServer(id, flag); return }
        if (typingRowFor != id) { typingRowFor = id; typingRow = "" }
        val data = JSONObject().put("match_id", id).put("to_user_id", to).put("typing", flag).put("typing_at", Tf.isoNow())
        // a refusal or an unknown table: never again in this app run; any other failure twice: the same
        val failed = { r: Api.Result -> r.status in 400..499 && r.status != 408 && r.status != 429 || ++typingFails >= 2 }
        if (typingRow.isNotEmpty()) {
            Api.update("Typing", typingRow, data) { r ->
                if (r.ok) return@update
                typingRow = ""
                if (failed(r)) typingDirect = false
                typingByServer(id, flag)
            }
            return
        }
        if (!flag) { typingByServer(id, false); return }          // nothing of ours to clear yet
        if (typingLooking) return
        typingLooking = true
        // this phone's row of this chat (made the first time, then only changed)
        Api.filter("Typing", JSONObject().put("match_id", id).put("created_by_id", meId), null, 1) { f ->
            if (!f.ok) { typingLooking = false; if (failed(f)) typingDirect = false; typingByServer(id, flag); return@filter }
            val have = f.data.optJSONArray("items")?.optJSONObject(0)?.optString("id").orEmpty()
            if (have.isNotEmpty()) {
                Api.update("Typing", have, data) { r ->
                    typingLooking = false
                    if (r.ok) { if (typingRowFor == id) typingRow = have } else { if (failed(r)) typingDirect = false; typingByServer(id, flag) }
                }
            } else {
                Api.create("Typing", data) { r ->
                    typingLooking = false
                    val made = r.data.optString("id")
                    if (r.ok && made.isNotEmpty()) { if (typingRowFor == id) typingRow = made } else { if (failed(r)) typingDirect = false; typingByServer(id, flag) }
                }
            }
        }
    }
    private fun typingByServer(id: String, flag: Boolean) { if (id.isNotEmpty()) Api.invoke("setTyping", JSONObject().put("match_id", id).put("typing", flag)) { } }
    private fun onType(v: String) {
        val now = System.currentTimeMillis()
        if (v.trim().isNotEmpty() && now - typingPing > 4000) { typingPing = now; setTyping(true) }
        main.removeCallbacks(typingOff); main.postDelayed(typingOff, 3500)
        renderSendState()
    }

    // ═════════════ held (not sent) messages: only on this phone, 7 days, last 30 ═════════════
    private fun readHeld(id: String): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        try {
            val a = JSONArray(prefs.getString("cr_held_$id", "[]"))
            for (i in 0 until a.length()) { val h = a.optJSONObject(i) ?: continue; if (h.optString("id").isNotEmpty() && System.currentTimeMillis() - h.optLong("at") < 7L * 24 * 3600_000) out.add(h) }
        } catch (e: Exception) { /* none */ }
        return out
    }
    private fun writeHeld() {
        val list = held.takeLast(30)
        if (list.isEmpty()) prefs.edit().remove("cr_held_$matchId").apply() else prefs.edit().putString("cr_held_$matchId", JSONArray(list).toString()).apply()
    }
    private fun holdBack(t: String, reason: String) {
        val h = JSONObject().put("id", "held-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}").put("text", t).put("reason", reason)
            .put("at", System.currentTimeMillis()).put("after", messages.lastOrNull()?.optString("id").orEmpty()).put("by", meId)
        held.add(h); while (held.size > 30) held.removeAt(0)
        writeHeld()
        try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (e: Exception) { /* fine */ }
        renderAll(); scrollToEnd(true)
    }
    private fun dropHeld(id: String) { held.removeAll { it.optString("id") == id }; writeHeld(); renderAll() }

    // ═════════════ sending ═════════════
    private fun isPrivacyError(d: JSONObject) = d.optString("error") in listOf("privacy_blocked", "blocked", "content_blocked")
    private fun handleSendError(r: Api.Result, restore: Boolean) {
        val d = r.data
        when (d.optString("error")) {
            "insufficient_coins" -> { error = "Not enough balance. This chat costs ₹${d.opt("rate")} per message and you have ₹${d.opt("balance")}."; lowCoins = true; renderError() }
            "user_blocked", "blocked_user" -> say("You can't message this person.", "err")
            "verify_required" -> say(d.optString("message").ifEmpty { "Verify your profile to chat — it's free for verified girls." }, "verify")
            "premium_required" -> say(d.optString("message").ifEmpty { "Chat is for Premium members." }, "premium")
            "profile_incomplete" -> say(d.optString("message").ifEmpty { "Add your name, age, state and city to chat." }, "err")
            else -> when {
                isPrivacyError(d) -> say(d.optString("reason").ifEmpty { d.optString("message") }.ifEmpty { "For your safety, numbers, links, IDs and abusive words can't be sent." }, "err")
                restore -> { sendFailed = true; failBtn.text = failText(r); failBtn.visibility = VISIBLE }
                else -> say(d.optString("message").ifEmpty { d.optString("error") }.ifEmpty { "Couldn't send. Check your internet and try again." }, "err")
            }
        }
    }
    /**
     * Sends, and when the answer is a passing failure (no answer from the server, the server busy for a moment —
     * the platform's read limit — or failing for a moment) asks again by itself, 1.2 s and then 3 s later (busy: as
     * long as the server says, up to 8 s), with the message's one-time key, so the server answers with the message
     * it already saved instead of a second copy (owner, 19 Nov:
     * "Message didn't send. Check your internet" while the internet was fine, and it worked after going out and back).
     */
    private fun deliver(payload: JSONObject, price: Int, key: String = newKey(), again: Boolean = false, done: (Api.Result) -> Unit) {
        // one key per message: every retry of this message (by itself, or "tap to try again" with the same words)
        // carries it, so the server finds the copy it may already have saved instead of saving a second one
        payload.put("client_key", key)
        if (again) payload.put("retry", true)
        deliverTry(payload, price, 0, done)
    }
    private fun newKey() = "m" + java.util.UUID.randomUUID().toString().replace("-", "")
    /** the message that did not send (its words are back in the box): "tap to try again" sends it as the same message */
    private var failedKey = ""
    private var failedText = ""
    private fun passingFailure(r: Api.Result): Boolean = !r.ok && (r.status == 0 || r.status == 408 || r.status in 500..599 ||
        (r.status == 429 && (r.data.optBoolean("server_busy") || r.data.optString("error").let { it == "busy" || it == "rate_limited" || it.isEmpty() })))
    private fun deliverTry(payload: JSONObject, price: Int, attempt: Int, done: (Api.Result) -> Unit) {
        val forMatch = matchId
        val body = JSONObject(payload.toString()).put("match_id", forMatch)
        if (attempt > 0) body.put("retry", true)
        Api.invokeOnce("sendMessage", body) { r ->
            // owner, 23 Nov: "Message didn't send — the chat was busy for a moment" came up although the next tries
            // worked: a busy moment is now waited out longer by itself (up to 6 tries, ~30 s at most) before the
            // red note shows — the same message every time (its one-time key), never a second copy
            if (!r.ok && attempt < 5 && passingFailure(r) && forMatch == matchId) {
                val err = r.data.optString("error")
                val wait = when {
                    r.status == 429 && err == "rate_limited" -> 900L     // sent too fast: just after the 0.6 s gap
                    // the chat busy (the platform's read limit): as long as the server said, up to 10 s — asking
                    // sooner would only be told "busy" again
                    r.status == 429 && (err == "busy" || r.data.optBoolean("server_busy")) -> (r.data.optDouble("retry_after", 3.0) * 1000).toLong().coerceIn(1500L, 10_000L)
                    attempt == 0 -> 1200L
                    attempt == 1 -> 3000L
                    else -> 5000L
                }
                main.postDelayed({ if (forMatch == matchId) deliverTry(payload, price, attempt + 1, done) else done(r) }, wait)
                return@invokeOnce
            }
            if (forMatch != matchId) { done(r); return@invokeOnce }
            if (r.ok) {
                val w = r.data.optJSONObject("wallet")
                if (w != null) balance = w.optInt("coins") else if (price > 0) balance = balance?.let { max(0, it - price) }
                sendFailed = false; failBtn.visibility = GONE
                main.postDelayed({ scrollToEnd(true) }, 60)
                // the message itself arrives live; show it now if it came back in the answer
                r.data.optJSONObject("message")?.let { m -> if (m.optString("id").isNotEmpty() && messages.none { it.optString("id") == m.optString("id") }) { messages.add(m); renderAll() } }
            }
            done(r)
        }
    }
    /** the red note under the chat when a message could not be sent: the real reason (only "no connection" names the internet) */
    private fun failText(r: Api.Result): String = when {
        r.status == 0 -> "Message didn't send. Check your internet and tap to try again."
        r.status == 429 && r.data.optString("error") == "rate_limited" -> "You're sending messages too fast. Tap to send again."
        r.status == 429 || r.data.optString("error") == "busy" -> "Message didn't send — the chat was busy for a moment. Tap to try again."
        r.status >= 500 -> "Message didn't send — something went wrong on our side. Tap to try again. (${r.status})"
        else -> "Message didn't send. Tap to try again." + (if (r.status > 0) " (${r.status})" else "")
    }
    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || other == null || sending || blockRow != null) return
        main.removeCallbacks(typingOff); setTyping(false)
        input.setText("")
        val problem = ChatSafety.problem(t)
        if (problem.isNotEmpty()) { holdBack(t, problem); return }
        sending = true; renderSendState()
        val again = failedKey.isNotEmpty() && t == failedText
        val key = if (again) failedKey else newKey()
        failedKey = ""; failedText = ""
        val forMatch = matchId
        deliver(JSONObject().put("text", t).put("kind", "text"), 0, key, again) { r ->
            sending = false
            if (!r.ok) {
                if (isPrivacyError(r.data)) holdBack(t, r.data.optString("reason").ifEmpty { r.data.optString("message") }.ifEmpty { "Numbers, links, IDs and abusive words aren't allowed." })
                else {
                    handleSendError(r, true); input.setText(t); input.setSelection(t.length)
                    if (forMatch == matchId) { failedKey = key; failedText = t }
                }
            }
            renderSendState()
        }
    }
    private fun sendGifNow(g: Gif) {
        if (other == null || blockRow != null) return
        val p = Pending("tmp-${System.currentTimeMillis()}", g)
        pending.add(p); renderTail(); main.postDelayed({ scrollToEnd(true) }, 60)
        deliver(JSONObject().put("kind", "gif").put("text", "").put("gif_id", g.id), g.price) { r ->
            if (!r.ok) {
                val d = r.data; val st = r.status
                val known = d.optString("error") in listOf("insufficient_coins", "privacy_blocked", "blocked", "content_blocked", "user_blocked", "blocked_user")
                if (!known && (st == 400 || (st == 422 && d.optString("reason").isEmpty()))) say("GIFs will work after the one-time server update.", "err") else handleSendError(r, false)
            }
            pending.remove(p); renderTail(); renderTray()
        }
    }

    // ═════════════ header ═════════════
    private fun buildHeader(): View {
        val row = hbox(context, 8f).apply { setPadding(dpi(2f), dpi(8f), dpi(4f), dpi(8f)) }
        row.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ArrowLeft, Color.WHITE, 26f, 2.3f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back to chats" }
            .press().onTap { goBack() }, lp(dpi(44f), dpi(44f)))
        val ph = FrameLayout(context)
        headRing.setPadding(dpi(2.5f), dpi(2.5f), dpi(2.5f), dpi(2.5f))
        val white = headWhite.apply { background = oval(pk.tint, 2f, Color.WHITE); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
        white.addView(headPhoto, LayoutParams(-1, -1)); headRing.addView(white, LayoutParams(-1, -1))
        ph.addView(headRing, LayoutParams(dpi(48f), dpi(48f)))
        headDot.background = oval(pk.green, 2.5f, Color.WHITE)
        ph.addView(headDot, LayoutParams(dpi(13f), dpi(13f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(1f); bottomMargin = dpi(1f) })
        ph.elevation = Ui.dp(3f)
        ph.press().onTap { openProfile() }
        row.addView(ph, lp(dpi(48f), dpi(48f)))
        val t = vbox(context).apply { setPadding(0, dpi(2f), 0, dpi(2f)) }
        t.addView(headName, lp(WRAP, WRAP))
        headStatus.orientation = LinearLayout.HORIZONTAL; headStatus.gravity = Gravity.CENTER_VERTICAL
        t.addView(headStatus, lp(MATCH, WRAP, t = 1f))
        t.onTap { openProfile() }
        row.addView(t, lp(0, WRAP, 1f))
        menuBtn.setImageDrawable(Icon(Lucide.MoreVertical, Color.WHITE, 24f, 2.6f)); menuBtn.scaleType = ImageView.ScaleType.CENTER; menuBtn.contentDescription = "Chat options"
        menuBtn.press().onTap { gifOpen = false; renderTray(); menu() }
        row.addView(menuBtn, lp(dpi(44f), dpi(44f)))
        return row
    }
    private val firstName get() = SdRules.s(other, "name").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    private val isOnline get() = onlineOf(other)
    private val isVerified get() = other?.let { it.optBoolean("verified") || it.optBoolean("is_verified") } == true
    private fun renderHeader() {
        val o = other
        headPhoto.set(listOf(otherPhotoRaw), Demo.round(JSONObject().put("id", SdRules.s(o, "profile_id", "id")).put("gender", SdRules.s(o, "gender")), Ui.dpi(43f)))
        val st = story
        headRing.background = when {
            // on the maroon band: a white ring; their story lights it bright pink (seen: pale)
            sdLook -> oval(if (st == null) Color.WHITE else if (st.optBoolean("seen")) Rs.c("#F0C9C8") else Rs.SEAL)
            st == null -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#F7569F"), Ui.c("#C7105F"))).apply { shape = GradientDrawable.OVAL }
            st.optBoolean("seen") -> oval(Ui.c("#F2C3D8"))
            else -> RingDrawable(intArrayOf(Ui.c("#FFC2DF"), Ui.c("#F7569F"), Ui.c("#E3086C"), Ui.c("#FF8CC8"), Ui.c("#FFC2DF")), floatArrayOf(0f, .25f, .5f, .75f, 1f))
        }
        headDot.visibility = if (isOnline && !loading) VISIBLE else GONE
        headName.text = SdRules.s(o, "name").ifEmpty { if (loading) "" else "Chat" }
        headName.setCompoundDrawablesWithIntrinsicBounds(null, null, if (!isVerified) null else if (sdLook) Seal(19f, Rs.SEAL, Color.WHITE) else Seal(19f, Color.WHITE, pk.rose), null); headName.compoundDrawablePadding = dpi(6f)
        headStatus.removeAllViews()
        if (!loading) {
            when {
                otherTyping -> headStatus.addView(sdText(context, "typing…", 13.5f, Color.WHITE))
                isOnline -> { headStatus.addView(View(context).apply { background = oval(pk.green) }, lp(dpi(8f), dpi(8f), r = 5f)); headStatus.addView(sdText(context, "Online now", 13.5f, Color.WHITE, bold = false)) }
                else -> headStatus.addView(sdText(context, lastSeenOf(o), 13.5f, Color.WHITE, bold = false))
            }
            if (!otherTyping) headStatus.addView(sdText(context, " · tap for profile", 13.5f, pa(217, 255, 255, 255), bold = false).oneLine(), lp(0, WRAP, 1f))
        }
        menuBtn.visibility = if (SdRules.s(o, "profile_id").isNotEmpty()) VISIBLE else GONE
        (headPhoto.parent?.parent?.parent as? View)?.contentDescription = "Open ${SdRules.s(o, "name").ifEmpty { "user" }}'s profile"
    }

    private fun renderError() {
        errorBox.removeAllViews()
        errorBox.visibility = if (error.isEmpty()) GONE else VISIBLE
        if (error.isEmpty()) return
        errorBox.addView(sdText(context, error, 15f, CK.red, bold = false).apply { gravity = Gravity.CENTER; lineHeight(1.2f) }, lp(MATCH, WRAP))
        if (other == null && !lowCoins) errorBox.addView(btn("Open your chats") { replace("/secret-app?tab=chat") }, lp(MATCH, dpi(44f), t = 10f))
        if (lowCoins) errorBox.addView(btn("Top up in Wallet") { go("/wallet?app=$chatApp", null) }, lp(MATCH, dpi(44f), t = 10f))
    }
    private fun btn(t: String, onClick: () -> Unit) = sdText(context, t, 16f, Color.WHITE).apply { gravity = Gravity.CENTER; background = ckBtn(22f) }.press().onTap { onClick() }

    // ═════════════ the list ═════════════
    private fun renderAll() { renderHeader(); renderError(); renderInfo(); renderList(); renderTail(); renderTray(); renderComposer(); refreshFx() }

    private fun renderInfo() {
        infoBox.removeAllViews()
        if (loading || error.isNotEmpty()) return
        // (no "Chat open till … · … left" any more: a chat once opened stays open — owner, 20 Nov)
        infoBox.addView(infoPill("Numbers, links, IDs, email & abusive words are not sent", Lucide.ShieldCheck, pk.sub, false), lp(WRAP, WRAP, t = 2f, b = 8f))
    }
    private fun infoPill(t: String, icon: String, color: Int, bold: Boolean) = sdText(context, t, if (bold) 13.5f else 13f, color, bold = bold).icon(Icon(icon, pk.rose, 15f), 7f).apply {
        gravity = Gravity.CENTER_VERTICAL; background = rounded(Color.WHITE, 99f, 1f, pk.line2); setPadding(dpi(13f), dpi(7f), dpi(13f), dpi(7f)); lineHeight(1.1f)
    }

    private var shownKeys: List<String> = emptyList()
    private val ticks = HashMap<String, ImageView>()
    private fun myHeld() = held.filter { it.optString("by").isEmpty() || meId.isEmpty() || it.optString("by") == meId }
    private fun entries(): List<Pair<String, () -> View>> {
        val out = ArrayList<Pair<String, () -> View>>()
        val ids = messages.map { it.optString("id") }.toHashSet()
        val after = HashMap<String, MutableList<JSONObject>>(); val atEnd = ArrayList<JSONObject>()
        for (h in myHeld()) {
            val a = h.optString("after")
            if (a.isEmpty()) out.add(h.optString("id") to { heldView(h) })
            else if (a in ids) after.getOrPut(a) { ArrayList() }.add(h) else atEnd.add(h)
        }
        var lastDay = ""
        for (m in messages) {
            val d = dayOf(SdRules.ms(m.optString("created_date")))
            if (d.isNotEmpty() && d != lastDay) { out.add("d-${m.optString("id")}" to { divider(d) }); lastDay = d }
            out.add(m.optString("id") to { bubble(m) })
            for (h in after[m.optString("id")] ?: emptyList()) out.add(h.optString("id") to { heldView(h) })
        }
        for (h in atEnd) out.add(h.optString("id") to { heldView(h) })
        return out
    }
    private fun renderList() {
        val showEmpty = !loading && messages.isEmpty() && pending.isEmpty() && myHeld().isEmpty()
        spinner.visibility = if (loading) VISIBLE else GONE
        renderEmpty(showEmpty && error.isEmpty())
        if (loading || showEmpty) { listCol.removeAllViews(); shownKeys = emptyList(); return }
        val es = entries(); val keys = es.map { it.first }
        if (keys != shownKeys) {
            val before = shownKeys.toHashSet()
            fun add(e: Pair<String, () -> View>) {
                val v = e.second()
                listCol.addView(v, lp(MATCH, WRAP))
                if (e.first !in before && !Lite.reducedMotion) { v.alpha = 0f; v.translationY = Ui.dp(6f); v.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start() }
            }
            if (keys.size > shownKeys.size && keys.subList(0, shownKeys.size) == shownKeys) for (e in es.drop(shownKeys.size)) add(e)
            else { listCol.removeAllViews(); ticks.clear(); for (e in es) add(e) }
            shownKeys = keys
        }
        for (m in messages) ticks[m.optString("id")]?.let { paintTick(it, m) }
    }
    private fun renderEmpty(show: Boolean) {
        emptyBox.removeAllViews()
        if (!show) return
        emptyBox.setPadding(dpi(10f), dpi(44f), dpi(10f), dpi(44f))
        val wave = sdText(context, "👋", 50f, Color.BLACK, bold = false)
        emptyBox.addView(wave); Emoji.animate(wave, "wave")
        emptyBox.addView(sdText(context, "Say hi to ${firstName.ifEmpty { "them" }}", 26f, Color.WHITE).shadow(2f, 1f, pa(71, 160, 20, 80)).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, t = 10f, b = 4f))
        emptyBox.addView(sdText(context, "Send a message or a GIF.", 16f, Color.WHITE).shadow(2f, 1f, pa(71, 160, 20, 80)).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        if (blockRow == null) {
            val r = hbox(context, 10f, Gravity.CENTER)
            for (g in GIFS.take(3)) {
                val b = FrameLayout(context).apply { background = rounded(Color.WHITE, 20f, 1f, pk.line); elevation = Ui.dp(3f); contentDescription = "Send ${g.label} GIF" }
                val e = sdText(context, g.e, 34f, Color.BLACK, bold = false); b.addView(e, LayoutParams(-2, -2, Gravity.CENTER)); Emoji.animate(e, g.cls)
                b.press().onTap { pickGif(g) }
                r.addView(b, lp(dpi(74f), dpi(74f)))
            }
            emptyBox.addView(r, lp(MATCH, WRAP, t = 18f))
        }
    }
    private fun renderTail() {
        tailCol.removeAllViews()
        for (p in pending) {
            val f = FrameLayout(context).apply { alpha = 0.85f; setPadding(dpi(6f), dpi(4f), dpi(6f), dpi(22f)) }
            f.addView(gifView(p.gif, big = false, showPrice = false, onPink = false), LayoutParams(-2, -2))
            val tag = hbox(context, 4f).apply { background = rounded(Color.WHITE, 9f, 1f, pk.line2); setPadding(dpi(8f), dpi(2f), dpi(8f), dpi(2f)) }
            tag.addView(ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(pk.sub) }, lp(dpi(12f), dpi(12f)))
            tag.addView(sdText(context, "Sending", 11.5f, pk.sub, bold = false))
            f.addView(tag, LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(4f) })
            tailCol.addView(f, lp(WRAP, WRAP, gravity = Gravity.END))
        }
        if (otherTyping) {
            val b = hbox(context, 4f).apply { background = bubbleBg(false, false); setPadding(dpi(15f), dpi(12f), dpi(15f), dpi(12f)) }
            b.contentDescription = "${firstName.ifEmpty { "They" }} is typing"
            for (i in 0 until 3) { val d = View(context).apply { background = oval(pk.rose) }; b.addView(d, lp(dpi(7f), dpi(7f))); Emoji.typingDot(d, i) }
            tailCol.addView(b, lp(WRAP, WRAP))
        }
    }
    private fun scrollToEnd(smooth: Boolean) {
        main.postDelayed({ val y = max(0, listWrap.height - scroll.height); if (smooth) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y) }, if (smooth) 100 else 60)
    }

    private fun divider(t: String) = FrameLayout(context).apply {
        addView(sdText(context, t, 12.5f, pk.sub).apply { letterSpacing = .03f; background = rounded(Color.WHITE, 99f, 1f, pk.line2); setPadding(dpi(13f), dpi(4f), dpi(13f), dpi(4f)) },
            LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(8f); bottomMargin = dpi(4f) })
    }

    private val maxBubble get() = ((resources.displayMetrics.widthPixels - dpi(24f)) * 0.8f).toInt()
    private fun bubbleBg(mine: Boolean, bare: Boolean): Drawable? {
        if (bare) return null
        val r = Ui.dp(18f); val s = Ui.dp(6f)
        val radii = if (mine) floatArrayOf(r, r, r, r, s, s, r, r) else floatArrayOf(r, r, r, r, r, r, s, s)
        if (sdLook) return GradientDrawable().apply { setColor(if (mine) Rs.M else Color.WHITE); cornerRadii = radii; if (!mine) setStroke(dpi(1f), Rs.LINE) }
        return if (mine) CssGradient(160f, intArrayOf(Ui.c("#F43D95"), Ui.c("#E3086C"), Ui.c("#C80962")), floatArrayOf(0f, .55f, 1f), radii, Ui.dp(1.5f), Color.WHITE)
        else GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = radii; setStroke(dpi(1f), pk.line2) }
    }
    private fun bubble(m: JSONObject): View {
        val mine = isMine(m)
        val k = kindOf(m)
        val bare = k == "gif" && SdRules.s(m, "gif_url").isEmpty()
        val wrap = FrameLayout(context)
        val b = vbox(context)
        b.background = bubbleBg(mine, bare)
        if (!bare && !sdLook) { b.elevation = Ui.dp(2f); b.outlineSpotShadowColorCompat(if (mine) Color.argb(178, 200, 9, 98) else Color.argb(89, 150, 0, 60)) }
        when (k) {
            "photo" -> b.setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
            "gif" -> if (bare) b.setPadding(dpi(6f), dpi(4f), dpi(6f), dpi(4f)) else b.setPadding(dpi(13f), dpi(9f), dpi(13f), dpi(7f))
            else -> b.setPadding(dpi(13f), dpi(9f), dpi(13f), dpi(7f))
        }
        val inkOnBubble = if (mine) Color.WHITE else pk.ink
        when (k) {
            "photo" -> b.addView(photoBubble(SdRules.s(m, "media_uri", "media_url")), lp(dpi(210f).coerceAtMost(maxBubble), (dpi(210f).coerceAtMost(maxBubble) * 5 / 4)))
            "voice" -> b.addView(voiceBubble(SdRules.s(m, "media_uri", "media_url"), m.optDouble("duration_sec", 0.0), mine), lp(WRAP, WRAP))
            "gif" -> {
                val g = gifOf(m.optString("gif_id"))
                val url = SdRules.s(m, "gif_url")
                if (url.startsWith("https://")) b.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; clipToOutline = true; outlineProvider = roundOutline(14f); Photos.loadFull(url, 600) { bm -> if (bm != null) setImageBitmap(bm) } }, lp(dpi(170f), dpi(170f)))
                else if (g == null) b.addView(sdText(context, "GIF", 13f, inkOnBubble, bold = false).apply { alpha = 0.7f }, lp(WRAP, WRAP))
                else {
                    b.addView(gifView(g, big = false, showPrice = mine, onPink = false), lp(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL))
                    if (!mine) b.addView(sdText(context, "You earned ${fmtHalf(g.price)}", 12f, pc("#17803A")).icon(Icon(Lucide.CoinsIcon, pc("#17803A"), 12f), 4f).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, t = 4f, gravity = Gravity.CENTER_HORIZONTAL))
                }
            }
            else -> b.addView(sdText(context, m.optString("text"), 16f, inkOnBubble, bold = false).apply { lineHeight(1.15f); maxWidth = maxBubble - dpi(26f); setTextIsSelectable(false) }, lp(WRAP, WRAP))
        }
        val meta = hbox(context, 4f, Gravity.END or Gravity.CENTER_VERTICAL)
        if (k == "photo") meta.setPadding(dpi(6f), 0, dpi(6f), dpi(2f))
        val metaColor = if (mine && !bare && k != "photo") pa(217, 255, 255, 255) else pk.soft
        meta.addView(sdText(context, timeOf(SdRules.ms(m.optString("created_date"))), 11.5f, metaColor, bold = false))
        if (mine) { val tick = ImageView(context); meta.addView(tick, lp(dpi(14f), dpi(14f))); ticks[m.optString("id")] = tick; tick.tag = (bare || k == "photo"); paintTick(tick, m) }
        b.addView(meta, lp(WRAP, WRAP, t = 3f, gravity = Gravity.END))
        wrap.addView(b, LayoutParams(-2, -2, if (mine) Gravity.END else Gravity.START))
        return wrap
    }
    private fun paintTick(v: ImageView, m: JSONObject) {
        val onWhite = v.tag == true
        val read = m.optBoolean("read")
        // same as the web (ChatRoom.jsx): two ticks always — blue when read, grey / soft white when only sent
        // (on the maroon bubble a lighter blue, so "read" can be told from "sent")
        v.setImageDrawable(if (read) Icon(Lucide.CheckCheck, if (sdLook && !onWhite) Ui.c("#8FD3FF") else Ui.c("#3B82F6"), 14f) else Icon(Lucide.CheckCheck, if (onWhite) Color.argb(179, 140, 135, 139) else Color.argb(179, 255, 255, 255), 14f))
        v.contentDescription = if (read) "Read" else "Sent"
    }
    private fun heldView(h: JSONObject): View {
        val col = vbox(context, 0f, Gravity.END)
        val row = hbox(context, 7f)
        row.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.X, Color.WHITE, 15f, 3f)); scaleType = ImageView.ScaleType.CENTER; background = oval(CK.red); elevation = Ui.dp(2f); contentDescription = "Not sent" }, lp(dpi(24f), dpi(24f)))
        val b = vbox(context).apply {
            background = GradientDrawable().apply { setColor(pc("#FFF1F3")); val r = Ui.dp(18f); val s = Ui.dp(6f); cornerRadii = floatArrayOf(r, r, r, r, s, s, r, r); setStroke(dpi(1f), pc("#F8B4C2")) }
            setPadding(dpi(13f), dpi(9f), dpi(13f), dpi(7f))
        }
        b.addView(sdText(context, h.optString("text"), 16f, pc("#6B1730"), bold = false).apply { lineHeight(1.15f); maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt() - dpi(80f) }, lp(WRAP, WRAP))
        b.addView(sdText(context, timeOf(h.optLong("at")), 11.5f, pc("#B04A63"), bold = false).icon(Icon(Lucide.X, CK.red, 13f, 3f), 4f, end = true), lp(WRAP, WRAP, t = 3f, gravity = Gravity.END))
        row.addView(b, lp(WRAP, WRAP))
        col.addView(row, lp(WRAP, WRAP))
        val note = hbox(context, 8f, Gravity.END or Gravity.CENTER_VERTICAL).apply { background = rounded(Color.WHITE, 12f); setPadding(dpi(10f), dpi(3f), dpi(10f), dpi(3f)) }
        note.addView(sdText(context, "Not sent · ${h.optString("reason")}", 13f, CK.red).apply { maxWidth = (resources.displayMetrics.widthPixels * 0.6f).toInt() })
        note.addView(sdText(context, "Remove", 13f, pk.sub).apply { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; contentDescription = "Remove this unsent message"; setPadding(0, dpi(2f), 0, dpi(2f)) }.onTap { dropHeld(h.optString("id")) })
        col.addView(note, lp(WRAP, WRAP, t = 4f))
        return FrameLayout(context).apply { addView(col, LayoutParams(-2, -2, Gravity.END)) }
    }
    private fun photoBubble(uri: String): View {
        val f = FrameLayout(context).apply { background = rounded(pk.tint, 14f); clipToOutline = true; outlineProvider = roundOutline(14f); contentDescription = "Open photo" }
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val sp = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(pk.rose) }
        f.addView(img, LayoutParams(-1, -1)); f.addView(sp, LayoutParams(dpi(22f), dpi(22f), Gravity.CENTER))
        var loaded: Bitmap? = null
        Photos.loadFull(uri, 1200) { b -> if (b != null) { loaded = b; img.setImageBitmap(b); sp.visibility = GONE } }
        f.onTap { loaded?.let { viewPhoto(it) } }
        return f
    }
    private fun voiceBubble(uri: String, secs: Double, mine: Boolean): View {
        val r = hbox(context, 10f).apply { minimumWidth = dpi(190f) }
        val play = FrameLayout(context).apply { background = oval(if (mine) Color.WHITE else pk.pill) }
        val ic = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
        play.addView(ic, LayoutParams(-1, -1))
        r.addView(play, lp(dpi(38f), dpi(38f)))
        val right = vbox(context)
        val bars = object : View(context) {
            var pct = 0f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val n = 26; val gap = Ui.dp(2f); val bw = (width - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val hh = Ui.dp((5 + abs(sin(i * 1.7 + uri.length)) * 15).toFloat())
                    p.color = if ((i / 26f) * 100 < pct) (if (mine) Color.WHITE else pk.hot) else if (mine) pa(115, 255, 255, 255) else pc("#F3C6DA")
                    val x = i * (bw + gap); c.drawRoundRect(x, (height - hh) / 2, x + bw, (height + hh) / 2, Ui.dp(2f), Ui.dp(2f), p)
                }
            }
        }
        right.addView(bars, lp(MATCH, dpi(22f)))
        val time = sdText(context, mmss(secs), 11.5f, if (mine) pa(217, 255, 255, 255) else pk.soft, bold = false)
        right.addView(time)
        r.addView(right, lp(0, WRAP, 1f))
        fun paint(playing: Boolean) { ic.setImageDrawable(Icon(if (playing) Lucide.Pause else Lucide.Play, if (mine) pk.hot else Color.WHITE, 17f, 2f, if (mine) pk.hot else Color.WHITE)) }
        paint(false)
        play.contentDescription = "Play voice message"
        play.press().onTap {
            Voice.toggle(uri, onState = { playing, pos, dur ->
                paint(playing)
                bars.pct = if (dur > 0) min(100f, (pos / dur * 100).toFloat()) else 0f; bars.invalidate()
                time.text = mmss(if (playing || pos > 0) pos else if (dur > 0) dur else secs)
                play.contentDescription = if (playing) "Pause voice message" else "Play voice message"
            })
        }
        return r
    }

    // ═════════════ GIF gifts ═════════════
    private fun fmtHalf(p: Int) = if (p % 2 == 0) (p / 2).toString() else (p / 2.0).toString()
    private fun gifView(g: Gif, big: Boolean, showPrice: Boolean, onPink: Boolean): View {
        val size = if (big) 70f else 46f
        val emoji: View = if (g.many) {
            FrameLayout(context).apply {
                for ((i, t) in listOf(Triple(-0.32f, 0.12f, 0.8f), Triple(0.32f, 0.12f, 0.8f), Triple(0f, 0f, 1f)).withIndex()) {
                    val e = sdText(context, g.e, size * t.third, Color.BLACK, bold = false)
                    addView(e, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { leftMargin = dpi(size * 1.6f * t.first * 0.6f * 2); topMargin = dpi(size * 1.15f * t.second) })
                    Emoji.animate(e, g.cls, i * 200L)
                }
                minimumWidth = dpi(size * 1.6f); minimumHeight = dpi(size * 1.15f)
            }
        } else sdText(context, g.e, size, Color.BLACK, bold = false).also { Emoji.animate(it, g.cls) }
        if (!g.vip) {
            val v = vbox(context, 2f, Gravity.CENTER_HORIZONTAL).apply { if (big) setPadding(dpi(8f), dpi(4f), dpi(8f), dpi(4f)) }
            v.addView(emoji, lp(WRAP, WRAP))
            v.addView(sdText(context, g.label, if (big) 18f else 15f, if (onPink) Color.WHITE else pk.wine, italic = true), lp(WRAP, WRAP))
            if (showPrice) v.addView(sdText(context, g.price.toString(), 11.5f, if (onPink) Color.WHITE else pk.hot).icon(Icon(Lucide.CoinsIcon, if (onPink) Color.WHITE else pk.hot, 12f), 3f).apply { alpha = 0.9f }, lp(WRAP, WRAP))
            return v
        }
        // premium gift card: gold frame, glow, sparkles, shine
        val w = if (big) 190f else 150f
        val card = FrameLayout(context).apply {
            background = VipBg(); clipToOutline = true; outlineProvider = roundOutline(20f); elevation = Ui.dp(4f)
            outlineSpotShadowColorCompat(pa(191, 200, 10, 100))
            setPadding(dpi(if (big) 10f else 8f), dpi(if (big) 16f else 12f), dpi(if (big) 10f else 8f), dpi(if (big) 12f else 10f))
        }
        val glowTop = if (big) 50f else 40f
        card.addView(Glow(context, size).also { Emoji.animate(it, "glow") }, LayoutParams(dpi(size * 2), dpi(size * 2), Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(glowTop - size) - dpi(if (big) 16f else 12f) })
        if (!Lite.on) card.addView(Sparkles(context, size * 0.85f), LayoutParams(dpi(size * 2), dpi(size * 2), Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(glowTop - size) - dpi(if (big) 16f else 12f) })
        val v = vbox(context, 0f, Gravity.CENTER_HORIZONTAL)
        v.addView(emoji, lp(WRAP, WRAP))
        v.addView(sdText(context, g.label, if (big) 20f else 17f, Color.WHITE, italic = true).shadow(6f, 1f, pa(128, 90, 0, 30)), lp(WRAP, WRAP, t = 4f))
        val chip = sdText(context, "PREMIUM" + if (showPrice) " · " else "", 11f, pc("#5A2A00")).apply {
            letterSpacing = .06f; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pc("#FBE7B5"), pc("#E2B04F"))).apply { cornerRadius = Ui.dp(99f) }
            setPadding(dpi(8f), dpi(2f), dpi(8f), dpi(2f))
        }
        if (showPrice) chip.text = android.text.SpannableStringBuilder("PREMIUM · ").append(coinSpan(11f, pc("#5A2A00"))).append(g.price.toString())
        v.addView(chip, lp(WRAP, WRAP, t = 4f))
        card.addView(v, LayoutParams(-1, -2))
        if (!Lite.on) card.addView(Shine(context), LayoutParams(-1, -1))
        return FrameLayout(context).apply { addView(card, LayoutParams(dpi(w), -2)) }
    }
    private fun coinSpan(sizeDp: Float, color: Int): CharSequence {
        val s = android.text.SpannableString("￼")
        val d = Icon(Lucide.CoinsIcon, color, sizeDp); d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        s.setSpan(IconSpan(d, Ui.dpi(3f), false), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
    /** radial-gradient(120% 90% at 50% 0%, rgba(255,226,170,.6), rgba(226,20,112,.92) 55%, rgba(128,6,56,.98)) + gold border */
    private class VipBg(private val strong: Boolean = false) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.c("#F8E2AD"); strokeWidth = Ui.dp(1.5f) }
        override fun onBoundsChange(b: android.graphics.Rect) {
            p.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(if (strong) 191 else 153, 255, 226, 170), Color.argb(235, 226, 20, 112), Color.argb(250, 128, 6, 56)), floatArrayOf(0f, if (strong) .6f else .55f, 1f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setScale(b.width() * 1.2f, b.height() * 0.9f); postTranslate(b.exactCenterX(), b.top.toFloat()) })
            }
        }
        override fun draw(c: Canvas) { val r = Ui.dp(if (strong) 16f else 20f); val b = RectF(bounds); c.drawRoundRect(b, r, r, p); b.inset(Ui.dp(.75f), Ui.dp(.75f)); c.drawRoundRect(b, r, r, s) }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    private class Glow(ctx: Context, private val size: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            p.shader = RadialGradient(w / 2f, h / 2f, w / 2f * 1.414f, intArrayOf(Color.argb(153, 255, 236, 190), Color.argb(51, 255, 160, 200), Color.argb(0, 0, 0, 0)), floatArrayOf(0f, .5f, .7f), Shader.TileMode.CLAMP)
        }
        override fun onDraw(c: Canvas) { c.drawCircle(width / 2f, height / 2f, width / 2f, p) }
    }
    /** three ✨ orbiting the gift */
    private class Sparkles(ctx: Context, private val radiusDp: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dp(10f); textAlign = Paint.Align.CENTER }
        private val t0 = System.currentTimeMillis()
        override fun onDraw(c: Canvas) {
            val now = System.currentTimeMillis() - t0
            for (i in 0 until 3) {
                val dur = (2.6 + i * 0.7) * 1000; val delay = -i * 900
                val a = 2 * Math.PI * (((now - delay) % dur) / dur)
                val x = width / 2f + (Ui.dp(radiusDp) * kotlin.math.cos(a)).toFloat(); val y = height / 2f + (Ui.dp(radiusDp) * sin(a)).toFloat()
                c.drawText("✨", x, y + Ui.dp(4f), p)
            }
            if (!Lite.reducedMotion && isShown) postInvalidateOnAnimation()
        }
    }
    /** the light sweeping across a premium gift */
    private class Shine(ctx: Context) : View(ctx) {
        private val p = Paint()
        private val t0 = System.currentTimeMillis()
        override fun onDraw(c: Canvas) {
            if (Lite.reducedMotion) return
            val f = ((System.currentTimeMillis() - t0) % 3200) / 3200f
            val k = if (f < 0.6f) EASE.getInterpolation(f / 0.6f) else 1f
            val w = width * 0.4f
            val x = (-1.4f + k * 3.8f) * w
            c.save(); c.skew(-0.32f, 0f); c.translate(x, 0f)
            p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(77, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, height.toFloat(), p); c.restore()
            if (isShown) postInvalidateOnAnimation()
        }
        companion object { val EASE = android.view.animation.AccelerateDecelerateInterpolator() }
    }

    private fun pickGif(g: Gif) { if (System.currentTimeMillis() - swipedAt < 350) return; giftSheet(g) }
    private var swipedAt = 0L

    private fun renderTray() {
        tray.removeAllViews()
        tray.visibility = if (gifOpen && blockRow == null) VISIBLE else GONE
        if (tray.visibility == GONE) tray.tag = null
        gifBtn.background = rounded(if (gifOpen) pk.pill else pk.tint, 18f); gifBtn.setTextColor(if (gifOpen) Color.WHITE else pk.hot)
        giftBtn.background = if (gifOpen) oval(Rs.M) else oval(Color.WHITE, 1f, Rs.LINE)
        giftBtn.setImageDrawable(Icon(Lucide.Gift, if (gifOpen) Color.WHITE else Rs.M, 24f, 1.9f))
        if (!gifOpen || blockRow != null) return
        val box = vbox(context).apply { background = rounded(Color.WHITE, 22f, 1f, pk.line2); setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(8f)); elevation = Ui.dp(6f) }
        val head = hbox(context, 8f)
        head.addView(sdText(context, "Send a GIF", 20f, pk.deep), lp(0, WRAP, 1f))
        val bal = sdText(context, "${balance ?: "–"} ", 14f, pk.wine).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(pk.tint2, 16f, 1f, pk.line); setPadding(dpi(11f), 0, dpi(11f), 0)
            setCompoundDrawablesWithIntrinsicBounds(Icon(Lucide.CoinsIcon, pk.hot, 15f), null, null, null); compoundDrawablePadding = dpi(5f)
            append(android.text.SpannableString("+").apply { setSpan(android.text.style.ForegroundColorSpan(pk.hot), 0, 1, 0); setSpan(android.text.style.RelativeSizeSpan(16f / 14f), 0, 1, 0) })
            contentDescription = "Balance ${balance ?: "–"} coins. Top up"
        }
        head.addView(bal.press().onTap { go("/wallet?app=$chatApp", null) }, lp(WRAP, dpi(32f)))
        head.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.X, pk.wine, 20f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Close GIFs" }.onTap { gifOpen = false; renderTray() }, lp(dpi(36f), dpi(36f)))
        box.addView(head, lp(MATCH, WRAP, l = 2f, r = 2f, b = 8f))
        // the categories
        val tabsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val tabs = hbox(context, 6f)
        var selectedTab: View? = null
        for (t in GIF_GROUPS) {
            val on = gifTab == t.first
            val tv = sdText(context, (if (t.first == "premium") "👑 " else "") + t.second, 14f, if (on) Color.WHITE else pk.wine).apply {
                gravity = Gravity.CENTER; setPadding(dpi(14f), 0, dpi(14f), 0)
                background = if (on) (if (t.first == "premium") GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pc("#F2B84B"), pc("#FF6FAF"), pc("#E3086C"))).apply { cornerRadius = Ui.dp(17f) } else ckPill(17f)) else rounded(Color.WHITE, 17f, 1f, pk.line2)
            }
            if (on) selectedTab = tv
            tabs.addView(tv.press().onTap { gifTab = t.first; renderTray() }, lp(WRAP, dpi(34f)))
        }
        tabsScroll.addView(tabs)
        box.addView(tabsScroll, lp(MATCH, WRAP, b = 10f))
        selectedTab?.let { st -> tabsScroll.post { tabsScroll.smoothScrollTo(max(0, st.left - (tabsScroll.width - st.width) / 2), 0) } }
        // the grid (swipe sideways for the next category)
        val gridScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val grid = vbox(context, 8f)
        val list = GIFS.filter { it.g == gifTab }
        val cellW = (resources.displayMetrics.widthPixels - dpi(20f) - dpi(20f) - dpi(8f) * 3) / 4
        for (i in list.indices step 4) {
            val row = hbox(context, 8f, Gravity.TOP)
            for (g in list.subList(i, min(i + 4, list.size))) row.addView(gifTile(g), lp(cellW, (cellW / 0.86f).toInt()))
            grid.addView(row, lp(MATCH, WRAP))
        }
        gridScroll.addView(grid)
        var sx = 0f; var sy = 0f; var st0 = 0L
        gridScroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; st0 = System.currentTimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val dx = (e.rawX - sx) / Ui.density; val dy = (e.rawY - sy) / Ui.density
                    if (abs(dx) >= 45 && abs(dx) >= abs(dy) * 1.3f && System.currentTimeMillis() - st0 <= 800) {
                        val i = GIF_GROUPS.indexOfFirst { it.first == gifTab }
                        val next = GIF_GROUPS[max(0, min(GIF_GROUPS.size - 1, i + if (dx < 0) 1 else -1))]
                        if (next.first != gifTab) { gifTab = next.first; swipedAt = System.currentTimeMillis(); post { renderTray() } }
                    }
                }
            }
            false
        }
        box.addView(gridScroll, lp(MATCH, dpi(214f)))
        box.addView(sdText(context, "Swipe left or right for more · Text messages are free", 12.5f, pk.soft, bold = false).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, l = 4f, r = 4f, t = 8f))
        tray.addView(box, LayoutParams(-1, -2))
        if (tray.tag != "up") { tray.tag = "up"; tray.translationY = Ui.dp(300f); tray.animate().translationY(0f).setStartDelay(70).setDuration(340).setInterpolator(SdLayer.EASE_UP).start() }
    }
    private fun gifTile(g: Gif): View {
        val v = vbox(context, 3f, Gravity.CENTER).apply {
            background = if (g.vip) VipBg(true) else rounded(pk.tint2, 16f, 1f, pk.line2)
            if (g.vip) { elevation = Ui.dp(4f); outlineSpotShadowColorCompat(pa(178, 200, 10, 100)) }
            setPadding(dpi(2f), dpi(6f), dpi(2f), dpi(6f))
            contentDescription = "${g.label} GIF, ${g.price} ${if (g.price == 1) "coin" else "coins"}"
        }
        val e = sdText(context, g.e, 30f, Color.BLACK, bold = false); v.addView(e, lp(WRAP, WRAP)); Emoji.animate(e, g.cls)
        v.addView(sdText(context, g.label, 12f, if (g.vip) Color.WHITE else pk.wine).oneLine().apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        val price = sdText(context, g.price.toString(), 12f, if (g.vip) pc("#5A2A00") else pk.hot).icon(Icon(Lucide.CoinsIcon, if (g.vip) pc("#5A2A00") else pk.hot, 12f), 3f).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (g.vip) { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pc("#FBE7B5"), pc("#E2B04F"))).apply { cornerRadius = Ui.dp(99f) }; setPadding(dpi(7f), dpi(1f), dpi(7f), dpi(1f)) }
        }
        v.addView(price, lp(WRAP, WRAP))
        return v.press().onTap { pickGif(g) }
    }

    // ═════════════ composer — text + GIF only ═════════════
    private fun buildComposer(): View {
        composer.setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(12f))
        val row = hbox(context, 8f, Gravity.BOTTOM)
        // Secret Dating's look: the gifts on a round button of their own, left of the message box
        giftBtn.setImageDrawable(Icon(Lucide.Gift, Rs.M, 24f, 1.9f)); giftBtn.scaleType = ImageView.ScaleType.CENTER
        giftBtn.background = oval(Color.WHITE, 1f, Rs.LINE); giftBtn.contentDescription = "Gifts"
        giftBtn.press().onTap { gifOpen = !gifOpen; if (gifOpen) { hideKeyboard(); emojiOpen = false; renderEmoji() }; renderTray() }
        row.addView(giftBtn, lp(dpi(52f), dpi(52f)))
        inputBox.orientation = LinearLayout.HORIZONTAL; inputBox.gravity = Gravity.CENTER_VERTICAL
        inputBox.background = rounded(Color.WHITE, 26f, 1.5f, pk.line)
        inputBox.elevation = Ui.dp(3f); inputBox.outlineSpotShadowColorCompat(pa(115, 150, 0, 60))
        inputBox.setPadding(dpi(6f), 0, dpi(6f), 0); inputBox.minimumHeight = dpi(52f)
        gifBtn.gravity = Gravity.CENTER; gifBtn.letterSpacing = .05f; gifBtn.setPadding(dpi(11f), 0, dpi(11f), 0); gifBtn.contentDescription = "GIFs"
        gifBtn.press().onTap { gifOpen = !gifOpen; if (gifOpen) hideKeyboard(); renderTray() }
        inputBox.addView(gifBtn, lp(WRAP, dpi(36f)))
        input.background = null; input.setTextColor(pk.ink); input.setHintTextColor(pc("#A8909C")); input.typeface = Ui.regular
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(16f)); input.isSingleLine = true; input.filters = arrayOf(InputFilter.LengthFilter(500))
        input.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI; input.setPadding(dpi(6f), 0, dpi(6f), 0)
        input.contentDescription = "Message"
        input.setOnEditorActionListener { _, a, ev -> if (a == EditorInfo.IME_ACTION_SEND || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { send(); true } else false }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onType(s.toString()) }
        })
        input.setOnFocusChangeListener { _, f ->
            inputBox.background = rounded(Color.WHITE, 26f, if (sdLook) 1f else 1.5f, if (f) pk.rose else pk.line)
            if (f) { if (gifOpen) { gifOpen = false; renderTray() }; main.postDelayed({ scrollToEnd(false) }, 250) }
        }
        inputBox.addView(input, lp(0, dpi(48f), 1f))
        emojiBtn.scaleType = ImageView.ScaleType.CENTER; emojiBtn.contentDescription = "Emojis"
        emojiBtn.press().onTap { emojiOpen = !emojiOpen; if (emojiOpen && gifOpen) { gifOpen = false; renderTray() }; renderEmoji() }
        inputBox.addView(emojiBtn, lp(dpi(38f), dpi(44f)))
        counter.visibility = GONE
        inputBox.addView(counter, lp(WRAP, WRAP, r = 4f))
        row.addView(inputBox, lp(0, WRAP, 1f))
        sendBtn.background = ckOval()
        sendIcon.setImageDrawable(Icon(Lucide.Send, Color.WHITE, 21f)); sendIcon.scaleType = ImageView.ScaleType.CENTER; sendIcon.translationX = -Ui.dp(2f)
        sendBtn.addView(sendIcon, LayoutParams(-1, -1)); sendBtn.addView(sendSpin, LayoutParams(dpi(21f), dpi(21f), Gravity.CENTER)); sendSpin.visibility = GONE
        sendBtn.contentDescription = "Send"
        sendBtn.press().onTap { send() }
        row.addView(sendBtn, lp(dpi(52f), dpi(52f)))
        composer.addView(row, LayoutParams(-1, -2))
        // blocked: "You blocked Priya." · Unblock
        blockedBar.orientation = LinearLayout.HORIZONTAL; blockedBar.gravity = Gravity.CENTER_VERTICAL
        blockedBar.background = rounded(Color.WHITE, 20f, 1f, pk.line); blockedBar.elevation = Ui.dp(3f)
        blockedBar.setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
        blockedBar.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Ban, CK.red, 19f)) }, lp(dpi(19f), dpi(19f), r = 10f))
        blockedBar.addView(blockedText, lp(0, WRAP, 1f))
        blockedBar.addView(unblockBtn.apply { gravity = Gravity.CENTER; background = ckPill(19f); setPadding(dpi(16f), 0, dpi(16f), 0) }.press().onTap { unblock() }, lp(WRAP, dpi(38f), l = 10f))
        blockedBar.visibility = GONE
        composer.addView(blockedBar, LayoutParams(-1, -2))
        return composer
    }
    private fun renderComposer() {
        val blocked = blockRow != null
        (inputBox.parent as View).visibility = if (blocked) GONE else VISIBLE
        blockedBar.visibility = if (blocked) VISIBLE else GONE
        blockedText.text = "You blocked ${firstName.ifEmpty { "this person" }}."
        if (blocked && emojiOpen) emojiOpen = false
        renderEmoji()
        input.hint = "Message $firstName…"
        renderSendState()
    }
    private fun renderSendState() {
        val t = input.text.toString()
        val can = t.trim().isNotEmpty() && !sending
        sendBtn.alpha = if (can) 1f else 0.45f
        sendBtn.elevation = if (can) Ui.dp(6f) else 0f
        sendIcon.visibility = if (sending) GONE else VISIBLE; sendSpin.visibility = if (sending) VISIBLE else GONE
        counter.visibility = if (t.length > 400) VISIBLE else GONE; counter.text = "${t.length}/500"
    }
    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    // ═════════════ gift effect: after a gift, it floats over the chat (and their profile) like sparkles ═════════════
    private val fxEnd = Runnable { refreshFx() }
    private var fxId = ""
    private fun refreshFx() {
        main.removeCallbacks(fxEnd)
        var found: Triple<JSONObject, Gif, Long>? = null
        val now = System.currentTimeMillis()
        for (i in messages.indices.reversed()) {
            val m = messages[i]; val at = SdRules.ms(m.optString("created_date"))
            if (at != 0L && at < now - 10 * 60_000) break
            val g = gifOf(m.optString("gif_id")) ?: continue
            if (at == 0L) continue
            val until = at + fxMinutes(g) * 60_000L
            if (until > now) { found = Triple(m, g, until); break }
        }
        if (found == null || error.isNotEmpty() || !shownNow) { fx?.let { (it.parent as? ViewGroup)?.removeView(it) }; fx = null; fxId = ""; return }
        val (m, g, until) = found
        if (fxId != m.optString("id")) {
            fx?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val who = if (isMine(m)) "You" else firstName.ifEmpty { "They" }
            val v = GiftFxView(context, g, who)
            v.elevation = Ui.dp(60f) + 79f
            host.addView(v, LayoutParams(-1, -1))
            fx = v
            fxId = m.optString("id")
        }
        main.postDelayed(fxEnd, max(1000L, until - now + 50))
    }
    private fun fxMinutes(g: Gif) = if (g.g == "premium") 10 else if (g.g == "toys") 5 else 1

    // ═════════════ their profile, menu, sheets ═════════════
    private fun openProfile() { if (other != null) { gifOpen = false; renderTray(); profilePage() } }

    private fun profilePage() {
        val o = other ?: return
        val root = FrameLayout(context)
        root.addView(PinkBackdrop(context).apply { rose = sdLook }, LayoutParams(-1, -1))
        val sc = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        root.addView(sc, LayoutParams(-1, -1))
        val col = vbox(context)
        sc.addView(col, LayoutParams(-1, -2))
        var close: () -> Unit = {}
        val d = JSONObject(o.toString())
        // the rest of the profile (about, city, interests…) when it arrives
        val pid = SdRules.s(o, "profile_id")
        fun draw() {
            col.removeAllViews()
            val name = SdRules.s(d, "full_name", "name").ifEmpty { SdRules.s(o, "name") }
            val first = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            val age = SdRules.ageOf(d)
            val where = listOf(SdRules.s(d, "city"), SdRules.s(d, "state").takeIf { it != SdRules.s(d, "city") }.orEmpty()).filter { it.isNotEmpty() }.joinToString(", ")
            val about = SdRules.s(d, "bio", "about", "description")
            val verified = d.optBoolean("verified") || d.optBoolean("is_verified") || SdRules.s(d, "account_type").lowercase() == "creator"
            val online = isOnline
            // top bar
            val top = FrameLayout(context).apply { setPadding(dpi(6f), dpi(16f), dpi(8f), 0) }
            top.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ArrowLeft, onBg, 26f, 2.3f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back to chat" }.press().onTap { close() },
                LayoutParams(dpi(44f), dpi(44f), Gravity.START))
            val more = ImageView(context).apply { setImageDrawable(Icon(Lucide.MoreVertical, onBg, 24f, 2.6f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "More options" }
            top.addView(more.press().onTap { profileMenu(more) { close() } }, LayoutParams(dpi(44f), dpi(44f), Gravity.END))
            col.addView(top, lp(MATCH, WRAP))
            // round photo
            val ph = FrameLayout(context)
            val ring = FrameLayout(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pc("#F7569F"), pc("#C7105F"))).apply { shape = GradientDrawable.OVAL }; setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f)); elevation = Ui.dp(8f); outlineSpotShadowColorCompat(pa(153, 200, 16, 100)) }
            val white = FrameLayout(context).apply { background = oval(Color.WHITE); setPadding(dpi(5f), dpi(5f), dpi(5f), dpi(5f)) }
            val photo = RoundPhoto(context, 186f)
            photo.set(listOf(otherPhotoRaw), Demo.round(JSONObject().put("id", pid).put("gender", SdRules.s(o, "gender")), Ui.dpi(186f)))
            white.addView(photo, LayoutParams(-1, -1)); ring.addView(white, LayoutParams(-1, -1))
            ph.addView(ring, LayoutParams(dpi(204f), dpi(204f)))
            if (verified) ph.addView(ImageView(context).apply { setImageDrawable(Seal(46f, if (sdLook) Rs.SEAL else KP.rose)); scaleType = ImageView.ScaleType.CENTER; background = oval(Color.WHITE); elevation = Ui.dp(10f) }, LayoutParams(dpi(54f), dpi(54f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(2f); bottomMargin = dpi(8f) })
            ph.contentDescription = if (otherPhotoRaw.isNotEmpty()) "See ${first.ifEmpty { "their" }} photo" else "No photo"
            ph.onTap { Photos.loadFull(otherPhotoRaw, 1600) { b -> if (b != null) viewPhoto(b) } }
            col.addView(ph, lp(dpi(204f), dpi(204f), t = 4f, gravity = Gravity.CENTER_HORIZONTAL))
            // name · city
            val nameRow = hbox(context, 10f, Gravity.CENTER)
            val glow = if (sdLook) Color.TRANSPARENT else Color.argb(71, 160, 20, 80)
            nameRow.addView((if (sdLook) Rs.title(context, first.ifEmpty { "Member" } + (age?.let { ", $it" } ?: ""), 28f) else sdText(context, first.ifEmpty { "Member" } + (age?.let { ", $it" } ?: ""), 30f, Color.WHITE)).oneLine().shadow(2f, 1f, glow), lp(WRAP, WRAP))
            if (online) nameRow.addView(LiveDot(context, 12f))
            col.addView(nameRow, lp(MATCH, WRAP, l = 24f, r = 24f, t = 18f))
            col.addView(sdText(context, if (online) "Online now" else lastSeenOf(o), 15f, if (sdLook) Rs.SUB else Color.WHITE).shadow(2f, 1f, glow).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, t = 6f))
            if (where.isNotEmpty()) col.addView(sdText(context, where, 17f, onBg).oneLine().shadow(2f, 1f, glow).icon(Icon(Lucide.MapPin, if (sdLook) Rs.ICON else Color.WHITE, 19f, 2f), 6f), lp(WRAP, WRAP, t = 6f, gravity = Gravity.CENTER_HORIZONTAL))
            // story
            story?.let {
                val b = hbox(context, 9f).apply { background = rounded(Color.WHITE, 22f, 1f, pk.line); setPadding(dpi(4f), 0, dpi(16f), 0) }
                val sr = FrameLayout(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pc("#F7569F"), pc("#FF9BCB"))).apply { shape = GradientDrawable.OVAL }; setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
                val sw = FrameLayout(context).apply { background = oval(pk.tint, 2f, Color.WHITE); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
                val sp = RoundPhoto(context, 28f); sp.set(listOf(otherPhotoRaw), Demo.round(JSONObject().put("id", pid), Ui.dpi(28f))); if (!storyPremium) sp.dim = 0.35f
                sw.addView(sp, LayoutParams(-1, -1)); sr.addView(sw, LayoutParams(-1, -1))
                b.addView(sr, lp(dpi(36f), dpi(36f)))
                b.addView(if (storyPremium) sdText(context, "Watch story", 15.5f, pk.wine) else sdText(context, "Story · Premium", 15.5f, pk.wine).icon(Icon(Lucide.Lock, pk.wine, 15f), 6f))
                b.contentDescription = if (storyPremium) "Watch $first's story" else "Stories are for Premium members"
                col.addView(b.press().onTap { openStory() }, lp(WRAP, dpi(44f), t = 14f, gravity = Gravity.CENTER_HORIZONTAL))
            }
            // main button: back to the chat
            val back = sdText(context, "Back to Chat", 22f, Color.WHITE).icon(Icon(Lucide.MessageCircleMore, Color.WHITE, 27f, 2f), 10f).apply { gravity = Gravity.CENTER }
            val backBox = FrameLayout(context).apply { background = ckBtn(30f, Color.WHITE); elevation = Ui.dp(6f); outlineSpotShadowColorCompat(pa(191, 190, 0, 85)); addView(back, LayoutParams(-2, -1, Gravity.CENTER)) }
            col.addView(backBox.press().onTap { close() }, lp(MATCH, dpi(60f), l = 24f, r = 24f, t = 18f))
            col.addView(sdText(context, "Your private chat with ${first.ifEmpty { "them" }}. Numbers, links and IDs stay hidden.", 14f, pk.wine).apply { gravity = Gravity.CENTER; lineHeight(1.2f) }, lp(MATCH, WRAP, l = 24f, r = 24f, t = 8f))
            // about
            val sec = vbox(context).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
            if (about.isNotEmpty()) {
                sec.addView(sdText(context, "About Me", 21f, pc("#A1144A")))
                sec.addView(sdText(context, about, 17f, pc("#161216"), bold = false).lineHeight(1.2f).inlineIcon(Icon(Lucide.Heart, pk.rose, 16f, 2.2f), 4f, atEnd = true), lp(MATCH, WRAP, t = 8f))
            }
            val g = SdRules.sexOf(d)
            val job = SdRules.s(d, "profession", "job", "work", "occupation")
            val chips = listOfNotNull(age?.let { Lucide.Calendar to "$it Years" }, g.takeIf { it.isNotEmpty() }?.let { Lucide.User to if (it == "f") "Female" else "Male" },
                SdRules.s(d, "height").takeIf { it.isNotEmpty() }?.let { Lucide.Ruler to it }, job.takeIf { it.isNotEmpty() }?.let { Lucide.Briefcase to it },
                SdRules.s(d, "education").takeIf { it.isNotEmpty() }?.let { Lucide.Sparkles to it }).take(5)
            if (chips.isNotEmpty()) sec.addView(SdProfileView.Flow(context, 6f).apply {
                for ((ic, t) in chips) addView(sdText(context, t, 14.5f, pk.ink, bold = false).icon(Icon(ic, pc("#97294B"), 16f, 2f), 5f).apply { gravity = Gravity.CENTER_VERTICAL; background = rounded(Color.WHITE, 999f, 1f, pc("#EED6E2")); setPadding(dpi(9f), 0, dpi(11f), 0); minHeight = dpi(36f) })
            }, lp(MATCH, WRAP, t = 14f))
            val tagsArr = d.optJSONArray("interests") ?: d.optJSONArray("hobbies")
            val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr!!.opt(it).toString() }.take(8)
            if (tags.isNotEmpty()) sec.addView(SdProfileView.Flow(context, 6f).apply {
                for (t in tags) addView(sdText(context, t, 14f, pk.wine).apply { background = rounded(pk.tint2, 99f, 1f, pk.line); setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f)) })
            }, lp(MATCH, WRAP, t = 8f))
            val main1 = SdRules.s(d, "photo"); val main2 = SdRules.s(d, "profile_picture")
            val pics = photoListOf(d).filter { it != main1 && it != main2 }.take(4)
            if (pics.isNotEmpty()) {
                val row = hbox(context, 8f, Gravity.TOP)
                val side = (resources.displayMetrics.widthPixels - dpi(48f) - dpi(8f) * 3) / 4
                for (u in pics) {
                    val f = FrameLayout(context).apply { background = rounded(pk.tint, 14f, 1f, pk.line2); clipToOutline = true; outlineProvider = roundOutline(14f); contentDescription = "See photo" }
                    val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    f.addView(img, LayoutParams(-1, -1))
                    Photos.loadFull(u, 600) { b -> if (b != null) img.setImageBitmap(b) }
                    row.addView(f.press().onTap { Photos.loadFull(u, 1600) { b -> if (b != null) viewPhoto(b) } }, lp(side, side))
                }
                sec.addView(row, lp(MATCH, WRAP, t = 14f))
            }
            col.addView(sec, lp(MATCH, WRAP, t = 18f))
            if (verified) {
                val v = vbox(context).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
                v.addView(sdText(context, "Verification", 21f, pc("#A1144A")))
                val box = hbox(context, 12f).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pc("#FDE5F1"), pc("#FFF6FA"))).apply { cornerRadius = Ui.dp(18f); setStroke(dpi(1f), pc("#F5C7DC")) }; setPadding(dpi(14f), dpi(13f), dpi(14f), dpi(13f)) }
                box.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ShieldCheck, pk.rose, 38f, 1.8f)) }, lp(dpi(38f), dpi(38f)))
                val tx = vbox(context); tx.addView(sdText(context, "Verified Member", 17f, pc("#A91C56"))); tx.addView(sdText(context, "Identity & profile verified for a safer experience.", 14f, pk.ink2, bold = false).lineHeight(1.1f), lp(WRAP, WRAP, t = 2f))
                box.addView(tx, lp(0, WRAP, 1f))
                v.addView(box, lp(MATCH, WRAP, t = 10f))
                col.addView(v, lp(MATCH, WRAP, t = 22f))
            }
            // rules + block / report
            val rules = hbox(context, 10f, Gravity.TOP).apply { background = rounded(Color.WHITE, 16f, 1f, pk.line2); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)) }
            rules.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Lock, pk.rose, 17f)) }, lp(dpi(17f), dpi(17f), t = 1f))
            rules.addView(sdText(context, "Chat only. Phone numbers, email, links and social IDs (Instagram, Telegram, WhatsApp…) are not allowed — such messages are not sent.", 14f, pk.sub, bold = false).lineHeight(1.2f), lp(0, WRAP, 1f))
            col.addView(rules, lp(MATCH, WRAP, l = 24f, r = 24f, t = 16f))
            val two = hbox(context, 10f, Gravity.CENTER)
            two.addView(sdText(context, if (blockRow != null) "Unblock" else "Block", 15.5f, CK.red).icon(Icon(Lucide.Ban, CK.red, 16f), 6f).apply { gravity = Gravity.CENTER; background = rounded(Color.WHITE, 22f, 1f, pc("#f3a7b5")); setPadding(dpi(18f), 0, dpi(18f), 0) }
                .press().onTap { close(); if (blockRow != null) unblock() else confirm("block") }, lp(WRAP, dpi(44f)))
            two.addView(sdText(context, "Report", 15.5f, pk.ink2).icon(Icon(Lucide.Flag, pk.ink2, 16f), 6f).apply { gravity = Gravity.CENTER; background = rounded(Color.WHITE, 22f, 1f, pk.line); setPadding(dpi(18f), 0, dpi(18f), 0) }
                .press().onTap { close(); report() }, lp(WRAP, dpi(44f)))
            col.addView(two, lp(MATCH, WRAP, t = 14f, b = 30f))
        }
        draw()
        close = layer.page(root, z = 78f)
        if (pid.isNotEmpty()) Api.filter("UserProfile", JSONObject().put("id", pid), null, 1) { r ->
            val row = r.data.optJSONArray("items")?.optJSONObject(0)
            fun use(p: JSONObject) { p.keys().forEach { k -> d.put(k, p.get(k)) }; draw() }
            if (row != null) use(row) else Api.get("UserProfile", pid) { g -> if (g.ok && g.data.length() > 0) use(g.data) }
        }
    }
    private fun profileMenu(anchor: View, closePage: () -> Unit) {
        val panel = vbox(context).apply { background = rounded(Color.WHITE, 16f, 1f, pk.line2); setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f)); elevation = Ui.dp(10f) }
        val pop = android.widget.PopupWindow(panel, dpi(176f), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pop.elevation = Ui.dp(10f)
        panel.addView(sdText(context, if (blockRow != null) "Unblock" else "Block", 15.5f, CK.red).icon(Icon(Lucide.Ban, CK.red, 17f), 10f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10f), 0, dpi(10f), 0) }
            .onTap { pop.dismiss(); closePage(); if (blockRow != null) unblock() else confirm("block") }, lp(MATCH, dpi(44f)))
        panel.addView(sdText(context, "Report", 15.5f, pk.ink2).icon(Icon(Lucide.Flag, pk.ink2, 17f), 10f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10f), 0, dpi(10f), 0) }
            .onTap { pop.dismiss(); closePage(); report() }, lp(MATCH, dpi(44f)))
        pop.showAsDropDown(anchor, -dpi(176f) + anchor.width - dpi(4f), dpi(2f))
    }

    private fun sheetPanel(): LinearLayout = vbox(context).apply {
        background = pk.sheet(); setPadding(dpi(18f), dpi(10f), dpi(18f), dpi(18f))
        addView(View(context).apply { background = rounded(pc("#F2C3D8"), 3f) }, lp(dpi(44f), dpi(5f), b = 14f, gravity = Gravity.CENTER_HORIZONTAL))
    }
    private fun sheetRow(icon: String, label: String, sub: String?, danger: Boolean, onClick: () -> Unit): View {
        val r = hbox(context, 12f).apply { background = rounded(Color.WHITE, 16f, 1f, if (danger) pc("#F8C9D3") else pk.line2); setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f)); minimumHeight = dpi(58f) }
        r.addView(ImageView(context).apply { setImageDrawable(Icon(icon, if (danger) CK.red else pk.rose, 20f)); scaleType = ImageView.ScaleType.CENTER; background = rounded(if (danger) pc("#FFE4E8") else pk.tint, 12f) }, lp(dpi(40f), dpi(40f)))
        val t = vbox(context)
        t.addView(sdText(context, label, 16f, if (danger) CK.red else pk.ink2))
        if (sub != null) t.addView(sdText(context, sub, 13f, pk.sub, bold = false), lp(WRAP, WRAP, t = 1f))
        r.addView(t, lp(0, WRAP, 1f))
        return r.press().onTap { onClick() }
    }
    private fun menu() {
        val p = sheetPanel()
        val head = hbox(context, 12f).apply { setPadding(dpi(4f), 0, dpi(4f), dpi(8f)) }
        val ring = FrameLayout(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pc("#F7569F"), pc("#C7105F"))).apply { shape = GradientDrawable.OVAL }; setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
        val w = FrameLayout(context).apply { background = oval(pk.tint, 2f, Color.WHITE); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
        val ph = RoundPhoto(context, 42f); ph.set(listOf(otherPhotoRaw), Demo.round(JSONObject().put("id", SdRules.s(other, "profile_id")), Ui.dpi(42f)))
        w.addView(ph, LayoutParams(-1, -1)); ring.addView(w, LayoutParams(-1, -1))
        head.addView(ring, lp(dpi(50f), dpi(50f)))
        head.addView(sdText(context, SdRules.s(other, "name"), 22f, pk.deep).oneLine(), lp(0, WRAP, 1f))
        p.addView(head, lp(MATCH, WRAP))
        var close: () -> Unit = {}
        p.addView(sheetRow(Lucide.User, "View profile", null, false) { close(); profilePage() }, lp(MATCH, WRAP, t = 8f))
        p.addView(sheetRow(if (pinned) Lucide.PinOff else Lucide.Pin, if (pinned) "Unpin chat" else "Pin chat", if (pinned) "It goes back to its normal place" else "Keep it at the top of your chats", false) { close(); togglePin() }, lp(MATCH, WRAP, t = 8f))
        if (story != null) p.addView(sheetRow(Lucide.Eye, "Watch story", if (storyPremium) "Posted in the last 24 hours" else "Premium", false) { close(); openStory() }, lp(MATCH, WRAP, t = 8f))
        if (blockRow != null) p.addView(sheetRow(Lucide.Ban, "Unblock", "Let them message you again", false) { close(); unblock() }, lp(MATCH, WRAP, t = 8f))
        else p.addView(sheetRow(Lucide.Ban, "Block", "They won't be able to message you", true) { close(); confirm("block") }, lp(MATCH, WRAP, t = 8f))
        p.addView(sheetRow(Lucide.Trash2, "Delete chat", "Removes this conversation for you", true) { close(); confirm("delete") }, lp(MATCH, WRAP, t = 8f))
        p.addView(sheetRow(Lucide.Flag, "Report", "Spam, harassment, inappropriate photos…", true) { close(); report() }, lp(MATCH, WRAP, t = 8f))
        p.contentDescription = "Chat options"
        close = layer.sheet(p, z = 80f, maxVh = 0.88f)
    }
    private fun confirm(kind: String) {
        val box = FrameLayout(context).apply { setBackgroundColor(pa(115, 60, 0, 25)); setPadding(dpi(24f), dpi(24f), dpi(24f), dpi(24f)) }
        val card = vbox(context, 0f, Gravity.CENTER_HORIZONTAL).apply { background = rounded(Color.WHITE, 24f, 1f, pk.line2); setPadding(dpi(18f), dpi(22f), dpi(18f), dpi(16f)); elevation = Ui.dp(16f); isClickable = true }
        card.addView(sdText(context, if (kind == "delete") "Delete this chat?" else "Block ${firstName}?", 24f, pk.deep).apply { gravity = Gravity.CENTER })
        card.addView(sdText(context, if (kind == "delete") "All messages in this chat will be removed for you. This can't be undone." else "They won't be able to message you. You can unblock anytime from this chat or Settings.", 15.5f, pk.sub, bold = false).apply { gravity = Gravity.CENTER; lineHeight(1.2f) }, lp(MATCH, WRAP, t = 8f, b = 18f))
        var close: () -> Unit = {}
        val two = hbox(context, 10f)
        two.addView(sdText(context, "Cancel", 16f, pk.ink2).apply { gravity = Gravity.CENTER; background = rounded(Color.WHITE, 24f, 1f, pk.line) }.press().onTap { close() }, lp(0, dpi(48f), 1f))
        two.addView(sdText(context, if (kind == "delete") "Delete" else "Block", 16f, Color.WHITE).apply { gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pc("#F0506C"), pc("#C0153D"))).apply { cornerRadius = Ui.dp(24f) } }
            .press().onTap { close(); if (kind == "delete") deleteChat() else block() }, lp(0, dpi(48f), 1f))
        card.addView(two, lp(MATCH, WRAP))
        box.addView(card, LayoutParams(dpi(340f).coerceAtMost(resources.displayMetrics.widthPixels - dpi(48f)), -2, Gravity.CENTER))
        box.setOnClickListener { close() }
        close = layer.fade(box, z = 85f)
    }

    /** "Pin chat" / "Unpin chat": only my own list changes (pinChat); the chat list puts it on top at once */
    private fun togglePin() {
        if (pinBusy || matchId.isEmpty()) return
        val id = matchId
        val next = !pinned
        if (Demo.on) { pinned = next; ChatCache.chat(id)?.put("pinned", next); onPinned?.invoke(id, next); say(if (next) "Chat pinned to the top." else "Chat unpinned."); return }
        pinBusy = true
        Api.invoke("pinChat", JSONObject().put("match_id", id).put("pin", next)) { r ->
            pinBusy = false
            if (id != matchId) return@invoke
            if (!r.ok) { say(r.data.optString("error").ifEmpty { "Couldn't ${if (next) "pin" else "unpin"} the chat. Try again." }, "err"); return@invoke }
            pinned = next
            ChatCache.chat(id)?.put("pinned", next)
            onPinned?.invoke(id, next)
            say(if (next) "Chat pinned to the top." else "Chat unpinned.")
        }
    }
    /** the chat list moves the chat at once (SecretHome) */
    var onPinned: ((String, Boolean) -> Unit)? = null

    private fun block() {
        val o = other ?: return
        Api.invoke("moderateUser", JSONObject().put("profile_id", SdRules.s(o, "profile_id")).put("action", "block")) { r ->
            if (!r.ok) { say(r.data.optString("error").ifEmpty { "Couldn't block. Try again." }, "err"); return@invoke }
            blockRow = JSONObject()
            say("${firstName.ifEmpty { "They" }} can't message you now.")
            renderComposer(); renderTray(); renderList()
            Api.filter("Block", JSONObject().put("blocker_id", meId), null, 200) { b -> if (b.ok) findBlock(b.data.optJSONArray("items"))?.let { blockRow = it } }
        }
    }
    private fun unblock() {
        val o = other ?: return
        fun done(ok: Boolean, err: String?) {
            if (!ok) { say(err ?: "Couldn't unblock. Try again.", "err"); return }
            blockRow = null; say("Unblocked. You can chat again."); renderComposer(); renderList()
        }
        fun byRow(row: JSONObject?) {
            val id = row?.optString("id").orEmpty()
            if (id.isNotEmpty()) Api.delete("Block", id) { r -> done(r.ok, r.data.optString("error").ifEmpty { null }) }
            else Api.invoke("moderateUser", JSONObject().put("profile_id", SdRules.s(o, "profile_id")).put("action", "unblock")) { r -> done(r.ok, r.data.optString("error").ifEmpty { null }) }
        }
        val row = blockRow
        if (row?.optString("id").isNullOrEmpty()) Api.filter("Block", JSONObject().put("blocker_id", meId), null, 200) { b -> byRow(if (b.ok) findBlock(b.data.optJSONArray("items")) else null) }
        else byRow(row)
    }
    private fun deleteChat() {
        Api.invoke("deleteChat", JSONObject().put("match_id", matchId)) { r ->
            if (!r.ok) { say(if (r.status == 404) "Deleting chats needs a one-time server update." else r.data.optString("error").ifEmpty { "Couldn't delete. Try again." }, "err"); return@invoke }
            held.clear(); writeHeld()
            say("Chat deleted.")
            main.postDelayed({ goBack() }, 500)
        }
    }

    private class Shot(val bytes: ByteArray, val bmp: Bitmap?)
    private fun report() {
        val p = sheetPanel()
        var reason = ""; var details = ""; val shots = ArrayList<Shot>(); var busy = false
        var close: () -> Unit = {}
        p.addView(sdText(context, "Report ${firstName}", 25f, pk.deep), lp(MATCH, WRAP, l = 4f, r = 4f, b = 4f))
        p.addView(sdText(context, "Your report is private. Our team checks it and can remove them from the app. They'll also be blocked for you.", 15f, pk.sub, bold = false).lineHeight(1.2f), lp(MATCH, WRAP, l = 4f, r = 4f, b = 12f))
        val reasons = vbox(context, 6f)
        p.addView(reasons, lp(MATCH, WRAP))
        val detailsIn = EditText(context)
        val hint = sdText(context, "", 13f, pk.soft, bold = false)
        val shotsRow = hbox(context, 8f)
        val sendB = hbox(context, 8f, Gravity.CENTER)
        fun ready() = reason.isNotEmpty() && details.trim().length >= 10
        lateinit var paint: () -> Unit
        paint = {
            reasons.removeAllViews()
            for ((k, label, sub) in REPORT_REASONS) {
                val on = reason == k
                val r = hbox(context, 12f).apply { background = rounded(if (on) pk.tint2 else Color.WHITE, 16f, if (on) 1.5f else 1f, if (on) pk.rose else pk.line2); setPadding(dpi(12f), dpi(11f), dpi(12f), dpi(11f)) }
                val dot = FrameLayout(context).apply { background = oval(Color.TRANSPARENT, 2f, if (on) pk.rose else pc("#D9B3C5")) }
                if (on) dot.addView(View(context).apply { background = oval(pk.rose) }, LayoutParams(dpi(11f), dpi(11f), Gravity.CENTER))
                r.addView(dot, lp(dpi(22f), dpi(22f)))
                val t = vbox(context); t.addView(sdText(context, label, 16f, pk.ink)); if (sub.isNotEmpty()) t.addView(sdText(context, sub, 13.5f, pk.sub, bold = false))
                r.addView(t, lp(0, WRAP, 1f))
                r.contentDescription = label + if (on) ", selected" else ""
                reasons.addView(r.press().onTap { reason = k; paint() }, lp(MATCH, WRAP))
            }
            val ok = details.trim().length >= 10
            detailsIn.background = rounded(Color.WHITE, 16f, 1.5f, if (ok) pk.line else pc("#F8B4C2"))
            hint.text = if (ok) "Thank you — this helps our team." else "Please write at least 10 letters."
            hint.setTextColor(if (ok) pk.soft else CK.red)
            shotsRow.removeAllViews()
            for ((i, sh) in shots.withIndex()) {
                val f = FrameLayout(context).apply { background = rounded(pk.tint, 14f, 1f, pk.line); clipToOutline = true; outlineProvider = roundOutline(14f) }
                f.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(sh.bmp) }, LayoutParams(-1, -1))
                f.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.X, Color.WHITE, 14f)); scaleType = ImageView.ScaleType.CENTER; background = oval(pa(153, 0, 0, 0)); contentDescription = "Remove screenshot ${i + 1}" }
                    .onTap { shots.removeAt(i); paint() }, LayoutParams(dpi(24f), dpi(24f), Gravity.END or Gravity.TOP).apply { setMargins(0, dpi(3f), dpi(3f), 0) })
                shotsRow.addView(f, lp(dpi(74f), dpi(98f)))
            }
            if (shots.size < 3) {
                val add = vbox(context, 4f, Gravity.CENTER).apply { background = DashedRect(); contentDescription = "Add screenshot" }
                add.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Camera, pk.rose, 22f)) }, lp(dpi(22f), dpi(22f)))
                add.addView(sdText(context, "Add", 13f, pk.rose), lp(WRAP, WRAP))
                shotsRow.addView(add.press().onTap {
                    val pick = SdSheets.pickImage ?: return@onTap
                    pick { b, type -> if (b != null && type.startsWith("image/") && shots.size < 3) { shots.add(Shot(b, decodeSmall(b))); paint() } }
                }, lp(dpi(74f), dpi(98f)))
            }
            sendB.removeAllViews()
            sendB.background = if (ready()) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pc("#F0506C"), pc("#C0153D"))).apply { cornerRadius = Ui.dp(27f) } else rounded(pc("#F3E3EA"), 27f)
            if (busy) sendB.addView(ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, lp(dpi(19f), dpi(19f)))
            else sendB.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.Flag, if (ready()) Color.WHITE else pc("#B79AA8"), 18f)) }, lp(dpi(18f), dpi(18f)))
            sendB.addView(sdText(context, "Report & block", 17f, if (ready()) Color.WHITE else pc("#B79AA8")))
        }
        p.addView(sdText(context, "What did they do? ", 16f, pk.wine).apply { append(android.text.SpannableString("*").apply { setSpan(android.text.style.ForegroundColorSpan(CK.red), 0, 1, 0) }) }, lp(MATCH, WRAP, l = 4f, r = 4f, t = 14f, b = 6f))
        detailsIn.hint = "Describe their behaviour — what they said or sent"; detailsIn.setHintTextColor(pc("#A8909C")); detailsIn.setTextColor(pk.ink); detailsIn.typeface = Ui.regular
        detailsIn.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(16f)); detailsIn.minLines = 3; detailsIn.gravity = Gravity.TOP; detailsIn.filters = arrayOf(InputFilter.LengthFilter(1000))
        detailsIn.setPadding(dpi(13f), dpi(11f), dpi(13f), dpi(11f)); detailsIn.contentDescription = "Details"
        detailsIn.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { val was = ready(); details = s.toString(); if (was != ready() || (details.trim().length >= 10) != (details.trim().length - 1 >= 10)) paint() }
        })
        p.addView(detailsIn, lp(MATCH, WRAP))
        p.addView(hint, lp(MATCH, WRAP, l = 4f, r = 4f, t = 3f))
        p.addView(sdText(context, "Screenshots ", 16f, pk.wine).apply { append(android.text.SpannableString("(up to 3, optional)").apply { setSpan(android.text.style.ForegroundColorSpan(pk.soft), 0, length, 0); setSpan(Compat.typefaceSpan(Ui.regular), 0, length, 0) }) }, lp(MATCH, WRAP, l = 4f, r = 4f, t = 14f, b = 6f))
        p.addView(shotsRow, lp(MATCH, WRAP))
        p.addView(sendB.press().onTap {
            if (!ready() || busy) return@onTap
            busy = true; paint()
            val o = other ?: return@onTap
            val evidence = JSONArray()
            fun finish() {
                Api.invoke("moderateUser", JSONObject().put("profile_id", SdRules.s(o, "profile_id")).put("action", "report").put("reason", reason).put("details", details.trim().take(1000))
                    .put("evidence_uris", evidence).put("match_id", matchId).put("app", chatApp)) { r ->
                    if (!r.ok) { busy = false; paint(); say(r.data.optString("error").ifEmpty { "Couldn't send the report. Try again." }, "err"); return@invoke }
                    Api.invoke("moderateUser", JSONObject().put("profile_id", SdRules.s(o, "profile_id")).put("action", "block")) { }
                    close()
                    blockRow = JSONObject(); renderComposer(); renderTray()
                    say("Report sent. Our team will review it — they're blocked for you.")
                }
            }
            fun upload(i: Int) {
                if (i >= shots.size) { finish(); return }
                Api.async({ shrink(shots[i].bytes, 1600, 85) }) { bytes ->
                    Api.uploadPrivate(bytes, "photo.jpg", "image/jpeg") { u ->
                        val uri = u.data.optString("file_uri").ifEmpty { u.data.optJSONObject("data")?.optString("file_uri").orEmpty() }.ifEmpty { u.data.optString("uri") }
                        if (!u.ok || uri.isEmpty()) { busy = false; paint(); say("Couldn't send the report. Try again.", "err"); return@uploadPrivate }
                        evidence.put(uri); upload(i + 1)
                    }
                }
            }
            upload(0)
        }, lp(MATCH, dpi(54f), t = 16f))
        paint()
        p.contentDescription = "Report"
        close = layer.sheet(p, z = 82f, locked = { busy }, maxVh = 0.88f)
    }
    private fun decodeSmall(b: ByteArray): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (max(o.outWidth, o.outHeight) / (s * 2) >= 300) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }
    private fun shrink(b: ByteArray, maxSide: Int, q: Int): ByteArray = try {
        val bm = BitmapFactory.decodeByteArray(b, 0, b.size)
        val sc = min(1f, maxSide.toFloat() / max(bm.width, bm.height))
        val out = if (sc < 1f) Bitmap.createScaledBitmap(bm, (bm.width * sc).toInt(), (bm.height * sc).toInt(), true) else bm
        java.io.ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
    } catch (e: Exception) { b }

    private fun giftSheet(g: Gif) {
        val p = sheetPanel()
        var close: () -> Unit = {}
        val c = vbox(context, 10f, Gravity.CENTER_HORIZONTAL).apply { setPadding(0, dpi(4f), 0, dpi(6f)) }
        c.addView(gifView(g, big = true, showPrice = false, onPink = false), lp(WRAP, WRAP))
        c.addView(sdText(context, android.text.SpannableStringBuilder("Send ").append(android.text.SpannableString(g.label).apply { setSpan(android.text.style.ForegroundColorSpan(pk.deep), 0, length, 0); setSpan(Compat.typefaceSpan(Ui.bold), 0, length, 0) }).append(" to ${firstName.ifEmpty { "them" }}"), 16.5f, pk.sub, bold = false).apply { gravity = Gravity.CENTER })
        c.addView(sdText(context, android.text.SpannableStringBuilder("Your balance: ").append(android.text.SpannableString("${balance ?: "–"}").apply { setSpan(android.text.style.ForegroundColorSpan(pk.deep), 0, length, 0); setSpan(Compat.typefaceSpan(Ui.bold), 0, length, 0) }), 15f, pk.sub, bold = false).icon(Icon(Lucide.CoinsIcon, pk.hot, 16f), 6f), lp(WRAP, WRAP))
        p.addView(c, lp(MATCH, WRAP))
        val bal = balance
        if (bal != null && bal < g.price) {
            p.addView(sdText(context, "Not enough coins · Top up", 18f, Color.WHITE).apply { gravity = Gravity.CENTER; background = ckBtn(28f) }.press().onTap { close(); go("/wallet?app=$chatApp", null) }, lp(MATCH, dpi(56f), t = 10f))
        } else {
            val label = sdText(context, android.text.SpannableStringBuilder("Send for ").append(coinSpan(18f, Color.WHITE)).append("${g.price} coin${if (g.price == 1) "" else "s"}"), 18f, Color.WHITE)
            val b = FrameLayout(context).apply {
                background = if (g.vip) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pc("#F2B84B"), pc("#FF6FAF"), pc("#E3086C"))).apply { cornerRadius = Ui.dp(28f) } else ckBtn(28f)
                elevation = Ui.dp(6f); outlineSpotShadowColorCompat(pa(217, 200, 9, 98))
                addView(label, LayoutParams(-2, -2, Gravity.CENTER))
            }
            p.addView(b.press().onTap {
                val bl = balance
                if (bl != null && bl < g.price) return@onTap
                close(); gifOpen = false; renderTray()
                sendGifNow(g)
            }, lp(MATCH, dpi(56f), t = 10f))
        }
        p.addView(sdText(context, "Cancel", 16f, pk.sub).apply { gravity = Gravity.CENTER }.onTap { close() }, lp(MATCH, dpi(46f), t = 8f))
        p.contentDescription = "Send ${g.label}"
        close = layer.sheet(p, z = 81f, maxVh = 0.88f)
    }

    private fun viewPhoto(b: Bitmap) {
        val f = FrameLayout(context).apply { setBackgroundColor(pa(242, 0, 0, 0)) }
        f.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(b); contentDescription = "Photo" }, LayoutParams(-1, -1))
        var close: () -> Unit = {}
        f.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.X, Color.WHITE, 22f)); scaleType = ImageView.ScaleType.CENTER; background = oval(pa(36, 255, 255, 255)); contentDescription = "Close photo" }
            .onTap { close() }, LayoutParams(dpi(44f), dpi(44f), Gravity.END or Gravity.TOP).apply { setMargins(0, dpi(12f), dpi(14f), 0) })
        f.setOnClickListener { close() }
        close = layer.fade(f, z = 90f)
    }

    private fun openStory() {
        val s = story ?: return
        if (!storyPremium) { say("Watching stories is for Premium members.", "premium"); return }
        // one story, 6 s (counted on the clock), tap to close
        val f = FrameLayout(context).apply { setBackgroundColor(Color.BLACK); contentDescription = "${SdRules.s(s, "name").ifEmpty { SdRules.s(other, "name") }}'s story" }
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val sp = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        f.addView(img, LayoutParams(-1, -1)); f.addView(sp, LayoutParams(dpi(30f), dpi(30f), Gravity.CENTER))
        Photos.loadFull(SdRules.s(s, "media_url", "media_uri"), 1600) { b -> if (b != null) { img.setImageBitmap(b); sp.visibility = GONE } }
        val top = vbox(context).apply { setPadding(dpi(12f), dpi(10f), dpi(12f), 0) }
        val track = FrameLayout(context).apply { background = rounded(pa(64, 255, 255, 255), 2f) }
        val fill = View(context).apply { background = rounded(Color.WHITE, 2f); pivotX = 0f; scaleX = 0f }
        track.addView(fill, LayoutParams(-1, -1))
        top.addView(track, lp(MATCH, dpi(3f)))
        val row = FrameLayout(context)
        row.addView(sdText(context, SdRules.s(s, "name").ifEmpty { SdRules.s(other, "name") }, 18f, Color.WHITE), LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        var close: () -> Unit = {}
        row.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.X, Color.WHITE, 22f)); scaleType = ImageView.ScaleType.CENTER; background = oval(pa(102, 0, 0, 0)); contentDescription = "Close story" }.onTap { close() }, LayoutParams(dpi(40f), dpi(40f), Gravity.END))
        top.addView(row, lp(MATCH, WRAP, t = 10f))
        f.addView(top, LayoutParams(-1, -2, Gravity.TOP))
        if (SdRules.s(s, "caption").isNotEmpty()) f.addView(sdText(context, SdRules.s(s, "caption"), 16f, Color.WHITE, bold = false).shadow(6f, 1f, pa(230, 0, 0, 0)).apply { gravity = Gravity.CENTER },
            LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dpi(16f), 0, dpi(16f), dpi(28f)) })
        f.setOnClickListener { close() }
        Api.invoke("viewStory", JSONObject().put("story_id", s.optString("id"))) { }
        val t0 = System.currentTimeMillis()
        val tick = object : Runnable { override fun run() { val k = (System.currentTimeMillis() - t0) / 6000f; fill.scaleX = min(1f, k); if (k >= 1f) close() else f.postOnAnimation(this) } }
        close = layer.fade(f, z = 95f, onClose = { f.removeCallbacks(tick); story = story?.put("seen", true); renderHeader() })
        f.postOnAnimation(tick)
    }

    // ═════════════ toast ═════════════
    private var toastView: View? = null
    private val hideToast = Runnable { toastView?.let { v -> v.animate().alpha(0f).setDuration(200).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start() }; toastView = null }
    private fun say(msg: String, kind: String = "ok") {
        toastView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val t = hbox(context, 10f).apply { background = rounded(Color.WHITE, 18f, 1f, if (kind == "err") pc("#F8B4C2") else pk.line); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)); elevation = Ui.dp(12f) }
        val ic = when (kind) { "premium", "verify" -> Icon(Lucide.Lock, pk.rose, 18f); "err" -> Icon(Lucide.AlertTriangle, CK.red, 18f); else -> Icon(Lucide.ShieldCheck, pc("#17803A"), 18f) }
        t.addView(ImageView(context).apply { setImageDrawable(ic) }, lp(dpi(18f), dpi(18f)))
        t.addView(sdText(context, msg, 15f, pk.ink, bold = false).lineHeight(1.1f), lp(0, WRAP, 1f))
        if (kind == "premium") t.addView(sdText(context, "Unlock", 14f, Color.WHITE).apply { gravity = Gravity.CENTER; background = rounded(if (sdLook) Rs.M else CK.PILL_END, 16f); setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f)) }.onTap { main.removeCallbacks(hideToast); hideToast.run(); go("/wallet?app=secret", null) })
        if (kind == "verify") t.addView(sdText(context, "Verify Now", 14f, Color.WHITE).apply { gravity = Gravity.CENTER; background = rounded(if (sdLook) Rs.M else CK.PILL_END, 16f); setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f)) }.onTap { main.removeCallbacks(hideToast); hideToast.run(); go("/profile?open=creator", null) })
        val wrap = FrameLayout(context).apply { elevation = Ui.dp(60f) + 96f }
        wrap.addView(t, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        host.addView(wrap, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dpi(16f), 0, dpi(16f), dpi(92f)) })
        wrap.alpha = 0f; wrap.animate().alpha(1f).setDuration(200).start()
        toastView = wrap
        main.removeCallbacks(hideToast); main.postDelayed(hideToast, 3600)
    }

    private fun roundOutline(r: Float) = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, Ui.dp(r)) } }

    // ═════════════ helpers (same as the web) ═════════════
    companion object {
        /** the row of quick emojis (Secret Dating's chat) */
        val EMOJIS = listOf("😊", "😂", "❤️", "😍", "😘", "🥰", "😉", "👍", "🙏", "😎", "🤗", "🔥", "🌹", "💕", "🎉", "👋", "😇", "🤔", "😋", "😢")
        fun kindOf(m: JSONObject): String {
            val k = (listOf("kind", "type", "message_type").map { m.optString(it) }.firstOrNull { it.isNotEmpty() } ?: "").lowercase()
            if (Regex("voice|audio").containsMatchIn(k)) return "voice"
            if (Regex("photo|image").containsMatchIn(k)) return "photo"
            if (Regex("gif|sticker").containsMatchIn(k)) return "gif"
            if (k.isEmpty() && m.optString("media_uri").isNotEmpty() && m.optString("text").isEmpty()) return "photo"
            return "text"
        }
        fun timeOf(ms: Long): String = if (ms == 0L) "" else java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(ms)).uppercase()
        fun dayOf(ms: Long): String {
            if (ms == 0L) return ""
            val t = Calendar.getInstance().apply { timeInMillis = ms }; val now = Calendar.getInstance()
            fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (same(t, now)) return "Today"
            if (same(t, (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Yesterday"
            return java.text.SimpleDateFormat(if (t.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy", Locale.US).format(t.time)
        }
        /** the web's photoListOf: images + photos (not the ones still waiting for review) */
        fun photoListOf(d: JSONObject?): List<String> {
            val out = ArrayList<String>()
            for (key in listOf("images", "photos")) {
                val a = d?.optJSONArray(key) ?: continue
                for (i in 0 until a.length()) {
                    val x = a.opt(i)
                    val u = when (x) {
                        is String -> x
                        is JSONObject -> if (Regex("pending|review|submitted", RegexOption.IGNORE_CASE).containsMatchIn(x.optString("status"))) "" else listOf("url", "file_url", "uri", "file_uri", "path").map { x.optString(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
                        else -> ""
                    }
                    if (u.isNotEmpty()) out.add(u)
                }
            }
            return out
        }
        fun mmss(s: Double): String { val n = max(0, Math.round(s).toInt()); return "${n / 60}:${(n % 60).toString().padStart(2, '0')}" }
        /** online dot: the server's presence fields when it sends them; if none, "Online now" (as before) */
        fun onlineOf(o: JSONObject?): Boolean {
            if (o == null) return false
            if (o.opt("online") == true || o.opt("is_online") == true) return true
            if (o.opt("online") == false || o.opt("is_online") == false) return false
            val t = SdRules.ms(SdRules.s(o, "last_active", "last_seen", "last_active_at"))
            if (t > 0) return System.currentTimeMillis() - t < 5 * 60 * 1000
            return true
        }
        /** "Last seen 5m ago" · "Last seen 3h ago" · "Last seen 2d ago" · "Last seen 7 Nov" */
        fun lastSeenOf(o: JSONObject?): String {
            val t = SdRules.ms(SdRules.s(o, "last_active", "last_seen", "last_active_at"))
            if (t == 0L) return "Offline"
            val m = floor((System.currentTimeMillis() - t) / 60000.0).toLong()
            if (m < 1) return "Last seen just now"
            if (m < 60) return "Last seen ${m}m ago"
            val h = m / 60
            if (h < 24) return "Last seen ${h}h ago"
            val d = h / 24
            return if (d < 7) "Last seen ${d}d ago" else "Last seen ${java.text.SimpleDateFormat("d MMM", Locale.US).format(java.util.Date(t))}"
        }
        val REPORT_REASONS = listOf(
            Triple("spam", "Spam or scam", "Ads, money requests, fake offers"),
            Triple("harassment", "Harassment or abuse", "Rude, threatening or abusive messages"),
            Triple("inappropriate_photos", "Inappropriate photos", "Nude, sexual or offensive pictures"),
            Triple("fake_profile", "Fake profile", "Pretending to be someone else"),
            Triple("contact_sharing", "Sharing numbers or links", "Trying to move the chat outside the app"),
            Triple("underage", "Looks under 18", ""),
            Triple("other", "Something else", ""),
        )
    }
}

/** the chat room's own colours (src/pages/ChatRoom.jsx K) */
object CK {
    val red = Ui.c("#C0153D")
    val PILL_END = Ui.c("#D80F6E")
    /** K.btn: linear-gradient(180deg,#FF4CA6 0%,#EC157C 55%,#CF0760 100%) */
    fun btn(radiusDp: Float, ring: Int? = null) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#FF4CA6"), Ui.c("#EC157C"), Ui.c("#CF0760"))).apply { cornerRadius = Ui.dp(radiusDp); if (ring != null) setStroke(Ui.dpi(2f), ring) }
    fun btnOval() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#FF4CA6"), Ui.c("#EC157C"), Ui.c("#CF0760"))).apply { shape = GradientDrawable.OVAL }
    /** K.pill: linear-gradient(180deg,#F23D93 0%,#D80F6E 100%) */
    fun pill(radiusDp: Float) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#F23D93"), Ui.c("#D80F6E"))).apply { cornerRadius = Ui.dp(radiusDp) }
}

/** a CSS linear-gradient at any angle, with corner radii and an optional outer hairline (box-shadow 0 0 0 Npx) */
class CssGradient(private val angle: Float, private val colors: IntArray, private val stops: FloatArray?, private val radii: FloatArray, private val ringPx: Float = 0f, private val ringColor: Int = 0) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val outer = Path()
    override fun onBoundsChange(b: android.graphics.Rect) {
        val rect = RectF(b); val inner = RectF(b).apply { inset(ringPx, ringPx) }
        val a = Math.toRadians(angle.toDouble()); val dx = sin(a).toFloat(); val dy = -kotlin.math.cos(a).toFloat()
        val half = (abs(inner.width() * dx) + abs(inner.height() * dy)) / 2
        val cx = inner.centerX(); val cy = inner.centerY()
        p.shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, stops, Shader.TileMode.CLAMP)
        path.reset(); path.addRoundRect(inner, radii.map { max(0f, it - ringPx) }.toFloatArray(), Path.Direction.CW)
        outer.reset(); outer.addRoundRect(rect, radii, Path.Direction.CW)
        r.color = ringColor
    }
    override fun draw(c: Canvas) { if (ringPx > 0) c.drawPath(outer, r); c.drawPath(path, p) }
    override fun getOutline(o: android.graphics.Outline) { @Suppress("DEPRECATION") o.setConvexPath(outer) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** the dashed pink "Add" box */
class DashedRect : Drawable() {
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = K.tint2 }
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = K.line; strokeWidth = Ui.dp(1.5f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(Ui.dp(5f), Ui.dp(4f)), 0f) }
    override fun draw(c: Canvas) { val b = RectF(bounds); val r = Ui.dp(14f); c.drawRoundRect(b, r, r, f); b.inset(Ui.dp(.75f), Ui.dp(.75f)); c.drawRoundRect(b, r, r, s) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

// ───────── GIF gifts (animated). price = coins the sender pays; the receiver earns half ─────────
class Gif(val id: String, val e: String, val label: String, val cls: String, val price: Int, val g: String, val vip: Boolean = false, val many: Boolean = false)
val GIF_GROUPS = listOf("sweet" to "Sweet", "love" to "Love", "choco" to "Chocolates", "night" to "Good night", "toys" to "Toys", "premium" to "Premium")
val GIFS = listOf(
    Gif("hi", "👋", "Hi!", "wave", 1, "sweet"), Gif("tea", "🍵", "Chai?", "float", 1, "sweet"), Gif("coffee", "☕", "Coffee?", "float", 1, "sweet"),
    Gif("blush", "🥰", "Aww", "float", 1, "sweet"), Gif("lol", "😂", "Haha", "shake", 1, "sweet"),
    Gif("rose", "🌹", "For you", "sway", 2, "love"), Gif("hug", "🤗", "Hug", "pop", 2, "love"), Gif("missyou", "🥺", "Miss you", "float", 2, "love"),
    Gif("heart", "❤️", "Love it", "beat", 2, "love"), Gif("kiss", "💋", "Kiss", "pop", 2, "love"),
    Gif("choco", "🍫", "Chocolate", "pop", 2, "choco"), Gif("candy", "🍬", "Candy", "shake", 2, "choco"), Gif("cupcake", "🧁", "Cupcake", "float", 2, "choco"), Gif("donut", "🍩", "Donut", "pop", 2, "choco"),
    Gif("night", "🌙", "Good night", "tw", 1, "night"), Gif("dreams", "💤", "Sweet dreams", "float", 1, "night"), Gif("morning", "🌅", "Good morning", "glow", 1, "night"),
    Gif("teddy", "🧸", "Teddy", "pop", 5, "toys"), Gif("balloons", "🎈", "Balloons", "float", 5, "toys"), Gif("unicorn", "🦄", "Unicorn", "float", 5, "toys"),
    Gif("bouquet", "💐", "Bouquet", "sway", 10, "premium", vip = true), Gif("cake", "🎂", "Cake", "pop", 10, "premium", vip = true), Gif("champagne", "🍾", "Champagne", "shake", 10, "premium", vip = true),
    Gif("crown", "👑", "Queen", "float", 15, "premium", vip = true), Gif("roses100", "🌹", "100 Roses", "beat", 15, "premium", vip = true, many = true),
    Gif("ring", "💍", "Ring", "glow", 15, "premium", vip = true), Gif("diamond", "💎", "Diamond", "glow", 15, "premium", vip = true), Gif("car", "🏎️", "Dream car", "shake", 15, "premium", vip = true),
)
fun gifOf(id: String): Gif? = GIFS.firstOrNull { it.id == id }

/** the GIFs' little animations (the web's cr-wave, cr-float … keyframes) */
object Emoji {
    fun animate(v: View, cls: String, delayMs: Long = 0) {
        if (Lite.reducedMotion) return
        val a: Animator = when (cls) {
            "wave" -> { v.pivotX = 0f; v.post { v.pivotX = v.width * 0.7f; v.pivotY = v.height * 0.8f }
                kf(v, View.ROTATION, 1600, 0f to 0f, .2f to 18f, .4f to -10f, .6f to 14f, .8f to -4f, 1f to 0f) }
            "float" -> kf(v, View.TRANSLATION_Y, 2200, 0f to 0f, .5f to -Ui.dp(7f), 1f to 0f)
            "shake" -> kf(v, View.ROTATION, 700, 0f to 0f, .25f to -9f, .75f to 9f, 1f to 0f)
            "sway" -> { v.post { v.pivotX = v.width * 0.5f; v.pivotY = v.height * 0.9f }; kf(v, View.ROTATION, 2400, 0f to -8f, .5f to 8f, 1f to -8f) }
            "pop" -> ObjectAnimator.ofPropertyValuesHolder(v, pvh(View.SCALE_X, 0f to 1f, .5f to 1.15f, 1f to 1f), pvh(View.SCALE_Y, 0f to 1f, .5f to 1.15f, 1f to 1f), pvh(View.ROTATION, 0f to 0f, .5f to -6f, 1f to 0f)).setDuration(1400)
            "beat" -> ObjectAnimator.ofPropertyValuesHolder(v, pvh(View.SCALE_X, 0f to 1f, .15f to 1.18f, .3f to .96f, .45f to 1.12f, 1f to 1f), pvh(View.SCALE_Y, 0f to 1f, .15f to 1.18f, .3f to .96f, .45f to 1.12f, 1f to 1f)).setDuration(1300)
            "tw" -> ObjectAnimator.ofPropertyValuesHolder(v, pvh(View.ALPHA, 0f to .35f, .5f to 1f, 1f to .35f), pvh(View.SCALE_X, 0f to .8f, .5f to 1.1f, 1f to .8f), pvh(View.SCALE_Y, 0f to .8f, .5f to 1.1f, 1f to .8f)).setDuration(1800)
            "glow" -> ObjectAnimator.ofPropertyValuesHolder(v, pvh(View.ALPHA, 0f to .55f, .5f to 1f, 1f to .55f), pvh(View.SCALE_X, 0f to .92f, .5f to 1.06f, 1f to .92f), pvh(View.SCALE_Y, 0f to .92f, .5f to 1.06f, 1f to .92f)).setDuration(2400)
            else -> return
        }
        (a as? ValueAnimator)?.repeatCount = ValueAnimator.INFINITE
        a.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        a.startDelay = delayMs
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { if (!a.isStarted) a.start() }
            override fun onViewDetachedFromWindow(x: View) { a.cancel() }
        })
        if (v.isAttachedToWindow) a.start()
    }
    /** typing dots: up 4px and brighter at 40 %, one after another */
    fun typingDot(v: View, i: Int) {
        val a = ObjectAnimator.ofPropertyValuesHolder(v, pvh(View.TRANSLATION_Y, 0f to 0f, .4f to -Ui.dp(4f), .8f to 0f, 1f to 0f), pvh(View.ALPHA, 0f to .5f, .4f to 1f, .8f to .5f, 1f to .5f)).setDuration(1200)
        a.repeatCount = ValueAnimator.INFINITE; a.startDelay = i * 150L
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { if (!a.isStarted) a.start() }
            override fun onViewDetachedFromWindow(x: View) { a.cancel() }
        })
    }
    private fun pvh(p: android.util.Property<View, Float>, vararg k: Pair<Float, Float>) = PropertyValuesHolder.ofKeyframe(p, *k.map { Keyframe.ofFloat(it.first, it.second) }.toTypedArray())
    private fun kf(v: View, p: android.util.Property<View, Float>, ms: Long, vararg k: Pair<Float, Float>) = ObjectAnimator.ofPropertyValuesHolder(v, pvh(p, *k)).setDuration(ms)
}

/** gift effect: the gift floats up over the chat like sparkles; "Priya sent you a Bouquet 💐" shows for a few seconds */
@SuppressLint("ViewConstructor")
class GiftFxView(ctx: Context, g: Gif, who: String) : FrameLayout(ctx) {
    private class Item(val ch: String, val left: Float, val size: Float, val dur: Float, val delay: Float, val sx: Float)
    private val items: List<Item>
    private val bitmaps = HashMap<String, Bitmap>()
    private val t0 = System.currentTimeMillis()
    private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val fxE = FX[g.id]?.first ?: listOf(g.e)
        val n = if (Lite.reducedMotion) 0 else FX[g.id]?.second ?: if (g.g == "toys") 8 else 6
        fun rnd(i: Int, k: Int): Float { val x = sin((i + 1) * 12.9898 + k * 78.233) * 43758.5453; return (x - floor(x)).toFloat() }
        items = List(n) { i ->
            val dur = 7 + rnd(i, 1) * 5
            Item(fxE[i % fxE.size], 3 + rnd(i, 2) * 88, 26 + rnd(i, 3) * 16, dur, -(dur * (i.toFloat() / n)) - rnd(i, 4), Math.round(10 + rnd(i, 5) * 34).toFloat().let { if (i % 2 == 1) -it else it })
        }
        val tag = sdText(ctx, "${if (who == "You") "You sent" else "$who sent you"} ${GIFT_NAME[g.id] ?: g.label} ${g.e}", 14.5f, K.wine).oneLine().apply {
            background = rounded(Color.argb(235, 255, 255, 255), 999f, 1f, Ui.c("#F6CFE1")); setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(6f)); elevation = Ui.dp(4f)
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.86f).toInt()
        }
        addView(tag, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(82f) })
        // shows for 7 s: in, stays, out
        tag.alpha = 0f; tag.translationY = -Ui.dp(6f)
        tag.animate().alpha(1f).translationY(0f).setDuration(350).start()
        tag.postDelayed({ tag.animate().alpha(0f).translationY(-Ui.dp(4f)).setDuration(840).start() }, 6160)
    }
    // each emoji drawn once at 42dp (the biggest size used), with room for its soft shadow (drop-shadow 0 4px 8px)
    private val glyph = Ui.dp(42f)
    private val side = Ui.dpi(42f + 28f)
    private fun bmp(ch: String): Bitmap = bitmaps.getOrPut(ch) {
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = glyph; textAlign = Paint.Align.CENTER; setShadowLayer(Ui.dp(6f), 0f, Ui.dp(4f), Color.argb(64, 150, 0, 60)) }
        val fm = tp.fontMetrics
        c.drawText(ch, side / 2f, side / 2f - (fm.ascent + fm.descent) / 2, tp)
        b
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = (System.currentTimeMillis() - t0) / 1000f
        val vh = height.toFloat()
        for (it in items) {
            val t = ((now - it.delay) % it.dur + it.dur) % it.dur / it.dur
            // keyframes: 0 % (0, 0, -10°, .85, 0) · 8 % op 1 · 50 % (sx, -56vh, 8°, 1) · 90 % op .95 · 100 % (-sx, -112vh, -6°, .9, 0)
            val x: Float; val y: Float; val rot: Float; val sc: Float
            if (t < 0.5f) { val k = t / 0.5f; x = it.sx * k; y = -0.56f * vh * k; rot = -10 + 18 * k; sc = 0.85f + 0.15f * k }
            else { val k = (t - 0.5f) / 0.5f; x = it.sx + (-2 * it.sx) * k; y = -0.56f * vh - 0.56f * vh * k; rot = 8 - 14 * k; sc = 1f - 0.1f * k }
            // opacity keyframes: 0 % → 0 · 8 % → 1 · 90 % → .95 · 100 % → 0
            val op = when { t < 0.08f -> t / 0.08f; t < 0.9f -> 1f - 0.05f * (t - 0.08f) / 0.82f; else -> 0.95f * (1 - (t - 0.9f) / 0.1f) }
            val k = Ui.dp(it.size) / glyph * sc                       // font-size of this one, as a share of the drawn emoji
            val half = side * k / 2
            val cx = width * it.left / 100f + Ui.dp(it.size) / 2 + Ui.dp(x)
            val cy = vh + Ui.dp(64f) - Ui.dp(it.size) / 2 + y
            c.save(); c.rotate(rot, cx, cy)
            p.alpha = (op.coerceIn(0f, 1f) * 255).toInt()
            c.drawBitmap(bmp(it.ch), null, RectF(cx - half, cy - half, cx + half, cy + half), p)
            c.restore()
        }
        if (items.isNotEmpty() && isShown) postInvalidateOnAnimation()
    }
    companion object {
        private val FX = mapOf(
            "bouquet" to (listOf("🌸", "🌹", "🌷", "🌺", "🌼") to 10), "roses100" to (listOf("🌹") to 18), "ring" to (listOf("💍", "✨", "💖") to 10),
            "crown" to (listOf("👑", "✨", "💖") to 10), "diamond" to (listOf("💎", "✨") to 10), "car" to (listOf("🏎️", "✨", "💖") to 8),
            "cake" to (listOf("🎂", "🎉", "✨") to 10), "champagne" to (listOf("🍾", "🥂", "🎉") to 10), "balloons" to (listOf("🎈") to 10), "heart" to (listOf("❤️", "💖") to 8),
        )
        private val GIFT_NAME = mapOf(
            "hi" to "a Hi", "tea" to "Chai", "coffee" to "Coffee", "blush" to "an Aww", "lol" to "a Haha", "rose" to "a Rose", "hug" to "a Hug", "missyou" to "Miss you",
            "heart" to "Love", "kiss" to "a Kiss", "choco" to "a Chocolate", "candy" to "a Candy", "cupcake" to "a Cupcake", "donut" to "a Donut",
            "night" to "Good night", "dreams" to "Sweet dreams", "morning" to "Good morning", "teddy" to "a Teddy", "balloons" to "Balloons", "unicorn" to "a Unicorn",
            "bouquet" to "a Bouquet", "cake" to "a Cake", "champagne" to "Champagne", "crown" to "a Queen crown", "roses100" to "100 Roses", "ring" to "a Ring",
            "diamond" to "a Diamond", "car" to "a Dream car",
        )
    }
}

/** one voice note plays at a time */
object Voice {
    private var player: MediaPlayer? = null
    private var uri = ""
    private var cb: ((Boolean, Double, Double) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { val p = player ?: return; if (p.isPlaying) { cb?.invoke(true, p.currentPosition / 1000.0, p.duration / 1000.0); main.postDelayed(this, 200) } } }
    fun toggle(u: String, onState: (Boolean, Double, Double) -> Unit) {
        val p = player
        if (p != null && uri == u) {
            if (p.isPlaying) { p.pause(); onState(false, p.currentPosition / 1000.0, p.duration / 1000.0) } else { p.start(); main.post(tick) }
            return
        }
        stop()
        uri = u; cb = onState
        Photos.url(u) { url ->
            if (url.isEmpty() || uri != u) return@url
            try {
                val mp = MediaPlayer()
                mp.setDataSource(url)
                mp.setOnPreparedListener { it.start(); main.post(tick) }
                mp.setOnCompletionListener { cb?.invoke(false, 0.0, it.duration / 1000.0) }
                mp.prepareAsync()
                player = mp
            } catch (e: Exception) { onState(false, 0.0, 0.0) }
        }
    }
    fun stop() {
        main.removeCallbacks(tick)
        player?.let { p -> try { if (p.isPlaying) p.stop() } catch (e: Exception) { }; p.release(); cb?.invoke(false, 0.0, 0.0) }
        player = null; uri = ""
    }
}

/**
 * What was last seen of each chat (owner, 20 Nov: "a chat should open the moment it is tapped, with everything on
 * it"): the chat list's answer and each chat's last 100 messages, for this sign-in only. A chat opens from here
 * at once, and the server's answer replaces it a moment later.
 */
object ChatCache {
    private var token = ""
    private var meId = ""
    private val chats = HashMap<String, JSONObject>()
    private val msgs = HashMap<String, List<JSONObject>>()
    private fun check() { if (token != Api.token) { token = Api.token; meId = ""; chats.clear(); msgs.clear() } }
    fun me(): String { check(); return meId }
    fun setMe(id: String) { check(); meId = id }
    fun chat(id: String): JSONObject? { check(); return chats[id] }
    fun putChat(c: JSONObject) {
        check()
        val id = c.optString("match_id").ifEmpty { c.optString("id") }
        if (id.isNotEmpty() && c.optJSONObject("other") != null) chats[id] = c
    }
    fun putChats(a: JSONArray?) { for (i in 0 until (a?.length() ?: 0)) a!!.optJSONObject(i)?.let { putChat(it) } }
    fun messages(id: String): List<JSONObject>? { check(); return msgs[id]?.map { JSONObject(it.toString()) } }
    fun putMessages(id: String, list: List<JSONObject>) {
        check()
        if (id.isEmpty()) return
        msgs[id] = list.takeLast(100).map { JSONObject(it.toString()) }
        // (a phone that keeps many chats open in a day: the oldest are let go)
        if (msgs.size > 40) msgs.keys.firstOrNull { it != id }?.let { msgs.remove(it) }
    }
}
