package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Another member, opened from Recent in two steps (components/PersonProfile.jsx):
 * 1st tap on a person → the small card from the bottom (photo, name, Online · Free / Busy / Offline, place,
 * Followers · Following, Follow, Voice / Video Call); tap its round photo → the full profile (bio, photos,
 * interests, joined, verification) sliding in from the right. Both show the same profile
 * (follow { action: "profile" }) and stay in step; Follow / Following (Unfollow asks first) uses
 * follow { action: "set" }. The calls themselves are started by RecentScreen.
 */
class RecentPerson(private val screen: RecentScreen, val row: RecentScreen.Row) {
    private val pages = screen.pages
    private val ctx: Context = screen.context
    private val main = Handler(Looper.getMainLooper())
    val id = row.otherId

    // ── the profile ──
    private var data: JSONObject? = cached(id)
    private var error = ""
    private var saving = false
    private var loadSeq = 0
    val ready: Boolean get() = data != null

    // ── what is open ──
    private var closeCard: (() -> Unit)? = null
    private var closeFull: (() -> Unit)? = null
    private var closeViewer: (() -> Unit)? = null
    private var closeAsk: (() -> Unit)? = null
    private var cardBody: LinearLayout? = null
    private var fullHead: LinearLayout? = null
    private var fullMain: LinearLayout? = null
    private var fullScroll: ScrollView? = null

    companion object {
        /** user id → last loaded profile (opens at once the next time); forgotten when someone else signs in */
        private val cache = HashMap<String, JSONObject>()
        private var cacheToken = ""
        fun cached(id: String): JSONObject? { if (cacheToken != Api.token) { cache.clear(); cacheToken = Api.token }; return cache[id] }
        fun keep(id: String, p: JSONObject) { if (cacheToken != Api.token) { cache.clear(); cacheToken = Api.token }; cache[id] = p }
        /** test screens only: the small card on a plain white panel (to see which drawing a test phone can't take) */
        @Volatile var testPlain = false

        // components/PersonProfile.jsx N (the Profile page colours)
        val HEAD = Ui.c("#4C1D95"); val HEAD_BTN = Ui.c("#5E31AD"); val DEEP = Ui.c("#3A1478"); val ON_HEAD = Ui.c("#DCCFF5"); val DASH = Ui.c("#B9A3E6")
        val GROUND = Ui.c("#F6F3FB"); val LINE = Ui.c("#E7E0F2"); val TINT = Ui.c("#F1EAFB"); val SHARE = Ui.c("#E6DBF8")
        val INK = Ui.c("#1E1433"); val TEXT = Ui.c("#3B3350"); val SUB = Ui.c("#5F5673"); val GREEN_BG = Ui.c("#E3F5EA"); val GREEN = Ui.c("#0F6B3F")
        val SK = Ui.c("#EEE8F7"); val SK_LIGHT = Ui.c("#F8F5FC")
        private val IN = Locale("en", "IN")
    }

    /** what to show before the profile arrives: what the Recent row already knows */
    val p: JSONObject get() = data ?: JSONObject().put("name", row.name).put("age", row.age).put("photo", row.full.ifEmpty { row.photo }).put("photo_thumb", row.photo)
        .put("verified", row.verified).put("online", row.online).put("busy", row.busy).put("last_active", row.lastSeen)

