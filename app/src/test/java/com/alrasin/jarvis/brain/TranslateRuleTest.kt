package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TranslateRuleTest {
    private val now = LocalDateTime.of(2026, 11, 12, 10, 0)
    private fun m(text: String) = RuleMatcher.match(text, now)

    private fun check(said: String, text: String, to: String) {
        val c = m(said)
        assertEquals(said, "translate", c?.tool)
        assertEquals(said, text, c?.arg("text"))
        assertEquals(said, to, c?.arg("to"))
    }

    @Test fun toBangla() {
        check("translate good morning to bangla", "good morning", "bn")
        check("Translate \"how are you\" into Bengali.", "how are you", "bn")
        check("can you translate thank you in bangla", "thank you", "bn")
        check("how do you say i love you in bangla", "i love you", "bn")
        check("what is water in bengali", "water", "bn")
        check("translate see you tomorrow", "see you tomorrow", "bn")
    }

    @Test fun toEnglish() {
        check("what does ধন্যবাদ mean in english", "ধন্যবাদ", "en")
        check("translate আমি ভালো আছি", "আমি ভালো আছি", "en")
        check("translate ভালোবাসা to english", "ভালোবাসা", "en")
        check("what's the meaning of শুভ সকাল", "শুভ সকাল", "en")
        check("what is the english for আকাশ", "আকাশ", "en")
    }

    @Test fun notTranslate() {
        assertNotEquals("translate", m("what is the meaning of life")?.tool)
        assertNotEquals("translate", m("what is the weather in dhaka")?.tool)
        assertNotEquals("translate", m("translate this")?.tool)
        assertNotEquals("translate", m("open google translate")?.tool)
    }

    @Test fun helpers() {
        assertTrue(TranslateText.hasBengali("আমি"))
        assertFalse(TranslateText.hasBengali("hello"))
        assertEquals("bn", TranslateText.target("hello", null))
        assertEquals("en", TranslateText.target("আমি", null))
        assertEquals("en", TranslateText.target("hello", "english"))
        assertEquals("en", TranslateText.source("bn"))
        assertEquals("Bangla", TranslateText.name("bn"))
        assertNull(TranslateText.lang("french"))
        assertEquals("hi there", TranslateText.clean(" \"hi there\" please"))
    }
}
