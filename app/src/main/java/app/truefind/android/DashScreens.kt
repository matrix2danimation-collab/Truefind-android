package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared look of My Dashboard and Secret Dating Earning (390 px design, zoomed to the phone like the web). */
object DashK {
    val INK = Ui.c("#1F1235"); val SUB = Ui.c("#6B6478"); val BODY = Ui.c("#4B4458"); val PURPLE = Ui.c("#6C2BD9"); val DEEP = Ui.c("#4A1D96")
    val LINE = Ui.c("#E9E0FB"); val SOFT = Ui.c("#EEE9F5"); val TINT = Ui.c("#F4EFFC"); val PAGE = Ui.c("#FAF8FD")
    val PINK = Ui.c("#DB2777"); val PINK_BG = Ui.c("#FCE7F3"); val GREEN = Ui.c("#047857"); val GREEN_BG = Ui.c("#D1FAE5"); val AMBER = Ui.c("#92400E"); val AMBER_BG = Ui.c("#FEF3C7")

    fun num(v: Any?): Double = when (v) { is Number -> v.toDouble().takeIf { it.isFinite() } ?: 0.0; is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0; else -> 0.0 }
    private fun inFmt(max: Int) = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = max }
    /** 28450 → "28,450" · 22.5 → "22.5" (small amounts keep their paise; big ones are never rounded up) */
    fun plain(v: Any?): String { val n = num(v); return if (abs(n) >= 100) inFmt(0).format(floor(n)) else inFmt(2).format(Math.round(n * 100) / 100.0) }
    fun inr(v: Any?) = "₹ ${plain(v)}"

    private const val ST = "fill=\"none\" stroke=\"#6C2BD9\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\""
    val ICON_CALL = "<svg viewBox=\"0 0 24 24\"><path d=\"M6.5 3.5h3l1.5 4-2 1.5a11 11 0 0 0 6 6l1.5-2 4 1.5v3a2 2 0 0 1-2 2A15.5 15.5 0 0 1 4.5 5.5a2 2 0 0 1 2-2z\" $ST/></svg>"
    val ICON_VIDEO = "<svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"6.5\" width=\"12.5\" height=\"11\" rx=\"2.5\" $ST/><path d=\"M15.5 10.5l5-3v9l-5-3\" $ST/></svg>"
    val ICON_TIME = "<svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"8.5\" $ST/><path d=\"M12 7.5V12l3 2\" $ST/></svg>"
    val ICON_GIFT = "<svg viewBox=\"0 0 24 24\"><rect x=\"4\" y=\"10.5\" width=\"16\" height=\"9.5\" rx=\"1.5\" $ST/><path d=\"M3 7.5h18v3H3zM12 7.5V20M12 7.5c-1.5-4-6-3.5-5-1 .5 1 2.5 1 5 1zm0 0c1.5-4 6-3.5 5-1-.5 1-2.5 1-5 1z\" $ST/></svg>"


    /** stroke style of Secret Dating Earning's icons */
    const val SE = "fill=\"none\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\""

    /** zoomed text */
    fun t(ctx: Context, m: Zm, s: CharSequence, size: Float, color: Int, bold: Boolean = false, lh: Float = 0f): TextView = EtText(ctx).apply {
        text = s; includeFontPadding = false
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m.px(size)); setTextColor(color); typeface = if (bold) Ui.bold else Ui.regular
        lh(if (lh > 0f) lh / size else 1.2f)
    }
    fun one(v: TextView): TextView { v.maxLines = 1; v.isSingleLine = true; v.ellipsize = TextUtils.TruncateAt.END; return v }
    fun box(m: Zm, color: Int, r: Float, line: Int = 0): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = m.px(r); if (line != 0) setStroke(maxOf(1, m.pi(1f)), line) }
    fun circ(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    fun row(ctx: Context, m: Zm, gap: Float, g: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = g; if (gap > 0) { dividerDrawable = EtGap(m.pi(gap)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE } }
    fun col(ctx: Context, m: Zm, gap: Float, g: Int = Gravity.START) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = g; if (gap > 0) { dividerDrawable = EtGap(m.pi(gap)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE } }
    fun lp(m: Zm, w: Float, h: Float, weight: Float = 0f) = LinearLayout.LayoutParams(if (w < 0) w.toInt() else m.pi(w), if (h < 0) h.toInt() else m.pi(h), weight)
    /** an inline SVG drawn at a zoomed size */
    fun svg(ctx: Context, m: Zm, markup: String, w: Float, h: Float = w): View = SvgView(ctx, Svg.of(markup)).apply { layoutParams = lp(m, w, h) }

    /** the round back button of these pages (44, white, soft line, chevron) */
    fun back(ctx: Context, m: Zm, onClick: () -> Unit): View = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(maxOf(1, m.pi(1f)), SOFT) }
        addView(svg(ctx, m, "<svg viewBox=\"0 0 24 24\"><path d=\"M14.5 5l-7 7 7 7\" fill=\"none\" stroke=\"#1F1235\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>", 20f), FrameLayout.LayoutParams(m.pi(20f), m.pi(20f), Gravity.CENTER))
        contentDescription = "Back"
        setOnClickListener { onClick() }
        Ui.pressable(this, .96f)
    }

    /** header: back · title (centred) · space */
    fun header(ctx: Context, m: Zm, title: String, size: Float, onBack: () -> Unit): View {
        val r = row(ctx, m, 0f)
        r.addView(FrameLayout(ctx).apply { addView(back(ctx, m, onBack), FrameLayout.LayoutParams(m.pi(44f), m.pi(44f), Gravity.START or Gravity.CENTER_VERTICAL)) }, lp(m, 0f, 44f, 1f))
        r.addView(t(ctx, m, title, size, INK, true).apply { maxLines = 1; letterSpacing = if (size >= 20f) -.01f else 0f })
        r.addView(View(ctx), lp(m, 0f, 44f, 1f))
        return r
    }

    /** the light shimmer of a loading number (.tfd-sk / -dark) */
    fun skel(ctx: Context, dark: Boolean, r: Float = 8f) = EtSkel(ctx, r, if (dark) Ui.c("#5B2BB0") else Ui.c("#EFE7FB"), if (dark) Ui.c("#6E41C2") else Ui.c("#F8F4FE"))

    /** the white three-part box (This Week · This Month · All Time) */
    fun three(ctx: Context, m: Zm, items: List<Pair<String, String>>, loading: Boolean, cellBg: Int = Color.WHITE, lineColor: Int = SOFT, radius: Float = 14f, labelOneLine: Boolean = true, valueSize: Float = 16f): View {
        val g = EtGrid(ctx, items.size, 0).apply { background = box(m, cellBg, radius); setPadding(0, m.pi(if (valueSize >= 16f) 12f else 10f), 0, m.pi(if (valueSize >= 16f) 12f else 10f)) }
        items.forEachIndexed { i, (lab, v) ->
            val c = col(ctx, m, 2f).apply {
                setPadding(m.pi(12f), 0, m.pi(6f), 0)
                if (i > 0) background = LeftLine(lineColor)
            }
            c.addView(t(ctx, m, lab, 11.5f, SUB).let { if (labelOneLine) one(it) else it })
            if (loading) c.addView(skel(ctx, false), lp(m, 34f, 19f))
            else c.addView(one(t(ctx, m, v, valueSize, INK, true)))
            g.addView(c)
        }
        return g
    }
}

