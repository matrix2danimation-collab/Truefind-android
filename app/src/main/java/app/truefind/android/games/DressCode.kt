package app.truefind.android.games

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a Dress Code game shows (the server's session view, or Practice's own copy). */
class DcView(
    val id: String, val code: String, val status: String, val me: String, val names: Map<String, String>,
    val look: Map<String, DressArt.Wear>, val owned: List<String>, val spent: Double, val girlEarned: Double,
    val round: Int, val rating: Int, val lookStartedAt: String, val chat: List<Triple<String, String, String>>, // r, t, at
    val chatLocked: Boolean, val partnerOnline: Boolean, val balance: Double?,
) {
    companion object {
        fun of(s: JSONObject): DcView {
            val n = s.obj("names")
            val ch = s.arr("chat").objs().map { Triple(it.str("r"), it.str("t"), it.str("at")) }
            return DcView(s.str("id"), s.str("code"), s.str("status"), s.str("me"), mapOf("boy" to n.str("boy"), "girl" to n.str("girl")),
                DressArt.lookOf(s.obj("look")), s.arr("owned").strs(), s.num("spent"), s.num("girl_earned"), s.int("round", 1).let { if (it < 1) 1 else it },
                s.int("rating"), s.str("look_started_at"), ch, s.bool("chat_locked"), s.bool("partner_online"), if (s.hasNum("balance")) s.num("balance") else null)
        }
    }
    fun copy(status: String = this.status, look: Map<String, DressArt.Wear> = this.look, owned: List<String> = this.owned, spent: Double = this.spent,
             girlEarned: Double = this.girlEarned, round: Int = this.round, rating: Int = this.rating, lookStartedAt: String = this.lookStartedAt,
             chat: List<Triple<String, String, String>> = this.chat) =
        DcView(id, code, status, me, names, look, owned, spent, girlEarned, round, rating, lookStartedAt, chat, chatLocked, partnerOnline, balance)
}

/** The game's actions (Practice does them here; Partner mode asks dressCodeAction). */
interface DcHandlers {
    fun buy(id: String, c: Int, done: (Boolean) -> Unit)
    fun remove(slot: String, done: () -> Unit)
    fun ready()
    fun rate(stars: Int)
    fun newlook()
    fun chat(t: String)
}

private fun nowIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
/** web Date.parse(iso) for the server's toISOString() times ("2026-11-17T15:28:01.123Z"); 0 when empty or unreadable */
private fun isoMs(s: String): Long {
    if (s.isEmpty()) return 0L
    for (f in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
        try {
            val d = java.text.SimpleDateFormat(f, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false }.parse(s)
            if (d != null) return d.time
        } catch (e: Throwable) { }
    }
    return 0L
}

// ═════════════════════ Dress Code home (opened from the Games page) ═════════════════════
@SuppressLint("ViewConstructor")
class DressCodeScreen(ctx: Context, layer: GamesLayer) : GameScreen(ctx, layer) {
    private var mode: DcMode? = null

    init {
        setBackgroundColor(DC.bg)
        isClickable = true
        DressArt.load(ctx) { if (alive) buildHome() }
    }

