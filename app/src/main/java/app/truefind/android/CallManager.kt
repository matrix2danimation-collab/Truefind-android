package app.truefind.android

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject

/**
 * Every call of True Find, drawn by the phone itself:
 *  - the incoming call (IncomingCallWatcher.jsx): a live update + a slow safety check + a quick catch-up
 *    when the app comes back, the phone's own ringtone, Accept / Decline (microphone and camera asked
 *    first: answering starts the bill)
 *  - the call screen (/call/:id, and /demo-call for Explore's demo profiles): CallView
 *  - "Call in progress · Return" (ActiveCallBanner.jsx): a call of mine that is really going on (asked
 *    of the server, again every 20 s) while its screen is not open
 *
 * The web app keeps running under the native screens. Its call pages are handed to the app: a script in
 * the page sends every move to /call/… or /demo-call here instead of opening the web's call page, and the
 * web's own incoming-call screen and pill stay hidden and quiet (the Base44 backend is the same one).
 */
class CallManager(private val act: ComponentActivity, private val root: FrameLayout, private val host: Host) {
    interface Host {
        /** where the web page is (path + ?query) */
        fun path(): String
        /** open a page of the app (like a tap) */
        fun go(path: String)
        /** a screen or the pill changed: the app updates what it shows */
        fun changed()
        /** microphone (+ camera) for a call: true when allowed */
        fun askPerms(video: Boolean, done: (Boolean) -> Unit)
    }

    companion object {
        const val NAME = "TFCall"
        private const val CATCH_UP_MS = 42000L      // only offer "Accept" for calls younger than this
        private const val RING_MS = 45000L          // the caller gives up after 45 s
        private const val CHECK_MS = 25000L         // safety check for a ringing call while the app is on screen
        private const val RING_CHECK_MS = 3000L     // while ringing: is the call still ringing?
        private const val STALE_ACTIVE_MS = 3 * 60 * 1000L
        private const val RECHECK_MS = 20000L
        /** pages where the pill never shows (ActiveCallBanner's HIDE_ON) */
        private val HIDE_ON = listOf(Regex("^/call/"), Regex("^/demo-call"), Regex("^/games/."), Regex("^/live/."), Regex("^/dance/."), Regex("^/expert-talk"))
        /** pages with their own sound / camera: an answered call leaves them (the web's call page replaced them) */
        private val MEDIA = listOf(Regex("^/games/."), Regex("^/live/."), Regex("^/dance(/|$)"), Regex("^/expert-talk"))

        /**
         * Runs in the page as early as possible (after the app's other scripts): the web's call pages are
         * handed to the app, its own incoming-call screen and "Call in progress" pill stay hidden, and their
         * ring / buzz stay quiet (the app rings with the phone's ringtone instead).
         */
        val JS = """
(function(){
  if (window.__tfCallsInit) return; window.__tfCallsInit = true;
  var C = window.$NAME; if (!C) return;
  window.TFNativeCalls = 1;
  var d = document;
  var st = d.createElement('style');
  st.textContent = '.tfic{display:none!important}button[aria-label="Return to your call"]{display:none!important}';
  (d.head || d.documentElement).appendChild(st);
  function callOf(url){
    try {
      var u = new URL(String(url), location.href);
      if (u.origin !== location.origin) return null;
      var p = u.pathname.replace(/\/+$/, '');
      var m = /^\/call\/([^\/]+)$/.exec(p);
      if (m) return { kind: 'call', id: decodeURIComponent(m[1]), q: u.search || '' };
      if (p === '/demo-call') return { kind: 'demo', id: '', q: u.search || '' };
    } catch(e){}
    return null;
  }
  function hand(c, state){ try { C.open(location.origin, c.kind, c.id, c.q, JSON.stringify(state == null ? null : state)); } catch(e){} }
  ['pushState','replaceState'].forEach(function(k){
    var o = history[k];
    history[k] = function(s, t, url){
      var c = url != null ? callOf(url) : null;
      if (c) { hand(c, s && s.usr !== undefined ? s.usr : null); return; }
      return o.apply(this, arguments);
    };
  });
  var g = window.__tfGo;
  if (g) window.__tfGo = function(path, state){ var c = callOf(path); if (c) { hand(c, state); return; } return g.apply(this, arguments); };
  var n = window.__tfNav;
  if (n) window.__tfNav = function(path, state){ var c = callOf(path); if (c) { hand(c, state); return 'call'; } return n.apply(this, arguments); };
  var c0 = callOf(location.href);
  if (c0) { hand(c0, null); try { history.replaceState(history.state, '', c0.kind === 'demo' ? '/explore' : '/'); } catch(e){} }
  var AC = window.AudioContext || window.webkitAudioContext;
  if (AC) {
    var W = function(o){ var x = arguments.length ? new AC(o) : new AC(); if (d.querySelector('.tfic')) { try { x.suspend(); } catch(e){} x.resume = function(){ return Promise.resolve(); }; } return x; };
    W.prototype = AC.prototype;
    window.AudioContext = W; if (window.webkitAudioContext) window.webkitAudioContext = W;
  }
  if (navigator.vibrate) { var vb = navigator.vibrate.bind(navigator); navigator.vibrate = function(p){ if (d.querySelector('.tfic')) return true; return vb(p); }; }
})();
""".trimIndent()

        fun utcMs(v: String?) = CallSession.utcMs(v)
    }

