package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.truefind.android.rupees
import org.json.JSONObject

/** Native Games page — port of Games.jsx (header, Top Games card, the four games, coming soon). */
@SuppressLint("ViewConstructor")
class GamesHub(ctx: Context, layer: GamesLayer) : GameScreen(ctx, layer) {

    private object TG {
        val ink = G.c("#1f0a3d"); val dark = G.c("#120a24"); val purple = G.c("#5c12b8"); val vivid = G.c("#6411c9")
        val sub = G.c("#6b6380"); val soft = G.c("#857c99")
    }
    private val btnGrad = listOf<CssBg.Layer>(CssBg.Linear(180f, intArrayOf(G.c("#7a0ad7"), G.c("#5806a3"))))

    private class Live(val key: String, val title: String, val sub: String, val tags: List<Pair<String, String>>, val art: String, val shadow: List<Svg.Shadow>, val bgFrom: String, val bgTo: String)

    private val live = listOf(
        Live("heart-chase", "Heart Chase Boxes", "4 secret boxes, dhoondo pehle", listOf("Users" to "1 vs 1", "Timer" to "15 sec turns"), HubArt.heart, HubArt.heartShadow, "#fff8fc", "#fbe9f6"),
        Live("mood-changer", "Mood Changer", "Mood share karo, baat karo", listOf("Users" to "1 vs 1", "Mic" to "Voice call"), HubArt.mood, HubArt.moodShadow, "#fbf8ff", "#efe7fe"),
        Live("clue-me", "ClueMe", "Clue do, naam bujhao", listOf("Users" to "1 vs 1", "Timer" to "${NameGuess.ROUND_SECONDS} sec rounds"), HubArt.clue, HubArt.clueShadow, "#fbf8ff", "#f3ebfe"),
        Live("dress-code", "Dress Code", "Style battle", listOf("Users" to "1 vs 1", "Shirt" to DressData.designsLabel), HubArt.dress, HubArt.dressShadow, "#fdf9fe", "#f7ebfc"),
    )

    private val coinsText: TextView
    private val walletBtn: LinearLayout
    private val toast: Toast
    private var sheet: Sheet? = null
    private var shown = false

