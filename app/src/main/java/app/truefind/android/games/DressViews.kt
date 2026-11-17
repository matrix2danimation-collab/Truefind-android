package app.truefind.android.games

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Dress Code look: premium white + gold (web T), Calibri. */
object DC {
    val bg = G.c("#faf8f5"); val line = G.c("#ece5da"); val ink = G.c("#1f1b16"); val sub = G.c("#7a7067"); val soft = G.c("#f4efe7")
    val gold = G.c("#b8892b"); val rose = G.c("#b0174f"); val red = G.c("#b91c1c"); val green = G.c("#16a34a")
    private val shadowTint = G.c("#3c2814")

    /** linear-gradient(90deg,#d4ad55,#a8792a) + its glow (btnGold) */
    fun goldBtn(v: View, r: Float) { v.background = CssBg(r, listOf(CssBg.Linear(90f, intArrayOf(G.c("#d4ad55"), G.c("#a8792a"))))); G.shadow(v, 6f, G.c("#a8792a"), r) }
    /** white, 1 px line, small shadow (btnWhite) */
    fun whiteBtn(v: View, r: Float) { v.background = G.round(Color.WHITE, r, line, 1f); G.shadow(v, 1.5f, shadowTint, r) }
    /** T.shadow (cards) */
    fun card(v: View, r: Float, fill: Int = Color.WHITE) { v.background = G.round(fill, r, line, 1f); G.shadow(v, 4f, shadowTint, r) }

    fun t(ctx: Context, s: CharSequence, size: Float, color: Int = ink, bold: Boolean = false, lh: Float = 1.5f): TextView =
        G.text(ctx, s, size, color, bold).lineH(lh)

    /** Normal / ✦ Premium / 👑 Super Premium */
    fun tierBadge(ctx: Context, tier: String, small: Boolean = false): TextView {
        val label = when (tier) { "s" -> "👑 Super Premium"; "p" -> "✦ Premium"; else -> "Normal" }
        val fg = when (tier) { "s" -> G.c("#f7d77c"); "p" -> G.c("#6b4a00"); else -> G.c("#6b6158") }
        return G.text(ctx, label, if (small) 9.5f else 11f, fg, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            background = when (tier) {
                "s" -> CssBg(99f, listOf(CssBg.Linear(90f, intArrayOf(G.c("#2b2118"), G.c("#5a4320")))))
                "p" -> CssBg(99f, listOf(CssBg.Linear(90f, intArrayOf(G.c("#f6e7c1"), G.c("#e9c97a")))))
                else -> G.round(G.c("#f1ede6"), 99f)
            }
            if (small) pad(6f, 0f, 6f, 0f) else pad(8f, 0f, 8f, 0f)
            minHeight = G.dpi(if (small) 20f else 24f) // also right inside wrapping rows
            layoutParams = LinearLayout.LayoutParams(G.WRAP, G.dpi(if (small) 20f else 24f))
        }
    }

    /** the Coins icon (gold) + amount (web Coin) */
    fun coin(ctx: Context, n: Double, iconDp: Float, size: Float, color: Int = ink, bold: Boolean = true): LinearLayout {
        val r = G.hbox(ctx, 2f)
        r.addView(G.icon(ctx, "Coins", gold, iconDp))
        r.addView(G.text(ctx, DressArt.coinFmt(n), size, color, bold).apply { fontFeatureSettings = "tnum" })
        return r
    }

    /** h-8 px-3 rounded-full white chip with a line and a small shadow (web Chip) */
    fun chip(ctx: Context): LinearLayout = G.hbox(ctx, 6f).apply { pad(12f, 0f, 12f, 0f); whiteBtn(this, 99f); minimumHeight = G.dpi(32f) }

    /** white bar with a 1 px line on top (the chat bar) */
    fun topLine(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val p = Paint()
        override fun draw(c: Canvas) { val b = bounds; p.color = Color.WHITE; c.drawRect(b, p); p.color = line; c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + G.dp(1f), p) }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    fun gradeBg(grade: String): android.graphics.drawable.Drawable {
        val (a, b) = when (grade) { "S" -> "#f6dc8a" to "#c9962c"; "A" -> "#f4b6cf" to "#b0174f"; "B" -> "#b9c6f5" to "#4153b8"; "C" -> "#a9e4cb" to "#13805a"; else -> "#d6d1ca" to "#8b837a" }
        return CssBg(16f, listOf(CssBg.Linear(135f, intArrayOf(G.c(a), G.c(b)))))
    }

