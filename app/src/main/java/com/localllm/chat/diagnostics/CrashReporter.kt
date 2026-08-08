package com.localllm.chat.diagnostics

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Captures crashes and inference errors on-device.
 * - Uncaught exceptions → saved + shown on next launch
 * - Handled errors (chat send) → saved + can share/copy
 * - Breadcrumbs flushed to disk before native calls (survives native SIGSEGV / LMK)
 * - Incomplete trail after cold start → PROCESS_KILL report (not buried under old bench)
 */
object CrashReporter {
    private const val TAG = "PocketAiCrash"
    private const val PREFS = "crash_reporter"
    private const val KEY_PENDING = "pending_show"
    private const val KEY_PENDING_NATIVE = "pending_native"
    private const val KEY_LAST_BREADCRUMB = "last_breadcrumb"
    private const val KEY_LAST_BREADCRUMB_AT = "last_breadcrumb_at"
    private const val MAX_LOG_FILES = 8
    /** Android Binder clip limit ~1MB; stay safely under. */
    private const val MAX_CLIPBOARD_CHARS = 400_000
    private const val MAX_SHARE_TEXT_CHARS = 8_000
    private const val MAX_STACK_CHARS = 20_000
    private const val MAX_TRAIL_CHARS = 8_000
    private const val MAX_TRAIL_FILE_CHARS = 64_000

    private lateinit var appContext: Context
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(app: Application) {
        appContext = app.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveReport(
                kind = "FATAL_CRASH",
                throwable = throwable,
                context = mapOf("thread" to thread.name),
                showOnNextLaunch = true,
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }
        // Before app_start overwrites last-breadcrumb prefs from prior crash.
        flagNativeCrashIfTrailLooksIncomplete()
        breadcrumb("app_start version=${appVersion()}")
    }

    /** Sync write + fsync — survives native SIGSEGV right after. */
    fun breadcrumbSync(message: String) {
        if (!::appContext.isInitialized) return
        val line = "${timestamp()} BREADCRUMB $message\n"
        runCatching {
            FileOutputStream(trailFile(), /* append = */ true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_BREADCRUMB, message.trim())
                .putLong(KEY_LAST_BREADCRUMB_AT, System.currentTimeMillis())
                .commit()
            trimTrailFileIfNeeded()
            Log.d(TAG, message)
        }
    }

    /** Last action before a possible native crash — written asynchronously. */
    fun breadcrumb(message: String) {
        breadcrumbSync(message)
    }

    /** Handled error (chat send failed but app survived). Written synchronously for immediate UI. */
    fun logError(tag: String, throwable: Throwable, context: Map<String, String> = emptyMap()) {
        writeReportSync(
            kind = "ERROR:$tag",
            throwable = throwable,
            context = context,
            showOnNextLaunch = false,
        )
    }

