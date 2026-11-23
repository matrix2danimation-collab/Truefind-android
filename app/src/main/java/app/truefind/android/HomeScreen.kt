package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * TRUE FIND · HOME — native version of src/pages/Home.jsx with the look of
 * components/home/HomeView.jsx (design "13 · Home"). Same data (auth.me +
 * UserProfile, getWallet, claimDailyCoins, getDiscoverProfiles, getOnlineNow,
 * getAppConfig), same rules, same calls (startCall), same pop-ups.
 */
@SuppressLint("ViewConstructor")
class HomeScreen(ctx: Context, private val pages: NativePages) : KeepPage(ctx) {

    class Streak(val day: Int, val done: Int, val bonus: Int)
    class Daily(val ready: Boolean, val nextAt: Long, val amount: Int, val streak: Streak?, val busy: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val dm = ctx.resources.displayMetrics
    val m = Zm(min(dm.widthPixels / dm.density, 480f) / 390f)
    private val prefs = ctx.getSharedPreferences("tfhome", 0)
    val sheets = HomeSheets(this, pages)

    // ── data (same names as the web) ──
    var me: JSONObject? = null; private set
    var coins: Long? = null; private set
    private var earned: Long? = null
    private var daily: Daily? = null
    private var claiming = false
    private var dailyFresh = false
    var profiles: List<JSONObject>? = null; private set
    private var needsApp = ""
    var rates = intArrayOf(prefs.getInt("rate_voice", 10), prefs.getInt("rate_video", 40)); private set
    private var shown = false
    private var appVisible = true
    private var loadingAll = false
    private var lastBack = 0L
    private var claimOpen = false

    // ── views ──
    private val pull = PullHost(ctx)
    private val scroll = ScrollView(ctx)
    private val column = LinearLayout(ctx)
    private val avatar = FacePic(ctx)
    private val coinText: TextView
    private val hiText: TextView
    private val verifyBar: VerifyBarView
    private val dailyBox: DailyBox
    private var rmOnline: TextView? = null
    private val body = ArrayList<View>()      // everything under the greeting (hidden behind the join card)
    private var joinCard: View? = null
    private val onlineBox = LinearLayout(ctx)
    private val onlineCount: TextView
    private val onlineRow: CardRow
    private val onlineEmpty: TextView
    private val creatorsBox = FrameLayout(ctx)
    private val creatorsRow: CardRow
    private val creatorsEmpty: TextView
    private var onlineKeys = ""
    private var creatorKeys = ""
    private var creatorsAnimated = false
    private var selCreator: String? = null
    private val spinner = PullSpinner(ctx)

    init {
        setBackgroundColor(Ui.c("#FAF8FD"))
        clipChildren = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        pull.addView(scroll, LayoutParams(-1, -1))
        addView(pull, LayoutParams(-1, -1))
        val content = FrameLayout(ctx).apply { clipChildren = false }
        scroll.addView(content, LayoutParams(-1, -2))
        // quiet backdrop behind the header: dot grid from the top-right corner
        content.addView(SvgView(ctx, Svg.of(HomeArt.BACKDROP), meet = false), LayoutParams(m.pi(390f), m.pi(250f)))
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false; column.clipToPadding = false
        column.setPadding(m.pi(16f), 0, m.pi(16f), m.pi(12f) + Ui.dpi(16f))
        content.addView(column, LayoutParams(-1, -2))

        // ── header: me · wallet ──
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val me = FrameLayout(ctx).apply { contentDescription = "My profile"; setOnClickListener { pages.go("/profile") } }
        avatar.bg = Ui.c("#DBEAFE")
        me.addView(avatar, LayoutParams(m.pi(40f), m.pi(40f)))
        me.addView(View(ctx).apply { background = Tf.circle(Ui.c("#22C55E")).apply { setStroke(m.pi(2f), Ui.c("#FAF8FD")) } },
            LayoutParams(m.pi(11f), m.pi(11f), Gravity.END or Gravity.BOTTOM))
        Ui.pressable(me, 0.97f)
        header.addView(me, LinearLayout.LayoutParams(m.pi(40f), m.pi(40f)))
        header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val wallet = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Tf.boxPx(Color.WHITE, m.px(19f), max(1f, m.px(1f)), Ui.c("#EEE9F5"))
            setPadding(m.pi(8f), 0, m.pi(6f), 0)
            setOnClickListener { pages.go("/wallet") }
        }
        wallet.addView(CoverPicFit(ctx, "home/tf_coin_sm.png"), LinearLayout.LayoutParams(m.pi(20f), m.pi(20f)).apply { rightMargin = m.pi(7f) })
        coinText = Tf.tpx(ctx, "…", m.px(14f), Ui.c("#1F1235"))
        wallet.addView(coinText, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = m.pi(7f) })
        wallet.addView(FrameLayout(ctx).apply {
            background = Tf.circle(Ui.c("#6C2BD9"))
            addView(SvgView(ctx, Svg.of(HomeArt.PLUS_WHITE13)), LayoutParams(m.pi(13f), m.pi(13f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(m.pi(24f), m.pi(24f)))
        Ui.pressable(wallet, 0.97f)
        header.addView(wallet, LinearLayout.LayoutParams(-2, m.pi(38f)))
        column.addView(header, LinearLayout.LayoutParams(-1, m.pi(40f)).apply { topMargin = m.pi(14f) })

        // ── greeting ──
        val greet = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        hiText = Tf.tpx(ctx, "Hi there", m.px(20f), Ui.c("#1F1235")).apply { letterSpacing = -0.2f / 20f }
        greet.addView(hiText)
        greet.addView(Tf.tpx(ctx, "Who would you like to talk to today?", m.px(13f), Ui.c("#6B6478"), false), LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(3f) })
        column.addView(greet, gapLp(m.pi(44f)))

        // ── Verification + Daily Free Coins ──
        val vd = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        verifyBar = VerifyBarView(ctx, m) { verifyOpen() }
        vd.addView(verifyBar, LinearLayout.LayoutParams(-1, m.pi(64f)))
        dailyBox = DailyBox(ctx, m) { openClaim() }
        vd.addView(dailyBox, LinearLayout.LayoutParams(-1, m.pi(64f)).apply { topMargin = m.pi(8f) })
        addBody(vd)

        // ── Online (owner, 19 Nov evening): made like the Weekly Top Creators box — the same card size (three on the
        //    screen, more to the side), always shown (a line when no one is online). Tap → the Voice / Video sheet.
        //    Order (owner, 21 Nov): Verification · Daily Free Coins · Online · Live Rooms · Serious Single · Random Match. ──
        onlineBox.orientation = LinearLayout.VERTICAL
        onlineBox.clipChildren = false
        val oh = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ping = FrameLayout(ctx)
        ping.addView(PingDot(ctx, Ui.c("#22C55E"), m.px(10f), 1800, 3.2f, .55f, .7f), LayoutParams(m.pi(36f), m.pi(36f), Gravity.CENTER))
        ping.clipChildren = false
        val pingWrap = FrameLayout(ctx).apply { clipChildren = false }
        // the 36 box is centred on the 10 dot by the gravity alone (the ring grows past the dot)
        pingWrap.addView(ping, LayoutParams(m.pi(36f), m.pi(36f), Gravity.CENTER))
        oh.addView(pingWrap, LinearLayout.LayoutParams(m.pi(10f), m.pi(10f)).apply { rightMargin = m.pi(8f) })
        oh.clipChildren = false
        oh.addView(Tf.tpx(ctx, "Online", m.px(17f), Ui.c("#1F1235")).apply { letterSpacing = -0.1f / 17f })
        onlineCount = Tf.tpx(ctx, "0", m.px(13f), Ui.c("#15803D")).apply {
            background = Tf.boxPx(Ui.c("#DCFCE7"), m.px(10f)); setPadding(m.pi(8f), m.pi(1f), m.pi(8f), m.pi(1f)); visibility = GONE
        }
        oh.addView(onlineCount, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = m.pi(8f) })
        oh.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val seeAll = Tf.tpx(ctx, "See all", m.px(13.5f), Ui.c("#5B21B6")).apply {
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = m.pi(2f)
            setCompoundDrawablesWithIntrinsicBounds(null, null, SvgDrawable(Svg.of(HomeArt.SEE_ALL_CHEV), 14f * m.z), null)
            setOnClickListener { pages.go("/explore") }
        }
        oh.addView(seeAll, LinearLayout.LayoutParams(-2, m.pi(32f)))
        onlineBox.addView(oh, LinearLayout.LayoutParams(-1, m.pi(24f)))
        onlineRow = CardRow(ctx, { m.pi(113f) + m.pi(10f) }, glide = true)
        onlineRow.setPadding(m.pi(16f), m.pi(2f), m.pi(16f), m.pi(4f))
        onlineBox.addView(onlineRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.pi(14f); leftMargin = -m.pi(16f); rightMargin = -m.pi(16f) })
        onlineEmpty = Tf.tpx(ctx, "No one is online right now. Check back in a minute!", m.px(13f), Ui.c("#6B6478"), false).apply {
            gravity = Gravity.CENTER
            background = Tf.boxPx(Color.WHITE, m.px(16f), max(1f, m.px(1f)), Ui.c("#EEE9F5"))
            setPadding(m.pi(14f), m.pi(18f), m.pi(14f), m.pi(18f))
            visibility = GONE
        }
        onlineBox.addView(onlineEmpty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.pi(14f) })
        addBody(onlineBox)
        // ── Live Rooms ──
        // (no "All rooms ›" at the end of the line: owner, 20 Nov — the tiles below open the rooms)
        addBody(sectionHead(ctx, "Live Rooms", liveDot(ctx), null) { }, m.pi(24f))
        addBody(HomeTiles.grid(ctx, m, { p -> pages.go(p) }, { goEarn() }), m.pi(202f))
        // ── Serious Single ──
        addBody(HomeTiles.serious(ctx, m) { pages.go("/secret-app") }, m.pi(236f))
        // ── Random Match (owner, 21 Nov: moved down from under Daily Free Coins to under Serious Single) ──
        addBody(randomMatch(ctx))
        // ── Weekly Top Creators ──
        addBody(sectionHead(ctx, "Weekly Top Creators", SvgView(ctx, Svg.of(HomeArt.CROWN_HEAD18)), "See all") { sheets.allCreators() }, m.pi(24f))
        creatorsRow = CardRow(ctx, { m.pi(113f) + m.pi(10f) }, glide = true)
        creatorsRow.setPadding(m.pi(16f), m.pi(2f), m.pi(16f), m.pi(4f))
        creatorsBox.clipChildren = false
        creatorsBox.addView(creatorsRow, LayoutParams(-1, -2).apply { leftMargin = -m.pi(16f); rightMargin = -m.pi(16f) })
        creatorsEmpty = Tf.tpx(ctx, "No creators online yet. New people join every day!", m.px(13f), Ui.c("#6B6478"), false).apply {
            gravity = Gravity.CENTER
            background = Tf.boxPx(Color.WHITE, m.px(16f), max(1f, m.px(1f)), Ui.c("#EEE9F5"))
            setPadding(m.pi(14f), m.pi(18f), m.pi(14f), m.pi(18f))
        }
        creatorsBox.addView(creatorsEmpty, LayoutParams(-1, -2))
        addBody(creatorsBox)
        // ── Safe & Privacy ──
        addBody(sectionHead(ctx, "Safe & Privacy", SvgView(ctx, Svg.of(HomeArt.SHIELD_HEAD18)), null) {}, m.pi(24f))
        addBody(HomeTiles.safety(ctx, m) { k -> sheets.safety(k) }, m.pi(100f))
        // ── footer ──
        addBody(HomeTiles.footer(ctx, m, Calendar.getInstance().get(Calendar.YEAR)), m.pi(84f))

