package com.alrasin.jimi.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkReplyTest {
    @Test fun replies() {
        assertEquals("You're on Wi-Fi Home. Your IP address is 192.168.1.5.", networkReply("wifi", "Home", "192.168.1.5"))
        assertEquals("You're on mobile data.", networkReply("mobile", null, null))
        assertEquals("You're not connected to the internet.", networkReply(null, null, null))
    }

    @Test fun ssidCleanup() {
        assertEquals("Home", cleanSsid("\"Home\""))
        assertNull(cleanSsid("<unknown ssid>"))
        assertNull(cleanSsid(null))
    }
}
