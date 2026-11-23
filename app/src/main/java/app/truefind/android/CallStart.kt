package app.truefind.android

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * One tap on Voice Call / Video Call → startCall, done so that a tap always either rings the other phone or tells
 * clearly why not (owner, 23 Nov: "the loading circle turned, then no call — and then everyone showed busy").
 *
 *  - the tap gets a one-time key; when the answer is lost or the server is busy for a moment, the SAME tap is asked
 *    again (for up to 45 s, 1.5–10 s apart): the server then answers with the call this tap already made, never a
 *    second call (CallRules)
 *  - when it still can't start, whatever this tap may have made on the server is closed (CallEnder.abandon), so it
 *    can't ring on, nor keep both people "busy" for the next minute
 *  - [Handle.cancel] (Cancel / Back while it is starting): nothing rings — a call that was made meanwhile is ended
 */
object CallStart {
    class Handle internal constructor(val key: String) {
        internal val at = System.currentTimeMillis()
        internal var cancelled = false
        internal var callId = ""
        internal var finished = false
        /** Cancel / Back while the call is being started: it is not made (or ended at once if it was) */
        fun cancel() {
            if (cancelled) return
            cancelled = true
            if (callId.isNotEmpty()) CallEnder.end(callId) else if (!finished) CallEnder.abandon(key)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** startCall to [toUserId] (voice / video). [done] gets the final answer (never called after [Handle.cancel]). */
    fun start(toUserId: String, type: String, done: (Api.Result) -> Unit): Handle {
        val h = Handle(CallRules.newKey())
        attempt(h, toUserId, type, 0, done)
        return h
    }

    private fun attempt(h: Handle, to: String, type: String, n: Int, done: (Api.Result) -> Unit) {
        if (h.cancelled) return
        val body = JSONObject().put("to_user_id", to).put("type", type).put("app", "truefind").put("client_key", h.key)
        if (n > 0) body.put("retry", true)
        Api.invokeOnce("startCall", body, CallRules.startTimeoutMs(n)) { r ->
            val id = r.data.optJSONObject("call")?.optString("id").orEmpty()
            if (r.ok && id.isNotEmpty()) h.callId = id
            if (h.cancelled) {
                // cancelled while it was being made: it must not ring on their phone
                if (h.callId.isNotEmpty()) CallEnder.end(h.callId) else if (CallRules.mayHaveMade(r.status)) CallEnder.abandon(h.key)
                return@invokeOnce
            }
            if (!r.ok && CallRules.retryStart(r.status) && Api.ready && !Demo.on) {
                val said = if (r.data.has("retry_after")) r.data.optDouble("retry_after") else null
                val wait = CallRules.startWaitMs(n, said)
                if (CallRules.startAgain(System.currentTimeMillis() - h.at, wait)) {
                    main.postDelayed({ attempt(h, to, type, n + 1, done) }, wait)
                    return@invokeOnce
                }
            }
            h.finished = true
            // gave up on an answer that never came: whatever this tap may have made is closed
            if (!r.ok && CallRules.mayHaveMade(r.status)) CallEnder.abandon(h.key)
            done(r)
        }
    }

    /** the words of the message box for a call that could not start: title to text (null: not a "busy" answer) */
    fun busyDialog(r: Api.Result, name: String): Pair<String, String>? = when (CallRules.busyKind(r.status, r.data.optString("error"), r.data.optString("code"))) {
        CallRules.Busy.SELF -> "You're on another call" to "Your other call is still going on. End it first, then try again."
        CallRules.Busy.PEER -> "${name.ifEmpty { "They" }} ${if (name.isNotEmpty()) "is" else "are"} busy" to "They are on another call right now. Please try again in a few minutes."
        CallRules.Busy.NONE -> null
    }
}