    /** on when the web page hands its calls over (the script ran before the page's own code) */
    var enabled = false
    private val main = Handler(Looper.getMainLooper())
    private val finishedIds = LinkedHashSet<String>()
    private var meId = ""
    private var token = ""
    private var unsub: (() -> Unit)? = null
    private var visible = true
    private var preview = false

    // ── the call screen ──
    var view: CallView? = null; private set
    /**
     * The call screen is put aside for a moment while the call goes on: the coin Wallet opened from the call's "+"
     * (owner, 19 Nov: the call must not end there, and Back must come back to the call). The screen stays where it
     * is (hidden, its sound and video keep going); "Call in progress · Return" shows on the Wallet; leaving the
     * Wallet (Back, its own back arrow) or tapping Return brings the call screen back.
     */
    var minimized = false; private set

    // ── the incoming call ──
    private var incoming: JSONObject? = null
    private var incomingView: IncomingView? = null
    private val ringer = Ringer(act)
    private var busy = false

    // ── "Call in progress · Return" ──
    var pillCall: JSONObject? = null; private set

    val bridge = Bridge()

    inner class Bridge {
        /** the page wanted to open /call/:id or /demo-call */
        @JavascriptInterface
        fun open(origin: String?, kind: String?, id: String?, search: String?, state: String?) {
            if (origin == null || !MainActivity.ORIGINS.contains(origin)) return
            val k = kind.orEmpty(); val i = id.orEmpty(); val q = search.orEmpty()
            val st = try { if (state.isNullOrEmpty() || state == "null") null else JSONObject(state) } catch (e: Exception) { null }
            main.post {
                if (k == "demo") openPractice(st ?: JSONObject())
                else if (i.isNotEmpty()) openCall(i, st, q)
            }
        }
    }

    fun lifecycle(): LifecycleOwner = act
    fun askPerms(video: Boolean, done: (Boolean) -> Unit) = host.askPerms(video, done)

    // ═════════════════ calls this phone knows are over (callRegistry.js) ═════════════════
    fun markFinished(id: String) {
        if (id.isEmpty() || !finishedIds.add(id)) return
        if (finishedIds.size > 300) finishedIds.remove(finishedIds.first())
        if (Tf.s(incoming, "id") == id) clearIncoming("")
        if (Tf.s(pillCall, "id") == id) { pillCall = null; host.changed() }
    }
    private fun isFinished(id: String) = id.isNotEmpty() && finishedIds.contains(id)

