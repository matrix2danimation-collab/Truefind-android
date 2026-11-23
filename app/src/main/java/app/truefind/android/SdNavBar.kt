package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.PathParser

/**
 * Secret Dating's bottom bar, from the owner's picture (23 Nov): one white, pill-shaped bar on the blush page —
 * Discover (Home) · Likes (My Activity: who liked you) · Chat · Map — each an icon with its name under it.
 * The open tab is a flat maroon pill with a white icon and name ("2D": no gloss, no shadow).
 * The blush runs on under the bar, down over the phone's own button strip, so no separate strip shows.
 * Likes opens My Activity over the page you are on; a red dot on its heart while new likes / messages wait.
 * A tap on another tab opens it; a tap on the tab you are on does nothing.
 */
@SuppressLint("ViewConstructor")
class SdNavBar(ctx: Context, private val onTab: (String) -> Unit) : View(ctx) {

    private class Item(val key: String, val label: String, val path: String)
    private val items = listOf(
        Item("home", "Discover", "/secret-app"),
        Item("likes", "Likes", "likes"),
        Item("chat", "Chat", "/secret-app?tab=chat"),
        Item("map", "Map", "/dating"),
    )

    /** the bar's height: the Secret Dating pages end where this starts */
    val barPx: Int get() = Ui.dpi(BAR)

    /** which page is open: "home" · "likes" · "chat" · "map" */
    var current = "home"
        set(v) {
            if (field == v) return
            val from = items.indexOfFirst { it.key == field }.coerceAtLeast(0)
            field = v
            val to = items.indexOfFirst { it.key == v }.coerceAtLeast(0)
            slide(from, to)
            contentDescription = "Secret Dating, ${items[to].label}"
        }

