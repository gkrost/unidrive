package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The attempt metrics of the RequestId plugin: what one HTTP request attempt is recorded as, measured at the
 * boundary where Ktor hands it to the engine and where its body is consumed.
 */
class RequestMetricsPluginTest {
    private class Collected : RequestMetricsSink {
        val attempts = CopyOnWriteArrayList<RequestAttempt>()

        override fun record(attempt: RequestAttempt) {
            attempts += attempt
        }

        /** An attempt is recorded when its body is consumed, which may be after the call returned. */
        fun awaitCount(n: Int): List<RequestAttempt> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (attempts.size < n && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(n, attempts.size, "attempts recorded: $attempts")
            return attempts.toList()
        }
    }

    private val classifier = EndpointClassifier(setOf("api.example.test"), setOf("v1", "items", "children", "content"))

    private fun client(
        collected: Collected?,
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): Pair<HttpClient, RequestMetricsTap> {
        val tap = RequestMetricsTap().also { it.sink = collected }
        val client =
            HttpClient(MockEngine(handler)) {
                install(RequestId) {
                    metrics = tap
                    provider = "test"
                    endpoints = classifier
                }
            }
        return client to tap
    }

    @Test
    fun `a large body is included in total time and bytes read`() =
        runBlocking {
            // A real loopback server: the mock engine hands a response over at times that depend on how its body
            // producer is scheduled, so it cannot say "the headers came first, the body followed slowly".
            val chunk = ByteArray(64 * 1024) { 1 }
            val chunks = 6
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/v1/items/abc/content") { exchange ->
                exchange.sendResponseHeaders(200, (chunk.size * chunks).toLong())
                exchange.responseBody.use { out ->
                    repeat(chunks) {
                        out.write(chunk)
                        out.flush()
                        Thread.sleep(60)
                    }
                }
            }
            server.start()
            try {
                val collected = Collected()
                val tap = RequestMetricsTap().also { it.sink = collected }
                val client =
                    HttpClient(CIO) {
                        install(RequestId) {
                            metrics = tap
                            provider = "test"
                            endpoints = EndpointClassifier(setOf("127.0.0.1", "localhost"), setOf("v1", "items", "children", "content"))
                        }
                    }
                client.use {
                    val bytes = it.get("http://127.0.0.1:${server.address.port}/v1/items/abc/content").bodyAsBytes()
                    assertEquals(chunk.size * chunks, bytes.size)
                }

            val bytes = client.get("https://api.example.test/v1/items/abc/content").bodyAsBytes()
            assertEquals(chunk.size * chunks, bytes.size)

            val attempt = collected.awaitCount(1).single()
            assertEquals("GET /v1/items/{id}/content", attempt.endpointClass)
            assertEquals(AttemptOutcome.OK, attempt.outcome)
            val headers = assertNotNull(attempt.headersMs)
            // Both values are rounded down to whole milliseconds. On a saturated CI worker, the mock response can
            // finish between the two samples within one clock tick even though the delayed body was consumed.
            assertTrue(headers <= attempt.totalMs, "headers ${headers}ms must not exceed total ${attempt.totalMs}ms")
            assertTrue(attempt.totalMs >= (chunks - 1) * 60L - 20, "total ${attempt.totalMs}ms includes the slow body")
            assertEquals((chunk.size * chunks).toLong(), attempt.bytesDown)
            assertEquals("test", attempt.provider)
        }

    @Test
    fun `a 429 and a connection reset are different classes and only the 429 is throttled`() =
        runBlocking {
            val collected = Collected()
            var calls = 0
            val (client, _) =
                client(collected) {
                    when (calls++) {
                        0 -> respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3"))
                        else -> throw SocketException("Connection reset")
                    }
                }

            assertEquals("slow down", client.get("https://api.example.test/v1/items/a/children").bodyAsText())
            assertFailsWith<SocketException> { client.get("https://api.example.test/v1/items/b/children") }

            val (throttle, reset) = collected.awaitCount(2).sortedBy { it.startedAtMs }
            assertEquals(AttemptOutcome.THROTTLED, throttle.outcome)
            assertTrue(throttle.throttled)
            assertNotNull(throttle.headersMs)
            assertEquals(AttemptOutcome.RESET, reset.outcome)
            assertTrue(!reset.throttled)
            assertNull(reset.headersMs, "no response arrived: unknown, not zero")
            assertNull(reset.bytesDown)
            assertNotEquals(throttle.outcome.wire, reset.outcome.wire)
        }

