package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Inside a Live Room (LiveRoom.jsx, audio room): voice, the host and up to 5 Co-Host seats, Super Chat
 * (2 coins, no normal chat), Heart (5 coins), free hearts, gifts, Join list → Pay & Join, Follow,
 * Block / Report, End Live. Asks the server like the web page: every 2 s, slower (up to 5 s, 3 s on a seat)
 * while nothing happens, back to 2 s on any news or tap.
 */
@SuppressLint("ViewConstructor")
class LiveRoomPage(ctx: Context, val screen: LiveRoomsScreen, val roomId: String) : FrameLayout(ctx), LiveVoice.Listener {
    companion object {
        const val POLL_MS = 2000L
        const val POLL_MAX_MS = 5000L
        const val POLL_MAX_ON_SEAT_MS = 3000L
        val TAGS = listOf("⭐", "💜", "🔥", "🌸", "💖", "✨", "🌙", "🦋")
        /** the little sign after a name in the chat (the same for the same person, like the web) */
        fun tagOf(id: String): String {
            var h = 0L
            for (ch in id) h = (h * 31 + ch.code) and 0xffffffffL
            return TAGS[(h % TAGS.size).toInt()]
        }
    }

    private val main = Handler(Looper.getMainLooper())

    // ── state (same names as the web page) ──
    var st: JSONObject? = null
        private set
    val msgs = ArrayList<JSONObject>()
    private var fatal = ""
    private var ended = false
    private var joined = false
    var followed = false
        private set
    val blocked = HashSet<String>()
    private var since: String? = null
    private val seen = HashSet<String>()
    private var stSig = ""
    private var quiet = 0
    private var lastSig = ""
    private var likeCount = 0
    private var wasInvited = false
    private var wishSeen: Set<String>? = null
    var leaving = false
    private var closed = false
    private var active = true
    private var started = false
    private var loopBusy = false
    private var sending = false
    private var heartBusy = false
    private val giftQ = ArrayList<JSONObject>()
    private var giftShowing = false
    private var voice: LiveVoice? = null
    var voiceOn: Boolean? = null
        private set
    private var speaking: Set<Int> = emptySet()
    /** the open panel (chat / users / gifts / info), refreshed when the room changes */
    var panel: LiveRoomSheets.Panel? = null
    /** the open Join list sheet, drawn again when the room changes */
    var wishRender: (() -> Unit)? = null

    private val loop = Runnable { pollRound() }
    private val content = FrameLayout(ctx)
    private var live: LiveViews? = null

    init {
        setBackgroundColor(Ui.c("#16072b"))
        isClickable = true
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showLoading()
    }

    // ───────────────────────── small getters ─────────────────────────
    fun room(): JSONObject? = st?.optJSONObject("room")
    fun me(): JSONObject? = st?.optJSONObject("me")
    fun host(): JSONObject? = st?.optJSONObject("host")
    fun cohosts(): List<JSONObject> = DK.arr(st?.optJSONArray("cohosts"))
    fun mode(): String = room()?.optString("mode")?.ifEmpty { "audio" } ?: "audio"
    fun isHost() = me()?.optString("role") == "host"
    fun isCo() = me()?.optString("role") == "cohost"
    fun onSeat() = isHost() || isCo()
    fun myId() = me()?.optString("user_id").orEmpty()
    private fun myMuted() = me()?.optBoolean("muted") == true
    fun fee(): Int = st?.optInt("fee")?.takeIf { it > 0 } ?: LK.feeOf(mode())
    fun seatsLeft(): Int = st?.optInt("seats_left") ?: 0
    fun canJoin() = !onSeat() && seatsLeft() > 0
    fun wishlist(): List<JSONObject> = DK.arr(st?.optJSONArray("wishlist"))
    fun visibleMsgs(): List<JSONObject> = msgs.filter { !blocked.contains(it.optString("user_id")) }

