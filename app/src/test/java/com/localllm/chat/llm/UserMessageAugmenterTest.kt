package com.localllm.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageAugmenterTest {
    @Test
    fun liveInfoForcesWebSearchToolHintOnLlama1b() {
        val msg = "What is the current price of Bitcoin in USD today? You must look it up online."
        assertTrue(UserMessageAugmenter.isLiveInfoRequest(msg))
        val out = UserMessageAugmenter.augment("llama3.2-1b-q4", isUncensored = false, message = msg)
        assertTrue(out.contains("web_search"))
        assertTrue(out.contains("Do not invent URLs"))
    }

    @Test
    fun simpleMathIsNotLiveInfoButGetsDigitHint() {
        assertFalse(UserMessageAugmenter.isLiveInfoRequest("What is 2+2?"))
        assertTrue(UserMessageAugmenter.isSimpleMathRequest("What is 2+2?"))
        val out = UserMessageAugmenter.augment("llama3.2-1b-q4", isUncensored = false, message = "What is 2+2?")
        assertFalse(out.contains("web_search"))
        assertTrue(out.contains("digit 4"))
    }

    @Test
    fun twelveTimesThreeGetsDigit36Hint() {
        assertTrue(UserMessageAugmenter.isSimpleMathRequest("What is 12*3? Just the number."))
        assertEquals(36, UserMessageAugmenter.simpleMathAnswer("What is 12*3?"))
        val out = UserMessageAugmenter.augment(
            "llama3.2-3b-q4",
            isUncensored = false,
            message = "What is 12*3? Just the number.",
        )
        assertTrue(out.contains("digit 36"))
    }

    @Test
    fun qwenGetsNoThinkAndLiveHint() {
        val msg = "What is the current price of Bitcoin in USD today? You must look it up online."
        val out = UserMessageAugmenter.augment("qwen3-1.7b-q4", isUncensored = false, message = msg)
        assertTrue(out.contains("/no_think"))
        assertTrue(out.contains("web_search"))
    }

    @Test
    fun simpleMathGetsDigitHintOnLlama3b() {
        val out = UserMessageAugmenter.augment("llama3.2-3b-q4", isUncensored = false, message = "What is 2+2?")
        assertTrue(out.contains("digit 4"))
    }

    @Test
    fun softBitcoinPriceTodayIsLiveInfo() {
        assertTrue(UserMessageAugmenter.isLiveInfoRequest("What's the Bitcoin price in USD today?"))
        assertTrue(UserMessageAugmenter.isLiveInfoRequest("What's the weather in Amsterdam right now?"))
    }

    @Test
    fun howToRequestForUncensoredAddsStepHint() {
        assertTrue(UserMessageAugmenter.isHowToRequest("How do I bake bread?"))
        val out = UserMessageAugmenter.augment(
            catalogId = "dolphin3-qwen2.5-1.5b-uncensored",
            isUncensored = true,
            message = "How do I bake bread?",
        )
        assertTrue(out.contains("numbered steps"))
        assertTrue(out.contains("materials"))
    }

    @Test
    fun visionPhotoQuestionWithoutAttachmentAddsWarning() {
        val out = UserMessageAugmenter.augment(
            catalogId = "smolvlm2-500m-video-vision",
            isUncensored = false,
            message = "What is in this photo?",
            hasPhotoAttachment = false,
        )
        assertTrue(out.contains("No image is attached"))
    }

    @Test
    fun identityQuestionOnLlamaGetsPocketAiHint() {
        val out = UserMessageAugmenter.augment(
            catalogId = "llama3.2-1b-q4",
            isUncensored = false,
            message = "What model are you?",
        )
        assertTrue(out.contains("Llama 3.2 running offline in Airux Pocket AI"))
    }

    @Test
    fun simpleMathDivisionAndEdgeCases() {
        assertEquals(4, UserMessageAugmenter.simpleMathAnswer("8 / 2"))
        assertEquals(null, UserMessageAugmenter.simpleMathAnswer("8 / 3"))
        assertEquals(null, UserMessageAugmenter.simpleMathAnswer("hello"))
        assertEquals(null, UserMessageAugmenter.simpleMathAnswer("1000000 * 1000000"))
    }

    @Test
    fun unmatchedCatalogReturnsMessageUnchanged() {
        val msg = "Just chat"
        assertEquals(msg, UserMessageAugmenter.augment("unknown", isUncensored = false, message = msg))
    }
}
