package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommsTruthTest {
    @Test fun singlePartRequiresSuccessCallback() {
        assertEquals(true, SmsSentStatus(1).record(0, true))
        assertEquals(false, SmsSentStatus(1).record(0, false))
    }

    @Test fun allPartsMustSucceedEvenWhenCallbacksArriveOutOfOrder() {
        val status = SmsSentStatus(3)
        assertNull(status.record(2, true))
        assertNull(status.record(0, true))
        assertEquals(true, status.record(1, true))
    }

    @Test fun duplicateCallbacksDoNotConfirmMissingParts() {
        val status = SmsSentStatus(2)
        assertNull(status.record(0, true))
        assertNull(status.record(0, true))
        assertEquals(true, status.record(1, true))
    }

    @Test fun oneFailedPartCannotBecomeSuccess() {
        val status = SmsSentStatus(2)
        assertEquals(false, status.record(1, false))
        assertEquals(false, status.record(0, true))
        assertEquals(false, status.record(1, true))
    }

    @Test fun invalidPartCannotConfirmTheMessage() {
        val status = SmsSentStatus(1)
        assertNull(status.record(-1, true))
        assertNull(status.record(1, true))
        assertEquals(true, status.record(0, true))
    }
}
