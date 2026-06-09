package com.jm.sillydroid.data.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateHttpDownloaderTest {
    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun resumesPartFileWithRangeRequestWhenServerSupportsPartialContent() {
        val body = "hello-world".toByteArray()
        var receivedRange: String? = null
        startServer { exchange ->
            receivedRange = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Length", "6")
            exchange.sendResponseHeaders(206, 6)
            exchange.responseBody.use { output -> output.write(body.copyOfRange(5, body.size)) }
        }
        val partFile = Files.createTempDirectory("sillydroid-update").resolve("update.apk.part").toFile()
        partFile.writeBytes(body.copyOfRange(0, 5))

        val result = AppUpdateHttpDownloader().download(
            url = serverUrl(),
            partFile = partFile,
            stalledTimeoutMillis = 10 * 60 * 1_000L,
            onProgress = {}
        )

        assertEquals("bytes=5-", receivedRange)
        assertTrue(result is AppUpdateHttpDownloadResult.Completed)
        assertEquals("hello-world", partFile.readText())
    }

    @Test
    fun restartsFromZeroWhenServerIgnoresRangeRequest() {
        val body = "fresh-body".toByteArray()
        startServer { exchange ->
            exchange.responseHeaders.add("Content-Length", body.size.toString())
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }
        val partFile = Files.createTempDirectory("sillydroid-update").resolve("update.apk.part").toFile()
        partFile.writeText("stale")

        AppUpdateHttpDownloader().download(
            url = serverUrl(),
            partFile = partFile,
            stalledTimeoutMillis = 10 * 60 * 1_000L,
            onProgress = {}
        )

        assertEquals("fresh-body", partFile.readText())
    }

    private fun startServer(handler: (HttpExchange) -> Unit) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/update.apk") { exchange -> handler(exchange) }
            start()
        }
    }

    private fun serverUrl(): String {
        val port = requireNotNull(server).address.port
        return "http://127.0.0.1:$port/update.apk"
    }
}