        // pull down at the top to refresh (same as the web)
        addView(spinner, LayoutParams(Ui.dpi(42f), Ui.dpi(42f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(4f) })
        spinner.elevation = Ui.dp(20f)
        pull.canPull = { scroll.scrollY <= 0 && !pages.layer.open && !spinner.spinning }
        pull.onPull = { d -> spinner.pull(d, 64f) }
        pull.onRelease = { d -> if (d >= 64f) refreshNow() else spinner.hide() }
        render()
    }

    private fun gapLp(h: Int = -2) = LinearLayout.LayoutParams(-1, h).apply { topMargin = m.pi(14f) }
    private fun addBody(v: View, h: Int = -2) { column.addView(v, gapLp(h)); body.add(v) }

    private fun liveDot(ctx: Context): View = FrameLayout(ctx).apply {
        background = Tf.circle(Ui.c("#FEE2E2"))
        addView(View(ctx).apply { background = Tf.circle(Ui.c("#EF4444")) }, LayoutParams(m.pi(7f), m.pi(7f), Gravity.CENTER))
    }

    /** "Live Rooms ─────── All rooms ›" */
    private fun sectionHead(ctx: Context, title: String, icon: View, link: String?, onLink: () -> Unit): View {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(icon, LinearLayout.LayoutParams(m.pi(if (title == "Live Rooms") 16f else 18f), m.pi(if (title == "Live Rooms") 16f else 18f)).apply { rightMargin = m.pi(8f) })
        r.addView(Tf.tpx(ctx, title, m.px(17f), Ui.c("#1F1235")).apply { letterSpacing = -0.1f / 17f })
        r.addView(View(ctx).apply { setBackgroundColor(Ui.c("#E9E0FB")) }, LinearLayout.LayoutParams(0, max(1, m.pi(1f)), 1f).apply { leftMargin = m.pi(10f); rightMargin = m.pi(10f) })
        if (link != null) r.addView(Tf.tpx(ctx, link, m.px(12.5f), Ui.c("#5B21B6")).apply {
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = m.pi(2f)
            setCompoundDrawablesWithIntrinsicBounds(null, null, SvgDrawable(Svg.of(HomeArt.CHEV_13), 13f * m.z), null)
            setPadding(0, m.pi(6f), 0, m.pi(6f))
            setOnClickListener { onLink() }
        })
        return r
    }

