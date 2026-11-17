package app.truefind.android

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live updates — the same as the web SDK's entities.X.subscribe():
 * a socket.io (v4) connection to {origin}/ws-user-apps/socket.io/ with the app id and the login token;
 * join the room "entities:{appId}:{Entity}" and every create / update / delete of that entity
 * arrives as an "update_model" event. One connection for the whole app; it closes itself
 * half a minute after the last screen stops listening.
 */
object Realtime {
    class Event(val type: String, val data: JSONObject?, val id: String)

    private val main = Handler(Looper.getMainLooper())
    private val rooms = LinkedHashMap<String, MutableList<(Event) -> Unit>>()
    private var ws: WebSocket? = null
    private var joined = false          // socket.io "/" connected
    private var tries = 0
    private var usedToken = ""

    /** Listen to one entity (Message, Match …). Returns the "stop listening" function. */
    fun subscribe(entity: String, cb: (Event) -> Unit): () -> Unit {
        if (Demo.on) return {}
        val room = "entities:${Api.appId}:$entity"
        val list = rooms.getOrPut(room) { mutableListOf() }
        list.add(cb)
        main.removeCallbacks(closeIdle)
        if (ws == null) open() else if (joined && list.size == 1) emit("join", room)
        var off = false
        return {
            if (!off) {
                off = true
                val l = rooms[room]
                l?.remove(cb)
                if (l != null && l.isEmpty()) { rooms.remove(room); if (joined) emit("leave", room) }
                if (rooms.isEmpty()) { main.removeCallbacks(closeIdle); main.postDelayed(closeIdle, 30_000) }
            }
        }
    }

    /** live updates are flowing (joined the socket) */
    val connected: Boolean get() = joined

    /** the login changed (another account): connect again with the new token */
    fun tokenChanged() { if (ws != null && Api.token.isNotEmpty() && Api.token != usedToken) { close(); if (rooms.isNotEmpty()) open() } }

    private val closeIdle = Runnable { if (rooms.isEmpty()) close() }
    private val reopen = Runnable { if (ws == null && rooms.isNotEmpty()) open() }

    private fun open() {
        if (Api.appId.isEmpty() || Api.token.isEmpty()) { later(); return }
        val base = Api.origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val url = "$base/ws-user-apps/socket.io/?app_id=${enc(Api.appId)}&token=${enc(Api.token)}&EIO=4&transport=websocket"
        usedToken = Api.token
        joined = false
        ws = Api.http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun close() {
        main.removeCallbacks(reopen)
        val w = ws; ws = null; joined = false
        try { w?.close(1000, null) } catch (e: Exception) { /* gone */ }
    }

    private fun later() {
        main.removeCallbacks(reopen)
        val wait = minOf(30_000L, 1000L shl minOf(tries, 5))
        tries++
        main.postDelayed(reopen, wait)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) { main.post { if (webSocket === ws) packet(text) } }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post { if (webSocket === ws) { ws = null; joined = false; later() } } }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { if (webSocket === ws) { ws = null; joined = false; later() } } }
    }

    // engine.io v4: 0 open · 2 ping · 3 pong · 4 message · 1 close
    private fun packet(t: String) {
        if (t.isEmpty()) return
        when (t[0]) {
            '0' -> ws?.send("40")                 // open → connect to the "/" namespace
            '2' -> ws?.send("3")                  // the server's ping → pong
            '1' -> { close(); later() }
            '4' -> socketIo(t.substring(1))
        }
    }

    // socket.io v4: 0 connect · 1 disconnect · 2 event · 4 connect error
    private fun socketIo(p: String) {
        if (p.isEmpty()) return
        when (p[0]) {
            '0' -> { joined = true; tries = 0; for (room in rooms.keys) emit("join", room) }
            '1', '4' -> { close(); later() }
            '2' -> {
                val at = p.indexOf('[')
                if (at < 0) return
                val arr = try { JSONArray(p.substring(at)) } catch (e: Exception) { return }
                if (arr.optString(0) != "update_model") return
                val msg = arr.optJSONObject(1) ?: return
                val room = msg.optString("room")
                val raw = msg.opt("data")
                val parsed = try { if (raw is JSONObject) raw else JSONObject(raw.toString()) } catch (e: Exception) { return }
                val data = parsed.optJSONObject("data")
                val ev = Event(parsed.optString("type"), data, parsed.optString("id").ifEmpty { data?.optString("id").orEmpty() })
                for (cb in rooms[room]?.toList() ?: return) try { cb(ev) } catch (e: Exception) { /* one screen's error */ }
            }
        }
    }

    private fun emit(name: String, arg: String) { ws?.send("42" + JSONArray().put(name).put(arg).toString()) }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
