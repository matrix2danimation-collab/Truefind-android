package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.truefind.android.games.GameVoice
import org.json.JSONObject

/**
 * The Expert Talk call (CallView.jsx), for both sides. Voice only, never recorded — the same Agora
 * voice the games use (GameVoice, with the expertTalk function's pass), so an app phone and a
 * website phone hear each other.
 *  - The member starts the call and is the only one who can end it.
 *  - The expert can only pick up (no decline, no end) and can report the member:
 *    1st report warns the member, a 2nd report (30 s later or more) ends the call.
 */
@SuppressLint("ViewConstructor")
class EtCallPage(et: ExpertTalkScreen, private val bookingId: String, private val role: String, private val other: String, private val otherPhoto: String,
                 initial: JSONObject?, private val onDone: (JSONObject?) -> Unit) : EtPage(et, Color.WHITE), EtPreviewable {
    override fun preview(what: String) { when (what) { "end" -> endSheet(); "report" -> reportSheet(); "ureport" -> userReportSheet() } }
    companion object { val OVER = listOf("completed", "user_no_show", "refunded", "expert_no_show", "expert_left", "cut_by_report") }
    override val pullable: Boolean get() = false
    override val rank = 7
    private var s: JSONObject? = initial
    private var done = false
    private var busy = false
    private val voice = GameVoice(ctx, "expertTalk", "Allow the microphone so you can talk to your expert.") { if (!disposed) paint() }

    // views (built once, filled on every change)
    private val leftPill = etPill(ctx, "", "ok")
    private val warn = etAlert(ctx, EtSpan().add("The expert reported this call.", bold = true).add(" Please be respectful. If she reports again, the call ends and True Find may block your account.").build(), true)
    private val ring = EtPulse(ctx)
    private val status = etT(ctx, "", 12.5f, Et.BODY, false, 1f)
    private val statusBox = etRow(ctx, 8f)
    private val reconnect = EtBtn(ctx, "Reconnect voice", "s2", sm = true)
    private val ringNote = etT(ctx, "", 12.5f, Et.MUT)
    private val ctrls = FrameLayout(ctx)
    private val reportLink: TextView
    private var ctrlKey = ""
    private val muteBtn = FrameLayout(ctx); private val muteLabel = etT(ctx, "Mute", 12.5f, Et.BODY)
    private val loudBtn = FrameLayout(ctx)

    init {
        // the soft purple halo behind the top
        addView(View(ctx).apply { background = Tf.circle(Ui.c("#F4EFFE")) }, 0, LayoutParams(Ui.dpi(540f), Ui.dpi(470f), Gravity.CENTER_HORIZONTAL).apply { topMargin = -Ui.dpi(130f) })
        reportLink = et.ui.lnk("Report the expert", red = true) { userReportSheet() }
        poll()
        every(2000) { poll() }
    }

    override fun back(): Boolean {
        val st = Et.s(s, "status")
        // a call is never left by accident: the member gets "End the call now?"; the expert can't leave it
        if (role == "member" && st == "live") { endSheet(); return true }
        if (role == "member" && st == "confirmed") { onDone(s); return true }
        // the expert's screen after the member cancelled the ring: back to her dashboard
        if (role == "expert" && st != "ringing" && st != "live") { onDone(s); return true }
        return true
    }

    override fun onDispose() { voice.stop() }

    private fun poll() {
        Et.call("state", JSONObject().put("booking_id", bookingId)) { r ->
            if (disposed) return@call
            if (r.ok) {
                val st = r.data.optJSONObject("state") ?: return@call
                s = st
                paint()
                if (OVER.contains(Et.s(st, "status")) && !done) { done = true; voice.stop(); onDone(st) }
            } else if (r.status == 403 || r.status == 404) { if (!done) { done = true; voice.stop(); onDone(null) } }
        }
    }

    private fun act(action: String, extra: JSONObject = JSONObject(), done: (Et.R?) -> Unit = {}) {
        busy = true; paint()
        extra.put("booking_id", bookingId)
        Et.call(action, extra) { r ->
            busy = false
            if (disposed) return@call
            if (r.ok) { r.data.optJSONObject("state")?.let { s = it }; paint(); poll(); done(r) }
            else { toast(r.error); paint(); done(null) }
        }
    }

    override fun tick() = paint()

    private fun ms(v: String) = Tf.ms(v, plainUtc = true)
    private fun left(): Double {
        val st = s ?: return 0.0
        val live = Et.s(st, "status") == "live"
        val ans = ms(Et.s(st, "answered_at"))
        return if (live && ans > 0) Et.i(st, "dur") * 60.0 - (System.currentTimeMillis() - ans) / 1000.0 else Et.i(st, "dur") * 60.0
    }

    private var built = false
    override fun render() {
        if (built) { paint(); return }
        body.removeAllViews()
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(Ui.dpi(16f), Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(22f))
        if (s == null) { body.put(FrameLayout(ctx).apply { addView(TfLoader(ctx, 30f), LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER_HORIZONTAL or Gravity.TOP)) }, -1f, -2f, t = 120f); return }
        built = true
        val top = etRow(ctx, 0f)
        top.put(etPill(ctx, "Private · not recorded", "pur", EtIcon.Lock, 11f))
        top.put(View(ctx), 0f, 1f, 1f)
        top.put(leftPill)
        body.put(top, -1f, -2f)
        body.put(warn, -1f, -2f, t = 12f)
        val rb = FrameLayout(ctx)
        rb.put(ring, -1f, -1f)
        rb.put(EtPhoto(ctx, 140f).apply { set(otherPhoto) }, 140f, 140f, Gravity.CENTER)
        body.put(rb, 168f, 168f, t = 34f)
        body.put(etT(ctx, other, 22f, Et.INK, true).apply { gravity = Gravity.CENTER }, t = 16f)
        statusBox.removeAllViews()
        statusBox.background = Tf.box(Et.PAGE, 16f, 1f, Et.LINE)
        statusBox.setPadding(Ui.dpi(13f), 0, Ui.dpi(13f), 0)
        statusBox.put(EtBlinkDot(ctx), 8f, 8f)
        statusBox.put(status)
        body.put(statusBox, -2f, 32f, t = 14f)
        reconnect.setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0)
        reconnect.setOnClickListener { voice.retry() }
        body.put(reconnect, -2f, -2f, t = 14f)
        body.put(ringNote, t = 12f)
        body.put(View(ctx), -1f, 0f, 1f)
        body.put(ctrls, -1f, -2f, t = 16f)
        body.put(reportLink, -2f, -2f, t = 14f)
        ctrlKey = ""
        paint()
    }

    /** fills everything from the call's state (every second and on every change) */
    private fun paint() {
        val st = s ?: return
        if (!built) { render(); return }
        val stt = Et.s(st, "status")
        val live = stt == "live"
        val missed = Et.i(st, "missed_rings")
        voice.setSession(if (live) bookingId else null)
        leftPill.visibility = if (live) View.VISIBLE else View.INVISIBLE
        leftPill.text = "${Et.mmss(left())} left"
        warn.visibility = if (role == "member" && Et.i(st, "report_count") == 1 && live) View.VISIBLE else View.GONE
        ring.on = stt == "ringing" || (live && voice.speakingPartner)
        ring.green = live
        status.text = when {
            stt == "ringing" -> if (role == "member") "Ringing… try ${minOf(2, missed + 1)} of 2" else "Incoming Expert Talk call"
            stt == "confirmed" && role == "member" -> if (missed > 0) "No answer" else "Ready to call"
            live -> when (voice.state) {
                "live" -> if (voice.partnerIn) "Connected · not recorded" else "Waiting for the other side to join…"
                "unavailable" -> "Voice is not switched on yet"
                "error" -> voice.error.ifEmpty { "Voice problem" }
                else -> "Connecting…"
            }
            else -> "Call ended"
        }
        reconnect.visibility = if (live && voice.state == "error") View.VISIBLE else View.GONE
        val ringAt = ms(Et.s(st, "ring_at"))
        val ringLeft = maxOf(0.0, 45 - (System.currentTimeMillis() - ringAt) / 1000.0)
        ringNote.visibility = if (stt == "ringing" && role == "member") View.VISIBLE else View.GONE
        ringNote.text = "${Math.ceil(ringLeft).toInt()}s · if she doesn't pick up twice, you get a full refund"
        reportLink.visibility = if (live && role == "member") View.VISIBLE else View.GONE
        // the buttons at the bottom (built again only when what they are changes)
        val key = "$stt|$role|$missed|$busy"
        if (key != ctrlKey) { ctrlKey = key; buildCtrls(stt, live, missed) }
        if (live) paintRound()
    }

    private fun buildCtrls(stt: String, live: Boolean, missed: Int) {
        ctrls.removeAllViews()
        when {
            role == "expert" && stt == "ringing" -> ctrls.addView(etCol(ctx, 8f, Gravity.CENTER_HORIZONTAL).apply {
                put(EtBtn(ctx, "Pick up", "green", EtIcon.Phone, 19f).apply { off = busy; setOnClickListener { if (!busy) act("answer") } }, -1f, -2f)
                put(etT(ctx, "Only the member can end an Expert Talk call.", 11.5f, Et.MUT))
            }, FrameLayout.LayoutParams(-1, -2))
            role == "member" && stt == "confirmed" -> ctrls.addView(etCol(ctx, 8f).apply {
                put(EtBtn(ctx, if (missed > 0) "Call again" else "Start call", icon = EtIcon.Phone, iconSize = 19f).apply { off = busy; setOnClickListener { if (!busy) act("call") } }, -1f, -2f)
                put(EtBtn(ctx, "Back", "s2").apply { setOnClickListener { onDone(s) } }, -1f, -2f)
            }, FrameLayout.LayoutParams(-1, -2))
            role == "member" && stt == "ringing" -> ctrls.addView(etRow(ctx, 10f, Gravity.CENTER).apply {
                background = Tf.box(Color.WHITE, 27f, 1.5f, Ui.c("#F3C4C6"))
                setPadding(Ui.dpi(30f), 0, Ui.dpi(30f), 0)
                put(etIcon(ctx, EtIcon.PhoneOff, 18f, Et.RED_TEXT), 18f, 18f)
                put(etT(ctx, "Cancel", 16f, Et.RED_TEXT, true, 1f))
                isEnabled = !busy; alpha = if (busy) .6f else 1f
                setOnClickListener { if (!busy) act("end") }
                Ui.pressable(this, .97f)
            }, FrameLayout.LayoutParams(-2, Ui.dpi(54f), Gravity.CENTER_HORIZONTAL))
            live -> {
                val row = etRow(ctx, 0f, Gravity.TOP)
                fun slot(btn: View, size: Float, label: TextView): View = etCol(ctx, 6f, Gravity.CENTER_HORIZONTAL).apply { put(btn, size, size); put(label) }
                row.put(View(ctx), 0f, 1f, 1f)
                row.put(slot(muteBtn.apply { contentDescription = "Mute"; setOnClickListener { voice.toggleMute() } }, 58f, muteLabel))
                row.put(View(ctx), 0f, 1f, 1f)
                if (role == "member") {
                    val end = FrameLayout(ctx).apply {
                        background = Tf.circle(Ui.c("#E5484D")); elevation = Ui.dp(6f); outlineSpotShadowColorCompat(Color.argb(150, 229, 72, 77))
                        addView(etIcon(ctx, EtIcon.PhoneOff, 26f, Color.WHITE), LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER))
                        contentDescription = "End call"; setOnClickListener { endSheet() }
                    }
                    row.put(slot(end, 66f, etT(ctx, "End", 12.5f, Et.BODY)))
                } else {
                    val rep = FrameLayout(ctx).apply {
                        background = Tf.circle(Ui.c("#FDECEC"))
                        addView(etIcon(ctx, EtIcon.Flag, 22f, Et.RED_TEXT), LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
                        contentDescription = "Report member"; setOnClickListener { reportSheet() }
                    }
                    row.put(slot(rep, 58f, etT(ctx, "Report", 12.5f, Et.BODY)))
                }
                row.put(View(ctx), 0f, 1f, 1f)
                row.put(slot(loudBtn.apply { contentDescription = "Speaker"; setOnClickListener { voice.toggleLoud() } }, 58f, etT(ctx, "Speaker", 12.5f, Et.BODY)))
                row.put(View(ctx), 0f, 1f, 1f)
                ctrls.addView(row, FrameLayout.LayoutParams(-1, -2))
                paintRound()
            }
        }
    }

    private var roundKey = ""
    private fun paintRound() {
        val k = "${voice.muted}|${voice.loud}"
        if (k == roundKey && muteBtn.childCount > 0) return
        roundKey = k
        muteBtn.removeAllViews()
        muteBtn.background = Tf.circle(if (voice.muted) Et.P else Ui.c("#F3F0F8"))
        muteBtn.addView(etIcon(ctx, if (voice.muted) EtIcon.MicOff else EtIcon.Mic, 22f, if (voice.muted) Color.WHITE else Et.INK), FrameLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
        muteBtn.contentDescription = if (voice.muted) "Unmute" else "Mute"
        muteLabel.text = if (voice.muted) "Muted" else "Mute"
        loudBtn.removeAllViews()
        loudBtn.background = Tf.circle(if (voice.loud) Et.P else Ui.c("#F3F0F8"))
        loudBtn.addView(etIcon(ctx, if (voice.loud) EtIcon.Volume2 else EtIcon.Volume1, 22f, if (voice.loud) Color.WHITE else Et.INK), FrameLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
    }

    // ── pop-ups ──
    private fun endSheet() {
        val ui = et.ui
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.PhoneOff, "End the call now?", "${Et.mmss(left())} of your session is left. Ended sessions can't be restarted.", null), -1f, -2f)
            p.put(EtBtn(ctx, "End call", "red").apply { setOnClickListener { if (!busy) { off = true; act("end") { close() } } } }, -1f, -2f)
            p.put(EtBtn(ctx, "Keep talking", "s2").apply { setOnClickListener { close() } }, -1f, -2f)
        }
    }

    private fun reportSheet() {
        val ui = et.ui
        val second = Et.i(s, "report_count") > 0
        var text = ""
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.Flag, if (second) "Second report ends the call" else "Report this member", if (second) "The call ends now and goes to True Find with both sides." else "The member is warned at once. A second report ends the call.") { close() }, -1f, -2f)
            p.put(etTextArea(ctx, "What did the member say or do?", 600) { text = it }, -1f, -2f)
            p.put(EtBtn(ctx, if (second) "Report again and end call" else "Send report", "red").apply {
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    off = true
                    act("report", JSONObject().put("text", text)) { r -> off = false; if (r != null) { toast(if (Et.s(r.data, "step") == "ended") "Call ended. True Find will review it." else "The member has been warned."); close() } }
                }
            }, -1f, -2f)
        }
    }

    private fun userReportSheet() {
        val ui = et.ui
        var text = ""
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.Flag, "Report the expert", "True Find reads every report. You can also end the call and give a low rating.") { close() }, -1f, -2f)
            val send = EtBtn(ctx, "Send report", "red").apply { off = true }
            p.put(etTextArea(ctx, "What happened?", 1500) { text = it; send.off = busy || it.trim().length < 10 }, -1f, -2f)
            send.setOnClickListener {
                if (busy || text.trim().length < 10) return@setOnClickListener
                send.off = true
                act("user_report", JSONObject().put("text", text)) { r -> if (r != null) { toast("Report sent to True Find."); close() } else send.off = false }
            }
            p.put(send, -1f, -2f)
        }
    }
}

