package com.jm.sillydroid.data.logs

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogUploadBundlePolicyTest {
    @Test
    fun defaultUploadRelativePathsIncludeTavernServerLogsForSafeStartupSlice() {
        val logsDir = createTempDirectory(prefix = "host-log-upload-default").toFile()
        try {
            val startup = File(logsDir, "startup-20260605-010101-001.log").apply { writeText("startup") }
            val tavern = File(logsDir, "sillydroid-server-20260605-010101-001.log").apply { writeText("server") }
            val diagnostics = File(logsDir, "host-diagnostics-20260605-010101-001.log").apply { writeText("diag") }

            val relativePaths = HostLogUploadBundlePolicy.defaultUploadRelativePaths(
                logFiles = listOf(startup, tavern, diagnostics),
                logsDir = logsDir
            )

            assertEquals(
                setOf(
                    "startup-20260605-010101-001.log",
                    "sillydroid-server-20260605-010101-001.log",
                    "host-diagnostics-20260605-010101-001.log"
                ),
                relativePaths
            )
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun defaultUploadRelativePathsIncludeExitInfoTraceArtifacts() {
        val logsDir = createTempDirectory(prefix = "host-log-upload-exit-trace").toFile()
        try {
            val exitInfo = File(logsDir, HostLogManager.exitInfoLogFileName).apply { writeText("exit-info") }
            File(logsDir, HostLogManager.exitInfoTraceDirectoryName).mkdirs()
            val trace = File(
                logsDir,
                "${HostLogManager.exitInfoTraceDirectoryName}/history-0-1000-pid-123-reason-crash_native-webview.trace"
            ).apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
            val tavern = File(logsDir, "sillydroid-server-20260605-010101-001.log").apply { writeText("server") }

            val relativePaths = HostLogUploadBundlePolicy.defaultUploadRelativePaths(
                logFiles = listOf(exitInfo, trace, tavern),
                logsDir = logsDir
            )

            assertEquals(
                setOf(
                    HostLogManager.exitInfoLogFileName,
                    "${HostLogManager.exitInfoTraceDirectoryName}/history-0-1000-pid-123-reason-crash_native-webview.trace",
                    "sillydroid-server-20260605-010101-001.log"
                ),
                relativePaths
            )
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun compactTavernServerLogContentStopsAfterOpenUrlSeparator() {
        val logsDir = createTempDirectory(prefix = "host-log-upload-tavern-listen").toFile()
        try {
            val tavern = File(logsDir, "sillydroid-server-20260605-010101-001.log").apply {
                writeText(
                    listOf(
                        "SillyTavern 1.18.0",
                        "Compiling frontend libraries...",
                        "webpack 5.105.4 compiled successfully in 2720 ms",
                        "SillyTavern is listening on IPv4: 0.0.0.0:8000",
                        "",
                        "=================================================",
                        "",
                        "Go to: http://127.0.0.1:8000/ to open SillyTavern",
                        "",
                        "=================================================",
                        "",
                        "New connection from 127.0.0.1; User Agent: Dalvik/2.1.0",
                        "chat payload should not be uploaded"
                    ).joinToString(separator = "\n")
                )
            }

            val content = HostLogUploadBundlePolicy.compactTavernServerLogContent(tavern)

            assertTrue(content.contains("上传包只保留服务监听完成前的启动片段"))
            assertTrue(content.contains("SillyTavern is listening on IPv4: 0.0.0.0:8000"))
            assertTrue(content.contains("Go to: http://127.0.0.1:8000/ to open SillyTavern"))
            assertFalse(content.contains("New connection from 127.0.0.1"))
            assertFalse(content.contains("chat payload should not be uploaded"))
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun compactTavernServerLogContentFallsBackToLineLimitWhenOpenUrlIsMissing() {
        val logsDir = createTempDirectory(prefix = "host-log-upload-tavern-head").toFile()
        try {
            val tavern = File(logsDir, "sillydroid-server-20260605-010101-001.log").apply {
                writeText((1..60).joinToString(separator = "\n") { line -> "line-$line" })
            }

            val content = HostLogUploadBundlePolicy.compactTavernServerLogContent(tavern)

            assertTrue(content.contains("上传包只保留服务监听完成前的启动片段"))
            assertTrue(content.contains("line-1"))
            assertTrue(content.contains("line-50"))
            assertFalse(content.contains("line-51"))
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun sanitizeAttachmentEntryNameDropsPathSegments() {
        assertEquals(
            "photo.png",
            HostLogUploadBundlePolicy.sanitizeAttachmentEntryName("../private/photo.png", fallbackIndex = 0)
        )
        assertEquals(
            "image-2",
            HostLogUploadBundlePolicy.sanitizeAttachmentEntryName("", fallbackIndex = 1)
        )
    }
}
