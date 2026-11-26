package app.truefind.android

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout

/**
 * A page that stays ready while it is hidden (Home, Explore, Recent, Profile, Wallet, Reconnect).
 *
 * Why: when Android hides a view completely (GONE), it throws away everything it had drawn of it — and of
 * every view inside. Showing the page again then draws all of it from nothing in one frame; on the owner's
 * phone that one frame took so long that the page move after it jumped most of the way in one step.
 * FRND's tabs are a ViewPager: its neighbour pages are never thrown away, only moved off the screen, so a
 * tab slide there only moves two finished pictures.
 *
 * So a KeepPage that has been shown once is never GONE again. Hidden means:
 * - INVISIBLE (the views inside know they are hidden and stop their own animations; no touches, no focus),
 * - moved off the screen,
 * - and [PageHost] still hands its drawing to Android, which therefore keeps it.
 * Android would also tell every view inside "your pictures are hidden / shown again", and each of them
 * would then be drawn again — the very work this is meant to save; that message is left out ([quiet]).
 * Showing the page again is then only a move.
 *
 * A page that was never shown stays really GONE (costs nothing) until [keepReady] or its first visit.
 * When the app goes to the background Android drops all drawings anyway: hidden pages then "sleep"
 * (not drawn) until they are shown or woken one by one ([wake]) — never all in the first frame back.
 */
open class KeepPage(ctx: Context) : FrameLayout(ctx) {
    /** hidden, but its drawing is kept (off the screen) */
    var parked = false; private set
    /** hidden and not drawn for now (after the app was in the background) */
    var asleep = false; private set
    private var kept = false
    private var quiet = false
    private var loud = false
    private var specW = 0; private var specH = 0; private var sized = false
    private var resized = false

    /** (while this page itself is shown / hidden: Android skips telling the views inside about their pictures) */
    override fun getWindowVisibility(): Int = if (quiet) GONE else super.getWindowVisibility()

    private fun flip(v: Int) {
        if (v == super.getVisibility()) return
        // the first time it shows after the app was in the background, every view inside is told as usual
        if (loud && v == VISIBLE) { loud = false; super.setVisibility(v); return }
        quiet = true
        try { super.setVisibility(v) } finally { quiet = false }
    }

    override fun setVisibility(v: Int) {
        if (v == VISIBLE) {
            val was = kept
            kept = true
            asleep = false
            if (parked) {
                parked = false
                translationY = 0f
                // the screen changed size while it was hidden (the keyboard, the bottom bar): fit it now — every view
                // inside is measured again, not only the page (see [relayoutAll]: the owner's blank Wallet, 20 Nov)
                if (resized) { resized = false; if (!testNoHeal) relayoutAll(this); requestLayout() }
            }
            if (was) flip(VISIBLE) else super.setVisibility(VISIBLE)
            // a moment after it shows: anything inside that was never laid out means a layout went missing → all again
            removeCallbacks(healCheck); postDelayed(healCheck, 350)
        } else if (kept) {
            if (!parked) { parked = true; translationY = away() }
            removeCallbacks(healCheck)
            flip(INVISIBLE)
        } else { removeCallbacks(healCheck); super.setVisibility(v) }
    }

    /** times the safety net below had to lay a page out again (the page speed report shows it) */
    var healed = 0; private set
    // (a page where laying everything out again changes nothing — some view that is never laid out on purpose —
    // is not laid out again and again: after one useless try the safety net rests for this page)
    private var healUseless = false
    private var healAt = 0L
    private val healCheck = Runnable {
        if (testNoHeal || healUseless || visibility != VISIBLE || parked || !isAttachedToWindow) return@Runnable
        if (!hasUnlaid(this)) return@Runnable
        if (healAt > 0 && System.currentTimeMillis() - healAt < 2000) { healUseless = true; return@Runnable }
        healed++; healedAll++
        healAt = System.currentTimeMillis()
        relayoutAll(this); requestLayout()
        // checked once more: still something never laid out → laying out again doesn't help here
        postDelayed({ if (visibility == VISIBLE && !parked && hasUnlaid(this)) healUseless = true else healAt = 0L }, 350)
    }
    /** a shown view (in a shown parent) that has never been through a layout */
    private fun hasUnlaid(v: View): Boolean {
        if (v.visibility != VISIBLE) return false
        if (!v.isLaidOut) return true
        if (v is android.view.ViewGroup && v !is androidx.recyclerview.widget.RecyclerView) {
            for (i in 0 until v.childCount) if (hasUnlaid(v.getChildAt(i))) return true
        }
        return false
    }

    private fun away(): Float = -4f * maxOf(height, resources.displayMetrics.heightPixels)

    /**
     * Gets a page that was never shown ready in the background (laid out and drawn off the screen), so its
     * first visit is as quick as the later ones. Nothing is loaded and nothing shows.
     */
    fun keepReady() {
        if (kept || !isAttachedToWindow) return
        kept = true
        parked = true
        translationY = away()
        super.setVisibility(INVISIBLE)
    }

    /** The app went to the background: Android drops every drawing; a hidden page is not drawn again until [wake] or its next visit. */
    fun appStopped() {
        loud = true
        if (parked) asleep = true
    }
    /** A sleeping page is drawn again (off the screen), ready for its next visit. */
    fun wake() {
        if (!asleep) return
        asleep = false
        (parent as? View)?.invalidate()
    }

    companion object {
        /**
         * Every view inside [v] is marked "measure me again" (Android's forceLayout, which goes no further up).
         *
         * Why (owner, 20 Nov: both Wallets showed only their header and an empty white sheet, from every way in):
         * while a page is hidden and the screen's size changes, [onMeasure] keeps its old size without measuring
         * what is inside. If something inside had just asked to be laid out (an answer from the server redrew the
         * Wallet while the keyboard was open in a chat), that request stays half done: the views below it keep the
         * mark "waiting for layout", and every later request from inside stops at that mark instead of reaching the
         * screen. Nothing new inside the page was ever measured again — new rows had no size and showed nothing,
         * for the rest of the session. Marking all of them before the page shows again clears every such mark.
         */
        fun relayoutAll(v: View) {
            v.forceLayout()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) relayoutAll(v.getChildAt(i))
        }
        /** all pages together: how often a missing layout was found and fixed (page speed report) */
        @Volatile var healedAll = 0; private set
        /** device tests only: the old behaviour (no fresh layout when shown again), to show the problem itself */
        @Volatile var testNoHeal = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // hidden and the screen's size changes (the keyboard opens somewhere else): it keeps its size until shown
        // (and when it shows again, everything inside is measured afresh: see relayoutAll)
        if (parked && sized && (widthMeasureSpec != specW || heightMeasureSpec != specH)) {
            resized = true
            setMeasuredDimension(measuredWidth, measuredHeight)
            return
        }
        specW = widthMeasureSpec; specH = heightMeasureSpec; sized = true
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** The app's screen: every page is one of its children. Hidden [KeepPage]s are still handed to Android's drawing. */
class PageHost(ctx: Context) : FrameLayout(ctx) {
    private var failed = false
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (failed || !canvas.isHardwareAccelerated) return
        try {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                // (off the screen: nothing of it is painted, but Android keeps what it has drawn of it)
                if (c is KeepPage && c.parked && !c.asleep && c.visibility == View.INVISIBLE) drawChild(canvas, c, drawingTime)
            }
        } catch (e: Throwable) {
            // never worth a crash: from here on hidden pages are simply drawn again when they show
            failed = true
            android.util.Log.w("TFFX", "keeping hidden pages failed", e)
        }
    }
}
