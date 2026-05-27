package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeltaCursorExpiredException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * UD-410: a 410 Gone on the delta endpoint means the resumed cursor aged
 * out / the drive re-keyed. `OneDriveProvider.delta()` must surface that
 * as a typed [DeltaCursorExpiredException] so the tracking engine can
 * self-heal into a full re-enumeration instead of looping forever on an
 * incomplete pass. Any other status stays a generic GraphApiException.
 *
 * Same MockEngine-swap idiom as OneDriveProviderVaultFilterTest: build a
 * real provider, replace its private `graphApi`'s httpClient with a mock.
 */
class DeltaCursorExpiredTest {
    private fun mockedProvider(
        status: HttpStatusCode,
        responseBody: String,
        includeShared: Boolean = false,
    ): OneDriveProvider {
        val engine =
            MockEngine { _ ->
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val provider = OneDriveProvider(OneDriveConfig(includeShared = includeShared))

        val freshGraphApi =
            GraphApiService(
                config = OneDriveConfig(),
                tokenProvider = { _ -> "test-token" },
            )
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(freshGraphApi) as? HttpClient)?.close()
        httpClientField.set(freshGraphApi, HttpClient(engine))

        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, freshGraphApi)
        return provider
    }

    @Test
    fun `410 Gone on a resumed delta cursor throws DeltaCursorExpiredException`() =
        runTest {
            val provider =
                mockedProvider(
                    HttpStatusCode.Gone,
                    """{"error":{"code":"resyncRequired","message":"The delta token is too old."}}""",
                )

            val ex =
                assertFailsWith<DeltaCursorExpiredException> {
                    provider.delta("https://graph.microsoft.com/v1.0/me/drive/root/delta?token=STALE")
                }
            assertTrue(
                ex.message!!.contains("410") || ex.message!!.contains("Gone"),
                "message should name the 410: ${ex.message}",
            )
            provider.close()
        }

    @Test
    fun `410 Gone on the initial delta call also surfaces the typed expired signal`() =
        runTest {
            val provider = mockedProvider(HttpStatusCode.Gone, """{"error":{"code":"resyncRequired"}}""")

            assertFailsWith<DeltaCursorExpiredException> {
                provider.delta(null)
            }
            provider.close()
        }

    @Test
    fun `a non-410 delta failure stays a generic GraphApiException`() =
        runTest {
            val provider =
                mockedProvider(HttpStatusCode.InternalServerError, """{"error":{"code":"serviceError"}}""")

            val ex =
                assertFailsWith<GraphApiException> {
                    provider.delta("https://graph.microsoft.com/v1.0/me/drive/root/delta?token=ABC")
                }
            assertTrue(ex !is DeltaCursorExpiredException, "500 must NOT be classified as cursor-expired")
            provider.close()
        }

    @Test
    fun `deltaWithShared 410 Gone surfaces as DeltaCursorExpiredException not a raw GraphApiException`() =
        runTest {
            val provider =
                mockedProvider(
                    HttpStatusCode.Gone,
                    """{"error":{"code":"resyncRequired","message":"The delta token is too old."}}""",
                    includeShared = true,
                )

            val ex =
                assertFailsWith<DeltaCursorExpiredException> {
                    provider.deltaWithShared("https://graph.microsoft.com/v1.0/me/drive/root/delta?token=STALE")
                }
            assertTrue(
                ex.message!!.contains("410") || ex.message!!.contains("Gone"),
                "message should name the 410: ${ex.message}",
            )
            provider.close()
        }
}
