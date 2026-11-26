package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView

/**
 * Pop-ups of Reconnect on top of the whole screen (the web's portal layer):
 * bottom sheets (dim + panel sliding up), full pages sliding in from the right,
 * and plain fades. The phone's Back closes the top one first.
 */
class SdLayer(private val host: FrameLayout) {
    private class Item(val view: View, val back: () -> Boolean, val close: () -> Unit, val page: Boolean)
    private val stack = ArrayList<Item>()
    val open: Boolean get() = stack.isNotEmpty()
    /** a full page (a profile …) covers what is under it — a sheet or a story does not count */
    val pageOpen: Boolean get() = stack.any { it.page }
    val root: FrameLayout get() = host
    var onChange: (() -> Unit)? = null

    /** Back: the top pop-up handles it (or closes). */
    fun back(): Boolean {
        val top = stack.lastOrNull() ?: return false
        if (!top.back()) top.close()
        return true
    }

    fun closeAll() { for (i in stack.toList()) remove(i.view, animate = false) }

    private fun add(v: View, back: () -> Boolean, close: () -> Unit, z: Float, bottom: Int = 0, page: Boolean = false) {
        v.elevation = Ui.dp(60f) + z
        host.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = bottom })
        stack.add(Item(v, back, close, page))
        onChange?.invoke()
    }

    fun remove(v: View, animate: Boolean = true) {
        val i = stack.indexOfFirst { it.view === v }
        if (i < 0) return
        stack.removeAt(i)
        if (animate) v.animate().alpha(0f).setDuration(180).withEndAction { host.removeView(v) }.start() else host.removeView(v)
        onChange?.invoke()
    }

    /**
     * A bottom sheet: dim + [panel] (already filled). Tap on the dim closes it unless [locked]() says no.
     * Returns the close function.
     */
    fun sheet(panel: View, z: Float = 37f, locked: () -> Boolean = { false }, onClose: () -> Unit = {}, maxVh: Float = 0f): () -> Unit {
        val ctx = host.context
        val box = FrameLayout(ctx)
        val dim = View(ctx).apply { setBackgroundColor(Color.argb(115, 60, 0, 25)); alpha = 0f }
        box.addView(dim, FrameLayout.LayoutParams(-1, -1))
        // the web's max-height: N vh = N % of the page's own height (the screen inside the phone's bars)
        val maxH = if (maxVh > 0f) (maxVh * (host.height - host.paddingTop - host.paddingBottom)).toInt().coerceAtLeast(Ui.dpi(300f))
            else (host.height * 0.94f).toInt().coerceAtLeast(Ui.dpi(300f))
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)) }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true; clipToPadding = false }
        scroll.addView(panel)
        box.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        var closed = false
        val close: () -> Unit = {
            if (!closed) {
                closed = true
                dim.animate().alpha(0f).setDuration(200).start()
                scroll.animate().translationY(scroll.height.toFloat()).setDuration(240).setInterpolator(EASE_UP).withEndAction { remove(box, animate = false) }.start()
                onClose()
            }
        }
        dim.setOnClickListener { if (!locked()) close() }
        box.isClickable = true
        add(box, { locked() }, close, z)
        dim.animate().alpha(1f).setDuration(250).start()
        // from just below the screen (its own height is not known yet; the screen's is)
        scroll.translationY = host.height.toFloat().coerceAtLeast(Ui.dp(400f))
        scroll.animate().translationY(0f).setStartDelay(70).setDuration(380).setInterpolator(EASE_UP).start()
        return close
    }

    /** A full page sliding in from the right (sd-in). [back] may handle Back itself (e.g. leave the edit form). */
    /** [bottom]: the page ends that far above the bottom (Reconnect's bar stays in sight under it) */
    fun page(view: View, z: Float = 30f, back: () -> Boolean = { false }, onClose: () -> Unit = {}, animate: Boolean = true, bottom: Int = 0): () -> Unit {
        var closed = false
        val close: () -> Unit = {
            if (!closed) {
                closed = true
                view.animate().translationX(view.width.toFloat()).setDuration(260).setInterpolator(EASE_IN).withEndAction { remove(view, animate = false) }.start()
                onClose()
            }
        }
        view.isClickable = true
        add(view, back, close, z, bottom, page = true)
        if (animate) {
            view.translationX = host.width.toFloat().coerceAtLeast(1000f)
            view.animate().translationX(0f).setDuration(340).setInterpolator(EASE_IN).start()
        }
        return close
    }

    /** A pop-up that just fades in (story viewer, toasts). */
    fun fade(view: View, z: Float = 36f, back: () -> Boolean = { false }, onClose: () -> Unit = {}): () -> Unit {
        var closed = false
        val close: () -> Unit = { if (!closed) { closed = true; remove(view); onClose() } }
        view.isClickable = true
        add(view, back, close, z)
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(250).start()
        return close
    }

    companion object {
        val EASE_UP = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
        val EASE_IN = PathInterpolator(0.25f, 0.6f, 0.3f, 1f)
    }
}

