package app.truefind.android.login

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.truefind.android.Ui
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 1. GET STARTED (Welcome.jsx IntroScreen): one page. Only the top part (picture + its words) slides:
 * 1 → 2 → 3, then it glides back to 1 and starts again; a finger swipe moves it too. The white card at
 * the bottom (Get Started) stays still. Drawn on the 390 × 844 design board and scaled to fit the
 * phone, like the web page.
 */
@SuppressLint("ViewConstructor")
internal class IntroStep(ctx: Context, private val page: WelcomePage) : WelcomeStep(ctx) {

    companion object {
        const val BW = 390f
        const val BH = 844f
        const val CARD_H = 168f
        const val REST_MS = 3000L     // each screen stays still this long
        const val SLIDE_MS = 650L     // one screen to the next
        const val BACK_MS = 1000L     // from screen 3 back to screen 1
        val EASE = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        val BG = intArrayOf(LK.c("#4A1D96"), LK.c("#861A4B"), LK.c("#0B4F5C"))
        val DOODLE = arrayOf("calls", "nearby", "safety")
        private const val W80 = 0xCCFFFFFF.toInt()

        class Words(val dot: String, val label: String, val title: String, val text: String, val feats: List<Pair<String, String>>)
        val WORDS = listOf(
            Words("#F9A8D4", "Real conversations", "Voice & Video Calls", "Talk to real people near you — anytime, anywhere.", listOf(
                """<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1.1 5.9L12 16.9l-5.3 2.8 1.1-5.9L3.5 9.7l5.9-.8z" fill="rgba(255,255,255,0.8)"/>""" to "HD voice & video",
                """<circle cx="12" cy="12" r="8.5" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2.2"/><path d="M12 7.5v9M9.5 9.5h3.2a1.6 1.6 0 0 1 0 3.2H9.5h3.6a1.6 1.6 0 0 1 0 3.2H9.5" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="1.8" stroke-linecap="round"/>""" to "Pay per minute",
            )),
            Words("#FCD34D", "Nearby", "Find People Nearby", "See who is online around you on the live India map.", listOf(
                """<path d="M3 7h3.5c2 0 3.2 1 4.3 2.6L13 13c1.1 1.6 2.3 2.6 4.3 2.6H20M3 17h3.5c1.4 0 2.4-.5 3.2-1.3M20 8.4h-2.7c-1.4 0-2.4.5-3.2 1.3M17.5 5.5L20 8.4l-2.5 2.9M17.5 12.7l2.5 2.9-2.5 2.9" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>""" to "Random match",
                """<path d="M4 6c3-1.5 13-1.5 16 0v5c0 5-4 8-8 8s-8-3-8-8z" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2.2"/><circle cx="9" cy="11" r="1.5" fill="rgba(255,255,255,0.8)"/><circle cx="15" cy="11" r="1.5" fill="rgba(255,255,255,0.8)"/>""" to "Reconnect",
            )),
            Words("#6EE7B7", "Safety", "100% Safe & Private", "No fake users. Your data stays yours.", listOf(
                """<rect x="4" y="5" width="16" height="14" rx="3" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2.2"/><circle cx="12" cy="12" r="3" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2"/><circle cx="17" cy="8.5" r="1" fill="rgba(255,255,255,0.8)"/>""" to "Live selfie check",
                """<rect x="3.5" y="5" width="17" height="14" rx="2.5" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2.2"/><path d="M5 17l4.5-5 3.5 4 2.5-3 3.5 4" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2" stroke-linejoin="round"/><circle cx="15.5" cy="9" r="1.3" fill="rgba(255,255,255,0.8)"/>""" to "Photo review",
                """<circle cx="12" cy="12" r="8.5" fill="none" stroke="rgba(255,255,255,0.8)" stroke-width="2.2"/><path d="M6 6l12 12" stroke="rgba(255,255,255,0.8)" stroke-width="2.2" stroke-linecap="round"/>""" to "Block anytime",
            )),
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var s = 1f            // how much the design board is scaled
    private var w = 0
    private var index = 0
    private var pos = 0
    private var lastMs = 0L
    private var live = false
    private var holding = false
    private var info = false
    private var appShown = true
    private val reduce = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    private var anim: ValueAnimator? = null

    private val stage = Stage(ctx)
    private val track = Track(ctx)
    private val panels = ArrayList<Panel>()
    private val over = Board(ctx, BW, BH)
    private val dots = Dots(ctx, 3)
    private val card = FrameLayout(ctx)
    private val cardBoard = Board(ctx, BW, CARD_H)

    init {
        setBackgroundColor(BG[0])
        for (i in 0..2) {
            val b = Board(ctx, BW, BH)
            b.addView(hero(ctx, i), LK.flp(Ui.dpi(390f), Ui.dpi(330f), Gravity.TOP or Gravity.START, t = 100f).also { })
            b.addView(words(ctx, i), LK.flp(Ui.dpi(342f), -2, Gravity.TOP or Gravity.START, l = 24f, t = 480f))
            val p = Panel(ctx, BG[i], DOODLE[i], b)
            panels.add(p); track.addView(p)
        }
        // the hero box is scaled 1.08 from its top centre (transform-origin 50% 0%)
        for (p in panels) (p.board.getChildAt(0)).apply { pivotX = Ui.dp(195f); pivotY = 0f; scaleX = 1.08f; scaleY = 1.08f }
        // logo + dots: above the moving strip, they stay in place
        over.addView(LK.t(ctx, "TrueFind", 22f, Color.WHITE, true, 1f, -0.2f), LK.flp(-2, -2, Gravity.TOP or Gravity.START, l = 24f, t = 56f))
        over.addView(dots, LK.flp(-2, Ui.dp(11f * 1.2207f).roundToInt(), Gravity.TOP or Gravity.END, t = 480f, r = 24f))
        over.isClickable = false
        // the white card
        card.isClickable = true
        card.addView(cardBoard, FrameLayout.LayoutParams(-2, -2))
        cardBoard.addView(cardContent(ctx), FrameLayout.LayoutParams(-1, -1))
        stage.addView(track); stage.addView(over); stage.addView(card)
        addView(stage, LayoutParams(-1, -1))
        dots.set(0, false)
    }

    // ═════════════════ the three screens ═════════════════
    private fun hero(ctx: Context, i: Int): View = when (i) {
        0 -> callsHero(ctx)
        1 -> ArtView(ctx, IntroArt.MATCH) { s * 1.08f }
        else -> ArtView(ctx, IntroArt.SECURE) { s * 1.08f }
    }

    private fun svg24(inner: String) = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">$inner</svg>"""

    /** the words under the picture: small label, title, line, and the features with thin bars between */
    private fun words(ctx: Context, i: Int): View {
        val d = WORDS[i]
        val col = LK.col(ctx)
        val head = LK.row(ctx)
        head.addView(View(ctx).apply { background = CssBox(LK.c(d.dot), 3f) }, LK.lp(Ui.dpi(6f), Ui.dpi(6f), r = 7f))
        head.addView(LK.t(ctx, d.label.uppercase(), 11f, W80, true, ls = 1.4f))
        col.addView(head)
        col.addView(LK.t(ctx, d.title, 26f, Color.WHITE, true, 1.12f, -0.3f), LK.lp(-1, -2, t = 12f))
        col.addView(LK.t(ctx, d.text, 14.5f, LK.a(Color.WHITE, 0.82f), false, 1.42f).apply { maxWidth = Ui.dpi(310f) }, LK.lp(-2, -2, t = 8f))
        val feats = LK.row(ctx).apply { background = TopLine(LK.a(Color.WHITE, 0.14f)); setPadding(0, Ui.dpi(12f), 0, 0) }
        for ((k, f) in d.feats.withIndex()) {
            if (k > 0) feats.addView(View(ctx).apply { setBackgroundColor(LK.a(Color.WHITE, 0.28f)) }, LK.lp(Ui.dpi(1f), Ui.dpi(14f), l = 12f, r = 12f))
            feats.addView(WK.icoView(ctx, svg24(f.first), Color.WHITE, 13f), LK.lp(Ui.dpi(13f), Ui.dpi(13f), r = 6f))
            feats.addView(LK.one(LK.t(ctx, f.second, 12.5f, LK.a(Color.WHITE, 0.9f), true)))
        }
        col.addView(feats, LK.lp(-1, -2, t = 14f))
        return col
    }

    /** screen 1's picture: the ringing phone and the five feature pills (HTML on the web, views here) */
    private fun callsHero(ctx: Context): FrameLayout {
        val hero = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val phone = FrameLayout(ctx).apply { clipChildren = false; background = CssBox(LK.c("#1C1430"), 30f, shadows = listOf(Sh(0f, 14f, 28f, 0f, Color.argb(89, 10, 4, 30)))) }
        hero.addView(phone, LK.flp(Ui.dpi(158f), Ui.dpi(300f), Gravity.TOP or Gravity.START, l = 24f, t = 15f))
        val scr = LK.col(ctx, Gravity.CENTER_HORIZONTAL).apply { background = CssBox(LK.c("#2E1F5E"), 25f); setPadding(0, Ui.dpi(24f), 0, Ui.dpi(16f)) }
        phone.addView(scr, LK.flp(-1, -1, l = 6f, t = 6f, r = 6f, b = 6f))
        // "Incoming video call"
        val top = LK.row(ctx)
        top.addView(WK.icoView(ctx, svg24("""<rect x="3" y="7" width="12" height="10" rx="2.5" fill="rgba(255,255,255,0.75)"/><path d="M15 10.5l6-3v9l-6-3z" fill="rgba(255,255,255,0.75)"/>"""), Color.WHITE, 11f), LK.lp(Ui.dpi(11f), Ui.dpi(11f), r = 5f))
        top.addView(LK.t(ctx, "Incoming video call", 10.5f, LK.a(Color.WHITE, 0.75f)))
        scr.addView(top, LK.lp())
        // the caller: round picture with the green online dot
        val av = FrameLayout(ctx).apply { clipChildren = false }
        av.addView(ImageView(ctx).apply { setImageDrawable(SilDrawable(LK.c("#A78BFA"), Color.argb(242, 255, 255, 255))) }, FrameLayout.LayoutParams(-1, -1))
        av.addView(View(ctx).apply { background = CssBox(LK.green, 6f, 2f, LK.c("#2E1F5E")) }, LK.flp(Ui.dpi(12f), Ui.dpi(12f), Gravity.BOTTOM or Gravity.END, r = 2f, b = 2f))
        scr.addView(av, LK.lp(Ui.dpi(64f), Ui.dpi(64f), t = 14f))
        scr.addView(LK.t(ctx, "Aarohi", 15f, Color.WHITE, true), LK.lp(t = 10f))
        val ver = LK.row(ctx)
        val tick = FrameLayout(ctx).apply { background = CssBox(LK.green, 6f) }
        tick.addView(WK.icoView(ctx, svg24("""<path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="#FFFFFF" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>"""), Color.WHITE, 8f), FrameLayout.LayoutParams(Ui.dpi(8f), Ui.dpi(8f), Gravity.CENTER))
        ver.addView(tick, LK.lp(Ui.dpi(11f), Ui.dpi(11f), r = 4f))
        ver.addView(LK.t(ctx, "Verified · 2 km away", 10.5f, LK.a(Color.WHITE, 0.7f)))
        scr.addView(ver, LK.lp(t = 3f))
        // the voice wave
        val wave = LK.row(ctx, Gravity.CENTER)
        for ((k, h) in listOf(5, 9, 14, 20, 26, 16, 22, 10, 18, 7, 12, 6, 4).withIndex())
            wave.addView(View(ctx).apply { background = CssBox(LK.c("#C4B5FD"), 2f) }, LK.lp(Ui.dpi(3f), Ui.dpi(h.toFloat()), l = if (k == 0) 0f else 3f))
        scr.addView(wave, LK.lp(-2, Ui.dpi(26f), t = 16f))
        scr.addView(LK.t(ctx, "Ringing…", 10f, LK.a(Color.WHITE, 0.55f), ls = 0.3f), LK.lp(t = 6f))
        scr.addView(View(ctx), LK.lp(1, 0, weight = 1f))
        // Decline / Accept
        val btns = LK.row(ctx)
        val handset = svg24("""<path d="M6.6 10.8a15.3 15.3 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25c1.1.37 2.3.57 3.6.57a1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z" fill="#FFFFFF"/>""")
        for ((k, b) in listOf(Triple("#EF4444", 135f, "Decline"), Triple("#22C55E", 0f, "Accept")).withIndex()) {
            val c = LK.col(ctx, Gravity.CENTER_HORIZONTAL)
            val color = LK.c(b.first)
            val ball = FrameLayout(ctx).apply { clipChildren = false; background = CssBox(color, 21f, shadows = listOf(Sh(0f, 6f, 14f, 0f, LK.a(color, 0.4f)))) }
            ball.addView(WK.icoView(ctx, handset, Color.WHITE, 19f).apply { rotation = b.second }, FrameLayout.LayoutParams(Ui.dpi(19f), Ui.dpi(19f), Gravity.CENTER))
            c.addView(ball, LK.lp(Ui.dpi(42f), Ui.dpi(42f)))
            c.addView(LK.t(ctx, b.third, 9.5f, LK.a(Color.WHITE, 0.65f)), LK.lp(t = 5f))
            btns.addView(c, LK.lp(l = if (k == 0) 0f else 34f))
        }
        scr.addView(btns, LK.lp())
        // the feature pills
        class Pill(val x: Float, val y: Float, val rot: Float, val tint: String, val icon: String, val label: String, val live: Boolean = false)
        val pills = listOf(
            Pill(206f, 62f, -1.5f, "#FEE2E2", """<rect x="9" y="3" width="6" height="11" rx="3" fill="#DC2626"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4M9 21h6" fill="none" stroke="#DC2626" stroke-width="2" stroke-linecap="round"/>""", "Live Rooms", true),
            Pill(216f, 110f, 1f, "#FCE7F3", """<path d="M9 18V6l10-2v12" fill="none" stroke="#DB2777" stroke-width="2.2" stroke-linejoin="round"/><circle cx="6.5" cy="18" r="2.5" fill="#DB2777"/><circle cx="16.5" cy="16" r="2.5" fill="#DB2777"/>""", "Dance Rooms"),
            Pill(208f, 158f, -1f, "#EDE9FE", """<rect x="3" y="8" width="18" height="10" rx="5" fill="none" stroke="#7C3AED" stroke-width="2.2"/><path d="M8 11v4M6 13h4" fill="none" stroke="#7C3AED" stroke-width="2" stroke-linecap="round"/><circle cx="15.5" cy="12" r="1.2" fill="#7C3AED"/><circle cx="18" cy="14" r="1.2" fill="#7C3AED"/>""", "Games"),
            Pill(218f, 206f, 1.5f, "#FEF3C7", """<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1.1 5.9L12 16.9l-5.3 2.8 1.1-5.9L3.5 9.7l5.9-.8z" fill="#D97706"/>""", "Expert Talk"),
            Pill(206f, 254f, -1f, "#DCFCE7", """<path d="M4 5h16v11H9l-5 4z" fill="none" stroke="#16A34A" stroke-width="2.2" stroke-linejoin="round"/><path d="M8 9h8M8 12.5h5" fill="none" stroke="#16A34A" stroke-width="2" stroke-linecap="round"/>""", "Unlimited chat"),
        )
        for (p in pills) {
            val row = LK.row(ctx).apply {
                background = CssBox(Color.WHITE, 18f, shadows = listOf(Sh(0f, 8f, 20f, 0f, Color.argb(66, 10, 4, 30))))
                setPadding(Ui.dpi(6f), 0, Ui.dpi(13f), 0)
                rotation = p.rot
            }
            val ic = FrameLayout(ctx).apply { background = CssBox(LK.c(p.tint), 12f) }
            ic.addView(WK.icoView(ctx, svg24(p.icon), Color.BLACK, 13f), FrameLayout.LayoutParams(Ui.dpi(13f), Ui.dpi(13f), Gravity.CENTER))
            row.addView(ic, LK.lp(Ui.dpi(24f), Ui.dpi(24f), r = 8f))
            row.addView(LK.one(LK.t(ctx, p.label, 12.5f, LK.ink, true)))
            if (p.live) row.addView(View(ctx).apply { background = CssBox(LK.c("#EF4444"), 3f) }, LK.lp(Ui.dpi(6f), Ui.dpi(6f), l = 10f))
            hero.addView(row, LK.flp(-2, Ui.dpi(36f), Gravity.TOP or Gravity.START, l = p.x, t = p.y))
        }
        return hero
    }

    /** the white card: line, Get Started, and the rules links */
    private fun cardContent(ctx: Context): View {
        val col = LK.col(ctx).apply { setPadding(Ui.dpi(24f), Ui.dpi(18f), Ui.dpi(24f), Ui.dpi(14f)) }
        col.addView(LK.t(ctx, "Real people. Real conversations.", 16f, LK.ink, true, 1.2f))
        val btn = FrameLayout(ctx).apply {
            clipChildren = false
            background = CssBox(LK.purple, 24f, shadows = listOf(Sh(0f, 8f, 20f, 0f, LK.ca("#6C2BD9", 0.30f))))
            foreground = CssBox(Color.argb(18, 0, 0, 0), 24f).apply { alpha = 0 }
            contentDescription = "Get Started"
        }
        val in1 = LK.row(ctx, Gravity.CENTER)
        in1.addView(LK.t(ctx, "Get Started", 16f, Color.WHITE, true))
        in1.addView(WK.icoView(ctx, WK.ARROW, Color.WHITE, 16f), LK.lp(Ui.dpi(16f), Ui.dpi(16f), l = 8f))
        btn.addView(in1, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // :active { filter: brightness(.93) } = a 7 % black veil over the button
        btn.setOnTouchListener { v, e ->
            val f = v.foreground
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> ValueAnimator.ofInt(f.alpha, 255).apply { duration = 150; addUpdateListener { f.alpha = it.animatedValue as Int } }.start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ValueAnimator.ofInt(f.alpha, 0).apply { duration = 150; addUpdateListener { f.alpha = it.animatedValue as Int } }.start()
            }
            false
        }
        btn.setOnClickListener { if (live) page.go("login") }
        col.addView(btn, LK.lp(-1, Ui.dpi(48f), t = 12f))
        col.addView(View(ctx).apply { setBackgroundColor(LK.div) }, LK.lp(-1, Ui.dpi(1f), t = 12f, b = 8f))
        col.addView(WK.linked(ctx, 11.5f, LK.c("#6F6880"), LK.link, 1.4f,
            "By continuing I accept the " to null, "Community Guidelines" to { page.layer.openSite("/community-guidelines") }, ", " to null,
            "Terms of Use" to { page.layer.openSite("/terms") }, " & " to null, "Privacy Policy" to { page.layer.openSite("/privacy") }, "." to null), LK.lp(-1, -2))
        return col
    }

    private fun openInfo(what: String) {
        if (info) return
        info = true; plan()
        InfoSheet.open(page.layer, what) { info = false; plan() }
    }

    // ═════════════════ moving the screens ═════════════════
    private fun setTrack(x: Float) { track.translationX = x }

    private fun goTo(i: Int, ms: Long) {
        val k = i.coerceIn(0, 2)
        val d = if (reduce) 0L else ms
        pos = k; lastMs = d
        if (index != k) { index = k; setBackgroundColor(BG[k]); dots.set(k, !reduce) }
        anim?.cancel()
        val to = -k * w.toFloat()
        if (d <= 0L || w == 0) { setTrack(to); plan(); return }
        val from = track.translationX
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d; interpolator = EASE
            addUpdateListener { setTrack(from + (to - from) * (it.animatedValue as Float)) }
            start()
        }
        plan()
    }

    /** auto-slide: 1 → 2 → 3, then back to 1 (waits while a finger is on it, the rules are open or the app is away) */
    private val auto = Runnable {
        if (!live || holding || info || w == 0) return@Runnable
        if (!appShown) { plan(); return@Runnable }
        if (pos < 2) goTo(pos + 1, SLIDE_MS) else goTo(0, BACK_MS)
    }
    private fun plan() {
        main.removeCallbacks(auto)
        if (page.layer.preview.contains("still")) return // test pictures of one screen
        if (live && !holding && !info && w > 0) main.postDelayed(auto, lastMs + REST_MS)
    }

    // the finger
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var tracking = false; private var dragging = false; private var dx = 0f

    private fun touch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = live && !info && !(e.y >= card.top && e.y <= card.bottom)
                dragging = false; dx = 0f
                downX = e.x; downY = e.y; downT = SystemClock.uptimeMillis()
                return tracking
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val mx = (e.x - downX) / Ui.density; val my = (e.y - downY) / Ui.density
                if (!dragging) {
                    if (abs(my) > 14f && abs(my) > abs(mx)) { tracking = false; return false } // up/down, not a swipe
                    if (abs(mx) < 8f) return true
                    dragging = true; holding = true; plan()
                    anim?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                dx = e.x - downX
                val edge = (pos == 0 && dx > 0) || (pos == 2 && dx < 0) // nothing further: soft pull
                setTrack(-pos * w + (if (edge) dx * 0.3f else dx))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                if (!dragging) return true
                dragging = false; holding = false
                val v = (dx / Ui.density) / max(1L, SystemClock.uptimeMillis() - downT)
                var i = pos
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if ((dx < -w * 0.18f || v < -0.45f) && i < 2) i += 1
                    else if ((dx > w * 0.18f || v > 0.45f) && i > 0) i -= 1
                }
                goTo(i, SLIDE_MS)
                return true
            }
        }
        return tracking
    }

    override fun setLive(v: Boolean) { live = v; plan() }
    override fun appVisible(v: Boolean) { appShown = v; plan() }
    override fun dispose() { live = false; main.removeCallbacksAndMessages(null); anim?.cancel(); dots.stop() }
    override fun preview(variant: String, open: String) {
        when {
            variant.contains("2") -> goTo(1, 0)
            variant.contains("3") -> goTo(2, 0)
        }
        if (variant.contains("still")) { live = false; main.removeCallbacks(auto) }
        if (open == "rules" || open == "privacy") openInfo(open)
    }

    // ═════════════════ layout ═════════════════
    /** the page: the moving strip, the logo and dots above it, the white card at the bottom */
    private inner class Stage(ctx: Context) : ViewGroup(ctx) {
        init { clipChildren = true }
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val ww = MeasureSpec.getSize(wSpec); val hh = MeasureSpec.getSize(hSpec)
            setMeasuredDimension(ww, hh)
            if (ww <= 0 || hh <= 0) return
            val ns = max(0.4f, min(ww / Ui.dp(BW), hh / Ui.dp(BH)))
            if (ns != s || ww != w) {
                s = ns
                val first = w == 0
                w = ww
                anim?.cancel(); setTrack(-pos * w.toFloat())
                card.background = CssBox(Color.WHITE, corners = floatArrayOf(28f * s, 28f * s, 0f, 0f), shadows = listOf(Sh(0f, -10f * s, 30f * s, 0f, Color.argb(41, 0, 0, 0))))
                if (first) plan()
            }
            track.measure(MeasureSpec.makeMeasureSpec(ww * 3, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY))
            over.measure(0, 0)
            card.measure(MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((Ui.dp(CARD_H) * s).roundToInt(), MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val ww = r - l; val hh = b - t
            track.layout(0, 0, ww * 3, hh)
            place(over, ww, 0)
            val ch = card.measuredHeight
            card.layout(0, hh - ch, ww, hh)
            place(cardBoard, ww, 0)
        }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            // a swipe that starts on a screen is ours; taps on the card stay with its buttons
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { touch(e); return false }
            if (!tracking) return false
            touch(e)
            return dragging
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean = touch(e) || e.actionMasked == MotionEvent.ACTION_DOWN && tracking
    }

    /** puts a design board at the centre (left = (w − 390·s) / 2), scaled by s from its top-left corner */
    private fun place(b: Board, ww: Int, top: Int) {
        b.pivotX = 0f; b.pivotY = 0f; b.scaleX = s; b.scaleY = s
        val left = ((ww - Ui.dp(BW) * s) / 2f).roundToInt()
        b.layout(left, top, left + b.measuredWidth, top + b.measuredHeight)
    }

    /** the strip of three screens side by side (moved by translationX) */
    private inner class Track(ctx: Context) : ViewGroup(ctx) {
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val ww = MeasureSpec.getSize(wSpec); val hh = MeasureSpec.getSize(hSpec)
            setMeasuredDimension(ww, hh)
            for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(ww / 3, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val pw = (r - l) / 3
            for (i in 0 until childCount) getChildAt(i).layout(i * pw, 0, (i + 1) * pw, b - t)
        }
    }

    /** one screen: its colour, the light doodle wallpaper at the top, and its design board */
    @SuppressLint("ViewConstructor")
    private inner class Panel(ctx: Context, color: Int, doodle: String, val board: Board) : ViewGroup(ctx) {
        private val wall = WK.doodleView(ctx, doodle)
        init { setBackgroundColor(color); clipChildren = true; addView(wall); addView(board) }
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val ww = MeasureSpec.getSize(wSpec); val hh = MeasureSpec.getSize(hSpec)
            setMeasuredDimension(ww, hh)
            wall.measure(MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            board.measure(0, 0)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            wall.layout(0, 0, wall.measuredWidth, wall.measuredHeight)
            place(board, r - l, 0)
        }
    }
}

