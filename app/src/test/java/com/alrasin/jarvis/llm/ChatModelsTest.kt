package com.alrasin.jarvis.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatModelsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun preferredQadFileWinsOverOtherQuantizations() {
        folder.newFile("LFM2.5-1.2B-Instruct-Q4_K_M.gguf")
        val preferred = folder.newFile(ChatModels.LFM_FILE)
        assertEquals(preferred, ChatModels.findLfm(folder.root))
    }

    @Test fun detectionIsCaseInsensitive() {
        val file = folder.newFile("lfm2.5-1.2b-instruct-qad-q4_0.GGUF")
        assertEquals(file, ChatModels.findLfm(folder.root))
    }

    @Test fun missingLfmNeverFallsBackToAnotherFamilyOrIncompleteDownload() {
        folder.newFile("Qwen3-0.6B-Q4_0.gguf")
        folder.newFile("LFM2.5-1.2B-Base-Q4_0.gguf")
        folder.newFile("LFM2.5-1.2B-Thinking-Q4_0.gguf")
        folder.newFile("LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf.part")
        folder.newFolder(ChatModels.LFM_FILE)
        assertNull(ChatModels.findLfm(folder.root))
    }

    @Test fun otherInstructQuantsHaveStableFilenameOrder() {
        folder.newFile("LFM2.5-1.2B-Instruct-Q8_0.gguf")
        val smaller = folder.newFile("LFM2.5-1.2B-Instruct-Q4_K_M.gguf")
        assertEquals(smaller, ChatModels.findLfm(folder.root))
    }
}
