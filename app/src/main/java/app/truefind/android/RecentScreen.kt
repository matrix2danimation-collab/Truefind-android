package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * True Find · RECENT (the bottom-bar tab "Recent", address /chats) — native copy of pages/Chats.jsx.
 * Only your recent voice and video calls: one row per person (getRecentActivity), each with
 * what the last call was and when, and Voice / Video buttons that call again at once (no
 * Online / Offline on the rows: owner, 19 Nov). Tap a person → the small profile card (Follow, Voice / Video Call);
 * tap its round photo → the full profile (RecentPerson). The last tapped row keeps a purple outline.
 * Calls start with startCall and open the web's call screen (/call/:id); no coins → "Add coins" (Wallet),
 * busy / not available / failed → the same messages as the web. "Couldn't load" → Try again.
 * Fresh every time the tab opens, every 45 s while it is on screen, and when you pull down at the top.
 */
@SuppressLint("ViewConstructor")
class RecentScreen(ctx: Context, val pages: NativePages) : KeepPage(ctx) {

    /** one row: the person and their last call with you (rowsFrom of Chats.jsx) */
    class Row(
        val otherId: String, val name: String, val age: String, val photo: String, val full: String, val verified: Boolean,
        val online: Boolean, val busy: Boolean, val lastSeen: String, val type: String, val out: Boolean, val status: String, val time: String,
    ) {
        val sig: String get() = listOf(otherId, name, photo, online, busy, lastSeen, time, status, type, out).joinToString("|")
    }

    private val main = Handler(Looper.getMainLooper())

    // ── data ──
    private var calls: List<Row>? = null
    private var failed = false
    private var sig = ""
    private var loaded = false
    private var loadSeq = 0
    /** the sign-in the list belongs to (someone else signs in → the list starts again) */
    private var dataToken = ""
    /** the person whose box keeps the purple outline (the last one tapped) */
    private var picked = ""
    /** "<id>-voice" / "<id>-video" while that call is starting */
    var calling = ""; private set
    private var shown = false
    private var appVisible = true

    // ── views ──
    private val backdrop = RecentBackdrop(ctx)
    private val pull = PullHost(ctx)
    private val scroll = ScrollView(ctx)
    private val column = RecentMaxWidth(ctx, 480f)
    private val listBox = LinearLayout(ctx)
    private val spinner = PullSpinner(ctx)
    private val rows = HashMap<String, RowView>()
    private val callBtns = ArrayList<Pair<String, RecentCallBtn>>()

    // ── what is open on top (the small card, the full profile, a message) ──
    var person: RecentPerson? = null; private set
    private var closeDialog: (() -> Unit)? = null

