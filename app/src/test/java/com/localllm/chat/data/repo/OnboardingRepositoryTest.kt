package com.localllm.chat.data.repo

import androidx.room.Room
import com.localllm.chat.data.db.AppDatabase
import com.localllm.chat.data.db.ConversationEntity
import com.localllm.chat.data.db.ModelEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: OnboardingRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = OnboardingRepository(context, db.modelDao(), db.conversationDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun read(): OnboardingState = runBlocking { repo.state.first() }

    @Test
    fun defaultStateMatchesDocumentedValues() {
        val defaults = OnboardingState()
        assertFalse(defaults.complete)
        assertEquals("en", defaults.language)
        assertEquals("mid", defaults.tier)
        assertEquals("sensored", defaults.llmType)
        assertFalse(defaults.unsensoredUnlocked)
    }

    @Test
    fun existingModelSkipsOnboarding() = runBlocking {
        db.modelDao().insert(
            ModelEntity(name = "M", filePath = "/m.gguf", fileSizeBytes = 0, promptFormat = "LLAMA_3"),
        )
        assertFalse(repo.needsOnboarding())
    }

    @Test
    fun existingConversationSkipsOnboarding() = runBlocking {
        db.conversationDao().insert(ConversationEntity(title = "Legacy chat"))
        assertFalse(repo.needsOnboarding())
    }

    /**
     * DataStore keeps one process-wide instance, and "complete" can only move forward,
     * so the whole first-run flow is asserted in a single ordered test.
     */
    @Test
    fun firstRunFlowStoresSelectionsAndCompletes() = runBlocking {
        assertTrue(repo.needsOnboarding())

        repo.setLanguage("nl")
        repo.setTier("high")
        repo.setLlmType("unsensored")
        repo.setUnsensoredUnlocked(true)

        val selected = read()
        assertEquals("nl", selected.language)
        assertEquals("high", selected.tier)
        assertEquals("unsensored", selected.llmType)
        assertTrue(selected.unsensoredUnlocked)
        assertFalse(selected.complete)

        repo.markComplete()
        assertTrue(read().complete)
        assertFalse(repo.needsOnboarding())
    }
}
