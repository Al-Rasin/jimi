package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberWordsTest {
    @Test fun emptyAndRepeatedSpaces() {
        assertEquals("", NumberWords.normalize(""))
        assertEquals("", NumberWords.normalize("   "))
        assertEquals("set timer for 5 minutes", NumberWords.normalize("  set  timer for five   minutes  "))
    }

    @Test fun unitsAndTeensInSpokenDurations() {
        val words = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        words.forEachIndexed { value, word ->
            assertEquals(word, "$value seconds", NumberWords.normalize("$word seconds"))
        }
    }

    @Test fun tensAndSpeechSpellingVariant() {
        val cases = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40,
            "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
        cases.forEach { (word, value) ->
            assertEquals(word, "$value percent", NumberWords.normalize("$word percent"))
            assertEquals(word, "${value + 9} percent", NumberWords.normalize("$word nine percent"))
        }
    }

    @Test fun percentagesPreserveTheSettingAndUnit() {
        assertEquals("set brightness to 45 percent", NumberWords.normalize("set brightness to forty five percent"))
        assertEquals("volume 100 percent", NumberWords.normalize("volume a hundred percent"))
        assertEquals("volume 0 percent", NumberWords.normalize("volume zero percent"))
    }

    @Test fun hundredsWithAndWithoutAnd() {
        val cases = mapOf("one hundred" to "100", "a hundred and five" to "105",
            "two hundred fifteen" to "215", "three hundred and twenty six" to "326",
            "nine hundred ninety nine" to "999")
        cases.forEach { (words, digits) -> assertEquals(words, digits, NumberWords.normalize(words)) }
    }

    @Test fun adjacentTimeNumbersStaySeparate() {
        assertEquals("alarm at 7 30 pm", NumberWords.normalize("alarm at seven thirty pm"))
        assertEquals("alarm at 7 0 5 am", NumberWords.normalize("alarm at seven oh five am"))
        assertEquals("alarm at 7 0 5 am", NumberWords.normalize("alarm at 7 oh five am"))
    }

    @Test fun ohRemainsAWordOutsideClockNumbers() {
        for (text in listOf("oh no", "oh five", "seven oh no", "seven oh")) {
            assertEquals(text.replace("seven", "7").replace("five", "5"), NumberWords.normalize(text))
        }
    }

    @Test fun separateQuantitiesDoNotMergeAcrossUnits() {
        assertEquals("1 hour and 25 minutes", NumberWords.normalize("one hour and twenty five minutes"))
        assertEquals("2 apples and 3 oranges", NumberWords.normalize("two apples and three oranges"))
    }

    @Test fun existingDigitsAndUnrelatedWordsRemainIntact() {
        assertEquals("set alarm at 07:05", NumberWords.normalize("set alarm at 07:05"))
        assertEquals("someone has twentyfive stones", NumberWords.normalize("someone has twentyfive stones"))
        assertEquals("a minute", NumberWords.normalize("a minute"))
    }
}
