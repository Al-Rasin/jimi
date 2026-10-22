package com.alrasin.jarvis.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenChatAnswerTest {
    @Test fun generalAnswersSurviveThinkingAndMarkdownCleanup() {
        listOf(
            "Gravity is the force that pulls objects with mass toward each other.",
            "Photosynthesis lets plants use sunlight to turn water and carbon dioxide into food.",
            "The capital of France is Paris.",
            "Why did the bicycle fall over? It was two tired!",
        ).forEach { answer ->
            assertEquals(answer, ChatFormat.clean("<think>\n\n</think>\n\n**$answer**"))
        }
    }

    @Test fun missingPersonalMemoryReplyIsPreserved() {
        val answer = "I don't remember your wife's name."
        assertEquals(answer, ChatFormat.clean("<think>No stored name.</think>\n$answer"))
    }

    @Test fun generalAndPersonalQuestionsBothStartOutsideThinking() {
        listOf("What is gravity?", "What is my wife's name?").forEach { question ->
            val prompt = ChatFormat.qwen("Answer briefly.", emptyList(), question)
            assertTrue(prompt.contains("<|im_start|>user\n$question /no_think<|im_end|>"))
            assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        }
    }
}
