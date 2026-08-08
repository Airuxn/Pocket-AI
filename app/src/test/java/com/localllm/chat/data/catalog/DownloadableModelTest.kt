package com.localllm.chat.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadableModelTest {
    @Test
    fun standardDefaults() {
        val model = DownloadableModel(
            id = "test",
            name = "Test",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "test.gguf",
            downloadUrl = "https://example.com/test.gguf",
            promptFormat = "LLAMA_3",
            category = "standard",
            tier = "low",
        )
        assertEquals(PromptFormatKind.LLAMA_3, model.promptFormatKind)
        assertEquals(ModelCategory.STANDARD, model.modelCategory)
        assertEquals(DeviceTier.LOW, model.deviceTier)
        assertFalse(model.isUncensored)
        assertFalse(model.isVision)
        assertFalse(model.requiresMmproj)
    }

    @Test
    fun visionWithMmproj() {
        val model = DownloadableModel(
            id = "vision",
            name = "Vision",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "vision.gguf",
            downloadUrl = "https://example.com/vision.gguf",
            promptFormat = "GEMMA",
            category = "vision",
            tier = "high",
            mmprojFileName = "mmproj.gguf",
            mmprojDownloadUrl = "https://example.com/mmproj.gguf",
        )
        assertEquals(PromptFormatKind.GEMMA, model.promptFormatKind)
        assertEquals(ModelCategory.VISION, model.modelCategory)
        assertEquals(DeviceTier.HIGH, model.deviceTier)
        assertTrue(model.isVision)
        assertTrue(model.requiresMmproj)
    }

    @Test
    fun chatMlFallbackForUnknownFormat() {
        val model = DownloadableModel(
            id = "test",
            name = "Test",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "test.gguf",
            downloadUrl = "https://example.com/test.gguf",
            promptFormat = "UNKNOWN",
        )
        assertEquals(PromptFormatKind.CHAT_ML, model.promptFormatKind)
    }

    @Test
    fun isFullyInstalledRequiresMainFile() {
        val model = DownloadableModel(
            id = "test",
            name = "Test",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "test.gguf",
            downloadUrl = "https://example.com/test.gguf",
            promptFormat = "LLAMA_3",
        )
        val dir = File.createTempFile("models", "")
        dir.delete()
        dir.mkdirs()
        try {
            assertFalse(ModelCatalog.isFullyInstalled(model, dir))
            val main = File(dir, "test.gguf")
            main.writeBytes(ByteArray(1024))
            assertTrue(ModelCatalog.isFullyInstalled(model, dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun isFullyInstalledRequiresMmprojForVision() {
        val model = DownloadableModel(
            id = "vision",
            name = "Vision",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "vision.gguf",
            downloadUrl = "https://example.com/vision.gguf",
            promptFormat = "LLAMA_3",
            category = "vision",
            mmprojFileName = "mmproj.gguf",
            mmprojDownloadUrl = "https://example.com/mmproj.gguf",
        )
        val dir = File.createTempFile("models", "")
        dir.delete()
        dir.mkdirs()
        try {
            File(dir, "vision.gguf").writeBytes(ByteArray(1024))
            assertFalse(ModelCatalog.isFullyInstalled(model, dir))
            File(dir, "mmproj.gguf").writeBytes(ByteArray(1024))
            assertTrue(ModelCatalog.isFullyInstalled(model, dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