    /** the phone's own button strip under the bar: the blush runs on over it (MainActivity sets it) */
    var below = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    /** new likes are waiting: a red dot on the heart */
    var heartDot = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val bg = Paint().apply { color = Rs.BG_END }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pillLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Rs.LINE }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Rs.M }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textAlign = Paint.Align.CENTER }
    private val flame: Path = PathParser.createPathFromPathData(Lucide.Flame)
    private val heart: Path = PathParser.createPathFromPathData(Lucide.Heart)
    private val bubble: Path = PathParser.createPathFromPathData("M7.9 20A9 9 0 1 0 4 16.1L2 22Z")
    private val dots: Path = PathParser.createPathFromPathData("M8 12h.01 M12 12h.01 M16 12h.01")
    private val pin: Path = PathParser.createPathFromPathData("M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0")
    private val r = RectF()

    private var at = 0f
    private var fromAt = 0; private var toAt = 0
    private var tint = 1f
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private var anim: ValueAnimator? = null
    private var pressed = -1
    private val scale = FloatArray(4) { 1f }

    init {
        isClickable = true
        contentDescription = "Secret Dating, Discover"
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), barPx + below)

    private fun slide(from: Int, to: Int) {
        anim?.cancel()
        fromAt = from; toAt = to
        if (!isShown || PageFx.off(context)) { at = to.toFloat(); tint = 1f; invalidate(); return }
        val start = at
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                at = start + (to - start) * ease.getInterpolation(f)
                tint = minOf(1f, f * 1.3f)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); at = toAt.toFloat(); tint = 1f; super.onDetachedFromWindow() }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
    }
    private fun onness(i: Int): Float = when {
        fromAt == toAt -> if (i == toAt) 1f else 0f
        i == toAt -> tint
        i == fromAt -> 1f - tint
        else -> 0f
    }
    private val side get() = Ui.dp(SIDE)
    private val inner get() = Ui.dp(5f)
    private fun cell(): Float = (width - 2 * side - 2 * inner) / 4f

    override fun onDraw(c: Canvas) {
        // the page's blush under the bar, on down over the phone's own button strip (this view's height includes it)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val top = Ui.dp(TOP); val ph = Ui.dp(PILL)
        r.set(side, top, width - side, top + ph)
        c.drawRoundRect(r, ph / 2f, ph / 2f, pillFill)
        pillLine.strokeWidth = maxOf(1f, Ui.dp(1f))
        c.drawRoundRect(r, ph / 2f, ph / 2f, pillLine)
        val cw = cell(); val x0 = side + inner
        // the open tab: a flat maroon pill that slides to the tab you tap
        r.set(x0 + at * cw, top + inner, x0 + at * cw + cw, top + ph - inner)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, on)
        val s = Ui.dp(23f) / 24f
        text.textSize = Ui.dp(12.5f)
        for ((i, it) in items.withIndex()) {
            val cx = x0 + cw * i + cw / 2f
            val cy = top + Ui.dp(21.5f)
            val o = onness(i)
            val col = mix(Rs.INK, Color.WHITE, o)
            c.save()
            if (scale[i] != 1f) c.scale(scale[i], scale[i], cx, top + ph / 2f)
            c.save()
            c.translate(cx - 12f * s, cy - 12f * s); c.scale(s, s)
            stroke.color = col; stroke.strokeWidth = 2f
            when (it.key) {
                "home" -> outlined(c, flame, o)
                "likes" -> {
                    outlined(c, heart, o)
                    if (heartDot && current != "likes") {
                        fill.color = Color.WHITE; fill.alpha = 255; c.drawCircle(19.6f, 4.6f, 4.8f, fill)
                        fill.color = Rs.RED; c.drawCircle(19.6f, 4.6f, 3.3f, fill)
                    }
                }
                "chat" -> {
                    outlined(c, bubble, o)
                    stroke.color = mix(Rs.INK, Rs.M, o); stroke.strokeWidth = 2.2f; c.drawPath(dots, stroke)
                }
                else -> {
                    outlined(c, pin, o)
                    if (o > 0f) { fill.color = Rs.M; fill.alpha = (255 * o).toInt(); c.drawCircle(12f, 10f, 3f, fill); fill.alpha = 255 }
                    stroke.color = mix(Rs.INK, Rs.M, o); stroke.strokeWidth = 2f; c.drawCircle(12f, 10f, 3f, stroke)
                }
            }
            c.restore()
            text.color = col
            c.drawText(it.label, cx, top + Ui.dp(48.5f), text)
            c.restore()
        }
    }

    /** a line icon; on the open tab it is white and filled ([o] 0..1 while the maroon pill slides) */
    private fun outlined(c: Canvas, p: Path, o: Float) {
        if (o > 0f) { fill.color = Color.WHITE; fill.alpha = (255 * o).toInt(); c.drawPath(p, fill); fill.alpha = 255 }
        c.drawPath(p, stroke)
    }

    private fun hit(x: Float, y: Float): Int {
        if (y < 0 || y > barPx || x < side || x > width - side) return -1
        return ((x - side - inner) / cell()).toInt().coerceIn(0, 3)
    }

    private fun press(i: Int, down: Boolean) {
        if (i !in 0..3) return
        ValueAnimator.ofFloat(scale[i], if (down) 0.92f else 1f).apply {
            duration = 120
            addUpdateListener { scale[i] = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit(e.x, e.y); press(pressed, true); return true }
            MotionEvent.ACTION_MOVE -> if (pressed >= 0 && hit(e.x, e.y) != pressed) { press(pressed, false); pressed = -1 }
            MotionEvent.ACTION_UP -> {
                val i = pressed
                pressed = -1
                if (i >= 0) {
                    press(i, false)
                    if (hit(e.x, e.y) == i && items[i].key != current) onTab(items[i].path)
                }
            }
            MotionEvent.ACTION_CANCEL -> { press(pressed, false); pressed = -1 }
        }
        return true
    }

    companion object {
        /** the bar's whole height · the white pill's height · its gap from the page above · its gap from the screen's sides (dp) */
        const val BAR = 76f
        const val PILL = 62f
        const val TOP = 6f
        const val SIDE = 14f
    }
}