    /**
     * Latest diagnostic file. Prefer PROCESS_KILL / FATAL over a stale completed bench report
     * when the process died mid-vision / mid-inference.
     */
    fun getLastReport(): String? {
        if (!::appContext.isInitialized) return null
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PENDING_NATIVE, false)) {
            latestLogFile()?.takeIf { it.nameContainsKillOrFatal() }?.readText()?.let { return it }
            buildKillSuspectReport()?.let { return it }
        }
        latestLogFile()?.readText()?.let { return it }
        return buildKillSuspectReport()
    }

    fun hasPendingStartupReport(): Boolean {
        if (!::appContext.isInitialized) return false
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PENDING, false) && getLastReport() != null) return true
        return prefs.getBoolean(KEY_PENDING_NATIVE, false) && getLastReport() != null
    }

    /**
     * Cold start: if the previous process died mid-bench / mid-vision / mid-generate,
     * persist a PROCESS_KILL report as the newest log file so Share/Copy is not the old bench.
     */
    fun flagNativeCrashIfTrailLooksIncomplete() {
        if (!::appContext.isInitialized) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PENDING, false)) return
        val lastStep = lastMeaningfulBreadcrumb() ?: return
        if (isCleanShutdownBreadcrumb(lastStep)) return
        val trail = trailFile().takeIf { it.exists() }?.readText().orEmpty()
        val benchOpen = trailHasOpenBenchPin(trail)
        val visionOrBenchRisk =
            benchOpen ||
                lastStep.contains("image=", ignoreCase = true) ||
                lastStep.contains("with_image", ignoreCase = true) ||
                lastStep.contains("bench checkpoint", ignoreCase = true) ||
                lastStep.contains("completion start", ignoreCase = true) ||
                lastStep.contains("completion stream", ignoreCase = true)
        if (!visionOrBenchRisk) return
        prefs.edit().putBoolean(KEY_PENDING_NATIVE, true).commit()
        persistKillSuspectReport(lastStep)
    }

    private fun trailHasOpenBenchPin(trail: String): Boolean {
        val pinnedOn = trail.lastIndexOf("BREADCRUMB workPinned=true")
        if (pinnedOn < 0) return false
        val pinnedOff = trail.lastIndexOf("BREADCRUMB workPinned=false")
        return pinnedOff < pinnedOn
    }

    private fun isCleanShutdownBreadcrumb(step: String): Boolean {
        val s = step.lowercase()
        return s.startsWith("bench done") ||
            s.startsWith("self_check saved") ||
            s == "workpinned=false"
    }

    fun clearPendingStartupReport() {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING, false)
            .putBoolean(KEY_PENDING_NATIVE, false)
            .commit()
    }

    fun copyLastReportToClipboard(context: Context): Boolean {
        val text = getExportableDiagnostics() ?: return false
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("Airux Pocket AI diagnostic log", textForClipboard(text)),
            )
            true
        }.getOrElse { e ->
            Log.e(TAG, "clipboard copy failed", e)
            false
        }
    }

    fun shareLastReport(context: Context): Boolean {
        val text = getExportableDiagnostics() ?: return false
        val file = File(logsDir(), "export_${System.currentTimeMillis()}.txt").also {
            it.writeText(text)
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val summary = buildString {
                append(text.take(MAX_SHARE_TEXT_CHARS))
                if (text.length > MAX_SHARE_TEXT_CHARS) {
                    append("\n\n… truncated — full log attached (${text.length} chars).")
                }
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Airux Pocket AI diagnostic log")
                putExtra(Intent.EXTRA_TEXT, summary)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share diagnostic log"))
            true
        }.getOrElse { e ->
            Log.e(TAG, "share failed", e)
            false
        }
    }

    /**
     * Full export: kill/crash report first when present, then last bench, then trail.
     */
    fun getExportableDiagnostics(): String? {
        if (!::appContext.isInitialized) return null
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingKill = prefs.getBoolean(KEY_PENDING_NATIVE, false) || prefs.getBoolean(KEY_PENDING, false)
        val killOrFatal = latestLogFile()?.takeIf {
            it.nameContainsKillOrFatal() || pendingKill
        }?.readText()
            ?: if (pendingKill) buildKillSuspectReport() else null
        val latest = latestLogFile()?.readText()
        val trail = trailFile().takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (killOrFatal.isNullOrBlank() && latest.isNullOrBlank() && trail.isEmpty()) return null
        return buildString {
            appendLine("=== Airux Pocket AI Full Diagnostics Export ===")
            appendLine("time: ${timestamp()}")
            appendLine("app: ${appVersion()}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            if (!killOrFatal.isNullOrBlank()) {
                appendLine()
                appendLine("--- process kill / crash (primary) ---")
                appendLine(killOrFatal.trim())
            }
            if (!latest.isNullOrBlank() && latest.trim() != killOrFatal?.trim()) {
                appendLine()
                appendLine("--- last report (may be prior completed bench) ---")
                appendLine(latest.trim())
            }
            if (trail.isNotEmpty()) {
                appendLine()
                appendLine("--- breadcrumb trail (full) ---")
                appendLine(trail.takeLast(MAX_TRAIL_FILE_CHARS))
            }
        }
    }

    /** Persist a self-check / completed-bench report as a diagnostic file. */
    fun saveSelfCheckReport(body: String) {
        if (!::appContext.isInitialized) return
        runCatching {
            val file = newLogFile("report")
            val trail = trailFile().takeIf { it.exists() }?.readText()?.trim().orEmpty()
            file.writeText(
                buildString {
                    appendLine(body.trim())
                    appendLine()
                    appendLine("--- breadcrumb trail ---")
                    appendLine(trail.takeLast(MAX_TRAIL_CHARS))
                },
            )
            trimOldLogs()
            breadcrumbSync("self_check saved ${file.name}")
        }
    }

    /**
     * Flush a partial bench before a risky vision turn so a LMK kill still leaves
     * a readable "died at …" checkpoint (not only the previous full run).
     */
    fun saveBenchCheckpoint(body: String) {
        if (!::appContext.isInitialized) return
        runCatching {
            val file = newLogFile("checkpoint")
            writeAndSync(
                file,
                buildString {
                    appendLine("=== Airux Pocket AI Bench Checkpoint ===")
                    appendLine("time: ${timestamp()}")
                    appendLine("app: ${appVersion()}")
                    appendLine("note: process may die during the next vision/native step")
                    appendLine()
                    appendLine(body.trim())
                },
            )
            trimOldLogs()
            breadcrumbSync("bench checkpoint saved ${file.name}")
        }
    }

    fun formatForDisplay(raw: String?): String =
        raw?.trim()?.take(24_000) ?: "No diagnostic log saved yet. Run a self-check or benchmark in Settings."

    private fun saveReport(
        kind: String,
        throwable: Throwable,
        context: Map<String, String>,
        showOnNextLaunch: Boolean,
    ) {
        io.execute {
            writeReportSync(kind, throwable, context, showOnNextLaunch)
        }
    }

    private fun writeReportSync(
        kind: String,
        throwable: Throwable,
        context: Map<String, String>,
        showOnNextLaunch: Boolean,
    ) {
        if (!::appContext.isInitialized) return
        val report = buildReportText(kind, throwable, context)
        runCatching {
            val file = newLogFile("report")
            writeAndSync(file, report)
            trimOldLogs()
            if (showOnNextLaunch) {
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PENDING, true).commit()
            }
            Log.e(TAG, report)
        }
    }

    private fun persistKillSuspectReport(lastStep: String) {
        val text = buildKillSuspectReport(lastStep) ?: return
        runCatching {
            val file = newLogFile("kill")
            writeAndSync(file, text)
            trimOldLogs()
            Log.e(TAG, "PROCESS_KILL report written: $lastStep")
        }
    }

    private fun buildKillSuspectReport(lastStepOverride: String? = null): String? {
        if (!::appContext.isInitialized) return null
        val trail = trailFile().takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (trail.isEmpty()) return null
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = lastStepOverride ?: lastMeaningfulBreadcrumb().orEmpty()
        return buildString {
            appendLine("=== Airux Pocket AI Diagnostic Report ===")
            appendLine("kind: PROCESS_KILL_SUSPECTED")
            appendLine("time: ${timestamp()}")
            appendLine("app: ${appVersion()}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("--- note ---")
            appendLine("Process was killed without a Java stack (LMK / native SIGSEGV / SIGKILL).")
            appendLine("Common during vision mmproj eval (Gemma 4B + image).")
            appendLine("Export prefers this over a prior completed benchmark report.")
            if (last.isNotEmpty()) {
                appendLine("last_breadcrumb: $last")
                val lastAt = prefs.getLong(KEY_LAST_BREADCRUMB_AT, 0L)
                if (lastAt > 0L) appendLine("last_breadcrumb_at: ${timestamp(lastAt)}")
            }
            appendLine("--- breadcrumbs (last actions) ---")
            appendLine(trail.takeLast(MAX_TRAIL_CHARS))
        }
    }

    private fun buildReportText(
        kind: String,
        throwable: Throwable,
        context: Map<String, String>,
    ): String = buildString {
            appendLine("=== Airux Pocket AI Diagnostic Report ===")
            appendLine("kind: $kind")
            appendLine("time: ${timestamp()}")
            appendLine("app: ${appVersion()}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            if (context.isNotEmpty()) {
                appendLine("--- context ---")
                context.forEach { (k, v) -> appendLine("$k: $v") }
            }
            appendLine("--- breadcrumbs (last actions) ---")
            appendLine(trailFile().takeIf { it.exists() }?.readText()?.trim()?.takeLast(MAX_TRAIL_CHARS).orEmpty())
            appendLine("--- stack trace ---")
            appendLine(stackTraceOf(throwable).take(MAX_STACK_CHARS))
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 3) {
                appendLine("--- caused by ---")
                appendLine(stackTraceOf(cause).take(MAX_STACK_CHARS))
                cause = cause.cause
                depth++
            }
        }

    private fun textForClipboard(full: String): String {
        if (full.length <= MAX_CLIPBOARD_CHARS) return full
        return buildString {
            appendLine("=== TRUNCATED for clipboard (${full.length} chars total; use Share for full log) ===")
            append(full.takeLast(MAX_CLIPBOARD_CHARS - 120))
        }
    }

    private fun trimTrailFileIfNeeded() {
        val file = trailFile()
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.length <= MAX_TRAIL_FILE_CHARS) return
        runCatching {
            file.writeText(text.takeLast(MAX_TRAIL_FILE_CHARS))
        }
    }

    /** Single open for write + fsync; reopening to sync would truncate the text away. */
    private fun writeAndSync(file: File, text: String) {
        FileOutputStream(file, /* append = */ false).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun logsDir(): File =
        File(appContext.filesDir, "crash_logs").also { it.mkdirs() }

    private fun trailFile(): File = File(logsDir(), "breadcrumb_trail.log")

    private fun newLogFile(prefix: String): File =
        File(logsDir(), "${prefix}_${System.currentTimeMillis()}.txt")

    private fun latestLogFile(): File? =
        logsDir().listFiles { f ->
            f.isFile && (
                f.name.startsWith("report_") ||
                    f.name.startsWith("kill_") ||
                    f.name.startsWith("checkpoint_")
                )
        }?.maxByOrNull { it.lastModified() }

    private fun File.nameContainsKillOrFatal(): Boolean {
        if (name.startsWith("kill_")) return true
        val head = runCatching { readText().take(400) }.getOrNull().orEmpty()
        return head.contains("PROCESS_KILL") || head.contains("FATAL_CRASH") || head.contains("NATIVE_CRASH")
    }

    private fun trimOldLogs() {
        val files = logsDir().listFiles { f ->
            f.isFile && (
                f.name.startsWith("report_") ||
                    f.name.startsWith("kill_") ||
                    f.name.startsWith("checkpoint_")
                )
        }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_LOG_FILES).forEach { it.delete() }
    }

    private fun lastMeaningfulBreadcrumb(): String? {
        val trail = trailFile().takeIf { it.exists() }?.readLines().orEmpty()
        return trail.asReversed()
            .firstOrNull { line ->
                line.contains("BREADCRUMB") &&
                    !line.contains("app_start version=")
            }
            ?.substringAfter("BREADCRUMB ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun timestamp(): String =
        timestamp(System.currentTimeMillis())

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))

    private fun appVersion(): String = runCatching {
        if (!::appContext.isInitialized) return "unknown"
        val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        "${pi.versionName} ($code)"
    }.getOrDefault("unknown")
}