/** A box of a fixed design size (dp), whatever room its parent gives (it is scaled from outside). */
@SuppressLint("ViewConstructor")
internal class Board(ctx: Context, private val wDp: Float, private val hDp: Float) : FrameLayout(ctx) {
    init { clipChildren = false; clipToPadding = false }
    override fun onMeasure(wSpec: Int, hSpec: Int) =
        super.onMeasure(MeasureSpec.makeMeasureSpec(Ui.dpi(wDp), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ui.dpi(hDp), MeasureSpec.EXACTLY))
}

/** The page dots: they stay in place; the long dot moves to the screen being shown (.45 s). */
@SuppressLint("ViewConstructor")
internal class Dots(ctx: Context, private val n: Int) : View(ctx) {
    private val k = FloatArray(n)          // 0 = small dot, 1 = the long white one
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    fun set(i: Int, animate: Boolean) {
        anim?.cancel()
        val from = k.copyOf()
        if (!animate) { for (j in 0 until n) k[j] = if (j == i) 1f else 0f; requestLayout(); invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450; interpolator = ease
            addUpdateListener { a -> val t = a.animatedValue as Float; for (j in 0 until n) k[j] = from[j] + ((if (j == i) 1f else 0f) - from[j]) * t; invalidate() }
            start()
        }
    }
    fun stop() { anim?.cancel() }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        // n dots of 6, one of them 18 wide, 5 apart
        setMeasuredDimension(Ui.dpi(6f * n + 12f + 5f * (n - 1)), MeasureSpec.getSize(hSpec))
    }
    override fun onDraw(c: Canvas) {
        var x = width.toFloat()
        val cy = height / 2f
        val r = Ui.dp(3f)
        for (j in n - 1 downTo 0) {
            val ww = Ui.dp(6f + 12f * k[j])
            p.color = blend(Color.argb(102, 255, 255, 255), Color.WHITE, k[j])
            c.drawRoundRect(x - ww, cy - r, x, cy + r, r, r, p)
            x -= ww + Ui.dp(5f)
        }
    }
    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).roundToInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt())
}