    private fun buildHome() {
        val ctx = context
        val scroll = scrollPage(ctx)
        val col = G.vbox(ctx)
        col.pad(20f, 20f, 20f, 40f)
        scroll.addView(col, LayoutParams(G.MATCH, G.WRAP))
        addView(scroll, 0, LayoutParams(G.MATCH, G.MATCH))
        // header
        val head = G.hbox(ctx, 12f)
        head.addView(DC.roundBtn(ctx, "ChevronLeft", 44f, 24f, "Back") { layer.close(this) }, LinearLayout.LayoutParams(G.dpi(44f), G.dpi(44f)))
        val ht = G.vbox(ctx)
        ht.addView(DC.t(ctx, "Dress Code", 26f, DC.ink, true, 1.25f))
        ht.addView(DC.t(ctx, "Style battle for two · 1 vs 1", 13f, DC.gold, false, 1.375f))
        head.addView(ht, G.lpw(1f))
        col.addView(head, G.lp(G.MATCH, G.WRAP).m(b = 16f))
        // hero
        val hero = FrameLayout(ctx).apply {
            background = CssBg(28f, listOf(CssBg.Linear(150f, intArrayOf(Color.WHITE, G.c("#fbf3e2"), G.c("#f1dfb6")), floatArrayOf(0f, 0.6f, 1f))), border = DC.line, borderDp = 1f)
            G.shadow(this, 4f, G.c("#3c2814"), 28f)
        }
        val hr = G.hbox(ctx, 8f, Gravity.BOTTOM).apply { pad(16f) }
        val hl = G.vbox(ctx).apply { pad(0f, 0f, 0f, 8f) }
        hl.addView(DC.t(ctx, "Gudiya ko\nstar banao 👑", 22f, DC.ink, true, 1.25f))
        hl.addView(DC.t(ctx, "Har round nayi theme, 3 missions aur timer. Saree, suit, tops, bottoms, gehne — sab alag-alag.", 13.5f, DC.sub), G.lp(G.MATCH, G.WRAP).m(t = 8f))
        val chips = Flow(ctx, 6f, 6f)
        chips.addView(G.text(ctx, "${DressArt.items.size}+ designs", 12f, DC.ink, true).lineH(1.5f).apply { background = G.round(Color.WHITE, 99f, DC.line, 1f); pad(10f, 4f, 10f, 4f) })
        chips.addView(DC.tierBadge(ctx, "p")); chips.addView(DC.tierBadge(ctx, "s"))
        hl.addView(chips, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        hr.addView(hl, G.lpw(1f))
        val show = DollView(ctx).apply { set(DressArt.SHOWCASE, false); contentDescription = "Doll" }
        hr.addView(show, LinearLayout.LayoutParams(0, G.dpi(230f), 0.6667f)) // w-[40%] of the row (60/40 with the text)
        hero.addView(hr, LayoutParams(G.MATCH, G.WRAP))
        col.addView(hero, G.lp(G.MATCH, G.WRAP))
        // the three tiers
        val tiers = G.hbox(ctx, 8f, Gravity.TOP)
        for ((k, p) in listOf("n" to "1 coin", "p" to "2 coin", "s" to "3-5 coin")) {
            val c = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply { DC.card(this, 16f); pad(10f) }
            c.addView(DC.tierBadge(ctx, k, true))
            c.addView(DC.t(ctx, "${DressArt.items.count { it.tier == k }}", 15f, DC.ink, true), G.lp(G.WRAP, G.WRAP).m(t = 4f))
            c.addView(DC.t(ctx, p, 11f, DC.sub))
            tiers.addView(c, G.lpw(1f))
        }
        col.addView(tiers, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        // play
        val partner = G.hbox(ctx, 8f, Gravity.CENTER).apply { DC.goldBtn(this, 16f); pad(0f, 14f, 0f, 14f) }
        partner.addView(G.icon(ctx, "Users", Color.WHITE, 16f))
        partner.addView(DC.t(ctx, "Play with a partner", 15f, Color.WHITE, true))
        G.click(partner, 0.98f) { openMode(false) }
        col.addView(partner, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        val practice = G.hbox(ctx, 8f, Gravity.CENTER).apply { DC.whiteBtn(this, 16f) }
        practice.addView(G.icon(ctx, "Shirt", DC.ink, 16f))
        practice.addView(DC.t(ctx, "Practice alone (free)", 14f, DC.ink, true))
        G.click(practice, 0.98f) { openMode(true) }
        col.addView(practice, G.lp(G.MATCH, G.dpi(48f)).m(t = 10f))
        col.addView(DC.t(ctx, "Kaise khelein", 14f, DC.ink, true), G.lp(G.MATCH, G.WRAP).m(t = 24f, b = 6f))
        col.addView(dcRules(ctx), G.lp(G.MATCH, G.WRAP))
        // test pictures only (GamesDemo): open the scene's screen without taps
        if (GameApi.demo) when (GamesDemo.scene) {
            "dc-practice", "dc-wardrobe", "dc-sheet" -> {
                openMode(true)
                val g = (mode as? DcMode)?.gameView()
                if (GamesDemo.scene == "dc-wardrobe") g?.demoWardrobe("tops")
                if (GamesDemo.scene == "dc-sheet") DressArt.byId["banarasi-zari"]?.let { g?.demoSheet(it) }
            }
            "dc-online", "dc-reveal" -> openMode(false)
        }
    }

    private fun openMode(practice: Boolean) {
        if (mode != null) return
        val m = if (practice) DcPractice(context, this) else DcOnline(context, this)
        mode = m
        addView(m, LayoutParams(G.MATCH, G.MATCH))
    }
    /** a mode (Practice / Partner) closes itself */
    fun closeMode(m: DcMode) {
        if (mode !== m) return
        m.dispose(); removeView(m); mode = null
    }
    fun openWallet() = layer.openWeb("/wallet")

    override fun onBack(): Boolean {
        val m = mode ?: return false
        return m.onBack()
    }
    override fun leave() { mode?.leave() }
    override fun destroy() { mode?.dispose(); super.destroy() }
}

/** numbered rules (web Rules: ol, 13 px, relaxed lines) */
private fun dcRules(ctx: Context): View {
    val l = G.vbox(ctx, 4f)
    DressArt.DC_RULES.forEachIndexed { i, r ->
        val row = G.hbox(ctx, 0f, Gravity.TOP)
        row.addView(DC.t(ctx, "${i + 1}.", 13f, DC.sub, false, 1.625f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(G.dpi(16f), G.WRAP).m(r = 4f))
        row.addView(DC.t(ctx, r, 13f, DC.sub, false, 1.625f), G.lpw(1f))
        l.addView(row, G.lp(G.MATCH, G.WRAP))
    }
    return l
}

/** A Practice or Partner game on top of the Dress Code home. */
abstract class DcMode(ctx: Context, val home: DressCodeScreen) : FrameLayout(ctx) {
    protected val main = android.os.Handler(android.os.Looper.getMainLooper())
    protected var alive = true
    protected fun later(ms: Long, f: () -> Unit): Runnable { val r = Runnable { if (alive) f() }; main.postDelayed(r, ms); return r }
    protected var game: DcGame? = null
    fun gameView() = game
    init { setBackgroundColor(DC.bg); isClickable = true }
    open fun onBack(): Boolean { if (game?.onBack() == true) return true; exit(); return true }
    abstract fun exit()
    open fun leave() {}
    open fun dispose() { alive = false; main.removeCallbacksAndMessages(null); game?.dispose() }
}

// ═════════════════════ Practice: play alone, free, nothing is charged ═════════════════════
@SuppressLint("ViewConstructor")
class DcPractice(ctx: Context, home: DressCodeScreen) : DcMode(ctx, home), DcHandlers {
    private var v = DcView("practice", "", "playing", "boy", mapOf("boy" to "You", "girl" to "Meera"), emptyMap(), emptyList(), 0.0, 0.0, 1, 0, nowIso(),
        listOf(Triple("girl", "Hii! ${DressArt.themeFor(1).ask}", nowIso())), false, true, null)
    private val rnd = java.util.Random()

    init {
        game = DcGame(ctx, this, practice = true, onExit = { exit() }, onRecharge = {}).also { addView(it, LayoutParams(G.MATCH, G.MATCH)) }
        push()
    }
    private fun push() { game?.bind(v, false, "", "", false) }
    private fun say(t: String) { v = v.copy(chat = (v.chat + Triple("girl", t, nowIso())).takeLast(30)); push() }

    override fun buy(id: String, c: Int, done: (Boolean) -> Unit) {
        val it = DressArt.byId[id] ?: run { done(true); return }
        val look = LinkedHashMap(v.look); look[it.slot] = DressArt.Wear(id, c)
        if (v.owned.contains(id)) { v = v.copy(look = look); push(); done(true); return }
        later(900) { if (rnd.nextDouble() < 0.5) say(DressArt.PARTNER_REPLIES[rnd.nextInt(DressArt.PARTNER_REPLIES.size)]) }
        v = v.copy(look = look, owned = v.owned + id, spent = DressArt.money(v.spent + it.price), girlEarned = DressArt.money(v.girlEarned + (it.price * DressArt.GIRL_PERCENT) / 100.0))
        push(); done(true)
    }
    override fun remove(slot: String, done: () -> Unit) { val look = LinkedHashMap(v.look); look.remove(slot); v = v.copy(look = look); push(); done() }
    override fun ready() { v = v.copy(status = "reveal"); push(); later(1400) { v = v.copy(rating = 4 + (if (rnd.nextBoolean()) 1 else 0)); push() } }
    override fun rate(stars: Int) {}
    override fun newlook() {
        v = v.copy(status = "playing", look = emptyMap(), owned = emptyList(), rating = 0, round = v.round + 1, lookStartedAt = nowIso()); push()
        later(600) { v = v.copy(chat = (v.chat + Triple("girl", DressArt.themeFor(v.round).ask, nowIso())).takeLast(30)); push() }
    }
    override fun chat(t: String) { v = v.copy(chat = (v.chat + Triple("boy", t, nowIso())).takeLast(30)); push(); later(1000) { say("Haha 😄 sahi hai!") } }
    override fun exit() { home.closeMode(this) }
}

// ═════════════════════ Partner mode: two phones, the server decides everything ═════════════════════
@SuppressLint("ViewConstructor")
class DcOnline(ctx: Context, home: DressCodeScreen) : DcMode(ctx, home), DcHandlers {
    private var s: JSONObject? = null
    private var sid = ""
    private var open = false
    private var busy = false
    private var error = ""
    private var note = ""
    private var broke = false
    private var noteT: Runnable? = null
    private var pollRun = 0
    private var pollKey = ""
    private var polling = false
    private var shown = ""          // which page is on screen: start / waiting / over / game
    private val page = FrameLayout(ctx)
    private var codeInput: EditText? = null
    private var createBtn: LinearLayout? = null
    private var joinBtn: View? = null
    private var startErr: TextView? = null
    private var waitNote: TextView? = null

    init {
        addView(page, LayoutParams(G.MATCH, G.MATCH)); render()
        if (GameApi.demo && GamesDemo.scene in listOf("dc-online", "dc-reveal")) call("state") { } // test pictures only
    }

    private fun dcErr(r: app.truefind.android.Api.Result, f: String): String {
        if (r.status == 404 && r.data.str("error").isEmpty()) return "Partner mode abhi setup nahi hua. Abhi 'Practice' try karo."
        return r.data.str("error").ifEmpty { f }
    }

    /** web call(): the server's answer replaces the session */
    private fun call(action: String, extra: JSONObject = JSONObject(), done: (app.truefind.android.Api.Result) -> Unit) {
        val body = JSONObject().put("action", action)
        if (sid.isNotEmpty()) body.put("session_id", sid)
        val k = extra.keys(); while (k.hasNext()) { val n = k.next(); body.put(n, extra.get(n)) }
        GameApi.call("dressCodeAction", body) { r ->
            if (!alive) return@call
            val sess = r.data.obj("session")
            if (r.ok && sess != null) { sid = sess.str("id"); open = sess.str("status") in listOf("waiting", "playing", "reveal"); s = sess; render() }
            done(r)
        }
    }
    private fun flash(t: String) { note = t; noteT?.let { main.removeCallbacks(it) }; noteT = later(2200) { note = ""; render() }; render() }

    /** web run(): one action at a time; out of coins → the Recharge note */
    private fun run(action: String, extra: JSONObject = JSONObject(), fallback: String = "That didn't work. Try again.", ok: () -> Unit = {}, done: (Boolean) -> Unit = {}) {
        if (busy) { done(false); return }
        busy = true; error = ""; render()
        call(action, extra) { r ->
            busy = false
            if (r.ok) { ok(); render(); done(true); return@call }
            if (r.status == 402 && Regex("coin", RegexOption.IGNORE_CASE).containsMatchIn(r.data.str("error"))) broke = true
            else error = dcErr(r, fallback)
            render(); done(false)
        }
    }

    private fun poll() {
        val st = s?.str("status").orEmpty()
        val key = if (s != null && st in listOf("waiting", "playing", "reveal")) "${s.str("id")}|$st" else ""
        if (key == pollKey) return
        pollKey = key; val run = ++pollRun
        if (key.isEmpty()) return
        fun tick() {
            if (!alive || run != pollRun) return
            if (!polling) { polling = true; call("state") { polling = false } }
            later(2000) { tick() }
        }
        later(2000) { tick() }
    }

    override fun exit() {
        val cur = s
        if (cur != null && cur.str("status") in listOf("waiting", "playing", "reveal")) {
            G.confirm(context, "Leave the game? It will end for both players.") {
                call("leave") { home.closeMode(this) }
            }
            return
        }
        home.closeMode(this)
    }
    /** left without Exit (Games closed): still end it, so the partner isn't left waiting */
    override fun leave() {
        if (open && sid.isNotEmpty()) { open = false; GameApi.call("dressCodeAction", JSONObject().put("action", "leave").put("session_id", sid)) { } }
    }
    override fun dispose() { leave(); super.dispose() }

    // handlers
    override fun buy(id: String, c: Int, done: (Boolean) -> Unit) = run("buy", JSONObject().put("item", id).put("color", c).put("client_buy_id", UUID.randomUUID().toString()), ok = { broke = false }, done = done)
    override fun remove(slot: String, done: () -> Unit) = run("remove", JSONObject().put("slot", slot)) { done() }
    override fun ready() = run("ready")
    override fun rate(stars: Int) = run("rate", JSONObject().put("stars", stars))
    override fun newlook() = run("newlook")
    override fun chat(t: String) {
        call("chat", JSONObject().put("text", t)) { r -> if (r.ok) { error = ""; render() } else flash(dcErr(r, "Message not sent.")) }
    }

    private fun render() {
        poll()
        val cur = s
        val st = cur?.str("status").orEmpty()
        val want = when { cur == null -> "start"; st == "waiting" -> "waiting"; st == "cancelled" || st == "finished" -> "over"; else -> "game" }
        if (want != shown) {
            shown = want
            page.removeAllViews(); game?.dispose(); game = null; codeInput = null; createBtn = null; joinBtn = null; startErr = null; waitNote = null
            when (want) {
                "start" -> buildStart()
                "waiting" -> buildWaiting()
                "over" -> buildOver()
                else -> game = DcGame(context, this, practice = false, onExit = { exit() }, onRecharge = { home.openWallet() }).also { page.addView(it, LayoutParams(G.MATCH, G.MATCH)) }
            }
        }
        when (want) {
            "start" -> {
                createBtn?.let { cb -> cb.isEnabled = !busy; cb.alpha = if (busy) 0.6f else 1f; cb.getChildAt(0)?.visibility = if (busy) View.GONE else View.VISIBLE; cb.getChildAt(1)?.visibility = if (busy) View.VISIBLE else View.GONE }
                joinBtn?.let { j -> val on = (codeInput?.text?.length ?: 0) == 4 && !busy; j.isEnabled = on; j.alpha = if (on) 1f else 0.4f }
                startErr?.let { it.text = error; it.visibility = if (error.isNotEmpty()) View.VISIBLE else View.GONE }
            }
            "waiting" -> {
                (page.findViewWithTag<TextView>("code"))?.text = cur.str("code")
                waitNote?.let { it.text = note; it.visibility = if (note.isNotEmpty()) View.VISIBLE else View.GONE }
            }
            "game" -> game?.bind(DcView.of(cur!!), busy, error, note, broke)
        }
    }

    private fun shell(sub: String?, onExit: () -> Unit): Pair<LinearLayout, LinearLayout> {
        val ctx = context
        val root = G.vbox(ctx)
        root.addView(dcTopBar(ctx, "Dress Code", sub?.let { DC.t(ctx, it, 12f, DC.sub).ellipsize1() }, null, "X", "Exit game", onExit), G.lp(G.MATCH, G.WRAP))
        val body = G.vbox(ctx)
        page.addView(root, LayoutParams(G.MATCH, G.MATCH))
        return root to body
    }

    private fun buildStart() {
        val ctx = context
        val (root, body) = shell("Partner mode · 1 ladka + 1 ladki") { home.closeMode(this) }
        val scroll = scrollPage(ctx)
        body.pad(20f, 0f, 20f, 32f)
        scroll.addView(body, LayoutParams(G.MATCH, G.WRAP))
        root.addView(scroll, G.lpw(1f, 0).apply { width = G.MATCH })
        val create = G.vbox(ctx).apply { DC.card(this, 22f); pad(16f) }
        create.addView(DC.t(ctx, "New game", 15f, DC.ink, true))
        create.addView(DC.t(ctx, "Code milega. Apne partner (ladka ya ladki) ko bhejo.", 13f, DC.sub), G.lp(G.MATCH, G.WRAP).m(t = 2f))
        val cb = G.hbox(ctx, 0f, Gravity.CENTER).apply { DC.goldBtn(this, 16f) }
        cb.addView(DC.t(ctx, "Create game", 16f, Color.WHITE, true))
        cb.addView(TfSpinner(ctx, 20f, white = true).apply { visibility = View.GONE }, LinearLayout.LayoutParams(G.dpi(20f), G.dpi(20f)))
        G.click(cb, 0.98f) { run("create") }
        createBtn = cb
        create.addView(cb, G.lp(G.MATCH, G.dpi(48f)).m(t = 12f))
        body.addView(create, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        val join = G.vbox(ctx).apply { DC.card(this, 22f); pad(16f) }
        join.addView(DC.t(ctx, "Join with a code", 15f, DC.ink, true))
        val row = G.hbox(ctx, 8f)
        val code = EditText(ctx).apply {
            hint = "1234"; setHintTextColor(G.ca("#1f1b16", 0.4f)); setTextColor(DC.ink)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, G.dp(20f)); typeface = app.truefind.android.Ui.bold; letterSpacing = 0.3f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(4)); isSingleLine = true
            background = G.round(DC.soft, 16f, DC.line, 1f); pad(16f, 0f, 16f, 0f); contentDescription = "Game code"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { render() }
            })
        }
        codeInput = code
        row.addView(code, G.lpw(1f, G.dpi(48f)))
        val jb = G.text(ctx, "Join", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; pad(20f, 0f, 20f, 0f); DC.goldBtn(this, 16f) }
        G.click(jb, 0.98f) { if ((codeInput?.text?.length ?: 0) == 4) run("join", JSONObject().put("code", codeInput?.text.toString())) }
        joinBtn = jb
        row.addView(jb, LinearLayout.LayoutParams(G.WRAP, G.dpi(48f)))
        join.addView(row, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        body.addView(join, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        startErr = DC.t(ctx, "", 13f, DC.red).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        body.addView(startErr, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        body.addView(DC.t(ctx, "Rules", 14f, DC.ink, true), G.lp(G.MATCH, G.WRAP).m(t = 20f, b = 6f))
        body.addView(dcRules(ctx), G.lp(G.MATCH, G.WRAP))
    }

    private fun buildWaiting() {
        val ctx = context
        val (root, body) = shell("Partner ka intezaar") { exit() }
        body.pad(20f, 0f, 20f, 0f)
        root.addView(body, G.lp(G.MATCH, G.WRAP))
        val c = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply { DC.card(this, 22f); pad(20f) }
        c.addView(DC.t(ctx, "Ye code partner ko bhejo", 13f, DC.sub))
        c.addView(G.text(ctx, s.str("code"), 44f, DC.ink, true).lineH(1.5f).apply { letterSpacing = 0.25f; tag = "code" }, G.lp(G.WRAP, G.WRAP).m(t = 4f))
        val copy = G.hbox(ctx, 6f, Gravity.CENTER).apply { DC.whiteBtn(this, 99f); pad(16f, 0f, 16f, 0f) }
        copy.addView(G.icon(ctx, "Copy", DC.ink, 16f))
        copy.addView(DC.t(ctx, "Copy code", 13f, DC.ink, true))
        G.click(copy) {
            try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", s.str("code"))); flash("Code copied") } catch (e: Throwable) { }
        }
        c.addView(copy, LinearLayout.LayoutParams(G.WRAP, G.dpi(36f)).m(t = 4f))
        val w = G.hbox(ctx, 8f, Gravity.CENTER)
        w.addView(TfSpinner(ctx, 16f), LinearLayout.LayoutParams(G.dpi(16f), G.dpi(16f)))
        w.addView(DC.t(ctx, "Partner ke join hone ka intezaar…", 13f, DC.sub))
        c.addView(w, G.lp(G.WRAP, G.WRAP).m(t = 16f))
        c.addView(DC.t(ctx, if (s.str("me") == "boy") "Tum ladke ho: tum stylist ho." else "Tum ladki ho: tumhari gudiya saji jayegi.", 12f, DC.sub).apply { gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 8f))
        body.addView(c, G.lp(G.MATCH, G.WRAP).m(t = 24f))
        waitNote = DC.t(ctx, "", 13f, DC.ink, true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        body.addView(waitNote, G.lp(G.MATCH, G.WRAP).m(t = 12f))
    }

    private fun buildOver() {
        val ctx = context
        val (root, body) = shell(null) { home.closeMode(this) }
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.pad(20f, 64f, 20f, 0f)
        root.addView(body, G.lp(G.MATCH, G.WRAP))
        body.addView(DC.t(ctx, "Game khatam ho gaya", 18f, DC.ink, true))
        body.addView(DC.t(ctx, "Partner chala gaya.", 13f, DC.sub), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        val done = G.text(ctx, "Done", 15f, DC.ink, true).apply { gravity = Gravity.CENTER; pad(24f, 0f, 24f, 0f); DC.whiteBtn(this, 16f) }
        G.click(done, 0.98f) { home.closeMode(this) }
        body.addView(done, LinearLayout.LayoutParams(G.WRAP, G.dpi(44f)).m(t = 20f))
    }
}

/** TopBar (web): round exit button · title / sub · right */
internal fun dcTopBar(ctx: Context, title: String, sub: View?, right: View?, icon: String, exitLabel: String, onExit: () -> Unit): LinearLayout {
    val row = G.hbox(ctx, 12f)
    row.pad(16f, 12f, 16f, 8f)
    row.addView(DC.roundBtn(ctx, icon, 40f, 20f, exitLabel) { onExit() }, LinearLayout.LayoutParams(G.dpi(40f), G.dpi(40f)))
    val c = G.vbox(ctx)
    c.addView(DC.t(ctx, title, 18f, DC.ink, true, 1.25f).ellipsize1())
    if (sub != null) c.addView(sub)
    row.addView(c, G.lpw(1f))
    if (right != null) row.addView(right)
    return row
}

// ═════════════════════ The game screen (Practice and Partner modes) ═════════════════════
@SuppressLint("ViewConstructor")
class DcGame(ctx: Context, private val h: DcHandlers, private val practice: Boolean, private val onExit: () -> Unit, private val onRecharge: () -> Unit) : FrameLayout(ctx) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var disposed = false
    private fun later(ms: Long, f: () -> Unit): Runnable { val r = Runnable { if (!disposed) f() }; main.postDelayed(r, ms); return r }

    private var v: DcView? = null
    private var busy = false; private var error = ""; private var note = ""; private var broke = false
    private val girl get() = v?.me == "girl"
    // web state
    private var tab: String? = null
    private var sheetItem: DressArt.Item? = null
    private var prevEarned = 0.0; private var prevScore = 0; private var prevDone: List<Boolean> = emptyList(); private var prevRound = 0
    private var autoReady = 0
    private var started = false

    // views
    private val col = G.vbox(ctx)
    private lateinit var subDot: View
    private lateinit var subText: TextView
    private lateinit var rightChip: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var viewer: DollViewer
    private lateinit var themeChip: TextView
    private lateinit var roundT: TextView
    private lateinit var timerChip: TextView
    private lateinit var gradeBox: TextView
    private lateinit var totalT: TextView
    private lateinit var chatCol: LinearLayout
    private lateinit var popsBox: FrameLayout
    private lateinit var fxBox: FrameLayout
    private lateinit var barFill: View
    private lateinit var barBox: FrameLayout
    private lateinit var comboT: TextView
    private val missions = ArrayList<Pair<LinearLayout, Pair<FrameLayout, TextView>>>()
    private lateinit var content: ScrollView
    private lateinit var contentCol: LinearLayout
    private lateinit var statusT: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: FrameLayout
    private var showBtn: LinearLayout? = null
    private var timerBlink: ValueAnimator? = null
    private var wardrobe: DcWardrobe? = null
    private var sheet: DcItemSheet? = null
    private var full: FrameLayout? = null
    private var reveal: FrameLayout? = null
    private var revealKey = ""
    private var contentKey = ""
    private var lastGrade = ""
    private var tick: Runnable? = null

    init {
        setBackgroundColor(DC.bg)
        isClickable = true
        addView(col, LayoutParams(G.MATCH, G.MATCH))
    }

    fun dispose() { disposed = true; main.removeCallbacksAndMessages(null); timerBlink?.cancel(); barAnim?.cancel(); wardrobe?.dispose() }

    /** web: the stage is min(46 dvh, 430 px) of the screen the game fills */
    override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, hh, ow, oh)
        if (!::stage.isInitialized || hh <= 0) return
        val want = min((hh * 0.46f).roundToInt(), G.dpi(430f))
        val lp = stage.layoutParams
        if (lp != null && lp.height != want) { lp.height = want; post { stage.layoutParams = lp } }
    }

    private fun theme() = DressArt.themeFor(v?.round ?: 1)

    private fun build(view: DcView) {
        val ctx = context
        val partnerName = if (view.me == "girl") view.names["boy"] else view.names["girl"]
        // top bar
        val sub = G.hbox(ctx, 6f)
        subDot = View(ctx).apply { background = G.oval(G.c("#a8a29e")) }
        if (!practice) sub.addView(subDot, LinearLayout.LayoutParams(G.dpi(8f), G.dpi(8f)))
        subText = DC.t(ctx, if (practice) "Practice · free, no coins" else "", 12f, DC.sub).ellipsize1()
        sub.addView(subText, G.lpw(1f))
        rightChip = DC.chip(ctx)
        col.addView(dcTopBar(ctx, "Dress Code", sub, rightChip, "X", "Exit game") { onExit() }, G.lp(G.MATCH, G.WRAP))
        // stage
        stage = FrameLayout(ctx).apply {
            background = CssBg(28f, listOf(CssBg.Radial(0.5f, 0.42f, 0.7f, 0.6f, intArrayOf(Color.WHITE, G.c("#f6f0e6"), G.c("#efe6d8")), floatArrayOf(0f, 0.7f, 1f))), border = DC.line, borderDp = 1f)
            clipToOutline = true
            G.shadow(this, 4f, G.c("#3c2814"), 28f)
        }
        val shadowSpot = View(ctx).apply { background = CssBg.ovalOf(listOf(CssBg.Radial(0.5f, 0.5f, 0.5f, 0.5f, intArrayOf(G.ca("#50381e", 0.22f), Color.TRANSPARENT)))) }
        stage.addView(shadowSpot, LayoutParams(0, 0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        viewer = DollViewer(ctx, true)
        stage.addView(viewer, LayoutParams(G.MATCH, G.MATCH))
        stage.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val hh = b - t
            val lp = shadowSpot.layoutParams as LayoutParams
            val sw = (w * 0.46f).roundToInt(); val sh = (hh * 0.05f).roundToInt(); val bm = (hh * 0.04f).roundToInt()
            if (lp.width != sw || lp.height != sh || lp.bottomMargin != bm) { lp.width = sw; lp.height = sh; lp.bottomMargin = bm; lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; shadowSpot.post { shadowSpot.layoutParams = lp } }
        }
        val ov = viewer.overlay
        fxBox = FrameLayout(ctx).apply { clipChildren = false }
        ov.addView(fxBox, LayoutParams(G.MATCH, G.MATCH))
        popsBox = FrameLayout(ctx).apply { clipChildren = false }
        ov.addView(popsBox, LayoutParams(G.MATCH, G.MATCH))
        // HUD
        val hud = FrameLayout(ctx)
        val left = G.vbox(ctx, 4f)
        themeChip = DC.t(ctx, "", 13f, DC.ink, true).apply { gravity = Gravity.CENTER_VERTICAL; pad(12f, 0f, 12f, 0f); DC.whiteBtn(this, 99f) }
        left.addView(themeChip, LinearLayout.LayoutParams(G.WRAP, G.dpi(32f)))
        roundT = G.text(ctx, "", 10.5f, DC.sub, true).apply { letterSpacing = 0.025f; pad(8f, 0f, 0f, 0f) }
        left.addView(roundT)
        hud.addView(left, LayoutParams(G.WRAP, G.WRAP, Gravity.START or Gravity.TOP))
        val rightCol = G.vbox(ctx, 6f, Gravity.END)
        timerChip = DC.t(ctx, "", 13f, DC.ink, true).apply { gravity = Gravity.CENTER_VERTICAL; pad(12f, 0f, 12f, 0f); DC.whiteBtn(this, 99f); fontFeatureSettings = "tnum" }
        rightCol.addView(timerChip, LinearLayout.LayoutParams(G.WRAP, G.dpi(32f)))
        val gradeCol = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        gradeBox = G.text(ctx, "", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER; G.shadow(this, 5f, G.c("#3c2814"), 16f) }
        gradeCol.addView(gradeBox, LinearLayout.LayoutParams(G.dpi(48f), G.dpi(48f)))
        totalT = G.text(ctx, "", 10.5f, DC.ink, true).apply { fontFeatureSettings = "tnum" }
        gradeCol.addView(totalT, G.lp(G.WRAP, G.WRAP).m(t = 2f))
        rightCol.addView(gradeCol)
        hud.addView(rightCol, LayoutParams(G.WRAP, G.WRAP, Gravity.END or Gravity.TOP))
        ov.addView(hud, LayoutParams(G.MATCH, G.WRAP).m(12f, 12f, 12f, 0f))
        chatCol = G.vbox(ctx, 4f)
        ov.addView(chatCol, LayoutParams(G.WRAP, G.WRAP, Gravity.BOTTOM or Gravity.START).m(l = 10f, b = 48f))
        val fullBtn = G.text(ctx, "⤢ Full view", 11.5f, DC.ink, true).apply { gravity = Gravity.CENTER; pad(10f, 0f, 10f, 0f); DC.whiteBtn(this, 99f); contentDescription = "Open full-screen doll view" }
        G.click(fullBtn, 0.97f) { openFull() }
        ov.addView(fullBtn, LayoutParams(G.WRAP, G.dpi(32f), Gravity.BOTTOM or Gravity.END).m(r = 8f, b = 48f))
        col.addView(stage, G.lp(G.MATCH, stageHeight()).m(l = 12f, r = 12f))
        // missions
        val mb = G.vbox(ctx)
        val mrow = G.hbox(ctx, 8f)
        mrow.addView(G.text(ctx, "🎯 MISSIONS", 11f, DC.sub, true).apply { letterSpacing = 0.025f })
        barBox = FrameLayout(ctx).apply { background = G.round(DC.soft, 99f); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        barFill = View(ctx).apply { background = CssBg(99f, listOf(CssBg.Linear(90f, intArrayOf(G.c("#e7c66f"), G.c("#b0174f"))))) }
        barBox.addView(barFill, LayoutParams(0, G.MATCH))
        mrow.addView(barBox, G.lpw(1f, G.dpi(8f)))
        comboT = G.text(ctx, "🎨 Combo", 11f, DC.gold, true).apply { visibility = View.GONE }
        mrow.addView(comboT)
        mb.addView(mrow, G.lp(G.MATCH, G.WRAP))
        val grid = G.hbox(ctx, 6f, Gravity.TOP)
        missions.clear()
        for (i in 0..2) {
            val card = G.hbox(ctx, 6f).apply { pad(8f, 6f, 8f, 6f) }
            val ic = FrameLayout(ctx)
            card.addView(ic, LinearLayout.LayoutParams(G.dpi(20f), G.dpi(20f)))
            val t = DC.t(ctx, "", 11.5f, DC.ink, true, 1.25f)
            card.addView(t, G.lpw(1f))
            missions.add(card to (ic to t))
            grid.addView(card, G.lpw(1f, G.MATCH))
        }
        mb.addView(grid, G.lp(G.MATCH, G.WRAP).m(t = 6f))
        col.addView(mb, G.lp(G.MATCH, G.WRAP).m(12f, 10f, 12f, 0f))
        // closet (boy) / her panel (girl)
        content = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        contentCol = G.vbox(ctx)
        content.addView(contentCol, LayoutParams(G.MATCH, G.WRAP))
        col.addView(content, G.lpw(1f, 0).apply { width = G.MATCH })
        statusT = DC.t(ctx, "", 13f, DC.ink, true).apply { gravity = Gravity.CENTER; visibility = View.GONE; pad(16f, 4f, 16f, 0f) }
        col.addView(statusT, G.lp(G.MATCH, G.WRAP))
        // chat + Show look
        val bar = G.hbox(ctx, 8f).apply { background = DC.topLine(); pad(12f, 8f, 12f, 12f) }
        input = EditText(ctx).apply {
            setTextColor(DC.ink); setHintTextColor(G.ca("#1f1b16", 0.45f)); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, G.dp(14f)); typeface = app.truefind.android.Ui.regular
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND; filters = arrayOf(InputFilter.LengthFilter(140))
            background = G.round(DC.soft, 16f, DC.line, 1f); pad(16f, 0f, 16f, 0f); contentDescription = "Chat message"
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { paintBar() }
            })
        }
        bar.addView(input, G.lpw(1f, G.dpi(44f)))
        sendBtn = FrameLayout(ctx).apply { DC.whiteBtn(this, 16f); contentDescription = "Send message" }
        sendBtn.addView(G.icon(ctx, "Send", DC.ink, 20f), LayoutParams(G.dpi(20f), G.dpi(20f), Gravity.CENTER))
        G.click(sendBtn, 0.95f) { send() }
        bar.addView(sendBtn, LinearLayout.LayoutParams(G.dpi(44f), G.dpi(44f)))
        if (view.me != "girl") {
            val sb = G.hbox(ctx, 6f, Gravity.CENTER).apply { DC.goldBtn(this, 16f); pad(14f, 0f, 14f, 0f) }
            sb.addView(G.icon(ctx, "Sparkles", Color.WHITE, 16f))
            sb.addView(DC.t(ctx, "Show look", 14f, Color.WHITE, true))
            G.click(sb, 0.97f) { h.ready() }
            showBtn = sb
            bar.addView(sb, LinearLayout.LayoutParams(G.WRAP, G.dpi(44f)))
        }
        col.addView(bar, G.lp(G.MATCH, G.WRAP))
    }

    private fun stageHeight(): Int {
        val hh = (parent as? View)?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        return min((hh * 0.46f).roundToInt(), G.dpi(430f))
    }

    private fun send() {
        val vv = v ?: return
        if (vv.chatLocked) return
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        h.chat(t)
    }

    /** puts the latest game state on screen (web: DressGame render + its effects) */
    fun bind(view: DcView, busyNow: Boolean, err: String, noteNow: String, brokeNow: Boolean) {
        if (disposed) return
        val first = v == null
        v = view; busy = busyNow; error = err; note = noteNow; broke = brokeNow
        if (first) {
            build(view)
            prevEarned = view.girlEarned; prevRound = view.round
            val sc = DressArt.styleScore(view.look, theme()); prevScore = sc.total; prevDone = sc.done
            banner("Round ${view.round}", "${theme().icon} ${theme().title}")
            startTick()
        }
        val theme = theme()
        val score = DressArt.styleScore(view.look, theme)
        val partner = if (view.me == "girl") view.names["boy"] else view.names["girl"]
        if (!practice) {
            subDot.background = G.oval(if (view.partnerOnline) G.c("#22c55e") else G.c("#a8a29e"))
            subText.text = "${partner?.ifEmpty { null } ?: "Partner"} ${if (view.partnerOnline) "is here" else "is away"}"
        }
        rightChip.removeAllViews()
        if (view.me != "girl") { rightChip.contentDescription = "Your coins"; if (practice) rightChip.addView(DC.t(context, "Free", 13f, DC.ink, true)) else rightChip.addView(DC.coin(context, view.balance ?: 0.0, 16f, 13f)) }
        else rightChip.addView(DC.t(context, "👗 Model", 13f, DC.ink, true))
        // stage
        viewer.setLook(view.look)
        themeChip.text = SpannableStringBuilder(theme.icon).also { MC.gapSpan(it, 6f); it.append(theme.title) }
        themeChip.contentDescription = "Round ${view.round}: ${theme.title}"
        roundT.text = "ROUND ${view.round}"
        gradeBox.text = score.grade
        gradeBox.background = DC.gradeBg(score.grade)
        gradeBox.contentDescription = "Style score ${score.total}, rank ${score.grade}"
        if (lastGrade != score.grade) bump(gradeBox, 500)
        lastGrade = score.grade
        totalT.text = "${score.total}/100"
        bindChat(view)
        bindTimer()
        // missions
        barTarget = score.total
        barBox.post { paintBar(first) }
        comboT.visibility = if (score.combo) View.VISIBLE else View.GONE
        theme.wishes.forEachIndexed { i, w ->
            val (card, pr) = missions[i]; val (ic, t) = pr
            val ok = score.done[i]
            card.background = if (ok) G.round(G.c("#ecfdf3"), 12f, G.c("#86efac"), 1f) else dashed()
            ic.removeAllViews()
            ic.background = G.oval(if (ok) DC.green else DC.soft)
            if (ok) ic.addView(G.icon(context, "Check", Color.WHITE, 12f), LayoutParams(G.dpi(12f), G.dpi(12f), Gravity.CENTER))
            else ic.addView(G.text(context, w.e, 11f, DC.ink, false).apply { gravity = Gravity.CENTER }, LayoutParams(G.MATCH, G.MATCH))
            t.text = w.t; t.setTextColor(if (ok) G.c("#166534") else DC.ink)
            card.contentDescription = "${w.t}, ${if (ok) "done" else "not done"}"
        }
        // pops / banner (web effect on girl_earned, score.total, round)
        if (!first && (view.girlEarned != prevEarned || score.total != prevScore || view.round != prevRound)) {
            if (prevRound == view.round) {
                val dScore = score.total - prevScore
                val nm = score.done.indices.firstOrNull { score.done[it] && !(prevDone.getOrNull(it) ?: false) } ?: -1
                if (nm >= 0) banner("Mission complete 🎯", "${theme.wishes[nm].e} ${theme.wishes[nm].t}")
                if (dScore > 0) { pop("style", "+$dScore style ✨"); sparks() }
                val dEarn = DressArt.money(view.girlEarned - prevEarned)
                if (view.me == "girl" && dEarn > 0) pop("earn", "+${DressArt.coinFmt(dEarn)} coins")
            } else banner("Round ${view.round}", "${theme.icon} ${theme.title}")
            prevEarned = view.girlEarned; prevScore = score.total; prevDone = score.done; prevRound = view.round
        }
        bindContent(view, theme)
        statusT.text = error.ifEmpty { note }
        statusT.setTextColor(if (error.isNotEmpty()) DC.red else DC.ink)
        statusT.visibility = if (error.isNotEmpty() || note.isNotEmpty()) View.VISIBLE else View.GONE
        paintBar()
        wardrobe?.bind(view, theme)
        sheet?.bind(view, busy)
        bindReveal(view, theme, score)
    }

    private var barTarget = 0
    private var barAnim: ValueAnimator? = null
    /** the missions bar width (web: width transition .5 s; the first paint has no transition) */
    private fun paintBar(first: Boolean) {
        val lp = barFill.layoutParams
        val w = (barBox.width * barTarget / 100f).roundToInt()
        if (lp.width == w) return
        barAnim?.cancel()
        if (first || barBox.width == 0) { lp.width = w; barFill.layoutParams = lp; return }
        barAnim = ValueAnimator.ofInt(lp.width, w).apply { duration = 500; interpolator = MC.EASE; addUpdateListener { a -> lp.width = a.animatedValue as Int; barFill.layoutParams = lp }; start() }
    }

    private fun dashed(): android.graphics.drawable.Drawable = CssBg(12f, emptyList(), Color.WHITE, DC.line, 1f, dashed = true)

    private fun paintBar() {
        val vv = v ?: return
        val locked = vv.chatLocked
        input.isEnabled = !locked
        input.alpha = if (locked) 0.6f else 1f
        input.hint = if (locked) (if (vv.me == "girl") "💬 Wo kuch naya lega to baat chalegi" else "💬 Kuch naya lo, baat jaari rahegi") else "Baat karo…"
        val canSend = !locked && input.text.toString().trim().isNotEmpty()
        sendBtn.isEnabled = canSend; sendBtn.alpha = if (canSend) 1f else 0.4f
        showBtn?.let { val on = !(busy || vv.owned.isEmpty()); it.isEnabled = on; it.alpha = if (on) 1f else 0.4f }
    }

    private fun bindChat(view: DcView) {
        val last = view.chat.takeLast(2)
        val keys = last.mapIndexed { i, m -> m.third + i }
        val key = keys.joinToString("|") + "|" + last.joinToString("|") { it.second }
        if (chatCol.tag == key) return
        val old = (chatCol.tag as? String).orEmpty().split("|").toSet()
        chatCol.tag = key
        chatCol.removeAllViews()
        val maxW = ((if (stage.width > 0) stage.width else resources.displayMetrics.widthPixels - G.dpi(24f)) * 0.42f).roundToInt()
        last.forEachIndexed { i, m ->
            val mine = m.first == view.me
            val sb = SpannableStringBuilder()
            val nm = if (mine) "You" else view.names[m.first]?.ifEmpty { null } ?: "Partner"
            sb.append(nm)
            sb.setSpan(FontSpan(app.truefind.android.Ui.bold), 0, sb.length, 0)
            sb.setSpan(ForegroundColorSpan(if (mine) DC.rose else DC.gold), 0, sb.length, 0)
            sb.setSpan(android.text.style.AbsoluteSizeSpan(G.dpi(10.5f)), 0, sb.length, 0)
            MC.gapSpan(sb, 4f)
            sb.append(m.second)
            val bubble = DC.t(context, sb, 12f, DC.ink, false, 1.375f).apply {
                background = G.round(if (mine) G.c("#fbeaf1") else Color.WHITE, 16f, if (mine) G.c("#f3c6d8") else DC.line, 1f)
                G.shadow(this, 1.5f, G.c("#3c2814"), 16f)
                pad(10f, 4f, 10f, 4f); maxWidth = maxW
            }
            chatCol.addView(bubble, LinearLayout.LayoutParams(G.WRAP, G.WRAP))
            if (!old.contains(keys[i])) G.rise(bubble, 0, 14f, 300)
        }
    }

    private fun msLeft(): Long {
        val vv = v ?: return DressArt.LOOK_SECONDS * 1000L
        val start = isoMs(vv.lookStartedAt)
        return if (start > 0) max(0L, start + DressArt.LOOK_SECONDS * 1000L - System.currentTimeMillis()) else DressArt.LOOK_SECONDS * 1000L
    }
    private fun bindTimer() {
        val ms = msLeft()
        timerChip.text = "⏱ ${DressArt.mmss(ms)}"
        timerChip.contentDescription = "Time left ${DressArt.mmss(ms)}"
        val low = ms <= 30000
        timerChip.setTextColor(if (low) DC.red else DC.ink)
        timerChip.background = G.round(Color.WHITE, 99f, if (low) G.c("#fca5a5") else DC.line, 1f)
        val blink = low && ms > 0
        if (blink && timerBlink == null) timerBlink = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a -> val t = a.animatedValue as Float; timerChip.alpha = 1f - 0.55f * G.ease(if (t < 0.5f) t * 2 else (1 - t) * 2) }
            start()
        } else if (!blink && timerBlink != null) { timerBlink?.cancel(); timerBlink = null; timerChip.alpha = 1f }
    }
    /** web useNow(playing, 500) + the boy's automatic "Show look" when the time is up */
    private fun startTick() {
        tick = later(500) {
            val vv = v
            if (vv != null && vv.status == "playing") {
                bindTimer()
                val start = isoMs(vv.lookStartedAt)
                if (vv.me != "girl" && start > 0 && msLeft() <= 0 && autoReady != vv.round) {
                    autoReady = vv.round
                    sheet?.let { closeSheet() }; wardrobe?.let { closeWardrobe() }
                    h.ready()
                }
            }
            startTick()
        }
    }

