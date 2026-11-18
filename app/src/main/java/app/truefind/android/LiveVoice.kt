package app.truefind.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.ClientRoleOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import org.json.JSONObject

/**
 * The voice of a Live Room with Agora's own Android engine. The web page uses Agora's web engine;
 * both meet in the same channel ("tf_<room id>"), with the same uid numbers and the same passes
 * (liveRoomToken), so people on the app and on the website hear each other.
 * Same rules as useLiveAV in LiveRoom.jsx (audio rooms):
 *  - everyone listens (audience); the host and the paid Co-Hosts talk (mic only, never the camera)
 *  - the pass follows the CURRENT seat and is renewed before it runs out
 *  - no pass / no keys → enabled = false ("Live voice starts soon")
 *
 * One Agora engine is shared by the whole app (Live Dance, the games' voice and Live Rooms). Making it
 * again (RtcEngine.create) would cut the other parts off from their events, so this class borrows the
 * engine that is already there, adds its own listener only while a room is open, never touches the
 * video switches, and puts back the sound switches it changed when the room closes.
 */
class LiveVoice(private val ctx: Context, private val roomId: String, private val listener: Listener) {
    interface Listener {
        /** true = connected · false = the voice service is not available (no keys / no connection) */
        fun onVoiceEnabled(on: Boolean)
        /** who is talking now (Agora uids; my own uid while I talk) */
        fun onSpeaking(uids: Set<Int>)
        fun onVoiceError(msg: String)
        fun askMic(done: (Boolean) -> Unit)
    }

    private val main = Handler(Looper.getMainLooper())
    private var dead = false
    var enabled: Boolean? = null
        private set
    private var engine: RtcEngine? = null
    private var publisher = false
    private var publishing = false
    private var muted = true
    private var paused = false
    private var myUid = 0
    private var publishTries = 0
    private var localTalking = false
    private var remoteTalking: Set<Int> = emptySet()
    private var speaking: Set<Int> = emptySet()
    private val quiet = Runnable { remoteTalking = emptySet(); localTalking = false; pushSpeaking() }

    companion object {
        private var current: LiveVoice? = null
        private val mainH = Handler(Looper.getMainLooper())
        private fun post(f: (LiveVoice) -> Unit) { mainH.post { val v = current; if (v != null && !v.dead) f(v) } }

        private val handler = object : IRtcEngineEventHandler() {
            override fun onUserOffline(uid: Int, reason: Int) { post { it.dropSpeaker(uid) } }
            override fun onTokenPrivilegeWillExpire(token: String?) { post { it.renew(0) } }
            override fun onRequestToken() { post { it.renew(0) } }
            override fun onLocalAudioStateChanged(state: Int, reason: Int) {
                // 3 = LOCAL_AUDIO_STREAM_STATE_FAILED; reason 3 = device busy, 4 = recording failed
                if (state == 3) post { it.micFailed(reason == 3 || reason == 4) }
            }
            override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
                // Agora sends the local volume (uid 0) and the remote ones in separate calls
                var hasLocal = false; var local = false; var hasRemote = speakers == null || speakers.isEmpty()
                val remote = HashSet<Int>()
                if (speakers != null) for (s in speakers) {
                    if (s.uid == 0) { hasLocal = true; local = s.volume > 12 }
                    else { hasRemote = true; if (s.volume > 12) remote.add(s.uid) }
                }
                post { it.levels(hasLocal, local, hasRemote, remote) }
            }
        }

        /** Agora keeps ONE engine for the app: use it as it is (never re-made, never reset), or make the first one */
        private fun engineFor(ctx: Context, appId: String): RtcEngine? {
            existing()?.let { e -> try { e.addHandler(handler) } catch (t: Throwable) { }; return e }
            return try {
                val cfg = RtcEngineConfig().apply {
                    mContext = ctx.applicationContext
                    mAppId = appId
                    mEventHandler = handler
                    mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                }
                RtcEngine.create(cfg)
            } catch (t: Throwable) { null }
        }

