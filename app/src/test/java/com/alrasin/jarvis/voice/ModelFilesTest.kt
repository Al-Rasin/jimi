package com.alrasin.jarvis.voice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun speechRequiresEveryNonemptyPart() {
        val dir = temp.newFolder()
        File(dir, "tokens.txt").writeText("tokens")
        File(dir, "encoder.int8.onnx").writeText("model")
        File(dir, "decoder.onnx").writeText("model")
        assertFalse(ModelFiles.speechReady(dir))
        File(dir, "joiner.onnx").writeText("")
        assertFalse(ModelFiles.speechReady(dir))
        File(dir, "joiner.onnx").writeText("model")
        assertTrue(ModelFiles.speechReady(dir))
    }

    @Test fun archiveRejectsTraversalAndAbsoluteNames() {
        assertNull(ModelFiles.archivePath("/tmp/file"))
        assertNull(ModelFiles.archivePath("pack/../file"))
        assertNull(ModelFiles.archivePath("pack/./file"))
        assertNull(ModelFiles.archivePath("pack\\..\\file"))
        assertEquals("espeak-ng-data/en_dict", ModelFiles.archivePath("pack/espeak-ng-data/en_dict"))
    }

    @Test fun voiceNeedsACompletedExtractionBeforeItCanLoad() {
        val dir = temp.newFolder()
        File(dir, "espeak-ng-data").mkdirs()
        listOf("en_GB-alan-medium.onnx", "tokens.txt", "espeak-ng-data/phontab").forEach {
            File(dir, it).writeText("data")
        }
        assertFalse(ModelFiles.voiceReady(dir))
        File(dir, ".complete").writeText("complete")
        assertTrue(ModelFiles.voiceReady(dir))
        File(dir, "tokens.txt").writeText("")
        assertFalse(ModelFiles.voiceReady(dir))
    }

    @Test fun downloadsRejectEmptyOrTruncatedResponses() {
        assertTrue(runCatching { ModelFiles.validateDownload(0, -1) }.isFailure)
        assertTrue(runCatching { ModelFiles.validateDownload(4, 8) }.isFailure)
        ModelFiles.validateDownload(4, 4)
        ModelFiles.validateDownload(4, -1)
    }
}
