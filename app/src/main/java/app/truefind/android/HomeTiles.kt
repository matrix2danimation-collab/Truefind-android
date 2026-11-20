package app.truefind.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max

/** A rounded box with a border and an outer ring (CSS border + box-shadow: 0 0 0 Npx). */
class RingBox(private val fill: Int, private val radius: Float, private val border: Int, private val borderW: Float,
              private val ring: Int = Color.TRANSPARENT, private val ringW: Float = 0f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds
        if (ringW > 0f) { p.color = ring; r.set(b.left - ringW, b.top - ringW, b.right + ringW, b.bottom + ringW); c.drawRoundRect(r, radius + ringW, radius + ringW, p) }
        p.color = border; r.set(b); c.drawRoundRect(r, radius, radius, p)
        p.color = fill; r.set(b.left + borderW, b.top + borderW, b.right - borderW, b.bottom - borderW)
        val ir = max(0f, radius - borderW); c.drawRoundRect(r, ir, ir, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, radius) }
}

/** A plain repeating background tile (the web's background-image: url(svg) …). */
class TileFill(private val tile: Svg, private val tileDp: Float, private val key: String, private val bg: Int = Color.TRANSPARENT, private val radius: Float = 0f) : Drawable() {
    private val p by lazy { SvgPattern.paint(tile, tileDp, 0f, key) }
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun draw(c: Canvas) {
        r.set(bounds)
        if (bg != Color.TRANSPARENT) { pb.color = bg; c.drawRoundRect(r, radius, radius, pb) }
        c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat())
        r.set(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
        c.drawRoundRect(r, radius, radius, p); c.restore()
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The Home boxes that are drawn once (Live Rooms tiles, Serious Single, Safe & Privacy, footer) and the person cards. */
object HomeTiles {
    private fun label(ctx: Context, m: Zm, s: String) = Tf.tpx(ctx, s, m.px(13.5f), Ui.c("#1F1235")).apply { maxLines = 1 }

    /** LIVE tag: lilac pill with a pulsing purple dot */
    private fun liveTag(ctx: Context, m: Zm, text: String = "LIVE", dot: Boolean = true): View {
        val tag = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Tf.boxPx(Ui.c("#EBE2FD"), m.px(9f))
            setPadding(m.pi(if (dot) 6f else 7f), 0, m.pi(7f), 0)
            clipChildren = false; clipToPadding = false
        }
        if (dot) {
            val holder = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            holder.addView(PingDot(ctx, Ui.c("#6C2BD9"), m.px(5f), 1600, 3.2f, .55f, .7f, blink = true),
                FrameLayout.LayoutParams(m.pi(18f), m.pi(18f)).apply { leftMargin = -m.pi(6.5f); topMargin = -m.pi(6.5f) })
            tag.addView(holder, LinearLayout.LayoutParams(m.pi(5f), m.pi(5f)).apply { rightMargin = m.pi(4f) })
        }
        tag.addView(Tf.tpx(ctx, text, m.px(9.5f), Ui.c("#5B21B6")).apply { letterSpacing = 0.6f / 9.5f })
        return tag
    }

    /** one Live Rooms tile: white card, corner marks, tag, icon over the label */
    private fun tile(ctx: Context, m: Zm, name: String, icon: View, iconW: Int, iconH: Int, iconTop: Int = 0, iconBottom: Int = 0,
                     bg: Drawable, tag: View?, tagLeft: View? = null, onClick: () -> Unit): FrameLayout {
        val t = FrameLayout(ctx).apply { background = bg; clipChildren = false; clipToPadding = false; contentDescription = name; setOnClickListener { onClick() } }
        t.addView(CornerMarks(ctx, m), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, m.pi(14f), 0, 0); clipChildren = false }
        col.addView(icon, LinearLayout.LayoutParams(iconW, iconH).apply { topMargin = iconTop; bottomMargin = iconBottom })
        col.addView(label(ctx, m, name), LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(6f) })
        t.addView(col, FrameLayout.LayoutParams(-1, -1))
        if (tag != null) t.addView(tag, FrameLayout.LayoutParams(-2, m.pi(17f), Gravity.END).apply { topMargin = m.pi(7f); rightMargin = m.pi(7f) })
        if (tagLeft != null) t.addView(tagLeft, FrameLayout.LayoutParams(-2, m.pi(17f), Gravity.START).apply { topMargin = m.pi(7f) })
        Ui.pressable(t, 0.97f)
        return t
    }

    /** Live Rooms: Live Dance · Live Room · Game | Expert Talk · Dashboard · Earn */
    fun grid(ctx: Context, m: Zm, go: (String) -> Unit, earnTap: () -> Unit): View {
        val plain = { RingBox(Color.WHITE, m.px(18f), Ui.c("#E4D9F7"), max(1f, m.px(1f))) }
        val g = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val r1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val r2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val dance = CoverPicFit(ctx, "home/dance_icon.jpg")
        r1.addView(tile(ctx, m, "Live Dance", dance, m.pi(38f), m.pi(41f), -m.pi(8f), -m.pi(3f), plain(), liveTag(ctx, m)) { go("/dance") }, cell(m, 0))
        r1.addView(tile(ctx, m, "Live Room", LiveRoomIcon(ctx, m), m.pi(30f), m.pi(30f), bg = plain(), tag = liveTag(ctx, m)) { go("/live") }, cell(m, 1))
        val ctrl = FrameLayout(ctx).apply { addView(SvgView(ctx, Svg.of(HomeArt.CONTROLLER)), FrameLayout.LayoutParams(m.pi(30f), m.pi(21f), Gravity.CENTER)) }
        val off = Tf.tpx(ctx, "80% OFF", m.px(9.5f), Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL; letterSpacing = 0.3f / 9.5f; setPadding(m.pi(5f), 0, m.pi(6f), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.c("#1E3A8A")); val r = m.px(9f); cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) }
        }
        r1.addView(tile(ctx, m, "Game", ctrl, m.pi(30f), m.pi(30f), bg = RingBox(Color.WHITE, m.px(18f), Ui.c("#1D4ED8"), m.px(1.5f), Ui.c("#DBEAFE"), m.px(3f)),
            tag = liveTag(ctx, m), tagLeft = off) { go("/games") }, cell(m, 2))
        r2.addView(tile(ctx, m, "Expert Talk", ExpertIcon(ctx), m.pi(36f), m.pi(30f), bg = plain(), tag = liveTag(ctx, m)) { go("/expert-talk") }, cell(m, 0))
        r2.addView(tile(ctx, m, "Dashboard", DashIcon(ctx), m.pi(30f), m.pi(30f), bg = plain(), tag = liveTag(ctx, m, "MY STATS", false)) { go("/dashboard") }.apply { contentDescription = "Open my Dashboard" }, cell(m, 1))
        val earn = tile(ctx, m, "Earn", EarnCoins(ctx), m.pi(30f), m.pi(30f), bg = RingBox(Color.WHITE, m.px(18f), Ui.c("#F5C542"), m.px(1.5f), Ui.c("#FEF3C7"), m.px(3f)), tag = null) { earnTap() }
        earn.addView(EarnShine(ctx, m), FrameLayout.LayoutParams(-1, -1).apply { val p = m.pi(14f); leftMargin = -p; topMargin = -p; rightMargin = -p; bottomMargin = -p })
        r2.addView(earn, cell(m, 2))
        g.addView(r1, LinearLayout.LayoutParams(-1, m.pi(96f)))
        g.addView(r2, LinearLayout.LayoutParams(-1, m.pi(96f)).apply { topMargin = m.pi(10f) })
        return g
    }
    private fun cell(m: Zm, i: Int) = LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = m.pi(10f) }

    /** Serious Single (opens Secret Dating) */
    fun serious(ctx: Context, m: Zm, open: () -> Unit): View {
        val card = FrameLayout(ctx).apply {
            background = Tf.boxPx(Ui.c("#3A1A70"), m.px(20f)); clipToOutline = true
            setOnClickListener { open() }
        }
        card.addView(View(ctx).apply { background = TileFill(Svg.of(HomeArt.PLUS_TILE), 22f * m.z, "plus") }, FrameLayout.LayoutParams(-1, -1))
        card.addView(SvgView(ctx, Svg.of(HomeArt.SS_HEARTS), meet = false), FrameLayout.LayoutParams(m.pi(202f), m.pi(110f), Gravity.END).apply { topMargin = m.pi(14f) })
        card.addView(CoverPicFit(ctx, "home/ss_girl.webp"), FrameLayout.LayoutParams(m.pi(104f), m.pi(112f), Gravity.END).apply { topMargin = m.pi(12f); rightMargin = m.pi(22f) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(m.pi(16f), m.pi(16f), m.pi(16f), 0) }
        top.addView(Tf.tpx(ctx, "PREMIUM", m.px(10f), Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL; letterSpacing = 1.2f / 10f
            background = Tf.boxPx(Color.TRANSPARENT, m.px(10f), max(1f, m.px(1f)), Color.argb(128, 255, 255, 255))
            setPadding(m.pi(7f), 0, m.pi(9f), 0)
            compoundDrawablePadding = m.pi(5f)
            setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.CROWN_GOLD11), 11f * m.z), null, null, null)
        }, LinearLayout.LayoutParams(-2, m.pi(20f)))
        top.addView(Tf.tpx(ctx, "Serious Single", m.px(22f), Color.WHITE).apply { letterSpacing = -0.2f / 22f; setPadding(0, m.pi(2f), 0, m.pi(2f)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(8f) })
        top.addView(Tf.tpx(ctx, "Date quietly.\nYour identity stays yours.", m.px(13f), Color.argb(217, 255, 255, 255), false).apply { setLineSpacing(m.px(13f) * 0.38f - 0f, 1f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(4f) })
        col.addView(top)
        val feats = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, m.pi(12f), 0, 0) }
        for ((svg, text) in listOf(HomeArt.MASK to "Anonymous", HomeArt.EYE_OFF to "Hidden", HomeArt.LOCK to "Private", HomeArt.INFINITY to "No Limits")) {
            val f = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            f.addView(SvgView(ctx, Svg.of(svg)), LinearLayout.LayoutParams(m.pi(20f), m.pi(20f)))
            f.addView(Tf.tpx(ctx, text, m.px(11.5f), Color.argb(235, 255, 255, 255)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(5f) })
            feats.addView(f, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = m.pi(4f); rightMargin = m.pi(4f) })
        }
        col.addView(feats, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = m.pi(12f); rightMargin = m.pi(12f); topMargin = m.pi(14f) })
        col.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
        val btn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = Tf.boxPx(Ui.c("#EDE9FE"), m.px(14f))
            setOnClickListener { open() }
        }
        btn.addView(SvgView(ctx, Svg.of(HomeArt.CROWN_PURPLE15)), LinearLayout.LayoutParams(m.pi(15f), m.pi(15f)).apply { rightMargin = m.pi(6f) })
        btn.addView(Tf.tpx(ctx, "Explore Premium", m.px(14.5f), Ui.c("#4A1D96")))
        btn.addView(SvgView(ctx, Svg.of(HomeArt.ARROW_PURPLE15)), LinearLayout.LayoutParams(m.pi(15f), m.pi(15f)).apply { leftMargin = m.pi(6f) })
        Ui.pressable(btn, 0.97f)
        col.addView(btn, LinearLayout.LayoutParams(-1, m.pi(44f)).apply { leftMargin = m.pi(14f); rightMargin = m.pi(14f); bottomMargin = m.pi(14f) })
        card.addView(col, FrameLayout.LayoutParams(-1, -1))
        return card
    }

    /** Safe & Privacy: Verified Creators · Secure Payments · Block Anytime */
    fun safety(ctx: Context, m: Zm, open: (String) -> Unit): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple("verified", HomeArt.SAFE_VERIFIED, "Verified\nCreators"), Triple("payments", HomeArt.SAFE_PAY, "Secure\nPayments"), Triple("block", HomeArt.SAFE_BLOCK, "Block\nAnytime")).forEachIndexed { i, (k, svg, text) ->
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = Tf.boxPx(Color.WHITE, m.px(16f), max(1f, m.px(1f)), Ui.c("#EEE9F5"))
                setPadding(m.pi(4f), 0, m.pi(4f), 0)
                setOnClickListener { open(k) }
            }
            t.addView(SvgView(ctx, Svg.of(svg)), LinearLayout.LayoutParams(m.pi(40f), m.pi(40f)))
            t.addView(Tf.tpx(ctx, text, m.px(12.5f), Ui.c("#1F1235")).apply { gravity = Gravity.CENTER; setLineSpacing(m.px(12.5f) * 0.2f - 0f, 1f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(6f) })
            Ui.pressable(t, 0.97f)
            row.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = m.pi(10f) })
        }
        return row
    }

    fun footer(ctx: Context, m: Zm, year: Int): View {
        val f = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val brand = SpannableStringBuilder("True Find").apply {
            setSpan(ForegroundColorSpan(Ui.c("#4A1D96")), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Ui.c("#B0306C")), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        f.addView(Tf.tpx(ctx, brand, m.px(15f), Ui.c("#4A1D96")))
        // what keeps members safe — only what the app really does (owner, 19 Nov: no "100 % trusted", no © line)
        for ((i, line) in listOf(
            "Every new photo is checked by our team",
            "Phone numbers and links are blocked in chats",
            "Report or block anyone, anytime",
        ).withIndex()) {
            f.addView(Tf.tpx(ctx, line, m.px(12f), Ui.c("#6B6478"), false).apply {
                compoundDrawablePadding = m.pi(5f)
                setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.SHIELD_GREEN13), 13f * m.z), null, null, null)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(if (i == 0) 6f else 3f) })
        }
        val love = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        love.addView(Tf.tpx(ctx, "Made with", m.px(11.5f), Ui.c("#6B6478"), false))
        love.addView(SvgView(ctx, Svg.of(HomeArt.HEART_PURPLE11)).apply { contentDescription = "love" }, LinearLayout.LayoutParams(m.pi(11f), m.pi(11f)).apply { leftMargin = m.pi(4f); rightMargin = m.pi(4f) })
        love.addView(Tf.tpx(ctx, "in India", m.px(11.5f), Ui.c("#6B6478"), false))
        f.addView(love, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(1f) })
        return f
    }

    /** Online card (113 × 188, the Weekly Top card size and doodle top): 3D face with the status ring, name, city, Online · Free / On a call, Video Call */
    fun onlineCard(ctx: Context, m: Zm, p: JSONObject, face: String, open: () -> Unit): View {
        val busy = !HomeRules.isFree(p)
        val age = HomeRules.ageOf(p)
        val name = "${HomeRules.firstName(p).ifEmpty { "Member" }}${if (age != null) ", $age" else ""}"
        val city = HomeRules.cityOf(p).ifEmpty { "India" }
        val card = FrameLayout(ctx).apply {
            background = Tf.boxPx(Color.WHITE, m.px(18f))
            foreground = Tf.boxPx(Color.TRANSPARENT, m.px(18f), m.px(1.5f), Ui.c("#EEE9F5"))
            clipToOutline = true
            contentDescription = "Open $name, $city, ${if (busy) "on a call" else "online"}"
            setOnClickListener { open() }
        }
        // the Weekly Top card's doodle top (owner, 19 Nov evening: the Online box like Weekly Top Creators)
        val bw = Math.round(m.px(1.5f))
        card.addView(doodleTop(ctx, m, bw), FrameLayout.LayoutParams(-1, m.pi(116f) - bw).apply { leftMargin = bw; topMargin = bw; rightMargin = bw })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val av = FrameLayout(ctx)
        val ring = FrameLayout(ctx).apply { background = Tf.circle(Ui.c("#EDE7FB")).apply { setStroke(m.pi(3f), if (busy) Ui.c("#FCA5A5") else Ui.c("#86EFAC")) }; setPadding(m.pi(3f), m.pi(3f), m.pi(3f), m.pi(3f)) }
        ring.addView(CoverPic(ctx, face, round = true), FrameLayout.LayoutParams(-1, -1))
        av.addView(ring, FrameLayout.LayoutParams(-1, -1))
        av.addView(View(ctx).apply { background = Tf.circle(if (busy) Ui.c("#EF4444") else Ui.c("#22C55E")).apply { setStroke(m.pi(2.5f), Color.WHITE) } },
            FrameLayout.LayoutParams(m.pi(14f), m.pi(14f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = m.pi(2f); bottomMargin = m.pi(2f) })
        col.addView(av, LinearLayout.LayoutParams(m.pi(68f), m.pi(68f)).apply { topMargin = m.pi(14f) })
        col.addView(Tf.tpx(ctx, name, m.px(14f), Ui.c("#1F1235")).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = m.pi(101f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(8f) })
        col.addView(Tf.tpx(ctx, city, m.px(12f), Ui.c("#6B6478"), false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = m.pi(101f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(2f) })
        col.addView(Tf.tpx(ctx, if (busy) "On a call" else "Online · Free", m.px(11f), if (busy) Ui.c("#DC2626") else Ui.c("#15803D")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(3f) })
        card.addView(col, FrameLayout.LayoutParams(-1, -1))
        val btn = Tf.tpx(ctx, "Video Call", m.px(13f), if (busy) Ui.c("#6B6478") else Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Tf.boxPx(if (busy) Ui.c("#E9E3F5") else Ui.c("#6C2BD9"), m.px(12f))
            compoundDrawablePadding = m.pi(5f)
            setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.VIDEO_CUR), 14f * m.z, 14f * m.z, if (busy) Ui.c("#6B6478") else Color.WHITE), null, null, null)
            setPadding(m.pi(8f), 0, m.pi(8f), 0)
            isEnabled = !busy
            contentDescription = "Video call $name"
            setOnClickListener { open() }
        }
        card.addView(btn, FrameLayout.LayoutParams(-1, m.pi(36f), Gravity.BOTTOM).apply { leftMargin = m.pi(8f); rightMargin = m.pi(8f); bottomMargin = m.pi(8f) })
        Ui.pressable(card, 0.98f)
        return card
    }

    /** Weekly Top Creators card (113 × 188): doodle top, 3D face with the live dot, name, hours this week, Video Call */
    /** the doodle top of a Weekly Top / Online card: its two top corners as round as the inside of the card's border */
    fun doodleTop(ctx: Context, m: Zm, bw: Int): View = View(ctx).apply {
        background = TileFill(Svg.of(HomeArt.DOODLE_TILE), 60f * m.z, "doodle", Color.WHITE)
        val r = (m.px(18f) - bw).coerceAtLeast(0f)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, view.width, view.height + r.toInt() + 1, r) }
        }
        clipToOutline = true
    }

    fun creatorCard(ctx: Context, m: Zm, p: JSONObject, face: String, selected: Boolean, call: () -> Unit): View {
        val mins = HomeRules.weekMins(p); val age = HomeRules.ageOf(p); val free = HomeRules.isFree(p); val busy = HomeRules.isBusy(p)
        val title = "${HomeRules.firstName(p).ifEmpty { "Creator" }}${if (age != null) ", $age" else ""}"
        val hours = if (mins >= 60) "${HomeRules.jsRound(mins / 60)} h this week" else if (mins > 0) "${HomeRules.jsRound(mins)} min this week" else if (busy) "On a call" else ""
        val dot = if (busy) Ui.c("#EF4444") else Ui.c("#22C55E")
        val card = FrameLayout(ctx).apply {
            background = Tf.boxPx(Color.WHITE, m.px(18f))
            // the border is drawn over the card's insides: the doodle top used to cover it at the two top
            // corners (owner, 20 Nov: "white at the corners" on the chosen card)
            foreground = Tf.boxPx(Color.TRANSPARENT, m.px(18f), m.px(1.5f), if (selected) Ui.c("#6C2BD9") else Ui.c("#EEE9F5"))
            clipToOutline = true
            contentDescription = "Open $title, ${HomeRules.cityOf(p).ifEmpty { "India" }}, ${if (free) "online" else if (busy) "on a call" else "offline"}${if (hours.isNotEmpty()) ", $hours" else ""}"
            setOnClickListener { call() }
        }
        // the doodles sit inside the card's border (like the web's absolute span in the padding box), their top corners
        // as round as the border's inside
        val bw = Math.round(m.px(1.5f))
        card.addView(doodleTop(ctx, m, bw), FrameLayout.LayoutParams(-1, m.pi(116f) - bw).apply { leftMargin = bw; topMargin = bw; rightMargin = bw })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        val av = FrameLayout(ctx).apply { clipChildren = false }
        val ring = FrameLayout(ctx).apply { background = Tf.circle(Color.WHITE).apply { setStroke(m.pi(3f), Ui.c("#E4DAFA")) }; setPadding(m.pi(3f), m.pi(3f), m.pi(3f), m.pi(3f)) }
        ring.addView(CoverPic(ctx, face, round = true), FrameLayout.LayoutParams(-1, -1))
        av.addView(ring, FrameLayout.LayoutParams(-1, -1))
        if (free) av.addView(PingDot(ctx, Ui.c("#22C55E"), m.px(9f), 1600, 2.6f, .6f, .8f, ringOnly = true),
            FrameLayout.LayoutParams(m.pi(26f), m.pi(26f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = m.pi(3f) - m.pi(8.5f); bottomMargin = m.pi(3f) - m.pi(8.5f) })
        av.addView(View(ctx).apply { background = Tf.circle(dot).apply { setStroke(m.pi(2.5f), Color.WHITE) } },
            FrameLayout.LayoutParams(m.pi(13f), m.pi(13f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = m.pi(1f); bottomMargin = m.pi(1f) })
        col.addView(av, LinearLayout.LayoutParams(m.pi(64f), m.pi(64f)).apply { topMargin = m.pi(24f) })
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(Tf.tpx(ctx, title, m.px(14f), Ui.c("#1F1235")).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = m.pi(101f) },
            LinearLayout.LayoutParams(-2, -2))
        if (hours.isNotEmpty()) col.addView(Tf.tpx(ctx, hours, m.px(11f), Ui.c("#5B21B6")).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = m.pi(2f) })
        col.addView(View(ctx), LinearLayout.LayoutParams(1, m.pi(52f)))
        card.addView(col, FrameLayout.LayoutParams(-1, -1))
        val btn = Tf.tpx(ctx, "Video Call", m.px(13f), Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Tf.boxPx(Ui.c("#6C2BD9"), m.px(12f))
            compoundDrawablePadding = m.pi(5f)
            setCompoundDrawablesWithIntrinsicBounds(SvgDrawable(Svg.of(HomeArt.VIDEO_WHITE14), 14f * m.z), null, null, null)
            setPadding(m.pi(8f), 0, m.pi(8f), 0)
            contentDescription = "Video call $title"
            setOnClickListener { call() }
        }
        card.addView(btn, FrameLayout.LayoutParams(-1, m.pi(36f), Gravity.BOTTOM).apply { leftMargin = m.pi(8f); rightMargin = m.pi(8f); bottomMargin = m.pi(8f) })
        return card
    }

    @Suppress("unused") private fun keep(t: TextView) = t
}
