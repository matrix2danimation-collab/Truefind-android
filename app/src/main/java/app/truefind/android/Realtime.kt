package app.truefind.android

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Live updates — the same as the web SDK's entities.X.subscribe(), on Supabase Realtime (since 24 Nov 2026):
 * one websocket to Supabase ({URL}/realtime/v1/websocket, the Phoenix protocol), one channel per table
 * ("realtime:tf-{Entity}") listening to that table's row changes (postgres_changes), with the member's sign-in
 * as access_token — so each member only gets the rows the table's rules let them read. Every INSERT / UPDATE /
 * DELETE arrives as an [Event] "create" / "update" / "delete" with the row, as Base44's "update_model" did.
 * One connection for the whole app; it closes itself half a minute after the last screen stops listening.
 * The tables must be in the supabase_realtime publication (Call, Match, Message, Typing, Wallet, WalletTransaction).
 */
object Realtime {
    class Event(val type: String, val data: JSONObject?, val id: String)

    private const val HEARTBEAT_MS = 25_000L

    private val main = Handler(Looper.getMainLooper())
    /** the sign-in is renewed here (off the main thread) before a channel is joined with it */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** listeners per table */
    private val rooms = LinkedHashMap<String, MutableList<(Event) -> Unit>>()
    /** tables whose channel the server has joined */
    private val joinedRooms = HashSet<String>()
    /** the join request sent per table (its ref), to match the server's answer */
    private val joinRefs = HashMap<String, String>()
    private var ws: WebSocket? = null
    private var open = false            // the websocket is up
    private var joined = false          // live updates are flowing (a channel joined)
    private var tries = 0
    private var usedToken = ""
    private var ref = 0
    private var pendingBeat = ""        // the heartbeat not answered yet

    /** Listen to one entity (Message, Match …). Returns the "stop listening" function. */
    fun subscribe(entity: String, cb: (Event) -> Unit): () -> Unit {
        if (Demo.on) return {}
        val list = rooms.getOrPut(entity) { mutableListOf() }
        list.add(cb)
        main.removeCallbacks(closeIdle)
        if (ws == null) connect() else if (open && list.size == 1) join(entity)
        var off = false
        return {
            if (!off) {
                off = true
                val l = rooms[entity]
                l?.remove(cb)
                if (l != null && l.isEmpty()) { rooms.remove(entity); leave(entity) }
                if (rooms.isEmpty()) { main.removeCallbacks(closeIdle); main.postDelayed(closeIdle, 30_000) }
            }
        }
    }

    /** live updates are flowing (joined) */
    val connected: Boolean get() = joined

    /** told when the live connection comes up or goes down (the chat's "Connecting…" bar); returns "stop telling me" */
    private val stateCbs = mutableListOf<(Boolean) -> Unit>()
    fun onState(cb: (Boolean) -> Unit): () -> Unit { stateCbs.add(cb); return { stateCbs.remove(cb) } }
    private fun setJoined(v: Boolean) {
        if (joined == v) return
        joined = v
        for (cb in stateCbs.toList()) try { cb(v) } catch (e: Exception) { }
    }

    /**
     * The sign-in changed (another account, or the hourly renewal): the channels get the new one. A new
     * account connects again; a renewed token is handed to the joined channels (no reconnect needed).
     */
    fun tokenChanged() {
        val t = Api.token
        if (ws == null || t == usedToken) return
        if (t.isEmpty() || !open) { close(); if (rooms.isNotEmpty() && t.isNotEmpty()) connect(); return }
        usedToken = t
        for (entity in joinedRooms.toList()) send(topic(entity), "access_token", JSONObject().put("access_token", t))
    }

    private val closeIdle = Runnable { if (rooms.isEmpty()) close() }
    private val reopen = Runnable { if (ws == null && rooms.isNotEmpty()) connect() }

