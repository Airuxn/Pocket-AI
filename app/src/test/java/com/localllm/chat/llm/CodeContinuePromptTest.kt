package com.localllm.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeContinuePromptTest {
    @Test
    fun continuePromptKeepsPlainCodeTail() {
        val prompt = CodeContinuePrompt.buildContinuePrompt("  fun main() {\n  println(1)  ")
        assertTrue(prompt.startsWith("Continue the code EXACTLY"))
        assertTrue(prompt.contains("fun main() {"))
        assertTrue(prompt.contains("println(1)"))
    }

    @Test
    fun continuePromptStripsFenceAndLanguageTag() {
        val prompt = CodeContinuePrompt.buildContinuePrompt("Here you go:\n```kotlin\nval x = 1\n")
        assertTrue(prompt.contains("val x = 1"))
        assertFalse(prompt.contains("```"))
        assertFalse(prompt.contains("kotlin\n"))
        assertFalse(prompt.contains("Here you go:"))
    }

    @Test
    fun continuePromptKeepsOnlyTheLastCharacters() {
        val prompt = CodeContinuePrompt.buildContinuePrompt("A".repeat(2000) + "TAILMARKER")
        assertTrue(prompt.contains("TAILMARKER"))
        assertTrue(prompt.length < 2000)
    }

    @Test
    fun continueTriggerIsDetectedCaseInsensitively() {
        val prompt = CodeContinuePrompt.buildContinuePrompt("val x = 1")
        assertTrue(CodeContinuePrompt.isContinueTrigger(prompt))
        assertTrue(CodeContinuePrompt.isContinueTrigger("continue the code exactly where it stopped"))
        assertFalse(CodeContinuePrompt.isContinueTrigger("Please write a function"))
    }

    @Test
    fun oddNumberOfFencesMeansIncompleteBlock() {
        assertTrue(CodeContinuePrompt.hasIncompleteCodeFence("```kotlin\nval x = 1"))
        assertFalse(CodeContinuePrompt.hasIncompleteCodeFence("```kotlin\nval x = 1\n```"))
        assertFalse(CodeContinuePrompt.hasIncompleteCodeFence("no code here"))
    }

    @Test
    fun emptyCodeBlockIsDetected() {
        assertTrue(CodeContinuePrompt.hasEmptyCodeBlock("``````"))
        assertTrue(CodeContinuePrompt.hasEmptyCodeBlock("```python\n```"))
        assertFalse(CodeContinuePrompt.hasEmptyCodeBlock("```python\nprint(1)\n```"))
    }

    @Test
    fun unclosedHtmlDocumentIsDetected() {
        assertTrue(CodeContinuePrompt.looksIncompleteHtml("<html><body>hi"))
        assertTrue(CodeContinuePrompt.looksIncompleteHtml("<!DOCTYPE html><body>hi"))
        assertFalse(CodeContinuePrompt.looksIncompleteHtml("<html><body>hi</body></html>"))
        assertFalse(CodeContinuePrompt.looksIncompleteHtml("just prose"))
    }

    @Test
    fun continueIsOfferedForAnyIncompleteOutput() {
        assertTrue(CodeContinuePrompt.shouldOfferContinue("```kotlin\nval x = 1"))
        assertTrue(CodeContinuePrompt.shouldOfferContinue("<html><body>hi"))
        assertTrue(CodeContinuePrompt.shouldOfferContinue("```js\n```"))
        assertFalse(CodeContinuePrompt.shouldOfferContinue("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun fenceWithoutNewlineFallsBackToRemainingText() {
        val prompt = CodeContinuePrompt.buildContinuePrompt("```kotlin val x = 1")
        assertEquals(
            "Continue the code EXACTLY where it stopped. Output ONLY the remaining code — " +
                "no repetition, no explanation. Do not restart from the beginning.\n\n" +
                "The code ended with:\nkotlin val x = 1",
            prompt,
        )
    }
}
