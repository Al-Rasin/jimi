package com.alrasin.jimi.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloseTargetTest {
    @Test fun currentAllOrNamed() {
        assertEquals(CloseTarget.CURRENT, closeTarget("app"))
        assertEquals(CloseTarget.CURRENT, closeTarget("this app"))
        assertEquals(CloseTarget.ALL, closeTarget("all apps"))
        assertEquals(CloseTarget.ALL, closeTarget("the background apps"))
        assertNull(closeTarget("youtube"))
    }
}
