package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `x-internxt-desktop-header` is an optional token in Internxt's own SDK: it is
 * sent only when the host application configures one. unidrive has no such token,
 * so it must not invent one; the header goes out only when explicitly configured.
 */
class InternxtHeadersTest {
    private suspend fun sentHeaders(config: InternxtConfig): io.ktor.http.Headers {
        var seen: io.ktor.http.Headers? = null
        val client =
            HttpClient(
                MockEngine { request ->
                    seen = request.headers
                    respond("{}", HttpStatusCode.OK)
                },
            )
        client.get("https://gateway.invalid/drive/test") { applyInternxtHeaders(config) }
        client.close()
        return seen!!
    }

    @Test
    fun `desktop header is not sent by default`() =
        runTest {
            val headers = sentHeaders(InternxtConfig())
            assertNull(headers["x-internxt-desktop-header"])
        }

    @Test
    fun `desktop header is not sent when configured blank`() =
        runTest {
            val headers = sentHeaders(InternxtConfig(desktopHeader = ""))
            assertNull(headers["x-internxt-desktop-header"])
        }

    @Test
    fun `desktop header is sent when explicitly configured`() =
        runTest {
            val headers = sentHeaders(InternxtConfig(desktopHeader = "test-token"))
            assertEquals("test-token", headers["x-internxt-desktop-header"])
        }

    @Test
    fun `client name and version are always sent`() =
        runTest {
            val headers = sentHeaders(InternxtConfig())
            assertEquals(InternxtConfig.CLIENT_NAME, headers["internxt-client"])
            assertEquals(InternxtConfig.CLIENT_VERSION, headers["internxt-version"])
            assertEquals("application/json", headers[HttpHeaders.Accept])
        }
}
