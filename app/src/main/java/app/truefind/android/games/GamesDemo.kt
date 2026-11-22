package app.truefind.android.games

import app.truefind.android.Api
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test mode only (emulator pictures on GitHub, never in a real session): made-up server answers
 * so every game screen can be photographed without a login. [scene] picks the moment shown.
 */
object GamesDemo {
    var scene = ""
    var girl = false

    private fun ok(o: JSONObject) = Api.Result(200, o, null)

    private val hcRules = JSONObject("""{"entry":15,"turn_ms":15000,"win_back":0,"her_win":4,"her_lose":4,"her_share":4,"gift_share":1,"boxes":16,"secrets":4,"gifts":{"rose":1,"choco":2,"teddy":5,"bouquet":10,"ring":15}}""")

    private fun hcSession(): JSONObject? {
        val base = JSONObject().put("id", "demo-hc").put("version", 3)
            .put("partner", JSONObject().put("name", if (girl) "Rahul" else "Priya").put("photo", "").put("profile_id", "p1"))
            .put("theirs", JSONObject().put("opened", JSONArray("[1,6,10]")).put("hits", JSONArray("[6]")).put("found", 1))
            .put("ours", JSONObject().put("opened", JSONArray("[0,5]")).put("hits", JSONArray("[5]")).put("found", 1))
            .put("mine", JSONObject().put("secret", JSONArray("[2,5,11,14]")).put("ready", true))
            .put("gifts", JSONArray())
        return when (scene) {
            "hc-secret" -> base.put("status", "secret").put("mine", JSONObject().put("secret", JSONArray()).put("ready", false))
                .put("theirs", JSONObject().put("found", 0)).put("ours", JSONObject().put("found", 0))
            "hc-play" -> base.put("status", "play").put("turn", "me").put("turn_ms_left", 9000)
            "hc-over" -> base.put("status", "over").put("result", JSONObject().put("winner", "me").put("tries", 7).put("coins_back", 0).put("her_earned", 4))
                .put("theirs", JSONObject().put("opened", JSONArray("[1,3,6,9,10,12,15]")).put("hits", JSONArray("[3,6,12,15]")).put("found", 4).put("secret", JSONArray("[3,6,12,15]")))
            "hc-search" -> null
            else -> null
        }
    }

    fun answer(fn: String, body: JSONObject): Api.Result {
        val action = body.optString("action")
        return when (fn) {
            "getWallet" -> ok(JSONObject().put("wallet", JSONObject().put("coins", 1250)))
            "heartChase" -> when (action) {
                "enter" -> ok(JSONObject().put("me", JSONObject().put("role", if (girl) "creator" else "host").put("name", "Ankit").put("photo", ""))
                    .put("rules", hcRules).put("balance", 120).apply { hcSession()?.let { put("session", it) } })
                "state" -> ok(JSONObject().apply { hcSession()?.let { put("session", it) } })
                "search", "wait" -> ok(JSONObject())
                "token" -> ok(JSONObject().put("voice", JSONObject().put("enabled", true).put("app_id", "demo").put("channel", "demo").put("uid", 1234)))
                else -> ok(JSONObject().apply { hcSession()?.let { put("session", it) } })
            }
            else -> GamesDemoMore.answer(fn, body) ?: ok(JSONObject())
        }
    }
}
