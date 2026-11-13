package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SelfTestTest {
    private val now = LocalDateTime.of(2026, 11, 14, 10, 0)

    @Test fun everyRoutingCasePasses() {
        assertEquals(emptyList<String>(), SelfTest.routingFailures { RuleMatcher.match(it, now) })
    }

    @Test fun selfTestPhrasesRoute() {
        listOf("jimi test yourself", "run a self test", "self check", "do a health check").forEach {
            assertEquals(it, "self_test", RuleMatcher.match(it, now)?.tool)
        }
    }

    @Test fun wrongToolIsReported() {
        val failed = SelfTest.routingFailures { null }
        assertEquals(SelfTest.ROUTING.size, failed.size)
        assertTrue(failed.first().contains("went to nothing"))
    }

    @Test fun summaryIsShort() {
        assertEquals("Self test: 5 of 5 passed.", SelfTest.summary(5, 5, emptyList()))
        val s = SelfTest.summary(1, 6, listOf("a", "b", "c", "d", "e"))
        assertTrue(s.startsWith("Self test: 1 of 6 passed. Failed: a; b; c; and 2 more."))
    }
}
