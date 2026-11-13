package com.alrasin.jarvis.actions

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputKeysTest {
    @Test fun navigationKeysMapToGlobalActions() {
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, InputKeys.globalAction(3))
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, InputKeys.globalAction(4))
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, InputKeys.globalAction(187))
        assertEquals(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, InputKeys.globalAction(223))
    }

    @Test fun keysWithoutActionNeedRoot() {
        assertNull(InputKeys.globalAction(24))   // volume up
        assertNull(InputKeys.globalAction(27))   // camera
        assertNull(InputKeys.globalAction(5))    // call
    }
}
