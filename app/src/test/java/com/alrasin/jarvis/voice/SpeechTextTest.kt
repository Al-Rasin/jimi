package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SpeechTextTest {
    private fun words(text: String) = text.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun spoken(input: String) = words(SpeechText.normalize(input))

    @Test fun emptyTextIsSilent() {
        assertEquals("", SpeechText.normalize(""))
        assertEquals("", SpeechText.normalize(" \t\n "))
        assertTrue(SpeechText.chunks("").isEmpty())
    }

    @Test fun ordinaryReplyKeepsMeaning() {
        assertEquals("your flashlight is on", spoken("Your flashlight is on."))
    }

    @Test fun smallNumbersBecomeWords() {
        assertEquals("zero one twelve nineteen twenty forty two", spoken("0 1 12 19 20 42"))
    }

    @Test fun groupedThousandsBecomeWords() {
        assertEquals("one thousand two hundred thirty four", spoken("1,234").replace(" and ", " "))
    }

    @Test fun decimalKeepsEveryFractionalDigit() {
        assertEquals("three point zero five", spoken("3.05"))
    }

    @Test fun negativeNumberKeepsSign() {
        assertEquals("minus five", spoken("-5"))
    }

    @Test fun percentIsPronounceable() {
        assertEquals("battery is twenty five percent", spoken("Battery is 25%."))
    }

    @Test fun fractionalPercentIsPronounceable() {
        assertEquals("three point five percent", spoken("3.5%"))
    }

    @Test fun takaSymbolsAndCodesAgree() {
        val outputs = listOf("Tk 50", "BDT 50", "৳50").map(::spoken)
        assertEquals(listOf("fifty taka", "fifty taka", "fifty taka"), outputs)
    }

    @Test fun dollarsHaveCurrencyName() {
        assertEquals("five dollars", spoken("\$5"))
    }

    @Test fun fractionalMoneyKeepsValue() {
        val text = spoken("\$12.50")
        assertTrue(text, text.contains("twelve"))
        assertTrue(text, text.contains("dollar"))
        assertTrue(text, text.contains("fifty") || text.contains("five zero"))
    }

    @Test fun eveningTimeKeepsMinutesAndMeridiem() {
        assertEquals("ten thirty p m", spoken("10:30 PM"))
    }

    @Test fun singleDigitMinutesAreNotLost() {
        val text = spoken("7:05 AM")
        assertTrue(text, text.startsWith("seven"))
        assertTrue(text, text.contains("five"))
        assertTrue(text, text.endsWith("a m"))
    }

    @Test fun isoDateHasSpokenMonthDayAndYear() {
        val text = spoken("2026-10-22")
        assertTrue(text, text.contains("october"))
        assertTrue(text, text.contains("twenty second"))
        assertTrue(text, text.contains("twenty six"))
    }

    @Test fun slashDateHasSpokenMonth() {
        assertTrue(spoken("22/10/2026").contains("october"))
    }

    @Test fun localPhoneNumberIsReadDigitByDigit() {
        assertEquals("zero one two three four five six seven eight nine zero", spoken("01234567890"))
    }

    @Test fun internationalPhoneNumberKeepsPlus() {
        assertEquals("plus eight eight zero one two three four five six seven eight nine zero",
            spoken("+8801234567890"))
    }

    @Test fun formattedPhoneNumberKeepsAllDigits() {
        assertEquals("zero one two three four five six seven eight nine zero", spoken("01234-567890"))
    }

    @Test fun distanceUnitsAreExpanded() {
        assertEquals("five kilometers", spoken("5 km"))
    }

    @Test fun weightUnitsAreExpanded() {
        assertEquals("two kilograms", spoken("2 kg"))
    }

    @Test fun ordinalsUseWords() {
        assertEquals("first second third fourth eleventh twelfth twenty first",
            spoken("1st 2nd 3rd 4th 11th 12th 21st"))
    }

    @Test fun titleDoesNotLeaveAbbreviationPeriod() {
        assertEquals("doctor rahman", spoken("Dr. Rahman"))
        assertFalse(SpeechText.normalize("Dr. Rahman").contains("Dr."))
    }

    @Test fun commonAbbreviationsAreExpanded() {
        assertEquals("for example tea and so on", spoken("e.g. tea, etc."))
    }

    @Test fun rawUrlIsShortenedWithoutReadingQuery() {
        val text = SpeechText.normalize("Visit https://example.com/path?q=42&token=synthetic.")
        assertTrue(text, words(text).contains("link") || words(text).contains("example"))
        assertFalse(text, text.contains("https"))
        assertFalse(text, text.contains("synthetic"))
        assertFalse(text, text.contains("?"))
    }

    @Test fun emailDoesNotReadMailboxOrSymbols() {
        val text = SpeechText.normalize("Email sample.person@example.com.")
        assertFalse(text, text.contains("@"))
        assertFalse(text, text.contains("sample.person"))
        assertTrue(text, words(text).contains("link") || words(text).contains("example"))
    }

    @Test fun markdownLinkKeepsUsefulLabel() {
        val text = SpeechText.normalize("Open [the guide](https://example.com/guide).")
        assertTrue(text, words(text).contains("the guide"))
        assertFalse(text, text.contains("https"))
        assertFalse(text, text.contains("["))
    }

    @Test fun emphasisAndHeadingMarkersAreRemoved() {
        assertEquals("ready now", spoken("### **Ready** _now_"))
        assertFalse(SpeechText.normalize("**Ready**").contains("*"))
    }

    @Test fun bracketsAreRemovedWithoutLosingTheirWords() {
        val text = SpeechText.normalize("Ready (now) [please] {continue}.")
        assertEquals("ready now please continue", words(text))
        assertFalse(text.any { it in "()[]{}" })
    }

    @Test fun emojiIsRemoved() {
        assertEquals("done", spoken("Done 😀 ✅ 👨‍👩‍👧‍👦"))
        assertFalse(SpeechText.normalize("Done 😀").contains("😀"))
    }

    @Test fun banglaScriptDoesNotReachEnglishVoice() {
        val text = SpeechText.normalize("Hello বন্ধু, ready.")
        assertFalse(text.any { it in '\u0980'..'\u09ff' })
        assertTrue(text, text.contains("Hello", ignoreCase = true))
        assertTrue(text, text.contains("ready", ignoreCase = true))
    }

    @Test fun uppercaseOrdinaryWordsAreNotSpelledOut() {
        assertEquals("please wait", spoken("PLEASE WAIT"))
        assertFalse(SpeechText.normalize("PLEASE WAIT").contains("PLEASE"))
    }

    @Test fun knownInitialismsAreSpelledOut() {
        assertEquals("g p s u s b c p u", spoken("GPS USB CPU"))
    }

    @Test fun banglaDigitsBecomeEnglishWords() {
        assertEquals("twenty five", spoken("২৫"))
    }

    @Test fun bossIsNotShoutedOrIsolatedByComma() {
        val text = SpeechText.normalize("Done, BOSS!")
        assertEquals("done boss", words(text))
        assertFalse(text.contains("BOSS"))
        assertFalse(text.contains(", boss", ignoreCase = true))
    }

    @Test fun whitespaceIsCollapsed() {
        assertEquals("Hello world.", SpeechText.normalize("  Hello\n\tworld.  "))
    }

    @Test fun normalizationIsStableWhenAppliedTwice() {
        val inputs = listOf("Dr. Rahman paid Tk 50 at 10:30 PM.", "Done, BOSS!", "Battery 25%.",
            "Visit https://example.com.", "Call 01234567890.", "The 21st day.")
        for (input in inputs) {
            val normalized = SpeechText.normalize(input)
            assertEquals(input, normalized, SpeechText.normalize(normalized))
        }
    }

    @Test fun shortReplyStaysInOneChunk() {
        assertEquals(1, SpeechText.chunks("Your flashlight is on.").size)
    }

    @Test fun chunksPreserveAllWordsInOrder() {
        val input = "Please check the bedroom light and close the window before leaving the house, " +
            "then take your bag and keys with you because the weather may change later today."
        val chunks = SpeechText.chunks(input)
        assertEquals(spoken(input), words(chunks.joinToString(" ")))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.trim().split(Regex("\\s+")).size <= 20 })
    }

    @Test fun longSentenceWithoutCommasStillHasBoundedChunks() {
        val input = List(65) { "word" }.joinToString(" ")
        val chunks = SpeechText.chunks(input)
        assertEquals(words(input), words(chunks.joinToString(" ")))
        assertTrue(chunks.all { it.trim().split(Regex("\\s+")).size <= 20 })
    }

    @Test fun commaIsPreferredNearNaturalChunkLength() {
        val first = "Please check the bedroom light and close the window before leaving the house,"
        val input = "$first then take your bag and keys with you because the weather may change later today."
        assertEquals(words(first), words(SpeechText.chunks(input).first()))
    }

    @Test fun chunkLimitCanBeReduced() {
        val chunks = SpeechText.chunks("one two three four five six seven eight nine ten", maxWords = 5)
        assertTrue(chunks.all { it.split(Regex("\\s+")).size <= 5 })
        assertEquals("one two three four five six seven eight nine ten", words(chunks.joinToString(" ")))
    }

    @Test fun chunksKeepDoctorAndDecimalTogether() {
        val chunks = SpeechText.chunks("Dr. Rahman walked 3.5 km. He is home.")
        assertTrue(chunks.first(), words(chunks.first()).contains("doctor rahman"))
        assertTrue(chunks.first(), words(chunks.first()).contains("three point five"))
        assertEquals(spoken("Dr. Rahman walked 3.5 km. He is home."), words(chunks.joinToString(" ")))
    }

    @Test fun timeAtSentenceEndKeepsSentenceBoundary() {
        val normalized = SpeechText.normalize("Meet at 10:30 PM. Bring your keys.")
        assertTrue(normalized, normalized.contains("p m.", ignoreCase = true))
        assertEquals(2, SpeechText.chunks("Meet at 10:30 PM. Bring your keys.").size)
    }

    @Test fun decimalDollarsMentionEachCurrencyUnitOnce() {
        assertEquals("twelve dollars and fifty cents", spoken("\$12.50"))
    }

    @Test fun decimalTakaMentionsEachCurrencyUnitOnce() {
        for (input in listOf("Tk 12.50", "BDT 12.50", "৳12.50")) {
            assertEquals(input, "twelve taka and fifty poisha", spoken(input))
        }
    }

    @Test fun punctuationOnlyDoesNotGenerateSpeech() {
        for (input in listOf("...", "!?", "---", "() [] {}", "** __ ##")) {
            assertEquals(input, "", SpeechText.normalize(input))
            assertTrue(input, SpeechText.chunks(input).isEmpty())
        }
    }

    @Test fun emojiOnlyDoesNotGenerateSpeech() {
        assertEquals("", SpeechText.normalize("😀 ✅ 👨‍👩‍👧‍👦"))
        assertTrue(SpeechText.chunks("😀 ✅").isEmpty())
    }

    @Test fun banglaOnlyDoesNotGenerateSpeech() {
        assertEquals("", SpeechText.normalize("বন্ধু ভালো"))
        assertTrue(SpeechText.chunks("বন্ধু ভালো").isEmpty())
    }

    @Test fun spelledInitialismsStayStableOnSecondNormalization() {
        val normalized = SpeechText.normalize("GPS USB CPU")
        assertEquals("g p s u s b c p u", words(normalized))
        assertEquals(normalized, SpeechText.normalize(normalized))
    }

    @Test fun spacedInternationalPhoneIsOneDigitSequence() {
        assertEquals("plus eight eight zero one two three four five six seven eight nine zero",
            spoken("+880 1234 567890"))
    }

    @Test fun parenthesizedPhoneIsOneDigitSequence() {
        assertEquals("two zero two five five five zero one zero zero", spoken("(202) 555-0100"))
    }

    @Test fun banglaLettersAreFoundButDigitsAndTakaAreNot() {
        assertTrue(SpeechText.hasBangla("কেমন আছো"))
        assertFalse(SpeechText.hasBangla("৳500"))
        assertFalse(SpeechText.hasBangla("১২৩"))
        assertFalse(SpeechText.hasBangla("Hello there"))
    }

    @Test fun mixedTextSplitsIntoEnglishAndBanglaParts() {
        assertEquals(
            listOf(SpeechText.Part("Boss, Rahim says:", false), SpeechText.Part("কেমন আছো?", true)),
            SpeechText.parts("Boss, Rahim says: কেমন আছো?"),
        )
        val mid = SpeechText.parts("আমি office যাচ্ছি।")
        assertEquals(listOf(true, false, true), mid.map { it.bangla })
        assertEquals("office", mid[1].text)
    }
}
