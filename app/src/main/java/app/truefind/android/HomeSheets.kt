package app.truefind.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** The pop-ups of Home.jsx: Random Match confirm, a person's sheet, all creators, dialogs, the matching screen. */
class HomeSheets(private val home: HomeScreen, private val pages: NativePages) {
    private val ctx: Context get() = home.context
    private val C_INK = Ui.c("#1f0a3d"); private val C_SUB = Ui.c("#6a6280"); private val C_SOFT = Ui.c("#8e86a3"); private val C_DARK = Ui.c("#120a24")
    private fun btnBg(r: Float) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#7a0ad7"), Ui.c("#5806a3"))).apply { cornerRadius = Ui.dp(r) }

    private var closeConfirmFn: (() -> Unit)? = null
    private var closePersonFn: (() -> Unit)? = null
    private var closeAllFn: (() -> Unit)? = null
    private var closeDialogFn: (() -> Unit)? = null
    private var personView: LinearLayout? = null
    private var personP: JSONObject? = null
    private var stage: MatchStage? = null
    val matching: Boolean get() = stage != null

    fun closeAll() {
        closeDialogFn?.invoke(); closePersonFn?.invoke(); closeAllFn?.invoke(); closeConfirmFn?.invoke()
        if (stage?.stageName == "search" || stage?.stageName == "random") home.cancelRandom()
        else if (stage?.stageName == "direct") home.cancelCall()
    }
    fun closeConfirm() { closeConfirmFn?.invoke() }
    fun closePersonAndAll() { closePersonFn?.invoke(); closeAllFn?.invoke() }

    /** Back: the matching screen (search: cancel; a direct call being made: cancel; connecting: wait), else the layer closes its top pop-up */
    fun onBack(): Boolean {
        val s = stage ?: return false
        if (s.stageName == "search" || s.stageName == "random") home.cancelRandom()
        else if (s.stageName == "direct") home.cancelCall()
        return true
    }