    // ───────────────────────── server ─────────────────────────
    fun start() {
        if (started) return
        started = true
        LiveApi.call("join", JSONObject().put("room_id", roomId)) { r ->
            if (closed) return@call
            if (!r.ok) { setFatal(r.text("Could not open this room.")); return@call }
            val d = r.data
            d.optJSONArray("blocked")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { blocked.add(it) } }
            followed = d.optBoolean("followed")
            apply(d, true)
            joined = !d.optBoolean("ended")
            if (!joined) return@call
            syncVoice()
            if (screen.giftOnJoin == roomId) { screen.giftOnJoin = null; main.postDelayed({ if (!closed && !ended) LiveRoomSheets.panel(this, "gifts") }, 350) }
            lastSig = roomSig(d)
            schedule(POLL_MS)
        }
    }

    /** what tells if the room changed (the quiet room is asked less often) */
    private fun roomSig(d: JSONObject): String {
        val a = JSONArray()
        a.put(d.optBoolean("ended")); a.put(d.optInt("likes")); a.put(d.optJSONObject("room")?.optLong("viewers") ?: 0)
        a.put(d.optJSONObject("room")?.optString("mode")); a.put(d.opt("host")?.toString()); a.put(d.opt("cohosts")?.toString()); a.put(d.opt("me")?.toString()); a.put(d.optInt("seats_left"))
        return a.toString()
    }

    private fun schedule(ms: Long) {
        main.removeCallbacks(loop)
        if (!closed && active && !ended && fatal.isEmpty()) main.postDelayed(loop, ms)
    }

    private fun pollRound() {
        if (closed || loopBusy || ended || fatal.isNotEmpty()) return
        loopBusy = true
        poll { r ->
            loopBusy = false
            if (closed) return@poll
            if (!r.ok && (r.status == 403 || r.status == 404 || r.status == 410)) { setFatal(r.text()); return@poll }
            if (r.ok) {
                if (r.data.optBoolean("ended")) return@poll
                val sig = roomSig(r.data)
                val news = (r.data.optJSONArray("messages")?.length() ?: 0) > 0
                quiet = if (sig == lastSig && !news) quiet + 1 else 0
                lastSig = sig
            }
            val max = if (onSeat()) POLL_MAX_ON_SEAT_MS else POLL_MAX_MS
            schedule(min(max, POLL_MS + quiet * 1000L))
        }
    }

    private fun poll(done: ((LiveApi.Res) -> Unit)? = null) {
        val body = JSONObject().put("room_id", roomId)
        since?.let { body.put("since", it) }
        LiveApi.call("state", body) { r ->
            if (closed) return@call
            if (r.ok) apply(r.data, false)
            done?.invoke(r)
        }
    }

    /** ask once now (after my own tap): the room shows the change at once */
    fun refresh() { quiet = 0; poll() }

    private fun apply(d: JSONObject, first: Boolean) {
        // nothing changed in the room (only the clock): keep the screen as it is
        val sig = run { val c = JSONObject(d.toString()); c.remove("now"); c.remove("messages"); c.remove("likes"); c.toString() }
        var changed = false
        if (first || sig != stSig) { stSig = sig; st = d; changed = true }
        if (d.optBoolean("ended")) { ended = true; showEnd(); return }
        val fresh = DK.arr(d.optJSONArray("messages")).filter { !seen.contains(it.optString("id")) }
        fresh.forEach { seen.add(it.optString("id")) }
        if (fresh.isNotEmpty()) {
            since = fresh.last().optString("at")
            msgs.addAll(fresh)
            while (msgs.size > 150) msgs.removeAt(0)
            // my own gifts already popped up
            if (!first) fresh.filter { it.optString("kind") == "gift" && it.optString("user_id") != myId() }.forEach { queueGift(it) }
        } else if (since == null) since = d.optString("now").ifEmpty { null }
        // the server counts likes since the last chat line, so the same likes come back until someone writes:
        // only the new ones fly up
        val likes = d.optInt("likes")
        val newLikes = likes - likeCount
        likeCount = likes
        if (live == null) showLive()
        if (newLikes > 0) live?.hearts?.add(newLikes)
        if (changed) { live?.update(); panel?.roomChanged(); wishRender?.invoke() }
        if (fresh.isNotEmpty()) { live?.chatChanged(); panel?.chatChanged() }
        // the host invited me from the Join list → Pay & Join pops up (once per invite)
        val inv = me()?.optBoolean("invited") == true
        if (inv && !wasInvited) LiveRoomSheets.joinDialog(this)
        wasInvited = inv
        // host: a new Join list request pops up as a short note
        if (isHost()) {
            val list = wishlist()
            val ids = list.map { it.optString("user_id") }.toSet()
            val was = wishSeen
            if (was != null) {
                val newOnes = list.filter { !was.contains(it.optString("user_id")) }
                if (newOnes.isNotEmpty()) screen.showToast(if (newOnes.size == 1) "${newOnes[0].optString("name")} wants to join you" else "${newOnes.size} new join requests")
            }
            wishSeen = ids
        }
        syncVoice()
    }

    private fun setFatal(msg: String) {
        fatal = msg.ifBlank { "Could not open this room." }
        joined = false
        main.removeCallbacks(loop)
        showEnd()
    }

    /** a room action (act in the web page): watch the room closely again, say [ok], refresh */
    fun act(action: String, body: JSONObject = JSONObject(), ok: String? = null, done: ((LiveApi.Res) -> Unit)? = null) {
        quiet = 0
        LiveApi.call(action, JSONObject(body.toString()).put("room_id", roomId)) { r ->
            if (closed) return@call
            if (r.ok) { if (ok != null) screen.showToast(ok) } else screen.showToast(r.text())
            refresh()
            done?.invoke(r)
        }
    }

    // ───────────────────────── actions ─────────────────────────
    private fun goBack() = screen.nav.back()

    /** ‹ / Leave room: the host is asked first (ending the live) */
    fun leave() {
        if (isHost() && !ended && fatal.isEmpty()) { LiveRoomSheets.endConfirm(this); return }
        leaving = true
        if (!ended && fatal.isEmpty()) LiveApi.call("leave", JSONObject().put("room_id", roomId)) { }
        goBack()
    }

    fun endLive() {
        leaving = true
        LiveApi.call("end", JSONObject().put("room_id", roomId)) { r -> if (!r.ok) screen.showToast(r.text()) }
        goBack()
    }

    fun like() {
        live?.hearts?.add(3)
        LiveApi.call("like", JSONObject().put("room_id", roomId)) { }
    }

    /** every message is a Super Chat: 2 coins to the host (the host's own lines are free) */
    fun send(text: String, done: (Boolean) -> Unit) {
        val t = text.trim()
        if (t.isEmpty() || sending) { done(false); return }
        sending = true
        quiet = 0
        LiveApi.call("message", JSONObject().put("room_id", roomId).put("text", t).put("client_id", DK.newId())) { r ->
            sending = false
            if (closed) return@call
            if (r.ok) {
                val id = r.data.optString("id")
                val m = me()
                if (id.isNotEmpty() && !seen.contains(id) && m != null) {
                    seen.add(id)
                    msgs.add(JSONObject().put("id", id).put("kind", "text").put("user_id", m.optString("user_id")).put("name", m.optString("name")).put("photo", m.optString("photo"))
                        .put("text", t).put("coins", if (isHost()) 0 else LK.SUPER_CHAT_COINS).put("at", LK.iso(System.currentTimeMillis())))
                    while (msgs.size > 150) msgs.removeAt(0)
                    live?.chatChanged(); panel?.chatChanged()
                }
                done(true)
            } else {
                screen.showToast(if (r.noCoins) "A Super Chat costs ${LK.SUPER_CHAT_COINS} coins. You have ${LK.n(r.data.optLong("balance"))}." else r.text("Message not sent."))
                done(false)
            }
        }
    }

    /** one tap = 1 Heart (the cheapest gift) to the host */
    fun sendHeart(from: View?) {
        val s = st ?: return
        if (heartBusy || isHost()) return
        val hostO = s.optJSONObject("host") ?: return
        from?.let { live?.burst(it) }
        heartBusy = true
        LiveApi.call("gift", JSONObject().put("room_id", roomId).put("to_user_id", hostO.optString("user_id")).put("gift_id", LK.HEART.id).put("count", 1).put("client_id", DK.newId())) { r ->
            heartBusy = false
            if (closed) return@call
            if (r.ok) {
                queueGift(JSONObject().put("id", "local-${System.currentTimeMillis()}").put("gift_id", LK.HEART.id).put("count", 1).put("name", "You").put("to_name", hostO.optString("name")))
                live?.hearts?.add(4)
                refresh()
            } else screen.showToast(if (r.noCoins) "You need ${LK.n(r.data.optLong("need"))} coins. You have ${LK.n(r.data.optLong("balance"))}." else r.text("Heart not sent."))
        }
    }

    /** a gift from the gifts panel was sent */
    fun giftSent(g: JSONObject) { queueGift(g); refresh() }

    fun setWish(on: Boolean) {
        me()?.put("wish", on)
        stSig = ""
        live?.update()
        act("wish", JSONObject().put("on", on), if (on) "Request sent to the host" else "You left the Join list") { r ->
            if (r.ok && r.data.optBoolean("invited")) screen.showToast("The host invited you to a seat")
        }
    }

    fun share() {
        val r = room() ?: return
        val h = r.optJSONObject("host")
        val t = "Join ${h?.optString("name").orEmpty()}'s live \"${r.optString("name")}\" on True Find 💜 Room ID: ${r.optString("code")}"
        screen.nav.share(r.optString("name"), t)
    }

    fun copyId() {
        val code = room()?.optString("code").orEmpty()
        screen.showToast(if (liveCopy(context, code)) "Room ID copied" else "Could not copy")
    }

    fun toggleMic() {
        val m = me() ?: return
        if (!onSeat()) return
        val muted = m.optBoolean("muted")
        if (m.optBoolean("host_muted") && muted) { screen.showToast("The host muted you."); return }
        m.put("muted", !muted)
        stSig = ""
        live?.update()
        syncVoice()
        // mic on again: if the microphone was not allowed before, it is asked again now
        if (muted) voice?.retryStage()
        act("mute", JSONObject().put("muted", !muted))
    }

    /** Follow / Unfollow this room; [toast] = the short note (the header's + button says it, the info tab doesn't) */
    fun toggleFollow(toast: Boolean, done: (() -> Unit)? = null) {
        LiveApi.call("follow", JSONObject().put("room_id", roomId).put("on", !followed)) { r ->
            if (closed) return@call
            if (r.ok) {
                followed = r.data.optBoolean("followed")
                if (toast) screen.showToast(if (followed) "Following this room" else "Unfollowed")
                live?.update(); (content.getChildAt(0) as? EndView)?.refresh(); panel?.roomChanged()
            } else screen.showToast(r.text())
            done?.invoke()
        }
    }

    /** Pay & Join as Co-Host; [done] gets the failed answer (null = joined) */
    fun payAndJoin(done: (LiveApi.Res?) -> Unit) {
        LiveApi.call("cohost_join", JSONObject().put("room_id", roomId).put("client_id", DK.newId())) { r ->
            if (closed) return@call
            if (r.ok) {
                screen.showToast("You're on a seat now. Your mic is on.")
                refresh()
                done(null)
            } else done(r)
        }
    }

    /** someone tapped in the room (chat photo, seat, user list) */
    fun openUser(u: JSONObject) {
        val id = u.optString("user_id")
        if (id.isEmpty()) return
        val self = id == myId()
        if (self && !u.optBoolean("self")) return
        LiveRoomSheets.person(this, u)
    }

    /** someone was blocked or reported: their lines go away; the host → I leave the room */
    fun onBlocked(u: JSONObject) {
        blocked.add(u.optString("user_id"))
        live?.chatChanged(); panel?.chatChanged()
        if (u.optString("user_id") == host()?.optString("user_id")) {
            leaving = true
            LiveApi.call("leave", JSONObject().put("room_id", roomId)) { }
            screen.nav.replace("/live")
        }
    }

    // ───────────────────────── voice ─────────────────────────
    private fun syncVoice() {
        if (closed) return
        if (!joined || ended || fatal.isNotEmpty()) { stopVoice(); return }
        val v = voice ?: LiveVoice(context, roomId, this).also { voice = it; it.start() }
        v.setPublisher(onSeat())
        v.setMuted(myMuted())
        v.setPaused(!active)
    }
    private fun stopVoice() { voice?.stop(); voice = null }

    override fun onVoiceEnabled(on: Boolean) { voiceOn = on; live?.update() }
    override fun onSpeaking(uids: Set<Int>) { speaking = uids; live?.updateSpeaking() }
    override fun onVoiceError(msg: String) { screen.showToast(msg) }
    override fun askMic(done: (Boolean) -> Unit) = screen.nav.askMic(done)
    fun speakingNow(uid: Int) = speaking.contains(uid)

    // ───────────────────────── life ─────────────────────────
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        voice?.setPaused(!on)
        if (on && started && !closed) { quiet = 0; main.removeCallbacks(loop); pollRound() } else main.removeCallbacks(loop)
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        stopVoice()
        panel?.dismiss()
        val s = st
        // a Co-Host who only opened the Wallet / Leaderboard keeps the paid seat (the server frees it after 75 s away);
        // "Leave room" and "Leave Co-Host seat" still free it at once
        val role = s?.optJSONObject("me")?.optString("role")
        if (!leaving && s != null && !s.optBoolean("ended") && fatal.isEmpty() && role != "host" && role != "cohost")
            LiveApi.call("leave", JSONObject().put("room_id", roomId)) { }
    }

    /**
     * phone back inside the room: the host is asked before the live ends (like the ‹ button).
     * Everyone else just goes back, as on the web page: a listener leaves the room (close()), a Co-Host keeps
     * the paid seat for a while (the server frees it after 75 s away; "Leave room" frees it at once).
     */
    fun onBack(): Boolean {
        if (live?.onBack() == true) return true
        if (isHost() && !ended && fatal.isEmpty() && live != null) { LiveRoomSheets.endConfirm(this); return true }
        return false
    }

    // ───────────────────────── gifts popping up ─────────────────────────
    private fun queueGift(g: JSONObject) {
        if (giftQ.size < 6) giftQ.add(g)
        if (!giftShowing) nextGift()
    }
    private fun nextGift() {
        val g = if (giftQ.isEmpty()) null else giftQ.removeAt(0)
        if (g == null || closed) { giftShowing = false; return }
        giftShowing = true
        live?.giftBurst(g)
        main.postDelayed({ nextGift() }, 2800)
    }

    // ───────────────────────── screens ─────────────────────────
    private fun swap(v: View) {
        content.removeAllViews()
        content.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showLoading() {
        val f = FrameLayout(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#2a0d55"), Ui.c("#12051f"))) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(DotsSpinner(context, Ui.c("#d8b4fe")), DK.llp(Ui.dpi(34f), Ui.dpi(34f)))
        col.addView(Ui.text(context, "Joining the room…", 15f, Ui.ca("#ffffff", 0.8f), false), DK.llp(DK.WRAP, DK.WRAP, t = 14f))
        f.addView(col, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER))
        f.addView(LK.roundBtn(context, DIcon(DIcon.CHEVRON_LEFT, Color.WHITE, 26f), 44f, false, "Back").apply { setOnClickListener { goBack() } },
            DK.flp(Ui.dpi(44f), Ui.dpi(44f), Gravity.TOP or Gravity.START, l = 8f, t = 8f))
        swap(f)
    }

    private fun showEnd() {
        main.removeCallbacks(loop)
        stopVoice()
        panel?.dismiss()
        screen.sheets.close()
        screen.dialog?.close()
        live = null
        swap(EndView())
    }

    private fun showLive() {
        val l = LiveViews()
        live = l
        swap(l.root)
        l.update()
        l.chatChanged()
    }

    // ───────── ended / can't open ─────────
    private inner class EndView : FrameLayout(context) {
        private val followBtn: TextView?
        init {
            background = object : android.graphics.drawable.Drawable() {
                private val p = Paint()
                override fun draw(c: Canvas) {
                    val b = bounds
                    val sh = android.graphics.RadialGradient(0f, 0f, 1f, intArrayOf(Ui.c("#4c1a8a"), Ui.c("#1a0833")), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
                    sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(Math.max(1f, b.width() * 0.9f), Math.max(1f, b.height() * 0.6f)); postTranslate(b.exactCenterX(), b.top + b.height() * 0.2f) })
                    p.shader = sh; c.drawRect(b, p)
                }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
            }
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(28f), Ui.dpi(28f), Ui.dpi(28f), Ui.dpi(28f)) }
            val r = room()
            val h = r?.optJSONObject("host")
            if (h != null) box.addView(AvatarView(context, 110f, Ui.ca("#ffffff", 0.6f), 5.5f).apply { set(h.optString("photo")) })
            val isFatal = fatal.isNotEmpty()
            box.addView(DK.t(context, if (isFatal) "Can't open this room" else "This live has ended", 23f, Color.WHITE, true, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 18f, b = 6f))
            val line = if (isFatal) fatal else if (r != null) "${h?.optString("name").orEmpty()} is offline now. Follow ${r.optString("name")} to find it again." else ""
            box.addView(DK.t(context, line, 15.5f, Ui.ca("#ffffff", 0.85f), false, 0).apply { gravity = Gravity.CENTER; maxWidth = Ui.dpi(320f); setLineSpacing(0f, 1.2f) })
            followBtn = if (r != null && !isFatal) DK.button(context, "", null).apply { setOnClickListener { toggleFollow(false) } } else null
            followBtn?.let { box.addView(it, DK.llp(DK.WRAP, Ui.dpi(50f), t = 22f)) }
            box.addView(DK.lineButton(context, "Back to Live Rooms", color = Color.WHITE, stroke = Ui.ca("#ffffff", 0.5f)).apply {
                background = DK.pill(Color.TRANSPARENT, Ui.ca("#ffffff", 0.5f), 1.5f)
                setPadding(Ui.dpi(30f), 0, Ui.dpi(30f), 0)
                setOnClickListener { screen.nav.replace("/live") }
            }, DK.llp(DK.WRAP, Ui.dpi(50f), t = 12f))
            addView(box, DK.flp(DK.MATCH, DK.WRAP, Gravity.CENTER))
            addView(LK.roundBtn(context, DIcon(DIcon.CHEVRON_LEFT, Color.WHITE, 26f), 44f, false, "Back").apply { setOnClickListener { goBack() } },
                DK.flp(Ui.dpi(44f), Ui.dpi(44f), Gravity.TOP or Gravity.START, l = 8f, t = 8f))
            refresh()
            box.fadeUpIn()
        }
        fun refresh() {
            val b = followBtn ?: return
            b.text = if (followed) "Following" else "Follow Room"
            b.contentDescription = b.text
            b.background = if (followed) DK.pill(Ui.ca("#ffffff", 0.15f)) else DK.grad(DK.GRAD, 25f)
            b.elevation = if (followed) 0f else Ui.dp(5f)
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(if (followed) DIcon.CHECK else DIcon.PLUS, Color.WHITE, 19f), null, null, null)
            b.compoundDrawablePadding = Ui.dpi(8f)
            b.setPadding(Ui.dpi(30f), 0, Ui.dpi(30f), 0)
        }
    }

    // ════════════════════════ LIVE ════════════════════════
    private inner class LiveViews {
        val root = FrameLayout(context).apply { background = LiveBg.room(); clipChildren = false }
        val hearts = HeartsView(context)
        private val burstFx = BurstView(context)
        private val giftLayer = FrameLayout(context)
        // header
        private val backBtn: View
        private val title: TextView
        private val idText: TextView
        private val hostChip = LinearLayout(context)
        private val hostBtn: LinearLayout
        private val hostFace = AvatarView(context, 42f)
        private val hostName: TextView
        private val followBtn: FrameLayout
        private val online: TextView
        private val badge = LevelBadgeView(context, 58f)
        // stage
        private val hostBig = HostFace(context, 128f)
        private val waveL = WaveView(context, false)
        private val waveR = WaveView(context, true)
        private val hostLabel: TextView
        private val soon: TextView
        private val seatsRow = LinearLayout(context)
        private var seatSig = ""
        private val seatFaces = HashMap<Int, SeatFace>() // agora uid → seat (for "who is talking")
        // chat over the stage
        private val chatBox = FadeTopFrame(context)
        private val chatCol = LinearLayout(context)
        private var chatSig = ""
        // bottom bar
        private val input: EditText
        private val sendBtn: TextView
        private val heartBtn: TextView
        private val wishBtn: FrameLayout
        private val wishIcon: ImageView
        private val wishBadge: TextView
        private val micBtn: FrameLayout
        private val micIcon: ImageView
        private val freeHeart: TextView
        private val giftBtn: FrameLayout

        init {
            val ctx = context
            // ── stage: waves · host · waves, name, seats ──
            val stage = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
            val hostRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
            hostRow.addView(waveL)
            hostBig.isClickable = true
            hostBig.setOnClickListener { LiveSheets.hostProfile(screen, roomId, if (isHost()) null else ({ LiveRoomSheets.panel(this@LiveRoomPage, "gifts", giftTo = host()?.optString("user_id")) }), isHost()) }
            hostRow.addView(hostBig, DK.llp(Ui.dpi(128f), Ui.dpi(128f), l = 14f, r = 14f))
            hostRow.addView(waveR)
            stage.addView(hostRow)
            hostLabel = LK.nameBadge(ctx, "", 17f, Color.WHITE, 18f).apply { gravity = Gravity.CENTER; maxWidth = Ui.dpi(260f) }
            stage.addView(hostLabel, DK.llp(DK.WRAP, DK.WRAP, t = 10f))
            soon = Ui.text(ctx, "Live voice starts soon", 12.5f, Ui.ca("#ffffff", 0.75f), false).apply { visibility = View.GONE }
            stage.addView(soon, DK.llp(DK.WRAP, DK.WRAP, t = 4f))
            seatsRow.orientation = LinearLayout.HORIZONTAL; seatsRow.clipChildren = false; seatsRow.clipToPadding = false
            seatsRow.setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
            stage.addView(seatsRow, DK.llp(DK.MATCH, DK.WRAP, t = 18f))
            root.addView(stage, DK.flp(DK.MATCH, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 150f))

            // ── chat over the stage (the last 7 lines, fading at the top) ──
            chatCol.orientation = LinearLayout.VERTICAL
            chatCol.gravity = Gravity.BOTTOM
            chatBox.addView(chatCol, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            root.addView(chatBox, DK.flp(DK.MATCH, DK.WRAP, Gravity.BOTTOM, l = 10f, r = 92f, b = 76f))

            root.addView(hearts, DK.flp(Ui.dpi(90f), Ui.dpi(320f), Gravity.BOTTOM or Gravity.END, r = 8f, b = 150f))
            root.addView(giftLayer, DK.flp())

            // ── header ──
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = LiveBg.topFade(); setPadding(Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(6f)); clipChildren = false; clipToPadding = false }
            val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            backBtn = LK.roundBtn(ctx, DIcon(DIcon.CHEVRON_LEFT, Color.WHITE, 28f), 40f, false, "Leave room", bg = GradientDrawable()).apply { setOnClickListener { leave() } }
            row1.addView(backBtn)
            val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(6f), 0, Ui.dpi(6f), 0) }
            val trow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            title = DK.t(ctx, "", 19f, Color.WHITE).apply { setShadowLayer(Ui.dp(6f), 0f, Ui.dp(1f), Ui.ca("#000000", 0.45f)) }
            trow.addView(title, DK.llp(DK.WRAP, DK.WRAP))
            trow.addView(Ui.text(ctx, "🔥 LIVE", 12f, Color.WHITE).apply {
                gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#ff2d6f"), Ui.c("#c026d3"))).apply { cornerRadius = Ui.dp(99f) }
                setPadding(Ui.dpi(8f), 0, Ui.dpi(8f), 0)
            }, DK.llp(DK.WRAP, Ui.dpi(22f), l = 8f))
            tcol.addView(trow)
            idText = Ui.text(ctx, "", 13.5f, Ui.ca("#ffffff", 0.85f), false).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, DIcon(DIcon.COPY, Ui.ca("#ffffff", 0.85f), 14f), null); compoundDrawablePadding = Ui.dpi(5f)
                contentDescription = "Copy Room ID"; isClickable = true; setOnClickListener { copyId() }
            }
            tcol.addView(idText, DK.llp(DK.WRAP, DK.WRAP, t = 2f))
            row1.addView(tcol, DK.llp(0, DK.WRAP, 1f))
            row1.addView(LK.roundBtn(ctx, DIcon(DIcon.SHARE, Color.WHITE, 20f), 40f, false, "Share room").apply { setOnClickListener { share() } })
            row1.addView(LK.roundBtn(ctx, DIcon(LK.MORE, Color.WHITE, 22f, Color.WHITE), 40f, false, "More options").apply { setOnClickListener { LiveRoomSheets.menu(this@LiveRoomPage) } }, DK.llp(Ui.dpi(40f), Ui.dpi(40f), l = 6f))
            head.addView(row1)
            val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(4f), 0, Ui.dpi(4f), 0) }
            hostChip.orientation = LinearLayout.HORIZONTAL; hostChip.gravity = Gravity.CENTER_VERTICAL
            hostChip.background = LK.glass(30f)
            hostChip.setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(5f), Ui.dpi(4f))
            hostBtn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
                setOnClickListener { LiveSheets.hostProfile(screen, roomId, if (isHost()) null else ({ LiveRoomSheets.panel(this@LiveRoomPage, "gifts", giftTo = host()?.optString("user_id")) }), isHost()) }
            }
            hostBtn.addView(hostFace)
            val hcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(9f), 0, Ui.dpi(4f), 0) }
            hostName = LK.nameBadge(ctx, "", 15f, Color.WHITE, 16f).apply { maxWidth = Ui.dpi(140f) }
            hcol.addView(hostName, DK.llp(DK.WRAP, DK.WRAP))
            hcol.addView(LK.hostTag(ctx, co = false, small = true), DK.llp(DK.WRAP, Ui.dpi(20f), t = 2f))
            hostBtn.addView(hcol)
            hostChip.addView(hostBtn)
            followBtn = DK.iconButton(ctx, DIcon(DIcon.PLUS, Color.WHITE, 17f, stroke = 2.8f), 30f, DK.grad(LK.PINK_GRAD, 15f), "Follow room").apply { setOnClickListener { toggleFollow(true) } }
            hostChip.addView(followBtn, DK.llp(Ui.dpi(30f), Ui.dpi(30f), l = 4f))
            row2.addView(hostChip, DK.llp(DK.WRAP, DK.WRAP))
            row2.addView(View(ctx), DK.llp(0, 1, 1f))
            online = Ui.text(ctx, "", 13.5f, Color.WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL; background = LK.glass(17f)
                setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.USERS, Color.WHITE, 17f), null, null, null); compoundDrawablePadding = Ui.dpi(6f)
                isClickable = true; setOnClickListener { LiveRoomSheets.panel(this@LiveRoomPage, "users") }
            }
            row2.addView(online, DK.llp(DK.WRAP, Ui.dpi(34f)))
            head.addView(row2, DK.llp(t = 10f))
            badge.isClickable = true
            badge.setOnClickListener { LiveRoomSheets.panel(this@LiveRoomPage, "info", sub = "level") }
            head.addView(badge, DK.llp(DK.WRAP, DK.WRAP, l = 2f, t = 6f))
            root.addView(head, DK.flp(DK.MATCH, DK.WRAP, Gravity.TOP))

            // ── the big gift button ──
            giftBtn = FrameLayout(ctx).apply {
                contentDescription = "Send a gift"; isClickable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = Ui.dp(40f)
                    colors = intArrayOf(Ui.c("#ff7ad9"), Ui.c("#b5179e"), Ui.c("#6d0f8f")); setGradientCenter(0.35f, 0.3f)
                    setStroke(Ui.dpi(2f), Ui.ca("#ffffff", 0.6f))
                }
                elevation = Ui.dp(12f)
                outlineSpotShadowColorCompat(Ui.c("#ff50c8"))
                setOnClickListener { LiveRoomSheets.panel(this@LiveRoomPage, "gifts") }
                Ui.pressable(this, 0.95f)
            }
            val box = Ui.text(ctx, "🎁", 34f, Color.WHITE, false).apply { gravity = Gravity.CENTER }
            giftBtn.addView(box, DK.flp(DK.MATCH, DK.MATCH))
            box.giftRock()
            root.addView(giftBtn, DK.flp(Ui.dpi(66f), Ui.dpi(66f), Gravity.BOTTOM or Gravity.END, r = 14f, b = 84f))

            // ── bottom bar: Super Chat · Heart · Join list · mic · free hearts · more ──
            val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(10f), Ui.dpi(8f), Ui.dpi(10f), Ui.dpi(12f)) }
            val field = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Ui.ca("#ffffff", 0.14f)); cornerRadius = Ui.dp(24f); setStroke(Ui.dpi(1f), Ui.ca("#ffd678", 0.55f)) }
                setPadding(Ui.dpi(12f), 0, Ui.dpi(4f), 0)
            }
            input = EditText(ctx).apply {
                setHintTextColor(Ui.ca("#ffffff", 0.6f)); setTextColor(Color.WHITE)
                typeface = Ui.regular; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f))
                background = null; setPadding(0, 0, 0, 0); isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEND
                filters = arrayOf(InputFilter.LengthFilter(200))
                setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { doSend(); true } else false }
            }
            field.addView(input, DK.llp(0, Ui.dpi(40f), 1f))
            sendBtn = Ui.text(ctx, "", 13f, Color.WHITE).apply {
                gravity = Gravity.CENTER; isClickable = true
                setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0)
                setOnClickListener { doSend() }
            }
            field.addView(sendBtn, DK.llp(DK.WRAP, Ui.dpi(38f)))
            bar.addView(field, DK.llp(0, Ui.dpi(48f), 1f))
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { paintSend() }
            })
            heartBtn = Ui.text(ctx, "", 12.5f, Color.WHITE).apply {
                gravity = Gravity.CENTER; isClickable = true
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, LK.PINK_GRAD).apply { cornerRadius = Ui.dp(22f) }
                setPadding(Ui.dpi(8f), 0, Ui.dpi(8f), 0)
                val sb = SpannableStringBuilder(LK.HEART.e)
                sb.setSpan(android.text.style.AbsoluteSizeSpan(Ui.dpi(19f)), 0, sb.length, 0)
                sb.append("${LK.HEART.price}")
                text = sb
                contentDescription = "Send ${LK.HEART.name} for ${LK.HEART.price} coins"
                setOnClickListener { sendHeart(this) }
                Ui.pressable(this, 0.95f)
            }
            bar.addView(heartBtn, DK.llp(DK.WRAP, Ui.dpi(44f), l = 6f))
            wishBtn = FrameLayout(ctx).apply { isClickable = true; clipChildren = false; setOnClickListener { LiveRoomSheets.wish(this@LiveRoomPage) } }
            wishBtn.addView(FrameLayout(ctx).apply { background = DK.oval(Color.WHITE); elevation = Ui.dp(4f) }, DK.flp())
            wishIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER; elevation = Ui.dp(4f) }
            wishBtn.addView(wishIcon, DK.flp())
            wishBadge = Ui.text(ctx, "", 11f, Color.WHITE).apply {
                gravity = Gravity.CENTER; background = DK.pill(Ui.c("#ef4444")); setPadding(Ui.dpi(5f), 0, Ui.dpi(5f), 0); minWidth = Ui.dpi(19f); elevation = Ui.dp(6f)
            }
            wishBtn.addView(wishBadge, DK.flp(DK.WRAP, Ui.dpi(19f), Gravity.TOP or Gravity.END, t = -3f, r = -3f))
            bar.addView(wishBtn, DK.llp(Ui.dpi(44f), Ui.dpi(44f), l = 6f))
            micBtn = FrameLayout(ctx).apply { isClickable = true; background = DK.oval(Color.WHITE); elevation = Ui.dp(4f); setOnClickListener { toggleMic() } }
            micIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER }
            micBtn.addView(micIcon, DK.flp())
            bar.addView(micBtn, DK.llp(Ui.dpi(44f), Ui.dpi(44f), l = 6f))
            freeHeart = Ui.text(ctx, "💗", 20f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER; background = DK.oval(Color.WHITE); elevation = Ui.dp(4f)
                contentDescription = "Send free hearts"; isClickable = true
                setOnClickListener { burst(this); like() }
            }
            bar.addView(freeHeart, DK.llp(Ui.dpi(44f), Ui.dpi(44f), l = 6f))
            bar.addView(LK.roundBtn(ctx, DIcon(LK.MORE, DK.PURPLE, 22f, DK.PURPLE), 44f, true, "Chat, people, gifts and room info").apply {
                setOnClickListener { LiveRoomSheets.panel(this@LiveRoomPage, "chat") }
            }, DK.llp(Ui.dpi(44f), Ui.dpi(44f), l = 6f))
            root.addView(bar, DK.flp(DK.MATCH, DK.WRAP, Gravity.BOTTOM))
            root.addView(burstFx, DK.flp())
            paintSend()
        }

        private fun doSend() {
            val t = input.text.toString()
            if (t.isBlank()) return
            sendBtn.isEnabled = false
            send(t) { ok ->
                sendBtn.isEnabled = true
                if (ok) input.setText("")
                paintSend()
            }
            paintSend()
        }

        private fun paintSend() {
            val has = input.text.toString().isNotBlank()
            sendBtn.background = if (has) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#ffb020"), Ui.c("#e11d8f"))).apply { cornerRadius = Ui.dp(19f) }
                else GradientDrawable().apply { setColor(Ui.ca("#ffffff", 0.16f)); cornerRadius = Ui.dp(19f) }
            val host = isHost()
            val sb = SpannableStringBuilder(" ")
            sb.setSpan(object : android.text.style.ReplacementSpan() {
                override fun getSize(p: Paint, t: CharSequence?, s: Int, e: Int, fm: Paint.FontMetricsInt?) = Ui.dpi(16f)
                override fun draw(c: Canvas, t: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
                    val d = DIcon(DIcon.SEND, Color.WHITE, 16f); val sz = Ui.dpi(16f); val mid = (top + bottom) / 2
                    d.setBounds(x.toInt(), mid - sz / 2, x.toInt() + sz, mid + sz / 2); d.draw(c)
                }
            }, 0, 1, 0)
            if (!host) {
                sb.append("  ")
                sb.setSpan(CoinSpan(), sb.length - 1, sb.length, 0)
                sb.append("${LK.SUPER_CHAT_COINS}")
            }
            sendBtn.text = sb
            sendBtn.alpha = if (has && sendBtn.isEnabled) 1f else if (has) 0.6f else 1f
            sendBtn.contentDescription = if (host) "Send" else "Send Super Chat for ${LK.SUPER_CHAT_COINS} coins"
            input.hint = if (host) "Say something…" else "Super Chat…"
            input.contentDescription = if (host) "Message" else "Super Chat, ${LK.SUPER_CHAT_COINS} coins"
        }

        fun burst(v: View) = burstFx.burst(v)

        fun onBack(): Boolean = false

        fun update() {
            val s = st ?: return
            val r = s.optJSONObject("room") ?: return
            val h = s.optJSONObject("host") ?: JSONObject()
            val m = s.optJSONObject("me") ?: JSONObject()
            val hostMe = isHost()
            // header
            backBtn.contentDescription = if (hostMe) "End live" else "Leave room"
            title.text = r.optString("name")
            // no "Demo" label anywhere in Live Rooms (owner rule, Nov 17)
            title.maxWidth = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - Ui.dpi(40f + 40f + 46f + 20f + 70f)
            idText.text = "ID: ${r.optString("code")}"
            hostFace.set(h.optString("photo")); hostFace.showOnline = h.optBoolean("online")
            hostName.text = h.optString("name")
            hostBtn.contentDescription = "Host ${h.optString("name")}"
            followBtn.visibility = if (hostMe) View.GONE else View.VISIBLE
            (followBtn.getChildAt(0) as ImageView).setImageDrawable(if (followed) DIcon(DIcon.CHECK, Color.WHITE, 16f) else DIcon(DIcon.PLUS, Color.WHITE, 17f, stroke = 2.8f))
            followBtn.background = if (followed) DK.oval(Ui.ca("#ffffff", 0.2f)) else GradientDrawable(GradientDrawable.Orientation.TL_BR, LK.PINK_GRAD).apply { shape = GradientDrawable.OVAL }
            followBtn.contentDescription = if (followed) "Unfollow room" else "Follow room"
            val v = r.optLong("viewers")
            online.text = "${if (v >= 10000) LK.c(v) else LK.n(v)} Online"
            online.contentDescription = "$v online, see people"
            badge.level = r.optInt("level", 1)
            // stage
            hostBig.face.set(h.optString("photo"))
            hostBig.mic.off = h.optBoolean("muted")
            hostBig.contentDescription = "Host ${h.optString("name")}"
            hostLabel.text = h.optString("name")
            soon.visibility = if (voiceOn == false) View.VISIBLE else View.GONE
            updateSeats()
            updateSpeaking()
            // bottom bar
            val seat = onSeat()
            heartBtn.visibility = if (hostMe) View.GONE else View.VISIBLE
            micBtn.visibility = if (seat) View.VISIBLE else View.GONE
            val muted = m.optBoolean("muted")
            micIcon.setImageDrawable(DIcon(if (muted) DIcon.MIC_OFF else DIcon.MIC, if (muted) Ui.c("#ef4444") else DK.PURPLE, 21f))
            micBtn.contentDescription = if (muted) "Turn mic on" else "Turn mic off"
            freeHeart.visibility = if (!seat && !hostMe) View.VISIBLE else View.GONE
            val wishN = wishlist().size
            wishIcon.setImageDrawable(DIcon(DIcon.HAND, if (m.optBoolean("wish")) Ui.c("#16a34a") else DK.PURPLE, 21f))
            wishBadge.text = if (wishN > 99) "99+" else "$wishN"
            wishBadge.visibility = if (wishN > 0) View.VISIBLE else View.GONE
            wishBtn.contentDescription = "Join list, $wishN waiting"
            paintSend()
        }

        private fun updateSeats() {
            val s = st ?: return
            val co = cohosts()
            val slots = LK.seatsOf(mode())
            val canJoin = canJoin()
            val sig = "$slots|$canJoin|${myId()}|" + co.joinToString(",") { "${it.optString("user_id")}:${it.optString("photo")}:${it.optBoolean("muted")}:${it.optString("name")}" }
            if (sig == seatSig) return
            seatSig = sig
            seatsRow.removeAllViews()
            seatFaces.clear()
            var firstEmpty = true
            for (i in 0 until slots) {
                val c = co.getOrNull(i)
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
                if (c != null) {
                    val mine = c.optString("user_id") == myId()
                    val face = SeatFace(context, 58f)
                    face.face.set(c.optString("photo"))
                    face.mic.off = c.optBoolean("muted")
                    seatFaces[c.optInt("agora_uid")] = face
                    cell.addView(face, DK.llp(Ui.dpi(58f), Ui.dpi(58f)))
                    cell.addView(DK.t(context, c.optString("name"), 12.5f, Color.WHITE).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 5f))
                    cell.contentDescription = "${c.optString("name")}, Co-Host"
                    cell.isClickable = true
                    cell.setOnClickListener { openUser(JSONObject(c.toString()).put("role", "cohost").put("self", mine)) }
                } else {
                    val showJoin = canJoin && firstEmpty
                    firstEmpty = false
                    val ring = FrameLayout(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ca("#ffffff", 0.06f)); setStroke(Ui.dpi(2f), Ui.ca("#ffffff", 0.7f), Ui.dp(5f), Ui.dp(4f)) }
                        addView(ImageView(context).apply { setImageDrawable(DIcon(DIcon.PLUS, Color.WHITE, 26f)); scaleType = ImageView.ScaleType.CENTER }, DK.flp())
                    }
                    cell.addView(ring, DK.llp(Ui.dpi(58f), Ui.dpi(58f)))
                    cell.addView(Ui.text(context, if (showJoin) "Join" else "Seat", 12.5f, Color.WHITE).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 5f))
                    cell.alpha = if (showJoin) 1f else 0.45f
                    cell.contentDescription = if (canJoin) "Join seat ${i + 1} for ${fee()} coins" else "Seat ${i + 1} is empty"
                    if (canJoin) { cell.isClickable = true; cell.setOnClickListener { LiveRoomSheets.joinDialog(this@LiveRoomPage) } }
                }
                seatsRow.addView(cell, DK.llp(0, DK.WRAP, 1f, l = if (i == 0) 0f else 6f))
            }
        }

        fun updateSpeaking() {
            val h = host() ?: return
            hostBig.speaking = speakingNow(h.optInt("agora_uid")) || (voiceOn != true && !h.optBoolean("muted"))
            waveL.on = hostBig.speaking; waveR.on = hostBig.speaking
            seatFaces.forEach { (uid, f) -> f.speaking = speakingNow(uid) }
        }

        // ── chat over the stage ──
        fun chatChanged() {
            val list = visibleMsgs().takeLast(7)
            val sig = list.joinToString(",") { it.optString("id") }
            if (sig == chatSig) return
            chatSig = sig
            chatCol.removeAllViews()
            val hn = host()?.optString("name").orEmpty()
            list.forEach { m -> chatCol.addView(LiveChat.line(context, m, true, hn) { u -> openUser(u) }, DK.llp(DK.MATCH, DK.WRAP)) }
            val vh = (this@LiveRoomPage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
            chatBox.maxH = (vh * 0.3f).toInt()
        }

        // ── gift pop-up ──
        fun giftBurst(g: JSONObject) {
            val gift = LK.gift(g.optString("gift_id")) ?: return
            giftLayer.removeAllViews()
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(Ui.text(context, gift.e, 96f, Color.WHITE, false).apply { gravity = Gravity.CENTER; setShadowLayer(Ui.dp(18f), 0f, Ui.dp(10f), Ui.ca("#000000", 0.45f)) })
            val sb = SpannableStringBuilder("${g.optString("name")} sent ${gift.name}${if (g.optInt("count") > 1) " x${g.optInt("count")}" else ""}")
            if (g.optString("to_name").isNotBlank()) { val a = sb.length; sb.append(" to ${g.optString("to_name")}"); sb.setSpan(ForegroundColorSpan(Color.WHITE), a, sb.length, 0); sb.setSpan(StyleSpan(Typeface.NORMAL), a, sb.length, 0) }
            col.addView(DK.t(context, sb, 14.5f, Ui.c("#ffe1a1"), true, 2).apply {
                gravity = Gravity.CENTER; background = LK.glass(99f, 0.72f).apply { setColor(Ui.ca("#280a46", 0.72f)) }; setPadding(Ui.dpi(14f), Ui.dpi(7f), Ui.dpi(14f), Ui.dpi(7f))
            }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            val top = ((this@LiveRoomPage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 0.34f) / Ui.density
            giftLayer.addView(col, DK.flp(Ui.dpi(260f), DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = top))
            if (Lite.reducedMotion) {
                col.alpha = 0f
                col.animate().alpha(1f).setDuration(300).withEndAction { col.animate().alpha(0f).setStartDelay(2000).setDuration(300).withEndAction { giftLayer.removeView(col) }.start() }.start()
                return
            }
            // lrGiftPop: pops up, stays, floats away (2.8 s)
            col.scaleX = 0.3f; col.scaleY = 0.3f; col.alpha = 0f
            col.animate().scaleX(1.12f).scaleY(1.12f).alpha(1f).setDuration(500).withEndAction {
                col.animate().scaleX(1f).scaleY(1f).setDuration(280).withEndAction {
                    col.animate().scaleX(0.9f).scaleY(0.9f).translationY(-Ui.dp(20f)).alpha(0f).setStartDelay(1600).setDuration(420)
                        .withEndAction { giftLayer.removeView(col) }.start()
                }.start()
            }.start()
        }
    }
}

/** A frame that fades its content out at the top (the chat over the stage: mask-image in the web) and keeps it at most [maxH] tall. */
class FadeTopFrame(ctx: Context) : FrameLayout(ctx) {
    var maxH = 0
        set(v) { if (v != field) { field = v; requestLayout() } }
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var maskH = -1
    init { setWillNotDraw(false) }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var tallest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            // the lines are measured without a height limit: the newest ones decide what shows
            c.measure(MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            tallest = maxOf(tallest, c.measuredHeight)
        }
        val limit = if (maxH > 0) maxH else Int.MAX_VALUE
        setMeasuredDimension(width, minOf(tallest, limit))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // the newest lines stay at the bottom: older ones are pushed out at the top
        val hgt = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(paddingLeft, hgt - c.measuredHeight, paddingLeft + c.measuredWidth, hgt)
        }
    }
    override fun dispatchDraw(c: Canvas) {
        if (height <= 0) return
        if (maskH != height) { mask.shader = LinearGradient(0f, 0f, 0f, height * 0.22f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP); maskH = height }
        val save = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(c)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        c.restoreToCount(save)
    }
}

