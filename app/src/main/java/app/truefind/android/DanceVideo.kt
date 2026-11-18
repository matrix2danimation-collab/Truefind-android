package app.truefind.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.ClientRoleOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration

/**
 * The live video of a dance room, with Agora's own Android engine (the web
 * page uses Agora's web engine; both meet in the same channel).
 * Same rules as useDanceAV in LiveDance.jsx:
 *  - everyone watches (audience); the host and the dancers on stage send camera + mic
 *  - the pass (token) comes from "liveDanceToken" and is renewed before it runs out
 *  - no pass / no keys → enabled = false and the stage shows photos
 */
class DanceVideo(private val ctx: Context, private val roomId: String, private val listener: Listener) {
    interface Listener {
        fun onVideoEnabled(on: Boolean)
        fun onRemoteVideo(uid: Int, on: Boolean)
        fun onVideoError(msg: String)
        fun askCameraMic(done: (Boolean) -> Unit)
    }

    private val main = Handler(Looper.getMainLooper())
    private var dead = false
    private var joined = false
    var enabled: Boolean? = null
        private set
    private var publisher = false
    private var publishing = false
    private var muted = false
    private var camOff = false
    private var speakerOff = false
    private var paused = false
    private val remoteOn = HashMap<Int, Boolean>()
    private val views = HashMap<Int, TextureView>()
    private var localView: TextureView? = null

