package app.truefind.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream

/**
 * The 3D Earth's drawing data — the same numbers as the web map (src/pages/SecretApp.jsx):
 * world land, India's border, state lines, rivers, roads, highways, city lights and town names.
 * Built from the web file by genscripts/mapdata.mjs into assets/sd-map.bin (already simplified
 * at the same levels of detail the web page uses). Each ring is lng, lat, lng, lat …
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

    @Volatile var ready = false
        private set
    lateinit var world: List<Rings>; private set
    lateinit var border: List<Rings>; private set
    lateinit var state: List<Rings>; private set
    lateinit var rivers: Rings; private set
    lateinit var highways: Rings; private set
    lateinit var roads: Rings; private set
    lateinit var worldLights: FloatArray; private set
    lateinit var cityGeo: FloatArray; private set
    lateinit var netGeo: FloatArray; private set
    lateinit var netLines: Rings; private set
    lateinit var hub: FloatArray; private set
    lateinit var cities: List<City>; private set
    lateinit var states: List<State>; private set
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
            try { read(app) } catch (e: Exception) { /* the map shows the ocean only */ }
            Handler(Looper.getMainLooper()).post { ready = true; loading = false; val w = waiting.toList(); waiting.clear(); w.forEach { it() } }
        }.start()
    }

    private fun read(ctx: Context) {
        DataInputStream(ctx.assets.open("sd-map.bin").buffered(1 shl 16)).use { d ->
            check(d.readUTF() == "TFMAP1")
            val layers = HashMap<String, Rings>()
            repeat(d.readInt()) {
                val tol = d.readFloat()
                val name = d.readUTF()
                val n = d.readInt()
                val rings = Array(n) { val m = d.readInt(); FloatArray(m * 2) { d.readFloat() } }
                layers[name] = Rings(tol, rings)
            }
            world = (0..2).map { layers.getValue("world$it") }
            border = (0..3).map { layers.getValue("border$it") }
            state = (0..2).map { layers.getValue("state$it") }
            rivers = layers.getValue("rivers"); highways = layers.getValue("highways"); roads = layers.getValue("roads")
            worldLights = layers.getValue("worldLights").rings[0]
            cityGeo = layers.getValue("cityGeo").rings[0]
            netGeo = layers.getValue("netGeo").rings[0]
            netLines = layers.getValue("netLines")
            hub = layers.getValue("hub").rings[0]
            cities = List(d.readInt()) { City(d.readUTF(), d.readFloat(), d.readFloat(), d.readInt()) }
            states = List(d.readInt()) { State(d.readUTF(), d.readFloat(), d.readFloat(), d.readFloat()) }
        }
    }
}
