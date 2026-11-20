package app.truefind.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Hold a photo and it shows big in the middle of the screen, the way Instagram does (owner, 21 Nov): the whole
 * photo — nothing cut off — with round corners, over the page gone soft (blurred on Android 12 and newer) and dark.
 * Lift the finger and it goes. While it shows, the page under the finger does not scroll and the photo's tap does
 * not fire; a normal tap still does what it did before.
 */
object PhotoPeek {
    /** whole photos already seen: holding the same photo again shows it sharp at once */
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * [v]: the photo on the page (pressed, it shrinks a little, as before). [raws]: the photo (its saved values; the
     * first one that loads is shown). [thumb]: the copy already drawn on the page, shown at once while the whole
     * photo loads. [under]: the page that goes soft behind it. [onEmpty]: a hold when there is no photo.
     * [onClosed]: after the big photo went away.
     */
    fun attach(v: View, raws: List<String>, thumb: () -> Bitmap?, under: View?, pressScale: Float = .97f,
               onEmpty: (() -> Unit)? = null, onClosed: (() -> Unit)? = null) {
        val list = raws.filter { it.isNotBlank() }.distinct()
        var open: Peek? = null
        // no buzz on the hold (as the profile photo's hold before)
        v.isHapticFeedbackEnabled = false
        v.setOnLongClickListener {
            if (open != null) return@setOnLongClickListener true
            if (list.isEmpty()) { onEmpty?.invoke(); return@setOnLongClickListener onEmpty != null }
            val host = v.rootView as? FrameLayout ?: return@setOnLongClickListener false
            // the finger now holds the photo: the page must not scroll it away (lifting the finger closes it)
            v.parent?.requestDisallowInterceptTouchEvent(true)
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            open = Peek(host, list, thumb(), under) { open = null; onClosed?.invoke() }.also { it.show() }
            true
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(pressScale).scaleY(pressScale).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    open?.hide()
                }
            }
            false
        }
    }

    /** the big photo over everything (the whole window, the status bar strip too) */
    private class Peek(private val host: FrameLayout, private val raws: List<String>, private val thumb: Bitmap?,
                       private val under: View?, private val onHidden: () -> Unit) {
        private val ctx: Context = host.context
        private val box = FrameLayout(ctx)
        private val dim = View(ctx)
        private val pic = Pic(ctx)
        private val spin = TfLoader(ctx, 22f, Color.WHITE)
        private val side = min(max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels), 1600)
        private val key = "${raws[0]}@$side"
        private var gone = false
        private var blurLevel = 0f
        private var blurAnim: ValueAnimator? = null
        private var sizeAnim: ValueAnimator? = null

        fun show() {
            val whole = cache.get(key)
            val first = whole ?: thumb
            dim.setBackgroundColor(Color.argb(168, 14, 6, 28))
            box.addView(dim, FrameLayout.LayoutParams(-1, -1))
            val (w, h) = fit(first)
            pic.set(first)
            box.addView(pic, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
            box.addView(spin, FrameLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
            spin.visibility = if (first == null) View.VISIBLE else View.GONE
            box.contentDescription = "Photo. Lift your finger to close it."
            // it never stays by mistake: a tap closes it too
            box.setOnClickListener { hide() }
            host.addView(box, FrameLayout.LayoutParams(-1, -1))
            dim.alpha = 0f
            dim.animate().alpha(1f).setDuration(180).start()
            pic.alpha = 0f; pic.scaleX = .86f; pic.scaleY = .86f
            pic.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.15f)).start()
            blurTo(1f, 200)
            if (whole == null) load(0)
        }

        fun hide() {
            if (gone) return
            gone = true
            sizeAnim?.cancel()
            spin.visibility = View.GONE
            pic.animate().cancel()
            pic.animate().alpha(0f).scaleX(.9f).scaleY(.9f).setDuration(150).setInterpolator(AccelerateInterpolator()).start()
            dim.animate().cancel()
            dim.animate().alpha(0f).setDuration(180).withEndAction { (box.parent as? ViewGroup)?.removeView(box); clearBlur() }.start()
            blurTo(0f, 170)
            onHidden()
        }

        /** the whole photo (not cropped): the first of [raws] that loads; kept for the next hold even if the finger lifted */
        private fun load(i: Int) {
            if (i >= raws.size) { if (!gone) spin.visibility = View.GONE; return }
            Photos.loadFull(raws[i], side) { b ->
                if (b == null) { load(i + 1); return@loadFull }
                cache.put(key, b)
                if (!gone) whole(b)
            }
        }

        /** the whole photo came: it fades in over the small copy, and the card takes the photo's own shape */
        private fun whole(b: Bitmap) {
            spin.visibility = View.GONE
            val (w, h) = fit(b)
            pic.swap(b)
            val lp = pic.layoutParams as FrameLayout.LayoutParams
            val w0 = lp.width; val h0 = lp.height
            if (w0 == w && h0 == h) return
            sizeAnim?.cancel()
            sizeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val k = it.animatedValue as Float
                    lp.width = (w0 + (w - w0) * k).toInt(); lp.height = (h0 + (h - h0) * k).toInt()
                    pic.layoutParams = lp
                }
                start()
            }
        }

        /** as wide as the screen allows (20 dp free each side, at most 460 dp), at most 72 % of the screen's height */
        private fun fit(b: Bitmap?): Pair<Int, Int> {
            val dm = ctx.resources.displayMetrics
            val sw = host.width.takeIf { it > 0 } ?: dm.widthPixels
            val sh = host.height.takeIf { it > 0 } ?: dm.heightPixels
            val maxW = min(sw - Ui.dpi(40f), Ui.dpi(460f)).coerceAtLeast(Ui.dpi(120f))
            val maxH = (sh * .72f).toInt()
            val a = (if (b != null && b.width > 0 && b.height > 0) b.height.toFloat() / b.width else 1f).coerceIn(.3f, 3f)
            var w = maxW; var h = (w * a).toInt()
            if (h > maxH) { h = maxH; w = (h / a).toInt() }
            return w to h
        }

        // ── the page behind goes soft (Android 12 and newer; older phones: only the dark layer) ──
        private fun blurTo(to: Float, ms: Long) {
            val u = under ?: return
            if (Build.VERSION.SDK_INT < 31) return
            blurAnim?.cancel()
            blurAnim = ValueAnimator.ofFloat(blurLevel, to).apply {
                duration = ms
                addUpdateListener { blurLevel = it.animatedValue as Float; setBlur(u, blurLevel) }
                start()
            }
        }

        private fun setBlur(u: View, k: Float) {
            if (Build.VERSION.SDK_INT < 31) return
            val r = Ui.dp(16f) * k
            try { u.setRenderEffect(if (r < .5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)) } catch (t: Throwable) { }
        }

        private fun clearBlur() {
            blurAnim?.cancel(); blurAnim = null
            blurLevel = 0f
            val u = under ?: return
            if (Build.VERSION.SDK_INT >= 31) try { u.setRenderEffect(null) } catch (t: Throwable) { }
        }
    }

    /** the photo cut to the card (the card already has the photo's shape), round corners and a soft shadow */
    private class Pic(ctx: Context) : View(ctx) {
        private var shown: Bitmap? = null
        private var next: Bitmap? = null
        private var mix = 1f
        private var fade: ValueAnimator? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val m = Matrix()
        private val r = RectF()
        private val rad = Ui.dp(18f)

        init {
            elevation = Ui.dp(14f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rad) }
            }
        }

        fun set(b: Bitmap?) { fade?.cancel(); shown = b; next = null; mix = 1f; invalidate() }

        /** the sharp photo fades in over the small copy */
        fun swap(b: Bitmap) {
            if (shown == null || shown === b) { set(b); return }
            fade?.cancel()
            next = b; mix = 0f
            fade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                addUpdateListener { mix = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { if (next === b) { shown = b; next = null; mix = 1f; invalidate() } }
                })
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            val a = shown; val b = next
            // white under the photo (a photo with see-through parts), a soft white card while nothing has come yet
            p.shader = null
            p.color = if (a == null && b == null) Color.argb(46, 255, 255, 255) else Color.WHITE
            c.drawRoundRect(r, rad, rad, p)
            if (a != null) paint(c, a, 255)
            if (b != null) paint(c, b, (mix * 255).toInt())
        }

        private fun paint(c: Canvas, b: Bitmap, alpha: Int) {
            val k = max(width / b.width.toFloat(), height / b.height.toFloat())
            m.setScale(k, k); m.postTranslate((width - b.width * k) / 2f, (height - b.height * k) / 2f)
            p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            p.alpha = alpha
            c.drawRoundRect(r, rad, rad, p)
            p.shader = null; p.alpha = 255
        }
    }
}
