package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Map planner (ROADMAP §J.4): target match, ambiguity, path, risk, where-am-I, abilities. */
class PlannerTest {

    private val whatsApp: AppCard = run {
        val dir = listOf(File("src/main/assets/apps"), File("app/src/main/assets/apps")).first { it.isDirectory }
        AppCard.parse(File(dir, "com.whatsapp.json").readText())
    }

    /** Same shape as [AppCards] puts into the DB: one id per screen, cleaned labels, edges from "to". */
    private fun graphOf(card: AppCard): MapPlan.Graph {
        val screens = card.screens.mapIndexed { i, s ->
            AppScreen(id = i + 1L, pkg = card.pkg, fingerprint = AppCard.fingerprint(card, s), name = s.name, lastSeen = 0)
        }
        val ids = screens.associate { it.name to it.id }
        var nextElement = 100L
        val elements = HashMap<Long, List<AppElement>>()
        val edges = ArrayList<AppEdge>()
        card.screens.forEach { s ->
            val from = ids.getValue(s.name)
            elements[from] = s.elements.map { e ->
                val el = AppElement(nextElement++, from, Json.write(e.selectors), e.role, ScreenStructure.clean(e.label), e.risk)
                ids[e.to]?.let { to -> edges += AppEdge(edges.size + 1L, from, el.id, to, 0, 0, 0) }
                el
            }
        }
        return MapPlan.Graph(screens, elements, edges)
    }

    private val wa = graphOf(whatsApp)
    private fun id(name: String) = wa.screens.first { it.name == name }.id

    @Test fun privacySettingsGoesChatsMenuSettingsPrivacy() {
        val plan = MapPlan.plan(wa, "privacy settings", setOf(id("chats"))) as MapPlan.Plan.Go
        assertEquals(listOf("menu", "settings", "privacy"), plan.path.map { e -> wa.screen(e.toScreen)!!.name })
        assertNull(plan.finalTap)
    }

    @Test fun alreadyThereIsEmptyPath() {
        val plan = MapPlan.plan(wa, "privacy", setOf(id("privacy"))) as MapPlan.Plan.Go
        assertTrue(plan.path.isEmpty())
    }

    @Test fun buttonWithoutDestinationIsFinalTap() {
        val plan = MapPlan.plan(wa, "blocked contacts", setOf(id("chats"))) as MapPlan.Plan.Go
        assertEquals("privacy", wa.screen(plan.path.last().toScreen)!!.name)
        assertEquals("blocked contacts", plan.finalTap!!.label)
    }

    @Test fun sameLabelOnTwoScreensAsksWhichOne() {
        // "New group" is on the menu and on the new chat screen.
        assertTrue(MapPlan.findTarget(wa, "new group") is MapPlan.Target.Ambiguous)
    }

    @Test fun unknownTargetIsNotFound() {
        assertEquals(MapPlan.Plan.NotFound, MapPlan.plan(wa, "payments wallet", setOf(id("chats"))))
        assertEquals(MapPlan.Plan.NotFound, MapPlan.plan(wa, "", setOf(id("chats"))))
    }

    @Test fun pathPrefersEdgesThatNeverFailed() {
        val g = MapPlan.Graph(
            screens = (1L..3L).map { AppScreen(it, "p", "c::", "s$it", 0) },
            elements = mapOf(1L to listOf(el(11, 1, "a"), el(12, 1, "b")), 2L to listOf(el(21, 2, "goal"))),
            edges = listOf(AppEdge(1, 1, 11, 3, 5, 0, failedCount = 2), AppEdge(2, 1, 12, 3, 1, 0, failedCount = 0)),
        )
        assertEquals(12L, MapPlan.shortestPath(g, setOf(1L), 3L)!!.single().element)
        assertNull(MapPlan.shortestPath(g, setOf(1L), 2L))
    }

    @Test fun fewerTapsBeatFewerFailures() {
        val g = MapPlan.Graph(
            screens = (1L..3L).map { AppScreen(it, "p", "c::", "s$it", 0) },
            elements = emptyMap(),
            edges = listOf(
                AppEdge(1, 1, 11, 3, 0, 0, failedCount = 3),
                AppEdge(2, 1, 12, 2, 0, 0, 0),
                AppEdge(3, 2, 21, 3, 0, 0, 0),
            ),
        )
        assertEquals(listOf(11L), MapPlan.shortestPath(g, setOf(1L), 3L)!!.map { it.element })
    }

    @Test fun riskyTapIsRefused() {
        val g = MapPlan.Graph(
            screens = listOf(AppScreen(1, "p", "c::", "home", 0), AppScreen(2, "p", "c::", "pay", 0)),
            elements = mapOf(1L to listOf(el(11, 1, "send money", risk = "high")), 2L to listOf(el(21, 2, "confirm", risk = "secret"))),
            edges = listOf(AppEdge(1, 1, 11, 2, 0, 0, 0)),
        )
        assertEquals(MapPlan.Plan.Risky("send money"), MapPlan.plan(g, "pay", setOf(1L)))
        assertEquals(MapPlan.Plan.Risky("send money"), MapPlan.plan(g, "confirm", setOf(1L)))
        val waSend = MapPlan.plan(wa, "send", setOf(id("chat")))
        assertEquals(MapPlan.Plan.Risky("send"), waSend)
    }

    @Test fun cardScreenMatchesLiveScreenByLabels() {
        val known = wa.screens.first { it.name == "privacy" }.fingerprint
        val live = AppFingerprint.compute(
            "com.whatsapp.settings.SettingsPrivacy",
            listOf("button:navigate up", "item:last seen and online", "item:profile photo", "item:read receipts", "text:about"),
        )
        assertTrue(MapPlan.matchScore(known, live) > 0)
        val chatsLive = AppFingerprint.compute("com.whatsapp.HomeActivity", listOf("tab:chats", "tab:calls"))
        assertEquals(0.0, MapPlan.matchScore(known, chatsLive), 0.0)
    }

    @Test fun learnedScreenMatchesByFingerprint() {
        val fp = AppFingerprint.compute("a.Main", listOf("button:a", "button:b", "button:c", "button:d", "button:e"))
        val g = MapPlan.Graph(listOf(AppScreen(7, "a", fp, "Main", 0)), emptyMap(), emptyList())
        val live = AppFingerprint.compute("a.Main", listOf("button:a", "button:b", "button:c", "button:d", "button:e", "text:x"))
        assertEquals(listOf(7L), MapPlan.whereAmI(g, live))
        assertTrue(MapPlan.whereAmI(g, AppFingerprint.compute("a.Other", listOf("button:z"))).isEmpty())
    }

    @Test fun abilitiesFromCardThenMapThenUnknown() {
        assertEquals(whatsApp.abilities(), MapPlan.abilities("WhatsApp", whatsApp, wa))
        val learned = MapPlan.Graph(listOf(AppScreen(1, "x", "c::", "Inbox", 0)), emptyMap(), emptyList())
        assertTrue("Inbox" in MapPlan.abilities("Mail", null, learned))
        assertTrue("don't know" in MapPlan.abilities("Mail", null, MapPlan.Graph(emptyList(), emptyMap(), emptyList())))
    }

    @Test fun selectorsComeFromStoredJson() {
        val privacy = wa.elements.getValue(id("settings")).first { it.label == "privacy" }
        assertTrue(MapPlan.selectors(privacy).isNotEmpty())
        assertEquals(2, MapPlan.selectors(el(1, 1, "x").copy(selectorsJson = "bad")).size)
    }

    private fun el(id: Long, screen: Long, label: String, risk: String = "low") =
        AppElement(id, screen, "[]", "button", label, risk)
}
