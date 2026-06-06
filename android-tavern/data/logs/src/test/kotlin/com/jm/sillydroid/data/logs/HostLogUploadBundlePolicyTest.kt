package com.jm.sillydroid.data.logs

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogUploadBundlePolicyTest {
    @Test
    fun defaultUploadRelativePathsExcludeTavernServerLogs() {
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
                    "host-diagnostics-20260605-010101-001.log"
                ),
                relativePaths
            )
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun compactTavernServerLogContentKeepsOnlyFirstFiftyLines() {
        val logsDir = createTempDirectory(prefix = "host-log-upload-tavern-head").toFile()
        try {
            val tavern = File(logsDir, "sillydroid-server-20260605-010101-001.log").apply {
                writeText((1..60).joinToString(separator = "\n") { line -> "line-$line" })
            }

            val content = HostLogUploadBundlePolicy.compactTavernServerLogContent(tavern)

            assertTrue(content.contains("上传包只保留开头 50 行"))
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
