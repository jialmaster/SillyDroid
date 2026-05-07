package com.stai.sillytavern

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class HostLogSnapshot(
    val sourceFile: File,
    val fileName: String,
    val updatedAt: String,
    val content: String
)

internal object HostLogReader {
    private const val defaultMaxChars = 320_000
    private const val defaultMaxBytes = 768 * 1024
    private const val defaultMaxLines = 6_000

    fun readLatestSnapshot(
        context: Context,
        maxChars: Int = defaultMaxChars,
        maxBytes: Int = defaultMaxBytes,
        maxLines: Int = defaultMaxLines
    ): HostLogSnapshot? {
        val paths = HostPaths.from(context)
        paths.ensureWorkingDirectories()
        val latestLog = paths.logsDir.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.extension.equals("log", ignoreCase = true) }
            .maxByOrNull(File::lastModified)
            ?: return null

        val content = readTailContent(
            context = context,
            logFile = latestLog,
            maxChars = maxChars,
            maxBytes = maxBytes,
            maxLines = maxLines
        )

        return HostLogSnapshot(
            sourceFile = latestLog,
            fileName = latestLog.name,
            updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(latestLog.lastModified())),
            content = content
        )
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

        if (!truncated || content.isBlank()) {
            return content
        }

        return buildString {
            append(context.getString(R.string.bootstrap_settings_logs_truncated_prefix))
            append("\n\n")
            append(content)
        }
    }

    private fun trimLeadingPartialLine(content: String): String {
        val firstLineBreak = content.indexOf('\n')
        if (firstLineBreak <= 0 || firstLineBreak >= content.lastIndex) {
            return content
        }

        return content.substring(firstLineBreak + 1)
    }
}