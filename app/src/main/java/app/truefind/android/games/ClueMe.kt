package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.LinearInterpolator
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

/** ClueMe rules and numbers (same as Games.jsx). */
object NameGuess {
    const val ROUND_SECONDS = 60
    const val TOTAL_ROUNDS = 6
    const val SKIPS = 2
    const val PTS_GUESSER = 10
    const val PTS_HINTER = 5
    val RULES = listOf(
        "Ek player ko gupt shabd dikhega (jaise पंखा · Fan). Wo bina shabd bole hint dega.",
        "Doosra player hint sun kar naam bolega ya type karega.",
        "Hint me shabd, uska hissa, spelling ya doosri bhasha me wahi shabd bolna mana hai.",
        "Har round 60 second. Hint dene wala 2 baar shabd badal (skip) sakta hai, koi saza nahi.",
        "Sahi jawab: bujhne wale ko +10, hint dene wale ko +5. Time khatam: koi point nahi.",
        "6 round, baari-baari se dono hint dete hain. Jiske zyada points, wo jeeta.",
    )
    val END_TEXT = mapOf(
        "left" to "Your partner left.", "you_left" to "You left the game.", "idle" to "Your partner's connection dropped.",
        "expired" to "Nobody joined with the code in time.", "replaced" to "A new game was started.", "too_long" to "The game ran too long and was closed.",
    )
    val THEME = intArrayOf(G.c("#ff8fa6"), G.c("#f43f5e"), G.c("#7c1d4b"))
    val THEME_STOPS = floatArrayOf(0f, 0.42f, 1f)
    val GLOW = G.ca("#f43f5e", 0.5f)
}

/**
 * The ClueMe game shell (web: NgShell): dark full screen, exit button, title, sub line,
 * and the shared parts — scores, timer bar, word card, summary, buttons.
 */
@SuppressLint("ViewConstructor")
abstract class NgScreen(ctx: Context, layer: GamesLayer) : GameScreen(ctx, layer) {
    protected val body: LinearLayout
    private val subT: TextView
    private val scroll = scrollPage(ctx)

    init {
        background = CssBg(0f, listOf(CssBg.Linear(180f, intArrayOf(G.c("#1a0d33"), G.c("#0e0824"), G.c("#07061a")), floatArrayOf(0f, 0.6f, 1f))))
        isClickable = true
        val col = G.vbox(ctx)
        col.pad(20f, 16f, 20f, 32f)
        val head = G.hbox(ctx, 12f)
        val x = FrameLayout(ctx).apply {
            background = G.oval(G.ca("#ffffff", 0.1f), G.ca("#ffffff", 0.2f), 1f)
            contentDescription = "Exit game"
            addView(G.icon(ctx, "X", Color.WHITE, 20f), FrameLayout.LayoutParams(G.dpi(20f), G.dpi(20f), Gravity.CENTER))
        }
        G.click(x) { exit() }
        head.addView(x, LinearLayout.LayoutParams(G.dpi(40f), G.dpi(40f)))
        val tc = G.vbox(ctx)
        tc.addView(G.text(ctx, "🕵️ ClueMe", 18f, Color.WHITE).lineH(1.25f))
        subT = G.text(ctx, "", 12f, G.ca("#ffffff", 0.55f), bold = false)
        tc.addView(subT)
        head.addView(tc, G.lpw(1f))
        col.addView(head, G.lp(G.MATCH, G.WRAP))
        body = G.vbox(ctx)
        col.addView(body, G.lp(G.MATCH, G.WRAP))
        scroll.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        addView(scroll, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
    }

    protected fun sub(s: String) { subT.text = s; subT.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE }
    protected open fun exit() { layer.close(this) }

    // ── shared parts ──
    protected fun themeBg(radius: Float) = CssBg(radius, listOf(CssBg.Linear(150f, NameGuess.THEME, NameGuess.THEME_STOPS)))
    protected fun ghostBg(radius: Float) = G.round(G.ca("#ffffff", 0.1f), radius, G.ca("#ffffff", 0.2f), 1f)
    protected fun greenBg(radius: Float) = CssBg(radius, listOf(CssBg.Linear(90f, intArrayOf(G.c("#10b981"), G.c("#059669")))))

    /** A full-width button (green / ghost / theme), 48 or 44 high like h-12 / h-11. */
    protected fun button(text: String, kind: String = "green", icon: String? = null, h: Float = 48f, semibold: Boolean = false, f: () -> Unit): LinearLayout {
        val ctx = context
        val b = G.hbox(ctx, 8f, Gravity.CENTER)
        b.background = when (kind) { "ghost" -> ghostBg(16f); "theme" -> themeBg(16f); else -> greenBg(16f) }
        if (kind == "green") G.shadow(b, 6f, G.c("#10b981"), 16f)
        if (kind == "theme") G.shadow(b, 6f, NameGuess.GLOW, 16f)
        if (icon != null) b.addView(G.icon(ctx, icon, Color.WHITE, if (h >= 48f) 18f else 16f))
        b.addView(G.text(ctx, text, 15f, if (kind == "ghost") G.ca("#ffffff", 0.9f) else Color.WHITE, bold = !semibold || kind != "ghost"))
        b.tag = h
        G.click(b, 0.98f) { if (b.isEnabled) f() }
        return b
    }
    protected fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }
    protected fun blp(h: Float, top: Float = 16f) = G.lp(G.MATCH, G.dpi(h)).m(t = top)

    protected fun scores(names: List<String>, scores: List<Int>, active: Int): View {
        val ctx = context
        val row = G.hbox(ctx, 10f, Gravity.TOP)
        for (i in 0..1) {
            val c = G.vbox(ctx)
            c.pad(12f, 10f, 12f, 10f)
            if (active == i) { c.background = themeBg(16f); G.shadow(c, 8f, NameGuess.GLOW, 16f) }
            else c.background = G.round(G.ca("#ffffff", 0.06f), 16f, G.ca("#ffffff", 0.1f), 1f)
            c.addView(G.text(ctx, names.getOrElse(i) { "…" }.ifEmpty { "…" }, 13.5f, Color.WHITE).ellipsize1())
            val sc = android.text.SpannableStringBuilder("${scores.getOrElse(i) { 0 }}")
            val a = sc.length
            sc.append(" pts")
            sc.setSpan(android.text.style.AbsoluteSizeSpan(G.dpi(12f)), a, sc.length, 0)
            sc.setSpan(android.text.style.ForegroundColorSpan(G.ca("#ffffff", 0.7f)), a, sc.length, 0)
            c.addView(G.text(ctx, sc, 24f, Color.WHITE), G.lp(G.WRAP, G.WRAP).m(t = 2f))
            if (active == i) c.addView(G.text(ctx, "🕵️ Giving hints", 11f, G.ca("#ffffff", 0.9f), bold = false), G.lp(G.WRAP, G.WRAP).m(t = 2f))
            row.addView(c, G.lpw(1f))
        }
        return row
    }

