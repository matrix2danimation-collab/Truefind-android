package app.truefind.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** starting and ending calls (owner, 23 Nov: "busy" for people on no call; a call that spun and never came) */
class CallRulesTest {
    @Test fun herCallIsPeerBusy() {
        assertEquals(CallRules.Busy.PEER, CallRules.busyKind(409, "busy", "peer_busy"))
        assertEquals(CallRules.Busy.PEER, CallRules.busyKind(409, "busy", ""))           // an older server
    }
    @Test fun myOwnCallIsSelfBusy() {
        assertEquals(CallRules.Busy.SELF, CallRules.busyKind(409, "You are already on a call.", "self_busy"))
        assertEquals(CallRules.Busy.SELF, CallRules.busyKind(409, "You are already on a call.", "")) // an older server
    }
    @Test fun aBusyServerIsNeverSomeonesBusy() {
        assertEquals(CallRules.Busy.NONE, CallRules.busyKind(429, "busy", ""))
        assertEquals(CallRules.Busy.NONE, CallRules.busyKind(429, "The server is busy. Please try again in a moment.", "start_failed"))
        assertEquals(CallRules.Busy.NONE, CallRules.busyKind(0, "", ""))
        assertEquals(CallRules.Busy.NONE, CallRules.busyKind(200, "", ""))
    }
    @Test fun lostOrBusyStartsAreAskedAgain() {
        for (st in listOf(0, 408, 429, 500, 502, 503)) assertTrue("status $st", CallRules.retryStart(st))
        for (st in listOf(200, 400, 402, 403, 404, 409)) assertFalse("status $st", CallRules.retryStart(st))
    }
    @Test fun aStartThatMayHaveMadeACallIsClosed() {
        assertTrue(CallRules.mayHaveMade(0)); assertTrue(CallRules.mayHaveMade(429)); assertTrue(CallRules.mayHaveMade(500))
        assertFalse(CallRules.mayHaveMade(402)); assertFalse(CallRules.mayHaveMade(409)); assertFalse(CallRules.mayHaveMade(403))
    }
    @Test fun waitsStayShort() {
        assertEquals(1200L, CallRules.startWaitMs(0, null))
        assertEquals(2500L, CallRules.startWaitMs(1, null))
        assertEquals(4000L, CallRules.startWaitMs(2, null))
        assertEquals(4000L, CallRules.startWaitMs(7, null))
        assertEquals(3000L, CallRules.startWaitMs(0, 3.0))   // what the server said
        assertEquals(5000L, CallRules.startWaitMs(0, 30.0))  // never longer than 5 s
        assertEquals(1000L, CallRules.startWaitMs(0, 0.2))   // never shorter than 1 s
        assertEquals(1200L, CallRules.startWaitMs(0, Double.NaN))
        // the whole tap, every answer lost: 4 tries (20 s + 3 × 15 s at most) + 1.2 + 2.5 + 4 s of waiting
        assertEquals(4, CallRules.START_TRIES)
        assertEquals(20000, CallRules.startTimeoutMs(0)); assertEquals(15000, CallRules.startTimeoutMs(3))
    }
    @Test fun endsAreSentUntilTheServerHasThem() {
        for (st in listOf(0, 401, 408, 429, 500, 503)) { assertTrue("retry $st", CallRules.endRetry(st)); assertFalse("done $st", CallRules.endDone(st)) }
        for (st in listOf(200, 400, 403, 404, 409)) { assertTrue("done $st", CallRules.endDone(st)); assertFalse("retry $st", CallRules.endRetry(st)) }
        assertEquals(1500L, CallRules.endWaitMs(0)); assertEquals(30000L, CallRules.endWaitMs(5)); assertEquals(30000L, CallRules.endWaitMs(99))
        assertEquals(15 * 60_000L, CallRules.END_GIVE_UP_MS)
    }
    @Test fun aDeclineIsSentUntilTheServerHasIt() {
        // respondCall "decline" answers like endCall: 200 declined · 404 / 409 the call is over already (done) ·
        // a busy server (429), no answer, a server error or not signed in yet → sent again (23 Nov: a 429 was
        // counted as "done" by the notification's Decline, so the ring went on and she showed Busy)
        for (st in listOf(200, 404, 409)) assertTrue("done $st", CallRules.endDone(st))
        for (st in listOf(0, 401, 408, 429, 500, 502)) assertTrue("again $st", CallRules.endRetry(st) && !CallRules.endDone(st))
    }
    @Test fun keysAreWhatTheServerAccepts() {
        val a = CallRules.newKey(); val b = CallRules.newKey()
        assertTrue(a != b)
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(a))
    }
}