    private fun s(k: String): String = PR.str(p.opt(k))
    private fun b(k: String): Boolean = PR.truthy(p.opt(k))
    private val name: String get() = s("name").ifEmpty { "Member" }
    private val nameAge: String get() { val a = p.opt("age"); return name + if (PR.truthy(a)) ", ${a}" else "" }
    private val place: String get() = listOf(s("city"), s("state")).filter { it.isNotEmpty() }.joinToString(", ")
    private val isMe: Boolean get() = b("me")
    private fun photoRaws(): List<String> = listOf(s("photo_thumb").ifEmpty { s("photo") }, s("photo")).filter { it.isNotEmpty() }
    private fun photos(): List<Pair<String, String>> {
        if (!ready) return emptyList()
        val a = p.optJSONArray("photos") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> PR.str(o.opt("uri")).takeIf { u -> u.isNotEmpty() }?.let { u -> u to PR.str(o.opt("thumb")) } } }
    }
    private fun interests(): List<String> = if (!ready) emptyList() else Tf.strs(p.optJSONArray("interests")).filter { it.isNotEmpty() }

    /** Online · Free / Online · Busy / Offline · 3h ago (the card and the profile use a lighter green dot) */
    private class St(val label: String, val dot: Int)
    private fun st(): St = if (b("online")) (if (b("busy")) St("Online · Busy", Ui.c("#EF4444")) else St("Online · Free", Ui.c("#4ADE80")))
        else { val a = RecentScreen.agoText(s("last_active")); St(if (s("last_active").isNotEmpty() && a.isNotEmpty()) "Offline · $a" else "Offline", Ui.c("#C3BDD0")) }

    private fun callBusy(type: String) = screen.calling == "$id-$type"

    // ═════════════════ open / close ═════════════════
    fun openCard() {
        val wrap = FrameLayout(ctx)
        val panel = RecentMaxWidth(ctx, 480f).apply {
            background = if (testPlain) Tf.topRound(Color.WHITE, 24f) else SheetBg()
            setPadding(Ui.dpi(18f), Ui.dpi(11f), Ui.dpi(18f), Ui.dpi(18f))
            isClickable = true
            contentDescription = row.name
        }
        cardBody = panel
        wrap.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        renderCard()
        closeCard = pages.layer.sheet(wrap, Color.argb(107, 28, 6, 60), 0.88f, 20f, { screen.calling.isNotEmpty() }) {
            closeCard = null; cardBody = null
            if (closeFull == null) screen.personClosed(this)
        }
        load()
    }

    fun openFull() {
        if (closeFull != null) return
        val root = FrameLayout(ctx).apply { setBackgroundColor(GROUND); contentDescription = "$name's profile" }
        val sc = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val outer = FrameLayout(ctx)
        val col = RecentMaxWidth(ctx, 520f).apply { clipChildren = false; clipToPadding = false }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(head, LinearLayout.LayoutParams(-1, -2))
        col.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -Ui.dpi(28f) })
        col.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(col, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        sc.addView(outer, FrameLayout.LayoutParams(-1, -2))
        root.addView(sc, FrameLayout.LayoutParams(-1, -1))
        fullHead = head; fullMain = body; fullScroll = sc
        renderFull()
        // above the small card (the card stays under it), under the messages and the photo viewer
        closeFull = pages.layer.page(root, 25f, { screen.calling.isNotEmpty() }) {
            closeFull = null; fullHead = null; fullMain = null; fullScroll = null
            if (closeCard == null) screen.personClosed(this)
        }
        load()
    }

    /** everything of this person closes (a call is starting, the page changed …) */
    fun closeAll() {
        closeAsk?.invoke(); closeViewer?.invoke(); closeFull?.invoke(); closeCard?.invoke()
    }

    /** a call started or ended: the call buttons */
    fun refresh() = render()

    private fun render() { if (cardBody != null) renderCard(); if (fullMain != null) renderFull() }

    // ═════════════════ data ═════════════════
    private fun errText(r: Api.Result, fallback: String): String {
        if (r.status == 0) return fallback
        val d = r.data
        val e = d.opt("error")
        val m = (if (e is String && e.length < 140) e else "").ifEmpty { d.optString("message") }
        return m.ifEmpty { fallback }
    }

    fun load() {
        if (id.isEmpty()) return
        val seq = ++loadSeq
        error = ""
        render()
        Api.invoke("follow", JSONObject().put("action", "profile").put("user_id", id)) { r ->
            if (seq != loadSeq) return@invoke
            val prof = if (r.ok) r.data.optJSONObject("profile") else null
            if (prof == null) {
                error = if (r.ok) "Couldn't load this profile. Check your internet and try again." else errText(r, "Couldn't load this profile. Check your internet and try again.")
                render()
                return@invoke
            }
            publish(prof)
        }
    }

    private fun publish(next: JSONObject) {
        data = next
        keep(id, next)
        render()
    }

    private fun say(text: String) = pages.toast.show(text, top = true, ms = 2400, style = "profile")

    /** Follow / Follow back right away (the numbers move at once); Following asks before unfollowing */
    fun toggleFollow() { if (!ready || saving) return; if (b("i_follow")) askUnfollow() else setFollow(true) }

    private fun setFollow(on: Boolean) {
        val before = data ?: return
        if (saving) return
        saving = true
        val nm = PR.str(before.opt("name")).ifEmpty { name }
        publish(JSONObject(before.toString()).put("i_follow", on).put("followers", max(0L, (PR.firstNum(before, listOf("followers")) ?: 0.0).toLong() + if (on) 1 else -1)))
        Api.invoke("follow", JSONObject().put("action", "set").put("user_id", id).put("on", on)) { r ->
            saving = false
            if (r.ok && !PR.truthy(r.data.opt("error"))) {
                render()
                say(if (on) "You follow $nm now." else "You unfollowed $nm.")
            } else {
                publish(before)
                say(errText(r, "Couldn't update. Try again."))
            }
        }
    }

    /** "Unfollow …?" — black box, red Unfollow, Cancel (same as Followers / Following) */
    fun askUnfollow() {
        if (closeAsk != null) return
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Tf.box(Ui.c("#17121F"), 22f)
            setPadding(Ui.dpi(18f), Ui.dpi(20f), Ui.dpi(18f), Ui.dpi(16f))
            contentDescription = "Unfollow $name?"
        }
        val t = Tf.t(ctx, "Unfollow $name?", 17f, Color.WHITE).apply { gravity = Gravity.CENTER }
        card.addView(t, RC.line(t, 25.5f, LinearLayout.LayoutParams.MATCH_PARENT))
        val x = Tf.t(ctx, "They will be removed from your Following list. You can follow them again anytime.", 13.5f, Ui.c("#B9B0C9"), false).apply { gravity = Gravity.CENTER }
        val xh = RC.half(x, 19f)
        x.lineH(19f)
        card.addView(x, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f) + xh; bottomMargin = Ui.dpi(14f) + xh })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, bg: Int, onTap: () -> Unit) = Tf.t(ctx, label, 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = Tf.box(bg, 12f); setOnClickListener { onTap() }; Ui.pressable(this, .97f)
        }
        btns.addView(btn("Cancel", Ui.c("#2B2438")) { closeAsk?.invoke() }, LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f).apply { rightMargin = Ui.dpi(10f) })
        btns.addView(btn("Unfollow", Ui.c("#DC2626")) { closeAsk?.invoke(); setFollow(false) }, LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f))
        card.addView(btns, LinearLayout.LayoutParams(-1, Ui.dpi(44f)))
        closeAsk = pages.layer.dialog(card, Color.argb(128, 20, 10, 30), 340f, 45f) { closeAsk = null }
    }

    // ═════════════════ shared pieces ═════════════════
    /** Follow / Follow back / Following — purple, or light with a tick when you follow them */
    private fun followBtn(heightDp: Float): View {
        val on = b("i_follow")
        val label = if (on) "Following" else if (b("follows_me")) "Follow back" else "Follow"
        val fg = if (on) HEAD else Color.WHITE
        val box = FrameLayout(ctx).apply {
            background = Tf.box(if (on) SHARE else HEAD, 12f)
            alpha = if (ready) 1f else .6f
            isEnabled = ready && !saving
            contentDescription = if (on) "Following $name. Tap to unfollow" else "Follow $name"
            setOnClickListener { toggleFollow() }
        }
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (saving) r.addView(TfLoader(ctx, 17f, if (on) Tf.BLUE else Color.WHITE), LinearLayout.LayoutParams(Ui.dpi(17f), Ui.dpi(17f)).apply { rightMargin = Ui.dpi(7f) })
        else r.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(if (on) Lu.Check else Lu.Plus, fg, 17f, 2.6f)) }, LinearLayout.LayoutParams(Ui.dpi(17f), Ui.dpi(17f)).apply { rightMargin = Ui.dpi(7f) })
        r.addView(Tf.t(ctx, label, 15f, fg).apply { maxLines = 1 })
        box.addView(r, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        if (ready && !saving) Ui.pressable(box, .97f)
        box.layoutParams = LinearLayout.LayoutParams(0, Ui.dpi(heightDp), 1f)
        return box
    }

    /** a call button: [wide] = the card's Voice Call / Video Call, else the profile's square 46 */
    private fun callBtn(type: String, wide: Boolean): View {
        val v = type == "video"
        val busy = callBusy(type)
        val calling = screen.calling.isNotEmpty()
        val fg = if (v) Color.WHITE else HEAD
        val box = FrameLayout(ctx).apply {
            background = if (wide) (if (v) Tf.box(HEAD, 14f) else Tf.box(TINT, 14f, 1f, RC.softLine)) else Tf.box(if (v) HEAD else SHARE, 12f)
            alpha = if (calling && !busy) .6f else 1f
            isEnabled = !calling
            contentDescription = "${if (v) (if (wide) "Video Call" else "Video call") else (if (wide) "Voice Call" else "Voice call")} $name"
            setOnClickListener { screen.startCall(row, type) }
        }
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iconDp = if (v) 19f else 18f
        if (busy) r.addView(TfLoader(ctx, if (wide) 19f else 18f, if (v) Color.WHITE else Tf.BLUE), LinearLayout.LayoutParams(Ui.dpi(if (wide) 19f else 18f), Ui.dpi(if (wide) 19f else 18f)))
        else r.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(if (v) Lu.Video else RC.PHONE, fg, iconDp, 2.1f)) }, LinearLayout.LayoutParams(Ui.dpi(iconDp), Ui.dpi(iconDp)))
        if (wide) r.addView(Tf.t(ctx, if (v) "Video Call" else "Voice Call", 16f, fg), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dpi(8f) })
        box.addView(r, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        if (!calling) Ui.pressable(box, .97f)
        return box
    }

    /** a loading block of [wDp] (or the share [frac] of the row) × [hDp] */
    private fun sk(hDp: Float, radiusDp: Float = 8f) = RecentShimmer(ctx, radiusDp, SK, SK_LIGHT)
    private fun skRow(frac: Float, hDp: Float, radiusDp: Float = 8f, topDp: Float = 0f): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(sk(hDp, radiusDp), LinearLayout.LayoutParams(0, Ui.dpi(hDp), frac))
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f - frac))
        layoutParams = LinearLayout.LayoutParams(-1, Ui.dpi(hDp)).apply { topMargin = Ui.dpi(topDp) }
    }

    /** the round photo inside a 2 px light purple ring ([padDp] between them) and the status dot (tap → [onTap]) */
    private fun photoBtn(sizeDp: Float, padDp: Float, dotDp: Float, dotRing: Int, dotInsetDp: Float, onTap: () -> Unit): View {
        val f = FrameLayout(ctx).apply { clipChildren = false }
        val ring = FrameLayout(ctx).apply { background = Tf.circle(Color.TRANSPARENT, 2f, DASH); setPadding(Ui.dpi(2f + padDp), Ui.dpi(2f + padDp), Ui.dpi(2f + padDp), Ui.dpi(2f + padDp)) }
        val img = PImg(ctx, round = true).apply { placeholder = Ui.c("#E9DDFB") }
        img.set(photoRaws())
        ring.addView(img, FrameLayout.LayoutParams(-1, -1))
        f.addView(ring, FrameLayout.LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp)))
        f.addView(View(ctx).apply { background = Tf.circle(st().dot, 3f, dotRing) },
            FrameLayout.LayoutParams(Ui.dpi(dotDp), Ui.dpi(dotDp), Gravity.END or Gravity.BOTTOM).apply { rightMargin = Ui.dpi(dotInsetDp); bottomMargin = Ui.dpi(dotInsetDp) })
        f.setOnClickListener { onTap() }
        Ui.pressable(f, .97f)
        return f
    }

    // ═════════════════ the small card (1st tap) ═════════════════
    private fun renderCard() {
        val c = cardBody ?: return
        c.removeAllViews()
        // the handle
        c.addView(View(ctx).apply { background = Tf.box(Ui.c("#E3D9F3"), 3f) }, LinearLayout.LayoutParams(Ui.dpi(42f), Ui.dpi(5f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dpi(14f) })
        val stt = st()
        // photo (→ full profile) · name, status, place · ✕
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(photoBtn(70f, 2f, 17f, Color.WHITE, 2f) { openFull() }.apply { contentDescription = "Open $name profile" }, LinearLayout.LayoutParams(Ui.dpi(70f), Ui.dpi(70f)))
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameT = Tf.t(ctx, nameAge, 20f, INK).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val l1 = RecentLine(ctx, Ui.dpi(24f)).add(nameT, shrinks = true)
        if (b("verified")) l1.add(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.BadgeCheck, HEAD, 18f)) }, 6f, Ui.dpi(18f), Ui.dpi(18f))
        info.addView(l1, LinearLayout.LayoutParams(-1, Ui.dpi(24f)))
        val stColor = if (b("online")) (if (b("busy")) RC.red else RC.green) else SUB
        val l2 = RecentLine(ctx, Ui.dpi(21f))
            .add(View(ctx).apply { background = Tf.circle(stt.dot) }, 0f, Ui.dpi(8f), Ui.dpi(8f))
            .add(Tf.t(ctx, stt.label, 14f, stColor).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }, 6f, shrinks = true)
        info.addView(l2, LinearLayout.LayoutParams(-1, Ui.dpi(21f)).apply { topMargin = Ui.dpi(3f) })
        if (place.isNotEmpty()) {
            val l3 = RecentLine(ctx, Ui.dpi(19.5f))
                .add(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.MapPin, SUB, 13f)) }, 0f, Ui.dpi(13f), Ui.dpi(13f))
                .add(Tf.t(ctx, place, 13f, SUB, false).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }, 4f, shrinks = true)
            info.addView(l3, LinearLayout.LayoutParams(-1, Ui.dpi(19.5f)).apply { topMargin = Ui.dpi(3f) })
        }
        top.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dpi(14f); rightMargin = Ui.dpi(14f) })
        val x = FrameLayout(ctx).apply {
            background = Tf.circle(TINT); contentDescription = "Close"
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.X, HEAD, 19f)) }, FrameLayout.LayoutParams(Ui.dpi(19f), Ui.dpi(19f), Gravity.CENTER))
            setOnClickListener { if (screen.calling.isEmpty()) closeCard?.invoke() }
            Ui.pressable(this, .97f)
        }
        top.addView(x, LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f)).apply { gravity = Gravity.TOP })
        c.addView(top, LinearLayout.LayoutParams(-1, -2))

        // Followers · Following + Follow
        val fol = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val counts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(2f), 0, 0, 0) }
        listOf("Followers" to "followers", "Following" to "following").forEachIndexed { i, (label, key) ->
            val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; minimumWidth = Ui.dpi(56f) }
            if (ready) {
                val n = Tf.t(ctx, ((PR.firstNum(p, listOf(key)) ?: 0.0).toLong()).toString(), 18f, HEAD).apply { gravity = Gravity.CENTER }
                cell.addView(n, RC.line(n, 22f, LinearLayout.LayoutParams.MATCH_PARENT))
            } else cell.addView(sk(18f), LinearLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(18f)).apply { topMargin = Ui.dpi(2f); bottomMargin = Ui.dpi(2f) })
            val l = Tf.t(ctx, label, 12f, SUB, false).apply { gravity = Gravity.CENTER }
            cell.addView(l, RC.line(l, 15f, LinearLayout.LayoutParams.MATCH_PARENT))
            counts.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = Ui.dpi(16f) })
        }
        fol.addView(counts, LinearLayout.LayoutParams(-2, -2))
        if (!isMe) fol.addView(followBtn(44f), LinearLayout.LayoutParams(0, Ui.dpi(44f), 1f).apply { leftMargin = Ui.dpi(12f) })
        c.addView(fol, LinearLayout.LayoutParams(-1, Ui.dpi(44f)).apply { topMargin = Ui.dpi(14f) })

        // couldn't load: the reason + Try again
        if (error.isNotEmpty() && !ready) {
            val sp = SpannableStringBuilder("$error ")
            val st0 = sp.length
            sp.append("Try again")
            // only "Try again" is the button (bold, purple, underlined)
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: View) { load() }
                override fun updateDrawState(ds: android.text.TextPaint) { ds.color = HEAD; ds.isUnderlineText = true; ds.typeface = Ui.bold }
            }, st0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val e = Tf.t(ctx, sp, 13f, SUB, false).apply {
                movementMethod = android.text.method.LinkMovementMethod.getInstance(); highlightColor = Color.TRANSPARENT
                contentDescription = "$error Try again"
            }
            val eh = RC.half(e, 19.5f)
            e.lineH(19.5f)
            c.addView(e, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(10f) + eh; bottomMargin = eh })
        }
        // on another call now
        if (b("online") && b("busy")) {
            val n = Tf.t(ctx, "They're on another call right now — you can still try.", 14f, Ui.c("#B91C1C"), false).apply {
                background = Tf.box(Ui.c("#FEF2F2"), 12f, 1f, Ui.c("#FECACA"))
            }
            val nh = RC.half(n, 19.6f)
            n.lineH(19.6f)
            n.setPadding(Ui.dpi(13f), Ui.dpi(11f) + nh, Ui.dpi(13f), Ui.dpi(11f) + nh)
            c.addView(n, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(12f) })
        }
        // Voice Call · Video Call
        if (!isMe) {
            val calls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            calls.addView(callBtn("voice", true), LinearLayout.LayoutParams(0, Ui.dpi(50f), 1f).apply { rightMargin = Ui.dpi(10f) })
            calls.addView(callBtn("video", true), LinearLayout.LayoutParams(0, Ui.dpi(50f), 1f))
            c.addView(calls, LinearLayout.LayoutParams(-1, Ui.dpi(50f)).apply { topMargin = Ui.dpi(12f) })
        }
    }

    // ═════════════════ the full profile (2nd tap) ═════════════════
    private fun cardBox(radius: Float = 18f) = Tf.box(Color.WHITE, radius, 1f, LINE)

    private fun renderFull() {
        val head = fullHead ?: return
        val body = fullMain ?: return
        val stt = st()
        // ── purple top: back · Profile, then photo + name + status + place (+ CREATOR / Follows you) ──
        head.removeAllViews()
        val top = FrameLayout(ctx).apply { setBackgroundColor(HEAD); clipChildren = true }
        top.addView(HeadDeco(ctx), FrameLayout.LayoutParams(-1, -1))
        val hc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(20f), Ui.dpi(52f), Ui.dpi(20f), Ui.dpi(84f)) }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = FrameLayout(ctx).apply {
            background = Tf.box(HEAD_BTN, 12f); contentDescription = "Back"
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.ChevronLeft, Color.WHITE, 22f, 2.2f)) }, FrameLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
            setOnClickListener { if (screen.calling.isEmpty()) closeFull?.invoke() }
            Ui.pressable(this, .97f)
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)))
        bar.addView(Tf.t(ctx, "Profile", 17f, Color.WHITE).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dpi(12f) })
        hc.addView(bar, LinearLayout.LayoutParams(-1, Ui.dpi(44f)))
        val sec = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dpi(96f) }
        val ph = photos()
        sec.addView(photoBtn(84f, 3f, 18f, HEAD, 4f) { if (ph.isNotEmpty()) openViewer(0) }.apply { contentDescription = "Profile photo" }, LinearLayout.LayoutParams(Ui.dpi(84f), Ui.dpi(84f)))
        val who = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
        val nr = ShrinkRow(ctx, Ui.dpi(6f))
        val nameT = Tf.t(ctx, nameAge, 20f, Color.WHITE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val nh = RC.half(nameT, 24f)
        nameT.lineH(24f)
        nameT.setPadding(0, nh.coerceAtLeast(0), 0, nh.coerceAtLeast(0))
        nr.addView(nameT)
        if (b("verified")) nr.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.BadgeCheck, HEAD, 20f, 2f, "#fff")); contentDescription = "Verified"; layoutParams = ViewGroup.LayoutParams(Ui.dpi(20f), Ui.dpi(20f)) })
        who.addView(nr, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(6f) })
        val pill = Tf.t(ctx, stt.label, 12.5f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            background = Tf.box(DEEP, 11f); setPadding(Ui.dpi(8f), 0, Ui.dpi(10f), 0)
            compoundDrawablePadding = Ui.dpi(6f); setCompoundDrawablesWithIntrinsicBounds(DotDrawable(stt.dot, 8f), null, null, null)
        }
        who.addView(pill, LinearLayout.LayoutParams(-2, Ui.dpi(22f)))
        if (place.isNotEmpty()) {
            val pl = RecentLine(ctx, Ui.dpi(18f))
                .add(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.MapPin, ON_HEAD, 15f)) }, 0f, Ui.dpi(15f), Ui.dpi(15f))
                .add(Tf.t(ctx, place, 13.5f, Color.WHITE, false).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }, 6f, shrinks = true)
            who.addView(pl, LinearLayout.LayoutParams(-2, Ui.dpi(18f)).apply { topMargin = Ui.dpi(10f) })
        }
        if (ready && (b("creator") || b("host") || b("follows_me"))) {
            val tags = FlowRow(ctx, Ui.dpi(6f), Ui.dpi(6f))
            fun tag(t: String, bg: Int, fg: Int, size: Float, spacing: Float) = Tf.t(ctx, t, size, fg).apply {
                gravity = Gravity.CENTER_VERTICAL; background = Tf.box(bg, 6f); setPadding(Ui.dpi(8f), 0, Ui.dpi(8f), 0); letterSpacing = spacing
                layoutParams = ViewGroup.LayoutParams(-2, Ui.dpi(20f))
            }
            if (b("creator")) tags.addView(tag("CREATOR", Color.WHITE, HEAD, 11f, .04f))
            if (!b("creator") && b("host")) tags.addView(tag("HOST", Color.WHITE, HEAD, 11f, .04f))
            if (b("follows_me")) tags.addView(tag(if (b("i_follow")) "You follow each other" else "Follows you", Color.argb(41, 255, 255, 255), Color.WHITE, 11.5f, 0f))
            who.addView(tags, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(8f) })
        }
        sec.addView(who, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dpi(16f) })
        hc.addView(sec, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(20f) })
        top.addView(hc, FrameLayout.LayoutParams(-1, -2))
        head.addView(top, LinearLayout.LayoutParams(-1, -2))

        // ── the lavender part (the followers card sits half on the purple top) ──
        body.removeAllViews()
        body.background = Tf.topRound(GROUND, 28f)
        body.setPadding(Ui.dpi(16f), Ui.dpi(20f), Ui.dpi(16f), Ui.dpi(28f))
        fun gapLp(h: Int = -2) = LinearLayout.LayoutParams(-1, h).apply { topMargin = Ui.dpi(14f) }

        // Followers | Following
        val counts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = cardBox() }
        listOf("Followers" to "followers", "Following" to "following").forEachIndexed { i, (label, key) ->
            if (i == 1) counts.addView(View(ctx).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(Ui.dpi(1f), Ui.dpi(34f)))
            val half = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            if (ready) {
                val n = Tf.t(ctx, ((PR.firstNum(p, listOf(key)) ?: 0.0).toLong()).toString(), 20f, HEAD).apply { gravity = Gravity.CENTER }
                half.addView(n, RC.line(n, 24f, LinearLayout.LayoutParams.MATCH_PARENT))
            } else half.addView(sk(20f), LinearLayout.LayoutParams(Ui.dpi(28f), Ui.dpi(20f)).apply { topMargin = Ui.dpi(2f); bottomMargin = Ui.dpi(4f) })
            val l = Tf.t(ctx, label, 12.5f, SUB, false).apply { gravity = Gravity.CENTER }
            half.addView(l, RC.line(l, 16f, LinearLayout.LayoutParams.MATCH_PARENT))
            counts.addView(half, LinearLayout.LayoutParams(0, -1, 1f))
        }
        body.addView(counts, LinearLayout.LayoutParams(-1, Ui.dpi(68f)).apply { topMargin = -Ui.dpi(56f) })

        // couldn't load: the reason + Try again
        if (error.isNotEmpty() && !ready) {
            val eb = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = cardBox(); setPadding(Ui.dpi(15f), Ui.dpi(17f), Ui.dpi(15f), Ui.dpi(17f)) }
            val et = Tf.t(ctx, error, 14f, SUB, false).apply { gravity = Gravity.CENTER }
            val eh = RC.half(et, 19f)
            et.lineH(19f)
            eb.addView(et, LinearLayout.LayoutParams(-1, -2).apply { topMargin = eh; bottomMargin = Ui.dpi(12f) + eh })
            val tb = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Tf.box(HEAD, 12f); setPadding(Ui.dpi(20f), 0, Ui.dpi(20f), 0)
                setOnClickListener { load() }; Ui.pressable(this, .97f)
            }
            tb.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.RefreshCw, Color.WHITE, 16f)) }, LinearLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f)).apply { rightMargin = Ui.dpi(8f) })
            tb.addView(Tf.t(ctx, "Try again", 14.5f, Color.WHITE))
            eb.addView(tb, LinearLayout.LayoutParams(-2, Ui.dpi(42f)))
            body.addView(eb, gapLp())
        }

        // Follow + Voice / Video call
        if (!isMe) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(followBtn(46f), LinearLayout.LayoutParams(0, Ui.dpi(46f), 1f))
            r.addView(callBtn("voice", false), LinearLayout.LayoutParams(Ui.dpi(46f), Ui.dpi(46f)).apply { leftMargin = Ui.dpi(10f) })
            r.addView(callBtn("video", false), LinearLayout.LayoutParams(Ui.dpi(46f), Ui.dpi(46f)).apply { leftMargin = Ui.dpi(10f) })
            body.addView(r, gapLp(Ui.dpi(46f)))
        }

        // Bio
        val bio = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = cardBox(); setPadding(Ui.dpi(15f), Ui.dpi(9f), Ui.dpi(15f), Ui.dpi(15f)) }
        bio.addView(PUi.secHead(ctx, Lu.User, "Bio", null), LinearLayout.LayoutParams(-1, -2))
        if (ready) {
            val text = s("bio")
            val bt = Tf.t(ctx, text.ifEmpty { "No bio yet." }, 14.5f, if (text.isNotEmpty()) TEXT else SUB, false)
            val bh = RC.half(bt, 20f)
            bt.lineH(20f)
            bio.addView(bt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = bh; bottomMargin = bh })
        } else {
            bio.addView(skRow(.9f, 14f))
            bio.addView(skRow(.6f, 14f, topDp = 8f))
        }
        body.addView(bio, gapLp())

        // Photos: 1 big + up to 4 small
        val pc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = cardBox(); setPadding(Ui.dpi(15f), Ui.dpi(9f), Ui.dpi(15f), Ui.dpi(15f)) }
        val countPill = if (ready) Tf.t(ctx, ph.size.toString(), 12.5f, HEAD).apply {
            gravity = Gravity.CENTER; background = Tf.box(TINT, 8f); setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0); layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(24f))
        } else null
        pc.addView(PUi.secHead(ctx, Lu.Image, "Photos", countPill), LinearLayout.LayoutParams(-1, -2))
        when {
            !ready -> pc.addView(sk(156f, 14f), LinearLayout.LayoutParams(-1, Ui.dpi(156f)))
            ph.isEmpty() -> { val t = Tf.t(ctx, "No photos yet.", 14f, SUB, false); pc.addView(t, RC.line(t, 21f)) }
            else -> {
                val grid = PhotoGrid(ctx, ph.size)
                ph.forEachIndexed { i, (uri, thumb) ->
                    val big = i == 0
                    val cell = FrameLayout(ctx).apply {
                        background = Tf.box(TINT, if (big) 14f else 12f); clipToOutline = true
                        contentDescription = if (big) "Main photo" else "Photo ${i + 1}"
                        setOnClickListener { openViewer(i) }
                        Ui.pressable(this, .97f)
                    }
                    val img = PImg(ctx, if (big) 14f else 12f)
                    img.set(listOf(thumb.ifEmpty { uri }, uri))
                    cell.addView(img, FrameLayout.LayoutParams(-1, -1))
                    if (big) {
                        val m = Tf.t(ctx, "Main", 11.5f, Color.WHITE).apply {
                            gravity = Gravity.CENTER_VERTICAL; background = Tf.box(HEAD, 7f); setPadding(Ui.dpi(7f), 0, Ui.dpi(9f), 0)
                            compoundDrawablePadding = Ui.dpi(4f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(Lu.Star, Color.WHITE, 11f, 0f, "#fff"), null, null, null)
                        }
                        cell.addView(m, FrameLayout.LayoutParams(-2, Ui.dpi(22f), Gravity.START or Gravity.BOTTOM).apply { leftMargin = Ui.dpi(8f); bottomMargin = Ui.dpi(8f) })
                    }
                    grid.addView(cell)
                }
                pc.addView(grid, LinearLayout.LayoutParams(-1, Ui.dpi(if (ph.size == 1) 220f else 156f)))
            }
        }
        body.addView(pc, gapLp())

        // Interests
        val ic = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = cardBox(); setPadding(Ui.dpi(15f), Ui.dpi(9f), Ui.dpi(15f), Ui.dpi(15f)) }
        ic.addView(PUi.secHead(ctx, Lu.Heart, "Interests", null), LinearLayout.LayoutParams(-1, -2))
        val ints = interests()
        when {
            !ready -> ic.addView(skRow(.7f, 30f, 16f))
            ints.isEmpty() -> { val t = Tf.t(ctx, "No interests added yet.", 14f, SUB, false); ic.addView(t, RC.line(t, 21f)) }
            else -> {
                val chips = FlowRow(ctx, Ui.dpi(8f), Ui.dpi(8f))
                for (k in ints) {
                    val inner = PR.INTERESTS.firstOrNull { it.first.lowercase() == k.lowercase() }?.second ?: Lu.Sparkles
                    chips.addView(Tf.t(ctx, k, 13.5f, Color.WHITE).apply {
                        gravity = Gravity.CENTER_VERTICAL; background = Tf.box(HEAD, 16f); setPadding(Ui.dpi(11f), 0, Ui.dpi(14f), 0)
                        compoundDrawablePadding = Ui.dpi(6f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(inner, Color.WHITE, 15f), null, null, null)
                        maxLines = 1
                        layoutParams = ViewGroup.LayoutParams(-2, Ui.dpi(32f))
                    })
                }
                ic.addView(chips, LinearLayout.LayoutParams(-1, -2))
            }
        }
        body.addView(ic, gapLp())

        // Joined · Verification
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val ver = b("verified")
        info.addView(infoCard(Lu.CalendarDays, "Joined", if (ready) joinedText().ifEmpty { "—" } else null, TINT, HEAD, INK), LinearLayout.LayoutParams(0, Ui.dpi(68f), 1f).apply { rightMargin = Ui.dpi(10f) })
        info.addView(infoCard(Lu.ShieldCheck, "Verification", if (ready) (if (ver) "Verified" else "Not verified") else null,
            if (ver) GREEN_BG else Ui.c("#EEEAF4"), if (ver) GREEN else SUB, if (ver) GREEN else SUB), LinearLayout.LayoutParams(0, Ui.dpi(68f), 1f))
        body.addView(info, gapLp(Ui.dpi(68f)))
    }

    /** "2 Nov 2026" (Base44 times without a zone are UTC) */
    private fun joinedText(): String {
        val t = Tf.ms(s("joined"), plainUtc = true)
        return if (t <= 0L) "" else SimpleDateFormat("d MMM yyyy", IN).format(Date(t))
    }

    private fun infoCard(inner: String, label: String, value: String?, tileBg: Int, tileFg: Int, valueColor: Int): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = cardBox(16f); setPadding(Ui.dpi(13f), 0, Ui.dpi(13f), 0) }
        val t = FrameLayout(ctx).apply { background = Tf.box(tileBg, 9f) }
        t.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(inner, tileFg, 16f)) }, FrameLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f), Gravity.CENTER))
        c.addView(t, LinearLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f)).apply { rightMargin = Ui.dpi(10f) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val l = Tf.t(ctx, label, 12.5f, SUB, false)
        col.addView(l, RC.line(l, 16f))
        if (value == null) col.addView(sk(15f), LinearLayout.LayoutParams(Ui.dpi(70f), Ui.dpi(15f)).apply { topMargin = Ui.dpi(3f) })
        else { val v = Tf.t(ctx, value, 14.5f, valueColor).apply { maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }; col.addView(v, RC.line(v, 19f, LinearLayout.LayoutParams.MATCH_PARENT)) }
        c.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        c.contentDescription = "$label: ${value ?: ""}"
        return c
    }

    // ═════════════════ the photo, full screen ═════════════════
    /** dark page: "1 / 3" and ✕ at the top, the photo in the middle (as big as it is, at most the screen), ‹ › when there are more */
    fun openViewer(start: Int) {
        val list = photos()
        if (list.isEmpty() || closeViewer != null) return
        var at = start.coerceIn(0, list.size - 1)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(240, 10, 5, 16)); contentDescription = "Photo" }
        val top = FrameLayout(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(52f), Ui.dpi(12f), 0) }
        val count = Tf.t(ctx, "", 14f, Color.WHITE, false).apply { setPadding(Ui.dpi(6f), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL }
        top.addView(count, FrameLayout.LayoutParams(-2, Ui.dpi(40f), Gravity.START))
        val x = FrameLayout(ctx).apply {
            background = Tf.circle(Color.argb(36, 255, 255, 255)); contentDescription = "Close photo"
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.X, Color.WHITE, 22f)) }, FrameLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
            setOnClickListener { closeViewer?.invoke() }
            Ui.pressable(this, .97f)
        }
        top.addView(x, FrameLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f), Gravity.END))
        box.addView(top, LinearLayout.LayoutParams(-1, -2))
        val mid = FrameLayout(ctx).apply { setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)) }
        val pic = ViewerPic(ctx)
        val spin = TfLoader(ctx, 18f, Color.WHITE)
        mid.addView(pic, FrameLayout.LayoutParams(-1, -1))
        mid.addView(spin, FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        box.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))
        fun show() {
            count.text = if (list.size > 1) "${at + 1} / ${list.size}" else ""
            val uri = list[at].first
            pic.bitmap = null; spin.visibility = View.VISIBLE
            val want = at
            if (uri.startsWith("asset:")) { pic.bitmap = Tf.pic(ctx, uri.removePrefix("asset:")); spin.visibility = View.GONE; return }
            val side = max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            Photos.loadFull(uri, side) { bmp -> if (want == at && closeViewer != null) { pic.bitmap = bmp; if (bmp != null) spin.visibility = View.GONE } }
        }
        if (list.size > 1) {
            for ((d, inner, label, g) in listOf(Quad(-1, Lu.ChevronLeft, "Previous photo", Gravity.START), Quad(1, Lu.ChevronRight, "Next photo", Gravity.END))) {
                val arrow = FrameLayout(ctx).apply {
                    background = Tf.circle(Color.argb(36, 255, 255, 255)); contentDescription = label
                    addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(inner, Color.WHITE, 24f)) }, FrameLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.CENTER))
                    setOnClickListener { at = (at + d + list.size) % list.size; show() }
                    Ui.pressable(this, .97f)
                }
                mid.addView(arrow, FrameLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f), g or Gravity.CENTER_VERTICAL).apply { leftMargin = -Ui.dpi(4f); rightMargin = -Ui.dpi(4f) })
            }
        }
        box.setOnClickListener { closeViewer?.invoke() }
        closeViewer = pages.layer.fade(box, 30f) { closeViewer = null }
        show()
    }

    private data class Quad(val d: Int, val inner: String, val label: String, val g: Int)

    /** the photo as it is (object-fit: contain), never bigger than its own size, round 12 corners; a tap on it stays */
    private class ViewerPic(ctx: Context) : View(ctx) {
        var bitmap: Bitmap? = null
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shown = RectF()
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean = bitmap != null && shown.contains(e.x, e.y)
        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            // the web: max-width 100 %, max-height = the screen − 160 (CSS px = the photo's own pixels)
            val maxH = min(height.toFloat(), (rootView.height - Ui.dp(160f)).coerceAtLeast(Ui.dp(100f)))
            val k = minOf(width.toFloat() / b.width, maxH / b.height, Ui.density)
            val w = b.width * k; val h = b.height * k
            val l = (width - w) / 2f; val t = (height - h) / 2f
            shown.set(l, t, l + w, t + h)
            p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate(l, t) }) }
            c.drawRoundRect(shown, Ui.dp(12f), Ui.dp(12f), p); p.shader = null
        }
    }

    // ═════════════════ test screens only ═════════════════
    fun scrollFullToEnd() { fullScroll?.let { s -> s.post { s.scrollTo(0, s.getChildAt(0)?.height ?: 0) } } }
}

