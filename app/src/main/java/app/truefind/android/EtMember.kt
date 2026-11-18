package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Expert Talk — the member's side (MemberApp.jsx), native: find an expert, book (any day up to
 * 30 days ahead), pay with coins, change the date, call, rate, report; and "Become an Expert"
 * for verified creators. Same server function (expertTalk), same rules, same look.
 */
object EtM {
    val UPCOMING = listOf("confirmed", "ringing", "live")
    val DONE = listOf("completed", "user_no_show", "cut_by_report")
    val CANCELLED = listOf("refunded", "expert_no_show", "expert_left")
    val STATUS_TXT = mapOf(
        "completed" to ("Completed" to "ok"), "user_no_show" to ("You didn't call" to "grey"), "cut_by_report" to ("Ended after reports" to "bad"),
        "refunded" to ("Refunded" to "grey"), "expert_no_show" to ("Expert missed · refunded" to "warn"), "expert_left" to ("Expert left · refunded" to "grey"),
    )

    class Cat(val k: String, val l: String, val s: String, val icon: String)
    val CATS = listOf(
        Cat("breakup", "Breakup Support", "Breakup", EtIcon.HeartCrack), Cat("rel", "Relationship Problems", "Relationship", EtIcon.Users),
        Cat("emo", "Emotional Support", "Emotional support", EtIcon.Heart), Cat("stress", "Stress & Overthinking", "Stress & overthinking", EtIcon.Cloud),
        Cat("lonely", "Loneliness", "Loneliness", EtIcon.Moon), Cat("family", "Family Problems", "Family", EtIcon.House),
        Cat("friend", "Friendship Issues", "Friendship", EtIcon.Smile), Cat("conf", "Confidence Building", "Confidence", EtIcon.Star),
        Cat("career", "Career Guidance", "Career", EtIcon.Briefcase), Cat("growth", "Personal Growth", "Personal growth", EtIcon.Sprout),
        Cat("pro", "Life Guidance", "Counselling", EtIcon.Compass),
    )

    class Feel(val q: String, val a: String, val cat: String)
    val FEELS = listOf(
        Feel("So many people around me, yet no one I can really talk to.", "You are not alone in this.", "lonely"),
        Feel("You have survived every hard day so far. That is strength.", "Be proud of yourself today.", "conf"),
        Feel("I smile all day, but the nights feel very heavy.", "It's okay to say it out loud.", "emo"),
        Feel("One good conversation can change a whole day.", "Yours can start right now.", "emo"),
        Feel("I just want someone to listen, without judging me.", "Someone here will listen.", "emo"),
        Feel("You are not too much. You were just talking to the wrong people.", "The right listener is here.", "lonely"),
        Feel("After the breakup, everything feels empty.", "One talk can be the first step.", "breakup"),
        Feel("Your heart is not broken forever. It is learning to love again.", "Take it one day at a time.", "breakup"),
        Feel("My mind never stops. I overthink everything.", "Let's make it a little lighter.", "stress"),
        Feel("Slow down. You don't have to solve your whole life tonight.", "Just breathe, then talk.", "stress"),
        Feel("I miss someone asking me, “How was your day?”", "Tell us about your day.", "lonely"),
        Feel("Someone out there is happy that you exist.", "You matter more than you think.", "lonely"),
        Feel("I feel like nobody would notice if I went quiet.", "We would. Come and talk.", "lonely"),
        Feel("It's okay to rest. Even the sun sets every day and still rises.", "Tomorrow is a fresh start.", "growth"),
        Feel("I am tired of being strong for everyone.", "Here, you can just be you.", "emo"),
        Feel("Asking for support is not weakness. It is courage.", "You are braver than you feel.", "conf"),
        Feel("I have so much to say, but nobody to say it to.", "Say it here, safely.", "lonely"),
        Feel("Good things are still coming to you. Don't close the door yet.", "Keep a little hope today.", "growth"),
        Feel("At home, nobody understands what I feel.", "You deserve to be heard.", "family"),
        Feel("You are allowed to start again, as many times as you need.", "Today counts as day one.", "growth"),
        Feel("My friends are busy with their own lives now.", "A caring voice is one tap away.", "friend"),
        Feel("A small smile today is still a win.", "Let's find one together.", "emo"),
        Feel("I keep replaying old memories again and again.", "Let's untangle them slowly.", "stress"),
        Feel("The love you give so freely to others, you deserve it too.", "Be kind to yourself today.", "rel"),
        Feel("I don't know what I am doing with my life.", "You don't have to figure it out alone.", "career"),
        Feel("Your story is not over. This is just a difficult chapter.", "Better pages are ahead.", "growth"),
        Feel("I feel left out, even when I am with people.", "Here, you belong.", "friend"),
        Feel("You are someone's reason to smile, even if you don't know it.", "Carry that with you today.", "conf"),
    )
    /** today's six (the list moves five steps a day; it comes round after 4 weeks) */
    fun feelsToday(date: String): List<Feel> {
        val day = Math.floorDiv(Et.dayMs(date), 86_400_000L)
        val n = FEELS.size
        val start = (((day * 5) % n + n) % n).toInt()
        return (0 until 6).map { FEELS[(start + it) % n] }
    }

    fun minPrice(e: JSONObject): Int = (Et.ints(e.optJSONArray("durs")).ifEmpty { listOf(25) }).minOf { when (it) { 25 -> 250; 35 -> 350; 45 -> 450; else -> 250 } }
    fun freeToday(e: JSONObject, today: String) = e.optJSONObject("next")?.let { Et.s(it, "date") == today } ?: false
    fun jsNum(v: Double): String = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()

    /** "★ 4.8 · 12 h talked" / "New · 12 h talked" — the grey line under a name */
    fun ratingLine(e: JSONObject, withCount: Boolean, tail: String): CharSequence {
        val sp = EtSpan()
        if (Et.i(e, "reviews") > 0) {
            sp.add("★", Et.GOLD).add(" ").add(Et.fixed1(Et.d(e, "rating")), Et.INK, true)
            if (withCount) sp.add(" (${Et.i(e, "reviews")} reviews)")
            sp.add(" · ")
        } else sp.add("New · ")
        sp.add(tail)
        return sp.build()
    }

    const val HERO_BG_HEAD = "<svg viewBox=\"0 0 368 190\"><defs><linearGradient id=\"tfHeroBg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\"><stop offset=\"0\" stop-color=\"#2B0F6B\"/><stop offset=\"1\" stop-color=\"#5E17EB\"/></linearGradient></defs>" +
        "<rect width=\"368\" height=\"190\" fill=\"url(#tfHeroBg)\"/><circle cx=\"300\" cy=\"70\" r=\"104\" fill=\"#FFFFFF\" fill-opacity=\".08\"/><circle cx=\"300\" cy=\"70\" r=\"74\" fill=\"#FFFFFF\" fill-opacity=\".07\"/>" +
        "<path d=\"M0 128C62 112 122 142 192 127S318 110 368 122V190H0Z\" fill=\"#FFFFFF\" fill-opacity=\".05\"/><path d=\"M0 150C72 136 140 162 212 150S328 138 368 146V190H0Z\" fill=\"#FFFFFF\" fill-opacity=\".06\"/><path d=\"M0 170C80 160 150 180 232 170S330 162 368 168V190H0Z\" fill=\"#FFFFFF\" fill-opacity=\".07\"/>"
    private val HEARTS = listOf(
        floatArrayOf(20f, 150f, .55f, -12f, 0f), floatArrayOf(52f, 34f, .42f, 10f, 1f), floatArrayOf(96f, 160f, .5f, 8f, 0f), floatArrayOf(132f, 20f, .38f, -8f, 0f), floatArrayOf(150f, 118f, .46f, 14f, 1f),
        floatArrayOf(188f, 58f, .4f, -6f, 0f), floatArrayOf(214f, 150f, .55f, -10f, 0f), floatArrayOf(236f, 24f, .36f, 12f, 0f), floatArrayOf(258f, 104f, .5f, 6f, 1f), floatArrayOf(290f, 160f, .44f, -14f, 0f),
        floatArrayOf(312f, 40f, .4f, 10f, 0f), floatArrayOf(336f, 120f, .52f, -6f, 1f), floatArrayOf(352f, 18f, .36f, 8f, 0f), floatArrayOf(70f, 96f, .4f, 12f, 0f), floatArrayOf(110f, 76f, .34f, -10f, 1f),
        floatArrayOf(176f, 170f, .42f, 6f, 0f), floatArrayOf(322f, 84f, .38f, -12f, 0f), floatArrayOf(8f, 70f, .4f, 8f, 0f),
    )
    val HERO_BG: String by lazy {
        val sb = StringBuilder(HERO_BG_HEAD)
        for (h in HEARTS) {
            val paint = if (h[4] > 0f) "fill=\"#FFFFFF\" fill-opacity=\"0.16\"" else "fill=\"none\" stroke=\"#FFFFFF\" stroke-opacity=\"0.22\" stroke-width=\"1.6\""
            sb.append("<g transform=\"translate(${h[0]} ${h[1]}) rotate(${h[3]}) scale(${h[2]})\"><path d=\"M0 7s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 7-3c0 5.5-7 10-7 10z\" $paint/></g>")
        }
        sb.append("</svg>").toString()
    }
    const val HERO_GIRL = "<svg viewBox=\"0 0 64 64\">" +
        "<path d=\"M19.6 31C18.2 17 24.6 11 32 11s14 5.6 12.6 20c-.3 7.2 1.4 13.4 4.2 17.6-5 1.8-10.2.6-13.2-1.6H28.6c-3.2 2.4-8.4 3.4-13.4 1.6 2.8-4.2 4.6-10.4 4.4-17.6z\" fill=\"#2A1A16\"/>" +
        "<path d=\"M9 64c1.5-10.5 10-16 23-16s21.5 5.5 23 16z\" fill=\"#FFFFFF\"/><rect x=\"28.2\" y=\"36\" width=\"7.6\" height=\"13\" rx=\"3.8\" fill=\"#E2A57C\"/><path d=\"M26.6 48.2Q32 54.6 37.4 48.2z\" fill=\"#E2A57C\"/>" +
        "<ellipse cx=\"32\" cy=\"28.5\" rx=\"10.6\" ry=\"12.4\" fill=\"#E2A57C\"/><path d=\"M20.8 30.5C19.6 18.6 25.4 13.4 32.6 13.4c7 0 12.4 5 10.8 17.2-1.2-5.4-3.6-9-7.4-10.4-3.4 3.4-8.8 5.6-15.2 10.3z\" fill=\"#2A1A16\"/>" +
        "<path d=\"M25.6 25.4q2.1-1.3 4.3-.2M34.1 25.2q2.2-1.1 4.3.2\" stroke=\"#2A1A16\" stroke-width=\"1.2\" fill=\"none\" stroke-linecap=\"round\"/><path d=\"M26.4 29.4q1.4-1.2 2.8 0M34.8 29.4q1.4-1.2 2.8 0\" stroke=\"#26201E\" stroke-width=\"1.2\" fill=\"none\" stroke-linecap=\"round\"/>" +
        "<path d=\"M29 35.2q3 2.4 6 0\" stroke=\"#9E4337\" stroke-width=\"1.3\" fill=\"none\" stroke-linecap=\"round\"/><circle cx=\"25.2\" cy=\"32.6\" r=\"1.9\" fill=\"#F2777A\" fill-opacity=\".25\"/><circle cx=\"38.8\" cy=\"32.6\" r=\"1.9\" fill=\"#F2777A\" fill-opacity=\".25\"/>" +
        "<path d=\"M17.4 29.5a14.6 14.6 0 0 1 29.2 0\" stroke=\"#FFFFFF\" stroke-width=\"2.4\" fill=\"none\" stroke-linecap=\"round\"/><rect x=\"14.2\" y=\"26.5\" width=\"6.4\" height=\"11\" rx=\"3\" fill=\"#FFFFFF\"/><rect x=\"43.4\" y=\"26.5\" width=\"6.4\" height=\"11\" rx=\"3\" fill=\"#FFFFFF\"/></svg>"
}

/** An SVG drawn to cover its box (preserveAspectRatio "xMidYMid slice"). */
class EtSliceSvg(ctx: Context, private val svg: Svg) : View(ctx) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f || svg.vbW <= 0f) return
        val s = max(w / svg.vbW, h / svg.vbH)
        val dw = svg.vbW * s; val dh = svg.vbH * s
        c.save(); c.clipRect(0f, 0f, w, h)
        svg.draw(c, (w - dw) / 2f, (h - dh) / 2f, dw, dh, meet = false)
        c.restore()
    }
}

/** a photo with the purple ring (.pring: 3 px white, 2 px purple) and the green "free" dot */
fun etRingPhoto(ctx: Context, src: String, size: Float, dot: Int?, demoKey: String = ""): FrameLayout = FrameLayout(ctx).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(Ui.dpi(2f), Et.P) }
    val ph = EtPhoto(ctx, size).apply { this.demoKey = demoKey; set(src) }
    addView(ph, FrameLayout.LayoutParams(Ui.dpi(size), Ui.dpi(size), Gravity.CENTER).apply { setMargins(Ui.dpi(5f), Ui.dpi(5f), Ui.dpi(5f), Ui.dpi(5f)) })
    if (dot != null) addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot); setStroke(Ui.dpi(3f), Color.WHITE) } },
        FrameLayout.LayoutParams(Ui.dpi(15f), Ui.dpi(15f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = Ui.dpi(3f); bottomMargin = Ui.dpi(3f) })
    clipChildren = false
}

