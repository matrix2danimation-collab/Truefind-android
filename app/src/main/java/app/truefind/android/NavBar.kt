package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * True Find bottom bar — native copy of components/BottomNav.jsx:
 * Home · Explore · Find (Reconnect, purple heart with a white T and the
 * red count of new Reconnect notifications) · Recent · Wallet.
 * Same sizes (the web's f(n, min) on a 853 design), colours, icons, the lit
 * tab's soft glow and underline, and the .94 press.
 * It shows on Home, Explore and Recent only (NativePages decides).
 * The view is [shadowPx] taller than the bar: that top strip only draws the
 * bar's soft shadow and lets touches through.
 * Owner's rules (19 Nov): a tap on the bar never vibrates the phone; the count on Find is only the
 * number in its red badge (no growing "live" ring, no blinking); on Explore the bar has no top line or
 * shadow, so Explore's Voice / Video / Random panel and the bar read as one piece ([seamless]).
 */
@SuppressLint("ViewConstructor")
class NavBar(ctx: Context, private val onTab: (key: String, path: String) -> Unit) : View(ctx) {

    class Item(val key: String, val label: String, val path: String)
    private val items = listOf(Item("home", "Home", "/"), Item("explore", "Explore", "/explore"), Item("find", "Find", "/secret-app"),
        // the Recent tab reads "Call Back" (owner, 20 Nov: same icon, only its name changes)
        Item("recent", "Call Back", "/chats"), Item("wallet", "Wallet", "/wallet"))

    private val ON = Ui.c("#6c0fd6"); private val OFF = Ui.c("#7c7594")
    private val wDp: Float get() = minOf(resources.displayMetrics.widthPixels / resources.displayMetrics.density, 480f)
    /** f(n, min): max(min px, min(100vw, 480px) / 853 * n) */
    private fun f(n: Float, min: Float) = maxOf(min, wDp / 853f * n)
    private fun u(n: Float) = wDp / 853f * n
    val barDp: Float get() = f(139f, 60f)
    val barPx: Int get() = Ui.dpi(barDp)
    val shadowPx = Ui.dpi(16f)

    var current = "home"          // the tab of the page on screen
        set(v) { if (field != v) { field = v; pressed = null; invalidate() } }
    private var pressed: String? = null   // tapped, lit at once while its page opens
    private var pressedAt = 0L
    private var touched: String? = null   // a finger is on this tab: lit from the moment it touches (as FRND's bar answers a touch)
    var unread = 0
        set(v) { if (field != v) { val was = field; field = v; if (v > 0 && was == 0) popBadge() else invalidate() } }
    /** Explore: no top line and no shadow, so the panel above and the bar are one piece */
    var seamless = false
        set(v) { if (field != v) { field = v; invalidate() } }

    // icons (SVG markup of the web)
    private val homeOn = Svg.of("<svg viewBox=\"0 0 24 24\"><defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#7d0ade\"/><stop offset=\"1\" stop-color=\"#52049a\"/></linearGradient></defs><path d=\"M3 10.6 12 3l9 7.6V20a1.5 1.5 0 0 1-1.5 1.5H15v-6H9v6H4.5A1.5 1.5 0 0 1 3 20z\" fill=\"url(#g)\" stroke=\"none\" stroke-width=\"1.9\" stroke-linejoin=\"round\"/></svg>")
    private val homeOff = Svg.of("<svg viewBox=\"0 0 24 24\"><path d=\"M3 10.6 12 3l9 7.6V20a1.5 1.5 0 0 1-1.5 1.5H15v-6H9v6H4.5A1.5 1.5 0 0 1 3 20z\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.9\" stroke-linejoin=\"round\"/></svg>")
    private val search = Svg.lucide(Lu.Search, 2.1f)
    private val find = Svg.of("<svg viewBox=\"0 0 24 24\"><defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#7d0ade\"/><stop offset=\"1\" stop-color=\"#52049a\"/></linearGradient></defs><path d=\"M12 21.4 10.5 20C5.4 15.4 2 12.3 2 8.5 2 5.4 4.4 3 7.5 3c1.7 0 3.4.8 4.5 2.1C13.1 3.8 14.8 3 16.5 3 19.6 3 22 5.4 22 8.5c0 3.8-3.4 6.9-8.5 11.5L12 21.4z\" fill=\"url(#g)\"/><path d=\"M8.4 8.2h7.2M12 8.2v8.2\" stroke=\"#fff\" stroke-width=\"2.3\" stroke-linecap=\"round\"/></svg>")
    private val findShadow = Svg.of("<svg viewBox=\"0 0 24 24\"><path d=\"M12 21.4 10.5 20C5.4 15.4 2 12.3 2 8.5 2 5.4 4.4 3 7.5 3c1.7 0 3.4.8 4.5 2.1C13.1 3.8 14.8 3 16.5 3 19.6 3 22 5.4 22 8.5c0 3.8-3.4 6.9-8.5 11.5L12 21.4z\" fill=\"#6c0fd6\"/></svg>")
    private val recentOn = Svg.of("<svg viewBox=\"0 0 24 24\"><defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#7d0ade\"/><stop offset=\"1\" stop-color=\"#52049a\"/></linearGradient></defs><circle cx=\"12\" cy=\"12\" r=\"9.2\" fill=\"url(#g)\" stroke=\"none\"/><path d=\"M12 7.2v5.1l3.4 2.1\" fill=\"none\" stroke=\"#fff\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>")
    private val recentOff = Svg.of("<svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"9.2\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.9\"/><path d=\"M12 7.2v5.1l3.4 2.1\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>")
    private val wallet = Svg.lucide(Lu.Wallet, 2.1f)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var scale = FloatArray(5) { 1f }
    private var badgePop = 1f   // the count pops in once when it first appears (.32 s); nothing else moves

    init {
        isClickable = true
        contentDescription = "True Find"
        // a tap on the bar never vibrates (owner, 19 Nov) — not even where the phone's own touch vibration is on
        isHapticFeedbackEnabled = false
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), barPx + shadowPx)

    private fun popBadge() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { val f = it.animatedFraction; badgePop = if (f < .6f) .4f + (1.18f - .4f) * (f / .6f) else 1.18f - .18f * ((f - .6f) / .4f); invalidate() }
            start()
        }
    }

    private fun lit(): String = touched ?: pressed?.takeIf { System.currentTimeMillis() - pressedAt < 1500 } ?: current

    override fun onDraw(c: Canvas) {
        val top = shadowPx.toFloat()
        val w = width.toFloat(); val h = barPx.toFloat()
        if (!seamless) {
            // soft shadow above the bar: 0 -4px 16px rgba(91,33,182,.06)
            p.shader = LinearGradient(0f, 0f, 0f, top, Color.argb(0, 91, 33, 182), Color.argb(14, 91, 33, 182), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, top, p); p.shader = null
        }
        p.color = Color.WHITE; c.drawRect(0f, top, w, top + h, p)
        // top line: 1.5 (on the 853 design) #f1ebfc — not on Explore, where the white panel above flows into the bar
        if (!seamless) { p.color = Ui.c("#f1ebfc"); c.drawRect(0f, top, w, top + maxOf(1f, Ui.dp(u(1.5f))), p) }
        val contentW = minOf(w, Ui.dp(480f)); val x0 = (w - contentW) / 2f
        val cell = contentW / items.size
        val iconPx = Ui.dp(f(40f, 22f)); val gap = Ui.dp(f(6f, 3f)); val labelPx = Ui.dp(f(27f, 12f))
        text.textSize = labelPx; text.textAlign = Paint.Align.CENTER
        val on = lit()
        // Explore (owner, 20 Nov): its tab is a purple box joined to the purple Voice / Video / Random panel above it
        val exploreBox = seamless && on == "explore"
        if (exploreBox) drawExploreBox(c, x0 + cell * 1, cell, top, h)
        val fm = text.fontMetrics
        val labelH = fm.descent - fm.ascent
        val blockH = iconPx + gap + labelPx
        for ((i, it) in items.withIndex()) {
            val cx = x0 + cell * i + cell / 2f
            val isOn = it.key == on
            c.save()
            val s = scale[i]
            if (s != 1f) c.scale(s, s, cx, top + h / 2f)
            val box = exploreBox && it.key == "explore"
            if (isOn && !box) {
                // radial glow: 68 % × 80 % at 4 % from the top
                val gw = cell * 0.68f; val gh = h * 0.80f; val gy = top + h * 0.04f
                val g = RadialGradient(0f, 0f, 1f, Color.argb(51, 167, 121, 247), Color.argb(0, 167, 121, 247), Shader.TileMode.CLAMP)
                g.setLocalMatrix(Matrix().apply { setScale(gw / 2f, gh / 2f); postTranslate(cx, gy + gh / 2f) })
                p.shader = g; rect.set(cx - gw / 2f, gy, cx + gw / 2f, gy + gh)
                val rr = Ui.dp(u(30f)); c.drawRoundRect(rect, rr, rr, p); p.shader = null
            }
            val iy = top + (h - blockH) / 2f
            val col = if (box) Color.WHITE else if (isOn) ON else OFF
            when (it.key) {
                "home" -> (if (isOn) homeOn else homeOff).draw(c, cx - iconPx / 2f, iy, iconPx, iconPx, col)
                "explore" -> search.draw(c, cx - iconPx / 2f, iy, iconPx, iconPx, col)
                "find" -> {
                    // drop-shadow(0 2px 4px rgba(108,15,214,.35)): a soft copy under the heart
                    c.save(); c.translate(0f, Ui.dp(2f))
                    for (k in 0 until 3) { val e = Ui.dp(1f + k); findShadow.draw(c, cx - iconPx / 2f - e, iy - e, iconPx + 2 * e, iconPx + 2 * e, ON, 0.06f) }
                    c.restore()
                    find.draw(c, cx - iconPx / 2f, iy, iconPx, iconPx, col)
                    if (unread > 0) drawBadge(c, cx + iconPx / 2f, iy)
                }
                "recent" -> (if (isOn) recentOn else recentOff).draw(c, cx - iconPx / 2f, iy, iconPx, iconPx, col)
                "wallet" -> wallet.draw(c, cx - iconPx / 2f, iy, iconPx, iconPx, col)
            }
            text.color = col
            text.typeface = if (isOn) Ui.bold else Ui.regular
            val ly = iy + iconPx + gap
            // a longer name ("Call Back") never runs into the next tab: a little smaller if it must be
            val fit = cell * 0.94f
            val tw = text.measureText(it.label)
            if (tw > fit) text.textSize = labelPx * fit / tw
            c.drawText(it.label, cx, ly + (labelPx - labelH) / 2f - fm.ascent, text)
            text.textSize = labelPx
            if (isOn && !box) {
                val uw = cell * 0.60f; val uh = Ui.dp(f(5f, 2.5f)); val by = top + h - Ui.dp(f(8f, 4f))
                p.shader = LinearGradient(cx - uw / 2f, 0f, cx + uw / 2f, 0f, Ui.c("#6a06c4"), Ui.c("#8a2cf0"), Shader.TileMode.CLAMP)
                rect.set(cx - uw / 2f, by - uh, cx + uw / 2f, by); val rr = Ui.dp(u(3f)); c.drawRoundRect(rect, rr, rr, p); p.shader = null
            }
            c.restore()
        }
    }

    private val boxPath = android.graphics.Path()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /**
     * Explore's tab: one purple shape that comes down out of the panel above (no line between them), its two top
     * corners curving softly into the panel and its bottom corners a little rounded; a faint glow inside and a thin
     * line of light along its bottom. [left] and [cell] = Explore's place in the bar; [top] / [h] = the bar.
     */
    private fun drawExploreBox(c: Canvas, left: Float, cell: Float, top: Float, h: Float) {
        val pad = cell * 0.08f
        val l = left + pad; val r = left + cell - pad; val b = top + h * 0.90f
        val f = Ui.dp(14f); val k = Ui.dp(14f)
        boxPath.rewind()
        boxPath.moveTo(l - f, top); boxPath.lineTo(r + f, top)
        boxPath.quadTo(r, top, r, top + f)
        boxPath.lineTo(r, b - k); boxPath.quadTo(r, b, r - k, b)
        boxPath.lineTo(l + k, b); boxPath.quadTo(l, b, l, b - k)
        boxPath.lineTo(l, top + f); boxPath.quadTo(l, top, l - f, top)
        boxPath.close()
        // the panel ends in #3F1487; the box carries on from there
        boxPaint.shader = LinearGradient(0f, top, 0f, b, Ui.c("#3F1487"), Ui.c("#2F0B6B"), Shader.TileMode.CLAMP)
        c.drawPath(boxPath, boxPaint)
        val cx = (l + r) / 2f
        boxGlow.shader = RadialGradient(cx, b, (r - l) * 0.9f, Color.argb(120, 167, 139, 250), Color.argb(0, 167, 139, 250), Shader.TileMode.CLAMP)
        c.save(); c.clipPath(boxPath); c.drawPaint(boxGlow); c.restore()
        boxLine.strokeWidth = Ui.dp(1.2f); boxLine.color = Color.argb(90, 255, 255, 255)
        c.drawLine(l + k, b - Ui.dp(0.6f), r - k, b - Ui.dp(0.6f), boxLine)
    }

    /** red count at the heart's top-right: only the number in its red badge (white edge), steady — no ring, no blinking */
    private fun drawBadge(c: Canvas, right: Float, top: Float) {
        val txt = if (unread > 99) "99+" else unread.toString()
        val hb = Ui.dp(f(34f, 17f)); val pad = Ui.dp(f(8f, 4f)); val border = Ui.dp(f(3.5f, 2f))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = Ui.dp(f(21f, 10.5f)); textAlign = Paint.Align.CENTER; color = Color.WHITE }
        val bw = maxOf(hb, tp.measureText(txt) + 2 * pad + 2 * border)
        val r0 = right + Ui.dp(f(18f, 10f)); val t0 = top - Ui.dp(f(10f, 6f))
        rect.set(r0 - bw, t0, r0, t0 + hb)
        val cx = rect.centerX(); val cy = rect.centerY()
        c.save(); c.scale(badgePop, badgePop, cx, cy)
        p.color = Color.WHITE; c.drawRoundRect(rect, hb / 2f, hb / 2f, p)
        val inner = RectF(rect.left + border, rect.top + border, rect.right - border, rect.bottom - border)
        p.color = Ui.c("#FF1F3D"); c.drawRoundRect(inner, inner.height() / 2f, inner.height() / 2f, p)
        val fm = tp.fontMetrics
        c.drawText(txt, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
        c.restore()
    }

    // ── touches: the top shadow strip lets touches through; each tab presses (scale .94) ──
    private var downIdx = -1
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.y < shadowPx) return false
        val contentW = minOf(width.toFloat(), Ui.dp(480f)); val x0 = (width - contentW) / 2f
        val idx = ((e.x - x0) / (contentW / items.size)).toInt().coerceIn(0, items.size - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downIdx = idx; touched = items[idx].key; press(idx, true) }
            MotionEvent.ACTION_MOVE -> if (idx != downIdx || e.y < shadowPx - Ui.dp(20f)) { if (downIdx >= 0) press(downIdx, false); downIdx = -1; touched = null; invalidate() }
            MotionEvent.ACTION_UP -> {
                val was = downIdx
                downIdx = -1; touched = null
                if (was >= 0) { press(was, false); if (idx == was) tap(items[idx]) }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { if (downIdx >= 0) press(downIdx, false); downIdx = -1; touched = null; invalidate() }
        }
        return true
    }

    private val pressAnims = arrayOfNulls<ValueAnimator>(5)
    private fun press(i: Int, down: Boolean) {
        pressAnims[i]?.cancel()
        pressAnims[i] = ValueAnimator.ofFloat(scale[i], if (down) .94f else 1f).apply {
            duration = 150; addUpdateListener { scale[i] = it.animatedValue as Float; invalidate() }; start()
        }
    }

    private fun tap(it: Item) {
        // (no vibration on a tab tap: owner, 19 Nov)
        if (it.key == current) { pressed = null; invalidate(); onTab(it.key, it.path); return }
        if (pressed != null && System.currentTimeMillis() - pressedAt < 1500) return // a page is already on its way
        pressed = it.key; pressedAt = System.currentTimeMillis()
        invalidate()
        onTab(it.key, it.path)
        postDelayed({ if (pressed == it.key) { pressed = null; invalidate() } }, 1500)
    }

    override fun dispatchSetPressed(pressed: Boolean) {}
}
