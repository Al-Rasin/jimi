package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.ContactResolver
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingContactChoiceTest {
    private val candidates = listOf(
        Contacts.Contact("Rahim Khan", "+15550000001"),
        Contacts.Contact("Rahim Office", "+15550000002"),
        Contacts.Contact("Rahim Uddin", "+15550000003"),
    )
    private val call = ToolCall("send_sms", mapOf("contact" to "Rahim", "text" to "See you at lunch"))
    private var now = 1_000L
    private val pending = PendingContactChoice { now }

    private fun begin() = pending.begin(call, ContactResolver.Resolution.Ambiguous("Rahim", candidates))

    @Test fun noQuestionMeansNoChoice() {
        assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("the first one"))
    }

    @Test fun promptListsEveryChoiceInOfferedOrder() {
        assertEquals("Which Rahim: Rahim Khan, Rahim Office, or Rahim Uddin?", begin().question)
    }

    @Test fun ordinalPinsNumberAndPreservesMessage() {
        begin()
        val selection = pending.answer("the first one") as PendingContactChoice.Reply.Selection
        assertEquals("send_sms", selection.call.tool)
        assertEquals("+15550000001", selection.call.arg("contact"))
        assertEquals("Rahim Khan", selection.call.arg("contact_name"))
        assertEquals("See you at lunch", selection.call.arg("text"))
        assertEquals(candidates.first(), selection.contact)
    }

    @Test fun uniqueNameFragmentsSelectTheOfferedContact() {
        for ((reply, index) in listOf("Khan" to 0, "office" to 1, "Uddin" to 2)) {
            begin()
            assertEquals(candidates[index], (pending.answer(reply) as PendingContactChoice.Reply.Selection).contact)
        }
    }

    @Test fun repeatedFamilyNameDoesNotGuess() {
        begin()
        assertTrue(pending.answer("Rahim") is PendingContactChoice.Reply.Prompt)
    }

    @Test fun yesDoesNotSelectOrConfirmAnyContact() {
        begin()
        assertTrue(pending.answer("yes") is PendingContactChoice.Reply.Prompt)
        assertTrue(pending.answer("the second one") is PendingContactChoice.Reply.Selection)
    }

    @Test fun unknownReplyKeepsChoiceOpen() {
        begin()
        assertTrue(pending.answer("banana") is PendingContactChoice.Reply.Prompt)
        assertEquals(candidates[1], (pending.answer("office") as PendingContactChoice.Reply.Selection).contact)
    }

    @Test fun selectionConsumesQuestion() {
        begin()
        pending.answer("Khan")
        assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("office"))
    }

    @Test fun noAndCancelDiscardPendingAction() {
        for (reply in listOf("no", "cancel", "never mind", "stop")) {
            begin()
            assertEquals(PendingContactChoice.Reply.Cancelled, pending.answer(reply))
            assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("Khan"))
        }
    }

    @Test fun explicitClearDiscardsPendingAction() {
        begin()
        pending.clear()
        assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("Khan"))
    }

    @Test fun selectionWorksJustBeforeSixtySeconds() {
        begin()
        now += 59_999
        assertTrue(pending.answer("office") is PendingContactChoice.Reply.Selection)
    }

    @Test fun sixtySecondBoundaryExpiresAndConsumesChoice() {
        begin()
        now += 60_000
        assertEquals(PendingContactChoice.Reply.Expired, pending.answer("office"))
        assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("office"))
    }

    @Test fun repromptDoesNotExtendExpiry() {
        begin()
        now += 59_000
        assertTrue(pending.answer("yes") is PendingContactChoice.Reply.Prompt)
        now += 1_000
        assertEquals(PendingContactChoice.Reply.Expired, pending.answer("Khan"))
    }

    @Test fun backwardClockExpiresInsteadOfRevivingChoice() {
        begin()
        now -= 1
        assertEquals(PendingContactChoice.Reply.Expired, pending.answer("Khan"))
    }

    @Test fun newRequestDiscardsChoiceAndPassesThrough() {
        begin()
        assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("turn up volume", newRequest = true))
        assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("Khan"))
    }

    @Test fun newRequestPassesThroughEvenAfterExpiry() {
        begin()
        now += 60_000
        assertEquals(PendingContactChoice.Reply.NoPending, pending.answer("what time is it", newRequest = true))
    }

    @Test fun replacementQuestionDropsPreviousMessageAndCandidates() {
        begin()
        val newCandidates = listOf(Contacts.Contact("Alex Home", "+15550000004"), Contacts.Contact("Alex Work", "+15550000005"))
        pending.begin(ToolCall("call", mapOf("contact" to "Alex")), ContactResolver.Resolution.Ambiguous("Alex", newCandidates))
        val selection = pending.answer("the first one") as PendingContactChoice.Reply.Selection
        assertEquals(ToolCall("call", mapOf("contact" to "+15550000004", "contact_name" to "Alex Home")), selection.call)
    }

    @Test fun offeredCandidatesAndArgumentsAreSnapshots() {
        val mutableCandidates = candidates.toMutableList()
        val args = mutableMapOf("contact" to "Rahim", "text" to "original")
        pending.begin(ToolCall("send_sms", args), ContactResolver.Resolution.Ambiguous("Rahim", mutableCandidates))
        mutableCandidates.reverse()
        args["text"] = "changed"
        val selection = pending.answer("the first one") as PendingContactChoice.Reply.Selection
        assertEquals(candidates.first(), selection.contact)
        assertEquals("original", selection.call.arg("text"))
    }
}
