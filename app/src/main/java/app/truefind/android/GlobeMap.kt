package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Premium 3D Earth (the web's GlobeMap in src/pages/SecretApp.jsx): a real globe you can spin 360°,
 * India raised and glowing on it, verified members as gold pins at the city they chose (never a live
 * location). One finger turns the Earth under your finger, two fingers zoom where your fingers are,
 * a flick keeps it turning a little. The first time it opens the Earth rolls half way round and
 * glides down to your city. Sizes are the web's CSS pixels (dp here).
 */
@SuppressLint("ViewConstructor")
class GlobeMap(
    ctx: Context,
    private val onOpen: (JSONObject) -> Unit,
    private val onGroup: (List<JSONObject>, String) -> Unit,
    private val onFull: () -> Unit,
    private val say: (String) -> Unit,
) : FrameLayout(ctx) {

    // ── the view of the Earth: rotation (lon, lat), zoom k ──
    private class V(var lon: Double, var lat: Double, var k: Double) { fun copy() = V(lon, lat, k) }
    private var view = V(HOME_LON, HOME_LAT, HOME_K)
    private var idleView = view.copy()
    private var bw = 0.0; private var bh = 0.0            // the box, in dp
    private val dens = Ui.density
    private var busy = false
    private var pendingIntro = true
    var active = false
        set(v) { field = v; if (v) maybeStart() else { if (frame != null) { stop(); busy = false; idleView = view.copy(); refresh() } } }
    var full = false
        set(v) { field = v; fullBtn.setImageDrawable(Icon(if (v) Lucide.Minimize2 else Lucide.Maximize2, GOLD_ICON, 20f)); fullBtn.contentDescription = if (v) "Exit full screen" else "Full screen map" }

    // people
    private var people: List<JSONObject> = emptyList()
    var me: JSONObject? = null
    var mine: JSONObject = JSONObject()
    private class Group(val key: String, val city: String, val geo: DoubleArray, val people: MutableList<JSONObject>)
    private class Cluster(val key: String, val geo: DoubleArray, val people: List<JSONObject>, val city: String, val cities: List<String>)
    private var groups: List<Group> = emptyList()
    private var clusters: List<Cluster> = emptyList()
    private var zoomStepNow = Int.MIN_VALUE
    private class Label(val key: String, val n: String, val lng: Double, val lat: Double, val fs: Float, val st: Boolean, val big: Boolean)
    private var labels: List<Label> = emptyList()
    private val labelBorn = HashMap<String, Long>()

    // children: stars · the Earth (kept as a picture while it rests) · pins and names · buttons
    private val stars = Stars(ctx)
    private val globe = Globe(ctx)
    private val top = Top(ctx)
    private val fullBtn = mapButton(Lucide.Maximize2, 20f, "Full screen map") { onFull() }
    private val locBtn = mapButton(Lucide.Navigation, 19f, "Go to my city", 1.8f) { locate() }

    init {
        clipChildren = true
        background = null
        addView(stars, LayoutParams(-1, -1))
        globe.setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(globe, LayoutParams(-1, -1))
        addView(top, LayoutParams(-1, -1))
        // controls
        addView(fullBtn, LayoutParams(dpi(46f), dpi(46f), Gravity.START or Gravity.BOTTOM).apply { setMargins(dpi(12f), 0, 0, dpi(14f)) })
        addView(locBtn, LayoutParams(dpi(46f), dpi(46f), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dpi(12f), dpi(14f)) })
        val zoom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctlBg(23f, .3f, .2f, .4f, .9f, .7f)
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = Ui.dp(4f); outlineSpotShadowColorCompat(Color.argb(191, 139, 63, 224))
        }
        zoom.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Plus, Color.WHITE, 22f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Zoom in" }
            .onTap { zoomSoft(1.7, null) }, LinearLayout.LayoutParams(dpi(46f), dpi(48f)))
        zoom.addView(View(ctx).apply { setBackgroundColor(Color.argb(89, 240, 200, 120)) }, LinearLayout.LayoutParams(-1, dpi(1f)).apply { setMargins(dpi(10f), 0, dpi(10f), 0) })
        zoom.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Minus, Color.WHITE, 22f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Zoom out" }
            .onTap { zoomSoft(1 / 1.7, null) }, LinearLayout.LayoutParams(dpi(46f), dpi(48f)))
        addView(zoom, LayoutParams(dpi(46f), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(12f) })
        // translateY(-40%) of its own height: a little below the middle
        zoom.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.translationY = v.height * 0.1f }
        addView(sdText(ctx, "Map: svg-maps (CC BY 4.0) · Natural Earth · GeoNames", 8.5f, Color.argb(71, 255, 255, 255), bold = false).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(4f) })
        contentDescription = "3D Earth with verified members across India. Drag to spin the globe, pinch or use + and − to zoom."
        post { SdMapData.load(context) { makeClusters(); refresh() } }
    }

    // ═════════════ the projection (orthographic) ═════════════
    private fun radius(k: Double) = max(60.0, min(bw, bh) / 2 - 18) * k
    private fun cx() = bw / 2
    private fun cy() = bh / 2
    private fun proj(v: V) = Proj(v.lon, v.lat, radius(v.k), cx(), cy())

    /** which rotation puts place g at screen point (sx, sy) at zoom k */
    private fun aim(g: DoubleArray, k: Double, sx: Double, sy: Double): V {
        val r = radius(k)
        var c = V(g[0], g[1], k)
        val o = DoubleArray(5)
        for (i in 0 until 8) {
            Proj(c.lon, c.lat, r, cx(), cy()).at(g[0], g[1], o)
            if (o[2] <= 0) break
            val ex = o[0] - sx; val ey = o[1] - sy
            if (abs(ex) + abs(ey) < 0.3) break
            c = V(c.lon + (ex / r) / D2R, max(-85.0, min(85.0, c.lat - (ey / r) / D2R)), k)
        }
        return c
    }
    /** the place under a screen point (null: space) */
    private fun grab(x: Double, y: Double): DoubleArray? = unProj(x, y, view.lon, view.lat, radius(view.k), cx(), cy())
    /** the patch of the Earth on screen (lon/lat box with a margin) — null when the whole globe shows */
    private fun geoBox(v: V): DoubleArray? {
        val r = radius(v.k); val ex = max(bw, bh) * 0.3
        var a = 1e9; var s = 1e9; var e = -1e9; var n = -1e9
        for (i in 0..8) for (j in 0..8) {
            if (i in 1..7 && j in 1..7) continue
            val g = unProj(-ex + ((bw + 2 * ex) * i) / 8, -ex + ((bh + 2 * ex) * j) / 8, v.lon, v.lat, r, cx(), cy()) ?: return null
            if (g[0] < a) a = g[0]; if (g[0] > e) e = g[0]; if (g[1] < s) s = g[1]; if (g[1] > n) n = g[1]
        }
        if (e - a > 110 || n - s > 80) return null
        val pl = (e - a) * 0.12 + 0.05; val pt = (n - s) * 0.12 + 0.05
        return doubleArrayOf(a - pl, s - pt, e + pl, n + pt)
    }
    private fun freeV(v: V) = V(wrapLon(v.lon), max(-85.0, min(85.0, v.lat)), max(1.0, min(MAX_K, v.k)))
    private fun put(v: V) { view = freeV(v); refresh() }
    private fun homeView() = aim(doubleArrayOf(80.5, 22.0), HOME_K, bw / 2, bh * 0.54)
    private fun myGeo(): DoubleArray = me?.let { SdRules.geoOf(SdRules.s(it, "city"), SdRules.s(it, "state")) } ?: SdRules.geoOf(SdRules.s(mine, "city"), SdRules.s(mine, "state"))
    private fun myView() = aim(myGeo(), 4.2, bw / 2, bh * 0.5)

    // ═════════════ frames ═════════════
    private var frame: ((Double) -> Unit)? = null
    private val choreo = Choreographer.getInstance()
    private val tick = object : Choreographer.FrameCallback {
        // (while pages are moving the globe holds still, as Explore's bubbles do: see PageFx)
        override fun doFrame(t: Long) { val f = frame ?: return; if (!PageFx.running) f(t / 1e6); if (frame != null) choreo.postFrameCallback(this) }
    }
    private fun loop(f: (Double) -> Unit) { val was = frame != null; frame = f; if (!was) { choreo.removeFrameCallback(tick); choreo.postFrameCallback(tick) } }
    private fun stop() { frame = null; choreo.removeFrameCallback(tick); zt = null }
    private fun now() = System.nanoTime() / 1e6

    private var lastPaint = 0.0
    private val again = Runnable { refresh() }
    /** draw again (low-end phones: about 30 frames a second while it moves — half the drawing, no stutter) */
    private fun refresh() {
        val t = now()
        if (LITE && busy && t - lastPaint < 26) { removeCallbacks(again); postOnAnimation(again); return }
        lastPaint = t
        globe.invalidate(); top.invalidate(); stars.invalidate()
        val step = round(ln(view.k) / ln(1.18)).toInt()
        if (step != zoomStepNow) { zoomStepNow = step; makeClusters() }
    }
    private val main = Handler(Looper.getMainLooper())
    private val settled = Runnable { busy = false; idleView = view.copy(); makeClusters(); refresh() }
    private fun settle(ms: Long = 200) { main.removeCallbacks(settled); main.postDelayed(settled, ms) }

    private fun fly(to: V, ms: Double = 650.0, raw: Boolean = false, then: (() -> Unit)? = null) {
        stop()
        val from = view.copy()
        val dLon = if (raw) to.lon - from.lon else lonGap(from.lon, to.lon)
        val t0 = now()
        busy = true
        loop { n ->
            val t = min(1.0, (n - t0) / ms); val e = easeIO(t)
            put(V(from.lon + dLon * e, from.lat + (to.lat - from.lat) * e, from.k + (to.k - from.k) * e))
            if (t >= 1) { frame = null; if (then != null) then() else settle(60) }
        }
    }

    // smooth zoom: fingers or buttons only say WHERE the zoom should go; the map glides there frame by frame
    private class Z(var k: Double, var sx: Double, var sy: Double, var g: DoubleArray?, var vel: Double = 0.0, var last: Double = 0.0, var hold: Boolean = false, var tau: Double = 90.0)
    private var zt: Z? = null
    private val zoomTick: (Double) -> Unit = { n ->
        val z = zt
        if (z == null) frame = null
        else {
            val dt = min(34.0, max(4.0, n - (if (z.last > 0) z.last else n - 16))); z.last = n
            if (!z.hold && z.vel != 0.0) {
                z.k = max(1.0, min(MAX_K, z.k * exp(z.vel * dt)))
                z.vel *= 0.82.pow(dt / 16)
                if (abs(z.vel) < 1.5e-4 || z.k <= 1 || z.k >= MAX_K) z.vel = 0.0
            }
            val lk = ln(view.k); val tk = ln(z.k)
            val done = !z.hold && z.vel == 0.0 && abs(tk - lk) < 0.004
            val nk = if (done) z.k else exp(lk + max(-0.11, min(0.11, (tk - lk) * (1 - exp(-dt / z.tau)))))
            var next: V? = null
            val g = z.g
            if (g != null) {
                val t = aim(g, nk, z.sx, z.sy)
                val o = DoubleArray(5); Proj(t.lon, t.lat, radius(nk), cx(), cy()).at(g[0], g[1], o)
                if (o[2] > 0.05) next = t else z.g = null
            }
            put(next ?: V(view.lon, view.lat, nk))
            if (z.g == null) z.g = grab(z.sx, z.sy)
            if (done) { zt = null; frame = null; settle(60) }
        }
    }
    private fun zoomToward(k: Double, sx: Double, sy: Double, hold: Boolean = false, tau: Double = 90.0, g: DoubleArray? = null) {
        var z = zt
        if (z == null) {
            stop()
            z = Z(view.k, sx, sy, g ?: grab(sx, sy)); zt = z
            busy = true
        } else if (g != null) z.g = g
        else if (!hold && abs(z.sx - sx) + abs(z.sy - sy) > 2) z.g = grab(sx, sy)
        z.k = max(1.0, min(MAX_K, k)); z.sx = sx; z.sy = sy; z.hold = hold; z.tau = tau; z.vel = 0.0
        if (frame !== zoomTick) loop(zoomTick)
    }
    private fun zoomBy(f: Double, px: Double, py: Double) { val base = zt?.k ?: view.k; zoomToward(base * f, px, py, tau = 110.0) }
    private fun zoomSoft(f: Double, at: DoubleArray?) { zoomBy(f, at?.get(0) ?: (bw / 2), at?.get(1) ?: (bh / 2)) }

    // the first time the map opens: the Earth rolls half way round and glides down to where you are
    private fun maybeStart() {
        if (!active || bw <= 0 || !pendingIntro) return
        pendingIntro = false
        if (!introDone) playIntro() else { view = homeView(); idleView = view.copy(); refresh() }
    }
    private fun playIntro() {
        stop()
        introDone = true
        val end = myView()
        if (REDUCED) { view = freeV(end); idleView = view.copy(); refresh(); return }
        val start = V(end.lon - 180, 14.0, 1.0)
        view = start.copy()
        busy = true; refresh()
        val t0 = now(); val ms = if (LITE) 3000.0 else 3300.0
        loop { n ->
            val t = min(1.0, (n - t0) / ms)
            val spin = 0.5 - 0.5 * cos(Math.PI * min(1.0, t / 0.9))
            val dive = if (t < 0.4) 0.0 else easeIO((t - 0.4) / 0.6)
            view = freeV(V(start.lon + (end.lon - start.lon) * spin, start.lat + (end.lat - start.lat) * dive, start.k + (end.k - start.k) * dive))
            refresh()
            if (t >= 1) { frame = null; settle(40) }
        }
    }

    private fun locate() {
        fly(aim(myGeo(), 5.0, bw / 2, bh * 0.5), 900.0)
    }

    // the stars, the glowing centre and the pins move only while the map is on screen
    private val visRect = android.graphics.Rect()
    private fun onScreen(): Boolean = isShown && getGlobalVisibleRect(visRect)
    private val scrolled = android.view.ViewTreeObserver.OnScrollChangedListener { if (onScreen()) { stars.invalidate(); top.invalidate() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnScrollChangedListener(scrolled) }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrolled)
        if (frame != null) { stop(); busy = false; idleView = view.copy() }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        bw = w / dens.toDouble(); bh = h / dens.toDouble()
        if (pendingIntro) maybeStart()
        makeClusters(); refresh()
    }

    // ═════════════ people → pins (nearby cities share one pin until you zoom in) ═════════════
    fun setPeople(list: List<JSONObject>) {
        if (list.map { it.optString("id") + it.optBoolean("_online") + it.optBoolean("chatting") } == people.map { it.optString("id") + it.optBoolean("_online") + it.optBoolean("chatting") }) { people = list; return }
        people = list
        val m = LinkedHashMap<String, Group>()
        for (p in list) {
            val g = SdRules.geoOf(SdRules.s(p, "city"), SdRules.s(p, "state"))
            val key = SdRules.s(p, "city") + "|" + SdRules.s(p, "state")
            m.getOrPut(key) { Group(key, SdRules.s(p, "city").ifEmpty { SdRules.s(p, "state") }, g, ArrayList()) }.people.add(p)
        }
        groups = m.values.toList()
        makeClusters(); top.invalidate()
    }
    private fun makeClusters() {
        if (bw <= 0) { clusters = emptyList(); return }
        val cv = view
        val r = radius(cv.k); val P = Proj(cv.lon, cv.lat, r, cx(), cy())
        val thr = 58 * pinScaleOf(r)
        class C(val key: String, val geo: DoubleArray, val sx: Double, val sy: Double, val people: ArrayList<JSONObject>, val cities: ArrayList<String>)
        val out = ArrayList<C>(); val o = DoubleArray(5)
        for (g in groups.sortedByDescending { it.people.size }) {
            P.at(g.geo[0], g.geo[1], o)
            val near = out.firstOrNull { hypot(it.sx - o[0], it.sy - o[1]) < thr }
            if (near != null) { near.people.addAll(g.people); if (g.city !in near.cities) near.cities.add(g.city) }
            else out.add(C(g.key, g.geo, o[0], o[1], ArrayList(g.people), arrayListOf(g.city)))
        }
        clusters = out.map { Cluster(it.key, it.geo, it.people, if (it.cities.size > 1) "${it.cities[0]} +${it.cities.size - 1}" else it.cities[0], it.cities) }
        makeLabels()
    }
    // which place names fit is worked out when the map comes to rest (never on top of a person, a button or each other)
    private fun makeLabels() {
        if (bw <= 0 || !SdMapData.ready) { labels = emptyList(); return }
        val vv = view; val r = radius(vv.k); val P = Proj(vv.lon, vv.lat, r, cx(), cy()); val ps = pinScaleOf(r)
        val minPop = minPopFor(r * D2R)
        val showStates = vv.k >= 4.5 && vv.k < 15
        val cityLbl = r > 900
        val boxes = ArrayList<DoubleArray>(); val o = DoubleArray(5)
        for (c in clusters) { P.at(c.geo[0], c.geo[1], o); if (o[2] > 0.08) boxes.add(doubleArrayOf(o[0], o[1] - 24 * ps, (if (cityLbl) 104.0 else 62.0) * ps, 114 * ps)) }
        boxes.add(doubleArrayOf(87.0, 33.0, 164.0, 48.0)); boxes.add(doubleArrayOf(bw - 35, bh / 2 + 10, 58.0, 108.0))
        boxes.add(doubleArrayOf(35.0, bh - 37, 60.0, 60.0)); boxes.add(doubleArrayOf(bw - 35, bh - 37, 60.0, 60.0)); boxes.add(doubleArrayOf(bw / 2, bh - 9, 300.0, 16.0))
        fun fits(x: Double, y: Double, wd: Double, ht: Double) = boxes.none { q -> abs(q[0] - x) < (q[2] + wd) / 2 && abs(q[1] - y) < (q[3] + ht) / 2 }
        val qv = geoBox(vv)
        fun seen(lng: Double, lat: Double) = qv == null || (lng >= qv[0] && lng <= qv[2] && lat >= qv[1] && lat <= qv[3])
        val out = ArrayList<Label>()
        fun tryAdd(ct: SdMapData.City?, st: SdMapData.State?) {
            val lng = (ct?.lng ?: st!!.lng).toDouble(); val lat = (ct?.lat ?: st!!.lat).toDouble()
            P.at(lng, lat, o)
            if (o[2] < 0.3 || o[0] < -60 || o[1] < -20 || o[0] > bw + 60 || o[1] > bh + 20) return
            if (st != null) {
                val fs = if (vv.k >= 7) 11.5 else 10.5; val lw = st.n.length * fs * 0.6 + 6
                if (!fits(o[0], o[1], lw, 15.0)) return
                boxes.add(doubleArrayOf(o[0], o[1], lw, 15.0))
                out.add(Label("s-${st.n}", st.n, lng, lat, fs.toFloat(), true, false)); return
            }
            ct!!
            val big = ct.pop >= 3500; val fs = if (big) 12.0 else 10.5; val lw = ct.n.length * fs * 0.56 + 14
            if (!fits(o[0] + lw / 2 - 4, o[1], lw, 16.0)) return
            boxes.add(doubleArrayOf(o[0] + lw / 2 - 4, o[1], lw, 16.0))
            out.add(Label("c-${ct.n}", ct.n, lng, lat, fs.toFloat(), false, big))
        }
        for (ct in SdMapData.cities) if (ct.pop >= 3500 && ct.pop >= minPop && seen(ct.lng.toDouble(), ct.lat.toDouble())) tryAdd(ct, null)
        if (showStates) for (st in SdMapData.states) if (st.a > (if (vv.k >= 7) 280 else 1500)) tryAdd(null, st)
        for (ct in SdMapData.cities) if (ct.pop < 3500 && ct.pop >= minPop && seen(ct.lng.toDouble(), ct.lat.toDouble())) tryAdd(ct, null)
        val t = System.currentTimeMillis()
        val keep = out.map { it.key }.toHashSet()
        labelBorn.keys.retainAll(keep)
        for (l in out) labelBorn.getOrPut(l.key) { t }
        labels = out
    }

    private fun tapCluster(c: Cluster) {
        if (c.people.size == 1) { onOpen(c.people[0]); return }
        if (view.k < 6 && c.cities.size > 1) { fly(aim(c.geo, min(MAX_K, view.k * 2.2), bw / 2, bh * 0.5), 650.0); return }
        onGroup(c.people, "${c.people.size} ${if (c.people.size == 1) "person" else "people"} · ${c.city}")
    }

    // ═════════════ touch: one finger turns, two fingers zoom ═════════════
    private val ptrs = LinkedHashMap<Int, DoubleArray>()
    private class G { var mode = "turn"; var moved = false; var travel = 0.0; var vx = 0.0; var vy = 0.0; var t = 0.0; var g0: DoubleArray? = null
        var k0 = 1.0; var dist0 = 1.0; var pg: DoubleArray? = null; var zv = 0.0; var lt = 0.0; var lk = 1.0 }
    private var gs = G()
    private var lastTap = 0L; private var lastTapX = 0.0; private var lastTapY = 0.0
    private fun fingers(): Pair<DoubleArray, Double> {
        val pts = ptrs.values.toList()
        return if (pts.size > 1) doubleArrayOf((pts[0][0] + pts[1][0]) / 2, (pts[0][1] + pts[1][1]) / 2) to hypot(pts[0][0] - pts[1][0], pts[0][1] - pts[1][1]) else pts[0] to 0.0
    }
    private fun startPinch() {
        val (mid, dist) = fingers()
        gs.mode = "pinch"; gs.k0 = zt?.k ?: view.k; gs.dist0 = if (dist > 0) dist else 1.0; gs.pg = grab(mid[0], mid[1]); gs.zv = 0.0; gs.lt = 0.0; gs.lk = gs.k0
    }
    private fun startTurn(pos: DoubleArray) { gs.mode = "turn"; gs.g0 = grab(pos[0], pos[1]); gs.vx = 0.0; gs.vy = 0.0; gs.t = now() }
    private fun turnTo(cur: DoubleArray, prev: DoubleArray) {
        val v = view; val r = radius(v.k)
        val g0 = gs.g0
        if (g0 != null) {
            val t = aim(g0, v.k, cur[0], cur[1])
            val o = DoubleArray(5); Proj(t.lon, t.lat, r, cx(), cy()).at(g0[0], g0[1], o)
            if (o[2] > 0.05 && hypot(o[0] - cur[0], o[1] - cur[1]) < 4) { put(V(t.lon, t.lat, v.k)); return }
        }
        put(V(v.lon - ((cur[0] - prev[0]) / r) / D2R, v.lat + ((cur[1] - prev[1]) / r) / D2R, v.k))
        gs.g0 = grab(cur[0], cur[1])
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // the map's own touches; the round buttons keep theirs
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (frame != null) { stop(); settle(30) }
                val pos = doubleArrayOf(e.getX(i) / dens.toDouble(), e.getY(i) / dens.toDouble())
                ptrs[e.getPointerId(i)] = pos
                if (ptrs.size == 1) { gs = G(); gs.t = now(); startTurn(pos) } else if (ptrs.size == 2) startPinch()
            }
            MotionEvent.ACTION_MOVE -> {
                val prev1 = if (ptrs.size == 1) ptrs.values.first().clone() else null
                for (k in 0 until e.pointerCount) { val p = ptrs[e.getPointerId(k)] ?: continue; p[0] = e.getX(k) / dens.toDouble(); p[1] = e.getY(k) / dens.toDouble() }
                if (gs.mode == "pinch") {
                    if (ptrs.size < 2) return true
                    if (!gs.moved) { gs.moved = true; busy = true }
                    val (mid, dist) = fingers(); val n = now()
                    val k = max(1.0, min(MAX_K, gs.k0 * (dist / gs.dist0)))
                    if (gs.lt > 0) { val d = max(8.0, n - gs.lt); gs.zv = gs.zv * 0.6 + (0.4 * (ln(k) - ln(gs.lk))) / d }
                    gs.lt = n; gs.lk = k
                    zoomToward(k, mid[0], mid[1], hold = true, tau = 85.0, g = if (zt != null) null else gs.pg)
                    return true
                }
                if (ptrs.size != 1 || prev1 == null) return true
                val cur = ptrs.values.first()
                val dx = cur[0] - prev1[0]; val dy = cur[1] - prev1[1]
                gs.travel += abs(dx) + abs(dy)
                val n = now(); val dt = max(8.0, n - gs.t)
                gs.vx = gs.vx * 0.5 + (dx / dt) * 0.5; gs.vy = gs.vy * 0.5 + (dy / dt) * 0.5; gs.t = n
                if (gs.travel <= 6 && !gs.moved) return true
                if (!gs.moved) { gs.moved = true; busy = true }
                if (zt != null) { gs.g0 = null; return true }
                turnTo(cur, prev1)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(i)
                val upPos = ptrs.remove(id)
                if (gs.mode == "pinch") {
                    val z = zt
                    if (ptrs.size == 1) { if (z != null) { z.hold = false; z.tau = 90.0 }; startTurn(ptrs.values.first()) }
                    else if (ptrs.size == 2) startPinch()
                    else if (z != null) {
                        z.hold = false; z.tau = 90.0
                        z.vel = if (now() - gs.lt < 80) max(-0.003, min(0.003, gs.zv)) else 0.0
                        if (abs(z.vel) < 4e-4) z.vel = 0.0
                    } else settle(if (gs.moved) 120 else 0)
                    return true
                }
                if (ptrs.isNotEmpty()) return true
                if (e.actionMasked == MotionEvent.ACTION_UP && !gs.moved && upPos != null) { tapAt(upPos[0], upPos[1]); }
                if (zt != null) return true
                // after a flick the Earth keeps turning a little and slows down smoothly
                if (gs.moved && hypot(gs.vx, gs.vy) > 0.3 && now() - gs.t < 90) {
                    var vx = gs.vx * 15; var vy = gs.vy * 15
                    val sp = hypot(vx, vy); if (sp > 55) { vx *= 55 / sp; vy *= 55 / sp }
                    loop { _ ->
                        val v = view; val r = radius(v.k)
                        put(V(v.lon - (vx / r) / D2R, v.lat + (vy / r) / D2R, v.k))
                        vx *= 0.92; vy *= 0.92
                        if (hypot(vx, vy) <= 0.35) { frame = null; settle() }
                    }
                    return true
                }
                settle(if (gs.moved) 120 else 0)
            }
        }
        return true
    }

    private fun tapAt(x: Double, y: Double) {
        // a pin first (the one drawn on top), then a double tap zooms in where you tapped
        val hit = top.pinAt(x, y)
        if (hit != null) { tapCluster(hit); return }
        val t = System.currentTimeMillis()
        if (t - lastTap < 320 && hypot(x - lastTapX, y - lastTapY) < 30) { lastTap = 0; zoomSoft(2.0, doubleArrayOf(x, y)) }
        else { lastTap = t; lastTapX = x; lastTapY = y }
    }

    // ═════════════ the Earth (one picture, redrawn while it moves) ═════════════
    private inner class Globe(ctx: Context) : View(ctx) {
        private val path = Path()
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val m = Matrix()
        private val atm = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(0, 124, 77, 255), Color.argb(0, 124, 77, 255), Color.argb(204, 196, 173, 255), Color.argb(82, 124, 77, 255), Color.argb(0, 124, 77, 255)),
            floatArrayOf(0f, .84f, .877f, .92f, 1f), Shader.TileMode.CLAMP)
        private val sea = RadialGradient(0f, 0f, 1f, intArrayOf(Ui.c("#4b30b0"), Ui.c("#25166c"), Ui.c("#100a3a"), Ui.c("#060320")), floatArrayOf(0f, .35f, .7f, 1f), Shader.TileMode.CLAMP)
        private val shade = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(0, 2, 0, 8), Color.argb(0, 2, 0, 8), Color.argb(107, 2, 0, 8), Color.argb(199, 2, 0, 8)), floatArrayOf(0f, .66f, .9f, 1f), Shader.TileMode.CLAMP)
        private val glint = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(97, 232, 220, 255), Color.argb(31, 179, 146, 255), Color.argb(0, 179, 146, 255)), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        private val rim = LinearGradient(0f, -1f, 0f, 1f, intArrayOf(Ui.c("#f4ecff"), Color.argb(179, 179, 146, 255), Color.argb(38, 124, 77, 255), Color.argb(13, 124, 77, 255)), floatArrayOf(0f, .3f, .65f, 1f), Shader.TileMode.CLAMP)
        private val side = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(Ui.c("#ffe3a6"), Ui.c("#d99a3c"), Ui.c("#6e3a10")), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        private val topG = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(Ui.c("#4e28a6"), Ui.c("#2f1470"), Ui.c("#1c0a48")), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        private var waves: BitmapShader? = null
        private val o = DoubleArray(5)
        private var pts = FloatArray(512)

        private fun shaderAt(s: Shader, x: Double, y: Double, r: Double): Shader { m.reset(); m.setScale(r.toFloat(), r.toFloat()); m.postTranslate(x.toFloat(), y.toFloat()); s.setLocalMatrix(m); return s }

        override fun onDraw(c: Canvas) {
            if (bw <= 0) return
            c.save(); c.scale(dens, dens)
            paintGlobe(c)
            c.restore()
        }

        private fun paintGlobe(c: Canvas) {
            val v = view
            val r = radius(v.k); val x0 = cx(); val y0 = cy()
            val P = Proj(v.lon, v.lat, r, x0, y0)
            val moving = busy
            val rich = !LITE && !moving
            val fine = !moving
            val sd = min(2.2, max(0.7, r / 420)).toFloat()
            val dd = min(1.25, max(0.7, r / 520)).toFloat()
            val rf = r.toFloat(); val xf = x0.toFloat(); val yf = y0.toFloat()
            // atmosphere + ocean
            fillP.reset(); fillP.isAntiAlias = true
            fillP.shader = shaderAt(atm, x0, y0, r * 1.14); c.drawCircle(xf, yf, rf * 1.14f, fillP)
            fillP.shader = shaderAt(sea, x0 - r * 0.35, y0 - r * 0.45, r * 1.55); c.drawCircle(xf, yf, rf, fillP)
            if (rich) {
                val w = wavesShader()
                m.reset(); m.setScale(1f / dens, 1f / dens)
                m.postTranslate(modp(-v.lon * D2R * r * cos(v.lat * D2R), 46.0).toFloat(), modp(v.lat * D2R * r, 16.0).toFloat())
                w.setLocalMatrix(m)
                fillP.shader = w; fillP.alpha = 128; c.drawCircle(xf, yf, rf, fillP); fillP.alpha = 255
            }
            // the glint of light on the sea
            c.save()
            val gx = (x0 - r * 0.32).toFloat(); val gy = (y0 - r * 0.42).toFloat()
            c.rotate(-24f, gx, gy); c.translate(gx, gy); c.scale((r * 0.42).toFloat(), (r * 0.22).toFloat())
            glint.setLocalMatrix(null); fillP.shader = glint; fillP.alpha = 191; c.drawCircle(0f, 0f, 1f, fillP); fillP.alpha = 255
            c.restore()
            fillP.shader = null
            if (!SdMapData.ready) { drawShadeRim(c, x0, y0, r); return }
            val q = geoBox(v)
            if (fine) { line(SdMapData.graticule, q, P, false); stroke(c, Color.argb(36, 185, 162, 255), 0.8f) }
            // the continents at night
            fill(worldRings(r), q, P, r, x0, y0)
            fillP.color = Color.argb(235, 42, 28, 102); c.drawPath(path, fillP)
            stroke(c, Color.argb(97, 165, 140, 255), 0.8f)
            // India's border shape (a thin channel of sea around it)
            fill(borderRings(r), q, P, r, x0, y0)
            val india = Path(path)
            val hasIndia = !india.isEmpty
            if (hasIndia) {
                fillP.shader = shaderAt(sea, x0 - r * 0.35, y0 - r * 0.45, r * 1.55); c.drawPath(india, fillP)
                strokeP.shader = fillP.shader; strokeP.strokeWidth = 5f; strokeP.color = Color.BLACK; c.drawPath(india, strokeP); strokeP.shader = null
                fillP.shader = null
            }
            if (rich) dots(c, SdMapData.worldLights, P, 0.1, { it % 3 != 0 }, Color.argb(56, 255, 184, 92), 4 * dd)
            dots(c, SdMapData.worldLights, P, 0.06, null, Color.argb(191, 255, 217, 160), 1.3f * dd)
            if (hasIndia) {
                // India — raised, glowing, with a gold edge
                c.save(); c.translate(0f, 9 * sd)
                fillP.color = Color.argb(77, 124, 58, 237); c.drawPath(india, fillP)
                if (rich) { strokeP.color = Color.argb(56, 139, 92, 246); strokeP.strokeWidth = 16 * sd; c.drawPath(india, strokeP) }
                c.restore()
                val b = RectF(); india.computeBounds(b, true)
                c.save(); c.translate(0f, 4 * sd)
                m.reset(); m.setScale(b.width().coerceAtLeast(1f), b.height().coerceAtLeast(1f)); m.postTranslate(b.left, b.top); side.setLocalMatrix(m)
                fillP.shader = side; c.drawPath(india, fillP); fillP.shader = null
                strokeP.color = Ui.c("#6e3a10"); strokeP.strokeWidth = 1.2f * sd; c.drawPath(india, strokeP)
                c.restore()
                strokeP.color = Ui.c("#ffdca0"); strokeP.strokeWidth = 3 * sd; c.drawPath(india, strokeP)
                topG.setLocalMatrix(m.also { it.reset(); it.setScale(b.width().coerceAtLeast(1f), b.height().coerceAtLeast(1f)); it.postTranslate(b.left, b.top) })
                fillP.shader = topG; c.drawPath(india, fillP); fillP.shader = null
                if (fine && r > 1500) {
                    line(SdMapData.roads, q, P, false); stroke(c, Ui.c("#cdb8ff"), min(1.6, 0.5 + r / 9000).toFloat(), min(0.35, 0.12 + r / 40000).toFloat())
                    line(SdMapData.highways, q, P, false); stroke(c, Ui.c("#f3cf8a"), min(2.6, 0.8 + r / 7000).toFloat(), min(0.5, 0.2 + r / 30000).toFloat())
                }
                if (fine && r > 520) { line(SdMapData.rivers, q, P, false); stroke(c, Ui.c("#9cc4ff"), min(1.8, 0.5 + r / 3000).toFloat(), min(0.55, 0.2 + r / 6000).toFloat()) }
                line(stateRings(r), q, P, false); stroke(c, Ui.c("#f3ecff"), min(1.1, 0.5 + r / 1500).toFloat(), 0.45f)
                if (fine && r <= 900) { line(SdMapData.netLines, null, P, false); stroke(c, Ui.c("#d9b8ff"), 0.6f, 0.3f) }
                if (rich && r <= 900) dots(c, SdMapData.netGeo, P, 0.05, { it % 2 != 0 }, Color.argb(89, 255, 179, 71), 4.5f * dd)
                if (r <= 900) dots(c, SdMapData.netGeo, P, 0.06, null, Color.argb(204, 255, 226, 166), 1.4f * dd)
                if (rich) dots(c, SdMapData.cityGeo, P, 0.06, null, Color.argb(102, 255, 198, 107), 7 * dd)
                dots(c, SdMapData.cityGeo, P, 0.06, null, Ui.c("#fff1cf"), 2.2f * dd)
            }
            drawShadeRim(c, x0, y0, r)
        }

        private fun drawShadeRim(c: Canvas, x0: Double, y0: Double, r: Double) {
            // night-side shading + bright rim
            fillP.shader = shaderAt(shade, x0, y0, r); c.drawCircle(x0.toFloat(), y0.toFloat(), r.toFloat(), fillP); fillP.shader = null
            m.reset(); m.setScale(1f, r.toFloat()); m.postTranslate(x0.toFloat(), y0.toFloat()); rim.setLocalMatrix(m)
            strokeP.shader = rim; strokeP.strokeWidth = 1.8f; c.drawCircle(x0.toFloat(), y0.toFloat(), r.toFloat(), strokeP); strokeP.shader = null
        }

        private fun stroke(c: Canvas, color: Int, width: Float, alpha: Float = 1f) {
            if (path.isEmpty) return
            strokeP.shader = null; strokeP.color = color; if (alpha < 1f) strokeP.alpha = (Color.alpha(color) * alpha).toInt()
            strokeP.strokeWidth = width; c.drawPath(path, strokeP)
        }

        private fun wavesShader(): BitmapShader {
            waves?.let { return it }
            val s = dens
            val bmp = Bitmap.createBitmap((46 * s).toInt().coerceAtLeast(1), (16 * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp); cv.scale(s, s)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            p.color = Color.argb(56, 200, 182, 255); p.strokeWidth = 0.8f
            cv.drawPath(androidx.core.graphics.PathParser.createPathFromPathData("M0 9C6 5 11 5 17 9S29 13 35 9 43 5 46 7"), p)
            p.color = Color.argb(36, 230, 220, 255); p.strokeWidth = 0.6f
            cv.drawPath(androidx.core.graphics.PathParser.createPathFromPathData("M-6 2C0 0 5 0 10 2"), p)
            p.color = Color.argb(31, 230, 220, 255)
            cv.drawPath(androidx.core.graphics.PathParser.createPathFromPathData("M24 14C30 12 35 12 40 14"), p)
            return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { waves = it }
        }

        // filled shapes: the far side folds onto the edge of the Earth
        private fun fill(src: SdMapData.Rings, q: DoubleArray?, P: Proj, r: Double, x0: Double, y0: Double) {
            path.rewind()
            for (ri in src.rings.indices) {
                val bb = src.bb[ri]
                var ring = src.rings[ri]
                if (q != null) {
                    if (outside(bb, q)) continue
                    if (!within(bb, q)) { ring = clipRing(ring, q); if (ring.size <= 4) continue }
                }
                fillRing(ring, P, r, x0, y0)
            }
        }
        private fun fillRing(ring: FloatArray, P: Proj, r: Double, x0: Double, y0: Double) {
            val n = ring.size / 2
            if (pts.size < n * 5) pts = FloatArray(n * 5 + 64)
            var any = false
            for (i in 0 until n) { P.at(ring[2 * i].toDouble(), ring[2 * i + 1].toDouble(), o); pts[5 * i] = o[0].toFloat(); pts[5 * i + 1] = o[1].toFloat(); pts[5 * i + 2] = o[2].toFloat(); pts[5 * i + 3] = o[3].toFloat(); pts[5 * i + 4] = o[4].toFloat(); if (o[2] > 0) any = true }
            if (!any) return
            var prevBack = Double.NaN; var lx = 0f; var ly = 0f
            val oval = RectF((x0 - r).toFloat(), (y0 - r).toFloat(), (x0 + r).toFloat(), (y0 + r).toFloat())
            for (i in 0 until n) {
                val px = pts[5 * i]; val py = pts[5 * i + 1]; val pz = pts[5 * i + 2]
                if (pz > 0) {
                    if (i > 0 && prevBack.isNaN() && i < n - 1 && abs(px - lx) + abs(py - ly) < 0.7f) continue
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    prevBack = Double.NaN; lx = px; ly = py
                    continue
                }
                val ux = pts[5 * i + 3].toDouble(); val uy = pts[5 * i + 4].toDouble()
                val rr = hypot(ux, uy).takeIf { it > 0 } ?: 1.0
                val X = x0 + (r * ux) / rr; val Y = y0 - (r * uy) / rr; val ang = atan2(Y - y0, X - x0)
                if (!prevBack.isNaN() && i > 0) {
                    var da = ang - prevBack
                    while (da > Math.PI) da -= 2 * Math.PI
                    while (da < -Math.PI) da += 2 * Math.PI
                    path.arcTo(oval, Math.toDegrees(prevBack).toFloat(), Math.toDegrees(da).toFloat(), false)
                } else if (i == 0) path.moveTo(X.toFloat(), Y.toFloat()) else path.lineTo(X.toFloat(), Y.toFloat())
                prevBack = ang
            }
            path.close()
        }

        // lines: only the part facing you
        private fun line(src: SdMapData.Rings, q: DoubleArray?, P: Proj, closed: Boolean) {
            path.rewind()
            for (ri in src.rings.indices) {
                val ring = src.rings[ri]
                if (q != null) {
                    val bb = src.bb[ri]
                    if (outside(bb, q)) continue
                    if (!within(bb, q)) { runsInside(ring, q) { a, from, to -> lineRun(a, from, to, P, false) }; continue }
                }
                lineRun(ring, 0, ring.size / 2, P, closed)
            }
        }
        private fun lineRun(r: FloatArray, from: Int, to: Int, P: Proj, closed: Boolean) {
            var on = false
            val len = to - from
            val n = len + (if (closed) 1 else 0)
            var lx = 0.0; var ly = 0.0
            for (i in 0 until n) {
                val k = from + (i % len)
                P.at(r[2 * k].toDouble(), r[2 * k + 1].toDouble(), o)
                if (o[2] > 0.02) {
                    if (on && i < n - 1 && abs(o[0] - lx) + abs(o[1] - ly) < 0.9) continue
                    if (on) path.lineTo(o[0].toFloat(), o[1].toFloat()) else path.moveTo(o[0].toFloat(), o[1].toFloat())
                    on = true; lx = o[0]; ly = o[1]
                } else on = false
            }
        }

        // many tiny dots (round caps on zero-length lines)
        private fun dots(c: Canvas, src: FloatArray, P: Proj, minZ: Double, skip: ((Int) -> Boolean)?, color: Int, width: Float) {
            val n = src.size / 2
            if (pts.size < n * 2) pts = FloatArray(n * 2 + 64)
            var k = 0
            for (i in 0 until n) {
                if (skip != null && skip(i)) continue
                P.at(src[2 * i].toDouble(), src[2 * i + 1].toDouble(), o)
                if (o[2] > minZ) { pts[k++] = o[0].toFloat(); pts[k++] = o[1].toFloat() }
            }
            if (k == 0) return
            dotP.color = color; dotP.strokeWidth = width
            c.drawPoints(pts, 0, k, dotP)
        }
    }

    // ═════════════ stars that stream past as the Earth turns ═════════════
    private inner class Stars(ctx: Context) : View(ctx) {
        private val bg = Paint()
        private val glow = Paint()
        private var tileA: Bitmap? = null; private var tileB: Bitmap? = null
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shoot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t0 = System.currentTimeMillis()
        private fun tile(n: Int, size: Int, seed: Int, big: Boolean): Bitmap {
            val s = dens
            val b = Bitmap.createBitmap((size * s).toInt(), (size * s).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.scale(s, s)
            var x = seed.toLong()
            fun r(): Double { x = (x * 16807) % 2147483647; return x / 2147483647.0 }
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until n) {
                val cxp = r() * size; val cyp = r() * size
                val rad = if (big && i % 5 == 0) 1.3 else 0.6 + r() * 0.5
                q.color = if (i % 4 != 0) Ui.c("#f1eaff") else Ui.c("#ffe6b8")
                q.alpha = ((0.45 + r() * 0.55) * 255).toInt()
                c.drawCircle(cxp.toFloat(), cyp.toFloat(), rad.toFloat(), q)
            }
            return b
        }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Ui.c("#06021a"), Ui.c("#040112"), Ui.c("#030009")), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            // radial-gradient(60% 40% at 50% 45%, rgba(110,60,220,.22), transparent 70%)
            glow.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(56, 110, 60, 220), Color.argb(0, 110, 60, 220)), floatArrayOf(0f, .7f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(w * 0.6f, h * 0.4f); postTranslate(w * 0.5f, h * 0.45f) })
            }
        }
        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
            val a = tileA ?: tile(26, 260, 11, true).also { tileA = it }
            val b = tileB ?: if (!LITE) tile(18, 190, 97, false).also { tileB = it } else null
            val now = System.currentTimeMillis() - t0
            val anim = !REDUCED
            fun layer(bm: Bitmap, size: Float, offX: Double, offY: Double, drift: Double, alpha: Int) {
                val sp = size * dens
                var x0 = ((offX - drift) % size).let { if (it > 0) it - size else it } * dens
                val y0 = (offY % size).let { if (it > 0) it - size else it } * dens
                p.alpha = alpha
                var y = y0.toFloat()
                while (y < height) { var x = x0.toFloat(); while (x < width) { c.drawBitmap(bm, x, y, p); x += sp }; y += sp }
            }
            layer(a, 260f, modp(-view.lon * 10, 260.0), modp(view.lat * 10, 260.0), if (anim) (now % 20000) / 20000.0 * 260 else 0.0, 255)
            if (b != null) {
                val tw = if (anim) 0.45 + 0.55 * (0.5 - 0.5 * cos(2 * Math.PI * (now % 2400) / 2400.0)) else 1.0
                layer(b, 190f, modp(-view.lon * 5, 190.0), modp(view.lat * 5, 190.0), if (anim) (now % 12000) / 12000.0 * 190 else 0.0, (tw * 255).toInt())
            }
            // the odd shooting star
            if (anim) {
                shootingStar(c, now - 1200, 5500, 0.26f, 0.08f, 110f, 1.6f, Color.WHITE)
                shootingStar(c, now - 4600, 11000, 0.08f, 0.30f, 80f, 1.2f, Ui.c("#f3e6ff"))
                if (onScreen()) postInvalidateOnAnimation()
            }
        }
        private fun shootingStar(c: Canvas, t: Long, dur: Long, lx: Float, ty: Float, len: Float, th: Float, color: Int) {
            if (t < 0) return
            val f = (t % dur).toFloat() / dur
            if (f > 0.1f) return
            val e = 1 - (1 - f / 0.1f).let { it * it }       // ease-out over the first 10 %
            val op = if (f < 0.02f) f / 0.02f else 1f - (f - 0.02f) / 0.08f
            val x = lx * width; val y = ty * height
            c.save(); c.translate(x, y); c.rotate(24f); c.translate(260 * dens * e, 0f)
            shoot.shader = LinearGradient(0f, 0f, len * dens, 0f, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), color, Shader.TileMode.CLAMP)
            shoot.alpha = (op.coerceIn(0f, 1f) * 255).toInt()
            c.drawRoundRect(0f, 0f, len * dens, th * dens, dens, dens, shoot)
            c.restore()
        }
    }

    // ═════════════ the glowing centre of India, place names, people ═════════════
    private inner class Top(ctx: Context) : View(ctx) {
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val o = DoubleArray(5)
        private val t0 = System.currentTimeMillis()
        private val hubG = RadialGradient(0f, 0f, 1f, intArrayOf(Ui.c("#fff3cf"), Ui.c("#ffb547"), Color.argb(0, 255, 150, 40)), floatArrayOf(0f, .45f, .72f), Shader.TileMode.CLAMP)
        private val baseG = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(179, 167, 112, 255), Color.argb(64, 124, 58, 237), Color.argb(0, 124, 58, 237)), floatArrayOf(0f, .55f, .72f), Shader.TileMode.CLAMP)
        private val pinGlow = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(140, 167, 112, 255), Color.argb(0, 167, 112, 255)), floatArrayOf(0f, .7f), Shader.TileMode.CLAMP)
        private val coreG = RadialGradient(0f, 0f, 1f, intArrayOf(Ui.c("#ffe4a8"), Color.argb(150, 255, 190, 90), Color.argb(0, 255, 190, 90)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        private val m = Matrix()
        private inner class Shown(val c: Cluster, val x: Double, val y: Double, val s: Double)
        private var shown: List<Shown> = emptyList()
        private val loading = HashSet<String>()

        fun pinAt(x: Double, y: Double): Cluster? {
            for (sh in shown.asReversed()) {
                val l = sh.x - 28 * sh.s; val t = sh.y - 80 * sh.s
                if (x >= l && x <= l + 56 * sh.s && y >= t && y <= t + 96 * sh.s) return sh.c
            }
            return null
        }

        override fun onDraw(c: Canvas) {
            if (bw <= 0) return
            c.save(); c.scale(dens, dens)
            val v = view; val r = radius(v.k); val P = Proj(v.lon, v.lat, r, cx(), cy()); val ps = pinScaleOf(r)
            val now = System.currentTimeMillis() - t0
            var animating = false
            // glowing centre of India
            if (SdMapData.ready) {
                P.at(SdMapData.hub[0].toDouble(), SdMapData.hub[1].toDouble(), o)
                if (o[2] >= 0.2) {
                    val s = (60 * ps).toFloat(); val hx = o[0].toFloat(); val hy = o[1].toFloat()
                    m.reset(); m.setScale(0.2828f * s, 0.2828f * s); m.postTranslate(hx, hy); hubG.setLocalMatrix(m)
                    gp.shader = hubG; c.drawCircle(hx, hy, 0.2f * s, gp); gp.shader = null
                    if (!REDUCED) {
                        for (d in longArrayOf(0, 1200)) {
                            val f = ((now - d).coerceAtLeast(0) % 2400) / 2400f
                            val e = 1 - (1 - f) * (1 - f)
                            val sc = 0.4f + 1.4f * e; val op = 0.95f * (1 - e)
                            sp.color = Color.argb((op * (if (d == 0L) 204 else 153)).toInt(), 255, 190, 90); sp.strokeWidth = 1.5f * sc
                            c.drawCircle(hx, hy, s / 2 * sc, sp)
                        }
                        animating = true
                    }
                }
            }
            // continents, oceans and neighbours
            for (n in PLACE_NAMES) {
                if (n.kind == 'c' && r > 1300) continue
                if (n.kind == 'n' && r < 620) continue
                if (n.kind == 'o' && n.t != "Indian Ocean" && n.t != "Pacific Ocean" && n.t != "Atlantic Ocean" && r < 380) continue
                P.at(n.lng, n.lat, o)
                if (o[2] < 0.18 || o[0] < -80 || o[1] < -20 || o[0] > bw + 80 || o[1] > bh + 20) continue
                val op = min(1.0, (o[2] - 0.18) / 0.3).toFloat()
                when (n.kind) {
                    'c' -> text(c, n.t, o[0], o[1], if (r > 600) 15f else 12.5f, Ui.bold, 0.22f, Color.argb((0.78f * op * 255).toInt(), 246, 222, 170), 6f, 0.95f)
                    'o' -> text(c, n.t, o[0], o[1], if (r > 600) 14f else 12f, Ui.boldItalic, 0.04f, Color.argb((0.62f * op * 255).toInt(), 190, 170, 255), 6f, 0.95f)
                    else -> text(c, n.t, o[0], o[1], 10.5f, Ui.bold, 0.06f, Color.argb((0.55f * op * 255).toInt(), 220, 210, 255), 6f, 0.95f)
                }
            }
            // place names as you zoom in: big cities, then states, then smaller cities
            if (!(LITE && busy)) {
                val t = System.currentTimeMillis()
                for (l in labels) {
                    P.at(l.lng, l.lat, o)
                    if (o[2] < 0.25 || o[0] < -160 || o[1] < -40 || o[0] > bw + 160 || o[1] > bh + 40) continue
                    val fade = if (REDUCED) 1f else min(1f, (t - (labelBorn[l.key] ?: 0L)) / 350f)
                    if (fade < 1f) animating = true
                    if (l.st) { text(c, l.n, o[0], o[1], l.fs, Ui.bold, 0.05f, Color.argb((0.66f * fade * 255).toInt(), 240, 230, 255), 4f, 0.9f); continue }
                    val ds = if (l.big) 7f else 5f
                    val dx = (o[0] - 4 + ds / 2).toFloat(); val dy = o[1].toFloat()
                    gp.color = if (l.big) Ui.c("#ffe2a6") else Ui.c("#f1e8ff"); gp.alpha = (fade * 255).toInt()
                    gp.setShadowLayer(if (l.big) 8f else 5f, 0f, 0f, if (l.big) Color.argb((0.95f * fade * 255).toInt(), 255, 200, 110) else Color.argb((0.9f * fade * 255).toInt(), 200, 170, 255))
                    c.drawCircle(dx, dy, ds / 2, gp); gp.clearShadowLayer()
                    sp.color = Color.argb((0.6f * fade * 255).toInt(), 20, 8, 40); sp.strokeWidth = 1f; c.drawCircle(dx, dy, ds / 2 - 0.5f, sp)
                    tp.reset(); tp.isAntiAlias = true
                    tp.typeface = if (l.big) Ui.bold else Ui.bold; tp.textSize = l.fs; tp.letterSpacing = 0.02f
                    tp.color = if (l.big) Color.argb((0.95f * fade * 255).toInt(), 255, 236, 200) else Color.argb((0.85f * fade * 255).toInt(), 236, 228, 255)
                    tp.setShadowLayer(3f, 0f, 1f, Color.argb((0.95f * fade * 255).toInt(), 0, 0, 0))
                    val fm = tp.fontMetrics
                    c.drawText(l.n, (o[0] - 4 + ds + 4).toFloat(), (o[1] - (fm.ascent + fm.descent) / 2).toFloat(), tp)
                }
            }
            // people
            val list = ArrayList<Shown>()
            for (cl in clusters) {
                P.at(cl.geo[0], cl.geo[1], o)
                if (o[2] <= 0.08) continue
                if (o[0] < -8 - 30 || o[1] < -8 || o[0] > bw + 8 + 30 || o[1] > bh + 8 + 80) continue
                list.add(Shown(cl, o[0], o[1], o[2]))
            }
            list.sortBy { it.y }
            val showCity = r > 900
            val out = ArrayList<Shown>()
            for (sh in list) {
                val fade = if (sh.s < 0.3) max(0.25, sh.s / 0.3).toFloat() else 1f
                if (drawPin(c, sh.c, sh.x, sh.y, ps, fade, showCity, now)) animating = true
                out.add(Shown(sh.c, sh.x, sh.y, ps))
            }
            shown = out
            c.restore()
            if (animating && onScreen()) postInvalidateOnAnimation()
        }

        private fun text(c: Canvas, s: String, x: Double, y: Double, size: Float, tf: android.graphics.Typeface, spacing: Float, color: Int, shadow: Float, shadowA: Float) {
            tp.reset(); tp.isAntiAlias = true; tp.typeface = tf; tp.textSize = size; tp.letterSpacing = spacing; tp.color = color; tp.textAlign = Paint.Align.CENTER
            tp.setShadowLayer(shadow, 0f, 1f, Color.argb((shadowA * Color.alpha(color)).toInt(), 0, 0, 0))
            val fm = tp.fontMetrics
            c.drawText(s, x.toFloat(), (y - (fm.ascent + fm.descent) / 2).toFloat(), tp)
        }

        /** one pin: ripples on the ground, the breathing glow, the photo in a gold ring. true = it is animating */
        private fun drawPin(c: Canvas, cl: Cluster, x: Double, y: Double, ps: Double, fade: Float, showCity: Boolean, now: Long): Boolean {
            val live = cl.people.any { SdRules.liveOf(it) }
            c.save()
            c.translate((x - 28).toFloat(), (y - 80).toFloat())
            if (ps != 1.0) c.scale(ps.toFloat(), ps.toFloat(), 28f, 80f)
            val a = (fade * 255).toInt()
            // ripples on the ground
            val groupA = if (live) 1f else 0.45f
            m.reset(); m.setScale(32f * 1.414f, 12f * 1.414f); m.postTranslate(28f, 78f); baseG.setLocalMatrix(m)
            gp.shader = baseG; gp.alpha = (a * groupA).toInt(); c.drawOval(-4f, 66f, 60f, 90f, gp); gp.shader = null; gp.alpha = 255
            var anim = false
            if (live && !REDUCED) {
                for ((d, al) in listOf(0L to 1f, 900L to 0.9f)) {
                    val f = (now - d).coerceAtLeast(0) % 1800 / 1800f
                    val e = 1 - (1 - f) * (1 - f)
                    val sc = 0.35f + 1.15f * e; val op = (1 - e) * al * fade
                    sp.color = Color.argb((op * 255).toInt(), 200, 160, 255); sp.strokeWidth = 2f * sc
                    c.drawOval(28 - 24 * sc, 78 - 8 * sc, 28 + 24 * sc, 78 + 8 * sc, sp)
                    sp.color = Color.argb((op * 90).toInt(), 167, 112, 255); sp.strokeWidth = 6f * sc
                    c.drawOval(28 - 24 * sc, 78 - 8 * sc, 28 + 24 * sc, 78 + 8 * sc, sp)
                }
                anim = true
            }
            m.reset(); m.setScale(13f, 9f); m.postTranslate(28f, 78f); coreG.setLocalMatrix(m)
            gp.shader = coreG; gp.alpha = (a * groupA).toInt(); c.drawOval(15f, 69f, 41f, 87f, gp); gp.shader = null; gp.alpha = 255
            // glow that breathes (live)
            if (live) {
                val br = if (REDUCED) 1f else 0.35f + 0.65f * (0.5f - 0.5f * cos(2 * Math.PI * (now % 1600) / 1600.0).toFloat())
                m.reset(); m.setScale(34f * 1.414f, 34f * 1.414f); m.postTranslate(28f, 28f); pinGlow.setLocalMatrix(m)
                gp.shader = pinGlow; gp.alpha = (a * br).toInt(); c.drawCircle(28f, 28f, 34f, gp); gp.shader = null; gp.alpha = 255
                anim = anim || !REDUCED
            }
            // the photo in a gold ring, its dot, the gold tail, badges and the city
            val sp1 = sprite(cl, live, showCity)
            if (sp1 != null) { tp.reset(); tp.isFilterBitmap = true; tp.alpha = a; c.drawBitmap(sp1.bmp, null, RectF(sp1.l, sp1.t, sp1.l + sp1.w, sp1.t + sp1.h), tp) }
            c.restore()
            return anim
        }

        private inner class Sprite(val bmp: Bitmap, val l: Float, val t: Float, val w: Float, val h: Float)
        private val sprites = LinkedHashMap<String, Sprite>()

        /** the still part of a pin (photo in its gold ring, the dot, the tail, badges, the city) as one small picture */
        private fun sprite(cl: Cluster, live: Boolean, showCity: Boolean): Sprite? {
            val p = cl.people.firstOrNull { SdRules.liveOf(it) } ?: cl.people[0]
            val pid = p.optString("id")
            val chat = cl.people.any { it.optBoolean("chatting") }
            val key = "${cl.key}|${cl.people.size}|$live|$showCity|$chat|${cl.city}|$pid"
            sprites[key]?.let { return it }
            val raws = listOf(SdRules.s(p, "photo_thumb"), SdRules.s(p, "photo", "profile_picture")).filter { it.isNotBlank() }
            val px = Ui.dpi(46f).coerceAtLeast(48)
            val face = Demo.round(p, px) ?: Photos.cached(raws, px)
            if (face == null && raws.isNotEmpty() && loading.add("photo|$pid")) Photos.loadRaw(raws, px) { _ -> sprites.keys.removeAll { it.endsWith("|$pid") }; invalidate() }
            val tq = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold }
            tq.textSize = 10.5f; val chatW = 3f + 5f + 11f + 3f + tq.measureText("Chatting") + 7f
            tq.textSize = 12f; tq.isFakeBoldText = true; val count = cl.people.size.toString(); val countW = max(24f, tq.measureText(count) + 12f)
            tq.textSize = 11f; tq.isFakeBoldText = false; val cityW = tq.measureText(cl.city) + 16f
            val withCity = showCity && cl.city.isNotEmpty()
            // the picture's box around the pin (0,0 = the pin's top-left; it is 56 wide)
            var l = -8f; var r = 64f; var t = -2f; var b = 78f
            if (chat) { l = min(l, -14f); r = max(r, -12f + chatW + 2f); t = min(t, -10f) }
            if (cl.people.size > 1) { r = max(r, 68f); t = min(t, -8f) }
            if (withCity) { l = min(l, 28f - cityW / 2 - 2f); r = max(r, 28f + cityW / 2 + 2f); b = 112f }
            val w = r - l; val h = b - t
            val bm = Bitmap.createBitmap((w * dens).toInt().coerceAtLeast(1), (h * dens).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bm); cv.scale(dens, dens); cv.translate(-l, -t)
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            // shadow and the purple ring around the gold ring
            q.color = Color.argb(140, 0, 0, 0); q.maskFilter = android.graphics.BlurMaskFilter(7f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            cv.drawCircle(28f, 34f, 28f, q); q.maskFilter = null
            q.color = Color.argb(89, 139, 63, 224); cv.drawCircle(28f, 28f, 31f, q)
            val gdx = (35.9 * sin(Math.toRadians(160.0))).toFloat(); val gdy = (-35.9 * cos(Math.toRadians(160.0))).toFloat()
            q.shader = LinearGradient(28f - gdx, 28f - gdy, 28f + gdx, 28f + gdy, intArrayOf(Ui.c("#ffe7a8"), Ui.c("#e0a43c"), Ui.c("#a8661a")), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(28f, 28f, 28f, q); q.shader = null
            q.color = Ui.c("#20123a"); cv.drawCircle(28f, 28f, 25f, q)
            if (face != null) {
                val fp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                // offline: saturate(.75) brightness(.92)
                if (!live) fp.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.75f); postConcat(ColorMatrix().apply { setScale(0.92f, 0.92f, 0.92f, 1f) }) })
                cv.save(); cv.clipPath(Path().apply { addCircle(28f, 28f, 23f, Path.Direction.CW) })
                cv.drawBitmap(face, null, RectF(5f, 5f, 51f, 51f), fp); cv.restore()
            } else { cv.save(); cv.translate(5f, 5f); DefaultAvatar.draw(cv, 46f); cv.restore() }
            q.style = Paint.Style.STROKE; q.strokeWidth = 2f; q.color = Ui.c("#1a0b33"); cv.drawCircle(28f, 28f, 24f, q); q.style = Paint.Style.FILL
            // online / offline dot
            if (live) { q.color = Color.argb(230, 52, 211, 94); q.maskFilter = android.graphics.BlurMaskFilter(5f, android.graphics.BlurMaskFilter.Blur.NORMAL); cv.drawCircle(49.5f, 45.5f, 7.5f, q); q.maskFilter = null }
            q.shader = RadialGradient(47.25f, 42.5f, 14.3f, if (live) Ui.c("#7af08a") else Ui.c("#d4cfe0"), if (live) Ui.c("#16a34a") else Ui.c("#8c84a3"), Shader.TileMode.CLAMP)
            cv.drawCircle(49.5f, 45.5f, 7.5f, q); q.shader = null
            q.style = Paint.Style.STROKE; q.color = Ui.c("#12081f"); cv.drawCircle(49.5f, 45.5f, 6.5f, q); q.style = Paint.Style.FILL
            // gold tail
            q.shader = LinearGradient(0f, 54f, 0f, 76f, intArrayOf(Ui.c("#ffe7a8"), Ui.c("#e0a43c"), Ui.c("#b8741f")), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            cv.drawPath(Path().apply { moveTo(18f, 54f); lineTo(38f, 54f); lineTo(28f, 76f); close() }, q); q.shader = null
            if (chat) {
                tq.textSize = 10.5f; tq.color = Color.WHITE
                q.color = Color.argb(115, 0, 0, 0); q.maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL); cv.drawRoundRect(-12f, -6f, -12f + chatW, 14f, 10f, 10f, q); q.maskFilter = null
                q.color = Ui.c("#E3086C"); cv.drawRoundRect(-12f, -8f, -12f + chatW, 12f, 10f, 10f, q)
                q.style = Paint.Style.STROKE; q.strokeWidth = 1.5f; q.color = Color.WHITE; cv.drawRoundRect(-11.25f, -7.25f, -12.75f + chatW, 11.25f, 9.25f, 9.25f, q); q.style = Paint.Style.FILL
                cv.save(); cv.translate(-12f + 1.5f + 5f, -2.5f); Icon(Lucide.MessageCircleMore, Color.WHITE, 11f, 2.4f).apply { setBounds(0, 0, 11, 11) }.draw(cv); cv.restore()
                val fm = tq.fontMetrics; cv.drawText("Chatting", -12f + 1.5f + 5f + 11f + 3f, 2f - (fm.ascent + fm.descent) / 2, tq)
            }
            if (cl.people.size > 1) {
                tq.textSize = 12f; tq.isFakeBoldText = true; tq.color = Ui.c("#2a1600")
                val lx = 66f - countW
                q.color = Color.argb(128, 0, 0, 0); q.maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL); cv.drawRoundRect(lx, -4f, lx + countW, 18f, 11f, 11f, q); q.maskFilter = null
                q.shader = LinearGradient(0f, -6f, 0f, 16f, Ui.c("#ffe7a8"), Ui.c("#d99a35"), Shader.TileMode.CLAMP); cv.drawRoundRect(lx, -6f, lx + countW, 16f, 11f, 11f, q); q.shader = null
                tq.textAlign = Paint.Align.CENTER; val fm = tq.fontMetrics; cv.drawText(count, lx + countW / 2, 5f - (fm.ascent + fm.descent) / 2, tq); tq.textAlign = Paint.Align.LEFT
                tq.isFakeBoldText = false
            }
            if (withCity) {
                tq.textSize = 11f; tq.color = Ui.c("#efe6ff")
                val lx = 28f - cityW / 2
                q.color = Color.argb(199, 12, 6, 24); cv.drawRoundRect(lx, 92f, lx + cityW, 92f + 17f, 8f, 8f, q)
                q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = Color.argb(102, 186, 140, 255); cv.drawRoundRect(lx + .5f, 92.5f, lx + cityW - .5f, 92f + 16.5f, 7.5f, 7.5f, q); q.style = Paint.Style.FILL
                val fm = tq.fontMetrics; cv.drawText(cl.city, lx + 8f, 92f + 8.5f - (fm.ascent + fm.descent) / 2, tq)
            }
            while (sprites.size >= 40) sprites.remove(sprites.keys.first())
            return Sprite(bm, l, t, w, h).also { sprites[key] = it }
        }
    }

    // ═════════════ helpers ═════════════
    private fun mapButton(icon: String, size: Float, label: String, stroke: Float = 2f, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageDrawable(Icon(icon, GOLD_ICON, size, stroke))
        scaleType = ImageView.ScaleType.CENTER
        background = ctlBg(23f, .3f, .25f, .45f, .88f, .68f)
        elevation = Ui.dp(4f); outlineSpotShadowColorCompat(Color.argb(191, 139, 63, 224))
        contentDescription = label
        onTap { onClick() }
    }
    /** the dark round buttons: radial-gradient(circle at 30% 25%, rgba(139,63,224,.45), rgba(12,6,24,.88) 68%) and a gold hairline */
    private fun ctlBg(radiusDp: Float, fx: Float, fy: Float, a1: Float, a2: Float, stop: Float) = object : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(1f); color = Color.argb(166, 240, 200, 120) }
        override fun onBoundsChange(b: android.graphics.Rect) {
            val d = max(b.width(), b.height()).toFloat()
            p.shader = RadialGradient(b.left + b.width() * fx, b.top + b.height() * fy, d * 1.1f,
                intArrayOf(Color.argb((a1 * 255).toInt(), 139, 63, 224), Color.argb((a2 * 255).toInt(), 12, 6, 24), Color.argb((a2 * 255).toInt(), 12, 6, 24)), floatArrayOf(0f, stop, 1f), Shader.TileMode.CLAMP)
        }
        override fun draw(c: Canvas) {
            val b = RectF(bounds); val r = Ui.dp(radiusDp)
            c.drawRoundRect(b, r, r, p)
            b.inset(Ui.dp(0.5f), Ui.dp(0.5f)); c.drawRoundRect(b, r, r, s)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getOutline(o: android.graphics.Outline) { o.setRoundRect(bounds, Ui.dp(radiusDp)) }
    }

    private fun worldRings(r: Double): SdMapData.Rings { val ok = SdMapData.world.filter { it.tol * D2R * r < 1.6 }; return ok.lastOrNull() ?: SdMapData.world[0] }
    private fun borderRings(r: Double): SdMapData.Rings { val ok = SdMapData.border.filter { it.tol * D2R * r < 0.9 }; return ok.lastOrNull() ?: SdMapData.border[0] }
    private fun stateRings(r: Double): SdMapData.Rings { val ok = SdMapData.state.filter { it.tol * D2R * r < 1 }; return ok.lastOrNull() ?: SdMapData.state[0] }

    companion object {
        const val D2R = Math.PI / 180
        const val MAX_K = 96.0
        private const val HOME_LON = 80.5; private const val HOME_LAT = -10.0; private const val HOME_K = 2.4
        private val GOLD_ICON = Ui.c("#f6d89a")
        /** the 360° Earth spin plays once each time the app is opened, not on every visit to the map */
        var introDone = false
        /** lighter drawing on budget phones (few cores / little memory) or when the phone asks for less motion */
        val LITE: Boolean by lazy { Lite.on }
        val REDUCED: Boolean get() = Lite.reducedMotion

        private class Name(val t: String, val lng: Double, val lat: Double, val kind: Char)
        private val PLACE_NAMES = listOf(
            Name("ASIA", 96.0, 48.0, 'c'), Name("EUROPE", 18.0, 52.0, 'c'), Name("AFRICA", 20.0, 6.0, 'c'),
            Name("NORTH AMERICA", -100.0, 46.0, 'c'), Name("SOUTH AMERICA", -60.0, -14.0, 'c'), Name("AUSTRALIA", 134.0, -25.0, 'c'),
            Name("Indian Ocean", 80.0, -18.0, 'o'), Name("Arabian Sea", 64.5, 14.5, 'o'), Name("Bay of Bengal", 88.5, 14.5, 'o'),
            Name("Pacific Ocean", -150.0, 4.0, 'o'), Name("Pacific Ocean", 160.0, 18.0, 'o'), Name("Atlantic Ocean", -35.0, 12.0, 'o'),
            Name("Pakistan", 68.5, 28.5, 'n'), Name("China", 90.0, 34.5, 'n'), Name("Nepal", 84.0, 28.3, 'n'),
            Name("Bhutan", 90.5, 27.45, 'n'), Name("Bangladesh", 90.2, 23.9, 'n'), Name("Myanmar", 95.8, 21.5, 'n'),
            Name("Sri Lanka", 80.7, 7.6, 'n'), Name("Afghanistan", 66.0, 33.8, 'n'),
        )
    }
}

