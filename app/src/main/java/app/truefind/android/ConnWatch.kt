package app.truefind.android

/**
 * When the chat's "Connecting…" bar shows (pure logic, tested in ConnWatchTest): the same as the web's
 * ChatRoom.jsx (owner chose "only when disconnected"): nothing while the live connection is up; a drop
 * shows the bar only after it has lasted 2 seconds (a short blink never flashes the bar), and the bar
 * goes at once when the connection is back. Without internet the words say so.
 */
class ConnWatch(private val delayMs: Long = 2000L) {
    private var downSince = -1L
    /** the connection went up (true) or down (false) at [now] */
    fun state(up: Boolean, now: Long) { downSince = if (up) -1L else if (downSince < 0) now else downSince }
    /** the bar's words at [now]: "" = no bar */
    fun text(now: Long, phoneOffline: Boolean): String {
        if (downSince < 0 || now - downSince < delayMs) return ""
        return if (phoneOffline) "No internet · trying to reconnect" else "Connecting…"
    }
    /** how long until the bar may appear (to check again then) */
    fun waitMs(now: Long): Long = if (downSince < 0) -1L else maxOf(0L, delayMs - (now - downSince))
}
