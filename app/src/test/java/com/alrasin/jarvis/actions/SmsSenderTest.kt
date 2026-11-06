package com.alrasin.jarvis.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSenderTest {
    @Test fun serviceAndPromoSendersAreBroadcast() {
        listOf("16216", "GP", "AD-BKASH", "", "  ", null).forEach { assertTrue("$it", SmsSender.isBroadcast(it)) }
    }

    @Test fun peopleAreNotBroadcast() {
        listOf("+8801712345678", "01712345678", " 01712 345678 ").forEach { assertFalse(it, SmsSender.isBroadcast(it)) }
    }
}
