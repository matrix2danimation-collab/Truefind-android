package app.truefind.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Moving from one page to the next, the way FRND does it. Measured from the owner's screen recordings of
 * FRND (same phone, 60 pictures a second on the screen, 24 in the recording), each move followed frame by frame:
 *
 * - TAB (Home ↔ Explore ↔ Recent, and Secret Dating's Map ↔ Home ↔ Chat): both pages slide side by side, a
 *   whole screen width; nothing fades, the bottom bar stays still. FRND's pages were 35 % of the way on the
 *   first screen frame, then 60 · 76 · 87 · 93 · 97 · 99 %: exactly Android's ViewPager (200 ms, curve
 *   1 − (1 − t)⁵). Two tabs apart takes 300 ms and the page in between passes by, as in FRND.
 *   (Checked again on 19 Nov: the app's own tab moves land on the same places, frame for frame.)
 * - PUSH (opening a page: Profile, Wallet, Games, a chat …): the new page slides in from the right over the
 *   old one. The old page does not move and does not darken — the way FRND opens its Profile:
 *   12 · 59 · 89 · 100 % and 26 · 76 · 98 · 100 % at 24 pictures a second, which is 150 ms on Android's
 *   plain ease-in-out curve (½ − ½ cos πt), exactly on the screen's frames (33 · 83 · 117 ms / 50 · 100 · 133 ms).
 * - POP (Back): the way the Claude app goes back — the owner's choice on 19 Nov, from his recording of it
 *   (four Backs followed frame by frame, each page's place, size and see-through fitted to a fraction of a pixel):
 *   · the page you leave goes half a screen to the right, shrinks to 90 % and fades away, all three together:
 *     35 → 81 → 93 % of the way on three recorded frames (41 ms apart), gone after about 110 ms;
 *   · the page you come back to lies under it: it fades in at 92 % of its size, coming from 15 % of the width
 *     further left (seen 16 → 57 → 72 → 82 %, shift −12 → −6 → −4 → −2.6 %), and from about 140 ms it grows
 *     to full size (92 → 97 → 99.4 → 99.8 → 100 %); all is still after about 290 ms.
 *   Both pages turn around the middle of the screen. (Until 19 Nov: FRND's way — the page slid away over
 *   300 ms and the page under it stood still: 2 · 12 · 34 · 51 · 73 · 90 · 98 %.)
 *
 *   FRND has a second way too, for the pages that are separate screens of it (its room lists): Android's own
 *   screen change — 450 ms, the old page a quarter of the width to the left and darkened to half. This app
 *   used that one until 19 Nov (360 / 420 ms); the owner chose FRND's quicker, lighter Profile way for every
 *   page: nothing is blended over the whole screen and only one page moves, so each frame is cheaper too.
 *
 * How it stays smooth:
 * - Only where a page is drawn changes (its drawing is not made again), and the part of the lower page that
 *   the upper page covers is not drawn at all.
 * - Back fades and resizes whole pages: for that move each of the two pages is one finished picture
 *   (a hardware layer), so a frame only places two pictures instead of drawing both pages again.
 * - The move's clock goes by the frames that were really drawn: when the phone needs longer for one frame
 *   (a page drawn for the first time), the next frame goes on from where the last one was instead of jumping
 *   ahead. Before, one slow frame made the page jump most of the way in one go.
 * - Answers from the server wait until the move has ended ([whenStill]): filling a page in the middle of a
 *   move made it stop half way.
 *
 * The page you leave stays on screen until the move ends (the app hides it a moment later). A page that
 * isn't a view any more (a web page you went back from) is shown as the picture taken just before.
 * Phones with animations switched off get no movement.
 */
object PageFx {
    enum class Kind { TAB, PUSH, POP }

    private const val TAB_MS = 200f        // + 100 ms for each page in between (ViewPager)
    private const val PUSH_MS = 150f
    private const val POP_MS = 300f
    // Back, the Claude app's way (ms on the move's clock; see the notes above)
    private const val POP_OLD_MS = 110f    // the page you leave: gone by then
    private const val POP_TURN_MS = 140f   // the page under it: faded in (small) until then, growing after
    private const val POP_GROW_MS = 35f    // … each 35 ms it comes 63 % closer to full size
    private const val POP_OLD_SHIFT = 0.5f // of the width, to the right
    private const val POP_OLD_SIZE = 0.9f
    private const val POP_NEW_SHIFT = 0.15f // of the width, from the left
    private const val POP_NEW_SIZE = 0.92f
    private const val POP_NEW_SEEN = 0.8f  // how visible at the turn
    private const val POP_NEW_LEFT = 0.15f // how much of its way it still has to go at the turn
    /** quick at first, then slowing (1 − (1 − t)²) */
    private fun outQuad(t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return 1f - u * u }
    /** Android's plain ease-in-out (AccelerateDecelerateInterpolator) */
    private fun inOut(t: Float): Float = (0.5 - 0.5 * Math.cos(Math.PI * t)).toFloat()
    /** ViewPager's curve */
    private fun pager(t: Float): Float { val u = t - 1f; return u * u * u * u * u + 1f }

    private var ticker: Choreographer.FrameCallback? = null
    private val held = ArrayList<View>()      // pages kept on screen until the move ends
    private val wanted = HashMap<View, Int>() // what the app last set for each of them (shown after the move)
    private val moved = ArrayList<View>()     // views whose translation we change
    private val lifted = ArrayList<Lift>()    // views lifted above the others for the move
    private val clipped = ArrayList<View>()   // views drawn only where the upper page doesn't cover them
    private val faded = ArrayList<Look>()     // views whose size and see-through we change (Back)
    private var overlayHost: ViewGroup? = null
    private val overlays = ArrayList<Drawable>()
    private var ended: (() -> Unit)? = null
    private val clip = Rect()

    private class Lift(val v: View, val z: Float, val spot: Int, val ambient: Int)
    /** how a view looked before the move (it gets all of it back) */
    private class Look(val v: View, val alpha: Float, val sx: Float, val sy: Float, val px: Float, val py: Float, val pivotSet: Boolean, val layer: Int)

    fun off(ctx: Context): Boolean = try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) { false }

    val running: Boolean get() = ticker != null || frozen
    fun isHeld(v: View) = held.contains(v)
    /**
     * Before the app sets which pages show again: the pages still moving get back what the app set last, so a
     * screen that only hides itself once ("already hidden: nothing to do") isn't taken for one that should show.
     */
    fun release() { if (running) for (v in held) wanted[v]?.let { v.visibility = it } }
    /**
     * The app has just set which pages show: the pages still moving stay on screen until the move ends,
     * and then get what the app set last (a screen that was told "hidden" once isn't told again).
     */
    fun reassert() { if (running) for (v in held) { wanted[v] = v.visibility; if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE } }

    /** test runs only: the next move stops this far along the way (0..1) until [finish], so it can be photographed */
    var freezeAt = -1f
    private var frozen = false

    // ── work that waits for the move to end (answers from the server filling a page) ──
    private val main = Handler(Looper.getMainLooper())
    private val later = ArrayList<() -> Unit>()
    private fun runLater() { main.removeCallbacks(force); val q = ArrayList(later); later.clear(); for (b in q) b() }
    private val flush = Runnable { if (ticker == null) runLater() }
    /** (someone switching pages again and again: nothing waits longer than 2 s) */
    private val force = Runnable { runLater() }
    /** Runs [block] now, or right after the move when pages are moving (never during a test's stopped move). */
    fun whenStill(block: () -> Unit) {
        if (ticker == null && later.isEmpty()) { block(); return }
        if (later.isEmpty()) main.postDelayed(force, 2000)
        later.add(block)
        if (ticker == null) { main.removeCallbacks(flush); main.post(flush) }
    }

    /** Ends a running move at once (every page in its final place). */
    fun finish() {
        ticker?.let { Choreographer.getInstance().removeFrameCallback(it) }
        ticker = null
        Perf.moveEnd()
        for (v in moved) v.translationX = 0f
        moved.clear()
        for (l in lifted) {
            l.v.translationZ = l.z
            if (Build.VERSION.SDK_INT >= 28) { l.v.outlineSpotShadowColor = l.spot; l.v.outlineAmbientShadowColor = l.ambient }
        }
        lifted.clear()
        for (v in clipped) v.clipBounds = null
        clipped.clear()
        for (k in faded) {
            val v = k.v
            v.alpha = k.alpha; v.scaleX = k.sx; v.scaleY = k.sy
            if (Build.VERSION.SDK_INT >= 28 && !k.pivotSet) v.resetPivot() else if (k.pivotSet) { v.pivotX = k.px; v.pivotY = k.py } else { v.pivotX = v.width / 2f; v.pivotY = v.height / 2f }
            if (v.layerType != k.layer) v.setLayerType(k.layer, null)
        }
        faded.clear()
        for (v in held) wanted[v]?.let { v.visibility = it }
        wanted.clear()
        frozen = false
        overlayHost?.let { h -> for (d in overlays) h.overlay.remove(d) }
        overlays.clear(); overlayHost = null
        held.clear()
        val e = ended; ended = null
        e?.invoke()
        // (posted: when one move ends because the next one starts, the waiting work must not run at that tap)
        if (later.isNotEmpty()) { main.removeCallbacks(flush); main.post(flush) }
    }

    /**
     * [from] / [to]: the page you leave and the page you go to (the web view counts as a page).
     * [between]: TAB only — the pages lying between them, in the order they pass by.
     * [lowerExtras]: views that move with the lower page (the bottom bar of a tab page).
     * [upperExtras]: views that move with the upper page, just under it (Secret Dating's own bottom bar).
     * [snapshot]: for POP when the page you leave is already gone (a web page): its picture.
     * [moveLower]: false when the lower page is not one of the app's own (the sign-in web page): it is left alone.
     * [onEnd]: after the move (the app then hides the pages that should be hidden).
     */
    fun run(
        container: FrameLayout, kind: Kind, from: View?, to: View?, dir: Int = 1,
        lowerExtras: List<View> = emptyList(), upperExtras: List<View> = emptyList(), snapshot: Bitmap? = null, moveLower: Boolean = true,
        between: List<View> = emptyList(), onEnd: (() -> Unit)? = null,
    ) {
        finish()
        val w = container.width.toFloat()
        if (w <= 0f || off(container.context) || (from == null && to == null && snapshot == null) || (from != null && from === to)) { onEnd?.invoke(); return }
        ended = onEnd
        val zUp = Ui.dp(48f)
        fun hold(v: View?) { if (v != null && v !is WebView && !held.contains(v)) { held.add(v); wanted[v] = v.visibility; v.visibility = View.VISIBLE } }
        fun move(v: View?) { if (v != null && !moved.contains(v)) moved.add(v) }
        fun lift(v: View, z: Float) {
            val spot = if (Build.VERSION.SDK_INT >= 28) v.outlineSpotShadowColor else 0
            val amb = if (Build.VERSION.SDK_INT >= 28) v.outlineAmbientShadowColor else 0
            lifted.add(Lift(v, v.translationZ, spot, amb))
            v.translationZ = z
            // above the others without a big shadow (FRND's sliding page has a clean edge)
            if (Build.VERSION.SDK_INT >= 28) { v.outlineSpotShadowColor = Color.TRANSPARENT; v.outlineAmbientShadowColor = Color.TRANSPARENT }
        }
        /** [v] is drawn only left of [edge] (a place on the screen): the upper page covers the rest */
        fun cut(v: View, edge: Float) {
            if (!clipped.contains(v)) {
                if (v.clipBounds != null) return   // a view with its own clip keeps it
                clipped.add(v)
            }
            val r = (edge - v.left - v.translationX).toInt() + 2
            clip.set(0, 0, r.coerceAtLeast(0), maxOf(v.height, container.height, 1))
            v.clipBounds = clip
        }
        /** [v] may be resized and faded in this move: one finished picture of it, turning around the middle of the screen */
        fun look(v: View) {
            if (v is WebView || faded.any { it.v === v }) return
            faded.add(Look(v, v.alpha, v.scaleX, v.scaleY, v.pivotX, v.pivotY, Build.VERSION.SDK_INT >= 28 && v.isPivotSet, v.layerType))
            if (v.layerType == View.LAYER_TYPE_NONE) v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            v.pivotX = container.width / 2f - v.left
            v.pivotY = container.height / 2f - v.top
        }
        val ms: Float
        val ease: (Float) -> Float
        val step: (Float) -> Unit
        when (kind) {
            Kind.TAB -> {
                // side by side, like the pages of a ViewPager: [from] | between … | [to]
                val mid = between.filter { it !== from && it !== to && it !is WebView }
                val n = mid.size + 1
                ms = TAB_MS + 100f * mid.size
                ease = ::pager
                hold(from); mid.forEach { hold(it) }
                move(from); move(to); mid.forEach { move(it) }
                step = { t ->
                    val p = n * t
                    from?.translationX = -dir * w * p
                    for (i in mid.indices) mid[i].translationX = dir * w * (i + 1 - p)
                    to?.translationX = dir * w * (n - p)
                }
            }
            Kind.PUSH -> {
                ms = PUSH_MS
                ease = ::inOut
                val upper = to
                if (upper == null) { ended = null; onEnd?.invoke(); return }
                hold(from); lowerExtras.forEach { hold(it) }
                lift(upper, zUp)
                move(upper)
                val ups = upperExtras.filter { it !== upper && it !== from }
                ups.forEach { lift(it, zUp - Ui.dp(1f)); move(it) }
                // the old page stays where it is, as bright as it is: only what the new page covers is not drawn
                val lowers = if (moveLower) listOfNotNull(from) + lowerExtras else emptyList()
                step = { t ->
                    val x = w * (1f - t)
                    val edge = upper.left + x
                    upper.translationX = x
                    for (u in ups) u.translationX = x
                    for (l in lowers) cut(l, edge)
                }
            }
            Kind.POP -> {
                ms = POP_MS
                // (each part of this move has its own curve: the step gets the plain clock, 0..1 of POP_MS)
                ease = { it }
                val lowers = (listOfNotNull(to) + lowerExtras).filter { it !is WebView }
                val looks = HashMap<View, Look>()
                fun ready(v: View) { look(v); faded.firstOrNull { it.v === v }?.let { looks[v] = it } }
                /** the page under: small and faint, a little to the left, until the turn; then it grows to full size */
                fun under(at: Float) {
                    val seen: Float; val shift: Float; val size: Float
                    if (at <= POP_TURN_MS) {
                        val g = outQuad(at / POP_TURN_MS)
                        seen = POP_NEW_SEEN * g
                        shift = 1f - (1f - POP_NEW_LEFT) * g
                        size = POP_NEW_SIZE
                    } else {
                        val k = if (at >= POP_MS) 0f else Math.exp(-((at - POP_TURN_MS) / POP_GROW_MS).toDouble()).toFloat()
                        seen = 1f - (1f - POP_NEW_SEEN) * k
                        shift = POP_NEW_LEFT * k
                        size = 1f - (1f - POP_NEW_SIZE) * k
                    }
                    for (l in lowers) {
                        val was = looks[l] ?: continue
                        l.translationX = -POP_NEW_SHIFT * w * shift
                        l.scaleX = was.sx * size; l.scaleY = was.sy * size
                        l.alpha = was.alpha * seen
                    }
                }
                lowers.forEach { ready(it); move(it) }
                if (from != null) {
                    // the page you leave is still a view: it goes to the right on top, shrinking and fading
                    hold(from)
                    lift(from, zUp)
                    move(from)
                    val ups = upperExtras.filter { it !== from && it !== to }
                    // (they leave with the page: on screen until the move ends, as that page is)
                    ups.forEach { hold(it); lift(it, zUp - Ui.dp(1f)); move(it) }
                    val uppers = listOf(from) + ups
                    uppers.forEach { ready(it) }
                    step = { t ->
                        val at = t * POP_MS
                        val p = if (t >= 1f) 1f else outQuad(at / POP_OLD_MS)
                        for (u in uppers) {
                            // (a web page is not resized or faded: it slides off the screen, as it did)
                            val was = looks[u]
                            if (was == null) { u.translationX = w * p; continue }
                            u.translationX = POP_OLD_SHIFT * w * p
                            val size = 1f - (1f - POP_OLD_SIZE) * p
                            u.scaleX = was.sx * size; u.scaleY = was.sy * size
                            u.alpha = was.alpha * (1f - p)
                        }
                        under(at)
                    }
                } else {
                    // the page you leave is gone (a web page): its picture goes the same way on top
                    val shot = snapshot?.let { BitmapDrawable(container.resources, it).apply { setBounds(0, 0, container.width, container.height) } }
                    if (shot == null) { ended = null; finish(); onEnd?.invoke(); return }
                    container.overlay.add(shot); overlays.add(shot)
                    overlayHost = container
                    val cw = container.width; val ch = container.height
                    step = { t ->
                        val at = t * POP_MS
                        val p = if (t >= 1f) 1f else outQuad(at / POP_OLD_MS)
                        val size = 1f - (1f - POP_OLD_SIZE) * p
                        val sw = (cw * size).toInt(); val sh = (ch * size).toInt()
                        val x = ((cw - sw) / 2f + POP_OLD_SHIFT * w * p).toInt(); val y = (ch - sh) / 2
                        shot.setBounds(x, y, x + sw, y + sh)
                        shot.alpha = (255f * (1f - p)).toInt().coerceIn(0, 255)
                        under(at)
                        container.invalidate()
                    }
                }
            }
        }
        Perf.moveStart(kind.name, ms.toInt())
        step(0f)
        if (freezeAt in 0f..1f) {
            // test runs only: stay at this point of the way (0..1) until finish()
            // (a Back has most to show early: both pages are on screen for its first third only)
            val at = if (kind == Kind.POP) freezeAt / 3f else freezeAt
            freezeAt = -1f
            frozen = true
            step(at)
            container.postInvalidate()
            return
        }

        // The clock of the move: each drawn frame goes on from the last one. A frame that took long (the new
        // page drawn for the first time) counts as one and a half frames at most, so nothing is jumped over.
        val hz = try { container.display?.refreshRate ?: 60f } catch (e: Exception) { 60f }
        val frameNs = 1e9f / (if (hz in 30f..240f) hz else 60f)
        val born = System.nanoTime()
        var last = 0L
        var first = 0L
        var spent = 0f          // ms of the move shown so far
        var frames = 0
        var worst = 0L          // the longest wait between two frames (ms)
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (ticker !== this) return
                val gap: Long
                if (last == 0L) {
                    first = now; last = now
                    if (now <= born) {
                        // still the frame of the tap itself: it shows the start (as ViewPager does)
                        Choreographer.getInstance().postFrameCallback(this)
                        return
                    }
                    // the first frame after the tap: one frame into the move (FRND: 35 % of a tab slide)
                    gap = frameNs.toLong()
                } else {
                    gap = now - last
                    last = now
                }
                frames++
                if (gap / 1_000_000L > worst) worst = gap / 1_000_000L
                spent += minOf(gap.toFloat(), frameNs * 1.5f) / 1_000_000f
                // (a phone that can't keep up at all: the move never takes more than half a second longer)
                val late = (now - first) / 1_000_000f > ms + 500f
                if (spent >= ms || late) {
                    step(1f)
                    android.util.Log.i("TFFX", "$kind ${ms.toInt()}ms took ${(now - first) / 1_000_000L}ms, $frames frames, longest gap ${worst}ms")
                    finish()
                    return
                }
                step(ease(spent / ms))
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        ticker = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }
}
