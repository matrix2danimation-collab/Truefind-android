package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Inside a dance live (DanceRoom in LiveDance.jsx): entry fee → watch, chat,
 * likes, gifts, votes, song requests, request to dance (and dancing on stage).
 * Asks the server like the web page: every 2 s, slower (up to 5 s) while
 * nothing happens, back to 2 s on any news or tap.
 */
@SuppressLint("ViewConstructor")
class DanceRoomPage(ctx: Context, private val screen: LiveDanceScreen, val roomId: String) : FrameLayout(ctx), DanceVideo.Listener {
    companion object {
        const val POLL_MS = 2000L
        const val POLL_MAX_MS = 5000L
        const val POLL_MAX_ON_STAGE_MS = 3000L
    }

    private val main = Handler(Looper.getMainLooper())

    // ── state (same names as the web page) ──
    var st: JSONObject? = null
        private set
    private var phase = "loading"          // loading | pay | live | ended | gone
    private var fatal = ""
    private val msgs = ArrayList<JSONObject>()
    private var balance: Long? = null
    private var followed = false
    var genres: List<String> = DK.GENRES
    var styles: List<String> = DK.STYLES
    var minutes: List<Int> = listOf(3, 5, 10, 15)
    private var blocked: Set<String> = emptySet()
    private var since: String? = null
    private var likeSince: String? = null
    private val seen = HashSet<String>()
    private var stSig = ""
    private var quiet = 0
    private var lastBattle: Pair<String, String>? = null
    private var giftTo = ""
    private var speakerOff = false
    private var offsetMs = 0L
    private var leaving = false
    private var closed = false
    private var active = true
    private var started = false
    private var loopBusy = false
    private var loopSig = ""
    private var video: DanceVideo? = null
    private var videoOn: Boolean? = null
    private val giftQ = ArrayList<JSONObject>()
    private var giftShowing = false

    private val loop = Runnable { pollRound() }
    private val clock = object : Runnable {
        override fun run() { updateTimer(); if (running()) main.postDelayed(this, 1000) }
    }

    // ── views ──
    private val content = FrameLayout(ctx)
    private var live: LiveViews? = null

    init {
        background = DK.pageBg()
        isClickable = true
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showLoading()
    }

    // ───────────────────────── server ─────────────────────────
    fun start() {
        if (started) return
        started = true
        DanceApi.call("join", JSONObject().put("room_id", roomId)) { r ->
            if (closed) return@call
            if (!r.ok) { fatal = r.text("Could not open this live."); setPhase("gone"); return@call }
            val d = r.data
            d.optJSONArray("blocked")?.let { a -> blocked = (0 until a.length()).map { a.optString(it) }.toSet() }
            followed = d.optBoolean("followed")
            d.optJSONArray("genres")?.let { a -> genres = (0 until a.length()).map { a.optString(it) } }
            d.optJSONArray("styles")?.let { a -> styles = (0 until a.length()).map { a.optString(it) } }
            d.optJSONArray("minutes")?.let { a -> minutes = (0 until a.length()).map { a.optInt(it) }.filter { it > 0 }.ifEmpty { minutes } }
            apply(d, true)
            if (d.optBoolean("ended")) return@call
            loopSig = liveSig(d)
            schedule(POLL_MS)
        }
    }

    /** the sign that tells if something changed (without the clock, chat, likes, coins) */
    private fun liveSig(d: JSONObject): String {
        val c = JSONObject(d.toString())
        c.remove("now"); c.remove("messages"); c.remove("likes"); c.remove("balance")
        return c.toString()
    }

    private fun schedule(ms: Long) {
        main.removeCallbacks(loop)
        if (!closed && active && phase != "ended" && phase != "gone") main.postDelayed(loop, ms)
    }

    private fun pollRound() {
        if (closed || loopBusy) return
        loopBusy = true
        poll { r ->
            loopBusy = false
            if (closed) return@poll
            if (r != null && !r.ok && (r.status == 403 || r.status == 404 || r.status == 410)) {
                fatal = r.text(); setPhase("gone"); return@poll
            }
            if (r != null && r.ok) {
                if (r.data.optBoolean("ended")) return@poll
                val sig = liveSig(r.data)
                val news = (r.data.optJSONArray("messages")?.length() ?: 0) > 0 || r.data.optInt("likes") > 0
                quiet = if (sig == loopSig && !news) quiet + 1 else 0
                loopSig = sig
            }
            val s = st
            val battleOn = running()
            val me = s?.optJSONObject("me")
            val onStageNow = me?.optString("role") == "host" || DK.arr(s?.optJSONArray("dancers")).any { it.optString("user_id") == me?.optString("user_id") }
            val wait = if (battleOn) POLL_MS else min(if (onStageNow) POLL_MAX_ON_STAGE_MS else POLL_MAX_MS, POLL_MS + quiet * 1000L)
            schedule(wait)
        }
    }

    private fun poll(done: ((DanceApi.Res?) -> Unit)? = null) {
        val body = JSONObject().put("room_id", roomId)
        since?.let { body.put("since", it) }
        likeSince?.let { body.put("like_since", it) }
        DanceApi.call("state", body) { r ->
            if (closed) return@call
            if (r.ok) apply(r.data, false)
            done?.invoke(r)
        }
    }

    private fun apply(d: JSONObject, first: Boolean) {
        val now = DK.ms(d.optString("now"))
        if (now > 0) offsetMs = now - System.currentTimeMillis()
        if (d.has("balance") && !d.isNull("balance")) balance = d.optLong("balance")
        val sig = run {
            val c = JSONObject(d.toString()); c.remove("now"); c.remove("messages"); c.remove("likes"); c.toString()
        }
        var changed = false
        if (first || sig != stSig) { stSig = sig; st = d; changed = true }
        if (d.optBoolean("ended")) { setPhase("ended"); return }
        if (d.optBoolean("need_pay")) { setPhase("pay"); return }
        val newPhase = phase != "live"
        setPhase("live")
        // chat: only lines not seen yet
        val fresh = DK.arr(d.optJSONArray("messages")).filter { !seen.contains(it.optString("id")) }
        fresh.forEach { seen.add(it.optString("id")) }
        if (fresh.isNotEmpty()) {
            since = fresh.last().optString("at")
            val myId = d.optJSONObject("me")?.optString("user_id")
            val show = fresh.filter { !blocked.contains(it.optString("user_id")) }
            if (show.isNotEmpty()) {
                val atEnd = live?.chatAtEnd() ?: true
                msgs.addAll(show)
                while (msgs.size > 150) msgs.removeAt(0)
                live?.chatChanged(atEnd)
            }
            if (!first) fresh.filter { it.optString("kind") == "gift" && it.optString("user_id") != myId }.forEach { queueGift(it) }
        } else if (since == null) since = d.optString("now")
        likeSince = d.optString("now")
        val likes = d.optInt("likes")
        if (likes > 0 && !first) live?.hearts?.add(likes)
        // a battle just finished → the winner
        val b = d.optJSONObject("room")?.optJSONObject("battle")
        val prev = lastBattle
        if (b != null && prev != null && prev.first == b.optString("id") && prev.second == "running" && b.optString("status") == "ended") live?.showWinner(b)
        lastBattle = if (b != null) b.optString("id") to b.optString("status") else null
        if (changed || newPhase) live?.update()
        live?.updateBalance()
        if (running()) { main.removeCallbacks(clock); main.post(clock) }
        syncVideo()
    }

    private fun setPhase(p: String) {
        if (p == phase && content.childCount > 0 && p != "live") return
        val was = phase
        phase = p
        when (p) {
            "ended", "gone" -> { main.removeCallbacks(loop); stopVideo(); showEnd() }
            "pay" -> if (was != "pay") showPay() else (content.getChildAt(0) as? PayView)?.update()
            "live" -> if (was != "live" || live == null) showLive()
        }
    }

    private fun act(action: String, body: JSONObject = JSONObject(), ok: String? = null, done: ((DanceApi.Res) -> Unit)? = null) {
        quiet = 0
        DanceApi.call(action, JSONObject(body.toString()).put("room_id", roomId)) { r ->
            if (closed) return@call
            if (r.ok) { if (ok != null) screen.showToast(ok); poll() } else screen.showToast(r.text())
            done?.invoke(r)
        }
    }

    // ───────────────────────── small getters ─────────────────────────
    private fun room(): JSONObject? = st?.optJSONObject("room")
    private fun me(): JSONObject? = st?.optJSONObject("me")
    private fun mode(): String = room()?.optString("mode")?.takeIf { DK.MODES.contains(it) } ?: "solo"
    private fun battle(): JSONObject? = room()?.optJSONObject("battle")
    private fun running(): Boolean = battle()?.optString("status") == "running"
    private fun isHost() = me()?.optString("role") == "host"
    private fun amDancer() = me()?.optString("role") == "dancer"
    private fun onStage() = isHost() || amDancer()
    private fun dancers(): List<JSONObject> = DK.arr(st?.optJSONArray("dancers"))
    private fun hostTile(): JSONObject = JSONObject(st?.optJSONObject("host")?.toString() ?: "{}").put("role", "host")
    private fun myId() = me()?.optString("user_id").orEmpty()

    /** people you can send a gift to (targets in the web page) */
    fun targets(): List<JSONObject> {
        val s = st ?: return emptyList()
        if (phase != "live") return emptyList()
        val h = s.optJSONObject("host") ?: return emptyList()
        val host = JSONObject().put("user_id", h.optString("user_id")).put("name", h.optString("name")).put("photo", h.optString("photo")).put("label", "Host")
        return when (mode()) {
            "solo" -> listOf(host)
            "battle" -> { val c = dancers().firstOrNull(); if (c != null) listOf(host, JSONObject(c.toString()).put("label", "Challenger")) else listOf(host) }
            else -> dancers().map { JSONObject(it.toString()).put("label", "Dancer ${it.optInt("seat")}") } + host
        }
    }
    /** the gift target: the dancer I chose / voted for, else side A, else the host */
    fun target(): JSONObject? {
        val t = targets(); val me = myId(); val vote = me()?.optString("vote").orEmpty()
        return t.firstOrNull { it.optString("user_id") == giftTo && it.optString("user_id") != me }
            ?: t.firstOrNull { vote.isNotEmpty() && it.optString("user_id") == vote && it.optString("user_id") != me }
            ?: t.firstOrNull { it.optString("user_id") != me }
    }
    fun pickTarget(uid: String) { giftTo = uid; live?.quick?.refresh() }

    // ───────────────────────── actions ─────────────────────────
    fun sendGift(giftId: String, count: Int, toId: String?, done: (Boolean) -> Unit) {
        val g = DK.gift(giftId)
        val to = if (toId != null) targets().firstOrNull { it.optString("user_id") == toId } else target()
        if (g == null || to == null) { done(false); return }
        val cost = g.price.toLong() * count
        val b = balance
        if (b != null && b < cost) { DanceSheets.coins(screen, cost, b); done(false); return }
        val body = JSONObject().put("room_id", roomId).put("to_user_id", to.optString("user_id")).put("gift_id", giftId).put("count", count).put("client_id", DK.newId())
        DanceApi.call("gift", body) { r ->
            if (closed) return@call
            if (r.ok) {
                if (r.data.has("balance")) { balance = r.data.optLong("balance"); live?.updateBalance() }
                queueGift(JSONObject().put("id", DK.newId()).put("gift_id", giftId).put("count", count).put("name", "You").put("to_name", to.optString("name")))
                poll()
                done(true)
            } else {
                if (r.noCoins) { if (r.data.has("balance")) balance = r.data.optLong("balance"); live?.updateBalance(); DanceSheets.coins(screen, cost, balance ?: 0L) } else screen.showToast(r.text())
                done(false)
            }
        }
    }

    private fun vote(uid: String) {
        val m = me() ?: return
        if (!running() || m.optString("vote").isNotEmpty()) return
        m.put("vote", uid); live?.update()
        DanceApi.call("vote", JSONObject().put("room_id", roomId).put("for_user_id", uid)) { r ->
            if (closed) return@call
            if (r.ok) { screen.showToast("Your vote is in ✅"); poll() } else { me()?.put("vote", ""); live?.update(); screen.showToast(r.text()) }
        }
    }