    // ═════════════════ the call screen ═════════════════
    /** /call/:id: the call screen for this call (hint: what the caller's screen already knew, { peer: { name, photo } }) */
    fun openCall(id: String, hint: JSONObject?, search: String) {
        if (preview) return
        view?.let { v ->
            if (v.session.callId == id) { if (minimized) restore(); return }
            closeCall(v, null)
        }
        if (Tf.s(incoming, "id") == id) clearIncoming("")
        leaveMediaPage()
        show(CallView(act, this, id, CallSession.Mode.REAL, hint, search, meId))
    }

    /** /demo-call: the practice call with a demo profile from Explore (nothing connects, nothing is billed) */
    fun openPractice(state: JSONObject) {
        if (preview) return
        view?.let { closeCall(it, null) }
        show(CallView(act, this, "demo", CallSession.Mode.PRACTICE, state, "", meId))
    }

    private fun show(v: CallView) {
        view = v
        minimized = false
        v.elevation = Ui.dp(100f)
        v.outlineProvider = null
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.start()
        host.changed()
    }

    /** The call goes on with its screen put aside, and [path] opens (the coin Wallet). */
    fun minimizeTo(path: String) {
        val v = view ?: return
        if (minimized) return
        minimized = true
        // INVISIBLE, not removed: the call's sound goes on and its video pictures keep their surfaces
        v.animate().cancel()
        v.visibility = View.INVISIBLE
        host.go(path)
        host.changed()
    }

    /** The call screen comes back (Back from the Wallet, its back arrow, or "Return"). */
    fun restore() {
        val v = view ?: return
        if (!minimized) return
        minimized = false
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(180).start()
        host.changed()
    }

    /** the web's call page used to replace pages with their own sound (a game, a live room, Live Dance): they close the same way */
    private fun leaveMediaPage() {
        val p = host.path().substringBefore('?').trimEnd('/')
        if (MEDIA.any { it.containsMatchIn(p) }) host.go("/chats")
    }

    /** The call screen closes; then [to] opens (Recent, the Wallet …) unless the app is already there. */
    fun closeCall(v: CallView, to0: String?) {
        var to = to0
        if (view === v) {
            view = null
            // the call ended while its screen was put aside (they hung up, the coins ran out): the member stays
            // on the page they are on (the Wallet) and is told
            if (minimized) { minimized = false; to = null; say("Call ended.") }
        }
        v.dispose()
        v.animate().alpha(0f).setDuration(220).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        if (to != null && !preview) {
            val here = host.path()
            val norm = { s: String -> s.trimEnd('/').ifEmpty { "/" }.let { if (it == "/home") "/" else it } }
            if (norm(here) != norm(to)) host.go(to)
        }
        host.changed()
        // the call may still be going on somewhere (an error, another phone): the pill knows
        verifyPill()
    }

    // ═════════════════ signed in / the app on screen ═════════════════
    /** the login token arrived or changed: listen for my calls */
    fun signedIn(tok: String) {
        if (!enabled || preview || tok == token) return
        // the app id may still be on its way from the page: the next report (every second) tries again
        if (tok.isNotEmpty() && !Api.ready) return
        token = tok
        unsub?.invoke(); unsub = null
        meId = ""
        pillCall = null
        clearIncoming("")
        if (tok.isEmpty()) { host.changed(); return }
        Api.me { r ->
            if (token != tok) return@me
            val id = Tf.s(r.data, "id")
            if (!r.ok || id.isEmpty()) { if (r.status == 0 || r.status >= 500) token = ""; return@me }
            meId = id
            unsub = Realtime.subscribe("Call") { ev -> onEvent(ev) }
            pendingOpen?.let { (cid, a) -> pendingOpen = null; fromNotification(cid, a) }
            catchUp()
            findActive()
            main.removeCallbacks(check); main.postDelayed(check, CHECK_MS)
        }
    }

