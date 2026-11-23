package app.truefind.android

/**
 * The rules for starting and ending a call — pure logic, no Android parts, so they are checked on a computer
 * (CallRulesTest). Owner, 23 Nov: "<name> is busy — on another call" showed for people who were on no call at all,
 * and a tap on Call turned the loading circle and then no call came.
 *
 * What went wrong: a tap on Call met the server busy for a moment (its shared allowance used up). The server had
 * already made the call and marked both people "on a call", then failed half way; the phone got no proper answer,
 * showed no call screen, and nothing ever closed that call. For the next minute every call found it: the other
 * person "busy" (their phone was still marked), and the CALLER "already on a call" — which the app also showed as
 * "<name> is busy".
 *
 *  - every tap carries a one-time key; a lost / busy / failing answer is asked again with the same key (the server
 *    answers with the call that tap already made — never a second call), a few times, then whatever that tap may
 *    have made is closed (endCall "abandon")
 *  - 409 tells WHO is busy: "peer_busy" (the other person really is on a call) or "self_busy" (I am)
 *  - a hang-up that didn't reach the server is sent again by itself until the server has it (CallEnder)
 */
object CallRules {
    enum class Busy { NONE, PEER, SELF }

    /** who is busy, from startCall's answer. Older servers: error "busy" = them, "You are already on a call." = me. */
    fun busyKind(status: Int, error: String, code: String): Busy {
        if (status != 409) return Busy.NONE
        if (code == "self_busy" || error.contains("already on a call", ignoreCase = true)) return Busy.SELF
        return Busy.PEER
    }

    /** tries of one tap (the first + asked again) */
    const val START_TRIES = 4

    /** no answer, the server busy or failing for a moment: the same tap may be asked again (same key) */
    fun retryStart(status: Int): Boolean = status == 0 || status == 408 || status == 429 || status in 500..599

    /** the server may have made a call although the phone never got it: if we give up, it must be closed */
    fun mayHaveMade(status: Int): Boolean = retryStart(status)

    /**
     * Wait before asking again (ms): what the server said (kept between 1 and 5 s), otherwise 1.2 s, 2.5 s, 4 s.
     * The whole tap stays well under half a minute even when every answer is lost.
     */
    fun startWaitMs(attempt: Int, retryAfterSec: Double?): Long {
        val said = retryAfterSec?.takeIf { !it.isNaN() && it > 0 }?.let { (it * 1000).toLong().coerceIn(1000L, 5000L) }
        return said ?: longArrayOf(1200L, 2500L, 4000L)[attempt.coerceIn(0, 2)]
    }

    /** how long one try may take (ms): the first a little longer (the server rings their phone before it answers) */
    fun startTimeoutMs(attempt: Int): Int = if (attempt == 0) 20000 else 15000

    // ── ending a call for sure (CallEnder) ──

    /** endCall's answer settles it: done (also "not found" / "can't end now": the call is over or not mine) */
    fun endDone(status: Int): Boolean = status in 200..299 || (status in 400..499 && status != 401 && status != 408 && status != 429)

    /** endCall may be asked again: no answer, the server busy or failing for a moment, or not signed in yet */
    fun endRetry(status: Int): Boolean = status == 0 || status == 401 || status == 408 || status == 429 || status in 500..599

    /** waits between tries of one end (ms): quick at first, then calmer */
    fun endWaitMs(attempt: Int): Long = longArrayOf(1500L, 3000L, 6000L, 10000L, 20000L, 30000L)[attempt.coerceIn(0, 5)]

    /** after this long the server's own clean-up has long closed it: the phone stops asking */
    const val END_GIVE_UP_MS = 15 * 60_000L

    /** a one-time key for one tap (letters and digits, as the server accepts: 8–64) */
    fun newKey(): String = "c" + java.util.UUID.randomUUID().toString().replace("-", "")
}
