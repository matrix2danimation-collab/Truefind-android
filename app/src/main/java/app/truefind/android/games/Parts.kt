package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** Every native game screen. */
@SuppressLint("ViewConstructor")
abstract class GameScreen(ctx: Context, val layer: GamesLayer) : FrameLayout(ctx) {
    /** true = handled inside (a sheet closed …); false = the screen should go away */
    open fun onBack(): Boolean = false
    /** The screen goes away for good: end the game on the server, stop voice and timers. */
    open fun leave() {}
    protected val anims = ArrayList<ValueAnimator>()
    protected val main = android.os.Handler(android.os.Looper.getMainLooper())
    protected var alive = true
    /** Runs [f] after [ms] only while the screen is alive. */
    protected fun later(ms: Long, f: () -> Unit): Runnable { val r = Runnable { if (alive) f() }; main.postDelayed(r, ms); return r }
    protected fun cancel(r: Runnable?) { if (r != null) main.removeCallbacks(r) }
    open fun destroy() {
        alive = false
        main.removeCallbacksAndMessages(null)
        G.stopAll(anims)
    }
}

/**
 * FrameLayout whose children can be placed by percent of its size (CSS left / top / width %),
 * centred vertically, or a fixed distance from the right edge.
 */
class PctFrame(ctx: Context) : FrameLayout(ctx) {
    class Spec(
        val left: Float? = null, val top: Float? = null,          // fractions of the box
        val widthFrac: Float? = null, val maxWdp: Float = 0f,
        val rightDp: Float? = null, val topDp: Float? = null, val vCenter: Boolean = false,
    )
    private val specs = LinkedHashMap<View, Spec>()
    fun place(v: View, spec: Spec, lp: LayoutParams = LayoutParams(G.WRAP, G.WRAP)) { specs[v] = spec; addView(v, lp) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        for ((v, s) in specs) {
            val wf = s.widthFrac ?: continue
            var cw = (w * wf).toInt()
            if (s.maxWdp > 0f) cw = min(cw, G.dpi(s.maxWdp))
            val lp = v.layoutParams
            if (lp.width != cw) lp.width = cw
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l; val h = b - t
        for ((v, s) in specs) {
            val cw = v.measuredWidth; val ch = v.measuredHeight
            val x = when {
                s.rightDp != null -> w - G.dpi(s.rightDp) - cw
                s.left != null -> (w * s.left).toInt()
                else -> v.left
            }
            val y = when {
                s.vCenter -> (h - ch) / 2
                s.topDp != null -> G.dpi(s.topDp)
                s.top != null -> (h * s.top).toInt()
                else -> v.top
            }
            v.layout(x, y, x + cw, y + ch)
        }
    }
}

/** A white sweep across a button every few seconds (web: .tg-shine). */
class ShineView(ctx: Context, private val delayMs: Long, private val periodMs: Long = 4000) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var x = -10000f
    private var anim: ValueAnimator? = null
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val bw = w * 0.34f
        val skew = h * 0.325f // tan(18°)
        path.reset()
        path.moveTo(x + skew, 0f); path.lineTo(x + bw + skew, 0f); path.lineTo(x + bw - skew, h); path.lineTo(x - skew, h); path.close()
        p.shader = LinearGradient(x, 0f, x + bw, 0f, intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(89, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); startDelay = delayMs
            addUpdateListener {
                val t = it.animatedValue as Float
                val w = width.toFloat(); val bw = w * 0.34f
                x = if (t < 0.64f) -1.4f * bw else {
                    val k = G.ease((t - 0.64f) / 0.36f)
                    -1.4f * bw + k * (3.6f * bw + 1.4f * bw)
                }
                invalidate()
            }
            start()
        }
    }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}

/**
 * Bottom sheet over a screen: dim layer + panel sliding up (web: .tg-sheet / .hc-in).
 * [build] fills the panel; [dismissable] = tap on the dim layer closes it.
 */
