package app.truefind.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Secret Dating → My Activity, now the heart in the bottom bar (owner, 20 Nov night): no Notifications box any more,
 * only "Liked you" (who liked me) and "Matches" (we both liked), each with how many. With a plan: their photos — tap
 * one for their profile; a match's round chat button opens the chat (or Send Request, the same sheet as everywhere,
 * which asks before 1 credit is used). Without a plan (or a girl not verified yet) they stay blurred — the server
 * (getMyActivity) sends no names or photos then — and "See who it is" opens the plans, as before.
 * The same maroon top as Chat and Online Map.
 */
@SuppressLint("ViewConstructor")
class SdLikesPage(private val h: SecretHome) : FrameLayout(h.context) {
    private var tab = "liked"
    private var data: JSONObject? = null       // null = loading
    private var failed = false
    private var seq = 0
    private var active = false
    val top = SdTop(context, h, "My Activity", bell = false)
    private val tabs = SdTabs(context, listOf("Liked you", "Matches"), 150f) { i -> tab = if (i == 1) "match" else "liked"; renderBody(); scroll.scrollTo(0, 0) }
    private val scroll = ScrollView(context)
    private val body = vbox(context)
    private val cta = FrameLayout(context)
    private val ctaText = jk(context, "", 16f, Color.WHITE, 700)

    init {
        setBackgroundColor(SD.M)
        isClickable = true
        addView(MaroonDeco(context), LayoutParams(-1, -1))
        val inset = InsetFrame(context)
        addView(inset, LayoutParams(-1, -1))
        inset.addView(top, LayoutParams(-1, -2))
        val strip = FrameLayout(context).apply { background = SD.stripBg() }
        strip.addView(tabs, LayoutParams(-1, -2))
        inset.addView(strip, LayoutParams(-1, -1).apply { topMargin = dpi(SD.STRIP_TOP) })
        val sheet = FrameLayout(context).apply { background = SD.sheetBg(); clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND }
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        body.setPadding(dpi(16f), dpi(28f), dpi(16f), dpi(24f))
        scroll.addView(body, LayoutParams(-1, -2))
        sheet.addView(scroll, LayoutParams(-1, -1))
        sheet.addView(sdHandle(context))
        // "See who it is" (no plan): stays at the bottom of the sheet
        cta.background = rounded(SD.M, 26f)
        cta.elevation = Ui.dp(10f)
        cta.outlineSpotShadowColorCompat(Color.argb(160, 120, 10, 50))
        cta.addView(ctaText, LayoutParams(-2, -2, Gravity.CENTER))
        cta.press().onTap { h.locked("activity") }
        cta.visibility = GONE
        sheet.addView(cta, LayoutParams(-1, dpi(52f), Gravity.BOTTOM).apply { leftMargin = dpi(16f); rightMargin = dpi(16f); bottomMargin = dpi(16f) })
        inset.addView(sheet, LayoutParams(-1, -1).apply { topMargin = dpi(SD.SHEET_TOP) })
        renderBody()
    }

    /** shown (the heart tab) / hidden */
    fun setActive(on: Boolean) {
        active = on
        if (on) { top.update(); load() }
    }
    /** a tab asked for from outside (the bell, a notification): "liked" · "match" */
    fun pick(which: String) {
        val t = if (which == "match") "match" else "liked"
        if (t == tab) return
        tab = t; tabs.pick(if (t == "match") 1 else 0); renderBody(); scroll.scrollTo(0, 0)
    }
    fun atTop(): Boolean = !scroll.canScrollVertically(-1)
    fun render() { top.update(); if (data != null) renderBody() }

