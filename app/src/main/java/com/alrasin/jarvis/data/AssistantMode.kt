package com.alrasin.jarvis.data

enum class AssistantMode(val label: String) {
    REPLACE_GEMINI("Replace Gemini"),
    BESIDE_GEMINI("Work beside Gemini");

    companion object {
        fun fromStored(value: String?): AssistantMode = entries.firstOrNull { it.name == value } ?: BESIDE_GEMINI
    }
}
