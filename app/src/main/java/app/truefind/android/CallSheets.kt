package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject

/**
 * The sheets over the call screen (the web's CallFeedback.jsx and CallScreen.jsx): the stars after a call
 * that was picked up, the in-call Report, and "Add coins". Flat white sheets, Calibri, black + purple.
 */
object CallSheets {
    private val INK = Ui.c("#17121F"); private val SUB = Ui.c("#5E586B"); private val FAINT = Ui.c("#8A829B")
    private val LINE = Ui.c("#E9E6EE"); private val PURPLE = Ui.c("#6D28D9"); private val DEEP = Ui.c("#4C1D95")
    private val TINT = Ui.c("#F3EEFB"); private val RED = Ui.c("#B91C1C"); private val RED_BG = Ui.c("#FDECEC"); private val STAR = Ui.c("#F5A400")
    private val DIM = Color.argb(140, 10, 6, 18)
    val GOOD_TAGS = listOf("Friendly", "Good talk", "Clear voice", "Respectful")
    val BAD_TAGS = listOf("Rude", "Silent / didn't talk", "Asked for money", "Fake profile", "Bad network")
    val REPORT_REASONS = listOf("Abusive or rude", "Sexual / nudity", "Asking for money", "Fake profile", "Underage", "Other")
    private val STAR_WORDS = listOf("", "Very bad", "Not good", "Okay", "Good", "Loved it")
    private fun first(n: String) = CallKit.firstName(n)