    /** Timer bar (60 s rounds): turns red in the last 10 seconds. */
    protected inner class TimerBar(ctx: Context, private val totalMs: Long) : LinearLayout(ctx) {
        private val secs = G.text(ctx, "", 13f, Color.WHITE)
        private val track = FrameLayout(ctx)
        private val fill = View(ctx)
        private var red = false
        init {
            orientation = VERTICAL
            val row = G.hbox(ctx)
            val l = G.hbox(ctx, 4f)
            l.addView(G.icon(ctx, "Timer", Color.WHITE, 16f))
            l.addView(G.text(ctx, "Time", 13f, Color.WHITE))
            row.addView(l, G.lpw(1f))
            row.addView(secs)
            addView(row, G.lp(G.MATCH, G.WRAP))
            track.background = G.round(G.ca("#ffffff", 0.12f), 99f)
            track.clipToOutline = true
            fill.pivotX = 0f
            track.addView(fill, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
            addView(track, G.lp(G.MATCH, G.dpi(8f)).m(t = 4f))
            paint(false)
        }
        private fun paint(r: Boolean) {
            red = r
            fill.background = if (r) G.round(G.c("#f87171"), 99f) else CssBg(99f, listOf(CssBg.Linear(90f, intArrayOf(G.c("#ffb199"), G.c("#ff5e7e")))))
        }
        fun set(msLeft: Long) {
            val s = ceil(msLeft / 1000.0).toInt()
            val t = "${s}s"
            if (secs.text.toString() != t) secs.text = t
            val r = s <= 10
            secs.setTextColor(if (r) G.c("#fca5a5") else Color.WHITE)
            if (r != red) paint(r)
            fill.scaleX = (msLeft.toFloat() / totalMs).coerceIn(0f, 1f)
        }
    }

    /** The word card: the secret word, or a hidden face with a line of text. */
    protected fun wordCard(word: NameGuessWords.Word?, hidden: String?): FrameLayout {
        val ctx = context
        val card = FrameLayout(ctx)
        card.background = themeBg(26f)
        G.shadow(card, 10f, NameGuess.GLOW, 26f)
        card.minimumHeight = G.dpi(170f)
        val col = G.vbox(ctx, 0f, Gravity.CENTER)
        col.pad(20f)
        if (hidden != null) {
            col.addView(G.icon(ctx, "Eye", G.ca("#ffffff", 0.9f), 32f))
            col.addView(G.text(ctx, hidden, 16f, Color.WHITE).apply { gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 8f))
        } else if (word != null) {
            col.addView(G.text(ctx, (NameGuessWords.CATEGORIES[word.cat] ?: "").uppercase(), 12f, G.ca("#ffffff", 0.85f)).apply { letterSpacing = 0.025f; gravity = Gravity.CENTER })
            col.addView(G.text(ctx, word.hi, 40f, Color.WHITE).lineH(1.25f).apply { gravity = Gravity.CENTER; setShadowLayer(G.dp(10f), 0f, G.dp(2f), G.ca("#000000", 0.25f)) }, G.lp(G.WRAP, G.WRAP).m(t = 4f))
            col.addView(G.text(ctx, word.en, 20f, G.ca("#ffffff", 0.9f)).apply { gravity = Gravity.CENTER })
        }
        card.addView(col, FrameLayout.LayoutParams(G.MATCH, G.dpi(170f)).apply { gravity = Gravity.CENTER })
        col.minimumHeight = G.dpi(170f)
        (col.layoutParams as FrameLayout.LayoutParams).height = G.WRAP
        return card
    }

    protected fun summary(names: List<String>, scores: List<Int>, onAgain: (() -> Unit)?): View {
        val ctx = context
        val box = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        box.background = G.round(G.ca("#ffffff", 0.05f), 26f, G.ca("#ffffff", 0.12f), 1f)
        box.pad(20f)
        val tie = scores[0] == scores[1]
        val w = if (scores[0] > scores[1]) 0 else 1
        box.addView(G.icon(ctx, "Trophy", G.c("#fcd34d"), 48f))
        val title = when {
            tie -> "It's a tie!"
            names[w].lowercase() == "you" -> "You win!"
            else -> "${names[w]} wins!"
        }
        box.addView(G.text(ctx, title, 22f, Color.WHITE).apply { gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 8f))
        val total = scores[0] + scores[1]
        box.addView(G.text(ctx, "${NameGuess.TOTAL_ROUNDS} rounds · $total points together", 13f, G.ca("#ffffff", 0.6f), bold = false), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        val badge = G.text(ctx, if (total >= 60) "🧠 Mind Readers" else if (total >= 30) "✨ Great Team" else "🙌 Good Sport", 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = themeBg(99f); pad(14f, 0f, 14f, 0f)
        }
        box.addView(badge, LinearLayout.LayoutParams(G.WRAP, G.dpi(36f)).m(t = 12f))
        val btns = G.vbox(ctx, 8f)
        if (onAgain != null) btns.addView(button("Play again", "green", "RotateCcw") { onAgain() }, G.lp(G.MATCH, G.dpi(48f)))
        btns.addView(button("Done", "ghost", h = 44f, semibold = true) { layer.close(this) }, G.lp(G.MATCH, G.dpi(44f)))
        box.addView(btns, G.lp(G.MATCH, G.WRAP).m(t = 20f))
        return box
    }

    protected fun rulesList(): View {
        val ctx = context
        val l = G.vbox(ctx, 4f)
        NameGuess.RULES.forEachIndexed { i, r ->
            l.addView(G.text(ctx, "${i + 1}. $r", 13f, G.ca("#ffffff", 0.8f), bold = false).lineH(1.6f), G.lp(G.MATCH, G.WRAP))
        }
        return l
    }

    protected fun input(hint: String, maxLen: Int, numeric: Boolean = false, sizeDp: Float = 15f): EditText = EditText(context).apply {
        this.hint = hint
        setHintTextColor(G.ca("#ffffff", 0.35f))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, G.dp(sizeDp))
        typeface = app.truefind.android.Ui.regular
        background = G.round(G.ca("#ffffff", 0.08f), 16f, G.ca("#ffffff", 0.15f), 1f)
        pad(16f, 0f, 16f, 0f)
        isSingleLine = true
        filters = arrayOf(InputFilter.LengthFilter(maxLen))
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
    }

    /** A frame-by-frame ticker (timers) that stops with the screen. */
    protected fun ticker(f: () -> Unit): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { if (alive) f() }
        start()
        anims.add(this)
    }
}

// ───────────────────────── One phone ─────────────────────────
@SuppressLint("ViewConstructor")
class NameGuessLocal(ctx: Context, layer: GamesLayer) : NgScreen(ctx, layer) {
    private val names = arrayListOf("You", "Partner")
    private var deck = NameGuessWords.WORDS.shuffled()
    private var idx = 0
    private var round = 1
    private val scores = intArrayOf(0, 0)
    private var phase = "handoff" // handoff | playing | result | finished
    private var peek = false
    private var skips = NameGuess.SKIPS
    private var endsAt = 0L
    private var result = ""
    private var tick: ValueAnimator? = null
    private var timer: TimerBar? = null
    private var peekCard: FrameLayout? = null
    private var scoresBox: FrameLayout? = null
    private var skipBtn: LinearLayout? = null
    private var lines: TextView? = null
    private var correctBtn: LinearLayout? = null

    private val hinter get() = (round - 1) % 2
    private val guesser get() = 1 - hinter
    private val word get() = deck[idx % deck.size]
    private fun isYou(n: String) = n.trim().lowercase() == "you"

    init { render() }

