package com.alrasin.jarvis.debug

import org.junit.Assert.*
import org.junit.Test

class SmokeRequestTest {
    @Test fun defaultsToReadOnlyAndTrimsText() {
        val r = SmokeRequest.parse(SmokeRequest.ACTION, mapOf("id" to "run-1", "text" to " time "))
        assertEquals("time", r.text)
        assertTrue(r.readOnly)
    }
    @Test fun acceptsExplicitMutableSuite() {
        assertFalse(SmokeRequest.parse(SmokeRequest.ACTION,
            mapOf("id" to "run-2", "text" to "volume 50", "read_only" to false)).readOnly)
    }
    @Test fun rejectsMalformedPayloads() {
        val valid = mapOf<String, Any?>("id" to "run-1", "text" to "time")
        val invalid = listOf(valid - "id", valid - "text", valid + ("id" to "bad\nid"),
            valid + ("text" to " "), valid + ("text" to "a".repeat(241)),
            valid + ("read_only" to "false"), valid + ("text" to 12))
        invalid.forEach { assertTrue(runCatching { SmokeRequest.parse(SmokeRequest.ACTION, it) }.isFailure) }
        assertTrue(runCatching { SmokeRequest.parse("wrong", valid) }.isFailure)
    }
}
