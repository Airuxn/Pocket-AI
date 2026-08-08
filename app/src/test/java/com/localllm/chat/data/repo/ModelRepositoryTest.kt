package com.localllm.chat.data.repo

import android.content.Context
import androidx.room.Room
import com.localllm.chat.data.catalog.DownloadableModel
import com.localllm.chat.data.db.AppDatabase
import com.localllm.chat.data.db.ModelDao
import com.localllm.chat.data.db.ModelEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: ModelDao
    private lateinit var repo: ModelRepository

    /** Minimal but valid GGUF payload so [com.localllm.chat.llm.GgufValidator] accepts it. */
    private fun writeGguf(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(4096))
    }

    private fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.modelDao()
        repo = ModelRepository(context, dao)
        modelsDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        modelsDir().deleteRecursively()
    }

    private suspend fun insertModel(
        name: String = "Model",
        fileName: String = "model.gguf",
        active: Boolean = false,
        catalogId: String? = null,
    ): ModelEntity {
        val file = File(modelsDir(), fileName)
        writeGguf(file)
        val id = dao.insert(
            ModelEntity(
                name = name,
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                promptFormat = "LLAMA_3",
                catalogId = catalogId,
                isActive = active,
            ),
        )
        return dao.getById(id)!!
    }

    @Test
    fun catalogIsLoadedFromAssets() {
        assertTrue(repo.catalog.isNotEmpty())
        assertTrue(repo.catalog.all { it.fileName.endsWith(".gguf") })
    }

    @Test
    fun observeInstalledEmitsInsertedModels() = runBlocking {
        insertModel(name = "First")
        val installed = repo.observeInstalled().first()
        assertEquals(1, installed.size)
        assertEquals("First", installed[0].name)
    }

    @Test
    fun getActiveModelReturnsNullWhenNoneInstalled() = runBlocking {
        assertNull(repo.getActiveModel())
    }

    @Test
    fun getActiveModelActivatesFirstWhenNoneMarked() = runBlocking {
        insertModel(name = "Only", fileName = "only.gguf")
        val active = repo.getActiveModel()
        assertNotNull(active)
        assertEquals("Only", active!!.name)
        assertNotNull(dao.getActive())
    }

    @Test
    fun getActiveModelReturnsMarkedModel() = runBlocking {
        insertModel(name = "Inactive", fileName = "a.gguf")
        insertModel(name = "Active", fileName = "b.gguf", active = true)
        assertEquals("Active", repo.getActiveModel()!!.name)
    }

    @Test
    fun setActiveMovesActiveFlag() = runBlocking {
        val first = insertModel(name = "First", fileName = "a.gguf", active = true)
        val second = insertModel(name = "Second", fileName = "b.gguf")
        repo.setActive(second)
        assertEquals(second.id, dao.getActive()!!.id)
        assertFalse(dao.getById(first.id)!!.isActive)
    }

    @Test
    fun savePromptAndResetPrompt() = runBlocking {
        val model = insertModel()
        repo.savePrompt(model.id, "  You are a pirate.  ")
        val saved = dao.getById(model.id)!!
        assertEquals("You are a pirate.", saved.systemPrompt)
        assertTrue(saved.hasCustomPrompt)

        repo.resetPrompt(model.id)
        val reset = dao.getById(model.id)!!
        assertNull(reset.systemPrompt)
        assertFalse(reset.hasCustomPrompt)
    }

    @Test
    fun deleteModelRemovesRowAndFile() = runBlocking {
        val model = insertModel(fileName = "gone.gguf")
        repo.deleteModel(model)
        assertNull(dao.getById(model.id))
        assertFalse(File(model.filePath).exists())
    }

    @Test
    fun deleteActiveModelPromotesRemainingModel() = runBlocking {
        val active = insertModel(name = "Active", fileName = "a.gguf", active = true)
        val other = insertModel(name = "Other", fileName = "b.gguf")
        repo.deleteModel(active)
        assertEquals(other.id, dao.getActive()!!.id)
    }

    @Test
    fun deleteVisionModelAlsoRemovesMmprojFile() = runBlocking {
        val entry = repo.catalog.firstOrNull { it.requiresMmproj } ?: return@runBlocking
        val main = File(modelsDir(), entry.fileName).also { writeGguf(it) }
        val mmproj = File(modelsDir(), entry.mmprojFileName!!).also { writeGguf(it) }
        val id = dao.insert(
            ModelEntity(
                name = entry.name,
                filePath = main.absolutePath,
                fileSizeBytes = main.length(),
                promptFormat = entry.promptFormat,
                catalogId = entry.id,
            ),
        )
        repo.deleteModel(dao.getById(id)!!)
        assertFalse(main.exists())
        assertFalse(mmproj.exists())
    }

    @Test
    fun downloadReusesExistingFileAndRegistersModel() = runBlocking {
        val model = DownloadableModel(
            id = "test-model",
            name = "Test Model",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "test-model.gguf",
            downloadUrl = "https://example.invalid/test-model.gguf",
            promptFormat = "LLAMA_3",
        )
        writeGguf(File(modelsDir(), model.fileName))
        val progress = mutableListOf<Pair<Int, String>>()

        val entity = repo.download(model) { pct, label -> progress.add(pct to label) }

        assertEquals("Test Model", entity.name)
        assertEquals("test-model", entity.catalogId)
        assertTrue(entity.isActive)
        assertFalse(entity.hasCustomPrompt)
        assertTrue(progress.any { it.second.contains("already downloaded") })
        assertEquals(100, progress.last().first)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun downloadUpdatesMetadataOfAlreadyInstalledModel() = runBlocking {
        val model = DownloadableModel(
            id = "test-model",
            name = "Renamed Model",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "test-model.gguf",
            downloadUrl = "https://example.invalid/test-model.gguf",
            promptFormat = "GEMMA",
        )
        val file = File(modelsDir(), model.fileName).also { writeGguf(it) }
        val existingId = dao.insert(
            ModelEntity(
                name = "Old name",
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                promptFormat = "LLAMA_3",
            ),
        )

        val entity = repo.download(model, systemPrompt = null) { _, _ -> }

        assertEquals(existingId, entity.id)
        assertEquals("Renamed Model", entity.name)
        val stored = dao.getById(existingId)!!
        assertEquals("GEMMA", stored.promptFormat)
        assertEquals("test-model", stored.catalogId)
        assertTrue(stored.isActive)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun downloadWithCustomPromptMarksHasCustomPrompt() = runBlocking {
        val model = DownloadableModel(
            id = "prompted",
            name = "Prompted",
            description = "desc",
            sizeLabel = "1 GB",
            minRamLabel = "4 GB",
            fileName = "prompted.gguf",
            downloadUrl = "https://example.invalid/prompted.gguf",
            promptFormat = "LLAMA_3",
        )
        writeGguf(File(modelsDir(), model.fileName))
        val entity = repo.download(model, systemPrompt = "You only speak in haiku.") { _, _ -> }
        assertTrue(entity.hasCustomPrompt)
    }

    @Test
    fun reconcileRegistersModelsFoundOnDisk() = runBlocking {
        val entry = repo.catalog.first { !it.requiresMmproj }
        writeGguf(File(modelsDir(), entry.fileName))

        assertTrue(repo.reconcileInstallState())

        val stored = dao.getAll()
        assertEquals(1, stored.size)
        assertEquals(entry.id, stored[0].catalogId)
        assertNotNull(dao.getActive())
        // Second run finds nothing new.
        assertFalse(repo.reconcileInstallState())
    }

    @Test
    fun syncFixesStaleCatalogMetadata() = runBlocking {
        val entry = repo.catalog.first { !it.requiresMmproj }
        val file = File(modelsDir(), entry.fileName).also { writeGguf(it) }
        val id = dao.insert(
            ModelEntity(
                name = "Stale name",
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                promptFormat = "STALE_FORMAT",
                catalogId = null,
            ),
        )

        assertTrue(repo.syncInstalledWithCatalog())

        val updated = dao.getById(id)!!
        assertEquals(entry.name, updated.name)
        assertEquals(entry.promptFormat, updated.promptFormat)
        assertEquals(entry.id, updated.catalogId)
    }

    @Test
    fun syncClearsLegacyOnboardingLanguagePrompt() = runBlocking {
        val entry = repo.catalog.first { !it.requiresMmproj }
        val file = File(modelsDir(), entry.fileName).also { writeGguf(it) }
        val id = dao.insert(
            ModelEntity(
                name = entry.name,
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                promptFormat = entry.promptFormat,
                catalogId = entry.id,
                systemPrompt = "You are a helpful assistant. Always respond in Nederlands " +
                    "(language code nl). Be concise and natural.",
                hasCustomPrompt = true,
            ),
        )

        assertTrue(repo.syncInstalledWithCatalog())

        val updated = dao.getById(id)!!
        assertFalse(updated.hasCustomPrompt)
        assertNull(updated.systemPrompt)
    }

    @Test
    fun syncDeletesOrphanMmprojFiles() = runBlocking {
        val orphan = File(modelsDir(), "mmproj-not-in-catalog.gguf").also { writeGguf(it) }
        assertTrue(repo.syncInstalledWithCatalog())
        assertFalse(orphan.exists())
    }

    @Test
    fun syncReportsNoChangeWhenNothingInstalled() = runBlocking {
        assertFalse(repo.syncInstalledWithCatalog())
    }

    private fun ggufBody(extraBytes: Int = 4096): Buffer =
        Buffer().write(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(extraBytes))

    private fun remoteModel(
        server: MockWebServer,
        fileName: String = "remote.gguf",
        expectedExactBytes: Long = 0,
        expectedMinBytes: Long = 0,
        mmprojFileName: String? = null,
    ) = DownloadableModel(
        id = "remote",
        name = "Remote Model",
        description = "desc",
        sizeLabel = "1 GB",
        minRamLabel = "4 GB",
        fileName = fileName,
        downloadUrl = server.url("/$fileName").toString(),
        promptFormat = "LLAMA_3",
        category = if (mmprojFileName != null) "vision" else "standard",
        mmprojFileName = mmprojFileName,
        mmprojDownloadUrl = mmprojFileName?.let { server.url("/$it").toString() },
        expectedExactBytes = expectedExactBytes,
        expectedMinBytes = expectedMinBytes,
    )

    @Test
    fun downloadStreamsFileAndReportsProgress() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(ggufBody()))
        server.start()
        try {
            val model = remoteModel(server)
            val progress = mutableListOf<Int>()

            val entity = repo.download(model) { pct -> progress.add(pct) }

            val file = File(modelsDir(), model.fileName)
            assertTrue(file.exists())
            assertEquals(4100L, file.length())
            assertEquals(file.absolutePath, entity.filePath)
            assertEquals(4100L, entity.fileSizeBytes)
            assertTrue(entity.isActive)
            assertEquals(100, progress.last())
            assertTrue(progress.any { it in 1..99 })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun downloadWithoutContentLengthStillCompletes() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setChunkedBody(ggufBody(), 512))
        server.start()
        try {
            val labels = mutableListOf<String>()
            repo.download(remoteModel(server)) { _, label -> labels.add(label) }
            assertTrue(labels.any { it.startsWith("Downloading Remote Model…") })
            assertTrue(File(modelsDir(), "remote.gguf").exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpErrorAbortsTheDownload() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val error = runCatching { repo.download(remoteModel(server)) { _, _ -> } }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("HTTP 404"))
            assertEquals(0, dao.getAll().size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun truncatedDownloadIsDeletedInsteadOfKept() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(ggufBody(1024)))
        server.start()
        try {
            val model = remoteModel(server, expectedExactBytes = 99_999)
            val error = runCatching { repo.download(model) { _, _ -> } }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("size mismatch"))
            assertFalse(File(modelsDir(), model.fileName).exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun downloadBelowMinimumSizeIsRejected() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(ggufBody(1024)))
        server.start()
        try {
            val model = remoteModel(server, expectedMinBytes = 50_000)
            val error = runCatching { repo.download(model) { _, _ -> } }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("Download incomplete"))
            assertFalse(File(modelsDir(), model.fileName).exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun existingFileWithWrongSizeIsDownloadedAgain() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(ggufBody()))
        server.start()
        try {
            val model = remoteModel(server, expectedExactBytes = 4100)
            File(modelsDir(), model.fileName).writeBytes(ByteArray(10))

            repo.download(model) { _, _ -> }

            assertEquals(4100L, File(modelsDir(), model.fileName).length())
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun visionModelDownloadsBothWeightsAndProjector() = runBlocking {
        // A catalog projector name, so the orphan-mmproj cleanup does not consider it stale.
        val projectorName = repo.catalog.first { it.requiresMmproj }.mmprojFileName!!
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(ggufBody()))
        server.enqueue(MockResponse().setBody(ggufBody(2048)))
        server.start()
        try {
            val model = remoteModel(server, mmprojFileName = projectorName)
            val labels = mutableListOf<String>()

            repo.download(model) { _, label -> labels.add(label) }

            assertTrue(File(modelsDir(), model.fileName).exists())
            assertEquals(2052L, File(modelsDir(), projectorName).length())
            assertTrue(labels.any { it.contains("vision projector") })
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
