package com.alrasin.jarvis.ui

import com.alrasin.jarvis.data.AssistantMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPolicyTest {
    private fun permissions(sdk: Int) = SetupPolicy.runtimePermissions(sdk).map { it.first.substringAfterLast('.') }

    @Test fun permissionsRespectAndroidVersions() {
        assertFalse("POST_NOTIFICATIONS" in permissions(32))
        assertTrue("POST_NOTIFICATIONS" in permissions(33))
        assertFalse("BLUETOOTH_CONNECT" in permissions(30))
        assertTrue("BLUETOOTH_CONNECT" in permissions(31))
        assertTrue("READ_EXTERNAL_STORAGE" in permissions(32))
        assertFalse("READ_EXTERNAL_STORAGE" in permissions(33))
    }

    @Test fun locationIncludesCoarseForAndroid12AndCameraForTorch() {
        assertTrue("ACCESS_COARSE_LOCATION" in permissions(31))
        assertTrue("ACCESS_FINE_LOCATION" in permissions(31))
        assertTrue("CAMERA" in permissions(30))
    }

    @Test fun voiceTestNeedsMicAndAllRequiredModels() {
        val ready = mapOf("wake_features" to true, "wake_jimi" to true, "stt" to true)
        assertTrue(SetupPolicy.wakeReady(true, ready))
        assertFalse(SetupPolicy.wakeReady(false, ready))
        ready.keys.forEach { missing -> assertFalse(SetupPolicy.wakeReady(true, ready - missing)) }
        assertFalse(SetupPolicy.wakeReady(true, ready + ("stt" to false)))
    }

    @Test fun historicalWakeDoesNotPassNewTest() {
        assertFalse(SetupPolicy.newWakeDetected(null, listOf(200L)))
        assertFalse(SetupPolicy.newWakeDetected(100L, listOf(99L)))
        assertFalse(SetupPolicy.newWakeDetected(100L, emptyList()))
        assertTrue(SetupPolicy.newWakeDetected(100L, listOf(101L, 99L)))
    }

    @Test fun assistantChoiceDefaultsToPreservingSystemAssistant() {
        assertEquals(AssistantMode.BESIDE_GEMINI, AssistantMode.fromStored(null))
        assertEquals(AssistantMode.BESIDE_GEMINI, AssistantMode.fromStored("invalid"))
        AssistantMode.entries.forEach { assertEquals(it, AssistantMode.fromStored(it.name)) }
    }
}