    /** a round 40 (or [size]) white button with an icon (TopBar exit / back) */
    fun roundBtn(ctx: Context, icon: String, size: Float, iconDp: Float, label: String, f: () -> Unit): FrameLayout {
        val b = FrameLayout(ctx).apply { whiteBtn(this, size / 2f); contentDescription = label }
        b.addView(G.icon(ctx, icon, ink, iconDp), FrameLayout.LayoutParams(G.dpi(iconDp), G.dpi(iconDp), Gravity.CENTER))
        G.click(b, 0.95f) { f() }
        return b
    }
}

/**
 * The doll (web Doll): the whole SVG drawn into a picture off the main thread, kept until the next look is
 * ready (no blank frame while it changes). Fits its box like the web's h-full w-auto / w-full h-full.
 */
class DollView(ctx: Context) : View(ctx) {
    private var look: Map<String, DressArt.Wear> = emptyMap()
    private var back = false
    private var bmp: Bitmap? = null
    private var wantKey = ""
    private var shownKey = ""
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    /** 1 = picture at the screen's own sharpness; more after zooming in */
    var renderScale = 1f
        set(v) { if (field != v) { field = v; request() } }

    init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun set(l: Map<String, DressArt.Wear>, backSide: Boolean) {
        look = LinkedHashMap(l); back = backSide
        request()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); request() }

    private fun fitted(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w / 300f, h / 545f)
        val dw = 300f * s; val dh = 545f * s
        dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        return dst
    }

    private fun request() {
        if (width <= 0 || height <= 0) return
        if (!DressArt.ready) { DressArt.load(context) { request() }; return }
        val r = fitted()
        val k = min(renderScale, 2.2f)
        val pw = (r.width() * k).roundToInt(); val ph = (r.height() * k).roundToInt()
        val src = DressArt.doll(look, back)
        val key = "$pw×$ph|${src.hashCode()}|${src.length}"
        if (key == wantKey) return
        wantKey = key
        Svg.bitmapOnce(src, pw, ph) { b ->
            if (b != null && wantKey == key) { bmp = b; shownKey = key; invalidate() }
        }
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.drawBitmap(b, null, fitted(), paint)
    }
}

/** One wardrobe item on a soft mannequin (web Thumb); drawn only while near the screen. */
class ThumbView(ctx: Context) : View(ctx) {
    private var item: DressArt.Item? = null
    private var c = 0
    private var bmp: Bitmap? = null
    private var want = ""
    private var vbW = 300f; private var vbH = 545f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    var active = true
        set(v) { if (field != v) { field = v; if (v) request() else { bmp = null; want = ""; invalidate() } } }

    fun set(it: DressArt.Item, color: Int) {
        if (item === it && c == color) return
        item = it; c = color; want = ""
        request()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); request() }
    private fun fitted(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w / vbW, h / vbH)
        dst.set((w - vbW * s) / 2f, (h - vbH * s) / 2f, (w + vbW * s) / 2f, (h + vbH * s) / 2f)
        return dst
    }
    private fun request() {
        val it = item ?: return
        if (!active || width <= 0 || height <= 0 || !DressArt.ready) return
        val (src, w, h) = DressArt.thumb(it, c)
        vbW = w; vbH = h
        val r = fitted()
        val pw = r.width().roundToInt(); val ph = r.height().roundToInt()
        val key = "$pw×$ph|${it.id}|$c"
        if (key == want) return
        want = key
        Svg.bitmapOnce(src, pw, ph) { b -> if (want == key) { bmp = b; invalidate() } }
    }
    override fun onDraw(cv: Canvas) { val b = bmp ?: return; cv.drawBitmap(b, null, fitted(), paint) }
}

