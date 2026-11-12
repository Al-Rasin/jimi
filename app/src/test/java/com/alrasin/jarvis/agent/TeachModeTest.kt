package com.alrasin.jarvis.agent

import com.alrasin.jarvis.brain.AssistantRouting
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachModeTest {
    private val noApp: (String) -> Boolean = { false }
    private val app = "com.example.coffee"

    @Test fun startCommands() {
        assertEquals("order coffee", TeachCommand.startOrNull("Jimi, learn how to order coffee", noApp)?.name)
        assertEquals("order coffee", TeachCommand.startOrNull("watch me order coffee", noApp)?.name)
        assertEquals("order coffee", TeachCommand.startOrNull("learn order coffee.", noApp)?.name)
        assertEquals("morning news", TeachCommand.startOrNull("learn the task morning news", noApp)?.name)
    }

    @Test fun plainLearnOfAppOrQuestionIsNotTeach() {
        assertNull(TeachCommand.startOrNull("learn whatsapp", { it == "whatsapp" }))
        assertNull(TeachCommand.startOrNull("learn the whatsapp app", { it == "whatsapp" }))
        assertNull(TeachCommand.startOrNull("learn about black holes", noApp))
        assertNull(TeachCommand.startOrNull("learn english", noApp))
        assertNull(TeachCommand.startOrNull("open youtube", noApp))
        // Explicit "how to" wins even if the words name an app.
        assertNotNull(TeachCommand.startOrNull("learn how to whatsapp", { true }))
    }

    @Test fun doneAndCancel() {
        assertEquals(TeachCommand.Cmd.Done, TeachCommand.duringTeach("Jimi, done"))
        assertEquals(TeachCommand.Cmd.Done, TeachCommand.duringTeach("ok I'm done"))
        assertEquals(TeachCommand.Cmd.Done, TeachCommand.duringTeach("that's it"))
        assertEquals(TeachCommand.Cmd.Cancel, TeachCommand.duringTeach("cancel"))
        assertEquals(TeachCommand.Cmd.Cancel, TeachCommand.duringTeach("don't save it"))
        assertNull(TeachCommand.duringTeach("what time is it"))
    }

    @Test fun assistantRouting() {
        val done = AssistantRouting.teachCallFor("done", null, active = true) { false }
        assertEquals("teach_done", done?.tool)
        assertNull(AssistantRouting.teachCallFor("done", null, active = false) { false })
        assertEquals("teach_cancel", AssistantRouting.teachCallFor("cancel", ToolCall("cancel", emptyMap()), true) { false }?.tool)
        val start = AssistantRouting.teachCallFor("learn order coffee", null, false) { false }
        assertEquals("teach_start", start?.tool)
        assertEquals("order coffee", start?.arg("task"))
        // Another rule (e.g. "learn it" for the permission queue) keeps its own meaning.
        assertNull(AssistantRouting.teachCallFor("learn it", ToolCall("ask_answer", emptyMap()), false) { false })
    }

    @Test fun recorderKeepsOneAppAndSkipsSecrets() {
        val rec = TeachRecorder { it == "launcher" }
        rec.add(TeachEvent.Click("launcher", TeachNode(text = "Coffee")))
        rec.add(TeachEvent.Click(app, TeachNode(text = "Menu")))
        rec.add(TeachEvent.Click("other.app", TeachNode(text = "X")))
        rec.add(TeachEvent.Click(app, TeachNode(id = "$app:id/search", editable = true)))
        rec.add(TeachEvent.Typed(app, TeachNode(id = "$app:id/search"), "lat"))
        rec.add(TeachEvent.Typed(app, TeachNode(id = "$app:id/search"), "latte"))
        rec.add(TeachEvent.Typed(app, TeachNode(id = "$app:id/otp_input"), "123456"))
        rec.add(TeachEvent.Typed(app, TeachNode(password = true), "hunter2"))
        assertEquals(app, rec.pkg)
        assertEquals(2, rec.size)
        assertEquals("latte", (rec.events()[1] as TeachEvent.Typed).text)
        assertTrue(rec.skippedSecret)
    }

    @Test fun buildMakesParamsAndEnter() {
        val events = listOf(
            TeachEvent.Click(app, TeachNode(text = "Menu")),
            TeachEvent.Typed(app, TeachNode(id = "$app:id/search", hint = "Search"), "Latte"),
            TeachEvent.Click(app, TeachNode(id = "$app:id/row", text = "Latte", inList = true)),
            TeachEvent.Click(app, TeachNode(id = "$app:id/add", desc = "Add to cart")),
        )
        val built = TeachBook.build("order latte", events, app, "Coffee", 42)
        val r = (built as TeachBook.Built.Ok).recipe
        assertEquals(listOf("order {text}"), r.say)
        assertEquals(listOf("text"), r.params)
        assertEquals(listOf("open", "tap", "type", "tap", "tap"), r.steps.map { it.action })
        assertEquals("{text}", r.steps[2].text)
        assertEquals("{text}", r.steps[3].target)
        // The list row's shared id is not used when it has a label.
        assertFalse(r.steps[3].selectors.any { it["type"] == "id" })
        assertEquals(TeachBook.SOURCE, r.source)
        assertEquals(42L, r.minVersion)
        assertTrue(TeachBook.summary(r).contains("order something"))
    }

    @Test fun typingThenNothingAddsEnter() {
        val events = listOf(TeachEvent.Typed(app, TeachNode(id = "$app:id/q"), "weather"))
        val r = (TeachBook.build("check weather", events, app, "W", 1) as TeachBook.Built.Ok).recipe
        assertEquals(listOf("open", "type", "enter"), r.steps.map { it.action })
    }

    @Test fun buildFailsWithNothing() {
        assertTrue(TeachBook.build("x", emptyList(), null, "", 0) is TeachBook.Built.Fail)
        val blank = listOf(TeachEvent.Click(app, TeachNode()))
        assertTrue(TeachBook.build("x y", blank, app, "", 0) is TeachBook.Built.Fail)
    }

    @Test fun riskyTapMarksRecipe() {
        val events = listOf(TeachEvent.Click(app, TeachNode(text = "Place order")))
        val r = (TeachBook.build("buy coffee", events, app, "Coffee", 1) as TeachBook.Built.Ok).recipe
        assertTrue(r.risky)
    }

    @Test fun taughtRecipeMatchesWithOneWordAndBeatsCard() {
        val taught = Recipe(pkg = app, appName = "Coffee", name = "coffee", say = listOf("coffee"), params = emptyList(),
            steps = listOf(RecipeStep("open")), risky = false, source = TeachBook.SOURCE)
        assertEquals("coffee", RecipeBook.match("coffee", listOf(taught))?.recipe?.name)
    }

    @Test fun renameKeepsParams() {
        assertEquals("get my drink {text}", TeachBook.renamedSay("order {text}", "Get my drink"))
        assertEquals("send {text} hi", TeachBook.renamedSay("message {text}", "send {text} hi"))
    }
}
