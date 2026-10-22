package com.alrasin.jarvis.brain

import com.alrasin.jarvis.llm.ChatModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRouteModelTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun gravityUsesInstalledChatModelWhenSavedLfmIsMissing() {
        folder.newFile("functiongemma-270m-jarvis-v2-Q8_0.gguf")
        val qwen = folder.newFile("Qwen3-0.6B-Q4_0.gguf")
        val intent = IntentRouter.classify("what is gravity", null)
        assertEquals(IntentRouter.Route.CHAT, intent.route)
        assertEquals(qwen, ModelSelection.fileFor(intent.route, folder.root, ChatModels.LFM))
        assertEquals(qwen, ModelSelection.fileFor(intent.route, folder.root))
    }

    @Test fun installedLfmStillWinsWhenSelected() {
        val lfm = folder.newFile(ChatModels.LFM_FILE)
        val qwen = folder.newFile("Qwen3-0.6B-Q4_0.gguf")
        assertEquals(lfm, ModelSelection.fileFor(IntentRouter.Route.CHAT, folder.root, ChatModels.LFM))
        assertEquals(qwen, ModelSelection.fileFor(IntentRouter.Route.CHAT, folder.root))
    }

    @Test fun chatNeverUsesToolWeightsOrPartialDownloads() {
        folder.newFile("functiongemma-270m-jarvis-v2-Q8_0.gguf")
        folder.newFile("Qwen3-0.6B-Q4_0.gguf.part")
        folder.newFolder("gemma-3-1b.gguf")
        assertNull(ModelSelection.fileFor(IntentRouter.Route.CHAT, folder.root, ChatModels.LFM))
    }

    @Test fun toolRouteKeepsFineTunedModelWhenChatIsSelected() {
        folder.newFile("functiongemma-270m-it-Q8_0.gguf")
        val tool = folder.newFile("functiongemma-270m-jarvis-v2-Q8_0.gguf")
        folder.newFile(ChatModels.LFM_FILE)
        assertEquals(tool, ModelSelection.fileFor(IntentRouter.Route.TOOL, folder.root, ChatModels.LFM))
        assertNull(ModelSelection.fileFor(IntentRouter.Route.WEB, folder.root))
    }
}
