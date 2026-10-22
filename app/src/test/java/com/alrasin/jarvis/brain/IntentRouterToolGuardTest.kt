package com.alrasin.jarvis.brain

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class IntentRouterToolGuardTest(private val text: String, private val tool: String) {
    @Test fun installedAppGuardPreservesOtherTools() {
        val previous = RuleMatcher.appNameMatches
        try {
            RuleMatcher.appNameMatches = { false }
            val rule = RuleMatcher.match(text)
            assertEquals(text, tool, rule?.tool)
            val decision = IntentRouter.classify(text, rule)
            assertEquals(text, IntentRouter.Route.TOOL, decision.route)
            assertTrue(text, decision.isConfident)
        } finally {
            RuleMatcher.appNameMatches = previous
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf("start a timer for 5 minutes", "set_timer"),
            arrayOf("show my notifications", "read_notifications"),
            arrayOf("open youtube and search lofi", "play_media"),
            arrayOf("run my morning routine", "run_routine"),
        )
    }
}
