package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.internxt.model.InternxtCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * UD-203: pin the Internxt `x-request-id` propagation contract.
 *
 * Internxt's drive-server emits `x-request-id` on every response; the
 * upstream SDK extracts it at error-normalization time as
 * `AxiosResponseError.xRequestId`. When a call fails, the resulting
 * `ProviderException` subclass must carry the value so operators can
 * grep `requestId=` on an ERROR log line and correlate with Internxt's
 * server-side traces.
 *
 * Three orthogonal invariants:
 *  1. 401 → `AuthenticationException` carries `x-request-id`.
 *  2. Non-retryable 4xx (e.g. 404) → `InternxtApiException` carries it.
 *  3. Missing `x-request-id` → exception's `requestId` is null.
 *
 * Driver: `listFiles` is the smallest public method that runs through
 * `authenticatedGet` → `checkResponse`. 401/404 are not in
 * TRANSIENT_STATUSES so the retry loop won't kick in; the exception
 * propagates immediately.
 */
class InternxtRequestIdPropagationTest {
    private fun installMockClient(
        service: InternxtApiService,
        engine: MockEngine,
    ) {
        val field = InternxtApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
    }

    private fun newService(): InternxtApiService =
        InternxtApiService(InternxtConfig()) {
            InternxtCredentials(
                jwt = "test-jwt",
                mnemonic = "test-mnemonic",
                rootFolderId = "test-root",
                email = "test@example.invalid",
            )
        }

    @Test
    fun `401 carries x-request-id on AuthenticationException`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "{\"error\":\"unauthorized\"}",
                        status = HttpStatusCode.Unauthorized,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/json"),
                                "x-request-id" to listOf("internxt-trace-abc-123"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<AuthenticationException> { service.listFiles() }

            assertEquals(
                "internxt-trace-abc-123",
                ex.requestId,
                "AuthenticationException must surface Internxt's `x-request-id` header",
            )
            service.close()
        }

    @Test
    fun `non-retryable 4xx carries x-request-id on InternxtApiException`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "{\"error\":\"not found\"}",
                        status = HttpStatusCode.NotFound,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/json"),
                                "x-request-id" to listOf("internxt-trace-def-456"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertEquals(404, ex.statusCode)
            assertEquals(
                "internxt-trace-def-456",
                ex.requestId,
                "InternxtApiException must surface Internxt's `x-request-id` header",
            )
            service.close()
        }

    @Test
    fun `missing x-request-id yields null requestId`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<html>captive portal</html>",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf("Content-Type", "text/html"),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertNull(
                ex.requestId,
                "absent x-request-id header must leave requestId null, not a fake id",
            )
            service.close()
        }
}