/**
 * Doll viewer (web DollViewer): pinch or + / − to zoom (1–3×), drag when zoomed, swipe or ⟲ ⟳ to turn
 * (front → side → back → side), double tap to zoom 2× / back. [overlay] holds the screen's own HUD.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DollViewer(ctx: Context, private val controls: Boolean = true) : FrameLayout(ctx) {
    private class V(val k: String, val sx: Float, val label: String)
    private val VIEWS = listOf(V("front", 1f, "Front"), V("side", 0.58f, "Side"), V("back", -1f, "Back"), V("side", -0.58f, "Side"))
    private var view = 0
    private var z = 1f
    private var panX = 0f; private var panY = 0f
    private val stage = object : FrameLayout(ctx) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            // web: flex, centred, items at the bottom with 3% (of the width) below; the doll 94% high, its own width
            val w = r - l; val h = b - t
            val dh = (h * 0.94f).roundToInt(); val dw = (dh * 300f / 545f).roundToInt()
            val bottom = h - (w * 0.03f).roundToInt()
            val x = (w - dw) / 2
            doll.layout(x, bottom - dh, x + dw, bottom)
        }
        override fun onMeasure(ws: Int, hs: Int) {
            super.onMeasure(ws, hs)
            val h = MeasureSpec.getSize(hs)
            val dh = (h * 0.94f).roundToInt(); val dw = (dh * 300f / 545f).roundToInt()
            doll.measure(MeasureSpec.makeMeasureSpec(dw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dh, MeasureSpec.EXACTLY))
        }
    }
    val doll = DollView(ctx)
    /** the screen's own things over the doll (web {children}) */
    val overlay = FrameLayout(ctx)
    private var label: TextView? = null
    private var reset: View? = null
    private var look: Map<String, DressArt.Wear> = emptyMap()
    private val EASE_IO = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)
    private var turnRun: Runnable? = null
    private var sharpRun: Runnable? = null

    init {
        clipChildren = true
        stage.addView(doll)
        addView(stage, LayoutParams(G.MATCH, G.MATCH))
        addView(overlay, LayoutParams(G.MATCH, G.MATCH))
        overlay.clipChildren = false
        if (controls) buildControls()
        stage.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width * 0.5f; v.pivotY = v.height * 0.45f }
        paintLabel()
    }

    fun setLook(l: Map<String, DressArt.Wear>) { look = l; doll.set(l, VIEWS[view].k == "back"); doll.contentDescription = "Doll, ${VIEWS[view].label} view" }

    private fun buildControls() {
        val ctx = context
        fun turnBtn(t: String, label: String, dir: Int): View {
            val b = G.text(ctx, t, 16f, DC.ink, true).apply { gravity = Gravity.CENTER; contentDescription = label }
            DC.whiteBtn(b, 18f)
            G.click(b, 0.95f) { turn(dir) }
            return b
        }
        addView(turnBtn("⟲", "Turn left", -1), LayoutParams(G.dpi(36f), G.dpi(36f), Gravity.START or Gravity.CENTER_VERTICAL).m(l = 8f))
        addView(turnBtn("⟳", "Turn right", 1), LayoutParams(G.dpi(36f), G.dpi(36f), Gravity.END or Gravity.CENTER_VERTICAL).m(r = 8f))
        val pill = G.hbox(ctx, 6f).apply {
            background = G.round(G.ca("#ffffff", 0.92f), 99f, DC.line, 1f); pad(6f, 4f, 6f, 4f)
            G.shadow(this, 1.5f, G.c("#3c2814"), 99f)
        }
        fun pm(t: String, label: String, f: () -> Unit) = G.text(ctx, t, 18f, DC.ink, true).lineH(1f).apply { gravity = Gravity.CENTER; contentDescription = label }.also { v ->
            G.click(v, 0.92f) { f() }; pill.addView(v, LinearLayout.LayoutParams(G.dpi(28f), G.dpi(28f)))
        }
        pm("−", "Zoom out") { zoomTo(z - 0.5f, true) }
        label = G.text(ctx, "", 11f, DC.sub, true).apply { gravity = Gravity.CENTER; maxLines = 1; minWidth = G.dpi(64f) }
        pill.addView(label)
        pm("+", "Zoom in") { zoomTo(z + 0.5f, true) }
        reset = G.text(ctx, "Reset", 11f, DC.gold, true).apply { gravity = Gravity.CENTER; pad(8f, 0f, 8f, 0f); contentDescription = "Reset view" }.also { v ->
            G.click(v, 0.95f) { zoomTo(1f, true); if (view != 0) { view = 0; doll.animate().cancel(); doll.scaleX = 1f; setLook(look) }; paintLabel() }
            pill.addView(v, LinearLayout.LayoutParams(G.WRAP, G.dpi(28f)))
        }
        addView(pill, LayoutParams(G.WRAP, G.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).m(b = 8f))
    }

    private fun paintLabel() {
        label?.text = "${VIEWS[view].label} · ${(z * 100).roundToInt()}%"
        reset?.visibility = if (z > 1f || view != 0) View.VISIBLE else View.GONE
    }

    /** web turn(): squeeze to 0 in .15 s, change the view, open to its width in .15 s */
    fun turn(dir: Int) {
        val n = (view + dir + 4) % 4
        doll.animate().scaleX(0f).setDuration(150).setInterpolator(EASE_IO).start()
        turnRun?.let { removeCallbacks(it) }
        turnRun = Runnable {
            view = n; setLook(look)
            doll.animate().scaleX(VIEWS[n].sx).setDuration(150).setInterpolator(EASE_IO).start()
            paintLabel()
        }.also { postDelayed(it, 150) }
    }

    private fun applyZoom(animate: Boolean) {
        stage.animate().cancel()
        if (animate) stage.animate().scaleX(z).scaleY(z).translationX(panX).translationY(panY).setDuration(200).setInterpolator(EASE_OUT).start()
        else { stage.scaleX = z; stage.scaleY = z; stage.translationX = panX; stage.translationY = panY }
        paintLabel()
        // sharper picture once the zoom settles
        sharpRun?.let { removeCallbacks(it) }
        sharpRun = Runnable { doll.renderScale = z }.also { postDelayed(it, 250) }
    }
    private fun zoomTo(v: Float, animate: Boolean) {
        z = v.coerceIn(1f, 3f)
        if (z == 1f) { panX = 0f; panY = 0f }
        applyZoom(animate)
    }

    // ── gestures (web pointer events) ──
    private val pts = LinkedHashMap<Int, Pair<Float, Float>>()
    private var pinch = false; private var d0 = 1f; private var z0 = 1f
    private var x0 = 0f; private var y0 = 0f; private var p0x = 0f; private var p0y = 0f
    private var gesture = false
    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomTo(if (z > 1f) 1f else 2f, true); return true }
        override fun onDown(e: MotionEvent) = true
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = e.actionIndex
                pts[e.getPointerId(i)] = e.getX(i) to e.getY(i)
                val arr = pts.values.toList()
                gesture = true
                if (arr.size == 2) { pinch = true; d0 = hypot(arr[0].first - arr[1].first, arr[0].second - arr[1].second); z0 = z }
                else { pinch = false; x0 = e.getX(i); y0 = e.getY(i); p0x = panX; p0y = panY }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) { val id = e.getPointerId(i); if (pts.containsKey(id)) pts[id] = e.getX(i) to e.getY(i) }
                if (!gesture) return true
                val arr = pts.values.toList()
                if (pinch && arr.size == 2) zoomTo(z0 * (hypot(arr[0].first - arr[1].first, arr[0].second - arr[1].second) / (if (d0 > 0f) d0 else 1f)), false)
                else if (!pinch && z > 1f) { panX = p0x + (e.x - x0); panY = p0y + (e.y - y0); applyZoom(false) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val i = e.actionIndex
                if (gesture && !pinch && z == 1f && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val dx = e.getX(i) - x0
                    if (kotlin.math.abs(dx) > G.dp(40f) && kotlin.math.abs(dx) > kotlin.math.abs(e.getY(i) - y0)) turn(if (dx < 0) 1 else -1)
                }
                pts.remove(e.getPointerId(i))
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP || pts.isEmpty()) { gesture = false; pts.clear() }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() { turnRun?.let { removeCallbacks(it) }; sharpRun?.let { removeCallbacks(it) }; super.onDetachedFromWindow() }
}

