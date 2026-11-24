package app.truefind.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/** The map file (24 Nov): today's states, every capital, all towns, and every district people can pick lands where it should. */
class SdMapDataTest {
    private fun load() {
        if (SdPlaces.ready) return
        val f = listOf(File("src/main/assets/sd-map.bin"), File("app/src/main/assets/sd-map.bin")).first { it.exists() }
        DataInputStream(f.inputStream().buffered()).use { SdMapData.read(it); assertEquals("nothing left over", -1, it.read()) }
    }
    private fun km(a: DoubleArray, lng: Double, lat: Double) = 111.2 * hypot((a[0] - lng) * cos(Math.toRadians((a[1] + lat) / 2)), a[1] - lat)

    @Test fun statesCapitalsTowns() {
        load()
        val st = SdMapData.states.map { it.n }
        assertEquals(36, st.size)
        assertTrue("Ladakh" in st); assertTrue("Jammu & Kashmir" in st); assertTrue("Dadra & Nagar Haveli and Daman & Diu" in st)
        assertEquals(1, SdMapData.cities.count { it.kind == 3 })
        assertEquals(34, SdMapData.cities.count { it.kind == 2 })
        assertTrue(SdMapData.cities.size > 7000)
        assertTrue(SdMapData.district.isNotEmpty())
        // biggest first: the order names get room in
        assertTrue(SdMapData.cities.zipWithNext().all { (a, b) -> a.pop >= b.pop })
    }

    @Test fun everyDistrictAtItsPlace() {
        load()
        val f = listOf(File("src/main/assets/sd-data.json"), File("app/src/main/assets/sd-data.json")).first { it.exists() }
        val d = JSONObject(f.readText()).getJSONObject("districts")
        var n = 0
        for (st in d.keys()) {
            val a = d.getJSONArray(st)
            assertNotNull(st, SdPlaces.placeOf("", st))
            for (i in 0 until a.length()) { n++; val p = SdPlaces.placeOf(a.getString(i), st); assertNotNull("${a.getString(i)}, $st", p) }
        }
        assertEquals(786, n)
        // places that were wrong before (another town of the same name, or a mistyped table entry)
        assertTrue(km(SdPlaces.placeOf("Mau", "Uttar Pradesh")!!, 83.56, 25.95) < 3)
        assertTrue(km(SdPlaces.placeOf("Bikaner", "Rajasthan")!!, 73.32, 28.02) < 3)
        assertTrue(km(SdPlaces.placeOf("Aizawl", "Mizoram")!!, 92.72, 23.73) < 3)
        assertTrue(km(SdPlaces.placeOf("Fatehpur", "Uttar Pradesh")!!, 80.81, 25.93) < 3)
        assertTrue(km(SdPlaces.placeOf("Haridwar", "Uttarakhand")!!, 78.16, 29.95) < 3)
        assertTrue(km(SdPlaces.placeOf("Leh", "Ladakh")!!, 77.58, 34.15) < 3)
        // old spellings still find their town
        assertTrue(km(SdPlaces.placeOf("Dehra Dun", "Uttarakhand")!!, 78.03, 30.32) < 3)
    }
}
