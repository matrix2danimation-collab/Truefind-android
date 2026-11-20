package app.truefind.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The 3D Earth's drawing data — the same numbers as the web map (src/components/secret/mapData.js):
 * world land, India's border, state lines, rivers, highways, roads, city lights, the thin network between
 * India's big cities, towns, states, the names of seas / regions / neighbours, and the tables that put a
 * person at the town or district they chose. Built from the web file by tools/sd-map-gen/gen.mjs into
 * assets/sd-map.bin (already simplified at the same levels of detail the web uses). Each ring is lng, lat, lng, lat …
 */
object SdMapData {
    class Rings(val tol: Float, val rings: Array<FloatArray>) {
        // bounding box of every ring: minLng, minLat, maxLng, maxLat (for drawing only what is on screen)
        val bb: Array<FloatArray> = Array(rings.size) { i ->
            val r = rings[i]; var a = 1e9f; var b = 1e9f; var c = -1e9f; var d = -1e9f
            var k = 0
            while (k < r.size) { val x = r[k]; val y = r[k + 1]; if (x < a) a = x; if (x > c) c = x; if (y < b) b = y; if (y > d) d = y; k += 2 }
            floatArrayOf(a, b, c, d)
        }
    }
    class City(val n: String, val lng: Float, val lat: Float, val pop: Int)
    class State(val n: String, val a: Float, val lng: Float, val lat: Float)
    /** a name that is not a town: continent (co), ocean or sea (oc), country (cn), region (rg), water (wt); shown between zooms z0 … z1 (pixels per degree) */
    class Name(val t: String, val lng: Float, val lat: Float, val k: String, val z0: Float, val z1: Float)

    @Volatile var ready = false
        private set
    lateinit var world: List<Rings>; private set
    lateinit var border: List<Rings>; private set
    lateinit var state: List<Rings>; private set
    lateinit var river: List<Rings>; private set
    lateinit var highways: Rings; private set
    lateinit var roads: Rings; private set
    lateinit var worldLights: FloatArray; private set
    lateinit var cityGeo: FloatArray; private set
    /** the network between India's big cities: x1, y1, x2, y2 for each line */
    lateinit var mesh: FloatArray; private set
    /** towns, biggest first (the order names are given room in) */
    lateinit var cities: List<City>; private set
    lateinit var states: List<State>; private set
    lateinit var names: List<Name>; private set
    /** the graticule: latitude lines every 20° (and 75°), meridians every 20° */
    val graticule: Rings by lazy {
        val out = ArrayList<FloatArray>()
        for (lat in intArrayOf(-60, -40, -20, 0, 20, 40, 60, 75)) out.add(FloatArray(73 * 2) { k -> if (k % 2 == 0) -180f + (k / 2) * 5f else lat.toFloat() })
        for (j in 0 until 18) out.add(FloatArray(35 * 2) { k -> if (k % 2 == 0) -180f + j * 20f else -85f + (k / 2) * 5f })
        Rings(0f, out.toTypedArray())
    }

    private val waiting = ArrayList<() -> Unit>()
    private var loading = false

    fun load(ctx: Context, done: () -> Unit) {
        if (ready) { done(); return }
        waiting.add(done)
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try { DataInputStream(app.assets.open("sd-map.bin").buffered(1 shl 16)).use { read(it) } } catch (e: Exception) { /* the map shows the ocean only */ }
            Handler(Looper.getMainLooper()).post { ready = true; loading = false; val w = waiting.toList(); waiting.clear(); w.forEach { it() } }
        }.start()
    }

    /** reads the whole file (no Android here: tools can read it the same way) */
    fun read(d: DataInputStream) {
        check(d.readUTF() == "TFMAP2")
        val layers = HashMap<String, Rings>()
        repeat(d.readInt()) {
            val tol = d.readFloat()
            val name = d.readUTF()
            val n = d.readInt()
            val rings = Array(n) { val m = d.readInt(); FloatArray(m * 2) { d.readFloat() } }
            layers[name] = Rings(tol, rings)
        }
        fun levels(prefix: String) = generateSequence(0) { it + 1 }.map { layers["$prefix$it"] }.takeWhile { it != null }.filterNotNull().toList()
        world = levels("world"); border = levels("border"); state = levels("state"); river = levels("river")
        highways = layers.getValue("highways"); roads = layers.getValue("roads")
        worldLights = layers.getValue("worldLights").rings[0]
        cityGeo = layers.getValue("cityGeo").rings[0]
        val m = layers.getValue("mesh").rings
        mesh = FloatArray(m.size * 4).also { a -> for (i in m.indices) for (j in 0 until 4) a[i * 4 + j] = m[i][j] }
        cities = List(d.readInt()) { City(d.readUTF(), d.readFloat(), d.readFloat(), d.readInt()) }
        states = List(d.readInt()) { State(d.readUTF(), d.readFloat(), d.readFloat(), d.readFloat()) }
        names = List(d.readInt()) { Name(d.readUTF(), d.readFloat(), d.readFloat(), d.readUTF(), d.readFloat(), d.readFloat()) }
        fun table(): HashMap<String, FloatArray> { val t = HashMap<String, FloatArray>(); repeat(d.readInt()) { t[d.readUTF()] = floatArrayOf(d.readFloat(), d.readFloat()) }; return t }
        val coords = table(); val extra = table()
        val aliases = HashMap<String, List<String>>(); repeat(d.readInt()) { val k = d.readUTF(); aliases[k] = List(d.readInt()) { d.readUTF() } }
        val statePlaces = table(); val extraStates = table()
        SdPlaces.set(coords, extra, aliases, statePlaces, extraStates, cities, states)
    }
}

