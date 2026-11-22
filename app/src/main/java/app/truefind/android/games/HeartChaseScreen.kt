package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Heart Chase Boxes — native port of HeartChase.jsx. Two players (a boy and a girl) each secretly
 * pick 4 of 16 boxes, then take turns opening one box on the partner's board, 15 seconds a turn.
 * Every rule, the clock and the coins live in the heartChase server function; this screen only
 * shows what the server says and sends the taps back. Voice: GameVoice (Agora), same room as the web.
 */
@SuppressLint("ViewConstructor")
class HeartChaseScreen(ctx: Context, layer: GamesLayer) : GameScreen(ctx, layer) {

    private object C {
        val purple = G.c("#5E17EB"); val pink = G.c("#FF4FA3"); val blue = G.c("#38A8FF"); val gold = G.c("#FFD477")
        val bg = G.c("#100A20"); val panel = G.c("#1A1034"); val line = G.ca("#ffffff", 0.12f); val muted = G.c("#B9ABD8")
        val ok = G.c("#5BE3A4"); val miss = G.c("#FF5C7C")
    }
    private val giftArt = linkedMapOf("rose" to ("🌹" to "Rose"), "choco" to ("🍫" to "Chocolate"), "teddy" to ("🧸" to "Teddy"), "bouquet" to ("💐" to "Bouquet"), "ring" to ("💍" to "Ring"))
    private val quickGifts = listOf("rose", "choco", "teddy")

    // ── state (same names as the web page) ──
    private var boot = "loading"            // loading | ready | problem
    private var problem: JSONObject? = null // { code, message, membership }
    private var me: JSONObject? = null
    private var rules: JSONObject? = null
    private var balance: Long? = null
    private var screen = "intro"            // intro | search | game
    private var session: JSONObject? = null
    private val pick = ArrayList<Int>()
    private var sel: Int? = null
    private var view = "theirs"
    private var busy = false
    private var sending = false
    private var seenGifts = 0
    private var clockAt = 0L
    private var clockLeft = 0L
    private var lastTurnKey = ""
    private var skipTimer: Runnable? = null
    private var pollRun = 0
    private var searchRun = 0
    private var pageKey = ""
    private var resultKey = ""
    private var flashHide: Runnable? = null
    private var flashHost: FrameLayout? = null
    private var searching = false
    private var pollId = ""
    private var polling = false
    private var headerCoins: TextView? = null
    private var inviteAccept: Btn? = null
    private var sheetBtn: Btn? = null

    // ── views ──
    private val scroll = scrollPage(ctx)
    private val page = FrameLayout(ctx)
    private val overlay = FrameLayout(ctx)       // flash banner, floating gifts
    private val toast: Toast
    private var sheet: Sheet? = null
    private var sheetName = ""
    private var inviteSheet: Sheet? = null
    private val voice: GameVoice

    // game screen parts (built once per page)
    private var foundMe: TextView? = null
    private var foundPartner: TextView? = null
    private var statusLabel: TextView? = null
    private var clockBar: ClockBar? = null
    private var boardHint: TextView? = null
    private var board: Board? = null
    private var selectedLabel: TextView? = null
    private var toggles = ArrayList<TextView>()
    private var confirmBtn: Btn? = null
    private var revealBtn: Btn? = null
    private var micBtn: IconBtn? = null
    private var resultBox: LinearLayout? = null
    private var voiceDot: View? = null
    private var voiceText: TextView? = null
    private var voiceRight: FrameLayout? = null
    private var voiceRightKey = ""
    private var myAvatar: AvatarView? = null
    private var prAvatar: AvatarView? = null
    private var dotBeat: ValueAnimator? = null
    private var ticker: ValueAnimator? = null

