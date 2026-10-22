package com.alrasin.jarvis.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SttCorrectorTest {
    private val toolWords = listOf("flashlight", "volume", "brightness", "bluetooth", "wifi", "do not disturb")
    private val corrector = SttCorrector(
        contacts = listOf("Mohammed", "Mohammed Rahman", "Rafiq", "Alice", "Home", "Ann"),
        apps = listOf("Spotify", "Telegram", "WhatsApp", "Google Maps"),
        toolWords = toolWords,
    )

    private fun corrected(input: String, expected: String) = assertEquals(expected, corrector.correct(input))
    private fun unchanged(input: String) = corrected(input, input)

    @Test fun correctsRealFlushlightMistake() = corrected("turn on flushlight", "turn on flashlight")
    @Test fun correctsSpacedFlushLight() = corrected("turn on flush light", "turn on flashlight")
    @Test fun correctsFleshlight() = corrected("switch off fleshlight", "switch off flashlight")
    @Test fun correctsFlashLite() = corrected("enable flash lite", "enable flashlight")
    @Test fun correctsPluralFlashlights() = corrected("toggle flashlights", "toggle flashlight")
    @Test fun correctsBareFlushlight() = corrected("flushlight", "flashlight")
    @Test fun preservesRealLostTurnPrefix() = corrected("down on flushlight", "down on flashlight")
    @Test fun preservesRealDoYouMeanPrefix() = corrected("do you mean on flushlight", "do you mean on flashlight")
    @Test fun correctsOffAndToolTogether() = corrected("turn of flushlight", "turn off flashlight")
    @Test fun correctsOffWhenTargetAlreadyCorrect() = corrected("switch of flashlight", "switch off flashlight")
    @Test fun doesNotCorrectOfWithoutKnownTarget() = unchanged("turn of page")
    @Test fun correctsRealSoundUp() = corrected("sound up", "volume up")
    @Test fun correctsRealSoundDown() = corrected("sound down", "volume down")
    @Test fun correctsRealSoundOf() = corrected("sound of", "volume off")
    @Test fun correctsRealVolumeOf() = corrected("volume of", "volume off")
    @Test fun correctsSoundMaximum() = corrected("sound maximum", "volume maximum")
    @Test fun leavesSoundOfMusicAlone() = unchanged("sound of music")
    @Test fun matchesConsonantsAndEditDistance() = corrected("turn on bluetoth", "turn on bluetooth")
    @Test fun correctsToolBeforeDirection() = corrected("turn the volome up", "turn the volume up")
    @Test fun correctsTargetWithNumericArgument() = corrected("set brighness to 25 percent", "set brightness to 25 percent")
    @Test fun preservesAllDigits() = corrected("change brighness at 007", "change brightness at 007")
    @Test fun correctsIncreaseTarget() = corrected("increase brighness", "increase brightness")
    @Test fun correctsToolWithSuffix() = corrected("flaslight off", "flashlight off")
    @Test fun correctsContactPhonetics() = corrected("call Muhammed", "call Mohammed")
    @Test fun correctsMultiwordContact() = corrected("call Muhammed Rahman", "call Mohammed Rahman")
    @Test fun correctsContactBeforeCallSuffix() = corrected("phone Muhammed on speaker", "phone Mohammed on speaker")
    @Test fun correctsContactBeforeWhatsappSuffix() = corrected("call Muhammed on whatsapp", "call Mohammed on whatsapp")
    @Test fun correctsContactQSound() = corrected("ring Rafik", "ring Rafiq")
    @Test fun correctsContactVowelSound() = corrected("dial Alyce", "dial Alice")
    @Test fun correctsAppPhonetics() = corrected("open Spotifi", "open Spotify")
    @Test fun correctsAppDeletion() = corrected("launch Telegrm", "launch Telegram")
    @Test fun correctsAppBeforeSuffix() = corrected("open the Spotifi app", "open the Spotify app")
    @Test fun correctsCloseApp() = corrected("close Telegrm", "close Telegram")
    @Test fun correctsLongAppWithTwoVowelMistakes() = corrected("open Tilagram", "open Telegram")
    @Test fun rejectsTwoConsonantMistakes() = unchanged("open Telxgrxm")
    @Test fun acceptsOneLongHighSimilarityConsonantMistake() = corrected("open Telegrap", "open Telegram")
    @Test fun preservesOutsideCaseSpacingAndPunctuation() = corrected(
        "  Could you please CALL Muhammed on speaker please!  ",
        "  Could you please CALL Mohammed on speaker please!  ",
    )
    @Test fun preservesOutsideToolPunctuation() = corrected("Please turn on the Flushlight!", "Please turn on the flashlight!")
    @Test fun preservesExactKnownContactCase() = unchanged("CALL moHAMmed.")
    @Test fun preservesExactKnownShortName() = unchanged("call Ann")
    @Test fun preservesExactKnownAppCase() = unchanged("open sPOTify")
    @Test fun preservesExactKnownCompactPhrase() = unchanged("open Whats App")
    @Test fun preservesExactKnownToolPhrase() = unchanged("turn on do not disturb")
    @Test fun preservesExactVolumeCase() = unchanged("VOLUME UP!")
    @Test fun preservesBlankInput() = unchanged("   ")
    @Test fun preservesEmptyInput() = unchanged("")
    @Test fun preservesLargeInput() = unchanged("call " + "a".repeat(600))
    @Test fun preservesUnknownNames() = unchanged("call Alexandra")
    @Test fun preservesUnknownApps() = unchanged("open Calculator")
    @Test fun preservesUnrelatedSpeech() = unchanged("my flushlight is broken")
    @Test fun preservesSearchQuery() = unchanged("search for flushlight near Muhammed")
    @Test fun preservesNoteText() = unchanged("note down turn on flushlight and call Muhammed")
    @Test fun preservesReminderText() = unchanged("remind me to call Muhammed at five")
    @Test fun preservesTypedContent() = unchanged("type turn on flushlight")
    @Test fun preservesSongQuery() = unchanged("play sound of silence")
    @Test fun preservesSmsBody() = corrected(
        "text Muhammed saying turn on flushlight and don't call Rafik",
        "text Mohammed saying turn on flushlight and don't call Rafik",
    )
    @Test fun preservesSmsBodyAfterColon() = corrected(
        "send a message to Muhammed: Sound of music, PIN 1234!",
        "send a message to Mohammed: Sound of music, PIN 1234!",
    )
    @Test fun preservesSmsBodyAfterThatSays() = corrected(
        "send sms to Muhammed that says call Rafik",
        "send sms to Mohammed that says call Rafik",
    )
    @Test fun leavesUndelimitedSmsUntouched() = unchanged("text Muhammed turn on flushlight")
    @Test fun preservesNegatedCommand() = unchanged("don't turn on flushlight")
    @Test fun preservesDoNotCommand() = unchanged("do not call Muhammed")
    @Test fun preservesNeverCommand() = unchanged("never open Spotifi")
    @Test fun preservesNegatedRecipient() = unchanged("call not Muhammed")
    @Test fun preservesPhoneNumber() = unchanged("call +880 1712 345678")
    @Test fun preservesPins() = unchanged("unlock phone 1234")
    @Test fun preservesUrl() = unchanged("open https://spotifi.example")
    @Test fun preservesSpokenUrl() = unchanged("open spotifi dot com")
    @Test fun preservesCompoundCommand() = unchanged("open Spotifi and call Muhammed")
    @Test fun preservesNonLatinNames() = unchanged("call মোহাম্মদ")

    @Test fun rejectsAmbiguousAnnaAnne() {
        val c = SttCorrector(contacts = listOf("Anna", "Anne"))
        assertEquals("call Anni", c.correct("call Anni"))
        assertEquals("call Anna", c.correct("call Anna"))
    }

    @Test fun rejectsAmbiguousSmithSmyth() {
        val c = SttCorrector(contacts = listOf("Smith", "Smyth"))
        assertEquals("call Smeth", c.correct("call Smeth"))
    }

    @Test fun rejectsNearTiedLongNames() {
        val c = SttCorrector(contacts = listOf("Christopher", "Christophers"))
        assertEquals("call Christophr", c.correct("call Christophr"))
    }

    @Test fun exactContactBeatsSimilarContact() {
        val c = SttCorrector(contacts = listOf("Muhammed", "Mohammed"))
        assertEquals("call Muhammed", c.correct("call Muhammed"))
    }

    @Test fun duplicateLabelsDoNotCreateAmbiguity() {
        val c = SttCorrector(apps = listOf("Spotify", "Spotify", "SPOTIFY"))
        assertEquals("open Spotify", c.correct("open Spotifi"))
    }

    @Test fun shortHeardNamesAreNeverExpanded() {
        val c = SttCorrector(contacts = listOf("Anna", "John"))
        assertEquals("call Ana", c.correct("call Ana"))
        assertEquals("call Jon", c.correct("call Jon"))
    }

    @Test fun homeDoesNotBecomeHomer() {
        val c = SttCorrector(contacts = listOf("Homer"))
        assertEquals("call home", c.correct("call home"))
    }

    @Test fun phoneticCollisionAloneIsNotEnough() {
        val c = SttCorrector(contacts = listOf("Robert"))
        assertEquals("call Rupert", c.correct("call Rupert"))
    }

    @Test fun acceptsTwoEditsOnlyWithLongMatchingPhonetics() {
        val c = SttCorrector(contacts = listOf("Christopher"))
        assertEquals("call Christopher", c.correct("call Christofer"))
    }

    @Test fun preservesKnownApostropheAndHyphen() {
        val c = SttCorrector(contacts = listOf("O'Neil", "Mary-Jane"))
        assertEquals("call O'Neil", c.correct("call O'Neal"))
        assertEquals("call Mary-Jane", c.correct("call Mary-Jane"))
    }

    @Test fun namesDoNotCrossIntoAppSlots() {
        val c = SttCorrector(contacts = listOf("Mohammed"))
        assertEquals("open Muhammed", c.correct("open Muhammed"))
    }

    @Test fun appsDoNotCrossIntoContactSlots() {
        val c = SttCorrector(apps = listOf("Spotify"))
        assertEquals("call Spotifi", c.correct("call Spotifi"))
    }

    @Test fun mistakesTableDoesNotChangeNamedContacts() {
        val c = SttCorrector(contacts = listOf("Flushlight"))
        assertEquals("call Flushlight", c.correct("call Flushlight"))
    }

    @Test fun mistakesTableHonorsToolVocabulary() {
        val c = SttCorrector(toolWords = emptyList())
        assertEquals("turn on flushlight", c.correct("turn on flushlight"))
    }

    @Test fun preservesLabelsWithDigits() {
        val c = SttCorrector(contacts = listOf("Agent 47"), apps = listOf("Authenticator 2"))
        assertEquals("call Agent 47", c.correct("call Agent 47"))
        assertEquals("open Authenticator 3", c.correct("open Authenticator 3"))
    }

    @Test fun ignoresBlankVocabularyEntries() {
        val c = SttCorrector(contacts = listOf("", "   ", "Mohammed"))
        assertEquals("call Mohammed", c.correct("call Muhammed"))
    }

    @Test fun isIndependentOfTurkishDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val c = SttCorrector(apps = listOf("SPOTIFY"))
            assertEquals("open SPOTIFY", c.correct("open Spotifi"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun repeatedCorrectionIsStable() {
        val result = corrector.correct("turn of flushlight")
        assertEquals(result, corrector.correct(result))
    }
}
