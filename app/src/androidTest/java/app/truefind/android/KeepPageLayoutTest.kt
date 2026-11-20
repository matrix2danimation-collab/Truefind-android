package app.truefind.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The owner's empty Wallet (20 Nov): both Wallets showed only their header and an empty white sheet, from every way in.
 * On a real Android phone (the emulator), the real WalletScreen with made-up data (WalletDemo):
 *   shown → hidden while its answer is still on the way → the screen gets shorter (a keyboard opens in a chat) → the
 *   answer arrives and the hidden Wallet is drawn again → the screen gets its height back → the Wallet is shown again.
 * 1. with the old behaviour (KeepPage.testNoHeal): the new rows are never laid out — the problem itself;
 * 2. with the fix: every row is laid out and has a size.
 * Plus the plain cases (opened, closed and opened again; the keyboard while it is shown) — all must show their rows.
 */
@RunWith(AndroidJUnit4::class)
class KeepPageLayoutTest {
    private lateinit var a: LiveTestActivity
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var host: FrameLayout
    private lateinit var wallet: WalletScreen

    @Before fun start() {
        val ready = CountDownLatch(1)
        LiveTestActivity.resumed = ready
        inst.targetContext.startActivity(Intent(inst.targetContext, LiveTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("the test screen did not open", ready.await(60, TimeUnit.SECONDS))
        a = LiveTestActivity.last!!
        main {
            val root = a.findViewById<ViewGroup>(android.R.id.content)
            host = PageHost(a)
            root.addView(host, FrameLayout.LayoutParams(-1, -1))
            host.elevation = 1000f
            wallet = WalletScreen(a, { _, _ -> }, { }, { }, { })
            wallet.source = WalletDemo("plan")
            wallet.visibility = View.GONE
            host.addView(wallet, FrameLayout.LayoutParams(-1, -1))
        }
        pause(600)
    }

    @After fun end() { KeepPage.testNoHeal = false; main { a.finish() }; Thread.sleep(600) }

    private fun main(f: () -> Unit) = inst.runOnMainSync { f() }
    private fun pause(ms: Long) { Thread.sleep(ms); inst.waitForIdleSync() }

    /** shown views (in shown parents) that were never laid out, and the texts that have no size */
    private fun unlaid(v: View, out: MutableList<String>) {
        if (v.visibility != View.VISIBLE) return
        if (!v.isLaidOut) out.add(v.javaClass.simpleName + ((v as? TextView)?.text?.let { " \"$it\"" } ?: ""))
        if (v is ViewGroup) for (i in 0 until v.childCount) unlaid(v.getChildAt(i), out)
    }
    private fun texts(v: View, out: MutableList<TextView>) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView && v.text.isNotBlank()) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
    }
    private fun state(): Pair<List<String>, List<String>> {
        val un = ArrayList<String>(); val tv = ArrayList<TextView>()
        main { unlaid(wallet, un); texts(wallet, tv) }
        val noSize = tv.filter { it.width == 0 || it.height == 0 }.map { "\"${it.text}\"" }
        return un to noSize
    }

    /** the owner's way: hidden while the answer is on the way, a keyboard elsewhere, then shown again */
    private fun ownersWay() {
        main { wallet.setShown(true, "") }
        pause(1500)
        main { wallet.setShown(false, "") }
        pause(400)
        // shown again (it asks the server again) and hidden at once, before the answer
        main { wallet.setShown(true, "app=secret", fresh = true); wallet.setShown(false, "") }
        // a keyboard opens in a chat: the screen is shorter while the Wallet is hidden
        main { (host.layoutParams as FrameLayout.LayoutParams).let { it.height = host.height - 600; host.layoutParams = it } }
        pause(1500)           // the answers arrive (450 ms) and the hidden Wallet is drawn again
        main { (host.layoutParams as FrameLayout.LayoutParams).let { it.height = -1; host.layoutParams = it } }
        pause(500)
        main { wallet.setShown(true, "app=secret", fresh = true) }
        pause(1500)
    }

    @Test fun walletAfterKeyboardWhileHidden() {
        val problems = ArrayList<String>()
        // 1. the old behaviour: the problem shows itself
        KeepPage.testNoHeal = true
        ownersWay()
        val (un1, nz1) = state()
        android.util.Log.i("TFTEST", "old behaviour: ${un1.size} views never laid out, ${nz1.size} texts without size: ${un1.take(6)}")
        if (un1.isEmpty()) problems.add("the old behaviour did not show the problem (it could not be made to happen here)")
        // 2. the fix: shown again → everything laid out
        KeepPage.testNoHeal = false
        main { wallet.setShown(false, "") }
        pause(300)
        ownersWay()
        val (un2, nz2) = state()
        android.util.Log.i("TFTEST", "fixed: ${un2.size} views never laid out, ${nz2.size} texts without size: ${un2.take(6)} ${nz2.take(6)}")
        if (un2.isNotEmpty()) problems.add("after the fix still never laid out: ${un2.take(8)}")
        if (nz2.isNotEmpty()) problems.add("after the fix texts without size: ${nz2.take(8)}")
        // 3. five more rounds of the same, each must show everything
        repeat(5) { i ->
            main { wallet.setShown(false, "") }
            pause(300)
            ownersWay()
            val (u, n) = state()
            if (u.isNotEmpty() || n.isNotEmpty()) problems.add("round ${i + 3}: never laid out ${u.take(5)}, without size ${n.take(5)}")
        }
        // 4. the plain ways in: opened, closed, opened again; True Find ↔ Secret Dating; a keyboard while it shows
        main { wallet.setShown(false, "") }; pause(300)
        main { wallet.setShown(true, "") }; pause(1500)
        main { wallet.setShown(true, "app=secret") }; pause(1500)
        main { (host.layoutParams as FrameLayout.LayoutParams).let { it.height = host.height - 600; host.layoutParams = it } }; pause(800)
        main { (host.layoutParams as FrameLayout.LayoutParams).let { it.height = -1; host.layoutParams = it } }; pause(800)
        val (u4, n4) = state()
        if (u4.isNotEmpty() || n4.isNotEmpty()) problems.add("plain ways: never laid out ${u4.take(5)}, without size ${n4.take(5)}")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