/** ★★★★★ (web Stars): gold outline, filled + a soft glow up to [value]. */
class StarsView(ctx: Context, private val sizeDp: Float = 34f, private val onPick: ((Int) -> Unit)? = null) : LinearLayout(ctx) {
    private val stars = ArrayList<SvgView>()
    var value = 0
        set(v) { field = v; paint() }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        G.gap(this, 6f)
        for (n in 1..5) {
            val v = SvgView(ctx, "", 24f, 24f)
            v.contentDescription = "$n star${if (n > 1) "s" else ""}"
            if (onPick != null) G.click(v, 0.9f) { onPick.invoke(n) }
            stars.add(v)
            addView(v, LayoutParams(G.dpi(sizeDp), G.dpi(sizeDp)))
        }
        contentDescription = "0 of 5 stars"
        clipChildren = false; clipToPadding = false
        paint()
    }
    private fun paint() {
        contentDescription = "$value of 5 stars"
        stars.forEachIndexed { i, v ->
            val on = i + 1 <= value
            val src = Svg.icon("Star", G.c("#e0a92e"), 1.6f, if (on) G.c("#f2c14e") else null)
            // drop-shadow(0 2px 6px rgba(224,169,46,.5)) in the icon's own units (24 per star)
            v.shadows = if (on) listOf(Svg.Shadow(src, 0f, 2f * 24f / sizeDp, 3f * 24f / sizeDp, G.c("#e0a92e"), 0.5f)) else emptyList()
            v.set(src)
        }
    }
}
