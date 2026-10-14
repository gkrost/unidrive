package org.krost.unidrive.http

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UD-255 regression: every outbound HTTP request must carry an
 * `X-Unidrive-Request-Id` header unique per call.
 */
class RequestIdPluginTest {
    // UD-811 audit: invariant is "every request carries a non-blank,
    // unique `X-Unidrive-Request-Id`." The previous body additionally
    // asserted `id.length == 8`, which pinned the current id format —
    // if the format ever changes (e.g. to 12 chars or a full UUID),
    // the test breaks without the contract being violated. Length
    // dropped; non-blank + unique remain.
    @Test
    fun `every request carries a non-blank unique X-Unidrive-Request-Id header`() =
        runTest {
            val seenIds = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    seenIds += request.headers["X-Unidrive-Request-Id"].orEmpty()
                    respond(
                        content = "ok",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/plain"),
                    )
                }
            val client =
                HttpClient(engine) {
                    install(RequestId)
                }

            client.get("https://example.invalid/a")
            client.get("https://example.invalid/b")
            client.get("https://example.invalid/c")

            assertEquals(3, seenIds.size)
            seenIds.forEachIndexed { i, id ->
                assertTrue(id.isNotBlank(), "request $i should carry X-Unidrive-Request-Id; saw '$id'")
            }
            // Three distinct ids.
            assertEquals(seenIds.toSet().size, seenIds.size, "each request should get a fresh id; saw $seenIds")
        }

    // UD-811 audit: renamed from `request attribute exposes the id for
    // caller introspection`. The previous body tried to capture
    // `this.requestId()` inside the `client.request { ... }` lambda,
    // but that lambda runs during HttpRequestBuilder construction —
    // BEFORE the `RequestId` plugin's `onRequest` populates the
    // attribute. So `capturedFromBuilder` was always null, the
    // `if (capturedFromBuilder != null) { assertEquals(...) }` guard
    // never ran, and the test silently degenerated to "engine saw a
    // non-blank header." Renamed to match what the test actually
    // verifies: that the plugin populates the header before the
    // request reaches the engine. The "exposes via attribute for
    // caller introspection" use-case is not reachable from outside
    // the plugin's own `onResponse` hook and so cannot be black-box
    // tested; covered indirectly by the log line that pulls the
    // attribute in `onResponse`.
    @Test
    fun `RequestId plugin populates X-Unidrive-Request-Id before the request reaches the engine`() =
        runTest {
            val engine =
                MockEngine {
                    respond("ok", HttpStatusCode.OK)
                }
            val client =
                HttpClient(engine) {
                    install(RequestId)
                }

            val response = client.request("https://example.invalid/probe")
            assertEquals("ok", response.bodyAsText())

            val fromEngineHeader = engine.requestHistory.first().headers["X-Unidrive-Request-Id"]
            assertNotNull(
                fromEngineHeader,
                "engine must see the X-Unidrive-Request-Id header populated by the plugin",
            )
            assertTrue(
                fromEngineHeader.isNotBlank(),
                "X-Unidrive-Request-Id must be non-blank; got '$fromEngineHeader'",
            )
        }

    @Test
    fun `UD-773 formatSizeSegment suppresses both-zero and both-null`() {
        assertEquals("", formatSizeSegment(null, null))
        assertEquals("", formatSizeSegment(0L, 0L))
    }

    @Test
    fun `UD-773 formatSizeSegment emits up and dn for non-zero values`() {
        assertEquals(" up=12345 dn=30", formatSizeSegment(12345L, 30L))
        assertEquals(" up=0 dn=4194304", formatSizeSegment(0L, 4194304L))
        assertEquals(" up=999 dn=0", formatSizeSegment(999L, 0L))
    }

    @Test
    fun `UD-773 formatSizeSegment uses question-mark for unknown side`() {
        // Streaming response with known upload size.
        assertEquals(" up=12345 dn=?", formatSizeSegment(12345L, null))
        // Streaming upload with known response size.
        assertEquals(" up=? dn=30", formatSizeSegment(null, 30L))
    }

    @Test
    fun `redact strips query string`() {
        assertEquals("https://a.example/path?<redacted>", redact("https://a.example/path?token=secret"))
        assertEquals("https://a.example/path", redact("https://a.example/path"))
        // The original (secret) URL must not survive redaction verbatim.
        assertNotEquals(
            "https://a.example/path?token=secret",
            redact("https://a.example/path?token=secret"),
        )
    }

    // #644: the response line carries the profile and the server's own request id in the message itself,
    // because the MDC does not reach every worker thread. A client that names neither logs the old format.
    private suspend fun capturedLines(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("org.krost.unidrive.http.RequestId") as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
            return appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    private fun responding(headers: Pair<String, String>? = null) =
        MockEngine { request ->
            respond(
                content = "ok",
                status = HttpStatusCode.OK,
                headers =
                    if (headers == null) {
                        headersOf("Content-Type" to listOf("text/plain"))
                    } else {
                        headersOf(headers.first to listOf(headers.second), "Content-Type" to listOf("text/plain"))
                    },
            )
        }

    @Test
    fun `two clients with different profiles log their own name from a thread without MDC`() =
        runTest {
            assertNull(MDC.get("profile"), "precondition: the test thread carries no profile MDC")
            fun client(name: String) =
                HttpClient(responding()) {
                    install(RequestId) { profile = name }
                }
            val alpha = client("alpha")
            val beta = client("beta")
            val lines =
                capturedLines {
                    alpha.get("https://example.invalid/a")
                    beta.get("https://example.invalid/b")
                }
            assertTrue(lines.any { it.startsWith("← req=") && it.contains(" [alpha] 200 ") }, "alpha's own line: $lines")
            assertTrue(lines.any { it.startsWith("← req=") && it.contains(" [beta] 200 ") }, "beta's own line: $lines")
            assertTrue(lines.none { it.contains("[alpha]") && it.contains("[beta]") }, "no line mixes the names: $lines")
            alpha.close()
            beta.close()
        }

    @Test
    fun `a client without a profile logs the old format byte for byte`() =
        runTest {
            val client = HttpClient(responding()) { install(RequestId) }
            val lines =
                capturedLines {
                    client.get("https://example.invalid/a")
                }
            val close = lines.last { it.startsWith("←") }
            assertTrue(Regex("""^← req=[^ ]+ 200 \(\d+ms\)( up=\S+ dn=\S+)?$""").matches(close), "the old close line, unchanged: $close")
            assertTrue("![" !in close && " srv=" !in close, "no new segments: $close")
            client.close()
        }

    @Test
    fun `a response carrying the server request id logs srv, one without logs none`() =
        runTest {
            val withHeader =
                HttpClient(responding("x-request-id" to "srv-abc-123")) {
                    install(RequestId) {
                        profile = "alpha"
                        serverRequestIdHeaders = listOf("x-request-id")
                    }
                }
            val withoutHeader =
                HttpClient(responding()) {
                    install(RequestId) {
                        profile = "alpha"
                        serverRequestIdHeaders = listOf("x-request-id")
                    }
                }
            val withLines =
                capturedLines {
                    withHeader.get("https://example.invalid/a")
                }.last { it.startsWith("←") }
            val withoutLines =
                capturedLines {
                    withoutHeader.get("https://example.invalid/b")
                }.last { it.startsWith("←") }
            assertTrue(" srv=srv-abc-123 " in " $withLines", "the server id is on the line: $withLines")
            assertTrue(Regex("""^← req=[^ ]+ \[alpha\] srv=srv-abc-123 200 \(\d+ms\)( up=\S+ dn=\S+)?$""").matches(withLines), "full shape: $withLines")
            assertTrue(" srv=" !in withoutLines, "no header, no segment: $withoutLines")
            withHeader.close()
            withoutHeader.close()
        }

    @Test
    fun `no query string reaches the new log segments`() =
        runTest {
            val client =
                HttpClient(responding()) {
                    install(RequestId) {
                        profile = "alpha"
                        serverRequestIdHeaders = listOf("x-request-id")
                    }
                }
            val lines =
                capturedLines {
                    client.get("https://example.invalid/a?token=SECRET-VALUE")
                }
            assertTrue(lines.none { "SECRET-VALUE" in it }, "the redaction rule covers every line: $lines")
            client.close()
        }
}