        /** the engine Live Dance or a game already made (read only: RtcEngine.create would drop their listeners) */
        private fun existing(): RtcEngine? = try {
            val f = RtcEngine::class.java.getDeclaredField("mInstance")
            f.isAccessible = true
            f.get(null) as? RtcEngine
        } catch (t: Throwable) { null }
    }

    /** start listening (after joining the room) */
    fun start() {
        current?.let { if (it !== this) it.stop() }
        current = this
        fetchPass(3) { tk, problem ->
            if (dead) return@fetchPass
            if (tk == null) {
                setEnabled(false)
                listener.onVoiceError(problem ?: "Live voice could not connect. Check your internet and try again.")
                return@fetchPass
            }
            if (!tk.optBoolean("enabled", false)) { setEnabled(false); return@fetchPass }
            val e = engineFor(ctx, tk.optString("app_id"))
            if (e == null) { setEnabled(false); listener.onVoiceError("Live voice could not load."); return@fetchPass }
            engine = e
            try { e.addHandler(handler) } catch (t: Throwable) { }
            myUid = tk.optInt("uid")
            try {
                e.enableAudio()
                e.setDefaultAudioRoutetoSpeakerphone(true)
                e.enableAudioVolumeIndication(250, 3, true)
                e.muteLocalAudioStream(true) // nothing is sent until I have a seat
                e.muteAllRemoteAudioStreams(paused)
            } catch (t: Throwable) { }
            val opts = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
                audienceLatencyLevel = Constants.AUDIENCE_LATENCY_LEVEL_ULTRA_LOW_LATENCY
                autoSubscribeAudio = true; autoSubscribeVideo = false
                publishCameraTrack = false; publishMicrophoneTrack = false
            }
            val token = tk.optString("token").takeIf { it.isNotEmpty() && it != "null" }
            val rc = try { e.joinChannel(token, tk.optString("channel"), myUid, opts) } catch (t: Throwable) { -1 }
            if (rc < 0) {
                setEnabled(false)
                listener.onVoiceError("Live voice could not connect. Try again. ($rc)")
                return@fetchPass
            }
            setEnabled(true)
            if (publisher) goOnStage()
        }
    }

    /** leave the channel (the room closes, or another room opens) and put the sound switches back */
    fun stop() {
        if (dead) return
        dead = true
        main.removeCallbacksAndMessages(null)
        val e = engine
        if (current === this) current = null
        try {
            if (publishing) e?.updateChannelMediaOptions(ChannelMediaOptions().apply { publishMicrophoneTrack = false })
            e?.leaveChannel()
            e?.muteLocalAudioStream(false)
            e?.muteAllRemoteAudioStreams(false)
            e?.removeHandler(handler)
        } catch (t: Throwable) { }
        publishing = false
        engine = null
    }

    private fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        listener.onVoiceEnabled(on)
    }

    /** on = the server has me on a seat (host or Co-Host): send my voice */
    fun setPublisher(on: Boolean) {
        if (on == publisher) return
        publisher = on
        publishTries = 0
        if (enabled != true) return
        if (on) goOnStage() else leaveStage()
    }

    /** on a seat but not sending (the microphone was not allowed, or it failed): try once more */
    fun retryStage() {
        if (dead || enabled != true || !publisher || publishing) return
        publishTries = 0
        goOnStage()
    }

    fun setMuted(m: Boolean) {
        muted = m
        if (publishing) try { engine?.muteLocalAudioStream(m || paused) } catch (t: Throwable) { }
        pushSpeaking()
    }

    /** the app went to the background / a pop-up covers the room: no sound in or out until it comes back */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (enabled != true || dead) return
        try {
            engine?.muteAllRemoteAudioStreams(p)
            if (publishing) engine?.muteLocalAudioStream(muted || p)
        } catch (t: Throwable) { }
        if (p) { remoteTalking = emptySet(); localTalking = false; pushSpeaking() }
    }

    private fun goOnStage() {
        listener.askMic { ok ->
            if (dead || !publisher) return@askMic
            if (!ok) { listener.onVoiceError("Allow the microphone to talk in the live."); return@askMic }
            fetchPass(3) { tk, _ ->
                if (dead || !publisher || publishing) return@fetchPass
                if (tk == null) { listener.onVoiceError("Couldn’t go live. Check your internet and try again."); return@fetchPass }
                if (tk.optString("role") != "host") {
                    // the server doesn't have me on the seat yet: keep listening and ask again a little later
                    if (publishTries++ < 3) main.postDelayed({ if (!dead && publisher && !publishing) goOnStage() }, 2000)
                    return@fetchPass
                }
                val e = engine ?: return@fetchPass
                try {
                    tk.optString("token").takeIf { it.isNotEmpty() && it != "null" }?.let { e.renewToken(it) }
                    e.setClientRole(Constants.CLIENT_ROLE_BROADCASTER, ClientRoleOptions())
                    e.muteLocalAudioStream(muted || paused)
                    e.updateChannelMediaOptions(ChannelMediaOptions().apply {
                        clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                        publishMicrophoneTrack = true; publishCameraTrack = false
                    })
                    publishing = true
                    publishTries = 0
                } catch (t: Throwable) {
                    listener.onVoiceError("Your mic couldn’t go live. Try again.")
                }
            }
        }
    }

    private fun leaveStage() {
        val e = engine ?: return
        try {
            e.updateChannelMediaOptions(ChannelMediaOptions().apply {
                publishMicrophoneTrack = false; publishCameraTrack = false
                clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            })
            e.setClientRole(Constants.CLIENT_ROLE_AUDIENCE, ClientRoleOptions().apply { audienceLatencyLevel = Constants.AUDIENCE_LATENCY_LEVEL_ULTRA_LOW_LATENCY })
            e.muteLocalAudioStream(true)
        } catch (t: Throwable) { }
        publishing = false
        localTalking = false
        pushSpeaking()
        renew(0) // a pass without the right to talk
    }

    // ── from the engine (already on the main thread) ──
    private fun levels(hasLocal: Boolean, local: Boolean, hasRemote: Boolean, remote: Set<Int>) {
        if (paused) return
        if (hasLocal) localTalking = local
        if (hasRemote) remoteTalking = remote
        main.removeCallbacks(quiet); main.postDelayed(quiet, 1500)
        pushSpeaking()
    }
    private fun dropSpeaker(uid: Int) { if (remoteTalking.contains(uid)) { remoteTalking = remoteTalking - uid; pushSpeaking() } }
    private fun pushSpeaking() {
        val now = if (localTalking && publishing && !muted && !paused && myUid != 0) remoteTalking + myUid else remoteTalking
        if (now == speaking) return
        speaking = now
        listener.onSpeaking(now)
    }
    private fun micFailed(busy: Boolean) {
        if (!publishing) return
        listener.onVoiceError(if (busy) "The microphone is busy (another app may be using it)." else "Allow the microphone to talk in the live.")
    }

    // ── the pass ──
    private fun fetchPass(tries: Int, done: (JSONObject?, String?) -> Unit) {
        LiveApi.token(roomId) { r ->
            if (dead) return@token
            if (r.ok) { done(r.data, null); return@token }
            val code = r.data.optString("code")
            if (code.startsWith("AGORA_")) { done(null, "Live voice is not set up correctly ($code)."); return@token }
            if (tries <= 1) { done(null, null); return@token }
            main.postDelayed({ if (!dead) fetchPass(tries - 1, done) }, 1200L * (4 - tries))
        }
    }

    private fun renew(attempt: Int) {
        if (dead || attempt > 3) return
        LiveApi.token(roomId) { r ->
            if (dead) return@token
            val t = r.data.optString("token")
            if (r.ok) { if (t.isNotEmpty() && t != "null") try { engine?.renewToken(t) } catch (x: Throwable) { } }
            else main.postDelayed({ renew(attempt + 1) }, 5000)
        }
    }
}