    private fun render() {
        body.removeAllViews()
        tick?.cancel(); tick = null; timer = null; peekCard = null
        val ctx = context
        sub(if (phase == "finished") "Game over" else "Round $round of ${NameGuess.TOTAL_ROUNDS} · one phone")
        // names (editable)
        val nameRow = G.hbox(ctx, 10f)
        for (i in 0..1) {
            val e = input("", 14, sizeDp = 13f).apply {
                setText(names[i])
                background = G.round(G.ca("#ffffff", 0.05f), 12f, G.ca("#ffffff", 0.1f), 1f)
                pad(12f, 0f, 12f, 0f)
                contentDescription = "Player ${i + 1} name"
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { names[i] = s?.toString().orEmpty(); paintScores(); paintLines() }
                })
            }
            nameRow.addView(e, G.lpw(1f, G.dpi(36f)))
        }
        body.addView(nameRow, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        scoresBox = FrameLayout(ctx).also { body.addView(it, G.lp(G.MATCH, G.WRAP).m(t = 16f)) }
        paintScores()
        when (phase) {
            "handoff" -> {
                val youH = isYou(names[hinter])
                body.addView(wordCard(null, if (youH) "Phone apne paas rakho. Sirf tum shabd dekhoge!" else "Phone ${names[hinter]} ko do. Sirf ${names[hinter]} shabd dekhe!"), G.lp(G.MATCH, G.WRAP).m(t = 16f))
                body.addView(button(if (youH) "Start round" else "I'm ${names[hinter]}, start round") { startRound() }, blp(48f))
            }
            "playing" -> {
                timer = TimerBar(ctx, NameGuess.ROUND_SECONDS * 1000L).also { body.addView(it, G.lp(G.MATCH, G.WRAP).m(t = 12f)) }
                val holder = FrameLayout(ctx)
                peekCard = holder
                holder.contentDescription = "Hold to see the word"
                holder.isClickable = true
                holder.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { setPeek(true); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { setPeek(false); true }
                        else -> true
                    }
                }
                body.addView(holder, G.lp(G.MATCH, G.WRAP))
                setPeek(false)
                lines = G.text(ctx, "", 13.5f, G.ca("#ffffff", 0.75f), bold = false).apply { gravity = Gravity.CENTER }
                body.addView(lines, G.lp(G.MATCH, G.WRAP).m(t = 12f))
                paintLines()
                correctBtn = button("${names[guesser]} ne sahi bataya", "green", "Check") { correct() }.also { body.addView(it, blp(48f)) }
                skipBtn = button("New word ($skips left)", "ghost", "SkipForward", 44f, semibold = true) { skip() }.also { body.addView(it, blp(44f, 10f)) }
                enable(skipBtn!!, skips > 0)
                tick = ticker { onTick() }
            }
            "result" -> {
                val ok = result == "correct"
                body.addView(G.text(ctx, if (ok) "Sahi! +${NameGuess.PTS_GUESSER} ${names[guesser]}, +${NameGuess.PTS_HINTER} ${names[hinter]}" else "Time up!", 20f, if (ok) G.c("#6ee7b7") else G.c("#fca5a5")).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP).m(t = 16f))
                body.addView(wordCard(word, null), G.lp(G.MATCH, G.WRAP).m(t = 16f))
                body.addView(button(if (round >= NameGuess.TOTAL_ROUNDS) "See result" else "Next round") { next() }, blp(48f))
            }
            "finished" -> body.addView(summary(names.toList(), scores.toList(), onAgain = { again() }), G.lp(G.MATCH, G.WRAP).m(t = 20f))
        }
    }
    private fun paintScores() {
        val box = scoresBox ?: return
        box.removeAllViews()
        box.addView(scores(names.toList(), scores.toList(), if (phase == "finished") -1 else hinter))
    }
    private fun paintLines() {
        val l = lines ?: return
        val youH = isYou(names[hinter]); val youG = isYou(names[guesser])
        l.text = (if (youH) "Tum hint do, ${names[guesser]} bujhe." else if (youG) "${names[hinter]} hint de, tum bujho." else "${names[hinter]} hint de, ${names[guesser]} bujhe.") + " Shabd khud mat bolna!"
    }
    private fun setPeek(on: Boolean) {
        peek = on
        val h = peekCard ?: return
        h.removeAllViews()
        val youH = isYou(names[hinter])
        h.addView(if (on) wordCard(word, null) else wordCard(null, if (youH) "Dabaa kar rakho, shabd dikhega" else "${names[hinter]}: dabaa kar rakho, shabd dikhega"),
            FrameLayout.LayoutParams(G.MATCH, G.WRAP).m(t = 16f))
    }

    private fun onTick() {
        if (phase != "playing") return
        val left = max(0L, endsAt - System.currentTimeMillis())
        timer?.set(left)
        if (left <= 0L) { result = "timeout"; phase = "result"; peek = false; render() }
    }
    private fun nextWord() { idx += 1; if (idx % deck.size == 0) deck = NameGuessWords.WORDS.shuffled() }
    private fun startRound() { skips = NameGuess.SKIPS; endsAt = System.currentTimeMillis() + NameGuess.ROUND_SECONDS * 1000L; phase = "playing"; render() }
    private fun correct() {
        scores[guesser] += NameGuess.PTS_GUESSER; scores[hinter] += NameGuess.PTS_HINTER
        result = "correct"; phase = "result"; peek = false; render()
    }
    private fun skip() {
        if (skips <= 0) return
        skips -= 1; nextWord()
        skipBtn?.let { b -> (b.getChildAt(b.childCount - 1) as? TextView)?.text = "New word ($skips left)"; enable(b, skips > 0) }
        setPeek(peek)
    }
    private fun next() {
        if (round >= NameGuess.TOTAL_ROUNDS) { phase = "finished"; render(); return }
        round += 1; nextWord(); phase = "handoff"; render()
    }
    private fun again() { deck = NameGuessWords.WORDS.shuffled(); idx = 0; round = 1; scores[0] = 0; scores[1] = 0; phase = "handoff"; render() }

    override fun destroy() { tick?.cancel(); super.destroy() }
}

// ───────────────────────── Two phones (server synced) ─────────────────────────
@SuppressLint("ViewConstructor")
class NameGuessOnline(ctx: Context, layer: GamesLayer) : NgScreen(ctx, layer) {
    private var game: JSONObject? = null
    private var busy = false
    private var error = ""
    private var note = ""
    private var clockLeft = 0L
    private var clockAt = 0L
    private var pollRun = 0
    private var polling = false
    private var renderKey = ""
    private var noteHide: Runnable? = null
    private var timer: TimerBar? = null
    private var tick: ValueAnimator? = null
    private var noteView: TextView? = null
    private var guessInput: EditText? = null
    private var codeInput: EditText? = null
    private val actionBtns = ArrayList<View>()
    private var createBtn: LinearLayout? = null
    private var createSpin: View? = null
    private var pollKey = ""

    init { render() }

    private fun call(action: String, extra: JSONObject = JSONObject(), done: (app.truefind.android.Api.Result) -> Unit) {
        val body = JSONObject().put("action", action)
        val keys = extra.keys(); while (keys.hasNext()) { val k = keys.next(); body.put(k, extra.get(k)) }
        GameApi.call("nameGuess", body) { r ->
            if (!alive) return@call
            // "not right now" answers carry the game as it is now: show it
            r.data.obj("game")?.let { show(it) }
            done(r)
        }
    }
    private fun show(g: JSONObject) {
        game = g
        clockLeft = g.long("time_left_ms"); clockAt = System.currentTimeMillis()
        poll()
        render()
    }
    private fun err(r: app.truefind.android.Api.Result, f: String): String {
        if (r.status == 404 && r.data.str("error").isEmpty()) return "2-phone game abhi setup nahi hua. Abhi 'Play on one phone' se khelo."
        return r.data.str("error").ifEmpty { if (r.status == 0) "Please check your internet and try again." else f }
    }
    private fun run(action: String, extra: JSONObject = JSONObject(), fallback: String = "That didn't work. Try again.", then: (JSONObject) -> Unit = {}) {
        if (busy) return
        busy = true; error = ""; paintBusy()
        call(action, extra) { r ->
            busy = false
            if (r.ok) then(r.data) else error = err(r, fallback)
            render(force = true)
        }
    }
    private fun flash(t: String) {
        note = t; paintNote()
        cancel(noteHide)
        noteHide = later(1800) { note = ""; paintNote() }
    }

