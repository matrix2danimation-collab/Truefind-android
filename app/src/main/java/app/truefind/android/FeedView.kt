package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
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
 * centre, again and again — over the still purple background with doodles.
 *  - Moves up on its own (25 per second) each time Explore is opened; once you
 *    touch it, it stops drifting until Explore is opened again.
 *  - Drag up or down; it glides on after you let go (the phone's own fling).
 *  - A bubble shrinks and fades as it reaches the top, fades in at the bottom.
 *  - Tap a bubble → [onTap].
 * Every frame is drawn by the phone's graphics chip; photos are decoded once,
 * already round and at the size they are shown.
 */
@SuppressLint("ViewConstructor")
class FeedView(ctx: Context, private val onTap: (Person) -> Unit) : View(ctx) {

    // ── geometry (same numbers as the web, in dp) ──
    private val ROW = Ui.dp(100f)
    private val TOPPAD = Ui.dp(30f)
    private val SLOT_X = floatArrayOf(0.24f, 0.76f, 0.50f)
    private val AVA = Ui.dp(66f)
    private val AHEAD = Ui.dp(600f)
    private val BEHIND = Ui.dp(220f)
    private val AUTO = Ui.dp(25f)      // per second, upward
    private val BLOCK_W = Ui.dp(96f)
    private val NAME_H = Ui.dp(22f)    // name line 16 + gap 6
    private val BLOCK_H = Ui.dp(124f)
    private val WARM_AHEAD = 14
    val faceSize = Ui.dpi(56f)

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
    private var lastFrame = 0L
    private val scroller = OverScroller(ctx)
    private var tracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity.toFloat()

    /** A sheet, dialog or call screen is on top: no drift. */
    var blocked = false
    /** Explore is on screen: draw frames. */
    var running = false
        set(v) { field = v; if (v) { lastFrame = 0L; postInvalidateOnAnimation() } }

    /** Explore was opened again (tab tap): the drift plays once more. */
    fun reopened() { auto = true; scroller.forceFinished(true); running = true }

    fun setPool(list: List<Person>, all: List<Person>, reset: Boolean) {
        pool = list
        byId = all.associateBy { it.id }
        inPool = list.map { it.id }.toHashSet()
        if (reset) { assigned.clear(); born.clear(); cursor = 0; y = 0f; scroller.forceFinished(true) }
        invalidate()
    }

    // ── paints ──
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.bold; textSize = Ui.dp(13f); color = Color.WHITE
        setShadowLayer(Ui.dp(2f), 0f, Ui.dp(1f), Ui.ca("#1E0646", 0.55f))
    }
    private val pAge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.regular; textSize = Ui.dp(13f); color = Ui.c("#E6D9FF")
        setShadowLayer(Ui.dp(2f), 0f, Ui.dp(1f), Ui.ca("#1E0646", 0.55f))
    }
    private val pLoc = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = Ui.dp(10.5f); color = Ui.c("#F6EEFF") }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringFree = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // conic-gradient(from 200deg, #C4B5FD, #F5D0FE, #FBCFE8, #C4B5FD)
        shader = SweepGradient(0f, 0f, intArrayOf(Ui.c("#C4B5FD"), Ui.c("#F5D0FE"), Ui.c("#FBCFE8"), Ui.c("#C4B5FD")), null).apply {
            setLocalMatrix(Matrix().apply { setRotate(110f) })
        }
    }
    private val GREEN = Ui.c("#22c55e"); private val RED = Ui.c("#ef4444"); private val GREY = Ui.c("#9A8FB8")
    private val VIOLET = Ui.c("#7C3AED"); private val DOT_OFF = Ui.c("#B9AED3")

    private val phonePath: Path = PathParser.createPathFromPathData("M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2c.3-.3.7-.4 1-.2 1.1.4 2.3.6 3.6.6.6 0 1 .4 1 1V20c0 .6-.4 1-1 1A17 17 0 0 1 3 4c0-.6.4-1 1-1h3.5c.6 0 1 .4 1 1 0 1.3.2 2.5.6 3.6.1.3 0 .7-.2 1z")
    private val camPath: Path = PathParser.createPathFromPathData("M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM16 10l6-4v12l-6-4z")
    private val vfStar: Path = PathParser.createPathFromPathData("m12 1 2.6 2.1 3.3-.3.9 3.2 2.9 1.6-1 3.2 1 3.2-2.9 1.6-.9 3.2-3.3-.3L12 23l-2.6-2.1-3.3.3-.9-3.2-2.9-1.6 1-3.2-1-3.2 2.9-1.6.9-3.2 3.3.3z")
    private val vfTick: Path = PathParser.createPathFromPathData("m8 12 3 3 5-6")
    private val shadowBmp: Bitmap by lazy { makeShadow() }

    private var bg: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w > 0 && h > 0 && (bg == null || bg!!.width != w || bg!!.height != h)) {
            bg?.recycle()
            bg = Background.make(w, h)
        }
    }

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
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now

        if (scroller.computeScrollOffset()) {
            y = scroller.currY.toFloat()
        } else if (auto && !dragging && !blocked && pool.isNotEmpty() && running) {
            y += AUTO * dt
        }

        bg?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        val slots = visibleSlots()
        val onScreen = HashSet<String>()
        for (v in slots) assigned[v]?.let { onScreen.add(it) }
        // nearest to the middle of the screen first (same order as the web)
        val mid = y + height / 2f
        val order = slots.sortedBy { abs(slotY(it) - mid) }
        for (v in order) {
            if (!assigned.containsKey(v) && pool.isNotEmpty()) pickFor(v, onScreen)?.let { onScreen.add(it.id) }
        }
        for (v in slots) {
            val id = assigned[v] ?: continue
            val p = byId[id] ?: continue
            val top = slotY(v) - y
            if (top > height + Ui.dp(10f) || top < -BLOCK_H) continue
            if (!born.containsKey(v)) born[v] = now
            drawBubble(canvas, v, p, slotX(v), top, now)
        }
        // While pages are moving the bubbles hold still: for the move this page is one finished picture, and
        // drawing the bubbles again in each of its frames made the slide into Explore stutter on the owner's
        // phone (19 Nov: a fifth of those frames late). They go on right after the move.
        if (running) {
            if (!PageFx.running) postInvalidateOnAnimation()
            else if (!waiting) { waiting = true; PageFx.whenStill { waiting = false; if (running) postInvalidateOnAnimation() } }
        }
    }
    private var waiting = false

    private fun drawBubble(c: Canvas, v: Int, p: Person, cx: Float, top: Float, now: Long) {
        val gone = !inPool.contains(p.id)
        val busy = !gone && p.busy
        // fade + shrink at the top, fade in at the bottom
        val kTop = ((top + Ui.dp(40f)) / Ui.dp(120f)).coerceIn(0f, 1f)
        val kBot = ((height - top) / Ui.dp(70f)).coerceIn(0f, 1f)
        val k = min(kTop, kBot)
        if (k <= 0f) return
        val save = if (k < 1f) c.saveLayerAlpha(cx - BLOCK_W, top - Ui.dp(10f), cx + BLOCK_W, top + BLOCK_H + Ui.dp(10f), (k * 255).roundToInt())
        else c.save()
        val sc = 0.72f + 0.28f * k
        c.translate(cx, top)
        if (sc < 1f) c.scale(sc, sc, 0f, BLOCK_H * 0.4f)

        // ── name, age and the blinking dot ──
        val name = p.firstName.ifBlank { "Someone" }
        val age = if (p.age > 0) ", ${p.age}" else ""
        val dot = Ui.dp(7f); val gap = Ui.dp(5f)
        var nw = pText.measureText(name); val aw = pAge.measureText(age)
        val maxW = BLOCK_W - dot - gap
        var shown = name
        if (nw + aw > maxW) {
            while (shown.length > 1 && pText.measureText("$shown…") + aw > maxW) shown = shown.dropLast(1)
            shown = "$shown…"; nw = pText.measureText(shown)
        }
        val total = nw + aw + gap + dot
        var x = -total / 2f
        val base = Ui.dp(12.5f)
        c.drawText(shown, x, base, pText); x += nw
        if (age.isNotEmpty()) { c.drawText(age, x, base, pAge); x += aw }
        x += gap
        pFill.color = if (gone) DOT_OFF else if (busy) RED else GREEN
        if (!gone) pFill.alpha = (255 * blink(now)).roundToInt()
        c.drawCircle(x + dot / 2f, Ui.dp(8.5f), dot / 2f, pFill)
        pFill.alpha = 255

        // ── avatar ──
        val cy = NAME_H + AVA / 2f
        c.save()
        c.translate(0f, cy)
        tapAt[v]?.let { t ->
            val f = (now - t) / 350f
            if (f >= 1f) tapAt.remove(v) else { val s = 1f - 0.12f * (1f - abs(1f - 2f * f)); c.scale(s, s) }
        }
        // soft shadow under the photo
        c.drawBitmap(shadowBmp, -shadowBmp.width / 2f, -shadowBmp.height / 2f + Ui.dp(8f), null)
        val r = AVA / 2f
        if (gone) { pFill.color = GREY; c.drawCircle(0f, 0f, r, pFill) }
        else if (busy) { pFill.color = RED; c.drawCircle(0f, 0f, r, pFill) }
        else {
            c.drawCircle(0f, 0f, r, ringFree)
            // ring pulse
            val ph = ((now - (born[v] ?: now)) % 2400L) / 2400f
            val e = 1f - (1f - ph) * (1f - ph)
            pStroke.color = Ui.c("#DDC8FF"); pStroke.strokeWidth = Ui.dp(2f)
            pStroke.alpha = ((0.8f * 0.85f * (1f - e)) * 255).roundToInt().coerceIn(0, 255)
            c.drawCircle(0f, 0f, (r + Ui.dp(3f) - Ui.dp(1f)) * (1f + 0.45f * e), pStroke)
        }
        pFill.color = Color.WHITE
        c.drawCircle(0f, 0f, r - Ui.dp(3f), pFill)
        val fr = r - Ui.dp(5f)
        val face = faceOf(p)
        tmpRect.set(-fr, -fr, fr, fr)
        c.drawBitmap(face, null, tmpRect, pBmp)

        // call mark at the bottom of the photo: purple camera = video, green phone = voice
        val video = p.callType == "video"
        val br = Ui.dp(11.5f); val by = r + Ui.dp(11f) - br
        pFill.color = Color.WHITE; c.drawCircle(0f, by, br, pFill)
        pFill.color = if (gone) GREY else if (busy) RED else if (video) VIOLET else GREEN
        c.drawCircle(0f, by, br - Ui.dp(2f), pFill)
        c.save()
        val icon = if (video) Ui.dp(12f) else Ui.dp(11f)
        c.translate(-icon / 2f, by - icon / 2f)
        c.scale(icon / 24f, icon / 24f)
        pFill.color = Color.WHITE
        c.drawPath(if (video) camPath else phonePath, pFill)
        c.restore()

        // verified mark
        if (p.verified) {
            val s = Ui.dp(17f)
            c.save()
            c.translate(r + Ui.dp(2f) - s, r - Ui.dp(6f) - s)
            c.scale(s / 24f, s / 24f)
            pFill.color = Ui.c("#3b82f6"); c.drawPath(vfStar, pFill)
            pStroke.color = Color.WHITE; pStroke.alpha = 255; pStroke.strokeWidth = 2.4f; pStroke.strokeCap = Paint.Cap.ROUND
            c.drawPath(vfTick, pStroke)
            pStroke.strokeCap = Paint.Cap.BUTT
            c.restore()
        }
        c.restore()

        // ── city pill under the photo ──
        var place = p.place.ifBlank { "India" }
        val padX = Ui.dp(9f); val maxText = BLOCK_W - 2 * padX - Ui.dp(2f)
        if (pLoc.measureText(place) > maxText) {
            while (place.length > 1 && pLoc.measureText("$place…") > maxText) place = place.dropLast(1)
            place = "$place…"
        }
        val tw = pLoc.measureText(place)
        val pillTop = NAME_H + AVA + Ui.dp(15f); val pillH = Ui.dp(20f)
        tmpRect.set(-tw / 2f - padX - Ui.dp(1f), pillTop, tw / 2f + padX + Ui.dp(1f), pillTop + pillH)
        pFill.color = Color.argb(36, 255, 255, 255)
        c.drawRoundRect(tmpRect, Ui.dp(10f), Ui.dp(10f), pFill)
        pStroke.color = Color.argb(51, 255, 255, 255); pStroke.strokeWidth = Ui.dp(1f)
        tmpRect.inset(Ui.dp(0.5f), Ui.dp(0.5f))
        c.drawRoundRect(tmpRect, Ui.dp(10f), Ui.dp(10f), pStroke)
        c.drawText(place, -tw / 2f, pillTop + Ui.dp(14f), pLoc)

        c.restoreToCount(save)
    }

    private val tmpRect = RectF()
    private fun blink(now: Long): Float {
        // opacity 1 → .25 → 1 every 1.1 s (ease-in-out)
        val f = (now % 1100L) / 1100f
        return (0.625f + 0.375f * cos(2 * PI * f)).toFloat()
    }

    private fun makeShadow(): Bitmap {
        // box-shadow: 0 8px 16px -10px rgba(24,4,60,.8)
        val rad = AVA / 2f - Ui.dp(10f)
        val blur = Ui.dp(8f)
        val size = ((rad + blur * 2) * 2).toInt() + 2
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.ca("#18043C", 0.8f)
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(b).drawCircle(size / 2f, size / 2f, rad, p)
        return b
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

/** The still background of the feed, drawn once per screen size. */
object Background {
    fun make(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val W = w.toFloat(); val H = h.toFloat()
        // linear-gradient(180deg, #5B21B6 0%, #4C1D95 48%, #40148A 100%)
        p.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(Ui.c("#5B21B6"), Ui.c("#4C1D95"), Ui.c("#40148A")), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        // the three soft glows (drawn bottom layer first, like CSS)
        glow(c, W, H, -0.08f, 0.56f, 0.70f, 0.40f, Ui.ca("#C048D6", 0.30f), 0.70f)
        glow(c, W, H, 1.08f, 0.78f, 0.75f, 0.42f, Ui.ca("#8B5CF6", 0.55f), 0.70f)
        glow(c, W, H, 0.50f, 0f, 1.20f, 0.50f, Ui.ca("#BE96FF", 0.42f), 0.65f)
        // fine grain: white dots every 16, 9 % strong
        p.shader = null; p.color = Color.argb(23, 255, 255, 255)
        val step = Ui.dp(16f); val gr = Ui.dp(0.6f)
        var gy = 0f
        while (gy < H) { var gx = 0f; while (gx < W) { c.drawCircle(gx, gy, gr, p); gx += step }; gy += step }
        doodles(c, W, H)
        deco(c, W, H)
        return b
    }

    private fun glow(c: Canvas, W: Float, H: Float, cxF: Float, cyF: Float, rxF: Float, ryF: Float, color: Int, stop: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, stop), Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply { setScale(rxF * W, ryF * H); postTranslate(cxF * W, cyF * H) })
        p.shader = g
        c.drawRect(0f, 0f, W, H, p)
    }

    private fun doodles(c: Canvas, W: Float, H: Float) {
        // pattern 132 × 132, rotate(-8) scale(.8), stroke #e9d5ff 1.5, whole layer 13 %
        val tileU = 132
        val sc = 0.8f * Ui.density
        val size = (tileU * sc).roundToInt().coerceAtLeast(1)
        val tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tile)
        tc.scale(size / tileU.toFloat(), size / tileU.toFloat())
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Ui.c("#e9d5ff")
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        listOf(
            "M22 22c-5-8-17-3-13 6 2 5 13 12 13 12s11-7 13-12c4-9-8-14-13-6z",
            "M92 14l3.4 7.4 8.1 1-6 5.6 1.6 8-7.1-4-7.1 4 1.6-8-6-5.6 8.1-1z",
            "M58 56c6-7 12 7 18 0s12 7 18 0",
            "M16 78c0 13 9 22 22 22l4-7-8-5-4 3c-3-2-5-4-7-7l3-4-5-8z",
            "M102 70v16M94 78h16",
            "M64 104h12l-6 9z",
            "M112 108c-3-5-10-2-8 3 1 3 8 8 8 8s7-5 8-8c2-5-5-8-8-3z",
            "M40 122l5-9 5 9",
            "M62 18a8 8 0 0 1 8 8M56 12a14 14 0 0 1 20 12",
        ).forEach { tc.drawPath(PathParser.createPathFromPathData(it), st) }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#e9d5ff") }
        listOf(48f to 40f, 120f to 50f, 8f to 112f, 88f to 126f).forEach { (x, y) -> tc.drawCircle(x, y, 2.2f, dot) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setRotate(-8f) })
        }
        p.alpha = (0.13f * 255).roundToInt()
        c.drawRect(0f, 0f, W, H, p)
    }

    private fun deco(c: Canvas, W: Float, Hpx: Float) {
        val n = max(400f, Hpx / Ui.density)   // the web works in CSS px (= dp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cols = listOf("#A855F7", "#8B5CF6", "#C84BD6", "#7C6CF2", "#9F67FF")
        // blurred orbs
        for (i in 0 until ceil(n / 90f).toInt()) {
            val sz = 70 + (i * 47) % 120
            val x = ((i * 61) % 100 - 10) / 100f * W
            val yy = Ui.dp(((i * 173) % n.toInt()).toFloat())
            val r = Ui.dp(sz / 2f); val blur = Ui.dp(22f)
            val col = Ui.ca(cols[i % 5], 0.26f)
            p.shader = RadialGradient(x + r, yy + r, r + blur, intArrayOf(col, col, col and 0x00FFFFFF),
                floatArrayOf(0f, ((r - blur) / (r + blur)).coerceIn(0f, 0.9f), 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x + r, yy + r, r + blur, p)
        }
        p.shader = null
        // sparks and petals
        val petal = PathParser.createPathFromPathData("M12 2c4 4 6 7 6 11a6 6 0 0 1-12 0c0-4 2-7 6-11z")
        for (i in 0 until ceil(n / 38f).toInt()) {
            val x = ((i * 29) % 100) / 100f * W
            val yy = Ui.dp(((i * 131) % n.toInt()).toFloat())
            if (i % 7 == 1) {
                val sz = Ui.dp((14 + (i * 7) % 18).toFloat())
                c.save()
                c.translate(x, yy)
                c.rotate(((i * 47) % 360).toFloat(), sz / 2f, sz / 2f)
                c.scale(sz / 24f, sz / 24f)
                p.color = Ui.ca("#f9a8d4", 0.18f)
                c.drawPath(petal, p)
                c.restore()
            } else {
                val gold = i % 6 == 0
                val s = Ui.dp(if (gold) 4f else 3f)
                val a = listOf(0.35f, 0.6f, 0.85f)[i % 3]
                p.color = if (gold) Ui.ca("#fcd34d", a) else Color.argb((a * 255).roundToInt(), 255, 255, 255)
                c.drawCircle(x + s / 2f, yy + s / 2f, s / 2f, p)
            }
        }
    }
}
