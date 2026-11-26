package app.truefind.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * The sign-in, kept on the phone by the app itself.
 *
 * Before, the web page under the native screens kept it (in its own storage) and handed it over every
 * second. Now the app remembers it (its private storage, which no other app can read) and opens straight
 * into its own screens: no web page is needed while you are signed in.
 */
object Session {
    private const val PREFS = "tf_session"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Reads the saved sign-in into [Api]. true when there is one. Since the move to Supabase the sign-in is
     * Supabase Auth's (an access token + a refresh token, [Auth]); a Base44 sign-in kept by an older version
     * of the app is not one any more: it is forgotten here and the sign-in page shows.
     */
    fun load(ctx: Context): Boolean = Auth.load(ctx)

    /** The sign-in is kept by [Auth] as soon as it is made or renewed (nothing more to remember here). */
    fun save(ctx: Context) { }

    /** a sign-in is kept on this phone */
    fun has(ctx: Context): Boolean = Auth.has(ctx)

    /** Signed out: the sign-in is forgotten (this phone's id stays). */
    fun clear(ctx: Context) = Auth.clear(ctx)

    /** the app's id in the server's addresses (on Supabase it is fixed) */
    fun knownAppId(ctx: Context): String = Supa.APP_ID

    /**
     * A random id kept on this phone (no personal data), sent with the first "I am online" after the app
     * opens — the web's deviceId() of presence.js: lets the Control Center spot one phone running many accounts.
     */
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        val have = p.getString("device_id", "").orEmpty()
        if (Regex("^[A-Za-z0-9_-]{8,64}$").matches(have)) return have
        val id = java.util.UUID.randomUUID().toString().replace(Regex("[^A-Za-z0-9_-]"), "")
        p.edit().putString("device_id", id).apply()
        return id
    }
    fun hasDeviceId(ctx: Context): Boolean = Regex("^[A-Za-z0-9_-]{8,64}$").matches(prefs(ctx).getString("device_id", "").orEmpty())
    /** the id the web page had made for this phone before (kept, so the phone stays the same phone) */
    fun adoptDeviceId(ctx: Context, id: String?) {
        if (id == null || !Regex("^[A-Za-z0-9_-]{8,64}$").matches(id)) return
        val p = prefs(ctx)
        if (p.getString("device_id", "").isNullOrEmpty()) p.edit().putString("device_id", id).apply()
    }

    /** this account was an expert the last time it was checked (Expert Talk opens at once, no flash of Home) */
    fun wasExpert(ctx: Context): Boolean = prefs(ctx).getBoolean("expert", false)
    fun setExpert(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("expert", on).apply() }

    /** a friend's invite code waiting on this phone (Refer & Earn fills it into the box) */
    fun refCode(ctx: Context): String = prefs(ctx).getString("ref", "").orEmpty()
    fun setRefCode(ctx: Context, code: String) { prefs(ctx).edit().putString("ref", code).apply() }
}

/**
 * What the web app did quietly in the background for a signed-in member, done by the app itself:
 *  - "Who am I" when the app opens (AuthContext.jsx): a sign-in the server no longer accepts goes back to
 *    the sign-in pages; no internet is not a sign-out ("No internet connection", tried again by itself).
 *  - Presence (lib/presence.js): online as soon as the app is on screen (again every minute), offline as
 *    soon as it leaves the screen and on log out — so Explore shows who is online right now.
 *  - The red number on the Find button (BottomNav.jsx): new Reconnect likes, messages and views.
 *  - Expert accounts are Expert Talk only (ExpertGate.jsx).
 */
class SessionWork(private val ctx: Context, private val host: Host) {
    interface Host {
        /** the number on the bottom bar's Find button */
        fun unread(n: Int)
        /** the server no longer accepts this sign-in */
        fun rejected()
        /** the server could not be reached when the app opened (still signed in) */
        fun offline(on: Boolean)
        /** this account is an expert (or not) */
        fun expert(on: Boolean)
        /** the bottom bar is on screen */
        fun barShown(): Boolean
    }

