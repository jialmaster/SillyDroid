package com.jm.sillydroid.data.logs

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogExportOptionsTest {
    @Test
    fun buildExportOptionsDefaultsToExcludeSensitiveTavernLogs() {
        val logsDir = createTempDirectory(prefix = "host-log-export-options").toFile()
        try {
            val startupLog = File(logsDir, "startup-20260518-010101-001.log").apply { writeText("startup") }
            val tavernLog = File(logsDir, "sillydroid-server-20260518-010101-001.log").apply { writeText("server") }

            val options = HostLogExportPlanner.buildExportOptions(
                logFiles = listOf(startupLog, tavernLog),
                logsDir = logsDir
            )

            val startupOption = options.first { it.displayName == "启动日志" }
            val tavernOption = options.first { it.displayName == "酒馆服务日志" }
            assertTrue(startupOption.selectedByDefault)
            assertFalse(startupOption.containsSensitiveContent)
            assertFalse(tavernOption.selectedByDefault)
            assertTrue(tavernOption.containsSensitiveContent)
            assertEquals(setOf("sillydroid-server-20260518-010101-001.log"), tavernOption.relativePaths)
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun buildExportOptionsKeepsStableTypeRowsEvenWhenSomeLogsDoNotExistYet() {
        val logsDir = createTempDirectory(prefix = "host-log-export-stable-types").toFile()
        try {
            val startupLog = File(logsDir, "startup-20260518-010101-001.log").apply { writeText("startup") }

            val options = HostLogExportPlanner.buildExportOptions(
                logFiles = listOf(startupLog),
                logsDir = logsDir
            )

            val startupOption = options.first { it.displayName == "启动日志" }
            val jsOption = options.first { it.displayName == "WebView JS 报错" }
            val diagnosticsOption = options.first { it.displayName == "宿主诊断日志" }
            val tavernOption = options.first { it.displayName == "酒馆服务日志" }

            assertEquals(setOf("startup-20260518-010101-001.log"), startupOption.relativePaths)
            assertTrue(jsOption.relativePaths.isEmpty())
            assertTrue(diagnosticsOption.relativePaths.isEmpty())
            assertTrue(tavernOption.relativePaths.isEmpty())
            assertFalse(tavernOption.selectedByDefault)
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun collectLogFilesHonorsSelectedRelativePaths() {
        val logsDir = createTempDirectory(prefix = "host-log-export-filter").toFile()
        try {
            File(logsDir, "startup-20260518-010101-001.log").apply { writeText("startup") }
            File(logsDir, "sillydroid-server-20260518-010101-001.log").apply { writeText("server") }
            File(logsDir, "host-diagnostics-20260518-010101-001.log").apply { writeText("diag") }

            val filtered = HostLogExportPlanner.collectLogFiles(
                logsDir = logsDir,
                includedRelativePaths = setOf(
                    "startup-20260518-010101-001.log",
                    "host-diagnostics-20260518-010101-001.log"
                )
            )

            assertEquals(
                listOf(
                    "host-diagnostics-20260518-010101-001.log",
                    "startup-20260518-010101-001.log"
                ),
                filtered.map { file -> file.name }
            )
        } finally {
            logsDir.deleteRecursively()
        }
    }
}