/**
 * Heart tap burst (lib/heartBurst.js): the heart pops, then blue / pink lines
 * and yellow / teal dots fly out from its centre and fade (about 1 second).
 */
object HeartBurst {
    private val LINES = listOf(Triple("#1EB4F0", 0f to -1f, 90f), Triple("#1EB4F0", 0f to 1f, 90f), Triple("#F0105A", -1f to 0f, 0f), Triple("#F0105A", 1f to 0f, 0f))
    private val DOTS = listOf("#FFC72C" to (-0.75f to -0.75f), "#1EE6C0" to (0.75f to -0.75f), "#1EE6C0" to (-0.75f to 0.75f), "#FFC72C" to (0.75f to 0.75f))

    fun play(target: View, host: FrameLayout, heart: View = target) {
        // the heart: scale 1 → .8 → 1.2 → 1 in 520 ms
        heart.animate().cancel()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val f = it.animatedFraction
                val s = when { f < .3f -> 1f - .2f * (f / .3f); f < .65f -> .8f + .4f * ((f - .3f) / .35f); else -> 1.2f - .2f * ((f - .65f) / .35f) }
                heart.scaleX = s; heart.scaleY = s
            }
            start()
        }
        val loc = IntArray(2); target.getLocationInWindow(loc)
        val hl = IntArray(2); host.getLocationInWindow(hl)
        val cx = loc[0] - hl[0] + target.width / 2f
        val cy = loc[1] - hl[1] + target.height / 2f
        val s = maxOf(Ui.dp(26f), minOf(target.width, target.height) * 0.75f)
        val reach = s * 1.05f
        val layer = object : View(host.context) {
            var t = 0f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = RectF()
            override fun onDraw(c: Canvas) {
                // lines: 750 ms after 280 ms; dots: 800 ms after 280 ms (ease-out)
                val tl = ((t * 1200f - 280f) / 750f).coerceIn(0f, 1f)
                val td = ((t * 1200f - 280f) / 800f).coerceIn(0f, 1f)
                if (t * 1200f >= 280f) for ((col, d, rot) in LINES) {
                    val e = ease(tl)
                    val (k, sx, a) = if (e < .25f) Triple(.25f + (.6f - .25f) * (e / .25f), .3f + .7f * (e / .25f), 1f)
                    else Triple(.6f + .4f * ((e - .25f) / .75f), 1f - .8f * ((e - .25f) / .75f), 1f - (e - .25f) / .75f)
                    p.color = Ui.c(col); p.alpha = (a * 255).toInt()
                    c.save(); c.translate(cx + d.first * reach * k, cy + d.second * reach * k); c.rotate(rot)
                    val w = s * .42f * sx; val h = maxOf(Ui.dp(3f), s * .085f)
                    r.set(-w / 2, -h / 2, w / 2, h / 2); c.drawRoundRect(r, h / 2, h / 2, p); c.restore()
                }
                if (t * 1200f >= 280f) for ((col, d) in DOTS) {
                    val e = ease(td)
                    val (k, sc, a) = if (e < .2f) Triple(.6f * (e / .2f), .3f + .7f * (e / .2f), e / .2f)
                    else Triple(.6f + .4f * ((e - .2f) / .8f), 1f - .5f * ((e - .2f) / .8f), 1f - (e - .2f) / .8f)
                    p.color = Ui.c(col); p.alpha = (a * 255).toInt()
                    val z = maxOf(Ui.dp(4f), s * .13f) * sc
                    c.drawCircle(cx + d.first * reach * k, cy + d.second * reach * k, z / 2, p)
                }
            }
        }
        layer.elevation = Ui.dp(200f)
        host.addView(layer, FrameLayout.LayoutParams(-1, -1))
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            addUpdateListener { layer.t = it.animatedValue as Float; layer.invalidate() }
            doOnEnd { host.removeView(layer) }
            start()
        }
    }
    private fun ease(x: Float) = 1f - (1f - x) * (1f - x)
    private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(a: android.animation.Animator) { f() }
    })
}
