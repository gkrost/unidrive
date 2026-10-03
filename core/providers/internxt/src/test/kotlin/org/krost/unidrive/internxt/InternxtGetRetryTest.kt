package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ClosedReadChannelException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.internxt.model.InternxtFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A GET whose connection is closed before the server answered must be retried like a 5xx (the canonical
 * retry matrix, see HttpRetryBudget) and, once the ladder is spent, reported as 503: the status the
 * `/files` -> folder walk fallback and the folder skip act on.
 *
 * The Ktor CIO engine does not throw the EOFException for a connection that closes before any response byte.
 * It throws a ClosedReadChannelException, an IOException that wraps the EOFException (checked against a real
 * socket, Ktor 3.4 to 3.6). Matching the EOFException alone made every such call give up at once with status 0,
 * so a whole-drive enumeration against a gateway that cuts slow `/drive/files` calls never reached the fallback.
 */
class InternxtGetRetryTest {
    private val prematureClose = "Failed to parse HTTP response: the server prematurely closed the connection"

    private fun ktorPrematureClose() = ClosedReadChannelException(java.io.EOFException(prematureClose))

    private fun serviceOn(engine: MockEngine) =
        InternxtApiService(
            InternxtConfig(),
            credentialsProvider = { _ ->
                InternxtCredentials(
                    jwt = "test-jwt",
                    mnemonic = "test-mnemonic",
                    rootFolderId = "test-root",
                    email = "test@example.invalid",
                )
            },
            driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
            httpClient = HttpClient(engine),
        )

    @Test
    fun `a GET whose connection closes before the response is retried and the next answer is used`() =
        runTest {
            val calls = AtomicInteger(0)
            val service =
                serviceOn(
                    MockEngine {
                        if (calls.incrementAndGet() == 1) throw ktorPrematureClose()
                        respond(content = "[]", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
                    },
                )
            assertEquals(emptyList<InternxtFile>(), service.listFiles())
            assertEquals(2, calls.get(), "the first attempt was lost, the second answered")
            service.close()
        }

    @Test
    fun `a connection that keeps closing ends as 503 after the whole ladder`() =
        runTest {
            val calls = AtomicInteger(0)
            val service =
                serviceOn(
                    MockEngine {
                        calls.incrementAndGet()
                        throw ktorPrematureClose()
                    },
                )
            val e = assertFailsWith<InternxtApiException> { service.listFiles() }
            assertEquals(503, e.statusCode, "503 is what the /files fallback and the folder skip react to")
            assertEquals(3, calls.get(), "three attempts, not one")
            assertTrue(e.message.orEmpty().startsWith("Server closed connection for GET"), e.message)
            service.close()
        }

    @Test
    fun `a bare EOFException is handled the same way`() =
        runTest {
            val calls = AtomicInteger(0)
            val service =
                serviceOn(
                    MockEngine {
                        calls.incrementAndGet()
                        throw java.io.EOFException("Unexpected end of stream after reading 11 bytes")
                    },
                )
            val e = assertFailsWith<InternxtApiException> { service.getFolderContents("some-folder") }
            assertEquals(503, e.statusCode)
            assertEquals(3, calls.get())
            service.close()
        }

    @Test
    fun `another network error is retried too and keeps status 0 when it never recovers`() =
        runTest {
            val calls = AtomicInteger(0)
            val service =
                serviceOn(
                    MockEngine {
                        calls.incrementAndGet()
                        throw java.net.ConnectException("Connection refused")
                    },
                )
            val e = assertFailsWith<InternxtApiException> { service.listFiles() }
            assertEquals(0, e.statusCode, "not a server that is unavailable: no fallback walk for a connection that cannot be made")
            assertEquals(3, calls.get(), "network errors without a status are retried (canonical matrix)")
            assertTrue(e.message.orEmpty().startsWith("Connection error for GET"), e.message)
            service.close()
        }


    // ---- the pauses between the attempts ------------------------------------------------------------------------

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    // What a 503 says about when to come back: a Retry-After header, a retry_after hint in the JSON body, both or neither.
    private class Hint(
        val body: String = "{}",
        val retryAfter: String? = null,
    )

    private fun MockRequestHandleScope.overloaded(hint: Hint = Hint()) =
        respond(
            content = hint.body,
            status = HttpStatusCode.ServiceUnavailable,
            headers =
                if (hint.retryAfter == null) {
                    jsonHeaders
                } else {
                    headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf(hint.retryAfter))
                },
        )

    @Test
    fun `three attempts are paused 2 s and 4 s apart, there is no step after the last one`() =
        runTest {
            val calls = AtomicInteger(0)
            val service =
                serviceOn(
                    MockEngine {
                        calls.incrementAndGet()
                        overloaded()
                    },
                )

            val e = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertEquals(503, e.statusCode)
            assertEquals(3, calls.get())
            assertEquals(2_000L + 4_000L, currentTime)
            service.close()
        }

    @Test
    fun `the server's hint replaces the backoff, the header over the body, a long one capped at a minute`() =
        runTest {
            val cases =
                listOf(
                    Hint(retryAfter = "7") to 7_000L,
                    Hint(body = """{"retry_after":5}""") to 5_000L,
                    Hint(body = """{"retry_after":5}""", retryAfter = "3") to 3_000L,
                    Hint(retryAfter = "3600") to 60_000L,
                )
            for ((hint, expectedMs) in cases) {
                val calls = AtomicInteger(0)
                val service =
                    serviceOn(
                        MockEngine {
                            if (calls.incrementAndGet() == 1) overloaded(hint) else respond("[]", HttpStatusCode.OK, jsonHeaders)
                        },
                    )
                val before = currentTime

                assertEquals(emptyList<InternxtFile>(), service.listFiles())

                assertEquals(expectedMs, currentTime - before, "hint: ${hint.retryAfter} / ${hint.body}")
                service.close()
            }
        }
}
