package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.onedrive.model.FileSystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `uploadSimple follows up with PATCH fileSystemInfo when supplied`() =
        runTest {
            val driveItemBody = """{"id":"item-1","name":"hello.txt","size":5}"""
            val patchedBody = """{"id":"item-1","name":"hello.txt","size":5,"fileSystemInfo":{"createdDateTime":"2026-05-19T10:00:00Z","lastModifiedDateTime":"2026-05-19T10:05:00Z"}}"""
            val seenMethods = mutableListOf<HttpMethod>()
            val seenUrls = mutableListOf<String>()
            val seenBodies = mutableListOf<String>()

            val engine =
                MockEngine { request ->
                    seenMethods += request.method
                    seenUrls += request.url.toString()
                    seenBodies += drainOutgoingContent(request.body)
                    when (request.method) {
                        HttpMethod.Put ->
                            respond(
                                content = driveItemBody,
                                status = HttpStatusCode.Created,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        HttpMethod.Patch ->
                            respond(
                                content = patchedBody,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> error("Unexpected method ${request.method}")
                    }
                }
            val service = newService()
            installMockClient(service, engine)

            val result =
                service.uploadSimple(
                    "/hello.txt",
                    "hello".toByteArray(),
                    FileSystemInfo(
                        createdDateTime = "2026-05-19T10:00:00Z",
                        lastModifiedDateTime = "2026-05-19T10:05:00Z",
                    ),
                )

            assertEquals(2, seenMethods.size, "Expected PUT then PATCH; saw $seenMethods")
            assertEquals(HttpMethod.Put, seenMethods[0])
            assertEquals(HttpMethod.Patch, seenMethods[1])
            assertTrue(seenUrls[1].endsWith("/me/drive/items/item-1"), "PATCH should target the created item by id, was: ${seenUrls[1]}")
            assertTrue(
                seenBodies[1].contains("\"fileSystemInfo\"") &&
                    seenBodies[1].contains("\"createdDateTime\":\"2026-05-19T10:00:00Z\"") &&
                    seenBodies[1].contains("\"lastModifiedDateTime\":\"2026-05-19T10:05:00Z\""),
                "PATCH body must carry both timestamps under fileSystemInfo, was: ${seenBodies[1]}",
            )
            assertEquals("item-1", result.id)
            service.close()
        }

    @Test
    fun `uploadSimple skips PATCH when fileSystemInfo is null`() =
        runTest {
            val driveItemBody = """{"id":"item-2","name":"plain.txt","size":3}"""
            var putCount = 0
            var otherCount = 0
            val engine =
                MockEngine { request ->
                    when (request.method) {
                        HttpMethod.Put -> {
                            putCount++
                            respond(driveItemBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        else -> {
                            otherCount++
                            respond("", HttpStatusCode.OK)
                        }
                    }
                }
            val service = newService()
            installMockClient(service, engine)

            service.uploadSimple("/plain.txt", "abc".toByteArray(), fileSystemInfo = null)

            assertEquals(1, putCount, "Single PUT, no follow-up")
            assertEquals(0, otherCount, "No PATCH when fileSystemInfo absent")
            service.close()
        }

    @Test
    fun `createUploadSession body includes fileSystemInfo when supplied`() =
        runTest {
            val tempFile = java.nio.file.Files.createTempFile("unidrive-fsi-large-", ".bin")
            java.nio.file.Files.write(tempFile, "chunky".toByteArray())
            val driveItemBody = """{"id":"item-3","name":"chunky.bin","size":6}"""
            val sessionBody = """{"uploadUrl":"https://upload.example.com/session/abc","expirationDateTime":"2030-01-01T00:00:00Z"}"""
            var createBody: String? = null
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("createUploadSession") -> {
                            createBody = drainOutgoingContent(request.body)
                            respond(sessionBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        url.startsWith("https://upload.example.com/") ->
                            respond(driveItemBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> error("Unexpected URL: $url")
                    }
                }
            val service = newService()
            installMockClient(service, engine)

            service.uploadLargeFile(
                localPath = tempFile,
                remotePath = "/chunky.bin",
                onProgress = null,
                fileSystemInfo =
                    FileSystemInfo(
                        createdDateTime = "2026-05-19T11:00:00Z",
                        lastModifiedDateTime = "2026-05-19T11:30:00Z",
                    ),
            )

            val body = checkNotNull(createBody) { "createUploadSession request not observed" }
            assertTrue(body.contains("\"fileSystemInfo\""), "createUploadSession body must include fileSystemInfo block, was: $body")
            assertTrue(body.contains("\"createdDateTime\":\"2026-05-19T11:00:00Z\""), "Body must carry creation time, was: $body")
            assertTrue(body.contains("\"lastModifiedDateTime\":\"2026-05-19T11:30:00Z\""), "Body must carry modified time, was: $body")
            assertTrue(body.contains("\"@microsoft.graph.conflictBehavior\":\"replace\""), "Existing conflictBehavior must remain, was: $body")
            service.close()
            java.nio.file.Files.deleteIfExists(tempFile)
        }

    @Test
    fun `createUploadSession body omits fileSystemInfo when null`() =
        runTest {
            val tempFile = java.nio.file.Files.createTempFile("unidrive-fsi-null-", ".bin")
            java.nio.file.Files.write(tempFile, "x".toByteArray())
            val driveItemBody = """{"id":"item-4","name":"x.bin","size":1}"""
            val sessionBody = """{"uploadUrl":"https://upload.example.com/session/x","expirationDateTime":"2030-01-01T00:00:00Z"}"""
            var createBody: String? = null
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("createUploadSession") -> {
                            createBody = drainOutgoingContent(request.body)
                            respond(sessionBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        url.startsWith("https://upload.example.com/") ->
                            respond(driveItemBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> error("Unexpected URL: $url")
                    }
                }
            val service = newService()
            installMockClient(service, engine)

            service.uploadLargeFile(localPath = tempFile, remotePath = "/x.bin", onProgress = null, fileSystemInfo = null)

            val body = checkNotNull(createBody)
            assertFalse(body.contains("fileSystemInfo"), "createUploadSession body must omit fileSystemInfo when caller didn't supply one, was: $body")
            service.close()
            java.nio.file.Files.deleteIfExists(tempFile)
        }

    private suspend fun drainOutgoingContent(content: io.ktor.http.content.OutgoingContent): String =
        when (content) {
            // Ktor's defaultTransformers wraps String/ByteArray bodies in anonymous
            // OutgoingContent.ByteArrayContent subclasses — match the abstract nested base,
            // not the top-level concrete ByteArrayContent.
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> String(content.bytes(), Charsets.UTF_8)
            is io.ktor.http.content.TextContent -> content.text
            is io.ktor.http.content.OutgoingContent.ReadChannelContent ->
                String(content.readFrom().toByteArray(), Charsets.UTF_8)
            is io.ktor.http.content.OutgoingContent.WriteChannelContent ->
                kotlinx.coroutines.coroutineScope {
                    val channel = io.ktor.utils.io.ByteChannel(autoFlush = true)
                    val writeJob =
                        this.async(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                content.writeTo(channel)
                            } finally {
                                channel.flushAndClose()
                            }
                        }
                    val bytes = channel.toByteArray()
                    writeJob.await()
                    String(bytes, Charsets.UTF_8)
                }
            else -> error("Unexpected OutgoingContent: ${content.javaClass}")
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