    // poll while a game is open (every 1.2 s, one question at a time)
    private fun poll() {
        val g = game
        val open = g != null && g.str("id").isNotEmpty() && g.str("status") !in listOf("finished", "cancelled")
        if (!open) { pollRun++; pollKey = ""; return }
        if (pollKey == g!!.str("id")) return
        pollKey = g.str("id")
        val run = ++pollRun
        fun beat() {
            if (!alive || run != pollRun) return
            val cur = game ?: return
            if (!polling) {
                polling = true
                call("state", JSONObject().put("game_id", cur.str("id"))) { polling = false }
            }
            later(1200) { beat() }
        }
        later(1200) { beat() }
    }

    private fun msLeft() = max(0L, clockLeft - (System.currentTimeMillis() - clockAt))
    private fun myIdx() = if (game.str("me") == "guest") 1 else 0
    private fun names() = listOf(game.obj("names").str("host"), game.obj("names").str("guest"))
    private fun display(i: Int) = if (i == myIdx()) "You" else names()[i].ifEmpty { "Partner" }

    override fun exit() {
        val g = game
        if (g != null && g.str("id").isNotEmpty() && g.str("status") !in listOf("finished", "cancelled")) {
            G.confirm(context, "Leave the game? It will end for both players.") {
                call("leave", JSONObject().put("game_id", g.str("id"))) { }
                layer.close(this)
            }
            return
        }
        layer.close(this)
    }
    override fun onBack(): Boolean {
        val g = game
        if (g != null && g.str("id").isNotEmpty() && g.str("status") !in listOf("finished", "cancelled")) { exit(); return true }
        return false
    }
    override fun leave() {
        // the screen goes away (the app left Games): end the game for both, like the web's Exit
        val g = game
        pollRun++
        if (g != null && g.str("id").isNotEmpty() && g.str("status") !in listOf("finished", "cancelled")) {
            GameApi.call("nameGuess", JSONObject().put("action", "leave").put("game_id", g.str("id"))) { }
        }
    }

