package app.truefind.android.games

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * NATIVE GAMES. Whenever the web app is on /games or a game's address, the phone draws the Games
 * page and the games itself (this layer, on top of the web page):
 *   /games              → Games page (hub)
 *   /games/heart-chase  → Heart Chase Boxes
 *   /games/mood-changer → Mood Changer
 *   /games/clue-me      → ClueMe page
 * ClueMe (one phone / two phones) and Dress Code open from the hub, like on the web.
 * Every game talks to the same Base44 functions as the web, so the rules and coins are the same.
 *
 * [go]      opens a web page of the app: go(path, stateJson)
 * [webBack] the web's history back; when there is no page behind, opens [fallback] instead
 */
@SuppressLint("ViewConstructor")
class GamesLayer(
    ctx: Context,
    private val go: (String, JSONObject?) -> Unit,
    private val webBack: (fallback: String) -> Unit,
) : FrameLayout(ctx) {

    private var hub: GamesHub? = null
    private var routed: GameScreen? = null
    private var routedKey = ""
    private val overlays = ArrayList<GameScreen>()
    private var inGames = false

    init {
        setBackgroundColor(Color.WHITE)
        visibility = View.GONE
        isClickable = true // nothing falls through to the hidden web page
    }

    val active get() = inGames

    /** From the app: the web's address, whether we are signed in, whether a web pop-up covers. */
    fun setRoute(path: String, signedIn: Boolean, covered: Boolean) {
        val p = path.trimEnd('/')
        val nowIn = signedIn && (p == "/games" || p.startsWith("/games/"))
        if (!nowIn) {
            if (inGames) closeAll()
            inGames = false
            visibility = View.GONE
            return
        }
        inGames = true
        if (hub == null) hub = GamesHub(context, this).also { addView(it, 0, LayoutParams(G.MATCH, G.MATCH)) }
        val key = when (p) {
            "/games/heart-chase" -> "heart-chase"
            "/games/mood-changer" -> "mood-changer"
            "/games/clue-me" -> "clue-me"
            else -> ""
        }
        if (key != routedKey) {
            routed?.let { dispose(it) }
            routed = null
            routedKey = key
            if (key.isNotEmpty()) {
                // a game with its own address is never under a hub pop-up
                for (o in ArrayList(overlays)) dispose(o)
                overlays.clear()
                routed = make(key)?.also { addView(it, LayoutParams(G.MATCH, G.MATCH)) }
            }
        }
        visibility = if (covered) View.GONE else View.VISIBLE
        hub?.onShown(visibility == View.VISIBLE && routed == null && overlays.isEmpty())
    }

    private fun make(key: String): GameScreen? = when (key) {
        "heart-chase" -> HeartChaseScreen(context, this)
        "mood-changer" -> MoodChangerScreen(context, this)
        "clue-me" -> ClueMePage(context, this)
        else -> null
    }

    /** The hub opens a game. Games with an address get it on the web too (history, back button). */
    fun open(key: String) {
        when (key) {
            "heart-chase", "mood-changer" -> {
                if (routedKey != key) {
                    routed?.let { dispose(it) }
                    routedKey = key
                    routed = make(key)?.also { addView(it, LayoutParams(G.MATCH, G.MATCH)) }
                }
                hub?.onShown(false)
                go("/games/$key", null)
            }
            "clue-local" -> overlay(NameGuessLocal(context, this))
            "clue-online" -> overlay(NameGuessOnline(context, this))
            "dress-code" -> overlay(DressCodeScreen(context, this))
            "clue-sheet" -> hub?.showClueSheet()
        }
    }

    private fun overlay(s: GameScreen) {
        overlays.add(s)
        addView(s, LayoutParams(G.MATCH, G.MATCH))
        s.alpha = 0f
        s.animate().alpha(1f).setDuration(180).start()
        hub?.onShown(false)
    }

    /** A screen closes itself (Exit / Done / back). */
    fun close(s: GameScreen) {
        if (overlays.remove(s)) {
            dispose(s)
            hub?.onShown(routed == null && overlays.isEmpty())
            hub?.refreshWallet()
            return
        }
        if (s === routed) {
            dispose(s)
            routed = null
            routedKey = ""
            hub?.onShown(overlays.isEmpty())
            webBack("/games")
        }
    }

    /** The Games page's own back (web history back, to wherever Games was opened from). */
    fun back() = webBack("/")

    /** Opens a web page (wallet, chat, Reconnect, profile …). Leaving /games ends the games. */
    fun openWeb(path: String) = go(path, null)

    fun onBack(): Boolean {
        if (!inGames || visibility != View.VISIBLE) return false
        val top: GameScreen? = overlays.lastOrNull() ?: routed
        if (top != null) {
            if (top.onBack()) return true
            close(top)
            return true
        }
        return hub?.onBack() == true
    }

    private fun dispose(s: GameScreen) {
        try { s.leave() } catch (e: Throwable) { }
        s.destroy()
        removeView(s)
    }

    private fun closeAll() {
        for (o in ArrayList(overlays)) dispose(o)
        overlays.clear()
        routed?.let { dispose(it) }
        routed = null; routedKey = ""
        hub?.let { it.destroy(); removeView(it) }
        hub = null
    }

    /** The app is closing for good: end any running game (web: pagehide). */
    fun shutdown() { if (inGames) closeAll(); inGames = false }
}
