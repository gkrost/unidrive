package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-223: fast-bootstrap via `?token=latest`. The single-call transport assertion
 * lives here because the full-path behaviour (SyncEngine skips enumeration on
 * capability-declaring providers) is covered in `SyncEngineTest`.
 */
class DeltaFromLatestTest {
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
    fun `getDelta with fromLatest appends token=latest and honours returned cursor`() =
        runTest {
            val seenUrls = mutableListOf<String>()
            val responseBody = """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=ABC123"}"""

            val engine =
                MockEngine { request ->
                    seenUrls += request.url.toString()
                    respond(
                        content = responseBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = newService()
            installMockClient(service, engine)

            val result = service.getDelta(fromLatest = true)

            assertEquals(1, seenUrls.size)
            assertTrue(
                seenUrls[0].contains("token=latest"),
                "fromLatest=true must request ?token=latest, got: ${seenUrls[0]}",
            )
            assertTrue(result.items.isEmpty(), "Graph returns an empty item list for token=latest")
            assertNotNull(result.deltaLink)
            assertTrue(
                result.deltaLink!!.contains("token=ABC123"),
                "expected server-issued cursor to be surfaced, got: ${result.deltaLink}",
            )
            service.close()
        }

    @Test
    fun `getDelta without fromLatest does not append token=latest`() =
        runTest {
            val seenUrls = mutableListOf<String>()
            val responseBody = """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=FULL"}"""

            val engine =
                MockEngine { request ->
                    seenUrls += request.url.toString()
                    respond(
                        content = responseBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = newService()
            installMockClient(service, engine)

            service.getDelta()

            assertEquals(1, seenUrls.size)
            assertTrue(
                !seenUrls[0].contains("token=latest"),
                "default getDelta() must NOT emit token=latest, got: ${seenUrls[0]}",
            )
            service.close()
        }
}
