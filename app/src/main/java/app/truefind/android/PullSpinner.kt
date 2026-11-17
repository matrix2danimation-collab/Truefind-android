package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * The pull-down-to-refresh circle — same as the web's PullToRefresh:
 * a white round button that comes down with the finger, showing the five
 * blue dots (still, turning with the pull); while refreshing the dots run
 * round as the "long train" loader (TfSpinner, chosen Nov 16).
 */
class PullSpinner(ctx: Context) : View(ctx) {
    private val BLUE = Ui.c("#5664EF")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
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
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Ui.c("#FFFFFF")); setStroke(Ui.dpi(1f), Ui.c("#EEE6FB"))
        }
        elevation = Ui.dp(8f)
        outlineSpotShadowColorCompat(Ui.c("#4C1D95"))
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