// ═══════════════════════════ home ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtHome(et: ExpertTalkScreen) : EtPage(et, Et.PM_PAGE) {
    override val rank = 0
    private val ui = et.ui
    private var nextPill: TextView? = null
    private var feelRow: EtSnapRow? = null
    private var hold = 0L

    init {
        // the quote cards glide to the next one by themselves, right to left, and start again after the last
        every(3500) { advanceFeels() }
        every(30_000) { tick30() }
    }

    override fun back(): Boolean = false
    override fun homeChanged() = render()

    override fun render() {
        val home = et.home ?: return
        rebuild { b ->
            b.put(header(home), -1f, -2f)
            val st = ui.stack(18f)
            b.put(st, -1f, -2f)
            val today = Et.s(home, "today").ifEmpty { Et.todayIST() }
            val experts = Et.objs(home.optJSONArray("experts"))
            val online = experts.filter { EtM.freeToday(it, today) }
            home.optJSONObject("next")?.let { st.put(nextStrip(it), -1f, -2f) }
            st.put(hero(online.size), -1f, 190f)
            st.put(trust(), -1f, -2f, t = -6f)
            st.put(searchRow(), -1f, -2f)
            st.put(feelCards(today), -1f, -2f)
            st.put(mindSection(), -1f, -2f)
            st.put(availableToday(experts, online), -1f, -2f)
            val top = experts.sortedWith(compareByDescending<JSONObject> { Et.d(it, "rating") }.thenByDescending { Et.i(it, "reviews") }).take(3)
            if (top.isNotEmpty()) st.put(topRated(top), -1f, -2f)
            st.put(endQuote(), -1f, -2f)
            val me = home.optJSONObject("me")
            if (Et.b(me, "creator") && !Et.b(me, "is_expert")) st.put(becomeCard(home.optJSONObject("application")), -1f, -2f)
        }
    }

    private fun header(home: JSONObject): View {
        val r = etRow(ctx, 10f).apply { setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(10f)) }
        r.put(ui.ib(EtIcon.ChevronLeft, 20f, pm = true, label = "Back to True Find") { et.pages.go("/home") }, 40f, 40f)
        val name = etCol(ctx)
        name.put(etT(ctx, "Expert Talk", 23f, Et.INK, true, 1.05f))
        name.put(etT(ctx, EtSpan().add("by ").add("True", Ui.c("#4A12B8")).add(" ").add("Find", Ui.c("#8E1F4F")).build(), 11.5f, Et.MUT, true), t = 3f)
        r.put(name, 0f, -2f, 1f)
        val coins = etRow(ctx, 6f).apply {
            background = Tf.box(Color.WHITE, 19f, 1f, Et.PM_LINE); etShadow(2f)
            setPadding(Ui.dpi(8f), 0, Ui.dpi(14f), 0)
            put(etCoin(ctx, 19f), 19f, 19f)
            put(etT(ctx, Et.num(Et.d(home, "coins").toLong()), 15f, Et.INK, true, 1f))
            contentDescription = "Wallet"
            setOnClickListener { et.pages.go("/wallet") }
            Ui.pressable(this, .97f)
        }
        r.put(coins, -2f, 38f)
        val cal = ui.ib(EtIcon.CalendarDays, 20f, pm = true, label = "My sessions") { et.go("sessions") }
        val up = Et.i(home, "upcoming")
        if (up > 0) cal.addView(etT(ctx, "$up", 11f, Color.WHITE, true, 1f).apply {
            gravity = Gravity.CENTER; minWidth = Ui.dpi(18f); setPadding(Ui.dpi(4f), 0, Ui.dpi(4f), 0)
            background = Tf.box(Et.P, 9f, 2f, Et.PAGE)
        }, FrameLayout.LayoutParams(-2, Ui.dpi(18f), Gravity.END or Gravity.TOP).apply { topMargin = -Ui.dpi(5f); rightMargin = -Ui.dpi(5f) })
        cal.clipChildren = false; r.clipChildren = false
        r.put(cal, 40f, 40f)
        return r
    }

    private fun nextStrip(nb: JSONObject): View {
        val c = etRow(ctx, 12f).apply {
            background = Tf.box(Ui.c("#FBF9FF"), 20f, 1f, Ui.c("#D9CBFA")); etShadow(3f)
            setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
            setOnClickListener { et.go("sessions") }
        }
        c.put(etSq(ctx, EtIcon.Headphones, 20f, Color.WHITE, Et.P), 40f, 40f)
        val col = etCol(ctx, 2f)
        val date = Et.s(nb, "date"); val sm = Et.i(nb, "start_min")
        col.put(etT(ctx, "${Et.dayLabel(date)}, ${Et.hm(sm)} · ${Et.s(nb, "expert_name")}", 14.5f, Et.INK, true))
        col.put(etT(ctx, "${Et.i(nb, "dur")} min session · call opens at ${Et.hm(sm - 2)}", 12.5f, Et.MUT))
        c.put(col, 0f, -2f, 1f)
        val pill = etPill(ctx, "", "pur")
        nextPill = pill
        c.put(pill)
        fillNext(nb)
        return c
    }
    private fun fillNext(nb: JSONObject) {
        val start = Et.startMs(Et.s(nb, "date"), Et.i(nb, "start_min"))
        nextPill?.text = if (Et.s(nb, "status") == "confirmed") (if (start > System.currentTimeMillis()) "in ${Et.countdown(start - System.currentTimeMillis())}" else "Now") else "On call"
    }
    private fun tick30() { et.home?.optJSONObject("next")?.let { fillNext(it) } }

    private fun hero(onlineCount: Int): View {
        val f = FrameLayout(ctx).apply {
            background = Tf.box(Et.DEEP, 24f); clipToOutline = true
            elevation = Ui.dp(6f); outlineSpotShadowColorCompat(Color.argb(190, 58, 20, 144))
        }
        f.put(EtSliceSvg(ctx, Svg.of(EtM.HERO_BG)), -1f, -1f)
        f.put(SvgView(ctx, Svg.of(EtM.HERO_GIRL)), 150f, 150f, Gravity.END or Gravity.BOTTOM)
        // chat bubbles: a white one with a heart, a dark one with three dots
        val b1 = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); val r = Ui.dp(12f); val s = Ui.dp(4f); cornerRadii = floatArrayOf(r, r, r, r, r, r, s, s) }
            addView(etIcon(ctx, EtIcon.Heart, 15f, Et.P, 2f, "#5E17EB"), FrameLayout.LayoutParams(Ui.dpi(15f), Ui.dpi(15f), Gravity.CENTER))
        }
        f.put(b1, 36f, 30f, Gravity.END or Gravity.TOP, r = 104f, t = 14f)
        val b2 = etRow(ctx, 3f, Gravity.CENTER).apply {
            background = GradientDrawable().apply { setColor(Et.INK); val r = Ui.dp(12f); val s = Ui.dp(4f); cornerRadii = floatArrayOf(r, r, r, r, s, s, r, r) }
            repeat(3) { put(View(ctx).apply { background = Tf.circle(Color.WHITE) }, 4f, 4f) }
        }
        f.put(b2, 38f, 26f, Gravity.END or Gravity.TOP, r = 8f, t = 30f)
        val tx = etCol(ctx, 8f)
        tx.put(etRow(ctx, 5f).apply {
            background = Tf.box(Color.argb(36, 255, 255, 255), 11f); setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0)
            put(etIcon(ctx, EtIcon.Lock, 11f, Color.WHITE), 11f, 11f)
            put(etT(ctx, "PRIVATE · ANONYMOUS", 10.5f, Color.WHITE, true, 1f).apply { letterSpacing = .12f })
        }, -2f, 22f)
        tx.put(etT(ctx, "Healing Starts\nwith One Talk.", 25f, Color.WHITE, true, 1.06f).apply { letterSpacing = -.01f })
        tx.put(etT(ctx, "Connect with verified experts for real emotional support.", 13f, Ui.c("#DCD0F8")))
        tx.put(etRow(ctx, 7f).apply {
            background = Tf.box(Color.WHITE, 14f); setPadding(Ui.dpi(10f), 0, Ui.dpi(12f), 0)
            put(EtDot(ctx, Et.GREEN))
            put(etT(ctx, "$onlineCount expert${if (onlineCount == 1) "" else "s"} available today", 12.5f, Et.INK, true, 1f))
        }, -2f, 28f)
        f.put(tx, 192f, -2f, l = 20f, t = 20f)
        return f
    }

    private fun trust(): View {
        val r = etRow(ctx, 6f).apply {
            background = Tf.box(Color.WHITE, 16f, 1f, Et.PM_LINE)
            setPadding(Ui.dpi(12f), Ui.dpi(11f), Ui.dpi(12f), Ui.dpi(11f))
        }
        val items = listOf(EtIcon.Lock to "Anonymous calls", EtIcon.ShieldCheck to "Verified experts", EtIcon.RefreshCw to "No-show refund")
        items.forEachIndexed { i, (ic, t) ->
            if (i > 0) { r.put(View(ctx), 0f, 1f, 1f); r.put(View(ctx).apply { setBackgroundColor(Et.PM_LINE) }, 1f, 16f); r.put(View(ctx), 0f, 1f, 1f) }
            r.put(etRow(ctx, 5f).apply { put(etIcon(ctx, ic, 15f, Et.P), 15f, 15f); put(etT(ctx, t, 12f, Ui.c("#3B3847"), true).etEll()) })
        }
        return r
    }

    private fun searchRow(): View {
        val r = etRow(ctx, 10f)
        val btn = etRow(ctx, 10f).apply {
            background = Tf.box(Color.WHITE, 16f, 1f, Et.PM_LINE); etShadow(2f)
            setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0)
            put(etIcon(ctx, EtIcon.Search, 19f, Et.MUT), 19f, 19f)
            put(etT(ctx, "Search experts, topics…", 15f, Et.MUT).etEll())
            contentDescription = "Search experts, topics"
            setOnClickListener { et.go("experts") { cat = ""; chip = "All"; focus = true } }
            Ui.pressable(this, .98f)
        }
        r.put(btn, 0f, 52f, 1f)
        r.put(ui.ib(EtIcon.SlidersHorizontal, 19f, pm = true, dark = true, label = "All experts and filters") { et.go("experts") { cat = ""; chip = "All"; focus = false } }, 52f, 52f)
        return r
    }

    // ── "Does this feel like you?" ──
    private fun feelCards(today: String): View {
        val sec = etCol(ctx, 12f)
        val head = etCol(ctx, 3f)
        head.put(ui.h3("Does this feel like you?", true))
        head.put(etT(ctx, "You are not alone. Someone here will listen.", 12.5f, Et.MUT), l = 12f)
        sec.put(head)
        val inner = etRow(ctx, 10f, Gravity.TOP).apply { setPadding(Ui.dpi(16f), Ui.dpi(2f), Ui.dpi(16f), Ui.dpi(6f)) }
        val row = EtSnapRow(ctx, Ui.dpi(246f)).apply { addView(inner, FrameLayout.LayoutParams(-2, -2)) }
        row.onTouched = { hold = System.currentTimeMillis() + 6000 }
        EtM.feelsToday(today).forEachIndexed { i, f -> inner.put(feelCard(f, i % 3), 236f, -1f) }
        feelRow = row
        sec.put(row, -1f, -2f, l = -16f, r = -16f)
        return sec
    }
    private fun feelCard(f: EtM.Feel, variant: Int): View {
        val dark = variant == 1
        val c = etCol(ctx, 8f).apply {
            background = when (variant) { 1 -> Tf.box(Et.DEEP, 22f, 1f, Et.DEEP); 2 -> Tf.box(Ui.c("#F3EDFE"), 22f, 1f, Ui.c("#E2D6FB")); else -> Tf.box(Color.WHITE, 22f, 1f, Et.PM_LINE) }
            etShadow(3f)
            setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), Ui.dpi(14f))
            minimumHeight = Ui.dpi(176f)
            contentDescription = f.q
            setOnClickListener { et.go("experts") { cat = f.cat; chip = "All"; focus = false } }
            Ui.pressable(this, .98f)
        }
        c.put(EtQuoteMark(ctx, if (dark) Ui.c("#C9B6F5") else Et.P), 30f, 20f)
        c.put(etT(ctx, f.q, 16.5f, if (dark) Color.WHITE else Et.INK, true, 1.3f, italic = true), -1f, 0f, 1f)
        val fa = if (dark) Ui.c("#DCD0F8") else Et.PTEXT
        c.put(etRow(ctx, 6f).apply { put(etIcon(ctx, EtIcon.Heart, 13f, fa, 2f, "currentColor"), 13f, 13f); put(etT(ctx, f.a, 13f, fa, true)) })
        c.put(etRow(ctx, 3f).apply {
            background = Tf.box(if (dark) Color.WHITE else Et.DEEP, 16f)
            setPadding(Ui.dpi(14f), 0, Ui.dpi(10f), 0)
            val fg = if (dark) Et.DEEP else Color.WHITE
            put(etT(ctx, "Talk to an expert", 13f, fg, true, 1f)); put(etIcon(ctx, EtIcon.ChevronRight, 15f, fg), 15f, 15f)
        }, -2f, 32f)
        return c
    }
    private fun advanceFeels() {
        val row = feelRow ?: return
        if (System.currentTimeMillis() < hold || !row.isShown || !et.appVisible) return
        val inner = row.getChildAt(0) as? LinearLayout ?: return
        if (inner.childCount < 2) return
        val step = inner.getChildAt(1).left - inner.getChildAt(0).left
        if (step <= 0) return
        val atEnd = row.scrollX + row.width >= inner.width - Ui.dpi(8f)
        row.smoothScrollTo(if (atEnd) 0 else ((row.scrollX.toFloat() / step).roundToInt() + 1) * step, 0)
    }

    private fun mindSection(): View {
        val sec = etCol(ctx, 12f)
        sec.put(ui.h3("What's on your mind?", true))
        val g = EtGrid(ctx, 3, Ui.dpi(10f))
        for (c in EtM.CATS) g.addView(tile(c.l, c.icon, false) { et.go("experts") { cat = c.k; chip = "All"; focus = false } }, android.view.ViewGroup.LayoutParams(-1, Ui.dpi(104f)))
        g.addView(tile("All Experts", EtIcon.LayoutGrid, true) { et.go("experts") { cat = ""; chip = "All"; focus = false } }, android.view.ViewGroup.LayoutParams(-1, Ui.dpi(104f)))
        sec.put(g, -1f, -2f)
        return sec
    }
    private fun tile(label: String, icon: String, all: Boolean, onClick: () -> Unit): View {
        val t = etCol(ctx, 9f, Gravity.CENTER).apply {
            background = if (all) Tf.box(Et.DEEP, 20f, 1f, Et.DEEP) else Tf.box(Color.WHITE, 20f, 1f, Et.PM_LINE)
            etShadow(if (all) 4f else 2f)
            setPadding(Ui.dpi(8f), Ui.dpi(6f), Ui.dpi(8f), Ui.dpi(6f))
            contentDescription = label
            setOnClickListener { onClick() }
            Ui.pressable(this, .97f)
        }
        val ico = FrameLayout(ctx).apply {
            background = if (all) Tf.circle(Color.argb(41, 255, 255, 255)) else Tf.circle(Et.PM_TINT, 1f, Ui.c("#E2D6FB"))
            addView(etIcon(ctx, icon, 21f, if (all) Color.WHITE else Et.P, 1.8f), FrameLayout.LayoutParams(Ui.dpi(21f), Ui.dpi(21f), Gravity.CENTER))
        }
        t.put(ico, 44f, 44f)
        t.put(etT(ctx, label, 13f, if (all) Color.WHITE else Et.INK, true, 1.15f).apply { gravity = Gravity.CENTER })
        return t
    }

    private fun availableToday(all: List<JSONObject>, online: List<JSONObject>): View {
        val sec = etCol(ctx, 12f)
        sec.put(ui.st(ui.h3("Available Today", true, EtDot(ctx, Et.GREEN)), ui.lnk("See all", pm = true) { et.go("experts") { cat = ""; chip = "Available today"; focus = false } }))
        if (online.isNotEmpty()) {
            val (sv, inner) = ui.hs(12f)
            for (e in online) inner.put(availCard(e), 172f, -1f)
            sec.put(sv, -1f, -2f, l = -16f, r = -16f)
        } else sec.put(ui.empty(if (all.isNotEmpty()) "No free slot left today. Tap an expert to book another day." else "No expert is taking bookings right now. Please check again soon.", true), -1f, -2f)
        return sec
    }
    private fun availCard(e: JSONObject): View {
        val c = etCol(ctx, 8f, Gravity.CENTER_HORIZONTAL).apply {
            background = etCardBg(true); etShadow(3f)
            setPadding(Ui.dpi(12f), Ui.dpi(18f), Ui.dpi(12f), Ui.dpi(12f))
        }
        c.put(etRingPhoto(ctx, Et.s(e, "photo"), 64f, Et.GREEN, Et.s(e, "id")), l = -2f, t = -2f, r = -2f, b = -2f)
        c.put(etRow(ctx, 4f).apply { put(etT(ctx, Et.s(e, "name"), 15f, Et.INK, true).etEll().apply { maxWidth = Ui.dpi(120f) }); put(etVerified(ctx, 14f), 14f, 14f) })
        c.put(etT(ctx, Et.s(e, "title"), 11.5f, Et.MUT).etEll().apply { maxWidth = Ui.dpi(150f) })
        c.put(etT(ctx, EtM.ratingLine(e, false, "${EtM.jsNum(Et.d(e, "hours"))} h talked"), 12.5f, Et.MUT).apply { gravity = Gravity.CENTER })
        val price = EtM.minPrice(e)
        val btn = EtBtn(ctx, "$price", "p", sm = true, pm = true, leadView = etRow(ctx, 8f).apply {
            put(etT(ctx, "Book ·", 14f, Color.WHITE, true, 1f)); put(etCoin(ctx, 15f), 15f, 15f)
        }).apply {
            setOnClickListener { et.go("profile") { expertId = Et.s(e, "id"); from = "home"; stub = e } }
        }
        c.put(btn, -1f, -2f, t = 4f)
        return c
    }

    private fun topRated(top: List<JSONObject>): View {
        val sec = etCol(ctx, 12f)
        sec.put(ui.st(ui.h3("Top Rated Experts", true), ui.lnk("See all", pm = true) { et.go("experts") { cat = ""; chip = "Top Rated"; focus = false } }))
        val card = ui.card(pm = true, shadow = true)
        card.clipToOutline = true
        top.forEachIndexed { i, e ->
            val r = etRow(ctx, 12f).apply {
                setPadding(Ui.dpi(14f), Ui.dpi(13f), Ui.dpi(14f), Ui.dpi(13f))
                if (i < top.size - 1) background = BottomLine(Et.SOFT_LINE)
                setOnClickListener { et.go("profile") { expertId = Et.s(e, "id"); from = "home"; stub = e } }
            }
            r.put(etT(ctx, "${i + 1}", 12f, if (i == 0) Color.WHITE else Et.PTEXT, true, 1f).apply { gravity = Gravity.CENTER; background = Tf.circle(if (i == 0) Et.GOLD else Et.TINT) }, 24f, 24f)
            r.put(EtPhoto(ctx, 46f).apply { demoKey = Et.s(e, "id"); set(Et.s(e, "photo")) }, 46f, 46f)
            val col = etCol(ctx, 3f)
            col.put(etRow(ctx, 4f).apply { put(etT(ctx, Et.s(e, "name"), 15f, Et.INK, true).etEll()); put(etVerified(ctx, 13f), 13f, 13f) })
            col.put(etT(ctx, EtSpan().add(Et.s(e, "title"), Et.PTEXT, true).add(" · ${Et.topicNames(Et.strs(e.optJSONArray("specs")).take(2))}").build(), 12.5f, Et.MUT).etEll())
            r.put(col, 0f, -2f, 1f)
            val rc = etCol(ctx, 2f, Gravity.END)
            rc.put(etT(ctx, if (Et.i(e, "reviews") > 0) EtSpan().add("★", Et.GOLD).add(" ${Et.fixed1(Et.d(e, "rating"))}").build() else "New", 14.5f, Et.INK, true))
            rc.put(etT(ctx, "${Et.i(e, "reviews")} reviews", 11.5f, Et.MUT))
            r.put(rc)
            card.put(r, -1f, -2f)
        }
        sec.put(card, -1f, -2f)
        return sec
    }

    private fun endQuote(): View {
        val c = etCol(ctx, 8f, Gravity.CENTER_HORIZONTAL).apply {
            background = Tf.box(Et.DEEP, 24f)
            setPadding(Ui.dpi(18f), Ui.dpi(20f), Ui.dpi(18f), Ui.dpi(18f))
        }
        c.put(etIcon(ctx, EtIcon.Heart, 20f, Color.WHITE, 2f, "#FFFFFF"), 20f, 20f)
        c.put(etT(ctx, "You don't have to worry anymore.", 19f, Color.WHITE, true).apply { gravity = Gravity.CENTER; letterSpacing = -.01f })
        c.put(etT(ctx, "We are here to listen, to support you and to stand by you, so you never have to feel alone. We will do everything we can to keep your hope alive.", 13.5f, Ui.c("#DCD0F8"), lh = 1.4f).apply { gravity = Gravity.CENTER; maxWidth = Ui.dpi(290f) })
        val ul = etCol(ctx, 9f).apply { background = Tf.box(Color.argb(26, 255, 255, 255), 16f); setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f)) }
        for ((ic, t) in listOf(EtIcon.Lock to "No name, phone number or ID needed", EtIcon.ShieldCheck to "Share only what you want to. Nothing personal is required", EtIcon.Heart to "No judgement, no restrictions. Just emotional support")) {
            ul.put(etRow(ctx, 9f, Gravity.TOP).apply { put(etIcon(ctx, ic, 15f, Ui.c("#C9B6F5")), 15f, 15f, t = 1f); put(etT(ctx, t, 13.5f, Color.WHITE, true, 1.3f), 0f, -2f, 1f) })
        }
        c.put(ul, -1f, -2f, t = 6f)
        c.put(etT(ctx, "Your loneliness gets lighter here. We are with you, always.", 15f, Color.WHITE, true, 1.3f).apply { gravity = Gravity.CENTER; maxWidth = Ui.dpi(290f) }, t = 6f)
        val b = EtBtn(ctx, "Find someone to talk to", "whtp").apply { heightDp = 46f; radiusDp = 23f; refreshShape(); setOnClickListener { et.go("experts") { cat = ""; chip = "Available today"; focus = false } } }
        c.put(b, -1f, -2f, t = 8f)
        c.put(etT(ctx, "Our experts are caring people with real life experience, not doctors.", 11.5f, Ui.c("#BFB0E8")).apply { gravity = Gravity.CENTER }, t = 2f)
        return c
    }

    private fun becomeCard(app: JSONObject?): View {
        val st = Et.s(app, "status")
        if (st == "pending" || st == "zoom_set") {
            val c = ui.card(8f, pm = true, pad = floatArrayOf(16f, 16f, 16f, 16f))
            c.put(etRow(ctx, 8f).apply {
                put(etSq(ctx, EtIcon.Sparkles, 16f, Et.P, Et.TINT, 32f, 10f), 32f, 32f)
                put(etT(ctx, "Your expert application", 15f, Et.INK, true), 0f, -2f, 1f)
                put(etPill(ctx, if (st == "zoom_set") "Interview set" else "In review", if (st == "zoom_set") "pur" else "warn"))
            })
            val zoomAt = Et.msOf(Et.s(app, "zoom_at"))
            c.put(etT(ctx, if (st == "zoom_set") "Zoom video interview: ${if (zoomAt > 0) Et.dayTime(zoomAt) else "Invalid Date"}. True Find will send you the link on your phone." else "True Find will call your phone number to set up a Zoom video interview.", 12.5f, Et.BODY))
            return c
        }
        val c = etCol(ctx, 14f).apply { background = Tf.box(Et.INK, 24f); setPadding(Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f), Ui.dpi(18f)) }
        c.put(etRow(ctx, 8f).apply { put(etIcon(ctx, EtIcon.Sparkles, 18f, Ui.c("#C9B6F5")), 18f, 18f); put(etT(ctx, "Become an Expert", 17f, Color.WHITE, true)) })
        val note = Et.s(app, "note")
        c.put(etT(ctx, if (st == "rejected") "Your last application was not approved${if (note.isNotEmpty()) ": $note" else ""}. You can apply again." else "Verified creators can guide members in paid 1-on-1 sessions. Your account becomes Expert Talk only.", 12.5f, Ui.c("#CFC8DD")))
        c.put(EtBtn(ctx, "Apply now", "wht").apply { setOnClickListener { et.go("apply") } }, -1f, -2f)
        return c
    }
}

