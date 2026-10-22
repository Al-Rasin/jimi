package com.alrasin.jarvis.notify

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Only row IDs live here; the full notification and reason remain in Room. */
class NotificationClearJournal(context: Context) : ClearJournal {
    private val file = AtomicFile(File(context.noBackupFilesDir, "notification_last_clear"))

    override suspend fun read(): List<Long> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        return file.openRead().bufferedReader().use { reader ->
            reader.readLines().filter { it.isNotBlank() }.map { line ->
                line.toLong().also { require(it > 0) }
            }
        }
    }

    override suspend fun write(ids: List<Long>) {
        val stream = file.startWrite()
        try {
            stream.write(ids.joinToString("\n").toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
            check(read() == ids) { "Clear journal could not be saved" }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}
