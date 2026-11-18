package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * The native call screen (the web's /call/:id page, components/DateCallScreen.jsx and CallFeedback.jsx):
 *  - before pick-up and for calls that never connected: the white + purple stage (Calling / Ringing /
 *    Connecting… / Call declined / No answer) with the red Cancel / End / Close button
 *  - the call itself, voice or video: two round photos with talk bars (voice) or the other person's camera
 *    with mine in a small tile you can drag (video), "Date This" with the pink heart that flies onto her
 *    photo, duration, coin balance with "+", Mute / Camera / End / Speaker, the Report button, the
 *    low-balance / reconnecting / error notes
 *  - after the call: the soft "Call ended" card (coins charged, "Add coins" when they ran out) and the stars sheet
 *  - "Go to Chat": her Secret Dating card with a small chat over the call (the call keeps going)
 */
@SuppressLint("ViewConstructor")
class CallView(
    ctx: Context,
    private val mgr: CallManager,
    callId: String,
    mode: CallSession.Mode,
    hint: JSONObject?,
    search: String,
    meId: String,
) : FrameLayout(ctx), CallSession.Host {

    val session = CallSession(ctx, callId, mode, hint, search, meId, this)
    private val main = Handler(Looper.getMainLooper())
    val layer = TfLayer(this)
    private val stage = StagePage(ctx)
    private val conn = ConnPage(ctx)
    private var errorPage: View? = null
    private var shownConnected = false
    private var closing = false

    // web state of DateCallScreen
    private var hideUi = false
    private var landed = false
    private var flying = false
    private var likeErr = ""
    private var peerPage: PeerPage? = null
    private var everVideo = false
    private var seenTheirs = false
    private var reportClose: (() -> Unit)? = null
    private var addCoinsClose: (() -> Unit)? = null
    private var ratingOpen = false

    init {
        isClickable = true // nothing under the call can be tapped
        clipChildren = false
        setBackgroundColor(Color.WHITE)
        addView(stage, LayoutParams(-1, -1))
        addView(conn, LayoutParams(-1, -1))
        conn.visibility = GONE
        stage.visibility = GONE
    }

    fun start() { session.start(); changed() }

    // ═════════════════ CallSession.Host ═════════════════
    override fun changed() { if (!closing) render() }
    override fun leave(to: String?) { mgr.closeCall(this, to) }
    override fun askPerms(video: Boolean, done: (Boolean) -> Unit) = mgr.askPerms(video, done)
    override fun finished(id: String) = mgr.markFinished(id)

    /** the screen goes away (the manager removes it) */
    fun dispose() {
        closing = true
        main.removeCallbacksAndMessages(null)
        layer.closeAll()
        peerPage?.dispose()
        conn.practiceCam?.stop()
        session.dispose()
    }

    // ═════════════════ drawing (a re-render after every change) ═════════════════
    private fun render() {
        val s = session
        if (s.loadError.isNotEmpty() && s.call == null) { showError(s.loadError); return }
        if (s.showConnected) {
            if (!shownConnected) {
                shownConnected = true
                stage.visibility = GONE
                conn.visibility = VISIBLE
                conn.enter()
            }
            conn.bind()
        } else {
            if (shownConnected) { shownConnected = false; conn.visibility = GONE }
            if (stage.visibility != VISIBLE) { stage.visibility = VISIBLE; stage.enter() }
            stage.bind()
        }
        // the stars sheet (once, for a call that was picked up) — drawn by the connected screen only, as the web
        if (s.rateAsk && shownConnected && !ratingOpen) {
            ratingOpen = true
            CallSheets.rating(this, s, s.otherName) { ratingOpen = false; s.afterRating() }
        }
        // the call is over: the in-call report sheet closes (as setReportOpen(false))
        if (s.settled != null || s.ended) { reportClose?.invoke(); reportClose = null }
    }

    private fun showError(msg: String) {
        if (errorPage != null) return
        stage.visibility = GONE; conn.visibility = GONE
        val ctx = context
        val page = FrameLayout(ctx)
        page.addView(GradientView(ctx, intArrayOf(Ui.c("#1A0B33"), Ui.c("#0E0618")), null), LayoutParams(-1, -1))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dpi(32f), 0, Ui.dpi(32f), 0) }
        col.addView(CallKit.t(ctx, msg, 18f, Color.WHITE, 600).apply { gravity = Gravity.CENTER })
        val btn = CallKit.t(ctx, "Go back", 15f, Color.WHITE, 600).apply {
            gravity = Gravity.CENTER
            background = Tf.box(Color.argb(26, 255, 255, 255), 16f, 1f, Color.argb(38, 255, 255, 255))
            setPadding(Ui.dpi(24f), Ui.dpi(12f), Ui.dpi(24f), Ui.dpi(12f))
            setOnClickListener { leave(null) }
        }
        Ui.pressable(btn)
        col.addView(btn, Tf.ll(t = 16f))
        page.addView(col, LayoutParams(-1, -1))
        errorPage = page
        addView(page, LayoutParams(-1, -1))
    }

    // ═════════════════ the phone's Back ═════════════════
    /** Back: a sheet closes first, then Go to Chat goes back to the call; on the call itself it hangs up (as leaving the web page did). */
    fun onBack(): Boolean {
        if (layer.back()) return true
        peerPage?.let { if (it.back()) return true; if (!session.ended) { closePeer(); return true } }
        val s = session
        if (errorPage != null) { leave(null); return true }
        val st = s.screenState
        if (s.practice) { if (st == "ended") leave(null) else s.hangUp(); return true }
        if (st == "ended" || s.settled != null) { leave(null); return true }
        // ringing / connecting / talking: leaving the call screen ends the call (the web's endOnLeave)
        s.hangUp()
        return true
    }

    // ═════════════════ toasts and the heart ═════════════════
    private var toastView: View? = null
    private val hideToast = Runnable { toastView?.let { v -> v.animate().alpha(0f).setDuration(180).withEndAction { (v.parent as? ViewGroup)?.removeView(v); if (toastView === v) toastView = null }.start() } }
    fun toast(text: String, ms: Long) {
        main.removeCallbacks(hideToast)
        toastView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val ctx = context
        val v = Tf.row(ctx).apply {
            background = Tf.box(CallKit.INK, 999f)
            setPadding(Ui.dpi(14f), Ui.dpi(8f), Ui.dpi(14f), Ui.dpi(8f))
            elevation = Ui.dp(6f)
            outlineSpotShadowColorCompat(Color.argb(64, 0, 0, 0))
            addView(ImageView(ctx).apply { setImageDrawable(CallKit.heart(16f)) }, Tf.ll(Ui.dpi(16f), Ui.dpi(16f), r = 8f))
            addView(CallKit.t(ctx, text, 13.5f, Color.WHITE).apply { maxLines = 1 })
        }
        val lp = LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(74f) }
        conn.addView(v, lp)
        v.alpha = 0f; v.translationY = -Ui.dp(14f)
        v.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(CallKit.EASE_OUT).start()
        toastView = v
        main.postDelayed(hideToast, ms)
    }

    /** Date This: the heart bursts, flies onto her photo and stays there until the call ends */
    private fun like(btn: View, heartPart: View) {
        val s = session
        if (s.iLiked || landed || flying) return
        HeartBurst.play(heartPart, this)
        likeErr = ""
        val a = IntArray(2); heartPart.getLocationInWindow(a)
        val target = if (s.video) conn.vPeerFace else conn.peerFace
        val b = IntArray(2); target.getLocationInWindow(b)
        val me = IntArray(2); getLocationInWindow(me)
        val x = a[0] - me[0] + heartPart.width / 2f; val y = a[1] - me[1] + heartPart.height / 2f - Ui.dp(10f)
        val tx = b[0] - me[0] + target.width - Ui.dp(13f); val ty = b[1] - me[1] + Ui.dp(15f)
        fly(x, y, tx - x, ty - y)
        flying = true
        conn.bind()
        var err: String? = null
        var answered = false
        s.like { e -> answered = true; err = e; if (e != null) { landed = false; likeErr = e; conn.bind() } }
        main.postDelayed({
            flying = false
            if (!(answered && err != null)) landed = true
            toast("Date request sent to ${CallKit.firstName(s.otherName).ifEmpty { "them" }}", 2600)
            conn.bind()
        }, 1000)
        @Suppress("UNUSED_EXPRESSION") btn
    }

    private fun fly(x: Float, y: Float, dx: Float, dy: Float) {
        val size = Ui.dpi(40f)
        val v = ImageView(context).apply { setImageDrawable(CallKit.heart(40f)); elevation = Ui.dp(60f) }
        addView(v, LayoutParams(size, size))
        val ease = android.view.animation.PathInterpolator(.45f, .05f, .35f, 1f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            addUpdateListener {
                val t = it.animatedValue as Float
                val (ox, oy, sc) = if (t < .25f) { val e = ease.getInterpolation(t / .25f); Triple(-30f * e, -70f * e, 1f + .35f * e) }
                else { val e = ease.getInterpolation((t - .25f) / .75f); Triple(-30f + (dx / Ui.density + 30f) * e, -70f + (dy / Ui.density + 70f) * e, 1.35f - .85f * e) }
                v.x = x + Ui.dp(ox) - size / 2f; v.y = y + Ui.dp(oy) - size / 2f
                v.scaleX = sc; v.scaleY = sc
            }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { removeView(v) } })
            start()
        }
    }

    // ═════════════════ sheets and the Go to Chat page ═════════════════
    private fun openAddCoins() {
        val s = session
        if (s.practice) { leave("/wallet"); return }
        addCoinsClose = CallSheets.addCoins(this, onKeep = { addCoinsClose = null }) { addCoinsClose = null; s.endAndGo("/wallet") }
    }

    private fun openReport() {
        val s = session
        reportClose = CallSheets.report(this, s, s.otherName, onClose = { reportClose = null; conn.bind() }) {
            reportClose = null
            s.ratingAsked = true
            s.hangUp()
        }
        conn.bind()
    }

    private fun openPeer() {
        if (peerPage != null) return
        val p = PeerPage(context, session) { closePeer() }
        peerPage = p
        addView(p, LayoutParams(-1, -1))
        p.elevation = Ui.dp(40f)
        p.alpha = 0f; p.animate().alpha(1f).setDuration(160).start()
        conn.bind()
    }

    private fun closePeer() {
        val p = peerPage ?: return
        peerPage = null
        p.dispose()
        p.animate().alpha(0f).setDuration(160).withEndAction { removeView(p) }.start()
        conn.bind()
    }

    /** the PeerPage's clock and bars follow the call */
    private fun tickPeer() { peerPage?.tick(session.seconds, session.meSpeaking || session.peerSpeaking) }

    // ═════════════════════════════════════════════════════════════════════════
    // The stage: before pick-up and calls that never connected (CallStageScreen)
    // ═════════════════════════════════════════════════════════════════════════
    private inner class StagePage(ctx: Context) : FrameLayout(ctx) {
        private val kind: TextView
        private val face = CallFace(ctx, 140f, 4f)
        private val pulses = StagePulses(ctx)
        private val line: TextView
        private val sub: TextView
        private val status: TextView
        private val dotGreen = CallKit.blinkDot(ctx, CallKit.GREEN)
        private val dotRed = CallKit.blinkDot(ctx, Ui.c("#FF5A6A"))
        private val btn: CallKit.RoundBtn
        private val panel: FrameLayout

        init {
            setBackgroundColor(Color.WHITE)
            isClickable = true
            addView(CallKit.doodles(ctx), LayoutParams(-1, -1))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            addView(col, LayoutParams(-1, -1))
            val top = Tf.row(ctx).apply { setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), 0); minimumHeight = Ui.dpi(48f) }
            top.addView(View(ctx), Tf.ll(Ui.dpi(86f), 1))
            val mid = Tf.col(ctx, Gravity.CENTER_HORIZONTAL)
            mid.addView(CallKit.t(ctx, "True Find", 23f, CallKit.PURPLE, 800), Tf.ll())
            kind = CallKit.t(ctx, "", 11.5f, CallKit.SUB, 600)
            mid.addView(kind, Tf.ll(t = 2f))
            top.addView(mid, Tf.ll(0, Tf.W, 1f))
            top.addView(CallKit.safeBadge(ctx))
            col.addView(top)
            val center = LinearLayout(ctx).apply { clipChildren = false; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
            val ph = FrameLayout(ctx).apply { clipChildren = false }
            ph.addView(pulses, LayoutParams(Ui.dpi(140f), Ui.dpi(140f)))
            face.outlineSpotShadowColorCompat(Color.argb(110, 40, 10, 80))
            ph.addView(face, LayoutParams(Ui.dpi(140f), Ui.dpi(140f)))
            center.addView(ph, LinearLayout.LayoutParams(Ui.dpi(140f), Ui.dpi(140f)).apply { bottomMargin = Ui.dpi(18f) })
            line = CallKit.t(ctx, "", 24f, CallKit.INK, 800).apply { gravity = Gravity.CENTER }
            center.addView(line)
            sub = CallKit.t(ctx, "", 14f, CallKit.SUB, 400).apply { gravity = Gravity.CENTER }
            center.addView(sub, Tf.ll(t = 6f))
            col.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
            panel = FrameLayout(ctx).apply { background = Tf.topRound(CallKit.PANEL, 34f); clipToOutline = true }
            panel.addView(CallKit.panelDoodles(ctx), LayoutParams(-1, -1))
            val pc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(22f), Ui.dpi(22f), Ui.dpi(22f), Ui.dpi(26f)) }
            val srow = Tf.row(ctx)
            srow.addView(dotGreen, Tf.ll(Ui.dpi(7f), Ui.dpi(7f), r = 7f))
            srow.addView(dotRed, Tf.ll(Ui.dpi(7f), Ui.dpi(7f), r = 7f))
            status = CallKit.t(ctx, "", 12.5f, CallKit.LILAC_TEXT, 400)
            srow.addView(status)
            pc.addView(srow, Tf.ll())
            btn = CallKit.RoundBtn(ctx, "Cancel", 72f, CallKit.RED, 3f, Color.WHITE, CallKit.svg(CallKit.PHONE, 30f), 135f, 700) { onButton() }
            pc.addView(btn, Tf.ll(t = 14f))
            panel.addView(pc, LayoutParams(-1, -2))
            col.addView(panel, LinearLayout.LayoutParams(-1, -2))
        }

        private var action = ""

        fun enter() {
            alpha = 0f; scaleX = .97f; scaleY = .97f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).setInterpolator(CallKit.EASE_OUT).start()
            panel.translationY = Ui.dp(400f)
            panel.animate().translationY(0f).setStartDelay(50).setDuration(500).setInterpolator(CallKit.EASE_OUT_BACK).start()
        }

        fun bind() {
            val s = session
            val ss = s.screenState
            val stg = if (ss == "ended") "ended" else if (ss == "ringing") "ringing" else "calling"
            val who = CallKit.firstName(s.otherName)
            val rate = if (s.practice) (if (s.video) 40 else 10) else s.rate
            kind.text = "${if (s.video) "Video" else "Voice"} call${if (rate > 0) " · $rate coins/min" else ""}"
            line.text = when {
                stg == "ended" -> s.settledText
                stg == "calling" && s.status == "active" && !s.practice -> "Connecting…"
                stg == "ringing" && !s.practice -> "Ringing${if (who.isNotEmpty()) " $who" else ""}…"
                else -> "Calling${if (who.isNotEmpty()) " $who" else ""}…"
            }
            val st = s.settled
            sub.text = when {
                s.practice -> "${if (s.video) "Video" else "Voice"} demo call · nothing billed"
                s.error.isNotEmpty() -> "${s.error}${if (s.errorDetail.isNotEmpty()) " (${s.errorDetail})" else ""}"
                stg == "ended" && st != null && st.seconds > 0 -> "Duration ${CallKit.fmt(st.seconds)}"
                stg == "ended" -> ""
                else -> "Please wait a moment"
            }
            sub.visibility = if (sub.text.isEmpty()) GONE else VISIBLE
            face.setPhoto(s.otherPhoto)
            face.grey = stg == "ended"
            pulses.on = stg != "ended"
            pulses.visibility = if (stg == "ended") INVISIBLE else VISIBLE
            dotGreen.visibility = if (stg == "ended") GONE else VISIBLE
            dotRed.visibility = if (stg == "ended") VISIBLE else GONE
            status.text = if (stg == "ended") "Not connected" else "Connecting"
            action = if (stg == "ended") "close" else "end"
            val label = if (stg == "ended") "Close" else if (s.status == "active" && !s.practice) "End" else "Cancel"
            btn.set(label, CallKit.RED, 3f, Color.WHITE, CallKit.svg(CallKit.PHONE, 30f))
        }

        private fun onButton() { if (action == "close") session.onCallEnded() else session.hangUp() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // The call itself (DateCallScreen): voice and video
    // ═════════════════════════════════════════════════════════════════════════
    private inner class ConnPage(ctx: Context) : FrameLayout(ctx) {
        private val isVideo get() = session.video
        private var built = false
        private var builtVideo = false

        // background
        private val doodles = CallKit.doodles(ctx)
        private val videoLayer = FrameLayout(ctx)
        private val poster = CoverImage(ctx)
        private val remoteBox = FrameLayout(ctx)
        private val shade = GradientView(ctx, intArrayOf(Color.argb(166, 10, 6, 18), Color.argb(0, 10, 6, 18), Color.argb(0, 10, 6, 18), Color.argb(140, 10, 6, 18)), floatArrayOf(0f, .26f, .55f, 1f))
        private val camOffView = CamOffView(ctx)

        // content
        private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        private val title = CallKit.t(ctx, "True Find", 23f, CallKit.PURPLE, 800)
        private val statusText = CallKit.t(ctx, "", 11.5f, CallKit.INK, 600)

        // voice: the two people
        val meFace = CallFace(ctx, 116f, 4f)
        val peerFace = CallFace(ctx, 116f, 4f)
        private val meBars = CallKit.Bars(ctx)
        private val peerBars = CallKit.Bars(ctx)
        private val peerLabel = CallKit.t(ctx, "…", 16.5f, CallKit.INK, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
        private val meHeart = HeartBadge(ctx, "liked you for a date")
        private val peerHeart = HeartBadge(ctx, "You liked them for a date")
        private val muteBadge = FrameLayout(ctx)
        private lateinit var peopleRow: LinearLayout
        private lateinit var meCol: View
        private lateinit var peerCol: View

        // voice: the Date This card
        private lateinit var dateCard: LinearLayout
        private lateinit var dateBox: FrameLayout
        private val dateAsk = Tf.row(ctx)
        private val dateSent = Tf.row(ctx)
        private val dateTitle = CallKit.t(ctx, "", 20f, CallKit.INK, 800)
        private lateinit var heartBtn: LinearLayout
        private lateinit var heartCircle: FrameLayout
        private val dateErr = CallKit.t(ctx, "", 13f, Ui.c("#B91C1C"), 400)

        // video: her chip, my tile, the floating Date This
        val vPeerFace = CallFace(ctx, 46f, 3f, shadow = false)
        private val vPeerName = CallKit.t(ctx, "…", 15.5f, Color.WHITE, 700)
        private val vPeerBars = CallKit.Bars(ctx, white = true, count = 5)
        private val vPeerHeart = HeartBadge(ctx, "You liked them for a date")
        private lateinit var vPeerChip: LinearLayout
        private val tile = FrameLayout(ctx)
        private val tileBox = FrameLayout(ctx)
        private val tilePhoto = CoverImage(ctx)
        private val tileFace = CallFace(ctx, 84f, 0f, shadow = false)
        private val tileBorder = View(ctx)
        private val tileHeart = HeartBadge(ctx, "liked you for a date")
        private lateinit var vDate: LinearLayout
        private lateinit var vHeartBtn: LinearLayout
        private lateinit var vHeartCircle: FrameLayout
        private lateinit var vGoChat: LinearLayout
        private val vDateErr = CallKit.t(ctx, "", 12f, Color.WHITE, 400)
        private var tileX = 0f; private var tileY = 0f
        private var posterKey = "?"
        private var tileKey = "?"
        private var pillsKey = "?"
        private var lastMuted: Boolean? = null
        private var lastSpeaker: Boolean? = null
        private var lastCamOff: Boolean? = null
        private var lastTileTalk: Boolean? = null
        var practiceCam: PracticeCam? = null

        // the purple panel
        private lateinit var panel: FrameLayout
        private lateinit var minsChip: TextView
        private val duration = CallKit.t(ctx, "00:00", 20f, Color.WHITE, 700)
        private val balance = CallKit.t(ctx, "—", 19f, Color.WHITE, 700)
        private lateinit var muteBtn: CallKit.RoundBtn
        private lateinit var camBtn: CallKit.RoundBtn
        private lateinit var speakerBtn: CallKit.RoundBtn
        private lateinit var showBtns: TextView

        // over everything
        private val pills = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
        private val reportBtn = Tf.row(ctx)
        private val endedOverlay = FrameLayout(ctx)
        private val endedLabel = CallKit.t(ctx, "", 14f, CallKit.LILAC_TEXT, 400).apply { gravity = Gravity.CENTER }
        private val endedNote = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        private var greyAnim: ValueAnimator? = null
        private var greyed = false

        init { clipChildren = false }

        private fun build() {
            built = true
            builtVideo = isVideo
            val ctx = context
            removeAllViews()
            setBackgroundColor(if (builtVideo) Color.BLACK else Color.WHITE)
            if (builtVideo) {
                videoLayer.removeAllViews()
                videoLayer.addView(poster, LayoutParams(-1, -1))
                videoLayer.addView(remoteBox, LayoutParams(-1, -1))
                videoLayer.addView(shade, LayoutParams(-1, -1))
                videoLayer.addView(camOffView, LayoutParams(-1, -1))
                videoLayer.setOnClickListener { hideUi = !hideUi; bind() }
                addView(videoLayer, LayoutParams(-1, -1))
            } else addView(doodles, LayoutParams(-1, -1))

            content.removeAllViews()
            // header: the Report button sits on the left (drawn over it), brand in the middle, safety badge on the right
            val head = Tf.row(ctx).apply { setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), 0); minimumHeight = Ui.dpi(48f) }
            head.addView(View(ctx), Tf.ll(Ui.dpi(86f), 1))
            val mid = Tf.col(ctx, Gravity.CENTER_HORIZONTAL)
            title.setTextColor(if (builtVideo) Color.WHITE else CallKit.PURPLE)
            (title.parent as? ViewGroup)?.removeView(title)
            mid.addView(title, Tf.ll())
            val srow = Tf.row(ctx)
            srow.addView(CallKit.blinkDot(ctx, CallKit.GREEN), Tf.ll(Ui.dpi(7f), Ui.dpi(7f), r = 5f))
            statusText.setTextColor(if (builtVideo) Color.WHITE else CallKit.INK)
            (statusText.parent as? ViewGroup)?.removeView(statusText)
            srow.addView(statusText)
            mid.addView(srow, Tf.ll(t = 2f))
            head.addView(mid, Tf.ll(0, Tf.W, 1f))
            head.addView(CallKit.safeBadge(ctx))
            content.addView(head)

            if (builtVideo) buildVideoTop(ctx) else buildVoicePeople(ctx)
            if (builtVideo) {
                content.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
                buildVideoDate(ctx)
            } else {
                buildDateCard(ctx)
                content.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
            }
            buildPanel(ctx)
            val maxW = Ui.dpi(480f)
            addView(object : FrameLayout(ctx) {
                init { clipChildren = false }
                override fun onMeasure(w: Int, h: Int) {
                    val ww = MeasureSpec.getSize(w)
                    super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(ww, maxW), MeasureSpec.EXACTLY), h)
                    setMeasuredDimension(ww, measuredHeight)
                }
                override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    val cw = minOf(r - l, maxW); val x = (r - l - cw) / 2
                    content.layout(x, 0, x + cw, b - t)
                }
            }.also { it.addView(content, LayoutParams(-1, -1)) }, LayoutParams(-1, -1))

            // the "Show buttons" pill (video, buttons hidden)
            showBtns = CallKit.t(ctx, "", 13f, Color.WHITE, 700).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Tf.box(Color.argb(179, 10, 6, 18), 999f)
                setPadding(Ui.dpi(16f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(8f))
                compoundDrawablePadding = Ui.dpi(6f)
                setCompoundDrawablesWithIntrinsicBounds(CallKit.svg(CallKit.UP, 14f), null, null, null)
                setOnClickListener { hideUi = false; bind() }
                contentDescription = "Show call buttons"
                visibility = GONE
            }
            addView(showBtns, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dpi(18f) })

            // pills under the header: low balance, weak network, an error
            addView(pills, LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = Ui.dpi(118f) })
            pills.elevation = Ui.dp(30f)

            // the Report button (a real call that is going on)
            reportBtn.removeAllViews()
            reportBtn.apply {
                background = Tf.box(CallKit.INK, 17f)
                setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
                addView(ImageView(ctx).apply { setImageDrawable(CallKit.lu(CallKit.FLAG, 14f, Color.WHITE)) }, Tf.ll(Ui.dpi(14f), Ui.dpi(14f), r = 6f))
                addView(CallKit.t(ctx, "Report", 13f, Color.WHITE, 700))
                elevation = Ui.dp(40f)
                contentDescription = "Report this person"
                setOnClickListener { openReport() }
            }
            Ui.pressable(reportBtn, .96f)
            addView(reportBtn, LayoutParams(-2, Ui.dpi(34f), Gravity.TOP or Gravity.START).apply { leftMargin = Ui.dpi(14f); topMargin = Ui.dpi(14f) })

            // the soft "Call ended" card
            buildEnded(ctx)
            addView(endedOverlay, LayoutParams(-1, -1))
            endedOverlay.elevation = Ui.dp(25f)
        }

        private fun buildVoicePeople(ctx: Context) {
            peopleRow = Tf.row(ctx, Gravity.TOP).apply { setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0); clipChildren = false }
            fun column(face: CallFace, label: View, bars: View): LinearLayout = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
                clipChildren = false
                addView(face, Tf.ll(Ui.dpi(116f), Ui.dpi(116f)))
                addView(label, Tf.ll(Ui.dpi(136f), Tf.W, t = 10f))
                val bb = FrameLayout(ctx); bb.addView(bars, LayoutParams(-2, -2, Gravity.CENTER))
                addView(bb, Tf.ll(Ui.dpi(136f), Ui.dpi(26f), t = 10f))
            }
            val you = CallKit.t(ctx, "You", 16.5f, CallKit.INK, 600).apply { gravity = Gravity.CENTER }
            meFace.badge(meHeart, 34f, 34f, rightDp = -4f, topDp = -2f)
            muteBadge.background = Tf.circle(CallKit.RED)
            muteBadge.addView(ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.MIC, 16f, Color.WHITE)) }, LayoutParams(Ui.dpi(16f), Ui.dpi(16f), Gravity.CENTER))
            muteBadge.contentDescription = "Muted"
            meFace.badge(muteBadge, 32f, 32f, rightDp = 0f, bottomDp = 2f)
            peerFace.badge(peerHeart, 34f, 34f, rightDp = -4f, topDp = -2f)
            meCol = column(meFace, you, meBars)
            peerCol = column(peerFace, peerLabel, peerBars)
            peopleRow.addView(meCol, Tf.ll(Ui.dpi(136f), Tf.W))
            peopleRow.addView(View(ctx), Tf.ll(0, 1, 1f))
            peopleRow.addView(peerCol, Tf.ll(Ui.dpi(136f), Tf.W))
            content.addView(peopleRow, Tf.ll(Tf.M, Tf.W, t = 44f))
        }

        private fun buildDateCard(ctx: Context) {
            dateCard = Tf.col(ctx).apply { clipChildren = false }
            // the card's hard shadow (4 px right and down), drawn behind it: the box is exactly the card's size
            dateBox = object : FrameLayout(ctx) {
                private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CallKit.INK }
                private val rr = RectF()
                override fun dispatchDraw(c: Canvas) {
                    val d = Ui.dp(4f); val r = Ui.dp(20f)
                    rr.set(d, d, width + d, height + d)
                    c.drawRoundRect(rr, r, r, sp)
                    super.dispatchDraw(c)
                }
            }.apply { clipChildren = false }
            val card = FrameLayout(ctx).apply {
                background = Tf.box(Color.WHITE, 20f, 2f, CallKit.INK)
                setPadding(Ui.dpi(16f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f))
                minimumHeight = Ui.dpi(66f)
            }
            // not liked yet: "Date Riya?" + the heart
            dateTitle.setLineSpacing(0f, 1.1f)
            dateAsk.addView(dateTitle, Tf.ll(0, Tf.W, 1f))
            heartBtn = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply { minimumWidth = Ui.dpi(64f) }
            heartCircle = FrameLayout(ctx).apply { background = Tf.circle(CallKit.PINK_BG) }
            heartCircle.addView(ImageView(ctx).apply { setImageDrawable(CallKit.heart(28f)) }, LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
            heartBtn.addView(heartCircle, Tf.ll(Ui.dpi(44f), Ui.dpi(44f)))
            heartBtn.addView(CallKit.t(ctx, "Date This", 12f, CallKit.PINK_TEXT, 700), Tf.ll(t = 2f))
            heartBtn.setOnClickListener { like(heartBtn, heartCircle) }
            dateAsk.addView(heartBtn, Tf.ll(l = 12f))
            // after the heart: "Request sent" + Go to Chat
            val sentLeft = Tf.row(ctx)
            sentLeft.addView(ImageView(ctx).apply { setImageDrawable(CallKit.heart(20f)) }, Tf.ll(Ui.dpi(20f), Ui.dpi(20f), r = 8f))
            sentLeft.addView(CallKit.t(ctx, "Request sent", 18f, CallKit.INK, 800))
            dateSent.addView(sentLeft, Tf.ll(0, Tf.W, 1f))
            dateSent.addView(goChatPill(ctx, false), Tf.ll(Tf.W, Ui.dpi(44f), l = 12f))
            card.addView(dateAsk, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            card.addView(dateSent, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            dateBox.addView(card, LayoutParams(-1, -2))
            dateCard.addView(dateBox, Tf.ll(Tf.M, Tf.W))
            dateCard.addView(dateErr, Tf.ll(Tf.M, Tf.W, l = 4f, t = 12f, r = 4f))
            content.addView(dateCard, Tf.ll(Tf.M, Tf.W, l = 34f, t = 30f, r = 34f))
        }

        private fun goChatPill(ctx: Context, video: Boolean): LinearLayout = Tf.row(ctx, Gravity.CENTER).apply {
            background = Tf.box(CallKit.PURPLE, 999f)
            setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
            elevation = Ui.dp(6f)
            outlineSpotShadowColorCompat(if (video) Color.BLACK else Color.argb(150, 106, 31, 208))
            addView(ImageView(ctx).apply { setImageDrawable(if (video) CallKit.heart(18f) else CallKit.svg(CallKit.CHAT, 18f, Color.WHITE)) }, Tf.ll(Ui.dpi(18f), Ui.dpi(18f), r = 7f))
            addView(CallKit.t(ctx, "Go to Chat", if (video) 14f else 13.5f, Color.WHITE, 700))
            setOnClickListener { openPeer() }
            Ui.pressable(this, .96f)
        }

        private fun buildVideoTop(ctx: Context) {
            val row = Tf.row(ctx, Gravity.TOP).apply { setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0); clipChildren = false }
            vPeerChip = Tf.row(ctx).apply {
                background = Tf.box(Color.argb(140, 10, 6, 18), 999f)
                setPadding(Ui.dpi(5f), Ui.dpi(5f), Ui.dpi(14f), Ui.dpi(5f))
                clipChildren = false
            }
            vPeerFace.badge(vPeerHeart, 34f, 34f, rightDp = -12f, topDp = -10f)
            vPeerChip.addView(vPeerFace, Tf.ll(Ui.dpi(46f), Ui.dpi(46f), r = 10f))
            val nameCol = Tf.col(ctx)
            vPeerName.maxLines = 1; vPeerName.ellipsize = android.text.TextUtils.TruncateAt.END; vPeerName.maxWidth = Ui.dpi(170f)
            nameCol.addView(vPeerName, Tf.ll())
            val bb = FrameLayout(ctx); bb.addView(vPeerBars, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            nameCol.addView(bb, Tf.ll(Tf.W, Ui.dpi(16f), t = 2f))
            vPeerChip.addView(nameCol)
            row.addView(vPeerChip, Tf.ll())
            row.addView(View(ctx), Tf.ll(0, 1, 1f))
            // my own camera, top right (drag it anywhere)
            tile.clipChildren = false
            tileBox.background = Tf.box(CallKit.LAV2, 18f)
            tileBox.clipToOutline = true
            tileBox.addView(tileFace, LayoutParams(-1, -1))
            tileBox.addView(tilePhoto, LayoutParams(-1, -1))
            tile.addView(tileBox, LayoutParams(-1, -1))
            tileBorder.isClickable = false
            tile.addView(tileBorder, LayoutParams(-1, -1))
            tile.addView(tileHeart, LayoutParams(Ui.dpi(34f), Ui.dpi(34f), Gravity.END or Gravity.TOP).apply { rightMargin = -Ui.dpi(4f); topMargin = -Ui.dpi(2f) })
            tileHeart.elevation = Ui.dp(10f)
            tile.elevation = Ui.dp(4f)
            tile.contentDescription = "Your camera (drag to move)"
            var sx = 0f; var sy = 0f; var ox = 0f; var oy = 0f
            tile.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = tileX; oy = tileY; true }
                    MotionEvent.ACTION_MOVE -> {
                        val w = width.toFloat(); val h = height.toFloat()
                        tileX = (ox + e.rawX - sx).coerceIn(-(w - Ui.dp(120f)), 0f)
                        tileY = (oy + e.rawY - sy).coerceIn(-Ui.dp(20f), h - Ui.dp(260f))
                        tile.translationX = tileX; tile.translationY = tileY
                        true
                    }
                    else -> true
                }
            }
            row.addView(tile, Tf.ll(Ui.dpi(84f), Ui.dpi(118f)))
            content.addView(row, Tf.ll(Tf.M, Tf.W, t = 16f))
        }

        private fun buildVideoDate(ctx: Context) {
            vDate = Tf.col(ctx, Gravity.END).apply { clipChildren = false }
            vHeartBtn = Tf.col(ctx, Gravity.CENTER_HORIZONTAL)
            vHeartCircle = FrameLayout(ctx).apply { background = Tf.circle(Color.argb(235, 255, 255, 255)); elevation = Ui.dp(6f); outlineSpotShadowColorCompat(Color.BLACK) }
            vHeartCircle.addView(ImageView(ctx).apply { setImageDrawable(CallKit.heart(32f)) }, LayoutParams(Ui.dpi(32f), Ui.dpi(32f), Gravity.CENTER))
            vHeartBtn.addView(vHeartCircle, Tf.ll(Ui.dpi(54f), Ui.dpi(54f)))
            vHeartBtn.addView(CallKit.t(ctx, "Date This", 12.5f, Color.WHITE, 800).apply { setShadowLayer(Ui.dp(4f), 0f, Ui.dp(1f), Color.argb(178, 0, 0, 0)) }, Tf.ll(t = 3f))
            vHeartBtn.setOnClickListener { like(vHeartBtn, vHeartCircle) }
            vDate.addView(vHeartBtn, Tf.ll())
            vGoChat = goChatPill(ctx, true)
            vDate.addView(vGoChat, Tf.ll(Tf.W, Ui.dpi(44f)))
            vDateErr.setShadowLayer(Ui.dp(3f), 0f, Ui.dp(1f), Color.BLACK)
            vDateErr.maxWidth = Ui.dpi(160f)
            vDate.addView(vDateErr, Tf.ll(t = 6f))
            content.addView(vDate, Tf.ll(Tf.W, Tf.W, r = 14f, b = 12f, g = Gravity.END))
        }

        private fun buildPanel(ctx: Context) {
            val v = builtVideo
            panel = FrameLayout(ctx).apply { background = Tf.topRound(CallKit.PANEL, if (v) 22f else 28f); clipToOutline = true; isClickable = true }
            panel.addView(CallKit.panelDoodles(ctx), LayoutParams(-1, -1))
            val pc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            if (v) pc.setPadding(Ui.dpi(16f), Ui.dpi(4f), Ui.dpi(16f), Ui.dpi(16f)) else pc.setPadding(Ui.dpi(22f), Ui.dpi(12f), Ui.dpi(22f), Ui.dpi(34f))
            if (v) {
                val handle = FrameLayout(ctx).apply { setOnClickListener { hideUi = true; bind() }; contentDescription = "Hide call buttons" }
                handle.addView(View(ctx).apply { background = Tf.box(Color.argb(89, 255, 255, 255), 3f) }, LayoutParams(Ui.dpi(40f), Ui.dpi(5f), Gravity.CENTER))
                pc.addView(handle, Tf.ll(Ui.dpi(56f), Ui.dpi(14f), g = Gravity.CENTER_HORIZONTAL))
            }
            minsChip = CallKit.t(ctx, "", 12f, Color.WHITE, 400).apply {
                background = Tf.box(Color.argb(36, 255, 255, 255), 999f, 1f, Color.argb(89, 255, 255, 255))
                setPadding(Ui.dpi(11f), Ui.dpi(3f), Ui.dpi(11f), Ui.dpi(3f))
            }
            if (!v) pc.addView(minsChip, Tf.ll(g = Gravity.CENTER_HORIZONTAL, b = 9f))
            val row = Tf.row(ctx)
            val left = Tf.col(ctx)
            if (!v) left.addView(label(ctx, "Duration"), Tf.ll(b = 1f))
            val dur = Tf.row(ctx)
            dur.addView(CallKit.blinkDot(ctx, Ui.c("#FF5A6A")), Tf.ll(Ui.dpi(8f), Ui.dpi(8f), r = 6f))
            duration.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(if (v) 16f else 20f))
            duration.fontFeatureSettings = "tnum"
            dur.addView(duration)
            left.addView(dur)
            row.addView(left, Tf.ll(0, Tf.W, 1f))
            val right = Tf.col(ctx, Gravity.END)
            if (!v) right.addView(label(ctx, "Balance"), Tf.ll(b = 1f))
            val bal = Tf.row(ctx)
            bal.addView(CallKit.CoinTF(ctx), Tf.ll(r = 8f))
            balance.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(if (v) 16f else 19f))
            bal.addView(balance, Tf.ll(r = 8f))
            val plus = FrameLayout(ctx).apply { setPadding(Ui.dpi(9f), Ui.dpi(9f), Ui.dpi(9f), Ui.dpi(9f)); contentDescription = "Add coins"; setOnClickListener { openAddCoins() } }
            plus.addView(FrameLayout(ctx).apply {
                background = Tf.circle(Color.WHITE)
                addView(ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.PLUS, 14f, CallKit.PURPLE)) }, LayoutParams(Ui.dpi(14f), Ui.dpi(14f), Gravity.CENTER))
            }, LayoutParams(Ui.dpi(26f), Ui.dpi(26f)))
            bal.addView(plus, Tf.ll().apply { setMargins(-Ui.dpi(9f), -Ui.dpi(9f), -Ui.dpi(9f), -Ui.dpi(9f)) })
            right.addView(bal)
            row.addView(right)
            pc.addView(row, Tf.ll(Tf.M, Tf.W, b = if (v) 4f else 9f))
            val btns = Tf.row(ctx, Gravity.TOP).apply { setPadding(Ui.dpi(4f), 0, Ui.dpi(4f), 0) }
            val s = session
            val sz = if (v) 44f else 52f
            muteBtn = CallKit.RoundBtn(ctx, "Mute", sz, Color.argb(31, 255, 255, 255), 1f, Color.argb(56, 255, 255, 255), CallKit.svg(CallKit.MIC, 24f), compact = v) { s.toggleMute() }
            btns.addView(muteBtn); btns.addView(View(ctx), Tf.ll(0, 1, 1f))
            if (v) {
                camBtn = CallKit.RoundBtn(ctx, "Camera", 44f, Color.argb(31, 255, 255, 255), 1f, Color.argb(56, 255, 255, 255), CallKit.svg(CallKit.CAMERA, 24f), compact = true) { s.toggleCam() }
                btns.addView(camBtn); btns.addView(View(ctx), Tf.ll(0, 1, 1f))
            }
            val end = CallKit.RoundBtn(ctx, "End", if (v) 52f else 62f, CallKit.RED, 3f, Color.WHITE, CallKit.svg(CallKit.PHONE, 30f), 135f, 700, compact = v) { s.hangUp() }
            btns.addView(end); btns.addView(View(ctx), Tf.ll(0, 1, 1f))
            speakerBtn = CallKit.RoundBtn(ctx, "Speaker", sz, Color.argb(31, 255, 255, 255), 1f, Color.argb(56, 255, 255, 255), CallKit.svg(CallKit.SPEAKER, 24f), compact = v) { s.toggleSpeaker() }
            btns.addView(speakerBtn)
            pc.addView(btns, Tf.ll(Tf.M, Tf.W))
            panel.addView(pc, LayoutParams(-1, -2))
            content.addView(panel, Tf.ll(Tf.M, Tf.W))
        }

        private fun label(ctx: Context, s: String) = CallKit.t(ctx, s.uppercase(), 10.5f, Ui.c("#EBDDFF"), 600).apply { letterSpacing = 1f / 10.5f }

        private fun buildEnded(ctx: Context) {
            endedOverlay.removeAllViews()
            endedOverlay.setBackgroundColor(Color.argb(184, 255, 255, 255))
            endedOverlay.isClickable = true
            val card = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
                background = Tf.box(CallKit.INK, 26f)
                setPadding(Ui.dpi(28f), Ui.dpi(24f), Ui.dpi(28f), Ui.dpi(24f))
                minimumWidth = Ui.dpi(230f)
                elevation = Ui.dp(18f)
                outlineSpotShadowColorCompat(Color.argb(140, 20, 5, 40))
            }
            val icon = ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.PHONE, 28f)); rotation = 135f; scaleType = ImageView.ScaleType.CENTER; background = Tf.circle(CallKit.RED) }
            card.addView(icon, Tf.ll(Ui.dpi(60f), Ui.dpi(60f)))
            card.addView(CallKit.t(ctx, "Call ended", 21f, Color.WHITE, 800), Tf.ll(t = 10f))
            endedLabel.fontFeatureSettings = "tnum"
            card.addView(endedLabel, Tf.ll(t = 10f))
            card.addView(endedNote, Tf.ll(t = 10f))
            endedOverlay.addView(card, LayoutParams(-2, -2, Gravity.CENTER))
            endedOverlay.setPadding(Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f))
            endedOverlay.visibility = GONE
        }

        // ── first look: fade + scale in, the photos from the sides, the card up, the panel from below ──
        fun enter() {
            if (!built || builtVideo != isVideo) build()
            alpha = 0f; scaleX = .97f; scaleY = .97f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(CallKit.EASE_OUT).start()
            val slide = { v: View, dx: Float, delay: Long ->
                v.alpha = 0f; v.translationX = Ui.dp(dx)
                v.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(550).setInterpolator(CallKit.EASE_OUT_BACK).start()
            }
            if (builtVideo) { slide(vPeerChip, -46f, 120); tile.alpha = 0f; tile.animate().alpha(1f).setStartDelay(120).setDuration(550).start() }
            else { slide(meCol, -46f, 120); slide(peerCol, 46f, 120) }
            val up = if (builtVideo) vDate else dateCard
            up.alpha = 0f; up.translationY = Ui.dp(28f)
            up.animate().alpha(1f).translationY(0f).setStartDelay(280).setDuration(500).setInterpolator(CallKit.EASE_OUT_BACK).start()
            panel.translationY = Ui.dp(500f)
            panel.post { panel.translationY = panel.height.toFloat().coerceAtLeast(Ui.dp(100f)); panel.animate().translationY(0f).setStartDelay(50).setDuration(550).setInterpolator(CallKit.EASE_OUT_BACK).start() }
            if (builtVideo && session.practice) startPracticeCam()
        }

        private fun startPracticeCam() {
            if (practiceCam != null || session.mode != CallSession.Mode.PRACTICE) return
            mgr.askPerms(true) { ok -> if (ok && !closing && practiceCam == null) practiceCam = PracticeCam(context, mgr.lifecycle()).also { it.start(); bind() } }
        }

        // ── every change ──
        fun bind() {
            if (!built || builtVideo != isVideo) { build(); enter() }
            val s = session
            val ctx = context
            val first = CallKit.firstName(s.otherName)
            val peerName = first.ifEmpty { "them" }
            val liked = s.iLiked || landed
            val ended = s.ended
            val v = builtVideo
            statusText.text = if (s.practice) "Demo call · nothing billed" else "Connected · HD Voice"
            // they gave ME a heart: a banner for a few seconds (the badge on my photo stays)
            if (s.theyLiked && !seenTheirs) { seenTheirs = true; toast("$peerName liked you for a date", 4000) }

            if (!v) {
                meFace.setPhoto(s.mePhoto); peerFace.setPhoto(s.otherPhoto)
                meFace.border(if (s.meSpeaking) CallKit.PURPLE else Color.WHITE)
                peerFace.border(if (s.peerSpeaking) CallKit.PURPLE else Color.WHITE)
                peerLabel.text = first.ifEmpty { "…" }
                show(meBars, s.meSpeaking && !ended, invisible = true)
                show(peerBars, s.peerSpeaking && !ended, invisible = true)
                badge(meHeart, s.theyLiked)
                badge(peerHeart, liked)
                muteBadge.visibility = if (s.muted) VISIBLE else GONE
                dateTitle.text = "Date $peerName?"
                dateAsk.visibility = if (liked) INVISIBLE else VISIBLE
                dateSent.visibility = if (liked) VISIBLE else INVISIBLE
                heartCircle.alpha = if (flying) .35f else 1f
                dateErr.text = likeErr; dateErr.visibility = if (likeErr.isEmpty()) GONE else VISIBLE
                heartBtn.contentDescription = "Date $peerName"
                showBtns.visibility = GONE
            } else {
                vPeerFace.setPhoto(s.otherPhoto)
                vPeerFace.border(if (s.peerSpeaking) CallKit.LILAC else Color.WHITE)
                vPeerName.text = first.ifEmpty { "…" }
                show(vPeerBars, s.peerSpeaking && !ended, invisible = true)
                badge(vPeerHeart, liked)
                badge(tileHeart, s.theyLiked)
                vHeartBtn.visibility = if (liked) GONE else VISIBLE
                vGoChat.visibility = if (liked) VISIBLE else GONE
                vHeartCircle.alpha = if (flying) .35f else 1f
                vDateErr.text = likeErr; vDateErr.visibility = if (likeErr.isEmpty()) GONE else VISIBLE
                // her camera fills the screen; her photo under it (and when her camera is off: her photo, big and round)
                val dm = resources.displayMetrics
                if (posterKey != s.otherPhoto) { posterKey = s.otherPhoto; CallPics.fill(ctx, s.otherPhoto, dm.widthPixels, dm.heightPixels) { b -> if (posterKey == s.otherPhoto) poster.bitmap = b } }
                val rv = s.media?.remoteView
                placeIn(remoteBox, rv)
                if (s.practice) remoteBox.visibility = GONE else remoteBox.visibility = if (s.peerVideoOn) VISIBLE else INVISIBLE
                val peerOff = !s.practice && !s.peerVideoOn
                if (s.peerVideoOn) everVideo = true
                camOffView.visibility = if (peerOff) VISIBLE else GONE
                if (peerOff) camOffView.bind(s.otherPhoto, s.otherName, s.peerSpeaking && !ended, connecting = !everVideo && s.seconds < 15 && !ended)
                // my own camera (or my photo when it is off)
                val lv = s.media?.localView ?: practiceCam?.view
                val camOn = !s.camOff && lv != null
                placeIn(tileBox, if (camOn) lv else null, keep = 2)
                tilePhoto.visibility = if (camOn) GONE else VISIBLE
                tileFace.visibility = if (camOn) GONE else VISIBLE
                if (!camOn) {
                    tileFace.setPhoto(s.mePhoto)
                    if (tileKey != s.mePhoto) {
                        tileKey = s.mePhoto
                        tilePhoto.bitmap = null
                        if (s.mePhoto.isNotEmpty()) CallPics.fill(ctx, s.mePhoto, Ui.dpi(84f), Ui.dpi(118f)) { b -> if (tileKey == s.mePhoto) tilePhoto.bitmap = b }
                    }
                    tileFace.visibility = if (tilePhoto.bitmap != null) GONE else VISIBLE
                }
                if (lastTileTalk != s.meSpeaking) { lastTileTalk = s.meSpeaking; tileBorder.background = Tf.box(Color.TRANSPARENT, 18f, 3f, if (s.meSpeaking) CallKit.LILAC else Color.WHITE) }
                // tap the picture (or the arrow) to hide / show the buttons
                val hidden = hideUi && !ended
                vPeerChip.animate().alpha(if (hidden) 0f else 1f).setDuration(300).start()
                vDate.animate().alpha(if (hidden) 0f else 1f).setDuration(300).start()
                vDate.isEnabled = !hidden
                vHeartBtn.isClickable = !hidden && !ended; vGoChat.isClickable = !hidden && !ended
                panel.animate().translationY(if (hidden) panel.height * 1.1f else 0f).setDuration(350).setInterpolator(CallKit.EASE_OUT_BACK).start()
                showBtns.text = "${CallKit.fmt(s.seconds)} · Show buttons"
                showBtns.visibility = if (hidden) VISIBLE else GONE
                if (lastCamOff != s.camOff) {
                    lastCamOff = s.camOff
                    camBtn.set(if (s.camOff) "Camera on" else "Camera", if (s.camOff) Color.WHITE else Color.argb(31, 255, 255, 255), 1f, Color.argb(56, 255, 255, 255),
                        CallKit.svg(if (s.camOff) CallKit.CAMERA_OFF else CallKit.CAMERA, 24f, if (s.camOff) CallKit.INK else Color.WHITE))
                }
            }
            // the panel
            val left = s.secsLeft
            val minsLeft = left?.let { Math.ceil(it / 60.0).toLong().coerceAtLeast(0) }
            if (!v) {
                val rate = if (s.practice) (if (s.video) 40 else 10) else s.rate
                minsChip.text = "About $minsLeft min left${if (rate > 0) " · $rate coins/min" else ""}"
                minsChip.visibility = if (s.showLeft && minsLeft != null) VISIBLE else GONE
            }
            duration.text = CallKit.fmt(s.seconds)
            balance.text = s.coins?.toString() ?: "—"
            if (lastMuted != s.muted) {
                lastMuted = s.muted
                muteBtn.set(if (s.muted) "Unmute" else "Mute", if (s.muted) Color.WHITE else Color.argb(31, 255, 255, 255), 1f, Color.argb(56, 255, 255, 255),
                    CallKit.svg(CallKit.MIC, 24f, if (s.muted) CallKit.INK else Color.WHITE))
            }
            if (lastSpeaker != s.speakerOn) {
                lastSpeaker = s.speakerOn
                speakerBtn.set("Speaker", if (s.speakerOn) Color.WHITE else Color.argb(31, 255, 255, 255), 1f, Color.argb(56, 255, 255, 255),
                    CallKit.svg(if (s.speakerOn) CallKit.SPEAKER_ON else CallKit.SPEAKER, 24f, if (s.speakerOn) CallKit.PURPLE else Color.WHITE))
            }

            // over the call (hidden while Go to Chat is open, unless the call is over)
            val peerOpen = peerPage != null && !ended
            bindPills(ctx, s, peerOpen || ended)
            reportBtn.visibility = if (s.live && reportClose == null && !peerOpen) VISIBLE else GONE
            if (ended && peerPage != null) closePeer()

            // the call is over: the page greys out under the soft card
            setGrey(ended)
            endedOverlay.visibility = if (ended) VISIBLE else GONE
            if (ended) {
                endedLabel.text = s.endedLabel
                bindEndedNote(ctx, s)
            }
            tickPeer()
        }

        private fun bindPills(ctx: Context, s: CallSession, hidden: Boolean) {
            val left0 = s.secsLeft
            val low0 = s.isCaller && left0 != null && left0 <= 30 && (s.mode != CallSession.Mode.PRACTICE || s.previewLow)
            val key = if (hidden || s.settled != null) "" else "${if (low0) left0 else -1}|${s.netState == "RECONNECTING"}|${s.error}"
            if (key == pillsKey) return
            pillsKey = key
            pills.removeAllViews()
            if (key.isEmpty()) return
            fun pill(): LinearLayout = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
                background = Tf.box(CallKit.INK, 16f)
                setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(10f))
                elevation = Ui.dp(8f)
            }
            val left = s.secsLeft
            if (s.isCaller && left != null && left <= 30 && (s.mode != CallSession.Mode.PRACTICE || s.previewLow)) {
                val p = pill()
                p.addView(CallKit.t(ctx, "Balance running low · call ends in ${left}s", 14f, Color.WHITE, 600).apply { gravity = Gravity.CENTER })
                val b = Tf.row(ctx, Gravity.CENTER).apply {
                    background = Tf.box(CallKit.VIOLET, 12f)
                    setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
                    addView(ImageView(ctx).apply { setImageDrawable(CallKit.lu(Lu.Wallet, 16f, Color.WHITE)) }, Tf.ll(Ui.dpi(16f), Ui.dpi(16f), r = 6f))
                    addView(CallKit.t(ctx, "End & add coins", 14f, Color.WHITE, 700))
                    setOnClickListener { s.endAndGo("/wallet") }
                }
                Ui.pressable(b)
                p.addView(b, Tf.ll(Tf.W, Ui.dpi(36f), t = 8f))
                pills.addView(p, Tf.ll(b = 8f))
            }
            if (s.netState == "RECONNECTING") {
                val p = Tf.row(ctx).apply { background = Tf.box(CallKit.INK, 16f); setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(10f)); elevation = Ui.dp(8f) }
                p.addView(TfLoader(ctx, 16f, Color.WHITE), Tf.ll(Ui.dpi(16f), Ui.dpi(16f), r = 6f))
                p.addView(CallKit.t(ctx, "Weak network · reconnecting…", 14f, Color.WHITE, 600))
                pills.addView(p, Tf.ll(b = 8f))
            }
            if (s.error.isNotEmpty()) {
                val p = CallKit.t(ctx, s.error, 14f, Color.WHITE, 600).apply {
                    gravity = Gravity.CENTER
                    background = Tf.box(Ui.c("#B91C1C"), 16f)
                    setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(10f))
                    maxWidth = Ui.dpi(320f)
                    elevation = Ui.dp(8f)
                }
                pills.addView(p, Tf.ll(b = 8f))
            }
        }

        private var noteKey = ""
        private fun bindEndedNote(ctx: Context, s: CallSession) {
            val charged = if (s.mode == CallSession.Mode.PREVIEW) s.previewCharge else if (s.practice) 0L else s.settled?.charged ?: 0L
            val addCoins = s.ranOut && s.isCaller
            val key = "$charged|$addCoins"
            if (key == noteKey) return
            noteKey = key
            endedNote.removeAllViews()
            if (charged > 0) {
                endedNote.addView(Tf.row(ctx).apply {
                    addView(ImageView(ctx).apply { setImageDrawable(CallKit.lu(CallKit.COINS, 16f, Ui.c("#FFB800"))) }, Tf.ll(Ui.dpi(16f), Ui.dpi(16f), r = 4f))
                    addView(CallKit.t(ctx, "$charged coins charged", 14f, Color.argb(204, 255, 255, 255), 400))
                })
            }
            if (addCoins) {
                val b = Tf.row(ctx, Gravity.CENTER).apply {
                    background = Tf.box(CallKit.VIOLET, 16f)
                    setPadding(Ui.dpi(20f), 0, Ui.dpi(20f), 0)
                    addView(ImageView(ctx).apply { setImageDrawable(CallKit.lu(Lu.Wallet, 16f, Color.WHITE)) }, Tf.ll(Ui.dpi(16f), Ui.dpi(16f), r = 6f))
                    addView(CallKit.t(ctx, "Add coins", 14f, Color.WHITE, 700))
                    setOnClickListener { leave("/wallet") }
                }
                Ui.pressable(b)
                endedNote.addView(b, Tf.ll(Tf.W, Ui.dpi(40f), t = 4f))
            }
            endedNote.visibility = if (charged > 0 || addCoins) VISIBLE else GONE
        }

        /** grayscale(.6) over the page when the call is over (and a 3 px blur under the card, where the phone can) */
        private fun setGrey(on: Boolean) {
            if (on == greyed) return
            greyed = on
            greyAnim?.cancel()
            if (!on) { content.setLayerType(LAYER_TYPE_NONE, null); if (Build.VERSION.SDK_INT >= 31) content.setRenderEffect(null); return }
            val paint = Paint()
            content.setLayerType(LAYER_TYPE_HARDWARE, paint)
            greyAnim = ValueAnimator.ofFloat(1f, .4f).apply {
                duration = 500
                addUpdateListener {
                    paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(it.animatedValue as Float) })
                    content.setLayerPaint(paint)
                }
                start()
            }
            if (Build.VERSION.SDK_INT >= 31) {
                try { content.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(Ui.dp(3f), Ui.dp(3f), android.graphics.Shader.TileMode.CLAMP)) } catch (t: Throwable) { }
            }
        }
    }

    /** a view in a box: the box keeps [keep] views of its own first, the given view is put last (or removed) */
    private fun placeIn(box: FrameLayout, v: View?, keep: Int = 0) {
        val cur = if (box.childCount > keep) box.getChildAt(box.childCount - 1) else null
        if (cur === v) return
        if (cur != null && cur is TextureView || cur is androidx.camera.view.PreviewView) box.removeView(cur)
        if (v == null) return
        (v.parent as? ViewGroup)?.removeView(v)
        box.addView(v, LayoutParams(-1, -1))
    }

    private fun show(v: View, on: Boolean, invisible: Boolean = false) { v.visibility = if (on) VISIBLE else if (invisible) INVISIBLE else GONE }

    private fun badge(b: HeartBadge, on: Boolean) {
        if (on && b.visibility != VISIBLE) { b.visibility = VISIBLE; b.pop() }
        else if (!on) b.visibility = GONE
    }

    /** the other person's camera is off (video): her photo, big and round, over a soft copy of it (PeerCameraOff) */
    private inner class CamOffView(ctx: Context) : FrameLayout(ctx) {
        private val bg = GradientView(ctx, intArrayOf(Ui.c("#3B0F7A"), Ui.c("#24074F"), Ui.c("#120428")), floatArrayOf(0f, .48f, 1f), 165f)
        private val blur = CoverImage(ctx).apply { webBlur = true; alpha = .9f }
        private val dim = GradientView(ctx, intArrayOf(Color.argb(158, 18, 4, 40), Color.argb(89, 26, 8, 52), Color.argb(204, 18, 4, 40)), floatArrayOf(0f, .4f, 1f))
        private val ring = View(ctx)
        private val face = CallFace(ctx, 136f, 4f)
        private val name = CallKit.t(ctx, "", 26f, Color.WHITE, 800).apply { setShadowLayer(Ui.dp(10f), 0f, Ui.dp(2f), Color.argb(89, 0, 0, 0)) }
        private val bars = CallKit.Bars(ctx, white = true, count = 5)
        private val chip = Tf.row(ctx)
        private val chipText = CallKit.t(ctx, "", 13f, Color.WHITE, 700)
        private val chipDot = CallKit.blinkDot(ctx, CallKit.GREEN)
        private val chipIcon = ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.CAMERA_OFF, 15f, Color.WHITE)) }
        private var raw = "?"
        init {
            addView(bg, LayoutParams(-1, -1)); addView(blur, LayoutParams(-1, -1))
            addView(CallKit.panelDoodles(ctx), LayoutParams(-1, -1)); addView(dim, LayoutParams(-1, -1))
            val col = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply { setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
            val ph = FrameLayout(ctx).apply { clipChildren = false }
            ph.addView(ring, LayoutParams(Ui.dpi(152f), Ui.dpi(152f), Gravity.CENTER))
            ph.addView(face, LayoutParams(Ui.dpi(136f), Ui.dpi(136f), Gravity.CENTER))
            col.addView(ph, Tf.ll(Ui.dpi(152f), Ui.dpi(152f)))
            col.addView(name, Tf.ll(t = 4f))
            val bb = FrameLayout(ctx); bb.addView(bars, LayoutParams(-2, -2, Gravity.CENTER))
            col.addView(bb, Tf.ll(Tf.W, Ui.dpi(18f), t = 12f))
            chip.background = Tf.box(Color.argb(33, 255, 255, 255), 999f, 1f, Color.argb(61, 255, 255, 255))
            chip.setPadding(Ui.dpi(13f), Ui.dpi(6f), Ui.dpi(13f), Ui.dpi(6f))
            chip.addView(chipDot, Tf.ll(Ui.dpi(7f), Ui.dpi(7f), r = 7f))
            chip.addView(chipIcon, Tf.ll(Ui.dpi(15f), Ui.dpi(15f), r = 7f))
            chip.addView(chipText)
            col.addView(chip, Tf.ll(t = 12f))
            addView(col, LayoutParams(-1, -2).apply { topMargin = 0 })
            col.tag = "col"
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            // the photo block starts at 24 % of the height
            val col = findViewWithTag<View>("col") ?: return
            val y = ((b - t) * .24f).toInt()
            col.layout(0, y, r - l, y + col.measuredHeight)
        }
        fun bind(photo: String, n: String, speaking: Boolean, connecting: Boolean) {
            if (photo != raw) {
                raw = photo
                face.setPhoto(photo)
                if (photo.isNotEmpty()) CallPics.blurred(context, photo, width.coerceAtLeast(Ui.dpi(360f)), height.coerceAtLeast(Ui.dpi(760f))) { b -> blur.bitmap = b } else blur.bitmap = null
            }
            ring.background = Tf.circle(Color.TRANSPARENT, 2f, if (speaking) Color.argb(230, 183, 148, 255) else Color.argb(46, 255, 255, 255))
            name.text = CallKit.firstName(n).ifEmpty { "…" }
            show(bars, speaking, invisible = true)
            chipDot.visibility = if (connecting) VISIBLE else GONE
            chipIcon.visibility = if (connecting) GONE else VISIBLE
            chipText.text = if (connecting) "Connecting video…" else "Camera off"
            contentDescription = if (connecting) "Connecting to ${CallKit.firstName(n).ifEmpty { "them" }}" else "${CallKit.firstName(n).ifEmpty { "Their" }} camera is off"
        }
    }

    // ═════════════════ test pictures ═════════════════
    fun previewOpen(what: String) {
        when (what) {
            "addcoins" -> addCoinsClose = CallSheets.addCoins(this, onKeep = {}) {}
            "report" -> openReport()
            "rating" -> { ratingOpen = true; CallSheets.rating(this, session, session.otherName, preview = 0) { } }
            "rating2" -> { ratingOpen = true; CallSheets.rating(this, session, session.otherName, preview = 2) { } }
            "thanks" -> { ratingOpen = true; CallSheets.rating(this, session, session.otherName, preview = -1) { } }
            "peer" -> openPeer()
            "peerchat" -> { openPeer(); peerPage?.preview("chat") }
            "peerlocked" -> { openPeer(); peerPage?.preview("locked") }
            "premium" -> { openPeer(); peerPage?.preview("premium") }
            "hidden" -> { hideUi = true; conn.bind() }
            "toast" -> toast("Date request sent to ${CallKit.firstName(session.otherName)}", 60000)
            "liketoast" -> toast("${CallKit.firstName(session.otherName)} liked you for a date", 60000)
        }
    }

    @Suppress("unused") private val keepGd = GradientDrawable()
}
