package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Demo chats (testing only — they go with SdDemo.SHOW). The owner, 23 Nov: on a demo profile "Send Request दबाते ही
 * सीधा chat खुले", and a tap on a chat must open the chat, never the profile first. So a demo person's chat opens as a
 * chat room like any other: the server calls the chat room makes for a "demo-chat-<n>" are answered here, on the phone.
 * Its first lines are made here, what the member types is kept while the app is open, gifts are not sent (no coin is
 * ever spent) and nothing reaches a server.
 *   chat "demo-chat-<n>" = the n-th demo person of the gender this member sees (profile "demo-sd-f<n>" / "demo-sd-m<n>");
 *   0–4 are the chat list's five demo chats (the web's sdDemoChats), the others start with Send Request on a demo profile.
 */
object SdDemoChat {
    /** who "I" am in a demo chat's lines (the chat room takes this id as its own, beside the member's real one) */
    const val SELF = "demo-self"
    private const val PRE = "demo-chat-"
    private val LIST_TEXTS = listOf("Hey! How are you? 😊", "Nice to meet you!", "Let's talk soon 💕", "Hi 👋", "Good morning!")

    /** the member's gender ("f": she sees the demo boys; anything else: the demo girls) — set where the demo people are made */
    @Volatile var viewer = ""

    class Line(val id: String, val mine: Boolean, val text: String, val at: Long)

    private val lines = HashMap<String, ArrayList<Line>>()
    private val pinned = HashSet<String>()
    private val started = ArrayList<String>()          // by Send Request, the oldest first
    private val startedAt = HashMap<String, Long>()
    private val touched = HashSet<String>()            // opened or written in since the app started
    private val gone = HashSet<String>()               // deleted from the chat's ⋮ menu
    private var seq = 0

    private fun index(id: String?): Int = if (id != null && id.startsWith(PRE)) id.removePrefix(PRE).toIntOrNull() ?: -1 else -1
    fun owns(matchId: String?): Boolean = SdDemo.SHOW && index(matchId) in 0..99
    /** the demo chat of a demo profile ("" for anyone else) */
    fun idFor(p: JSONObject?): String {
        if (p == null || !SdDemo.isDemo(p)) return ""
        val n = Regex("^demo-sd-[fm](\\d+)$").find(p.optString("id"))?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        return PRE + n
    }
    fun person(matchId: String): JSONObject? = SdDemo.people(viewer).getOrNull(index(matchId))
    private fun userOf(matchId: String) = "demo-user-${index(matchId)}"
    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /**
     * a demo chat's first lines. The list's five end with the words their row shows (mine where the row says "You:");
     * a chat started with Send Request begins empty ("Say hi to …").
     */
    fun firstLines(n: Int, now: Long): List<Line> {
        if (n !in 0..4) return emptyList()
        val last = LIST_TEXTS[n]
        val at = now - (5L + n * 23L) * 60_000L
        val mine = n % 2 == 0
        val before = if (mine) Line("demo-msg-$n-a", false, if (last.startsWith("Hi")) "Hello 🙂" else "Hi 👋", at - 14 * 60_000L)
                     else Line("demo-msg-$n-a", true, if (last.startsWith("Hi")) "Hello!" else "Hi! 😊", at - 9 * 60_000L)
        return listOf(before, Line("demo-msg-$n-b", mine, last, at))
    }
    private fun linesOf(id: String, now: Long = System.currentTimeMillis()): ArrayList<Line> =
        lines.getOrPut(id) { ArrayList(firstLines(index(id), now)) }

    private fun json(chat: String, l: Line): JSONObject {
        val other = userOf(chat)
        return JSONObject().put("id", l.id).put("match_id", chat).put("from_user_id", if (l.mine) SELF else other).put("to_user_id", if (l.mine) other else SELF)
            .put("kind", "text").put("text", l.text).put("gif_id", "").put("created_date", iso(l.at)).put("read", true)
    }
    /** the chat's lines, the oldest first */
    @Synchronized fun messages(id: String, now: Long = System.currentTimeMillis()): List<JSONObject> = linesOf(id, now).map { json(id, it) }

    /** what the chat room asks for about one chat (getChats with "only") */
    @Synchronized fun chat(id: String): JSONObject? {
        val p = person(id) ?: return null
        val last = linesOf(id).lastOrNull()
        return JSONObject().put("match_id", id).put("app", "secret").put("pinned", id in pinned).put("demo", true)
            .put("other", JSONObject().put("name", p.optString("name")).put("photo", p.optString("photo")).put("photo_thumb", "")
                .put("profile_id", p.optString("id")).put("user_id", userOf(id)).put("verified", true).put("online", p.optBoolean("online"))
                .put("last_active", p.optString("last_active")).put("age", p.optInt("age")).put("gender", p.optString("gender"))
                .put("city", p.optString("city")).put("state", p.optString("state")))
            .put("last_message", last?.let { json(id, it) } ?: JSONObject.NULL).put("last_message_at", last?.let { iso(it.at) } ?: JSONObject.NULL)
            .put("unread_count", 0)
    }

