package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * LIVE ROOMS — native screens of src/pages/LiveRooms.jsx and LiveRoom.jsx (Audio rooms; owner rule,
 * Nov 17: Live Room is Audio only, video lives are in Live Dance).
 *   /live                → the Live Room page (the big Audio Room box, Go Live)
 *   /live?m=audio        → every Audio Room that is live now, in big cards
 *   /live/leaderboard    → the hosts' Leaderboard
 *   /live/:roomId        → inside a room (voice, seats, Super Chat, gifts, Join list …)
 *   /live?demo=1         → (admins) the demo tools, there is no Demo button on the page
 * The web page's address stays the single list of where the person is (so the phone's Back and the
 * web app agree); this screen draws it natively, and the web page under it stays quiet (WebBridge).
 */
@SuppressLint("ViewConstructor")
class LiveRoomsScreen(ctx: Context, val nav: Nav) : FrameLayout(ctx) {
    interface Nav {
        /** open a page of the app (pushes the web address) */
        fun go(path: String)
        /** replace the current web address */
        fun replace(path: String)
        /** one step back in the web history */
        fun back()
        fun askMic(done: (Boolean) -> Unit)
        fun share(title: String, text: String)
        /** a photo from the phone (report screenshots): bytes + type, or null */
        fun pickImage(done: (ByteArray?, String) -> Unit)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pages = FrameLayout(ctx)
    /** sheets, dialogs and toasts go here (above the pages) */
    val layer = FrameLayout(ctx)
    val toast = DanceToast(ctx)
    val sheets = SheetSlot()
    /** a centred pop-up (Pay & Join) — closed by Back before the sheets */
    var dialog: LiveDialog? = null

    private var homePage: LiveHomePage? = null
    private var listPage: LiveListPage? = null
    private var boardPage: LiveBoardPage? = null
    private var roomPage: LiveRoomPage? = null
    private var current: View? = null
    private var currentPath = ""
    private val stack = ArrayList<String>()
    private var shown = false
    private var appVisible = true
    /** Host profile → Send Gift: open the gifts as soon as this room is joined */
    var giftOnJoin: String? = null

    // ── the rooms list (liveRoom "home"): shared by the Live Room page and the Audio list ──
    var home: JSONObject? = null; private set
    var homeErr = ""; private set
    private var homeLoading = false
    private var homeAt = 0L
    // (the 10 s refresh never wipes an error that is showing: it just asks again quietly)
    private val homePoll = Runnable { if (homeTries == 0) loadHome(auto = true); scheduleHome() }

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true // nothing under it gets the touches
        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layer.addView(toast, DK.flp(DK.WRAP, DK.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 24f, 0f, 24f, 96f))
    }

    // ── where am I ──
    private class Target(val kind: String, val roomId: String = "", val demo: Boolean = false)
    private fun parse(full: String): Target? {
        val u = try { Uri.parse("https://x$full") } catch (e: Exception) { return null }
        val p = (u.path ?: "").trimEnd('/')
        if (p == "/live") {
            val m = u.getQueryParameter("m").orEmpty()
            return Target(if (m.isNotEmpty()) "list" else "home", demo = u.getQueryParameter("demo") == "1")
        }
        if (p == "/live/leaderboard") return Target("board")
        if (p.startsWith("/live/")) {
            val id = p.removePrefix("/live/").substringBefore('/')
            if (id.isNotEmpty()) return Target("room", id)
        }
        return null
    }
    private fun key(t: Target) = when (t.kind) { "room" -> "room:${t.roomId}"; else -> t.kind }

