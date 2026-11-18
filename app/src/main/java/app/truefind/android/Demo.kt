package app.truefind.android

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test mode only (emulator screenshots on GitHub, never in the real app):
 * the native screens get made-up answers instead of the Base44 backend,
 * so every screen can be opened and photographed without logging in.
 */
object Demo {
    @Volatile var on = false
    var premium = false          // the made-up user has a Secret Dating plan
    var girl = false              // the made-up user is a girl (sees boys)
    var noBlur = false            // test runs only: compare speed without the side-card blur
    var noChats = false           // test runs only: no chats yet (the empty Chats page)

    private val girls = listOf("Anvi" to "Rohtak", "Simran" to "Delhi", "Kavya" to "Jaipur", "Riya" to "Hisar", "Meher" to "Chandigarh", "Ishita" to "Noida", "Pooja" to "Panipat")
    private val boys = listOf("Aarav" to "Rohtak", "Rohan" to "Delhi", "Kabir" to "Jaipur", "Arjun" to "Hisar", "Karan" to "Chandigarh")
    private val stateOf = mapOf("Rohtak" to "Haryana", "Delhi" to "Delhi", "Jaipur" to "Rajasthan", "Hisar" to "Haryana", "Chandigarh" to "Chandigarh", "Noida" to "Uttar Pradesh", "Panipat" to "Haryana")

