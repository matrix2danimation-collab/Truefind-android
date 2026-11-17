package app.truefind.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** A member's full profile (opens when a card is tapped) — ProfileView in SecretApp.jsx. */
object SdProfileView {
    fun open(h: SecretHome, p: JSONObject) {
        val ctx = h.context
        val root = FrameLayout(ctx)
        root.addView(PinkBackdrop(ctx), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val col = vbox(ctx)
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        var close: () -> Unit = {}
        val white = Color.WHITE
        val textShadow = Color.argb(71, 160, 20, 80)

        // ── top bar: back · ⋮ (Block / Report) ──
        val top = FrameLayout(ctx).apply { setPadding(dpi(6f), dpi(18f), dpi(8f), 0) }
        top.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ArrowLeft, white, 26f, 2.3f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back" }.press().onTap { close() },
            FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.START))
        val menu = vbox(ctx).apply { background = rounded(white, 16f, 1f, K.line2); setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f)); elevation = Ui.dp(8f); visibility = View.GONE }
        menu.addView(sdText(ctx, "Block", 15f, Ui.c("#c0153d")).icon(Icon(Lucide.Ban, Ui.c("#c0153d"), 17f), 10f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10f), 0, dpi(10f), 0) }
            .onTap { menu.visibility = View.GONE; h.moderate(p, "block") }, lp(dpi(158f), dpi(44f)))
        menu.addView(sdText(ctx, "Report", 15f, K.ink2).icon(Icon(Lucide.Flag, K.ink2, 17f), 10f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10f), 0, dpi(10f), 0) }
            .onTap { menu.visibility = View.GONE; h.moderate(p, "report") }, lp(dpi(158f), dpi(44f)))
        top.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.MoreVertical, white, 24f, 2.6f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "More options" }
            .press().onTap { menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.END))
        col.addView(top, lp(MATCH, WRAP))

        // ── round photo (gradient ring, white gap) + verified seal ──
        val photoBox = FrameLayout(ctx).apply { clipChildren = false }
        val ring = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#F7569F"), Ui.c("#C7105F"))).apply { shape = GradientDrawable.OVAL }
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f)); elevation = Ui.dp(10f)
        }
        val gap = FrameLayout(ctx).apply { background = oval(white); setPadding(dpi(5f), dpi(5f), dpi(5f), dpi(5f)) }
        val ph = RoundPhoto(ctx, 186f)
        ph.set(SdRules.thumbs(p).reversed(), Demo.round(p, dpi(186f)))
        gap.addView(ph, FrameLayout.LayoutParams(-1, -1)); ring.addView(gap, FrameLayout.LayoutParams(-1, -1))
        photoBox.addView(ring, FrameLayout.LayoutParams(dpi(204f), dpi(204f)))
        val verified = p.opt("verified") != false
        if (verified) photoBox.addView(ImageView(ctx).apply { setImageDrawable(Seal(46f)); scaleType = ImageView.ScaleType.CENTER; background = oval(white); elevation = Ui.dp(12f) },
            FrameLayout.LayoutParams(dpi(54f), dpi(54f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(2f); bottomMargin = dpi(8f) })
        col.addView(photoBox, lp(dpi(204f), dpi(204f), t = 4f, gravity = Gravity.CENTER_HORIZONTAL))

        // ── name · city · seen · like ──
        val same = h.isSameGender(p)
        val live = SdRules.liveOf(p)
        val head = hbox(ctx, 12f).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
        val info = vbox(ctx, 6f)
        val nameRow = hbox(ctx, 10f)
        nameRow.addView(sdText(ctx, SdRules.firstName(SdRules.s(p, "name")) + SdRules.s(p, "age").let { if (it.isNotEmpty()) ", $it" else "" }, 30f, white).oneLine().shadow(2f, 1f, textShadow), lp(0, WRAP, 1f).apply { weight = 0f; width = WRAP })
        if (live) nameRow.addView(LiveDot(ctx, 12f, 3f, Color.argb(46, 61, 214, 42)))
        info.addView(nameRow)
        val c = SdRules.cityOf(p); val st = SdRules.s(p, "state")
        val where = listOf(c, if (st.isNotEmpty() && SdRules.s(p, "city").isNotEmpty() && st != SdRules.s(p, "city")) st else "").filter { it.isNotEmpty() }.joinToString(", ")
        if (where.isNotEmpty()) info.addView(sdText(ctx, where, 17f, white).oneLine().shadow(2f, 1f, textShadow).icon(Icon(Lucide.MapPin, white, 19f), 6f))
        val dot = if (live) oval(K.green, 2f, Color.argb(217, 255, 255, 255)) else oval(Color.argb(191, 255, 255, 255))
        info.addView(sdText(ctx, SdRules.seenText(p), 15f, white).shadow(2f, 1f, textShadow).icon(dot.apply { setSize(dpi(9f), dpi(9f)) }, 7f))
        head.addView(info, lp(0, WRAP, 1f))
        val likeMsg = sdText(ctx, "", 14f, white).apply { gravity = Gravity.END; shadow(2f, 1f, textShadow); visibility = View.GONE }
        if (!same) {
            var fav = p.optBoolean("liked_by_me")
            var liking = false
            val heart = Icon(Lucide.Heart, if (fav) white else Ui.c("#C8186C"), 26f, 2f, if (fav) white else null)
            val heartView = ImageView(ctx).apply { setImageDrawable(heart); scaleType = ImageView.ScaleType.CENTER }
            val btn = FrameLayout(ctx).apply { background = oval(if (fav) Ui.c("#E3086C") else Ui.c("#FDEFF7"), 1f, Ui.c("#F6CFE1")); elevation = Ui.dp(4f); contentDescription = if (fav) "Liked. Tap to take back" else "Like" }
            btn.addView(heartView, FrameLayout.LayoutParams(-1, -1))
            fun paint() { btn.background = oval(if (fav) Ui.c("#E3086C") else Ui.c("#FDEFF7"), 1f, Ui.c("#F6CFE1")); heart.tint(if (fav) white else Ui.c("#C8186C"), if (fav) white else null) }
            btn.press().onTap {
                if (liking) return@onTap
                val next = !fav
                if (next) HeartBurst.play(btn, h.layer.root, heartView)
                fav = next; paint(); liking = true; likeMsg.visibility = View.GONE
                Api.invoke("likeProfile", JSONObject().put("to_profile_id", p.optString("id")).put("app", "secret").put("action", if (next) "like" else "unlike")) { r ->
                    liking = false
                    if (r.ok && r.data.optString("error").isEmpty()) {
                        h.liked(p.optString("id"), next)
                        if (next) { likeMsg.text = if (r.data.optBoolean("mutual")) "You both like each other" else "${SdRules.firstName(SdRules.s(p, "name")).ifEmpty { "They" }} will be notified"; likeMsg.visibility = View.VISIBLE }
                    } else {
                        fav = !next; paint()
                        likeMsg.text = r.data.optString("message").ifEmpty { r.data.optString("error").ifEmpty { "Couldn't send the like. Try again." } }; likeMsg.visibility = View.VISIBLE
                    }
                    likeMsg.icon(Icon(Lucide.Heart, white, 14f, 2f, white), 5f)
                }
            }
            head.addView(btn, lp(dpi(52f), dpi(52f)))
        }
        col.addView(head, lp(MATCH, WRAP, t = 18f))
        col.addView(likeMsg, lp(MATCH, WRAP, l = 24f, r = 24f, t = 10f))

        // ── story ──
        val story = h.storyFor(p)
        if (story != null) {
            val prem = h.isPremium
            val b = hbox(ctx, 9f).apply { background = rounded(white, 22f, 1f, K.line); setPadding(dpi(4f), 0, dpi(16f), 0) }
            val r2 = FrameLayout(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.c("#F7569F"), Ui.c("#FF9BCB"))).apply { shape = GradientDrawable.OVAL }; setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
            val w2 = FrameLayout(ctx).apply { background = oval(white); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
            w2.addView(RoundPhoto(ctx, 32f).also { it.set(SdRules.thumbs(p)); if (!prem) it.dim = 0.35f }, FrameLayout.LayoutParams(-1, -1)); r2.addView(w2, FrameLayout.LayoutParams(-1, -1))
            b.addView(r2, lp(dpi(36f), dpi(36f)))
            b.addView(if (prem) sdText(ctx, "Watch story", 15f, K.wine) else sdText(ctx, "Story · Premium", 15f, K.wine).icon(Icon(Lucide.Lock, K.wine, 15f), 6f))
            b.press().onTap { if (prem) h.watchStory(story) else h.locked("watch") }
            b.contentDescription = if (prem) "Watch ${SdRules.s(p, "name")}'s story" else "Stories are for Premium members"
            col.addView(b, lp(WRAP, dpi(44f), l = 24f, t = 14f))
        }

        // ── main button ──
        val mainBox = vbox(ctx).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
        col.addView(mainBox, lp(MATCH, WRAP, t = 18f))
        fun big(text: String, icon: android.graphics.drawable.Drawable?, sent: Boolean = false, onClick: () -> Unit): View {
            val tv = sdText(ctx, text, 22f, if (sent) K.wine else white).apply { gravity = Gravity.CENTER; if (icon != null) icon(icon, 10f) }
            return FrameLayout(ctx).apply {
                background = if (sent) rounded(white, 30f, 1.5f, K.line) else K.btn(30f).apply { setStroke(dpi(2f), white) }
                elevation = if (sent) 0f else Ui.dp(6f)
                addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                press().onTap(onClick)
            }
        }
        fun note(t: String) = sdText(ctx, t, 14f, K.wine).apply { gravity = Gravity.CENTER; lineHeight(1.15f) }
        fun renderMain() {
            mainBox.removeAllViews()
            val prem = h.isPremium
            when {
                p.optString("_chatId").isNotEmpty() -> {
                    mainBox.addView(big("Open Chat", if (prem) Icon(Lucide.MessageCircleMore, white, 27f, 2f) else Icon(Lucide.Lock, white, 22f, 2.3f)) { if (prem) h.openChat(p) else h.locked("chat") }, lp(MATCH, dpi(60f)))
                    mainBox.addView(note("Your private chat with ${SdRules.firstName(SdRules.s(p, "name")).ifEmpty { "them" }}. Numbers and links stay hidden."), lp(MATCH, WRAP, t = 8f))
                }
                same -> mainBox.addView(sdText(ctx, "Requests can be sent to the opposite gender only.", 15f, K.sub, bold = false).icon(Icon(Lucide.Info, K.rose, 18f), 8f).apply {
                    gravity = Gravity.CENTER; lineHeight(1.15f); background = rounded(white, 18f, 1f, K.line2); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
                }, lp(MATCH, WRAP))
                h.mayRequest -> {
                    val st = h.inviteOf(p.optString("id"))
                    val icon = when (st) { "sent" -> Icon(Lucide.Check, K.wine, 22f, 3f); "sending" -> null; else -> Icon(Lucide.Send, white, 24f, 2.2f) }
                    mainBox.addView(big(if (st == "sent") "Request Sent" else if (st == "sending") "Sending…" else "Send Request", icon, st == "sent") {
                        if (st != "sending" && st != "sent") h.askRequest(p)
                    }, lp(MATCH, dpi(60f)))
                    val cr = h.creditsShown
                    mainBox.addView(note(if (st == "sent") "When they accept, your private chat opens in Chat." else "1 credit per request${if (cr != null) " · You have ${SdRules.inr(cr)} ${if (cr == 1) "credit" else "credits"}" else ""}"), lp(MATCH, WRAP, t = 8f))
                }
                else -> {
                    val why = h.whyLocked
                    val tag = if (why == "verify") "VERIFY" else if (why == "plan") "PLAN" else "PREMIUM"
                    val tv = sdText(ctx, "Send Request", 22f, white).apply { gravity = Gravity.CENTER; icon(Icon(Lucide.Lock, white, 22f, 2.3f), 10f) }
                    val row = hbox(ctx, 10f, Gravity.CENTER)
                    row.addView(tv)
                    row.addView(sdText(ctx, tag, 11.5f, white).apply { letterSpacing = .06f; background = rounded(Color.argb(61, 255, 255, 255), 10f); setPadding(dpi(8f), dpi(4f), dpi(8f), dpi(4f)) })
                    val b = FrameLayout(ctx).apply { background = K.btn(30f).apply { setStroke(dpi(2f), white) }; elevation = Ui.dp(6f); addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); press().onTap { h.locked("request") } }
                    mainBox.addView(b, lp(MATCH, dpi(60f)))
                    val line = hbox(ctx, 6f, Gravity.CENTER)
                    line.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Lock, K.wine, 15f)) }, lp(dpi(15f), dpi(15f)))
                    line.addView(sdText(ctx, if (why == "verify") "Verify your profile first — then chat is free." else if (why == "plan") "Requests need credits from a plan." else "Free members can only view profiles.", 14f, K.wine).apply { lineHeight(1.1f) }, lp(0, WRAP, 1f).apply { weight = 0f; width = WRAP })
                    mainBox.addView(line, lp(MATCH, WRAP, t = 2f))
                    mainBox.addView(sdText(ctx, if (why == "verify") "Verify Now" else if (why == "plan") "Get a Plan" else "Get Premium", 14f, K.deep).apply {
                        gravity = Gravity.CENTER; paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    }.onTap { h.locked("request") }, lp(MATCH, dpi(44f)))
                }
            }
        }
        renderMain()

        // ── about · details · interests ──
        val about = SdRules.s(p, "bio", "about", "description")
        val sec = vbox(ctx).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
        if (about.isNotEmpty()) {
            sec.addView(sdText(ctx, "About Me", 21f, Ui.c("#A1144A")))
            sec.addView(sdText(ctx, about, 17f, Ui.c("#161216"), bold = false).lineHeight(1.2f).icon(Icon(Lucide.Heart, K.hot, 16f, 2.2f), 4f, end = true), lp(MATCH, WRAP, t = 8f))
        }
        val g = SdRules.sexOf(p)
        val chips = listOfNotNull(
            SdRules.s(p, "age").takeIf { it.isNotEmpty() }?.let { Lucide.Calendar to "$it Years" },
            g.takeIf { it.isNotEmpty() }?.let { Lucide.User to (if (it == "f") "Female" else "Male") },
            SdRules.s(p, "height").takeIf { it.isNotEmpty() }?.let { Lucide.Ruler to it },
            SdRules.s(p, "profession", "job", "work", "occupation").takeIf { it.isNotEmpty() }?.let { Lucide.Briefcase to it },
            SdRules.s(p, "education").takeIf { it.isNotEmpty() }?.let { Lucide.Sparkles to it },
        ).take(5)
        if (chips.isNotEmpty()) sec.addView(Flow(ctx, 6f).apply { for ((ic, t) in chips) addView(sdText(ctx, t, 14f, K.ink, bold = false).icon(Icon(ic, Ui.c("#97294B"), 16f), 5f).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(white, 999f, 1f, Ui.c("#EED6E2")); setPadding(dpi(9f), 0, dpi(11f), 0); height = dpi(36f); maxLines = 1
        }) }, lp(MATCH, WRAP, t = 14f))
        val tagsArr = p.optJSONArray("interests") ?: p.optJSONArray("hobbies")
        val tags = if (tagsArr == null) emptyList() else (0 until minOf(8, tagsArr.length())).map { tagsArr.optString(it) }.filter { it.isNotEmpty() }
        if (tags.isNotEmpty()) sec.addView(Flow(ctx, 6f).apply { for (t in tags) addView(sdText(ctx, t, 14f, K.wine).apply { background = rounded(K.tint2, 99f, 1f, K.line); setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f)) }) }, lp(MATCH, WRAP, t = 8f))
        col.addView(sec, lp(MATCH, WRAP, t = 18f))

        // ── verification ──
        val ver = vbox(ctx).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
        ver.addView(sdText(ctx, "Verification", 21f, Ui.c("#A1144A")))
        val vb = hbox(ctx, 12f).apply { background = grad(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#FDE5F1"), Ui.c("#FFF6FA")), 18f, 1f, Ui.c("#F5C7DC")); setPadding(dpi(14f), dpi(13f), dpi(14f), dpi(13f)) }
        vb.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ShieldCheck, if (verified) K.rose else K.soft, 38f, 1.8f)) }, lp(dpi(38f), dpi(38f)))
        val vt = vbox(ctx)
        vt.addView(sdText(ctx, if (verified) "Verified Member" else "Not verified yet", 17f, if (verified) Ui.c("#A91C56") else K.ink2))
        vt.addView(sdText(ctx, if (verified) "Identity & profile verified for a safer experience." else "This member hasn't finished verification yet.", 14f, K.ink2, bold = false).lineHeight(1.1f), lp(WRAP, WRAP, t = 2f))
        vb.addView(vt, lp(0, WRAP, 1f))
        ver.addView(vb, lp(MATCH, WRAP, t = 10f))
        col.addView(ver, lp(MATCH, WRAP, t = 22f))

        // ── rules + block / report ──
        val end = vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply { setPadding(dpi(24f), 0, dpi(24f), dpi(30f)) }
        val rule = hbox(ctx, 10f, Gravity.TOP).apply { background = rounded(white, 16f, 1f, K.line2); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)) }
        rule.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Lock, K.rose, 17f)) }, lp(dpi(17f), dpi(17f), t = 1f))
        rule.addView(sdText(ctx, "Chat only. Phone numbers, links and social IDs (Instagram, Telegram, WhatsApp…) are not allowed.", 13.5f, K.sub, bold = false).lineHeight(1.15f), lp(0, WRAP, 1f))
        end.addView(rule, lp(MATCH, WRAP, t = 14f))
        val moreBox = FrameLayout(ctx)
        val moreLink = sdText(ctx, "Block or report", 14f, K.soft, bold = false).apply { gravity = Gravity.CENTER }
        val two = hbox(ctx, 10f, Gravity.CENTER).apply { visibility = View.GONE; setPadding(0, dpi(4f), 0, 0) }
        two.addView(sdText(ctx, "Block", 15f, Ui.c("#c0153d")).icon(Icon(Lucide.Ban, Ui.c("#c0153d"), 16f), 6f).apply { gravity = Gravity.CENTER; background = rounded(white, 22f, 1f, Ui.c("#f3a7b5")); setPadding(dpi(18f), 0, dpi(18f), 0) }
            .onTap { h.moderate(p, "block") }, lp(WRAP, dpi(44f)))
        two.addView(sdText(ctx, "Report", 15f, K.ink2).icon(Icon(Lucide.Flag, K.ink2, 16f), 6f).apply { gravity = Gravity.CENTER; background = rounded(white, 22f, 1f, K.line); setPadding(dpi(18f), 0, dpi(18f), 0) }
            .onTap { h.moderate(p, "report") }, lp(WRAP, dpi(44f)))
        moreLink.onTap { moreLink.visibility = View.GONE; two.visibility = View.VISIBLE }
        moreBox.addView(moreLink, FrameLayout.LayoutParams(-1, dpi(44f)))
        moreBox.addView(two, FrameLayout.LayoutParams(-1, -2))
        end.addView(moreBox, lp(MATCH, WRAP, t = 10f))
        col.addView(end, lp(MATCH, WRAP))

        root.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { setMargins(0, dpi(18f + 46f), dpi(12f), 0) })
        root.setOnClickListener { menu.visibility = View.GONE }
        close = h.layer.page(root, z = 30f)
        // the request state can change while this page is open (sent, sending): keep the button fresh
        var key = ""
        fun keyNow() = "${h.isPremium}|${h.mayRequest}|${h.inviteOf(p.optString("id"))}|${h.creditsShown}"
        key = keyNow()
        val tick = object : Runnable { override fun run() { if (root.parent != null) { val k = keyNow(); if (k != key) { key = k; renderMain() }; root.postDelayed(this, 700) } } }
        root.postDelayed(tick, 700)
    }

    /** chips that wrap onto the next line (flex-wrap) */
    class Flow(ctx: android.content.Context, private val gapDp: Float) : android.view.ViewGroup(ctx) {
        override fun onMeasure(w: Int, hs: Int) {
            val max = MeasureSpec.getSize(w); val gap = Ui.dp(gapDp).toInt()
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i); measureChild(c, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + c.measuredWidth > max) { x = 0; y += rowH + gap; rowH = 0 }
                x += c.measuredWidth + gap; rowH = maxOf(rowH, c.measuredHeight)
            }
            setMeasuredDimension(max, y + rowH)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val max = r - l; val gap = Ui.dp(gapDp).toInt()
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > max) { x = 0; y += rowH + gap; rowH = 0 }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + gap; rowH = maxOf(rowH, c.measuredHeight)
            }
        }
    }
}

@Suppress("unused") private val keepTV: Class<TextView> = TextView::class.java
@Suppress("unused") private val keepLL2: Class<LinearLayout> = LinearLayout::class.java
