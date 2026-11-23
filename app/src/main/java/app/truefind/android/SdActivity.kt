package app.truefind.android

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Secret Dating → My Activity (Nov 20, the web's MyActivityPage in SecretApp.jsx): Notifications first (who liked you,
 * messaged you or viewed your profile), then four tabs — Liked you · You liked · Matches · Requests. It opens from the
 * bell beside Credits on Secret Dating Home, and when a notification is tapped.
 * Read-only: it never spends a credit itself — "Send Request" goes through the same Send Request sheet as everywhere
 * else (it asks before 1 credit is used). Without a plan (or a girl not verified yet) "Liked you" and "Matches" stay
 * blurred: the server (getMyActivity) sends no names or photos then.
 */
class SdActivity private constructor(private val h: SecretHome, start: String, private val aboveBar: Int) {
    companion object {
        /** tab · its name · the list in getMyActivity's answer */
        private val TABS = listOf(Triple("liked", "Liked you", "liked_you"), Triple("you", "You liked", "you_liked"), Triple("match", "Matches", "matches"), Triple("req", "Requests", "requests"))
        /** [aboveBar]: opened from the heart in the bottom bar — the page ends above the bar, which stays in sight */
        fun open(h: SecretHome, start: String = "liked", aboveBar: Int = 0) = SdActivity(h, start, aboveBar)
    }

    private val ctx = h.context
    private var tab = if (TABS.any { it.first == start }) start else "liked"
    private var data: JSONObject? = null       // null = loading
    private var failed = false
    private var seq = 0
    private val root = FrameLayout(ctx)
    val scroll = ScrollView(ctx)
    private val col = vbox(ctx)
    private val tabsRow = LinearLayout(ctx)
    private val tabViews = ArrayList<Pair<FrameLayout, Pair<TextView, TextView>>>()
    private val tabBg = IntArray(TABS.size)
    private val body = vbox(ctx)
    private var pill: View? = null
    private var closeFn: () -> Unit = {}
    var isOpen = true; private set

