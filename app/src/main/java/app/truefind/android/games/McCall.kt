package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Mood Changer — the live voice call with the 7 rounds (native port of mcCall.jsx → Call).
 * Everything shown comes from the server: the boy's taps (Next, Skip, Change topic) move the game for
 * both phones; the girl's screen follows within a couple of seconds.
 * Built once; each new server state or voice change only updates what changed (no rebuild, no lag).
 */
@SuppressLint("ViewConstructor")
class McCallView(ctx: Context, private val sc: MoodChangerScreen) : FrameLayout(ctx) {
    private companion object {
        val ROUND_ICON = mapOf(1 to "Hand", 2 to "Waves", 3 to "HeartHandshake", 4 to "Mountain", 5 to "Star", 6 to "PartyPopper", 7 to "Sun")
        /** title, body */
        val CHECKINS = mapOf(
            "skips" to ("Want a different kind of question?" to "No problem, some questions just don’t click. Pick a topic, or try something lighter."),
            "dislikes" to ("Let’s keep it lighter" to "Got it. The next questions will be easier and more fun."),
            "silence" to ("A quiet moment" to "Silence is okay. If you’d like, try an easier question or take a short break."),
        )
        val EASE_IN_OUT = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    }

    private val h = Handler(Looper.getMainLooper())
    private var disposed = false
    private var compact = false
    private var builtCompact: Boolean? = null

    // ── state (the web component's useState / useRef) ──
    private var sheet: Sheet? = null
    private var sheetType = ""
    private var ciSheet: Sheet? = null
    private var ciShown: String? = null
    private var introFor = 0
    private var seenRound = 0
    private var introT: Runnable? = null
    private var pauseLeft = 0
    private var pauseKey = ""
    private var pauseT: Runnable? = null
    private var busy = false
    private var silenceAsked = false
    private var lastQ = ""
    private var qKey = ""
    private var reportReason: String? = null
    private var reportBtn: MC.Btn? = null
    private var reportOpts: LinearLayout? = null

    // ── views ──
    private val col = G.vbox(ctx)
    private lateinit var timerT: TextView
    private lateinit var voiceRow: LinearLayout
    private lateinit var voiceDot: FrameLayout
    private lateinit var voiceText: TextView
    private lateinit var voiceRetry: TextView
    private lateinit var roundNum: TextView
    private lateinit var roundTitle: TextView
    private lateinit var pauseChip: LinearLayout
    private val progress = ArrayList<View>()
    private lateinit var myAv: McAvatar
    private lateinit var prAv: McAvatar
    private lateinit var myTag: LinearLayout
    private lateinit var prTag: LinearLayout
    private lateinit var prName: TextView
    private lateinit var wave: Waveform
    private lateinit var qcol: LinearLayout
    private lateinit var qcard: LinearLayout
    private lateinit var muteC: FrameLayout
    private lateinit var muteT: TextView
    private lateinit var muteBtn: LinearLayout
    private lateinit var loudC: FrameLayout
    private lateinit var loudT: TextView
    private val busyViews = ArrayList<View>()      // web: disabled={busy}
    private var breakBig: TextView? = null
    private var tagKey = ""
    private var voiceKey = ""
    private val anims = ArrayList<ValueAnimator>()
    private val qAnims = ArrayList<ValueAnimator>()

    init {
        background = CssBg(0f, listOf(CssBg.Linear(180f, intArrayOf(MC.call1, MC.call2, MC.call3), floatArrayOf(0f, 0.55f, 1f))))
        isClickable = true
        addView(col, LayoutParams(G.MATCH, G.MATCH))
        val hNow = if (sc.height > 0) sc.height else resources.displayMetrics.heightPixels
        compact = hNow / resources.displayMetrics.density < 730f
        build()
        tickClock()
        silenceLoop()
        alpha = 0.35f; translationY = G.dp(6f)
        animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(MC.EASE).start() // .call { animation: mcEnter .3s ease }
    }

