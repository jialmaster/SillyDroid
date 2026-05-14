package com.jm.sillydroid

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class HostLogSnapshot(
    val sourceFile: File,
    val fileName: String,
    val displayName: String,
    val updatedAt: String,
    val content: String
)

internal data class HostLogEntry(
    val sourceFile: File,
    val fileName: String,
    val displayName: String,
    val updatedAt: String,
    val lastModified: Long
)

internal object HostLogReader {
    private const val defaultMaxChars = 320_000
    private const val defaultMaxBytes = 768 * 1024
    private const val defaultMaxLines = 6_000
    private const val startupLogFileName = "startup.log"

    private val visibleLogFileNames = linkedSetOf(
        startupLogFileName,
        "sillydroid-server.log",
        "rootfs-runtime.log"
    )

    fun listEntries(context: Context): List<HostLogEntry> {
        val paths = HostPaths.from(context)
        paths.ensureWorkingDirectories()
        return paths.logsDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("log", ignoreCase = true) &&
                    file.name.lowercase(Locale.ROOT) in visibleLogFileNames
            }
            .map { file ->
                HostLogEntry(
                    sourceFile = file,
                    fileName = file.name,
                    displayName = resolveDisplayName(context, file.name),
                    updatedAt = formatTimestamp(file.lastModified()),
                    lastModified = file.lastModified()
                )
            }
            .sortedWith(compareBy<HostLogEntry>({ displayOrder(it.fileName) }).thenByDescending { it.lastModified })
    }

    fun readPreferredSnapshot(
        context: Context,
        preferTavernServerLog: Boolean,
        maxChars: Int = defaultMaxChars,
        maxBytes: Int = defaultMaxBytes,
        maxLines: Int = defaultMaxLines,
        entries: List<HostLogEntry>? = null
    ): HostLogSnapshot? {
        val availableEntries = entries ?: listEntries(context)
        val selectedEntry = when {
            availableEntries.isEmpty() -> null
            preferTavernServerLog -> availableEntries.firstOrNull { entry ->
                entry.fileName.equals("sillydroid-server.log", ignoreCase = true)
            } ?: availableEntries.firstOrNull { entry ->
                entry.fileName.equals("startup.log", ignoreCase = true)
            } ?: availableEntries.first()
            else -> availableEntries.firstOrNull { entry ->
                entry.fileName.equals("startup.log", ignoreCase = true)
            } ?: availableEntries.firstOrNull { entry ->
                entry.fileName.equals("rootfs-runtime.log", ignoreCase = true)
            } ?: availableEntries.first()
        }
            ?: return null

        return readSnapshot(
            context = context,
            logFile = selectedEntry.sourceFile,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines,
            entry = selectedEntry
        )
    }

    fun readSnapshot(
        context: Context,
        logFile: File,
        maxChars: Int = defaultMaxChars,
        maxBytes: Int = defaultMaxBytes,
        maxLines: Int = defaultMaxLines,
        entry: HostLogEntry? = null
    ): HostLogSnapshot? {
        if (!logFile.isFile || !logFile.extension.equals("log", ignoreCase = true)) {
            return null
        }

        val resolvedEntry = entry ?: HostLogEntry(
            sourceFile = logFile,
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
            maxLines = maxLines
        )

        return HostLogSnapshot(
            sourceFile = resolvedEntry.sourceFile,
            fileName = resolvedEntry.fileName,
            displayName = resolvedEntry.displayName,
            updatedAt = resolvedEntry.updatedAt,
            content = content
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

        val normalizedLine = if (logFile.name.equals(startupLogFileName, ignoreCase = true)) {
            normalizeLegacyStartupLogLine(lastLine)
        } else {
            lastLine
        }

        if (normalizedLine.length <= maxChars) {
            return normalizedLine
        }

        return normalizedLine.takeLast(maxChars)
    }

    private fun formatTimestamp(lastModified: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastModified))
    }

    private fun resolveDisplayName(context: Context, fileName: String): String {
        val normalizedName = fileName.lowercase(Locale.ROOT)
        return when {
            normalizedName == "sillydroid-server.log" -> context.getString(R.string.bootstrap_settings_logs_name_tavern_server)
            normalizedName == "startup.log" -> context.getString(R.string.bootstrap_settings_logs_name_startup)
            normalizedName == "rootfs-runtime.log" -> context.getString(R.string.bootstrap_settings_logs_name_runtime)
            normalizedName.startsWith("extension-install-preview-") -> context.getString(R.string.bootstrap_settings_logs_name_extension_preview)
            normalizedName.startsWith("extension-reinstall-") -> context.getString(R.string.bootstrap_settings_logs_name_extension_reinstall)
            normalizedName.startsWith("extension-") -> context.getString(R.string.bootstrap_settings_logs_name_extension_runtime)
            else -> context.getString(R.string.bootstrap_settings_logs_name_other)
        }
    }

    private fun displayOrder(fileName: String): Int {
        return when (fileName.lowercase(Locale.ROOT)) {
            "startup.log" -> 0
            "sillydroid-server.log" -> 1
            "rootfs-runtime.log" -> 2
            else -> Int.MAX_VALUE
        }
    }

    private fun readTailContent(
        context: Context,
        logFile: File,
        maxChars: Int,
        maxBytes: Int,
        maxLines: Int
    ): String {
        val safeMaxChars = maxChars.coerceAtLeast(120_000)
        val safeMaxBytes = maxBytes.coerceAtLeast(256 * 1024)
        val safeMaxLines = maxLines.coerceAtLeast(2_000)
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

        if (logFile.name.equals(startupLogFileName, ignoreCase = true)) {
            content = normalizeLegacyStartupLogTimestamps(content)
        }

        if (!truncated || content.isBlank()) {
            return content
        }

        return buildString {
            append(context.getString(R.string.bootstrap_settings_logs_truncated_prefix))
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
        val firstLineBreak = content.indexOf('\n')
        if (firstLineBreak <= 0 || firstLineBreak >= content.lastIndex) {
            return content
        }

        return content.substring(firstLineBreak + 1)
    }
}