    init {
        background = CssBg(0f, listOf(CssBg.Radial(0.5f, 0f, 0.9f, 0.4f, intArrayOf(G.c("#2C1168"), C.bg), floatArrayOf(0f, 0.7f))), fill = C.bg)
        scroll.addView(page, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        addView(scroll, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        addView(overlay, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        overlay.isClickable = false
        toast = Toast(this, G.round(G.ca("#140a2c", 0.96f), 18f, G.ca("#ffd477", 0.5f), 1f), Color.WHITE, 14f, true, 64f).padding(16f, 9f, 16f, 9f)
        voice = GameVoice(ctx, "heartChase") { bindVoice() }
        render()
        enter()
    }

    // ───────── server ─────────
    private fun fn(body: JSONObject, done: (app.truefind.android.Api.Result) -> Unit) = GameApi.call("heartChase", body, done)

    private fun applyState(v: JSONObject?) {
        if (v == null) return
        val prev = session
        if (prev != null && prev.str("id") == v.str("id") && v.long("version") < prev.long("version")) return
        val oldStatus = prev?.str("status").orEmpty()
        session = v
        onSession(oldStatus)
    }

    private fun clearSession() { session = null; pick.clear(); sel = null; seenGifts = 0; onSession("") }

    private fun enter() {
        boot = "loading"; render()
        GameApi.callRetry("heartChase", JSONObject().put("action", "enter")) { r ->
            if (!alive) return@callRetry
            if (!r.ok) {
                problem = JSONObject().put("code", r.data.str("code")).put("message", GameApi.err(r)).put("membership", r.data.str("needs_membership"))
                boot = "problem"; render(); return@callRetry
            }
            val d = r.data
            me = d.obj("me"); rules = d.obj("rules"); if (d.hasNum("balance")) balance = d.long("balance")
            boot = "ready"
            val s = d.obj("session")
            if (s != null) { applyState(s); screen = if (s.str("status") == "waiting") "search" else "game" }
            render()
            searchLoop()
        }
    }

    /** Everything that follows a new server state (the web's effects). */
    private fun onSession(oldStatus: String) {
        val s = session
        val st = s?.str("status").orEmpty()
        if (s != null) {
            if (st == "cancelled") {
                session = null; pick.clear(); sel = null
                screen = if (me.str("role") == "creator") "search" else "intro"
                stopPoll(); voice.setSession(null); render(); searchLoop()
                return
            }
            if (st != "waiting" && screen == "search") screen = "game"
            // the clock counts down from the server's own answer
            clockAt = System.currentTimeMillis(); clockLeft = s.long("turn_ms_left")
            // gifts that just arrived
            val gifts = s.arr("gifts").objs()
            if (gifts.size > seenGifts) {
                val fresh = gifts.drop(seenGifts)
                seenGifts = gifts.size
                fresh.forEach { g ->
                    val art = giftArt[g.str("id")] ?: return@forEach
                    floatGift(art.first, g.str("by") == "me")
                    if (g.str("by") == "partner") toast.show("${s.obj("partner").str("name").ifEmpty { "Your partner" }} sent you a ${art.second} · +${g.int("share")} earned")
                }
            }
            if (gifts.isEmpty()) seenGifts = 0
            // which board is on screen follows the turn
            if (st == "play") {
                val key = "$st|${s.str("turn")}"
                if (key != lastTurnKey) { lastTurnKey = key; view = if (s.str("turn") == "me") "theirs" else "mine" }
            } else lastTurnKey = ""
        }
        // voice: on from the match until the call ends
        voice.setSession(if (s != null && st in listOf("secret", "play", "over")) s.str("id") else null)
        pollLoop()
        render()
        if (oldStatus != st) skipCheck()
    }

    // ── polling: the server is the only truth (every 1.5 s, never two questions at once) ──
    private fun stopPoll() { pollRun++; pollId = "" }
    private fun pollLoop() {
        val s = session
        val live = s != null && s.str("status") in listOf("waiting", "secret", "play", "over")
        if (!live) { stopPoll(); return }
        val id = s!!.str("id")
        if (pollId == id) return
        pollId = id
        val run = ++pollRun
        fun beat() {
            if (!alive || run != pollRun) return
            val cur = session ?: return
            if (!polling) {
                polling = true
                fn(JSONObject().put("action", "state").put("session_id", cur.str("id"))) { r ->
                    polling = false
                    if (!alive || run != pollRun) return@fn
                    r.data.obj("session")?.let { applyState(it) }
                }
            }
            later(1500) { beat() }
        }
        beat()
    }

    // ── the boy's search / the girl's wait ──
    private fun searchLoop() {
        if (screen != "search" || session != null || boot != "ready") { searchRun++; return }
        if (searching) return
        searching = true
        val run = ++searchRun
        fun look() {
            if (!alive || run != searchRun) { searching = false; return }
            if (screen != "search" || session != null) { searching = false; return }
            fn(JSONObject().put("action", if (me.str("role") == "creator") "wait" else "search")) { r ->
                if (!alive || run != searchRun) { searching = false; return@fn }
                if (r.ok) {
                    if (r.data.hasNum("balance")) balance = r.data.long("balance")
                    val s = r.data.obj("session")
                    if (s != null) {
                        searching = false
                        applyState(s); screen = if (s.str("status") == "waiting") "search" else "game"; render(); return@fn
                    }
                    later(2500) { look() }
                } else {
                    searching = false
                    screen = "intro"
                    if (r.data.str("code") == "coins") { if (r.data.hasNum("balance")) balance = r.data.long("balance"); openSheet("coins") }
                    else toast.show(GameApi.err(r))
                    render()
                }
            }
        }
        look()
    }

    private fun act(action: String, extra: JSONObject = JSONObject(), done: (JSONObject?) -> Unit = {}) {
        val s = session ?: run { done(null); return }
        val body = JSONObject().put("action", action).put("session_id", s.str("id"))
        val keys = extra.keys(); while (keys.hasNext()) { val k = keys.next(); body.put(k, extra.get(k)) }
        fn(body) { r ->
            if (!alive) return@fn
            if (r.ok) {
                r.data.obj("session")?.let { applyState(it) }
                if (r.data.hasNum("balance")) { balance = r.data.long("balance"); bindHeaderCoins() }
                done(r.data)
            } else {
                r.data.obj("session")?.let { applyState(it) }
                if (r.data.str("code") == "coins") { if (r.data.hasNum("balance")) balance = r.data.long("balance"); openSheet("coins") }
                else toast.show(GameApi.err(r))
                done(null)
            }
        }
    }

    // ── actions ──
    private fun start() {
        val entry = entry()
        val bal = balance
        if (me.str("role") == "host" && rules != null && bal != null && bal < entry) { openSheet("coins"); return }
        screen = "search"; render(); searchLoop()
    }

    private fun leaveGame() {
        val s = session
        screen = "intro"; session = null; pick.clear(); sel = null; stopPoll(); searchRun++; searching = false
        voice.setSession(null)
        render()
        if (s != null) fn(JSONObject().put("action", if (s.str("status") == "over") "end" else "cancel").put("session_id", s.str("id"))) { enter() }
        else enter()
    }

    private fun confirmSecret() {
        if (pick.size != 4 || busy) return
        busy = true; bindGame()
        val cells = org.json.JSONArray(); pick.forEach { cells.put(it) }
        act("secret", JSONObject().put("cells", cells)) { busy = false; bindGame() }
    }

    private fun reveal() {
        val cell = sel ?: return
        if (busy) return
        busy = true; bindGame()
        act("reveal", JSONObject().put("cell", cell)) { d ->
            sel = null
            val last = d.obj("last")
            if (last != null) {
                val pn = session.obj("partner").str("name").ifEmpty { "her" }
                if (last.bool("hit")) flash(true, "HEART FOUND!", "You found one of $pn's boxes")
                else flash(false, "NOT FOUND – TRY AGAIN", "Box ${coord(last.int("cell"))} was empty")
            }
            busy = false; bindGame()
        }
    }

    private fun sendGift(id: String) { closeSheet(); act("gift", JSONObject().put("gift", id)) }

    // ── Secret Dating: the app's own invitation flow ──
    private fun sendSecret() {
        val s = session ?: return
        val pid = s.obj("partner").str("profile_id")
        if (pid.isEmpty() || sending) return
        sending = true; refreshSheet()
        GameApi.call("sendInvitation", JSONObject().put("to_profile_id", pid)) { r ->
            if (!alive) return@call
            if (r.ok) {
                val d = r.data
                val invId = d.str("invitation_id").ifEmpty { d.str("id") }.ifEmpty { d.obj("invitation").str("id") }
                act("invite_sent", JSONObject().put("invitation_id", invId)) { sending = false; openSheet("sent") }
            } else {
                sending = false
                val d = r.data
                when {
                    d.str("needs_membership") == "secret" -> openSheet("join")
                    d.str("error") == "no_credits" || r.status == 402 -> openSheet("offer")
                    d.str("error") == "verify_first" -> { closeSheet(); toast.show("Finish your verification in Secret Dating first.") }
                    else -> { closeSheet(); toast.show(GameApi.err(r)) }
                }
            }
        }
    }
    private fun claimOffer() {
        sending = true; refreshSheet()
        GameApi.call("claimIntroOffer", JSONObject()) { r ->
            if (!alive) return@call
            if (r.ok) { closeSheet(); sending = false; sendSecret() }
            else {
                sending = false
                if (r.data.str("error") == "offer_used") { closeSheet(); layer.openWeb("/secret-app") }
                else { refreshSheet(); toast.show(GameApi.err(r)) }
            }
        }
    }
    private fun answerSecret(accept: Boolean) {
        val s = session ?: return
        val invId = s.obj("invite").str("id")
        if (invId.isEmpty() || sending) return
        sending = true; refreshInvite()
        GameApi.call("respondInvitation", JSONObject().put("invitation_id", invId).put("action", if (accept) "accept" else "decline")) { r ->
            if (!alive) return@call
            if (r.ok) {
                val matchId = r.data.str("match_id")
                act("invite_done", JSONObject().put("accepted", accept).put("match_id", matchId)) {
                    sending = false
                    inviteSheet?.close(); inviteSheet = null
                    if (accept && matchId.isNotEmpty()) openChat(matchId)
                }
            } else { sending = false; refreshInvite(); toast.show(GameApi.err(r)) }
        }
    }
    private fun openChat(matchId: String) {
        val s = session
        val go = { layer.openWeb("/chat/$matchId") }
        if (s != null) {
            session = null; stopPoll(); voice.setSession(null)
            fn(JSONObject().put("action", "end").put("session_id", s.str("id"))) { go() }
        } else go()
    }
    private fun joinApp(app: String, then: () -> Unit) {
        GameApi.call("joinApp", JSONObject().put("app", app)) { r ->
            if (!alive) return@call
            if (r.ok) then() else toast.show(GameApi.err(r))
        }
    }

    // ── the clock ──
    private fun left(): Long = if (session.str("status") == "play") max(0L, clockLeft - (System.currentTimeMillis() - clockAt)) else 0L
    private fun skipCheck() {
        cancel(skipTimer); skipTimer = null
        val s = session ?: return
        if (s.str("status") != "play" || s.str("turn") != "me") return
        val l = left()
        skipTimer = later(l + 400) {
            val cur = session
            if (cur != null && cur.str("status") == "play" && cur.str("turn") == "me" && left() <= 0) act("skip")
        }
    }

    // ───────── render ─────────
    private fun render() {
        if (!alive) return
        val s = session
        val st = s?.str("status").orEmpty()
        val gameSt = when (st) { "secret" -> "secret"; "play" -> "play"; "over", "ended" -> "over"; else -> st }
        val key = "$boot|$screen|${if (screen == "game") gameSt else ""}|${me.str("role")}|${s?.str("id").orEmpty().isNotEmpty()}"
        if (key != pageKey) {
            pageKey = key; resultKey = ""
            buildPage()
        }
        bindGame()
        bindVoice()
        syncInvite()
        syncTicker()
    }

    private fun buildPage() {
        page.removeAllViews()
        foundMe = null; foundPartner = null; statusLabel = null; clockBar = null; boardHint = null; board = null; selectedLabel = null
        toggles.clear(); confirmBtn = null; revealBtn = null; micBtn = null; resultBox = null; voiceDot = null; voiceText = null; voiceRight = null; voiceRightKey = ""
        myAvatar = null; prAvatar = null
        dotBeat?.cancel(); dotBeat = null
        G.stopAll(anims)
        val ctx = context
        when {
            boot == "loading" -> {
                val box = FrameLayout(ctx)
                val heart = G.icon(ctx, "Heart", C.pink, 44f, 2f, C.pink)
                box.addView(heart, FrameLayout.LayoutParams(G.dpi(44f), G.dpi(44f), Gravity.CENTER))
                anims.add(G.beat(heart))
                page.addView(box, FrameLayout.LayoutParams(G.MATCH, scrollHeight()))
            }
            boot == "problem" -> buildProblem()
            screen == "intro" -> buildIntro()
            screen == "search" && (session == null || session.str("status") == "waiting") -> buildSearch()
            screen == "game" && session != null && session.str("status") != "waiting" -> buildGame()
            else -> page.addView(View(ctx), FrameLayout.LayoutParams(G.MATCH, scrollHeight()))
        }
    }

    private fun scrollHeight(): Int = if (height > 0) height else resources.displayMetrics.heightPixels

    private fun header(title: String, sub: String?, onBack: () -> Unit): View {
        val ctx = context
        val row = G.hbox(ctx, 10f)
        row.pad(14f, 10f, 14f, 6f)
        val back = FrameLayout(ctx).apply {
            background = G.oval(G.ca("#ffffff", 0.06f), C.line, 1f)
            contentDescription = "Back"
            addView(G.icon(ctx, "ChevronLeft", Color.WHITE, 23f), FrameLayout.LayoutParams(G.dpi(23f), G.dpi(23f), Gravity.CENTER))
        }
        G.click(back) { onBack() }
        row.addView(back, LinearLayout.LayoutParams(G.dpi(42f), G.dpi(42f)))
        val col = G.vbox(ctx)
        col.addView(G.text(ctx, title, 21f, Color.WHITE).lineH(1.15f))
        if (!sub.isNullOrEmpty()) col.addView(G.text(ctx, sub, 12.5f, C.muted, bold = false))
        row.addView(col, G.lpw(1f))
        if (balance != null && me.str("role") == "host") {
            val pill = G.hbox(ctx, 5f).apply {
                background = G.round(G.ca("#ffd477", 0.12f), 16f, G.ca("#ffd477", 0.4f), 1f)
                pad(12f, 0f, 12f, 0f)
            }
            pill.addView(G.icon(ctx, "Coins", C.gold, 15f))
            headerCoins = G.text(ctx, "${balance}", 14f, C.gold)
            pill.addView(headerCoins)
            row.addView(pill, LinearLayout.LayoutParams(G.WRAP, G.dpi(32f)))
        } else headerCoins = null
        return row
    }
    private fun bindHeaderCoins() { headerCoins?.text = "${balance ?: 0}" }

    private fun content(): LinearLayout = G.vbox(context, 14f).apply { pad(16f, 8f, 16f, 28f) }

    private fun buildProblem() {
        val ctx = context
        val col = G.vbox(ctx)
        col.addView(header("Heart Chase Boxes", null) { layer.close(this) })
        val body = content()
        body.addView(G.text(ctx, problem.str("message"), 17f, Color.WHITE, bold = false).lineH(1.4f))
        if (problem.str("membership") == "truefind") body.addView(Btn(ctx, "pink", "Join True Find") { joinApp("truefind") { enter() } }, btnLp())
        if (problem.str("code") == "profile") body.addView(Btn(ctx, "purple", "Complete my profile") { layer.openWeb("/profile/edit") }, btnLp())
        body.addView(Btn(ctx, "quiet", "Back to Games") { layer.close(this) }, btnLp())
        col.addView(body)
        page.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
    }

    private fun btnLp() = LinearLayout.LayoutParams(G.MATCH, G.dpi(52f))
    private fun entry(): Long = rules?.let { if (it.hasNum("entry")) it.long("entry") else 15L } ?: 15L

    private fun buildIntro() {
        val ctx = context
        val host = me.str("role") == "host"
        val col = G.vbox(ctx)
        col.addView(header("Heart Chase Boxes", "Find all 4 of your partner's hearts") { layer.close(this) })
        val body = content()
        // the little board picture
        val demo = Grid4(ctx, 8f, 260f, cols = 4)
        for (i in 0 until 8) {
            val hot = i == 5
            val tile = FrameLayout(ctx)
            tile.background = if (hot) CssBg(floatArrayOf(13f, 13f, 13f, 13f), listOf(CssBg.Radial(0.5f, 0.38f, 0f, 0f, intArrayOf(G.c("#FF86C2"), G.c("#B02FD0")), floatArrayOf(0f, 0.75f), circle = true)), border = G.ca("#ffffff", 0.5f), borderDp = 1.5f)
            else CssBg(13f, emptyList(), fill = G.c("#231350"), border = if (i % 2 == 1) G.ca("#FF4FA3", 0.5f) else G.ca("#38A8FF", 0.5f), borderDp = 1.5f)
            if (hot) G.shadow(tile, 8f, C.pink, 13f)
            val c = if (hot) Color.WHITE else if (i % 2 == 1) G.ca("#FF4FA3", 0.7f) else G.ca("#38A8FF", 0.7f)
            val hv = HeartIcon(ctx, c, if (hot) Color.WHITE else null)
            tile.addView(hv, FrameLayout.LayoutParams(G.MATCH, G.MATCH).apply { val m = G.dpi(14f); setMargins(m, m, m, m) })
            if (hot) anims.add(G.beat(hv))
            demo.addView(tile)
        }
        G.rise(demo)
        body.addView(demo, G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 2f))
        // how it works
        val info = G.vbox(ctx, 10f).apply { background = G.round(G.ca("#ffffff", 0.05f), 18f, C.line, 1f); pad(14f) }
        val turnS = ((rules?.let { if (it.hasNum("turn_ms")) it.long("turn_ms") else 15000L } ?: 15000L) / 1000.0).roundToInt()
        listOf("Users2" to "You are matched with a ${if (host) "girl" else "boy"} who is on this game right now",
            "Lock" to "Both of you secretly pick 4 of the 16 boxes",
            "Timer" to "$turnS seconds a turn · open one box on your partner's board",
            "Heart" to "Find all 4 first and you win",
            "Mic" to "The call stays on after the game, so you can talk").forEach { (ic, t) ->
            val row = G.hbox(ctx, 10f)
            row.addView(G.icon(ctx, ic, C.pink, 19f))
            row.addView(G.text(ctx, t, 15f, Color.WHITE, bold = false).lineH(1.3f), G.lpw(1f))
            info.addView(row, G.lp(G.MATCH, G.WRAP))
        }
        body.addView(info, G.lp(G.MATCH, G.WRAP))
        // coins
        val coins = G.vbox(ctx).apply { background = G.round(G.ca("#ffd477", 0.07f), 18f, G.ca("#ffd477", 0.35f), 1f); pad(14f) }
        if (host) {
            coins.addView(G.text(ctx, "Entry ${entry()} coins", 15f, C.gold).lineH(1.45f))
            coins.addView(G.text(ctx, "The entry is the same whoever wins — the game is just for fun. Your partner earns her share for every game.", 15f, Color.WHITE, bold = false).lineH(1.45f))
        } else {
            coins.addView(G.text(ctx, "Free for you", 15f, C.gold).lineH(1.45f))
            val share = rules?.let { if (it.hasNum("her_share")) it.long("her_share") else if (it.hasNum("her_lose")) it.long("her_lose") else 4L } ?: 4L
            val giftPct = Math.round((rules?.optDouble("gift_share", 1.0)?.takeIf { !it.isNaN() } ?: 1.0) * 100)
            coins.addView(G.text(ctx, "You earn $share coins for every game, whoever wins, plus $giftPct% of every gift he sends.", 15f, Color.WHITE, bold = false).lineH(1.45f))
        }
        body.addView(coins, G.lp(G.MATCH, G.WRAP))
        body.addView(Btn(ctx, "pink", if (host) "Find a partner · ${entry()} coins" else "Wait for a game", icon = "Play", iconFill = true) { start() }, btnLp())
        val bal = balance
        if (host && bal != null && bal < entry()) {
            val add = G.text(ctx, "You have $bal coins · add more", 14f, C.gold, bold = false).apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; gravity = Gravity.CENTER
            }
            G.click(add) { layer.openWeb("/wallet") }
            body.addView(add, G.lp(G.MATCH, G.WRAP))
        }
        col.addView(body)
        page.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
    }

    private fun buildSearch() {
        val ctx = context
        val host = me.str("role") == "host"
        val col = G.vbox(ctx)
        col.addView(header(if (host) "Finding a partner…" else "Waiting for a player…", "You can leave any time") { leaveGame() })
        val body = G.vbox(ctx, 18f, Gravity.CENTER_HORIZONTAL).apply { pad(16f) }
        val radar = FrameLayout(ctx)
        for (i in 0 until 3) {
            val ring = View(ctx).apply { background = G.oval(Color.TRANSPARENT, G.hex8("#FF4FA370"), 2f) }
            radar.addView(ring, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
            ping(ring, i * 800L)
        }
        val center = FrameLayout(ctx).apply {
            background = CssBg.ovalOf(listOf(CssBg.Radial(0.5f, 0.35f, 0f, 0f, intArrayOf(G.c("#FF86C2"), G.c("#F03C92")), null, circle = true)))
            G.shadow(this, 10f, C.pink)
            addView(G.icon(ctx, "Heart", Color.WHITE, 38f, 2f, Color.WHITE), FrameLayout.LayoutParams(G.dpi(38f), G.dpi(38f), Gravity.CENTER))
        }
        val halo = View(ctx).apply { background = G.oval(G.ca("#FF4FA3", 0.14f)) }
        radar.addView(halo, FrameLayout.LayoutParams(G.dpi(114f), G.dpi(114f), Gravity.CENTER))
        radar.addView(center, FrameLayout.LayoutParams(G.dpi(90f), G.dpi(90f), Gravity.CENTER))
        anims.add(G.beat(center)); anims.add(G.beat(halo))
        body.addView(radar, LinearLayout.LayoutParams(G.dpi(210f), G.dpi(210f)).m(t = 24f))
        body.addView(G.text(ctx, if (host) "Looking for a girl who is on this game right now." else "A player will be matched with you in a moment. Keep this screen open.", 15f, C.muted, bold = false).apply { gravity = Gravity.CENTER; maxWidth = G.dpi(280f) })
        body.addView(Btn(ctx, "quiet", "Cancel") { leaveGame() }, btnLp())
        col.addView(body)
        page.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
    }

    private fun buildGame() {
        val ctx = context
        val s = session ?: return
        val st = s.str("status")
        val pn = partnerName()
        val col = G.vbox(ctx, 10f)
        col.pad(14f, 10f, 14f, 18f)
        col.minimumHeight = scrollHeight()

        // players
        val players = G.hbox(ctx, 8f)
        val left = G.hbox(ctx, 8f)
        myAvatar = AvatarView(ctx, 44f, C.blue).also { it.setPhoto(me.str("photo")); left.addView(it, LinearLayout.LayoutParams(G.dpi(44f), G.dpi(44f))) }
        val lc = G.vbox(ctx)
        lc.addView(G.text(ctx, "You", 15f, Color.WHITE))
        foundMe = G.text(ctx, "", 12f, C.muted, bold = false).also { lc.addView(it) }
        left.addView(lc)
        players.addView(left, G.lpw(1f))
        statusLabel = G.text(ctx, "", 12f, C.muted, bold = false).apply { gravity = Gravity.CENTER }
        players.addView(statusLabel)
        val right = G.hbox(ctx, 8f, Gravity.CENTER_VERTICAL or Gravity.END)
        val rc = G.vbox(ctx, 0f, Gravity.END)
        rc.addView(G.text(ctx, pn, 15f, Color.WHITE).ellipsize1().apply { gravity = Gravity.END })
        foundPartner = G.text(ctx, "", 12f, C.muted, bold = false).also { it.gravity = Gravity.END; rc.addView(it) }
        right.addView(rc, G.lpw(1f))
        prAvatar = AvatarView(ctx, 44f, C.pink).also { it.setPhoto(s.obj("partner").str("photo")); right.addView(it, LinearLayout.LayoutParams(G.dpi(44f), G.dpi(44f))) }
        players.addView(right, G.lpw(1f))
        col.addView(players, G.lp(G.MATCH, G.WRAP))

        // turn clock
        if (st == "play") { clockBar = ClockBar(ctx).also { col.addView(it, G.lp(G.MATCH, G.dpi(38f))) } }

        // board
        val boardBox = FrameLayout(ctx)
        val bcol = G.vbox(ctx)
        bcol.pad(0f, 6f, 0f, 6f)
        boardHint = G.text(ctx, "", if (st == "secret") 15f else 14f, C.muted, bold = false).apply { gravity = Gravity.CENTER }
        bcol.addView(boardHint, G.lp(G.MATCH, G.WRAP).m(t = 2f, b = 10f))
        board = Board(ctx) { i -> onTile(i) }.also { bcol.addView(it, G.lp(G.MATCH, G.WRAP)) }
        if (st == "secret") {
            selectedLabel = G.text(ctx, "", 15f, Color.WHITE).apply { gravity = Gravity.CENTER }
            bcol.addView(selectedLabel, G.lp(G.MATCH, G.WRAP).m(t = 10f))
        } else {
            val tg = G.hbox(ctx, 8f, Gravity.CENTER)
            for (v in listOf("theirs", "mine")) {
                val b = G.text(ctx, if (v == "theirs") "$pn's board" else "My board", 13f, Color.WHITE).apply { gravity = Gravity.CENTER; tag = v; pad(14f, 0f, 14f, 0f) }
                G.click(b) { view = v; bindGame() }
                toggles.add(b)
                tg.addView(b, LinearLayout.LayoutParams(G.WRAP, G.dpi(30f)))
            }
            bcol.addView(tg, G.lp(G.MATCH, G.WRAP).m(t = 10f))
        }
        boardBox.addView(bcol, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        flashHost = boardBox
        col.addView(boardBox, G.lp(G.MATCH, G.WRAP))

        // bottom
        if (st == "secret") confirmBtn = Btn(ctx, "pink", "Confirm") { confirmSecret() }.also { col.addView(it, btnLp()) }
        if (st == "play") {
            val row = G.hbox(ctx, 8f)
            micBtn = IconBtn(ctx, "Mic", 48f) { voice.toggleMute() }.also { row.addView(it, LinearLayout.LayoutParams(G.dpi(48f), G.dpi(48f))) }
            revealBtn = Btn(ctx, "pink", "Reveal Box") { reveal() }.also { row.addView(it, G.lpw(1f, G.dpi(52f))) }
            row.addView(IconBtn(ctx, "Gift", 48f, iconColor = C.gold) { openSheet("gift") }, LinearLayout.LayoutParams(G.dpi(48f), G.dpi(48f)))
            col.addView(row, G.lp(G.MATCH, G.WRAP))
        }
        if (st == "over" || st == "ended") { resultBox = G.vbox(ctx, 12f, Gravity.CENTER_HORIZONTAL).also { col.addView(it, G.lp(G.MATCH, G.WRAP)) } }

        // the call keeps running (pinned to the bottom)
        if (st in listOf("secret", "play", "over")) {
            col.addView(View(ctx), G.lpw(1f, 0).apply { width = G.MATCH; height = 0; weight = 1f })
            val bar = G.hbox(ctx, 8f).apply { background = G.round(G.ca("#ffffff", 0.05f), 23f, C.line, 1f); pad(14f, 0f, 6f, 0f) }
            voiceDot = View(ctx).apply { background = G.oval(C.muted) }
            bar.addView(voiceDot, LinearLayout.LayoutParams(G.dpi(8f), G.dpi(8f)))
            voiceText = G.text(ctx, "", 13.5f, C.muted, bold = false).ellipsize1()
            bar.addView(voiceText, G.lpw(1f))
            voiceRight = FrameLayout(ctx)
            bar.addView(voiceRight, LinearLayout.LayoutParams(G.WRAP, G.WRAP))
            col.addView(bar, G.lp(G.MATCH, G.dpi(46f)))
        }
        page.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
    }

    private fun partnerName() = session.obj("partner").str("name").ifEmpty { "Partner" }

    private fun onTile(i: Int) {
        val s = session ?: return
        val st = s.str("status")
        if (st == "secret") {
            if (s.obj("mine").bool("ready")) return
            if (pick.contains(i)) pick.remove(i) else if (pick.size < 4) pick.add(i)
            bindGame()
        } else if (st == "play") {
            if (view == "mine" || s.str("turn") != "me" || busy) return
            sel = if (sel == i) null else i
            bindGame()
        }
    }

    /** Puts the current server state into the game screen (no rebuild). */
    private fun bindGame() {
        val s = session ?: return
        if (screen != "game") return
        val st = s.str("status")
        val pn = partnerName()
        val myTurn = s.str("turn") == "me"
        foundMe?.text = "${s.obj("theirs").int("found")}/4 found"
        foundPartner?.text = "${s.obj("ours").int("found")}/4 found"
        statusLabel?.text = when (st) { "secret" -> "Pick 4"; "play" -> "In play"; else -> "Game over" }
        val b = board ?: return
        if (st == "secret") {
            val ready = s.obj("mine").bool("ready")
            boardHint?.text = "Pick 4 secret boxes. Only you can see them."
            b.mine = true
            b.setCells(List(16) { i -> Cell("", pick.contains(i)) }, disabled = ready)
            selectedLabel?.text = "Selected: ${if (ready) 4 else pick.size} / 4"
            confirmBtn?.let {
                it.label = if (ready) "Waiting for $pn…" else "Confirm"
                it.isEnabled = pick.size == 4 && !busy && !ready
            }
        } else {
            boardHint?.text = when {
                view == "mine" -> "Your board — your hearts are the blue ones"
                st == "play" -> "$pn's board — tap a box, then Reveal"
                else -> "$pn's 4 boxes — the dashed ones stayed hidden"
            }
            val theirs = s.obj("theirs"); val ours = s.obj("ours")
            val cells: List<Cell> = if (view == "theirs") {
                val opened = theirs.arr("opened").ints(); val hits = theirs.arr("hits").ints(); val shown = theirs.arr("secret").ints()
                List(16) { i -> Cell(if (opened.contains(i)) (if (hits.contains(i)) "hit" else "miss") else if (shown.contains(i)) "left" else "", sel == i) }
            } else {
                val opened = ours.arr("opened").ints(); val hits = ours.arr("hits").ints(); val mine = s.obj("mine").arr("secret").ints()
                List(16) { i -> Cell(if (opened.contains(i)) (if (hits.contains(i)) "hit" else "miss") else "", mine.contains(i) && !opened.contains(i)) }
            }
            b.mine = view == "mine"
            b.setCells(cells, disabled = view == "mine" || !myTurn || st != "play" || busy)
            for (t in toggles) {
                val on = t.tag == view
                t.background = G.round(if (on) G.ca("#ffffff", 0.14f) else Color.TRANSPARENT, 15f, if (on) Color.WHITE else C.line, 1f)
            }
            revealBtn?.let {
                val cell = sel
                it.label = if (cell == null) "Reveal Box" else "Reveal ${coord(cell)}"
                it.isEnabled = myTurn && cell != null && !busy
            }
            micBtn?.let { it.setIcon(if (voice.muted) "MicOff" else "Mic"); it.active = !voice.muted; it.contentDescription = if (voice.muted) "Unmute" else "Mute" }
        }
        bindResult()
        bindClock()
    }

    private fun bindResult() {
        val box = resultBox ?: return
        val s = session ?: return
        val st = s.str("status")
        val res = s.obj("result")
        val inv = s.obj("invite")
        val key = "${res != null}|${s.str("match_id")}|${inv.str("status")}|${inv.str("from")}|$st|$sending|${s.obj("theirs").int("found")}"
        if (key == resultKey) return
        resultKey = key
        box.removeAllViews()
        val ctx = context
        val pn = partnerName()
        val host = me.str("role") == "host"
        val iWon = res.str("winner") == "me"
        box.addView(G.text(ctx, if (res != null) (if (iWon) "You Win!" else "$pn Wins") else "Game over", 34f, if (iWon) C.gold else G.c("#D8CCF5"), bold = true, italic = true).apply { gravity = Gravity.CENTER })
        if (res != null) {
            box.addView(G.text(ctx, if (iWon) "All 4 hearts found in ${res.int("tries")} tries" else "$pn found all 4 first", 15f, C.muted, bold = false).apply { gravity = Gravity.CENTER })
            val grid = G.hbox(ctx, 10f)
            val left = if (host) "Thanks for playing" else "+${res.int("her_earned")} coins earned"
            grid.addView(statBox(ctx, left, C.gold), G.lpw(1f))
            grid.addView(statBox(ctx, "${s.obj("theirs").int("found")}/4 hearts found", C.pink), G.lpw(1f))
            box.addView(grid, G.lp(G.MATCH, G.WRAP))
        }
        // gift moment
        if (host && st == "over") {
            val gm = G.vbox(ctx).apply { background = G.round(G.ca("#ffffff", 0.05f), 16f, C.line, 1f); pad(10f, 8f, 10f, 10f) }
            gm.addView(G.text(ctx, "Send $pn a gift", 13f, C.muted), G.lp(G.WRAP, G.WRAP).m(b = 7f))
            val row = G.hbox(ctx, 8f)
            for (id in quickGifts) {
                val b = G.hbox(ctx, 8f, Gravity.CENTER).apply { background = G.round(G.ca("#ffffff", 0.06f), 13f, C.line, 1.5f) }
                b.addView(G.text(ctx, giftArt[id]!!.first, 22f, Color.WHITE, bold = false))
                val price = G.hbox(ctx, 3f)
                price.addView(G.icon(ctx, "Coins", C.gold, 12f))
                price.addView(G.text(ctx, "${rules.obj("gifts").int(id)}", 14f, C.gold))
                b.addView(price)
                G.click(b) { sendGift(id) }
                row.addView(b, G.lpw(1f, G.dpi(46f)))
            }
            gm.addView(row, G.lp(G.MATCH, G.WRAP))
            box.addView(gm, G.lp(G.MATCH, G.WRAP))
        }
        // Secret Dating
        when {
            st == "over" && s.str("match_id").isNotEmpty() -> box.addView(Btn(ctx, "pink", "Open Secret Dating chat", icon = "MessageCircle") { openChat(s.str("match_id")) }, btnLp())
            st == "over" && host && inv.str("status") != "sent" -> box.addView(Btn(ctx, "pink", "Secret Date with $pn?", icon = "Lock") { openSheet("secret") }, btnLp())
            st == "over" && inv.str("status") == "sent" && inv.str("from") == "me" -> box.addView(G.text(ctx, "Invitation sent · waiting for $pn…", 14f, C.muted, bold = false).apply { gravity = Gravity.CENTER })
        }
        box.addView(Btn(ctx, "quiet", "End call", icon = "PhoneOff", iconColor = C.miss) { leaveGame() }, btnLp())
        G.rise(box)
    }

    private fun statBox(ctx: Context, t: String, color: Int): View =
        G.text(ctx, t, 15f, color).apply { gravity = Gravity.CENTER; background = G.round(G.ca("#ffffff", 0.06f), 16f, C.line, 1f); pad(10f) }

    private fun bindClock() {
        val bar = clockBar ?: return
        val s = session ?: return
        val myTurn = s.str("turn") == "me"
        val l = left()
        val turnMs = rules?.let { if (it.hasNum("turn_ms")) it.long("turn_ms") else 15000L } ?: 15000L
        val secs = ceil(l / 1000.0).toInt()
        bar.set(myTurn, if (myTurn) "Your turn" else "${partnerName()}'s turn", secs, (l.toFloat() / turnMs).coerceIn(0f, 1f), secs <= 5 && myTurn)
    }

    /** Smooth clock while a turn runs (the web redrew 4 times a second; here every frame, only the bar). */
    private fun syncTicker() {
        val run = session?.str("status") == "play" && screen == "game"
        if (run && ticker == null) {
            ticker = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { bindClock() }
                start()
            }
        } else if (!run && ticker != null) { ticker?.cancel(); ticker = null }
        skipCheck()
    }

    private fun bindVoice() {
        val dot = voiceDot ?: return
        val txt = voiceText ?: return
        val pn = partnerName()
        val live = voice.state == "live"
        dot.background = G.oval(when { live && !voice.reconnecting -> C.ok; voice.state == "error" -> C.miss; else -> C.muted })
        if (live && dotBeat == null) dotBeat = G.beat(dot)
        if (!live && dotBeat != null) { dotBeat?.cancel(); dotBeat = null; dot.scaleX = 1f; dot.scaleY = 1f }
        txt.text = when (voice.state) {
            "live" -> if (voice.reconnecting) "Weak network · reconnecting…" else "On call with $pn"
            "unavailable" -> "Voice is not switched on yet"
            "error" -> voice.error.ifEmpty { "The call couldn’t connect" }
            else -> "Connecting the call…"
        }
        val right = voiceRight ?: return
        val key = if (voice.state == "error") "retry" else "loud:${voice.loud}"
        if (key != voiceRightKey) {
            voiceRightKey = key
            right.removeAllViews()
            if (voice.state == "error") {
                val r = G.text(context, "Retry", 13f, G.c("#3A2400")).apply { gravity = Gravity.CENTER; background = G.round(C.gold, 16f); pad(12f, 0f, 12f, 0f) }
                G.click(r) { voice.retry() }
                right.addView(r, FrameLayout.LayoutParams(G.WRAP, G.dpi(32f)))
            } else {
                right.addView(IconBtn(context, "Volume2", 34f, iconSize = 17f) { voice.toggleLoud() }.also { it.active = voice.loud; it.contentDescription = "Speaker" },
                    FrameLayout.LayoutParams(G.dpi(34f), G.dpi(34f)))
            }
        }
        micBtn?.let { it.setIcon(if (voice.muted) "MicOff" else "Mic"); it.active = !voice.muted }
    }

    // ── flash banner (HEART FOUND / NOT FOUND) ──
    private fun flash(hit: Boolean, title: String, text: String) {
        val host = flashHost ?: return
        val ctx = context
        val box = G.vbox(ctx, 2f, Gravity.CENTER_HORIZONTAL).apply {
            background = if (hit) CssBg(20f, listOf(CssBg.Linear(180f, intArrayOf(G.ca("#D63BB8", 0.95f), G.ca("#5E17EB", 0.95f)))), border = G.c("#FF9BD0"), borderDp = 1.5f)
            else CssBg(20f, emptyList(), fill = G.ca("#340E2C", 0.95f), border = C.miss, borderDp = 1.5f)
            pad(16f, 12f, 16f, 12f)
            elevation = G.dp(6f)
        }
        box.addView(G.text(ctx, title, 22f, Color.WHITE).apply { letterSpacing = 0.03f; gravity = Gravity.CENTER })
        box.addView(G.text(ctx, text, 14f, Color.WHITE, bold = false).apply { gravity = Gravity.CENTER })
        val w = min(host.width * 0.92f, G.dp(310f)).toInt().coerceAtLeast(G.dpi(200f))
        host.addView(box, FrameLayout.LayoutParams(w, G.WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = (host.height * 0.08f).toInt() })
        G.rise(box)
        val old = flashHide
        if (old != null) cancel(old)
        flashHide = later(1800) { host.removeView(box) }
        // a previous banner still showing goes away now
        for (i in host.childCount - 2 downTo 1) host.getChildAt(i)?.let { if (it !== box) host.removeView(it) }
    }

    // ── floating gifts ──
    private fun floatGift(emoji: String, mine: Boolean) {
        val tv = G.text(context, emoji, 34f, Color.WHITE, bold = false)
        overlay.addView(tv, FrameLayout.LayoutParams(G.WRAP, G.WRAP))
        overlay.post {
            tv.x = overlay.width * (if (mine) 0.22f else 0.70f)
            tv.y = overlay.height * 0.46f
            tv.alpha = 0f; tv.scaleX = 0.6f; tv.scaleY = 0.6f
            // web hcFloat 1.9s ease-out: 0 → 15 % → 100 %, each step eased on its own (CSS keyframes)
            val out = android.view.animation.PathInterpolator(0f, 0f, 0.58f, 1f)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1900; interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    if (t < 0.15f) {
                        val k = out.getInterpolation(t / 0.15f)
                        tv.alpha = k; tv.translationY = -G.dp(14f) * k; val sc = 0.6f + 0.55f * k; tv.scaleX = sc; tv.scaleY = sc
                    } else {
                        val k = out.getInterpolation(((t - 0.15f) / 0.85f).coerceIn(0f, 1f))
                        tv.alpha = 1f - k
                        tv.translationY = -G.dp(14f) - (G.dp(150f) - G.dp(14f)) * k
                        val sc = 1.15f - 0.15f * k; tv.scaleX = sc; tv.scaleY = sc
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { overlay.removeView(tv) }
                })
                start()
            }
        }
    }

