package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Explore's moving feed, drawn natively (no web page): round profile bubbles
 * in the same fixed pattern as the web — one left + one right, then one in the
 * centre, again and again — over the still wallpaper (pearl white with thin purple heart doodles, [Wallpaper]).
 *  - Moves up on its own (25 per second) each time Explore is opened; once you
 *    touch it, it stops drifting until Explore is opened again.
 *  - Drag up or down; it glides on after you let go (the phone's own fling).
 *  - Its own clock ([clock]) moves it: one tick per screen frame while Explore shows.
 *  - A bubble shrinks and fades as it reaches the top, fades in at the bottom.
 *  - Tap a bubble → [onTap].
 * Every frame is drawn by the phone's graphics chip; photos are decoded once,
 * already round and at the size they are shown.
 */
@SuppressLint("ViewConstructor")
class FeedView(ctx: Context, private val onTap: (Person) -> Unit) : View(ctx) {

    // ── geometry (in dp) ── the pattern is the web's; the sizes are the native app's own since 20 Nov (bigger bubbles:
    // the owner wanted the "Moti · Dil" design in the native app only, the web's Explore keeps its older look)
    private val ROW = Ui.dp(125f)
    private val TOPPAD = Ui.dp(30f)
    private val SLOT_X = floatArrayOf(0.24f, 0.76f, 0.50f)
    private val AVA = Ui.dp(84f)       // bubble size (20 Nov: 66 → 84)
    private val AHEAD = Ui.dp(600f)
    private val BEHIND = Ui.dp(220f)
    private val AUTO = Ui.dp(25f)      // per second, upward
    private val BLOCK_W = Ui.dp(128f)
    private val NAME_H = Ui.dp(23f)    // name line 18 + gap 5
    private val BLOCK_H = Ui.dp(150f)
    private val TAG_GAP = Ui.dp(5f)    // a city never comes closer than this to the name pill beside it
    private val HALO_R = Ui.dp(88f)    // the soft patch behind a bubble
    private val DOT = Ui.dp(7f)        // the blinking dot after the name…
    private val DOT_GAP = Ui.dp(6f)    // …and its gap
    private val WARM_AHEAD = 14
    val faceSize = Ui.dpi(76f)

    // ── state ──
    private var pool: List<Person> = emptyList()
    private var byId: Map<String, Person> = emptyMap()
    private var inPool: Set<String> = emptySet()
    private val assigned = LinkedHashMap<Int, String>()
    private val born = HashMap<Int, Long>()       // slot → when it was first shown (pulse phase)
    private val tapAt = HashMap<Int, Long>()
    private var cursor = 0
    private var y = 0f
    private var auto = true
    private var dragging = false
    private var downY = 0f
    private var lastY = 0f
    private var moved = 0f
    private val scroller = OverScroller(ctx)
    private var tracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity.toFloat()

    /** A sheet, dialog or call screen is on top: no drift. */
    var blocked = false
    /** Explore is on screen: the feed's clock runs (drift, glide, the pulsing rings). */
    var running = false
        set(v) { field = v; if (v) startClock() else stopClock() }

    /** Explore was opened again (tab tap): the drift plays once more. */
    fun reopened() { auto = true; scroller.forceFinished(true); running = true; startClock() }

    /** Explore is (still) on screen: make sure the clock runs, without starting the drift again. */
    fun keepRunning() { if (!running) running = true else startClock() }

    // ── the feed's own clock ──
    // One tick per screen frame while Explore shows: each tick moves the bubbles (the drift, or the glide after
    // a swipe) and asks for one new picture. Until 19 Nov the next frame was only asked for from inside the
    // drawing itself, and while a page move ran it waited for one "the move has ended" call to start again. When
    // that call didn't come (or the drawing wasn't asked for), nothing asked for a frame any more: the bubbles
    // stood still — no drift, no glide after a swipe — until the app was closed. The clock asks for its next
    // tick itself, every frame, whatever happens around it, so the feed can no longer stop on its own.
    private var clockOn = false
    private var lastTick = 0L
    private val clock = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!clockOn) return
            if (!running || !isAttachedToWindow) { clockOn = false; return }
            tick(frameTimeNanos / 1_000_000L)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun startClock() {
        if (!running || clockOn || !isAttachedToWindow) return
        clockOn = true
        lastTick = 0L
        android.view.Choreographer.getInstance().postFrameCallback(clock)
    }
    private fun stopClock() {
        clockOn = false
        android.view.Choreographer.getInstance().removeFrameCallback(clock)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startClock() }
    override fun onDetachedFromWindow() { stopClock(); super.onDetachedFromWindow() }

    /** one frame of the clock: move (unless pages are moving: then the bubbles hold still), then a new picture */
    private fun tick(nowMs: Long) {
        val dt = if (lastTick == 0L) 0f else min(0.05f, (nowMs - lastTick) / 1000f)
        lastTick = nowMs
        // While pages are moving the bubbles hold still: for the move this page is one finished picture, and
        // drawing the bubbles again in each of its frames made the slide into Explore stutter (19 Nov). The
        // clock keeps ticking meanwhile, so they go on by themselves on the first frame after the move.
        if (PageFx.running && !dragging) return
        if (scroller.computeScrollOffset()) y = scroller.currY.toFloat()
        else if (auto && !dragging && !blocked && pool.isNotEmpty()) y += AUTO * dt
        invalidate()
    }

    fun setPool(list: List<Person>, all: List<Person>, reset: Boolean) {
        pool = list
        byId = all.associateBy { it.id }
        inPool = list.map { it.id }.toHashSet()
        if (reset) { assigned.clear(); born.clear(); cursor = 0; y = 0f; scroller.forceFinished(true) }
        invalidate()
    }

    // ── paints ──
    private val INK = Ui.c("#2B0B57")
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = Ui.dp(14f); color = INK }
    private val pAge = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.regular; textSize = Ui.dp(14f); color = INK }
    private val pLoc = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = Ui.dp(12.5f); color = Ui.c("#5B3E8C") }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // the soft patch of the wallpaper's ground colour behind a bubble: 70 % in the middle, fading out to its edge
    // (it calms the doodles around the name and the photo)
    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val clear = Wallpaper.GROUND and 0x00FFFFFF
        val full = clear or (179 shl 24)
        shader = RadialGradient(0f, 0f, HALO_R, intArrayOf(full, full, clear), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
    }
    private val GREEN = Ui.c("#22c55e"); private val RED = Ui.c("#ef4444"); private val GREY = Ui.c("#9A8FB8")
    private val VIOLET = Ui.c("#7C3AED"); private val DOT_OFF = Ui.c("#B9AED3")
    private val RING_LINE = Color.argb(77, 91, 33, 182)   // the hairline around the photo's white ring: rgba(91,33,182,.30)
    private val RING_GLOW = Color.argb(18, 91, 33, 182)   // …and the soft glow outside it: rgba(91,33,182,.07)
    private val PILL = Color.argb(209, 255, 255, 255)     // the city tag: rgba(255,255,255,.82)
    private val PILL_LINE = Color.argb(61, 91, 33, 182)   // …and its outline: rgba(91,33,182,.24)

    private val phonePath: Path = PathParser.createPathFromPathData("M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2c.3-.3.7-.4 1-.2 1.1.4 2.3.6 3.6.6.6 0 1 .4 1 1V20c0 .6-.4 1-1 1A17 17 0 0 1 3 4c0-.6.4-1 1-1h3.5c.6 0 1 .4 1 1 0 1.3.2 2.5.6 3.6.1.3 0 .7-.2 1z")
    private val camPath: Path = PathParser.createPathFromPathData("M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM16 10l6-4v12l-6-4z")
    private val vfStar: Path = PathParser.createPathFromPathData("m12 1 2.6 2.1 3.3-.3.9 3.2 2.9 1.6-1 3.2 1 3.2-2.9 1.6-.9 3.2-3.3-.3L12 23l-2.6-2.1-3.3.3-.9-3.2-2.9-1.6 1-3.2-1-3.2 2.9-1.6.9-3.2 3.3.3z")
    private val vfTick: Path = PathParser.createPathFromPathData("m8 12 3 3 5-6")

    // ── slots ──
    private fun slotX(v: Int) = SLOT_X[Math.floorMod(v, 3)] * width
    private fun slotY(v: Int): Float {
        val g = Math.floorDiv(v, 3); val s = Math.floorMod(v, 3)
        return TOPPAD + (2 * g + (if (s == 2) 1 else 0)) * ROW
    }

    private fun visibleSlots(): List<Int> {
        val lo = y - BEHIND; val hi = y + height + AHEAD
        val r0 = floor((lo - TOPPAD) / ROW).toInt() - 1
        val r1 = ceil((hi - TOPPAD) / ROW).toInt() + 1
        val out = ArrayList<Int>(24)
        for (r in r0..r1) {
            val g = Math.floorDiv(r, 2)
            if (Math.floorMod(r, 2) == 0) { out.add(3 * g); out.add(3 * g + 1) } else out.add(3 * g + 2)
        }
        return out
    }

    private fun pickFor(v: Int, onScreen: MutableSet<String>): Person? {
        if (pool.isEmpty()) return null
        assigned[v]?.let { id -> byId[id]?.let { return it } }
        var p: Person? = null
        for (i in pool.indices) {
            val c = pool[(cursor + i) % pool.size]
            if (!onScreen.contains(c.id) || pool.size <= onScreen.size) { p = c; cursor = (cursor + i + 1) % pool.size; break }
        }
        if (p == null) { p = pool[cursor % pool.size]; cursor = (cursor + 1) % pool.size }
        assigned[v] = p.id
        if (assigned.size > 400) assigned.remove(assigned.keys.first())
        warmAhead()
        return p
    }

    private val asked = HashSet<String>()   // photos being fetched right now

    private fun warmAhead() {
        for (i in 0 until min(WARM_AHEAD, pool.size)) {
            val p = pool[(cursor + i) % pool.size]
            val urls = p.photoUrls
            if (urls.isEmpty() || Photos.get(urls[0], faceSize) != null) continue
            val k = urls[0]
            if (asked.add(k)) Photos.load(urls, faceSize) { asked.remove(k) }
        }
    }

    private fun faceOf(p: Person): Bitmap {
        val urls = p.photoUrls
        if (urls.isNotEmpty()) {
            for (u in urls) Photos.get(u, faceSize)?.let { return it }
            val k = urls[0]
            if (urls.any { !Photos.hasFailed(it) } && asked.add(k)) Photos.load(urls, faceSize) { asked.remove(k); if (it != null) invalidate() }
        }
        return AvatarArt.forPerson(p, faceSize)
    }

    // ── frame ──
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        // (the bubbles are moved by the clock and by the finger; drawing only draws where they are)

        // the wallpaper stands still behind the moving bubbles (plain ground colour until its picture is there)
        val wall = Wallpaper.paint
        if (wall != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wall) else canvas.drawColor(Wallpaper.GROUND)

        val slots = visibleSlots()
        val onScreen = HashSet<String>()
        for (v in slots) assigned[v]?.let { onScreen.add(it) }
        // nearest to the middle of the screen first (same order as the web)
        val mid = y + height / 2f
        val order = slots.sortedBy { abs(slotY(it) - mid) }
        for (v in order) {
            if (!assigned.containsKey(v) && pool.isNotEmpty()) pickFor(v, onScreen)?.let { onScreen.add(it.id) }
        }
        // first the soft patches of all bubbles, then the bubbles themselves: a patch never lies over a neighbour's name or city
        for (pass in 0..1) for (v in slots) {
            val id = assigned[v] ?: continue
            val p = byId[id] ?: continue
            val top = slotY(v) - y
            if (top > height + Ui.dp(10f) || top < -BLOCK_H) continue
            if (pass == 0) { drawHalo(canvas, slotX(v), top); continue }
            if (!born.containsKey(v)) born[v] = now
            drawBubble(canvas, v, p, slotX(v), top, now)
        }
    }

    /** 1 = fully shown; a bubble shrinks and fades as it reaches the top, and fades in at the bottom */
    private fun fade(top: Float): Float {
        val kTop = ((top + Ui.dp(40f)) / Ui.dp(120f)).coerceIn(0f, 1f)
        val kBot = ((height - top) / Ui.dp(70f)).coerceIn(0f, 1f)
        return min(kTop, kBot)
    }

    private fun drawHalo(c: Canvas, cx: Float, top: Float) {
        val k = fade(top)
        if (k <= 0f) return
        val sc = 0.72f + 0.28f * k
        c.save()
        c.translate(cx, top)
        if (sc < 1f) c.scale(sc, sc, 0f, BLOCK_H * 0.4f)
        c.translate(0f, NAME_H + AVA / 2f)
        pHalo.alpha = (k * 255).roundToInt()
        c.drawCircle(0f, 0f, HALO_R, pHalo)
        c.restore()
    }

    // ── the city (top, a little right of the middle) and the name pill (under the photo) never touch ──
    // Rows are 125 apart and a bubble is 150 tall, so the name pill of a bubble and the city of a bubble in the next
    // row are on the same line. The name pill takes the width its text needs (cut with … only past NAME_MAX); the city
    // gives way: it is cut (…) so that it keeps TAG_GAP from every name pill beside it (never narrower than CITY_MIN).
    private val CITY_O = Ui.dp(14f)      // the city's middle sits this far right of the bubble's middle (owner, 20 Nov)
    private val CITY_MIN = Ui.dp(44f)
    private val PIN_W = Ui.dp(10f); private val PIN_H = Ui.dp(12.5f); private val PIN_GAP = Ui.dp(3f)
    private val PIN_RED = Ui.c("#EF4444")
    /** a location pin in a 24 × 30 box: round head, pointed foot */
    private val pinPath = Path().apply {
        moveTo(12f, 30f)
        cubicTo(12f, 30f, 1f, 18.5f, 1f, 11.5f)
        cubicTo(1f, 5.2f, 5.9f, 0.5f, 12f, 0.5f)
        cubicTo(18.1f, 0.5f, 23f, 5.2f, 23f, 11.5f)
        cubicTo(23f, 18.5f, 12f, 30f, 12f, 30f)
        close()
    }
    private val PILL_PAD = Ui.dp(12f)    // the name pill's side padding (+ 1 + 1 outline)
    private val nameWidths = HashMap<Person, Float>()   // (a new list of people brings new Person objects: the old ones go when it is emptied)

    /** the widest a name pill may be: room for the shortest city beside it on the tighter side */
    private fun pillMax(): Float = min(BLOCK_W, 2f * (abs(SLOT_X[2] - SLOT_X[0]) * width - CITY_O - CITY_MIN / 2f - TAG_GAP))

    /** how wide the name pill of the bubble in slot [u] is drawn (0 = nobody there) */
    private fun pillWidthAt(u: Int): Float {
        val p = assigned[u]?.let { byId[it] } ?: return 0f
        val full = nameWidths[p] ?: run {
            if (nameWidths.size > 600) nameWidths.clear()
            val w = pText.measureText(p.firstName.ifBlank { "Someone" }) + (if (p.age > 0) pAge.measureText(", ${p.age}") else 0f) + DOT_GAP + DOT + 2 * PILL_PAD + Ui.dp(2f)
            nameWidths[p] = w
            w
        }
        return min(full, pillMax())
    }

    /** the widest the city of slot [u] may be: it keeps TAG_GAP from the name pills of the row above it */
    private fun cityRoom(u: Int): Float {
        val g = Math.floorDiv(u, 3)
        val above = if (Math.floorMod(u, 3) == 2) intArrayOf(3 * g, 3 * g + 1) else intArrayOf(3 * g - 1)
        val cx = slotX(u) + CITY_O
        var half = BLOCK_W / 2f
        for (v in above) {
            val pw = pillWidthAt(v)
            if (pw <= 0f) continue
            half = min(half, abs(cx - slotX(v)) - pw / 2f - TAG_GAP)
        }
        return max(CITY_MIN, 2f * half)
    }

    private fun drawBubble(c: Canvas, v: Int, p: Person, cx: Float, top: Float, now: Long) {
        val gone = !inPool.contains(p.id)
        val busy = !gone && p.busy
        // fade + shrink at the top, fade in at the bottom
        val k = fade(top)
        if (k <= 0f) return
        val save = if (k < 1f) c.saveLayerAlpha(cx - BLOCK_W, top - Ui.dp(10f), cx + BLOCK_W, top + BLOCK_H + Ui.dp(10f), (k * 255).roundToInt())
        else c.save()
        val sc = 0.72f + 0.28f * k
        c.translate(cx, top)
        if (sc < 1f) c.scale(sc, sc, 0f, BLOCK_H * 0.4f)

        // ── the city: top, a little right of the middle (owner, 20 Nov) ──
        // a small red location pin in front of the city (owner, 20 Nov)
        var place = p.place.ifBlank { "India" }
        val room = cityRoom(v) - PIN_W - PIN_GAP
        if (pLoc.measureText(place) > room) {
            while (place.length > 1 && pLoc.measureText("$place…") > room) place = place.dropLast(1)
            place = "$place…"
        }
        val cw = PIN_W + PIN_GAP + pLoc.measureText(place)
        val cx0 = CITY_O - cw / 2f
        c.save()
        c.translate(cx0, Ui.dp(13f) - PIN_H + Ui.dp(1f))
        c.scale(PIN_W / 24f, PIN_H / 30f)
        pFill.color = PIN_RED; c.drawPath(pinPath, pFill)
        pFill.color = Color.WHITE; c.drawCircle(12f, 11.5f, 4.2f, pFill)
        c.restore()
        c.drawText(place, cx0 + PIN_W + PIN_GAP, Ui.dp(13f), pLoc)

        // ── avatar ──
        val cy = NAME_H + AVA / 2f
        c.save()
        c.translate(0f, cy)
        tapAt[v]?.let { t ->
            val f = (now - t) / 350f
            if (f >= 1f) tapAt.remove(v) else { val s = 1f - 0.12f * (1f - abs(1f - 2f * f)); c.scale(s, s) }
        }
        val r = AVA / 2f
        // a soft glow and a hairline around the ring
        pFill.color = RING_GLOW; c.drawCircle(0f, 0f, r + Ui.dp(6f), pFill)
        pFill.color = RING_LINE; c.drawCircle(0f, 0f, r + Ui.dp(1f), pFill)
        // the ring: white when free, red when busy, grey when gone
        pFill.color = if (gone) GREY else if (busy) RED else Color.WHITE
        c.drawCircle(0f, 0f, r, pFill)
        // (no pulsing circle around the photo any more: owner, 20 Nov — the bubbles only move)
        val fr = r - Ui.dp(4f)
        val face = faceOf(p)
        tmpRect.set(-fr, -fr, fr, fr)
        c.drawBitmap(face, null, tmpRect, pBmp)

        // call mark at the bottom of the photo: purple camera = video, green phone = voice
        val video = p.callType == "video"
        val br = Ui.dp(15f); val by = r + Ui.dp(13f) - br
        pFill.color = Color.WHITE; c.drawCircle(0f, by, br, pFill)
        pFill.color = if (gone) GREY else if (busy) RED else if (video) VIOLET else GREEN
        c.drawCircle(0f, by, br - Ui.dp(2.5f), pFill)
        c.save()
        val icon = if (video) Ui.dp(14f) else Ui.dp(13f)
        c.translate(-icon / 2f, by - icon / 2f)
        c.scale(icon / 24f, icon / 24f)
        pFill.color = Color.WHITE
        c.drawPath(if (video) camPath else phonePath, pFill)
        c.restore()

        // verified mark
        if (p.verified) {
            val s = Ui.dp(21f)
            c.save()
            c.translate(r + Ui.dp(2f) - s, r - Ui.dp(12f) - s)
            c.scale(s / 24f, s / 24f)
            pFill.color = Ui.c("#3b82f6"); c.drawPath(vfStar, pFill)
            pStroke.color = Color.WHITE; pStroke.alpha = 255; pStroke.strokeWidth = 2.4f; pStroke.strokeCap = Paint.Cap.ROUND
            c.drawPath(vfTick, pStroke)
            pStroke.strokeCap = Paint.Cap.BUTT
            c.restore()
        }
        c.restore()

        // ── name, age and the blinking dot, in the pill under the photo (owner, 20 Nov: where the city was) ──
        val name = p.firstName.ifBlank { "Someone" }
        val age = if (p.age > 0) ", ${p.age}" else ""
        val dot = DOT; val gap = DOT_GAP
        var nw = pText.measureText(name); val aw = pAge.measureText(age)
        val maxText = pillWidthAt(v).takeIf { it > 0f }?.let { it - 2 * PILL_PAD - Ui.dp(2f) - dot - gap } ?: (BLOCK_W - 2 * PILL_PAD)
        var shown = name
        if (nw + aw > maxText) {
            while (shown.length > 1 && pText.measureText("$shown…") + aw > maxText) shown = shown.dropLast(1)
            shown = "$shown…"; nw = pText.measureText(shown)
        }
        val tw = nw + aw + gap + dot
        val pillTop = NAME_H + AVA + Ui.dp(18f); val pillH = Ui.dp(25f)
        tmpRect.set(-tw / 2f - PILL_PAD - Ui.dp(1f), pillTop, tw / 2f + PILL_PAD + Ui.dp(1f), pillTop + pillH)
        pFill.color = PILL
        c.drawRoundRect(tmpRect, pillH / 2f, pillH / 2f, pFill)
        pStroke.color = PILL_LINE; pStroke.strokeWidth = Ui.dp(1f)
        tmpRect.inset(Ui.dp(0.5f), Ui.dp(0.5f))
        c.drawRoundRect(tmpRect, pillH / 2f, pillH / 2f, pStroke)
        var x = -tw / 2f
        val base = pillTop + Ui.dp(17.3f)
        c.drawText(shown, x, base, pText); x += nw
        if (age.isNotEmpty()) { c.drawText(age, x, base, pAge); x += aw }
        x += gap
        pFill.color = if (gone) DOT_OFF else if (busy) RED else GREEN
        if (!gone) pFill.alpha = (255 * blink(now)).roundToInt()
        c.drawCircle(x + dot / 2f, pillTop + pillH / 2f, dot / 2f, pFill)
        pFill.alpha = 255

        c.restoreToCount(save)
    }

    private val tmpRect = RectF()

    // the wallpaper's picture is drawn in the background the first time; the feed shows it as soon as it is there
    init { Wallpaper.load(ctx) { invalidate() } }

    private fun blink(now: Long): Float {
        // opacity 1 → .25 → 1 every 1.1 s (ease-in-out)
        val f = (now % 1100L) / 1100f
        return (0.625f + 0.375f * cos(2 * PI * f)).toFloat()
    }

    // ── touch: drag, fling, tap ──
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                auto = false
                scroller.forceFinished(true)
                // a finger on the feed means it is on screen: its clock must run (it can never be left stopped)
                if (isShown && windowVisibility == VISIBLE) keepRunning()
                downY = e.y; lastY = e.y; moved = 0f; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - lastY
                lastY = e.y
                moved += abs(dy)
                if (!dragging && abs(e.y - downY) > slop) dragging = true
                if (dragging) { y -= dy; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    hit(e.x, e.y)
                } else {
                    tracker?.computeCurrentVelocity(1000, maxFling)
                    val vy = tracker?.yVelocity ?: 0f
                    scroller.fling(0, y.roundToInt(), 0, (-vy).roundToInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                }
                dragging = false
                tracker?.recycle(); tracker = null
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }

    private fun hit(px: Float, py: Float) {
        for (v in visibleSlots()) {
            val id = assigned[v] ?: continue
            val p = byId[id] ?: continue
            val cx = slotX(v); val top = slotY(v) - y
            if (px >= cx - BLOCK_W / 2 && px <= cx + BLOCK_W / 2 && py >= top && py <= top + BLOCK_H) {
                tapAt[v] = SystemClock.uptimeMillis()
                invalidate()
                onTap(p)
                return
            }
        }
    }
}

