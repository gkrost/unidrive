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
import kotlin.test.assertTrue

/**
 * UD-314: verifies that `GraphApiService.listChildren` follows `@odata.nextLink` to the end,
 * so folders with more than the Graph page size (~200) are not silently truncated.
 *
 * The test swaps the service's private `httpClient` for one backed by a Ktor `MockEngine`
 * that returns canned paginated JSON. We touch only the field — the service's internal call
 *  paths (`authenticatedRequest`, `HttpRetryBudget`, `tokenProvider`) exercise their real code,
 * only the transport is faked.
 */
class ListChildrenPaginationTest {
    /** Builds N fake driveItem JSON objects sharing a simple id/name pattern. */
    private fun fakeItems(
        count: Int,
        idPrefix: String,
    ): String =
        (0 until count).joinToString(",") { i ->
            """{"id":"$idPrefix-$i","name":"item_${idPrefix}_$i"}"""
        }

    private fun pageBody(
        items: String,
        nextLink: String?,
    ): String {
        val nextPart = if (nextLink != null) ""","@odata.nextLink":"$nextLink"""" else ""
        return """{"value":[$items]$nextPart}"""
    }

    private fun installMockClient(
        service: GraphApiService,
        engine: MockEngine,
    ) {
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        // Close the real client we replace, so we don't leak its background threads.
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
    }

    private fun newService(): GraphApiService {
        // tokenProvider is invoked by authenticatedRequest; we never actually hit Graph, so
        // any non-empty token will do.
        return GraphApiService(
            config = OneDriveConfig(),
            tokenProvider = { _ -> "test-token" },
        )
    }

    @Test
    fun `listChildren follows nextLink across three pages`() =
        runTest {
            // Page 1: 200 items + nextLink pointing at page 2.
            // Page 2: 200 items + nextLink pointing at page 3.
            // Page 3: 150 items, NO nextLink — pagination stops here.
            val page1Body =
                pageBody(
                    items = fakeItems(200, "p1"),
                    nextLink = "https://graph.microsoft.com/v1.0/me/drive/root/children?\$skiptoken=PAGE2",
                )
            val page2Body =
                pageBody(
                    items = fakeItems(200, "p2"),
                    nextLink = "https://graph.microsoft.com/v1.0/me/drive/root/children?\$skiptoken=PAGE3",
                )
            val page3Body = pageBody(items = fakeItems(150, "p3"), nextLink = null)

            var callIndex = 0
            val seenUrls = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    seenUrls += request.url.toString()
                    val body =
                        when (callIndex++) {
                            0 -> page1Body
                            1 -> page2Body
                            2 -> page3Body
                            else -> error("Unexpected 4th request to Graph in pagination test")
                        }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = newService()
            installMockClient(service, engine)

            val items = service.listChildren("/")

            assertEquals(550, items.size, "Expected 550 items across 3 pages (200+200+150)")
            assertEquals(3, callIndex, "Expected exactly 3 HTTP requests (one per page)")
            // First request must include $top=999 to reduce round-trips per UD-314 fix-direction B.
            assertTrue(
                seenUrls[0].contains("\$top=999"),
                "Initial request should include \$top=999 for minimum round-trips, was: ${seenUrls[0]}",
            )
            // Subsequent requests must use the server-provided nextLink verbatim — if we rebuilt
            // the URL ourselves we'd lose the server's $skiptoken and infinite-loop on page 2.
            assertTrue(
                seenUrls[1].contains("PAGE2"),
                "Second request should follow server's nextLink (skiptoken=PAGE2), was: ${seenUrls[1]}",
            )
            assertTrue(
                seenUrls[2].contains("PAGE3"),
                "Third request should follow server's nextLink (skiptoken=PAGE3), was: ${seenUrls[2]}",
            )
            service.close()
        }

    @Test
    fun `listChildren single-page response does not fetch again`() =
        runTest {
            val body = pageBody(items = fakeItems(42, "only"), nextLink = null)
            var callCount = 0
            val engine =
                MockEngine { _ ->
                    callCount++
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = newService()
            installMockClient(service, engine)

            val items = service.listChildren("/some/folder")

            assertEquals(42, items.size)
            assertEquals(1, callCount, "Single-page response must not trigger a second request")
            service.close()
        }

    @Test
    fun `listChildren empty response returns empty list`() =
        runTest {
            val body = """{"value":[]}"""
            var callCount = 0
            val engine =
                MockEngine { _ ->
                    callCount++
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = newService()
            installMockClient(service, engine)

            val items = service.listChildren("/empty")

            assertEquals(0, items.size)
            assertEquals(1, callCount)
            service.close()
        }
}