    // ═════════════════ Random Match box ═════════════════
    private fun randomMatch(ctx: Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Tf.boxPx(Ui.c("#F4EFFC"), m.px(22f))
            setPadding(m.pi(14f), m.pi(14f), m.pi(14f), m.pi(14f))
            minimumHeight = m.pi(366f)
            clipToOutline = true
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tt.addView(Tf.tpx(ctx, "Random Match", m.px(19f), Ui.c("#1F1235")).apply { letterSpacing = -0.2f / 19f; setPadding(0, m.pi(2f), 0, m.pi(2f)) })
        tt.addView(Tf.tpx(ctx, "Meet a new person instantly", m.px(13f), Ui.c("#4B4458"), false).apply { setPadding(0, m.pi(1f), 0, m.pi(2f)) })
        top.addView(tt, LinearLayout.LayoutParams(0, -2, 1f))
        val on = Tf.tpx(ctx, "", m.px(12f), Ui.c("#166534")).apply {
            compoundDrawablePadding = m.pi(5f)
            setCompoundDrawablesWithIntrinsicBounds(DotDrawable(Ui.c("#16A34A"), 6f * m.z), null, null, null)
            maxLines = 1
        }
        rmOnline = on
        top.addView(on)
        box.addView(top)
        // the two cards and the live matching circle (156 high)
        val stage = AbsBox(ctx)
        val cw = m.pi(118f); val ch = m.pi(140f)
        stage.put(rmCard(ctx, "home/rm_boy.jpg", Ui.c("#DBEAFE"), -5f), m.px(26f), m.px(8f), cw, ch)
        val girl = rmCard(ctx, "home/rm_girl.jpg", Ui.c("#FCE7F3"), 5f)
        stage.addView(girl, FrameLayout.LayoutParams(cw, ch, Gravity.END).apply { rightMargin = m.pi(26f); topMargin = m.pi(8f) })
        val circle = RmCircle(ctx, m)
        stage.addView(circle, FrameLayout.LayoutParams(m.pi(130f), m.pi(130f), Gravity.CENTER_HORIZONTAL).apply { topMargin = m.pi(46f + 32f - 65f) })
        box.addView(stage, LinearLayout.LayoutParams(-1, m.pi(156f)).apply { topMargin = m.pi(12f) })
        // Choose Match Mode
        val div = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        div.addView(View(ctx).apply { setBackgroundColor(Ui.c("#DDD0F7")) }, LinearLayout.LayoutParams(0, max(1, m.pi(1f)), 1f))
        div.addView(Tf.tpx(ctx, "Choose Match Mode", m.px(12.5f), Ui.c("#4B4458")).apply { setPadding(m.pi(10f), m.pi(1f), m.pi(10f), m.pi(1f)) })
        div.addView(View(ctx).apply { setBackgroundColor(Ui.c("#DDD0F7")) }, LinearLayout.LayoutParams(0, max(1, m.pi(1f)), 1f))
        box.addView(div, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.pi(12f) })
        // Voice / Video
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val voice = Tf.tpx(ctx, "Voice Call", m.px(14.5f), Ui.c("#6C2BD9")).apply {
            gravity = Gravity.CENTER
            background = Tf.boxPx(Color.WHITE, m.px(23f), m.px(1.5f), Ui.c("#6C2BD9"))
            compoundDrawablePadding = m.pi(7f)
            setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.MIC_PURPLE16), 16f * m.z), null, null, null)
            // a tap starts the call straight away — no "Random Voice Call" pop-up first (owner, 19 Nov)
            setOnClickListener { startRandom("voice") }
        }
        val video = Tf.tpx(ctx, "Video Call", m.px(14.5f), Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Tf.boxPx(Ui.c("#6C2BD9"), m.px(23f))
            compoundDrawablePadding = m.pi(7f)
            setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.VIDEO_WHITE16), 16f * m.z), null, null, null)
            setOnClickListener { startRandom("video") }
        }
        // the text + icon sit centred in the pill
        for (b in listOf(voice, video)) { Ui.pressable(b, 0.97f) }
        btns.addView(centered(ctx, voice), LinearLayout.LayoutParams(0, m.pi(46f), 1f).apply { rightMargin = m.pi(5f) })
        btns.addView(centered(ctx, video), LinearLayout.LayoutParams(0, m.pi(46f), 1f).apply { leftMargin = m.pi(5f) })
        box.addView(btns, LinearLayout.LayoutParams(-1, m.pi(46f)).apply { topMargin = m.pi(12f) })
        // (no "10 coins / min · 40 coins / min" under the buttons: owner, 19 Nov evening)
        return box
    }

    /** a view centred in a full-width cell (the web's flex: 1 + justify-content: center) — the cell takes the background */
    private fun centered(ctx: Context, v: TextView): View {
        val cell = FrameLayout(ctx)
        cell.background = v.background; v.background = null
        cell.setOnClickListener(if (v.hasOnClickListeners()) View.OnClickListener { v.performClick() } else null)
        if (cell.hasOnClickListeners()) Ui.pressable(cell, 0.97f)
        v.setOnTouchListener(null); v.isClickable = false
        cell.addView(v, LayoutParams(-2, -2, Gravity.CENTER))
        return cell
    }

    private fun rmCard(ctx: Context, asset: String, bg: Int, rot: Float): View {
        val card = FrameLayout(ctx).apply {
            background = Tf.boxPx(Color.WHITE, m.px(20f), m.px(1.5f), Ui.c("#E8C25A"))
            setPadding(m.pi(3f), m.pi(3f), m.pi(3f), m.pi(3f))
            rotation = rot
        }
        val inner = FrameLayout(ctx).apply { background = Tf.boxPx(bg, m.px(17f)); clipToOutline = true }
        inner.addView(CoverPic(ctx, asset, m.px(17f)), LayoutParams(-1, -1))
        val tag = Tf.tpx(ctx, "Online", m.px(10f), Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Tf.boxPx(Ui.c("#1F1235"), m.px(9f))
            setPadding(m.pi(6f), 0, m.pi(7f), 0)
            compoundDrawablePadding = m.pi(4f)
            setCompoundDrawablesWithIntrinsicBounds(DotDrawable(Ui.c("#22C55E"), 6f * m.z), null, null, null)
        }
        inner.addView(tag, LayoutParams(-2, m.pi(18f)).apply { leftMargin = m.pi(6f); topMargin = m.pi(6f) })
        val ok = FrameLayout(ctx).apply {
            background = Tf.circle(Ui.c("#6C2BD9")).apply { setStroke(max(1, m.pi(1.5f)), Color.WHITE) }
            addView(SvgView(ctx, Svg.of(HomeArt.CHECK_RM)), LayoutParams(m.pi(11f), m.pi(11f), Gravity.CENTER))
        }
        inner.addView(ok, LayoutParams(m.pi(20f), m.pi(20f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = m.pi(6f); bottomMargin = m.pi(6f) })
        card.addView(inner, LayoutParams(-1, -1))
        return card
    }

    // ═════════════════ shown / hidden ═════════════════
    fun setShown(on: Boolean) {
        if (on == shown) return
        shown = on
        if (on) {
            visibility = VISIBLE
            // back on Home after another page: your name / photo may have changed, and coins after a call
            loadMe()
            if (System.currentTimeMillis() - lastBack > 2000 || profiles == null) { lastBack = System.currentTimeMillis(); loadWallet(); loadPeople(); loadDaily(); loadRates() }
            schedulePoll()
        } else {
            visibility = GONE
            sheets.closeAll()
            main.removeCallbacks(poll)
            pages.toast.clear()
        }
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        if (!shown) return
        if (v) { loadWallet(); loadPeople(); loadDaily(); schedulePoll() } else main.removeCallbacks(poll)
    }

    // every minute: coins and people; the daily coins (they change once a day) every 10 minutes — fewer server
    // calls from everyone's Home (20 Nov: the app's shared server allowance ran out → "busy" everywhere)
    private var dailyAt = 0L
    private val poll = Runnable {
        if (shown && appVisible) { loadWallet(); loadPeople(); if (android.os.SystemClock.elapsedRealtime() - dailyAt > 10 * 60_000L) loadDaily() }
        schedulePoll()
    }
    private fun schedulePoll() { main.removeCallbacks(poll); if (shown) main.postDelayed(poll, 60_000) }

    fun onBack(): Boolean = sheets.onBack()

    fun scrollTop() { scroll.smoothScrollTo(0, 0) }

    // ═════════════════ data ═════════════════
    private fun loadMe(done: (() -> Unit)? = null) {
        if (!Api.ready) { main.postDelayed({ if (shown) loadMe() }, 700); done?.invoke(); return }
        Api.me { r ->
            val user = r.data
            val uid = user.optString("id")
            if (!r.ok || uid.isEmpty()) { done?.invoke(); return@me }
            Api.filter("UserProfile", JSONObject().put("created_by_id", uid)) { pr ->
                val prof = if (pr.ok) Tf.list(pr).firstOrNull() else null
                val next = JSONObject(user.toString())
                prof?.keys()?.forEach { k -> next.put(k, prof.get(k)) }
                next.put("_uid", uid).put("_pid", prof?.optString("id").orEmpty())
                if (!pr.ok && me != null) { done?.invoke(); return@filter }
                me = next
                render()
                // Signed in but sign-up never finished (no profile, or one without name / gender / date of birth /
                // state / city): nothing here works without a finished profile, so the sign-up carries on.
                // (the web's Home did this; the page under the native Home used to — now the app does)
                if (pr.ok && shown && !Demo.on && pages.preview.isEmpty()) {
                    val finished = prof != null && (prof.opt("profile_complete") == true ||
                        listOf("full_name", "gender", "date_of_birth", "state", "city").all { k -> !prof.isNull(k) && prof.optString(k).isNotEmpty() })
                    if (!finished) { pages.go("/welcome", null, true); done?.invoke(); return@filter }
                    // joined from a friend's invite: the code is used once, quietly
                    if (prof?.opt("profile_complete") == true) applyInvite()
                    // an older main photo without its small list copy: this phone makes it once (quietly, later)
                    ThumbBackfill.maybe(context, next)
                }
                done?.invoke()
            }
        }
    }

    /** a friend's invite code kept on this phone: used once the member has a finished profile (lib/referral.js) */
    private var inviteTrying = false
    private fun applyInvite() {
        val code = Session.refCode(context)
        if (inviteTrying || !Regex("^TF[A-HJ-NP-Z2-9]{5}$").matches(code)) return
        inviteTrying = true
        Api.invoke("referral", JSONObject().put("action", "apply").put("code", code).put("device_id", Session.deviceId(context))) { r ->
            inviteTrying = false
            if (r.ok) {
                Session.setRefCode(context, "")
                say("Invite from ${r.data.optString("inviter").ifEmpty { "your friend" }} added — recharge to get your bonus coins")
            } else if (r.status in 400..499 && r.status != 401 && r.status != 429) Session.setRefCode(context, "")
        }
    }

    private fun loadWallet(done: (() -> Unit)? = null) {
        if (!Api.ready) { done?.invoke(); return }
        Api.invoke("getWallet", JSONObject().put("app", "truefind")) { r ->
            val w = r.data.optJSONObject("wallet")
            if (r.ok && w != null) {
                coins = HomeRules.num(w.opt("coins")).toLong()
                earned = HomeRules.num(w.opt("earned_coins")).toLong()
                pages.onWallet(r.data)
                render()
            }
            done?.invoke()
        }
    }

    private fun parseDaily(d: JSONObject, ready: Boolean, amountDef: Int = 10): Daily {
        val s = d.optJSONObject("streak")
        val streak = s?.let { Streak(HomeRules.num(it.opt("day")).toInt().takeIf { v -> v > 0 } ?: 1, HomeRules.num(it.opt("done")).toInt(), HomeRules.num(it.opt("bonus_amount")).toInt()) }
        val amt = HomeRules.num(d.opt("amount")).toInt().takeIf { it > 0 } ?: amountDef
        return Daily(ready, Tf.ms(d.optString("next_at").takeIf { d.has("next_at") && !d.isNull("next_at") }), amt, streak)
    }

    private fun loadDaily(done: (() -> Unit)? = null) {
        if (!Api.ready) { done?.invoke(); return }
        dailyAt = android.os.SystemClock.elapsedRealtime()
        Api.invoke("claimDailyCoins", JSONObject().put("peek", true)) { r ->
            if (r.ok) {
                daily = parseDaily(r.data, r.data.optBoolean("ready"))
                dailyFresh = true
                render()
                maybePop()
            }
            done?.invoke()
        }
    }

    private fun loadPeople(done: ((List<JSONObject>?) -> Unit)? = null) {
        if (!Api.ready) { done?.invoke(null); return }
        // week: the Weekly Top row's minutes (only Home asks for them: the feed elsewhere doesn't read the calls)
        Api.invoke("getDiscoverProfiles", JSONObject().put("week", true)) { r ->
            if (r.ok) {
                val list = HomeRules.uniq(Tf.arr(r.data.optJSONArray("profiles")))
                val need = r.data.optString("needs_membership").takeIf { it.isNotEmpty() && it != "secret" && it != "null" && r.data.opt("needs_membership") != false } ?: ""
                profiles = list
                needsApp = need
                render()
                done?.invoke(list)
            } else {
                if (profiles == null) { profiles = emptyList(); render() }
                done?.invoke(null)
            }
        }
    }

    private fun loadRates() {
        if (!Api.ready) return
        Api.invoke("getAppConfig") { r ->
            val rt = r.data.optJSONObject("config")?.optJSONObject("rates") ?: return@invoke
            if (r.data.optBoolean("fallback")) return@invoke
            rates = intArrayOf(rt.optInt("voice", rates[0]), rt.optInt("video", rates[1]))
            prefs.edit().putInt("rate_voice", rates[0]).putInt("rate_video", rates[1]).apply()
            render()
        }
    }

    /** pull down at the top: everything loads again (the spinner turns until it is all here) */
    private fun refreshNow() {
        spinner.spin(22f)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        lastBack = System.currentTimeMillis()
        val t0 = System.currentTimeMillis()
        var left = 4
        val one = { left -= 1; if (left == 0) main.postDelayed({ spinner.hide() }, (650 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)) }
        main.postDelayed({ if (spinner.spinning) spinner.hide() }, 8000)
        loadMe { one() }; loadWallet { one() }; loadPeople { one() }; loadDaily { one() }
    }

    // ═════════════════ derived ═════════════════
    private fun myIds(): Set<String> = listOfNotNull(me?.optString("_uid"), me?.optString("_pid"), me?.optString("id")).filter { it.isNotEmpty() }.toSet()
    fun isMe(p: JSONObject): Boolean { val ids = myIds(); return ids.contains(p.optString("id")) || ids.contains(p.optString("user_id")) || ids.contains(p.optString("created_by_id")) }
    fun others(): List<JSONObject> = (profiles ?: emptyList()).filter { !isMe(it) }
    fun creators(): List<JSONObject> {
        fun rank(p: JSONObject) = if (HomeRules.isFree(p)) 2 else if (HomeRules.isBusy(p)) 1 else 0
        return others().sortedWith(Comparator { a, b ->
            val d = HomeRules.weekMins(b) - HomeRules.weekMins(a)
            if (d != 0.0) (if (d > 0) 1 else -1) else rank(b) - rank(a)
        })
    }
    fun myGender() = HomeRules.genderOf(me)
    fun rateOf(type: String) = if (type == "video") rates[1] else rates[0]
    fun myPhotoRaws(): List<String> { val full = HomeRules.photoOf(me); return listOf(HomeRules.thumbOf(me, full), full).filter { it.isNotEmpty() } }

    private val girls by lazy { (0 until 20).map { "home/faces/g%02d.jpg".format(it) } }
    private val boys by lazy { (0 until 20).map { "home/faces/b%02d.jpg".format(it) } }

    // ═════════════════ drawing the data ═════════════════
    private fun render() {
        val me = me
        avatar.setPhoto(myPhotoRaws())
        coinText.text = coins?.let { Tf.inr(it) } ?: "…"
        val first = HomeRules.firstName(me)
        hiText.text = if (first.isNotEmpty()) "Hi, $first" else "Hi there"
        verifyBar.set(HomeRules.verifyOf(me))
        dailyBox.set(daily?.let { Daily(it.ready, it.nextAt, it.amount, it.streak, claiming) })
        val others = others()
        val onlineList = others.filter { HomeRules.isOnline(it) } // (on a call too: shown as online — 23 Nov evening)
        rmOnline?.text = if (profiles == null) "Checking who is online…" else if (onlineList.isNotEmpty()) "${onlineList.size} online now" else "Tap a call to start"
        // join card instead of the page body (not a member of this app yet)
        if (needsApp.isNotEmpty()) {
            if (joinCard == null) { joinCard = sheets.joinCard(needsApp) { needsApp = ""; render(); loadAllNow() }; column.addView(joinCard, 3, gapLp()) }
            body.forEach { it.visibility = GONE }
            return
        } else {
            joinCard?.let { column.removeView(it) }; joinCard = null
            body.forEach { it.visibility = VISIBLE }
        }
        renderOnline(others)
        renderCreators()
    }

    private fun loadAllNow() { loadMe(); loadWallet(); loadPeople(); loadDaily() }

    /** Online: only people online right now (free first), up to 20, the other gender; 3D faces, two neighbours never the same. */
    private fun renderOnline(others: List<JSONObject>) {
        val myG = myGender()
        val list = others.filter { HomeRules.isOnline(it) && (myG.isEmpty() || HomeRules.genderOf(it).isEmpty() || HomeRules.genderOf(it) != myG) }
            .sortedWith(compareByDescending<JSONObject> { if (HomeRules.isFree(it)) 1 else 0 }).take(20)
        val keys = list.joinToString("|") { (HomeRules.idOf(it).ifEmpty { it.optString("id") }) + ":" + HomeRules.isFree(it) + ":" + HomeRules.firstName(it) + HomeRules.ageOf(it) + HomeRules.cityOf(it) }
        onlineEmpty.visibility = if (list.isEmpty()) VISIBLE else GONE
        onlineRow.visibility = if (list.isEmpty()) GONE else VISIBLE
        onlineCount.visibility = if (list.isEmpty()) GONE else VISIBLE
        onlineCount.text = list.size.toString()
        if (keys == onlineKeys) return
        onlineKeys = keys
        val row = onlineRow.row
        row.removeAllViews()
        var lastFace = -1
        list.forEachIndexed { i, p ->
            val set = if (HomeRules.genderOf(p) == "m" || (HomeRules.genderOf(p).isEmpty() && myG == "f")) boys else girls
            var n = (HomeRules.hash(HomeRules.idOf(p).ifEmpty { p.optString("id") }) % set.size).toInt()
            if (n == lastFace) n = (n + 1) % set.size
            lastFace = n
            row.addView(HomeTiles.onlineCard(context, m, p, set[n]) { sheets.person(p) },
                LinearLayout.LayoutParams(m.pi(113f), m.pi(188f)).apply { if (i > 0) leftMargin = m.pi(10f) })
        }
    }

    /** Weekly Top Creators: 20 cards, every one a different 3D face; tap → video call straight away. */
    private fun renderCreators() {
        val all = creators()
        val top = all.take(20)
        val myG = myGender()
        val keys = top.joinToString("|") { (HomeRules.idOf(it).ifEmpty { it.optString("id") }) + ":" + HomeRules.isFree(it) + HomeRules.isBusy(it) + HomeRules.weekMins(it) + HomeRules.firstName(it) + HomeRules.ageOf(it) }
        creatorsEmpty.visibility = if (top.isEmpty()) VISIBLE else GONE
        creatorsRow.visibility = if (top.isEmpty()) GONE else VISIBLE
        if (keys == creatorKeys) return
        creatorKeys = keys
        val row = creatorsRow.row
        row.removeAllViews()
        val used = mapOf("girls" to HashSet<Int>(), "boys" to HashSet<Int>())
        top.forEachIndexed { i, p ->
            val g = HomeRules.genderOf(p)
            val which = if (g == "m" || (g.isEmpty() && myG == "f")) "boys" else "girls"
            val set = if (which == "boys") boys else girls
            val u = used.getValue(which)
            val key = HomeRules.idOf(p).ifEmpty { p.optString("id") }.ifEmpty { i.toString() }
            var n = (HomeRules.hash(key) % set.size).toInt()
            var tries = 0
            while (tries < set.size && u.contains(n)) { n = (n + 1) % set.size; tries++ }
            u.add(n)
            val card = HomeTiles.creatorCard(context, m, p, set[n], key == (selCreator ?: HomeRules.idOf(top[0]).ifEmpty { top[0].optString("id") })) {
                selCreator = key; repaintCreatorBorders(); callPerson(p, "video")
            }
            card.tag = key
            row.addView(card, LinearLayout.LayoutParams(m.pi(113f), m.pi(188f)).apply { if (i > 0) leftMargin = m.pi(10f) })
            if (!creatorsAnimated) {
                card.alpha = 0f; card.translationX = m.px(18f)
                card.animate().alpha(1f).translationX(0f).setStartDelay(min(i, 6) * 60L).setDuration(450)
                    .setInterpolator(android.view.animation.PathInterpolator(.22f, .8f, .3f, 1f)).start()
            }
        }
        if (top.isNotEmpty()) creatorsAnimated = true
    }

    private fun repaintCreatorBorders() {
        val row = creatorsRow.row
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            // the border is the card's foreground (drawn over its doodle top, whole at the corners)
            (c.foreground as? GradientDrawable)?.setStroke(m.pi(1.5f), if (c.tag == selCreator) Ui.c("#6C2BD9") else Ui.c("#EEE9F5"))
        }
    }

    // ═════════════════ actions ═════════════════
    private fun verifyOpen() { val v = HomeRules.verifyOf(me) ?: return; pages.go(v.to) }

    fun goEarn() {
        val me = me
        if (me == null) { pages.go("/profile"); return }
        val female = myGender() == "f"
        val st = if (female) HomeRules.creatorStatus(me) else HomeRules.hostStatus(me)
        if (st == "approved") pages.go("/wallet?tab=earnings") else pages.go("/profile?open=${if (female) "creator" else "host"}")
    }

    // ── Daily Free Coins ──
    private fun openClaim() { if (daily != null) { claimOpen = true; DailyClaimSheet.open(this, pages, daily!!, HomeRules.firstName(me), firstTime()) { claimOpen = false } } }
    private fun firstTime(): Boolean = !prefs.getBoolean("tf_daily_ever", false) && (daily?.streak?.done ?: 0) == 0

    /** The pop-up opens by itself once a day (per user, Indian date) when today's coins are ready. */
    private fun maybePop() {
        val d = daily ?: return
        if (!dailyFresh || !d.ready || needsApp.isNotEmpty() || claimOpen || !shown) return
        if (pages.preview.isNotEmpty()) return   // test screens: the pop-up opens only when asked ("daily")
        val key = "tf_daily_pop:${me?.optString("_uid")?.ifEmpty { null } ?: "me"}"
        val today = Tf.todayIst()
        if (prefs.getString(key, "") == today) return
        main.postDelayed({
            if (!shown || claimOpen || prefs.getString(key, "") == today) return@postDelayed
            prefs.edit().putString(key, today).apply()
            openClaim()
        }, 700)
    }

    /** Claims today's coins. quiet: the pop-up shows the result itself. → ok / already / error */
    fun claimDaily(quiet: Boolean, done: (ok: Boolean, already: Boolean, amount: Int, day: Int, error: String) -> Unit) {
        if (claiming) { done(false, false, 0, 0, ""); return }
        claiming = true; render()
        Api.invoke("claimDailyCoins", JSONObject()) { r ->
            claiming = false
            val d = r.data
            if (r.ok) {
                val amount = HomeRules.num(d.opt("claimed")).toInt().takeIf { it > 0 } ?: HomeRules.num(d.opt("amount")).toInt().takeIf { it > 0 } ?: 10
                daily = parseDaily(d, false, amount)
                val v = d.optJSONObject("wallet")?.opt("coins")
                if (v != null && v != JSONObject.NULL) coins = HomeRules.num(v).toLong() else loadWallet()
                val day = HomeRules.num(d.optJSONObject("streak")?.opt("done")).toInt()
                prefs.edit().putBoolean("tf_daily_ever", true).apply()
                render()
                if (!quiet) say(if (day >= 7) "Day 7 streak! $amount free coins added" else "$amount free coins added${if (day > 1) " · day $day streak" else ""}")
                done(true, false, amount, if (day > 0) day else 1, "")
            } else if (d.has("next_at") && !d.isNull("next_at")) {
                daily = parseDaily(d, false)
                render()
                say("You already claimed today's coins")
                done(false, true, 0, 0, "")
            } else {
                render()
                val msg = Tf.err(r, "Could not claim the coins. Please try again.")
                if (!quiet) say(msg)
                done(false, false, 0, 0, msg)
            }
        }
    }

    fun say(text: String) = pages.toast.show(text, bottomPx = pages.nav.barPx + Ui.dpi(16f))

    // ── calls ──
    private var calling = ""
    private var matchRun = 0
    private var lastMatch = ""
    val callingType: String get() = calling

    fun showCoins(type: String, d: JSONObject) {
        val rate = HomeRules.num(d.opt("rate")).toInt().takeIf { it > 0 } ?: rateOf(type)
        val bal = if (d.has("balance") && !d.isNull("balance")) HomeRules.num(d.opt("balance")).toLong() else (coins ?: 0L)
        sheets.dialog("Add coins to call", "${if (type == "video") "Video" else "Voice"} calls cost $rate coins per minute. Your balance is ${Tf.inr(bal)} coins.", "Add coins") { pages.go("/wallet") }
    }

    /**
     * Call one person (Weekly Top Creators / the person sheet). Server busy (read limit): wait as asked (≤ 12 s) and try once more.
     * The "Calling …" screen shows at the first tap (owner, 19 Nov: the call screen came only seconds later, so he tapped
     * "Video Call" again and again); Cancel there (or Back) stops it, and a call that was already made then is ended.
     */
    private var callRun = 0
    /** the tap being started now (Cancel ends whatever it made) */
    private var starting: CallStart.Handle? = null
    fun callPerson(p: JSONObject, type: String) {
        if (calling.isNotEmpty()) return
        calling = type
        val run = ++callRun
        sheets.refreshPerson()
        sheets.showMatching(type, "direct", HomeRules.firstName(p)) { cancelCall() }
        // one tap = one call: a lost / busy answer is asked again by itself (the server answers with the call this tap
        // already made), and a start that never got through is closed on the server (CallStart, 23 Nov)
        starting = CallStart.start(HomeRules.idOf(p), type) { r ->
            if (run != callRun) return@start
            starting = null
            val id = r.data.optJSONObject("call")?.optString("id").orEmpty()
            calling = ""
            if (r.ok && id.isNotEmpty()) {
                sheets.hideMatching()
                sheets.closeAll()
                // the call screen knows at once who is being called (name, photo) and whether it is a video call
                pages.go("/call/$id", peerHint(p).put("type", type))
                return@start
            }
            val d = r.data
            val st = r.status
            // they are on another call: never "<name> is busy" (owner, 23 Nov evening) — the call goes to someone else
            // by itself, the same way as Random Match (the "Calling …" screen turns into "Finding someone for you…")
            if (CallRules.busyKind(st, d.optString("error"), d.optString("code")) == CallRules.Busy.PEER) {
                startRandom(type, insteadOf = HomeRules.idOf(p)); return@start
            }
            sheets.hideMatching()
            sheets.closePersonAndAll()
            val busy = CallStart.busyDialog(r, HomeRules.firstName(p))
            when {
                st == 402 || d.optString("error") == "insufficient_coins" -> showCoins(type, d)
                // I am on another call myself (my other call is still on)
                busy != null -> sheets.dialog(busy.first, busy.second)
                else -> {
                    val why = d.optString("detail").take(160)
                    val code = if (r.ok) "no_call" else if (st > 0) "code $st${if (why.isNotEmpty()) ": $why" else ""}" else "Network Error"
                    val base = if (r.ok) "Something went wrong. Please try again." else Tf.err(r, "Something went wrong. Please try again.")
                    sheets.dialog("Couldn't connect", "$base ($code)")
                }
            }
        }
    }

    /** Cancel on the "Calling …" screen of a direct call (or Back): nothing rings */
    fun cancelCall() {
        if (calling.isEmpty()) return
        callRun += 1
        // nothing rings: a call this tap already made is ended at once
        starting?.cancel(); starting = null
        calling = ""
        sheets.hideMatching()
        sheets.refreshPerson()
    }

    /**
     * Random Match: searches without stopping until someone picks up — no "No one is free" pop-up, no second tap
     * (owner, 21 Nov). One creator at a time rings for 12 s, then the next (RandomSearch). Cancel / Back stops it.
     */
    private var search: RandomSearch? = null
    /**
     * [insteadOf]: a call that found that person on another call (Home, or Call Back's buttons) goes to someone else by
     * itself (owner, 23 Nov evening) — the "Calling …" screen (if up) turns into the search.
     */
    fun startRandom(type: String, insteadOf: String = "") {
        if (sheets.matching && insteadOf.isEmpty()) return
        search?.cancel(); search = null // (never two searches at once)
        val run = ++matchRun
        sheets.closeConfirm()
        sheets.showMatching(type, "search", "") { cancelRandom() }
        val s = RandomSearch(type, object : RandomSearch.Host {
            override fun stage(stage: String, name: String, photo: String) {
                if (run != matchRun) return
                // ("random": the calling screen with its Cancel — the search can be stopped while she rings too)
                if (stage == "search") sheets.showMatching(type, "search", "") { cancelRandom() }
                else sheets.showMatching(type, "random", name) { cancelRandom() }
            }
            override fun found(callId: String, peer: JSONObject) {
                if (run != matchRun) return
                search = null
                lastMatch = ""
                sheets.hideMatching()
                if (insteadOf.isNotEmpty()) sheets.closePersonAndAll() // (the pop-up of the one who was on another call)
                pages.go("/call/$callId", JSONObject().put("peer", peer).put("type", type))
            }
            override fun onCoins(data: JSONObject) { if (run != matchRun) return; search = null; sheets.hideMatching(); showCoins(type, data) }
            override fun onStop(title: String, text: String) { if (run != matchRun) return; search = null; sheets.hideMatching(); sheets.dialog(title, text) }
        }, insteadOf)
        search = s
        s.start()
    }

    fun cancelRandom() { matchRun += 1; search?.cancel(); search = null; sheets.hideMatching() }

    /** what the call screen shows before it has loaded anything (the web's location.state.peer) */
    private fun peerHint(p: JSONObject) = JSONObject().put("peer", JSONObject().put("name", HomeRules.firstName(p)).put("photo", HomeRules.listPhoto(p, HomeRules.photoOf(p))))

    /** test mode only: open one pop-up so the emulator can photograph it */
    fun previewOpen(what: String) {
        val first = others().firstOrNull()
        when (what) {
            "confirm" -> sheets.confirm("video")
            "person" -> first?.let { sheets.person(it) }
            "all" -> sheets.allCreators()
            "daily" -> openClaim()
            "dialog" -> sheets.safety("verified")
            "match" -> sheets.showMatching("video", "search", "") { cancelRandom() }
            "toast" -> say("10 free coins added · day 2 streak")
            "scroll1" -> scroll.scrollTo(0, (column.height * 0.33f).toInt())
            "scroll2" -> scroll.scrollTo(0, (column.height * 0.62f).toInt())
            "scroll3" -> scroll.scrollTo(0, column.height)
        }
    }

    /** Test mode: made-up data straight away. */
    fun previewData() { loadAllNow(); loadRates() }

    override fun onAttachedToWindow() { super.onAttachedToWindow() }
}

