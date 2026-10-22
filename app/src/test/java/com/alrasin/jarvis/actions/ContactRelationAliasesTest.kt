package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ContactRelationAliasesTest(private val query: String, private val savedName: String) {
    @Test fun relationResolvesWithoutInternet() {
        val contact = Contacts.Contact(savedName, "+15550100001")
        assertEquals(ContactResolver.Resolution.Found(contact), ContactResolver.resolve(query, listOf(contact)))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} matches {1}")
        fun cases(): List<Array<String>> = listOf(
            "mom" to "Mother", "mother" to "Mom", "mom" to "Ammu", "mom" to "Amma",
            "mom" to "Maa", "mom" to "Mum", "mom" to "Mummy", "mom" to "Mommy",
            "mom" to "Ammi", "mom" to "Ma", "mom" to "Mom Robi", "mom" to "Ammu Robi",
            "mom" to "মা", "mom" to "আম্মু", "mom" to "আম্মা", "আম্মু" to "Mother",
            "my mom" to "Mother", "amar ammu" to "Mom", "amma robi" to "Mother Robi",
            "dad" to "Father", "dad" to "Baba", "dad" to "Abbu", "dad" to "Abba",
            "dad" to "বাবা", "sister" to "Apu", "sister" to "Apa", "brother" to "Bhai",
            "brother" to "Vai", "grandma" to "Nani", "grandma" to "Dadi",
            "grandpa" to "Nana", "grandpa" to "Dada", "aunt" to "Khala",
            "aunt" to "Fupu", "uncle" to "Chacha", "uncle" to "Mamu",
            " MOM " to "❤ Ammu ❤", "ma" to "Ammu Robi", "Mother Robi" to "Amma Robi",
        ).map { arrayOf(it.first, it.second) }
    }
}