    /** the web address changed to a Live Room page (full = path + ?query) */
    fun route(full: String) {
        if (full == currentPath) return
        val t = parse(full) ?: return
        currentPath = full
        // Back = the page before this one in my own list; anything else is a new page (it slides in from the right)
        val k = key(t)
        val back = stack.size >= 2 && stack[stack.size - 2] == k
        if (back) stack.removeAt(stack.size - 1) else if (stack.lastOrNull() != k) stack.add(k)
        if (stack.size > 30) stack.removeAt(0)
        when (t.kind) {
            "home" -> {
                val hp = homePage ?: LiveHomePage(context, this).also { homePage = it }
                closeRoom()
                show(hp, !back)
                if (t.demo) main.postDelayed({ if (current === hp) hp.openDemoIfAdmin() }, 300)
            }
            "list" -> {
                val lp = listPage ?: LiveListPage(context, this).also { listPage = it }
                closeRoom()
                show(lp, !back)
            }
            "board" -> {
                val bp = boardPage ?: LiveBoardPage(context, this).also { boardPage = it }
                closeRoom()
                show(bp, !back)
                bp.onShown()
            }
            "room" -> {
                val rp = roomPage
                if (rp != null && rp.roomId == t.roomId) { show(rp, false); return }
                closeRoom()
                val page = LiveRoomPage(context, this, t.roomId)
                roomPage = page
                show(page, !back)
                page.start()
            }
        }
        if (t.kind == "home" || t.kind == "list") { if (home == null || System.currentTimeMillis() - homeAt > 4000) loadHome() }
        syncActive()
    }

    /** only the page on top asks the server */
    private fun syncActive() {
        val on = shown && appVisible
        // a room keeps asking the server under a web pop-up (shown = false), only its sound stops
        roomPage?.setActive(appVisible && current === roomPage, sound = on && current === roomPage)
        val listOn = on && (current === homePage || current === listPage)
        main.removeCallbacks(homePoll)
        if (listOn) scheduleHome()
    }
    private fun scheduleHome() {
        main.removeCallbacks(homePoll)
        if (shown && appVisible && (current === homePage || current === listPage)) main.postDelayed(homePoll, 10_000)
    }

    /**
     * The live rooms list ("home"). A failure that passes (the server busy for a moment — the platform's read
     * limit — or a weak network) is asked again by itself, 1.5 s, 4 s and 8 s later, while the page keeps showing
     * the loading outline (owner, 19 Nov: the page showed "Something went wrong · Try again", and Try again too).
     * Only when that fails as well does the error show; a list that was already there stays on screen.
     */
    private var homeTries = 0
    private val homeWaits = longArrayOf(1500L, 4000L, 8000L)
    private val homeRetry = Runnable { if (shown && appVisible) loadHome(auto = true) }
    fun loadHome() = loadHome(auto = false)
    private fun loadHome(auto: Boolean) {
        if (!auto) { homeTries = 0; main.removeCallbacks(homeRetry); if (home == null && homeErr.isNotEmpty()) { homeErr = ""; homePage?.render(); listPage?.render() } }
        if (homeLoading) return
        homeLoading = true
        LiveApi.call("home") { r ->
            homeLoading = false
            if (r.ok) { home = r.data; homeErr = ""; homeAt = System.currentTimeMillis(); homeTries = 0 }
            else if (home == null) {
                if (passing(r) && homeTries < homeWaits.size) {
                    // asked again by itself; the loading outline stays meanwhile (a busy server says how long to
                    // wait: then it is that long, up to 10 s — asking sooner would only be told "busy" again)
                    val said = (r.data.optDouble("retry_after", 0.0) * 1000).toLong().coerceIn(0L, 10_000L)
                    main.removeCallbacks(homeRetry); main.postDelayed(homeRetry, maxOf(homeWaits[homeTries++], said))
                    return@call
                }
                homeTries = 0
                homeErr = homeError(r)
            }
            homePage?.render(); listPage?.render()
        }
    }
    /** a failure that passes by itself: no answer, the server busy, or the server failing for a moment */
    private fun passing(r: LiveApi.Res): Boolean = r.status == 0 || r.status == 408 || r.status == 429 || r.status in 500..599 || r.code == "busy"
    /** what the page says when the list could not be loaded (with the server's short code, to find the cause) */
    private fun homeError(r: LiveApi.Res): String = when {
        r.status == 0 -> "Please check your internet and try again."
        r.status == 429 || r.code == "busy" -> "Live Rooms are busy right now. Please try again in a moment."
        else -> r.text("Could not load live rooms.").let { t -> if (r.status >= 400) "$t (${r.status})" else t }
    }

