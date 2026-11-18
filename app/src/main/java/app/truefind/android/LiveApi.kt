package app.truefind.android

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Live Rooms talk to the SAME backend functions as the web page (LiveRooms.jsx / LiveRoom.jsx):
 * "liveRoom" for every room action and "liveRoomToken" for the voice pass, plus getWallet
 * and moderateUser (block / report). Login is the web page's (Api).
 * Tests can put a pretend server in [fake].
 */
object LiveApi {
    @Volatile var fake: ((String, JSONObject) -> Api.Result)? = null
    private val fakePool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class Res(val r: Api.Result) {
        val data: JSONObject get() = r.data
        val status: Int get() = r.status
        /** ok = 2xx and no { error } inside */
        val ok: Boolean get() = r.ok && !(r.data.has("error") && r.data.optString("error").isNotBlank())
        val code: String get() = r.data.optString("error", "")
        val noCoins: Boolean get() = code == "insufficient_coins"
        /** errText() of LiveRooms.jsx */
        fun text(fallback: String = "Something went wrong. Try again."): String {
            val d = r.data
            return when {
                code == "insufficient_coins" -> "You don't have enough coins."
                code == "banned" -> "Your account can't use Live Rooms."
                d.optString("reason").isNotBlank() -> d.optString("reason")
                code.isNotBlank() -> code
                r.status == 0 -> r.error ?: "Please check your internet and try again."
                else -> r.error ?: fallback
            }
        }
    }

    /** liveApi(action, body) */
    fun call(action: String, body: JSONObject = JSONObject(), done: (Res) -> Unit) {
        val payload = JSONObject(body.toString()).put("action", action)
        fn("liveRoom", payload) { done(Res(it)) }
    }

    /** the voice pass for a room (liveRoomToken) */
    fun token(roomId: String, done: (Res) -> Unit) = fn("liveRoomToken", JSONObject().put("room_id", roomId)) { done(Res(it)) }

    /** any other backend function (getWallet, moderateUser …), through the same pretend server in tests */
    fun other(name: String, payload: JSONObject, done: (Res) -> Unit) = fn(name, payload) { done(Res(it)) }

    /** UploadPrivateFile (report screenshots) */
    fun upload(bytes: ByteArray, mime: String, done: (Res) -> Unit) {
        val f = fake
        if (f != null) {
            fakePool.execute {
                val r = try { f("upload", JSONObject().put("size", bytes.size)) } catch (e: Exception) { Api.Result(500, JSONObject(), "fake failed") }
                main.post { done(Res(r)) }
            }
            return
        }
        val ext = if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg"
        Api.uploadPrivate(bytes, "report.$ext", mime) { done(Res(it)) }
    }

    private fun fn(name: String, payload: JSONObject, done: (Api.Result) -> Unit) {
        val f = fake
        if (f != null) {
            fakePool.execute {
                val r = try { f(name, payload) } catch (e: Exception) { Api.Result(500, JSONObject().put("error", "fake: ${e.message}"), "fake failed") }
                main.post { done(r) }
            }
            return
        }
        Api.invoke(name, payload, done = done)
    }
}
