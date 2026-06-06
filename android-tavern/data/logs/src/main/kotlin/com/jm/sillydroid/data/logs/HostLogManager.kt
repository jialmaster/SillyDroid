package com.jm.sillydroid.data.logs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.jm.sillydroid.core.model.logs.HostLogBundleAttachment
import com.jm.sillydroid.core.model.logs.HostLogBundleExportResult
import com.jm.sillydroid.core.model.logs.HostLogEntry
import com.jm.sillydroid.core.model.logs.HostLogExportOption
import com.jm.sillydroid.core.model.logs.HostLogSnapshot
import com.jm.sillydroid.core.model.logs.HostLogTailWindowProfile
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object HostLogManager {
    private const val defaultMaxChars = 320_000
    private const val defaultMaxBytes = 768 * 1024
    private const val defaultMaxLines = 6_000
    private const val fullTailWindowMinChars = 120_000
    private const val fullTailWindowMinBytes = 256 * 1024
    private const val fullTailWindowMinLines = 2_000
    private const val realtimeSnapshotMaxChars = 6_000
    private const val realtimeSnapshotMaxBytes = 24 * 1024
    private const val realtimeSnapshotMaxLines = 120
    private const val retainedAppSessionCount = 5
    private const val startupLogPrefix = "startup-"
    private const val tavernServerLogPrefix = "sillydroid-server-"
    private const val rootfsRuntimeLogPrefix = "rootfs-runtime-"
    private const val hostDiagnosticsLogPrefix = "host-diagnostics-"
    private const val jsErrorLogPrefix = "js-error-"
    private const val startupLogLegacyFileName = "startup.log"
    private const val tavernServerLogLegacyFileName = "sillydroid-server.log"
    private const val rootfsRuntimeLogLegacyFileName = "rootfs-runtime.log"
    private const val bundleInfoEntryName = "bundle-info.txt"
    private const val bundleInfoJsonEntryName = "bundle-info.json"
    private const val bundleFilePrefix = "sillydroid-logs"
    private const val asyncWriterLogTag = "HostLogAsyncWriter"
    private const val asyncWriterShutdownTimeoutMillis = 750L
    private const val uploadMaxLogArchiveBytes = HostLogUploadBundlePolicy.maxCompactLogArchiveBytes
    private const val uploadCompactMaxBytes = HostLogUploadBundlePolicy.compactMaxBytes
    private const val uploadCompactMaxChars = HostLogUploadBundlePolicy.compactMaxChars
    private const val uploadCompactMaxLines = HostLogUploadBundlePolicy.compactMaxLines
    private const val uploadTavernServerHeadLines = HostLogUploadBundlePolicy.tavernServerHeadLines

    const val crashLogFileName = "app-last-crash.log"
    const val exitInfoLogFileName = "app-last-exit-info.log"

    @Volatile
    private var currentAppSessionId: String? = null

    private val visibleFixedLogFileNames = linkedSetOf(
        crashLogFileName,
        exitInfoLogFileName
    )

    private val tailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableLatestTavernServerLine = MutableStateFlow("")

    val latestTavernServerLine = mutableLatestTavernServerLine.asStateFlow()

    private var observedServerLogFile: File? = null
    private var serverTailFileObserver: FileObserver? = null
    private var serverTailRefreshJob: Job? = null
    private var serverTailRefreshPending = false
    private var sharedLogsFileObserver: FileObserver? = null
    private var nextSharedLogsSubscriberId = 1L
    private val sharedLogsSubscribers = LinkedHashMap<Long, SharedLogsSubscriber>()

    private data class SharedLogsSubscriber(
        val matcher: (String?) -> Boolean,
        val onChanged: () -> Unit
    )

    fun initializeForAppStart(context: Context) {
        synchronized(this) {
            if (currentAppSessionId != null) {
                return
            }

            val logsDir = logsDirectory(context)
            logsDir.mkdirs()
            val sessionId = buildSessionId(nowMillis = System.currentTimeMillis())
            currentAppSessionId = sessionId
            cleanupRetainedLogsLocked(logsDir, currentSessionId = sessionId)
        }
    }

    fun currentStartupLogFile(context: Context): File {
        return File(logsDirectory(context), currentStartupLogFileName(context))
    }

    fun currentServerLogFile(context: Context): File {
        return File(logsDirectory(context), currentServerLogFileName(context))
    }

    fun currentRootfsRuntimeLogFile(context: Context): File {
        return File(logsDirectory(context), currentRootfsRuntimeLogFileName(context))
    }

    fun currentHostDiagnosticsLogFile(context: Context): File {
        return File(logsDirectory(context), currentHostDiagnosticsLogFileName(context))
    }

    fun crashLogFile(context: Context): File {
        return File(logsDirectory(context), crashLogFileName)
    }

    fun exitInfoLogFile(context: Context): File {
        return File(logsDirectory(context), exitInfoLogFileName)
    }

    fun logsDirectory(context: Context): File {
        return resolveHostLogsDir(context.applicationContext)
    }

    fun currentAppSessionId(context: Context): String {
        return requireCurrentAppSessionId(context.applicationContext)
    }

    fun retainedAppSessionLimit(): Int {
        return retainedAppSessionCount
    }

    fun runtimeLogFileName(baseName: String): String {
        return "$baseName.log"
    }

    fun currentStartupLogFileName(context: Context): String {
        return "$startupLogPrefix${requireCurrentAppSessionId(context)}.log"
    }

    fun currentServerLogFileName(context: Context): String {
        return "$tavernServerLogPrefix${requireCurrentAppSessionId(context)}.log"
    }

    fun currentRootfsRuntimeLogFileName(context: Context): String {
        return "$rootfsRuntimeLogPrefix${requireCurrentAppSessionId(context)}.log"
    }

    fun currentHostDiagnosticsLogFileName(context: Context): String {
        return "$hostDiagnosticsLogPrefix${requireCurrentAppSessionId(context)}.log"
    }

    fun currentJsErrorLogFileName(context: Context): String {
        return "$jsErrorLogPrefix${requireCurrentAppSessionId(context)}.log"
    }

    fun currentJsErrorLogFile(context: Context): File {
        return File(logsDirectory(context), currentJsErrorLogFileName(context))
    }

    fun isCurrentSessionHostLogFileName(context: Context, fileName: String): Boolean {
        return isCurrentStartupLogFileName(context, fileName) ||
            isCurrentServerLogFileName(context, fileName) ||
            isCurrentRootfsRuntimeLogFileName(context, fileName) ||
            isCurrentHostDiagnosticsLogFileName(context, fileName) ||
            isCurrentJsErrorLogFileName(context, fileName)
    }

    fun isCurrentStartupLogFileName(context: Context, fileName: String): Boolean {
        return fileName.equals(currentStartupLogFileName(context), ignoreCase = true)
    }

    fun isCurrentServerLogFileName(context: Context, fileName: String): Boolean {
        return fileName.equals(currentServerLogFileName(context), ignoreCase = true)
    }

    fun isCurrentRootfsRuntimeLogFileName(context: Context, fileName: String): Boolean {
        return fileName.equals(currentRootfsRuntimeLogFileName(context), ignoreCase = true)
    }

    fun isCurrentHostDiagnosticsLogFileName(context: Context, fileName: String): Boolean {
        return fileName.equals(currentHostDiagnosticsLogFileName(context), ignoreCase = true)
    }

    fun isCurrentJsErrorLogFileName(context: Context, fileName: String): Boolean {
        return fileName.equals(currentJsErrorLogFileName(context), ignoreCase = true)
    }

    fun clearAllLogs(context: Context) {
        val applicationContext = context.applicationContext
        initializeForAppStart(applicationContext)
        val logsDir = logsDirectory(applicationContext)
        logsDir.mkdirs()
        val currentStartupFileName = currentStartupLogFileName(applicationContext)
        val currentServerFileName = currentServerLogFileName(applicationContext)
        val currentRootfsFileName = currentRootfsRuntimeLogFileName(applicationContext)
        val currentHostDiagnosticsFileName = currentHostDiagnosticsLogFileName(applicationContext)
        val currentJsErrorFileName = currentJsErrorLogFileName(applicationContext)

        logsDir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || !file.extension.equals("log", ignoreCase = true)) {
                return@forEach
            }

            when {
                file.name.equals(currentStartupFileName, ignoreCase = true) ||
                    file.name.equals(currentServerFileName, ignoreCase = true) ||
                    file.name.equals(currentRootfsFileName, ignoreCase = true) ||
                    file.name.equals(currentHostDiagnosticsFileName, ignoreCase = true) ||
                    file.name.equals(currentJsErrorFileName, ignoreCase = true) -> {
                    file.writeText("")
                }

                else -> {
                    file.delete()
                }
            }
        }
    }

    fun writeCrashLog(context: Context, content: String) {
        replaceLogFileContent(crashLogFile(context), content)
    }

    fun writeExitInfoLog(context: Context, content: String?) {
        val logFile = exitInfoLogFile(context)
        if (content.isNullOrBlank()) {
            deleteLogFile(logFile)
            return
        }

        replaceLogFileContent(logFile, content)
    }

    fun listEntries(context: Context): List<HostLogEntry> {
        val logsDir = logsDirectory(context).apply { mkdirs() }
        return logsDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("log", ignoreCase = true) &&
                    (
                        isCurrentSessionHostLogFileName(context, file.name) ||
                            file.name.lowercase(Locale.ROOT) in visibleFixedLogFileNames
                        )
            }
            .map { file ->
                HostLogEntry(
                    fileName = file.name,
                    displayName = resolveDisplayName(context, file.name),
                    updatedAt = formatTimestamp(file.lastModified()),
                    lastModified = file.lastModified()
                )
            }
            .sortedWith(compareBy<HostLogEntry>({ displayOrder(it.fileName) }).thenByDescending { it.lastModified })
    }

    fun listExportOptions(context: Context): List<HostLogExportOption> {
        val logsDir = logsDirectory(context).apply { mkdirs() }
        return buildExportOptions(
            logFiles = collectLogFiles(logsDir),
            logsDir = logsDir
        )
    }

    fun readPreferredSnapshot(
        context: Context,
        preferTavernServerLog: Boolean,
        maxChars: Int = defaultMaxChars,
        maxBytes: Int = defaultMaxBytes,
        maxLines: Int = defaultMaxLines,
        tailWindowProfile: HostLogTailWindowProfile = HostLogTailWindowProfile.FULL,
        entries: List<HostLogEntry>? = null
    ): HostLogSnapshot? {
        val availableEntries = entries ?: listEntries(context)
        val selectedEntry = when {
            availableEntries.isEmpty() -> null
            preferTavernServerLog -> availableEntries.firstOrNull { entry ->
                isCurrentServerLogFileName(context, entry.fileName)
            } ?: availableEntries.firstOrNull { entry ->
                isCurrentStartupLogFileName(context, entry.fileName)
            } ?: availableEntries.first()
            else -> availableEntries.firstOrNull { entry ->
                isCurrentStartupLogFileName(context, entry.fileName)
            } ?: availableEntries.firstOrNull { entry ->
                isCurrentRootfsRuntimeLogFileName(context, entry.fileName)
            } ?: availableEntries.first()
        } ?: return null

        return readSnapshot(
            context = context,
            entry = selectedEntry,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            tailWindowProfile = tailWindowProfile
        )
    }

    fun readPreferredRealtimeSnapshot(
        context: Context,
        preferTavernServerLog: Boolean,
        entries: List<HostLogEntry>? = null
    ): HostLogSnapshot? {
        return readPreferredSnapshot(
            context = context,
            preferTavernServerLog = preferTavernServerLog,
            maxChars = realtimeSnapshotMaxChars,
            maxBytes = realtimeSnapshotMaxBytes,
            maxLines = realtimeSnapshotMaxLines,
            tailWindowProfile = HostLogTailWindowProfile.COMPACT,
            entries = entries
        )
    }

    fun readSnapshot(
        context: Context,
        entry: HostLogEntry,
        maxChars: Int = defaultMaxChars,
        maxBytes: Int = defaultMaxBytes,
        maxLines: Int = defaultMaxLines,
        tailWindowProfile: HostLogTailWindowProfile = HostLogTailWindowProfile.FULL
    ): HostLogSnapshot? {
        val logFile = resolveLogFile(context, entry.fileName)
        return readSnapshot(
            context = context,
            logFile = logFile,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            tailWindowProfile = tailWindowProfile,
            entry = entry
        )
    }

    fun readSnapshot(
        context: Context,
        fileName: String,
        maxChars: Int = defaultMaxChars,
        maxBytes: Int = defaultMaxBytes,
        maxLines: Int = defaultMaxLines,
        tailWindowProfile: HostLogTailWindowProfile = HostLogTailWindowProfile.FULL
    ): HostLogSnapshot? {
        val logFile = resolveLogFile(context, fileName)
        return readSnapshot(
            context = context,
            logFile = logFile,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            tailWindowProfile = tailWindowProfile,
            entry = null
        )
    }

    private fun readSnapshot(
        context: Context,
        logFile: File,
        maxChars: Int,
        maxBytes: Int,
        maxLines: Int,
        tailWindowProfile: HostLogTailWindowProfile,
        entry: HostLogEntry?
    ): HostLogSnapshot? {
        if (!logFile.isFile || !logFile.extension.equals("log", ignoreCase = true)) {
            return null
        }

        val resolvedEntry = entry ?: HostLogEntry(
            fileName = logFile.name,
            displayName = resolveDisplayName(context, logFile.name),
            updatedAt = formatTimestamp(logFile.lastModified()),
            lastModified = logFile.lastModified()
        )
        val content = readTailContent(
            context = context,
            logFile = logFile,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            tailWindowProfile = tailWindowProfile
        )

        return HostLogSnapshot(
            fileName = resolvedEntry.fileName,
            displayName = resolvedEntry.displayName,
            updatedAt = resolvedEntry.updatedAt,
            content = content
        )
    }

    fun readRealtimeSnapshot(
        context: Context,
        entry: HostLogEntry
    ): HostLogSnapshot? {
        return readSnapshot(
            context = context,
            entry = entry,
            maxChars = realtimeSnapshotMaxChars,
            maxBytes = realtimeSnapshotMaxBytes,
            maxLines = realtimeSnapshotMaxLines,
            tailWindowProfile = HostLogTailWindowProfile.COMPACT
        )
    }

    fun readLastNonBlankLine(logFile: File, maxChars: Int = 220): String {
        if (!logFile.isFile || !logFile.extension.equals("log", ignoreCase = true)) {
            return ""
        }

        val totalBytes = logFile.length()
        if (totalBytes <= 0L) {
            return ""
        }

        val readBytes = 16 * 1024
        val startOffset = (totalBytes - readBytes).coerceAtLeast(0L)
        val buffer = ByteArray((totalBytes - startOffset).toInt())
        RandomAccessFile(logFile, "r").use { input ->
            input.seek(startOffset)
            input.readFully(buffer)
        }

        var content = buffer.toString(Charsets.UTF_8)
        if (startOffset > 0L) {
            content = trimLeadingPartialLine(content)
        }

        val lastLine = content.lineSequence()
            .map { it.trimEnd() }
            .lastOrNull { it.isNotBlank() }
            .orEmpty()
        if (lastLine.isBlank()) {
            return ""
        }

        val normalizedLine = if (logFile.name.startsWith(startupLogPrefix, ignoreCase = true)) {
            normalizeLegacyStartupLogLine(lastLine)
        } else {
            lastLine
        }

        return if (normalizedLine.length <= maxChars) {
            normalizedLine
        } else {
            normalizedLine.takeLast(maxChars)
        }
    }

    fun buildBundleFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        return "$bundleFilePrefix-$timestamp.zip"
    }

    fun exportToUri(
        context: Context,
        targetUri: Uri,
        bundleFileName: String = buildBundleFileName(),
        includedRelativePaths: Set<String>? = null
    ): HostLogBundleExportResult {
        context.contentResolver.openOutputStream(targetUri)?.use { output ->
            return writeBundle(
                context = context,
                output = output,
                bundleFileName = bundleFileName,
                includedRelativePaths = includedRelativePaths
            )
        } ?: throw IllegalStateException("Failed to open export target.")
    }

    fun exportToPublicDownloads(
        context: Context,
        bundleFileName: String = buildBundleFileName(),
        includedRelativePaths: Set<String>? = null
    ): HostLogBundleExportResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, bundleFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create Downloads entry.")

        return try {
            val exportResult = exportToUri(
                context = context,
                targetUri = targetUri,
                bundleFileName = bundleFileName,
                includedRelativePaths = includedRelativePaths
            )
            resolver.update(
                targetUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            HostLogBundleExportResult(
                bundleFileName = exportResult.bundleFileName,
                zipPath = "${Environment.DIRECTORY_DOWNLOADS}/$bundleFileName",
                logFileCount = exportResult.logFileCount
            )
        } catch (error: Throwable) {
            resolver.delete(targetUri, null, null)
            throw error
        }
    }

    fun exportToCacheFile(
        context: Context,
        bundleFileName: String = buildBundleFileName(),
        includedRelativePaths: Set<String>? = null,
        compactForUpload: Boolean = false,
        feedbackText: String? = null,
        attachments: List<HostLogBundleAttachment> = emptyList(),
        maxArchiveSizeBytes: Long? = null
    ): Pair<File, HostLogBundleExportResult> {
        val cacheDir = File(context.applicationContext.cacheDir, "host-log-upload").apply { mkdirs() }
        val targetFile = File(cacheDir, bundleFileName)
        return try {
            val logLimitBytes = maxArchiveSizeBytes
                ?: if (compactForUpload) uploadMaxLogArchiveBytes else null
            targetFile.outputStream().use { output ->
                writeBundle(
                    context = context,
                    output = output,
                    bundleFileName = bundleFileName,
                    includedRelativePaths = includedRelativePaths,
                    compactForUpload = compactForUpload,
                    feedbackText = feedbackText,
                    attachments = attachments
                )
            }
            if (compactForUpload && logLimitBytes != null) {
                rewriteCompactUploadBundleNearLogLimit(
                    context = context,
                    targetFile = targetFile,
                    bundleFileName = bundleFileName,
                    includedRelativePaths = includedRelativePaths,
                    feedbackText = feedbackText,
                    attachments = attachments,
                    logLimitBytes = logLimitBytes
                )
            }
            val archiveSizeBytes = targetFile.length()
            targetFile to HostLogBundleExportResult(
                bundleFileName = bundleFileName,
                zipPath = targetFile.absolutePath,
                logFileCount = countZipLogEntries(targetFile),
                archiveSizeBytes = archiveSizeBytes
            )
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        }
    }

    private fun rewriteCompactUploadBundleNearLogLimit(
        context: Context,
        targetFile: File,
        bundleFileName: String,
        includedRelativePaths: Set<String>?,
        feedbackText: String?,
        attachments: List<HostLogBundleAttachment>,
        logLimitBytes: Long
    ) {
        val logsDir = logsDirectory(context).apply { mkdirs() }
        val prioritizedLogFiles = prioritizeCompactUploadLogFiles(
            collectLogFiles(logsDir, normalizeIncludedPaths(includedRelativePaths))
        )
        val minLogCount = if (prioritizedLogFiles.isEmpty()) 0 else 1
        var selectedLogCount = prioritizedLogFiles.size

        // 上传大小只约束日志证据包，不约束用户反馈图片；图片可能单张就超过目标值，不能因此丢失。
        val logProbeFile = File(targetFile.parentFile, "${targetFile.name}.logs-only.tmp")
        try {
            writeCompactUploadBundleForProbe(
                context = context,
                targetFile = logProbeFile,
                bundleFileName = bundleFileName,
                logFiles = prioritizedLogFiles.take(selectedLogCount)
            )
            while (logProbeFile.length() > logLimitBytes && selectedLogCount > minLogCount) {
                selectedLogCount -= 1
                writeCompactUploadBundleForProbe(
                    context = context,
                    targetFile = logProbeFile,
                    bundleFileName = bundleFileName,
                    logFiles = prioritizedLogFiles.take(selectedLogCount)
                )
            }
        } finally {
            logProbeFile.delete()
        }

        targetFile.outputStream().use { output ->
            writeBundle(
                context = context,
                output = output,
                bundleFileName = bundleFileName,
                compactForUpload = true,
                feedbackText = feedbackText,
                attachments = attachments,
                logFilesOverride = prioritizedLogFiles.take(selectedLogCount)
            )
        }
    }

    private fun writeCompactUploadBundleForProbe(
        context: Context,
        targetFile: File,
        bundleFileName: String,
        logFiles: List<File>
    ) {
        targetFile.outputStream().use { output ->
            writeBundle(
                context = context,
                output = output,
                bundleFileName = bundleFileName,
                compactForUpload = true,
                logFilesOverride = logFiles
            )
        }
    }

    fun exportCompactUploadBundleToCacheFile(
        context: Context,
        includedRelativePaths: Set<String>? = null,
        feedbackText: String? = null,
        attachments: List<HostLogBundleAttachment> = emptyList()
    ): Pair<File, HostLogBundleExportResult> {
        return exportToCacheFile(
            context = context,
            includedRelativePaths = includedRelativePaths,
            compactForUpload = true,
            feedbackText = feedbackText,
            attachments = attachments,
            maxArchiveSizeBytes = uploadMaxLogArchiveBytes
        )
    }

    fun uploadMaxArchiveSizeBytes(): Long {
        return uploadMaxLogArchiveBytes
    }

    fun uploadTavernServerHeadLineLimit(): Int {
        return uploadTavernServerHeadLines
    }

    fun defaultUploadRelativePaths(context: Context): Set<String> {
        val logsDir = logsDirectory(context).apply { mkdirs() }
        return HostLogUploadBundlePolicy.defaultUploadRelativePaths(
            logFiles = collectLogFiles(logsDir),
            logsDir = logsDir
        )
    }

    fun crashAutoUploadKey(context: Context): String? {
        val crashFile = crashLogFile(context)
        if (!crashFile.isFile || crashFile.length() <= 0L) {
            return null
        }
        return "${crashFile.name}:${crashFile.lastModified()}:${crashFile.length()}"
    }

    fun startCurrentServerTail(context: Context) {
        val applicationContext = context.applicationContext
        val logFile = currentServerLogFile(applicationContext)
        val logFileName = logFile.name

        synchronized(this) {
            if (observedServerLogFile?.absolutePath == logFile.absolutePath && serverTailFileObserver != null) {
                requestServerTailRefreshLocked()
                return
            }

            stopCurrentServerTailLocked(clearState = false)
            observedServerLogFile = logFile
            logFile.parentFile?.mkdirs()
            val logsDir = logFile.parentFile ?: return
            val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE
            serverTailFileObserver = object : FileObserver(logsDir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && !path.equals(logFileName, ignoreCase = true)) {
                        return
                    }

                    synchronized(this@HostLogManager) {
                        requestServerTailRefreshLocked()
                    }
                }
            }.also { observer ->
                observer.startWatching()
            }
            requestServerTailRefreshLocked()
        }
    }

    fun stopCurrentServerTail() {
        synchronized(this) {
            stopCurrentServerTailLocked(clearState = true)
        }
    }

    fun subscribeToLogChanges(
        context: Context,
        matcher: (String?) -> Boolean = { path ->
            path == null || path.endsWith(".log", ignoreCase = true)
        },
        onChanged: () -> Unit
    ): AutoCloseable {
        val applicationContext = context.applicationContext
        val subscriptionId = synchronized(this) {
            val id = nextSharedLogsSubscriberId++
            sharedLogsSubscribers[id] = SharedLogsSubscriber(
                matcher = matcher,
                onChanged = onChanged
            )
            ensureSharedLogsObserverLocked(applicationContext)
            id
        }

        return AutoCloseable {
            synchronized(this) {
                sharedLogsSubscribers.remove(subscriptionId)
                if (sharedLogsSubscribers.isEmpty()) {
                    sharedLogsFileObserver?.stopWatching()
                    sharedLogsFileObserver = null
                }
            }
        }
    }

    class AsyncWriter(
        private val logFileProvider: () -> File
    ) {
        private sealed class Command {
            data class Reset(val sessionId: Long) : Command()
            data class Append(val sessionId: Long, val line: String) : Command()
        }

        private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val commandChannel = Channel<Command>(Channel.UNLIMITED)
        private val writerJob = writerScope.launch {
            var activeSessionId = 0L
            for (command in commandChannel) {
                when (command) {
                    is Command.Reset -> {
                        activeSessionId = command.sessionId
                        runCatching {
                            val logFile = ensureLogFile()
                            logFile.writeText("")
                        }.onFailure { error ->
                            Log.e(asyncWriterLogTag, "Failed to reset log file.", error)
                        }
                    }

                    is Command.Append -> {
                        if (command.sessionId != activeSessionId) {
                            continue
                        }

                        runCatching {
                            val logFile = ensureLogFile()
                            logFile.appendText(command.line)
                        }.onFailure { error ->
                            Log.e(asyncWriterLogTag, "Failed to append log file.", error)
                        }
                    }
                }
            }
        }

        fun reset(sessionId: Long) {
            submit(Command.Reset(sessionId))
        }

        fun append(sessionId: Long, line: String) {
            submit(Command.Append(sessionId, line))
        }

        fun close() {
            commandChannel.close()
            // Drain any pending log commands without blocking the caller (typically Activity.onDestroy on the main thread).
            // Use a detached scope so cancelling writerScope here does not abort the drain itself.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val writerClosed = withTimeoutOrNull(asyncWriterShutdownTimeoutMillis) {
                        writerJob.join()
                        true
                    } ?: false
                    if (!writerClosed) {
                        writerJob.cancel()
                    }
                } finally {
                    writerScope.cancel()
                    coroutineContext[Job]?.cancel()
                }
            }
        }

        private fun submit(command: Command) {
            val result = commandChannel.trySend(command)
            if (result.isFailure) {
                Log.w(asyncWriterLogTag, "Ignoring log command because writer is already closed.")
            }
        }

        private fun ensureLogFile(): File {
            val logFile = logFileProvider()
            logFile.parentFile?.mkdirs()
            if (!logFile.exists()) {
                logFile.createNewFile()
            }
            return logFile
        }
    }

    private fun requireCurrentAppSessionId(context: Context): String {
        initializeForAppStart(context.applicationContext)
        return currentAppSessionId
            ?: error("Current app log session should be initialized before use.")
    }

    private fun cleanupRetainedLogsLocked(logsDir: File, currentSessionId: String) {
        val retainedSessionIds = LinkedHashSet<String>().apply {
            add(currentSessionId)
            addAll(
                logsDir.listFiles()
                    .orEmpty()
                    .mapNotNull { file -> extractSessionId(file.name) }
                    .distinct()
                    .sortedDescending()
                    .take((retainedAppSessionCount - 1).coerceAtLeast(0))
            )
        }

        logsDir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || !file.extension.equals("log", ignoreCase = true)) {
                return@forEach
            }

            val sessionId = extractSessionId(file.name)
            when {
                sessionId != null && sessionId !in retainedSessionIds -> {
                    file.delete()
                }

                file.name.equals(startupLogLegacyFileName, ignoreCase = true) ||
                    file.name.equals(tavernServerLogLegacyFileName, ignoreCase = true) ||
                    file.name.equals(rootfsRuntimeLogLegacyFileName, ignoreCase = true) -> {
                    file.delete()
                }
            }
        }
    }

    private fun extractSessionId(fileName: String): String? {
        return extractSessionId(fileName, startupLogPrefix)
            ?: extractSessionId(fileName, tavernServerLogPrefix)
            ?: extractSessionId(fileName, rootfsRuntimeLogPrefix)
            ?: extractSessionId(fileName, hostDiagnosticsLogPrefix)
            ?: extractSessionId(fileName, jsErrorLogPrefix)
    }

    private fun extractSessionId(fileName: String, prefix: String): String? {
        if (!fileName.endsWith(".log", ignoreCase = true) || !fileName.startsWith(prefix, ignoreCase = true)) {
            return null
        }

        return fileName.substring(prefix.length, fileName.length - 4)
            .takeIf { it.isNotBlank() }
    }

    private fun buildSessionId(nowMillis: Long): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(nowMillis))
    }

    private fun formatTimestamp(lastModified: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastModified))
    }

    private fun replaceLogFileContent(logFile: File, content: String) {
        logFile.parentFile?.mkdirs()
        logFile.writeText(content, Charsets.UTF_8)
    }

    private fun deleteLogFile(logFile: File) {
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    private fun resolveHostLogsDir(context: Context): File {
        return File(context.applicationContext.filesDir, "android-tavern/logs")
    }

    private fun resolveLogFile(context: Context, fileName: String): File {
        val safeFileName = File(fileName).name
        return File(logsDirectory(context), safeFileName)
    }

    private fun resolveDisplayName(context: Context, fileName: String): String {
        return HostLogExportPlanner.resolveDisplayName(fileName)
    }

    private fun displayOrder(fileName: String): Int {
        return HostLogExportPlanner.displayOrder(fileName)
    }

    internal fun buildExportOptions(logFiles: List<File>, logsDir: File): List<HostLogExportOption> {
        return HostLogExportPlanner.buildExportOptions(logFiles, logsDir)
    }

    private fun ensureSharedLogsObserverLocked(context: Context) {
        if (sharedLogsFileObserver != null) {
            return
        }

        val logsDir = logsDirectory(context).apply {
            mkdirs()
        }
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE
        sharedLogsFileObserver = object : FileObserver(logsDir, mask) {
            override fun onEvent(event: Int, path: String?) {
                val callbacks = synchronized(this@HostLogManager) {
                    resolveSharedLogCallbacksLocked(path)
                }
                if (callbacks.isEmpty()) {
                    return
                }

                mainHandler.post {
                    callbacks.forEach { callback ->
                        callback.invoke()
                    }
                }
            }
        }.also { observer ->
            observer.startWatching()
        }
    }

    private fun resolveSharedLogCallbacksLocked(path: String?): List<() -> Unit> {
        if (path != null && !path.endsWith(".log", ignoreCase = true)) {
            return emptyList()
        }

        return sharedLogsSubscribers.values
            .filter { subscriber -> path == null || subscriber.matcher(path) }
            .map { subscriber -> subscriber.onChanged }
    }

    private fun readTailContent(
        context: Context,
        logFile: File,
        maxChars: Int,
        maxBytes: Int,
        maxLines: Int,
        tailWindowProfile: HostLogTailWindowProfile
    ): String {
        val safeMaxChars = when (tailWindowProfile) {
            HostLogTailWindowProfile.FULL -> maxChars.coerceAtLeast(fullTailWindowMinChars)
            HostLogTailWindowProfile.COMPACT -> maxChars.coerceAtLeast(1)
        }
        val safeMaxBytes = when (tailWindowProfile) {
            HostLogTailWindowProfile.FULL -> maxBytes.coerceAtLeast(fullTailWindowMinBytes)
            HostLogTailWindowProfile.COMPACT -> maxBytes.coerceAtLeast(1)
        }
        val safeMaxLines = when (tailWindowProfile) {
            HostLogTailWindowProfile.FULL -> maxLines.coerceAtLeast(fullTailWindowMinLines)
            HostLogTailWindowProfile.COMPACT -> maxLines.coerceAtLeast(1)
        }
        val totalBytes = logFile.length()
        val startOffset = (totalBytes - safeMaxBytes).coerceAtLeast(0L)
        val readSize = (totalBytes - startOffset).toInt()
        if (readSize <= 0) {
            return ""
        }

        val buffer = ByteArray(readSize)
        RandomAccessFile(logFile, "r").use { input ->
            input.seek(startOffset)
            input.readFully(buffer)
        }

        var content = buffer.toString(Charsets.UTF_8)
        var truncated = startOffset > 0L
        if (startOffset > 0L) {
            val trimmedLeading = trimLeadingPartialLine(content)
            if (trimmedLeading !== content) {
                content = trimmedLeading
            }
        }

        val lines = content.lines()
        if (lines.size > safeMaxLines) {
            content = lines.takeLast(safeMaxLines).joinToString(separator = "\n")
            truncated = true
        }

        if (content.length > safeMaxChars) {
            content = content.takeLast(safeMaxChars)
            content = trimLeadingPartialLine(content)
            truncated = true
        }

        if (logFile.name.startsWith(startupLogPrefix, ignoreCase = true)) {
            content = normalizeLegacyStartupLogTimestamps(content)
        }

        if (!truncated || content.isBlank()) {
            return content
        }

        return buildString {
            append("日志内容过长，当前只显示末尾片段。")
            append("\n\n")
            append(content)
        }
    }

    private fun normalizeLegacyStartupLogTimestamps(content: String): String {
        if (content.isBlank()) {
            return content
        }

        return content.lineSequence()
            .joinToString(separator = "\n") { line -> normalizeLegacyStartupLogLine(line) }
    }

    private fun normalizeLegacyStartupLogLine(line: String): String {
        val firstSpaceIndex = line.indexOf(' ')
        if (firstSpaceIndex != 13) {
            return line
        }

        val epochMillis = line.substring(0, firstSpaceIndex).toLongOrNull() ?: return line
        val formattedPrefix = formatTimestampWithMillis(epochMillis)
        val payload = line.substring(firstSpaceIndex + 1)
        return if (payload.isBlank()) {
            formattedPrefix
        } else {
            "$formattedPrefix $payload"
        }
    }

    private fun formatTimestampWithMillis(epochMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(epochMillis))
    }

    private fun trimLeadingPartialLine(content: String): String {
        val newlineIndex = content.indexOf('\n')
        if (newlineIndex < 0 || newlineIndex >= content.lastIndex) {
            return content
        }

        return content.substring(newlineIndex + 1)
    }

    private fun writeBundle(
        context: Context,
        output: OutputStream,
        bundleFileName: String,
        includedRelativePaths: Set<String>? = null,
        compactForUpload: Boolean = false,
        feedbackText: String? = null,
        attachments: List<HostLogBundleAttachment> = emptyList(),
        logFilesOverride: List<File>? = null
    ): HostLogBundleExportResult {
        val logsDir = logsDirectory(context)
        logsDir.mkdirs()
        val normalizedIncludedPaths = normalizeIncludedPaths(includedRelativePaths)
        if (normalizedIncludedPaths != null && normalizedIncludedPaths.isEmpty()) {
            throw IllegalArgumentException("No log files were selected for export.")
        }
        val logFiles = logFilesOverride ?: collectLogFiles(logsDir, normalizedIncludedPaths)
        val baseInfo = HostLogBundleBaseInfoResolver.resolve(context, bundleFilePrefix)
        val logSummary = HostLogBundleInfoFormatter.summarize(logFiles, logsDir)
        ZipOutputStream(output).use { zipOut ->
            // 日志包根目录固定输出一份文本摘要和一份结构化 JSON，方便人工查看与自动分析共用同一套诊断信息。
            zipOut.putNextEntry(ZipEntry(bundleInfoEntryName))
            zipOut.write(HostLogBundleInfoFormatter.buildText(baseInfo, logSummary).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            zipOut.putNextEntry(ZipEntry(bundleInfoJsonEntryName))
            zipOut.write(HostLogBundleInfoFormatter.buildJson(baseInfo, logSummary).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            logFiles.forEach { logFile ->
                val relativePath = logFile.relativeTo(logsDir).invariantSeparatorsPath
                zipOut.putNextEntry(ZipEntry(relativePath))
                if (compactForUpload) {
                    writeCompactUploadLogEntry(context, logFile, zipOut)
                } else {
                    logFile.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                }
                zipOut.closeEntry()
            }

            feedbackText
                ?.trim()
                ?.takeIf { text -> text.isNotBlank() }
                ?.let { text ->
                    zipOut.putNextEntry(ZipEntry("feedback/feedback.txt"))
                    zipOut.write(text.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }

            attachments.forEachIndexed { index, attachment ->
                context.contentResolver.openInputStream(attachment.sourceUri)?.use { input ->
                    zipOut.putNextEntry(
                        ZipEntry("feedback/${sanitizeAttachmentEntryName(attachment.entryName, index)}")
                    )
                    input.copyTo(zipOut)
                    zipOut.closeEntry()
                }
            }
        }

        return HostLogBundleExportResult(
            bundleFileName = bundleFileName,
            logFileCount = logFiles.size
        )
    }

    private fun writeCompactUploadLogEntry(context: Context, logFile: File, output: OutputStream) {
        val content = if (HostLogUploadBundlePolicy.isTavernServerLog(logFile.name)) {
            HostLogUploadBundlePolicy.compactTavernServerLogContent(logFile, uploadTavernServerHeadLines)
        } else {
            readTailContent(
                context = context,
                logFile = logFile,
                maxChars = uploadCompactMaxChars,
                maxBytes = uploadCompactMaxBytes,
                maxLines = uploadCompactMaxLines,
                tailWindowProfile = HostLogTailWindowProfile.COMPACT
            )
        }
        output.write(content.toByteArray(Charsets.UTF_8))
    }

    private fun sanitizeAttachmentEntryName(requestedEntryName: String, fallbackIndex: Int): String {
        return HostLogUploadBundlePolicy.sanitizeAttachmentEntryName(requestedEntryName, fallbackIndex)
    }

    private fun normalizeIncludedPaths(includedRelativePaths: Set<String>?): Set<String>? {
        return includedRelativePaths
            ?.map { path -> path.replace('\\', '/').trimStart('/') }
            ?.toSet()
    }

    private fun prioritizeCompactUploadLogFiles(logFiles: List<File>): List<File> {
        return logFiles.sortedWith(
            compareBy<File> { file -> compactUploadPriority(file.name) }
                .thenByDescending { file -> file.lastModified() }
                .thenBy { file -> file.name.lowercase(Locale.ROOT) }
        )
    }

    private fun compactUploadPriority(fileName: String): Int {
        val normalizedName = fileName.lowercase(Locale.ROOT)
        return when {
            normalizedName == crashLogFileName -> 0
            normalizedName == exitInfoLogFileName -> 1
            normalizedName.startsWith("host-diagnostics-") -> 2
            normalizedName.startsWith("startup-") -> 3
            normalizedName.startsWith("js-error-") -> 4
            normalizedName.startsWith("rootfs-runtime-") -> 5
            normalizedName.startsWith("extension-") -> 6
            HostLogUploadBundlePolicy.isTavernServerLog(normalizedName) -> 7
            else -> 8
        }
    }

    private fun countZipLogEntries(zipFile: File): Int {
        if (!zipFile.isFile) {
            return 0
        }
        return java.util.zip.ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().count { entry ->
                entry.name.endsWith(".log", ignoreCase = true)
            }
        }
    }

    internal fun collectLogFiles(logsDir: File, includedRelativePaths: Set<String>? = null): List<File> {
        return HostLogExportPlanner.collectLogFiles(
            logsDir = logsDir,
            includedRelativePaths = includedRelativePaths
        )
    }

    private fun stopCurrentServerTailLocked(clearState: Boolean) {
        serverTailFileObserver?.stopWatching()
        serverTailFileObserver = null
        observedServerLogFile = null
        serverTailRefreshPending = false
        serverTailRefreshJob?.cancel()
        serverTailRefreshJob = null
        if (clearState) {
            mutableLatestTavernServerLine.value = ""
        }
    }

    private fun requestServerTailRefreshLocked() {
        if (serverTailRefreshJob?.isActive == true) {
            serverTailRefreshPending = true
            return
        }

        val logFile = observedServerLogFile ?: return
        serverTailRefreshJob = tailScope.launch {
            do {
                synchronized(this@HostLogManager) {
                    serverTailRefreshPending = false
                }

                mutableLatestTavernServerLine.value = readLastNonBlankLine(logFile)

                val shouldContinue = synchronized(this@HostLogManager) {
                    serverTailRefreshPending && observedServerLogFile?.absolutePath == logFile.absolutePath
                }
                if (!shouldContinue) {
                    break
                }
            } while (true)
        }
    }
}