    private val check = object : Runnable {
        override fun run() {
            if (meId.isEmpty()) return
            if (incoming == null) catchUp()
            main.postDelayed(this, CHECK_MS)
        }
    }

    fun onAppVisible(v: Boolean) {
        visible = v
        if (!v || !enabled || preview) return
        if (incoming != null && !busy) ringer.start()
        pendingOpen?.let { (id, a) -> pendingOpen = null; fromNotification(id, a) }
        catchUp()
        verifyPill()
        view?.session?.onAppVisible()
    }

    /** true while a call rings here or its screen is open (the app may show over the lock screen) */
    val busyWithCall: Boolean get() = incoming != null || view != null
    /** the call screen is on screen (not put aside) */
    val callShown: Boolean get() = view != null && !minimized

    // ═════════════════ opened from the ringing notification (Push) ═════════════════
    private var pendingOpen: Pair<String, String>? = null
    /** [act]: "" (the notification itself, or the full-screen ring), "answer" */
    fun fromNotification(id: String, a: String) {
        if (id.isEmpty() || preview) return
        if (meId.isEmpty()) { pendingOpen = id to a; return } // signed in first (signedIn runs it)
        if (view?.session?.callId == id) return
        Api.get("Call", id) { r ->
            val c = r.data
            if (!r.ok || Tf.s(c, "callee_id") != meId) return@get
            // Answer was tapped on the phone's ringing screen: opening the app takes a few seconds, allow them
            val maxAge = if (a == "answer") RING_MS else CATCH_UP_MS
            if (Tf.s(c, "status") != "ringing" || ageOf(c) >= maxAge) {
                markFinished(id)
                say(if (Tf.s(c, "status") == "active") "This call was answered on another device." else "Missed call from ${Tf.s(c, "caller_name").substringBefore(' ').ifEmpty { "someone" }}.")
                return@get
            }
            if (showIncoming(c, maxAge) && a == "answer") respond("accept")
        }
    }

    /** the app moved to another page: the pill is checked again (it shows on most pages); leaving the Wallet brings a put-aside call back */
    fun routeChanged() {
        if (minimized && !host.path().substringBefore('?').trimEnd('/').equals("/wallet")) restore()
        if (pillCall != null) verifyPill()
    }

    // ═════════════════ the incoming call ═════════════════
    private fun ageOf(c: JSONObject?): Long { val t = utcMs(Tf.s(c, "created_date")); return if (t > 0) System.currentTimeMillis() - t else 0L }

    /** a call that is already ringing for me (after the app came back, the network came back …) */
    private fun catchUp() {
        if (meId.isEmpty() || !visible) return
        Api.filter("Call", JSONObject().put("callee_id", meId).put("status", "ringing"), "-created_date", 1) { r ->
            if (r.ok) Tf.list(r).firstOrNull()?.let { showIncoming(it) }
        }
    }

    private fun showIncoming(c: JSONObject, maxAge: Long = CATCH_UP_MS): Boolean {
        val id = Tf.s(c, "id")
        if (Tf.s(c, "status") != "ringing" || ageOf(c) >= maxAge || isFinished(id)) return false
        if (meId.isNotEmpty() && Tf.s(c, "callee_id") != meId) return false
        if (Tf.s(incoming, "id") == id) return true // the same call twice: don't restart the ringing
        if (view?.session?.callId == id) return false
        clearIncoming("")
        incoming = c
        val v = IncomingView(act, c, { respond("accept") }, { respond("decline") })
        v.elevation = Ui.dp(110f)
        v.outlineProvider = null
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        incomingView = v
        v.setPhoto(Tf.s(c, "caller_photo"))
        // in the background the ringing notification (Push) rings; the app rings once it is on screen
        if (visible || Push.token(act).isEmpty()) ringer.start()
        // while it rings: still ringing? (the caller may hang up); and a safety stop when it would have timed out
        main.removeCallbacks(ringCheck); main.postDelayed(ringCheck, RING_CHECK_MS)
        main.removeCallbacks(ringStop); main.postDelayed(ringStop, minOf(RING_MS, maxOf(5000L, RING_MS - ageOf(c))))
        host.changed()
        return true
    }