    private fun like() {
        live?.hearts?.add(3)
        DanceApi.call("like", JSONObject().put("room_id", roomId)) { }
    }

    fun shareLive() {
        val url = "${Api.origin.trimEnd('/')}/dance/$roomId"
        screen.nav.share(room()?.optString("title")?.takeIf { it.isNotBlank() } ?: "Live Dance", "Join my Live Dance on True Find 💃 $url")
    }

    fun follow() {
        DanceApi.call("follow", JSONObject().put("room_id", roomId).put("on", !followed)) { r ->
            if (closed) return@call
            if (r.ok) { followed = r.data.optBoolean("followed"); screen.showToast(if (followed) "Following 💜" else "Unfollowed"); (content.getChildAt(0) as? EndView)?.refresh() }
            else screen.showToast(r.text())
        }
    }
    fun isFollowed() = followed

    fun copyId() {
        val code = room()?.optString("code").orEmpty()
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Room ID", code))
            screen.showToast("Room ID copied")
        } catch (e: Exception) { }
    }

    fun requestSong(genre: String, style: String, song: String, done: (Boolean) -> Unit) =
        act("song_request", JSONObject().put("genre", genre).put("style", style).put("song", song), "Request sent to the host 🎵") { done(it.ok) }

    fun myRequest(): JSONObject? = me()?.optJSONObject("request")
    fun feeOf(): Int = st?.optInt("fee") ?: 0
    fun roomJson(): JSONObject? = room()
    fun balanceOf(): Long? = balance
    fun hostMode(): Boolean = isHost()
    fun meJson(): JSONObject? = me()
    fun openWallet() = screen.nav.go("/wallet")

    // ── for the host's sheets ──
    fun refreshNow() { quiet = 0; poll() }
    fun pendingOf(kind: String): Int = st?.optJSONObject("pending")?.optInt(kind) ?: 0
    fun seatsFree(): Int = (st?.optInt("seats") ?: 0) - dancers().size
    fun isRunning(): Boolean = running()
    fun isDemoRoom(): Boolean = room()?.optBoolean("demo") == true
    /** the two sides of a battle (host + challenger, or dancer 1 + dancer 2) */
    fun stageSides(): List<JSONObject?> {
        val d = dancers()
        return when (mode()) {
            "battle" -> listOf(hostTile(), d.firstOrNull())
            "duo" -> listOf(d.firstOrNull { it.optInt("seat") == 1 }, d.firstOrNull { it.optInt("seat") == 2 })
            else -> emptyList()
        }
    }
    fun startBattle(m: Int, done: (Boolean) -> Unit) =
        act("battle_start", JSONObject().put("minutes", m), "Battle started ⚔️") { done(it.ok) }
    private fun confirmStop() =
        DanceSheets.confirm(screen, "Stop this battle?", "The battle ends now without a winner. You can start a new one any time.", "Stop battle") { act("battle_stop", ok = "Battle stopped") }
    private fun confirmEnd() =
        DanceSheets.confirm(screen, "End your live?", "Everyone will leave the room. Gifts and entry fees you earned go to your Wallet.", "End live") {
            leaving = true
            // ended anyway if this fails (the server closes a live whose host is gone)
            DanceApi.call("end", JSONObject().put("room_id", roomId)) { screen.nav.replace("/dance") }
        }

    fun personAct(action: String, u: JSONObject, ok: String) {
        val body = JSONObject().put("user_id", u.optString("user_id"))
        if (action == "mute") body.put("muted", !u.optBoolean("host_muted"))
        act(action, body, ok)
    }

    private fun goBack() { if (isHost() && phase == "live") confirmEnd() else screen.nav.back() }

    // ───────────────────────── video ─────────────────────────
    private fun syncVideo() {
        if (phase != "live" || closed) return
        val v = video ?: DanceVideo(context, roomId, this).also { video = it; it.start() }
        val m = me()
        v.setPublisher(onStage())
        v.setMuted(m?.optBoolean("muted") == true)
        v.setCamOff(m?.optBoolean("cam_off") == true)
        v.setSpeakerOff(speakerOff)
        v.setPaused(!active)
    }
    private fun stopVideo() { video?.stop(); video = null; videoOn = null }

    override fun onVideoEnabled(on: Boolean) { videoOn = on; live?.stage?.refreshVideo() }
    override fun onRemoteVideo(uid: Int, on: Boolean) { live?.stage?.refreshVideo() }
    override fun onVideoError(msg: String) { screen.showToast(msg) }
    override fun askCameraMic(done: (Boolean) -> Unit) = screen.nav.askCameraMic(done)

    // ───────────────────────── life ─────────────────────────
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        video?.setPaused(!on)
        if (on && started && !closed) { quiet = 0; main.removeCallbacks(loop); pollRound() } else main.removeCallbacks(loop)
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        stopVideo()
        val s = st
        if (!leaving && s != null && !s.optBoolean("ended") && !s.optBoolean("need_pay") && s.optJSONObject("me")?.optString("role") != "host")
            DanceApi.call("leave", JSONObject().put("room_id", roomId)) { }
    }

    /** phone back inside the room: closes the emoji panel / the winner first; the host is asked before the live ends */
    fun onBack(): Boolean {
        if (live?.onBack() == true) return true
        if (isHost() && phase == "live") { confirmEnd(); return true }
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
        live?.showGiftBurst(g)
        main.postDelayed({ nextGift() }, 2800)
    }

    // ───────────────────────── screens ─────────────────────────
    private fun swap(v: View) {
        content.removeAllViews()
        content.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showLoading() {
        val f = FrameLayout(context)
        f.addView(DotsSpinner(context), DK.flp(Ui.dpi(34f), Ui.dpi(34f), Gravity.CENTER))
        swap(f)
    }

    private fun showEnd() { live = null; swap(EndView()) }
    private fun showPay() { live = null; swap(PayView()) }
    private fun showLive() {
        val l = LiveViews()
        live = l
        swap(l.root)
        l.chatChanged(true)
        l.update()
    }

    /** a top bar with the logo and coins (TopHeader) */
    private fun header(onBack: () -> Unit): Pair<LinearLayout, TextView> {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dpi(4f), 0, Ui.dpi(10f), 0)
            setBackgroundColor(Ui.ca("#ffffff", 0.9f))
        }
        top.addView(DK.iconButton(context, DIcon(DIcon.CHEVRON_LEFT, DK.INK, 28f), 44f, null, "Back").apply { setOnClickListener { onBack() } })
        top.addView(DK.logo(context), DK.llp(0, DK.WRAP, 1f))
        val (pill, coins) = DK.coinPill(context)
        pill.contentDescription = "Coins. Add coins"
        pill.setOnClickListener { openWallet() }
        top.addView(pill, DK.llp(DK.WRAP, Ui.dpi(34f)))
        coins.text = balance?.let { DK.n(it) } ?: "…"
        return top to coins
    }

    // ───────── ended / can't open ─────────
    private inner class EndView : LinearLayout(context) {
        private val followBtn: TextView?
        init {
            orientation = VERTICAL
            val (top, _) = header { screen.nav.replace("/dance") }
            addView(top, DK.llp(DK.MATCH, Ui.dpi(56f)))
            addView(View(context).apply { setBackgroundColor(DK.LINE) }, DK.llp(DK.MATCH, Ui.dpi(1f)))
            val box = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f)) }
            val r = room()
            if (r?.optJSONObject("host") != null) box.addView(AvatarView(context, 92f, Ui.c("#e9d5ff")).apply { set(r.optJSONObject("host")?.optString("photo")) })
            val ended = phase == "ended"
            box.addView(DK.t(context, if (ended) "This live has ended" else "Can't open this live", 22f, DK.INK, true, 0).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 16f, b = 6f))
            box.addView(DK.t(context, if (ended) "${r?.optJSONObject("host")?.optString("name")?.takeIf { it.isNotBlank() } ?: "The host"} has finished dancing. Follow to see the next live." else fatal, 15f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; maxWidth = Ui.dpi(300f) })
            followBtn = if (ended && r != null && !isHost()) DK.button(context, "", null).apply { setOnClickListener { follow() } } else null
            followBtn?.let { box.addView(it, DK.llp(DK.WRAP, Ui.dpi(50f), t = 18f)) }
            box.addView(DK.lineButton(context, "Back to Live Dance").apply { setOnClickListener { screen.nav.replace("/dance") } }, DK.llp(DK.WRAP, Ui.dpi(50f), t = 12f))
            addView(box, DK.llp(DK.MATCH, 0, 1f))
            refresh()
        }
        fun refresh() {
            val b = followBtn ?: return
            if (followed) {
                b.text = "Following"; b.setTextColor(DK.PURPLE); b.background = DK.pill(Color.WHITE, DK.PURPLE, 1.5f); b.elevation = 0f
                b.setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.CHECK, DK.PURPLE, 18f), null, null, null)
            } else {
                b.text = "Follow"; b.setTextColor(Color.WHITE); b.background = DK.grad(DK.GRAD, 25f)
                b.setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.PLUS, Color.WHITE, 18f), null, null, null)
            }
            b.compoundDrawablePadding = Ui.dpi(8f)
            b.setPadding(Ui.dpi(28f), 0, Ui.dpi(28f), 0)
        }
    }

    // ───────── entry fee (PayScreen) ─────────
    private inner class PayView : FrameLayout(context) {
        private val coins: TextView
        private val errText: TextView
        private val payBtn: TextView
        private val spin = DotsSpinner(context, Color.WHITE)
        private var busy = false
        private var cid = DK.newId()
        private val cover: FrameLayout

        init {
            val s = st!!
            val r = s.optJSONObject("room") ?: JSONObject()
            val mode = r.optString("mode").takeIf { DK.MODES.contains(it) } ?: "solo"
            val fee = s.optInt("fee")
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val (top, c) = header { goBack() }
            coins = c
            col.addView(top, DK.llp(DK.MATCH, Ui.dpi(56f)))
            col.addView(View(context).apply { setBackgroundColor(DK.LINE) }, DK.llp(DK.MATCH, Ui.dpi(1f)))
            val sv = ScrollView(context).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(112f)) }
            sv.addView(body)
            col.addView(sv, DK.llp(DK.MATCH, 0, 1f))
            addView(col)

            // the preview card
            cover = FrameLayout(context).apply { background = DK.stageBg(); elevation = Ui.dp(6f) }
            DK.roundClip(cover, 26f)
            val vh = resources.displayMetrics.heightPixels / Ui.density
            val ch = (vh * 0.52f).coerceIn(300f, 460f)
            body.addView(cover, DK.llp(DK.MATCH, Ui.dpi(ch)))
            val dancers = DK.arr(s.optJSONArray("dancers"))
            val hostO = s.optJSONObject("host") ?: r.optJSONObject("host") ?: JSONObject()
            if (mode == "solo") {
                cover.addView(CoverView(context).apply { blur = 3f; dim = 0.85f; zoom = 1.06f; set(r.optJSONObject("host")?.optString("photo")) }, DK.flp())
            } else {
                val pair = if (mode == "battle") listOf(hostO, dancers.firstOrNull()) else listOf(dancers.firstOrNull { it.optInt("seat") == 1 }, dancers.firstOrNull { it.optInt("seat") == 2 })
                val lf = SlantFrame(context, true).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#5b21b6"), Ui.c("#2a0b55"))) }
                val rf = SlantFrame(context, false).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#9d174d"), Ui.c("#3b0f5a"))) }
                lf.addView(CoverView(context).apply { blur = 3f; dim = 0.85f; set(pair[0]?.optString("photo")) }, DK.flp())
                rf.addView(CoverView(context).apply { blur = 3f; dim = 0.85f; set(pair[1]?.optString("photo")) }, DK.flp())
                cover.addView(object : FrameLayout(context) {
                    override fun onLayout(changed: Boolean, l: Int, t: Int, rr: Int, b: Int) {
                        val w = rr - l; val h = b - t
                        val half = (w * 0.53f).toInt()
                        lf.layout(0, 0, half, h); rf.layout(w - half, 0, w, h)
                    }
                    override fun onMeasure(ws: Int, hs: Int) {
                        val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs); val half = (w * 0.53f).toInt()
                        lf.measure(MeasureSpec.makeMeasureSpec(half, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                        rf.measure(MeasureSpec.makeMeasureSpec(half, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                        setMeasuredDimension(w, h)
                    }
                }.apply { addView(lf); addView(rf) }, DK.flp())
                val vs = Ui.text(context, "VS", 48f, DK.GOLD_TEXT).apply { typeface = DK.italic(); setShadowLayer(Ui.dp(9f), 0f, Ui.dp(3f), Ui.c("#b45309")) }
                cover.addView(vs, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP, t = ch * 0.34f))
                vs.vsWobble()
            }
            cover.addView(ShadeView(context, intArrayOf(Ui.ca("#140528", 0.35f), Color.TRANSPARENT, Color.TRANSPARENT, Ui.ca("#140528", 0.9f)), floatArrayOf(0f, 0.25f, 0.45f, 1f)), DK.flp())
            val topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val (lp, v) = DK.livePill(context); v.text = DK.c(r.optLong("viewers"))
            topRow.addView(lp); topRow.addView(View(context), DK.llp(0, 1, 1f))
            if (r.optBoolean("demo")) topRow.addView(DK.demoTag(context), DK.llp(DK.WRAP, Ui.dpi(20f), r = 6f))
            topRow.addView(DK.tag(context, "👑 Host", true))
            cover.addView(topRow, DK.flp(DK.MATCH, DK.WRAP, Gravity.TOP, 12f, 12f, 12f))
            val b = r.optJSONObject("battle")
            if (b?.optString("status") == "running") cover.addView(Ui.text(context, "Battle ${b.optInt("no")} is on now", 12.5f, Color.WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL; background = DK.pill(Ui.ca("#ff3b5c", 0.92f)); setPadding(Ui.dpi(11f), Ui.dpi(4f), Ui.dpi(11f), Ui.dpi(4f))
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.SWORDS, Color.WHITE, 14f), null, null, null); compoundDrawablePadding = Ui.dpi(5f)
            }, DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 56f))
            val hostRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val avBox = FrameLayout(context).apply { clipChildren = false }
            avBox.addView(AvatarView(context, 54f, DK.GOLD_TEXT).apply { set(r.optJSONObject("host")?.optString("photo")) }, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER, t = 8f))
            avBox.addView(Ui.text(context, "👑", 17f, Color.WHITE, false), DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = -6f))
            hostRow.addView(avBox)
            val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(10f), 0, 0, 0) }
            names.addView(nameWithBadge(r.optJSONObject("host")?.optString("name").orEmpty(), 18f, 16f), DK.llp(DK.WRAP, DK.WRAP))
            names.addView(DK.t(context, r.optString("title"), 13.5f, Ui.ca("#ffffff", 0.88f), false))
            hostRow.addView(names, DK.llp(DK.WRAP, DK.WRAP))
            cover.addView(hostRow, DK.flp(DK.WRAP, DK.WRAP, Gravity.BOTTOM or Gravity.START, l = 14f, b = 82f))
            val banner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.ca("#3c0f6e", 0.92f), Ui.ca("#6e1eaa", 0.92f))).apply { cornerRadius = Ui.dp(18f); setStroke(Ui.dpi(1.5f), Ui.ca("#ffce66", 0.85f)) }
                setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
            }
            banner.addView(Ui.text(context, DK.mode(mode).banner, 21f, Ui.c("#ffd98a")).apply { typeface = DK.italic(); gravity = Gravity.CENTER })
            banner.addView(Ui.text(context, DK.mode(mode).line, 12.5f, Ui.ca("#ffffff", 0.95f), false).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(2f), 0, 0) })
            cover.addView(banner, DK.flp(DK.MATCH, DK.WRAP, Gravity.BOTTOM, 12f, 0f, 12f, 14f))

            // 4 small cards
            val feats = if (mode == "solo") listOf(Triple(DIcon.TICKET, "Entry Fee", "$fee Coins"), Triple(DIcon.GIFT, "Send Gifts", "Support Host"), Triple(DIcon.MUSIC, "Song Request", "Pick the vibe"), Triple(DIcon.SHIELD_CHECK, "Safe Room", "Host can block"))
            else listOf(Triple(DIcon.TICKET, "Entry Fee", "$fee Coins"), Triple(DIcon.CHECK, "Free Vote", "1 vote per battle"), Triple(DIcon.GIFT, "Send Gifts", "Support dancers"), Triple(DIcon.TROPHY, "Winner", "By votes only"))
            val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            feats.forEachIndexed { i, (ic, t, sub) ->
                val gold = i == 0
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    background = if (gold) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#fffaf0"), Color.WHITE)).apply { cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), Ui.c("#f7d58a")) } else DK.card(16f)
                    elevation = Ui.dp(2f)
                    setPadding(Ui.dpi(4f), Ui.dpi(10f), Ui.dpi(4f), Ui.dpi(10f))
                }
                cell.addView(ImageView(context).apply { setImageDrawable(DIcon(ic, if (gold) Ui.c("#d97706") else DK.PURPLE, 22f)) })
                cell.addView(Ui.text(context, t, 12.5f, DK.INK).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(4f), 0, 0) })
                cell.addView(Ui.text(context, sub, 11f, if (gold) Ui.c("#92400e") else DK.SUB, gold).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(2f), 0, 0) })
                grid.addView(cell, DK.llp(0, DK.WRAP, 1f, l = if (i == 0) 0f else 3.5f, r = if (i == 3) 0f else 3.5f))
            }
            body.addView(grid, DK.llp(t = 12f))
            // how it works
            val how = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; danceCard(); setPadding(Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(10f)) }
            how.addView(Ui.text(context, "How it works?", 14f, DK.PURPLE).apply { gravity = Gravity.CENTER }, DK.llp(b = 8f))
            val steps = if (mode == "solo") listOf("🎟️" to "Pay Entry Fee", "👀" to "Join & Watch", "🎁" to "Send Gifts", "💬" to "Chat with Host", "👑" to "Host Earns")
            else listOf("🎟️" to "Pay Entry", "⚔️" to "Watch Battle", "✅" to "Vote Free", "🎁" to "Gift Dancers", "🏆" to "Winner")
            how.addView(DK.steps(context, steps, 11f, 20f))
            body.addView(how, DK.llp(t = 12f))
            body.addView(DK.t(context, "One-time entry for this live. Chat and voting are free. No other charges.", 12.5f, DK.SUB, false, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, DK.llp(l = 4f, t = 10f, r = 4f))

            // the pay button (fixed at the bottom)
            val bottom = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.ca("#fdfbff", 0f), Ui.c("#fdfbff"), Ui.c("#fdfbff")))
                setPadding(Ui.dpi(14f), Ui.dpi(10f), Ui.dpi(14f), Ui.dpi(12f))
            }
            errText = DK.t(context, "", 14f, DK.ERR, true, 0).apply { gravity = Gravity.CENTER; visibility = View.GONE }
            bottom.addView(errText, DK.llp(b = 8f))
            val btnBox = FrameLayout(context)
            payBtn = DK.button(context, payLabel(fee), DIcon(DIcon.PLAY, Color.WHITE, 20f, Color.WHITE), 58f, 18f).apply {
                background = LayerDrawableCompat.goldRing(DK.grad(DK.GRAD, 29f))
                contentDescription = "Join Live Room for $fee coins"
                setOnClickListener { pay(fee) }
            }
            btnBox.addView(payBtn, DK.flp(DK.MATCH, Ui.dpi(58f)))
            spin.visibility = View.GONE; spin.elevation = Ui.dp(8f)
            btnBox.addView(spin, DK.flp(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER))
            bottom.addView(btnBox)
            addView(bottom, DK.flp(DK.MATCH, DK.WRAP, Gravity.BOTTOM))
            update()
        }

        private fun payLabel(fee: Int): CharSequence {
            val sb = SpannableStringBuilder("Join Live Room ·  ")
            val at = sb.length
            sb.append(" ").append(fee.toString())
            sb.setSpan(CoinSpan(), at - 1, at, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }

        fun update() { coins.text = balance?.let { DK.n(it) } ?: "…" }

        private fun pay(fee: Int) {
            if (busy) return
            val b = balance
            if (b != null && b < fee) { showErr("You need $fee coins. You have ${DK.n(b)}.", true); return }
            busy = true; errText.visibility = View.GONE
            payBtn.text = ""; payBtn.setCompoundDrawables(null, null, null, null); spin.visibility = View.VISIBLE
            DanceApi.call("pay", JSONObject().put("room_id", roomId).put("client_id", cid)) { r ->
                if (closed) return@call
                busy = false; spin.visibility = View.GONE
                payBtn.text = payLabel(fee); payBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.PLAY, Color.WHITE, 20f, Color.WHITE), null, null, null)
                if (r.ok) {
                    screen.showToast("Welcome! Enjoy the dance 💃")
                    if (r.data.has("balance")) balance = r.data.optLong("balance")
                    poll()
                } else {
                    cid = DK.newId()
                    if (r.noCoins) showErr("You need $fee coins. You have ${DK.n(r.data.optLong("balance"))}.", true) else showErr(r.text(), false)
                }
            }
        }

        private fun showErr(s: String, coinsLink: Boolean) {
            if (coinsLink) {
                val sb = SpannableStringBuilder(s).append("  Add coins")
                sb.setSpan(ForegroundColorSpan(DK.PURPLE), s.length + 2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                errText.text = sb
                errText.setOnClickListener { openWallet() }
            } else { errText.text = s; errText.setOnClickListener(null) }
            errText.visibility = View.VISIBLE
        }
    }

    /** name + the purple verified mark */
    private fun nameWithBadge(name: String, sizeDp: Float, badgeDp: Float, color: Int = Color.WHITE, shadow: Boolean = true): TextView =
        DK.t(context, name, sizeDp, color).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, VerifiedBadge(badgeDp), null)
            compoundDrawablePadding = Ui.dpi(4f)
            if (shadow) setShadowLayer(Ui.dp(4f), 0f, Ui.dp(1f), Ui.ca("#000000", 0.6f))
        }

    // ════════════════════════ LIVE ════════════════════════
    private inner class LiveViews {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val infoTitle: TextView
        private val infoSub: TextView
        private val infoDemo: View
        private val infoIcon = FrameLayout(context)
        private val coins: TextView
        val stage = StageView()
        private val voteBox = FrameLayout(context)
        private val supBox = LinearLayout(context)
        private val chat = object : RecyclerView(context) { override fun getBottomFadingEdgeStrength() = 0f } // fade only at the top
        private val chatAdapter = ChatAdapter()
        private val side = LinearLayout(context)
        private val emojiPanel = DanceFlowRow(context)
        private val input: EditText
        private val sendBtn: FrameLayout
        private val toolRow = LinearLayout(context)
        val quick = QuickGifts()
        private val giftBtn: FrameLayout
        val hearts get() = stage.hearts
        private var toolsSig = ""
        private var sideSig = ""

        init {
            // ── top bar ──
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dpi(2f), 0, Ui.dpi(8f), 0); setBackgroundColor(Ui.ca("#ffffff", 0.92f))
            }
            top.addView(DK.iconButton(context, DIcon(DIcon.CHEVRON_LEFT, DK.INK, 27f), 42f, null, "Back").apply { setOnClickListener { goBack() } })
            val info = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = DK.pill(Color.WHITE, DK.LINE)
                setPadding(Ui.dpi(6f), 0, Ui.dpi(10f), 0)
                contentDescription = "Room info"
                isClickable = true
                setOnClickListener { DanceSheets.info(screen, this@DanceRoomPage) }
                Ui.pressable(this, 0.98f)
            }
            infoIcon.background = DK.grad(DK.GRAD, 15f)
            info.addView(infoIcon, DK.llp(Ui.dpi(30f), Ui.dpi(30f)))
            val tcol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(8f), 0, Ui.dpi(6f), 0) }
            infoTitle = DK.t(context, "", 14f, DK.INK)
            infoSub = DK.t(context, "", 11.5f, DK.SUB, false)
            tcol.addView(infoTitle); tcol.addView(infoSub)
            info.addView(tcol, DK.llp(0, DK.WRAP, 1f))
            infoDemo = DK.demoTag(context)
            info.addView(infoDemo)
            top.addView(info, DK.llp(0, Ui.dpi(42f), 1f, r = 6f))
            val (pill, c) = DK.coinPill(context)
            coins = c
            pill.contentDescription = "Coins. Add coins"
            pill.setOnClickListener { openWallet() }
            top.addView(pill, DK.llp(DK.WRAP, Ui.dpi(34f)))
            root.addView(top, DK.llp(DK.MATCH, Ui.dpi(54f)))
            root.addView(View(context).apply { setBackgroundColor(DK.LINE) }, DK.llp(DK.MATCH, Ui.dpi(1f)))

            // ── stage ──
            root.addView(stage.box, DK.llp(DK.MATCH, Ui.dpi(300f), l = 8f, t = 8f, r = 8f))
            // ── votes (battles) + top supporters ──
            root.addView(voteBox, DK.llp(DK.MATCH, DK.WRAP))
            supBox.orientation = LinearLayout.HORIZONTAL; supBox.gravity = Gravity.CENTER_VERTICAL
            supBox.background = DK.card(26f); supBox.elevation = Ui.dp(2f)
            supBox.setPadding(Ui.dpi(5f), Ui.dpi(5f), Ui.dpi(6f), Ui.dpi(5f))
            root.addView(supBox, DK.llp(DK.MATCH, DK.WRAP, l = 8f, t = 8f, r = 8f))

            // ── chat + side menu ──
            val mid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dpi(8f), Ui.dpi(4f), Ui.dpi(8f), 0) }
            chat.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
            chat.adapter = chatAdapter
            chat.itemAnimator = null
            chat.isVerticalFadingEdgeEnabled = true
            chat.setFadingEdgeLength(Ui.dpi(18f))
            chat.overScrollMode = View.OVER_SCROLL_NEVER
            chat.setPadding(Ui.dpi(2f), Ui.dpi(8f), Ui.dpi(2f), Ui.dpi(4f))
            chat.clipToPadding = false
            mid.addView(chat, DK.llp(0, DK.MATCH, 1f))
            val sideScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            side.orientation = LinearLayout.VERTICAL
            side.setPadding(0, Ui.dpi(6f), 0, Ui.dpi(6f))
            sideScroll.addView(side)
            mid.addView(sideScroll, DK.llp(Ui.dpi(118f), DK.MATCH, l = 8f))
            root.addView(mid, DK.llp(DK.MATCH, 0, 1f))

            // ── bottom: emoji, message, gifts ──
            val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(8f), Ui.dpi(6f), Ui.dpi(8f), Ui.dpi(8f)) }
            emojiPanel.danceCard(16f)
            emojiPanel.setPadding(Ui.dpi(8f), Ui.dpi(8f), Ui.dpi(8f), Ui.dpi(8f))
            emojiPanel.visibility = View.GONE
            bottom.addView(emojiPanel, DK.llp(b = 6f))
            val inRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val field = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = DK.pill(Color.WHITE, DK.LINE); elevation = Ui.dp(2f)
                setPadding(Ui.dpi(14f), 0, 0, 0)
            }
            input = EditText(context).apply {
                hint = "Type a message…"; setHintTextColor(Ui.c("#9b93b3")); setTextColor(DK.INK)
                typeface = Ui.regular; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f))
                background = null; setPadding(0, 0, 0, 0); isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEND
                filters = arrayOf(InputFilter.LengthFilter(200))
                contentDescription = "Message"
                setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false }
            }
            field.addView(input, DK.llp(0, DK.MATCH, 1f))
            field.addView(DK.iconButton(context, DIcon(DIcon.SMILE, DK.PURPLE, 22f), 40f, null, "Emojis").apply {
                setOnClickListener { emojiPanel.visibility = if (emojiPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            })
            inRow.addView(field, DK.llp(0, Ui.dpi(46f), 1f))
            sendBtn = DK.iconButton(context, DIcon(DIcon.SEND, Color.WHITE, 19f), 46f, DK.oval(Ui.c("#e9e1f7")), "Send").apply { setOnClickListener { send() } }
            inRow.addView(sendBtn, DK.llp(Ui.dpi(46f), Ui.dpi(46f), l = 6f))
            bottom.addView(inRow)
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    sendBtn.background = if (s.isNullOrBlank()) DK.oval(Ui.c("#e9e1f7")) else GradientDrawable(GradientDrawable.Orientation.TL_BR, DK.GRAD).apply { shape = GradientDrawable.OVAL }
                }
            })
            DK.EMOJIS.forEach { e ->
                emojiPanel.addView(Ui.text(context, e, 21f, DK.INK, false).apply {
                    gravity = Gravity.CENTER; contentDescription = "Add $e"
                    isClickable = true; background = null
                    setOnClickListener { val t = input.text.toString(); if (t.length < 199) { input.setText(t + e); input.setSelection(input.text.length) } }
                }, ViewGroup.MarginLayoutParams(Ui.dpi(36f), Ui.dpi(36f)).apply { setMargins(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f)) })
            }
            toolRow.orientation = LinearLayout.HORIZONTAL; toolRow.gravity = Gravity.CENTER_VERTICAL
            toolRow.clipChildren = false; bottom.clipChildren = false
            bottom.addView(toolRow, DK.llp(t = 7f))
            giftBtn = FrameLayout(context).apply {
                contentDescription = "All gifts"; isClickable = true; clipChildren = false
                setOnClickListener { DanceSheets.gifts(screen, this@DanceRoomPage) }
                Ui.pressable(this, 0.95f)
            }
            giftBtn.addView(GlowRing(context), DK.flp(Ui.dpi(78f), Ui.dpi(78f), Gravity.CENTER))
            giftBtn.addView(Ui.text(context, "🎁", 28f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = Ui.dp(40f)
                    colors = intArrayOf(Ui.c("#c77dff"), Ui.c("#7a1fe4"), Ui.c("#4c0fa0")); setGradientCenter(0.35f, 0.3f)
                }
            }, DK.flp(Ui.dpi(58f), Ui.dpi(58f), Gravity.CENTER))
            root.addView(bottom, DK.llp(DK.MATCH, DK.WRAP))
            root.clipChildren = false
        }

        fun onBack(): Boolean {
            if (stage.closeWinner()) return true
            if (emojiPanel.visibility == View.VISIBLE) { emojiPanel.visibility = View.GONE; return true }
            return false
        }

        fun updateBalance() { coins.text = balance?.let { DK.n(it) } ?: "…" }

        fun update() {
            val s = st ?: return
            val r = s.optJSONObject("room") ?: return
            val mode = mode()
            infoTitle.text = r.optString("title")
            infoSub.text = "${DK.mode(mode).label} · ID ${r.optString("code")}"
            infoDemo.visibility = if (r.optBoolean("demo")) View.VISIBLE else View.GONE
            infoIcon.removeAllViews(); infoIcon.addView(DK.modeIcon(context, mode, 16f, Color.WHITE), DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER))
            updateBalance()
            // stage height: solo clamp(300, 50vh, 470) · battles clamp(250, 41vh, 380)
            val vh = (if (this@DanceRoomPage.height > 0) this@DanceRoomPage.height else resources.displayMetrics.heightPixels) / Ui.density
            val sh = if (mode == "solo") (vh * 0.5f).coerceIn(300f, 470f) else (vh * 0.41f).coerceIn(250f, 380f)
            val lp = stage.box.layoutParams as LinearLayout.LayoutParams
            if (lp.height != Ui.dpi(sh)) { lp.height = Ui.dpi(sh); stage.box.layoutParams = lp }
            stage.update()
            updateVotes()
            updateSupporters(DK.arr(r.optJSONArray("top")))
            updateSide()
            updateTools()
        }

        // ── votes row under the stage (battles) ──
        private var voteSig = ""
        private fun updateVotes() {
            val mode = mode()
            if (mode == "solo") { voteBox.visibility = View.GONE; return }
            voteBox.visibility = View.VISIBLE
            val b = battle(); val m = me()
            val sig = listOf(b?.optString("id"), b?.optString("status"), b?.optInt("votes_a"), b?.optInt("votes_b"), b?.optString("winner"), m?.optString("vote"), m?.optString("role"), dancers().joinToString(",") { "${it.optString("user_id")}:${it.optInt("seat")}" }).joinToString("|")
            if (sig == voteSig) return
            voteSig = sig
            voteBox.removeAllViews()
            val running = running()
            val voted = m?.optString("vote").orEmpty()
            if (running && !isHost() && !amDancer() && b != null) {
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(8f), Ui.dpi(8f), Ui.dpi(8f), 0) }
                col.addView(Ui.text(context, if (voted.isNotEmpty()) "👑 Your vote is in — thanks! 👑" else "👑 Vote for your favourite dancer (free) 👑", 13f, DK.STEP_TEXT).apply { gravity = Gravity.CENTER }, DK.llp(b = 6f))
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                listOf(b.optJSONObject("a"), b.optJSONObject("b")).forEachIndexed { i, p ->
                    val uid = p?.optString("user_id").orEmpty()
                    val mine = voted.isNotEmpty() && voted == uid
                    val other = voted.isNotEmpty() && !mine
                    val btn = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (i == 0) DK.BAR_A else DK.BAR_B).apply {
                            cornerRadius = Ui.dp(23f); if (mine) setStroke(Ui.dpi(3f), DK.GOLD_TEXT)
                        }
                        setPadding(Ui.dpi(4f), 0, Ui.dpi(10f), 0)
                        alpha = if (other) 0.45f else 1f
                        elevation = Ui.dp(3f)
                        contentDescription = "${if (mine) "Voted" else "Vote"} ${p?.optString("name").orEmpty()}"
                        isClickable = voted.isEmpty()
                        if (voted.isEmpty()) { setOnClickListener { if (uid.isNotEmpty()) vote(uid) }; Ui.pressable(this, 0.97f) }
                    }
                    btn.addView(AvatarView(context, 38f, Ui.ca("#ffffff", 0.8f), 1.5f).apply { set(p?.optString("photo")) })
                    btn.addView(DK.t(context, "${if (mine) "Voted" else "Vote"} ${DK.first(p?.optString("name"))}", 14f, Color.WHITE), DK.llp(0, DK.WRAP, 1f, l = 7f))
                    if (mine) btn.addView(ImageView(context).apply { setImageDrawable(DIcon(DIcon.CHECK, Color.WHITE, 18f)) })
                    row.addView(btn, DK.llp(0, Ui.dpi(46f), 1f, l = if (i == 0) 0f else 4f, r = if (i == 0) 4f else 0f))
                }
                col.addView(row)
                voteBox.addView(col)
                return
            }
            val line: CharSequence = when {
                running && b != null -> SpannableStringBuilder("Votes: ").apply {
                    val a0 = length; append(DK.n(b.optInt("votes_a"))); setSpan(ForegroundColorSpan(Ui.c("#6d28d9")), a0, length, 0); setSpan(StyleSpan(android.graphics.Typeface.BOLD), a0, length, 0)
                    append(" – ")
                    val b0 = length; append(DK.n(b.optInt("votes_b"))); setSpan(ForegroundColorSpan(Ui.c("#db2777")), b0, length, 0); setSpan(StyleSpan(android.graphics.Typeface.BOLD), b0, length, 0)
                    if (amDancer()) append(" · keep dancing! 🔥")
                }
                b?.optString("status") == "ended" -> if (b.optString("winner") == "tie") "Last battle was a tie 🤝" else {
                    val w = if (b.optString("winner") == b.optJSONObject("a")?.optString("user_id")) b.optJSONObject("a") else b.optJSONObject("b")
                    "🏆 ${w?.optString("name").orEmpty()} won the last battle"
                }
                b?.optString("status") == "cancelled" -> "Last battle was stopped"
                else -> {
                    val sides = stage.sides()
                    if (sides[0] != null && sides[1] != null) "Dancers are ready — battle starts soon" else "Waiting for ${if (sides.any { it != null }) "one more dancer" else "dancers"}…"
                }
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = DK.card(23f); elevation = Ui.dp(2f)
                setPadding(Ui.dpi(14f), Ui.dpi(6f), Ui.dpi(6f), Ui.dpi(6f)); minimumHeight = Ui.dpi(46f)
            }
            card.addView(DK.t(context, line, 13.5f, DK.STEP_TEXT, false, 2), DK.llp(0, DK.WRAP, 1f))
            if (isHost()) {
                if (running) card.addView(Ui.text(context, "Stop", 13.5f, DK.ERR).apply {
                    gravity = Gravity.CENTER; background = DK.pill(Ui.c("#fff1f2"), Ui.c("#fecdd3"), 1.5f); setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.SQUARE, DK.ERR, 13f, DK.ERR), null, null, null); compoundDrawablePadding = Ui.dpi(5f)
                    contentDescription = "Stop battle"; isClickable = true; setOnClickListener { confirmStop() }
                    Ui.pressable(this, 0.96f)
                }, DK.llp(DK.WRAP, Ui.dpi(36f), l = 8f))
                else {
                    val sides = stageSides()
                    val both = sides.size == 2 && sides[0] != null && sides[1] != null
                    card.addView(DK.button(context, "Start battle", DIcon(DIcon.SWORDS, Color.WHITE, 15f), 36f, 13.5f).apply {
                        elevation = 0f; setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0); alpha = if (both) 1f else 0.45f
                        contentDescription = "Start battle"
                        setOnClickListener { if (both) DanceSheets.timer(screen, this@DanceRoomPage) }
                    }, DK.llp(DK.WRAP, Ui.dpi(36f), l = 8f))
                }
            }
            voteBox.addView(card, DK.flp(DK.MATCH, DK.WRAP, l = 8f, t = 8f, r = 8f))
        }

        // ── top supporters strip ──
        private var supSig = ""
        private fun updateSupporters(top: List<JSONObject>) {
            val sig = top.take(3).joinToString("|") { "${it.optString("user_id")}:${it.optLong("coins")}" }
            if (sig == supSig && supBox.childCount > 0) return
            supSig = sig
            supBox.removeAllViews()
            val all = Ui.text(context, "Top Supporters", 12.5f, Ui.c("#7c4a03")).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#fffaf0"), Ui.c("#fff3d6"))).apply { cornerRadius = Ui.dp(17f); setStroke(Ui.dpi(1.5f), Ui.c("#f7d58a")) }
                setPadding(Ui.dpi(11f), 0, Ui.dpi(11f), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.TROPHY, Ui.c("#d97706"), 15f), null, null, null); compoundDrawablePadding = Ui.dpi(5f)
                isClickable = true; setOnClickListener { DanceSheets.supporters(screen, roomId) }
            }
            supBox.addView(all, DK.llp(DK.WRAP, Ui.dpi(34f)))
            val hs = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (top.isEmpty()) row.addView(DK.t(context, "Send a gift to be #1 👑", 12.5f, DK.SUB, false), DK.llp(DK.WRAP, DK.WRAP, l = 6f))
            top.take(3).forEachIndexed { i, s ->
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false
                    isClickable = true; setOnClickListener { openPerson(JSONObject().put("user_id", s.optString("user_id")).put("name", s.optString("name")).put("photo", s.optString("photo"))) }
                }
                val avBox = FrameLayout(context).apply { clipChildren = false }
                avBox.addView(AvatarView(context, 30f, listOf(Ui.c("#f5b301"), Ui.c("#cbd5e1"), Ui.c("#d97706"))[i]).apply { set(s.optString("photo")) }, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER, t = 6f))
                avBox.addView(Ui.text(context, listOf("👑", "🥈", "🥉")[i], 12f, Color.WHITE, false), DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = -4f))
                item.addView(avBox)
                val tx = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(5f), 0, 0, 0) }
                tx.addView(DK.t(context, s.optString("name"), 12f, DK.INK).apply { maxWidth = Ui.dpi(62f) })
                tx.addView(Ui.text(context, DK.c(s.optLong("coins")), 11f, Ui.c("#92400e")).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(11f), null, null, null); compoundDrawablePadding = Ui.dpi(2f)
                })
                item.addView(tx)
                row.addView(item, DK.llp(DK.WRAP, DK.WRAP, l = if (i == 0) 6f else 8f))
            }
            hs.addView(row)
            supBox.addView(hs, DK.llp(0, DK.WRAP, 1f))
            supBox.addView(DK.iconButton(context, DIcon(DIcon.CHEVRON_RIGHT, DK.PURPLE, 17f), 30f, DK.oval(DK.SOFT), "View all supporters").apply {
                setOnClickListener { DanceSheets.supporters(screen, roomId) }
            })
        }

        // ── side menu next to the chat ──
        private fun updateSide() {
            val r = room() ?: return
            val mode = mode()
            val pending = st?.optJSONObject("pending")
            val sig = "$mode|${r.optLong("viewers")}|${isHost()}|${pending?.optInt("song")}|${pending?.optInt("seat")}"
            if (sig == sideSig) return
            sideSig = sig
            side.removeAllViews()
            fun btn(icon: String, label: String, badge: Int = 0, click: () -> Unit) {
                val f = FrameLayout(context).apply { clipChildren = false; clipToPadding = false; setPadding(0, Ui.dpi(5f), Ui.dpi(4f), 0) }
                val b = Ui.text(context, label, 12f, DK.INK).apply {
                    gravity = Gravity.CENTER_VERTICAL; setLineSpacing(0f, 1.05f)
                    background = GradientDrawable().apply { setColor(Ui.ca("#ffffff", 0.95f)); cornerRadius = Ui.dp(14f); setStroke(Ui.dpi(1f), DK.LINE) }
                    elevation = Ui.dp(1.5f)
                    setPadding(Ui.dpi(8f), Ui.dpi(5f), Ui.dpi(8f), Ui.dpi(5f)); minHeight = Ui.dpi(36f)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(icon, DK.PURPLE, 16f), null, null, null); compoundDrawablePadding = Ui.dpi(6f)
                    isClickable = true; contentDescription = label
                    setOnClickListener { click() }
                    Ui.pressable(this, 0.96f)
                }
                f.addView(b, DK.flp(DK.MATCH, DK.WRAP))
                if (badge > 0) f.addView(Ui.text(context, if (badge > 99) "99+" else "$badge", 11f, Color.WHITE).apply {
                    gravity = Gravity.CENTER; background = DK.pill(DK.PINK); setPadding(Ui.dpi(5f), 0, Ui.dpi(5f), 0); minWidth = Ui.dpi(18f); elevation = Ui.dp(3f)
                }, DK.flp(DK.WRAP, Ui.dpi(18f), Gravity.TOP or Gravity.END))
                side.addView(f, DK.llp(DK.MATCH, DK.WRAP, b = 1f))
            }
            btn(DIcon.INFO, "Room Info") { DanceSheets.info(screen, this@DanceRoomPage) }
            btn(DIcon.USERS, "People (${DK.c(r.optLong("viewers"))})") { DanceSheets.people(screen, this@DanceRoomPage) }
            if (mode == "solo") btn(DIcon.MUSIC, if (isHost()) "Requests" else "Song Request", if (isHost()) pending?.optInt("song") ?: 0 else 0) {
                if (isHost()) DanceSheets.songsBoard(screen, this@DanceRoomPage) else DanceSheets.song(screen, this@DanceRoomPage)
            } else btn(DIcon.BAR_CHART, "Battle Rank") { DanceSheets.rank(screen, roomId, battle()) }
            if (mode != "solo" && isHost()) btn(DIcon.HAND, "Dance Requests", pending?.optInt("seat") ?: 0) { DanceSheets.seats(screen, this@DanceRoomPage) }
            btn(DIcon.FILE_TEXT, "Rules") { DanceSheets.rules(screen, mode, feeOf()) }
        }

        // ── the row under the message box: quick gifts + 🎁, or a dancer's tools ──
        private fun updateTools() {
            val m = me()
            val sig = "${amDancer()}|${isHost()}|${m?.optBoolean("muted")}|${m?.optBoolean("cam_off")}|${targets().joinToString { it.optString("user_id") }}|${mode()}|${running()}|${pendingOf("seat")}|${pendingOf("song")}|${isDemoRoom()}"
            if (sig == toolsSig) { quick.refresh(); return }
            toolsSig = sig
            toolRow.removeAllViews()
            if (onStage()) {
                // web HostTools: label = what it does, short = the word under the icon
                fun tool(icon: String, label: String, danger: Boolean, short: String = label, on: Boolean = false, badge: Int = 0, click: () -> Unit) {
                    val f = FrameLayout(context).apply { clipChildren = false }
                    val fg = if (on) Color.WHITE else if (danger) DK.ERR else DK.INK
                    val t = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        background = if (on) DK.grad(DK.GRAD, 16f) else GradientDrawable().apply { setColor(if (danger) Ui.c("#fff1f2") else Color.WHITE); cornerRadius = Ui.dp(16f); setStroke(Ui.dpi(1f), if (danger) Ui.c("#fecdd3") else DK.LINE) }
                        isClickable = true; contentDescription = label
                        setOnClickListener { click() }
                        Ui.pressable(this, 0.96f)
                    }
                    t.addView(ImageView(context).apply { setImageDrawable(DIcon(icon, if (on) Color.WHITE else if (danger) DK.ERR else DK.PURPLE, 20f)) }, DK.llp(DK.WRAP, DK.WRAP))
                    t.addView(Ui.text(context, short, 11f, fg).apply { setPadding(0, Ui.dpi(2f), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, DK.llp(DK.WRAP, DK.WRAP))
                    f.addView(t, DK.flp())
                    if (badge > 0) f.addView(Ui.text(context, if (badge > 99) "99+" else "$badge", 11f, Color.WHITE).apply {
                        gravity = Gravity.CENTER; background = DK.pill(DK.PINK); setPadding(Ui.dpi(5f), 0, Ui.dpi(5f), 0); minWidth = Ui.dpi(18f); elevation = Ui.dp(3f)
                    }, DK.flp(DK.WRAP, Ui.dpi(18f), Gravity.TOP or Gravity.END, t = 3f, r = 5f))
                    toolRow.addView(f, DK.llp(0, Ui.dpi(58f), 1f, l = if (toolRow.childCount == 0) 0f else 5f))
                }
                val muted = m?.optBoolean("muted") == true
                val camOff = m?.optBoolean("cam_off") == true
                tool(if (muted) DIcon.MIC_OFF else DIcon.MIC, if (muted) "Mic off" else "Mic on", muted) { act("mute", JSONObject().put("muted", !muted)) }
                tool(if (camOff) DIcon.VIDEO_OFF else DIcon.VIDEO, if (camOff) "Camera off" else "Camera", camOff) { act("camera", JSONObject().put("off", !camOff)) }
                if (isHost()) {
                    if (mode() == "solo") tool(DIcon.MUSIC, "Requests", false, badge = pendingOf("song")) { DanceSheets.songsBoard(screen, this@DanceRoomPage) }
                    else {
                        tool(DIcon.HAND, "Dancers", false, badge = pendingOf("seat")) { DanceSheets.seats(screen, this@DanceRoomPage) }
                        if (running()) tool(DIcon.SQUARE, "Stop battle", true, short = "Stop") { confirmStop() }
                        else tool(DIcon.SWORDS, "Start battle", false, short = "Battle", on = true) { DanceSheets.timer(screen, this@DanceRoomPage) }
                    }
                    if (!isDemoRoom()) tool(DIcon.FLASK, "Demo", false) { DanceSheets.demo(screen, inRoom = true) { refreshNow() } }
                    tool(DIcon.LOG_OUT, "End live", true, short = "End") { confirmEnd() }
                } else tool(DIcon.LOG_OUT, "Leave stage", true, short = "Leave") {
                    DanceSheets.confirm(screen, "Leave the stage?", if (running()) "The running battle will stop without a winner." else "You will watch as a viewer again.", "Leave stage") { act("seat_leave", ok = "You left the stage") }
                }
            } else {
                toolRow.addView(quick.box, DK.llp(0, Ui.dpi(58f), 1f))
                quick.refresh()
            }
            if (!isHost()) toolRow.addView(giftBtn, DK.llp(Ui.dpi(58f), Ui.dpi(58f), l = 8f))
        }

        // ── chat ──
        fun chatAtEnd(): Boolean = !chat.canScrollVertically(1)
        @SuppressLint("NotifyDataSetChanged")
        fun chatChanged(scroll: Boolean) {
            chatAdapter.notifyDataSetChanged()
            if (scroll && msgs.isNotEmpty()) chat.scrollToPosition(msgs.size - 1)
        }

        fun showGiftBurst(g: JSONObject) = stage.giftBurst(g)
        fun showWinner(b: JSONObject) = stage.winner(b)

        private fun send() {
            val t = input.text.toString().trim()
            if (t.isEmpty() || sending) return
            sending = true
            DanceApi.call("message", JSONObject().put("room_id", roomId).put("text", t)) { r ->
                sending = false
                if (closed) return@call
                if (r.ok) { input.setText(""); emojiPanel.visibility = View.GONE; poll() } else screen.showToast(r.text())
            }
        }
        private var sending = false
    }

    // ───────── one chat line (ChatLine) ─────────
    private inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = msgs.size
        override fun getItemViewType(p: Int): Int = when (msgs[p].optString("kind")) { "system" -> 0; "gift" -> 1; else -> 2 }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val v: View = if (type == 0) DK.t(context, "", 12.5f, DK.PURPLE, true, 0).apply { setPadding(Ui.dpi(4f), Ui.dpi(3f), Ui.dpi(4f), Ui.dpi(3f)); setLineSpacing(0f, 1.15f) }
            else LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                setPadding(0, Ui.dpi(3f), 0, Ui.dpi(3f))
                addView(AvatarView(context, 30f).apply { tag = "av" })
                addView(DK.t(context, "", if (type == 1) 13f else 14f, if (type == 1) Color.WHITE else Ui.c("#3b3355"), type == 1, 0).apply {
                    tag = "tx"; setLineSpacing(0f, 1.15f)
                    if (type == 1) { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#7a1fe4"), Ui.c("#e0399a"))).apply { cornerRadius = Ui.dp(14f) }; setPadding(Ui.dpi(11f), Ui.dpi(5f), Ui.dpi(11f), Ui.dpi(5f)) }
                    else setPadding(0, Ui.dpi(5f), 0, 0)
                }, DK.llp(DK.WRAP, DK.WRAP, l = 7f))
            }
            v.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            val m = msgs[p]
            val kind = m.optString("kind")
            if (kind == "system") { (h.itemView as TextView).text = m.optString("text"); return }
            val row = h.itemView as LinearLayout
            val av = row.findViewWithTag<AvatarView>("av")
            av.set(m.optString("photo"))
            av.setOnClickListener { openPerson(JSONObject().put("user_id", m.optString("user_id")).put("name", m.optString("name")).put("photo", m.optString("photo"))) }
            av.contentDescription = m.optString("name")
            val tx = row.findViewWithTag<TextView>("tx")
            val sb = SpannableStringBuilder()
            if (kind == "gift") {
                val g = DK.gift(m.optString("gift_id"))
                sb.append(m.optString("name")); sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
                sb.append(" sent ${g?.name ?: "a gift"} ")
                val e0 = sb.length; sb.append(g?.e ?: "🎁"); sb.setSpan(RelativeSizeSpan(1.15f), e0, sb.length, 0)
                if (m.optInt("count") > 1) { val c0 = sb.length; sb.append(" x${m.optInt("count")}"); sb.setSpan(ForegroundColorSpan(Ui.c("#ffe08a")), c0, sb.length, 0) }
                if (m.optString("to_name").isNotBlank()) { val t0 = sb.length; sb.append(" → ${m.optString("to_name")}"); sb.setSpan(ForegroundColorSpan(Ui.ca("#ffffff", 0.9f)), t0, sb.length, 0) }
            } else {
                sb.append(m.optString("name")); sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0); sb.setSpan(ForegroundColorSpan(DK.INK), 0, sb.length, 0)
                sb.append(" ").append(DK.tagOf(m.optString("user_id"))).append(" ")
                if (kind == "join") { val j0 = sb.length; sb.append("joined the room"); sb.setSpan(ForegroundColorSpan(DK.SUB), j0, sb.length, 0) } else sb.append(m.optString("text"))
            }
            tx.text = sb
        }
    }

    fun openPerson(u: JSONObject) {
        if (u.optString("user_id").isEmpty() || u.optString("user_id") == myId()) return
        DanceSheets.person(screen, this, u)
    }

    // ═════════ the stage ═════════
    private inner class StageView {
        val box = FrameLayout(context)
        val hearts = HeartsView(context)
        private val burstFx = BurstView(context)
        private val people = FrameLayout(context)   // tiles + labels (rebuilt when who-is-where changes)
        private val viewers: TextView
        private val hostTag: TextView
        private val speaker: FrameLayout
        private val soon: TextView
        private val giftLayer = FrameLayout(context)
        private var winnerView: View? = null
        private var timerText: TextView? = null
        private var timerSub: TextView? = null
        private var timerIcon: View? = null
        private var timerBox: FrameLayout? = null
        private var bars: VoteBarsView? = null
        private var layoutSig = ""
        private val tiles = ArrayList<VideoTile>()
        private var vsAnim: android.animation.ValueAnimator? = null

        init {
            box.background = DK.stageBg()
            box.elevation = Ui.dp(6f)
            box.outlineSpotShadowColorCompat(Ui.c("#3c0a8c"))
            DK.roundClip(box, 24f)
            people.clipChildren = false
            box.addView(people, DK.flp())
            // top line: LIVE · viewers … host tag · sound
            val topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val (lp, v) = DK.livePill(context)
            viewers = v
            topRow.addView(lp)
            topRow.addView(View(context), DK.llp(0, 1, 1f))
            hostTag = DK.tag(context, "👑 Host", true)
            topRow.addView(hostTag, DK.llp(DK.WRAP, Ui.dpi(24f), r = 6f))
            speaker = DK.iconButton(context, DIcon(DIcon.VOLUME, Color.WHITE, 17f), 34f, DK.glassOval(), "Sound off").apply {
                setOnClickListener {
                    speakerOff = !speakerOff
                    video?.setSpeakerOff(speakerOff)
                    (getChildAt(0) as ImageView).setImageDrawable(DIcon(if (speakerOff) DIcon.VOLUME_X else DIcon.VOLUME, Color.WHITE, 17f))
                    contentDescription = if (speakerOff) "Sound on" else "Sound off"
                }
            }
            topRow.addView(speaker)
            box.addView(topRow, DK.flp(DK.MATCH, DK.WRAP, Gravity.TOP, 10f, 10f, 10f))
            soon = Ui.text(context, "Live video starts soon", 12f, Color.WHITE).apply {
                background = DK.glass(); setPadding(Ui.dpi(12f), Ui.dpi(6f), Ui.dpi(12f), Ui.dpi(6f)); visibility = View.GONE
            }
            box.addView(soon, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            box.addView(hearts, DK.flp(Ui.dpi(90f), Ui.dpi(320f), Gravity.BOTTOM or Gravity.END, r = 10f, b = 70f))
            box.addView(giftLayer, DK.flp())
            box.addView(burstFx, DK.flp())
        }

        fun sides(): List<JSONObject?> = stageSides()

        fun update() {
            val s = st ?: return
            val r = s.optJSONObject("room") ?: return
            val mode = mode()
            viewers.text = DK.c(r.optLong("viewers"))
            hostTag.visibility = if (mode == "solo") View.VISIBLE else View.GONE
            val b = battle()
            val winnerId = if (b?.optString("status") == "ended") b.optString("winner") else ""
            val sides = sides()
            val h = s.optJSONObject("host")
            val req = myRequest()
            val sig = listOf(mode, h?.optString("user_id"), h?.optBoolean("cam_off"), h?.optBoolean("muted"), h?.optString("photo"),
                sides.joinToString(",") { "${it?.optString("user_id")}:${it?.optBoolean("cam_off")}:${it?.optBoolean("muted")}" },
                winnerId, myId(), me()?.optString("role"), req?.optString("status"), req?.optString("kind"), dancers().size, s.optInt("seats"),
                if (isHost()) pendingOf("seat") else 0).joinToString("|")
            if (sig != layoutSig) { layoutSig = sig; rebuild(mode, sides, winnerId) }
            bars?.set(DK.pct(b?.optInt("votes_a"), b?.optInt("votes_b")), b != null && (running() || b.optString("status") == "ended"))
            updateTimer()
            refreshVideo()
        }

        private fun rebuild(mode: String, sides: List<JSONObject?>, winnerId: String) {
            tiles.forEach { it.release() }
            tiles.clear()
            vsAnim?.cancel(); vsAnim = null
            people.removeAllViews()
            timerText = null; timerSub = null; timerIcon = null; timerBox = null; bars = null
            val ctx = context
            val H = (box.layoutParams?.height ?: Ui.dpi(300f)).toFloat().let { if (it > 0) it else Ui.dp(300f) }
            // the stage width (before the first layout: the screen width minus the page margins)
            val W = (if (box.width > 0) box.width else resources.displayMetrics.widthPixels - Ui.dpi(16f)).toFloat()
            if (mode == "solo") {
                val host = hostTile()
                val t = VideoTile(host, host.optString("user_id") == myId(), host.optBoolean("cam_off"))
                tiles.add(t)
                people.addView(t.view, DK.flp())
                people.addView(ShadeView(ctx, intArrayOf(Ui.ca("#140528", 0.35f), Color.TRANSPARENT, Color.TRANSPARENT, Ui.ca("#140528", 0.7f)), floatArrayOf(0f, 0.22f, 0.62f, 1f)), DK.flp())
                // host chip (bottom-left)
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false
                    contentDescription = "Host ${host.optString("name")}"
                    isClickable = !isHost()
                    setOnClickListener { if (!isHost()) DanceSheets.info(screen, this@DanceRoomPage) }
                }
                val avBox = FrameLayout(ctx).apply { clipChildren = false }
                avBox.addView(AvatarView(ctx, 50f, DK.GOLD_TEXT).apply { set(host.optString("photo")) }, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER, t = 8f))
                avBox.addView(Ui.text(ctx, "👑", 16f, Color.WHITE, false), DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = -6f))
                chip.addView(avBox)
                val names = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(8f), 0, 0, 0) }
                // WRAP: in a column that is only as wide as its content, a full-width child gets squeezed to the "Host" tag's width
                names.addView(nameWithBadge(room()?.optJSONObject("host")?.optString("name").orEmpty(), 16f, 15f).apply {
                    maxWidth = (W * 0.62f - Ui.dp(63f)).toInt().coerceAtLeast(Ui.dpi(80f)) // web: the host chip is at most 62 % wide
                }, DK.llp(DK.WRAP, DK.WRAP))
                names.addView(Ui.text(ctx, if (host.optBoolean("muted")) "🔇 Host" else "Host", 11.5f, Color.WHITE).apply { background = DK.glass(); setPadding(Ui.dpi(8f), Ui.dpi(1f), Ui.dpi(8f), Ui.dpi(1f)) }, DK.llp(DK.WRAP, DK.WRAP, t = 2f))
                chip.addView(names)
                people.addView(chip, DK.flp(DK.WRAP, DK.WRAP, Gravity.BOTTOM or Gravity.START, l = 12f, b = 14f))
                // side buttons (bottom-right)
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                fun sb(v: View) = col.addView(v, DK.llp(Ui.dpi(46f), Ui.dpi(46f), t = if (col.childCount == 0) 0f else 9f))
                if (!isHost()) sb(DK.emojiButton(ctx, "🎁", 46f, 22f, DK.glassOval(), "Send a gift").apply { setOnClickListener { DanceSheets.gifts(screen, this@DanceRoomPage) } })
                val heart = DK.iconButton(ctx, DIcon(DIcon.HEART, Color.WHITE, 22f, Color.WHITE), 46f, DK.glassOval(), "Send hearts")
                heart.setOnClickListener { burstFx.burst(heart); like() }
                sb(heart)
                if (!isHost()) sb(DK.iconButton(ctx, DIcon(DIcon.MUSIC, Color.WHITE, 21f), 46f, DK.glassOval(), "Request a song").apply { setOnClickListener { DanceSheets.song(screen, this@DanceRoomPage) } })
                sb(DK.iconButton(ctx, DIcon(DIcon.SHARE, Color.WHITE, 20f), 46f, DK.glassOval(), "Share").apply { setOnClickListener { shareLive() } })
                people.addView(col, DK.flp(DK.WRAP, DK.WRAP, Gravity.BOTTOM or Gravity.END, r = 10f, b = 14f))
                (soon.layoutParams as FrameLayout.LayoutParams).topMargin = (H * 0.46f).toInt()
            } else {
                // two slanted halves: 53 % wide each, overlapping in the middle
                val halves = object : FrameLayout(ctx) {
                    override fun onMeasure(ws: Int, hs: Int) {
                        val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs); val half = (w * 0.53f).toInt()
                        for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(half, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                        setMeasuredDimension(w, h)
                    }
                    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                        val w = r - l; val h = b - t; val half = (w * 0.53f).toInt()
                        if (childCount > 0) getChildAt(0).layout(0, 0, half, h)
                        if (childCount > 1) getChildAt(1).layout(w - half, 0, w, h)
                    }
                }
                sides.forEachIndexed { i, p ->
                    val f = SlantFrame(ctx, i == 0).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (i == 0) intArrayOf(Ui.c("#5b21b6"), Ui.c("#2a0b55")) else intArrayOf(Ui.c("#9d174d"), Ui.c("#3b0f5a")))
                    }
                    if (p != null) {
                        val t = VideoTile(p, p.optString("user_id") == myId(), p.optBoolean("cam_off"))
                        tiles.add(t)
                        f.addView(t.view, DK.flp())
                        f.addView(ShadeView(ctx, intArrayOf(Ui.ca("#140528", 0.4f), Color.TRANSPARENT, Color.TRANSPARENT, Ui.ca("#140528", 0.8f)), floatArrayOf(0f, 0.25f, 0.55f, 1f)), DK.flp())
                    } else f.addView(emptySeat(i), DK.flp())
                    halves.addView(f)
                }
                people.addView(halves, DK.flp())
                // labels on each side
                sides.forEachIndexed { i, p ->
                    if (p == null) return@forEachIndexed
                    val g = if (i == 0) Gravity.START else Gravity.END
                    val label = if (mode == "battle") (if (i == 0) "👑 Host" else "Challenger") else "Dancer ${i + 1}"
                    people.addView(DK.tag(ctx, label, i == 0 && mode == "battle"), DK.flp(DK.WRAP, Ui.dpi(24f), Gravity.TOP or g, l = 10f, t = 46f, r = 10f))
                    if (p.optBoolean("muted")) people.addView(DK.iconButton(ctx, DIcon(DIcon.MIC_OFF, Color.WHITE, 14f), 26f, DK.glassOval(), "Mic off").apply { isClickable = false },
                        DK.flp(Ui.dpi(26f), Ui.dpi(26f), Gravity.TOP or g, l = 12f, t = 76f, r = 12f))
                    val nb = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false
                        layoutDirection = if (i == 0) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
                        contentDescription = p.optString("name")
                        isClickable = true; setOnClickListener { openPerson(p) }
                    }
                    val avBox = FrameLayout(ctx).apply { clipChildren = false }
                    avBox.addView(AvatarView(ctx, 36f, if (i == 0) Ui.c("#b98cff") else Ui.c("#ff8fc0")).apply { set(p.optString("photo")) }, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER, t = 8f))
                    if (winnerId.isNotEmpty() && winnerId == p.optString("user_id")) {
                        val crown = Ui.text(ctx, "👑", 18f, Color.WHITE, false)
                        avBox.addView(crown, DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = -6f)); crown.bob()
                    }
                    nb.addView(avBox)
                    // web: photo + name are at most 34 % of the stage wide
                    nb.addView(nameWithBadge(p.optString("name"), 13.5f, 14f).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; maxWidth = (W * 0.34f - Ui.dp(46f)).toInt().coerceAtLeast(Ui.dpi(60f)) }, DK.llp(DK.WRAP, DK.WRAP, l = 6f, r = 6f))
                    people.addView(nb, DK.flp(DK.WRAP, DK.WRAP, Gravity.BOTTOM or g, l = 8f, r = 8f, b = 38f))
                }
                // host bubble (2 Dancer Battle)
                if (mode == "duo") {
                    val host = hostTile()
                    val bubble = FrameLayout(ctx).apply { clipChildren = false; contentDescription = "Host ${host.optString("name")}"; isClickable = true; setOnClickListener { openPerson(host) } }
                    val glow = GlowRing(ctx)
                    bubble.addView(glow, DK.flp(Ui.dpi(84f), Ui.dpi(84f), Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = -11f))
                    val circle = FrameLayout(ctx).apply { setBackgroundColor(Ui.c("#2a0b55")) }
                    DK.ovalClip(circle)
                    val t = VideoTile(host, host.optString("user_id") == myId(), false)
                    tiles.add(t)
                    circle.addView(t.view, DK.flp())
                    bubble.addView(circle, DK.flp(Ui.dpi(62f), Ui.dpi(62f), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                    bubble.addView(Ui.text(ctx, "Host", 11f, Ui.c("#4a2300")).apply {
                        background = DK.grad(intArrayOf(Ui.c("#ffd166"), Ui.c("#f59e0b")), 99f); setPadding(Ui.dpi(9f), Ui.dpi(1f), Ui.dpi(9f), Ui.dpi(1f))
                    }, DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 52f))
                    people.addView(bubble, DK.flp(Ui.dpi(84f), Ui.dpi(84f), Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 38f))
                }
                // VS + timer
                val vs = Ui.text(ctx, "VS", 46f, DK.GOLD_TEXT).apply { typeface = DK.italic(); setShadowLayer(Ui.dp(9f), 0f, Ui.dp(3f), Ui.c("#b45309")) }
                people.addView(vs, DK.flp(DK.WRAP, DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = (H * (if (mode == "duo") 0.37f else 0.30f)) / Ui.density))
                vsAnim = vs.vsWobble()
                val tb = FrameLayout(ctx)
                timerBox = tb
                val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                val ti = ImageView(ctx).apply { setImageDrawable(DIcon(DIcon.TIMER, Color.WHITE, 24f)); alpha = 0.85f }
                val tt = Ui.text(ctx, "", 19f, Color.WHITE).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
                val ts = Ui.text(ctx, "", 9.5f, Ui.ca("#ffffff", 0.8f)).apply { gravity = Gravity.CENTER }
                tcol.addView(ti); tcol.addView(tt); tcol.addView(ts)
                tb.addView(tcol, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER))
                timerIcon = ti; timerText = tt; timerSub = ts
                people.addView(tb, DK.flp(Ui.dpi(72f), Ui.dpi(72f), Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = (H * (if (mode == "duo") 0.55f else 0.52f)) / Ui.density))
                val vb = VoteBarsView(ctx)
                bars = vb
                people.addView(vb, DK.flp(DK.MATCH, Ui.dpi(26f), Gravity.BOTTOM, 8f, 0f, 8f, 8f))
                (soon.layoutParams as FrameLayout.LayoutParams).topMargin = (H * 0.18f).toInt()
            }
        }

        private fun emptySeat(side: Int): View {
            val ctx = context
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(if (side == 0) Ui.dpi(8f) else Ui.dpi(40f), 0, if (side == 0) Ui.dpi(40f) else Ui.dpi(8f), 0)
            }
            col.addView(Ui.text(ctx, "💃", 28f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(Ui.dpi(2f), Ui.ca("#ffffff", 0.55f), Ui.dp(5f), Ui.dp(4f)) }
            }, DK.llp(Ui.dpi(64f), Ui.dpi(64f), b = 10f))
            val req = myRequest()
            val waiting = req?.optString("kind") == "seat" && req.optString("status") == "pending"
            val seatOpen = mode() != "solo" && dancers().size < (st?.optInt("seats") ?: 0)
            when {
                isHost() -> {
                    col.addView(Ui.text(ctx, "Waiting for a dancer", 12.5f, Ui.ca("#ffffff", 0.9f)).apply { gravity = Gravity.CENTER })
                    val n = pendingOf("seat")
                    col.addView(Ui.text(ctx, "Requests${if (n > 0) " ($n)" else ""}", 13f, DK.PURPLE).apply {
                        gravity = Gravity.CENTER; background = DK.pill(Color.WHITE); setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                        setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.HAND, DK.PURPLE, 15f), null, null, null); compoundDrawablePadding = Ui.dpi(5f)
                        contentDescription = "Dance requests"; isClickable = true; setOnClickListener { DanceSheets.seats(screen, this@DanceRoomPage) }
                        Ui.pressable(this, 0.96f)
                    }, DK.llp(DK.WRAP, Ui.dpi(34f), t = 10f))
                }
                waiting -> {
                    col.addView(Ui.text(ctx, "Request sent ⏳", 12.5f, Ui.ca("#ffffff", 0.9f)).apply { gravity = Gravity.CENTER })
                    col.addView(Ui.text(ctx, "Cancel", 12.5f, Color.WHITE).apply {
                        gravity = Gravity.CENTER; background = DK.pill(Color.TRANSPARENT, Ui.ca("#ffffff", 0.6f)); setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                        isClickable = true; setOnClickListener { act("seat_cancel", ok = "Request cancelled") }
                    }, DK.llp(DK.WRAP, Ui.dpi(32f), t = 10f))
                }
                !onStage() && seatOpen -> col.addView(Ui.text(ctx, "Request to dance", 13.5f, Ui.c("#4a2300")).apply {
                    gravity = Gravity.CENTER
                    background = DK.grad(intArrayOf(Ui.c("#ffd166"), Ui.c("#f59e0b")), 99f)
                    setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.PLUS, Ui.c("#4a2300"), 16f), null, null, null); compoundDrawablePadding = Ui.dpi(5f)
                    contentDescription = "Request to dance"
                    isClickable = true
                    setOnClickListener { act("seat_request", ok = if (room()?.optBoolean("demo") == true) "You are on stage now 💃" else "Request sent to the host") }
                    Ui.pressable(this, 0.96f)
                }, DK.llp(DK.WRAP, Ui.dpi(38f)))
                else -> col.addView(Ui.text(ctx, "Seat empty", 12.5f, Ui.ca("#ffffff", 0.85f)).apply { gravity = Gravity.CENTER })
            }
            return col
        }

        fun updateTimerNow() {
            val tt = timerText ?: return
            val tb = timerBox ?: return
            val b = battle()
            val run = running()
            if (run && b != null) {
                val left = (DK.ms(b.optString("ends_at")) - (System.currentTimeMillis() + offsetMs)) / 1000.0
                tt.text = DK.mmss(left); tt.visibility = View.VISIBLE
                timerIcon?.visibility = View.GONE; timerSub?.visibility = View.GONE
                tb.contentDescription = "Time left ${DK.mmss(left)}"
            } else {
                tt.visibility = View.GONE; timerIcon?.visibility = View.VISIBLE
                timerSub?.visibility = View.VISIBLE; timerSub?.text = if (b?.optString("status") == "ended") "Ended" else "Waiting"
                tb.contentDescription = "Battle not running"
            }
            tb.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = Ui.dp(36f)
                colors = intArrayOf(Ui.c("#2a0b55"), Ui.c("#2a0b55"), Ui.c("#14052b"))
                if (run) setStroke(Ui.dpi(3f), Ui.c("#b98cff")) else setStroke(Ui.dpi(2f), Ui.ca("#ffffff", 0.3f))
            }
        }

        fun refreshVideo() {
            val v = video
            tiles.forEach { it.refresh(v) }
            // "starts soon" only when the video itself is missing (not when the host simply turned the camera off)
            val hostCamOff = st?.optJSONObject("host")?.optBoolean("cam_off") == true
            soon.visibility = if (videoOn == false && !onStage() && mode() != "duo" && !(mode() == "solo" && hostCamOff)) View.VISIBLE else View.GONE
        }

        // ── gift pop-up, winner ──
        fun giftBurst(g: JSONObject) {
            val gift = DK.gift(g.optString("gift_id")) ?: return
            giftLayer.removeAllViews()
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(Ui.text(context, gift.e, 90f, Color.WHITE, false).apply { gravity = Gravity.CENTER; setShadowLayer(Ui.dp(18f), 0f, Ui.dp(10f), Ui.ca("#000000", 0.45f)) })
            val sb = SpannableStringBuilder("${g.optString("name")} sent ${gift.name}${if (g.optInt("count") > 1) " x${g.optInt("count")}" else ""}")
            if (g.optString("to_name").isNotBlank()) { val a = sb.length; sb.append(" to ${g.optString("to_name")}"); sb.setSpan(ForegroundColorSpan(Color.WHITE), a, sb.length, 0) }
            col.addView(DK.t(context, sb, 14f, Ui.c("#ffe1a1"), true, 2).apply {
                gravity = Gravity.CENTER; background = DK.glass(99f, 0.75f); setPadding(Ui.dpi(14f), Ui.dpi(7f), Ui.dpi(14f), Ui.dpi(7f))
            }, DK.llp(DK.WRAP, DK.WRAP, t = 6f))
            giftLayer.addView(col, DK.flp(Ui.dpi(250f), DK.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = (box.height * 0.22f) / Ui.density))
            col.scaleX = 0.3f; col.scaleY = 0.3f; col.alpha = 0f
            col.animate().scaleX(1.12f).scaleY(1.12f).alpha(1f).setDuration(500).withEndAction {
                col.animate().scaleX(1f).scaleY(1f).setDuration(280).withEndAction {
                    col.animate().scaleX(0.9f).scaleY(0.9f).translationY(-Ui.dp(20f)).alpha(0f).setStartDelay(1600).setDuration(420)
                        .withEndAction { giftLayer.removeView(col) }.start()
                }.start()
            }.start()
        }

        fun winner(b: JSONObject) {
            closeWinner()
            val tie = b.optString("winner") == "tie"
            val a = b.optJSONObject("a"); val bb = b.optJSONObject("b")
            val aWon = b.optString("winner") == a?.optString("user_id")
            val w = if (tie) null else if (aWon) a else bb
            val pct = if (w != null) DK.pct(if (aWon) b.optInt("votes_a") else b.optInt("votes_b"), if (aWon) b.optInt("votes_b") else b.optInt("votes_a")) else 50
            val f = FrameLayout(context).apply {
                setBackgroundColor(Ui.ca("#140528", 0.62f)); isClickable = true
                contentDescription = if (tie) "Winner: it's a tie — tap to close" else "Winner: ${w?.optString("name")} — tap to close"
                setOnClickListener { closeWinner() }
            }
            val conf = ConfettiView(context)
            f.addView(conf, DK.flp())
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val crown = Ui.text(context, if (tie) "🤝" else "👑", 44f, Color.WHITE, false)
            col.addView(crown, DK.llp(DK.WRAP, DK.WRAP)); crown.bob()
            if (w != null) col.addView(AvatarView(context, 84f, DK.GOLD_TEXT).apply { set(w.optString("photo")) }, DK.llp(DK.WRAP, DK.WRAP, t = 8f))
            col.addView(Ui.text(context, if (tie) "It's a tie!" else "${w?.optString("name")} wins!", 28f, DK.GOLD_TEXT).apply {
                typeface = DK.italic(); setShadowLayer(1f, 0f, Ui.dp(2f), Ui.c("#92400e")); gravity = Gravity.CENTER
            }, DK.llp(DK.WRAP, DK.WRAP, t = 8f))
            col.addView(Ui.text(context, if (tie) "${DK.n(b.optInt("votes_a"))} – ${DK.n(b.optInt("votes_b"))} votes" else "$pct% of the votes · Battle ${b.optInt("no")}", 14.5f, Ui.ca("#ffffff", 0.95f)).apply { gravity = Gravity.CENTER }, DK.llp(DK.WRAP, DK.WRAP, t = 8f))
            f.addView(col, DK.flp(DK.WRAP, DK.WRAP, Gravity.CENTER))
            box.addView(f, DK.flp())
            winnerView = f
            f.alpha = 0f; f.animate().alpha(1f).setDuration(200).start()
            conf.play()
            main.postDelayed({ if (winnerView === f) closeWinner() }, 7000)
        }

        fun closeWinner(): Boolean {
            val wv = winnerView ?: return false
            winnerView = null
            wv.animate().alpha(0f).setDuration(180).withEndAction { box.removeView(wv) }.start()
            return true
        }
    }

    private fun updateTimer() { live?.stage?.updateTimerNow() }

    /** one person's picture on the stage: live video, or the photo (soft when the camera is off) */
    private inner class VideoTile(private val p: JSONObject, private val isMe: Boolean, private val camOff: Boolean) {
        val view = FrameLayout(context)
        private val photo = CoverView(context)
        private val holder = FrameLayout(context)
        private val offBox: LinearLayout?
        private val uid = p.optInt("agora_uid")
        private var attached: View? = null

        init {
            view.addView(photo, DK.flp())
            photo.set(p.optString("photo"))
            if (camOff) { photo.blur = 14f; photo.dim = 0.6f; photo.zoom = 1.15f }
            view.addView(holder, DK.flp())
            holder.visibility = View.GONE
            offBox = if (camOff) LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(AvatarView(context, 64f, Ui.ca("#ffffff", 0.7f)).apply { set(p.optString("photo")) })
                addView(Ui.text(context, "Camera off", 12f, Ui.ca("#ffffff", 0.88f)).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(DIcon(DIcon.VIDEO_OFF, Ui.ca("#ffffff", 0.88f), 14f), null, null, null); compoundDrawablePadding = Ui.dpi(4f)
                }, DK.llp(DK.WRAP, DK.WRAP, t = 8f))
            } else null
            offBox?.let { view.addView(it, DK.flp()) }
        }

        fun refresh(v: DanceVideo?) {
            val tv: View? = when {
                v == null || camOff -> null
                isMe -> if (v.sendingVideo) v.localView() else null
                v.hasVideo(uid) -> v.viewFor(uid)
                else -> { v.viewFor(uid); null } // make sure it is bound, so it shows when the video starts
            }
            if (tv !== attached) {
                attached?.let { if (it.parent === holder) holder.removeView(it) }
                attached = null
                if (tv != null) {
                    (tv.parent as? ViewGroup)?.removeView(tv)
                    holder.addView(tv, DK.flp())
                    attached = tv
                }
            }
            holder.visibility = if (attached != null) View.VISIBLE else View.GONE
            photo.visibility = if (attached != null) View.INVISIBLE else View.VISIBLE
        }

        fun release() {
            attached?.let { if (it.parent === holder) holder.removeView(it) }
            attached = null
        }
    }

    // ───────── quick gifts (first tap: "Send?", second tap sends) ─────────
    inner class QuickGifts {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            danceCard(18f)
            setPadding(Ui.dpi(4f), 0, Ui.dpi(4f), 0)
        }
        private var armed = ""
        private var busy = ""
        private val disarm = Runnable { armed = ""; refresh() }

        fun refresh() {
            box.removeAllViews()
            val ts = targets().filter { it.optString("user_id") != myId() }
            val tgt = target()
            if (ts.size > 1 && tgt != null) {
                val pick = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    contentDescription = "Gifts go to ${tgt.optString("name")}. Tap to change"
                    isClickable = true
                    setOnClickListener {
                        val i = ts.indexOfFirst { it.optString("user_id") == tgt.optString("user_id") }
                        ts.getOrNull((i + 1) % ts.size)?.let { pickTarget(it.optString("user_id")) }
                    }
                }
                pick.addView(AvatarView(context, 28f, DK.PURPLE, 1.5f).apply { set(tgt.optString("photo")) })
                pick.addView(DK.t(context, "to ${DK.first(tgt.optString("name"))} ▾", 9.5f, DK.PURPLE).apply { maxWidth = Ui.dpi(46f) })
                box.addView(pick, DK.llp(Ui.dpi(46f), DK.WRAP))
            }
            DK.QUICK.forEach { id ->
                val g = DK.gift(id) ?: return@forEach
                val on = armed == id
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = if (on || busy == id) GradientDrawable().apply { setColor(DK.SOFT); cornerRadius = Ui.dp(12f); if (on) setStroke(Ui.dpi(1.5f), DK.PURPLE) } else null
                    setPadding(0, Ui.dpi(4f), 0, Ui.dpi(4f))
                    contentDescription = if (on) "Tap again to send ${g.name} for ${g.price} coins" else "Send ${g.name} for ${g.price} coins${tgt?.let { " to ${it.optString("name")}" } ?: ""}"
                    isClickable = true
                    setOnClickListener { tap(id) }
                    Ui.pressable(this, 0.95f)
                }
                cell.addView(Ui.text(context, g.e, 23f, DK.INK, false).apply { gravity = Gravity.CENTER })
                if (on) cell.addView(Ui.text(context, "Send ${DK.c(g.price)}?", 10.5f, DK.PURPLE).apply { isSingleLine = true })
                else cell.addView(Ui.text(context, DK.c(g.price), 11f, DK.STEP_TEXT).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(CoinIcon(11f), null, null, null); compoundDrawablePadding = Ui.dpi(2f)
                })
                box.addView(cell, DK.llp(0, DK.WRAP, if (on) 1.6f else 1f, l = 1f, r = 1f))
            }
        }

        private fun tap(id: String) {
            if (busy.isNotEmpty()) return
            if (armed != id) {
                armed = id
                main.removeCallbacks(disarm); main.postDelayed(disarm, 3500)
                refresh(); return
            }
            main.removeCallbacks(disarm)
            armed = ""; busy = id; refresh()
            sendGift(id, 1, null) { busy = ""; refresh() }
        }
    }
}

