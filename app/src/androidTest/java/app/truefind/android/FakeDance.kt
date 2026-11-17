package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A pretend "liveDance" server for the automatic screen checks: the same
 * answers (fields and shapes) as base44/functions/liveDance/entry.ts.
 */
class FakeDance {
    private val calls = ArrayList<String>()
    private val timeline = ArrayList<String>()
    private val t0 = System.currentTimeMillis()
    private var paid = HashSet<String>()
    private var stateCount = HashMap<String, Int>()
    private val battleStart = HashMap<String, Long>()
    private val myVote = HashMap<String, String>()
    private val sent = HashMap<String, ArrayList<JSONObject>>()
    private var onStage = false
    var balance = 5115L

    fun log(): List<String> = synchronized(calls) { calls.toList() }
    /** "+1234ms state battle3" lines, to explain a timing problem */
    fun timeline(room: String): String = synchronized(calls) { timeline.filter { it.endsWith(" $room") }.joinToString(", ") }

    private fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun pic(name: String) = "https://api.dicebear.com/9.x/avataaars/png?seed=${name.replace(" ", "")}&size=256"
    private fun uid(id: String): Int { var h = 0; for (c in id) h = h * 31 + c.code; return (Math.abs(h) % 2_000_000_000) + 1000 }

    private fun host(name: String) = JSONObject().put("user_id", "h_$name").put("profile_id", "").put("name", name).put("photo", pic(name)).put("gender", "f").put("verified", true)

    private val ROOMS = listOf(
        Triple("solo1", "solo", "Priya Sharma" to "Bollywood Night 💃"),
        Triple("solo2", "solo", "Simran Kaur" to "Punjabi Bhangra Vibes"),
        Triple("solo3", "solo", "Neha Yadav" to "Haryanvi Thumka"),
        Triple("pay1", "solo", "Ananya Rao" to "Classical Evening 🪷"),
        Triple("battle1", "battle", "Riya Kapoor" to "Dance Battle Royale ⚔️"),
        Triple("battle2", "battle", "Kavya Singh" to "Who Dances Better?"),
        Triple("battle3", "battle", "Tanya Arora" to "Thumka vs Thumka"),
        Triple("duo1", "duo", "Karan Mehta" to "2 Dancer Face-off 🔥"),
        Triple("duo2", "duo", "Aditya Rao" to "Thumka Challenge"),
    )

    private fun dancersOf(id: String, mode: String): JSONArray {
        val a = JSONArray()
        fun d(seat: Int, name: String, uidStr: String = "d_$name") = a.put(JSONObject().put("seat", seat).put("user_id", uidStr).put("profile_id", "").put("name", name).put("photo", pic(name))
            .put("muted", false).put("host_muted", false).put("cam_off", false).put("agora_uid", uid(uidStr)).put("demo", true))
        when (id) {
            "battle1", "battle3" -> d(1, "Chhavi")
            "battle2" -> if (onStage) d(1, "Vikas", "me")
            "duo1", "duo2" -> { d(1, "Tara"); d(2, "Neha") }
        }
        if (mode == "duo" && id == "duo1") a.optJSONObject(1)?.put("muted", true)
        return a
    }

    private fun battleOf(id: String, mode: String, dancers: JSONArray, now: Long, ended: Boolean): JSONObject? {
        if (mode == "solo" || dancers.length() == 0 || id == "battle2") return null
        val h = ROOMS.first { it.first == id }.third.first
        val start = battleStart.getOrPut(id) { now - 107_000 } // a 5-minute battle that started 1:47 ago
        val a = if (mode == "battle") JSONObject().put("user_id", "h_$h").put("name", h).put("photo", pic(h)) else dancers.getJSONObject(0).let { JSONObject().put("user_id", it.getString("user_id")).put("name", it.getString("name")).put("photo", it.getString("photo")) }
        val b = (if (mode == "battle") dancers.getJSONObject(0) else dancers.getJSONObject(1)).let { JSONObject().put("user_id", it.getString("user_id")).put("name", it.getString("name")).put("photo", it.getString("photo")) }
        val voted = myVote[id].orEmpty()
        return JSONObject().put("id", "bt_$id").put("no", 3).put("status", if (ended) "ended" else "running").put("minutes", 5)
            .put("started_at", iso(start)).put("ends_at", iso(if (ended) now - 1000 else start + 300_000)).put("ended_at", if (ended) iso(now) else JSONObject.NULL)
            .put("a", a).put("b", b).put("votes_a", 148 + if (voted == a.getString("user_id")) 1 else 0).put("votes_b", 96 + if (voted == b.getString("user_id")) 1 else 0)
            .put("gifts_a", 1200).put("gifts_b", 640).put("winner", if (ended) a.getString("user_id") else "")
    }

    private fun top() = JSONArray().apply {
        listOf("Rahul" to 4200, "Arjun" to 2650, "Kabir" to 1500).forEach { (n, c) -> put(JSONObject().put("user_id", "u_$n").put("name", n).put("photo", pic(n)).put("coins", c)) }
    }