    private fun iso(minAgo: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() - minAgo * 60000))

    private fun person(i: Int, g: Boolean): JSONObject {
        val (n, c) = (if (g) girls else boys)[i % (if (g) girls else boys).size]
        return JSONObject().put("id", "demo-${if (g) "g" else "b"}$i").put("user_id", "demo-u$i").put("name", "$n Sharma").put("age", 20 + (i * 3) % 8)
            .put("gender", if (g) "female" else "male").put("city", c).put("state", stateOf[c]).put("online", i % 5 != 4).put("verified", true)
            .put("last_active", iso((i * 7).toLong())).put("bio", "Love long drives, chai and good music. Looking for someone genuine.")
            .put("interests", JSONArray(listOf("Music", "Travel", "Movies"))).put("profession", "Teacher").put("height", "5'4\"")
    }
    private fun people(): JSONArray { val a = JSONArray(); for (i in 0 until if (girl) 5 else 7) a.put(person(i, !girl)); return a }

    fun face(p: JSONObject?): Bitmap? {
        val id = p?.optString("id").orEmpty()
        if (!on || !id.startsWith("demo")) return null
        return AvatarArt.forKey(id, isGirl(p, id), Ui.dpi(360f), round = false)
    }

    /** a demo "photo" shaped like the place it is shown in (portrait cards, stories): the face in the middle, its background around it */
    private val portraits = HashMap<String, Bitmap>()
    fun portrait(key: String, girl: Boolean, w: Int, h: Int): Bitmap = portraits.getOrPut("$key|$girl|$w|$h") {
        val sq = AvatarArt.forKey(key, girl, w, round = false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(sq.getPixel(2, 2))
        c.drawBitmap(sq, 0f, (h - w) * 0.55f, null)
        out
    }
    fun cover(p: JSONObject?, w: Int, h: Int): Bitmap? {
        val id = p?.optString("id").orEmpty()
        if (!on || !id.startsWith("demo") || w <= 0 || h <= 0) return null
        return portrait(id, isGirl(p, id), w, h)
    }

    /** a demo face, already round, at the size it is shown (cached) */
    fun round(p: JSONObject?, sizePx: Int): Bitmap? {
        val id = p?.optString("id").orEmpty().ifEmpty { p?.optString("profile_id").orEmpty() }
        if (!on || !id.startsWith("demo")) return null
        return AvatarArt.forKey(id, isGirl(p, id), sizePx.coerceAtLeast(48), round = true)
    }

    private fun isGirl(p: JSONObject?, id: String): Boolean { val g = SdRules.sexOf(p); return if (g.isNotEmpty()) g != "m" else !id.startsWith("demo-b") }

    // ── more people, all over India (the Online Map) ──
    private val farGirls = listOf("Aditi", "Sneha", "Priya", "Nisha", "Tanvi", "Ananya", "Diya", "Sara", "Zoya", "Aisha", "Pallavi", "Shruti")
    private val farBoys = listOf("Vivaan", "Aditya", "Ishaan", "Reyansh", "Dev", "Yash", "Rahul", "Nikhil", "Sahil", "Varun", "Manav", "Harsh")
    private val farCities = listOf("Mumbai" to "Maharashtra", "Bengaluru" to "Karnataka", "Kolkata" to "West Bengal", "Chennai" to "Tamil Nadu", "Hyderabad" to "Telangana",
        "Pune" to "Maharashtra", "Ahmedabad" to "Gujarat", "Lucknow" to "Uttar Pradesh", "Patna" to "Bihar", "Bhopal" to "Madhya Pradesh", "Guwahati" to "Assam", "Kochi" to "Kerala")
    private fun far(i: Int, g: Boolean): JSONObject {
        val (c, st) = farCities[i % farCities.size]
        val on = i % 3 != 2
        return JSONObject().put("id", "demo-${if (g) "g" else "b"}${20 + i}").put("user_id", "demo-u${20 + i}")
            .put("name", "${(if (g) farGirls else farBoys)[i % 12]} ${listOf("Patel", "Reddy", "Das", "Nair", "Iyer", "Khan")[i % 6]}").put("age", 21 + (i * 5) % 9)
            .put("gender", if (g) "female" else "male").put("city", c).put("state", st).put("online", on).put("verified", true)
            .put("last_active", iso(if (on) 1L else (95 + i * 41).toLong())).put("bio", "Coffee, books and weekend trips. Say hi!")
            .put("interests", JSONArray(listOf("Travel", "Food", "Dance"))).put("profession", "Designer").put("height", "5'5\"")
    }
    private fun mapPeople(): JSONArray {
        val a = people()
        for (i in 0 until 12) a.put(far(i, !girl))
        // the people you're chatting with show a little chat bubble on their pin
        for (k in 0 until a.length()) a.optJSONObject(k)?.let { p -> if (p.optString("id").endsWith("1") || p.optString("id").endsWith("20")) p.put("chatting", true) }
        return a
    }
    private fun byId(id: String): JSONObject? {
        for (g in listOf(true, false)) { for (i in 0 until 7) person(i, g).let { if (it.optString("id") == id) return it }; for (i in 0 until 12) far(i, g).let { if (it.optString("id") == id) return it } }
        return null
    }

    // ── chats ──
    private class Line(val minAgo: Long, val mine: Boolean, val text: String = "", val gif: String = "")
    private fun chatOther(id: String): JSONObject? = when (id) {
        "m1" -> person(1, !girl); "m2" -> far(0, !girl); "m3" -> person(2, !girl); "m4" -> far(4, !girl); "m5" -> person(4, !girl); else -> null
    }
    private fun lines(id: String, first: String): List<Line> = when (id) {
        "m1" -> listOf(Line(1560, false, "Hi! 👋"), Line(1555, true, "Hey $first! Kaise ho?"), Line(1550, false, "Main theek hoon. Aap kahan se ho?"),
            Line(1546, true, "Rohtak se. Weekend pe Delhi aata hoon 🙂"), Line(1540, true, gif = "rose"), Line(1538, false, "Aww, thank you 🌹"),
            Line(50, false, "Aaj ka din kaisa tha?"), Line(45, true, "Achha tha! Office ke baad gym gaya"), Line(44, false, gif = "hug"),
            Line(6, false, "Kal coffee pe milte hain? ☕"), Line(2, true, "Haan, pakka! 😊"))
        "m2" -> listOf(Line(300, false, "Hello 🙂"), Line(290, true, "Hi $first, nice to meet you"), Line(42, true, gif = "bouquet"))
        "m3" -> listOf(Line(200, true, "Hi $first"), Line(185, false, "Good night 🌙"))
        "m4" -> listOf(Line(1700, true, gif = "rose"), Line(1650, false, "Thank you for the rose 🌹"))
        "m5" -> listOf(Line(4400, true, "Hi! How are you?"))
        else -> emptyList()
    }
    private fun messages(id: String): JSONArray {
        val o = chatOther(id) ?: return JSONArray()
        val ou = o.optString("user_id"); val first = o.optString("name").substringBefore(" ")
        val all = lines(id, first)
        val a = JSONArray()
        for ((k, l) in all.withIndex()) a.put(JSONObject().put("id", "msg-$id-$k").put("match_id", id).put("from_user_id", if (l.mine) "demo-me" else ou).put("to_user_id", if (l.mine) ou else "demo-me")
            .put("kind", if (l.gif.isNotEmpty()) "gif" else "text").put("text", l.text).put("gif_id", l.gif).put("created_date", iso(l.minAgo)).put("read", !l.mine || k < all.size - 1))
        return a
    }
    private fun chat(id: String): JSONObject? {
        val o = chatOther(id) ?: return null
        val m = messages(id); val last = m.optJSONObject(m.length() - 1)
        val unread = when (id) { "m1" -> 0; "m3" -> 1; else -> 0 }
        return JSONObject().put("match_id", id).put("app", "secret")
            .put("other", JSONObject().put("name", o.optString("name")).put("photo", "").put("profile_id", o.optString("id")).put("user_id", o.optString("user_id"))
                .put("verified", true).put("online", o.optBoolean("online")).put("last_active", o.optString("last_active")).put("age", o.optInt("age")).put("gender", o.optString("gender")))
            .put("last_message", last).put("last_message_at", last?.optString("created_date")).put("unread_count", unread)
    }

    fun me(): Api.Result = DemoHome.me() ?: Api.Result(200, JSONObject().put("id", "demo-me").put("full_name", if (girl) "Neha Verma" else "Aman Verma").put("gender", if (girl) "female" else "male"), null)

    private val myProfile get() = JSONObject().put("id", "demo-myprofile").put("full_name", if (girl) "Neha Verma" else "Aman Verma").put("gender", if (girl) "female" else "male")
        .put("date_of_birth", "1999-04-12").put("state", "Haryana").put("city", "Rohtak").put("bio", "Simple person, family first.")

    fun filter(entity: String, q: JSONObject): Api.Result = DemoHome.filter(entity, q) ?: when (entity) {
        "UserProfile" -> Api.Result(200, JSONObject().put("items", JSONArray().put(if (q.has("id")) byId(q.optString("id")) ?: myProfile else myProfile)), null)
        // newest first, the way the chat asks ("-created_date")
        "Message" -> Api.Result(200, JSONObject().put("items", messages(q.optString("match_id")).let { a -> JSONArray().also { r -> for (i in a.length() - 1 downTo 0) r.put(a.get(i)) } }), null)
        "PremiumAccess" -> Api.Result(200, JSONObject().put("items", if (premium) JSONArray().put(JSONObject().put("until", iso(-3 * 24 * 60))) else JSONArray()), null)
        else -> Api.Result(200, JSONObject().put("items", JSONArray()), null)
    }

    fun core(endpoint: String, payload: JSONObject) = DemoHome.core(endpoint, payload) ?: Api.Result(200, JSONObject().put("signed_url", ""), null)

    fun function(name: String, payload: JSONObject): Api.Result {
        DemoHome.function(name, payload)?.let { return it }
        val d = when (name) {
            "getOnlineNow" -> JSONObject().put("me", JSONObject().put("gender", if (girl) "female" else "male").put("city", "Rohtak").put("state", "Haryana")).put("profiles", JSONArray()).put("online", 0).put("poll_ms", 15000)
            "getWallet" -> JSONObject().put("wallet", JSONObject().put("coins", 120))
            "getAppConfig" -> JSONObject().put("config", JSONObject().put("rates", JSONObject().put("voice", 10).put("video", 40)))
            "getDiscoverProfiles" -> JSONObject().put("profiles", people())
            "getMapProfiles" -> JSONObject().put("profiles", mapPeople()).put("premium", premium).put("premium_until", if (premium) iso(-3 * 24 * 60) else "")
                .put("me", JSONObject().put("gender", if (girl) "female" else "male").put("city", "Rohtak").put("state", "Haryana"))
            "getRequestCredits" -> JSONObject().put("credits", JSONObject().put("available_request_credits", if (premium) 5 else 0))
                .put("member", JSONObject().put("verified", true).put("free", false).put("gender", if (girl) "f" else "m")).put("intro", JSONObject().put("available", true))
            "getStories" -> {
                val st = JSONArray()
                for (i in 0 until 5) { val p = person(i, !girl); st.put(JSONObject().put("id", "st$i").put("profile_id", p.optString("id")).put("user_id", p.optString("user_id"))
                    .put("name", p.optString("name")).put("age", p.optInt("age")).put("city", p.optString("city")).put("state", p.optString("state")).put("gender", p.optString("gender"))
                    .put("seen", i == 3).put("created_date", iso((i * 50).toLong())).put("caption", "Good morning ☀️")
                    .put("media_url", "demo:story:${p.optString("id")}:${p.optString("gender")}")) }
                JSONObject().put("premium", premium).put("mine", JSONObject.NULL).put("stories", st)
            }
            "getSecretActivity" -> {
                val items = JSONArray()
                val kinds = listOf("like", "message", "view", "like", "view")
                for (i in 0 until 5) {
                    val p = person(i + 1, !girl)
                    items.put(JSONObject().put("id", "a$i").put("kind", kinds[i]).put("fresh", i < 2).put("at", iso((i * 40 + 3).toLong())).put("g", if (girl) "m" else "f")
                        .put("count", if (kinds[i] == "message") 2 else 1).put("unread", if (kinds[i] == "message") 2 else 0)
                        .put("person", if (premium) p else JSONObject.NULL).put("match_id", if (kinds[i] == "message") "m1" else JSONObject.NULL))
                }
                JSONObject().put("can_see", premium).put("counts", JSONObject().put("total", 2)).put("items", items)
            }
            "getChats" -> {
                val ids = if (noChats) emptyList() else listOf("m1", "m2", "m3", "m4", "m5")
                val only = payload.optString("match_id")
                JSONObject().put("chats", JSONArray(ids.filter { only.isEmpty() || it == only }.mapNotNull { chat(it) }))
            }
            "sendMessage" -> {
                val mid = payload.optString("match_id"); val o = chatOther(mid)
                val gif = gifOf(payload.optString("gif_id"))
                JSONObject().put("ok", true).put("wallet", JSONObject().put("coins", 120 - (gif?.price ?: 0)))
                    .put("message", JSONObject().put("id", "msg-$mid-new-${System.currentTimeMillis()}").put("match_id", mid).put("from_user_id", "demo-me").put("to_user_id", o?.optString("user_id"))
                        .put("kind", payload.optString("kind", "text")).put("text", payload.optString("text")).put("gif_id", payload.optString("gif_id")).put("created_date", iso(0)).put("read", false))
            }
            "likeProfile" -> JSONObject().put("ok", true).put("mutual", false)
            "sendInvitation" -> JSONObject().put("ok", true).put("credits_left", 4)
            "updateMyProfile" -> JSONObject().put("ok", true).put("profile", myProfile.also { m -> payload.optJSONObject("updates")?.let { u -> u.keys().forEach { k -> m.put(k, u.get(k)) } } })
            "createStory" -> JSONObject().put("mine", JSONObject().put("id", "mine").put("views", 0))
            else -> JSONObject().put("ok", true)
        }
        return Api.Result(200, d, null)
    }
}
