package app.truefind.android

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * The link between the Base44 page (in the WebView) and the native screens.
 * The page tells the app: which address it is on, the login token, the app id,
 * where the bottom bar starts, and whether a web pop-up (incoming call …) is
 * on top. The app opens web pages with window.__tfGo(path, state).
 */
class WebBridge(private val listener: Listener) {
    interface Listener {
        fun onRoute(path: String)
        fun onInfo(origin: String, token: String, appId: String, navTopCss: Float, cover: Boolean)
        /** what the published web page can hand over to native screens (dance = Live Dance) */
        fun onCaps(dance: Boolean)
        /** the web's history position of the page now shown (sent just before its address) */
        fun onRouteIdx(idx: Int) {}
        /** the web asked to go back [n] pages (history.back / history.go(-n)): the app does it */
        fun onWebBack(n: Int) {}
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

    @JavascriptInterface
    fun idx(origin: String?, idx: Int) {
        if (!allowed(origin)) return
        main.post { listener.onRouteIdx(idx) }
    }

    @JavascriptInterface
    fun back(origin: String?, n: Int) {
        if (!allowed(origin)) return
        main.post { listener.onWebBack(if (n < 0) n else -1) }
    }

    @JavascriptInterface
    fun caps(origin: String?, dance: Int) {
        if (!allowed(origin)) return
        main.post { listener.onCaps(dance == 1) }
    }

    private fun allowed(origin: String?) = origin != null && MainActivity.ORIGINS.contains(origin)

    companion object {
        const val NAME = "TFBridge"

        /** Runs in the page as early as possible (and again after each load, harmless). */
        val JS = """
(function(){
  if (window.__tfNativeInit) return; window.__tfNativeInit = true;
  window.TFNativeApp = true;
  window.TFNativeGames = 1; // the app draws the games itself (the web game pages stay quiet)
  // This app draws Live Dance itself (the web page then stays empty under it) — but only when
  // this script ran before the page's own code; otherwise the web page keeps doing it all.
  window.TFNativeDance = document.readyState === 'loading';
  var B = window.$NAME; if (!B) return;
  var css = document.createElement('style');
  css.textContent = 'html.tfnx .tfc-stage,html.tfnx .tfc-top,html.tfnx .tfc-acts{display:none!important}html.tfsd #root .sd{visibility:hidden!important}html.tfcr .cr{visibility:hidden!important}'
    + 'html.tfgm #root .tg,html.tfgm #root .hc,html.tfgm #root .mc{display:none!important}'
    // the app moves the pages itself for a moment (tfnav): the web's own slide, under-page and dim stay still
    + 'html.tfnav .tf-pop,html.tfnav .tf-under,html.tfnav .tf-tabdim{visibility:hidden!important;animation:none!important}'
    + 'html.tfnav .tf-push,html.tfnav .tf-pop-in,html.tfnav .tf-glide,html.tfnav .tf-tab.tf-under-anim,html.tfnav .tf-pg-in,html.tfnav .tf-pg-out{animation:none!important}';
  (document.head || document.documentElement).appendChild(css);
  var lastPath = '';
  function sendRoute(){
    var p = (location.pathname || '/') + (location.search || '');
    if (p !== lastPath) {
      lastPath = p;
      var i = (history.state && typeof history.state.idx === 'number') ? history.state.idx : -1;
      try { B.idx(location.origin, i); } catch(e){}
      try { B.route(location.origin, p); } catch(e){}
    }
  }
  // Back inside the web (its back arrows, navigate(-1)) goes through the app, which moves the pages the
  // way the phone's Back does (the page slides away to the right). The app then goes back in the web view.
  try {
    var hb = history.back, hg = history.go;
    history.back = function(){ try { B.back(location.origin, -1); } catch(e){ hb.call(history); } };
    history.go = function(n){ if (typeof n === 'number' && n < 0) { try { B.back(location.origin, n); } catch(e){ hg.call(history, n); } } else hg.apply(history, arguments); };
  } catch(e){}
  ['pushState','replaceState'].forEach(function(k){
    var o = history[k];
    history[k] = function(){ var r = o.apply(this, arguments); setTimeout(sendRoute, 0); setTimeout(info, 0); return r; };
  });
  addEventListener('popstate', function(){ setTimeout(sendRoute, 0); });
  window.__tfGo = function(path, state, replace){
    var cur = (history.state && history.state.idx) || 0;
    var st = { usr: state || null, key: Math.random().toString(36).slice(2, 10), idx: replace ? cur : cur + 1 };
    if (replace) history.replaceState(st, '', path); else history.pushState(st, '', path);
    dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
  };
  // a chat's Back (ChatRoom.jsx goBack): the page before it, or [fallback] in its place
  window.__tfBack = function(fallback){
    var idx = history.state && history.state.idx, here = location.pathname;
    if (typeof idx === 'number' && idx > 0) {
      history.back();
      setTimeout(function(){ if (location.pathname === here) window.__tfGo(fallback, null, true); }, 400);
    } else window.__tfGo(fallback, null, true);
  };
  window.__tfReplace = function(path){
    var idx = (history.state && history.state.idx) || 0;
    history.replaceState({ usr: null, key: Math.random().toString(36).slice(2, 10), idx: idx }, '', path);
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
  function visible(el){
    var h = document.documentElement.classList;
    return !!(el && el.getClientRects().length) && !(el.closest && el.closest('.tfc')) &&
      !(h.contains('tfsd') && el.closest && el.closest('#root .sd')) && !(h.contains('tfcr') && el.closest && el.closest('.cr'));
  }
  function covered(){
    var list = document.querySelectorAll('.tfic,[role="alertdialog"],[aria-modal="true"]');
    for (var i = 0; i < list.length; i++) if (visible(list[i])) return true;
    return false;
  }
  function info(){
    try {
      var tok = localStorage.getItem('base44_access_token') || localStorage.getItem('token') || '';
      if (tok && tok.charAt(0) === '"') { try { tok = JSON.parse(tok); } catch(e){} }
      var top = -1;
      var navs = document.querySelectorAll('nav.tfnav, nav[aria-label="Secret Dating"]');
      for (var i = 0; i < navs.length; i++) { var n = navs[i]; if (n.getClientRects().length) { var r = n.getBoundingClientRect(); if (r.height > 0 && r.top < innerHeight) { top = r.top; break; } } }
      B.info(location.origin, String(tok || ''), findAppId(), top, covered());
      try { B.caps(location.origin, (window.__tfDanceGate && window.TFNativeDance) ? 1 : 0); } catch(e){}
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
