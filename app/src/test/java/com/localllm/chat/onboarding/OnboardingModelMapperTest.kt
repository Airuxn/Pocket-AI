package com.localllm.chat.onboarding

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingModelMapperTest {
    @Test
    fun systemPromptForLanguageContainsLanguage() {
        val prompt = OnboardingModelMapper.systemPromptForLanguage("nl")
        assertTrue(prompt.contains("Nederlands"))
        assertTrue(prompt.contains("code nl"))
    }

    @Test
    fun catalogModelForSelection() {
        val context = RuntimeEnvironment.getApplication()
        val model = OnboardingModelMapper.catalogModelFor(context, llmType = "chat", tier = "low")
        assertTrue(model.id.isNotBlank())
    }
}
