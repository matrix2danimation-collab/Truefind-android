package app.truefind.android

import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Test builds only: shows the native Live Dance screens without the web app,
 * so the automatic checks (androidTest) can open every screen with a fake server.
 * The "web address" here is just a list kept by this activity.
 */
class DanceTestActivity : ComponentActivity() {
    lateinit var screen: LiveDanceScreen
    val history = ArrayList<String>()
    val shared = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        screen = LiveDanceScreen(this, object : LiveDanceScreen.Nav {
            override fun go(path: String) { history.add(path); show(path) }
            override fun replace(path: String) { if (history.isNotEmpty()) history[history.size - 1] = path else history.add(path); show(path) }
            override fun back() { if (history.size > 1) { history.removeAt(history.size - 1); show(history.last()) } }
            override fun askCameraMic(done: (Boolean) -> Unit) = done(false)
            override fun share(title: String, text: String) { shared.add(text) }
        })
        root.addView(screen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun show(path: String) {
        val p = path.substringBefore('?')
        val dance = p == "/dance" || p.startsWith("/dance/")
        if (dance && !path.contains("web=1")) { screen.route(path); screen.setShown(true) } else screen.leave()
    }

    /** open a page as if the web app went there */
    fun open(path: String) { history.add(path); show(path) }

    override fun onResume() { super.onResume(); if (::screen.isInitialized) screen.onAppVisible(true) }
    override fun onPause() { if (::screen.isInitialized) screen.onAppVisible(false); super.onPause() }
    override fun onDestroy() { if (::screen.isInitialized) screen.leave(); super.onDestroy() }
}