/** The big opening quote of a feeling card (Georgia 44 px, line-height .7, in a 20 px box). */
class EtQuoteMark(ctx: Context, private val color: Int) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = etSerif; textSize = Ui.dp(44f) }
    override fun onDraw(c: Canvas) { p.color = color; c.drawText("“", 0f, Ui.dp(30.7f), p) }
}

/** A row of cards that stops on a card's start (scroll-snap) and tells when a finger touches it. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EtSnapRow(ctx: Context, private val step: Int) : HorizontalScrollView(ctx) {
    var onTouched: () -> Unit = {}
    private var flung = false
    init { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { onTouched(); flung = false }
        val r = super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { if (!flung) snap(0); onTouched() }
        return r
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouched(); return super.onInterceptTouchEvent(e) }
    override fun fling(velocityX: Int) { flung = true; snap(velocityX) }
    private fun snap(v: Int) {
        if (step <= 0) return
        val pos = scrollX.toFloat() / step
        val i = when { v > 600 -> ceil(pos).toInt(); v < -600 -> floor(pos).toInt(); else -> pos.roundToInt() }
        val maxX = max(0, (getChildAt(0)?.width ?: 0) - width)
        smoothScrollTo((i * step).coerceIn(0, maxX), 0)
    }
}

// ═══════════════════════════ explore experts ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtExperts(et: ExpertTalkScreen) : EtPage(et, Et.PM_PAGE) {
    override val rank = 1
    private val ui = et.ui
    private val list = etCol(ctx, 12f)
    private val chipsBox = FrameLayout(ctx)
    private val topicBox = FrameLayout(ctx)
    private val count = etT(ctx, "", 14f, Et.INK, true)
    private var input: EditText? = null
    private var builtHead = false

    override fun back(): Boolean { et.go("home"); return true }
    override fun homeChanged() { if (builtHead) { fillChips(); fillList() } else render() }

    override fun render() {
        val home = et.home ?: return
        val n = Et.objs(home.optJSONArray("experts")).size
        rebuild { b ->
            val vp = etRow(ctx, 5f).apply { background = Tf.box(Et.PM_TINT, 14f); setPadding(Ui.dpi(11f), 0, Ui.dpi(11f), 0)
                put(etIcon(ctx, EtIcon.ShieldCheck, 13f, Et.PTEXT), 13f, 13f); put(etT(ctx, "$n verified", 12.5f, Et.PTEXT, true, 1f)) }
            b.put(ui.hdr("Explore Experts", true, { et.go("home") }, vp.also { it.layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(28f)) }), -1f, -2f)
            val st = ui.stack(12f)
            b.put(st, -1f, -2f)
            // search box
            val box = etRow(ctx, 10f).apply { background = Tf.box(Color.WHITE, 16f, 1f, Et.PM_LINE); etShadow(2f); setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0) }
            box.put(etIcon(ctx, EtIcon.Search, 19f, Et.MUT), 19f, 19f)
            val ed = EditText(ctx).apply {
                background = null; setPadding(0, 0, 0, 0); includeFontPadding = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(15f)); typeface = Ui.regular; setTextColor(Et.INK); setHintTextColor(Ui.c("#9A97A3"))
                hint = "Search by name or topic"; isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER; imeOptions = EditorInfo.IME_ACTION_SEARCH
                contentDescription = "Search experts"
                setText(et.nav.q)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { val v = s?.toString().orEmpty(); if (v != et.nav.q) { et.nav.q = v; fillList() } }
                })
                setOnEditorActionListener { v, _, _ -> hideKeys(v); true }
            }
            input = ed
            box.put(ed, 0f, -1f, 1f)
            st.put(box, -1f, 52f)
            st.put(chipsBox, -1f, -2f)
            st.put(topicBox, -1f, -2f)
            st.put(count, -2f, -2f)
            st.put(list, -1f, -2f)
            builtHead = true
            fillChips(); fillList()
            if (et.nav.focus) ed.post { ed.requestFocus(); (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(ed, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun hideKeys(v: View) { try { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) } catch (e: Exception) { } }

    private fun fillChips() {
        chipsBox.removeAllViews()
        val chips = listOf("All", "Available today", "Top Rated", "Hindi", "English").map { v ->
            ui.chip(v, et.nav.chip == v, "dark", pm = true, lead = if (v == "Available today") EtDot(ctx, Et.GREEN) else null) { et.nav.chip = v; fillChips(); fillList() }
        }
        chipsBox.addView(ui.chips(chips), FrameLayout.LayoutParams(-1, -2))
        topicBox.removeAllViews()
        val c = EtM.CATS.firstOrNull { it.k == et.nav.cat }
        topicBox.visibility = if (c != null) View.VISIBLE else View.GONE
        if (c != null) topicBox.addView(etRow(ctx, 8f).apply {
            put(etT(ctx, "Topic:", 12.5f, Et.MUT))
            put(ui.chip(c.l, true, pm = true, trail = EtIcon.X) { et.nav.cat = ""; fillChips(); fillList() }.apply { contentDescription = "Remove topic filter ${c.l}" })
        })
    }

    private fun fillList() {
        val home = et.home ?: return
        val today = Et.s(home, "today").ifEmpty { Et.todayIST() }
        val all = Et.objs(home.optJSONArray("experts"))
        val q = et.nav.q.trim().lowercase()
        val catObj = EtM.CATS.firstOrNull { it.k == et.nav.cat }
        val chip = et.nav.chip
        var l = all.filter { e ->
            val specs = Et.strs(e.optJSONArray("specs")); val langs = Et.strs(e.optJSONArray("langs"))
            if (catObj != null && !specs.contains(catObj.s)) return@filter false
            if (q.isNotEmpty() && !(listOf(Et.s(e, "name"), Et.s(e, "title")) + specs.map { Et.topicName(it) } + langs).joinToString(" ").lowercase().contains(q)) return@filter false
            if (chip == "Available today" && !EtM.freeToday(e, today)) return@filter false
            if (chip == "Hindi" && !langs.contains("Hindi")) return@filter false
            if (chip == "English" && !langs.contains("English")) return@filter false
            true
        }
        l = if (chip == "Top Rated") l.sortedWith(compareByDescending<JSONObject> { Et.d(it, "rating") }.thenByDescending { Et.i(it, "reviews") })
        else l.sortedWith(compareByDescending<JSONObject> { if (EtM.freeToday(it, today)) 1 else 0 }.thenByDescending { Et.d(it, "rating") })
        count.text = "${l.size} expert${if (l.size == 1) "" else "s"} found"
        list.removeAllViews()
        if (l.isEmpty()) list.put(ui.empty(if (all.isNotEmpty()) "No experts match. Try another filter." else "No expert is taking bookings right now. Please check again soon.", true), -1f, -2f)
        for (e in l) list.put(card(e, today), -1f, -2f)
    }

    private fun card(e: JSONObject, today: String): View {
        val free = EtM.freeToday(e, today)
        val c = object : LinearLayout(ctx) {
            // the purple bar on the card's left (.pm .ecard::before)
            private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Et.P }
            override fun dispatchDraw(cv: Canvas) {
                super.dispatchDraw(cv)
                val r = Ui.dp(3f)
                val rect = android.graphics.RectF(0f, Ui.dp(18f), Ui.dp(4f), height - Ui.dp(18f))
                val p = android.graphics.Path().apply { addRoundRect(rect, floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f), android.graphics.Path.Direction.CW) }
                cv.drawPath(p, bar)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; dividerDrawable = EtGap(Ui.dpi(13f)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            background = etCardBg(true); etShadow(3f); clipToOutline = true
            setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f))
        }
        val top = etRow(ctx, 12f, Gravity.TOP)
        top.put(etRingPhoto(ctx, Et.s(e, "photo"), 62f, if (free) Et.GREEN else Ui.c("#B5B0C2"), Et.s(e, "id")), l = -2f, t = -2f, r = -2f, b = -2f)
        val col = etCol(ctx, 4f)
        val nm = etRow(ctx, 5f)
        nm.put(etT(ctx, Et.s(e, "name"), 16f, Et.INK, true).etEll(), -2f, -2f)
        nm.put(etVerified(ctx, 14f), 14f, 14f)
        nm.put(View(ctx), 0f, 1f, 1f)
        val nx = e.optJSONObject("next")
        nm.put(etPill(ctx, if (free) "● Free today" else if (nx != null) "Next: ${Et.dayLabel(Et.s(nx, "date"))}" else "Fully booked", if (free) "ok" else "grey"))
        col.put(nm, -1f, -2f)
        col.put(etT(ctx, Et.s(e, "title"), 12.5f, Et.PTEXT, true))
        col.put(etT(ctx, Et.topicNames(Et.strs(e.optJSONArray("specs"))), 13.5f, Et.BODY))
        col.put(etT(ctx, EtM.ratingLine(e, true, "${EtM.jsNum(Et.d(e, "hours"))} h talked · ${Et.strs(e.optJSONArray("langs")).joinToString(", ")}"), 12.5f, Et.MUT))
        top.put(col, 0f, -2f, 1f)
        c.put(top, -1f, -2f)
        val bottom = etRow(ctx, 10f).apply { background = TopLine(Et.SOFT_LINE); setPadding(0, Ui.dpi(12f), 0, 0) }
        val left = etCol(ctx, 5f)
        left.put(etRow(ctx, 5f).apply { for (d in Et.ints(e.optJSONArray("durs"))) put(etT(ctx, "$d min", 11.5f, Et.BODY, true, 1f).apply { gravity = Gravity.CENTER; background = Tf.box(Et.PM_PAGE, 12f, 1f, Et.PM_LINE); setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0) }, -2f, 24f) })
        left.put(etRow(ctx, 5f).apply { put(etCoin(ctx, 16f), 16f, 16f); put(etT(ctx, "from ${EtM.minPrice(e)} coins", 14f, Et.INK, true)) })
        bottom.put(left, 0f, -2f, 1f)
        bottom.put(EtBtn(ctx, "View Profile", sm = true, pm = true).apply { setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0); setOnClickListener { et.go("profile") { expertId = Et.s(e, "id"); from = "experts"; stub = e } } })
        c.put(bottom, -1f, -2f)
        return c
    }
}

// ═══════════════════════════ expert profile ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtProfile(et: ExpertTalkScreen) : EtPage(et) {
    override val rank = 2
    private var d: JSONObject? = null       // { expert } | { unavailable, name }
    private var loading = true
    private var err: Et.R? = null
    private val id = et.nav.expertId.orEmpty()

    init {
        val stub = et.nav.stub
        // the list already has the full expert card, so the profile opens at once; the reviews come after
        if (stub != null && Et.s(stub, "id") == id) { d = JSONObject().put("expert", stub); loading = false }
        load()
    }
    private fun goBack() = et.go(et.nav.from ?: "home")
    override fun back(): Boolean { goBack(); return true }

    private fun load() {
        Et.call("expert", JSONObject().put("expert_id", id)) { r ->
            if (disposed) return@call
            if (r.ok) { d = r.data; err = null } else { val stub = et.nav.stub; err = r; if (stub != null && Et.s(stub, "id") == id) d = JSONObject().put("expert", stub) }
            loading = false
            render()
        }
    }

    override fun render() {
        val ui = et.ui
        val data = d
        if (loading || data == null) {
            setSticky(null)
            rebuild { b -> b.put(ui.hdr("Expert", false, { goBack() }), -1f, -2f); if (loading) b.put(ui.spinner(), -1f, -2f) else err?.let { e -> b.put(ui.errorBox(e.error, e.detail) { loading = true; render(); load() }, -1f, -2f) } }
            return
        }
        if (Et.b(data, "unavailable")) {
            setSticky(null)
            rebuild { b -> b.put(ui.hdr("Expert", false, { goBack() }), -1f, -2f); b.put(ui.empty("${Et.s(data, "name")} isn't taking bookings right now.").also { (it as TextView).setTextColor(Et.INK) }, -1f, -2f, l = 16f, t = 16f, r = 16f) }
            return
        }
        val e = data.optJSONObject("expert") ?: return
        EtProfileBody.fill(this, e, "member", onBack = { goBack() }, onBook = {
            et.go("book") { expertId = Et.s(e, "id"); expert = e; concerns = ArrayList(); dur = Et.ints(e.optJSONArray("durs")).firstOrNull() ?: 25; date = null; t = null; move = null }
        })
    }
}

/** ProfileBody of MemberApp.jsx — the expert's full profile (members see it; the expert sees her own). */
object EtProfileBody {
    fun fill(page: EtPage, e: JSONObject, view: String, onBack: (() -> Unit)?, onBook: (() -> Unit)? = null, onEdit: (() -> Unit)? = null, headerRight: View? = null) {
        val ctx = page.context
        val ui = page.et.ui
        page.setBackgroundColor(Et.PAGE)
        page.rebuild { b ->
            // ── purple head with two soft circles ──
            val head = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(Et.TINT); val r = Ui.dp(30f); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
                clipToOutline = true
            }
            head.put(View(ctx).apply { background = Tf.circle(Ui.c("#E8DFFD")) }, 170f, 170f, l = -60f, t = 120f)
            head.put(View(ctx).apply { background = Tf.circle(Ui.c("#E8DFFD")) }, 150f, 150f, Gravity.END or Gravity.TOP, r = -50f, t = 20f)
            val hc = etCol(ctx, 10f, Gravity.CENTER_HORIZONTAL).apply { setPadding(Ui.dpi(16f), Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(22f)) }
            val top = etRow(ctx, 0f)
            if (onBack != null) top.put(ui.ib(EtIcon.ChevronLeft, 20f, label = "Back") { onBack() }, 40f, 40f)
            top.put(View(ctx), 0f, 1f, 1f)
            if (headerRight != null) top.put(headerRight)
            hc.put(top, -1f, -2f)
            hc.put(EtPhoto(ctx, 104f).apply { demoKey = Et.s(e, "id"); set(Et.s(e, "photo")) }, 104f, 104f)
            hc.put(etRow(ctx, 6f).apply { put(etT(ctx, Et.s(e, "name"), 22f, Et.INK, true)); put(etVerified(ctx, 18f), 18f, 18f) })
            hc.put(etT(ctx, Et.s(e, "title"), 12.5f, Et.BODY).apply { gravity = Gravity.CENTER })
            hc.put(etPill(ctx, "Fully verified by True Find", "solid", EtIcon.ShieldCheck))
            head.put(hc, -1f, -2f)
            b.put(head, -1f, -2f)

            val st = ui.stack(18f)
            b.put(st, -1f, -2f)
            // ── numbers ──
            val stat = EtGrid(ctx, 4, 0).apply { background = etCardBg(); setPadding(Ui.dpi(4f), Ui.dpi(14f), Ui.dpi(4f), Ui.dpi(14f)) }
            val reviews = Et.i(e, "reviews")
            val nums = listOf(EtM.jsNum(Et.d(e, "hours")) to "hours talked", "${Et.i(e, "sessions")}" to "sessions", "${Et.i(e, "on_time")}%" to "on time",
                (if (reviews > 0) Et.fixed1(Et.d(e, "rating")) else "New") to (if (reviews > 0) "$reviews reviews" else "rating"))
            nums.forEachIndexed { i, (a, l) ->
                stat.addView(etCol(ctx, 2f, Gravity.CENTER_HORIZONTAL).apply {
                    if (i > 0) background = LeftLine(Et.SOFT_LINE)
                    put(etT(ctx, a, 17f, Et.INK, true)); put(etT(ctx, l, 11.5f, Et.MUT))
                })
            }
            st.put(stat, -1f, -2f)
            fun sec(title: String, body: View) {
                val c = ui.card(10f, pad = floatArrayOf(14f, 16f, 14f, 16f))
                c.put(etT(ctx, title, 15f, Et.INK, true))
                c.put(body, -1f, -2f)
                st.put(c, -1f, -2f)
            }
            val about = Et.s(e, "about")
            if (about.isNotEmpty()) sec("About", etT(ctx, about, 12.5f, Et.BODY))
            sec("Helps with", ui.chips(Et.strs(e.optJSONArray("specs")).map { x ->
                etT(ctx, Et.topicName(x), 13.5f, Et.PTEXT, true, 1f).apply { gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 16f, 1f, Ui.c("#DCCFFB")); setPadding(Ui.dpi(13f), 0, Ui.dpi(13f), 0); layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(32f)) }
            }))
            val life = etCol(ctx)
            val years = Et.i(e, "years")
            val qual = Et.s(e, "qual")
            life.put(ui.kv2(ui.iconLabel(EtIcon.Briefcase, "Experience"), etT(ctx, "$years year${if (years == 1) "" else "s"}", 12.5f, Et.INK, true), last = false), -1f, -2f)
            life.put(ui.kv2(ui.iconLabel(EtIcon.Languages, "Languages"), etT(ctx, Et.strs(e.optJSONArray("langs")).joinToString(", "), 12.5f, Et.INK, true), last = qual.isEmpty()), -1f, -2f)
            if (qual.isNotEmpty()) life.put(ui.kv2(ui.iconLabel(EtIcon.GraduationCap, "Background"), etRow(ctx, 5f).apply {
                put(etT(ctx, qual, 12.5f, Et.INK, true))
                if (Et.b(e, "qual_checked")) put(etPill(ctx, "Checked", "ok", EtIcon.Check, 10f, 18f))
            }, last = true), -1f, -2f)
            sec("Life experience", life)
            val prices = EtGrid(ctx, 3, Ui.dpi(8f))
            for (dd in Et.ints(e.optJSONArray("durs"))) prices.addView(etCol(ctx, 3f, Gravity.CENTER_HORIZONTAL).apply {
                background = Tf.box(Color.WHITE, 14f, 1f, Et.LINE); setPadding(Ui.dpi(6f), Ui.dpi(10f), Ui.dpi(6f), Ui.dpi(10f))
                put(etT(ctx, "$dd min", 15f, Et.INK, true))
                put(etRow(ctx, 4f).apply { put(etCoin(ctx, 13f), 13f, 13f); put(etT(ctx, "${when (dd) { 25 -> 250; 35 -> 350; 45 -> 450; else -> 0 }}", 12.5f, Et.INK, true)) })
            })
            sec("Session prices", prices)
            // ── availability: the next 7 days ──
            val av = etCol(ctx, 10f)
            val t0 = Et.todayIST()
            val days = Et.ints(e.optJSONArray("days"))
            val wk = EtGrid(ctx, 7, Ui.dpi(6f))
            for (i in 0 until 7) {
                val dd = Et.addDays(t0, i); val open = days.contains(Et.wdOf(dd)); val today = i == 0
                val fg = if (today && open) Color.WHITE else if (open) Et.INK else Ui.c("#A29EAD")
                wk.addView(etCol(ctx, 2f, Gravity.CENTER).apply {
                    background = Tf.box(if (today && open) Et.P else if (open) Et.TINT else Et.PAGE, 13f)
                    put(etT(ctx, Et.WDN[Et.wdOf(dd)], 11.5f, fg))
                    put(etT(ctx, "${Et.dayOfMonth(dd)}", 15f, fg, true))
                    put(View(ctx).apply { background = Tf.circle(if (today && open) Color.WHITE else if (open) Et.GREEN else Color.TRANSPARENT) }, 5f, 5f)
                }, android.view.ViewGroup.LayoutParams(-1, Ui.dpi(58f)))
            }
            av.put(wk, -1f, -2f)
            val perDay = Et.i(e, "per_day")
            val sp = EtSpan().add("${Et.daysText(days)} · 10 AM – 6 PM · up to $perDay session${if (perDay == 1) "" else "s"} a day")
            if (view == "member") {
                val nx = e.optJSONObject("next")
                if (nx != null) sp.add(" · next free ").add(Et.whenText(Et.s(nx, "date"), Et.i(nx, "start_min")), Et.INK, true) else sp.add(" · no free slot in the next 30 days")
                sp.add(" · book up to 30 days ahead")
            }
            av.put(etT(ctx, sp.build(), 12.5f, Et.BODY))
            sec("Availability", av)
            val rel = etCol(ctx)
            rel.put(ui.kv2("On time for sessions", "${Et.i(e, "on_time")}%"), -1f, -2f)
            rel.put(ui.kv2("Answers on the first ring", "${Et.i(e, "first_ring")}%"), -1f, -2f)
            rel.put(ui.kv2("Missed sessions", "${Et.i(e, "missed")}", last = true), -1f, -2f)
            sec("Reliability", rel)
            val rl = Et.objs(e.optJSONArray("review_list"))
            if (rl.isNotEmpty()) {
                val rc = etCol(ctx, 12f)
                for (r in rl) rc.put(etCol(ctx, 4f).apply {
                    background = TopLine(Et.SOFT_LINE); setPadding(0, Ui.dpi(12f), 0, 0)
                    put(etRow(ctx, 0f).apply { put(etT(ctx, Et.s(r, "by"), 12.5f, Et.INK, true), 0f, -2f, 1f); put(etStars(ctx, Et.d(r, "stars"), 12f)) }, -1f, -2f)
                    if (Et.s(r, "text").isNotEmpty()) put(etT(ctx, Et.s(r, "text"), 12.5f, Et.BODY))
                    if (Et.s(r, "topic").isNotEmpty()) put(etT(ctx, Et.s(r, "topic"), 11.5f, Et.MUT))
                }, -1f, -2f)
                sec("Reviews${if (reviews > 0) " ($reviews)" else ""}", rc)
            } else sec("Reviews${if (reviews > 0) " ($reviews)" else ""}", etT(ctx, "No reviews yet.", 12.5f, Et.MUT))
            st.put(etRow(ctx, 6f, Gravity.CENTER).apply { put(etIcon(ctx, EtIcon.Lock, 12f, Et.MUT), 12f, 12f); put(etT(ctx, "Phone numbers and payments are never shown.", 11.5f, Et.MUT)) }, -1f, -2f)
        }
        when {
            onBook != null -> page.setSticky(EtBtn(ctx, "Book a session", icon = EtIcon.CalendarDays).apply { setOnClickListener { onBook() } })
            onEdit != null -> page.setSticky(EtBtn(ctx, "Edit profile").apply { setOnClickListener { onEdit() } })
            else -> page.setSticky(null)
        }
    }
}

