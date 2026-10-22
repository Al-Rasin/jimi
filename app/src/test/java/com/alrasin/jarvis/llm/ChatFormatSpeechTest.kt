package com.alrasin.jarvis.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormatSpeechTest {
    @Test fun titlesStayWithNames() {
        listOf("Dr.", "Mr.", "Mrs.", "Ms.", "Prof.").forEach { title ->
            assertEquals(
                listOf("Ask $title Rahman tomorrow.") to "Next",
                ChatFormat.sentences("Ask $title Rahman tomorrow. Next"),
            )
        }
    }

    @Test fun abbreviationsStayInsideSentence() {
        listOf("e.g.", "i.e.", "etc.", "vs.").forEach { abbreviation ->
            assertEquals(
                listOf("Use $abbreviation this example.") to "Next",
                ChatFormat.sentences("Use $abbreviation this example. Next"),
            )
        }
    }

    @Test fun decimalsStayInsideSentence() = assertEquals(
        listOf("It costs 3.5 dollars.") to "Next",
        ChatFormat.sentences("It costs 3.5 dollars. Next"),
    )

    @Test fun dottedTimesAndInitialsStayInsideSentence() = assertEquals(
        listOf("Meet A. Rahman at 10 p.m. tomorrow.") to "Next",
        ChatFormat.sentences("Meet A. Rahman at 10 p.m. tomorrow. Next"),
    )

    @Test fun unfinishedTitleIsBufferedEvenWithExtraWhitespace() = assertEquals(
        emptyList<String>() to "Ask Dr.  ",
        ChatFormat.sentences("Ask Dr.  "),
    )

    @Test fun finalNewlineFlushesAbbreviation() = assertEquals(
        listOf("Bring pens, etc.") to "",
        ChatFormat.sentences("Bring pens, etc.\n"),
    )

    @Test fun newlineInsideSentenceDoesNotSplitTitle() = assertEquals(
        listOf("Ask Dr.\nRahman.") to "",
        ChatFormat.sentences("Ask Dr.\nRahman.\n"),
    )

    @Test fun everyTokenBoundaryKeepsAbbreviationsAndDecimalsIntact() {
        val text = "Ask Dr. Rahman for 3.5 kg, e.g. rice, etc. tomorrow. Done!\n"
        for (cut in 0..text.length) {
            val first = ChatFormat.sentences(text.take(cut))
            val second = ChatFormat.sentences(first.second + text.drop(cut))
            assertEquals("cut=$cut", listOf(
                "Ask Dr. Rahman for 3.5 kg, e.g. rice, etc. tomorrow.", "Done!",
            ), first.first + second.first)
            assertEquals("cut=$cut", "", second.second)
        }
    }

    @Test fun characterAtATimeStreamKeepsDecimalIntact() {
        var buffer = ""
        val spoken = mutableListOf<String>()
        "Ask Dr. Rahman about 3.5 percent. Ready?\n".forEach { character ->
            val (sentences, rest) = ChatFormat.sentences(buffer + character)
            spoken += sentences
            buffer = rest
        }
        assertEquals(listOf("Ask Dr. Rahman about 3.5 percent.", "Ready?"), spoken)
        assertEquals("", buffer)
    }
}
