package app.truefind.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
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
 * Opens every native Live Rooms screen with a pretend server (FakeLive), taps what a
 * person would tap, checks what happens and saves a picture of each screen in
 * /data/local/tmp/tf-shots (names start with "L"; the GitHub build pulls them).
 * Problems are collected and reported together at the end, so one run shows everything.
 */
@RunWith(AndroidJUnit4::class)
class LiveScreensTest {
    private lateinit var a: LiveTestActivity
    private lateinit var fake: FakeLive
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val problems = ArrayList<String>()
    private val dir = "/data/local/tmp/tf-shots"

    @Before fun start() {
        fake = FakeLive()
        LiveApi.fake = { n, p -> fake.handle(n, p) }
        shell("mkdir -p $dir")
        val ready = CountDownLatch(1)
        LiveTestActivity.resumed = ready
        inst.targetContext.startActivity(Intent(inst.targetContext, LiveTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("the test screen did not open", ready.await(60, TimeUnit.SECONDS))
        a = LiveTestActivity.last!!
        dismissSystemDialogs()
        pause(800)
    }

    @After fun end() {
        main { a.finish() }
        Thread.sleep(800)
        LiveApi.fake = null
    }

    private fun main(f: () -> Unit) = inst.runOnMainSync { f() }

    private fun shell(cmd: String) {
        try {
            val pfd = inst.uiAutomation.executeShellCommand(cmd)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        } catch (e: Throwable) { }
    }

    private fun act(f: (LiveTestActivity) -> Unit) { main { f(a) } }
    private fun pause(ms: Long) { Thread.sleep(ms); main { } }
    private fun check(ok: Boolean, what: String) { if (!ok) { problems.add(what); android.util.Log.w("TFTEST", "PROBLEM: $what") } }
    private fun note(s: String) = android.util.Log.i("TFTEST", s)

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

    private fun onScreen(): String {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (!v.isShown) return
            v.contentDescription?.let { out.add(it.toString()) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        main { walk(a.window.decorView) }
        return out.take(50).joinToString(" | ")
    }

    private fun shot(name: String) {
        note("shot: $name")
        dismissSystemDialogs()
        pause(350)
        shell("screencap -p $dir/$name.png")
        if (!File("$dir/$name.png").exists()) {
            main {
                val v = a.window.decorView
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(b))
                val f = File(a.filesDir, "shots").apply { mkdirs() }
                FileOutputStream(File(f, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private fun find(root: View, desc: String, prefix: Boolean, out: MutableList<View>) {
        if (!root.isShown) return
        val cd = root.contentDescription?.toString() ?: (root as? android.widget.TextView)?.takeIf { it.isClickable }?.text?.toString()
        if (cd != null && (cd == desc || (prefix && cd.startsWith(desc)))) out.add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), desc, prefix, out)
    }

    private fun views(desc: String, prefix: Boolean = false): List<View> {
        val list = ArrayList<View>()
        main { find(a.window.decorView, desc, prefix, list) }
        return list
    }

    private fun tap(desc: String, prefix: Boolean = false): Boolean {
        note("tap: $desc")
        var done = false
        main {
            val list = ArrayList<View>()
            find(a.window.decorView, desc, prefix, list)
            list.lastOrNull()?.let { it.performClick(); done = true }
        }
        check(done, "nothing to tap: \"$desc\" — on screen: ${if (done) "" else onScreen()}")
        return done
    }

    private fun has(desc: String, prefix: Boolean = false) = views(desc, prefix).isNotEmpty()

    /** all visible texts (for checks on words that are not buttons) */
    private fun texts(): String {
        val out = StringBuilder()
        fun walk(v: View) {
            if (!v.isShown) return
            if (v is android.widget.TextView) out.append(v.text).append(" | ")
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        main { walk(a.window.decorView) }
        return out.toString()
    }
    private fun seesText(t: String) = texts().contains(t)

    private fun type(desc: String, text: String) {
        var done = false
        main {
            val l = ArrayList<View>(); find(a.window.decorView, desc, false, l)
            (l.lastOrNull() as? EditText)?.let { it.setText(text); done = true }
        }
        check(done, "no text box \"$desc\" — on screen: ${if (done) "" else onScreen()}")
    }

    private fun back() { act { x -> if (!x.screen.onBack()) x.screen.nav.back() } }
    private fun called(action: String) = fake.log().contains(action)
    private fun room(): LiveRoomPage? { var p: LiveRoomPage? = null; main { p = a.screen.currentPage as? LiveRoomPage }; return p }
    /** waits up to [ms] for [cond] (checked on the main thread) */
    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { var ok = false; main { ok = cond() }; if (ok) return true; Thread.sleep(150) }
        return false
    }

    @Test fun everyScreen() {
        // ═══════ the Live Room page (/live) ═══════
        note("step: lists")
        act { it.open("/live") }
        pause(2200)
        shot("L01_live_page")
        check(has("Audio Room: ", true), "the Audio Room box is missing — on screen: ${onScreen()}")
        check(has("Go Live"), "Go Live button missing")
        check(!seesText("True Find"), "the page must not show a True Find title (owner rule)")
        check(!has("Demo mode"), "there must be no Demo button on the page (owner rule)")
        check(!seesText("Video Room"), "there must be no Video Room box (Audio only)")

        // ── admins: /live?demo=1 opens the demo tools ──
        act { it.open("/live?demo=1") }
        pause(1500)
        shot("L02_demo_tools")
        tap("Add demo live rooms")
        pause(900)
        check(called("demo_seed"), "demo_seed not sent")

        // ── every Audio Room live now ──
        tap("Audio Room: ", true)
        pause(1600)
        shot("L03_audio_list")
        check(has("Aman Verma is live: ", true), "audio room card missing — on screen: ${onScreen()}")
        check(!has("Riya Sharma is live: ", true), "a video room is in the Audio list")
        check(seesText("12.1K listening"), "listeners are not shown short (12.1K)")
        tap("Music"); pause(700)
        shot("L04_audio_list_music")
        check(!has("Vikram Rao is live: ", true), "the Music chip still shows a Chat room")
        tap("All"); pause(500)

        // ── Leaderboard ──
        tap("Leaderboard"); pause(1600)
        shot("L05_leaderboard")
        check(called("leaderboard"), "leaderboard not asked")
        check(has("Rank 1: Sneha Kapoor", true), "podium missing")
        check(has("You, rank 24: Vikas"), "my own rank row missing")
        tap("View Top 30 Ranking"); pause(600)
        shot("L06_leaderboard_all")
        tap("Monthly"); pause(1200)
        check(fake.bodiesOf("leaderboard").any { it.contains("\"period\":\"month\"") }, "Monthly not asked")
        tap("How the ranking works"); pause(800); shot("L07_rank_info"); back(); pause(500)
        tap("rank 4: ", true); pause(1500)
        shot("L08_host_profile")
        check(called("host_profile"), "host profile not asked")
        back(); pause(500)
        back(); pause(900)

        // ═══════ inside a room, as a listener ═══════
        note("step: room as listener")
        tap("Aman Verma is live: ", true)
        pause(2600)
        shot("L10_room")
        check(called("join"), "join not sent")
        check(room() != null, "the room page did not open — on screen: ${onScreen()}")
        check(has("Host Aman Verma"), "host chip missing")
        check(has("Rahul, Co-Host"), "Co-Host seat missing")
        check(has("Join seat 4 for 10 coins"), "empty seat with Join missing")
        check(has("Super Chat, 2 coins"), "Super Chat box missing")
        check(has("Send Heart for 5 coins"), "Heart button (5 coins) missing")
        check(has("Join list, 2 waiting"), "Join list button missing")
        check(has("Send free hearts"), "free hearts missing")
        check(seesText("Live voice starts soon"), "no 'voice starts soon' while there is no voice pass")
        check(waitFor(6000) { fake.count("state") > 0 }, "the room is not asked again (state)")

        // ── Super Chat (2 coins) ──
        val before = fake.balance
        type("Super Chat, 2 coins", "Superb gaana 🔥")
        pause(200)
        tap("Send Super Chat for 2 coins"); pause(1400)
        check(called("message"), "message not sent")
        check(fake.balance == before - 2, "Super Chat did not cost 2 coins (${before} → ${fake.balance})")
        check(seesText("Superb gaana"), "my Super Chat is not in the chat")
        shot("L11_superchat_sent")
        // a phone number is refused by the server: the reason shows
        type("Super Chat, 2 coins", "call me 98765432100"); pause(150)
        tap("Send Super Chat for 2 coins"); pause(900)
        shot("L11b_superchat_refused")

        // ── one tap = 1 Heart (5 coins) ──
        val b2 = fake.balance
        tap("Send Heart for 5 coins"); pause(1500)
        check(fake.balance == b2 - 5, "Heart did not cost 5 coins (${b2} → ${fake.balance})")
        shot("L12_heart_sent")
        tap("Send free hearts"); pause(500)
        check(called("like"), "like not sent")
        shot("L13_free_hearts")

        // ── gifts ──
        note("step: gifts")
        tap("Send a gift"); pause(1100)
        shot("L14_gifts")
        tap("Teddy, 50 coins"); pause(200)
        tap("x5"); pause(300)
        shot("L15_gift_chosen")
        check(seesText("Send Gift · 250 coins"), "the price on the send button is not 5 × 50 = 250")
        val b3 = fake.balance
        tap("Send Gift"); pause(1600)
        check(fake.balance == b3 - 250, "Teddy x5 did not cost 250 coins (${b3} → ${fake.balance})")
        shot("L16_gift_sent")
        tap("Send a gift"); pause(1000)
        tap("Gift to Rahul"); pause(200)
        tap("Love"); pause(300)
        tap("Kiss, 20 coins"); pause(200)
        tap("Send Gift"); pause(1500)
        check(fake.bodiesOf("gift").any { it.contains("\"to_user_id\":\"u_Rahul\"") && it.contains("\"gift_id\":\"kiss\"") }, "the gift to a Co-Host was not sent to him")

        // ── the panel: Chat · Users · Gifts · Info ──
        note("step: panel")
        tap("Chat, people, gifts and room info"); pause(1000)
        shot("L17_panel_chat")
        tap("Users (760)"); pause(1300)
        check(called("users"), "users not asked")
        shot("L18_panel_users")
        tap("Online (", true); pause(400)
        tap("Info"); pause(700)
        shot("L19_panel_info")
        tap("Room Stats"); pause(1300)
        check(called("stats"), "stats not asked")
        shot("L20_room_stats")
        tap("Weekly"); pause(1200)
        shot("L21_room_stats_week")
        tap("Back"); pause(500)
        tap("Room Rules"); pause(600); shot("L22_room_rules"); tap("Back"); pause(500)
        tap("Room Level"); pause(1300)
        check(called("level"), "level not asked")
        shot("L23_room_level")
        tap("See all 40 levels"); pause(700)
        shot("L24_all_levels")
        tap("Back"); pause(500)
        tap("About Room"); pause(600); shot("L25_about_room")
        back(); pause(600)

        // ── header ──
        // (no "Copy Room ID" tap here: the test emulator runs without a window, and both runs that copied
        // to its clipboard lost the emulator at about that moment; copying is checked on a real phone)
        check(has("Copy Room ID"), "Copy Room ID missing")
        tap("Share room"); pause(300)
        check(a.shared.any { it.contains("TF-482913") }, "share text has no Room ID")
        tap("Follow room"); pause(900)
        check(called("follow"), "follow not sent")
        check(has("Unfollow room"), "the + did not turn into a tick after following")
        tap("Host Aman Verma"); pause(1500)
        shot("L26_host_profile_in_room")
        back(); pause(500)
        tap("Level ", true); pause(1300)
        shot("L26b_level_from_badge")
        back(); pause(500)

        // ── one person: report (with a screenshot) and block ──
        note("step: report and block")
        tap("Rahul, Co-Host"); pause(900)
        shot("L27_person")
        check(has("Send a gift to Rahul"), "person sheet has no gift")
        tap("Report"); pause(900)
        tap("Abuse or bad language"); pause(200)
        type("What happened", "He was using bad words again and again")
        tap("Add screenshot"); pause(500)
        check(has("Remove screenshot"), "the screenshot was not added")
        shot("L28_report")
        tap("Send Report"); pause(2000)
        check(called("upload"), "screenshot not uploaded")
        check(called("report"), "report not sent")
        check(called("moderateUser"), "moderateUser report not sent")
        shot("L29_after_report")
        tap("More options"); pause(800)
        shot("L30_menu")
        tap("Block someone"); pause(1400)
        tap("Mira"); pause(800)
        shot("L31_block")
        tap("Block"); pause(1200)
        check(fake.count("moderateUser") >= 2, "block not sent")
        pause(400)
        check(!has("Mira"), "Mira's chat lines still show after blocking her")
        tap("More options"); pause(700)
        tap("Rate this room"); pause(700)
        shot("L32_rate")
        tap("Yes, love it"); pause(900)
        check(called("rate"), "rate not sent")

        // ── Join list → seat ──
        note("step: join list and seat")
        tap("Join list, 2 waiting"); pause(900)
        shot("L33_join_list")
        tap("Request to join"); pause(1300)
        check(called("wish"), "wish not sent")
        shot("L34_join_list_requested")
        back(); pause(500)
        tap("Join seat 4 for 10 coins"); pause(1000)
        shot("L35_pay_and_join")
        check(seesText("10 Coins"), "Pay & Join does not show 10 coins")
        val b4 = fake.balance
        tap("Pay & Join"); pause(2200)
        check(called("cohost_join"), "cohost_join not sent")
        check(fake.balance == b4 - 10, "the seat did not cost 10 coins")
        check(has("Turn mic off"), "no mic button on my seat — on screen: ${onScreen()}")
        shot("L36_on_seat")
        tap("Turn mic off"); pause(1200)
        check(called("mute"), "mute not sent")
        check(has("Turn mic on"), "mic did not turn off")
        shot("L37_mic_off")
        tap("More options"); pause(700)
        tap("Leave Co-Host seat"); pause(1500)
        check(called("cohost_leave"), "cohost_leave not sent")
        check(!has("Turn mic on") && !has("Turn mic off"), "mic still shows after leaving the seat")

        // ── leave → back to the list ──
        tap("Leave room"); pause(1200)
        check(called("leave"), "leave not sent")
        var last = ""
        main { last = a.history.lastOrNull().orEmpty() }
        check(last == "/live?m=audio", "Leave room went to \"$last\" instead of the list")
        shot("L38_back_to_list")

        // ═══════ invited from the Join list: Pay & Join pops up ═══════
        note("step: invited")
        act { it.open("/live/inv1") }
        pause(2600)
        shot("L40_invited")
        check(has("Pay & Join"), "Pay & Join did not pop up for an invite")
        back(); pause(600)

        // ═══════ not enough coins ═══════
        note("step: no coins")
        act { it.open("/live/poor1") }
        pause(2400)
        type("Super Chat, 2 coins", "Hello"); pause(150)
        tap("Send Super Chat for 2 coins"); pause(900)
        shot("L41_no_coins_chat")
        tap("Send a gift"); pause(1000)
        tap("Teddy, 50 coins"); tap("Send Gift"); pause(1300)
        check(seesText("You need 50 coins. You have 1."), "no 'You need 50 coins' message")
        shot("L42_no_coins_gift")
        tap("Recharge"); pause(1200)
        var outside = ""
        main { outside = a.outside.lastOrNull().orEmpty() }
        check(outside == "/wallet", "Recharge did not open the Wallet (went to \"$outside\")")

        // ═══════ on a Co-Host seat already ═══════
        act { it.open("/live/co1") }
        pause(2500)
        shot("L43_cohost")
        check(has("Turn mic off"), "Co-Host has no mic button")
        check(has("Nikhil, Co-Host") && has("Vikas, Co-Host"), "seats missing")

        // ═══════ ended / can't open ═══════
        act { it.open("/live/ended1") }; pause(2000)
        shot("L44_ended")
        tap("Follow Room"); pause(900)
        check(has("Following"), "Follow on the ended page did not turn to Following")
        act { it.open("/live/gone1") }; pause(2000)
        shot("L45_cant_open")
        check(seesText("Can't open this room"), "no 'Can't open this room'")

        // ═══════ the host: Go Live → my room → Join list → End live ═══════
        note("step: host")
        act { it.open("/live") }; pause(1800)
        tap("Go Live"); pause(1000)
        shot("L50_go_live")
        type("Room name", "Vikas ki Mehfil")
        tap("Start Live"); pause(2800)
        check(called("go_live"), "go_live not sent")
        check(fake.bodiesOf("go_live").any { it.contains("\"mode\":\"audio\"") }, "Go Live did not ask for an audio room")
        shot("L51_host_room")
        check(room()?.isHost() == true, "not in my own room as the host — on screen: ${onScreen()}")
        check(!has("Send Heart for 5 coins"), "the host sees the Heart button")
        check(has("Message"), "host message box missing")
        type("Message", "Welcome sabko 🙏"); pause(150)
        tap("Send"); pause(1300)
        check(fake.bodiesOf("message").any { it.contains("Welcome sabko") }, "host message not sent")
        tap("Join list, 2 waiting"); pause(900)
        shot("L52_host_join_list")
        tap("Invite Priya"); pause(1800)
        check(called("wish_invite"), "wish_invite not sent")
        shot("L53_host_after_invite")
        back(); pause(500)
        // the phone's Back asks first; Keep Live stays
        back(); pause(800)
        shot("L54_end_confirm")
        tap("Keep Live"); pause(600)
        check(room() != null, "Keep Live left the room")
        back(); pause(700)
        tap("End Live"); pause(1600)
        check(called("end"), "end not sent")
        shot("L55_after_end")

        // ═══════ the voice engine (Agora) with a made-up pass: loads, joins nothing real ═══════
        note("step: voice")
        act { it.open("/live/voice1") }
        val voiced = waitFor(9000) { room()?.voiceOn == true }
        note("voice1: voiceOn=${room()?.voiceOn} voiced=$voiced")
        val inst2 = try {
            val f = io.agora.rtc2.RtcEngine::class.java.getDeclaredField("mInstance"); f.isAccessible = true; f.get(null) != null
        } catch (t: Throwable) { note("mInstance: ${t.javaClass.simpleName} ${t.message}"); false }
        note("voice engine kept in RtcEngine.mInstance: $inst2")
        check(voiced, "the voice did not start with a pass (Agora)")
        check(inst2, "Agora's one engine is not found (RtcEngine.mInstance): rooms would cut Live Dance / games off")
        shot("L60_voice_room")
        act { it.open("/live") }; pause(1200)

        note("calls: ${fake.log().groupingBy { it }.eachCount()}")
        assertTrue("Problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
