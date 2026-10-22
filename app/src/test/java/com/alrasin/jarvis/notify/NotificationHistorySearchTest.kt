package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.NotificationLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHistorySearchTest {
    private val cleared = item(1, title = "Summer sale", text = "Discount today", status = "cleared", reason = "promo words")
    private val kept = item(2, title = "Meeting", text = "Meet at noon", reason = "personal message metadata", label = "important")

    @Test fun clearedNotificationsRemainSearchable() {
        assertEquals(listOf(cleared), search("summer"))
        assertEquals(listOf(cleared), search("discount"))
    }

    @Test fun savedClearReasonIsSearchable() {
        assertEquals(listOf(cleared), search("promo words"))
    }

    @Test fun reviewLabelIsSearchable() {
        assertEquals(listOf(kept), search("important"))
    }

    @Test fun packageAndApplicationNameAreSearchable() {
        val app = item(3, pkg = "com.example.shop", appName = "Shopping App")
        assertEquals(listOf(app), NotificationHistorySearch.search(listOf(app), "com.example.shop"))
        assertEquals(listOf(app), NotificationHistorySearch.search(listOf(app), "shopping app"))
    }

    @Test fun statusIsSearchableIncludingPendingClear() {
        val pending = item(3, status = "clear_pending")
        assertEquals(listOf(cleared), search("cleared"))
        assertEquals(listOf(pending), NotificationHistorySearch.search(listOf(cleared, pending), "clear_pending"))
    }

    @Test fun searchIsCaseInsensitive() {
        assertEquals(listOf(cleared), search("SUMMER DISCOUNT PROMO"))
        assertEquals(listOf(kept), search("ImPoRtAnT"))
    }

    @Test fun everyWordMustMatchButMayMatchDifferentFields() {
        assertEquals(listOf(cleared), search("summer discount promo cleared"))
        assertTrue(search("summer meeting").isEmpty())
    }

    @Test fun tokenOrderDoesNotMatter() {
        assertEquals(search("summer discount"), search("discount summer"))
    }

    @Test fun repeatedSearchWordsDoNotDuplicateResults() {
        assertEquals(listOf(cleared), search("summer summer summer"))
    }

    @Test fun whitespaceIsIgnoredAroundAndBetweenWords() {
        assertEquals(listOf(cleared), search(" \n SUMMER\t  discount \r\n"))
    }

    @Test fun blankQueryNeverReturnsAllPrivateHistory() {
        for (query in listOf("", " ", "\t\n\r")) assertTrue(search(query).isEmpty())
    }

    @Test fun emptyHistoryAndUnmatchedQueryReturnEmpty() {
        assertTrue(NotificationHistorySearch.search(emptyList(), "sale").isEmpty())
        assertTrue(search("nothing like this").isEmpty())
    }

    @Test fun missingReviewLabelDoesNotMatchNullString() {
        assertTrue(NotificationHistorySearch.search(listOf(cleared), "null").isEmpty())
    }

    @Test fun nonLatinTextAndEmojiRemainSearchable() {
        val bangla = item(3, title = "নতুন বার্তা", text = "আজ দেখা হবে 🔔")
        val chinese = item(4, title = "新消息", text = "明天见")
        assertEquals(listOf(bangla), NotificationHistorySearch.search(listOf(bangla, chinese), "বার্তা দেখা"))
        assertEquals(listOf(bangla), NotificationHistorySearch.search(listOf(bangla, chinese), "🔔"))
        assertEquals(listOf(chinese), NotificationHistorySearch.search(listOf(bangla, chinese), "新消息"))
    }

    @Test fun accentedLettersAreCaseInsensitive() {
        val notification = item(3, title = "CAFÉ reçu")
        assertEquals(listOf(notification), NotificationHistorySearch.search(listOf(notification), "café REÇU"))
    }

    @Test fun punctuationIsLiteralAndCannotActAsRegex() {
        val notification = item(3, title = "Coupon [SAVE]", text = "Literal .* pattern")
        assertEquals(listOf(notification), NotificationHistorySearch.search(listOf(notification), "[save]"))
        assertEquals(listOf(notification), NotificationHistorySearch.search(listOf(cleared, notification), ".*"))
        assertTrue(NotificationHistorySearch.search(listOf(notification), "[missing]").isEmpty())
    }

    @Test fun resultsSortNewestFirstThenDescendingId() {
        val oldest = item(8, time = 10)
        val newest = item(1, time = 30)
        val middleLowId = item(2, time = 20)
        val middleHighId = item(9, time = 20)
        assertEquals(
            listOf(newest, middleHighId, middleLowId, oldest),
            NotificationHistorySearch.search(listOf(middleLowId, oldest, newest, middleHighId), "notice"),
        )
    }

    @Test fun searchDoesNotChangeSavedContentOrInputOrder() {
        val items = mutableListOf(cleared, kept)
        val before = items.toList()
        NotificationHistorySearch.search(items, "example")
        assertEquals(before, items)
        assertEquals("cleared", items.first().status)
        assertEquals("promo words", items.first().reason)
    }

    private fun search(query: String) = NotificationHistorySearch.search(listOf(cleared, kept), query)

    private fun item(
        id: Long,
        time: Long = id,
        pkg: String = "com.example",
        appName: String = "Example",
        title: String = "Notice",
        text: String = "",
        status: String = "kept",
        reason: String = "default keep",
        label: String? = null,
    ) = NotificationLog(id, time, pkg, appName, title, text, important = status == "kept", reason, status, label)
}
