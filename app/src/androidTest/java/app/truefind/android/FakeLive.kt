package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A pretend "liveRoom" server for the automatic screen checks: the same answers
 * (fields and shapes) as base44/functions/liveRoom/entry.ts, plus liveRoomToken,
 * getWallet, moderateUser and the screenshot upload.
 *   a1 … a4  audio rooms (a2 has all 5 seats taken) · v9 a video room (the app must not list it)
 *   inv1     the host has invited me from the Join list (Pay & Join pops up)
 *   co1      I sit on a Co-Host seat
 *   h1       my own room (I am the host) — goes live with "go_live"
 *   poor1    a room where my coins are not enough
 *   ended1   the live has ended · gone1  the room does not exist
 *   voice1   the voice pass is real (Agora is loaded with a made-up App ID, nothing is joined for real)
 */
class FakeLive {
    private val calls = ArrayList<String>()
    private val bodies = ArrayList<String>()
    private val t0 = System.currentTimeMillis()
    @Volatile var balance = 1240L
    private var msgSeq = 0
    private var hostLive = false
    private var demoSeeded = false

    private class Room(
        val id: String, val code: String, val name: String, val hostId: String, val hostName: String,
        val category: String, val language: String, val level: Int, var viewers: Long, val mode: String = "audio",
    ) {
        var live = true
        val cohosts = ArrayList<JSONObject>()
        val wish = ArrayList<JSONObject>()
        val msgs = ArrayList<JSONObject>()
        var hostMuted = false
        var myRole = "viewer"
        var myWish = false
        var myInvited = false
        var myMuted = true
        var myHostMuted = false
        var followed = false
        var likesNext = 0
        var description = ""
        var kicked = HashSet<String>()
    }

    private val rooms = LinkedHashMap<String, Room>()

    init {
        fun person(n: String, muted: Boolean = false) = JSONObject().put("user_id", "u_$n").put("profile_id", "p_$n").put("name", n).put("photo", pic(n)).put("gender", "")
            .put("muted", muted).put("host_muted", false).put("cam_off", true).put("agora_uid", uid("u_$n"))
        fun add(r: Room) { rooms[r.id] = r }
        add(Room("a1", "TF-482913", "Music Vibes 🎵", "host_aman", "Aman Verma", "Music", "Hindi", 12, 760).apply {
            cohosts.add(person("Rahul")); cohosts.add(person("Ananya", true)); cohosts.add(person("Kabir"))
            wish.add(wishOf("Ishaan", 3)); wish.add(wishOf("Sara", 2))
            description = "Old and new Hindi songs. Request your song with a Super Chat 🎶"
        })
        add(Room("a2", "TF-551208", "Open Talk", "host_vikram", "Vikram Rao", "Chat", "English", 9, 430).apply {
            listOf("Dev", "Tara", "Rohan", "Aditi", "Karan").forEach { cohosts.add(person(it)) }
        })
        add(Room("a3", "TF-310877", "Friends Adda", "host_kavya", "Kavya Iyer", "Friends", "Hindi", 4, 220))
        add(Room("a4", "TF-907344", "Antakshari Night 🎶", "host_sneha", "Sneha Kapoor", "Music", "Hinglish", 16, 12100).apply { cohosts.add(person("Meera")) })
        add(Room("v9", "TF-120005", "Video Chat Room", "host_riya", "Riya Sharma", "Chat", "Hindi", 15, 1840, mode = "video"))
        add(Room("inv1", "TF-662013", "Shayari Mehfil", "host_rohan", "Rohan Gupta", "Chat", "Hindi", 11, 540).apply {
            cohosts.add(person("Yash")); myWish = true; myInvited = true; wish.add(wishOf("Vikas", 1).put("user_id", "me").put("invited", true))
        })
        add(Room("co1", "TF-731150", "Single & Mingle 💜", "host_tanya", "Tanya Arora", "Dating", "Hinglish", 14, 890).apply {
            cohosts.add(person("Nikhil")); cohosts.add(JSONObject().put("user_id", "me").put("profile_id", "p_me").put("name", "Vikas").put("photo", pic("Vikas")).put("gender", "m")
                .put("muted", false).put("host_muted", false).put("cam_off", true).put("agora_uid", uid("me")))
            myRole = "cohost"; myMuted = false
        })
        add(Room("h1", "TF-204466", "Vikas ki Mehfil", "me", "Vikas", "Chat", "Hindi", 3, 0).apply {
            live = false
            wish.add(wishOf("Priya", 4)); wish.add(wishOf("Sahil", 2))
        })
        add(Room("poor1", "TF-118822", "Late Night Gup-Shup", "host_ananya", "Ananya Das", "Friends", "Hindi", 6, 180))
        add(Room("ended1", "TF-440021", "Truth or Dare", "host_kabir", "Kabir Khan", "Fun", "Hinglish", 7, 0).apply { live = false })
        add(Room("voice1", "TF-990011", "Good Morning Chat ☀️", "host_ishita", "Ishita Roy", "Chat", "Bengali", 8, 520))
        // chat lines already in the rooms
        rooms.values.forEach { r ->
            if (r.id == "h1" || r.id == "ended1") return@forEach
            val t = System.currentTimeMillis()
            r.msgs.add(msg(r, "system", r.hostId, r.hostName, "${r.hostName} started a voice live", t - 600_000))
            r.msgs.add(msg(r, "join", "u_Arjun", "Arjun", "joined the room", t - 300_000))
            r.msgs.add(msg(r, "text", "u_Mira", "Mira", "Aapki awaaz bahut pyaari hai 😍", t - 240_000).put("coins", 2))
            r.msgs.add(msg(r, "gift", "u_Dev", "Dev", "sent Rose", t - 200_000).put("gift_id", "rose").put("count", 5).put("to_name", r.hostName))
            r.msgs.add(msg(r, "wish", "u_Ishaan", "Ishaan", "wants a seat", t - 150_000))
            r.msgs.add(msg(r, "cohost", "u_Rahul", "Rahul", "joined as Co-Host", t - 120_000))
            r.msgs.add(msg(r, "text", r.hostId, r.hostName, "Welcome everyone! Next song request karo 🎤", t - 90_000).put("coins", 0))
            r.msgs.add(msg(r, "gift", "u_Zoya", "Zoya", "sent Teddy", t - 60_000).put("gift_id", "teddy").put("count", 1).put("to_name", "Rahul"))
            r.msgs.add(msg(r, "text", "u_Yash", "Yash", "Mumbai se hello 🙏", t - 30_000).put("coins", 2))
        }
    }