/** a line along the left side (the dividers between the four numbers) */
class LeftLine(private val color: Int) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint().apply { this.color = this@LeftLine.color }
    override fun draw(c: Canvas) { val b = bounds; c.drawRect(b.left.toFloat(), b.top.toFloat(), (b.left + Ui.dpi(1f).coerceAtLeast(1)).toFloat(), b.bottom.toFloat(), p) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

// ═══════════════════════════ booking: step 1 ═══════════════════════════
fun etSteps(ctx: Context, n: Int): View = EtGrid(ctx, 3, Ui.dpi(6f)).apply {
    for (i in 1..3) addView(View(ctx).apply { background = Tf.box(if (i <= n) Et.P else Et.FIELD_LINE, 2f) }, android.view.ViewGroup.LayoutParams(-1, Ui.dpi(4f)))
}

/** .opt with a round radio (session length) */
fun etOpt(ctx: Context, on: Boolean, content: View, check: Boolean = false, onClick: () -> Unit): LinearLayout = etRow(ctx, 10f).apply {
    background = Tf.box(if (on) Ui.c("#FBF9FF") else Color.WHITE, 14f, 1.5f, if (on) Et.P else Et.LINE)
    setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f))
    minimumHeight = Ui.dpi(44f)
    if (check) put(FrameLayout(ctx).apply {
        background = if (on) Tf.box(Et.P, 6f) else Tf.box(Color.WHITE, 6f, 1.5f, Ui.c("#CFC8DD"))
        if (on) addView(etIcon(ctx, EtIcon.Check, 14f, Color.WHITE), FrameLayout.LayoutParams(Ui.dpi(14f), Ui.dpi(14f), Gravity.CENTER))
    }, 20f, 20f)
    else put(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(Ui.dpi(if (on) 6f else 2f), if (on) Et.P else Ui.c("#CFC8DD")) } }, 20f, 20f)
    put(content, 0f, -2f, 1f)
    isSelected = on
    setOnClickListener { onClick() }
}

