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
        return AvatarArt.forKey(id, SdRules.sexOf(p) != "m", Ui.dpi(360f), round = false)
    }

    /** a demo face, already round, at the size it is shown (cached) */
    fun round(p: JSONObject?, sizePx: Int): Bitmap? {
        val id = p?.optString("id").orEmpty().ifEmpty { p?.optString("profile_id").orEmpty() }
        if (!on || !id.startsWith("demo")) return null
        return AvatarArt.forKey(id, SdRules.sexOf(p) != "m", sizePx.coerceAtLeast(48), round = true)
    }

    fun me(): Api.Result = Api.Result(200, JSONObject().put("id", "demo-me").put("full_name", if (girl) "Neha Verma" else "Aman Verma").put("gender", if (girl) "female" else "male"), null)

    private val myProfile get() = JSONObject().put("id", "demo-myprofile").put("full_name", if (girl) "Neha Verma" else "Aman Verma").put("gender", if (girl) "female" else "male")
        .put("date_of_birth", "1999-04-12").put("state", "Haryana").put("city", "Rohtak").put("bio", "Simple person, family first.")

    fun filter(entity: String, q: JSONObject): Api.Result = when (entity) {
        "UserProfile" -> Api.Result(200, JSONObject().put("items", JSONArray().put(myProfile)), null)
        "PremiumAccess" -> Api.Result(200, JSONObject().put("items", if (premium) JSONArray().put(JSONObject().put("until", iso(-3 * 24 * 60))) else JSONArray()), null)
        else -> Api.Result(200, JSONObject().put("items", JSONArray()), null)
    }

    fun core(endpoint: String, payload: JSONObject) = Api.Result(200, JSONObject().put("signed_url", ""), null)

    fun function(name: String, payload: JSONObject): Api.Result {
        val d = when (name) {
            "getOnlineNow" -> JSONObject().put("me", JSONObject().put("gender", if (girl) "female" else "male").put("city", "Rohtak").put("state", "Haryana")).put("profiles", JSONArray()).put("online", 0).put("poll_ms", 15000)
            "getWallet" -> JSONObject().put("wallet", JSONObject().put("coins", 120))
            "getAppConfig" -> JSONObject().put("config", JSONObject().put("rates", JSONObject().put("voice", 10).put("video", 40)))
            "getDiscoverProfiles" -> JSONObject().put("profiles", people())
            "getMapProfiles" -> JSONObject().put("profiles", people()).put("premium", premium).put("premium_until", if (premium) iso(-3 * 24 * 60) else "")
                .put("me", JSONObject().put("gender", if (girl) "female" else "male").put("city", "Rohtak").put("state", "Haryana"))
            "getRequestCredits" -> JSONObject().put("credits", JSONObject().put("available_request_credits", if (premium) 5 else 0))
                .put("member", JSONObject().put("verified", true).put("free", false).put("gender", if (girl) "f" else "m")).put("intro", JSONObject().put("available", true))
            "getStories" -> {
                val st = JSONArray()
                for (i in 0 until 5) { val p = person(i, !girl); st.put(JSONObject().put("id", "st$i").put("profile_id", p.optString("id")).put("user_id", p.optString("user_id"))
                    .put("name", p.optString("name")).put("age", p.optInt("age")).put("city", p.optString("city")).put("state", p.optString("state")).put("gender", p.optString("gender"))
                    .put("seen", i == 3).put("created_date", iso((i * 50).toLong())).put("caption", "Good morning ☀️")) }
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
            "likeProfile" -> JSONObject().put("ok", true).put("mutual", false)
            "sendInvitation" -> JSONObject().put("ok", true).put("credits_left", 4)
            "updateMyProfile" -> JSONObject().put("ok", true).put("profile", myProfile.also { m -> payload.optJSONObject("updates")?.let { u -> u.keys().forEach { k -> m.put(k, u.get(k)) } } })
            "createStory" -> JSONObject().put("mine", JSONObject().put("id", "mine").put("views", 0))
            else -> JSONObject().put("ok", true)
        }
        return Api.Result(200, d, null)
    }
}