    private val ringCheck = object : Runnable {
        override fun run() {
            val c = incoming ?: return
            val id = Tf.s(c, "id")
            if (!busy) Api.get("Call", id) { r ->
                if (!r.ok || Tf.s(incoming, "id") != id || busy) return@get
                val st = Tf.s(r.data, "status")
                if (st.isNotEmpty() && st != "ringing") clearIncoming(if (CallSession.DONE.contains(st)) id else "")
            }
            main.postDelayed(this, RING_CHECK_MS)
        }
    }
    private val ringStop = Runnable { incoming?.let { clearIncoming(Tf.s(it, "id")) } }

    private fun clearIncoming(finishedId: String) {
        if (finishedId.isNotEmpty()) markFinished(finishedId)
        main.removeCallbacks(ringCheck); main.removeCallbacks(ringStop)
        ringer.stop()
        incoming = null
        busy = false
        incomingView?.let { v ->
            incomingView = null
            v.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(v) }.start()
        }
        host.changed()
    }

    private fun respond(action: String) {
        val c = incoming ?: return
        if (busy) return
        val id = Tf.s(c, "id")
        val video = Tf.s(c, "type") == "video"
        busy = true
        incomingView?.busy = true
        fun done() { busy = false; incomingView?.busy = false }
        fun send() {
            Api.invoke("respondCall", JSONObject().put("call_id", id).put("action", action)) { r ->
                if (Tf.s(incoming, "id") != id) { done(); return@invoke }
                if (r.ok) {
                    if (action == "accept") {
                        ringer.stop()
                        main.removeCallbacks(ringCheck); main.removeCallbacks(ringStop)
                        val iv = incomingView
                        incoming = null; incomingView = null; busy = false
                        openCall(id, null, "")
                        // the incoming screen goes once the call screen is under it
                        iv?.let { v -> v.animate().alpha(0f).setStartDelay(150).setDuration(220).withEndAction { root.removeView(v) }.start() }
                        host.changed()
                    } else clearIncoming(id)
                } else {
                    clearIncoming(id)
                    if (action == "accept") say(if (r.status == 409) "The call ended before it could be answered." else "Couldn't answer the call. Please try again.")
                }
            }
        }
        if (action != "accept") { send(); return }
        // microphone (and camera) first: answering starts the bill, so a blocked microphone is found out before
        host.askPerms(video) { ok ->
            if (Tf.s(incoming, "id") != id) { done(); return@askPerms } // the caller hung up meanwhile
            if (!ok) {
                done()
                note("Allow ${if (video) "microphone and camera" else "microphone"} access for True Find to answer.")
                return@askPerms
            }
            send()
        }
    }

    private val clearNote = Runnable { incomingView?.note("") }
    private fun note(text: String) {
        val v = incomingView ?: return say(text)
        v.note(text)
        main.removeCallbacks(clearNote); main.postDelayed(clearNote, 3500)
    }

    /** a short message at the bottom (no incoming screen on) */
    private var toast: View? = null
    private val hideToast = Runnable { toast?.let { v -> v.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(v); if (toast === v) toast = null }.start() } }
    private fun say(text: String) {
        main.removeCallbacks(hideToast)
        toast?.let { root.removeView(it) }
        val v = CallKit.t(act, text, 14f, Color.WHITE, 700).apply {
            gravity = Gravity.CENTER
            background = Tf.box(Color.argb(235, 21, 16, 30), 999f)
            setPadding(Ui.dpi(16f), Ui.dpi(10f), Ui.dpi(16f), Ui.dpi(10f))
            maxWidth = (root.width * .88f).toInt().coerceAtLeast(Ui.dpi(220f))
            elevation = Ui.dp(120f)
        }
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dpi(96f) })
        v.alpha = 0f; v.animate().alpha(1f).setDuration(180).start()
        toast = v
        main.postDelayed(hideToast, 3500)
    }

    // ═════════════════ live updates of my calls ═════════════════
    private fun onEvent(ev: Realtime.Event) {
        val d = ev.data ?: JSONObject()
        val id = Tf.s(d, "id").ifEmpty { ev.id }
        if (id.isEmpty()) return
        // incoming
        if (ev.type == "create" && Tf.s(d, "callee_id") == meId && Tf.s(d, "status") == "ringing") showIncoming(d)
        else if (Tf.s(incoming, "id") == id) {
            val st = Tf.s(d, "status")
            // the caller hung up before an answer (or it was answered on another device)
            if (ev.type == "delete" || (st.isNotEmpty() && st != "ringing")) clearIncoming(if (CallSession.DONE.contains(st) || ev.type == "delete") id else "")
        }
        // the pill: anything that is not "active" ends it for that call at once; "active" is only trusted after asking the server
        val cur = pillCall
        if (cur != null && Tf.s(cur, "id") == id && (ev.type == "delete" || Tf.s(d, "status").let { it.isNotEmpty() && it != "active" })) { pillCall = null; host.changed(); return }
        if (Tf.s(d, "caller_id") != meId && Tf.s(d, "callee_id") != meId) return
        if (Tf.s(d, "status") == "active" && !isFinished(id)) verify(id)
    }

    // ═════════════════ "Call in progress · Return" ═════════════════
    private fun liveActive(c: JSONObject?): Boolean {
        if (c == null || Tf.s(c, "status") != "active" || isFinished(Tf.s(c, "id"))) return false
        val seen = utcMs(Tf.s(c, "last_tick_at")).takeIf { it > 0 } ?: utcMs(Tf.s(c, "started_at"))
        return !(seen > 0 && System.currentTimeMillis() - seen > STALE_ACTIVE_MS)
    }

    /**
     * On start: one of my calls may still be active (the app was closed mid-call). A call left "active" by
     * phones that both went quiet for minutes is over: its bill is settled here (endCall).
     */
    private fun findActive() {
        val me = meId
        val q = JSONObject().put("status", "active").put("\$or", org.json.JSONArray().put(JSONObject().put("caller_id", me)).put(JSONObject().put("callee_id", me)))
        Api.filter("Call", q, "-created_date", 3) { r ->
            if (!r.ok || meId != me) return@filter
            var live: JSONObject? = null
            for (c in Tf.list(r)) {
                if (!liveActive(c)) { if (Tf.s(c, "status") == "active" && !isFinished(Tf.s(c, "id"))) Api.invoke("endCall", JSONObject().put("call_id", Tf.s(c, "id"))) { } }
                else if (live == null) live = c
            }
            pillCall = live
            if (live != null) { main.removeCallbacks(recheck); main.postDelayed(recheck, RECHECK_MS) }
            host.changed()
        }
    }

    /** asks the server whether this call is really still going on */
    private fun verify(id: String) {
        Api.get("Call", id) { r ->
            if (r.status == 0) return@get // network trouble: keep what we have, ask again later
            val fresh = if (r.ok) r.data else null
            val cur = pillCall
            if (fresh == null || !liveActive(fresh)) { if (cur != null && Tf.s(cur, "id") == id) { pillCall = null; host.changed() } }
            else if (cur == null || Tf.s(cur, "id") == id) {
                pillCall = fresh
                main.removeCallbacks(recheck); main.postDelayed(recheck, RECHECK_MS)
                host.changed()
            }
        }
    }
    private fun verifyPill() { pillCall?.let { verify(Tf.s(it, "id")) } }
    private val recheck = object : Runnable {
        override fun run() { val c = pillCall ?: return; if (visible) verify(Tf.s(c, "id")); main.postDelayed(this, RECHECK_MS) }
    }

    /** the pill shows on this page now */
    fun pillVisible(path: String): Boolean {
        // a call whose screen is put aside (the coin Wallet opened from it): "Call in progress · Return"
        if (minimized && view != null && incomingView == null) return true
        val c = pillCall ?: return false
        if (!enabled || view != null || incomingView != null || !liveActive(c)) return false
        val p = path.substringBefore('?')
        return HIDE_ON.none { it.containsMatchIn(p) }
    }

    /** tap on the pill: back to that call's screen */
    fun returnToCall() {
        if (minimized) { restore(); return }
        pillCall?.let { openCall(Tf.s(it, "id"), null, "") }
    }

    // ═════════════════ the phone's Back, closing ═════════════════
    /** Back: the incoming screen waits for Accept / Decline; the call screen handles its own Back (not while it is put aside: the Wallet's Back leads back to it) */
    fun onBack(): Boolean {
        if (incomingView != null) return true
        if (minimized) return false
        return view?.onBack() ?: false
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        ringer.stop()
        view?.dispose(); view = null
        unsub?.invoke(); unsub = null
    }

    // ═════════════════ test pictures (emulator, made-up data) ═════════════════
    /**
     * scene: incoming-voice · incoming-video · incoming-busy · incoming-note · pill · error ·
     * stage scenes (ringing, connecting, declined, missed, stage-error) · voice-… / video-… (CallSession.preview words)
     * open: a sheet / page on top (addcoins, report, rating, rating2, thanks, peer, peerchat, peerlocked, premium, hidden, toast, liketoast)
     */
    fun preview(scene: String, open: String, girl: Boolean) {
        preview = true
        enabled = true
        if (scene == "call-check") { android.util.Log.i("TFCALL", CallMedia.selfCheck(act)); return }
        val her = if (girl) "Aman Verma" else "Riya Sharma"
        val herFace = if (girl) "asset:home/faces/b03.jpg" else "asset:home/faces/g05.jpg"
        val me = if (girl) "Neha Verma" else "Aman Verma"
        val meFace = if (girl) "asset:home/faces/g02.jpg" else "asset:home/faces/b01.jpg"
        val video = scene.startsWith("video") || scene.endsWith("-video")
        if (scene.startsWith("incoming")) {
            val c = JSONObject().put("id", "c1").put("status", "ringing").put("type", if (video) "video" else "voice")
                .put("caller_name", her).put("caller_city", "Jaipur").put("callee_id", "me")
            incoming = c
            val v = IncomingView(act, c, {}, {})
            v.elevation = Ui.dp(110f); v.outlineProvider = null
            root.addView(v, FrameLayout.LayoutParams(-1, -1))
            incomingView = v
            v.setPhoto(herFace)
            if (scene.contains("busy")) v.busy = true
            if (scene.contains("note")) v.note("Allow microphone access for True Find to answer.")
            return
        }
        if (scene == "pill") { pillCall = JSONObject().put("id", "c1").put("status", "active").put("last_tick_at", ""); return }
        val hint = JSONObject().put("type", if (video) "video" else "voice").put("skipRing", true).put("peer", JSONObject().put("name", her).put("photo", herFace))
        val v = CallView(act, this, "c1", CallSession.Mode.PREVIEW, hint, "", "me")
        view = v
        v.elevation = Ui.dp(100f); v.outlineProvider = null
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.start()
        if (scene == "error") { v.session.previewLoadError(); return }
        val stageScenes = setOf("ringing", "connecting", "declined", "missed", "stage-error")
        val s = scene.removePrefix("voice-").removePrefix("video-").removePrefix("voice").removePrefix("video")
        v.session.preview(if (scene in stageScenes) (if (scene == "stage-error") "error" else scene) else s.ifEmpty { "talkp" }, me, meFace, her, herFace)
        if (open.isNotEmpty()) main.postDelayed({ v.previewOpen(open) }, 1200)
    }
}
