package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.ContextMode
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingBlockTest {
    private val car = ContextMode(car = true, headphones = false, bedtime = false)
    private val none = ContextMode(car = false, headphones = false, bedtime = false)

    @Test fun blocksScreenTasksWhileDriving() {
        assertTrue(drivingBlock(ToolCall("open_app", mapOf("app" to "youtube")), car))
        assertTrue(drivingBlock(ToolCall("tap", emptyMap()), car))
    }

    @Test fun allowsVoiceTasksWhileDriving() {
        assertFalse(drivingBlock(ToolCall("send_whatsapp", mapOf("contact" to "mom")), car))
        assertFalse(drivingBlock(ToolCall("call", mapOf("contact" to "mom", "app" to "whatsapp")), car))
        assertFalse(drivingBlock(ToolCall("set_alarm", emptyMap()), car))
    }

    @Test fun noBlockWhenNotDriving() {
        assertFalse(drivingBlock(ToolCall("open_app", mapOf("app" to "youtube")), none))
    }
}