/** The Verification bar (VerifyBar of HomeView.jsx). */
@SuppressLint("ViewConstructor")
class VerifyBarView(ctx: Context, private val m: Zm, private val onOpen: () -> Unit) : LinearLayout(ctx) {
    private val ring = StepRing(ctx)
    private val seal = SvgView(ctx, Svg.of(HomeArt.SEAL_FULL))
    private val main: TextView
    private val capText: TextView
    private val capTicks = LinearLayout(ctx)
    private val right = FrameLayout(ctx)
    private var v: HomeRules.Verify? = null
    private var drawn = "?"

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(m.pi(11f), 0, m.pi(12f), 0)
        val icon = FrameLayout(ctx)
        icon.addView(ring, FrameLayout.LayoutParams(-1, -1)); icon.addView(seal, FrameLayout.LayoutParams(-1, -1))
        addView(icon, LayoutParams(m.pi(42f), m.pi(42f)).apply { rightMargin = m.pi(11f) })
        val mid = LinearLayout(ctx).apply { orientation = VERTICAL }
        mid.addView(Tf.tpx(ctx, "VERIFICATION", m.px(10.5f), Ui.c("#5B21B6")).apply {
            letterSpacing = 0.9f / 10.5f
            compoundDrawablePadding = m.pi(4f)
            setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(HomeGlyph.shield("#6C2BD9", true), 10f * m.z), null, null, null)
        })
        main = Tf.tpx(ctx, "Checking…", m.px(16f), Ui.c("#1F1235")).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        mid.addView(main, LayoutParams(-1, -2).apply { topMargin = m.pi(2f) })
        capText = Tf.tpx(ctx, "", m.px(12f), Ui.c("#6C2BD9")).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        mid.addView(capText, LayoutParams(-1, -2).apply { topMargin = m.pi(2f) })
        capTicks.orientation = HORIZONTAL; capTicks.gravity = Gravity.CENTER_VERTICAL
        mid.addView(capTicks, LayoutParams(-1, -2).apply { topMargin = m.pi(2f) })
        addView(mid, LayoutParams(0, -2, 1f).apply { rightMargin = m.pi(11f) })
        addView(right, LayoutParams(-2, -2))
        setOnClickListener { if (v != null) onOpen() }
        Ui.pressable(this, 0.985f)
        set(null)
    }

    fun set(nv: HomeRules.Verify?) {
        val key = nv?.let { "${it.state}|${it.left}|${it.waiting}|${it.step}|${it.tf}|${it.sd}" } ?: ""
        if (key == drawn) return
        drawn = key
        v = nv
        val st = nv?.state ?: ""
        val full = st == "full"; val tf = st == "tf"; val wait = nv?.waiting == true
        background = if (full) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#F4EEFF"), Color.WHITE, Color.WHITE)).apply {
            cornerRadius = m.px(18f); setStroke(max(1, m.pi(1f)), Ui.c("#E4D8FB"))
        } else Tf.boxPx(Color.WHITE, m.px(18f), max(1f, m.px(1f)), Ui.c("#EEE9F5"))
        fun plural(n: Int) = "$n step${if (n == 1) "" else "s"} left"
        main.text = if (nv == null) "Checking…" else if (full) "Fully Verified" else if (tf) (if (wait) "Secret Dating in review" else "Secret Dating pending") else plural(nv.left)
        main.setTextColor(if (full) Ui.c("#4A1D96") else Ui.c("#1F1235"))
        // icon
        seal.visibility = if (full) VISIBLE else GONE
        ring.visibility = if (full) GONE else VISIBLE
        ring.tf = nv?.tf ?: listOf("todo", "todo", "todo"); ring.sd = nv?.sd ?: listOf("todo")
        ring.glyph = if (tf) HomeGlyph.shield("#16A34A", true) else HomeGlyph.shield(if (wait) "#B45309" else "#6C2BD9", false)
        ring.invalidate()
        // caption
        capTicks.removeAllViews()
        if (nv == null) { capText.visibility = GONE; capTicks.visibility = GONE }
        else if (full || tf) {
            capText.visibility = GONE; capTicks.visibility = VISIBLE
            fun tick() = SvgView(context, HomeGlyph.tick())
            capTicks.addView(tick(), LayoutParams(m.pi(11f), m.pi(11f)).apply { rightMargin = m.pi(4f) })
            capTicks.addView(Tf.tpx(context, if (full) "True Find" else "True Find verified", m.px(12f), Ui.c("#15803D")))
            if (full) {
                capTicks.addView(tick(), LayoutParams(m.pi(11f), m.pi(11f)).apply { leftMargin = m.pi(9f); rightMargin = m.pi(4f) })
                capTicks.addView(Tf.tpx(context, "Secret Dating", m.px(12f), Ui.c("#15803D")))
            }
        } else {
            capTicks.visibility = GONE; capText.visibility = VISIBLE
            capText.text = if (wait) "${nv.step} · in review" else "Next: ${nv.step}"
            capText.setTextColor(if (wait) Ui.c("#B45309") else Ui.c("#6C2BD9"))
        }
        // right side
        right.removeAllViews()
        if (nv == null) return
        when {
            full -> right.addView(FrameLayout(context).apply {
                background = Tf.circle(Ui.c("#F1EAFE"))
                addView(SvgView(context, Svg.of(HomeArt.CHEV_PURPLE)), FrameLayout.LayoutParams(m.pi(14f), m.pi(14f), Gravity.CENTER))
            }, FrameLayout.LayoutParams(m.pi(30f), m.pi(30f)))
            wait -> right.addView(Tf.tpx(context, "In review", m.px(12f), Ui.c("#B45309")).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Tf.boxPx(Ui.c("#FEF3C7"), m.px(14f)); setPadding(m.pi(10f), 0, m.pi(10f), 0)
                compoundDrawablePadding = m.pi(5f)
                setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.CLOCK_AMBER), 12f * m.z), null, null, null)
            }, FrameLayout.LayoutParams(-2, m.pi(28f)))
            else -> right.addView(Tf.tpx(context, if (tf) "Verify" else "Continue", m.px(14f), Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = Tf.boxPx(if (tf) Ui.c("#DB2777") else Ui.c("#6C2BD9"), m.px(18f)); setPadding(m.pi(16f), 0, m.pi(16f), 0)
            }, FrameLayout.LayoutParams(-2, m.pi(36f)))
        }
    }
}