    /** Send Request on a demo profile, or a tap on a demo chat: the chat exists from now on (and shows in the chat list) */
    @Synchronized fun start(id: String, now: Long = System.currentTimeMillis()) {
        if (!owns(id)) return
        gone.remove(id); touched.add(id)
        if (index(id) > 4 && id !in started) { started.add(id); startedAt[id] = now }
    }
    @Synchronized fun isStarted(id: String): Boolean = owns(id) && id !in gone && (index(id) <= 4 || id in started)

    private fun ok(d: JSONObject) = Api.Result(200, d, null)
    private fun no(msg: String) = Api.Result(403, JSONObject().put("error", "demo").put("message", msg), msg)

    /** a server function the app calls: answered here when it is about a demo chat / a demo person, else null (the server answers) */
    @Synchronized fun function(name: String, payload: JSONObject, now: Long = System.currentTimeMillis()): Api.Result? {
        if (!SdDemo.SHOW) return null
        val id = payload.optString("match_id")
        when (name) {
            "getChats" -> if (owns(id)) return ok(JSONObject().put("chats", JSONArray().apply { chat(id)?.let { put(it) } }))
            "sendMessage" -> if (owns(id)) {
                if (payload.optString("kind", "text") != "text") return no("Demo chat: gifts go to real members only.")
                val text = payload.optString("text").trim()
                if (text.isEmpty()) return no("Write a message first.")
                val l = Line("demo-msg-${index(id)}-n${++seq}", true, text, now)
                linesOf(id, now).add(l); touched.add(id)
                return ok(JSONObject().put("ok", true).put("message", json(id, l).put("read", false)))
            }
            "setTyping" -> if (owns(id)) return ok(JSONObject().put("ok", true))
            "pinChat" -> if (owns(id)) {
                val on = payload.optBoolean("pin")
                if (on) pinned.add(id) else pinned.remove(id)
                return ok(JSONObject().put("ok", true).put("pinned", on))
            }
            "deleteChat" -> if (owns(id)) {
                lines.remove(id); pinned.remove(id); started.remove(id); startedAt.remove(id); touched.remove(id); gone.add(id)
                return ok(JSONObject().put("ok", true))
            }
            "moderateUser" -> if (payload.optString("profile_id").startsWith("demo-sd-")) return Api.Result(403, JSONObject().put("error", "This is a demo profile."), "This is a demo profile.")
        }
        return null
    }

    /** entities.X.filter: a demo chat's messages (the newest first, as the server sends them) and a demo person's profile */
    @Synchronized fun filter(entity: String, query: JSONObject, now: Long = System.currentTimeMillis()): Api.Result? {
        if (!SdDemo.SHOW) return null
        if (entity == "Message") {
            val id = query.optString("match_id")
            if (owns(id)) { val a = JSONArray(); val all = linesOf(id, now); for (k in all.size - 1 downTo 0) a.put(json(id, all[k])); return ok(JSONObject().put("items", a)) }
        }
        if (entity == "UserProfile") {
            val pid = query.optString("id")
            if (pid.startsWith("demo-sd-")) return ok(JSONObject().put("items", JSONArray().apply { profile(pid)?.let { put(it) } }))
        }
        return null
    }
    /** a write to a demo chat's messages (marking them read): nothing to do, and nothing to send */
    fun ownsQuery(query: JSONObject?): Boolean = owns(query?.optString("match_id"))
    fun profile(pid: String): JSONObject? = (SdDemo.people("m") + SdDemo.people("f")).firstOrNull { it.optString("id") == pid }?.let { JSONObject(it.toString()) }

    /**
     * The chat list's demo rows: the chats Send Request started (the newest on top), then the web's five
     * ([SdChatList.demoChats]) — each with the last words written in it since, and nothing unread once it was opened.
     */
    @Synchronized fun rows(myGender: String, now: Long = System.currentTimeMillis()): List<SdChatsPage.Chat> {
        if (!SdDemo.SHOW) return emptyList()
        viewer = myGender
        val people = SdDemo.people(myGender)
        val out = ArrayList<SdChatsPage.Chat>()
        fun row(id: String, base: SdChatsPage.Chat?): SdChatsPage.Chat? {
            val p = people.getOrNull(index(id)) ?: return null
            val last = lines[id]?.lastOrNull()
            return SdChatsPage.Chat(id = id, name = p.optString("name"), photo = p.optString("photo"), photoThumb = p.optString("photo_thumb"),
                age = p.opt("age")?.toString().orEmpty(), pid = p.optString("id"), uid = "", online = p.optBoolean("online"), verified = p.optBoolean("verified"),
                text = last?.text ?: base?.text.orEmpty(), from = "", time = last?.let { iso(it.at) } ?: base?.time ?: iso(startedAt[id] ?: now),
                unread = 0, open = true, pinned = id in pinned, demo = true, mine = last?.mine ?: (base?.mine == true))
        }
        for (k in started.size - 1 downTo 0) row(started[k], null)?.let { out.add(it) }
        for (c in SdChatList.demoChats(myGender, now)) {
            if (c.id in gone) continue
            out.add(if (c.id in touched || c.id in pinned) (row(c.id, c) ?: c) else c)
        }
        return out
    }

    /** tests only: as after a fresh start of the app */
    @Synchronized fun reset() { lines.clear(); pinned.clear(); started.clear(); startedAt.clear(); touched.clear(); gone.clear(); seq = 0; viewer = "" }
}
