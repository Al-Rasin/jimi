package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfAccountTest {
    @Test fun selfWords() {
        listOf("me", "Myself", "my self", "my own number", "self.", "message yourself").forEach { assertTrue(it, SelfAccount.isSelf(it)) }
        listOf("Mehdi", "mother g", "my mom", "", null).forEach { assertFalse("$it", SelfAccount.isSelf(it)) }
    }

    @Test fun whatsappNumberFromPrefs() {
        val xml = """<map><string name="cc">880</string><string name="ph">1700000000</string></map>"""
        assertEquals("+8801700000000", SelfAccount.whatsappNumber(xml))
        assertNull(SelfAccount.whatsappNumber("<map></map>"))
    }

    @Test fun messengerIdFromFileNames() {
        assertEquals("100001234567", SelfAccount.messengerId(listOf("papaya.xml", "media_platform_x_100001234567.xml")))
        assertNull(SelfAccount.messengerId(listOf("papaya.xml")))
    }
}
