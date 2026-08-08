package com.localllm.chat.data.repo

import com.localllm.chat.data.db.ConversationDao
import com.localllm.chat.data.db.ConversationEntity
import com.localllm.chat.data.db.MessageDao
import com.localllm.chat.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {
    private class FakeConversationDao : ConversationDao {
        val inserted = mutableListOf<ConversationEntity>()
        val deleted = mutableListOf<Long>()
        var returnedById: ConversationEntity? = null

        override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(inserted.toList())

        override suspend fun insert(conversation: ConversationEntity): Long {
            inserted.add(conversation)
            return inserted.size.toLong()
        }

        override suspend fun getById(id: Long): ConversationEntity? = returnedById

        override suspend fun delete(id: Long) {
            deleted.add(id)
        }
    }

    private class FakeMessageDao : MessageDao {
        val inserted = mutableListOf<MessageEntity>()
        var observedConversationId: Long? = null

        override fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>> {
            observedConversationId = conversationId
            return flowOf(inserted.filter { it.conversationId == conversationId })
        }

        override suspend fun insert(message: MessageEntity): Long {
            inserted.add(message)
            return inserted.size.toLong()
        }
    }

    @Test
    fun createConversationInserts() = runBlocking {
        val convDao = FakeConversationDao()
        val repo = ChatRepository(convDao, FakeMessageDao())
        val id = repo.createConversation("My chat", "CODING")
        assertEquals(1L, id)
        assertEquals(1, convDao.inserted.size)
        assertEquals("My chat", convDao.inserted[0].title)
        assertEquals("CODING", convDao.inserted[0].mode)
    }

    @Test
    fun addMessageInserts() = runBlocking {
        val msgDao = FakeMessageDao()
        val repo = ChatRepository(FakeConversationDao(), msgDao)
        val id = repo.addMessage(5L, "user", "hi", "thinking")
        assertEquals(1L, id)
        assertEquals(1, msgDao.inserted.size)
        assertEquals(5L, msgDao.inserted[0].conversationId)
        assertEquals("hi", msgDao.inserted[0].content)
        assertEquals("thinking", msgDao.inserted[0].thinkingContent)
    }

    @Test
    fun deleteConversationCallsDao() = runBlocking {
        val convDao = FakeConversationDao()
        val repo = ChatRepository(convDao, FakeMessageDao())
        repo.deleteConversation(3L)
        assertTrue(convDao.deleted.contains(3L))
    }

    @Test
    fun getConversationReturnsDaoResult() = runBlocking {
        val convDao = FakeConversationDao()
        convDao.returnedById = ConversationEntity(id = 8L, title = "Found")
        val repo = ChatRepository(convDao, FakeMessageDao())
        val found = repo.getConversation(8L)
        assertEquals("Found", found?.title)
    }
}
