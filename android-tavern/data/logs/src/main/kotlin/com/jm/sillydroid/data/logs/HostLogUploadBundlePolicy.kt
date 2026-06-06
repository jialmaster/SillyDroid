package com.jm.sillydroid.data.logs

import java.io.File

internal object HostLogUploadBundlePolicy {
    private const val tavernServerLogPrefix = "sillydroid-server-"
    const val maxCompactLogArchiveBytes: Long = 10L * 1024L * 1024L
    const val tavernServerHeadLines = 50
    const val compactMaxBytes = 96 * 1024
    const val compactMaxChars = 160_000
    const val compactMaxLines = 1_000

    fun defaultUploadRelativePaths(logFiles: List<File>, logsDir: File): Set<String> {
        return logFiles
            .filterNot { file -> isTavernServerLog(file.name) }
            .map { file -> file.relativeTo(logsDir).invariantSeparatorsPath }
            .toSet()
    }

    fun compactTavernServerLogContent(logFile: File, maxLines: Int = tavernServerHeadLines): String {
        if (!logFile.isFile || maxLines <= 0) {
            return ""
        }
        var lineCount = 0
        var truncated = false
        val content = buildString {
            logFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (lineCount >= maxLines) {
                        truncated = true
                        break
                    }
                    if (lineCount > 0) {
                        append('\n')
                    }
                    append(line)
                    lineCount += 1
                }
            }
        }
        if (!truncated) {
            return content
        }
        return buildString {
            appendLine("酒馆服务日志可能包含聊天收发内容，上传包只保留开头 $maxLines 行。")
            appendLine()
            append(content)
        }
    }

    fun sanitizeAttachmentEntryName(requestedEntryName: String, fallbackIndex: Int): String {
        return File(requestedEntryName).name
            .replace('\\', '_')
            .replace('/', '_')
            .trim()
            .ifBlank { "image-${fallbackIndex + 1}" }
            .take(120)
    }

    fun isTavernServerLog(fileName: String): Boolean {
        return fileName.startsWith(tavernServerLogPrefix, ignoreCase = true)
    }
}
