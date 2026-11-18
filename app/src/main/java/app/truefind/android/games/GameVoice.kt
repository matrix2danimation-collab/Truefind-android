package app.truefind.android.games

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import org.json.JSONObject

/**
 * Voice room of a 2-player game (Heart Chase Boxes, Mood Changer) — native port of useGameVoice.js.
 * A real Agora audio call between the two players, on the same Agora project, channel, uid numbers
 * and passes as the web (the game's own server function hands out the pass), so a player on the
 * app and a player on the website hear each other.
 *
 * state: idle → token → joining → live | unavailable | error
 */
class GameVoice(
    private val ctx: Context,
    private val fn: String,
    private val micText: String = "Allow the microphone so you can talk in the game.",
    private val onChange: () -> Unit,
) {
    var state = "idle"; private set
    var step = 0; private set
    var error = ""; private set
    var partnerIn = false; private set
    var speakingMe = false; private set
    var speakingPartner = false; private set
    var muted = false; private set
    var loud = false; private set
    var reconnecting = false; private set
    var onPartnerLeft: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var sessionId = ""
    private var run = 0            // every start / stop / retry gets a new number; old answers are ignored
    private var myUid = 0
    private var lastVoice = System.currentTimeMillis()

    fun silentFor(): Long = System.currentTimeMillis() - lastVoice
    fun markVoice() { lastVoice = System.currentTimeMillis() }

    /** On while [sessionId] is not empty (same as active && sessionId in the web hook). */
    fun setSession(id: String?) {
        val s = id.orEmpty()
        if (s == sessionId) return
        stopInternal()
        sessionId = s
        if (s.isNotEmpty()) begin()
    }

    fun retry() { if (sessionId.isEmpty()) return; stopInternal(); begin() }
    fun stop() { stopInternal(); sessionId = "" }

    fun toggleMute() {
        muted = !muted
        try { Engine.get()?.muteLocalAudioStream(muted) } catch (e: Throwable) { }
        onChange()
    }
    /** "Speaker": the partner's voice louder (same as the web: 250% instead of 100%). */
    fun toggleLoud() {
        loud = !loud
        try { Engine.get()?.adjustPlaybackSignalVolume(if (loud) 250 else 100) } catch (e: Throwable) { }
        onChange()
    }

    private fun set(st: String, err: String = "") { state = st; error = err; onChange() }

    private fun fail(r: Int, msg: String, code: String = "") {
        if (r != run) return
        state = "error"; error = if (code.isNotEmpty()) "$msg ($code)" else msg
        Engine.leave(this)
        onChange()
    }

    private fun begin() {
        val r = ++run
        state = "token"; step = 0; error = ""; partnerIn = false; reconnecting = false
        onChange()
        if (GameApi.demo) { main.postDelayed({ if (r == run) { step = 2; partnerIn = true; set("live") } }, 400); return }
        pass(r, 0)
    }

    private fun pass(r: Int, attempt: Int) {
        GameApi.call(fn, JSONObject().put("action", "token").put("session_id", sessionId)) { res ->
            if (r != run) return@call
            if (!res.ok) {
                val code = res.data.str("code")
                if (code.startsWith("AGORA_")) { fail(r, "Voice is not set up correctly.", code); return@call }
                if (res.status in listOf(401, 403, 404, 409, 410)) { fail(r, "The voice room is closed.", "PASS_${res.status}"); return@call }
                if (attempt < 2) { main.postDelayed({ if (r == run) pass(r, attempt + 1) }, 1200L * (attempt + 1)); return@call }
                fail(r, "Couldn’t open the voice room. Check your internet and try again."); return@call
            }
            val d = res.data
            val tk = d.obj("voice") ?: d
            if (tk.has("enabled") && !tk.optBoolean("enabled", true)) {
                state = "unavailable"; error = "Voice calls are not switched on in True Find yet."; onChange(); return@call
            }
            val appId = tk.str("app_id")
            if (appId.isEmpty()) { fail(r, "Couldn’t open the voice room. Try again.", "PASS_BAD"); return@call }
            step = 1; set("joining")
            askMic { ok ->
                if (r != run) return@askMic
                if (!ok) { fail(r, micText); return@askMic }
                join(r, appId, tk.str("channel"), tk.str("token").ifEmpty { null }, tk.int("uid"))
            }
        }
    }

    private fun join(r: Int, appId: String, channel: String, token: String?, uid: Int) {
        val engine = Engine.obtain(ctx, appId)
        if (engine == null) { fail(r, "The voice call couldn’t load. Check your internet."); return }
        myUid = uid
        Engine.listener = this
        Engine.run = r
        try {
            engine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
            engine.enableAudio()
            engine.setDefaultAudioRoutetoSpeakerphone(true)
            engine.enableAudioVolumeIndication(200, 3, true)
            engine.muteLocalAudioStream(muted)
            engine.adjustPlaybackSignalVolume(if (loud) 250 else 100)
            val opts = ChannelMediaOptions()
            opts.channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            opts.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            opts.publishMicrophoneTrack = true
            opts.autoSubscribeAudio = true
            val code = engine.joinChannel(token, channel, uid, opts)
            if (code < 0) { fail(r, "The voice call couldn’t connect. Check your internet and try again.", "JOIN_$code"); return }
        } catch (e: Throwable) {
            fail(r, "The voice call couldn’t connect. Try again.", e.javaClass.simpleName.take(40)); return
        }
        // a join that never answers (blocked network) must not leave "Connecting…" for ever
        main.postDelayed({ if (r == run && state == "joining") fail(r, "The voice call couldn’t connect. Check your internet and try again.", "JOIN_TIMEOUT") }, 30000)
    }

    private fun stopInternal() {
        run++
        Engine.leave(this)
        partnerIn = false; speakingMe = false; speakingPartner = false; reconnecting = false
        if (state != "idle") { state = "idle"; onChange() }
    }

    // ── from the Agora engine (already on the main thread) ──
    internal fun joined(r: Int) {
        if (r != run) return
        lastVoice = System.currentTimeMillis()
        step = 2; set("live")
    }
    internal fun userJoined(r: Int) { if (r == run) { partnerIn = true; onChange() } }
    internal fun userLeft(r: Int) {
        if (r != run) return
        partnerIn = false; speakingPartner = false; onChange()
        onPartnerLeft?.invoke()
    }
    internal fun connection(r: Int, isReconnecting: Boolean) { if (r == run && reconnecting != isReconnecting) { reconnecting = isReconnecting; onChange() } }
    internal fun volumes(r: Int, me: Boolean, partner: Boolean) {
        if (r != run) return
        val m = me && !muted
        if (m || partner) lastVoice = System.currentTimeMillis()
        if (m != speakingMe || partner != speakingPartner) { speakingMe = m; speakingPartner = partner; onChange() }
    }
    internal fun micFailed(r: Int, busy: Boolean) {
        fail(r, if (busy) "The microphone is busy or missing (another app may be using it)." else micText)
    }
    internal fun tokenWillExpire(r: Int) {
        fun renew(i: Int) {
            if (r != run || i >= 4) return
            GameApi.call(fn, JSONObject().put("action", "token").put("session_id", sessionId)) { res ->
                if (r != run) return@call
                val tk = res.data.obj("voice") ?: res.data
                val t = tk.str("token")
                if (res.ok && t.isNotEmpty()) { try { Engine.get()?.renewToken(t) } catch (e: Throwable) { } }
                else main.postDelayed({ renew(i + 1) }, 5000)
            }
        }
        renew(0)
    }
    internal fun uid() = myUid

    // ── the microphone permission (asked by the app screen) ──
    private fun askMic(done: (Boolean) -> Unit) {
        val ask = micAsker
        if (ask == null) done(true) else ask(done)
    }

    companion object {
        /** Set by the app: asks Android for the microphone, answers true when allowed. */
        var micAsker: (((Boolean) -> Unit) -> Unit)? = null

        /**
         * A 1-on-1 call takes Agora's one engine (CallMedia): a game voice still in a room leaves it, and the
         * games forget their handle on the engine, so the next game voice makes it again the way it needs it.
         */
        fun releaseEngine() = Engine.release()

        /**
         * Test builds only (the GitHub test phone): makes Agora's voice engine and sets it up exactly like
         * a call does, without joining or calling anyone, then drops it again. Answers "ok" or what failed,
         * so a missing part of the engine shows up in the test run instead of in a player's call.
         */
        fun selfCheck(ctx: Context): String {
            if (Engine.get() != null) return "skipped (an engine is in use)"
            return try {
                val cfg = RtcEngineConfig()
                cfg.mContext = ctx.applicationContext
                cfg.mAppId = "0123456789abcdef0123456789abcdef" // made up: nothing is joined
                cfg.mEventHandler = object : IRtcEngineEventHandler() {}
                val e = RtcEngine.create(cfg)
                val codes = listOf(
                    e.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION),
                    e.enableAudio(),
                    e.setDefaultAudioRoutetoSpeakerphone(true),
                    e.enableAudioVolumeIndication(200, 3, true),
                    e.muteLocalAudioStream(false),
                    e.adjustPlaybackSignalVolume(100),
                )
                RtcEngine.destroy()
                if (codes.all { it >= 0 }) "ok (Agora ${RtcEngine.getSdkVersion()})" else "setup codes $codes"
            } catch (t: Throwable) {
                "fail ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }

    /** One Agora engine for the whole app (making one is slow); each game voice borrows it. */
    private object Engine {
        private var engine: RtcEngine? = null
        private var engineAppId = ""
        var listener: GameVoice? = null
        var run = 0
        private val main = Handler(Looper.getMainLooper())

        fun get(): RtcEngine? = engine

        fun obtain(ctx: Context, appId: String): RtcEngine? {
            engine?.let { if (engineAppId == appId) return it }
            if (engine != null) { engine = null; try { RtcEngine.destroy() } catch (e: Throwable) { } }
            return try {
                val cfg = RtcEngineConfig()
                cfg.mContext = ctx.applicationContext
                cfg.mAppId = appId
                cfg.mEventHandler = handler
                RtcEngine.create(cfg).also { engine = it; engineAppId = appId }
            } catch (e: Throwable) { null }
        }

        fun release() {
            listener = null
            try { engine?.leaveChannel() } catch (e: Throwable) { }
            engine = null; engineAppId = ""
        }

        fun leave(v: GameVoice) {
            if (listener !== v) return
            listener = null
            try { engine?.leaveChannel() } catch (e: Throwable) { }
        }

        private fun on(f: (GameVoice, Int) -> Unit) {
            main.post { val l = listener ?: return@post; f(l, run) }
        }

        val handler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) { on { v, r -> v.joined(r) } }
            override fun onUserJoined(uid: Int, elapsed: Int) { on { v, r -> v.userJoined(r) } }
            override fun onUserOffline(uid: Int, reason: Int) { on { v, r -> v.userLeft(r) } }
            override fun onConnectionStateChanged(state: Int, reason: Int) {
                on { v, r -> v.connection(r, state == 4) } // CONNECTION_STATE_RECONNECTING
            }
            override fun onTokenPrivilegeWillExpire(token: String?) { on { v, r -> v.tokenWillExpire(r) } }
            override fun onLocalAudioStateChanged(state: Int, reason: Int) {
                // 3 = LOCAL_AUDIO_STREAM_STATE_FAILED; reason 3 = device busy, 4 = recording failed
                if (state == 3) on { v, r -> v.micFailed(r, reason == 3 || reason == 4) }
            }
            override fun onAudioVolumeIndication(speakers: Array<out IRtcEngineEventHandler.AudioVolumeInfo>?, totalVolume: Int) {
                var me = false; var pr = false
                if (speakers != null) for (s in speakers) {
                    if (s.volume <= 12) continue // Agora 0–255 (web: a level above 5 of 100)
                    if (s.uid == 0) me = true else pr = true
                }
                on { v, r -> v.volumes(r, me, pr) }
            }
        }
    }
}
