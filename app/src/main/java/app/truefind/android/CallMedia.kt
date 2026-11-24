package app.truefind.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import app.truefind.android.games.GameVoice
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.CameraCapturerConfiguration
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import org.json.JSONObject

/**
 * Sound (and video) of a 1-on-1 call with Agora's own Android engine. The web call screen used
 * Agora's web engine; both meet in the same channel ("tfc_<call id>", the pass from callToken), so a
 * member on the app and a member on the website hear and see each other.
 *
 * Same rules as the media part of CallScreen.jsx:
 *  - the pass is asked up to 4 times (a slow network, a server function waking up); wrong Agora keys
 *    or a call that is no longer on stop at once, with a short code to report
 *  - voice: the microphone (echo / noise filters of the engine); video: + the front camera, 480p 15 fps,
 *    my own picture mirrored
 *  - who is talking (sound levels), the other phone leaving (→ hang up), a new pass 30 s before the old
 *    one runs out, "reconnecting" on a weak network, a join that never answers stops after 30 s
 *
 * Agora has one engine per app. Live Dance and the games' voice keep their own handle on it: they let
 * go of it first, and it is left clean (nothing joined, no video) when the call is over, so they make
 * it again the way they need it.
 */
class CallMedia(private val ctx: Context, private val callId: String, video0: Boolean, private val l: Listener) {
    /** a voice call can become a video call while it runs (toVideo) */
    private var video = video0
    interface Listener {
        /** preparing → ready | failed */
        fun onMediaState(state: String)
        fun onMediaError(msg: String, detail: String)
        fun onPeerJoined()
        fun onPeerLeft()
        fun onPeerVideo(on: Boolean)
        /** "" | CONNECTING | CONNECTED | RECONNECTING | DISCONNECTED | FAILED */
        fun onNet(state: String)
        /** a sound level report: me / the other person loud right now (null = this report is not about them) */
        fun onLevels(me: Boolean?, peer: Boolean?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var dead = false
    private var joined = false
    var localView: TextureView? = null; private set
    var remoteView: TextureView? = null; private set
    private var remoteUid = 0
    /** the other phone in the channel (known on a voice call too, for a switch to video) */
    private var peerUid = 0
    private var muted = false
    private var camOff = false
    private var loud = false
    /** the back camera instead of the front one (Flip, owner 23 Nov) */
    private var backCam = false
    val usingBackCamera: Boolean get() = backCam

    companion object {
        /**
         * Test builds only (the GitHub test phone): makes Agora's engine and sets it up the way a video call does
         * (sound, video, encoder, my picture in a view), without joining or calling anyone, then drops it again.
         * Answers "ok …" or what failed, so a missing part of the engine shows up in the test run, not in a call.
         */
        fun selfCheck(ctx: Context): String {
            if (engine != null) return "skipped (a call is on)"
            return try {
                val e = RtcEngine.create(RtcEngineConfig().apply {
                    mContext = ctx.applicationContext; mAppId = "0123456789abcdef0123456789abcdef"
                    mEventHandler = object : IRtcEngineEventHandler() {}; mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                })
                val codes = listOf(
                    e.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION),
                    e.setClientRole(Constants.CLIENT_ROLE_BROADCASTER),
                    e.enableAudio(),
                    e.setDefaultAudioRoutetoSpeakerphone(true),
                    e.enableAudioVolumeIndication(200, 3, true),
                    e.enableVideo(),
                    e.setVideoEncoderConfiguration(VideoEncoderConfiguration(VideoEncoderConfiguration.VD_640x480, VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                        VideoEncoderConfiguration.STANDARD_BITRATE, VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE)),
                    e.setupLocalVideo(VideoCanvas(TextureView(ctx), VideoCanvas.RENDER_MODE_HIDDEN, 0).apply { mirrorMode = Constants.VIDEO_MIRROR_MODE_ENABLED }),
                    e.enableLocalVideo(false),
                    e.muteLocalVideoStream(true),
                    e.adjustPlaybackSignalVolume(250),
                    e.disableVideo(),
                )
                RtcEngine.destroy()
                if (codes.all { it >= 0 }) "ok (Agora ${RtcEngine.getSdkVersion()})" else "setup codes $codes"
            } catch (t: Throwable) {
                try { RtcEngine.destroy() } catch (x: Throwable) { }
                "fail ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        private var engine: RtcEngine? = null
        private var current: CallMedia? = null
        /** Agora's engine was last left on the back camera (Flip): the next call starts on the front one again */
        private var engineOnBack = false
        /** Agora's level above which someone counts as talking (the web: a level above 0.06 of 1) */
        private const val LOUD = 15

        private fun post(f: (CallMedia) -> Unit) { val c = current ?: return; c.main.post { if (current === c && !c.dead) f(c) } }

        private val handler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) { post { it.joinedOk() } }
            override fun onUserJoined(uid: Int, elapsed: Int) { post { it.peerIn(uid) } }
            override fun onUserOffline(uid: Int, reason: Int) { post { it.l.onPeerLeft() } }
            override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                val on = state == Constants.REMOTE_VIDEO_STATE_STARTING || state == Constants.REMOTE_VIDEO_STATE_DECODING
                val off = state == Constants.REMOTE_VIDEO_STATE_STOPPED || state == Constants.REMOTE_VIDEO_STATE_FAILED
                if (on || off) post { it.remoteVideo(uid, on) }
            }
            override fun onFirstRemoteVideoFrame(uid: Int, width: Int, height: Int, elapsed: Int) { post { it.remoteVideo(uid, true) } }
            @Deprecated("Deprecated in Java")
            override fun onUserMuteVideo(uid: Int, muted: Boolean) { post { it.remoteVideo(uid, !muted) } }
            override fun onConnectionStateChanged(state: Int, reason: Int) {
                val s = when (state) { 1 -> "DISCONNECTED"; 2 -> "CONNECTING"; 3 -> "CONNECTED"; 4 -> "RECONNECTING"; 5 -> "FAILED"; else -> "" }
                post { it.l.onNet(s) }
            }
            override fun onTokenPrivilegeWillExpire(token: String?) { post { it.renew(0) } }
            override fun onRequestToken() { post { it.renew(0) } }
            override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
                // Agora reports me (uid 0) and the others in two separate reports
                val list = speakers ?: return
                var mine = false; var me = false; var peer = false
                for (s in list) { if (s.uid == 0) { mine = true; if (s.volume > LOUD) me = true } else if (s.volume > LOUD) peer = true }
                post { if (mine) it.l.onLevels(me, null) else it.l.onLevels(null, peer) }
            }
            override fun onLocalAudioStateChanged(state: Int, reason: Int) {
                // 3 = failed; reason 2 = no permission, 3 = busy, 4 = recording failed
                if (state == 3) post { it.deviceFailed(reason == 2) }
            }
            override fun onLocalVideoStateChanged(source: Constants.VideoSourceType?, state: Int, reason: Int) {
                if (state == 3) post { it.deviceFailed(reason == 2) }
            }
        }
    }

    /** Starts: the pass, then the engine, then the channel. */
    fun start() {
        current?.let { if (it !== this) it.stop() }
        current = this
        l.onMediaState("preparing")
        pass(0) { tk, failed ->
            if (dead) return@pass
            if (tk == null) { fail("Could not connect the call service. Please end the call and try again.", failed ?: "PASS"); return@pass }
            if (!tk.optBoolean("enabled", false)) {
                // the web fell back to its old direct link here; the app always goes through Agora
                fail("Could not connect the call service. Please end the call and try again.", "PASS_OFF"); return@pass
            }
            val appId = tk.optString("app_id")
            if (appId.isEmpty()) { fail("Could not connect the call service.", "PASS_BAD"); return@pass }
            join(appId, tk.optString("channel"), tk.optString("token").takeIf { it.isNotEmpty() && it != "null" }, tk.optInt("uid"))
        }
    }

    private fun fail(msg: String, detail: String) {
        if (dead) return
        leaveEngine()
        l.onMediaState("failed")
        l.onMediaError(msg, detail)
    }

    /** callToken, tried up to 4 times; Agora key problems and a closed call stop at once (same codes as the web) */
    private fun pass(i: Int, done: (JSONObject?, String?) -> Unit) {
        Api.invoke("callToken", JSONObject().put("call_id", callId)) { r ->
            if (dead) return@invoke
            if (r.ok) {
                val d = r.data
                if (d.has("enabled") && (!d.optBoolean("enabled", true) || d.optString("app_id").isNotEmpty())) { done(d, null); return@invoke }
            } else {
                val code = r.data.optString("code")
                if (code.startsWith("AGORA_")) { done(null, code); return@invoke }
                if (r.status in listOf(401, 403, 404, 410)) { done(null, "PASS_${r.status}"); return@invoke }
            }
            // a busy server / no reply: asked again for up to ~30 s (24 Nov: 4 tries in ~7 s ended calls with "Could not
            // connect the call service" during a busy moment)
            val again = CallRules.retryStart(r.status) || r.ok
            if (i >= (if (again) 9 else 3)) { done(null, "PASS_NETWORK"); return@invoke }
            val said = if (r.data.has("retry_after")) r.data.optDouble("retry_after") else null
            main.postDelayed({ if (!dead) pass(i + 1, done) }, if (said != null && !said.isNaN()) CallRules.startWaitMs(i, said).coerceAtMost(6000L) else minOf(1200L * (i + 1), 4000L))
        }
    }

    private fun join(appId: String, channel: String, token: String?, uid: Int) {
        // Live Dance and the games' voice let go of Agora's one engine (they make it again when needed)
        try { DanceVideo.destroyAll() } catch (t: Throwable) { }
        try { GameVoice.releaseEngine() } catch (t: Throwable) { }
        val e = try {
            RtcEngine.create(RtcEngineConfig().apply {
                mContext = ctx.applicationContext; mAppId = appId; mEventHandler = handler
                mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            })
        } catch (t: Throwable) { null }
        if (e == null) { fail("The call could not load. Check your internet, then end the call and try again.", "SDK_LOAD"); return }
        engine = e
        try {
            e.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
            e.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            e.enableAudio()
            // the same loudspeaker as the website (the web's Speaker button only makes the other voice louder)
            e.setDefaultAudioRoutetoSpeakerphone(true)
            e.enableAudioVolumeIndication(200, 3, true)
            e.muteLocalAudioStream(muted)
            e.muteAllRemoteAudioStreams(false)
            e.adjustPlaybackSignalVolume(if (loud) 250 else 100)
            if (video) {
                videoOn(e)
            } else {
                e.disableVideo()
            }
            val opts = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                publishMicrophoneTrack = true
                publishCameraTrack = video
                autoSubscribeAudio = true
                autoSubscribeVideo = video
            }
            val rc = e.joinChannel(token, channel, uid, opts)
            if (rc < 0) { fail("Could not connect the call. Check your internet and try again.", "JOIN_$rc"); return }
        } catch (t: Throwable) {
            fail("Could not connect the call. Check your internet and try again.", t.javaClass.simpleName.take(40)); return
        }
        // a join that never answers (blocked network) must not leave the call on "Connecting…" for ever
        main.postDelayed({ if (!dead && !joined) fail("Could not connect the call. Check your internet and try again.", "JOIN_TIMEOUT: no answer from the call service") }, 30000)
    }

    /** sound + camera: the front camera, 480p 15 fps, my own picture mirrored */
    private fun videoOn(e: RtcEngine) {
        e.enableVideo()
        // the web's "480p_1": 640 × 480, 15 fps
        e.setVideoEncoderConfiguration(VideoEncoderConfiguration(
            VideoEncoderConfiguration.VD_640x480, VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
            VideoEncoderConfiguration.STANDARD_BITRATE, VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE))
        e.muteAllRemoteVideoStreams(false)
        // which camera: front as always, or the back one chosen with Flip before the engine was up. Agora's one engine
        // outlives a call: when the last call ended on the back camera, this call is set back to the front one.
        if (backCam || engineOnBack) try {
            val rc = e.setCameraCapturerConfiguration(CameraCapturerConfiguration(
                if (backCam) CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR else CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_FRONT))
            if (rc >= 0) engineOnBack = backCam
        } catch (t: Throwable) { }
        e.enableLocalVideo(!camOff)
        e.muteLocalVideoStream(camOff)
        val lv = localView ?: TextureView(ctx).also { localView = it }
        // my own picture mirrored from the front camera only (the back camera shows the world the right way round)
        e.setupLocalVideo(VideoCanvas(lv, VideoCanvas.RENDER_MODE_HIDDEN, 0).apply { mirrorMode = mirror() })
        e.startPreview()
    }

    private fun mirror() = if (backCam) Constants.VIDEO_MIRROR_MODE_DISABLED else Constants.VIDEO_MIRROR_MODE_ENABLED

    /**
     * Flip: front ↔ back camera while the video call goes on (owner, 23 Nov: "only the front camera worked"). The other
     * person sees the new camera at once. Before the engine is up, the choice waits for it. False: it didn't switch.
     */
    fun switchCamera(): Boolean {
        if (dead) return false
        val e = engine
        if (e == null || !video) { backCam = !backCam; return true }
        return try {
            if (e.switchCamera() < 0) false else {
                backCam = !backCam
                engineOnBack = backCam
                e.setLocalRenderMode(VideoCanvas.RENDER_MODE_HIDDEN, mirror())
                true
            }
        } catch (t: Throwable) { false }
    }

    /**
     * Voice → video while the call goes on (owner, 20 Nov): the camera starts and is sent, the other person's
     * picture is received. [off]: my camera stays off (no camera permission) — I still see theirs.
     */
    fun toVideo(off: Boolean) {
        if (dead || video) return
        video = true
        camOff = off
        val e = engine ?: return // not joined yet: join() sets video up itself
        try {
            videoOn(e)
            e.updateChannelMediaOptions(ChannelMediaOptions().apply {
                publishCameraTrack = true
                autoSubscribeVideo = true
            })
            if (peerUid != 0) bindRemote(peerUid)
        } catch (t: Throwable) { }
    }

    private fun joinedOk() {
        if (joined) return
        joined = true
        l.onMediaState("ready")
    }

    private fun peerIn(uid: Int) {
        if (uid != 0) peerUid = uid
        if (video && remoteUid == 0) bindRemote(uid)
        l.onPeerJoined()
    }

    private fun remoteVideo(uid: Int, on: Boolean) {
        if (!video) return
        if (remoteUid == 0 || remoteUid != uid) bindRemote(uid)
        l.onPeerVideo(on)
    }

    private fun bindRemote(uid: Int) {
        if (uid == 0) return
        remoteUid = uid
        val v = remoteView ?: TextureView(ctx).also { remoteView = it }
        try { engine?.setupRemoteVideo(VideoCanvas(v, VideoCanvas.RENDER_MODE_HIDDEN, uid)) } catch (t: Throwable) { }
    }

    private fun deviceFailed(denied: Boolean) {
        fail(if (denied) "Microphone/camera permission denied. Allow access for True Find, then call again."
            else "The microphone or camera is busy or missing (another app may be using it).", if (denied) "PERMISSION_DENIED" else "DEVICE_NOT_READABLE")
    }

    /** a new pass before the old one runs out (a few tries: one network blip must not cut the call) */
    private fun renew(i: Int) {
        if (dead || i >= 12) return // (a minute of tries: a busy moment must not cut a running call)
        Api.invoke("callToken", JSONObject().put("call_id", callId)) { r ->
            if (dead) return@invoke
            val t = r.data.optString("token")
            if (r.ok && t.isNotEmpty() && t != "null") { try { engine?.renewToken(t) } catch (x: Throwable) { } }
            else main.postDelayed({ renew(i + 1) }, 5000)
        }
    }

    fun setMuted(m: Boolean) { muted = m; try { engine?.muteLocalAudioStream(m) } catch (t: Throwable) { } }
    fun setCamOff(off: Boolean) {
        camOff = off
        if (!video) return
        try { engine?.enableLocalVideo(!off); engine?.muteLocalVideoStream(off) } catch (t: Throwable) { }
    }
    /** "Speaker": the other person's sound louder (as the web: 250 % instead of 100 %) */
    fun setLoud(on: Boolean) { loud = on; try { engine?.adjustPlaybackSignalVolume(if (on) 250 else 100) } catch (t: Throwable) { } }

    /** Leaves the call (the screen closes, the call is over). */
    fun stop() {
        if (dead) return
        dead = true
        main.removeCallbacksAndMessages(null)
        leaveEngine()
        if (current === this) current = null
    }

    private fun leaveEngine() {
        val e = engine ?: return
        try {
            e.leaveChannel()
            if (video) {
                e.stopPreview()
                e.setupLocalVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, 0))
                if (remoteUid != 0) e.setupRemoteVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, remoteUid))
                e.disableVideo()
            }
            e.adjustPlaybackSignalVolume(100)
            e.muteLocalAudioStream(false)
            e.removeHandler(handler)
        } catch (t: Throwable) { }
        engine = null
    }
}
