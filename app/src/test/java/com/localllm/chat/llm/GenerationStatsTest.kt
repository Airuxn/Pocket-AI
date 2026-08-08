package com.localllm.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStatsTest {
    @Test
    fun statsHoldValues() {
        val stats = GenerationStats(tokensPerSecond = 12.5f, isFinal = true)
        assertEquals(12.5f, stats.tokensPerSecond, 0.001f)
        assertTrue(stats.isFinal)
    }

    @Test
    fun nonFinalStats() {
        val stats = GenerationStats(tokensPerSecond = 0f, isFinal = false)
        assertFalse(stats.isFinal)
    }
}
