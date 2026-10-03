package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ClosedReadChannelException
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
}