/**
 * A Get Started picture made of SVG layers (some with the web's drop shadow) and words. The layers are
 * painted once, at the size the picture really has on screen ([scale] = how much it is scaled from
 * outside), off the main thread; the words are drawn on top in the app's Calibri-style font.
 */
@SuppressLint("ViewConstructor")
internal class ArtView(ctx: Context, private val art: IntroArt.Art, private val scale: () -> Float) : View(ctx) {
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var asked = ""
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        val k = scale().coerceAtLeast(0.1f)
        val bw = (width * k).roundToInt(); val bh = (height * k).roundToInt()
        val key = "${System.identityHashCode(art)}|$bw|$bh"
        val b = Cache.get(key)
        if (b == null) {
            if (asked != key) { asked = key; Cache.paint(art, bw, bh, key) { alpha = 0f; invalidate(); animate().alpha(1f).setDuration(160).start() } }
            return
        }
        c.save(); c.scale(width.toFloat() / b.width, height.toFloat() / b.height); c.drawBitmap(b, 0f, 0f, bp); c.restore()
        val f = width / 390f
        for (t in art.texts) {
            tp.typeface = if (t.bold) Ui.bold else Ui.regular
            tp.textSize = t.size * f
            tp.color = t.color
            tp.textAlign = if (t.center) Paint.Align.CENTER else Paint.Align.LEFT
            c.save()
            if (t.rot != 0f) c.rotate(t.rot, t.cx * f, t.cy * f)
            c.drawText(t.s, t.x * f, t.y * f, tp)
            c.restore()
        }
    }

    private object Cache {
        private val pics = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
        private val worker = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        fun get(key: String): Bitmap? = pics.get(key)
        fun paint(art: IntroArt.Art, w: Int, h: Int, key: String, done: () -> Unit) {
            worker.execute {
                val out = try { draw(art, w, h) } catch (e: Throwable) { null }
                main.post { if (out != null) { pics.put(key, out); done() } }
            }
        }
        private fun draw(art: IntroArt.Art, w: Int, h: Int): Bitmap {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val sc = w / 390f
            for (part in art.parts) {
                val svg = try { SVG.getFromString(part.svg) } catch (e: Throwable) { null } ?: continue
                val opts = RenderOptions.create().viewPort(0f, 0f, w.toFloat(), h.toFloat())
                if (!part.shadow) { svg.renderToCanvas(c, opts); continue }
                // feDropShadow: the layer's shape, blurred (stdDeviation), moved (dx, dy), in the flood colour, under the layer
                val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(Canvas(layer), opts)
                val sigma = art.std * sc
                val blur = Paint().apply { maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL) }
                val off = IntArray(2)
                val a = layer.extractAlpha(blur, off)
                c.drawBitmap(a, off[0] + art.dx * sc, off[1] + art.dy * sc, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = art.shadowColor })
                c.drawBitmap(layer, 0f, 0f, null)
                a.recycle(); layer.recycle()
            }
            return out
        }
    }
}
