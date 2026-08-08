package com.localllm.chat.diagnostics

import com.localllm.chat.data.catalog.ModelCatalog
import com.localllm.chat.data.db.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceSelfCheckTest {
    @Test
    fun runReturnsChecks() {
        val context = RuntimeEnvironment.getApplication()
        val checks = DeviceSelfCheck.run(context, emptyList())
        assertTrue(checks.isNotEmpty())
        val names = checks.map { it.name }
        assertTrue(names.contains("loose_prose_web_search"))
        assertTrue(names.contains("context_policy"))
    }

    @Test
    fun toolRecoveryAndContextChecksPass() {
        val context = RuntimeEnvironment.getApplication()
        val failures = DeviceSelfCheck.run(context, emptyList()).filterNot { it.passed }
        // The vision fixture asset is only bundled in release flavors of the bench build.
        assertEquals(emptyList<String>(), failures.map { it.name } - "vision_count_fixture")
    }

    @Test
    fun installedCatalogModelIsResolvedFromItsFile() {
        val context = RuntimeEnvironment.getApplication()
        val entry = ModelCatalog.all(context)[0]
        val checks = DeviceSelfCheck.run(
            context,
            listOf(
                ModelEntity(
                    name = entry.name,
                    filePath = "/data/models/${entry.fileName}",
                    fileSizeBytes = 0,
                    promptFormat = entry.promptFormat,
                    catalogId = entry.id,
                ),
            ),
        )
        val check = checks.first { it.name == "catalog_resolve:${entry.name}" }
        assertTrue(check.detail, check.passed)
        assertTrue(check.detail.contains("resolved=${entry.id}"))
    }

    @Test
    fun staleRoomCatalogIdIsReportedAsResolvedFromDisk() {
        val context = RuntimeEnvironment.getApplication()
        val catalog = ModelCatalog.all(context)
        val onDisk = catalog[0]
        val staleId = catalog.first { it.id != onDisk.id }.id
        val checks = DeviceSelfCheck.run(
            context,
            listOf(
                ModelEntity(
                    name = "Stale",
                    filePath = "/data/models/${onDisk.fileName}",
                    fileSizeBytes = 0,
                    promptFormat = onDisk.promptFormat,
                    catalogId = staleId,
                ),
            ),
        )
        val check = checks.first { it.name == "catalog_resolve:Stale" }
        assertTrue(check.detail, check.passed)
        assertTrue(check.detail.contains("roomId=$staleId"))
        assertTrue(check.detail.contains("resolved=${onDisk.id}"))
    }

    @Test
    fun sideloadedModelResolvesToNoCatalogEntry() {
        val context = RuntimeEnvironment.getApplication()
        val checks = DeviceSelfCheck.run(
            context,
            listOf(
                ModelEntity(
                    name = "Sideloaded",
                    filePath = "/data/models/sideloaded.gguf",
                    fileSizeBytes = 0,
                    promptFormat = "LLAMA_3",
                ),
            ),
        )
        val check = checks.first { it.name == "catalog_resolve:Sideloaded" }
        assertTrue(check.detail, check.passed)
        assertTrue(check.detail.contains("resolved=null"))
        assertTrue(check.detail.contains("tools=[]"))
    }

    @Test
    fun formatReportContainsHeader() {
        val checks = listOf(
            DeviceSelfCheck.Check("test_check", true, "ok"),
        )
        val report = DeviceSelfCheck.formatReport(checks)
        assertTrue(report.contains("Airux Pocket AI Device Self-Check"))
        assertTrue(report.contains("1/1 passed"))
        assertTrue(report.contains("OK  test_check"))
    }

    @Test
    fun formatReportMarksFailuresAndCountsThem() {
        val report = DeviceSelfCheck.formatReport(
            listOf(
                DeviceSelfCheck.Check("ok_check", true, "fine"),
                DeviceSelfCheck.Check("bad_check", false, "broken"),
            ),
        )
        assertTrue(report.contains("1/2 passed"))
        assertTrue(report.contains("FAIL  bad_check — broken"))
    }
}
