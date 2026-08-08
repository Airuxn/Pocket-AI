package com.localllm.chat.llm

import com.localllm.chat.data.catalog.ModelCategory
import com.localllm.chat.domain.ChatMode
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptProfileJvmTest {
    @Test
    fun llama1bIdentity() {
        val prompt = PromptProfile.forAgent(
            "llama3.2-1b-q4",
            "Llama 3.2 1B",
            ModelCategory.STANDARD,
            "low",
            ChatMode.CHAT,
        )
        assertTrue(prompt.contains("Llama 3.2 running offline in Airux Pocket AI"))
    }

    @Test
    fun codingPromptDiffersFromChat() {
        val chat = PromptProfile.forAgent("qwen3-1.7b-q4", "Qwen3", ModelCategory.STANDARD, "mid", ChatMode.CHAT)
        val coding = PromptProfile.forAgent("qwen3-1.7b-q4", "Qwen3", ModelCategory.STANDARD, "mid", ChatMode.CODING)
        assertTrue(chat != coding)
    }

    @Test
    fun uncensoredPrompt() {
        val prompt = PromptProfile.forAgent(
            "dolphin3-llama3.2-1b-uncensored",
            "Dolphin",
            ModelCategory.UNCENSORED,
            "low",
            ChatMode.CHAT,
        )
        assertTrue(prompt.contains("Core rule"))
    }

    @Test
    fun visionPrompt() {
        val prompt = PromptProfile.forAgent(
            "smolvlm2-2.2b-vision",
            "SmolVLM2",
            ModelCategory.VISION,
            "mid",
            ChatMode.CHAT,
        )
        assertTrue(prompt.contains("NO photo") || prompt.contains("no image") || prompt.contains("mmproj"))
    }

    @Test
    fun everyKnownCatalogIdHasADedicatedProfileInBothModes() {
        PromptProfile.knownCatalogIds.forEach { id ->
            val category = when {
                id.contains("uncensored") -> ModelCategory.UNCENSORED
                id.contains("vision") -> ModelCategory.VISION
                else -> ModelCategory.STANDARD
            }
            ChatMode.entries.forEach { mode ->
                val prompt = PromptProfile.forAgent(id, "Model", category, "mid", mode)
                assertTrue("$id/$mode should mention the app", prompt.contains("Airux Pocket AI"))
                assertTrue("$id/$mode should not be blank", prompt.length > 200)
            }
        }
    }

    @Test
    fun unknownStandardModelFallsBackToGenericPromptWithTierHint() {
        val low = PromptProfile.forAgent("no-such-model", "Mystery", ModelCategory.STANDARD, "low", ChatMode.CHAT)
        val mid = PromptProfile.forAgent("no-such-model", "Mystery", ModelCategory.STANDARD, "mid", ChatMode.CHAT)
        val high = PromptProfile.forAgent("no-such-model", "Mystery", ModelCategory.STANDARD, "high", ChatMode.CHAT)
        assertTrue(low.contains("keep responses concise"))
        assertTrue(mid.contains("match depth to the request"))
        assertTrue(high.contains("thorough when the topic needs it"))
        assertTrue(low.contains("You are Mystery"))
    }

    @Test
    fun unknownStandardModelHasGenericCodingPrompt() {
        val prompt = PromptProfile.forAgent("no-such-model", "Mystery", ModelCategory.STANDARD, "low", ChatMode.CODING)
        assertTrue(prompt.contains("programming assistant"))
        assertTrue(prompt.contains("keep responses concise"))
    }

    @Test
    fun unknownVisionModelStillGetsTheBaseVisionRules() {
        val prompt = PromptProfile.forAgent("no-such-vlm", "Mystery VLM", ModelCategory.VISION, "high", ChatMode.CHAT)
        assertTrue(prompt.contains("mmproj"))
        assertTrue(prompt.contains("rich, detailed visual analysis"))
    }

    @Test
    fun lowTierVisionPromptAsksForShortDescriptions() {
        val prompt = PromptProfile.forAgent(
            "smolvlm2-500m-video-vision",
            "SmolVLM2 500M",
            ModelCategory.VISION,
            "low",
            ChatMode.CHAT,
        )
        assertTrue(prompt.contains("Keep image descriptions concise"))
        assertTrue(prompt.contains("short video-frame understanding"))
    }

    @Test
    fun visionCodingPromptTargetsScreenshots() {
        val prompt = PromptProfile.forAgent(
            "gemma3-4b-vision",
            "Gemma 3 4B",
            ModelCategory.VISION,
            "high",
            ChatMode.CODING,
        )
        assertTrue(prompt.contains("screenshots"))
    }

    @Test
    fun qwenBasedUncensoredModelSuppressesClosingSafetyNote() {
        val qwen = PromptProfile.forAgent(
            "dolphin3-qwen2.5-1.5b-uncensored",
            "Dolphin Qwen",
            ModelCategory.UNCENSORED,
            "mid",
            ChatMode.CHAT,
        )
        val llama = PromptProfile.forAgent(
            "dolphin3-llama3.2-1b-uncensored",
            "Dolphin Llama",
            ModelCategory.UNCENSORED,
            "low",
            ChatMode.CHAT,
        )
        assertTrue(qwen.contains("Qwen-base quirk"))
        assertTrue(llama.contains("Be concise by default"))
    }

    @Test
    fun uncensoredCodingPromptRefusesToRefuse() {
        val prompt = PromptProfile.forAgent(
            "dolphin3-llama3.2-3b-uncensored",
            "Dolphin",
            ModelCategory.UNCENSORED,
            "high",
            ChatMode.CODING,
        )
        assertTrue(prompt.contains("no refusals"))
    }
}