    override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, hh, ow, oh)
        // web: compact = window.innerHeight < 730 (the call fills the window)
        val c = hh / resources.displayMetrics.density < 730f
        if (hh > 0 && c != compact) { compact = c; post { if (!disposed) { build(); bind() } } }
    }

    fun dispose() {
        disposed = true
        h.removeCallbacksAndMessages(null)
        G.stopAll(anims); G.stopAll(qAnims)
        sheet?.close(false); sheet = null
        ciSheet?.close(false); ciSheet = null
    }

    private fun later(ms: Long, f: () -> Unit): Runnable { val r = Runnable { if (!disposed) f() }; h.postDelayed(r, ms); return r }

    private val s get() = sc.session
    private fun partnerName() = s.obj("partner").str("name")
    private fun q() = s.obj("q")
    private fun paused() = pauseLeft > 0

    // ───────── build (once, again only if the height class changes) ─────────
    private fun build() {
        builtCompact = compact
        col.removeAllViews(); progress.clear(); busyViews.clear()
        G.stopAll(anims)
        tagKey = ""; voiceKey = ""; qKey = ""
        val ctx = context
        col.clipChildren = false

        // top: safety · wordmark · call time
        val top = G.hbox(ctx)
        top.pad(14f, 18f, 14f, 0f)
        val glassBox = FrameLayout(ctx)
        val glass = FrameLayout(ctx).apply {
            background = G.round(G.ca("#ffffff", 0.12f), 12f, G.ca("#ffffff", 0.18f), 1f)
            contentDescription = "Safety options"
            addView(G.icon(ctx, "ShieldCheck", Color.WHITE, 18f), LayoutParams(G.dpi(18f), G.dpi(18f), Gravity.CENTER))
        }
        G.click(glass) { openSheet("safety") }
        glassBox.addView(glass, LayoutParams(G.dpi(38f), G.dpi(38f), Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(glassBox, LinearLayout.LayoutParams(G.dpi(44f), G.dpi(38f)))
        val wmBox = FrameLayout(ctx)
        wmBox.addView(MC.wordmark(ctx, true, 14f), LayoutParams(G.WRAP, G.WRAP, Gravity.CENTER))
        top.addView(wmBox, G.lpw(1f))
        val timer = G.hbox(ctx, 6f).apply { background = G.round(G.ca("#ffffff", 0.13f), 99f); pad(10f, 6f, 10f, 6f) }
        val dot = View(ctx).apply { background = G.oval(G.c("#FF5A6E")) }
        timer.addView(dot, LinearLayout.LayoutParams(G.dpi(7f), G.dpi(7f)))
        anims.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { val t = it.animatedValue as Float; dot.alpha = 1f - 0.65f * G.ease(if (t < 0.5f) t * 2 else (1 - t) * 2) }
            start()
        })
        timerT = G.text(ctx, "00:00", MC.SM, Color.WHITE, true).apply { fontFeatureSettings = "tnum" }
        timer.addView(timerT)
        top.addView(timer)
        col.addView(top, G.lp(G.MATCH, G.WRAP))

        // the voice line (.voicebar: centred; min-height 22 incl. its 6 px top padding)
        val vbar = FrameLayout(ctx)
        vbar.pad(14f, 6f, 14f, 0f)
        vbar.minimumHeight = G.dpi(22f)
        voiceDot = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(G.dpi(13f), G.dpi(13f)).m(l = -3f, r = -3f) } // 7 + 3 px ring
        voiceText = G.text(ctx, "", MC.XS, G.ca("#ffffff", 0.86f), false).lineH(1.5f).apply { gravity = Gravity.CENTER }
        voiceRetry = G.text(ctx, "Retry", MC.SM, Color.WHITE, true).apply { pad(6f, 2f, 6f, 2f); visibility = View.GONE }
        G.click(voiceRetry) { sc.voice.retry() }
        voiceRow = packRow(ctx, 8f, listOf(voiceDot, voiceText, voiceRetry), 1)
        voiceRow.clipChildren = false
        vbar.clipChildren = false
        vbar.addView(voiceRow, LayoutParams(G.WRAP, G.WRAP, Gravity.CENTER))
        col.addView(vbar, G.lp(G.MATCH, G.WRAP))

        // round n of 7 · pause · progress
        val roundbar = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        roundbar.pad(20f, if (compact) 8f else 12f, 20f, 0f)
        val pill = G.hbox(ctx, 8f).apply { background = G.round(G.ca("#ffffff", 0.14f), 99f); pad(12f, 5f, 12f, 5f) }
        roundNum = G.text(ctx, "", MC.SM, Color.WHITE, true).lineH(1.5f)
        roundTitle = G.text(ctx, "", MC.SM, Color.WHITE, false).lineH(1.5f).apply { alpha = 0.78f }
        pill.addView(roundNum); pill.addView(roundTitle)
        roundbar.addView(pill, G.lp(G.WRAP, G.WRAP))
        val rrow = G.hbox(ctx, 10f)
        pauseChip = sideChip(ctx, true)
        G.click(pauseChip, 0.97f) { startBreak() }
        rrow.addView(pauseChip, LinearLayout.LayoutParams(G.WRAP, G.dpi(28f)))
        val prog = G.hbox(ctx, 4f)
        for (i in 0 until 7) { val v = View(ctx); progress.add(v); prog.addView(v, G.lpw(1f, G.dpi(4f))) }
        rrow.addView(prog, G.lpw(1f).m(t = if (compact) 8f else 0f))
        rrow.addView(sideChip(ctx, false).apply { visibility = View.INVISIBLE }, LinearLayout.LayoutParams(G.WRAP, G.dpi(28f)))
        roundbar.addView(rrow, G.lp(G.MATCH, G.WRAP).m(t = 10f))
        col.addView(roundbar, G.lp(G.MATCH, G.WRAP))

        // the two players
        val players = G.hbox(ctx, 0f, Gravity.TOP)
        players.pad(12f, if (compact) 12f else 16f, 12f, 0f)
        players.clipChildren = false; players.clipToPadding = false
        val avSize = if (compact) 60f else 78f
        fun player(av: McAvatar, name: TextView, tag: LinearLayout): LinearLayout {
            val p = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
            p.clipChildren = false; p.clipToPadding = false
            val nine = -9f
            p.addView(av, LinearLayout.LayoutParams(av.boxPx(), av.boxPx()).m(nine, nine, nine, nine))
            p.addView(name, G.lp(G.WRAP, G.WRAP).m(t = if (compact) 6f else 8f))
            p.addView(tag, G.lp(G.WRAP, G.WRAP).m(t = if (compact) 6f else 8f))
            return p
        }
        myAv = McAvatar(ctx, avSize, null).also { it.setPhoto(sc.me.str("photo")); it.contentDescription = "You" }
        prAv = McAvatar(ctx, avSize, null).also { it.setPhoto(s.obj("partner").str("photo")) }
        myTag = G.hbox(ctx, 6f); prTag = G.hbox(ctx, 6f)
        prName = G.text(ctx, partnerName(), MC.SM, Color.WHITE, true).lineH(1.5f).apply { gravity = Gravity.CENTER; maxLines = 2 }
        players.addView(player(myAv, G.text(ctx, "You", MC.SM, Color.WHITE, true).lineH(1.5f), myTag), G.lpw(1f))
        wave = Waveform(ctx, 16, true)
        players.addView(wave, LinearLayout.LayoutParams(G.dpi(84f), G.dpi(34f)).m(t = if (compact) 18f else 30f))
        players.addView(player(prAv, prName, prTag), G.lpw(1f))
        col.addView(players, G.lp(G.MATCH, G.WRAP))

        // the question zone (scrolls)
        val qzone = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; clipToPadding = false
            // .qzone { overflow-y: auto }: what doesn't fit scrolls inside, never over the buttons below
            // (the screen's column lets the avatar rings reach outside, so the zone clips itself)
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS; clipToOutline = true
        }
        qcol = G.vbox(ctx, if (compact) 10f else 12f)
        qcol.pad(16f, if (compact) 12f else 16f, 16f, 6f)
        qcol.clipChildren = false; qcol.clipToPadding = false
        qzone.addView(qcol, LayoutParams(G.MATCH, G.WRAP))
        col.addView(qzone, G.lpw(1f, 0).apply { width = G.MATCH; height = 0 })
        qcard = G.vbox(ctx).apply { background = G.round(Color.WHITE, 22f); pad(if (compact) 14f else 16f) }
        G.shadow(qcard, 10f, G.c("#0f0032"), 22f)

        // controls: mute · speaker · report · end
        val controls = G.hbox(ctx, 0f, Gravity.TOP)
        controls.pad(14f, if (compact) 6f else 10f, 14f, if (compact) 10f else 14f)
        val cs = if (compact) 46f else 54f
        fun ctl(icon: String, label: String, end: Boolean, f: () -> Unit): Triple<LinearLayout, FrameLayout, TextView> {
            val b = G.vbox(ctx, 6f, Gravity.CENTER_HORIZONTAL)
            val c = FrameLayout(ctx).apply { background = G.oval(if (end) MC.danger else Color.WHITE) }
            G.shadow(c, 8f, G.c("#0f0032"))
            c.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            c.addView(G.icon(ctx, icon, if (end) Color.WHITE else MC.accentDeep, if (icon == "Flag") 21f else 22f), LayoutParams(G.dpi(if (icon == "Flag") 21f else 22f), G.dpi(if (icon == "Flag") 21f else 22f), Gravity.CENTER))
            b.addView(c, LinearLayout.LayoutParams(G.dpi(cs), G.dpi(cs)))
            val t = G.text(ctx, label, MC.XS, Color.WHITE, false).lineH(1.5f).apply { gravity = Gravity.CENTER }
            b.addView(t)
            G.click(b, 0.97f) { f() }
            controls.addView(b, G.lpw(1f))
            return Triple(b, c, t)
        }
        ctl("Mic", "Mute", false) { sc.voice.toggleMute() }.let { muteBtn = it.first; muteC = it.second; muteT = it.third }
        ctl("Volume1", "Speaker", false) { sc.voice.toggleLoud() }.let { loudC = it.second; loudT = it.third }
        ctl("Flag", "Report", false) { openSheet("report") }
        ctl("PhoneOff", "End", true) { openSheet("end") }
        col.addView(controls, G.lp(G.MATCH, G.WRAP))
    }

    private fun sideChip(ctx: Context, real: Boolean): LinearLayout {
        val c = G.hbox(ctx, 5f, Gravity.CENTER).apply {
            background = G.round(G.ca("#ffffff", 0.1f), 99f, G.ca("#ffffff", 0.22f), 1f); pad(10f, 0f, 10f, 0f)
            minimumWidth = G.dpi(88f)
            contentDescription = if (real) "Pause the game for a minute" else null
        }
        c.addView(G.icon(ctx, "Pause", Color.WHITE, 13f))
        c.addView(G.text(ctx, "Pause", MC.XS, Color.WHITE, true))
        return c
    }

    // ───────── bind: puts the latest server state on screen ─────────
    fun bind() {
        if (disposed) return
        val ss = s ?: return
        // a short "Round n" card each time a round begins
        val round = ss.int("round")
        if (round != 0 && round != seenRound) {
            seenRound = round
            introFor = round
            introT?.let { h.removeCallbacks(it) }
            introT = later(2200) { introFor = 0; bindQ() }
        }
        // shared pause (either player): count down here from what the server said
        val pk = "${ss.long("paused_ms")}|${ss.long("version")}"
        if (pk != pauseKey) {
            pauseKey = pk
            pauseLeft = ceil(ss.long("paused_ms") / 1000.0).toInt()
            pauseT?.let { h.removeCallbacks(it) }; pauseT = null
            if (pauseLeft > 0) countPause()
        }
        // each new question starts a fresh quiet-moment timer
        val qid = q().str("id")
        if (qid != lastQ) { lastQ = qid; sc.voice.markVoice() }

        roundNum.text = "Round $round of ${ss.int("total", 7)}"
        roundTitle.text = "· " + if (round in 1..7) McData.LEVELS[round - 1].title else ""
        for (i in 0 until 7) {
            val n = i + 1
            progress[i].background = G.round(when { n < round -> Color.WHITE; n == round -> G.ca("#ffffff", 0.62f); else -> G.ca("#ffffff", 0.22f) }, 2f)
        }
        prName.text = partnerName()
        val myMood = ss.obj("mine").obj("mood").str("mood", "neutral")
        val prMood = ss.obj("partner").obj("mood").str("mood", "neutral")
        val tk = "$myMood|$prMood|${ss.obj("partner").str("photo")}"
        if (tk != tagKey) {
            tagKey = tk
            myAv.setRing(G.c(McData.moodOf(myMood).tint)); prAv.setRing(G.c(McData.moodOf(prMood).tint))
            prAv.setPhoto(ss.obj("partner").str("photo")); prAv.contentDescription = partnerName()
            moodTag(myTag, myMood, "Your mood"); moodTag(prTag, prMood, "Their mood")
        }
        bindVoice()
        bindQ()
        bindBusy()
        // the check-in sheet (asked by the server through the parent)
        val ci = sc.checkin
        if (ci != null && ci != ciShown && CHECKINS.containsKey(ci)) showCheckin(ci)
        if (ci == null && ciShown != null) { ciShown = null; ciSheet?.close(); ciSheet = null }
        // the report sheet follows the parent's "sending"
        reportBtn?.let { it.label = if (sc.reporting) "Sending…" else "Submit report and end call"; it.isEnabled = reportReason != null && !sc.reporting }
    }

    private fun moodTag(box: LinearLayout, mood: String, k: String) {
        val ctx = context
        box.removeAllViews()
        box.background = G.round(Color.WHITE, 12f)
        box.pad(6f, 5f, 10f, 5f)
        box.addView(MC.face(ctx, mood, 20f))
        val c = G.vbox(ctx)
        c.addView(G.text(ctx, k, 9.5f, MC.inkSoft, false).lineH(1.15f))
        c.addView(G.text(ctx, McData.moodOf(mood).label, MC.SM, MC.ink, true).lineH(1.15f))
        box.addView(c)
    }

    fun bindVoice() {
        if (disposed) return
        val v = sc.voice
        val pn = partnerName()
        var text: String; var warn = false
        when (v.state) {
            "live" -> when {
                v.reconnecting -> { text = "Reconnecting…"; warn = true }
                !v.partnerIn -> { text = "Waiting for $pn to join the call…"; warn = true }
                else -> text = "Voice call connected"
            }
            "unavailable", "error" -> { text = v.error; warn = true }
            else -> text = "Connecting the voice call…"
        }
        val key = "$text|$warn|${v.state}|${v.muted}|${v.loud}|${v.speakingMe}|${v.speakingPartner}"
        if (key == voiceKey) return
        voiceKey = key
        voiceText.text = text
        voiceText.setTextColor(if (warn) G.c("#FFD9A8") else G.ca("#ffffff", 0.86f))
        voiceDot.removeAllViews()
        val dc = if (warn) "#FFB547" else "#4ADE80"
        voiceDot.addView(View(context).apply { background = G.oval(G.ca(dc, 0.22f)) }, LayoutParams(G.MATCH, G.MATCH))
        voiceDot.addView(View(context).apply { background = G.oval(G.c(dc)) }, LayoutParams(G.dpi(7f), G.dpi(7f), Gravity.CENTER))
        voiceRetry.visibility = if (v.state == "error") View.VISIBLE else View.GONE
        // speaking rings, the waveform, the mute badge
        myAv.setSpeaking(v.speakingMe); prAv.setSpeaking(v.speakingPartner); myAv.setMuted(v.muted)
        wave.active = v.speakingMe || v.speakingPartner
        // mute (only while the call is live) and speaker
        muteBtn.isEnabled = v.state == "live"
        paintCtl(muteC, if (v.muted) "MicOff" else "Mic", v.muted, 22f); muteT.text = if (v.muted) "Unmute" else "Mute"
        muteBtn.contentDescription = if (v.muted) "Unmute" else "Mute"
        paintCtl(loudC, if (v.loud) "Volume2" else "Volume1", v.loud, 22f); loudT.text = if (v.loud) "Loud" else "Speaker"
        (loudC.parent as? View)?.contentDescription = if (v.loud) "Speaker: loud" else "Speaker: normal"
    }

    private fun paintCtl(c: FrameLayout, icon: String, pressed: Boolean, size: Float) {
        c.background = G.oval(if (pressed) G.c("#24074F") else Color.WHITE)
        c.removeAllViews()
        c.addView(G.icon(context, icon, if (pressed) Color.WHITE else MC.accentDeep, size), LayoutParams(G.dpi(size), G.dpi(size), Gravity.CENTER))
    }

    private fun bindBusy() {
        pauseChip.isEnabled = !(paused() || busy)
        pauseChip.alpha = if (pauseChip.isEnabled) 1f else 0.5f
        for (v in busyViews) v.isEnabled = !busy
    }

    // ───────── the question card (rebuilt only when what it shows changes) ─────────
    private fun bindQ() {
        if (disposed) return
        val ss = s ?: return
        val q = q()
        val showQ = ss.str("phase") == "question" && q != null && introFor == 0 && !paused()
        val key = listOf(introFor, ss.str("phase"), paused(), showQ, q.str("id"), sc.lang, ss.str("reaction"), sc.isHost, ss.bool("is_last"), ss.int("turn"),
            ss.str("next_ask"), ss.str("lighter_by"), ss.bool("lighten"), ss.str("pause_by"), partnerName(), ss.obj("plan").str("title_en"), compact,
            q.str("ans"), ss.obj("mine").obj("mood").str("mood"), ss.obj("partner").obj("mood").str("mood")).joinToString("§")
        if (key == qKey) { breakBig?.text = clock(pauseLeft); return }
        qKey = key
        G.stopAll(qAnims)
        busyViews.clear(); breakBig = null
        qcol.removeAllViews()
        qcard.removeAllViews()
        qcol.addView(qcard, G.lp(G.MATCH, G.WRAP))
        val ctx = context
        val pn = partnerName()
        when {
            introFor > 0 -> roundIntro(introFor, ss.obj("plan"))
            ss.str("phase") == "mindful" && !paused() -> mindful()
            paused() -> breakCard(ss)
        }
        if (showQ) question(ss, q!!)
        val isHost = sc.isHost
        if (showQ && isHost && !compact) {
            val nb = MC.Btn(ctx, "white", nextLabel(ss), iconRight = if (ss.bool("is_last")) "Check" else "ArrowRight") { onNext() }
            nb.minimumWidth = G.dpi(210f)
            busyViews.add(nb)
            val row = FrameLayout(ctx)
            row.addView(nb, LayoutParams(G.WRAP, G.dpi(50f), Gravity.CENTER_HORIZONTAL))
            qcol.addView(row, G.lp(G.MATCH, G.WRAP))
            nextBtnLabel = nb
        } else nextBtnLabel = null
        if (showQ && !isHost) {
            val w = G.hbox(ctx, 10f, Gravity.CENTER).apply { alpha = 0.92f }
            w.addView(TfSpinner(ctx, 14f, white = true), LinearLayout.LayoutParams(G.dpi(14f), G.dpi(14f)))
            w.addView(G.text(ctx, "${hostLabel(ss)} · when $pn taps Next", MC.SM, Color.WHITE, false).lineH(1.5f), LinearLayout.LayoutParams(G.WRAP, G.WRAP).apply { weight = 0f })
            qcol.addView(w, G.lp(G.MATCH, G.WRAP))
        }
        if (ss.str("lighter_by") == "partner" && isHost && ss.bool("lighten") && showQ) {
            val w = G.hbox(ctx, 10f, Gravity.CENTER).apply { alpha = 0.92f }
            w.addView(G.icon(ctx, "Info", Color.WHITE, 14f))
            w.addView(G.text(ctx, "$pn would like lighter questions.", MC.SM, Color.WHITE, false).lineH(1.5f))
            qcol.addView(w, G.lp(G.MATCH, G.WRAP))
        }
        bindBusy()
    }
    private var nextBtnLabel: MC.Btn? = null

    private fun nextLabel(ss: JSONObject): String {
        val pn = partnerName()
        return if (ss.bool("is_last")) "Finish game" else if (ss.int("turn") == 0) (if (ss.str("next_ask") == "me") "Next: you ask $pn" else "Next: $pn asks you") else "Next round"
    }
    private fun hostLabel(ss: JSONObject): String {
        val pn = partnerName()
        return if (ss.bool("is_last")) "$pn will finish the game" else if (ss.int("turn") == 0) (if (ss.str("next_ask") == "me") "Next, you ask $pn" else "Next, $pn asks you") else "Next round"
    }

    private fun riK(ctx: Context, s: String): TextView =
        G.text(ctx, s.uppercase(), MC.XS, MC.accent, true).lineH(1.5f).apply { letterSpacing = 0.07f; gravity = Gravity.CENTER }

    private fun roundIntro(n: Int, plan: JSONObject?) {
        val ctx = context
        val L = McData.LEVELS[(n - 1).coerceIn(0, 6)]
        val box = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        if (compact) box.pad(4f, 8f, 4f, 4f) else box.pad(6f, 18f, 6f, 10f)
        val icS = if (compact) 46f else 56f
        box.addView(FrameLayout(ctx).apply {
            background = G.round(MC.accentTint, 18f)
            addView(G.icon(ctx, ROUND_ICON[n] ?: "Smile", MC.accent, 26f), LayoutParams(G.dpi(26f), G.dpi(26f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(G.dpi(icS), G.dpi(icS)).m(b = if (compact) 8f else 12f))
        box.addView(riK(ctx, "Round $n of 7"))
        box.addView(G.text(ctx, L.title, 21f, MC.ink, true).lineH(1.5f).apply { letterSpacing = -0.01f; gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 4f, b = 2f))
        box.addView(G.text(ctx, L.blurb, MC.SM, MC.inkSoft, false).lineH(1.5f).apply { gravity = Gravity.CENTER })
        // depth meter
        val depth = G.vbox(ctx, 6f, Gravity.CENTER_HORIZONTAL)
        depth.contentDescription = "Round $n of 7, ${McData.STAGE[n - 1].lowercase()}"
        val bars = G.hbox(ctx, 4f, Gravity.BOTTOM)
        for (l in McData.LEVELS) {
            val c = when { l.n == n -> MC.accent; l.n < n -> G.c("#CDB2F7"); else -> G.c("#EADFFB") }
            bars.addView(View(ctx).apply { background = G.round(c, 3f) }, LinearLayout.LayoutParams(G.dpi(10f), G.dpi(6f + l.depth * 5f)))
        }
        depth.addView(bars, LinearLayout.LayoutParams(G.WRAP, G.dpi(26f)))
        depth.addView(G.text(ctx, McData.STAGE[n - 1], MC.XS, MC.inkSoft, false).lineH(1.5f).apply { gravity = Gravity.CENTER })
        box.addView(depth, G.lp(G.WRAP, G.WRAP).m(t = 14f))
        if (n == 1) {
            if (plan != null) box.addView(sc.styleChip(ctx, "Today: ${plan.str("title_en")}"), G.lp(G.WRAP, G.WRAP).m(t = 14f))
        } else {
            val cheer = G.hbox(ctx, 6f, Gravity.CENTER)
            cheer.addView(G.icon(ctx, "Check", MC.ok, 15f, 2.6f))
            cheer.addView(G.text(ctx, McData.CHEERS[n - 1], MC.SM, MC.ok, false).lineH(1.5f))
            box.addView(cheer, G.lp(G.WRAP, G.WRAP).m(t = 14f))
        }
        qcard.addView(box, G.lp(G.MATCH, G.WRAP))
    }

    /** "A moment together": breathe in 4 s · hold 2 s · out 4 s, twice; then the boy's phone moves on. */
    private fun mindful() {
        val ctx = context
        val isHost = sc.isHost
        val box = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        box.pad(0f, 8f, 0f, 4f)
        box.addView(riK(ctx, "A moment together"))
        val bs = if (compact) 92f else 116f
        val circle = View(ctx).apply { background = CssBg.ovalOf(listOf(CssBg.Radial(0.5f, 0.42f, 1f, 1f, intArrayOf(G.c("#EFE4FF"), G.c("#CDB4FA")), null, true))) }
        // web: the circle starts with the "in" class already on, so it begins full size (no grow on the first breath)
        circle.scaleX = 1f; circle.scaleY = 1f
        box.addView(circle, LinearLayout.LayoutParams(G.dpi(bs), G.dpi(bs)).m(t = if (compact) 8f else 12f, b = if (compact) 8f else 10f))
        val label = G.text(ctx, "Breathe in…", MC.LG, MC.ink, true).lineH(1.5f).apply { gravity = Gravity.CENTER }
        box.addView(label)
        val small = MC.small(ctx, "Breathe together before going deeper. 1 of 2", true)
        box.addView(small, G.lp(G.MATCH, G.WRAP))
        if (isHost) {
            val skip = MC.Btn(ctx, "ghost", "Skip", small = true) { run("mindful_done") }
            val row = FrameLayout(ctx); row.addView(skip, LayoutParams(G.WRAP, G.dpi(38f), Gravity.CENTER_HORIZONTAL))
            box.addView(row, G.lp(G.MATCH, G.WRAP).m(t = 8f))
        } else box.addView(MC.small(ctx, "${partnerName()} can skip this.", true), G.lp(G.MATCH, G.WRAP).m(t = 8f))
        qcard.addView(box, G.lp(G.MATCH, G.WRAP))
        // 0–4 s in, 4–6 hold, 6–10 out; again 10–20; then done (boy)
        fun grow(to: Float) { circle.animate().scaleX(to).scaleY(to).setDuration(4000).setInterpolator(EASE_IN_OUT).start() }
        val steps = listOf(
            0L to { small.text = "Breathe together before going deeper. 1 of 2"; label.text = "Breathe in…"; grow(1f) },
            4000L to { label.text = "Hold…"; grow(1f) },
            6000L to { label.text = "Breathe out…"; grow(0.72f) },
            10000L to { small.text = "Breathe together before going deeper. 2 of 2"; label.text = "Breathe in…"; grow(1f) },
            14000L to { label.text = "Hold…"; grow(1f) },
            16000L to { label.text = "Breathe out…"; grow(0.72f) },
            20000L to { if (isHost) run("mindful_done") },
        )
        val token = qKey
        for ((at, f) in steps) later(at) { if (qKey == token) f() }
    }

    private fun clock(n: Int) = "${n / 60}:${(n % 60).toString().padStart(2, '0')}"

    private fun breakCard(ss: JSONObject) {
        val ctx = context
        val box = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        box.pad(4f, 14f, 4f, 6f)
        box.addView(riK(ctx, "Game paused" + if (ss.str("pause_by") == "partner") " by ${partnerName()}" else ""))
        val big = G.text(ctx, clock(pauseLeft), 34f, MC.ink, true).apply { letterSpacing = -0.02f; fontFeatureSettings = "tnum"; gravity = Gravity.CENTER }
        breakBig = big
        box.addView(big, G.lp(G.WRAP, G.WRAP).m(t = 6f, b = 6f))
        box.addView(MC.small(ctx, "The call stays connected. Resume whenever you’re both ready.", true), G.lp(G.MATCH, G.WRAP))
        val rb = MC.Btn(ctx, "primary", "Resume now", small = true) { run("resume") }
        busyViews.add(rb)
        val row = FrameLayout(ctx); row.addView(rb, LayoutParams(G.WRAP, G.dpi(38f), Gravity.CENTER_HORIZONTAL))
        box.addView(row, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        qcard.addView(box, G.lp(G.MATCH, G.WRAP))
    }

    private fun boldName(sb: SpannableStringBuilder, s: String, color: Int) {
        val a = sb.length; sb.append(s)
        sb.setSpan(FontSpan(app.truefind.android.Ui.bold), a, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(color), a, sb.length, 0)
    }

    private fun question(ss: JSONObject, q: JSONObject) {
        val ctx = context
        val pn = partnerName()
        val hi = sc.lang == "hi"
        val iAsk = q.str("ask") == "me"
        val ansMood = if (q.str("ans") == "me") ss.obj("mine").obj("mood").str("mood", "neutral") else ss.obj("partner").obj("mood").str("mood", "neutral")
        // Q n · category · हिं / EN
        val head = G.hbox(ctx, 8f)
        head.addView(G.text(ctx, "Q${q.int("n")}", MC.XS, MC.accent, true).apply { background = G.round(MC.accentTint, 8f); pad(8f, 3f, 8f, 3f); fontFeatureSettings = "tnum" })
        head.addView(G.text(ctx, q.str("cat"), MC.XS, MC.inkSoft, false).lineH(1.5f), G.lpw(1f))
        val lt = G.hbox(ctx, 0f).apply { background = G.round(Color.WHITE, 99f, MC.line, 1f); pad(2f) }
        lt.contentDescription = "Question language"
        for ((code, label) in listOf("hi" to "हिं", "en" to "EN")) {
            val on = sc.lang == code
            val b = G.text(ctx, label, MC.XS, if (on) Color.WHITE else MC.inkSoft, true).apply {
                pad(9f, 3f, 9f, 3f); gravity = Gravity.CENTER
                background = if (on) G.round(MC.accent, 99f) else null
            }
            G.click(b, 0.97f) { if (sc.lang != code) { sc.setLang(code); bindQ() } }
            lt.addView(b)
        }
        head.addView(lt)
        qcard.addView(head, G.lp(G.MATCH, G.WRAP))
        // who asks whom (and how the one answering feels)
        val fw = G.hbox(ctx, 8f)
        fw.background = if (iAsk) G.round(MC.accentTint, 12f, G.c("#DCC7FA"), 1f) else G.round(G.c("#FBF8FF"), 12f, G.c("#EEE3FD"), 1f)
        if (compact) fw.pad(8f, 6f, 8f, 6f) else fw.pad(10f, 8f, 10f, 8f)
        fw.addView(AvatarView(ctx, 22f, Color.TRANSPARENT, 0f, G.c("#EDE2FF")).also { it.setPhoto(if (iAsk) sc.me.str("photo") else s.obj("partner").str("photo")) }, LinearLayout.LayoutParams(G.dpi(22f), G.dpi(22f)))
        val who = SpannableStringBuilder(); boldName(who, if (iAsk) "You ask $pn" else "$pn asks you", MC.accentDeep)
        fw.addView(G.text(ctx, who, MC.SM, MC.inkSoft, false).lineH(1.5f), G.lpw(1f))
        val fm = G.hbox(ctx, 5f).apply { background = G.round(Color.WHITE, 99f, MC.line, 1f); pad(3f, 2f, 8f, 2f) }
        fm.addView(MC.face(ctx, ansMood, 18f))
        fm.addView(G.text(ctx, McData.moodOf(ansMood).label, MC.XS, MC.ink, true).lineH(1.5f))
        fm.contentDescription = (if (q.str("ans") == "me") "You feel" else "$pn feels") + " " + McData.moodOf(ansMood).label
        fw.addView(fm)
        qcard.addView(fw, G.lp(G.MATCH, G.WRAP).m(t = if (compact) 8f else 12f))
        // the question (only the one asking sees it)
        fun qtext(text: String, color: Int): TextView {
            val size = if (compact) (if (hi) 17f else 16.5f) else (if (hi) 19f else 18f)
            val lh = if (hi) (if (compact) 1.55f else 1.62f) else 1.5f
            val t = G.text(ctx, text, size, color, true).lineH(lh).apply { letterSpacing = if (hi) 0f else -0.005f }
            t.alpha = 0.35f; t.translationY = G.dp(6f)
            t.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(MC.EASE).start() // .qtext { animation: mcEnter .3s ease }
            return t
        }
        val m = if (compact) 8f else 12f
        if (q.bool("hidden")) {
            qcard.addView(qtext(if (hi) "$pn आपसे एक सवाल पूछेंगे — ध्यान से सुनिए।" else "$pn will ask you a question — just listen.", G.c("#5A4E78")), G.lp(G.MATCH, G.WRAP).m(t = m, b = m))
            qcard.addView(turnLine(ctx, "Ear", SpannableStringBuilder(if (hi) "अपने शब्दों में जवाब दीजिए। उतना ही बताइए जितना सहज लगे — यहाँ कोई जवाब ग़लत नहीं।" else "Answer in your own words. Share only what feels right — there are no wrong answers.")), G.lp(G.MATCH, G.WRAP))
        } else {
            qcard.addView(qtext(if (hi) q.obj("text").str("hi") else q.obj("text").str("en"), MC.ink), G.lp(G.MATCH, G.WRAP).m(t = m, b = m))
            val tsb = SpannableStringBuilder()
            if (hi) { tsb.append("यह सवाल सिर्फ आपको दिख रहा है। अपने अंदाज़ में $pn से पूछिए — जवाब "); boldName(tsb, pn, MC.ink); tsb.append(" देंगे।") }
            else { tsb.append("Only you can see this. Ask $pn in your own words — "); boldName(tsb, pn, MC.ink); tsb.append(" answers.") }
            qcard.addView(turnLine(ctx, "MessageCircleQuestion", tsb), G.lp(G.MATCH, G.WRAP))
            val coach = q.obj("coach")
            val tip = q.obj("tip")
            if (coach != null) {
                val cb = G.vbox(ctx).apply { background = G.round(G.c("#F3ECFF"), 14f, G.c("#E2D4FB"), 1f); pad(13f, 11f, 13f, 11f) }
                val k = G.hbox(ctx, 6f)
                k.addView(G.icon(ctx, "Sparkles", MC.accent, 15f))
                k.addView(G.text(ctx, if (hi) "$pn का मूड अच्छा करने के लिए" else "To lift $pn’s mood", MC.XS, G.c("#6A3FC0"), true).lineH(1.5f), G.lpw(1f))
                k.addView(G.text(ctx, if (hi) coach.obj("name").str("hi") else coach.obj("name").str("en"), MC.XS, G.c("#5B00BF"), true).lineH(1.5f).apply { background = G.round(Color.WHITE, 99f); pad(8f, 2f, 8f, 2f) })
                cb.addView(k, G.lp(G.MATCH, G.WRAP))
                cb.addView(G.text(ctx, if (hi) coach.obj("how").str("hi") else coach.obj("how").str("en"), MC.SM, G.c("#3B2D5C"), false).lineH(if (hi) 1.65f else 1.45f), G.lp(G.MATCH, G.WRAP).m(t = 6f))
                qcard.addView(cb, G.lp(G.MATCH, G.WRAP).m(t = 12f))
            } else if (tip != null) {
                val tb = G.hbox(ctx, 8f, Gravity.TOP).apply { background = G.round(MC.wash, 12f); if (compact) pad(10f, 8f, 10f, 8f) else pad(12f, 10f, 12f, 10f) }
                tb.addView(G.icon(ctx, "Sparkles", MC.accent, 15f), LinearLayout.LayoutParams(G.dpi(15f), G.dpi(15f)).m(t = 2f))
                tb.addView(G.text(ctx, if (hi) tip.str("hi") else tip.str("en"), MC.SM, G.c("#463C61"), false).lineH(if (hi) 1.6f else 1.5f), G.lpw(1f))
                qcard.addView(tb, G.lp(G.MATCH, G.WRAP).m(t = if (compact) 8f else 12f))
            }
        }
        // 👍 👎 · Skip · Change topic (· Next)  |  or "X moves the questions"
        val foot = G.hbox(ctx, if (compact) 4f else 6f)
        foot.background = MC.topLine(Color.WHITE)
        foot.pad(0f, if (compact) 8f else 12f, 0f, 0f)
        if (!q.bool("hidden")) {
            for ((kind, icon) in listOf("up" to "ThumbsUp", "down" to "ThumbsDown")) {
                val on = ss.str("reaction") == kind
                val r = FrameLayout(ctx).apply {
                    background = if (on) G.round(MC.accentTint, 12f, G.c("#CDB2F7"), 1f) else G.round(Color.WHITE, 12f, MC.line, 1f)
                    contentDescription = if (kind == "up") "Good question" else "Not for me"
                    addView(G.icon(ctx, icon, if (on) MC.accentDeep else MC.inkSoft, 16f), LayoutParams(G.dpi(16f), G.dpi(16f), Gravity.CENTER))
                }
                G.click(r, 0.97f) { onReact(kind) }
                foot.addView(r, LinearLayout.LayoutParams(G.dpi(36f), G.dpi(36f)))
            }
        }
        foot.addView(View(ctx), G.lpw(1f, 1))
        if (sc.isHost) {
            foot.addView(textBtn(ctx, "SkipForward", "Skip") { onSkip() })
            foot.addView(textBtn(ctx, "Shuffle", if (compact) "Topic" else "Change topic") { openSheet("topic") })
            if (compact) {
                val last = ss.bool("is_last")
                val nb = MC.Btn(ctx, "primary", if (last) "Finish" else if (ss.int("turn") == 0) "Next turn" else "Next", iconRight = if (last) "Check" else "ArrowRight", small = true) { onNext() }
                nb.pad(12f, 0f, 12f, 0f)
                busyViews.add(nb)
                foot.addView(nb, LinearLayout.LayoutParams(G.WRAP, G.dpi(38f)).m(l = 2f))
            }
        } else {
            val hn = G.hbox(ctx, 6f).apply { background = G.round(MC.accentTint, 99f); pad(10f, 6f, 10f, 6f) }
            hn.addView(G.icon(ctx, "Crown", MC.accentDeep, 13f))
            hn.addView(G.text(ctx, "$pn moves the questions", MC.XS, MC.accentDeep, true))
            foot.addView(hn)
        }
        qcard.addView(foot, G.lp(G.MATCH, G.WRAP).m(t = if (compact) 8f else 12f))
    }

    private fun turnLine(ctx: Context, icon: String, text: CharSequence): View {
        val t = G.hbox(ctx, 8f)
        t.addView(G.icon(ctx, icon, MC.accent, 16f))
        t.addView(G.text(ctx, text, MC.SM, MC.inkSoft, false).lineH(1.5f), G.lpw(1f))
        return t
    }

    private fun textBtn(ctx: Context, icon: String, label: String, f: () -> Unit): View {
        val b = G.hbox(ctx, if (compact) 4f else 6f).apply { if (compact) pad(6f, 8f, 6f, 8f) else pad(10f, 8f, 10f, 8f) }
        b.addView(G.icon(ctx, icon, MC.accentDeep, 15f))
        b.addView(G.text(ctx, label, MC.SM, MC.accentDeep, true))
        G.click(b, 0.97f) { f() }
        busyViews.add(b)
        return b
    }

    // ───────── actions (the web's run / onNext / onSkip …) ─────────
    private fun run(action: String, extra: JSONObject = JSONObject(), done: (JSONObject?) -> Unit = {}) {
        if (busy) { done(null); return }
        busy = true; bindBusy(); nextBtnLabel?.label = "…"
        sc.act(action, extra) { r ->
            busy = false
            if (!disposed) { bindBusy(); s?.let { nextBtnLabel?.label = nextLabel(it) } }
            done(r)
        }
    }
    private fun qid() = q().str("id")
    private fun onNext() = run("next", JSONObject().put("q_id", qid()))
    private fun onSkip() = run("skip", JSONObject().put("q_id", qid())) { r -> val k = r.str("suggest_checkin"); if (k.isNotEmpty()) sc.claimCheckin(k) }
    private fun onReact(kind: String) {
        val q = q() ?: return
        if (s.str("reaction") == kind) return
        sc.act("react", JSONObject().put("q_id", q.str("id")).put("kind", kind)) { r -> val k = r.str("suggest_checkin"); if (k.isNotEmpty()) sc.claimCheckin(k) }
    }
    private fun pickTopic(cat: String) { closeSheet(); run("topic", JSONObject().put("q_id", qid()).put("category", cat)) }
    private fun lighter() {
        closeSheet(); sc.checkin = null; syncCheckin()
        run("lighter") { if (!sc.isHost) sc.toast("${partnerName()} will see that you’d like lighter questions.") }
    }
    private fun startBreak() { closeSheet(); sc.checkin = null; syncCheckin(); run("pause") }
    private fun checkinAction(a: String) {
        when (a) {
            "topic" -> { sc.checkin = null; syncCheckin(); openSheet("topic") }
            "lighter" -> lighter()
            "break" -> startBreak()
            else -> { sc.checkin = null; syncCheckin() }
        }
    }
    private fun syncCheckin() { if (sc.checkin == null && ciShown != null) { ciShown = null; ciSheet?.close(false); ciSheet = null } }

    private fun countPause() {
        pauseT = later(1000) {
            pauseLeft = max(0, pauseLeft - 1)
            if (pauseLeft > 0) { breakBig?.text = clock(pauseLeft); countPause() } else { pauseT = null; bindQ(); bindBusy() }
        }
    }

    private fun tickClock() {
        val e = max(0L, floor((System.currentTimeMillis() - sc.callStartLocal) / 1000.0).toLong()).toInt()
        timerT.text = McData.fmtClock(e)
        timerT.contentDescription = "Call time ${McData.fmtClock(e)}"
        later(1000) { tickClock() }
    }

    /** "A quiet moment": nobody has said anything for 90 s on the same question (real audio levels). */
    private fun silenceLoop() {
        later(3000) {
            val ss = s
            val v = sc.voice
            val skip = silenceAsked || ss == null || ss.obj("mine").str("checkin").isNotEmpty() || ss.obj("mine").bool("checkin") || (sheet?.isOpen == true) ||
                sc.checkin != null || paused() || introFor != 0 || ss.str("phase") != "question" || v.state != "live" || !v.partnerIn
            if (!skip && v.silentFor() > 90_000) { silenceAsked = true; sc.claimCheckin("silence") }
            silenceLoop()
        }
    }

    // ───────── sheets ─────────
    private fun closeSheet() { sheet?.close(false); sheet = null; sheetType = ""; reportBtn = null; reportOpts = null }

    private fun opt(ctx: Context, icon: String, text: String, small: String? = null, danger: Boolean = false, f: () -> Unit): View =
        MC.opt(ctx, text, icon, small = small, danger = danger) { f() }

    private fun openSheet(type: String) {
        closeSheet()
        val ctx = context
        val pn = partnerName()
        val title = when (type) {
            "topic" -> "Change the topic"; "discomfort" -> "Your comfort comes first"; "safety" -> "Safety"; "rules" -> "Game guidelines"
            "support" -> "Talk to someone"; "report" -> "Report $pn"; "end" -> "End the call?"; else -> null
        }
        lateinit var sh: Sheet
        sh = MC.sheet(this, title) { if (sheet === sh) { sheet = null; sheetType = ""; reportBtn = null; reportOpts = null } }
        sheet = sh; sheetType = type
        val p = sh.panel
        fun para(t: String) = p.addView(MC.sheetP(ctx, t), G.lp(G.MATCH, G.WRAP).m(b = 14f))
        fun opts(): LinearLayout = G.vbox(ctx, 8f).also { p.addView(it, G.lp(G.MATCH, G.WRAP)) }
        fun foot(): LinearLayout = G.vbox(ctx, 8f).also { p.addView(it, G.lp(G.MATCH, G.WRAP).m(t = 14f)) }
        when (type) {
            "topic" -> {
                para("Pick something you’d both enjoy talking about.")
                val grid = G.vbox(ctx, 8f)
                McData.TOPICS.chunked(3).forEach { row ->
                    val r = G.hbox(ctx, 8f, Gravity.TOP)
                    for ((k, label) in row) {
                        val b = G.text(ctx, label, MC.SM, MC.ink, false).lineH(1.5f).apply {
                            gravity = Gravity.CENTER; background = G.round(Color.WHITE, 12f, MC.line, 1f); pad(4f, 12f, 4f, 12f)
                        }
                        G.click(b, 0.97f) { pickTopic(k) }
                        r.addView(b, G.lpw(1f))
                    }
                    grid.addView(r, G.lp(G.MATCH, G.WRAP))
                }
                p.addView(grid, G.lp(G.MATCH, G.WRAP))
                val f = foot()
                f.addView(opt(ctx, "PartyPopper", "Something lighter") { lighter() }, G.lp(G.MATCH, G.WRAP))
                f.addView(opt(ctx, "LifeBuoy", "I feel uncomfortable") { openSheet("discomfort") }, G.lp(G.MATCH, G.WRAP))
            }
            "discomfort" -> {
                para("Choose what feels right. $pn will only see that the questions changed or the call ended.")
                val o = opts()
                if (sc.isHost) o.addView(opt(ctx, "Shuffle", "Change the topic") { openSheet("topic") }, G.lp(G.MATCH, G.WRAP))
                else o.addView(opt(ctx, "PartyPopper", "Ask for lighter questions") { lighter() }, G.lp(G.MATCH, G.WRAP))
                o.addView(opt(ctx, "Coffee", "Pause for a minute") { startBreak() }, G.lp(G.MATCH, G.WRAP))
                o.addView(opt(ctx, "PhoneOff", "End the call") { closeSheet(); sc.endCall("discomfort") }, G.lp(G.MATCH, G.WRAP))
                o.addView(opt(ctx, "Flag", "Report and block", danger = true) { openSheet("report") }, G.lp(G.MATCH, G.WRAP))
            }
            "safety" -> {
                val o = opts()
                o.addView(opt(ctx, "LifeBuoy", "I feel uncomfortable", "Lighter questions, pause or leave") { openSheet("discomfort") }, G.lp(G.MATCH, G.WRAP))
                o.addView(opt(ctx, "HeartHandshake", "Talk to someone", "Tele-MANAS · free, 24×7") { openSheet("support") }, G.lp(G.MATCH, G.WRAP))
                o.addView(opt(ctx, "Info", "Game guidelines") { openSheet("rules") }, G.lp(G.MATCH, G.WRAP))
                o.addView(opt(ctx, "Flag", "Report $pn", danger = true) { openSheet("report") }, G.lp(G.MATCH, G.WRAP))
            }
            "rules" -> {
                p.addView(MC.rulesList(ctx), G.lp(G.MATCH, G.WRAP).m(t = 6f, b = 16f))
                p.addView(MC.Btn(ctx, "secondary", "Back to the call") { closeSheet() }, G.lp(G.MATCH, G.dpi(50f)))
            }
            "support" -> {
                para("Tele-MANAS is a free, private helpline, open 24×7. Your call with $pn stays on.")
                val f = foot()
                f.addView(MC.Btn(ctx, "primary", "Call Tele-MANAS (14416)", icon = "HeartHandshake") { sc.dial() }, G.lp(G.MATCH, G.dpi(50f)))
                f.addView(MC.Btn(ctx, "secondary", "Back to the call") { closeSheet() }, G.lp(G.MATCH, G.dpi(50f)))
            }
            "report" -> {
                reportReason = null
                para("Reports are private and reviewed by the True Find safety team. $pn will be blocked, so you won’t be matched again.")
                val o = opts()
                reportOpts = o
                fun fill() {
                    o.removeAllViews()
                    for (r in McData.REPORT_REASONS) o.addView(MC.opt(ctx, r, null, radio = reportReason == r) { reportReason = r; fill(); bindReport() }, G.lp(G.MATCH, G.WRAP))
                }
                fill()
                val f = foot()
                val rb = MC.Btn(ctx, "danger", "Submit report and end call", icon = "Flag") { val r = reportReason; if (r != null && !sc.reporting) sc.onReport(r) }
                reportBtn = rb
                f.addView(rb, G.lp(G.MATCH, G.dpi(50f)))
                f.addView(MC.Btn(ctx, "ghost", "Cancel") { closeSheet() }, G.lp(G.MATCH, G.dpi(50f)))
                bindReport()
            }
            "end" -> {
                para("You can still tell us how you feel afterwards.")
                val f = foot()
                f.addView(MC.Btn(ctx, "danger", "End call", icon = "PhoneOff") { closeSheet(); sc.endCall("ended_early") }, G.lp(G.MATCH, G.dpi(50f)))
                f.addView(MC.Btn(ctx, "secondary", "Keep talking") { closeSheet() }, G.lp(G.MATCH, G.dpi(50f)))
            }
        }
    }
    private fun bindReport() { reportBtn?.let { it.label = if (sc.reporting) "Sending…" else "Submit report and end call"; it.isEnabled = reportReason != null && !sc.reporting } }

    private fun showCheckin(kind: String) {
        val (title, body) = CHECKINS[kind] ?: return
        ciShown = kind
        ciSheet?.close(false)
        val ctx = context
        lateinit var sh: Sheet
        sh = MC.sheet(this, title) { if (ciSheet === sh) { ciSheet = null; if (ciShown == kind && sc.checkin == kind) checkinAction("close") } }
        ciSheet = sh
        sh.panel.addView(MC.sheetP(ctx, body), G.lp(G.MATCH, G.WRAP).m(b = 14f))
        val o = G.vbox(ctx, 8f)
        val isHost = sc.isHost
        val list: List<Triple<String, String, String>> = when (kind) {
            "skips" -> listOf(Triple("topic", "Shuffle", "Pick a topic"), Triple("lighter", "PartyPopper", "Lighter, fun question"), Triple("close", "ArrowRight", "Keep going"))
            "dislikes" -> if (isHost) listOf(Triple("close", "Check", "Sounds good"), Triple("topic", "Shuffle", "Pick a topic instead")) else listOf(Triple("close", "Check", "Sounds good"))
            else -> listOf(Triple("lighter", "Sparkles", if (isHost) "Try an easier question" else "Ask for easier questions"), Triple("break", "Coffee", "Take a 1-minute break"), Triple("close", "Check", "We’re fine"))
        }
        for ((a, ic, label) in list) o.addView(MC.opt(ctx, label, ic) { checkinAction(a) }, G.lp(G.MATCH, G.WRAP))
        sh.panel.addView(o, G.lp(G.MATCH, G.WRAP))
    }

    /** Phone back: a sheet closes first (web: the sheet's own close). */
    fun onBack(): Boolean {
        if (sheet?.isOpen == true) { closeSheet(); return true }
        if (ciSheet?.isOpen == true) { checkinAction("close"); return true }
        return false
    }
}