@SuppressLint("ViewConstructor")
class EtBook(et: ExpertTalkScreen) : EtPage(et) {
    override val rank = 3
    override fun back(): Boolean { et.go("profile"); return true }
    private val btn = EtBtn(ctx, "Continue").apply { setOnClickListener { et.go("slot") } }

    override fun render() {
        val ui = et.ui
        val e = et.nav.expert ?: return
        val rules = et.home?.optJSONObject("rules")
        val conc = et.nav.concerns
        rebuild { b ->
            b.put(ui.hdr("Book a Session", false, { et.go("profile") }, ui.stepText("Step 1 of 3")), -1f, -2f)
            val st = ui.stack(16f)
            b.put(st, -1f, -2f)
            st.put(etSteps(ctx, 1), -1f, -2f)
            st.put(ui.card(12f, row = true, pad = floatArrayOf(12f, 12f, 12f, 12f)).apply {
                put(EtPhoto(ctx, 44f).apply { demoKey = Et.s(e, "id"); set(Et.s(e, "photo")) }, 44f, 44f)
                put(etCol(ctx).apply { put(etRow(ctx, 5f).apply { put(etT(ctx, Et.s(e, "name"), 15f, Et.INK, true)); put(etVerified(ctx, 14f), 14f, 14f) }); put(etT(ctx, Et.s(e, "title"), 12.5f, Et.MUT)) }, 0f, -2f, 1f)
            }, -1f, -2f)
            val s1 = etCol(ctx, 10f)
            s1.put(etT(ctx, "What do you want to talk about?", 15f, Et.INK, true))
            s1.put(etT(ctx, "Pick up to 3. Only your expert sees this.", 11.5f, Et.MUT))
            s1.put(ui.chips(Et.strs(rules?.optJSONArray("concerns")).map { c ->
                ui.chip(Et.topicName(c), conc.contains(c)) {
                    if (conc.contains(c)) conc.remove(c) else if (conc.size < 3) conc.add(c)
                    render()
                }
            }), -1f, -2f)
            st.put(s1, -1f, -2f)
            val s2 = etCol(ctx, 8f)
            s2.put(etT(ctx, "Session length", 15f, Et.INK, true))
            for (dd in Et.ints(e.optJSONArray("durs"))) {
                val content = etRow(ctx, 10f).apply {
                    put(etCol(ctx).apply { put(etT(ctx, "$dd minutes", 15f, Et.INK, true)); put(etT(ctx, "Paid time starts only after you connect", 11.5f, Et.MUT)) }, 0f, -2f, 1f)
                    put(etRow(ctx, 4f).apply { put(etCoin(ctx, 15f), 15f, 15f); put(etT(ctx, "${Et.price(rules, dd)}", 15f, Et.INK, true)) })
                }
                s2.put(etOpt(ctx, et.nav.dur == dd, content) { et.nav.dur = dd; et.nav.date = null; et.nav.t = null; render() }, -1f, -2f)
            }
            st.put(s2, -1f, -2f)
        }
        btn.off = conc.isEmpty()
        if (btn.parent == null) setSticky(btn)
    }
}

// ═══════════════════════════ booking: step 2 / change date ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtSlot(et: ExpertTalkScreen) : EtPage(et), EtPreviewable {
    override fun preview(what: String) { if (what == "calendar") openCalendar() else if (what == "pick") { slots.firstOrNull { !Et.b(it, "taken") }?.let { et.nav.t = Et.i(it, "t"); fillSlots(); fillBar() } } }
    override val rank = if (et.nav.move != null) 2 else 4
    private val e = et.nav.expert ?: JSONObject()
    private val mv = et.nav.move
    private val reply = et.nav.reply
    private val dur = if (mv != null) Et.i(mv, "dur") else et.nav.dur
    private var days: List<JSONObject> = emptyList()
    private var calLoading = true
    private var calErr = ""
    private var today = ""; private var last = ""; private var perDay = 6; private var workDays: List<Int> = emptyList()
    private var slots: List<JSONObject> = emptyList()
    private var slotsLoading = false
    private var slotSeq = 0
    private var busy = false
    private val stripHolder = FrameLayout(ctx)
    private val slotsHolder = etCol(ctx, 10f)
    private val monthTitle = etT(ctx, "", 15f, Et.INK, true)
    private val bar = etRow(ctx, 12f)
    private val barLabel = etT(ctx, "", 11.5f, Et.MUT)
    private val barValue = etT(ctx, "", 14.5f, Et.INK, true)
    private val barBtn = EtBtn(ctx, if (mv != null) "Move" else "Continue").apply { setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
    private var calYM = 0

    init {
        bar.put(etCol(ctx).apply { put(barLabel); put(barValue) }, 0f, -2f, 1f)
        bar.put(barBtn)
        barBtn.setOnClickListener { if (mv != null) moveSheet() else et.go("confirm") }
        loadDays()
    }

    private fun goBack() { if (mv != null) et.go("sessions") { move = null; date = null; t = null } else et.go("book") }
    override fun back(): Boolean { goBack(); return true }

    private fun pl(): JSONObject { val p = JSONObject().put("expert_id", Et.s(e, "id")).put("dur", dur); if (mv != null) p.put("move_id", Et.s(mv, "id")); return p }

    private fun loadDays() {
        Et.call("slots", pl()) { r ->
            if (disposed) return@call
            if (!r.ok) { calLoading = false; calErr = r.error; days = emptyList(); render(); return@call }
            days = Et.objs(r.data.optJSONArray("days")); today = Et.s(r.data, "today"); last = Et.s(r.data, "last")
            perDay = Et.i(r.data, "per_day", 6); workDays = Et.ints(r.data.optJSONArray("work_days")); calLoading = false
            // the server sends the first open day and its slots in one go: no second request
            if (et.nav.date == null && Et.s(r.data, "first_date").isNotEmpty()) {
                et.nav.date = Et.s(r.data, "first_date")
                slots = Et.objs(r.data.optJSONArray("slots")); slotsLoading = false
            } else if (et.nav.date == null) {
                et.nav.date = if (mv != null) Et.s(mv, "date") else (days.firstOrNull { Et.b(it, "open") } ?: days.firstOrNull())?.let { Et.s(it, "date") }
                loadSlots()
            } else loadSlots()
            render()
        }
    }

    private fun loadSlots() {
        val date = et.nav.date ?: return
        val seq = ++slotSeq
        slotsLoading = true
        fillSlots()
        Et.call("slots", pl().put("date", date)) { r ->
            if (disposed || seq != slotSeq) return@call
            slotsLoading = false
            if (r.ok) { slots = Et.objs(r.data.optJSONArray("slots")); days = Et.objs(r.data.optJSONArray("days")).ifEmpty { days } } else slots = emptyList()
            fillStrip(); fillSlots(); fillBar()
        }
    }

    private fun pick(d: String) { et.nav.date = d; et.nav.t = null; render(); loadSlots() }
    private fun isCur(t: Int) = mv != null && et.nav.date == Et.s(mv, "date") && t == Et.i(mv, "start_min")

    override fun render() {
        val ui = et.ui
        val rules = et.home?.optJSONObject("rules")
        rebuild { b ->
            if (mv != null) b.put(ui.hdr("Change Date & Time", false, { goBack() }), -1f, -2f)
            else b.put(ui.hdr("Select Date & Time", false, { goBack() }, ui.stepText("Step 2 of 3")), -1f, -2f)
            val st = ui.stack(14f)
            b.put(st, -1f, -2f)
            if (mv != null) {
                val c = ui.card(9f, pad = floatArrayOf(12f, 14f, 12f, 14f))
                c.put(etRow(ctx, 10f).apply {
                    put(EtPhoto(ctx, 40f).apply { demoKey = Et.s(e, "id"); set(Et.s(e, "photo")) }, 40f, 40f)
                    put(etCol(ctx).apply { put(etT(ctx, Et.s(e, "name"), 15f, Et.INK, true)); put(etT(ctx, "$dur min · already paid", 12.5f, Et.MUT)) }, 0f, -2f, 1f)
                }, -1f, -2f)
                c.put(etRow(ctx, 0f).apply {
                    background = TopLine(Et.SOFT_LINE); setPadding(0, Ui.dpi(9f), 0, 0)
                    put(etT(ctx, "Booked now", 11.5f, Et.MUT), 0f, -2f, 1f)
                    put(etT(ctx, "${Et.dayLabel(Et.s(mv, "date"))} · ${Et.hm(Et.i(mv, "start_min"))} – ${Et.hm(Et.i(mv, "start_min") + dur)}", 13.5f, Et.INK, true))
                }, -1f, -2f)
                val first = Et.firstName(Et.s(e, "name"))
                c.put(etT(ctx, if (reply) "$first can't make this time. Pick any open time that suits you." else "Pick a new date and time that suits you. No extra coins.", 12.5f, Et.BODY))
                st.put(c, -1f, -2f)
            } else {
                st.put(etSteps(ctx, 2), -1f, -2f)
                st.put(etRow(ctx, 8f).apply {
                    put(etIcon(ctx, EtIcon.Clock, 16f, Et.P), 16f, 16f)
                    put(etT(ctx, EtSpan().add(Et.s(e, "name"), Et.INK, true).add(" · $dur min · ${Et.price(rules, dur)} coins").build(), 12.5f, Et.BODY), 0f, -2f, 1f)
                }, -1f, -2f)
            }
            val cal = etCol(ctx, 8f)
            cal.put(etRow(ctx, 0f).apply {
                put(monthTitle, 0f, -2f, 1f)
                put(ui.chip("Calendar", false, lead = etIcon(ctx, EtIcon.CalendarDays, 15f, Et.P)) { openCalendar() }.apply { minimumHeight = Ui.dpi(32f); layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(32f)) })
            }, -1f, -2f)
            cal.put(stripHolder, -1f, -2f, l = -16f, r = -16f)
            cal.put(legend(), -1f, -2f)
            st.put(cal, -1f, -2f)
            st.put(slotsHolder, -1f, -2f)
            if (calLoading) slotsHolder.removeAllViews()
        }
        fillStrip(); fillSlots(); fillBar()
        if (bar.parent == null) setSticky(bar)
    }

    private fun legend(): View = etRow(ctx, 14f).apply {
        for ((c, t) in listOf(Et.GREEN to "Slots open", Ui.c("#E5484D") to "Fully booked", Ui.c("#D6D0E2") to "Expert off"))
            put(etRow(ctx, 5f).apply { put(View(ctx).apply { background = Tf.circle(c) }, 7f, 7f); put(etT(ctx, t, 11.5f, Et.MUT)) })
    }

    private fun fillStrip() {
        val date = et.nav.date
        monthTitle.text = if (date != null) "${Et.MONF[Et.monthOf(date)]} ${Et.yearOf(date)}" else ""
        stripHolder.removeAllViews()
        val (sv, inner) = et.ui.hs(8f)
        val t0 = today.ifEmpty { Et.todayIST() }
        var onView: View? = null
        for (d in days) {
            val ds = Et.s(d, "date"); val on = ds == date; val works = Et.b(d, "works"); val open = Et.b(d, "open")
            if (ds != t0 && Et.dayOfMonth(ds) == 1) inner.put(etT(ctx, Et.MON[Et.monthOf(ds)], 11f, Et.PTEXT, true, 1f).apply { background = Tf.box(Et.TINT, 9f); setPadding(Ui.dpi(7f), Ui.dpi(5f), Ui.dpi(7f), Ui.dpi(5f)) }, g = Gravity.CENTER_VERTICAL)
            val fg = if (on) Color.WHITE else if (!works) Ui.c("#B5B0C2") else Et.INK
            val v = etCol(ctx, 2f, Gravity.CENTER).apply {
                background = if (on) Tf.box(Et.P, 15f, 1.5f, Et.P) else if (!works) Tf.box(Ui.c("#F6F4F9"), 15f, 1.5f, Et.LINE) else Tf.box(Color.WHITE, 15f, 1.5f, Et.LINE)
                put(etT(ctx, if (ds == t0) "Today" else Et.WDN[Et.wdOf(ds)], 11.5f, fg))
                put(etT(ctx, "${Et.dayOfMonth(ds)}", 17f, fg, true))
                put(View(ctx).apply { background = Tf.circle(if (on) Color.WHITE else if (works) (if (open) Et.GREEN else Ui.c("#E5484D")) else Color.TRANSPARENT) }, 5f, 5f)
                isEnabled = works
                isSelected = on
                contentDescription = "${Et.dateStr(ds)}${if (works) (if (open) ", slots open" else ", fully booked") else ", expert off"}"
                setOnClickListener { if (works) pick(ds) }
            }
            if (on) onView = v
            inner.put(v, 54f, 66f)
        }
        stripHolder.addView(sv, FrameLayout.LayoutParams(-1, -2))
        // keep the chosen day in view
        onView?.let { ov -> sv.post { val l = ov.left; val r = ov.right; if (l < sv.scrollX + Ui.dpi(16f) || r > sv.scrollX + sv.width - Ui.dpi(16f)) sv.scrollTo((l + r) / 2 - sv.width / 2, 0) } }
    }

    private fun fillSlots() {
        val ui = et.ui
        slotsHolder.removeAllViews()
        val date = et.nav.date ?: return
        val t0 = today.ifEmpty { Et.todayIST() }
        val free = slots.filter { !Et.b(it, "taken") && !isCur(Et.i(it, "t")) }
        val head = etT(ctx, "${Et.dayLabel(date)}${if (date <= Et.addDays(t0, 1)) " · ${Et.dateStr(date)}" else ""}", 16.5f, Et.INK, true)
        slotsHolder.put(ui.st(head, etT(ctx, "${free.size} slot${if (free.size == 1) "" else "s"} left", 12.5f, if (free.size <= 3) Et.RED_TEXT else Ui.c("#157F44"), true)), -1f, -2f)
        if (date == t0) slotsHolder.put(etT(ctx, "Earlier slots have passed. Book at least 30 minutes ahead.", 12.5f, Et.MUT))
        val info = days.firstOrNull { Et.s(it, "date") == date }
        val first = Et.firstName(Et.s(e, "name"))
        if (info != null && Et.b(info, "full") && slots.isNotEmpty()) slotsHolder.put(etT(ctx, EtSpan().add("This day is full.", bold = true).add(" $first takes up to $perDay session${if (perDay == 1) "" else "s"} a day. Please pick another day.").build(), 13.5f, Ui.c("#6B4400")).apply {
            background = Tf.box(Ui.c("#FFF6E8"), 16f, 1f, Ui.c("#F6DDB0")); setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
        }, -1f, -2f)
        if (slotsLoading && slots.isEmpty()) { slotsHolder.put(ui.spinner(), -1f, -2f); return }
        if (slots.isEmpty()) {
            slotsHolder.put(ui.empty(if (info != null && !Et.b(info, "works")) "$first doesn't take sessions on ${Et.WDN[Et.wdOf(date)]}s." else "No slots this day. Pick another date."), -1f, -2f)
            return
        }
        val g = EtGrid(ctx, 3, Ui.dpi(8f))
        for (s in slots) {
            val t = Et.i(s, "t"); val taken = Et.b(s, "taken"); val full = Et.b(s, "full")
            val cur = isCur(t); val on = et.nav.t == t
            val (bg, line, fg, sub) = when {
                cur -> listOf(Et.TINT, Ui.c("#D9CCF7"), Et.PTEXT, Et.PTEXT)
                on -> listOf(Et.P, Et.P, Color.WHITE, Ui.c("#E4D8FE"))
                taken -> listOf(Ui.c("#F6F4F9"), Ui.c("#F1EEF6"), Ui.c("#A29EAD"), Et.RED_TEXT)
                else -> listOf(Color.WHITE, Et.LINE, Et.INK, Et.MUT)
            }
            val v = etCol(ctx, 0f, Gravity.CENTER).apply {
                background = Tf.box(bg, 13f, 1.5f, line)
                put(etT(ctx, Et.hmShort(t), 14.5f, fg, true, 1.1f).apply { if (taken && !cur) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
                put(etT(ctx, if (cur) "Yours" else if (taken) (if (full) "Full" else "Booked") else Et.hm(t).takeLast(2), 10.5f, sub, true, 1.1f))
                isEnabled = !taken && !cur
                isSelected = on
                setOnClickListener { if (!taken && !cur) { et.nav.t = t; fillSlots(); fillBar() } }
            }
            g.addView(v, android.view.ViewGroup.LayoutParams(-1, Ui.dpi(48f)))
        }
        slotsHolder.put(g, -1f, -2f)
    }

    private fun fillBar() {
        val t = et.nav.t; val date = et.nav.date
        barLabel.text = if (mv != null) "New time" else "Your session"
        barValue.text = if (t != null && date != null) "${Et.dayLabel(date)} · ${Et.hm(t)} – ${Et.hm(t + dur)}" else if (mv != null) "Pick a new time" else "Pick a time"
        barBtn.off = t == null
    }

    private fun openCalendar() {
        val x = et.nav.date ?: today.ifEmpty { Et.todayIST() }
        calYM = Et.yearOf(x) * 12 + Et.monthOf(x)
        sheet { panel, close -> calendarSheet(panel, close) }
    }

    private fun calendarSheet(panel: LinearLayout, close: () -> Unit) {
        val ui = et.ui
        val t0 = today.ifEmpty { Et.todayIST() }
        val k0 = Et.yearOf(t0) * 12 + Et.monthOf(t0)
        val kl = if (last.isNotEmpty()) Et.yearOf(last) * 12 + Et.monthOf(last) else k0 + 1
        val body = etCol(ctx, 12f)
        val map = days.associateBy { Et.s(it, "date") }
        val first = Et.firstName(Et.s(e, "name"))
        fun draw() {
            body.removeAllViews()
            val y = calYM / 12; val m = calYM % 12
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m, 1) }
            val n = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH); val lead = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
            var open = 0
            val grid = EtGrid(ctx, 7, Ui.dpi(4f))
            for (w in listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")) grid.addView(etT(ctx, w, 11.5f, Ui.c("#8A8696"), true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, Ui.dpi(2f)) })
            repeat(lead) { grid.addView(View(ctx), android.view.ViewGroup.LayoutParams(-1, Ui.dpi(44f))) }
            for (k in 1..n) {
                val ds = "$y-${(m + 1).toString().padStart(2, '0')}-${k.toString().padStart(2, '0')}"
                val d = map[ds]
                val st = if (d == null) "out" else if (!Et.b(d, "works")) "off" else if (Et.b(d, "open")) "open" else "full"
                if (st == "open") open++
                val on = et.nav.date == ds
                val fg = when { on -> Color.WHITE; st == "open" -> Et.INK; st == "full" -> Ui.c("#9C98A8"); st == "off" -> Ui.c("#B3AEC0"); else -> Ui.c("#D9D5E2") }
                val dot = when { on -> Color.WHITE; st == "open" -> Et.GREEN; st == "full" -> Ui.c("#E5484D"); st == "off" -> Ui.c("#D6D0E2"); else -> Color.TRANSPARENT }
                val cell = etCol(ctx, 4f, Gravity.CENTER).apply {
                    background = when { on -> Tf.box(Et.P, 12f); st == "open" -> Tf.box(Ui.c("#F4F0FD"), 12f, if (ds == t0) 1.5f else 0f, Ui.c("#B9A3F5")); ds == t0 -> Tf.box(Color.TRANSPARENT, 12f, 1.5f, Ui.c("#B9A3F5")); else -> null }
                    put(etT(ctx, "$k", 15f, fg, st == "open" || on, 1f))
                    put(View(ctx).apply { background = Tf.circle(dot) }, 5f, 5f)
                    isEnabled = st == "open"
                    val note = when (st) { "open" -> "slots open"; "full" -> "fully booked"; "off" -> "$first is off"; else -> if (ds < t0) "past" else "not open for booking yet" }
                    contentDescription = "${Et.dateStr(ds)}, $note"
                    setOnClickListener { if (st == "open") { close(); pick(ds) } }
                }
                grid.addView(cell, android.view.ViewGroup.LayoutParams(-1, Ui.dpi(44f)))
            }
            val nav = etRow(ctx, 0f)
            val prev = ui.ib(EtIcon.ChevronLeft, 18f, label = "Previous month") { if (calYM > k0) { calYM--; draw() } }.apply { alpha = if (calYM <= k0) .35f else 1f; isEnabled = calYM > k0 }
            val next = ui.ib(EtIcon.ChevronRight, 18f, label = "Next month") { if (calYM < kl) { calYM++; draw() } }.apply { alpha = if (calYM >= kl) .35f else 1f; isEnabled = calYM < kl }
            nav.put(prev, 40f, 40f)
            nav.put(etCol(ctx, 1f, Gravity.CENTER_HORIZONTAL).apply {
                put(etT(ctx, "${Et.MONF[m]} $y", 16.5f, Et.INK, true)); put(etT(ctx, if (open > 0) "$open day${if (open == 1) "" else "s"} with open slots" else "No open days this month", 11.5f, Et.MUT))
            }, 0f, -2f, 1f)
            nav.put(next, 40f, 40f)
            body.put(nav, -1f, -2f)
            body.put(grid, -1f, -2f)
            body.put(legend(), -2f, -2f, g = Gravity.CENTER_HORIZONTAL)
            val dt = Et.ints(e.optJSONArray("days")).ifEmpty { workDays }
            body.put(etRow(ctx, 9f, Gravity.TOP).apply {
                background = Tf.box(Et.TINT, 18f); setPadding(Ui.dpi(13f), Ui.dpi(10f), Ui.dpi(13f), Ui.dpi(10f))
                put(etIcon(ctx, EtIcon.Info, 16f, Et.P), 16f, 16f, t = 1f)
                put(etT(ctx, "Book any day up to 30 days ahead${if (last.isNotEmpty()) ", until ${Et.dateStr(last)}" else ""}. $first works ${Et.daysText(dt)}, 10 AM–6 PM and takes up to $perDay session${if (perDay == 1) "" else "s"} a day.", 12.5f, Ui.c("#3A3746")), 0f, -2f, 1f)
            }, -1f, -2f)
        }
        panel.put(ui.sheetHead(EtIcon.CalendarDays, "Choose a date", "Pick any day that suits you") { close() }, -1f, -2f)
        panel.put(body, -1f, -2f)
        draw()
    }

    private fun moveSheet() {
        val m = mv ?: return
        val ui = et.ui
        val date = et.nav.date ?: return
        val t = et.nav.t ?: return
        val rules = et.home?.optJSONObject("rules")
        val first = Et.firstName(Et.s(e, "name"))
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.CalendarDays, "Move your session?", "${Et.s(e, "name")} · $dur min", null), -1f, -2f)
            p.put(etRow(ctx, 8f).apply {
                put(etCol(ctx).apply { background = Tf.box(Et.PAGE, 12f); setPadding(Ui.dpi(10f), Ui.dpi(9f), Ui.dpi(10f), Ui.dpi(9f))
                    put(etT(ctx, "Booked", 11.5f, Et.MUT)); put(etT(ctx, "${Et.dayLabel(Et.s(m, "date"))} · ${Et.hm(Et.i(m, "start_min"))}", 13f, Et.MUT, true).apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }) }, 0f, -2f, 1f)
                put(etIcon(ctx, EtIcon.ChevronRight, 18f, Et.P), 18f, 18f)
                put(etCol(ctx).apply { background = Tf.box(Et.TINT, 12f); setPadding(Ui.dpi(10f), Ui.dpi(9f), Ui.dpi(10f), Ui.dpi(9f))
                    put(etT(ctx, "New time", 11.5f, Ui.c("#57546A"))); put(etT(ctx, "${Et.dayLabel(date)} · ${Et.hm(t)}", 13f, Et.PTEXT, true)) }, 0f, -2f, 1f)
            }, -1f, -2f)
            val moveMax = rules?.optInt("move_max", 2) ?: 2
            val left = moveMax - Et.i(m, "moves") - 1
            val lines = listOf(EtIcon.Wallet to "No extra coins. Your payment moves with the session.", EtIcon.Bell to "$first is told right away.",
                if (reply) EtIcon.Info to "This replaces $first's suggested time." else EtIcon.RefreshCw to (if (left > 0) "You can change it $left more time, until 3 hours before it starts." else "This is your last change for this session."))
            for ((ic, tx) in lines) p.put(ui.bullet(ic, tx, iconSize = 17f), -1f, -2f)
            val go = EtBtn(ctx, "Move session")
            go.setOnClickListener {
                if (busy) return@setOnClickListener
                busy = true; go.off = true
                Et.call("move", JSONObject().put("booking_id", Et.s(m, "id")).put("date", date).put("start_min", t).put("reply", reply)) { r ->
                    busy = false; go.off = false
                    if (disposed) return@call
                    if (r.ok) { toast("Session moved to ${Et.s(r.data, "when")}"); close(); et.go("sessions") { move = null; this.reply = false; this.date = null; this.t = null } }
                    else { toast(r.error); close(); et.nav.t = null; fillSlots(); fillBar(); loadSlots() }
                }
            }
            p.put(go, -1f, -2f)
            p.put(EtBtn(ctx, "Back", "s2").apply { setOnClickListener { close() } }, -1f, -2f)
        }
    }
}

