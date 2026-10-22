package com.alrasin.jarvis.notify

import android.content.ContextWrapper
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.NotificationLog
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTrustToolsTest {
    @Test fun trustToolsAreRegisteredExactlyOnce() {
        val names = notificationTools(dao()).map { it.name }
        for (name in listOf("undo_notification_clear", "explain_notification_clear", "search_notification_history")) {
            assertEquals(name, 1, names.count { it == name })
        }
    }

    @Test fun privateTrustToolsRequireVoiceIdentity() {
        for (name in listOf("undo_notification_clear", "explain_notification_clear", "search_notification_history", "read_notifications", "notification_summary")) {
            assertEquals(name, Risk.VOICE_ID, notificationTools(dao()).single { it.name == name }.risk)
        }
        assertEquals(Risk.CONFIRM, notificationTools(dao()).single { it.name == "clear_notifications" }.risk)
    }

    @Test fun searchSchemaRequiresOnlyQueryString() {
        val args = notificationTools(dao()).single { it.name == "search_notification_history" }.args
        assertEquals(listOf("query"), args.map { it.name })
        assertTrue(args.single().required)
        assertNull(args.single().choices)
    }

    @Test fun undoAndExplanationHaveNoArguments() {
        for (name in listOf("undo_notification_clear", "explain_notification_clear")) {
            assertTrue(notificationTools(dao()).single { it.name == name }.args.isEmpty())
        }
    }

    @Test fun undoAndClearFailHonestlyWithoutListener() = runBlocking {
        assertNull(JarvisNotificationListener.instance)
        for (name in listOf("undo_notification_clear", "clear_notifications")) {
            val result = run(name)
            assertFalse(result.ok)
            assertTrue(result.say.contains("access is off"))
        }
    }

    @Test fun explanationWithoutSavedHistoryIsHonest() = runBlocking {
        val result = run("explain_notification_clear")
        assertTrue(result.ok)
        assertTrue(result.say.contains("no saved clear"))
    }

    @Test fun explanationUsesSavedReasonWithoutListener() = runBlocking {
        val result = run("explain_notification_clear", items = listOf(item(1, "cleared")))
        assertTrue(result.ok)
        assertTrue(result.say.contains("promo words"))
        assertTrue(result.data.contains("cleared"))
        assertTrue(result.data.contains("Sale"))
    }

    @Test fun explanationStillAvailableAfterUndoRestoredHistory() = runBlocking {
        val result = run("explain_notification_clear", items = listOf(item(1, "restored")))
        assertTrue(result.ok)
        assertTrue(result.say.contains("promo words"))
        assertTrue(result.data.contains("restored"))
    }

    @Test fun explanationAfterUndoUsesClearInsertionOrderNotOriginalPostingTime() = runBlocking {
        val earlierClear = item(1, "restored").copy(timeMs = 100, reason = "earlier reason")
        val latestClear = item(2, "restored").copy(timeMs = 10, reason = "latest reason")
        val result = run("explain_notification_clear", items = listOf(latestClear, earlierClear))
        assertTrue(result.say.contains("latest reason"))
        assertFalse(result.say.contains("earlier reason"))
    }

    @Test fun explanationIncludesPendingClearButIgnoresKeptAndFailedRecords() = runBlocking {
        val pending = item(2, "clear_pending")
        val result = run("explain_notification_clear", items = listOf(item(1, "kept"), pending, item(3, "clear_failed")))
        assertTrue(result.data.contains("clear_pending"))
        assertFalse(result.data.contains("clear_failed"))
    }

    @Test fun searchWorksWithoutListenerAndIncludesFullReasonAndLabel() = runBlocking {
        val result = run("search_notification_history", "sale", listOf(item(1, "cleared", label = "important")))
        assertTrue(result.ok)
        assertTrue(result.say.contains("1 saved notifications match"))
        assertTrue(result.data.contains("[1]"))
        assertTrue(result.data.contains("Saved body"))
        assertTrue(result.data.contains("cleared; promo words; review: important"))
    }

    @Test fun missingAndBlankQueriesFailWithoutDumpingHistory() = runBlocking {
        for (query in listOf(null, "", " \t\n")) {
            val result = run("search_notification_history", query, listOf(item(1, "cleared")))
            assertFalse(result.ok)
            assertTrue(result.data.isEmpty())
        }
    }

    @Test fun unmatchedSearchReturnsNoPrivateContent() = runBlocking {
        val result = run("search_notification_history", "absent", listOf(item(1, "cleared")))
        assertTrue(result.ok)
        assertEquals("No saved notifications match.", result.say)
        assertEquals("", result.data)
    }

    @Test fun searchSortsNewestFirstAndLabelsUnreviewedRecords() = runBlocking {
        val result = run("search_notification_history", "sale", listOf(item(1, "cleared"), item(2, "restored")))
        assertTrue(result.data.startsWith("[2]"))
        assertTrue(result.data.contains("review: unreviewed"))
    }

    private suspend fun run(name: String, query: String? = null, items: List<NotificationLog> = emptyList()) =
        notificationTools(dao(items)).single { it.name == name }.run(
            ToolCall(name, query?.let { mapOf("query" to it) }.orEmpty()),
            ToolContext(ContextWrapper(null), "", locked = false),
        )

    private fun item(id: Long, status: String, label: String? = null) = NotificationLog(
        id = id, timeMs = id, pkg = "com.example", appName = "Example", title = "Sale", text = "Saved body",
        important = false, reason = "promo words", status = status, label = label,
    )

    private fun dao(items: List<NotificationLog> = emptyList()): NotificationDao = Proxy.newProxyInstance(
        NotificationDao::class.java.classLoader, arrayOf(NotificationDao::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "all" -> items
            else -> error("Unexpected DAO call: ${method.name}")
        }
    } as NotificationDao
}