    companion object {
        private const val BEAT_MS = 60_000L
        private val RETRY_WAITS = longArrayOf(700, 1500, 3000, 5000)   // about 10 s in all, as the web
        /** test runs only (ci/session-test.sh): "who am I" is not asked, so a made-up sign-in is not thrown out */
        @Volatile var skipCheck = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var token = ""
    private var visible = false
    var meId = ""; private set
    /** an expert account (Expert Talk only). Until the server has answered: what it said the last time. */
    private var expertNow: Boolean? = null
    val isExpert: Boolean get() = expertNow ?: (Api.token.isNotEmpty() && !Demo.on && Session.wasExpert(ctx))

    // ═════════════════ start / stop ═════════════════
    /** Signed in and inside the app (again after another account signed in). */
    fun start() {
        if (Demo.on) return
        if (running && token == Api.token) return
        if (running) halt()
        running = true
        token = Api.token
        meId = ""
        deviceSent = false
        said = ""
        expertNow = null
        if (isExpert) host.expert(true)
        watchNetwork(true)
        if (visible) startBeating()
        check(0)
        main.removeCallbacks(unreadTick); main.postDelayed(unreadTick, 120_000)
    }

    private fun halt() {
        running = false
        stopBeating()
        main.removeCallbacksAndMessages(null)
        liveStop?.invoke(); liveStop = null
        watchNetwork(false)
        count = 0
        offlineShown = false
        expertNow = null
    }

    /** Signing out (or the sign-in ended): [sayOffline] tells Explore first (waits 1.5 s at most), then [done]. */
    fun stop(sayOffline: Boolean, done: () -> Unit = {}) {
        val was = running
        halt()
        if (!was || !sayOffline || Api.token.isEmpty()) { done(); return }
        var fired = false
        val fire = Runnable { if (!fired) { fired = true; done() } }
        main.postDelayed(fire, 1500)
        said = ""
        sendOffline { main.removeCallbacks(fire); fire.run() }
    }

    /** the app's screen is on show / has left the screen (another app, the screen locked) */
    fun onVisible(v: Boolean) {
        if (visible == v) return
        visible = v
        if (!running) return
        if (v) {
            startBeating()
            unreadSoon(200)
            if (offlineShown) check(0)
        } else {
            stopBeating()
            sendOffline { }
        }
    }

    // ═════════════════ who am I (is this sign-in still good?) ═════════════════
    private var offlineShown = false
    private val recheckTick = Runnable { if (running && offlineShown && visible) check(0) }

    /** the "No internet connection" screen's Retry */
    fun retry() { if (running) check(0) }

    private fun check(attempt: Int) {
        if (skipCheck) return
        val tok = token
        Api.me { r ->
            if (!running || tok != token) return@me
            val id = r.data.optString("id")
            if (r.ok && id.isNotEmpty()) {
                meId = id
                if (offlineShown) { offlineShown = false; host.offline(false) }
                main.removeCallbacks(recheckTick)
                listenMessages()
                unreadSoon(0)
                checkExpert(id)
                return@me
            }
            if (r.status == 401 || r.status == 403) { host.rejected(); return@me }
            // no internet yet (the phone just woke up) or the server was busy: ask again before saying so
            if (attempt < RETRY_WAITS.size) { main.postDelayed({ if (running && tok == token) check(attempt + 1) }, RETRY_WAITS[attempt]); return@me }
            if (!offlineShown) { offlineShown = true; host.offline(true) }
            main.removeCallbacks(recheckTick); main.postDelayed(recheckTick, 15_000)
        }
    }

    // ═════════════════ expert accounts ═════════════════
    private fun checkExpert(uid: String) {
        Api.filter("UserProfile", JSONObject().put("created_by_id", uid).put("is_expert", true), null, 1) { r ->
            if (!running || !r.ok) return@filter
            val on = Tf.list(r).isNotEmpty()
            val was = isExpert
            expertNow = on
            if (on != Session.wasExpert(ctx)) Session.setExpert(ctx, on)
            if (on != was) host.expert(on)
        }
    }

    // ═════════════════ presence ═════════════════
    private var beating = false
    private var said = ""          // the last thing told to the server: "online" | "offline"
    private var offlineAt = 0L
    private var deviceSent = false
    private val beatTick = object : Runnable { override fun run() { if (beating && visible) beat(); if (beating) main.postDelayed(this, BEAT_MS) } }
    private val beatAgain = Runnable { if (beating && visible) beat() }

    private fun beat() {
        if (!running || Api.token.isEmpty()) return
        said = "online"
        val body = JSONObject()
        if (!deviceSent) { Session.deviceId(ctx).takeIf { it.isNotEmpty() }?.let { body.put("device_id", it) }; deviceSent = true }
        // App Health (25 Nov): about every 5 minutes the phone's numbers go along (no extra request)
        val stats = AppHealth.takeBatch(false)
        if (stats != null) body.put("stats", stats)
        // (a blocked account: the answer is 403 "banned" — Api tells the "blocked" screen)
        Api.invoke("updatePresence", body) { r -> if (stats != null) AppHealth.ack(stats, r.ok) }
    }

    private fun startBeating() {
        if (beating || !running) return
        beating = true
        beat()
        // back within a few seconds of "offline" (a quick app switch): "online" once more shortly after,
        // in case the two messages crossed on the way
        main.removeCallbacks(beatAgain)
        if (System.currentTimeMillis() - offlineAt < 5000) main.postDelayed(beatAgain, 3000)
        main.removeCallbacks(beatTick); main.postDelayed(beatTick, BEAT_MS)
    }
    private fun stopBeating() {
        beating = false
        main.removeCallbacks(beatTick); main.removeCallbacks(beatAgain)
    }
    /** "online" once more in a moment (the sign-in page that just closed may have said "offline" as it went) */
    fun beatSoon() { if (running) { main.postDelayed(beatAgain, 4000) } }

    private fun sendOffline(done: () -> Unit) {
        if (said == "offline" || Api.token.isEmpty()) { done(); return }
        said = "offline"
        offlineAt = System.currentTimeMillis()
        // (App Health: everything waiting goes with it; what doesn't arrive goes with the next heartbeat)
        val body = JSONObject().put("state", "offline")
        val stats = AppHealth.takeBatch(true)
        if (stats != null) body.put("stats", stats)
        // (8 s to arrive: the server can take a few seconds to wake up, and 1.5 s cut the message off on the way, so
        // the member stayed "online"; signing out still waits 1.5 s at most — the message goes on by itself)
        Api.invoke("updatePresence", body, 8000) { r -> if (stats != null) AppHealth.ack(stats, r.ok); done() }
    }

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private fun watchNetwork(on: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb?.let { try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { } }
            netCb = null
            if (!on) return
            val cb = object : ConnectivityManager.NetworkCallback() {
                // the network is back: online again at once; a sign-in check that had no internet is tried again
                override fun onAvailable(network: Network) { main.post { if (running && visible) { if (beating) beat(); if (offlineShown) check(0) } } }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) { }
    }

    // ═════════════════ the red number on Find ═════════════════
    private var count = 0
    private var liveStop: (() -> Unit)? = null
    private var lastBarAsk = 0L
    private val unreadLoad = Runnable { loadUnread() }
    private val unreadTick = object : Runnable { override fun run() { if (running) { unreadSoon(0); main.postDelayed(this, 120_000) } } }

    private fun setCount(n: Int) { count = maxOf(0, n); host.unread(count) }

    private fun loadUnread() {
        if (!running || meId.isEmpty() || !visible || !host.barShown()) return
        val tok = token
        Api.invoke("getSecretActivity", JSONObject().put("count", true)) { r ->
            if (!running || tok != token || !r.ok) return@invoke
            val counts = r.data.optJSONObject("counts") ?: return@invoke
            if (!counts.has("total")) return@invoke
            val n = counts.optDouble("total", Double.NaN)
            if (!n.isNaN() && !n.isInfinite()) setCount(n.toInt())
        }
    }
    /** asks the number again in [ms] */
    fun unreadSoon(ms: Long = 900) { if (!running) return; main.removeCallbacks(unreadLoad); main.postDelayed(unreadLoad, ms) }

    /** the bottom bar came back (e.g. after reading a chat): the number is asked again (20 s apart at most) */
    fun barBack() {
        if (!running || meId.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastBarAsk < 20_000) return
        lastBarAsk = now
        unreadSoon(250)
    }

    /** a new message to me arrives: the number goes up at once, then the server is asked */
    private fun listenMessages() {
        if (liveStop != null) return
        liveStop = Realtime.subscribe("Message") { ev ->
            val d = ev.data ?: return@subscribe
            val me = meId
            if (me.isEmpty() || d.optString("to_user_id") != me) return@subscribe
            val read = d.opt("read") == true
            if (ev.type == "create" && !read && d.optString("app").ifEmpty { "secret" } == "secret") setCount(count + 1)
            unreadSoon()
        }
    }
}

/**
 * Members who added their main photo before small list copies existed have no small copy yet. When such a
 * member opens the app, the phone makes it once, quietly, a little after Home has settled: it downloads the
 * photo, shrinks it (360 px) and sends the small copy — lib/thumbBackfill.js of the web, which did this from
 * the page under the native Home. If anything fails it is tried again only after three days.
 */
object ThumbBackfill {
    private const val RETRY_MS = 3L * 24 * 3600 * 1000
    private var running = false

