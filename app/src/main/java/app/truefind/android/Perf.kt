package app.truefind.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.provider.Settings
import android.view.FrameMetrics
import android.view.Window
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * How smoothly the app really draws on this phone — test builds only (Settings → Page speed report).
 *
 * A screen recording cannot show this: it keeps 24 pictures a second while the screen shows 60 or more, so a
 * picture the app delivered late is simply not in it. Android itself can tell: for every frame the app draws
 * it reports how long the frame took from the screen's tick it was meant for until the picture was handed
 * over (the window's frame metrics, Android 7+). A frame that came after its tick was late: the screen
 * showed the old picture once more, which is the small stutter the eye feels.
 *
 * Two kinds of late (19 Nov, after the first report from the owner's phone showed "0 late · worst 143 ms"):
 *  - the frame itself took too long (Android 12+ says how long it was allowed: the "deadline"), or
 *  - the app was busy with other work and started the frame late. Android then plans that frame anew with a
 *    later deadline, so by Android's own rule it is "on time" — but the old picture stayed on the screen all
 *    that while. Both count here now, by the ticks the old picture stayed ("ticks lost"); a wait of 50 ms or
 *    more is listed on its own with what took the time (waiting to start / layout / drawing / painting).
 *
 * Each frame is counted under what was happening when it was drawn:
 *  - a page move (TAB / OPEN / BACK, from → to), or
 *  - the page that was on screen (its own animations: floating bubbles, a loader, a blinking badge).
 * Nothing is sent anywhere and nothing is kept after the app closes; the report is text to copy.
 */
object Perf {
    private class Bucket {
        var frames = 0; var late = 0; var lost = 0; var sumMs = 0.0; var worstMs = 0.0
        val bins = IntArray(51)   // frames by how long they took: 0–2, 2–4 … 98–100, 100+ ms
        fun typical(): Double { var n = 0; for (i in bins.indices) { n += bins[i]; if (n * 2 >= frames) return i * 2 + 1.0 }; return 0.0 }
    }
    private class Move(val label: String, val planMs: Int, val startNs: Long, val wallMs: Long) {
        var endNs = 0L; var frames = 0; var late = 0; var lost = 0; var worstMs = 0.0
    }
    /** a wait of 50 ms or more: the old picture stayed that long */
    private class Wait(val wallMs: Long, val where: String, val ms: Double, val parts: String)

    private val lock = Any()
    private val moveStats = LinkedHashMap<String, Bucket>()
    private val moveCount = HashMap<String, Int>()
    private val pageStats = LinkedHashMap<String, Bucket>()
    private val moves = ArrayDeque<Move>()               // the last 40 moves
    private val timeline = ArrayDeque<Pair<Long, String>>()   // (when, page) — the last 40 page changes
    private val waits = ArrayDeque<Wait>()                // the last 30 waits of 50 ms or more
    private var allowSumNs = 0L; private var allowN = 0    // what Android allowed an ordinary frame (Android 12+)
    private var screen: android.view.Display? = null
    private var running: Move? = null
    private var since = System.currentTimeMillis()
    private var started = false
    private var periodNs = 16_666_667L
    private var thread: HandlerThread? = null

    val on: Boolean get() = started

    /** Starts counting the frames of [window] (once; Android 7+). */
    fun start(act: Activity) {
        if (started || Build.VERSION.SDK_INT < 24) return
        val window: Window = act.window ?: return
        started = true
        since = System.currentTimeMillis()
        screen = try { act.windowManager.defaultDisplay } catch (e: Throwable) { null }
        tick()
        val t = HandlerThread("tf-perf").apply { start() }
        thread = t
        try {
            window.addOnFrameMetricsAvailableListener({ _, m, _ -> frame(m) }, Handler(t.looper))
        } catch (e: Throwable) { started = false }
    }

    /** How long one tick of the screen is now (the phone may change between 60 and 90 pictures a second). */
    private fun tick() {
        val hz = try { screen?.refreshRate ?: 60f } catch (e: Throwable) { 60f }
        periodNs = (1e9 / (if (hz in 30f..240f) hz else 60f)).toLong()
    }

    /** The page on screen from now on (its own frames are counted under this name). */
    fun page(path: String, search: String) {
        if (!started) return
        tick()
        val name = pageName(path, search)
        synchronized(lock) {
            if (timeline.peekLast()?.second == name) return
            timeline.addLast(System.nanoTime() to name)
            while (timeline.size > 40) timeline.removeFirst()
        }
    }

    /** What the next move is called (set just before PageFx starts it). */
    @Volatile var nextLabel = ""

    fun moveStart(kind: String, planMs: Int) {
        if (!started) return
        tick()
        val what = when (kind) { "PUSH" -> "OPEN"; "POP" -> "BACK"; else -> kind }
        val m = Move("$what ${nextLabel.ifEmpty { "?" }}", planMs, System.nanoTime(), System.currentTimeMillis())
        nextLabel = ""
        synchronized(lock) {
            running = m
            moves.addLast(m)
            while (moves.size > 40) moves.removeFirst()
            moveCount[m.label] = (moveCount[m.label] ?: 0) + 1
        }
    }

    fun moveEnd() {
        if (!started) return
        synchronized(lock) { running?.let { if (it.endNs == 0L) it.endNs = System.nanoTime() }; running = null }
    }

    private fun frame(m: FrameMetrics) {
        try {
            if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total <= 0L) return
            // when the frame was meant to show (the same clock as System.nanoTime); older phones: about now
            val at = if (Build.VERSION.SDK_INT >= 26) m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) else System.nanoTime() - total
            // how long Android allowed for it (Android 12+ says so; before: one tick)
            val limit = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0L } ?: periodNs else periodNs
            // a frame that was started late gets a new, later limit from Android: the screen still waited, so never more than two ticks
            val allow = minOf(limit, 2 * periodNs)
            val ms = total / 1e6
            val lost = if (total > allow) ((total - allow + periodNs - 1) / periodNs).toInt() else 0
            val late = lost > 0
            val long = ms >= 50.0
            val parts = if (long) parts(m) else ""
            synchronized(lock) {
                if (limit <= 3 * periodNs) { allowSumNs += limit; allowN++ }
                val mv = moves.newest { at >= it.startNs - periodNs && (it.endNs == 0L || at <= it.endNs) }
                val b: Bucket
                val where: String
                if (mv != null) {
                    mv.frames++; if (late) mv.late++; mv.lost += lost; if (ms > mv.worstMs) mv.worstMs = ms
                    b = moveStats.getOrPut(mv.label) { Bucket() }
                    where = mv.label
                } else {
                    var name = "?"
                    for (e in timeline) { if (e.first <= at) name = e.second else break }
                    b = pageStats.getOrPut(name) { Bucket() }
                    where = name
                }
                b.frames++; b.sumMs += ms
                b.bins[minOf(50, (ms / 2.0).toInt())]++
                if (late) b.late++
                b.lost += lost
                if (ms > b.worstMs) b.worstMs = ms
                if (long) { waits.addLast(Wait(System.currentTimeMillis(), where, ms, parts)); while (waits.size > 30) waits.removeFirst() }
            }
        } catch (e: Throwable) { }
    }

    /** What a long frame spent its time on (only the parts of 8 ms or more). */
    private fun parts(m: FrameMetrics): String {
        fun g(id: Int): Long = try { m.getMetric(id) / 1_000_000L } catch (e: Throwable) { 0L }
        val list = listOf(
            "waiting to start" to g(FrameMetrics.UNKNOWN_DELAY_DURATION),
            "touch" to g(FrameMetrics.INPUT_HANDLING_DURATION),
            "animation" to g(FrameMetrics.ANIMATION_DURATION),
            "layout" to g(FrameMetrics.LAYOUT_MEASURE_DURATION),
            "drawing" to g(FrameMetrics.DRAW_DURATION),
            "painting" to (g(FrameMetrics.SYNC_DURATION) + g(FrameMetrics.COMMAND_ISSUE_DURATION) + g(FrameMetrics.SWAP_BUFFERS_DURATION)))
        return list.filter { it.second >= 8L }.sortedByDescending { it.second }.joinToString(" · ") { "${it.first} ${it.second}" }
    }

    private fun <T> ArrayDeque<T>.newest(test: (T) -> Boolean): T? {
        val it = descendingIterator()
        while (it.hasNext()) { val v = it.next(); if (test(v)) return v }
        return null
    }

    fun reset() {
        synchronized(lock) { moveStats.clear(); moveCount.clear(); pageStats.clear(); moves.clear(); waits.clear(); allowSumNs = 0L; allowN = 0; since = System.currentTimeMillis() }
    }

    /** A short name for the page at this address. */
    fun pageName(path: String, search: String = ""): String {
        val p = path.trimEnd('/').ifEmpty { "/" }
        return when {
            p == "/" || p == "/home" -> "Home"
            p == "/explore" -> "Explore"
            p == "/chats" -> "Recent"
            p == "/wallet" -> if (search.contains("app=secret")) "Wallet (Secret Dating)" else "Wallet"
            p == "/profile/edit" -> "Edit profile"
            p == "/profile" -> if (search.isEmpty()) "Profile" else "Profile (inside)"
            p == "/secret-app" -> if (search.contains("tab=chat")) "SD Chat" else "Secret Dating"
            p == "/dating" -> "SD Map"
            p.startsWith("/chat/") -> "Chat room"
            p.startsWith("/games") -> "Games"
            p.startsWith("/dance") -> "Live Dance"
            p.startsWith("/live") -> "Live Rooms"
            p == "/expert-talk" -> "Expert Talk"
            p == "/dashboard" || p == "/secret-earnings" -> "Dashboard"
            p == "/admin" -> "Control Center"
            p == "/register/verification" -> "Verification"
            else -> p
        }
    }

    private fun pct(a: Int, b: Int): String = if (b <= 0) "0%" else String.format(Locale.US, "%.1f%%", a * 100.0 / b)
    private fun ms(v: Double): String = String.format(Locale.US, "%.0f ms", v)

    /** The move that ended last, in one line (test runs write it to the phone's log). */
    fun lastMove(): String = synchronized(lock) {
        val m = moves.peekLast() ?: return "no move yet"
        val took = if (m.endNs > 0L) "${(m.endNs - m.startNs) / 1_000_000L} ms" else "running"
        "${m.label}: planned ${m.planMs} ms, took $took, ${m.frames} frames, ${m.late} late (${m.lost} ticks lost), worst ${ms(m.worstMs)}"
    }

    /** The report as text. */
    fun report(act: Activity): String {
        val sb = StringBuilder()
        val disp = try { act.windowManager.defaultDisplay } catch (e: Throwable) { null }
        val hz = try { disp?.refreshRate ?: 0f } catch (e: Throwable) { 0f }
        val can = try {
            val cur = disp?.mode
            disp?.supportedModes.orEmpty().filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }
                .map { Math.round(it.refreshRate) }.distinct().sorted().joinToString(", ")
        } catch (e: Throwable) { "" }
        val dm = act.resources.displayMetrics
        val pm = act.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val saver = try { pm?.isPowerSaveMode == true } catch (e: Throwable) { false }
        val heat = if (Build.VERSION.SDK_INT >= 29) try {
            when (pm?.currentThermalStatus) { 0 -> "normal"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"; else -> "?" }
        } catch (e: Throwable) { "?" } else "not told by this Android"
        val anim = try { Settings.Global.getFloat(act.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (e: Throwable) { 1f }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        sb.append("True Find · page speed · build ${BuildConfig.VERSION_NAME}\n")
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
        sb.append("Screen: ${dm.widthPixels}×${dm.heightPixels} · now ${Math.round(hz)} pictures a second" + (if (can.isNotEmpty()) " (can do: $can)" else "") + "\n")
        sb.append("Battery saver: ${if (saver) "ON" else "off"} · animation speed setting: ${anim}× · heat: $heat\n")
        if (!started) { sb.append("\nThis Android cannot measure frames (needs Android 7 or newer).\n"); return sb.toString() }
        synchronized(lock) {
            sb.append("Counted since ${time.format(Date(since))} (now ${time.format(Date())})\n")
            if (allowN > 0) sb.append("Android allows an ordinary frame about ${String.format(Locale.US, "%.0f", allowSumNs / 1e6 / allowN)} ms here (one tick is ${String.format(Locale.US, "%.1f", periodNs / 1e6)} ms)\n")
            sb.append("\"late\" = the picture came after the tick it was meant for · \"ticks lost\" = how many ticks the old picture stayed on in all\n")
            sb.append("\nPAGE MOVES — times · frames · late · ticks lost · typical frame · longest wait\n")
            if (moveStats.isEmpty()) sb.append("  (none yet: change pages a few times, then open this again)\n")
            for ((k, b) in moveStats.entries.sortedByDescending { it.value.lost * 1000 / maxOf(1, it.value.frames) }) {
                sb.append("  $k  ×${moveCount[k] ?: 0} · ${b.frames} frames · ${b.late} late (${pct(b.late, b.frames)}) · ${b.lost} ticks lost · typical ${String.format(Locale.US, "%.0f", b.typical())} ms · longest ${ms(b.worstMs)}\n")
            }
            val all = Bucket()
            for (b in moveStats.values) { all.frames += b.frames; all.late += b.late; all.lost += b.lost; if (b.worstMs > all.worstMs) all.worstMs = b.worstMs }
            if (moveStats.isNotEmpty()) sb.append("  ALL MOVES: ${all.frames} frames · ${all.late} late (${pct(all.late, all.frames)}) · ${all.lost} ticks lost · longest ${ms(all.worstMs)}\n")
            sb.append("\nPAGES WHILE OPEN (their own animations) — frames · late · ticks lost · typical frame · longest wait\n")
            if (pageStats.isEmpty()) sb.append("  (none yet)\n")
            for ((k, b) in pageStats.entries.sortedByDescending { it.value.frames }) {
                sb.append("  $k: ${b.frames} frames · ${b.late} late (${pct(b.late, b.frames)}) · ${b.lost} ticks lost · typical ${String.format(Locale.US, "%.0f", b.typical())} ms · longest ${ms(b.worstMs)}\n")
            }
            sb.append("\nLONG WAITS (50 ms or more, the newest last) — when · where · how long · what took the time\n")
            if (waits.isEmpty()) sb.append("  (none)\n")
            for (w in waits) sb.append("  ${time.format(Date(w.wallMs))} ${w.where}: ${ms(w.ms)}" + (if (w.parts.isNotEmpty()) " · ${w.parts}" else "") + "\n")
            sb.append("\nLAST MOVES — planned · really took · frames · late · longest wait\n")
            val last = ArrayList(moves).takeLast(15)
            if (last.isEmpty()) sb.append("  (none yet)\n")
            for (m in last) {
                val took = if (m.endNs > 0L) "${(m.endNs - m.startNs) / 1_000_000L} ms" else "running"
                sb.append("  ${time.format(Date(m.wallMs))} ${m.label}: ${m.planMs} ms · $took · ${m.frames} frames · ${m.late} late (${m.lost} ticks) · longest ${ms(m.worstMs)}\n")
            }
        }
        return sb.toString()
    }

    /** Settings → Page speed report: the numbers, with Copy. */
    fun show(act: Activity) {
        val text = report(act)
        val tv = TextView(act).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            val p = Ui.dpi(16f)
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(act)
            .setTitle("Page speed report")
            .setView(ScrollView(act).apply { addView(tv) })
            .setPositiveButton("Copy") { _, _ ->
                try {
                    (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("True Find page speed", text))
                    Toast.makeText(act, "Copied. Paste it in the chat.", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) { }
            }
            .setNeutralButton("Start again") { _, _ -> reset(); Toast.makeText(act, "Counting from now.", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Close", null)
            .show()
    }
}
