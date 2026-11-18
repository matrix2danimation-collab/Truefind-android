package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the app is, and where Back goes — kept by the app itself.
 *
 * Before, the web page hidden under the native screens kept this: every tap went to its router and came
 * back as "the address is now …", and the phone ran two apps at once (that is what made page changes hang).
 * This works the way the web app's router did, so every screen written against it behaves the same:
 *  - go(address, state)           a new page on top                  (navigate(path, { state }))
 *  - go(address, state, true)     in place of the page on screen     (navigate(path, { replace: true }))
 *  - back(n)                      n pages back                       (navigate(-n)), with the fixed Back steps of
 *                                 the web's PhoneBack.jsx: Wallet → Recent → Explore → Home, a screen inside
 *                                 Profile → the Profile page → Home
 * An address is a path with an optional ?query ("/profile?settings=1"); [state] is what the web kept in
 * history.state.usr (Profile's tfDepth, a chat's openChat …).
 */
class Router {
    class Entry(val path: String, val search: String, val state: JSONObject?) {
        val url: String get() = path + search
    }

    enum class Move { RESET, PUSH, REPLACE, POP }

    companion object {
        const val HOME = "/home"

        fun parse(full: String, state: JSONObject? = null): Entry {
            val hash = full.indexOf('#')
            val s = if (hash >= 0) full.substring(0, hash) else full
            val q = s.indexOf('?')
            var path = if (q >= 0) s.substring(0, q) else s
            if (!path.startsWith("/")) path = "/$path"
            var search = if (q >= 0) s.substring(q) else ""
            if (search == "?") search = ""
            return Entry(path, search, state)
        }

        fun pathOnly(p: String): String = p.substringBefore('?').trimEnd('/').ifEmpty { "/" }

        /** the same page? ("/" and "/home" are one page; a trailing "/" doesn't count) */
        fun same(a: String, b: String): Boolean = norm(a) == norm(b)
        private fun norm(u: String): String {
            val q = u.indexOf('?')
            val p = pathOnly(u)
            val query = if (q >= 0) u.substring(q + 1) else ""
            return (if (p == "/") HOME else p) + (if (query.isNotEmpty()) "?$query" else "")
        }

        /** The fixed step above a page for Back (PhoneBack.jsx). null: Back goes where you came from. */
        fun stepAbove(path: String, search: String): String? = when (pathOnly(path)) {
            "/explore" -> HOME
            "/chats" -> "/explore"
            "/wallet" -> "/chats"
            "/profile/edit" -> "/profile"
            "/profile" -> if (search.isNotEmpty() && search != "?") "/profile" else HOME
            else -> null
        }
    }

    private val stack = ArrayList<Entry>()
    /** called after every change, with what kind of move it was */
    var onChange: (Move) -> Unit = {}

    val current: Entry get() = stack.lastOrNull() ?: Entry("/", "", null)
    val path: String get() = current.path
    val search: String get() = current.search
    val state: JSONObject? get() = current.state
    /** the place of the page on screen in the history (0 = the first page) */
    val idx: Int get() = stack.size - 1
    val canGoBack: Boolean get() = stack.size > 1
    val started: Boolean get() = stack.isNotEmpty()

    /**
     * The history starts again at [full] (signing in, the app opening). [under]: a page that lies below it
     * (Home under a page opened from a notification), so Back has somewhere to go.
     */
    fun reset(full: String, state: JSONObject? = null, under: String? = null) {
        stack.clear()
        if (under != null && !same(under, full)) stack.add(parse(under))
        stack.add(parse(full, state))
        onChange(Move.RESET)
    }

    /** Forgets everything (signing out). No change is reported. */
    fun clear() { stack.clear() }

    fun go(full: String, state: JSONObject? = null, replace: Boolean = false) {
        val e = parse(full, state)
        if (stack.isEmpty()) { stack.add(e); onChange(Move.RESET); return }
        if (replace) {
            stack[stack.size - 1] = e
            onChange(Move.REPLACE)
            return
        }
        // the same page with the same state again (a second tap while the page was opening): one page, not two
        val cur = stack.last()
        if (cur.url == e.url && sameState(cur.state, e.state)) return
        stack.add(e)
        // (a history that long is never walked back: the oldest pages go)
        while (stack.size > 200) stack.removeAt(0)
        onChange(Move.PUSH)
    }

