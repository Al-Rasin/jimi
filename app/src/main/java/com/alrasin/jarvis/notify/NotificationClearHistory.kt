package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.NotificationLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ClearJournal {
    suspend fun read(): List<Long>
    suspend fun write(ids: List<Long>)
}

data class ClearResult(val completed: Int, val failed: Int, val errors: List<String> = emptyList())

class NotificationClearHistory(private val dao: NotificationDao, private val journal: ClearJournal) {
    private val mutex = Mutex()

    suspend fun clear(items: List<NotificationLog>, cancel: suspend (index: Int) -> Unit): ClearResult = mutex.withLock {
        if (items.isEmpty()) return@withLock ClearResult(0, 0)
        val ids = mutableListOf<Long>()
        var previousIds: List<Long>? = null
        try {
            previousIds = journal.read()
            for (item in items) ids += dao.insert(item.copy(id = 0, status = "clear_pending"))
            journal.write(ids.toList())
        } catch (e: Exception) {
            e.rethrowCancellation()
            for (id in ids) {
                try {
                    dao.setStatus(id, "clear_failed")
                } catch (cleanup: Exception) {
                    cleanup.rethrowCancellation()
                }
            }
            previousIds?.let {
                try {
                    journal.write(it)
                } catch (cleanup: Exception) {
                    cleanup.rethrowCancellation()
                }
            }
            return@withLock ClearResult(0, items.size, listOf("Could not save clear history; nothing was cleared."))
        }
        var completed = 0
        var pending = false
        val errors = mutableListOf<String>()
        for ((index, id) in ids.withIndex()) {
            val status = try {
                cancel(index)
                completed++
                "cleared"
            } catch (e: Exception) {
                e.rethrowCancellation()
                errors += "Could not clear notification ${index + 1}."
                "clear_failed"
            }
            try {
                dao.setStatus(id, status)
            } catch (e: Exception) {
                e.rethrowCancellation()
                pending = true
                errors += "Could not update history for notification ${index + 1}; its saved copy is still available."
            }
        }
        if (completed == 0 && !pending) {
            try {
                journal.write(previousIds.orEmpty())
            } catch (e: Exception) {
                e.rethrowCancellation()
                errors += "Could not preserve the previous undo history."
            }
        }
        ClearResult(completed, items.size - completed, errors)
    }

    suspend fun undo(restore: suspend (NotificationLog) -> Unit): ClearResult = mutex.withLock {
        val ids: MutableList<Long>
        val records: Map<Long, NotificationLog>
        try {
            ids = journal.read().distinct().toMutableList()
            if (ids.isEmpty()) return@withLock ClearResult(0, 0)
            records = dao.all().associateBy { it.id }
        } catch (e: Exception) {
            e.rethrowCancellation()
            return@withLock ClearResult(0, 1, listOf("Could not read clear history; try undo again."))
        }
        var completed = 0
        var failed = 0
        val errors = mutableListOf<String>()
        for (id in ids.toList()) {
            val item = records[id]
            if (item == null) {
                failed++
                errors += "A saved notification is missing from history."
                continue
            }
            if (item.status != "restored" && item.status != "clear_failed") {
                try {
                    // Pending may mean Android cleared it just before the process stopped.
                    restore(item)
                    completed++
                } catch (e: Exception) {
                    e.rethrowCancellation()
                    failed++
                    errors += "Could not restore a notification; try undo again."
                    continue
                }
                try {
                    dao.setStatus(id, "restored")
                } catch (e: Exception) {
                    e.rethrowCancellation()
                    errors += "Could not save restore status; try undo again."
                    continue
                }
            }
            try {
                journal.write(ids.filterNot { it == id })
                ids.remove(id)
            } catch (e: Exception) {
                e.rethrowCancellation()
                errors += "Could not update undo history; try undo again."
            }
        }
        ClearResult(completed, failed, errors)
    }

    suspend fun lastCleared(): List<NotificationLog> = mutex.withLock {
        val ids = journal.read().toSet()
        dao.all().filter { it.id in ids && it.status in setOf("clear_pending", "cleared") }
    }

    private fun Exception.rethrowCancellation() {
        if (this is CancellationException) throw this
    }
}