    private fun render(force: Boolean = false) {
        val g = game
        val st = g?.str("status").orEmpty()
        val key = "${g?.str("id")}|$st|${g.int("round")}|${g.str("my_role")}|${g.obj("word").str("hi")}|${g.int("skips_left")}|${g.str("hinter")}|" +
            "${g.obj("scores").int("host")}|${g.obj("scores").int("guest")}|${g.obj("names").str("guest")}|${g.str("last_result")}"
        if (!force && key == renderKey) { paintBusy(); return }
        renderKey = key
        val keepGuess = guessInput?.text?.toString().orEmpty()
        val keepCode = codeInput?.text?.toString().orEmpty()
        body.removeAllViews(); actionBtns.clear(); createBtn = null; createSpin = null
        tick?.cancel(); tick = null; timer = null; guessInput = null; codeInput = null
        val ctx = context
        if (g == null) {
            sub("Two phones · 1 vs 1")
            val create = G.vbox(ctx).apply { background = G.round(G.ca("#ffffff", 0.05f), 22f, G.ca("#ffffff", 0.12f), 1f); pad(16f) }
            create.addView(G.text(ctx, "New game", 15f, Color.WHITE))
            create.addView(G.text(ctx, "Get a code and share it with your partner.", 13f, G.ca("#ffffff", 0.6f), bold = false), G.lp(G.WRAP, G.WRAP).m(t = 2f))
            val cb = button("Create game", "theme") { run("create", fallback = "That didn't work. Try again.") }
            // web: while busy the button shows the white loader instead of its words
            createSpin = TfSpinner(ctx, 20f, white = true).also { cb.addView(it, LinearLayout.LayoutParams(G.dpi(20f), G.dpi(20f))) }
            createBtn = cb
            actionBtns.add(cb)
            create.addView(cb, blp(48f, 12f))
            body.addView(create, G.lp(G.MATCH, G.WRAP).m(t = 20f))
            val join = G.vbox(ctx).apply { background = G.round(G.ca("#ffffff", 0.05f), 22f, G.ca("#ffffff", 0.12f), 1f); pad(16f) }
            join.addView(G.text(ctx, "Join with a code", 15f, Color.WHITE))
            val row = G.hbox(ctx, 8f)
            val code = input("1234", 4, numeric = true, sizeDp = 20f).apply {
                typeface = app.truefind.android.Ui.bold; letterSpacing = 0.3f; contentDescription = "Game code"
                setText(keepCode)
            }
            codeInput = code
            row.addView(code, G.lpw(1f, G.dpi(48f)))
            val jb = button("Join", "green") { run("join", JSONObject().put("code", code.text.toString())) }.apply { pad(20f, 0f, 20f, 0f) }
            actionBtns.add(jb)
            row.addView(jb, LinearLayout.LayoutParams(G.WRAP, G.dpi(48f)))
            fun sync() = enable(jb, code.text.length == 4 && !busy)
            code.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { sync() }
            })
            sync()
            join.addView(row, G.lp(G.MATCH, G.WRAP).m(t = 12f))
            body.addView(join, G.lp(G.MATCH, G.WRAP).m(t = 12f))
            noteLine()
            body.addView(G.text(ctx, "Rules", 14f, Color.WHITE), G.lp(G.WRAP, G.WRAP).m(t = 20f, b = 6f))
            body.addView(rulesList())
            return
        }
        val myIdx = myIdx()
        val hinterIdx = when (g.str("hinter")) { "guest" -> 1; "host" -> 0; else -> -1 }
        sub(when (st) { "playing", "between" -> "Round ${g.int("round")} of ${g.int("total_rounds")}"; "finished" -> "Game over"; else -> "Two phones · 1 vs 1" })
        val sc = listOf(g.obj("scores").int("host"), g.obj("scores").int("guest"))
        body.addView(scores(listOf(display(0), display(1)), sc, if (st == "playing" || st == "between") hinterIdx else -1), G.lp(G.MATCH, G.WRAP).m(t = 16f))
        when (st) {
            "waiting" -> {
                val box = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply { background = G.round(G.ca("#ffffff", 0.05f), 22f, G.ca("#ffffff", 0.12f), 1f); pad(20f) }
                box.addView(G.text(ctx, "Share this code with your partner", 13f, G.ca("#ffffff", 0.7f), bold = false).apply { gravity = Gravity.CENTER })
                box.addView(G.text(ctx, g.str("code"), 44f, Color.WHITE).apply { letterSpacing = 0.25f }, G.lp(G.WRAP, G.WRAP).m(t = 4f))
                val copy = G.hbox(ctx, 6f, Gravity.CENTER).apply { background = ghostBg(99f); pad(16f, 0f, 16f, 0f) }
                copy.addView(G.icon(ctx, "Copy", Color.WHITE, 16f))
                copy.addView(G.text(ctx, "Copy code", 13f, Color.WHITE))
                G.click(copy) {
                    try {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", g.str("code")))
                        flash("Code copied")
                    } catch (e: Exception) { }
                }
                box.addView(copy, LinearLayout.LayoutParams(G.WRAP, G.dpi(36f)).m(t = 4f))
                val w = G.hbox(ctx, 8f, Gravity.CENTER)
                w.addView(TfSpinner(ctx, 16f, white = true), LinearLayout.LayoutParams(G.dpi(16f), G.dpi(16f)))
                w.addView(G.text(ctx, "Waiting for partner to join…", 13f, G.ca("#ffffff", 0.6f), bold = false))
                box.addView(w, G.lp(G.WRAP, G.WRAP).m(t = 16f))
                body.addView(box, G.lp(G.MATCH, G.WRAP).m(t = 20f))
            }
            "ready" -> {
                val c = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
                c.addView(G.text(ctx, if (myIdx == 0) "${display(1)} joined! 🎉" else "You joined ${display(0)}'s game! 🎉", 16f, Color.WHITE).apply { gravity = Gravity.CENTER })
                c.addView(G.text(ctx, if (myIdx == 0) "You give hints first." else "${display(0)} gives hints first.", 13f, G.ca("#ffffff", 0.6f), bold = false), G.lp(G.WRAP, G.WRAP).m(t = 4f))
                val sb = button("Start game") { run("start", JSONObject().put("game_id", g.str("id"))) }
                actionBtns.add(sb)
                c.addView(sb, blp(48f))
                c.addView(rulesList(), G.lp(G.MATCH, G.WRAP).m(t = 20f))
                body.addView(c, G.lp(G.MATCH, G.WRAP).m(t = 20f))
            }
            "playing" -> {
                timer = TimerBar(ctx, NameGuess.ROUND_SECONDS * 1000L).also { body.addView(it, G.lp(G.MATCH, G.WRAP).m(t = 12f)) }
                if (g.str("my_role") == "hinter") {
                    val w = g.obj("word")
                    val word = NameGuessWords.Word("", w.str("hi"), w.str("en"), w.str("cat"), emptyList())
                    body.addView(wordCard(word, null), G.lp(G.MATCH, G.WRAP).m(t = 16f))
                    body.addView(G.text(ctx, "Hint do, par shabd ya uska hissa mat bolna!", 13.5f, G.ca("#ffffff", 0.75f), bold = false).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP).m(t = 12f))
                    val ok = button("${display(1 - myIdx)} ne sahi bola", "green", "Check") { run("correct", JSONObject().put("game_id", g.str("id"))) }
                    actionBtns.add(ok); body.addView(ok, blp(48f))
                    val sk = button("New word (${g.int("skips_left")} left)", "ghost", "SkipForward", 44f, semibold = true) { run("skip", JSONObject().put("game_id", g.str("id"))) }
                    body.addView(sk, blp(44f, 10f))
                    enable(sk, !busy && g.int("skips_left") > 0)
                } else {
                    body.addView(wordCard(null, "${display(hinterIdx)} ke hint suno aur naam bujho!"), G.lp(G.MATCH, G.WRAP).m(t = 16f))
                    val row = G.hbox(ctx, 8f)
                    val gi = input("Jawab type karo (ya bol do)", 40).apply { setText(keepGuess); contentDescription = "Your guess" }
                    guessInput = gi
                    row.addView(gi, G.lpw(1f, G.dpi(48f)))
                    val send = FrameLayout(ctx).apply {
                        background = greenBg(16f); contentDescription = "Send guess"
                        addView(G.icon(ctx, "Send", Color.WHITE, 20f), FrameLayout.LayoutParams(G.dpi(20f), G.dpi(20f), Gravity.CENTER))
                    }
                    G.click(send) { sendGuess() }
                    row.addView(send, LinearLayout.LayoutParams(G.dpi(48f), G.dpi(48f)))
                    fun sync() = enable(send, gi.text.toString().trim().isNotEmpty() && !busy)
                    gi.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) { sync() }
                    })
                    gi.setOnEditorActionListener { _, _, _ -> sendGuess(); true }
                    sync()
                    body.addView(row, G.lp(G.MATCH, G.WRAP).m(t = 16f))
                }
                tick = ticker { timer?.set(msLeft()) }
            }
            "between" -> {
                val ok = g.str("last_result") == "correct"
                body.addView(G.text(ctx, if (ok) "Sahi! +${NameGuess.PTS_GUESSER} / +${NameGuess.PTS_HINTER}" else "Time up!", 20f, if (ok) G.c("#6ee7b7") else G.c("#fca5a5")).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP).m(t = 16f))
                val w = g.obj("word")
                body.addView(wordCard(NameGuessWords.Word("", w.str("hi"), w.str("en"), w.str("cat"), emptyList()), null), G.lp(G.MATCH, G.WRAP).m(t = 16f))
                val nb = button(if (g.int("round") >= g.int("total_rounds")) "See result" else "Next round") { run("next", JSONObject().put("game_id", g.str("id"))) }
                actionBtns.add(nb); body.addView(nb, blp(48f))
            }
            "finished" -> body.addView(summary(listOf(display(0), display(1)), sc, null), G.lp(G.MATCH, G.WRAP).m(t = 20f))
            "cancelled" -> {
                val c = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
                c.addView(G.text(ctx, "The game ended", 17f, Color.WHITE).apply { gravity = Gravity.CENTER })
                c.addView(G.text(ctx, NameGuess.END_TEXT[g.str("end_reason")] ?: NameGuess.END_TEXT["left"]!!, 13f, G.ca("#ffffff", 0.6f), bold = false).apply { gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 4f))
                val d = button("Done", "ghost", h = 44f, semibold = true) { layer.close(this) }.apply { pad(24f, 0f, 24f, 0f) }
                c.addView(d, LinearLayout.LayoutParams(G.WRAP, G.dpi(44f)).m(t = 16f))
                body.addView(c, G.lp(G.MATCH, G.WRAP).m(t = 24f))
            }
        }
        noteLine()
        paintBusy()
    }

    private fun noteLine() {
        noteView = G.text(context, "", 13.5f, Color.WHITE).apply { gravity = Gravity.CENTER }
        body.addView(noteView, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        paintNote()
    }
    private fun paintNote() {
        val v = noteView ?: return
        val t = error.ifEmpty { note }
        v.text = t
        v.setTextColor(if (error.isNotEmpty()) G.c("#fecaca") else Color.WHITE)
        v.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun paintBusy() {
        for (b in actionBtns) enable(b, !busy && (b !== actionBtns.getOrNull(1) || game != null || (codeInput?.text?.length ?: 0) == 4))
        createBtn?.let { cb ->
            if (!cb.isEnabled) cb.alpha = 0.6f // web: disabled:opacity-60 (Join: 40)
            cb.getChildAt(0)?.visibility = if (busy) View.GONE else View.VISIBLE
            createSpin?.visibility = if (busy) View.VISIBLE else View.GONE
        }
        paintNote()
    }

    private fun sendGuess() {
        val g = game ?: return
        val t = guessInput?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty() || busy) return
        busy = true; error = ""
        call("guess", JSONObject().put("game_id", g.str("id")).put("text", t)) { r ->
            busy = false
            if (r.ok) {
                guessInput?.setText("")
                flash(if (r.data.bool("right")) "Sahi jawab! 🎉" else "“$t” nahi hai, phir try karo")
            } else { error = err(r, "That didn't work. Try again."); paintNote() }
        }
    }

    override fun destroy() { tick?.cancel(); super.destroy() }
}