    /** the white sheet itself (padding 18, round top 22) */
    private fun panel(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Tf.topRound(Color.WHITE, 22f)
        setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f))
        isClickable = true
    }

    /** a chip (Chip of CallFeedback.jsx): purple when picked, red for report reasons */
    private class Chip(ctx: Context, val label: String, private val danger: Boolean, onTap: (Chip) -> Unit) : FrameLayout(ctx) {
        private val tv = CallKit.t(ctx, label, 13.5f, INK, 700).apply { gravity = Gravity.CENTER }
        var on = false
            set(v) { field = v; paint() }
        init {
            setPadding(Ui.dpi(13f), 0, Ui.dpi(13f), 0)
            addView(tv, LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { onTap(this) }
            paint()
        }
        private fun paint() {
            background = Tf.box(if (on) (if (danger) RED_BG else TINT) else Color.WHITE, 17f, 1f, if (on) (if (danger) RED else PURPLE) else LINE)
            tv.setTextColor(if (on) (if (danger) RED else DEEP) else INK)
            contentDescription = label + if (on) ", selected" else ""
        }
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.dpi(34f), MeasureSpec.EXACTLY))
    }

    /** the big button (MainBtn): black, or red for a report; busy → the loader */
    private fun mainBtn(ctx: Context, label: String, danger: Boolean): TfBtn = TfBtn(ctx, label, if (danger) "danger" else "dark", null, 48f, 14f, 15.5f)
    private fun linkBtn(ctx: Context, label: String, onTap: () -> Unit): TextView = CallKit.t(ctx, label, 14f, SUB, 700).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onTap() }
    }

    private fun wrap(ctx: Context, chips: List<View>, center: Boolean): CallFlow = CallFlow(ctx, Ui.dpi(7f), Ui.dpi(7f), center).apply { chips.forEach { addView(it) } }

    private fun area(ctx: Context, hint: String, max: Int): EditText = EditText(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(14f))
        typeface = Ui.regular
        setTextColor(INK); setHintTextColor(FAINT)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2; maxLines = 4
        gravity = Gravity.TOP or Gravity.START
        background = Tf.box(Color.WHITE, 12f, 1f, LINE)
        setPadding(Ui.dpi(11f), Ui.dpi(9f), Ui.dpi(11f), Ui.dpi(9f))
        filters = arrayOf(InputFilter.LengthFilter(max))
        includeFontPadding = false
    }

    // ═════════════════ after the call: the stars ═════════════════
    /**
     * Asks the server first (only calls that were picked up, once per call); if there is nothing to rate,
     * onDone() runs at once. preview (test pictures): 0 = ask, 2 = two stars + report, -1 = thanks.
     */
    fun rating(host: CallView, s: CallSession, name: String, preview: Int? = null, onDone: () -> Unit) {
        val ctx = host.context
        var done = false
        val finish = { if (!done) { done = true; onDone() } }
        if (preview != null) { show(host, s, name, preview, finish); return }
        if (s.practice) { finish(); return }
        Api.invoke("callFeedback", JSONObject().put("action", "status").put("call_id", s.callId)) { r ->
            if (r.ok && r.data.optBoolean("can_rate")) show(host, s, name, null, finish) else finish()
        }
        @Suppress("UNUSED_EXPRESSION") ctx
    }

    private fun show(host: CallView, s: CallSession, name: String, preview: Int?, onDone: () -> Unit) {
        val ctx = host.context
        val f = first(name).ifEmpty { "them" }
        val p = panel(ctx)
        var stars = 0
        val tags = ArrayList<String>()
        var report = ""
        var busy = false
        var close: () -> Unit = {}
        var closedBySend = false

        // header: title + private note, the skip ×
        val head = Tf.row(ctx, Gravity.TOP)
        val hc = Tf.col(ctx)
        hc.addView(CallKit.t(ctx, "How was your call with $f?", 18f, INK, 700))
        hc.addView(CallKit.t(ctx, "Your rating is private. It helps keep True Find friendly.", 13f, SUB, 400).apply { setLineSpacing(0f, 1.15f) }, Tf.ll(t = 2f))
        head.addView(hc, Tf.ll(0, Tf.W, 1f, r = 10f))
        val x = FrameLayout(ctx).apply { background = Tf.circle(Ui.c("#F4F2F7")); contentDescription = "Skip"; setOnClickListener { close() } }
        x.addView(ImageView(ctx).apply { setImageDrawable(CallKit.lu(Lu.X, 17f, SUB)) }, FrameLayout.LayoutParams(Ui.dpi(17f), Ui.dpi(17f), Gravity.CENTER))
        head.addView(x, Tf.ll(Ui.dpi(34f), Ui.dpi(34f)))
        p.addView(head)

        // the stars
        val starRow = Tf.row(ctx, Gravity.CENTER)
        val starViews = ArrayList<ImageView>()
        val word = CallKit.t(ctx, "Tap a star", 13.5f, FAINT, 700).apply { gravity = Gravity.CENTER; minHeight = Ui.dpi(18f) }
        val tagBox = FrameLayout(ctx)
        val low = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 14f, 1f, LINE); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)) }
        val comment = area(ctx, "Anything else? (optional)", 300)
        val err = CallKit.t(ctx, "", 13f, RED, 400)
        val send = mainBtn(ctx, "Send rating", false)
        val reportChips = ArrayList<Chip>()
        val reportNote = CallKit.t(ctx, "The team gets this report and $f is blocked for you.", 12.5f, SUB, 400)

        fun refresh() {
            starViews.forEachIndexed { i, v ->
                val on = i + 1 <= stars
                v.setImageDrawable(SvgDrawable(Svg.lucide(Lu.Star, 1.6f, if (on) "#F5A400" else "none"), 36f, 36f, if (on) STAR else Ui.c("#CFC9D9")))
            }
            word.text = if (stars > 0) STAR_WORDS[stars] else "Tap a star"
            word.setTextColor(if (stars > 0) INK else FAINT)
            val isLow = stars in 1..2
            val list = if (stars >= 4) GOOD_TAGS else if (stars > 0) BAD_TAGS else emptyList()
            tagBox.removeAllViews()
            if (list.isNotEmpty()) {
                val chips = list.map { t -> Chip(ctx, t, false) { c ->
                    if (tags.contains(t)) tags.remove(t) else if (tags.size < 5) tags.add(t)
                    c.on = tags.contains(t)
                } .also { it.on = tags.contains(t) } }
                tagBox.addView(wrap(ctx, chips, true), FrameLayout.LayoutParams(-1, -2))
            }
            tagBox.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
            low.visibility = if (isLow) View.VISIBLE else View.GONE
            reportChips.forEach { it.on = it.label == report }
            reportNote.visibility = if (report.isNotEmpty()) View.VISIBLE else View.GONE
            comment.visibility = if (stars > 0) View.VISIBLE else View.GONE
            val danger = isLow && report.isNotEmpty()
            send.label(if (danger) "Send rating & report" else "Send rating")
            send.background = Tf.box(if (danger) RED else INK, 14f)
            send.off = stars == 0
            err.visibility = if (err.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        for (n in 1..5) {
            val b = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "$n star${if (n > 1) "s" else ""}"
                setOnClickListener { stars = n; tags.clear(); if (n > 2) report = ""; refresh() }
            }
            starViews.add(b)
            starRow.addView(b, Tf.ll(Ui.dpi(48f), Ui.dpi(48f), l = if (n > 1) 6f else 0f))
        }
        p.addView(starRow, Tf.ll(Tf.M, Tf.W, t = 16f, b = 4f))
        p.addView(word, Tf.ll(Tf.M, Tf.W, b = 12f))
        p.addView(tagBox, Tf.ll(Tf.M, Tf.W, b = 12f))
        // 1–2 stars: "Something wrong? Report Riya"
        val lt = Tf.row(ctx)
        lt.addView(ImageView(ctx).apply { setImageDrawable(CallKit.lu(CallKit.SHIELD_ALERT, 16f, RED)) }, Tf.ll(Ui.dpi(16f), Ui.dpi(16f), r = 6f))
        lt.addView(CallKit.t(ctx, "Something wrong? Report $f", 13.5f, INK, 700))
        low.addView(lt, Tf.ll(b = 8f))
        REPORT_REASONS.forEach { r -> reportChips.add(Chip(ctx, r, true) { report = if (report == r) "" else r; refresh() }) }
        low.addView(wrap(ctx, reportChips, false), Tf.ll(Tf.M, Tf.W))
        low.addView(reportNote, Tf.ll(t = 8f))
        p.addView(low, Tf.ll(Tf.M, Tf.W, b = 12f))
        p.addView(comment, Tf.ll(Tf.M, Tf.W, b = 12f))
        p.addView(err, Tf.ll(Tf.M, Tf.W, b = 10f))
        p.addView(send, Tf.ll(Tf.M, Ui.dpi(48f)))
        p.addView(linkBtn(ctx, "Skip") { close() }, Tf.ll(Tf.M, Ui.dpi(42f), t = 6f))

        fun thanks(reported: Boolean) {
            closedBySend = true
            close()
            val tp = panel(ctx)
            val c = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply { setPadding(0, Ui.dpi(10f), 0, Ui.dpi(6f)) }
            val ok = FrameLayout(ctx).apply { background = Tf.circle(TINT) }
            ok.addView(ImageView(ctx).apply { setImageDrawable(CallKit.lu(Lu.Check, 24f, PURPLE)) }, FrameLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.CENTER))
            c.addView(ok, Tf.ll(Ui.dpi(46f), Ui.dpi(46f)))
            c.addView(CallKit.t(ctx, "Thanks for your feedback", 17f, INK, 700), Tf.ll(t = 8f))
            if (reported) c.addView(CallKit.t(ctx, "Our team will check it. $f can't call you any more.", 13.5f, SUB, 400).apply { gravity = Gravity.CENTER }, Tf.ll(t = 8f))
            tp.addView(c, Tf.ll(Tf.M, Tf.W))
            var tclose: () -> Unit = {}
            var left = false
            val leave = { if (!left) { left = true; tclose(); onDone() } }
            tclose = host.layer.sheet(tp, DIM, onClose = { leave() })
            if (preview == null) Handler(Looper.getMainLooper()).postDelayed({ leave() }, 1200)
        }

        send.setOnClickListener {
            if (stars == 0 || busy) return@setOnClickListener
            val isLow = stars in 1..2
            val list = if (stars >= 4) GOOD_TAGS else BAD_TAGS
            busy = true; send.busy = true; err.text = ""; refresh()
            val body = JSONObject().put("action", "rate").put("call_id", s.callId).put("stars", stars)
                .put("tags", org.json.JSONArray(tags.filter { list.contains(it) })).put("comment", comment.text.toString().trim())
            if (isLow && report.isNotEmpty()) body.put("report_reason", report)
            Api.invoke("callFeedback", body) { r ->
                busy = false; send.busy = false
                if (r.ok || r.status == 409) thanks(isLow && report.isNotEmpty())
                else { err.text = Tf.err(r, "Could not send. Please try again."); refresh() }
            }
        }

        refresh()
        if (preview == -1) { thanks(false); return }
        if (preview == 2) { stars = 2; report = "Abusive or rude"; tags.add("Rude"); refresh() }
        close = host.layer.sheet(p, DIM, maxFrac = .92f, locked = { busy }, onClose = { if (!closedBySend) onDone() })
    }

    // ═════════════════ during the call: Report ═════════════════
    /** Files the report (and blocks them), then onReported() hangs up. Returns close(). */
    fun report(host: CallView, s: CallSession, name: String, onClose: () -> Unit, onReported: () -> Unit): () -> Unit {
        val ctx = host.context
        val f = first(name).ifEmpty { "this person" }
        val p = panel(ctx)
        var reason = ""
        var busy = false
        var close: () -> Unit = {}
        var reported = false
        p.addView(CallKit.t(ctx, "Report $f", 18f, INK, 700))
        p.addView(CallKit.t(ctx, "The call ends, $f is blocked for you and our team checks it. $f is not told who reported.", 13f, SUB, 400).apply { setLineSpacing(0f, 1.15f) }, Tf.ll(t = 2f, b = 12f))
        val chips = ArrayList<Chip>()
        val send = mainBtn(ctx, "Report & end call", true)
        val err = CallKit.t(ctx, "", 13f, RED, 400).apply { visibility = View.GONE }
        fun refresh() { chips.forEach { it.on = it.label == reason }; send.off = reason.isEmpty() }
        REPORT_REASONS.forEach { r -> chips.add(Chip(ctx, r, true) { reason = r; refresh() }) }
        p.addView(wrap(ctx, chips, false), Tf.ll(Tf.M, Tf.W, b = 12f))
        val details = area(ctx, "What happened? (optional)", 500)
        p.addView(details, Tf.ll(Tf.M, Tf.W, b = 12f))
        p.addView(err, Tf.ll(Tf.M, Tf.W, b = 10f))
        p.addView(send, Tf.ll(Tf.M, Ui.dpi(48f)))
        val cancel = linkBtn(ctx, "Cancel") { if (!busy) close() }
        p.addView(cancel, Tf.ll(Tf.M, Ui.dpi(42f), t = 6f))
        send.setOnClickListener {
            if (reason.isEmpty() || busy) return@setOnClickListener
            if (s.practice) { reported = true; close(); onReported(); return@setOnClickListener }
            busy = true; send.busy = true; err.visibility = View.GONE
            Api.invoke("callFeedback", JSONObject().put("action", "report").put("call_id", s.callId).put("reason", reason).put("details", details.text.toString().trim())) { r ->
                if (r.ok) { reported = true; busy = false; close(); onReported() }
                else { busy = false; send.busy = false; err.text = Tf.err(r, "Could not send the report. Please try again."); err.visibility = View.VISIBLE }
            }
        }
        refresh()
        close = host.layer.sheet(p, DIM, maxFrac = .92f, locked = { busy }, onClose = { if (!reported) onClose() })
        return { close() }
    }

    // ═════════════════ the "+" after the balance ═════════════════
    /** "The Wallet opens on its own page, so this call will end." Returns close(). */
    fun addCoins(host: CallView, onKeep: () -> Unit, onEnd: () -> Unit): () -> Unit {
        val ctx = host.context
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Tf.topRound(Color.WHITE, 24f)
            setPadding(Ui.dpi(20f), Ui.dpi(20f), Ui.dpi(20f), Ui.dpi(20f))
            isClickable = true
        }
        p.addView(CallKit.t(ctx, "Add coins", 18f, CallKit.INK, 700))
        p.addView(CallKit.t(ctx, "The Wallet opens on its own page, so this call will end. Add coins now?", 14f, CallKit.SUB, 400).apply { setLineSpacing(0f, 1.2f) }, Tf.ll(t = 4f))
        val row = Tf.row(ctx)
        var close: () -> Unit = {}
        var ending = false
        val keep = CallKit.t(ctx, "Keep talking", 15f, CallKit.INK, 600).apply { gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 16f, 1f, Ui.c("#E9E6EE")); setOnClickListener { close() } }
        val end = CallKit.t(ctx, "End & add coins", 15f, Color.WHITE, 600).apply { gravity = Gravity.CENTER; background = Tf.box(Ui.c("#6A1FD0"), 16f); setOnClickListener { ending = true; close(); onEnd() } }
        Ui.pressable(keep, .97f); Ui.pressable(end, .97f)
        row.addView(keep, Tf.ll(0, Ui.dpi(44f), 1f, r = 12f))
        row.addView(end, Tf.ll(0, Ui.dpi(44f), 1f))
        p.addView(row, Tf.ll(Tf.M, Tf.W, t = 16f))
        close = host.layer.sheet(p, Color.argb(128, 0, 0, 0), onClose = { if (!ending) onKeep() })
        return { close() }
    }
}

