package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactAppActionsTest {
    private fun contact(name: String, suffix: Int = 1, vararg aliases: String) =
        Contacts.Contact(name, "+155501000${suffix.toString().padStart(2, '0')}", aliases = aliases.toList())

    @Test fun exactMomDoesNotHideAllOtherRelationMatches() {
        val contacts = listOf("Mom", "Mother", "Ammu", "Amma", "Maa", "Mum", "Mom Robi")
            .mapIndexed { i, name -> contact(name, i + 1) }
        val result = ContactResolver.resolve("mom", contacts) as ContactResolver.Resolution.Ambiguous
        assertEquals(contacts.toSet(), result.candidates.toSet())
    }

    @Test fun aliasMatchesTheActualContactName() {
        val person = contact("Parent Person", 1, "Amma")
        assertEquals(ContactResolver.Resolution.Found(person), ContactResolver.resolve("mom", listOf(person)))
    }

    @Test fun arbitraryNicknameMatches() {
        val person = contact("Someone Else", 1, "Rocket")
        assertEquals(ContactResolver.Resolution.Found(person), ContactResolver.resolve("rocket", listOf(person)))
    }

    @Test fun sharedNicknamesRemainAmbiguous() {
        val candidates = listOf(contact("Person A", 1, "Mom"), contact("Person B", 2, "Mother"))
        assertTrue(ContactResolver.resolve("ammu", candidates) is ContactResolver.Resolution.Ambiguous)
    }

    @Test fun duplicateRowsMergeNicknames() {
        val first = contact("Parent Person", 1, "Home")
        val second = first.copy(aliases = listOf("Amma"))
        val result = ContactResolver.resolve("mom", listOf(first, second)) as ContactResolver.Resolution.Found
        assertEquals(first.number, result.contact.number)
        assertEquals(listOf("Home", "Amma"), result.contact.aliases)
    }

    @Test fun shortRelationNeverMatchesUnrelatedNamePrefix() {
        assertEquals(ContactResolver.Resolution.NotFound,
            ContactResolver.resolve("ma", listOf(contact("Martin"), contact("Martha", 2))))
    }

    @Test fun motherDoesNotFuzzilyBecomeBrother() {
        assertEquals(ContactResolver.Resolution.NotFound,
            ContactResolver.resolve("mother", listOf(contact("Brother"))))
    }

    @Test fun momDoesNotFuzzilyBecomeTom() {
        assertEquals(ContactResolver.Resolution.NotFound,
            ContactResolver.resolve("mom", listOf(contact("Tom"))))
    }

    @Test fun relationModifiersKeepTheRequestedPersonSpecific() {
        val robi = contact("Mom Robi")
        assertEquals(ContactResolver.Resolution.Found(robi),
            ContactResolver.resolve("ammu robi", listOf(robi, contact("Mother Office", 2))))
    }

    @Test fun phoneticVowelsMatchWithinLongerNames() {
        val person = contact("Raheem Office")
        assertEquals(ContactResolver.Resolution.Found(person), ContactResolver.resolve("Rahim", listOf(person)))
    }

    @Test fun transliteratedDoubleConsonantsMatch() {
        val person = contact("Hassan Khan")
        assertEquals(ContactResolver.Resolution.Found(person), ContactResolver.resolve("Hasan", listOf(person)))
    }

    @Test fun transliteratedLongVowelsMatch() {
        val person = contact("Shaheen Office")
        assertEquals(ContactResolver.Resolution.Found(person), ContactResolver.resolve("Shahin", listOf(person)))
    }

    @Test fun phoneticMatchRemainsAlongsideExactMatch() {
        val result = ContactResolver.resolve("Rahim", listOf(contact("Rahim"), contact("Raheem", 2)))
        assertEquals(2, (result as ContactResolver.Resolution.Ambiguous).candidates.size)
    }

    @Test fun phoneticNicknameCanMatch() {
        val person = contact("Someone Else", 1, "Raheem")
        assertEquals(ContactResolver.Resolution.Found(person), ContactResolver.resolve("Rahim", listOf(person)))
    }

    @Test fun accentsDoNotPreventMatching() {
        val person = contact("José")
        assertEquals(ContactResolver.Resolution.Found(person), ContactResolver.resolve("Jose", listOf(person)))
    }

    @Test fun banglaNamesKeepDistinctVowelMarks() {
        assertEquals(ContactResolver.Resolution.NotFound,
            ContactResolver.resolve("মা", listOf(contact("মি"))))
    }

    @Test fun exactDisplayedNameCanSelectAmongRelatedNames() {
        val mom = contact("Mom")
        assertEquals(mom, ContactResolver.choose("Mom", listOf(mom, contact("Mother", 2))))
    }

    @Test fun nicknameSelectsOnlyOneOfferedContact() {
        val person = contact("Person A", 1, "Home")
        assertEquals(person, ContactResolver.choose("home", listOf(person, contact("Person B", 2, "Office"))))
    }

    @Test fun sharedRelationSelectionDoesNotChooseFirst() {
        assertNull(ContactResolver.choose("mom", listOf(contact("Ammu"), contact("Mother", 2))))
    }

    @Test fun fuzzySelectionDoesNotSilentlyChangeRecipient() {
        assertNull(ContactResolver.choose("Rahim", listOf(contact("Raheem"))))
    }

    @Test fun emptyPossessiveIsNotAName() {
        listOf("my ", "amar ").forEach {
            assertEquals(ContactResolver.Resolution.NotFound, ContactResolver.resolve(it, emptyList()))
        }
    }
}
