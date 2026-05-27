package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the download re-resolve-and-retry-once contract that makes a live
 * OneDrive read recover instead of EIO-ing. Two failure modes the CDN
 * presents, plus the genuinely-missing case, each as one named invariant:
 *
 * 1. A stale pre-resolved `@microsoft.graph.downloadUrl` (or a drifted
 *    `remoteId`) → the content GET 404s. The provider must re-resolve the
 *    item by PATH exactly once and retry, since re-resolving by path fixes
 *    BOTH a stale download URL AND a drifted id.
 * 2. The CDN serves an HTML throttle page with HTTP 200 instead of
 *    bytes — `assertNotHtml` trips. The download path must re-resolve the
 *    download URL once and retry with the fresh URL rather than re-GETting
 *    the same stale URL.
 * 3. A genuinely-missing item 404s on every resolve and GET. The download
 *    must surface a not-found (statusCode 404) and re-resolve AT MOST once —
 *    no infinite loop.
 */
class OneDriveDownloadReresolveTest {
    private fun installMockClient(
        service: GraphApiService,
        engine: MockEngine,
    ) {
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
    }

    /**
     * Replace the provider's real `graphApi` (which is wired to a live
     * TokenManager that throws "Not authenticated" without a token file) with
     * a GraphApiService backed by the mock engine and a passthrough token
     * provider, so the provider's download path can be exercised offline.
     */
    private fun installGraphApi(
        provider: OneDriveProvider,
        engine: MockEngine,
    ): GraphApiService {
        val service =
            GraphApiService(
                config = OneDriveConfig(),
                tokenProvider = { "tok" },
            )
        installMockClient(service, engine)
        val field = OneDriveProvider::class.java.getDeclaredField("graphApi")
        field.isAccessible = true
        field.set(provider, service)
        return service
    }

    private fun jsonItem(
        id: String,
        downloadUrl: String?,
        size: Long = 4,
    ): String {
        val dl = downloadUrl?.let { ""","@microsoft.graph.downloadUrl":"$it"""" } ?: ""
        return """{"id":"$id","name":"f.bin","size":$size,"file":{"mimeType":"application/octet-stream"}$dl}"""
    }

    @Test
    fun `download_recovers_from_stale_download_url_404_by_reresolving_once`() =
        runTest {
            val getByPathCalls = AtomicInteger(0)
            val contentGets = AtomicInteger(0)

            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        // getItemByPath: provider's path resolve (and the re-resolve)
                        url.contains("/me/drive/root:/") -> {
                            val n = getByPathCalls.getAndIncrement()
                            // First resolve hands out a stale CDN url; the re-resolve a fresh one.
                            val dl = if (n == 0) "https://cdn.example/stale" else "https://cdn.example/fresh"
                            respond(
                                content = jsonItem("item-1", dl),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        // getItemById inside downloadFile — echo the same downloadUrl back.
                        url.contains("/me/drive/items/item-1") && !url.endsWith("/content") -> {
                            // Re-emit whatever the most recent path-resolve produced.
                            val dl = if (getByPathCalls.get() <= 1) "https://cdn.example/stale" else "https://cdn.example/fresh"
                            respond(
                                content = jsonItem("item-1", dl),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        // Content GET on the stale CDN url → 404 itemNotFound.
                        url == "https://cdn.example/stale" -> {
                            contentGets.incrementAndGet()
                            respond(
                                content = """{"error":{"code":"itemNotFound","message":"The resource could not be found."}}""",
                                status = HttpStatusCode.NotFound,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        // Content GET on the fresh CDN url → real bytes.
                        url == "https://cdn.example/fresh" -> {
                            contentGets.incrementAndGet()
                            respond(
                                content = "DATA",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }

            val provider = OneDriveProvider(OneDriveConfig())
            installGraphApi(provider, engine)

            val dest = Files.createTempFile("od-stale", ".bin")
            val size = provider.download("/f.bin", dest)

            assertEquals(4L, size, "download should report the re-resolved item size")
            assertEquals("DATA", Files.readString(dest), "the fresh bytes must land on disk")
            assertTrue(getByPathCalls.get() >= 2, "the item must be re-resolved by path at least once")
            provider.close()
            Files.deleteIfExists(dest)
        }

    @Test
    fun `download_recovers_from_cdn_html_throttle_by_reresolving_download_url`() =
        runTest {
            val contentGets = AtomicInteger(0)
            val itemByIdCalls = AtomicInteger(0)

            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        // getItemById inside downloadFile (and the HTML re-resolve).
                        url.contains("/me/drive/items/item-9") && !url.endsWith("/content") -> {
                            val n = itemByIdCalls.getAndIncrement()
                            val dl = if (n == 0) "https://cdn.example/html" else "https://cdn.example/bytes"
                            respond(
                                content = jsonItem("item-9", dl),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        // First content GET → HTTP 200 but an HTML throttle page.
                        url == "https://cdn.example/html" -> {
                            contentGets.incrementAndGet()
                            respond(
                                content = "<html><body>Throttled, please retry later</body></html>",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/html"),
                            )
                        }
                        // Re-resolved content GET → real bytes.
                        url == "https://cdn.example/bytes" -> {
                            contentGets.incrementAndGet()
                            respond(
                                content = "REAL",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    tokenProvider = { "tok" },
                )
            installMockClient(service, engine)

            val dest = Files.createTempFile("od-html", ".bin")
            service.downloadFile("item-9", dest)

            assertEquals("REAL", Files.readString(dest), "the post-reresolve bytes must land on disk")
            assertTrue(itemByIdCalls.get() >= 2, "an HTML page must trigger one download-URL re-resolve")
            service.close()
            Files.deleteIfExists(dest)
        }

    @Test
    fun `download_of_genuinely_missing_item_throws_after_single_reresolve`() =
        runTest {
            val getByPathCalls = AtomicInteger(0)
            val contentGets = AtomicInteger(0)

            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/me/drive/root:/") -> {
                            getByPathCalls.incrementAndGet()
                            respond(
                                content = jsonItem("gone-1", "https://cdn.example/gone"),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        url.contains("/me/drive/items/gone-1") && !url.endsWith("/content") -> {
                            respond(
                                content = jsonItem("gone-1", "https://cdn.example/gone"),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        url == "https://cdn.example/gone" -> {
                            contentGets.incrementAndGet()
                            respond(
                                content = """{"error":{"code":"itemNotFound","message":"gone"}}""",
                                status = HttpStatusCode.NotFound,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }

            val provider = OneDriveProvider(OneDriveConfig())
            installGraphApi(provider, engine)

            val dest = Files.createTempFile("od-gone", ".bin")
            val ex =
                assertFailsWith<GraphApiException> {
                    provider.download("/gone.bin", dest)
                }
            assertEquals(404, ex.statusCode, "a genuinely-missing item must surface a 404 not-found")
            // Bounded: initial resolve + exactly one re-resolve = 2 path resolves; no more.
            assertEquals(2, getByPathCalls.get(), "the item must be re-resolved AT MOST once (no infinite loop)")
            provider.close()
            Files.deleteIfExists(dest)
        }
}