/** A row that wraps its children onto more lines (flex-wrap), optionally centred. */
class CallFlow(ctx: Context, private val gapX: Int, private val gapY: Int, private val center: Boolean) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gapY; lineH = 0 }
            x += c.measuredWidth + gapX
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val lines = ArrayList<MutableList<View>>()
        var cur = ArrayList<View>(); var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (cur.isNotEmpty() && x + c.measuredWidth > maxW) { lines.add(cur); cur = ArrayList(); x = 0 }
            cur.add(c); x += c.measuredWidth + gapX
        }
        if (cur.isNotEmpty()) lines.add(cur)
        var y = paddingTop
        for (line in lines) {
            val w = line.sumOf { it.measuredWidth } + gapX * (line.size - 1)
            var xx = paddingLeft + if (center) (maxW - w) / 2 else 0
            val h = line.maxOf { it.measuredHeight }
            for (c in line) { c.layout(xx, y, xx + c.measuredWidth, y + c.measuredHeight); xx += c.measuredWidth + gapX }
            y += h + gapY
        }
    }
}

/**
 * "Go to Chat" during a call: the other person's Secret Dating card with a small chat, drawn over the call
 * (the call keeps going; the purple bar on top goes back to it). Same as PeerPage in DateCallScreen.jsx:
 * callDate "peer" (her card + chat state), "chat" (opens the chat, 5 free messages without a plan),
 * the messages of that chat live, sendMessage; free messages used up → Go Premium (₹19/day).
 */
