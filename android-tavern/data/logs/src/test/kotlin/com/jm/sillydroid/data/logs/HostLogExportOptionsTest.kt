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

    @Test
    fun exitInfoOptionIncludesRawTraceArtifacts() {
        val logsDir = createTempDirectory(prefix = "host-log-export-exit-traces").toFile()
        try {
            File(logsDir, HostLogManager.exitInfoLogFileName).apply { writeText("exit-info") }
            File(logsDir, HostLogManager.exitInfoTraceDirectoryName).mkdirs()
            File(
                logsDir,
                "${HostLogManager.exitInfoTraceDirectoryName}/history-0-1000-pid-123-reason-crash_native-webview.trace"
            ).apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }

            val logFiles = HostLogExportPlanner.collectLogFiles(logsDir)
            val options = HostLogExportPlanner.buildExportOptions(
                logFiles = logFiles,
                logsDir = logsDir
            )

            val exitInfoOption = options.first { it.displayName == "应用退出信息" }
            assertEquals(
                setOf(
                    HostLogManager.exitInfoLogFileName,
                    "${HostLogManager.exitInfoTraceDirectoryName}/history-0-1000-pid-123-reason-crash_native-webview.trace"
                ),
                exitInfoOption.relativePaths
            )
            assertTrue(exitInfoOption.selectedByDefault)
            assertFalse(exitInfoOption.containsSensitiveContent)
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun buildExportOptionsGroupsPluginMaintenanceLogs() {
        val logsDir = createTempDirectory(prefix = "host-log-export-plugin-maintenance").toFile()
        try {
            val previewLog = File(logsDir, "plugin-preview-auto-continue-1783171329475.log").apply { writeText("preview") }
            val installLog = File(logsDir, "plugin-install-auto-continue-1783171329476.log").apply { writeText("install") }
            val updateLog = File(logsDir, "repository-update-check-auto-continue-1783171329477.log").apply { writeText("update") }
            val npmLog = File(logsDir, "server-plugin-npm-install-auto-continue-1783171329478.log").apply { writeText("npm") }

            val options = HostLogExportPlanner.buildExportOptions(
                logFiles = listOf(previewLog, installLog, updateLog, npmLog),
                logsDir = logsDir
            )

            assertEquals(
                setOf("plugin-preview-auto-continue-1783171329475.log"),
                options.first { it.displayName == "插件预检日志" }.relativePaths
            )
            assertEquals(
                setOf("plugin-install-auto-continue-1783171329476.log"),
                options.first { it.displayName == "插件安装日志" }.relativePaths
            )
            assertEquals(
                setOf("repository-update-check-auto-continue-1783171329477.log"),
                options.first { it.displayName == "仓库更新检测日志" }.relativePaths
            )
            assertEquals(
                setOf("server-plugin-npm-install-auto-continue-1783171329478.log"),
                options.first { it.displayName == "插件依赖安装日志" }.relativePaths
            )
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun logTabShowsPluginMaintenanceLogs() {
        assertTrue(HostLogExportPlanner.isVisibleInLogTab("plugin-preview-auto-continue-1783171329475.log"))
        assertTrue(HostLogExportPlanner.isVisibleInLogTab("plugin-install-auto-continue-1783171329476.log"))
        assertTrue(HostLogExportPlanner.isVisibleInLogTab("repository-update-check-auto-continue-1783171329477.log"))
        assertTrue(HostLogExportPlanner.isVisibleInLogTab("server-plugin-npm-install-auto-continue-1783171329478.log"))
    }
}