/** orthographic projection: [screen x, screen y, depth (z > 0 faces you), x, y] */
class Proj(lon0: Double, lat0: Double, val r: Double, val cx: Double, val cy: Double) {
    private val sp = sin(lat0 * GlobeMap.D2R); private val cp = cos(lat0 * GlobeMap.D2R); private val l0 = lon0
    fun at(lng: Double, lat: Double, o: DoubleArray) {
        val l = (lng - l0) * GlobeMap.D2R; val f = lat * GlobeMap.D2R
        val cf = cos(f); val sf = sin(f); val cl = cos(l)
        val x = cf * sin(l); val y = cp * sf - sp * cf * cl; val z = sp * sf + cp * cf * cl
        o[0] = cx + r * x; o[1] = cy - r * y; o[2] = z; o[3] = x; o[4] = y
    }
}

fun unProj(X: Double, Y: Double, lon0: Double, lat0: Double, r: Double, cx: Double, cy: Double): DoubleArray? {
    val x = (X - cx) / r; val y = (cy - Y) / r; val rho = hypot(x, y)
    if (rho > 1) return null
    if (rho < 1e-9) return doubleArrayOf(lon0, lat0)
    val c = asin(rho); val sc = sin(c); val cc = cos(c); val sp = sin(lat0 * GlobeMap.D2R); val cp = cos(lat0 * GlobeMap.D2R)
    return doubleArrayOf(wrapLon(lon0 + atan2(x * sc, rho * cp * cc - y * sp * sc) / GlobeMap.D2R), asin(cc * sp + (y * sc * cp) / rho) / GlobeMap.D2R)
}
fun wrapLon(l: Double) = ((((l + 180) % 360) + 360) % 360) - 180
fun lonGap(a: Double, b: Double) = wrapLon(b - a)
fun modp(a: Double, n: Double) = ((a % n) + n) % n
fun easeIO(t: Double) = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
fun pinScaleOf(r: Double) = min(1.0, max(0.62, 0.62 + (r - 190) / 480))
fun minPopFor(ppd: Double) = when { ppd >= 230 -> 0.0; ppd >= 170 -> 25.0; ppd >= 110 -> 50.0; ppd >= 70 -> 100.0; ppd >= 40 -> 250.0; ppd >= 24 -> 900.0; ppd >= 14 -> 3500.0; else -> Double.POSITIVE_INFINITY }