@SuppressLint("ViewConstructor")
class PeerPage(ctx: Context, private val s: CallSession, private val onBack: () -> Unit) : FrameLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private val demo = s.practice
    private var info: JSONObject? = null
    private var err = ""
    private var busy = false
    private var sendErr = ""
    private var meId = ""
    private val msgs = ArrayList<JSONObject>()
    private var unsub: (() -> Unit)? = null
    private var dead = false
    private var photoRaw = s.otherPhoto
    private var premiumClose: (() -> Unit)? = null
    private val layer = TfLayer(this)

    // the "On call with Riya" bar
    private val barTime = CallKit.t(ctx, "00:00", 12.5f, CallKit.LILAC_TEXT, 400).apply { fontFeatureSettings = "tnum" }
    private val barBars = CallKit.Bars(ctx, white = true, count = 4)
    private val barName = CallKit.t(ctx, "", 14f, Color.WHITE, 700)
    // her card
    private val card = FrameLayout(ctx)
    private val cardPhoto = CoverImage(ctx)
    private val cardFace = CallFace(ctx, 120f, 0f, shadow = false)
    private val cardName = CallKit.t(ctx, "", 24f, Color.WHITE, 800)
    private val cardVerified = CallKit.t(ctx, "Verified", 11f, Color.WHITE, 700)
    private val cardCity = CallKit.t(ctx, "", 13f, Color.argb(217, 255, 255, 255), 400)
    private val errText = CallKit.t(ctx, "", 13f, Ui.c("#B91C1C"), 400)
    private val loading = CallKit.t(ctx, "Loading…", 13f, CallKit.SUB, 400)
    // before the chat: "Chat with Riya" + Start Free Chat
    private val startBox = LinearLayout(ctx)
    private val startTitle = CallKit.t(ctx, "", 21f, Color.WHITE, 800)
    private val startFree = CallKit.t(ctx, "", 12f, CallKit.LILAC_TEXT, 600)
    private val startBtnText = CallKit.t(ctx, "", 15.5f, CallKit.INK, 700)
    // the chat
    private val chatBox = LinearLayout(ctx)
    private val chatTitle = CallKit.t(ctx, "", 16f, Color.WHITE, 800)
    private val chatFree = CallKit.t(ctx, "", 12f, CallKit.LILAC_TEXT, 400)
    private val list = LinearLayout(ctx)
    private val scroll = ScrollView(ctx)
    private val empty = CallKit.t(ctx, "", 13f, CallKit.LILAC_TEXT, 400)
    private val sendErrText = CallKit.t(ctx, "", 12.5f, Ui.c("#FFB4BC"), 400)
    private val inputRow = Tf.row(ctx)
    private val input = EditText(ctx)
    private val sendBtn = FrameLayout(ctx)
    private val lockedBox = LinearLayout(ctx)
    private val spacer = View(ctx)

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        addView(CallKit.doodles(ctx), LayoutParams(-1, -1))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), Ui.dpi(14f)) }
        addView(col, LayoutParams(-1, -1))
        // back to the call
        val bar = Tf.row(ctx).apply {
            background = Tf.box(CallKit.PURPLE, 18f)
            setPadding(Ui.dpi(12f), Ui.dpi(9f), Ui.dpi(12f), Ui.dpi(9f))
            elevation = Ui.dp(8f); outlineSpotShadowColorCompat(Color.argb(150, 106, 31, 208))
            contentDescription = "Back to the call"
            setOnClickListener { onBack() }
        }
        val ph = FrameLayout(ctx).apply { background = Tf.circle(Color.argb(46, 255, 255, 255)) }
        ph.addView(ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.PHONE, 17f)) }, LayoutParams(Ui.dpi(17f), Ui.dpi(17f), Gravity.CENTER))
        bar.addView(ph, Tf.ll(Ui.dpi(34f), Ui.dpi(34f), r = 10f))
        val bc = Tf.col(ctx)
        bc.addView(barName); bc.addView(barTime, Tf.ll(t = 2f))
        bar.addView(bc, Tf.ll(0, Tf.W, 1f))
        bar.addView(barBars, Tf.ll(r = 6f))
        bar.addView(CallKit.t(ctx, "Return", 12.5f, CallKit.PURPLE, 700).apply { background = Tf.box(Color.WHITE, 999f); setPadding(Ui.dpi(12f), Ui.dpi(6f), Ui.dpi(12f), Ui.dpi(6f)) })
        Ui.pressable(bar, .98f)
        col.addView(bar, Tf.ll(Tf.M, Tf.W))
        // SECRET DATING
        val sd = Tf.row(ctx)
        sd.addView(ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.LOCK, 14f, CallKit.PURPLE)) }, Tf.ll(Ui.dpi(14f), Ui.dpi(14f), r = 7f))
        sd.addView(CallKit.t(ctx, "SECRET DATING", 13f, CallKit.PURPLE, 700).apply { letterSpacing = .6f / 13f })
        col.addView(sd, Tf.ll(t = 14f))
        // her card
        card.background = Tf.box(CallKit.LAV2, 26f, 2.5f, CallKit.INK)
        card.clipToOutline = true
        card.addView(cardFace, LayoutParams(Ui.dpi(120f), Ui.dpi(120f), Gravity.CENTER))
        card.addView(cardPhoto, LayoutParams(-1, -1))
        val liked = Tf.row(ctx).apply { background = Tf.box(Color.WHITE, 999f); setPadding(Ui.dpi(7f), Ui.dpi(5f), Ui.dpi(11f), Ui.dpi(5f)); elevation = Ui.dp(4f) }
        liked.addView(ImageView(ctx).apply { setImageDrawable(CallKit.heart(17f)) }, Tf.ll(Ui.dpi(17f), Ui.dpi(17f), r = 6f))
        liked.addView(CallKit.t(ctx, "Liked", 12.5f, CallKit.PINK_TEXT, 700))
        card.addView(liked, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = Ui.dpi(12f); rightMargin = Ui.dpi(12f) })
        val shade = GradientView(ctx, intArrayOf(Color.argb(0, 21, 16, 30), Color.argb(217, 21, 16, 30)), null)
        val nameBox = FrameLayout(ctx)
        nameBox.addView(shade, LayoutParams(-1, -1))
        val nb = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(16f), Ui.dpi(34f), Ui.dpi(16f), Ui.dpi(12f)) }
        val nr = Tf.row(ctx)
        nr.addView(cardName, Tf.ll(r = 8f))
        cardVerified.background = Tf.box(CallKit.GREEN, 999f); cardVerified.setPadding(Ui.dpi(8f), Ui.dpi(2f), Ui.dpi(8f), Ui.dpi(2f))
        nr.addView(cardVerified)
        nb.addView(nr); nb.addView(cardCity, Tf.ll(t = 4f))
        nameBox.addView(nb, LayoutParams(-1, -2))
        card.addView(nameBox, LayoutParams(-1, -2, Gravity.BOTTOM))
        col.addView(card, Tf.ll(Tf.M, Ui.dpi(300f), t = 14f))
        col.addView(errText, Tf.ll(t = 14f))
        col.addView(loading, Tf.ll(t = 14f))
        col.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))
        // before the chat
        startBox.orientation = LinearLayout.VERTICAL
        startBox.background = Tf.box(CallKit.INK, 24f)
        startBox.setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f))
        val sh = Tf.row(ctx)
        sh.addView(startTitle, Tf.ll(0, Tf.W, 1f))
        startFree.background = Tf.box(Color.argb(77, 123, 47, 247), 999f, 1f, CallKit.LILAC)
        startFree.setPadding(Ui.dpi(10f), Ui.dpi(4f), Ui.dpi(10f), Ui.dpi(4f))
        sh.addView(startFree)
        startBox.addView(sh)
        val sb = Tf.row(ctx, Gravity.CENTER).apply { background = Tf.box(Color.WHITE, 999f); setOnClickListener { startChat() } }
        sb.addView(ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.CHAT, 18f, CallKit.PURPLE)) }, Tf.ll(Ui.dpi(18f), Ui.dpi(18f), r = 8f))
        sb.addView(startBtnText)
        Ui.pressable(sb, .98f)
        startBox.addView(sb, Tf.ll(Tf.M, Ui.dpi(50f), t = 12f))
        startBox.addView(CallKit.t(ctx, "Your call keeps going while you chat.", 12f, CallKit.LILAC_TEXT, 400).apply { gravity = Gravity.CENTER }, Tf.ll(Tf.M, Tf.W, t = 12f))
        col.addView(startBox, Tf.ll(Tf.M, Tf.W, t = 14f))
        // the chat
        chatBox.orientation = LinearLayout.VERTICAL
        chatBox.background = Tf.box(CallKit.INK, 24f)
        chatBox.setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f))
        val ch = Tf.row(ctx)
        ch.addView(chatTitle, Tf.ll(0, Tf.W, 1f)); ch.addView(chatFree)
        chatBox.addView(ch)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(Ui.dpi(2f), Ui.dpi(4f), Ui.dpi(2f), Ui.dpi(4f))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(list)
        chatBox.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dpi(10f) })
        chatBox.addView(sendErrText, Tf.ll(t = 10f))
        // the message box + send
        input.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f)); typeface = Ui.regular
            setTextColor(CallKit.INK); setHintTextColor(Ui.c("#9A91AD")); hint = "Type a message…"
            background = Tf.box(Color.WHITE, 999f)
            setPadding(Ui.dpi(16f), 0, Ui.dpi(16f), 0)
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(500))
            contentDescription = "Message"
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: Editable?) { paintSend() }
            })
        }
        inputRow.addView(input, Tf.ll(0, Ui.dpi(44f), 1f, r = 8f))
        sendBtn.background = Tf.circle(CallKit.VIOLET)
        sendBtn.addView(ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.SEND, 18f, Color.WHITE)) }, LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        sendBtn.contentDescription = "Send"
        sendBtn.setOnClickListener { send() }
        inputRow.addView(sendBtn, Tf.ll(Ui.dpi(44f), Ui.dpi(44f)))
        chatBox.addView(inputRow, Tf.ll(Tf.M, Tf.W, t = 10f))
        // free messages used up
        lockedBox.orientation = LinearLayout.VERTICAL
        lockedBox.background = Tf.box(Color.argb(77, 123, 47, 247), 16f)
        lockedBox.setPadding(Ui.dpi(10f), Ui.dpi(10f), Ui.dpi(10f), Ui.dpi(10f))
        lockedBox.addView(CallKit.t(ctx, "Free messages used up", 13f, Color.WHITE, 400).apply { gravity = Gravity.CENTER }, Tf.ll(Tf.M, Tf.W))
        val go = Tf.row(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CallKit.PINK_A, CallKit.PINK_B)).apply { cornerRadius = Ui.dp(16f) }
            setPadding(Ui.dpi(14f), Ui.dpi(8f), Ui.dpi(14f), Ui.dpi(8f))
            minimumHeight = Ui.dpi(52f)
            elevation = Ui.dp(6f); outlineSpotShadowColorCompat(Color.argb(160, 225, 48, 108))
            setOnClickListener { if (demo) s.endAndGo("/wallet?app=secret") else premiumAsk() }
        }
        val gc = Tf.col(ctx)
        gc.addView(CallKit.t(ctx, "Go Premium", 16f, Color.WHITE, 800))
        gc.addView(CallKit.t(ctx, "₹19/day · Unlimited chat", 12.5f, Color.argb(242, 255, 255, 255), 600), Tf.ll(t = 2f))
        go.addView(gc, Tf.ll(0, Tf.W, 1f))
        go.addView(CallKit.t(ctx, "Buy", 13f, CallKit.PINK_B, 800).apply { background = Tf.box(Color.WHITE, 999f); setPadding(Ui.dpi(12f), Ui.dpi(7f), Ui.dpi(12f), Ui.dpi(7f)) })
        Ui.pressable(go, .98f)
        lockedBox.addView(go, Tf.ll(Tf.M, Tf.W, t = 8f))
        chatBox.addView(lockedBox, Tf.ll(Tf.M, Tf.W, t = 10f))
        col.addView(chatBox, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dpi(14f) })
        load()
        render()
    }

    private fun load() {
        if (demo) {
            meId = "me"
            val p = JSONObject().put("name", s.otherName)
            info = JSONObject().put("peer", p).put("premium", false).put("free_left", 5).put("match_id", JSONObject.NULL)
            return
        }
        Api.me { r -> if (!dead) { meId = Tf.s(r.data, "id"); render() } }
        Api.invoke("callDate", JSONObject().put("call_id", s.callId).put("action", "peer")) { r ->
            if (dead) return@invoke
            if (r.ok) {
                info = r.data
                Tf.s(r.data.optJSONObject("peer"), "photo").takeIf { it.isNotEmpty() }?.let { photoRaw = it; photoKey = "" }
                watchChat()
            } else err = Tf.s(r.data, "error").ifEmpty { "Could not open the profile." }
            render()
        }
    }

    private fun matchId(): String = Tf.s(info, "match_id")

    private var watching = ""
    /** the chat's messages (the 40 newest) and new ones live */
    private fun watchChat() {
        val m = matchId()
        if (m.isEmpty() || demo || m == watching) return
        watching = m
        Api.filter("Message", JSONObject().put("match_id", m), "-created_date", 40) { r ->
            if (dead || !r.ok) return@filter
            val got = Tf.list(r).reversed()
            for (x in got) if (msgs.none { Tf.s(it, "id") == Tf.s(x, "id") }) msgs.add(x)
            renderMsgs(true)
        }
        unsub = Realtime.subscribe("Message") { ev ->
            val d = ev.data ?: return@subscribe
            if (dead || Tf.s(d, "match_id") != m) return@subscribe
            val i = msgs.indexOfFirst { Tf.s(it, "id") == Tf.s(d, "id") }
            if (i >= 0) msgs[i] = d else msgs.add(d)
            renderMsgs(true)
        }
    }

    private fun startChat() {
        if (busy) return
        if (demo) { info?.put("match_id", "demo"); render(); return }
        busy = true; err = ""; render()
        Api.invoke("callDate", JSONObject().put("call_id", s.callId).put("action", "chat")) { r ->
            busy = false
            if (dead) return@invoke
            if (r.ok) { val cur = info ?: JSONObject(); r.data.keys().forEach { k -> cur.put(k, r.data.opt(k)) }; info = cur; watchChat() }
            else err = Tf.s(r.data, "error").ifEmpty { "Could not start the chat." }
            render()
        }
    }

    private fun send() {
        val t = input.text.toString().trim()
        val m = matchId()
        if (t.isEmpty() || busy || m.isEmpty()) return
        val inf = info ?: return
        if (demo) {
            msgs.add(JSONObject().put("id", "d${System.currentTimeMillis()}").put("from_user_id", "me").put("kind", "text").put("text", t))
            input.setText("")
            inf.put("free_left", maxOf(0, inf.optInt("free_left") - 1))
            render(); renderMsgs(true); return
        }
        busy = true; sendErr = ""; render()
        Api.invoke("sendMessage", JSONObject().put("match_id", m).put("kind", "text").put("text", t)) { r ->
            busy = false
            if (dead) return@invoke
            if (r.ok) {
                input.setText("")
                val msg = r.data.optJSONObject("message")
                if (msg != null && Tf.s(msg, "id").isNotEmpty() && msgs.none { Tf.s(it, "id") == Tf.s(msg, "id") }) msgs.add(msg)
                if (!inf.optBoolean("premium")) inf.put("free_left", maxOf(0, inf.optInt("free_left") - 1))
                renderMsgs(true)
            } else sendErr = Tf.s(r.data, "reason", "message", "error").ifEmpty { "Message not sent." }
            render()
        }
    }

    private fun premiumAsk() {
        val ctx = context
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Tf.topRound(Color.WHITE, 22f)
            setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f))
            isClickable = true
        }
        p.addView(CallKit.t(ctx, "Go Premium", 18f, CallKit.INK, 800))
        p.addView(CallKit.t(ctx, "The Secret Dating wallet opens on its own page, so this call will end. Open it now?", 14f, CallKit.SUB, 400).apply { setLineSpacing(0f, 1.2f) }, Tf.ll(t = 4f))
        val row = Tf.row(ctx)
        val keep = CallKit.t(ctx, "Keep talking", 15f, CallKit.INK, 700).apply { gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 14f, 1f, Ui.c("#E9E6EE")); setOnClickListener { premiumClose?.invoke() } }
        val buy = CallKit.t(ctx, "End call & buy", 15f, Color.WHITE, 700).apply { gravity = Gravity.CENTER; background = Tf.box(CallKit.PINK_B, 14f); setOnClickListener { premiumClose?.invoke(); s.endAndGo("/wallet?app=secret") } }
        row.addView(keep, Tf.ll(0, Ui.dpi(46f), 1f, r = 10f)); row.addView(buy, Tf.ll(0, Ui.dpi(46f), 1f))
        p.addView(row, Tf.ll(Tf.M, Tf.W, t = 16f))
        premiumClose = layer.sheet(p, Color.argb(140, 10, 6, 18), onClose = { premiumClose = null })
    }

    /** the phone's Back on this page: a sheet first, then back to the call */
    fun back(): Boolean { if (layer.back()) return true; return false }

    private var photoKey = "?"
    private fun render() {
        val inf = info
        val p = inf?.optJSONObject("peer") ?: JSONObject()
        val name = CallKit.firstName(Tf.s(p, "name").ifEmpty { s.otherName }).ifEmpty { "Them" }
        barName.text = "On call with $name"
        val age = Tf.s(p, "age")
        cardName.text = if (age.isNotEmpty()) "$name, $age" else name
        cardVerified.visibility = if (p.optBoolean("verified")) View.VISIBLE else View.GONE
        val city = Tf.s(p, "city"); val st = Tf.s(p, "state")
        cardCity.text = if (city.isNotEmpty()) (if (st.isNotEmpty()) "$city, $st" else city) else ""
        cardCity.visibility = if (city.isNotEmpty()) View.VISIBLE else View.GONE
        if (photoKey != photoRaw) {
            photoKey = photoRaw
            cardFace.setPhoto(photoRaw)
            cardPhoto.bitmap = null
            if (photoRaw.isNotEmpty()) CallPics.fill(context, photoRaw, resources.displayMetrics.widthPixels, Ui.dpi(300f)) { b -> if (photoKey == photoRaw) cardPhoto.bitmap = b }
        }
        errText.text = err; errText.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE
        loading.visibility = if (inf == null && err.isEmpty()) View.VISIBLE else View.GONE
        val m = matchId()
        val chatOpen = m.isNotEmpty()
        val premium = inf?.optBoolean("premium") == true
        val freeLeft = inf?.optInt("free_left") ?: 0
        val locked = inf != null && !premium && freeLeft <= 0
        // the card shrinks to 150 when the chat opens (flex-basis 300 → 150)
        (card.layoutParams as LinearLayout.LayoutParams).let { lp -> val h = Ui.dpi(if (chatOpen) 150f else 300f); if (lp.height != h) { lp.height = h; card.layoutParams = lp } }
        startBox.visibility = if (inf != null && !chatOpen) View.VISIBLE else View.GONE
        spacer.visibility = if (inf != null && !chatOpen) View.VISIBLE else View.GONE
        chatBox.visibility = if (chatOpen) View.VISIBLE else View.GONE
        startTitle.text = "Chat with $name"
        startFree.text = "$freeLeft free chats"
        startFree.visibility = if (premium) View.GONE else View.VISIBLE
        startBtnText.text = if (premium) "Start Chat" else "Start Free Chat"
        startBox.alpha = if (busy && !chatOpen) .6f else 1f
        chatTitle.text = "Chat with $name"
        chatFree.text = "${if (demo) "Demo · not sent · " else ""}$freeLeft free left"
        chatFree.visibility = if (premium) View.GONE else View.VISIBLE
        sendErrText.text = sendErr; sendErrText.visibility = if (sendErr.isEmpty()) View.GONE else View.VISIBLE
        inputRow.visibility = if (locked) View.GONE else View.VISIBLE
        lockedBox.visibility = if (locked) View.VISIBLE else View.GONE
        empty.text = "Say hi to $name"
        paintSend()
        renderMsgs(false)
    }

    private fun paintSend() { sendBtn.alpha = if (busy || input.text.toString().trim().isEmpty()) .5f else 1f }

    private var shown = -1
    private fun renderMsgs(scrollDown: Boolean) {
        val ctx = context
        if (msgs.size == shown && !scrollDown) return
        shown = msgs.size
        list.removeAllViews()
        if (msgs.isEmpty()) { list.addView(empty.apply { gravity = Gravity.CENTER; (parent as? ViewGroup)?.removeView(this) }, Tf.ll(Tf.M, Tf.W, t = 10f)); return }
        for (m in msgs) {
            val mine = meId.isNotEmpty() && Tf.s(m, "from_user_id") == meId
            val kind = Tf.s(m, "kind")
            val txt = if (kind == "text") Tf.s(m, "text") else if (kind == "gif") "GIF" else "Message"
            val r = Ui.dp(16f); val small = Ui.dp(4f)
            val bubble = CallKit.t(ctx, txt, 14.5f, if (mine) Color.WHITE else CallKit.INK, 400).apply {
                setLineSpacing(0f, 1.2f)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (mine) CallKit.VIOLET else Color.WHITE)
                    cornerRadii = if (mine) floatArrayOf(r, r, r, r, small, small, r, r) else floatArrayOf(r, r, r, r, r, r, small, small)
                }
                setPadding(Ui.dpi(11f), Ui.dpi(7f), Ui.dpi(11f), Ui.dpi(7f))
                maxWidth = (resources.displayMetrics.widthPixels * .78f * .9f).toInt()
            }
            list.addView(bubble, Tf.ll(Tf.W, Tf.W, b = 6f, g = if (mine) Gravity.END else Gravity.START))
        }
        if (scrollDown) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** the clock and the talk bars of the purple bar follow the call */
    fun tick(seconds: Long, speaking: Boolean) {
        barTime.text = CallKit.fmt(seconds)
        barBars.visibility = if (speaking) View.VISIBLE else View.GONE
    }

    fun dispose() { dead = true; unsub?.invoke(); unsub = null; main.removeCallbacksAndMessages(null); layer.closeAll() }

    /** test pictures: the chat open with a few messages, the free messages used up, the Go Premium question */
    fun preview(what: String) {
        val inf = info ?: return
        inf.put("match_id", "demo")
        msgs.clear()
        msgs.add(JSONObject().put("id", "1").put("from_user_id", "me").put("kind", "text").put("text", "Hi Riya! Loved talking to you 😊"))
        msgs.add(JSONObject().put("id", "2").put("from_user_id", "her").put("kind", "text").put("text", "Same here! Your voice is so calm"))
        msgs.add(JSONObject().put("id", "3").put("from_user_id", "me").put("kind", "text").put("text", "Coffee this weekend?"))
        inf.put("free_left", if (what == "chat") 2 else 0)
        render(); renderMsgs(true)
        if (what == "premium") premiumAsk()
    }
}

/** The practice video call (/demo-call): my own front camera in the small tile, as the web's getUserMedia preview. */
class PracticeCam(private val ctx: Context, private val owner: LifecycleOwner?) {
    val view = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
    private var provider: ProcessCameraProvider? = null
    private var stopped = false
    fun start() {
        val o = owner ?: return
        try {
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({
                if (stopped) return@addListener
                try {
                    val p = f.get(); provider = p
                    val pv = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val cam = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    p.unbindAll()
                    p.bindToLifecycle(o, cam, pv)
                } catch (e: Throwable) { }
            }, ContextCompat.getMainExecutor(ctx))
        } catch (e: Throwable) { }
    }
    fun stop() { stopped = true; try { provider?.unbindAll() } catch (e: Throwable) { } }
}