/** The small card's panel: lavender at the top fading to white, round 24 at the top, a thin lavender line along the top. */
class SheetBg : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.c("#E6D9FA") }
    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        val r = Ui.dp(24f)
        p.shader = LinearGradient(0f, b.top, 0f, b.bottom, intArrayOf(Ui.c("#F3EAFF"), Ui.c("#FBF8FF"), Color.WHITE, Color.WHITE), floatArrayOf(0f, .38f, .70f, 1f), Shader.TileMode.CLAMP)
        val path = Path().apply { addRoundRect(b, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW) }
        c.drawPath(path, p)
        p.shader = null
        // border-top: 1px — the top edge and its round corners
        val sw = Ui.dp(1f)
        line.strokeWidth = sw
        val top = Path().apply {
            moveTo(b.left + sw / 2f, b.top + r)
            arcTo(RectF(b.left + sw / 2f, b.top + sw / 2f, b.left + 2 * r - sw / 2f, b.top + 2 * r), 180f, 90f)
            lineTo(b.right - r, b.top + sw / 2f)
            arcTo(RectF(b.right - 2 * r + sw / 2f, b.top + sw / 2f, b.right - sw / 2f, b.top + 2 * r), 270f, 90f)
        }
        c.drawPath(top, line)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The purple top's decoration: a soft white circle at the top right and a thin white ring at the bottom left. */
