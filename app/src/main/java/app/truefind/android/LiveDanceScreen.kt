package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.FrameLayout

/**
 * LIVE DANCE — native screens of src/pages/LiveDance.jsx.
 *   /dance          → the 3 big mode boxes
 *   /dance?m=solo   → the lives of one mode
 *   /dance/:roomId  → inside a live (entry fee → watch, chat, gifts, votes …)
 * The web page's address stays the single list of where the person is (so the
 * phone's back button and the web app agree); this screen draws it natively.
 * Everything is native: watching and dancing, and the host side (Go Live, host tools).
 * A "?web=1" address would leave the page to the web (not used by these screens).
 */
@SuppressLint("ViewConstructor")
class LiveDanceScreen(ctx: Context, val nav: Nav) : FrameLayout(ctx) {
    interface Nav {
        /** open a page of the app (pushes the web address) */
        fun go(path: String)
        /** replace the current web address */
        fun replace(path: String)
        /** one step back in the web history */
        fun back()
        fun askCameraMic(done: (Boolean) -> Unit)
        fun share(title: String, text: String)
    }

    private val pages = FrameLayout(ctx)
    /** sheets, toasts and overlays go here (above the pages) */
    val layer = FrameLayout(ctx)
    val toast = DanceToast(ctx)
    private var listPage: DanceListPage? = null
    private var roomPage: DanceRoomPage? = null
    private var current: View? = null
    private var currentPath = ""
    private var shown = false
    private var appVisible = true
    val sheets = SheetSlot()

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true // nothing under it gets the touches
        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layer.addView(toast, DK.flp(DK.WRAP, DK.WRAP, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 24f, 0f, 24f, 96f))
    }

    // ── where am I ──
    private class Target(val kind: String, val mode: String, val roomId: String)
    private fun parse(full: String): Target? {
        val u = Uri.parse("https://x$full")
        val p = (u.path ?: "").trimEnd('/')
        if (p == "/dance") {
            val m = u.getQueryParameter("m").orEmpty()
            return Target("list", if (DK.MODES.contains(m)) m else "", "")
        }
        if (p.startsWith("/dance/")) {
            val id = p.removePrefix("/dance/").substringBefore('/')
            if (id.isNotEmpty()) return Target("room", "", id)
        }
        return null
    }

    /** the web address changed to a Live Dance page (full = path + ?query) */
    fun route(full: String) {
        if (full == currentPath) return
        val t = parse(full) ?: return
        val prev = parse(currentPath)
        currentPath = full
        val deeper = prev == null || depth(t) >= depth(prev)
        when (t.kind) {
            "list" -> {
                val lp = listPage ?: DanceListPage(context, this).also { listPage = it }
                closeRoom()
                lp.setMode(t.mode, animate = current === lp && prev != null)
                show(lp, deeper && current !== lp)
                lp.onShown()
            }
            "room" -> {
                val rp = roomPage
                if (rp != null && rp.roomId == t.roomId) { show(rp, false); return }
                closeRoom()
                val page = DanceRoomPage(context, this, t.roomId)
                roomPage = page
                show(page, true)
                page.start()
            }
        }
    }
    private fun depth(t: Target) = when { t.kind == "room" -> 2; t.mode.isNotEmpty() -> 1; else -> 0 }

    /** only the page on top asks the server */
    private fun syncActive() {
        listPage?.setActive(shown && appVisible && current === listPage)
        roomPage?.setActive(shown && appVisible && current === roomPage)
    }

    private fun show(v: View, forward: Boolean) {
        val old = current
        if (old === v) { syncActive(); return }
        if (v.parent == null) pages.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        v.bringToFront()
        current = v
        sheets.close()
        syncActive()
        val w = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        if (old == null || !shown) {
            v.translationX = 0f; v.alpha = 1f; v.visibility = View.VISIBLE
            old?.let { detach(it) }
            return
        }
        v.visibility = View.VISIBLE
        if (forward) {
            v.translationX = w; v.alpha = 1f
            v.animate().translationX(0f).setDuration(300).setInterpolator(DK.EASE).withEndAction { detach(old) }.start()
            old.animate().translationX(-w * 0.25f).setDuration(300).setInterpolator(DK.EASE).start()
        } else {
            // going back: the page underneath comes back, the old one slides away
            pages.bringChildToFront(old)
            v.translationX = -w * 0.25f
            v.animate().translationX(0f).setDuration(280).setInterpolator(DK.EASE).start()
            old.animate().translationX(w).setDuration(280).setInterpolator(DK.EASE).withEndAction { detach(old) }.start()
        }
    }

    private fun detach(v: View) {
        if (v === current) return
        v.animate().cancel()
        v.translationX = 0f
        if (v === listPage) v.visibility = View.GONE else pages.removeView(v)
    }

    private fun closeRoom() {
        roomPage?.let { it.close(); if (it !== current) pages.removeView(it) }
        roomPage = null
    }

    /** visible or not (the web page under it shows something else / a pop-up is on top) */
    fun setShown(on: Boolean) {
        if (on == shown) return
        shown = on
        visibility = if (on) View.VISIBLE else View.GONE
        syncActive()
    }

    /** the person left Live Dance (another page of the app) */
    fun leave() {
        sheets.close()
        closeRoom()
        currentPath = ""
        current?.let { if (it !== listPage) pages.removeView(it) }
        current = null
        listPage?.let { it.visibility = View.GONE; it.setActive(false) }
        setShown(false)
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        syncActive()
    }

    /** phone back: close a sheet first; true = handled here */
    fun onBack(): Boolean {
        if (sheets.close()) return true
        roomPage?.let { if (it === current && it.onBack()) return true }
        return false
    }

    fun showToast(s: String) = toast.show(s)
}
