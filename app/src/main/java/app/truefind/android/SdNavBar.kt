package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * Secret Dating's bottom bar — Online Map · Home · Chat — drawn by the phone (SecretNav of SecretApp.jsx).
 * It used to be the web page's own bar, showing through under the native Secret Dating pages.
 * Same look: a flat white bar (70 high, 14 from the sides, 10 from the bottom, corners 24, a hairline pink
 * edge and one soft shadow) and the pink pill that slides to the tab you are on (.32 s); the tab's icon and
 * word turn white on it (.25 s). A tap on another tab opens it; a tap on the tab you are on does nothing.
 */
@SuppressLint("ViewConstructor")
class SdNavBar(ctx: Context, private val onTab: (String) -> Unit) : View(ctx) {

    private class Item(val key: String, val label: String, val path: String, val icon: String)
    private val items = listOf(
        Item("map", "Online Map", "/dating", Lucide.MapPin),
        Item("home", "Home", "/secret-app", Lucide.Home),
        Item("chat", "Chat", "/secret-app?tab=chat", Lucide.MessageCircleMore),
    )

    /** the bar with its 10 below it: the Secret Dating pages end where this starts */
    val barPx: Int get() = Ui.dpi(80f)

    /** which page is open: "map" · "home" · "chat" */
    var current = "home"
        set(v) {
            if (field == v) return
            val from = items.indexOfFirst { it.key == field }.coerceAtLeast(0)
            field = v
            val to = items.indexOfFirst { it.key == v }.coerceAtLeast(0)
            slide(from, to)
        }

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, Ui.dp(1f)); color = Color.argb(36, 214, 40, 120) }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = K.hot }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Ui.bold; textSize = Ui.dp(14f); letterSpacing = 0.01f }
    private val icons = items.map { Icon(it.icon, Color.BLACK, 23f, 2f) }
    private val rect = RectF()
    private val OFF = Ui.c("#111111")

    // the pill's place (0..2, between two tabs while it slides) and how far each tab's colour has turned
    private var at = 1f
    private var fromAt = 1; private var toAt = 1
    private var tint = 1f            // 0..1: from the old tab's colours to the new tab's
    private val pillEase = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val colourEase = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    private var anim: ValueAnimator? = null
    private var pressed = -1
    private val scale = FloatArray(3) { 1f }

    init {
        isClickable = true
        contentDescription = "Secret Dating"
        // (the soft shadow under the bar)
        if (Build.VERSION.SDK_INT >= 28) shade.setShadowLayer(Ui.dp(16f), 0f, Ui.dp(14f), Color.argb(102, 140, 10, 70))
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), barPx)

    private fun slide(from: Int, to: Int) {
        anim?.cancel()
        fromAt = from; toAt = to
        if (!isShown || PageFx.off(context)) { at = to.toFloat(); tint = 1f; invalidate(); return }
        val start = at
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                at = start + (to - start) * pillEase.getInterpolation(f)
                tint = colourEase.getInterpolation(minOf(1f, f * 320f / 250f))
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); at = toAt.toFloat(); tint = 1f; super.onDetachedFromWindow() }

    /** the bar's own box (max 430 wide, in the middle) */
    private fun bar(): RectF {
        val side = Ui.dp(14f)
        val w = minOf(width - 2 * side, Ui.dp(430f))
        val l = (width - w) / 2f
        rect.set(l, 0f, l + w, Ui.dp(70f))
        return rect
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(255, (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
    }
    /** how "on" tab [i] is right now (0 = black, 1 = white) */
    private fun onness(i: Int): Float = when {
        fromAt == toAt -> if (i == toAt) 1f else 0f
        i == toAt -> tint
        i == fromAt -> 1f - tint
        else -> 0f
    }

    override fun onDraw(c: Canvas) {
        // the page under the bar ends in white: the strip behind the bar is white
        c.drawColor(Ui.c("#FEFEFF"))
        val b = bar()
        val l = b.left; val t = b.top; val r = b.right; val btm = b.bottom
        val rr = Ui.dp(24f)
        // one soft shadow (0 14px 32px -20px): a smaller shape under the bar carries it
        if (Build.VERSION.SDK_INT >= 28) {
            val inset = Ui.dp(20f)
            if (r - l > 2 * inset + Ui.dp(40f)) c.drawRoundRect(l + inset, t + inset, r - inset, btm - inset, rr, rr, shade)
        }
        c.drawRoundRect(l, t, r, btm, rr, rr, white)
        c.drawRoundRect(l, t, r, btm, rr, rr, edge)
        // the pink pill (inside the bar's 6 padding; 3 from its cell's sides)
        val pad = Ui.dp(6f)
        val cell = (r - l - 2 * pad) / 3f
        val px = l + pad + at * cell
        val pr = Ui.dp(18f)
        c.drawRoundRect(px + Ui.dp(3f), t + pad, px + cell - Ui.dp(3f), btm - pad, pr, pr, pill)
        // icon (23) + 3 + word (14 bold)
        val iconPx = Ui.dp(23f); val gap = Ui.dp(3f)
        val fm = text.fontMetrics
        val lineH = Ui.dp(14f) * 1.2f
        val blockH = iconPx + gap + lineH
        for ((i, it) in items.withIndex()) {
            val cx = l + pad + cell * i + cell / 2f
            val on = onness(i)
            val col = mix(OFF, Color.WHITE, on)
            c.save()
            if (scale[i] != 1f) c.scale(scale[i], scale[i], cx, (t + btm) / 2f)
            val iy = t + (btm - t - blockH) / 2f
            val ic = icons[i]
            // Home is filled white on the pill (as the web's fill="#fff")
            ic.tint(col, if (it.key == "home" && on > 0.5f) Color.WHITE else null)
            ic.setBounds((cx - iconPx / 2f).toInt(), iy.toInt(), (cx + iconPx / 2f).toInt(), (iy + iconPx).toInt())
            ic.draw(c)
            text.color = col
            val base = iy + iconPx + gap + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
            c.drawText(it.label, cx, base, text)
            c.restore()
        }
    }

    private fun hit(x: Float, y: Float): Int {
        val b = bar()
        if (x < b.left || x > b.right || y < b.top || y > b.bottom) return -1
        val pad = Ui.dp(6f)
        val cell = (b.width() - 2 * pad) / 3f
        return ((x - b.left - pad) / cell).toInt().coerceIn(0, 2)
    }

    private fun press(i: Int, down: Boolean) {
        if (i !in 0..2) return
        val target = if (down) 0.96f else 1f
        ValueAnimator.ofFloat(scale[i], target).apply {
            duration = 140
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