/**
 * Where a person goes on the map: the town or district they chose in their profile (never a live location) —
 * the web's placeOf (src/components/secret/mapData.js), the same steps in the same order.
 * The district list people pick from has 786 names; many are named after a region, not a town (East Godavari,
 * Kamrup, Ranga Reddy…) or are spelt differently on the map (Gurugram / Gurgaon). Each is matched to the town
 * it is run from; a name the map doesn't know goes to the middle of its state.
 */
object SdPlaces {
    private const val D2R = PI / 180
    private class St(val mid: DoubleArray, val r: Double)
    private var coords: Map<String, FloatArray> = emptyMap()
    private var extra: Map<String, FloatArray> = emptyMap()
    private var aliases: Map<String, List<String>> = emptyMap()
    private var statePlaces: Map<String, FloatArray> = emptyMap()
    private var extraStates: Map<String, FloatArray> = emptyMap()
    private var towns: Map<String, List<SdMapData.City>> = emptyMap()
    private var stateMid: Map<String, St> = emptyMap()
    private val cache = HashMap<String, DoubleArray?>()
    @Volatile var ready = false
        private set

    fun set(coords: Map<String, FloatArray>, extra: Map<String, FloatArray>, aliases: Map<String, List<String>>, statePlaces: Map<String, FloatArray>,
            extraStates: Map<String, FloatArray>, cities: List<SdMapData.City>, states: List<SdMapData.State>) {
        this.coords = coords; this.extra = extra; this.aliases = aliases; this.statePlaces = statePlaces; this.extraStates = extraStates
        val t = LinkedHashMap<String, MutableList<SdMapData.City>>()
        for (c in cities) t.getOrPut(norm(c.n)) { ArrayList() }.add(c)
        towns = t
        stateMid = states.associate { s -> norm(s.n) to St(doubleArrayOf(s.lng.toDouble(), s.lat.toDouble()), max(3.0, 2.2 * sqrt(s.a / 481.0 / PI))) }
        synchronized(cache) { cache.clear() }
        ready = true
    }

    private val MARKS = Regex("[\\u0300-\\u036f]")
    private val NOT_WORD = Regex("[^a-z0-9]+")
    private val SPLIT = Regex("[()/,]|\\s-\\s|-")
    private val DIRS = Regex("\\b(north|south|east|west|central|outer|upper|lower|middle|eastern|western|northern|southern|purba|paschim|uttar|dakshin)\\b")
    private val SPACES = Regex("\\s+")
    fun norm(t: String?): String = java.text.Normalizer.normalize((t ?: "").lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(MARKS, "").replace("&", " and ").replace(NOT_WORD, " ").trim()

    private fun stateOf(state: String?): St? { val k = norm(state); return stateMid[k] ?: extraStates[k]?.let { St(doubleArrayOf(it[0].toDouble(), it[1].toDouble()), 3.0) } }
    private fun apart(a: DoubleArray, b: DoubleArray) = hypot((a[0] - b[0]) * cos(((a[1] + b[1]) / 2) * D2R), a[1] - b[1])
    private fun d(f: FloatArray) = doubleArrayOf(f[0].toDouble(), f[1].toDouble())

    /** [lng, lat] of a person's town, or the middle of their state, or null (nothing known) */
    fun placeOf(city: String?, state: String?): DoubleArray? {
        if (!ready) return null
        val key = "${city ?: ""}|${state ?: ""}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val s = stateOf(state); val mid = s?.mid; val rad = s?.r ?: 9.0
        var out: DoubleArray? = statePlaces["${norm(city)}|${norm(state)}"]?.let { d(it) }
        val raw = (city ?: "").trim()
        if (out == null && raw.isNotEmpty()) {
            val names = ArrayList<String>(); names.add(raw)
            for (x in raw.split(SPLIT)) { val t = x.trim(); if (t.isNotEmpty()) names.add(t) }
            val more = ArrayList<String>(); for (n in names) aliases[norm(n)]?.let { more.addAll(it) }
            val all = names + more + norm(raw).replace(DIRS, " ").replace(SPACES, " ").trim()
            for (nm in all) {
                val k = norm(nm)
                if (k.isEmpty()) continue
                val e = extra[k]
                if (e != null) { out = d(e); break }
                val c = coords[k]
                if (c != null && (mid == null || apart(d(c), mid) < rad)) { out = d(c); break }
                val ts = towns[k]
                if (ts != null) {
                    val best = if (mid != null) ts.reduce { a, b -> if (apart(doubleArrayOf(b.lng.toDouble(), b.lat.toDouble()), mid) < apart(doubleArrayOf(a.lng.toDouble(), a.lat.toDouble()), mid)) b else a } else ts[0]
                    val bg = doubleArrayOf(best.lng.toDouble(), best.lat.toDouble())
                    if (mid == null || apart(bg, mid) < rad) { out = bg; break }
                }
            }
        }
        val res = out ?: mid
        synchronized(cache) { cache[key] = res }
        return res
    }
}