/** the zoom of a 390-px design on this phone (max 480) */
fun dashZoom(ctx: Context): Zm { val dm = ctx.resources.displayMetrics; return Zm(min(dm.widthPixels / dm.density, 480f) / 390f) }

// ═══════════════════════════ My Dashboard ═══════════════════════════
/**
 * True Find · My Dashboard (src/pages/Dashboard.jsx), native. The signed-in person's own numbers:
 * photo and name, calls, video calls, talk time, gifts, money, and a graph for the week / month / all.
 * A Creator / Host sees EARNINGS (rupees); a member sees only calls, talk time and the Secret Dating plan.
 * It always opens (the last numbers, or the page outline while the first answer comes) and updates
 * itself: when one of my calls starts / ends or my wallet changes, every 30 s, and when the app comes back.
 */
@SuppressLint("ViewConstructor")
class DashboardScreen(ctx: Context, val pages: NativePages) : FrameLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private val m = dashZoom(ctx)
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
    private val column = DashK.col(ctx, m, 14f)

    private var d: JSONObject? = null
    private var shownJson = ""
    private var err = ""
    private var errorAction = ""
    private var busy = false
    private var retrying = false
    private var retryN = 0
    private var tab = "week"
    private var shown = false
    private var appVisible = true
    private var inFlight = false
    private var again = false
    private var meId = ""
    private val unsubs = ArrayList<() -> Unit>()

    private val retryRun = Runnable { if (shown && appVisible) load() else main.postDelayed({ if (shown) load() }, 5000) }
    private val soon = Runnable { refresh() }
    private val later = Runnable { refresh() }
    private val tick = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 30_000) } }

    init {
        setBackgroundColor(DashK.PAGE)
        isClickable = true
        val wrap = FrameLayout(ctx)
        column.setPadding(m.pi(16f), m.pi(14f), m.pi(16f), m.pi(24f))
        wrap.addView(column, FrameLayout.LayoutParams(m.pi(390f), -2, Gravity.CENTER_HORIZONTAL))
        scroll.addView(wrap, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
    }

    fun setShown(on: Boolean) {
        val was = shown
        shown = on
        visibility = if (on) VISIBLE else GONE
        if (on && !was) {
            render()
            if (!pages.signedIn) return
            load()
            listen()
            main.removeCallbacks(tick); main.postDelayed(tick, 30_000)
        } else if (!on && was) stopListening()
        if (!pages.signedIn && d != null) { d = null; render() }
    }

    fun onAppVisible(v: Boolean) { appVisible = v; if (v && shown) refresh() }

    /** test mode: "month" / "all" (the graph's period), "bottom" (scrolled down) */
    fun previewOpen(what: String) {
        when (what) { "month", "all" -> { tab = what; render() }; "bottom" -> scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun stopListening() {
        unsubs.forEach { try { it() } catch (e: Exception) { } }
        unsubs.clear()
        main.removeCallbacks(tick); main.removeCallbacks(soon); main.removeCallbacks(later); main.removeCallbacks(retryRun)
    }

    /** live numbers without pulling to refresh: my calls, my wallet and its entries */
    private fun listen() {
        if (unsubs.isNotEmpty() || Demo.on) return
        val bump = {
            main.removeCallbacks(soon); main.removeCallbacks(later)
            main.postDelayed(soon, 1500)   // first look
            main.postDelayed(later, 6000)  // once the call is fully settled
        }
        Api.me { r ->
            if (!shown || unsubs.isNotEmpty()) return@me
            meId = r.data.optString("id")
            if (meId.isEmpty()) return@me
            unsubs.add(Realtime.subscribe("Call") { ev -> val c = ev.data ?: return@subscribe; if (c.optString("caller_id") == meId || c.optString("callee_id") == meId) bump() })
            unsubs.add(Realtime.subscribe("Wallet") { ev -> if (ev.data?.optString("user_id") == meId) bump() })
            unsubs.add(Realtime.subscribe("WalletTransaction") { ev -> val t = ev.data ?: return@subscribe; if (t.optString("user_id") == meId || t.optString("receiver_id") == meId) bump() })
        }
    }

    private fun refresh() {
        if (!shown || !appVisible) return
        if (inFlight) { again = true; return }
        load()
    }

    private fun normalize(v: JSONObject?): JSONObject? {
        if (v == null) return null
        val role = v.optString("role")
        if (role != "user" && role != "creator") return null
        if (v.optJSONObject("profile") == null || v.optJSONObject("stats") == null || v.optJSONObject("money") == null) return null
        val s = v.optJSONObject("series") ?: JSONObject()
        fun ok(a: JSONArray?): Boolean = a != null && (0 until a.length()).all { i -> val p = a.optJSONObject(i); p != null && p.opt("l") is String && p.opt("v") is Number && (p.opt("v") as Number).toDouble().isFinite() }
        fun blank(n: Int) = JSONArray().apply { repeat(n) { put(JSONObject().put("l", "").put("v", 0)) } }
        val out = JSONObject(v.toString())
        out.put("series", JSONObject().put("week", if (ok(s.optJSONArray("week"))) s.optJSONArray("week") else blank(7))
            .put("month", if (ok(s.optJSONArray("month"))) s.optJSONArray("month") else blank(30)).put("all", if (ok(s.optJSONArray("all"))) s.optJSONArray("all") else blank(6)))
        return out
    }

    private fun load() {
        main.removeCallbacks(retryRun)
        inFlight = true
        busy = true
        fun attempt(i: Int) {
            Api.invoke("getDashboard", JSONObject(), 30000) { r ->
                if (!r.ok && r.status != 401 && r.status != 403 && i < 2) { main.postDelayed({ attempt(i + 1) }, (if (r.status == 429) 2000L else 1200L) * (i + 1)); return@invoke }
                finish(r)
            }
        }
        attempt(0)
    }

    private fun finish(r: Api.Result) {
        busy = false
        val n = if (r.ok) normalize(r.data) else null
        if (n != null) {
            d = n; err = ""; errorAction = ""; retrying = false; retryN = 0
        } else {
            val code = r.data.optString("code")
            val action = if (r.status == 401) "/login?returnTo=%2Fdashboard" else if (code == "profile_required") "/welcome" else ""
            if (action.isNotEmpty()) {
                d = null; errorAction = action
                err = if (r.status == 401) "Please sign in again to open your dashboard." else Tf.err(r, "Could not load your dashboard. Please try again.")
            } else {
                // busy / slow / no internet: keep the page and try again by itself
                retrying = true
                val wait = listOf(4000L, 8000L, 15000L, 30000L)[min(retryN, 3)]
                retryN++
                main.postDelayed(retryRun, wait)
            }
        }
        render()
        inFlight = false
        if (again) { again = false; refresh() }
    }

    private fun go(path: String) = pages.go(path)

    fun render() {
        val ctx = context
        val key = "${d?.toString()}|$tab|$err|$errorAction|$retrying"
        if (key == shownJson && column.childCount > 0) return
        shownJson = key
        val y = scroll.scrollY
        column.removeAllViews()
        column.addView(DashK.header(ctx, m, "My Dashboard", 20f) { pages.historyBack() }, DashK.lp(m, -1f, 44f))
        if (err.isNotEmpty() && errorAction.isNotEmpty()) {
            val c = DashK.col(ctx, m, 12f, Gravity.CENTER_HORIZONTAL).apply { background = DashK.box(m, Color.WHITE, 20f, DashK.LINE); setPadding(m.pi(18f), m.pi(22f), m.pi(18f), m.pi(22f)) }
            c.addView(DashK.t(ctx, m, err, 15f, DashK.BODY, lh = 21f).apply { gravity = Gravity.CENTER })
            c.addView(DashK.t(ctx, m, if (busy) "Loading…" else if (errorAction.startsWith("/login")) "Sign in again" else "Finish profile", 14.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = DashK.box(m, DashK.PURPLE, 22f); setPadding(m.pi(22f), 0, m.pi(22f), 0)
                alpha = if (busy) .6f else 1f
                setOnClickListener { pages.js("location.assign(${JSONObject.quote(errorAction)})") {} }
            }, DashK.lp(m, -2f, 44f))
            column.addView(c, DashK.lp(m, -1f, -2f))
            return
        }
        val data = d
        val loading = data == null
        val creator = data?.optString("role") == "creator"
        if (retrying) column.addView(DashK.row(ctx, m, 8f, Gravity.CENTER).apply {
            addView(TfLoader(ctx, 14f * m.z), LinearLayout.LayoutParams(m.pi(14f), m.pi(14f)))
            addView(DashK.t(ctx, m, if (data != null) "Updating your numbers…" else "Loading your numbers…", 12.5f, DashK.SUB))
        }, DashK.lp(m, -1f, -2f))
        column.addView(meCard(data, loading, creator), DashK.lp(m, -1f, -2f))
        column.addView(stats(data, loading, creator), DashK.lp(m, -1f, -2f))
        if (!loading && !creator) column.addView(planCard(data!!), DashK.lp(m, -1f, -2f))
        if (loading || creator) {
            column.addView(moneyCard(data, loading, creator), DashK.lp(m, -1f, -2f))
            if (creator) column.addView(sdRow(), DashK.lp(m, -1f, -2f))
            column.addView(chartCard(data, loading, creator), DashK.lp(m, -1f, -2f))
        }
        if (!loading && creator) column.addView(DashK.t(ctx, m, "Earnings are counted when a call, gift or game is settled. To take money out, open Wallet → Earnings.${if (data!!.optBoolean("capped")) " Very old activity may not be shown in the graph." else ""}", 12f, DashK.SUB, lh = 17.4f).apply { gravity = Gravity.CENTER }, DashK.lp(m, -1f, -2f).apply { setMargins(m.pi(4f), 0, m.pi(4f), 0) })
        if (!loading && data!!.optBoolean("partial") && err.isEmpty()) column.addView(DashK.t(ctx, m, "Some numbers could not load right now. Open again in a moment to refresh.", 12f, DashK.SUB).apply { gravity = Gravity.CENTER }, DashK.lp(m, -1f, -2f))
        if (err.isNotEmpty() && data != null) column.addView(DashK.t(ctx, m, "Could not refresh just now. Showing the last numbers.", 12f, Ui.c("#B91C1C")).apply { gravity = Gravity.CENTER }, DashK.lp(m, -1f, -2f))
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun monthYear(iso: String): String { val t = Tf.ms(iso, plainUtc = true); return if (t <= 0) "" else java.text.SimpleDateFormat("MMM yyyy", Locale("en", "IN")).format(java.util.Date(t)) }

    private fun meCard(data: JSONObject?, loading: Boolean, creator: Boolean): View {
        val ctx = context
        val p = data?.optJSONObject("profile")
        val c = DashK.row(ctx, m, 14f).apply { background = DashK.box(m, Color.WHITE, 20f, DashK.LINE); setPadding(m.pi(16f), m.pi(16f), m.pi(16f), m.pi(16f)) }
        val ph = FrameLayout(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.c("#DBEAFE")); setStroke(m.pi(2f), DashK.PURPLE) } }
        if (!loading) ph.addView(EtPhoto(ctx, 60f * m.z).apply { set(Et.s(p, "photo")) }, FrameLayout.LayoutParams(m.pi(60f), m.pi(60f), Gravity.CENTER))
        c.addView(ph, DashK.lp(m, 64f, 64f))
        val col = DashK.col(ctx, m, 3f)
        val nm = DashK.row(ctx, m, 6f)
        if (loading) nm.addView(DashK.skel(ctx, false), DashK.lp(m, 120f, 23f))
        else {
            nm.addView(DashK.one(DashK.t(ctx, m, Et.s(p, "name").ifEmpty { "You" }, 19f, DashK.INK, true)), LinearLayout.LayoutParams(-2, -2, 0f).apply { weight = 0f })
            if (p?.optBoolean("verified") == true) nm.addView(DashK.svg(ctx, m, "<svg viewBox=\"0 0 18 18\"><circle cx=\"9\" cy=\"9\" r=\"8\" fill=\"#6C2BD9\"/><path d=\"M5.5 9.2l2.4 2.4 4.6-5\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"1.9\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>", 17f).apply { contentDescription = "Verified" })
        }
        col.addView(nm)
        val joined = monthYear(Et.s(p, "joined"))
        col.addView(DashK.t(ctx, m, if (loading) " " else if (joined.isNotEmpty()) "Member since $joined" else "True Find member", 12.5f, DashK.SUB))
        if (loading) col.addView(View(ctx), DashK.lp(m, 1f, 22f).apply { topMargin = m.pi(3f) })
        else col.addView(DashK.row(ctx, m, 6f).apply {
            addView(pill(Et.s(p, "label").ifEmpty { "Member" }, if (creator) DashK.AMBER_BG else Ui.c("#EBE2FD"), if (creator) DashK.AMBER else Ui.c("#5B21B6")))
            if (p?.optBoolean("verified") == true) addView(pill("Verified", Ui.c("#EBE2FD"), Ui.c("#5B21B6")))
        }, DashK.lp(m, -2f, -2f).apply { topMargin = m.pi(3f) })
        c.addView(col, DashK.lp(m, 0f, -2f, 1f))
        return c
    }
    private fun pill(s: String, bg: Int, fg: Int): View = DashK.t(context, m, s, 12f, fg, true).apply {
        gravity = Gravity.CENTER; background = DashK.box(m, bg, 11f); setPadding(m.pi(9f), 0, m.pi(9f), 0); maxLines = 1
        layoutParams = LinearLayout.LayoutParams(-2, m.pi(22f))
    }

    private fun talkTime(sec: Double): String { val mm = floor(sec / 60).toLong(); return if (mm >= 60) "${mm / 60}h ${mm % 60}m" else "${mm}m" }

    private fun stats(data: JSONObject?, loading: Boolean, creator: Boolean): View {
        val ctx = context
        val st = data?.optJSONObject("stats")
        val n = if (loading || creator) 4 else 3
        val g = EtGrid(ctx, n, m.pi(8f))
        val items = mutableListOf(Triple(DashK.ICON_CALL, DashK.plain(st?.opt("voice_calls")), "Calls"), Triple(DashK.ICON_VIDEO, DashK.plain(st?.opt("video_calls")), "Video"),
            Triple(DashK.ICON_TIME, talkTime(DashK.num(st?.opt("talk_seconds"))), "Talk time"))
        if (loading || creator) items.add(Triple(DashK.ICON_GIFT, DashK.plain(st?.opt("gifts")), "Gifts"))
        for ((ic, v, l) in items) {
            val c = DashK.col(ctx, m, 2f, Gravity.CENTER).apply { background = DashK.box(m, Color.WHITE, 16f, DashK.LINE) }
            c.addView(DashK.svg(ctx, m, ic, 22f))
            if (loading) c.addView(DashK.skel(ctx, false), DashK.lp(m, 34f, 21f)) else c.addView(DashK.t(ctx, m, v, 17f, DashK.INK, true, 21f).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
            c.addView(DashK.t(ctx, m, l, 11.5f, DashK.SUB, lh = 14f).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
            g.addView(c, android.view.ViewGroup.LayoutParams(-1, m.pi(84f)))
        }
        return g
    }

    private fun planUntil(iso: String): String { val t = Tf.ms(iso, plainUtc = true); return if (t <= 0) "" else Et.dayTime(t) }

    private fun planCard(data: JSONObject): View {
        val ctx = context
        val sec = data.optJSONObject("secret")
        val on = sec?.optBoolean("active") == true
        val c = DashK.row(ctx, m, 12f).apply {
            background = DashK.box(m, Color.WHITE, 18f, DashK.LINE); setPadding(m.pi(16f), m.pi(14f), m.pi(16f), m.pi(14f))
            setOnClickListener { go("/secret-app") }
            Ui.pressable(this, .98f)
        }
        val color = if (on) "#047857" else "#DB2777"
        c.addView(FrameLayout(ctx).apply { background = DashK.circ(if (on) DashK.GREEN_BG else DashK.PINK_BG); addView(DashK.svg(ctx, m, heart(color), 22f), FrameLayout.LayoutParams(m.pi(22f), m.pi(22f), Gravity.CENTER)) }, DashK.lp(m, 40f, 40f))
        c.addView(DashK.col(ctx, m, 2f).apply {
            addView(DashK.t(ctx, m, "Secret Dating plan", 16f, DashK.INK, true))
            addView(DashK.t(ctx, m, if (on) "Active till ${planUntil(Et.s(sec, "until"))}" else "No active plan", 12.5f, DashK.SUB))
        }, DashK.lp(m, 0f, -2f, 1f))
        c.addView(pill(if (on) "Active" else "Get plan", if (on) DashK.GREEN_BG else DashK.PINK_BG, if (on) DashK.GREEN else DashK.PINK).apply { layoutParams = LinearLayout.LayoutParams(-2, m.pi(24f)); background = DashK.box(m, if (on) DashK.GREEN_BG else DashK.PINK_BG, 12f); setPadding(m.pi(10f), 0, m.pi(10f), 0) })
        return c
    }
    private fun heart(c: String) = "<svg viewBox=\"0 0 24 24\"><path d=\"M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z\" fill=\"none\" stroke=\"$c\" stroke-width=\"2\" stroke-linejoin=\"round\"/></svg>"

    private fun moneyCard(data: JSONObject?, loading: Boolean, creator: Boolean): View {
        val ctx = context
        val money = data?.optJSONObject("money")
        val c = DashK.col(ctx, m, 0f).apply { background = DashK.box(m, DashK.DEEP, 20f); setPadding(m.pi(16f), m.pi(16f), m.pi(16f), m.pi(16f)) }
        val top = DashK.row(ctx, m, 8f).apply { minimumHeight = m.pi(24f) }
        top.addView(DashK.t(ctx, m, if (loading) " " else if (creator) "Total Earnings" else "Coins Spent", 13.5f, Ui.c("#E4D9F7")), DashK.lp(m, 0f, -2f, 1f))
        val pct = money?.opt("change_pct")
        if (pct is Number) {
            val p = pct.toDouble()
            val txt = "${if (p > 0) "+" else if (p < 0) "−" else ""}${Et.num(abs(p))}% vs last week"
            top.addView(DashK.t(ctx, m, txt, 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = DashK.box(m, Ui.c("#5F33AE"), 12f); setPadding(m.pi(9f), 0, m.pi(9f), 0); maxLines = 1 }, DashK.lp(m, -2f, 24f))
        }
        c.addView(top, DashK.lp(m, -1f, -2f))
        val money0 = { v: Any? -> if (creator) DashK.inr(v) else "${DashK.plain(v)} coins" }
        if (loading) c.addView(DashK.skel(ctx, true), DashK.lp(m, 150f, 40f).apply { topMargin = m.pi(2f) })
        else c.addView(DashK.t(ctx, m, money0(money?.opt("total")), 32f, Color.WHITE, true, 40f).apply { letterSpacing = -.0125f }, DashK.lp(m, -2f, -2f).apply { topMargin = m.pi(2f) })
        val items = listOf("This Week" to money?.opt("week"), "This Month" to money?.opt("month"), "All Time" to money?.opt("total")).map { (l, v) -> l to (if (creator) DashK.inr(v) else DashK.plain(v)) }
        c.addView(DashK.three(ctx, m, items, loading), DashK.lp(m, -1f, -2f).apply { topMargin = m.pi(12f) })
        return c
    }

    private fun sdRow(): View {
        val ctx = context
        val c = DashK.row(ctx, m, 12f).apply {
            background = DashK.box(m, Color.WHITE, 18f, DashK.LINE); setPadding(m.pi(16f), m.pi(14f), m.pi(16f), m.pi(14f))
            setOnClickListener { go("/secret-earnings") }
            Ui.pressable(this, .98f)
        }
        c.addView(FrameLayout(ctx).apply { background = DashK.circ(DashK.PINK_BG); addView(DashK.svg(ctx, m, heart("#DB2777"), 22f), FrameLayout.LayoutParams(m.pi(22f), m.pi(22f), Gravity.CENTER)) }, DashK.lp(m, 40f, 40f))
        c.addView(DashK.col(ctx, m, 2f).apply {
            addView(DashK.t(ctx, m, "Secret Dating Earning", 16f, DashK.INK, true))
            addView(DashK.t(ctx, m, "Like bonus, renewals, gifts", 12.5f, DashK.SUB))
        }, DashK.lp(m, 0f, -2f, 1f))
        c.addView(DashK.svg(ctx, m, "<svg viewBox=\"0 0 24 24\"><path d=\"M9.5 5l7 7-7 7\" fill=\"none\" stroke=\"#6B6478\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>", 18f))
        return c
    }

    private fun chartCard(data: JSONObject?, loading: Boolean, creator: Boolean): View {
        val ctx = context
        val c = DashK.col(ctx, m, 10f).apply { background = DashK.box(m, Color.WHITE, 20f, DashK.LINE); setPadding(m.pi(16f), m.pi(16f), m.pi(16f), m.pi(16f)) }
        val money = data?.optJSONObject("money")
        val title = if (creator) "Earnings Overview" else "Spending Overview"
        val period = mapOf("week" to "Last 7 days", "month" to "Last 30 days", "all" to "All time")
        val sum = DashK.num(when (tab) { "week" -> money?.opt("week"); "month" -> money?.opt("month"); else -> money?.opt("total") })
        val moneyTxt = if (creator) DashK.inr(sum) else "${DashK.plain(sum)} coins"
        val head = DashK.row(ctx, m, 8f)
        head.addView(DashK.col(ctx, m, 1f).apply {
            addView(DashK.t(ctx, m, if (loading) "Overview" else title, 16f, DashK.INK, true).apply { maxLines = 1 })
            addView(DashK.one(DashK.t(ctx, m, if (loading) " " else "${period[tab]}: $moneyTxt", 12f, DashK.SUB)))
        }, DashK.lp(m, 0f, -2f, 1f))
        val seg = DashK.row(ctx, m, 0f).apply { background = DashK.box(m, DashK.TINT, 18f); setPadding(m.pi(3f), m.pi(3f), m.pi(3f), m.pi(3f)) }
        for ((k, l) in listOf("week" to "Week", "month" to "Month", "all" to "All")) {
            val on = tab == k
            seg.addView(DashK.t(ctx, m, l, 12.5f, if (on) Color.WHITE else DashK.BODY, true).apply {
                gravity = Gravity.CENTER; setPadding(m.pi(11f), 0, m.pi(11f), 0)
                background = if (on) DashK.box(m, DashK.PURPLE, 15f) else null
                isSelected = on
                setOnClickListener { tab = k; render() }
            }, DashK.lp(m, -2f, 30f))
        }
        head.addView(seg, DashK.lp(m, -2f, 36f))
        c.addView(head, DashK.lp(m, -1f, -2f))
        val area = FrameLayout(ctx)
        val pts = Et.objs(data?.optJSONObject("series")?.optJSONArray(tab)).ifEmpty { (0 until 7).map { JSONObject().put("l", "").put("v", 0) } }
        if (loading) area.addView(DashK.skel(ctx, false, 12f), FrameLayout.LayoutParams(m.pi(326f), m.pi(124f)))
        else area.addView(DashChart(ctx, m, pts.map { DashK.num(it.opt("v")) }), FrameLayout.LayoutParams(m.pi(326f), m.pi(124f)))
        val empty = !loading && pts.none { DashK.num(it.opt("v")) > 0 }
        if (empty) area.addView(DashK.t(ctx, m, if (creator) "No earnings in this period yet" else "No coins spent in this period yet", 13f, DashK.SUB).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = m.pi(28f) })
        c.addView(area, DashK.lp(m, -1f, 124f))
        val labels = DashK.row(ctx, m, 0f).apply { setPadding(m.pi(2f), 0, m.pi(2f), 0); minimumHeight = m.pi(14f) }
        if (!loading) {
            val l = axisLabels(pts.map { it.optString("l") })
            l.forEachIndexed { i, s ->
                if (i > 0) labels.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                labels.addView(DashK.t(ctx, m, s, 11f, DashK.SUB).apply { maxLines = 1 })
            }
        }
        c.addView(labels, DashK.lp(m, -1f, -2f))
        return c
    }

    /** every label up to 12 points (days, months); for the 30 days, five spread evenly */
    private fun axisLabels(l: List<String>): List<String> {
        val n = l.size
        if (n <= 12) return l
        return (0 until 5).map { k -> l[((k * (n - 1)) / 4.0).roundToInt()] }
    }
}

/** The purple line with a soft fill (Chart of Dashboard.jsx): 326 × 124 design units. */
class DashChart(ctx: Context, private val m: Zm, private val vals0: List<Double>) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val k = m.px(1f)
        val top = 12f; val base = 112f; val w = 326f
        val vals = vals0.map { maxOf(0.0, it) }
        val max = vals.maxOrNull() ?: 0.0
        val n = vals.size
        val xy = vals.mapIndexed { i, v ->
            val x = Math.round((6 + (if (n > 1) i * (314.0 / (n - 1)) else 157.0)) * 10) / 10.0
            val y = Math.round((base - (if (max > 0) (v / (max * 1.15)) * (base - top) else 0.0)) * 10) / 10.0
            x.toFloat() to y.toFloat()
        }
        c.save(); c.scale(k, k)
        // three soft guide lines
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = DashK.SOFT
        for (y in listOf(top, 62f, base)) c.drawLine(0f, y, w, y, p)
        if (xy.isNotEmpty()) {
            if (max > 0) {
                val area = Path().apply { moveTo(xy[0].first, base); for ((x, y) in xy) lineTo(x, y); lineTo(xy.last().first, base); close() }
                p.style = Paint.Style.FILL; p.color = DashK.PURPLE; p.alpha = 26
                c.drawPath(area, p)
            }
            if (n > 1) {
                val line = Path().apply { xy.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) } }
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                p.color = DashK.PURPLE; p.alpha = if (max > 0) 255 else 89
                c.drawPath(line, p)
            }
            if (max > 0) {
                val (lx, ly) = xy.last()
                p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(lx, ly, 4.5f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f; p.color = DashK.PURPLE; c.drawCircle(lx, ly, 4.5f, p)
            }
        }
        c.restore()
    }
}

// ═══════════════════════════ Secret Dating Earning ═══════════════════════════
/**
 * True Find · Secret Dating Earning (src/pages/SecretEarnings.jsx), native. Opened from My Dashboard
 * (creators only): like bonus, renewal bonus, gift earnings and the locked-chat messages left today.
 * Every number comes from getSecretEarnings; the admin can open /secret-earnings?creator=<id>.
 */
@SuppressLint("ViewConstructor")
class SecretEarningsScreen(ctx: Context, val pages: NativePages) : FrameLayout(ctx) {
    private val m = dashZoom(ctx)
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
    private val column = DashK.col(ctx, m, 14f)
    private var d: JSONObject? = null
    private var dFor = "?"
    private var err = ""
    private var busy = false
    private var shown = false
    private var creatorId = ""
    private var seq = 0

    init {
        setBackgroundColor(DashK.PAGE)
        isClickable = true
        val wrap = FrameLayout(ctx)
        column.setPadding(m.pi(16f), m.pi(14f), m.pi(16f), m.pi(24f))
        wrap.addView(column, FrameLayout.LayoutParams(m.pi(390f), -2, Gravity.CENTER_HORIZONTAL))
        scroll.addView(wrap, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
    }

    fun setShown(on: Boolean, search: String) {
        val was = shown
        shown = on
        visibility = if (on) VISIBLE else GONE
        if (!on) { if (!pages.signedIn) { d = null; dFor = "?" }; return }
        val c = try { android.net.Uri.parse("tf://x/" + (if (search.startsWith("?")) search else "?$search")).getQueryParameter("creator").orEmpty() } catch (e: Exception) { "" }
        if (!was || c != creatorId) {
            creatorId = c
            if (dFor != c) { d = null; err = "" }
            render()
            load()
        }
    }

    /** test mode: "bottom" (scrolled down) */
    fun previewOpen(what: String) { if (what == "bottom") scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun load() {
        val s = ++seq
        busy = true
        if (err.isNotEmpty() && d == null) render()
        Api.invoke("getSecretEarnings", if (creatorId.isNotEmpty()) JSONObject().put("creator_id", creatorId) else JSONObject(), 30000) { r ->
            if (s != seq) return@invoke
            busy = false
            if (r.ok && r.data.optJSONObject("total") != null) { d = r.data; dFor = creatorId; err = "" }
            else err = if (r.ok) "Could not load Secret Dating Earning. Please try again." else Tf.err(r, "Could not load Secret Dating Earning. Please try again.")
            render()
        }
    }

    private fun icon(kind: String, c: String) = "<svg viewBox=\"0 0 24 24\">" + when (kind) {
        "heart" -> "<path d=\"M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z\" stroke=\"$c\" ${DashK.SE}/>"
        "renew" -> "<path d=\"M19.5 12a7.5 7.5 0 0 1-13 5.1M4.5 12a7.5 7.5 0 0 1 13-5.1\" stroke=\"$c\" ${DashK.SE}/><path d=\"M17.5 3.5v3.5H14M6.5 20.5V17H10\" stroke=\"$c\" ${DashK.SE}/>"
        "gift" -> "<rect x=\"4\" y=\"10.5\" width=\"16\" height=\"9.5\" rx=\"1.5\" stroke=\"$c\" ${DashK.SE}/><path d=\"M3 7.5h18v3H3zM12 7.5V20M12 7.5c-1.5-4-6-3.5-5-1 .5 1 2.5 1 5 1zm0 0c1.5-4 6-3.5 5-1-.5 1-2.5 1-5 1z\" stroke=\"$c\" ${DashK.SE}/>"
        else -> "<rect x=\"5\" y=\"10.5\" width=\"14\" height=\"9.5\" rx=\"2\" stroke=\"$c\" ${DashK.SE}/><path d=\"M8 10.5V8a4 4 0 0 1 8 0v2.5\" stroke=\"$c\" ${DashK.SE}/>"
    } + "</svg>"
    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    private fun render() {
        val ctx = context
        column.removeAllViews()
        column.addView(DashK.header(ctx, m, "Secret Dating Earning", 19f) { pages.historyBack() }, DashK.lp(m, -1f, 44f))
        val data = d
        if (data?.optBoolean("viewed_by_admin") == true) column.addView(DashK.t(ctx, m, "Admin view · ${Et.s(data.optJSONObject("creator"), "name").ifEmpty { "Creator" }}", 12.5f, DashK.AMBER, true).apply {
            gravity = Gravity.CENTER; background = DashK.box(m, DashK.AMBER_BG, 12f); setPadding(m.pi(12f), m.pi(8f), m.pi(12f), m.pi(8f))
        }, DashK.lp(m, -1f, -2f))
        if (err.isNotEmpty() && data == null) {
            val c = DashK.col(ctx, m, 12f, Gravity.CENTER_HORIZONTAL).apply { background = DashK.box(m, Color.WHITE, 20f, DashK.LINE); setPadding(m.pi(18f), m.pi(22f), m.pi(18f), m.pi(22f)) }
            c.addView(DashK.t(ctx, m, err, 15f, DashK.BODY, lh = 21f).apply { gravity = Gravity.CENTER })
            c.addView(DashK.t(ctx, m, if (busy) "Loading…" else "Try again", 14.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = DashK.box(m, DashK.PURPLE, 22f); setPadding(m.pi(22f), 0, m.pi(22f), 0); alpha = if (busy) .6f else 1f
                setOnClickListener { if (!busy) load() }
            }, DashK.lp(m, -2f, 44f))
            column.addView(c, DashK.lp(m, -1f, -2f))
            return
        }
        val loading = data == null
        val total = data?.optJSONObject("total")
        // ── total this month ──
        val tc = DashK.col(ctx, m, 0f).apply { background = DashK.box(m, DashK.DEEP, 20f); setPadding(m.pi(16f), m.pi(16f), m.pi(16f), m.pi(16f)) }
        tc.addView(DashK.t(ctx, m, "Secret Dating · this month", 13.5f, Ui.c("#E4D9F7")))
        if (loading) tc.addView(DashK.skel(ctx, true), DashK.lp(m, 150f, 40f).apply { topMargin = m.pi(2f) })
        else tc.addView(DashK.t(ctx, m, DashK.inr(total?.opt("month")), 32f, Color.WHITE, true, 40f), DashK.lp(m, -2f, -2f).apply { topMargin = m.pi(2f) })
        tc.addView(DashK.three(ctx, m, listOf("This Week" to DashK.inr(total?.opt("week")), "This Month" to DashK.inr(total?.opt("month")), "All Time" to DashK.inr(total?.opt("all"))), loading, labelOneLine = false), DashK.lp(m, -1f, -2f).apply { topMargin = m.pi(12f) })
        tc.addView(DashK.t(ctx, m, "Like members you enjoyed talking to — earn more ❤", 12.5f, Ui.c("#E4D9F7")).apply { gravity = Gravity.CENTER }, DashK.lp(m, -1f, -2f).apply { topMargin = m.pi(10f) })
        column.addView(tc, DashK.lp(m, -1f, -2f))
        val lb = data?.optJSONObject("like_bonus"); val rb = data?.optJSONObject("renewal_bonus"); val gf = data?.optJSONObject("gifts"); val lm = data?.optJSONObject("locked_messages"); val rates = data?.optJSONObject("rates")
        column.addView(part("heart", DashK.PINK, DashK.PINK_BG, "Like bonus", data?.let { DashK.inr(lb?.opt("inr_month")) },
            listOf("Likes given" to "${lb?.optInt("likes_month") ?: 0}", "Bought a plan" to "${lb?.optInt("count_month") ?: 0}", "All time" to DashK.inr(lb?.opt("inr_all"))), loading), DashK.lp(m, -1f, -2f))
        column.addView(part("renew", DashK.GREEN, DashK.GREEN_BG, "Renewal bonus", data?.let { DashK.inr(rb?.opt("inr_month")) },
            listOf("Renewed" to "${rb?.optInt("count_month") ?: 0}", "From your message" to "${rb?.optInt("messages_renewed") ?: 0}", "All time" to DashK.inr(rb?.opt("inr_all"))), loading), DashK.lp(m, -1f, -2f))
        column.addView(part("gift", DashK.PURPLE, DashK.TINT, "Gift earnings", data?.let { DashK.inr(gf?.opt("inr_month")) },
            listOf("Gifts" to "${gf?.optInt("count_month") ?: 0}", "Your share" to "${rates?.optInt("gift_pct") ?: 0}%", "All time" to DashK.inr(gf?.opt("inr_all"))), loading), DashK.lp(m, -1f, -2f))
        column.addView(part("lock", DashK.AMBER, DashK.AMBER_BG, "Locked-chat messages", lm?.let { "${it.optInt("left_today")} / ${it.optInt("limit")} left" },
            listOf("Sent today" to "${lm?.optInt("sent_today") ?: 0}", "This month" to "${lm?.optInt("sent_month") ?: 0}"), loading), DashK.lp(m, -1f, -2f))
        if (!loading && data != null) {
            val f = rates?.optJSONObject("first")
            column.addView(DashK.t(ctx, m, "Like bonus: ${DashK.inr(f?.opt("1"))} (1 day) · ${DashK.inr(f?.opt("7"))} (1 week) · ${DashK.inr(f?.opt("30"))} (1 month) when your like brings a member to his first plan. " +
                "Renewal bonus: ${DashK.inr(rates?.opt("renewal"))} when your message brings him back. One message per locked chat. Only you can see this page.${if (data.optBoolean("capped")) " Very old activity may not be counted." else ""}",
                12f, DashK.SUB, lh = 18f).apply { gravity = Gravity.CENTER }, DashK.lp(m, -1f, -2f).apply { setMargins(m.pi(4f), 0, m.pi(4f), 0) })
        }
        if (err.isNotEmpty() && data != null) column.addView(DashK.t(ctx, m, "Could not refresh just now. Showing the last numbers.", 12f, Ui.c("#B91C1C")).apply { gravity = Gravity.CENTER }, DashK.lp(m, -1f, -2f))
    }

    /** one of the four parts: icon, title, the money this month, and two or three small facts */
    private fun part(kind: String, color: Int, bg: Int, title: String, money: String?, facts: List<Pair<String, String>>, loading: Boolean): View {
        val ctx = context
        val c = DashK.col(ctx, m, 10f).apply { background = DashK.box(m, Color.WHITE, 18f, DashK.LINE); setPadding(m.pi(16f), m.pi(14f), m.pi(16f), m.pi(14f)) }
        val top = DashK.row(ctx, m, 12f)
        top.addView(FrameLayout(ctx).apply { background = DashK.circ(bg); addView(DashK.svg(ctx, m, icon(kind, hex(color)), 22f), FrameLayout.LayoutParams(m.pi(22f), m.pi(22f), Gravity.CENTER)) }, DashK.lp(m, 40f, 40f))
        top.addView(DashK.t(ctx, m, title, 16f, DashK.INK, true), DashK.lp(m, 0f, -2f, 1f))
        if (loading) top.addView(DashK.skel(ctx, false), DashK.lp(m, 60f, 20f))
        else if (money != null) top.addView(DashK.t(ctx, m, money, 17f, color, true).apply { maxLines = 1 })
        c.addView(top, DashK.lp(m, -1f, -2f))
        c.addView(DashK.three(ctx, m, facts, loading, DashK.TINT, DashK.LINE, 12f, true, 15f), DashK.lp(m, -1f, -2f))
        return c
    }
}

@Suppress("unused") private fun keepDash(a: Any) = a
