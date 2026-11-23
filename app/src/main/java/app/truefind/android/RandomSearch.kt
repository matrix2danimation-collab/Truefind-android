package app.truefind.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Random Match that never gives up on its own (owner, 16 + 21 Nov): the search keeps going — no "No one is free"
 * pop-up, no second tap — until someone picks up or the member presses Cancel / Back. The same steps as the web's
 * RandomMatchSearch.jsx, on the same server functions:
 *
 *  1. claimRandomMatch: the server takes one free, online creator (and me) in one step, so two callers never get
 *     the same person. Nobody free: ask again in 2 s.
 *  2. startCall to her: she rings for at most 12 s (one creator at a time, owner 16 Nov). The search screen shows
 *     "Calling Riya…" meanwhile; the call screen opens only when she picks up.
 *  3. No answer / declined / busy / a problem: she is let go (releaseMatch) and the next creator is tried at once,
 *     silently. The same person is never tried twice in a row.
 *
 * Only these stop it: Cancel / Back ([cancel]), not enough coins ([Host.onCoins]), and a problem with my own account
 * (banned, profile not complete, not joined) ([Host.onStop]).
 */
class RandomSearch(private val type: String, private val ui: Host) {
    interface Host {
        /** "search" (Finding someone…) or "calling" (Calling <name>…, her photo) */
        fun stage(stage: String, name: String, photo: String)
        /** she picked up: open the call screen (the search screen goes away) */
        fun found(callId: String, peer: JSONObject)
        fun onCoins(data: JSONObject)
        fun onStop(title: String, text: String)
    }

    companion object {
        /** the microphone (+ camera) asked once before searching — set by MainActivity */
        var perms: (video: Boolean, done: (Boolean) -> Unit) -> Unit = { _, done -> done(true) }
        private const val RING_MS = 12000L       // one creator rings this long, then the next (owner, 16 Nov)
        private const val AGAIN_MS = 2000L       // nobody free: look again after this
        private const val NEXT_MS = 800L         // after a no-answer / busy: the next one
        private const val POLL_MS = 2000L        // the ringing call is also read every 2 s (a lost live update)
    }

    private val main = Handler(Looper.getMainLooper())
    private var stopped = false
    private var step = 0
    private val tried = LinkedHashSet<String>()
    private var lastTried = ""
    private var ringing: Ringing? = null

    private class Ringing(val id: String, val peer: JSONObject, val at: Long) { var unsub: (() -> Unit)? = null; var done = false }

    fun start() {
        perms(type == "video") { ok ->
            if (stopped) return@perms
            if (!ok) { stop(); ui.onStop("Allow microphone", "Allow ${if (type == "video") "microphone and camera" else "microphone"} access for True Find, then try again."); return@perms }
            search()
        }
    }

    /** Cancel / Back: everything stops, a ringing call is ended and both people are free again */
    fun cancel() {
        if (stopped) return
        stop()
        val r = ringing
        if (r != null && !r.done) {
            r.done = true
            // Cancel: the ring stops for sure (CallEnder sends it again if this answer is lost — 23 Nov); picked up at
            // that very moment, it ends without a charge (endCall's rule for a call answered a moment ago)
            CallEnder.end(r.id) { Api.invoke("releaseMatch", JSONObject().put("call_id", r.id)) { } }
        } else Api.invoke("releaseMatch", JSONObject()) { }
    }

    private fun stop() {
        stopped = true
        step++
        main.removeCallbacksAndMessages(null)
        ringing?.unsub?.invoke()
    }

    private fun later(ms: Long, block: () -> Unit) {
        val my = step
        main.postDelayed({ if (!stopped && my == step) block() }, ms)
    }

    // ── 1. find someone ──
    private fun search() {
        if (stopped) return
        val my = ++step
        ui.stage("search", "", "")
        Api.invoke("claimRandomMatch", JSONObject().put("exclude_user_ids", JSONArray(tried.toList()))) { r ->
            if (stopped || my != step) {
                // (cancelled meanwhile: the one the server just took is let go)
                r.data.optJSONObject("candidate")?.let { release(it) }
                return@invoke
            }
            val err = r.data.optString("error")
            if (r.status == 402 || err == "insufficient_coins") { stop(); ui.onCoins(r.data); return@invoke }
            if (r.data.has("needs_membership")) { stop(); ui.onStop("Couldn't start Random Match", err.ifEmpty { "Join True Find to use Random Match." }); return@invoke }
            if ((r.status == 400 || r.status == 403) && err.isNotEmpty()) { stop(); ui.onStop("Couldn't start Random Match", if (err == "banned") "Your account can't make calls right now." else err); return@invoke }
            val c = if (r.ok) r.data.optJSONObject("candidate") else null
            if (c == null) {
                // nobody free right now (or a passing problem): look again soon. Creators tried before may be free
                // again by now — only the last one stays left out (never the same person twice in a row)
                if (tried.size > 1) { tried.clear(); if (lastTried.isNotEmpty()) tried.add(lastTried) }
                later(AGAIN_MS) { search() }
                return@invoke
            }
            call(c)
        }
    }

