package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.NotificationLog
import java.util.Locale

object NotificationHistorySearch {
    fun search(items: List<NotificationLog>, query: String): List<NotificationLog> {
        val words = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.filter { item ->
            val haystack = listOf(item.pkg, item.appName, item.title, item.text, item.reason, item.status, item.label.orEmpty())
                .joinToString("\n").lowercase(Locale.ROOT)
            words.all { it in haystack }
        }.sortedWith(compareByDescending<NotificationLog> { it.timeMs }.thenByDescending { it.id })
    }
}