private class HeadDeco(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(if (MeasureSpec.getMode(w) == MeasureSpec.EXACTLY) MeasureSpec.getSize(w) else 0, if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else 0)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // 240 circle, top −90, right −70, rgba(255,255,255,.06)
        p.style = Paint.Style.FILL; p.color = Color.argb(15, 255, 255, 255)
        c.drawCircle(w + Ui.dp(70f) - Ui.dp(120f), -Ui.dp(90f) + Ui.dp(120f), Ui.dp(120f), p)
        // 170 ring (1.5 line), bottom −70, left −60, rgba(255,255,255,.12)
        p.style = Paint.Style.STROKE; p.strokeWidth = Ui.dp(1.5f); p.color = Color.argb(31, 255, 255, 255)
        c.drawCircle(-Ui.dp(60f) + Ui.dp(85f), h + Ui.dp(70f) - Ui.dp(85f), Ui.dp(85f) - Ui.dp(0.75f), p)
    }
}

/** The photos grid: 1 photo alone (220 high); else columns 1.9fr 1fr 1fr in two rows (156 high), gap 8, the main photo over both rows. */
private class PhotoGrid(ctx: Context, private val n: Int) : ViewGroup(ctx) {
    private val g = Ui.dpi(8f)
    private fun cols(w: Int): IntArray { val a = w - 2 * g; val c0 = (a * 1.9f / 3.9f).toInt(); val c = (a - c0) / 2; return intArrayOf(c0, c, a - c0 - c) }
    private fun cell(i: Int, w: Int, h: Int): IntArray {
        if (n == 1) return intArrayOf(0, 0, w, h)
        val c = cols(w); val rh = (h - g) / 2
        val x1 = c[0] + g; val x2 = x1 + c[1] + g
        return when (i) {
            0 -> intArrayOf(0, 0, c[0], h)
            1 -> intArrayOf(x1, 0, c[1], rh)
            2 -> intArrayOf(x2, 0, c[2], rh)
            3 -> intArrayOf(x1, rh + g, c[1], h - rh - g)
            else -> intArrayOf(x2, rh + g, c[2], h - rh - g)
        }
    }
    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs)
        for (i in 0 until childCount) { val r = cell(i, w, h); getChildAt(i).measure(MeasureSpec.makeMeasureSpec(r[2], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r[3], MeasureSpec.EXACTLY)) }
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        for (i in 0 until childCount) { val c = cell(i, w, h); getChildAt(i).layout(c[0], c[1], c[0] + c[2], c[1] + c[3]) }
    }
}

@Suppress("unused") private val keepInset: Class<*> = InsetDrawable::class.java