    private fun room(id: String, now: Long, ended: Boolean = false): JSONObject {
        val (rid, mode, hp) = ROOMS.firstOrNull { it.first == id } ?: Triple(id, "solo", "Priya Sharma" to "Bollywood Night 💃")
        val d = dancersOf(rid, mode)
        return JSONObject().put("id", rid).put("code", "TD-48291${ROOMS.indexOfFirst { it.first == id }.coerceAtLeast(0)}").put("title", hp.second).put("language", "Hindi").put("mode", mode)
            .put("live", true).put("live_since", iso(now - 23 * 60_000)).put("viewers", 1284 - ROOMS.indexOfFirst { it.first == id } * 97).put("fee", if (mode == "duo") 25 else 50)
            .put("host", host(hp.first)).put("battle", battleOf(rid, mode, d, now, ended) ?: JSONObject.NULL).put("top", top()).put("demo", true).put("demo_fill", false)
    }

    private fun msgs(id: String, now: Long): JSONArray {
        val r = room(id, now)
        val h = r.getJSONObject("host").getString("name")
        val a = JSONArray()
        fun m(i: Int, kind: String, who: String, text: String, gift: String = "") = a.put(JSONObject().put("id", "m_${id}_$i").put("kind", kind).put("user_id", "u_$who").put("profile_id", "")
            .put("name", who).put("photo", pic(who)).put("text", text).put("gift_id", gift).put("count", if (gift == "rose") 5 else 1).put("to_user_id", "").put("to_name", if (gift.isNotEmpty()) h else "")
            .put("at", iso(now - (8 - i) * 20_000)))
        a.put(JSONObject().put("id", "m_${id}_0").put("kind", "system").put("user_id", "h").put("name", h).put("photo", "").put("text", "$h started ${DK.mode(r.getString("mode")).label} 💃").put("gift_id", "").put("count", 1).put("to_user_id", "").put("to_name", "").put("at", iso(now - 200_000)))
        m(1, "join", "Rahul", "joined the room")
        m(2, "text", "Arjun", "Amazing dance! 🔥")
        m(3, "gift", "Rahul", "", "car")
        m(4, "text", "Kabir", "Ek Punjabi gaana please 🎶")
        m(5, "gift", "Dev", "", "rose")
        m(6, "text", "Ishaan", "Kya moves hain 👏 superb performance, keep going!")
        return a
    }

    private fun state(id: String, action: String, now: Long, p: JSONObject): Api.Result {
        if (id == "gone1") return Api.Result(404, JSONObject().put("error", "This room doesn't exist any more."), "This room doesn't exist any more.")
        val n = (stateCount[id] ?: 0) + if (action == "state") 1 else 0
        stateCount[id] = n
        val ended = id == "battle3" && n >= 2
        val r = room(id, now, ended)
        val mode = r.getString("mode")
        val d = dancersOf(id, mode)
        val hostO = JSONObject(r.getJSONObject("host").toString()).put("muted", false).put("cam_off", id == "solo2").put("agora_uid", uid(r.getJSONObject("host").getString("user_id"))).put("online", true)
        val base = JSONObject().put("ok", true).put("now", iso(now)).put("ended", id == "ended1").put("fee", r.getInt("fee")).put("room", r).put("host", hostO).put("dancers", d)
            .put("seats", when (mode) { "solo" -> 0; "battle" -> 1; else -> 2 })
        if (id == "ended1") return Api.Result(200, base.put("me", JSONObject().put("user_id", "me").put("role", "viewer").put("paid", true).put("in_room", false)), null)
        if (id == "pay1" && !paid.contains(id)) return Api.Result(200, base.put("need_pay", true).put("balance", balance)
            .put("me", JSONObject().put("user_id", "me").put("role", "viewer").put("paid", false).put("in_room", false).put("name", "Vikas").put("photo", pic("Vikas"))), null)
        val dancer = id == "battle2" && onStage
        base.put("likes", if (action == "state" && n % 3 == 1) 3 else 0).put("pending", JSONObject.NULL)
        base.put("me", JSONObject().put("user_id", "me").put("role", if (dancer) "dancer" else "viewer").put("seat", if (dancer) 1 else 0).put("paid", true).put("in_room", true)
            .put("muted", false).put("host_muted", false).put("cam_off", !dancer).put("agora_uid", uid("me")).put("name", "Vikas").put("photo", pic("Vikas"))
            .put("vote", myVote[id].orEmpty()).put("request", if (id == "solo3") JSONObject().put("id", "rq1").put("kind", "song").put("status", "pending").put("genre", "Punjabi").put("style", "Bhangra").put("at", iso(now)) else JSONObject.NULL))
        // chat: the starting lines on join, then what this person sent (newer than "since")
        val mine = synchronized(sent) { sent[id]?.toList().orEmpty() }
        val since = p.optString("since")
        val out = if (action == "join") msgs(id, now) else JSONArray()
        mine.filter { action == "join" || since.isEmpty() || it.optString("at") > since }.forEach { out.put(it) }
        base.put("messages", out)
        if (action == "join") base.put("balance", balance).put("blocked", JSONArray()).put("followed", false)
            .put("genres", JSONArray(DK.GENRES)).put("styles", JSONArray(DK.STYLES)).put("minutes", JSONArray(listOf(3, 5, 10, 15)))
        return Api.Result(200, base, null)
    }

