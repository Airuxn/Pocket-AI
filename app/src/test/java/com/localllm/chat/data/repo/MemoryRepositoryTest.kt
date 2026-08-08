package com.localllm.chat.data.repo

import com.localllm.chat.data.db.MemoryDao
import com.localllm.chat.data.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    private class FakeMemoryDao : MemoryDao {
        val inserted = mutableListOf<MemoryEntity>()
        val deleted = mutableListOf<Long>()
        var updatedId: Long? = null
        var updatedContent: String? = null

        override fun observeAll(): Flow<List<MemoryEntity>> = flowOf(emptyList())
        override fun observeForPrompt(): Flow<List<MemoryEntity>> = flowOf(emptyList())

        override suspend fun insert(memory: MemoryEntity): Long {
            inserted.add(memory)
            return 1L
        }

        override suspend fun update(id: Long, content: String, updatedAt: Long) {
            updatedId = id
            updatedContent = content
        }

        override suspend fun delete(id: Long) {
            deleted.add(id)
        }
    }

    @Test
    fun addTrimsAndInserts() = runBlocking {
        val dao = FakeMemoryDao()
        val repo = MemoryRepository(dao)
        val id = repo.add("  hello  ", sourceConversationId = 42L)
        assertEquals(1L, id)
        assertEquals(1, dao.inserted.size)
        assertEquals("hello", dao.inserted[0].content)
        assertEquals(42L, dao.inserted[0].sourceConversationId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun addEmptyThrows() {
        runBlocking {
            MemoryRepository(FakeMemoryDao()).add("   ")
        }
    }

    @Test
    fun updateTrimsAndCallsDao() = runBlocking {
        val dao = FakeMemoryDao()
        val repo = MemoryRepository(dao)
        repo.update(7L, "  updated  ")
        assertEquals(7L, dao.updatedId)
        assertEquals("updated", dao.updatedContent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateEmptyThrows() {
        runBlocking {
            MemoryRepository(FakeMemoryDao()).update(7L, "   ")
        }
    }

    @Test
    fun deleteCallsDao() = runBlocking {
        val dao = FakeMemoryDao()
        val repo = MemoryRepository(dao)
        repo.delete(9L)
        assertTrue(dao.deleted.contains(9L))
    }
}