    /** The web's Sheet: white panel, handle, round X at the top right. */
    private fun sheet(label: String, content: View, onClose: () -> Unit = {}): () -> Unit {
        val panel = FrameLayout(ctx).apply { background = Tf.topRound(Color.WHITE, 24f); contentDescription = label }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(18f), Ui.dpi(10f), Ui.dpi(18f), Ui.dpi(22f)) }
        col.addView(View(ctx).apply { background = Tf.box(Ui.c("#ddd2ee"), 2f) }, Tf.ll(Ui.dpi(40f), Ui.dpi(4f), b = 12f, g = Gravity.CENTER_HORIZONTAL))
        col.addView(content, Tf.ll(Tf.M, Tf.W))
        panel.addView(col, FrameLayout.LayoutParams(-1, -2))
        var close: () -> Unit = {}
        val x = FrameLayout(ctx).apply {
            background = Tf.circle(Ui.c("#f4effc")); contentDescription = "Close"
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.X, Ui.c("#5b4a7a"), 18f, 2.2f)) }, FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
            setOnClickListener { close() }
        }
        panel.addView(x, FrameLayout.LayoutParams(Ui.dpi(34f), Ui.dpi(34f), Gravity.END).apply { topMargin = Ui.dpi(10f); rightMargin = Ui.dpi(12f) })
        close = pages.layer.sheet(panel, Color.argb(115, 24, 8, 52), 0.88f, onClose = onClose)
        return close
    }

    // ═════════════════ Random Match: confirm the price first ═════════════════
    fun confirm(type: String) {
        closeConfirmFn?.invoke()
        val video = type == "video"
        val rate = home.rateOf(type)
        val coins = home.coins
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = Tf.row(ctx)
        val ic = FrameLayout(ctx).apply { background = btnBg(16f); elevation = Ui.dp(4f); outlineSpotShadowColorCompat(Ui.c("#5a06aa")) }
        ic.addView(ImageView(ctx).apply {
            setImageDrawable(if (video) Tf.lu(Lu.Video, Color.WHITE, 26f, 1.6f, "#FFFFFF") else Tf.lu(Lu.Mic, Color.WHITE, 26f, 2.3f))
        }, FrameLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER))
        top.addView(ic, Tf.ll(Ui.dpi(54f), Ui.dpi(54f), r = 14f))
        val tt = Tf.col(ctx)
        tt.addView(Tf.t(ctx, "Random ${if (video) "Video" else "Voice"} Call", 20f, C_INK))
        tt.addView(Tf.t(ctx, "We'll connect you with someone who is online and free right now.", 14f, C_SUB, false).apply { setLineSpacing(Ui.dp(14f) * 0.4f - 0f, 1f) }, Tf.ll(t = 3f))
        top.addView(tt, Tf.ll(0, Tf.W, 1f))
        c.addView(top)
        fun line(k: String, v: String, t: Float) = Tf.row(ctx).apply {
            background = Tf.box(Ui.c("#f8f4ff"), 16f, 1f, Ui.c("#ece4fa")); setPadding(Ui.dpi(14f), Ui.dpi(12f), Ui.dpi(14f), Ui.dpi(12f))
            addView(Tf.t(ctx, k, 14.5f, C_SUB, false), Tf.ll(0, Tf.W, 1f))
            addView(Tf.t(ctx, v, 16f, C_INK))
        }.also { c.addView(it, Tf.ll(Tf.M, Tf.W, t = t)) }
        line("Price", "$rate coins per minute", 16f)
        line("Your balance", if (coins == null) "…" else "${Tf.inr(coins)} coins", 8f)
        c.addView(Tf.t(ctx, "Billed per minute only while the call is connected (minimum 1 minute). You can end the call anytime.", 12.5f, C_SOFT, false).apply { setLineSpacing(Ui.dp(12.5f) * 0.45f, 1f) },
            Tf.ll(Tf.M, Tf.W, l = 2f, t = 10f, r = 2f))
        val btns = Tf.row(ctx)
        if (coins != null && coins < rate) {
            btns.addView(Tf.t(ctx, "Add coins", 15.5f, Ui.c("#5b12b8")).apply {
                gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 16f, 1.5f, Ui.c("#d9c6f7"))
                setOnClickListener { closeConfirmFn?.invoke(); pages.go("/wallet") }
            }.also { Ui.pressable(it, 0.97f) }, Tf.ll(0, Ui.dpi(50f), 1f, r = 10f))
        }
        btns.addView(Tf.t(ctx, "Find someone", 15.5f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = btnBg(16f); elevation = Ui.dp(3f); outlineSpotShadowColorCompat(Ui.c("#5a06aa"))
            setOnClickListener { home.startRandom(type) }
        }.also { Ui.pressable(it, 0.97f) }, Tf.ll(0, Ui.dpi(50f), 1.4f))
        c.addView(btns, Tf.ll(Tf.M, Tf.W, t = 16f))
        closeConfirmFn = sheet("Random ${if (video) "Video" else "Voice"} Call", c) { closeConfirmFn = null }
    }

    // ═════════════════ Weekly Top Creators: everyone ═════════════════
    /**
     * Weekly Top Creators → See all: a page of its own (owner, 19 Nov evening) — the first three on top (1 in the
     * middle, 2 and 3 on its sides), "Your rank", then 4 to 30 (TopCreatorsPage).
     */
    fun allCreators() {
        closeAllFn?.invoke()
        var close: () -> Unit = {}
        val page = TopCreatorsPage(ctx, home, this) { close() }
        close = pages.layer.page(page, z = 12f) { closeAllFn = null }
        closeAllFn = close
    }

    // ═════════════════ a person: Voice or Video (price shown first) ═════════════════
    fun person(p: JSONObject) {
        closePersonFn?.invoke()
        personP = p
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        personView = c
        fillPerson(c, p)
        closePersonFn = sheet(HomeRules.fullName(p).ifEmpty { "Creator" }, c) { closePersonFn = null; personView = null; personP = null }
    }
    fun refreshPerson() { val c = personView ?: return; val p = personP ?: return; c.removeAllViews(); fillPerson(c, p) }

    private fun fillPerson(c: LinearLayout, p: JSONObject) {
        // never Busy (owner, 23 Nov evening): online is "Online", also on another call — a call then goes to someone else
        val online = HomeRules.isOnline(p); val age = HomeRules.ageOf(p); val city = HomeRules.cityOf(p)
        val calling = home.callingType
        val top = Tf.row(ctx).apply { setPadding(0, 0, Ui.dpi(30f), 0) }
        val ring = FrameLayout(ctx).apply { background = Tf.circle(if (online) Ui.c("#22c55e") else Ui.c("#e6dcf7")); setPadding(Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f)) }
        val inner = FrameLayout(ctx).apply { background = Tf.circle(Color.WHITE); setPadding(Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f)) }
        val full = HomeRules.photoOf(p)
        inner.addView(FacePic(ctx).apply { setPhoto(listOf(HomeRules.listPhoto(p, full), full)) }, FrameLayout.LayoutParams(-1, -1))
        ring.addView(inner, FrameLayout.LayoutParams(-1, -1))
        top.addView(ring, Tf.ll(Ui.dpi(78f), Ui.dpi(78f), r = 14f))
        val info = Tf.col(ctx)
        val nameRow = FlowRow(ctx, Ui.dpi(6f), Ui.dpi(4f))
        nameRow.addView(Tf.t(ctx, "${HomeRules.fullName(p).ifEmpty { "Creator" }}${if (age != null) ", $age" else ""}", 20f, C_INK))
        if (HomeRules.isVerified(p)) nameRow.addView(Tf.t(ctx, "Verified", 11.5f, Ui.c("#1d4ed8")).apply {
            background = Tf.box(Ui.c("#eff6ff"), 99f); setPadding(Ui.dpi(8f), Ui.dpi(2f), Ui.dpi(8f), Ui.dpi(2f))
            compoundDrawablePadding = Ui.dpi(3f); setCompoundDrawablesWithIntrinsicBounds(Tf.lu(Lu.BadgeCheck, Ui.c("#1d4ed8"), 13f, 2.4f), null, null, null)
            gravity = Gravity.CENTER_VERTICAL
        })
        info.addView(nameRow)
        val st = FlowRow(ctx, Ui.dpi(6f), Ui.dpi(2f))
        st.addView(View(ctx).apply { background = Tf.circle(if (online) Ui.c("#22c55e") else Ui.c("#c4b5d9")) }, ViewGroupLp(Ui.dpi(8f), Ui.dpi(8f)))
        st.addView(Tf.t(ctx, if (online) "Online" else "Offline", 13.5f, if (online) Ui.c("#15803d") else Ui.c("#8b7bb0")))
        if (city.isNotEmpty()) st.addView(Tf.t(ctx, "· ", 13.5f, C_SUB, false).apply {
            text = "· "; compoundDrawablePadding = Ui.dpi(3f)
        }.let { dot -> Tf.row(ctx).apply { addView(dot); addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.MapPin, C_SUB, 13f)) }, Tf.ll(Ui.dpi(13f), Ui.dpi(13f), r = 3f)); addView(Tf.t(ctx, city, 13.5f, C_SUB, false)) } })
        info.addView(st, Tf.ll(t = 4f))
        val wm = HomeRules.weekMins(p)
        if (wm > 0) info.addView(Tf.t(ctx, "📞 ${HomeRules.hoursText(wm)} talked this week", 13f, Ui.c("#5c12b8")), Tf.ll(t = 3f))
        top.addView(info, Tf.ll(0, Tf.W, 1f))
        c.addView(top)
        if (!online) c.addView(Tf.t(ctx, "Offline now. You can still try to call — they may not pick up.", 13f, Ui.c("#6b6380"), false).apply {
            background = Tf.box(Ui.c("#f6f3fb"), 12f); setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f))
        }, Tf.ll(Tf.M, Tf.W, t = 12f))
        val btns = Tf.row(ctx)
        for ((t, label) in listOf("voice" to "Voice Call", "video" to "Video Call")) {
            val voice = t == "voice"
            val b = Tf.col(ctx, Gravity.CENTER).apply {
                background = if (voice) Tf.box(Color.WHITE, 16f, 1.5f, Ui.c("#d9c6f7")) else btnBg(16f)
                if (!voice) { elevation = Ui.dp(3f); outlineSpotShadowColorCompat(Ui.c("#5a06aa")) }
                isEnabled = calling.isEmpty()
                setOnClickListener { home.callPerson(p, t) }
            }
            val fg = if (voice) Ui.c("#5b12b8") else Color.WHITE
            val r1 = Tf.row(ctx, Gravity.CENTER)
            if (calling == t) r1.addView(TfLoader(ctx, 17f, if (voice) Tf.BLUE else Color.WHITE), Tf.ll(Ui.dpi(17f), Ui.dpi(17f), r = 7f))
            else r1.addView(ImageView(ctx).apply { setImageDrawable(if (voice) Tf.lu(Lu.Mic, fg, 17f, 2.3f) else Tf.lu(Lu.Video, fg, 17f, 1.6f, "#FFFFFF")) }, Tf.ll(Ui.dpi(17f), Ui.dpi(17f), r = 7f))
            r1.addView(Tf.t(ctx, label, 15.5f, fg))
            b.addView(r1)
            b.addView(Tf.t(ctx, "${home.rateOf(t)} coins/min", 12f, fg, false).apply { alpha = 0.85f }, Tf.ll(t = 2f))
            Ui.pressable(b, 0.97f)
            btns.addView(b, Tf.ll(0, Ui.dpi(58f), 1f).apply { if (!voice) leftMargin = Ui.dpi(10f) })
        }
        c.addView(btns, Tf.ll(Tf.M, Tf.W, t = 16f))
        c.addView(Tf.t(ctx, "Your balance: ${home.coins?.let { "${Tf.inr(it)} coins" } ?: "…"} · Billed only while connected (min. 1 minute)", 12.5f, C_SOFT, false).apply { gravity = Gravity.CENTER },
            Tf.ll(Tf.M, Tf.W, l = 2f, t = 10f, r = 2f))
    }

    // ═════════════════ dialogs ═════════════════
    fun dialog(title: String, text: String, goLabel: String? = null, icon: View? = null, onGo: (() -> Unit)? = null) {
        closeDialogFn?.invoke()
        val card = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
            background = Tf.box(Color.WHITE, 22f); setPadding(Ui.dpi(20f), Ui.dpi(22f), Ui.dpi(20f), Ui.dpi(18f))
            elevation = Ui.dp(10f); outlineSpotShadowColorCompat(Ui.c("#1e083c"))
        }
        if (icon != null) card.addView(icon, Tf.ll(Ui.dpi(58f), Ui.dpi(58f), b = 12f))
        card.addView(Tf.t(ctx, title, 19f, C_INK).apply { gravity = Gravity.CENTER })
        if (text.isNotEmpty()) card.addView(Tf.t(ctx, text, 14.5f, C_SUB, false).apply { gravity = Gravity.CENTER; setLineSpacing(Ui.dp(14.5f) * 0.45f, 1f) }, Tf.ll(t = 8f))
        val row = Tf.row(ctx)
        var close: () -> Unit = {}
        if (goLabel != null) row.addView(Tf.t(ctx, goLabel, 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = btnBg(14f); setOnClickListener { close(); closeAll(); onGo?.invoke() }
        }.also { Ui.pressable(it, 0.97f) }, Tf.ll(0, Ui.dpi(44f), 1f, r = 10f))
        row.addView(Tf.t(ctx, if (goLabel != null) "Not now" else "OK", 15f, Ui.c("#4a1a8c")).apply {
            gravity = Gravity.CENTER; background = Tf.box(Ui.c("#f3eefc"), 14f); setOnClickListener { close() }
        }.also { Ui.pressable(it, 0.97f) }, Tf.ll(0, Ui.dpi(44f), 1f))
        card.addView(row, Tf.ll(Tf.M, Tf.W, t = 18f))
        close = pages.layer.dialog(card, Color.argb(128, 24, 8, 52)) { closeDialogFn = null }
        closeDialogFn = close
    }

    fun safety(k: String) {
        val (svg, title, text) = when (k) {
            "verified" -> Triple(SAFE_V, "Verified Creators", "Every Creator is checked with a live video and approved by our team before she can earn. Real people only.")
            "payments" -> Triple(SAFE_L, "Secure Payments", "All payments are 100% safe and encrypted. Your UPI and card details are never shown to anyone.")
            else -> Triple(SAFE_P, "Block Anytime", "Your chats and calls stay private. Your number is never shared, and you can block anyone anytime.")
        }
        dialog(title, text, icon = SvgView(ctx, Svg.of(svg)))
    }

    /** Join card (the server says this member has not joined this app yet) — components/JoinAppCard.jsx */
    fun joinCard(app: String, onJoined: () -> Unit): View = JoinCard.build(ctx, app, onJoined)

    // ═════════════════ the full-screen "finding someone" while Random Match works ═════════════════
    fun showMatching(type: String, stageName: String, name: String, onCancel: () -> Unit) {
        val s = stage ?: MatchStage(ctx).also { st ->
            stage = st
            st.elevation = Ui.dp(95f)
            pages.rootView().addView(st, FrameLayout.LayoutParams(-1, -1))
        }
        // "direct": one person is being called from Home (Weekly Top Creators / a person's sheet) — it can be cancelled;
        // "random": Random Match is ringing one creator (it searches on after her) — it can be cancelled too
        val cancellable = stageName == "search" || stageName == "direct" || stageName == "random"
        s.show(if (stageName == "search") "search" else "calling", type, home.rateOf(type), home.myPhotoRaws(), name,
            if (stageName == "search") "Finding someone for you…" else null, if (cancellable) onCancel else null)
        s.stageName = stageName
    }
    fun hideMatching() {
        val s = stage ?: return
        stage = null
        s.animate().alpha(0f).setDuration(160).withEndAction { (s.parent as? FrameLayout)?.removeView(s) }.start()
    }

    companion object {
        const val CROWN = "<svg viewBox=\"0 0 24 19\"><path d=\"M2.4 6.4 7.2 10.2 12 3.4l4.8 6.8 4.8-3.8-1.7 9.1H4.1z\" fill=\"currentColor\"/><circle cx=\"2.4\" cy=\"5.6\" r=\"1.7\" fill=\"currentColor\"/><circle cx=\"12\" cy=\"2.4\" r=\"1.8\" fill=\"currentColor\"/><circle cx=\"21.6\" cy=\"5.6\" r=\"1.7\" fill=\"currentColor\"/><rect x=\"4\" y=\"16.6\" width=\"16\" height=\"2.2\" rx=\"1.1\" fill=\"currentColor\"/></svg>"
        const val SAFE_V = "<svg viewBox=\"0 0 24 24\"><defs><linearGradient id=\"v\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#48b4ff\"/><stop offset=\"1\" stop-color=\"#1572e8\"/></linearGradient></defs><path d=\"M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z\" fill=\"url(#v)\"/><path d=\"m8.2 12.2 2.6 2.6 5-5.3\" fill=\"none\" stroke=\"#fff\" stroke-width=\"2.3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"
        const val SAFE_L = "<svg viewBox=\"0 0 24 24\"><defs><linearGradient id=\"l\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#4ade80\"/><stop offset=\"1\" stop-color=\"#16a34a\"/></linearGradient></defs><circle cx=\"12\" cy=\"12\" r=\"11.4\" fill=\"url(#l)\"/><rect x=\"7.4\" y=\"10.8\" width=\"9.2\" height=\"7\" rx=\"1.6\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1.7\"/><path d=\"M9.3 10.8V9.1a2.7 2.7 0 0 1 5.4 0v1.7\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1.7\"/><circle cx=\"12\" cy=\"14.2\" r=\"1.1\" fill=\"#fff\"/></svg>"
        const val SAFE_P = "<svg viewBox=\"0 0 24 24\"><defs><linearGradient id=\"p\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#c084fc\"/><stop offset=\"1\" stop-color=\"#7c3aed\"/></linearGradient></defs><circle cx=\"12\" cy=\"12\" r=\"11.4\" fill=\"url(#p)\"/><path d=\"M12 5.6 7.2 7.5v3.6c0 3.1 2 5.5 4.8 6.4 2.8-.9 4.8-3.3 4.8-6.4V7.5z\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1.6\" stroke-linejoin=\"round\"/><path d=\"m9.8 11.4 1.6 1.6 2.9-3\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"
    }
}

