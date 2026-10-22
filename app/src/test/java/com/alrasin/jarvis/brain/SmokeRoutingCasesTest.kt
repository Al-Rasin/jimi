package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SmokeRoutingCasesTest {
    @Test fun savedSmokeCasesKeepTheirExpectedRulesAndRoutes() {
        val file = listOf(File("tools/smoke_routing_cases.txt"), File("../tools/smoke_routing_cases.txt"))
            .first { it.exists() }
        val previous = RuleMatcher.appNameMatches
        try {
            RuleMatcher.appNameMatches = { false }
            val cases = file.readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            assertTrue("Smoke cases must not be empty", cases.isNotEmpty())
            for (line in cases) {
                val case = Json.parse(line) as Map<*, *>
                fun fixture(value: String) = value.replace(Regex("\\{\\{[^}]+}}"), "Example Contact")
                val text = fixture(case["text"] as String)
                val name = case["name"] as String
                val rule = RuleMatcher.match(text)
                val decision = IntentRouter.classify(text, rule)
                val expectedRoute = if (case["route"] == "chat") IntentRouter.Route.CHAT else IntentRouter.Route.TOOL
                assertEquals(name, expectedRoute, decision.route)
                assertTrue("$name: $decision", decision.isConfident)
                if (expectedRoute == IntentRouter.Route.TOOL) {
                    assertEquals(name, case["tool"], rule?.tool)
                    val expectedArgs = (case["requested_args"] ?: case["args"]) as Map<*, *>
                    for ((key, value) in expectedArgs) {
                        assertEquals("$name: $key", fixture(value as String).lowercase(), rule?.arg(key as String))
                    }
                }
            }
        } finally {
            RuleMatcher.appNameMatches = previous
        }
    }
}