    init {
        setBackgroundColor(Ui.c("#FCFAFF"))
        clipChildren = false
        addView(backdrop, LayoutParams(-1, -1))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        scroll.clipChildren = false
        pull.addView(scroll, LayoutParams(-1, -1))
        addView(pull, LayoutParams(-1, -1))
        val content = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        scroll.addView(content, LayoutParams(-1, -2))
        column.clipChildren = false; column.clipToPadding = false
        // the web's padding: 18 above (in the first line's margin), 14 at the sides, 120 under the list (the bar sits over the page)
        column.setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), Ui.dpi(120f))
        content.addView(column, LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))

        // "True Find" (24, line-height 1)
        val name = SpannableString("True Find").apply {
            setSpan(ForegroundColorSpan(RC.ink), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(RC.burgundy), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val word = Tf.t(ctx, name, 24f, RC.ink).apply { letterSpacing = -0.005f }
        column.addView(word, RC.line(word, 24f, topDp = 18f))
        // (clock) Recent — 27, line-height 1.1
        val h1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h1.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.Clock, RC.purple, 24f, 2.2f)) }, LinearLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f)).apply { rightMargin = Ui.dpi(8f) })
        val h1t = Tf.t(ctx, "Recent", 27f, RC.ink)
        h1.addView(h1t, LinearLayout.LayoutParams(-2, -2))
        h1.contentDescription = "Recent"
        column.addView(h1, LinearLayout.LayoutParams(-2, -2).apply { val h = RC.half(h1t, 29.7f); topMargin = Ui.dpi(16f) + h; bottomMargin = h })
        val subT = Tf.t(ctx, "Your recent voice and video calls", 14.5f, RC.sub, false)
        column.addView(subT, RC.line(subT, 21.75f, topDp = 4f))
        listBox.orientation = LinearLayout.VERTICAL
        listBox.clipChildren = false; listBox.clipToPadding = false
        column.addView(listBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(16f) })

        // pull down at the top: the list loads again (five-dot True Find loader)
        spinner.elevation = Ui.dp(20f)
        addView(spinner, LayoutParams(Ui.dpi(42f), Ui.dpi(42f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(4f) })
        pull.canPull = { scroll.scrollY <= 0 && !pages.layer.open && !spinner.spinning }
        pull.onPull = { d -> spinner.pull(d, 64f) }
        pull.onRelease = { d -> if (d >= 64f) refreshNow() else spinner.hide() }
        render()
    }

    // ═════════════════ shown / hidden ═════════════════
    fun setShown(on: Boolean) {
        if (on == shown) return
        shown = on
        if (on) {
            visibility = VISIBLE
            checkToken()
            // fresh each time you open Recent (Online / Busy / last seen stay fresh while it is on screen)
            load()
            schedulePoll()
        } else {
            visibility = GONE
            main.removeCallbacks(poll)
            closeAll()
        }
    }

    fun onAppVisible(v: Boolean) {
        appVisible = v
        if (!shown) return
        if (v) { load(); schedulePoll() } else main.removeCallbacks(poll)
    }

    private val poll = Runnable { if (shown && appVisible) load(); schedulePoll() }
    private fun schedulePoll() { main.removeCallbacks(poll); if (shown) main.postDelayed(poll, 45_000) }

    /** The app has opened: the list is loaded once in the background, so the first visit shows the calls at once (as the web does). */
    fun warm() {
        checkToken()
        if (loaded || calls != null || warming) return
        warming = true
        main.postDelayed({ warming = false; if (!loaded && !shown && pages.signedIn) load() }, 4000)
    }
    private var warming = false

    /** another account signed in on this phone: nothing of the last one is shown */
    private fun checkToken() {
        if (dataToken.isEmpty() || dataToken == Api.token) return
        dataToken = ""; calls = null; loaded = false; sig = ""; failed = false; picked = ""
        loadSeq++
        closeAll()
        render()
    }

    fun onBack(): Boolean = false

    /** the bottom bar's Recent tapped while Recent is open: back to the top */
    fun scrollTop() { scroll.smoothScrollTo(0, 0) }

    private fun closeAll() {
        closeDialog?.invoke(); closeDialog = null
        person?.closeAll(); person = null
    }

    // ═════════════════ data ═════════════════
    /** getRecentActivity → one row per person (only people you called or who called you) */
    fun load(done: (() -> Unit)? = null) {
        if (!Api.ready) { if (shown) main.postDelayed({ if (shown) load() }, 700); done?.invoke(); return }
        val seq = ++loadSeq
        val tok = Api.token
        Api.invoke("getRecentActivity", JSONObject()) { r ->
            if (seq != loadSeq || tok != Api.token) { done?.invoke(); return@invoke }
            if (!r.ok) {
                failed = true
                if (calls == null) { calls = emptyList(); render() } else if (calls!!.isEmpty()) render()
                done?.invoke()
                return@invoke
            }
            val list = rowsFrom(r.data)
            val s = list.joinToString("~") { it.sig }
            val wasFailed = failed
            failed = false
            if (loaded && s == sig && !wasFailed) { done?.invoke(); return@invoke } // nothing changed
            loaded = true
            dataToken = tok
            sig = s
            calls = list
            render()
            done?.invoke()
        }
    }

    private fun rowsFrom(d: JSONObject): List<Row> {
        val me = d.optString("me")
        val out = ArrayList<Row>()
        val arr = d.optJSONArray("entries") ?: return out
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val uid = e.optString("user_id")
            val c = e.optJSONObject("last_call") ?: continue
            if (uid.isEmpty() || uid == me) continue
            val at = Tf.ms(c.optString("at"), plainUtc = true)
            val status0 = PR.str(c.opt("status")).ifEmpty { "ended" }
            // a call still "ringing" after 2 minutes never connected
            val stale = status0 == "ringing" && at > 0L && System.currentTimeMillis() - at > 2 * 60 * 1000
            val nm = PR.str(e.opt("name"))
            val age = e.opt("age").let { if (it == null || it == JSONObject.NULL) "" else it.toString() }
            out.add(Row(
                otherId = uid,
                name = if (nm.isNotEmpty() && nm != "Member") nm else "Member",
                age = age,
                // the small list copy when there is one; the full photo if it can't load
                photo = PR.str(e.opt("photo_thumb")).ifEmpty { PR.str(e.opt("photo")) },
                full = PR.str(e.opt("photo")),
                verified = PR.truthy(e.opt("verified")),
                online = PR.truthy(e.opt("online")),
                busy = PR.truthy(e.opt("busy")),
                lastSeen = PR.str(e.opt("last_active")),
                type = if (c.optString("type") == "video") "video" else "voice",
                out = PR.truthy(c.opt("mine")),
                status = if (stale) "missed" else status0,
                time = PR.str(c.opt("at")),
            ))
        }
        return out
    }

    /** pull down at the top: load again; the loader turns at least 0.65 s (at most 8 s) */
    private fun refreshNow() {
        spinner.spin(22f)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val t0 = System.currentTimeMillis()
        var ended = false
        val end = {
            if (!ended) { ended = true; main.postDelayed({ spinner.hide() }, (650 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)) }
        }
        main.postDelayed({ end() }, 8000)
        load { end() }
    }

    // ═════════════════ the words on screen ═════════════════
    class Status(val label: String, val color: Int, val dot: Int)
    class Kind(val icon: String, val text: String, val full: String, val color: Int)

    companion object {
        private val IN = Locale("en", "IN")

        /** just now · 5m ago · 3h ago · 2d ago (Base44 times without a zone are UTC) */
        fun agoText(t: String?): String {
            val at = Tf.ms(t, plainUtc = true)
            if (at <= 0L) return ""
            val ms = System.currentTimeMillis() - at
            if (ms < 0) return ""
            val m = ms / 60000
            if (m < 1) return "just now"
            if (m < 60) return "${m}m ago"
            val h = m / 60
            if (h < 24) return "${h}h ago"
            return "${h / 24}d ago"
        }

        /** today: 3:05 PM · yesterday: Yesterday · else: 15 Nov */
        fun timeText(t: String?): String {
            val at = Tf.ms(t, plainUtc = true)
            if (at <= 0L) return ""
            val d = Calendar.getInstance().apply { timeInMillis = at }
            val now = Calendar.getInstance()
            fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (same(d, now)) return SimpleDateFormat("h:mm a", IN).format(Date(at)).uppercase(IN)
            val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (same(d, y)) return "Yesterday"
            return SimpleDateFormat("d MMM", IN).format(Date(at))
        }

        /** Online · Free / Online · Busy / Offline · 3h ago */
        fun statusOf(online: Boolean, busy: Boolean, lastSeen: String): Status =
            if (online) (if (busy) Status("Online · Busy", RC.red, Ui.c("#EF4444")) else Status("Online · Free", RC.green, Ui.c("#22C55E")))
            else { val a = agoText(lastSeen); Status(if (lastSeen.isNotEmpty() && a.isNotEmpty()) "Offline · $a" else "Offline", RC.gray, Ui.c("#C3BDD0")) }

        /** short words on screen; the icon shows voice or video */
        fun kindOf(r: Row): Kind {
            val v = r.type == "video"
            val kind = if (v) "video call" else "voice call"
            if (r.status == "missed") return if (r.out) Kind(if (v) Lu.Video else RC.ARROW_UP_RIGHT, "No answer", "Outgoing $kind, no answer", RC.red)
                else Kind(if (v) Lu.Video else RC.PHONE_MISSED, "Missed", "Missed $kind", RC.red)
            if (r.status == "declined") return if (r.out) Kind(if (v) Lu.Video else RC.ARROW_UP_RIGHT, "Declined", "Outgoing $kind, declined", RC.red)
                else Kind(if (v) Lu.Video else RC.ARROW_DOWN_LEFT, "You declined", "Incoming $kind, you declined", RC.gray)
            if (v) return Kind(Lu.Video, if (r.out) "Outgoing" else "Incoming", "${if (r.out) "Outgoing" else "Incoming"} $kind", RC.purple)
            return if (r.out) Kind(RC.ARROW_UP_RIGHT, "Outgoing", "Outgoing $kind", RC.purple) else Kind(RC.ARROW_DOWN_LEFT, "Incoming", "Incoming $kind", RC.green)
        }
    }

    // ═════════════════ drawing the list ═════════════════
    private fun render() {
        val ctx = context
        listBox.removeAllViews()
        rows.clear(); callBtns.clear()
        val list = calls
        when {
            // loading: five grey boxes
            list == null -> for (i in 0 until 5) listBox.addView(RecentShimmer(ctx, 16f, Ui.c("#F3EEFA"), Ui.c("#FBF8FE")), gapLp(i, Ui.dpi(78f)))
            failed && list.isEmpty() -> listBox.addView(errorCard(), gapLp(0))
            list.isEmpty() -> listBox.addView(emptyCard(), gapLp(0))
            else -> list.forEachIndexed { i, r ->
                val v = RowView(ctx, r)
                rows[r.otherId] = v
                listBox.addView(v, gapLp(i))
            }
        }
        refreshRowCalls()
    }

    private fun gapLp(i: Int, h: Int = -2) = LinearLayout.LayoutParams(-1, h).apply { if (i > 0) topMargin = Ui.dpi(8f) }

    /** "Couldn't load your calls" + Try again */
    private fun errorCard(): View {
        val ctx = context
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Tf.box(RC.card, 16f, 1f, RC.line)
            setPadding(Ui.dpi(19f), Ui.dpi(27f), Ui.dpi(19f), Ui.dpi(27f))
        }
        val t = Tf.t(ctx, "Couldn't load your calls", 17f, RC.ink).apply { gravity = Gravity.CENTER }
        c.addView(t, RC.line(t, 25.5f, LinearLayout.LayoutParams.MATCH_PARENT))
        val s = Tf.t(ctx, "Check your internet and try again.", 14f, RC.sub, false).apply { gravity = Gravity.CENTER }
        c.addView(s, RC.line(s, 21f, LinearLayout.LayoutParams.MATCH_PARENT, topDp = 4f))
        val b = Tf.t(ctx, "Try again", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = RC.btn(12f); setPadding(Ui.dpi(22f), 0, Ui.dpi(22f), 0)
            setOnClickListener { calls = null; render(); load() }
        }
        Ui.pressable(b, 0.95f)
        c.addView(b, LinearLayout.LayoutParams(-2, Ui.dpi(42f)).apply { topMargin = Ui.dpi(14f) })
        return c
    }

    /** "No recent calls" */
    private fun emptyCard(): View {
        val ctx = context
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Tf.box(RC.card, 16f, 1f, RC.line)
            setPadding(Ui.dpi(21f), Ui.dpi(35f), Ui.dpi(21f), Ui.dpi(35f))
        }
        val ic = FrameLayout(ctx).apply { background = Tf.circle(RC.soft) }
        ic.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(RC.PHONE, RC.purple, 26f)) }, LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER))
        c.addView(ic, LinearLayout.LayoutParams(Ui.dpi(58f), Ui.dpi(58f)))
        val t = Tf.t(ctx, "No recent calls", 18f, RC.ink).apply { gravity = Gravity.CENTER }
        c.addView(t, RC.line(t, 27f, LinearLayout.LayoutParams.MATCH_PARENT, topDp = 12f))
        val s = Tf.t(ctx, "Your voice and video calls will show here.", 14.5f, RC.sub, false).apply { gravity = Gravity.CENTER }
        c.addView(s, RC.line(s, 21.75f, LinearLayout.LayoutParams.MATCH_PARENT, topDp = 4f))
        return c
    }

    /** One person: photo with the status dot, name (+ tick), what the last call was, status · time, Voice, Video. */
    @SuppressLint("ViewConstructor")
    inner class RowView(ctx: Context, val r: Row) : LinearLayout(ctx) {
        val box = RowBox()

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            background = box
            box.setPicked(picked == r.otherId, false)
            // the content sits 11 in from the box edge (13 on the left), in both looks (the web pads 1 less when the line is 2)
            setPadding(Ui.dpi(13f), Ui.dpi(11f), Ui.dpi(11f), Ui.dpi(11f))
            val k = kindOf(r)
            val nm = r.name.ifEmpty { "Someone" }
            // Recent never shows whether someone is online or offline (owner, 19 Nov): no status words, no status dot
            contentDescription = "$nm. ${k.full}. Tap to see profile"

            val face = RecentFace(ctx, 54f).apply { setPhoto(listOf(r.photo, r.full)) }
            addView(face, LayoutParams(Ui.dpi(54f), Ui.dpi(54f)))

            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            // name, age (+ tick)
            val nameT = Tf.t(ctx, nm + if (r.age.isNotEmpty()) ", ${r.age}" else "", 16.5f, RC.ink).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
            val l1 = RecentLine(ctx, Ui.dpi(24.75f)).add(nameT, shrinks = true)
            if (r.verified) l1.add(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.BadgeCheck, RC.purple, 16f, 2.2f)) }, 5f, Ui.dpi(16f), Ui.dpi(16f))
            col.addView(l1, LayoutParams(-1, Ui.dpi(24.75f)))
            // Outgoing / Incoming / Missed / No answer / Declined / You declined
            val kindT = Tf.t(ctx, k.text, 13.5f, if (k.color == RC.red) RC.red else RC.sub, false).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
            val l2 = RecentLine(ctx, Ui.dpi(20.25f))
                .add(ImageView(ctx).apply { setImageDrawable(Tf.lu(k.icon, k.color, 14f, 2.3f)) }, 0f, Ui.dpi(14f), Ui.dpi(14f))
                .add(kindT, 4f, shrinks = true)
            col.addView(l2, LayoutParams(-1, Ui.dpi(20.25f)).apply { topMargin = Ui.dpi(2f) })
            // the time of the call (no "Online · Free" / "Offline · …" before it)
            val timeT = Tf.t(ctx, timeText(r.time), 12.5f, RC.gray, false).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
            val l3 = RecentLine(ctx, Ui.dpi(19.5f))
                .add(timeT, 0f, shrinks = true)
            col.addView(l3, LayoutParams(-1, Ui.dpi(19.5f)).apply { topMargin = Ui.dpi(3f) })
            addView(col, LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dpi(12f) })

            for (type in listOf("voice", "video")) {
                val b = RecentCallBtn(ctx, type == "video").apply {
                    contentDescription = "${if (type == "video") "Video" else "Voice"} call $nm"
                    setOnClickListener { startCall(r, type) }
                }
                callBtns.add("${r.otherId}-$type" to b)
                addView(b, LayoutParams(Ui.dpi(42f), Ui.dpi(42f)).apply { leftMargin = Ui.dpi(12f) })
            }
            setOnClickListener { pick(r) }
        }

        // a finger anywhere on the row (its buttons too) gives the light purple line, as :active does on the web
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> box.setPressed(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> box.setPressed(false)
            }
            return super.dispatchTouchEvent(e)
        }
    }

    /** 1st tap on a person → the small profile card (their box keeps a purple outline) */
    private fun pick(r: Row) {
        if (picked != r.otherId) {
            rows[picked]?.box?.setPicked(false, true)
            picked = r.otherId
            rows[picked]?.box?.setPicked(true, true)
        }
        person?.closeAll()
        person = RecentPerson(this, r).also { it.openCard() }
    }

    /** the card / full profile closed itself */
    fun personClosed(p: RecentPerson) { if (person === p) person = null }

    // ═════════════════ calls ═════════════════
    /** the row buttons: the one whose call is starting turns, the others wait at 60 % */
    private fun refreshRowCalls() {
        for ((key, b) in callBtns) b.state(calling == key, calling.isNotEmpty() && calling != key)
    }
    private fun refreshCalls() {
        refreshRowCalls()
        person?.refresh()
    }

    fun startCall(r: Row, type: String) {
        if (calling.isNotEmpty() || r.otherId.isEmpty()) return
        calling = "${r.otherId}-$type"
        refreshCalls()
        Api.invoke("startCall", JSONObject().put("to_user_id", r.otherId).put("type", type).put("app", "truefind")) { res ->
            val d = res.data
            val id = d.optJSONObject("call")?.optString("id").orEmpty().ifEmpty { d.optString("call_id") }
            calling = ""
            refreshCalls()
            if (res.ok && id.isNotEmpty()) {
                person?.closeAll(); person = null
                // the call screen knows at once who is being called and whether it is a video call
                pages.go("/call/$id", JSONObject().put("type", type).put("peer", JSONObject().put("name", r.name).put("photo", r.photo.ifEmpty { r.full })))
                return@invoke
            }
            val st = res.status
            val err = d.optString("error")
            when {
                res.ok -> dialog("Couldn't connect", "Could not start the call. Please try again.")
                st == 402 || err == "insufficient_coins" || err == "insufficient_balance" -> {
                    val rate = d.opt("rate")?.takeIf { it != JSONObject.NULL && PR.truthy(it) }
                    dialog("Add coins to call", if (rate != null) "${if (type == "video") "Video" else "Voice"} calls cost $rate coins per minute." else "You need coins in your wallet to make this call.", coins = true)
                }
                err == "busy" || st == 409 -> dialog("${r.name.ifEmpty { "They" }} ${if (r.name.isNotEmpty()) "is" else "are"} busy", "They are on another call right now. Please try again in a few minutes.")
                st == 403 || st == 404 -> dialog("Can't call right now", if (err == "banned") "Your account can't make calls." else err.ifEmpty { d.optString("message") }.ifEmpty { "This person is not available." })
                else -> dialog("Couldn't connect", d.optString("message").ifEmpty { err }.ifEmpty { res.error ?: "Something went wrong. Please try again." })
            }
        }
    }

    // ═════════════════ the message box ═════════════════
    /** The Recent message box: title, text and OK — or Not now / Add coins (→ Wallet). Tap outside closes it. */
    private fun dialog(title: String, text: String, coins: Boolean = false) {
        closeDialog?.invoke()
        val ctx = context
        val box = FrameLayout(ctx).apply { setBackgroundColor(Color.argb(107, 28, 6, 60)); contentDescription = title }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = ShadowBox(Color.WHITE, 20f, 24f, 50f, -20f, Color.argb(128, 59, 7, 100))
            setPadding(Ui.dpi(18f), Ui.dpi(22f), Ui.dpi(18f), Ui.dpi(16f))
            isClickable = true
        }
        val t = Tf.t(ctx, title, 20f, RC.ink).apply { gravity = Gravity.CENTER }
        card.addView(t, RC.line(t, 30f, LinearLayout.LayoutParams.MATCH_PARENT))
        val s = Tf.t(ctx, text, 15f, RC.sub, false).apply { gravity = Gravity.CENTER }
        val sh = RC.half(s, 21.75f)
        s.lineH(21.75f)
        card.addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f) + sh; bottomMargin = Ui.dpi(18f) + sh })
        var close: () -> Unit = {}
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, filled: Boolean, onTap: () -> Unit): TextView = Tf.t(ctx, label, 15.5f, if (filled) Color.WHITE else RC.sub).apply {
            gravity = Gravity.CENTER
            background = if (filled) RC.btn(12f) else Tf.box(Color.WHITE, 12f, 1f, RC.line)
            setOnClickListener { onTap() }
            Ui.pressable(this, 0.95f)
        }
        if (coins) {
            row.addView(btn("Not now", false) { close() }, LinearLayout.LayoutParams(0, Ui.dpi(46f), 1f).apply { rightMargin = Ui.dpi(10f) })
            row.addView(btn("Add coins", true) { close(); person?.closeAll(); person = null; pages.go("/wallet") }, LinearLayout.LayoutParams(0, Ui.dpi(46f), 1f))
        } else {
            row.addView(btn("OK", true) { close() }, LinearLayout.LayoutParams(0, Ui.dpi(46f), 1f))
        }
        card.addView(row, LinearLayout.LayoutParams(-1, Ui.dpi(46f)))
        val hostW = pages.rootView().width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        box.addView(card, LayoutParams(minOf(Ui.dpi(340f), hostW - Ui.dpi(48f)), -2, Gravity.CENTER))
        box.setOnClickListener { close() }
        close = pages.layer.fade(box, 40f) { if (closeDialog === close) closeDialog = null }
        closeDialog = close
    }

    // ═════════════════ test screens (emulator only, made-up data) ═════════════════
    fun previewOpen(what: String) {
        val list = calls.orEmpty()
        when (what) {
            "card" -> list.firstOrNull()?.let { pick(it) }
            // test runs: the card with its profile already there (no loading blocks); "cardplain" also without the lavender panel
            "cardready", "cardplain" -> list.firstOrNull()?.let { r ->
                RecentPerson.testPlain = what == "cardplain"
                Api.invoke("follow", JSONObject().put("action", "profile").put("user_id", r.otherId)) { res ->
                    res.data.optJSONObject("profile")?.let { RecentPerson.keep(r.otherId, it) }
                    pick(r)
                }
            }
            "cardbusy" -> list.firstOrNull { it.busy }?.let { pick(it) }
            // test runs: just the dim + a plain white panel (is it the sheet itself the test phone can't draw?)
            "sheetonly" -> pages.layer.sheet(View(context).apply { setBackgroundColor(Color.WHITE); minimumHeight = Ui.dpi(240f) }, Color.argb(107, 28, 6, 60), 0.88f, 20f)
            "full" -> list.firstOrNull()?.let { pick(it); main.postDelayed({ person?.openFull() }, 700) }
            "fullscroll" -> list.firstOrNull()?.let { pick(it); main.postDelayed({ person?.openFull(); main.postDelayed({ person?.scrollFullToEnd() }, 900) }, 700) }
            "viewer" -> list.firstOrNull()?.let { pick(it); main.postDelayed({ person?.openFull(); main.postDelayed({ person?.openViewer(0) }, 900) }, 700) }
            "coins" -> list.firstOrNull()?.let { startCall(it, "video") }
            "busy" -> list.firstOrNull()?.let { startCall(it, "voice") }
            "unfollow" -> list.getOrNull(1)?.let { pick(it); main.postDelayed({ person?.askUnfollow() }, 900) }
            "follow" -> list.firstOrNull()?.let { pick(it); main.postDelayed({ person?.toggleFollow() }, 900) }
            "picked" -> list.getOrNull(2)?.let { r -> rows[picked]?.box?.setPicked(false, false); picked = r.otherId; rows[picked]?.box?.setPicked(true, false) }
            "scroll" -> scroll.scrollTo(0, listBox.height)
        }
    }
}
