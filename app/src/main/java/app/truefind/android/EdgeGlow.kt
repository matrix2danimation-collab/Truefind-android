package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * The soft glow at the very top or bottom of a list when you go on pulling past its end, or when a fast swipe
 * hits the end (owner, 19 Nov evening: "like VoiceClub's red one, in blue"; Secret Dating in its own pink).
 * Android 12 and later only stretch the page there and give it no colour, so the app draws the glow itself:
 * one see-through layer over the screen, fed with every touch (it never takes a touch). The list under the
 * finger is found at the touch; it doesn't stretch any more (the glow takes the stretch's place).
 */
class EdgeGlow(ctx: Context, private val colorNow: () -> Int) : View(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var target: View? = null
    private var vertical = false
    private var gone = false              // the touch went sideways: no glow for it
    private var x0 = 0f; private var y0 = 0f; private var lastY = 0f; private var fingerX = 0.5f
    /** how far each edge is pulled: 0 … 1 */
    var topK = 0f; private set
    var bottomK = 0f; private set
    private var pullTop = 0f; private var pullBottom = 0f
    private val box = RectF()             // the list's place on this layer
    private var release: ValueAnimator? = null
    private var color = Color.TRANSPARENT

    init {
        isClickable = false; isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }
    // never takes a touch
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    /** every touch of the screen, before the screen gets it */
    fun feed(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                watchStop()
                vertical = false; gone = false
                x0 = e.rawX; y0 = e.rawY; lastY = e.rawY
                val root = parent as? ViewGroup ?: return
                val t = find(root, e.rawX, e.rawY)
                target = t
                if (t != null) {
                    // the glow takes the place of Android's own stretch on this list
                    if (t.overScrollMode != OVER_SCROLL_NEVER) t.overScrollMode = OVER_SCROLL_NEVER
                    place(t); color = colorNow()
                }
                pullTop = 0f; pullBottom = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val t = target ?: return
                if (gone) return
                if (!vertical) {
                    val dx = abs(e.rawX - x0); val dy = abs(e.rawY - y0)
                    if (dx > slop && dx > dy) { gone = true; return }
                    if (dy > slop) { vertical = true; lastY = e.rawY } else return
                }
                val d = e.rawY - lastY
                lastY = e.rawY
                if (d == 0f) return
                fingerX = ((e.rawX - screenX()) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                val h = box.height().coerceAtLeast(1f)
                if (d > 0) {
                    // finger down: past the top when the list can't go up any more
                    if (pullBottom > 0f) pullBottom = (pullBottom - d).coerceAtLeast(0f)
                    else if (!t.canScrollVertically(-1)) pullTop += d
                } else {
                    if (pullTop > 0f) pullTop = (pullTop + d).coerceAtLeast(0f)
                    else if (!t.canScrollVertically(1)) pullBottom += -d
                }
                release?.cancel(); release = null
                topK = min(1f, pullTop / (h * 0.35f)); bottomK = min(1f, pullBottom / (h * 0.35f))
                place(t)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (topK > 0f || bottomK > 0f) fade()
                // a quick swipe that runs into the end glows there for a moment, too
                val t = target
                if (t != null && vertical && !gone && e.actionMasked == MotionEvent.ACTION_UP) watch(t)
                pullTop = 0f; pullBottom = 0f
            }
        }
    }

    private fun fade() {
        release?.cancel()
        val t0 = topK; val b0 = bottomK
        release = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 600; interpolator = DecelerateInterpolator()
            addUpdateListener { val f = it.animatedValue as Float; topK = t0 * f; bottomK = b0 * f; invalidate() }
            start()
        }
    }

    // ── a fast swipe hitting the end: followed for 2 s ──
    private var watched: View? = null
    private var lastOff = Int.MIN_VALUE
    private var lastV = 0f
    private var watchEnd = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val t = watched ?: return
            if (now > watchEnd || !t.isShown) { watched = null; return }
            val off = offsetOf(t)
            if (lastOff != Int.MIN_VALUE) {
                val v = (off - lastOff).toFloat()
                if (v != 0f) lastV = v
                else if (abs(lastV) > 2f) {
                    // it stopped moving because it reached an end, and it was going there fast
                    val hitTop = lastV < 0 && !t.canScrollVertically(-1)
                    val hitBottom = lastV > 0 && !t.canScrollVertically(1)
                    if (hitTop || hitBottom) {
                        place(t); color = colorNow()
                        val k = min(1f, abs(lastV) / Ui.dp(40f)) * 0.8f
                        if (hitTop) topK = k else bottomK = k
                        invalidate(); fade()
                        watched = null; return
                    }
                    lastV = 0f
                }
            }
            lastOff = off
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun watch(t: View) {
        watched = t; lastOff = Int.MIN_VALUE; lastV = 0f
        watchEnd = System.nanoTime() + 2_000_000_000L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }
    private fun watchStop() { watched = null; Choreographer.getInstance().removeFrameCallback(frame) }
    private fun offsetOf(v: View): Int = if (v is RecyclerView) v.computeVerticalScrollOffset() else v.scrollY

    // ── where things are ──
    private val loc = IntArray(2)
    private fun screenX(): Int { getLocationOnScreen(loc); return loc[0] }
    private fun place(t: View) {
        getLocationOnScreen(loc); val ox = loc[0]; val oy = loc[1]
        t.getLocationOnScreen(loc)
        box.set((loc[0] - ox).toFloat(), (loc[1] - oy).toFloat(), (loc[0] - ox + t.width).toFloat(), (loc[1] - oy + t.height).toFloat())
    }
    /** the list under the finger: the top-most one that can scroll up or down */
    private fun find(v: View, x: Float, y: Float): View? {
        if (v === this || !v.isShown || v.alpha <= 0.05f) return null
        v.getLocationOnScreen(loc)
        if (x < loc[0] || y < loc[1] || x > loc[0] + v.width * v.scaleX || y > loc[1] + v.height * v.scaleY) return null
        if (v is ViewGroup) {
            // the children from the top-most down (what is drawn last is on top)
            val kids = (0 until v.childCount).map { v.getChildAt(it) }.sortedWith(compareBy<View> { it.z })
            for (c in kids.asReversed()) {
                if (c === this) continue
                val r = find(c, x, y)
                if (r != null) return r
                // a child that takes touches covers what lies under it
                if (c.isShown && c.alpha > 0.05f && covers(c, x, y) && (c.isClickable || c.background != null)) break
            }
        }
        return if (v.canScrollVertically(1) || v.canScrollVertically(-1)) v else null
    }
    private fun covers(c: View, x: Float, y: Float): Boolean {
        c.getLocationOnScreen(loc)
        return x >= loc[0] && y >= loc[1] && x <= loc[0] + c.width * c.scaleX && y <= loc[1] + c.height * c.scaleY
    }

    // ── the glow: a wide circle's cap at the edge (the way Android drew it before the stretch) ──
    override fun onDraw(c: Canvas) {
        if (topK <= 0.001f && bottomK <= 0.001f) return
        val w = box.width(); if (w <= 0f) return
        val r = w * 0.75f / 0.5f                       // the circle: as Android's glow (30° each side)
        val capH = min(r - r * cos(Math.toRadians(30.0)).toFloat(), Ui.dp(140f))
        for (edge in 0..1) {
            val k = if (edge == 0) topK else bottomK
            if (k <= 0.001f) continue
            val h = capH * (0.25f + 0.75f * k)
            c.save()
            if (edge == 0) c.clipRect(box.left, box.top, box.right, box.top + h) else c.clipRect(box.left, box.bottom - h, box.right, box.bottom)
            // the cap leans a little to where the finger is, as Android's did
            val cx = box.left + w / 2f + (fingerX - 0.5f) * w * 0.25f
            val cy = if (edge == 0) box.top + h - r else box.bottom - h + r
            val a = (0.16f + 0.34f * k).coerceAtMost(0.5f)
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(color, a * 0.85f), withAlpha(color, a), withAlpha(color, a * 0.25f)),
                floatArrayOf(0f, 0.93f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
            c.restore()
        }
    }
    private fun withAlpha(col: Int, a: Float): Int = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))
}
