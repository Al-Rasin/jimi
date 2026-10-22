package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverTest {
    private val khan = Contacts.Contact("Rahim Khan", "+15550100001")
    private val office = Contacts.Contact("Rahim Office", "+15550100002")
    private val uddin = Contacts.Contact("Rahim Uddin", "+15550100003")
    private val contacts = listOf(khan, office, uddin)

    @Test fun firstNameKeepsAllCandidates() {
        val result = ContactResolver.resolve("Rahim", contacts) as ContactResolver.Resolution.Ambiguous
        assertEquals(contacts, result.candidates)
        assertEquals("Which Rahim: Rahim Khan, Rahim Office, or Rahim Uddin?", result.question)
    }

    @Test fun candidatesHaveStableOrderDespiteProviderOrder() {
        val result = ContactResolver.resolve("Rahim", contacts.reversed()) as ContactResolver.Resolution.Ambiguous
        assertEquals(contacts, result.candidates)
    }

    @Test fun fullNameResolvesUniquely() {
        assertEquals(ContactResolver.Resolution.Found(khan), ContactResolver.resolve("Rahim Khan", contacts))
    }

    @Test fun surnameResolvesUniquely() {
        assertEquals(ContactResolver.Resolution.Found(khan), ContactResolver.resolve("Khan", contacts))
    }

    @Test fun nameMatchingIgnoresCaseAndExtraSpaces() {
        assertEquals(ContactResolver.Resolution.Found(office), ContactResolver.resolve("  RAHIM   OFFICE ", contacts))
    }

    @Test fun exactShortNameDoesNotHideLongerNames() {
        val result = ContactResolver.resolve("Rahim", contacts + Contacts.Contact("Rahim", "+15550100004"))
        assertEquals(4, (result as ContactResolver.Resolution.Ambiguous).candidates.size)
    }

    @Test fun duplicateProviderRowsAreCollapsed() {
        assertEquals(ContactResolver.Resolution.Found(khan), ContactResolver.resolve("Rahim", listOf(khan, khan)))
    }

    @Test fun duplicateFormattedNumbersAreCollapsed() {
        val duplicate = khan.copy(number = "+1 (555) 010-0001")
        assertEquals(ContactResolver.Resolution.Found(khan), ContactResolver.resolve("Rahim", listOf(khan, duplicate)))
    }

    @Test fun sameNameDifferentNumbersRemainAmbiguous() {
        val second = khan.copy(number = "+15550100004")
        val result = ContactResolver.resolve("Rahim Khan", listOf(khan, second)) as ContactResolver.Resolution.Ambiguous
        assertEquals(2, result.candidates.size)
        assertTrue(result.question.contains("number ending 0001"))
        assertTrue(result.question.contains("number ending 0004"))
        assertNull(ContactResolver.choose("Khan", result.candidates))
    }

    @Test fun sharedNumberWithDifferentNamesStillAsks() {
        val result = ContactResolver.resolve("Rahim", listOf(khan, office.copy(number = khan.number)))
        assertTrue(result is ContactResolver.Resolution.Ambiguous)
    }

    @Test fun uniqueTypoCanResolve() {
        assertEquals(ContactResolver.Resolution.Found(khan), ContactResolver.resolve("Rahim Kahn", contacts))
    }

    @Test fun uncertainFuzzyCandidatesAreNotSilentlyRankedAway() {
        val similar = listOf(Contacts.Contact("Kamal", "+15550100001"), Contacts.Contact("Kamel", "+15550100002"))
        assertTrue(ContactResolver.resolve("Kamil", similar) is ContactResolver.Resolution.Ambiguous)
    }

    @Test fun unknownNameHasNoMatch() {
        assertEquals(ContactResolver.Resolution.NotFound, ContactResolver.resolve("Elizabeth", contacts))
    }

    @Test fun emptyAndPunctuationOnlyQueriesHaveNoMatch() {
        listOf("", "  ", "!!!").forEach {
            assertEquals(ContactResolver.Resolution.NotFound, ContactResolver.resolve(it, contacts))
        }
    }

    @Test fun blankNamesAndInvalidNumbersAreNotCandidates() {
        val invalid = listOf(Contacts.Contact("Rahim", ""), Contacts.Contact("Rahim", "unknown"), Contacts.Contact("", "+15550100001"))
        assertEquals(ContactResolver.Resolution.NotFound, ContactResolver.resolve("Rahim", invalid))
    }

    @Test fun decoratedContactNameCanMatch() {
        val mom = Contacts.Contact("Mom ❤", "+15550100005")
        assertEquals(ContactResolver.Resolution.Found(mom), ContactResolver.resolve("mom", listOf(mom)))
    }

    @Test fun numericTargetDoesNotRequireContacts() {
        val expected = Contacts.Contact("+15550100001", "+15550100001")
        assertEquals(ContactResolver.Resolution.Found(expected), ContactResolver.resolve("+1 (555) 010-0001", emptyList()))
    }

    @Test fun localNumbersPreserveLeadingZero() {
        assertEquals("01712345678", ContactResolver.number("01712 345678"))
    }

    @Test fun parenthesizedLocalNumberIsAccepted() {
        assertEquals("5550100001", ContactResolver.number("(555) 010-0001"))
    }

    @Test fun invalidOrMixedNumericTargetsAreRejected() {
        listOf("1234", "+1234", "Rahim 123456", "123456abc", "12+34567", "++123456", "123456;789").forEach {
            assertNull(it, ContactResolver.number(it))
        }
    }

    @Test fun ordinalFirstVariantsChooseFirstOfferedCandidate() {
        listOf("first", "the first one", "first one please", "one", "1", "1st", "number 1", "option one").forEach {
            assertEquals(it, khan, ContactResolver.choose(it, contacts))
        }
    }

    @Test fun secondAndThirdOrdinalsWork() {
        listOf("second", "the second one", "two", "2nd").forEach {
            assertEquals(it, office, ContactResolver.choose(it, contacts))
        }
        listOf("third", "the third one", "three", "3rd").forEach {
            assertEquals(it, uddin, ContactResolver.choose(it, contacts))
        }
    }

    @Test fun laterOrdinalsAndLastWork() {
        val many = (1..10).map { Contacts.Contact("Person $it", "+155501000${it.toString().padStart(2, '0')}") }
        listOf("fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth").forEachIndexed { i, choice ->
            assertEquals(many[i + 3], ContactResolver.choose(choice, many))
        }
        assertEquals(uddin, ContactResolver.choose("the last one", contacts))
    }

    @Test fun ordinalUsesOfferedOrderWithoutResorting() {
        assertEquals(uddin, ContactResolver.choose("first", contacts.reversed()))
    }

    @Test fun invalidOrdinalsDoNotPickAContact() {
        listOf("zero", "0", "4", "fourth", "-1", "9999999999999999").forEach {
            assertNull(it, ContactResolver.choose(it, contacts))
        }
    }

    @Test fun uniqueSurnameAndOfficeSelectionWork() {
        assertEquals(khan, ContactResolver.choose("Khan", contacts))
        assertEquals(office, ContactResolver.choose("office", contacts))
        assertEquals(office, ContactResolver.choose("OFFICE!", contacts))
        assertEquals(office, ContactResolver.choose("Rahim Office", contacts))
    }

    @Test fun sharedNameDoesNotPickFirst() {
        assertNull(ContactResolver.choose("Rahim", contacts))
    }

    @Test fun ordinalWordsInsideAnUnrelatedCommandDoNotSelect() {
        assertNull(ContactResolver.choose("call the first one tomorrow", contacts))
    }

    @Test fun emptyUnknownAndAmbiguousAnswersDoNotSelect() {
        listOf("", "yes", "no", "cancel", "Zahid").forEach {
            assertNull(it, ContactResolver.choose(it, contacts))
        }
    }

    @Test fun numericChoiceOnlySelectsOfferedNumber() {
        assertEquals(office, ContactResolver.choose("+1 (555) 010-0002", contacts))
        assertNull(ContactResolver.choose("+15550100099", contacts))
    }

    @Test fun matchingNumberSharedByCandidatesNeedsFurtherChoice() {
        assertNull(ContactResolver.choose(khan.number, listOf(khan, office.copy(number = khan.number))))
    }

    @Test fun emptyChoicesAreSafe() {
        assertNull(ContactResolver.choose("first", emptyList()))
        assertNull(ContactResolver.choose("last", emptyList()))
        assertEquals("Which contact did you mean?", ContactResolver.question("Rahim", emptyList()))
    }

    @Test fun twoCandidateQuestionUsesOr() {
        assertEquals("Which Rahim: Rahim Khan or Rahim Office?", ContactResolver.question("Rahim", contacts.take(2)))
    }
}
