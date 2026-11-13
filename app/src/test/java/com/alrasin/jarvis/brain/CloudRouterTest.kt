package com.alrasin.jarvis.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §K 14.2: when the cloud may be asked (D-079). "Use cloud AI" off is handled by enabled(). */
class CloudRouterTest {
    @Test fun allChatAsksFirst() {
        assertTrue(CloudBrain.mayAsk(allChat = true, forced = false, backup = false))
        assertFalse(CloudBrain.mayAsk(allChat = true, forced = false, backup = true))
    }

    @Test fun thinkHardAsksFirst() {
        assertTrue(CloudBrain.mayAsk(allChat = false, forced = true, backup = false))
        assertFalse(CloudBrain.mayAsk(allChat = false, forced = true, backup = true))
    }

    @Test fun otherwiseOnlyAsBackup() {
        assertFalse(CloudBrain.mayAsk(allChat = false, forced = false, backup = false))
        assertTrue(CloudBrain.mayAsk(allChat = false, forced = false, backup = true))
    }
}
