package com.alrasin.jimi.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantVolumeTest {
    @Test fun followsMediaShare() = assertEquals(8, AssistantVolume.level(8, 15, 15))
    @Test fun scalesToAssistantRange() = assertEquals(5, AssistantVolume.level(10, 30, 15))
    @Test fun neverZeroWhenMediaLow() = assertEquals(1, AssistantVolume.level(1, 30, 15))
    @Test fun mutedMediaGivesHalf() = assertEquals(8, AssistantVolume.level(0, 15, 15))
}
