package com.alrasin.jarvis.agent

import com.alrasin.jarvis.agent.UiQuery.Candidate
import com.alrasin.jarvis.agent.UiQuery.Selector
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers UiQuery's pure, node-free logic (matching + popup-dismiss safety) with hand-built
 * fixtures, since this project has no Robolectric/MockK/Mockito and AccessibilityNodeInfo can't
 * be constructed in plain JUnit. tap/type/enter/scrollUntilFound/dismissPopups's node-walking,
 * gestureTap (needs AccessibilityService.dispatchGesture) and pasteViaClipboard (needs a real
 * ClipboardManager) are instrumented-only and skipped here on purpose.
 */
class UiQueryTest {

    @Test fun matchesByResourceId() {
        val candidates = listOf(
            Candidate(resourceId = "com.app:id/search", position = 0),
            Candidate(resourceId = "com.app:id/other", position = 1),
        )
        val hit = UiQuery.matchCandidate(listOf(Selector.Id("com.app:id/search")), candidates)
        assertEquals(0, hit?.position)
    }

    @Test fun matchesByContentDescription() {
        val candidates = listOf(Candidate(desc = "Search", position = 0))
        assertEquals(0, UiQuery.matchCandidate(listOf(Selector.Desc("Search")), candidates)?.position)
        assertNull(UiQuery.matchCandidate(listOf(Selector.Desc("Settings")), candidates))
    }

    @Test fun matchesByExactAndFuzzyText() {
        val exact = listOf(Candidate(text = "Search", position = 0))
        assertEquals(0, UiQuery.matchCandidate(listOf(Selector.Text("Search")), exact)?.position)

        // "Serch" is a near-miss, not an exact or alias match: falls back to Fuzzy.best.
        val fuzzy = listOf(Candidate(text = "Serch", position = 0))
        assertEquals(0, UiQuery.matchCandidate(listOf(Selector.Text("Search")), fuzzy)?.position)

        val tooFar = listOf(Candidate(text = "Zzzzz", position = 0))
        assertNull(UiQuery.matchCandidate(listOf(Selector.Text("Search")), tooFar))
    }

    @Test fun matchesByHint() {
        val candidates = listOf(Candidate(hint = "Search", position = 0))
        assertEquals(0, UiQuery.matchCandidate(listOf(Selector.Hint("Search")), candidates)?.position)
    }

    @Test fun matchesByClassAndPosition() {
        val candidates = listOf(
            Candidate(className = "android.widget.Button", position = 0),
            Candidate(className = "android.widget.Button", position = 1),
        )
        val hit = UiQuery.matchCandidate(listOf(Selector.ClassAt("android.widget.Button", 1)), candidates)
        assertEquals(1, hit?.position)
    }

    @Test fun selectorOrderPicksEarliestMatchingKind() {
        // Id selector first in the list: should win even though a later Desc selector also matches.
        val candidates = listOf(Candidate(resourceId = "id/x", desc = "Search", position = 0))
        val hit = UiQuery.matchCandidate(listOf(Selector.Id("id/x"), Selector.Desc("Search")), candidates)
        assertEquals("id/x", hit?.resourceId)

        // No id match: falls through to the desc selector.
        val noId = listOf(Candidate(desc = "Search", position = 0))
        val fallback = UiQuery.matchCandidate(listOf(Selector.Id("id/missing"), Selector.Desc("Search")), noId)
        assertEquals("Search", fallback?.desc)
    }

    @Test fun matchesBanglaAliasesForCommonLabels() {
        val search = listOf(Candidate(desc = "খুঁজুন", position = 0))
        assertEquals(0, UiQuery.matchCandidate(listOf(Selector.Desc("Search")), search)?.position)

        val next = listOf(Candidate(text = "পরবর্তী", position = 0))
        assertEquals(0, UiQuery.matchCandidate(listOf(Selector.Text("Next")), next)?.position)
    }

    @Test fun forLabelBuildsIdDescTextHintCascade() {
        val selectors = UiQuery.forLabel("Search", resourceId = "id/search", className = "android.widget.Button", position = 2)
        assertEquals(
            listOf(
                Selector.Id("id/search"),
                Selector.Desc("Search"),
                Selector.Text("Search"),
                Selector.Hint("Search"),
                Selector.ClassAt("android.widget.Button", 2),
            ),
            selectors,
        )
    }

    @Test fun safePopupDismissAllowsKnownHarmlessLabels() {
        listOf("Not now", "Skip", "Later", "No thanks", "Close", "এখন না", "এড়িয়ে যান").forEach {
            assertTrue("expected '$it' to be a safe dismiss", UiQuery.isSafePopupDismiss(it, ""))
        }
    }

    @Test fun safePopupDismissNeverAllowsRiskyWords() {
        listOf("Allow", "Pay", "Send", "Delete", "Confirm", "Submit", "Remove", "Buy", "Install").forEach {
            assertFalse("expected '$it' to be rejected", UiQuery.isSafePopupDismiss(it, ""))
        }
    }

    @Test fun safePopupDismissRejectsRiskyWordEvenInsideASofterLabel() {
        // Guards against a dialog titled e.g. "Allow access" slipping past because of a substring match.
        assertFalse(UiQuery.isSafePopupDismiss("Allow access", ""))
        assertFalse(UiQuery.isSafePopupDismiss("", "Allow access"))
    }

    @Test fun safePopupDismissRejectsBlankLabels() {
        assertFalse(UiQuery.isSafePopupDismiss("", ""))
    }

    @Test fun popupDismissListNeverOverlapsRiskyWords() {
        // Mirrors UiQuery's own init{} invariant check, kept here too for visibility in test reports.
        listOf("Not now", "Skip", "Later", "No thanks", "Close", "এখন না", "এড়িয়ে যান", "পরে", "না ধন্যবাদ", "বন্ধ করুন")
            .forEach { assertTrue("'$it' should be a safe dismiss", UiQuery.isSafePopupDismiss(it, "")) }
    }
}
