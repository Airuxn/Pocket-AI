package com.localllm.chat.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatModeTest {
    @Test
    fun fromStoredReturnsChatForUnknown() {
        assertEquals(ChatMode.CHAT, ChatMode.fromStored("INVALID"))
    }

    @Test
    fun fromStoredReturnsCoding() {
        assertEquals(ChatMode.CODING, ChatMode.fromStored("CODING"))
    }

    @Test
    fun fromStoredReturnsChat() {
        assertEquals(ChatMode.CHAT, ChatMode.fromStored("CHAT"))
    }
}