// ═══════════════════════════ booking: step 3 ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtConfirm(et: ExpertTalkScreen) : EtPage(et) {
    override val rank = 5
    private var coins: Int? = null
    private var busy = false
    override fun back(): Boolean { et.go("slot"); return true }

    init {
        Et.call("sessions") { r -> if (!disposed) { coins = if (r.ok) Et.i(r.data, "coins") else null; render() } }
    }

    override fun render() {
        val ui = et.ui
        val e = et.nav.expert ?: return
        val date = et.nav.date ?: return
        val t = et.nav.t ?: return
        val dur = et.nav.dur
        val price = Et.price(et.home?.optJSONObject("rules"), dur)
        val c = coins
        val short = c != null && c < price
        rebuild { b ->
            b.put(ui.hdr("Confirm & Pay", false, { et.go("slot") }, ui.stepText("Step 3 of 3")), -1f, -2f)
            val st = ui.stack(14f)
            b.put(st, -1f, -2f)
            st.put(etSteps(ctx, 3), -1f, -2f)
            val card = ui.card(shadow = true, pad = floatArrayOf(4f, 16f, 4f, 16f))
            card.put(etRow(ctx, 12f).apply {
                background = BottomLine(Et.SOFT_LINE); setPadding(0, Ui.dpi(12f), 0, Ui.dpi(12f))
                put(EtPhoto(ctx, 46f).apply { demoKey = Et.s(e, "id"); set(Et.s(e, "photo")) }, 46f, 46f)
                put(etCol(ctx).apply { put(etRow(ctx, 5f).apply { put(etT(ctx, Et.s(e, "name"), 15f, Et.INK, true)); put(etVerified(ctx, 14f), 14f, 14f) }); put(etT(ctx, Et.s(e, "title"), 12.5f, Et.MUT)) })
            }, -1f, -2f)
            fun row(ic: String, k: String, v: String) = card.put(etRow(ctx, 12f).apply {
                background = BottomLine(Et.SOFT_LINE); setPadding(0, Ui.dpi(10f), 0, Ui.dpi(10f))
                put(etSq(ctx, ic, 16f, Et.P, Et.TINT, 32f, 10f), 32f, 32f)
                put(etCol(ctx).apply { put(etT(ctx, k, 11.5f, Et.MUT)); put(etT(ctx, v, 14.5f, Et.INK, true)) }, 0f, -2f, 1f)
            }, -1f, -2f)
            row(EtIcon.CalendarDays, "Date", "${Et.dateStr(date)} ${Et.yearOf(date)}")
            row(EtIcon.Clock, "Time", "${Et.hm(t)} – ${Et.hm(t + dur)} ($dur min)")
            row(EtIcon.MessageSquareWarning, "Topic", Et.topicNames(et.nav.concerns, ", "))
            card.put(etRow(ctx, 0f).apply {
                setPadding(0, Ui.dpi(12f), 0, Ui.dpi(12f))
                put(etT(ctx, "Total", 15f, Et.INK, true), 0f, -2f, 1f)
                put(etRow(ctx, 5f).apply { put(etCoin(ctx, 18f), 18f, 18f); put(etT(ctx, "$price", 18f, Et.INK, true)) })
            }, -1f, -2f)
            st.put(card, -1f, -2f)
            val info = ui.card(8f, pad = floatArrayOf(12f, 14f, 12f, 14f))
            for ((tx, ic) in listOf("Paid time starts only after the expert picks up." to EtIcon.Clock, "If she doesn't pick up twice, you get every coin back." to EtIcon.RefreshCw,
                    "You can change the date or time 2 times, until 3 hours before." to EtIcon.CalendarDays, "Calls are private and never recorded." to EtIcon.Lock))
                info.put(etRow(ctx, 9f).apply { put(etIcon(ctx, ic, 15f, Et.P), 15f, 15f); put(etT(ctx, tx, 12.5f, Et.BODY), 0f, -2f, 1f) }, -1f, -2f)
            st.put(info, -1f, -2f)
            if (c != null) st.put(etRow(ctx, 6f).apply {
                val col = if (short) Et.RED_TEXT else Et.MUT
                put(etIcon(ctx, EtIcon.Wallet, 15f, col), 15f, 15f)
                put(etT(ctx, "Wallet: $c coins${if (short) " · you need ${price - c} more" else ""}", 12.5f, col))
            }, -1f, -2f)
        }
        if (short) setSticky(EtBtn(ctx, "Add coins").apply { setOnClickListener { et.pages.go("/wallet") } })
        else {
            val pay = EtBtn(ctx, "Pay $price coins & book", leadView = etCoin(ctx, 18f).apply { layoutParams = LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)) })
            pay.off = busy
            pay.setOnClickListener { book(pay, e, date, t, dur) }
            setSticky(pay)
        }
    }

    private fun book(btn: EtBtn, e: JSONObject, date: String, t: Int, dur: Int) {
        if (busy) return
        busy = true; btn.off = true
        Et.call("book", JSONObject().put("expert_id", Et.s(e, "id")).put("dur", dur).put("date", date).put("start_min", t).put("concerns", JSONArray(et.nav.concerns))) { r ->
            busy = false
            if (disposed) return@call
            btn.off = false
            if (r.ok) { val bk = r.data.optJSONObject("booking"); et.go("booked", back = false) { booked = bk } }
            else { toast(r.error); if (r.status == 409) et.go("slot") { this.t = null } }
        }
    }
}

