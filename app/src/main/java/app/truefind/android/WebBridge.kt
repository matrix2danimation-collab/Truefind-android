package app.truefind.android

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * The link between the Base44 page (in the WebView) and the native screens.
 * The page tells the app: which address it is on, the login token, the app id,
 * where the bottom bar starts, and whether a web pop-up (incoming call …) is
 * on top. The app opens web pages with window.__tfGo(path, state).
 * Home → Random Match → Voice Call / Video Call opens the app's own (native)
 * Random Match instead of the web's price sheet (random()).
 */
class WebBridge(private val listener: Listener) {
    interface Listener {
        fun onRoute(path: String)
        fun onInfo(origin: String, token: String, appId: String, navTopCss: Float, cover: Boolean)
        fun onRandom(type: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun route(origin: String?, path: String?) {
        if (!allowed(origin)) return
        val p = path ?: return
        main.post { listener.onRoute(p) }
    }

    @JavascriptInterface
    fun info(origin: String?, token: String?, appId: String?, navTop: Double, cover: Boolean) {
        if (!allowed(origin)) return
        val o = origin ?: return
        main.post { listener.onInfo(o, token.orEmpty(), appId.orEmpty(), navTop.toFloat(), cover) }
    }

    /**
     * Random Match Voice Call / Video Call was tapped on the web Home. true → the
     * app shows its own Random Match (the page then skips its sheet); false → the
     * page goes on as usual (not signed in yet on the app side).
     */
    @JavascriptInterface
    fun random(origin: String?, type: String?): Boolean {
        if (!allowed(origin) || !Api.ready) return false
        val t = when (type) { "voice" -> "voice"; "video" -> "video"; else -> return false }
        main.post { listener.onRandom(t) }
        return true
    }

    private fun allowed(origin: String?) = origin != null && MainActivity.ORIGINS.contains(origin)

    companion object {
        const val NAME = "TFBridge"

        /** Runs in the page as early as possible (and again after each load, harmless). */
        val JS = """
(function(){
  if (window.__tfNativeInit) return; window.__tfNativeInit = true;
  window.TFNativeApp = true;
  var B = window.$NAME; if (!B) return;
  var css = document.createElement('style');
  css.textContent = 'html.tfnx .tfc-stage,html.tfnx .tfc-top,html.tfnx .tfc-acts{display:none!important}';
  (document.head || document.documentElement).appendChild(css);
  var lastPath = '';
  function sendRoute(){ var p = location.pathname || '/'; if (p !== lastPath) { lastPath = p; try { B.route(location.origin, p); } catch(e){} } }
  ['pushState','replaceState'].forEach(function(k){
    var o = history[k];
    history[k] = function(){ var r = o.apply(this, arguments); setTimeout(sendRoute, 0); setTimeout(info, 0); return r; };
  });
  addEventListener('popstate', function(){ setTimeout(sendRoute, 0); });
  // Home → Random Match: Voice Call / Video Call open the app's own Random Match.
  // (The box is the one with the "A man and a woman being matched" picture; the
  // creators' "Video call <name>" buttons have their own label and are left alone.)
  addEventListener('click', function(e){
    try {
      if (typeof B.random !== 'function') return;
      var p = (location.pathname || '/').replace(/\/+$/, '') || '/';
      if (p !== '/' && p !== '/home') return;
      var b = e.target && e.target.closest ? e.target.closest('button') : null;
      if (!b || b.hasAttribute('aria-label')) return;
      var t = (b.textContent || '').replace(/\s+/g, ' ').trim();
      var type = t === 'Voice Call' ? 'voice' : t === 'Video Call' ? 'video' : '';
      if (!type) return;
      var row = b.parentElement, box = row && row.parentElement;
      if (!box || !box.querySelector('[role="img"][aria-label="A man and a woman being matched for a call"]')) return;
      if (B.random(location.origin, type) === true) { e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); }
    } catch (x) {}
  }, true);
  window.__tfGo = function(path, state){
    var idx = ((history.state && history.state.idx) || 0) + 1;
    history.pushState({ usr: state || null, key: Math.random().toString(36).slice(2, 10), idx: idx }, '', path);
    dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
  };
  var appId = '';
  function findAppId(){
    if (appId) return appId;
    try {
      var es = performance.getEntriesByType('resource');
      for (var i = es.length - 1; i >= 0; i--) { var m = /\/api\/apps\/([a-zA-Z0-9]{16,})\//.exec(es[i].name); if (m) { appId = m[1]; break; } }
    } catch(e){}
    return appId;
  }
  function visible(el){ return !!(el && el.getClientRects().length) && !(el.closest && el.closest('.tfc')); }
  function covered(){
    var list = document.querySelectorAll('.tfic,[role="alertdialog"],[aria-modal="true"]');
    for (var i = 0; i < list.length; i++) if (visible(list[i])) return true;
    return false;
  }
  function info(){
    try {
      var tok = localStorage.getItem('base44_access_token') || localStorage.getItem('token') || '';
      if (tok && tok.charAt(0) === '"') { try { tok = JSON.parse(tok); } catch(e){} }
      var nav = document.querySelector('nav.tfnav');
      var top = nav && nav.getClientRects().length ? nav.getBoundingClientRect().top : -1;
      B.info(location.origin, String(tok || ''), findAppId(), top, covered());
    } catch(e){}
  }
  var t = 0;
  function soon(){ if (!t) t = setTimeout(function(){ t = 0; info(); }, 120); }
  function watch(){
    if (!document.body) { setTimeout(watch, 50); return; }
    new MutationObserver(soon).observe(document.body, { childList: true, subtree: true });
  }
  watch();
  setInterval(info, 1000);
  sendRoute(); info();
})();
""".trimIndent()
    }
}