    // ── helpers ──
    private fun wishOf(n: String, minAgo: Int) = JSONObject().put("user_id", "u_$n").put("profile_id", "p_$n").put("name", n).put("photo", pic(n))
        .put("at", iso(System.currentTimeMillis() - minAgo * 60_000L)).put("invited", false)
    private fun msg(r: Room, kind: String, userId: String, name: String, text: String, at: Long): JSONObject =
        JSONObject().put("id", "m${++msgSeq}_${r.id}").put("kind", kind).put("user_id", userId).put("profile_id", if (userId == "me") "p_me" else "p_${name}").put("name", name)
            .put("photo", pic(name)).put("text", text).put("gift_id", "").put("count", 1).put("to_name", "").put("coins", 0).put("at", iso(at))

    fun log(): List<String> = synchronized(calls) { calls.toList() }
    fun bodiesOf(action: String): List<String> = synchronized(calls) { bodies.filter { it.startsWith("$action ") } }
    fun count(action: String) = log().count { it == action }

    private fun showRoom(r: Room) = JSONObject().put("id", r.id).put("code", r.code).put("name", r.name).put("category", r.category).put("language", r.language)
        .put("description", r.description).put("mode", r.mode).put("live", r.live).put("live_since", iso(t0 - 25 * 60_000)).put("session_id", "s_${r.id}")
        .put("viewers", if (r.live) r.viewers else 0).put("level", r.level).put("created_date", "2026-08-14T10:00:00.000Z").put("total_visits", 18420)
        .put("host", JSONObject().put("user_id", r.hostId).put("profile_id", "p_${r.hostId}").put("name", r.hostName).put("photo", pic(r.hostName)).put("gender", "").put("verified", true))
        .put("demo", r.id == "a4" && demoSeeded)

    private fun ok(o: JSONObject = JSONObject()) = Api.Result(200, o.put("ok", true), null)
    private fun fail(status: Int, error: String, extra: JSONObject = JSONObject()) = Api.Result(status, extra.put("error", error), error)

