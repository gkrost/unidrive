package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneDriveSlowUploadTimeoutTest {
    private lateinit var tokenDir: Path
    private lateinit var server: ServerSocket

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-slow-upload")
        server = ServerSocket(0)
    }

    @AfterTest
    fun tearDown() {
        server.close()
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `simple and chunk uploads allow the server more than the default socket timeout to answer`() =
        runTest {
            val finished = CountDownLatch(1)
            val serverFailure = AtomicReference<Throwable?>()
            val serverThread =
                thread(isDaemon = true, name = "onedrive-slow-upload-test-server") {
                    try {
                        repeat(3) {
                            server.accept().use { socket ->
                                val request = readRequest(socket.getInputStream())
                                val (status, body, delayMs) =
                                    when {
                                        request.startsWith("POST ") ->
                                            Triple(
                                                "200 OK",
                                                """{"uploadUrl":"http://127.0.0.1:${server.localPort}/session","expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                                0L,
                                            )
                                        request.startsWith("PUT ") && request.contains("/session ") ->
                                            Triple("201 Created", driveItemJson("large-id", "large.bin", 5L * 1024 * 1024), 300L)
                                        else -> Triple("200 OK", driveItemJson("small-id", "small.txt", 4), 300L)
                                    }
                                if (delayMs > 0) Thread.sleep(delayMs)
                                respond(socket, status, body)
                            }
                        }
                    } catch (e: Throwable) {
                        serverFailure.set(e)
                    } finally {
                        finished.countDown()
                    }
                }

            val service = graphWithShortDefaultSocketTimeout()
            val largeFile = tokenDir.resolve("large.bin")
            Files.write(largeFile, ByteArray(5 * 1024 * 1024) { 7 })

            val smallItem = service.uploadSimple("/small.txt", byteArrayOf(1, 2, 3, 4))
            val largeItem = service.uploadLargeFile(largeFile, "/large.bin")

            assertEquals("small.txt", smallItem.name)
            assertEquals("large.bin", largeItem.name)
            assertTrue(finished.await(5, TimeUnit.SECONDS), "loopback server did not finish all upload requests")
            assertEquals(null, serverFailure.get())
            serverThread.join(1_000)
            service.close()
        }

    private fun graphWithShortDefaultSocketTimeout(): GraphApiService {
        val service =
            GraphApiService(
                config = OneDriveConfig(tokenPath = tokenDir),
                tokenProvider = { _ -> "test-token" },
            )
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(service) as? HttpClient)?.close()
        httpClientField.set(
            service,
            HttpClient(CIO) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 2_000
                    socketTimeoutMillis = 100
                    requestTimeoutMillis = 10_000
                }
            },
        )
        val baseUrlField = GraphApiService::class.java.getDeclaredField("baseUrl")
        baseUrlField.isAccessible = true
        baseUrlField.set(service, "http://127.0.0.1:${server.localPort}/v1.0")
        return service
    }

    private fun readRequest(input: java.io.InputStream): String {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < delimiter.size) {
            val next = input.read()
            check(next >= 0) { "client closed before request headers completed" }
            headerBytes.write(next)
            matched = if (next == delimiter[matched].toInt()) matched + 1 else if (next == delimiter[0].toInt()) 1 else 0
        }
        val headers = headerBytes.toString(Charsets.ISO_8859_1.name())
        val contentLength =
            headers.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull() ?: 0
        var remaining = contentLength
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            check(count > 0) { "client closed before upload body completed" }
            remaining -= count
        }
        return headers.lineSequence().first()
    }

    private fun respond(
        socket: java.net.Socket,
        status: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun driveItemJson(
        id: String,
        name: String,
        size: Long,
    ): String =
        """{"id":"$id","name":"$name","size":$size,"file":{"mimeType":"application/octet-stream"},"parentReference":{"path":"/drive/root:"}}"""
}
