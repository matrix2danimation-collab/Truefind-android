package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Mood Changer — native port of MoodChanger.jsx (+ mcScreensPre / mcCall / mcScreensPost).
 * The boy searches and moves the questions; the girl waits, joins, shares her mood and follows.
 * The moodChanger server function owns every rule; this screen only shows its state.
 */
@SuppressLint("ViewConstructor")
class MoodChangerScreen(ctx: Context, layer: GamesLayer) : GameScreen(ctx, layer) {

    companion object {
        val OPEN = listOf("waiting", "mood", "reveal", "call", "final", "td")
        val VOICE_ON = listOf("call", "final", "td")
        val NO_SECRET_AFTER = listOf("reported", "discomfort", "done", "chat")
        const val SEARCH_MS = 90_000L
        var agreed = false // web: sessionStorage tf_mc_agree_v1 (this app run)
    }

    private val prefs = ctx.getSharedPreferences("games", Context.MODE_PRIVATE)

    // ── state ──
    var boot = "loading"; private set
    private var problem: Array<String>? = null // title, body, primary, go
    var me: JSONObject? = null; private set
    private var history = ArrayList<McData.Hist>()
    private var screen = "intro" // intro | rules | guidelines | search
    var session: JSONObject? = null; private set
    private var searchSince = System.currentTimeMillis()
    private var searchChecked = false
    private var timedOut = false
    private var moodStep = "matched"
    // mine
    private var myMood: String? = null; private var myInt = 3; private val myPrefs = ArrayList<String>(); private var myReason: String? = null
    private var finMood: String? = null; private var finInt = 3
    private var busy = false
    private var formErr = ""
    var lang = if (prefs.getString("tf_mc_lang", "hi") == "en") "en" else "hi"; private set
    private var sheet: Sheet? = null
    var checkin: String? = null
    var reporting = false; private set
    private var reported = false
    private var skipFinal = false
    private var offer: JSONObject? = null
    private var offerLoading = false
    private var secretBusy = false
    private var secretNote: JSONObject? = null // kind: coins | error | connected, text, match_id
    var callStartLocal = System.currentTimeMillis(); private set
    private var everLive = ""
    private val prepared = HashSet<Int>()
    private var starting = false
    private var lastSid = ""
    private var prevStatus = ""
    private var pollRun = 0; private var pollId = ""
    private var searchRun = 0; private var searchingNow = false
    private var renderKey = ""
    private var callView: McCallView? = null
    private var revealAuto: Runnable? = null
    private var revealLeftT: TextView? = null
    private var revealStart = 0L
    private var revealFired = false
    private var matchingTimer: TextView? = null
    private var matchTick: Runnable? = null

    val voice: GameVoice
    private val host = FrameLayout(ctx)
    private val toastV: LinearLayout
    private val toastT: TextView
    private var toastHide: Runnable? = null

    val isHost get() = me.str("role") == "host"
    val status get() = session?.str("status").orEmpty()

    init {
        setBackgroundColor(MC.bg)
        isClickable = true
        addView(host, LayoutParams(G.MATCH, G.MATCH))
        toastV = G.hbox(ctx, 10f).apply {
            background = G.round(G.c("#1F1535"), 14f); pad(14f, 12f, 14f, 12f); elevation = G.dp(12f); visibility = View.GONE
        }
        toastV.addView(G.icon(ctx, "Info", G.c("#C9A6FF"), 16f))
        toastT = G.text(ctx, "", MC.SM, Color.WHITE, false).lineH(1.4f)
        toastV.addView(toastT, G.lpw(1f))
        addView(toastV, LayoutParams(G.MATCH, G.WRAP, Gravity.TOP).m(16f, 62f, 16f, 0f))
        voice = GameVoice(ctx, "moodChanger", "Allow the microphone so you can talk in Mood Changer.") { onVoice() }
        voice.onPartnerLeft = { pollNow() }
        render()
        enter()
    }

    // ───────── helpers ─────────
    fun toast(m: String) {
        toastT.text = m
        toastV.bringToFront()
        toastV.animate().cancel()
        if (toastV.visibility != View.VISIBLE) { toastV.visibility = View.VISIBLE; toastV.alpha = 0.4f; toastV.translationY = G.dp(40f) }
        toastV.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(MC.EASE).start() // .toast { animation: mcUp .22s ease }
        cancel(toastHide)
        toastHide = later(3800) { toastV.animate().cancel(); toastV.visibility = View.GONE } // the web removes it at once
    }
    fun setLang(l: String) { lang = l; prefs.edit().putString("tf_mc_lang", l).apply() }
    private fun fn(body: JSONObject, done: (app.truefind.android.Api.Result) -> Unit) = GameApi.call("moodChanger", body, done)
    /** web errMsg(e, f): the server's "error", else [f] */
    private fun errMsg(r: app.truefind.android.Api.Result, f: String = "Something went wrong. Please try again.") = r.data.str("error").ifEmpty { f }
    /** web: d.message || d.error || fallback */
    private fun secretErr(r: app.truefind.android.Api.Result) = r.data.str("message").ifEmpty { r.data.str("error") }.ifEmpty { "Couldn’t send the request. Please try again." }

    private fun applyState(v: JSONObject?) {
        if (v == null) return
        val prev = session
        if (prev != null && prev.str("id") == v.str("id") && v.long("version") < prev.long("version")) return
        session = v
        afterSession()
    }
    private fun clearSession() { session = null; afterSession() }

    /** like the web's act(): returns the new session (or null) */
    fun act(action: String, extra: JSONObject = JSONObject(), done: (JSONObject?) -> Unit = {}) {
        val s = session ?: run { done(null); return }
        val body = JSONObject().put("action", action).put("session_id", s.str("id"))
        val keys = extra.keys(); while (keys.hasNext()) { val k = keys.next(); body.put(k, extra.get(k)) }
        fn(body) { r ->
            if (!alive) return@fn
            val ns = r.data.obj("session")
            if (ns != null) applyState(ns)
            if (r.ok) done(ns) else {
                if (r.status != 409 || ns == null) toast(errMsg(r))
                done(null)
            }
        }
    }

    // ── start: who am I, is there a game to resume ──
    private fun enter() {
        boot = "loading"; render()
        fn(JSONObject().put("action", "enter")) { r ->
            if (!alive) return@fn
            if (r.ok) {
                val d = r.data
                me = d.obj("me")
                history = ArrayList(d.arr("history").objs().map { McData.Hist(it.str("mood"), it.int("intensity"), it.str("final_mood").ifEmpty { null }, if (it.hasNum("final_intensity")) it.int("final_intensity") else null) })
                val s = d.obj("session")
                boot = "ready"
                if (s != null) { session = s; if (s.str("status") == "waiting") screen = "search"; afterSession() } else render()
                // test pictures only (GamesDemo): open the 2nd mood question with answers filled in
                if (GameApi.demo) when (GamesDemo.scene) {
                    "mc-pick" -> { moodStep = "pick"; myMood = "stressed"; render() }
                    "mc-detail" -> { moodStep = "detail"; myMood = "stressed"; myInt = 4; myPrefs.add("listen"); render() }
                    "mc-reason" -> { moodStep = "reason"; myMood = "stressed"; myInt = 4; myPrefs.add("listen"); render() }
                    "mc-rules" -> { screen = "rules"; render() }
                    "mc-guidelines" -> { screen = "guidelines"; render() }
                }
                searchLoop()
            } else {
                val d = r.data
                problem = when {
                    d.str("error") == "banned" -> arrayOf("You can’t play right now", "Your account is restricted. Please contact True Find support.", "", "")
                    d.str("code") == "profile" -> arrayOf("Complete your profile first", "Add your name, gender, date of birth and city so we can match you.", "Open my profile", "profile")
                    d.str("code") == "age" -> arrayOf("Mood Changer is 18+", "This game is only for adults.", "", "")
                    d.str("code") == "membership" -> arrayOf("Join True Find to play", "Mood Changer is part of True Find. Join, then start a game.", "Join True Find", "join")
                    r.status == 404 && d.str("error").isEmpty() -> arrayOf("Mood Changer is getting ready", "The game’s server part isn’t live yet. Please try again in a little while.", "Try again", "retry")
                    else -> arrayOf("Couldn’t open Mood Changer", errMsg(r, "Check your internet and try again."), "Try again", "retry")
                }
                boot = "problem"; render()
            }
        }
    }
    private fun solveProblem() {
        val p = problem ?: return
        when (p[3]) {
            "profile" -> { layer.openWeb("/profile/edit"); return }
            "join" -> { GameApi.call("joinApp", JSONObject().put("app", "truefind")) { r -> if (!alive) return@call; if (r.ok) { problem = null; enter() } else toast(errMsg(r)) }; return }
        }
        problem = null; enter()
    }

    /** everything that follows a new server state (the web's effects) */
    private fun afterSession() {
        val s = session
        val sid = s?.str("id").orEmpty()
        if (sid != lastSid) {
            lastSid = sid
            moodStep = "matched"; myMood = null; myInt = 3; myPrefs.clear(); myReason = null
            finMood = null; finInt = 3; checkin = null; reported = false; skipFinal = false; formErr = ""
            offer = null; offerLoading = false; secretBusy = false; secretNote = null; prepared.clear()
            revealFired = false
        }
        val st = status
        // a little buzz for the girl waiting when a player joins
        if (prevStatus == "waiting" && st == "mood") buzz()
        prevStatus = st
        // polling
        pollLoop()
        // the boy's phone asks the AI for the next round in the background
        if (isHost && s != null) {
            var level = 0
            if (st == "reveal") level = 1
            else if (st == "call" && s.int("round") >= 1 && s.int("round") < 7) level = s.int("round") + 1
            if (level > 0 && prepared.add(level)) fn(JSONObject().put("action", "prepare").put("session_id", sid).put("level", level)) { }
        }
        // voice
        val voiceActive = s != null && st in VOICE_ON
        voice.setSession(if (voiceActive) sid else null)
        // call timer from the server's clock
        if (voiceActive) {
            val est = System.currentTimeMillis() - s!!.long("seconds") * 1000
            if (abs(callStartLocal - est) > 3000) callStartLocal = est
        }
        // the Reconnect offer (the boy, after his final mood)
        if (wantOffer() && !offerLoading) {
            offerLoading = true
            fn(JSONObject().put("action", "secret_info").put("session_id", sid)) { r ->
                if (!alive || lastSid != sid) return@fn
                offer = r.data.obj("secret_offer") ?: JSONObject().put("can", false).put("why", "error")
                render()
            }
        }
        searchLoop()
        render()
    }
    private fun secretOpen(): Boolean {
        val s = session ?: return false
        return s.bool("started") && (status in OPEN || s.obj("ended").str("reason") !in NO_SECRET_AFTER)
    }
    private fun wantOffer() = isHost && secretOpen() && session.obj("mine").obj("final") != null && session.obj("secret") == null && offer == null