    fun handle(name: String, p: JSONObject): Api.Result {
        val action = p.optString("action")
        synchronized(calls) {
            calls.add(if (name == "liveRoom") action else name)
            bodies.add("${if (name == "liveRoom") action else name} $p")
        }
        Thread.sleep(110) // a little network time
        return synchronized(this) { answer(name, action, p) }
    }

    private fun answer(name: String, action: String, p: JSONObject): Api.Result {
        val now = System.currentTimeMillis()
        when (name) {
            "liveRoomToken" -> {
                val r = rooms[p.optString("room_id")]
                if (r?.id == "voice1") return Api.Result(200, JSONObject().put("ok", true).put("enabled", true).put("app_id", "0123456789abcdef0123456789abcdef")
                    .put("token", JSONObject.NULL).put("channel", "tf_voice1").put("uid", uid("me")).put("role", "audience"), null)
                return Api.Result(200, JSONObject().put("ok", true).put("enabled", false), null)
            }
            "getWallet" -> return ok(JSONObject().put("wallet", JSONObject().put("coins", balance).put("earned_coins", 0)))
            "moderateUser" -> return ok()
            "upload" -> return Api.Result(200, JSONObject().put("file_uri", "private/report_${now}.png"), null)
        }
        if (name != "liveRoom") return fail(404, "unknown function")
        when (action) {
            "home" -> {
                val list = JSONArray()
                rooms.values.filter { it.live && it.id !in setOf("inv1", "co1", "poor1", "voice1") }.forEach { list.put(showRoom(it).put("followed", it.followed)) }
                val h1 = rooms["h1"]!!
                return ok(JSONObject().put("rooms", list).put("can_host", true).put("my_room", showRoom(h1)).put("is_admin", true)
                    .put("categories", JSONArray(listOf("Chat", "Music", "Friends", "Dating", "Fun")))
                    .put("languages", JSONArray(listOf("Hindi", "English", "Hinglish", "Punjabi", "Bengali")))
                    .put("fee", JSONObject().put("video", 90).put("audio", 10))
                    .put("me", JSONObject().put("user_id", "me").put("name", "Vikas").put("photo", pic("Vikas")).put("gender", "m")))
            }
            "demo_seed" -> { val was = demoSeeded; demoSeeded = true; return ok(if (was) JSONObject().put("already", 16) else JSONObject().put("created", 16)) }
            "demo_coins" -> { balance += 5000; return ok(JSONObject().put("balance", balance)) }
            "demo_host", "demo_clear" -> return ok(JSONObject().put("removed", 16))
            "go_live" -> {
                val h1 = rooms["h1"]!!
                h1.live = true; h1.myRole = "host"; h1.viewers = 1; hostLive = true
                h1.msgs.clear()
                h1.msgs.add(msg(h1, "system", "me", "Vikas", "Vikas started a voice live", now))
                return ok(JSONObject().put("room", showRoom(h1)))
            }
            "leaderboard" -> {
                val names = listOf("Sneha Kapoor", "Neha Singh", "Riya Sharma", "Aman Verma", "Tanya Arora", "Pooja Mehta", "Rohan Gupta", "Aarohi Jain", "Vikram Rao", "Simran Kaur", "Kavya Iyer", "Ishita Roy")
                val top = JSONArray()
                names.forEachIndexed { i, n ->
                    top.put(JSONObject().put("rank", i + 1).put("value", 48210 - i * 3650).put("room_id", if (i == 0) "a4" else "a1").put("room_name", "$n's Room").put("level", 18 - i)
                        .put("live", i < 3).put("user_id", "host_$i").put("profile_id", "").put("name", n).put("photo", pic(n)).put("verified", true))
                }
                val me = JSONObject().put("rank", 24).put("value", 1530).put("room_id", "h1").put("room_name", "Vikas ki Mehfil").put("level", 3).put("live", false)
                    .put("user_id", "me").put("profile_id", "").put("name", "Vikas").put("photo", pic("Vikas")).put("verified", true)
                return ok(JSONObject().put("period", p.optString("period", "week")).put("top", top).put("me", me))
            }
        }
        val r = rooms[p.optString("room_id")] ?: return fail(404, "This room doesn't exist any more.")
        val isHost = r.hostId == "me"
        when (action) {
            "join", "state" -> {
                if (r.kicked.contains("me")) return fail(403, "The host removed you from this live.")
                // a busy room: someone else writes, sends a gift or a like now and then
                if (action == "state" && r.live && r.id == "a1") {
                    val n = (now / 2000) % 5
                    if (n == 0L) r.msgs.add(msg(r, "text", "u_Priya", "Priya", "Kya baat hai 🔥", now).put("coins", 2))
                    if (n == 2L) r.msgs.add(msg(r, "gift", "u_Sahil", "Sahil", "sent Kiss", now).put("gift_id", "kiss").put("count", 1).put("to_name", r.hostName))
                    if (n == 3L) r.likesNext += 2
                }
                return state(r, action, p, now)
            }
            "leave" -> { if (isHost && r.live) { r.live = false; hostLive = false } else if (r.myRole == "cohost") { r.myRole = "viewer"; r.cohosts.removeAll { it.optString("user_id") == "me" } }; return ok() }
            "end" -> { r.live = false; hostLive = false; return ok() }
            "users" -> {
                val list = JSONArray()
                list.put(JSONObject().put("user_id", r.hostId).put("profile_id", "p_${r.hostId}").put("name", r.hostName).put("photo", pic(r.hostName)).put("gender", "").put("role", "host")
                    .put("online", true).put("on_mic", !r.hostMuted).put("host_muted", false))
                r.cohosts.forEach { c -> list.put(JSONObject().put("user_id", c.optString("user_id")).put("profile_id", c.optString("profile_id")).put("name", c.optString("name")).put("photo", c.optString("photo"))
                    .put("gender", "").put("role", "cohost").put("online", true).put("on_mic", !c.optBoolean("muted")).put("host_muted", c.optBoolean("host_muted"))) }
                listOf("Arjun", "Mira", "Dev", "Zoya", "Ishaan", "Sara", "Priya").forEachIndexed { i, n ->
                    list.put(JSONObject().put("user_id", "u_$n").put("profile_id", "p_$n").put("name", n).put("photo", pic(n)).put("gender", "").put("role", "viewer")
                        .put("online", i % 4 != 3).put("on_mic", false).put("host_muted", false))
                }
                if (!isHost && r.myRole != "cohost") list.put(JSONObject().put("user_id", "me").put("profile_id", "p_me").put("name", "Vikas").put("photo", pic("Vikas")).put("role", "viewer").put("online", true).put("on_mic", false))
                val online = (0 until list.length()).count { list.getJSONObject(it).optBoolean("online") }
                return ok(JSONObject().put("users", list).put("total", list.length()).put("online", online).put("hosts", 1 + r.cohosts.size))
            }
            "stats" -> {
                val week = p.optString("period") == "week"
                val chart = JSONArray()
                if (week) listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed").forEachIndexed { i, d -> chart.put(JSONObject().put("label", d).put("value", 320 + i * 41 + (i % 2) * 90)) }
                else for (h in 0 until 24) chart.put(JSONObject().put("label", h).put("value", if (h < 9) 0 else (40 + 380 * Math.max(0.0, Math.sin(((h - 6) / 24.0) * Math.PI * 2))).toInt()))
                return ok(JSONObject().put("period", if (week) "week" else "day").put("online", r.viewers).put("visits", if (week) 6120 else 914).put("total_visits", 18420)
                    .put("peak", if (week) 1380 else 812).put("avg_session_sec", 437).put("gifts", if (week) 2210 else 318).put("gift_coins", if (week) 64300 else 9120)
                    .put("live_minutes", if (week) 0 else 186).put("chart", chart))
            }
            "level" -> return ok(JSONObject().put("level", r.level).put("xp", 52450).put("level_xp", 50000).put("next_xp", 65000).put("max", false)
                .put("reward", "Gold Badge").put("next_reward", "Elite Badge").put("live_minutes", 6120).put("live_hours", 102).put("unique_visitors", 1730)
                .put("active_days", 24).put("week_days", 5).put("gift_total", 214500).put("respectful", true)
                .put("need", JSONObject().put("xp", 65000).put("days", 28).put("hours", 180).put("minutes", 10800).put("visitors", 2000)))
            "host_profile" -> return ok(JSONObject().put("room", showRoom(r)).put("level", r.level).put("bio", r.description.ifEmpty { "Fun talks, new friends and good music 💜" })
                .put("stats", JSONObject().put("online", r.viewers).put("total_visits", 18420).put("gifts", 2214).put("rating", 96).put("ratings", 311).put("followers", 1287))
                .put("followed", r.followed).put("hosting_since", "2026-08-14T10:00:00.000Z").put("mine", isHost))
            "follow" -> { r.followed = if (p.has("on")) p.optBoolean("on") else !r.followed; return ok(JSONObject().put("followed", r.followed)) }
            "rate" -> return if (isHost) fail(422, "You can't rate your own room.") else ok()
            "report" -> {
                if (p.optString("reason").isEmpty()) return fail(422, "Choose a reason.")
                if (p.optString("details").length < 10) return fail(422, "Please write what happened (at least 10 letters).")
                return ok()
            }
        }
        // actions that need you inside a live room
        if (!r.live) return fail(410, "This live has ended.")
        when (action) {
            "message" -> {
                val text = p.optString("text").trim()
                if (text.isEmpty()) return fail(422, "Type a message.")
                if (Regex("\\d{7,}").containsMatchIn(text)) return fail(422, "Phone numbers are not allowed.")
                if (!isHost) {
                    if (r.id == "poor1" || balance < 2) return fail(402, "insufficient_coins", JSONObject().put("need", 2).put("balance", if (r.id == "poor1") 1 else balance))
                    balance -= 2
                }
                val m = msg(r, "text", "me", "Vikas", text, now).put("coins", if (isHost) 0 else 2)
                r.msgs.add(m)
                return ok(JSONObject().put("id", m.getString("id")).put("balance", balance))
            }
            "like" -> return ok()
            "gift" -> {
                val g = LK.gift(p.optString("gift_id")) ?: return fail(422, "Unknown gift.")
                val count = p.optInt("count", 1)
                val total = g.price.toLong() * count
                if (r.id == "poor1" || balance < total) return fail(402, "insufficient_coins", JSONObject().put("need", total).put("balance", if (r.id == "poor1") 1 else balance))
                balance -= total
                val to = p.optString("to_user_id")
                val toName = if (to == r.hostId || to.isEmpty()) r.hostName else r.cohosts.firstOrNull { it.optString("user_id") == to }?.optString("name") ?: return fail(422, "Gifts can go to the host or a Co-Host.")
                r.msgs.add(msg(r, "gift", "me", "Vikas", "sent ${g.name}", now).put("gift_id", g.id).put("count", count).put("to_name", toName))
                return ok(JSONObject().put("balance", balance))
            }
            "wish" -> {
                if (isHost) return fail(422, "You are the host.")
                if (p.optBoolean("on", true)) {
                    r.myWish = true; r.myInvited = false
                    r.wish.removeAll { it.optString("user_id") == "me" }
                    r.wish.add(wishOf("Vikas", 0).put("user_id", "me"))
                    r.msgs.add(msg(r, "wish", "me", "Vikas", "wants a seat", now))
                    return ok(JSONObject().put("wish", true))
                }
                r.myWish = false; r.myInvited = false
                r.wish.removeAll { it.optString("user_id") == "me" }
                return ok(JSONObject().put("wish", false))
            }
            "wish_invite" -> {
                if (!isHost) return fail(403, "Only the host can invite to a seat.")
                val w = r.wish.firstOrNull { it.optString("user_id") == p.optString("user_id") } ?: return fail(404, "This person is not on the wishlist any more.")
                w.put("invited", true)
                r.msgs.add(msg(r, "system", "me", "Vikas", "Vikas invited ${w.optString("name")} to a seat", now))
                return ok()
            }
            "cohost_join" -> {
                if (isHost) return fail(422, "You are the host.")
                if (r.myRole == "cohost") return ok(JSONObject().put("already", true))
                if (r.cohosts.size >= 5) return fail(409, "All 5 Co-Host seats are taken.")
                if (balance < 10) return fail(402, "insufficient_coins", JSONObject().put("need", 10).put("balance", balance))
                balance -= 10
                r.myRole = "cohost"; r.myMuted = false; r.myWish = false; r.myInvited = false
                r.wish.removeAll { it.optString("user_id") == "me" }
                r.cohosts.add(JSONObject().put("user_id", "me").put("profile_id", "p_me").put("name", "Vikas").put("photo", pic("Vikas")).put("gender", "m")
                    .put("muted", false).put("host_muted", false).put("cam_off", true).put("agora_uid", uid("me")))
                r.msgs.add(msg(r, "cohost", "me", "Vikas", "joined as Co-Host", now))
                return ok(JSONObject().put("balance", balance))
            }
            "cohost_leave" -> {
                if (r.myRole == "cohost") { r.myRole = "viewer"; r.myMuted = true; r.cohosts.removeAll { it.optString("user_id") == "me" } }
                r.msgs.add(msg(r, "system", "me", "Vikas", "Vikas left the Co-Host seat", now))
                return ok()
            }
            "cohost_remove", "kick" -> {
                if (!isHost) return fail(403, "Only the host can do this.")
                val t = p.optString("user_id")
                val c = r.cohosts.firstOrNull { it.optString("user_id") == t }
                r.cohosts.removeAll { it.optString("user_id") == t }
                if (action == "kick") r.msgs.add(msg(r, "system", "me", "Vikas", "${c?.optString("name") ?: "Someone"} was removed by the host", now))
                return ok()
            }
            "mute" -> {
                val target = p.optString("user_id", "me").ifEmpty { "me" }
                val muted = p.optBoolean("muted")
                if (target == "me") {
                    if (r.myHostMuted && !muted) return fail(403, "The host muted you.")
                    if (isHost) r.hostMuted = muted else { r.myMuted = muted; r.cohosts.firstOrNull { it.optString("user_id") == "me" }?.put("muted", muted) }
                } else {
                    if (!isHost) return fail(403, "Only the host can mute others.")
                    val c = r.cohosts.firstOrNull { it.optString("user_id") == target } ?: return fail(404, "This person isn't a Co-Host.")
                    c.put("host_muted", muted).put("muted", muted || c.optBoolean("muted"))
                }
                return ok()
            }
        }
        return fail(400, "Unknown action.")
    }

