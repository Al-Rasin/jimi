package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.AppRule
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.NotificationLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClearHistoryTest {
    private val dao = FakeDao()
    private val journal = FakeJournal()
    private val history = NotificationClearHistory(dao, journal)

    private fun item(title: String = "Offer") = NotificationLog(
        timeMs = 123, pkg = "example.shop", appName = "Shop", title = title,
        text = "Complete message ".repeat(100), important = false, reason = "promo words",
        status = "kept", label = "noise",
    )

    @Test fun storesFullSnapshotAndReasonBeforeCancellation() = runBlocking {
        val source = item()
        val result = history.clear(listOf(source)) {
            assertEquals(listOf(1L), journal.ids)
            assertEquals(source.copy(id = 1, status = "clear_pending"), dao.rows.single())
        }
        assertEquals(ClearResult(1, 0), result)
        assertEquals(source.copy(id = 1, status = "cleared"), dao.rows.single())
    }

    @Test fun storesEntireBatchBeforeAnyCancellation() = runBlocking {
        val called = mutableListOf<Int>()
        history.clear(listOf(item("One"), item("Two"))) {
            assertEquals(2, dao.rows.size)
            assertEquals(listOf(1L, 2L), journal.ids)
            called += it
        }
        assertEquals(listOf(0, 1), called)
    }

    @Test fun insertFailureKeepsEveryNotificationVisible() = runBlocking {
        dao.failInsertAt = 2
        var called = false
        val result = history.clear(listOf(item(), item())) { called = true }
        assertFalse(called)
        assertEquals(2, result.failed)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(journal.ids.isEmpty())
        assertEquals("clear_failed", dao.rows.single().status)
    }

    @Test fun journalFailurePreventsCancellation() = runBlocking {
        journal.failWrite = true
        var called = false
        val result = history.clear(listOf(item())) { called = true }
        assertFalse(called)
        assertEquals(1, result.failed)
        assertEquals("clear_failed", dao.rows.single().status)
    }

    @Test fun failedCancelDoesNotClaimItWasCleared() = runBlocking {
        val result = history.clear(listOf(item())) { error("Android refused") }
        assertEquals(0, result.completed)
        assertEquals(1, result.failed)
        assertEquals("clear_failed", dao.rows.single().status)
        assertTrue(history.lastCleared().isEmpty())
        var restored = false
        history.undo { restored = true }
        assertFalse(restored)
        assertTrue(journal.ids.isEmpty())
    }

    @Test fun partialCancelFailureKeepsSuccessfulItemsUndoable() = runBlocking {
        val result = history.clear(listOf(item("One"), item("Two"))) { if (it == 0) error("refused") }
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
        val restored = mutableListOf<String>()
        history.undo { restored += it.title }
        assertEquals(listOf("Two"), restored)
        assertTrue(journal.ids.isEmpty())
    }

    @Test fun fullyFailedClearPreservesPreviousUndoBatch() = runBlocking {
        history.clear(listOf(item("Older"))) {}
        history.clear(listOf(item("Failed"))) { error("refused") }
        assertEquals(listOf(1L), journal.ids)
        val restored = mutableListOf<String>()
        history.undo { restored += it.title }
        assertEquals(listOf("Older"), restored)
        assertEquals("clear_failed", dao.rows.last().status)
    }

    @Test fun failedBatchSaveMarksPreparedRowsFailedAndPreservesPreviousUndo() = runBlocking {
        history.clear(listOf(item("Older"))) {}
        dao.failInsertAt = 3
        history.clear(listOf(item("Prepared"), item("Failed"))) { error("must not cancel") }
        assertEquals(listOf(1L), journal.ids)
        assertEquals("clear_failed", dao.rows.last().status)
        assertEquals("Older", history.lastCleared().single().title)
    }

    @Test fun pendingCancelStatusRetainsNewBatchForRecovery() = runBlocking {
        history.clear(listOf(item("Older"))) {}
        dao.failStatus = true
        history.clear(listOf(item("Pending"))) { error("refused") }
        assertEquals(listOf(2L), journal.ids)
        assertEquals("clear_pending", history.lastCleared().single().status)
    }

    @Test fun statusFailureAfterCancelRetainsUndoableCopy() = runBlocking {
        dao.failStatus = true
        val result = history.clear(listOf(item())) {}
        assertEquals(1, result.completed)
        assertTrue(result.errors.isNotEmpty())
        assertEquals("clear_pending", history.lastCleared().single().status)
        dao.failStatus = false
        assertEquals(1, history.undo {}.completed)
    }

    @Test fun undoSurvivesNewControllerInstanceAndPreservesLabel() = runBlocking {
        history.clear(listOf(item())) {}
        val restarted = NotificationClearHistory(dao, journal)
        assertEquals("promo words", restarted.lastCleared().single().reason)
        val result = restarted.undo { assertEquals(item().text, it.text) }
        assertEquals(ClearResult(1, 0), result)
        assertEquals("restored", dao.rows.single().status)
        assertEquals("noise", dao.rows.single().label)
        assertTrue(journal.ids.isEmpty())
    }

    @Test fun undoOnlyRestoresLastBatch() = runBlocking {
        history.clear(listOf(item("Older"))) {}
        history.clear(listOf(item("Latest"))) {}
        val restored = mutableListOf<String>()
        history.undo { restored += it.title }
        assertEquals(listOf("Latest"), restored)
        assertEquals("cleared", dao.rows.first().status)
    }

    @Test fun emptyClearDoesNotForgetPreviousBatch() = runBlocking {
        history.clear(listOf(item())) {}
        assertEquals(ClearResult(0, 0), history.clear(emptyList()) { error("unexpected") })
        assertEquals(listOf(1L), journal.ids)
    }

    @Test fun failedRestoreRemainsRetryableWhileSuccessfulItemsAreRemoved() = runBlocking {
        history.clear(listOf(item("One"), item("Two"))) {}
        val first = history.undo { if (it.title == "One") error("post failed") }
        assertEquals(1, first.completed)
        assertEquals(1, first.failed)
        assertEquals(listOf(1L), journal.ids)
        val restored = mutableListOf<String>()
        assertEquals(1, history.undo { restored += it.title }.completed)
        assertEquals(listOf("One"), restored)
        assertTrue(journal.ids.isEmpty())
    }

    @Test fun failedUndoJournalWriteDoesNotRepostAlreadyRestoredItem() = runBlocking {
        history.clear(listOf(item())) {}
        journal.failWrite = true
        val first = history.undo {}
        assertEquals(1, first.completed)
        assertTrue(first.errors.isNotEmpty())
        assertEquals(listOf(1L), journal.ids)
        journal.failWrite = false
        assertEquals(0, history.undo { error("must not restore twice") }.completed)
        assertTrue(journal.ids.isEmpty())
    }

    @Test fun restoreStatusFailureLeavesJournalForRetry() = runBlocking {
        history.clear(listOf(item())) {}
        dao.failStatus = true
        val result = history.undo {}
        assertEquals(1, result.completed)
        assertTrue(result.errors.isNotEmpty())
        assertEquals(listOf(1L), journal.ids)
    }

    @Test fun missingHistoryRecordIsReportedAndNotForgotten() = runBlocking {
        journal.ids = listOf(77)
        val result = history.undo { error("missing") }
        assertEquals(1, result.failed)
        assertEquals(listOf(77L), journal.ids)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test fun failedHistoryReadDoesNotRestoreAnything() = runBlocking {
        history.clear(listOf(item())) {}
        dao.failRead = true
        val result = history.undo { error("unexpected") }
        assertEquals(1, result.failed)
        assertEquals(listOf(1L), journal.ids)
    }

    @Test fun failedJournalReadDoesNotRestoreAnything() = runBlocking {
        journal.failRead = true
        val result = history.undo { error("unexpected") }
        assertEquals(1, result.failed)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test fun repeatedUndoDoesNotDuplicateRestoredNotifications() = runBlocking {
        history.clear(listOf(item())) {}
        var restores = 0
        history.undo { restores++ }
        assertEquals(ClearResult(0, 0), history.undo { restores++ })
        assertEquals(1, restores)
    }

    @Test fun cancellationPropagatesAndSavedCopyRemainsRecoverable() = runBlocking {
        var cancelled = false
        try {
            history.clear(listOf(item())) { throw CancellationException("stopped") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals("clear_pending", history.lastCleared().single().status)
        assertEquals(1, history.undo {}.completed)
    }

    @Test fun serializesClearAndUndo() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finishClear = CompletableDeferred<Unit>()
        val clearing = async {
            history.clear(listOf(item())) { entered.complete(Unit); finishClear.await() }
        }
        entered.await()
        var restored = false
        val undoing = async { history.undo { restored = true } }
        yield()
        assertFalse(restored)
        finishClear.complete(Unit)
        assertEquals(1, clearing.await().completed)
        assertEquals(1, undoing.await().completed)
        assertTrue(restored)
    }

    private class FakeJournal : ClearJournal {
        var ids = emptyList<Long>()
        var failWrite = false
        var failRead = false
        override suspend fun read(): List<Long> {
            check(!failRead)
            return ids
        }
        override suspend fun write(ids: List<Long>) {
            check(!failWrite)
            this.ids = ids.toList()
        }
    }

    private class FakeDao : NotificationDao {
        val rows = mutableListOf<NotificationLog>()
        var failInsertAt = -1
        var failStatus = false
        var failRead = false
        override suspend fun insert(n: NotificationLog): Long {
            check(rows.size + 1 != failInsertAt)
            val id = rows.size + 1L
            rows += n.copy(id = id)
            return id
        }
        override suspend fun setStatus(id: Long, status: String) {
            check(!failStatus)
            val index = rows.indexOfFirst { it.id == id }
            rows[index] = rows[index].copy(status = status)
        }
        override suspend fun setLabel(id: Long, label: String) = error("Labels must not change")
        override fun recent(limit: Int): Flow<List<NotificationLog>> = flowOf(rows.takeLast(limit))
        override suspend fun since(since: Long): List<NotificationLog> = rows.filter { it.timeMs >= since }
        override suspend fun all(): List<NotificationLog> {
            check(!failRead)
            return rows.toList()
        }
        override suspend fun setRule(r: AppRule) = Unit
        override suspend fun clearRule(pkg: String) = Unit
        override suspend fun rules(): List<AppRule> = emptyList()
        override fun rulesFlow(): Flow<List<AppRule>> = flowOf(emptyList())
    }
}
