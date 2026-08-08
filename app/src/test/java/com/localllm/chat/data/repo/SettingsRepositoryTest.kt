package com.localllm.chat.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        repo = SettingsRepository(RuntimeEnvironment.getApplication())
    }

    private fun read(): SettingsState = runBlocking { repo.settings.first() }

    @Test
    fun defaultStateMatchesDocumentedValues() {
        val defaults = SettingsState()
        assertEquals(0.7f, defaults.temperature, 0.0001f)
        assertEquals(6144, defaults.contextSize)
        assertEquals(512, defaults.maxTokens)
        assertEquals("", defaults.systemPromptOverride)
        assertFalse(defaults.showThinking)
        assertTrue(defaults.memoryEnabled)
        assertNull(defaults.darkTheme)
    }

    @Test
    fun temperatureRoundTrips() = runBlocking {
        repo.updateTemperature(0.35f)
        assertEquals(0.35f, read().temperature, 0.0001f)
    }

    @Test
    fun contextSizeRoundTrips() = runBlocking {
        repo.updateContextSize(8192)
        assertEquals(8192, read().contextSize)
    }

    @Test
    fun maxTokensRoundTrips() = runBlocking {
        repo.updateMaxTokens(1024)
        assertEquals(1024, read().maxTokens)
    }

    @Test
    fun systemPromptRoundTrips() = runBlocking {
        repo.updateSystemPrompt("Be terse.")
        assertEquals("Be terse.", read().systemPromptOverride)
    }

    @Test
    fun showThinkingRoundTrips() = runBlocking {
        repo.updateShowThinking(true)
        assertTrue(read().showThinking)
        repo.updateShowThinking(false)
        assertFalse(read().showThinking)
    }

    @Test
    fun memoryEnabledRoundTrips() = runBlocking {
        repo.updateMemoryEnabled(false)
        assertFalse(read().memoryEnabled)
        repo.updateMemoryEnabled(true)
        assertTrue(read().memoryEnabled)
    }

    @Test
    fun darkThemeOverrideCanBeClearedBackToSystem() = runBlocking {
        repo.updateDarkTheme(true)
        assertEquals(true, read().darkTheme)
        repo.updateDarkTheme(false)
        assertEquals(false, read().darkTheme)
        repo.updateDarkTheme(null)
        assertNull(read().darkTheme)
    }
}
