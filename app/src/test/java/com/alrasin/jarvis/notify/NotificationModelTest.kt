package com.alrasin.jarvis.notify

import com.alrasin.jarvis.core.Json
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NotificationModelTest {
    private val input = NotificationModel.Input("com.example", "Offer", "Sale 50%", 0)

    @Test fun sharedPythonVectorsMatchFeaturesAndProbabilities() {
        val fixture = javaClass.getResourceAsStream("/notification_feature_vectors.json")!!
            .bufferedReader().use { Json.parse(it.readText()) as Map<*, *> }
        assertEquals(1L, fixture["feature_version"])
        val model = NotificationModel.fromJson(Json.write(fixture["model"]))
        assertTrue(model.isLoaded)
        for (raw in fixture["vectors"] as List<*>) {
            val vector = raw as Map<*, *>
            val values = vector["input"] as Map<*, *>
            val notification = NotificationModel.Input(
                pkg = values["pkg"] as? String ?: "",
                title = values["title"] as? String ?: "",
                text = values["text"] as? String ?: "",
                timeMs = (values["timeMs"] as? Number)?.toLong() ?: 0L,
                isConversation = values["isConversation"] as? Boolean ?: false,
                isGroupConversation = values["isGroupConversation"] as? Boolean ?: false,
            )
            assertEquals(vector["name"].toString(), (vector["features"] as List<*>).toSet(), NotificationModel.features(notification))
            assertEquals(vector["name"].toString(), (vector["probability"] as Number).toDouble(), model.predictKeepProbability(notification)!!, 1e-12)
        }
    }

    @Test fun missingModelFallsBackToExactTriageVerdict() {
        val model = NotificationModel.load(null)
        assertFalse(model.isLoaded)
        assertNull(model.predictKeepProbability(input))
        assertEquals(Triage.classify(input.pkg, input.title, input.text, null, null), model.classify(input))
        assertEquals(Triage.classify(input.pkg, input.title, input.text, "msg", null), model.classify(input, "msg"))
    }

    @Test fun invalidModelsFallBack() {
        val valid = modelJson()
        val invalid = listOf(
            "", "{", "[]", valid + "trailing", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"feature_version\":1", "\"feature_version\":2"),
            valid.replace("\"bias\":0", "\"bias\":1e999"),
            valid.replace("\"threshold\":0.5", "\"threshold\":1"),
            valid.replace("\"weights\":{}", "\"weights\":{\"x\":\"bad\"}"),
            valid.replace("\"weights\":{}", "\"weights\":{\"x\":1e999}"),
        )
        invalid.forEach {
            val model = NotificationModel.fromJson(it)
            assertFalse(it, model.isLoaded)
            assertEquals(Triage.classify(input.pkg, input.title, input.text, null, null), model.classify(input))
        }
    }

    @Test fun fileLoadingAndUnreadablePath() {
        val file = File.createTempFile("notification-model", ".json")
        try {
            file.writeText(modelJson())
            assertEquals(0.5, NotificationModel.load(file).predictKeepProbability(input)!!, 0.0)
            assertFalse(NotificationModel.load(File(file, "missing.json")).isLoaded)
            file.writeText("broken")
            assertFalse(NotificationModel.load(file).isLoaded)
        } finally {
            file.delete()
        }
    }

    @Test fun explicitAppRulesAlwaysWin() {
        val model = NotificationModel.fromJson(modelJson(bias = -1000.0))
        assertFalse(model.classify(input).important)
        assertEquals(Triage.Verdict(true, "your rule"), model.classify(input, rule = "keep"))
        val positive = NotificationModel.fromJson(modelJson(bias = 1000.0))
        assertEquals(Triage.Verdict(false, "your rule"), positive.classify(input, rule = "clear"))
    }

    @Test fun stableSigmoidAtExtremeScores() {
        assertEquals(0.0, NotificationModel.fromJson(modelJson(-1000.0)).predictKeepProbability(input)!!, 0.0)
        assertEquals(1.0, NotificationModel.fromJson(modelJson(1000.0)).predictKeepProbability(input)!!, 0.0)
    }

    @Test fun thresholdAndUnknownFeatures() {
        val model = NotificationModel.fromJson(modelJson(threshold = 0.7))
        assertEquals(0.5, model.predictKeepProbability(input)!!, 0.0)
        assertFalse(model.classify(input).important)
        assertTrue(NotificationModel.fromJson(modelJson()).classify(input).important)
    }

    @Test fun conservativeClassificationOnlyAddsKeeps() {
        val dismiss = NotificationModel.fromJson(modelJson(bias = -1000.0))
        val keep = NotificationModel.fromJson(modelJson(bias = 1000.0))
        assertEquals(Triage.Verdict(true, "learned notification preference"), keep.classifyConservatively(input))
        assertEquals(Triage.classify(input.pkg, input.title, input.text, null, null), dismiss.classifyConservatively(input))
        for (category in listOf("call", "msg", "alarm")) {
            assertEquals(
                Triage.classify(input.pkg, input.title, input.text, category, null),
                dismiss.classifyConservatively(input, category),
            )
        }
        val ordinary = input.copy(title = "Update", text = "The task finished")
        assertTrue(dismiss.classifyConservatively(ordinary).important)
        assertEquals(Triage.Verdict(true, "your rule"), dismiss.classifyConservatively(input, rule = "keep"))
        assertEquals(Triage.Verdict(false, "your rule"), keep.classifyConservatively(input, rule = "clear"))
    }

    @Test fun conservativeClassificationPreservesFullTextProtectionAndFallback() {
        val fullText = "x".repeat(501) + " OTP 1234"
        val clipped = input.copy(text = fullText.take(500))
        val dismiss = NotificationModel.fromJson(modelJson(bias = -1000.0))
        assertEquals(Triage.Verdict(true, "important words"), dismiss.classifyConservatively(clipped, fullText = fullText))

        val latePromo = "x".repeat(501) + " sale"
        val plain = input.copy(title = "Update", text = latePromo.take(500))
        val baseline = Triage.classify(plain.pkg, plain.title, latePromo, null, null)
        assertFalse(baseline.important)
        for (model in listOf(NotificationModel.load(null), NotificationModel.fromJson("broken"))) {
            assertEquals(baseline, model.classifyConservatively(plain, fullText = latePromo))
        }
    }

    private fun modelJson(bias: Double = 0.0, threshold: Double = 0.5): String = Json.write(
        mapOf("version" to 1, "feature_version" to 1, "bias" to bias, "threshold" to threshold, "weights" to emptyMap<String, Double>()),
    )
}