    companion object {
        private var engine: RtcEngine? = null
        private var engineAppId = ""
        private var current: DanceVideo? = null
        private val handler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) { current?.post { it.joined = true } }
            override fun onUserOffline(uid: Int, reason: Int) { current?.post { it.setRemote(uid, false) } }
            override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                val on = state == Constants.REMOTE_VIDEO_STATE_STARTING || state == Constants.REMOTE_VIDEO_STATE_DECODING
                val off = state == Constants.REMOTE_VIDEO_STATE_STOPPED || state == Constants.REMOTE_VIDEO_STATE_FAILED
                if (on || off) current?.post { it.setRemote(uid, on) }
            }
            override fun onFirstRemoteVideoFrame(uid: Int, width: Int, height: Int, elapsed: Int) { current?.post { it.setRemote(uid, true) } }
            override fun onTokenPrivilegeWillExpire(token: String?) { current?.post { it.renew(0) } }
            override fun onRequestToken() { current?.post { it.renew(0) } }
            override fun onError(err: Int) { }
        }

        /** the engine is kept for the whole app session (made once) */
        private fun engineFor(ctx: Context, appId: String): RtcEngine? {
            val e = engine
            if (e != null && engineAppId == appId) return e
            if (e != null) { try { RtcEngine.destroy() } catch (x: Throwable) { }; engine = null }
            return try {
                val cfg = RtcEngineConfig().apply {
                    mContext = ctx.applicationContext
                    mAppId = appId
                    mEventHandler = handler
                    mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                }
                RtcEngine.create(cfg).also {
                    it.enableVideo()
                    it.setDefaultAudioRoutetoSpeakerphone(true)
                    // the web dancers send 720p (720p_1): the same here
                    it.setVideoEncoderConfiguration(VideoEncoderConfiguration(
                        VideoEncoderConfiguration.VD_1280x720, VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                        VideoEncoderConfiguration.STANDARD_BITRATE, VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE))
                    engine = it; engineAppId = appId
                }
            } catch (t: Throwable) { null }
        }

        /** the app is closing */
        fun destroyAll() {
            current?.stop()
            try { if (engine != null) RtcEngine.destroy() } catch (t: Throwable) { }
            engine = null; engineAppId = ""
        }
    }

    private fun post(f: (DanceVideo) -> Unit) { main.post { if (!dead && current === this) f(this) } }

    /** start watching (once the entry fee is paid) */
    fun start() {
        current?.let { if (it !== this) it.stop() }
        current = this
        fetchPass(3) { tk, problem ->
            if (dead) return@fetchPass
            if (tk == null) {
                enabled = false; listener.onVideoEnabled(false)
                listener.onVideoError(problem ?: "Live video could not connect. Check your internet and try again.")
                return@fetchPass
            }
            if (!tk.optBoolean("enabled", false)) { enabled = false; listener.onVideoEnabled(false); return@fetchPass }
            val e = engineFor(ctx, tk.optString("app_id"))
            if (e == null) { enabled = false; listener.onVideoEnabled(false); listener.onVideoError("Live video could not load."); return@fetchPass }
            val host = tk.optString("role") == "host"
            val opts = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType = if (host) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
                autoSubscribeAudio = true; autoSubscribeVideo = true
                publishCameraTrack = false; publishMicrophoneTrack = false
                if (!host) audienceLatencyLevel = Constants.AUDIENCE_LATENCY_LEVEL_ULTRA_LOW_LATENCY
            }
            val token = tk.optString("token").takeIf { it.isNotEmpty() && it != "null" }
            val rc = try { e.joinChannel(token, tk.optString("channel"), tk.optInt("uid"), opts) } catch (t: Throwable) { -1 }
            if (rc < 0) {
                enabled = false; listener.onVideoEnabled(false)
                listener.onVideoError("Live video could not connect. Try again. ($rc)")
                return@fetchPass
            }
            e.muteAllRemoteAudioStreams(speakerOff || paused)
            e.muteAllRemoteVideoStreams(paused)
            enabled = true
            listener.onVideoEnabled(true)
            // bind the pictures that were asked for before the engine was ready
            views.forEach { (uid, v) -> bindRemote(uid, v) }
            if (publisher) goOnStage()
        }
    }

    /** leave the channel (closing the room, or another room opens) */
    fun stop() {
        if (dead) return
        dead = true
        val e = engine
        if (current === this) current = null
        try {
            if (publishing) { e?.stopPreview(); publishing = false }
            views.keys.forEach { uid -> e?.setupRemoteVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid)) }
            e?.leaveChannel()
        } catch (t: Throwable) { }
        views.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        views.clear()
        localView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        localView = null
        remoteOn.clear()
    }

    /** a person's picture: a TextureView that shows their video (made once per person) */
    fun viewFor(uid: Int): TextureView {
        views[uid]?.let { return it }
        val v = TextureView(ctx)
        views[uid] = v
        if (enabled == true) bindRemote(uid, v)
        return v
    }

    private fun bindRemote(uid: Int, v: TextureView) {
        try { engine?.setupRemoteVideo(VideoCanvas(v, VideoCanvas.RENDER_MODE_HIDDEN, uid)) } catch (t: Throwable) { }
    }

    fun hasVideo(uid: Int): Boolean = enabled == true && remoteOn[uid] == true

    private fun setRemote(uid: Int, on: Boolean) {
        if (remoteOn[uid] == on) return
        remoteOn[uid] = on
        listener.onRemoteVideo(uid, on)
    }

    // ── me on stage (dancer) ──
    fun localView(): TextureView? = localView
    val sendingVideo: Boolean get() = publishing && !camOff

    /** on = the server has me on the stage (host or dancer) */
    fun setPublisher(on: Boolean) {
        if (on == publisher) return
        publisher = on
        if (enabled != true) return
        if (on) goOnStage() else leaveStage()
    }

    private fun goOnStage() {
        listener.askCameraMic { ok ->
            if (dead || !publisher) return@askCameraMic
            if (!ok) { listener.onVideoError("Allow camera and microphone to dance live."); return@askCameraMic }
            fetchPass(3) { tk, _ ->
                if (dead || !publisher) return@fetchPass
                if (tk == null) { listener.onVideoError("Couldn’t go live. Check your internet and try again."); return@fetchPass }
                if (tk.optString("role") != "host") return@fetchPass // not on stage on the server (yet): keep watching
                val e = engine ?: return@fetchPass
                try {
                    tk.optString("token").takeIf { it.isNotEmpty() && it != "null" }?.let { e.renewToken(it) }
                    e.setClientRole(Constants.CLIENT_ROLE_BROADCASTER, ClientRoleOptions())
                    val lv = localView ?: TextureView(ctx).also { localView = it }
                    e.setupLocalVideo(VideoCanvas(lv, VideoCanvas.RENDER_MODE_HIDDEN, 0).apply { mirrorMode = Constants.VIDEO_MIRROR_MODE_ENABLED })
                    e.enableLocalVideo(!camOff)
                    e.startPreview()
                    e.muteLocalAudioStream(muted)
                    e.updateChannelMediaOptions(ChannelMediaOptions().apply {
                        clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                        publishCameraTrack = true; publishMicrophoneTrack = true
                    })
                    publishing = true
                    listener.onRemoteVideo(0, true)
                } catch (t: Throwable) {
                    listener.onVideoError("Your camera/mic couldn’t go live. Try again.")
                }
            }
        }
    }

    private fun leaveStage() {
        val e = engine ?: return
        try {
            e.updateChannelMediaOptions(ChannelMediaOptions().apply {
                publishCameraTrack = false; publishMicrophoneTrack = false
                clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            })
            e.stopPreview()
            e.setClientRole(Constants.CLIENT_ROLE_AUDIENCE, ClientRoleOptions().apply { audienceLatencyLevel = Constants.AUDIENCE_LATENCY_LEVEL_ULTRA_LOW_LATENCY })
        } catch (t: Throwable) { }
        publishing = false
        listener.onRemoteVideo(0, false)
        renew(0)
    }

    fun setMuted(m: Boolean) { muted = m; if (publishing) try { engine?.muteLocalAudioStream(m) } catch (t: Throwable) { } }
    fun setCamOff(off: Boolean) {
        if (camOff == off) return
        camOff = off
        if (publishing) try { engine?.enableLocalVideo(!off) } catch (t: Throwable) { }
        listener.onRemoteVideo(0, publishing && !off)
    }
    fun setSpeakerOff(off: Boolean) { speakerOff = off; try { if (joinedOrEnabled()) engine?.muteAllRemoteAudioStreams(off || paused) } catch (t: Throwable) { } }

    /** the app went to the background: stop sound and pictures until it comes back */
    fun setPaused(p: Boolean) {
        paused = p
        if (!joinedOrEnabled()) return
        try {
            engine?.muteAllRemoteAudioStreams(speakerOff || p)
            engine?.muteAllRemoteVideoStreams(p)
        } catch (t: Throwable) { }
    }

    private fun joinedOrEnabled() = enabled == true && !dead

    // ── the pass ──
    private fun fetchPass(tries: Int, done: (org.json.JSONObject?, String?) -> Unit) {
        DanceApi.token(roomId) { r ->
            if (dead) return@token
            if (r.ok) { done(r.data, null); return@token }
            val code = r.data.optString("code")
            if (code.startsWith("AGORA_")) { done(null, "Live video is not set up correctly ($code)."); return@token }
            if (tries <= 1) { done(null, null); return@token }
            main.postDelayed({ if (!dead) fetchPass(tries - 1, done) }, 1200L * (4 - tries))
        }
    }

    private fun renew(attempt: Int) {
        if (dead || attempt > 3) return
        DanceApi.token(roomId) { r ->
            if (dead) return@token
            val t = r.data.optString("token")
            if (r.ok && t.isNotEmpty() && t != "null") { try { engine?.renewToken(t) } catch (x: Throwable) { } }
            else main.postDelayed({ renew(attempt + 1) }, 5000)
        }
    }

    @Suppress("unused") private val keep = View.GONE
}