    private fun connect() {
        if (Api.token.isEmpty()) { later(); return }
        val base = Supa.URL.replaceFirst("https://", "wss://")
        val url = "$base/realtime/v1/websocket?apikey=${enc(Supa.KEY)}&vsn=1.0.0"
        usedToken = Api.token
        open = false; joinedRooms.clear(); joinRefs.clear(); pendingBeat = ""
        setJoined(false)
        ws = Api.http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun close() {
        main.removeCallbacks(reopen); main.removeCallbacks(beat)
        val w = ws; ws = null; open = false; joinedRooms.clear(); joinRefs.clear(); setJoined(false)
        try { w?.close(1000, null) } catch (e: Exception) { /* gone */ }
    }

    private fun later() {
        main.removeCallbacks(reopen)
        val wait = minOf(30_000L, 1000L shl minOf(tries, 5))
        tries++
        main.postDelayed(reopen, wait)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { main.post { if (webSocket === ws) opened() } }
        override fun onMessage(webSocket: WebSocket, text: String) { main.post { if (webSocket === ws) message(text) } }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post { if (webSocket === ws) { lost(); later() } } }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { if (webSocket === ws) { lost(); later() } } }
    }

    private fun lost() { ws = null; open = false; joinedRooms.clear(); joinRefs.clear(); main.removeCallbacks(beat); setJoined(false) }

    private fun opened() {
        open = true
        // a sign-in renewed while the socket was connecting is used from the start
        usedToken = Api.token
        for (entity in rooms.keys.toList()) join(entity)
        main.removeCallbacks(beat); main.postDelayed(beat, HEARTBEAT_MS)
    }

    /** Phoenix heartbeat: an unanswered one means the line is dead — connect again */
    private val beat = object : Runnable {
        override fun run() {
            if (ws == null || !open) return
            if (pendingBeat.isNotEmpty()) { android.util.Log.w("TFLive", "no heartbeat answer: connecting again"); close(); later(); return }
            pendingBeat = send("phoenix", "heartbeat", JSONObject())
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun topic(entity: String) = "realtime:tf-$entity"

    /** joins [entity]'s channel with a fresh sign-in (renewed first when its hour is nearly up) */
    private fun join(entity: String) {
        if (!open || joinRefs.containsKey(entity) || joinedRooms.contains(entity)) return
        joinRefs[entity] = ""   // (on its way: asked once)
        io.execute {
            val tok = try { Auth.fresh() } catch (e: Throwable) { Api.token }
            main.post {
                if (!open || !rooms.containsKey(entity) || joinedRooms.contains(entity) || joinRefs[entity] != "") { if (joinRefs[entity] == "") joinRefs.remove(entity); return@post }
                sendJoin(entity, tok)
            }
        }
    }

    private fun sendJoin(entity: String, token: String) {
        val changes = org.json.JSONArray().put(JSONObject().put("event", "*").put("schema", "public").put("table", entity))
        val config = JSONObject()
            .put("broadcast", JSONObject().put("ack", false).put("self", false))
            .put("presence", JSONObject().put("key", ""))
            .put("postgres_changes", changes)
            .put("private", false)
        if (token != usedToken && token.isNotEmpty()) usedToken = token
        val r = send(topic(entity), "phx_join", JSONObject().put("config", config).put("access_token", token), joinRef = true)
        joinRefs[entity] = r
    }

    private fun leave(entity: String) {
        joinRefs.remove(entity)
        if (joinedRooms.remove(entity) && open) send(topic(entity), "phx_leave", JSONObject())
        if (joinedRooms.isEmpty()) setJoined(false)
    }

    /** sends one Phoenix message; returns its ref */
    private fun send(topic: String, event: String, payload: JSONObject, joinRef: Boolean = false): String {
        val r = (++ref).toString()
        val m = JSONObject().put("topic", topic).put("event", event).put("payload", payload).put("ref", r)
        if (joinRef) m.put("join_ref", r)
        ws?.send(m.toString())
        return r
    }

    private fun message(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        val topic = m.optString("topic")
        val event = m.optString("event")
        val payload = m.optJSONObject("payload") ?: JSONObject()
        if (topic == "phoenix") { if (event == "phx_reply" && m.optString("ref") == pendingBeat) pendingBeat = ""; return }
        val entity = topic.removePrefix("realtime:tf-").takeIf { topic.startsWith("realtime:tf-") } ?: return
        when (event) {
            "phx_reply" -> {
                if (joinRefs[entity] != m.optString("ref")) return
                if (payload.optString("status") == "ok") {
                    joinedRooms.add(entity); tries = 0; fails.remove(entity)
                    setJoined(true)
                } else {
                    android.util.Log.w("TFLive", "join $entity refused: ${payload.optJSONObject("response")}")
                    joinRefs.remove(entity)
                    rejoinLater(entity)
                }
            }
            "postgres_changes" -> deliver(entity, payload.optJSONObject("data") ?: return)
            "phx_error", "phx_close" -> { joinedRooms.remove(entity); joinRefs.remove(entity); if (joinedRooms.isEmpty()) setJoined(false); rejoinLater(entity) }
            "system" -> {
                // e.g. the token ran out before the renewal reached the channel: the channel is joined again
                if (payload.optString("status") == "error") {
                    android.util.Log.w("TFLive", "channel $entity: ${payload.optString("message").take(160)}")
                    joinedRooms.remove(entity); joinRefs.remove(entity); if (joinedRooms.isEmpty()) setJoined(false); rejoinLater(entity)
                }
            }
        }
    }

    /** a channel the server turned away or closed is joined again: 3 s, then longer each time (1 min at most) */
    private val fails = HashMap<String, Int>()
    private fun rejoinLater(entity: String) {
        val n = (fails[entity] ?: 0) + 1
        fails[entity] = n
        main.postDelayed({ if (open && rooms.containsKey(entity)) join(entity) }, minOf(60_000L, 3000L shl minOf(n - 1, 5)))
    }

    /** a row change → "create" / "update" / "delete" with the row, to every listener of that table */
    private fun deliver(entity: String, d: JSONObject) {
        val type = when (d.optString("type").uppercase()) { "INSERT" -> "create"; "UPDATE" -> "update"; "DELETE" -> "delete"; else -> return }
        val row = if (type == "delete") d.optJSONObject("old_record") else d.optJSONObject("record")
        val ev = Event(type, row, row?.optString("id").orEmpty())
        for (cb in rooms[entity]?.toList() ?: return) try { cb(ev) } catch (e: Exception) { /* one screen's error */ }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