    // ── content: the closet (boy) or her side (girl) ──
    private fun bindContent(view: DcView, theme: DressArt.Theme) {
        val key = "${view.me}|${view.look.entries.joinToString { "${it.key}=${it.value.id}.${it.value.c}" }}|${theme.key}|$broke|${view.girlEarned}|${view.chatLocked}|${view.names}"
        if (key == contentKey) return
        contentKey = key
        val keepY = content.scrollY
        contentCol.removeAllViews()
        (content.layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin = if (view.me == "girl") 0 else G.dpi(10f); content.layoutParams = it }
        if (view.me == "girl") girlPanel(view, theme) else closet(view, theme)
        content.post { content.scrollTo(0, keepY) }
    }

    private fun label(t: String) = G.text(context, t.uppercase(), 12f, DC.sub, true).apply { letterSpacing = 0.025f }

    private fun closet(view: DcView, theme: DressArt.Theme) {
        val ctx = context
        contentCol.pad(0f, 0f, 0f, 12f)
        val hot = theme.wishes.flatMap { it.tags }.toSet()
        if (broke) {
            val a = G.hbox(ctx, 12f).apply { background = G.round(G.c("#fef2f2"), 16f, G.c("#fecaca"), 1f); pad(12f, 10f, 12f, 10f) }
            a.addView(DC.t(ctx, "Coins khatam! Recharge karo aur khelte raho.", 13f, DC.red, true), G.lpw(1f))
            val r = G.text(ctx, "Recharge", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; pad(16f, 0f, 16f, 0f); DC.goldBtn(this, 12f) }
            G.click(r, 0.97f) { onRecharge() }
            a.addView(r, LinearLayout.LayoutParams(G.WRAP, G.dpi(36f)))
            contentCol.addView(a, G.lp(G.MATCH, G.WRAP).m(12f, 0f, 12f, 8f))
        }
        contentCol.addView(label("Wardrobe kholo"), G.lp(G.MATCH, G.WRAP).m(16f, 0f, 16f, 6f))
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false; pad(12f, 0f, 12f, 4f) }
        val row = G.hbox(ctx, 10f, Gravity.TOP)
        row.clipChildren = false
        for (t in DressArt.tabs) {
            val n = DressArt.items.count { t.slots.contains(it.slot) }
            val isHot = DressArt.items.any { t.slots.contains(it.slot) && it.tags.any { g -> hot.contains(g) } }
            val on = t.slots.any { view.look.containsKey(it) }
            val box = FrameLayout(ctx).apply { clipChildren = false }
            val b = G.vbox(ctx, 2f, Gravity.CENTER_HORIZONTAL).apply {
                background = G.round(Color.WHITE, 16f, if (on) DC.gold else DC.line, if (on) 1.5f else 1f); G.shadow(this, 1.5f, G.c("#3c2814"), 16f)
                pad(0f, 10f, 0f, 10f); contentDescription = "${t.label}, $n designs"
            }
            b.addView(G.text(ctx, t.icon, 26f, DC.ink, false).lineH(1f).apply { gravity = Gravity.CENTER })
            b.addView(DC.t(ctx, t.label, 11.5f, DC.ink, true, 1.25f).apply { gravity = Gravity.CENTER; pad(4f, 0f, 4f, 0f) }, G.lp(G.MATCH, G.WRAP))
            b.addView(DC.t(ctx, "$n", 10f, DC.sub))
            G.click(b, 0.95f) { openWardrobe(t.key) }
            box.addView(b, LayoutParams(G.dpi(84f), G.WRAP))
            if (isHot) box.addView(G.text(ctx, "🎯", 12f, DC.ink, false), LayoutParams(G.WRAP, G.WRAP, Gravity.TOP or Gravity.END).m(t = -4f, r = -4f))
            row.addView(box, LinearLayout.LayoutParams(G.dpi(84f), G.WRAP))
        }
        hs.addView(row)
        contentCol.addView(hs, G.lp(G.MATCH, G.WRAP))
        contentCol.addView(label("Pehna hua · tap karke rang badlo"), G.lp(G.MATCH, G.WRAP).m(16f, 12f, 16f, 6f))
        val worn = view.look.values.mapNotNull { DressArt.byId[it.id] }
        val f = Flow(ctx, 6f, 6f)
        f.pad(12f, 0f, 12f, 0f)
        if (worn.isNotEmpty()) for (it in worn) {
            val c = G.hbox(ctx, 6f).apply { pad(4f, 0f, 12f, 0f); DC.whiteBtn(this, 99f); minimumHeight = G.dpi(32f) }
            c.addView(View(ctx).apply { background = G.oval(G.c(DressArt.colorHex(it.id, view.look[it.slot]?.c ?: 0))) }, LinearLayout.LayoutParams(G.dpi(24f), G.dpi(24f)))
            c.addView(DC.t(ctx, it.name, 12f, DC.ink, true))
            G.click(c, 0.97f) { openSheet(it) }
            f.addView(c)
        } else f.addView(DC.t(ctx, "Abhi kuch nahi. Upar se koi wardrobe kholo 👆", 13f, DC.sub).apply { pad(4f, 0f, 4f, 0f) })
        contentCol.addView(f, G.lp(G.MATCH, G.WRAP))
    }

    private fun girlPanel(view: DcView, theme: DressArt.Theme) {
        val ctx = context
        contentCol.pad(12f, 12f, 12f, 12f)
        val card = G.hbox(ctx, 12f).apply { DC.card(this, 22f); pad(14f) }
        card.addView(FrameLayout(ctx).apply {
            background = G.round(G.c("#fbf3df"), 16f)
            addView(G.icon(ctx, "Coins", DC.gold, 28f), LayoutParams(G.dpi(28f), G.dpi(28f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(G.dpi(48f), G.dpi(48f)))
        val mid = G.vbox(ctx)
        mid.addView(G.text(ctx, "TUMHARI KAMAI", 11.5f, DC.sub, true).apply { letterSpacing = 0.025f })
        val amt = SpannableStringBuilder(DressArt.coinFmt(view.girlEarned) + " ")
        val a = amt.length; amt.append("coins")
        amt.setSpan(android.text.style.AbsoluteSizeSpan(G.dpi(14f)), a, amt.length, 0); amt.setSpan(ForegroundColorSpan(DC.sub), a, amt.length, 0)
        mid.addView(DC.t(ctx, amt, 26f, DC.ink, true, 1.25f).apply { fontFeatureSettings = "tnum" })
        card.addView(mid, G.lpw(1f))
        card.addView(DC.t(ctx, "Har kharid ka\n${DressArt.GIRL_PERCENT}% tumhara", 11.5f, DC.sub, false, 1.375f).apply { gravity = Gravity.END })
        contentCol.addView(card, G.lp(G.MATCH, G.WRAP))
        contentCol.addView(DC.t(ctx, "Usse bolo, kya pehnana hai", 14f, DC.ink, true), G.lp(G.MATCH, G.WRAP).m(t = 16f, b = 6f))
        val asks = theme.wishes.map { "${it.t} chahiye ${it.e}" } + listOf("Premium wala pehnao 👑", "Ye accha hai 😍", "Kuch aur try karo 😎")
        val f = Flow(ctx, 8f, 8f)
        for (r in asks) {
            val b = G.text(ctx, r, 13f, DC.ink, true).apply { gravity = Gravity.CENTER; pad(12f, 0f, 12f, 0f); DC.whiteBtn(this, 99f); minimumHeight = G.dpi(36f) }
            if (view.chatLocked) { b.isEnabled = false; b.alpha = 0.4f }
            G.click(b, 0.95f) { h.chat(r) }
            f.addView(b)
        }
        contentCol.addView(f, G.lp(G.MATCH, G.WRAP))
        contentCol.addView(DC.t(ctx, "Abhi pehna hua", 14f, DC.ink, true), G.lp(G.MATCH, G.WRAP).m(t = 16f, b = 6f))
        val wearing = DressArt.visible(view.look).values.mapNotNull { DressArt.byId[it.id] }
        val wf = Flow(ctx, 6f, 6f)
        if (wearing.isNotEmpty()) for (it in wearing) {
            val c = G.hbox(ctx, 6f).apply { pad(12f, 0f, 12f, 0f); DC.whiteBtn(this, 99f); minimumHeight = G.dpi(32f) }
            c.addView(DC.t(ctx, it.name, 12f, DC.ink, true))
            if (it.tier != "n") c.addView(DC.tierBadge(ctx, it.tier, true))
            wf.addView(c)
        } else wf.addView(DC.t(ctx, "Abhi kuch nahi. ${view.names["boy"]?.ifEmpty { null } ?: "Partner"} styling shuru karega…", 13f, DC.sub))
        contentCol.addView(wf, G.lp(G.MATCH, G.WRAP))
    }

    // ── pops / sparks / banner (web keyframes dcPop, dcSpark, dcBanner; ease-out inside each keyframe step) ──
    private val pops = ArrayList<View>()
    /** a value at time [t] (0–1) from CSS keyframes [at] → [v], each step eased like animation-timing-function ease-out */
    private fun kf(t: Float, at: FloatArray, v: FloatArray): Float {
        var i = 0
        while (i < at.size - 2 && t > at[i + 1]) i++
        val span = at[i + 1] - at[i]
        val k = if (span <= 0f) 1f else PathIx.EASE_OUT.getInterpolation(((t - at[i]) / span).coerceIn(0f, 1f))
        return v[i] + (v[i + 1] - v[i]) * k
    }
    private fun pop(kind: String, text: String) {
        val ctx = context
        while (pops.size >= 3) popsBox.removeView(pops.removeAt(0)) // web: [...p.slice(-2), new]
        val p = G.text(ctx, text, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; pad(12f, 4f, 12f, 4f); maxLines = 1
            background = if (kind == "earn") CssBg(99f, listOf(CssBg.Linear(90f, intArrayOf(G.c("#16a34a"), G.c("#15803d"))))) else CssBg(99f, listOf(CssBg.Linear(90f, intArrayOf(G.c("#d4ad55"), G.c("#a8792a")))))
            G.shadow(this, 5f, Color.BLACK, 99f)
            alpha = 0f
        }
        pops.add(p)
        // web: left 50% (the keyframes' transform replaces the class's -50% shift), top (28 + i·9)% by its place in the list
        popsBox.addView(p, LayoutParams(G.WRAP, G.WRAP, Gravity.TOP or Gravity.START))
        val base = { val i = pops.indexOf(p).coerceAtLeast(0); popsBox.height * (0.28f + i * 0.09f) }
        val at = floatArrayOf(0f, 0.18f, 1f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                p.translationX = popsBox.width * 0.5f
                p.alpha = kf(t, at, floatArrayOf(0f, 1f, 0f))
                p.translationY = base() + G.dp(kf(t, at, floatArrayOf(8f, 0f, -70f)))
                val sc = kf(t, at, floatArrayOf(0.7f, 1.08f, 1f)); p.scaleX = sc; p.scaleY = sc
            }
            start()
        }
        later(1500) { if (pops.remove(p)) popsBox.removeView(p) }
    }
    private fun sparks() {
        val ctx = context
        val rnd = java.util.Random()
        repeat(5) {
            val s = G.text(ctx, "✨", 20f, DC.ink, false)
            fxBox.addView(s, LayoutParams(G.WRAP, G.WRAP))
            val x = 0.18f + rnd.nextFloat() * 0.64f; val y = 0.14f + rnd.nextFloat() * 0.60f
            s.alpha = 0f; s.scaleX = 0f; s.scaleY = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900; interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    s.translationX = fxBox.width * x; s.translationY = fxBox.height * y
                    s.alpha = kf(t, floatArrayOf(0f, 0.35f, 1f), floatArrayOf(0f, 1f, 0f))
                    val k = kf(t, floatArrayOf(0f, 1f), floatArrayOf(0f, 1f)) // transform: 0% → 100%
                    s.scaleX = 1.5f * k; s.scaleY = 1.5f * k; s.rotation = 120f * k
                }
                start()
            }
            later(1000) { fxBox.removeView(s) }
        }
    }
    private var bannerV: View? = null
    private fun banner(top: String, text: String) {
        val ctx = context
        bannerV?.let { fxBox.removeView(it) }
        val b = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply {
            background = G.round(G.ca("#ffffff", 0.96f), 24f, DC.gold, 1.5f); pad(20f, 12f, 20f, 12f)
            G.shadow(this, 10f, G.c("#3c2814"), 24f)
        }
        b.addView(G.text(ctx, top.uppercase(), 11f, DC.gold, true).lineH(1.5f).apply { letterSpacing = 0.1f; gravity = Gravity.CENTER })
        b.addView(DC.t(ctx, text, 22f, DC.ink, true, 1.25f).apply { gravity = Gravity.CENTER; maxLines = 1 })
        bannerV = b
        fxBox.addView(b, LayoutParams(G.WRAP, G.WRAP, Gravity.CENTER))
        b.alpha = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                b.alpha = kf(t, floatArrayOf(0f, 0.15f, 0.8f, 1f), floatArrayOf(0f, 1f, 1f, 0f))
                val sc = kf(t, floatArrayOf(0f, 0.15f, 0.25f, 1f), floatArrayOf(0.6f, 1.06f, 1f, 1f)); b.scaleX = sc; b.scaleY = sc
                b.translationY = b.height * kf(t, floatArrayOf(0f, 0.15f, 0.25f, 1f), floatArrayOf(0f, 0f, 0f, -0.1f)) // translate(-50%, -50% → -60%)
            }
            start()
        }
        later(2500) { if (bannerV === b) { fxBox.removeView(b); bannerV = null } }
    }
    /** dcBump: 1 → 1.25 (40%) → 1, ease-out in each step */
    private fun bump(v: View, ms: Long) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a -> val sc = kf(a.animatedValue as Float, floatArrayOf(0f, 0.4f, 1f), floatArrayOf(1f, 1.25f, 1f)); v.scaleX = sc; v.scaleY = sc }
            start()
        }
    }

    // ── wardrobe / item sheet / full view ──
    private fun openWardrobe(key: String) {
        if (wardrobe != null || v?.me == "girl") return
        val w = DcWardrobe(context, key, onClose = { closeWardrobe() }, onOpenItem = { openSheet(it) })
        wardrobe = w
        addView(w, LayoutParams(G.MATCH, G.MATCH))
        v?.let { w.bind(it, theme()) }
        sheet?.bringToFront()
    }
    private fun closeWardrobe() { wardrobe?.let { it.dispose(); removeView(it) }; wardrobe = null }
    private fun openSheet(item: DressArt.Item) {
        val vv = v ?: return
        if (vv.me == "girl") return
        closeSheet()
        val s = DcItemSheet(context, item, vv, practice, onClose = { closeSheet() },
            onWear = { id, c -> h.buy(id, c) { ok -> if (ok) closeSheet() } },
            onRemove = { slot -> h.remove(slot) { closeSheet() } })
        sheet = s
        addView(s, LayoutParams(G.MATCH, G.MATCH))
        s.bind(vv, busy)
    }
    private fun closeSheet() { sheet?.let { removeView(it) }; sheet = null }
    private fun openFull() {
        if (full != null) return
        val ctx = context
        val f = FrameLayout(ctx).apply { setBackgroundColor(DC.bg); isClickable = true }
        val c = G.vbox(ctx)
        c.addView(dcTopBar(ctx, "Doll ko dekho", DC.t(ctx, "Do ungli se zoom · swipe karke ghumao", 12f, DC.sub).ellipsize1(), null, "ChevronLeft", "Close full view") { closeFull() }, G.lp(G.MATCH, G.WRAP))
        val box = FrameLayout(ctx).apply {
            background = CssBg(28f, listOf(CssBg.Radial(0.5f, 0.42f, 0.7f, 0.6f, intArrayOf(Color.WHITE, G.c("#f4ecdf")))), border = DC.line, borderDp = 1f)
            clipToOutline = true
        }
        val fv = DollViewer(ctx, true)
        v?.let { fv.setLook(it.look) }
        box.addView(fv, LayoutParams(G.MATCH, G.MATCH))
        c.addView(box, G.lpw(1f, 0).apply { width = G.MATCH; setMargins(G.dpi(12f), 0, G.dpi(12f), G.dpi(12f)) })
        f.addView(c, LayoutParams(G.MATCH, G.MATCH))
        f.tag = fv
        full = f
        addView(f, LayoutParams(G.MATCH, G.MATCH))
    }
    private fun closeFull() { full?.let { removeView(it) }; full = null }

    // ── end of round (web Reveal) ──
    private fun bindReveal(view: DcView, theme: DressArt.Theme, score: DressArt.Score) {
        (full?.tag as? DollViewer)?.setLook(view.look)
        if (view.status != "reveal") { reveal?.let { removeView(it) }; reveal = null; revealKey = ""; return }
        val key = "${view.round}|${view.rating}|$busy|${score.total}|${view.girlEarned}|${view.spent}|${view.names}"
        if (key == revealKey) return
        val firstShow = reveal == null
        revealKey = key
        reveal?.let { removeView(it) }
        val ctx = context
        val girlSide = view.me == "girl"
        val doneN = score.done.count { it }
        val next = DressArt.themeFor(view.round + 1)
        val r = FrameLayout(ctx).apply {
            background = CssBg(0f, listOf(CssBg.Radial(0.5f, 0.3f, 0.8f, 0.5f, intArrayOf(Color.WHITE, G.c("#f4ecdf")))))
            isClickable = true
        }
        val sc = scrollPage(ctx)
        val c = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        c.pad(20f, 16f, 20f, 20f)
        sc.addView(c, LayoutParams(G.MATCH, G.MATCH))
        r.addView(sc, LayoutParams(G.MATCH, G.MATCH))
        c.addView(G.text(ctx, "ROUND ${view.round} · ${theme.icon} ${theme.title.uppercase()}", 12f, DC.gold, true).apply { letterSpacing = 0.1f; gravity = Gravity.CENTER })
        c.addView(DC.t(ctx, "✨ Final look", 22f, DC.ink, true))
        val stageBox = FrameLayout(ctx)
        val rv = DollViewer(ctx, true)
        rv.setLook(view.look)
        stageBox.addView(rv, LayoutParams(G.MATCH, G.MATCH))
        val gcol = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        val gb = G.text(ctx, score.grade, 44f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = CssBg(24f, listOf(CssBg.Linear(135f, gradeColors(score.grade)))); G.shadow(this, 8f, G.c("#3c2814"), 24f) }
        gcol.addView(gb, LinearLayout.LayoutParams(G.dpi(80f), G.dpi(80f)))
        gcol.addView(DC.t(ctx, "${score.total}/100", 13f, DC.ink, true), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        gcol.contentDescription = "Rank ${score.grade}, ${score.total} out of 100"
        stageBox.addView(gcol, LayoutParams(G.WRAP, G.WRAP, Gravity.TOP or Gravity.END))
        if (firstShow) bump(gb, 700)
        val hh = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
        val clamp = object : FrameLayout(ctx) {
            override fun onMeasure(ws: Int, hs: Int) {
                val given = if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED) 0 else MeasureSpec.getSize(hs)
                val use = min((hh * 0.48f).roundToInt(), max(G.dpi(300f), given))
                super.onMeasure(ws, MeasureSpec.makeMeasureSpec(use, MeasureSpec.EXACTLY))
            }
        }
        clamp.addView(stageBox, LayoutParams(G.MATCH, G.MATCH))
        c.addView(clamp, LinearLayout.LayoutParams(G.MATCH, 0, 1f).m(t = 4f))
        val chips = Flow(ctx, 8f, 8f, center = true)
        chips.addView(DC.chip(ctx).apply { addView(DC.t(ctx, "🎯 Missions $doneN/3", 13f, DC.ink, true)) })
        if (score.combo) chips.addView(DC.chip(ctx).apply { addView(DC.t(ctx, "🎨 Colour combo", 13f, DC.ink, true)) })
        chips.addView(DC.chip(ctx).apply {
            addView(DC.coin(ctx, if (girlSide) view.girlEarned else view.spent, 14f, 13f))
            addView(DC.t(ctx, if (girlSide) "kamaye" else "lage", 13f, DC.ink, true))
        })
        c.addView(chips, G.lp(G.MATCH, G.WRAP).m(t = 8f))
        val rate = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        val bName = view.names["boy"]?.ifEmpty { null } ?: "Partner"
        val gName = view.names["girl"]?.ifEmpty { null } ?: "She"
        if (girlSide) {
            if (view.rating > 0) {
                rate.addView(StarsView(ctx).apply { value = view.rating })
                rate.addView(DC.t(ctx, "Thanks! $bName ko tumhare stars mil gaye.", 13f, DC.sub).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP).m(t = 8f))
            } else {
                rate.addView(DC.t(ctx, "Stylist ko stars do ⭐", 14f, DC.ink).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP).m(b = 8f))
                rate.addView(StarsView(ctx, onPick = { n -> h.rate(n) }))
            }
        } else {
            rate.addView(StarsView(ctx).apply { value = view.rating })
            rate.addView(DC.t(ctx, if (view.rating > 0) "$gName ne ${view.rating} stars diye! 🎉" else "$gName stars de rahi hai…", 13f, DC.sub).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP).m(t = 8f))
        }
        c.addView(rate, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        val btns = G.vbox(ctx, 8f)
        if (!girlSide) {
            val nb = G.text(ctx, "Next round: ${next.icon} ${next.title} ▶", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; DC.goldBtn(this, 16f) }
            if (busy) { nb.isEnabled = false; nb.alpha = 0.6f }
            G.click(nb, 0.98f) { h.newlook() }
            btns.addView(nb, G.lp(G.MATCH, G.dpi(48f)))
        } else btns.addView(DC.t(ctx, "$bName agla round shuru karega…", 12.5f, DC.sub).apply { gravity = Gravity.CENTER }, G.lp(G.MATCH, G.WRAP))
        val done = G.text(ctx, "Done", 15f, DC.ink, true).apply { gravity = Gravity.CENTER; DC.whiteBtn(this, 16f) }
        G.click(done, 0.98f) { onExit() }
        btns.addView(done, G.lp(G.MATCH, G.dpi(44f)))
        c.addView(btns, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        reveal = r
        addView(r, LayoutParams(G.MATCH, G.MATCH))
        if (firstShow) { closeSheet(); closeWardrobe() }
    }
    private fun gradeColors(g: String): IntArray = when (g) {
        "S" -> intArrayOf(G.c("#f6dc8a"), G.c("#c9962c")); "A" -> intArrayOf(G.c("#f4b6cf"), G.c("#b0174f")); "B" -> intArrayOf(G.c("#b9c6f5"), G.c("#4153b8"))
        "C" -> intArrayOf(G.c("#a9e4cb"), G.c("#13805a")); else -> intArrayOf(G.c("#d6d1ca"), G.c("#8b837a"))
    }

    fun demoWardrobe(key: String) = openWardrobe(key)
    fun demoSheet(item: DressArt.Item) = openSheet(item)

    /** phone back: the top layer closes first */
    fun onBack(): Boolean {
        if (sheet != null) { closeSheet(); return true }
        if (full != null) { closeFull(); return true }
        if (wardrobe != null) { closeWardrobe(); return true }
        return false
    }
}

