package app.truefind.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The move from one full page to the next (Home ↔ Explore ↔ Find ↔ Wallet, Profile, Games, Live Dance,
 * a chat …), the way FRND moves between its pages: the two pages sit side by side and slide together
 * the whole width of the screen — the old one goes out one side while the new one comes in from the
 * other, no fading. (Before: a 30 dp nudge with a fade, which on a phone looked like a quick blink.)
 *
 * - The page you leave is shown as a recording of its last look (a [Picture]: no pixels are drawn at the
 *   moment of the tap, the recording is replayed by the GPU), so the real page can be hidden at once and the
 *   new page is never waited for.
 * - Forward (a tab to the right, or a page opened) comes in from the right, Back from the left.
 * - 300 ms with Android's ViewPager curve (1 − (1 − t)⁵): most of the way in the first third, then a long
 *   soft stop — measured frame by frame on the FRND video (a page is ~90% across after ~100 ms).
 * - Phones with animations turned off (Settings → Developer options, or Remove animations) get no movement.
 * - A new move while one is running finishes the old one at once.
 */
object PageFx {
    private const val MS = 300L
    /** ViewPager's scroll curve */
    private val ease = android.animation.TimeInterpolator { t -> val u = t - 1f; u * u * u * u * u + 1f }

    private var running: ValueAnimator? = null
    private var snap: View? = null
    private var moving: View? = null

    private fun off(ctx: Context): Boolean = try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) { false }

    /** ends a running move at once (pages in their final places) */
    fun finish() {
        running?.let { a -> running = null; a.removeAllListeners(); a.cancel() }
        snap?.let { s -> (s.parent as? ViewGroup)?.removeView(s) }
        snap = null
        moving?.let { m -> m.translationX = 0f; m.alpha = 1f; m.setLayerType(View.LAYER_TYPE_NONE, null) }
        moving = null
    }

    /**
     * [gone]: the page that was just hidden (or null when the web page was showing),
     * [came]: the page that was just shown (or null when the web page now shows).
     */
    fun run(root: FrameLayout, gone: View?, came: View?, back: Boolean) {
        finish()
        if (gone == null && came == null) return
        if (off(root.context) || root.width == 0) return
        val dir = if (back) -1f else 1f
        val shift = root.width.toFloat()

        // the page you leave: its last look, on top of both pages
        var overlay: View? = null
        if (gone != null && gone.width > 0 && gone.height > 0) {
            val pic = try {
                Picture().also { p ->
                    val c: Canvas = p.beginRecording(gone.width, gone.height)
                    try { gone.draw(c) } finally { p.endRecording() }
                }
            } catch (e: Throwable) { null }
            if (pic != null) {
                overlay = object : View(root.context) {
                    override fun onDraw(canvas: Canvas) { canvas.drawPicture(pic) }
                }.apply {
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    // just above whichever of the two pages sits higher (bars above them stay above)
                    val at = maxOf(root.indexOfChild(gone), if (came != null) root.indexOfChild(came) else -1) + 1
                    root.addView(this, at.coerceIn(0, root.childCount), FrameLayout.LayoutParams(gone.width, gone.height))
                    x = gone.x; y = gone.y
                    elevation = maxOf(gone.elevation, came?.elevation ?: 0f)
                }
            }
        }
        snap = overlay
        moving = came
        if (came != null) {
            came.translationX = dir * shift
            came.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        overlay?.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val a = ValueAnimator.ofFloat(0f, 1f).setDuration(MS)
        a.interpolator = ease
        a.addUpdateListener { v ->
            val t = v.animatedValue as Float
            came?.translationX = dir * shift * (1f - t)
            overlay?.translationX = -dir * shift * t
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (running === animation) finish() }
        })
        running = a
        a.start()
    }
}
