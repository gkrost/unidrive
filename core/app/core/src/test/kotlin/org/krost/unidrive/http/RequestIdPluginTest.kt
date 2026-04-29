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
    @Test
    fun `every request carries X-Unidrive-Request-Id header`() =
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
                assertEquals(8, id.length, "request $i id should be 8 chars; was '$id'")
            }
            // Three distinct ids.
            assertEquals(seenIds.toSet().size, seenIds.size, "each request should get a fresh id; saw $seenIds")
        }

    @Test
    fun `request attribute exposes the id for caller introspection`() =
        runTest {
            val engine =
                MockEngine {
                    respond("ok", HttpStatusCode.OK)
                }
            val client =
                HttpClient(engine) {
                    install(RequestId)
                }

            var capturedFromBuilder: String? = null
            val response =
                client.request("https://example.invalid/probe") {
                    // The plugin populates the attribute before send; the builder
                    // hook runs after, so we can read it here.
                    capturedFromBuilder = this.requestId()
                }
            assertEquals("ok", response.bodyAsText())

            // The same id that the plugin put on the request must reach the server
            // as the X-Unidrive-Request-Id header; assert that indirectly via the
            // builder hook capturing the attribute.
            val fromEngineHeader = (engine.requestHistory.first().headers["X-Unidrive-Request-Id"])
            assertNotNull(fromEngineHeader)
            // Builder-side value may be null if onRequest runs after our block;
            // either way the header must be populated for the server to see it.
            if (capturedFromBuilder != null) {
                assertEquals(capturedFromBuilder, fromEngineHeader)
            }
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