class Sheet(
    private val host: FrameLayout,
    panelBg: android.graphics.drawable.Drawable,
    scrim: Int,
    padL: Float, padT: Float, padR: Float, padB: Float,
    gapDp: Float,
    private val dismissable: Boolean = true,
    private val onClosed: () -> Unit = {},
    maxHeightFrac: Float = 0.9f,
) {
    val root = FrameLayout(host.context)
    val panel: LinearLayout = G.vbox(host.context, gapDp)
    private var closing = false
    private val dim = View(host.context)
    private val scroll: ScrollView

    init {
        dim.setBackgroundColor(scrim)
        root.addView(dim, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        dim.setOnClickListener { if (dismissable) close() }
        scroll = object : ScrollView(host.context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (host.height * maxHeightFrac).toInt().coerceAtLeast(G.dpi(200f))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.background = panelBg
        scroll.isClickable = true
        panel.pad(padL, padT, padR, padB)
        scroll.addView(panel, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(G.MATCH, G.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.isClickable = true
        host.addView(root, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(180).start()
        scroll.translationY = G.dp(600f)
        scroll.post {
            scroll.translationY = scroll.height.toFloat()
            scroll.animate().translationY(0f).setStartDelay(40).setDuration(340).setInterpolator(G.EASE_SHEET).start()
        }
    }

    val isOpen get() = !closing && root.parent != null

    fun close(animated: Boolean = true) {
        if (closing) return
        closing = true
        if (!animated) { host.removeView(root); onClosed(); return }
        dim.animate().alpha(0f).setDuration(160).start()
        scroll.animate().translationY(scroll.height.toFloat()).setStartDelay(0).setDuration(200).withEndAction {
            host.removeView(root); onClosed()
        }.start()
    }
}

/** A pill toast (top or bottom of the screen) that hides itself. */
class Toast(private val host: FrameLayout, bg: android.graphics.drawable.Drawable, textColor: Int, sizeDp: Float, top: Boolean, offsetDp: Float) {
    private val tv: TextView = G.text(host.context, "", sizeDp, textColor).apply {
        background = bg
        gravity = Gravity.CENTER
        visibility = View.GONE
        maxWidth = (host.resources.displayMetrics.widthPixels * 0.88f).toInt()
        elevation = G.dp(12f)
    }
    private var hide: Runnable? = null
    init {
        host.addView(tv, FrameLayout.LayoutParams(G.WRAP, G.WRAP, Gravity.CENTER_HORIZONTAL or (if (top) Gravity.TOP else Gravity.BOTTOM)).apply {
            if (top) topMargin = G.dpi(offsetDp) else bottomMargin = G.dpi(offsetDp)
        })
    }
    fun padding(l: Float, t: Float, r: Float, b: Float): Toast { tv.pad(l, t, r, b); return this }
    fun show(msg: String, ms: Long = 3000) {
        tv.text = msg
        tv.bringToFront()
        tv.animate().cancel()
        if (tv.visibility != View.VISIBLE) tv.fadeIn() else tv.alpha = 1f
        hide?.let { tv.removeCallbacks(it) }
        val h = Runnable { tv.animate().alpha(0f).setDuration(160).withEndAction { tv.visibility = View.GONE }.start() }
        hide = h
        tv.postDelayed(h, ms)
    }
}

/** Round photo with a coloured ring; the lavender default picture when there is none. */
class AvatarView(ctx: Context, sizeDp: Float, ring: Int, ringDp: Float = 2.5f, bgColor: Int = G.c("#2a1a55")) : FrameLayout(ctx) {
    private val img = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
    private var url = ""
    private val px = G.dpi(sizeDp)
    init {
        background = G.oval(bgColor, ring, ringDp)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) { o.setOval(0, 0, view.width, view.height) }
        }
        val inset = G.dpi(ringDp)
        addView(img, FrameLayout.LayoutParams(G.MATCH, G.MATCH).apply { setMargins(inset, inset, inset, inset) })
        showDefault()
    }
    var dim: Boolean = false
        set(v) { field = v; img.alpha = if (v) 0.75f else 1f; img.colorFilter = if (v) android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.5f) }) else null }
    private fun showDefault() { img.setImageDrawable(SvgDrawable(DEFAULT_AVATAR, 44f)) }
    /** [uri] = the stored photo (private files get a signed link first). */
    fun setPhoto(uri: String?) {
        val u = uri.orEmpty()
        if (u == url) return
        url = u
        if (u.isEmpty()) { showDefault(); return }
        GameApi.photo(u) { link ->
            if (url != u) return@photo
            if (link.isEmpty()) { showDefault(); return@photo }
            app.truefind.android.Photos.load(listOf(link), px) { bmp ->
                if (url != u) return@load
                if (bmp != null) img.setImageBitmap(bmp) else showDefault()
            }
        }
    }
    companion object {
        const val DEFAULT_AVATAR = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 124 124"><defs><radialGradient id="a" cx=".35" cy=".35" r=".75"><stop offset="0" stop-color="#F5F3FF"/><stop offset="1" stop-color="#C4B5FD"/></radialGradient></defs><rect width="124" height="124" fill="url(#a)"/><circle cx="62" cy="48" r="21" fill="#FFFFFF" opacity=".92"/><path d="M20 130C22 94 42 78 62 78S102 94 104 130Z" fill="#FFFFFF" opacity=".92"/></svg>"""
    }
}

/** Swallows touches (a panel over the page must not let taps fall through). */
@SuppressLint("ClickableViewAccessibility")
fun View.eatTouches() { setOnTouchListener { _, _ -> true } }