/** A photo filling a box with round corners (the creators grid). */
class RectPhoto(ctx: Context, private val radius: Float) : View(ctx) {
    private var bmp: android.graphics.Bitmap? = null
    private var want: List<String> = emptyList()
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val r = android.graphics.RectF()
    fun set(raws: List<String>) {
        val list = raws.filter { it.isNotBlank() }.distinct()
        if (list == want && bmp != null) return
        want = list
        post {
            val w = max(width, Ui.dpi(80f)); val h = max(height, Ui.dpi(90f))
            bmp = Photos.cachedCover(list, w, h)
            invalidate()
            if (bmp == null && list.isNotEmpty()) Photos.loadCover(list, w, h) { b -> if (want == list && b != null) { bmp = b; invalidate() } }
        }
    }
    override fun onDraw(c: android.graphics.Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        val b = bmp
        if (b != null) {
            p.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                val k = max(width.toFloat() / b.width, height.toFloat() / b.height)
                setLocalMatrix(android.graphics.Matrix().apply { setScale(k, k); postTranslate((width - b.width * k) / 2f, (height - b.height * k) / 2f) })
            }
            c.drawRoundRect(r, radius, radius, p); p.shader = null
        } else {
            // the web's fallback: lavender gradient + the default avatar
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), Ui.c("#ece2fd"), Ui.c("#dccdfa"), android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radius, radius, p); p.shader = null
            val s = min(width, height) * 0.62f
            c.save(); c.translate((width - s) / 2f, (height - s) / 2f); DefaultAvatar.draw(c, s); c.restore()
        }
    }
}

