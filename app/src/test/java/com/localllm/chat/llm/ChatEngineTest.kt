package com.localllm.chat.llm

import com.localllm.chat.data.db.ModelEntity
import com.localllm.chat.data.repo.SettingsRepository
import com.localllm.chat.data.repo.SettingsState
import com.localllm.chat.domain.ChatMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatEngineTest {
    @Test
    fun resolveSystemPromptUsesCatalogProfile() {
        val context = RuntimeEnvironment.getApplication()
        val settingsRepo = SettingsRepository(context)
        val llmRuntime = LlmRuntime(context, settingsRepo)
        val engine = ChatEngine(context, llmRuntime)

        val model = ModelEntity(
            name = "Llama 3.2 1B",
            filePath = "/models/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 0,
            promptFormat = "LLAMA_3",
            catalogId = "llama3.2-1b-q4",
        )
        val prompt = engine.resolveSystemPrompt(
            model = model,
            mode = ChatMode.CHAT,
            settings = SettingsState(),
            memories = emptyList(),
            onboardingLanguagePrompt = null,
        )
        assertTrue(prompt.contains("Llama 3.2 running offline in Airux Pocket AI"))
    }

    @Test
    fun resolveSystemPromptUsesCustomPromptOverride() {
        val context = RuntimeEnvironment.getApplication()
        val settingsRepo = SettingsRepository(context)
        val llmRuntime = LlmRuntime(context, settingsRepo)
        val engine = ChatEngine(context, llmRuntime)

        val model = ModelEntity(
            name = "Custom",
            filePath = "/models/custom.gguf",
            fileSizeBytes = 0,
            promptFormat = "LLAMA_3",
            hasCustomPrompt = true,
            systemPrompt = "You are a pirate.",
        )
        val prompt = engine.resolveSystemPrompt(
            model = model,
            mode = ChatMode.CHAT,
            settings = SettingsState(),
            memories = emptyList(),
            onboardingLanguagePrompt = null,
        )
        assertTrue(prompt.contains("pirate"))
    }

    @Test
    fun resolveSystemPromptAppendsLanguagePrompt() {
        val context = RuntimeEnvironment.getApplication()
        val settingsRepo = SettingsRepository(context)
        val llmRuntime = LlmRuntime(context, settingsRepo)
        val engine = ChatEngine(context, llmRuntime)

        val model = ModelEntity(
            name = "Llama 3.2 1B",
            filePath = "/models/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileSizeBytes = 0,
            promptFormat = "LLAMA_3",
            catalogId = "llama3.2-1b-q4",
        )
        val lang = "Language: always respond in Nederlands"
        val prompt = engine.resolveSystemPrompt(
            model = model,
            mode = ChatMode.CHAT,
            settings = SettingsState(),
            memories = emptyList(),
            onboardingLanguagePrompt = lang,
        )
        assertTrue(prompt.contains("Nederlands"))
    }
}
