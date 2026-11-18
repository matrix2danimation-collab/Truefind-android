package app.truefind.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Moving from one page to the next, the way FRND does it. Measured frame by frame from the owner's
 * screen recordings of FRND (same phone, 24 frames a second):
 *
 * - TAB (Home ↔ Explore ↔ Recent, and Secret Dating's Map ↔ Home ↔ Chat): both pages slide side by side,
 *   a whole screen width, like a ViewPager. Nothing fades, the bottom bar stays still. In FRND the new page
 *   was ~35 % of the way after the first recorded frame, ~76 % after the second, settled by the fourth.
 * - PUSH (opening a page: Profile, Wallet, Games, Dashboard, a chat …): the new page slides in from the right
 *   edge over the old one; the old page slides 24 % to the left and darkens to ~55 % brightness.
 *   (FRND: 48 % → 79 % → 89 % → 94 % → 97 %, the old page 0.12 → 0.21 of the width, brightness ×0.76 → ×0.57.)
 * - POP (Back): the same in reverse — the top page slides away to the right, the page under it comes back
 *   from 24 % on the left and brightens.
 *
 * All moves use the same curve (Material "emphasized decelerate": a quick start and a long, soft stop), which
 * matches those measurements. Only translation and opacity change, on the phone's graphics chip.
 *
 * The page you leave stays on screen until the move ends (the app hides it a moment later). A page that
 * isn't a view any more (a web page you went back from) is shown as the picture taken just before.
 * Phones with animations switched off get no movement.
 */
object PageFx {
    enum class Kind { TAB, PUSH, POP }

    private const val TAB_MS = 220L
    private const val PUSH_MS = 300L
    private const val POP_MS = 300L
    private const val UNDER = 0.24f   // how far the lower page moves left
    private const val DIM = 0.45f     // how dark the lower page gets (FRND: brightness ×0.55)
    private val ease = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    private var anim: ValueAnimator? = null
    private var pending: Choreographer.FrameCallback? = null
    private val held = ArrayList<View>()      // pages kept on screen until the move ends
    private val wanted = HashMap<View, Int>() // what the app last set for each of them (shown after the move)
    private val moved = ArrayList<View>()     // views whose translation we change
    private val lifted = ArrayList<Lift>()    // views lifted above the others for the move
    private val layered = ArrayList<View>()   // views drawn from a GPU layer during the move
    private var scrim: View? = null
    private var overlayHost: ViewGroup? = null
    private val overlays = ArrayList<Drawable>()
    private var ended: (() -> Unit)? = null

    private class Lift(val v: View, val z: Float, val spot: Int, val ambient: Int)

    fun off(ctx: Context): Boolean = try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) { false }

    val running: Boolean get() = anim != null || pending != null || frozen
    fun isHeld(v: View) = held.contains(v)
    /**
     * The app has just set which pages show: the pages still moving stay on screen until the move ends,
     * and then get what the app set last (a screen that was told "hidden" once isn't told again).
     */
    fun reassert() { if (running) for (v in held) { wanted[v] = v.visibility; if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE } }

    /** test runs only: the next move stops at this point (0..1) until [finish], so it can be photographed */
    var freezeAt = -1f
    private var frozen = false

    /** Ends a running move at once (every page in its final place). */
    fun finish() {
        pending?.let { Choreographer.getInstance().removeFrameCallback(it) }
        pending = null
        anim?.let { a -> anim = null; a.removeAllListeners(); a.cancel() }
        for (v in moved) v.translationX = 0f
        moved.clear()
        for (l in lifted) {
            l.v.translationZ = l.z
            if (Build.VERSION.SDK_INT >= 28) { l.v.outlineSpotShadowColor = l.spot; l.v.outlineAmbientShadowColor = l.ambient }
        }
        lifted.clear()
        for (v in layered) v.setLayerType(View.LAYER_TYPE_NONE, null)
        layered.clear()
        for (v in held) wanted[v]?.let { v.visibility = it }
        wanted.clear()
        frozen = false
        scrim?.let { s -> (s.parent as? ViewGroup)?.removeView(s) }
        scrim = null
        overlayHost?.let { h -> for (d in overlays) h.overlay.remove(d) }
        overlays.clear(); overlayHost = null
        held.clear()
        val e = ended; ended = null
        e?.invoke()
    }

    /**
     * [from] / [to]: the page you leave and the page you go to (the web view counts as a page).
     * [lowerExtras]: views that move with the lower page (the bottom bar of a tab page).
     * [upperExtras]: views that move with the upper page, just under it (Secret Dating's bar lives in the web view).
     * [snapshot]: for POP when the page you leave is already gone (a web page): its picture.
     * [moveLower]: false when the web page moves and darkens its own lower page.
     * [onEnd]: after the move (the app then hides the pages that should be hidden).
     */
    fun run(
        container: FrameLayout, kind: Kind, from: View?, to: View?, dir: Int = 1,
        lowerExtras: List<View> = emptyList(), upperExtras: List<View> = emptyList(), snapshot: Bitmap? = null, moveLower: Boolean = true,
        waitFrames: Int = 1, onEnd: (() -> Unit)? = null,
    ) {
        finish()
        val w = container.width.toFloat()
        if (w <= 0f || off(container.context) || (from == null && to == null && snapshot == null) || (from != null && from === to)) { onEnd?.invoke(); return }
        ended = onEnd
        val zUp = Ui.dp(48f)
        val zScrim = Ui.dp(40f)
        fun hold(v: View?) { if (v != null && v !is WebView && !held.contains(v)) { held.add(v); wanted[v] = v.visibility; v.visibility = View.VISIBLE } }
        fun move(v: View?) { if (v != null && !moved.contains(v)) moved.add(v) }
        // (no GPU layers: moving a page only changes where it is drawn; a layer would be redrawn every frame
        // on pages that move inside — Explore's floating bubbles — and costs a full extra picture at the tap)
        fun layer(@Suppress("UNUSED_PARAMETER") v: View?) {}
        fun lift(v: View, z: Float) {
            val spot = if (Build.VERSION.SDK_INT >= 28) v.outlineSpotShadowColor else 0
            val amb = if (Build.VERSION.SDK_INT >= 28) v.outlineAmbientShadowColor else 0
            lifted.add(Lift(v, v.translationZ, spot, amb))
            v.translationZ = z
            // above the others without a big shadow (FRND's sliding page has a clean edge)
            if (Build.VERSION.SDK_INT >= 28) { v.outlineSpotShadowColor = Color.TRANSPARENT; v.outlineAmbientShadowColor = Color.TRANSPARENT }
        }
        fun addScrim(alpha: Float): View = View(container.context).apply {
            setBackgroundColor(Color.BLACK)
            this.alpha = alpha
            outlineProvider = null
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            translationZ = zScrim
            container.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }.also { scrim = it }

        val ms: Long
        val step: (Float) -> Unit
        when (kind) {
            Kind.TAB -> {
                ms = TAB_MS
                hold(from)
                move(from); move(to); layer(from); layer(to)
                to?.translationX = dir * w
                step = { t ->
                    from?.translationX = -dir * w * t
                    to?.translationX = dir * w * (1f - t)
                }
            }
            Kind.PUSH -> {
                ms = PUSH_MS
                val upper = to
                if (upper == null) { ended = null; onEnd?.invoke(); return }
                hold(from); lowerExtras.forEach { hold(it) }
                lift(upper, zUp)
                move(upper); layer(upper)
                upper.translationX = w
                val ups = upperExtras.filter { it !== upper && it !== from }
                ups.forEach { lift(it, zUp - Ui.dp(1f)); move(it); it.translationX = w }
                val lowers = if (moveLower) listOfNotNull(from) + lowerExtras else emptyList()
                lowers.forEach { move(it); layer(it) }
                val sc = if (moveLower) addScrim(0f) else null
                step = { t ->
                    upper.translationX = w * (1f - t)
                    for (u in ups) u.translationX = w * (1f - t)
                    for (l in lowers) l.translationX = -UNDER * w * t
                    sc?.alpha = DIM * t
                }
            }
            Kind.POP -> {
                ms = POP_MS
                val lowers = listOfNotNull(to) + lowerExtras
                lowers.forEach { move(it); layer(it); it.translationX = -UNDER * w }
                if (from != null) {
                    // the page you leave is still a view: it slides away on top
                    hold(from)
                    lift(from, zUp)
                    move(from); layer(from)
                    val ups = upperExtras.filter { it !== from && it !== to }
                    ups.forEach { lift(it, zUp - Ui.dp(1f)); move(it) }
                    val sc = addScrim(DIM)
                    step = { t ->
                        from.translationX = w * t
                        for (u in ups) u.translationX = w * t
                        for (l in lowers) l.translationX = -UNDER * w * (1f - t)
                        sc.alpha = DIM * (1f - t)
                    }
                } else {
                    // the page you leave is gone (a web page): its picture slides away on top
                    val dim = ColorDrawable(Color.BLACK).apply { setBounds(0, 0, container.width, container.height); alpha = (DIM * 255).toInt() }
                    container.overlay.add(dim); overlays.add(dim)
                    val shot = snapshot?.let { BitmapDrawable(container.resources, it).apply { setBounds(0, 0, container.width, container.height) } }
                    shot?.let { container.overlay.add(it); overlays.add(it) }
                    overlayHost = container
                    step = { t ->
                        for (l in lowers) l.translationX = -UNDER * w * (1f - t)
                        dim.alpha = (DIM * 255 * (1f - t)).toInt()
                        shot?.let { s -> val x = (w * t).toInt(); s.setBounds(x, 0, x + container.width, container.height) }
                        container.invalidate()
                    }
                }
            }
        }
        step(0f)
        if (freezeAt in 0f..1f) {
            // test runs only: stay at this point of the move until finish()
            val at = ease.getInterpolation(freezeAt)
            freezeAt = -1f
            frozen = true
            step(at)
            container.postInvalidate()
            return
        }

        val a = ValueAnimator.ofFloat(0f, 1f).setDuration(ms)
        a.interpolator = ease
        var frames = 0
        var t0 = 0L
        a.addUpdateListener { v -> frames++; step(v.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { t0 = android.os.SystemClock.uptimeMillis() }
            override fun onAnimationEnd(animation: Animator) {
                // one line per move (how long it took and how many frames it drew: a phone at 60 Hz draws ~18 in 300 ms)
                android.util.Log.i("TFFX", "$kind ${ms}ms took ${android.os.SystemClock.uptimeMillis() - t0}ms, $frames frames")
                if (anim === animation) { anim = null; step(1f); finish() }
            }
        })
        // Start after the new page's first frame is drawn: building a page can take a moment the first time,
        // and the clock of the move must not run during it (that is what makes a move jump).
        var left = waitFrames.coerceAtLeast(1)
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (pending !== this) return
                if (--left > 0) { Choreographer.getInstance().postFrameCallback(this); return }
                pending = null
                anim = a
                a.start()
            }
        }
        pending = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }
}
