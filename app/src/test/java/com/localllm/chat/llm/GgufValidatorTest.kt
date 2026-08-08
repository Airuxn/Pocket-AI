package com.localllm.chat.llm

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class GgufValidatorTest {
    @Test
    fun rejectsMissingFile() {
        try {
            GgufValidator.validate("/tmp/nonexistent-pocket-ai-test.gguf")
            fail("Expected exception for missing file")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    @Test
    fun rejectsTooSmallFile() {
        val temp = File.createTempFile("tiny", ".gguf")
        temp.writeBytes("x".toByteArray())
        try {
            try {
                GgufValidator.validate(temp.absolutePath)
                fail("Expected exception for tiny file")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("too small"))
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun acceptsValidGguf() {
        val temp = File.createTempFile("valid", ".gguf")
        val magic = byteArrayOf(0x47, 0x47, 0x55, 0x46) // GGUF
        temp.writeBytes(magic + ByteArray(2048) { 0 })
        try {
            GgufValidator.validate(temp.absolutePath)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun rejectsHtmlFile() {
        val temp = File.createTempFile("html", ".gguf")
        // Make it large enough to pass the size check, then fail the magic check.
        temp.writeText("<html><body>not a model " + "x".repeat(1200) + "</body></html>")
        try {
            try {
                GgufValidator.validate(temp.absolutePath)
                fail("Expected exception for HTML file")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Not a valid GGUF"))
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun rejectsExactSizeMismatch() {
        val temp = File.createTempFile("valid", ".gguf")
        val magic = byteArrayOf(0x47, 0x47, 0x55, 0x46) // GGUF
        temp.writeBytes(magic + ByteArray(2048) { 0 })
        try {
            try {
                GgufValidator.validate(temp.absolutePath, expectedExactBytes = 1234)
                fail("Expected exception for size mismatch")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("size mismatch"))
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun rejectsTooSmallForMinBytes() {
        val temp = File.createTempFile("valid", ".gguf")
        val magic = byteArrayOf(0x47, 0x47, 0x55, 0x46) // GGUF
        // 4 + 1024 = 1028 bytes: > 1024 threshold but < 2048 expectedMinBytes.
        temp.writeBytes(magic + ByteArray(1024) { 0 })
        try {
            try {
                GgufValidator.validate(temp.absolutePath, expectedMinBytes = 2048)
                fail("Expected exception for incomplete file")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("incomplete"))
            }
        } finally {
            temp.delete()
        }
    }
}