    private fun buzz() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(160, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(160)
        } catch (e: Throwable) { }
    }

    // ── keep both phones in step: check in with the server every 2–3 s ──
    private var pollTimer: Runnable? = null
    private var pollBusyRun = -1 // the poll chain whose question is still on its way (web: inflight)
    private fun pollMs() = if (status == "waiting") 3000L else 2000L
    private fun pollLoop() {
        val s = session
        val id = if (s != null && status in OPEN) s.str("id") else ""
        if (id == pollId) return
        pollRun++; cancel(pollTimer); pollTimer = null
        pollId = id
        if (id.isEmpty()) return
        val run = pollRun
        pollTimer = later(500) { tick(run) }
    }
    private fun tick(run: Int) {
        cancel(pollTimer); pollTimer = null
        if (!alive || run != pollRun) return
        if (pollBusyRun == run) { pollTimer = later(pollMs()) { tick(run) }; return }
        pollBusyRun = run
        fn(JSONObject().put("action", "state").put("session_id", pollId)) { r ->
            if (pollBusyRun == run) pollBusyRun = -1
            if (!alive || run != pollRun) return@fn
            r.data.obj("session")?.let { applyState(it) }
            if (run == pollRun && pollTimer == null) pollTimer = later(pollMs()) { tick(run) }
        }
    }
    private fun pollNow() { if (pollId.isNotEmpty()) tick(pollRun) }

    // ── matchmaking ──
    private fun closed() = session != null && status !in OPEN
    private fun requeue(): Boolean { val s = session ?: return false; return closed() && !s.bool("started") && s.obj("ended").str("reason") in listOf("busy", "stale") }
    private fun wantSearch() = boot == "ready" && screen == "search" && !timedOut && (session == null || requeue()) && me != null
    private fun searchLoop() {
        if (!wantSearch()) { if (searchingNow) { searchRun++; searchingNow = false }; return }
        if (searchingNow) return
        searchingNow = true
        val run = ++searchRun
        val t0 = System.currentTimeMillis()
        fun stop() { if (run == searchRun) { searchingNow = false; searchRun++ } }
        fun look() {
            if (!alive || run != searchRun) return
            if (!wantSearch()) { stop(); return }
            fn(JSONObject().put("action", if (isHost) "search" else "wait")) { r ->
                if (!alive || run != searchRun) return@fn
                if (r.ok) {
                    searchChecked = true
                    val s = r.data.obj("session")
                    if (s != null) { stop(); applyState(s); return@fn }
                    if (isHost && System.currentTimeMillis() - t0 > SEARCH_MS) { stop(); timedOut = true; render(); return@fn }
                } else {
                    if (r.data.str("code") == "busy") { stop(); toast(r.data.str("error")); screen = "intro"; render(); return@fn }
                    searchChecked = true
                }
                render()
                later(3000) { look() }
            }
        }
        look()
    }
    private fun startSearch() {
        session = null
        timedOut = false; searchChecked = false; searchSince = System.currentTimeMillis()
        screen = "search"
        afterSession()
    }
    private fun onStart() { if (!agreed) { screen = "rules"; render(); return }; startSearch() }
    private fun cancelSearch() {
        val s = session
        screen = "intro"
        searchRun++; searchingNow = false
        if (s != null && s.str("status") in OPEN) act("cancel", JSONObject().put("reason", "left")) { clearSession() } else { clearSession() }
    }
    private fun leaveGame() {
        sheet?.close(); sheet = null
        act("cancel", JSONObject().put("reason", "left")) { screen = "intro"; clearSession() }
    }
    fun backToGames() = layer.close(this)
    private fun playAgain() = startSearch() // web: clearSession(); startSearch() — startSearch clears it too

    // ── the three mood questions ──
    private fun submitMood() {
        val m = myMood ?: return
        if (busy) return
        busy = true; formErr = ""; render()
        val pr = JSONArray(); myPrefs.forEach { pr.put(it) }
        act("mood", JSONObject().put("mood", m).put("intensity", myInt).put("prefs", pr).put("reason", myReason ?: "skip")) { r ->
            busy = false
            if (r == null) formErr = "Couldn’t save. Check your internet and try again."
            render()
        }
    }
    private fun startGame() {
        if (starting) return
        starting = true; render()
        act("start") { starting = false; render() }
    }
    fun endCall(reason: String) = act("end", JSONObject().put("reason", reason))
    fun claimCheckin(kind: String) { act("checkin", JSONObject().put("kind", kind)) { r -> if (r != null && r.bool("checkin_allowed")) { checkin = kind; render() } } }
    fun onReport(reason: String) {
        val s = session ?: return
        val pid = s.obj("partner").str("profile_id")
        if (pid.isEmpty() || reporting) return
        reporting = true; render()
        GameApi.call("moderateUser", JSONObject().put("action", "report").put("profile_id", pid).put("reason", reason)
            .put("details", "Reported during a Mood Changer voice game: $reason.").put("app", "truefind")) { r ->
            if (!alive) return@call
            if (r.ok) reported = true else toast(errMsg(r, "Couldn’t send the report. The call is ending."))
            act("end", JSONObject().put("reason", "reported")) { reporting = false; render() }
        }
    }
    private fun submitFinal() {
        val m = finMood ?: return
        if (busy) return
        busy = true; render()
        act("final", JSONObject().put("mood", m).put("intensity", finInt)) { r ->
            busy = false
            if (r != null) {
                val mm = r.obj("mine").obj("mood")
                history.add(McData.Hist(mm.str("mood"), mm.int("intensity"), m, finInt))
            }
            render()
        }
    }

    // ── Reconnect request: the boy sends it before the call ends, the girl answers it ──
    private fun sendSecret() {
        val s = session ?: return
        val o = offer ?: return
        val pid = s.obj("partner").str("profile_id")
        if (pid.isEmpty() || secretBusy) return
        secretBusy = true; secretNote = null; render()
        val done = { secretBusy = false; render() }
        fun send() {
            GameApi.call("sendInvitation", JSONObject().put("to_profile_id", pid).put("mood_session_id", s.str("id"))) { r ->
                if (!alive) return@call
                if (!r.ok) { secretNote = JSONObject().put("kind", "error").put("text", secretErr(r)); done(); return@call }
                val d = r.data
                if (d.bool("already_matched") || d.bool("already_invited")) { secretNote = JSONObject().put("kind", "connected").put("match_id", d.str("match_id")); done(); return@call }
                act("invite_sent", JSONObject().put("invitation_id", d.str("invitation_id"))) { after ->
                    if (after == null) secretNote = JSONObject().put("kind", "error").put("text", "Your request was sent, but the game couldn’t show it. You’ll find it in Reconnect.")
                    done()
                }
            }
        }
        fun claim() {
            if (o.str("mode") != "offer") { send(); return }
            GameApi.call("claimIntroOffer", JSONObject()) { r ->
                if (!alive) return@call
                if (r.ok) { send(); return@call }
                val err = r.data.str("error")
                if (err == "insufficient_coins") { secretNote = JSONObject().put("kind", "coins"); done(); return@call }
                if (err == "offer_used") { send(); return@call } // already used: the request goes out free instead
                secretNote = JSONObject().put("kind", "error").put("text", secretErr(r)); done()
            }
        }
        if (!o.bool("member")) {
            GameApi.call("joinApp", JSONObject().put("app", "secret")) { r ->
                if (!alive) return@call
                if (r.ok) claim() else { secretNote = JSONObject().put("kind", "error").put("text", secretErr(r)); done() }
            }
        } else claim()
    }
    private fun skipSecret() {
        if (secretBusy) return
        secretBusy = true; render()
        act("secret_skip") { offer = JSONObject().put("can", false).put("why", "skipped"); secretBusy = false; render() }
    }
    private fun answerSecret(accept: Boolean) {
        val sv = session.obj("secret")
        val inv = sv.str("invitation_id")
        if (inv.isEmpty() || secretBusy) return
        secretBusy = true; render()
        GameApi.call("respondInvitation", JSONObject().put("invitation_id", inv).put("action", if (accept) "accept" else "decline")) { r ->
            if (!alive) return@call
            if (!r.ok && !Regex("already handled", RegexOption.IGNORE_CASE).containsMatchIn(r.data.str("error"))) { toast(errMsg(r)); secretBusy = false; render(); return@call }
            act("invite_done") { secretBusy = false; render() }
        }
    }
    /** the chat lives in Reconnect: the game ends for both, then the chat opens */
    private fun openChat(matchId: String) {
        if (matchId.isEmpty()) return
        val s = session
        if (s != null && s.str("status") in OPEN) act("end", JSONObject().put("reason", "chat")) { layer.openWeb("/chat/$matchId") }
        else layer.openWeb("/chat/$matchId")
    }

    // ── voice ──
    private fun onVoice() {
        val s = session
        if (voice.state == "live" && s != null) everLive = s.str("id")
        callView?.bindVoice()
        // the "Connecting…" screen turns into the call
        if (status == "call") render()
        miniCall?.let { bindMini(it) }
    }

    // ───────── render ─────────
    private fun shapeKey(): String {
        val s = session
        val st = status
        val pDone = s.obj("partner").bool("mood_done")
        val hasMine = s.obj("mine").obj("mood") != null
        val wasLive = everLive.isNotEmpty() && everLive == s?.str("id")
        val callPhase = if (st == "call") (if (!wasLive && voice.state != "live") "connecting:${voice.state}:${voice.step}:${s.obj("plan") != null}" else "call") else ""
        return listOf(boot, screen, st, timedOut, searchChecked, moodStep, hasMine, pDone, myMood, myInt, myPrefs.joinToString(","), myReason, busy, formErr,
            callPhase, finMood, finInt, s.obj("mine").obj("final") != null, skipFinal, s.obj("secret")?.toString(), offer?.toString(), secretBusy, secretNote?.toString(),
            s.obj("ended")?.toString(), starting, problem?.joinToString("|"), s?.str("id"), reported, s.obj("partner").obj("mood")?.toString(),
            if (st == "mood" || st == "reveal") s.obj("partner").bool("online") else "").joinToString("§")
    }

    private fun render() {
        if (!alive) return
        val k = shapeKey()
        if (k == renderKey) { callView?.bind(); return }
        renderKey = k
        cancel(revealAuto); revealAuto = null; revealLeftT = null
        cancel(matchTick); matchTick = null; matchingTimer = null
        val st = status
        val s = session
        val oldId = curId
        val oldY = curScreen?.scroll?.scrollY ?: 0
        newScreen = null
        // the call keeps its own view (it updates itself); everything else is rebuilt
        if (st == "call" && callPhaseIsCall()) {
            if (callView == null) {
                host.removeAllViews(); miniCall = null
                callView = McCallView(context, this).also { host.addView(it, LayoutParams(G.MATCH, G.MATCH)) }
                // test pictures only: one of the call's sheets open (mc-call-sheet-report, …)
                if (GameApi.demo && GamesDemo.scene.startsWith("mc-call-sheet-")) postDelayed({ callView?.demoSheet(GamesDemo.scene.removePrefix("mc-call-sheet-")) }, 800)
            }
            callView?.bind()
            curScreen = null; curId = "call"
            return
        }
        callView?.let { it.dispose(); host.removeView(it) }
        callView = null
        host.removeAllViews(); miniCall = null
        G.stopAll(anims) // the old screen's floating faces / radar must not keep running
        var id: String
        val v: View = when {
            boot == "loading" -> { id = "loading"; FrameLayout(context).apply { addView(TfSpinner(context, 28f), LayoutParams(G.dpi(28f), G.dpi(28f), Gravity.CENTER)) } }
            boot == "problem" && problem != null -> { id = "problem"; problemView() }
            s == null || requeue() || (closed() && !s.bool("started") && s.obj("ended").str("by") == "me") -> { id = screen; when (screen) {
                "rules" -> rulesView(true)
                "guidelines" -> rulesView(false)
                "search" -> matchingView(isHost, searchChecked, timedOut)
                else -> { id = "intro"; introView() }
            } }
            st == "waiting" -> { id = "search"; matchingView(false, true, false) }
            st == "mood" -> when {
                s.obj("mine").obj("mood") != null -> { id = "wait-mood"; waitingView() }
                moodStep == "matched" -> { id = "matched"; matchedView() }
                moodStep == "pick" -> { id = "pick"; pickView() }
                moodStep == "detail" -> { id = "detail"; detailView() }
                else -> { id = "reason"; reasonView() }
            }
            st == "reveal" -> { id = "reveal"; revealView() }
            st == "call" -> { id = "connecting"; connectingView() }
            st == "final" || st == "td" || s.bool("started") -> { postId = "post"; val pv = postView(); id = postId; pv }
            else -> { id = "ended-early"; endedEarlyView() }
        }
        host.addView(v, LayoutParams(G.MATCH, G.MATCH))
        // web: a screen animates in only when it first appears; a tap that redraws it keeps its place
        val ns = newScreen
        if (ns != null && id == oldId) ns.same(oldY)
        curScreen = ns; curId = id
    }
    private var curScreen: MC.Screen? = null
    private var curId = ""
    private var newScreen: MC.Screen? = null
    private var postId = ""
    private fun screen(): MC.Screen = MC.Screen(context).also { newScreen = it }
    private fun callPhaseIsCall(): Boolean {
        val wasLive = everLive.isNotEmpty() && everLive == session?.str("id")
        return wasLive || voice.state == "live"
    }

    private fun partnerName() = session.obj("partner").str("name").ifEmpty { "Your partner" }
    private fun pn() = session.obj("partner").str("name")

    // ───────── screens before the call ─────────
    private fun problemView(): View {
        val p = problem!!
        val sc = screen()
        sc.bar({ backToGames() })
        sc.body.addView(FrameLayout(context).apply {
            background = G.round(G.c("#FFF4E5"), 20f)
            addView(G.icon(context, "Info", G.c("#8A5200"), 30f), LayoutParams(G.dpi(30f), G.dpi(30f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(G.dpi(64f), G.dpi(64f)).m(t = 18f, b = 6f))
        sc.body.addView(MC.h1(context, p[0]), G.lp(G.MATCH, G.WRAP).m(b = 6f)) // its 6 px top collapses into the icon's 6
        sc.body.addView(MC.lede(context, p[1]), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        if (p[2].isNotEmpty()) sc.addFoot(MC.Btn(context, "primary", p[2]) { solveProblem() }, G.dpi(50f))
        sc.addFoot(MC.Btn(context, "secondary", "Back to Games") { backToGames() }, G.dpi(50f))
        return sc.root
    }

    private fun introView(): View {
        val ctx = context
        val sc = screen()
        sc.bar({ backToGames() }, MC.iconBtn(ctx, "Info", "Game guidelines", 18f) { screen = "guidelines"; render() })
        val hero = G.vbox(ctx).apply { background = G.round(MC.wash, 24f); pad(18f, 16f, 18f, 18f) }
        // .hero-art: you · waveform · "?" in a row (gap 12), four faces floating at the corners (left/right in %)
        val faceSpecs = ArrayList<Pair<View, FloatArray>>() // [side 0 left / 1 right, fraction, top dp or -1, bottom dp or -1]
        val art = object : FrameLayout(ctx) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                val w = r - l; val h = b - t
                for ((v, sp) in faceSpecs) {
                    val x = if (sp[0] == 0f) (w * sp[1]).toInt() else w - (w * sp[1]).toInt() - v.measuredWidth
                    val y = if (sp[2] >= 0f) G.dpi(sp[2]) else h - G.dpi(sp[3]) - v.measuredHeight
                    v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
                }
            }
        }
        art.clipChildren = false
        val mid = G.hbox(ctx, 12f, Gravity.CENTER)
        mid.clipChildren = false; mid.clipToPadding = false
        val meAv = McAvatar(ctx, 78f, G.c("#D9C4FF")).also { it.setPhoto(me.str("photo")) }
        mid.addView(meAv, LinearLayout.LayoutParams(meAv.boxPx(), meAv.boxPx()).m(-9f, -9f, -9f, -9f)) // the rings sit outside the 78 box
        mid.addView(Waveform(ctx, 12, false).also { it.active = true }, LinearLayout.LayoutParams(G.dpi(92f), G.dpi(34f)))
        mid.addView(G.text(ctx, "?", 28f, MC.accent, true).apply { gravity = Gravity.CENTER; background = G.oval(Color.WHITE, G.c("#CDB2F7"), 2f).also { it.setStroke(G.dpi(2f), G.c("#CDB2F7"), G.dp(5f), G.dp(4f)) } }, LinearLayout.LayoutParams(G.dpi(78f), G.dpi(78f)))
        art.addView(mid, LayoutParams(G.MATCH, G.MATCH, Gravity.CENTER))
        // f1 left 2% top 8 · f2 left 5% bottom 4 (−2 s) · f3 right 3% top 4 (−1 s) · f4 right 1% bottom 10 (−3 s)
        val faces = listOf(
            Triple("happy", 28f, floatArrayOf(0f, 0.02f, 8f, -1f)), Triple("sad", 24f, floatArrayOf(0f, 0.05f, -1f, 4f)),
            Triple("relaxed", 26f, floatArrayOf(1f, 0.03f, 4f, -1f)), Triple("curious", 22f, floatArrayOf(1f, 0.01f, -1f, 10f)),
        )
        faces.forEachIndexed { i, (m, sz, spec) ->
            val f = MC.face(ctx, m, sz)
            art.addView(f, LayoutParams(G.dpi(sz), G.dpi(sz)))
            faceSpecs.add(f to spec)
            floatAnim(f, longArrayOf(0, 2000, 1000, 3000)[i])
        }
        hero.addView(art, G.lp(G.MATCH, G.dpi(132f)))
        val title = SpannableStringBuilder("Mood Changer")
        title.setSpan(ForegroundColorSpan(MC.accent), 5, 12, 0)
        hero.addView(G.text(ctx, title, 27f, MC.ink, true).lineH(1.15f).apply { letterSpacing = -0.025f }, G.lp(G.MATCH, G.WRAP).m(t = 4f))
        hero.addView(MC.t(ctx, "Share how you feel, talk it out on a voice call, and leave a little lighter.", MC.MD, MC.inkSoft), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 14f))
        val meta = Flow(ctx, 14f, 6f)
        for ((ic, t) in listOf("Clock" to "7 rounds", "Headphones" to "Voice only", "Users" to "2 players")) {
            val r = G.hbox(ctx, 6f); r.addView(G.icon(ctx, ic, MC.accent, 15f)); r.addView(MC.t(ctx, t, MC.SM, MC.inkSoft)); meta.addView(r)
        }
        hero.addView(meta, G.lp(G.MATCH, G.WRAP))
        sc.body.addView(hero, G.lp(G.MATCH, G.WRAP))
        sc.body.addView(MC.label(ctx, "How it works"), G.lp(G.MATCH, G.WRAP).m(t = 24f, b = 10f))
        sc.body.addView(MC.how(ctx, listOf(
            if (isHost) "Get matched" to "We find a partner who’s free to play right now." else "Wait for a player" to "You’re connected as soon as someone starts a game.",
            "Both share your mood" to "20 moods, how strong it feels, and privately, why.",
            "Talk through 7 rounds" to "Each round you get a question to lift your partner’s mood — only you see it, with a tip on how to ask. They get one for you. A little deeper each time, then a warm ending.",
            "Check in" to "See how your mood changed. Enjoyed the talk? Keep it going on Reconnect.",
        )), G.lp(G.MATCH, G.WRAP))
        val last = history.lastOrNull()
        if (last != null && McData.has(last.mood)) {
            val box = G.hbox(ctx, 8f).apply { background = G.round(Color.WHITE, 12f, MC.line, 1f); pad(12f, 10f, 12f, 10f) }
            box.addView(MC.face(ctx, last.mood, 22f))
            val sb = SpannableStringBuilder("Last time you started ")
            bold(sb, McData.moodOf(last.mood).label)
            if (last.finalMood != null && McData.has(last.finalMood)) { sb.append(" and ended "); bold(sb, McData.moodOf(last.finalMood).label) }
            sb.append(".")
            box.addView(MC.t(ctx, sb, MC.SM, MC.ink), G.lpw(1f))
            sc.body.addView(box, G.lp(G.MATCH, G.WRAP).m(t = 14f))
        }
        val free = G.hbox(ctx, 8f)
        free.addView(G.icon(ctx, "ShieldCheck", MC.ok, 16f))
        free.addView(MC.t(ctx, "Free to play while True Find is in testing. Safety tools and support are always free.", MC.SM, MC.inkSoft), G.lpw(1f))
        sc.body.addView(free, G.lp(G.MATCH, G.WRAP).m(t = 18f))
        sc.addFoot(MC.Btn(ctx, "primary", if (isHost) "Find a partner" else "I’m ready to play", iconRight = "ArrowRight") { onStart() }, G.dpi(50f))
        return sc.root
    }

    private fun bold(sb: SpannableStringBuilder, s: String) { val a = sb.length; sb.append(s); sb.setSpan(FontSpan(app.truefind.android.Ui.bold), a, sb.length, 0) }

    private fun shieldBox(): View = FrameLayout(context).apply {
        background = G.round(MC.accentTint, 16f)
        addView(G.icon(context, "ShieldCheck", MC.accent, 26f), LayoutParams(G.dpi(26f), G.dpi(26f), Gravity.CENTER))
    }

    private fun rulesView(consent: Boolean): View {
        val ctx = context
        val sc = screen()
        sc.bar({ screen = "intro"; render() })
        sc.body.addView(shieldBox(), LinearLayout.LayoutParams(G.dpi(52f), G.dpi(52f)).m(t = 4f))
        sc.body.addView(MC.h1(ctx, if (consent) "Before you start" else "Game guidelines"), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        sc.body.addView(MC.lede(ctx, if (consent) "A quick read so the game stays safe and kind for both of you." else "So the game stays safe and kind for both players."), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        sc.body.addView(MC.rulesList(ctx), G.lp(G.MATCH, G.WRAP).m(b = 16f)) // its 6 px top collapses into the lede's 18
        if (consent) {
            lateinit var btn: MC.Btn
            val row = G.hbox(ctx, 12f, Gravity.TOP)
            row.pad(14f)
            val box = FrameLayout(ctx)
            val txt = SpannableStringBuilder()
            bold(txt, "I agree to the guidelines")
            txt.setSpan(ForegroundColorSpan(MC.ink), 0, txt.length, 0)
            txt.setSpan(android.text.style.AbsoluteSizeSpan(G.dpi(MC.MD)), 0, txt.length, 0) // <b> is 14 px, the rest 12.5
            txt.append("\nI’m okay sharing my mood with my game partner and joining a live voice call.")
            fun paint() {
                row.background = if (agreed) G.round(G.c("#FBF8FF"), 16f, G.c("#CDB2F7"), 1f) else G.round(Color.WHITE, 16f, MC.line, 1f)
                box.removeAllViews()
                box.background = if (agreed) G.round(MC.accent, 7f) else G.round(Color.WHITE, 7f, G.c("#C6B5E4"), 1.5f)
                if (agreed) box.addView(G.icon(ctx, "Check", Color.WHITE, 14f, 3f), LayoutParams(G.dpi(14f), G.dpi(14f), Gravity.CENTER))
                btn.isEnabled = agreed
            }
            row.addView(box, LinearLayout.LayoutParams(G.dpi(22f), G.dpi(22f)).m(t = 1f))
            row.addView(MC.t(ctx, txt, MC.SM, MC.inkSoft).lineH(1.45f), G.lpw(1f))
            G.click(row, 0.99f) { agreed = !agreed; paint() }
            sc.body.addView(row, G.lp(G.MATCH, G.WRAP))
            btn = MC.Btn(ctx, "primary", "Continue", iconRight = "ArrowRight") { if (agreed) startSearch() }
            sc.addFoot(btn, G.dpi(50f))
            paint()
        } else sc.addFoot(MC.Btn(ctx, "secondary", "Back") { screen = "intro"; render() }, G.dpi(50f))
        return sc.root
    }

    /** .radar: three rings (+ two pulses and four mood blips while searching) around a photo */
    private fun radar(withPulses: Boolean, idle: Boolean, person: String?): View {
        val ctx = context
        val r = FrameLayout(ctx)
        r.clipChildren = false
        fun ring(inset: Float, fill: Int?) = View(ctx).apply { background = G.oval(fill ?: Color.TRANSPARENT, G.c("#E6DAFA"), 1f) }.also {
            r.addView(it, LayoutParams(G.dpi(232f - inset * 2), G.dpi(232f - inset * 2), Gravity.CENTER))
        }
        ring(0f, null); ring(32f, null); ring(64f, MC.wash)
        if (withPulses) {
            for (i in 0..1) {
                val p = View(ctx).apply { background = G.oval(Color.TRANSPARENT, G.ca("#7F00FF", 0.32f), 2f) }
                r.addView(p, LayoutParams(G.dpi(104f), G.dpi(104f), Gravity.CENTER))
                if (idle) p.alpha = 0.25f else pulse(p, i * 1300L)
            }
            // b1 top 10 left 64 · b2 top 70 right 0 (−1 s) · b3 bottom 18 left 22 (−2 s) · b4 bottom 0 right 58 (−0.5 s)
            val blips = listOf(Triple("relaxed", 24f, 0), Triple("happy", 26f, 1), Triple("curious", 22f, 2), Triple("grateful", 24f, 3))
            for ((m, sz, i) in blips) {
                val f = MC.face(ctx, m, sz)
                val lp = when (i) {
                    0 -> LayoutParams(G.dpi(sz), G.dpi(sz), Gravity.TOP or Gravity.START).m(l = 64f, t = 10f)
                    1 -> LayoutParams(G.dpi(sz), G.dpi(sz), Gravity.TOP or Gravity.END).m(t = 70f)
                    2 -> LayoutParams(G.dpi(sz), G.dpi(sz), Gravity.BOTTOM or Gravity.START).m(l = 22f, b = 18f)
                    else -> LayoutParams(G.dpi(sz), G.dpi(sz), Gravity.BOTTOM or Gravity.END).m(r = 58f)
                }
                r.addView(f, lp)
                if (idle) f.alpha = 0.25f else blip(f, longArrayOf(0, 1000, 2000, 500)[i])
            }
        }
        val av = McAvatar(ctx, 84f, G.c("#D9C4FF")).also { it.setPhoto(person) }
        r.addView(av, LayoutParams(av.boxPx(), av.boxPx(), Gravity.CENTER))
        return r
    }

    private fun matchingView(host: Boolean, checked: Boolean, timed: Boolean): View {
        val ctx = context
        val sc = screen()
        sc.bar(null)
        val b = sc.body
        b.gravity = Gravity.CENTER_HORIZONTAL
        b.addView(radar(true, timed, me.str("photo")), LinearLayout.LayoutParams(G.dpi(232f), G.dpi(232f)).m(t = 10f, b = 4f))
        val title: String; val lede: String
        when {
            timed -> { title = "No partner available right now"; lede = "Everyone is busy at the moment. Try again in a minute." }
            host -> { title = "Finding your mood partner…"; lede = "Looking for someone who’s free to talk right now. " }
            else -> { title = "Waiting for a player…"; lede = "You’re available for Mood Changer. Keep this screen open — we’ll connect you when someone starts a game. " }
        }
        b.addView(MC.h1(ctx, title, true), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        val ledeT = MC.lede(ctx, lede, true)
        b.addView(ledeT, G.lp(G.MATCH, G.WRAP).m(b = 18f))
        if (!timed) {
            matchingTimer = ledeT
            val base = lede
            fun upd() {
                val t = base + McData.fmtShort(((System.currentTimeMillis() - searchSince) / 1000).toInt().coerceAtLeast(0))
                if (ledeT.text.toString() != t) ledeT.text = t
                matchTick = later(500) { upd() }
            }
            upd()
            val sl = MC.StatusList(ctx)
            sl.add(if (host) "Checking who’s available" else "You’re online for games", checked, !checked)
            sl.add(if (host) "Finding a partner for you" else "Waiting for a player to start", false, checked)
            sl.add("Safety check: blocks and reports", checked, false)
            b.addView(sl.root, G.lp(G.MATCH, G.WRAP))
        }
        if (timed) {
            sc.addFoot(MC.Btn(ctx, "primary", "Try again", icon = "RefreshCw") { startSearch() }, G.dpi(50f))
            sc.addFoot(MC.Btn(ctx, "secondary", "Back to Games") { backToGames() }, G.dpi(50f))
        } else sc.addFoot(MC.Btn(ctx, "secondary", "Cancel") { cancelSearch() }, G.dpi(50f))
        return sc.root
    }

    /** .pair: you · link with a heart · partner (the link sits 15 px above the row's middle, web margin-top −30) */
    private fun pairView(meRing: Int, prRing: Int): View {
        val ctx = context
        val s = session
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; clipChildren = false; clipToPadding = false }
        fun who(photo: String, name: String, small: String, ring: Int): View {
            val c = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
            c.clipChildren = false; c.clipToPadding = false
            val av = McAvatar(ctx, 84f, ring).also { it.setPhoto(photo) }
            c.addView(av, LinearLayout.LayoutParams(av.boxPx(), av.boxPx()).m(-9f, -9f, -9f, -9f))
            c.addView(MC.t(ctx, name, MC.MD, MC.ink, true).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP).m(t = 8f))
            c.addView(MC.t(ctx, small, MC.XS, MC.inkSoft).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP))
            return c
        }
        row.addView(who(me.str("photo"), "You", me.str("name"), meRing), G.lpw(1f))
        val link = FrameLayout(ctx)
        link.addView(View(ctx).apply { background = CssBg(3f, listOf(CssBg.Linear(90f, intArrayOf(meRing, prRing)))) }, LayoutParams(G.dpi(64f), G.dpi(3f), Gravity.CENTER))
        link.addView(FrameLayout(ctx).apply {
            background = G.oval(Color.WHITE, MC.line, 1f)
            addView(G.icon(ctx, "Heart", G.c("#E8436E"), 15f, 2f, G.c("#E8436E")), LayoutParams(G.dpi(15f), G.dpi(15f), Gravity.CENTER))
        }, LayoutParams(G.dpi(30f), G.dpi(30f), Gravity.CENTER))
        // row height 84 + 8 + 21 + 16.5 = 129.5 → middle 64.75 → link middle 49.75 → 30 box top 34.75
        row.addView(link, LinearLayout.LayoutParams(G.dpi(64f), G.dpi(30f)).m(t = 34.75f))
        val p = s.obj("partner")
        val age = p.int("age")
        row.addView(who(p.str("photo"), p.str("name") + if (age > 0) ", $age" else "", if (p.bool("online")) "Online now" else "Connecting…", prRing), G.lpw(1f))
        return row
    }

    /** "You both like …" (.interests) */
    private fun interests(center: Boolean): View? {
        val shared = session.arr("shared").strs()
        if (shared.isEmpty()) return null
        val ctx = context
        val f = Flow(ctx, 6f, 6f, center)
        f.addView(MC.t(ctx, "You both like", MC.SM, MC.inkSoft))
        for (x in shared) f.addView(G.text(ctx, x, MC.XS, MC.ink, false).lineH(1.5f).apply { background = G.round(Color.WHITE, 99f, MC.line, 1f); pad(10f, 3f, 10f, 3f) })
        return f
    }

    private fun matchedView(): View {
        val ctx = context
        val sc = screen()
        sc.bar({ openLeave() })
        val n = pn()
        // CSS margins between blocks collapse: h1 (6) / pair (8 … 16) / interests (14) / how (22)
        sc.body.addView(MC.h1(ctx, if (isHost) "You’re matched!" else "$n joined you", true), G.lp(G.MATCH, G.WRAP).m(t = 6f))
        val ints = interests(true)
        sc.body.addView(pairView(G.c("#D9C4FF"), G.c("#D9C4FF")), G.lp(G.MATCH, G.WRAP).m(t = 8f, b = if (ints != null) 16f else 0f))
        ints?.let { sc.body.addView(it, G.lp(G.MATCH, G.WRAP)) }
        sc.body.addView(MC.how(ctx, listOf(
            "Both of you share your mood" to "Three quick questions. $n answers at the same time.",
            "See each other’s moods" to "So you can be kind and supportive.",
            "The voice call starts" to if (isHost) "You start the game and move the questions." else "$n starts the game and moves the questions.",
        )), G.lp(G.MATCH, G.WRAP).m(t = 22f))
        sc.addFoot(MC.Btn(ctx, "primary", "Share how I feel", iconRight = "ArrowRight") { moodStep = "pick"; render() }, G.dpi(50f))
        return sc.root
    }

    /** .pairline: mini photo · "Name is answering…" · ✓ or loader (right behind the words) */
    private fun pairLine(): View? {
        val p = session.obj("partner") ?: return null
        val ctx = context
        val done = p.bool("mood_done")
        val sb = SpannableStringBuilder()
        bold(sb, p.str("name")); sb.setSpan(ForegroundColorSpan(MC.ink), 0, sb.length, 0)
        sb.append(if (done) " has shared their mood" else " is answering their questions too")
        val mini = AvatarView(ctx, 22f, Color.TRANSPARENT, 0f, G.c("#EDE2FF")).also { it.setPhoto(p.str("photo")); it.layoutParams = LinearLayout.LayoutParams(G.dpi(22f), G.dpi(22f)) }
        val tail: View = if (done) G.icon(ctx, "Check", MC.inkSoft, 14f, 2.6f) else TfSpinner(ctx, 12f).also { it.layoutParams = LinearLayout.LayoutParams(G.dpi(12f), G.dpi(12f)) }
        return packRow(ctx, 8f, listOf(mini, MC.t(ctx, sb, MC.SM, MC.inkSoft), tail), 1)
    }

    /** the 20 moods, 4 per row (.moodgrid: tiles in a row are as tall as the tallest) */
    private fun moodGrid(value: String?, compact: Boolean, onPick: (String) -> Unit): View {
        val ctx = context
        val narrow = MC.narrow(ctx)
        val gap = if (compact || narrow) 6f else 8f
        val col = G.vbox(ctx, gap)
        var row: LinearLayout? = null
        McData.MOODS.forEachIndexed { i, m ->
            if (i % 4 == 0) { row = G.hbox(ctx, gap, Gravity.TOP); col.addView(row, G.lp(G.MATCH, G.WRAP)) }
            val on = value == m.key
            val tile = FrameLayout(ctx)
            tile.background = if (on) G.round(G.c("#F8F2FF"), 14f, MC.accent, 2f) else G.round(Color.WHITE, 14f, MC.line, 1f)
            val c = G.vbox(ctx, if (compact) 3f else 5f, Gravity.CENTER_HORIZONTAL)
            c.pad(2f, if (compact) 8f else 10f, 2f, if (compact) 6f else 8f)
            c.addView(MC.face(ctx, m.key, if (compact) 30f else 34f))
            c.addView(G.text(ctx, m.label, if (narrow) 9.8f else 10.5f, MC.ink, false).lineH(1.2f).apply { gravity = Gravity.CENTER; letterSpacing = if (narrow) -0.01f else -0.005f })
            tile.addView(c, LayoutParams(G.MATCH, G.WRAP))
            if (on) tile.addView(FrameLayout(ctx).apply {
                background = G.oval(MC.accent)
                addView(G.icon(ctx, "Check", Color.WHITE, 11f, 3.2f), LayoutParams(G.dpi(11f), G.dpi(11f), Gravity.CENTER))
            }, LayoutParams(G.dpi(16f), G.dpi(16f), Gravity.TOP or Gravity.END).m(t = 5f, r = 5f))
            tile.contentDescription = m.label
            G.click(tile, 0.97f) { onPick(m.key) }
            row?.addView(tile, G.lpw(1f, G.MATCH))
        }
        return col
    }

    private fun pickView(): View {
        val ctx = context
        val sc = screen()
        sc.bar({ moodStep = "matched"; render() }, MC.stepTag(ctx, 1, 3))
        sc.body.addView(MC.h1(ctx, "How are you feeling right now?"), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        sc.body.addView(MC.lede(ctx, "Pick the one that fits best. There’s no wrong answer."), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        pairLine()?.let { sc.body.addView(it, G.lp(G.MATCH, G.WRAP).m(t = -8f, b = 16f)) }
        sc.body.addView(moodGrid(myMood, false) { k -> if (myMood != k) myReason = null; myMood = k; render() }, G.lp(G.MATCH, G.WRAP))
        val m = myMood
        sc.addFoot(MC.Btn(ctx, "primary", if (m != null) "Continue with ${McData.moodOf(m).label}" else "Continue", iconRight = "ArrowRight") { if (myMood != null) { moodStep = "detail"; render() } }.also { it.isEnabled = m != null }, G.dpi(50f))
        return sc.root
    }

    private fun detailView(): View {
        val ctx = context
        val m = McData.moodOf(myMood)
        val sc = screen()
        sc.bar({ moodStep = "pick"; render() }, MC.stepTag(ctx, 2, 3))
        sc.body.addView(MC.h1(ctx, "Tell us a little more"), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        sc.body.addView(MC.lede(ctx, "This helps the game pick the right questions for both of you."), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        pairLine()?.let { sc.body.addView(it, G.lp(G.MATCH, G.WRAP).m(t = -8f, b = 16f)) }
        val picked = G.hbox(ctx, 14f).apply { background = G.round(MC.wash, 18f); pad(14f, 12f, 14f, 12f) }
        picked.addView(MC.face(ctx, myMood, 52f))
        val pc = G.vbox(ctx)
        pc.addView(G.text(ctx, m.label, MC.XL, MC.ink, true).lineH(1.2f))
        pc.addView(MC.t(ctx, m.hi, MC.XS, MC.inkSoft))
        picked.addView(pc, G.lpw(1f))
        val change = G.text(ctx, "Change", MC.SM, MC.accentDeep, true).apply { pad(10f, 8f, 10f, 8f) }
        G.click(change) { moodStep = "pick"; render() }
        picked.addView(change)
        sc.body.addView(picked, G.lp(G.MATCH, G.WRAP).m(b = 22f)) // CSS 4 px top collapses into the 16/18 above
        val strengthLabel = MC.label(ctx, "How strong is it?", McData.INTENSITY[myInt - 1], true)
        sc.body.addView(strengthLabel, G.lp(G.MATCH, G.WRAP).m(b = 10f))
        val meter = MC.Meter(ctx, myInt) { v -> myInt = v; strengthLabel.text = MC.label(ctx, "How strong is it?", McData.INTENSITY[v - 1], true).text }
        sc.body.addView(meter.root, G.lp(G.MATCH, G.WRAP))
        sc.body.addView(MC.meterEnds(ctx), G.lp(G.MATCH, G.WRAP).m(t = 6f))
        sc.body.addView(MC.label(ctx, "What would help today?", "Optional · up to 2"), G.lp(G.MATCH, G.WRAP).m(t = 24f, b = 10f))
        val chips = Flow(ctx, 8f, 8f)
        for ((k, label) in McData.PREFS) {
            val on = myPrefs.contains(k)
            val c = MC.chip(ctx, label, on) {
                if (myPrefs.contains(k)) myPrefs.remove(k) else if (myPrefs.size < 2) myPrefs.add(k)
                render()
            }
            if (!on && myPrefs.size >= 2) { c.isEnabled = false; c.alpha = 0.45f }
            chips.addView(c)
        }
        sc.body.addView(chips, G.lp(G.MATCH, G.WRAP))
        if (myMood != null && McData.repeatMood(history, myMood!!)) {
            val note = G.hbox(ctx, 10f, Gravity.TOP).apply { background = G.round(G.c("#FFF8EE"), 14f, G.c("#F5E2C6"), 1f); pad(14f, 12f, 14f, 12f) }
            note.addView(G.icon(ctx, "Lightbulb", G.c("#B26A00"), 17f), LinearLayout.LayoutParams(G.dpi(17f), G.dpi(17f)).m(t = 2f))
            val nc = G.vbox(ctx)
            val sb = SpannableStringBuilder("You’ve picked "); bold(sb, m.label); sb.append(" in your last few games. That’s okay. Today, would you like to talk about it, or keep things light?")
            nc.addView(MC.t(ctx, sb, MC.SM, MC.ink))
            val ch = Flow(ctx, 8f, 8f)
            ch.addView(MC.chip(ctx, "Talk about it", myPrefs.contains("listen"), tick = false, minH = 32f) { myPrefs.clear(); myPrefs.add("listen"); render() })
            ch.addView(MC.chip(ctx, "Keep it light", myPrefs.contains("light"), tick = false, minH = 32f) { myPrefs.clear(); myPrefs.add("light"); render() })
            ch.addView(MC.chip(ctx, "Talk to someone", false, "LifeBuoy", minH = 32f) { openSupport() })
            nc.addView(ch, G.lp(G.MATCH, G.WRAP).m(t = 10f))
            note.addView(nc, G.lpw(1f))
            sc.body.addView(note, G.lp(G.MATCH, G.WRAP).m(t = 18f))
        }
        sc.addFoot(MC.Btn(ctx, "primary", "Continue", iconRight = "ArrowRight") { moodStep = "reason"; render() }, G.dpi(50f))
        return sc.root
    }

    private fun reasonView(): View {
        val ctx = context
        val sc = screen()
        sc.bar({ moodStep = "detail"; render() }, MC.stepTag(ctx, 3, 3))
        sc.body.addView(MC.h1(ctx, "What’s behind this mood?"), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        val pl = G.hbox(ctx, 8f, Gravity.TOP)
        pl.addView(G.icon(ctx, "Lock", MC.accent, 15f), LinearLayout.LayoutParams(G.dpi(15f), G.dpi(15f)).m(t = 3f))
        pl.addView(MC.lede(ctx, "Private. ${session.obj("partner").str("name").ifEmpty { "Your partner" }} never sees this. It only shapes the questions you’re asked."), G.lpw(1f))
        sc.body.addView(pl, G.lp(G.MATCH, G.WRAP).m(b = 18f))
        val opts = G.vbox(ctx, 8f)
        val list = McData.REASONS[McData.fam(myMood)] ?: McData.REASONS["flat"]!!
        for (r in list + McData.Reason("skip", "I’d rather not say", "बताने का मन नहीं", "EyeOff")) {
            opts.addView(MC.opt(ctx, r.en, r.icon, hi = r.hi, radio = myReason == r.key) { myReason = r.key; render() }.apply { pad(14f, 10f, 14f, 10f) }, G.lp(G.MATCH, G.WRAP))
        }
        sc.body.addView(opts, G.lp(G.MATCH, G.WRAP))
        if (myReason == "treated_badly") {
            val sup = G.vbox(ctx).apply { background = G.round(G.c("#FBF7FF"), 18f, G.c("#E8DAFB"), 1f); pad(16f) }
            sup.addView(G.text(ctx, "You don’t have to face this alone", MC.LG, MC.ink, true), G.lp(G.MATCH, G.WRAP).m(b = 4f))
            sup.addView(MC.t(ctx, "If someone hurt you or you don’t feel safe, please talk to someone you trust. Tele-MANAS is free, private and open 24×7.", MC.SM, G.c("#463C61")), G.lp(G.MATCH, G.WRAP).m(b = 12f))
            sup.addView(MC.Btn(ctx, "primary", "Call Tele-MANAS (14416)", icon = "HeartHandshake", small = true) { dial() }, G.lp(G.MATCH, G.dpi(38f)))
            sc.body.addView(sup, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        }
        if (myReason == "unwell") sc.body.addView(MC.noteBox(ctx, "Info", "If you feel very unwell, please see a doctor. This game is friendly support, not medical advice.", "note"), G.lp(G.MATCH, G.WRAP).m(t = 18f))
        if (formErr.isNotEmpty()) sc.body.addView(MC.t(ctx, formErr, MC.SM, MC.danger), G.lp(G.MATCH, G.WRAP).m(t = 12f))
        sc.addFoot(MC.Btn(ctx, "primary", if (busy) "Saving…" else "Done, show our moods", iconRight = if (busy) null else "ArrowRight") { submitMood() }.also { it.isEnabled = !busy }, G.dpi(50f))
        return sc.root
    }

    private fun waitingView(): View {
        val ctx = context
        val sc = screen()
        sc.bar({ openLeave() })
        val b = sc.body
        b.gravity = Gravity.CENTER_HORIZONTAL
        val n = pn()
        b.addView(radar(false, true, session.obj("partner").str("photo")), LinearLayout.LayoutParams(G.dpi(232f), G.dpi(232f)).m(t = 30f, b = 4f)) // style marginTop 30
        b.addView(MC.h1(ctx, "Waiting for $n…", true), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        b.addView(MC.lede(ctx, "$n is finishing their answers. You’ll see each other’s moods next.", true), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        val sl = MC.StatusList(ctx)
        sl.add("Your answers are in", true, false)
        sl.add("$n’s answers", false, true)
        b.addView(sl.root, G.lp(G.MATCH, G.WRAP))
        return sc.root
    }

    private fun moodCard(k: String, p: JSONObject?): View {
        val ctx = context
        val c = G.vbox(ctx, 6f).apply { background = G.round(Color.WHITE, 16f, MC.line, 1f); pad(12f) }
        c.addView(MC.t(ctx, k, MC.XS, MC.inkSoft))
        val m = G.hbox(ctx, 8f)
        m.addView(MC.face(ctx, p.str("mood"), 30f))
        m.addView(G.text(ctx, McData.moodOf(p.str("mood")).label, MC.LG, MC.ink, true))
        c.addView(m)
        val sline = G.hbox(ctx, 6f)
        val n = p.int("intensity", 3).coerceIn(1, 5)
        sline.addView(MC.strength(ctx, n))
        sline.addView(MC.t(ctx, McData.INTENSITY[n - 1], MC.XS, MC.inkSoft))
        c.addView(sline)
        val prefs = p.arr("prefs").strs().filter { McData.PREF.containsKey(it) }
        if (prefs.isNotEmpty()) c.addView(MC.t(ctx, "Would like: " + prefs.joinToString(", ") { McData.PREF[it]!! }, MC.XS, MC.accentDeep))
        return c
    }

    private fun revealView(): View {
        val ctx = context
        val s = session!!
        val mine = s.obj("mine").obj("mood")
        val pm = s.obj("partner").obj("mood")
        val sc = screen()
        sc.bar({ openLeave() })
        sc.body.addView(MC.h1(ctx, "Mood partner found", true), G.lp(G.MATCH, G.WRAP).m(t = 6f))
        sc.body.addView(pairView(G.c(McData.moodOf(mine.str("mood")).tint), G.c(McData.moodOf(pm.str("mood")).tint)), G.lp(G.MATCH, G.WRAP).m(t = 8f, b = 16f))
        val cards = G.hbox(ctx, 10f, Gravity.TOP)
        cards.addView(moodCard("Your mood", mine), G.lpw(1f))
        cards.addView(moodCard("${pn()}’s mood", pm), G.lpw(1f))
        sc.body.addView(cards, G.lp(G.MATCH, G.WRAP))
        interests(false)?.let { sc.body.addView(it, G.lp(G.MATCH, G.WRAP).m(t = 14f)) }
        val priv = SpannableStringBuilder(); bold(priv, "Private to this game."); priv.setSpan(ForegroundColorSpan(MC.ink), 0, priv.length, 0)
        priv.append(" You can see each other’s moods so you can be kind and supportive. Please don’t share them outside this session.")
        sc.body.addView(MC.noteBox(ctx, "Lock", priv), G.lp(G.MATCH, G.WRAP).m(t = 14f))
        val okl = G.hbox(ctx, 8f); okl.addView(G.icon(ctx, "Check", MC.ok, 16f, 2.6f)); okl.addView(MC.t(ctx, "Both of you agreed to share moods", MC.SM, MC.ok), G.lpw(1f))
        sc.body.addView(okl, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        if (isHost) {
            sc.addFoot(MC.Btn(ctx, "primary", if (starting) "Starting…" else "Start game & connect call", icon = if (starting) null else "Headphones") { revealFired = true; startGame() }.also { it.isEnabled = !starting }, G.dpi(50f))
            val note = MC.small(ctx, "", true)
            sc.addFoot(note)
            if (starting) note.text = "Planning your 7 rounds…"
            else {
                if (revealStart == 0L || renderKeyRevealSid != s.str("id")) { revealStart = System.currentTimeMillis(); renderKeyRevealSid = s.str("id") }
                revealLeftT = note
                fun tick() {
                    val l = ceil((8000 - (System.currentTimeMillis() - revealStart)) / 1000.0).toInt()
                    note.text = "Connecting automatically in ${max(0, l)}s"
                    if (l <= 0) { if (!revealFired) { revealFired = true; startGame() }; return } // web: the countdown stops at 0
                    revealAuto = later(250) { tick() }
                }
                tick()
            }
        } else {
            sc.addFoot(waitingBox(MC.t(ctx, "${pn()} is starting the game…", MC.SM, MC.ink), avatar = true, spinner = true))
        }
        return sc.root
    }
    private var renderKeyRevealSid = ""

    /** .waiting: [loader] [photo] words [loader] in a wash box; the items stay together at the start */
    private fun waitingBox(text: TextView, avatar: Boolean = false, spinner: Boolean = false, spinnerFirst: Boolean = false, icon: String? = null,
                           iconColor: Int = MC.ink, bg: Int = MC.wash, extra: View? = null): View {
        val ctx = context
        val box = FrameLayout(ctx).apply { background = G.round(bg, 14f); pad(12f) }
        val items = ArrayList<View>()
        if (spinnerFirst) items.add(TfSpinner(ctx, 16f).also { it.layoutParams = LinearLayout.LayoutParams(G.dpi(16f), G.dpi(16f)) })
        if (avatar) {
            val av = McAvatar(ctx, 30f, null).also { it.setPhoto(session.obj("partner").str("photo")) }
            av.layoutParams = LinearLayout.LayoutParams(av.boxPx(), av.boxPx()).m(-9f, -9f, -9f, -9f)
            items.add(av)
        }
        if (icon != null) items.add(G.icon(ctx, icon, iconColor, 18f, if (icon == "Check") 2.6f else 2f))
        val ti = items.size
        items.add(text)
        if (spinner) items.add(TfSpinner(ctx, 16f).also { it.layoutParams = LinearLayout.LayoutParams(G.dpi(16f), G.dpi(16f)) })
        if (extra != null) items.add(extra)
        val row = packRow(ctx, 12f, items, ti)
        row.clipChildren = false; row.clipToPadding = false
        box.clipChildren = false; box.clipToPadding = false
        box.addView(row, LayoutParams(G.WRAP, G.WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
        return box
    }

    private fun connectingView(): View {
        val ctx = context
        val s = session!!
        val bad = voice.state == "error" || voice.state == "unavailable"
        val err = if (bad) voice.error else ""
        val plan = s.obj("plan")
        val sc = screen()
        sc.bar(null)
        val b = sc.body
        b.gravity = Gravity.CENTER_HORIZONTAL
        val art = FrameLayout(ctx)
        art.addView(View(ctx).apply { background = G.oval(Color.TRANSPARENT, G.c("#EADFFB"), 1f) }, LayoutParams(G.dpi(184f), G.dpi(184f), Gravity.CENTER))
        art.addView(View(ctx).apply { background = G.oval(MC.wash) }, LayoutParams(G.dpi(156f), G.dpi(156f), Gravity.CENTER))
        art.addView(FrameLayout(ctx).apply {
            background = G.oval(MC.accentTint)
            addView(G.icon(ctx, "Headphones", MC.accent, 34f), LayoutParams(G.dpi(34f), G.dpi(34f), Gravity.CENTER))
        }, LayoutParams(G.dpi(78f), G.dpi(78f), Gravity.CENTER))
        b.addView(art, LinearLayout.LayoutParams(G.dpi(184f), G.dpi(184f)).m(t = 8f, b = -10f)) // circle 22 from the top, 10 to the title
        b.addView(MC.h1(ctx, if (err.isNotEmpty()) "Voice call didn’t connect" else "Connecting your voice call…", true), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        b.addView(MC.lede(ctx, err.ifEmpty { "Your mic turns on when the call starts. You can mute anytime." }, true), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        val sl = MC.StatusList(ctx)
        sl.add("Both players ready", true, false)
        sl.add("Moods shared", true, false)
        sl.add("Planning your 7 rounds", plan != null, plan == null, if (plan != null) (if (plan.str("source") == "ai") "AI" else "Rules") else null)
        sl.add("Creating a secure voice room", voice.step >= 1, err.isEmpty() && voice.step == 0)
        sl.add("Connecting audio", voice.step >= 2, err.isEmpty() && voice.step == 1)
        b.addView(sl.root, G.lp(G.MATCH, G.WRAP))
        if (plan != null) b.addView(styleChip(ctx, "Today: ${plan.str("title_en")}"), G.lp(G.WRAP, G.WRAP).m(t = 16f))
        b.addView(Waveform(ctx, 26, false).also { it.active = voice.step >= 1 && err.isEmpty() }, LinearLayout.LayoutParams(G.dpi(160f), G.dpi(30f)).m(t = 20f))
        if (err.isNotEmpty()) {
            sc.addFoot(MC.Btn(ctx, "primary", "Try again", icon = "RefreshCw") { voice.retry() }, G.dpi(50f))
            sc.addFoot(MC.Btn(ctx, "secondary", "End the game") { endCall("ended_early") }, G.dpi(50f))
        }
        return sc.root
    }
    fun styleChip(ctx: Context, text: String): View {
        val c = G.hbox(ctx, 6f).apply { background = G.round(MC.accentTint, 99f); pad(12f, 6f, 12f, 6f) }
        c.addView(G.icon(ctx, "Sparkles", MC.accentDeep, 14f))
        c.addView(G.text(ctx, text, MC.SM, MC.accentDeep, true))
        return c
    }

    // ───────── after the rounds ─────────
    private var miniCall: LinearLayout? = null
    private fun miniCallView(): View? {
        val voiceActive = session != null && status in VOICE_ON
        if (!voiceActive || voice.state == "idle") return null
        val ctx = context
        val bar = G.hbox(ctx, 10f).apply {
            background = CssBg(16f, listOf(CssBg.Linear(90f, intArrayOf(MC.call1, MC.call2))))
            pad(12f, 7f, 8f, 7f)
        }
        miniCall = bar
        bindMini(bar)
        return bar
    }
    private fun bindMini(bar: LinearLayout) {
        val ctx = context
        bar.removeAllViews()
        val live = voice.state == "live" && voice.partnerIn && !voice.reconnecting
        val dc = if (live) "#4ADE80" else "#FFB547"
        val dot = FrameLayout(ctx)
        dot.addView(View(ctx).apply { background = G.oval(G.ca(dc, 0.25f)) }, LayoutParams(G.MATCH, G.MATCH))
        dot.addView(View(ctx).apply { background = G.oval(G.c(dc)) }, LayoutParams(G.dpi(8f), G.dpi(8f), Gravity.CENTER))
        bar.clipChildren = false
        bar.addView(dot, LinearLayout.LayoutParams(G.dpi(14f), G.dpi(14f)).m(l = -3f, r = -3f)) // box-shadow 0 0 0 3px
        val sb = SpannableStringBuilder(); bold(sb, partnerName())
        sb.append(" · " + if (live) "on the call" else if (voice.state == "live") "connecting…" else voice.error.ifEmpty { "connecting…" })
        bar.addView(MC.t(ctx, sb, MC.SM, Color.WHITE), G.lpw(1f))
        val mute = FrameLayout(ctx).apply {
            background = G.oval(if (voice.muted) Color.WHITE else G.ca("#ffffff", 0.16f)); contentDescription = if (voice.muted) "Unmute" else "Mute"
            addView(G.icon(ctx, if (voice.muted) "MicOff" else "Mic", if (voice.muted) MC.accentDeep else Color.WHITE, 16f), LayoutParams(G.dpi(16f), G.dpi(16f), Gravity.CENTER))
        }
        G.click(mute) { voice.toggleMute() }
        bar.addView(mute, LinearLayout.LayoutParams(G.dpi(34f), G.dpi(34f)))
        val end = FrameLayout(ctx).apply {
            background = G.oval(MC.danger); contentDescription = "End call"
            addView(G.icon(ctx, "PhoneOff", Color.WHITE, 16f), LayoutParams(G.dpi(16f), G.dpi(16f), Gravity.CENTER))
        }
        G.click(end) { endCall("ended_early") }
        bar.addView(end, LinearLayout.LayoutParams(G.dpi(34f), G.dpi(34f)))
    }

    private fun withTop(sc: MC.Screen) {
        val mc = miniCallView() ?: return
        sc.root.addView(mc, 0, G.lp(G.MATCH, G.WRAP).m(14f, 10f, 14f, 0f))
        sc.top.pad(14f, 8f, 14f, 8f)
    }

    private fun postView(): View {
        val s = session!!
        val ended = status !in OPEN
        val myFinal = s.obj("mine").obj("final")
        val startM = s.obj("mine").obj("mood") ?: JSONObject().put("mood", "neutral").put("intensity", 3)
        val reason = s.obj("ended").str("reason")
        val by = s.obj("ended").str("by")
        var notice = ""
        if (ended && by == "partner") notice = when (reason) {
            "partner_left" -> "${partnerName()} lost connection, so the call ended."
            "reported" -> "The call has ended."
            else -> "${partnerName()} ended the call."
        }
        if (myFinal == null && !(ended && skipFinal)) { postId = "final"; return finalView(ended, if (notice.isNotEmpty()) "$notice Let’s check in on how you’re feeling." else "") }
        if (ended && myFinal == null) { postId = "end"; return endView() }
        postId = "summary"
        return summaryView(startM, myFinal!!, ended, reason, by, notice)
    }

    private fun finalView(ended: Boolean, notice: String): View {
        val ctx = context
        val sc = screen()
        sc.bar(null, MC.stepTag(ctx, 1, 2))
        withTop(sc)
        if (notice.isNotEmpty()) sc.body.addView(MC.noteBox(ctx, "Info", notice), G.lp(G.MATCH, G.WRAP).m(b = 12f))
        sc.body.addView(MC.h1(ctx, "How are you feeling now?"), G.lp(G.MATCH, G.WRAP).m(t = if (notice.isNotEmpty()) 0f else 6f, b = 6f))
        sc.body.addView(MC.lede(ctx, "Only you see your answers. Your partner won’t."), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        sc.body.addView(moodGrid(finMood, true) { k -> finMood = k; render() }, G.lp(G.MATCH, G.WRAP))
        if (finMood != null) {
            val lab = MC.label(ctx, "How strong is it?", McData.INTENSITY[finInt - 1], true)
            sc.body.addView(lab, G.lp(G.MATCH, G.WRAP).m(t = 24f, b = 10f))
            val meter = MC.Meter(ctx, finInt) { v -> finInt = v; lab.text = MC.label(ctx, "How strong is it?", McData.INTENSITY[v - 1], true).text }
            sc.body.addView(meter.root, G.lp(G.MATCH, G.WRAP))
            sc.body.addView(MC.meterEnds(ctx), G.lp(G.MATCH, G.WRAP).m(t = 6f))
        }
        sc.addFoot(MC.Btn(ctx, "primary", if (busy) "Saving…" else "See my mood check", iconRight = "ArrowRight") { submitFinal() }.also { it.isEnabled = finMood != null && !busy }, G.dpi(50f))
        if (ended) sc.addFoot(MC.Btn(ctx, "ghost", "Skip for now") { skipFinal = true; render() }, G.dpi(50f))
        return sc.root
    }

    private fun endView(): View {
        val ctx = context
        val s = session!!
        val sc = screen()
        sc.bar(null)
        sc.body.addView(FrameLayout(ctx).apply {
            background = G.round(MC.accentTint, 20f)
            addView(G.icon(ctx, "Check", MC.accent, 30f, 2.6f), LayoutParams(G.dpi(30f), G.dpi(30f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(G.dpi(64f), G.dpi(64f)).m(t = 18f, b = 6f))
        sc.body.addView(MC.h1(ctx, "Thanks for playing"), G.lp(G.MATCH, G.WRAP).m(b = 6f))
        sc.body.addView(MC.lede(ctx, "Every conversation is a little different. Come back whenever you feel like talking."), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        val stats = G.hbox(ctx, 8f)
        for ((v, l) in listOf(McData.fmtShort(s.int("seconds")) to "Call time", "${s.obj("stats").int("rounds")}/7" to "Rounds", "${s.obj("stats").int("answered")}" to "Questions")) {
            val c = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply { background = G.round(Color.WHITE, 14f, MC.line, 1f); pad(12f) }
            c.addView(G.text(ctx, v, MC.XL, MC.ink, true).apply { gravity = Gravity.CENTER })
            c.addView(MC.t(ctx, l, MC.XS, MC.inkSoft).apply { gravity = Gravity.CENTER })
            stats.addView(c, G.lpw(1f))
        }
        sc.body.addView(stats, G.lp(G.MATCH, G.WRAP)) // its 16 px top collapses into the lede's 18
        sc.addFoot(MC.Btn(ctx, "primary", "Play again", icon = "RefreshCw") { playAgain() }, G.dpi(50f))
        sc.addFoot(MC.Btn(ctx, "secondary", "Back to Games") { backToGames() }, G.dpi(50f))
        return sc.root
    }

    private fun summaryView(start: JSONObject, fin: JSONObject, ended: Boolean, reason: String, by: String, notice: String): View {
        val ctx = context
        val s = session!!
        val sMood = start.str("mood", "neutral"); val sInt = start.int("intensity", 3).coerceIn(1, 5)
        val fMood = fin.str("mood", "neutral"); val fInt = fin.int("intensity", 3).coerceIn(1, 5)
        val change = McData.changeOf(sMood, sInt, fMood, fInt)
        val support = McData.needsSupport(history, fMood, fInt, change)
        val pname = partnerName()
        val sc = screen()
        sc.bar(null, MC.stepTag(ctx, 2, 2))
        withTop(sc)
        sc.body.addView(MC.h1(ctx, "Your mood check"), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 6f))
        sc.body.addView(MC.lede(ctx, "How your mood moved during the game, in your own words."), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        val cmp = G.hbox(ctx, 6f).apply { background = G.round(Color.WHITE, 18f, MC.line, 1f); pad(12f, 16f, 12f, 16f) }
        fun side(k: String, m: String, n: Int): View {
            val c = G.vbox(ctx, 6f, Gravity.CENTER_HORIZONTAL)
            c.addView(MC.t(ctx, k, MC.XS, MC.inkSoft).apply { gravity = Gravity.CENTER })
            c.addView(MC.face(ctx, m, 46f))
            c.addView(G.text(ctx, McData.moodOf(m).label, MC.MD, MC.ink, true).apply { gravity = Gravity.CENTER })
            c.addView(MC.strength(ctx, n), G.lp(G.WRAP, G.WRAP))
            return c
        }
        cmp.addView(side("At the start", sMood, sInt), G.lpw(1f))
        cmp.addView(G.icon(ctx, "ArrowRight", MC.accent, 22f))
        cmp.addView(side("Now", fMood, fInt), G.lpw(1f))
        sc.body.addView(cmp, G.lp(G.MATCH, G.WRAP))
        val changeLine = mapOf("lighter" to "That is lighter than when you started.", "same" to "That is about the same as when you started.",
            "heavier" to "That feels heavier than when you started.", "good" to "You’re still in a good place.")
        val rf = SpannableStringBuilder("You started feeling ")
        bold(rf, McData.moodOf(sMood).label.lowercase()); rf.append(" (${McData.INTENSITY[sInt - 1].lowercase()}) and now feel ")
        bold(rf, McData.moodOf(fMood).label.lowercase()); rf.append(" (${McData.INTENSITY[fInt - 1].lowercase()}). ${changeLine[change] ?: ""}")
        sc.body.addView(MC.t(ctx, rf, MC.MD, MC.ink), G.lp(G.MATCH, G.WRAP).m(2f, 14f, 2f, 0f))
        if (support) {
            val sup = G.vbox(ctx).apply { background = G.round(G.c("#FBF7FF"), 18f, G.c("#E8DAFB"), 1f); pad(16f) }
            sup.addView(G.text(ctx, "Thanks for being honest", MC.LG, MC.ink, true), G.lp(G.MATCH, G.WRAP).m(b = 4f))
            sup.addView(MC.t(ctx, "If things feel heavy, it can help to take a short break or talk to someone you trust.", MC.SM, G.c("#463C61")), G.lp(G.MATCH, G.WRAP).m(b = 12f))
            sup.addView(MC.Btn(ctx, "primary", "Talk to someone", icon = "HeartHandshake", small = true) { openSupport() }, G.lp(G.MATCH, G.dpi(38f)))
            sup.addView(MC.Btn(ctx, "secondary", "Take a break for now", icon = "Coffee", small = true) { if (!ended) endCall("ended_early"); backToGames() }, G.lp(G.MATCH, G.dpi(38f)).m(t = 8f))
            sc.body.addView(sup, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        }
        // the note when the game has ended
        if (ended) {
            val note: Pair<String, String> = when {
                reason == "reported" && (reported || by == "me") -> "ShieldCheck" to "Report received. You won’t be matched with ${s.obj("partner").str("name").ifEmpty { "them" }} again. Thanks for keeping True Find safe."
                reason == "partner_left" -> "Info" to "$pname lost connection, so the call ended."
                reason == "chat" -> "Info" to if (by == "me") "The game has ended." else "$pname went to your Reconnect chat. The game has ended."
                reason == "done" -> "Info" to "The game is over. Thanks for the chat!"
                else -> "Info" to if (by == "me") "You ended the call. Thanks for playing!" else notice.ifEmpty { "The game has ended." }
            }
            sc.body.addView(MC.noteBox(ctx, note.first, note.second), G.lp(G.MATCH, G.WRAP).m(t = 14f))
        }
        // the Reconnect card
        val secret = secretView(ended)
        var deciding = false
        if (secret != null) { sc.body.addView(secret.first, G.lp(G.MATCH, G.WRAP).m(t = 18f)); deciding = secret.second in listOf("ask", "loading", "invited") }
        if (ended) {
            sc.addFoot(MC.Btn(ctx, "primary", "Play again", icon = "RefreshCw") { playAgain() }, G.dpi(50f))
            sc.addFoot(MC.Btn(ctx, "secondary", "Back to Games") { backToGames() }, G.dpi(50f))
        } else if (!deciding) sc.addFoot(MC.Btn(ctx, "secondary", "End the game") { endCall("ended_early") }, G.dpi(50f))
        return sc.root
    }

    /** the Reconnect card and its view name */
    private fun secretView(ended: Boolean): Pair<View, String>? {
        val ctx = context
        val s = session!!
        val sv = s.obj("secret")
        val name = s.obj("partner").str("name").ifEmpty { "your partner" }
        var view = ""; var mine = false; var matchId = ""; var locked = false
        if (isHost) {
            if (sv != null) {
                view = mapOf("sent" to "sent", "accepted" to "accepted", "declined" to "declined")[sv.str("status")] ?: ""
                mine = true; matchId = sv.str("match_id"); locked = sv.bool("locked")
            } else if (secretNote.str("kind") == "connected") { view = "connected"; matchId = secretNote.str("match_id") }
            else if (secretOpen() && offer.str("why") != "skipped") {
                val o = offer
                view = when { o == null -> "loading"; o.bool("can") -> "ask"; o.str("why") != "error" -> "unavailable"; else -> "" }
            }
        } else if (sv != null) {
            view = mapOf("sent" to "invited", "accepted" to "accepted", "declined" to "declined")[sv.str("status")] ?: ""
            matchId = sv.str("match_id")
        } else if (!ended) view = "thanks"
        if (view.isEmpty()) return null
        val PLAN = "1 day, 7 days or 1 month"
        val box = G.vbox(ctx).apply { background = G.round(Color.WHITE, 20f, MC.line, 1f); pad(16f) }
        fun head(icon: String, title: String, sub: String?, strokeW: Float = 2f) {
            val h = G.hbox(ctx, 12f)
            h.addView(FrameLayout(ctx).apply {
                background = CssBg(15f, listOf(CssBg.Linear(180f, intArrayOf(G.c("#F43D95"), G.c("#C80962")))))
                addView(G.icon(ctx, icon, Color.WHITE, 24f, strokeW), LayoutParams(G.dpi(24f), G.dpi(24f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(G.dpi(50f), G.dpi(50f)))
            val c = G.vbox(ctx)
            c.addView(MC.t(ctx, title, MC.LG, MC.ink, true))
            if (sub != null) c.addView(MC.t(ctx, sub, MC.SM, MC.inkSoft))
            h.addView(c, G.lpw(1f))
            box.addView(h, G.lp(G.MATCH, G.WRAP).m(b = 14f))
        }
        /** .waiting (.is-no: the warm red one) */
        fun waiting(text: CharSequence, kind: String = "", icon: String? = null, avatar: Boolean = false, spinner: Boolean = false, spinnerFirst: Boolean = false, extra: View? = null): View {
            val col = when (kind) { "ok" -> G.c("#0C6B44"); "no" -> G.c("#8A3B1E"); else -> MC.ink }
            val bg = when (kind) { "ok" -> G.c("#E8F7F0"); "no" -> G.c("#FFF4F0"); else -> MC.wash }
            return waitingBox(MC.t(ctx, text, MC.SM, col), avatar = avatar, spinner = spinner, spinnerFirst = spinnerFirst, icon = icon, iconColor = col, bg = bg, extra = extra)
        }
        val chatBtn: View? = when {
            locked -> MC.Btn(ctx, "primary", "Unlock chat · see plans", icon = "Lock") { layer.openWeb("/wallet?app=secret") }
            matchId.isNotEmpty() -> MC.Btn(ctx, "primary", "Open chat", icon = "MessageCircle") { openChat(matchId) }.also { it.isEnabled = !(busy || secretBusy) }
            else -> null
        }
        fun lockLine() {
            if (!locked) return
            val l = G.hbox(ctx, 8f, Gravity.TOP).apply { background = G.round(G.c("#FFF1F7"), 14f); pad(12f, 10f, 12f, 10f) }
            l.addView(G.icon(ctx, "Lock", G.c("#7A1043"), 16f), LinearLayout.LayoutParams(G.dpi(16f), G.dpi(16f)).m(t = 2f))
            l.addView(G.text(ctx, "Your chat with $name is locked. It opens as soon as you take a Reconnect plan ($PLAN).", MC.SM, G.c("#7A1043"), false).lineH(1.4f), G.lpw(1f))
            box.addView(l, G.lp(G.MATCH, G.WRAP).m(b = 12f))
        }
        val bz = busy || secretBusy
        when (view) {
            "loading" -> { head("Heart", "Reconnect with $name?", "One moment…"); box.addView(waiting("Checking your Reconnect options…", spinnerFirst = true), G.lp(G.MATCH, G.WRAP)) }
            "ask" -> {
                val o = offer!!
                head("Heart", "Reconnect with $name?", "Enjoyed talking? Send $name a Reconnect request before the call ends.")
                val mode = o.str("mode")
                val cost = when (mode) {
                    "send" -> "Uses 1 request credit from your plan · ${o.int("credits")} left."
                    "offer" -> "First-day offer: send it for just 1 coin. You have ${o.int("coins")} ${if (o.int("coins") == 1) "coin" else "coins"}."
                    else -> if (o.bool("plan")) "Free from the game. $name gets it now, and your chat is open." else "Free from the game. $name gets it now; your chat opens as soon as you take a Reconnect plan ($PLAN)."
                }
                val cl = G.hbox(ctx, 8f, Gravity.TOP).apply { background = G.round(G.c("#FFF1F7"), 14f); pad(12f, 10f, 12f, 10f) }
                cl.addView(G.icon(ctx, if (mode == "offer") "Coins" else if (mode == "free" && !o.bool("plan")) "Lock" else "Check", G.c("#7A1043"), 16f), LinearLayout.LayoutParams(G.dpi(16f), G.dpi(16f)).m(t = 2f))
                cl.addView(G.text(ctx, cost, MC.SM, G.c("#7A1043"), false).lineH(1.4f), G.lpw(1f))
                box.addView(cl, G.lp(G.MATCH, G.WRAP))
                if (!o.bool("member")) box.addView(MC.t(ctx, "Reconnect, True Find’s private chat space, gets added to your account.", MC.XS, MC.inkSoft), G.lp(G.MATCH, G.WRAP).m(t = 8f))
                // (no margin above these in the web: they sit right under the price line)
                if (secretNote.str("kind") == "coins") box.addView(waiting("You need 1 coin for the first-day offer.", "no", "Info", extra = MC.Btn(ctx, "secondary", "Add coins", small = true) { layer.openWeb("/wallet") }.also { it.layoutParams = LinearLayout.LayoutParams(G.WRAP, G.dpi(38f)) }), G.lp(G.MATCH, G.WRAP))
                if (secretNote.str("kind") == "error") box.addView(waiting(secretNote.str("text"), "no", "Info"), G.lp(G.MATCH, G.WRAP))
                box.addView(MC.Btn(ctx, "primary", if (bz) "Sending…" else if (mode == "offer") "Send for 1 coin" else "Yes, send request", iconRight = if (bz) null else "ArrowRight") { sendSecret() }.also { it.isEnabled = !bz }, G.lp(G.MATCH, G.dpi(50f)).m(t = 12f))
                box.addView(MC.Btn(ctx, "secondary", if (ended) "No thanks" else "No thanks, end the game") { skipSecret() }.also { it.isEnabled = !bz }, G.lp(G.MATCH, G.dpi(50f)).m(t = 8f))
            }
            "sent" -> {
                head("Check", "Request sent to $name", if (locked) "She can see your request now." else "Your Reconnect chat is open — say hi anytime.", 2.6f)
                box.addView(waiting("Waiting for $name’s answer…", avatar = true, spinner = true), G.lp(G.MATCH, G.WRAP).m(b = 12f))
                lockLine()
                chatBtn?.let { box.addView(it, G.lp(G.MATCH, G.dpi(50f))) }
            }
            "accepted" -> {
                head("HeartHandshake", if (mine) "$name accepted!" else "You’re connected with $name", if (locked) "Your chat is waiting for you in Reconnect." else "Your private chat is ready in Reconnect.")
                lockLine()
                chatBtn?.let { box.addView(it, G.lp(G.MATCH, G.dpi(50f))) }
            }
            "declined" -> head("Heart", "Thanks for the chat!", if (mine) "$name isn’t ready to Reconnect right now." else "No problem — you said not now.")
            "connected" -> { head("HeartHandshake", "You and $name are already connected", "Your chat is in Reconnect."); chatBtn?.let { box.addView(it, G.lp(G.MATCH, G.dpi(50f))) } }
            "unavailable" -> head("Heart", "Thanks for the chat!", "A Reconnect request isn’t available with $name.")
            "invited" -> {
                head("Heart", "$name wants to Reconnect with you", "Accept to open a private chat in Reconnect.")
                box.addView(MC.Btn(ctx, "primary", if (bz) "One moment…" else "Accept", iconRight = "ArrowRight") { answerSecret(true) }.also { it.isEnabled = !bz }, G.lp(G.MATCH, G.dpi(50f)))
                box.addView(MC.Btn(ctx, "secondary", "Not now") { answerSecret(false) }.also { it.isEnabled = !bz }, G.lp(G.MATCH, G.dpi(50f)).m(t = 8f))
            }
            "thanks" -> box.addView(waiting("The game is over. Thanks for the chat! You can keep talking, or end the call when you’re ready.", icon = "Check"), G.lp(G.MATCH, G.WRAP))
        }
        return box to view
    }

    private fun endedEarlyView(): View {
        val ctx = context
        val reason = session.obj("ended").str("reason")
        val name = partnerName()
        val sc = screen()
        sc.bar({ backToGames() })
        sc.body.addView(FrameLayout(ctx).apply {
            background = G.round(MC.accentTint, 20f)
            addView(G.icon(ctx, "Info", MC.accent, 30f), LayoutParams(G.dpi(30f), G.dpi(30f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(G.dpi(64f), G.dpi(64f)).m(t = 18f, b = 6f))
        sc.body.addView(MC.h1(ctx, if (reason == "timeout") "Time ran out" else "$name left the game"), G.lp(G.MATCH, G.WRAP).m(b = 6f))
        sc.body.addView(MC.lede(ctx, if (reason == "timeout") "Moods weren’t shared in time, so this game closed. Start a new one anytime." else "No worries — it happens. Find a new partner whenever you’re ready."), G.lp(G.MATCH, G.WRAP).m(b = 18f))
        sc.addFoot(MC.Btn(ctx, "primary", "Find a new game", icon = "RefreshCw") { playAgain() }, G.dpi(50f))
        sc.addFoot(MC.Btn(ctx, "secondary", "Back to Games") { backToGames() }, G.dpi(50f))
        return sc.root
    }

    // ───────── sheets ─────────
    private fun openLeave() {
        sheet?.close()
        val ctx = context
        val s = MC.sheet(this, "Leave the game?") { }
        sheet = s
        val p = session.obj("partner")
        s.panel.addView(MC.sheetP(ctx, if (p != null) "${p.str("name")} will see that you left." else "You can start again anytime."), G.lp(G.MATCH, G.WRAP).m(b = 14f))
        s.panel.addView(MC.Btn(ctx, "danger", "Leave the game") { leaveGame() }, G.lp(G.MATCH, G.dpi(50f)))
        s.panel.addView(MC.Btn(ctx, "secondary", "Stay") { s.close(); sheet = null }, G.lp(G.MATCH, G.dpi(50f)).m(t = 8f))
    }
    fun openSupport() {
        sheet?.close()
        val ctx = context
        val s = MC.sheet(this, "Talk to someone") { }
        sheet = s
        s.panel.addView(MC.sheetP(ctx, "If things feel heavy, talking to someone you trust helps. Tele-MANAS is a free, private helpline, open 24×7."), G.lp(G.MATCH, G.WRAP).m(b = 14f))
        s.panel.addView(MC.Btn(ctx, "primary", "Call Tele-MANAS (14416)", icon = "HeartHandshake") { dial() }, G.lp(G.MATCH, G.dpi(50f)))
        s.panel.addView(MC.Btn(ctx, "secondary", "Back") { s.close(); sheet = null }, G.lp(G.MATCH, G.dpi(50f)).m(t = 8f))
    }
    fun dial() { try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:14416"))) } catch (e: Throwable) { } }

    // ───────── animations ─────────
    private fun floatAnim(v: View, delay: Long) {
        anims.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            currentPlayTime = delay // CSS animation-delay: -d s → already d s in
            addUpdateListener { val t = it.animatedValue as Float; v.translationY = -G.dp(5f) * G.ease(if (t < 0.5f) t * 2 else (1 - t) * 2) }
            start()
        })
    }
    private fun pulse(v: View, delay: Long) {
        anims.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = ValueAnimator.INFINITE; startDelay = delay
            interpolator = android.view.animation.PathInterpolator(0f, 0f, 0.58f, 1f) // ease-out
            addUpdateListener { val t = it.animatedValue as Float; val s = 1f + 1.15f * t; v.scaleX = s; v.scaleY = s; v.alpha = 0.9f * (1 - t) }
            start()
        })
    }
    private fun blip(v: View, phase: Long) {
        anims.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            currentPlayTime = phase
            addUpdateListener { val t = it.animatedValue as Float; v.alpha = 0.25f + 0.75f * G.ease(if (t < 0.5f) t * 2 else (1 - t) * 2) }
            start()
        })
    }

    // ───────── back / leave ─────────
    override fun onBack(): Boolean {
        sheet?.let { if (it.isOpen) { it.close(); sheet = null; return true } }
        if (callView?.onBack() == true) return true
        if (boot != "ready") return false
        val s = session
        // inside a game: the web's back buttons (leave sheet / previous step)
        if (s != null && status == "mood") {
            when {
                s.obj("mine").obj("mood") != null || moodStep == "matched" -> openLeave()
                moodStep == "pick" -> { moodStep = "matched"; render() }
                moodStep == "detail" -> { moodStep = "pick"; render() }
                else -> { moodStep = "detail"; render() }
            }
            return true
        }
        if (s != null && status == "reveal") { openLeave(); return true }
        if (s == null && screen in listOf("rules", "guidelines")) { screen = "intro"; render(); return true }
        if (s == null && screen == "search") { cancelSearch(); return true }
        return false
    }
    /** leaving the screen ends the game for both (web: pagehide / unmount) */
    override fun leave() {
        val s = session
        pollRun++; searchRun++
        voice.stop()
        if (s != null && s.str("status") in OPEN) {
            GameApi.call("moodChanger", JSONObject().put("action", if (s.str("status") in VOICE_ON) "end" else "cancel").put("session_id", s.str("id")).put("reason", "left")) { }
        }
    }
    override fun destroy() {
        callView?.dispose(); voice.stop()
        super.destroy()
    }
}