// ───────────────────────── /games/clue-me: Clue Me (clues one by one) ─────────────────────────
@SuppressLint("ViewConstructor")
class ClueMePage(ctx: Context, layer: GamesLayer) : GameScreen(ctx, layer) {
    private var screen = "setup" // setup | playing | over
    private var category = "mixed"
    private var deck: List<ClueMeData.Item> = emptyList()
    private var index = 0
    private var score = 0
    private var solved = 0
    private val prefs = ctx.getSharedPreferences("games", Context.MODE_PRIVATE)
    private var best = prefs.getInt("clueme_best", 0)
    private val content = G.vbox(ctx)
    private val scorePill: TextView
    private val scroll = scrollPage(ctx)
    private val pinkSoft = G.c("#f9a8d4")
    private val btnGrad = listOf<CssBg.Layer>(CssBg.Linear(90f, intArrayOf(G.c("#ff4d8d"), G.c("#a855f7"))))

    // round state
    private var revealed = 1
    private var status = "playing" // playing | correct | revealed | timeout
    private var points = 0
    private var timeLeft = ClueMeData.SECONDS_PER_ROUND
    private var secTimer: Runnable? = null
    private var wrong = ""
    private var timerText: TextView? = null
    private var timerFill: View? = null

    init {
        background = CssBg(0f, listOf(
            CssBg.Linear(180f, intArrayOf(G.c("#0d0b2a"), G.c("#07071a"), G.c("#060615")), floatArrayOf(0f, 0.55f, 1f)),
            CssBg.Radial(0f, 1f, 0.9f, 0.3f, intArrayOf(G.ca("#7c3aed", 0.28f), G.ca("#7c3aed", 0f)), floatArrayOf(0f, 0.7f)),
            CssBg.Radial(0.5f, 0f, 1.1f, 0.34f, intArrayOf(G.ca("#ff4d8d", 0.28f), G.ca("#ff4d8d", 0f)), floatArrayOf(0f, 0.62f)),
        ))
        isClickable = true
        val col = G.vbox(ctx)
        col.pad(20f, 20f, 20f, 0f)
        val head = G.hbox(ctx, 12f)
        val back = FrameLayout(ctx).apply {
            background = G.oval(G.ca("#12102a", 0.9f), G.ca("#8c96ff", 0.3f), 1f)
            contentDescription = "Back"
            addView(G.icon(ctx, "ChevronLeft", Color.WHITE, 24f), FrameLayout.LayoutParams(G.dpi(24f), G.dpi(24f), Gravity.CENTER))
        }
        G.click(back, 0.95f) { goBack() }
        head.addView(back, LinearLayout.LayoutParams(G.dpi(44f), G.dpi(44f)))
        val tc = G.vbox(ctx)
        tc.addView(G.text(ctx, "Clue Me", 22f, Color.WHITE).lineH(1.25f))
        tc.addView(G.text(ctx, "Guess the name from the clues", 12.5f, pinkSoft, bold = false))
        head.addView(tc, G.lpw(1f))
        scorePill = G.text(ctx, "", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = G.round(G.ca("#120c24", 0.9f), 99f, G.ca("#ff4d8d", 0.6f), 1f); pad(12f, 0f, 12f, 0f)
        }
        head.addView(scorePill, LinearLayout.LayoutParams(G.WRAP, G.dpi(36f)))
        col.addView(head, G.lp(G.MATCH, G.WRAP))
        col.addView(content, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        scroll.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        addView(scroll, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        render()
    }

    private fun goBack() { if (screen == "playing") { stopTimer(); screen = "setup"; render() } else layer.close(this) }
    override fun onBack(): Boolean { if (screen == "playing") { goBack(); return true }; return false }

    private fun render() {
        content.removeAllViews()
        scorePill.visibility = if (screen == "playing") View.VISIBLE else View.GONE
        scorePill.text = "$score pts"
        when (screen) {
            "setup" -> setup()
            "playing" -> round()
            "over" -> result()
        }
    }

    private fun start(key: String) {
        category = key
        val pool = ClueMeData.ITEMS[key] ?: ClueMeData.ITEMS.values.flatten()
        val picked = ArrayList(pool.shuffled().take(ClueMeData.ROUNDS))
        while (picked.size < ClueMeData.ROUNDS) picked.add(pool.shuffled()[0])
        deck = picked.mapIndexed { i, x -> ClueMeData.Item(x.answer, x.aliases, x.clues, "$i-${x.answer}") }
        index = 0; score = 0; solved = 0
        screen = "playing"; newRound(); render()
    }
    private fun finish(final: Int) {
        if (final > best) { best = final; prefs.edit().putInt("clueme_best", final).apply() }
        screen = "over"
    }
    private fun completeRound(pts: Int) {
        score += pts; if (pts > 0) solved += 1
        if (index + 1 >= deck.size) finish(score) else { index += 1; newRound() }
        render()
    }

    // ── setup ──
    private fun setup() {
        val ctx = context
        val card = G.vbox(ctx)
        card.background = CssBg(24f, listOf(CssBg.Linear(140f, intArrayOf(G.ca("#ff4d8d", 0.18f), G.ca("#7c3aed", 0.16f), G.ca("#0e0d26", 0.9f)), floatArrayOf(0f, 0.6f, 1f))), border = G.ca("#ffffff", 0.1f), borderDp = 1f)
        card.pad(20f)
        card.addView(G.text(ctx, "CLUE ME", 13f, G.ca("#ffffff", 0.7f)).apply { letterSpacing = 0.025f })
        card.addView(G.text(ctx, "Guess what the clues are hiding", 26f, Color.WHITE).lineH(1.25f), G.lp(G.MATCH, G.WRAP).m(t = 4f))
        card.addView(G.text(ctx, "Read the clue, guess the name. Ask for more clues and your points drop.", 13.5f, G.ca("#ffffff", 0.7f), bold = false).lineH(1.375f), G.lp(G.MATCH, G.WRAP).m(t = 8f))
        val stats = G.hbox(ctx, 8f)
        listOf(Triple("Target", "${ClueMeData.ROUNDS}", "Rounds"), Triple("Flame", "${ClueMeData.SECONDS_PER_ROUND}s", "Per round"), Triple("Trophy", "$best", "Best score")).forEach { (ic, v, l) ->
            val s = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply { background = G.round(G.ca("#000000", 0.28f), 16f, G.ca("#ffffff", 0.1f), 1f); pad(8f, 10f, 8f, 10f) }
            s.addView(G.icon(ctx, ic, pinkSoft, 16f))
            s.addView(G.text(ctx, v, 16f, Color.WHITE), G.lp(G.WRAP, G.WRAP).m(t = 6f))
            s.addView(G.text(ctx, l, 11f, G.ca("#ffffff", 0.5f), bold = false), G.lp(G.WRAP, G.WRAP).m(t = 4f))
            stats.addView(s, G.lpw(1f))
        }
        card.addView(stats, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        content.addView(card, G.lp(G.MATCH, G.WRAP).m(b = 20f))
        content.addView(G.text(ctx, "Pick a category", 16f, Color.WHITE), G.lp(G.WRAP, G.WRAP).m(b = 10f))
        var row: LinearLayout? = null
        ClueMeData.CATEGORIES.forEachIndexed { i, c ->
            if (i % 2 == 0) { row = G.hbox(ctx, 10f, Gravity.TOP); content.addView(row, G.lp(G.MATCH, G.WRAP).m(b = 10f)) }
            val b = G.vbox(ctx).apply {
                background = G.round(G.ca("#141230", 0.85f), 16f, G.ca("#ffffff", 0.12f), 1f); pad(14f)
                G.shadow(this, 4f, G.c("#ff4d8d"), 16f)
            }
            b.addView(G.text(ctx, c.emoji, 24f, Color.WHITE, bold = false))
            b.addView(G.text(ctx, c.label, 15f, Color.WHITE), G.lp(G.WRAP, G.WRAP).m(t = 8f))
            b.addView(G.text(ctx, c.blurb, 11.5f, G.ca("#ffffff", 0.5f), bold = false), G.lp(G.WRAP, G.WRAP).m(t = 2f))
            G.click(b, 0.97f) { start(c.key) }
            row?.addView(b, G.lpw(1f))
        }
        val tip = G.hbox(ctx, 6f, Gravity.CENTER)
        tip.addView(G.text(ctx, "Tap a category to start", 12f, G.ca("#ffffff", 0.45f), bold = false))
        tip.addView(G.icon(ctx, "ArrowRight", G.ca("#ffffff", 0.45f), 14f))
        content.addView(tip, G.lp(G.MATCH, G.WRAP).m(t = 10f, b = 32f))
    }

    // ── one round ──
    private fun newRound() { revealed = 1; status = "playing"; points = 0; timeLeft = ClueMeData.SECONDS_PER_ROUND; wrong = ""; startTimer() }
    private fun startTimer() {
        stopTimer()
        secTimer = later(1000) { tickSec() }
    }
    private fun stopTimer() { cancel(secTimer); secTimer = null }
    private fun tickSec() {
        if (screen != "playing" || status != "playing") return
        timeLeft = if (timeLeft <= 1) 0 else timeLeft - 1
        if (timeLeft == 0) { status = "timeout"; render(); return }
        paintTimer()
        secTimer = later(1000) { tickSec() }
    }
    private fun paintTimer() {
        val done = status != "playing"
        timerText?.let { it.text = "${timeLeft}s"; it.setTextColor(if (timeLeft <= 10 && !done) G.c("#f87171") else G.ca("#ffffff", 0.7f)) }
        timerFill?.let { f ->
            f.background = CssBg(99f, listOf(CssBg.Linear(90f, if (timeLeft <= 10) intArrayOf(G.c("#f87171"), G.c("#ef4444")) else intArrayOf(G.c("#ff4d8d"), G.c("#a855f7")))))
            f.animate().scaleX(timeLeft.toFloat() / ClueMeData.SECONDS_PER_ROUND).setDuration(1000).setInterpolator(LinearInterpolator()).start()
        }
    }

    private fun round() {
        val ctx = context
        val item = deck.getOrNull(index) ?: return
        val total = max(deck.size, ClueMeData.ROUNDS)
        val head = G.hbox(ctx)
        head.addView(G.text(ctx, "Round ${index + 1} of $total", 12.5f, G.ca("#ffffff", 0.6f)), G.lpw(1f))
        val tm = G.hbox(ctx, 4f)
        val tIcon = G.icon(ctx, "Timer", G.ca("#ffffff", 0.7f), 16f)
        tm.addView(tIcon)
        timerText = G.text(ctx, "", 13f, Color.WHITE).also { tm.addView(it) }
        head.addView(tm)
        content.addView(head, G.lp(G.MATCH, G.WRAP).m(b = 8f))
        val track = FrameLayout(ctx).apply { background = G.round(G.ca("#ffffff", 0.1f), 99f); clipToOutline = true }
        val fillV = View(ctx).apply { pivotX = 0f; scaleX = timeLeft.toFloat() / ClueMeData.SECONDS_PER_ROUND }
        timerFill = fillV
        track.addView(fillV, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        content.addView(track, G.lp(G.MATCH, G.dpi(6f)).m(b = 16f))
        paintTimer()

        val card = G.vbox(ctx)
        card.background = G.round(G.ca("#141230", 0.9f), 24f, G.ca("#ffffff", 0.12f), 1f)
        G.shadow(card, 8f, G.c("#a855f7"), 24f)
        card.pad(20f)
        card.addView(G.text(ctx, "CLUE $revealed OF 3", 11.5f, G.ca("#ffffff", 0.5f)).apply { letterSpacing = 0.025f }, G.lp(G.WRAP, G.WRAP).m(b = 12f))
        card.addView(G.text(ctx, "“${item.clues.getOrElse(revealed - 1) { "" }}”", 19f, Color.WHITE).lineH(1.375f).apply { minHeight = G.dpi(56f) })
        if (revealed > 1) {
            val prev = G.vbox(ctx, 6f)
            for (c in item.clues.take(revealed - 1)) prev.addView(G.text(ctx, "• $c", 13f, G.ca("#ffffff", 0.45f), bold = false).lineH(1.375f))
            card.addView(prev, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        }
        val done = status != "playing"
        val solveState = status == "correct"
        if (done) {
            stopTimer()
            val box = G.hbox(ctx, 10f).apply {
                background = G.round(if (solveState) G.ca("#10b981", 0.14f) else G.ca("#f43f5e", 0.12f), 16f, if (solveState) G.ca("#10b981", 0.45f) else G.ca("#f43f5e", 0.4f), 1f)
                pad(16f, 12f, 16f, 12f)
            }
            box.addView(G.icon(ctx, if (solveState) "Check" else "X", if (solveState) G.c("#34d399") else G.c("#fb7185"), 20f))
            val tc = G.vbox(ctx)
            tc.addView(G.text(ctx, if (solveState) "Correct! +$points points" else if (status == "timeout") "Time up!" else "Answer revealed", 14f, Color.WHITE))
            val it2 = android.text.SpannableStringBuilder("It was ")
            val a = it2.length; it2.append(item.answer)
            it2.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), a, it2.length, 0)
            it2.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, it2.length, 0)
            tc.addView(G.text(ctx, it2, 13f, G.ca("#ffffff", 0.65f), bold = false))
            box.addView(tc, G.lpw(1f))
            card.addView(box, G.lp(G.MATCH, G.WRAP).m(t = 20f))
            val nb = G.hbox(ctx, 8f, Gravity.CENTER).apply { background = CssBg(16f, btnGrad); G.shadow(this, 8f, G.c("#a855f7"), 16f) }
            nb.addView(G.text(ctx, if (index + 1 == total) "See my score" else "Next clue", 15f, Color.WHITE))
            nb.addView(G.icon(ctx, "ArrowRight", Color.WHITE, 16f))
            G.click(nb, 0.98f) { completeRound(if (solveState) points else 0) }
            card.addView(nb, G.lp(G.MATCH, G.dpi(48f)).m(t = 12f))
        } else {
            val gi = EditText(ctx).apply {
                hint = "Type your guess…"; setHintTextColor(G.ca("#ffffff", 0.35f)); setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, G.dp(15f)); typeface = app.truefind.android.Ui.regular
                background = G.round(G.ca("#ffffff", 0.07f), 16f, G.ca("#ffffff", 0.16f), 1f); pad(16f, 0f, 16f, 0f)
                isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            card.addView(gi, G.lp(G.MATCH, G.dpi(48f)).m(t = 20f))
            val wrongT = G.text(ctx, wrong, 12.5f, G.c("#fda4af"), bold = false).apply { visibility = if (wrong.isEmpty()) View.GONE else View.VISIBLE }
            card.addView(wrongT, G.lp(G.WRAP, G.WRAP).m(t = 6f))
            val guess = G.hbox(ctx, 0f, Gravity.CENTER).apply { background = CssBg(16f, btnGrad); G.shadow(this, 8f, G.c("#ff4d8d"), 16f) }
            guess.addView(G.text(ctx, "Guess", 15f, Color.WHITE))
            fun submit() {
                if (status != "playing") return
                if (isCorrect(item, gi.text.toString())) {
                    points = listOf(30, 20, 10).getOrElse(revealed - 1) { 5 }
                    status = "correct"; render(); return
                }
                wrong = "Not that one, try again"
                wrongT.text = wrong; wrongT.visibility = View.VISIBLE
                wrongT.animate().cancel(); wrongT.translationX = 0f
                // web tfShake .4s ease-in-out: 0 → -6 → 6 → -4 → 4 → 0 px, each fifth eased on its own
                val inOut = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 400; interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener {
                        val t = it.animatedValue as Float
                        val k = floatArrayOf(0f, -6f, 6f, -4f, 4f, 0f)
                        val pos = t * 5f; val i = pos.toInt().coerceAtMost(4); val f = inOut.getInterpolation((pos - i).coerceIn(0f, 1f))
                        wrongT.translationX = G.dp(k[i] + (k[i + 1] - k[i]) * f)
                    }
                    start()
                }
            }
            fun syncGuess() { val on = gi.text.toString().trim().isNotEmpty(); guess.isEnabled = on; guess.alpha = if (on) 1f else 0.45f }
            gi.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { syncGuess() }
            })
            gi.setOnEditorActionListener { _, _, _ -> if (gi.text.toString().trim().isNotEmpty()) submit(); true }
            syncGuess()
            G.click(guess, 0.98f) { submit() }
            card.addView(guess, G.lp(G.MATCH, G.dpi(48f)).m(t = 12f))
            val row = G.hbox(ctx, 8f)
            val more = G.hbox(ctx, 6f, Gravity.CENTER).apply { background = G.round(G.ca("#ffffff", 0.07f), 16f, G.ca("#ffffff", 0.14f), 1f) }
            more.addView(G.icon(ctx, "Lightbulb", G.ca("#ffffff", 0.85f), 16f))
            more.addView(G.text(ctx, "Another clue", 13f, G.ca("#ffffff", 0.85f)))
            G.click(more, 0.97f) { if (revealed < 3) { revealed += 1; render() } }
            more.isEnabled = revealed < 3; more.alpha = if (revealed < 3) 1f else 0.4f
            row.addView(more, G.lpw(1f, G.dpi(40f)))
            val show = G.hbox(ctx, 6f, Gravity.CENTER).apply { background = G.round(G.ca("#ffffff", 0.05f), 16f, G.ca("#ffffff", 0.1f), 1f) }
            show.addView(G.icon(ctx, "Eye", G.ca("#ffffff", 0.7f), 16f))
            show.addView(G.text(ctx, "Show answer", 13f, G.ca("#ffffff", 0.7f)))
            G.click(show, 0.97f) { status = "revealed"; render() }
            row.addView(show, G.lpw(1f, G.dpi(40f)))
            card.addView(row, G.lp(G.MATCH, G.WRAP).m(t = 10f))
            gi.post { gi.requestFocus() }
        }
        content.addView(card, G.lp(G.MATCH, G.WRAP).m(b = 24f))
    }

    private fun normalize(v: String) = v.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()
    private fun isCorrect(item: ClueMeData.Item, guess: String): Boolean {
        val g = normalize(guess)
        if (g.isEmpty()) return false
        return (listOf(item.answer) + item.aliases).map { normalize(it) }.contains(g)
    }

    // ── result ──
    private fun result() {
        val ctx = context
        val msg = when { score >= 180 -> "Clue master! Nobody beats you."; score >= 120 -> "Sharp guessing, very impressive."; score >= 60 -> "Nice run — one more round?"; else -> "Good start. Try the easier category?" }
        val card = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        card.background = CssBg(24f, listOf(CssBg.Linear(150f, intArrayOf(G.ca("#ff4d8d", 0.2f), G.ca("#7c3aed", 0.2f), G.ca("#0e0d26", 0.95f)), floatArrayOf(0f, 0.55f, 1f))), border = G.ca("#ffffff", 0.12f), borderDp = 1f)
        card.pad(24f)
        card.addView(G.icon(ctx, "Trophy", G.c("#fcd34d"), 40f))
        val catLabel = ClueMeData.CATEGORIES.firstOrNull { it.key == category }?.label ?: "Mixed"
        card.addView(G.text(ctx, "GAME OVER · ${catLabel.uppercase()}", 12f, G.ca("#ffffff", 0.6f)).apply { letterSpacing = 0.025f }, G.lp(G.WRAP, G.WRAP).m(t = 12f))
        card.addView(G.text(ctx, "$score", 46f, Color.WHITE), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        card.addView(G.text(ctx, "points", 13f, G.ca("#ffffff", 0.7f), bold = false), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        card.addView(G.text(ctx, msg, 14f, Color.WHITE).apply { gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 12f))
        content.addView(card, G.lp(G.MATCH, G.WRAP))
        val stats = G.hbox(ctx, 10f)
        for ((l, v, c) in listOf(Triple("Solved", "$solved/${ClueMeData.ROUNDS}", Color.WHITE), Triple("Best score", "$best", G.c("#fcd34d")))) {
            val s = G.vbox(ctx).apply { background = G.round(G.ca("#141230", 0.85f), 16f, G.ca("#ffffff", 0.1f), 1f); pad(16f, 12f, 16f, 12f) }
            s.addView(G.text(ctx, l, 11.5f, G.ca("#ffffff", 0.5f), bold = false))
            s.addView(G.text(ctx, v, 20f, c), G.lp(G.WRAP, G.WRAP).m(t = 4f))
            stats.addView(s, G.lpw(1f))
        }
        content.addView(stats, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        val again = G.hbox(ctx, 8f, Gravity.CENTER).apply { background = CssBg(16f, btnGrad); G.shadow(this, 8f, G.c("#a855f7"), 16f) }
        again.addView(G.icon(ctx, "RotateCcw", Color.WHITE, 16f)); again.addView(G.text(ctx, "Play again", 15f, Color.WHITE))
        G.click(again, 0.98f) { start(category) }
        content.addView(again, G.lp(G.MATCH, G.dpi(48f)).m(t = 16f))
        val change = G.hbox(ctx, 8f, Gravity.CENTER).apply { background = G.round(G.ca("#ffffff", 0.07f), 16f, G.ca("#ffffff", 0.14f), 1f) }
        change.addView(G.icon(ctx, "Shuffle", G.ca("#ffffff", 0.85f), 16f)); change.addView(G.text(ctx, "Change category", 14f, G.ca("#ffffff", 0.85f)))
        G.click(change, 0.98f) { screen = "setup"; render() }
        content.addView(change, G.lp(G.MATCH, G.dpi(44f)).m(t = 10f))
        val back = G.text(ctx, "Back to Top Games", 13f, G.ca("#ffffff", 0.45f), bold = false).apply { gravity = Gravity.CENTER; pad(0f, 8f, 0f, 8f) }
        G.click(back) { layer.close(this) }
        content.addView(back, G.lp(G.MATCH, G.WRAP).m(t = 12f, b = 32f))
    }

    override fun destroy() { stopTimer(); super.destroy() }
}
