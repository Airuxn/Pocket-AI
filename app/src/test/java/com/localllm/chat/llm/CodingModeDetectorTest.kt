package com.localllm.chat.llm

import com.localllm.chat.domain.ChatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CodingModeDetectorTest {
    @Test
    fun generalQuestionUsesChatMode() {
        val mode = CodingModeDetector.resolve(
            userMessage = "What is the capital of France?",
            priorTurns = emptyList(),
            isContinue = false,
        )
        assertEquals(ChatMode.CHAT, mode)
    }

    @Test
    fun codeFenceUsesCodingMode() {
        val mode = CodingModeDetector.resolve(
            userMessage = "Fix this:\n```kotlin\nfun main()",
            priorTurns = emptyList(),
            isContinue = false,
        )
        assertEquals(ChatMode.CODING, mode)
    }

    @Test
    fun codingKeywordUsesCodingMode() {
        val mode = CodingModeDetector.resolve(
            userMessage = "Write a Kotlin function to sort a list",
            priorTurns = emptyList(),
            isContinue = false,
        )
        assertEquals(ChatMode.CODING, mode)
    }

    @Test
    fun continueFlowUsesCodingMode() {
        val mode = CodingModeDetector.resolve(
            userMessage = "anything",
            priorTurns = emptyList(),
            isContinue = true,
        )
        assertEquals(ChatMode.CODING, mode)
    }

    @Test
    fun shortFollowUpAfterCodeUsesCodingMode() {
        val prior = listOf(
            ChatTurn("user", "Build a login form"),
            ChatTurn("assistant", "```html\n<form>"),
        )
        val mode = CodingModeDetector.resolve(
            userMessage = "make it async",
            priorTurns = prior,
            isContinue = false,
        )
        assertEquals(ChatMode.CODING, mode)
    }

    @Test
    fun thanksAfterCodeStaysChatMode() {
        val prior = listOf(
            ChatTurn("user", "Build a login form"),
            ChatTurn("assistant", "```html\n<form></form>\n```"),
        )
        val mode = CodingModeDetector.resolve(
            userMessage = "Thanks!",
            priorTurns = prior,
            isContinue = false,
        )
        assertEquals(ChatMode.CHAT, mode)
    }
}