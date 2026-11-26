package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * The pull-down-to-refresh loader: the five blue dots come down with the finger (still, turning with the pull);
 * while refreshing they run round as the "long train" loader (TfSpinner, chosen Nov 16).
 * Owner, 23 Nov: only the dots — no white round button (and no shadow) behind them, for a cleaner, more
 * professional look. [ring] is no longer drawn (kept so every page's call stays the same).
 */
class PullSpinner(ctx: Context, dots: Int = BLUE, ring: Int = Ui.c("#EEE6FB")) : View(ctx) {
    companion object {
        val BLUE = Ui.c("#5664EF")
        /** Reconnect: its own pink, not the blue (owner, 19 Nov evening) */
        val PINK get() = K.rose
        val PINK_RING get() = K.line
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dots }
    private var pullDeg = 0f
    private var ready = false
    var spinning = false
        private set
    private var t = 0f
    private val ease = PathInterpolator(0.5f, 0f, 0.5f, 1f)
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    init {
        // no background: nothing behind the dots, so no outline and no shadow either (the page's elevation still
        // keeps the dots above the page's content)
        background = null
        outlineProvider = null
        alpha = 0f
        translationY = -Ui.dp(64f)
        scaleX = 0.7f; scaleY = 0.7f
    }

    /** d = how far it has come down (dp), as on the web. */
    fun pull(d: Float, readyAt: Float) {
        animate().cancel()
        val k = (d / readyAt).coerceAtMost(1f)
        alpha = (d / 26f).coerceAtMost(1f)
        translationY = Ui.dp(d - 52f)
        scaleX = 0.7f + 0.3f * k; scaleY = scaleX
        ready = k >= 1f
        pullDeg = d * 3.2f
        invalidate()
    }

    fun spin(restDp: Float) {
        spinning = true
        animate().alpha(1f).translationY(Ui.dp(restDp)).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
        if (!anim.isStarted) anim.start()
    }

    fun hide() {
        animate().alpha(0f).translationY(-Ui.dp(64f)).scaleX(0.7f).scaleY(0.7f).setDuration(320)
            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
            .withEndAction { anim.cancel(); spinning = false }.start()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (!spinning) {
            // still dots (24-unit drawing): r 2.7 → 1.3, turning with the pull
            val s = Ui.dp(24f) / 24f
            val radii = floatArrayOf(2.7f, 2.35f, 2f, 1.65f, 1.3f)
            val rot = floatArrayOf(0f, -26f, -50f, -72f, -92f)
            val op = floatArrayOf(1f, 0.8f, 0.62f, 0.45f, 0.3f)
            val base = if (ready) 1f else 0.75f
            for (i in 0 until 5) {
                c.save()
                c.translate(cx, cy)
                c.rotate(pullDeg + rot[i])
                p.alpha = (255 * op[i] * base).toInt()
                c.drawCircle(0f, (3.6f - 12f) * s, radii[i] * s, p)
                c.restore()
            }
        } else {
            // the "long train": 26 box, dots 12 % from the top, big to small
            val size = Ui.dp(26f)
            val sizes = floatArrayOf(0.22f, 0.192f, 0.164f, 0.136f, 0.108f)
            val op = floatArrayOf(1f, 0.8f, 0.62f, 0.45f, 0.3f)
            for (i in 0 until 5) {
                val f = ((t - (-0.08f * i) / 1.3f) % 1f + 1f) % 1f
                val ang = 360f * ease.getInterpolation(f)
                c.save()
                c.translate(cx, cy)
                c.rotate(ang)
                p.alpha = (255 * op[i]).toInt()
                c.drawCircle(0f, -size / 2f + size * 0.12f, size * sizes[i] / 2f, p)
                c.restore()
            }
        }
    }
}


/**
 * Pull down at the top of a page to refresh — for any page (owner, 19 Nov evening: Reconnect, Live Room,
 * Live Dance, Games, Expert Talk, Dashboard and its earnings pages too; "wherever it is needed").
 * [scroll] goes inside a [PullHost] with the five-dot circle over it, the same as Home. [refresh] loads the page
 * again and calls done() when everything is back: the circle turns at least 0.65 s, at most 8 s. No vibration.
 */
class PullRefresh private constructor(val host: PullHost, val spinner: PullSpinner) {
    companion object {
        /** [scroll] has no parent yet: the returned host goes where it would have gone */
        fun around(scroll: View, dots: Int = PullSpinner.BLUE, ring: Int = Ui.c("#EEE6FB"), canPull: () -> Boolean = { true },
                   refresh: (done: () -> Unit) -> Unit): PullRefresh {
            val ctx = scroll.context
            val host = PullHost(ctx)
            val sp = PullSpinner(ctx, dots, ring)
            host.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
            host.addView(sp, android.widget.FrameLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(42f), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(4f) })
            sp.elevation = Ui.dp(20f)
            val pr = PullRefresh(host, sp)
            host.canPull = { !scroll.canScrollVertically(-1) && !sp.spinning && scroll.isShown && canPull() }
            host.onPull = { d -> sp.pull(d, 64f) }
            host.onRelease = { d -> if (d >= 64f) pr.start(refresh) else sp.hide() }
            return pr
        }
        /** [scroll] already sits in a parent: the host takes its place there */
        fun inPlace(scroll: View, dots: Int = PullSpinner.BLUE, ring: Int = Ui.c("#EEE6FB"), canPull: () -> Boolean = { true },
                    refresh: (done: () -> Unit) -> Unit): PullRefresh {
            val parent = scroll.parent as android.view.ViewGroup
            val i = parent.indexOfChild(scroll); val lp = scroll.layoutParams
            parent.removeViewAt(i)
            val pr = around(scroll, dots, ring, canPull, refresh)
            parent.addView(pr.host, i, lp)
            return pr
        }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var run = 0
    val busy: Boolean get() = spinner.spinning
    /** the refresh itself (also for a page's own "refresh" moments) */
    fun start(refresh: (done: () -> Unit) -> Unit) {
        val my = ++run
        spinner.spin(22f)
        val t0 = android.os.SystemClock.uptimeMillis()
        var ended = false
        val end = {
            if (!ended && my == run) {
                ended = true
                main.postDelayed({ if (my == run) spinner.hide() }, (650 - (android.os.SystemClock.uptimeMillis() - t0)).coerceAtLeast(0))
            }
        }
        main.postDelayed({ if (my == run && spinner.spinning) { ended = true; spinner.hide() } }, 8000)
        try { refresh { main.post { end() } } } catch (e: Exception) { end() }
    }
}

/**
 * Pull-to-refresh on a page that keeps its own layout (it can't be put inside a [PullHost]): the page hands its
 * onInterceptTouchEvent / onTouchEvent to [intercept] / [touch]; the circle sits at the page's top ([topDp]).
 */
class PagePull(page: android.view.ViewGroup, dots: Int = PullSpinner.BLUE, ring: Int = Ui.c("#EEE6FB"), topDp: Float = 4f,
               private val canPull: () -> Boolean, private val refresh: (done: () -> Unit) -> Unit) {
    val spinner = PullSpinner(page.context, dots, ring)
    private val g = PullGesture(page.context)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var run = 0
    init {
        page.addView(spinner, android.widget.FrameLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(42f), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(topDp) })
        spinner.elevation = Ui.dp(20f)
        g.canPull = { !spinner.spinning && canPull() }
        g.onPull = { d -> spinner.pull(d, 64f) }
        g.onRelease = { d -> if (d >= 64f) start() else spinner.hide() }
    }
    fun intercept(e: android.view.MotionEvent): Boolean = g.intercept(e)
    fun touch(e: android.view.MotionEvent): Boolean = g.touch(e)
    val busy: Boolean get() = spinner.spinning
    /** the refresh: the circle turns until done() (at least 0.65 s, at most 8 s) */
    fun start() {
        val my = ++run
        spinner.spin(22f)
        val t0 = android.os.SystemClock.uptimeMillis()
        var ended = false
        val end = {
            if (!ended && my == run) {
                ended = true
                main.postDelayed({ if (my == run) spinner.hide() }, (650 - (android.os.SystemClock.uptimeMillis() - t0)).coerceAtLeast(0))
            }
        }
        main.postDelayed({ if (my == run && spinner.spinning) { ended = true; spinner.hide() } }, 8000)
        try { refresh { main.post { end() } } } catch (e: Exception) { end() }
    }
}
