package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins how `AuthService.fetchRefreshedJwt` classifies a non-2xx answer from
 * `/users/refresh`. Live incident (2026-10-01 13:23): one refresh call got a
 * Cloudflare 502 (`retryable:true, retry_after:60`) and surfaced as an
 * [AuthenticationException] carrying the whole HTML/JSON body — the exact
 * "transient blip must not latch the session as expired" case that
 * [TransientNetworkException] was introduced for. A watch daemon treats an
 * AuthenticationException as "stop and re-authenticate", so one gateway hiccup
 * would end the session although the JWT was untouched and still usable.
 *
 * Invariants, one named test each:
 *  1. 408 / 429 / 5xx -> [TransientNetworkException], credentials untouched and
 *     the next attempt can succeed.
 *  2. 401 / 403 -> [AuthenticationException] (the token really was rejected).
 *  3. The message carries the status and at most 200 body characters.
 *  4. 200 is unchanged.
 */
class InternxtAuthRefreshClassificationTest {
    private val staleJwt = "stale-jwt"

    private fun seedCredentials(dir: Path) {
        val creds =
            InternxtCredentials(
                jwt = staleJwt,
                mnemonic = "test-mnemonic",
                rootFolderId = "test-root",
                email = "test@example.invalid",
            )
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("credentials.json"), Json.encodeToString(InternxtCredentials.serializer(), creds))
    }

    private suspend fun newAuth(
        dir: Path,
        engine: MockEngine,
    ): AuthService {
        seedCredentials(dir)
        return AuthService(InternxtConfig(tokenPath = dir), httpClient = HttpClient(engine)).also { it.initialize() }
    }

    private fun engineAnswering(
        status: HttpStatusCode,
        body: String = """{"error":"x"}""",
        headers: io.ktor.http.Headers = headersOf(HttpHeaders.ContentType, "application/json"),
        counter: AtomicInteger = AtomicInteger(),
    ): MockEngine =
        MockEngine {
            counter.incrementAndGet()
            respond(content = body, status = status, headers = headers)
        }

    private fun withTempDir(block: suspend (Path) -> Unit) =
        runTest {
            val dir = Files.createTempDirectory("internxt-refresh-class-")
            try {
                block(dir)
            } finally {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }

    @Test
    fun `transient statuses 408 429 502 503 504 surface as TransientNetworkException`() =
        withTempDir { dir ->
            for (code in listOf(408, 429, 500, 502, 503, 504)) {
                val auth = newAuth(dir, engineAnswering(HttpStatusCode.fromValue(code)))
                try {
                    val ex = assertFailsWith<TransientNetworkException>("HTTP $code must be transient") {
                        auth.getValidCredentials(forceRefresh = true)
                    }
                    assertTrue(ex.message!!.contains("HTTP $code"), "message must carry the status; got: ${ex.message}")
                } finally {
                    auth.close()
                }
            }
        }

    @Test
    fun `transient refresh failure leaves credentials usable for the next attempt`() =
        withTempDir { dir ->
            val calls = AtomicInteger()
            val engine =
                MockEngine {
                    if (calls.incrementAndGet() == 1) {
                        respond(
                            content = """{"error":"upstream","retryable":true,"retry_after":60}""",
                            status = HttpStatusCode.BadGateway,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"newToken":"fresh-jwt"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            val auth = newAuth(dir, engine)
            try {
                assertFailsWith<TransientNetworkException> { auth.refreshToken() }
                // Not latched: still authenticated, JWT unchanged, nothing persisted.
                assertTrue(auth.isAuthenticated, "a transient refresh failure must not drop the session")
                assertEquals(staleJwt, auth.currentCredentials?.jwt)
                // And the very next attempt goes through and rotates the token.
                assertEquals("fresh-jwt", auth.refreshToken().jwt)
                assertEquals(2, calls.get())
            } finally {
                auth.close()
            }
        }

    @Test
    fun `401 and 403 stay AuthenticationException`() =
        withTempDir { dir ->
            for (code in listOf(401, 403)) {
                val auth = newAuth(dir, engineAnswering(HttpStatusCode.fromValue(code)))
                try {
                    val ex = assertFailsWith<AuthenticationException>("HTTP $code must stay an auth failure") {
                        auth.getValidCredentials(forceRefresh = true)
                    }
                    assertTrue(ex.message!!.contains("HTTP $code"), "message must carry the status; got: ${ex.message}")
                } finally {
                    auth.close()
                }
            }
        }

    @Test
    fun `message carries status and at most 200 body characters and the retry hint`() =
        withTempDir { dir ->
            val huge = "<html>" + "Bad gateway ".repeat(500) + "</html>"
            val auth =
                newAuth(
                    dir,
                    engineAnswering(
                        HttpStatusCode.BadGateway,
                        body = huge,
                        headers = headersOf(HttpHeaders.RetryAfter, "60"),
                    ),
                )
            try {
                val ex = assertFailsWith<TransientNetworkException> { auth.refreshToken() }
                val msg = ex.message!!
                assertTrue(msg.contains("HTTP 502"), "status missing: $msg")
                assertTrue(msg.contains("60"), "Retry-After hint missing: $msg")
                // Whole-message ceiling: status/hint prefix plus at most 200 body chars.
                assertTrue(msg.length < 400, "message must not carry the whole body; length=${msg.length}")
                assertFalse(msg.contains("Bad gateway ".repeat(30)), "body must be cut at 200 chars: $msg")
            } finally {
                auth.close()
            }
        }

    @Test
    fun `retry_after in the json body is surfaced as information only`() =
        withTempDir { dir ->
            val auth =
                newAuth(
                    dir,
                    engineAnswering(
                        HttpStatusCode.ServiceUnavailable,
                        body = """{"error":"Bad gateway","retryable":true,"retry_after":60}""",
                    ),
                )
            try {
                val ex = assertFailsWith<TransientNetworkException> { auth.refreshToken() }
                assertTrue(ex.message!!.contains("retry_after"), "hint missing: ${ex.message}")
                assertTrue(ex.message!!.contains("60"), "hint value missing: ${ex.message}")
            } finally {
                auth.close()
            }
        }

    @Test
    fun `200 is unchanged and rotates the token`() =
        withTempDir { dir ->
            val auth = newAuth(dir, engineAnswering(HttpStatusCode.OK, body = """{"newToken":"fresh-jwt"}"""))
            try {
                assertEquals("fresh-jwt", auth.getValidCredentials(forceRefresh = true).jwt)
                assertEquals("fresh-jwt", auth.currentCredentials?.jwt)
            } finally {
                auth.close()
            }
        }
}