/** One chat line (ChatLine in LiveRoom.jsx): on the dark stage, or in the white Chat panel. */
object LiveChat {
    fun line(ctx: Context, m: JSONObject, dark: Boolean, hostName: String, onUser: (JSONObject) -> Unit): View {
        val kind = m.optString("kind")
        val ink = if (dark) Color.WHITE else DK.INK
        val sub = if (dark) Ui.ca("#ffffff", 0.88f) else Ui.c("#4b4466")
        if (kind == "system") {
            return DK.t(ctx, m.optString("text"), 13f, if (dark) Ui.ca("#ffe6b4", 0.9f) else DK.PURPLE, false, 0).apply {
                typeface = Typeface.create(Ui.regular, Typeface.ITALIC); setPadding(Ui.dpi(10f), Ui.dpi(3f), Ui.dpi(10f), Ui.dpi(3f))
            }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(0, Ui.dpi(3f), 0, Ui.dpi(3f)) }
        val user = JSONObject().put("user_id", m.optString("user_id")).put("profile_id", m.optString("profile_id")).put("name", m.optString("name")).put("photo", m.optString("photo"))
        row.addView(AvatarView(ctx, 34f, if (dark) Ui.ca("#ffffff", 0.35f) else null, 1.7f).apply {
            set(m.optString("photo")); contentDescription = m.optString("name"); isClickable = true
            setOnClickListener { onUser(user) }
        }, DK.llp(DK.WRAP, DK.WRAP, r = 9f))
        val name = m.optString("name")
        when (kind) {
            "gift" -> {
                val g = LK.gift(m.optString("gift_id"))
                val sb = SpannableStringBuilder()
                val a = sb.length; sb.append(if (name.length > 14) name.take(13) + "…" else name)
                sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, 0); sb.setSpan(ForegroundColorSpan(ink), a, sb.length, 0)
                val b = sb.length; sb.append("  Sent ${g?.name ?: "a gift"} ")
                sb.setSpan(ForegroundColorSpan(if (dark) Ui.c("#ffd27a") else Ui.c("#b45309")), b, sb.length, 0); sb.setSpan(StyleSpan(Typeface.BOLD), b, sb.length, 0)
                val e = sb.length; sb.append(g?.e ?: "🎁"); sb.setSpan(android.text.style.RelativeSizeSpan(1.2f), e, sb.length, 0)
                if (m.optInt("count") > 1) { val c = sb.length; sb.append(" x${m.optInt("count")}"); sb.setSpan(ForegroundColorSpan(if (dark) Ui.c("#ffd27a") else Ui.c("#b45309")), c, sb.length, 0); sb.setSpan(StyleSpan(Typeface.BOLD), c, sb.length, 0) }
                val to = m.optString("to_name")
                if (to.isNotBlank() && to != hostName) { val t0 = sb.length; sb.append("  → $to"); sb.setSpan(ForegroundColorSpan(sub), t0, sb.length, 0); sb.setSpan(android.text.style.RelativeSizeSpan(0.88f), t0, sb.length, 0) }
                row.addView(DK.t(ctx, sb, 14f, ink, false, 0).apply {
                    background = if (dark) GradientDrawable().apply { setColor(Ui.ca("#220a3c", 0.6f)); cornerRadius = Ui.dp(99f); setStroke(Ui.dpi(1f), Ui.ca("#ffffff", 0.14f)) }
                        else GradientDrawable().apply { setColor(Ui.c("#fff7e6")); cornerRadius = Ui.dp(99f); setStroke(Ui.dpi(1f), Ui.c("#f7d58a")) }
                    setPadding(Ui.dpi(12f), Ui.dpi(6f), Ui.dpi(12f), Ui.dpi(6f))
                }, DK.llp(DK.WRAP, DK.WRAP))
            }
            "text" -> {
                val bubble = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = if (dark) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.ca("#ffaa28", 0.32f), Ui.ca("#be188c", 0.34f))).apply { cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), Ui.ca("#ffd678", 0.55f)) }
                        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#fff7e6"), Ui.c("#fdeefe"))).apply { cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), Ui.c("#f7d58a")) }
                    setPadding(Ui.dpi(11f), Ui.dpi(6f), Ui.dpi(11f), Ui.dpi(7f))
                }
                val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(DK.t(ctx, name, 14.5f, ink).apply { maxWidth = Ui.dpi(150f) }, DK.llp(DK.WRAP, DK.WRAP))
                val coins = m.optInt("coins")
                val chip = if (coins > 0) Ui.text(ctx, SpannableStringBuilder("SUPER CHAT  ").apply { setSpan(CoinSpan(), length - 1, length, 0); append("$coins") }, 11f, Ui.c("#7c2d12")).apply {
                    background = DK.pill(if (dark) Ui.ca("#ffd678", 0.9f) else Ui.c("#fde68a"))
                } else Ui.text(ctx, "HOST", 11f, Color.WHITE).apply { background = DK.grad(DK.GRAD, 99f) }
                chip.gravity = Gravity.CENTER; chip.setPadding(Ui.dpi(6f), 0, Ui.dpi(6f), 0)
                head.addView(chip, DK.llp(DK.WRAP, Ui.dpi(18f), l = 6f))
                bubble.addView(head)
                bubble.addView(DK.t(ctx, m.optString("text"), 14.5f, ink, false, 0).apply { setLineSpacing(0f, 1.15f) }, DK.llp(DK.WRAP, DK.WRAP, t = 2f))
                row.addView(bubble, DK.llp(DK.WRAP, DK.WRAP))
            }
            else -> {
                val sb = SpannableStringBuilder()
                sb.append(name); sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, 0); sb.setSpan(ForegroundColorSpan(ink), 0, sb.length, 0)
                sb.append(" ").append(LiveRoomPage.tagOf(m.optString("user_id"))).append(" ")
                val a = sb.length
                when (kind) {
                    "join" -> { sb.append("joined the room"); sb.setSpan(ForegroundColorSpan(if (dark) Ui.ca("#ffffff", 0.75f) else Ui.c("#7a7290")), a, sb.length, 0) }
                    "wish" -> { sb.append("wants to join 🙋"); sb.setSpan(ForegroundColorSpan(if (dark) Ui.c("#fde68a") else Ui.c("#b45309")), a, sb.length, 0); sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, 0) }
                    "cohost" -> { sb.append("joined as Co-Host 🎤"); sb.setSpan(ForegroundColorSpan(if (dark) Ui.c("#d8b4fe") else DK.PURPLE), a, sb.length, 0); sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, 0) }
                    else -> sb.append(m.optString("text"))
                }
                row.addView(DK.t(ctx, sb, 14.5f, sub, false, 0).apply {
                    if (dark) { background = GradientDrawable().apply { setColor(Ui.ca("#16082c", 0.38f)); cornerRadius = Ui.dp(16f) }; setPadding(Ui.dpi(11f), Ui.dpi(5f), Ui.dpi(11f), Ui.dpi(6f)) }
                    setLineSpacing(0f, 1.15f)
                }, DK.llp(DK.WRAP, DK.WRAP))
            }
        }
        return row
    }
}
