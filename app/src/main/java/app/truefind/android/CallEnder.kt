package app.truefind.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ends calls on the server FOR SURE (owner, 23 Nov: people showed "busy — on another call" while nobody was on a
 * call). A hang-up, a closed call screen or a start the phone gave up on sends endCall; when that answer is lost
 * (no network for a moment, the server busy) it used to be dropped, the call stayed "ringing" / "active" on the
 * server, and both people counted as busy. Here every such end is kept on the phone (also when the app is closed)
 * and sent again by itself — 1.5 s, 3 s, 6 s, 10 s, 20 s, then every 30 s — until the server has it, for up to
 * 15 minutes (by then the server's own clean-up has closed it). Sending the same end twice is harmless: the
 * server answers "already ended".
 */
object CallEnder {
    private class Item(val kind: String, val id: String, val reason: String, val at: Long, var tries: Int = 0, var sending: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val pending = LinkedHashMap<String, Item>()
    private var store: SharedPreferences? = null

    /** MainActivity: where the ends still to send are kept between app starts; anything left is sent now */
    fun init(ctx: Context) {
        if (store != null) return
        val sp = ctx.applicationContext.getSharedPreferences("tf_call_end", Context.MODE_PRIVATE)
        store = sp
        try {
            val a = JSONArray(sp.getString("pending", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val it = Item(o.optString("kind"), o.optString("id"), o.optString("reason"), o.optLong("at"))
                if (it.id.isNotEmpty() && System.currentTimeMillis() - it.at < CallRules.END_GIVE_UP_MS) pending["${it.kind}:${it.id}"] = it
            }
        } catch (e: Exception) { }
        flush()
    }

    /**
     * End this call (the hang-up, a call screen that closed while the call was still on). [first]: the first answer
     * (the call screen shows the bill or the problem); when that one was lost, the end goes on by itself.
     */
    fun end(callId: String, reason: String = "", first: ((Api.Result) -> Unit)? = null) {
        if (callId.isEmpty() || callId == "demo") return
        add(Item("end", callId, reason, System.currentTimeMillis()), first)
    }

    /** A start the phone gave up on (its answer never came): the call that tap may have made is closed. */
    fun abandon(clientKey: String) {
        if (clientKey.isEmpty()) return
        add(Item("abandon", clientKey, "", System.currentTimeMillis()), null)
    }

    /** the app came back / signed in: everything still waiting is sent now */
    fun flush() {
        if (!Api.ready) return
        for (it in pending.values.toList()) if (!it.sending) send(it, null)
    }

    /** how many ends are still waiting (the page speed report) */
    val waiting: Int get() = pending.size

    private fun add(it: Item, first: ((Api.Result) -> Unit)?) {
        val k = "${it.kind}:${it.id}"
        val had = pending[k]
        if (had != null) {
            // already on its way: the screen still gets an answer (the next one)
            if (first != null) { if (had.sending) waiters.getOrPut(k) { mutableListOf() }.add(first) else send(had, first) }
            return
        }
        pending[k] = it
        save()
        send(it, first)
    }

    private val waiters = HashMap<String, MutableList<(Api.Result) -> Unit>>()

    private fun send(it: Item, first: ((Api.Result) -> Unit)?) {
        val k = "${it.kind}:${it.id}"
        if (!Api.ready) { first?.let { f -> main.post { f(Api.Result(0, JSONObject(), "Please check your internet and try again.")) } }; return }
        it.sending = true
        val body = if (it.kind == "abandon") JSONObject().put("action", "abandon").put("client_key", it.id)
            else JSONObject().put("call_id", it.id).also { b -> if (it.reason.isNotEmpty()) b.put("reason", it.reason) }
        Api.invokeOnce("endCall", body, 20000) { r ->
            it.sending = false
            val more = waiters.remove(k)
            first?.invoke(r)
            more?.forEach { f -> f(r) }
            if (pending[k] !== it) return@invokeOnce
            val old = System.currentTimeMillis() - it.at > CallRules.END_GIVE_UP_MS
            if (CallRules.endDone(r.status) || old || !CallRules.endRetry(r.status)) {
                pending.remove(k); save(); return@invokeOnce
            }
            val wait = CallRules.endWaitMs(it.tries++)
            main.postDelayed({ if (pending[k] === it && !it.sending) send(it, null) }, wait)
        }
    }

    private fun save() {
        val a = JSONArray()
        for (it in pending.values) a.put(JSONObject().put("kind", it.kind).put("id", it.id).put("reason", it.reason).put("at", it.at))
        try { store?.edit()?.putString("pending", a.toString())?.apply() } catch (e: Exception) { }
    }
}