    private fun show(v: View, forward: Boolean) {
        val old = current
        if (old === v) { syncActive(); return }
        if (v.parent == null) pages.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        v.bringToFront()
        current = v
        sheets.close()
        dialog?.close()
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
        if (v === roomPage || v is LiveRoomPage) pages.removeView(v) else v.visibility = View.GONE
    }

    private fun closeRoom() {
        roomPage?.let { it.close(); if (it !== current) pages.removeView(it) }
        roomPage = null
    }

    /** visible or not (another page of the app / a web pop-up such as an incoming call is on top) */
    fun setShown(on: Boolean) {
        if (on == shown) return
        shown = on
        visibility = if (on) View.VISIBLE else View.GONE
        if (!on) { sheets.close(); dialog?.close() }
        syncActive()
    }

    /** the person left Live Rooms (another page of the app) */
    fun leave() {
        sheets.close()
        dialog?.close()
        closeRoom()
        currentPath = ""
        stack.clear()
        current?.let { if (it is LiveRoomPage) pages.removeView(it) else it.visibility = View.GONE }
        current = null
        main.removeCallbacks(homePoll)
        main.removeCallbacks(homeRetry); homeTries = 0
        setShown(false)
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        syncActive()
        if (v && shown && (current === homePage || current === listPage) && System.currentTimeMillis() - homeAt > 4000) loadHome()
    }

    /** phone back: a pop-up or sheet closes first; true = handled here */
    fun onBack(): Boolean {
        dialog?.let { if (it.isOpen) { it.close(); return true } }
        if (sheets.close()) return true
        roomPage?.let { if (it === current && it.onBack()) return true }
        return false
    }

    fun showToast(s: String) = toast.show(s)

    /** the page on top (tests) */
    val currentPage: View? get() = current
}

/**
 * A centred pop-up over the Live Room (JoinDialog in LiveRoom.jsx): dims the screen, the card pops in,
 * a tap outside or Back closes it.
 */
@SuppressLint("ViewConstructor")
class LiveDialog(ctx: Context, private val screen: LiveRoomsScreen) : FrameLayout(ctx) {
    private val dim = View(ctx).apply { setBackgroundColor(Ui.ca("#0e061e", 0.6f)); alpha = 0f }
    val card = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(26f) }
        elevation = Ui.dp(20f)
        setPadding(Ui.dpi(22f), Ui.dpi(26f), Ui.dpi(22f), Ui.dpi(22f))
        isClickable = true
    }
    var isOpen = false; private set
    private var closing = false
    var onClosed: (() -> Unit)? = null

    init {
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dim.setOnClickListener { close() }
        val box = FrameLayout(ctx)
        box.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        box.addView(DK.iconButton(ctx, DIcon(DIcon.X, DK.INK, 22f), 38f, null, "Close").apply { setOnClickListener { close() } },
            LayoutParams(Ui.dpi(38f), Ui.dpi(38f), Gravity.TOP or Gravity.END).apply { topMargin = Ui.dpi(10f); rightMargin = Ui.dpi(10f) })
        addView(box, LayoutParams(Ui.dpi(340f).coerceAtMost(ctx.resources.displayMetrics.widthPixels - Ui.dpi(44f)), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun open(): LiveDialog {
        if (isOpen) return this
        screen.dialog?.close()
        screen.sheets.close()
        isOpen = true
        screen.dialog = this
        screen.layer.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dim.animate().alpha(1f).setDuration(200).start()
        card.alpha = 0f; card.translationY = Ui.dp(8f)
        card.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DK.EASE_OUT).start()
        return this
    }

    fun close() {
        if (closing || !isOpen) return
        closing = true
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (e: Exception) { }
        card.animate().alpha(0f).setDuration(180).start()
        dim.animate().alpha(0f).setDuration(200).withEndAction {
            screen.layer.removeView(this); isOpen = false; closing = false
            if (screen.dialog === this) screen.dialog = null
            onClosed?.invoke()
        }.start()
    }
}
