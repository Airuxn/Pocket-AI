package com.localllm.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingSanitizerTest {
    @Test
    fun stripsThinkingBlock() {
        val raw = "Hello <thinking>internal reasoning</thinking> world"
        assertEquals("Hello  world", ThinkingSanitizer.stripForDisplay(raw))
    }

    @Test
    fun stripsRedactedThinkingBlock() {
        val raw = "Result: <redacted_thinking>private</redacted_thinking> done"
        assertEquals("Result:  done", ThinkingSanitizer.stripForDisplay(raw))
    }

    @Test
    fun stripsOrphanTags() {
        val raw = "<thinking>open only</thinking> close only"
        assertEquals("close only", ThinkingSanitizer.stripForDisplay(raw))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("hello", ThinkingSanitizer.stripForDisplay("  hello  "))
    }
}