    fun handle(name: String, p: JSONObject): Api.Result {
        val action = p.optString("action")
        synchronized(calls) {
            calls.add(if (name == "liveDance") action else name)
            timeline.add("+${System.currentTimeMillis() - t0}ms ${if (name == "liveDance") action else name} ${p.optString("room_id")}")
        }
        Thread.sleep(120) // a little network time
        val now = System.currentTimeMillis()
        if (name == "liveDanceToken") return Api.Result(200, JSONObject().put("ok", true).put("enabled", false), null)
        val id = p.optString("room_id")
        val ok = JSONObject().put("ok", true)
        return when (action) {
            "home" -> {
                val rooms = JSONArray(); ROOMS.forEach { rooms.put(room(it.first, now)) }
                Api.Result(200, JSONObject().put("ok", true).put("rooms", rooms).put("can_host", true).put("my_room", JSONObject.NULL).put("is_admin", true).put("balance", balance)
                    .put("languages", JSONArray(listOf("Hindi", "English", "Hinglish"))).put("genres", JSONArray(DK.GENRES)).put("styles", JSONArray(DK.STYLES)), null)
            }
            "join", "state" -> state(id, action, now, p)
            "vote" -> { myVote[id] = p.optString("for_user_id"); Api.Result(200, JSONObject().put("ok", true), null) }
            "message" -> {
                val m = JSONObject().put("id", "s_${now}").put("kind", "text").put("user_id", "me").put("profile_id", "").put("name", "Vikas").put("photo", pic("Vikas"))
                    .put("text", p.optString("text")).put("gift_id", "").put("count", 1).put("to_user_id", "").put("to_name", "").put("at", iso(now))
                synchronized(sent) { sent.getOrPut(id) { ArrayList() }.add(m) }
                Api.Result(200, JSONObject().put("ok", true), null)
            }
            "pay" -> { paid.add(id); balance -= 50; Api.Result(200, ok.put("balance", balance), null) }
            "gift" -> {
                val g = DK.gift(p.optString("gift_id")); val cost = (g?.price ?: 0) * p.optInt("count", 1)
                if (cost > balance) Api.Result(402, JSONObject().put("error", "insufficient_coins").put("balance", balance), "insufficient_coins")
                else { balance -= cost; Api.Result(200, ok.put("balance", balance), null) }
            }
            "seat_request" -> { onStage = true; Api.Result(200, ok.put("accepted", true).put("seat", 1), null) }
            "supporters" -> Api.Result(200, JSONObject().put("ok", true).put("top", JSONArray().apply {
                listOf("Rahul" to 4200, "Arjun" to 2650, "Kabir" to 1500, "Ishaan" to 900, "Dev" to 640, "Yash" to 300).forEachIndexed { i, (n, c) -> put(JSONObject().put("rank", i + 1).put("user_id", "u_$n").put("name", n).put("photo", pic(n)).put("coins", c)) }
            }), null)
            "battles" -> {
                val r = room(id, now); val b = r.optJSONObject("battle")
                val h = JSONArray()
                if (b != null) { h.put(JSONObject(b.toString()).put("id", "o2").put("no", 2).put("status", "ended").put("votes_a", 80).put("votes_b", 112).put("winner", b.getJSONObject("b").getString("user_id")))
                    h.put(JSONObject(b.toString()).put("id", "o1").put("no", 1).put("status", "ended").put("votes_a", 60).put("votes_b", 60).put("winner", "tie")) }
                Api.Result(200, JSONObject().put("ok", true).put("current", b ?: JSONObject.NULL).put("history", h), null)
            }
            "users" -> Api.Result(200, JSONObject().put("ok", true).put("total", 9).put("online", 8).put("users", JSONArray().apply {
                put(JSONObject().put("user_id", "h").put("name", "Priya Sharma").put("photo", pic("Priya Sharma")).put("role", "host").put("seat", 0).put("online", true))
                listOf("Rahul", "Arjun", "Kabir", "Ishaan", "Dev", "Yash").forEach { n -> put(JSONObject().put("user_id", "u_$n").put("name", n).put("photo", pic(n)).put("role", "viewer").put("seat", 0).put("online", true)) }
                put(JSONObject().put("user_id", "u_Pooja").put("name", "Pooja").put("photo", pic("Pooja")).put("role", "viewer").put("seat", 0).put("online", false))
            }), null)
            "follow" -> Api.Result(200, ok.put("followed", p.optBoolean("on")), null)
            "demo_seed" -> Api.Result(200, ok.put("created", 30), null)
            else -> Api.Result(200, ok, null)
        }
    }
}
