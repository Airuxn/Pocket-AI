package com.localllm.chat.diagnostics

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReporterTest {
    private lateinit var app: Application
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private fun logsDir() = File(app.filesDir, "crash_logs")

    private fun trailFile() = File(logsDir(), "breadcrumb_trail.log")

    private fun prefs() = app.getSharedPreferences("crash_reporter", Context.MODE_PRIVATE)

    private fun reportFiles(): List<File> =
        logsDir().listFiles { f: File ->
            f.isFile && (
                f.name.startsWith("report_") ||
                    f.name.startsWith("kill_") ||
                    f.name.startsWith("checkpoint_")
                )
        }?.toList().orEmpty()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        logsDir().deleteRecursively()
        prefs().edit().clear().commit()
        // Without this, each install() chains onto the handler installed by the previous
        // test and a single uncaught exception recurses through all of them.
        Thread.setDefaultUncaughtExceptionHandler(null)
        CrashReporter.install(app)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        logsDir().deleteRecursively()
        prefs().edit().clear().commit()
    }

    @Test
    fun installWritesAppStartBreadcrumb() {
        assertTrue(trailFile().exists())
        assertTrue(trailFile().readText().contains("app_start version="))
    }

    @Test
    fun breadcrumbAppendsLines() {
        CrashReporter.breadcrumb("first step")
        CrashReporter.breadcrumbSync("second step")
        val trail = trailFile().readText()
        assertTrue(trail.contains("BREADCRUMB first step"))
        assertTrue(trail.contains("BREADCRUMB second step"))
    }

    @Test
    fun breadcrumbTrailIsTrimmedWhenTooLarge() {
        CrashReporter.breadcrumbSync("x".repeat(70_000))
        assertTrue(trailFile().length() <= 64_000L)
    }

    @Test
    fun logErrorWritesReportWithStackAndContext() {
        CrashReporter.logError("send", IllegalStateException("boom"), mapOf("model" to "gemma"))
        val report = CrashReporter.getLastReport()
        assertNotNull(report)
        assertTrue(report!!.contains("kind: ERROR:send"))
        assertTrue(report.contains("model: gemma"))
        assertTrue(report.contains("boom"))
        assertTrue(report.contains("--- stack trace ---"))
    }

    @Test
    fun logErrorIncludesCauseChain() {
        val root = IllegalArgumentException("root cause")
        CrashReporter.logError("load", RuntimeException("outer", root))
        val report = CrashReporter.getLastReport()!!
        assertTrue(report.contains("--- caused by ---"))
        assertTrue(report.contains("root cause"))
    }

    @Test
    fun saveSelfCheckReportPersistsBodyAndTrail() {
        CrashReporter.breadcrumbSync("before self check")
        CrashReporter.saveSelfCheckReport("=== Self check ===\nall good")
        val report = CrashReporter.getLastReport()!!
        assertTrue(report.contains("all good"))
        assertTrue(report.contains("--- breadcrumb trail ---"))
        assertTrue(trailFile().readText().contains("self_check saved report_"))
    }

    @Test
    fun saveBenchCheckpointPersistsHeader() {
        CrashReporter.saveBenchCheckpoint("tokens/s: 12.5")
        val report = CrashReporter.getLastReport()!!
        assertTrue(report.contains("Bench Checkpoint"))
        assertTrue(report.contains("tokens/s: 12.5"))
        assertTrue(trailFile().readText().contains("bench checkpoint saved checkpoint_"))
    }

    @Test
    fun oldLogsAreTrimmedToMaximum() {
        repeat(12) { index ->
            CrashReporter.saveSelfCheckReport("report number $index")
            Thread.sleep(2)
        }
        assertTrue(reportFiles().size <= 8)
    }

    @Test
    fun exportableDiagnosticsContainsDeviceAndTrailSections() {
        CrashReporter.breadcrumbSync("completion start model=gemma")
        CrashReporter.saveSelfCheckReport("bench body")
        val export = CrashReporter.getExportableDiagnostics()!!
        assertTrue(export.contains("Full Diagnostics Export"))
        assertTrue(export.contains("device: "))
        assertTrue(export.contains("abi: "))
        assertTrue(export.contains("--- breadcrumb trail (full) ---"))
        assertTrue(export.contains("completion start model=gemma"))
    }

    @Test
    fun exportableDiagnosticsPrefersKillReportWhenPending() {
        CrashReporter.breadcrumbSync("completion stream token=42")
        CrashReporter.flagNativeCrashIfTrailLooksIncomplete()
        val export = CrashReporter.getExportableDiagnostics()!!
        assertTrue(export.contains("--- process kill / crash (primary) ---"))
        assertTrue(export.contains("PROCESS_KILL_SUSPECTED"))
    }

    @Test
    fun incompleteVisionTrailFlagsProcessKill() {
        CrashReporter.breadcrumbSync("vision eval image=1024x768")
        CrashReporter.flagNativeCrashIfTrailLooksIncomplete()
        assertTrue(prefs().getBoolean("pending_native", false))
        assertTrue(reportFiles().any { it.name.startsWith("kill_") })
        assertTrue(CrashReporter.hasPendingStartupReport())
        val report = CrashReporter.getLastReport()!!
        assertTrue(report.contains("PROCESS_KILL_SUSPECTED"))
        assertTrue(report.contains("last_breadcrumb: vision eval image=1024x768"))
    }

    @Test
    fun openBenchPinFlagsProcessKill() {
        CrashReporter.breadcrumbSync("workPinned=true")
        CrashReporter.flagNativeCrashIfTrailLooksIncomplete()
        assertTrue(prefs().getBoolean("pending_native", false))
    }

    @Test
    fun closedBenchPinDoesNotFlagProcessKill() {
        CrashReporter.breadcrumbSync("workPinned=true")
        CrashReporter.breadcrumbSync("workPinned=false")
        CrashReporter.flagNativeCrashIfTrailLooksIncomplete()
        assertFalse(prefs().getBoolean("pending_native", false))
    }

    @Test
    fun cleanShutdownBreadcrumbDoesNotFlagProcessKill() {
        CrashReporter.breadcrumbSync("bench done in 4s")
        CrashReporter.flagNativeCrashIfTrailLooksIncomplete()
        assertFalse(prefs().getBoolean("pending_native", false))
    }

    @Test
    fun harmlessBreadcrumbDoesNotFlagProcessKill() {
        CrashReporter.breadcrumbSync("user opened settings")
        CrashReporter.flagNativeCrashIfTrailLooksIncomplete()
        assertFalse(prefs().getBoolean("pending_native", false))
    }

    @Test
    fun clearPendingStartupReportResetsFlags() {
        CrashReporter.breadcrumbSync("bench checkpoint written")
        CrashReporter.flagNativeCrashIfTrailLooksIncomplete()
        assertTrue(CrashReporter.hasPendingStartupReport())
        CrashReporter.clearPendingStartupReport()
        assertFalse(CrashReporter.hasPendingStartupReport())
    }

    @Test
    fun copyLastReportPutsTextOnClipboard() {
        CrashReporter.saveSelfCheckReport("clipboard body")
        assertTrue(CrashReporter.copyLastReportToClipboard(app))
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipped = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clipped.contains("clipboard body"))
    }

    @Test
    fun uncaughtExceptionIsSavedAndShownOnNextLaunch() {
        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        handler.uncaughtException(Thread.currentThread(), RuntimeException("fatal boom"))

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && reportFiles().isEmpty()) {
            Thread.sleep(10)
        }

        assertTrue(prefs().getBoolean("pending_show", false))
        assertTrue(CrashReporter.hasPendingStartupReport())
        val report = CrashReporter.getLastReport()!!
        assertTrue(report.contains("kind: FATAL_CRASH"))
        assertTrue(report.contains("thread: "))
        assertTrue(report.contains("fatal boom"))
    }

    /**
     * FileProvider caches its path strategy per authority for the whole JVM, so the share
     * path has to be exercised from a single test to stay independent of Robolectric's
     * per-test data directory.
     */
    @Test
    fun shareLastReportAttachesTheLogAndTruncatesLongSummaries() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        CrashReporter.saveSelfCheckReport("shared body")
        assertTrue(CrashReporter.shareLastReport(activity))

        assertTrue(logsDir().listFiles()!!.any { it.name.startsWith("export_") })
        val shortShare = shadowOf(activity).nextStartedActivity!!
            .getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("text/plain", shortShare.type)
        assertEquals("Airux Pocket AI diagnostic log", shortShare.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(shortShare.getStringExtra(Intent.EXTRA_TEXT)!!.contains("shared body"))
        assertNotNull(shortShare.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))

        CrashReporter.saveSelfCheckReport("w".repeat(20_000))
        assertTrue(CrashReporter.shareLastReport(activity))

        val longShare = shadowOf(activity).nextStartedActivity!!
            .getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertTrue(longShare.getStringExtra(Intent.EXTRA_TEXT)!!.contains("full log attached"))
    }

    @Test
    fun exportsAreUnavailableBeforeAnythingIsLogged() {
        logsDir().deleteRecursively()
        assertNull(CrashReporter.getExportableDiagnostics())
        assertNull(CrashReporter.getLastReport())
        assertFalse(CrashReporter.copyLastReportToClipboard(app))
        assertFalse(CrashReporter.shareLastReport(app))
    }

    @Test
    fun oversizedReportsAreTruncatedForTheClipboard() {
        CrashReporter.saveSelfCheckReport("z".repeat(500_000))
        assertTrue(CrashReporter.copyLastReportToClipboard(app))
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipped = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clipped.startsWith("=== TRUNCATED for clipboard"))
        assertTrue(clipped.length <= 400_000)
    }

    @Test
    fun formatForDisplayFallsBackWhenNoReport() {
        assertTrue(CrashReporter.formatForDisplay(null).contains("No diagnostic log saved yet"))
    }

    @Test
    fun formatForDisplayTrimsAndCaps() {
        assertEquals("hello", CrashReporter.formatForDisplay("  hello  "))
        assertEquals(24_000, CrashReporter.formatForDisplay("y".repeat(30_000)).length)
    }
}
