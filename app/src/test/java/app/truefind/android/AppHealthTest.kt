package app.truefind.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** App Health (25 Nov): the plain parts — India's operator names and readable page names (no ids). */
class AppHealthTest {
    @Test fun operatorsByOneName() {
        assertEquals("Jio", AppHealth.operator("JIO 4G"))
        assertEquals("Jio", AppHealth.operator("Jio True5G"))
        assertEquals("Airtel", AppHealth.operator("airtel"))
        assertEquals("Airtel", AppHealth.operator("IND airtel"))
        assertEquals("Vi", AppHealth.operator("Vi India"))
        assertEquals("Vi", AppHealth.operator("Vodafone IN"))
        assertEquals("Vi", AppHealth.operator("IDEA"))
        assertEquals("BSNL", AppHealth.operator("BSNL MOBILE"))
        assertEquals("", AppHealth.operator(""))
        assertEquals("Some Carrier", AppHealth.operator(" Some Carrier "))
    }

    @Test fun pageNamesWithoutIds() {
        assertEquals("Home", AppHealth.nameOf("/"))
        assertEquals("Explore", AppHealth.nameOf("/explore"))
        assertEquals("Call Back", AppHealth.nameOf("/chats"))
        assertEquals("Chat room", AppHealth.nameOf("/chat/abc123def456ghi"))
        assertEquals("Call screen", AppHealth.nameOf("/call/0f1e2d3c4b5a69788"))
        assertEquals("Wallet (Reconnect)", AppHealth.nameOf("/wallet", "?app=secret"))
        assertEquals("/profile/:id", AppHealth.nameOf("/profile/68f1a2b3c4d5e6f7a8b9c0d1"))
        assertEquals("/settings", AppHealth.nameOf("/settings/"))
    }
}
