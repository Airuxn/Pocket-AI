package com.localllm.chat.llm

import com.localllm.chat.data.db.ModelEntity
import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmErrorMessagesTest {
    @Test
    fun contextOverflowMessage() {
        val msg = LlmErrorMessages.forGenerationError(TokenGenerationResultCode.CONTEXT_OVERFLOW)
        assertTrue(msg.contains("context full"))
    }

    @Test
    fun cancelledMessage() {
        val msg = LlmErrorMessages.forGenerationError(TokenGenerationResultCode.CANCELLED)
        assertTrue(msg.contains("cancelled"))
    }

    @Test
    fun unknownErrorMessage() {
        val msg = LlmErrorMessages.forGenerationError(TokenGenerationResultCode.UNKNOWN)
        assertTrue(msg.contains("Generation failed"))
    }

    @Test
    fun loadFailureForUnknownModel() {
        val model = ModelEntity(name = "Test", filePath = "/models/test.gguf", fileSizeBytes = 0, promptFormat = "LLAMA_3")
        val msg = LlmErrorMessages.forLoadFailure(Exception("boom"), model)
        assertTrue(msg.contains("boom"))
    }

    @Test
    fun loadFailureForOldQwen() {
        val model = ModelEntity(name = "Old Qwen", filePath = "/models/qwen3.5-0.8b.gguf", fileSizeBytes = 0, promptFormat = "CHAT_ML")
        val msg = LlmErrorMessages.forLoadFailure(Exception("boom"), model)
        assertTrue(msg.contains("Outdated model file"))
    }

    @Test
    fun throwableForModelNotFound() {
        val model = ModelEntity(name = "Test", filePath = "/models/test.gguf", fileSizeBytes = 0, promptFormat = "LLAMA_3")
        val msg = LlmErrorMessages.forThrowable(Exception("Model file not found"), model)
        assertTrue(msg.contains("download again"))
    }

    @Test
    fun throwableFallback() {
        val msg = LlmErrorMessages.forThrowable(null, null)
        assertTrue(msg.contains("Generation failed"))
    }
}