    // ═════════════ data: getMyActivity ═════════════
    fun load(done: (() -> Unit)? = null) {
        val my = ++seq
        failed = false
        if (data == null) renderBody()
        Api.invoke("getMyActivity", JSONObject()) { r ->
            if (my != seq) { done?.invoke(); return@invoke }
            if (r.ok && r.data.optString("error").isEmpty()) { data = r.data; failed = false } else failed = true
            renderBody()
            done?.invoke()
            // what was new counts as seen once the list has been on screen for a moment (instead of the old Notifications box)
            if (active && data != null && h.hasNewActivity) postDelayed({ if (active && isShown) h.likesSeen() }, 1200)
        }
    }

    private fun list(key: String): List<JSONObject> {
        val a: JSONArray = data?.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun person(x: JSONObject): JSONObject? = x.optJSONObject("person")
    private fun ago(t: String) = SdRules.timeAgo(t)

    // ═════════════ the list ═════════════
    private fun renderBody() {
        body.removeAllViews()
        cta.visibility = GONE
        val d = data
        val counts = d?.optJSONObject("counts")
        tabs.count(0, counts?.optInt("liked_you", 0)); tabs.count(1, counts?.optInt("matches", 0))
        if (d == null) {
            if (failed) {
                val b = vbox(context, 10f, Gravity.CENTER_HORIZONTAL)
                b.addView(jk(context, "Couldn't load your activity.", 15f, SD.SUB, 500).apply { gravity = Gravity.CENTER })
                b.addView(jk(context, "Try again", 15f, Color.WHITE, 700).apply { gravity = Gravity.CENTER; background = rounded(SD.M, 21f); setPadding(dpi(22f), 0, dpi(22f), 0) }
                    .press().onTap { load() }, lp(WRAP, dpi(42f)))
                body.addView(b, lp(MATCH, WRAP, t = 30f))
            } else grid(4) { View(context).apply { background = rounded(Ui.c("#F4F4F7"), 20f) } }
            return
        }
        val canSee = d.optBoolean("can_see") == true
        val key = if (tab == "match") "matches" else "liked_you"
        val l = list(key).filter { person(it) != null || !canSee }
        if (l.isEmpty()) {
            val msg = if (tab == "match") "When you and someone like each other, it's a Match." else "When someone likes you, they show up here."
            val b = vbox(context, 0f, Gravity.CENTER_HORIZONTAL)
            b.addView(sdIconView(context, if (tab == "match") SdIcon(listOf(SdPaths.TWO_HEARTS_BACK to SD.M_LIGHT, SdPaths.TWO_HEARTS_FRONT to SD.M), 40f)
                else SdIcon(listOf(SdPaths.HEART_FILL to SD.M), 40f)).apply { background = oval(SD.M_TINT); setPadding(dpi(18f), dpi(18f), dpi(18f), dpi(18f)) }, lp(dpi(76f), dpi(76f)))
            b.addView(jk(context, msg, 15f, SD.SUB, 500).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP, t = 14f))
            body.addView(b, lp(MATCH, WRAP, t = 40f))
            return
        }
        if (!canSee) {
            grid(l.size) { i -> lockedTile(l[i]) }
            val verify = h.verifyFirst
            ctaText.text = if (verify) "Verify free to see who" else if (tab == "match") "See your matches" else "See who it is"
            ctaText.icon(if (verify) Icon(Lucide.ShieldCheck, Color.WHITE, 19f, 2.3f) else SdIcon(listOf(SdPaths.CROWN to Color.WHITE), 20f), 9f)
            cta.contentDescription = ctaText.text
            cta.visibility = VISIBLE
            body.setPadding(dpi(16f), dpi(28f), dpi(16f), dpi(88f))
        } else {
            body.setPadding(dpi(16f), dpi(28f), dpi(16f), dpi(24f))
            grid(l.size) { i -> tile(l[i], tab == "match") }
        }
    }

