package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * UD-364: pin the `/files/limits` → `maxFileSizeBytes()` contract.
 *
 * Three orthogonal invariants (per CLAUDE.md testing discipline):
 *  1. First call performs one HTTP round-trip and surfaces the
 *     server-reported cap.
 *  2. Second call returns the cached value WITHOUT another HTTP call
 *     (process-lifetime cache; user plans change rarely).
 *  3. A failed fetch does NOT cache the failure — the next call retries.
 *
 * Response shape (drive-server-wip `GetFileLimitsDto`):
 *   `{ versioning: {...}, maxUploadFileSize: number | null }`
 * `versioning` is ignored via `ignoreUnknownKeys` in [UnidriveJson].
 */
class InternxtFileLimitsTest {
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

    /**
     * Build a provider whose underlying AuthService is pre-populated with
     * dummy credentials carrying a far-future JWT exp so that
     * [InternxtApiService.authenticatedGet] proceeds without triggering
     * AuthService.refreshToken() (which would attempt a real HTTP call).
     */
    private fun newProviderWithCredentials(): InternxtProvider {
        val provider = InternxtProvider()
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        // Build a JWT with header.payload.signature where payload encodes
        // {"exp": 9999999999} (a far-future Unix epoch). isJwtExpired()
        // base64url-decodes parts[1] and reads exp.
        val payload = """{"exp":9999999999}"""
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray())
        val fakeJwt = "header.$payloadB64.signature"
        credsField.set(
            authService,
            InternxtCredentials(
                jwt = fakeJwt,
                mnemonic = "test-mnemonic",
                rootFolderId = "test-root",
                email = "test@example.invalid",
            ),
        )
        return provider
    }

    @Test
    fun `UD-364 getFileLimits deserialises maxUploadFileSize from the documented body shape`() =
        runTest {
            val body =
                """
                {
                  "versioning": {
                    "enabled": true,
                    "maxFileSize": 1048576,
                    "retentionDays": 30,
                    "maxVersions": 10
                  },
                  "maxUploadFileSize": 21474836480
                }
                """.trimIndent()
            val engine =
                MockEngine {
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val limits = service.getFileLimits()
            assertEquals(21_474_836_480L, limits.maxUploadFileSize, "20 GiB cap should round-trip")
            service.close()
        }

    @Test
    fun `UD-364 getFileLimits tolerates null maxUploadFileSize (no cap on this plan)`() =
        runTest {
            val body = """{"versioning":{"enabled":false,"maxFileSize":0,"retentionDays":0,"maxVersions":0},"maxUploadFileSize":null}"""
            val engine =
                MockEngine {
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            val service = newService()
            installMockClient(service, engine)

            val limits = service.getFileLimits()
            assertNull(limits.maxUploadFileSize, "server-reported null cap must round-trip")
            service.close()
        }

    @Test
    fun `UD-364 maxFileSizeBytes caches the first successful fetch (no second HTTP round-trip)`() {
        val callCount = AtomicInteger(0)
        val body = """{"maxUploadFileSize":5368709120}"""
        val engine =
            MockEngine {
                callCount.incrementAndGet()
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
        val provider = newProviderWithCredentials()
        // Reach the api field and swap its httpClient. Mirrors the pattern
        // used in InternxtRequestIdPropagationTest.
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        installMockClient(api, engine)

        val first = provider.maxFileSizeBytes()
        val second = provider.maxFileSizeBytes()

        assertEquals(5_368_709_120L, first, "first call returns server-reported cap")
        assertEquals(5_368_709_120L, second, "second call returns cached cap")
        assertEquals(1, callCount.get(), "second call must NOT hit the network")
        provider.close()
    }

    @Test
    fun `UD-364 maxFileSizeBytes does not cache a failed fetch (retries on next call)`() {
        // Use 502 (Bad Gateway): NOT in authenticatedGet's 500/503 retry list,
        // so it propagates immediately without sleeping — keeps the test fast.
        val callCount = AtomicInteger(0)
        val engine =
            MockEngine {
                callCount.incrementAndGet()
                respond(
                    content = "<html>oops</html>",
                    status = HttpStatusCode.BadGateway,
                    headers = headersOf("Content-Type", "text/html"),
                )
            }
        val provider = newProviderWithCredentials()
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        installMockClient(api, engine)

        val first = provider.maxFileSizeBytes()
        assertNull(first, "failed fetch returns null without poisoning the cache")
        val callsAfterFirstFail = callCount.get()
        // Second call must retry — i.e. hit the network again.
        val second = provider.maxFileSizeBytes()
        assertNull(second, "second failed fetch is also null")
        kotlin.test.assertTrue(
            callCount.get() > callsAfterFirstFail,
            "second call after failure must retry (network calls increased)",
        )
        provider.close()
    }
}
