package com.jm.sillydroid.data.logs

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogCrashCleanupTest {
    @Test
    fun clearUploadedCrashLogFileDeletesExistingCrashLog() {
        val logsDir = createTempDirectory(prefix = "host-log-crash-cleanup").toFile()
        try {
            val crashLog = File(logsDir, "app-last-crash.log").apply {
                writeText("timestamp=2026-06-15 14:59:00\nhostVersion=1.0.1.56\n")
            }

            assertTrue(UploadedCrashLogCleaner.clear(crashLog))

            assertFalse(crashLog.exists())
        } finally {
            logsDir.deleteRecursively()
        }
    }

    @Test
    fun clearUploadedCrashLogFileIgnoresMissingCrashLog() {
        val logsDir = createTempDirectory(prefix = "host-log-crash-cleanup-missing").toFile()
        try {
            val crashLog = File(logsDir, "app-last-crash.log")

            assertTrue(UploadedCrashLogCleaner.clear(crashLog))
        } finally {
            logsDir.deleteRecursively()
        }
    }
}