    // ───────── sheets ─────────
    private fun sheetBg() = CssBg(floatArrayOf(26f, 26f, 0f, 0f), emptyList(), fill = C.panel, border = C.line, borderDp = 1f)

    private fun closeSheet() { sheet?.close(); sheet = null; sheetName = ""; sheetBtn = null }
    private fun refreshSheet() {
        val b = sheetBtn ?: return
        when (sheetName) {
            "secret" -> { b.label = if (sending) "Sending…" else "Send invitation"; b.isEnabled = !sending }
            "offer" -> { b.label = if (sending) "Opening…" else "Unlock for 1 coin & send"; b.isEnabled = !sending }
        }
    }

    private fun openSheet(name: String) {
        if (!alive) return
        sheet?.close()
        sheetBtn = null
        val ctx = context
        val pn = partnerName()
        val s = Sheet(this, sheetBg(), G.ca("#080414", 0.66f), 16f, 16f, 16f, 18f, 12f, onClosed = { if (sheet == null) sheetName = "" })
        sheet = s; sheetName = name
        val p = s.panel
        fun head(t: String, closable: Boolean = true) {
            val row = G.hbox(ctx, 10f)
            row.addView(G.text(ctx, t, 21f, Color.WHITE), G.lpw(1f))
            if (closable) {
                val x = FrameLayout(ctx).apply {
                    background = G.oval(Color.TRANSPARENT, C.line, 1f); contentDescription = "Close"
                    addView(G.icon(ctx, "X", Color.WHITE, 16f), FrameLayout.LayoutParams(G.dpi(16f), G.dpi(16f), Gravity.CENTER))
                }
                G.click(x) { closeSheet() }
                row.addView(x, LinearLayout.LayoutParams(G.dpi(36f), G.dpi(36f)))
            }
            p.addView(row, G.lp(G.MATCH, G.WRAP))
        }
        fun para(t: String) = p.addView(G.text(ctx, t, 15.5f, G.c("#F4D9E8"), bold = false).lineH(1.45f), G.lp(G.MATCH, G.WRAP))
        fun link(t: String, f: () -> Unit) {
            val l = G.text(ctx, t, 14f, C.muted, bold = false).apply { gravity = Gravity.CENTER; paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; pad(6f) }
            G.click(l) { f() }
            p.addView(l, G.lp(G.MATCH, G.WRAP))
        }
        when (name) {
            "gift" -> {
                head("Send $pn a gift")
                val row = G.hbox(ctx, 6f, Gravity.TOP)
                val ids = rules.obj("gifts")?.keys()?.asSequence()?.toList() ?: giftArt.keys.toList()
                for (id in ids) {
                    val art = giftArt[id]
                    val b = G.vbox(ctx, 3f, Gravity.CENTER_HORIZONTAL).apply { background = G.round(G.ca("#ffffff", 0.05f), 14f, C.line, 1.5f); pad(2f, 8f, 2f, 6f) }
                    b.addView(G.text(ctx, art?.first ?: "", 26f, Color.WHITE, bold = false).lineH(1.5f).apply { gravity = Gravity.CENTER })
                    b.addView(G.text(ctx, art?.second ?: "", 12f, Color.WHITE).lineH(1.5f).apply { gravity = Gravity.CENTER })
                    val pr = G.hbox(ctx, 3f, Gravity.CENTER)
                    pr.addView(G.icon(ctx, "Coins", C.gold, 11f))
                    pr.addView(G.text(ctx, "${rules.obj("gifts").int(id)}", 9.6f, C.gold).lineH(1.5f)) // <small>: 80 % of 12 px
                    b.addView(pr)
                    G.click(b) { sendGift(id) }
                    row.addView(b, G.lpw(1f, G.MATCH))
                }
                p.addView(row, G.lp(G.MATCH, G.WRAP))
                p.addView(G.text(ctx, "$pn keeps 70% of every gift.", 13f, C.muted, bold = false).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP))
            }
            "secret" -> {
                head("Invite $pn to Secret Dating")
                para("You are still on the call. Talk it over, then send — her screen shows your invitation at once.")
                sheetBtn = Btn(ctx, "pink", if (sending) "Sending…" else "Send invitation") { sendSecret() }.also { it.isEnabled = !sending; p.addView(it, btnLp()) }
                link("Not now") { closeSheet() }
            }
            "sent" -> {
                head("Invitation sent")
                para("$pn sees it on her screen now. Stay on the call.")
                p.addView(Btn(ctx, "quiet", "OK") { closeSheet() }, btnLp())
            }
            "offer" -> {
                head("Open Secret Dating")
                val box = G.vbox(ctx).apply { background = G.round(G.ca("#ffd477", 0.08f), 16f, G.ca("#ffd477", 0.6f), 1.5f); pad(12f) }
                box.addView(G.text(ctx, "First-time offer · 1 coin", 17f, C.gold))
                box.addView(G.text(ctx, "Secret Dating for 1 day · 3 invitations · unlimited chat", 14f, G.c("#F4D9E8"), bold = false))
                p.addView(box, G.lp(G.MATCH, G.WRAP))
                sheetBtn = Btn(ctx, "pink", if (sending) "Opening…" else "Unlock for 1 coin & send") { claimOffer() }.also { it.isEnabled = !sending; p.addView(it, btnLp()) }
                link("See Secret Dating plans") { closeSheet(); layer.openWeb("/secret-app") }
            }
            "join" -> {
                head("Join Secret Dating")
                para("Secret Dating is where your private chat with $pn lives.")
                p.addView(Btn(ctx, "pink", "Join and send") { joinApp("secret") { closeSheet(); sendSecret() } }, btnLp())
            }
            "coins" -> {
                head("You need more coins")
                para("Heart Chase Boxes costs ${entry()} coins a game. You have ${balance ?: 0}.")
                p.addView(Btn(ctx, "purple", "Add coins") { closeSheet(); layer.openWeb("/wallet") }, btnLp())
            }
        }
    }

    /** Her invitation, right after the game (stays until she answers). */
    private fun syncInvite() {
        val s = session
        val inv = s.obj("invite")
        val want = s != null && inv.str("status") == "sent" && inv.str("from") == "partner" && s.str("match_id").isEmpty()
        if (want && inviteSheet == null) buildInvite()
        else if (!want && inviteSheet != null) { inviteSheet?.close(); inviteSheet = null }
    }
    private fun buildInvite() {
        val ctx = context
        val pn = partnerName()
        val s = Sheet(this, sheetBg(), G.ca("#080414", 0.66f), 16f, 16f, 16f, 18f, 12f, dismissable = false)
        inviteSheet = s
        val p = s.panel
        p.addView(G.text(ctx, "$pn invites you", 21f, Color.WHITE), G.lp(G.MATCH, G.WRAP))
        p.addView(G.text(ctx, "“Will you go on a Secret Date with me?” Secret Dating is free for you — if you accept, your private chat opens right away.", 15.5f, G.c("#F4D9E8"), bold = false).lineH(1.45f), G.lp(G.MATCH, G.WRAP))
        inviteAccept = Btn(ctx, "pink", "Accept") { answerSecret(true) }.also { p.addView(it, btnLp()) }
        val no = G.text(ctx, "Not now", 14f, C.muted, bold = false).apply { gravity = Gravity.CENTER; paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; pad(6f) }
        G.click(no) { answerSecret(false) }
        p.addView(no, G.lp(G.MATCH, G.WRAP))
    }
    private fun refreshInvite() { inviteAccept?.let { it.label = if (sending) "Opening…" else "Accept"; it.isEnabled = !sending } }

    // ───────── back / leave ─────────
    override fun onBack(): Boolean {
        if (sheet != null) { closeSheet(); return true }
        if (inviteSheet != null) return true // she answers first
        return false
    }

    /** Leaving the screen ends the game for both (same as the web's pagehide / unmount). */
    override fun leave() {
        val s = session
        session = null; stopPoll(); searchRun++
        voice.stop()
        if (s != null && s.str("status") in listOf("waiting", "secret", "play", "over")) {
            GameApi.call("heartChase", JSONObject().put("action", if (s.str("status") == "over") "end" else "cancel").put("session_id", s.str("id"))) { }
        }
    }

    override fun destroy() {
        ticker?.cancel(); ticker = null
        dotBeat?.cancel(); dotBeat = null
        voice.stop()
        super.destroy()
    }

    private fun coord(i: Int) = "${('A'.code + i / 4).toChar()}${i % 4 + 1}"

    // ───────── parts ─────────
    class Cell(val state: String, val selected: Boolean)

    /** The 4 × 4 board (max 340 wide, gap 8). */
    private inner class Board(ctx: Context, private val onTap: (Int) -> Unit) : Grid4(ctx, 8f, 340f, 4) {
        var mine = false
        private val tiles = ArrayList<Tile>()
        init { for (i in 0 until 16) { val t = Tile(ctx, coord(i)) { onTap(i) }; tiles.add(t); addView(t) } }
        fun setCells(cells: List<Cell>, disabled: Boolean) {
            for (i in 0 until 16) {
                val c = cells[i]
                tiles[i].set(c.state, c.selected, mine, disabled || c.state == "hit" || c.state == "miss")
            }
        }
    }

    /** One box: front face (heart outline), flips to a heart or an X when opened (web: .hc-tile). */
    private inner class Tile(ctx: Context, private val label: String, private val tap: () -> Unit) : View(ctx) {
        private var state = ""; private var selected = false; private var mine = false; private var disabledT = false
        private var flip = 0f      // 0 = front, 1 = back
        private var pop = 1f       // heart pop on a hit
        private var pressedT = false
        private var flipAnim: ValueAnimator? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = app.truefind.android.Ui.bold; textSize = G.dp(10f) }
        private val rect = RectF()
        init {
            cameraDistance = 8000f * resources.displayMetrics.density
            isClickable = true
            setOnClickListener { if (!disabledT) tap() }
        }
        override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, s) }

        fun set(st: String, sel: Boolean, isMine: Boolean, dis: Boolean) {
            val wasOpen = state == "hit" || state == "miss"
            val open = st == "hit" || st == "miss"
            val changed = st != state || sel != selected || isMine != mine
            state = st; selected = sel; mine = isMine; disabledT = dis
            isEnabled = !dis
            alpha = if (st == "left") 0.85f else 1f
            contentDescription = "Box $label" + when (st) { "hit" -> ", heart found"; "miss" -> ", empty"; else -> "" }
            if (open && !wasOpen) {
                // flip like the web (rotateY 180°, .6 s, a little bounce)
                flipAnim?.cancel()
                flipAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 600; interpolator = android.view.animation.PathInterpolator(0.35f, 1.25f, 0.5f, 1f)
                    addUpdateListener { val t = it.animatedValue as Float; rotationY = 180f * t; flip = if (rotationY >= 90f) 1f else 0f; invalidate() }
                    start()
                }
                if (st == "hit") ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 700; interpolator = android.view.animation.PathInterpolator(0.3f, 1.7f, 0.4f, 1f)
                    addUpdateListener { pop = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else if (!open && wasOpen) { flipAnim?.cancel(); flipAnim = null; rotationY = 0f; flip = 0f; pop = 1f }
            else if (open && flipAnim?.isRunning != true) { rotationY = 180f; flip = 1f }
            if (changed) invalidate()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!disabledT && state != "hit" && state != "miss") {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pressedT = true; animate().scaleX(0.94f).scaleY(0.94f).setDuration(110).start() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressedT = false; animate().scaleX(1f).scaleY(1f).setDuration(140).start() }
                }
            }
            return super.onTouchEvent(e)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = w * 0.22f
            val tc = if (mine) C.blue else C.pink
            if (flip >= 1f) {
                // back face (drawn mirrored so it reads the right way round)
                c.save(); c.scale(-1f, 1f, w / 2f, h / 2f)
                rect.set(0.75f, 0.75f, w - 0.75f, h - 0.75f)
                if (state == "hit") {
                    p.shader = RadialGradient(w * 0.5f, h * 0.38f, w * 0.75f, intArrayOf(G.c("#FF86C2"), G.c("#D43BB8"), G.c("#7A22E0")), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
                    c.drawRoundRect(rect, r, r, p); p.shader = null
                    sp.color = G.ca("#ffffff", 0.55f); sp.strokeWidth = G.dp(1.5f); sp.pathEffect = null
                    c.drawRoundRect(rect, r, r, sp)
                    val s = 0.52f * pop.coerceAtLeast(0f)
                    drawHeart(c, w / 2f, h / 2f, w * s, Color.WHITE, true)
                } else {
                    p.shader = null; p.color = G.c("#160D30"); c.drawRoundRect(rect, r, r, p)
                    sp.color = G.alpha(C.miss, 0x55 / 255f); sp.strokeWidth = G.dp(1.5f); sp.pathEffect = null
                    c.drawRoundRect(rect, r, r, sp)
                    drawX(c, w / 2f, h / 2f, w * 0.42f, C.miss)
                }
                c.restore()
                return
            }
            // front face
            rect.set(0.75f, 0.75f, w - 0.75f, h - 0.75f)
            val ghost = state == "left"
            if (selected) {
                // glow (web: box-shadow 0 0 18px)
                for (k in 3 downTo 1) {
                    p.shader = null; p.color = G.alpha(tc, 0.10f * k)
                    val g = G.dp(2.2f * (4 - k))
                    c.drawRoundRect(RectF(rect.left - g, rect.top - g, rect.right + g, rect.bottom + g), r + g, r + g, p)
                }
                p.color = tc; c.drawRoundRect(rect, r, r, p)
            } else {
                // drop (web: 0 3px 0 rgba(0,0,0,.35))
                p.shader = null; p.color = G.ca("#000000", 0.35f)
                c.drawRoundRect(RectF(rect.left, rect.top + G.dp(3f), rect.right, rect.bottom + G.dp(3f)), r, r, p)
                p.shader = LinearGradient(w * 0.37f, 0f, w * 0.63f, h, intArrayOf(if (mine) G.ca("#38A8FF", 0.3f) else G.ca("#FF4FA3", 0.26f), G.c("#170D36")), floatArrayOf(0f, 0.72f), Shader.TileMode.CLAMP)
                // under the gradient: the page colour, so the drop never shows through
                val under = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = G.c("#170D36") }
                c.drawRoundRect(rect, r, r, under)
                c.drawRoundRect(rect, r, r, p); p.shader = null
            }
            sp.strokeWidth = G.dp(1.5f)
            sp.color = when { ghost -> tc; selected -> Color.WHITE; mine -> G.ca("#38A8FF", 0.6f); else -> G.ca("#FF4FA3", 0.6f) }
            sp.pathEffect = if (ghost) DashPathEffect(floatArrayOf(G.dp(4f), G.dp(3f)), 0f) else null
            c.drawRoundRect(rect, r, r, sp)
            val ic = if (selected) Color.WHITE else if (mine) G.ca("#38A8FF", 0.85f) else G.ca("#FF4FA3", 0.85f)
            tp.color = ic; tp.alpha = (255 * 0.55f).toInt() * Color.alpha(ic) / 255
            c.drawText(label, G.dp(6f), G.dp(4f) + tp.textSize * 0.9f, tp)
            val fill = when { ghost -> G.alpha(tc, 0x99 / 255f); selected -> Color.WHITE; else -> null }
            drawHeart(c, w / 2f, h / 2f, w * 0.38f, ic, false, fill)
        }

        private fun drawHeart(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, solid: Boolean, fill: Int? = null) {
            val path = HEART_PATH
            c.save()
            c.translate(cx - size / 2f, cy - size / 2f)
            c.scale(size / 24f, size / 24f)
            if (solid || fill != null) { p.shader = null; p.style = Paint.Style.FILL; p.color = fill ?: color; c.drawPath(path, p) }
            if (!solid) {
                sp.pathEffect = null; sp.color = color; sp.strokeWidth = 2f; sp.strokeJoin = Paint.Join.ROUND; sp.strokeCap = Paint.Cap.ROUND
                c.drawPath(path, sp)
            }
            c.restore()
        }
        private fun drawX(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
            c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(size / 24f, size / 24f)
            sp.pathEffect = null; sp.color = color; sp.strokeWidth = 3f; sp.strokeCap = Paint.Cap.ROUND
            c.drawLine(18f, 6f, 6f, 18f, sp); c.drawLine(6f, 6f, 18f, 18f, sp)
            c.restore()
        }
    }

    /** Turn clock: the bar empties as the 15 seconds run out; glows red in my last 5 seconds. */
    private inner class ClockBar(ctx: Context) : FrameLayout(ctx) {
        private val fill = View(ctx)
        private val label = G.text(ctx, "", 15f, Color.WHITE)
        private val secsT = G.text(ctx, "", 14f, Color.WHITE).apply { background = G.round(G.ca("#000000", 0.3f), 9f); pad(7f, 1f, 7f, 1f) }
        private var hurry: ValueAnimator? = null
        private var lastKey = ""
        init {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, G.dp(19f)) }
            }
            fill.pivotX = 0f
            addView(fill, LayoutParams(G.MATCH, G.MATCH))
            val row = G.hbox(ctx, 8f, Gravity.CENTER)
            row.addView(label); row.addView(secsT)
            addView(row, LayoutParams(G.MATCH, G.MATCH))
        }
        fun set(myTurn: Boolean, text: String, secs: Int, frac: Float, hurryOn: Boolean) {
            val col = if (myTurn) C.blue else C.pink
            val key = "$myTurn|$text"
            if (key != lastKey) {
                lastKey = key
                background = G.round(G.alpha(col, 0x22 / 255f), 19f, col, 1.5f)
                fill.setBackgroundColor(G.alpha(col, 0x55 / 255f))
                label.text = text
            }
            val s = "$secs"
            if (secsT.text.toString() != s) secsT.text = s
            fill.scaleX = frac
            if (hurryOn && hurry == null) {
                hurry = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 500; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                    interpolator = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f) // web hcHurry .5s ease-in-out alternate
                    addUpdateListener { this@ClockBar.elevation = G.dp(10f) * (it.animatedValue as Float) }
                    start()
                }
                if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = C.miss; outlineAmbientShadowColor = C.miss }
            } else if (!hurryOn && hurry != null) { hurry?.cancel(); hurry = null; elevation = 0f }
        }
        override fun onDetachedFromWindow() { hurry?.cancel(); hurry = null; super.onDetachedFromWindow() }
    }

    /** Pill buttons of the game (pink / purple / ghost / quiet), 52 high. */
    inner class Btn(ctx: Context, private val kind: String, text: String, icon: String? = null, iconFill: Boolean = false, iconColor: Int? = null, private val f: () -> Unit) : LinearLayout(ctx) {
        private val tv = G.text(ctx, text, 18f, if (kind == "ghost") C.gold else Color.WHITE)
        var label: String
            get() = tv.text.toString()
            set(v) { if (tv.text.toString() != v) tv.text = v }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            G.gap(this, 8f)
            background = when (kind) {
                "pink" -> CssBg(26f, listOf(CssBg.Linear(180f, intArrayOf(G.c("#FF6BB3"), G.c("#F03C92")))))
                "purple" -> CssBg(26f, listOf(CssBg.Linear(180f, intArrayOf(G.c("#7A3BFF"), G.c("#5E17EB")))))
                "ghost" -> CssBg(26f, emptyList(), border = C.gold, borderDp = 1.5f)
                else -> G.round(G.ca("#ffffff", 0.08f), 26f)
            }
            if (icon != null) addView(G.icon(ctx, icon, iconColor ?: Color.WHITE, 18f, 2f, if (iconFill) Color.WHITE else null))
            addView(tv)
            G.click(this) { f() }
        }
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.45f }
    }

    /** Round icon button (mic, gift, speaker). [active] false → red outline and icon (web IconBtn). */
    inner class IconBtn(ctx: Context, private var iconName: String, sizeDp: Float, private val iconSize: Float = 20f, private val iconColor: Int? = null, f: () -> Unit) : FrameLayout(ctx) {
        private val img = android.widget.ImageView(ctx)
        var active = true
            set(v) { if (field != v) { field = v; paint() } }
        init {
            addView(img, LayoutParams(G.dpi(iconSize), G.dpi(iconSize), Gravity.CENTER))
            paint()
            G.click(this) { f() }
        }
        fun setIcon(name: String) { if (name != iconName) { iconName = name; paint() } }
        private fun paint() {
            background = G.oval(G.ca("#ffffff", 0.07f), if (active) C.line else G.alpha(C.miss, 0x80 / 255f), 1f)
            img.setImageDrawable(SvgDrawable(Svg.icon(iconName, if (active) (iconColor ?: Color.WHITE) else C.miss), iconSize))
        }
    }

    private fun ping(v: View, delay: Long) {
        v.scaleX = 0.32f; v.scaleY = 0.32f; v.alpha = 1f
        anims.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400; repeatCount = ValueAnimator.INFINITE; startDelay = delay
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { val t = it.animatedValue as Float; val s = 0.32f + 0.68f * t; v.scaleX = s; v.scaleY = s; v.alpha = 1f - t }
            start()
        })
    }

    companion object {
        val HEART_PATH: Path = PathParser.createPathFromPathData(HubArt.HEART)
    }
}

