package com.localllm.chat.data

import androidx.room.Room
import com.localllm.chat.data.db.AppDatabase
import com.localllm.chat.data.db.ConversationEntity
import com.localllm.chat.data.db.MemoryEntity
import com.localllm.chat.data.db.MessageEntity
import com.localllm.chat.data.db.ModelEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun conversationDaoRoundTrip() = runBlocking {
        val id = db.conversationDao().insert(
            ConversationEntity(title = "Test chat", mode = "CHAT"),
        )
        assertTrue(id > 0)
        val loaded = db.conversationDao().getById(id)
        assertNotNull(loaded)
        assertEquals("Test chat", loaded!!.title)
        assertEquals("CHAT", loaded.mode)
    }

    @Test
    fun messageDaoRoundTrip() = runBlocking {
        val convId = db.conversationDao().insert(ConversationEntity(title = "Chat"))
        val msgId = db.messageDao().insert(
            MessageEntity(conversationId = convId, role = "user", content = "hi"),
        )
        assertTrue(msgId > 0)
    }

    @Test
    fun modelDaoRoundTrip() = runBlocking {
        val id = db.modelDao().insert(
            ModelEntity(name = "M", filePath = "/m.gguf", fileSizeBytes = 0, promptFormat = "LLAMA_3"),
        )
        val model = db.modelDao().getById(id)
        assertNotNull(model)
        assertEquals("M", model!!.name)
    }

    @Test
    fun memoryDaoRoundTrip() = runBlocking {
        val id = db.memoryDao().insert(MemoryEntity(content = "remember this"))
        assertTrue(id > 0)
    }
}