@SuppressLint("ViewConstructor")
class EtBooked(et: ExpertTalkScreen) : EtPage(et, Color.WHITE) {
    override val rank = 6
    override fun back(): Boolean { et.go("home"); return true }
    override fun render() {
        val ui = et.ui
        val b = et.nav.booked ?: JSONObject()
        val ex = b.optJSONObject("expert")
        rebuild { col ->
            val st = ui.stack(14f).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(16f), Ui.dpi(40f), Ui.dpi(16f), Ui.dpi(22f)) }
            col.put(st, -1f, -2f)
            val ring = FrameLayout(ctx).apply {
                put(View(ctx).apply { background = Tf.circle(Et.TINT) }, -1f, -1f)
                put(View(ctx).apply { background = Tf.circle(Ui.c("#E2D6FD")) }, -1f, -1f, l = 18f, t = 18f, r = 18f, b = 18f)
                val c3 = FrameLayout(ctx).apply {
                    background = Tf.circle(Et.P); elevation = Ui.dp(6f); outlineSpotShadowColorCompat(Color.argb(140, 94, 23, 235))
                    addView(etIcon(ctx, EtIcon.Check, 30f, Color.WHITE, 3f), FrameLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER))
                }
                put(c3, 62f, 62f, Gravity.CENTER)
            }
            st.put(ring, 128f, 128f)
            st.put(etT(ctx, "Session booked!", 22.5f, Et.INK, true).apply { gravity = Gravity.CENTER })
            st.put(etT(ctx, "${Et.s(ex, "name")} is told. The call opens 2 minutes before the start.", 12.5f, Et.MUT).apply { gravity = Gravity.CENTER })
            val card = ui.card(pad = floatArrayOf(4f, 16f, 4f, 16f))
            val date = Et.s(b, "date"); val sm = Et.i(b, "start_min"); val dur = Et.i(b, "dur")
            val rows = listOf("Date" to (if (date.isNotEmpty()) Et.dateStr(date) else ""), "Time" to "${Et.hm(sm)} – ${Et.hm(sm + dur)}", "Duration" to "$dur minutes", "Paid" to "${Et.i(b, "coins")} coins")
            rows.forEachIndexed { i, (k, v) -> card.put(ui.kv2(k, v, i == rows.size - 1), -1f, -2f) }
            st.put(card, -1f, -2f)
        }
        val bar = etCol(ctx, 8f)
        bar.put(EtBtn(ctx, "Go to My Sessions").apply { setOnClickListener { et.go("sessions", back = false) { booked = null } } }, -1f, -2f)
        bar.put(EtBtn(ctx, "Back to experts", "s2").apply { setOnClickListener { et.go("home") } }, -1f, -2f)
        setSticky(bar)
    }
}

// ═══════════════════════════ my sessions ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtSessions(et: ExpertTalkScreen) : EtPage(et), EtPreviewable {
    override fun preview(what: String) {
        val all = Et.objs(d?.optJSONArray("sessions"))
        when (what) {
            "rules" -> all.firstOrNull { Et.s(it, "status") == "confirmed" }?.let { rulesSheet(it) }
            "rate" -> all.firstOrNull { Et.s(it, "status") == "completed" && Et.i(it, "stars") == 0 }?.let { rateSheet(it) }
            "side" -> all.firstOrNull()?.let { sideSheet(it) }
            "report" -> all.firstOrNull { Et.s(it, "status") == "completed" }?.let { reportSheet(it) }
            "done" -> { tab = "done"; render() }
            "cancel" -> { tab = "cancel"; render() }
        }
    }
    override val rank = 1
    private var d: JSONObject? = null
    private var err: Et.R? = null
    private var tab = "up"
    private var busy = false
    private var lastJson = ""
    private val tickers = ArrayList<() -> Unit>()

    init {
        load()
        every(15_000) { load() }
    }
    override fun back(): Boolean { et.go("home"); return true }
    override fun tick() { for (t in tickers) t() }

    private fun load(done: (() -> Unit)? = null) {
        Et.call("sessions") { r ->
            if (disposed) return@call
            if (r.ok) {
                d = r.data; err = null
                val j = r.data.toString()
                if (j != lastJson) { lastJson = j; render() }
                afterCall()
            } else { if (d == null) { err = r; render() } }
            done?.invoke()
        }
    }

    /** back from a call: rate it, or tell True Find your side after two reports */
    private fun afterCall() {
        val a = et.nav.after ?: return
        et.nav.after = null
        if (Et.s(a, "status") == "completed" && Et.i(a, "stars") == 0) rateSheet(a)
        if (Et.s(a, "status") == "cut_by_report") sideSheet(a)
    }

    private fun act(f: (() -> Unit) -> Unit) { busy = true; f { busy = false; load() } }

    override fun render() {
        val ui = et.ui
        val data = d
        tickers.clear()
        if (data == null) {
            rebuild { b ->
                b.put(ui.hdr("My Sessions", false, { et.go("home") }), -1f, -2f)
                val e = err
                if (e != null) b.put(ui.errorBox(e.error, e.detail) { err = null; render(); load() }, -1f, -2f) else b.put(ui.spinner(), -1f, -2f)
            }
            return
        }
        val rules = data.optJSONObject("rules")
        val all = Et.objs(data.optJSONArray("sessions"))
        var list = all.filter { b -> val s = Et.s(b, "status"); when (tab) { "up" -> EtM.UPCOMING.contains(s); "done" -> EtM.DONE.contains(s); else -> EtM.CANCELLED.contains(s) } }
        if (tab == "up") list = list.sortedBy { Et.msOf(Et.s(it, "start_at")) }
        rebuild { b ->
            b.put(ui.hdr("My Sessions", false, { et.go("home") }), -1f, -2f)
            val st = ui.stack(14f)
            b.put(st, -1f, -2f)
            val tabs = EtGrid(ctx, 3, Ui.dpi(4f)).apply { background = Tf.box(Ui.c("#EFEBF6"), 14f); setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)) }
            val upN = all.count { EtM.UPCOMING.contains(Et.s(it, "status")) }
            for ((k, l) in listOf("up" to "Upcoming", "done" to "Completed", "cancel" to "Cancelled")) {
                val on = tab == k
                tabs.addView(etT(ctx, if (k == "up") "$l ($upN)" else l, 14f, if (on) Et.INK else Et.MUT, true, 1f).apply {
                    gravity = Gravity.CENTER
                    background = if (on) Tf.box(Color.WHITE, 11f) else null
                    if (on) { elevation = Ui.dp(1f) }
                    setOnClickListener { tab = k; render() }
                }, android.view.ViewGroup.LayoutParams(-1, Ui.dpi(38f)))
            }
            st.put(tabs, -1f, -2f)
            if (list.isEmpty()) st.put(ui.empty(when (tab) { "up" -> "No upcoming sessions. Book one with an expert."; "done" -> "No completed sessions yet."; else -> "Nothing cancelled." }), -1f, -2f)
            for (bk in list) {
                st.put(sessionCard(bk, rules), -1f, -2f)
                bk.optJSONObject("resched")?.let { rs -> if (Et.s(rs, "date").isNotEmpty()) st.put(reschedCard(bk, rs), -1f, -2f) }
            }
        }
    }

    private fun sessionCard(b: JSONObject, rules: JSONObject?): View {
        val ui = et.ui
        val status = Et.s(b, "status")
        val ex = b.optJSONObject("expert") ?: JSONObject()
        val date = Et.s(b, "date"); val sm = Et.i(b, "start_min"); val dur = Et.i(b, "dur")
        val openBefore = rules?.optLong("open_before_ms", 120_000L) ?: 120_000L
        val moveMax = rules?.optInt("move_max", 2) ?: 2
        val cutoff = rules?.optLong("move_cutoff_ms", 3 * 3600_000L) ?: 3 * 3600_000L
        val start = Et.startMs(date, sm); val open = start - openBefore; val endAt = start + dur * 60_000L
        val live = status == "live" || status == "ringing"
        val missed = Et.i(b, "missed_rings")
        val c = ui.card(12f, shadow = true, pad = floatArrayOf(16f, 16f, 16f, 16f))
        val top = etRow(ctx, 12f, Gravity.TOP)
        top.put(EtPhoto(ctx, 52f).apply { demoKey = Et.s(ex, "id"); set(Et.s(ex, "photo")) }, 52f, 52f)
        val col = etCol(ctx, 1f)
        col.put(etRow(ctx, 5f).apply { put(etT(ctx, Et.s(ex, "name"), 16f, Et.INK, true)); put(etVerified(ctx, 14f), 14f, 14f) })
        col.put(etT(ctx, "${Et.dayLabel(date)} · ${Et.hm(sm)} – ${Et.hm(sm + dur)}", 12.5f, Et.BODY))
        col.put(etT(ctx, "$dur min · ${Et.i(b, "coins")} coins", 11.5f, Et.MUT))
        b.optJSONObject("moved_from")?.let { mf -> if (Et.s(mf, "date").isNotEmpty()) col.put(etT(ctx, "Moved from ${Et.whenText(Et.s(mf, "date"), Et.i(mf, "start_min"))}", 11.5f, Et.PTEXT, true)) }
        top.put(col, 0f, -2f, 1f)
        val stx = EtM.STATUS_TXT[status]
        top.put(if (stx != null) etPill(ctx, stx.first, stx.second) else etPill(ctx, if (live) "On call" else "Upcoming", if (live) "ok" else "pur"))
        c.put(top, -1f, -2f)
        val concerns = Et.strs(b.optJSONArray("concerns"))
        if (concerns.isNotEmpty()) c.put(ui.chips(concerns.map { x -> etT(ctx, Et.topicName(x), 12.5f, Et.INK, true, 1f).apply { gravity = Gravity.CENTER; background = Tf.box(Et.PAGE, 13f, 1f, Et.LINE); setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0); layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(26f)) } }), -1f, -2f)
        if (EtM.UPCOMING.contains(status)) {
            val cbLeft = etT(ctx, "", 12.5f, Et.INK); val cbRight = etT(ctx, "", 15f, Et.P, true)
            c.put(etRow(ctx, 8f).apply { background = Tf.box(Et.PAGE, 14f); setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)); put(cbLeft, 0f, -2f, 1f); put(cbRight) }, -1f, -2f)
            val callBtn: EtBtn
            if (live) {
                callBtn = EtBtn(ctx, "Return to call", "green", EtIcon.Phone, 16f, sm = true).apply { setOnClickListener { et.go("call") { callId = Et.s(b, "id"); callName = Et.s(ex, "name"); callPhoto = Et.s(ex, "photo") } } }
            } else {
                callBtn = EtBtn(ctx, if (missed > 0) "Call again" else "Start Call", "p", EtIcon.Phone, 16f, sm = true).apply { setOnClickListener { rulesSheet(b) } }
            }
            c.put(callBtn, -1f, -2f)
            val update = {
                val now = System.currentTimeMillis()
                cbLeft.text = if (live) "Your call is on" else if (now < open) "Call opens ${Et.dayLabel(date)}, ${Et.hm(sm - 2)}" else if (missed > 0) "No answer on the first try" else "Call is open"
                cbRight.text = if (now < open) "in ${Et.countdown(open - now)}" else if (live) "" else "${Et.countdown(endAt - now)} left"
                if (!live) callBtn.off = !(now >= open && now <= endAt && status == "confirmed" && missed < 2)
            }
            update(); tickers.add(update)
            val resched = b.optJSONObject("resched")?.let { Et.s(it, "date").isNotEmpty() } ?: false
            if (status == "confirmed" && !resched) {
                val movesLeft = moveMax - Et.i(b, "moves")
                val canMove = movesLeft > 0 && start - System.currentTimeMillis() >= cutoff
                if (canMove) c.put(mvRow(EtIcon.CalendarDays, "Change date or time", etT(ctx, "$movesLeft change${if (movesLeft == 1) "" else "s"} left", 11.5f, Et.MUT)) { openMove(b, false) }, -1f, -2f)
                else c.put(etRow(ctx, 6f).apply { put(etIcon(ctx, EtIcon.CalendarDays, 14f, Et.MUT), 14f, 14f); put(etT(ctx, if (movesLeft <= 0) "You've already changed this session $moveMax times." else "Date changes close 3 hours before the session.", 11.5f, Et.MUT), 0f, -2f, 1f) }, -1f, -2f)
            }
        }
        if (status == "completed") {
            val stars = Et.i(b, "stars")
            if (stars > 0) c.put(etRow(ctx, 8f).apply { put(etStars(ctx, stars.toDouble())); val rv = Et.s(b, "review"); if (rv.isNotEmpty()) put(etT(ctx, "“$rv”", 12.5f, Et.MUT).etEll(), 0f, -2f, 1f) }, -1f, -2f)
            else c.put(EtBtn(ctx, "Rate your expert", "s2", EtIcon.Star, 16f, sm = true).apply { setOnClickListener { rateSheet(b) } }, -1f, -2f)
        }
        if (status == "expert_no_show" || status == "expert_left" || status == "refunded") c.put(etT(ctx, "${Et.s(b, "refund_reason").ifEmpty { "Refunded" }} · ${Et.i(b, "coins")} coins back in your wallet", 12.5f, Et.MUT))
        if (status == "cut_by_report" && Et.s(b, "case_id").isNotEmpty()) c.put(EtBtn(ctx, "Tell True Find your side", "s2", sm = true).apply { setOnClickListener { sideSheet(b) } }, -1f, -2f)
        if (EtM.DONE.contains(status)) c.put(ui.lnk("Report a problem", red = true) { reportSheet(b) }, -2f, -2f)
        return c
    }

    private fun mvRow(icon: String, text: String, right: View?, onClick: () -> Unit): View = etRow(ctx, 10f).apply {
        background = Tf.box(Ui.c("#F8F5FF"), 12f, 1f, Ui.c("#E7DEFB"))
        setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0); minimumHeight = Ui.dpi(42f)
        put(etIcon(ctx, icon, 17f, Et.P), 17f, 17f)
        put(etT(ctx, text, 14f, Et.PTEXT, true), 0f, -2f, 1f)
        if (right != null) put(right)
        setOnClickListener { onClick() }
        Ui.pressable(this, .98f)
    }

    private fun reschedCard(b: JSONObject, rs: JSONObject): View {
        val ui = et.ui
        val ex = b.optJSONObject("expert") ?: JSONObject()
        val first = Et.firstName(Et.s(ex, "name"))
        val c = etCol(ctx, 12f).apply { background = Tf.box(Color.WHITE, 18f, 1.5f, Ui.c("#F2D49B")); setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f)) }
        c.put(etRow(ctx, 10f).apply {
            put(etSq(ctx, EtIcon.CalendarDays, 16f, Ui.c("#7A4A00"), Ui.c("#FFF3DC"), 32f, 10f), 32f, 32f)
            put(etCol(ctx).apply { put(etT(ctx, "$first asked to move your session", 15f, Et.INK, true)); put(etT(ctx, "Reason: ${Et.s(rs, "reason")} · needs your OK", 11.5f, Et.MUT)) }, 0f, -2f, 1f)
        }, -1f, -2f)
        c.put(etRow(ctx, 8f).apply {
            put(etCol(ctx).apply { background = Tf.box(Et.PAGE, 12f); setPadding(Ui.dpi(10f), Ui.dpi(9f), Ui.dpi(10f), Ui.dpi(9f))
                put(etT(ctx, "Booked", 11.5f, Et.MUT)); put(etT(ctx, "${Et.dayLabel(Et.s(b, "date"))} · ${Et.hm(Et.i(b, "start_min"))}", 13f, Et.MUT, true).apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }) }, 0f, -2f, 1f)
            put(etIcon(ctx, EtIcon.ChevronRight, 18f, Et.P), 18f, 18f)
            put(etCol(ctx).apply { background = Tf.box(Et.TINT, 12f); setPadding(Ui.dpi(10f), Ui.dpi(9f), Ui.dpi(10f), Ui.dpi(9f))
                put(etT(ctx, "New time", 11.5f, Ui.c("#57546A"))); put(etT(ctx, "${Et.dayLabel(Et.s(rs, "date"))} · ${Et.hm(Et.i(rs, "start_min"))}", 13f, Et.PTEXT, true)) }, 0f, -2f, 1f)
        }, -1f, -2f)
        val g = EtGrid(ctx, 2, Ui.dpi(8f))
        val acc = EtBtn(ctx, "Accept new time", sm = true); val keep = EtBtn(ctx, "Keep original", "s2", sm = true)
        acc.off = busy; keep.off = busy
        acc.setOnClickListener { if (!busy) { acc.off = true; keep.off = true; act { fin -> Et.call("resched_answer", JSONObject().put("booking_id", Et.s(b, "id")).put("answer", "accept")) { r -> if (r.ok) toast("New time accepted") else toast(r.error); fin() } } } }
        keep.setOnClickListener { if (!busy) { acc.off = true; keep.off = true; act { fin -> Et.call("resched_answer", JSONObject().put("booking_id", Et.s(b, "id")).put("answer", "keep")) { r -> if (r.ok) toast("You kept the original time") else toast(r.error); fin() } } } }
        g.addView(acc); g.addView(keep)
        c.put(g, -1f, -2f)
        c.put(mvRow(EtIcon.CalendarDays, "Pick a different time yourself", etIcon(ctx, EtIcon.ChevronRight, 16f, Et.P)) { openMove(b, true) }, -1f, -2f)
        c.put(etT(ctx, "If you keep the original time and the expert can't make it, you get a full refund automatically.", 11.5f, Et.MUT))
        return c
    }

    private fun openMove(b: JSONObject, reply: Boolean) {
        val ex = b.optJSONObject("expert") ?: JSONObject()
        et.go("slot", back = false) {
            move = b; this.reply = reply
            expert = JSONObject().put("id", Et.s(ex, "id")).put("name", Et.s(ex, "name")).put("photo", Et.s(ex, "photo")).put("title", Et.s(ex, "title"))
            date = null; t = null
        }
    }

    // ── pop-ups ──
    private fun rulesSheet(b: JSONObject) {
        val ui = et.ui
        val ex = b.optJSONObject("expert") ?: JSONObject()
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.ShieldCheck, "Before your call", "${Et.i(b, "dur")} min with ${Et.s(ex, "name")}") { close() }, -1f, -2f)
            for ((h, t) in listOf("Be respectful" to "Misbehaving with an expert gets your account blocked permanently.", "Only you can end the call" to "The expert can't end or decline it.",
                    "Expert rude?" to "End the call, give a low rating and report. True Find checks every report.", "Private" to "Calls are never recorded. Don't share phone numbers or social IDs.",
                    "No answer?" to "If she doesn't pick up twice, every coin comes back."))
                p.put(etRow(ctx, 10f, Gravity.TOP).apply { put(ui.okc(), 22f, 22f, t = 2f); put(etCol(ctx).apply { put(etT(ctx, h, 12.5f, Et.INK, true)); put(etT(ctx, t, 12.5f, Et.MUT)) }, 0f, -2f, 1f) }, -1f, -2f)
            p.put(EtBtn(ctx, "I agree, start call", icon = EtIcon.Phone).apply { setOnClickListener { close(); openCall(b) } }, -1f, -2f)
        }
    }
    private fun openCall(b: JSONObject) {
        val ex = b.optJSONObject("expert") ?: JSONObject()
        val go = { et.go("call", back = false) { callId = Et.s(b, "id"); callName = Et.s(ex, "name"); callPhoto = Et.s(ex, "photo") } }
        if (Et.s(b, "status") == "confirmed") Et.call("call", JSONObject().put("booking_id", Et.s(b, "id"))) { r -> if (disposed) return@call; if (r.ok) go() else { toast(r.error); load() } }
        else go()
    }

    private fun rateSheet(b: JSONObject) {
        val ui = et.ui
        var stars = 0
        var text = ""
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.Star, "How was ${Et.s(b.optJSONObject("expert"), "name").ifEmpty { "your expert" }}?", "Your review shows on the expert's profile with your alias.") { close() }, -1f, -2f)
            val row = etRow(ctx, 8f, Gravity.CENTER)
            val send = EtBtn(ctx, "Send review").apply { off = true }
            fun draw() {
                row.removeAllViews()
                for (i in 1..5) row.put(FrameLayout(ctx).apply {
                    addView(etIcon(ctx, EtIcon.Star, 30f, Et.GOLD, 2f, if (i <= stars) "#E8A317" else "none"), FrameLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER))
                    contentDescription = "$i stars"
                    setOnClickListener { stars = i; draw(); send.off = busy || stars == 0 }
                }, 40f, 40f)
            }
            draw()
            p.put(row, -1f, -2f)
            p.put(etTextArea(ctx, "Write a few words (optional)", 400, 3) { text = it }, -1f, -2f)
            send.setOnClickListener {
                if (stars == 0 || busy) return@setOnClickListener
                send.off = true
                act { fin -> Et.call("rate", JSONObject().put("booking_id", Et.s(b, "id")).put("stars", stars).put("text", text)) { r -> if (r.ok) { toast("Thanks for your review!"); close() } else { toast(r.error); send.off = false }; fin() } }
            }
            p.put(send, -1f, -2f)
        }
    }

    private fun caseOf(b: JSONObject): String = Et.objs(d?.optJSONArray("sessions")).firstOrNull { Et.s(it, "id") == Et.s(b, "id") }?.let { Et.s(it, "case_id") } ?: Et.s(b, "case_id")

    private fun sideSheet(b: JSONObject) {
        val ui = et.ui
        var text = ""
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.CircleAlert, "Tell True Find your side", "The expert reported this call twice, so it ended. True Find reads both sides before deciding.") { close() }, -1f, -2f)
            val send = EtBtn(ctx, "Send my side").apply { off = true }
            val skip = EtBtn(ctx, "Skip", "s2")
            p.put(etTextArea(ctx, "What happened on the call?", 1500) { text = it; send.off = busy || it.trim().length < 10 }, -1f, -2f)
            send.setOnClickListener {
                if (busy || text.trim().length < 10) return@setOnClickListener
                send.off = true; skip.off = true
                act { fin -> Et.call("side", JSONObject().put("case_id", caseOf(b)).put("text", text)) { r -> if (r.ok) { toast("Sent to True Find"); close() } else { toast(r.error); send.off = false; skip.off = false }; fin() } }
            }
            skip.setOnClickListener {
                if (busy) return@setOnClickListener
                send.off = true; skip.off = true
                act { fin -> Et.call("side", JSONObject().put("case_id", caseOf(b)).put("skip", true)) { r -> if (r.ok) close() else { toast(r.error); skip.off = false }; fin() } }
            }
            p.put(send, -1f, -2f)
            p.put(skip, -1f, -2f)
        }
    }

    private fun reportSheet(b: JSONObject) {
        val ui = et.ui
        var text = ""
        sheet { p, close ->
            p.put(ui.sheetHead(EtIcon.Flag, "Report a problem", "Tell True Find what went wrong with this session.") { close() }, -1f, -2f)
            val send = EtBtn(ctx, "Send report", "red").apply { off = true }
            p.put(etTextArea(ctx, "What happened?", 1500) { text = it; send.off = busy || it.trim().length < 10 }, -1f, -2f)
            send.setOnClickListener {
                if (busy || text.trim().length < 10) return@setOnClickListener
                send.off = true
                act { fin -> Et.call("user_report", JSONObject().put("booking_id", Et.s(b, "id")).put("text", text)) { r -> if (r.ok) { toast("Report sent to True Find."); close() } else { toast(r.error); send.off = false }; fin() } }
            }
            p.put(send, -1f, -2f)
        }
    }
}