/**
 * Explore's wallpaper (design "#83-1 Moti · Dil", chosen by the owner on 20 Nov): pearl white with thin purple
 * heart doodles, for the native app only (the web's Explore keeps its older purple background). One picture of
 * 360 × 420 (assets/explore/moti-dil.svg), drawn once, off the main thread, and then repeated across the feed.
 */
object Wallpaper {
    val GROUND: Int = Ui.c("#F8F4FC")
    private const val FILE = "explore/moti-dil.svg"

    /** the repeating picture; null until it has been drawn (the plain ground colour shows meanwhile) */
    var paint: Paint? = null
        private set
    private var asked = false
    private val waiting = ArrayList<() -> Unit>()

    /** Draws the picture if that has not been done yet; [ready] is called (on the main thread) once it is there. */
    fun load(ctx: Context, ready: () -> Unit) {
        if (paint != null) return
        waiting.add(ready)
        if (asked) return
        asked = true
        val assets = ctx.applicationContext.assets
        val w = Ui.dpi(360f); val h = Ui.dpi(420f)
        Api.async({
            try {
                val xml = assets.open(FILE).bufferedReader().use { it.readText() }
                val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(tile)
                c.drawColor(GROUND)
                Svg.once(xml).draw(c, 0f, 0f, w.toFloat(), h.toFloat(), meet = false)
                tile.setHasAlpha(false)   // nothing shows through it: the phone draws it without blending
                Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
            } catch (e: Throwable) {
                null   // no picture (not enough memory, a broken file): the plain ground colour stays
            }
        }) { made ->
            paint = made
            val call = ArrayList(waiting)
            waiting.clear()
            if (made != null) call.forEach { it() }
        }
    }
}
