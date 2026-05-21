package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * UD-203: pin the Graph request-id propagation contract.
 *
 * Microsoft Graph emits both `request-id` (the server-side trace id surfaced
 * in Microsoft support tickets) and `client-request-id` (echoed from the
 * request when supplied) on every response. When a call fails, the resulting
 * `ProviderException` subclass must carry the value so the operator can
 * grep `requestId=` on an ERROR log line and correlate it to Graph's own
 * traces. Four orthogonal invariants, one named test each:
 *
 * 1. **401 propagates `request-id` onto `AuthenticationException`.** A
 *    repeated 401 (refresh-revoked) carries the server's request-id all
 *    the way out of `authenticatedRequest`.
 * 2. **5xx propagates `request-id` onto `GraphApiException`.** A 500
 *    response with no Retry-After (so the retry budget doesn't kick in)
 *    surfaces the request-id on the typed exception.
 * 3. **Missing `request-id` falls back to `client-request-id`.** When
 *    Graph's edge omits the server header (unusual but observed), we use
 *    the client-supplied id so the user has something to grep on.
 * 4. **Neither header present → `requestId` is null.** Captive-portal
 *    HTML pages, MockEngine synthetic failures, and pre-flight errors
 *    don't get a fake correlation id.
 */
class GraphRequestIdPropagationTest {
    private fun installMockClient(
        service: GraphApiService,
        engine: MockEngine,
    ) {
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
    }

    private fun newService(): GraphApiService =
        GraphApiService(
            config = OneDriveConfig(),
            tokenProvider = { _ -> "test-token" },
        )

    @Test
    fun `repeated 401 carries server request-id on AuthenticationException`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "{\"error\":\"refresh revoked\"}",
                        status = HttpStatusCode.Unauthorized,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/json"),
                                "request-id" to listOf("abc-123-server-trace"),
                                "client-request-id" to listOf("zzz-not-this-one"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<AuthenticationException> { service.getDrive() }

            assertEquals(
                "abc-123-server-trace",
                ex.requestId,
                "AuthenticationException must surface Graph's `request-id` header (preferred over client-request-id)",
            )
            service.close()
        }

    @Test
    fun `5xx response carries request-id on GraphApiException`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "{\"error\":\"server boom\"}",
                        status = HttpStatusCode.InternalServerError,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/json"),
                                "request-id" to listOf("def-456-server-trace"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<GraphApiException> { service.getDrive() }

            assertEquals(500, ex.statusCode)
            assertEquals(
                "def-456-server-trace",
                ex.requestId,
                "GraphApiException must surface Graph's `request-id` header",
            )
            service.close()
        }

    @Test
    fun `missing request-id falls back to client-request-id`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "{\"error\":\"server boom\"}",
                        status = HttpStatusCode.InternalServerError,
                        // No `request-id` — only the echoed client one.
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/json"),
                                "client-request-id" to listOf("client-supplied-789"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<GraphApiException> { service.getDrive() }

            assertEquals(
                "client-supplied-789",
                ex.requestId,
                "missing `request-id` must fall back to `client-request-id`",
            )
            service.close()
        }

    @Test
    fun `neither header present yields null requestId`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<html>captive portal</html>",
                        status = HttpStatusCode.InternalServerError,
                        // No correlation headers at all — e.g. captive portal interception.
                        headers = headersOf("Content-Type", "text/html"),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<GraphApiException> { service.getDrive() }

            assertNull(
                ex.requestId,
                "absent correlation headers must leave requestId null, not a fake id",
            )
            service.close()
        }
}
