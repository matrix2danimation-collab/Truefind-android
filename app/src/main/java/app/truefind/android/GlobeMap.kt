package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import android.util.LruCache
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Secret Dating · Online Map — a 3D Earth with India on it (the web's components/secret/GlobeMap.jsx, rebuilt Nov 20).
 * • A real globe you can spin 360°: one finger turns the Earth under your finger, two fingers zoom where your
 *   fingers are, a flick keeps it turning a little. The first time it opens the Earth rolls half way round and
 *   glides down to your city.
 * • Place names are placed again on every frame, most important first, and never sit on each other or on a
 *   person. They fade in and out as you zoom (before, the names from the last zoom stayed on screen while you
 *   zoomed out, bunched together, and then jumped all at once when the map stopped).
 * • People sit at the town or district they chose in their profile (never a live location, and never moved off
 *   it to make room). Pins that would overlap share one pin with a count; as you zoom in they split up, gliding
 *   apart instead of jumping. A shared pin of one town opens the list of everyone there.
 * • More detail as you come closer: rivers, then highways, then smaller roads; state names, landscapes and waters.
 * • The glowing light that sat in the middle of India is gone.
 * Sizes are the web's CSS pixels (dp here).
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
        set(v) { field = v; if (v) { maybeStart(); refresh() } else { if (frame != null) { stop(); busy = false; idleView = view.copy(); refresh() } } }
    var full = false
        set(v) { field = v; fullBtn.setImageDrawable(Icon(if (v) Lucide.Minimize2 else Lucide.Maximize2, GOLD_ICON, 20f)); fullBtn.contentDescription = if (v) "Exit full screen" else "Full screen map" }

    // ── people → points (one per person, at their town) → pins (people whose pins would overlap share one) ──
    private var people: List<JSONObject> = emptyList()
    private var peopleSig = ""
    var me: JSONObject? = null
    var mine: JSONObject = JSONObject()
    private class Pt(val p: JSONObject, val id: String, val g: DoubleArray, val live: Boolean, val chat: Boolean, val town: String) {
        var i = 0; var n = 0; val cl = cos(g[1] * D2R)
    }
    /** where a pin is drawn: its town, or on its way between two places while it glides */
    private class Disp(val lng: Double, val lat: Double)
    private class Cl(val lead: Pt, val m: List<Pt>, val towns: List<String>) {
        val key: String get() = lead.id
        val n = m.size
        val live = m.any { it.live }
        val chat = m.any { it.chat }
        val city = if (towns.size > 1) "${towns[0]} +${towns.size - 1}" else towns.firstOrNull() ?: ""
        val people: List<JSONObject> get() = m.map { it.p }
        val first: String by lazy { SdRules.firstName(SdRules.s(lead.p, "name")) }
        var a = 1.0                 // fades in when new
        var from: Disp? = null      // glides from here…
        var t0 = 0.0                // …since then
        var disp: Disp? = null      // where it was drawn last
    }
    /** a pin that joined another one: it glides into it and fades away */
    private class Ghost(val c: Cl, val from: Disp, val into: Cl?, val t0: Double)
    private var pts: List<Pt> = emptyList()
    private var ver = 0
    private var cver = -1
    private var zstep = Int.MIN_VALUE
    private var clusters: List<Cl> = emptyList()
    private var ghosts = ArrayList<Ghost>()
    private var firstFrame = true
    /** a place name on screen (or fading out): its kind, text, place, which side of its dot, how visible */
    private class Lbl(var kind: String, var text: String, var lng: Double, var lat: Double, var side: Int, var a: Double, var on: Boolean, var dy: Double = 0.0) {
        /** its picture, kept while it stays the same name on the same side (no look-up on every frame) */
        var sprite: NameSprite? = null
    }
    private val lbl = LinkedHashMap<String, Lbl>()
    /** the pins drawn last (tap boxes), the rings under people online */
    private class Drawn(val c: Cl, val x0: Double, val x1: Double, val y0: Double, val y1: Double)
    private var drawn: List<Drawn> = emptyList()
    private class Rip(val key: String, val x: Double, val y: Double, val s: Double)
    private var rips: List<Rip> = emptyList()

    // children: stars · the Earth (kept as a picture while it rests) · names and pins · rings under people online · buttons
    private val stars = Stars(ctx)
    private val globe = Globe(ctx)
    private val top = Top(ctx)
    private val fx = Fx(ctx)
    private val fullBtn = mapButton(Lucide.Maximize2, 20f, "Full screen map") { onFull() }
    private val locBtn = mapButton(Lucide.Navigation, 19f, "Go to my city", 1.8f) { locate() }

    init {
        clipChildren = true
        background = null
        addView(stars, LayoutParams(-1, -1))
        globe.setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(globe, LayoutParams(-1, -1))
        addView(top, LayoutParams(-1, -1))
        addView(fx, LayoutParams(-1, -1))
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
        addView(sdText(ctx, "Map: svg-maps (CC BY 4.0) · Natural Earth · GeoNames · INDIAN-SHAPEFILES", 7.5f, Color.argb(71, 255, 255, 255), bold = false).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(4f) })
        describe()
        post { SdMapData.load(context) { rebuildPoints(); refresh() } }
    }
    private fun describe() {
        val n = people.size
        contentDescription = "3D Earth with $n verified ${if (n == 1) "member" else "members"} across India. Drag to turn the globe, pinch or use + and − to zoom, tap a picture to open it."
    }

    // ═════════════ the projection (orthographic) ═════════════
    private fun radius(k: Double) = max(60.0, min(bw, bh) / 2 - 18) * k
    private fun cx() = bw / 2
    private fun cy() = bh / 2
    /** the Earth covers the whole box: even the box's corners are inside it (paintGlobe then paints the sea edge to edge) */
    private fun earthFills() = bw > 0 && hypot(cx(), cy()) <= radius(view.k)

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
    /** the patch of the Earth on screen (lon/lat box with a margin) — null when the whole globe shows. (The margin was
     *  0.3 of the screen on every side: the map then worked out and drew about three screens of lines it never showed.) */
    private fun geoBox(v: V): DoubleArray? {
        val r = radius(v.k); val ex = max(bw, bh) * 0.06
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
    /** your own town (from your profile) — null when the map doesn't know it yet */
    private fun myGeo(): DoubleArray? = me?.let { SdPlaces.placeOf(SdRules.s(it, "city"), SdRules.s(it, "state")) } ?: SdPlaces.placeOf(SdRules.s(mine, "city"), SdRules.s(mine, "state"))
    private fun myView(): V? = myGeo()?.let { aim(it, 4.2, bw / 2, bh * 0.5) }

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
        globe.invalidate(); top.invalidate(); fx.invalidate(); stars.invalidate()
    }
    private val main = Handler(Looper.getMainLooper())
    private val settled = Runnable { busy = false; idleView = view.copy(); refresh() }
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
        var end = myView() ?: homeView()
        var endKnown = myView() != null
        if (REDUCED) { view = freeV(end); idleView = view.copy(); refresh(); return }
        val start = V(end.lon - 180, 14.0, 1.0)
        view = start.copy()
        busy = true; refresh()
        val t0 = now(); val ms = if (LITE) 3000.0 else 3300.0
        loop { n ->
            val t = min(1.0, (n - t0) / ms)
            val spin = 0.5 - 0.5 * cos(Math.PI * min(1.0, t / 0.9))
            val dive = if (t < 0.4) 0.0 else easeIO((t - 0.4) / 0.6)
            // your city arrived while the Earth was turning: land there
            if (!endKnown && t > 0.3) { endKnown = true; myView()?.let { mv -> end = V(start.lon + 180 + lonGap(start.lon + 180, mv.lon), mv.lat, mv.k) } }
            view = freeV(V(start.lon + (end.lon - start.lon) * spin, start.lat + (end.lat - start.lat) * dive, start.k + (end.k - start.k) * dive))
            refresh()
            if (t >= 1) { frame = null; settle(40) }
        }
    }

    /** test photos only: the map at rest over [lng, lat] at zoom k (no intro, no flight) */
    fun showAt(lng: Double, lat: Double, k: Double) {
        stop(); pendingIntro = false; introDone = true
        busy = false; view = freeV(aim(doubleArrayOf(lng, lat), k, bw / 2, bh * 0.5)); idleView = view.copy(); refresh()
    }

    private fun locate() {
        val g = myGeo()
        if (g == null) { say("Add your city in your profile to see people around you."); return }
        fly(aim(g, 5.0, bw / 2, bh * 0.5), 900.0)
    }

    // the stars and the rings under people online move only while the map is on screen
    private val visRect = android.graphics.Rect()
    private fun onScreen(): Boolean = isShown && getGlobalVisibleRect(visRect)
    private val scrolled = android.view.ViewTreeObserver.OnScrollChangedListener { if (onScreen()) { stars.invalidate(); top.invalidate(); fx.invalidate() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnScrollChangedListener(scrolled) }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrolled)
        if (frame != null) { stop(); busy = false; idleView = view.copy() }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        bw = w / dens.toDouble(); bh = h / dens.toDouble()
        cver = -1 // regroup the pins for the new size
        if (pendingIntro) maybeStart()
        refresh()
    }

    // ═════════════ people → points ═════════════
    fun setPeople(list: List<JSONObject>) {
        people = list
        val sig = list.joinToString(";") { "${it.optString("id")}|${SdRules.liveOf(it)}|${it.optBoolean("chatting")}|${SdRules.s(it, "city")}|${SdRules.s(it, "state")}|${SdRules.s(it, "photo_thumb", "photo", "profile_picture")}" }
        if (sig == peopleSig) return
        peopleSig = sig
        rebuildPoints()
        describe()
    }
    /** each person at their town (people of one town: online first); who gets a pin first: people you chat with, then people online, then bigger towns */
    private fun rebuildPoints() {
        val towns = LinkedHashMap<String, MutableList<Pt>>()
        for (p in people) {
            val g = SdPlaces.placeOf(SdRules.s(p, "city"), SdRules.s(p, "state")) ?: continue
            val key = "%.3f,%.3f".format(java.util.Locale.US, g[0], g[1])
            towns.getOrPut(key) { ArrayList() }.add(Pt(p, p.optString("id"), g, SdRules.liveOf(p), p.optBoolean("chatting"), shortTown(SdRules.s(p, "city").ifEmpty { SdRules.s(p, "state") })))
        }
        val out = ArrayList<Pt>()
        for (list in towns.values) {
            list.sortWith(compareByDescending<Pt> { it.chat }.thenByDescending { it.live }.thenBy { it.id })
            list.forEachIndexed { i, o -> o.i = i; o.n = list.size; out.add(o) }
        }
        out.sortWith(compareByDescending<Pt> { it.chat }.thenByDescending { it.live }.thenByDescending { it.n }.thenBy { it.i })
        pts = out
        ver += 1
        top.invalidate()
    }
    /** pins that would sit on each other share one (the first person's) — worked out from the zoom only, so turning the Earth never regroups them */
    private fun clusterize(list: List<Pt>, ppd: Double, withLabels: Boolean): List<Cl> {
        val ps = pinScaleOf(ppd)
        // what one pin covers (with its tag when tags show), plus a step of zoom to spare
        val w = (if (withLabels) 76 else 58) * ps * 1.12; val h = (if (withLabels) 102 else 80) * ps * 1.12; val cell = max(w, h)
        class C(val x: Double, val y: Double, val lead: Pt) { val m = ArrayList<Pt>().apply { add(lead) } }
        val grid = HashMap<Long, ArrayList<C>>(); val out = ArrayList<C>()
        fun cellKey(ix: Long, iy: Long) = (ix shl 32) xor (iy and 0xffffffffL)
        for (o in list) {
            // flat map around India: east-west distances shrink with latitude (measured from 80°E so they stay true)
            val x = (o.g[0] - 80) * o.cl * ppd; val y = -o.g[1] * ppd
            val gx = floor(x / cell).toLong(); val gy = floor(y / cell).toLong()
            var hit: C? = null
            loop@ for (ix in gx - 1..gx + 1) for (iy in gy - 1..gy + 1) {
                val l = grid[cellKey(ix, iy)] ?: continue
                for (c in l) if (abs(c.x - x) < w && abs(c.y - y) < h) { hit = c; break@loop }
            }
            if (hit != null) { hit.m.add(o); continue }
            val c = C(x, y, o)
            out.add(c)
            grid.getOrPut(cellKey(gx, gy)) { ArrayList() }.add(c)
        }
        return out.map { c ->
            val towns = ArrayList<String>()
            for (o in c.m) if (o.town.isNotEmpty() && o.town !in towns) towns.add(o.town)
            Cl(c.lead, c.m, towns)
        }
    }

    private fun tapGroup(c: Cl) {
        if (c.n == 1) { onOpen(c.people[0]); return }
        // people from several towns under one pin: come closer and they separate
        if (c.towns.size > 1 && view.k < MAX_K / 2) { fly(aim(c.lead.g, min(MAX_K, view.k * 2.4), bw / 2, bh * 0.56), 650.0); return }
        onGroup(c.people, "${c.people.size} ${if (c.people.size == 1) "person" else "people"} · ${c.city}")
    }
    private fun pinAt(x: Double, y: Double): Cl? {
        for (d in drawn.asReversed()) if (x >= d.x0 && x <= d.x1 && y >= d.y0 && y <= d.y1) return d.c
        return null
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
        val hit = pinAt(x, y)
        if (hit != null) { tapGroup(hit); return }
        val t = System.currentTimeMillis()
        if (t - lastTap < 320 && hypot(x - lastTapX, y - lastTapY) < 30) { lastTap = 0; zoomSoft(2.0, doubleArrayOf(x, y)) }
        else { lastTap = t; lastTapX = x; lastTapY = y }
    }

    // ═════════════ the Earth (one picture, redrawn while it moves) ═════════════
    private inner class Globe(ctx: Context) : View(ctx) {
        private val path = Path()
        private val india = Path()
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val m = Matrix()
        private val atm = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(0, 124, 77, 255), Color.argb(0, 124, 77, 255), Color.argb(204, 196, 173, 255), Color.argb(82, 124, 77, 255), Color.argb(0, 124, 77, 255)),
            floatArrayOf(0f, .84f, .877f, .92f, 1f), Shader.TileMode.CLAMP)
        private val sea = RadialGradient(0f, 0f, 1f, intArrayOf(Ui.c("#4b30b0"), Ui.c("#25166c"), Ui.c("#100a3a"), Ui.c("#060320")), floatArrayOf(0f, .35f, .7f, 1f), Shader.TileMode.CLAMP)
        private val shade = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(0, 2, 0, 8), Color.argb(0, 2, 0, 8), Color.argb(107, 2, 0, 8), Color.argb(199, 2, 0, 8)), floatArrayOf(0f, .66f, .9f, 1f), Shader.TileMode.CLAMP)
        private val rim = LinearGradient(0f, -1f, 0f, 1f, intArrayOf(Ui.c("#f4ecff"), Color.argb(179, 179, 146, 255), Color.argb(38, 124, 77, 255), Color.argb(13, 124, 77, 255)), floatArrayOf(0f, .3f, .65f, 1f), Shader.TileMode.CLAMP)
        private val side = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(Ui.c("#ffe3a6"), Ui.c("#d99a3c"), Ui.c("#6e3a10")), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        private val o = DoubleArray(5)
        private val o2 = DoubleArray(5)
        private var pts = FloatArray(512)
        private val bounds = RectF()

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
            val ppd = r * D2R
            val moving = busy
            val rich = !LITE && !moving
            val rf = r.toFloat(); val xf = x0.toFloat(); val yf = y0.toFloat()
            // is the edge of the Earth on screen? (zoomed in it isn't, and its glow and shading need no drawing)
            val far = maxOf(hypot(x0, y0), hypot(bw - x0, y0), hypot(x0, bh - y0), hypot(bw - x0, bh - y0))
            val edgeOn = far > r * 0.62
            // atmosphere + ocean
            fillP.reset(); fillP.isAntiAlias = true
            if (far > r) { fillP.shader = shaderAt(atm, x0, y0, r * 1.14); c.drawCircle(xf, yf, rf * 1.14f, fillP) }
            // close in, the sea is one colour (one quick fill instead of a big gradient)
            val seaShader: Shader? = if (edgeOn) shaderAt(sea, x0 - r * 0.35, y0 - r * 0.45, r * 1.55) else null
            fillP.shader = seaShader; fillP.color = SEA_FLAT
            if (far > r) c.drawCircle(xf, yf, rf, fillP) else c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), fillP)
            fillP.shader = null
            if (!SdMapData.ready) { if (edgeOn) drawShadeRim(c, x0, y0, r, far > r); return }
            val q = geoBox(v)
            // faint latitude / longitude lines
            if (!(LITE && moving) && ppd < 40) { line(SdMapData.graticule, q, P, false); stroke(c, Color.argb(33, 185, 162, 255), 0.8f) }
            // the continents at night
            fill(worldRings(r), q, P, r, x0, y0)
            fillP.color = Color.argb(235, 42, 28, 102); c.drawPath(path, fillP)
            stroke(c, Color.argb(97, 165, 140, 255), 0.8f)
            // India
            fill(borderRings(r), q, P, r, x0, y0)
            india.set(path)
            val indiaOn = !india.isEmpty
            // a thin channel of sea around India so it stands apart from its neighbours
            if (indiaOn) {
                strokeP.shader = seaShader; strokeP.color = SEA_FLAT; strokeP.strokeWidth = 5f; c.drawPath(india, strokeP); strokeP.shader = null
            }
            // the world's city lights
            if (ppd < 30) {
                val dd = min(1.25, max(0.7, r / 520)).toFloat()
                if (rich) dots(c, SdMapData.worldLights, P, 0.1, { it % 3 != 0 }, Color.argb(51, 255, 184, 92), 4f * dd)
                dots(c, SdMapData.worldLights, P, 0.06, null, Color.argb(191, 255, 217, 160), 1.3f * dd)
            }
            if (indiaOn) {
                val sd = min(2.2, max(0.7, r / 420)).toFloat()
                india.computeBounds(bounds, true)
                // raised, with a soft purple shadow and a gold edge. Close in only the edges show, so the shadow and the
                // gold side are drawn as wide edge lines there instead of whole filled shapes (much less to paint).
                val whole = ppd < 24
                c.save(); c.translate(0f, 9 * sd)
                if (whole) { fillP.color = Color.argb(77, 124, 58, 237); c.drawPath(india, fillP) }
                if (rich) { strokeP.color = Color.argb(56, 139, 92, 246); strokeP.strokeWidth = 16 * sd; c.drawPath(india, strokeP) }
                c.restore()
                c.save(); c.translate(0f, 4 * sd)
                m.reset(); m.setScale(1f, max(1f, bounds.height())); m.postTranslate(0f, bounds.top); side.setLocalMatrix(m)
                if (whole) { fillP.shader = side; c.drawPath(india, fillP); fillP.shader = null }
                else { strokeP.shader = side; strokeP.strokeWidth = 8 * sd; c.drawPath(india, strokeP); strokeP.shader = null }
                strokeP.color = Ui.c("#6e3a10"); strokeP.strokeWidth = 1.2f * sd; c.drawPath(india, strokeP)
                c.restore()
                strokeP.color = Ui.c("#ffdca0"); strokeP.strokeWidth = 3 * sd; c.drawPath(india, strokeP)
                fillP.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom, TOP_COLORS, TOP_STOPS, Shader.TileMode.CLAMP); c.drawPath(india, fillP); fillP.shader = null
                // detail that comes in as you zoom: rivers, then highways, then smaller roads
                val aRiv = ramp(ppd, 7.0, 16.0); val aHw = ramp(ppd, 16.0, 30.0); val aRd = ramp(ppd, 30.0, 60.0)
                if (aRd > 0) { line(SdMapData.roads, q, P, false); stroke(c, Ui.c("#cdb8ff"), min(1.6, 0.5 + ppd / 160).toFloat(), (aRd * min(0.34, 0.14 + ppd / 700)).toFloat()) }
                if (aHw > 0) { line(SdMapData.highways, q, P, false); stroke(c, Ui.c("#f3cf8a"), min(2.6, 0.8 + ppd / 120).toFloat(), (aHw * min(0.5, 0.22 + ppd / 520)).toFloat()) }
                if (aRiv > 0) { line(riverRings(r), q, P, false); stroke(c, Ui.c("#9cc4ff"), min(1.8, 0.5 + ppd / 52).toFloat(), (aRiv * min(0.55, 0.22 + ppd / 105)).toFloat()) }
                // district lines (owner, 24 Nov: every district of India), faint, coming in as you zoom
                val aDist = ramp(ppd, 15.0, 30.0)
                if (aDist > 0 && SdMapData.district.isNotEmpty()) { line(districtRings(r), q, P, false); stroke(c, Ui.c("#d9c9ff"), min(1.1, 0.5 + ppd / 260).toFloat(), (aDist * min(0.42, 0.22 + ppd / 500)).toFloat()) }
                // state borders (today's states: Ladakh apart from Jammu & Kashmir), a little clearer than before
                line(stateRings(r), q, P, false); stroke(c, Ui.c("#f3ecff"), min(1.4, 0.6 + r / 1400).toFloat(), 0.6f)
                // the network between India's big cities (zoomed out only)
                val aMesh = 1 - ramp(ppd, 12.0, 24.0)
                if (aMesh > 0) {
                    path.rewind()
                    val ms = SdMapData.mesh
                    var i = 0
                    while (i < ms.size) {
                        P.at(ms[i].toDouble(), ms[i + 1].toDouble(), o); P.at(ms[i + 2].toDouble(), ms[i + 3].toDouble(), o2)
                        if (o[2] >= 0.05 && o2[2] >= 0.05) { path.moveTo(o[0].toFloat(), o[1].toFloat()); path.lineTo(o2[0].toFloat(), o2[1].toFloat()) }
                        i += 4
                    }
                    stroke(c, Ui.c("#d9b8ff"), 0.7f, (0.32 * aMesh).toFloat())
                }
                // the big cities' lights (they hand over to the town names as you come closer)
                val aLights = 1 - ramp(ppd, 20.0, 34.0)
                if (aLights > 0) {
                    val dd = min(1.25, max(0.7, r / 520)).toFloat()
                    if (rich) dots(c, SdMapData.cityGeo, P, 0.06, null, Color.argb((0.36 * aLights * 255).toInt(), 255, 198, 107), 7f * dd)
                    dots(c, SdMapData.cityGeo, P, 0.06, null, Color.argb((aLights * 255).toInt(), 255, 241, 207), 2.3f * dd)
                }
            }
            // the night side and the bright rim
            if (edgeOn) drawShadeRim(c, x0, y0, r, far > r)
        }

        private fun drawShadeRim(c: Canvas, x0: Double, y0: Double, r: Double, rimOn: Boolean) {
            fillP.shader = shaderAt(shade, x0, y0, r); c.drawCircle(x0.toFloat(), y0.toFloat(), r.toFloat(), fillP); fillP.shader = null
            if (!rimOn) return
            m.reset(); m.setScale(1f, r.toFloat()); m.postTranslate(x0.toFloat(), y0.toFloat()); rim.setLocalMatrix(m)
            strokeP.shader = rim; strokeP.strokeWidth = 1.8f; c.drawCircle(x0.toFloat(), y0.toFloat(), r.toFloat(), strokeP); strokeP.shader = null
        }

        private fun stroke(c: Canvas, color: Int, width: Float, alpha: Float = 1f) {
            if (path.isEmpty) return
            strokeP.shader = null; strokeP.color = color; if (alpha < 1f) strokeP.alpha = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
            strokeP.strokeWidth = width; c.drawPath(path, strokeP)
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
            var back = false; var started = false; var prevAng = 0.0; var lx = 0f; var ly = 0f
            val oval = RectF((x0 - r).toFloat(), (y0 - r).toFloat(), (x0 + r).toFloat(), (y0 + r).toFloat())
            for (i in 0 until n) {
                val px = pts[5 * i]; val py = pts[5 * i + 1]; val pz = pts[5 * i + 2]
                if (pz > 0) {
                    if (started && !back && i < n - 1 && abs(px - lx) + abs(py - ly) < 0.7f) continue   // closer than a pixel
                    if (started) path.lineTo(px, py) else { path.moveTo(px, py); started = true }
                    back = false; lx = px; ly = py
                    continue
                }
                val ux = pts[5 * i + 3].toDouble(); val uy = pts[5 * i + 4].toDouble()
                val rr = hypot(ux, uy).takeIf { it > 0 } ?: 1.0
                val X = x0 + (r * ux) / rr; val Y = y0 - (r * uy) / rr; val ang = atan2(Y - y0, X - x0)
                if (back && started) {
                    var da = ang - prevAng
                    while (da > Math.PI) da -= 2 * Math.PI
                    while (da < -Math.PI) da += 2 * Math.PI
                    path.arcTo(oval, Math.toDegrees(prevAng).toFloat(), Math.toDegrees(da).toFloat(), false)
                } else if (started) path.lineTo(X.toFloat(), Y.toFloat())
                else { path.moveTo(X.toFloat(), Y.toFloat()); started = true }
                back = true; prevAng = ang
            }
            if (started) path.close()
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

        // many small round dots (round caps on zero-length lines; width = the dot's size)
        private fun dots(c: Canvas, src: FloatArray, P: Proj, minZ: Double, skip: ((Int) -> Boolean)?, color: Int, width: Float) {
            val n = src.size / 2
            if (pts.size < n * 2) pts = FloatArray(n * 2 + 64)
            var k = 0
            for (i in 0 until n) {
                if (skip != null && skip(i)) continue
                P.at(src[2 * i].toDouble(), src[2 * i + 1].toDouble(), o)
                if (o[2] > minZ) { pts[k++] = o[0].toFloat(); pts[k++] = o[1].toFloat() }
            }
            if (k == 0 || Color.alpha(color) == 0) return
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
            // the Earth fills the whole map (its sea is drawn edge to edge, solid): no sky shows, so none is drawn and
            // the stars rest — before, four screens of sky were painted under it on every frame, and kept the phone
            // drawing all the time. They come back as soon as the Earth is small enough for the sky to show.
            if (earthFills()) return
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
    // ═════════════ place names and people (placed again on every frame the map moves) ═════════════
    private class Sprite(val bmp: Bitmap, val l: Float, val t: Float, val w: Float, val h: Float)
    /** a name: its picture and where its anchor is inside it (the dot for towns, the middle for other names) */
    private class NameSprite(val bmp: Bitmap, val w: Float, val h: Float, val ax: Float, val ay: Float)
    private class Placed(val kind: String, val text: String, val lng: Double, val lat: Double, val side: Int, val dy: Double = 0.0)
    private class Pin(val c: Cl, val x: Double, val y: Double, val a: Double, val label: String, val ghost: Boolean)

    private fun regroup(now: Double, sppd: Double, withLabels: Boolean, stp: Int) {
        val prev = clusters
        val next = clusterize(pts, sppd, withLabels)
        val prevOf = HashMap<String, Cl>()
        for (c in prev) for (o in c.m) prevOf[o.id] = c
        val leads = HashSet<String>(); for (c in next) leads.add(c.lead.id)
        val glide = !firstFrame && prev.isNotEmpty()
        for (c in next) {
            val was = prevOf[c.lead.id]
            if (!glide) { c.a = 1.0; continue }
            if (was != null && was.lead.id == c.lead.id) { c.from = was.from; c.t0 = was.t0; c.a = was.a }
            else if (was?.disp != null) { c.from = was.disp; c.t0 = now; c.a = 1.0 }
            else c.a = 0.0 // someone new: fades in
        }
        // pins that join another one glide into it and fade away
        ghosts.removeAll { now - it.t0 >= GLIDE_MS }
        if (glide) for (c in prev) {
            if (c.lead.id in leads) continue
            val d = c.disp ?: continue
            ghosts.add(Ghost(c, d, next.firstOrNull { n -> n.m.any { it.id == c.lead.id } }, now))
        }
        clusters = next; cver = ver; zstep = stp; firstFrame = false
    }
    private fun pinLabel(c: Cl, ppd: Double) = if (ppd < PIN_LABEL_PPD) "" else if (c.n == 1 && ppd >= 40) c.first else c.city
    // the names' keys, made once (a name keeps its key while it fades in and out)
    private val cityKeys: Array<String> by lazy { Array(SdMapData.cities.size) { val c = SdMapData.cities[it]; "c-${c.n}-${c.lng}" } }
    private val stateKeys: Array<String> by lazy { Array(SdMapData.states.size) { "s-${SdMapData.states[it].n}" } }
    /** the capitals (New Delhi first) and the states, biggest first: the order they get room in */
    private val capitals: IntArray by lazy { SdMapData.cities.indices.filter { SdMapData.cities[it].kind >= 2 }.sortedByDescending { SdMapData.cities[it].kind * 100000 + SdMapData.cities[it].pop }.toIntArray() }
    private val statesBySize: IntArray by lazy { SdMapData.states.indices.sortedByDescending { SdMapData.states[it].a }.toIntArray() }
    private val nameKeys: Array<String> by lazy { Array(SdMapData.names.size) { val n = SdMapData.names[it]; "x-${n.t}-${n.lng}" } }
    private fun mix(a: Disp, b: Disp, e: Double) = Disp(a.lng + lonGap(a.lng, b.lng) * e, a.lat + (b.lat - a.lat) * e)

    private inner class Top(ctx: Context) : View(ctx) {
        private val o = DoubleArray(5)
        private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val photoOn = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // offline: saturate(.75) brightness(.92)
        private val photoOff = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.75f); postConcat(ColorMatrix().apply { setScale(0.92f, 0.92f, 0.92f, 1f) }) })
        }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#20123a") }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Ui.c("#1a0b33") }
        private val dst = RectF()
        private var lastT = 0.0
        // what is covered on screen (names keep clear of it): x0, y0, x1, y1 …
        private var boxes = DoubleArray(512)
        private var nb = 0
        private val placed = LinkedHashMap<String, Placed>()

        private fun box(x0: Double, y0: Double, x1: Double, y1: Double) {
            if (nb + 4 > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
            boxes[nb] = x0; boxes[nb + 1] = y0; boxes[nb + 2] = x1; boxes[nb + 3] = y1; nb += 4
        }
        private fun free(x0: Double, y0: Double, x1: Double, y1: Double, from: Int = 0): Boolean {
            val pad = 2.0
            var i = from
            while (i < nb) { if (x0 < boxes[i + 2] + pad && x1 > boxes[i] - pad && y0 < boxes[i + 3] + pad && y1 > boxes[i + 1] - pad) return false; i += 4 }
            return true
        }

        override fun onDraw(c: Canvas) {
            if (bw <= 0) return
            val now = now()
            val dt = if (lastT > 0) min(64.0, now - lastT) else 16.0
            lastT = now
            val v = view; val r = radius(v.k); val P = Proj(v.lon, v.lat, r, cx(), cy())
            val ppd = r * D2R; val q = geoBox(v); val moving = busy
            val w = bw; val h = bh
            var more = false
            c.save(); c.scale(dens, dens)

            // ── people: regroup when the zoom has moved a step (or the list / the size changed) ──
            val stp = round(ln(max(1.0, ppd)) / ln(1.12)).toInt()
            val sppd = 1.12.pow(stp)
            if (cver != ver || zstep != stp) regroup(now, sppd, sppd >= PIN_LABEL_PPD, stp)
            val ps = pinScaleOf(ppd)
            val pins = ArrayList<Pin>()
            for (cl in clusters) {
                var d = Disp(cl.lead.g[0], cl.lead.g[1])
                val fr = cl.from
                if (fr != null) {
                    val t = (now - cl.t0) / GLIDE_MS
                    if (t < 1) { d = mix(fr, d, easeOut(t.coerceIn(0.0, 1.0))); more = true } else cl.from = null
                }
                if (cl.a < 1) { cl.a = min(1.0, cl.a + dt / 220); more = true }
                cl.disp = d
                P.at(d.lng, d.lat, o)
                if (o[2] < 0.06) continue
                val fade = cl.a * (if (o[2] < 0.3) ((o[2] - 0.06) / 0.24).coerceIn(0.0, 1.0) else 1.0)
                if (o[0] < -80 || o[0] > w + 80 || o[1] < -40 || o[1] > h + 120) continue
                pins.add(Pin(cl, o[0], o[1], fade, pinLabel(cl, ppd), false))
            }
            val gone = ArrayList<Pin>()
            for (g in ghosts) {
                val t = (now - g.t0) / GLIDE_MS
                if (t >= 1) continue
                more = true
                val e = easeOut(t.coerceIn(0.0, 1.0))
                val to = g.into?.let { it.disp ?: Disp(it.lead.g[0], it.lead.g[1]) } ?: g.from
                val d = mix(g.from, to, e)
                P.at(d.lng, d.lat, o)
                if (o[2] < 0.06) continue
                gone.add(Pin(g.c, o[0], o[1], 1 - e, "", true))
            }
            ghosts.removeAll { now - it.t0 >= GLIDE_MS }

            // ── what pins and buttons cover (place names keep clear of them) ──
            nb = 0
            for (pn in pins) {
                box(pn.x - 28 * ps, pn.y - 70 * ps, pn.x + 28 * ps, pn.y + 9 * ps)
                if (pn.label.isNotEmpty()) { val lw = (tagWidth(pn.label) + 16) * ps; box(pn.x - lw / 2, pn.y + 9 * ps, pn.x + lw / 2, pn.y + 30 * ps) }
            }
            // (state names and the stars of capitals may lie under a pin: they look past the pins' boxes, which come first)
            val pinEnd = nb
            box(10.0, 8.0, 236.0, 58.0); box(w - 62, h / 2 - 44, w - 8, h / 2 + 62); box(6.0, h - 66, 62.0, h - 8); box(w - 62, h - 66, w - 6, h - 8); box(w / 2 - 130, h - 16, w / 2 + 130, h)

            // ── place names: most important first, each only where it fits ──
            placed.clear()
            fun tryPlace(key: String, kind: String, text: String, lng: Double, lat: Double, twoSides: Boolean, from: Int = 0, nudge: Boolean = false): Boolean {
                if (placed.containsKey(key)) return true
                P.at(lng, lat, o)
                if (o[2] < 0.25 || o[0] < -60 || o[0] > w + 60 || o[1] < -30 || o[1] > h + 30) return false
                val px = o[0]; val py = o[1]
                val first = if (twoSides) (lbl[key]?.side?.takeIf { it != 0 } ?: 1) else 0
                // a state's name may move a line or two up or down to find room (its own spot first; the spot it had last time first of all)
                val tries = if (nudge) NUDGES else NO_NUDGE
                val had = lbl[key]?.let { e -> if (e.on) e.dy else null }
                val order = if (had != null && had != 0.0) doubleArrayOf(had) + tries.filter { it != had } else tries
                for (dyk in order) for (k in 0 until (if (twoSides) 2 else 1)) {
                    val side = if (k == 0) first else -first
                    // (only the name's size here: its picture is made once it gets a place — making pictures for every
                    // town that was tried, most of them never shown, overflowed the picture store and redid them all on every frame)
                    val sp = nameBox(kind, text, side)
                    val x0 = px - sp.ax; val y0 = py - sp.ay + dyk * sp.h
                    if (x0 < -4 || y0 < -4 || x0 + sp.w > w + 4 || y0 + sp.h > h + 4) continue
                    if (!free(x0 + 2, y0 + 2, x0 + sp.w - 2, y0 + sp.h - 2, from)) continue
                    box(x0 + 2, y0 + 2, x0 + sp.w - 2, y0 + sp.h - 2)
                    placed[key] = Placed(kind, text, lng, lat, side, dyk)
                    return true
                }
                return false
            }
            if (SdMapData.ready && !(LITE && moving && ppd < 3)) {
                val names = SdMapData.names
                fun inZ(n: SdMapData.Name) = ppd >= n.z0 && ppd < n.z1
                fun seen(lng: Float, lat: Float) = q == null || (lng >= q[0] && lng <= q[2] && lat >= q[1] && lat <= q[3])
                val cs = SdMapData.cities
                // far away: continents and oceans
                for ((j, n) in names.withIndex()) if ((n.k == "co" || n.k == "oc") && ppd < 10 && inZ(n)) tryPlace(nameKeys[j], n.k, n.t, n.lng.toDouble(), n.lat.toDouble(), false)
                // (owner, 24 Nov: every capital, every state and all of India's towns on the map)
                // New Delhi, then every state's and union territory's capital: a star with its name — when the name has
                // no room, the star alone (so a capital always shows where it is)
                if (ppd >= 2.5) for (ix in capitals) {
                    val ct = cs[ix]
                    if (!seen(ct.lng, ct.lat)) continue
                    val kind = if (ct.kind == 3) "nc" else "cap"
                    if (!tryPlace(cityKeys[ix], kind, ct.n, ct.lng.toDouble(), ct.lat.toDouble(), true)) tryPlace(cityKeys[ix], "${kind}0", "", ct.lng.toDouble(), ct.lat.toDouble(), false, pinEnd)
                }
                // states: all of them, the big ones first (smaller letters while the map shows all of India)
                if (ppd >= 3.5 && ppd < 70) {
                    val minA = if (ppd < 6) 2400f else if (ppd < 10) 700f else if (ppd < 16) 120f else 0f
                    val kind = if (ppd < 11) "st2" else "st"
                    for (j in statesBySize) { val s = SdMapData.states[j]; if (s.a > minA && seen(s.lng, s.lat)) tryPlace(stateKeys[j], kind, s.n, s.lng.toDouble(), s.lat.toDouble(), false, pinEnd, nudge = true) }
                }
                // big cities, neighbours and seas, then every other town (district headquarters before others), then landscapes
                val minPop = minPopFor(ppd)
                var i = 0
                while (i < cs.size && cs[i].pop >= 1000) { val ct = cs[i]; if (ct.kind < 2 && ct.pop >= minPop && seen(ct.lng, ct.lat)) tryPlace(cityKeys[i], "cb", ct.n, ct.lng.toDouble(), ct.lat.toDouble(), true); i++ }
                for ((j, n) in names.withIndex()) if ((n.k == "cn" || (n.k == "oc" && ppd >= 10)) && inZ(n)) tryPlace(nameKeys[j], n.k, n.t, n.lng.toDouble(), n.lat.toDouble(), false)
                if (minPop.isFinite()) {
                    // a district headquarters counts as a town three times its size (people pick their district)
                    for (k in i until cs.size) { val ct = cs[k]; if (ct.kind == 1 && ct.pop < minPop && ct.pop * 3 + 30 >= minPop && seen(ct.lng, ct.lat)) tryPlace(cityKeys[k], "ct", ct.n, ct.lng.toDouble(), ct.lat.toDouble(), true) }
                    while (i < cs.size) { val ct = cs[i]; if (ct.pop < minPop) break; if (ct.kind < 2 && seen(ct.lng, ct.lat)) tryPlace(cityKeys[i], "ct", ct.n, ct.lng.toDouble(), ct.lat.toDouble(), true); i++ }
                }
                for ((j, n) in names.withIndex()) if ((n.k == "rg" || n.k == "wt") && inZ(n) && seen(n.lng, n.lat)) tryPlace(nameKeys[j], n.k, n.t, n.lng.toDouble(), n.lat.toDouble(), false)
            }
            // fade names in and out (never a jump)
            val fadeStep = dt / 170
            for ((key, pl) in placed) {
                val e = lbl[key]
                if (e != null) {
                    if (e.side != pl.side || e.kind != pl.kind || e.text != pl.text) e.sprite = null
                    e.side = pl.side; e.on = true; e.kind = pl.kind; e.text = pl.text; e.lng = pl.lng; e.lat = pl.lat; e.dy = pl.dy
                }
                else lbl[key] = Lbl(pl.kind, pl.text, pl.lng, pl.lat, pl.side, if (firstFrame) 1.0 else 0.0, true, pl.dy)
            }
            val it = lbl.entries.iterator()
            while (it.hasNext()) {
                val en = it.next(); val e = en.value
                if (!placed.containsKey(en.key)) e.on = false
                val tgt = if (e.on) 1.0 else 0.0
                if (e.a != tgt) { e.a = if (tgt > e.a) min(1.0, e.a + fadeStep) else max(0.0, e.a - fadeStep * 1.4); if (e.a != tgt) more = true }
                if (!e.on && e.a <= 0) { it.remove(); continue }
                P.at(e.lng, e.lat, o)
                if (o[2] < 0.2) { if (!e.on) it.remove(); continue }
                val sp = e.sprite ?: nameSprite(e.kind, e.text, e.side).also { e.sprite = it }
                val zf = if (o[2] < 0.45) ((o[2] - 0.2) / 0.25).coerceIn(0.0, 1.0) else 1.0
                val al = (e.a * zf * 255).toInt()
                if (al <= 0) continue
                bp.alpha = al
                val x0 = (o[0] - sp.ax).toFloat(); val y0 = (o[1] - sp.ay + e.dy * sp.h).toFloat()
                dst.set(x0, y0, x0 + sp.w, y0 + sp.h)
                c.drawBitmap(sp.bmp, null, dst, bp)
            }
            bp.alpha = 255

            // ── pins on top: lower ones in front ──
            val all = ArrayList<Pin>(gone.size + pins.size); all.addAll(gone); all.addAll(pins)
            all.sortBy { it.y }
            val dr = ArrayList<Drawn>(pins.size)
            for (pn in all) {
                drawPin(c, pn.c, pn.x, pn.y, pn.a, pn.label, ps)
                if (!pn.ghost) dr.add(Drawn(pn.c, pn.x - 30 * ps, pn.x + 30 * ps, pn.y - 72 * ps, pn.y + 8 * ps + (if (pn.label.isNotEmpty()) 22 * ps else 0.0)))
            }
            drawn = dr
            // the rings under people who are online (drawn by their own layer above this one)
            val rp = ArrayList<Rip>()
            for (pn in pins) if (pn.c.live && pn.a >= 0.5) rp.add(Rip(pn.c.key, pn.x, pn.y, ps))
            rips = rp
            c.restore()
            firstFrame = false
            fx.invalidate()
            if (more && active && onScreen()) postInvalidateOnAnimation()
        }

        /** one pin: photo in a gold ring on a tail, standing on a glowing spot; count, online dot, name/town tag */
        private fun drawPin(c: Canvas, cl: Cl, x: Double, y: Double, a: Double, label: String, ps: Double) {
            val alpha = (a * 255).toInt().coerceIn(0, 255)
            if (alpha <= 1) return
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.scale(ps.toFloat(), ps.toFloat())
            // fading: the whole pin fades as one picture (as the web's sprite does), not part by part
            val layered = alpha < 250
            if (layered) c.saveLayerAlpha(-96f, -96f, 96f, 36f, alpha)
            fun draw(s: Sprite?) { if (s == null) return; dst.set(s.l, s.t, s.l + s.w, s.t + s.h); c.drawBitmap(s.bmp, null, dst, bp) }
            draw(baseSprite(cl.live))
            // the photo
            c.drawCircle(0f, -44f, 21.5f, disc)
            val face = faceOf(cl.lead.p)
            if (face != null) { dst.set(-21.5f, -65.5f, 21.5f, -22.5f); c.drawBitmap(face, null, dst, if (cl.live) photoOn else photoOff) }
            c.drawCircle(0f, -44f, 21.5f, edge)
            draw(dotSprite(cl.live))
            if (cl.n > 1) draw(countSprite(if (cl.n > 99) "99+" else cl.n.toString()))
            if (cl.chat) draw(chatSprite(label))
            if (label.isNotEmpty()) draw(tagSprite(label))
            if (layered) c.restore()
            c.restore()
        }

        // the picture for a person: their photo (round), the default avatar, or nothing yet (still loading)
        private val loading = HashSet<String>()
        private val noFace = HashSet<String>()
        private fun faceOf(p: JSONObject): Bitmap? {
            val px = Ui.dpi(43f).coerceAtLeast(48)
            Demo.round(p, px)?.let { return it }
            val raws = listOf(SdRules.s(p, "photo_thumb"), SdRules.s(p, "photo", "profile_picture")).filter { it.isNotBlank() }.distinct()
            if (raws.isEmpty()) return defaultFace(px)
            val key = raws.joinToString("|")
            if (key in noFace) return defaultFace(px)
            Photos.cached(raws, px)?.let { return it }
            if (loading.add(key)) Photos.loadRaw(raws, px) { b -> loading.remove(key); if (b == null) noFace.add(key); invalidate() }
            return null
        }
    }

    /** the rings that ripple out under people who are online (their own layer: the map underneath is not redrawn for them) */
    private inner class Fx(ctx: Context) : View(ctx) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(167, 112, 255) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(200, 160, 255) }
        private val oval = RectF()
        private val t0 = System.currentTimeMillis()
        override fun onDraw(c: Canvas) {
            val list = rips
            if (list.isEmpty() || bw <= 0) return
            c.save(); c.scale(dens, dens)
            val now = System.currentTimeMillis() - t0
            for (rp in list) {
                if (REDUCED) { ring(c, rp, 1.0, 0.6); continue }
                for (d in if (LITE) longArrayOf(0) else longArrayOf(0, 900)) {
                    val f = ((now - d).coerceAtLeast(0) % 1800) / 1800.0
                    val e = cssEaseOut(f)
                    ring(c, rp, 0.35 + 1.15 * e, 1 - e)
                }
            }
            c.restore()
            if (!REDUCED && active && onScreen()) postInvalidateOnAnimation()
        }
        /** a 48 × 16 ring (2 px edge, soft purple glow) at the person's spot, at scale s */
        private fun ring(c: Canvas, rp: Rip, s: Double, op: Double) {
            if (op <= 0.01) return
            val k = (s * rp.s).toFloat()
            val x = rp.x.toFloat(); val y = rp.y.toFloat()
            oval.set(x - 23 * k, y - 7 * k, x + 23 * k, y + 7 * k)
            glow.strokeWidth = 7f * k; glow.alpha = (0.28 * op * 255).toInt(); c.drawOval(oval, glow)
            line.strokeWidth = 2f * k; line.alpha = (0.95 * op * 255).toInt(); c.drawOval(oval, line)
        }
    }

    // ── sprites: a pin part or a name is drawn once into a small picture, then only copied on every frame ──
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)
    private val widths = HashMap<String, Float>()
    /** width of a pin's town / name tag text (600 11px) */
    private fun tagWidth(t: String): Double = widths.getOrPut(t) { measure.typeface = Ui.bold; measure.textSize = 11f; measure.letterSpacing = 0f; measure.measureText(t) }.toDouble()
    private val nameCache = object : LruCache<String, NameSprite>(18 * 1024 * 1024) { override fun sizeOf(key: String, value: NameSprite) = value.bmp.byteCount }
    private val parts = object : LruCache<String, Sprite>(6 * 1024 * 1024) { override fun sizeOf(key: String, value: Sprite) = value.bmp.byteCount }
    private val bases = arrayOfNulls<Sprite>(2)
    private val dotSprites = arrayOfNulls<Sprite>(2)
    private var noPhoto: Bitmap? = null

    private fun canvasFor(w: Float, h: Float): Pair<Bitmap, Canvas> {
        val bm = Bitmap.createBitmap(ceil(w * dens).toInt().coerceAtLeast(1), ceil(h * dens).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bm); cv.scale(dens, dens)
        return bm to cv
    }
    /** canvas shadowBlur b → Android's blur radius (the same softness) */
    private fun blur(b: Float) = BlurMaskFilter(max(0.5f, (b / 2 - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
    private fun sprite(l: Float, t: Float, w: Float, h: Float, draw: (Canvas) -> Unit): Sprite {
        val (bm, cv) = canvasFor(w, h)
        cv.translate(-l, -t)
        draw(cv)
        return Sprite(bm, l, t, bm.width / dens, bm.height / dens)
    }

    // name styles: kind = town, big city, state, continent, ocean/sea, country, region, water
    private class Ls(val size: Float, val italic: Boolean, val color: Int, val spacing: Float = 0f, val upper: Boolean = false, val dot: Int = 0, val glow: Int = 0, val r: Float = 0f)
    private val LS = mapOf(
        "ct" to Ls(11f, false, Color.argb(235, 238, 230, 255), dot = Ui.c("#efe6ff"), glow = Color.argb(242, 200, 170, 255), r = 2.4f),
        "cb" to Ls(12.5f, false, Color.argb(250, 255, 238, 204), dot = Ui.c("#ffe2a6"), glow = Color.argb(250, 255, 196, 104), r = 3.2f),
        "st" to Ls(11f, false, Color.argb(165, 236, 226, 255), 1.8f, true),
        "st2" to Ls(8.5f, false, Color.argb(165, 236, 226, 255), 1.1f, true),
        // capitals: a gold star (New Delhi's bigger and red-gold); "…0" is the star without its name
        "cap" to Ls(11.5f, false, Color.argb(255, 255, 236, 196), dot = Ui.c("#ffd36b"), glow = Color.argb(250, 255, 190, 80), r = 5.2f),
        "nc" to Ls(13f, false, Color.argb(255, 255, 240, 210), dot = Ui.c("#ffb347"), glow = Color.argb(255, 255, 120, 80), r = 6.6f),
        "cap0" to Ls(0f, false, 0, dot = Ui.c("#ffd36b"), glow = Color.argb(250, 255, 190, 80), r = 5.2f),
        "nc0" to Ls(0f, false, 0, dot = Ui.c("#ffb347"), glow = Color.argb(255, 255, 120, 80), r = 6.6f),
        "co" to Ls(14f, false, Color.argb(204, 246, 222, 170), 3.4f, true),
        "oc" to Ls(13f, true, Color.argb(168, 190, 170, 255), 0.5f),
        "cn" to Ls(10.5f, false, Color.argb(153, 222, 212, 255), 1.1f, true),
        "rg" to Ls(11f, true, Color.argb(128, 214, 198, 255), 1.4f),
        "wt" to Ls(10.5f, true, Color.argb(168, 160, 196, 255), 0.4f),
    )
    /** where a name's picture would sit, without making the picture: its size (dp, as the picture comes out) and its
     *  anchor — the same numbers nameSprite works out. Kept for every name (kind → text → side: no new objects per look-up). */
    private class NameBox(val w: Float, val h: Float, val ax: Float, val ay: Float)
    private val nameBoxes = HashMap<String, HashMap<String, Array<NameBox?>>>()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun nameBox(kind: String, text: String, side: Int): NameBox {
        val byText = nameBoxes.getOrPut(kind) { HashMap() }
        val sides = byText[text] ?: arrayOfNulls<NameBox>(3).also { byText[text] = it }
        sides[side + 1]?.let { return it }
        val st = LS[kind] ?: LS.getValue("ct")
        val t = if (st.upper) text.uppercase() else text
        val tw = if (t.isEmpty()) 0f else boxPaint.run { typeface = if (st.italic) Ui.boldItalic else Ui.bold; textSize = st.size; letterSpacing = st.spacing / st.size; measureText(t) }
        val dot = if (st.dot != 0) st.r * 2 + 5 else 0f
        val w = if (t.isEmpty()) ceil(st.r * 2 + 8) else ceil(tw + dot + 8); val h = max(ceil(st.size * 1.45f + 6), ceil(st.r * 2 + 8))
        var dx = 0f
        if (st.dot != 0) { dx = if (t.isEmpty()) w / 2 else if (side >= 0) 4 + st.r else w - 4 - st.r }
        val bwPx = ceil(w * dens).toInt().coerceAtLeast(1); val bhPx = ceil(h * dens).toInt().coerceAtLeast(1)
        val ax = if (t.isEmpty()) dx else if (st.dot != 0) dx else w / 2
        return NameBox(bwPx / dens, bhPx / dens, ax, h / 2).also { sides[side + 1] = it }
    }
    /** side: 1 = name right of the dot, -1 = left of it, 0 = centred (no dot) */
    private fun nameSprite(kind: String, text: String, side: Int): NameSprite {
        val key = "$kind|$side|$text"
        nameCache.get(key)?.let { return it }
        val st = LS[kind] ?: LS.getValue("ct")
        val t = if (st.upper) text.uppercase() else text
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (st.italic) Ui.boldItalic else Ui.bold; textSize = st.size; letterSpacing = st.spacing / st.size }
        val star = kind.startsWith("cap") || kind.startsWith("nc")
        val tw = if (t.isEmpty()) 0f else tp.measureText(t)
        val dot = if (st.dot != 0) st.r * 2 + 5 else 0f
        val w = if (t.isEmpty()) ceil(st.r * 2 + 8) else ceil(tw + dot + 8); val h = max(ceil(st.size * 1.45f + 6), ceil(st.r * 2 + 8))
        val (bm, cv) = canvasFor(w, h)
        val fm = tp.fontMetrics
        val ty = h / 2 + 0.5f - (fm.ascent + fm.descent) / 2
        var tx = 4f; var dx = 0f
        if (st.dot != 0) {
            if (t.isEmpty()) { dx = w / 2 } else if (side >= 0) { dx = 4 + st.r; tx = 4 + dot } else { dx = w - 4 - st.r; tx = 4f }
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            if (star) {
                // a five-pointed star: the capital
                val sp = Path(); val R = st.r * 1.25f; val ri = R * 0.45f
                for (k in 0 until 10) { val rr = if (k % 2 == 0) R else ri; val an = -Math.PI / 2 + k * Math.PI / 5; val x = dx + (rr * cos(an)).toFloat(); val y = h / 2 + (rr * sin(an)).toFloat() + R * 0.08f; if (k == 0) sp.moveTo(x, y) else sp.lineTo(x, y) }
                sp.close()
                q.color = st.glow; q.maskFilter = blur(7f); cv.drawPath(sp, q); q.maskFilter = null
                q.color = st.dot; cv.drawPath(sp, q)
                q.style = Paint.Style.STROKE; q.strokeWidth = 1.1f; q.strokeJoin = Paint.Join.ROUND; q.color = Color.argb(204, 60, 20, 0); cv.drawPath(sp, q)
            } else {
                q.color = st.glow; q.maskFilter = blur(6f); cv.drawCircle(dx, h / 2, st.r, q); q.maskFilter = null
                q.color = st.dot; cv.drawCircle(dx, h / 2, st.r, q)
                q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = Color.argb(179, 20, 8, 40); cv.drawCircle(dx, h / 2, st.r, q)
            }
        }
        if (t.isEmpty()) { val s0 = NameSprite(bm, bm.width / dens, bm.height / dens, dx, h / 2); nameCache.put(key, s0); return s0 }
        tp.style = Paint.Style.STROKE; tp.strokeWidth = 3.2f; tp.strokeJoin = Paint.Join.ROUND; tp.color = Color.argb(224, 6, 2, 22)
        cv.drawText(t, tx, ty, tp)
        tp.style = Paint.Style.FILL; tp.color = st.color
        cv.drawText(t, tx, ty, tp)
        val s = NameSprite(bm, bm.width / dens, bm.height / dens, if (st.dot != 0) dx else w / 2, h / 2)
        nameCache.put(key, s)
        return s
    }

    // the still parts of a pin, around its ground point (0, 0); the photo's middle is 44 above it
    private fun baseSprite(live: Boolean): Sprite = bases[if (live) 1 else 0] ?: sprite(-41f, -85f, 82f, 98f) { cv ->
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        // soft glow when someone here is online
        if (live) { q.shader = RadialGradient(0f, -44f, 40f, intArrayOf(Color.argb(140, 167, 112, 255), Color.argb(140, 167, 112, 255), Color.argb(0, 167, 112, 255)), floatArrayOf(0f, .1f, 1f), Shader.TileMode.CLAMP); cv.drawCircle(0f, -44f, 40f, q); q.shader = null }
        // the spot on the ground
        cv.save(); cv.scale(1f, 0.34f)
        val a0 = if (live) 191 else 102
        q.shader = RadialGradient(0f, 0f, 24f, intArrayOf(Color.argb(a0, 167, 112, 255), Color.argb(a0, 167, 112, 255), Color.argb(64, 124, 58, 237), Color.argb(0, 124, 58, 237)), floatArrayOf(0f, 2f / 24, 0.5875f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(0f, 0f, 24f, q); q.shader = null
        cv.restore()
        q.color = Color.argb(217, 255, 190, 90); q.maskFilter = blur(8f); cv.drawOval(-6f, -3f, 6f, 3f, q); q.maskFilter = null
        q.color = Ui.c("#ffe4a8"); cv.drawOval(-6f, -3f, 6f, 3f, q)
        // gold tail
        q.shader = LinearGradient(0f, -24f, 0f, -5f, intArrayOf(Ui.c("#ffe7a8"), Ui.c("#e0a43c"), Ui.c("#b8741f")), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(Path().apply { moveTo(-9f, -25f); lineTo(9f, -25f); lineTo(0f, -5f); close() }, q); q.shader = null
        // ring
        q.color = Color.argb(140, 0, 0, 0); q.maskFilter = blur(10f); cv.drawCircle(0f, -40f, 25f, q); q.maskFilter = null
        q.shader = LinearGradient(-18f, -69f, 14f, -19f, intArrayOf(Ui.c("#ffe7a8"), Ui.c("#e0a43c"), Ui.c("#a8661a")), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(0f, -44f, 25f, q); q.shader = null
        q.style = Paint.Style.STROKE; q.strokeWidth = 3f; q.color = Color.argb(102, 139, 63, 224); cv.drawCircle(0f, -44f, 26.5f, q)
    }.also { bases[if (live) 1 else 0] = it }

    /** online (green) / offline (grey) dot */
    private fun dotSprite(live: Boolean): Sprite = dotSprites[if (live) 1 else 0] ?: sprite(10f, -37f, 18f, 18f) { cv ->
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = RadialGradient(17f, -30f, 8.5f, intArrayOf(if (live) Ui.c("#7af08a") else Ui.c("#d4cfe0"), if (live) Ui.c("#7af08a") else Ui.c("#d4cfe0"), if (live) Ui.c("#16a34a") else Ui.c("#8c84a3")), floatArrayOf(0f, .12f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(19f, -28f, 7f, q); q.shader = null
        q.style = Paint.Style.STROKE; q.strokeWidth = 2f; q.color = Ui.c("#12081f"); cv.drawCircle(19f, -28f, 7f, q)
    }.also { dotSprites[if (live) 1 else 0] = it }

    /** how many people (top right of the ring) */
    private fun countSprite(t: String): Sprite = parts.get("n|$t") ?: run {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = 12f }
        val bw = max(24f, tp.measureText(t) + 12)
        sprite(4f, -83f, bw + 12f, 34f) { cv ->
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            q.color = Color.argb(128, 0, 0, 0); q.maskFilter = blur(5f); cv.drawRoundRect(10f, -75f, 10f + bw, -54f, 10.5f, 10.5f, q); q.maskFilter = null
            q.shader = LinearGradient(0f, -77f, 0f, -56f, Ui.c("#ffe7a8"), Ui.c("#d99a35"), Shader.TileMode.CLAMP); cv.drawRoundRect(10f, -77f, 10f + bw, -56f, 10.5f, 10.5f, q); q.shader = null
            tp.color = Ui.c("#2a1600"); tp.textAlign = Paint.Align.CENTER
            val fm = tp.fontMetrics; cv.drawText(t, 10f + bw / 2, -66f - (fm.ascent + fm.descent) / 2, tp)
        }.also { parts.put("n|$t", it) }
    }

    /** "Chatting": someone you chat with (its place follows the web's: it depends on how wide the pin's tag is) */
    private fun chatSprite(label: String): Sprite {
        val lw = if (label.isNotEmpty()) ceil(tagWidth(label) + 16).toFloat() else 0f
        val bigX = max(84f, lw + 6) / 2
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = 10.5f }
        val bw = tp.measureText("Chatting") + 14
        val bx = max(1f, bigX - 34 - bw / 2 + 8) - bigX
        val key = "c|${(bx * 4).toInt()}"
        parts.get(key)?.let { return it }
        return sprite(bx - 2, -81f, bw + 4, 23f) { cv ->
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            q.color = Ui.c("#E3086C"); cv.drawRoundRect(bx, -79f, bx + bw, -60f, 9.5f, 9.5f, q)
            q.style = Paint.Style.STROKE; q.strokeWidth = 1.5f; q.color = Color.WHITE; cv.drawRoundRect(bx, -79f, bx + bw, -60f, 9.5f, 9.5f, q)
            tp.color = Color.WHITE; tp.textAlign = Paint.Align.CENTER
            val fm = tp.fontMetrics; cv.drawText("Chatting", bx + bw / 2, -69f - (fm.ascent + fm.descent) / 2, tp)
        }.also { parts.put(key, it) }
    }

    /** town (or first name) under the pin */
    private fun tagSprite(label: String): Sprite = parts.get("t|$label") ?: run {
        val lw = ceil(tagWidth(label) + 16).toFloat()
        sprite(-lw / 2 - 1, 9f, lw + 2, 21f) { cv ->
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            q.color = Color.argb(204, 12, 6, 24); cv.drawRoundRect(-lw / 2, 10f, lw / 2, 29f, 8f, 8f, q)
            q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = Color.argb(107, 186, 140, 255); cv.drawRoundRect(-lw / 2, 10f, lw / 2, 29f, 8f, 8f, q)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.bold; textSize = 11f; color = Ui.c("#efe6ff"); textAlign = Paint.Align.CENTER }
            val fm = tp.fontMetrics; cv.drawText(label, 0f, 20f - (fm.ascent + fm.descent) / 2, tp)
        }.also { parts.put("t|$label", it) }
    }

    /** the default avatar (lavender silhouette) for someone without a photo */
    private fun defaultFace(px: Int): Bitmap = noPhoto?.takeIf { it.width == px } ?: Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { DefaultAvatar.draw(Canvas(it), px.toFloat()); noPhoto = it }

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

    private fun pick(levels: List<SdMapData.Rings>, r: Double, px: Double): SdMapData.Rings { val ok = levels.filter { it.tol * D2R * r < px }; return ok.lastOrNull() ?: levels[0] }
    private fun worldRings(r: Double) = pick(SdMapData.world, r, 1.6)
    private fun borderRings(r: Double) = pick(SdMapData.border, r, 0.9)
    private fun stateRings(r: Double) = pick(SdMapData.state, r, 1.0)
    private fun riverRings(r: Double) = pick(SdMapData.river, r, 1.2)
    private fun districtRings(r: Double) = pick(SdMapData.district, r, 1.0)

    companion object {
        const val D2R = Math.PI / 180
        const val MAX_K = 96.0
        private const val HOME_LON = 80.5; private const val HOME_LAT = -10.0; private const val HOME_K = 2.4
        private const val GLIDE_MS = 320.0
        private val GOLD_ICON = Ui.c("#f6d89a")
        private val SEA_FLAT = Ui.c("#1d1258")
        private val TOP_COLORS = intArrayOf(Ui.c("#4e28a6"), Ui.c("#2f1470"), Ui.c("#1c0a48"))
        private val TOP_STOPS = floatArrayOf(0f, .5f, 1f)
        /** where a state's name may try to sit: its own line, then a line or two up or down */
        private val NUDGES = doubleArrayOf(0.0, -1.3, 1.3, -2.6, 2.6)
        private val NO_NUDGE = doubleArrayOf(0.0)
        /** the 360° Earth spin plays once each time the app is opened, not on every visit to the map */
        var introDone = false
        /** lighter drawing on budget phones (few cores / little memory) or when the phone asks for less motion */
        val LITE: Boolean by lazy { Lite.on }
        val REDUCED: Boolean get() = Lite.reducedMotion
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
fun easeOut(t: Double) = 1 - (1 - t).pow(3)
fun ramp(x: Double, a: Double, b: Double) = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
/** CSS "ease-out" (cubic-bezier(0, 0, .58, 1)): the timing of the rings under people online */
fun cssEaseOut(t: Double): Double {
    if (t <= 0) return 0.0
    if (t >= 1) return 1.0
    // x(s) = 3(1-s)s²·.58 + s³ (x1 = 0): solve x(s) = t, then y(s)
    var lo = 0.0; var hi = 1.0; var s = t
    repeat(22) { s = (lo + hi) / 2; val x = 3 * (1 - s) * s * s * 0.58 + s * s * s; if (x < t) lo = s else hi = s }
    return 3 * (1 - s) * s * s + s * s * s      // y(s) with y1 = 0, y2 = 1
}
/** how big a pin is at this zoom (ppd = screen dp per degree of the Earth) */
// (owner, 23 Nov night: the places shown better — the pins a little bigger when the map shows all of India, 0.7 instead of 0.56)
fun pinScaleOf(ppd: Double) = (0.7 + 0.14 * log2(max(1.0, ppd) / 8)).coerceIn(0.7, 1.0)
/** from this zoom (pixels a degree) every pin carries its town's name — already when the map shows all of India */
const val PIN_LABEL_PPD = 6.0
/** the smallest town (people, in thousands) that gets a name at this zoom */
// (owner, 24 Nov: all of India's towns — about 7,100 now — come in sooner as you zoom; at the closest zoom every one of them)
fun minPopFor(ppd: Double) = when {
    ppd < 4 -> Double.POSITIVE_INFINITY; ppd < 7 -> 2500.0; ppd < 10 -> 700.0; ppd < 14 -> 250.0; ppd < 20 -> 100.0; ppd < 28 -> 45.0
    ppd < 40 -> 20.0; ppd < 56 -> 9.0; ppd < 80 -> 3.0; else -> 0.0
}
/** "Gautam Buddh Nagar (Noida)" → "Noida", "Mohali (SAS Nagar)" → "Mohali": the short name people know, for the tag under a pin */
fun shortTown(t: String): String {
    val raw = t.trim()
    val m = Regex("^(.*?)\\s*\\((.+)\\)\\s*$").find(raw)
    val name = (if (m != null) (if (m.groupValues[1].length > 12) m.groupValues[2] else m.groupValues[1]) else raw).trim()
    return if (name.length > 15) name.take(14) + "…" else name
}

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

/** the runs of a line inside the box q (with one point before and after, so it reaches the edge); a long piece
 *  that crosses the box with both its ends outside it is a run of its own (else it would be missing on screen) */
private inline fun runsInside(r: FloatArray, q: DoubleArray, run: (FloatArray, Int, Int) -> Unit) {
    val n = r.size / 2
    var start = -1
    var i = 0
    while (i < n) {
        val x = r[2 * i]; val y = r[2 * i + 1]
        val ins = x >= q[0] && x <= q[2] && y >= q[1] && y <= q[3]
        if (ins) { if (start < 0) start = if (i > 0) i - 1 else 0 }
        else if (start >= 0) { run(r, start, i + 1); start = -1 }
        else if (i > 0) {
            val px = r[2 * i - 2]; val py = r[2 * i - 1]
            if (min(px, x) <= q[2] && max(px, x) >= q[0] && min(py, y) <= q[3] && max(py, y) >= q[1]) run(r, i - 1, i + 1)
        }
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