    init {
        setBackgroundColor(Color.WHITE)
        // soft lavender light at the top, like Home
        val glow = View(ctx).apply {
            background = CssBg(0f, listOf(
                CssBg.Radial(0f, 0f, 0.8f, 0.7f, intArrayOf(G.c("#f7f1ff"), G.ca("#f7f1ff", 0f)), floatArrayOf(0f, 0.7f)),
                CssBg.Radial(1f, 0f, 1.2f, 0.9f, intArrayOf(G.c("#efe4fe"), G.ca("#efe4fe", 0f)), floatArrayOf(0f, 0.62f)),
            ))
        }
        addView(glow, FrameLayout.LayoutParams(G.MATCH, G.dpi(280f)))

        val scroll = scrollPage(ctx)
        val col = G.vbox(ctx)
        col.pad(0f, 0f, 0f, 34f)
        scroll.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        addView(scroll, FrameLayout.LayoutParams(G.MATCH, G.MATCH))

        // ── header: back · True Find · wallet ──
        val header = FrameLayout(ctx)
        header.pad(14f, 10f, 14f, 0f)
        val back = FrameLayout(ctx).apply {
            background = G.oval(Color.WHITE, G.c("#e9dff8"), 1.5f)
            G.shadow(this, 3f, G.ca("#5b21b6", 0.5f))
            contentDescription = "Back"
            addView(G.icon(ctx, "ChevronLeft", TG.vivid, 23f, 2.4f), FrameLayout.LayoutParams(G.dpi(23f), G.dpi(23f), Gravity.CENTER))
        }
        G.click(back, 0.97f) { layer.back() }
        header.addView(back, FrameLayout.LayoutParams(G.dpi(42f), G.dpi(42f), Gravity.START or Gravity.CENTER_VERTICAL))

        val logo = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        logo.addView(SvgView(ctx, HubArt.logo, 34.6f, 21.2f), LinearLayout.LayoutParams(G.dpi(31f), G.dpi(19f)))
        val brand = SpannableStringBuilder("True Find").apply {
            setSpan(ForegroundColorSpan(G.c("#1d0838")), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(TG.purple), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logo.addView(G.text(ctx, brand, 23f, TG.ink), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        header.addView(logo, FrameLayout.LayoutParams(G.WRAP, G.WRAP, Gravity.CENTER))

        walletBtn = G.hbox(ctx, 7f, Gravity.CENTER).apply {
            background = CssBg(12f, listOf(CssBg.Linear(180f, intArrayOf(G.c("#faf7ff"), G.c("#f4effc")))), border = G.c("#efe8fa"), borderDp = 1.5f)
            minimumWidth = G.dpi(82f)
            pad(11f, 0f, 8f, 0f)
            contentDescription = "Wallet"
        }
        walletBtn.addView(G.coin(ctx, 17f))
        coinsText = G.text(ctx, "–", 15f, G.c("#5b4a7a"), bold = true)
        walletBtn.addView(coinsText)
        walletBtn.addView(G.icon(ctx, "ChevronRight", G.c("#5b4a7a"), 12f, 2.6f))
        G.click(walletBtn, 0.97f) { layer.openWeb("/wallet") }
        header.addView(walletBtn, FrameLayout.LayoutParams(G.WRAP, G.dpi(36f), Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(header, G.lp(G.MATCH, G.WRAP))

        // ── premium card: Top Games ──
        col.addView(topCard(ctx), G.lp(G.MATCH, G.WRAP).m(14f, 16f, 14f, 0f))

        // ── Play now ──
        col.addView(sectionTitle(ctx, SvgView(ctx, HubArt.miniPad(), 30f, 20f), 25f, 17f, "Play now"), G.lp(G.MATCH, G.WRAP).m(18f, 24f, 18f, 0f))
        val cards = G.vbox(ctx, 12f)
        live.forEachIndexed { i, g -> cards.addView(gameCard(ctx, g, i), G.lp(G.MATCH, G.WRAP)) }
        col.addView(cards, G.lp(G.MATCH, G.WRAP).m(14f, 12f, 14f, 0f))

        // ── More games coming soon ──
        col.addView(sectionTitle(ctx, G.icon(ctx, "Hourglass", TG.vivid, 19f, 2.3f), 19f, 19f, "More games coming soon"), G.lp(G.MATCH, G.WRAP).m(18f, 24f, 18f, 0f))
        val soon = G.hbox(ctx, 10f, Gravity.TOP)
        listOf(Triple("Crush", "Sweet first-call questions", HubArt.soonCrush to (34f to 30f)),
            Triple("Flirty", "Playful, never pushy", HubArt.soonFlirty to (26f to 32f)),
            Triple("Deep", "Real talk, real you", HubArt.soonDeep to (32f to 32f))).forEachIndexed { i, (t, s, art) ->
            soon.addView(soonTile(ctx, t, s, art.first, art.second.first, art.second.second, i), G.lpw(1f))
        }
        col.addView(soon, G.lp(G.MATCH, G.WRAP).m(14f, 12f, 14f, 0f))

        toast = Toast(this, G.round(G.ca("#1e0a3c", 0.92f), 99f), Color.WHITE, 14.5f, false, 24f).padding(18f, 11f, 18f, 11f)

        refreshWallet()
    }

    fun onShown(v: Boolean) {
        if (v && !shown) refreshWallet()
        shown = v
    }

    /** Wallet balance in the header (same wallet as Home). */
    fun refreshWallet() {
        GameApi.call("getWallet", JSONObject().put("app", "truefind")) { r ->
            if (!r.ok) return@call
            val w = r.data.obj("wallet") ?: return@call
            if (w.hasNum("coins")) {
                val v = w.long("coins")
                coinsText.text = rupees(v)
                walletBtn.contentDescription = "Wallet, ${rupees(v)} coins"
            }
        }
    }

    override fun onBack(): Boolean {
        sheet?.let { if (it.isOpen) { it.close(); return true } }
        return false
    }

    // ───────── parts ─────────
    private fun sectionTitle(ctx: Context, icon: View, w: Float, h: Float, label: String): View {
        val row = G.hbox(ctx, 9f)
        row.addView(icon, LinearLayout.LayoutParams(G.dpi(w), G.dpi(h)))
        row.addView(G.text(ctx, label, 18f, TG.dark))
        return row
    }

    private fun topCard(ctx: Context): View {
        val card = PctFrame(ctx)
        card.background = CssBg(24f, listOf(
            CssBg.Linear(162f, intArrayOf(G.c("#9d22d2"), G.c("#7c10c6"), G.c("#5e06aa"), G.c("#45048c"), G.c("#33066f")), floatArrayOf(0f, 0.24f, 0.5f, 0.74f, 1f)),
            CssBg.Radial(0.84f, 0.26f, 0f, 0f, intArrayOf(G.ca("#de6eff", 0.45f), G.ca("#de6eff", 0f)), floatArrayOf(0f, 0.46f), circle = true),
        ), border = G.ca("#ffffff", 0.12f), borderDp = 1.5f)
        G.shadow(card, 14f, G.c("#4c0896"), 24f)
        card.clipChildren = true
        card.clipToOutline = true
        card.pad(18f, 18f, 18f, 16f)
        rise(card, 0)

        // twinkling dots
        listOf(Triple(0.58f, 0.12f, 4f), Triple(0.92f, 0.10f, 5f), Triple(0.96f, 0.60f, 4f), Triple(0.66f, 0.64f, 6f), Triple(0.40f, 0.08f, 3f)).forEachIndexed { n, (l, t, s) ->
            val dot = View(ctx).apply { background = G.oval(Color.WHITE); G.shadow(this, 2f, Color.WHITE) }
            card.place(dot, PctFrame.Spec(left = l, top = t), FrameLayout.LayoutParams(G.dpi(s), G.dpi(s)))
            twinkle(dot, n * 600L)
        }
        // the floating game pad
        val pad = SvgView(ctx, HubArt.pad, 44f, 36f).apply { shadows = HubArt.padShadow }
        card.place(pad, PctFrame.Spec(widthFrac = 0.43f, maxWdp = 186f, rightDp = 6f, topDp = 12f), FrameLayout.LayoutParams(G.dpi(100f), G.WRAP))
        float(pad)

        val col = G.vbox(ctx)
        // GAME ZONE pill
        val pill = G.hbox(ctx, 7f).apply {
            background = CssBg(99f, listOf(CssBg.Linear(180f, intArrayOf(G.ca("#ffffff", 0.18f), G.ca("#ffffff", 0.06f)))), border = G.ca("#ffc4ec", 0.7f), borderDp = 1.5f)
            pad(3f, 0f, 12f, 0f)
        }
        val red = FrameLayout(ctx).apply {
            background = CssBg.ovalOf(listOf(CssBg.Linear(180f, intArrayOf(G.c("#f43f5e"), G.c("#be123c")))))
            addView(SvgView(ctx, HubArt.miniPad("#fff", "#be123c"), 30f, 20f), FrameLayout.LayoutParams(G.dpi(12f), G.dpi(8f), Gravity.CENTER))
        }
        pill.addView(red, LinearLayout.LayoutParams(G.dpi(19f), G.dpi(19f)))
        pill.addView(G.text(ctx, "GAME ZONE", 10.5f, Color.WHITE).apply { letterSpacing = 0.16f })
        col.addView(pill, LinearLayout.LayoutParams(G.WRAP, G.dpi(26f)))

        val title = G.text(ctx, "Top Games", 31f, Color.WHITE, bold = true, italic = true).apply {
            letterSpacing = -0.01f
            setPadding(0, 0, G.dpi(4f), G.dpi(2f))
            setShadowLayer(G.dp(10f), 0f, 0f, G.ca("#f0abfc", 0.35f))
        }
        title.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val h = (bottom - top).toFloat()
            (v as TextView).paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.WHITE, Color.WHITE, G.c("#f0d9ff")), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        }
        col.addView(title, G.lp(G.WRAP, G.WRAP).m(t = 11f))
        col.addView(G.text(ctx, "Play together on call", 14.5f, G.c("#efe4ff"), bold = false).lineH(1.35f), G.lp(G.WRAP, G.WRAP).m(t = 6f))
        card.place(col, PctFrame.Spec(widthFrac = 0.58f), FrameLayout.LayoutParams(G.dpi(100f), G.WRAP))

        // three feature chips (below the title block)
        val chips = G.hbox(ctx, 8f)
        listOf("Mic" to "On call", "Timer" to "Quick rounds", "Trophy" to "Score & win").forEach { (ic, t) ->
            val chip = G.vbox(ctx, 6f, Gravity.CENTER).apply {
                background = G.round(G.ca("#ffffff", 0.075f), 13f, G.ca("#ffffff", 0.07f), 1f)
                pad(4f, 0f, 4f, 0f)
            }
            chip.addView(G.icon(ctx, ic, G.c("#ffd877"), 17f, 2.2f))
            chip.addView(G.text(ctx, t, 12.5f, Color.WHITE).ellipsize1())
            chips.addView(chip, G.lpw(1f, G.dpi(56f)))
        }
        // chips sit under the text: an invisible spacer column holds the height
        val holder = G.vbox(ctx)
        val spacer = View(ctx)
        holder.addView(spacer, G.lp(G.MATCH, 0))
        holder.addView(chips, G.lp(G.MATCH, G.WRAP).m(t = 16f))
        card.addView(holder, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        col.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            val lp = spacer.layoutParams
            if (lp.height != h) { lp.height = h; spacer.post { spacer.layoutParams = lp } }
        }
        return card
    }

    private fun gameCard(ctx: Context, g: Live, i: Int): View {
        val card = PctFrame(ctx)
        card.minimumHeight = G.dpi(152f)
        card.background = CssBg(22f, listOf(CssBg.Linear(100f, intArrayOf(G.c(g.bgFrom), G.c(g.bgTo)))), border = Color.WHITE, borderDp = 3f)
        card.foreground = CssBg(19f, emptyList(), border = G.c("#ece4fa"), borderDp = 1.5f).let { inset(it, 3f) }
        G.shadow(card, 6f, G.c("#5b21b6"), 22f)
        card.clipToOutline = true
        card.contentDescription = "Play ${g.title}. ${g.sub}. ${g.tags.joinToString(", ") { it.second }}"

        val art = SvgView(ctx, g.art, 150f, 130f).apply { shadows = g.shadow }
        card.place(art, PctFrame.Spec(widthFrac = 0.44f, maxWdp = 172f, rightDp = -4f, vCenter = true), FrameLayout.LayoutParams(G.dpi(100f), G.WRAP))

        val col = G.vbox(ctx)
        col.pad(18f, 16f, 0f, 16f)
        col.addView(G.text(ctx, g.title, 22f, TG.ink).lineH(1.1f))
        col.addView(G.text(ctx, g.sub, 13.5f, TG.sub, bold = false).lineH(1.3f), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        val tags = G.hbox(ctx, 6f)
        for ((ic, t) in g.tags) {
            val tag = G.hbox(ctx, 5f).apply {
                background = G.round(Color.WHITE, 99f, G.c("#e6dbf7"), 1f)
                pad(7f, 0f, 9f, 0f)
            }
            tag.addView(G.icon(ctx, ic, TG.vivid, 13f, 2.3f))
            tag.addView(G.text(ctx, t, 12f, G.c("#4a1a8c")).ellipsize1())
            tags.addView(tag, LinearLayout.LayoutParams(G.WRAP, G.dpi(24f)))
        }
        col.addView(tags, G.lp(G.WRAP, G.WRAP).m(t = 10f))
        // Play now
        val play = FrameLayout(ctx).apply {
            background = CssBg(99f, btnGrad)
            G.shadow(this, 6f, G.c("#5a06aa"), 99f)
            clipToOutline = true
        }
        val playRow = G.hbox(ctx, 8f)
        playRow.pad(8f, 0f, 18f, 0f)
        val dot = FrameLayout(ctx).apply { background = G.oval(G.ca("#ffffff", 0.2f)) }
        dot.addView(G.icon(ctx, "Play", Color.WHITE, 11f, 0f, Color.WHITE), FrameLayout.LayoutParams(G.dpi(11f), G.dpi(11f), Gravity.CENTER).m(l = 1.5f))
        playRow.addView(dot, LinearLayout.LayoutParams(G.dpi(24f), G.dpi(24f)))
        playRow.addView(G.text(ctx, "Play now", 14.5f, Color.WHITE))
        play.addView(playRow, FrameLayout.LayoutParams(G.WRAP, G.MATCH))
        play.addView(ShineView(ctx, (i * 1300).toLong()), FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        col.addView(play, LinearLayout.LayoutParams(G.WRAP, G.dpi(38f)).m(t = 14f))
        card.place(col, PctFrame.Spec(widthFrac = 0.6f), FrameLayout.LayoutParams(G.dpi(100f), G.WRAP))

        G.click(card, 0.97f) { openGame(g.key) }
        rise(card, 120L + i * 80L, 12f, 450)
        return card
    }

    private fun soonTile(ctx: Context, title: String, sub: String, art: String, vbW: Float, vbH: Float, i: Int): View {
        val tile = FrameLayout(ctx)
        tile.background = CssBg(18f, listOf(CssBg.Linear(165f, intArrayOf(G.c("#f8f5fe"), G.c("#f1ecfc")))), border = Color.WHITE, borderDp = 3f)
        tile.foreground = inset(CssBg(15f, emptyList(), border = G.c("#ede6fa"), borderDp = 1.5f), 3f)
        G.shadow(tile, 4f, G.c("#5b21b6"), 18f)
        tile.contentDescription = "$title, coming soon. $sub"
        val col = G.vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL)
        col.pad(8f, 18f, 8f, 13f)
        val h = if (vbH >= 32f) 32f else 31f
        col.addView(FrameLayout(ctx).apply {
            addView(SvgView(ctx, art, vbW, vbH), FrameLayout.LayoutParams((G.dp(h) * vbW / vbH).toInt(), G.dpi(h), Gravity.CENTER))
        }, G.lp(G.MATCH, G.dpi(34f)).m(t = 6f))
        col.addView(G.text(ctx, title, 15f, TG.ink).apply { gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 8f))
        col.addView(G.text(ctx, sub, 11.5f, TG.soft, bold = false).lineH(1.3f).apply { gravity = Gravity.CENTER }, G.lp(G.WRAP, G.WRAP).m(t = 4f))
        tile.addView(col, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        val badge = G.hbox(ctx, 3f)
        badge.addView(G.icon(ctx, "Lock", G.c("#a996cc"), 9f, 2.8f))
        badge.addView(G.text(ctx, "SOON", 9.5f, G.c("#a996cc")).apply { letterSpacing = 0.06f })
        tile.addView(badge, FrameLayout.LayoutParams(G.WRAP, G.WRAP, Gravity.TOP or Gravity.END).m(t = 7f, r = 8f))
        G.click(tile, 0.97f) { toast.show("$title is coming soon!", 2400) }
        rise(tile, 300L + i * 60L, 12f, 450)
        return tile
    }

    fun showClueSheet() = openClueSheet()

    private fun openGame(key: String) {
        when (key) {
            "clue-me" -> openClueSheet()
            else -> layer.open(key)
        }
    }

    // ───────── ClueMe sheet: example, rules, one phone / two phones ─────────
    private fun openClueSheet() {
        if (sheet?.isOpen == true) return
        val ctx = context
        val s = Sheet(this, CssBg(floatArrayOf(26f, 26f, 0f, 0f), emptyList(), fill = Color.WHITE), G.ca("#180834", 0.45f), 18f, 10f, 18f, 20f, 0f)
        sheet = s
        val p = s.panel
        p.addView(View(ctx).apply { background = G.round(G.c("#ddd2ee"), 2f) }, LinearLayout.LayoutParams(G.dpi(40f), G.dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = G.dpi(14f) })
        val head = FrameLayout(ctx)
        val row = G.hbox(ctx, 14f)
        row.pad(0f, 0f, 40f, 0f)
        val box = FrameLayout(ctx).apply {
            background = CssBg(18f, listOf(CssBg.Linear(160f, intArrayOf(G.c("#9a3cf3"), G.c("#6411c9"), G.c("#4c0fa8")), floatArrayOf(0f, 0.55f, 1f))))
            G.shadow(this, 6f, G.c("#5a06aa"), 18f)
            addView(ImageView(ctx).apply { setImageDrawable(SvgDrawable(HubArt.clueIcon, 34f)) }, FrameLayout.LayoutParams(G.dpi(34f), G.dpi(34f), Gravity.CENTER))
        }
        row.addView(box, LinearLayout.LayoutParams(G.dpi(62f), G.dpi(62f)))
        val tcol = G.vbox(ctx)
        tcol.addView(G.text(ctx, "ClueMe", 25f, TG.ink).lineH(1.1f))
        tcol.addView(G.text(ctx, "Clue do, naam bujhao · 1 vs 1", 14.5f, TG.sub, bold = false).lineH(1.3f), G.lp(G.WRAP, G.WRAP).m(t = 4f))
        row.addView(tcol, G.lpw(1f))
        head.addView(row, FrameLayout.LayoutParams(G.MATCH, G.WRAP))
        val close = FrameLayout(ctx).apply {
            background = G.oval(G.c("#f4effc"))
            contentDescription = "Close"
            addView(G.icon(ctx, "X", G.c("#5b4a7a"), 18f, 2.2f), FrameLayout.LayoutParams(G.dpi(18f), G.dpi(18f), Gravity.CENTER))
        }
        G.click(close, 0.97f) { s.close() }
        head.addView(close, FrameLayout.LayoutParams(G.dpi(34f), G.dpi(34f), Gravity.END or Gravity.TOP).m(t = -16f, r = -6f))
        p.addView(head, G.lp(G.MATCH, G.WRAP))

        val ex = G.vbox(ctx).apply {
            background = CssBg(16f, listOf(CssBg.Linear(180f, intArrayOf(G.c("#faf7ff"), G.c("#f4eefd")))), border = G.c("#ece4fa"), borderDp = 1.5f)
            pad(15f, 13f, 15f, 13f)
        }
        ex.addView(G.text(ctx, "EXAMPLE", 11.5f, TG.vivid).apply { letterSpacing = 0.1f })
        val exText = SpannableStringBuilder()
        exText.append("पंखा · Fan"); exText.setSpan(ForegroundColorSpan(TG.ink), 0, exText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        exText.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, exText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        exText.append(" ")
        val a = exText.length; exText.append("→"); exText.setSpan(ForegroundColorSpan(TG.vivid), a, exText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        exText.append(" Chhat par lagta hai, ghoomta rehta hai, garmi me sabse zaroori.")
        ex.addView(G.text(ctx, exText, 15f, G.c("#4a4060"), bold = false).lineH(1.45f), G.lp(G.MATCH, G.WRAP).m(t = 5f))
        p.addView(ex, G.lp(G.MATCH, G.WRAP).m(t = 16f))

        p.addView(G.text(ctx, "Rules", 16.5f, TG.dark), G.lp(G.WRAP, G.WRAP).m(t = 18f, b = 10f))
        val rules = G.vbox(ctx, 10f)
        NameGuess.RULES.forEachIndexed { n, r ->
            val li = G.hbox(ctx, 10f, Gravity.TOP)
            li.addView(G.text(ctx, "${n + 1}", 12.5f, TG.purple).apply { gravity = Gravity.CENTER; background = G.oval(G.c("#efe5fc")) }, LinearLayout.LayoutParams(G.dpi(22f), G.dpi(22f)))
            li.addView(G.text(ctx, r, 14.5f, G.c("#4a4060"), bold = false).lineH(1.45f), G.lpw(1f))
            rules.addView(li, G.lp(G.MATCH, G.WRAP))
        }
        p.addView(rules, G.lp(G.MATCH, G.WRAP))

        val one = FrameLayout(ctx).apply {
            background = CssBg(16f, btnGrad)
            G.shadow(this, 8f, G.c("#5a06aa"), 16f)
            clipToOutline = true
            val r1 = G.hbox(ctx, 9f, Gravity.CENTER)
            r1.addView(G.icon(ctx, "Smartphone", Color.WHITE, 19f, 2.2f))
            r1.addView(G.text(ctx, "Play on one phone", 16f, Color.WHITE))
            addView(r1, FrameLayout.LayoutParams(G.MATCH, G.MATCH))
            addView(ShineView(ctx, 0L), FrameLayout.LayoutParams(G.MATCH, G.MATCH))
        }
        G.click(one, 0.97f) { s.close(); layer.open("clue-local") }
        p.addView(one, G.lp(G.MATCH, G.dpi(52f)).m(t = 20f))
        val two = G.hbox(ctx, 9f, Gravity.CENTER).apply { background = G.round(Color.WHITE, 16f, G.c("#e3d8f5"), 1.5f) }
        two.addView(G.icon(ctx, "Users", TG.vivid, 18f, 2.2f))
        two.addView(G.text(ctx, "Play on 2 phones (code)", 15f, G.c("#3d1a78")))
        G.click(two, 0.97f) { s.close(); layer.open("clue-online") }
        p.addView(two, G.lp(G.MATCH, G.dpi(48f)).m(t = 10f))
    }

    // ───────── animations ─────────
    private fun rise(v: View, delay: Long, dy: Float = 12f, ms: Long = 450) = G.rise(v, delay, dy, ms)

    private fun twinkle(v: View, delay: Long) {
        v.alpha = 0.2f; v.scaleX = 0.6f; v.scaleY = 0.6f
        anims.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); startDelay = delay
            addUpdateListener {
                val t = it.animatedValue as Float
                val k = G.ease(if (t < 0.5f) t * 2f else (1f - t) * 2f)
                v.alpha = 0.2f + 0.8f * k
                val s = 0.6f + 0.4f * k
                v.scaleX = s; v.scaleY = s
            }
            start()
        })
    }

    private fun float(v: View) {
        v.rotation = -9f
        anims.add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val k = G.ease(if (t < 0.5f) t * 2f else (1f - t) * 2f)
                v.translationY = -G.dp(6f) * k
                v.rotation = -9f + 3f * k
            }
            start()
        })
    }

    private fun inset(d: android.graphics.drawable.Drawable, dp: Float): android.graphics.drawable.Drawable {
        val px = G.dpi(dp)
        return android.graphics.drawable.InsetDrawable(d, px, px, px, px)
    }
}
