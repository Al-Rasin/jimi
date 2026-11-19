package com.alrasin.jimi.agent.appmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AppHistoryTest {
    @Before fun clear() = AppHistory.reset()

    @Test fun inAppGoesToTheOneBefore() {
        AppHistory.seen("youtube")
        AppHistory.seen("whatsapp")
        assertEquals("youtube", AppHistory.lastApp("whatsapp"))
    }

    @Test fun onHomeScreenGoesToNewest() {
        AppHistory.seen("youtube")
        AppHistory.seen("whatsapp")
        assertEquals("whatsapp", AppHistory.lastApp("launcher"))
        assertEquals("whatsapp", AppHistory.lastApp(null))
    }

    @Test fun sameAppTwiceKeepsHistory() {
        AppHistory.seen("youtube")
        AppHistory.seen("whatsapp")
        AppHistory.seen("whatsapp")
        assertEquals("youtube", AppHistory.lastApp("whatsapp"))
    }

    @Test fun nothingSeen() = assertNull(AppHistory.lastApp("whatsapp"))
}
