package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
}