    /** two columns, 12 apart */
    private fun grid(n: Int, cell: (Int) -> View) {
        var row: LinearLayout? = null
        for (i in 0 until n) {
            if (i % 2 == 0) row = hbox(context).also { body.addView(it, lp(MATCH, WRAP, t = if (i == 0) 0f else 12f)) }
            row!!.addView(cell(i), lp(0, dpi(214f), 1f, l = if (i % 2 == 1) 12f else 0f))
            if (i == n - 1 && i % 2 == 0) row.addView(View(context), lp(0, 1, 1f, l = 12f))
        }
    }

    private fun tileBase(): FrameLayout = FrameLayout(context).apply {
        background = rounded(SD.M_TINT, 20f)
        clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
    }
    private fun badge(icon: SdIcon): View = sdIconView(context, icon).apply {
        background = oval(Color.WHITE); elevation = Ui.dp(3f); setPadding(dpi(7f), dpi(7f), dpi(7f), dpi(7f))
    }
    private fun foot(title: String, sub: String, online: Boolean, endGap: Float): View {
        val f = vbox(context)
        val row = hbox(context, 6f)
        if (online) row.addView(View(context).apply { background = oval(SD.ONLINE) }, lp(dpi(8f), dpi(8f)))
        row.addView(jk(context, title, 15f, Color.WHITE, 700).oneLine())
        f.addView(row)
        f.addView(jk(context, sub, 11.5f, Color.argb(219, 255, 255, 255), 500).oneLine(), lp(MATCH, WRAP, t = 2f))
        return f.also { it.setPadding(dpi(12f), 0, dpi(endGap), dpi(12f)) }
    }
    private fun shade(): View = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(0, 20, 6, 14), Color.argb(0, 20, 6, 14), Color.argb(189, 20, 6, 14)))
    }

    /** with a plan: their photo, name, city · when — tap for their profile */
    private fun tile(x: JSONObject, match: Boolean): View {
        val p = person(x)!!
        val name = SdRules.firstName(SdRules.s(p, "name")) + SdRules.s(p, "age").let { if (it.isNotEmpty()) ", $it" else "" }
        val t = tileBase()
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        t.addView(img, LayoutParams(-1, -1))
        t.addView(shade(), LayoutParams(-1, -1))
        val req = x.optJSONObject("request")
        val st = req?.optString("status").orEmpty()
        val sub = when {
            match && st == "pending" -> "Request sent · waiting"
            match && st == "declined" -> "Not accepted"
            else -> listOf(SdRules.s(p, "city"), ago(x.optString("at"))).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        t.addView(foot(name, sub, SdRules.liveOf(p), if (match) 56f else 12f), LayoutParams(-1, -2, Gravity.BOTTOM))
        t.addView(badge(if (match) SdIcon(listOf(SdPaths.TWO_HEARTS_BACK to SD.M_LIGHT, SdPaths.TWO_HEARTS_FRONT to SD.M), 17f) else SdIcon(listOf(SdPaths.HEART_FILL to SD.M), 16f)),
            LayoutParams(dpi(30f), dpi(30f), Gravity.END or Gravity.TOP).apply { topMargin = dpi(10f); rightMargin = dpi(10f) })
        t.contentDescription = "Open $name's profile"
        t.press(0.98f).onTap { h.openProfile(p) }
        if (match) {
            val mid = req?.optString("match_id").orEmpty().takeIf { it.isNotEmpty() && req?.isNull("match_id") == false }
            val chat = FrameLayout(context).apply { background = oval(SD.M); elevation = Ui.dp(6f) }
            chat.addView(sdIconView(context, SdIcon(listOf(SdPaths.CHAT_FILL to Color.WHITE), 19f)), LayoutParams(dpi(19f), dpi(19f), Gravity.CENTER))
            chat.addView(sdIconView(context, SdIcon(listOf(SdPaths.CHAT_LINES to SD.M), 19f, 1.9f)), LayoutParams(dpi(19f), dpi(19f), Gravity.CENTER))
            chat.contentDescription = if (st == "accepted" && mid != null) "Open chat with $name" else "Send Request to $name"
            chat.press().onTap {
                when {
                    st == "accepted" && mid != null -> h.openChatId(mid)
                    st == "pending" -> h.say("Request sent. Waiting for ${SdRules.firstName(SdRules.s(p, "name"))} to accept.")
                    st == "declined" -> h.say("${SdRules.firstName(SdRules.s(p, "name"))} didn't accept your request.")
                    else -> h.askRequest(p)
                }
            }
            t.addView(chat, LayoutParams(dpi(38f), dpi(38f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(10f); bottomMargin = dpi(12f) })
        }
        // the photo, cut to fill the tile
        var sized = ""
        t.addOnLayoutChangeListener { _, l0, t0, r0, b0, _, _, _, _ ->
            val w = r0 - l0; val hh = b0 - t0
            if (w > 0 && hh > 0 && sized != "$w×$hh") {
                sized = "$w×$hh"
                val raws = listOf(SdRules.s(p, "photo"), SdRules.s(p, "photo_thumb"), SdRules.s(p, "profile_picture")).filter { it.isNotBlank() }.distinct()
                val demo = Demo.cover(p, w, hh)
                if (demo != null) img.setImageBitmap(demo)
                else if (raws.isNotEmpty()) Photos.cachedCover(raws, w, hh)?.let { img.setImageBitmap(it) } ?: Photos.loadCover(raws, w, hh) { b -> if (b != null) img.setImageBitmap(b) }
            }
        }
        return t
    }

    /** without a plan: a soft blurred shape, "Someone pretty · Liked you · 3h ago", a lock */
    private fun lockedTile(x: JSONObject): View {
        val girl = x.optString("g") == "f"
        val match = tab == "match"
        val t = tileBase()
        t.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (girl) intArrayOf(Ui.c("#E7A3C0"), SD.M) else intArrayOf(Ui.c("#B9A3D9"), Ui.c("#7A1240"))).apply { cornerRadius = Ui.dp(20f) }
        t.addView(BlurShape(context), LayoutParams(-1, -1))
        t.addView(shade(), LayoutParams(-1, -1))
        t.addView(foot(if (girl) "Someone pretty" else "Someone nice", "${if (match) "It's a Match" else "Liked you"} · ${ago(x.optString("at"))}", false, 12f), LayoutParams(-1, -2, Gravity.BOTTOM))
        t.addView(badge(SdIcon(listOf(SdPaths.LOCK to SD.M), 16f, 2.2f)), LayoutParams(dpi(30f), dpi(30f), Gravity.END or Gravity.TOP).apply { topMargin = dpi(10f); rightMargin = dpi(10f) })
        t.contentDescription = "Someone ${if (match) "matched with you" else "liked you"}, ${ago(x.optString("at"))}. Locked"
        t.press(0.98f).onTap { h.locked("activity") }
        return t
    }

    /** a blurred person shape (the locked tiles) */
    private class BlurShape(ctx: android.content.Context) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val shape = androidx.core.graphics.PathParser.createPathFromPathData("M7 48c1.5-10 8.6-15.5 17-15.5S39.5 38 41 48z")
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onDraw(c: android.graphics.Canvas) {
            val s = minOf(width, height) / 48f
            if (s <= 0) return
            c.save(); c.translate((width - 48 * s) / 2, (height - 48 * s) / 2 - Ui.dp(10f)); c.scale(s, s)
            p.maskFilter = android.graphics.BlurMaskFilter(((9f * Ui.density) / 0.57735f) / s, android.graphics.BlurMaskFilter.Blur.NORMAL)
            p.color = Color.argb(190, 255, 255, 255); c.drawCircle(24f, 19f, 9f, p)
            p.color = Color.argb(170, 255, 255, 255); c.drawPath(shape, p)
            p.maskFilter = null
            c.restore()
        }
    }

    /** test shots */
    fun preview(what: String) { when (what) { "match" -> pick("match"); "scroll" -> scroll.scrollTo(0, body.height) } }
}