/** The TF coin picture as a drawable (img tf-coin-sm). */
class CoinPicDrawable(private val ctx: Context, private val sizeDp: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    override fun getIntrinsicWidth() = Ui.dpi(sizeDp)
    override fun getIntrinsicHeight() = Ui.dpi(sizeDp)
    override fun draw(c: android.graphics.Canvas) {
        val b = Tf.pic(ctx, "home/tf_coin_sm.png") ?: return
        val r = bounds
        val k = min(r.width().toFloat() / b.width, r.height().toFloat() / b.height)
        val w = b.width * k; val h = b.height * k
        c.drawBitmap(b, null, android.graphics.RectF(r.exactCenterX() - w / 2f, r.exactCenterY() - h / 2f, r.exactCenterX() + w / 2f, r.exactCenterY() + h / 2f), p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Pull down at the top of a page to refresh (the child decides when it is at the top). */
class PullHost(ctx: Context) : FrameLayout(ctx) {
    private val g = PullGesture(ctx)
    var canPull: () -> Boolean get() = g.canPull; set(v) { g.canPull = v }
    var onPull: (Float) -> Unit get() = g.onPull; set(v) { g.onPull = v }
    var onRelease: (Float) -> Unit get() = g.onRelease; set(v) { g.onRelease = v }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = g.intercept(e)
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = g.touch(e)
}

/**
 * The pull-down gesture itself, for any page (a page passes its onInterceptTouchEvent / onTouchEvent here).
 * It starts at the same distance the page's own scrolling does (never later), and a page sees a touch before
 * its scroll does, so a pull at the top is always the pull — before 19 Nov the scroll could take it first.
 */
class PullGesture(ctx: Context) {
    var canPull: () -> Boolean = { false }
    var onPull: (Float) -> Unit = {}
    var onRelease: (Float) -> Unit = {}
    private val slop = min(Ui.dp(10f), android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat())
    private var track = false; private var pulling = false
    private var x0 = 0f; private var y0 = 0f; private var d = 0f
    private fun travel(dy: Float): Float { val v = ((dy - slop) / Ui.density).coerceAtLeast(0f) * 0.55f; return if (v <= 60f) v else min(112f, 60f + (v - 60f) * 0.4f) }
    fun intercept(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pulling = false; track = canPull(); x0 = e.x; y0 = e.y; d = 0f }
            MotionEvent.ACTION_MOVE -> if (track && !pulling) {
                val dx = e.x - x0; val dy = e.y - y0
                if (abs(dx) >= slop || abs(dy) >= slop) { if (dy > 0 && dy > abs(dx) * 1.4f && canPull()) pulling = true else track = false }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!pulling) track = false
        }
        return pulling
    }
    fun touch(e: MotionEvent): Boolean {
        if (!pulling) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> { d = travel(e.y - y0); onPull(d) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pulling = false; track = false; onRelease(if (e.actionMasked == MotionEvent.ACTION_UP) d else 0f); d = 0f }
        }
        return true
    }
}

@Suppress("unused") private val keepVG: Class<*> = ViewGroup::class.java