    // ── 2. ring her ──
    private fun call(c: JSONObject) {
        val uid = Tf.s(c, "user_id")
        tried.add(uid); lastTried = uid
        val name = CallKit.firstName(Tf.s(c, "name"))
        val photo = Tf.s(c, "photo")
        ui.stage("calling", name, photo)
        val my = step
        // one key for this ring (as on Call Back): when no answer comes back (no network for a moment, a busy server),
        // the call it may have made is closed for sure — before 23 Nov such a call rang her phone for up to a minute
        // with nobody on the other side, and showed her "Online · Busy" meanwhile
        val key = CallRules.newKey()
        Api.invoke("startCall", JSONObject().put("to_user_id", uid).put("type", type).put("app", "truefind").put("client_key", key)) { r ->
            val call = r.data.optJSONObject("call")
            val id = Tf.s(call, "id")
            if (id.isEmpty() && CallRules.mayHaveMade(r.status)) CallEnder.abandon(key)
            if (stopped || my != step) {
                if (id.isNotEmpty()) CallEnder.end(id) { Api.invoke("releaseMatch", JSONObject().put("call_id", id)) { } }
                else release(c)
                return@invoke
            }
            val err = r.data.optString("error")
            if (r.status == 402 || err == "insufficient_coins") { release(c); stop(); ui.onCoins(r.data); return@invoke }
            if (r.status == 403 && (err == "banned" || err.contains("complete your profile", true))) {
                release(c); stop(); ui.onStop("Couldn't connect", if (err == "banned") "Your account can't make calls right now." else err); return@invoke
            }
            if (!r.ok || id.isEmpty() || Tf.s(call, "status") != "ringing") {
                // busy / a problem / already over: the next one
                if (id.isNotEmpty()) Api.invoke("releaseMatch", JSONObject().put("call_id", id)) { } else release(c)
                later(NEXT_MS) { search() }
                return@invoke
            }
            ring(Ringing(id, JSONObject().put("name", name).put("photo", photo), SystemClock.elapsedRealtime()))
        }
    }

    private fun ring(rg: Ringing) {
        ringing = rg
        fun seen(status: String) {
            if (rg.done || stopped) return
            when {
                status == "active" -> { rg.done = true; rg.unsub?.invoke(); stop(); ui.found(rg.id, rg.peer) }
                status.isNotEmpty() && status != "ringing" -> next(rg)   // missed / declined / ended
            }
        }
        rg.unsub = Realtime.subscribe("Call") { ev -> val d = ev.data; if (d != null && Tf.s(d, "id") == rg.id) seen(Tf.s(d, "status")) }
        fun poll() {
            if (rg.done || stopped) return
            if (SystemClock.elapsedRealtime() - rg.at >= RING_MS) {
                // 12 s, no answer: one last look (she may have just picked up), then the next creator
                Api.get("Call", rg.id) { r ->
                    val st = if (r.ok) Tf.s(r.data, "status") else ""
                    if (st == "active") seen(st) else next(rg, endIt = true)
                }
                return
            }
            Api.get("Call", rg.id) { r -> if (r.ok) seen(Tf.s(r.data, "status")) }
            later(POLL_MS) { poll() }
        }
        later(POLL_MS) { poll() }
    }

    // ── 3. no answer: let her go, the next one ──
    private fun next(rg: Ringing, endIt: Boolean = false) {
        if (rg.done) return
        rg.done = true
        rg.unsub?.invoke()
        ringing = null
        if (endIt) Api.invoke("endCall", JSONObject().put("call_id", rg.id).put("reason", "no_answer")) { r ->
            // she picked up at that very moment: the server keeps the call going — open it
            val c = r.data.optJSONObject("call")
            if (!stopped && Tf.s(c, "status") == "active") { stop(); ui.found(rg.id, rg.peer); return@invoke }
            // lost / the server busy: the ring is stopped for sure, by itself (CallEnder, 23 Nov)
            if (!r.ok && CallRules.endRetry(r.status)) CallEnder.end(rg.id, "no_answer")
            Api.invoke("releaseMatch", JSONObject().put("call_id", rg.id)) { }
        } else Api.invoke("releaseMatch", JSONObject().put("call_id", rg.id)) { }
        later(NEXT_MS) { search() }
    }

    /** the creator the server took for me, given back (no call was made) */
    private fun release(c: JSONObject) {
        Api.invoke("releaseMatch", JSONObject().put("callee_id", Tf.s(c, "user_id")).put("callee_profile_id", Tf.s(c, "profile_id"))) { }
    }
}
