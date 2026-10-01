package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.onedrive.model.FileSystemInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #293: every mutating Graph call (simple PUT, DELETE, folder POST, move PATCH, fileSystemInfo PATCH,
 * upload-session POST, chunk PUT) bypassed the throttle budget and the 429/503/401 handling that
 * `authenticatedRequest` gives the reads: a throttled write failed on the spot, and writes kept firing
 * through a throttle storm the budget exists to brake.
 *
 * Each test states one invariant and runs it for every mutating call. The budget's clock is the test's
 * virtual time, so a wait the code takes (Retry-After, an open circuit) shows up as `currentTime`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphWriteThrottleTest {
    private lateinit var tokenDir: Path
    private lateinit var localFile: Path

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-graph-write-throttle")
        localFile = tokenDir.resolve("local.bin")
        Files.write(localFile, "bytes".toByteArray())
    }

    @AfterTest
    fun tearDown() {
        tokenDir.toFile().deleteRecursively()
    }

    /** One mutating call, and the HTTP method of the request it sends that the test makes fail. */
    private class Write(
        val name: String,
        val failingMethod: String,
        val errorPrefix: String,
        val call: suspend GraphApiService.(Path) -> Unit,
    )

    private val writes =
        listOf(
            Write("uploadSimple", "PUT", "Upload failed") { uploadSimple("/a.txt", "x".toByteArray()) },
            Write("deleteItem", "DELETE", "Delete failed") { deleteItem("item-1") },
            Write("createFolder", "POST", "Create folder failed") { createFolder("sub", "/") },
            // Same parent folder: no parent lookup (a GET) is needed, so the PATCH is the only request.
            Write("moveItem", "PATCH", "Move failed") { moveItem("item-1", "/b.txt", oldPath = "/a.txt") },
            // The content PUT succeeds, the follow-up fileSystemInfo PATCH is the one that fails.
            Write("patchFileSystemInfo", "PATCH", "fileSystemInfo PATCH failed") {
                uploadSimple("/a.txt", "x".toByteArray(), fileSystemInfo = FileSystemInfo(lastModifiedDateTime = "2026-03-28T12:00:00Z"))
            },
            Write("createUploadSession", "POST", "Create upload session failed") { local -> uploadLargeFile(local, "/big.bin") },
        )

    private fun itemJson(id: String = "item-1") = """{"id":"$id","name":"x","size":5,"file":{"mimeType":"application/octet-stream"}}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun MockRequestHandleScope.success(request: HttpRequestData): HttpResponseData =
        when {
            request.method.value == "DELETE" -> respond("", HttpStatusCode.NoContent)
            request.url.encodedPath.endsWith("createUploadSession") ->
                respond(
                    """{"uploadUrl":"https://upload.example/session-1","expirationDateTime":"2099-01-01T00:00:00Z"}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
            request.url.host == "upload.example" -> respond(itemJson(), HttpStatusCode.Created, jsonHeaders())
            else -> respond(itemJson(), HttpStatusCode.OK, jsonHeaders())
        }

    private class Failure(
        val status: HttpStatusCode,
        val headers: Headers = headersOf(),
        val body: String = """{"error":{"code":"activityLimitReached"}}""",
    )

    /**
     * Answers [failures] to the requests of [write]'s failing method, in order, then succeeds. [seen]
     * collects every request, including the ones that succeed.
     */
    private fun engine(
        write: Write,
        failures: List<Failure>,
        seen: MutableList<HttpRequestData>,
    ): MockEngine {
        val queue = ArrayDeque(failures)
        return MockEngine { request ->
            seen += request
            val targeted = request.method.value == write.failingMethod && request.url.host != "upload.example"
            if (targeted && queue.isNotEmpty()) {
                val failure = queue.removeFirst()
                respond(failure.body, failure.status, failure.headers)
            } else {
                success(request)
            }
        }
    }

    private fun serviceOver(
        engine: MockEngine,
        budget: HttpRetryBudget,
        tokenProvider: suspend (Boolean) -> String = { "token" },
    ): GraphApiService {
        val service = GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), throttleBudget = budget, tokenProvider = tokenProvider)
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
        return service
    }

    private fun TestScope.budget() = HttpRetryBudget(maxConcurrency = 8, clock = { currentTime })

    private fun List<HttpRequestData>.countFor(write: Write) = count { it.method.value == write.failingMethod && it.url.host != "upload.example" }

    /** Runs [check] for every write and reports all the ones that fail, not just the first. */
    private suspend fun eachWrite(
        which: List<Write> = writes,
        check: suspend (Write) -> Unit,
    ) {
        val failures =
            which.mapNotNull { write ->
                try {
                    check(write)
                    null
                } catch (e: Throwable) {
                    "${write.name}: ${e::class.simpleName}: ${e.message}"
                }
            }
        assertTrue(failures.isEmpty(), "failing writes: " + failures.joinToString("; "))
    }

    @Test
    fun `a 429 with Retry-After is retried after the hinted wait`() =
        runTest {
            eachWrite { write ->
                val seen = mutableListOf<HttpRequestData>()
                val budget = budget()
                val service = serviceOver(engine(write, listOf(Failure(HttpStatusCode.TooManyRequests, headersOf("Retry-After", "7"))), seen), budget)
                val start = currentTime

                write.call(service, localFile)

                assertEquals(2, seen.countFor(write), "${write.name}: the throttled request must be sent again")
                assertTrue(currentTime - start >= 7_000, "${write.name}: must wait the hinted 7s, waited ${currentTime - start}ms")
                assertEquals(200L, budget.currentSpacingMs(), "${write.name}: the throttle must be recorded on the shared budget")
                service.close()
            }
        }

    @Test
    fun `a 503 without Retry-After backs off and is retried`() =
        runTest {
            eachWrite { write ->
                val seen = mutableListOf<HttpRequestData>()
                val service = serviceOver(engine(write, listOf(Failure(HttpStatusCode.ServiceUnavailable)), seen), budget())
                val start = currentTime

                write.call(service, localFile)

                assertEquals(2, seen.countFor(write), "${write.name}: the 503 must be retried")
                assertTrue(currentTime - start >= 2_000, "${write.name}: must back off at least 2s, waited ${currentTime - start}ms")
                service.close()
            }
        }

    @Test
    fun `throttled writes count against the throttle budget like reads`() =
        runTest {
            eachWrite { write ->
                val seen = mutableListOf<HttpRequestData>()
                val budget = budget()
                val failures = List(4) { Failure(HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1")) }
                val service = serviceOver(engine(write, failures, seen), budget)

                write.call(service, localFile)

                assertEquals(5, seen.countFor(write), "${write.name}: four throttles, then the success")
                assertEquals(4, budget.currentConcurrency(), "${write.name}: four throttles in the window are a storm and halve the concurrency")
                service.close()
            }
        }

    @Test
    fun `a write waits for an open throttle circuit before it is sent`() =
        runTest {
            eachWrite { write ->
                val seen = mutableListOf<HttpRequestData>()
                val budget = budget()
                repeat(4) { budget.recordThrottle(10_000) } // a storm: the circuit stays open for 12s
                val start = currentTime
                val service = serviceOver(engine(write, emptyList(), seen), budget)

                write.call(service, localFile)

                assertTrue(currentTime - start >= 12_000, "${write.name}: sent into an open circuit after ${currentTime - start}ms")
                service.close()
            }
        }

    @Test
    fun `a 401 on a write refreshes the token once and retries`() =
        runTest {
            eachWrite { write ->
                val seen = mutableListOf<HttpRequestData>()
                val tokenCalls = mutableListOf<Boolean>()
                val service =
                    serviceOver(engine(write, listOf(Failure(HttpStatusCode.Unauthorized)), seen), budget()) { refreshed ->
                        tokenCalls += refreshed
                        if (refreshed) "fresh" else "stale"
                    }

                write.call(service, localFile)

                val targeted = seen.filter { it.method.value == write.failingMethod && it.url.host != "upload.example" }
                assertEquals(
                    listOf<String?>("Bearer stale", "Bearer fresh"),
                    targeted.map { it.headers[HttpHeaders.Authorization] },
                    "${write.name}: the retry must carry the refreshed token",
                )
                assertEquals(true, tokenCalls.contains(true), "${write.name}: a refreshed token must have been requested")
                service.close()
            }
        }

    @Test
    fun `a 401 after the refresh is an AuthenticationException without a third attempt`() =
        runTest {
            eachWrite { write ->
                val seen = mutableListOf<HttpRequestData>()
                val failures = List(3) { Failure(HttpStatusCode.Unauthorized) }
                val service = serviceOver(engine(write, failures, seen), budget())

                assertFailsWith<AuthenticationException>("${write.name}: 401 after the refresh") { write.call(service, localFile) }

                assertEquals(2, seen.countFor(write), "${write.name}: exactly one retry after the 401")
                service.close()
            }
        }

    @Test
    fun `an exhausted throttle surfaces the call's own error and status`() =
        runTest {
            eachWrite { write ->
                val seen = mutableListOf<HttpRequestData>()
                val failures = List(20) { Failure(HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1")) }
                val service = serviceOver(engine(write, failures, seen), budget())

                val e = assertFailsWith<GraphApiException>("${write.name}: always throttled") { write.call(service, localFile) }

                assertEquals(429, e.statusCode, "${write.name}: status of the last response")
                assertTrue(e.message!!.startsWith(write.errorPrefix), "${write.name}: message was '${e.message}'")
                assertEquals(6, seen.countFor(write), "${write.name}: the first attempt plus five retries, then give up")
                service.close()
            }
        }

    @Test
    fun `a conflict or precondition failure on a write is not retried`() =
        runTest {
            eachWrite { write ->
                for (status in listOf(HttpStatusCode.Conflict, HttpStatusCode.PreconditionFailed)) {
                    val seen = mutableListOf<HttpRequestData>()
                    val service = serviceOver(engine(write, listOf(Failure(status)), seen), budget())

                    val e = assertFailsWith<GraphApiException>("${write.name}: $status") { write.call(service, localFile) }

                    assertEquals(status.value, e.statusCode, "${write.name}: the status must reach the caller (nameAlreadyExists / If-Match handling)")
                    assertEquals(1, seen.countFor(write), "${write.name}: $status is not a throttle and must not be retried")
                    service.close()
                }
            }
        }

    @Test
    fun `the create-collision body stays in the message the provider matches on`() =
        runTest {
            // OneDriveProvider treats `409` + "nameAlreadyExists" in the message as a create collision.
            eachWrite(writes.filter { it.name == "uploadSimple" || it.name == "createFolder" }) { write ->
                val seen = mutableListOf<HttpRequestData>()
                val collision = Failure(HttpStatusCode.Conflict, body = """{"error":{"code":"nameAlreadyExists"}}""")
                val service = serviceOver(engine(write, listOf(collision), seen), budget())

                val e = assertFailsWith<GraphApiException>("${write.name}: collision") { write.call(service, localFile) }

                assertEquals(409, e.statusCode)
                assertTrue("nameAlreadyExists" in e.message!!, "${write.name}: message was '${e.message}'")
                service.close()
            }
        }

    @Test
    fun `a throttled upload chunk is recorded on the budget and waits for an open circuit`() =
        runTest {
            val budget = budget()
            val seen = mutableListOf<HttpRequestData>()
            var chunkAttempts = 0
            val engine =
                MockEngine { request ->
                    seen += request
                    when {
                        request.url.host == "upload.example" && chunkAttempts++ == 0 ->
                            respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "3"))
                        request.url.encodedPath.endsWith("createUploadSession") -> {
                            // A storm lands while the session is being created: the circuit is open for 12s
                            // when the first chunk is about to go out.
                            repeat(4) { budget.recordThrottle(10_000) }
                            success(request)
                        }
                        else -> success(request)
                    }
                }
            val service = serviceOver(engine, budget)
            val start = currentTime

            service.uploadLargeFile(localFile, "/big.bin")

            assertEquals(2, seen.count { it.url.host == "upload.example" }, "the throttled chunk must be sent again")
            assertTrue(currentTime - start >= 15_000, "the chunk must wait out the circuit (12s) and the hinted 3s, waited ${currentTime - start}ms")
            service.close()
        }
}
