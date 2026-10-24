package com.alrasin.jarvis.agent.appmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks every built-in card in `src/main/assets/apps` (ROADMAP §J.3). */
class AppCardsTest {

    private val files: List<File> = run {
        val dir = listOf(File("src/main/assets/apps"), File("app/src/main/assets/apps")).first { it.isDirectory }
        dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    }
    private val cards: List<Pair<String, AppCard>> = files.map { it.name to AppCard.parse(it.readText()) }

    private val placeholders = setOf("query", "number", "text", "user", "contact", "first result")

    @Test fun allCardsParse() {
        assertTrue("expected about 21 cards, got ${cards.size}", cards.size >= 20)
        cards.forEach { (f, c) ->
            assertEquals("$f file name", "${c.pkg}.json", f)
            assertTrue("$f name", c.name.isNotBlank())
            assertTrue("$f summary", c.summary.isNotBlank())
            assertTrue("$f screens", c.screens.isNotEmpty())
            assertFalse("$f not checked on phone yet", c.verified)
        }
    }

    @Test fun packagesAndAliasesAreUnique() {
        val pkgs = cards.flatMap { it.second.packages }
        assertEquals(pkgs.size, pkgs.toSet().size)
        val names = cards.flatMap { (_, c) -> (c.aliases.map { it.lowercase() } + c.name.lowercase()).distinct() }
        assertEquals(names.groupBy { it }.filterValues { it.size > 1 }.keys.toString(), names.size, names.toSet().size)
    }

    @Test fun edgesAndExpectsPointToRealScreens() {
        cards.forEach { (f, c) ->
            val names = c.screens.map { it.name }.toSet()
            assertEquals("$f duplicate screen names", c.screens.size, names.size)
            c.screens.flatMap { it.elements }.filter { it.to.isNotBlank() }.forEach {
                assertTrue("$f: '${it.label}' -> '${it.to}'", it.to in names)
            }
            c.tasks.flatMap { it.steps }.filter { it.expect.isNotBlank() }.forEach {
                assertTrue("$f: expect '${it.expect}'", it.expect in names)
            }
            c.search?.let { s ->
                if (s.deepLink.isNotBlank()) assertTrue("$f search link", c.deepLinks.any { it.task == s.deepLink })
                else assertTrue("$f search screen", s.screen in names && s.selectors.isNotEmpty())
            }
        }
    }

    @Test fun stepsAndRolesAreKnown() {
        cards.forEach { (f, c) ->
            c.screens.flatMap { it.elements }.forEach {
                assertTrue("$f role ${it.role}", it.role in AppCard.ROLES)
                assertTrue("$f selectors for ${it.label}", AppCard.toSelectors(it.selectors).isNotEmpty())
            }
            c.tasks.forEach { t ->
                assertTrue("$f task ${t.name} has steps", t.steps.isNotEmpty())
                t.steps.forEach { assertTrue("$f do ${it.action}", it.action in AppCard.STEP_ACTIONS) }
                t.steps.filter { it.action == "link" }.forEach { s ->
                    assertTrue("$f link ${s.target}", c.deepLinks.any { it.task == s.target })
                }
            }
        }
    }

    @Test fun riskyLabelsAreHigh() {
        cards.forEach { (f, c) ->
            c.screens.flatMap { it.elements }.filter { e -> c.risky.any { it.equals(e.label, true) } }.forEach {
                assertTrue("$f ${it.label} risk ${it.risk}", it.risk == "high" || it.risk == "secret")
            }
        }
        val wa = cards.first { it.second.pkg == "com.whatsapp" }.second
        assertEquals("high", wa.screen("chat")!!.elements.first { it.label == "Send" }.risk)
        assertEquals("low", wa.screen("chats")!!.elements.first { it.label == "Calls" }.risk)
    }

    @Test fun tasksThatSendOrCallAreRisky() {
        cards.forEach { (f, c) ->
            c.tasks.filter { t -> t.steps.any { s -> c.risky.any { it.equals(s.target, true) } } }.forEach {
                assertTrue("$f task '${it.name}' taps a risky button", it.risky)
            }
        }
    }

    @Test fun readOnlyCardsNeverTapOrType() {
        val bkash = cards.first { it.second.pkg == "com.bKash.customerapp" }.second
        assertTrue(bkash.readOnly)
        assertTrue(bkash.cannot.any { "send money" in it.what })
        cards.map { it.second }.filter { it.readOnly }.forEach { c ->
            c.tasks.flatMap { it.steps }.forEach { assertTrue("${c.name} ${it.action}", it.action !in setOf("tap", "type", "enter")) }
        }
    }

    @Test fun deepLinkPlaceholdersAreWellFormed() {
        val re = Regex("\\{([^}]*)\\}")
        cards.forEach { (f, c) ->
            c.deepLinks.forEach { l ->
                assertTrue("$f ${l.task} needs uri or action", l.uri.isNotBlank() || l.action.isNotBlank())
                assertEquals("$f ${l.task} braces", l.uri.count { it == '{' }, l.uri.count { it == '}' })
                re.findAll(l.uri).forEach { assertTrue("$f {${it.groupValues[1]}}", it.groupValues[1] in placeholders) }
            }
            c.tasks.forEach { t ->
                t.steps.flatMap { re.findAll(it.target + it.text).toList() }.forEach {
                    assertTrue("$f ${t.name} {${it.groupValues[1]}}", it.groupValues[1] in placeholders)
                }
            }
        }
    }

    @Test fun abilitiesMentionTasksAndLimits() {
        val yt = cards.first { it.second.pkg == "com.google.android.youtube" }.second
        val say = yt.abilities()
        yt.tasks.forEach { assertTrue(it.name, it.name in say) }
        assertTrue("Shorts" in say)
        assertTrue("can't" in say)
    }

    @Test fun cardScreensHaveStableFingerprints() {
        cards.forEach { (f, c) ->
            val fps = c.screens.map { AppCard.fingerprint(c, it) }
            assertEquals("$f fingerprints unique", fps.size, fps.toSet().size)
            assertNotNull(c.screens.firstOrNull { it.launch })
        }
    }
}