/** shared easing (CSS ease-out) */
object PathIx { val EASE_OUT = android.view.animation.PathInterpolator(0f, 0f, 0.58f, 1f) }

// ═════════════════════ Full-screen wardrobe ═════════════════════
@SuppressLint("ViewConstructor")
class DcWardrobe(ctx: Context, private val tabKey: String, private val onClose: () -> Unit, private val onOpenItem: (DressArt.Item) -> Unit) : FrameLayout(ctx) {
    private val def = DressArt.tabs.first { it.key == tabKey }
    private val all = DressArt.items.filter { def.slots.contains(it.slot) }
    private var tier = "all"
    private var base = "all"
    private var v: DcView? = null
    private var theme: DressArt.Theme? = null
    private val col = G.vbox(ctx)
    private val sub: TextView
    private val mini = DollView(ctx)
    private val tierRow = G.hbox(ctx, 6f)
    private val baseRow = G.hbox(ctx, 6f)
    private val scroll = ScrollView(ctx)
    private val grid = G.vbox(ctx, 12f)
    private val empty: TextView
    private val cards = ArrayList<Pair<DressArt.Item, Card>>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private inner class Card(val root: LinearLayout, val thumb: ThumbView, val thumbBox: FrameLayout, val foot: LinearLayout)

    init {
        setBackgroundColor(DC.bg); isClickable = true
        addView(col, LayoutParams(G.MATCH, G.MATCH))
        sub = DC.t(ctx, "", 12f, DC.sub).ellipsize1()
        val right = FrameLayout(ctx).apply { DC.whiteBtn(this, 16f); clipToOutline = true; contentDescription = "Back to doll" }
        right.addView(mini, LayoutParams(G.MATCH, G.MATCH))
        G.click(right, 0.97f) { onClose() }
        val tb = dcTopBar(ctx, "${def.icon} ${def.label}", sub, right, "ChevronLeft", "Back to doll") { onClose() }
        (right.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = G.dpi(54f); it.height = G.dpi(84f) } ?: run { right.layoutParams = LinearLayout.LayoutParams(G.dpi(54f), G.dpi(84f)) }
        col.addView(tb, G.lp(G.MATCH, G.WRAP))
        val filters = G.vbox(ctx, 8f).apply { pad(16f, 0f, 16f, 8f) }
        filters.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(tierRow) }, G.lp(G.MATCH, G.WRAP))
        val bases = LinkedHashMap<String, String>(); for (it in all) if (!bases.containsKey(it.base)) bases[it.base] = it.baseName
        if (bases.size > 1) filters.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(baseRow) }, G.lp(G.MATCH, G.WRAP))
        col.addView(filters, G.lp(G.MATCH, G.WRAP))
        scroll.isVerticalScrollBarEnabled = false; scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val inner = G.vbox(ctx).apply { pad(16f, 0f, 16f, 20f) }
        inner.addView(grid, G.lp(G.MATCH, G.WRAP))
        empty = DC.t(ctx, "Is filter me kuch nahi mila.", 14f, DC.sub).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        inner.addView(empty, G.lp(G.MATCH, G.WRAP).m(t = 40f))
        scroll.addView(inner, LayoutParams(G.MATCH, G.WRAP))
        col.addView(scroll, G.lpw(1f, 0).apply { width = G.MATCH })
        scroll.viewTreeObserver.addOnScrollChangedListener { activate() }
        // filter chips
        fun tierChips() {
            tierRow.removeAllViews()
            for ((k, l) in listOf("all" to "All", "n" to "Normal · 1", "p" to "✦ Premium · 2", "s" to "👑 Super · 3-5")) {
                val on = tier == k
                val b = G.text(ctx, l, 12.5f, if (on) Color.WHITE else DC.ink, true).apply {
                    gravity = Gravity.CENTER; pad(12f, 0f, 12f, 0f)
                    if (on) background = G.round(DC.ink, 99f) else DC.whiteBtn(this, 99f)
                }
                G.click(b, 0.96f) { tier = k; tierChips(); fill() }
                tierRow.addView(b, LinearLayout.LayoutParams(G.WRAP, G.dpi(32f)))
            }
        }
        fun baseChips() {
            baseRow.removeAllViews()
            for ((k, l) in listOf("all" to "Sab") + bases.entries.map { it.key to it.value }) {
                val on = base == k
                val b = G.text(ctx, l, 12.5f, if (on) G.c("#7a5410") else DC.ink, true).apply {
                    gravity = Gravity.CENTER; pad(12f, 0f, 12f, 0f)
                    background = if (on) G.round(G.c("#fbf3df"), 99f, DC.gold, 1f) else G.round(DC.soft, 99f)
                }
                G.click(b, 0.96f) { base = k; baseChips(); fill() }
                baseRow.addView(b, LinearLayout.LayoutParams(G.WRAP, G.dpi(32f)))
            }
        }
        tierChips(); baseChips()
        alpha = 0f; animate().alpha(1f).setDuration(160).start()
    }

    fun dispose() { main.removeCallbacksAndMessages(null) }

    fun bind(view: DcView, th: DressArt.Theme) {
        val first = v == null
        v = view; theme = th
        mini.set(view.look, false); mini.contentDescription = "Your doll"
        if (first) fill() else paintCards()
    }

    private fun list() = all.filter { (tier == "all" || it.tier == tier) && (base == "all" || it.base == base) }

    private fun fill() {
        val ctx = context
        val vv = v ?: return
        val th = theme ?: return
        val list = list()
        val hotTags = th.wishes.flatMap { it.tags }.toSet()
        val tabHot = all.any { it.tags.any { t -> hotTags.contains(t) } }
        sub.text = "${list.size} designs${if (tabHot) " · 🎯 mission ke kaam ka" else ""}"
        grid.removeAllViews(); cards.clear()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.chunked(2).forEach { pair ->
            val row = G.hbox(ctx, 12f, Gravity.TOP)
            for (it in pair) {
                val c = card(ctx, it)
                cards.add(it to c)
                row.addView(c.root, G.lpw(1f))
            }
            if (pair.size == 1) row.addView(View(ctx), G.lpw(1f))
            grid.addView(row, G.lp(G.MATCH, G.WRAP))
        }
        paintCards()
        scroll.scrollTo(0, 0)
        scroll.post { activate() }
    }

    private fun card(ctx: Context, item: DressArt.Item): Card {
        val root = G.vbox(ctx).apply { pad(8f) }
        val box = FrameLayout(ctx)
        val thumbBox = FrameLayout(ctx).apply {
            background = CssBg(16f, listOf(CssBg.Linear(160f, when (item.tier) {
                "s" -> intArrayOf(G.c("#fff8e6"), G.c("#f3e2b8")); "p" -> intArrayOf(G.c("#fffdf8"), G.c("#f5ecdc")); else -> intArrayOf(G.c("#fdfcfa"), G.c("#f2eee8"))
            })))
            pad(6f); clipToOutline = true
        }
        val th = ThumbView(ctx)
        thumbBox.addView(th, LayoutParams(G.MATCH, G.MATCH))
        val sq = object : FrameLayout(ctx) {
            override fun onMeasure(ws: Int, hs: Int) { val w = MeasureSpec.getSize(ws); super.onMeasure(ws, MeasureSpec.makeMeasureSpec(w * 5 / 4, MeasureSpec.EXACTLY)) } // aspect 4 / 5
        }
        sq.addView(thumbBox, LayoutParams(G.MATCH, G.MATCH))
        box.addView(sq, LayoutParams(G.MATCH, G.WRAP))
        box.addView(DC.tierBadge(ctx, item.tier, true).apply { layoutParams = LayoutParams(G.WRAP, G.dpi(20f)).m(l = 6f, t = 6f) })
        root.addView(box, G.lp(G.MATCH, G.WRAP))
        val name = DC.t(ctx, item.name, 13f, DC.ink, true, 1.25f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; minHeight = G.dpi(13f * 2.2f) }
        root.addView(name, G.lp(G.MATCH, G.WRAP).m(t = 6f))
        val foot = G.hbox(ctx)
        root.addView(foot, G.lp(G.MATCH, G.WRAP).m(t = 4f))
        G.click(root, 0.97f) { onOpenItem(item) }
        return Card(root, th, thumbBox, foot)
    }

    private fun paintCards() {
        val vv = v ?: return
        val ctx = context
        for ((item, c) in cards) {
            val owned = vv.owned.contains(item.id)
            val worn = vv.look[item.slot]?.id == item.id
            val col = if (worn) vv.look[item.slot]!!.c else 0
            c.thumb.set(item, col)
            if (worn) { c.root.background = G.round(Color.WHITE, 20f, DC.gold, 2f); G.shadow(c.root, 6f, G.c("#a8792a"), 20f) }
            else { c.root.background = G.round(Color.WHITE, 20f, DC.line, 1f); G.shadow(c.root, 1.5f, G.c("#3c2814"), 20f) }
            c.root.contentDescription = "${item.name}, ${tierName(item.tier)}, ${item.price} coin${if (item.price > 1) "s" else ""}${if (owned) ", owned" else ""}${if (worn) ", wearing" else ""}"
            c.foot.removeAllViews()
            val left: View = when {
                worn -> DC.t(ctx, "✓ Pehna hai", 11.5f, DC.gold, true)
                owned -> DC.t(ctx, "✓ Aapki hai", 11.5f, DC.green, true)
                else -> DC.coin(ctx, item.price.toDouble(), 16f, 13f)
            }
            c.foot.addView(left, G.lpw(1f))
            val dots = FrameLayout(ctx)
            item.cols.take(4).forEachIndexed { i, ci ->
                dots.addView(View(ctx).apply { background = G.oval(G.c(DressArt.colors[ci].second), Color.WHITE, 1f) }, LayoutParams(G.dpi(14f), G.dpi(14f)).m(l = i * 10f))
            }
            c.foot.addView(dots, LinearLayout.LayoutParams(G.dpi(14f + 3 * 10f), G.dpi(14f)))
        }
    }
    private fun tierName(t: String) = when (t) { "s" -> "Super Premium"; "p" -> "Premium"; else -> "Normal" }

    /** only the cards near the screen keep their pictures (a long list doesn't fill the memory) */
    private fun activate() {
        val top = scroll.scrollY - scroll.height; val bottom = scroll.scrollY + scroll.height * 2
        for ((_, c) in cards) {
            val row = c.root.parent as? View ?: continue
            val y = grid.top + row.top
            c.thumb.active = y + row.height >= top && y <= bottom
        }
    }
}