    @Test
    fun `status classes and transport failures are told apart`() =
        runBlocking {
            val collected = Collected()
            val failures =
                listOf<(() -> Throwable)?>(
                    null,
                    null,
                    null,
                    null,
                    { SSLHandshakeException("handshake_failure") },
                    { UnknownHostException("api.example.test") },
                    { io.ktor.client.plugins.HttpRequestTimeoutException("https://api.example.test/", 10) },
                )
            val statuses = listOf(HttpStatusCode.OK, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)
            var n = 0
            val (client, _) =
                client(collected) {
                    val i = n++
                    failures[i]?.let { throw it() }
                    respond("x", statuses[i])
                }
            repeat(failures.size) { i ->
                runCatching { client.get("https://api.example.test/v1/items/$i").bodyAsText() }
            }

            val outcomes = collected.awaitCount(failures.size).sortedBy { it.startedAtMs }.map { it.outcome }
            assertEquals(
                listOf(
                    AttemptOutcome.OK,
                    AttemptOutcome.UNAUTHORIZED,
                    AttemptOutcome.CLIENT_ERROR,
                    AttemptOutcome.SERVER_ERROR,
                    AttemptOutcome.TLS,
                    AttemptOutcome.DNS,
                    AttemptOutcome.TIMEOUT,
                ),
                outcomes,
            )
        }

    @Test
    fun `a Retry-After on a 503 sets the throttle flag`() =
        runBlocking {
            val collected = Collected()
            val (client, _) = client(collected) { respond("", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "1")) }
            client.get("https://api.example.test/v1/items/a")
            val attempt = collected.awaitCount(1).single()
            assertEquals(AttemptOutcome.SERVER_ERROR, attempt.outcome)
            assertTrue(attempt.throttled)
        }

    @Test
    fun `the retry number counts earlier failures of the same request and resets on success`() =
        runBlocking {
            val collected = Collected()
            var calls = 0
            val (client, _) =
                client(collected) {
                    if (calls++ < 2) respond("", HttpStatusCode.ServiceUnavailable) else respond("ok", HttpStatusCode.OK)
                }
            repeat(3) { client.get("https://api.example.test/v1/items/a/children").bodyAsText() }
            client.get("https://api.example.test/v1/items/a/children").bodyAsText()
            client.get("https://api.example.test/v1/items/z/children").bodyAsText()

            val attempts = collected.awaitCount(5).sortedBy { it.startedAtMs }
            assertEquals(listOf(0, 1, 2, 0, 0), attempts.map { it.retryNo })
        }

    @Test
    fun `an upload reports its declared length`() =
        runBlocking {
            val collected = Collected()
            val (client, _) = client(collected) { respond("", HttpStatusCode.OK) }
            client.put("https://api.example.test/v1/items/a/content") { setBody(ByteArray(1234)) }
            assertEquals(1234L, collected.awaitCount(1).single().bytesUp)
        }

    @Test
    fun `with no sink nothing is recorded and a sink attached later sees the next request`() =
        runBlocking {
            val (client, tap) = client(null) { respond("ok", HttpStatusCode.OK) }
            client.get("https://api.example.test/v1/items/a").bodyAsText()
            val collected = Collected()
            tap.sink = collected
            client.get("https://api.example.test/v1/items/b").bodyAsText()
            assertEquals(1, collected.awaitCount(1).size)
        }

    @Test
    fun `a sink that throws never fails the request`() =
        runBlocking {
            val tap = RequestMetricsTap().also { it.sink = RequestMetricsSink { error("broken sink") } }
            val client =
                HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) {
                    install(RequestId) { metrics = tap }
                }
            assertEquals("ok", client.get("https://api.example.test/v1/items/a").bodyAsText())
        }

    @Test
    fun `a client that never names a tap behaves as before`() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(ByteReadChannel("ok"), HttpStatusCode.OK) }) { install(RequestId) }
            assertEquals("ok", client.get("https://api.example.test/v1/items/a").bodyAsText())
        }

    /**
     * What the plugin's own attempt bookkeeping (clock reads, the retry key, the completion hook, one allocation) adds to a
     * request through a client, with a sink that does nothing: interleaved rounds, the fastest of each side. On a loaded
     * machine the noise can exceed the effect, so the bound is loose (5 % of a 10 ms request); the figure is printed.
     */
    @Test
    fun `the plugin's bookkeeping adds microseconds to a request`() {
        val off = RequestMetricsTap()
        val on = RequestMetricsTap().also { it.sink = RequestMetricsSink { } }
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        fun build(tap: RequestMetricsTap) = HttpClient(engine) { install(RequestId) { metrics = tap; endpoints = classifier } }
        val clientOff = build(off)
        val clientOn = build(on)

        fun perRequestNanos(client: HttpClient): Double =
            runBlocking {
                val n = 3_000
                val start = System.nanoTime()
                repeat(n) { client.get("https://api.example.test/v1/items/abc/children").bodyAsText() }
                (System.nanoTime() - start).toDouble() / n
            }
        perRequestNanos(clientOff)
        perRequestNanos(clientOn)
        var bestOff = Double.MAX_VALUE
        var bestOn = Double.MAX_VALUE
        repeat(9) {
            bestOff = minOf(bestOff, perRequestNanos(clientOff))
            bestOn = minOf(bestOn, perRequestNanos(clientOn))
        }
        val addedMicros = (bestOn - bestOff) / 1_000.0
        println("PLUGIN per_request_without_us=%.1f per_request_with_us=%.1f added_us=%.1f".format(bestOff / 1_000.0, bestOn / 1_000.0, addedMicros))
        assertTrue(addedMicros < 500.0, "the plugin adds $addedMicros us per request")
    }
}
