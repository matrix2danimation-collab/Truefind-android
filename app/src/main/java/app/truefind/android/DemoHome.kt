package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Test mode only (emulator screenshots on GitHub, never in the real app): made-up
 * answers for the native Home, Profile and bottom bar, so every screen can be
 * opened and photographed without logging in. Off unless a test screen turns it on.
 */
object DemoHome {
    @Volatile var on = false
    /** test runs only: the daily coins are already taken today (no pop-up over the pages) */
    @Volatile var dailyTaken = false
    /** test screens of Recent: "" (calls), "empty", "error", "skeleton" (never answers), "busy" (the call finds them busy), "proferror" (the profile won't load) */
    @Volatile var recentScene = ""
    private val girl get() = Demo.girl

    private fun iso(minAgo: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() - minAgo * 60000))

    private val GIRLS = listOf("Anvi" to "Rohtak", "Simran" to "Delhi", "Kavya" to "Jaipur", "Riya" to "Hisar", "Meher" to "Chandigarh", "Ishita" to "Noida", "Pooja" to "Panipat",
        "Naina" to "Pune", "Tanvi" to "Lucknow", "Aarohi" to "Indore", "Jasleen" to "Ludhiana", "Diya" to "Bhopal", "Sana" to "Amritsar", "Mahi" to "Karnal")
    private val BOYS = listOf("Aarav" to "Rohtak", "Rohan" to "Delhi", "Kabir" to "Jaipur", "Arjun" to "Hisar", "Karan" to "Chandigarh", "Vivaan" to "Noida", "Rahul" to "Panipat", "Yash" to "Pune")

    private fun person(i: Int, g: Boolean): JSONObject {
        val list = if (g) GIRLS else BOYS
        val (n, c) = list[i % list.size]
        val busy = i % 5 == 3
        return JSONObject().put("id", "demo-${if (g) "g" else "b"}$i").put("user_id", "demo-u${if (g) "g" else "b"}$i").put("name", "$n Sharma").put("full_name", "$n Sharma")
            .put("age", 20 + (i * 3) % 8).put("gender", if (g) "female" else "male").put("city", c).put("online", i % 7 != 6).put("busy", busy)
            .put("verified", i % 3 != 1).put("week_talk_minutes", listOf(842, 615, 410, 260, 190, 120, 75, 40, 0, 0)[i % 10]).put("last_active", iso(1))
    }
    private fun people(): JSONArray { val a = JSONArray(); for (i in 0 until if (girl) 8 else 14) a.put(person(i, !girl)); return a }

    private val myProfile: JSONObject get() = JSONObject().put("id", "demo-myprofile").put("created_by_id", "demo-me")
        .put("full_name", if (girl) "Neha Verma" else "Aman Verma").put("gender", if (girl) "female" else "male").put("date_of_birth", "1999-04-12")
        .put("state", "Haryana").put("city", "Rohtak").put("phone", "919876543210").put("phone_verified", true)
        .put("bio", "Simple person, family first. Love long drives, chai and old songs.")
        .put("hobbies", JSONArray(listOf("Music", "Travel", "Movies")))
        .put("apps", JSONArray(listOf("truefind", "secret")))
        .put("images", JSONArray(if (girl) listOf("asset:home/faces/g04.jpg", "asset:home/faces/g09.jpg") else listOf("asset:home/faces/b04.jpg", "asset:home/faces/b11.jpg")))
        .put("profile_picture", if (girl) "asset:home/faces/g04.jpg" else "asset:home/faces/b04.jpg")
        .put("photo_queue", JSONArray().put(JSONObject().put("url", if (girl) "asset:home/faces/g13.jpg" else "asset:home/faces/b15.jpg").put("status", "pending")))
        .put("age", 27).put("preferred_gender", if (girl) "male" else "female")
        .put("location_visible", true)
        .put("created_date", "2026-11-02T10:15:00.000")
        .put("profile_complete", true)

    fun me(): Api.Result? = if (!on) null else Api.Result(200, JSONObject().put("id", "demo-me").put("full_name", if (girl) "Neha Verma" else "Aman Verma")
        .put("email", "demo@truefind.local").put("role", "user").put("created_date", "2026-11-02T10:15:00.000"), null)

    fun filter(entity: String, q: JSONObject): Api.Result? {
        if (!on) return null
        val items = when (entity) {
            "UserProfile" -> JSONArray().put(myProfile)
            "Notification" -> JSONArray().apply {
                put(JSONObject().put("id", "n1").put("title", "Welcome to True Find").put("message", "Your profile is ready. Say hi to someone new today!").put("read", false).put("created_date", iso(30)))
                put(JSONObject().put("id", "n2").put("title", "Photo approved").put("read", true).put("created_date", iso(60 * 26)))
            }
            "Block" -> JSONArray().put(JSONObject().put("id", "bk1").put("blocked_name", "Rahul"))
            "PremiumAccess" -> JSONArray()
            "Call" -> JSONArray().apply { for (i in 0 until 6) put(JSONObject().put("id", "c$i").put("duration_seconds", 1500)) }
            else -> JSONArray()
        }
        return Api.Result(200, JSONObject().put("items", items), null)
    }

    fun core(endpoint: String, payload: JSONObject): Api.Result? = if (!on) null else Api.Result(200, JSONObject().put("signed_url", "").put("file_uri", "demo://file").put("file_url", "demo://file"), null)

    // ── Recent (made-up calls: every kind of row) ──
    private class RecentDemo(val id: String, val name: String, val age: Int, val face: String, val verified: Boolean, val online: Boolean, val busy: Boolean,
                             val seenMin: Long, val type: String, val status: String, val mine: Boolean, val atMin: Long)
    private val RECENT = listOf(
        RecentDemo("u1", "Simran Kaur", 24, "g01", true, true, false, 1, "video", "ended", true, 12),
        RecentDemo("u2", "Kavya", 22, "g02", false, true, true, 1, "voice", "ended", false, 65),
        RecentDemo("u3", "Riya Sharma", 27, "g03", true, false, false, 185, "voice", "missed", false, 200),
        RecentDemo("u4", "Meher", 25, "g05", true, false, false, 60 * 26, "voice", "missed", true, 60 * 27),
        RecentDemo("u5", "Ishita", 23, "", false, false, false, 60 * 50, "video", "declined", false, 60 * 30),
        RecentDemo("u6", "Pooja Verma", 26, "g07", true, true, false, 2, "voice", "declined", true, 60 * 24 * 3),
        RecentDemo("u7", "Naina", 21, "g08", false, false, false, -1, "video", "ended", false, 60 * 24 * 6),
    )
    private fun recentEntries(): JSONArray = JSONArray().apply {
        for (r in RECENT) put(JSONObject().put("user_id", r.id).put("name", r.name).put("age", r.age)
            .put("photo", if (r.face.isEmpty()) "" else "asset:home/faces/${r.face}.jpg").put("photo_thumb", "")
            .put("verified", r.verified).put("online", r.online).put("busy", r.busy).put("last_active", if (r.seenMin < 0) "" else iso(r.seenMin))
            .put("last_call", JSONObject().put("type", r.type).put("status", r.status).put("mine", r.mine).put("at", iso(r.atMin))))
    }
    private fun recentProfile(id: String): JSONObject {
        val r = RECENT.firstOrNull { it.id == id } ?: RECENT[0]
        val photos = JSONArray()
        if (r.face.isNotEmpty()) for (f in listOf(r.face, "g09", "g10")) photos.put(JSONObject().put("uri", "asset:home/faces/$f.jpg").put("thumb", ""))
        return JSONObject().put("user_id", r.id).put("name", r.name).put("age", r.age).put("gender", "female").put("city", "Rohtak").put("state", "Haryana")
            .put("photo", if (r.face.isEmpty()) "" else "asset:home/faces/${r.face}.jpg").put("photo_thumb", "").put("photos", photos)
            .put("bio", "Simple girl, family first. Love long drives, chai and old songs.").put("interests", JSONArray(listOf("Music", "Travel", "Movies", "Romantic Talk")))
            .put("verified", r.verified).put("creator", true).put("host", false).put("online", r.online).put("busy", r.busy)
            .put("last_active", if (r.seenMin < 0) "" else iso(r.seenMin)).put("joined", "2026-11-02T10:15:00.000")
            .put("followers", 128).put("following", 36).put("i_follow", r.id == "u2").put("follows_me", true).put("me", false)
    }

    fun function(name: String, payload: JSONObject): Api.Result? {
        if (!on) return null
        val d: JSONObject = when (name) {
            "getRecentActivity" -> when (recentScene) {
                "skeleton" -> { try { Thread.sleep(60_000) } catch (e: InterruptedException) { }; JSONObject().put("me", "demo-me").put("entries", JSONArray()) }
                "error" -> return Api.Result(500, JSONObject().put("error", "Something went wrong. Please try again."), "Something went wrong. Please try again.")
                "empty" -> JSONObject().put("me", "demo-me").put("entries", JSONArray())
                else -> JSONObject().put("me", "demo-me").put("entries", recentEntries())
            }
            "getWallet" -> JSONObject().put("wallet", JSONObject().put("coins", 1250).put("earned_coins", 0))
            "claimDailyCoins" -> if (payload.optBoolean("peek")) JSONObject().put("ready", !dailyTaken).put("amount", 10).put("streak", JSONObject().put("day", 3).put("done", 2).put("bonus_amount", 50))
                else JSONObject().put("claimed", 10).put("amount", 10).put("next_at", iso(-24 * 60)).put("streak", JSONObject().put("day", 4).put("done", 3).put("bonus_amount", 50)).put("wallet", JSONObject().put("coins", 1260))
            "getDiscoverProfiles", "getOnlineNow" -> JSONObject().put("profiles", people())
            "getAppConfig" -> JSONObject().put("config", JSONObject().put("rates", JSONObject().put("voice", 10).put("video", 40)))
            "getSecretActivity" -> JSONObject().put("counts", JSONObject().put("total", 3))
            "follow" -> when (payload.optString("action")) {
                "counts" -> JSONObject().put("followers", 128).put("following", 36)
                "profile" -> if (recentScene == "proferror") return Api.Result(500, JSONObject().put("error", "Something went wrong. Please try again."), "Something went wrong. Please try again.")
                    else JSONObject().put("profile", recentProfile(payload.optString("user_id")))
                "list" -> JSONObject().put("followers", 128).put("following", 36).put("people", JSONArray().apply {
                    for (i in 0 until 6) { val p = person(i, !girl); put(JSONObject().put("user_id", p.optString("user_id")).put("name", p.optString("name").substringBefore(" ")).put("city", p.optString("city")).put("state", "Haryana")
                        .put("online", i % 2 == 0).put("verified", i % 3 == 0).put("i_follow", i % 2 == 1).put("follows_me", payload.optString("kind") == "followers" || i == 0)) }
                }).put("suggestions", JSONArray().apply { for (i in 6 until 9) { val p = person(i, !girl); put(JSONObject().put("user_id", p.optString("user_id")).put("name", p.optString("name").substringBefore(" ")).put("city", p.optString("city")).put("i_follow", false).put("follows_me", false)) } })
                else -> JSONObject().put("ok", true).put("followers", 128).put("following", 37)
            }
            "referral" -> if (payload.optString("action") == "mine") JSONObject().put("enabled", true).put("code", "TF7K2QX").put("reward", 50).put("min_recharge", 49)
                .put("stats", JSONObject().put("joined", 3).put("rewarded", 1).put("coins", 50)).put("can_enter_code", true).put("enter_days_left", 5)
                .put("friends", JSONArray().apply { put(JSONObject().put("name", "Rohit").put("status", "rewarded").put("coins", 50)); put(JSONObject().put("name", "Simran").put("status", "pending")) })
                else JSONObject().put("ok", true).put("inviter", "Rohit").put("reward", 50).put("min_recharge", 49)
            "support" -> if (payload.optString("action") == "mine") JSONObject().put("contact", JSONObject().put("email", "help@truefindapp.com").put("whatsapp", "")).put("tickets", JSONArray().apply {
                put(JSONObject().put("id", "t1").put("category", "payment").put("subject", "Coins not added").put("status", "answered").put("user_unread", true).put("created_date", iso(300))
                    .put("messages", JSONArray().apply {
                        put(JSONObject().put("from", "user").put("text", "I paid ₹99 but the coins did not come.").put("at", iso(300)))
                        put(JSONObject().put("from", "admin").put("text", "Sorry about that! The coins are added now. Please check your wallet.").put("at", iso(240)))
                    }))
            }) else JSONObject().put("ok", true)
            "updateMyProfile" -> JSONObject().put("ok", true).put("profile", myProfile.also { m ->
                payload.optJSONObject("updates")?.let { u -> u.keys().forEach { k -> m.put(k, u.get(k)) } }
                payload.optJSONObject("apply")?.let { a -> if (a.optString("kind") == "host") m.put("host_applied_at", iso(0)).put("host_status", "pending") else m.put("creator_submitted_at", iso(0)).put("creator_status", "pending") }
            })
            "startCall" -> return if (recentScene == "busy") Api.Result(409, JSONObject().put("error", "busy"), "busy")
                else Api.Result(402, JSONObject().put("error", "insufficient_coins").put("rate", 40).put("balance", 12), "insufficient_coins")
            else -> JSONObject().put("ok", true)
        }
        return Api.Result(200, d, null)
    }
}