    private fun state(r: Room, action: String, p: JSONObject, now: Long): Api.Result {
        val isHost = r.hostId == "me"
        val live = r.live
        val since = DK.ms(p.optString("since"))
        val msgs = JSONArray()
        if (live) {
            val list = if (since > 0) r.msgs.filter { DK.ms(it.optString("at")) > since } else r.msgs.takeLast(30)
            list.forEach { msgs.put(it) }
        }
        val likes = if (live && since > 0) r.likesNext else 0
        val hostO = JSONObject().put("user_id", r.hostId).put("profile_id", "p_${r.hostId}").put("name", r.hostName).put("photo", pic(r.hostName)).put("gender", "").put("verified", true)
            .put("muted", r.hostMuted).put("cam_off", true).put("agora_uid", uid(r.hostId)).put("online", live)
        val co = JSONArray(); if (live) r.cohosts.take(5).forEach { co.put(it) }
        val wish = JSONArray(); if (live) r.wish.forEach { wish.put(it) }
        val me = JSONObject().put("user_id", "me").put("role", if (isHost) "host" else r.myRole).put("in_room", true).put("muted", if (isHost) r.hostMuted else r.myMuted)
            .put("host_muted", r.myHostMuted).put("cam_off", true).put("agora_uid", uid("me")).put("name", "Vikas").put("photo", pic("Vikas"))
        if (live) me.put("wish", r.myWish && r.myRole == "viewer").put("invited", r.myWish && r.myInvited && r.myRole == "viewer")
        val out = JSONObject().put("ok", true).put("now", iso(now)).put("ended", !live).put("likes", likes)
            .put("room", showRoom(r)).put("host", hostO).put("cohosts", co).put("me", me).put("messages", msgs)
            .put("fee", if (r.mode == "audio") 10 else 90).put("seats_left", if (live) Math.max(0, (if (r.mode == "audio") 5 else 1) - r.cohosts.size) else 0)
            .put("wishlist", wish)
        if (action == "join") out.put("blocked", JSONArray()).put("followed", r.followed)
        return Api.Result(200, out, null)
    }

    companion object {
        fun pic(seed: String) = "https://api.dicebear.com/9.x/lorelei/png?seed=${java.net.URLEncoder.encode(seed, "UTF-8")}&size=256&backgroundColor=e9d5ff,fbcfe8,c7d2fe,fde68a"
        fun uid(id: String): Int { var h = 2166136261L.toInt(); for (c in id) { h = h xor c.code; h *= 16777619 }; return ((h.toLong() and 0xffffffffL) % 2_000_000_000L + 1000).toInt() }
        fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    }
}