/** A row that wraps its children onto more lines (flex-wrap: wrap). */
class FlowRow(ctx: Context, private val gapX: Int, private val gapY: Int) : android.view.ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec).let { if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else it }
        var x = 0; var y = 0; var lineH = 0; var used = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i); if (ch.visibility == GONE) continue
            measureChild(ch, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > maxW) { y += lineH + gapY; x = 0; lineH = 0 }
            x += ch.measuredWidth + gapX; lineH = max(lineH, ch.measuredHeight); used = max(used, x - gapX)
        }
        setMeasuredDimension(if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(wSpec) else min(used, maxW), y + lineH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var lineH = 0
        val lines = ArrayList<Pair<Int, List<View>>>()
        var cur = ArrayList<View>()
        for (i in 0 until childCount) {
            val ch = getChildAt(i); if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { lines.add(lineH to cur); cur = ArrayList(); y += lineH + gapY; x = 0; lineH = 0 }
            cur.add(ch); x += ch.measuredWidth + gapX; lineH = max(lineH, ch.measuredHeight)
        }
        lines.add(lineH to cur)
        var top = 0
        for ((h, views) in lines) {
            var left = 0
            for (ch in views) { val dy = (h - ch.measuredHeight) / 2; ch.layout(left, top + dy, left + ch.measuredWidth, top + dy + ch.measuredHeight); left += ch.measuredWidth + gapX }
            top += h + gapY
        }
    }
}

