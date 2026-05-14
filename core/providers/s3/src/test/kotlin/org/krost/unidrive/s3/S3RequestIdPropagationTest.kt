package org.krost.unidrive.s3

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
 * UD-203: pin the S3 `x-amz-request-id` (+ `x-amz-id-2`) propagation
 * contract.
 *
 * AWS S3 emits two correlation headers on every response:
 *  - `x-amz-request-id` — primary id, surfaced to AWS support tickets.
 *    Carried on the cross-provider [org.krost.unidrive.ProviderException.requestId]
 *    field so the same log-grep pattern (`requestId=`) works across all
 *    providers.
 *  - `x-amz-id-2` — datacenter trace, useful for AWS-internal
 *    escalations. Carried separately on [S3Exception.extendedRequestId]
 *    so the cross-provider field stays clean.
 *
 * Five orthogonal invariants:
 *  1. Non-auth 4xx (404) → S3Exception carries both ids.
 *  2. 5xx → S3Exception carries both ids.
 *  3. 401 → AuthenticationException carries `x-amz-request-id`.
 *  4. 403 → AuthenticationException carries `x-amz-request-id`.
 *  5. Missing headers (captive portal HTML, MinIO without correlation)
 *     leave both ids null.
 */
class S3RequestIdPropagationTest {
    private fun installMockClient(
        service: S3ApiService,
        engine: MockEngine,
    ) {
        val field = S3ApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
    }

    private fun newService(): S3ApiService =
        S3ApiService(
            S3Config(
                bucket = "test-bucket",
                region = "us-east-1",
                endpoint = "https://s3.example.invalid",
                accessKey = "AKIAIOSFODNN7EXAMPLE",
                secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            ),
        )

    @Test
    fun `non-success 4xx surfaces x-amz-request-id and x-amz-id-2 on S3Exception`() =
        runTest {
            // Use listAll (which always routes through checkResponse) rather
            // than deleteObject — the latter is idempotent and silently
            // swallows 404. The cross-provider invariant is "non-success
            // → typed exception carries both correlation ids", and listAll
            // exercises that without bumping into delete-specific semantics.
            val engine =
                MockEngine {
                    respond(
                        content = "<Error><Code>NoSuchBucket</Code><Message>The bucket does not exist</Message></Error>",
                        status = HttpStatusCode.NotFound,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/xml"),
                                "x-amz-request-id" to listOf("AWS-PRIMARY-trace-abc"),
                                "x-amz-id-2" to listOf("DC-extended-trace-def"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<S3Exception> { service.listAll() }

            assertEquals(404, ex.statusCode)
            assertEquals("AWS-PRIMARY-trace-abc", ex.requestId, "x-amz-request-id must land on the cross-provider requestId field")
            assertEquals("DC-extended-trace-def", ex.extendedRequestId, "x-amz-id-2 must land on extendedRequestId")
            service.close()
        }

    @Test
    fun `5xx surfaces both correlation ids on S3Exception`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<Error><Code>InternalError</Code><Message>boom</Message></Error>",
                        status = HttpStatusCode.InternalServerError,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/xml"),
                                "x-amz-request-id" to listOf("AWS-PRIMARY-5xx-123"),
                                "x-amz-id-2" to listOf("DC-extended-5xx-456"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<S3Exception> { service.deleteObject("any.txt") }

            assertEquals(500, ex.statusCode)
            assertEquals("AWS-PRIMARY-5xx-123", ex.requestId)
            assertEquals("DC-extended-5xx-456", ex.extendedRequestId)
            service.close()
        }

    @Test
    fun `401 surfaces x-amz-request-id on AuthenticationException`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            "<Error><Code>InvalidAccessKeyId</Code><Message>The AWS Access Key Id you provided does not exist in our records.</Message></Error>",
                        status = HttpStatusCode.Unauthorized,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/xml"),
                                "x-amz-request-id" to listOf("AWS-PRIMARY-401-abc"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<AuthenticationException> { service.deleteObject("any.txt") }

            assertEquals("AWS-PRIMARY-401-abc", ex.requestId)
            service.close()
        }

    @Test
    fun `403 surfaces x-amz-request-id on AuthenticationException`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            "<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>",
                        status = HttpStatusCode.Forbidden,
                        headers =
                            headersOf(
                                "Content-Type" to listOf("application/xml"),
                                "x-amz-request-id" to listOf("AWS-PRIMARY-403-xyz"),
                            ),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<AuthenticationException> { service.deleteObject("any.txt") }

            assertEquals("AWS-PRIMARY-403-xyz", ex.requestId)
            service.close()
        }

    @Test
    fun `missing correlation headers yield null requestId and extendedRequestId`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<html>captive portal</html>",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf("Content-Type", "text/html"),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val ex = assertFailsWith<S3Exception> { service.deleteObject("any.txt") }

            assertNull(ex.requestId, "absent x-amz-request-id must leave the cross-provider field null")
            assertNull(ex.extendedRequestId, "absent x-amz-id-2 must leave extendedRequestId null")
            service.close()
        }
}