/** The ring growing out of the photo while it rings / while the other side talks (.pulse: 2.4 s). */
class EtPulse(ctx: Context) : View(ctx) {
    var on = false
        set(v) { if (field != v) { field = v; sync() } }
    var green = false
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(2f) }
    private val ease = DecelerateInterpolator()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private fun sync() { if (on && isAttachedToWindow && isShown) { if (!anim.isStarted) anim.start() } else { anim.cancel(); t = 0f; invalidate() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { sync() } }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onDraw(c: Canvas) {
        if (!on) return
        val e = ease.getInterpolation(t)
        val r = (width / 2f - Ui.dp(1f)) * (1f + .7f * e)
        p.color = if (green) Ui.c("#86D3A6") else Ui.c("#B9A0F7")
        p.alpha = ((1f - e) * .6f * 255).toInt()
        c.drawCircle(width / 2f, height / 2f, r, p)
    }
}

/** The purple dot of the status line, blinking (1.3 s, down to 30 %). */
class EtBlinkDot(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Et.P }
    private var a = 1f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { val f = it.animatedValue as Float; val k = if (f < .5f) f / .5f else (1f - f) / .5f; a = 1f - .7f * k; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) { p.alpha = (a * 255).toInt(); c.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, p) }
}

@Suppress("unused") private fun keepCall(l: LinearLayout) = l
