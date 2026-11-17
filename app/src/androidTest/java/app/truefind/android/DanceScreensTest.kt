package app.truefind.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opens every native Live Dance screen with a pretend server (FakeDance),
 * taps what a person would tap, checks what happens and saves a picture of
 * each screen in /data/local/tmp/tf-shots (the GitHub build pulls them).
 * Problems are collected and reported together at the end, so one run shows everything.
 * (No "wait until the app is idle": the screens have endless animations, like the web page.)
 */
@RunWith(AndroidJUnit4::class)
class DanceScreensTest {
    private lateinit var a: DanceTestActivity
    private lateinit var fake: FakeDance
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val problems = ArrayList<String>()
    private val dir = "/data/local/tmp/tf-shots"

    @Before fun start() {
        fake = FakeDance()
        DanceApi.fake = { n, p -> fake.handle(n, p) }
        shell("mkdir -p $dir")
        val ready = CountDownLatch(1)
        DanceTestActivity.resumed = ready
        inst.targetContext.startActivity(Intent(inst.targetContext, DanceTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("the test screen did not open", ready.await(60, TimeUnit.SECONDS))
        a = DanceTestActivity.last!!
        dismissSystemDialogs()
        pause(800)
    }

    @After fun end() {
        DanceApi.fake = null
        main { a.finish() }
        Thread.sleep(600)
    }

    /** run on the app's main thread and wait for it (does not wait for animations to stop) */
    private fun main(f: () -> Unit) = inst.runOnMainSync { f() }

    private fun shell(cmd: String) {
        try {
            val pfd = inst.uiAutomation.executeShellCommand(cmd)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        } catch (e: Throwable) { }
    }

    private fun act(f: (DanceTestActivity) -> Unit) { main { f(a) } }
    private fun pause(ms: Long) { Thread.sleep(ms); main { } }
    private fun check(ok: Boolean, what: String) { if (!ok) problems.add(what) }

    /** a slow emulator sometimes shows "… isn't responding" for its own home screen: tap "Wait" */
    private fun dismissSystemDialogs() {
        try {
            val ua = inst.uiAutomation
            for (i in 0 until 3) {
                val root = ua.rootInActiveWindow ?: return
                if (root.packageName?.toString() == inst.targetContext.packageName) return
                val btn = root.findAccessibilityNodeInfosByText("Wait").firstOrNull() ?: root.findAccessibilityNodeInfosByText("Close app").firstOrNull() ?: return
                var n: AccessibilityNodeInfo? = btn
                while (n != null && !n.isClickable) n = n.parent
                (n ?: btn).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(700)
            }
        } catch (e: Throwable) { }
    }

    /** all contentDescriptions on screen (to explain a missing one) */
    private fun onScreen(): String {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (!v.isShown) return
            v.contentDescription?.let { out.add(it.toString()) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        main { walk(a.window.decorView) }
        return out.take(40).joinToString(" | ")
    }

    private fun shot(name: String) {
        dismissSystemDialogs()
        pause(300)
        shell("screencap -p $dir/$name.png")
        if (!File("$dir/$name.png").exists()) {
            // fallback: draw the window ourselves (kept inside the app)
            main {
                val v = a.window.decorView
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(b))
                val f = File(a.filesDir, "shots").apply { mkdirs() }
                FileOutputStream(File(f, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** the visible views whose contentDescription is [desc] (or starts with it when [prefix]) */
    private fun find(root: View, desc: String, prefix: Boolean, out: MutableList<View>) {
        if (!root.isShown) return
        // its description, or (for a tappable text without one) its text
        val cd = root.contentDescription?.toString() ?: (root as? android.widget.TextView)?.takeIf { it.isClickable }?.text?.toString()
        if (cd != null && (cd == desc || (prefix && cd.startsWith(desc)))) out.add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), desc, prefix, out)
    }

    private fun views(desc: String, prefix: Boolean = false): List<View> {
        val list = ArrayList<View>()
        main { find(a.window.decorView, desc, prefix, list) }
        return list
    }

    private fun tap(desc: String, prefix: Boolean = false) {
        var done = false
        main {
            val list = ArrayList<View>()
            find(a.window.decorView, desc, prefix, list)
            list.lastOrNull()?.let { it.performClick(); done = true }
        }
        check(done, "nothing to tap: \"$desc\" — on screen: ${if (done) "" else onScreen()}")
    }

    private fun has(desc: String, prefix: Boolean = false) = views(desc, prefix).isNotEmpty()

    private fun back() { act { x -> if (!x.screen.onBack()) x.screen.nav.back() } }

    private fun called(action: String) = fake.log().contains(action)

    @Test fun everyScreen() {
        // ── the 3 big boxes ──
        act { it.open("/dance") }
        pause(2500)
        shot("01_boxes")
        check(has("Solo Dance: ", true), "Solo box missing — on screen: ${onScreen()}")
        check(has("1 vs 1 Battle: ", true), "1 vs 1 box missing")
        check(has("2 Dancer Battle: ", true), "2 Dancer box missing")
        main {
            val l = ArrayList<View>(); find(a.window.decorView, "2 Dancer Battle: ", true, l)
            var p = l.firstOrNull()?.parent
            while (p != null && p !is RecyclerView) p = p.parent
            (p as? RecyclerView)?.smoothScrollBy(0, 2000)
        }
        pause(900)
        shot("02_boxes_scrolled")

        // ── demo mode sheet (admins) ──
        tap("Demo mode")
        pause(700)
        shot("03_demo_sheet")
        tap("Add demo dance lives")
        pause(900)
        check(called("demo_seed"), "demo_seed not sent")
        back(); pause(500)

        // ── the lives of each mode ──
        tap("Solo Dance: ", true)
        pause(1500)
        shot("04_solo_list")
        check(has("Bollywood Night", true), "solo live card missing")
        back(); pause(700)
        tap("1 vs 1 Battle: ", true)
        pause(1500)
        shot("05_battle_list")
        back(); pause(700)
        tap("2 Dancer Battle: ", true)
        pause(1500)
        shot("06_duo_list")

        // ── entry fee ──
        act { it.open("/dance/pay1") }
        pause(2000)
        shot("07_pay")
        tap("Join Live Room for 50 coins")
        pause(2500)
        shot("08_after_pay_live")
        check(called("pay"), "pay not sent")
        check(has("All gifts"), "room did not open after paying")

        // ── solo live ──
        act { it.open("/dance/solo1") }
        pause(2500)
        shot("09_solo_live")
        tap("Send hearts")
        pause(400)
        shot("10_hearts")
        // quick gift: the first tap asks, the second tap sends (it stops asking after 3.5 s)
        tap("Send Rose for 10 coins", true)
        pause(300)
        shot("11_quick_armed")
        if (!has("Tap again to send Rose", true)) tap("Send Rose for 10 coins", true)
        tap("Tap again to send Rose", true)
        pause(1200)
        shot("12_gift_sent")
        check(called("gift"), "gift not sent")
        tap("All gifts"); pause(800); shot("13_gift_sheet")
        tap("Teddy, 50 coins"); pause(300); shot("13b_gift_sheet_teddy")
        back(); pause(500)
        tap("Request a song"); pause(800); shot("14_song_sheet"); back(); pause(500)
        tap("Rules"); pause(800); shot("15_rules"); back(); pause(500)
        tap("Room info"); pause(800); shot("16_room_info"); back(); pause(500)
        tap("View all supporters"); pause(1200); shot("17_supporters"); back(); pause(500)
        tap("People (", true); pause(1200); shot("18_people")
        tap("Rahul"); pause(800); shot("19_person"); back(); pause(500)
        // a chat message
        main {
            val l = ArrayList<View>(); find(a.window.decorView, "Message", false, l)
            (l.firstOrNull() as? EditText)?.setText("Superb dance 🔥")
        }
        tap("Send"); pause(1200)
        check(called("message"), "message not sent")
        pause(800)
        shot("19b_message_sent")
        check(has("Vikas"), "my message is not in the chat")
        tap("Emojis"); pause(400); shot("20_emoji"); back(); pause(300)

        // ── camera-off solo + a song request that is waiting ──
        act { it.open("/dance/solo2") }; pause(2200); shot("21_solo_camera_off")
        act { it.open("/dance/solo3") }; pause(2200); tap("Request a song"); pause(800); shot("22_song_waiting"); back(); pause(400)

        // ── 1 vs 1 battle: vote, rank ──
        act { it.open("/dance/battle1") }
        pause(2500)
        shot("23_battle_live")
        tap("Vote Riya", true); pause(1500); shot("24_voted")
        check(called("vote"), "vote not sent")
        check(has("Voted Riya", true), "vote button did not change to Voted")
        tap("Battle Rank"); pause(1200); shot("25_rank"); back(); pause(500)

        // ── a battle ends → the winner ──
        act { it.open("/dance/battle3") }
        var winner = false
        for (i in 0 until 48) {          // up to 12 s: the battle ends on the 2nd check with the server
            pause(250)
            var seen = false
            main { val l = ArrayList<View>(); find(a.window.decorView, "Winner:", true, l); seen = l.any { it.alpha > 0.5f } }
            if (seen) { winner = true; break }
        }
        pause(700)
        shot("26_winner")
        android.util.Log.i("TFTEST", "battle3 calls: ${fake.timeline("battle3")}")
        check(winner, "winner not shown — calls: ${fake.timeline("battle3")}")

        // ── empty seat → request to dance → on the stage ──
        act { it.open("/dance/battle2") }
        pause(2200)
        shot("27_empty_seat")
        tap("Request to dance"); pause(3500)
        shot("28_on_stage")
        check(called("seat_request"), "seat_request not sent")
        check(has("Leave stage"), "dancer tools missing")
        tap("Leave stage"); pause(600); shot("29_leave_confirm"); back(); pause(400)

        // ── 2 dancer battle ──
        act { it.open("/dance/duo1") }
        pause(2500)
        shot("30_duo_live")
        tap("All gifts"); pause(800); shot("31_duo_gift_sheet"); back(); pause(400)

        // ── ended / can't open ──
        act { it.open("/dance/ended1") }; pause(2000); shot("32_ended")
        act { it.open("/dance/gone1") }; pause(2000); shot("33_cant_open")

        // ═══════ the host side ═══════
        // ── Go Live: 1 vs 1 ──
        act { it.open("/dance") }; pause(1800)
        tap("Go Live Dance"); pause(900); shot("40_golive_sheet")
        tap("1 vs 1 Battle"); pause(300); shot("40b_golive_battle")
        tap("Start 1 vs 1 Battle"); pause(2600)
        shot("41_host_battle_waiting")
        check(called("go_live"), "go_live not sent")
        check(has("Dancers"), "host tools missing")
        check(has("Dance requests"), "empty seat has no Requests button for the host")
        // ── accept a dancer ──
        tap("Dancers"); pause(1300); shot("42_seats_sheet")
        tap("Accept Kabir"); pause(2200)
        check(called("seat_answer"), "seat_answer not sent")
        shot("43_host_with_challenger")
        // ── start a battle ──
        tap("Start battle"); pause(900); shot("44_timer_sheet")
        tap("5 minutes"); pause(200)
        tap("Start 5 min battle"); pause(2200)
        check(called("battle_start"), "battle_start not sent")
        shot("45_host_battle_running")
        // ── stop it ──
        tap("Stop battle"); pause(700); shot("46_stop_confirm")
        tap("Stop battle"); pause(1800)
        check(called("battle_stop"), "battle_stop not sent")
        shot("46b_after_stop")
        // ── mic ──
        tap("Mic on"); pause(1600)
        check(called("mute"), "mute not sent")
        check(has("Mic off"), "mic tool did not turn to Mic off")
        shot("47_mic_off")
        // ── the list shows "You are live" ──
        act { it.open("/dance") }; pause(1800); shot("47b_list_you_are_live")
        act { it.open("/dance/hb1") }; pause(1800)
        // ── phone back asks before ending; End live ends ──
        back(); pause(700); shot("48_back_asks_end")
        tap("End live"); pause(1800)
        check(called("end"), "end not sent")
        shot("49_after_end")
        // ── Solo host: song requests board ──
        act { it.open("/dance/hs1") }; pause(2400); shot("50_host_solo")
        tap("Requests"); pause(1400); shot("51_songs_board")
        tap("Mark as played"); pause(1600)
        check(called("song_answer"), "song_answer not sent")
        shot("51b_songs_after_play")
        back(); pause(500)
        tap("Demo"); pause(800); shot("52_demo_in_room"); back(); pause(400)
        // ── 2 Dancer host ──
        act { it.open("/dance/hd1") }; pause(2400); shot("53_host_duo_waiting")

        // ── back from a room goes back to that list ──
        act { it.open("/dance?m=solo") }; pause(800)
        act { it.open("/dance/solo1") }; pause(1500)
        back(); pause(900)
        shot("34_back_to_list")
        var last = ""
        main { last = a.history.lastOrNull().orEmpty() }
        check(last == "/dance?m=solo", "back went to \"$last\" instead of /dance?m=solo")

        assertTrue("Problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