    init {
        build()
        load()
        closeFn = h.layer.page(root, z = 29f, onClose = { closed() }, bottom = aboveBar)
        // also when every pop-up is cleared at once (leaving Secret Dating)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { closed() }
        })
    }

    fun close() = closeFn()
    private fun closed() {
        if (!isOpen) return
        isOpen = false
        pill?.let { h.forgetPill(it) }
        h.activityClosed(this)
    }

    // ═════════════ the page ═════════════
    private fun build() {
        root.addView(PinkBackdrop(ctx), FrameLayout.LayoutParams(-1, -1))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        col.setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(30f))
        col.clipChildren = false
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        // what is new counts as seen once the Notifications box has been on screen for a moment
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> h.activitySeenSoon() }

        // top bar: back · My Activity · credits
        val bar = hbox(ctx, 8f)
        bar.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ArrowLeft, Rs.INK, 26f, 2.3f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back" }
            .press().onTap { close() }, lp(dpi(44f), dpi(44f)))
        bar.addView(sdText(ctx, "My Activity", 26f, Rs.M_INK, serif = true).oneLine(), lp(0, WRAP, 1f))
        val p = h.newCreditsPill().also { pill = it }
        bar.addView(p, lp(WRAP, dpi(44f)))
        col.addView(bar, lp(MATCH, WRAP, l = -8f, r = -6f))

        // Notifications (who liked you, messaged you, viewed your profile) — first, at the top
        h.attachActivityBox(col, lp(MATCH, WRAP, t = 12f))

        // the four tabs
        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.background = rounded(Color.WHITE, 22f, 1f, Rs.argb(36, 214, 40, 120))
        tabsRow.elevation = Ui.dp(4f)
        tabsRow.setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        for ((i, t) in TABS.withIndex()) {
            val (k, label, _) = t
            val cell = FrameLayout(ctx).apply { contentDescription = label }
            val box = vbox(ctx, 1f, Gravity.CENTER_HORIZONTAL)
            val name = sdText(ctx, label, 13f, Rs.c("#111111")).oneLine().apply { gravity = Gravity.CENTER }
            val num = sdText(ctx, "·", 16f, K.hot).apply { gravity = Gravity.CENTER }
            box.addView(name); box.addView(num)
            cell.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            cell.press().onTap { if (tab != k) { tab = k; paintTabs(true); renderBody() } }
            tabsRow.addView(cell, lp(0, dpi(54f), 1f, l = if (i == 0) 0f else 4f))
            tabViews.add(cell to (name to num))
        }
        col.addView(tabsRow, lp(MATCH, WRAP, t = 12f))
        col.addView(body, lp(MATCH, WRAP, t = 14f))
        paintTabs(false)
        renderBody()
    }

    /** the chosen tab is pink (a quick colour change, as the web's .25s transition) */
    private fun paintTabs(animate: Boolean) {
        val counts = data?.optJSONObject("counts")
        for ((i, t) in TABS.withIndex()) {
            val (cell, texts) = tabViews[i]
            val (name, num) = texts
            val on = t.first == tab
            val n = if (data == null) "·" else (counts?.optInt(t.third, 0) ?: 0).toString()
            num.text = n
            cell.contentDescription = "${t.second}, ${if (data == null) "loading" else n}${if (on) ", selected" else ""}"
            val bgTo = if (on) K.hot else Color.TRANSPARENT
            val nameTo = if (on) Color.WHITE else Rs.c("#111111")
            val numTo = if (on) Color.WHITE else K.hot
            val bg = (cell.background as? GradientDrawable) ?: rounded(Color.TRANSPARENT, 18f).also { cell.background = it }
            val from = tabBg[i]
            tabBg[i] = bgTo
            if (animate && from != bgTo && !GlobeMap.REDUCED) {
                val nameFrom = name.currentTextColor; val numFrom = num.currentTextColor
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 250
                    val ev = ArgbEvaluator()
                    addUpdateListener { a ->
                        val f = a.animatedValue as Float
                        bg.setColor(ev.evaluate(f, from, bgTo) as Int)
                        name.setTextColor(ev.evaluate(f, nameFrom, nameTo) as Int); num.setTextColor(ev.evaluate(f, numFrom, numTo) as Int)
                    }
                }.start()
            } else { bg.setColor(bgTo); name.setTextColor(nameTo); num.setTextColor(numTo) }
        }
    }

    // ═════════════ data ═════════════
    /** (again) — after a request was sent, its status changes here too */
    fun load() {
        val my = ++seq
        failed = false
        if (data == null) renderBody()
        Api.invoke("getMyActivity", JSONObject()) { r ->
            if (my != seq || !isOpen) return@invoke
            if (r.ok && r.data.optString("error").isEmpty()) { data = r.data; failed = false }
            else failed = true
            paintTabs(false)
            renderBody()
        }
    }

    // ═════════════ the list of the chosen tab ═════════════
    private fun list(): List<JSONObject> {
        val a: JSONArray = data?.optJSONArray(TABS.first { it.first == tab }.third) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun person(x: JSONObject): JSONObject? = x.optJSONObject("person")
    private fun nameOf(p: JSONObject) = SdRules.firstName(SdRules.s(p, "name")) + SdRules.s(p, "age").let { if (it.isNotEmpty()) ", $it" else "" }
    private fun ago(t: String) = SdRules.timeAgo(t)

    private fun renderBody() {
        body.removeAllViews()
        val d = data
        if (d == null) {
            if (failed) {
                val card = vbox(ctx, 10f, Gravity.CENTER_HORIZONTAL).apply { background = cardBg(); setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(16f)); elevation = Ui.dp(4f) }
                card.addView(sdText(ctx, "Couldn't load your activity.", 15f, K.sub, bold = false).apply { gravity = Gravity.CENTER })
                card.addView(pillView("Try again", K.pill, Color.WHITE, null).also { it.press().onTap { load() } }, lp(WRAP, dpi(34f)))
                body.addView(card, lp(MATCH, WRAP))
            } else for (i in 0..2) body.addView(View(ctx).apply { background = rounded(Rs.c("#FCE4F0"), 18f) }, lp(MATCH, dpi(64f), t = if (i == 0) 0f else 8f))
            return
        }
        val l = list().filter { person(it) != null || ((tab == "liked" || tab == "match") && d.optBoolean("can_see") != true) }
        if (l.isEmpty()) {
            val msg = when (tab) {
                "liked" -> "When someone likes you, they show up here."
                "you" -> "People you like on the map or Home show up here."
                "match" -> "When you and someone like each other, it's a Match."
                else -> "Requests you send show up here with their status."
            }
            body.addView(sdText(ctx, msg, 15f, K.sub, bold = false).apply {
                gravity = Gravity.CENTER; lineHeight(1.2f); background = rounded(Color.WHITE, 18f, 1f, Rs.argb(36, 214, 40, 120)); setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            }, lp(MATCH, WRAP))
            return
        }
        val canSee = d.optBoolean("can_see") == true
        when {
            // Liked you / Matches without a plan: blurred
            (tab == "liked" || tab == "match") && !canSee -> locked(l, if (tab == "match") "match" else "activity")
            tab == "liked" -> likedGrid(l)
            tab == "you" || tab == "match" -> rows(l, tab == "match")
            else -> requests(l)
        }
    }

    private fun cardBg() = rounded(Color.WHITE, 24f, 1f, Rs.argb(41, 214, 40, 120))

    /** two columns of tiles */
    private fun grid(n: Int, cell: (Int) -> View) {
        var row: LinearLayout? = null
        for (i in 0 until n) {
            if (i % 2 == 0) { row = hbox(ctx).also { body.addView(it, lp(MATCH, WRAP, t = if (i == 0) 0f else 10f)) } }
            row!!.addView(cell(i), lp(0, WRAP, 1f, l = if (i % 2 == 1) 10f else 0f))
            if (i == n - 1 && i % 2 == 0) row.addView(View(ctx), lp(0, 1, 1f, l = 10f))
        }
    }

    /** blurred tiles: "Someone pretty · Liked you · 3h ago" with a lock, then the button to see who */
    private fun locked(l: List<JSONObject>, what: String) {
        grid(l.size) { i ->
            val x = l[i]
            val girl = x.optString("g") == "f"
            val tile = FrameLayout(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (girl) intArrayOf(Rs.c("#FF9CC6"), Rs.c("#E3086C")) else intArrayOf(Rs.c("#C9A6F5"), Rs.c("#8E1243"))).apply { cornerRadius = Ui.dp(20f) }
                clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; elevation = Ui.dp(4f)
                contentDescription = "Someone ${if (what == "match") "matched with you" else "liked you"}, ${ago(x.optString("at"))}. Locked"
            }
            tile.addView(BlurPerson(ctx), FrameLayout.LayoutParams(-1, -1))
            tile.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Lock, Color.WHITE, 15f, 2.6f)); scaleType = ImageView.ScaleType.CENTER; background = oval(Rs.argb(77, 255, 255, 255)) },
                FrameLayout.LayoutParams(dpi(30f), dpi(30f), Gravity.END or Gravity.TOP).apply { setMargins(0, dpi(10f), dpi(10f), 0) })
            val foot = vbox(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Rs.argb(0, 142, 18, 67), Rs.argb(166, 142, 18, 67)))
                setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
            }
            foot.addView(sdText(ctx, if (girl) "Someone pretty" else "Someone nice", 15f, Color.WHITE).oneLine())
            foot.addView(sdText(ctx, "${if (what == "match") "It's a Match" else "Liked you"} · ${ago(x.optString("at"))}", 13f, Rs.c("#FFE1EE"), bold = false).oneLine())
            tile.addView(foot, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            tile.press().onTap { h.locked("activity") }
            FrameLayout(ctx).apply { addView(tile, FrameLayout.LayoutParams(-1, dpi(176f))) }
        }
        val girlUnverified = h.verifyFirst
        val b = sdText(ctx, if (girlUnverified) "Verify free to see who" else if (what == "match") "See your matches" else "See who likes you", 17f, Color.WHITE)
            .icon(if (girlUnverified) Icon(Lucide.ShieldCheck, Color.WHITE, 19f, 2.3f) else Icon(Lucide.Crown, Color.WHITE, 18f, 2.2f, Color.WHITE), 8f)
        val btn = FrameLayout(ctx).apply { background = K.btn(25f); elevation = Ui.dp(4f) }
        btn.addView(b, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        btn.press().onTap { h.locked("activity") }
        body.addView(btn, lp(MATCH, dpi(50f), t = 14f))
    }

    /** Liked you (with a plan): photo tiles */
    private fun likedGrid(l: List<JSONObject>) {
        grid(l.size) { i ->
            val x = l[i]; val p = person(x)!!
            val tile = FrameLayout(ctx).apply {
                background = rounded(Rs.c("#F6D6E6"), 20f, 3f, Color.WHITE)
                clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; elevation = Ui.dp(4f)
                setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f))
                contentDescription = "${nameOf(p)} liked you, ${ago(x.optString("at"))}"
            }
            val inner = FrameLayout(ctx).apply { background = rounded(Rs.c("#F6D6E6"), 17f); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            inner.addView(img, FrameLayout.LayoutParams(-1, -1))
            inner.addView(View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Rs.argb(0, 122, 0, 54), Rs.argb(0, 122, 0, 54), Rs.argb(230, 92, 0, 40))) }, FrameLayout.LayoutParams(-1, -1))
            val foot = vbox(ctx).apply { setPadding(dpi(10f), 0, dpi(8f), dpi(8f)) }
            val nameRow = hbox(ctx, 5f)
            if (SdRules.liveOf(p)) nameRow.addView(LiveDot(ctx, 7f))
            nameRow.addView(sdText(ctx, nameOf(p), 15.5f, Color.WHITE).oneLine())
            foot.addView(nameRow)
            foot.addView(sdText(ctx, listOf(SdRules.s(p, "city"), ago(x.optString("at"))).filter { it.isNotEmpty() }.joinToString(" · "), 12.5f, Rs.argb(230, 255, 255, 255), bold = false).oneLine())
            inner.addView(foot, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            inner.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Heart, Color.WHITE, 13f, 2.4f, Color.WHITE)); scaleType = ImageView.ScaleType.CENTER; background = oval(K.hot, 2f, Color.WHITE) },
                FrameLayout.LayoutParams(dpi(28f), dpi(28f), Gravity.END or Gravity.TOP).apply { setMargins(0, dpi(5f), dpi(5f), 0) })
            tile.addView(inner, FrameLayout.LayoutParams(-1, -1))
            tile.press().onTap { h.openProfile(p) }
            // the photo, cut to fill the tile (object-fit: cover)
            var sized = ""
            tile.addOnLayoutChangeListener { _, l0, t0, r0, b0, _, _, _, _ ->
                val w = r0 - l0 - dpi(6f); val hh = b0 - t0 - dpi(6f)
                if (w > 0 && hh > 0 && sized != "$w×$hh") {
                    sized = "$w×$hh"
                    val raws = listOf(SdRules.s(p, "photo"), SdRules.s(p, "photo_thumb"), SdRules.s(p, "profile_picture")).filter { it.isNotBlank() }.distinct()
                    val demo = Demo.cover(p, w, hh)
                    if (demo != null) img.setImageBitmap(demo)
                    else if (raws.isEmpty()) img.setImageDrawable(null)
                    else Photos.cachedCover(raws, w, hh)?.let { img.setImageBitmap(it) } ?: Photos.loadCover(raws, w, hh) { b -> if (b != null) img.setImageBitmap(b) }
                }
            }
            FrameLayout(ctx).apply { addView(tile, FrameLayout.LayoutParams(-1, dpi(196f))) }
        }
    }

    /** a 48 round face with a 2 white gap and a 1.5 pink ring around it (outside its 48, like the web's box-shadow rings) */
    private fun face(p: JSONObject): View {
        val box = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val ring = FrameLayout(ctx).apply { background = oval(Color.WHITE, 1.5f, Rs.c("#F6C2DA")); setPadding(dpi(3.5f), dpi(3.5f), dpi(3.5f), dpi(3.5f)) }
        ring.addView(RoundPhoto(ctx, 48f).also { it.set(SdRules.thumbs(p), Demo.round(p, dpi(48f))) }, FrameLayout.LayoutParams(-1, -1))
        box.addView(ring, FrameLayout.LayoutParams(dpi(55f), dpi(55f), Gravity.CENTER).apply { setMargins(-dpi(3.5f), -dpi(3.5f), -dpi(3.5f), -dpi(3.5f)) })
        return box
    }
    private fun pillView(text: String, bg: Int, fg: Int, icon: Icon?): TextView = sdText(ctx, text, 13.5f, fg).apply {
        gravity = Gravity.CENTER; background = rounded(bg, 17f); setPadding(dpi(13f), 0, dpi(13f), 0); isSingleLine = true
        if (icon != null) icon(icon, 5f)
    }
    /** the right side of a row: their request's status, or Send Request */
    private fun action(x: JSONObject): View {
        val r = x.optJSONObject("request")
        val st = r?.optString("status").orEmpty()
        val mid = r?.optString("match_id").orEmpty().takeIf { it.isNotEmpty() && r?.isNull("match_id") == false }
        return when {
            st == "accepted" && mid != null -> pillView("Open Chat", K.pill, Color.WHITE, Icon(Lucide.MessageCircleMore, Color.WHITE, 15f, 2.4f)).also { it.press().onTap { openChat(mid) } }
            st == "pending" -> pillView("Request sent", K.tint, Rs.c("#A91C56"), Icon(Lucide.Hourglass, Rs.c("#A91C56"), 13f))
            st == "declined" -> pillView("Not accepted", Rs.c("#F4EFF2"), Rs.c("#7A6A73"), null)
            else -> pillView("Send Request", K.pill, Color.WHITE, Icon(Lucide.Send, Color.WHITE, 14f, 2.4f)).also { it.press().onTap { person(x)?.let { p -> h.askRequest(p) } } }
        }
    }
    private fun openChat(id: String) { close(); h.openChatId(id) }

    /** You liked / Matches: one card of rows (face · name · city and when · request) */
    private fun rows(l: List<JSONObject>, match: Boolean) {
        val card = vbox(ctx).apply { background = cardBg(); setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f)); elevation = Ui.dp(4f) }
        for ((i, x) in l.withIndex()) {
            val p = person(x) ?: continue
            val row = hbox(ctx, 11f).apply { setPadding(dpi(2f), dpi(11f), dpi(2f), dpi(11f)); clipChildren = false }
            row.addView(face(p), lp(dpi(48f), dpi(48f)))
            val mid = vbox(ctx)
            if (match) mid.addView(sdText(ctx, "It's a Match", 13f, K.hot).icon(Icon(Lucide.Heart, K.hot, 12f, 2.4f, K.hot), 4f))
            mid.addView(sdText(ctx, nameOf(p), 16f, K.ink).oneLine())
            mid.addView(sdText(ctx, listOf(SdRules.s(p, "city"), "${if (match) "matched" else "liked"} ${ago(x.optString("at"))}").filter { it.isNotBlank() }.joinToString(" · "), 13.5f, K.sub, bold = false).oneLine())
            row.addView(mid, lp(0, WRAP, 1f))
            row.addView(action(x), lp(WRAP, dpi(34f)))
            row.contentDescription = "${nameOf(p)}${if (match) ", it's a Match" else ""}"
            row.press(0.98f).onTap { h.openProfile(p) }
            card.addView(row, lp(MATCH, WRAP))
            if (i < l.size - 1) card.addView(View(ctx).apply { setBackgroundColor(K.line2) }, lp(MATCH, dpi(1f)))
        }
        body.addView(card, lp(MATCH, WRAP))
    }

    /** Requests I sent: who, the status, when — Open Chat once accepted */
    private fun requests(l: List<JSONObject>) {
        val card = vbox(ctx).apply { background = cardBg(); setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f)); elevation = Ui.dp(4f) }
        for ((i, x) in l.withIndex()) {
            val p = person(x) ?: continue
            val st = x.optString("status").ifEmpty { "pending" }
            val mid = x.optString("match_id").takeIf { it.isNotEmpty() && !x.isNull("match_id") }
            val row = hbox(ctx, 11f).apply { setPadding(dpi(2f), dpi(11f), dpi(2f), dpi(11f)); clipChildren = false }
            row.addView(face(p), lp(dpi(48f), dpi(48f)))
            val m = vbox(ctx)
            m.addView(sdText(ctx, nameOf(p), 16f, K.ink).oneLine())
            m.addView(when (st) {
                "accepted" -> sdText(ctx, "Accepted · you can chat now", 13.5f, Rs.c("#1E9E3A"))
                "declined" -> sdText(ctx, "Not accepted", 13.5f, K.sub, bold = false)
                else -> sdText(ctx, "Waiting for a reply", 13.5f, K.sub, bold = false)
            }.oneLine())
            m.addView(sdText(ctx, "Sent ${ago(x.optString("at"))}", 12.5f, K.soft, bold = false).oneLine())
            row.addView(m, lp(0, WRAP, 1f))
            row.addView(when {
                st == "accepted" && mid != null -> pillView("Open Chat", K.pill, Color.WHITE, Icon(Lucide.MessageCircleMore, Color.WHITE, 15f, 2.4f)).also { it.press().onTap { openChat(mid) } }
                st == "declined" -> pillView("Not accepted", Rs.c("#F4EFF2"), Rs.c("#7A6A73"), null)
                else -> pillView("Pending", K.tint, Rs.c("#A91C56"), Icon(Lucide.Hourglass, Rs.c("#A91C56"), 13f))
            }, lp(WRAP, dpi(34f)))
            row.contentDescription = "${nameOf(p)}, ${if (st == "accepted") "accepted" else if (st == "declined") "not accepted" else "pending"}"
            row.press(0.98f).onTap { h.openProfile(p) }
            card.addView(row, lp(MATCH, WRAP))
            if (i < l.size - 1) card.addView(View(ctx).apply { setBackgroundColor(K.line2) }, lp(MATCH, dpi(1f)))
        }
        body.addView(card, lp(MATCH, WRAP))
    }

    /** a blurred person shape (the locked tiles) */
    private class BlurPerson(ctx: android.content.Context) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val body = androidx.core.graphics.PathParser.createPathFromPathData("M7 48c1.5-10 8.6-15.5 17-15.5S39.5 38 41 48z")
        override fun onDraw(c: android.graphics.Canvas) {
            // viewBox 0 0 48 48, "slice"-free like the web's svg: scaled to fit, centred
            val s = minOf(width, height) / 48f
            if (s <= 0) return
            c.save(); c.translate((width - 48 * s) / 2, (height - 48 * s) / 2); c.scale(s, s)
            // blur(5px): a 5 px softness on screen, whatever the tile's size
            p.maskFilter = android.graphics.BlurMaskFilter(((5f * Ui.density - 0.5f) / 0.57735f) / s, android.graphics.BlurMaskFilter.Blur.NORMAL)
            p.color = Rs.argb(204, 255, 255, 255); c.drawCircle(24f, 19f, 9f, p)
            p.color = Rs.argb(191, 255, 255, 255); c.drawPath(body, p)
            p.maskFilter = null
            c.restore()
        }
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    }
}

@Suppress("unused") private val keepVg: Class<ViewGroup> = ViewGroup::class.java
