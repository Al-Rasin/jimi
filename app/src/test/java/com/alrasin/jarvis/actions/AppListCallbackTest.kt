package com.alrasin.jarvis.actions

import android.content.ContextWrapper
import com.alrasin.jarvis.brain.RuleMatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListCallbackTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @After fun restoreAppNameMatcher() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    @Test fun unavailablePackageManagerAllowsAppRoutingWithoutThrowing() {
        AppList(ContextWrapper(null))
        assertTrue(RuleMatcher.appNameMatches?.invoke("youtube") == true)
        assertEquals("open_app", RuleMatcher.match("open youtube")?.tool)
    }
}
