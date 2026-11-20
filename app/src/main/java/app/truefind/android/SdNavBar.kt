package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.PathParser

/**
 * Secret Dating's bottom bar, copied from the owner's photo (20 Nov night): a light grey bar, icons only —
 * cards (Home) · heart (who liked you, My Activity) · chat · map pin (Online Map) — the open tab yellow with a
 * short yellow line on the bar's top edge (owner, 21 Nov night; it was red). My profile stays at the top left of the pages, so the photo's
 * 4th (profile) icon became the Online Map. A red dot on the heart while new likes wait.
 * A tap on another tab opens it; a tap on the tab you are on does nothing.
 */
@SuppressLint("ViewConstructor")
class SdNavBar(ctx: Context, private val onTab: (String) -> Unit) : View(ctx) {

    private class Item(val key: String, val label: String, val path: String)
    private val items = listOf(
        Item("home", "Home", "/secret-app"),
        Item("likes", "My Activity", "/secret-app?tab=likes"),
        Item("chat", "Chats", "/secret-app?tab=chat"),
        Item("map", "Online Map", "/dating"),
    )

    /** the bar's height: the Secret Dating pages end where this starts */
    val barPx: Int get() = Ui.dpi(60f)

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

    /** the phone's own button strip under the bar: the grey runs on over it (MainActivity sets it) */
    var below = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    /** new likes are waiting: a red dot on the heart */
    var heartDot = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val bg = Paint().apply { color = SD.NAV_BG }
    private val line = Paint().apply { color = SD.NAV_LINE }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SD.NAV_ON }   // (the line over the open tab: yellow now)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val heart = PathParser.createPathFromPathData(SdPaths.HEART_FILL)
    private val chat = PathParser.createPathFromPathData(SdPaths.CHAT_FILL)
    private val chatLines = PathParser.createPathFromPathData(SdPaths.CHAT_LINES)
    private val pin = PathParser.createPathFromPathData(SdPaths.PIN_FILL)
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
        contentDescription = "Secret Dating, Home"
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
    private fun cell(): Float = (width - 2 * Ui.dp(10f)) / 4f

    override fun onDraw(c: Canvas) {
        // the grey runs on under the bar, down over the phone's own button strip (this view's height includes it)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        c.drawRect(0f, 0f, width.toFloat(), maxOf(1f, Ui.dp(1f)), line)
        val pad = Ui.dp(10f); val cw = cell()
        // the red line over the open tab (62 wide, on the top edge)
        val lx = pad + at * cw + cw / 2f
        r.set(lx - Ui.dp(31f), 0f, lx + Ui.dp(31f), Ui.dp(3f))
        c.drawRoundRect(r, Ui.dp(1.5f), Ui.dp(1.5f), red)
        c.drawRect(r.left, 0f, r.right, Ui.dp(1.5f), red)
        val s = Ui.dp(24f) / 24f
        for ((i, it) in items.withIndex()) {
            val cx = pad + cw * i + cw / 2f
            val cy = Ui.dp(30f)
            val col = mix(SD.NAV_OFF, SD.NAV_ON, onness(i))
            c.save()
            if (scale[i] != 1f) c.scale(scale[i], scale[i], cx, cy)
            c.translate(cx - 12f * s, cy - 12f * s); c.scale(s, s)
            when (it.key) {
                "home" -> cards(c, col)
                "likes" -> {
                    fill.color = col; c.drawPath(heart, fill)
                    if (heartDot && current != "likes") {
                        fill.color = SD.NAV_BG; c.drawCircle(18.6f, 5.2f, 4.6f, fill)
                        fill.color = SD.NAV_DOT; c.drawCircle(18.6f, 5.2f, 3.1f, fill)
                    }
                }
                "chat" -> {
                    fill.color = col; c.drawPath(chat, fill)
                    stroke.color = SD.NAV_BG; stroke.strokeWidth = 1.9f; c.drawPath(chatLines, stroke)
                }
                else -> {
                    fill.color = col; c.drawPath(pin, fill)
                    fill.color = SD.NAV_BG; c.drawCircle(12f, 9.9f, 2.9f, fill)
                }
            }
            c.restore()
        }
    }

    /** two cards: one upright behind, the front one tilted, with a thin gap of the bar's grey */
    private fun cards(c: Canvas, col: Int) {
        fill.color = col
        r.set(10.4f, 6.4f, 20.6f, 21f); c.drawRoundRect(r, 1.8f, 1.8f, fill)
        c.save(); c.rotate(-12f, 8.7f, 10.7f)
        r.set(3.4f, 3.2f, 14f, 18.2f)
        stroke.color = SD.NAV_BG; stroke.strokeWidth = 1.6f; c.drawRoundRect(r, 1.8f, 1.8f, stroke)
        c.drawRoundRect(r, 1.8f, 1.8f, fill)
        c.restore()
    }

    private fun hit(x: Float, y: Float): Int {
        if (y < 0 || y > barPx || x < 0 || x > width) return -1
        val pad = Ui.dp(10f)
        return ((x - pad) / cell()).toInt().coerceIn(0, 3)
    }

    private fun press(i: Int, down: Boolean) {
        if (i !in 0..3) return
        ValueAnimator.ofFloat(scale[i], if (down) 0.9f else 1f).apply {
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
}
