package com.jm.sillydroid.data.logs

import com.jm.sillydroid.core.model.logs.HostLogBundleUploadRequestConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogBundleUploaderTest {
    @Test
    fun uploadsZipArchiveWithRequiredMetadata() {
        val tempDir = createTempDirectory(prefix = "host-log-upload-test").toFile()
        val received = mutableListOf<String>()
        val latch = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/admin/projects/sillydroid/crash-logs") { exchange ->
            received += exchange.requestHeaders.getFirst("X-SillyDroid-Crash-Log-Key").orEmpty()
            received += exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            received += exchange.requestBody.bufferedReader().use { reader -> reader.readText() }
            val response = """{"crashLogId":7,"projectKey":"sillydroid","storedAt":"2026-06-05T01:03:04Z","archiveFileName":"logs.zip","archiveSizeBytes":3,"sha256":"abc"}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { output -> output.write(response.toByteArray()) }
            latch.countDown()
        }
        server.start()
        try {
            val archiveFile = tempDir.resolve("logs.zip").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val result = HostLogBundleUploader(
                metadataProvider = FakeHostLogUploadMetadataProvider()
            ).upload(
                archiveFile = archiveFile,
                config = HostLogBundleUploadRequestConfig(
                    uploadUrl = "http://127.0.0.1:${server.address.port}/api/admin/projects/sillydroid/crash-logs",
                    writerApiKey = "secret",
                    source = "test-upload"
                )
            )

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(7, result.crashLogId)
            assertEquals("secret", received[0])
            assertTrue(received[1].startsWith("multipart/form-data; boundary=sillydroid-"))
            assertTrue(received[2].contains("name=\"archive\"; filename=\"logs.zip\""))
            assertTrue(received[2].contains("name=\"occurredAt\""))
            assertTrue(received[2].contains("name=\"packageName\""))
            assertTrue(received[2].contains("name=\"versionName\""))
            assertTrue(received[2].contains("name=\"versionCode\""))
            assertTrue(received[2].contains("name=\"installationId\""))
            assertTrue(received[2].contains("test-upload"))
        } finally {
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    private class FakeHostLogUploadMetadataProvider : HostLogUploadMetadataProvider {
        override fun packageName(): String = "com.jm.sillydroid"
        override fun versionName(): String = "1.0.1"
        override fun versionCode(): String = "100"
        override fun installationId(): String = "install-a"
        override fun deviceModel(): String = "Pixel 8"
        override fun androidVersion(): String = "15"
        override fun abi(): String = "arm64-v8a"
        override fun buildFingerprint(): String = "fingerprint-a"
    }
}
