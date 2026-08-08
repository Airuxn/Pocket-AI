package com.localllm.chat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolDefinitionsTest {
    @Test
    fun webSearchDefinition() {
        val defs = NativeToolDefinitions.forNativeTools(listOf("web_search"))
        assertEquals(1, defs.size)
        assertEquals("web_search", defs[0].name)
        assertEquals("Search the web for current information.", defs[0].description)
    }

    @Test
    fun ignoresUnknownTools() {
        val defs = NativeToolDefinitions.forNativeTools(listOf("unknown"))
        assertTrue(defs.isEmpty())
    }

    @Test
    fun filtersKnownTools() {
        val defs = NativeToolDefinitions.forNativeTools(listOf("web_search", "unknown"))
        assertEquals(1, defs.size)
        assertEquals("web_search", defs[0].name)
    }
}