/** the web's map xy (Mercator, svg-maps/india): for "Near You" */
fun mapXY(lat: Double, lng: Double): DoubleArray {
    val merc = kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + (lat * Math.PI) / 360))
    return doubleArrayOf((lng - 68.16) * 20.92, 667.8 - (merc - 0.1411) * 1209.7)
}

private fun outside(bb: FloatArray, q: DoubleArray) = bb[2] < q[0] || bb[0] > q[2] || bb[3] < q[1] || bb[1] > q[3]
private fun within(bb: FloatArray, q: DoubleArray) = bb[0] >= q[0] && bb[2] <= q[2] && bb[1] >= q[1] && bb[3] <= q[3]

/** cut a filled ring to the box q (Sutherland–Hodgman), lng/lat pairs */
private fun clipRing(r: FloatArray, q: DoubleArray): FloatArray {
    var pts = r
    val edges = arrayOf(intArrayOf(0, 0, 1), intArrayOf(0, 2, -1), intArrayOf(1, 1, 1), intArrayOf(1, 3, -1))
    for (e in edges) {
        val n = pts.size / 2
        if (n == 0) break
        val ax = e[0]; val v = q[e[1]].toFloat(); val sg = e[2]
        val out = FloatArray(n * 4 + 4); var k = 0
        var px = pts[2 * (n - 1)]; var py = pts[2 * (n - 1) + 1]
        var pin = sg * ((if (ax == 0) px else py) - v) >= 0
        for (i in 0 until n) {
            val x = pts[2 * i]; val y = pts[2 * i + 1]
            val cin = sg * ((if (ax == 0) x else y) - v) >= 0
            if (cin != pin) {
                val pa = if (ax == 0) px else py; val ca = if (ax == 0) x else y
                val t = (v - pa) / (ca - pa)
                if (ax == 1) { out[k++] = px + t * (x - px); out[k++] = v } else { out[k++] = v; out[k++] = py + t * (y - py) }
            }
            if (cin) { out[k++] = x; out[k++] = y }
            px = x; py = y; pin = cin
        }
        pts = out.copyOf(k)
    }
    return pts
}

