package com.localllm.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentAugmenterTest {
    @Test
    fun returnsMessageWithoutPhoto() {
        val msg = "Describe this image"
        assertEquals(msg, AttachmentAugmenter.withPhotoMarker(msg, hasPhoto = false))
    }

    @Test
    fun returnsMessageWithPhoto() {
        val msg = "Describe this image"
        assertEquals(msg, AttachmentAugmenter.withPhotoMarker(msg, hasPhoto = true))
    }
}
