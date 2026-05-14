package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * UD-806 (chunk: 401 → refresh attempt): pin the GraphApiService auth-refresh
 * contract. Three orthogonal invariants, one named test each:
 *
 * 1. **One-shot refresh.** A 401 response triggers exactly one retry, and
 *    the retry's bearer token is sourced from `tokenProvider(refreshed = true)`.
 *    The retry's response body is what the caller sees.
 * 2. **No infinite-loop on expired refresh.** When the refreshed call ALSO
 *    returns 401 (e.g. refresh-token revoked), the helper surfaces
 *    `AuthenticationException` — it does not retry a third time, does not
 *    crash, and does not return success-shaped garbage.
 * 3. **Success path is unchanged.** When the first response is success, the
 *    refresh path is never entered and `tokenProvider(refreshed = true)` is
 *    never called.
 *
 * The contract under test lives in `GraphApiService.authenticatedRequest`
 * (single-method form). Identical logic appears in the body-carrying overload
 * and in the chunked download path; pinning the single-method form here
 * documents the canonical shape — UD-806 follow-ups can iterate to other
 * call sites once this baseline is in place.
 */
class GraphAuthRefreshTest {
    private fun installMockClient(
        service: GraphApiService,
        engine: MockEngine,
    ) {
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
    }

    @Test
    fun `401 then 200 surfaces refreshed token on retry and returns refreshed response`() =
        runTest {
            val tokenCalls = mutableListOf<Boolean>()
            val requestBearers = mutableListOf<String?>()
            val attempt = AtomicInteger(0)

            val engine =
                MockEngine { request ->
                    requestBearers += request.headers[HttpHeaders.Authorization]
                    if (attempt.getAndIncrement() == 0) {
                        respond(
                            content = "{\"error\":\"token expired\"}",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """{"id":"drive-id","driveType":"personal","quota":{"total":0,"used":0,"remaining":0,"deleted":0}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    tokenProvider = { refreshed ->
                        tokenCalls += refreshed
                        if (refreshed) "refreshed-token" else "stale-token"
                    },
                )
            installMockClient(service, engine)

            // getDrive() goes through authenticatedRequest; the success branch returns its parsed body.
            val drive = service.getDrive()

            assertEquals(2, attempt.get(), "expected exactly one retry after the 401")
            assertEquals(listOf(false, true), tokenCalls, "second token request must ask for a refreshed token")
            assertEquals(
                listOf<String?>("Bearer stale-token", "Bearer refreshed-token"),
                requestBearers,
                "second request must carry the refreshed bearer token",
            )
            assertEquals("drive-id", drive.id, "caller should see the post-refresh response body")
            service.close()
        }

    @Test
    fun `401 then another 401 surfaces AuthenticationException without a third attempt`() =
        runTest {
            val attempt = AtomicInteger(0)
            val tokenCalls = mutableListOf<Boolean>()

            val engine =
                MockEngine {
                    attempt.incrementAndGet()
                    respond(
                        content = "{\"error\":\"refresh token revoked\"}",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    tokenProvider = { refreshed ->
                        tokenCalls += refreshed
                        if (refreshed) "still-bad" else "bad"
                    },
                )
            installMockClient(service, engine)

            val ex =
                assertFailsWith<AuthenticationException> {
                    service.getDrive()
                }
            assertEquals(2, attempt.get(), "401-after-refresh must NOT trigger a third attempt")
            assertEquals(listOf(false, true), tokenCalls, "both attempts must run, the second with refresh=true")
            assertTrue(
                ex.message?.contains("401") == true,
                "AuthenticationException message must name the 401; got: ${ex.message}",
            )
            service.close()
        }

    @Test
    fun `200 on the first attempt never invokes tokenProvider with refresh=true`() =
        runTest {
            val tokenCalls = mutableListOf<Boolean>()

            val engine =
                MockEngine {
                    respond(
                        content =
                            """{"id":"drive-id","driveType":"personal","quota":{"total":0,"used":0,"remaining":0,"deleted":0}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    tokenProvider = { refreshed ->
                        tokenCalls += refreshed
                        "ok-token"
                    },
                )
            installMockClient(service, engine)

            service.getDrive()

            assertEquals(listOf(false), tokenCalls, "success path must not request a refreshed token")
            service.close()
        }
}