/** A heart outline (optionally filled) that scales with its box. */
class HeartIcon(ctx: Context, private val color: Int, private val fill: Int?) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        c.save(); c.translate((width - s) / 2f, (height - s) / 2f); c.scale(s / 24f, s / 24f)
        if (fill != null) { p.style = Paint.Style.FILL; p.color = fill; c.drawPath(HeartChaseScreen.HEART_PATH, p) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = color; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
        c.drawPath(HeartChaseScreen.HEART_PATH, p)
        c.restore()
    }
}

/** Square tiles in [cols] columns with a gap, at most [maxWdp] wide, centred (CSS grid). */
open class Grid4(ctx: Context, private val gapDp: Float, private val maxWdp: Float, private val cols: Int = 4) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val w = min(avail, G.dpi(maxWdp))
        val gap = G.dpi(gapDp)
        val cell = (w - gap * (cols - 1)) / cols
        val rows = (childCount + cols - 1) / cols
        val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(avail, rows * cell + max(0, rows - 1) * gap)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = r - l
        val w = min(avail, G.dpi(maxWdp))
        val gap = G.dpi(gapDp)
        val cell = (w - gap * (cols - 1)) / cols
        val x0 = (avail - (cell * cols + gap * (cols - 1))) / 2
        for (i in 0 until childCount) {
            val x = x0 + (i % cols) * (cell + gap); val y = (i / cols) * (cell + gap)
            getChildAt(i).layout(x, y, x + cell, y + cell)
        }
    }
}
