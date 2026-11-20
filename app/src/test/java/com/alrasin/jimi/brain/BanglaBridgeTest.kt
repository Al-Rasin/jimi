package com.alrasin.jimi.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BanglaBridgeTest {
    @Test fun yesAnswersWorkWithConfirm() {
        for (said in listOf("হ্যাঁ", "হ্যাঁ।", "জি", "ঠিক আছে", "হ্যাঁ জিমি", "  আচ্ছা! ")) {
            val en = BanglaBridge.quickEnglish(said)
            assertEquals(said, "yes", en)
            assertTrue(said, Persona.isYes(en!!))
        }
    }

    @Test fun noAnswersWorkWithConfirm() {
        for (said in listOf("না", "না।", "না না", "থাক", "বাতিল করো", "দরকার নেই")) {
            val en = BanglaBridge.quickEnglish(said)
            assertEquals(said, "no", en)
            assertTrue(said, Persona.isNo(en!!))
        }
    }

    @Test fun stopWords() {
        assertEquals("stop", BanglaBridge.quickEnglish("থামো"))
        assertEquals("stop", BanglaBridge.quickEnglish("চুপ করো।"))
    }

    @Test fun longerCommandsGoToTranslation() {
        assertNull(BanglaBridge.quickEnglish("রহিমকে কল করো"))
        assertNull(BanglaBridge.quickEnglish("না, রহিমকে না, করিমকে কল করো"))
        assertNull(BanglaBridge.quickEnglish("yes"))
        assertNull(BanglaBridge.quickEnglish(""))
    }
}