/** the runs of a line inside the box q (with one point before and after, so it reaches the edge) */
private inline fun runsInside(r: FloatArray, q: DoubleArray, run: (FloatArray, Int, Int) -> Unit) {
    val n = r.size / 2
    var start = -1
    var i = 0
    while (i < n) {
        val x = r[2 * i]; val y = r[2 * i + 1]
        val ins = x >= q[0] && x <= q[2] && y >= q[1] && y <= q[3]
        if (ins) { if (start < 0) start = if (i > 0) i - 1 else 0 }
        else if (start >= 0) { run(r, start, i + 1); start = -1 }
        i++
    }
    if (start >= 0 && n - start > 1) run(r, start, n)
}

/** budget phones and "remove animations": the same rules as the web's LITE / prefers-reduced-motion
 *  (SecretApp.jsx and ChatRoom.jsx: navigator.deviceMemory ≤ 3, hardwareConcurrency ≤ 4, or reduced motion) */
object Lite {
    var on = false; private set
    var reducedMotion = false; private set
    fun init(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val mem = deviceMemory(mi.totalMem / (1024 * 1024))
        // Chrome's hardwareConcurrency counts every core the phone has (sysconf _SC_NPROCESSORS_CONF), so does this
        val cores = Runtime.getRuntime().availableProcessors()
        // Android's "Remove animations" is what the page sees as prefers-reduced-motion
        reducedMotion = try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
        on = (mem > 0 && mem <= 3) || cores <= 4 || reducedMotion
    }
    /** test photos only: see the stronger-phone look on the slow emulator (or the budget look on a fast one) */
    fun forceForTests(lite: Boolean) { on = lite }
    /** navigator.deviceMemory the way Chrome works it out: the phone's RAM rounded to the nearest
     *  2·2ⁿ or 3·2ⁿ MB, in GB, at most 8 (so a "4 GB" phone is 4, a "3 GB" phone is 3) */
    private fun deviceMemory(mb: Long): Double {
        if (mb <= 0) return 0.0
        var lower = mb; var power = 0
        while (lower >= 4) { lower = lower shr 1; power++ }
        val upper = (lower + 1) shl power
        lower = lower shl power
        return min(8.0, (if (mb - lower <= upper - mb) lower else upper) / 1024.0)
    }
}