@Suppress("FunctionName") fun ViewGroupLp(w: Int, h: Int) = android.view.ViewGroup.LayoutParams(w, h)
@Suppress("unused") private val keepTV: Class<*> = TextView::class.java

/** Join card for one app membership ("truefind" or "secret") — components/JoinAppCard.jsx. */
object JoinCard {
    fun build(ctx: android.content.Context, app: String, onJoined: () -> Unit): View {
        val secret = app == "secret"
        val card = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
            // True Find: bg-background/70 · Secret Dating: bg-card/85 (the web's dark card), amber edge
            background = if (secret) Tf.box(Color.argb(217, 29, 40, 58), 24f, 1f, Color.argb(102, 252, 211, 77))
                else Tf.box(Color.argb(179, 22, 31, 44), 24f, 1f, Color.argb(102, 255, 92, 97))
            setPadding(Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f), Ui.dpi(24f))
        }
        // (Secret Dating's card is new here — the web's card is centred, title included; Home's own card is left as it was)
        card.addView(Tf.t(ctx, if (secret) "Secret Dating" else "True Find", 18f, if (secret) Ui.c("#FDE68A") else Color.WHITE).apply { if (secret) gravity = Gravity.CENTER })
        card.addView(Tf.t(ctx, if (secret) "A separate private membership with its own map, requests, chat, premium and wallet. Joining does not touch your True Find account."
            else "Friendship discovery with its own likes, chats, calls and wallet. Joining does not touch your Secret Dating account.", 14f, Color.argb(153, 255, 255, 255), false).apply { gravity = Gravity.CENTER; setLineSpacing(Ui.dp(14f) * 0.6f, 1f) }, Tf.ll(t = 8f))
        val err = Tf.t(ctx, "", 14f, Ui.c("#FCA5A5"), false).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val btn = TfBtn(ctx, if (secret) "Join Secret Dating" else "Join True Find", "primary", Tf.lu(Lu.Check, if (secret) Color.BLACK else Color.WHITE, 16f, 2f), 48f, 16f, 14f).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (secret) intArrayOf(Ui.c("#FCD34D"), Ui.c("#F59E0B")) else intArrayOf(Ui.c("#FF5C61"), Ui.c("#FF1A21"))).apply { cornerRadius = Ui.dp(16f) }
            if (secret) text.setTextColor(Color.BLACK)
        }
        btn.setOnClickListener {
            if (btn.busy) return@setOnClickListener
            btn.busy = true; err.visibility = View.GONE
            Api.invoke("joinApp", JSONObject().put("app", app)) { r ->
                if (r.ok) { onJoined() } else { btn.busy = false; err.text = r.data.optString("error").ifEmpty { r.error ?: "Could not join." }; err.visibility = View.VISIBLE }
            }
        }
        card.addView(btn, Tf.ll(Tf.M, Tf.W, t = 20f))
        card.addView(err, Tf.ll(t = 12f))
        return card
    }
}