// ═══════════════════════════ become an expert ═══════════════════════════
@SuppressLint("ViewConstructor")
class EtApply(et: ExpertTalkScreen) : EtPage(et) {
    override val rank = 1
    private var title = ""; private val specs = ArrayList<String>(); private var years = 1; private val langs = ArrayList<String>()
    private var qual = ""; private var about = ""; private val durs = arrayListOf(25, 35, 45); private var phone = ""; private var agree = false
    private var busy = false
    private val send = EtBtn(ctx, "Send application")
    private val specBox = FrameLayout(ctx); private val langBox = FrameLayout(ctx); private val durBox = FrameLayout(ctx); private val agreeBox = FrameLayout(ctx)
    private val yearsText = etT(ctx, "1", 18f, Et.INK, true).apply { gravity = Gravity.CENTER; minWidth = Ui.dpi(24f) }
    private var built = false

    override fun back(): Boolean { et.go("home"); return true }

    private fun phoneOk(): Boolean { var p = phone.replace(Regex("\\D"), ""); if (Regex("^91\\d{10}$").matches(p)) p = p.substring(2); return Regex("^[6-9]\\d{9}$").matches(p) }
    private fun ok() = title.trim().length >= 3 && specs.isNotEmpty() && langs.isNotEmpty() && durs.isNotEmpty() && phoneOk() && agree
    private fun refresh() { send.off = !ok() || busy }

    override fun render() {
        if (built) return
        built = true
        val ui = et.ui
        val r = et.home?.optJSONObject("rules")
        rebuild { b ->
            b.put(ui.hdr("Become an Expert", false, { et.go("home") }), -1f, -2f)
            val st = ui.stack(16f)
            b.put(st, -1f, -2f)
            val how = etCol(ctx, 6f).apply { background = Tf.box(Et.TINT, 18f); setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f)) }
            how.put(etT(ctx, "How it works", 12.5f, Et.INK, true))
            for (t in listOf("Only verified creators can apply.", "True Find calls your phone number and sets up an interview on a Zoom video call.",
                    "Once approved, your account becomes Expert Talk only: no other calls, video calls or games.", "You earn ${r?.optInt("share_pct", 50) ?: 50}% of every session you complete."))
                how.put(etRow(ctx, 8f, Gravity.TOP).apply { put(etIcon(ctx, EtIcon.Check, 14f, Et.P), 14f, 14f, t = 2f); put(etT(ctx, t, 12.5f, Et.INK), 0f, -2f, 1f) }, -1f, -2f)
            st.put(how, -1f, -2f)
            st.put(field("Your profession", EtField(ctx, "e.g. Relationship Coach", maxLen = 60).apply { onText = { title = it; refresh() } }), -1f, -2f)
            st.put(labeled("Areas you help with (up to 5)", specBox), -1f, -2f)
            st.put(labeled("Languages", langBox), -1f, -2f)
            st.put(etRow(ctx, 0f).apply {
                put(etT(ctx, "Years of experience", 13f, Et.BODY, true), 0f, -2f, 1f)
                put(etRow(ctx, 12f).apply {
                    put(stepBtn("−", "Fewer years") { years = (years - 1).coerceAtLeast(0); yearsText.text = "$years" }, 38f, 38f)
                    put(yearsText)
                    put(stepBtn("+", "More years") { years = (years + 1).coerceAtMost(50); yearsText.text = "$years" }, 38f, 38f)
                })
            }, -1f, -2f)
            st.put(field("Your background (optional)", EtField(ctx, "e.g. Helped friends through breakups for 6 years", maxLen = 120).apply { onText = { qual = it } }), -1f, -2f)
            st.put(labeled("About you (optional)", etTextArea(ctx, "How you help people", 600, 3) { about = it }), -1f, -2f)
            st.put(labeled("Session lengths you offer", durBox), -1f, -2f)
            val ph = EtField(ctx, "10-digit mobile number", maxLen = 14).apply { inputType = InputType.TYPE_CLASS_PHONE; onText = { phone = it; refresh() } }
            st.put(etCol(ctx).apply {
                put(etT(ctx, "Mobile number (only True Find sees it)", 13f, Et.BODY, true), b = 6f)
                put(ph, -1f, -2f)
                put(etT(ctx, "True Find calls this number to set up your Zoom video interview. Members never see it.", 11.5f, Et.MUT))
            }, -1f, -2f)
            st.put(agreeBox, -1f, -2f)
            fillChips()
        }
        send.setOnClickListener { submit() }
        refresh()
        setSticky(send)
    }

    private fun stepBtn(s: String, label: String, onClick: () -> Unit): View = etT(ctx, s, 20f, Et.INK, true, 1f).apply {
        gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 12f, 1.5f, Et.FIELD_LINE); contentDescription = label
        setOnClickListener { onClick() }
    }
    private fun labeled(label: String, v: View): View = etCol(ctx).apply { put(etT(ctx, label, 13f, Et.BODY, true), b = 6f); put(v, -1f, -2f) }
    private fun field(label: String, f: View): View = labeled(label, f)

    private fun fillChips() {
        val ui = et.ui
        val r = et.home?.optJSONObject("rules")
        fun tog(list: MutableList<String>, v: String, max: Int) { if (list.contains(v)) list.remove(v) else if (list.size < max) list.add(v) }
        specBox.removeAllViews(); langBox.removeAllViews(); durBox.removeAllViews(); agreeBox.removeAllViews()
        specBox.addView(ui.chips(Et.strs(r?.optJSONArray("specs")).map { s -> ui.chip(Et.topicName(s), specs.contains(s)) { tog(specs, s, 5); fillChips(); refresh() } }))
        langBox.addView(ui.chips(Et.strs(r?.optJSONArray("langs")).map { s -> ui.chip(s, langs.contains(s)) { tog(langs, s, 6); fillChips(); refresh() } }))
        durBox.addView(ui.chips(Et.ints(r?.optJSONArray("durs")).map { dd -> ui.chip("$dd min · ${Et.price(r, dd)} coins", durs.contains(dd)) { if (durs.contains(dd)) durs.remove(dd) else if (durs.size < 3) durs.add(dd); fillChips(); refresh() } }))
        agreeBox.addView(etOpt(ctx, agree, etT(ctx, "I'll pick up every booked call, explain any missed session within 24 hours, and keep every call respectful and private.", 12.5f, Et.INK), check = true) { agree = !agree; fillChips(); refresh() })
    }

    private fun submit() {
        if (!ok() || busy) return
        busy = true; refresh()
        val body = JSONObject().put("title", title).put("specs", JSONArray(specs)).put("years", years).put("langs", JSONArray(langs)).put("qual", qual).put("about", about)
            .put("durs", JSONArray(durs)).put("phone", phone).put("agree", agree)
        Et.call("apply", body) { r ->
            busy = false
            if (disposed) return@call
            if (r.ok) { toast("Application sent to True Find"); et.loadHome { et.go("home") } } else { toast(r.error); refresh() }
        }
    }
}