// ═════════════════════ Item sheet: try-on preview + colour + Confirm ═════════════════════
@SuppressLint("ViewConstructor")
class DcItemSheet(ctx: Context, private val item: DressArt.Item, start: DcView, private val practice: Boolean, private val onClose: () -> Unit,
                  private val onWear: (String, Int) -> Unit, private val onRemove: (String) -> Unit) : FrameLayout(ctx) {
    private var c = if (start.look[item.slot]?.id == item.id) start.look[item.slot]!!.c else 0
    private var v = start
    private var busy = false
    private val panel = G.vbox(ctx)
    private val preview = DollViewer(ctx, controls = false)
    private val colorName: TextView
    private val priceBox = G.vbox(ctx)
    private val swatches = ArrayList<View>()
    private val actions = G.vbox(ctx, 8f)

    init {
        isClickable = true
        val dim = View(ctx).apply { setBackgroundColor(G.ca("#1f1b16", 0.35f)) }
        dim.setOnClickListener { onClose() }
        addView(dim, LayoutParams(G.MATCH, G.MATCH))
        val sc = object : ScrollView(ctx) {
            override fun onMeasure(ws: Int, hs: Int) { super.onMeasure(ws, MeasureSpec.makeMeasureSpec(((this@DcItemSheet.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 0.92f).roundToInt(), MeasureSpec.AT_MOST)) }
        }
        sc.isVerticalScrollBarEnabled = false
        sc.background = CssBg(floatArrayOf(28f, 28f, 0f, 0f), emptyList(), Color.WHITE)
        sc.isClickable = true
        panel.pad(0f, 10f, 0f, 16f)
        sc.addView(panel, LayoutParams(G.MATCH, G.WRAP))
        val wrap = FrameLayout(ctx)
        wrap.addView(sc, LayoutParams(G.MATCH, G.WRAP))
        addView(wrap, LayoutParams(G.MATCH, G.WRAP, Gravity.BOTTOM))
        // grip + close
        panel.addView(View(ctx).apply { background = G.round(DC.line, 3f) }, LinearLayout.LayoutParams(G.dpi(40f), G.dpi(6f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        wrap.addView(DC.roundBtn(ctx, "X", 36f, 16f, "Close") { onClose() }, LayoutParams(G.dpi(36f), G.dpi(36f), Gravity.TOP or Gravity.END).m(t = 12f, r = 12f))
        // preview + details
        val top = G.hbox(ctx, 12f, Gravity.TOP).apply { pad(16f, 12f, 16f, 0f) }
        val pv = FrameLayout(ctx).apply { background = CssBg(16f, listOf(CssBg.Radial(0.5f, 0.4f, 0.8f, 0.7f, intArrayOf(Color.WHITE, G.c("#f3ece1"))))); clipToOutline = true }
        pv.addView(preview, LayoutParams(G.MATCH, G.MATCH))
        top.addView(pv, LinearLayout.LayoutParams(0, G.dpi(260f), 44f))
        val det = G.vbox(ctx).apply { pad(0f, 4f, 0f, 0f) }
        det.addView(DC.tierBadge(ctx, item.tier))
        det.addView(DC.t(ctx, item.name, 19f, DC.ink, true, 1.25f), G.lp(G.MATCH, G.WRAP).m(t = 6f))
        val pat = if (item.pat.isNotEmpty() && item.pat != "plain") DressArt.patterns[item.pat]?.first ?: "Classic" else "Classic"
        det.addView(DC.t(ctx, "${item.baseName} · $pat", 12.5f, DC.sub), G.lp(G.MATCH, G.WRAP).m(t = 4f))
        colorName = DC.t(ctx, "", 12.5f, DC.sub)
        det.addView(colorName, G.lp(G.MATCH, G.WRAP).m(t = 2f))
        priceBox.background = G.round(DC.soft, 16f); priceBox.pad(12f, 8f, 12f, 8f)
        det.addView(priceBox, G.lp(G.MATCH, G.WRAP).m(t = 12f))
        top.addView(det, LinearLayout.LayoutParams(0, G.WRAP, 56f))
        panel.addView(top, G.lp(G.MATCH, G.WRAP))
        // colours
        val cs = G.vbox(ctx).apply { pad(16f, 12f, 16f, 0f) }
        cs.addView(G.text(ctx, "RANG CHUNO", 12f, DC.sub, true).apply { letterSpacing = 0.025f }, G.lp(G.WRAP, G.WRAP).m(b = 6f))
        val sw = G.hbox(ctx, 10f)
        sw.contentDescription = "Colours"
        sw.clipChildren = false; cs.clipChildren = false; cs.clipToPadding = false
        item.cols.forEachIndexed { k, ci ->
            val s = View(ctx).apply { contentDescription = DressArt.colors[ci].first }
            G.click(s, 0.9f) { c = k; paint() }
            swatches.add(s)
            sw.addView(s, LinearLayout.LayoutParams(G.dpi(40f), G.dpi(40f)))
        }
        cs.addView(sw)
        panel.addView(cs, G.lp(G.MATCH, G.WRAP))
        actions.pad(16f, 16f, 16f, 0f)
        panel.addView(actions, G.lp(G.MATCH, G.WRAP))
        sc.translationY = G.dp(800f)
        sc.post { sc.translationY = sc.height.toFloat(); sc.animate().translationY(0f).setDuration(220).setInterpolator(PathIx.EASE_OUT).start() }
        paint()
    }

    fun bind(view: DcView, busyNow: Boolean) { v = view; busy = busyNow; paint() }

    private fun paint() {
        val ctx = context
        val owned = v.owned.contains(item.id)
        val worn = v.look[item.slot]?.id == item.id
        val look = LinkedHashMap(v.look); look[item.slot] = DressArt.Wear(item.id, c)
        preview.setLook(look)
        val sameAsWorn = worn && v.look[item.slot]?.c == c
        val short = !practice && !owned && v.balance != null && v.balance!! < item.price
        val nm = SpannableStringBuilder("Rang: "); val a = nm.length; nm.append(DressArt.colorName(item.id, c))
        nm.setSpan(FontSpan(app.truefind.android.Ui.bold), a, nm.length, 0); nm.setSpan(ForegroundColorSpan(DC.ink), a, nm.length, 0)
        colorName.text = nm
        priceBox.removeAllViews()
        if (owned) priceBox.addView(DC.t(ctx, "✓ Aapki hai · rang badalna free", 13f, G.c("#166534"), true))
        else {
            val r = G.hbox(ctx, 4f)
            r.addView(DC.t(ctx, "Keemat:", 13f, DC.ink, true)); r.addView(DC.coin(ctx, item.price.toDouble(), 16f, 13f)); r.addView(DC.t(ctx, "coin", 13f, DC.ink, true))
            priceBox.addView(r)
            priceBox.addView(DC.t(ctx, "Uske baad saare rang free", 11f, DC.sub), G.lp(G.WRAP, G.WRAP).m(t = 2f))
        }
        swatches.forEachIndexed { k, s ->
            val on = c == k
            val hex = G.c(DressArt.colors[item.cols[k]].second)
            s.background = if (on) object : android.graphics.drawable.Drawable() {
                private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun draw(cv: android.graphics.Canvas) {
                    val b = bounds; val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = b.width() / 2f
                    p.color = DC.gold; cv.drawCircle(cx, cy, r + G.dp(2.5f), p)   // box-shadow 0 0 0 2.5px gold
                    p.color = Color.WHITE; cv.drawCircle(cx, cy, r, p)            // 3 px white border
                    p.color = hex; cv.drawCircle(cx, cy, r - G.dp(3f), p)
                }
                override fun setAlpha(al: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            } else G.oval(hex, DC.line, 1.5f)
        }
        actions.removeAllViews()
        if (short) actions.addView(DC.t(ctx, "Coins kam hain. Recharge karke aao!", 13f, DC.red, true).apply { gravity = Gravity.CENTER; background = G.round(G.c("#fef2f2"), 12f); pad(0f, 8f, 0f, 8f) }, G.lp(G.MATCH, G.WRAP))
        if (owned) {
            if (sameAsWorn) {
                val b = G.text(ctx, "Utaro (free)", 15f, DC.ink, true).apply { gravity = Gravity.CENTER; DC.whiteBtn(this, 16f) }
                if (busy) { b.isEnabled = false; b.alpha = 0.6f }
                G.click(b, 0.98f) { onRemove(item.slot) }
                actions.addView(b, G.lp(G.MATCH, G.dpi(48f)))
            } else {
                val b = G.text(ctx, if (worn) "Rang badlo (free)" else "Pehnao (free)", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; DC.goldBtn(this, 16f) }
                if (busy) { b.isEnabled = false; b.alpha = 0.6f }
                G.click(b, 0.98f) { onWear(item.id, c) }
                actions.addView(b, G.lp(G.MATCH, G.dpi(48f)))
            }
        } else {
            val b = G.hbox(ctx, 8f, Gravity.CENTER).apply { DC.goldBtn(this, 16f); pad(0f, 14f, 0f, 14f) }
            if (busy) b.addView(TfSpinner(ctx, 20f, white = true), LinearLayout.LayoutParams(G.dpi(20f), G.dpi(20f)))
            else {
                b.addView(DC.t(ctx, "Confirm ·", 16f, Color.WHITE, true))
                b.addView(G.icon(ctx, "Coins", Color.WHITE, 20f))
                b.addView(DC.t(ctx, "${item.price}", 16f, Color.WHITE, true))
                if (practice) b.addView(DC.t(ctx, "(practice: free)", 12f, Color.WHITE, true))
            }
            if (busy || short) { b.isEnabled = false; b.alpha = 0.5f }
            G.click(b, 0.98f) { onWear(item.id, c) }
            actions.addView(b, G.lp(G.MATCH, G.WRAP))
        }
        val cancel = G.text(ctx, "Cancel", 15f, DC.sub, true).apply { gravity = Gravity.CENTER }
        G.click(cancel, 0.98f) { onClose() }
        actions.addView(cancel, G.lp(G.MATCH, G.dpi(44f)))
    }
}
