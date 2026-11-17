package app.truefind.android.games

import app.truefind.android.Api
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test mode only (emulator pictures on GitHub, never in a real session): made-up server answers for
 * Mood Changer, ClueMe and Dress Code, shaped exactly like the real functions' answers.
 */
object GamesDemoMore {
    private fun ok(o: JSONObject) = Api.Result(200, o, null)
    private val scene get() = GamesDemo.scene
    private val girl get() = GamesDemo.girl

    fun answer(fn: String, body: JSONObject): Api.Result? = when (fn) {
        "moodChanger" -> mood(body.optString("action"))
        "dressCodeAction" -> dress()
        else -> null
    }

    // ───────── Dress Code (dressCodeAction: every action answers with the same game) ─────────
    private fun iso(msAgo: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() - msAgo))
    private fun dress(): Api.Result {
        val w = { id: String, c: Int -> JSONObject().put("id", id).put("c", c) }
        val look = JSONObject().put("saree", w("banarasi-zari", 1)).put("blouse", w("bshort-zari", 1)).put("shoes", w("jutti-jewel", 0))
            .put("hair", w("bun", 0)).put("earrings", w("chandbali", 0)).put("necklace", w("kundan", 0))
        val owned = JSONArray(listOf("banarasi-zari", "bshort-zari", "jutti-jewel", "bun", "chandbali", "kundan"))
        val chat = JSONArray()
            .put(JSONObject().put("r", "girl").put("t", "Hii! Aaj shaadi hai! Saree ya lehenga pehnao 💍").put("at", iso(60000)))
            .put(JSONObject().put("r", "boy").put("t", "Ye dekho 😄").put("at", iso(20000)))
        val s = JSONObject().put("id", "demo-dc").put("code", "4821").put("status", if (scene == "dc-reveal") "reveal" else "playing")
            .put("me", if (girl) "girl" else "boy").put("names", JSONObject().put("boy", "Rahul").put("girl", "Priya"))
            .put("look", look).put("owned", owned).put("spent", 16).put("girl_earned", 10.72).put("round", 1).put("rating", if (scene == "dc-reveal") 4 else 0)
            .put("chat", chat).put("chat_locked", false).put("chat_ms_left", 60000).put("partner_online", true)
            .put("look_started_at", iso(40000)).put("balance", 1250)
        return ok(JSONObject().put("session", s))
    }

    // ───────── Mood Changer (moodChanger: enter / state / start / secret_info …) ─────────
    private fun moodOf(m: String, n: Int, prefs: List<String> = emptyList()) =
        JSONObject().put("mood", m).put("intensity", n).put("prefs", JSONArray(prefs))

    private fun mcSession(): JSONObject? {
        if (scene == "mc-intro") return null
        val myMood = moodOf("stressed", 4, listOf("listen"))
        val herMood = moodOf("tired", 3, listOf("laugh", "light"))
        val started = scene in listOf("mc-call", "mc-call-girl", "mc-final", "mc-summary")
        val revealed = scene != "mc-matched" && scene != "mc-detail" && scene != "mc-wait"
        val partner = JSONObject().put("name", if (girl) "Rahul" else "Priya").put("photo", "").put("age", if (girl) 25 else 23)
            .put("profile_id", "p1").put("online", true).put("mood_done", revealed)
            .put("mood", if (revealed) herMood else JSONObject.NULL).put("final_done", false)
        val s = JSONObject().put("id", "demo-mc").put("version", 5).put("me", if (girl) "creator" else "host")
            .put("partner", if (scene == "mc-wait") JSONObject.NULL else partner)
            .put("mine", JSONObject().put("name", if (girl) "Priya" else "Ankit").put("photo", "")
                .put("mood", if (revealed) myMood else JSONObject.NULL)
                .put("final", if (scene == "mc-summary") JSONObject().put("mood", "relaxed").put("intensity", 2) else JSONObject.NULL)
                .put("checkin", ""))
            .put("shared", JSONArray(listOf("Music", "Travel")))
            .put("plan", if (revealed) JSONObject().put("style", "gentle").put("mindful", true).put("title_en", "Gentle and kind").put("title_hi", "नरम और प्यार भरा").put("source", "ai") else JSONObject.NULL)
            .put("started", started)
            .put("round", if (started) 2 else 0).put("total", 7).put("turn", 0).put("phase", "question")
            .put("next_ask", if (started) "partner" else "").put("is_last", false).put("answered", 2)
            .put("lighten", false).put("lighter_by", "").put("paused_ms", 0).put("pause_by", "").put("reaction", "")
            .put("seconds", if (started) 214 else 0).put("secret", JSONObject.NULL).put("ended", JSONObject.NULL)
            .put("stats", JSONObject().put("rounds", 2).put("answered", 2).put("skipped", 0).put("topic_changes", 0))
        s.put("status", when (scene) {
            "mc-wait" -> "waiting"
            "mc-matched", "mc-detail" -> "mood"
            "mc-reveal" -> "reveal"
            "mc-call", "mc-call-girl" -> "call"
            else -> "final"
        })
        if (scene == "mc-call" || scene == "mc-call-girl") {
            val asking = scene == "mc-call"
            val q = JSONObject().put("id", "demo-q3").put("n", 3).put("level", 2).put("cat", "Daily life")
                .put("ask", if (asking) "me" else "partner").put("ans", if (asking) "partner" else "me")
            if (asking) q.put("text", JSONObject().put("en", "What was the best part of your day so far?").put("hi", "आज अब तक आपके दिन का सबसे अच्छा पल कौन-सा रहा?"))
                .put("tip", JSONObject().put("en", "Ask it in your own words.").put("hi", "अपने शब्दों में पूछिए।"))
                .put("coach", JSONObject().put("name", JSONObject().put("en", "Listen first").put("hi", "पहले सुनिए"))
                    .put("how", JSONObject().put("en", "Let her finish, then ask one small follow-up about what she said.").put("hi", "उन्हें पूरा बोलने दीजिए, फिर उनकी बात पर एक छोटा-सा सवाल और पूछिए।")))
            else q.put("hidden", true)
            s.put("q", q)
        } else s.put("q", JSONObject.NULL)
        return s
    }

    private fun mood(action: String): Api.Result = when (action) {
        "enter" -> ok(JSONObject().put("me", JSONObject().put("role", if (girl) "creator" else "host").put("name", if (girl) "Priya" else "Ankit").put("photo", ""))
            .put("history", JSONArray().put(JSONObject().put("mood", "sad").put("intensity", 4).put("final_mood", "relaxed").put("final_intensity", 2)))
            .apply { mcSession()?.let { put("session", it) } })
        "secret_info" -> ok(JSONObject().put("session", mcSession())
            .put("secret_offer", JSONObject().put("can", true).put("mode", "offer").put("coins", 120).put("credits", 0).put("plan", false).put("member", true)))
        "token" -> ok(JSONObject().put("enabled", true).put("app_id", "demo").put("channel", "demo").put("uid", 1234))
        "search", "wait" -> ok(JSONObject())
        else -> ok(JSONObject().apply { mcSession()?.let { put("session", it) } })
    }
}
