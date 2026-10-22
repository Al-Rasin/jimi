package com.alrasin.jarvis.debug

import org.junit.Assert.*
import org.junit.Test

class SmokeDryRunRequestTest {
    private val valid = mapOf<String, Any?>("id" to "dry-1", "text" to "call mom")

    @Test fun explicitDryRunRetainsReadOnlyDefault() {
        val request = SmokeRequest.parse(SmokeRequest.ACTION, valid + ("dry_run" to true))
        assertTrue(request.dryRun)
        assertTrue(request.readOnly)
    }

    @Test fun absentDryRunPreservesExistingSuiteBehavior() {
        assertFalse(SmokeRequest.parse(SmokeRequest.ACTION, valid).dryRun)
        assertFalse(SmokeRequest.parse(SmokeRequest.ACTION, valid + ("dry_run" to false)).dryRun)
    }

    @Test fun rejectsNonBooleanDryRun() {
        for (value in listOf("true", 1, "false")) {
            assertTrue(runCatching {
                SmokeRequest.parse(SmokeRequest.ACTION, valid + ("dry_run" to value))
            }.isFailure)
        }
    }
}
