package com.alrasin.jarvis.agent

import com.alrasin.jarvis.core.Json

data class ScreenElement(
    val index: Int,
    val text: String = "",
    val description: String = "",
    val resourceId: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val bounds: String = "",
    val password: Boolean = false,
    val nodePath: String = "",
    val safetyUncertain: Boolean = false,
)

data class ScreenSnapshot(val packageName: String, val elements: List<ScreenElement>) {
    fun compact(): String = buildString {
        append("App: ").append(packageName.take(160)).append('\n')
        elements.take(60).forEach { e ->
            fun quoted(value: String) = Json.write(value.replace(Regex("\\s+"), " ").take(160))
            append('#').append(e.index)
            append(" text=").append(quoted(if (e.password) "[private]" else e.text))
            append(" desc=").append(quoted(if (e.password) "[private]" else e.description))
            append(" id=").append(quoted(e.resourceId))
            append(" clickable=").append(e.clickable)
            append(" editable=").append(e.editable)
            append(" password=").append(e.password)
            append(" bounds=").append(quoted(e.bounds)).append('\n')
        }
    }

    companion object {
        fun compact(packageName: String, elements: List<ScreenElement>): ScreenSnapshot =
            ScreenSnapshot(packageName, elements.asSequence().filter {
                it.clickable || it.editable || it.text.isNotBlank() || it.description.isNotBlank()
            }.take(60).mapIndexed { i, e -> e.copy(index = i + 1) }.toList())
    }
}
