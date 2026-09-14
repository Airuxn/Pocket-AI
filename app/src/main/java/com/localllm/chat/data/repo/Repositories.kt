package com.localllm.chat.data.repo

import android.content.Context
import com.localllm.chat.data.catalog.DownloadableModel
import com.localllm.chat.data.catalog.ModelCatalog
import com.localllm.chat.data.db.ConversationEntity
import com.localllm.chat.data.db.ModelDao
import com.localllm.chat.data.db.ModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import com.localllm.chat.llm.GgufValidator
import com.localllm.chat.llm.LanguagePrompt
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ModelRepository(
    private val context: Context,
    private val modelDao: ModelDao,
) {
    val catalog: List<DownloadableModel> by lazy { ModelCatalog.all(context) }

    fun observeInstalled(): Flow<List<ModelEntity>> = modelDao.observeAll()

    suspend fun getActiveModel(): ModelEntity? = withContext(Dispatchers.IO) {
        modelDao.getActive() ?: modelDao.getAll().firstOrNull()?.also { first ->
            modelDao.deactivateAll()
            modelDao.updateActive(first.id, true)
        }
    }

    suspend fun download(
        model: DownloadableModel,
        systemPrompt: String? = null,
        onProgress: (Int, String) -> Unit,
    ): ModelEntity =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val mainOut = downloadGguf(
                dir = dir,
                fileName = model.fileName,
                url = model.downloadUrl,
                expectedExactBytes = model.expectedExactBytes,
                expectedMinBytes = model.expectedMinBytes,
                label = model.name,
                progressShare = if (model.requiresMmproj) 0.85f else 1f,
                progressStart = 0f,
                onProgress = onProgress,
            )
            if (model.requiresMmproj) {
                downloadGguf(
                    dir = dir,
                    fileName = model.mmprojFileName!!,
                    url = model.mmprojDownloadUrl!!,
                    expectedExactBytes = model.expectedMmprojExactBytes,
                    expectedMinBytes = 0,
                    label = "${model.name} (vision projector)",
                    progressShare = 0.15f,
                    progressStart = 0.85f,
                    onProgress = onProgress,
                )
            }
            GgufValidator.validate(
                path = mainOut.absolutePath,
                expectedExactBytes = model.expectedExactBytes,
                expectedMinBytes = model.expectedMinBytes,
            )
            val existing = modelDao.getAll().find { entity ->
                entity.catalogId == model.id ||
                    File(entity.filePath).name == model.fileName
            }
            modelDao.deactivateAll()
            if (existing != null) {
                modelDao.updateCatalogMetadata(
                    existing.id,
                    model.promptFormat,
                    model.name,
                    model.id,
                )
                modelDao.updateActive(existing.id, true)
                onProgress(100, "Ready")
                return@withContext existing.copy(
                    isActive = true,
                    promptFormat = model.promptFormat,
                    name = model.name,
                    catalogId = model.id,
                )
            }
            val entity = ModelEntity(
                name = model.name,
                filePath = mainOut.absolutePath,
                fileSizeBytes = mainOut.length(),
                promptFormat = model.promptFormat,
                catalogId = model.id,
                systemPrompt = systemPrompt,
                isActive = true,
                hasCustomPrompt = systemPrompt != null && !LanguagePrompt.isOnboardingLanguageOnly(systemPrompt),
            )
            modelDao.insert(entity)
            onProgress(100, "Ready")
            entity
        }

    private fun downloadGguf(
        dir: File,
        fileName: String,
        url: String,
        expectedExactBytes: Long,
        expectedMinBytes: Long,
        label: String,
        progressShare: Float,
        progressStart: Float,
        onProgress: (Int, String) -> Unit,
    ): File {
        val out = File(dir, fileName)
        if (out.exists() && out.length() > 0) {
            val sizeOk = when {
                expectedExactBytes > 0 -> out.length() == expectedExactBytes
                expectedMinBytes > 0 -> out.length() >= expectedMinBytes
                else -> true
            }
            if (sizeOk) {
                val pct = ((progressStart + progressShare) * 100f).toInt().coerceIn(0, 99)
                onProgress(pct, "$label already downloaded")
                return out
            }
            out.delete()
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        conn.connect()
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} for $fileName" }
        val total = conn.contentLengthLong
        conn.inputStream.buffered().use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(8192)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val fraction = done.toFloat() / total.toFloat()
                        val pct = ((progressStart + progressShare * fraction) * 100f)
                            .toInt()
                            .coerceIn(0, 99)
                        onProgress(pct, "Downloading $label… $pct%")
                    } else {
                        onProgress((progressStart * 100f).toInt(), "Downloading $label…")
                    }
                }
            }
        }
        if (expectedExactBytes > 0 && out.length() != expectedExactBytes) {
            out.delete()
            error("Download incomplete (size mismatch) for $fileName")
        }
        if (expectedMinBytes > 0 && out.length() < expectedMinBytes) {
            out.delete()
            error("Download incomplete for $fileName")
        }
        return out
    }

    suspend fun download(model: DownloadableModel, onProgress: (Int) -> Unit): ModelEntity =
        download(model, systemPrompt = null) { pct, _ -> onProgress(pct) }

    suspend fun setActive(model: ModelEntity) = withContext(Dispatchers.IO) {
        modelDao.deactivateAll()
        modelDao.updateActive(model.id, true)
    }

    suspend fun deleteModel(model: ModelEntity) = withContext(Dispatchers.IO) {
        val wasActive = model.isActive
        val main = File(model.filePath)
        val dir = main.parentFile
        File(model.filePath).delete()
        val catalogEntry = ModelCatalog.catalogEntryForInstalledFile(context, main.name)
        catalogEntry?.mmprojFileName?.let { mmprojName ->
            dir?.let { File(it, mmprojName).delete() }
        }
        // Do not wipe other catalog mmproj files that may still be installed.
        modelDao.delete(model.id)
        if (wasActive) {
            modelDao.getAll().firstOrNull()?.let { next ->
                modelDao.updateActive(next.id, true)
            }
        }
    }

    suspend fun savePrompt(modelId: Long, prompt: String) = withContext(Dispatchers.IO) {
        modelDao.updatePrompt(modelId, prompt.trim(), hasCustom = true)
    }

    suspend fun resetPrompt(modelId: Long) = withContext(Dispatchers.IO) {
        modelDao.updatePrompt(modelId, null, hasCustom = false)
    }

    /** Fix catalog metadata and legacy onboarding prompts after app or catalog updates. */
    suspend fun syncInstalledWithCatalog(): Boolean = withContext(Dispatchers.IO) {
        var changed = reconcileInstallState()
        changed = cleanupOrphanMmprojFiles() || changed
        val catalog = ModelCatalog.all(context)
        for (model in modelDao.getAll()) {
            val fileName = File(model.filePath).name
            val entry = catalog.find { it.fileName == fileName } ?: continue
            if (model.promptFormat != entry.promptFormat ||
                model.name != entry.name ||
                model.catalogId != entry.id
            ) {
                modelDao.updateCatalogMetadata(model.id, entry.promptFormat, entry.name, entry.id)
                changed = true
            }
            if (model.hasCustomPrompt && LanguagePrompt.isOnboardingLanguageOnly(model.systemPrompt)) {
                modelDao.clearCustomPrompt(model.id)
                changed = true
            }
        }
        changed
    }

    /** Register on-disk models missing from Room and ensure exactly one active model when possible. */
    suspend fun reconcileInstallState(): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val registered = modelDao.getAll()
        val registeredFiles = registered.map { File(it.filePath).name }.toMutableSet()
        val registeredCatalogIds = registered.mapNotNull { it.catalogId }.toMutableSet()

        for (entry in ModelCatalog.all(context)) {
            if (!ModelCatalog.isFullyInstalled(entry, dir)) continue
            if (entry.fileName in registeredFiles || entry.id in registeredCatalogIds) continue
            val main = File(dir, entry.fileName)
            modelDao.insert(
                ModelEntity(
                    name = entry.name,
                    filePath = main.absolutePath,
                    fileSizeBytes = main.length(),
                    promptFormat = entry.promptFormat,
                    catalogId = entry.id,
                    isActive = false,
                ),
            )
            registeredFiles.add(entry.fileName)
            registeredCatalogIds.add(entry.id)
            changed = true
        }

        if (modelDao.getActive() == null) {
            modelDao.getAll().firstOrNull()?.let { first ->
                modelDao.deactivateAll()
                modelDao.updateActive(first.id, true)
                changed = true
            }
        }
        changed
    }

    /** Remove mmproj files that are not referenced by the current catalog. */
    private fun cleanupOrphanMmprojFiles(): Boolean {
        val dir = File(context.filesDir, "models")
        if (!dir.isDirectory) return false
        val keep = ModelCatalog.all(context)
            .mapNotNull { it.mmprojFileName }
            .toSet()
        var changed = false
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("mmproj-") }?.forEach { file ->
            if (file.name !in keep) {
                file.delete()
                // Count as changed once the orphan is gone, even if another sync already deleted it.
                if (!file.exists()) changed = true
            }
        }
        return changed
    }
}

class ChatRepository(
    private val conversationDao: com.localllm.chat.data.db.ConversationDao,
    private val messageDao: com.localllm.chat.data.db.MessageDao,
) {
    fun observeConversations() = conversationDao.observeAll()

    fun observeMessages(conversationId: Long) = messageDao.observeForConversation(conversationId)

    suspend fun createConversation(title: String = "New chat", mode: String = "CHAT"): Long =
        conversationDao.insert(ConversationEntity(title = title, mode = mode))

    suspend fun getConversation(id: Long) = conversationDao.getById(id)

    suspend fun addMessage(conversationId: Long, role: String, content: String, thinking: String? = null): Long =
        messageDao.insert(
            com.localllm.chat.data.db.MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                thinkingContent = thinking,
            ),
        )

    suspend fun deleteConversation(id: Long) = conversationDao.delete(id)
}