    /** Changes only the ?query of the page on screen (the web's history.replaceState: same page, same state). */
    fun replaceSearch(search: String) {
        if (stack.isEmpty()) return
        val cur = stack.last()
        val s = if (search.isEmpty() || search == "?") "" else if (search.startsWith("?")) search else "?$search"
        if (cur.search == s) return
        stack[stack.size - 1] = Entry(cur.path, s, cur.state)
        onChange(Move.REPLACE)
    }

    /**
     * [n] pages back. false when there is no page behind (nothing changes; the caller decides: the step
     * above, or leaving the app). The fixed Back steps of PhoneBack.jsx apply to where it lands.
     */
    fun back(n: Int = 1): Boolean {
        if (stack.size <= 1) return false
        var prev = stack.last()
        repeat(minOf(maxOf(1, n), stack.size - 1)) { stack.removeAt(stack.size - 1) }
        var guard = 0
        while (guard++ < 8) {
            val cur = stack.last()
            if (same(prev.url, cur.url)) break                       // the same address: nothing to fix
            val target = stepAbove(prev.path, prev.search) ?: break   // the page left has no fixed step
            if (same(target, cur.url)) break                         // Back landed on its step already
            // Inside Profile the screens are stacked on the Profile page (tfDepth = how deep): step back
            // through them to that same page instead of putting a second copy of it on top.
            val depth = cur.state?.optInt("tfDepth", 0) ?: 0
            if (depth > 0 && cur.path == prev.path && same(target, cur.path)) {
                val k = minOf(depth, stack.size - 1)
                if (k <= 0) break
                prev = cur
                repeat(k) { stack.removeAt(stack.size - 1) }
                continue
            }
            if (stack.size == 1 && !same(target, HOME)) {
                // Back reached the first page of the app: Home stays underneath so the next Back still has
                // somewhere to go, then the step shows.
                stack[0] = Entry(HOME, "", null)
                stack.add(parse(target))
            } else stack[stack.size - 1] = parse(target)
            break
        }
        onChange(Move.POP)
        return true
    }

    /**
     * Back when there is no page behind: the step above the page on screen, else Home (in place of this
     * page, moving like a Back). false on Home itself: the app is left.
     */
    fun up(): Boolean {
        if (stack.isEmpty()) return false
        val cur = stack.last()
        val here = pathOnly(cur.path)
        val target = stepAbove(cur.path, cur.search) ?: if (here != "/" && here != HOME) "/" else return false
        if (same(target, cur.url)) return false
        stack[stack.size - 1] = parse(target)
        onChange(Move.POP)
        return true
    }

    private fun sameState(a: JSONObject?, b: JSONObject?): Boolean =
        (a == null && b == null) || (a != null && b != null && a.toString() == b.toString())

    // ── kept when Android stops the app in the background and starts it again ──
    fun save(): String = write(stack)
    /** the history without the page on screen (null: there is no page behind it) */
    fun saveBelow(): String? = if (stack.size > 1) write(stack.subList(0, stack.size - 1)) else null
    private fun write(list: List<Entry>): String {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("p", e.path).put("s", e.search).apply { e.state?.let { put("st", it) } })
        return a.toString()
    }

    /** true when a history was read back (no change is reported: the caller shows the page) */
    fun restore(json: String?): Boolean {
        if (json.isNullOrEmpty()) return false
        return try {
            val a = JSONArray(json)
            val list = ArrayList<Entry>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val p = o.optString("p")
                if (p.startsWith("/")) list.add(Entry(p, o.optString("s"), o.optJSONObject("st")))
            }
            if (list.isEmpty()) false else { stack.clear(); stack.addAll(list); true }
        } catch (e: Exception) { false }
    }
}
