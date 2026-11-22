package app.truefind.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** pinned chats first, then newest first; demo chats the same as the web's sdDemoChats */
class ChatListTest {
    private fun c(id: String, time: String, pinned: Boolean = false) =
        SdChatsPage.Chat(id, id, "", "", "", "", "", false, false, "", "", time, 0, pinned = pinned)

    @Test fun pinnedFirstThenNewest() {
        val out = SdChatList.sorted(listOf(
            c("old", "2026-11-20T10:00:00Z"), c("new", "2026-11-22T10:00:00Z"),
            c("pinOld", "2026-11-01T10:00:00Z", true), c("pinNew", "2026-11-21T10:00:00Z", true)))
        assertEquals(listOf("pinNew", "pinOld", "new", "old"), out.map { it.id })
    }
    @Test fun demoChatsMatchTheWeb() {
        val now = 1_763_800_000_000L
        val d = SdChatList.demoChats("m", now)
        assertEquals(5, d.size)
        assertEquals(listOf("demo-chat-0", "demo-chat-1", "demo-chat-2", "demo-chat-3", "demo-chat-4"), d.map { it.id })
        assertEquals(listOf("Hey! How are you? 😊", "Nice to meet you!", "Let's talk soon 💕", "Hi 👋", "Good morning!"), d.map { it.text })
        assertEquals(listOf(2, 0, 0, 0, 0), d.map { it.unread })
        assertEquals(listOf(true, false, true, false, true), d.map { it.mine })
        assertTrue(d.all { it.demo && it.uid.isEmpty() && it.pid.startsWith("demo-sd-f") })
        // times: 5, 28, 51, 74, 97 minutes ago
        assertEquals(listOf(5L, 28L, 51L, 74L, 97L), d.map { (now - SdRules.ms(it.time)) / 60_000L })
    }
}
