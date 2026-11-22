package app.truefind.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** the chat's "Connecting…" bar: only after 2 s down, gone at once when back (same as the web) */
class ConnWatchTest {
    @Test fun upMeansNoBar() {
        val w = ConnWatch(); w.state(true, 0)
        assertEquals("", w.text(10_000, false)); assertEquals(-1L, w.waitMs(10_000))
    }
    @Test fun shortBlinkNeverShows() {
        val w = ConnWatch(); w.state(false, 1000)
        assertEquals("", w.text(2999, false)); assertEquals(1L, w.waitMs(2999))
        w.state(true, 2500); assertEquals("", w.text(5000, false))
    }
    @Test fun afterTwoSecondsTheBarShows() {
        val w = ConnWatch(); w.state(false, 1000)
        assertEquals("Connecting…", w.text(3000, false))
        assertEquals("No internet · trying to reconnect", w.text(3000, true))
    }
    @Test fun repeatedDownKeepsTheFirstTime() {
        val w = ConnWatch(); w.state(false, 1000); w.state(false, 2900)
        assertEquals("Connecting…", w.text(3000, false))
    }
    @Test fun backUpHidesAtOnce() {
        val w = ConnWatch(); w.state(false, 0); assertEquals("Connecting…", w.text(5000, false))
        w.state(true, 5001); assertEquals("", w.text(5002, false))
    }
}
