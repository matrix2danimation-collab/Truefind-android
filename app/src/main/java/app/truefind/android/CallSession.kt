package app.truefind.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * One call on the native call screen: the logic of the web's pages/CallScreen.jsx, step for step, on the
 * same Base44 server functions and Call records (startCall / respondCall / endCall / callToken / callDate /
 * callFeedback / getWallet), so a call between the app and the website works both ways.
 *
 *  - ringing (my outgoing call): the call is checked every 2.5 s (the live update can get lost), the
 *    microphone (+ camera) is asked for before anyone is billed, and it gives up after 45 s ("no_answer")
 *  - active: sound / video join (CallMedia), a keep-alive "tick" every 15 s tells how many seconds the
 *    caller can still afford (counted down in between), the wallet every 20 s, the duration every second,
 *    who is talking every 150 ms
 *  - over (ended / declined / missed): the bill is shown, the stars sheet once for a call that was
 *    picked up, and the screen leaves for Recent (or stays for "Add coins" when the coins ran out)
 *
 * Modes: REAL (a /call/:id address), PRACTICE (/demo-call: Explore's demo profiles — nothing connects,
 * nothing is billed, as DemoCall.jsx) and PREVIEW (test pictures on the emulator: made-up data, no server).
 */
class CallSession(
    private val ctx: Context,
    val callId: String,
    val mode: Mode,
    private val hint: JSONObject?,
    private val routeSearch: String,
    private val knownMeId: String,
    private val host: Host,
) {
    enum class Mode { REAL, PRACTICE, PREVIEW }
    interface Host {
        fun changed()
        /** close the call screen, then open [to] (null: stay on the page under it) */
        fun leave(to: String?)
        fun askPerms(video: Boolean, done: (Boolean) -> Unit)
        /** this call is over (the pill, the incoming screen and the notification forget it) */
        fun finished(id: String)
    }
    data class Settled(val status: String, val charged: Long, val seconds: Long)

    companion object {
        val DONE = setOf("ended", "declined", "missed", "settling", "settle_failed")
        private const val RING_GIVE_UP_MS = 45000L
        private const val HOLD = 450L
        fun utcMs(v: String?): Long = Tf.ms(v, plainUtc = true)
    }

    private val main = Handler(Looper.getMainLooper())
    private var disposed = false
    private var unsub: (() -> Unit)? = null
    var media: CallMedia? = null; private set
    val practice get() = mode == Mode.PRACTICE || (mode == Mode.PREVIEW && !previewAsReal)

    // ───────── state (the same names as CallScreen.jsx) ─────────
    var call: JSONObject? = null; private set
    var role = ""; private set
    var otherName = ""; private set
    var otherPhoto = ""; private set
    var meName = ""; private set
    var mePhoto = ""; private set
    var profilesReady = false; private set
    var loadError = ""; private set
    var error = ""; private set
    var errorDetail = ""; private set
    var mediaState = "idle"; private set
    var peerConnected = false; private set
    var muted = false; private set
    var camOff = false; private set
    var elapsed = 0L; private set
    var settled: Settled? = null; private set
    private var remaining: Long? = null
    private var tickAt = 0L
    var ranOut = false; private set
    var peerVideoOn = false; private set
    var netState = ""; private set
    var rateAsk = false; private set
    private var rateAskRef = false
    var ratingAsked = false
    var meSpeaking = false; private set
    var peerSpeaking = false; private set
    var speakerOn = false; private set
    var coins: Long? = null; private set
    var everConnected = false; private set
    private var ending = false
    private var meAt = 0L
    private var peerAt = 0L
    private var lastStatus = ""
    private var zeroAsked = false
    // practice (DemoCall.jsx): the short "Calling…" moment, the made-up talking, the heart she gives back
    var practiceRinging = false; private set
    var practiceEnded = false; private set
    private var practiceSec = 0L
    private var practiceEndedAt = -1L
    var practiceLiked = false; private set
    var practiceTheyLiked = false; private set
    /** test pictures: a fixed number of seconds left (low balance pill) */
    private var previewSecsLeft: Long? = null

    // ───────── what the screen reads ─────────
    val status: String get() = Tf.s(call, "status")
    // before the call's record has come, the type the caller's screen already knew (the hint): the screen says
    // "Video call" from the first moment instead of "Voice call" that turns into "Video call" (owner, 19 Nov)
    val type: String get() = if ((call?.let { Tf.s(it, "type") } ?: hint?.optString("type").orEmpty()) == "video") "video" else "voice"
    val video: Boolean get() = type == "video"
    val isVoice: Boolean get() = !video
    val isCaller: Boolean get() = role == "caller"
    val rate: Int get() = Tf.n(call, "rate_coins").toInt()
    val callApp: String get() = if (Tf.s(call, "app") == "secret") "secret" else "truefind"
    val isRandom: Boolean get() = routeSearch.contains("random=1")
    val demoCallee: Boolean get() = Tf.s(call, "callee_id").lowercase().startsWith("demo-user")
    val screenState: String get() = when {
        practice -> if (practiceRinging) "ringing" else if (practiceEnded) "ended" else "connected"
        call == null -> "connecting"
        status == "ringing" -> "ringing"
        status == "active" -> if (peerConnected) "connected" else "connecting"
        else -> "ended"
    }
    val endedAfterTalk: Boolean get() = if (practice) practiceEnded else settled != null && everConnected
    /** the connected screen (DateCallScreen) — before that, and for calls that never connected: the stage screen */
    val showConnected: Boolean get() = if (practice) !practiceRinging else profilesReady && ((screenState == "connected" && settled == null) || endedAfterTalk)
    val ended: Boolean get() = endedAfterTalk
    val seconds: Long get() = if (practice) (if (practiceEndedAt >= 0) practiceEndedAt else practiceSec) else elapsed
    val secsLeft: Long?
        get() {
            // the demo call: what the coins would last at this call's rate, counting down as it goes (nothing is billed);
            // no coins → no countdown (the duration shows instead)
            if (practice) { previewSecsLeft?.let { return it }; val c = coins ?: return null; val r = if (video) 40 else 10; val all = (c / r) * 60; return if (all <= 0) null else maxOf(0L, all - seconds) }
            val rem = remaining ?: return null
            return maxOf(0L, rem - (SystemClock.elapsedRealtime() - tickAt) / 1000)
        }
    val showLeft: Boolean get() = practice || isCaller
    val iLiked: Boolean get() = if (practice) practiceLiked else call?.optBoolean(if (isCaller) "caller_date_liked" else "callee_date_liked") == true
    val theyLiked: Boolean get() = if (practice) practiceTheyLiked else call?.optBoolean(if (isCaller) "callee_date_liked" else "caller_date_liked") == true
    /** the in-call Report button: a real call that is going on */
    val live: Boolean get() = !practice && status == "active" && !demoCallee
    val endedLabel: String get() {
        if (practice) return "Duration ${CallKit.fmt(seconds)}"
        val s = settled
        return when {
            s != null && s.seconds > 0 -> "Duration ${CallKit.fmt(s.seconds)}"
            s?.status == "declined" -> "Call declined"
            s?.status == "missed" -> "No answer"
            else -> "Call ended"
        }
    }
    val settledText: String get() = when (settled?.status) { "declined" -> "Call declined"; "missed" -> "No answer"; else -> "Call ended" }

    // ═════════════════ start ═════════════════
    fun start() {
        when (mode) {
            Mode.REAL -> loadReal()
            Mode.PRACTICE, Mode.PREVIEW -> startPractice()
        }
    }

    private fun emit() {
        if (disposed) return
        if (screenState == "connected") everConnected = true
        host.changed()
    }

    private fun loadReal() {
        unsub = Realtime.subscribe("Call") { ev -> onEvent(ev) }
        fun withMe(myId: String, me: JSONObject) {
            Api.get("Call", callId) { cr ->
                if (disposed) return@get
                val c = if (cr.ok) cr.data else null
                if (c == null || Tf.s(c, "id").isEmpty() || (Tf.s(c, "caller_id") != myId && Tf.s(c, "callee_id") != myId)) {
                    loadError = if (cr.status == 0 && cr.error != null) (cr.error ?: "Could not load the call.") else "Call not found."
                    emit(); return@get
                }
                role = if (Tf.s(c, "caller_id") == myId) "caller" else "callee"
                // show the call screen (and its hang-up button) right away; names and photos fill in when they arrive
                val h = if (role == "caller") hint?.optJSONObject("peer") else null
                otherName = Tf.s(h, "name").ifEmpty { if (role == "callee") Tf.s(c, "caller_name") else "" }
                meName = Tf.s(me, "full_name")
                profilesReady = true
                setCall(c)
                val otherId = if (role == "caller") Tf.s(c, "callee_id") else Tf.s(c, "caller_id")
                var other: Pair<String, String>? = null; var mine: Pair<String, String>? = null
                fun both() {
                    val o = other ?: return; val m = mine ?: return
                    if (disposed) return
                    meName = m.first.ifEmpty { Tf.s(me, "full_name") }
                    mePhoto = m.second
                    fun apply(p: Pair<String, String>?) {
                        if (disposed) return
                        if (p != null && p.first.isNotEmpty()) otherName = p.first
                        val photo = p?.second.orEmpty().ifEmpty { Tf.s(h, "photo") }
                        if (photo.isNotEmpty()) otherPhoto = photo
                        emit()
                    }
                    if (o.first.isNotEmpty()) apply(o) else peerFromRecent(otherId) { apply(it) }
                }
                loadProfile(otherId) { other = it; both() }
                loadProfile(myId) { mine = it; both() }
            }
        }
        if (knownMeId.isNotEmpty()) {
            Api.me { r -> if (!disposed) withMe(knownMeId, if (r.ok) r.data else JSONObject()) }
        } else Api.me { r ->
            if (disposed) return@me
            val id = Tf.s(r.data, "id")
            if (!r.ok || id.isEmpty()) { loadError = r.error ?: "Could not load the call."; emit(); return@me }
            withMe(id, r.data)
        }
    }

    /** name + photo of any user (their own profile record; other people's are usually private) */
    private fun loadProfile(uid: String, done: (Pair<String, String>) -> Unit) {
        if (uid.isEmpty()) { done("" to ""); return }
        val q = JSONObject().put("\$or", JSONArray().put(JSONObject().put("created_by_id", uid)).put(JSONObject().put("demo_user_id", uid)))
        Api.filter("UserProfile", q, limit = 1) { r ->
            val p = if (r.ok) Tf.list(r).firstOrNull() else null
            done(Tf.s(p, "full_name") to Tf.s(p, "profile_picture"))
        }
    }

    /** the other person's name and photo from the recent-calls list (other people's profiles are private) */
    private fun peerFromRecent(uid: String, done: (Pair<String, String>?) -> Unit) {
        Api.invoke("getRecentActivity") { r ->
            val e = Tf.arr(r.data.optJSONArray("entries")).firstOrNull { Tf.s(it, "user_id") == uid }
            if (e == null) { done(null); return@invoke }
            val n = Tf.s(e, "name")
            done((if (n.isNotEmpty() && n != "Member") n else "") to Tf.s(e, "photo"))
        }
    }

    private fun onEvent(ev: Realtime.Event) {
        val d = ev.data ?: return
        // before the call is loaded (whose call, my side) the first read gives the fresh record anyway
        if (Tf.s(d, "id") != callId || role.isEmpty()) return
        // a late "ringing" / "active" update must never bring back a call that is already over
        if (DONE.contains(status) && !DONE.contains(Tf.s(d, "status"))) return
        setCall(d)
    }

    private fun setCall(c: JSONObject) {
        if (disposed) return
        // a late "ringing" / "active" record (a slow first read, an old answer) never brings back a call that is over
        if (DONE.contains(status) && !DONE.contains(Tf.s(c, "status"))) return
        call = c
        onStatus()
    }

    // ═════════════════ what each status starts and stops ═════════════════
    private fun onStatus() {
        val st = status
        if (st == lastStatus) { emit(); return }
        lastStatus = st
        main.removeCallbacks(ringPoll); main.removeCallbacks(giveUp)
        if (st != "active") { main.removeCallbacks(tickRun); main.removeCallbacks(walletRun); main.removeCallbacks(second); main.removeCallbacks(talk) }
        when {
            st == "ringing" && isCaller -> {
                main.postDelayed(ringPoll, 2500)
                main.postDelayed(giveUp, RING_GIVE_UP_MS)
                askPermsWhileRinging()
            }
            st == "active" -> {
                startMedia()
                main.post(tickRun)
                main.post(walletRun)
                main.post(second)
            }
            DONE.contains(st) -> onDone()
        }
        emit()
    }

    // ── ringing ──
    /** while it rings, check the call every 2.5 s too: the live "picked up" can get lost on a weak network */
    private val ringPoll = object : Runnable {
        override fun run() {
            if (disposed || status != "ringing" || !isCaller) return
            Api.get("Call", callId) { r ->
                if (!disposed && r.ok && status == "ringing" && Tf.s(r.data, "status").let { it.isNotEmpty() && it != "ringing" }) setCall(r.data)
            }
            main.postDelayed(this, 2500)
        }
    }

    /**
     * 45 s without an answer: ask once more (a missed live update must not end a call that was picked up),
     * then end it as "no_answer" (if they picked up at that very moment, the server keeps the call going).
     */
    private val giveUp = Runnable {
        if (disposed || status != "ringing" || !isCaller) return@Runnable
        Api.get("Call", callId) { r ->
            if (disposed || status != "ringing") return@get
            val fresh = if (r.ok) r.data else null
            if (fresh != null && Tf.s(fresh, "status").let { it.isNotEmpty() && it != "ringing" }) { setCall(fresh); return@get }
            Api.invoke("endCall", JSONObject().put("call_id", callId).put("reason", "no_answer")) { er ->
                if (disposed) return@invoke
                er.data.optJSONObject("call")?.let { c -> if (Tf.s(c, "status") != status) setCall(c) }
            }
        }
    }

    /** the caller's microphone (and camera) is asked for while it rings, before anyone is billed */
    private var permAsked = false
    private fun askPermsWhileRinging() {
        if (permAsked) return
        permAsked = true
        host.askPerms(video) { ok ->
            if (disposed || ok || status != "ringing") return@askPerms
            error = "Allow ${if (video) "microphone and camera" else "microphone"} access for True Find, then call again."
            ending = true
            emit()
            Api.invoke("endCall", JSONObject().put("call_id", callId)) { r ->
                if (disposed) return@invoke
                val c = r.data.optJSONObject("call")
                if (r.ok && c != null) setCall(c) else ending = false
            }
        }
    }

    // ── active ──
    private fun startMedia() {
        if (mediaState != "idle" || role.isEmpty() || practice) return
        mediaState = "preparing"
        val m = CallMedia(ctx, callId, video, mediaListener)
        media = m
        if (muted) m.setMuted(true)
        if (camOff) m.setCamOff(true)
        if (speakerOn) m.setLoud(true)
        m.start()
    }

    private val mediaListener = object : CallMedia.Listener {
        override fun onMediaState(state: String) {
            if (disposed) return
            mediaState = state
            if (state == "ready") { main.removeCallbacks(talk); main.post(talk) }
            emit()
        }
        override fun onMediaError(msg: String, detail: String) { if (!disposed) { error = msg; errorDetail = detail; emit() } }
        override fun onPeerJoined() { if (!disposed && !peerConnected) { peerConnected = true; emit() } }
        override fun onPeerLeft() { if (!disposed) hangUp() }
        override fun onPeerVideo(on: Boolean) { if (!disposed && peerVideoOn != on) { peerVideoOn = on; emit() } }
        override fun onNet(state: String) { if (!disposed && netState != state) { netState = state; emit() } }
        override fun onLevels(me: Boolean?, peer: Boolean?) {
            val now = SystemClock.elapsedRealtime()
            if (me == true) meAt = now
            if (peer == true) { peerAt = now; if (!peerConnected) { peerConnected = true; emit() } }
        }
    }

    /** keep-alive + how long the caller can still talk; when coins run out the server ends the call */
    private val tickRun = object : Runnable {
        override fun run() {
            if (disposed || status != "active") return
            tick()
            main.postDelayed(this, 15000)
        }
    }

    private fun tick() {
        Api.invoke("endCall", JSONObject().put("call_id", callId).put("action", "tick")) { r ->
            if (disposed || status != "active" || !r.ok) return@invoke
            val d = r.data
            if (d.optBoolean("ended")) {
                if (d.optBoolean("ran_out")) ranOut = true
                val c = d.optJSONObject("call")
                if (c != null) setCall(c) else emit()
                return@invoke
            }
            if (d.has("remaining_seconds") && !d.isNull("remaining_seconds")) {
                tickAt = SystemClock.elapsedRealtime()
                remaining = d.optLong("remaining_seconds")
                zeroAsked = false
                emit()
            }
        }
    }

    /** the coin balance on the call screen (the wallet of this call's app), kept fresh while talking */
    private val walletRun = object : Runnable {
        override fun run() {
            if (disposed || status != "active") return
            Api.invoke("getWallet", JSONObject().put("app", callApp)) { r ->
                val v = r.data.optJSONObject("wallet")?.opt("coins")
                if (!disposed && v is Number) { coins = v.toLong(); emit() }
            }
            main.postDelayed(this, 20000)
        }
    }

    /** the duration and the seconds left, every second; at 0 left the server is asked at once (it ends the call) */
    private val second = object : Runnable {
        override fun run() {
            if (disposed || status != "active") return
            val started = utcMs(Tf.s(call, "started_at"))
            if (started > 0) elapsed = maxOf(0L, (System.currentTimeMillis() - started) / 1000)
            val left = secsLeft
            if (left == 0L && !zeroAsked) {
                zeroAsked = true
                Api.invoke("endCall", JSONObject().put("call_id", callId).put("action", "tick")) { r ->
                    if (disposed) return@invoke
                    val d = r.data
                    if (d.optBoolean("ended")) { if (d.optBoolean("ran_out")) ranOut = true; d.optJSONObject("call")?.let { setCall(it) } ?: emit() }
                }
            }
            emit()
            main.postDelayed(this, 1000)
        }
    }

    /** who is talking: the bars stay a moment after they stop, so they don't flicker between words */
    private val talk = object : Runnable {
        override fun run() {
            if (disposed || status != "active" || mediaState != "ready") { meSpeaking = false; peerSpeaking = false; return }
            val now = SystemClock.elapsedRealtime()
            val m = !muted && now - meAt < HOLD
            val p = now - peerAt < HOLD
            if (m != meSpeaking || p != peerSpeaking) { meSpeaking = m; peerSpeaking = p; emit() }
            main.postDelayed(this, 150)
        }
    }

    // ── over ──
    private val autoLeave = Runnable { if (!disposed) host.leave("/chats") }

    private fun onDone() {
        val c = call ?: return
        host.finished(Tf.s(c, "id"))
        settled = Settled(status, Tf.n(c, "charged_coins").toLong(), Tf.n(c, "duration_seconds").toLong())
        stopMedia()
        meSpeaking = false; peerSpeaking = false
        // a call that was picked up: the stars first (the sheet leaves the screen itself when done)
        val talked = Tf.s(c, "started_at").isNotEmpty() || Tf.n(c, "duration_seconds") > 0 || everConnected
        if (talked && !ratingAsked && !demoCallee) {
            ratingAsked = true
            rateAskRef = true
            // voice: the soft "Call ended" card shows for a moment before the stars sheet slides up
            if (isVoice) main.postDelayed({ if (!disposed) { rateAsk = true; emit() } }, 1600) else rateAsk = true
            return
        }
        if (rateAskRef) return
        if (ranOut && isCaller) return // stay so they can tap "Add coins"
        // voice: a little longer, so the "call ended" card can be read
        main.postDelayed(autoLeave, if (isVoice) 4500 else 3000)
    }

    /** the stars sheet closed: leave like before (a caller whose coins ran out stays to tap "Add coins") */
    fun afterRating() {
        rateAskRef = false
        rateAsk = false
        if (ranOut && isCaller) { emit(); return }
        host.leave("/chats")
    }

    /** "Close" on a call that never connected: Random Match starts a new search, other calls go to Recent */
    fun onCallEnded() {
        if (practice) { host.leave(null); return }
        host.leave(if (isRandom) "/home?random=$type" else "/chats")
    }

    // ═════════════════ actions ═════════════════
    fun hangUp() {
        if (ending) return
        ending = true
        if (practice) { practiceEnd(); return }
        Api.invoke("endCall", JSONObject().put("call_id", callId)) { r ->
            if (disposed) return@invoke
            if (r.ok) r.data.optJSONObject("call")?.let { setCall(it) }
            else { error = r.error ?: "Could not end the call."; ending = false; emit() }
        }
    }

    /** End and open the Wallet (the low-balance pill, the add-coins sheet, Go Premium) */
    fun endAndGo(to: String) {
        if (!practice) hangUp()
        host.leave(to)
    }

    fun toggleMute() { muted = !muted; media?.setMuted(muted); if (muted) meSpeaking = false; emit() }
    fun toggleCam() { camOff = !camOff; media?.setCamOff(camOff); emit() }
    fun toggleSpeaker() { speakerOn = !speakerOn; media?.setLoud(speakerOn); emit() }

    /** "Date This": the pink heart (the server marks the call, both phones see it live). done(error or null) */
    fun like(done: (String?) -> Unit) {
        if (practice) {
            practiceLiked = true; emit(); done(null)
            // she likes you back a few seconds after your heart (practice only)
            main.postDelayed({ if (!disposed && !practiceTheyLiked) { practiceTheyLiked = true; emit() } }, 6000)
            return
        }
        Api.invoke("callDate", JSONObject().put("call_id", callId).put("action", "like")) { r ->
            if (disposed) return@invoke
            if (r.ok) Api.get("Call", callId) { cr -> if (!disposed && cr.ok && Tf.s(cr.data, "id") == callId) setCall(cr.data) }
            done(if (r.ok) null else Tf.s(r.data, "message", "error").ifEmpty { "Could not send the heart. Try again." })
        }
    }

    // ═════════════════ practice (/demo-call) and test pictures ═════════════════
    private val practiceTick = object : Runnable {
        override fun run() {
            if (disposed || practiceEnded) return
            practiceSec += 1
            // the demo's coins would be used up now: it ends the way a real call does at 0 left
            if (mode == Mode.PRACTICE && secsLeft == 0L) { practiceEnd(); return }
            // made-up talking, 3 s each: her, you, both together, her again
            val phase = (practiceSec / 3) % 4
            peerSpeaking = phase == 0L || phase == 2L || phase == 3L
            meSpeaking = (phase == 1L || phase == 2L) && !muted
            emit()
            main.postDelayed(this, 1000)
        }
    }

    private fun startPractice() {
        val p = hint?.optJSONObject("peer") ?: JSONObject().put("name", "Riya")
        otherName = Tf.s(p, "name"); otherPhoto = Tf.s(p, "photo")
        val v = Tf.s(hint, "type") == "video"
        call = JSONObject().put("id", "demo").put("status", "active").put("type", if (v) "video" else "voice")
            .put("rate_coins", if (v) 40 else 10).put("caller_id", "me").put("callee_id", "demo-user").put("app", "truefind")
        role = "caller"
        profilesReady = true
        practiceRinging = hint?.optBoolean("skipRing") != true
        if (mode == Mode.PREVIEW) { practiceRinging = false; return }
        if (mode == Mode.PRACTICE) {
            Api.me { r ->
                if (disposed) return@me
                val id = Tf.s(r.data, "id")
                meName = Tf.s(r.data, "full_name")
                if (id.isNotEmpty()) loadProfile(id) { (n, ph) -> if (!disposed) { if (n.isNotEmpty()) meName = n; mePhoto = ph; emit() } }
            }
            Api.invoke("getWallet", JSONObject().put("app", "truefind")) { r ->
                val c = r.data.optJSONObject("wallet")?.opt("coins")
                if (!disposed && c is Number) { coins = c.toLong(); emit() }
            }
        }
        // a short "Calling…" moment first (like a real call ringing), then the call screen fades in
        if (practiceRinging) main.postDelayed({ if (!disposed) { practiceRinging = false; practiceSec = 0; emit(); main.postDelayed(practiceTick, 1000) } }, 1800)
        else main.postDelayed(practiceTick, 1000)
        emit()
    }

    private fun practiceEnd() {
        if (practiceRinging) { host.leave(null); return }
        if (practiceEnded) return
        practiceEnded = true
        practiceEndedAt = practiceSec
        meSpeaking = false; peerSpeaking = false
        emit()
        // End: the soft "Call ended" card shows first, then back to the page you came from
        main.postDelayed({ if (!disposed) host.leave(null) }, 1800)
    }

    /**
     * Test pictures only (emulator, made-up data, no server): put the screen into one state.
     * Stage screens of a real call: ringing · connecting · declined · missed · error.
     * Connected screens (talk/ended variants): words in [scene] switch parts on — talkp (she talks), talkm (I talk),
     * talkmp (both), muted, speaker, liked, theyliked, camoff, low (balance running low), ended, ranout, long.
     */
    fun preview(scene: String, me: String, meFace: String, peer: String, peerFace: String) {
        meName = me; mePhoto = meFace; otherName = peer; otherPhoto = peerFace
        coins = 1250
        main.removeCallbacks(practiceTick)
        practiceRinging = false
        val base = call ?: JSONObject()
        fun asReal(st: String, r: String) {
            previewAsReal = true
            role = r
            profilesReady = true
            call = JSONObject(base.toString()).put("status", st).put("callee_id", "u2").put("caller_id", "u1")
            lastStatus = st
        }
        when (scene) {
            "ringing" -> asReal("ringing", "caller")
            "connecting" -> asReal("active", "callee")
            "declined" -> { asReal("declined", "caller"); settled = Settled("declined", 0, 0) }
            "missed" -> { asReal("missed", "caller"); settled = Settled("missed", 0, 0) }
            "error" -> {
                asReal("active", "caller")
                error = "Could not connect the call. Check your internet and try again."; errorDetail = "JOIN_TIMEOUT"
            }
            else -> {
                practiceSec = if (scene.contains("long")) 754 else 83
                val talk = Regex("talk([mp]+)").find(scene)?.groupValues?.get(1).orEmpty()
                peerSpeaking = talk.contains("p")
                meSpeaking = talk.contains("m")
                if (scene.contains("muted")) { muted = true; meSpeaking = false }
                if (scene.contains("speaker")) speakerOn = true
                if (scene.contains("liked")) practiceLiked = true
                if (scene.contains("theyliked")) practiceTheyLiked = true
                if (scene.contains("camoff")) camOff = true
                if (scene.contains("peeroff")) peerVideoOn = false else peerVideoOn = true
                // made-up numbers that agree with each other (10 coins/min voice, 40 video, as the practice call)
                if (scene.contains("low")) { previewSecsLeft = 24; coins = if (video) 16 else 4 } // 24 s of talk left
                if (scene.contains("reconnect")) netState = "RECONNECTING"
                if (scene.contains("ended")) { practiceEnded = true; practiceEndedAt = 312; meSpeaking = false; peerSpeaking = false }
                if (scene.contains("ranout")) { ranOut = true; coins = 0 }
                if (scene.contains("charged")) previewCharge = Math.round((if (video) 40 else 10) * 312 / 60.0) // 5:12 talked, as the server bills
            }
        }
        emit()
    }
    /** test pictures: the page of a call that can't be opened ("Call not found.") */
    fun previewLoadError() { previewAsReal = true; call = null; loadError = "Call not found."; emit() }

    /** test pictures: a real call's stage screen is drawn (not the practice call) */
    private var previewAsReal = false
    /** test pictures: the coins charged on the "Call ended" card */
    var previewCharge = 0L; private set
    /** test pictures: the low-balance pill and the seconds left */
    val previewLow: Boolean get() = previewSecsLeft != null

    // ═════════════════ leaving ═════════════════
    private fun stopMedia() {
        media?.stop()
        media = null
        if (mediaState != "failed") mediaState = "idle"
    }

    /** The screen closes: a call that is still ringing or running ends (nobody keeps paying for a call with no sound). */
    fun dispose() {
        if (disposed) return
        val st = status
        if (!practice && !ending && (st == "ringing" || st == "active")) {
            ending = true
            host.finished(callId)
            Api.invoke("endCall", JSONObject().put("call_id", callId)) { }
        }
        disposed = true
        main.removeCallbacksAndMessages(null)
        unsub?.invoke(); unsub = null
        stopMedia()
    }

    /** the app came back on screen: a ringing call is checked at once */
    fun onAppVisible() { if (!disposed && status == "ringing" && isCaller) { main.removeCallbacks(ringPoll); main.post(ringPoll) } }
}
