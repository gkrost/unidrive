package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-231: guard against the "200 + text/html" CDN failure mode. A SharePoint / Azure edge node
 * can hand back an HTTP 200 response whose body is a branded error page (tenant throttle, login
 * redirect, expired download URL). The old download path streamed those HTML bytes straight to
 * disk at roughly the right size — the corrupt file then looked healthy to the NUL-stub sweeper
 * (UD-226) and only a quickXorHash comparison would reveal it.
 *
 * The guard in `downloadFile` inspects `Content-Type` before any write and throws `IOException`
 * when it matches `text/html`. The existing UD-309 flake-retry loop catches generic `Exception`
 * and retries the same URL up to `MAX_FLAKE_ATTEMPTS` (3) times; after exhaustion it surfaces
 * the last failure so higher layers can mark the file errored rather than corrupt-hydrated.
 *
 * Swaps the private `httpClient` field (same trick as `ListChildrenPaginationTest`) so the
 *  real `authenticatedRequest` / `HttpRetryBudget` / flake-loop code paths run and only the
 * transport is faked.
 */
class DownloadContentTypeGuardTest {
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

    /** Minimal DriveItem JSON carrying a `@microsoft.graph.downloadUrl` so the download path
     *  follows the CDN branch (anonymous GET, the same one that produces the 200+HTML failure). */
    private fun driveItemWithCdnUrl(cdnUrl: String): String =
        """{"id":"test-id","name":"photo.jpg","size":1024,"@microsoft.graph.downloadUrl":"$cdnUrl"}"""

    private val htmlErrorPage =
        """
        <!DOCTYPE html><html><head><title>Sharepoint Online</title></head>
        <body><h1>Something went wrong</h1><p>Ref A: DEADBEEF Ref B: SPO Ref C: 2026-04-19</p></body>
        </html>
        """.trimIndent()

    @Test
    fun `200 with text html content-type does not write destination file and flake loop exhausts`() =
        runTest {
            val cdnUrl = "https://fake-cdn.sharepoint.com/fake-download-url"
            var metadataCalls = 0
            var contentCalls = 0
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    if (url.contains("/drive/items/test-id") && !url.endsWith("/content")) {
                        // Metadata fetch — always returns the CDN-routed download URL.
                        metadataCalls++
                        respond(
                            content = driveItemWithCdnUrl(cdnUrl),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        // CDN content fetch — 200 + text/html (the dangerous failure mode).
                        contentCalls++
                        respond(
                            content = htmlErrorPage,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
                        )
                    }
                }

            val service = newService()
            installMockClient(service, engine)

            val tempDir = Files.createTempDirectory("ud231-guard-")
            val destPath = tempDir.resolve("photo.jpg")

            // Deliberately does NOT pre-create the file so we can assert it's never written.
            assertFalse(Files.exists(destPath), "precondition: dest must not exist")

            assertFailsWith<Exception> {
                service.downloadFile("test-id", destPath)
            }

            assertFalse(
                Files.exists(destPath),
                "UD-231: HTML-on-200 must not produce a destination file (was: exists=${Files.exists(destPath)})",
            )
            assertEquals(
                1,
                metadataCalls,
                "getItemById is called once; the flake loop reuses the cached downloadUrl across retries",
            )
            assertEquals(
                3,
                contentCalls,
                "Flake loop should retry the CDN fetch MAX_FLAKE_ATTEMPTS (3) times before surfacing",
            )
            service.close()
            // Cleanup
            runCatching { Files.deleteIfExists(destPath) }
            runCatching { Files.deleteIfExists(tempDir) }
        }

    @Test
    fun `200 with application octet-stream writes bytes verbatim`() =
        runTest {
            val cdnUrl = "https://fake-cdn.sharepoint.com/good-download"
            val payload = ByteArray(512) { (it and 0xFF).toByte() }
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    if (url.contains("/drive/items/test-id") && !url.endsWith("/content")) {
                        respond(
                            content = driveItemWithCdnUrl(cdnUrl),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                        )
                    }
                }

            val service = newService()
            installMockClient(service, engine)

            val tempDir = Files.createTempDirectory("ud231-positive-")
            val destPath = tempDir.resolve("photo.bin")

            service.downloadFile("test-id", destPath)

            assertTrue(Files.exists(destPath), "positive path must produce the destination file")
            val actual = Files.readAllBytes(destPath)
            assertEquals(payload.size, actual.size, "Downloaded size must equal served payload size")
            assertTrue(payload.contentEquals(actual), "Downloaded bytes must match served payload")
            service.close()
            runCatching { Files.deleteIfExists(destPath) }
            runCatching { Files.deleteIfExists(tempDir) }
        }
}
