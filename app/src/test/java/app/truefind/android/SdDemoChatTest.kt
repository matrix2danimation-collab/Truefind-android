package app.truefind.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** demo chats (testing only): answered on the phone, the list's five keep the web's words, Send Request starts a new one */
class SdDemoChatTest {
    private val now = 1_763_800_000_000L
    @Before fun fresh() { SdDemoChat.reset() }

    @Test fun aDemoProfileHasItsOwnChat() {
        val girls = SdDemo.people("m")
        assertEquals("demo-chat-37", SdDemoChat.idFor(girls[37]))
        assertEquals("demo-chat-0", SdDemoChat.idFor(girls[0]))
        assertEquals("", SdDemoChat.idFor(JSONObject().put("id", "abc123")))
        assertTrue(SdDemoChat.owns("demo-chat-37"))
        assertFalse(SdDemoChat.owns("m1"))
        assertFalse(SdDemoChat.owns("demo-chat-x"))
        assertFalse(SdDemoChat.owns("demo-chat-100"))
        assertEquals(girls[37].optString("id"), SdDemoChat.person("demo-chat-37")?.optString("id"))
    }

    @Test fun theFiveListChatsEndWithTheWordsTheirRowShows() {
        val rows = SdChatList.demoChats("m", now)
        for (n in 0..4) {
            val last = SdDemoChat.firstLines(n, now).last()
            assertEquals(rows[n].text, last.text)
            assertEquals(rows[n].mine, last.mine)
            assertEquals(SdRules.ms(rows[n].time), last.at)
            assertEquals(2, SdDemoChat.firstLines(n, now).size)
        }
        assertTrue(SdDemoChat.firstLines(37, now).isEmpty())
    }

    @Test fun untouchedTheListIsTheWebsFive() {
        val rows = SdDemoChat.rows("m", now)
        val web = SdChatList.demoChats("m", now)
        assertEquals(web.map { it.id }, rows.map { it.id })
        assertEquals(web.map { it.text }, rows.map { it.text })
        assertEquals(web.map { it.unread }, rows.map { it.unread })
    }

    @Test fun whatIsWrittenStaysInTheChatAndShowsInTheList() {
        val id = "demo-chat-1"
        SdDemoChat.start(id, now)
        val r = SdDemoChat.function("sendMessage", JSONObject().put("match_id", id).put("kind", "text").put("text", "Kaise ho?"), now + 1000)
        assertNotNull(r); assertTrue(r!!.ok)
        assertEquals(SdDemoChat.SELF, r.data.optJSONObject("message")?.optString("from_user_id"))
        val all = SdDemoChat.messages(id, now)
        assertEquals(3, all.size)
        assertEquals("Kaise ho?", all.last().optString("text"))
        // the server's order: the newest first
        val f = SdDemoChat.filter("Message", JSONObject().put("match_id", id), now)!!.data.optJSONArray("items")!!
        assertEquals("Kaise ho?", f.optJSONObject(0).optString("text"))
        assertEquals(3, f.length())
        val row = SdDemoChat.rows("m", now + 2000).first { it.id == id }
        assertEquals("Kaise ho?", row.text)
        assertTrue(row.mine)
        assertEquals(0, row.unread)
    }

    @Test fun giftsAreNotSentInADemoChat() {
        val r = SdDemoChat.function("sendMessage", JSONObject().put("match_id", "demo-chat-0").put("kind", "gif").put("gif_id", "rose"), now)
        assertNotNull(r); assertFalse(r!!.ok)
        assertEquals(2, SdDemoChat.messages("demo-chat-0", now).size)
    }

    @Test fun sendRequestStartsAChatThatShowsOnTopOfTheDemoRows() {
        val id = SdDemoChat.idFor(SdDemo.people("m")[37])
        assertFalse(SdDemoChat.isStarted(id))
        SdDemoChat.viewer = "m"
        SdDemoChat.start(id, now)
        assertTrue(SdDemoChat.isStarted(id))
        val rows = SdDemoChat.rows("m", now)
        assertEquals(6, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals("", rows[0].text)
        assertTrue(rows[0].demo && rows[0].open)
        // the chat room's question about that chat
        val chat = SdDemoChat.function("getChats", JSONObject().put("match_id", id).put("only", true), now)!!.data.optJSONArray("chats")!!.optJSONObject(0)
        assertEquals(id, chat.optString("match_id"))
        assertEquals("demo-sd-f37", chat.optJSONObject("other")?.optString("profile_id"))
        assertTrue(SdDemoChat.messages(id, now).isEmpty())
        // deleted from the chat's menu: gone from the list
        assertTrue(SdDemoChat.function("deleteChat", JSONObject().put("match_id", id), now)!!.ok)
        assertEquals(5, SdDemoChat.rows("m", now).size)
        assertFalse(SdDemoChat.isStarted(id))
    }

    @Test fun realChatsAndPeopleAreLeftToTheServer() {
        assertNull(SdDemoChat.function("getChats", JSONObject(), now))
        assertNull(SdDemoChat.function("getChats", JSONObject().put("match_id", "abc").put("only", true), now))
        assertNull(SdDemoChat.function("sendMessage", JSONObject().put("match_id", "abc").put("text", "hi"), now))
        assertNull(SdDemoChat.filter("Message", JSONObject().put("match_id", "abc"), now))
        assertNull(SdDemoChat.filter("UserProfile", JSONObject().put("id", "abc"), now))
        assertNull(SdDemoChat.function("moderateUser", JSONObject().put("profile_id", "abc").put("action", "block"), now))
        // a demo person's profile is known here, and can't be blocked or reported
        assertEquals("demo-sd-f3", SdDemoChat.filter("UserProfile", JSONObject().put("id", "demo-sd-f3"), now)!!.data.optJSONArray("items")!!.optJSONObject(0).optString("id"))
        assertFalse(SdDemoChat.function("moderateUser", JSONObject().put("profile_id", "demo-sd-f3").put("action", "block"), now)!!.ok)
    }
}