/** draws the web's CoinIcon inside a line of text */
class CoinSpan : android.text.style.ReplacementSpan() {
    override fun getSize(p: android.graphics.Paint, t: CharSequence?, s: Int, e: Int, fm: android.graphics.Paint.FontMetricsInt?): Int = (p.textSize * 1.1f).toInt()
    override fun draw(c: android.graphics.Canvas, t: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint) {
        val size = (p.textSize * 1.1f).toInt()
        val d = CoinIcon(size / Ui.density)
        val mid = (top + bottom) / 2
        d.setBounds(x.toInt(), mid - size / 2, x.toInt() + size, mid + size / 2)
        d.draw(c)
    }
}

/** a row that wraps its children to new lines (chips, emoji) */
class DanceFlowRow(ctx: Context) : ViewGroup(ctx) {
    var gap = Ui.dpi(7f)
    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChildWithMargins(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
            val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += cw + gap; rowH = max(rowH, ch)
        }
        setMeasuredDimension(MeasureSpec.getSize(ws), y + rowH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            val cl = paddingLeft + x + lp.leftMargin; val ct = paddingTop + y + lp.topMargin
            c.layout(cl, ct, cl + c.measuredWidth, ct + c.measuredHeight)
            x += cw + gap; rowH = max(rowH, ch)
        }
    }
    override fun generateLayoutParams(attrs: android.util.AttributeSet?) = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?) = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?) = p is MarginLayoutParams
}