    fun maybe(ctx: Context, me: JSONObject?) {
        if (me == null || running || Demo.on) return
        val uri = me.optString("profile_picture")
        if (me.optString("_pid").isEmpty() || uri.isEmpty() || me.isNull("profile_picture")) return
        if (HomeRules.thumbOf(me, uri).isNotEmpty()) return
        if (uri.startsWith("blob:", true) || uri.startsWith("data:", true)) return
        val prefs = ctx.applicationContext.getSharedPreferences("tf_thumb_try", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(uri, 0L) < RETRY_MS) return
        running = true
        val token = Api.token
        Handler(Looper.getMainLooper()).postDelayed({
            if (Api.token != token || token.isEmpty()) { running = false; return@postDelayed }
            prefs.edit().putLong(uri, System.currentTimeMillis()).apply()
            Photos.url(uri) { url ->
                if (url.isEmpty()) { running = false; return@url }
                Api.async({ make(url, uri) }) { running = false }
            }
        }, 8000)
    }

    /** off the main thread: download, shrink, upload (stored the same way as the photo), tell the profile */
    private fun make(url: String, uri: String): Boolean = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000 }
        val bytes = try { if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null } finally { conn.disconnect() }
        val pic = if (bytes != null && bytes.size <= 25 * 1024 * 1024) PUp.decode(bytes, PUp.THUMB_PX) else null
        val small = pic?.let { p -> PUp.jpeg(p, PUp.THUMB_PX, 0.74f).also { p.bmp.recycle() } }
        if (small == null) false else {
            val up = PUp.send(!uri.startsWith("http", true), small, "thumb.jpg", "image/jpeg")
            val thumb = if (!up.ok) "" else up.data.optString("file_uri").ifEmpty { up.data.optString("file_url") }.ifEmpty { up.data.optString("url") }
            if (thumb.isEmpty()) false
            else Api.request("POST", "/functions/updateMyProfile", JSONObject().put("photo_thumb_add", JSONObject().put("uri", uri).put("thumb", thumb)).toString().toByteArray(Charsets.UTF_8), "application/json").ok
        }
    } catch (e: Throwable) { false }
}
